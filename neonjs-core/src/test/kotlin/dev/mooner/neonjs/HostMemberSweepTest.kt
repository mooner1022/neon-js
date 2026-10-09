package dev.mooner.neonjs

import dev.mooner.neonjs.fixtures.Narrowed
import dev.mooner.neonjs.fixtures.Version
import dev.mooner.neonjs.fixtures.Visible
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier

/**
 * Sweeps common JDK, Kotlin and fixture classes for two properties of host objects:
 * - every public method Java code could call on the object is a JS member (the expected names come from the
 *   reflection of the JVM running the test, so a new JDK, ART or Kotlin version is checked as well);
 * - calling each one without arguments fails, if at all, in the method itself (a HostError), never in the engine.
 *
 * Classes reach JS in many shapes: public classes with methods inherited from non-public ones (StringBuilder), objects
 * of non-public classes behind public interfaces (`listOf`, `Map.of`, `CharBuffer.wrap`), Kotlin implementations of
 * Java interfaces (whose Java names are bridges), so new rules for one shape are checked against all of them.
 */
class HostMemberSweepTest {
    companion object {
        /** Instances, each made fresh for every call (calls may change or exhaust them). */
        val samples: List<Pair<String, () -> Any>> = listOf(
            "StringBuilder" to { StringBuilder("abc") },
            "StringBuffer" to { StringBuffer("abc") },
            "CharBuffer.wrap" to { java.nio.CharBuffer.wrap("abc") },
            "ByteBuffer.allocate" to { java.nio.ByteBuffer.allocate(8) },
            "StringJoiner" to { java.util.StringJoiner(",").add("a") },
            "DecimalFormat" to { java.text.DecimalFormat("0.00") },
            "StringTokenizer" to { java.util.StringTokenizer("a b") },
            "Locale" to { java.util.Locale.KOREA },
            "UUID" to { java.util.UUID(1, 2) },
            "Random" to { java.util.Random(1) },
            "ArrayList" to { arrayListOf(1, 2) },
            "LinkedList" to { java.util.LinkedList(listOf(1, 2)) },
            "ArrayDeque" to { java.util.ArrayDeque(listOf(1, 2)) },
            "HashMap" to { hashMapOf("a" to 1) },
            "TreeMap" to { java.util.TreeMap(mapOf("a" to 1)) },
            "HashSet" to { hashSetOf(1) },
            "TreeSet" to { java.util.TreeSet(setOf(1, 2)) },
            "PriorityQueue" to { java.util.PriorityQueue(listOf(2, 1)) },
            "BitSet" to { java.util.BitSet().apply { set(3) } },
            "Optional.of" to { java.util.Optional.of(1) },
            "Optional.empty" to { java.util.Optional.empty<Int>() },
            "Collections.unmodifiableList" to { java.util.Collections.unmodifiableList(arrayListOf(1)) },
            "List.of" to { java.util.List.of(1, 2) },
            "Map.of" to { java.util.Map.of("a", 1) },
            "Kotlin listOf" to { listOf(1, 2) },
            "Kotlin emptyList" to { emptyList<Int>() },
            "Kotlin mapOf" to { mapOf("a" to 1, "b" to 2) },
            "Kotlin ArrayDeque" to { kotlin.collections.ArrayDeque(listOf(1, 2)) },
            "ConcurrentHashMap" to { java.util.concurrent.ConcurrentHashMap(mapOf("a" to 1)) },
            "ConcurrentHashMap.keySet" to { java.util.concurrent.ConcurrentHashMap(mapOf("a" to 1)).keySet(0) },
            "CopyOnWriteArrayList" to { java.util.concurrent.CopyOnWriteArrayList(listOf(1)) },
            "ConcurrentLinkedQueue" to { java.util.concurrent.ConcurrentLinkedQueue(listOf(1)) },
            "AtomicInteger" to { java.util.concurrent.atomic.AtomicInteger(1) },
            "AtomicReference" to { java.util.concurrent.atomic.AtomicReference("a") },
            "LongAdder" to { java.util.concurrent.atomic.LongAdder().apply { add(2) } },
            "Pattern" to { java.util.regex.Pattern.compile("a+") },
            "Matcher" to { java.util.regex.Pattern.compile("a+").matcher("baab") },
            "LocalDate" to { java.time.LocalDate.of(2026, 1, 2) },
            "LocalDateTime" to { java.time.LocalDateTime.of(2026, 1, 2, 3, 4) },
            "Duration" to { java.time.Duration.ofSeconds(90) },
            "HijrahDate" to { java.time.chrono.HijrahDate.of(1447, 1, 1) },
            "DayOfWeek" to { java.time.DayOfWeek.MONDAY },
            "Kotlin Regex" to { Regex("a+") },
            "Kotlin Pair" to { Pair(1, "a") },
            "Kotlin IntRange" to { 1..3 },
            "Visible (package-private base)" to { Visible() },
            "Narrowed (generic bridge)" to { Narrowed() },
            "Version (Comparable)" to { Version(1) },
            "Rot13 (Kotlin CharSequence)" to { Rot13("abc") },
            "Keeper (Kotlin properties)" to { Keeper() },
        )

        /**
         * The names of the public instance methods of [cls] Java code outside its package can call: those of its
         * public superclasses and interfaces (all of its own if it is public), minus what is never exposed.
         */
        fun expectedNames(cls: Class<*>): Set<String> {
            val types = LinkedHashSet<Class<*>>()
            fun add(t: Class<*>?) {
                if (t == null || !types.add(t)) return
                add(t.superclass)
                t.interfaces.forEach { add(it) }
            }
            add(cls)
            val out = HashSet<String>()
            for (t in types) {
                if (!Modifier.isPublic(t.modifiers)) continue
                for (m in t.methods) {
                    if (Modifier.isStatic(m.modifiers) || m.isSynthetic && !m.isBridge) continue
                    if (m.declaringClass == Any::class.java && m.name in HostAccess.deniedMethods) continue
                    out.add(m.name)
                }
            }
            return out
        }
    }

    private fun ctx() = NeonEngine.builder().hostAccess(HostAccess.ALL).console(null).build().newContext()

    @Test
    fun everyCallableMethodIsAMember() {
        val missing = ArrayList<String>()
        ctx().use { c ->
            for ((name, make) in samples) {
                val v = make()
                c["x"] = v
                for (m in expectedNames(v.javaClass).sorted()) {
                    c["n"] = m
                    if (c.eval("typeof x[n]").asString() != "function") missing.add("$name.$m")
                }
            }
        }
        assertTrue(missing.isEmpty(), "public methods JS does not see: $missing")
    }

    @Test
    fun callsFailOnlyInTheMethod() {
        val failures = ArrayList<String>()
        ctx().use { c ->
            for ((name, make) in samples) {
                val cls = make().javaClass
                val zeroArg = expectedNames(cls).filter { n -> cls.methods.any { it.name == n && it.parameterCount == 0 && !Modifier.isStatic(it.modifiers) } }
                for (m in zeroArg.sorted()) {
                    c["x"] = make()
                    c["n"] = m
                    val r = try {
                        c.eval("try { x[n](); 'ok' } catch (e) { e && e.name === 'HostError' ? 'ok' : 'engine ' + e }").asString()
                    } catch (e: Exception) {
                        "escaped $e"
                    }
                    if (r != "ok") failures.add("$name.$m(): $r")
                }
            }
        }
        assertTrue(failures.isEmpty(), "calls failing in the engine: $failures")
    }

    @Test
    fun everyCallableStaticMethodIsAMember() {
        val missing = ArrayList<String>()
        ctx().use { c ->
            for ((name, make) in samples) {
                val cls = make().javaClass
                if (!Modifier.isPublic(cls.modifiers)) continue
                c.exposeClass("K", cls)
                for (m in cls.methods.filter { Modifier.isStatic(it.modifiers) && !it.isSynthetic }.map { it.name }.toSortedSet()) {
                    c["n"] = m
                    if (c.eval("typeof K[n]").asString() != "function") missing.add("$name.$m")
                }
            }
        }
        assertTrue(missing.isEmpty(), "public static methods JS does not see: $missing")
    }
}
