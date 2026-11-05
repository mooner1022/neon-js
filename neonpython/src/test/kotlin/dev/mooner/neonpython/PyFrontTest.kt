package dev.mooner.neonpython

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigInteger

/** The tokenizer, the parser and the compiler's checks, on their own (PyAstTest compares the parser with CPython's). */
class PyFrontTest {
    private fun kinds(src: String) = PyLexer(src).tokenize().joinToString(" ") {
        when (it.kind) {
            PyToken.Kind.NAME, PyToken.Kind.OP -> it.text
            PyToken.Kind.NUMBER -> "${it.value}"
            PyToken.Kind.STRING -> "${(it.value as StrLit).value}"
            PyToken.Kind.FSTRING_MIDDLE -> "'${it.value}'"
            else -> it.kind.name
        }
    }

    @Test
    fun indentationBecomesTokens() {
        assertEquals("if x : NEWLINE INDENT y = 1 NEWLINE DEDENT z NEWLINE END", kinds("if x:\n    y = 1\nz\n"))
        // blank lines, comments, brackets across lines, a backslash
        assertEquals("a = [ 1 , 2 ] NEWLINE b = 3 + 4 NEWLINE END", kinds("a = [1,\n  2]  # c\n\n   # only a comment\nb = 3 + \\\n 4"))
        // dedents at the end
        assertEquals("def f ( ) : NEWLINE INDENT if 1 : NEWLINE INDENT pass NEWLINE DEDENT DEDENT END", kinds("def f():\n  if 1:\n    pass"))
        val e = assertThrows<PySyntaxError> { PyLexer("if x:\n    a\n  b\n").tokenize() }
        assertEquals("IndentationError", e.type)
    }

    @Test
    fun numbersAndStrings() {
        val t = PyLexer("0x_ff 0o17 0b101 1_000 3.5 1e3 99999999999999999999 'a\\n' r'a\\n' \"\"\"x\ny\"\"\"").tokenize()
        val values = t.filter { it.kind == PyToken.Kind.NUMBER || it.kind == PyToken.Kind.STRING }
            .map { if (it.value is StrLit) (it.value as StrLit).value else it.value }
        assertEquals(listOf<Any?>(255L, 15L, 5L, 1000L, 3.5, 1000.0, BigInteger("99999999999999999999"), "a\n", "a\\n", "x\ny"), values)
        assertThrows<PySyntaxError> { PyLexer("012").tokenize() }
        assertThrows<PySyntaxError> { PyLexer("'abc").tokenize() }
    }

    @Test
    fun fStringsAreTokenized() {
        // PEP 701: the replacement fields' tokens between { and }, the format spec's text after :
        assertEquals("FSTRING_START 'a' { x ! r : '>' { w } } 'b' FSTRING_END NEWLINE END", kinds("f'a{x!r:>{w}}b'"))
        assertEquals("FSTRING_START { d [ k ] } FSTRING_END NEWLINE END", kinds("f'{d['k']}'"))
        assertEquals("FSTRING_START 'a{b}' FSTRING_END NEWLINE END", kinds("f'a{{b}}'"))
    }

    @Test
    fun statementsAndTheirErrors() {
        val body = PyParser("a = b = 1\nx += 2\nfor i, j in y: pass\nwhile 1:\n  break\nelse: z = 1\n").parseModule().body
        assertEquals(listOf("Assign", "AugAssign", "For", "While"), body.map { it.pyName })
        assertEquals(2, (body[0] as Assign).targets.size)
        assertThrows<PySyntaxError> { PyParser("f() = 1\n").parseModule() }
        assertThrows<PySyntaxError> { PyParser("if x:\npass\n").parseModule() }
        assertThrows<PySyntaxError> { PyCompiler.compileModule("break\n") }
        assertThrows<PySyntaxError> { PyCompiler.compileModule("return 1\n") }
        val e = assertThrows<PySyntaxError> { PyCompiler.compileModule("import os\n") }
        assertTrue(e.message!!.contains("not supported"), e.message)
    }

    @Test
    fun scopeErrorsAreCPythons() {
        // (message and line as CPython 3.14's compile gives them)
        val cases = listOf(
            "def f():\n    nonlocal x\n" to ("no binding for nonlocal 'x' found" to 2),
            "nonlocal x\n" to ("nonlocal declaration not allowed at module level" to 1),
            "def f(a):\n    global a\n" to ("name 'a' is parameter and global" to 2),
            "def f(a, a): pass\n" to ("duplicate argument 'a' in function definition" to 1),
            "f(a=1, a=2)\n" to ("keyword argument repeated: a" to 1),
            "def f():\n    x = 1\n    global x\n" to ("name 'x' is assigned to before global declaration" to 3),
            "def f():\n    print(x)\n    global x\n" to ("name 'x' is used prior to global declaration" to 3),
            "return 1\n" to ("'return' outside function" to 1),
            "break\n" to ("'break' outside loop" to 1),
            "def g():\n    x = 1\n    def h():\n        x = 2\n        nonlocal x\n" to ("name 'x' is assigned to before nonlocal declaration" to 5),
            "def f(): yield\nclass C:\n    yield\n" to ("'yield' outside function" to 3),
        )
        for ((src, want) in cases) {
            val e = assertThrows<PySyntaxError>(src) { PyCompiler.compileModule(src) }
            assertEquals(want, e.msg to e.line, src)
        }
    }

    @Test
    fun localsThatMayHaveNoValueAreChecked() {
        val cb = PyCompiler.compileModule("def f(a):\n  if a:\n    b = 1\n  c = 2\n  return a + b + c\n")
        val f = cb.constants.filterIsInstance<dev.mooner.neonjs.compiler.CodeUnit>().single()
        val loads = ArrayList<Pair<Int, Int>>()
        var pc = 0
        while (pc < f.code.size) {
            val op = f.code[pc]
            if (op == PyOp.LOAD_FAST || op == PyOp.LOAD_FAST_CHECK) loads.add(op to f.code[pc + 1])
            pc += PyOp.length(op)
        }
        // a (a parameter) and c (stored on every path) are loaded unchecked, b (stored on one) checked
        val names = (f.dialectData as PyCodeInfo).localNames
        assertEquals(listOf("a" to PyOp.LOAD_FAST, "a" to PyOp.LOAD_FAST, "b" to PyOp.LOAD_FAST_CHECK, "c" to PyOp.LOAD_FAST),
            loads.map { names[it.second] to it.first })
    }
}
