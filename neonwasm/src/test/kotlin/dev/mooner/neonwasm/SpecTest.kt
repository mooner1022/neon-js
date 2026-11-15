package dev.mooner.neonwasm

import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * The WebAssembly spec tests (the core tests of WebAssembly/spec at the commit tools/wasm-spec.sh fetches), as
 * `wasm-tools json-from-wast` converts them: one test per .wast file, each of its commands run in order. A command
 * that fails is an error unless spec-known-failures.txt lists it (file:line); one listed that passes is reported.
 *
 * Modules in the text format (assert_malformed of a .wat) are skipped: NeonWasm reads binaries only. For
 * assert_invalid and assert_malformed, rejection is what is checked; the messages are compared and their mismatches
 * counted, not failed.
 */
class SpecTest {
    @TestFactory
    fun spec(): List<DynamicTest> {
        val dir = File(System.getProperty("neonwasm.spec") ?: "build/wasm-spec")
        if (!dir.isDirectory) {
            return listOf(dynamicTest("spec tests") {
                assumeTrue(false, "no $dir: tools/wasm-spec.sh converts the spec tests (with wasm-tools)")
            })
        }
        val known = HashSet<String>()
        System.getProperty("neonwasm.known")?.let { File(it) }?.takeIf { it.isFile }?.forEachLine { l ->
            val t = l.substringBefore('#').trim()
            if (t.isNotEmpty()) known.add(t)
        }
        val files = dir.listFiles()!!.filter { File(it, it.name + ".json").isFile }.sortedBy { it.name }
        val total = Totals()
        return files.map { d -> dynamicTest(d.name) { runFile(d, known, total) } } +
            dynamicTest("summary") { println(total) }
    }

    private class Totals {
        var passed = 0
        var failed = 0
        var known = 0
        var skipped = 0
        var messages = 0
        override fun toString() =
            "spec tests: $passed passed, $failed failed ($known known), $skipped skipped; $messages rejections with another message"
    }

    private class Skip : RuntimeException()

    private fun runFile(d: File, known: Set<String>, total: Totals) {
        @Suppress("UNCHECKED_CAST")
        val json = Json.parse(File(d, d.name + ".json").readText()) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val commands = json["commands"] as List<Map<String, Any?>>
        val r = Runner(d)
        val unexpected = ArrayList<String>()
        val nowPassing = ArrayList<String>()
        var passed = 0
        var skipped = 0
        for (c in commands) {
            val key = "${d.name}:${(c["line"] as Double).toInt()}"
            val error: String? = try {
                r.command(c)
                null
            } catch (_: Skip) {
                skipped++
                continue
            } catch (e: Throwable) {
                "${c["type"]}: ${e.message ?: e}"
            }
            if (error == null) {
                passed++
                if (key in known) nowPassing.add(key)
            } else if (key in known) total.known++
            else unexpected.add("$key $error")
        }
        total.passed += passed
        total.failed += unexpected.size
        total.skipped += skipped
        total.messages += r.messageMismatches.size
        println("${d.name}: $passed passed, ${unexpected.size} failed, $skipped skipped" +
            (if (r.messageMismatches.isNotEmpty()) "; other messages: " + r.messageMismatches.take(5).joinToString("; ") else ""))
        for (k in nowPassing) println("NOW PASSING $k")
        if (unexpected.isNotEmpty()) fail<Unit>("${unexpected.size} failed:\n" + unexpected.take(40).joinToString("\n"))
    }

    /** A host reference the tests pass as `ref.extern N`. */
    private data class HostRef(val n: Long)

    private class Runner(private val dir: File) {
        val ctx = WasmContext()
        val messageMismatches = ArrayList<String>()
        private var current: WasmInstance? = null
        private val named = HashMap<String, WasmInstance>()
        private val registered = HashMap<String, WasmInstance>()

        private val spectest: Map<String, Any> = run {
            fun fn(vararg params: Int) = HostFunction(FuncType(params, IntArray(0))) { null }
            mapOf(
                "print" to fn(),
                "print_i32" to fn(ValType.I32),
                "print_i64" to fn(ValType.I64),
                "print_f32" to fn(ValType.F32),
                "print_f64" to fn(ValType.F64),
                "print_i32_f32" to fn(ValType.I32, ValType.F32),
                "print_f64_f64" to fn(ValType.F64, ValType.F64),
                "global_i32" to WasmGlobal(GlobalType(ValType.I32, false), 666),
                "global_i64" to WasmGlobal(GlobalType(ValType.I64, false), 666L),
                "global_f32" to WasmGlobal(GlobalType(ValType.F32, false), 666.6f),
                "global_f64" to WasmGlobal(GlobalType(ValType.F64, false), 666.6),
                "table" to WasmTable(ValType.FUNCREF, 10, 20),
                "memory" to WasmMemory(MemType(Limits(1, 2))),
            )
        }

        private val imports = WasmImports { module, name ->
            if (module == "spectest") spectest[name] else registered[module]?.exports?.get(name)
        }

        private fun load(c: Map<String, Any?>): WasmModule {
            val file = (c["binary_filename"] ?: c["filename"]) as String
            return WasmModule.decode(File(dir, file).readBytes())
        }

        fun command(c: Map<String, Any?>) {
            when (val type = c["type"] as String) {
                "module" -> {
                    val inst = ctx.instantiate(load(c), imports)
                    current = inst
                    (c["name"] as String?)?.let { named[it] = inst }
                }
                "register" -> {
                    val inst = (c["name"] as String?)?.let { named[it] } ?: current ?: error("no module")
                    registered[c["as"] as String] = inst
                }
                "action" -> action(c)
                "assert_return" -> {
                    val got = action(c)
                    @Suppress("UNCHECKED_CAST")
                    val expected = c["expected"] as List<Map<String, Any?>>
                    if (got.size != expected.size) error("${got.size} results, not ${expected.size}")
                    for (i in expected.indices) check(expected[i], got[i])
                }
                "assert_trap", "assert_exhaustion" -> {
                    val text = c["text"] as String
                    try {
                        action(c)
                    } catch (e: WasmTrap) {
                        if (!sameMessage(e.message!!, text)) error("trapped with \"${e.message}\", not \"$text\"")
                        return
                    }
                    error("no trap (\"$text\" expected)")
                }
                "assert_invalid", "assert_malformed" -> {
                    if (c["module_type"] == "text") throw Skip()
                    val text = c["text"] as String
                    try {
                        load(c)
                    } catch (e: WasmException) {
                        if (!sameMessage(e.message!!, text)) messageMismatches.add("${c["line"]}: \"${e.message}\" for \"$text\"")
                        return
                    }
                    error("accepted (\"$text\" expected)")
                }
                "assert_unlinkable", "assert_uninstantiable" -> {
                    val text = c["text"] as String
                    val m = load(c)
                    try {
                        ctx.instantiate(m, imports)
                    } catch (e: WasmException) {
                        val expectedKind = if (type == "assert_unlinkable") e is WasmLinkException else e is WasmTrap
                        if (!expectedKind) error("failed with ${e.javaClass.simpleName} \"${e.message}\"")
                        if (!sameMessage(e.message!!, text)) messageMismatches.add("${c["line"]}: \"${e.message}\" for \"$text\"")
                        return
                    }
                    error("instantiated (\"$text\" expected)")
                }
                else -> error("unknown command $type")
            }
        }

        /** Whether a message is the expected one: either may say more (`uninitialized element 2`, `unknown global 3`). */
        private fun sameMessage(got: String, expected: String) = got.startsWith(expected) || expected.startsWith(got)

        private fun action(c: Map<String, Any?>): Array<Any?> {
            @Suppress("UNCHECKED_CAST")
            val a = c["action"] as Map<String, Any?>
            val inst = (a["module"] as String?)?.let { named[it] ?: error("no module $it") } ?: current ?: error("no module")
            val field = a["field"] as String
            return when (a["type"]) {
                "invoke" -> {
                    val f = inst.function(field) ?: error("no function $field")
                    @Suppress("UNCHECKED_CAST")
                    val args = (a["args"] as List<Map<String, Any?>>).map { value(it) }.toTypedArray()
                    f.invokeRaw(args)
                }
                "get" -> {
                    val g = inst.global(field) ?: error("no global $field")
                    arrayOf(if (ValType.isRef(g.type.valType)) g.ref else g.bits)
                }
                else -> error("unknown action ${a["type"]}")
            }
        }

        /** An argument, raw: a number's bits as a Long, a reference as it is. */
        private fun value(v: Map<String, Any?>): Any? {
            val s = v["value"] as String
            return when (v["type"]) {
                "externref" -> if (s == "null") null else HostRef(s.toLong())
                "funcref" -> if (s == "null") null else error("funcref $s")
                else -> parseBits(s)
            }
        }

        /** A value's bits: integers as wasm-tools writes them (signed decimal), floats' bits (unsigned decimal). */
        private fun parseBits(s: String): Long = if (s.startsWith("-")) s.toLong() else java.lang.Long.parseUnsignedLong(s)

        private fun check(e: Map<String, Any?>, got: Any?) {
            val s = e["value"] as String
            when (val t = e["type"]) {
                "i32" -> if ((got as Long).toInt() != parseBits(s).toInt()) error("i32 ${(got).toInt()}, not $s")
                "i64" -> if (got as Long != parseBits(s)) error("i64 $got, not $s")
                "f32" -> {
                    val bits = (got as Long).toInt()
                    val ok = when (s) {
                        "nan:canonical" -> bits and 0x7FFFFFFF == 0x7FC00000
                        "nan:arithmetic" -> bits and 0x7FC00000 == 0x7FC00000
                        else -> bits == parseBits(s).toInt()
                    }
                    if (!ok) error("f32 ${java.lang.Float.intBitsToFloat(bits)} (0x${Integer.toHexString(bits)}), not $s")
                }
                "f64" -> {
                    val bits = got as Long
                    val ok = when (s) {
                        "nan:canonical" -> bits and Long.MAX_VALUE == 0x7FF8000000000000L
                        "nan:arithmetic" -> bits and 0x7FF8000000000000L == 0x7FF8000000000000L
                        else -> bits == parseBits(s)
                    }
                    if (!ok) error("f64 ${java.lang.Double.longBitsToDouble(bits)} (0x${java.lang.Long.toHexString(bits)}), not $s")
                }
                "externref" -> {
                    val want = if (s == "null") null else HostRef(s.toLong())
                    if (got != want) error("externref $got, not $s")
                }
                "funcref" -> {
                    if (s == "null") { if (got != null) error("funcref $got, not null") }
                    else if (got !is WasmFunction) error("funcref $got, not a function")
                }
                else -> error("unknown type $t")
            }
        }
    }
}
