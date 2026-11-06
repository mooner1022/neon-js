package dev.mooner.neonpython

import dev.mooner.neonjs.compiler.CodeUnit
import dev.mooner.neonjs.jit.Emit
import dev.mooner.neonjs.jit.JitBailout
import dev.mooner.neonjs.jit.JvmEmitter
import dev.mooner.neonjs.jit.VmDeopt
import dev.mooner.neonjs.jit.VmRt
import dev.mooner.neonjs.jit.opt.ANY_KIND
import dev.mooner.neonjs.jit.opt.Block
import dev.mooner.neonjs.jit.opt.Const
import dev.mooner.neonjs.jit.opt.Dialect
import dev.mooner.neonjs.jit.opt.FactRules
import dev.mooner.neonjs.jit.opt.FrameState
import dev.mooner.neonjs.jit.opt.Graph
import dev.mooner.neonjs.jit.opt.GraphBuilder
import dev.mooner.neonjs.jit.opt.InlineSite
import dev.mooner.neonjs.jit.opt.Inst
import dev.mooner.neonjs.jit.opt.Ir
import dev.mooner.neonjs.jit.opt.IrCodegen
import dev.mooner.neonjs.jit.opt.KindSystem
import dev.mooner.neonjs.jit.opt.Kinds
import dev.mooner.neonjs.jit.opt.OpInfo
import dev.mooner.neonjs.jit.opt.OpTable
import dev.mooner.neonjs.jit.opt.Phi
import dev.mooner.neonjs.jit.opt.Rep
import dev.mooner.neonjs.jit.opt.Resumer
import dev.mooner.neonjs.jit.opt.Value
import dev.mooner.neonjs.vm.VmFrame
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*

/**
 * Python's dialect of the optimizing tier (docs/IR_NEUTRALITY.md, docs/PYTHON_PLATFORM.md §10): its bytecode
 * ([PyOp]) becomes the core's graph ([PyBuilder]), whose passes and code generation are the core's; each instruction's
 * code calls the runtime ([PyRt]), as the interpreter does, but comparisons, which branches test as JVM ints (the kind
 * [PyKinds.BOOL]), and the loop's control flow, which is the JVM's.
 *
 * Its blocks get the tier's baselines (no speculation: T2.compileBaselines), then, with the tier on (VmConfig.tier2),
 * its speculating code: arithmetic and comparisons of the ints (Longs) and floats the interpreter saw there
 * ([PyCodeInfo.profile]), unboxed, exiting to the interpreter where a value is another or an int leaves 64 bits.
 */
object PyDialect : Dialect {
    // (the deopt stress mode's exits, stressExits, are not made: NeonPy's exits are tested by real ones)
    override fun build(cb: CodeUnit, stressExits: Boolean, unswitch: Boolean, baseline: Boolean): Graph =
        PyBuilder(cb, speculate = !baseline).build()

    /** Exits of speculating code taken (the resumer's). */
    @JvmField val exits = java.util.concurrent.atomic.AtomicLong()

    /** An exit's state into the frame's slots, and the interpreter on from its pc. */
    override val resumer: Resumer = Resumer { f, pc, values, _ ->
        exits.incrementAndGet()
        val entered = !f.isCompiled
        VmDeopt.write(f, values)
        f.pc = pc
        if (entered) VmRt.DEOPTED else PyInterpreter.run(f as PyFrame)
    }

    override fun interpret(f: VmFrame): Any? = PyInterpreter.run(f as PyFrame)

    override fun identityExtras(g: Graph, out: java.io.DataOutputStream) {
        val t = backEdgeTargets(g.code.code)
        out.writeInt(t.size)
        for (pc in t) out.writeInt(pc)
    }

    override fun facts(g: Graph, kinds: Kinds): FactRules? = null

    override fun codegen(g: Graph, mv: MethodVisitor, kinds: Kinds, cw: ClassWriter, className: String): IrCodegen =
        PyCodegen(g, mv, kinds, cw, className)

    /** The targets of backward jumps (loop headers: where the interpreter may continue a loop in compiled code). */
    fun backEdgeTargets(bc: IntArray): IntArray {
        val out = sortedSetOf<Int>()
        var pc = 0
        while (pc < bc.size) {
            val op = bc[pc]
            if (op == PyOp.JUMP && bc[pc + 1] <= pc) out.add(bc[pc + 1])
            pc += PyOp.length(op)
        }
        return out.toIntArray()
    }
}

/** The graph's operations that are no bytecode's (from 500: below the core's 1000, well past [PyOp]'s, which grow). */
object PyIr {
    /** a: the argument a function's parameter a is (its register at the start). */
    const val ARG = 500
    /** (v) v, or an UnboundLocalError where it is the dead value (register a's: LOAD_FAST_CHECK). */
    const val CHECK_BOUND = 501
    /** (seq) its a items in an Object[] (UNPACK), then ITEM a of them. */
    const val UNPACK_ARRAY = 502
    const val ITEM = 503

    // the speculating tier's (each exits where its state, the instruction's operands on the stack, says)
    /** (v) v as an int of 64 bits (a Long), or an exit. */
    const val CHECK_INT = 504
    /** (v) v as a float (a Double), or an exit. */
    const val CHECK_FLOAT = 505
    /** (a b) or (a): bytecode operation a (ADD, SUB, MUL, FLOORDIV, MOD, BITAND, BITOR, BITXOR, NEG) of ints; an exit where the result leaves 64 bits or a division by zero would raise. */
    const val INT_OP = 506
    /** (a b) or (a): operation a (ADD, SUB, MUL, NEG) of floats. */
    const val FLOAT_OP = 507
    /** (a b): comparison a (LT..NE) of ints, of floats. */
    const val INT_CMP = 508
    const val FLOAT_CMP = 509
    /** (seq) UNPACK_EX's a + 1 + b items in an Object[] (a before the starred target, b after), then ITEM of them. */
    const val UNPACK_EX_ARRAY = 510
}

/**
 * Python's kinds: any value (an Object); a bool the code holds as a JVM int (a comparison's result, which a branch tests
 * as it is and which becomes a PyBool where something takes an Object); and, in speculating code, an int of 64 bits
 * held as a `long` ([INT]: a Python int is a Long there, and a BigInteger only where it must be, which the code exits
 * for) and a float held as a `double` ([FLOAT]).
 */
object PyKinds : KindSystem {
    const val BOOL: Byte = 1
    const val INT: Byte = 2
    const val FLOAT: Byte = 3

    override fun rep(kind: Byte): Rep = when (kind) {
        BOOL -> Rep.INT
        INT -> Rep.LONG
        FLOAT -> Rep.DOUBLE
        else -> Rep.OBJECT
    }
    override fun join(a: Byte, b: Byte): Byte = if (a == b) a else ANY_KIND
    override fun isNumeric(kind: Byte): Boolean = kind == INT || kind == FLOAT
    override fun constKind(value: Any?): Byte = when (value) {
        is PyBool -> BOOL
        is Long -> INT
        is Double -> FLOAT
        else -> ANY_KIND
    }

    override fun resultKind(i: Inst, kinds: Kinds): Byte = when (i.op) {
        in PyOp.LT..PyOp.NOT_IN, PyOp.NOT, PyIr.INT_CMP, PyIr.FLOAT_CMP -> BOOL
        PyIr.CHECK_INT, PyIr.INT_OP -> INT
        PyIr.CHECK_FLOAT, PyIr.FLOAT_OP -> FLOAT
        else -> ANY_KIND
    }

    override fun initial(em: Emit, kind: Byte) = rep(kind).zero(em.mv)

    override fun convert(em: Emit, from: Byte, to: Byte) {
        when {
            from == to -> {}
            from == BOOL && to == ANY_KIND -> em.mv.visitMethodInsn(INVOKESTATIC, PY_BOOL, "of", "(Z)L$PY_BOOL;", false)
            from == INT && to == ANY_KIND -> em.mv.visitMethodInsn(INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false)
            from == FLOAT && to == ANY_KIND -> em.mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false)
            else -> throw JitBailout("python: kind $from as $to")
        }
    }

    // (a register of kind BOOL at a loop header holds a PyBool in the interpreter too: the same comparisons made it)
    override fun osrCode(kind: Byte): Char = when (kind) {
        INT -> 'L'
        FLOAT -> 'F'
        else -> '.'
    }
    override fun osrRead(em: Emit, kind: Byte) {
        when (kind) {
            INT -> em.mv.visitMethodInsn(INVOKESTATIC, PY_RT, "slotLong", "([Ljava/lang/Object;I)J", false)
            FLOAT -> em.mv.visitMethodInsn(INVOKESTATIC, PY_RT, "slotDouble", "([Ljava/lang/Object;I)D", false)
            else -> {
                em.mv.visitInsn(AALOAD)
                if (kind == BOOL) em.mv.visitMethodInsn(INVOKESTATIC, PY_RT, "slotBool", "(Ljava/lang/Object;)Z", false)
            }
        }
    }
    override fun osrCheck(em: Emit, kinds: String) {
        em.mv.visitLdcInsn(kinds)
        em.mv.visitMethodInsn(INVOKESTATIC, PY_RT, "osrKinds", "([Ljava/lang/Object;Ljava/lang/String;)Z", false)
    }

    override fun name(kind: Byte): String = when (kind) {
        BOOL -> " :bool"
        INT -> " :int"
        FLOAT -> " :float"
        else -> ""
    }
}

/** What the passes know of Python's operations: most may run code and throw; a few only compute or allocate. */
object PyOps : OpTable {
    private val infos = arrayOfNulls<OpInfo>(PyIr.UNPACK_EX_ARRAY + 1).also { t ->
        for (op in 0 until PyOp.COUNT) if (PyOp.names[op] != null) t[op] = OpInfo(PyOp.name(op), PyGeneric.of(op)?.flags ?: OpInfo.ANYTHING)
        t[PyOp.IS] = OpInfo("IS", OpInfo.REMOVABLE)
        t[PyOp.IS_NOT] = OpInfo("IS_NOT", OpInfo.REMOVABLE)
        t[PyOp.BUILD_LIST] = OpInfo("BUILD_LIST", OpInfo.REMOVABLE or OpInfo.ALLOCATES)
        t[PyOp.BUILD_TUPLE] = OpInfo("BUILD_TUPLE", OpInfo.REMOVABLE or OpInfo.ALLOCATES)
        t[PyIr.ARG] = OpInfo("Arg", OpInfo.REMOVABLE)
        t[PyIr.CHECK_BOUND] = OpInfo("CheckBound", OpInfo.THROWS)
        t[PyIr.UNPACK_ARRAY] = OpInfo("UnpackArray", OpInfo.ANYTHING)
        t[PyIr.ITEM] = OpInfo("Item", OpInfo.REMOVABLE)
        t[PyIr.UNPACK_EX_ARRAY] = OpInfo("UnpackExArray", OpInfo.ANYTHING)
        // a check's result is its input unboxed; arithmetic of numbers only computes (an exit goes with it)
        t[PyIr.CHECK_INT] = OpInfo("CheckInt", OpInfo.REMOVABLE or OpInfo.UNBOXES, OpInfo.HOIST_ALWAYS)
        t[PyIr.CHECK_FLOAT] = OpInfo("CheckFloat", OpInfo.REMOVABLE or OpInfo.UNBOXES, OpInfo.HOIST_ALWAYS)
        t[PyIr.INT_OP] = object : OpInfo("Int", OpInfo.REMOVABLE) {
            // (an operation that cannot exit moves out of a loop with its inputs: & | ^)
            override fun hoistOf(i: Inst): Int =
                if (i.a == PyOp.BITAND || i.a == PyOp.BITOR || i.a == PyOp.BITXOR) OpInfo.HOIST_NUMERIC_NO_EXIT else OpInfo.HOIST_NEVER
        }
        t[PyIr.FLOAT_OP] = OpInfo("Float", OpInfo.REMOVABLE, OpInfo.HOIST_NUMERIC_NO_EXIT)
        t[PyIr.INT_CMP] = OpInfo("IntCmp", OpInfo.REMOVABLE, OpInfo.HOIST_NUMERIC_NO_EXIT)
        t[PyIr.FLOAT_CMP] = OpInfo("FloatCmp", OpInfo.REMOVABLE, OpInfo.HOIST_NUMERIC_NO_EXIT)
    }
    private val control = OpInfo("Control", 0)
    private val ret = OpInfo("Return", OpInfo.OBJECT_OPERANDS)

    override fun info(op: Int): OpInfo = when (op) {
        Ir.GOTO, Ir.BRANCH -> control
        Ir.RETURN -> ret
        else -> infos.getOrNull(op) ?: OpInfo(Ir.name(op), OpInfo.ANYTHING)
    }

    override fun operandText(i: Inst): String = when (i.op) {
        PyOp.LOAD_GLOBAL, PyOp.STORE_GLOBAL, PyOp.LOAD_ATTR -> " k${i.a}"
        PyIr.ARG, PyIr.ITEM, PyOp.CALL, PyOp.BUILD_LIST, PyOp.BUILD_TUPLE, PyOp.BUILD_MAP, PyIr.UNPACK_ARRAY -> " ${i.a}"
        PyIr.INT_OP, PyIr.FLOAT_OP, PyIr.INT_CMP, PyIr.FLOAT_CMP -> " " + PyOp.name(i.a)
        else -> ""
    }
}

/**
 * The graph of a Python code block: its registers from its arguments, each instruction built as its bytecode's; with
 * [speculate], arithmetic and comparisons the interpreter only saw ints (Longs) or only floats at
 * ([PyCodeInfo.profile]), and that never exited before ([CodeUnit.t2FailedAt]), on those unboxed.
 */
class PyBuilder(cb: CodeUnit, speculate: Boolean = false) :
    GraphBuilder(cb, Graph(cb, PyOps, PyKinds, null, PyDialect), stressExits = false, speculate = speculate) {
    override fun unbuildable(): String? = null
    override fun root(): Body = PyBody(cb)

    override fun entry(start: Block): Array<Value> = Array(cb.numRegs) { r ->
        if (r < cb.formalCount) add(start, Inst(PyIr.ARG, NO_VALUES, r, 0, 0, -1, -1)).also { it.hasResult = true } else dead()
    }

    private inner class PyBody(code: CodeUnit) : Body(code, null) {
        override fun length(pc: Int): Int = PyOp.length(bc[pc])
        override fun operand(pc: Int, k: Int): Int = if (k <= PyOp.operands[bc[pc]]) bc[pc + k] else 0

        override fun targets(pc: Int): IntArray? {
            val next = pc + length(pc)
            return when (bc[pc]) {
                PyOp.JUMP -> intArrayOf(bc[pc + 1])
                PyOp.POP_JUMP_IF_FALSE, PyOp.POP_JUMP_IF_TRUE -> if (bc[pc + 1] == next) intArrayOf(next) else intArrayOf(bc[pc + 1], next)
                PyOp.FOR_ITER -> intArrayOf(bc[pc + 3], next)
                PyOp.RETURN -> IntArray(0)
                else -> null
            }
        }

        override fun supported(pc: Int): Boolean = bc[pc] in 0 until PyOp.COUNT && PyOp.names[bc[pc]] != null

        override fun regsRead(pc: Int): IntArray = when (bc[pc]) {
            PyOp.LOAD_FAST, PyOp.LOAD_FAST_CHECK, PyOp.FOR_ITER -> intArrayOf(bc[pc + 1])
            else -> IntArray(0)
        }

        override fun regWritten(pc: Int): Int = when (bc[pc]) {
            PyOp.STORE_FAST, PyOp.DELETE_FAST -> bc[pc + 1]
            PyOp.FOR_ITER -> bc[pc + 2]
            else -> -1
        }

        override fun nameAt(pc: Int): String = PyOp.name(bc[pc])

        /**
         * Constant [k]: None, True and False as their values (the code has them as such: GraphIdentity tells them apart);
         * every other by its index, which the code reads from the block's constants (blocks of one graph share a
         * class, each with its own constants).
         */
        /** What the interpreter saw of the operands of the instruction at [pc] ([PyProfile]); 0 for nothing. */
        private fun profiled(pc: Int): Int {
            val p = (code.dialectData as PyCodeInfo?)?.profile ?: return 0
            return if (pc < p.size) p[pc].toInt() and 0xFF else 0
        }

        /** [v] as an int (INT) or a float (FLOAT): itself where it is one, else checked, exiting at [state] otherwise. */
        private fun At.number(v: Value, int: Boolean, state: dev.mooner.neonjs.jit.opt.FrameState): Value {
            if (v is Const) {
                if (int && v.value is Long || !int && v.value is Double) return v
            } else if (v is Inst) {
                val kindOp = if (int) v.op == PyIr.CHECK_INT || v.op == PyIr.INT_OP else v.op == PyIr.CHECK_FLOAT || v.op == PyIr.FLOAT_OP
                if (kindOp) return v
            }
            return add(cur, Inst(if (int) PyIr.CHECK_INT else PyIr.CHECK_FLOAT, arrayOf(v), 0, 0, 0, pc, stmt)).also {
                it.hasResult = true
                it.state = state
            }
        }

        /**
         * The arithmetic or comparison at [At.pc] on unboxed ints or floats, where the interpreter only saw those there:
         * the operands checked, the operation exiting where its result leaves 64 bits or it would raise (the interpreter
         * then does it, a BigInteger or the exception). False where it is not speculated on.
         */
        private fun At.speculated(): Boolean {
            if (!speculate || code.t2FailedAt(pc)) return false
            val bits = profiled(pc)
            val int = bits == PyProfile.INT
            if (!int && bits != PyProfile.FLOAT) return false
            // (augmented assignment of numbers is the binary operator's)
            val o = if (op == PyOp.INPLACE) a else op
            val kind = when (o) {
                PyOp.ADD, PyOp.SUB, PyOp.MUL, PyOp.NEG -> if (int) PyIr.INT_OP else PyIr.FLOAT_OP
                PyOp.FLOORDIV, PyOp.MOD, PyOp.BITAND, PyOp.BITOR, PyOp.BITXOR -> if (int) PyIr.INT_OP else return false
                in PyOp.LT..PyOp.NE -> if (int) PyIr.INT_CMP else PyIr.FLOAT_CMP
                else -> return false
            }
            // (the state before the instruction, its operands on the stack: where the interpreter does it again)
            val state = state(pc)
            val inputs = if (o == PyOp.NEG) arrayOf(number(pop(), int, state)) else {
                val r = pop()
                val l = pop()
                arrayOf(number(l, int, state), number(r, int, state))
            }
            val i = add(cur, Inst(kind, inputs, o, 0, 0, pc, stmt))
            i.hasResult = true
            // (an int's arithmetic may leave 64 bits, a division divide by zero: exits; the rest cannot)
            if (kind == PyIr.INT_OP && o != PyOp.BITAND && o != PyOp.BITOR && o != PyOp.BITXOR) i.state = state
            stack.add(i)
            return true
        }

        private fun constant(k: Int): Const {
            val v = code.constants[k]
            return if (v is PyBool || v === PyNone) Const(v) else Const(v, k, code)
        }

        override fun buildOp(at: At): Boolean = with(at) {
            if (speculated()) return false
            // (a runtime call: its row in PyGeneric)
            PyGeneric.of(op)?.let { gen ->
                inst(op, popN(gen.popped(a)), result = gen.result)
                return false
            }
            when (op) {
                PyOp.NOP -> {}
                PyOp.LOAD_CONST -> stack.add(constant(a))
                PyOp.LOAD_FAST -> stack.add(regs[a]!!)
                PyOp.LOAD_FAST_CHECK -> {
                    val v = regs[a]!!
                    // only the dead value is none, and a phi may be it (an instruction's result never is)
                    if (v is Phi || v is Const && v.value == null) inst(PyIr.CHECK_BOUND, arrayOf(v), result = true)
                    else stack.add(v)
                }
                PyOp.STORE_FAST -> {
                    regs[a] = pop()
                    home(a)
                }
                PyOp.DELETE_FAST -> {
                    regs[a] = dead()
                    home(a)
                }
                PyOp.UNPACK_EX -> {
                    val arr = inst(PyIr.UNPACK_EX_ARRAY, arrayOf(pop()), result = true)
                    pop()
                    // the first item on top
                    for (k in a + bb downTo 0) stack.add(add(cur, Inst(PyIr.ITEM, arrayOf(arr), k, 0, 0, pc, stmt)).also { it.hasResult = true })
                }
                PyOp.POP -> pop()
                PyOp.DUP -> {
                    val v = pop()
                    stack.add(v)
                    stack.add(v)
                }
                PyOp.SWAP -> permute(2, intArrayOf(1, 0))
                in PyOp.ADD..PyOp.BITXOR, in PyOp.LT..PyOp.NOT_IN, PyOp.SUBSCR -> {
                    val r = pop()
                    val l = pop()
                    inst(op, arrayOf(l, r), result = true)
                }
                PyOp.NEG, PyOp.POS, PyOp.NOT, PyOp.INVERT, PyOp.GET_ITER -> inst(op, arrayOf(pop()), result = true)
                PyOp.CALL -> {
                    val inputs = popN(a + 1)
                    inst(op, inputs, result = true)
                }
                PyOp.BUILD_LIST, PyOp.BUILD_TUPLE -> inst(op, popN(a), result = true)
                PyOp.BUILD_MAP -> inst(op, popN(2 * a), result = true)
                PyOp.STORE_SUBSCR -> inst(op, popN(3), result = false) // (v obj key)
                PyOp.UNPACK -> {
                    val arr = inst(PyIr.UNPACK_ARRAY, arrayOf(pop()), result = true)
                    pop()
                    // the first item on top
                    for (k in a - 1 downTo 0) stack.add(add(cur, Inst(PyIr.ITEM, arrayOf(arr), k, 0, 0, pc, stmt)).also { it.hasResult = true })
                }
                PyOp.JUMP -> {
                    end()
                    goto(cur, blockAt[a]!!)
                    return true
                }
                PyOp.POP_JUMP_IF_FALSE, PyOp.POP_JUMP_IF_TRUE -> {
                    val v = pop()
                    val next = pc + len
                    // (a test that goes on either way still asks for the value's truth: __bool__ may run, or raise)
                    if (a == next && v !is Const) {
                        inst(PyOp.NOT, arrayOf(v), result = true)
                        pop()
                    }
                    end()
                    if (a == next) goto(cur, blockAt[next]!!)
                    else add(cur, Inst(Ir.BRANCH, arrayOf(v), op, 0, 0, pc, stmt)).targets = listOf(blockAt[a]!!, blockAt[next]!!)
                    return true
                }
                PyOp.FOR_ITER -> {
                    // register bb gets the next value; to c where there is none (the register's value then is dead)
                    val v = add(cur, Inst(PyOp.FOR_ITER, arrayOf(regs[a]!!), a, bb, c, pc, stmt)).also { it.hasResult = true }
                    regs[bb] = v
                    home(bb)
                    end()
                    add(cur, Inst(Ir.BRANCH, arrayOf(v), PyOp.FOR_ITER, 0, 0, pc, stmt)).targets = listOf(blockAt[c]!!, blockAt[pc + len]!!)
                    return true
                }
                PyOp.RETURN -> {
                    add(cur, Inst(Ir.RETURN, arrayOf(pop()), 0, 0, 0, pc, stmt))
                    end()
                    return true
                }
                else -> throw JitBailout("python: no graph for ${PyOp.name(op)}")
            }
            false
        }

        /** The top [n] values of the stack, the deepest first, popped. */
        private fun At.popN(n: Int): Array<Value> {
            val out = arrayOfNulls<Value>(n)
            for (k in n - 1 downTo 0) out[k] = pop()
            @Suppress("UNCHECKED_CAST")
            return out as Array<Value>
        }
    }
}

private const val PY_RT = "dev/mooner/neonpython/PyRt"
private const val PY_BOOL = "dev/mooner/neonpython/PyBool"
private const val FRAME = "dev/mooner/neonjs/vm/VmFrame"
private const val FRAME_D = "Ldev/mooner/neonjs/vm/VmFrame;"
private const val OBJ = "Ljava/lang/Object;"
private const val OBJS = "[Ljava/lang/Object;"

/** The code of Python's instructions: calls of [PyRt] with the values as Objects, comparisons as JVM ints. */
class PyCodegen(g: Graph, mv: MethodVisitor, kinds: Kinds, cw: ClassWriter, className: String) : IrCodegen(g, mv, kinds, cw, className) {
    /** A call of PyRt's [name]. */
    private fun py(name: String, desc: String) = mv.visitMethodInsn(INVOKESTATIC, PY_RT, name, desc, false)

    /** Pushes constant [k] of the code block compiled. */
    private fun constant(k: Int) {
        mv.visitVarInsn(ALOAD, constsLocal[g.code]!!)
        iconst(k)
        mv.visitInsn(AALOAD)
    }

    private fun objects(values: Array<Value>, from: Int = 0) {
        iconst(values.size - from)
        mv.visitTypeInsn(ANEWARRAY, "java/lang/Object")
        for (j in from until values.size) {
            mv.visitInsn(DUP)
            iconst(j - from)
            load(values[j], ANY_KIND)
            mv.visitInsn(AASTORE)
        }
    }

    /** A runtime call ([PyGeneric]): its arguments as its row says, its result an Object. */
    private fun generic(i: Inst, gen: Generic) {
        if (gen.throws) storePc(i)
        var next = 0
        for (c in gen.args) when (c) {
            'f' -> frame()
            'k' -> constant(i.a)
            'a' -> iconst(i.a)
            'b' -> iconst(i.b)
            'v' -> load(i.inputs[next++], ANY_KIND)
            'n' -> {
                objects(i.inputs, from = next)
                next = i.inputs.size
            }
        }
        mv.visitMethodInsn(INVOKESTATIC, gen.owner, gen.method, gen.descriptor, false)
        if (gen.result) done(i, ANY_KIND)
    }

    override fun inst(i: Inst) {
        val op = i.op
        PyGeneric.of(op)?.let {
            generic(i, it)
            return
        }
        when (op) {
            PyIr.ARG -> {
                frame()
                mv.visitFieldInsn(GETFIELD, FRAME, "args", OBJS)
                iconst(i.a)
                mv.visitInsn(AALOAD)
                done(i, ANY_KIND)
            }
            PyIr.CHECK_BOUND -> {
                storePc(i)
                load(i.inputs[0], ANY_KIND)
                iconst(i.a)
                frame()
                py("checkBound", "(${OBJ}I$FRAME_D)$OBJ")
                done(i, ANY_KIND)
            }
            in PyOp.ADD..PyOp.BITXOR, PyOp.SUBSCR -> {
                storePc(i)
                load(i.inputs[0], ANY_KIND)
                load(i.inputs[1], ANY_KIND)
                py(BINARY[op]!!, "($OBJ$OBJ)$OBJ")
                done(i, ANY_KIND)
            }
            in PyOp.LT..PyOp.NOT_IN -> {
                if (op == PyOp.IS || op == PyOp.IS_NOT) {
                    load(i.inputs[0], ANY_KIND)
                    load(i.inputs[1], ANY_KIND)
                    py("same", "($OBJ$OBJ)Z")
                    if (op == PyOp.IS_NOT) {
                        iconst(1)
                        mv.visitInsn(IXOR)
                    }
                } else {
                    storePc(i)
                    load(i.inputs[0], ANY_KIND)
                    load(i.inputs[1], ANY_KIND)
                    py(COMPARE[op]!!, "($OBJ$OBJ)Z")
                }
                done(i, PyKinds.BOOL)
            }
            PyOp.NOT -> {
                val v = i.inputs[0]
                if (kindOf(v) == PyKinds.BOOL) load(v, PyKinds.BOOL)
                else {
                    load(v, ANY_KIND)
                    py("truthy", "($OBJ)Z")
                }
                iconst(1)
                mv.visitInsn(IXOR)
                done(i, PyKinds.BOOL)
            }
            PyOp.NEG, PyOp.POS, PyOp.INVERT, PyOp.GET_ITER, PyOp.FOR_ITER -> {
                storePc(i)
                load(i.inputs[0], ANY_KIND)
                py(UNARY[op]!!, "($OBJ)$OBJ")
                done(i, ANY_KIND)
            }
            PyOp.CALL -> {
                storePc(i)
                frame()
                load(i.inputs[0], ANY_KIND)
                objects(i.inputs, from = 1)
                py("call", "($FRAME_D$OBJ$OBJS)$OBJ")
                done(i, ANY_KIND)
            }
            PyOp.BUILD_LIST, PyOp.BUILD_TUPLE, PyOp.BUILD_MAP -> {
                storePc(i)
                objects(i.inputs)
                py(when (op) { PyOp.BUILD_LIST -> "buildList"; PyOp.BUILD_TUPLE -> "buildTuple"; else -> "buildMap" }, "($OBJS)$OBJ")
                done(i, ANY_KIND)
            }
            PyOp.STORE_SUBSCR -> {
                storePc(i)
                // (v obj key): setItem(obj, key, v)
                load(i.inputs[1], ANY_KIND)
                load(i.inputs[2], ANY_KIND)
                load(i.inputs[0], ANY_KIND)
                py("setItem", "($OBJ$OBJ$OBJ)V")
            }
            PyIr.UNPACK_ARRAY -> {
                storePc(i)
                load(i.inputs[0], ANY_KIND)
                iconst(i.a)
                py("unpack", "(${OBJ}I)$OBJS")
                done(i, ANY_KIND)
            }
            PyIr.ITEM -> {
                load(i.inputs[0], ANY_KIND)
                iconst(i.a)
                py("item", "(${OBJ}I)$OBJ")
                done(i, ANY_KIND)
            }
            PyIr.UNPACK_EX_ARRAY -> {
                storePc(i)
                load(i.inputs[0], ANY_KIND)
                iconst(i.a)
                iconst(i.b)
                mv.visitMethodInsn(INVOKESTATIC, Generic.CALLS, "unpackEx", "(${OBJ}II)$OBJS", false)
                done(i, ANY_KIND)
            }
            PyIr.CHECK_INT, PyIr.CHECK_FLOAT -> {
                val int = op == PyIr.CHECK_INT
                val want = if (int) PyKinds.INT else PyKinds.FLOAT
                val v = i.inputs[0]
                // (a value of the kind already: nothing to check)
                if (kindOf(v) == want) load(v, want)
                else {
                    val box = if (int) "java/lang/Long" else "java/lang/Double"
                    val exit = Label()
                    val ok = Label()
                    load(v, ANY_KIND)
                    mv.visitInsn(DUP)
                    mv.visitTypeInsn(INSTANCEOF, box)
                    mv.visitJumpInsn(IFNE, ok)
                    mv.visitInsn(POP)
                    mv.visitJumpInsn(GOTO, exit)
                    mv.visitLabel(ok)
                    mv.visitTypeInsn(CHECKCAST, box)
                    mv.visitMethodInsn(INVOKEVIRTUAL, box, if (int) "longValue" else "doubleValue", if (int) "()J" else "()D", false)
                    exits.add(specExit(exit, i))
                }
                done(i, want)
            }
            PyIr.INT_OP -> intOp(i)
            PyIr.FLOAT_OP -> {
                load(i.inputs[0], PyKinds.FLOAT)
                if (i.a == PyOp.NEG) mv.visitInsn(DNEG)
                else {
                    load(i.inputs[1], PyKinds.FLOAT)
                    mv.visitInsn(when (i.a) { PyOp.ADD -> DADD; PyOp.SUB -> DSUB; else -> DMUL })
                }
                done(i, PyKinds.FLOAT)
            }
            PyIr.INT_CMP, PyIr.FLOAT_CMP -> {
                val int = op == PyIr.INT_CMP
                val k = if (int) PyKinds.INT else PyKinds.FLOAT
                load(i.inputs[0], k)
                load(i.inputs[1], k)
                // (a NaN compares false but for !=: DCMPG where it must not pass a < or <=, DCMPL a > or >=)
                mv.visitInsn(if (int) LCMP else if (i.a == PyOp.LT || i.a == PyOp.LE) DCMPG else DCMPL)
                val t = Label()
                val end = Label()
                mv.visitJumpInsn(when (i.a) {
                    PyOp.LT -> IFLT; PyOp.LE -> IFLE; PyOp.GT -> IFGT; PyOp.GE -> IFGE; PyOp.EQ -> IFEQ; else -> IFNE
                }, t)
                iconst(0)
                mv.visitJumpInsn(GOTO, end)
                mv.visitLabel(t)
                iconst(1)
                mv.visitLabel(end)
                done(i, PyKinds.BOOL)
            }
            else -> throw JitBailout("python: no code for ${g.ops.info(op).name}")
        }
    }

    /**
     * An int operation of 64 bits ([PyIr.INT_OP]): exits where Python's result would leave 64 bits (a sum, a difference,
     * a product, a negation) or the operation would raise (a division by zero), and where `//` of the least int by -1
     * overflows; `//` and `%` round toward negative infinity, as Python's.
     */
    private fun intOp(i: Inst) {
        val exit = Label()
        val a = tempD()
        load(i.inputs[0], PyKinds.INT)
        mv.visitVarInsn(LSTORE, a)
        if (i.a == PyOp.NEG) {
            mv.visitVarInsn(LLOAD, a)
            mv.visitLdcInsn(Long.MIN_VALUE)
            mv.visitInsn(LCMP)
            mv.visitJumpInsn(IFEQ, exit)
            mv.visitVarInsn(LLOAD, a)
            mv.visitInsn(LNEG)
            exits.add(specExit(exit, i))
            done(i, PyKinds.INT)
            return
        }
        val b = tempD()
        load(i.inputs[1], PyKinds.INT)
        mv.visitVarInsn(LSTORE, b)
        when (i.a) {
            PyOp.ADD, PyOp.SUB -> {
                val r = tempD()
                mv.visitVarInsn(LLOAD, a)
                mv.visitVarInsn(LLOAD, b)
                mv.visitInsn(if (i.a == PyOp.ADD) LADD else LSUB)
                mv.visitVarInsn(LSTORE, r)
                // overflow: a sum's sign differs from both operands' ((a ^ r) & (b ^ r) < 0), a difference's from a's
                // where the operands' signs differ ((a ^ b) & (a ^ r) < 0)
                mv.visitVarInsn(LLOAD, a)
                mv.visitVarInsn(LLOAD, if (i.a == PyOp.ADD) r else b)
                mv.visitInsn(LXOR)
                mv.visitVarInsn(LLOAD, if (i.a == PyOp.ADD) b else a)
                mv.visitVarInsn(LLOAD, r)
                mv.visitInsn(LXOR)
                mv.visitInsn(LAND)
                mv.visitInsn(LCONST_0)
                mv.visitInsn(LCMP)
                mv.visitJumpInsn(IFLT, exit)
                mv.visitVarInsn(LLOAD, r)
            }
            PyOp.MUL -> {
                mv.visitVarInsn(LLOAD, a)
                mv.visitVarInsn(LLOAD, b)
                py("mulOverflows", "(JJ)Z")
                mv.visitJumpInsn(IFNE, exit)
                mv.visitVarInsn(LLOAD, a)
                mv.visitVarInsn(LLOAD, b)
                mv.visitInsn(LMUL)
            }
            PyOp.FLOORDIV, PyOp.MOD -> {
                mv.visitVarInsn(LLOAD, a)
                mv.visitVarInsn(LLOAD, b)
                py("divisible", "(JJ)Z")
                mv.visitJumpInsn(IFEQ, exit)
                mv.visitVarInsn(LLOAD, a)
                mv.visitVarInsn(LLOAD, b)
                mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", if (i.a == PyOp.FLOORDIV) "floorDiv" else "floorMod", "(JJ)J", false)
            }
            else -> {
                mv.visitVarInsn(LLOAD, a)
                mv.visitVarInsn(LLOAD, b)
                mv.visitInsn(when (i.a) { PyOp.BITAND -> LAND; PyOp.BITOR -> LOR; else -> LXOR })
            }
        }
        if (i.state != null) exits.add(specExit(exit, i)) else mv.visitLabel(exit)
        done(i, PyKinds.INT)
    }

    override fun branch(i: Inst): Int {
        val v = i.inputs[0]
        return when (i.a) {
            PyOp.FOR_ITER -> {
                load(v, ANY_KIND)
                getstatic("dev/mooner/neonpython/PyIterator", "STOP", OBJ)
                IF_ACMPEQ // done: to the loop's exit
            }
            else -> {
                if (kindOf(v) == PyKinds.BOOL) load(v, PyKinds.BOOL)
                else {
                    load(v, ANY_KIND)
                    py("truthy", "($OBJ)Z")
                }
                if (i.a == PyOp.POP_JUMP_IF_FALSE) IFEQ else IFNE
            }
        }
    }

    override fun switchIndex(i: Inst) = throw JitBailout("python: no switch")
    override fun otherTerminator(i: Inst): Boolean = false

    override fun pushConst(c: Const, want: Byte) {
        val v = c.value
        // (a constant of the block's, by its index, is read from its constants: the class may be another block's too,
        // its constants others at that index)
        if (c.k >= 0) {
            mv.visitVarInsn(ALOAD, constsLocal[c.code ?: g.code] ?: constsLocal[g.code]!!)
            iconst(c.k)
            mv.visitInsn(AALOAD)
            when (want) {
                PyKinds.BOOL -> py("slotBool", "($OBJ)Z")
                PyKinds.INT -> {
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Long")
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false)
                }
                PyKinds.FLOAT -> {
                    mv.visitTypeInsn(CHECKCAST, "java/lang/Double")
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false)
                }
            }
            return
        }
        when (want) {
            PyKinds.BOOL -> {
                if (v !is PyBool) throw JitBailout("python: constant $v as a bool")
                iconst(if (v.value) 1 else 0)
                return
            }
            PyKinds.INT -> { mv.visitLdcInsn(v as Long); return }
            PyKinds.FLOAT -> { mv.visitLdcInsn(v as Double); return }
        }
        when {
            v === PyNone -> getstatic("dev/mooner/neonpython/PyNone", "INSTANCE", "Ldev/mooner/neonpython/PyNone;")
            v is PyBool -> getstatic(PY_BOOL, if (v.value) "TRUE" else "FALSE", "L$PY_BOOL;")
            v == null -> mv.visitInsn(ACONST_NULL)
            else -> throw JitBailout("python: constant $v")
        }
    }

    override fun constObject(em: Emit, c: Const) {
        val v = c.value
        when {
            c.k >= 0 && (c.code == null || c.code === g.code) -> {
                em.frame()
                em.mv.visitMethodInsn(INVOKEVIRTUAL, FRAME, "getUnit", "()Ldev/mooner/neonjs/compiler/CodeUnit;", false)
                em.mv.visitFieldInsn(GETFIELD, "dev/mooner/neonjs/compiler/CodeUnit", "constants", OBJS)
                em.iconst(c.k)
                em.mv.visitInsn(AALOAD)
            }
            v === PyNone -> em.getstatic("dev/mooner/neonpython/PyNone", "INSTANCE", "Ldev/mooner/neonpython/PyNone;")
            v is PyBool -> em.getstatic(PY_BOOL, if (v.value) "TRUE" else "FALSE", "L$PY_BOOL;")
            v == null -> em.mv.visitInsn(ACONST_NULL)
            else -> throw JitBailout("python: constant $v at an exit")
        }
    }

    override fun allocateVirtual(em: Emit, v: Inst) = throw JitBailout("python: no virtual objects")
    override fun inlinedRecord(em: Emit, site: InlineSite, state: FrameState, inner: Int, value: (Value) -> Unit, array: (Array<Value>) -> Unit) =
        throw JitBailout("python: no inlining")
    override fun stressExit(em: Emit) = throw JitBailout("python: no stress exits")
    override fun suspendCall(em: Emit, i: Inst, value: (Value) -> Unit) = throw JitBailout("python: no suspensions yet")
    override fun siteLocals(site: InlineSite): List<Value> = emptyList()
    override fun newEmitter(m: MethodVisitor, first: Int): Emit = object : JvmEmitter(m) {
        var top = first
        override fun alloc(n: Int): Int = top.also { top += n }
    }

    override val deadValue: Any? get() = null

    override fun backEdgeTargets(): IntArray = PyDialect.backEdgeTargets(g.code.code)

    private companion object {
        val BINARY = mapOf(
            PyOp.ADD to "add", PyOp.SUB to "sub", PyOp.MUL to "mul", PyOp.TRUEDIV to "truediv", PyOp.FLOORDIV to "floordiv",
            PyOp.MOD to "mod", PyOp.POW to "pow", PyOp.LSHIFT to "lshift", PyOp.RSHIFT to "rshift", PyOp.BITAND to "bitAnd",
            PyOp.BITOR to "bitOr", PyOp.BITXOR to "bitXor", PyOp.SUBSCR to "getItem",
        )
        val COMPARE = mapOf(
            PyOp.LT to "lt", PyOp.LE to "le", PyOp.GT to "gt", PyOp.GE to "ge", PyOp.EQ to "eq", PyOp.NE to "ne",
            PyOp.IN to "isIn", PyOp.NOT_IN to "notIn",
        )
        val UNARY = mapOf(PyOp.NEG to "neg", PyOp.POS to "pos", PyOp.INVERT to "invert", PyOp.GET_ITER to "getIter", PyOp.FOR_ITER to "next")
    }
}
