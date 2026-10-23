package dev.mooner.neonjs.jit.opt

import dev.mooner.neonjs.compiler.CodeBlock
import dev.mooner.neonjs.compiler.Op
import dev.mooner.neonjs.jit.JT

/**
 * The optimizing tier's intermediate representation (docs/OPTIMIZING_TIER.md, "IR"): a control flow graph of [Block]s
 * whose instructions are in SSA form. A [Value] is defined once: a constant, a parameter, a phi at the start of a
 * block, or an instruction's result.
 */
class Graph(@JvmField val code: CodeBlock) {
    /** Blocks in reverse post order; the first is the entry. */
    @JvmField val blocks = ArrayList<Block>()
    /** Objects the code needs at run time (T2Code.data), each once. */
    @JvmField val data = ArrayList<Any?>()

    /** The index of [o] in [data], added if needed. */
    fun dataIndex(o: Any?): Int {
        val i = data.indexOfFirst { it === o }
        if (i >= 0) return i
        data.add(o)
        return data.size - 1
    }
    private var nextId = 0

    val entry: Block get() = blocks[0]

    fun newId(): Int = nextId++

    /** Values defined in the graph (phis and instructions), in block order. */
    fun values(): Sequence<Value> = blocks.asSequence().flatMap { b -> b.phis.asSequence() + b.insts.asSequence() }
}

/** A basic block: phis, then instructions, the last of which is its terminator ([Inst.isTerminator]). */
class Block(@JvmField val id: Int, /** Bytecode pc of its first instruction. */ @JvmField val pc: Int) {
    @JvmField val phis = ArrayList<Phi>()
    @JvmField val insts = ArrayList<Inst>()
    @JvmField val preds = ArrayList<Block>()
    /** Whether a predecessor comes after it in reverse post order (a loop header). */
    @JvmField var loopHeader = false
    /**
     * The value in each register when the block starts, and the depth of the operand stack there: kept for loop headers,
     * where on-stack replacement may enter (from the interpreter's registers).
     */
    @JvmField var entryRegs: Array<Value>? = null
    @JvmField var entryDepth = 0

    val term: Inst get() = insts.last()

    /** The successors: the terminator's targets. */
    val succs: List<Block> get() = if (insts.isEmpty()) emptyList() else term.targets

    override fun toString() = "B$id"
}

/** An SSA value; [kind] (a [JT] kind) is how generated code holds it: a `double`, an `int`, a boolean `int` or an Object. */
abstract class Value {
    @JvmField var id = -1
    @JvmField var kind: Byte = JT.ANY
}

/**
 * A constant: a JS value known when compiling. [k] is its index in the code block's constants when it is read from them
 * (a string or another object constant), else -1.
 */
class Const(@JvmField val value: Any?, @JvmField val k: Int = -1) : Value() {
    override fun toString() = when (value) {
        is CharSequence -> "\"" + value.toString().take(20) + "\""
        null -> "null!"
        else -> if (k >= 0) "k$k" else value.toString()
    }
}

/** A phi of [block]: the value of [inputs] (one per predecessor, in the order of [Block.preds]). */
class Phi(@JvmField val block: Block) : Value() {
    @JvmField val inputs = ArrayList<Value>()

    override fun toString() = "v$id"
}

/**
 * An instruction. [op] is a bytecode instruction (Op) with its own semantics, its stack operands being [inputs] in stack
 * order and its result (if it pushes one) this value, or one of the [Ir] operations. [a], [b] and [c] are its
 * operands as in the bytecode; [pc] its position, [stmt] that of the statement it belongs to (stack traces).
 */
class Inst(
    @JvmField val op: Int,
    @JvmField var inputs: Array<Value>,
    @JvmField val a: Int,
    @JvmField val b: Int,
    @JvmField val c: Int,
    @JvmField val pc: Int,
    @JvmField val stmt: Int,
) : Value() {
    @JvmField var block: Block? = null
    /** For [Ir.BRANCH]: the targets when the test holds and when it does not; for [Ir.GOTO], the target. */
    @JvmField var targets: List<Block> = emptyList()
    /** Whether the instruction produces a value (otherwise [kind] is meaningless). */
    @JvmField var hasResult = false
    /** For an instruction that can leave the code for the interpreter (an exit): the interpreter's state there. */
    @JvmField var state: FrameState? = null

    val isTerminator: Boolean get() = Ir.isTerminator(op)

    override fun toString() = "v$id"
}

/**
 * The interpreter's state at an exit (Deopt): where it resumes, [pc], and the values of the registers then the operand
 * stack there ([values]). Its values are uses of them, as instruction inputs are.
 */
class FrameState(@JvmField val pc: Int, @JvmField val values: Array<Value>)

/** The operations of the IR that are not bytecode instructions, and facts about all of them. */
object Ir {
    /** (no inputs) a parameter: [Inst.a] is its index. */
    const val PARAM = 1000
    /** (value) a TDZ check of a register's value: ReferenceError if uninitialized, [Inst.a] the name's constant. */
    const val CHECK_TDZ = 1001
    /** The deopt stress mode's exit (JvmCompiler.DEOPT_STRESS), taken one time in N, to its [Inst.state]. */
    const val STRESS_EXIT = 1002
    /** Terminators. GOTO; BRANCH(value): [Inst.a] is the bytecode jump tested (JUMP_IF_TRUE and the like). */
    const val GOTO = 1100
    const val BRANCH = 1101
    /** RETURN(value) and THROW(value) as the bytecode's; THROW_ERROR with no input. */
    const val RETURN = 1102
    const val THROW = 1103
    const val THROW_ERROR = 1104

    fun isTerminator(op: Int) = op >= GOTO

    fun name(op: Int): String = when (op) {
        PARAM -> "Param"
        CHECK_TDZ -> "CheckTdz"
        STRESS_EXIT -> "StressExit"
        GOTO -> "Goto"
        BRANCH -> "Branch"
        RETURN -> "Return"
        THROW -> "Throw"
        THROW_ERROR -> "ThrowError"
        else -> Op.names[op]
    }
}
