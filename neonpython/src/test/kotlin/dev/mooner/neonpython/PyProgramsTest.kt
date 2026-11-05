package dev.mooner.neonpython

import dev.mooner.neonjs.compiler.CodeBlock
import dev.mooner.neonjs.jit.Jit
import dev.mooner.neonjs.jit.opt.T2
import dev.mooner.neonjs.jit.opt.T2Code
import dev.mooner.neonjs.runtime.RuntimeConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Python programs (src/test/resources/programs/name.py) and what CPython 3.14 printed for each (name.out): the same
 * output interpreted, compiled (every function's code the optimizing tier's baseline before its first call) and
 * adaptive (compiled after a few calls, loops continuing in compiled code).
 */
@Timeout(120)
class PyProgramsTest {
    enum class Mode { INTERPRETED, COMPILED, ADAPTIVE }

    private fun config(mode: Mode) = RuntimeConfig().apply {
        executionMode = when (mode) {
            Mode.INTERPRETED -> Jit.MODE_INTERPRETER
            Mode.COMPILED -> Jit.MODE_COMPILED
            Mode.ADAPTIVE -> Jit.MODE_ADAPTIVE
        }
        jitThreshold = 2
        // compiled on the calling thread: each run the same
        backgroundJit = false
    }

    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/programs/$name")?.readBytes()?.toString(Charsets.UTF_8) ?: fail("no resource $name")

    private fun run(src: String, mode: Mode): Pair<String, PyDict> {
        val out = StringBuilder()
        val globals = PyContext(out, config(mode)).exec(src)
        return out.toString() to globals
    }

    @ParameterizedTest
    @MethodSource("programs")
    fun programPrintsWhatCPythonPrints(name: String) {
        val src = resource("$name.py")
        val expected = resource("$name.out")
        for (mode in Mode.entries) {
            val (out, _) = try {
                run(src, mode)
            } catch (e: PyException) {
                fail("$name ($mode): ${e.type}: ${e.message} at line ${e.line} of ${e.function}", e)
            }
            assertEquals(expected, out, "$name ($mode)")
        }
        // (that compiled mode ran the tier's code: noBlockFallsBackToTheInterpreter)
    }

    @Test
    fun functionsRunTheTiersCode() {
        val (out, globals) = run(resource("fib.py"), Mode.COMPILED)
        assertEquals(resource("fib.out"), out)
        val fib = globals[PyStr("fib")] as PyFunction
        assertTrue(fib.code.compiled is T2Code, "fib: ${fib.code.compiled} (${T2.baselineReasons.keys})")
        assertTrue(fib.code.jitFailed.not())
    }

    @Test
    fun blocksOfOneGraphShareTheirCodeNotTheirConstants() {
        // each is LOAD_CONST 0; RETURN: one graph, one baseline class, which must read its block's constant
        val src = "def a():\n    return None\ndef b():\n    return True\ndef c():\n    return False\n" +
            "def d():\n    return 7\ndef e():\n    return 'x'\nprint(a(), b(), c(), d(), e())\n" +
            "def f(x):\n    return x if True else 0\ndef g(x):\n    return x if False else 0\nprint(f(1), g(1))\n"
        for (mode in Mode.entries) assertEquals("None True False 7 x\n1 0\n", run(src, mode).first, "$mode")
    }

    @Test
    fun noBlockFallsBackToTheInterpreter() {
        // every block a program ran, compiled mode: the tier's code, none failing to compile (which would run it
        // interpreted, its output the same)
        val reasonsBefore = T2.baselineReasons.keys.toSet()
        for (name in programs()) {
            val cb = PyCompiler.compileModule(resource("$name.py"))
            val ctx = PyContext(StringBuilder(), config(Mode.COMPILED))
            PyInterpreter.call(ctx, PyFunction(cb, PyDict(), "<module>"), arrayOfNulls(0))
            for (b in blocks(cb)) {
                assertTrue(!b.jitFailed, "$name: ${b.name} not compiled (${T2.baselineReasons.keys})")
                if (b.compiled != null) assertTrue(b.compiled is T2Code, "$name: ${b.name}: ${b.compiled}")
            }
            assertTrue(cb.compiled is T2Code, "$name: the module's code")
        }
        assertEquals(emptySet<String>(), T2.baselineReasons.keys - reasonsBefore)
    }

    @Test
    fun loopsGoOnInCompiledCode() {
        val before = Jit.osrEntered.get()
        val (out, _) = run(resource("loops.py"), Mode.ADAPTIVE)
        assertEquals(resource("loops.out"), out)
        assertTrue(Jit.osrEntered.get() > before, "no loop entered compiled code (${T2.baselineReasons.keys})")
    }

    /** CPython 3.14's exception, its message and line for each program (recorded with CPython). */
    @Test
    fun errorsAreCPythons() {
        val cases = listOf(
            "def f():\n    print(y)\n    y = 1\nf()\n" to Triple("UnboundLocalError", "cannot access local variable 'y' where it is not associated with a value", 2),
            "x = 1\nprint(nope)\n" to Triple("NameError", "name 'nope' is not defined", 2),
            "a = 1\nb = a // 0\n" to Triple("ZeroDivisionError", "division by zero", 2),
            "a = 1.5\nb = a / 0\n" to Triple("ZeroDivisionError", "division by zero", 2),
            "def f(a):\n    return a\nf(1, 2)\n" to Triple("TypeError", "f() takes 1 positional argument but 2 were given", 3),
            "xs = [1]\nxs[5]\n" to Triple("IndexError", "list index out of range", 2),
            "d = {}\nd['k']\n" to Triple("KeyError", "'k'", 2),
            "x = 1 + 'a'\n" to Triple("TypeError", "unsupported operand type(s) for +: 'int' and 'str'", 1),
            "x = 'a' < 1\n" to Triple("TypeError", "'<' not supported between instances of 'str' and 'int'", 1),
            "a, b = [1, 2, 3]\n" to Triple("ValueError", "too many values to unpack (expected 2, got 3)", 1),
            "def r(n):\n    return r(n + 1)\nr(0)\n" to Triple("RecursionError", "maximum recursion depth exceeded", 2),
        )
        for (mode in Mode.entries) for ((src, want) in cases) {
            val e = try {
                run(src, mode)
                fail("no exception ($mode):\n$src")
            } catch (e: PyException) {
                e
            }
            assertEquals(want, Triple(e.type, e.message, e.line), "$mode:\n$src")
        }
    }

    @Test
    fun aLocalReadBeforeItsStoreInALoopIsChecked() {
        // the first iteration reads `prev` before any store: an UnboundLocalError in every mode, compiled code too
        val src = "def f(n):\n    for i in range(n):\n        if i > 0:\n            print(prev)\n        prev = i\n    return prev\nprint(f(3))\nf(0)\n"
        for (mode in Mode.entries) {
            val out = StringBuilder()
            val e = try {
                PyContext(out, config(mode)).exec(src)
                fail("no exception ($mode)")
            } catch (e: PyException) {
                e
            }
            assertEquals("0\n1\n2\n", out.toString(), "$mode")
            assertEquals("UnboundLocalError" to 6, e.type to e.line, "$mode")
        }
    }

    companion object {
        @JvmStatic
        fun programs(): List<String> = listOf(
            "fib", "loops", "bigints", "lists", "dicts", "strings", "tuples", "floats", "logic", "globals", "primes",
        )

        /** The code blocks of [cb] and every function it defines. */
        fun blocks(cb: CodeBlock): List<CodeBlock> = listOf(cb) + cb.constants.filterIsInstance<CodeBlock>().flatMap { blocks(it) }
    }
}
