package dev.mooner.neonpython

import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission

/**
 * An error of a [PyFileSystem] operation: its errno (Linux's numbers, the errno module's), which picks OSError's
 * subclass, and the message strerror gives for it where [message] is null.
 */
class PyFsException(@JvmField val errno: Int, message: String? = null) : RuntimeException(message)

/**
 * What stat() tells of a file (os.stat_result): its type and permission bits ([mode], st_mode's), its size, its times
 * in nanoseconds, and its identity.
 */
class PyFileStat(
    @JvmField val mode: Int,
    @JvmField val size: Long = 0,
    @JvmField val atimeNs: Long = 0,
    @JvmField val mtimeNs: Long = 0,
    @JvmField val ctimeNs: Long = 0,
    @JvmField val ino: Long = 0,
    @JvmField val dev: Long = 0,
    @JvmField val nlink: Long = 1,
    @JvmField val uid: Long = 0,
    @JvmField val gid: Long = 0,
) {
    val isDirectory: Boolean get() = mode and S_IFMT == S_IFDIR
    val isLink: Boolean get() = mode and S_IFMT == S_IFLNK

    companion object {
        const val S_IFMT = 0xF000
        const val S_IFDIR = 0x4000
        const val S_IFREG = 0x8000
        const val S_IFLNK = 0xA000
    }
}

/**
 * The file system a context's `os`, `open()` and `io` see (docs/PYTHON_PLATFORM.md §3.8, §12.2): none by default
 * ([NONE], every access a PermissionError, as GraalVM's default IOAccess.NONE denies it), a directory of the host's as
 * its root ([directory]), one in memory ([memory]), or the host's own implementation. Paths are absolute POSIX paths,
 * normalized (no `.`, `..` or `//`: NeonPy's posix resolves them against its working directory first); what they name
 * is the implementation's to say. Errors are [PyFsException]s with an errno. The bundled library is read-only under
 * `/usr/lib/python3.14` whatever the file system is ([withLibrary]: tracebacks and linecache read its sources).
 */
interface PyFileSystem {
    /** What is at [path] ([follow]: through a symbolic link at its end). */
    fun stat(path: String, follow: Boolean): PyFileStat

    /** The names in directory [path]. */
    fun list(path: String): List<String>

    /** The file at [path] opened by posix.open's [flags] (O_RDONLY, O_CREAT, O_EXCL, O_TRUNC, O_APPEND...) and [mode]. */
    fun open(path: String, flags: Int, mode: Int): SeekableByteChannel

    fun mkdir(path: String, mode: Int)
    fun rmdir(path: String)
    fun unlink(path: String)

    /** [src] renamed to [dst], a file there replaced (POSIX rename). */
    fun rename(src: String, dst: String)

    fun readlink(path: String): String = throw PyFsException(PyErrno.EINVAL)
    fun symlink(target: String, link: String): Unit = throw PyFsException(PyErrno.EPERM)
    fun utime(path: String, atimeNs: Long, mtimeNs: Long, follow: Boolean): Unit = throw PyFsException(PyErrno.EPERM)
    fun chmod(path: String, mode: Int, follow: Boolean): Unit = throw PyFsException(PyErrno.EPERM)

    companion object {
        // posix.open's flags (Linux's)
        const val O_ACCMODE = 3
        const val O_RDONLY = 0
        const val O_WRONLY = 1
        const val O_RDWR = 2
        const val O_CREAT = 0x40
        const val O_EXCL = 0x80
        const val O_TRUNC = 0x200
        const val O_APPEND = 0x400
        const val O_DIRECTORY = 0x10000
        const val O_NOFOLLOW = 0x20000

        /** No file system: every access a PermissionError (EPERM). */
        val NONE: PyFileSystem = object : PyFileSystem {
            private fun deny(): Nothing = throw PyFsException(PyErrno.EPERM)
            override fun stat(path: String, follow: Boolean) = deny()
            override fun list(path: String) = deny()
            override fun open(path: String, flags: Int, mode: Int) = deny()
            override fun mkdir(path: String, mode: Int) = deny()
            override fun rmdir(path: String) = deny()
            override fun unlink(path: String) = deny()
            override fun rename(src: String, dst: String) = deny()
            override fun readlink(path: String) = deny()
        }

        /**
         * The host's directory [root] as `/` (nothing outside it reachable: `..` stops at the root, a symbolic link
         * leading out of it is refused), read-only where [readOnly].
         */
        @JvmStatic @JvmOverloads
        fun directory(root: Path, readOnly: Boolean = false): PyFileSystem = PyDirectoryFs(root, readOnly)

        /**
         * A file system in memory, empty but for `/` and what [files] puts in it: each path its file's contents (a
         * path ending in `/` a directory), their directories made.
         */
        @JvmStatic @JvmOverloads
        fun memory(files: Map<String, ByteArray> = emptyMap()): PyFileSystem = PyMemoryFs().also { fs ->
            for ((p, b) in files) if (p.endsWith("/")) fs.mkdirs(normalize("/", p)) else fs.put(p, b)
        }

        /** [fs], every change refused (EROFS). */
        @JvmStatic
        fun readOnly(fs: PyFileSystem): PyFileSystem = object : PyFileSystem by fs {
            private fun refuse(): Nothing = throw PyFsException(PyErrno.EROFS)
            override fun open(path: String, flags: Int, mode: Int): SeekableByteChannel {
                if (flags and O_ACCMODE != O_RDONLY || flags and (O_CREAT or O_TRUNC) != 0) refuse()
                return fs.open(path, flags, mode)
            }
            override fun mkdir(path: String, mode: Int) = refuse()
            override fun rmdir(path: String) = refuse()
            override fun unlink(path: String) = refuse()
            override fun rename(src: String, dst: String) = refuse()
            override fun symlink(target: String, link: String) = refuse()
            override fun utime(path: String, atimeNs: Long, mtimeNs: Long, follow: Boolean) = refuse()
            override fun chmod(path: String, mode: Int, follow: Boolean) = refuse()
        }

        /** [fs] with the bundled library read-only at [PyStdlib.ROOT] (and `/usr`, `/usr/lib` directories). */
        @JvmStatic
        fun withLibrary(fs: PyFileSystem): PyFileSystem = PyLibraryFs(fs)

        /** [path] made absolute against [cwd] and normalized (`.` and `..` resolved, `..` of `/` staying `/`). */
        @JvmStatic
        fun normalize(cwd: String, path: String): String {
            val parts = ArrayList<String>()
            val all = if (path.startsWith("/")) path else "$cwd/$path"
            for (p in all.split('/')) when (p) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(p)
            }
            return "/" + parts.joinToString("/")
        }
    }
}

/** An in-memory channel over [data] (a file of [PyMemoryFs], a library file), [writable] or not. */
internal class PyBytesChannel(private val file: PyMemoryFs.Node?, private var data: ByteArray, private var length: Int,
                              private val readable: Boolean, private val writable: Boolean, private val append: Boolean) : SeekableByteChannel {
    private var pos = 0L
    private var open = true

    override fun isOpen() = open
    override fun close() {
        open = false
    }

    private fun check() {
        if (!open) throw ClosedChannelException()
    }

    override fun read(dst: ByteBuffer): Int {
        check()
        if (!readable) throw java.nio.channels.NonReadableChannelException()
        if (pos >= length) return -1
        val n = minOf(dst.remaining().toLong(), length - pos).toInt()
        dst.put(data, pos.toInt(), n)
        pos += n
        return n
    }

    override fun write(src: ByteBuffer): Int {
        check()
        if (!writable) throw NonWritableChannelException()
        if (append) pos = length.toLong()
        val n = src.remaining()
        val end = pos + n
        if (end > Int.MAX_VALUE) throw PyFsException(PyErrno.EFBIG)
        if (end > data.size) data = data.copyOf(maxOf(end.toInt(), data.size * 2, 16))
        if (pos > length) java.util.Arrays.fill(data, length, pos.toInt(), 0)
        src.get(data, pos.toInt(), n)
        pos = end
        if (end > length) length = end.toInt()
        file?.let { it.data = data; it.length = length; it.touch() }
        return n
    }

    override fun position() = pos
    override fun position(newPosition: Long): SeekableByteChannel {
        check()
        pos = newPosition
        return this
    }

    override fun size(): Long {
        check()
        return length.toLong()
    }

    override fun truncate(size: Long): SeekableByteChannel {
        check()
        if (!writable) throw NonWritableChannelException()
        if (size < length) {
            length = size.toInt()
            file?.let { it.length = length; it.touch() }
        }
        if (pos > size) pos = size
        return this
    }
}

/** [PyFileSystem.memory]: a tree of directories, files and symbolic links. */
internal class PyMemoryFs : PyFileSystem {
    class Node(@JvmField var mode: Int) {
        @JvmField val children = java.util.TreeMap<String, Node>()
        @JvmField var data = ByteArray(0)
        @JvmField var length = 0
        @JvmField var target = ""
        @JvmField val ino = nextIno++
        @JvmField var atimeNs = now()
        @JvmField var mtimeNs = atimeNs
        @JvmField var ctimeNs = atimeNs

        fun touch() {
            mtimeNs = now()
            ctimeNs = mtimeNs
        }

        val isDir get() = mode and PyFileStat.S_IFMT == PyFileStat.S_IFDIR
        val isLink get() = mode and PyFileStat.S_IFMT == PyFileStat.S_IFLNK
    }

    companion object {
        private var nextIno = 1L
        private fun now(): Long = System.currentTimeMillis() * 1_000_000
        private const val MAX_LINKS = 40
    }

    private val root = Node(PyFileStat.S_IFDIR or 0x1ED)

    fun put(path: String, bytes: ByteArray) {
        val p = PyFileSystem.normalize("/", path)
        val parent = p.substringBeforeLast('/').ifEmpty { "/" }
        mkdirs(parent)
        val n = Node(PyFileStat.S_IFREG or 0x1A4)
        n.data = bytes.copyOf()
        n.length = bytes.size
        (lookup(parent, true) ?: throw PyFsException(PyErrno.ENOENT)).children[p.substringAfterLast('/')] = n
    }

    fun mkdirs(path: String) {
        var cur = root
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            cur = cur.children.getOrPut(part) { Node(PyFileStat.S_IFDIR or 0x1ED) }
            if (!cur.isDir) throw PyFsException(PyErrno.ENOTDIR)
        }
    }

    /** The node at [path], links followed on the way (and at its end where [follow]); null where nothing is. */
    private fun lookup(path: String, follow: Boolean, depth: Int = 0): Node? {
        if (depth > MAX_LINKS) throw PyFsException(PyErrno.ELOOP)
        val parts = path.split('/').filter { it.isNotEmpty() }
        var cur = root
        var dir = "/"
        for ((i, part) in parts.withIndex()) {
            if (!cur.isDir) throw PyFsException(PyErrno.ENOTDIR)
            val next = cur.children[part] ?: return null
            val last = i == parts.size - 1
            if (next.isLink && (!last || follow)) {
                val t = PyFileSystem.normalize(dir, next.target)
                val rest = parts.subList(i + 1, parts.size).joinToString("/")
                return lookup(if (rest.isEmpty()) t else "$t/$rest", follow, depth + 1)
            }
            cur = next
            dir = if (dir == "/") "/$part" else "$dir/$part"
        }
        return cur
    }

    private fun parentOf(path: String): Pair<Node, String> {
        if (path == "/") throw PyFsException(PyErrno.EBUSY)
        val parent = lookup(path.substringBeforeLast('/').ifEmpty { "/" }, true) ?: throw PyFsException(PyErrno.ENOENT)
        if (!parent.isDir) throw PyFsException(PyErrno.ENOTDIR)
        return parent to path.substringAfterLast('/')
    }

    override fun stat(path: String, follow: Boolean): PyFileStat {
        val n = lookup(path, follow) ?: throw PyFsException(PyErrno.ENOENT)
        val size = when {
            n.isDir -> 4096L
            n.isLink -> n.target.toByteArray().size.toLong()
            else -> n.length.toLong()
        }
        return PyFileStat(n.mode, size, n.atimeNs, n.mtimeNs, n.ctimeNs, n.ino, 1, if (n.isDir) 2 else 1)
    }

    override fun list(path: String): List<String> {
        val n = lookup(path, true) ?: throw PyFsException(PyErrno.ENOENT)
        if (!n.isDir) throw PyFsException(PyErrno.ENOTDIR)
        return n.children.keys.toList()
    }

    override fun open(path: String, flags: Int, mode: Int): SeekableByteChannel {
        val acc = flags and PyFileSystem.O_ACCMODE
        var n = lookup(path, flags and PyFileSystem.O_NOFOLLOW == 0)
        if (n != null && flags and PyFileSystem.O_CREAT != 0 && flags and PyFileSystem.O_EXCL != 0) throw PyFsException(PyErrno.EEXIST)
        if (n == null) {
            if (flags and PyFileSystem.O_CREAT == 0) throw PyFsException(PyErrno.ENOENT)
            val (parent, name) = parentOf(path)
            n = Node(PyFileStat.S_IFREG or (mode and 0xFFF))
            parent.children[name] = n
            parent.touch()
        }
        if (n.isLink) throw PyFsException(PyErrno.ELOOP)
        // (a directory is not a file posix.open gives here: os.scandir and listdir name it instead)
        if (n.isDir) throw PyFsException(PyErrno.EISDIR)
        if (flags and PyFileSystem.O_DIRECTORY != 0) throw PyFsException(PyErrno.ENOTDIR)
        val writable = acc != PyFileSystem.O_RDONLY
        if (writable && flags and PyFileSystem.O_TRUNC != 0) {
            n.length = 0
            n.touch()
        }
        return PyBytesChannel(n, n.data, n.length, acc != PyFileSystem.O_WRONLY, writable, flags and PyFileSystem.O_APPEND != 0)
    }

    override fun mkdir(path: String, mode: Int) {
        val (parent, name) = parentOf(path)
        if (parent.children.containsKey(name)) throw PyFsException(PyErrno.EEXIST)
        parent.children[name] = Node(PyFileStat.S_IFDIR or (mode and 0xFFF))
        parent.touch()
    }

    override fun rmdir(path: String) {
        val (parent, name) = parentOf(path)
        val n = parent.children[name] ?: throw PyFsException(PyErrno.ENOENT)
        if (!n.isDir) throw PyFsException(PyErrno.ENOTDIR)
        if (n.children.isNotEmpty()) throw PyFsException(PyErrno.ENOTEMPTY)
        parent.children.remove(name)
        parent.touch()
    }

    override fun unlink(path: String) {
        val (parent, name) = parentOf(path)
        val n = parent.children[name] ?: throw PyFsException(PyErrno.ENOENT)
        if (n.isDir) throw PyFsException(PyErrno.EISDIR)
        parent.children.remove(name)
        parent.touch()
    }

    override fun rename(src: String, dst: String) {
        val (sp, sn) = parentOf(src)
        val n = sp.children[sn] ?: throw PyFsException(PyErrno.ENOENT)
        val (dp, dn) = parentOf(dst)
        if (src == dst) return
        if (n.isDir && (dst + "/").startsWith("$src/")) throw PyFsException(PyErrno.EINVAL)
        dp.children[dn]?.let { old ->
            if (old.isDir && !n.isDir) throw PyFsException(PyErrno.EISDIR)
            if (!old.isDir && n.isDir) throw PyFsException(PyErrno.ENOTDIR)
            if (old.isDir && old.children.isNotEmpty()) throw PyFsException(PyErrno.ENOTEMPTY)
        }
        sp.children.remove(sn)
        dp.children[dn] = n
        sp.touch()
        dp.touch()
    }

    override fun readlink(path: String): String {
        val n = lookup(path, false) ?: throw PyFsException(PyErrno.ENOENT)
        if (!n.isLink) throw PyFsException(PyErrno.EINVAL)
        return n.target
    }

    override fun symlink(target: String, link: String) {
        val (parent, name) = parentOf(link)
        if (parent.children.containsKey(name)) throw PyFsException(PyErrno.EEXIST)
        parent.children[name] = Node(PyFileStat.S_IFLNK or 0x1FF).also { it.target = target }
        parent.touch()
    }

    override fun utime(path: String, atimeNs: Long, mtimeNs: Long, follow: Boolean) {
        val n = lookup(path, follow) ?: throw PyFsException(PyErrno.ENOENT)
        n.atimeNs = atimeNs
        n.mtimeNs = mtimeNs
        n.ctimeNs = now()
    }

    override fun chmod(path: String, mode: Int, follow: Boolean) {
        val n = lookup(path, follow) ?: throw PyFsException(PyErrno.ENOENT)
        n.mode = (n.mode and PyFileStat.S_IFMT) or (mode and 0xFFF)
        n.ctimeNs = now()
    }
}

/** [PyFileSystem.directory]: the host's [root] as `/`. */
internal class PyDirectoryFs(root: Path, private val readOnly: Boolean) : PyFileSystem {
    private val root: Path = root.toAbsolutePath().normalize()
    private val realRoot: Path by lazy { this.root.toRealPath() }

    /** The host's path for [path] (normalized: it cannot leave the root by `..`), its links inside the root. */
    private fun host(path: String, follow: Boolean = true): Path {
        val p = if (path == "/") root else root.resolve(path.removePrefix("/"))
        // (a link that leads out of the root: refused, as FileSystemModuleLoader refuses it)
        try {
            val parent = p.parent
            val real = if (follow && Files.exists(p)) p.toRealPath() else if (parent != null && Files.exists(parent)) parent.toRealPath().resolve(p.fileName) else null
            if (real != null && !real.startsWith(realRoot)) throw PyFsException(PyErrno.EACCES)
        } catch (e: java.io.IOException) {
            throw errnoOf(e)
        }
        return p
    }

    private fun write() {
        if (readOnly) throw PyFsException(PyErrno.EROFS)
    }

    private fun <T> io(f: () -> T): T = try {
        f()
    } catch (e: java.io.IOException) {
        throw errnoOf(e)
    } catch (e: SecurityException) {
        throw PyFsException(PyErrno.EACCES)
    }

    override fun stat(path: String, follow: Boolean): PyFileStat = io {
        val p = host(path, follow)
        val opts = if (follow) arrayOf() else arrayOf(LinkOption.NOFOLLOW_LINKS)
        if (!Files.exists(p, *opts)) throw PyFsException(PyErrno.ENOENT)
        val a = Files.readAttributes(p, java.nio.file.attribute.BasicFileAttributes::class.java, *opts)
        val type = when {
            a.isSymbolicLink -> PyFileStat.S_IFLNK
            a.isDirectory -> PyFileStat.S_IFDIR
            else -> PyFileStat.S_IFREG
        }
        val perm = try {
            var m = 0
            for (x in Files.getPosixFilePermissions(p, *opts)) m = m or (1 shl (8 - x.ordinal))
            m
        } catch (e: UnsupportedOperationException) {
            (if (Files.isWritable(p)) 0x1B6 else 0x124) or (if (a.isDirectory) 0x49 else 0)
        }
        fun ns(t: FileTime) = t.to(java.util.concurrent.TimeUnit.NANOSECONDS)
        val key = a.fileKey()?.hashCode()?.toLong()?.and(0xFFFFFFFFL) ?: p.toString().hashCode().toLong().and(0xFFFFFFFFL)
        PyFileStat(type or perm, if (a.isDirectory) 4096 else a.size(), ns(a.lastAccessTime()), ns(a.lastModifiedTime()),
            ns(a.lastModifiedTime()), key, 1, if (a.isDirectory) 2 else 1)
    }

    override fun list(path: String): List<String> = io {
        val p = host(path)
        if (!Files.exists(p)) throw PyFsException(PyErrno.ENOENT)
        if (!Files.isDirectory(p)) throw PyFsException(PyErrno.ENOTDIR)
        Files.newDirectoryStream(p).use { s -> s.map { it.fileName.toString() } }
    }

    override fun open(path: String, flags: Int, mode: Int): SeekableByteChannel = io {
        val acc = flags and PyFileSystem.O_ACCMODE
        if (acc != PyFileSystem.O_RDONLY || flags and (PyFileSystem.O_CREAT or PyFileSystem.O_TRUNC) != 0) write()
        val p = host(path, flags and PyFileSystem.O_NOFOLLOW == 0)
        if (Files.isDirectory(p)) throw PyFsException(PyErrno.EISDIR)
        val opts = HashSet<java.nio.file.OpenOption>()
        if (acc != PyFileSystem.O_WRONLY) opts.add(StandardOpenOption.READ)
        if (acc != PyFileSystem.O_RDONLY) opts.add(StandardOpenOption.WRITE)
        if (flags and PyFileSystem.O_APPEND != 0) opts.add(StandardOpenOption.APPEND)
        if (flags and PyFileSystem.O_TRUNC != 0 && acc != PyFileSystem.O_RDONLY) opts.add(StandardOpenOption.TRUNCATE_EXISTING)
        if (flags and PyFileSystem.O_CREAT != 0) opts.add(if (flags and PyFileSystem.O_EXCL != 0) StandardOpenOption.CREATE_NEW else StandardOpenOption.CREATE)
        if (flags and PyFileSystem.O_NOFOLLOW != 0) opts.add(LinkOption.NOFOLLOW_LINKS)
        Files.newByteChannel(p, opts)
    }

    override fun mkdir(path: String, mode: Int) = io {
        write()
        Files.createDirectory(host(path))
        Unit
    }

    override fun rmdir(path: String) = io {
        write()
        val p = host(path, false)
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) throw PyFsException(PyErrno.ENOENT)
        if (!Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) throw PyFsException(PyErrno.ENOTDIR)
        Files.delete(p)
    }

    override fun unlink(path: String) = io {
        write()
        val p = host(path, false)
        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) throw PyFsException(PyErrno.EISDIR)
        Files.delete(p)
    }

    override fun rename(src: String, dst: String) = io {
        write()
        Files.move(host(src, false), host(dst, false), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        Unit
    }

    override fun readlink(path: String): String = io {
        val p = host(path, false)
        if (!Files.isSymbolicLink(p)) throw PyFsException(if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) PyErrno.EINVAL else PyErrno.ENOENT)
        Files.readSymbolicLink(p).toString().replace('\\', '/')
    }

    override fun symlink(target: String, link: String) = io {
        write()
        Files.createSymbolicLink(host(link, false), java.nio.file.Paths.get(target))
        Unit
    }

    override fun utime(path: String, atimeNs: Long, mtimeNs: Long, follow: Boolean) = io {
        write()
        val v = Files.getFileAttributeView(host(path, follow), java.nio.file.attribute.BasicFileAttributeView::class.java,
            *(if (follow) arrayOf() else arrayOf(LinkOption.NOFOLLOW_LINKS)))
        v.setTimes(FileTime.from(mtimeNs, java.util.concurrent.TimeUnit.NANOSECONDS),
            FileTime.from(atimeNs, java.util.concurrent.TimeUnit.NANOSECONDS), null)
    }

    override fun chmod(path: String, mode: Int, follow: Boolean) = io {
        write()
        val perms = HashSet<PosixFilePermission>()
        for (x in PosixFilePermission.entries) if (mode and (1 shl (8 - x.ordinal)) != 0) perms.add(x)
        try {
            Files.setPosixFilePermissions(host(path, follow), perms)
        } catch (e: UnsupportedOperationException) {
            host(path, follow).toFile().setWritable(mode and 0x80 != 0)
        }
        Unit
    }

    companion object {
        fun errnoOf(e: java.io.IOException): PyFsException = when (e) {
            is java.nio.file.NoSuchFileException -> PyFsException(PyErrno.ENOENT)
            is java.nio.file.FileAlreadyExistsException -> PyFsException(PyErrno.EEXIST)
            is java.nio.file.DirectoryNotEmptyException -> PyFsException(PyErrno.ENOTEMPTY)
            is java.nio.file.NotDirectoryException -> PyFsException(PyErrno.ENOTDIR)
            is java.nio.file.AccessDeniedException -> PyFsException(PyErrno.EACCES)
            is java.nio.file.NotLinkException -> PyFsException(PyErrno.EINVAL)
            is java.nio.file.FileSystemLoopException -> PyFsException(PyErrno.ELOOP)
            else -> PyFsException(PyErrno.EIO, e.message)
        }
    }
}

/** [PyFileSystem.withLibrary]: the bundled library read-only at its root, the rest [fs]'s. */
internal class PyLibraryFs(private val fs: PyFileSystem) : PyFileSystem by fs {
    private val root = PyStdlib.ROOT
    private val ancestors = setOf("/usr", "/usr/lib")

    /** [path]'s place in the library ("" its root), or null where it is not under it. */
    private fun rel(path: String): String? = when {
        path == root -> ""
        path.startsWith("$root/") -> path.substring(root.length + 1)
        else -> null
    }

    private fun dirStat() = PyFileStat(PyFileStat.S_IFDIR or 0x1ED, 4096, nlink = 2)

    override fun stat(path: String, follow: Boolean): PyFileStat {
        val r = rel(path)
        if (r == null) {
            if (path in ancestors) return try {
                fs.stat(path, follow)
            } catch (e: PyFsException) {
                dirStat()
            }
            return fs.stat(path, follow)
        }
        if (r == "" || PyStdlib.isDirectory(r)) return dirStat()
        val b = PyStdlib.bytes(r) ?: throw PyFsException(PyErrno.ENOENT)
        return PyFileStat(PyFileStat.S_IFREG or 0x1A4, b.size.toLong(), ino = r.hashCode().toLong() and 0xFFFFFFFFL)
    }

    override fun list(path: String): List<String> {
        val r = rel(path)
        if (r == null) {
            val own = fs.runCatching { list(path) }
            return when (path) {
                "/usr" -> (own.getOrDefault(emptyList()) + "lib").distinct()
                "/usr/lib" -> (own.getOrDefault(emptyList()) + "python3.14").distinct()
                else -> own.getOrThrow()
            }
        }
        if (r != "" && !PyStdlib.isDirectory(r)) throw PyFsException(if (PyStdlib.bytes(r) != null) PyErrno.ENOTDIR else PyErrno.ENOENT)
        return PyStdlib.listing(r)
    }

    override fun open(path: String, flags: Int, mode: Int): SeekableByteChannel {
        val r = rel(path) ?: return fs.open(path, flags, mode)
        if (flags and PyFileSystem.O_ACCMODE != PyFileSystem.O_RDONLY || flags and (PyFileSystem.O_CREAT or PyFileSystem.O_TRUNC) != 0) {
            throw PyFsException(PyErrno.EROFS)
        }
        if (r == "" || PyStdlib.isDirectory(r)) throw PyFsException(PyErrno.EISDIR)
        val b = PyStdlib.bytes(r) ?: throw PyFsException(PyErrno.ENOENT)
        return PyBytesChannel(null, b, b.size, readable = true, writable = false, append = false)
    }

    private fun refuse(path: String) {
        if (rel(path) != null) throw PyFsException(PyErrno.EROFS)
    }

    override fun mkdir(path: String, mode: Int) {
        refuse(path)
        fs.mkdir(path, mode)
    }

    override fun rmdir(path: String) {
        refuse(path)
        fs.rmdir(path)
    }

    override fun unlink(path: String) {
        refuse(path)
        fs.unlink(path)
    }

    override fun rename(src: String, dst: String) {
        refuse(src)
        refuse(dst)
        fs.rename(src, dst)
    }

    override fun readlink(path: String): String {
        if (rel(path) != null) throw PyFsException(PyErrno.EINVAL)
        return fs.readlink(path)
    }

    override fun symlink(target: String, link: String) {
        refuse(link)
        fs.symlink(target, link)
    }

    override fun utime(path: String, atimeNs: Long, mtimeNs: Long, follow: Boolean) {
        refuse(path)
        fs.utime(path, atimeNs, mtimeNs, follow)
    }

    override fun chmod(path: String, mode: Int, follow: Boolean) {
        refuse(path)
        fs.chmod(path, mode, follow)
    }
}
