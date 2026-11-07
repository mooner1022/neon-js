package dev.mooner.neonjs.jit

import dev.mooner.neonjs.compiler.CodeUnit
import dev.mooner.neonjs.runtime.VmAgent
import dev.mooner.neonjs.vm.VmFrame

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
    fun prepare(cb: CodeUnit, agent: VmAgent): CompiledCode? {
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

    /**
     * Back-edges of an interpreted loop between two calls of [loop] (Interpreter: also the period of its interrupt
     * checks). `-Dneonjs.jit.osrStress` makes it 1 and compiles on the calling thread at the first one, so every loop
     * that runs is entered compiled after its first iteration (for tests).
     */
    @JvmField val OSR_STRESS = System.getProperty("neonjs.jit.osrStress") != null

    /**
     * Testing deoptimization: with `-Dneonjs.jit.deoptStress=N` (N > 0; 3 without a value), code compiled from then on
     * has exits at its start, its loop headers, the start and loop headers of the bodies it inlines, and after each
     * inlined call, and one in N exits reached leaves the activation to the interpreter there. Part of the class
     * identity; tests change it between compilations.
     */
    @JvmField @Volatile var DEOPT_STRESS: Int = System.getProperty("neonjs.jit.deoptStress")?.let { it.toIntOrNull() ?: 3 } ?: 0
    @JvmField val LOOP_CHECK = if (OSR_STRESS) 1 else 4096

    /**
     * An interpreted loop of [cb] made [LOOP_CHECK] more iterations. Credits them toward compiling the block (adaptive
     * mode: 64 per period, so a function whose time goes into its loops is compiled after about 64K iterations even
     * when it is called only a few times), and queues the block once due rather than at its next call, so its code is
     * ready while the loop still runs. Returns whether compiled code is installed that the loop can continue in
     * ([osr]).
     *
     * Only blocks that calls and scripts compile anyway are compiled here: functions and classic scripts. Eval code and
     * module bodies stay interpreted, as before.
     */
    @JvmStatic
    fun loop(cb: CodeUnit, agent: VmAgent): Boolean {
        val config = agent.config
        if (cb.compiled != null) return config.executionMode != MODE_INTERPRETER
        cb.invocationCount += 64
        if (config.executionMode != MODE_ADAPTIVE || cb.jitFailed || cb.jitTask != null) return false
        if (cb.invocationCount < config.jitThreshold && !OSR_STRESS) return false
        if (!dev.mooner.neonjs.jit.opt.Dialects.of(cb).compilesAtLoops(cb)) return false
        if (!compilable(cb)) return false
        if (config.backgroundJit && !OSR_STRESS && JitQueue.submit(cb, backend(agent), agent)) return false
        return compileNow(cb, agent) != null
    }

    /**
     * On-stack replacement: continues interpreted frame [f] in its code unit's compiled code, at the loop header
     * [VmFrame.pc] with the registers in [VmFrame.slots] and an empty operand stack (see [CompiledCode]). Returns the
     * frame's result, or [VmRt.NO_OSR] (nothing done) when the code has no entry there. Called by the interpreter
     * outside its exception dispatch: exceptions leave the frame as they leave compiled code (Dialect.leaveCompiled).
     */
    @JvmStatic
    fun osr(f: VmFrame): Any? {
        val unit = f.unit
        val c = unit.compiled as CompiledCode
        var r = if (c is dev.mooner.neonjs.jit.opt.T2Code && !c.hasEntry(f.pc)) VmRt.NO_OSR else enter(c, f)
        // the optimizing tier's code declines a frame whose registers lack the kinds it speculated on (it changed
        // nothing then): the baseline, which takes any, continues the loop, and the tier's code is not tried there again
        if (r === VmRt.NO_OSR && c is dev.mooner.neonjs.jit.opt.T2Code) {
            c.declined(f.pc)
            val b = unit.baseline as CompiledCode?
            if (b != null && b !== c) r = enter(b, f)
        }
        (if (r === VmRt.NO_OSR) osrMissed else osrEntered).incrementAndGet()
        return r
    }

    private fun enter(c: CompiledCode, f: VmFrame): Any? = try {
        c.run(f)
    } catch (e: Throwable) {
        osrEntered.incrementAndGet()
        throw dev.mooner.neonjs.jit.opt.Dialects.of(f.unit).leaveCompiled(e, f)
    }

    /** Loops continued in compiled code ([osr]), and attempts at a pc the code has no entry at. */
    @JvmField val osrEntered = java.util.concurrent.atomic.AtomicLong()
    @JvmField val osrMissed = java.util.concurrent.atomic.AtomicLong()

    /**
     * Makes [code], a later version of [cb]'s code (the optimizing tier's), the code new activations of [cb] run
     * (docs/OPTIMIZING_TIER.md, "Code versions"). Like the baseline it runs frames made for compiled code from the start
     * and answers on-stack replacement. Returns false, installing nothing, while the block has no baseline. Activations
     * already running keep the version they started with.
     */
    @JvmStatic
    fun install(cb: CodeUnit, code: CompiledCode): Boolean {
        if (cb.baseline == null) return false
        cb.compiled = code
        return true
    }

    /** Makes [cb]'s baseline the code new activations run again, if [code] is the one they run now. */
    @JvmStatic
    fun revert(cb: CodeUnit, code: CompiledCode) {
        val b = cb.baseline ?: return
        COMPILED.compareAndSet(cb, code, b)
    }

    private val COMPILED: java.util.concurrent.atomic.AtomicReferenceFieldUpdater<CodeUnit, Any> =
        java.util.concurrent.atomic.AtomicReferenceFieldUpdater.newUpdater(CodeUnit::class.java, Any::class.java, "compiled")

    /** Queues [cb], code created at run time (the `Function` constructor), in compiled mode: it is about to be called. */
    @JvmStatic
    fun prefetch(cb: CodeUnit, agent: VmAgent) {
        val config = agent.config
        if (config.executionMode == MODE_COMPILED && config.backgroundJit && compilable(cb)) JitQueue.submit(cb, backend(agent), agent)
    }

    /** The backend compiling for [agent]: JVM classes defined by its code definer. */
    @JvmStatic
    fun backend(agent: VmAgent): JitBackend = ClassBackend(agent.config.codeDefiner ?: CodeDefiners.default, agent.config.tier2)

    /** In compiled mode, queues the functions [cb] defines (once per block; not for blocks compiled in advance). */
    private fun queueChildren(cb: CodeUnit, agent: VmAgent) {
        cb.childrenQueued = true
        val config = agent.config
        if (config.executionMode != MODE_COMPILED || !config.backgroundJit) return
        var backend: JitBackend? = null
        for (k in cb.constants) {
            if (k is CodeUnit && compilable(k)) JitQueue.submit(k, backend ?: backend(agent).also { backend = it }, agent)
        }
    }

    private fun compilable(cb: CodeUnit): Boolean =
        cb.compiled == null && !cb.jitFailed && cb.jitTask == null && canCompile(cb)

    /** Whether some tier compiles [cb], as its language says (Dialect.canCompile). */
    @JvmStatic
    fun canCompile(cb: CodeUnit): Boolean = dev.mooner.neonjs.jit.opt.Dialects.of(cb).canCompile(cb)

    /** Compiled code for [cb] now: compiled on this thread, taken over from the queue, or awaited from a worker. */
    private fun compileNow(cb: CodeUnit, agent: VmAgent): CompiledCode? {
        if (!canCompile(cb)) {
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
