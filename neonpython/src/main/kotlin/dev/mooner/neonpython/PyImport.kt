package dev.mooner.neonpython

import dev.mooner.neonjs.vm.VmFrame

/**
 * Where a host's modules come from (docs/PYTHON_PLATFORM.md §11.2): NeonPy never reads a file system; a host gives the
 * source of a module by its dotted name, or null where it has none. A context without a loader imports its builtin
 * modules only (sys, builtins, string, __future__).
 */
fun interface PyModuleLoader {
    fun find(name: String): PyModuleSource?
}

/** A module's source: its text, the path its __file__ says, and whether it is a package (its submodules under it). */
class PyModuleSource(@JvmField val source: String, @JvmField val path: String, @JvmField val isPackage: Boolean)

/** A module: its name and its dict (its globals, the same dict). */
class PyModule(@JvmField val name: String, @JvmField val dict: PyDict) {
    /** Whether its body is running (an import of it now meets it partially initialized). */
    @JvmField var initializing = false
    @JvmField var builtin = false

    val isPackage: Boolean get() = dict.containsKey(PyStr("__path__"))

    fun repr(): String {
        if (builtin) return "<module '$name' (built-in)>"
        val f = dict[PyStr("__file__")]
        return if (f is PyStr) "<module '$name' from '${f.s}'>" else "<module '$name'>"
    }
}

/** sys.stdout and sys.stderr: what they write goes to the context's out and err. */
class PyStream(@JvmField val out: Appendable, @JvmField val name: String)

/**
 * import (M7): IMPORT_NAME, IMPORT_FROM, IMPORT_STAR as CPython's importlib does them: sys.modules first, a module put
 * there before its body runs (circular imports meet it partially initialized) and taken out where the body raises, a
 * submodule an attribute of its package, relative imports from __package__; CPython's messages.
 */
object PyImport {
    private val NO_ARGS = arrayOfNulls<Any?>(0)

    @JvmField val MODULE_TYPE = PyTypes.newBuiltin("module", subclassable = true)
    @JvmField val STREAM_TYPE = PyTypes.newBuiltin("TextIOWrapper", subclassable = false).also { it.module = "_io" }

    private fun ctx() = PyContext.current()

    private fun importError(msg: String, name: Any? = null) = PyException("ImportError", msg).also { e -> if (name != null) e.attrs = mapOf("name" to name) }
    private fun notFound(n: String, msg: String = "No module named '$n'") = PyException("ModuleNotFoundError", msg).also { it.attrs = mapOf("name" to PyStr(n)) }

    /** The module named [name] (absolute), imported where it is not yet in sys.modules. */
    @JvmStatic
    fun importModule(ctx: PyContext, name: String): Any? {
        val key = PyStr(name)
        if (ctx.modules.containsKey(key)) return ctx.modules[key]
        val parent = name.substringBeforeLast('.', "")
        var pm: Any? = null
        if (parent.isNotEmpty()) {
            pm = importModule(ctx, parent)
            // (its package's import may have imported it)
            if (ctx.modules.containsKey(key)) return ctx.modules[key]
            if (pm !is PyModule || !pm.isPackage) throw notFound(name, "No module named '$name'; '$parent' is not a package")
        }
        val m = builtinModule(ctx, name) ?: load(ctx, name, parent)
        if (pm is PyModule) pm.dict[PyStr(name.substringAfterLast('.'))] = ctx.modules[key] ?: m
        return ctx.modules[key] ?: m
    }

    /** A module of the host's loader: made, put in sys.modules, its body run in its dict. */
    private fun load(ctx: PyContext, name: String, parent: String): PyModule {
        val src = ctx.loader?.find(name) ?: throw notFound(name)
        val d = PyDict()
        d[PyStr("__name__")] = PyStr(name)
        d[PyStr("__doc__")] = PyNone
        d[PyStr("__package__")] = PyStr(if (src.isPackage) name else parent)
        d[PyStr("__loader__")] = PyNone
        d[PyStr("__spec__")] = PyNone
        d[PyStr("__file__")] = PyStr(src.path)
        if (src.isPackage) d[PyStr("__path__")] = PyList(arrayListOf(PyStr(src.path.substringBeforeLast('/', src.path.substringBeforeLast('\\', "")))))
        val m = PyModule(name, d)
        val cb = try {
            PyCompiler.withFilename(PyCompiler.compileModule(src.source, "<module>"), src.path)
        } catch (e: PySyntaxError) {
            throw PyException("SyntaxError", "${e.msg} (${src.path}, line ${e.line})")
        }
        ctx.moduleUnits.add(cb)
        ctx.sources[src.path] = src.source
        ctx.modules[PyStr(name)] = m
        m.initializing = true
        try {
            ctx.runModule(cb, d)
        } catch (t: Throwable) {
            ctx.modules.map.remove(PyDict.Key(PyStr(name)))
            throw t
        } finally {
            m.initializing = false
        }
        return m
    }

    /** The absolute name of `from <dots><name> import` in a module whose globals are [g]. */
    @JvmStatic
    fun resolve(name: String, g: PyDict, level: Int): String {
        if (level == 0) return name
        var pkg = g[PyStr("__package__")]
        if (pkg !is PyStr) {
            val n = (g[PyStr("__name__")] as? PyStr)?.s ?: ""
            pkg = PyStr(if (g.containsKey(PyStr("__path__"))) n else n.substringBeforeLast('.', ""))
        }
        if (pkg.s.isEmpty()) throw importError("attempted relative import with no known parent package")
        val bits = pkg.s.split('.')
        if (bits.size < level) throw importError("attempted relative import beyond top-level package")
        val base = bits.subList(0, bits.size - (level - 1)).joinToString(".")
        return if (name.isEmpty()) base else "$base.$name"
    }

    /** IMPORT_NAME: the module (the top package for `import a.b`, the module itself where there is a fromlist). */
    @JvmStatic
    fun importName(f: VmFrame, level: Any?, fromlist: Any?, name: Any?): Any? {
        val pf = f as PyFrame
        val n = (name as PyStr).s
        val abs = resolve(n, pf.globals, (level as Long).toInt())
        val m = importModule(pf.ctx, abs)
        val hasFrom = fromlist is PyTuple && fromlist.items.isNotEmpty()
        if (hasFrom || (level as Long) > 0L && n.isEmpty()) return m
        if (!n.contains('.')) return m
        return pf.ctx.modules[PyStr(abs.substringBefore('.'))]
    }

    /** IMPORT_FROM: an attribute of the module, or its submodule of that name; CPython's errors. */
    @JvmStatic
    fun importFrom(m: Any?, name: Any?): Any? {
        val n = (name as PyStr).s
        try {
            return PyRt.getAttr(m, n)
        } catch (e: PyException) {
            if (!e.matches(PyExceptions.type("AttributeError"))) throw e
        }
        val ctx = ctx()
        if (m is PyModule) {
            val full = "${m.name}.$n"
            ctx.modules[PyStr(full)]?.let { return it }
            if (m.isPackage) {
                try {
                    return importModule(ctx, full)
                } catch (e: PyException) {
                    if (!e.matches(PyExceptions.type("ModuleNotFoundError"))) throw e
                }
            }
        }
        val mname = (m as? PyModule)?.name ?: (try { PyRt.str(PyRt.getAttr(m, "__name__")) } catch (e: PyException) { "<unknown module name>" })
        val path = (m as? PyModule)?.dict?.get(PyStr("__file__"))?.let { if (it is PyStr) it.s else null } ?: "unknown location"
        if (m is PyModule && m.initializing) {
            throw importError("cannot import name '$n' from partially initialized module '$mname' (most likely due to a circular import) ($path)", PyStr(mname))
        }
        throw importError("cannot import name '$n' from '$mname' ($path)", PyStr(mname))
    }

    /** IMPORT_STAR: the module's __all__ (strs only), else its public names, into the namespace. */
    @JvmStatic
    fun importStar(f: VmFrame, m: Any?) {
        val pf = f as PyFrame
        val ns = pf.locals ?: pf.globals
        val all = try {
            PyRt.getAttr(m, "__all__")
        } catch (e: PyException) {
            if (e.matches(PyExceptions.type("AttributeError"))) null else throw e
        }
        if (all != null) {
            for (x in PyBuiltins.toList(all)) {
                val s = x as? PyStr ?: throw PyRt.typeError("Item in ${(m as? PyModule)?.name ?: "module"}.__all__ must be str, not ${PyTypes.name(x)}")
                ns[s] = PyRt.getAttr(m, s.s)
            }
            return
        }
        val d = (m as? PyModule)?.dict ?: PyRt.getAttr(m, "__dict__") as PyDict
        for ((k, v) in d.map) {
            val s = k.v as? PyStr ?: continue
            if (!s.s.startsWith("_")) ns.map[k] = v
        }
    }

    // ------------------------------------------------------------------ the builtin modules

    /** The builtin module [name] of [ctx] (made once per context), or null. */
    private fun builtinModule(ctx: PyContext, name: String): PyModule? {
        val d = PyDict()
        fun mod(): PyModule {
            d[PyStr("__name__")] = PyStr(name)
            d[PyStr("__doc__")] = PyNone
            d[PyStr("__package__")] = PyStr(if (name == "string") name else name.substringBeforeLast('.', ""))
            d[PyStr("__loader__")] = PyNone
            d[PyStr("__spec__")] = PyNone
            val m = PyModule(name, d)
            m.builtin = true
            ctx.modules[PyStr(name)] = m
            return m
        }
        fun def(n: String, impl: (Array<Any?>) -> Any?) {
            d[PyStr(n)] = PyBuiltin(n) { _, a -> impl(a) }
        }
        when (name) {
            "sys" -> return ctx.sysModule
            "builtins" -> {
                val m = PyModule("builtins", ctx.builtins)
                m.builtin = true
                ctx.modules[PyStr("builtins")] = m
                return m
            }
            "string" -> {
                d[PyStr("__path__")] = PyList()
                d[PyStr("ascii_lowercase")] = PyStr("abcdefghijklmnopqrstuvwxyz")
                d[PyStr("ascii_uppercase")] = PyStr("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
                d[PyStr("ascii_letters")] = PyStr("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
                d[PyStr("digits")] = PyStr("0123456789")
                d[PyStr("hexdigits")] = PyStr("0123456789abcdefABCDEF")
                d[PyStr("octdigits")] = PyStr("01234567")
                d[PyStr("punctuation")] = PyStr("!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~")
                d[PyStr("whitespace")] = PyStr(" \t\n\r\u000b\u000c")
                d[PyStr("printable")] = PyStr("0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~ \t\n\r\u000b\u000c")
                return mod()
            }
            "string.templatelib" -> {
                d[PyStr("Template")] = PyTypes.TEMPLATE
                d[PyStr("Interpolation")] = PyTypes.INTERPOLATION
                return mod()
            }
            "__future__" -> {
                for (f in FUTURES) d[PyStr(f)] = PyStr(f)
                return mod()
            }
            else -> return null
        }
    }

    /** The features `from __future__ import` knows (CPython 3.14's). */
    @JvmField val FUTURES = listOf("nested_scopes", "generators", "division", "absolute_import", "with_statement", "print_function",
        "unicode_literals", "barry_as_FLUFL", "generator_stop", "annotations")

    /** A context's sys: its modules, path, argv, streams, exit, exc_info, recursion limit, version. */
    @JvmStatic
    fun sys(ctx: PyContext): PyModule {
        val d = PyDict()
        d[PyStr("__name__")] = PyStr("sys")
        d[PyStr("__doc__")] = PyNone
        d[PyStr("__package__")] = PyStr("")
        d[PyStr("__loader__")] = PyNone
        d[PyStr("__spec__")] = PyNone
        d[PyStr("modules")] = ctx.modules
        d[PyStr("path")] = PyList()
        d[PyStr("argv")] = PyList(arrayListOf(PyStr("")))
        d[PyStr("stdout")] = PyStream(ctx.out, "<stdout>")
        d[PyStr("stderr")] = PyStream(ctx.err, "<stderr>")
        d[PyStr("maxsize")] = Long.MAX_VALUE
        d[PyStr("byteorder")] = PyStr("little")
        d[PyStr("version")] = PyStr("3.14.6 (NeonPy)")
        d[PyStr("version_info")] = PyTuple(arrayOf(3L, 14L, 6L, PyStr("final"), 0L))
        d[PyStr("hexversion")] = 0x030e06f0L
        d[PyStr("platform")] = PyStr(platform())
        d[PyStr("executable")] = PyStr("")
        d[PyStr("float_repr_style")] = PyStr("short")
        fun def(n: String, impl: (Array<Any?>) -> Any?) {
            d[PyStr(n)] = PyBuiltin(n) { _, a -> impl(a) }
        }
        def("exit") { a ->
            // (its message str(exc): the code's)
            val e = PyBaseException(PyExceptions.type("SystemExit"))
            e.args = if (a.isEmpty() || a[0] === PyNone) PyTuple.EMPTY else PyTuple(arrayOf(a[0]))
            e.setField("code", a.getOrNull(0) ?: PyNone)
            throw PyException(e).also { e.thrown = it }
        }
        def("exc_info") { _ ->
            val h = ctx.currentHandled()
            if (h == null) PyTuple(arrayOf(PyNone, PyNone, PyNone)) else PyTuple(arrayOf(h.type, h, h.traceback))
        }
        def("exception") { _ -> ctx.currentHandled() ?: PyNone }
        def("_getframe") { a -> PyFrames.getFrame(ctx, a.getOrNull(0)) }
        // (an exception no code caught: printed to stderr as CPython prints it)
        val hook = PyBuiltin("excepthook") { _, a ->
            val v = a.getOrNull(1) as? PyBaseException ?: throw PyRt.typeError("excepthook expects an exception")
            ctx.err.append(PyFrames.format(v, ctx.sources))
            PyNone
        }
        d[PyStr("excepthook")] = hook
        d[PyStr("__excepthook__")] = hook
        def("_getframemodulename") { a ->
            // (the module of the frame [depth] out: its globals' __name__)
            try {
                PyFrames.getFrame(ctx, a.getOrNull(0)).f.globals[PyStr("__name__")] ?: PyNone
            } catch (e: PyException) {
                PyNone
            }
        }
        def("getrecursionlimit") { _ -> ctx.agent.maxDepth.toLong() }
        def("setrecursionlimit") { a ->
            val n = a[0] as? Long ?: throw PyRt.typeError("'${PyTypes.name(a[0])}' object cannot be interpreted as an integer")
            if (n < 1) throw PyException("ValueError", "recursion limit must be greater or equal than 1")
            ctx.agent.maxDepth = n.toInt()
            PyNone
        }
        def("intern") { a -> a[0] }
        val m = PyModule("sys", d)
        m.builtin = true
        ctx.modules[PyStr("sys")] = m
        return m
    }

    private fun platform(): String {
        val os = System.getProperty("os.name")?.lowercase() ?: ""
        val vm = System.getProperty("java.vm.name")?.lowercase() ?: ""
        return when {
            "dalvik" in vm || "art" == vm -> "android"
            os.startsWith("windows") -> "win32"
            os.startsWith("mac") -> "darwin"
            else -> "linux"
        }
    }

    /** The module and stream types' methods. */
    @JvmStatic
    fun define(d: PyDict) {
        STREAM_TYPE.def("write", PyBuiltin("write") { _, a ->
            val st = a[0] as PyStream
            val s = a.getOrNull(1) as? PyStr ?: throw PyRt.typeError("write() argument must be str, not ${PyTypes.name(a.getOrNull(1))}")
            st.out.append(s.s)
            s.length.toLong()
        })
        STREAM_TYPE.def("flush", PyBuiltin("flush") { _, _ -> PyNone })
        d[PyStr("__import__")] = PyBuiltin("__import__", { _, a -> builtinImport(a, null) }, { _, a, kw -> builtinImport(a, kw) })
    }

    /** __import__(name, globals=None, locals=None, fromlist=(), level=0) */
    private fun builtinImport(a: Array<Any?>, kw: PyDict?): Any? {
        val ctx = ctx()
        val name = (a.getOrNull(0) ?: kw?.get(PyStr("name")) as? PyStr ?: throw PyRt.typeError("__import__() missing required argument 'name' (pos 1)")) as PyStr
        val g = (a.getOrNull(1) ?: kw?.get(PyStr("globals"))) as? PyDict
        val fromlist = a.getOrNull(3) ?: kw?.get(PyStr("fromlist"))
        val level = ((a.getOrNull(4) ?: kw?.get(PyStr("level"))) as? Long ?: 0L).toInt()
        val abs = if (level > 0) resolve(name.s, g ?: PyDict(), level) else name.s
        val m = importModule(ctx, abs)
        val hasFrom = fromlist != null && fromlist !== PyNone && PyRt.truthy(fromlist)
        return if (hasFrom || !abs.contains('.')) m else ctx.modules[PyStr(abs.substringBefore('.'))]
    }
}
