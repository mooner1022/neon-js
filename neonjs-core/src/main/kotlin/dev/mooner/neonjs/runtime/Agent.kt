package dev.mooner.neonjs.runtime

import dev.mooner.neonjs.compiler.RegExpSite
import dev.mooner.neonjs.vm.Frame

/** Engine-level configuration consulted by the runtime. */
class RuntimeConfig {
    /** Default locale for Intl (BCP 47 tag; null = the host default). */
    @JvmField var defaultLocale: String? = null
    /** Whether `Intl` is installed when the neonjs-intl module is available. */
    @JvmField var intl: Boolean = true
    /** Local time zone for Date / Temporal.Now (null = the host zone, see DateTime.hostZone). */
    @JvmField var timeZone: java.time.ZoneId? = null
    /** Maximum JS call depth before a RangeError is thrown. */
    @JvmField var maxCallDepth = 3000
    /** Execution time limit in milliseconds (0 = unlimited). */
    @JvmField var maxExecutionMillis = 0L
    /** Instruction budget (approximate, counted at loop back-edges and calls; 0 = unlimited). */
    @JvmField var maxStatements = 0L
    /** Rethrow engine-internal errors (debugging) instead of converting them to JS errors. */
    @JvmField var propagateInternalErrors = false
    /** Maximum string length / array length allowed (memory guard). */
    @JvmField var maxStringLength = Rope.MAX_LENGTH
    /** Enable Annex B legacy features (html comments, __proto__, escape, ...). */
    @JvmField var annexB = true
    /** Bytes the thread may allocate per top-level evaluation (0 = unlimited; HotSpot only). */
    @JvmField var maxAllocatedBytes = 0L
    /** Whether the limits above restart for each task [Agent.runJobs] runs, and do not run while it waits for one. */
    @JvmField var limitsPerTask = false
    /** Whether eval / Function constructors may compile code at runtime. */
    @JvmField var allowCodeGeneration = true
    /** dev.mooner.neonjs.ExecutionMode ordinal: 0 interpreter, 1 compiled, 2 adaptive. */
    @JvmField var executionMode = 0
    @JvmField var jitThreshold = 1000
    /** Whether code is compiled by background threads (dev.mooner.neonjs.jit.JitQueue) rather than the calling thread. */
    @JvmField var backgroundJit = true
    /** Defines generated classes (JIT code, Java.extend adapters); null = dev.mooner.neonjs.jit.CodeDefiners.default. */
    @JvmField var codeDefiner: dev.mooner.neonjs.jit.CodeDefiner? = null
}

/** Module loader hooks used by import() and import.meta. */
interface ModuleLoaderHooks {
    fun importMeta(f: Frame): JSObject
    fun dynamicImport(f: Frame, specifier: Any?, options: Any?): Any?
}

/**
 * Agent: per-thread execution state of one engine context (job queue, call depth, current realm, interrupts).
 * Not thread-safe; a context must be used from one thread at a time. Exception: the external-job API
 * ([postExternalJob], [addExternalSource], [removeExternalSource], [closeExternal]) may be called from any thread.
 */
class Agent(@JvmField val config: RuntimeConfig = RuntimeConfig()) {
    @JvmField var currentRealm: Realm? = null
    @JvmField var depth = 0
    @JvmField var maxDepth = config.maxCallDepth
    @JvmField var topFrame: Frame? = null
    /**
     * Set by compiled code's element fast paths (JitRt.elemNumI and the like) when they do not apply; the generated code
     * clears it and takes the full [[Get]]. A stale true only costs a slow path.
     */
    @JvmField var elemMiss = false
    @JvmField val jobs = ArrayDeque<Runnable>()
    @JvmField val symbolRegistry = HashMap<String, JSSymbol>()

    @Volatile @JvmField var interruptRequested = false
    /** Calls left before the next interrupt check at function entry. */
    @JvmField var callBudget = 1024
    @JvmField var deadlineNanos = 0L
    @JvmField var statementsLeft = Long.MAX_VALUE

    /** Hook for `debugger` statements. */
    @JvmField var debuggerHook: ((Frame) -> Unit)? = null

    /** Creates RegExp objects for literals (installed by the RegExp builtin). */
    @JvmField var regexpFactory: (Realm, RegExpSite) -> JSObject = { _, _ -> throw JSException.syntaxError("RegExp not supported") }

    @JvmField var moduleLoader: ModuleLoaderHooks = object : ModuleLoaderHooks {
        override fun importMeta(f: Frame): JSObject = dev.mooner.neonjs.vm.Modules.importMeta(f)
        override fun dynamicImport(f: Frame, specifier: Any?, options: Any?): Any? = dev.mooner.neonjs.vm.Modules.dynamicImport(f, specifier, options)
    }

    /** Converts host exceptions to JS values (installed by the interop layer). */
    @JvmField var hostExceptionHandler: ((dev.mooner.neonjs.vm.HostException, Realm) -> Any?)? = null

    /** Weak references kept alive until the end of the current job (WeakRef semantics). */
    @JvmField val keptAlive = ArrayList<Any>()

    /** Host hook invoked for unhandled promise rejections. */
    @JvmField var rejectionTracker: ((JSObject, Boolean) -> Unit)? = null

    /** Receives what jobs leave uncaught. */
    interface UncaughtSink {
        /** An exception a job threw: a [JSException] or a [dev.mooner.neonjs.vm.HostException]. */
        fun exception(e: RuntimeException)

        /** A promise still rejected without a handler at the end of a microtask checkpoint. */
        fun rejection(p: dev.mooner.neonjs.vm.JSPromise)
    }

    /**
     * Where exceptions thrown by jobs go (the job ends, the others run) and promises rejected without a handler are
     * reported. Null: an exception ends [runJobs], leaving the rest queued, and rejections are not tracked.
     */
    @JvmField var uncaught: UncaughtSink? = null

    /** Promises rejected without a handler during the current microtask checkpoint (with [uncaught] only). */
    private val unhandledRejections = LinkedHashSet<dev.mooner.neonjs.vm.JSPromise>()

    /** HostPromiseRejectionTracker: [handled] false when [p] is rejected with no handler, true when one is added later. */
    fun trackRejection(p: dev.mooner.neonjs.vm.JSPromise, handled: Boolean) {
        rejectionTracker?.invoke(p, handled)
        if (uncaught == null) return
        if (handled) unhandledRejections.remove(p) else unhandledRejections.add(p)
    }

    /** Deterministic random source (sandbox), or null for ThreadLocalRandom. */
    @JvmField var randomSource: java.util.Random? = null
    /** Clock override for Date.now (sandbox), or null for the system clock. */
    @JvmField var clock: (() -> Double)? = null
    private var allocationBase = 0L

    fun nextRandom(): Double = randomSource?.nextDouble() ?: java.util.concurrent.ThreadLocalRandom.current().nextDouble()
    fun currentTimeMillis(): Double = clock?.invoke() ?: System.currentTimeMillis().toDouble()

    fun checkStringLength(len: Long) {
        if (len > config.maxStringLength) throw JSException.rangeError("Invalid string length")
        if (len > 1 shl 20) reserveAllocation(len * 2)
    }

    /**
     * Charges a large allocation of about [bytes] against the allocation budget *before* it happens. The budget is
     * otherwise only sampled periodically, so a few huge allocations between two checks could exhaust the heap.
     */
    fun reserveAllocation(bytes: Long) {
        if (bytes < 65536 || config.maxAllocatedBytes <= 0 || threadMX == null) return
        if (threadAllocatedBytes() - allocationBase + bytes > config.maxAllocatedBytes) throw ResourceLimitException("Memory allocation limit exceeded")
    }

    fun hostExceptionToJS(e: dev.mooner.neonjs.vm.HostException, realm: Realm): Any? {
        val h = hostExceptionHandler
        if (h != null) return h(e, realm)
        val cause = e.hostCause
        return realm.newError(ErrorKind.ERROR, "${cause.javaClass.name}: ${cause.message}")
    }

    fun onDebugger(f: Frame) {
        debuggerHook?.invoke(f)
    }

    fun startLimits() {
        interruptRequested = false
        restartLimits()
    }

    /** Gives what runs next the whole time, instruction and allocation budget, keeping a requested interrupt. */
    fun restartLimits() {
        deadlineNanos = if (config.maxExecutionMillis > 0) System.nanoTime() + config.maxExecutionMillis * 1_000_000 else 0L
        statementsLeft = if (config.maxStatements > 0) config.maxStatements else Long.MAX_VALUE
        if (config.maxAllocatedBytes > 0) allocationBase = threadAllocatedBytes()
    }

    /**
     * The state of the limits, for a thread that lets others evaluate meanwhile ([startLimits] replaces it; the
     * allocation base is that of the thread that started them).
     */
    fun saveLimits(): LongArray = longArrayOf(deadlineNanos, statementsLeft, allocationBase)

    fun restoreLimits(saved: LongArray) {
        deadlineNanos = saved[0]
        statementsLeft = saved[1]
        allocationBase = saved[2]
    }

    private val interrupts = java.util.concurrent.atomic.AtomicLong()

    /** How many interrupts were requested: a change shows one even after [startLimits] cleared [interruptRequested]. */
    val interruptCount: Long get() = interrupts.get()

    /** Sets [interruptRequested]; callable from any thread. */
    fun requestInterrupt() {
        interruptRequested = true
        interrupts.incrementAndGet()
    }

    private fun threadAllocatedBytes(): Long {
        val mx = threadMX ?: return 0L
        @Suppress("DEPRECATION") // threadId() needs JDK 19 / Android API 36
        return mx.getThreadAllocatedBytes(Thread.currentThread().id)
    }

    /** Called periodically from loops; throws a [TerminationException] when limits are exceeded. */
    fun checkInterrupt() {
        // sticky until the next top-level evaluation (startLimits), so host code swallowing the exception inside a
        // callback cannot cancel the interrupt
        if (interruptRequested) throw InterruptedExecutionException("Execution interrupted")
        if (deadlineNanos != 0L && System.nanoTime() > deadlineNanos) throw ExecutionTimeoutException("Execution time limit exceeded")
        statementsLeft -= 4096
        if (statementsLeft < 0) throw ResourceLimitException("Instruction limit exceeded")
        if (config.maxAllocatedBytes > 0 && threadMX != null) {
            if (threadAllocatedBytes() - allocationBase > config.maxAllocatedBytes) throw ResourceLimitException("Memory allocation limit exceeded")
        }
    }

    /** The checks of [checkInterrupt] that apply while blocked waiting (no instruction / allocation accounting). */
    fun checkWaitLimits() {
        if (interruptRequested) throw InterruptedExecutionException("Execution interrupted")
        if (deadlineNanos != 0L && System.nanoTime() > deadlineNanos) throw ExecutionTimeoutException("Execution time limit exceeded")
    }

    fun enqueueJob(job: Runnable) {
        jobs.addLast(job)
    }

    /** Jobs that run before the pending jobs at each microtask checkpoint (Node's `process.nextTick`). */
    private val ticks = ArrayDeque<Runnable>()

    /**
     * Queues [job] in the tick lane: at a microtask checkpoint the ticks run first, then the pending jobs, then the
     * ticks those queued, and so on until both are empty (the order of Node's `process.nextTick`).
     */
    fun enqueueTick(job: Runnable) {
        ticks.addLast(job)
    }

    // ------------------------------------------------------------------ external jobs
    //
    // Jobs originating outside this agent's thread, e.g. an Atomics.waitAsync waiter resolved by Atomics.notify on
    // another agent or by its timeout timer. Other threads only ever *post* such a job; the thread running [runJobs]
    // takes it from the queue and runs it there (under the context lock), so no foreign thread runs guest code. Each
    // external job is a task of the event loop: [runJobs] runs the microtasks it queues before the next task. While an
    // [ExternalSource] is registered the agent still expects an external job, and a host event loop should keep
    // waiting ([hasPendingExternal] / [awaitExternal]) instead of concluding that nothing is pending.

    /** A registered producer of a future external job (e.g. a pending Atomics.waitAsync waiter). */
    fun interface ExternalSource {
        /** Withdraws the source (cancels timers, unlinks waiters); called by [closeExternal], from any thread. */
        fun cancel()
    }

    /**
     * Guards [externalJobs]. [externalPosted] is signalled when a job is posted, when a source goes (a thread waiting
     * for one may find nothing pending any more) and when the queue is closed.
     */
    private val externalLock = java.util.concurrent.locks.ReentrantLock()
    private val externalPosted = externalLock.newCondition()
    private val externalJobs = ArrayDeque<Runnable>()
    /** The size of [externalJobs], readable without the lock. */
    @Volatile private var externalCount = 0
    private val externalSources: MutableSet<ExternalSource> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** Sources that do not keep an event loop waiting (an unref'd timer): only [closeExternal] needs them. */
    private val idleSources: MutableSet<ExternalSource> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    @Volatile private var externalClosed = false

    /**
     * Registers [s] as pending; false after [closeExternal]. With [keepsAlive] false the source does not count as
     * pending work ([hasPendingExternal]): an event loop may end before its job comes, which then waits in the queue
     * for the next run of jobs. Thread-safe.
     */
    fun addExternalSource(s: ExternalSource, keepsAlive: Boolean = true): Boolean {
        if (externalClosed) return false
        val set = if (keepsAlive) externalSources else idleSources
        set.add(s)
        if (externalClosed) {
            set.remove(s)
            return false
        }
        return true
    }

    /**
     * Makes a registered source keep an event loop waiting or not (Node's `ref()` / `unref()`); no effect on a source
     * that is not registered. Thread-safe.
     */
    fun setKeepsAlive(s: ExternalSource, keepsAlive: Boolean) {
        if (externalClosed) return
        if (keepsAlive) {
            if (idleSources.remove(s)) externalSources.add(s)
            return
        }
        if (!externalSources.remove(s)) return
        idleSources.add(s)
        if (externalSources.isEmpty()) {
            externalLock.lock()
            try {
                externalPosted.signalAll()
            } finally {
                externalLock.unlock()
            }
        }
    }

    /** Unregisters [s] (its job has run, or it was withdrawn). Thread-safe. */
    fun removeExternalSource(s: ExternalSource) {
        if (idleSources.remove(s)) return
        if (!externalSources.remove(s) || externalSources.isNotEmpty()) return
        externalLock.lock()
        try {
            externalPosted.signalAll()
        } finally {
            externalLock.unlock()
        }
    }

    /** Number of registered external sources (used to cap per-agent host resources). */
    val externalSourceCount: Int get() = externalSources.size + idleSources.size

    /** Posts [job] to run on the owner thread at its next [runJobs]; dropped after [closeExternal]. Thread-safe. */
    fun postExternalJob(job: Runnable) {
        externalLock.lock()
        try {
            if (externalClosed) return
            externalJobs.addLast(job)
            externalCount = externalJobs.size
            externalPosted.signalAll()
        } finally {
            externalLock.unlock()
        }
    }

    /** True while posted external jobs await the owner thread or registered sources may still post one. */
    fun hasPendingExternal(): Boolean = externalCount > 0 || externalSources.isNotEmpty()

    /** The next posted external job, or null (owner thread). */
    private fun pollExternal(): Runnable? {
        if (externalCount == 0) return null
        externalLock.lock()
        try {
            val j = externalJobs.removeFirstOrNull()
            externalCount = externalJobs.size
            return j
        } finally {
            externalLock.unlock()
        }
    }

    /**
     * Blocks the owner thread until an external job has been posted (true; [runJobs] runs it) or [timeoutMillis]
     * elapse (false). Waits in slices of at most 50 ms and checks [checkWaitLimits] between slices, so an interrupt or
     * the execution deadline still terminates the wait (with [RuntimeConfig.limitsPerTask], only an interrupt: the
     * wait is no task's); a Java interrupt of the thread ends it as an interrupted execution.
     */
    fun awaitExternal(timeoutMillis: Long): Boolean {
        val end = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(maxOf(timeoutMillis, 0L))
        while (true) {
            if (externalCount > 0) return true
            if (!config.limitsPerTask) checkWaitLimits()
            else if (interruptRequested) throw InterruptedExecutionException("Execution interrupted")
            val remaining = end - System.nanoTime()
            if (remaining <= 0) return false
            if (waitForExternal(minOf(remaining, WAIT_SLICE_NANOS))) return true
        }
    }

    /**
     * Waits at most [nanos] for an external job to be posted (less when woken otherwise); true if one is queued.
     * Reads no other state of the agent, so it may be called without the context lock. A Java interrupt of the
     * thread ends it as an interrupted execution.
     */
    fun waitForExternal(nanos: Long): Boolean {
        externalLock.lock()
        try {
            if (externalJobs.isEmpty() && !externalClosed && nanos > 0) externalPosted.awaitNanos(nanos)
            return externalJobs.isNotEmpty()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedExecutionException("Execution interrupted")
        } finally {
            externalLock.unlock()
        }
    }

    /**
     * Withdraws all external sources and drops queued external jobs; later posts are ignored. Called when the agent's
     * context is closed or its host is done with it, so pending waiters and their timers do not outlive it. Thread-safe.
     */
    fun closeExternal() {
        externalLock.lock()
        try {
            externalClosed = true
            externalJobs.clear()
            externalCount = 0
            externalPosted.signalAll()
        } finally {
            externalLock.unlock()
        }
        for (s in externalSources.toList() + idleSources.toList()) {
            externalSources.remove(s)
            idleSources.remove(s)
            try {
                s.cancel()
            } catch (_: RuntimeException) {
                // a failing withdrawal must not prevent closing the others
            }
        }
    }

    /**
     * Runs what is ready, as turns of an event loop: the pending jobs (microtasks), then each posted external job as a
     * task followed by the microtasks it queued, until neither is left. With [RuntimeConfig.limitsPerTask] each task
     * starts with the whole budget of [restartLimits]. A job that throws ends the call, leaving the rest queued.
     */
    fun runJobs() {
        try {
            runMicrotasks()
            while (true) {
                val task = pollExternal() ?: return
                if (config.limitsPerTask) restartLimits()
                if (interruptRequested) throw InterruptedExecutionException("Execution interrupted")
                if (--callBudget < 0) {
                    callBudget = 1024
                    checkInterrupt()
                }
                runJob(task)
                runMicrotasks()
            }
        } finally {
            keptAlive.clear()
        }
    }

    /**
     * Runs jobs until the queue is empty (a microtask checkpoint); WeakRef targets are kept alive until its end, and
     * then the promises it left rejected without a handler are reported.
     */
    private fun runMicrotasks() {
        while (true) {
            while (true) {
                val t = ticks.removeFirstOrNull() ?: break
                if (--callBudget < 0) {
                    callBudget = 1024
                    checkInterrupt()
                }
                runJob(t)
            }
            if (jobs.isEmpty()) break
            while (true) {
                val j = jobs.removeFirstOrNull() ?: break
                if (--callBudget < 0) {
                    callBudget = 1024
                    checkInterrupt()
                }
                runJob(j)
            }
            if (ticks.isEmpty()) break
        }
        keptAlive.clear()
        if (unhandledRejections.isNotEmpty()) reportRejections()
    }

    private fun runJob(j: Runnable) {
        val sink = uncaught
        if (sink == null) {
            j.run()
            return
        }
        try {
            j.run()
        } catch (e: JSException) {
            sink.exception(e)
        } catch (e: dev.mooner.neonjs.vm.HostException) {
            sink.exception(e)
        }
    }

    private fun reportRejections() {
        val rejected = unhandledRejections.toList()
        unhandledRejections.clear()
        val sink = uncaught ?: return
        for (p in rejected) if (!p.isHandled) sink.rejection(p)
    }

    /** Captures a JS stack trace string from the active interpreter frames. */
    fun captureStack(): String = captureStack(null, 50)

    /**
     * A JS stack trace of at most [limit] frames. With [skipThrough], the frames down to the call of that function are
     * left out (V8's `Error.captureStackTrace(target, constructorOpt)`), and the trace is empty when it is not on the
     * stack.
     */
    fun captureStack(skipThrough: JSObject?, limit: Int): String {
        val sb = StringBuilder()
        var skipping = skipThrough != null
        var f = topFrame
        var n = 0
        while (f != null && n < limit) {
            // a call compiled code runs inlined in this frame is a frame of its own here
            val inl = f.inlineFn
            if (inl != null) {
                if (skipping) {
                    if (inl === skipThrough) skipping = false
                } else {
                    appendFrame(sb, inl.debugName().ifEmpty { "<anonymous>" }, inl.code, f.inlinePc)
                    if (++n >= limit) break
                }
            }
            if (skipping) {
                if (f.fn != null && f.fn === skipThrough) skipping = false
            } else {
                appendFrame(sb, f.fn?.debugName()?.ifEmpty { "<anonymous>" } ?: f.code.name, f.code, f.pc)
                n++
            }
            f = f.parent
        }
        return sb.toString().trimEnd()
    }

    private fun appendFrame(sb: StringBuilder, name: String, code: dev.mooner.neonjs.compiler.CodeBlock, pc: Int) {
        val pos = code.positionAt(pc)
        val src = code.source
        sb.append("    at ").append(name)
        if (src != null && pos >= 0) {
            val (l, c) = src.lineCol(pos)
            sb.append(" (").append(src.name).append(':').append(l).append(':').append(c).append(')')
        }
        sb.append('\n')
    }

    inline fun <R> enter(block: () -> R): R {
        val prev = current.get()
        current.set(this)
        try {
            return block()
        } finally {
            current.set(prev)
        }
    }

    companion object {
        @JvmField val current = ThreadLocal<Agent>()

        /** Longest uninterrupted wait for an external job ([awaitExternal]) between interrupt / deadline checks. */
        @JvmField val WAIT_SLICE_NANOS = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(50)

        private val threadMX: com.sun.management.ThreadMXBean? = try {
            (java.lang.management.ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean)?.also {
                if (it.isThreadAllocatedMemorySupported && !it.isThreadAllocatedMemoryEnabled) it.isThreadAllocatedMemoryEnabled = true
            }
        } catch (_: Throwable) {
            null
        }

        /**
         * Classes used on error paths are initialized eagerly. A class whose static initializer first runs while the
         * thread stack is nearly exhausted (e.g. deep recursion ending in StackOverflowError) fails with an error
         * and stays unusable for the whole JVM ("NoClassDefFoundError: Could not initialize class"), which would let
         * one script break every context.
         */
        init {
            val loader = Agent::class.java.classLoader
            for (name in listOf(
                "dev.mooner.neonjs.runtime.JSException", "dev.mooner.neonjs.runtime.JSErrorObject", "dev.mooner.neonjs.runtime.ErrorKind",
                "dev.mooner.neonjs.runtime.TerminationException", "dev.mooner.neonjs.runtime.ExecutionTimeoutException",
                "dev.mooner.neonjs.runtime.ResourceLimitException", "dev.mooner.neonjs.runtime.InterruptedExecutionException",
                "dev.mooner.neonjs.runtime.PropertyDescriptor", "dev.mooner.neonjs.runtime.Ops", "dev.mooner.neonjs.runtime.NumberConv",
                "dev.mooner.neonjs.runtime.PK", "dev.mooner.neonjs.runtime.Shape", "dev.mooner.neonjs.runtime.PropCache", "dev.mooner.neonjs.runtime.GlobalCache",
                "dev.mooner.neonjs.vm.Interpreter", "dev.mooner.neonjs.vm.Rt", "dev.mooner.neonjs.vm.Frame", "dev.mooner.neonjs.vm.DerivedResult",
                "dev.mooner.neonjs.vm.HostException", "dev.mooner.neonjs.vm.Iteration", "dev.mooner.neonjs.vm.Promises", "dev.mooner.neonjs.vm.Generators",
                "dev.mooner.neonjs.vm.Disposal", "dev.mooner.neonjs.jit.JitRt", "dev.mooner.neonjs.builtins.ErrorBuiltins",
            )) {
                try {
                    Class.forName(name, true, loader)
                } catch (_: ClassNotFoundException) {
                    // optional / renamed class
                }
            }
        }

        @JvmStatic fun current(): Agent = current.get() ?: throw IllegalStateException("No active NeonJS context on this thread")
        @JvmStatic fun currentRealm(): Realm = current().currentRealm ?: throw IllegalStateException("No current realm")
        @JvmStatic fun currentRealmOrNull(): Realm? = current.get()?.currentRealm
    }
}
