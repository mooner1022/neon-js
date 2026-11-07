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
class PyContext(@JvmField val out: Appendable = StringBuilder(), config: VmConfig = VmConfig()) {
    @JvmField val agent = VmAgent(config)
    @JvmField val builtins: PyDict = PyBuiltins.module()

    /** Runs the module [src]; returns its globals. */
    fun exec(src: String, name: String = "<module>"): PyDict {
        val cb = PyCompiler.compileModule(src, name)
        val globals = PyDict()
        globals[PyStr("__name__")] = PyStr("__main__")
        PyInterpreter.call(this, PyFunction(cb, globals, name), NO_ARGS)
        return globals
    }

    private companion object {
        val NO_ARGS = arrayOfNulls<Any?>(0)
    }
}

/**
 * A Python activation: the core's frame (registers, then the operand stack, in its slots; its pc), with its code, the
 * context and the globals of the function's module. Registers without a value hold null (the optimizing tier's dead
 * value too), which LOAD_FAST_CHECK raises an UnboundLocalError for; an interpreted frame starts with its parameters'
 * registers holding the arguments.
 */
class PyFrame(@JvmField val code: CodeUnit, @JvmField val ctx: PyContext, @JvmField val globals: PyDict, args: Array<Any?>, compiled: CompiledCode?) :
    VmFrame(args, if (compiled != null) NO_SLOTS else slotsFor(code), code.numRegs) {
    override val unit: CodeUnit get() = code
    override val agent: VmAgent get() = ctx.agent

    init {
        if (compiled == null) for (i in args.indices) slots[i] = args[i]
    }
}

/**
 * Python's interpreter of [PyOp]'s bytecode, and its calls: a function runs its block's compiled code where the JIT's
 * policy has some (Jit.prepare: the execution mode, the call counts), interpreted otherwise; an interpreted loop goes
 * on in compiled code once there is some (Jit.loop, Jit.osr), as JavaScript's interpreter does.
 */
object PyInterpreter {
    @JvmStatic
    fun call(ctx: PyContext, fn: PyFunction, args: Array<Any?>): Any? {
        val code = fn.code
        if (args.size != fn.params) {
            val n = fn.params
            throw PyRt.typeError("${fn.name}() takes $n positional argument${if (n == 1) "" else "s"} but ${args.size} ${if (args.size == 1) "was" else "were"} given")
        }
        val c = Jit.prepare(code, ctx.agent)
        return runFrame(PyFrame(code, ctx, fn.globals, args, c), c)
    }

    /** Runs [f] (with [c], or interpreted), with the agent's call depth and interrupts kept. */
    private fun runFrame(f: PyFrame, c: CompiledCode?): Any? {
        val agent = f.ctx.agent
        if (++agent.depth > agent.maxDepth) {
            agent.depth--
            throw PyException("RecursionError", "maximum recursion depth exceeded")
        }
        if (--agent.callBudget < 0) {
            agent.callBudget = 1024
            try {
                agent.checkInterrupt()
            } catch (t: Throwable) {
                agent.depth--
                throw t
            }
        }
        try {
            return if (c == null) run(f) else c.run(f)
        } catch (e: PyException) {
            // the line of the innermost frame (compiled code stores its statement's pc where something can throw)
            if (e.line < 0) {
                e.line = (f.code.dialectData as PyCodeInfo).lineAt(f.code, f.pc)
                e.function = f.code.name
            }
            throw e
        } catch (e: StackOverflowError) {
            throw PyException("RecursionError", "maximum recursion depth exceeded")
        } finally {
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
                        if (op in PyOp.ADD..PyOp.BITXOR || op in PyOp.LT..PyOp.NE) PyProfile.record(prof, pc, PyProfile.of(s[sp - 2], s[sp - 1]))
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
                        PyOp.LT, PyOp.LE, PyOp.GT, PyOp.GE, PyOp.EQ, PyOp.NE, PyOp.IS, PyOp.IS_NOT, PyOp.IN, PyOp.NOT_IN -> {
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
                        else -> throw IllegalStateException("bad opcode ${bc[pc]} at $pc in ${cb.name}")
                    }
                }
            } catch (e: PyException) {
                f.pc = opStart
                f.sp = sp
                throw e
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
     * Every Jit.LOOP_CHECK loop iterations: the agent's interrupts and the JIT's policy (Jit.loop); at a backward jump
     * with an empty operand stack ([sp] at the registers' end), whether the loop goes on in compiled code.
     */
    private fun backEdge(f: PyFrame, sp: Int): Boolean {
        val agent = f.ctx.agent
        agent.checkInterrupt()
        val cb = f.code
        return Jit.loop(cb, agent) && sp == cb.numRegs
    }
}
