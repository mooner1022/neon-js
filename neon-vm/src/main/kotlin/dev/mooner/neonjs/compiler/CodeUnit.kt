package dev.mooner.neonjs.compiler

/**
 * What the execution core (the JIT's policy, the optimizing tier, the frames it runs) reads of a unit of code, whatever
 * its language (docs/IR_NEUTRALITY.md §10): its bytecode and constants, exception handlers, registers and operand
 * stack, line table, flags, parameters, the code compiled for it and the tiers' state, and its language's dialect.
 * JavaScript's [CodeBlock] extends it; another language's code is one, or its own subclass.
 *
 * (Part of the core's interface for languages: public for NeonJS's language modules, not a stable API.)
 */
open class CodeUnit(@JvmField val name: String) {
    /** [GENERATOR], [ASYNC], [NO_JIT] and the language's own bits. */
    @JvmField var flags = 0
    /** The bytecode: an opcode and its operands per instruction, in the language's encoding. */
    @JvmField var code: IntArray = IntArray(0)
    @JvmField var constants: Array<Any?> = arrayOfNulls(0)
    /**
     * Exception handlers: quadruples (startPc, endPc, handlerPc, stack depth at the handler), inner handlers first.
     */
    @JvmField var handlers: IntArray = IntArray(0)
    /** Registers, then the operand stack ([maxStack] at most), in a frame's slots. */
    @JvmField var numRegs = 0
    @JvmField var maxStack = 0
    /** The number of formal parameters. */
    @JvmField var formalCount = 0
    /** For each formal parameter, the register receiving the argument, or -1. */
    @JvmField var paramRegs: IntArray? = null
    /**
     * Pairs (pc, position) sorted by pc: where each statement starts (the optimizing tier stores its pc where something
     * can throw or call); the position is the language's (a source offset, a line).
     */
    @JvmField var lineTable: IntArray = IntArray(0)
    /**
     * JIT state (see dev.mooner.neonjs.jit.Jit): the code new activations of this unit run, once some is installed: the
     * [baseline], or a later version over it (docs/OPTIMIZING_TIER.md, "Code versions"), which can give way to the
     * baseline again. Every version runs a frame made for compiled code and answers on-stack replacement, so readers
     * need not care which one they get.
     */
    @JvmField @Volatile var compiled: Any? = null
    /** The first compiled code, once installed: never replaced. */
    @JvmField @Volatile var baseline: Any? = null
    /** Whether the optimizing tier's code for this unit deoptimized too often: it is not compiled by that tier again. */
    @JvmField var t2Off = false
    /**
     * The optimizing tier's dialect of the unit's language (a jit.opt.Dialect: its builder, ops, kinds and code
     * generator, how its baselines are compiled, docs/IR_NEUTRALITY.md).
     */
    @JvmField var dialect: Any? = null
    /** What the unit's language keeps of it besides its bytecode (another language's names of locals, say). */
    @JvmField var dialectData: Any? = null
    /**
     * The pcs where a speculation of the optimizing tier failed (an exit taken), which its next compilations of this
     * unit, or of units inlining it, do not make again (bit [code].size: the parameters); and how many times the unit
     * was compiled again because of them.
     */
    @JvmField var t2Failed: java.util.BitSet? = null
    @JvmField var t2Recompiles = 0

    /** Whether a speculation of the optimizing tier at [pc] failed before ([t2Failed]). */
    fun t2FailedAt(pc: Int): Boolean = t2Failed?.get(pc) ?: false
    @JvmField var invocationCount = 0
    @JvmField @Volatile var jitFailed = false
    /** The pending dev.mooner.neonjs.jit.JitTask while the unit is queued or being compiled. */
    @JvmField @Volatile var jitTask: Any? = null
    /** Whether the functions this unit defines were queued for compilation (compiled mode). */
    @JvmField var childrenQueued = false

    companion object {
        /** A generator's code (it suspends at yields), in every language. */
        const val GENERATOR = 4
        /** An async function's code (it suspends at awaits), in every language. */
        const val ASYNC = 8
        /** Code that stays interpreted. */
        const val NO_JIT = 4096
    }
}
