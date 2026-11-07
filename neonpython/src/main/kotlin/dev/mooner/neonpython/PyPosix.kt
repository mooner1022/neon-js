package dev.mooner.neonpython

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.CodingErrorAction

/** os.DirEntry: one name scandir found, its stat taken when first asked for (and kept, as CPython's is). */
class PyDirEntry(@JvmField val name: Any?, @JvmField val path: Any?, @JvmField val full: String) : PyObject(PyPosix.DIRENTRY) {
    @JvmField var stat: Any? = null
    @JvmField var lstat: Any? = null
}

/** os.scandir's iterator: its directory's names, until they are done or it is closed. */
class PyScandir(@JvmField val names: Iterator<String>, @JvmField val make: (String) -> PyDirEntry) : PyObject(PyPosix.SCANDIR) {
    @JvmField var closed = false
}

/**
 * `posix` (docs/PYTHON_PLATFORM.md §12, S4), what `os` is made of, over the context's file system ([PyFileSystem]:
 * none by default) and its own working directory (`/` to begin with), file descriptors and environment: nothing of the
 * host's is seen but what the host gives. Linux's names and numbers (errno, O_*), as on Android. File descriptors 0,
 * 1 and 2 are the context's input (empty), output and error output; pipes and sockets are in memory ([PyPipeEnd]).
 * What a sandbox has no use for (users, terminals, starting processes) is not here, so `os` says it has none; no
 * process being started, waitpid finds no child (subprocess needs it), and signals are _signal's ([PySignal]).
 */
object PyPosix {
    private val NO_ARGS = arrayOfNulls<Any?>(0)

    // (their methods builtins dispatch takes: they are what these types do)
    @JvmField val DIRENTRY = PyTypes.newBuiltin("DirEntry", subclassable = false).also { it.module = "posix"; it.tpName = "posix.DirEntry"; it.dispatchBuiltins = true }
    @JvmField val SCANDIR = PyTypes.newBuiltin("ScandirIterator", subclassable = false).also { it.module = "posix"; it.tpName = "posix.ScandirIterator"; it.dispatchBuiltins = true }

    @JvmField val STAT_RESULT = PySys.structType("stat_result",
        listOf("st_mode", "st_ino", "st_dev", "st_nlink", "st_uid", "st_gid", "st_size", null, null, null),
        listOf("st_atime", "st_mtime", "st_ctime", "st_atime_ns", "st_mtime_ns", "st_ctime_ns", "st_blksize", "st_blocks", "st_rdev"),
        module = "os", constructable = true).also { t ->
        // (statresult_new: made from a tuple, the float times it leaves None are the int ones)
        val structNew = t.newImpl!!
        t.newImpl = { ctx, cls, a, kw ->
            val r = structNew(ctx, cls, a, kw) as PyTuple
            val hidden = r.sub!!.state as Array<Any?>
            for (i in 0..2) if (hidden[i] === PyNone) hidden[i] = r.items[7 + i]
            r
        }
    }
    @JvmField val TERMINAL_SIZE = PySys.structType("terminal_size", listOf("columns", "lines"), module = "os", constructable = true)
    @JvmField val UNAME_RESULT = PySys.structType("uname_result", listOf("sysname", "nodename", "release", "version", "machine"),
        module = "posix", constructable = true)

    /** A context's posix: its file system (the library's under it), working directory, umask, files and environment. */
    class State(ctx: PyContext) {
        @JvmField val fs: PyFileSystem = PyFileSystem.withLibrary(ctx.fileSystem)
        @JvmField var cwd = "/"
        @JvmField var umask = 0x12
        @JvmField val fds = HashMap<Int, Fd>()
        /**
         * The next descriptor a file gets: never one closed before (where POSIX gives the lowest free), as a file
         * object the collector finalizes late must not close a newer file given its number.
         */
        @JvmField var nextFd = 3
        /** The environment (what putenv and unsetenv change: os.environ's own mapping is os.py's). */
        @JvmField val env = LinkedHashMap<String, String>(ctx.environ)
        /** socket.setdefaulttimeout's (ns; -1: none), what a socket made is given. */
        @JvmField var socketTimeout = -1L
        /** The signals' dispositions, mask and wakeup descriptor ([PySignal]), made when _signal is first imported. */
        @JvmField var signals: PySignal.State? = null

        init {
            fds[0] = Fd(null, "<stdin>", PyFileSystem.O_RDONLY, std = 0)
            fds[1] = Fd(null, "<stdout>", PyFileSystem.O_WRONLY, std = 1)
            fds[2] = Fd(null, "<stderr>", PyFileSystem.O_WRONLY, std = 2)
        }
    }

    /**
     * An open file: its channel (none for the standard ones), what it was opened as, how it is to be inherited; or
     * one end of a pipe or socket in memory ([end]: os.pipe's, _socket's).
     */
    class Fd(@JvmField val channel: SeekableByteChannel?, @JvmField val path: String, @JvmField val flags: Int, @JvmField val std: Int = -1,
             @JvmField val end: PyPipeEnd? = null) {
        @JvmField var inheritable = false
        /** O_NONBLOCK clear (an end's is the end's: the descriptors dup made share it). */
        @JvmField var blocking = true
        /** How many descriptors are open on its file (dup's share this): the file closed with the last. */
        @JvmField var shared = intArrayOf(1)
        /** A standard output's: the bytes written to it made text (a character split between writes kept for the next). */
        @JvmField val decoder = if (std > 0) Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE) else null
        @JvmField var pending = ByteArray(0)
    }

    @JvmStatic
    fun state(ctx: PyContext): State = ctx.posix ?: State(ctx).also { ctx.posix = it }

    // ------------------------------------------------------------------ errors

    /** OSError(errno, strerror(errno)[, filename[, None, filename2]]): the subclass the errno picks. */
    @JvmStatic
    fun error(errno: Int, filename: Any? = null, filename2: Any? = null, message: String? = null): PyException {
        val ctx = PyContext.current()
        val msg = PyStr(message ?: PyErrno.strerror(errno))
        val args: Array<Any?> = when {
            filename2 != null -> arrayOf(errno.toLong(), msg, filename, PyNone, filename2)
            filename != null -> arrayOf(errno.toLong(), msg, filename)
            else -> arrayOf(errno.toLong(), msg)
        }
        val o = PyCalls.callWith(ctx, PyExceptions.type("OSError"), args, NO_ARGS, NO_ARGS) as PyBaseException
        return PyException(o).also { o.thrown = it }
    }

    private inline fun <T> fsCall(filename: Any?, filename2: Any? = null, f: () -> T): T = try {
        f()
    } catch (e: PyFsException) {
        throw error(e.errno, filename, filename2, e.message)
    }

    // ------------------------------------------------------------------ arguments

    /**
     * Arguments as Argument Clinic takes them: [names] by position or keyword (the first [posOnly] by position only),
     * [kwOnly] by keyword only, the first [required] required; CPython's messages.
     */
    private fun clinic(fn: String, a: Array<Any?>, kw: PyDict?, names: List<String>, required: Int, posOnly: Int = 0,
                       kwOnly: List<String> = emptyList()): Array<Any?> {
        val out = arrayOfNulls<Any?>(names.size + kwOnly.size)
        if (a.size > names.size) {
            val most = names.size
            val what = if (kwOnly.isEmpty()) "argument" else "positional argument"
            val how = if (required == most) "exactly" else "at most"
            throw PyRt.typeError("$fn() takes $how $most $what${if (most == 1) "" else "s"} (${a.size} given)")
        }
        a.copyInto(out)
        if (kw != null) for ((k, v) in kw.map) {
            val n = (k.v as PyStr).s
            val i = names.indexOf(n).takeIf { it >= posOnly } ?: kwOnly.indexOf(n).takeIf { it >= 0 }?.plus(names.size)
                ?: throw PyRt.typeError("$fn() got an unexpected keyword argument '$n'")
            if (i < a.size) throw PyRt.typeError("argument for $fn() given by name ('$n') and position (${i + 1})")
            out[i] = v
        }
        for (i in 0 until required) if (out[i] == null) throw PyRt.typeError("$fn() missing required argument '${names[i]}' (pos ${i + 1})")
        return out
    }

    /** A path argument: its text, whether it was bytes (what comes back is then), the object given; or a descriptor. */
    class PathArg(@JvmField val text: String?, @JvmField val bytes: Boolean, @JvmField val obj: Any?, @JvmField val fd: Int = -1)

    /** path_converter: str, bytes, os.PathLike, an int where [allowFd], None where [nullable]; CPython's messages. */
    private fun path(fn: String, v: Any?, arg: String = "path", allowFd: Boolean = false, nullable: Boolean = false): PathArg {
        if (nullable && (v == null || v === PyNone)) return PathArg(".", false, PyNone)
        if (allowFd && (v is Long || v is PyBool || v is java.math.BigInteger)) {
            val n = intArg(v)
            if (n < 0 || n > Int.MAX_VALUE) throw error(PyErrno.EBADF)
            return PathArg(null, false, v, n.toInt())
        }
        var p = v
        if (p !is PyStr && p !is PyBytes) {
            val f = PyTypes.typeOf(p).lookup("__fspath__")
            if (f == null) {
                val what = when {
                    allowFd && nullable -> "string, bytes, os.PathLike, integer or None"
                    allowFd -> "string, bytes, os.PathLike or integer"
                    nullable -> "string, bytes, os.PathLike or None"
                    else -> "string, bytes or os.PathLike"
                }
                throw PyRt.typeError("$fn: $arg should be $what, not ${PyTypes.typeOf(v).name}")
            }
            p = PyCalls.callWith(PyContext.current(), PyClasses.descrGet(null, f, p, PyTypes.typeOf(p)), NO_ARGS, NO_ARGS, NO_ARGS)
            if (p !is PyStr && p !is PyBytes) {
                throw PyRt.typeError("expected ${PyTypes.typeOf(v).name}.__fspath__() to return str or bytes, not ${PyTypes.typeOf(p).name}")
            }
        }
        val text = if (p is PyBytes) decode(p.b) else (p as PyStr).s
        if (text.indexOf('\u0000') >= 0) throw PyException("ValueError", "$fn: embedded null character in $arg")
        // (an error's filename is what __fspath__ gave, as 3.14's path_converter keeps)
        return PathArg(text, p is PyBytes, p)
    }

    /** The file system's encoding: UTF-8, bytes not UTF-8 kept as surrogates (surrogateescape). */
    private fun decode(b: ByteArray): String {
        val d = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            d.decode(ByteBuffer.wrap(b)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            val sb = StringBuilder()
            var i = 0
            while (i < b.size) {
                val c = b[i].toInt() and 0xFF
                val n = when {
                    c < 0x80 -> 1
                    c in 0xC2..0xDF -> 2
                    c in 0xE0..0xEF -> 3
                    c in 0xF0..0xF4 -> 4
                    else -> 0
                }
                val ok = n > 0 && i + n <= b.size && try {
                    sb.append(d.decode(ByteBuffer.wrap(b, i, n)))
                    true
                } catch (x: java.nio.charset.CharacterCodingException) {
                    false
                }
                if (ok) i += n else {
                    sb.append((0xDC00 + c).toChar())
                    i++
                }
            }
            sb.toString()
        }
    }

    private fun encode(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c in '\uDC80'..'\uDCFF') {
                out.write(c.code - 0xDC00)
                i++
                continue
            }
            val n = if (Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) 2 else 1
            out.write(s.substring(i, i + n).toByteArray(Charsets.UTF_8))
            i += n
        }
        return out.toByteArray()
    }

    /** A name or path given back: bytes where the path was given as bytes. */
    private fun back(s: String, bytes: Boolean): Any = if (bytes) PyBytes(encode(s)) else PyStr(s)

    private fun absolute(st: State, p: String): String = PyFileSystem.normalize(st.cwd, p)

    private fun noDirFd(fn: String, v: Any?) {
        if (v != null && v !== PyNone) throw PyException("NotImplementedError", "$fn: dir_fd unavailable on this platform")
    }

    private fun fdArg(v: Any?): Int {
        val n = intArg(v)
        return if (n < Int.MIN_VALUE || n > Int.MAX_VALUE) throw PyException("OverflowError", "fd is greater than maximum") else n.toInt()
    }

    private fun intArg(v: Any?): Long = when (val n = PyRt.indexValue(v) ?: throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer")) {
        is Long -> n
        is PyBool -> if (n === PyBool.TRUE) 1L else 0L
        is java.math.BigInteger -> throw PyException("OverflowError", "Python int too large to convert to C long")
        else -> throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer")
    }

    @JvmStatic
    internal fun file(st: State, fd: Int): Fd = st.fds[fd] ?: throw error(PyErrno.EBADF)

    /** A new descriptor for [end] (a pipe's or socket's), opened as [flags]. */
    @JvmStatic
    internal fun open(st: State, end: PyPipeEnd, flags: Int): Int {
        val fd = st.nextFd++
        st.fds[fd] = Fd(null, if (end.isSocket) "<socket>" else "<pipe>", flags, end = end)
        return fd
    }

    /** dup(fd): a new descriptor on [fd]'s file (EBADF where none is open), not inheritable. */
    @JvmStatic
    internal fun dup(st: State, fd: Int): Int {
        val f = file(st, fd)
        val n = st.nextFd++
        st.fds[n] = Fd(f.channel, f.path, f.flags, f.std, f.end).also {
            it.shared = f.shared
            f.shared[0]++
            it.blocking = f.blocking
        }
        return n
    }

    /** close(fd): EBADF where none is open; the file closed with its last descriptor. */
    @JvmStatic
    internal fun close(ctx: PyContext, st: State, fd: Int) {
        release(ctx, st.fds.remove(fd) ?: throw error(PyErrno.EBADF))
    }

    private fun release(ctx: PyContext, f: Fd) {
        if (f.std > 0) flushStd(ctx, f)
        if (--f.shared[0] > 0) return
        f.channel?.close()
        f.end?.let { PySocket.close(it) }
    }

    /** Whether [f] blocks (O_NONBLOCK clear): an end's is its end's. */
    @JvmStatic
    internal fun blocking(f: Fd): Boolean = f.end?.blocking ?: f.blocking

    @JvmStatic
    internal fun setBlocking(f: Fd, blocking: Boolean) {
        val e = f.end
        if (e != null) e.blocking = blocking else f.blocking = blocking
    }

    // ------------------------------------------------------------------ stat

    private fun statResult(s: PyFileStat): PyTuple {
        fun sec(ns: Long) = Math.floorDiv(ns, 1_000_000_000L)
        fun f(ns: Long) = ns / 1e9
        val visible = arrayOf<Any?>(s.mode.toLong(), s.ino, s.dev, s.nlink, s.uid, s.gid, s.size, sec(s.atimeNs), sec(s.mtimeNs), sec(s.ctimeNs))
        val hidden = arrayOf<Any?>(f(s.atimeNs), f(s.mtimeNs), f(s.ctimeNs), s.atimeNs, s.mtimeNs,
            s.ctimeNs, 4096L, (s.size + 511) / 512, 0L)
        return PySys.struct(STAT_RESULT, visible, hidden)
    }

    /** What fstat says of a descriptor: its file's, a standard one a pipe's, a socket's (S_IFSOCK, rwx for all). */
    private fun fdStat(st: State, fd: Int): PyFileStat {
        val f = file(st, fd)
        if (f.end?.isSocket == true) return PyFileStat(0xC000 or 0x1FF, 0, nlink = 1)
        if (f.channel == null) return PyFileStat(0x1000 or 0x180, 0, nlink = 1)
        return try {
            st.fs.stat(f.path, true).let { s -> PyFileStat(s.mode, f.channel.size(), s.atimeNs, s.mtimeNs, s.ctimeNs, s.ino, s.dev, s.nlink, s.uid, s.gid) }
        } catch (e: PyFsException) {
            PyFileStat(PyFileStat.S_IFREG or 0x1A4, f.channel.size())
        }
    }

    private fun stat(fn: String, a: Array<Any?>, kw: PyDict?, follow0: Boolean): Any? {
        val v = clinic(fn, a, kw, listOf("path"), 1, kwOnly = if (follow0) listOf("dir_fd", "follow_symlinks") else listOf("dir_fd"))
        noDirFd(fn, v[1])
        val follow = follow0 && (v.getOrNull(2)?.let { PyRt.truthy(it) } ?: true)
        val ctx = PyContext.current()
        val st = state(ctx)
        val p = path(fn, v[0], allowFd = follow0)
        if (p.fd >= 0) return statResult(fdStat(st, p.fd))
        return fsCall(p.obj) { statResult(st.fs.stat(absolute(st, p.text!!), follow)) }
    }

    // ------------------------------------------------------------------ the module

    @JvmStatic
    fun fill(ctx: PyContext, d: PyDict) {
        val st = state(ctx)
        fun def(name: String, impl: (Array<Any?>, PyDict?) -> Any?) {
            d[PyStr(name)] = PyBuiltin(name, { _, a -> impl(a, null) }, { _, a, kw -> impl(a, kw) })
        }
        d[PyStr("__doc__")] = PyStr(PyErrno.POSIX_DOC)
        for ((n, v) in PyErrno.POSIX_INTS) d[PyStr(n)] = v
        // (what os's supports_fd, supports_dir_fd and supports_follow_symlinks are made of: what this posix takes)
        d[PyStr("_have_functions")] = PyList(arrayListOf<Any?>(PyStr("HAVE_LSTAT"), PyStr("HAVE_FTRUNCATE")))
        d[PyStr("error")] = PyExceptions.type("OSError")
        d[PyStr("stat_result")] = STAT_RESULT
        d[PyStr("terminal_size")] = TERMINAL_SIZE
        d[PyStr("uname_result")] = UNAME_RESULT
        d[PyStr("DirEntry")] = DIRENTRY
        d[PyStr("environ")] = environ(st)

        def("_create_environ") { a, kw -> clinic("_create_environ", a, kw, emptyList(), 0); environ(st) }
        def("stat") { a, kw -> stat("stat", a, kw, true) }
        def("lstat") { a, kw -> stat("lstat", a, kw, false) }
        def("fstat") { a, kw ->
            val v = clinic("fstat", a, kw, listOf("fd"), 1)
            statResult(fdStat(st, fdArg(v[0])))
        }
        def("access") { a, kw ->
            val v = clinic("access", a, kw, listOf("path", "mode"), 2, kwOnly = listOf("dir_fd", "effective_ids", "follow_symlinks"))
            noDirFd("access", v[2])
            if (v[3] != null && PyRt.truthy(v[3])) throw PyException("NotImplementedError", "access: effective_ids unavailable on this platform")
            val mode = intArg(v[1]).toInt()
            val p = path("access", v[0])
            val s = try {
                st.fs.stat(absolute(st, p.text!!), v[4]?.let { PyRt.truthy(it) } ?: true)
            } catch (e: PyFsException) {
                return@def PyBool.FALSE
            }
            // (the owner's bits: the context owns what it sees)
            val ok = (mode and 4 == 0 || s.mode and 0x100 != 0) && (mode and 2 == 0 || s.mode and 0x80 != 0) &&
                (mode and 1 == 0 || s.mode and 0x40 != 0)
            PyBool.of(ok)
        }
        def("chdir") { a, kw ->
            val v = clinic("chdir", a, kw, listOf("path"), 1)
            val p = path("chdir", v[0])
            val target = absolute(st, p.text!!)
            fsCall(p.obj) {
                if (!st.fs.stat(target, true).isDirectory) throw PyFsException(PyErrno.ENOTDIR)
            }
            st.cwd = target
            PyNone
        }
        def("getcwd") { a, kw -> clinic("getcwd", a, kw, emptyList(), 0); PyStr(st.cwd) }
        def("getcwdb") { a, kw -> clinic("getcwdb", a, kw, emptyList(), 0); PyBytes(encode(st.cwd)) }
        def("listdir") { a, kw ->
            val v = clinic("listdir", a, kw, listOf("path"), 0)
            val p = path("listdir", v[0], allowFd = true, nullable = true)
            // (a descriptor: of no directory here, os.open opening none)
            if (p.fd >= 0) throw error(if (st.fds.containsKey(p.fd)) PyErrno.ENOTDIR else PyErrno.EBADF)
            val names = fsCall(p.obj.takeIf { it !== PyNone } ?: PyStr(".")) { st.fs.list(absolute(st, p.text!!)) }
            PyList(names.mapTo(ArrayList<Any?>()) { back(it, p.bytes) })
        }
        def("scandir") { a, kw ->
            val v = clinic("scandir", a, kw, listOf("path"), 0)
            val p = path("scandir", v[0], allowFd = true, nullable = true)
            if (p.fd >= 0) throw error(if (st.fds.containsKey(p.fd)) PyErrno.ENOTDIR else PyErrno.EBADF)
            val dir = absolute(st, p.text!!)
            val names = fsCall(p.obj.takeIf { it !== PyNone } ?: PyStr(".")) { st.fs.list(dir) }
            val shown = p.text
            PyScandir(names.iterator()) { n ->
                val joined = if (shown.endsWith("/")) shown + n else "$shown/$n"
                PyDirEntry(back(n, p.bytes), back(joined, p.bytes), if (dir == "/") "/$n" else "$dir/$n")
            }
        }
        def("mkdir") { a, kw ->
            val v = clinic("mkdir", a, kw, listOf("path", "mode"), 1, kwOnly = listOf("dir_fd"))
            noDirFd("mkdir", v[2])
            val mode = v[1]?.let { intArg(it).toInt() } ?: 0x1FF
            val p = path("mkdir", v[0])
            fsCall(p.obj) { st.fs.mkdir(absolute(st, p.text!!), mode and st.umask.inv() and 0xFFF) }
            PyNone
        }
        def("rmdir") { a, kw ->
            val v = clinic("rmdir", a, kw, listOf("path"), 1, kwOnly = listOf("dir_fd"))
            noDirFd("rmdir", v[1])
            val p = path("rmdir", v[0])
            fsCall(p.obj) { st.fs.rmdir(absolute(st, p.text!!)) }
            PyNone
        }
        for (name in listOf("unlink", "remove")) def(name) { a, kw ->
            val v = clinic(name, a, kw, listOf("path"), 1, kwOnly = listOf("dir_fd"))
            noDirFd(name, v[1])
            val p = path(name, v[0])
            fsCall(p.obj) { st.fs.unlink(absolute(st, p.text!!)) }
            PyNone
        }
        for (name in listOf("rename", "replace")) def(name) { a, kw ->
            val v = clinic(name, a, kw, listOf("src", "dst"), 2, kwOnly = listOf("src_dir_fd", "dst_dir_fd"))
            noDirFd(name, v[2])
            noDirFd(name, v[3])
            val s = path(name, v[0], "src")
            val t = path(name, v[1], "dst")
            fsCall(s.obj, t.obj) { st.fs.rename(absolute(st, s.text!!), absolute(st, t.text!!)) }
            PyNone
        }
        def("readlink") { a, kw ->
            val v = clinic("readlink", a, kw, listOf("path"), 1, kwOnly = listOf("dir_fd"))
            noDirFd("readlink", v[1])
            val p = path("readlink", v[0])
            back(fsCall(p.obj) { st.fs.readlink(absolute(st, p.text!!)) }, p.bytes)
        }
        def("symlink") { a, kw ->
            val v = clinic("symlink", a, kw, listOf("src", "dst", "target_is_directory"), 2, kwOnly = listOf("dir_fd"))
            noDirFd("symlink", v[3])
            val s = path("symlink", v[0], "src")
            val t = path("symlink", v[1], "dst")
            fsCall(s.obj, t.obj) { st.fs.symlink(s.text!!, absolute(st, t.text!!)) }
            PyNone
        }
        def("utime") { a, kw ->
            val v = clinic("utime", a, kw, listOf("path", "times"), 1, kwOnly = listOf("ns", "dir_fd", "follow_symlinks"))
            noDirFd("utime", v[3])
            val p = path("utime", v[0])
            val times = v[1]?.takeIf { it !== PyNone }
            val ns = v[2]
            if (times != null && ns != null) throw PyException("ValueError", "utime: you may specify either 'times' or 'ns' but not both")
            val (at, mt) = when {
                times != null -> {
                    if (times !is PyTuple || times.items.size != 2) throw PyRt.typeError("utime: 'times' must be either a tuple of two ints or None")
                    fun toNs(x: Any?): Long = if (x is Double) Math.round(x * 1e9) else intArg(x) * 1_000_000_000L
                    toNs(times.items[0]) to toNs(times.items[1])
                }
                ns != null -> {
                    if (ns !is PyTuple || ns.items.size != 2) throw PyRt.typeError("utime: 'ns' must be a tuple of two ints")
                    intArg(ns.items[0]) to intArg(ns.items[1])
                }
                else -> System.currentTimeMillis() * 1_000_000 to System.currentTimeMillis() * 1_000_000
            }
            fsCall(p.obj) { st.fs.utime(absolute(st, p.text!!), at, mt, v[4]?.let { PyRt.truthy(it) } ?: true) }
            PyNone
        }
        def("chmod") { a, kw ->
            val v = clinic("chmod", a, kw, listOf("path", "mode"), 2, kwOnly = listOf("dir_fd", "follow_symlinks"))
            noDirFd("chmod", v[2])
            val p = path("chmod", v[0])
            fsCall(p.obj) { st.fs.chmod(absolute(st, p.text!!), intArg(v[1]).toInt(), v[3]?.let { PyRt.truthy(it) } ?: true) }
            PyNone
        }
        def("truncate") { a, kw ->
            val v = clinic("truncate", a, kw, listOf("path", "length"), 2)
            val p = path("truncate", v[0], allowFd = true)
            val n = intArg(v[1])
            if (p.fd >= 0) {
                truncate(file(st, p.fd), n, null)
            } else {
                val ch = fsCall(p.obj) { st.fs.open(absolute(st, p.text!!), PyFileSystem.O_WRONLY, 0) }
                try {
                    truncate(Fd(ch, p.text!!, PyFileSystem.O_WRONLY), n, p.obj)
                } finally {
                    ch.close()
                }
            }
            PyNone
        }
        def("ftruncate") { a, kw ->
            val v = clinic("ftruncate", a, kw, listOf("fd", "length"), 2)
            truncate(file(st, fdArg(v[0])), intArg(v[1]), null)
            PyNone
        }

        // descriptors
        def("open") { a, kw ->
            val v = clinic("open", a, kw, listOf("path", "flags", "mode"), 2, kwOnly = listOf("dir_fd"))
            noDirFd("open", v[3])
            val p = path("open", v[0])
            val flags = intArg(v[1]).toInt()
            val mode = v[2]?.let { intArg(it).toInt() } ?: 0x1FF
            val abs = absolute(st, p.text!!)
            val ch = fsCall(p.obj) { st.fs.open(abs, flags, mode and st.umask.inv() and 0xFFF) }
            val fd = st.nextFd++
            st.fds[fd] = Fd(ch, abs, flags)
            fd.toLong()
        }
        def("close") { a, kw ->
            val v = clinic("close", a, kw, listOf("fd"), 1)
            close(ctx, st, fdArg(v[0]))
            PyNone
        }
        def("closerange") { a, kw ->
            val v = clinic("closerange", a, kw, listOf("fd_low", "fd_high"), 2, posOnly = 2)
            val lo = fdArg(v[0]).coerceAtLeast(0)
            val hi = fdArg(v[1])
            for (fd in st.fds.keys.filter { it in lo until hi }) st.fds.remove(fd)?.let { release(ctx, it) }
            PyNone
        }
        def("dup") { a, kw ->
            val v = clinic("dup", a, kw, listOf("fd"), 1, posOnly = 1)
            dup(st, fdArg(v[0])).toLong()
        }
        // (a pipe in memory: what is written to its write end waits, 64 KiB of it, until read from its read end)
        def("pipe") { a, kw ->
            clinic("pipe", a, kw, emptyList(), 0)
            val (r, w) = PySocket.pipe()
            PyTuple(arrayOf(open(st, r, PyFileSystem.O_RDONLY).toLong(), open(st, w, PyFileSystem.O_WRONLY).toLong()))
        }
        def("read") { a, kw ->
            val v = clinic("read", a, kw, listOf("fd", "length"), 2, posOnly = 2)
            val f = file(st, fdArg(v[0]))
            val n = intArg(v[1])
            if (n < 0) throw error(PyErrno.EINVAL)
            PyBytes(read(f, n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()))
        }
        def("readinto") { a, kw ->
            val v = clinic("readinto", a, kw, listOf("fd", "buffer"), 2, posOnly = 2)
            val f = file(st, fdArg(v[0]))
            when (val buf = v[1]) {
                is PyByteArray -> {
                    val b = read(f, buf.size)
                    b.copyInto(buf.b, 0)
                    b.size.toLong()
                }
                is PyArrayObj -> {
                    val b = read(f, buf.n * buf.itemsize)
                    b.copyInto(buf.b, 0)
                    b.size.toLong()
                }
                is PyMemoryView -> {
                    if (buf.readonly) throw PyRt.typeError("readinto() argument 2 must be read-write bytes-like object, not memoryview")
                    val b = read(f, buf.count * buf.itemsize)
                    val arr = buf.arr()
                    if (!buf.contiguous()) throw PyException("BufferError", "memoryview: underlying buffer is not C-contiguous")
                    b.copyInto(arr, buf.offset)
                    b.size.toLong()
                }
                else -> throw PyRt.typeError("readinto() argument 2 must be read-write bytes-like object, not ${PyTypes.name(buf)}")
            }
        }
        def("write") { a, kw ->
            val v = clinic("write", a, kw, listOf("fd", "data"), 2, posOnly = 2)
            val f = file(st, fdArg(v[0]))
            val b = PyByteSeqs.content(v[1]) ?: throw PyRt.typeError("a bytes-like object is required, not '${PyTypes.name(v[1])}'")
            write(ctx, f, b).toLong()
        }
        def("lseek") { a, kw ->
            val v = clinic("lseek", a, kw, listOf("fd", "position", "whence"), 3, posOnly = 3)
            val f = file(st, fdArg(v[0]))
            val pos = intArg(v[1])
            val how = intArg(v[2]).toInt()
            val ch = f.channel ?: throw error(PyErrno.ESPIPE)
            val base = when (how) {
                0 -> 0L
                1 -> ch.position()
                2 -> ch.size()
                else -> throw error(PyErrno.EINVAL)
            }
            if (base + pos < 0) throw error(PyErrno.EINVAL)
            ch.position(base + pos)
            (base + pos)
        }
        for (name in listOf("fsync", "fdatasync")) def(name) { a, kw ->
            val v = clinic(name, a, kw, listOf("fd"), 1)
            file(st, fileno(v[0]))
            PyNone
        }
        def("isatty") { a, kw ->
            clinic("isatty", a, kw, listOf("fd"), 1, posOnly = 1)
            PyBool.FALSE
        }
        def("device_encoding") { a, kw ->
            clinic("device_encoding", a, kw, listOf("fd"), 1)
            PyNone
        }
        def("get_inheritable") { a, kw -> PyBool.of(file(st, fdArg(clinic("get_inheritable", a, kw, listOf("fd"), 1, posOnly = 1)[0])).inheritable) }
        def("set_inheritable") { a, kw ->
            val v = clinic("set_inheritable", a, kw, listOf("fd", "inheritable"), 2, posOnly = 2)
            file(st, fdArg(v[0])).inheritable = PyRt.truthy(v[1])
            PyNone
        }
        def("get_blocking") { a, kw -> PyBool.of(blocking(file(st, fdArg(clinic("get_blocking", a, kw, listOf("fd"), 1, posOnly = 1)[0])))) }
        def("set_blocking") { a, kw ->
            val v = clinic("set_blocking", a, kw, listOf("fd", "blocking"), 2, posOnly = 2)
            setBlocking(file(st, fdArg(v[0])), PyRt.truthy(v[1]))
            PyNone
        }
        def("get_terminal_size") { a, kw ->
            val v = clinic("get_terminal_size", a, kw, listOf("fd"), 0, posOnly = 1)
            val fd = v[0]?.let { fdArg(it) } ?: 1
            if (!st.fds.containsKey(fd)) throw error(PyErrno.EBADF)
            throw error(PyErrno.ENOTTY)
        }

        // the rest
        def("fspath") { a, kw -> fspath(clinic("fspath", a, kw, listOf("path"), 1)[0]) }
        def("strerror") { a, kw -> PyStr(PyErrno.strerror(intArg(clinic("strerror", a, kw, listOf("code"), 1, posOnly = 1)[0]).toInt())) }
        def("urandom") { a, kw ->
            val n = intArg(clinic("urandom", a, kw, listOf("size"), 1, posOnly = 1)[0])
            if (n < 0) throw PyException("ValueError", "negative argument not allowed")
            PyBytes(ByteArray(n.toInt()).also { RANDOM.nextBytes(it) })
        }
        def("umask") { a, kw ->
            val v = clinic("umask", a, kw, listOf("mask"), 1, posOnly = 1)
            val old = st.umask
            st.umask = intArg(v[0]).toInt() and 0x1FF
            old.toLong()
        }
        def("getpid") { a, kw -> clinic("getpid", a, kw, emptyList(), 0); 1L }
        def("getppid") { a, kw -> clinic("getppid", a, kw, emptyList(), 0); 0L }
        for (name in listOf("getuid", "geteuid", "getgid", "getegid")) def(name) { a, kw -> clinic(name, a, kw, emptyList(), 0); 0L }
        def("cpu_count") { a, kw -> clinic("cpu_count", a, kw, emptyList(), 0); Runtime.getRuntime().availableProcessors().toLong() }
        def("uname") { a, kw ->
            clinic("uname", a, kw, emptyList(), 0)
            PySys.struct(UNAME_RESULT, PyStr("Linux"), PyStr("localhost"), PyStr(""), PyStr(""), PyStr(machine()))
        }
        def("putenv") { a, kw ->
            val v = clinic("putenv", a, kw, listOf("name", "value"), 2, posOnly = 2)
            val k = envText("putenv", v[0])
            if (k.isEmpty() || '=' in k) throw PyException("ValueError", "illegal environment variable name")
            st.env[k] = envText("putenv", v[1])
            PyNone
        }
        def("unsetenv") { a, kw ->
            val v = clinic("unsetenv", a, kw, listOf("name"), 1, posOnly = 1)
            val k = envText("unsetenv", v[0])
            if (k.isEmpty() || '=' in k) throw PyException("ValueError", "illegal environment variable name")
            st.env.remove(k)
            PyNone
        }
        // (no process is ever started (_posixsubprocess refuses): none to wait for; a status read as Linux's macros do)
        def("waitpid") { a, kw ->
            val v = clinic("waitpid", a, kw, listOf("pid", "options"), 2, posOnly = 2)
            intArg(v[0])
            intArg(v[1])
            throw error(PyErrno.ECHILD)
        }
        def("wait") { a, kw -> clinic("wait", a, kw, emptyList(), 0); throw error(PyErrno.ECHILD) }
        def("waitstatus_to_exitcode") { a, kw ->
            val s = statusArg("waitstatus_to_exitcode", a, kw)
            when {
                s and 0x7F == 0 -> ((s shr 8) and 0xFF).toLong()
                ((s and 0x7F) + 1).toByte() / 2 > 0 -> -(s and 0x7F).toLong()
                s and 0xFF == 0x7F -> throw PyException("ValueError", "process stopped by delivery of signal ${(s shr 8) and 0xFF}")
                else -> throw PyException("ValueError", "invalid wait status: $s")
            }
        }
        for ((name, test) in listOf<Pair<String, (Int) -> Any>>(
            "WIFEXITED" to { s -> PyBool.of(s and 0x7F == 0) },
            "WEXITSTATUS" to { s -> ((s shr 8) and 0xFF).toLong() },
            "WIFSIGNALED" to { s -> PyBool.of(((s and 0x7F) + 1).toByte() / 2 > 0) },
            "WTERMSIG" to { s -> (s and 0x7F).toLong() },
            "WIFSTOPPED" to { s -> PyBool.of(s and 0xFF == 0x7F) },
            "WSTOPSIG" to { s -> ((s shr 8) and 0xFF).toLong() },
            "WCOREDUMP" to { s -> PyBool.of(s and 0x80 != 0) },
            "WIFCONTINUED" to { s -> PyBool.of(s == 0xFFFF) },
        )) def(name) { a, kw -> test(statusArg(name, a, kw)) }
        // (no process to leave: SystemExit, which the host sees as the program's end)
        def("_exit") { a, kw ->
            val v = clinic("_exit", a, kw, listOf("status"), 1)
            throw PyException(PyCalls.callWith(ctx, PyExceptions.type("SystemExit"), arrayOf(intArg(v[0])), NO_ARGS, NO_ARGS) as PyBaseException)
        }
        for ((k, v) in d.map) if (v is PyBuiltin) v.method = false
    }

    private val RANDOM = java.security.SecureRandom()

    private fun machine(): String = when (val a = System.getProperty("os.arch")?.lowercase() ?: "") {
        "amd64", "x86_64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> a
    }

    /** A wait status (`status`, a C int). */
    private fun statusArg(fn: String, a: Array<Any?>, kw: PyDict?): Int {
        val n = intArg(clinic(fn, a, kw, listOf("status"), 1)[0])
        if (n > Int.MAX_VALUE || n < Int.MIN_VALUE) throw PyException("OverflowError", "Python int too large to convert to C int")
        return n.toInt()
    }

    private fun envText(fn: String, v: Any?): String {
        val p = path(fn, v, "name")
        return p.text!!
    }

    /** errno: its names, errno.errorcode. */
    @JvmStatic
    fun fillErrno(d: PyDict) {
        for ((n, v) in PyErrno.NAMES) d[PyStr(n)] = v.toLong()
        d[PyStr("errorcode")] = PyDict().also { m -> for ((v, n) in PyErrno.ERRORCODE) m[v.toLong()] = PyStr(n) }
    }

    /** posix.environ: the context's environment, its names and values bytes (os.environ decodes them). */
    private fun environ(st: State): PyDict = PyDict().also { d -> for ((k, v) in st.env) d[PyBytes(encode(k))] = PyBytes(encode(v)) }

    /** os.fspath(path): str and bytes as they are, else what its `__fspath__` gives (str or bytes). */
    @JvmStatic
    fun fspath(v: Any?): Any? {
        if (v is PyStr || v is PyBytes) return v
        val t = PyTypes.typeOf(v)
        val f = t.lookup("__fspath__") ?: throw PyRt.typeError("expected str, bytes or os.PathLike object, not ${t.name}")
        val r = PyCalls.callWith(PyContext.current(), PyClasses.descrGet(null, f, v, t), NO_ARGS, NO_ARGS, NO_ARGS)
        if (r !is PyStr && r !is PyBytes) throw PyRt.typeError("expected ${t.name}.__fspath__() to return str or bytes, not ${PyTypes.name(r)}")
        return r
    }

    /** A descriptor of an int or of an object with fileno() (os.fsync's). */
    private fun fileno(v: Any?): Int {
        if (PyRt.indexValue(v) != null) return fdArg(v)
        val f = try {
            PyRt.getAttr(v, "fileno")
        } catch (e: PyException) {
            throw PyRt.typeError("argument must be an int, or have a fileno() method.")
        }
        return fdArg(PyCalls.callWith(PyContext.current(), f, NO_ARGS, NO_ARGS, NO_ARGS))
    }

    private fun read(f: Fd, n: Int): ByteArray {
        if (f.std == 0) return ByteArray(0)
        f.end?.let { return PySocket.readFd(it, n) }
        val ch = f.channel ?: throw error(PyErrno.EBADF)
        if (f.flags and PyFileSystem.O_ACCMODE == PyFileSystem.O_WRONLY) throw error(PyErrno.EBADF)
        val buf = ByteBuffer.allocate(minOf(n, 1 shl 24))
        try {
            while (buf.hasRemaining()) if (ch.read(buf) <= 0) break
        } catch (e: java.io.IOException) {
            throw error(PyErrno.EIO)
        } catch (e: java.nio.channels.NonReadableChannelException) {
            throw error(PyErrno.EBADF)
        }
        return buf.array().copyOf(buf.position())
    }

    /** Bytes written to standard descriptor [fd] (1 or 2): sys.stdout.buffer's. */
    @JvmStatic
    fun writeStd(ctx: PyContext, fd: Int, b: ByteArray): Int = write(ctx, file(state(ctx), fd), b)

    @JvmStatic
    internal fun write(ctx: PyContext, f: Fd, b: ByteArray): Int {
        if (f.std == 0) throw error(PyErrno.EBADF)
        f.end?.let { return PySocket.writeFd(it, b) }
        if (f.std > 0) {
            // (a standard output: its bytes as text, a character split between writes waiting for the next)
            val all = if (f.pending.isEmpty()) b else f.pending + b
            val inb = ByteBuffer.wrap(all)
            val out = CharBuffer.allocate(all.size + 1)
            f.decoder!!.decode(inb, out, false)
            f.pending = all.copyOfRange(inb.position(), all.size)
            out.flip()
            (if (f.std == 1) ctx.out else ctx.err).append(out)
            return b.size
        }
        if (f.flags and PyFileSystem.O_ACCMODE == PyFileSystem.O_RDONLY) throw error(PyErrno.EBADF)
        val ch = f.channel ?: throw error(PyErrno.EBADF)
        try {
            if (f.flags and PyFileSystem.O_APPEND != 0) ch.position(ch.size())
            val buf = ByteBuffer.wrap(b)
            while (buf.hasRemaining()) ch.write(buf)
        } catch (e: PyFsException) {
            throw error(e.errno)
        } catch (e: java.io.IOException) {
            throw error(PyErrno.EIO)
        } catch (e: java.nio.channels.NonWritableChannelException) {
            throw error(PyErrno.EBADF)
        }
        return b.size
    }

    private fun flushStd(ctx: PyContext, f: Fd) {
        if (f.pending.isEmpty()) return
        (if (f.std == 1) ctx.out else ctx.err).append("�")
        f.pending = ByteArray(0)
    }

    private fun truncate(f: Fd, n: Long, filename: Any?) {
        if (n < 0) throw error(PyErrno.EINVAL, filename)
        val ch = f.channel ?: throw error(PyErrno.EINVAL, filename)
        if (f.flags and PyFileSystem.O_ACCMODE == PyFileSystem.O_RDONLY) throw error(PyErrno.EINVAL, filename)
        try {
            val size = ch.size()
            if (n < size) ch.truncate(n) else if (n > size) {
                // (longer: zeros to the new end, the position kept)
                val pos = ch.position()
                ch.position(size)
                val zeros = ByteBuffer.allocate((n - size).toInt())
                while (zeros.hasRemaining()) ch.write(zeros)
                ch.position(pos)
            }
        } catch (e: java.io.IOException) {
            throw error(PyErrno.EIO, filename)
        }
    }

    // ------------------------------------------------------------------ DirEntry, scandir's iterator

    private fun entryStat(e: PyDirEntry, follow: Boolean): Any? {
        val st = state(PyContext.current())
        if (!follow) return e.lstat ?: fsCall(e.path) { statResult(st.fs.stat(e.full, false)) }.also { e.lstat = it }
        e.stat?.let { return it }
        val l = entryStat(e, false) as PyTuple
        // (not a link: its lstat is its stat)
        val r = if ((l.items[0] as Long).toInt() and PyFileStat.S_IFMT != PyFileStat.S_IFLNK) l
            else fsCall(e.path) { statResult(st.fs.stat(e.full, true)) }
        e.stat = r
        return r
    }

    private fun entryIs(e: PyDirEntry, follow: Boolean, type: Int): Boolean = try {
        ((entryStat(e, follow) as PyTuple).items[0] as Long).toInt() and PyFileStat.S_IFMT == type
    } catch (x: PyException) {
        if (x.matches(PyExceptions.type("FileNotFoundError"))) false else throw x
    }

    private fun followArg(fn: String, a: Array<Any?>, kw: PyDict?): Boolean {
        if (a.size > 1) throw PyRt.typeError("DirEntry.$fn() takes no positional arguments")
        var follow = true
        kw?.map?.forEach { (k, v) ->
            if ((k.v as PyStr).s != "follow_symlinks") throw PyRt.typeError("DirEntry.$fn() got an unexpected keyword argument '${k.v}'")
            follow = PyRt.truthy(v)
        }
        return follow
    }

    init {
        DIRENTRY.newImpl = { _, _, _, _ -> throw PyRt.typeError("cannot create 'posix.DirEntry' instances") }
        fun e(a: Array<Any?>) = a[0] as PyDirEntry
        DIRENTRY.def("name", PyProperty(PyBuiltin("name") { _, a -> e(a).name }, null, null, null).also { it.name = "name" })
        DIRENTRY.def("path", PyProperty(PyBuiltin("path") { _, a -> e(a).path }, null, null, null).also { it.name = "path" })
        DIRENTRY.def("is_dir", PyBuiltin("is_dir", { _, a -> PyBool.of(entryIs(e(a), true, PyFileStat.S_IFDIR)) },
            { _, a, kw -> PyBool.of(entryIs(e(a), followArg("is_dir", a, kw), PyFileStat.S_IFDIR)) }))
        DIRENTRY.def("is_file", PyBuiltin("is_file", { _, a -> PyBool.of(entryIs(e(a), true, PyFileStat.S_IFREG)) },
            { _, a, kw -> PyBool.of(entryIs(e(a), followArg("is_file", a, kw), PyFileStat.S_IFREG)) }))
        DIRENTRY.def("is_symlink", PyBuiltin("is_symlink") { _, a -> PyBool.of(entryIs(e(a), false, PyFileStat.S_IFLNK)) })
        DIRENTRY.def("is_junction", PyBuiltin("is_junction") { _, _ -> PyBool.FALSE })
        DIRENTRY.def("stat", PyBuiltin("stat", { _, a -> entryStat(e(a), true) }, { _, a, kw -> entryStat(e(a), followArg("stat", a, kw)) }))
        DIRENTRY.def("inode", PyBuiltin("inode") { _, a -> (entryStat(e(a), false) as PyTuple).items[1] })
        DIRENTRY.def("__fspath__", PyBuiltin("__fspath__") { _, a -> e(a).path })
        DIRENTRY.def("__repr__", PyBuiltin("__repr__") { _, a -> PyStr("<DirEntry ${PyRt.repr(e(a).name)}>") })
        DIRENTRY.def("__class_getitem__", PyClassMethod(PyBuiltin("__class_getitem__") { _, a -> PyGenericAlias(a[0], PyTyping.argsOf(a[1])) }))

        SCANDIR.newImpl = { _, _, _, _ -> throw PyRt.typeError("cannot create 'posix.ScandirIterator' instances") }
        fun s(a: Array<Any?>) = a[0] as PyScandir
        SCANDIR.def("__iter__", PyBuiltin("__iter__") { _, a -> a[0] })
        SCANDIR.def("__next__", PyBuiltin("__next__") { _, a ->
            val it = s(a)
            if (it.closed || !it.names.hasNext()) {
                it.closed = true
                throw PyException("StopIteration", "")
            }
            it.make(it.names.next())
        })
        SCANDIR.def("close", PyBuiltin("close") { _, a -> s(a).closed = true; PyNone })
        SCANDIR.def("__enter__", PyBuiltin("__enter__") { _, a -> a[0] })
        SCANDIR.def("__exit__", PyBuiltin("__exit__") { _, a -> s(a).closed = true; PyNone })
    }
}
