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
    /** The definer the code came from, which its block's next compilation uses. */
    @JvmField var definer: dev.mooner.neonjs.jit.CodeDefiner? = null
    /** The loop headers the code can be entered at by on-stack replacement, sorted. */
    @JvmField var osrPcs: IntArray = IntArray(0)

    fun hasEntry(pc: Int): Boolean = java.util.Arrays.binarySearch(osrPcs, pc) >= 0

    /**
     * The code declined a frame at the loop header [pc] (a register without the kind it speculated on: an int sum that
     * overflowed in the interpreter, say): that entry is not offered again (Jit.osr goes to the baseline there).
     */
    fun declined(pc: Int) {
        osrPcs = osrPcs.filter { it != pc }.toIntArray()
    }

    companion object {
        /**
         * Deoptimizations after which a version of a block's code gives way to its baseline (and the block is compiled
         * again without the speculations that failed, [RECOMPILES] times at most, then not at all).
         */
        @JvmField val DEOPT_LIMIT = Integer.getInteger("neonjs.jit.t2DeoptLimit", 16)
        const val RECOMPILES = 3

        /**
         * An exit of [code] running [f] (Deopt.resume) where the speculation [data] names (a [Speculation]) failed: it is
         * noted on its code block, and past [DEOPT_LIMIT] exits the block's baseline runs new activations again
         * (Jit.revert) while the block is compiled again, without the speculations that failed.
         */
        @JvmStatic
        fun exit(code: T2Code, f: Frame, pc: Int, values: Array<Any?>, inner: Any?, spec: Int): Any? {
            val s = code.data[spec] as Speculation
            synchronized(s.code) {
                (s.code.t2Failed ?: java.util.BitSet().also { s.code.t2Failed = it }).set(s.pc)
            }
            if (++code.deopts == DEOPT_LIMIT) {
                Jit.revert(code.block, code)
                T2.reverted.incrementAndGet()
                val b = code.block
                if (++b.t2Recompiles > RECOMPILES) b.t2Off = true
                else code.definer?.let { T2.submit(b, it, f.realm.agent.config.backgroundJit) }
            }
            return Deopt.resume(f, pc, values, inner as Deopt.Inlined?)
        }
    }
}

/** A speculation an exit stands for: made at [pc] of [code] (pc [CodeBlock.code].size: the parameters). */
class Speculation(@JvmField val code: CodeBlock, @JvmField val pc: Int)
