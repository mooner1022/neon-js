package dev.mooner.neonjs

import dev.mooner.neonjs.fixtures.Narrowed
import dev.mooner.neonjs.fixtures.Version
import dev.mooner.neonjs.fixtures.Visible
import dev.mooner.neonjs.runtime.Null
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

private fun shout(s: String) = s.uppercase() + "!"

/** A Kotlin functional interface: not annotated `@FunctionalInterface`. */
fun interface Transformer {
    fun transform(s: String): String
}

/** A named class implementing a functional interface: not a function itself, its method is a member. */
class NamedTransformer : Transformer {
    override fun transform(s: String) = "named $s"
}

/** `Runnable` is annotated `@FunctionalInterface`: any implementation is a function. */
class NamedRunnable : Runnable {
    var runs = 0
    override fun run() {
        runs++
    }
}

/** A CharSequence that is not a String. */
class Rot13(private val s: String) : CharSequence {
    override val length get() = s.length
    override fun get(index: Int): Char = s[index].let { if (it in 'a'..'z') 'a' + (it - 'a' + 13) % 26 else it }
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = Rot13(s.substring(startIndex, endIndex))
    override fun toString() = String(CharArray(length) { get(it) })
}

class Overloads {
    fun pick(x: Any?) = "Object"
    fun pick(x: String?) = "String"
    fun pick(x: CharSequence?) = "CharSequence"
    fun pick(x: StringBuilder?) = "StringBuilder"
    fun pick(x: IntArray?) = "int[]"
    fun num(x: Int) = "int"
    fun num(x: Long) = "long"
    fun num(x: Double) = "double"
    fun num(x: Any?) = "Object"
    fun take(r: Runnable) = "Runnable"
    fun take(c: java.util.concurrent.Callable<*>) = "Callable:" + c.call()
    fun text(s: String) = "text:$s"
    fun seq(s: CharSequence) = s
    fun id(x: Any?) = x
    fun sameList(l: List<Any?>) = l
    fun sameMap(m: Map<String, Any?>) = m

    companion object {
        @JvmStatic fun stat(x: Int) = "static int"
        @JvmStatic fun stat(x: String) = "static String"
    }
}

/** Takes and keeps 64-bit IDs. */
class Ids {
    @JvmField var last = 0L
    fun echo(id: Long) = id
    fun boxed(id: Long?) = id
}

/** Defines classes from bytes: classes whose signatures name a class that is missing at run time. */
private class BytesLoader(parent: ClassLoader) : ClassLoader(parent) {
    fun define(name: String, bytes: ByteArray): Class<*> = defineClass(name, bytes, 0, bytes.size)
}

/** A public class [name] (internal name) extending [superName], with a public no-argument constructor. */
private fun classBytes(name: String, superName: String, members: (org.objectweb.asm.ClassWriter) -> Unit): ByteArray {
    val cw = org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS)
    cw.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC or org.objectweb.asm.Opcodes.ACC_SUPER, name, null, superName, null)
    val init = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
    init.visitCode()
    init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
    init.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false)
    init.visitInsn(org.objectweb.asm.Opcodes.RETURN)
    init.visitMaxs(0, 0)
    init.visitEnd()
    members(cw)
    cw.visitEnd()
    return cw.toByteArray()
}

/** A public method [name] returning the string [value] ([descriptor]'s parameters are ignored). */
private fun org.objectweb.asm.ClassWriter.stringMethod(name: String, value: String, descriptor: String = "()Ljava/lang/String;") {
    val mv = visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, name, descriptor, null, null)
    mv.visitCode()
    mv.visitLdcInsn(value)
    mv.visitInsn(org.objectweb.asm.Opcodes.ARETURN)
    mv.visitMaxs(0, 0)
    mv.visitEnd()
}

/** Keeps what JS hands it, typed as interfaces. */
class Keeper {
    var transformer: Transformer? = null
    var runnable: Runnable? = null
    fun keep(t: Transformer) { transformer = t }
    fun keepRunnable(r: Runnable) { runnable = r }
}

/**
 * The host interop contract, value by value: what host values become in JS, which members JS sees, which host objects
 * are functions, how overloads are chosen. Each case states the rule it checks, so a change of rule shows up here.
 */
class InteropContractTest {
    private fun ctx(access: HostAccess = HostAccess.ALL, javaGlobal: Boolean = false) =
        NeonEngine.builder().hostAccess(access).console(null)
            .sandbox(SandboxPolicy.builder().exposeJavaGlobal(javaGlobal).build()).build().newContext()

    private val lookupAll = HostAccess.builder(HostAccess.Level.ALL).allowLookup { true }.build()

    @Test
    fun hostValuesInJs() {
        ctx().use { c ->
            val cases = listOf<Triple<String, Any?, String>>(
                Triple("String", "s", "string"),
                Triple("Char", 'c', "string"),
                Triple("Int", 1, "number"),
                Triple("Long", 2L, "number"),
                Triple("Long beyond 2^53 - 1", Long.MAX_VALUE, "bigint"),
                Triple("Short", 3.toShort(), "number"),
                Triple("Byte", 4.toByte(), "number"),
                Triple("Float", 1.5f, "number"),
                Triple("Double", 2.5, "number"),
                Triple("Boolean", true, "boolean"),
                Triple("null", null, "object"),
                Triple("Unit", Unit, "undefined"),
                Triple("BigInteger", java.math.BigInteger.TEN, "bigint"),
                Triple("BigDecimal", java.math.BigDecimal("1.25"), "number"),
                // a CharSequence other than String stays a host object: it may be mutable (StringBuilder, CharBuffer,
                // Android's Editable) or carry more than its text (Spanned)
                Triple("StringBuilder", StringBuilder("sb"), "object"),
                Triple("StringBuffer", StringBuffer("sf"), "object"),
                Triple("CharBuffer", java.nio.CharBuffer.wrap("cb"), "object"),
                Triple("custom CharSequence", Rot13("abc"), "object"),
                Triple("List", arrayListOf(1), "object"),
                Triple("Map", hashMapOf("k" to 1), "object"),
                Triple("int[]", intArrayOf(1), "object"),
                Triple("enum", java.time.DayOfWeek.MONDAY, "object"),
                // a TemporalAdjuster (functional), but also a Temporal: a value, not a function
                Triple("LocalDate", java.time.LocalDate.of(2026, 1, 2), "object"),
                Triple("Class", StringBuilder::class.java, "function"),
                Triple("Runnable lambda", Runnable { }, "function"),
            )
            for ((name, v, type) in cases) {
                c["v"] = v
                assertEquals(type, c.eval("typeof v").asString(), name)
            }
            c["v"] = java.util.concurrent.CompletableFuture.completedFuture(1)
            assertTrue(c.eval("v instanceof Promise").asBoolean(), "a CompletionStage becomes a promise")
        }
    }

    @Test
    fun stringBuildersAreJavaObjects() {
        ctx(lookupAll, javaGlobal = true).use { c ->
            // the reported case: new StringBuilder() was the empty string "", with no members
            assertEquals("object,true,function", c.eval("var SB = Java.type('java.lang.StringBuilder'); var sb = new SB(); [typeof sb, Java.isJavaObject(sb), typeof sb.append].join()").asString())
            // append returns the builder itself: the same object, so chains build one string
            assertEquals("a1-1.5-true-x-null|true", c.eval("var r = sb.append('a').append(1).append('-').append(1.5).append('-').append(true).append('-').append('x').append('-').append(null); sb.toString() + '|' + (r === sb)").asString())
            // methods inherited from the package-private AbstractStringBuilder (through visibility bridges)
            assertEquals("2,a,true,1a,z1a,za,0,a", c.eval("sb.setLength(2); [sb.length(), sb.charAt(0), sb.capacity() >= 2, String(sb.reverse()), String(sb.insert(0, 'z')), String(sb.deleteCharAt(1)), sb.indexOf('z'), sb.substring(1)].join()").asString())
            // text in JS: conversions go through toString(); a builder is not a string
            assertEquals("za,za,za,true,false", c.eval("[sb + '', `${'$'}{sb}`, String(sb), sb == 'za', sb === 'za'].join()").asString())
            // the host sees the same object, and changes show on both sides
            val host = c.eval("sb").asHostObject<StringBuilder>()
            host.append("!")
            assertEquals("za!", c.eval("sb.toString()").asString())
            c.eval("sb.append('?')")
            assertEquals("za!?", host.toString())
            // given back to Java: a String parameter takes the text, CharSequence and Object parameters the object
            c["o"] = Overloads()
            assertEquals("text:za!?", c.eval("o.text(sb)").asString())
            assertEquals("true,true", c.eval("[o.seq(sb) === sb, o.id(sb) === sb].join()").asString())
            assertSame(host, c.eval("o.seq(sb)").asHostObject<StringBuilder>())
            // NeonValue: the text through asString() and as(String), the object through as(StringBuilder)
            val v = c.eval("sb")
            assertFalse(v.isString)
            assertEquals("za!?", v.asString())
            assertEquals("za!?", v.`as`(String::class.java))
            assertSame(host, v.`as`(StringBuilder::class.java))
            // a builder made by the host, used from JS
            c["made"] = StringBuilder("x")
            c.eval("made.append(1).append(made.length())")
            assertEquals("x12", c.eval("made.toString()").asString())
        }
    }

    @Test
    fun otherCharSequences() {
        ctx().use { c ->
            c["buf"] = java.nio.CharBuffer.wrap("abc")
            assertEquals("3,b,bc,abc", c.eval("[buf.length(), buf.charAt(1), buf.subSequence(1, 3), buf].join()").asString())
            c["rot"] = Rot13("abc")
            assertEquals("nop,3,o", c.eval("[String(rot), rot.length(), rot.charAt(1)].join()").asString())
            c["o"] = Overloads()
            assertEquals("text:nop", c.eval("o.text(rot)").asString(), "the text where a String is expected")
            assertEquals("CharSequence", c.eval("o.pick(buf)").asString(), "the object where a CharSequence is expected")
            // strings made in JS are still JS strings in Java, whatever their representation (ropes)
            assertEquals("text:ab", c.eval("var s = 'a'; s += 'b'; o.text(s)").asString())
            assertEquals("string", c.eval("typeof o.seq(s)").asString())
        }
    }

    @Test
    fun methodsInheritedFromNonPublicClasses() {
        ctx().use { c ->
            c["v"] = Visible()
            assertEquals("hello from Visible,6,own,true", c.eval("[v.hello(), v.twice(3), v.own(), v.self() === v].join()").asString())
            assertEquals("x", c.eval("v.set('x'); v.get()").asString())
            assertEquals("undefined", c.eval("typeof v.count").asString(), "a field of a non-public class: out of reflection's reach")
            c["n"] = Narrowed()
            assertEquals("y!", c.eval("n.set('y'); n.get()").asString(), "the override, not the bridge")
            assertEquals("TypeError", c.eval("try { n.set({}) } catch (e) { e.name }").asString(), "the generic bridge set(Object) is no overload")
            c["a"] = Version(1)
            c["b"] = Version(2)
            assertEquals(-1, c.eval("a.compareTo(b)").asInt())
            assertEquals("TypeError", c.eval("try { a.compareTo('x') } catch (e) { e.name }").asString(), "the generic bridge compareTo(Object) is no overload")
            // the JDK's cases
            val keys = java.util.concurrent.ConcurrentHashMap<String, Int>().keySet(0)
            c["keys"] = keys
            assertTrue(c.eval("keys.getMap() !== undefined && typeof keys.removeAll").asString() == "function")
            c["date"] = java.time.chrono.HijrahDate.of(1447, 1, 1)
            assertTrue(c.eval("date.toString()").asString().startsWith("Hijrah-umalqura AH 1447-01-01"))
        }
    }

    @Test
    fun whichHostObjectsAreFunctions() {
        val ran = NamedRunnable()
        ctx().use { c ->
            val functions = linkedMapOf<String, Pair<Any, String>>(
                "Kotlin lambda" to ({ x: Int -> x + 1 } to "f(1)"),
                "Kotlin fun interface lambda" to (Transformer { it.uppercase() } to "f('a')"),
                "Kotlin function reference" to (::shout to "f('abc')"),
                "Runnable lambda" to (Runnable { ran.run() } to "f()"),
                "object : Runnable" to (object : Runnable { override fun run() = ran.run() } to "f()"),
                "object : fun interface" to (object : Transformer { override fun transform(s: String) = "$s!" } to "f('b')"),
                "named Runnable" to (ran to "f()"),
                "Comparator" to (String.CASE_INSENSITIVE_ORDER to "f('a', 'B')"),
                "java.util.function.Function" to (java.util.function.Function<Any?, Any?> { "fn $it" } to "f('c')"),
            )
            val expected = listOf("2", "A", "ABC!", "undefined", "undefined", "b!", "undefined", "-1", "fn c")
            for ((i, e) in functions.entries.withIndex()) {
                c["f"] = e.value.first
                assertEquals("function", c.eval("typeof f").asString(), e.key)
                assertEquals(expected[i], c.eval("String(${e.value.second})").asString(), e.key)
            }
            assertEquals(3, ran.runs)
            // objects that happen to implement a single-method interface are not functions
            val objects = linkedMapOf<String, Any>(
                "ArrayList (Iterable)" to arrayListOf(1),
                "File (Comparable)" to java.io.File("x"),
                "StringBuilder (Comparable)" to StringBuilder(),
                "Iterator" to listOf(1).iterator(),
                "named fun interface implementation" to NamedTransformer(),
                "Kotlin data class" to Pair(1, 2),
            )
            for ((name, v) in objects) {
                c["x"] = v
                assertEquals("object", c.eval("typeof x").asString(), name)
                assertEquals("TypeError", c.eval("try { x(); 'called' } catch (e) { e.name }").asString(), name)
            }
            c["x"] = NamedTransformer()
            assertEquals("named q", c.eval("x.transform('q')").asString(), "its method is a member")
        }
    }

    @Test
    fun jsFunctionsComeBackAsThemselves() {
        val keeper = Keeper()
        ctx().use { c ->
            c["keeper"] = keeper
            c.eval("var f = s => s + '?'; keeper.keep(f); var g = () => {}; keeper.keepRunnable(g)")
            assertEquals("a?", keeper.transformer!!.transform("a"))
            assertEquals("true,true,b?", c.eval("[keeper.getTransformer() === f, keeper.getRunnable() === g, keeper.getTransformer()('b')].join()").asString())
            // a JS array or object given as a List or Map comes back as itself
            c["o"] = Overloads()
            assertEquals("true,true,2", c.eval("var arr = [1, 2]; var obj = { k: 1 }; [o.sameList(arr) === arr, o.sameMap(obj) === obj, o.sameList(arr).length].join()").asString())
            // in another context the proxy is a host object: a function only through a declared functional interface
            ctx().use { c2 ->
                c2["keeper"] = keeper
                assertEquals("object,c?,function", c2.eval("[typeof keeper.getTransformer(), keeper.getTransformer().transform('c'), typeof keeper.getRunnable()].join()").asString())
            }
        }
    }

    @Test
    fun overloadsAreChosenTheSameWayEverywhere() {
        ctx().use { c ->
            c["o"] = Overloads()
            c.exposeClass("O", Overloads::class.java)
            val cases = listOf(
                "o.pick(null)" to "String", // the most specific of String, StringBuilder, int[] in a fixed order
                "o.pick('x')" to "String",
                "o.pick(1)" to "Object",
                "o.num(1)" to "int", // int is more specific than long
                "o.num(2 ** 40)" to "long",
                "o.num(1.5)" to "double",
                "o.num('x')" to "Object",
                "o.take(() => 1)" to "Runnable", // neither interface is more specific: the first by name
                "o['take(java.util.concurrent.Callable)'](() => 1)" to "Callable:1",
                "o['pick(java.lang.Object)']('x')" to "Object",
                "o['pick(Object)']('x')" to "Object",
                "o['pick(int[])'](null)" to "int[]",
                "O.stat(1)" to "static int",
                "O['stat(java.lang.String)']('1')" to "static String",
                "typeof o['pick(Nothing)']" to "undefined",
                "Object.getOwnPropertyNames(o).some(k => k.includes('('))" to "false",
            )
            for ((src, want) in cases) assertEquals(want, c.eval("String($src)").asString(), src)
            // the choice does not depend on the order reflection lists the overloads in
            val picks = Overloads::class.java.methods.filter { it.name == "pick" }
            val args = arrayOf<Any?>(Null)
            val chosen = HashSet<String>()
            for (perm in permutations(picks)) chosen.add(c.bridge.select(perm, args)!!.parameterTypes[0].name)
            assertEquals(setOf("java.lang.String"), chosen)
        }
    }

    @Test
    fun longsStayExact() {
        val maxSafe = 9007199254740991L
        ctx().use { c ->
            // a long is a number while the number is exact, and a BigInt beyond 2^53 - 1
            val cases = listOf(
                0L to "number", maxSafe to "number", -maxSafe to "number",
                maxSafe + 1 to "bigint", -maxSafe - 1 to "bigint", Long.MAX_VALUE to "bigint", Long.MIN_VALUE to "bigint",
            )
            for ((v, type) in cases) {
                c["v"] = v
                assertEquals("$type,$v", c.eval("[typeof v, String(v)].join()").asString(), "$v")
                assertEquals(v, c.eval("v").asLong(), "$v")
            }
            // through long parameters, return values and fields: the same value, never rounded to a nearby number
            val ids = Ids()
            val id = 1234567890123456789L // 1234567890123456800 as a number
            c["ids"] = ids
            c["id"] = id
            assertEquals("bigint,true,true,1234567890123456789", c.eval("[typeof ids.echo(id), ids.echo(id) === id, ids.boxed(id) === id, String(ids.echo(id))].join()").asString())
            c.eval("ids.last = id")
            assertEquals(id, ids.last)
            c.eval("ids.last = 9223372036854775807n")
            assertEquals(Long.MAX_VALUE, ids.last)
            assertEquals("true", c.eval("String(ids.last === 9223372036854775807n)").asString())
            // a BigInt takes a long parameter when it fits in 64 bits
            c["o"] = Overloads()
            assertEquals("long,long,Object", c.eval("[o.num(id), o.num(5n), o.num(2n ** 70n)].join()").asString())
            assertThrows(NeonException::class.java) { c.eval("ids.echo(2n ** 64n)") }
            // and does not fit beyond: a RangeError rather than a truncated long
            assertThrows(java.lang.ArithmeticException::class.java) { c.eval("2n ** 64n").asLong() }
            assertThrows(NeonException::class.java) { c.eval("2n ** 64n").`as`(Long::class.java) }
            assertEquals("RangeError", c.eval("try { ids.last = 2n ** 64n; 'no error' } catch (e) { e.name }").asString())
            assertEquals(Long.MAX_VALUE, ids.last)
            // Object parameters: a BigInt that fits in 64 bits is a Long, the type a long becomes on the way back, so
            // a small one comes back as a number; a larger one stays a BigInteger
            val m = HashMap<String, Any?>()
            c["m"] = m
            c.eval("m.put('small', 5n); m.put('id', id); m.put('huge', 2n ** 70n)")
            assertEquals(5L, m["small"])
            assertEquals(id, m["id"])
            assertEquals(java.math.BigInteger.ONE.shiftLeft(70), m["huge"])
            assertEquals("number,5,true,bigint", c.eval("[typeof m.get('small'), m.get('small'), m.get('id') === id, typeof m.get('huge')].join()").asString())
            assertEquals("number,bigint", c.eval("[typeof o.id(5n), typeof o.id(id)].join()").asString())
            // a number is a Long there only while it is an exact integer, as for long parameters: beyond 2^53 - 1 it
            // stays a Double, so it comes back as the same number rather than as a BigInt
            c.eval("m.put('safe', 2 ** 53 - 1); m.put('inexact', 2 ** 60)")
            assertEquals(maxSafe, m["safe"])
            assertEquals(Math.pow(2.0, 60.0), m["inexact"])
            assertEquals("number,true,true", c.eval("[typeof m.get('inexact'), m.get('inexact') === 2 ** 60, o.id(-(2 ** 60)) === -(2 ** 60)].join()").asString())
            // JS functions implementing host interfaces see longs the same way, and may return a BigInt for a long
            val f = c.eval("x => typeof x").`as`(java.util.function.LongFunction::class.java)
            assertEquals("number,bigint", listOf(f.apply(5), f.apply(Long.MAX_VALUE)).joinToString(","))
            val inc = c.eval("x => typeof x === 'bigint' ? x + 1n : x + 1").`as`(java.util.function.LongUnaryOperator::class.java)
            assertEquals(listOf(6L, maxSafe + 1, Long.MIN_VALUE + 1), listOf(inc.applyAsLong(5), inc.applyAsLong(maxSafe), inc.applyAsLong(Long.MIN_VALUE)))
            // a BigInt is not a number: compare with BigInt literals or strings
            assertEquals("false,true,true", c.eval("[id === 1234567890123456789, id === 1234567890123456789n, String(id) === '1234567890123456789'].join()").asString())
        }
    }

    @Test
    fun hostObjectsKeepTheirIdentity() {
        ctx().use { c ->
            val l1 = arrayListOf(1)
            val l2 = arrayListOf(1)
            c["a"] = l1
            c["b"] = l2
            c["a2"] = l1
            // the same object is the same wrapper; an equal one is another object
            assertEquals("true,false,1", c.eval("b.add(2); [a === a2, a === b, a.size()].join()").asString())
            // a list containing itself: no equals or hashCode is called on it
            val self = ArrayList<Any>()
            self.add(self)
            c["self"] = self
            assertEquals("true,1", c.eval("[self.get(0) === self, self.size()].join()").asString())
        }
    }

    @Test
    fun missingClassesLeaveOutOnlyTheirMembers() {
        // Sub's bad(Absent) and absent field name a class missing at run time, as an Android API of a later level would
        val loader = BytesLoader(javaClass.classLoader)
        loader.define("missing.Base", classBytes("missing/Base", "java/lang/Object") { it.stringMethod("ok", "ok") })
        val sub = loader.define("missing.Sub", classBytes("missing/Sub", "missing/Base") { cw ->
            cw.stringMethod("fine", "fine")
            cw.stringMethod("bad", "bad", "(Lmissing/Absent;)Ljava/lang/String;")
            cw.visitField(org.objectweb.asm.Opcodes.ACC_PUBLIC, "absent", "Lmissing/Absent;", null, null).visitEnd()
        })
        ctx().use { c ->
            c["x"] = sub.getConstructor().newInstance()
            // HotSpot resolves the signatures of a class's methods together, so fine() goes with bad() there; ART
            // resolves them one by one and keeps it (AndroidCheck checks that)
            assertEquals("ok,undefined,undefined,string", c.eval("[x.ok(), typeof x.bad, typeof x.absent, typeof x.toString()].join()").asString())
            c.exposeClass("Sub", sub)
            assertEquals("ok", c.eval("new Sub().ok()").asString())
        }
    }

    private fun <T> permutations(xs: List<T>): List<List<T>> =
        if (xs.size <= 1) listOf(xs) else xs.indices.flatMap { i -> permutations(xs.filterIndexed { j, _ -> j != i }).map { listOf(xs[i]) + it } }
}
