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

/**
 * A basic block: phis, then instructions, the last of which is its terminator ([Inst.isTerminator]). [site] is the call
 * compiled inlined whose body it belongs to (null: the function compiled); [pc] is its first instruction's in that body.
 */
class Block(@JvmField var id: Int, @JvmField val pc: Int, @JvmField val site: InlineSite? = null) {
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
    /**
     * The exception entry of the innermost handler whose range covers this block (null: none): what an exception
     * thrown by its code goes to. [catchEntry] marks such entries, which no other block jumps to.
     */
    @JvmField var handler: Block? = null
    @JvmField var catchEntry = false

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
 * A constant: a JS value known when compiling. [k] is its index in the constants of code block [code] when it is read
 * from them (a string or another object constant), else -1.
 */
class Const(@JvmField val value: Any?, @JvmField val k: Int = -1, @JvmField val code: CodeBlock? = null) : Value() {
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
 * The interpreter's state at an exit (Deopt) in the function compiled ([site] null) or in the body of a call compiled
 * inlined ([site]): where it resumes, [pc], and the values of the registers then the operand stack there ([values]).
 * An inlined body's caller has its own state at the call ([InlineSite.callerState]). Its values, and those of its sites,
 * are uses, as instruction inputs are.
 */
class FrameState(@JvmField val pc: Int, @JvmField val values: Array<Value>, @JvmField val site: InlineSite? = null)

/**
 * A call compiled inlined: the callee's [code], the CALL at [callPc] in its caller (the body of [parent], or the
 * function compiled), the caller's state at that call ([callerState]: its registers and the stack below the call's
 * operands), and what a frame of the callee holds: the closure [fn], `this` as the call binds it ([thisValue]; undefined
 * where the callee does not read it), the arguments, the environment.
 */
class InlineSite(
    @JvmField val parent: InlineSite?,
    @JvmField val code: CodeBlock,
    @JvmField val callPc: Int,
    @JvmField val callerState: FrameState,
    @JvmField var fn: Value,
    @JvmField var thisValue: Value,
    @JvmField val args: Array<Value>,
    @JvmField var env: Value,
) {
    /** 1 for a call in the function compiled, 2 for a call in that callee's body, and so on. */
    @JvmField val depth: Int = (parent?.depth ?: 0) + 1

    /** The values of this site and its callers' that an exit inside it reads. */
    fun values(): List<Value> {
        val out = ArrayList<Value>()
        var s: InlineSite? = this
        while (s != null) {
            out.addAll(s.callerState.values)
            out.add(s.fn)
            out.add(s.thisValue)
            out.addAll(s.args)
            out.add(s.env)
            s = s.parent
        }
        return out
    }
}

/** The operations of the IR that are not bytecode instructions, and facts about all of them. */
object Ir {
    /** (no inputs) a parameter: [Inst.a] is its index. */
    const val PARAM = 1000
    /** (value) a TDZ check of a register's value: ReferenceError if uninitialized, [Inst.a] the name's constant. */
    const val CHECK_TDZ = 1001
    /** The deopt stress mode's exit (JvmCompiler.DEOPT_STRESS), taken one time in N, to its [Inst.state]. */
    const val STRESS_EXIT = 1002
    /**
     * (fn) the guard of a call compiled inlined: [Inst.a] indexes the callee's code block in the graph's data; unless
     * fn is a closure of it in the frame's realm, an exit to [Inst.state], before the CALL.
     */
    const val CHECK_CLOSURE = 1003
    /** (fn) entering the body of a call compiled inlined at the CALL at [Inst.a] (JitRt.inlineEnter), and leaving it. */
    const val INLINE_ENTER = 1004
    const val INLINE_EXIT = 1005
    /** (fn, this) `this` as a call of fn binds it (JitRt.inlineThis); (fn) its environment. */
    const val INLINE_THIS = 1006
    const val CLOSURE_ENV = 1007
    /**
     * (value) the value as a number (CHECK_NUMBER, a double) or an int32 (CHECK_INT32, not -0), where the profile only
     * saw such values: anything else exits to [Inst.state], after the instruction that produced it.
     */
    const val CHECK_NUMBER = 1008
    const val CHECK_INT32 = 1009
    /**
     * (obj) a property read through the shapes a monomorphic cache saw: [Inst.a] indexes the receiver's shape in the
     * graph's data, [Inst.c] the shapes of the prototypes up to the holder (or -1: an own property), [Inst.b] the slot.
     * (obj, value) STORE_SLOT writes an own property so. Another shape exits to [Inst.state], before the instruction.
     */
    const val LOAD_SLOT = 1010
    const val STORE_SLOT = 1011
    /**
     * Exception handlers: CATCH_VALUE, the first instruction of a catch entry, is the caught exception's value
     * (JitRt.catchValue); SET_HOME(value) keeps register [Inst.a]'s value where a handler reads it when an exception
     * comes (LOAD_HOME, in the catch entry): each register a handler may read is set at the start of every block a range
     * covers and wherever it changes there.
     */
    const val CATCH_VALUE = 1012
    const val SET_HOME = 1013
    const val LOAD_HOME = 1014
    /** Terminators. GOTO; BRANCH(value): [Inst.a] is the bytecode jump tested (JUMP_IF_TRUE and the like). */
    const val GOTO = 1100
    const val BRANCH = 1101
    /** RETURN(value) and THROW(value) as the bytecode's; THROW_ERROR with no input. */
    const val RETURN = 1102
    const val THROW = 1103
    const val THROW_ERROR = 1104
    /** SWITCH(index) the finally dispatch (JUMP_TABLE): to target k for index k, else to the last. */
    const val SWITCH = 1105

    fun isTerminator(op: Int) = op >= GOTO

    fun name(op: Int): String = when (op) {
        PARAM -> "Param"
        CHECK_TDZ -> "CheckTdz"
        STRESS_EXIT -> "StressExit"
        CHECK_CLOSURE -> "CheckClosure"
        INLINE_ENTER -> "InlineEnter"
        INLINE_EXIT -> "InlineExit"
        INLINE_THIS -> "InlineThis"
        CLOSURE_ENV -> "ClosureEnv"
        CHECK_NUMBER -> "CheckNumber"
        CHECK_INT32 -> "CheckInt32"
        LOAD_SLOT -> "LoadSlot"
        STORE_SLOT -> "StoreSlot"
        CATCH_VALUE -> "CatchValue"
        SET_HOME -> "SetHome"
        LOAD_HOME -> "LoadHome"
        SWITCH -> "Switch"
        GOTO -> "Goto"
        BRANCH -> "Branch"
        RETURN -> "Return"
        THROW -> "Throw"
        THROW_ERROR -> "ThrowError"
        else -> Op.names[op]
    }
}
