package dev.mooner.neonjs.interop

import dev.mooner.neonjs.HostAccess
import dev.mooner.neonjs.HostName
import java.lang.reflect.*

/** Reflection metadata of a host class as seen from JS under a [HostAccess] policy. */
class HostClassInfo private constructor(val cls: Class<*>, val access: HostAccess) {
    /** Whether JS may use the class itself: static members, constructors (and see [instancesVisible]). */
    val accessible: Boolean = access.isClassAccessible(cls)
    /** Whether instances show members: those of a non-public class are the ones of its public supertypes. */
    val instancesVisible: Boolean = access.isInstanceAccessible(cls)

    val instanceFields = HashMap<String, Field>()
    val instanceMethods = HashMap<String, MutableList<Method>>()
    val instanceGetters = HashMap<String, Method>()
    val instanceSetters = HashMap<String, MutableList<Method>>()
    val staticFields = HashMap<String, Field>()
    val staticMethods = HashMap<String, MutableList<Method>>()
    val staticGetters = HashMap<String, Method>()
    val staticSetters = HashMap<String, MutableList<Method>>()
    val constructors = ArrayList<Constructor<*>>()
    val memberClasses = HashMap<String, Class<*>>()
    /** The method a call of an instance runs, if instances are functions (see [findFunctionalMethod]). */
    var functionalMethod: Method? = null
    /** The overloads of [functionalMethod] a call chooses from (set by the first call). */
    @Volatile internal var callOverloads: List<Method>? = null

    init {
        if (instancesVisible) collect()
        functionalMethod = try {
            findFunctionalMethod(cls, access)
        } catch (_: LinkageError) {
            null
        }
    }

    private fun jsName(m: AccessibleObject, default: String): String = m.getAnnotation(HostName::class.java)?.value ?: default

    /**
     * Members whose signature names a class missing at run time (an Android API of a later level, an optional
     * library) are left out, rather than failing the whole class: each member is skipped on its own where the platform
     * resolves its types one member at a time (ART), the declared members of a class together where it resolves them
     * all at once (HotSpot), keeping those inherited from the other classes of the hierarchy.
     */
    private fun collect() {
        for (f in publicMembers(Class<*>::getFields, Class<*>::getDeclaredFields)) {
            try {
                if (!access.isMemberAccessible(cls, f) || !isCallable(f) || Modifier.isStatic(f.modifiers) && !accessible) continue
                f.type // resolves it (ART resolves types when first asked for)
                val n = jsName(f, f.name)
                if (Modifier.isStatic(f.modifiers)) staticFields[n] = f else instanceFields[n] = f
            } catch (_: LinkageError) {
                // a missing type
            }
        }
        for (m0 in publicMembers(Class<*>::getMethods, Class<*>::getDeclaredMethods)) {
            try {
                if (!access.isMemberAccessible(cls, m0) || Modifier.isStatic(m0.modifiers) && !accessible) continue
                val m = publicVersion(m0) ?: continue
                // reached through a supertype: that type is what JS calls, so it must not be denied either
                if (m !== m0 && access.isClassDenied(m.declaringClass)) continue
                // resolves its types (ART resolves them when first asked for)
                m.parameterTypes
                m.returnType
                val n = jsName(m0, m0.name)
                val target = if (Modifier.isStatic(m.modifiers)) staticMethods else instanceMethods
                val list = target.getOrPut(n) { ArrayList() }
                if (list.none { sameSignature(it, m) }) list.add(m)
            } catch (_: LinkageError) {
                // a missing type
            }
        }
        // bean-style properties (Kotlin properties compile to getX/isX/setX)
        for ((isStatic, methods) in listOf(false to instanceMethods, true to staticMethods)) {
            val getters = if (isStatic) staticGetters else instanceGetters
            val setters = if (isStatic) staticSetters else instanceSetters
            val fields = if (isStatic) staticFields else instanceFields
            for ((name, list) in methods) {
                for (m in list) {
                    val prop = when {
                        name.length > 3 && name.startsWith("get") && m.parameterCount == 0 && m.returnType != Void.TYPE -> decap(name.substring(3))
                        name.length > 2 && name.startsWith("is") && m.parameterCount == 0 && (m.returnType == java.lang.Boolean.TYPE || m.returnType == java.lang.Boolean::class.java) -> decap(name.substring(2))
                        else -> null
                    } ?: continue
                    if (prop in methods || prop in fields) continue
                    getters.putIfAbsent(prop, m)
                }
                if (name.length > 3 && name.startsWith("set")) {
                    val prop = decap(name.substring(3))
                    if (prop in methods || prop in fields) continue
                    for (m in list) if (m.parameterCount == 1) setters.getOrPut(prop) { ArrayList() }.add(m)
                }
            }
        }
        if (!accessible) return
        if (!Modifier.isAbstract(cls.modifiers) && !cls.isInterface) {
            for (c in orNone { cls.constructors }) {
                try {
                    c.parameterTypes // resolves them
                    if (access.isMemberAccessible(cls, c)) constructors.add(c)
                } catch (_: LinkageError) {
                    // a missing type
                }
            }
        }
        for (mc in orNone { cls.classes }) {
            if (Modifier.isPublic(mc.modifiers) && Modifier.isStatic(mc.modifiers) && access.isClassAccessible(mc)) memberClasses[mc.simpleName] = mc
        }
    }

    private inline fun <T> orNone(get: () -> Array<T>): List<T> = try {
        get().asList()
    } catch (_: LinkageError) {
        emptyList()
    }

    /**
     * The public fields or methods of [cls] ([all]: `getFields`, `getMethods`). Where those fail because a type of
     * one of them is missing, the public members of each class of the hierarchy ([declared]) whose members load, the
     * nearest declaration of each signature first, without the static methods of supertypes' interfaces.
     */
    private fun <M : Member> publicMembers(all: (Class<*>) -> Array<M>, declared: (Class<*>) -> Array<M>): List<M> {
        try {
            return all(cls).asList()
        } catch (_: LinkageError) {
            // collect them class by class below
        }
        val found = LinkedHashMap<String, M>()
        val seen = HashSet<Class<*>>()
        fun add(c: Class<*>) {
            if (!seen.add(c)) return
            val members = try {
                declared(c)
            } catch (_: LinkageError) {
                return
            }
            for (m in members) {
                if (!Modifier.isPublic(m.modifiers)) continue
                if (c !== cls && c.isInterface && m is Method && Modifier.isStatic(m.modifiers)) continue
                val key = try {
                    if (m is Method) m.name + m.parameterTypes.joinToString(",", "(", ")") { it.name } else m.name
                } catch (_: LinkageError) {
                    continue
                }
                found.putIfAbsent(key, m)
            }
        }
        fun addInterfaces(c: Class<*>) {
            for (i in c.interfaces) {
                add(i)
                addInterfaces(i)
            }
        }
        var c: Class<*>? = cls
        while (c != null) {
            add(c)
            c = c.superclass
        }
        c = cls
        while (c != null) {
            addInterfaces(c)
            c = c.superclass
        }
        return found.values.toList()
    }

    private fun decap(s: String): String {
        if (s.isEmpty()) return s
        if (s.length > 1 && s[0].isUpperCase() && s[1].isUpperCase()) return s
        return s[0].lowercaseChar() + s.substring(1)
    }

    private fun sameSignature(a: Method, b: Method) = a.name == b.name && a.parameterTypes.contentEquals(b.parameterTypes)

    /**
     * The overload a signature key names, as in Rhino, Nashorn and GraalJS: `append(java.lang.String)`,
     * `append(char[])`, `max(int,int)`; parameter types by binary, canonical or simple name. Null if [key] is not a
     * signature or names no single overload.
     */
    fun overload(key: String, static: Boolean): Method? {
        val open = key.indexOf('(')
        if (open <= 0 || !key.endsWith(")")) return null
        val list = (if (static) staticMethods else instanceMethods)[key.substring(0, open)] ?: return null
        val body = key.substring(open + 1, key.length - 1).trim()
        val types = if (body.isEmpty()) emptyList() else body.split(',').map { it.trim() }
        val found = list.filter { m ->
            m.parameterCount == types.size && m.parameterTypes.withIndex().all { (i, t) -> types[i] == t.name || types[i] == t.typeName || types[i] == t.canonicalName || types[i] == t.simpleName }
        }
        return found.singleOrNull()
    }

    companion object {
        /** The (cached) view of [cls] under [access]. */
        fun of(cls: Class<*>, access: HostAccess): HostClassInfo = access.classInfos.getOrPut(cls) { HostClassInfo(cls, access) }

        /** A view of [cls] under [access] that nothing caches: for classes that live only as long as a context. */
        internal fun uncached(cls: Class<*>, access: HostAccess) = HostClassInfo(cls, access)

        /** Finds a version of [m] declared in a public, exported type so it can be invoked reflectively. */
        fun publicVersion(m: Method): Method? {
            if (isCallable(m)) return m
            val seen = HashSet<Class<*>>()
            fun search(c: Class<*>?): Method? {
                if (c == null || !seen.add(c)) return null
                if (Modifier.isPublic(c.modifiers) && isExported(c)) {
                    try {
                        val cand = c.getMethod(m.name, *m.parameterTypes)
                        if (isCallable(cand)) return cand
                    } catch (_: NoSuchMethodException) {
                        // continue
                    }
                }
                for (i in c.interfaces) search(i)?.let { return it }
                return search(c.superclass)
            }
            return search(m.declaringClass)
        }

        /** Whether [c]'s package is exported by its module (always true where there are no modules, e.g. Android). */
        private fun isExported(c: Class<*>): Boolean {
            if (!HAS_MODULES) return true
            val mod = c.module
            val pkg = c.name.substringBeforeLast('.', "")
            return !mod.isNamed || mod.isExported(pkg)
        }

        private val HAS_MODULES = try {
            Class::class.java.getMethod("getModule")
            true
        } catch (_: Throwable) {
            false
        }

        /** Whether [m] can be used reflectively: its declaring class is public and exported. */
        private fun isCallable(m: Member): Boolean {
            val d = m.declaringClass
            return Modifier.isPublic(d.modifiers) && isExported(d) && (d.enclosingClass == null || Modifier.isPublic(d.enclosingClass.modifiers))
        }

        /**
         * The method a call of an instance of [cls] runs, or null if instances are not functions. An object is a
         * function when that is what its class is for:
         * - a lambda, a method reference or an anonymous class, through the one single-method interface it implements
         *   (any: Kotlin `fun interface`s, Android listeners);
         * - otherwise through an interface declared functional, `@FunctionalInterface` as in GraalJS (`Runnable`,
         *   `Comparator`, `java.util.function.*`) or a Kotlin function type, provided that is the class's only role:
         *   every other interface it implements has no abstract methods or is a super- or subinterface of that one.
         *
         * So an `ArrayList` (`Iterable`), a `File` (`Comparable`), a `LocalDate` (a `TemporalAdjuster`, but also a
         * `Temporal`) or a named class implementing a Kotlin `fun interface` are objects, whose methods are members.
         * Nothing is a function under [HostAccess.Level.NONE], nor an instance of a denied class.
         */
        fun findFunctionalMethod(cls: Class<*>, access: HostAccess): Method? {
            if (access.level == HostAccess.Level.NONE || access.isCallDenied(cls)) return null
            fun samIn(i: Class<*>): Method? =
                if (Modifier.isPublic(i.modifiers) && !access.isClassDenied(i)) samOf(i)?.takeIf { isCallable(it) } else null
            if ((cls.isSynthetic || cls.isAnonymousClass) && cls.superclass == Any::class.java) {
                val sams = cls.interfaces.mapNotNull { samIn(it) }
                if (sams.size == 1) return sams[0]
            }
            // declared functional, nearest first: the class's interfaces in declaration order and theirs, then the superclass's
            val seen = HashSet<Class<*>>()
            var fi: Class<*>? = null
            var sam: Method? = null
            var c: Class<*>? = cls
            search@ while (c != null) {
                val queue = ArrayDeque(c.interfaces.asList())
                while (queue.isNotEmpty()) {
                    val i = queue.removeFirst()
                    if (!seen.add(i)) continue
                    if (i.isAnnotationPresent(FunctionalInterface::class.java) || KOTLIN_FUNCTION.isAssignableFrom(i)) {
                        sam = samIn(i)
                        if (sam != null) {
                            fi = i
                            break@search
                        }
                    }
                    queue.addAll(i.interfaces)
                }
                c = c.superclass
            }
            if (fi == null) return null
            // its only role; the interfaces every enum has, and those of the classes Kotlin compiles lambdas and
            // references to, are not roles of the class
            c = cls
            while (c != null && c != Enum::class.java && !c.name.startsWith("kotlin.jvm.internal.")) {
                for (i in c.interfaces) {
                    val related = i.isAssignableFrom(fi) || fi.isAssignableFrom(i) && samOf(i) != null ||
                        KOTLIN_FUNCTION.isAssignableFrom(i) && KOTLIN_FUNCTION.isAssignableFrom(fi)
                    if (!related && i.methods.any { Modifier.isAbstract(it.modifiers) && !isObjectMethod(it) }) return null
                }
                c = c.superclass
            }
            return sam
        }

        private val KOTLIN_FUNCTION = kotlin.Function::class.java

        /** Single abstract method of an interface, or null. */
        fun samOf(i: Class<*>): Method? {
            if (!i.isInterface) return null
            var found: Method? = null
            for (m in i.methods) {
                if (!Modifier.isAbstract(m.modifiers)) continue
                if (isObjectMethod(m)) continue
                if (found != null && !(found.name == m.name && found.parameterTypes.contentEquals(m.parameterTypes))) return null
                found = m
            }
            return found
        }

        private fun isObjectMethod(m: Method): Boolean = try {
            Any::class.java.getMethod(m.name, *m.parameterTypes)
            true
        } catch (_: NoSuchMethodException) {
            false
        }
    }
}
