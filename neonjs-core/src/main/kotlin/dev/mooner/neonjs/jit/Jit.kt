package dev.mooner.neonjs.jit

import dev.mooner.neonjs.compiler.CodeBlock
import dev.mooner.neonjs.runtime.Agent

/** Tier-up policy: decides when code blocks are compiled, and whether the caller waits for the code. */
object Jit {
    const val MODE_INTERPRETER = 0
    const val MODE_COMPILED = 1
    const val MODE_ADAPTIVE = 2

    /**
     * Returns compiled code for [cb] if available or due according to the agent's execution mode.
     *
     *  - Adaptive: after `jitThreshold` calls the block is queued for the background compiler ([JitQueue]) and keeps
     *    running in the interpreter until its code is installed. Without background compilation (or with the queue
     *    full) it is compiled on the calling thread, as before.
     *  - Compiled: the caller gets compiled code before the first call. It compiles the block itself, takes it over
     *    from the queue, or waits for the worker already compiling it; then the functions the block defines are
     *    queued, so they are usually compiled, in parallel, by the time they are called.
     */
    @JvmStatic
    fun prepare(cb: CodeBlock, agent: Agent): CompiledCode? {
        val c = cb.compiled
        if (c != null) {
            if (!cb.childrenQueued) queueChildren(cb, agent)
            return c as CompiledCode
        }
        if (cb.jitFailed) return null
        val config = agent.config
        when (config.executionMode) {
            MODE_INTERPRETER -> return null
            MODE_ADAPTIVE -> {
                if (++cb.invocationCount < config.jitThreshold) return null
                if (cb.jitTask != null) return null // queued or being compiled
                if (config.backgroundJit && compilable(cb) && JitQueue.submit(cb, backend(agent), agent)) return null
                return compileNow(cb, agent)
            }
            else -> {
                val r = compileNow(cb, agent)
                queueChildren(cb, agent)
                return r
            }
        }
    }

    /** Queues [cb], code created at run time (the `Function` constructor), in compiled mode: it is about to be called. */
    @JvmStatic
    fun prefetch(cb: CodeBlock, agent: Agent) {
        val config = agent.config
        if (config.executionMode == MODE_COMPILED && config.backgroundJit && compilable(cb)) JitQueue.submit(cb, backend(agent), agent)
    }

    /** The backend compiling for [agent]: JVM classes defined by its code definer. */
    @JvmStatic
    fun backend(agent: Agent): JitBackend = ClassBackend(agent.config.codeDefiner ?: CodeDefiners.default)

    /** In compiled mode, queues the functions [cb] defines (once per block; not for blocks compiled in advance). */
    private fun queueChildren(cb: CodeBlock, agent: Agent) {
        cb.childrenQueued = true
        val config = agent.config
        if (config.executionMode != MODE_COMPILED || !config.backgroundJit) return
        var backend: JitBackend? = null
        for (k in cb.constants) {
            if (k is CodeBlock && compilable(k)) JitQueue.submit(k, backend ?: backend(agent).also { backend = it }, agent)
        }
    }

    private fun compilable(cb: CodeBlock): Boolean =
        cb.compiled == null && !cb.jitFailed && cb.jitTask == null && cb.strictPcs == null && JvmCompiler.canCompile(cb)

    /** Compiled code for [cb] now: compiled on this thread, taken over from the queue, or awaited from a worker. */
    private fun compileNow(cb: CodeBlock, agent: Agent): CompiledCode? {
        // code with strict regions inside sloppy code (CodeBlock.strictPcs) is rare: keep it interpreted
        if (cb.strictPcs != null || !JvmCompiler.canCompile(cb)) {
            cb.jitFailed = true
            return null
        }
        while (true) {
            cb.compiled?.let { return it as CompiledCode }
            if (cb.jitFailed) return null
            val t = cb.jitTask as JitTask?
            if (t == null) {
                val own = JitTask(cb, backend(agent), agent, claimed = true)
                if (JitQueue.attach(own)) JitQueue.run(listOf(own))
            } else if (JitQueue.claim(t)) {
                JitQueue.run(listOf(t))
            } else {
                // a worker is compiling it; stay responsive to interrupts (the call then runs interpreted)
                try {
                    while (!t.await(20)) if (agent.interruptRequested) return null
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
    }
}
