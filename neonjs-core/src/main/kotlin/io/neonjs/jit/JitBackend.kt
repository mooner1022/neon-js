package io.neonjs.jit

import io.neonjs.compiler.CodeBlock

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

/** The JVM bytecode backend: [JvmCompiler] generates classes, [definer] defines them, a batch at a time. */
class ClassBackend(val definer: CodeDefiner) : JitBackend {
    override fun compile(blocks: List<CodeBlock>): List<CompiledCode?> = JvmCompiler.compileAll(blocks, definer)

    override fun equals(other: Any?): Boolean = other is ClassBackend && other.definer === definer
    override fun hashCode(): Int = System.identityHashCode(definer)
}
