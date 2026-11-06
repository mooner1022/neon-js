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
            "def f():\nreturn 1\n" to ("expected an indented block after function definition on line 1" to 2),
            "class C:\nx = 1\n" to ("expected an indented block after class definition on line 1" to 2),
            "if x:\npass\n" to ("expected an indented block after 'if' statement on line 1" to 2),
            "if x:\n    pass\nelif y:\npass\n" to ("expected an indented block after 'elif' statement on line 3" to 4),
            "for x in y:\n    pass\nelse:\npass\n" to ("expected an indented block after 'else' statement on line 3" to 4),
            "while x:\npass\n" to ("expected an indented block after 'while' statement on line 1" to 2),
            "with x:\npass\n" to ("expected an indented block after 'with' statement on line 1" to 2),
            "try:\npass\n" to ("expected an indented block after 'try' statement on line 1" to 2),
            "try:\n    pass\nexcept E:\npass\n" to ("expected an indented block after 'except' statement on line 3" to 4),
            "try:\n    pass\nexcept* E:\npass\n" to ("expected an indented block after 'except*' statement on line 3" to 4),
            "try:\n    pass\nfinally:\npass\n" to ("expected an indented block after 'finally' statement on line 3" to 4),
            "match x:\ncase 1: pass\n" to ("expected an indented block after 'match' statement on line 1" to 2),
            "match x:\n    case 1:\n    pass\n" to ("expected an indented block after 'case' statement on line 2" to 3),
            "async def f():\n    async for x in y:\n    pass\n" to ("expected an indented block after 'for' statement on line 2" to 3),
        )
        for ((src, want) in cases) {
            val e = assertThrows<PySyntaxError>(src) { PyCompiler.compileModule(src) }
            assertEquals(want, e.msg to e.line, src)
        }
    }

    @Test
    fun tokenizerErrorsAreCPythons() {
        // (message and line as CPython 3.14's compile gives them)
        val cases = listOf(
            "f(\n" to ("'(' was never closed" to 1),
            "x = (1,\ny = 2\n" to ("'(' was never closed" to 1),
            "foo(\n1 2\n" to ("'(' was never closed" to 1),
            "def f(:\n  pass\n" to ("invalid syntax" to 1),
            "x = 1 2\ny = (\n" to ("invalid syntax" to 1),
            "print 1, (2\n" to ("'(' was never closed" to 1),
            "with (a,\n" to ("'(' was never closed" to 1),
            "x = )\n" to ("unmatched ')'" to 1),
            "(\n]\n" to ("closing parenthesis ']' does not match opening parenthesis '(' on line 1" to 2),
            "[)\n" to ("closing parenthesis ')' does not match opening parenthesis '['" to 1),
            "f'{)}'\n" to ("f-string: unmatched ')'" to 1),
            "t'{]}'\n" to ("t-string: unmatched ']'" to 1),
            "f'{(]}'\n" to ("closing parenthesis ']' does not match opening parenthesis '('" to 1),
            "a \$ b\n" to ("invalid syntax" to 1),
            "a!b\n" to ("invalid syntax" to 1),
            "a € b\n" to ("invalid character '€' (U+20AC)" to 1),
            "ub''\n" to ("'u' and 'b' prefixes are incompatible" to 1),
            "ru''\n" to ("'u' and 'r' prefixes are incompatible" to 1),
            "tb''\n" to ("'b' and 't' prefixes are incompatible" to 1),
            "frt''\n" to ("'f' and 't' prefixes are incompatible" to 1),
            "bub''\n" to ("invalid syntax" to 1),
            "x = ub'x'\nfoo(\n" to ("'u' and 'b' prefixes are incompatible" to 1),
            "(".repeat(201) + "\n" to ("too many nested parentheses" to 1),
            "0b12\n" to ("invalid digit '2' in binary literal" to 1),
            "0o8\n" to ("invalid digit '8' in octal literal" to 1),
            "09\n" to ("leading zeros in decimal integer literals are not permitted; use an 0o prefix for octal integers" to 1),
            "1__0\n" to ("invalid decimal literal" to 1),
            "1_\n" to ("invalid decimal literal" to 1),
            "0x_\n" to ("invalid hexadecimal literal" to 1),
            "1e+\n" to ("invalid decimal literal" to 1),
            "0xg\n" to ("invalid hexadecimal literal" to 1),
            "1j2\n" to ("invalid imaginary literal" to 1),
            "print 'a'\n" to ("Missing parentheses in call to 'print'. Did you mean print(...)?" to 1),
            "exec code\n" to ("Missing parentheses in call to 'exec'. Did you mean exec(...)?" to 1),
            "'\\N{nope}'\n" to ("(unicode error) 'unicodeescape' codec can't decode bytes in position 0-7: unknown Unicode character name" to 1),
            "'é\\N{nope}'\n" to ("(unicode error) 'unicodeescape' codec can't decode bytes in position 10-17: unknown Unicode character name" to 1),
            "'ab\\x1z'\n" to ("(unicode error) 'unicodeescape' codec can't decode bytes in position 2-4: truncated \\xXX escape" to 1),
            "'\\U00110000'\n" to ("(unicode error) 'unicodeescape' codec can't decode bytes in position 0-9: illegal Unicode character" to 1),
            "'\\N'\n" to ("(unicode error) 'unicodeescape' codec can't decode bytes in position 0-1: malformed \\N character escape" to 1),
            "'\\N{'\n" to ("(unicode error) 'unicodeescape' codec can't decode bytes in position 0-2: malformed \\N character escape" to 1),
            "'\\u12'\n" to ("(unicode error) 'unicodeescape' codec can't decode bytes in position 0-3: truncated \\uXXXX escape" to 1),
            "b'\\x1'\n" to ("(value error) invalid \\x escape at position 0" to 1),
            "f'{x}a\\N{nope}'\n" to ("(unicode error) 'unicodeescape' codec can't decode bytes in position 1-8: unknown Unicode character name" to 1),
            "'''a\nb\\N{nope}'''\n" to ("(unicode error) 'unicodeescape' codec can't decode bytes in position 3-10: unknown Unicode character name" to 1),
            "f'{'\n" to ("f-string: expecting '}'" to 1),
            "f'{x!r'\n" to ("f-string: expecting '}'" to 1),
            "t'{'\n" to ("t-string: expecting '}'" to 1),
            "f'''{'''\n" to ("f-string: expecting '}'" to 1),
            "f'{\"a\"'\n" to ("f-string: expecting '}'" to 1),
            "f\"{'\"\n" to ("unterminated string literal (detected at line 1)" to 1),
            "f'{x=!}'\n" to ("f-string: missing conversion character" to 1),
            "f'{x!:}'\n" to ("f-string: missing conversion character" to 1),
            "f'{x!1}'\n" to ("f-string: invalid conversion character" to 1),
            "f'{x!z}'\n" to ("f-string: invalid conversion character 'z': expected 's', 'r', or 'a'" to 1),
            "f'{x:'\n" to ("f-string: expecting '}', or format specs" to 1),
            "f'{x!r:'\n" to ("f-string: expecting '}', or format specs" to 1),
            "if True:\n\tx = 1\n        y = 2\n" to ("inconsistent use of tabs and spaces in indentation" to 3),  // TabError
            "if True:\n        x = 1\n\ty = 2\n" to ("inconsistent use of tabs and spaces in indentation" to 3),  // TabError
            "if x:\n\tif y:\n\t pass\n\t\tpass\n" to ("inconsistent use of tabs and spaces in indentation" to 4),  // TabError
            "if x:\n  \tpass\n\t pass\n" to ("inconsistent use of tabs and spaces in indentation" to 3),  // TabError
            "if 1:\n" + (1..100).joinToString("") { " ".repeat(it) + "if 1:\n" } + " ".repeat(101) + "pass\n" to ("too many levels of indentation" to 101),  // IndentationError
        )
        for ((src, want) in cases) {
            val e = assertThrows<PySyntaxError>(src) { PyCompiler.compileModule(src) }
            assertEquals(want, e.msg to e.line, src)
        }
        // (accepted: prefixes in any case and order)
        PyCompiler.compileModule("Rb'' bR'' rB'' Br''\n")
    }

    @Test
    fun parserErrorsAreCPythons() {
        // (message and line as CPython 3.14's compile gives them: its generic error, its forced tokens, its invalid_ rules)
        val cases = listOf(
            "class R&D:\n    pass\n" to ("invalid syntax" to 1),
            "for x in range 10:\n  pass\n" to ("invalid syntax" to 1),
            "for x y: pass\n" to ("invalid syntax" to 1),
            "lambda x\n" to ("invalid syntax" to 1),
            "def f(x := 1): pass\n" to ("invalid syntax" to 1),
            "with a as b\n" to ("expected ':'" to 1),
            "with (a as b, c as d)\n" to ("expected ':'" to 1),
            "with (a as b):\n    1 +\n" to ("invalid syntax" to 2),
            "match x\n" to ("expected ':'" to 1),
            "match x, y\n" to ("expected ':'" to 1),
            "match x y\n" to ("invalid syntax" to 1),
            "match x:\n    case 1\n" to ("expected ':'" to 2),
            "try:\n    pass\nexcept\n" to ("expected ':'" to 3),
            "try:\n    pass\nexcept*\n" to ("expected one or more exception types" to 3),
            "try:\n    pass\nexcept E as e\n" to ("expected ':'" to 3),
            "def f() -> : pass\n" to ("expected ':'" to 1),
            "def f(x) pass\n" to ("expected ':'" to 1),
            "def f x: pass\n" to ("expected '('" to 1),
            "def f[T b](): pass\n" to ("expected '('" to 1),
            "if x:\n    pass\nelse pass\n" to ("expected ':'" to 3),
            "try pass\n" to ("expected ':'" to 1),
            "if x pass\n" to ("invalid syntax" to 1),
            "class C\n" to ("expected ':'" to 1),
            "from x y\n" to ("invalid syntax" to 1),
            "\\" to ("unexpected EOF while parsing" to 1),
            "x = \\\n" to ("unexpected EOF while parsing" to 1),
            "if x:\n    y \\" to ("unexpected EOF while parsing" to 2),
            "f(a, b c)\n" to ("invalid syntax. Perhaps you forgot a comma?" to 1),
            "{1:2, 2:5 3:12}\n" to ("invalid syntax. Perhaps you forgot a comma?" to 1),
            "x = (1\ny = 2)\n" to ("invalid syntax. Perhaps you forgot a comma?" to 1),
            "f(1 2\n" to ("'(' was never closed" to 1),
            "1 2 (\n" to ("'(' was never closed" to 1),
            "x[1 2]\n" to ("invalid syntax. Perhaps you forgot a comma?" to 1),
            "f(a=1 b=2)\n" to ("invalid syntax. Perhaps you forgot a comma?" to 1),
            "(a if b else c d)\n" to ("invalid syntax. Perhaps you forgot a comma?" to 1),
            "(lambda: 1 a)\n" to ("invalid syntax. Perhaps you forgot a comma?" to 1),
            "(*a b)\n" to ("invalid syntax. Perhaps you forgot a comma?" to 1),
            "f(*a < b c)\n" to ("invalid syntax. Perhaps you forgot a comma?" to 1),
            "f'{a b}'\n" to ("invalid syntax. Perhaps you forgot a comma?" to 1),
            "(a 'b')\n" to ("invalid syntax" to 1),
            "(_ b)\n" to ("invalid syntax" to 1),
            "(a, *b c)\n" to ("invalid syntax" to 1),
            "[x for x in y z]\n" to ("invalid syntax" to 1),
            "1 2\n" to ("invalid syntax" to 1),
            "(print 1)\n" to ("Missing parentheses in call to 'print'. Did you mean print(...)?" to 1),
            "'a' x 'b'\n" to ("invalid syntax. Is this intended to be part of the string?" to 1),
            "x if y\n" to ("expected 'else' after 'if' expression" to 1),
            "{a if b: c}\n" to ("invalid syntax" to 1),
            "x = 1 if 2 else\n" to ("expected expression after 'else', but statement is given" to 1),
            "x = a if b else *c\n" to ("expected expression after 'else', but statement is given" to 1),
            "x = pass if 1 else 1\n" to ("expected expression before 'if', but statement is given" to 1),
            "[x for a, b, (c + 1, d()) if y]\n" to ("'in' expected after for-loop variables" to 1),
            "[x for *a if y]\n" to ("invalid syntax" to 1),
            "{**a, b}\n" to ("':' expected after dictionary key" to 1),
            "{**a for a in b}\n" to ("dict unpacking cannot be used in dict comprehension" to 1),
            "{1: *a}\n" to ("cannot use a starred expression in a dictionary value" to 1),
            "{1:}\n" to ("expression expected after dictionary key and ':'" to 1),
            "{1:2, 5:}\n" to ("expression expected after dictionary key and ':'" to 1),
            "{a: b, c for a in x}\n" to ("':' expected after dictionary key" to 1),
            "f() = 1\n" to ("cannot assign to function call here. Maybe you meant '==' instead of '='?" to 1),
            "f() = g() = 1\n" to ("cannot assign to function call" to 1),
            "a < b = 1\n" to ("cannot assign to comparison" to 1),
            "-a = 1\n" to ("cannot assign to expression here. Maybe you meant '==' instead of '='?" to 1),
            "None = 1\n" to ("cannot assign to None" to 1),
            "... = 1\n" to ("cannot assign to ellipsis here. Maybe you meant '==' instead of '='?" to 1),
            "[x for x in y] = 1\n" to ("cannot assign to list comprehension here. Maybe you meant '==' instead of '='?" to 1),
            "(x for x in y) = 1\n" to ("cannot assign to generator expression" to 1),
            "a if b else c = 1\n" to ("cannot assign to conditional expression" to 1),
            "yield = 1\n" to ("assignment to yield expression not possible" to 1),
            "x = yield = 1\n" to ("assignment to yield expression not possible" to 1),
            "(yield) = 1\n" to ("cannot assign to yield expression here. Maybe you meant '==' instead of '='?" to 1),
            "[a, b, c + 1] = 1\n" to ("cannot assign to expression" to 1),
            "a, f() = 1\n" to ("cannot assign to function call here. Maybe you meant '==' instead of '='?" to 1),
            "(f()) = 1\n" to ("cannot assign to function call here. Maybe you meant '==' instead of '='?" to 1),
            "[f()] = 1\n" to ("cannot assign to function call" to 1),
            "f() = 1 == 2\n" to ("cannot assign to function call here. Maybe you meant '==' instead of '='?" to 1),
            "f() = x := 1\n" to ("cannot assign to function call" to 1),
            "f() = not x\n" to ("cannot assign to function call" to 1),
            "f() = 1 2\n" to ("cannot assign to function call here. Maybe you meant '==' instead of '='?" to 1),
            "x.y = 1, f() = 2\n" to ("cannot assign to attribute here. Maybe you meant '==' instead of '='?" to 1),
            "a, b = 1, f() = 2\n" to ("invalid syntax. Maybe you meant '==' or ':=' instead of '='?" to 1),
            "(a, b) = 1, f() = 2\n" to ("cannot assign to literal" to 1),
            "x = *a, f() = 1\n" to ("cannot assign to function call" to 1),
            "*a = 1\n" to ("starred assignment target must be in a list or tuple" to 1),
            "a = *b = 1\n" to ("starred assignment target must be in a list or tuple" to 1),
            "__debug__ = 1\n" to ("cannot assign to __debug__" to 1),
            "*a, *b, c = 1\n" to ("multiple starred expressions in assignment" to 1),
            "f() := 1\n" to ("cannot use assignment expressions with function call" to 1),
            "x[1:2] := 3\n" to ("cannot use assignment expressions with subscript" to 1),
            "(a.b := 1)\n" to ("cannot use assignment expressions with attribute" to 1),
            "(x := 1) := 2\n" to ("cannot use assignment expressions with named expression" to 1),
            "f(a.b := 1)\n" to ("invalid syntax" to 1),
            "del *a\n" to ("cannot delete starred" to 1),
            "del a if b else c\n" to ("cannot delete conditional expression" to 1),
            "del (a for a in b)\n" to ("cannot delete generator expression" to 1),
            "del x = 1\n" to ("invalid syntax" to 1),
            "for *a in b: pass\n" to ("starred assignment target must be in a list or tuple" to 1),
            "x.__debug__: int\n" to ("cannot assign to __debug__" to 1),
            "match x:\n  case Foo(a, __debug__=1, b=2):\n      pass\n" to ("cannot assign to __debug__" to 2),
            "if x = 3:\n   pass\n" to ("invalid syntax. Maybe you meant '==' or ':=' instead of '='?" to 1),
            "if x.a = 3:\n   pass\n" to ("cannot assign to attribute here. Maybe you meant '==' instead of '='?" to 1),
            "if True = 3: pass\n" to ("invalid syntax" to 1),
            "if x == 1 = 2: pass\n" to ("invalid syntax" to 1),
            "if (x = 1): pass\n" to ("invalid syntax. Maybe you meant '==' or ':=' instead of '='?" to 1),
            "while f() = 1 == 2: pass\n" to ("cannot assign to function call here. Maybe you meant '==' instead of '='?" to 1),
            "(x, y, z=3, d, e)\n" to ("invalid syntax. Maybe you meant '==' or ':=' instead of '='?" to 1),
            "[x, f() = 1]\n" to ("cannot assign to function call here. Maybe you meant '==' instead of '='?" to 1),
            "x[a = 1]\n" to ("invalid syntax. Maybe you meant '==' or ':=' instead of '='?" to 1),
            "{x = 1: y}\n" to ("invalid syntax. Maybe you meant '==' or ':=' instead of '='?" to 1),
            "{x: y = 1}\n" to ("invalid syntax" to 1),
            "match x = 1:\n    case 1: pass\n" to ("invalid syntax. Maybe you meant '==' or ':=' instead of '='?" to 1),
            "@x = 1\ndef f(): pass\n" to ("invalid syntax. Maybe you meant '==' or ':=' instead of '='?" to 1),
            "assert x.y = 1\n" to ("invalid syntax" to 1),
            "f(x, y = 1 = 2)\n" to ("invalid syntax" to 1),
            "f(True=1)\n" to ("cannot assign to True" to 1),
            "f(a, None=1)\n" to ("cannot assign to None" to 1),
            "f(a=)\n" to ("expected argument value expression" to 1),
            "f(a, b, c=, d)\n" to ("expected argument value expression" to 1),
            "f(*args=[0])\n" to ("cannot assign to iterable argument unpacking" to 1),
            "print(*a = 1)\n" to ("cannot assign to iterable argument unpacking" to 1),
            "f(**kwargs={'a': 1})\n" to ("cannot assign to keyword argument unpacking" to 1),
            "f(x for x in L,)\n" to ("Generator expression must be parenthesized" to 1),
            "f(x for x in L, 1)\n" to ("Generator expression must be parenthesized" to 1),
            "class C(x for x in L):\n    pass\n" to ("invalid syntax" to 1),
            "class C(x for x in L,): pass\n" to ("Generator expression must be parenthesized" to 1),
            "f(a=1 for x in L)\n" to ("invalid syntax. Maybe you meant '==' or ':=' instead of '='?" to 1),
            "f(*)\n" to ("Invalid star expression" to 1),
            "f(**x, *)\n" to ("Invalid star expression" to 1),
            "f(x, *:)\n" to ("Invalid star expression" to 1),
            "[*]\n" to ("Invalid star expression" to 1),
            "(*)\n" to ("Invalid star expression" to 1),
            "x = [*a, *]\n" to ("invalid syntax" to 1),
            "x = *\n" to ("invalid syntax" to 1),
            "A[*]\n" to ("Invalid star expression" to 1),
            "A[*(1:2)]\n" to ("Invalid star expression" to 1),
            "A[*b:]\n" to ("invalid syntax" to 1),
            "def f(x, (y, z), w):\n   pass\n" to ("Function parameters cannot be parenthesized" to 1),
            "def f(a=1, (b, c)): pass\n" to ("invalid syntax" to 1),
            "def f((a, b)=1): pass\n" to ("Function parameters cannot be parenthesized" to 1),
            "lambda (x, y): None\n" to ("Lambda expression parameters cannot be parenthesized" to 1),
            "def foo(a,*b=3,c):\n   pass\n" to ("var-positional argument cannot have default value" to 1),
            "lambda a,*b=3,c: None\n" to ("var-positional argument cannot have default value" to 1),
            "def f(*=1): pass\n" to ("invalid syntax" to 1),
            "def foo(a=1,d=,c):\n   pass\n" to ("expected default value expression" to 1),
            "lambda a=, b: 0\n" to ("expected default value expression" to 1),
            "lambda a=: 0\n" to ("invalid syntax" to 1),
            "def foo(a=1,/*,b,c):\n   pass\n" to ("expected comma between / and *" to 1),
            "import a from b\n" to ("Did you mean to use 'from ... import ...' instead?" to 1),
            "import a.y.z from b.y.z\n" to ("Did you mean to use 'from ... import ...' instead?" to 1),
            "import a as b from c\n" to ("cannot use name as import target" to 1),
            "import\n" to ("Expected one or more names after 'import'" to 1),
            "from i import\n" to ("Expected one or more names after 'import'" to 1),
            "from .. import\n" to ("Expected one or more names after 'import'" to 1),
            "import a as b.c\n" to ("cannot use attribute as import target" to 1),
            "import a.b as (a, b)\n" to ("cannot use tuple as import target" to 1),
            "import a as b c\n" to ("cannot use name as import target" to 1),
            "from a import (b as c.d)\n" to ("cannot use attribute as import target" to 1),
            "from a import b, c as d[e]\n" to ("cannot use subscript as import target" to 1),
            "from a import (\n  b as f())\n" to ("cannot use function call as import target" to 2),
            "from a import b as 1\n" to ("cannot use literal as import target" to 1),
            "match ...:\n  case 42 as 1+2+4:\n    ...\n" to ("cannot use expression as pattern target" to 2),
            "match ...:\n  case 42 as a.b:\n    ...\n" to ("cannot use attribute as pattern target" to 2),
            "match x:\n    case 42 as a=1:\n        pass\n" to ("cannot use name as pattern target" to 2),
            "match x:\n    case 42 as a b:\n        pass\n" to ("invalid syntax" to 2),
            "match ...:\n    case {**_}:\n       ...\n" to ("invalid syntax" to 2),
            "match x:\n    y = 3\n" to ("invalid syntax" to 2),
            "try:\n   pass\nexcept TypeError as obj.attr:\n   pass\n" to ("cannot use except statement with attribute" to 3),
            "try:\n    pass\nexcept E as (e):\n    pass\n" to ("cannot use except statement with name" to 3),
            "try:\n   pass\nexcept* TypeError as (obj, name):\n   pass\n" to ("cannot use except* statement with tuple" to 3),
            "try:\n   pass\nexcept* TypeError as 1:\n   pass\n" to ("cannot use except* statement with literal" to 3),
            "if a:\n    pass\nelse:\n    pass\nelif b:\n    pass\n" to ("'elif' block follows an 'else' block" to 5),
            "for x in y:\n    pass\nelse:\n    pass\nelif z:\n    pass\n" to ("'elif' block follows an 'else' block" to 5),
            "3 + not 3\n" to ("'not' after an operator must be parenthesized" to 1),
            "3 * not 3\n" to ("'not' after an operator must be parenthesized" to 1),
            "+ not 3\n" to ("'not' after an operator must be parenthesized" to 1),
            "a + -not b\n" to ("'not' after an operator must be parenthesized" to 1),
            "1 ** not 2\n" to ("invalid syntax" to 1),
            "1 | not 2\n" to ("invalid syntax" to 1),
            "1 + not\n" to ("invalid syntax" to 1),
            "[x,y for x,y in range(100)]\n" to ("did you forget parentheses around the comprehension target?" to 1),
            "{x,y for x,y in range(100)}\n" to ("did you forget parentheses around the comprehension target?" to 1),
            "[i := 0, j := 1 for i, j in [(1, 2), (3, 4)]]\n" to ("did you forget parentheses around the comprehension target?" to 1),
        )
        for ((src, want) in cases) {
            val e = assertThrows<PySyntaxError>(src) { PyCompiler.compileModule(src) }
            assertEquals(want, e.msg to e.line, src)
        }
        // (accepted: the name match before an expression on its line)
        PyCompiler.compileModule("match(x)\nmatch -x\nmatch[1]\nmatch(a=1)\nmatch[x] = 1\n")
        // (and what those checks must let through)
        PyCompiler.compileModule("A[*a if b else c]\nf(*a if b else c)\n*a, = 1\n[*a] = 1\ndel a, b,\nf(x for x in y)\nx = y = 1\nif (x := 1): pass\n")
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
