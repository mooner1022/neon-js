package dev.mooner.neonjs.jit

import dev.mooner.neonjs.compiler.CodeUnit

/**
 * Turns code blocks into [CompiledCode]. The tiering policy ([Jit]) and the background compiler hand it batches of
 * blocks; how it produces code is its own business: [ClassBackend] generates JVM classes and defines them through a
 * [CodeDefiner], a backend writing dex directly would not go through class files at all.
 *
 * Backends are compared with `equals` to group pending blocks into batches.
 */
interface JitBackend {
    /**
     * Compiled code for each of [blocks] (null where a block cannot be compiled). Compiling several blocks at once
     * lets a backend share fixed costs between them; one failing block must not fail the others. Never throws.
     */
    fun compile(blocks: List<CodeUnit>): List<CompiledCode?>
}

/**
 * The JVM bytecode backend: each block's language compiles its baselines (`Dialect.compileBaselines`: JavaScript's T1,
 * the optimizing tier's pipeline for the others), [definer] defines the classes, a batch at a time. With [tierUp] (the
 * optimizing tier on), the code's loops move to the tier's code once it is installed (JitInput.tierUp).
 */
class ClassBackend(val definer: CodeDefiner, val tierUp: Boolean = false) : JitBackend {
    override fun compile(blocks: List<CodeUnit>): List<CompiledCode?> {
        if (blocks.isEmpty()) return emptyList()
        val first = dev.mooner.neonjs.jit.opt.Dialects.of(blocks[0])
        if (blocks.all { dev.mooner.neonjs.jit.opt.Dialects.of(it) === first }) return first.compileBaselines(blocks, definer, tierUp)
        // a batch of several languages: each language's blocks together
        val out = arrayOfNulls<CompiledCode>(blocks.size)
        for ((d, ks) in blocks.indices.groupBy { dev.mooner.neonjs.jit.opt.Dialects.of(blocks[it]) }) {
            d.compileBaselines(ks.map { blocks[it] }, definer, tierUp).forEachIndexed { n, c -> out[ks[n]] = c }
        }
        return out.asList()
    }

    override fun equals(other: Any?): Boolean = other is ClassBackend && other.definer === definer && other.tierUp == tierUp
    override fun hashCode(): Int = System.identityHashCode(definer) * 31 + (if (tierUp) 1 else 0)
}
