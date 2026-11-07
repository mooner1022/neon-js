package dev.mooner.neonjs.runtime

/**
 * What the execution core reads of an engine's configuration, whatever the language (docs/IR_NEUTRALITY.md §10): the
 * limits of an execution and the JIT's settings. JavaScript's [RuntimeConfig] extends it.
 *
 * (Part of the core's interface for languages: public for NeonJS's language modules, not a stable API.)
 */
open class VmConfig {
    /** Maximum call depth before the language's stack overflow error is thrown. */
    @JvmField var maxCallDepth = 3000
    /** Execution time limit in milliseconds (0 = unlimited). */
    @JvmField var maxExecutionMillis = 0L
    /** Instruction budget (approximate, counted at loop back-edges and calls; 0 = unlimited). */
    @JvmField var maxStatements = 0L
    /** Bytes the thread may allocate per top-level evaluation (0 = unlimited; HotSpot only). */
    @JvmField var maxAllocatedBytes = 0L
    /** dev.mooner.neonjs.ExecutionMode ordinal: 0 interpreter, 1 compiled, 2 adaptive (dev.mooner.neonjs.jit.Jit). */
    @JvmField var executionMode = 0
    @JvmField var jitThreshold = 1000
    /**
     * Whether the optimizing tier is on (docs/OPTIMIZING_TIER.md; NeonEngine.Builder.optimizingTier, or
     * `-Dneonjs.jit.tier2=true`): an interpreter may record value profiles in adaptive mode, and each block whose
     * baseline is installed is compiled again by the tier in the background (JitQueue).
     */
    @JvmField var tier2 = java.lang.Boolean.getBoolean("neonjs.jit.tier2")
    /** Whether code is compiled by background threads (dev.mooner.neonjs.jit.JitQueue) rather than the calling thread. */
    @JvmField var backgroundJit = true
    /** Defines generated classes (JIT code, adapters); null = dev.mooner.neonjs.jit.CodeDefiners.default. */
    @JvmField var codeDefiner: dev.mooner.neonjs.jit.CodeDefiner? = null
}

/**
 * What the execution core keeps of one thread's execution, whatever the language: the call depth, and the limits
 * (time, instructions, allocation) and interrupts that end an execution with a [TerminationException], checked at
 * calls and loops' back edges ([checkInterrupt]). JavaScript's [Agent] extends it. Not thread-safe but for
 * [requestInterrupt].
 *
 * (Part of the core's interface for languages: public for NeonJS's language modules, not a stable API.)
 */
open class VmAgent(config: VmConfig) {
    open val config: VmConfig = config
    @JvmField var depth = 0
    @JvmField var maxDepth = config.maxCallDepth
    @Volatile @JvmField var interruptRequested = false
    /** Calls left before the next interrupt check at function entry. */
    @JvmField var callBudget = 1024
    @JvmField var deadlineNanos = 0L
    @JvmField var statementsLeft = Long.MAX_VALUE
    private var allocationBase = 0L

    /**
     * Charges a large allocation of about [bytes] against the allocation budget *before* it happens. The budget is
     * otherwise only sampled periodically, so a few huge allocations between two checks could exhaust the heap.
     */
    fun reserveAllocation(bytes: Long) {
        if (bytes < 65536 || config.maxAllocatedBytes <= 0 || threadMX == null) return
        if (threadAllocatedBytes() - allocationBase + bytes > config.maxAllocatedBytes) throw ResourceLimitException("Memory allocation limit exceeded")
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

    private companion object {
        val threadMX: com.sun.management.ThreadMXBean? = try {
            (java.lang.management.ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean)?.also {
                if (it.isThreadAllocatedMemorySupported && !it.isThreadAllocatedMemoryEnabled) it.isThreadAllocatedMemoryEnabled = true
            }
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * Thrown to terminate execution (sandbox limits, interrupts). Not catchable by the language's code: its catch/finally
 * handlers rethrow it without running.
 */
open class TerminationException(message: String) : RuntimeException(message)

class ExecutionTimeoutException(message: String) : TerminationException(message)
class ResourceLimitException(message: String) : TerminationException(message)
class InterruptedExecutionException(message: String) : TerminationException(message)
