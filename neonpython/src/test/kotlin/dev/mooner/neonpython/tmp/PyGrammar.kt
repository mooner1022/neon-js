package dev.mooner.neonpython.tmp

import dev.mooner.neonpython.PyBool
import dev.mooner.neonpython.PyBuiltin
import dev.mooner.neonpython.PyContext
import dev.mooner.neonpython.PyModuleLoader
import dev.mooner.neonpython.PyModuleSource
import dev.mooner.neonpython.PyStr
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Scratch (not committed): CPython's syntax tests (Lib/test) with stand-in modules, each's results to grammar/out.
 * The harness lives in Desktop/neonpy-conformance (see its README); without it, or with an empty tests.txt, nothing runs.
 */
class PyGrammar {
    @Test
    fun run() {
        val root = "C:/Users/moonm/Desktop/neonpy-conformance/grammar"
        Assumptions.assumeTrue(File("$root/tests.txt").isFile, "no conformance harness")
        val shims = "$root/shims"
        val tests = File("$root/tests.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        fun read(p: String): String? = File(p).takeIf { it.isFile }?.readText()
        val loader = PyModuleLoader { name ->
            val base = "$shims/" + name.replace('.', '/')
            read("$base/__init__.py")?.let { PyModuleSource(it, "$base/__init__.py", true) } ?: read("$base.py")?.let { PyModuleSource(it, "$base.py", false) }
        }
        File("$root/out").mkdirs()
        for (t in tests) {
            val out = StringBuilder()
            val err = StringBuilder()
            try {
                val ctx = PyContext(out, dev.mooner.neonjs.runtime.VmConfig(), loader, err)
                ctx.builtins[PyStr("neon_regex_search")] = PyBuiltin("neon_regex_search") { _, a ->
                    PyBool.of(Regex((a[0] as PyStr).s).containsMatchIn((a[1] as PyStr).s))
                }
                ctx.exec("import sys\nsys.path.insert(0, '$shims/test')\n")
                if (t.startsWith("doctest_")) {
                    ctx.builtins[PyStr("__doctest_source__")] = PyStr(File("$shims/test/${t.removePrefix("doctest_")}.py").readText())
                    ctx.exec("import doctest_lite\ndoctest_lite.run(__doctest_source__)\n")
                } else ctx.exec(File("$shims/test/$t.py").readText())
            } catch (e: Throwable) {
                out.append("\n!! ").append(e).append('\n')
                for (x in e.stackTrace.take(12)) out.append("   at ").append(x).append('\n')
            }
            File("$root/out/$t.neonpy.txt").writeText(out.toString())
        }
    }
}
