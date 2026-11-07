package dev.mooner.neonpython

/**
 * The standard library NeonPy bundles (docs/PYTHON_PLATFORM.md §12, S1): CPython 3.14.6's own Lib modules, as
 * neonpython/tools/port_cpython.py ported them into the resources (dev/mooner/neonpython/lib, its INDEX the files),
 * found after the host's modules; their __file__s under [ROOT].
 */
object PyStdlib {
    /** Where the bundled modules' files are said to be: sys.prefix's lib/python3.14. */
    const val PREFIX = "/usr"
    const val ROOT = "$PREFIX/lib/python3.14"
    private const val RES = "/dev/mooner/neonpython/lib/"

    /** The files bundled (paths under the lib), from its INDEX. */
    private val files: Set<String> by lazy {
        val text = PyStdlib::class.java.getResourceAsStream(RES + "INDEX")?.readBytes()?.toString(Charsets.UTF_8) ?: ""
        text.lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toSet()
    }

    /** Module [name]'s source where the library has it: a package's `__init__.py`, else `name.py`. */
    @JvmStatic
    fun find(name: String): PyModuleSource? {
        val rel = name.replace('.', '/')
        val pkg = "$rel/__init__.py"
        if (pkg in files) return PyModuleSource(read(pkg), "$ROOT/$pkg", true, library = true)
        val mod = "$rel.py"
        if (mod in files) return PyModuleSource(read(mod), "$ROOT/$mod", false, library = true)
        return null
    }

    // (LF line ends, as CPython's: a checkout may have made them CRLF)
    private fun read(rel: String): String =
        PyStdlib::class.java.getResourceAsStream(RES + rel)!!.readBytes().toString(Charsets.UTF_8).replace("\r\n", "\n")

    /** The directories of the library (each file's parents), under it. */
    private val dirs: Set<String> by lazy {
        val out = HashSet<String>()
        for (f in files) {
            var d = f.substringBeforeLast('/', "")
            while (d.isNotEmpty() && out.add(d)) d = d.substringBeforeLast('/', "")
        }
        out
    }

    /** Whether [rel] (a path under the library) is one of its directories. */
    @JvmStatic fun isDirectory(rel: String): Boolean = rel in dirs

    /** The bytes of the library's file [rel] (as [find] reads it), or null where it has none. */
    @JvmStatic fun bytes(rel: String): ByteArray? = if (rel in files) read(rel).toByteArray(Charsets.UTF_8) else null

    /** The names in the library's directory [rel] ("" its root). */
    @JvmStatic
    fun listing(rel: String): List<String> {
        val prefix = if (rel.isEmpty()) "" else "$rel/"
        return (files.asSequence() + dirs.asSequence()).filter { it.startsWith(prefix) && it.length > prefix.length }
            .map { it.substring(prefix.length).substringBefore('/') }.distinct().sorted().toList()
    }
}

