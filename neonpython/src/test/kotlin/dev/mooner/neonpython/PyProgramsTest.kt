package dev.mooner.neonpython

import dev.mooner.neonjs.compiler.CodeUnit
import dev.mooner.neonjs.jit.Jit
import dev.mooner.neonjs.jit.opt.T2
import dev.mooner.neonjs.jit.opt.T2Code
import dev.mooner.neonjs.runtime.VmConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Python programs (src/test/resources/programs/name.py) and what CPython 3.14 printed for each (name.out): the same
 * output interpreted, compiled (every function's code the optimizing tier's baseline before its first call),
 * adaptive (compiled after a few calls, loops continuing in compiled code) and adaptive with the speculating tier
 * (ints and floats unboxed where the interpreter saw only those, exits where that fails).
 */
@Timeout(120)
class PyProgramsTest {
    enum class Mode { INTERPRETED, COMPILED, ADAPTIVE, SPECULATING }

    private fun config(mode: Mode) = VmConfig().apply {
        executionMode = when (mode) {
            Mode.INTERPRETED -> Jit.MODE_INTERPRETER
            Mode.COMPILED -> Jit.MODE_COMPILED
            Mode.ADAPTIVE, Mode.SPECULATING -> Jit.MODE_ADAPTIVE
        }
        tier2 = mode == Mode.SPECULATING
        jitThreshold = 2
        // compiled on the calling thread: each run the same
        backgroundJit = false
    }

    // (line ends as LF: a Windows checkout with core.autocrlf has CRLF, and so has CPython's output recorded there)
    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/programs/$name")?.readBytes()?.toString(Charsets.UTF_8)?.replace("\r\n", "\n")
            ?: fail("no resource $name")

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
            PyContext(StringBuilder(), config(Mode.COMPILED)).exec(cb)
            for (b in blocks(cb)) {
                assertTrue(!b.jitFailed, "$name: ${b.name} not compiled (${T2.baselineReasons.keys})")
                if (b.compiled != null) assertTrue(b.compiled is T2Code, "$name: ${b.name}: ${b.compiled}")
            }
            assertTrue(cb.compiled is T2Code, "$name: the module's code")
        }
        assertEquals(emptySet<String>(), T2.baselineReasons.keys - reasonsBefore)
    }

    @Test
    fun speculatingCodeExitsWhereAnIntLeaves64Bits() {
        // grow's product passes 2**63 midway: its code, compiled speculating on ints, exits there, the interpreter
        // goes on with a BigInteger, and later calls get code without that speculation (or keep exiting) and print the
        // same
        val exits = PyDialect.exits.get()
        val (out, globals) = run(resource("speculation.py"), Mode.SPECULATING)
        assertEquals(resource("speculation.out"), out)
        assertTrue(PyDialect.exits.get() > exits, "no exit taken")
        val grow = globals[PyStr("grow")] as PyFunction
        val mix = globals[PyStr("mix")] as PyFunction
        assertTrue(mix.code.compiled is T2Code && mix.code.compiled !== mix.code.baseline,
            "mix: not the speculating code (${T2.failureReasons.keys})")
        assertTrue(grow.code.t2Failed != null, "grow: its overflow not noted")
    }

    @Test
    fun loopsGoOnInCompiledCode() {
        // (speculating too: an entry there passes the check of its registers' kinds, ints into `long`s)
        for (mode in listOf(Mode.ADAPTIVE, Mode.SPECULATING)) {
            val before = Jit.osrEntered.get()
            val (out, _) = run(resource("loops.py"), mode)
            assertEquals(resource("loops.out"), out, "$mode")
            assertTrue(Jit.osrEntered.get() > before, "$mode: no loop entered compiled code (${T2.baselineReasons.keys})")
        }
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
            // calls and their arguments (M1)
            "def f(a, b):\n    pass\nf(1)\n" to Triple("TypeError", "f() missing 1 required positional argument: 'b'", 3),
            "def f(a, *, k):\n    pass\nf(1)\n" to Triple("TypeError", "f() missing 1 required keyword-only argument: 'k'", 3),
            "def f(a):\n    pass\nf(1, z=2)\n" to Triple("TypeError", "f() got an unexpected keyword argument 'z'", 3),
            "def f(a):\n    pass\nf(1, a=2)\n" to Triple("TypeError", "f() got multiple values for argument 'a'", 3),
            "def f(a, /):\n    pass\nf(a=1)\n" to Triple("TypeError", "f() got some positional-only arguments passed as keyword arguments: 'a'", 3),
            "def f(a=1, b=2):\n    pass\nf(1, 2, 3)\n" to Triple("TypeError", "f() takes from 0 to 2 positional arguments but 3 were given", 3),
            "def f():\n    pass\nf(*1)\n" to Triple("TypeError", "__main__.f() argument after * must be an iterable, not int", 3),
            "def f(**k):\n    pass\nf(**{'a': 1}, **{'a': 2})\n" to Triple("TypeError", "__main__.f() got multiple values for keyword argument 'a'", 3),
            "len(x=1)\n" to Triple("TypeError", "len() takes no keyword arguments", 1),
            // closures, del, assert, unpacking
            "def outer():\n    def inner():\n        return x\n    r = inner()\n    x = 1\n    return r\nouter()\n" to
                Triple("NameError", "cannot access free variable 'x' where it is not associated with a value in enclosing scope", 3),
            "def f():\n    del y\n    y = 1\nf()\n" to Triple("UnboundLocalError", "cannot access local variable 'y' where it is not associated with a value", 2),
            "del undefined_name\n" to Triple("NameError", "name 'undefined_name' is not defined", 1),
            "assert 1 == 2, 'boom'\n" to Triple("AssertionError", "boom", 1),
            "x = 0\nassert x\n" to Triple("AssertionError", "", 2),
            "a, *b, c = [1]\n" to Triple("ValueError", "not enough values to unpack (expected at least 2, got 1)", 1),
            "[*1]\n" to Triple("TypeError", "Value after * must be an iterable, not int", 1),
            "{**1}\n" to Triple("TypeError", "'int' object is not a mapping", 1),
            // classes and their instances (M2)
            "class C: pass\nC(1)\n" to Triple("TypeError", "C() takes no arguments", 2),
            "class C:\n    def __init__(self): pass\nC(1)\n" to Triple("TypeError", "C.__init__() takes 1 positional argument but 2 were given", 3),
            "class C:\n    def __init__(self, x):\n        super().__init__(x)\nC(1)\n" to
                Triple("TypeError", "object.__init__() takes exactly one argument (the instance to initialize)", 3),
            "class C:\n    def __new__(cls, x):\n        return object.__new__(cls, x)\nC(1)\n" to
                Triple("TypeError", "object.__new__() takes exactly one argument (the type to instantiate)", 3),
            "class A: pass\nclass B(A): pass\nclass C(A, B): pass\n" to
                Triple("TypeError", "Cannot create a consistent method resolution order (MRO) for bases A, B", 3),
            "class M1(type): pass\nclass M2(type): pass\nclass A(metaclass=M1): pass\nclass B(metaclass=M2): pass\nclass C(A, B): pass\n" to
                Triple("TypeError", "metaclass conflict: the metaclass of a derived class must be a (non-strict) subclass of the metaclasses of all its bases", 5),
            "class A: pass\nclass B(A, x=1): pass\n" to Triple("TypeError", "B.__init_subclass__() takes no keyword arguments", 2),
            "class C:\n    def m():\n        return super()\nC.m()\n" to Triple("RuntimeError", "super(): no arguments", 3),
            "class C: pass\nC().x\n" to Triple("AttributeError", "'C' object has no attribute 'x'", 2),
            "class C: pass\nC.x\n" to Triple("AttributeError", "type object 'C' has no attribute 'x'", 2),
            "x = 1\nx.y = 2\n" to Triple("AttributeError", "'int' object has no attribute 'y' and no __dict__ for setting new attributes", 2),
            "class B(bool): pass\n" to Triple("TypeError", "type 'bool' is not an acceptable base type", 1),
            "class C:\n    def __init__(self): return 1\nC()\n" to Triple("TypeError", "__init__() should return None, not 'int'", 3),
            "class C: pass\nC()()\n" to Triple("TypeError", "'C' object is not callable", 2),
            "int.x = 1\n" to Triple("TypeError", "cannot set 'x' attribute of immutable type 'int'", 1),
            "class C:\n    @property\n    def p(self): return 1\nC().p = 2\n" to Triple("AttributeError", "property 'p' of 'C' object has no setter", 4),
            "class C:\n    p = property()\nC().p\n" to Triple("AttributeError", "property 'p' of 'C' object has no getter", 3),
            "class C: pass\ndel C()[0]\n" to Triple("TypeError", "'C' object doesn't support item deletion", 2),
            "class C: pass\nC()[0] = 1\n" to Triple("TypeError", "'C' object does not support item assignment", 2),
            "class C: pass\n1 in C()\n" to Triple("TypeError", "argument of type 'C' is not a container or iterable", 2),
            "class C: pass\nc = C()\nc += 1\n" to Triple("TypeError", "unsupported operand type(s) for +=: 'C' and 'int'", 3),
            "x = 1\nx += 'a'\n" to Triple("TypeError", "unsupported operand type(s) for +=: 'int' and 'str'", 2),
            "x = 'a'\nx **= 2\n" to Triple("TypeError", "unsupported operand type(s) for **=: 'str' and 'int'", 2),
            "class C: pass\nx = C()\nx -= C()\n" to Triple("TypeError", "unsupported operand type(s) for -=: 'C' and 'C'", 3),
            "x = 1\nx @= 2\n" to Triple("TypeError", "unsupported operand type(s) for @=: 'int' and 'int'", 2),
            "1 @ 2\n" to Triple("TypeError", "unsupported operand type(s) for @: 'int' and 'int'", 1),
            "class C: pass\nC() @ C()\n" to Triple("TypeError", "unsupported operand type(s) for @: 'C' and 'C'", 2),
            "class C: pass\nC() + 1\n" to Triple("TypeError", "unsupported operand type(s) for +: 'C' and 'int'", 2),
            "class C: pass\n2 ** C()\n" to Triple("TypeError", "unsupported operand type(s) for ** or pow(): 'int' and 'C'", 2),
            "l = []\nl += 1\n" to Triple("TypeError", "'int' object is not iterable", 2),
            "class C:\n    def __str__(self): return 1\nstr(C())\n" to Triple("TypeError", "__str__ returned non-string (type int)", 3),
            "class C:\n    def __repr__(self): return 1\nrepr(C())\n" to Triple("TypeError", "__repr__ returned non-string (type int)", 3),
            "class C:\n    def __bool__(self): return 1\nif C(): pass\n" to Triple("TypeError", "__bool__ should return bool, returned int", 3),
            "class C:\n    def __len__(self): return 'x'\nlen(C())\n" to Triple("TypeError", "'str' object cannot be interpreted as an integer", 3),
            "class C:\n    def __len__(self): return -1\nlen(C())\n" to Triple("ValueError", "__len__() should return >= 0", 3),
            "class C:\n    def __iter__(self): return 1\niter(C())\n" to Triple("TypeError", "iter() returned non-iterator of type 'int'", 3),
            "class C:\n    __iter__ = None\niter(C())\n" to Triple("TypeError", "'C' object is not iterable", 3),
            "class C:\n    def __hash__(self): return 'x'\nhash(C())\n" to Triple("TypeError", "__hash__ method should return an integer", 3),
            "class C:\n    def __eq__(self, o): return True\n{C(): 1}\n" to Triple("TypeError", "cannot use 'C' as a dict key (unhashable type: 'C')", 3),
            "d = {}\nd[[]] = 1\n" to Triple("TypeError", "cannot use 'list' as a dict key (unhashable type: 'list')", 2),
            "hash([])\n" to Triple("TypeError", "unhashable type: 'list'", 1),
            "class C: pass\nabs(C())\n" to Triple("TypeError", "bad operand type for abs(): 'C'", 2),
            "class C: pass\n~C()\n" to Triple("TypeError", "bad operand type for unary ~: 'C'", 2),
            "class C: pass\n-C()\n" to Triple("TypeError", "bad operand type for unary -: 'C'", 2),
            "class C: pass\nC() <= 1\n" to Triple("TypeError", "'<=' not supported between instances of 'C' and 'int'", 2),
            "class C: pass\nC() < C()\n" to Triple("TypeError", "'<' not supported between instances of 'C' and 'C'", 2),
            "class C: pass\nfor x in C(): pass\n" to Triple("TypeError", "'C' object is not iterable", 2),
            "class C: pass\nC()[0]\n" to Triple("TypeError", "'C' object is not subscriptable", 2),
            "class C: pass\nlen(C())\n" to Triple("TypeError", "object of type 'C' has no len()", 2),
            "next(1)\n" to Triple("TypeError", "'int' object is not an iterator", 1),
            "next(iter([]))\n" to Triple("StopIteration", "", 1),
            "next()\n" to Triple("TypeError", "next expected at least 1 argument, got 0", 1),
            "iter(1, 2)\n" to Triple("TypeError", "iter(v, w): v must be callable", 1),
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
            "speculation", "functions", "closures", "unpacking", "types", "classes", "descriptors",
            "dunders", "protocols",
        )

        /** The code blocks of [cb] and every function it defines. */
        fun blocks(cb: CodeUnit): List<CodeUnit> = listOf(cb) + cb.constants.filterIsInstance<CodeUnit>().flatMap { blocks(it) }
    }
}
