package io.neonjs.jit

import io.neonjs.compiler.CodeBlock
import io.neonjs.runtime.Agent

/** Tier-up policy: decides when code blocks are compiled to JVM bytecode. */
object Jit {
    const val MODE_INTERPRETER = 0
    const val MODE_COMPILED = 1
    const val MODE_ADAPTIVE = 2

    /** Returns compiled code for [cb] if available or due according to the agent's execution mode. */
    @JvmStatic
    fun prepare(cb: CodeBlock, agent: Agent): CompiledCode? {
        val c = cb.compiled
        if (c != null) return c as CompiledCode
        if (cb.jitFailed) return null
        val mode = agent.config.executionMode
        if (mode == MODE_INTERPRETER) return null
        if (mode == MODE_ADAPTIVE && ++cb.invocationCount < agent.config.jitThreshold) return null
        synchronized(cb) {
            if (cb.compiled == null && !cb.jitFailed) {
                // code with strict regions inside sloppy code (CodeBlock.strictPcs) is rare: keep it interpreted
                val r = if (cb.strictPcs != null) null else JvmCompiler.compile(cb, agent.config.codeDefiner ?: CodeDefiners.default)
                if (r == null) cb.jitFailed = true else cb.compiled = r
            }
        }
        return cb.compiled as CompiledCode?
    }
}
