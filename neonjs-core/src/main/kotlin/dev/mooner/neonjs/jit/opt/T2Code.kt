package dev.mooner.neonjs.jit.opt

import dev.mooner.neonjs.compiler.CodeBlock
import dev.mooner.neonjs.jit.CompiledCode
import dev.mooner.neonjs.jit.Deopt
import dev.mooner.neonjs.jit.Jit
import dev.mooner.neonjs.vm.Frame

/**
 * The superclass of the optimizing tier's classes: the code of one block ([block]) with what it was compiled against
 * ([data]: objects the code compares with or reads, which a class cannot hold as constants), and the count of its
 * deoptimizations. Code that deoptimizes too often gives way to the baseline for good ([exit]).
 */
abstract class T2Code(@JvmField val data: Array<Any?>, @JvmField val block: CodeBlock) : CompiledCode {
    @JvmField var deopts = 0
    /** The loop headers the code can be entered at by on-stack replacement, sorted. */
    @JvmField var osrPcs: IntArray = IntArray(0)

    fun hasEntry(pc: Int): Boolean = java.util.Arrays.binarySearch(osrPcs, pc) >= 0

    companion object {
        /** Deoptimizations after which a block's code goes back to its baseline, and the block stays there. */
        @JvmField val DEOPT_LIMIT = Integer.getInteger("neonjs.jit.t2DeoptLimit", 64)

        /**
         * An exit of [code] running [f] (Deopt.resume) where an assumption failed: counted, and past [DEOPT_LIMIT]
         * the block's baseline runs new activations again (Jit.revert) and the block is not compiled by this tier again.
         */
        @JvmStatic
        fun exit(code: T2Code, f: Frame, pc: Int, values: Array<Any?>, inner: Any?): Any? {
            if (++code.deopts == DEOPT_LIMIT) {
                code.block.t2Off = true
                Jit.revert(code.block, code)
                T2.reverted.incrementAndGet()
            }
            return Deopt.resume(f, pc, values, inner as Deopt.Inlined?)
        }
    }
}
