package dev.mooner.neonpython

import dev.mooner.neonjs.compiler.CodeUnit
import dev.mooner.neonjs.jit.CompiledCode
import dev.mooner.neonjs.jit.Jit
import dev.mooner.neonjs.jit.VmRt
import dev.mooner.neonjs.runtime.VmAgent
import dev.mooner.neonjs.runtime.VmConfig
import dev.mooner.neonjs.vm.VmFrame

/**
 * A Python interpreter's state: where print writes ([out]), the builtins, and the execution core's agent (call depth,
 * limits and interrupts, the execution mode and the JIT's settings: [VmConfig]), which frames carry. One thread at a
 * time (docs/PYTHON_PLATFORM.md: single-threaded, its state in one place for a GIL later).
 */
class PyContext(
    @JvmField val out: Appendable = StringBuilder(),
    config: VmConfig = VmConfig(),
    /** Where the modules `import` imports come from (none: the builtin modules only). */
    @JvmField val loader: PyModuleLoader? = null,
    /** What sys.stderr writes to. */
    @JvmField val err: Appendable = out,
) {
    @JvmField val agent = VmAgent(config)
    @JvmField val builtins: PyDict = PyBuiltins.module()
    /** sys.modules */
    @JvmField val modules = PyDict()
    /** The code of each module imported (the tests check them all compiled). */
    @JvmField val moduleUnits = ArrayList<dev.mooner.neonjs.compiler.CodeUnit>()
    /** The code of each module of the bundled library imported (their module bodies may be past the tier's block limit: interpreted, §12.2). */
    @JvmField val libraryUnits = ArrayList<dev.mooner.neonjs.compiler.CodeUnit>()
    @JvmField val sysModule: PyModule = PyImport.sys(this)
    /** The exception being handled (PyExceptions: PUSH_EXC_INFO sets it, POP_EXCEPT puts the one before back), or null. */
    @JvmField var handled: PyBaseException? = null
    /** The callers' exceptions handled, under the generators running (each its own [handled]: PyGenBase.resume). */
    @JvmField val handledStack = ArrayList<PyBaseException?>()
    /** The innermost frame running (sys._getframe), the chain out through PyFrame.back. */
    @JvmField var top: PyFrame? = null
    /** Weak references' and `__del__`'s (PyWeakrefs): the queue the collector puts what it found gone on, the instances with `__del__` followed, each object's weak references. */
    @JvmField val refQueue = java.lang.ref.ReferenceQueue<Any>()
    @JvmField val finalizers = HashSet<PyWeakrefs.Del>()
    @JvmField val weakrefs = HashMap<PyWeakrefs.IdKey, ArrayList<java.lang.ref.WeakReference<PyObject>>>()
    @JvmField var draining = false
    @JvmField var weakrefNotes = 0
    @JvmField var gcEnabled = true

    /** The source of each module imported, by its file (a traceback's lines). */
    @JvmField val sources = HashMap<String, String>()

    /** [e] (one Python code raised and did not catch) as CPython prints it: its traceback, then its type and message. */
    fun formatException(e: PyException): String {
        // (with this context current: a class's __str__ may run, KeyError's is its key's repr)
        val outer = CURRENT.get()
        CURRENT.set(this)
        try {
            return PyFrames.format(PyExceptions.objectOf(e, null), sources)
        } finally {
            CURRENT.set(outer)
        }
    }

    /** The exception handled here: the running code's, else the nearest caller's (a generator with none of its own). */
    fun currentHandled(): PyBaseException? {
        handled?.let { return it }
        for (i in handledStack.indices.reversed()) handledStack[i]?.let { return it }
        return null
    }

    /** Runs the module [src]; returns its globals. */
    fun exec(src: String, name: String = "<module>"): PyDict = exec(PyCompiler.withFilename(PyCompiler.compileModule(src, name), "<string>"), name)

    /** Runs a module's code [cb] (PyCompiler.compileModule's); returns its globals. */
    fun exec(cb: dev.mooner.neonjs.compiler.CodeUnit, name: String = "<module>"): PyDict {
        val globals = PyDict()
        globals[PyStr("__name__")] = PyStr("__main__")
        globals[PyStr("__doc__")] = PyNone
        globals[PyStr("__package__")] = PyNone
        modules[PyStr("__main__")] = PyModule("__main__", globals)
        val outer = CURRENT.get()
        val outerHandled = handled
        CURRENT.set(this)
        try {
            PyInterpreter.call(this, PyFunction(cb, globals, name), NO_ARGS)
        } catch (e: PyException) {
            // (its message while Python code can still run: a class's __str__)
            e.freeze()
            throw e
        } finally {
            CURRENT.set(outer)
            handled = outerHandled
        }
        return globals
    }

    /** Runs an imported module's code [cb] in its dict [globals] (PyImport). */
    fun runModule(cb: dev.mooner.neonjs.compiler.CodeUnit, globals: PyDict) {
        PyInterpreter.call(this, PyFunction(cb, globals, "<module>"), NO_ARGS)
    }

    companion object {
        private val NO_ARGS = arrayOfNulls<Any?>(0)
        private val CURRENT = ThreadLocal<PyContext?>()

        /** The context running Python code on this thread (descriptors and __getattr__ call back into it). */
        @JvmStatic fun current(): PyContext = CURRENT.get() ?: throw IllegalStateException("no Python code running")
    }
}

/**
 * A Python activation: the core's frame (registers, then the operand stack, in its slots; its pc), with its code, the
 * context and the globals of the function's module. Registers without a value hold null (the optimizing tier's dead
 * value too), which LOAD_FAST_CHECK raises an UnboundLocalError for; an interpreted frame starts with its parameters'
 * registers holding the arguments.
 */
class PyFrame(@JvmField val code: CodeUnit, @JvmField val ctx: PyContext, @JvmField val globals: PyDict, args: Array<Any?>, compiled: CompiledCode?,
              /** the cells of its function's free variables (LOAD_FREE) */
              @JvmField val closure: Array<Any?>? = null,
              /** a class body's namespace (LOAD_NAME, STORE_NAME) */
              @JvmField val locals: PyDict? = null) :
    VmFrame(args, if (compiled != null) NO_SLOTS else slotsFor(code), code.numRegs) {
    override val unit: CodeUnit get() = code
    override val agent: VmAgent get() = ctx.agent

    /** A generator's: the value its YIELD hands over, and how it is resumed (MODE_NEXT, MODE_THROW, MODE_THROW_ALL). */
    @JvmField var suspendValue: Any? = null
    @JvmField var resumeMode = MODE_NEXT
    /** The frame running it, while it runs (f_back); its frame object, once asked for (PyFrames). */
    @JvmField var back: PyFrame? = null
    @JvmField var obj: PyFrameObj? = null
    /** A function's locals' snapshot, what exec() and eval() called from it take as their locals (SET_EVAL_LOCALS). */
    @JvmField var evalLocals: PyDict? = null

    init {
        if (compiled == null) for (i in args.indices) slots[i] = args[i]
    }

    companion object {
        const val MODE_NEXT = 0
        const val MODE_THROW = 1
        /** thrown in, GeneratorExit too passed on to what a `yield from` or await steps (an async generator's aclose) */
        const val MODE_THROW_ALL = 2
    }
}

/**
 * Python's interpreter of [PyOp]'s bytecode, and its calls: a function runs its block's compiled code where the JIT's
 * policy has some (Jit.prepare: the execution mode, the call counts), interpreted otherwise; an interpreted loop goes
 * on in compiled code once there is some (Jit.loop, Jit.osr), as JavaScript's interpreter does.
 */
object PyInterpreter {
    /** Calls [fn] with positional [args]: as they are where they are its parameters, else bound (PyCalls.bind). */
    @JvmStatic
    fun call(ctx: PyContext, fn: PyFunction, args: Array<Any?>): Any? {
        val a = if (args.size == fn.params && fn.simple) args else PyCalls.bind(fn, args, NO_ARGS, NO_ARGS)
        return callBound(ctx, fn, a)
    }

    /** Calls [fn] with [args] its parameters' values, in its registers' order. */
    @JvmStatic
    fun callBound(ctx: PyContext, fn: PyFunction, args: Array<Any?>): Any? {
        val code = fn.code
        // a generator function's generator, an async def's coroutine: its frame (an interpreted one, which compiled code
        // runs from where it is) not run yet; its making the JIT's policy's call (Jit.prepare), as a function's
        if (fn.info.kind != 0) {
            Jit.prepare(code, ctx.agent)
            val f = PyFrame(code, ctx, fn.globals, args, null, fn.closure)
            return when (fn.info.kind) {
                PyCodeInfo.GENERATOR -> PyGenerator(f, fn)
                PyCodeInfo.COROUTINE -> PyCoroutine(f, fn)
                else -> PyAsyncGenerator(f, fn)
            }
        }
        val c = Jit.prepare(code, ctx.agent)
        return runFrame(PyFrame(code, ctx, fn.globals, args, c, fn.closure), c)
    }

    private val NO_ARGS = arrayOfNulls<Any?>(0)

    /** What [run] returns where a generator's frame suspended (its YIELD). */
    @JvmField val SUSPENDED = Any()

    /**
     * Runs a generator's frame on from where it is, as [runFrame] runs a function's: [SUSPENDED] at a yield. Its code's
     * compiled code where there is some, entered at the frame's pc (the start, a resumption, a loop header); interpreted
     * where it has no entry there or leaves for the interpreter.
     */
    @JvmStatic
    fun runGenerator(f: PyFrame): Any? = runFrame(f, f.code.compiled as CompiledCode?)

    /** Runs a class body, function [fn] without arguments, on namespace [ns]. */
    @JvmStatic
    fun callWithLocals(ctx: PyContext, fn: PyFunction, ns: PyDict): Any? {
        val code = fn.code
        val c = Jit.prepare(code, ctx.agent)
        return runFrame(PyFrame(code, ctx, fn.globals, NO_ARGS, c, fn.closure, ns), c)
    }

    /** Runs [f] (with [c], or interpreted), with the agent's call depth and interrupts kept. */
    private fun runFrame(f: PyFrame, c: CompiledCode?): Any? {
        val ctx = f.ctx
        val agent = ctx.agent
        if (++agent.depth > agent.maxDepth) {
            agent.depth--
            throw PyException("RecursionError", "maximum recursion depth exceeded")
        }
        if (--agent.callBudget < 0) {
            agent.callBudget = 1024
            try {
                agent.checkInterrupt()
                // (what the collector found gone: its weak references' callbacks, its __del__)
                PyWeakrefs.drain(ctx)
            } catch (t: Throwable) {
                agent.depth--
                throw t
            }
        }
        val back = ctx.top
        f.back = back
        ctx.top = f
        try {
            if (c == null) return run(f)
            val r = c.run(f)
            // (an interpreted frame, a generator's, the code has no entry for at its pc, or that left the code: interpreted)
            return if (r === VmRt.NO_OSR || r === VmRt.DEOPTED) run(f) else r
        } catch (e: PyException) {
            // the line of the innermost frame (compiled code stores its statement's pc where something can throw)
            if (e.line < 0) {
                e.line = (f.code.dialectData as PyCodeInfo).lineAt(f.code, f.pc)
                e.function = f.code.name
            }
            // (its entry where it leaves this frame, the tier's code having none of its own)
            PyFrames.addTraceback(e, f)
            throw e
        } catch (e: StackOverflowError) {
            throw PyException("RecursionError", "maximum recursion depth exceeded")
        } finally {
            ctx.top = back
            f.back = null
            agent.depth--
        }
    }

    private fun unbound(cb: CodeUnit, r: Int): PyException {
        val n = (cb.dialectData as PyCodeInfo).localNames[r]
        return PyException("UnboundLocalError", "cannot access local variable '$n' where it is not associated with a value")
    }

    /** Interprets [f] from its pc (0, a loop header after an exit of compiled code) to its return. */
    @JvmStatic
    fun run(f: PyFrame): Any? {
        val cb = f.code
        val bc = cb.code
        val k = cb.constants
        val s = f.slots
        var sp = f.sp
        var pc = f.pc
        var opStart = pc
        var budget = Jit.LOOP_CHECK
        // the operand types of arithmetic and comparisons, for the speculating tier (when it is on)
        val prof = if (f.ctx.agent.config.tier2) (cb.dialectData as PyCodeInfo).profile(bc.size) else null
        while (true) {
            try {
                dispatch@ while (true) {
                    opStart = pc
                    if (prof != null) {
                        val op = bc[pc]
                        if (op in PyOp.ADD..PyOp.BITXOR || op in PyOp.LT..PyOp.NE || op == PyOp.INPLACE) PyProfile.record(prof, pc, PyProfile.of(s[sp - 2], s[sp - 1]))
                        else if (op == PyOp.NEG) PyProfile.record(prof, pc, PyProfile.of(s[sp - 1]))
                    }
                    when (bc[pc]) {
                        PyOp.NOP -> pc++
                        PyOp.LOAD_CONST -> { s[sp++] = k[bc[pc + 1]]; pc += 2 }
                        PyOp.LOAD_FAST -> { s[sp++] = s[bc[pc + 1]]; pc += 2 }
                        PyOp.LOAD_FAST_CHECK -> {
                            val v = s[bc[pc + 1]]
                            if (v == null) throw unbound(cb, bc[pc + 1])
                            s[sp++] = v
                            pc += 2
                        }
                        PyOp.STORE_FAST -> { s[bc[pc + 1]] = s[--sp]; s[sp] = null; pc += 2 }
                        PyOp.LOAD_GLOBAL -> { s[sp++] = PyRt.loadGlobal(f, k[bc[pc + 1]]); pc += 2 }
                        PyOp.STORE_GLOBAL -> { PyRt.storeGlobal(f, k[bc[pc + 1]], s[--sp]); s[sp] = null; pc += 2 }
                        PyOp.POP -> { s[--sp] = null; pc++ }
                        PyOp.DUP -> { s[sp] = s[sp - 1]; sp++; pc++ }
                        PyOp.SWAP -> { val t = s[sp - 1]; s[sp - 1] = s[sp - 2]; s[sp - 2] = t; pc++ }
                        PyOp.ADD -> { sp--; s[sp - 1] = PyRt.add(s[sp - 1], s[sp]); s[sp] = null; pc++ }
                        PyOp.SUB -> { sp--; s[sp - 1] = PyRt.sub(s[sp - 1], s[sp]); s[sp] = null; pc++ }
                        PyOp.MUL -> { sp--; s[sp - 1] = PyRt.mul(s[sp - 1], s[sp]); s[sp] = null; pc++ }
                        PyOp.TRUEDIV -> { sp--; s[sp - 1] = PyRt.truediv(s[sp - 1], s[sp]); s[sp] = null; pc++ }
                        PyOp.FLOORDIV -> { sp--; s[sp - 1] = PyRt.floordiv(s[sp - 1], s[sp]); s[sp] = null; pc++ }
                        PyOp.MOD -> { sp--; s[sp - 1] = PyRt.mod(s[sp - 1], s[sp]); s[sp] = null; pc++ }
                        PyOp.POW -> { sp--; s[sp - 1] = PyRt.pow(s[sp - 1], s[sp]); s[sp] = null; pc++ }
                        PyOp.LSHIFT -> { sp--; s[sp - 1] = PyRt.lshift(s[sp - 1], s[sp]); s[sp] = null; pc++ }
                        PyOp.RSHIFT -> { sp--; s[sp - 1] = PyRt.rshift(s[sp - 1], s[sp]); s[sp] = null; pc++ }
                        PyOp.BITAND -> { sp--; s[sp - 1] = PyRt.bitAnd(s[sp - 1], s[sp]); s[sp] = null; pc++ }
                        PyOp.BITOR -> { sp--; s[sp - 1] = PyRt.bitOr(s[sp - 1], s[sp]); s[sp] = null; pc++ }
                        PyOp.BITXOR -> { sp--; s[sp - 1] = PyRt.bitXor(s[sp - 1], s[sp]); s[sp] = null; pc++ }
                        PyOp.YIELD -> {
                            f.suspendValue = s[--sp]
                            s[sp] = null
                            f.pc = pc + 1
                            f.sp = sp
                            return SUSPENDED
                        }
                        PyOp.SEND -> {
                            f.pc = opStart
                            val r = PyGenerators.send(f, s[bc[pc + 1]], s[sp - 1])
                            if (r is PyGenerators.Done) {
                                s[sp - 1] = r.value
                                pc = bc[pc + 2]
                            } else {
                                s[sp - 1] = r
                                pc += 3
                            }
                        }
                        PyOp.INPLACE -> { sp--; s[sp - 1] = PyRt.inplace(s[sp - 1], s[sp], bc[pc + 1]); s[sp] = null; pc += 2 }
                        PyOp.LT, PyOp.LE, PyOp.GT, PyOp.GE, PyOp.EQ, PyOp.NE -> {
                            sp--
                            s[sp - 1] = PyRt.compareValue(bc[pc], s[sp - 1], s[sp])
                            s[sp] = null
                            pc++
                        }
                        PyOp.IS, PyOp.IS_NOT, PyOp.IN, PyOp.NOT_IN -> {
                            sp--
                            s[sp - 1] = PyBool.of(PyRt.compare(bc[pc], s[sp - 1], s[sp]))
                            s[sp] = null
                            pc++
                        }
                        PyOp.NEG -> { s[sp - 1] = PyRt.neg(s[sp - 1]); pc++ }
                        PyOp.POS -> { s[sp - 1] = PyRt.pos(s[sp - 1]); pc++ }
                        PyOp.NOT -> { s[sp - 1] = PyRt.not(s[sp - 1]); pc++ }
                        PyOp.INVERT -> { s[sp - 1] = PyRt.invert(s[sp - 1]); pc++ }
                        PyOp.JUMP -> {
                            val t = bc[pc + 1]
                            if (t < pc && --budget <= 0) {
                                budget = Jit.LOOP_CHECK
                                if (backEdge(f, sp)) { pc = t; break@dispatch }
                            }
                            pc = t
                        }
                        PyOp.POP_JUMP_IF_FALSE -> {
                            val v = s[--sp]
                            s[sp] = null
                            pc = if (!PyRt.truthy(v)) bc[pc + 1] else pc + 2
                        }
                        PyOp.POP_JUMP_IF_TRUE -> {
                            val v = s[--sp]
                            s[sp] = null
                            pc = if (PyRt.truthy(v)) bc[pc + 1] else pc + 2
                        }
                        PyOp.CALL -> {
                            val n = bc[pc + 1]
                            val args = arrayOfNulls<Any?>(n)
                            for (i in n - 1 downTo 0) {
                                args[i] = s[--sp]
                                s[sp] = null
                            }
                            f.pc = opStart
                            s[sp - 1] = PyRt.call(f, s[sp - 1], args)
                            pc += 2
                        }
                        PyOp.RETURN -> return s[--sp]
                        PyOp.BUILD_LIST -> {
                            val n = bc[pc + 1]
                            val items = ArrayList<Any?>(n)
                            for (i in sp - n until sp) { items.add(s[i]); s[i] = null }
                            sp -= n
                            s[sp++] = PyList(items)
                            pc += 2
                        }
                        PyOp.BUILD_TUPLE -> {
                            val n = bc[pc + 1]
                            val items = arrayOfNulls<Any?>(n)
                            for (i in 0 until n) { items[i] = s[sp - n + i]; s[sp - n + i] = null }
                            sp -= n
                            s[sp++] = if (n == 0) PyTuple.EMPTY else PyTuple(items)
                            pc += 2
                        }
                        PyOp.BUILD_MAP -> {
                            val n = bc[pc + 1]
                            val d = PyDict()
                            val base = sp - 2 * n
                            for (i in 0 until n) d[s[base + 2 * i]] = s[base + 2 * i + 1]
                            for (i in base until sp) s[i] = null
                            sp = base
                            s[sp++] = d
                            pc += 2
                        }
                        PyOp.SUBSCR -> { sp--; s[sp - 1] = PyRt.getItem(s[sp - 1], s[sp]); s[sp] = null; pc++ }
                        PyOp.STORE_SUBSCR -> {
                            PyRt.setItem(s[sp - 2], s[sp - 1], s[sp - 3])
                            s[sp - 1] = null; s[sp - 2] = null; s[sp - 3] = null
                            sp -= 3
                            pc++
                        }
                        PyOp.LOAD_ATTR -> { s[sp - 1] = PyRt.loadAttr(s[sp - 1], k[bc[pc + 1]]); pc += 2 }
                        PyOp.GET_ITER -> { s[sp - 1] = PyRt.iter(s[sp - 1]); pc++ }
                        PyOp.FOR_ITER -> {
                            val v = PyRt.next(s[bc[pc + 1]])
                            if (v === PyIterator.STOP) pc = bc[pc + 3]
                            else {
                                s[bc[pc + 2]] = v
                                pc += 4
                            }
                        }
                        PyOp.UNPACK -> {
                            val n = bc[pc + 1]
                            val items = PyRt.unpack(s[--sp], n)
                            for (i in n - 1 downTo 0) s[sp++] = items[i]
                            pc += 2
                        }
                        PyOp.MAKE_FUNCTION -> { s[sp++] = PyRt.makeFunction(f, k[bc[pc + 1]]); pc += 2 }
                        else -> {
                            // the rarer instructions (calls with keywords, closures, displays with unpacking, del)
                            f.pc = opStart
                            sp = ext(f, s, bc, pc, sp)
                            pc += PyOp.length(bc[pc])
                        }
                    }
                }
            } catch (t: Throwable) {
                f.pc = opStart
                f.sp = sp
                if (t is PyException) PyFrames.addTraceback(t, f)
                if (!handle(f, t)) throw t
                pc = f.pc
                sp = f.sp
                continue
            }
            // a loop continues in compiled code (backEdge)
            f.pc = pc
            f.sp = sp
            val r = Jit.osr(f)
            if (r === VmRt.DEOPTED) {
                // the compiled code left for the interpreter (PyDialect's resumer): the frame continues here
                pc = f.pc
                sp = f.sp
                continue
            }
            if (r !== VmRt.NO_OSR) return r
        }
    }

    /**
     * Where [t], raised at [f]'s pc, goes: the innermost handler covering the pc, the operand stack cut to its depth and
     * the exception object pushed (the frame's pc and sp set to go on there). False where none covers it, or [t] is
     * none of Python's (a sandbox's termination): it leaves the frame.
     */
    private fun handle(f: PyFrame, t: Throwable): Boolean {
        val cb = f.code
        val h = cb.handlers
        var k = 0
        while (k < h.size && !(f.pc >= h[k] && f.pc < h[k + 1])) k += 4
        if (k >= h.size) return false
        val v = PyExceptions.caught(t, f) ?: return false
        val s = f.slots
        val base = cb.numRegs + h[k + 3]
        for (i in base until f.sp) s[i] = null
        s[base] = v
        f.sp = base + 1
        f.pc = h[k + 2]
        return true
    }

    /** The instruction at [pc] of [f]'s code, one that does not jump ([run]'s rarer ones): the new top of the stack. */
    private fun ext(f: PyFrame, s: Array<Any?>, bc: IntArray, pc: Int, top: Int): Int {
        var sp = top
        val k = f.code.constants
        val a = if (PyOp.operands[bc[pc]] > 0) bc[pc + 1] else 0
        fun pop(): Any? {
            val v = s[--sp]
            s[sp] = null
            return v
        }
        fun popN(n: Int): Array<Any?> {
            val out = arrayOfNulls<Any?>(n)
            for (i in n - 1 downTo 0) out[i] = pop()
            return out
        }
        when (val op = bc[pc]) {
            PyOp.CALL_KW, PyOp.CALL_EX -> {
                val rest = popN(a + 1)
                val fn = s[sp - 1]
                s[sp - 1] = if (op == PyOp.CALL_KW) PyCalls.callKw(f, fn, rest) else PyCalls.callEx(f, fn, rest)
            }
            PyOp.MAKE_CELL -> s[sp - 1] = PyCalls.makeCell(s[sp - 1])
            PyOp.NEW_CELL -> s[sp++] = PyCalls.newCell()
            PyOp.LOAD_FREE -> s[sp++] = PyCalls.loadFree(f, a)
            PyOp.CELL_GET -> s[sp - 1] = PyCalls.cellGet(s[sp - 1], k[a], bc[pc + 2])
            PyOp.CELL_SET -> {
                val cell = pop()
                PyCalls.cellSet(pop(), cell)
            }
            PyOp.CELL_DELETE -> PyCalls.cellDelete(pop(), k[a], bc[pc + 2])
            PyOp.SET_FUNCTION_ATTRIBUTE -> {
                val fn = pop()
                s[sp - 1] = PyCalls.setFunctionAttribute(s[sp - 1], fn, a)
            }
            PyOp.LIST_APPEND, PyOp.LIST_EXTEND, PyOp.DICT_UPDATE -> {
                val v = pop()
                s[sp - 1] = when (op) {
                    PyOp.LIST_APPEND -> PyCalls.listAppend(s[sp - 1], v)
                    PyOp.LIST_EXTEND -> PyCalls.listExtend(s[sp - 1], v)
                    else -> PyCalls.dictUpdate(s[sp - 1], v)
                }
            }
            PyOp.LIST_TO_TUPLE -> s[sp - 1] = PyCalls.listToTuple(s[sp - 1])
            PyOp.MATMUL -> {
                val b = pop()
                s[sp - 1] = PyDunder.matmul(s[sp - 1], b)
            }
            PyOp.RAISE -> throw PyExceptions.raise(f, popN(a)) as Throwable
            PyOp.RERAISE -> throw PyExceptions.reraise(pop()) as Throwable
            PyOp.PUSH_EXC_INFO -> s[sp - 1] = PyExceptions.pushExcInfo(f, s[sp - 1])
            PyOp.POP_EXCEPT -> PyExceptions.popExcept(f, pop())
            PyOp.CHECK_EXC_MATCH -> {
                val t = pop()
                s[sp - 1] = PyExceptions.checkExcMatch(s[sp - 1], t)
            }
            PyOp.LOAD_SPECIAL -> s[sp - 1] = PyExceptions.loadSpecial(s[sp - 1], a)
            PyOp.RESUME -> s[sp - 1] = PyGenerators.resumeCheck(f, s[sp - 1])
            PyOp.FORMAT_VALUE -> s[sp - 1] = PyFormat.formatValue(s[sp - 1], a)
            PyOp.BUILD_SET -> {
                val items = popN(a)
                s[sp++] = PySets.buildSet(items)
            }
            PyOp.BUILD_SLICE -> {
                val parts = popN(a)
                s[sp++] = PySets.buildSlice(parts)
            }
            PyOp.BUILD_INTERPOLATION -> {
                val p = popN(4)
                s[sp++] = PyLiterals.buildInterpolation(p[0], p[1], p[2], p[3])
            }
            PyOp.LOAD_FAST_RAW -> s[sp++] = s[a]
            PyOp.IMPORT_NAME -> {
                val fromlist = pop()
                s[sp - 1] = PyImport.importName(f, s[sp - 1], fromlist, k[a])
            }
            PyOp.IMPORT_FROM -> s[sp - 1] = PyImport.importFrom(s[sp - 1], k[a])
            PyOp.IMPORT_STAR -> PyImport.importStar(f, pop())
            PyOp.MATCH_SEQUENCE -> s[sp - 1] = PyMatch.matchSequence(s[sp - 1])
            PyOp.MATCH_MAPPING -> s[sp - 1] = PyMatch.matchMapping(s[sp - 1])
            PyOp.GET_LEN -> s[sp - 1] = PyMatch.getLen(s[sp - 1])
            PyOp.MATCH_KEYS, PyOp.COPY_DICT_WITHOUT_KEYS -> {
                val keys = pop()
                s[sp - 1] = if (op == PyOp.MATCH_KEYS) PyMatch.matchKeys(s[sp - 1], keys) else PyMatch.copyWithoutKeys(s[sp - 1], keys)
            }
            PyOp.MATCH_CLASS -> {
                val names = pop()
                val cls = pop()
                s[sp - 1] = PyMatch.matchClass(s[sp - 1], cls, names, a)
            }
            PyOp.LOAD_LOCALS -> s[sp - 1] = PyLibrary.loadLocals(s[sp - 1], k[a])
            PyOp.BUILD_TEMPLATE -> {
                val interps = pop()
                s[sp - 1] = PyLiterals.buildTemplate(s[sp - 1], interps)
            }
            PyOp.SET_ADD, PyOp.SET_UPDATE -> {
                val v = pop()
                s[sp - 1] = if (op == PyOp.SET_ADD) PySets.setAdd(s[sp - 1], v) else PySets.setUpdate(s[sp - 1], v)
            }
            PyOp.FORMAT_SPEC -> {
                val spec = pop()
                s[sp - 1] = PyFormat.formatSpec(s[sp - 1], spec, a)
            }
            PyOp.BUILD_STRING -> {
                val parts = popN(a)
                s[sp++] = PyFormat.buildString(parts)
            }
            PyOp.GET_YIELD_FROM_ITER -> s[sp - 1] = PyGenerators.yieldFromIter(s[sp - 1])
            PyOp.GET_AWAITABLE -> s[sp - 1] = PyGenerators.getAwaitable(f, s[sp - 1], a)
            PyOp.GET_AITER -> s[sp - 1] = PyGenerators.getAIter(f, s[sp - 1])
            PyOp.GET_ANEXT -> s[sp - 1] = PyGenerators.getANext(f, s[sp - 1])
            PyOp.END_ASYNC_FOR -> PyGenerators.endAsyncFor(pop())
            PyOp.ASYNC_GEN_WRAP -> s[sp - 1] = PyGenerators.asyncGenWrap(s[sp - 1])
            PyOp.ANNOTATE_CHECK -> PyAnnotations.annotateCheck(pop())
            PyOp.SETUP_ANNOTATIONS -> PyAnnotations.setupAnnotations(f)
            PyOp.LOAD_FROM_DICT_OR_GLOBALS -> s[sp - 1] = PyAnnotations.loadFromDictOrGlobals(f, s[sp - 1], k[a])
            PyOp.LOAD_FROM_DICT_OR_CELL -> {
                val cell = pop()
                s[sp - 1] = PyAnnotations.loadFromDictOrCell(s[sp - 1], cell, k[a])
            }
            PyOp.LOAD_NAMESPACE -> s[sp++] = PyAnnotations.loadNamespace(f)
            PyOp.MAKE_TYPEVAR -> s[sp - 1] = PyTyping.makeTypeVar(s[sp - 1], a)
            PyOp.MAKE_TYPEVAR_BOUND -> {
                val fn = pop()
                s[sp - 1] = PyTyping.makeTypeVarBound(s[sp - 1], fn, a)
            }
            PyOp.SET_TYPEPARAM_DEFAULT -> {
                val fn = pop()
                s[sp - 1] = PyTyping.setTypeParamDefault(s[sp - 1], fn)
            }
            PyOp.SUBSCRIPT_GENERIC -> s[sp - 1] = PyTyping.subscriptGeneric(s[sp - 1])
            PyOp.SET_EVAL_LOCALS -> PyEval.setEvalLocals(f, pop())
            PyOp.MAKE_TYPEALIAS -> {
                val fn = pop()
                val params = pop()
                s[sp - 1] = PyTyping.makeTypeAlias(f, s[sp - 1], params, fn)
            }
            PyOp.EXC_STAR_BEGIN -> s[sp - 1] = PyExceptions.excStarBegin(s[sp - 1])
            PyOp.EXC_STAR_MATCH -> {
                val t = pop()
                s[sp - 1] = PyExceptions.excStarMatch(s[sp - 1], t)
            }
            PyOp.EXC_STAR_RAISED -> {
                val exc = pop()
                PyExceptions.excStarRaised(pop(), exc)
            }
            PyOp.EXC_STAR_END -> s[sp - 1] = PyExceptions.excStarEnd(s[sp - 1])
            PyOp.WITH_EXCEPT -> {
                val exc = pop()
                s[sp - 1] = PyExceptions.withExcept(f, s[sp - 1], exc)
            }
            PyOp.WRAP_STAR -> s[sp - 1] = PyCalls.wrapStar(s[sp - 1])
            PyOp.MAP_ADD -> {
                val v = pop()
                val key = pop()
                s[sp - 1] = PyCalls.mapAdd(s[sp - 1], key, v)
            }
            PyOp.UNPACK_EX -> {
                val items = PyCalls.unpackEx(pop(), a, bc[pc + 2])
                for (i in items.indices.reversed()) s[sp++] = items[i]
            }
            PyOp.DELETE_FAST -> s[a] = null
            PyOp.DELETE_GLOBAL -> PyCalls.deleteGlobal(f, k[a])
            PyOp.DELETE_SUBSCR -> {
                val key = pop()
                PyCalls.deleteSubscr(pop(), key)
            }
            PyOp.DELETE_ATTR -> PyCalls.deleteAttr(pop(), k[a])
            PyOp.ASSERT_FAIL -> PyCalls.assertFail(popN(a))
            PyOp.LOAD_BUILD_CLASS -> s[sp++] = PyClasses.loadBuildClass(f)
            PyOp.LOAD_NAME -> s[sp++] = PyClasses.loadName(f, k[a])
            PyOp.STORE_NAME -> PyClasses.storeName(f, k[a], pop())
            PyOp.DELETE_NAME -> PyClasses.deleteName(f, k[a])
            PyOp.LOAD_FROM_DICT_OR_DEREF -> s[sp - 1] = PyClasses.loadFromDictOrDeref(f, s[sp - 1], k[a])
            PyOp.STORE_ATTR -> {
                val obj = pop()
                PyClasses.storeAttr(pop(), obj, k[a])
            }
            else -> throw IllegalStateException("bad opcode $op at $pc in ${f.code.name}")
        }
        return sp
    }

    /**
     * Every Jit.LOOP_CHECK loop iterations: the agent's interrupts and the JIT's policy (Jit.loop); at a backward jump
     * with an empty operand stack ([sp] at the registers' end), whether the loop goes on in compiled code.
     */
    private fun backEdge(f: PyFrame, sp: Int): Boolean {
        val agent = f.ctx.agent
        agent.checkInterrupt()
        val cb = f.code
        // (a generator's frame goes on in compiled code too, where the tier suspends: T2.suspendable)
        if (cb.flags and dev.mooner.neonjs.compiler.CodeUnit.GENERATOR != 0 && !dev.mooner.neonjs.jit.opt.T2.suspendable(cb)) return false
        return Jit.loop(cb, agent) && sp == cb.numRegs
    }
}
