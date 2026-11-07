package dev.mooner.neonwasm.wasi

import dev.mooner.neonwasm.HostFunction

/**
 * The file system's functions (fd_* on files and directories, path_*), over [WasiFs]. Paths are resolved by one
 * routine ([walk]) relative to a directory descriptor, which is the boundary: an absolute path, or `..` above that
 * directory (symbolic links included), is ENOTCAPABLE. Rights are reported (what applies to each descriptor) but not
 * enforced beyond reading and writing; fd_fdstat_set_rights is not supported, as in wasmtime.
 */
internal class WasiFiles(private val w: Wasi) {
    private val mem get() = w.mem

    fun register(m: MutableMap<String, HostFunction>) {
        m["fd_close"] = wasiFunction("i") { a ->
            if (w.fds.remove(a[0] as Int) == null) fail(Errno.BADF)
            Errno.SUCCESS
        }
        m["fd_renumber"] = wasiFunction("ii") { a ->
            val from = a[0] as Int
            val to = a[1] as Int
            val f = w.fd(from)
            w.fd(to)
            if (from != to) {
                w.fds[to] = f
                w.fds.remove(from)
            }
            Errno.SUCCESS
        }
        m["fd_fdstat_get"] = wasiFunction("ii") { a ->
            val f = w.fd(a[0] as Int)
            val p = a[1] as Int
            mem.check(p, 24)
            java.util.Arrays.fill(mem.bytes, p, p + 24, 0)
            mem.put8(p, f.fileType)
            mem.put16(p + 2, f.flags)
            mem.put64(p + 8, f.rightsBase)
            mem.put64(p + 16, f.rightsInheriting)
            Errno.SUCCESS
        }
        m["fd_fdstat_set_flags"] = wasiFunction("ii") { a ->
            val f = w.fd(a[0] as Int)
            val flags = a[1] as Int
            if (flags and (Flags.FD_APPEND or Flags.FD_DSYNC or Flags.FD_NONBLOCK or Flags.FD_RSYNC or Flags.FD_SYNC).inv() != 0) fail(Errno.INVAL)
            f.flags = flags
            Errno.SUCCESS
        }
        m["fd_fdstat_set_rights"] = wasiFunction("iII") { a ->
            w.fd(a[0] as Int)
            Errno.NOTSUP
        }
        m["fd_prestat_get"] = wasiFunction("ii") { a ->
            val name = preopen(a[0] as Int)
            val p = a[1] as Int
            mem.check(p, 8)
            java.util.Arrays.fill(mem.bytes, p, p + 8, 0)
            mem.put32(p + 4, name.size)
            Errno.SUCCESS
        }
        m["fd_prestat_dir_name"] = wasiFunction("iii") { a ->
            val name = preopen(a[0] as Int)
            if ((a[2] as Int).toLong() and 0xFFFFFFFFL < name.size) fail(Errno.NAMETOOLONG)
            mem.write(a[1] as Int, name)
            Errno.SUCCESS
        }
        m["fd_sync"] = wasiFunction("i") { a -> w.fd(a[0] as Int); Errno.SUCCESS }
        m["fd_datasync"] = wasiFunction("i") { a -> w.fd(a[0] as Int); Errno.SUCCESS }
        m["fd_advise"] = wasiFunction("iIIi") { a ->
            file(a[0] as Int)
            if (a[3] as Int !in 0..5) fail(Errno.INVAL)
            Errno.SUCCESS
        }
        m["fd_allocate"] = wasiFunction("iII") { a ->
            val f = file(a[0] as Int)
            val off = a[1] as Long
            val len = a[2] as Long
            if (off < 0 || len < 0) fail(Errno.INVAL)
            if (off + len > f.node.length) {
                f.node.resize(off + len)
                modified(f.node)
            }
            Errno.SUCCESS
        }
        m["fd_seek"] = wasiFunction("iIii") { a ->
            val f = file(a[0] as Int)
            val off = a[1] as Long
            val base = when (a[2] as Int) {
                Flags.WHENCE_SET -> 0L
                Flags.WHENCE_CUR -> f.pos
                Flags.WHENCE_END -> f.node.length
                else -> fail(Errno.INVAL)
            }
            val n = base + off
            if (n < 0) fail(Errno.INVAL)
            f.pos = n
            mem.put64(a[3] as Int, n)
            Errno.SUCCESS
        }
        m["fd_tell"] = wasiFunction("ii") { a ->
            mem.put64(a[1] as Int, file(a[0] as Int).pos)
            Errno.SUCCESS
        }
        m["fd_pread"] = wasiFunction("iiiIi") { a ->
            val off = a[3] as Long
            if (off < 0) fail(Errno.INVAL)
            mem.put32(a[4] as Int, read(w.fd(a[0] as Int), mem.iovecs(a[1] as Int, a[2] as Int), off).toInt())
            Errno.SUCCESS
        }
        m["fd_pwrite"] = wasiFunction("iiiIi") { a ->
            val off = a[3] as Long
            if (off < 0) fail(Errno.INVAL)
            mem.put32(a[4] as Int, write(w.fd(a[0] as Int), mem.iovecs(a[1] as Int, a[2] as Int), off).toInt())
            Errno.SUCCESS
        }
        m["fd_filestat_get"] = wasiFunction("ii") { a ->
            when (val f = w.fd(a[0] as Int)) {
                is Fd.File -> filestat(f.node, a[1] as Int)
                is Fd.Directory -> filestat(f.node, a[1] as Int)
                is Fd.Stream -> {
                    val p = a[1] as Int
                    mem.check(p, 64)
                    java.util.Arrays.fill(mem.bytes, p, p + 64, 0)
                    mem.put8(p + 16, f.fileType)
                }
            }
            Errno.SUCCESS
        }
        m["fd_filestat_set_size"] = wasiFunction("iI") { a ->
            val f = file(a[0] as Int)
            val n = a[1] as Long
            if (n < 0) fail(Errno.INVAL)
            f.node.resize(n)
            modified(f.node)
            Errno.SUCCESS
        }
        m["fd_filestat_set_times"] = wasiFunction("iIIi") { a ->
            val node = when (val f = w.fd(a[0] as Int)) {
                is Fd.File -> f.node
                is Fd.Directory -> f.node
                else -> fail(Errno.BADF)
            }
            setTimes(node, a[1] as Long, a[2] as Long, a[3] as Int)
            Errno.SUCCESS
        }
        m["fd_readdir"] = wasiFunction("iiiIi") { a -> readdir(a[0] as Int, a[1] as Int, a[2] as Int, a[3] as Long, a[4] as Int) }

        m["path_open"] = wasiFunction("iiiiiIIii") { a ->
            val fd = open(dir(a[0] as Int), a[1] as Int, path(a[2] as Int, a[3] as Int), a[4] as Int, a[5] as Long, a[7] as Int)
            mem.put32(a[8] as Int, fd)
            Errno.SUCCESS
        }
        m["path_filestat_get"] = wasiFunction("iiiii") { a ->
            val r = walk(dir(a[0] as Int), path(a[2] as Int, a[3] as Int), a[1] as Int and Flags.LOOKUP_SYMLINK_FOLLOW != 0)
            filestat(r.node ?: fail(Errno.NOENT), a[4] as Int)
            Errno.SUCCESS
        }
        m["path_filestat_set_times"] = wasiFunction("iiiiIIi") { a ->
            val fst = a[6] as Int
            checkTimes(fst)
            val r = walk(dir(a[0] as Int), path(a[2] as Int, a[3] as Int), a[1] as Int and Flags.LOOKUP_SYMLINK_FOLLOW != 0)
            setTimes(r.node ?: fail(Errno.NOENT), a[4] as Long, a[5] as Long, fst)
            Errno.SUCCESS
        }
        m["path_create_directory"] = wasiFunction("iii") { a ->
            val r = walk(dir(a[0] as Int), path(a[1] as Int, a[2] as Int), false)
            if (r.node != null || r.name == ".") fail(Errno.EXIST)
            r.dir.fs.link(r.dir, r.name, r.dir.fs.newDir(r.dir))
            Errno.SUCCESS
        }
        m["path_remove_directory"] = wasiFunction("iii") { a ->
            val r = walk(dir(a[0] as Int), path(a[1] as Int, a[2] as Int), false)
            val node = r.node ?: fail(Errno.NOENT)
            if (node !is WasiFs.Dir) fail(Errno.NOTDIR)
            if (r.name == ".") fail(Errno.INVAL)
            if (node.entries.isNotEmpty()) fail(Errno.NOTEMPTY)
            unlink(r.dir, r.name)
            Errno.SUCCESS
        }
        m["path_unlink_file"] = wasiFunction("iii") { a ->
            val r = walk(dir(a[0] as Int), path(a[1] as Int, a[2] as Int), false)
            val node = r.node ?: fail(Errno.NOENT)
            if (node is WasiFs.Dir) fail(Errno.ISDIR)
            unlink(r.dir, r.name)
            Errno.SUCCESS
        }
        m["path_rename"] = wasiFunction("iiiiii") { a ->
            rename(dir(a[0] as Int), path(a[1] as Int, a[2] as Int), dir(a[3] as Int), path(a[4] as Int, a[5] as Int))
            Errno.SUCCESS
        }
        m["path_symlink"] = wasiFunction("iiiii") { a ->
            val target = path(a[0] as Int, a[1] as Int)
            // (a link out of the sandbox could never be followed)
            if (target.startsWith("/")) fail(Errno.NOTCAPABLE)
            val r = walk(dir(a[2] as Int), path(a[3] as Int, a[4] as Int), false)
            if (r.node != null || r.name == ".") fail(Errno.EXIST)
            if (r.trailing) fail(Errno.NOENT)
            r.dir.fs.link(r.dir, r.name, r.dir.fs.newSymlink(target))
            Errno.SUCCESS
        }
        m["path_readlink"] = wasiFunction("iiiiii") { a ->
            val r = walk(dir(a[0] as Int), path(a[1] as Int, a[2] as Int), false)
            val link = r.node as? WasiFs.Symlink ?: fail(if (r.node == null) Errno.NOENT else Errno.INVAL)
            val b = link.target.toByteArray(Charsets.UTF_8)
            val n = minOf(b.size.toLong(), (a[4] as Int).toLong() and 0xFFFFFFFFL).toInt()
            mem.write(a[3] as Int, b, 0, n)
            mem.put32(a[5] as Int, n)
            Errno.SUCCESS
        }
        m["path_link"] = wasiFunction("iiiiiii") { a ->
            // (following a symbolic link at the source is not supported, as in wasmtime)
            if (a[1] as Int and Flags.LOOKUP_SYMLINK_FOLLOW != 0) fail(Errno.INVAL)
            val from = walk(dir(a[0] as Int), path(a[2] as Int, a[3] as Int), false)
            val node = from.node ?: fail(Errno.NOENT)
            if (node is WasiFs.Dir) fail(Errno.PERM)
            val to = walk(dir(a[4] as Int), path(a[5] as Int, a[6] as Int), false)
            if (to.node != null || to.name == ".") fail(Errno.EXIST)
            if (to.trailing) fail(Errno.NOENT)
            node.nlink++
            node.ctim = to.dir.fs.now()
            to.dir.fs.link(to.dir, to.name, node)
            Errno.SUCCESS
        }
    }

    // ------------------------------------------------------------------ descriptors

    private fun preopen(fd: Int): ByteArray {
        val d = w.fds[fd] as? Fd.Directory ?: fail(Errno.BADF)
        return (d.preopen ?: fail(Errno.BADF)).toByteArray(Charsets.UTF_8)
    }

    /** A regular file's descriptor: a directory's is EISDIR, a stream's ESPIPE. */
    private fun file(fd: Int): Fd.File = when (val f = w.fd(fd)) {
        is Fd.File -> f
        is Fd.Directory -> fail(Errno.ISDIR)
        is Fd.Stream -> fail(Errno.SPIPE)
    }

    /** A directory's descriptor, which a path is resolved from. */
    private fun dir(fd: Int): WasiFs.Dir = when (val f = w.fd(fd)) {
        is Fd.Directory -> f.node
        else -> fail(Errno.NOTDIR)
    }

    private fun path(ptr: Int, len: Int): String {
        val s = mem.string(ptr, len)
        if (s.indexOf('\u0000') >= 0) fail(Errno.INVAL)
        return s
    }

    private fun modified(node: WasiFs.Node) {
        val t = w.config.clock.realtimeNanos()
        node.mtim = t
        node.ctim = t
    }

    /** Reads into [vecs] at the file's position (moved), or at [offset] (not moved): the bytes read. */
    fun read(f: Fd, vecs: List<Pair<Int, Int>>, offset: Long): Long {
        val file = when (f) {
            is Fd.File -> f
            is Fd.Directory -> fail(Errno.ISDIR)
            is Fd.Stream -> fail(Errno.SPIPE)
        }
        if (!file.readable) fail(Errno.BADF)
        val node = file.node
        var pos = if (offset >= 0) offset else file.pos
        var total = 0L
        for ((p, len) in vecs) {
            if (pos >= node.length) break
            val n = minOf(len.toLong(), node.length - pos).toInt()
            System.arraycopy(node.data, pos.toInt(), mem.bytes, p, n)
            pos += n
            total += n
        }
        if (offset < 0) file.pos = pos
        return total
    }

    /** Writes [vecs] at the file's position (at its end with APPEND), or at [offset]: the bytes written. */
    fun write(f: Fd, vecs: List<Pair<Int, Int>>, offset: Long): Long {
        val file = when (f) {
            is Fd.File -> f
            is Fd.Directory -> fail(Errno.ISDIR)
            is Fd.Stream -> fail(Errno.SPIPE)
        }
        if (!file.writable) fail(Errno.BADF)
        val node = file.node
        var pos = when {
            offset >= 0 -> offset
            file.flags and Flags.FD_APPEND != 0 -> node.length
            else -> file.pos
        }
        var total = 0L
        for ((p, len) in vecs) {
            if (len == 0) continue
            if (pos + len > node.length) node.resize(pos + len)
            System.arraycopy(mem.bytes, p, node.data, pos.toInt(), len)
            pos += len
            total += len
        }
        if (offset < 0) file.pos = pos
        if (total > 0) modified(node)
        return total
    }

    /** What poll_oneoff reports as ready to read: what is left of a file, nothing known of a stream. */
    fun available(f: Fd): Long = if (f is Fd.File) maxOf(0L, f.node.length - f.pos) else 0L

    private fun filestat(node: WasiFs.Node, p: Int) {
        mem.check(p, 64)
        java.util.Arrays.fill(mem.bytes, p, p + 64, 0)
        mem.put64(p, DEV)
        mem.put64(p + 8, node.ino)
        mem.put8(p + 16, node.fileType)
        mem.put64(p + 24, node.nlink)
        mem.put64(p + 32, node.size)
        mem.put64(p + 40, node.atim)
        mem.put64(p + 48, node.mtim)
        mem.put64(p + 56, node.ctim)
    }

    private fun checkTimes(fst: Int) {
        if (fst and (Flags.FST_ATIM or Flags.FST_ATIM_NOW) == (Flags.FST_ATIM or Flags.FST_ATIM_NOW) ||
            fst and (Flags.FST_MTIM or Flags.FST_MTIM_NOW) == (Flags.FST_MTIM or Flags.FST_MTIM_NOW) ||
            fst and 0xF.inv() != 0) fail(Errno.INVAL)
    }

    private fun setTimes(node: WasiFs.Node, atim: Long, mtim: Long, fst: Int) {
        checkTimes(fst)
        val now = w.config.clock.realtimeNanos()
        if (fst and Flags.FST_ATIM != 0) node.atim = atim
        if (fst and Flags.FST_ATIM_NOW != 0) node.atim = now
        if (fst and Flags.FST_MTIM != 0) node.mtim = mtim
        if (fst and Flags.FST_MTIM_NOW != 0) node.mtim = now
        node.ctim = now
    }

    /**
     * fd_readdir: `.`, `..`, then the entries in order, entry i's cookie (d_next) i + 1. The buffer is filled
     * completely where more entries remain, the last one cut short: that is how a program knows to read on.
     */
    private fun readdir(fd: Int, buf: Int, bufLen: Int, cookie: Long, usedPtr: Int): Int {
        val d = when (val f = w.fd(fd)) {
            is Fd.Directory -> f.node
            is Fd.File -> fail(Errno.NOTDIR)
            is Fd.Stream -> fail(Errno.NOTDIR)
        }
        val len = (bufLen.toLong() and 0xFFFFFFFFL).toInt().coerceAtLeast(0)
        mem.check(buf, len.toLong())
        val out = java.io.ByteArrayOutputStream()
        val names = ArrayList<Pair<String, WasiFs.Node>>(d.entries.size + 2)
        names.add("." to d)
        names.add(".." to (d.parent ?: d))
        for ((k, v) in d.entries) names.add(k to v)
        var i = if (cookie < 0) names.size.toLong() else cookie
        val head = ByteArray(24)
        while (i < names.size && out.size() < len) {
            val (name, node) = names[i.toInt()]
            val nb = name.toByteArray(Charsets.UTF_8)
            putLong(head, 0, i + 1)
            putLong(head, 8, node.ino)
            putLong(head, 16, nb.size.toLong())
            head[20] = node.fileType.toByte()
            head[21] = 0; head[22] = 0; head[23] = 0
            out.write(head)
            out.write(nb)
            i++
        }
        val bytes = out.toByteArray()
        val n = minOf(bytes.size, len)
        mem.write(buf, bytes, 0, n)
        mem.put32(usedPtr, n)
        return Errno.SUCCESS
    }

    private fun putLong(b: ByteArray, at: Int, v: Long) {
        for (k in 0 until 8) b[at + k] = (v ushr (8 * k)).toByte()
    }

    // ------------------------------------------------------------------ paths

    /**
     * A resolved path: the directory holding its last component, that component's name ("." where the path ends at
     * a directory itself: ".", "..", "a/.."), the node there (null where there is none), and whether it ended
     * with a slash.
     */
    class Walk(@JvmField val dir: WasiFs.Dir, @JvmField val name: String, @JvmField val node: WasiFs.Node?, @JvmField val trailing: Boolean)

    /**
     * Resolves [path] from [base]: components one by one, `..` never above [base], symbolic links followed in the
     * middle of a path always and at its end where [follow] says so (or a slash follows), 40 of them at most.
     */
    fun walk(base: WasiFs.Dir, path: String, follow: Boolean): Walk {
        if (path.isEmpty()) fail(Errno.NOENT)
        if (path.startsWith("/")) fail(Errno.NOTCAPABLE)
        var trailing = path.endsWith("/")
        val comps = ArrayDeque(path.split('/').filter { it.isNotEmpty() })
        val stack = arrayListOf(base)
        var links = 0
        while (true) {
            val c = comps.removeFirstOrNull() ?: return Walk(stack.last(), ".", stack.last(), trailing)
            val last = comps.isEmpty()
            val cur = stack.last()
            when (c) {
                "." -> {
                    if (last) return Walk(cur, ".", cur, trailing)
                    continue
                }
                ".." -> {
                    if (stack.size == 1) fail(Errno.NOTCAPABLE)
                    stack.removeAt(stack.size - 1)
                    if (last) return Walk(stack.last(), ".", stack.last(), trailing)
                    continue
                }
            }
            val n = cur.entries[c]
            if (n is WasiFs.Symlink && (!last || follow || trailing)) {
                if (++links > 40) fail(Errno.LOOP)
                if (n.target.startsWith("/")) fail(Errno.NOTCAPABLE)
                // the target's components in place of the link's (a slash it ends with counts as the path's)
                if (last && n.target.endsWith("/")) trailing = true
                val t = n.target.split('/').filter { it.isNotEmpty() }
                for (k in t.indices.reversed()) comps.addFirst(t[k])
                if (t.isEmpty()) {
                    if (last) return Walk(cur, ".", cur, trailing)
                }
                continue
            }
            if (last) {
                if (trailing && n != null && n !is WasiFs.Dir && n !is WasiFs.Symlink) fail(Errno.NOTDIR)
                return Walk(cur, c, n, trailing)
            }
            when (n) {
                null -> fail(Errno.NOENT)
                is WasiFs.Dir -> stack.add(n)
                else -> fail(Errno.NOTDIR)
            }
        }
    }

    private fun unlink(dir: WasiFs.Dir, name: String) {
        val node = dir.entries.remove(name) ?: fail(Errno.NOENT)
        node.nlink--
        val t = w.config.clock.realtimeNanos()
        node.ctim = t
        dir.mtim = t
        dir.ctim = t
    }

    /** path_open: the new descriptor's number (the lowest free one past the standard streams). */
    private fun open(base: WasiFs.Dir, lookup: Int, path: String, oflags: Int, rights: Long, fdflags: Int): Int {
        val r = walk(base, path, lookup and Flags.LOOKUP_SYMLINK_FOLLOW != 0)
        var node = r.node
        if (node == null) {
            if (oflags and Flags.O_CREAT == 0) fail(Errno.NOENT)
            if (oflags and Flags.O_DIRECTORY != 0) fail(Errno.INVAL)
            if (r.trailing) fail(Errno.ISDIR)
            val fs = r.dir.fs
            node = fs.newFile()
            fs.link(r.dir, r.name, node)
        } else if (oflags and Flags.O_CREAT != 0 && oflags and Flags.O_EXCL != 0) fail(Errno.EXIST)
        val fd: Fd = when (node) {
            // a link at the end, not followed
            is WasiFs.Symlink -> fail(Errno.LOOP)
            is WasiFs.Dir -> {
                if (rights and Rights.FD_WRITE != 0L || oflags and Flags.O_TRUNC != 0) fail(Errno.ISDIR)
                Fd.Directory(node)
            }
            is WasiFs.File -> {
                if (oflags and Flags.O_DIRECTORY != 0) fail(Errno.NOTDIR)
                var readable = rights and (Rights.FD_READ or Rights.FD_READDIR) != 0L
                val writable = rights and (Rights.FD_WRITE or Rights.FD_DATASYNC or Rights.FD_ALLOCATE or Rights.FD_FILESTAT_SET_SIZE) != 0L
                if (!readable && !writable) readable = true
                if (oflags and Flags.O_TRUNC != 0 && node.length > 0) {
                    node.resize(0)
                    modified(node)
                }
                Fd.File(node, readable, writable)
            }
        }
        fd.flags = fdflags and (Flags.FD_APPEND or Flags.FD_DSYNC or Flags.FD_NONBLOCK or Flags.FD_RSYNC or Flags.FD_SYNC)
        var n = 3
        while (w.fds.containsKey(n)) n++
        w.fds[n] = fd
        return n
    }

    private fun rename(fromBase: WasiFs.Dir, from: String, toBase: WasiFs.Dir, to: String) {
        val a = walk(fromBase, from, false)
        val node = a.node ?: fail(Errno.NOENT)
        if (a.name == ".") fail(Errno.BUSY)
        val b = walk(toBase, to, false)
        if (b.name == ".") fail(Errno.BUSY)
        if (node !is WasiFs.Dir && (a.trailing || b.trailing)) fail(Errno.NOTDIR)
        val old = b.node
        if (old === node) return
        if (node is WasiFs.Dir) {
            // not into itself
            var d: WasiFs.Dir? = b.dir
            while (d != null) {
                if (d === node) fail(Errno.INVAL)
                d = d.parent
            }
            if (old != null) {
                if (old !is WasiFs.Dir) fail(Errno.NOTDIR)
                if (old.entries.isNotEmpty()) fail(Errno.NOTEMPTY)
            }
        } else if (old is WasiFs.Dir) fail(Errno.ISDIR)
        if (old != null) unlink(b.dir, b.name)
        a.dir.entries.remove(a.name)
        val fs = b.dir.fs
        val t = fs.now()
        a.dir.mtim = t
        a.dir.ctim = t
        node.ctim = t
        fs.link(b.dir, b.name, node)
    }

    companion object {
        /** The device every node is on: one file system. */
        const val DEV = 1L
    }
}
