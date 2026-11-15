package dev.mooner.neonwasm.wasi

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.TreeMap

/**
 * An in-memory file system: directories, files and symbolic links, as inodes (hard links share one). A WASI
 * program sees only what is preopened from it ([WasiConfig.Builder.preopen]), and nothing of the host's: a host
 * directory is copied in ([fromHost]), never opened.
 */
class WasiFs(@JvmField val clock: WasiClock = WasiClock.SYSTEM) {
    private var nextIno = 1L

    /** One inode: its number, links and times (nanoseconds since the epoch). */
    sealed class Node(@JvmField val ino: Long, now: Long, @JvmField val fs: WasiFs) {
        @JvmField var nlink = 1L
        @JvmField var atim = now
        @JvmField var mtim = now
        @JvmField var ctim = now
        abstract val fileType: Int
        abstract val size: Long
    }

    class File internal constructor(ino: Long, now: Long, fs: WasiFs) : Node(ino, now, fs) {
        @JvmField var data = ByteArray(0)
        @JvmField var length = 0L
        override val fileType get() = FileType.REGULAR_FILE
        override val size get() = length

        /** Makes the file [n] bytes long: cut, or grown with zeros. */
        fun resize(n: Long) {
            if (n > Int.MAX_VALUE - 8) fail(Errno.FBIG)
            if (n > data.size) data = data.copyOf(maxOf(n.toInt(), minOf(data.size * 2L, Int.MAX_VALUE - 8L).toInt()))
            if (n < length) java.util.Arrays.fill(data, n.toInt(), length.toInt(), 0)
            length = n
        }
    }

    class Dir internal constructor(ino: Long, now: Long, fs: WasiFs, @JvmField var parent: Dir?) : Node(ino, now, fs) {
        /** Entries by name, in order (readdir's order). */
        @JvmField val entries = TreeMap<String, Node>()
        override val fileType get() = FileType.DIRECTORY
        override val size get() = 0L
    }

    class Symlink internal constructor(ino: Long, now: Long, fs: WasiFs, @JvmField val target: String) : Node(ino, now, fs) {
        override val fileType get() = FileType.SYMBOLIC_LINK
        override val size get() = target.toByteArray(Charsets.UTF_8).size.toLong()
    }

    @JvmField val root: Dir = Dir(nextIno++, clock.realtimeNanos(), this, null)

    internal fun now() = clock.realtimeNanos()
    internal fun newFile() = File(nextIno++, now(), this)
    internal fun newDir(parent: Dir) = Dir(nextIno++, now(), this, parent)
    internal fun newSymlink(target: String) = Symlink(nextIno++, now(), this, target)

    /** Puts [node] in [dir] as [name]. */
    internal fun link(dir: Dir, name: String, node: Node) {
        dir.entries[name] = node
        val t = now()
        dir.mtim = t
        dir.ctim = t
        if (node is Dir) node.parent = dir
    }

    /** Adds a file with [content] under [path] (relative to the root, directories made as needed). */
    fun addFile(path: String, content: ByteArray): File {
        val parts = path.split('/').filter { it.isNotEmpty() }
        var d = root
        for (p in parts.dropLast(1)) d = d.entries[p] as? Dir ?: newDir(d).also { link(d, p, it) }
        val f = newFile()
        f.data = content.copyOf()
        f.length = content.size.toLong()
        link(d, parts.last(), f)
        return f
    }

    companion object {
        /** A file system holding a copy of the host directory [dir] (files, directories and symbolic links). */
        @JvmStatic
        fun fromHost(dir: Path, clock: WasiClock = WasiClock.SYSTEM): WasiFs {
            val fs = WasiFs(clock)
            fs.copy(dir, fs.root)
            return fs
        }
    }

    private fun copy(from: Path, to: Dir) {
        Files.list(from).use { s ->
            for (p in s.sorted().toList()) {
                val name = p.fileName.toString()
                val n: Node = when {
                    Files.isSymbolicLink(p) -> newSymlink(Files.readSymbolicLink(p).toString().replace('\\', '/'))
                    Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) -> newDir(to).also { copy(p, it) }
                    else -> newFile().also {
                        it.data = Files.readAllBytes(p)
                        it.length = it.data.size.toLong()
                    }
                }
                link(to, name, n)
            }
        }
    }
}
