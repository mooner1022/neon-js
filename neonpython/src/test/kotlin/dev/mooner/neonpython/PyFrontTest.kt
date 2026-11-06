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
            "def f():\n    try:\n        pass\n    except* ValueError:\n        return 1\n" to ("'break', 'continue' and 'return' cannot appear in an except* block" to 5),
            "for i in range(2):\n    try:\n        pass\n    except* ValueError:\n        break\n" to ("'break', 'continue' and 'return' cannot appear in an except* block" to 5),
            "try:\n    pass\nexcept*:\n    pass\n" to ("expected one or more exception types" to 3),
            "try:\n    pass\nexcept:\n    pass\nexcept ValueError:\n    pass\n" to ("default 'except:' must be last" to 3),
            "try:\n    pass\nexcept ValueError:\n    pass\nexcept* TypeError:\n    pass\n" to ("cannot have both 'except' and 'except*' on the same 'try'" to 5),
            "[(yield) for x in []]\n" to ("'yield' inside list comprehension" to 1),
            "{(yield): 1 for x in []}\n" to ("'yield' inside dict comprehension" to 1),
            "class C:\n    [z := 1 for x in range(1)]\n" to ("assignment expression within a comprehension cannot be used in a class body" to 2),
            "[x := 1 for x in range(1)]\n" to ("assignment expression cannot rebind comprehension iteration variable 'x'" to 1),
            "match 1:\n    case x:\n        pass\n    case 2:\n        pass\n" to ("name capture 'x' makes remaining patterns unreachable" to 2),
            "match 1:\n    case _:\n        pass\n    case 2:\n        pass\n" to ("wildcard makes remaining patterns unreachable" to 2),
            "match 1:\n    case [x] | [y]:\n        pass\n" to ("alternative patterns bind different names" to 2),
            "match 1:\n    case x | 2:\n        pass\n" to ("name capture 'x' makes remaining patterns unreachable" to 2),
            "match 1:\n    case [*a, *b]:\n        pass\n" to ("multiple starred names in sequence pattern" to 2),
            "match {}:\n    case {'a': 1, 'a': 2}:\n        pass\n" to ("mapping pattern checks duplicate key ('a')" to 2),
            "match 1:\n    case [x, x]:\n        pass\n" to ("multiple assignments to name 'x' in pattern" to 2),
            "def f():\n    from plain import *\n" to ("import * only allowed at module level" to 2),
            "x = 1\nfrom __future__ import annotations\n" to ("from __future__ imports must occur at the beginning of the file" to 2),
            "from __future__ import nope\n" to ("future feature nope is not defined" to 1),
            "from __future__ import braces\n" to ("not a chance" to 1),
            "await 1\n" to ("'await' outside function" to 1),
            "def f():\n    await 1\n" to ("'await' outside async function" to 2),
            "class C:\n    await 1\n" to ("'await' outside function" to 2),
            "f = lambda: await 1\n" to ("'await' outside async function" to 1),
            "async def g():\n    f = lambda: await 1\n" to ("'await' outside async function" to 2),
            "async def f(a=await x):\n    pass\n" to ("'await' outside function" to 1),
            "async for x in y:\n    pass\n" to ("'async for' outside async function" to 1),
            "def f():\n    async with y:\n        pass\n" to ("'async with' outside async function" to 2),
            "class C:\n    async for x in y:\n        pass\n" to ("'async for' outside async function" to 2),
            "def f():\n    return [x async for x in y]\n" to ("asynchronous comprehension outside of an asynchronous function" to 2),
            "[x async for x in y]\n" to ("asynchronous comprehension outside of an asynchronous function" to 1),
            "def f():\n    return [await x for x in y]\n" to ("asynchronous comprehension outside of an asynchronous function" to 2),
            "def f():\n    return [[x async for x in y] for z in w]\n" to ("asynchronous comprehension outside of an asynchronous function" to 2),
            "async def f():\n    yield from []\n" to ("'yield from' inside async function" to 2),
            "async def f():\n    yield 1\n    return 2\n" to ("'return' with value in async generator" to 3),
            "async def f():\n    return 2\n    yield 1\n" to ("'return' with value in async generator" to 2),
            "async def f():\n    return [(yield) for x in y]\n" to ("'yield' inside list comprehension" to 2),
            "g = ((yield) for x in [])\n" to ("'yield' inside generator expression" to 1),
            "def f():\n    return ((yield x) for x in [])\n" to ("'yield' inside generator expression" to 2),
            "def f(x: (yield)): pass\n" to ("yield expression cannot be used within an annotation" to 1),
            "async def f(x: await y): pass\n" to ("await expression cannot be used within an annotation" to 1),
            "def f(x: (y := 1)): pass\n" to ("named expression cannot be used within an annotation" to 1),
            "class C:\n    x: (y := 1)\n" to ("named expression cannot be used within an annotation" to 2),
            "def f():\n    x: (yield) = 1\n" to ("yield expression cannot be used within an annotation" to 2),
            "x: (yield)\n" to ("yield expression cannot be used within an annotation" to 1),
            "x: (yield from y)\n" to ("yield expression cannot be used within an annotation" to 1),
            "def f() -> (yield): pass\n" to ("yield expression cannot be used within an annotation" to 1),
            "def f():\n    global g\n    g: int\n" to ("annotated name 'g' can't be global" to 3),
            "def f():\n    x = 1\n    def g():\n        nonlocal x\n        x: int\n" to ("annotated name 'x' can't be nonlocal" to 5),
            "def f[T, T](): pass\n" to ("duplicate type parameter 'T'" to 1),
            "def f[T, *T](): pass\n" to ("duplicate type parameter 'T'" to 1),
            "def f[T]():\n    nonlocal T\n" to ("nonlocal binding not allowed for type parameter 'T'" to 2),
            "def f[T: (yield)](): pass\n" to ("yield expression cannot be used within a TypeVar bound" to 1),
            "async def f[T: await x](): pass\n" to ("await expression cannot be used within a TypeVar bound" to 1),
            "def f[T: (y := 1)](): pass\n" to ("named expression cannot be used within a TypeVar bound" to 1),
            "def f[T: ((yield), int)](): pass\n" to ("yield expression cannot be used within a TypeVar constraint" to 1),
            "def f[T = (yield)](): pass\n" to ("yield expression cannot be used within a TypeVar default" to 1),
            "def f[*Ts = (yield)](): pass\n" to ("yield expression cannot be used within a TypeVarTuple default" to 1),
            "def f[**P = (yield)](): pass\n" to ("yield expression cannot be used within a ParamSpec default" to 1),
            "type A = (yield)\n" to ("yield expression cannot be used within a type alias" to 1),
            "type A = (x := 1)\n" to ("named expression cannot be used within a type alias" to 1),
            "async def f():\n    type A = await x\n" to ("await expression cannot be used within a type alias" to 2),
            "def f[T = int, U](): pass\n" to ("non-default type parameter 'U' follows default type parameter" to 1),
            "def f[__classdict__](): pass\n" to ("reserved name '__classdict__' cannot be used for type parameter" to 1),
            "type A[T, T] = int\n" to ("duplicate type parameter 'T'" to 1),
            "class C[T, T]: pass\n" to ("duplicate type parameter 'T'" to 1),
            "class C[T]((yield)): pass\n" to ("yield expression cannot be used within the definition of a generic" to 1),
            "class C[T: (yield)]: pass\n" to ("yield expression cannot be used within a TypeVar bound" to 1),
            "class C[T]:\n    nonlocal T\n" to ("nonlocal binding not allowed for type parameter 'T'" to 2),
            "__debug__ = 1\n" to ("cannot assign to __debug__" to 1),
            "del __debug__\n" to ("cannot delete __debug__" to 1),
            "def f(__debug__): pass\n" to ("cannot assign to __debug__" to 1),
            "f(__debug__=1)\n" to ("cannot assign to __debug__" to 1),
            "x.__debug__ = 1\n" to ("cannot assign to __debug__" to 1),
            "import __debug__\n" to ("cannot assign to __debug__" to 1),
            "for __debug__ in x: pass\n" to ("cannot assign to __debug__" to 1),
            "class C:\n    def __debug__(self): pass\n" to ("cannot assign to __debug__" to 2),
            "(__debug__ := 1)\n" to ("cannot assign to __debug__" to 1),
            "lambda __debug__: 1\n" to ("cannot assign to __debug__" to 1),
            "with x as __debug__: pass\n" to ("cannot assign to __debug__" to 1),
            "__debug__ += 1\n" to ("cannot assign to __debug__" to 1),
            "match x:\n    case __debug__: pass\n" to ("cannot assign to __debug__" to 2),
            "from m import x as __debug__\n" to ("cannot assign to __debug__" to 1),
            "del x.__debug__\n" to ("cannot delete __debug__" to 1),
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
