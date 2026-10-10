package dev.mooner.neonjs.interop

import dev.mooner.neonjs.runtime.*
import dev.mooner.neonjs.vm.Iteration
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Callable wrapping one or more host method overloads, optionally bound to a receiver. */
class HostMethodFunction(
    @JvmField val bridge: HostBridge,
    name: String,
    @JvmField val overloads: List<Method>,
    @JvmField val target: Any?,
) : JSFunction(bridge.realm, bridge.realm.functionPrototype) {
    private var sigs: Array<HostBridge.Sig>? = null

    init {
        defineOwn("length", (overloads.minOfOrNull { it.parameterCount } ?: 0).toDouble(), Attr.CONFIGURABLE)
        defineOwn("name", name, Attr.CONFIGURABLE)
    }

    override fun call(thisArg: Any?, args: Array<Any?>): Any? {
        var recv = target
        if (recv == null && !Modifier.isStatic(overloads[0].modifiers)) {
            // unbound instance method: use thisArg
            recv = (thisArg as? HostObject)?.target ?: throw JSException.typeError("Host method ${debugName()} called on incompatible receiver")
        }
        val s = sigs ?: bridge.sigs(overloads).also { sigs = it }
        val i = bridge.selectIndex(s, args)
        if (i < 0) throw JSException.typeError("No applicable overload for ${debugName()} with arguments (${args.joinToString { Ops.typeOf(it) }})")
        return bridge.invoke(overloads[i], s[i], recv, args)
    }

    override fun sourceText(): String = "function ${debugName()}() { [native code] }"
}

/**
 * Exotic object exposing a host (Java/Kotlin) object's public members as properties: fields, methods, and
 * bean-style properties (getX/isX/setX, i.e. Kotlin properties). Java arrays and Lists are array-like; Maps expose
 * entries through get/set; Iterables are JS-iterable. Functions (lambdas, `@FunctionalInterface` and Kotlin function
 * type implementations, see [HostClassInfo.findFunctionalMethod]) are callable. A signature key such as
 * `append(java.lang.String)` names one overload ([HostClassInfo.overload]).
 */
class HostObject(@JvmField val bridge: HostBridge, @JvmField val target: Any, @JvmField val info: HostClassInfo) :
    JSObject(bridge.realm.objectPrototype) {

    private var methodCache: HashMap<String, HostMethodFunction>? = null

    init {
        special = special or SPECIAL_ALL
        if (info.functionalMethod != null) special = special or CALLABLE
        extensible = false
    }

    override val className: String get() = if (target.javaClass.isArray || (target is List<*> && bridge.access.allowListAccess)) "Array" else "Object"

    private val access get() = bridge.access
    private fun isArrayLike() = (target.javaClass.isArray && access.allowArrayAccess) || (target is List<*> && access.allowListAccess)

    private fun length(): Int = when (target) {
        is List<*> -> target.size
        else -> java.lang.reflect.Array.getLength(target)
    }

    private fun element(i: Int): Any? = when (target) {
        is List<*> -> target[i]
        else -> java.lang.reflect.Array.get(target, i)
    }

    private fun method(name: String): HostMethodFunction? {
        val list = info.instanceMethods[name] ?: info.overload(name, static = false)?.let { listOf(it) } ?: return null
        var mc = methodCache
        if (mc == null) {
            mc = HashMap()
            methodCache = mc
        }
        return mc.getOrPut(name) { HostMethodFunction(bridge, name.substringBefore('('), list, target) }
    }

    private fun isMap() = target is Map<*, *> && access.allowMapAccess

    private fun isIterable() = ((target is Iterable<*> || target is Iterator<*>) && access.allowIterableAccess) || isArrayLike()

    /** True if [name] is a Java member (members take precedence over Map entries). */
    private fun isMember(name: String) = name in info.instanceFields || name in info.instanceMethods || name in info.instanceGetters

    /** Own member value or NotFound. */
    private fun member(key: Any): Any? {
        if (key is Int) {
            if (isArrayLike() && key < length()) return bridge.toJS(element(key))
            if (isMap()) return mapEntry(key.toString())
            return NotFound
        }
        if (key is JSSymbol) {
            if (key === JSSymbol.iterator && isIterable()) return iteratorFunction()
            return NotFound
        }
        val name = key as String
        if (name == "length" && isArrayLike()) return length().toDouble()
        info.instanceFields[name]?.let { f -> return bridge.toJS(read(f)) }
        method(name)?.let { return it }
        info.instanceGetters[name]?.let { g -> return bridge.invoke(g, target, EMPTY_ARGS) }
        if (isMap()) return mapEntry(name)
        return NotFound
    }

    /** Java Map entries are visible as properties (Rhino-style) when the key is not a Java member name. */
    private fun mapEntry(name: String): Any? {
        @Suppress("UNCHECKED_CAST") val m = target as Map<Any?, Any?>
        val v = try {
            if (!m.containsKey(name)) return NotFound
            m[name]
        } catch (e: RuntimeException) {
            throw bridge.hostError(e)
        }
        return bridge.toJS(v)
    }

    private fun read(f: Field): Any? = try {
        f.get(target)
    } catch (_: IllegalAccessException) {
        throw JSException.typeError("Cannot access field ${f.name}")
    }

    private fun iteratorFunction(): JSObject = NativeFunction(bridge.realm, "[Symbol.iterator]", 0, { f, _, _, _ ->
        val it: Iterator<Any?> = when (target) {
            is Iterable<*> -> target.iterator()
            is Iterator<*> -> target
            else -> {
                // live index-based view over arrays / lists
                var i = 0
                object : Iterator<Any?> {
                    override fun hasNext() = i < length()
                    override fun next(): Any? = element(i++)
                }
            }
        }
        val o = JSObject(f.realm.iteratorPrototype)
        o.defineOwn("next", NativeFunction(f.realm, "next", 0, { nf, _, _, _ ->
            if (it.hasNext()) Iteration.createIterResult(nf.realm, bridge.toJS(it.next()), false)
            else Iteration.createIterResult(nf.realm, Undefined, true)
        }), Attr.WC)
        o
    })

    override fun getOwnValue(key: Any, receiver: Any?): Any? = member(key)

    override fun get(key: Any, receiver: Any?): Any? {
        val v = member(key)
        if (v !== NotFound) return v
        return proto?.get(key, receiver) ?: Undefined
    }

    /**
     * Attributes follow JS conventions: elements and Map entries are enumerable; methods and `length` are not; fields
     * and bean properties are enumerable except on collections (whose enumerable keys are just their entries).
     */
    override fun getOwnProperty(key: Any): PropertyDescriptor? {
        val v = member(key)
        if (v === NotFound) return null
        val collection = isMap() || isArrayLike()
        val attrs = when (key) {
            is Int -> Attr.WRITABLE or Attr.ENUMERABLE
            is String -> {
                val f = info.instanceFields[key]
                when {
                    key == "length" && isArrayLike() -> 0
                    f != null -> (if (Modifier.isFinal(f.modifiers)) 0 else Attr.WRITABLE) or (if (collection) 0 else Attr.ENUMERABLE)
                    key in info.instanceMethods || info.overload(key, static = false) != null -> 0
                    key in info.instanceGetters -> (if (key in info.instanceSetters) Attr.WRITABLE else 0) or (if (collection) 0 else Attr.ENUMERABLE)
                    else -> Attr.WRITABLE or Attr.ENUMERABLE // Map entry
                }
            }
            else -> 0
        }
        return PropertyDescriptor.data(v, attrs)
    }

    override fun hasOwnProperty(key: Any): Boolean = member(key) !== NotFound

    override fun hasProperty(key: Any): Boolean = hasOwnProperty(key) || (proto?.hasProperty(key) ?: false)

    override fun set(key: Any, value: Any?, receiver: Any?): Boolean {
        if (receiver !== this) return false
        if (key is Int && isArrayLike()) {
            if (key >= length()) return false
            return try {
                when (target) {
                    is MutableList<*> -> {
                        @Suppress("UNCHECKED_CAST") val list = target as MutableList<Any?>
                        list[key] = bridge.toObject(value)
                    }
                    else -> java.lang.reflect.Array.set(target, key, bridge.toHost(value, target.javaClass.componentType))
                }
                true
            } catch (_: UnsupportedOperationException) {
                false
            } catch (e: IllegalArgumentException) {
                throw JSException.typeError("Cannot store ${Ops.describe(value)} in a ${target.javaClass.componentType.name} array: ${e.message}")
            }
        }
        if (key is Int && isMap()) return mapPut(key.toString(), value)
        if (key !is String) return false
        if (isMap() && !isMember(key)) return mapPut(key, value)
        val f = info.instanceFields[key]
        if (f != null) {
            if (Modifier.isFinal(f.modifiers)) return false
            try {
                f.set(target, bridge.toField(value, f))
            } catch (_: IllegalAccessException) {
                return false
            } catch (e: IllegalArgumentException) {
                throw JSException.typeError("Cannot set field $key: ${e.message}")
            }
            return true
        }
        val setters = info.instanceSetters[key]
        if (setters != null) {
            val m = bridge.select(setters, arrayOf(value)) ?: throw JSException.typeError("Cannot convert value for property $key")
            bridge.invoke(m, target, arrayOf(value))
            return true
        }
        return false
    }

    override fun defineOwnProperty(key: Any, desc: PropertyDescriptor): Boolean {
        if (desc.isAccessor) return false
        if (!desc.hasValue) return hasOwnProperty(key)
        return set(key, desc.value, this)
    }

    private fun mapPut(name: String, value: Any?): Boolean {
        @Suppress("UNCHECKED_CAST") val m = target as MutableMap<Any?, Any?>
        return try {
            m[name] = bridge.toObject(value)
            true
        } catch (_: UnsupportedOperationException) {
            false
        } catch (e: RuntimeException) {
            throw bridge.hostError(e)
        }
    }

    override fun delete(key: Any): Boolean {
        if (isMap() && (key is String && !isMember(key) || key is Int)) {
            val name = key.toString()
            @Suppress("UNCHECKED_CAST") val m = target as MutableMap<Any?, Any?>
            if (!m.containsKey(name)) return true
            return try {
                m.remove(name)
                true
            } catch (_: UnsupportedOperationException) {
                false
            }
        }
        return !hasOwnProperty(key)
    }

    override fun ownPropertyKeys(): MutableList<Any> {
        val out = ArrayList<Any>()
        if (isArrayLike()) {
            for (i in 0 until length()) out.add(i)
            out.add("length")
        }
        if (isMap()) {
            var n = 0
            for (k in (target as Map<*, *>).keys) {
                if (k is String && !isMember(k)) out.add(PK.fromString(k))
                if (++n and 1023 == 0) bridge.realm.agent.checkInterrupt()
            }
        }
        out.addAll(info.instanceFields.keys)
        out.addAll(info.instanceGetters.keys)
        out.addAll(info.instanceMethods.keys)
        return out
    }

    override fun setPrototypeOf(p: JSObject?): Boolean = p === proto
    override fun preventExtensions(): Boolean = true
    override fun isExtensible(): Boolean = false

    override fun call(thisArg: Any?, args: Array<Any?>): Any? {
        val m = info.functionalMethod ?: throw JSException.typeError("Host object is not callable")
        val all = info.callOverloads ?: (bridge.classInfo(m.declaringClass).instanceMethods[m.name]
            ?.filter { it.parameterCount == m.parameterCount }?.ifEmpty { null } ?: listOf(m)).also { info.callOverloads = it }
        val chosen = bridge.select(all, args) ?: m
        if (chosen.parameterCount != args.size && !chosen.isVarArgs) {
            val padded = Array(chosen.parameterCount) { i -> if (i < args.size) args[i] else Undefined }
            return bridge.invoke(chosen, target, padded)
        }
        return bridge.invoke(chosen, target, args)
    }

    override fun toString(): String = "HostObject[${target.javaClass.name}]"
}

/** JS view of a host class: static members, constructor (`new`), instanceof checks and nested classes. */
class HostClassObject(@JvmField val bridge: HostBridge, @JvmField val cls: Class<*>) : JSObject(bridge.realm.functionPrototype) {
    @JvmField val info = bridge.classInfo(cls)
    private var methodCache = HashMap<String, HostMethodFunction>()

    /** Kotlin companion object (its members are also visible on the class, like @JvmStatic ones). */
    private val companion: HostObject? by lazy {
        val f = info.staticFields["Companion"] ?: return@lazy null
        val c = try { f.get(null) } catch (_: IllegalAccessException) { null } ?: return@lazy null
        bridge.toJS(c) as? HostObject
    }

    init {
        special = special or SPECIAL_ALL or CALLABLE
        if (info.constructors.isNotEmpty() || (cls.isInterface && bridge.access.allowImplementations) || cls.isArray) special = special or CONSTRUCTOR
        extensible = false
    }

    override val className: String get() = "Function"

    private fun member(key: Any): Any? {
        if (key === JSSymbol.hasInstance) {
            return NativeFunction(bridge.realm, "[Symbol.hasInstance]", 1, { _, _, a, _ ->
                val v = a.arg(0)
                v is HostObject && cls.isInstance(v.target)
            })
        }
        if (key !is String) return NotFound
        if (key == "name") return cls.simpleName
        if (key == "length") return (info.constructors.minOfOrNull { it.parameterCount } ?: 0).toDouble()
        info.staticFields[key]?.let { f ->
            return try { bridge.toJS(f.get(null)) } catch (_: IllegalAccessException) { throw JSException.typeError("Cannot access field $key") }
        }
        info.staticMethods[key]?.let { list -> return methodCache.getOrPut(key) { HostMethodFunction(bridge, key, list, null) } }
        info.overload(key, static = true)?.let { m -> return methodCache.getOrPut(key) { HostMethodFunction(bridge, key.substringBefore('('), listOf(m), null) } }
        info.staticGetters[key]?.let { g -> return bridge.invoke(g, null, EMPTY_ARGS) }
        info.memberClasses[key]?.let { return bridge.classObject(it) }
        if (cls.isEnum) {
            val c = cls.enumConstants.firstOrNull { (it as Enum<*>).name == key }
            if (c != null) return bridge.toJS(c)
        }
        companion?.let { c ->
            val v = c.getOwnValue(key, c)
            if (v !== NotFound) return v
        }
        return NotFound
    }

    override fun getOwnValue(key: Any, receiver: Any?): Any? = member(key)

    override fun get(key: Any, receiver: Any?): Any? {
        val v = member(key)
        if (v !== NotFound) return v
        return proto?.get(key, receiver) ?: Undefined
    }

    override fun getOwnProperty(key: Any): PropertyDescriptor? {
        val v = member(key)
        if (v === NotFound) return null
        return PropertyDescriptor.data(v, Attr.ENUMERABLE)
    }

    override fun hasOwnProperty(key: Any): Boolean = member(key) !== NotFound
    override fun hasProperty(key: Any): Boolean = hasOwnProperty(key) || (proto?.hasProperty(key) ?: false)

    override fun set(key: Any, value: Any?, receiver: Any?): Boolean {
        if (key !is String) return false
        val f = info.staticFields[key]
        if (f != null && !Modifier.isFinal(f.modifiers)) {
            try {
                f.set(null, bridge.toField(value, f))
            } catch (_: IllegalAccessException) {
                return false
            } catch (e: IllegalArgumentException) {
                throw JSException.typeError("Cannot set field $key: ${e.message}")
            }
            return true
        }
        val setters = info.staticSetters[key]
        if (setters == null) {
            val c = companion ?: return false
            return c.set(key, value, c)
        }
        val m = bridge.select(setters, arrayOf(value)) ?: return false
        bridge.invoke(m, null, arrayOf(value))
        return true
    }

    override fun defineOwnProperty(key: Any, desc: PropertyDescriptor): Boolean = false
    override fun delete(key: Any): Boolean = !hasOwnProperty(key)
    override fun ownPropertyKeys(): MutableList<Any> {
        val out = ArrayList<Any>()
        out.addAll(info.staticFields.keys)
        out.addAll(info.staticGetters.keys)
        out.addAll(info.staticMethods.keys)
        out.addAll(info.memberClasses.keys)
        return out
    }
    override fun setPrototypeOf(p: JSObject?): Boolean = p === proto
    override fun preventExtensions(): Boolean = true
    override fun isExtensible(): Boolean = false

    override fun call(thisArg: Any?, args: Array<Any?>): Any? =
        throw JSException.typeError("Host class ${cls.simpleName} must be invoked with 'new'")

    override fun construct(args: Array<Any?>, newTarget: JSObject): Any? {
        if (cls.isArray) return bridge.newArray(cls.componentType, args.arg(0))
        if (cls.isInterface) {
            val impl = args.arg(0) as? JSObject ?: throw JSException.typeError("Implementing ${cls.simpleName} requires a function or object")
            return bridge.toJS(bridge.implement(impl, cls))
        }
        val c = bridge.select(info.constructors, args)
            ?: throw JSException.typeError("No applicable constructor for ${cls.simpleName} with arguments (${args.joinToString { Ops.typeOf(it) }})")
        return bridge.construct(c, args)
    }

    override fun toString(): String = "HostClass[${cls.name}]"
}
