package dev.mooner.neonpython

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The parser against CPython 3.14's: each snippet of ast/corpus.py parsed, and its tree dumped as `ast.dump` prints
 * it, the same as what CPython printed (ast/corpus.ast, recorded by ast/record.py); a snippet CPython refuses is
 * refused (its message compared, mismatches reported). With `-Dneonpy.astDir=DIR` (a tree of .py files and their
 * dumps in .ast files beside them, as ast/record_tree.py writes them), every file there too.
 */
class PyAstTest {
    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/ast/$name")!!.readBytes().toString(Charsets.UTF_8).replace("\r\n", "\n")

    @Test
    fun theCorpusParsesAsCPythonParsesIt() {
        val snippets = resource("corpus.py").split("\n#---\n")
        val expected = resource("corpus.ast").trimEnd('\n').split("\n")
        assertTrue(snippets.size == expected.size, "${snippets.size} snippets, ${expected.size} dumps")
        val failures = ArrayList<String>()
        val messages = ArrayList<String>()
        for ((i, src) in snippets.withIndex()) {
            val want = expected[i]
            val got = try {
                PyAst.dump(PyParser(src).parseModule())
            } catch (e: PySyntaxError) {
                "SyntaxError: " + e.msg
            } catch (e: Exception) {
                "${e.javaClass.simpleName}: ${e.message}"
            }
            if (got == want) continue
            if (got.startsWith("SyntaxError: ") && want.startsWith("SyntaxError: ")) {
                messages.add("#$i ${src.lines().first()}\n  CPython: $want\n  NeonPy:  $got")
                continue
            }
            failures.add("#$i ${src.replace("\n", "\\n").take(120)}\n  CPython: $want\n  NeonPy:  $got")
        }
        if (messages.isNotEmpty()) println("SyntaxErrors with other messages:\n" + messages.joinToString("\n"))
        assertTrue(failures.isEmpty(), "${failures.size} of ${snippets.size} differ:\n" + failures.joinToString("\n"))
    }

    @Test
    fun aTreeOfFilesParsesAsCPythonParsesIt() {
        val dir = System.getProperty("neonpy.astDir")?.let { File(it) } ?: return
        val failures = ArrayList<String>()
        var n = 0
        dir.walkTopDown().filter { it.isFile && it.name.endsWith(".py") }.forEach { f ->
            val dump = File(f.path.removeSuffix(".py") + ".ast").takeIf { it.isFile } ?: return@forEach
            n++
            val want = dump.readText(Charsets.UTF_8).trimEnd('\n')
            val got = try {
                PyAst.dump(PyParser(f.readText(Charsets.UTF_8)).parseModule())
            } catch (e: PySyntaxError) {
                "SyntaxError: " + e.msg
            } catch (e: Exception) {
                "${e.javaClass.simpleName}: ${e.message}"
            }
            if (got != want) {
                val k = got.zip(want).indexOfFirst { (a, b) -> a != b }.let { if (it < 0) minOf(got.length, want.length) else it }
                failures.add("${f.relativeTo(dir)} at $k:\n  CPython: ${want.substring(maxOf(0, k - 80), minOf(want.length, k + 120))}\n  NeonPy:  ${got.substring(maxOf(0, k - 80), minOf(got.length, k + 120))}")
            }
        }
        println("$n files, ${failures.size} differ")
        assertTrue(failures.isEmpty(), "${failures.size} of $n files differ:\n" + failures.take(30).joinToString("\n"))
    }
}
