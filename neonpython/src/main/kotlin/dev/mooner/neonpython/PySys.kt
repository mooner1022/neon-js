package dev.mooner.neonpython

/**
 * What sys has beyond its modules, streams and exceptions (docs/PYTHON_PLATFORM.md §12, S1): its struct sequences
 * (version_info, flags, float_info, int_info, hash_info, thread_info: tuples whose fields have names),
 * sys.implementation (a types.SimpleNamespace, [NAMESPACE]), and the other attributes the bundled library reads;
 * the limit on int-str conversions (sys.set_int_max_str_digits).
 */
object PySys {
    private val NO_ARGS = arrayOfNulls<Any?>(0)

    /** types.SimpleNamespace: an object whose attributes its keywords give. */
    @JvmField val NAMESPACE: PyType = PyTypes.newBuiltin("SimpleNamespace").also {
        it.module = "types"
        it.tpName = "types.SimpleNamespace"
        it.dispatchBuiltins = true
    }

    /** CPython's int-str limits (sys.int_info): the default limit, and the least one may set but 0 (none). */
    const val DEFAULT_MAX_STR_DIGITS = 4300
    const val STR_DIGITS_CHECK_THRESHOLD = 640

    /**
     * A struct sequence's type: a tuple of [fields], each read by its name too, and [hidden] ones read by name only
     * (kept with the tuple's class, [PySub.state]); made by NeonPy alone.
     */
    @JvmStatic
    @JvmOverloads
    fun structType(name: String, fields: List<String?>, hidden: List<String> = emptyList(), module: String = "sys",
                   constructable: Boolean = false): PyType {
        val t = PyType(name, arrayOf(PyTypes.TUPLE))
        t.mro = PyTypes.c3(t, t.bases)
        t.module = module
        // (its tp_name: a builtins type's its name alone)
        val qual = if (module == "builtins") name else "$module.$name"
        t.tpName = qual
        t.subclassable = false
        // (a tuple: no __dict__ for attributes of one's own)
        t.hasDict = false
        // (its repr and fields are builtins dispatch takes: a tuple's own methods are what the runtime's paths do)
        t.dispatchBuiltins = true
        // (a field without a name (os.stat_result's integer times): in the tuple only; the members' names are the
        // named ones, visible then hidden, and the repr names the visible fields by them, as structseq.c does)
        val members = fields.filterNotNull() + hidden
        val unnamed = fields.count { it == null }
        val visible = fields.size
        val real = visible + hidden.size
        t.newImpl = if (!constructable) { _, _, _, _ -> throw PyRt.typeError("cannot create '$qual' instances") } else { _, cls, a, kw ->
            // structseq_new(sequence, dict=None): the visible fields from the sequence, the hidden ones from the rest
            // of it, else from dict by name, else None
            val names = listOf("sequence", "dict")
            val v = arrayOfNulls<Any?>(2)
            if (a.size > 2) throw PyRt.typeError("$name() takes at most 2 arguments (${a.size} given)")
            a.copyInto(v)
            kw?.map?.forEach { (k, x) ->
                val n = (k.v as PyStr).s
                val i = names.indexOf(n)
                if (i < 0) throw PyRt.typeError("$name() got an unexpected keyword argument '$n'")
                if (i < a.size) throw PyRt.typeError("argument for $name() given by name ('$n') and position (${i + 1})")
                v[i] = x
            }
            if (v[0] == null) throw PyRt.typeError("$name() missing required argument 'sequence' (pos 1)")
            val items = if (v[0] is PyTuple || v[0] is PyList) PyBuiltins.toList(v[0]) else try {
                PyBuiltins.toList(v[0])
            } catch (e: PyException) {
                throw PyRt.typeError("constructor requires a sequence")
            }
            val dict = v[1]?.takeIf { it !== PyNone }?.let { it as? PyDict ?: throw PyRt.typeError("$qual() takes a dict as second arg, if any") }
            val len = items.size
            if (len < visible) {
                throw PyRt.typeError(if (visible == real) "$qual() takes a $visible-sequence ($len-sequence given)"
                    else "$qual() takes an at least $visible-sequence ($len-sequence given)")
            }
            if (len > real) {
                throw PyRt.typeError(if (visible == real) "$qual() takes a $visible-sequence ($len-sequence given)"
                    else "$qual() takes an at most $real-sequence ($len-sequence given)")
            }
            val extra = Array<Any?>(hidden.size) { j ->
                val i = visible + j
                if (i < len) items[i] else dict?.get(PyStr(members[i - unnamed])) ?: PyNone
            }
            PyTuple(items.subList(0, visible).toTypedArray()).also { it.sub = PySub(cls as PyType); it.sub!!.state = extra }
        }
        // (its fields read-only, as members of CPython's are)
        val readonly = PyBuiltin("__set__") { _, _ -> throw PyException("AttributeError", "readonly attribute") }
        for ((i, f) in fields.withIndex()) {
            if (f == null) continue
            t.def(f, PyProperty(PyBuiltin(f) { _, a -> (a[0] as PyTuple).items[i] }, readonly, readonly, null).also { it.name = f })
        }
        for ((i, f) in hidden.withIndex()) {
            t.def(f, PyProperty(PyBuiltin(f) { _, a -> ((a[0] as PyTuple).sub!!.state as Array<*>)[i] }, readonly, readonly, null).also { it.name = f })
        }
        t.def("__repr__", PyBuiltin("__repr__") { _, a ->
            val items = (a[0] as PyTuple).items
            PyStr((0 until visible).joinToString(", ", "$qual(", ")") { "${members[it]}=${PyRt.repr(items[it])}" })
        })
        t.def("__reduce__", PyBuiltin("__reduce__") { _, a ->
            val v = a[0] as PyTuple
            val extra = PyDict().also { d -> for ((i, f) in hidden.withIndex()) d[PyStr(f)] = (v.sub!!.state as Array<*>)[i] }
            PyTuple(arrayOf(t, PyTuple(arrayOf(PyTuple(v.items), extra))))
        })
        t.def("n_fields", real.toLong())
        t.def("n_sequence_fields", visible.toLong())
        t.def("n_unnamed_fields", unnamed.toLong())
        t.def("__match_args__", PyTuple(fields.filterNotNull().map { PyStr(it) }.toTypedArray<Any?>()))
        return t
    }

    @JvmStatic
    fun struct(t: PyType, vararg values: Any?): PyTuple = PyTuple(arrayOf(*values)).also { it.sub = PySub(t) }

    @JvmStatic
    fun struct(t: PyType, values: Array<Any?>, hidden: Array<Any?>): PyTuple = struct(t, *values).also { it.sub!!.state = hidden }

    @JvmField val VERSION_INFO = structType("version_info", listOf("major", "minor", "micro", "releaselevel", "serial"))
    private val ASYNCGEN_HOOKS = structType("asyncgen_hooks", listOf("firstiter", "finalizer"), module = "builtins", constructable = true)
    private val FLAGS = structType("flags", listOf("debug", "inspect", "interactive", "optimize", "dont_write_bytecode",
        "no_user_site", "no_site", "ignore_environment", "verbose", "bytes_warning", "quiet", "hash_randomization", "isolated",
        "dev_mode", "utf8_mode", "warn_default_encoding", "safe_path", "int_max_str_digits"), listOf("gil", "thread_inherit_context", "context_aware_warnings"))
    private val FLOAT_INFO = structType("float_info", listOf("max", "max_exp", "max_10_exp", "min", "min_exp", "min_10_exp",
        "dig", "mant_dig", "epsilon", "radix", "rounds"))
    private val INT_INFO = structType("int_info", listOf("bits_per_digit", "sizeof_digit", "default_max_str_digits", "str_digits_check_threshold"))
    private val HASH_INFO = structType("hash_info", listOf("width", "modulus", "inf", "nan", "imag", "algorithm", "hash_bits", "seed_bits", "cutoff"))
    private val THREAD_INFO = structType("thread_info", listOf("name", "lock", "version"))

    /** The names of the modules CPython 3.14's standard library has (sys.stdlib_module_names), whether NeonPy has them or not. */
    private const val STDLIB_NAMES = "__future__ _abc _aix_support _android_support _apple_support _ast _ast_unparse _asyncio " +
        "_bisect _blake2 _bz2 _codecs _codecs_cn _codecs_hk _codecs_iso2022 _codecs_jp _codecs_kr _codecs_tw _collections " +
        "_collections_abc _colorize _compat_pickle _contextvars _csv _ctypes _curses _curses_panel _datetime _dbm _decimal " +
        "_elementtree _frozen_importlib _frozen_importlib_external _functools _gdbm _hashlib _heapq _hmac _imp _interpchannels " +
        "_interpqueues _interpreters _io _ios_support _json _locale _lsprof _lzma _markupbase _md5 _multibytecodec " +
        "_multiprocessing _opcode _opcode_metadata _operator _osx_support _overlapped _pickle _posixshmem _posixsubprocess " +
        "_py_abc _py_warnings _pydatetime _pydecimal _pyio _pylong _pyrepl _queue _random _remote_debugging _scproxy _sha1 " +
        "_sha2 _sha3 _signal _sitebuiltins _socket _sqlite3 _sre _ssl _stat _statistics _string _strptime _struct _suggestions " +
        "_symtable _sysconfig _thread _threading_local _tkinter _tokenize _tracemalloc _types _typing _uuid _warnings _weakref " +
        "_weakrefset _winapi _wmi _zoneinfo _zstd abc annotationlib antigravity argparse array ast asyncio atexit base64 bdb " +
        "binascii bisect builtins bz2 cProfile calendar cmath cmd code codecs codeop collections colorsys compileall " +
        "compression concurrent configparser contextlib contextvars copy copyreg csv ctypes curses dataclasses datetime dbm " +
        "decimal difflib dis doctest email encodings ensurepip enum errno faulthandler fcntl filecmp fileinput fnmatch " +
        "fractions ftplib functools gc genericpath getopt getpass gettext glob graphlib grp gzip hashlib heapq hmac html http " +
        "idlelib imaplib importlib inspect io ipaddress itertools json keyword linecache locale logging lzma mailbox marshal " +
        "math mimetypes mmap modulefinder msvcrt multiprocessing netrc nt ntpath nturl2path numbers opcode operator optparse " +
        "os pathlib pdb pickle pickletools pkgutil platform plistlib poplib posix posixpath pprint profile pstats pty pwd " +
        "py_compile pyclbr pydoc pydoc_data pyexpat queue quopri random re readline reprlib resource rlcompleter runpy sched " +
        "secrets select selectors shelve shlex shutil signal site smtplib socket socketserver sqlite3 sre_compile " +
        "sre_constants sre_parse ssl stat statistics string stringprep struct subprocess symtable sys sysconfig syslog " +
        "tabnanny tarfile tempfile termios textwrap this threading time timeit tkinter token tokenize tomllib trace traceback " +
        "tracemalloc tty turtle turtledemo types typing unicodedata unittest urllib uuid venv warnings wave weakref " +
        "webbrowser winreg winsound wsgiref xml xmlrpc zipapp zipfile zipimport zlib zoneinfo"

    private const val COPYRIGHT = "Copyright (c) 2001 Python Software Foundation.\nAll Rights Reserved.\n\n" +
        "Copyright (c) 2000 BeOpen.com.\nAll Rights Reserved.\n\n" +
        "Copyright (c) 1995-2001 Corporation for National Research Initiatives.\nAll Rights Reserved.\n\n" +
        "Copyright (c) 1991-1995 Stichting Mathematisch Centrum, Amsterdam.\nAll Rights Reserved."

    init {
        val t = NAMESPACE
        fun kwFill(o: PyObject, a: Array<Any?>, kw: PyDict?) {
            if (a.size > 2) throw PyRt.typeError("SimpleNamespace expected at most 1 argument, got ${a.size - 1}")
            val d = o.dict ?: PyDict().also { o.dict = it }
            // (a mapping or an iterable of pairs first, as dict() takes it; then the keywords)
            if (a.size == 2) {
                val m = PyBuiltins.dictFill(PyDict(), arrayOf(a[1]), null)
                for ((k, v) in m.map) {
                    if (k.v !is PyStr) throw PyRt.typeError("keywords must be strings")
                    d.map[k] = v
                }
            }
            if (kw != null) for ((k, v) in kw.map) d.map[k] = v
        }
        t.def("__new__", PyBuiltin("__new__", { _, a -> PyObject(a[0] as PyType) }, { _, a, _ -> PyObject(a[0] as PyType) }))
        t.def("__init__", PyBuiltin("__init__", { _, a -> kwFill(a[0] as PyObject, a, null); PyNone }, { _, a, kw -> kwFill(a[0] as PyObject, a, kw); PyNone }))
        t.def("__repr__", PyBuiltin("__repr__") { ctx, a ->
            val o = a[0] as PyObject
            val name = if (o.type === NAMESPACE) "namespace" else o.type.name
            PyStr(PyRt.reprGuarded(ctx, o, "$name(...)") {
                (o.dict?.map ?: emptyMap()).entries.joinToString(", ", "$name(", ")") { "${PyRt.str(it.key.v)}=${PyRt.repr(it.value)}" }
            })
        })
        t.def("__eq__", PyBuiltin("__eq__") { _, a ->
            val x = a[0] as PyObject
            val y = a[1]
            if (y !is PyObject || !y.type.isSubtype(NAMESPACE)) PyNotImplemented
            else PyBool.of(PyRt.eq(x.dict ?: PyDict(), y.dict ?: PyDict()))
        })
        t.def("__hash__", PyNone)
        t.def("__replace__", PyBuiltin("__replace__", { ctx, a -> replace(ctx, a[0] as PyObject, null) }, { ctx, a, kw ->
            if (a.size > 1) throw PyRt.typeError("__replace__() takes no positional arguments")
            replace(ctx, a[0] as PyObject, kw)
        }))
        t.def("__reduce__", PyBuiltin("__reduce__") { _, a ->
            val o = a[0] as PyObject
            PyTuple(arrayOf(o.type, PyTuple.EMPTY, o.dict ?: PyDict()))
        })
    }

    private fun replace(ctx: PyContext, o: PyObject, kw: PyDict?): Any? {
        val n = PyCalls.callWith(ctx, o.type, NO_ARGS, NO_ARGS, NO_ARGS) as PyObject
        val d = n.dict ?: PyDict().also { n.dict = it }
        o.dict?.let { for ((k, v) in it.map) d.map[k] = v }
        if (kw != null) for ((k, v) in kw.map) d.map[k] = v
        return n
    }

    /** A SimpleNamespace of [fields]. */
    @JvmStatic
    fun namespace(vararg fields: Pair<String, Any?>): PyObject = PyObject(NAMESPACE).also { o ->
        o.dict = PyDict().also { d -> for ((k, v) in fields) d[PyStr(k)] = v }
    }

    /** sys's attributes past its modules, streams, exit and exceptions ([PyImport.sys]). */
    @JvmStatic
    fun fill(ctx: PyContext, d: PyDict) {
        fun def(n: String, impl: (Array<Any?>) -> Any?) {
            d[PyStr(n)] = PyBuiltin(n) { _, a -> impl(a) }
        }
        val version = struct(VERSION_INFO, 3L, 14L, 6L, PyStr("final"), 0L)
        d[PyStr("version_info")] = version
        d[PyStr("implementation")] = namespace("name" to PyStr("neonpy"), "cache_tag" to PyNone, "version" to version,
            "hexversion" to 0x030e06f0L, "supports_isolated_interpreters" to PyBool.FALSE)
        // (no bytecode written, no site, the environment not read: a sandbox's interpreter; strs are UTF-8)
        d[PyStr("flags")] = struct(FLAGS, arrayOf(0L, 0L, 0L, 0L, 1L, 1L, 1L, 1L, 0L, 0L, 0L, 0L, 1L, PyBool.FALSE, 1L, 0L, PyBool.TRUE,
            DEFAULT_MAX_STR_DIGITS.toLong()), arrayOf(1L, 0L, 0L))
        d[PyStr("float_info")] = struct(FLOAT_INFO, Double.MAX_VALUE, 1024L, 308L, java.lang.Double.MIN_NORMAL, -1021L, -307L,
            15L, 53L, Math.ulp(1.0), 2L, 1L)
        d[PyStr("int_info")] = struct(INT_INFO, 30L, 4L, DEFAULT_MAX_STR_DIGITS.toLong(), STR_DIGITS_CHECK_THRESHOLD.toLong())
        d[PyStr("hash_info")] = struct(HASH_INFO, 64L, (1L shl 61) - 1, 314159L, 0L, 1000003L, PyStr("siphash13"), 64L, 128L, 0L)
        d[PyStr("thread_info")] = struct(THREAD_INFO, PyNone, PyNone, PyNone)
        // (the finalizer is kept, not called: no asynchronous generator is finalized when collected, §12.2)
        def("get_asyncgen_hooks") { a ->
            if (a.isNotEmpty()) throw PyRt.typeError("sys.get_asyncgen_hooks() takes no arguments (${a.size} given)")
            struct(ASYNCGEN_HOOKS, ctx.asyncgenFirstiter ?: PyNone, ctx.asyncgenFinalizer ?: PyNone)
        }
        fun setHooks(a: Array<Any?>, kw: PyDict?): Any? {
            if (a.size > 2) throw PyRt.typeError("function takes at most 2 arguments (${a.size} given)")
            val v = arrayOfNulls<Any?>(2)
            val set = BooleanArray(2)
            for (i in a.indices) { v[i] = a[i]; set[i] = true }
            if (kw != null) for ((k, x) in kw.map) {
                val i = when ((k.v as PyStr).s) {
                    "firstiter" -> 0
                    "finalizer" -> 1
                    else -> throw PyRt.typeError("this function got an unexpected keyword argument '${(k.v as PyStr).s}'")
                }
                if (set[i]) throw PyRt.typeError("argument for function given by name ('${(k.v as PyStr).s}') and position (${i + 1})")
                v[i] = x
                set[i] = true
            }
            val names = arrayOf("firstiter", "finalizer")
            for (i in 0..1) if (set[i] && v[i] !== PyNone && !PyCalls.isCallable(v[i])) {
                throw PyRt.typeError("callable ${names[i]} expected, got ${PyTypes.name(v[i])}")
            }
            if (set[1]) ctx.asyncgenFinalizer = v[1].takeIf { it !== PyNone }
            if (set[0]) ctx.asyncgenFirstiter = v[0].takeIf { it !== PyNone }
            return PyNone
        }
        d[PyStr("set_asyncgen_hooks")] = PyBuiltin("set_asyncgen_hooks", { _, a -> setHooks(a, null) }, { _, a, kw -> setHooks(a, kw) })
        def("get_coroutine_origin_tracking_depth") { a ->
            if (a.isNotEmpty()) throw PyRt.typeError("sys.get_coroutine_origin_tracking_depth() takes no arguments (${a.size} given)")
            ctx.coroutineOriginDepth.toLong()
        }
        fun setDepth(a: Array<Any?>, kw: PyDict?): Any? {
            if (a.size > 1) throw PyRt.typeError("set_coroutine_origin_tracking_depth() takes at most 1 argument (${a.size} given)")
            var v: Any? = a.getOrNull(0)
            if (kw != null) for ((k, x) in kw.map) {
                val n = (k.v as PyStr).s
                if (n != "depth") throw PyRt.typeError("set_coroutine_origin_tracking_depth() got an unexpected keyword argument '$n'")
                if (v != null) throw PyRt.typeError("argument for set_coroutine_origin_tracking_depth() given by name ('depth') and position (1)")
                v = x
            }
            if (v == null) throw PyRt.typeError("set_coroutine_origin_tracking_depth() missing required argument 'depth' (pos 1)")
            val n = PyOperator.index(v)
            if (PyRt.order(0, n, 0L)) throw PyException("ValueError", "depth must be >= 0")
            ctx.coroutineOriginDepth = (n as? Long)?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: Int.MAX_VALUE
            return PyNone
        }
        d[PyStr("set_coroutine_origin_tracking_depth")] = PyBuiltin("set_coroutine_origin_tracking_depth", { _, a -> setDepth(a, null) }, { _, a, kw -> setDepth(a, kw) })
        d[PyStr("maxunicode")] = 0x10FFFFL
        d[PyStr("api_version")] = 1013L
        d[PyStr("copyright")] = PyStr(COPYRIGHT)
        // (where the bundled library is said to be: prefix/lib/python3.14)
        for (n in listOf("prefix", "exec_prefix", "base_prefix", "base_exec_prefix")) d[PyStr(n)] = PyStr(PyStdlib.PREFIX)
        d[PyStr("platlibdir")] = PyStr("lib")
        d[PyStr("pycache_prefix")] = PyNone
        d[PyStr("dont_write_bytecode")] = PyBool.TRUE
        d[PyStr("abiflags")] = PyStr("")
        d[PyStr("orig_argv")] = PyList()
        d[PyStr("warnoptions")] = PyList()
        d[PyStr("_xoptions")] = PyDict()
        d[PyStr("meta_path")] = PyList()
        d[PyStr("path_hooks")] = PyList()
        d[PyStr("path_importer_cache")] = PyDict()
        d[PyStr("builtin_module_names")] = PyTuple(PyImport.BUILTIN_NAMES.sorted().map { PyStr(it) }.toTypedArray())
        d[PyStr("stdlib_module_names")] = PySet(true).also { s -> for (n in STDLIB_NAMES.split(' ')) s.addKey(PyStr(n)) }
        // (stdin: descriptor 0's, nothing to read from it but the end)
        d[PyStr("__stdin__")] = d[PyStr("stdin")]
        d[PyStr("__stdout__")] = d[PyStr("stdout")]
        d[PyStr("__stderr__")] = d[PyStr("stderr")]
        def("getdefaultencoding") { _ -> PyStr("utf-8") }
        def("getfilesystemencoding") { _ -> PyStr("utf-8") }
        def("getfilesystemencodeerrors") { _ -> PyStr("surrogateescape") }
        def("getfilesystemencodeerrors") { _ -> PyStr("surrogateescape") }
        def("is_finalizing") { _ -> PyBool.FALSE }
        def("_is_gil_enabled") { _ -> PyBool.TRUE }
        def("_is_interned") { _ -> PyBool.FALSE }
        def("gettrace") { _ -> PyNone }
        def("getprofile") { _ -> PyNone }
        var interval = 0.005
        def("getswitchinterval") { _ -> interval }
        def("setswitchinterval") { a ->
            val v = PyRt.toDouble(a[0])
            if (v <= 0) throw PyException("ValueError", "switch interval must be strictly positive")
            interval = v
            PyNone
        }
        def("get_int_max_str_digits") { _ -> ctx.maxStrDigits.toLong() }
        d[PyStr("set_int_max_str_digits")] = PyBuiltin("set_int_max_str_digits", { _, a -> setMaxStrDigits(ctx, a.getOrNull(0)) }, { _, a, kw ->
            setMaxStrDigits(ctx, kw[PyStr("maxdigits")] ?: a.getOrNull(0))
        })
        // (audit hooks: called with each event audit() raises, in the order they were added)
        val hooks = ArrayList<Any?>()
        def("addaudithook") { a -> hooks.add(a[0]); PyNone }
        d[PyStr("audit")] = PyBuiltin("audit") { c, a ->
            if (a.isEmpty()) throw PyRt.typeError("audit() missing 1 required positional argument: 'event'")
            val event = a[0] as? PyStr ?: throw PyRt.typeError("expected str for argument 'event', not ${PyTypes.name(a[0])}")
            val rest = PyTuple(a.copyOfRange(1, a.size))
            for (h in hooks) PyCalls.callWith(c, h, arrayOf(event, rest), NO_ARGS, NO_ARGS)
            PyNone
        }
        def("getsizeof") { a -> sizeOf(ctx, a[0], a.getOrNull(1)) }
        // (the interactive prompt's: repr to stdout, kept in builtins._)
        val display = PyBuiltin("displayhook") { c, a ->
            val v = a.getOrNull(0)
            if (v !== PyNone && v != null) {
                c.builtins[PyStr("_")] = PyNone
                val out = c.sysModule.dict[PyStr("stdout")]
                PyCalls.callWith(c, PyRt.getAttr(out, "write"), arrayOf(PyStr(PyRt.repr(v) + "\n")), NO_ARGS, NO_ARGS)
                c.builtins[PyStr("_")] = v
            }
            PyNone
        }
        d[PyStr("displayhook")] = display
        d[PyStr("__displayhook__")] = display
    }

    private fun setMaxStrDigits(ctx: PyContext, v: Any?): Any? {
        val n = PyRt.indexValue(v) as? Long ?: throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer")
        if (n != 0L && n < STR_DIGITS_CHECK_THRESHOLD) throw PyException("ValueError", "maxdigits must be >= $STR_DIGITS_CHECK_THRESHOLD or 0 for unlimited")
        ctx.maxStrDigits = n.toInt()
        return PyNone
    }

    /**
     * sys.getsizeof: an object's own __sizeof__ where its class has one, else near what CPython 3.14 (64 bits) gives
     * for the builtin kinds as it lays them out (the JVM's sizes are not the program's to see).
     */
    /**
     * CPython's pre-header of a class's instances: its managed dict and weak references' pointers (16), where it has
     * them, a class (not a builtin type, nor a module's, which have a tp_name) over a builtin base without weak
     * references of its own (object, list...; array's and set's keep theirs, and a subclass's dict, in the object).
     */
    private fun preheader(t: PyType): Long =
        if (t.heap && t.tpName == null && (t.hasDict || t.hasWeakref) && t.mro.first { !it.heap || it.tpName != null } in MANAGED_BASES) 16L else 0L

    private val MANAGED_BASES = setOf(PyTypes.OBJECT, PyTypes.INT, PyTypes.FLOAT, PyTypes.COMPLEX, PyTypes.STR, PyTypes.BYTES,
        PyTypes.BYTEARRAY, PyTypes.TUPLE, PyTypes.LIST, PyTypes.DICT)

    /** object.__sizeof__: sys.getsizeof's size less a collected object's header (an instance's, its dict pointers too). */
    @JvmStatic
    fun objectSize(ctx: PyContext, v: Any?): Long {
        val n = baseSize(v)
        return n - when (v) {
            is PyList, is PyDict, is PySet, is PyTuple, is PyFunction, is PyBuiltin, is PyBoundMethod, is PyIterator -> 16L
            is PyObject -> if (v.type === PyTypes.OBJECT) 0L else 16L + preheader(v.type)
            else -> 0L
        } - when (val sub = (v as? PyValue)?.sub) {
            null -> 0L
            else -> preheader(sub.cls)
        }
    }

    private fun sizeOf(ctx: PyContext, v: Any?, default: Any?): Any? {
        val cls = PyClasses.classOf(v)
        if (cls != null) PyDunder.userMethod(cls, "__sizeof__")?.let {
            val r = PyRt.indexValue(PyCalls.callWith(ctx, it, arrayOf(v), NO_ARGS, NO_ARGS))
            if (r !is Long) return default ?: throw PyRt.typeError("an integer is required")
            if (r < 0) throw PyException("ValueError", "__sizeof__() should return >= 0")
            // (a gc-tracked object's header, and an instance's dict and weakref pointers before it)
            return r + 16 + preheader(cls)
        }
        return baseSize(v)
    }

    /** sys.getsizeof's size of [v] by its kind (CPython's tp_basicsize, items and the collector's header). */
    private fun baseSize(v: Any?): Long {
        val n = when (v) {
            // (Linux CPython 3.14's sizes: a bool an int of one digit, 0 one digit too)
            null, PyNone, PyNotImplemented, PyEllipsis -> 16L
            is PyBool -> 28L
            is Long -> 28L + if (Math.abs(v) >= 1L shl 30) (if (Math.abs(v) >= 1L shl 60) 8L else 4L) else 0L
            is java.math.BigInteger -> 24L + 4L * ((v.abs().bitLength() + 29) / 30)
            is Double -> 24L
            is PyStr -> {
                val max = v.s.codePoints().max().orElse(0)
                val n = v.s.codePointCount(0, v.s.length).toLong()
                when {
                    max < 128 -> 41L + n
                    // (a one-character Latin-1 str is a cached one, its UTF-8 kept with it)
                    max < 256 -> if (n == 1L) 61L else 57L + n
                    max < 65536 -> 58L + 2 * n
                    else -> 60L + 4 * n
                }
            }
            is PyBytes -> 33L + v.b.size
            is PyByteArray -> if (v.size == 0) 56L else 57L + v.size
            is PyTuple -> 48L + 8L * v.items.size
            is PyList -> 56L + 8L * v.items.size
            is PyDict -> if (v.size == 0) 64L else 184L + 40L * v.size
            is PySet -> 216L
            is PyComplex -> 32L
            is PyRange -> 48L
            is PySlice -> 56L
            is PyFunction -> 168L
            is PyBuiltin, is PyBoundMethod -> 72L
            is PyType -> if (v.heap) 1720L else 416L
            is PyObject -> if (v.type === PyTypes.OBJECT) 16L else 32L + 8L * v.type.slotCount + preheader(v.type)
            is PyIterator -> 48L
            else -> 56L
        }
        // (a subclass's instance: its pre-header and its slots too)
        val sub = (v as? PyValue)?.sub ?: return n
        return n + preheader(sub.cls) + 8L * sub.cls.slotCount
    }
}
