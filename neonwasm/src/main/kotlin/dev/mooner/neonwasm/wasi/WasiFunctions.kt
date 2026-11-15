package dev.mooner.neonwasm.wasi

import dev.mooner.neonwasm.FuncType
import dev.mooner.neonwasm.HostFunction
import dev.mooner.neonwasm.ValType
import java.io.IOException

/** wasi_snapshot_preview1's functions over one [Wasi]'s state: the process, clocks, randomness, streams and polling. */
internal class WasiFunctions(private val w: Wasi) {
    private val mem get() = w.mem
    private val files = WasiFiles(w)

    fun all(): Map<String, HostFunction> {
        val m = LinkedHashMap<String, HostFunction>()
        m["args_sizes_get"] = wasiFunction("ii") { a -> sizes(w.config.args, a[0] as Int, a[1] as Int) }
        m["args_get"] = wasiFunction("ii") { a -> strings(w.config.args, a[0] as Int, a[1] as Int) }
        val env = w.config.env.map { (k, v) -> "$k=$v" }
        m["environ_sizes_get"] = wasiFunction("ii") { a -> sizes(env, a[0] as Int, a[1] as Int) }
        m["environ_get"] = wasiFunction("ii") { a -> strings(env, a[0] as Int, a[1] as Int) }
        m["clock_res_get"] = wasiFunction("ii") { a ->
            mem.put64(a[1] as Int, when (a[0] as Int) {
                Flags.CLOCK_REALTIME -> 1_000L
                Flags.CLOCK_MONOTONIC, Flags.CLOCK_PROCESS_CPUTIME, Flags.CLOCK_THREAD_CPUTIME -> 1L
                else -> fail(Errno.INVAL)
            })
            Errno.SUCCESS
        }
        m["clock_time_get"] = wasiFunction("iIi") { a ->
            mem.put64(a[2] as Int, now(a[0] as Int))
            Errno.SUCCESS
        }
        m["random_get"] = wasiFunction("ii") { a ->
            val n = a[1] as Int
            mem.check(a[0] as Int, n.toLong() and 0xFFFFFFFFL)
            val b = ByteArray(n)
            w.config.random.nextBytes(b)
            mem.write(a[0] as Int, b)
            Errno.SUCCESS
        }
        m["proc_exit"] = HostFunction(FuncType(intArrayOf(ValType.I32), IntArray(0))) { a -> throw WasiExit(a[0] as Int) }
        m["proc_raise"] = wasiFunction("i") { Errno.NOSYS }
        m["sched_yield"] = wasiFunction("") {
            Thread.yield()
            Errno.SUCCESS
        }
        m["poll_oneoff"] = wasiFunction("iiii") { a -> poll(a[0] as Int, a[1] as Int, a[2] as Int, a[3] as Int) }
        m["fd_write"] = wasiFunction("iiii") { a -> write(a[0] as Int, a[1] as Int, a[2] as Int, a[3] as Int) }
        m["fd_read"] = wasiFunction("iiii") { a -> read(a[0] as Int, a[1] as Int, a[2] as Int, a[3] as Int) }
        for (name in listOf("sock_accept", "sock_recv", "sock_send", "sock_shutdown")) {
            val sig = when (name) {
                "sock_accept" -> "iii"
                "sock_recv" -> "iiiiii"
                "sock_send" -> "iiiii"
                else -> "ii"
            }
            // no sockets: a descriptor is something else, or none
            m[name] = wasiFunction(sig) { a ->
                w.fd(a[0] as Int)
                Errno.NOTSOCK
            }
        }
        files.register(m)
        return m
    }

    internal fun now(clock: Int): Long = when (clock) {
        Flags.CLOCK_REALTIME -> w.config.clock.realtimeNanos()
        Flags.CLOCK_MONOTONIC, Flags.CLOCK_PROCESS_CPUTIME, Flags.CLOCK_THREAD_CPUTIME -> w.config.clock.monotonicNanos()
        else -> fail(Errno.INVAL)
    }

    private fun sizes(list: List<String>, countPtr: Int, sizePtr: Int): Int {
        mem.put32(countPtr, list.size)
        mem.put32(sizePtr, list.sumOf { it.toByteArray(Charsets.UTF_8).size + 1 })
        return Errno.SUCCESS
    }

    private fun strings(list: List<String>, ptrs: Int, buf: Int): Int {
        var p = buf
        for ((i, s) in list.withIndex()) {
            val b = s.toByteArray(Charsets.UTF_8)
            mem.put32(ptrs + 4 * i, p)
            mem.write(p, b)
            mem.put8(p + b.size, 0)
            p += b.size + 1
        }
        return Errno.SUCCESS
    }

    private fun write(fd: Int, iovs: Int, n: Int, written: Int): Int {
        val f = w.fd(fd)
        val vecs = mem.iovecs(iovs, n)
        val total = when (f) {
            is Fd.Stream -> {
                val out = f.output ?: fail(Errno.BADF)
                var t = 0L
                try {
                    for ((p, len) in vecs) {
                        out.write(mem.bytes, p, len)
                        t += len
                    }
                } catch (_: IOException) {
                    fail(Errno.IO)
                }
                t
            }
            else -> files.write(f, vecs, -1)
        }
        mem.put32(written, total.toInt())
        return Errno.SUCCESS
    }

    private fun read(fd: Int, iovs: Int, n: Int, readPtr: Int): Int {
        val f = w.fd(fd)
        val vecs = mem.iovecs(iovs, n)
        val total = when (f) {
            is Fd.Stream -> {
                val input = f.input ?: fail(Errno.BADF)
                var t = 0L
                try {
                    for ((p, len) in vecs) {
                        if (len == 0) continue
                        val k = input.read(mem.bytes, p, len)
                        if (k <= 0) break
                        t += k
                        if (k < len) break
                    }
                } catch (_: IOException) {
                    fail(Errno.IO)
                }
                t
            }
            else -> files.read(f, vecs, -1)
        }
        mem.put32(readPtr, total.toInt())
        return Errno.SUCCESS
    }

    /**
     * poll_oneoff: descriptors are always ready (streams and in-memory files do not block); clocks wait, on the
     * calling thread, in slices that let the host's limits stop the program.
     */
    private fun poll(subs: Int, events: Int, n: Int, neventsPtr: Int): Int {
        if (n == 0) fail(Errno.INVAL)
        mem.check(subs, (n.toLong() and 0xFFFFFFFFL) * 48)
        data class Sub(val userdata: Long, val tag: Int, val fd: Int, val deadline: Long, val clockErr: Int)
        val list = ArrayList<Sub>()
        for (i in 0 until n) {
            val s = subs + 48 * i
            val userdata = mem.u64(s)
            when (val tag = mem.u8(s + 8)) {
                Flags.EVENT_CLOCK -> {
                    val id = mem.u32(s + 16)
                    val timeout = mem.u64(s + 24)
                    val flags = mem.u16(s + 40)
                    val (deadline, err) = try {
                        val t = now(id)
                        val abs = flags and Flags.SUBSCRIPTION_CLOCK_ABSTIME != 0
                        val wait = if (abs) timeout - t else timeout
                        (System.nanoTime() + maxOf(0L, wait).coerceAtMost(Long.MAX_VALUE / 4)) to Errno.SUCCESS
                    } catch (e: WasiError) {
                        System.nanoTime() to e.errno
                    }
                    list.add(Sub(userdata, tag, -1, deadline, err))
                }
                Flags.EVENT_FD_READ, Flags.EVENT_FD_WRITE -> list.add(Sub(userdata, tag, mem.u32(s + 16), 0, Errno.SUCCESS))
                else -> fail(Errno.INVAL)
            }
        }
        val ready = ArrayList<Pair<Sub, Int>>()
        for (s in list) if (s.tag != Flags.EVENT_CLOCK) ready.add(s to if (w.fds.containsKey(s.fd)) Errno.SUCCESS else Errno.BADF)
        for (s in list) if (s.tag == Flags.EVENT_CLOCK && s.clockErr != Errno.SUCCESS) ready.add(s to s.clockErr)
        if (ready.isEmpty()) {
            val first = list.minOf { it.deadline }
            val agent = w.agent
            while (true) {
                val left = first - System.nanoTime()
                if (left <= 0) break
                agent?.checkWaitLimits()
                try {
                    Thread.sleep(minOf(left / 1_000_000 + 1, 50L))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            val t = System.nanoTime()
            for (s in list) if (s.tag == Flags.EVENT_CLOCK && s.deadline <= t) ready.add(s to Errno.SUCCESS)
        }
        for ((i, r) in ready.withIndex()) {
            val (s, err) = r
            val e = events + 32 * i
            mem.check(e, 32)
            java.util.Arrays.fill(mem.bytes, e, e + 32, 0)
            mem.put64(e, s.userdata)
            mem.put16(e + 8, err)
            mem.put8(e + 10, s.tag)
            if (s.tag != Flags.EVENT_CLOCK && err == Errno.SUCCESS) mem.put64(e + 16, files.available(w.fds[s.fd]!!))
        }
        mem.put32(neventsPtr, ready.size)
        return Errno.SUCCESS
    }
}
