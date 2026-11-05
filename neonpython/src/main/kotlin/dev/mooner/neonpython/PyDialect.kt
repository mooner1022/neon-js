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
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*

/**
 * Python's dialect of the optimizing tier (docs/IR_NEUTRALITY.md, docs/PYTHON_PLATFORM.md §10): its bytecode
 * ([PyOp]) becomes the core's graph ([PyBuilder]), whose passes and code generation are the core's; each instruction's
 * code calls the runtime ([PyRt]), as the interpreter does, but comparisons, which branches test as JVM ints (the kind
 * [PyKinds.BOOL]), and the loop's control flow, which is the JVM's.
 *
 * Its blocks get the tier's baselines (no speculation: T2.compileBaselines); the speculating tier's graphs are not
 * built yet.
 */
object PyDialect : Dialect {
    override fun build(cb: CodeUnit, stressExits: Boolean, unswitch: Boolean, baseline: Boolean): Graph {
        if (!baseline) throw JitBailout("python: no speculation yet")
        return PyBuilder(cb).build()
    }

    /** An exit's state into the frame's slots, and the interpreter on from its pc (no exit is made yet but the core's). */
    override val resumer: Resumer = Resumer { f, pc, values, _ ->
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

/** The graph's operations that are no bytecode's (numbers below the core's 1000, past [PyOp]'s). */
object PyIr {
    /** a: the argument a function's parameter a is (its register at the start). */
    const val ARG = 100
    /** (v) v, or an UnboundLocalError where it is the dead value (register a's: LOAD_FAST_CHECK). */
    const val CHECK_BOUND = 101
    /** (seq) its a items in an Object[] (UNPACK), then ITEM a of them. */
    const val UNPACK_ARRAY = 102
    const val ITEM = 103
}

/**
 * Python's kinds: any value (an Object), or a bool the code holds as a JVM int (a comparison's result, which a branch
 * tests as it is and which becomes a PyBool where something takes an Object). Numbers are boxed yet: a Python int
 * exceeds 64 bits where it must (speculating on Longs is the speculating tier's).
 */
object PyKinds : KindSystem {
    const val BOOL: Byte = 1

    override fun rep(kind: Byte): Rep = if (kind == BOOL) Rep.INT else Rep.OBJECT
    override fun join(a: Byte, b: Byte): Byte = if (a == b) a else ANY_KIND
    override fun isNumeric(kind: Byte): Boolean = false
    override fun constKind(value: Any?): Byte = if (value is PyBool) BOOL else ANY_KIND

    override fun resultKind(i: Inst, kinds: Kinds): Byte = when (i.op) {
        in PyOp.LT..PyOp.NOT_IN, PyOp.NOT -> BOOL
        else -> ANY_KIND
    }

    override fun initial(em: Emit, kind: Byte) = rep(kind).zero(em.mv)

    override fun convert(em: Emit, from: Byte, to: Byte) {
        when {
            from == to -> {}
            from == BOOL && to == ANY_KIND -> em.mv.visitMethodInsn(INVOKESTATIC, PY_BOOL, "of", "(Z)L$PY_BOOL;", false)
            else -> throw JitBailout("python: kind $from as $to")
        }
    }

    // a register of kind BOOL at a loop header holds a PyBool in the interpreter too (the same comparisons made it)
    override fun osrCode(kind: Byte): Char = '.'
    override fun osrRead(em: Emit, kind: Byte) {
        em.mv.visitInsn(AALOAD)
        if (kind == BOOL) em.mv.visitMethodInsn(INVOKESTATIC, PY_RT, "slotBool", "(Ljava/lang/Object;)Z", false)
    }

    override fun name(kind: Byte): String = if (kind == BOOL) " :bool" else ""
}

/** What the passes know of Python's operations: most may run code and throw; a few only compute or allocate. */
object PyOps : OpTable {
    private val infos = arrayOfNulls<OpInfo>(PyIr.ITEM + 1).also { t ->
        for (op in 0 until PyOp.COUNT) if (PyOp.names[op] != null) t[op] = OpInfo(PyOp.name(op), OpInfo.ANYTHING)
        t[PyOp.IS] = OpInfo("IS", OpInfo.REMOVABLE)
        t[PyOp.IS_NOT] = OpInfo("IS_NOT", OpInfo.REMOVABLE)
        t[PyOp.BUILD_LIST] = OpInfo("BUILD_LIST", OpInfo.REMOVABLE or OpInfo.ALLOCATES)
        t[PyOp.BUILD_TUPLE] = OpInfo("BUILD_TUPLE", OpInfo.REMOVABLE or OpInfo.ALLOCATES)
        t[PyOp.MAKE_FUNCTION] = OpInfo("MAKE_FUNCTION", OpInfo.REMOVABLE or OpInfo.ALLOCATES)
        t[PyIr.ARG] = OpInfo("Arg", OpInfo.REMOVABLE)
        t[PyIr.CHECK_BOUND] = OpInfo("CheckBound", OpInfo.THROWS)
        t[PyIr.UNPACK_ARRAY] = OpInfo("UnpackArray", OpInfo.ANYTHING)
        t[PyIr.ITEM] = OpInfo("Item", OpInfo.REMOVABLE)
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
        else -> ""
    }
}

/** The graph of a Python code block: its registers from its arguments, each instruction built as its bytecode's. */
class PyBuilder(cb: CodeUnit) : GraphBuilder(cb, Graph(cb, PyOps, PyKinds, null, PyDialect), stressExits = false, speculate = false) {
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
            PyOp.STORE_FAST -> bc[pc + 1]
            PyOp.FOR_ITER -> bc[pc + 2]
            else -> -1
        }

        override fun nameAt(pc: Int): String = PyOp.name(bc[pc])

        /**
         * Constant [k]: None, True and False as their values (the code has them as such: GraphIdentity tells them apart);
         * every other by its index, which the code reads from the block's constants (blocks of one graph share a
         * class, each with its own constants).
         */
        private fun constant(k: Int): Const {
            val v = code.constants[k]
            return if (v is PyBool || v === PyNone) Const(v) else Const(v, k, code)
        }

        override fun buildOp(at: At): Boolean = with(at) {
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
                PyOp.LOAD_GLOBAL, PyOp.MAKE_FUNCTION -> inst(op, NO_VALUES, result = true)
                PyOp.STORE_GLOBAL -> inst(op, arrayOf(pop()), result = false)
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
                PyOp.NEG, PyOp.POS, PyOp.NOT, PyOp.INVERT, PyOp.LOAD_ATTR, PyOp.GET_ITER -> inst(op, arrayOf(pop()), result = true)
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
                    end()
                    val next = pc + len
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

    override fun inst(i: Inst) {
        val op = i.op
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
            PyOp.LOAD_ATTR -> {
                storePc(i)
                load(i.inputs[0], ANY_KIND)
                constant(i.a)
                py("loadAttr", "($OBJ$OBJ)$OBJ")
                done(i, ANY_KIND)
            }
            PyOp.LOAD_GLOBAL -> {
                storePc(i)
                frame()
                constant(i.a)
                py("loadGlobal", "($FRAME_D$OBJ)$OBJ")
                done(i, ANY_KIND)
            }
            PyOp.STORE_GLOBAL -> {
                frame()
                constant(i.a)
                load(i.inputs[0], ANY_KIND)
                py("storeGlobal", "($FRAME_D$OBJ$OBJ)V")
            }
            PyOp.MAKE_FUNCTION -> {
                frame()
                constant(i.a)
                py("makeFunction", "($FRAME_D$OBJ)$OBJ")
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
            else -> throw JitBailout("python: no code for ${g.ops.info(op).name}")
        }
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
        // (a constant of the block's, by its index, is read from its constants: the class may be another block's too)
        if (c.k >= 0) {
            mv.visitVarInsn(ALOAD, constsLocal[c.code ?: g.code] ?: constsLocal[g.code]!!)
            iconst(c.k)
            mv.visitInsn(AALOAD)
            if (want == PyKinds.BOOL) py("slotBool", "($OBJ)Z")
            return
        }
        if (want == PyKinds.BOOL) {
            if (v !is PyBool) throw JitBailout("python: constant $v as a bool")
            iconst(if (v.value) 1 else 0)
            return
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
