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

    /** The modules programs import: src/test/resources/modules/, a package's __init__.py under its name. */
    private val loader = PyModuleLoader { name ->
        val base = "/modules/" + name.replace('.', '/')
        fun read(path: String) = javaClass.getResourceAsStream(path)?.readBytes()?.toString(Charsets.UTF_8)?.replace("\r\n", "\n")
        read("$base/__init__.py")?.let { PyModuleSource(it, "$base/__init__.py", true) } ?: read("$base.py")?.let { PyModuleSource(it, "$base.py", false) }
    }

    private fun run(src: String, mode: Mode, fs: Boolean = false): Pair<String, PyDict> {
        val out = StringBuilder()
        // (what sys.stderr writes is not CPython's output recorded: apart)
        val ctx = PyContext(out, config(mode), loader, StringBuilder(), memoryFs(fs))
        if (fs) ctx.exec("import os\nos.chdir('/tmp')\ndel os\n")
        val globals = ctx.exec(src)
        return out.toString() to globals
    }

    /**
     * The programs that use a file system: run with one in memory, in its empty /tmp; their outputs are Linux
     * CPython's (POSIX-shaped, as NeonPy's posix is), run in an empty directory.
     */
    private val fsPrograms = setOf("os_module", "io_module", "io_errors")

    private fun memoryFs(fs: Boolean): PyFileSystem = if (fs) PyFileSystem.memory(mapOf("/tmp/" to ByteArray(0))) else PyFileSystem.NONE

    @ParameterizedTest
    @MethodSource("programs")
    fun programPrintsWhatCPythonPrints(name: String) {
        val src = resource("$name.py")
        val expected = resource("$name.out")
        for (mode in Mode.entries) {
            val (out, _) = try {
                run(src, mode, name in fsPrograms)
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
        // interpreted, its output the same); a generator's and a coroutine's too, which suspends at its yields
        val reasonsBefore = T2.baselineReasons.keys.toSet()
        for (name in programs()) {
            val cb = PyCompiler.compileModule(resource("$name.py"))
            val ctx = PyContext(StringBuilder(), config(Mode.COMPILED), loader, StringBuilder(), memoryFs(name in fsPrograms))
            if (name in fsPrograms) ctx.exec("import os\nos.chdir('/tmp')\ndel os\n")
            ctx.exec(cb)
            // (the modules it imported too)
            for (b in blocks(cb) + ctx.moduleUnits.flatMap { blocks(it) }) {
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
    fun uncaughtExceptionsPrintAsCPythonPrintsThem() {
        // (CPython's traceback.format_exception of the program exec'd from "<string>", recorded with CPython)
        val src = javaClass.getResourceAsStream("/tracebacks/chain.py")!!.readBytes().toString(Charsets.UTF_8).replace("\r\n", "\n")
        val expected = javaClass.getResourceAsStream("/tracebacks/chain.out")!!.readBytes().toString(Charsets.UTF_8).replace("\r\n", "\n")
        for (mode in Mode.entries) {
            val ctx = PyContext(StringBuilder(), config(mode), loader, StringBuilder())
            val e = org.junit.jupiter.api.assertThrows<PyException> { ctx.exec(src) }
            assertEquals(expected, ctx.formatException(e), "$mode")
        }
    }

    @Test
    fun generatorsSuspendInTheTiersCode() {
        val src = resource("suspensions.py")
        val expected = resource("suspensions.out")
        // compiled: a generator's, a coroutine's and an async generator's code is the tier's, suspending at its yields
        val (out, globals) = run(src, Mode.COMPILED)
        assertEquals(expected, out)
        for (n in listOf("counting", "made_before", "growing", "sub", "coro", "agen")) {
            val f = globals[PyStr(n)] as PyFunction
            assertTrue(f.code.compiled is T2Code, "$n: ${f.code.compiled} (${T2.baselineReasons.keys})")
        }
        // adaptive: the long loop of a generator made once goes on in compiled code, which then yields; one made before
        // its code was compiled is resumed by it
        val before = Jit.osrEntered.get()
        assertEquals(expected, run(src, Mode.ADAPTIVE).first, "ADAPTIVE")
        assertTrue(Jit.osrEntered.get() > before, "no generator's loop entered compiled code")
        // speculating: an int leaving 64 bits exits the generator's code, the interpreter going on to its next yield
        val exits = PyDialect.exits.get()
        assertEquals(expected, run(src, Mode.SPECULATING).first, "SPECULATING")
        assertTrue(PyDialect.exits.get() > exits, "no exit taken")
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
            "class C(list, dict): pass\n" to Triple("TypeError", "multiple bases have instance lay-out conflict", 1),
            "class A(list): pass\nclass B(dict): pass\nclass C(A, B): pass\n" to Triple("TypeError", "multiple bases have instance lay-out conflict", 3),
            "class C(bool): pass\n" to Triple("TypeError", "type 'bool' is not an acceptable base type", 1),
            "class L(list): pass\nL(1, 2)\n" to Triple("TypeError", "list expected at most 1 argument, got 2", 2),
            "class L(list): pass\nL(1)\n" to Triple("TypeError", "'int' object is not iterable", 2),
            "class L(list): pass\nL(x=1)\n" to Triple("TypeError", "list() takes no keyword arguments", 2),
            "class D(dict): pass\nD(1, 2)\n" to Triple("TypeError", "dict expected at most 1 argument, got 2", 2),
            "dict(1, 2)\n" to Triple("TypeError", "dict expected at most 1 argument, got 2", 1),
            "class T(tuple): pass\nT(1, 2)\n" to Triple("TypeError", "tuple expected at most 1 argument, got 2", 2),
            "class T(tuple): pass\nT(x=1)\n" to Triple("TypeError", "tuple() takes no keyword arguments", 2),
            "class S(str): pass\nS(1, 2, 3, 4)\n" to Triple("TypeError", "str() takes at most 3 arguments (4 given)", 2),
            "class S(str): pass\nS(x=1)\n" to Triple("TypeError", "str() got an unexpected keyword argument 'x'", 2),
            "[].x = 1\n" to Triple("AttributeError", "'list' object has no attribute 'x' and no __dict__ for setting new attributes", 1),
            "class L(list): pass\nL().nope\n" to Triple("AttributeError", "'L' object has no attribute 'nope'", 2),
            "class L(list): pass\nL()[5]\n" to Triple("IndexError", "list index out of range", 2),
            "class L(list): pass\nhash(L())\n" to Triple("TypeError", "unhashable type: 'L'", 2),
            "class L(list): pass\nL() + 1\n" to Triple("TypeError", "can only concatenate list (not \"int\") to list", 2),
            "class L(list): pass\nx = L()\nx += 1\n" to Triple("TypeError", "'int' object is not iterable", 3),
            "class S(str): pass\nS() < 1\n" to Triple("TypeError", "'<' not supported between instances of 'S' and 'int'", 2),
            "class D(dict): pass\nD()['k']\n" to Triple("KeyError", "'k'", 2),
            "class L(list):\n    def __init__(self, a, b):\n        super().__init__(a, b)\nL(1, 2)\n" to Triple("TypeError", "list expected at most 1 argument, got 2", 3),
            "tuple.__new__(1)\n" to Triple("TypeError", "tuple.__new__(X): X is not a type object (int)", 1),
            "list.__new__(int)\n" to Triple("TypeError", "list.__new__(int): int is not a subtype of list", 1),
            "[] + 1\n" to Triple("TypeError", "can only concatenate list (not \"int\") to list", 1),
            "() + 1\n" to Triple("TypeError", "can only concatenate tuple (not \"int\") to tuple", 1),
            "'a' + 1\n" to Triple("TypeError", "can only concatenate str (not \"int\") to str", 1),
            "[] * 'a'\n" to Triple("TypeError", "can't multiply sequence by non-int of type 'str'", 1),
            "1.5 * 'a'\n" to Triple("TypeError", "can't multiply sequence by non-int of type 'float'", 1),
            "x = ()\nx += 1\n" to Triple("TypeError", "can only concatenate tuple (not \"int\") to tuple", 2),
            "raise 1\n" to Triple("TypeError", "exceptions must derive from BaseException", 1),
            "raise ValueError from 1\n" to Triple("TypeError", "exception causes must derive from BaseException", 1),
            "raise\n" to Triple("RuntimeError", "No active exception to reraise", 1),
            "try:\n    1/0\nexcept 1:\n    pass\n" to Triple("TypeError", "catching classes that do not inherit from BaseException is not allowed", 3),
            "try:\n    1/0\nexcept (1, ValueError):\n    pass\n" to Triple("TypeError", "catching classes that do not inherit from BaseException is not allowed", 3),
            "class C(Exception, list): pass\n" to Triple("TypeError", "multiple bases have instance lay-out conflict", 1),
            "def f():\n    try:\n        1/0\n    except ZeroDivisionError:\n        raise\nf()\n" to Triple("ZeroDivisionError", "division by zero", 3),
            "def f():\n    try:\n        1/0\n    except ZeroDivisionError:\n        raise ValueError('v')\nf()\n" to Triple("ValueError", "v", 5),
            "try:\n    1/0\nexcept ZeroDivisionError:\n    raise ValueError('v')\n" to Triple("ValueError", "v", 4),
            "try:\n    1/0\nexcept ValueError:\n    pass\n" to Triple("ZeroDivisionError", "division by zero", 2),
            "raise ValueError\n" to Triple("ValueError", "", 1),
            "class C: pass\nraise C()\n" to Triple("TypeError", "exceptions must derive from BaseException", 2),
            "class C: pass\nraise C\n" to Triple("TypeError", "exceptions must derive from BaseException", 2),
            "e = ValueError('x')\ne.args = 5\n" to Triple("TypeError", "'int' object is not iterable", 2),
            "try:\n    1/0\nexcept ZeroDivisionError as e:\n    pass\nprint(e)\n" to Triple("NameError", "name 'e' is not defined", 5),
            "class E(Exception):\n    def __str__(self): return 'custom'\nraise E()\n" to Triple("E", "custom", 3),
            "raise KeyError('k')\n" to Triple("KeyError", "'k'", 1),
            "x = {}\ntry:\n    x['a']\nfinally:\n    pass\n" to Triple("KeyError", "'a'", 3),
            "def f():\n    raise OSError(2, 'nf')\ntry:\n    f()\nexcept ValueError:\n    pass\n" to Triple("FileNotFoundError", "[Errno 2] nf", 2),
            "class E(Exception):\n    pass\nraise E(1, 2)\n" to Triple("E", "(1, 2)", 3),
            "e = ValueError()\ne.add_note(1)\n" to Triple("TypeError", "add_note() argument must be str, not int", 2),
            "e = ValueError()\ne.__cause__ = 1\n" to Triple("TypeError", "exception cause must be None or derive from BaseException", 2),
            "def f():\n    try:\n        return 1\n    finally:\n        {}['k']\nf()\n" to Triple("KeyError", "'k'", 5),
            "for i in range(3):\n    try:\n        break\n    finally:\n        1/0\n" to Triple("ZeroDivisionError", "division by zero", 5),
            "with 1:\n    pass\n" to Triple("TypeError", "'int' object does not support the context manager protocol (missed __exit__ method)", 1),
            "class C:\n    def __enter__(self): return 1\nwith C():\n    pass\n" to Triple("TypeError", "'C' object does not support the context manager protocol (missed __exit__ method)", 3),
            "class C:\n    def __exit__(self, *a): pass\nwith C():\n    pass\n" to Triple("TypeError", "'C' object does not support the context manager protocol (missed __enter__ method)", 3),
            "class C:\n    def __enter__(self): return self\n    def __exit__(self, *a): return False\nwith C():\n    1/0\n" to Triple("ZeroDivisionError", "division by zero", 5),
            "class C:\n    def __enter__(self): return self\n    def __exit__(self, *a): raise ValueError('x')\nwith C():\n    pass\n" to Triple("ValueError", "x", 3),
            "class C:\n    def __enter__(self): return self\n    def __exit__(self, *a): return False\ndef f():\n    with C():\n        return {}[1]\nf()\n" to Triple("KeyError", "1", 6),
            "class C:\n    pass\nC(x=1)\n" to Triple("TypeError", "C() takes no arguments", 3),
            "ExceptionGroup('g', [])\n" to Triple("ValueError", "second argument (exceptions) must be a non-empty sequence", 1),
            "ExceptionGroup('g', [1])\n" to Triple("ValueError", "Item 0 of second argument (exceptions) is not an exception", 1),
            "ExceptionGroup(1, [ValueError()])\n" to Triple("TypeError", "BaseExceptionGroup.__new__() argument 1 must be str, not int", 1),
            "ExceptionGroup('g', ValueError())\n" to Triple("TypeError", "second argument (exceptions) must be a sequence", 1),
            "ExceptionGroup('g', [KeyboardInterrupt()])\n" to Triple("TypeError", "Cannot nest BaseExceptions in an ExceptionGroup", 1),
            "try:\n    raise ValueError(1)\nexcept* ExceptionGroup:\n    pass\n" to Triple("TypeError", "catching ExceptionGroup with except* is not allowed. Use except instead.", 3),
            "try:\n    raise ValueError(1)\nexcept* 1:\n    pass\n" to Triple("TypeError", "catching classes that do not inherit from BaseException is not allowed", 3),
            "try:\n    raise ExceptionGroup('g', [ValueError(1), KeyError(2)])\nexcept* ValueError:\n    pass\n" to Triple("ExceptionGroup", "g (1 sub-exception)", 2),
            "try:\n    raise ValueError(1)\nexcept* TypeError:\n    pass\n" to Triple("ValueError", "1", 2),
            "eg = ExceptionGroup('g', [ValueError(1)])\neg.split(1)\n" to Triple("TypeError", "expected an exception type, a tuple of exception types, or a callable (other than a class)", 2),
            "g = (x for x in 1)\n" to Triple("TypeError", "'int' object is not iterable", 1),
            "def g():\n    yield 1\nit = g()\nnext(it)\nnext(it)\n" to Triple("StopIteration", "", 5),
            "def g():\n    yield 1\ng().send(1)\n" to Triple("TypeError", "can't send non-None value to a just-started generator", 3),
            "def g():\n    next(me)\n    yield\nme = g()\nnext(me)\n" to Triple("ValueError", "generator already executing", 2),
            "def g():\n    raise StopIteration(1)\n    yield\nnext(g())\n" to Triple("RuntimeError", "generator raised StopIteration", 4),
            "def g():\n    try:\n        yield 1\n    except GeneratorExit:\n        yield 2\nit = g()\nnext(it)\nit.close()\n" to Triple("RuntimeError", "generator ignored GeneratorExit", 8),
            "def g():\n    yield 1\nit = g()\nnext(it)\nit.throw(1)\n" to Triple("TypeError", "exceptions must be classes or instances deriving from BaseException, not int", 5),
            "def g():\n    yield from 1\nnext(g())\n" to Triple("TypeError", "'int' object is not iterable", 2),
            "[v for v in 5]\n" to Triple("TypeError", "'int' object is not iterable", 1),
            "def g():\n    print('never')\n    yield 1\ng().throw(KeyError('k'))\n" to Triple("KeyError", "'k'", 1),
            "def g():\n    yield 1\nit = g()\nlist(it)\nit.throw(KeyError('k'))\n" to Triple("KeyError", "'k'", 5),
            "def g():\n    yield 1\n    raise ValueError('after')\nlist(g())\n" to Triple("ValueError", "after", 3),
            "d = {k: 1 for k in [[]]}\n" to Triple("TypeError", "cannot use 'list' as a dict key (unhashable type: 'list')", 1),
            "f'{1:z}'\n" to Triple("ValueError", "Negative zero coercion (z) not allowed in integer format specifier", 1),
            "format(1, '.2d')\n" to Triple("ValueError", "Precision not allowed in integer format specifier", 1),
            "format('a', '+')\n" to Triple("ValueError", "Sign not allowed in string format specifier", 1),
            "format('a', '=5')\n" to Triple("ValueError", "'=' alignment not allowed in string format specifier", 1),
            "format('a', ',')\n" to Triple("ValueError", "Cannot specify ',' with 's'.", 1),
            "format(1.5, 'd')\n" to Triple("ValueError", "Unknown format code 'd' for object of type 'float'", 1),
            "format(1, ',x')\n" to Triple("ValueError", "Cannot specify ',' with 'x'.", 1),
            "format(1, '.')\n" to Triple("ValueError", "Format specifier missing precision", 1),
            "format(1, '5x5')\n" to Triple("ValueError", "Invalid format specifier '5x5' for object of type 'int'", 1),
            "class C: pass\nformat(C(), 'x')\n" to Triple("TypeError", "unsupported format string passed to C.__format__", 2),
            "class C:\n    def __format__(self, s): return 1\nf'{C()}'\n" to Triple("TypeError", "__format__ must return a str, not int", 3),
            "'{} {}'.format(1)\n" to Triple("IndexError", "Replacement index 1 out of range for positional args tuple", 1),
            "'{a}'.format()\n" to Triple("KeyError", "'a'", 1),
            "'{0} {}'.format(1, 2)\n" to Triple("ValueError", "cannot switch from manual field specification to automatic field numbering", 1),
            "'{'.format()\n" to Triple("ValueError", "Single '{' encountered in format string", 1),
            "'}'.format()\n" to Triple("ValueError", "Single '}' encountered in format string", 1),
            "'{!x}'.format(1)\n" to Triple("ValueError", "Unknown conversion specifier x", 1),
            "'%d' % 'x'\n" to Triple("TypeError", "%d format: a real number is required, not str", 1),
            "'%s %s' % ('a',)\n" to Triple("TypeError", "not enough arguments for format string", 1),
            "'%s' % ('a', 'b')\n" to Triple("TypeError", "not all arguments converted during string formatting", 1),
            "'%z' % 1\n" to Triple("ValueError", "unsupported format character 'z' (0x7a) at index 1", 1),
            "'%' % ()\n" to Triple("ValueError", "incomplete format", 1),
            "'%(a)s' % 1\n" to Triple("TypeError", "format requires a mapping", 1),
            "'abc' % 5\n" to Triple("TypeError", "not all arguments converted during string formatting", 1),
            "'%x' % 1.5\n" to Triple("TypeError", "%x format: an integer is required, not float", 1),
            "'%c' % 'ab'\n" to Triple("TypeError", "%c requires an int or a unicode character, not a string of length 2", 1),
            "format(1, 'c', 2)\n" to Triple("TypeError", "format expected at most 2 arguments, got 3", 1),
            "format(1, 'c', 2)\n" to Triple("TypeError", "format expected at most 2 arguments, got 3", 1),
            "f'{1:{\"x\"}d}'\n" to Triple("ValueError", "Invalid format specifier 'xd' for object of type 'int'", 1),
            "{1} | [2]\n" to Triple("TypeError", "unsupported operand type(s) for |: 'set' and 'list'", 1),
            "{1} < [2]\n" to Triple("TypeError", "'<' not supported between instances of 'set' and 'list'", 1),
            "set().pop()\n" to Triple("KeyError", "'pop from an empty set'", 1),
            "{[]}\n" to Triple("TypeError", "cannot use 'list' as a set element (unhashable type: 'list')", 1),
            "s = set()\ns.add([])\n" to Triple("TypeError", "cannot use 'list' as a set element (unhashable type: 'list')", 2),
            "s = {1}\ns.remove(2)\n" to Triple("KeyError", "2", 2),
            "frozenset().add(1)\n" to Triple("AttributeError", "'frozenset' object has no attribute 'add'", 1),
            "hash(set())\n" to Triple("TypeError", "unhashable type: 'set'", 1),
            "a = [1, 2, 3]\na[::2] = [1]\n" to Triple("ValueError", "attempt to assign sequence of size 1 to extended slice of size 2", 2),
            "a = [1]\na[0:1] = 1\n" to Triple("TypeError", "must assign iterable to extended slice", 2),
            "[1][::0]\n" to Triple("ValueError", "slice step cannot be zero", 1),
            "[1]['a':]\n" to Triple("TypeError", "slice indices must be integers or None or have an __index__ method", 1),
            "t = (1, 2)\nt[0:1] = [3]\n" to Triple("TypeError", "'tuple' object does not support item assignment", 2),
            "{} | []\n" to Triple("TypeError", "unsupported operand type(s) for |: 'dict' and 'list'", 1),
            "'abc'[::0]\n" to Triple("ValueError", "slice step cannot be zero", 1),
            "s = {1, 2}\nfor x in s:\n    s.add(3)\n" to Triple("RuntimeError", "Set changed size during iteration", 2),
            "slice()\n" to Triple("TypeError", "slice expected at least 1 argument, got 0", 1),
            "{1}[0]\n" to Triple("TypeError", "'set' object is not subscriptable", 1),
            "set(1)\n" to Triple("TypeError", "'int' object is not iterable", 1),
            "[] in {1}\n" to Triple("TypeError", "cannot use 'list' as a set element (unhashable type: 'list')", 1),
            "1j < 2j\n" to Triple("TypeError", "'<' not supported between instances of 'complex' and 'complex'", 1),
            "1j // 2\n" to Triple("TypeError", "unsupported operand type(s) for //: 'complex' and 'int'", 1),
            "complex('abc')\n" to Triple("ValueError", "complex() arg is a malformed string", 1),
            "1 / 0j\n" to Triple("ZeroDivisionError", "division by zero", 1),
            "0j ** -1\n" to Triple("ZeroDivisionError", "zero to a negative or complex power", 1),
            "b'a' + 1\n" to Triple("TypeError", "can't concat int to bytes", 1),
            "bytes('a')\n" to Triple("TypeError", "string argument without an encoding", 1),
            "bytes([256])\n" to Triple("ValueError", "bytes must be in range(0, 256)", 1),
            "b'a'[5]\n" to Triple("IndexError", "index out of range", 1),
            "'é'.encode('ascii')\n" to Triple("UnicodeEncodeError", "'ascii' codec can't encode character '\\xe9' in position 0: ordinal not in range(128)", 1),
            "b'\\xff'.decode()\n" to Triple("UnicodeDecodeError", "'utf-8' codec can't decode byte 0xff in position 0: invalid start byte", 1),
            "'a'.encode('nope')\n" to Triple("LookupError", "unknown encoding: nope", 1),
            "'a' in b'a'\n" to Triple("TypeError", "a bytes-like object is required, not 'str'", 1),
            "t'{1}' + 'x'\n" to Triple("TypeError", "can only concatenate string.templatelib.Template (not \"str\") to string.templatelib.Template", 1),
            "sorted(1)\n" to Triple("TypeError", "'int' object is not iterable", 1),
            "min()\n" to Triple("TypeError", "min expected at least 1 argument, got 0", 1),
            "max([])\n" to Triple("ValueError", "max() iterable argument is empty", 1),
            "min(1, 2, default=0)\n" to Triple("TypeError", "Cannot specify a default for min() with multiple positional arguments", 1),
            "max([1], foo=1)\n" to Triple("TypeError", "max() got an unexpected keyword argument 'foo'", 1),
            "chr(-1)\n" to Triple("ValueError", "chr() arg not in range(0x110000)", 1),
            "ord('ab')\n" to Triple("TypeError", "ord() expected a character, but string of length 2 found", 1),
            "hex(1.5)\n" to Triple("TypeError", "'float' object cannot be interpreted as an integer", 1),
            "pow(2, 3, 0)\n" to Triple("ValueError", "pow() 3rd argument cannot be 0", 1),
            "pow(2.0, 3, 5)\n" to Triple("TypeError", "pow() 3rd argument not allowed unless all arguments are integers", 1),
            "pow(2, -1, 4)\n" to Triple("ValueError", "base is not invertible for the given modulus", 1),
            "round(float('inf'))\n" to Triple("OverflowError", "cannot convert float infinity to integer", 1),
            "round('x')\n" to Triple("TypeError", "type str doesn't define __round__ method", 1),
            "reversed(1)\n" to Triple("TypeError", "'int' object is not reversible", 1),
            "list(zip([1], [1, 2], strict=True))\n" to Triple("ValueError", "zip() argument 2 is longer than argument 1", 1),
            "divmod(1, 0)\n" to Triple("ZeroDivisionError", "division by zero", 1),
            "vars(1)\n" to Triple("TypeError", "vars() argument must have __dict__ attribute", 1),
            "sum(['a'], '')\n" to Triple("TypeError", "sum() can't sum strings [use ''.join(seq) instead]", 1),
            "map(len)\n" to Triple("TypeError", "map() must have at least two arguments.", 1),
            "next(map(len, [1]))\n" to Triple("TypeError", "object of type 'int' has no len()", 1),
            "sorted([1, 'a'])\n" to Triple("TypeError", "'<' not supported between instances of 'str' and 'int'", 1),
            "'a'.split('')\n" to Triple("ValueError", "empty separator", 1),
            "'abc'.index('z')\n" to Triple("ValueError", "substring not found", 1),
            "'a'.center(5, 'xy')\n" to Triple("TypeError", "The fill character must be exactly one character long", 1),
            "'a'.startswith(1)\n" to Triple("TypeError", "startswith first arg must be str or a tuple of str, not int", 1),
            "'a'.replace(1, 'b')\n" to Triple("TypeError", "replace() argument 1 must be str, not int", 1),
            "str.maketrans('ab', 'c')\n" to Triple("ValueError", "the first two maketrans arguments must have equal length", 1),
            "'a'.partition('')\n" to Triple("ValueError", "empty separator", 1),
            "'abc'.join([1])\n" to Triple("TypeError", "sequence item 0: expected str instance, int found", 1),
            "'abc'.rindex('z')\n" to Triple("ValueError", "substring not found", 1),
            "[].remove(1)\n" to Triple("ValueError", "list.remove(x): x not in list", 1),
            "[1].index(2)\n" to Triple("ValueError", "list.index(x): x not in list", 1),
            "(1,).index(2)\n" to Triple("ValueError", "tuple.index(x): x not in tuple", 1),
            "{}.popitem()\n" to Triple("KeyError", "'popitem(): dictionary is empty'", 1),
            "{}.pop('k')\n" to Triple("KeyError", "'k'", 1),
            "d = {1: 2}\nfor k in d:\n    d[k + 1] = 0\n" to Triple("RuntimeError", "dictionary changed size during iteration", 2),
            "[3, 'a'].sort()\n" to Triple("TypeError", "'<' not supported between instances of 'str' and 'int'", 1),
            "[].sort(1)\n" to Triple("TypeError", "sort() takes no positional arguments", 1),
            "{}.update(1)\n" to Triple("TypeError", "'int' object is not iterable", 1),
            "{}.update([1])\n" to Triple("TypeError", "object is not iterable", 1),
            "[].insert(1)\n" to Triple("TypeError", "insert expected 2 arguments, got 1", 1),
            "match 1:\n    case len():\n        pass\n" to Triple("TypeError", "called match pattern must be a class", 2),
            "class C: pass\nmatch C():\n    case C(1):\n        pass\n" to Triple("TypeError", "C() accepts 0 positional sub-patterns (1 given)", 3),
            "class C:\n    __match_args__ = (1,)\nmatch C():\n    case C(x):\n        pass\n" to Triple("TypeError", "__match_args__ elements must be strings (got int)", 4),
            "class C:\n    __match_args__ = ('a',)\n    a = 1\nmatch C():\n    case C(x, a=y):\n        pass\n" to Triple("TypeError", "C() got multiple sub-patterns for attribute 'a'", 5),
            "class C:\n    __match_args__ = ['a']\n    a = 1\nmatch C():\n    case C(x):\n        pass\n" to Triple("TypeError", "C.__match_args__ must be a tuple (got list)", 5),
            "match 1:\n    case x if 1 / 0:\n        pass\n" to Triple("ZeroDivisionError", "division by zero", 2),
            "match {'a': 1}:\n    case {'a': 1, **r} if r['x']:\n        pass\n" to Triple("KeyError", "'x'", 2),
            "import nope\n" to Triple("ModuleNotFoundError", "No module named 'nope'", 1),
            "from . import x\n" to Triple("ImportError", "attempted relative import with no known parent package", 1),
            "import sys\nsys.exit('bye')\n" to Triple("SystemExit", "bye", 2),
            "import sys\nsys.nope\n" to Triple("AttributeError", "module 'sys' has no attribute 'nope'", 2),
            "import string.nope\n" to Triple("ModuleNotFoundError", "No module named 'string.nope'", 1),
            "async def f():\n    await 1\nf().send(None)\n" to Triple("TypeError", "'int' object can't be awaited", 2),
            "async def c():\n    raise StopIteration\nc().send(None)\n" to Triple("RuntimeError", "coroutine raised StopIteration", 3),
            "async def ag():\n    raise StopAsyncIteration\n    yield\nag().__anext__().send(None)\n" to Triple("RuntimeError", "async generator raised StopAsyncIteration", 4),
            "class M:\n    pass\nasync def f():\n    async with M():\n        pass\nf().send(None)\n" to Triple("TypeError", "'M' object does not support the asynchronous context manager protocol (missed __aexit__ method)", 4),
            "async def f():\n    async for x in 1:\n        pass\nf().send(None)\n" to Triple("TypeError", "'async for' requires an object with __aiter__ method, got int", 2),
            "async def c():\n    pass\nx = c()\nx.close()\nx.send(None)\n" to Triple("RuntimeError", "cannot reuse already awaited coroutine", 5),
            "async def ag():\n    yield 1\ng = ag()\ng.asend(1).send(None)\n" to Triple("TypeError", "can't send non-None value to a just-started async generator", 4),
            "async def f():\n    return [x async for x in [1]]\nf().send(None)\n" to Triple("TypeError", "'async for' requires an object with __aiter__ method, got list", 2),
            "def f():\n    return (x async for x in 1)\nf()\n" to Triple("TypeError", "'async for' requires an object with __aiter__ method, got int", 2),
            "async def g():\n    await h()\nasync def h():\n    await 1\ng().send(None)\n" to Triple("TypeError", "'int' object can't be awaited", 4),
            "aiter(1)\n" to Triple("TypeError", "'int' object is not an async iterable", 1),
            "async def f():\n    async with 1:\n        pass\nf().send(None)\n" to Triple("TypeError", "'int' object does not support the asynchronous context manager protocol (missed __aexit__ method)", 2),
            "a, b = 1\n" to Triple("TypeError", "cannot unpack non-iterable int object", 1),
            "for a, b in [1]:\n    pass\n" to Triple("TypeError", "cannot unpack non-iterable int object", 1),
            "class C:\n    def __getitem__(self, i):\n        raise TypeError(\"gi\")\na, b = C()\n" to Triple("TypeError", "gi", 3),
            "def g(x: undefined_name):\n    pass\ng.__annotations__\n" to Triple("NameError", "name 'undefined_name' is not defined", 1),
            "class C:\n    x: undefined_name\nC.__annotations__\n" to Triple("NameError", "name 'undefined_name' is not defined", 2),
            "x: undefined_name\n__annotate__(1)\n" to Triple("NameError", "name 'undefined_name' is not defined", 1),
            "def f(x: int): pass\nf.__annotate__(3)\n" to Triple("NotImplementedError", "", 1),
            "def f(): pass\nf.__annotations__ = 1\n" to Triple("TypeError", "__annotations__ must be set to a dict object", 2),
            "class C:\n    x: int\ndel C.__annotations__\n" to Triple("AttributeError", "__annotations__", 3),
            "int.__annotations__\n" to Triple("AttributeError", "type object 'int' has no attribute '__annotations__'", 1),
            "def f(x: int): pass\nf.__annotate__ = 5\n" to Triple("TypeError", "__annotate__ must be callable or None", 2),
            "list[int][str]\n" to Triple("TypeError", "list[int] is not a generic class", 1),
            "int[0]\n" to Triple("TypeError", "type 'int' is not subscriptable", 1),
            "isinstance([], list[int])\n" to Triple("TypeError", "isinstance() argument 2 cannot be a parameterized generic", 1),
            "int | 1\n" to Triple("TypeError", "unsupported operand type(s) for |: 'type' and 'int'", 1),
            "(int | str)()\n" to Triple("TypeError", "'typing.Union' object is not callable", 1),
            "class B:\n    def __mro_entries__(self, b):\n        return 1\nclass X(B()):\n    pass\n" to Triple("TypeError", "__mro_entries__ must return a tuple", 4),
            "issubclass(list, dict[str, int])\n" to Triple("TypeError", "issubclass() argument 2 cannot be a parameterized generic", 1),
            "def f[T](x=T): pass\n" to Triple("NameError", "name 'T' is not defined", 1),
            "type A = int\nA[int]\n" to Triple("TypeError", "Only generic type aliases are subscriptable", 2),
            "def f[T](): pass\nf.__type_params__[0]()\n" to Triple("TypeError", "'typing.TypeVar' object is not callable", 2),
            "def f[T: undefined](): pass\nf.__type_params__[0].__bound__\n" to Triple("NameError", "name 'undefined' is not defined", 1),
            "type A = undefined\nA.__value__\n" to Triple("NameError", "name 'undefined' is not defined", 1),
            "def f(): pass\nf.__type_params__ = 1\n" to Triple("TypeError", "__type_params__ must be set to a tuple", 2),
            "class A: pass\nclass B(A, A): pass\n" to Triple("TypeError", "duplicate base class A", 2),
            // (raised in typing.py, as in CPython: the line of its raise in the bundled typing.py)
            "class K[T]: pass\nK[int, str]\n" to Triple("TypeError", "Too many arguments for <class '__main__.K'>; actual 2, expected 1", 337),
            "class K[T]: pass\nclass L(K[int]): pass\nL[int]\n" to Triple("TypeError", "<class '__main__.L'> is not a generic class", 317),
            "from_k = 1\nclass K[T]: pass\nisinstance(K(), K[int])\n" to Triple("TypeError", "Subscripted generics cannot be used with class and instance checks", 1296),
            "class K[T]: pass\nG = K.__mro__[1]\nG[int]\n" to Triple("TypeError", "Parameters to Generic[...] must all be type variables or parameter specification variables.", 1137),
            "class K[T]: pass\nG = K.__mro__[1]\nclass X(G): pass\n" to Triple("TypeError", "Cannot inherit from plain Generic", 1184),
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
                PyContext(out, config(mode), loader, StringBuilder()).exec(src)
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
            "dunders", "protocols", "subclasses", "exceptions", "exception_objects", "with", "exception_groups", "generators", "comprehensions", "formatting", "sets", "slices", "literals", "builtins", "strmethods", "containers", "match", "imports", "coroutines", "async_generators", "annotations", "future_annotations", "generic_aliases", "type_params", "generic_classes", "private_names", "eval_exec", "suspensions", "rich_compare", "number_subclasses", "frames", "weakrefs", "stdlib", "descriptor_subclasses", "sys_module", "itertools_module", "collections_module", "type_checks", "ranges", "byte_sequences", "stdlib_core", "class_protocols", "enums", "class_namespaces", "mapping_proxies", "context_vars", "regexes", "warnings_module", "string_module", "ast_module", "typing_module", "math_module", "typing_generics", "os_module", "random_module", "importlib_module", "code_objects", "codecs_module", "struct_module", "time_module", "tokenize_module", "io_module", "class_slots", "builtin_functions", "library_modules", "io_errors", "hashing", "pure_modules",
        )

        /** The code blocks of [cb] and every function it defines. */
        fun blocks(cb: CodeUnit): List<CodeUnit> = listOf(cb) + cb.constants.filterIsInstance<CodeUnit>().flatMap { blocks(it) }
    }
}
