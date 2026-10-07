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
        deadlineNanos = if (config.maxExecutionMillis > 0) System.nanoTime() + config.maxExecutionMillis * 1_000_000 else 0L
        statementsLeft = if (config.maxStatements > 0) config.maxStatements else Long.MAX_VALUE
        interruptRequested = false
        if (config.maxAllocatedBytes > 0) allocationBase = threadAllocatedBytes()
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

    // ------------------------------------------------------------------ external jobs
    //
    // Jobs originating outside this agent's thread, e.g. an Atomics.waitAsync waiter resolved by Atomics.notify on
    // another agent or by its timeout timer. Other threads only ever *post* such a job; the owner thread moves it into
    // [jobs] in [runJobs] / [awaitExternal] and runs it there (under the context lock), so no foreign thread runs guest
    // code. While an [ExternalSource] is registered the agent still expects an external job, and a host event loop
    // should keep waiting ([hasPendingExternal] / [awaitExternal]) instead of concluding that nothing is pending.

    /** A registered producer of a future external job (e.g. a pending Atomics.waitAsync waiter). */
    fun interface ExternalSource {
        /** Withdraws the source (cancels timers, unlinks waiters); called by [closeExternal], from any thread. */
        fun cancel()
    }

    private val externalJobs = java.util.concurrent.LinkedBlockingQueue<Runnable>()
    private val externalSources: MutableSet<ExternalSource> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    @Volatile private var externalClosed = false

    /** Registers [s] as pending; false after [closeExternal]. Thread-safe. */
    fun addExternalSource(s: ExternalSource): Boolean {
        if (externalClosed) return false
        externalSources.add(s)
        if (externalClosed) {
            externalSources.remove(s)
            return false
        }
        return true
    }

    /** Unregisters [s] (its job has run, or it was withdrawn). Thread-safe. */
    fun removeExternalSource(s: ExternalSource) {
        externalSources.remove(s)
    }

    /** Number of registered external sources (used to cap per-agent host resources). */
    val externalSourceCount: Int get() = externalSources.size

    /** Posts [job] to run on the owner thread at its next [runJobs]; dropped after [closeExternal]. Thread-safe. */
    fun postExternalJob(job: Runnable) {
        if (!externalClosed) externalJobs.add(job)
    }

    /** True while posted external jobs await the owner thread or registered sources may still post one. */
    fun hasPendingExternal(): Boolean = !externalJobs.isEmpty() || externalSources.isNotEmpty()

    /** Moves posted external jobs to the end of [jobs] (owner thread). */
    private fun drainExternal(): Boolean {
        var moved = false
        while (true) {
            val j = externalJobs.poll() ?: return moved
            jobs.addLast(j)
            moved = true
        }
    }

    /**
     * Blocks the owner thread until an external job is posted (it is moved into [jobs]; returns true) or [timeoutMillis]
     * elapse (false). Waits in slices of at most 50 ms and calls [checkInterrupt] between slices, so an interrupt or the
     * execution deadline still terminates the wait; a Java interrupt of the thread ends it as an interrupted execution.
     */
    fun awaitExternal(timeoutMillis: Long): Boolean {
        val end = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(maxOf(timeoutMillis, 0L))
        while (true) {
            if (drainExternal()) return true
            checkWaitLimits()
            val remaining = end - System.nanoTime()
            if (remaining <= 0) return false
            val j = try {
                externalJobs.poll(minOf(remaining, EXTERNAL_WAIT_SLICE_NANOS), java.util.concurrent.TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedExecutionException("Execution interrupted")
            }
            if (j != null) {
                jobs.addLast(j)
                drainExternal()
                return true
            }
        }
    }

    /**
     * Withdraws all external sources and drops queued external jobs; later posts are ignored. Called when the agent's
     * context is closed or its host is done with it, so pending waiters and their timers do not outlive it. Thread-safe.
     */
    fun closeExternal() {
        externalClosed = true
        for (s in externalSources.toList()) {
            externalSources.remove(s)
            try {
                s.cancel()
            } catch (_: RuntimeException) {
                // a failing withdrawal must not prevent closing the others
            }
        }
        externalJobs.clear()
    }

    /** Runs pending jobs (microtasks), including posted external jobs, until the queue is empty. */
    fun runJobs() {
        while (true) {
            if (!externalJobs.isEmpty()) drainExternal()
            val j = jobs.removeFirstOrNull() ?: break
            if (--callBudget < 0) {
                callBudget = 1024
                checkInterrupt()
            }
            j.run()
        }
        keptAlive.clear()
    }

    /** Captures a JS stack trace string from the active interpreter frames. */
    fun captureStack(): String {
        val sb = StringBuilder()
        var f = topFrame
        var n = 0
        while (f != null && n < 50) {
            val code = f.code
            val name = f.fn?.debugName()?.ifEmpty { "<anonymous>" } ?: code.name
            val pos = code.positionAt(f.pc)
            val src = code.source
            sb.append("    at ").append(name)
            if (src != null && pos >= 0) {
                val (l, c) = src.lineCol(pos)
                sb.append(" (").append(src.name).append(':').append(l).append(':').append(c).append(')')
            }
            sb.append('\n')
            f = f.parent
            n++
        }
        return sb.toString().trimEnd()
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

        /** Longest uninterrupted block in [awaitExternal] between interrupt / deadline checks. */
        private val EXTERNAL_WAIT_SLICE_NANOS = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(50)

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
