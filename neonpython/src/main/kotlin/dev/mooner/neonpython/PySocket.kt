package dev.mooner.neonpython

import dev.mooner.neonjs.runtime.InterruptedExecutionException

/**
 * One end of a channel in memory (docs/PYTHON_PLATFORM.md §3.8, §12.2), the kernel's side of a descriptor: a pipe's
 * (os.pipe), a socket pair's (_socket.socketpair: each end the other's [peer]), or a socket's connected to nothing
 * (_socket.socket: the network is refused, so nothing ever connects it). What the peer sends waits in [inbox] until
 * read, as much as the sender's buffer holds. The descriptors dup made share it, and its O_NONBLOCK ([blocking]).
 */
class PyPipeEnd(
    /** A socket's family, type (the kernel's: an AF_UNIX SOCK_RAW is a SOCK_DGRAM) and protocol; a pipe's 0. */
    @JvmField val family: Int, @JvmField val type: Int, @JvmField val proto: Int,
    @JvmField val canRead: Boolean, @JvmField val canWrite: Boolean,
) {
    /** What was sent to it, not yet read: a datagram or a packet each where [messages], else a stream's chunks. */
    @JvmField val inbox = ArrayDeque<ByteArray>()
    /** The bytes of a stream's first chunk already read. */
    @JvmField var head = 0
    /** The bytes waiting in [inbox]. */
    @JvmField var queued = 0
    @JvmField var peer: PyPipeEnd? = null
    @JvmField var closed = false
    /** Its receiving shut (SHUT_RD, its peer's SHUT_WR or close): what waits is read, then the stream's end. */
    @JvmField var rdShut = false
    /** Its sending shut (SHUT_WR, its peer's SHUT_RD or close): EPIPE. */
    @JvmField var wrShut = false
    /** Its peer closed with bytes it had not read (AF_UNIX's): ECONNRESET, once (SO_ERROR's). */
    @JvmField var reset = false
    @JvmField var blocking = true
    /** SO_SNDBUF (what it may have sent that its peer has not read) and SO_RCVBUF. */
    @JvmField var sndbuf = 0
    @JvmField var rcvbuf = 0
    /** The options set ((level shl 16) or name), each's bytes. */
    @JvmField val options = HashMap<Int, ByteArray>()

    val isSocket: Boolean get() = family != 0
    /** Whether what is sent keeps its bounds (a datagram, a packet), not a stream's bytes. */
    val messages: Boolean get() = isSocket && type != PySocket.SOCK_STREAM
    /** Whether it has a peer (a pipe's end, a socket pair's). */
    val connected: Boolean get() = peer != null
}

/** A _socket.socket (a socket.socket too): its descriptor (-1 when closed or detached), what it was made as, its timeout. */
class PySocketObj(type: PyType) : PyObject(type) {
    @JvmField var fd = -1
    @JvmField var family = 0
    @JvmField var kind = 0
    @JvmField var proto = 0
    /** Its timeout in nanoseconds: -1 none (it blocks), 0 it does not block. */
    @JvmField var timeout = -1L
}

/**
 * `_socket` and `select` for a sandbox (docs/PYTHON_PLATFORM.md §3.8, §12.2), CPython's socket.py and selectors.py
 * over them: no network unless the host gives one, and none is given. Linux's names and numbers. A socket is made
 * (socket.socket()) but what would reach a network is refused with PermissionError(EPERM): connect, bind, listen,
 * accept, sendto and recvfrom on such a socket, the name lookups (getaddrinfo, gethostbyname...; gethostname() is
 * `localhost`, as uname() says, so getfqdn() is too). What
 * stays in the program works: socketpair()'s ends are connected in memory ([PyPipeEnd]), as os.pipe()'s are, with
 * Linux's semantics (buffers, shutdown, EPIPE, ECONNRESET, timeouts, non-blocking descriptors); select waits on them.
 * One thread runs: a wait nothing else could end raises at once (as `_thread`'s lock does), a wait with a timeout
 * sleeps it out, the agent's limits checked.
 */
object PySocket {
    const val AF_UNIX = 1
    const val AF_INET = 2
    const val AF_INET6 = 10
    const val SOCK_STREAM = 1
    const val SOCK_DGRAM = 2
    const val SOCK_RAW = 3
    const val SOCK_SEQPACKET = 5
    private const val SOCK_NONBLOCK = 0x800
    private const val SOCK_CLOEXEC = 0x80000
    private const val SOL_SOCKET = 1
    private const val IPPROTO_IP = 0
    private const val IPPROTO_TCP = 6
    private const val IPPROTO_IPV6 = 41
    private const val MSG_PEEK = 2
    private const val MSG_DONTWAIT = 0x40
    /** A pipe's capacity (Linux's 16 pages) and PIPE_BUF (a write of as many bytes is done whole or not at all). */
    private const val PIPE_CAPACITY = 65536
    private const val PIPE_BUF = 4096
    /** net.core.wmem_max and rmem_max: what SO_SNDBUF and SO_RCVBUF are given at most, doubled. */
    private const val BUF_MAX = 212992
    private val NO_ARGS = arrayOfNulls<Any?>(0)

    @JvmField val SOCKET: PyType = PyTypes.newBuiltin("socket").also {
        it.module = "_socket"
        it.tpName = "_socket.socket"
        it.dispatchBuiltins = true
    }

    // ------------------------------------------------------------------ ends

    /** os.pipe(): its read end and its write end. */
    @JvmStatic
    fun pipe(): Pair<PyPipeEnd, PyPipeEnd> {
        val r = PyPipeEnd(0, 0, 0, canRead = true, canWrite = false)
        val w = PyPipeEnd(0, 0, 0, canRead = false, canWrite = true)
        r.peer = w
        w.peer = r
        return r to w
    }

    /** The end closed (its last descriptor closed): its peer sees the stream end (a reset where bytes were left unread). */
    @JvmStatic
    fun close(e: PyPipeEnd) {
        if (e.closed) return
        e.closed = true
        val p = e.peer
        if (p != null && !p.closed) {
            if (!e.isSocket) {
                if (e.canWrite) p.rdShut = true
            } else if (e.type == SOCK_STREAM || e.type == SOCK_SEQPACKET) {
                p.rdShut = true
                p.wrShut = true
                if (e.queued > 0) p.reset = true
            }
        }
        e.inbox.clear()
        e.queued = 0
        e.head = 0
    }

    /** What a receive takes from [e]: at most [n] bytes (left there where [peek]); null where it would wait. */
    private fun take(e: PyPipeEnd, n: Int, peek: Boolean): ByteArray? {
        if (e.isSocket && !e.connected) {
            // (an unconnected datagram socket: nothing comes; a stream's, Linux's errors)
            if (e.type == SOCK_DGRAM) return null
            throw PyPosix.error(if (e.family == AF_UNIX) PyErrno.EINVAL else PyErrno.ENOTCONN)
        }
        if (e.messages) {
            val m = e.inbox.firstOrNull()
            if (m != null) {
                if (!peek) {
                    e.inbox.removeFirst()
                    e.queued -= m.size
                }
                return if (m.size <= n) m.copyOf() else m.copyOf(n)
            }
        } else {
            if (n == 0) return ByteArray(0)
            if (e.queued > 0) {
                val k = minOf(n, e.queued)
                val out = ByteArray(k)
                var off = 0
                var head = e.head
                var i = 0
                while (off < k) {
                    val c = e.inbox[i]
                    val m = minOf(c.size - head, k - off)
                    System.arraycopy(c, head, out, off, m)
                    off += m
                    head += m
                    if (head == c.size) {
                        i++
                        head = 0
                    }
                }
                if (!peek) {
                    repeat(i) { e.inbox.removeFirst() }
                    e.head = head
                    e.queued -= k
                }
                return out
            }
        }
        if (e.reset) {
            e.reset = false
            throw PyPosix.error(PyErrno.ECONNRESET)
        }
        if (e.rdShut) return ByteArray(0)
        return null
    }

    /**
     * What a send gives [e]'s peer of [b] from [off]: all of it where [whole] (a blocking descriptor's), else what there
     * is room for; how many bytes, or null where it would wait.
     */
    private fun put(e: PyPipeEnd, b: ByteArray, off: Int, whole: Boolean): Int? {
        val len = b.size - off
        val room: Int
        val p: PyPipeEnd
        if (e.isSocket) {
            if (!e.connected) {
                throw PyPosix.error(when {
                    e.family == AF_UNIX -> PyErrno.ENOTCONN
                    e.type == SOCK_STREAM -> PyErrno.EPIPE
                    else -> PyErrno.EDESTADDRREQ
                })
            }
            if (e.wrShut) throw PyPosix.error(PyErrno.EPIPE)
            p = e.peer!!
            if (e.messages) {
                if (p.closed) throw PyPosix.error(PyErrno.ECONNREFUSED)
                if (p.rdShut) throw PyPosix.error(PyErrno.EPIPE)
                if (len > e.sndbuf) throw PyPosix.error(PyErrno.EMSGSIZE)
                if (len > e.sndbuf - p.queued) return null
                p.inbox.addLast(b.copyOfRange(off, b.size))
                p.queued += len
                return len
            }
            if (len == 0) return 0
            if (p.closed || p.rdShut) throw PyPosix.error(PyErrno.EPIPE)
            room = e.sndbuf - p.queued
        } else {
            p = e.peer!!
            if (len == 0) return 0
            if (p.closed) throw PyPosix.error(PyErrno.EPIPE)
            room = PIPE_CAPACITY - p.queued
            if (len <= PIPE_BUF && room < len) return null
        }
        if (room <= 0 || whole && room < len) return null
        val k = minOf(room, len)
        p.inbox.addLast(b.copyOfRange(off, off + k))
        p.queued += k
        return k
    }

    /** Whether select (poll) finds [e] readable: something to read, its end, an error, a hang-up. */
    @JvmStatic
    internal fun readable(e: PyPipeEnd): Boolean = when {
        !e.isSocket -> if (e.canRead) e.queued > 0 || e.rdShut else e.peer!!.closed
        !e.connected -> e.type != SOCK_DGRAM
        else -> e.queued > 0 || e.rdShut || e.reset
    }

    /** Whether select finds [e] writable: room in its peer's buffer (Linux's measure), or a write failing at once. */
    @JvmStatic
    internal fun writable(e: PyPipeEnd): Boolean {
        if (!e.isSocket) {
            val p = e.peer!!
            return e.canWrite && (p.closed || PIPE_CAPACITY - p.queued >= PIPE_BUF)
        }
        val p = e.peer ?: return true
        return p.closed || p.queued.toLong() * 4 <= e.sndbuf
    }

    /** os.read on an end: what is there, its end; BlockingIOError where it does not block, else a wait for ever. */
    @JvmStatic
    internal fun readFd(e: PyPipeEnd, n: Int): ByteArray {
        if (!e.canRead) throw PyPosix.error(PyErrno.EBADF)
        // (read on a TCP socket never connected: the stream's end, where recv says ENOTCONN)
        if (e.isSocket && !e.connected && e.family != AF_UNIX && e.type == SOCK_STREAM) return ByteArray(0)
        return take(e, n, false) ?: throw if (e.blocking) forEver(false) else PyPosix.error(PyErrno.EAGAIN)
    }

    /** os.write on an end. */
    @JvmStatic
    internal fun writeFd(e: PyPipeEnd, b: ByteArray): Int {
        if (!e.canWrite) throw PyPosix.error(PyErrno.EBADF)
        return put(e, b, 0, e.blocking) ?: throw if (e.blocking) forEver(true) else PyPosix.error(PyErrno.EAGAIN)
    }

    /** A wait that can never end: one thread runs, so nothing else can send, read, or make a descriptor ready. */
    @JvmStatic
    internal fun forEver(what: String): PyException = PyException("RuntimeError", "$what (one thread: it would wait for ever)")

    private fun forEver(writing: Boolean): PyException =
        forEver(if (writing) "cannot wait for room nothing else can make" else "cannot wait for data nothing else can send")

    /**
     * Waits until [ready] or System.nanoTime() reaches [deadline], in slices of at most 10 ms, the agent's limits checked
     * (as time.sleep): whether it was ready.
     */
    @JvmStatic
    internal fun waitFor(ctx: PyContext, deadline: Long, ready: () -> Boolean): Boolean {
        val agent = ctx.agent
        while (true) {
            if (ready()) return true
            agent.checkWaitLimits()
            val left = deadline - System.nanoTime()
            if (left <= 0) return false
            val slice = minOf(left, 10_000_000L)
            try {
                Thread.sleep(slice / 1_000_000, (slice % 1_000_000).toInt())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedExecutionException("Execution interrupted")
            }
        }
    }

    /** System.nanoTime() [ns] from now (the farthest where that is past the clock's range). */
    @JvmStatic
    internal fun deadline(ns: Long): Long {
        val now = System.nanoTime()
        return if (ns > Long.MAX_VALUE - now) Long.MAX_VALUE else now + ns
    }

    // ------------------------------------------------------------------ sockets

    /** socket(family, type, proto) as Linux makes one (the network's raw and other families refused: EPERM). */
    private fun newEnd(family: Int, type0: Int, proto: Int): PyPipeEnd {
        if (type0 and 0xF.inv() and (SOCK_NONBLOCK or SOCK_CLOEXEC).inv() != 0) throw PyPosix.error(PyErrno.EINVAL)
        val base = type0 and 0xF
        if (family < 0 || family >= 46) throw PyPosix.error(PyErrno.EAFNOSUPPORT)
        if (base >= 11) throw PyPosix.error(PyErrno.EINVAL)
        val type: Int
        val kproto: Int
        when (family) {
            AF_UNIX -> {
                if (proto != 0 && proto != 1) throw PyPosix.error(PyErrno.EPROTONOSUPPORT)
                type = when (base) {
                    SOCK_STREAM, SOCK_SEQPACKET -> base
                    SOCK_DGRAM, SOCK_RAW -> SOCK_DGRAM
                    else -> throw PyPosix.error(PyErrno.ESOCKTNOSUPPORT)
                }
                kproto = 0
            }
            AF_INET, AF_INET6 -> when (base) {
                SOCK_STREAM -> {
                    if (proto != 0 && proto != IPPROTO_TCP) throw PyPosix.error(PyErrno.EPROTONOSUPPORT)
                    type = base
                    kproto = IPPROTO_TCP
                }
                SOCK_DGRAM -> {
                    if (proto != 0 && proto != 17 && proto != 136) throw PyPosix.error(PyErrno.EPROTONOSUPPORT)
                    type = base
                    kproto = if (proto == 0) 17 else proto
                }
                SOCK_RAW -> throw PyPosix.error(PyErrno.EPERM)
                else -> throw PyPosix.error(PyErrno.ESOCKTNOSUPPORT)
            }
            0 -> throw PyPosix.error(PyErrno.EAFNOSUPPORT)
            else -> throw PyPosix.error(PyErrno.EPERM)
        }
        val e = PyPipeEnd(family, type, kproto, canRead = true, canWrite = true)
        e.blocking = type0 and SOCK_NONBLOCK == 0
        if (family != AF_UNIX && type == SOCK_STREAM) {
            e.sndbuf = 16384
            e.rcvbuf = 131072
        } else {
            e.sndbuf = BUF_MAX
            e.rcvbuf = BUF_MAX
        }
        return e
    }

    /** init_sockobject: [s] made [fd]'s socket, its timeout the default (none where SOCK_NONBLOCK). */
    private fun initObject(st: PyPosix.State, s: PySocketObj, fd: Int, family: Int, type: Int, proto: Int) {
        s.fd = fd
        s.family = family
        s.kind = type and (SOCK_NONBLOCK or SOCK_CLOEXEC).inv()
        s.proto = proto
        if (type and SOCK_NONBLOCK != 0) {
            s.timeout = 0
        } else {
            s.timeout = st.socketTimeout
            if (st.socketTimeout >= 0) st.fds[fd]?.let { PyPosix.setBlocking(it, false) }
        }
    }

    private fun state(): PyPosix.State = PyPosix.state(PyContext.current())

    /** The end of [s]'s descriptor: EBADF where it is closed, ENOTSOCK where it is no socket's. */
    private fun endOf(s: PySocketObj): PyPipeEnd {
        val f = state().fds[s.fd] ?: throw PyPosix.error(PyErrno.EBADF)
        return f.end?.takeIf { it.isSocket } ?: throw PyPosix.error(PyErrno.ENOTSOCK)
    }

    /** internal_setblocking: [s]'s descriptor's O_NONBLOCK (EBADF where it is closed). */
    private fun setBlocking(s: PySocketObj, blocking: Boolean) {
        PyPosix.setBlocking(state().fds[s.fd] ?: throw PyPosix.error(PyErrno.EBADF), blocking)
    }

    /**
     * sock_call: [op] (null where it would wait) as [s]'s timeout has it: without one tried once (BlockingIOError
     * where the descriptor does not block or MSG_DONTWAIT, else a wait for ever); with one, waited for until done or
     * [deadline] (TimeoutError).
     */
    private fun <T : Any> call(s: PySocketObj, e: PyPipeEnd, writing: Boolean, flags: Int, deadline: Long, op: () -> T?): T {
        if (s.timeout > 0 && flags and MSG_DONTWAIT == 0) {
            var r: T? = null
            val ok = waitFor(PyContext.current(), deadline) {
                (if (writing) writable(e) else readable(e)) && op().also { r = it } != null
            }
            if (ok) return r!!
            throw PyException("TimeoutError", "timed out")
        }
        op()?.let { return it }
        if (!e.blocking || flags and MSG_DONTWAIT != 0) throw PyPosix.error(PyErrno.EAGAIN)
        throw forEver(writing)
    }

    private fun timeoutDeadline(s: PySocketObj): Long = if (s.timeout > 0) deadline(s.timeout) else 0L

    /** recv's: at most [n] bytes from [s]. */
    private fun receive(s: PySocketObj, n: Long, flags: Int): ByteArray {
        val e = endOf(s)
        val k = n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return call(s, e, false, flags, timeoutDeadline(s)) { take(e, k, flags and MSG_PEEK != 0) }
    }

    /** send's: how many bytes of [b] were sent. */
    private fun send(s: PySocketObj, b: ByteArray, flags: Int, off: Int, deadline: Long): Int {
        val e = endOf(s)
        val whole = e.blocking && flags and MSG_DONTWAIT == 0
        return call(s, e, true, flags, deadline) { put(e, b, off, whole) }
    }

    /** A timeout argument (socket_parse_timeout): None -1, else non-negative nanoseconds. */
    private fun timeoutArg(v: Any?): Long {
        if (v === PyNone) return -1
        val t = PyTime.fromSecondsObject(v, PyTime.ROUND_UP)
        if (t < 0) throw PyException("ValueError", "Timeout value out of range")
        return t
    }

    private fun seconds(ns: Long): Double = if (ns % 1_000_000_000L == 0L) (ns / 1_000_000_000L).toDouble() else ns / 1e9

    // ------------------------------------------------------------------ arguments

    private fun index(v: Any?): Any = PyRt.indexValue(v) ?: throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer")

    /** PyArg_Parse's "i". */
    private fun cInt(v: Any?): Int {
        val i = index(v) as? Long ?: throw PyException("OverflowError", "Python int too large to convert to C long")
        if (i > Int.MAX_VALUE) throw PyException("OverflowError", "signed integer is greater than maximum")
        if (i < Int.MIN_VALUE) throw PyException("OverflowError", "signed integer is less than minimum")
        return i.toInt()
    }

    /** Argument Clinic's int (PyLong_AsInt). */
    @JvmStatic
    internal fun asInt(v: Any?): Int {
        val i = index(v) as? Long
        if (i == null || i > Int.MAX_VALUE || i < Int.MIN_VALUE) throw PyException("OverflowError", "Python int too large to convert to C int")
        return i.toInt()
    }

    /** PyArg_Parse's "n". */
    private fun ssize(v: Any?): Long = index(v) as? Long ?: throw PyException("OverflowError", "Python int too large to convert to C ssize_t")

    /** PyArg_Parse's "y*". */
    private fun bytesArg(v: Any?): ByteArray = PyByteSeqs.content(v) ?: throw PyRt.typeError("a bytes-like object is required, not '${PyTypes.name(v)}'")

    /** PyArg_ParseTuple's count check, CPython's message. */
    private fun count(fn: String, a: Array<Any?>, min: Int, max: Int) {
        val n = a.size
        if (n in min..max) return
        val k = if (n < min) min else max
        val how = if (min == max) "exactly" else if (n < min) "at least" else "at most"
        throw PyRt.typeError("$fn() takes $how $k argument${if (k == 1) "" else "s"} ($n given)")
    }

    /** A writable buffer ("w*"): its array, where its bytes start, how many. */
    private class Target(@JvmField val arr: ByteArray, @JvmField val off: Int, @JvmField val len: Int)

    private fun target(fn: String, v: Any?): Target = when {
        v is PyByteArray -> Target(v.b, 0, v.size)
        v is PyArrayObj -> Target(v.b, 0, v.n * v.itemsize)
        v is PyMemoryView && !v.readonly -> {
            v.check()
            if (!v.contiguous()) throw PyException("BufferError", "memoryview: underlying buffer is not C-contiguous")
            Target(v.arr(), v.offset, v.count * v.itemsize)
        }
        else -> throw PyRt.typeError("$fn() argument 1 must be read-write bytes-like object, not ${PyTypes.name(v)}")
    }

    private fun sock(v: Any?, fn: String): PySocketObj = v as? PySocketObj
        ?: throw PyRt.typeError("descriptor '$fn' for '_socket.socket' objects doesn't apply to a '${PyTypes.name(v)}' object")

    private fun rest(a: Array<Any?>): Array<Any?> = if (a.size <= 1) NO_ARGS else a.copyOfRange(1, a.size)

    /** A method of METH_VARARGS (keywords refused): its arguments, self's not among them. */
    private fun varargs(name: String, f: (PySocketObj, Array<Any?>) -> Any?) {
        SOCKET.def(name, PyBuiltin(name, { _, a -> f(sock(a.getOrNull(0), name), rest(a)) }, { _, a, kw ->
            if (kw.size > 0) throw PyRt.typeError("socket.$name() takes no keyword arguments")
            f(sock(a.getOrNull(0), name), rest(a))
        }))
    }

    private fun noArgs(name: String, f: (PySocketObj) -> Any?) = varargs(name) { s, a ->
        if (a.isNotEmpty()) throw PyRt.typeError("socket.$name() takes no arguments (${a.size} given)")
        f(s)
    }

    private fun oneArg(name: String, f: (PySocketObj, Any?) -> Any?) = varargs(name) { s, a ->
        if (a.size != 1) throw PyRt.typeError("socket.$name() takes exactly one argument (${a.size} given)")
        f(s, a[0])
    }

    /** A method taking keywords (PyArg_ParseTupleAndKeywords'). */
    private fun withKw(name: String, f: (PySocketObj, Array<Any?>, PyDict?) -> Any?) {
        SOCKET.def(name, PyBuiltin(name, { _, a -> f(sock(a.getOrNull(0), name), rest(a), null) }, { _, a, kw ->
            f(sock(a.getOrNull(0), name), rest(a), kw)
        }))
    }

    private fun refused(): PyException = PyPosix.error(PyErrno.EPERM)

    // ------------------------------------------------------------------ options

    private fun le32(b: ByteArray, off: Int = 0): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or ((b[off + 2].toInt() and 0xFF) shl 16) or (b[off + 3].toInt() shl 24)

    private fun int32(vararg v: Int): ByteArray = ByteArray(v.size * 4).also { b ->
        for ((i, x) in v.withIndex()) for (k in 0..3) b[i * 4 + k] = (x shr (8 * k)).toByte()
    }

    /** An option's size and default; [readOnly]: setsockopt refuses it (ENOPROTOOPT); [bool]: set as 0 or 1. */
    private class Opt(@JvmField val size: Int, @JvmField val default: Int, @JvmField val readOnly: Boolean = false,
                      @JvmField val bool: Boolean = false, @JvmField val min: Int = Int.MIN_VALUE, @JvmField val max: Int = Int.MAX_VALUE)

    private val SOCKET_OPTS = mapOf(
        1 to Opt(4, 0, bool = true), 2 to Opt(4, 0, bool = true), 3 to Opt(4, 0, readOnly = true), 4 to Opt(4, 0, readOnly = true),
        5 to Opt(4, 0, bool = true), 6 to Opt(4, 0, bool = true), 7 to Opt(4, 0), 8 to Opt(4, 0), 9 to Opt(4, 0, bool = true),
        10 to Opt(4, 0, bool = true), 12 to Opt(4, 0), 13 to Opt(8, 0), 15 to Opt(4, 0, bool = true), 16 to Opt(4, 0, bool = true),
        17 to Opt(12, 0, readOnly = true), 18 to Opt(4, 1), 19 to Opt(4, 1), 20 to Opt(16, 0), 21 to Opt(16, 0),
        30 to Opt(4, 0, readOnly = true), 38 to Opt(4, 0, readOnly = true), 39 to Opt(4, 0, readOnly = true),
    )
    private val TCP_OPTS = mapOf(
        1 to Opt(4, 0, bool = true), 2 to Opt(4, 536, min = 8, max = 32767), 3 to Opt(4, 0, bool = true),
        4 to Opt(4, 7200, min = 1, max = 32767), 5 to Opt(4, 75, min = 1, max = 32767), 6 to Opt(4, 9, min = 1, max = 127),
        12 to Opt(4, 1, bool = true), 18 to Opt(4, 0, min = 0),
    )
    private val IP_OPTS = mapOf(1 to Opt(4, 0), 2 to Opt(4, 64, min = -1, max = 255))
    private val IPV6_OPTS = mapOf(16 to Opt(4, 64, min = -1, max = 255), 26 to Opt(4, 0, bool = true))

    /** The option [name] of [level] that [e] has (Linux's errors where it has none). */
    private fun opt(e: PyPipeEnd, level: Int, name: Int): Opt {
        val table = when {
            level == SOL_SOCKET -> SOCKET_OPTS
            e.family == AF_UNIX -> throw PyPosix.error(PyErrno.EOPNOTSUPP)
            level == IPPROTO_TCP -> if (e.type == SOCK_STREAM) TCP_OPTS else throw PyPosix.error(PyErrno.ENOPROTOOPT)
            level == IPPROTO_IP -> IP_OPTS
            level == IPPROTO_IPV6 -> if (e.family == AF_INET6) IPV6_OPTS else throw PyPosix.error(PyErrno.EOPNOTSUPP)
            else -> throw PyPosix.error(PyErrno.ENOPROTOOPT)
        }
        return table[name] ?: throw PyPosix.error(PyErrno.ENOPROTOOPT)
    }

    /** getsockopt's bytes of [name]: what was set, else [e]'s own (its type, buffers...), else the default. */
    private fun getOpt(e: PyPipeEnd, level: Int, name: Int): ByteArray {
        val o = opt(e, level, name)
        if (level == SOL_SOCKET) when (name) {
            3 -> return int32(e.type)
            4 -> return int32(if (e.reset) PyErrno.ECONNRESET.also { e.reset = false } else 0)
            7 -> return int32(e.sndbuf)
            8 -> return int32(e.rcvbuf)
            // (SO_PEERCRED: NeonPy's process, pid 1 uid 0, where there is a peer; else none's)
            17 -> return if (e.connected) int32(1, 0, 0) else int32(0, 65534, 65534)
            38 -> return int32(e.proto)
            39 -> return int32(e.family)
        }
        e.options[(level shl 16) or name]?.let { return it }
        return ByteArray(o.size).also { if (o.size == 4) int32(o.default).copyInto(it) }
    }

    /** setsockopt of [name]: [value] its bytes (null: a NULL pointer of [optlen] bytes). */
    private fun setOpt(e: PyPipeEnd, level: Int, name: Int, value: ByteArray?, optlen: Long) {
        val o = opt(e, level, name)
        if (o.readOnly || level == SOL_SOCKET && name == 19) throw PyPosix.error(PyErrno.ENOPROTOOPT)
        if (value == null) throw PyPosix.error(if (optlen < o.size) PyErrno.EINVAL else PyErrno.EFAULT)
        if (value.size < o.size) throw PyPosix.error(PyErrno.EINVAL)
        val key = (level shl 16) or name
        if (o.size != 4) {
            val v = value.copyOf(o.size)
            // (SO_LINGER: its onoff kept as 0 or 1)
            if (level == SOL_SOCKET && name == 13) int32(if (le32(v) != 0) 1 else 0).copyInto(v)
            e.options[key] = v
            return
        }
        var v = le32(value)
        if (level == SOL_SOCKET) when (name) {
            // (doubled, at most net.core.wmem_max's or rmem_max's double; at least Linux's SOCK_MIN_SNDBUF, SOCK_MIN_RCVBUF)
            7 -> {
                e.sndbuf = maxOf((Integer.toUnsignedLong(v).coerceAtMost(BUF_MAX.toLong()) * 2).toInt(), 4608)
                return
            }
            8 -> {
                e.rcvbuf = maxOf((Integer.toUnsignedLong(v).coerceAtMost(BUF_MAX.toLong()) * 2).toInt(), 2304)
                return
            }
            12 -> if (v < 0 || v > 6) throw PyPosix.error(PyErrno.EPERM)
            18 -> v = if (v < 0) Int.MAX_VALUE else if (v == 0) 1 else v
        }
        if (o.bool) v = if (v != 0) 1 else 0
        else if (v < o.min || v > o.max) {
            // (IP_TTL and IPV6_UNICAST_HOPS: -1 the default)
            throw PyPosix.error(PyErrno.EINVAL)
        }
        if (v == -1 && o.min == -1) v = o.default
        e.options[key] = int32(v)
    }

    // ------------------------------------------------------------------ the socket type

    init {
        val t = SOCKET
        t.def("__doc__", PyStr(PySocketData.SOCKET_TYPE_DOC))
        t.def("__new__", PyBuiltin("__new__", { _, a -> PySocketObj(a[0] as PyType) }, { _, a, _ -> PySocketObj(a[0] as PyType) }))
        t.def("__init__", PyBuiltin("__init__", { _, a -> init(a, null) }, { _, a, kw -> init(a, kw) }))
        t.def("__repr__", PyBuiltin("__repr__") { _, a ->
            val s = sock(a[0], "__repr__")
            PyStr("<socket object, fd=${s.fd}, family=${s.family}, type=${s.kind}, proto=${s.proto}>")
        })
        fun prop(name: String, get: (PySocketObj) -> Any?) {
            t.def(name, PyProperty(PyBuiltin(name) { _, a -> get(sock(a[0], name)) }, null, null, null).also { it.name = name })
        }
        prop("family") { it.family.toLong() }
        prop("type") { it.kind.toLong() }
        prop("proto") { it.proto.toLong() }
        prop("timeout") { if (it.timeout < 0) PyNone else seconds(it.timeout) }

        noArgs("fileno") { it.fd.toLong() }
        noArgs("close") { s ->
            if (s.fd != -1) {
                val fd = s.fd
                s.fd = -1
                try {
                    PyPosix.close(PyContext.current(), state(), fd)
                } catch (e: PyException) {
                    if (!e.matches(PyExceptions.type("ConnectionResetError"))) throw e
                }
            }
            PyNone
        }
        noArgs("detach") { s ->
            val fd = s.fd
            s.fd = -1
            fd.toLong()
        }
        noArgs("gettimeout") { if (it.timeout < 0) PyNone else seconds(it.timeout) }
        noArgs("getblocking") { PyBool.of(it.timeout != 0L) }
        oneArg("settimeout") { s, v ->
            val t0 = timeoutArg(v)
            s.timeout = t0
            setBlocking(s, t0 < 0)
            PyNone
        }
        oneArg("setblocking") { s, v ->
            val block = PyRt.truthy(v)
            s.timeout = if (block) -1 else 0
            setBlocking(s, block)
            PyNone
        }
        varargs("recv") { s, a ->
            count("recv", a, 1, 2)
            val n = ssize(a[0])
            val flags = if (a.size > 1) cInt(a[1]) else 0
            if (n < 0) throw PyException("ValueError", "negative buffersize in recv")
            PyBytes(receive(s, n, flags))
        }
        withKw("recv_into") { s, a, kw -> recvInto(s, a, kw, "recv_into").toLong() }
        varargs("recvfrom") { s, a ->
            count("recvfrom", a, 1, 2)
            val n = ssize(a[0])
            val flags = if (a.size > 1) cInt(a[1]) else 0
            if (n < 0) throw PyException("ValueError", "negative buffersize in recvfrom")
            if (!endOf(s).connected) throw refused()
            PyTuple(arrayOf(PyBytes(receive(s, n, flags)), PyNone))
        }
        withKw("recvfrom_into") { s, a, kw -> PyTuple(arrayOf(recvInto(s, a, kw, "recvfrom_into").toLong(), PyNone)) }
        varargs("send") { s, a ->
            count("send", a, 1, 2)
            val b = bytesArg(a[0])
            val flags = if (a.size > 1) cInt(a[1]) else 0
            send(s, b, flags, 0, timeoutDeadline(s)).toLong()
        }
        varargs("sendall") { s, a ->
            count("sendall", a, 1, 2)
            val b = bytesArg(a[0])
            val flags = if (a.size > 1) cInt(a[1]) else 0
            // (one deadline for the whole: sock_sendall's)
            val deadline = timeoutDeadline(s)
            var off = 0
            do {
                if (s.timeout > 0 && deadline - System.nanoTime() < 0) throw PyException("TimeoutError", "timed out")
                off += send(s, b, flags, off, deadline)
            } while (off < b.size)
            PyNone
        }
        varargs("sendto") { s, a ->
            if (a.size !in 2..3) throw PyRt.typeError("sendto() takes 2 or 3 arguments (${a.size} given)")
            bytesArg(a[0])
            if (a.size == 3) cInt(a[1])
            // (an address given to a connected socket: EISCONN; to another, the network: refused)
            throw if (endOf(s).connected) PyPosix.error(PyErrno.EISCONN) else refused()
        }
        oneArg("shutdown") { s, v ->
            val how = cInt(v)
            val e = endOf(s)
            if (how < 0 || how > 2) throw PyPosix.error(PyErrno.EINVAL)
            if (!e.connected && e.family != AF_UNIX) throw PyPosix.error(PyErrno.ENOTCONN)
            val p = e.peer?.takeIf { !it.closed && (e.type == SOCK_STREAM || e.type == SOCK_SEQPACKET) }
            if (how != 1) {
                e.rdShut = true
                p?.wrShut = true
            }
            if (how != 0) {
                e.wrShut = true
                p?.rdShut = true
            }
            PyNone
        }
        varargs("getsockopt") { s, a ->
            count("getsockopt", a, 2, 3)
            val level = cInt(a[0])
            val name = cInt(a[1])
            val buflen = if (a.size > 2) cInt(a[2]) else 0
            if (buflen == 0) {
                le32(getOpt(endOf(s), level, name)).toLong()
            } else {
                if (buflen < 0 || buflen > 1024) throw PyException("OSError", "getsockopt buflen out of range")
                val v = getOpt(endOf(s), level, name)
                PyBytes(v.copyOf(minOf(buflen, v.size)))
            }
        }
        varargs("setsockopt") { s, a -> setsockopt(s, a) }
        // (the network's: refused; on a socket pair's end, Linux's own errors where it has one)
        oneArg("connect") { s, _ ->
            throw if (endOf(s).connected) PyPosix.error(PyErrno.EISCONN) else refused()
        }
        // (connect's errno given back, not raised)
        oneArg("connect_ex") { s, _ ->
            val e = state().fds[s.fd]?.end
            when {
                state().fds[s.fd] == null -> PyErrno.EBADF
                e == null || !e.isSocket -> PyErrno.ENOTSOCK
                e.connected -> PyErrno.EISCONN
                else -> PyErrno.EPERM
            }.toLong()
        }
        oneArg("bind") { s, _ -> endOf(s); throw refused() }
        varargs("listen") { s, a ->
            count("listen", a, 0, 1)
            if (a.isNotEmpty()) cInt(a[0])
            endOf(s)
            throw refused()
        }
        noArgs("_accept") { s -> endOf(s); throw refused() }
        noArgs("getsockname") { s ->
            when (endOf(s).family) {
                AF_UNIX -> PyStr("")
                AF_INET6 -> PyTuple(arrayOf(PyStr("::"), 0L, 0L, 0L))
                else -> PyTuple(arrayOf(PyStr("0.0.0.0"), 0L))
            }
        }
        noArgs("getpeername") { s -> if (endOf(s).connected) PyStr("") else throw PyPosix.error(PyErrno.ENOTCONN) }
    }

    /** socket.__init__(family=-1, type=-1, proto=-1, fileno=None): a new socket, or [fileno]'s. */
    private fun init(a: Array<Any?>, kw: PyDict?): Any? {
        val s = sock(a.getOrNull(0), "__init__")
        val v = PyMath.clinic("socket", rest(a), kw?.takeIf { it.size > 0 }, listOf("family", "type", "proto", "fileno"), 0, 0, 4)
        var family = v[0]?.let { asInt(it) } ?: -1
        var type = v[1]?.let { asInt(it) } ?: -1
        var proto = v[2]?.let { asInt(it) } ?: -1
        val fdobj = v[3]
        val st = state()
        val fd: Int
        if (fdobj != null && fdobj !== PyNone) {
            val n = index(fdobj) as? Long ?: throw PyException("OverflowError", "Python int too large to convert to C long")
            if (n < 0) throw PyException("ValueError", "negative file descriptor")
            // (getsockname's check: a descriptor open, and a socket's)
            val f = st.fds[n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()] ?: throw PyPosix.error(PyErrno.EBADF)
            val e = f.end?.takeIf { it.isSocket } ?: throw PyPosix.error(PyErrno.ENOTSOCK)
            if (family == -1) family = e.family
            if (type == -1) type = e.type
            if (proto == -1) proto = e.proto
            fd = n.toInt()
        } else {
            if (family == -1) family = AF_INET
            if (type == -1) type = SOCK_STREAM
            if (proto == -1) proto = 0
            fd = PyPosix.open(st, newEnd(family, type, proto), PyFileSystem.O_RDWR)
        }
        initObject(st, s, fd, family, type, proto)
        return PyNone
    }

    /** recv_into(buffer, nbytes=0, flags=0), recvfrom_into's too: how many bytes came. */
    private fun recvInto(s: PySocketObj, a: Array<Any?>, kw: PyDict?, fn: String): Int {
        val v = PyMath.clinic(fn, a, kw?.takeIf { it.size > 0 }, listOf("buffer", "nbytes", "flags"), 0, 1, 3)
        val buf = target(fn, v[0])
        var n = v[1]?.let { ssize(it) } ?: 0L
        val flags = v[2]?.let { cInt(it) } ?: 0
        if (n < 0) throw PyException("ValueError", "negative buffersize in $fn")
        if (n == 0L) n = buf.len.toLong()
        if (buf.len < n) throw PyException("ValueError", "buffer too small for requested bytes")
        if (fn == "recvfrom_into" && !endOf(s).connected) throw refused()
        val b = receive(s, n, flags)
        b.copyInto(buf.arr, buf.off)
        return b.size
    }

    /** setsockopt(level, optname, value: int | buffer) or (level, optname, None, optlen): CPython's three parses. */
    private fun setsockopt(s: PySocketObj, a: Array<Any?>): Any? {
        fun tryInt(v: Any?): Int? = try {
            cInt(v)
        } catch (e: PyException) {
            null
        }
        if (a.size == 3) {
            val level = tryInt(a[0])
            val name = tryInt(a[1])
            val flag = if (a[2] is PyBool || PyRt.indexValue(a[2]) != null) tryInt(a[2]) else null
            if (level != null && name != null && flag != null) {
                setOpt(endOf(s), level, name, int32(flag), 4)
                return PyNone
            }
        }
        if (a.size == 4 && a[2] === PyNone) {
            val level = tryInt(a[0])
            val name = tryInt(a[1])
            val len = (PyRt.indexValue(a[3]) as? Long)?.takeIf { it in 0..0xFFFFFFFFL }
            if (level != null && name != null && len != null) {
                setOpt(endOf(s), level, name, null, len)
                return PyNone
            }
        }
        count("setsockopt", a, 3, 3)
        val level = cInt(a[0])
        val name = cInt(a[1])
        val b = bytesArg(a[2])
        setOpt(endOf(s), level, name, b, b.size.toLong())
        return PyNone
    }

    // ------------------------------------------------------------------ addresses

    /** inet_aton as glibc reads one: up to four parts, each decimal, octal (0...) or hex (0x...), the last the rest. */
    private fun atonOrNull(s: String): ByteArray? {
        val parts = ArrayList<Long>()
        var i = 0
        while (true) {
            if (i >= s.length || !s[i].isDigit()) return null
            var base = 10
            if (s[i] == '0') {
                base = 8
                i++
                if (i < s.length && (s[i] == 'x' || s[i] == 'X')) {
                    base = 16
                    i++
                }
            }
            var v = 0L
            var digits = base == 8
            while (i < s.length) {
                val d = Character.digit(s[i], base)
                if (d < 0) break
                v = v * base + d
                if (v > 0xFFFFFFFFL) return null
                digits = true
                i++
            }
            if (!digits) return null
            parts.add(v)
            if (i < s.length && s[i] == '.') {
                if (parts.size == 4) return null
                i++
                continue
            }
            break
        }
        // (what follows may be white space and anything after it)
        if (i < s.length && !s[i].isWhitespace()) return null
        val n = parts.size
        for (k in 0 until n - 1) if (parts[k] > 0xFF) return null
        val last = parts[n - 1]
        if (last >= 1L shl (8 * (5 - n))) return null
        var addr = last
        for (k in 0 until n - 1) addr = addr or (parts[k] shl (8 * (3 - k)))
        return ByteArray(4) { (addr shr (8 * (3 - it))).toByte() }
    }

    /** inet_pton(AF_INET): four decimal parts, no leading zeros. */
    private fun pton4(s: String): ByteArray? {
        val parts = s.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for ((k, p) in parts.withIndex()) {
            if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' } || p.length > 1 && p[0] == '0') return null
            val v = p.toInt()
            if (v > 255) return null
            out[k] = v.toByte()
        }
        return out
    }

    /** inet_pton(AF_INET6): eight groups, one run of them `::`, the last two an IPv4 address where written so. */
    private fun pton6(s: String): ByteArray? {
        if (s.isEmpty()) return null
        val gap = s.indexOf("::")
        if (gap >= 0 && s.indexOf("::", gap + 1) >= 0) return null
        fun groups(part: String, tail: Boolean): List<Int>? {
            if (part.isEmpty()) return emptyList()
            val out = ArrayList<Int>()
            val bits = part.split(':')
            for ((k, g) in bits.withIndex()) {
                if (tail && k == bits.size - 1 && '.' in g) {
                    val v4 = pton4(g) ?: return null
                    out.add(((v4[0].toInt() and 0xFF) shl 8) or (v4[1].toInt() and 0xFF))
                    out.add(((v4[2].toInt() and 0xFF) shl 8) or (v4[3].toInt() and 0xFF))
                    continue
                }
                if (g.isEmpty() || g.length > 4 || !g.all { Character.digit(it, 16) >= 0 }) return null
                out.add(g.toInt(16))
            }
            return out
        }
        val words: List<Int>
        if (gap >= 0) {
            val head = groups(s.substring(0, gap), false) ?: return null
            val tail = groups(s.substring(gap + 2), true) ?: return null
            if (head.size + tail.size > 7) return null
            words = head + List(8 - head.size - tail.size) { 0 } + tail
        } else {
            words = groups(s, true) ?: return null
            if (words.size != 8) return null
        }
        return ByteArray(16) { (words[it / 2] shr (if (it % 2 == 0) 8 else 0)).toByte() }
    }

    /** inet_ntop(AF_INET6) as glibc writes one: the longest run of two or more zero groups `::`, IPv4 tails dotted. */
    private fun ntop6(b: ByteArray): String {
        val w = IntArray(8) { ((b[2 * it].toInt() and 0xFF) shl 8) or (b[2 * it + 1].toInt() and 0xFF) }
        var best = -1
        var bestLen = 0
        var i = 0
        while (i < 8) {
            if (w[i] == 0) {
                var j = i
                while (j < 8 && w[j] == 0) j++
                if (j - i > bestLen) {
                    best = i
                    bestLen = j - i
                }
                i = j
            } else i++
        }
        if (bestLen < 2) best = -1
        val sb = StringBuilder()
        i = 0
        while (i < 8) {
            if (i == best) {
                sb.append(':')
                i += bestLen
                if (i == 8) sb.append(':')
                continue
            }
            if (i != 0) sb.append(':')
            if (i == 6 && best == 0 && (bestLen == 6 || bestLen == 5 && w[5] == 0xFFFF)) {
                sb.append("${b[12].toInt() and 0xFF}.${b[13].toInt() and 0xFF}.${b[14].toInt() and 0xFF}.${b[15].toInt() and 0xFF}")
                break
            }
            sb.append(Integer.toHexString(w[i]))
            i++
        }
        return sb.toString()
    }

    private fun ntop4(b: ByteArray): String = b.joinToString(".") { (it.toInt() and 0xFF).toString() }

    /** An unsigned argument of [bits] bits (htons', htonl's). */
    private fun unsigned(v: Any?, bits: Int): Long {
        val i = index(v)
        val n = i as? Long
        if (n != null && n < 0 || n == null && (i as java.math.BigInteger).signum() < 0) throw PyException("ValueError", "Cannot convert negative int")
        if (n == null || n ushr bits != 0L) throw PyException("OverflowError", "Python int too large for C uint${bits}_t")
        return n
    }

    // ------------------------------------------------------------------ the modules

    @JvmStatic
    fun fill(ctx: PyContext, d: PyDict) {
        val st = PyPosix.state(ctx)
        fun def(name: String, impl: (Array<Any?>) -> Any?) {
            d[PyStr(name)] = PyBuiltin(name, { _, a -> impl(a) }, { _, a, kw ->
                if (kw.size > 0) throw PyRt.typeError("$name() takes no keyword arguments")
                impl(a)
            })
        }
        fun oneArg(name: String, impl: (Any?) -> Any?) = def(name) { a ->
            if (a.size != 1) throw PyRt.typeError("_socket.$name() takes exactly one argument (${a.size} given)")
            impl(a[0])
        }
        fun noArgs(name: String, impl: () -> Any?) = def(name) { a ->
            if (a.isNotEmpty()) throw PyRt.typeError("_socket.$name() takes no arguments (${a.size} given)")
            impl()
        }
        d[PyStr("__doc__")] = PyStr(PySocketData.SOCKET_DOC)
        for ((n, v) in PySocketData.ints(PySocketData.SOCKET_INTS)) d[PyStr(n)] = v
        d[PyStr("has_ipv6")] = PyBool.of(PySocketData.HAS_IPV6)
        d[PyStr("socket")] = SOCKET
        d[PyStr("SocketType")] = SOCKET
        d[PyStr("error")] = PyExceptions.type("OSError")
        d[PyStr("timeout")] = PyExceptions.type("TimeoutError")
        d[PyStr("herror")] = PySignal.errorType(ctx, "herror", "socket", "OSError")
        d[PyStr("gaierror")] = PySignal.errorType(ctx, "gaierror", "socket", "OSError")

        def("socketpair") { a ->
            count("socketpair", a, 0, 3)
            val family = if (a.isNotEmpty()) cInt(a[0]) else AF_UNIX
            val type = if (a.size > 1) cInt(a[1]) else SOCK_STREAM
            val proto = if (a.size > 2) cInt(a[2]) else 0
            val x = newEnd(family, type, proto)
            if (family != AF_UNIX) throw PyPosix.error(PyErrno.EOPNOTSUPP)
            val y = newEnd(family, type, proto)
            x.peer = y
            y.peer = x
            fun obj(e: PyPipeEnd) = PySocketObj(SOCKET).also { initObject(st, it, PyPosix.open(st, e, PyFileSystem.O_RDWR), family, type, proto) }
            PyTuple(arrayOf<Any?>(obj(x), obj(y)))
        }
        oneArg("dup") { v ->
            val n = index(v) as? Long ?: throw PyException("OverflowError", "Python int too large to convert to C long")
            if (n < 0 || n > Int.MAX_VALUE) throw PyPosix.error(PyErrno.EBADF)
            PyPosix.dup(st, n.toInt()).toLong()
        }
        oneArg("close") { v ->
            val n = index(v) as? Long ?: throw PyException("OverflowError", "Python int too large to convert to C long")
            if (n < 0 || n > Int.MAX_VALUE) throw PyPosix.error(PyErrno.EBADF)
            try {
                PyPosix.close(ctx, st, n.toInt())
            } catch (e: PyException) {
                if (!e.matches(PyExceptions.type("ConnectionResetError"))) throw e
            }
            PyNone
        }
        noArgs("getdefaulttimeout") { if (st.socketTimeout < 0) PyNone else seconds(st.socketTimeout) }
        oneArg("setdefaulttimeout") { v ->
            st.socketTimeout = timeoutArg(v)
            PyNone
        }
        oneArg("htons") { v -> java.lang.Short.reverseBytes(unsigned(v, 16).toInt().toShort()).toInt().and(0xFFFF).toLong() }
        oneArg("ntohs") { v -> java.lang.Short.reverseBytes(unsigned(v, 16).toInt().toShort()).toInt().and(0xFFFF).toLong() }
        oneArg("htonl") { v -> Integer.toUnsignedLong(Integer.reverseBytes(unsigned(v, 32).toInt())) }
        oneArg("ntohl") { v -> Integer.toUnsignedLong(Integer.reverseBytes(unsigned(v, 32).toInt())) }
        oneArg("inet_aton") { v ->
            val s = v as? PyStr ?: throw PyRt.typeError("inet_aton() argument must be str, not ${PyTypes.name(v)}")
            PyBytes(atonOrNull(s.s) ?: throw PyException("OSError", "illegal IP address string passed to inet_aton"))
        }
        oneArg("inet_ntoa") { v ->
            val b = bytesArg(v)
            if (b.size != 4) throw PyException("OSError", "packed IP wrong length for inet_ntoa")
            PyStr(ntop4(b))
        }
        def("inet_pton") { a ->
            count("inet_pton", a, 2, 2)
            val af = cInt(a[0])
            val s = a[1] as? PyStr ?: throw PyRt.typeError("inet_pton() argument 2 must be str, not ${PyTypes.name(a[1])}")
            val r = when (af) {
                AF_INET -> pton4(s.s)
                AF_INET6 -> pton6(s.s)
                else -> throw PyPosix.error(PyErrno.EAFNOSUPPORT)
            }
            PyBytes(r ?: throw PyException("OSError", "illegal IP address string passed to inet_pton"))
        }
        def("inet_ntop") { a ->
            count("inet_ntop", a, 2, 2)
            val af = cInt(a[0])
            val b = bytesArg(a[1])
            when (af) {
                AF_INET -> if (b.size != 4) throw PyException("ValueError", "invalid length of packed IP address string") else PyStr(ntop4(b))
                AF_INET6 -> if (b.size != 16) throw PyException("ValueError", "invalid length of packed IP address string") else PyStr(ntop6(b))
                else -> throw PyException("ValueError", "unknown address family $af")
            }
        }
        def("CMSG_LEN") { a ->
            count("CMSG_LEN", a, 1, 1)
            val n = ssize(a[0])
            if (n < 0 || n > Int.MAX_VALUE - 16) throw PyException("OverflowError", "CMSG_LEN() argument out of range")
            16 + n
        }
        def("CMSG_SPACE") { a ->
            count("CMSG_SPACE", a, 1, 1)
            val n = ssize(a[0])
            if (n < 0 || n > Int.MAX_VALUE - 24) throw PyException("OverflowError", "CMSG_SPACE() argument out of range")
            16 + ((n + 7) and 7L.inv())
        }
        // (the network's and the host's name databases: none is given)
        fun refusedFn(name: String, names: List<String>, required: Int) {
            d[PyStr(name)] = PyBuiltin(name, { _, a ->
                PyMath.clinic(name, a, null, names, 0, required, names.size)
                throw refused()
            }, { _, a, kw ->
                if (name != "getaddrinfo" && kw.size > 0) throw PyRt.typeError("$name() takes no keyword arguments")
                PyMath.clinic(name, a, kw.takeIf { it.size > 0 }, names, 0, required, names.size)
                throw refused()
            })
        }
        refusedFn("getaddrinfo", listOf("host", "port", "family", "type", "proto", "flags"), 2)
        refusedFn("getnameinfo", listOf("sockaddr", "flags"), 2)
        refusedFn("gethostbyname", listOf("host"), 1)
        refusedFn("gethostbyname_ex", listOf("host"), 1)
        refusedFn("gethostbyaddr", listOf("host"), 1)
        refusedFn("sethostname", listOf("name"), 1)
        refusedFn("getservbyname", listOf("servicename", "protocolname"), 1)
        refusedFn("getservbyport", listOf("port", "protocolname"), 1)
        refusedFn("getprotobyname", listOf("protocolname"), 1)
        refusedFn("if_nametoindex", listOf("oname"), 1)
        refusedFn("if_indextoname", listOf("if_index"), 1)
        noArgs("gethostname") { PyStr("localhost") }
        noArgs("if_nameindex") { throw refused() }
    }

    /** `select`: select() over the context's descriptors (no poll, epoll or kqueue: selectors' SelectSelector). */
    @JvmStatic
    fun fillSelect(ctx: PyContext, d: PyDict) {
        val st = PyPosix.state(ctx)
        d[PyStr("__doc__")] = PyStr(PySocketData.SELECT_DOC)
        for ((n, v) in PySocketData.ints(PySocketData.SELECT_INTS)) d[PyStr(n)] = v
        d[PyStr("error")] = PyExceptions.type("OSError")
        d[PyStr("select")] = PyBuiltin("select", { _, a -> select(ctx, st, a) }, { _, a, kw ->
            if (kw.size > 0) throw PyRt.typeError("select.select() takes no keyword arguments")
            select(ctx, st, a)
        })
    }

    /** PyObject_AsFileDescriptor: an int, or what fileno() returns; select's range. */
    private fun fdOf(ctx: PyContext, o: Any?): Int {
        val fd = if (o is Long || o is java.math.BigInteger || o is PyBool || o is PyNumSub && o.num !is Double) {
            asInt(o)
        } else {
            val m = try {
                PyRt.getAttr(o, "fileno")
            } catch (e: PyException) {
                if (!e.matches(PyExceptions.type("AttributeError"))) throw e
                throw PyRt.typeError("argument must be an int, or have a fileno() method.")
            }
            val r = PyCalls.callWith(ctx, m, NO_ARGS, NO_ARGS, NO_ARGS)
            if (!(r is Long || r is java.math.BigInteger || r is PyBool || r is PyNumSub && r.num !is Double)) throw PyRt.typeError("fileno() returned a non-integer")
            asInt(r)
        }
        if (fd < 0) throw PyException("ValueError", "file descriptor cannot be a negative integer ($fd)")
        if (fd >= 1024) throw PyException("ValueError", "filedescriptor out of range in select()")
        return fd
    }

    /** Whether descriptor [fd] is ready ([writing]: for a write, else a read): an end's state, a file's always. */
    private fun ready(st: PyPosix.State, fd: Int, writing: Boolean): Boolean {
        val f = st.fds[fd] ?: return false
        f.end?.let { return if (writing) writable(it) else readable(it) }
        // (the context's input is at its end at once, as a pipe's whose writer is gone; its outputs always take more)
        if (f.std == 0) return !writing
        if (f.std > 0) return writing
        return true
    }

    /** select(rlist, wlist, xlist, timeout=None): each list's ready ones; none exceptional. */
    private fun select(ctx: PyContext, st: PyPosix.State, a: Array<Any?>): Any? {
        if (a.size < 3) throw PyRt.typeError("select expected at least 3 arguments, got ${a.size}")
        if (a.size > 4) throw PyRt.typeError("select expected at most 4 arguments, got ${a.size}")
        val tv = a.getOrNull(3)
        val timeout = if (tv == null || tv === PyNone) -1L else {
            val t = try {
                PyTime.fromSecondsObject(tv, PyTime.ROUND_UP)
            } catch (e: PyException) {
                if (e.matches(PyExceptions.type("TypeError"))) throw PyRt.typeError("timeout must be a float or None")
                throw e
            }
            if (t < 0) throw PyException("ValueError", "timeout must be non-negative")
            t
        }
        val lists = Array(3) { i ->
            val items = try {
                PyBuiltins.toList(a[i])
            } catch (e: PyException) {
                if (e.matches(PyExceptions.type("TypeError"))) throw PyRt.typeError("arguments 1-3 must be sequences")
                throw e
            }
            items.map { it to fdOf(ctx, it) }
        }
        for (l in lists) for ((_, fd) in l) if (!st.fds.containsKey(fd)) throw PyPosix.error(PyErrno.EBADF)
        fun result(): PyTuple? {
            val r = lists[0].filter { ready(st, it.second, false) }
            val w = lists[1].filter { ready(st, it.second, true) }
            if (r.isEmpty() && w.isEmpty()) return null
            return PyTuple(arrayOf(PyList(r.mapTo(ArrayList<Any?>()) { it.first }), PyList(w.mapTo(ArrayList<Any?>()) { it.first }), PyList()))
        }
        result()?.let { return it }
        if (timeout < 0) throw forEver("cannot wait for descriptors nothing else can make ready")
        var r: PyTuple? = null
        if (timeout > 0) waitFor(ctx, deadline(timeout)) { result().also { r = it } != null }
        return r ?: PyTuple(arrayOf(PyList(), PyList(), PyList()))
    }
}
