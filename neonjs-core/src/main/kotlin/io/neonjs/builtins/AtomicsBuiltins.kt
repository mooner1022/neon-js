package io.neonjs.builtins

import io.neonjs.runtime.*
import io.neonjs.vm.JSPromise
import io.neonjs.vm.Promises
import java.lang.ref.WeakReference
import java.math.BigInteger
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition

/**
 * The Atomics namespace object.
 *
 * Atomic operations on a SharedArrayBuffer run inside the critical section of its [SharedDataBlock] (one lock per
 * block, also guarding the waiter lists), which makes all atomics on a block sequentially consistent, including
 * mixed-size accesses. Lock-free VarHandle access is not an option: since JDK 23, byte-array view VarHandles no longer
 * support atomic access modes (JDK-8318966). Operations on non-shared buffers need no synchronization.
 *
 * Atomics.waitAsync threading model: an [AsyncWaiter] belongs to the agent that created it. Its promise is only ever
 * resolved on that agent's thread: directly by an Atomics.notify running in the same agent, otherwise by a job posted
 * with [Agent.postExternalJob] (by Atomics.notify on another agent's thread, or by the [timer] thread when the timeout
 * expires; the timeout job re-checks the waiter list on the owner thread, as EnqueueAtomicsWaitAsyncTimeoutJob does).
 * Each waiter is registered as an [Agent.ExternalSource] until it is resolved, so host event loops keep waiting for
 * it, and [Agent.closeExternal] withdraws it (unlinks it, cancels its timer). At most [MAX_ASYNC_WAITERS] waiters may
 * be pending per agent.
 */
internal object AtomicsBuiltins {
    /** Waiter Record in a SharedDataBlock waiter list; accessed while holding the block lock. */
    abstract class Waiter {
        @JvmField var notified = false
    }

    /** Waiter Record of a blocked Atomics.wait call. */
    class SyncWaiter(@JvmField val cond: Condition) : Waiter()

    /** Waiter Record of Atomics.waitAsync ([[PromiseCapability]] is [promise] of [realm], owned by [agent]). */
    class AsyncWaiter(
        @JvmField val agent: Agent,
        @JvmField val realm: Realm,
        @JvmField val promise: JSPromise,
        @JvmField val block: SharedDataBlock,
        @JvmField val byteIndex: Int,
    ) : Waiter(), Agent.ExternalSource {
        /** Pending timeout, if the timeout is finite. */
        @Volatile @JvmField var timer: ScheduledFuture<*>? = null

        /** Job posted to [agent] by Atomics.notify from another agent. */
        @JvmField val okJob = Runnable { resolve("ok") }

        /** Resolves the promise; owner thread only. */
        fun resolve(result: String) {
            agent.removeExternalSource(this)
            realm.enter { Promises.resolvePromise(realm, promise, result) }
        }

        /** The timeout job (EnqueueAtomicsWaitAsyncTimeoutJob), run on the owner thread after the timer fired. */
        fun onTimeout() {
            val removed: Boolean
            block.lock.lock()
            try {
                removed = unlink()
            } finally {
                block.lock.unlock()
            }
            if (removed) resolve("timed-out")
        }

        /** Removes this waiter from its waiter list (block lock held); false if it is no longer listed. */
        fun unlink(): Boolean {
            val list = block.waiters[byteIndex] ?: return false
            if (!list.remove(this)) return false
            if (list.isEmpty()) block.waiters.remove(byteIndex)
            return true
        }

        override fun cancel() {
            // unlink first: the lock orders this after a concurrent addAsyncWaiter, which sets [timer] under it
            block.lock.lock()
            try {
                unlink()
            } finally {
                block.lock.unlock()
            }
            timer?.cancel(false)
        }
    }

    /** Timer task: only posts the timeout job to the owning agent (never touches guest state itself). */
    private class TimeoutTask(w: AsyncWaiter) : Runnable {
        private val ref = WeakReference(w)

        override fun run() {
            val w = ref.get() ?: return
            w.agent.postExternalJob { w.onTimeout() }
        }
    }

    /** Per-agent cap on pending Atomics.waitAsync waiters (each holds a promise and possibly a timer task). */
    const val MAX_ASYNC_WAITERS = 10_000

    /** Finite timeouts above this many milliseconds (~31 years) never fire and get no timer. */
    private const val MAX_TIMER_MILLIS = 1e12

    /**
     * Shared timer for waitAsync timeouts: one daemon thread, started on demand and stopped after 10 s without work;
     * cancelled tasks are removed from the queue immediately. Tasks only post jobs ([TimeoutTask]).
     */
    private val timer: ScheduledThreadPoolExecutor by lazy {
        ScheduledThreadPoolExecutor(1) { r ->
            Thread(null, r, "neonjs-atomics-timer", 256L * 1024).also { it.isDaemon = true }
        }.also {
            it.removeOnCancelPolicy = true
            it.setKeepAliveTime(10, TimeUnit.SECONDS)
            it.allowCoreThreadTimeOut(true)
        }
    }

    private const val OP_ADD = 0
    private const val OP_SUB = 1
    private const val OP_AND = 2
    private const val OP_OR = 3
    private const val OP_XOR = 4
    private const val OP_EXCHANGE = 5

    /** Maximum slice of a blocking wait between interrupt / deadline checks. */
    private val WAIT_SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(50)

    fun install(realm: Realm) {
        val atomics = JSObject(realm.objectPrototype)
        realm.intrinsics["%Atomics%"] = atomics
        realm.global("Atomics", atomics)
        for ((name, op) in listOf("add" to OP_ADD, "and" to OP_AND, "exchange" to OP_EXCHANGE, "or" to OP_OR, "sub" to OP_SUB, "xor" to OP_XOR)) {
            atomics.method(realm, name, 3) { _, _, args, _ ->
                val ta = validateIntegerTypedArray(args.arg(0), false, write = true)
                val byteIndex = validateAtomicAccess(ta, args.arg(1))
                val v = toIntegerValue(ta.type, args.arg(2))
                revalidate(ta, byteIndex)
                val bits = rawBits(ta.type, v)
                val old = atomically(ta.buffer) {
                    val cur = rawLoad(ta.buffer.data, byteIndex, ta.type.size)
                    rawStore(ta.buffer.data, byteIndex, ta.type.size, apply(op, cur, bits))
                    cur
                }
                fromBits(ta.type, old)
            }
        }
        atomics.method(realm, "compareExchange", 4) { _, _, args, _ ->
            val ta = validateIntegerTypedArray(args.arg(0), false, write = true)
            val byteIndex = validateAtomicAccess(ta, args.arg(1))
            val expected = toIntegerValue(ta.type, args.arg(2))
            val replacement = toIntegerValue(ta.type, args.arg(3))
            revalidate(ta, byteIndex)
            val size = ta.type.size
            val exp = rawBits(ta.type, expected)
            val rep = rawBits(ta.type, replacement)
            val mask = if (size == 8) -1L else (1L shl (size * 8)) - 1
            val old = atomically(ta.buffer) {
                val cur = rawLoad(ta.buffer.data, byteIndex, size)
                if ((cur xor exp) and mask == 0L) rawStore(ta.buffer.data, byteIndex, size, rep)
                cur
            }
            fromBits(ta.type, old)
        }
        atomics.method(realm, "isLockFree", 1) { _, _, args, _ ->
            // [[IsLockFree1/2/8]] are false (atomics use the block lock); 4 must report true per spec
            Ops.toIntegerOrInfinity(args.arg(0)) == 4.0
        }
        atomics.method(realm, "load", 2) { _, _, args, _ ->
            val ta = validateIntegerTypedArray(args.arg(0), false)
            val byteIndex = validateAtomicAccess(ta, args.arg(1))
            revalidate(ta, byteIndex)
            fromBits(ta.type, atomically(ta.buffer) { rawLoad(ta.buffer.data, byteIndex, ta.type.size) })
        }
        atomics.method(realm, "store", 3) { _, _, args, _ ->
            val ta = validateIntegerTypedArray(args.arg(0), false, write = true)
            val byteIndex = validateAtomicAccess(ta, args.arg(1))
            val v = toIntegerValue(ta.type, args.arg(2))
            revalidate(ta, byteIndex)
            val bits = rawBits(ta.type, v)
            atomically(ta.buffer) { rawStore(ta.buffer.data, byteIndex, ta.type.size, bits) }
            v
        }
        atomics.method(realm, "wait", 4) { _, _, args, _ -> doWait(args) }
        atomics.method(realm, "waitAsync", 4) { f, _, args, _ -> doWaitAsync(f.realm, args) }
        atomics.method(realm, "notify", 3) { _, _, args, _ ->
            val ta = validateIntegerTypedArray(args.arg(0), true)
            val byteIndex = validateAtomicAccess(ta, args.arg(1))
            val countArg = args.arg(2)
            val c = if (countArg === Undefined) Double.POSITIVE_INFINITY else maxOf(Ops.toIntegerOrInfinity(countArg), 0.0)
            val block = ta.buffer.block ?: return@method 0.0
            notify(block, byteIndex, c).toDouble()
        }
        atomics.method(realm, "pause", 0) { _, _, args, _ ->
            val n = args.arg(0)
            if (n !== Undefined && !(n is Double && Ops.isIntegral(n))) typeErr("Atomics.pause argument must be undefined or an integer")
            val spins = if (n is Double) n.coerceIn(1.0, 1000.0).toInt() else 1
            repeat(spins) { spinWait() }
            Undefined
        }
        atomics.value(JSSymbol.toStringTag, "Atomics", Attr.CONFIGURABLE)
    }

    /** Thread.onSpinWait where it exists (JDK 9+, Android API 33+); a no-op hint otherwise. */
    private val HAS_SPIN_WAIT = try {
        Thread::class.java.getMethod("onSpinWait")
        true
    } catch (e: Throwable) {
        false
    }

    private fun spinWait() {
        if (HAS_SPIN_WAIT) Thread.onSpinWait()
    }

    // ------------------------------------------------------------------ validation

    /** ValidateIntegerTypedArray; [write] = accessMode ~write~ (read-modify-write and store operations). */
    private fun validateIntegerTypedArray(v: Any?, waitable: Boolean, write: Boolean = false): JSTypedArray {
        val ta = TypedArrayBuiltins.validate(v, "Atomics", write)
        val t = ta.type
        if (waitable) {
            if (t != ElementType.INT32 && t != ElementType.BIGINT64) typeErr("Atomics.wait / Atomics.notify require an Int32Array or BigInt64Array")
        } else if (t == ElementType.UINT8C || t == ElementType.FLOAT16 || t == ElementType.FLOAT32 || t == ElementType.FLOAT64) {
            typeErr("Atomics operations are not supported on ${t.ctorName}")
        }
        return ta
    }

    /** ValidateAtomicAccess: returns the byte index in the buffer. */
    private fun validateAtomicAccess(ta: JSTypedArray, requestIndex: Any?): Int {
        val length = ta.lengthOrOOB()
        val accessIndex = Ops.toIndex(requestIndex)
        if (accessIndex >= length) rangeErr("Atomics access index out of range")
        return (accessIndex.toInt() shl ta.type.shift) + ta.byteOffset
    }

    /** RevalidateAtomicAccess */
    private fun revalidate(ta: JSTypedArray, byteIndex: Int) {
        if (ta.lengthOrOOB() < 0) typeErr("Atomics: typed array is detached or out of bounds")
        if (byteIndex >= ta.buffer.byteLength()) rangeErr("Atomics access index out of range")
    }

    /** ToBigInt, or ToIntegerOrInfinity as a Number, of an operand. */
    private fun toIntegerValue(t: ElementType, v: Any?): Any = if (t.isBigInt) Ops.toBigInt(v) else Ops.toIntegerOrInfinity(v)

    /** NumericToRawBytes as a bit pattern in the low bits of a Long. */
    private fun rawBits(t: ElementType, v: Any): Long = if (t.isBigInt) (v as BigInteger).toLong() else Ops.toInt32(v as Double).toLong()

    /** RawBytesToNumeric from a bit pattern. */
    private fun fromBits(t: ElementType, bits: Long): Any = when (t) {
        ElementType.INT8 -> bits.toByte().toDouble()
        ElementType.UINT8 -> (bits and 0xFF).toDouble()
        ElementType.INT16 -> bits.toShort().toDouble()
        ElementType.UINT16 -> (bits and 0xFFFF).toDouble()
        ElementType.INT32 -> bits.toInt().toDouble()
        ElementType.UINT32 -> (bits and 0xFFFFFFFFL).toDouble()
        ElementType.BIGINT64 -> BigInteger.valueOf(bits)
        ElementType.BIGUINT64 -> BufferOps.bigFromBits(bits, true)
        else -> throw IllegalStateException("not an integer element type: $t")
    }

    // ------------------------------------------------------------------ atomic primitives

    /** Runs [body] inside the critical section of a shared buffer's data block. */
    private inline fun <R> atomically(buffer: JSArrayBuffer, body: () -> R): R {
        val block = buffer.block ?: return body()
        block.lock.lock()
        try {
            return body()
        } finally {
            block.lock.unlock()
        }
    }

    /** Raw little-endian load of a [size]-byte integer (sign-extended). */
    private fun rawLoad(a: ByteArray, i: Int, size: Int): Long = when (size) {
        1 -> a[i].toLong()
        2 -> BufferOps.getI16(a, i, true).toLong()
        4 -> BufferOps.getI32(a, i, true).toLong()
        else -> BufferOps.getI64(a, i, true)
    }

    private fun rawStore(a: ByteArray, i: Int, size: Int, bits: Long) {
        when (size) {
            1 -> a[i] = bits.toByte()
            2 -> BufferOps.setI16(a, i, bits.toShort(), true)
            4 -> BufferOps.setI32(a, i, bits.toInt(), true)
            else -> BufferOps.setI64(a, i, bits, true)
        }
    }

    private fun apply(op: Int, old: Long, v: Long): Long = when (op) {
        OP_ADD -> old + v
        OP_SUB -> old - v
        OP_AND -> old and v
        OP_OR -> old or v
        OP_XOR -> old xor v
        else -> v
    }

    // ------------------------------------------------------------------ wait / notify

    /** Validated arguments of DoWait: the waiter list position, the expected value and the timeout t in ms. */
    private class WaitArgs(@JvmField val block: SharedDataBlock, @JvmField val byteIndex: Int, @JvmField val size: Int,
                           @JvmField val value: Long, @JvmField val timeoutMs: Double)

    /** Steps 1-9 of DoWait (validation and argument coercion, in spec order). */
    private fun waitArgs(args: Array<Any?>, method: String): WaitArgs {
        val ta = validateIntegerTypedArray(args.arg(0), true)
        val block = ta.buffer.block ?: typeErr("$method requires a shared typed array")
        val byteIndex = validateAtomicAccess(ta, args.arg(1))
        val big = ta.type == ElementType.BIGINT64
        val v: Long = if (big) Ops.toBigInt(args.arg(2)).toLong() else Ops.toInt32(args.arg(2)).toLong()
        val q = Ops.toNumber(args.arg(3))
        val timeoutMs = if (q.isNaN() || q == Double.POSITIVE_INFINITY) Double.POSITIVE_INFINITY else maxOf(q, 0.0)
        return WaitArgs(block, byteIndex, ta.type.size, v, timeoutMs)
    }

    /**
     * RemoveWaiters + NotifyWaiter for up to [count] waiters at [byteIndex]; returns the number woken. Async waiters
     * of other agents get their resolution job posted while the block lock is held (so their timeout job, which takes
     * the lock, cannot run in between); those of the current agent are resolved directly after unlocking.
     */
    private fun notify(block: SharedDataBlock, byteIndex: Int, count: Double): Int {
        val self = Agent.current.get()
        var local: ArrayList<AsyncWaiter>? = null
        var n = 0
        block.lock.lock()
        try {
            val list = block.waiters[byteIndex]
            if (list != null) {
                while (n < count && list.isNotEmpty()) {
                    val w = list.removeFirst()
                    w.notified = true
                    if (w is SyncWaiter) {
                        w.cond.signal()
                    } else if (w is AsyncWaiter) {
                        w.timer?.cancel(false)
                        if (w.agent === self) (local ?: ArrayList<AsyncWaiter>().also { local = it }).add(w)
                        else w.agent.postExternalJob(w.okJob)
                    }
                    n++
                }
                if (list.isEmpty()) block.waiters.remove(byteIndex)
            }
        } finally {
            block.lock.unlock()
        }
        local?.forEach { it.resolve("ok") }
        return n
    }

    /** DoWait(async, typedArray, index, value, timeout): returns the { async, value } result object. */
    private fun doWaitAsync(realm: Realm, args: Array<Any?>): JSObject {
        val a = waitArgs(args, "Atomics.waitAsync")
        val agent = realm.agent
        val promise = Promises.newPromise(realm)
        val result = JSObject(realm.objectPrototype)
        val block = a.block
        var immediate: String? = null
        var overLimit = false
        block.lock.lock()
        try {
            if (rawLoad(block.data, a.byteIndex, a.size) != a.value) immediate = "not-equal"
            else if (a.timeoutMs == 0.0) immediate = "timed-out"
            else if (agent.externalSourceCount >= MAX_ASYNC_WAITERS) overLimit = true
            else addAsyncWaiter(AsyncWaiter(agent, realm, promise, block, a.byteIndex), a.timeoutMs)
        } finally {
            block.lock.unlock()
        }
        if (overLimit) rangeErr("Atomics.waitAsync: too many pending waiters (limit $MAX_ASYNC_WAITERS per agent)")
        result.createDataPropertyOrThrow("async", immediate == null)
        result.createDataPropertyOrThrow("value", immediate ?: promise)
        return result
    }

    /**
     * AddWaiter and, for a finite timeout, EnqueueAtomicsWaitAsyncTimeoutJob (block lock held). The waiter is registered
     * with its agent last, so a concurrent [Agent.closeExternal] either withdraws the fully linked waiter or makes the
     * registration fail (then it is unlinked again here).
     */
    private fun addAsyncWaiter(w: AsyncWaiter, timeoutMs: Double) {
        w.block.waiters.getOrPut(w.byteIndex) { ArrayDeque() }.addLast(w)
        if (timeoutMs <= MAX_TIMER_MILLIS) {
            w.timer = timer.schedule(TimeoutTask(w), (timeoutMs * 1e6).toLong(), TimeUnit.NANOSECONDS)
        }
        if (!w.agent.addExternalSource(w)) {
            w.unlink()
            w.timer?.cancel(false)
            typeErr("Atomics.waitAsync: the agent is shutting down")
        }
    }

    /** DoWait(sync, typedArray, index, value, timeout) */
    private fun doWait(args: Array<Any?>): String {
        val a = waitArgs(args, "Atomics.wait")
        val block = a.block
        val byteIndex = a.byteIndex
        val v = a.value
        val timeoutMs = a.timeoutMs
        val agent = Agent.current()
        val lock = block.lock
        lock.lock()
        try {
            val w = rawLoad(block.data, byteIndex, a.size)
            if (v != w) return "not-equal"
            val waiter = SyncWaiter(lock.newCondition())
            block.waiters.getOrPut(byteIndex) { ArrayDeque() }.addLast(waiter)
            try {
                val deadline = if (timeoutMs == Double.POSITIVE_INFINITY) Long.MAX_VALUE
                else System.nanoTime() + minOf(timeoutMs * 1e6, Long.MAX_VALUE / 4.0).toLong()
                while (!waiter.notified) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) break
                    try {
                        waiter.cond.awaitNanos(minOf(remaining, WAIT_SLICE_NANOS))
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw InterruptedExecutionException("Execution interrupted")
                    }
                    if (!waiter.notified) agent.checkInterrupt()
                }
            } finally {
                if (!waiter.notified) {
                    val list = block.waiters[byteIndex]
                    if (list != null) {
                        list.remove(waiter)
                        if (list.isEmpty()) block.waiters.remove(byteIndex)
                    }
                }
            }
            return if (waiter.notified) "ok" else "timed-out"
        } finally {
            lock.unlock()
        }
    }
}
