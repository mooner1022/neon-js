package dev.mooner.neonjs.vm

import dev.mooner.neonjs.compiler.CodeUnit
import dev.mooner.neonjs.runtime.VmAgent

/**
 * What the execution core reads of an activation, whatever its language (docs/IR_NEUTRALITY.md §10): the arguments it
 * was called with ([args], where a language's parameters come from), the registers then the operand stack of an
 * interpreted one ([slots], [sp], [pc]: on-stack replacement, resumption, exits write them), the position in a call
 * compiled inlined ([inlinePc]), its code unit and its agent. JavaScript's [Frame] extends it, with its own typed code
 * block and realm; another language's frame is its own subclass.
 *
 * (Part of the core's interface for languages: public for NeonJS's language modules, not a stable API.)
 */
abstract class VmFrame(
    @JvmField var args: Array<Any?>,
    /** Registers, then the operand stack, of an interpreted frame; [NO_SLOTS] for a frame compiled code runs. */
    @JvmField var slots: Array<Any?>,
    @JvmField var sp: Int,
) {
    @JvmField var pc: Int = 0
    @JvmField var inlinePc: Int = 0

    /** The code the frame runs (its language's subclass keeps its own typed reference). */
    abstract val unit: CodeUnit

    /** The agent running the frame: its limits and interrupts, the JIT's settings. */
    abstract val agent: VmAgent

    /** Whether compiled code runs this frame: it was made for compiled code and not deoptimized since. */
    val isCompiled: Boolean get() = slots === NO_SLOTS

    companion object {
        /** The slots of a frame compiled code runs. */
        @JvmField val NO_SLOTS = arrayOfNulls<Any?>(0)

        /** Slots for an interpreted frame of [unit]: its registers, then its operand stack. */
        @JvmStatic fun slotsFor(unit: CodeUnit): Array<Any?> = arrayOfNulls(unit.numRegs + unit.maxStack)
    }
}
