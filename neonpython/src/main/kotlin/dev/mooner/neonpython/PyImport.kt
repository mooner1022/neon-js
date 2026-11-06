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

/**
 * A module's source: its text, the path its __file__ says, whether it is a package (its submodules under it), and
 * whether it is the bundled library's ([PyStdlib]).
 */
class PyModuleSource(@JvmField val source: String, @JvmField val path: String, @JvmField val isPackage: Boolean,
                     @JvmField val library: Boolean = false)

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

/**
 * sys.stdin, sys.stdout and sys.stderr: text streams over descriptors 0, 1 and 2, what they write going to the
 * context's out and err (stdin: nothing to read). As CPython's TextIOWrapper with write_through: nothing is kept back,
 * so print, sys.stdout.write and os.write(1, ...) keep their order.
 */
class PyStream(@JvmField val out: Appendable, @JvmField val name: String, @JvmField val fd: Int) {
    @JvmField var closed = false
    /** Its `buffer`: the bytes level under it, made when first asked for. */
    @JvmField var buffer: PyObject? = null
    @JvmField val errors = if (fd == 2) "backslashreplace" else "surrogateescape"
    @JvmField val mode = if (fd == 0) "r" else "w"
}

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
        // (the host's modules first, as a script's directory comes first in sys.path; then the bundled library)
        val src = ctx.loader?.find(name) ?: PyStdlib.find(name) ?: throw notFound(name)
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
        if (src.library) ctx.libraryUnits.add(cb) else ctx.moduleUnits.add(cb)
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
            d[PyStr("__package__")] = PyStr(name.substringBeforeLast('.', ""))
            d[PyStr("__loader__")] = PyNone
            d[PyStr("__spec__")] = PyNone
            // (a module's builtins are functions, not methods: a class holding one gives it as it is)
            for (v in d.map.values) if (v is PyBuiltin) v.method = false
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
            "_thread" -> {
                PyStdlib.fillThread(ctx, d)
                return mod()
            }
            "_weakref" -> {
                PyWeakrefs.fillWeakref(ctx, d)
                return mod()
            }
            "gc" -> {
                PyWeakrefs.fillGc(ctx, d)
                return mod()
            }
            "_collections" -> {
                PyCollections.fill(ctx, d)
                return mod()
            }
            "itertools" -> {
                PyItertools.fill(ctx, d)
                return mod()
            }
            "_contextvars" -> {
                PyContextVars.fill(ctx, d)
                return mod()
            }
            "_sre" -> {
                PySre.fill(ctx, d)
                return mod()
            }
            "_string" -> {
                PyStringModule.fill(ctx, d)
                return mod()
            }
            "_typing" -> {
                PyTyping.fillTyping(ctx, d)
                return mod()
            }
            "math" -> {
                PyMath.fill(ctx, d)
                return mod().also { d[PyStr("__doc__")] = PyStr(PyMath.DOC) }
            }
            "errno" -> {
                PyPosix.fillErrno(d)
                return mod().also { d[PyStr("__doc__")] = PyStr(PyErrno.ERRNO_DOC) }
            }
            "posix" -> {
                PyPosix.fill(ctx, d)
                return mod().also { d[PyStr("__doc__")] = PyStr(PyErrno.POSIX_DOC) }
            }
            "_random" -> {
                PyRandom.fill(d)
                return mod().also { d[PyStr("__doc__")] = PyStr("Module implements the Mersenne Twister random number generator.") }
            }
            "_operator" -> {
                PyOperator.fill(d)
                return mod()
            }
            "_sha2", "_md5", "_sha1" -> {
                PyRandom.fillHash(name, d)
                return mod()
            }
            else -> return null
        }
    }

    /** The modules NeonPy makes itself (sys.builtin_module_names). */
    @JvmField val BUILTIN_NAMES = listOf("sys", "builtins", "_thread", "_weakref", "gc", "_collections", "itertools", "_contextvars", "_sre", "_string", "_typing", "math", "errno", "posix", "_random", "_sha2", "_md5", "_sha1", "_operator")

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
        d[PyStr("stdin")] = PyStream(ctx.out, "<stdin>", 0)
        d[PyStr("stdout")] = PyStream(ctx.out, "<stdout>", 1)
        d[PyStr("stderr")] = PyStream(ctx.err, "<stderr>", 2)
        d[PyStr("maxsize")] = Long.MAX_VALUE
        d[PyStr("byteorder")] = PyStr("little")
        d[PyStr("version")] = PyStr("3.14.6 (NeonPy)")
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
        def("intern") { a -> a[0] as? PyStr ?: throw PyRt.typeError("intern() argument must be str, not ${PyTypes.name(a[0])}") }
        PySys.fill(ctx, d)
        for (v in d.map.values) if (v is PyBuiltin) v.method = false
        val m = PyModule("sys", d)
        m.builtin = true
        ctx.modules[PyStr("sys")] = m
        return m
    }

    private fun platform(): String {
        val vm = System.getProperty("java.vm.name")?.lowercase() ?: ""
        // (the sandbox's: POSIX-shaped whatever the host is, posix its os (§12.2); CPython's 'android' on Android)
        return if ("dalvik" in vm || "art" == vm) "android" else "linux"
    }

    /** The module and stream types' methods. */
    @JvmStatic
    fun define(d: PyDict) {
        defineStreams()
        d[PyStr("__import__")] = PyBuiltin("__import__", { _, a -> builtinImport(a, null) }, { _, a, kw -> builtinImport(a, kw) })
    }

    /** The standard streams' methods and attributes (TextIOWrapper's, as far as a stream of the context's has them). */
    private fun defineStreams() {
        val t = STREAM_TYPE
        t.dispatchBuiltins = true
        fun st(a: Array<Any?>, fn: String): PyStream {
            val s = a.getOrNull(0) as? PyStream ?: throw PyRt.typeError("descriptor '$fn' requires a '_io.TextIOWrapper' object")
            return s
        }
        fun open(s: PyStream) {
            if (s.closed) throw PyException("ValueError", "I/O operation on closed file.")
        }
        fun unsupported(what: String): Nothing =
            throw PyException(PyCalls.callWith(ctx(), PyRt.getAttr(importModule(ctx(), "io"), "UnsupportedOperation"), arrayOf(PyStr(what)), NO_ARGS, NO_ARGS) as PyBaseException)
        fun method(name: String, impl: (PyStream, Array<Any?>) -> Any?) {
            t.def(name, PyBuiltin(name) { _, a -> impl(st(a, name), a) })
        }
        fun prop(name: String, get: (PyStream) -> Any?) {
            t.def(name, PyProperty(PyBuiltin(name) { _, a -> get(st(a, name)) }, null, null, null).also { it.name = name })
        }
        method("write") { s, a ->
            open(s)
            if (a.size != 2) throw PyRt.typeError("write() takes exactly one argument (${a.size - 1} given)")
            val x = a[1] as? PyStr ?: throw PyRt.typeError("write() argument must be str, not ${PyTypes.name(a[1])}")
            if (s.fd == 0) unsupported("not writable")
            s.out.append(x.s)
            x.length.toLong()
        }
        method("writelines") { s, a ->
            open(s)
            val it = PyRt.iter(a.getOrNull(1))
            while (true) {
                val x = it.next()
                if (x === PyIterator.STOP) break
                s.out.append((x as? PyStr ?: throw PyRt.typeError("write() argument must be str, not ${PyTypes.name(x)}")).s)
            }
            PyNone
        }
        method("flush") { s, _ -> open(s); PyNone }
        method("close") { s, _ -> s.closed = true; PyNone }
        method("fileno") { s, _ -> s.fd.toLong() }
        method("isatty") { s, _ -> open(s); PyBool.FALSE }
        method("readable") { s, _ -> open(s); PyBool.of(s.fd == 0) }
        method("writable") { s, _ -> open(s); PyBool.of(s.fd != 0) }
        method("seekable") { s, _ -> open(s); PyBool.FALSE }
        // (stdin: nothing to read, the end at once)
        method("read") { s, _ -> open(s); if (s.fd != 0) unsupported("not readable"); PyStr("") }
        method("readline") { s, _ -> open(s); if (s.fd != 0) unsupported("not readable"); PyStr("") }
        method("readlines") { s, _ -> open(s); if (s.fd != 0) unsupported("not readable"); PyList() }
        method("__iter__") { s, _ -> open(s); s }
        method("__next__") { s, _ -> open(s); throw PyException("StopIteration", "") }
        method("__enter__") { s, _ -> open(s); s }
        method("__exit__") { s, _ -> s.closed = true; PyNone }
        t.def("reconfigure", PyBuiltin("reconfigure", { _, a -> st(a, "reconfigure"); PyNone }, { _, a, _ -> st(a, "reconfigure"); PyNone }))
        for (name in listOf("seek", "tell", "truncate")) method(name) { s, _ -> open(s); unsupported("underlying stream is not seekable") }
        prop("buffer") { s -> s.buffer ?: streamBuffer(s).also { s.buffer = it } }
        prop("name") { PyStr(it.name) }
        prop("mode") { PyStr(it.mode) }
        prop("encoding") { PyStr("utf-8") }
        prop("errors") { PyStr(it.errors) }
        prop("closed") { PyBool.of(it.closed) }
        prop("line_buffering") { PyBool.FALSE }
        prop("write_through") { PyBool.TRUE }
        prop("newlines") { PyNone }
    }

    /** A standard stream's `buffer` (a BufferedWriter's surface, a BufferedReader's for stdin): bytes to its descriptor. */
    private val BUFFER_TYPE = PyTypes.newBuiltin("BufferedWriter", subclassable = false).also { t ->
        t.module = "_io"
        t.tpName = "_io.BufferedWriter"
        t.dispatchBuiltins = true
        fun s(a: Array<Any?>) = (a[0] as PyObject).dict!![PyStr("_stream")] as PyStream
        t.def("write", PyBuiltin("write") { _, a ->
            val st = s(a)
            if (st.closed) throw PyException("ValueError", "write to closed file")
            val b = PyByteSeqs.content(a.getOrNull(1)) ?: throw PyRt.typeError("a bytes-like object is required, not '${PyTypes.name(a.getOrNull(1))}'")
            if (st.fd == 0) throw PyException(PyCalls.callWith(ctx(), PyRt.getAttr(importModule(ctx(), "io"), "UnsupportedOperation"), arrayOf(PyStr("write")), NO_ARGS, NO_ARGS) as PyBaseException)
            PyPosix.writeStd(ctx(), st.fd, b).toLong()
        })
        t.def("read", PyBuiltin("read") { _, _ -> PyBytes(ByteArray(0)) })
        t.def("readline", PyBuiltin("readline") { _, _ -> PyBytes(ByteArray(0)) })
        t.def("flush", PyBuiltin("flush") { _, _ -> PyNone })
        t.def("fileno", PyBuiltin("fileno") { _, a -> s(a).fd.toLong() })
        t.def("isatty", PyBuiltin("isatty") { _, _ -> PyBool.FALSE })
        t.def("writable", PyBuiltin("writable") { _, a -> PyBool.of(s(a).fd != 0) })
        t.def("readable", PyBuiltin("readable") { _, a -> PyBool.of(s(a).fd == 0) })
        t.def("seekable", PyBuiltin("seekable") { _, _ -> PyBool.FALSE })
        t.def("closed", PyProperty(PyBuiltin("closed") { _, a -> PyBool.of(s(a).closed) }, null, null, null).also { it.name = "closed" })
        t.def("name", PyProperty(PyBuiltin("name") { _, a -> PyStr(s(a).name) }, null, null, null).also { it.name = "name" })
        t.def("mode", PyProperty(PyBuiltin("mode") { _, a -> PyStr(if (s(a).fd == 0) "rb" else "wb") }, null, null, null).also { it.name = "mode" })
        t.def("__repr__", PyBuiltin("__repr__") { _, a -> PyStr("<_io.${if (s(a).fd == 0) "BufferedReader" else "BufferedWriter"} name='${s(a).name}'>") })
    }

    private fun streamBuffer(s: PyStream): PyObject = PyObject(BUFFER_TYPE).also { o -> o.dict = PyDict().also { it[PyStr("_stream")] = s } }

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
