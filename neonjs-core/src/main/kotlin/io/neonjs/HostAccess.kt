package io.neonjs

import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier

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
 * Even in [ALL] mode a built-in deny list blocks reflection, class loading, process/thread control and similar
 * capabilities; add more with [Builder.denyClass] or a custom [Builder.classFilter].
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
    private val denied: Set<String> = defaultDenied + b.deniedClasses
    private val deniedPrefixes: List<String> = defaultDeniedPrefixes + b.deniedPrefixes
    val allowArrayAccess: Boolean = b.allowArrayAccess
    val allowListAccess: Boolean = b.allowListAccess
    val allowMapAccess: Boolean = b.allowMapAccess
    val allowIterableAccess: Boolean = b.allowIterableAccess
    /** Allow JS objects/functions to be converted to host interfaces (callbacks, listeners). */
    val allowImplementations: Boolean = b.allowImplementations

    fun isClassDenied(c: Class<*>): Boolean {
        var k: Class<*>? = c
        while (k != null) {
            val n = k.name
            if (n in denied || deniedPrefixes.any { n.startsWith(it) }) return true
            k = k.superclass
        }
        for (i in c.interfaces) if (i.name in denied) return true
        return false
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

    fun isMemberAccessible(c: Class<*>, m: Member): Boolean {
        if (!Modifier.isPublic(m.modifiers)) return false
        if (m is Method) {
            if (m.name in deniedMethods && m.declaringClass == Any::class.java) return false
            if (m.name == "getClass" && m.parameterCount == 0) return false
            if (m.isSynthetic || m.isBridge) return false
            if (isClassDenied(m.declaringClass)) return false
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
        fun denyClass(name: String) = apply { deniedClasses.add(name) }
        fun denyPackage(prefix: String) = apply { deniedPrefixes.add(if (prefix.endsWith(".")) prefix else "$prefix.") }
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
            "javax.script.", "java.lang.instrument.", "java.lang.management.", "io.neonjs.vm.", "io.neonjs.compiler.",
            "io.neonjs.runtime.", "kotlin.reflect.", "kotlin.jvm.internal.", "com.ibm.icu.",
        )
        /** java.lang.Object methods never exposed. */
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
