package dev.mooner.neonpython

/**
 * The IANA time zone database (tzdata.zip, made by neonpython/tools/gen_tzdata.py from a system's compiled one),
 * read-only at /usr/share/zoneinfo whatever the host's file system is (PyLibraryFs), as the bundled library is at
 * /usr/lib/python3.14: zoneinfo's TZPATH finds its zones there (docs/PYTHON_PLATFORM.md §12.2). Read whole, once, on
 * the first look: its TZif files by name, its aliases (a system's symbolic links) served as their targets' bytes.
 */
object PyTzdata {
    const val ROOT = "/usr/share/zoneinfo"

    private class Db(val files: Map<String, ByteArray>, val dirs: Map<String, List<String>>)

    private val db: Db by lazy {
        val files = HashMap<String, ByteArray>()
        var aliases = ""
        val stream = PyTzdata::class.java.getResourceAsStream("/dev/mooner/neonpython/tzdata.zip")
        if (stream != null) java.util.zip.ZipInputStream(stream).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val b = z.readBytes()
                when (e.name) {
                    "VERSION" -> {}
                    "ALIASES" -> aliases = String(b, Charsets.US_ASCII)
                    else -> files[e.name] = b
                }
            }
        }
        for (line in aliases.lineSequence()) {
            val sp = line.indexOf(' ')
            if (sp < 0) continue
            files[line.substring(sp + 1)]?.let { files[line.substring(0, sp)] = it }
        }
        // (each directory's names: its files' and its subdirectories')
        val dirs = HashMap<String, MutableSet<String>>()
        dirs[""] = sortedSetOf()
        for (name in files.keys) {
            var child = name
            while (true) {
                val i = child.lastIndexOf('/')
                val parent = if (i < 0) "" else child.substring(0, i)
                dirs.getOrPut(parent) { sortedSetOf() }.add(child.substring(i + 1))
                if (i < 0) break
                child = parent
            }
        }
        Db(files, dirs.mapValues { it.value.toList() })
    }

    @JvmStatic fun isDirectory(rel: String): Boolean = rel in db.dirs

    @JvmStatic fun bytes(rel: String): ByteArray? = db.files[rel]

    @JvmStatic fun listing(rel: String): List<String> = db.dirs[rel] ?: emptyList()
}
