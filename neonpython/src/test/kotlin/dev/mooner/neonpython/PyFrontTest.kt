package dev.mooner.neonpython

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigInteger

/** The tokenizer, the parser and the compiler's checks, on their own. */
class PyFrontTest {
    private fun kinds(src: String) = PyLexer(src).tokenize().joinToString(" ") {
        when (it.kind) {
            PyToken.Kind.NAME, PyToken.Kind.OP -> it.text
            PyToken.Kind.NUMBER, PyToken.Kind.STRING -> "${it.value}"
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
        val values = t.filter { it.kind == PyToken.Kind.NUMBER || it.kind == PyToken.Kind.STRING }.map { it.value }
        assertEquals(listOf<Any?>(255L, 15L, 5L, 1000L, 3.5, 1000.0, BigInteger("99999999999999999999"), "a\n", "a\\n", "x\ny"), values)
        assertThrows<PySyntaxError> { PyLexer("012").tokenize() }
        assertThrows<PySyntaxError> { PyLexer("'abc").tokenize() }
    }

    @Test
    fun expressionsParseByPrecedence() {
        fun show(e: Expr): String = when (e) {
            is Name -> e.id
            is Constant -> PyRt.repr(e.value)
            is BinOp -> "(${show(e.left)} ${e.op} ${show(e.right)})"
            is UnaryOp -> "(${e.op} ${show(e.operand)})"
            is BoolOp -> e.values.joinToString(" ${e.op} ", "(", ")") { show(it) }
            is Compare -> "(" + show(e.left) + e.ops.indices.joinToString("") { " ${e.ops[it]} ${show(e.comparators[it])}" } + ")"
            is Call -> show(e.func) + e.args.joinToString(", ", "(", ")") { show(it) }
            is Subscript -> "${show(e.value)}[${show(e.index)}]"
            is Attribute -> "${show(e.value)}.${e.attr}"
            is IfExp -> "(${show(e.body)} if ${show(e.test)} else ${show(e.orelse)})"
            is TupleExpr -> e.elts.joinToString(", ", "(", ")") { show(it) }
            is ListExpr -> e.elts.joinToString(", ", "[", "]") { show(it) }
            is DictExpr -> e.keys.indices.joinToString(", ", "{", "}") { "${show(e.keys[it])}: ${show(e.values[it])}" }
        }
        fun parse(src: String) = show(((PyParser(src).parseModule().single()) as ExprStmt).value)
        assertEquals("(1 + (2 * 3))", parse("1 + 2 * 3"))
        assertEquals("(- (2 ** 2))", parse("-2 ** 2"))
        assertEquals("(2 ** (- 1))", parse("2 ** -1"))
        assertEquals("(2 ** (3 ** 2))", parse("2 ** 3 ** 2"))
        assertEquals("((not (a < b < c)) or (d and e))", parse("not a < b < c or d and e"))
        assertEquals("(x not in y)", parse("x not in y"))
        assertEquals("(a is not None)", parse("a is not None"))
        assertEquals("f(a, g.h(1)[2])", parse("f(a, g.h(1)[2])"))
        assertEquals("(1 if c else 2)", parse("1 if c else 2"))
        assertEquals("(1, [2, 3], {'k': (4)})", parse("1, [2, 3], {'k': (4,)}"))
        assertEquals("'ab'", parse("'a' 'b'"))
    }

    @Test
    fun statementsAndTheirErrors() {
        val body = PyParser("a = b = 1\nx += 2\nfor i, j in y: pass\nwhile 1:\n  break\nelse: z = 1\n").parseModule()
        assertEquals(listOf("Assign", "AugAssign", "For", "While"), body.map { it.javaClass.simpleName })
        assertEquals(2, (body[0] as Assign).targets.size)
        assertThrows<PySyntaxError> { PyParser("f() = 1\n").parseModule() }
        assertThrows<PySyntaxError> { PyParser("if x:\npass\n").parseModule() }
        assertThrows<PySyntaxError> { PyParser("def f(a, a): pass\n").parseModule() }
        assertThrows<PySyntaxError> { PyCompiler.compileModule("break\n") }
        assertThrows<PySyntaxError> { PyCompiler.compileModule("return 1\n") }
        val e = assertThrows<PySyntaxError> { PyParser("import os\n").parseModule() }
        assertTrue(e.message!!.contains("not supported"), e.message)
    }

    @Test
    fun localsThatMayHaveNoValueAreChecked() {
        val cb = PyCompiler.compileModule("def f(a):\n  if a:\n    b = 1\n  c = 2\n  return a + b + c\n")
        val f = cb.constants.filterIsInstance<dev.mooner.neonjs.compiler.CodeBlock>().single()
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
