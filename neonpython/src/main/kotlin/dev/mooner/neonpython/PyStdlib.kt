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

    /**
     * `_thread`, for one thread (§9's decision 5: designed for a GIL later): a lock never blocks the thread holding
     * the interpreter, so acquiring one held (a deadlock in CPython) fails at once where it may not wait, and raises
     * where it would wait for ever.
     */
    @JvmStatic
    fun fillThread(ctx: PyContext, d: PyDict) {
        val lockType = PyTypes.newBuiltin("lock", subclassable = false).also { it.module = "_thread"; it.dispatchBuiltins = true }
        val rlockType = PyTypes.newBuiltin("RLock").also { it.module = "_thread"; it.dispatchBuiltins = true }
        fun field(o: PyObject, n: String): Any? = o.dict?.get(PyStr(n))
        fun set(o: PyObject, n: String, v: Any?) {
            (o.dict ?: PyDict().also { o.dict = it })[PyStr(n)] = v
        }
        fun newLock(t: PyType): PyObject = PyObject(t).also { set(it, "_count", 0L) }
        fun acquire(a: Array<Any?>, kw: PyDict?, reentrant: Boolean): Any? {
            val o = a[0] as PyObject
            val blocking = PyRt.truthy(kw?.get(PyStr("blocking")) ?: a.getOrNull(1) ?: PyBool.TRUE)
            val timeout = kw?.get(PyStr("timeout")) ?: a.getOrNull(2) ?: -1L
            val n = field(o, "_count") as Long
            if (n == 0L || reentrant) {
                set(o, "_count", n + 1)
                return PyBool.TRUE
            }
            // (held, by the only thread there is)
            if (!blocking || timeout != -1L && PyRt.toDouble(timeout) >= 0) return PyBool.FALSE
            throw PyException("RuntimeError", "cannot acquire a lock this thread holds (one thread: it would wait for ever)")
        }
        fun release(o: PyObject) {
            val n = field(o, "_count") as Long
            if (n == 0L) throw PyException("RuntimeError", if (o.type === lockType) "release unlocked lock" else "cannot release un-acquired lock")
            set(o, "_count", n - 1)
        }
        for ((t, re) in listOf(lockType to false, rlockType to true)) {
            t.def("acquire", PyBuiltin("acquire", { _, a -> acquire(a, null, re) }, { _, a, kw -> acquire(a, kw, re) }))
            t.def("acquire_lock", t.dict[PyStr("acquire")])
            t.def("__enter__", PyBuiltin("__enter__") { _, a -> acquire(a, null, re) })
            t.def("release", PyBuiltin("release") { _, a -> release(a[0] as PyObject); PyNone })
            t.def("release_lock", t.dict[PyStr("release")])
            t.def("__exit__", PyBuiltin("__exit__") { _, a -> release(a[0] as PyObject); PyNone })
            t.def("locked", PyBuiltin("locked") { _, a -> PyBool.of(field(a[0] as PyObject, "_count") != 0L) })
            t.def("_at_fork_reinit", PyBuiltin("_at_fork_reinit") { _, a -> set(a[0] as PyObject, "_count", 0L); PyNone })
            t.def("__repr__", PyBuiltin("__repr__") { _, a ->
                val o = a[0] as PyObject
                PyStr("<${if (field(o, "_count") != 0L) "locked" else "unlocked"} _thread.${t.name} object at 0x${Integer.toHexString(System.identityHashCode(o))}>")
            })
        }
        rlockType.def("__new__", PyBuiltin("__new__") { _, a -> newLock(a[0] as PyType) })
        rlockType.def("_is_owned", PyBuiltin("_is_owned") { _, a -> PyBool.of(field(a[0] as PyObject, "_count") != 0L) })
        rlockType.def("_recursion_count", PyBuiltin("_recursion_count") { _, a -> field(a[0] as PyObject, "_count") })
        fun def(n: String, impl: (Array<Any?>) -> Any?) {
            d[PyStr(n)] = PyBuiltin(n) { _, a -> impl(a) }
        }
        def("allocate_lock") { _ -> newLock(lockType) }
        def("allocate") { _ -> newLock(lockType) }
        d[PyStr("LockType")] = lockType
        d[PyStr("lock")] = lockType
        d[PyStr("RLock")] = rlockType
        def("get_ident") { _ -> 1L }
        def("get_native_id") { _ -> 1L }
        def("_count") { _ -> 0L }
        def("stack_size") { _ -> 0L }
        def("daemon_threads_allowed") { _ -> PyBool.FALSE }
        def("_is_main_interpreter") { _ -> PyBool.TRUE }
        def("start_new_thread") { _ -> throw PyException("RuntimeError", "can't start new thread (NeonPy runs one thread)") }
        def("start_new") { _ -> throw PyException("RuntimeError", "can't start new thread (NeonPy runs one thread)") }
        def("start_joinable_thread") { _ -> throw PyException("RuntimeError", "can't start new thread (NeonPy runs one thread)") }
        def("interrupt_main") { _ -> PyNone }
        def("exit") { _ -> throw PyException(PyBaseException(PyExceptions.type("SystemExit"))) }
        d[PyStr("error")] = PyExceptions.type("RuntimeError")
        d[PyStr("TIMEOUT_MAX")] = 9.223372036854776E9
    }
}
