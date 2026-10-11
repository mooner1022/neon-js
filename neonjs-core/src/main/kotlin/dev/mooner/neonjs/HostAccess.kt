package dev.mooner.neonjs

import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/** Marks a host class member (method, field, constructor, Kotlin property getter) as accessible from JS. */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.FIELD, AnnotationTarget.CONSTRUCTOR, AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER, AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class HostExport

/** Overrides the JS-visible name of a host member. */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.FIELD, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class HostName(val value: String)

/**
 * Policy deciding which host (Java/Kotlin) classes and members JS code may access.
 *
 * Even in [ALL] mode a built-in deny list ([defaultDenied], [defaultDeniedPrefixes]) blocks reflection, class
 * loading, process/thread control and similar capabilities; add more with [Builder.denyClass] or a custom
 * [Builder.classFilter]. Embedders that trust their scripts with some of these can lift entries of the built-in list
 * with [Builder.allowClass] and [Builder.allowPackage], or drop it with [Builder.defaultDenyList].
 */
class HostAccess private constructor(b: Builder) {
    enum class Level {
        /** No host values can be accessed (they are opaque). */
        NONE,
        /** Only members annotated with [HostExport] (or members of classes annotated with it). */
        EXPLICIT,
        /** All public members of allowed classes. */
        ALL,
    }

    val level: Level = b.level
    private val classFilter: ((Class<*>) -> Boolean)? = b.classFilter
    private val memberFilter: ((Member) -> Boolean)? = b.memberFilter
    private val lookupFilter: ((String) -> Boolean)? = b.lookupFilter
    private val deniedClasses: Set<String> = HashSet(b.deniedClasses)
    private val deniedPrefixes: List<String> = ArrayList(b.deniedPrefixes)
    private val defaultDenyList: Boolean = b.defaultDenyList
    private val allowedClasses: Set<String> = HashSet(b.allowedClasses)
    private val allowedPrefixes: List<String> = ArrayList(b.allowedPrefixes)
    val allowArrayAccess: Boolean = b.allowArrayAccess
    val allowListAccess: Boolean = b.allowListAccess
    val allowMapAccess: Boolean = b.allowMapAccess
    val allowIterableAccess: Boolean = b.allowIterableAccess
    /** Allow JS objects/functions to be converted to host interfaces (callbacks, listeners). */
    val allowImplementations: Boolean = b.allowImplementations

    /**
     * What JS sees of each class under this policy, shared by the engines using it and collected with it (classes
     * generated per context, `Java.extend` adapters, are kept by their context instead).
     */
    internal val classInfos = java.util.concurrent.ConcurrentHashMap<Class<*>, dev.mooner.neonjs.interop.HostClassInfo>()

    /**
     * Whether the class named [n] is on the deny list: denied by the embedder ([Builder.denyClass],
     * [Builder.denyPackage]), or on the built-in list and not lifted. [packages] also applies the package entries.
     */
    private fun isNameDenied(n: String, packages: Boolean = true): Boolean {
        if (n in deniedClasses || packages && deniedPrefixes.any { n.startsWith(it) }) return true
        if (!defaultDenyList || n in allowedClasses || allowedPrefixes.any { n.startsWith(it) }) return false
        return n in defaultDenied || isEngineApi(n) || packages && defaultDeniedPrefixes.any { n.startsWith(it) }
    }

    /** Whether [n] is one of [defaultDeniedEngineClasses] or a class nested in one. */
    private fun isEngineApi(n: String): Boolean {
        if (!n.startsWith(ENGINE_PACKAGE) || n.indexOf('.', ENGINE_PACKAGE.length) >= 0) return false
        val end = n.indexOf('$').let { if (it < 0) n.length else it }
        return n.substring(ENGINE_PACKAGE.length, end) in defaultDeniedEngineClasses
    }

    /** Whether [c], one of its superclasses or one of its interfaces is on the deny list. */
    fun isClassDenied(c: Class<*>): Boolean = isDenied(c) { false }

    /**
     * [isClassDenied], but for the superclasses [base] accepts only where the embedder denied them: the built-in list
     * denies those for what they offer themselves, not for what their subclasses' instances are.
     */
    private inline fun isDenied(c: Class<*>, base: (Class<*>) -> Boolean): Boolean {
        var k: Class<*>? = c
        while (k != null) {
            val n = k.name
            if (isNameDenied(n) && (k === c || !base(k) || n in deniedClasses || deniedPrefixes.any { n.startsWith(it) })) return true
            k = k.superclass
        }
        for (i in c.interfaces) if (isNameDenied(i.name, packages = false)) return true
        return false
    }

    /**
     * Whether instances of [c] may not be called as functions: [c] or a superclass is on the deny list, except the
     * bases of proxies (`java.lang.reflect.Proxy`) and of the classes Kotlin compiles lambdas and function references
     * to (`kotlin.jvm.internal`): those are denied for their own members (reflection), not for calls.
     */
    internal fun isCallDenied(c: Class<*>): Boolean = isDenied(c) { it === Proxy::class.java || it.name.startsWith("kotlin.jvm.internal.") }

    /**
     * Can JS see the instance members of an object of class [c]? Those of a class that is not public (what
     * `listOf`, `Collections.unmodifiableList`, `Map.of` or `CharBuffer.wrap` return) are the methods of its public
     * superclasses and interfaces, called through those types, as Java code outside its package would call them. A
     * proxy shows the methods of its interfaces: its base class `java.lang.reflect.Proxy` has no public instance
     * methods of its own.
     */
    internal fun isInstanceAccessible(c: Class<*>): Boolean {
        if (level == Level.NONE) return false
        if (c.isArray) return isClassAccessible(c)
        if (isDenied(c) { it === Proxy::class.java }) return false
        val f = classFilter
        return f == null || f(c)
    }

    /** Can JS see members of [c] at all? */
    fun isClassAccessible(c: Class<*>): Boolean {
        if (level == Level.NONE) return false
        if (c.isArray) {
            // arrays are judged by their element type (int[] is fine, Class[] is not)
            var e: Class<*> = c
            while (e.isArray) e = e.componentType
            return e.isPrimitive || isClassAccessible(e)
        }
        if (!Modifier.isPublic(c.modifiers)) return false
        if (isClassDenied(c)) return false
        val f = classFilter
        return f == null || f(c)
    }

    /**
     * Whether JS may use member [m] of class [c]: public, not declared by a denied class, exported under [Level.EXPLICIT],
     * accepted by the member filter. Synthetic methods are not members, except the bridges through which a method is
     * called by its name (see [isEntryBridge]).
     */
    fun isMemberAccessible(c: Class<*>, m: Member): Boolean {
        if (!Modifier.isPublic(m.modifiers)) return false
        if (m is Method) {
            if (m.name == "getClass" && m.parameterCount == 0) {
                // it hands out java.lang.Class: visible only where that class is
                if (isNameDenied("java.lang.Class")) return false
            } else if (m.name in deniedMethods && m.declaringClass == Any::class.java) return false
            if ((m.isSynthetic || m.isBridge) && !isEntryBridge(m)) return false
            if (isDenied(m.declaringClass) { it === Proxy::class.java }) return false
        }
        when (level) {
            Level.NONE -> return false
            Level.EXPLICIT -> {
                val annotated = (m is java.lang.reflect.AnnotatedElement && m.isAnnotationPresent(HostExport::class.java)) ||
                        c.isAnnotationPresent(HostExport::class.java) || m.declaringClass.isAnnotationPresent(HostExport::class.java) ||
                        (m is Method && overridesExported(m))
                if (!annotated) return false
            }
            Level.ALL -> {}
        }
        val f = memberFilter
        return f == null || f(m)
    }

    /**
     * Whether bridge method [m] is how a method is called by this name, rather than a forwarder to a visible method
     * of its own class. javac adds such bridges to a public class for the public methods it inherits from a
     * non-public superclass (JDK-6342411: `StringBuilder.length()` for `AbstractStringBuilder.length()`), Kotlin for
     * the Java names of members of the types it maps (`length()` and `charAt()` of a `CharSequence` implementing
     * `length` and `get`, `size()`, `remove(int)` for `removeAt`). A covariant-return or generic bridge forwards to a
     * method of the same name declared next to it, with parameter types as specific or more: that one is the member.
     * Synthetic methods that are not bridges (accessors, `Java.extend`'s super-call trampolines) stay hidden.
     */
    private fun isEntryBridge(m: Method): Boolean {
        if (!m.isBridge) return false
        val params = m.parameterTypes
        for (o in m.declaringClass.declaredMethods) {
            if (o.name != m.name || o.isBridge || o.isSynthetic || o.parameterCount != params.size) continue
            val ops = o.parameterTypes
            if (params.indices.all { params[it].isAssignableFrom(ops[it]) }) return false
        }
        return true
    }

    /**
     * True if [m] overrides or implements a method that is exported (annotated, or declared by an annotated type)
     * in a superclass or interface — e.g. a subclass, or a `Java.extend` adapter, of an exported type.
     */
    private fun overridesExported(m: Method): Boolean {
        val seen = HashSet<Class<*>>()
        fun check(c: Class<*>?): Boolean {
            if (c == null || !seen.add(c)) return false
            try {
                val sm = c.getDeclaredMethod(m.name, *m.parameterTypes)
                if (sm.isAnnotationPresent(HostExport::class.java) || c.isAnnotationPresent(HostExport::class.java)) return true
            } catch (e: NoSuchMethodException) {
                // not declared here
            }
            return check(c.superclass) || c.interfaces.any { check(it) }
        }
        val dc = m.declaringClass
        return check(dc.superclass) || dc.interfaces.any { check(it) }
    }

    /** Whether `Java.type(name)` may load the class. */
    fun isLookupAllowed(className: String): Boolean {
        if (level == Level.NONE) return false
        val f = lookupFilter ?: return false
        return f(className)
    }

    class Builder(var level: Level) {
        var classFilter: ((Class<*>) -> Boolean)? = null
        var memberFilter: ((Member) -> Boolean)? = null
        var lookupFilter: ((String) -> Boolean)? = null
        val deniedClasses = HashSet<String>()
        val deniedPrefixes = ArrayList<String>()
        val allowedClasses = HashSet<String>()
        val allowedPrefixes = ArrayList<String>()
        var defaultDenyList = true
        var allowArrayAccess = true
        var allowListAccess = true
        var allowMapAccess = true
        var allowIterableAccess = true
        var allowImplementations = true

        fun classFilter(f: (Class<*>) -> Boolean) = apply { classFilter = f }
        fun memberFilter(f: (Member) -> Boolean) = apply { memberFilter = f }
        /** Permits `Java.type(name)` for class names accepted by [f]. */
        fun allowLookup(f: (String) -> Boolean) = apply { lookupFilter = f }
        /** Additional predicate for classes (applied after the deny list). */
        fun filterClasses(f: (Class<*>) -> Boolean) = apply { classFilter = f }
        /** Additional predicate for members (methods, fields, constructors). */
        fun filterMembers(f: (Member) -> Boolean) = apply { memberFilter = f }
        /** Denies the class [name] (binary name), its subclasses and the classes implementing it. */
        fun denyClass(name: String) = apply { deniedClasses.add(name) }
        /** Denies the classes of the package [prefix] and its subpackages. */
        fun denyPackage(prefix: String) = apply { deniedPrefixes.add(packagePrefix(prefix)) }

        /**
         * Lifts the built-in deny list for the class [name] (binary name, e.g. `java.lang.System` or
         * `java.lang.reflect.Array`), also where its package is denied. Its members become visible like those of any
         * other class (for `System`: `exit`, `setProperty`, `getenv`, `load`…), so narrow them with [filterMembers]
         * where needed. Classes denied with [denyClass] or [denyPackage] stay denied. Lifting `java.lang.Class`
         * also exposes `getClass()`. Host objects are judged by their own class: an instance of a denied class stays
         * opaque even when an interface it implements is allowed (e.g. the `sun.management` implementations of
         * `java.lang.management` interfaces).
         */
        fun allowClass(name: String) = apply { allowedClasses.add(name) }

        /** Lifts the built-in deny list for the classes of the package [prefix] and its subpackages (see [allowClass]). */
        fun allowPackage(prefix: String) = apply { allowedPrefixes.add(packagePrefix(prefix)) }

        /**
         * Applies the built-in deny list ([defaultDenied], [defaultDeniedPrefixes]); `true` by default. Turning it off
         * leaves only the classes denied with [denyClass] / [denyPackage] and the filters: for fully trusted scripts.
         * `wait`, `notify`, `notifyAll` and `finalize` stay hidden either way.
         */
        fun defaultDenyList(enabled: Boolean) = apply { defaultDenyList = enabled }

        private fun packagePrefix(p: String) = if (p.endsWith(".")) p else "$p."
        fun allowArrayAccess(b: Boolean) = apply { allowArrayAccess = b }
        fun allowListAccess(b: Boolean) = apply { allowListAccess = b }
        fun allowMapAccess(b: Boolean) = apply { allowMapAccess = b }
        fun allowIterableAccess(b: Boolean) = apply { allowIterableAccess = b }
        fun allowImplementations(b: Boolean) = apply { allowImplementations = b }
        fun build() = HostAccess(this)
    }

    companion object {
        val defaultDenied = setOf(
            "java.lang.Class", "java.lang.ClassLoader", "java.lang.Thread", "java.lang.ThreadGroup", "java.lang.Runtime",
            "java.lang.System", "java.lang.ProcessBuilder", "java.lang.Process", "java.lang.ProcessHandle",
            "java.lang.SecurityManager", "java.lang.Module", "java.lang.ModuleLayer", "java.lang.StackWalker",
            "java.lang.ref.Cleaner", "sun.misc.Unsafe", "java.lang.invoke.MethodHandles",
            "java.lang.invoke.MethodHandles\$Lookup", "java.io.ObjectInputStream", "java.io.ObjectOutputStream",
        )
        val defaultDeniedPrefixes = listOf(
            "java.lang.reflect.", "java.lang.invoke.", "sun.", "com.sun.", "jdk.internal.", "java.security.",
            "javax.script.", "java.lang.instrument.", "java.lang.management.", "kotlin.reflect.", "kotlin.jvm.internal.",
            "com.ibm.icu.",
            // the engine's own packages
            "dev.mooner.neonjs.android.", "dev.mooner.neonjs.builtins.", "dev.mooner.neonjs.cli.", "dev.mooner.neonjs.compiler.",
            "dev.mooner.neonjs.ext.", "dev.mooner.neonjs.interop.", "dev.mooner.neonjs.intl.", "dev.mooner.neonjs.jit.", "dev.mooner.neonjs.node.",
            "dev.mooner.neonjs.parser.", "dev.mooner.neonjs.regexp.", "dev.mooner.neonjs.runtime.", "dev.mooner.neonjs.unicode.",
            "dev.mooner.neonjs.vm.",
        )

        private const val ENGINE_PACKAGE = "dev.mooner.neonjs."

        /**
         * The classes of the engine's API package that control engines, contexts, policies and values, or read files
         * (simple names; classes nested in them are denied too). The rest of the package (annotations, exceptions,
         * `HostFunction`, `NeonConsole`, `NeonModuleLoader`) is used or implemented by host code, and stays visible.
         */
        val defaultDeniedEngineClasses = setOf(
            "NeonEngine", "NeonContext", "NeonValue", "NeonScript", "HostAccess", "SandboxPolicy", "FileSystemModuleLoader",
            "MapModuleLoader",
        )
        /** java.lang.Object methods never exposed (`getClass` only while `java.lang.Class` is denied). */
        val deniedMethods = setOf("getClass", "wait", "notify", "notifyAll", "finalize")

        /** No host access. */
        @JvmField val NONE: HostAccess = Builder(Level.NONE).build()
        /** Only @HostExport annotated members. */
        @JvmField val EXPLICIT: HostAccess = Builder(Level.EXPLICIT).build()
        /** All public members (minus the deny list); no class lookup by name. */
        @JvmField val ALL: HostAccess = Builder(Level.ALL).build()

        @JvmStatic fun builder(level: Level) = Builder(level)
    }
}
