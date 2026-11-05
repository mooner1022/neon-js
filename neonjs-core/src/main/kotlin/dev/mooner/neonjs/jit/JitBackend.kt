package dev.mooner.neonjs.jit

import dev.mooner.neonjs.compiler.CodeBlock

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
    fun compile(blocks: List<CodeBlock>): List<CompiledCode?>
}

/**
 * The JVM bytecode backend: [JvmCompiler] generates classes, [definer] defines them, a batch at a time. With [tierUp]
 * (the optimizing tier on), the code's loops move to the tier's code once it is installed (JitInput.tierUp).
 */
class ClassBackend(val definer: CodeDefiner, val tierUp: Boolean = false) : JitBackend {
    override fun compile(blocks: List<CodeBlock>): List<CompiledCode?> {
        if (!dev.mooner.neonjs.jit.opt.T2.IR_BASELINE) {
            val out = JvmCompiler.compileAll(blocks, definer, tierUp)
            // generators and async functions, which the baseline compiler leaves: the optimizing tier's baselines
            val rest = blocks.indices.filter { out[it] == null && dev.mooner.neonjs.jit.opt.T2.suspendable(blocks[it]) }
            if (rest.isEmpty() || !CodeDefiners.isUsable(definer)) return out
            val ir = dev.mooner.neonjs.jit.opt.T2.compileBaselines(rest.map { blocks[it] }, definer, tierUp)
            return out.toMutableList().also { for ((j, i) in rest.withIndex()) it[i] = ir[j] }
        }
        // (testing) the optimizing tier's baselines, the baseline compiler's where it has none
        val usable = CodeDefiners.isUsable(definer)
        val ir = if (usable) dev.mooner.neonjs.jit.opt.T2.compileBaselines(blocks.filter { Jit.canCompile(it) }, definer, tierUp) else emptyList()
        val out = arrayOfNulls<CompiledCode>(blocks.size)
        var k = 0
        for ((i, cb) in blocks.withIndex()) if (usable && Jit.canCompile(cb)) out[i] = ir[k++]
        val rest = blocks.indices.filter { out[it] == null }
        if (rest.isNotEmpty()) {
            val t1 = JvmCompiler.compileAll(rest.map { blocks[it] }, definer, tierUp)
            for ((j, i) in rest.withIndex()) out[i] = t1[j]
        }
        return out.asList()
    }

    override fun equals(other: Any?): Boolean = other is ClassBackend && other.definer === definer && other.tierUp == tierUp
    override fun hashCode(): Int = System.identityHashCode(definer) * 31 + (if (tierUp) 1 else 0)
}
