package dev.mooner.neonjs.jit

import dev.mooner.neonjs.vm.VmFrame

/**
 * The execution core's own helpers of generated code, whatever the language (a language's runtime has the rest:
 * JavaScript's [JitRt]).
 *
 * (Part of the core's interface for languages: public for NeonJS's language modules, not a stable API.)
 */
object VmRt {
    /** Returned by [CompiledCode.run] given an interpreted frame at a pc it cannot be entered at. */
    @JvmField val NO_OSR = Any()

    /**
     * Returned by [CompiledCode.run] given an interpreted frame, when the code left for the interpreter (an exit): the
     * frame holds the interpreter state to continue with.
     */
    @JvmField val DEOPTED = Any()

    /**
     * Whether the code running [f] ([code]) finds, at a loop back edge, a later version of its unit's code installed
     * (the optimizing tier's) that it can be entered at the loop header [pc].
     */
    @JvmStatic fun canTierUp(f: VmFrame, code: Any?, pc: Int): Boolean {
        val c = f.unit.compiled
        return c !== code && c is dev.mooner.neonjs.jit.opt.T2Code && c.hasEntry(pc)
    }
}
