package dev.mooner.neonjs.jit.opt

import dev.mooner.neonjs.compiler.CodeUnit

/**
 * The optimizing tier's intermediate representation (docs/OPTIMIZING_TIER.md, "IR"): a control flow graph of [Block]s
 * whose instructions are in SSA form. A [Value] is defined once: a constant, a parameter, a phi at the start of a
 * block, or an instruction's result.
 */
class Graph(
    @JvmField val code: CodeUnit,
    /** What the passes know of the graph's operations, and the kinds of its values. */
    @JvmField val ops: OpTable,
    @JvmField val kindSystem: KindSystem,
    /** What the interpreter holds in a register it writes before reading it (a dead one), as exits pass it. */
    @JvmField val deadValue: Any?,
    /** The language: its builder and code generator. */
    @JvmField val dialect: Dialect,
) {
    /** Blocks in reverse post order; the first is the entry. */
    @JvmField val blocks = ArrayList<Block>()
    /** The loop headers the generated code can be entered at (IrCodegen's on-stack replacement entries), sorted. */
    @JvmField var osrPcs = IntArray(0)
    /** Objects the code needs at run time (T2Code.data), each once. */
    @JvmField val data = ArrayList<Any?>()
    /**
     * Whether the code's frames are interpreted ones from their start (a generator's, an async function's: they suspend):
     * the entry dispatch sends pc 0 to the start, never to a loop header there (Ir.SUSPEND).
     */
    @JvmField var startsInterpreted = false
    /**
     * For a baseline (T2.compileBaselines) compiled with the tier on: at a loop back edge whose budget ran out, the code
     * looks for a later version of its block's code (the tier's) with an entry at that header, and goes on there
     * (Deopt.tierUp), as the baseline compiler's code does (JitInput.tierUp).
     */
    @JvmField var tierUp = false
    /** Whether a loop was copied on a closure test (Unswitch): T2 builds the graph again without that where it is too large. */
    @JvmField var unswitched = false

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
    /** For a loop header: the block before it holding what LICM moved out of the loop (null: none). */
    @JvmField var loopEntry: Block? = null
    /**
     * For a loop header copied on a closure test (Unswitch): the copy's header, and the block testing which runs; for the
     * copy's header, the header it copies (not an on-stack replacement entry of its own: the original's tests first).
     */
    @JvmField var twin: Block? = null
    @JvmField var dispatch: Block? = null
    @JvmField var twinOf: Block? = null
    /**
     * The exception entry of the innermost handler whose range covers this block (null: none): what an exception
     * thrown by its code goes to. [catchEntry] marks such entries, which no other block jumps to.
     */
    @JvmField var handler: Block? = null
    @JvmField var catchEntry = false
    /**
     * Where a suspended activation (a generator's, an async function's) resumes, at its [pc]: entered only by the code's
     * entry dispatch, its registers and operand stack read from the frame (Ir.RESUME_SLOT); its one predecessor ends
     * with the SUSPEND that leaves the code.
     */
    @JvmField var resumeEntry = false

    val term: Inst get() = insts.last()

    /** The successors: the terminator's targets. */
    val succs: List<Block> get() = if (insts.isEmpty()) emptyList() else term.targets

    override fun toString() = "B$id"
}

/** An SSA value; [kind] (a kind of the language's KindSystem) is how generated code holds it: a `double`, an `int`, a boolean `int` or an Object. */
abstract class Value {
    @JvmField var id = -1
    @JvmField var kind: Byte = ANY_KIND
}

/**
 * A constant: a JS value known when compiling. [k] is its index in the constants of code block [code] when it is read
 * from them (a string or another object constant), else -1.
 */
class Const(@JvmField val value: Any?, @JvmField val k: Int = -1, @JvmField val code: CodeUnit? = null) : Value() {
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
    @JvmField var op: Int,
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
    /**
     * An allocation that is not made (ScalarReplacement): no code, no local; an exit whose state holds it allocates it
     * from its inputs.
     */
    @JvmField var virtual = false
    /** For an instruction LICM moved out of its loop: the code block it was made from (its exits note failures there). */
    @JvmField var origin: dev.mooner.neonjs.compiler.CodeUnit? = null
    /**
     * For int32 arithmetic whose every use truncates to 32 bits (ADD, SUB, SHR: IrBuilder.intArith): the int result
     * wraps around, with no exit.
     */
    @JvmField var wraps = false
    /** For LOAD_SLOT, STORE_SLOT and ADD_SLOT: the checks not made, their facts holding there (KnownShapes bits). */
    @JvmField var known = 0
    /** For INLINE_ENTER and INLINE_EXIT: the call they enter and leave. */
    @JvmField var callee: InlineSite? = null

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
 * A call compiled inlined: the callee's [code], the call at [callPc] in its caller (the body of [parent], or the
 * function compiled), the caller's state at that call ([callerState]: its registers and the stack below the call's
 * operands), and what a frame of the callee holds as its language lays it out ([frame]: JavaScript's closure, `this`
 * as the call binds it, the arguments, the environment), which an exit inside the body passes to the interpreter, in
 * that order. [flags] are the language's facts about the call (JavaScript's: a construction).
 */
class InlineSite(
    @JvmField val parent: InlineSite?,
    @JvmField val code: CodeUnit,
    @JvmField val callPc: Int,
    @JvmField val callerState: FrameState,
    @JvmField val frame: Array<Value>,
    @JvmField val flags: Int = 0,
) {
    /** 1 for a call in the function compiled, 2 for a call in that callee's body, and so on. */
    @JvmField val depth: Int = (parent?.depth ?: 0) + 1
    /** Whether nothing can see the call from outside the frame: it is entered and left without a record (QuietInlines). */
    @JvmField var quiet = false

    /** The values of this site and its callers' that an exit inside it reads. */
    fun values(): List<Value> {
        val out = ArrayList<Value>()
        var s: InlineSite? = this
        while (s != null) {
            out.addAll(s.callerState.values)
            out.addAll(s.frame)
            s = s.parent
        }
        return out
    }

    /** Puts [f]'s value in place of each value of the frame (a pass replacing values). */
    fun mapFrame(f: (Value) -> Value) {
        for (k in frame.indices) frame[k] = f(frame[k])
    }
}

/**
 * The operations of the IR that every language's graphs have (docs/IR_NEUTRALITY.md): terminators, exception handlers'
 * homes, the records and guards of calls compiled inlined, the stress mode's exits. A language's own operations are
 * its bytecode's (below 1000) and those its builder makes (from 2000: JavaScript's are [JsIr]).
 */
object Ir {
    /** The deopt stress mode's exit (Jit.DEOPT_STRESS), taken one time in N, to its [Inst.state]. */
    const val STRESS_EXIT = 1002
    /**
     * (fn) the guard of a call compiled inlined: [Inst.a] indexes the callee's code block in the graph's data; unless
     * fn is a closure of it in the frame's realm, an exit to [Inst.state], before the CALL.
     */
    const val CHECK_CLOSURE = 1003
    /** (fn) entering the body of a call compiled inlined at the CALL at [Inst.a] (JitRt.inlineEnter), and leaving it. */
    const val INLINE_ENTER = 1004
    const val INLINE_EXIT = 1005
    /**
     * Exception handlers: CATCH_VALUE, the first instruction of a catch entry, is the caught exception's value
     * (JitRt.catchValue); SET_HOME(value) keeps register [Inst.a]'s value where a handler reads it when an exception
     * comes (LOAD_HOME, in the catch entry): each register a handler may read is set at the start of every block a range
     * covers and wherever it changes there.
     */
    const val CATCH_VALUE = 1012
    const val SET_HOME = 1013
    const val LOAD_HOME = 1014
    /**
     * (fn) whether fn is a closure of the code block [Inst.a] indexes in the graph's data, in the frame's realm (as
     * CHECK_CLOSURE tests, without an exit): a call inlined where its guard failed before branches on it to a call made
     * as a call instead (IrBuilder.inline); [Inst.b] 1 marks a site built so tentatively (Unswitch), [Inst.state] being
     * its guard's.
     */
    const val IS_CLOSURE = 1017
    /** Terminators. GOTO; BRANCH(value): [Inst.a] is the bytecode jump tested (JUMP_IF_TRUE and the like). */
    const val GOTO = 1100
    const val BRANCH = 1101
    /** RETURN(value) and THROW(value) as the bytecode's; THROW_ERROR with no input. */
    const val RETURN = 1102
    const val THROW = 1103
    const val THROW_ERROR = 1104
    /** SWITCH(index) the finally dispatch (JUMP_TABLE): to target k for index k, else to the last. */
    const val SWITCH = 1105

    /** The core's operations are from 1000 to 1999. */
    /**
     * (value?) leaves the code, its activation suspended (a generator's yield, an async function's await): the
     * interpreter's state at the pc it resumes at ([Inst.state]) and the value it hands over are written to the frame
     * (the language's suspension, [Inst.a] its kind), and the code returns the interpreter's SUSPENDED. Its target is
     * the block where the activation resumes ([Block.resumeEntry]), which the code's entry dispatch enters.
     */
    const val SUSPEND = 1106
    /** (no inputs) slot [Inst.a] of the frame (a register, or the operand stack past them), where it resumes. */
    const val RESUME_SLOT = 1026

    fun isCore(op: Int) = op in 1000..1999

    fun isTerminator(op: Int) = op in GOTO..1999

    /** A core operation's name; "op<n>" for another (a language's: its op table names them). */
    fun name(op: Int): String = when (op) {
        STRESS_EXIT -> "StressExit"
        CHECK_CLOSURE -> "CheckClosure"
        INLINE_ENTER -> "InlineEnter"
        INLINE_EXIT -> "InlineExit"
        CATCH_VALUE -> "CatchValue"
        SET_HOME -> "SetHome"
        LOAD_HOME -> "LoadHome"
        IS_CLOSURE -> "IsClosure"
        GOTO -> "Goto"
        BRANCH -> "Branch"
        RETURN -> "Return"
        THROW -> "Throw"
        THROW_ERROR -> "ThrowError"
        SWITCH -> "Switch"
        SUSPEND -> "Suspend"
        RESUME_SLOT -> "ResumeSlot"
        else -> "op$op"
    }
}
