package dev.mooner.neonwasm.wasi

import dev.mooner.neonwasm.WasmMemory

/** wasi_snapshot_preview1's error numbers (`errno`). */
object Errno {
    const val SUCCESS = 0
    const val TOOBIG = 1
    const val ACCES = 2
    const val AGAIN = 6
    const val BADF = 8
    const val BUSY = 10
    const val EXIST = 20
    const val FAULT = 21
    const val FBIG = 22
    const val ILSEQ = 25
    const val INTR = 27
    const val INVAL = 28
    const val IO = 29
    const val ISDIR = 31
    const val LOOP = 32
    const val MFILE = 33
    const val MLINK = 34
    const val NAMETOOLONG = 37
    const val NOENT = 44
    const val NOMEM = 48
    const val NOSPC = 51
    const val NOSYS = 52
    const val NOTDIR = 54
    const val NOTEMPTY = 55
    const val NOTSOCK = 57
    const val NOTSUP = 58
    const val NOTTY = 59
    const val OVERFLOW = 61
    const val PERM = 63
    const val PIPE = 64
    const val RANGE = 68
    const val ROFS = 69
    const val SPIPE = 70
    const val XDEV = 75
    const val NOTCAPABLE = 76
}

/** A WASI call's failure: its [errno] is what the call returns. */
class WasiError(@JvmField val errno: Int) : RuntimeException(null, null, false, false)

/** `proc_exit`: ends the program with [code] (not a trap, not a termination by the host's limits). */
class WasiExit(@JvmField val code: Int) : RuntimeException("exit $code", null, false, false)

internal fun fail(errno: Int): Nothing = throw WasiError(errno)

/** `filetype` */
object FileType {
    const val UNKNOWN = 0
    const val BLOCK_DEVICE = 1
    const val CHARACTER_DEVICE = 2
    const val DIRECTORY = 3
    const val REGULAR_FILE = 4
    const val SOCKET_DGRAM = 5
    const val SOCKET_STREAM = 6
    const val SYMBOLIC_LINK = 7
}

/** `fdflags`, `oflags`, `lookupflags`, `fstflags`, `whence`, `clockid`, event types. */
internal object Flags {
    const val FD_APPEND = 1
    const val FD_DSYNC = 2
    const val FD_NONBLOCK = 4
    const val FD_RSYNC = 8
    const val FD_SYNC = 16

    const val O_CREAT = 1
    const val O_DIRECTORY = 2
    const val O_EXCL = 4
    const val O_TRUNC = 8

    const val LOOKUP_SYMLINK_FOLLOW = 1

    const val FST_ATIM = 1
    const val FST_ATIM_NOW = 2
    const val FST_MTIM = 4
    const val FST_MTIM_NOW = 8

    const val WHENCE_SET = 0
    const val WHENCE_CUR = 1
    const val WHENCE_END = 2

    const val CLOCK_REALTIME = 0
    const val CLOCK_MONOTONIC = 1
    const val CLOCK_PROCESS_CPUTIME = 2
    const val CLOCK_THREAD_CPUTIME = 3

    const val EVENT_CLOCK = 0
    const val EVENT_FD_READ = 1
    const val EVENT_FD_WRITE = 2
    const val SUBSCRIPTION_CLOCK_ABSTIME = 1
}

/** `rights` */
object Rights {
    const val FD_DATASYNC = 1L shl 0
    const val FD_READ = 1L shl 1
    const val FD_SEEK = 1L shl 2
    const val FD_FDSTAT_SET_FLAGS = 1L shl 3
    const val FD_SYNC = 1L shl 4
    const val FD_TELL = 1L shl 5
    const val FD_WRITE = 1L shl 6
    const val FD_ADVISE = 1L shl 7
    const val FD_ALLOCATE = 1L shl 8
    const val PATH_CREATE_DIRECTORY = 1L shl 9
    const val PATH_CREATE_FILE = 1L shl 10
    const val PATH_LINK_SOURCE = 1L shl 11
    const val PATH_LINK_TARGET = 1L shl 12
    const val PATH_OPEN = 1L shl 13
    const val FD_READDIR = 1L shl 14
    const val PATH_READLINK = 1L shl 15
    const val PATH_RENAME_SOURCE = 1L shl 16
    const val PATH_RENAME_TARGET = 1L shl 17
    const val PATH_FILESTAT_GET = 1L shl 18
    const val PATH_FILESTAT_SET_SIZE = 1L shl 19
    const val PATH_FILESTAT_SET_TIMES = 1L shl 20
    const val FD_FILESTAT_GET = 1L shl 21
    const val FD_FILESTAT_SET_SIZE = 1L shl 22
    const val FD_FILESTAT_SET_TIMES = 1L shl 23
    const val PATH_SYMLINK = 1L shl 24
    const val PATH_REMOVE_DIRECTORY = 1L shl 25
    const val PATH_UNLINK_FILE = 1L shl 26
    const val POLL_FD_READWRITE = 1L shl 27
    const val SOCK_SHUTDOWN = 1L shl 28
    const val SOCK_ACCEPT = 1L shl 29
    const val ALL = (1L shl 30) - 1

    /**
     * What a directory's descriptor reports (as wasmtime's: what applies to directories, without FD_WRITE, FD_SEEK or
     * the SET_SIZE rights, which a program would then try to drop, and fd_fdstat_set_rights is not supported).
     */
    const val DIR_BASE = PATH_CREATE_DIRECTORY or PATH_CREATE_FILE or PATH_LINK_SOURCE or PATH_LINK_TARGET or PATH_OPEN or
        FD_READDIR or PATH_READLINK or PATH_RENAME_SOURCE or PATH_RENAME_TARGET or PATH_SYMLINK or PATH_REMOVE_DIRECTORY or
        PATH_UNLINK_FILE or PATH_FILESTAT_GET or PATH_FILESTAT_SET_TIMES or FD_FILESTAT_GET or FD_FILESTAT_SET_TIMES

    /** What a regular file's descriptor reports, but FD_READ and FD_WRITE (as it is opened). */
    const val FILE_BASE = FD_DATASYNC or FD_SEEK or FD_FDSTAT_SET_FLAGS or FD_SYNC or FD_TELL or FD_ADVISE or FD_ALLOCATE or
        FD_FILESTAT_GET or FD_FILESTAT_SET_SIZE or FD_FILESTAT_SET_TIMES or POLL_FD_READWRITE

    /** What a directory's descriptor passes on to what is opened through it. */
    const val DIR_INHERITING = DIR_BASE or FILE_BASE or FD_READ or FD_WRITE

    /** stdin's, and stdout's and stderr's. */
    const val STDIN = FD_READ or FD_FDSTAT_SET_FLAGS or FD_FILESTAT_GET or POLL_FD_READWRITE
    const val STDOUT = FD_WRITE or FD_FDSTAT_SET_FLAGS or FD_FILESTAT_GET or POLL_FD_READWRITE
}

/**
 * The guest's memory as WASI reads and writes it: little-endian, every access bounds-checked. An access out of
 * bounds is the call's EFAULT (the program goes on), not a trap.
 */
internal class GuestMemory(private val mem: WasmMemory) {
    val bytes: ByteArray get() = mem.bytes

    fun check(ptr: Int, len: Long) {
        val p = ptr.toLong() and 0xFFFFFFFFL
        if (len < 0 || p + len > mem.bytes.size) fail(Errno.FAULT)
    }

    fun u8(ptr: Int): Int { check(ptr, 1); return mem.bytes[ptr].toInt() and 0xFF }
    fun u16(ptr: Int): Int { check(ptr, 2); return (mem.bytes[ptr].toInt() and 0xFF) or ((mem.bytes[ptr + 1].toInt() and 0xFF) shl 8) }
    fun u32(ptr: Int): Int {
        check(ptr, 4)
        val b = mem.bytes
        return (b[ptr].toInt() and 0xFF) or ((b[ptr + 1].toInt() and 0xFF) shl 8) or ((b[ptr + 2].toInt() and 0xFF) shl 16) or
            (b[ptr + 3].toInt() shl 24)
    }
    fun u64(ptr: Int): Long = (u32(ptr).toLong() and 0xFFFFFFFFL) or (u32(ptr + 4).toLong() shl 32)

    fun put8(ptr: Int, v: Int) { check(ptr, 1); mem.bytes[ptr] = v.toByte() }
    fun put16(ptr: Int, v: Int) { check(ptr, 2); mem.bytes[ptr] = v.toByte(); mem.bytes[ptr + 1] = (v shr 8).toByte() }
    fun put32(ptr: Int, v: Int) {
        check(ptr, 4)
        val b = mem.bytes
        b[ptr] = v.toByte(); b[ptr + 1] = (v shr 8).toByte(); b[ptr + 2] = (v shr 16).toByte(); b[ptr + 3] = (v shr 24).toByte()
    }
    fun put64(ptr: Int, v: Long) { put32(ptr, v.toInt()); put32(ptr + 4, (v ushr 32).toInt()) }

    fun read(ptr: Int, len: Int): ByteArray {
        check(ptr, len.toLong() and 0xFFFFFFFFL)
        return mem.bytes.copyOfRange(ptr, ptr + len)
    }

    fun write(ptr: Int, data: ByteArray, off: Int = 0, len: Int = data.size - off) {
        check(ptr, len.toLong())
        System.arraycopy(data, off, mem.bytes, ptr, len)
    }

    /** A path or name: bytes that must be UTF-8 (EILSEQ). */
    fun string(ptr: Int, len: Int): String {
        val b = read(ptr, len)
        return try {
            UTF8.get().decode(java.nio.ByteBuffer.wrap(b)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            fail(Errno.ILSEQ)
        }
    }

    /** An array of iovecs (`buf`, `buf_len`): each as (pointer, length), checked. */
    fun iovecs(iovs: Int, count: Int): List<Pair<Int, Int>> {
        check(iovs, (count.toLong() and 0xFFFFFFFFL) * 8)
        return List(count) { i ->
            val p = u32(iovs + 8 * i)
            val n = u32(iovs + 8 * i + 4)
            check(p, n.toLong() and 0xFFFFFFFFL)
            p to n
        }
    }

    private companion object {
        val UTF8: ThreadLocal<java.nio.charset.CharsetDecoder> = ThreadLocal.withInitial {
            Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        }
    }
}
