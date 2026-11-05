package dev.mooner.neonjs.jit

import dev.mooner.neonjs.jit.opt.Dialects
import dev.mooner.neonjs.vm.VmFrame
import java.util.concurrent.atomic.AtomicLong

/**
 * What the execution core does of deoptimization, whatever the language: a frame given the interpreter's state
 * ([write]), and a loop moving to a later version of its code ([tierUp]). How an exit continues in the interpreter is
 * the language's (its dialect's resumer: JavaScript's [Deopt]).
 *
 * (Part of the core's interface for languages: public for NeonJS's language modules, not a stable API.)
 */
object VmDeopt {
    /** Loops moved to a later version of their code ([tierUp]). */
    @JvmField val tierUps = AtomicLong()

    /**
     * Continues [f], whose code found a later version of its unit's code installed at a loop back edge
     * (VmRt.canTierUp), in that version: the frame becomes interpreted at the loop header [pc] with the registers
     * [values], then enters the new code there by on-stack replacement; where that code declines the entry or
     * deoptimizes, the language's interpreter goes on (Dialect.interpret). Returns the frame's result, or
     * [VmRt.DEOPTED] when the code was given [f] interpreted.
     */
    @JvmStatic
    fun tierUp(f: VmFrame, pc: Int, values: Array<Any?>): Any? {
        tierUps.incrementAndGet()
        val entered = !f.isCompiled
        val dialect = Dialects.of(f.unit)
        dialect.dropInlined(f)
        write(f, values)
        f.pc = pc
        val r = Jit.osr(f)
        if (r !== VmRt.NO_OSR && r !== VmRt.DEOPTED) return r
        return if (entered) VmRt.DEOPTED else dialect.interpret(f)
    }

    /** Gives [f] slots (it keeps them if it has some) holding [values]: its registers, then its operand stack. */
    @JvmStatic
    fun write(f: VmFrame, values: Array<Any?>) {
        val code = f.unit
        if (values.size < code.numRegs || values.size > code.numRegs + code.maxStack) {
            throw IllegalStateException("deoptimization state of ${values.size} values for ${code.numRegs} registers")
        }
        var s = f.slots
        if (s === VmFrame.NO_SLOTS) {
            s = VmFrame.slotsFor(code)
            f.slots = s
        }
        values.copyInto(s)
        java.util.Arrays.fill(s, values.size, s.size, null)
        f.sp = values.size
    }
}
