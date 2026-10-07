package dev.mooner.neonjs.interop

import dev.mooner.neonjs.runtime.*
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.Type
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicInteger

/**
 * Receives calls of overridden methods of a generated adapter class (see [Adapters]). Implementations run the JS
 * function under the owning context's lock.
 */
interface AdapterDelegate {
    /** Calls method #[index] of the adapter class; returns [AdapterSupport.NOT_HANDLED] to use the super implementation. */
    fun invoke(self: Any, index: Int, args: Array<Any?>): Any?
}

object AdapterSupport {
    /** Returned by [AdapterDelegate.invoke] when JS does not implement the method. */
    @JvmField val NOT_HANDLED = Any()
}

/** Structure of a generated adapter class. */
class AdapterClass(
    val cls: Class<*>,
    /** Overridable methods, indexed as passed to [AdapterDelegate.invoke]. */
    val methods: List<Method>,
    /** (base constructor, adapter constructor taking the delegate first). */
    val constructors: List<Pair<Constructor<*>, Constructor<*>>>,
    /** Generated `super$` bridges by JS method name, for Java.super(). */
    val superMethods: Map<String, List<Method>>,
)

/**
 * Generates JVM subclasses of host classes / implementations of several interfaces whose methods are implemented by
 * JS objects (`Java.extend`). Each class lives in its own class loader so it can be unloaded with its context (except
 * classes implementing interfaces on Android 8.0 and 8.1, which stay loaded: see [dev.mooner.neonjs.jit.ArtCha]).
 */
object Adapters {
    private val counter = AtomicInteger()
    private const val DELEGATE_FIELD = "__neonDelegate"
    private val DELEGATE = Type.getInternalName(AdapterDelegate::class.java)
    private val SUPPORT = Type.getInternalName(AdapterSupport::class.java)

    /**
     * Parent of an adapter class's loader: the extended types' loader, except for the engine types the adapter calls,
     * which that loader may not see.
     */
    private class BridgeLoader(parent: ClassLoader?) : ClassLoader(parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name == AdapterDelegate::class.java.name) return AdapterDelegate::class.java
            if (name == AdapterSupport::class.java.name) return AdapterSupport::class.java
            return super.loadClass(name, resolve)
        }
    }

    /** Validates [types] (at most one class, the rest interfaces) and generates the adapter, defined by [definer]. */
    fun generate(types: List<Class<*>>, definer: dev.mooner.neonjs.jit.CodeDefiner = dev.mooner.neonjs.jit.CodeDefiners.default): AdapterClass {
        val base = types.firstOrNull { !it.isInterface } ?: Any::class.java
        if (types.count { !it.isInterface } > 1) throw JSException.typeError("Java.extend: at most one class may be extended")
        if (Modifier.isFinal(base.modifiers)) throw JSException.typeError("Java.extend: ${base.name} is final")
        if (!Modifier.isPublic(base.modifiers)) throw JSException.typeError("Java.extend: ${base.name} is not public")
        val ifaces = LinkedHashSet<Class<*>>()
        fun addIface(i: Class<*>) {
            if (ifaces.add(i)) i.interfaces.forEach { addIface(it) }
        }
        types.filter { it.isInterface }.forEach { addIface(it) }
        val ctors = base.declaredConstructors.filter { Modifier.isPublic(it.modifiers) || Modifier.isProtected(it.modifiers) }
        if (ctors.isEmpty()) throw JSException.typeError("Java.extend: ${base.name} has no accessible constructor")

        val methods = collectMethods(base, ifaces)
        val name = "dev.mooner.neonjs.interop.gen.NeonAdapter${counter.incrementAndGet()}"
        val internal = name.replace('.', '/')
        val cw = object : ClassWriter(COMPUTE_FRAMES or COMPUTE_MAXS) {
            // the generated code never merges distinct reference types; avoid loading classes here
            override fun getCommonSuperClass(type1: String, type2: String): String = "java/lang/Object"
        }
        cw.visit(minOf(V21, definer.classFileVersion), ACC_PUBLIC or ACC_SUPER or ACC_SYNTHETIC, internal, null, Type.getInternalName(base),
            ifaces.map { Type.getInternalName(it) }.toTypedArray())
        cw.visitField(ACC_PRIVATE or ACC_FINAL, DELEGATE_FIELD, "L$DELEGATE;", null, null).visitEnd()

        for (c in ctors) {
            val pts = c.parameterTypes.map { Type.getType(it) }
            val desc = Type.getMethodDescriptor(Type.VOID_TYPE, Type.getObjectType(DELEGATE), *pts.toTypedArray())
            val mv = cw.visitMethod(ACC_PUBLIC, "<init>", desc, null, null)
            mv.visitCode()
            mv.visitVarInsn(ALOAD, 0)
            var slot = 2
            for (t in pts) {
                mv.visitVarInsn(t.getOpcode(ILOAD), slot)
                slot += t.size
            }
            mv.visitMethodInsn(INVOKESPECIAL, Type.getInternalName(base), "<init>", Type.getConstructorDescriptor(c), false)
            mv.visitVarInsn(ALOAD, 0)
            mv.visitVarInsn(ALOAD, 1)
            mv.visitFieldInsn(PUTFIELD, internal, DELEGATE_FIELD, "L$DELEGATE;")
            mv.visitInsn(RETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }

        for ((index, m) in methods.withIndex()) {
            emitOverride(cw, internal, base, m, index)
            if (!Modifier.isAbstract(m.modifiers)) emitSuperBridge(cw, base, m, index)
        }
        cw.visitEnd()

        val parent = (listOf(base) + ifaces).firstNotNullOfOrNull { it.classLoader } ?: Adapters::class.java.classLoader
        val cls = try {
            definer.define(name, cw.toByteArray(), BridgeLoader(parent))
        } catch (e: LinkageError) {
            throw JSException.typeError("Java.extend: cannot create adapter for ${base.name}: ${e.message}")
        } catch (e: RuntimeException) {
            if (e is JSException) throw e
            throw JSException.typeError("Java.extend: cannot create adapter for ${base.name} on this platform: ${e.message}")
        }
        // it may be the only implementation of an interface method
        if (ifaces.isNotEmpty()) dev.mooner.neonjs.jit.ArtCha.pinIfNeeded(cls)
        val genCtors = cls.declaredConstructors.associateBy { it.parameterTypes.drop(1) }
        val pairs = ctors.map { it to genCtors.getValue(it.parameterTypes.toList()) }
        val supers = HashMap<String, MutableList<Method>>()
        for ((index, m) in methods.withIndex()) {
            if (Modifier.isAbstract(m.modifiers)) continue
            val sm = cls.getDeclaredMethod(superName(m, index), *m.parameterTypes)
            supers.getOrPut(m.name) { ArrayList() }.add(sm)
        }
        return AdapterClass(cls, methods, pairs, supers)
    }

    private fun superName(m: Method, index: Int) = $$"super$$${m.name}$$$index"

    /** Public/protected overridable instance methods (Object methods limited to toString/hashCode/equals). */
    private fun collectMethods(base: Class<*>, ifaces: Collection<Class<*>>): List<Method> {
        val seen = HashMap<String, Method>()
        fun consider(m: Method) {
            val mod = m.modifiers
            if (Modifier.isStatic(mod) || Modifier.isFinal(mod) || m.isSynthetic || m.isBridge) return
            if (!(Modifier.isPublic(mod) || Modifier.isProtected(mod))) return
            if (m.declaringClass == Any::class.java && m.name !in setOf("toString", "hashCode", "equals")) return
            val key = m.name + Type.getMethodDescriptor(m)
            if (key !in seen) seen[key] = m
        }
        var c: Class<*>? = base
        val finals = HashSet<String>()
        while (c != null) {
            for (m in c.declaredMethods) {
                val key = m.name + Type.getMethodDescriptor(m)
                if (Modifier.isFinal(m.modifiers) || Modifier.isPrivate(m.modifiers)) finals.add(key)
                if (key in finals && !Modifier.isFinal(m.modifiers)) continue
                consider(m)
            }
            c = c.superclass
        }
        for (i in ifaces) for (m in i.methods) {
            val key = m.name + Type.getMethodDescriptor(m)
            if (key !in finals) consider(m)
        }
        return seen.values.sortedBy { it.name + Type.getMethodDescriptor(it) }
    }

    private fun emitOverride(cw: ClassWriter, internal: String, base: Class<*>, m: Method, index: Int) {
        val desc = Type.getMethodDescriptor(m)
        val access = if (Modifier.isPublic(m.modifiers)) ACC_PUBLIC else ACC_PROTECTED
        val mv = cw.visitMethod(access, m.name, desc, null, m.exceptionTypes.map { Type.getInternalName(it) }.toTypedArray())
        mv.visitCode()
        val args = Type.getArgumentTypes(desc)
        val ret = Type.getReturnType(desc)
        var resultSlot = 1
        for (t in args) resultSlot += t.size
        val callSuper = Label()
        // delegate is null while the base constructor runs
        mv.visitVarInsn(ALOAD, 0)
        mv.visitFieldInsn(GETFIELD, internal, DELEGATE_FIELD, "L$DELEGATE;")
        mv.visitJumpInsn(IFNULL, callSuper)
        mv.visitVarInsn(ALOAD, 0)
        mv.visitFieldInsn(GETFIELD, internal, DELEGATE_FIELD, "L$DELEGATE;")
        mv.visitVarInsn(ALOAD, 0)
        pushInt(mv, index)
        pushInt(mv, args.size)
        mv.visitTypeInsn(ANEWARRAY, "java/lang/Object")
        var slot = 1
        for ((i, t) in args.withIndex()) {
            mv.visitInsn(DUP)
            pushInt(mv, i)
            mv.visitVarInsn(t.getOpcode(ILOAD), slot)
            box(mv, t)
            mv.visitInsn(AASTORE)
            slot += t.size
        }
        mv.visitMethodInsn(INVOKEINTERFACE, DELEGATE, "invoke", "(Ljava/lang/Object;I[Ljava/lang/Object;)Ljava/lang/Object;", true)
        mv.visitVarInsn(ASTORE, resultSlot)
        mv.visitVarInsn(ALOAD, resultSlot)
        mv.visitFieldInsn(GETSTATIC, SUPPORT, "NOT_HANDLED", "Ljava/lang/Object;")
        mv.visitJumpInsn(IF_ACMPEQ, callSuper)
        if (ret == Type.VOID_TYPE) {
            mv.visitInsn(RETURN)
        } else {
            mv.visitVarInsn(ALOAD, resultSlot)
            unbox(mv, ret)
            mv.visitInsn(ret.getOpcode(IRETURN))
        }
        mv.visitLabel(callSuper)
        if (Modifier.isAbstract(m.modifiers)) {
            mv.visitTypeInsn(NEW, "java/lang/AbstractMethodError")
            mv.visitInsn(DUP)
            mv.visitLdcInsn("${m.declaringClass.simpleName}.${m.name} is not implemented by the JS adapter")
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/AbstractMethodError", "<init>", "(Ljava/lang/String;)V", false)
            mv.visitInsn(ATHROW)
        } else {
            invokeSuper(mv, base, m, args)
            mv.visitInsn(ret.getOpcode(IRETURN))
        }
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun invokeSuper(mv: org.objectweb.asm.MethodVisitor, base: Class<*>, m: Method, args: Array<Type>) {
        mv.visitVarInsn(ALOAD, 0)
        var slot = 1
        for (t in args) {
            mv.visitVarInsn(t.getOpcode(ILOAD), slot)
            slot += t.size
        }
        val dc = m.declaringClass
        if (dc.isInterface) {
            mv.visitMethodInsn(INVOKESPECIAL, Type.getInternalName(dc), m.name, Type.getMethodDescriptor(m), true)
        } else {
            mv.visitMethodInsn(INVOKESPECIAL, Type.getInternalName(base), m.name, Type.getMethodDescriptor(m), false)
        }
    }

    private fun emitSuperBridge(cw: ClassWriter, base: Class<*>, m: Method, index: Int) {
        val desc = Type.getMethodDescriptor(m)
        // synthetic: not visible as a JS member (HostAccess skips synthetic methods); reached through Java.super()
        val mv = cw.visitMethod(ACC_PUBLIC or ACC_SYNTHETIC, superName(m, index), desc, null, null)
        mv.visitCode()
        invokeSuper(mv, base, m, Type.getArgumentTypes(desc))
        mv.visitInsn(Type.getReturnType(desc).getOpcode(IRETURN))
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }

    private fun pushInt(mv: org.objectweb.asm.MethodVisitor, v: Int) {
        when (v) {
            in -1..5 -> mv.visitInsn(ICONST_0 + v)
            in Byte.MIN_VALUE..Byte.MAX_VALUE -> mv.visitIntInsn(BIPUSH, v)
            in Short.MIN_VALUE..Short.MAX_VALUE -> mv.visitIntInsn(SIPUSH, v)
            else -> mv.visitLdcInsn(v)
        }
    }

    private fun boxType(t: Type): Pair<String, String>? = when (t.sort) {
        Type.BOOLEAN -> "java/lang/Boolean" to "booleanValue"
        Type.BYTE -> "java/lang/Byte" to "byteValue"
        Type.CHAR -> "java/lang/Character" to "charValue"
        Type.SHORT -> "java/lang/Short" to "shortValue"
        Type.INT -> "java/lang/Integer" to "intValue"
        Type.LONG -> "java/lang/Long" to "longValue"
        Type.FLOAT -> "java/lang/Float" to "floatValue"
        Type.DOUBLE -> "java/lang/Double" to "doubleValue"
        else -> null
    }

    private fun box(mv: org.objectweb.asm.MethodVisitor, t: Type) {
        val (owner, _) = boxType(t) ?: return
        mv.visitMethodInsn(INVOKESTATIC, owner, "valueOf", "(${t.descriptor})L$owner;", false)
    }

    private fun unbox(mv: org.objectweb.asm.MethodVisitor, t: Type) {
        val b = boxType(t)
        if (b == null) {
            mv.visitTypeInsn(CHECKCAST, t.internalName)
            return
        }
        mv.visitTypeInsn(CHECKCAST, b.first)
        mv.visitMethodInsn(INVOKEVIRTUAL, b.first, b.second, "()${t.descriptor}", false)
    }
}

/** Runs adapter methods implemented by a JS object. */
class JSAdapterDelegate(private val bridge: HostBridge, private val impl: JSObject, private val adapter: AdapterClass) : AdapterDelegate {
    override fun invoke(self: Any, index: Int, args: Array<Any?>): Any? = bridge.gate.enter {
        val m = adapter.methods[index]
        val fn = impl.get(PK.fromString(m.name), impl)
        if (!Ops.isCallable(fn)) return@enter AdapterSupport.NOT_HANDLED
        val jsArgs = Array(args.size) { bridge.toJS(args[it]) }
        val r = (fn as JSObject).call(bridge.toJS(self), jsArgs)
        if (m.returnType == Void.TYPE) null else bridge.toHost(r, m.returnType, m.genericReturnType)
    }
}

/** The JS view of an adapter class: `new Adapter(...ctorArgs)` (class-level impl) or `new Adapter(impl, ...ctorArgs)`. */
class AdapterClassObject(
    @JvmField val bridge: HostBridge,
    @JvmField val adapter: AdapterClass,
    private val classImpl: JSObject?,
    private val displayName: String,
) : JSObject(bridge.realm.functionPrototype) {
    init {
        special = special or SPECIAL_ALL or CALLABLE or CONSTRUCTOR
        extensible = false
        defineOwn("name", displayName, Attr.CONFIGURABLE)
    }

    override val className: String get() = "Function"

    override fun getOwnValue(key: Any, receiver: Any?): Any? {
        if (key === JSSymbol.hasInstance) {
            return NativeFunction(bridge.realm, "[Symbol.hasInstance]", 1, { _, _, a, _ ->
                val v = a.arg(0)
                v is HostObject && adapter.cls.isInstance(v.target)
            })
        }
        return super.getOwnValue(key, receiver)
    }

    override fun get(key: Any, receiver: Any?): Any? {
        val v = getOwnValue(key, receiver)
        return if (v !== NotFound) v else proto?.get(key, receiver) ?: Undefined
    }

    override fun set(key: Any, value: Any?, receiver: Any?): Boolean = false

    override fun call(thisArg: Any?, args: Array<Any?>): Any =
        throw JSException.typeError("Adapter class $displayName must be invoked with 'new'")

    override fun construct(args: Array<Any?>, newTarget: JSObject): Any? {
        val impl: JSObject
        val ctorArgs: Array<Any?>
        if (classImpl != null) {
            impl = classImpl
            ctorArgs = args
        } else {
            impl = args.arg(0) as? JSObject ?: throw JSException.typeError("$displayName: the first argument must be an object implementing the methods")
            ctorArgs = if (args.isEmpty()) args else args.copyOfRange(1, args.size)
        }
        val base = bridge.select(adapter.constructors.map { it.first }, ctorArgs)
            ?: throw JSException.typeError("No applicable constructor for $displayName with arguments (${ctorArgs.joinToString { Ops.typeOf(it) }})")
        val gen = adapter.constructors.first { it.first == base }.second
        val conv = bridge.convertArgs(base, ctorArgs)
        val instance = try {
            gen.newInstance(JSAdapterDelegate(bridge, impl, adapter), *conv)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw bridge.hostError(e.targetException)
        }
        return bridge.toJS(instance)
    }

    /** `Java.super(instance)`: an object whose methods run the base-class implementations. */
    fun superOf(instance: HostObject): JSObject {
        val o = JSObject(bridge.realm.objectPrototype)
        for ((name, list) in adapter.superMethods) o.defineOwn(name, HostMethodFunction(bridge, name, list, instance.target), Attr.WC)
        return o
    }
}
