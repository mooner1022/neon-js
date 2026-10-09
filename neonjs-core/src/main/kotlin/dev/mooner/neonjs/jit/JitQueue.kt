package dev.mooner.neonjs.jit

import dev.mooner.neonjs.compiler.CodeBlock
import dev.mooner.neonjs.runtime.Agent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater

/**
 * One code block's compilation, attached to the block ([CodeBlock.jitTask]) from the moment it is queued (or a thread
 * starts compiling it) until its code is installed. Exactly one thread compiles it: a background worker, or a thread
 * that needs the code now and takes it over from the queue ([JitQueue.claim]).
 */
class JitTask internal constructor(
    @JvmField val cb: CodeBlock,
    @JvmField val backend: JitBackend,
    /** The agent that queued the block; its queued tasks are withdrawn when its context closes. */
    @JvmField val owner: Agent?,
    claimed: Boolean,
) {
    internal val state = AtomicInteger(if (claimed) RUNNING else QUEUED)
    private val done = CountDownLatch(1)

    internal fun finish() = done.countDown()

    /** Waits up to [millis] for the code to be installed (or the task withdrawn); true if it was. */
    fun await(millis: Long): Boolean = done.await(millis, TimeUnit.MILLISECONDS)

    internal companion object {
        const val QUEUED = 0
        const val RUNNING = 1
        const val CANCELLED = 2
    }
}

/**
 * Background compilation: daemon worker threads take queued code blocks, as many as are waiting (up to a batch), and
 * compile them with one [JitBackend.compile] call per backend, so a batch shares the backend's fixed costs (one dex
 * file and class loader on Android). The JS thread keeps interpreting meanwhile (adaptive mode) or waits for its block
 * only (compiled mode, see [Jit.prepare]).
 *
 * System properties: `neonjs.jit.threads` (workers; 0 = no background compilation, everything is compiled on the
 * calling thread), `neonjs.jit.batch` (largest batch, default 32), `neonjs.jit.maxPending` (queued blocks beyond which
 * callers compile themselves, default 4096). Work done by the workers is not charged to any context's limits.
 */
object JitQueue {
    @JvmField val THREADS: Int = Integer.getInteger("neonjs.jit.threads", (Runtime.getRuntime().availableProcessors() / 4).coerceIn(1, 2))
    private val BATCH: Int = Integer.getInteger("neonjs.jit.batch", 32).coerceAtLeast(1)
    private val MAX_PENDING: Int = Integer.getInteger("neonjs.jit.maxPending", 4096)

    /** Batches compiled by the workers, the blocks in them, and queued blocks taken over by threads needing them. */
    @JvmField val batches = AtomicLong()
    @JvmField val batchedBlocks = AtomicLong()
    @JvmField val takenOver = AtomicLong()

    private val queue = LinkedBlockingQueue<JitTask>()
    /** Tasks in state QUEUED. */
    private val pending = AtomicInteger()
    private val TASK: AtomicReferenceFieldUpdater<CodeBlock, Any> =
        AtomicReferenceFieldUpdater.newUpdater(CodeBlock::class.java, Any::class.java, "jitTask")
    @Volatile private var started = false

    /**
     * Queues [cb] for compilation with [backend]. True if the block is queued or being compiled now (by this call or
     * an earlier one); false if background compilation is off or the queue is full (the caller compiles it itself).
     */
    fun submit(cb: CodeBlock, backend: JitBackend, owner: Agent?): Boolean {
        if (THREADS <= 0) return false
        if (pending.incrementAndGet() > MAX_PENDING) {
            pending.decrementAndGet()
            return false
        }
        val t = JitTask(cb, backend, owner, claimed = false)
        if (!TASK.compareAndSet(cb, null, t)) {
            pending.decrementAndGet()
            return true
        }
        // installed in between (its task was detached just before ours was attached): withdraw ours
        if (cb.compiled != null || cb.jitFailed) {
            withdraw(t)
            return true
        }
        startWorkers()
        queue.add(t)
        return true
    }

    /** Attaches [t], a task already claimed by the calling thread, to its block; false if the block has a task. */
    internal fun attach(t: JitTask): Boolean = TASK.compareAndSet(t.cb, null, t)

    /** Takes queued task [t] over for the calling thread; false if a worker has it already (or it was withdrawn). */
    internal fun claim(t: JitTask): Boolean {
        if (!t.state.compareAndSet(JitTask.QUEUED, JitTask.RUNNING)) return false
        pending.decrementAndGet()
        takenOver.incrementAndGet()
        return true
    }

    /** Compiles claimed tasks, a batch per backend, and installs their code. Never throws. */
    internal fun run(tasks: List<JitTask>) {
        for ((backend, ts) in tasks.groupBy { it.backend }) {
            val code = try {
                backend.compile(ts.map { it.cb })
            } catch (_: Throwable) {
                emptyList()
            }
            for ((i, t) in ts.withIndex()) install(t, code.getOrNull(i))
        }
    }

    private fun install(t: JitTask, code: CompiledCode?) {
        val cb = t.cb
        // the result first, then the task goes: a thread seeing neither would queue the block again. One that read
        // `compiled` just before it was set still can, and code once installed stays: frames run by compiled code
        // find it through their code block (Frame.isCompiled)
        if (code != null) {
            if (cb.compiled == null) cb.compiled = code
        } else if (cb.compiled == null) cb.jitFailed = true
        TASK.compareAndSet(cb, t, null)
        t.finish()
    }

    private fun withdraw(t: JitTask) {
        if (!t.state.compareAndSet(JitTask.QUEUED, JitTask.CANCELLED)) return
        pending.decrementAndGet()
        TASK.compareAndSet(t.cb, t, null)
        t.finish()
    }

    /** Withdraws the queued tasks of [owner] (its context is closing); blocks being compiled are finished. */
    fun cancel(owner: Agent) {
        val it = queue.iterator()
        while (it.hasNext()) {
            val t = it.next()
            if (t.owner === owner) {
                it.remove()
                withdraw(t)
            }
        }
    }

    private fun startWorkers() {
        if (started) return
        synchronized(this) {
            if (started) return
            repeat(THREADS) { i ->
                val w = Thread(::work, "neonjs-jit-$i")
                w.isDaemon = true
                w.priority = Thread.NORM_PRIORITY - 1
                w.start()
            }
            started = true
        }
    }

    private fun work() {
        val batch = ArrayList<JitTask>(BATCH)
        val mine = ArrayList<JitTask>(BATCH)
        while (true) {
            batch.clear()
            mine.clear()
            try {
                batch.add(queue.take())
            } catch (_: InterruptedException) {
                continue
            }
            queue.drainTo(batch, BATCH - 1)
            for (t in batch) if (t.state.compareAndSet(JitTask.QUEUED, JitTask.RUNNING)) mine.add(t)
            if (mine.isEmpty()) continue
            pending.addAndGet(-mine.size)
            batches.incrementAndGet()
            batchedBlocks.addAndGet(mine.size.toLong())
            run(ArrayList(mine))
        }
    }
}
