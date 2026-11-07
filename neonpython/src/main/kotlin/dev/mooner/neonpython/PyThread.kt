package dev.mooner.neonpython

import java.math.BigInteger

/**
 * A `_thread.lock` or `_thread.RLock` (a subclass's instance too): whether it is held, and an RLock's owner (the
 * thread's ident, 0 when free) and level (how many acquisitions past the first), as _PyRecursiveMutex has them.
 */
class PyLock(type: PyType) : PyObject(type) {
    @JvmField var locked = false
    @JvmField var owner = 0L
    @JvmField var level = 0L
}

/** A `_thread._ThreadHandle`: its state and its thread's ident (a Python int: unsigned, 64 bits). */
class PyThreadHandle(type: PyType) : PyObject(type) {
    @JvmField var state = PyThread.NOT_STARTED
    @JvmField var ident: Any = 0L
}

/**
 * `_thread` (docs/PYTHON_PLATFORM.md §9's decision 5, §12 S2): Modules/_threadmodule.c as CPython 3.14 has it on
 * Linux, for the one thread NeonPy runs (its state ready for a GIL later). Nothing waits on another thread, for there
 * is none: a lock held when acquired again (a deadlock in CPython) fails at once where the call may not wait, waits
 * out its timeout and fails where it has one (in slices, the agent's limits and interrupts checked between them, as
 * time.sleep's), and raises RuntimeError where it would wait for ever. A thread cannot be started: start_new_thread
 * and start_joinable_thread raise RuntimeError("can't start new thread"), CPython's error where the OS refuses one
 * (their arguments checked first, a handle given left done). The thread's ident and native id are 1 (os.getpid()'s:
 * a Linux main thread's id is its process's); its name, set_name's, starts empty (sys.executable is). interrupt_main()
 * raises KeyboardInterrupt at once for SIGINT (the only signal with a Python handler, there being no signal module)
 * and ignores the others. `_local` is an object with a dict: one thread has one set of thread-local values.
 */
object PyThread {
    private val NO_ARGS = arrayOfNulls<Any?>(0)

    /** The one thread's ident (get_ident(), _get_main_thread_ident()) and native id. */
    const val IDENT = 1L

    // ThreadHandle's states (STARTING passes within a call here)
    const val NOT_STARTED = 0
    const val RUNNING = 2
    const val DONE = 3

    /** A timeout not given: -1 seconds (lock_acquire_parse_args' unset_timeout), in nanoseconds. */
    private const val UNSET = -1_000_000_000L

    /** Linux's: PY_TIMEOUT_MAX (LLONG_MAX / 1000 microseconds) in seconds, floored; pthread's name limit; the smallest stack. */
    private const val TIMEOUT_MAX = 9223372036.0
    private const val NAME_MAXLEN = 15L
    private const val MIN_STACK_SIZE = 53248L

    private fun type(name: String, subclassable: Boolean = true): PyType = PyTypes.newBuiltin(name, subclassable).also {
        it.module = "_thread"
        it.tpName = "_thread.$name"
        // (in its dict, as a type made from a spec has it: its instances' too)
        it.dict[PyStr("__module__")] = PyStr("_thread")
        // (its methods builtins dispatch takes: `with lock:` finds __enter__)
        it.dispatchBuiltins = true
    }

    @JvmField val LOCK = type("lock", subclassable = false).also { it.hasDict = false }
    @JvmField val RLOCK = type("RLock").also { it.hasDict = false }
    @JvmField val THREAD_HANDLE = type("_ThreadHandle", subclassable = false).also { it.hasDict = false }
    @JvmField val LOCAL = type("_local")
    @JvmField val EXCEPTHOOK_ARGS = PySys.structType("_ExceptHookArgs", listOf("exc_type", "exc_value", "exc_traceback", "thread"),
        module = "_thread", constructable = true)

    private fun runtimeError(msg: String) = PyException("RuntimeError", msg)
    private fun valueError(msg: String) = PyException("ValueError", msg)

    private fun noKw(fn: String, kw: PyDict?) {
        if (kw != null && kw.size > 0) throw PyRt.typeError("$fn() takes no keyword arguments")
    }

    /** A METH_NOARGS method of [t]: "lock.release() takes no arguments (1 given)". */
    private fun noArgs(t: PyType, n: String, body: (Any?) -> Any?) {
        val q = "${t.name}.$n"
        fun impl(a: Array<Any?>): Any? {
            if (a.size != 1) throw PyRt.typeError("$q() takes no arguments (${a.size - 1} given)")
            return body(a[0])
        }
        t.def(n, PyBuiltin(n, { _, a -> impl(a) }, { _, a, kw -> noKw(q, kw); impl(a) }))
    }

    /** A METH_VARARGS method of [t] (its arguments as given, [self] first). */
    private fun varArgs(t: PyType, n: String, body: (PyContext, Array<Any?>) -> Any?) {
        t.def(n, PyBuiltin(n, { c, a -> body(c, a) }, { c, a, kw -> noKw("${t.name}.$n", kw); body(c, a) }))
    }

    /** A METH_VARARGS|METH_KEYWORDS method of [t]. */
    private fun kwArgs(t: PyType, n: String, body: (PyContext, Array<Any?>, PyDict?) -> Any?) {
        t.def(n, PyBuiltin(n, { c, a -> body(c, a, null) }, { c, a, kw -> body(c, a, kw) }))
    }

    private fun lockOf(t: PyType, v: Any?, n: String): PyLock =
        (v as? PyLock)?.takeIf { it.type.isSubtype(t) }
            ?: throw PyRt.typeError("descriptor '$n' for '${t.tpName}' objects doesn't apply to a '${PyTypes.name(v)}' object")

    private fun handleOf(v: Any?, n: String): PyThreadHandle = v as? PyThreadHandle
        ?: throw PyRt.typeError("descriptor '$n' for '_thread._ThreadHandle' objects doesn't apply to a '${PyTypes.name(v)}' object")

    private fun unsigned(v: Long): String = java.lang.Long.toUnsignedString(v)

    // ------------------------------------------------------------------ waiting

    /**
     * What acquiring a lock held (by the one thread) does: fail where [timeout] is 0, wait it out and fail where it
     * is more, raise where it is none (nothing could release the lock: it would wait for ever).
     */
    private fun waitHeld(ctx: PyContext, timeout: Long, what: String): Any? {
        if (timeout == UNSET) throw runtimeError("cannot $what (one thread: it would wait for ever)")
        if (timeout > 0) PyTime.waitFor(ctx, timeout)
        return PyBool.FALSE
    }

    /** lock_acquire_parse_args (`|pO:acquire`, blocking and timeout): the timeout in nanoseconds, 0 not blocking. */
    private fun acquireTimeout(a: Array<Any?>, kw: PyDict?): Long {
        val args = PyMath.clinic("acquire", a.copyOfRange(1, a.size), kw, listOf("blocking", "timeout"), 0, 0, 2)
        val blocking = args[0]?.let { PyRt.truthy(it) } ?: true
        var timeout = args[1]?.let { PyTime.timeoutNs(it) } ?: UNSET
        if (!blocking && timeout != UNSET) throw valueError("can't specify a timeout for a non-blocking call")
        if (timeout < 0 && timeout != UNSET) throw valueError("timeout value must be a non-negative number")
        if (!blocking) {
            timeout = 0
        } else if (timeout != UNSET) {
            // (_PyTime_AsMicroseconds, rounded up, against PY_TIMEOUT_MAX)
            val us = timeout / 1000 + if (timeout % 1000 != 0L) 1 else 0
            if (us > Long.MAX_VALUE / 1000) throw PyException("OverflowError", "timeout value is too large")
        }
        return timeout
    }

    // ------------------------------------------------------------------ the types

    private fun defineLock() {
        val t = LOCK
        t.newImpl = { _, _, a, kw ->
            noKw("lock", kw)
            if (a.isNotEmpty()) throw PyRt.typeError("lock expected 0 arguments, got ${a.size}")
            PyLock(LOCK)
        }
        fun acquire(c: PyContext, a: Array<Any?>, kw: PyDict?): Any? {
            val o = lockOf(t, a.getOrNull(0), "acquire")
            val timeout = acquireTimeout(a, kw)
            if (!o.locked) {
                o.locked = true
                return PyBool.TRUE
            }
            return waitHeld(c, timeout, "acquire a lock this thread holds")
        }
        fun release(v: Any?): Any? {
            val o = lockOf(t, v, "release")
            if (!o.locked) throw runtimeError("release unlocked lock")
            o.locked = false
            return PyNone
        }
        for (n in listOf("acquire", "acquire_lock", "__enter__")) kwArgs(t, n, ::acquire)
        for (n in listOf("release", "release_lock")) noArgs(t, n, ::release)
        varArgs(t, "__exit__") { _, a -> release(a.getOrNull(0)) }
        for (n in listOf("locked", "locked_lock")) noArgs(t, n) { v -> PyBool.of(lockOf(t, v, n).locked) }
        noArgs(t, "_at_fork_reinit") { v -> lockOf(t, v, "_at_fork_reinit").locked = false; PyNone }
        t.def("__repr__", PyBuiltin("__repr__") { _, a ->
            val o = lockOf(t, a[0], "__repr__")
            PyStr("<${if (o.locked) "locked" else "unlocked"} ${PyTypes.name(o)} object at ${PyGenerators.addr(o)}>")
        })
    }

    private fun defineRLock() {
        val t = RLOCK
        // (rlock_new: its arguments ignored; threading.RLock() warns of them)
        fun new(a: Array<Any?>): Any? {
            val cls = a.getOrNull(0) as? PyType ?: throw PyRt.typeError("RLock.__new__(X): X is not a type object (${PyTypes.name(a.getOrNull(0))})")
            if (!cls.isSubtype(t)) throw PyRt.typeError("RLock.__new__(${cls.name}): ${cls.name} is not a subtype of _thread.RLock")
            return PyLock(cls).also { if (cls.heap) PyWeakrefs.made(it) }
        }
        t.def("__new__", PyBuiltin("__new__", { _, a -> new(a) }, { _, a, _ -> new(a) }))
        fun owned(o: PyLock) = o.locked && o.owner == IDENT
        // (_PyRecursiveMutex_LockTimed: again by its owner, a level more)
        fun lock(c: PyContext, o: PyLock, timeout: Long): Any? {
            if (!o.locked) {
                o.locked = true
                o.owner = IDENT
                o.level = 0
                return PyBool.TRUE
            }
            if (o.owner == IDENT) {
                o.level++
                return PyBool.TRUE
            }
            return waitHeld(c, timeout, "acquire a lock another thread holds")
        }
        fun acquire(c: PyContext, a: Array<Any?>, kw: PyDict?): Any? {
            val o = lockOf(t, a.getOrNull(0), "acquire")
            return lock(c, o, acquireTimeout(a, kw))
        }
        fun release(v: Any?): Any? {
            val o = lockOf(t, v, "release")
            if (!owned(o)) throw runtimeError("cannot release un-acquired lock")
            if (o.level > 0) {
                o.level--
            } else {
                o.owner = 0
                o.locked = false
            }
            return PyNone
        }
        for (n in listOf("acquire", "__enter__")) kwArgs(t, n, ::acquire)
        noArgs(t, "release", ::release)
        varArgs(t, "__exit__") { _, a -> release(a.getOrNull(0)) }
        noArgs(t, "locked") { v -> PyBool.of(lockOf(t, v, "locked").locked) }
        noArgs(t, "_is_owned") { v -> PyBool.of(owned(lockOf(t, v, "_is_owned"))) }
        noArgs(t, "_recursion_count") { v -> lockOf(t, v, "_recursion_count").let { if (owned(it)) it.level + 1 else 0L } }
        noArgs(t, "_release_save") { v ->
            val o = lockOf(t, v, "_release_save")
            if (!owned(o)) throw runtimeError("cannot release un-acquired lock")
            val state = PyTuple(arrayOf(o.level + 1, o.owner))
            o.level = 0
            o.owner = 0
            o.locked = false
            state
        }
        // ("(nK):_acquire_restore": the count and the owner _release_save gave)
        varArgs(t, "_acquire_restore") { c, a ->
            val o = lockOf(t, a.getOrNull(0), "_acquire_restore")
            if (a.size != 2) throw PyRt.typeError("_acquire_restore() takes exactly 1 argument (${a.size - 1} given)")
            val st = a[1] as? PyTuple ?: throw PyRt.typeError("_acquire_restore() argument 1 must be 2-item tuple, not ${PyTypes.name(a[1])}")
            if (st.items.size != 2) throw PyRt.typeError("_acquire_restore() argument 1 must be tuple of length 2, not ${st.items.size}")
            val count = PyRt.indexValue(st.items[0]) ?: throw PyRt.typeError("'${PyTypes.name(st.items[0])}' object cannot be interpreted as an integer")
            if (count !is Long) throw PyException("OverflowError", "Python int too large to convert to C ssize_t")
            val owner = when (val k = PyRt.indexValue(st.items[1]) ?: throw PyRt.typeError("'${PyTypes.name(st.items[1])}' object cannot be interpreted as an integer")) {
                is Long -> k
                else -> (k as BigInteger).toLong()
            }
            lock(c, o, UNSET)
            o.owner = owner
            o.level = count - 1
            PyNone
        }
        noArgs(t, "_at_fork_reinit") { v ->
            val o = lockOf(t, v, "_at_fork_reinit")
            o.locked = false
            o.owner = 0
            o.level = 0
            PyNone
        }
        t.def("__repr__", PyBuiltin("__repr__") { _, a ->
            val o = lockOf(t, a[0], "__repr__")
            val count = if (o.locked) unsigned(o.level + 1) else "0"
            PyStr("<${if (o.locked) "locked" else "unlocked"} ${PyTypes.name(o)} object owner=${unsigned(o.owner)} count=$count at ${PyGenerators.addr(o)}>")
        })
    }

    private fun defineThreadHandle() {
        val t = THREAD_HANDLE
        // (PyThreadHandleObject_tp_new: its arguments ignored)
        t.newImpl = { _, _, _, _ -> PyThreadHandle(THREAD_HANDLE) }
        fun started(h: PyThreadHandle) {
            if (h.state < RUNNING) throw runtimeError("thread not started")
        }
        t.def("ident", PyProperty(PyBuiltin("ident") { _, a -> handleOf(a[0], "ident").ident }, null, null, null).also { it.name = "ident" })
        varArgs(t, "join") { c, a ->
            val h = handleOf(a.getOrNull(0), "join")
            if (a.size > 2) throw PyRt.typeError("join() takes at most 1 argument (${a.size - 1} given)")
            val v = a.getOrNull(1)
            val timeout = if (v == null || v === PyNone) -1L else PyTime.timeoutNs(v)
            started(h)
            if (h.state != DONE) {
                if (h.ident == IDENT) throw runtimeError("Cannot join current thread")
                // (a handle of another thread (_make_thread_handle's): it never ends)
                if (timeout == -1L) throw runtimeError("cannot join thread ${h.ident} (one thread: it would wait for ever)")
                if (timeout > 0) PyTime.waitFor(c, timeout)
            }
            PyNone
        }
        noArgs(t, "_set_done") { v ->
            val h = handleOf(v, "_set_done")
            started(h)
            h.state = DONE
            PyNone
        }
        noArgs(t, "is_done") { v -> PyBool.of(handleOf(v, "is_done").state == DONE) }
        t.def("__repr__", PyBuiltin("__repr__") { _, a ->
            val h = handleOf(a[0], "__repr__")
            PyStr("<${PyTypes.name(h)} object: ident=${h.ident}>")
        })
    }

    private fun defineLocal() {
        val t = LOCAL
        val objectInit = PyTypes.OBJECT.dict[PyStr("__init__")]
        // (local_new: arguments only for a class with its own __init__, which a call runs once: one thread)
        fun new(a: Array<Any?>, kw: PyDict?): Any? {
            val cls = a.getOrNull(0) as? PyType ?: throw PyRt.typeError("_local.__new__(X): X is not a type object (${PyTypes.name(a.getOrNull(0))})")
            if (!cls.isSubtype(t)) throw PyRt.typeError("_local.__new__(${cls.name}): ${cls.name} is not a subtype of _thread._local")
            if (cls.lookup("__init__") === objectInit && (a.size > 1 || kw != null && kw.size > 0)) {
                throw PyRt.typeError("Initialization arguments are not supported")
            }
            return PyObject(cls).also { if (cls.heap) PyWeakrefs.made(it) }
        }
        t.def("__new__", PyBuiltin("__new__", { _, a -> new(a, null) }, { _, a, kw -> new(a, kw) }))
        fun dictName(o: Any?, name: Any?) {
            if (name is PyStr && name.s == "__dict__") {
                throw PyException("AttributeError", "'${PyTypes.name(o)}' object attribute ${PyRt.repr(name)} is read-only")
            }
        }
        fun nameOf(v: Any?): String = (v as? PyStr)?.s ?: throw PyRt.typeError("attribute name must be string, not '${PyTypes.name(v)}'")
        t.def("__setattr__", PyBuiltin("__setattr__") { _, a ->
            if (a.size != 3) throw PyRt.typeError("expected 2 arguments, got ${a.size - 1}")
            val n = nameOf(a[1])
            dictName(a[0], a[1])
            PyClasses.genericSetAttr(a[0], n, a[2])
            PyNone
        })
        t.def("__delattr__", PyBuiltin("__delattr__") { _, a ->
            if (a.size != 2) throw PyRt.typeError("expected 1 argument, got ${a.size - 1}")
            val n = nameOf(a[1])
            dictName(a[0], a[1])
            PyClasses.genericDelAttr(a[0], n)
            PyNone
        })
        t.hooks = 2 or 4
    }

    init {
        defineLock()
        defineRLock()
        defineThreadHandle()
        defineLocal()
    }

    // ------------------------------------------------------------------ the module

    /** PyLong_Check, then PyLong_AsUnsignedLongLong: a thread's ident. */
    private fun identOf(v: Any?): Any {
        val i: Any = when {
            v is Long || v is BigInteger -> v
            v is PyBool -> if (v.value) 1L else 0L
            v is PyNumSub && v.num !is Double -> v.num
            else -> throw PyRt.typeError("ident must be an integer")
        }
        val big = if (i is Long) BigInteger.valueOf(i) else i as BigInteger
        if (big.signum() < 0) throw PyException("OverflowError", "can't convert negative int to unsigned")
        if (big.bitLength() > 64) throw PyException("OverflowError", "int too big to convert")
        return if (big.bitLength() < 64) big.toLong() else big
    }

    /** _thread._excepthook's writing: "Exception in thread NAME:" and the exception, as _PyErr_Display shows it. */
    private fun excepthook(ctx: PyContext, args: Any?): Any? {
        val st = args as? PyTuple
        if (st?.sub?.cls !== EXCEPTHOOK_ARGS) throw PyRt.typeError("_thread.excepthook argument type must be ExceptHookArgs")
        val (excType, excValue, _, thread) = st.items
        // (SystemExit silently ignored)
        if (excType === PyExceptions.type("SystemExit")) return PyNone
        var file = ctx.sysModule.dict[PyStr("stderr")]
        if (file == null || file === PyNone) {
            if (thread === PyNone) return PyNone
            file = PyRt.getAttr(thread, "_stderr")
            if (file === PyNone) return PyNone
        }
        fun write(s: String) {
            PyCalls.callWith(ctx, PyRt.getAttr(file, "write"), arrayOf(PyStr(s)), NO_ARGS, NO_ARGS)
        }
        val name = if (thread === PyNone) null else try {
            PyRt.getAttr(thread, "name")
        } catch (e: PyException) {
            if (!e.matches(PyExceptions.type("AttributeError"))) throw e
            null
        }
        write("Exception in thread " + (if (name != null) PyRt.str(name) else IDENT.toString()) + ":\n")
        write(if (excValue is PyBaseException) PyFrames.format(excValue, ctx.sources)
            else "TypeError: print_exception(): Exception expected for value, ${PyTypes.name(excValue)} found\n")
        PyCalls.callWith(ctx, PyRt.getAttr(file, "flush"), NO_ARGS, NO_ARGS, NO_ARGS)
        return PyNone
    }

    @JvmStatic
    fun fill(ctx: PyContext, d: PyDict) {
        val audit = ctx.sysModule.dict[PyStr("audit")]
        fun audit(event: String, vararg a: Any?) {
            if (audit != null) PyCalls.callWith(ctx, audit, arrayOf(PyStr(event), *a), NO_ARGS, NO_ARGS)
        }
        // (METH_NOARGS, METH_O, METH_VARARGS and METH_VARARGS|METH_KEYWORDS functions)
        fun noArgs(n: String, impl: () -> Any?) {
            val q = "_thread.$n"
            fun check(a: Array<Any?>): Any? {
                if (a.isNotEmpty()) throw PyRt.typeError("$q() takes no arguments (${a.size} given)")
                return impl()
            }
            d[PyStr(n)] = PyBuiltin(n, { _, a -> check(a) }, { _, a, kw -> noKw(q, kw); check(a) })
        }
        fun oneArg(n: String, impl: (Any?) -> Any?) {
            val q = "_thread.$n"
            fun check(a: Array<Any?>): Any? {
                if (a.size != 1) throw PyRt.typeError("$q() takes exactly one argument (${a.size} given)")
                return impl(a[0])
            }
            d[PyStr(n)] = PyBuiltin(n, { _, a -> check(a) }, { _, a, kw -> noKw(q, kw); check(a) })
        }
        fun varArgs(n: String, impl: (Array<Any?>) -> Any?) {
            d[PyStr(n)] = PyBuiltin(n, { _, a -> impl(a) }, { _, a, kw -> noKw("_thread.$n", kw); impl(a) })
        }
        val noStart = { throw runtimeError("can't start new thread") }

        d[PyStr("_ThreadHandle")] = THREAD_HANDLE
        d[PyStr("lock")] = LOCK
        d[PyStr("LockType")] = LOCK
        d[PyStr("RLock")] = RLOCK
        d[PyStr("_local")] = LOCAL
        d[PyStr("error")] = PyExceptions.type("RuntimeError")
        d[PyStr("_ExceptHookArgs")] = EXCEPTHOOK_ARGS
        d[PyStr("TIMEOUT_MAX")] = TIMEOUT_MAX
        d[PyStr("_NAME_MAXLEN")] = NAME_MAXLEN

        for (n in listOf("start_new_thread", "start_new")) varArgs(n) { a ->
            if (a.size < 2) throw PyRt.typeError("start_new_thread expected at least 2 arguments, got ${a.size}")
            if (a.size > 3) throw PyRt.typeError("start_new_thread expected at most 3 arguments, got ${a.size}")
            if (!PyCalls.isCallable(a[0])) throw PyRt.typeError("first arg must be callable")
            if (a[1] !is PyTuple) throw PyRt.typeError("2nd arg must be a tuple")
            if (a.size > 2 && a[2] !is PyDict) throw PyRt.typeError("optional 3rd arg must be a dictionary")
            audit("_thread.start_new_thread", a[0], a[1], a.getOrNull(2) ?: PyNone)
            noStart()
        }
        fun startJoinable(a: Array<Any?>, kw: PyDict?): Any? {
            val args = PyMath.clinic("start_joinable_thread", a, kw, listOf("function", "handle", "daemon"), 0, 1, 3)
            val fn = args[0]
            if (!PyCalls.isCallable(fn)) throw PyRt.typeError("thread function must be callable")
            val hobj = args[1] ?: PyNone
            if (hobj !== PyNone && (hobj !is PyThreadHandle || hobj.type !== THREAD_HANDLE)) throw PyRt.typeError("'handle' must be a _ThreadHandle")
            val daemon = args[2]?.let { PyRt.truthy(it) } ?: true
            audit("_thread.start_joinable_thread", fn, PyBool.of(daemon), hobj)
            val h = hobj as? PyThreadHandle ?: PyThreadHandle(THREAD_HANDLE)
            if (h.state != NOT_STARTED) throw runtimeError("thread already started")
            // (ThreadHandle_start failing: the handle done, as force_done leaves it)
            h.state = DONE
            noStart()
        }
        d[PyStr("start_joinable_thread")] = PyBuiltin("start_joinable_thread", { _, a -> startJoinable(a, null) }, { _, a, kw -> startJoinable(a, kw) })
        noArgs("daemon_threads_allowed") { PyBool.TRUE }
        for (n in listOf("allocate_lock", "allocate")) noArgs(n) { PyLock(LOCK) }
        for (n in listOf("exit_thread", "exit")) noArgs(n) { throw PyException(PyBaseException(PyExceptions.type("SystemExit"))) }
        varArgs("interrupt_main") { a ->
            if (a.size > 1) throw PyRt.typeError("signum() takes at most 1 argument (${a.size} given)")
            val signum = if (a.isEmpty()) 2L else {
                val i = PyRt.indexValue(a[0]) ?: throw PyRt.typeError("'${PyTypes.name(a[0])}' object cannot be interpreted as an integer")
                if (i !is Long) throw PyException("OverflowError", "Python int too large to convert to C long")
                if (i > Int.MAX_VALUE) throw PyException("OverflowError", "signed integer is greater than maximum")
                if (i < Int.MIN_VALUE) throw PyException("OverflowError", "signed integer is less than minimum")
                i
            }
            // (Linux's signals 1 to 64; SIGINT's handler default_int_handler, the others SIG_DFL: ignored)
            if (signum < 1 || signum > 64) throw valueError("signal number out of range")
            if (signum == 2L) throw PyException(PyBaseException(PyExceptions.type("KeyboardInterrupt")))
            PyNone
        }
        noArgs("get_ident") { IDENT }
        noArgs("get_native_id") { IDENT }
        noArgs("_count") { 0L }
        var stackSize = 0L
        varArgs("stack_size") { a ->
            if (a.size > 1) throw PyRt.typeError("stack_size() takes at most 1 argument (${a.size} given)")
            val size = if (a.isEmpty()) 0L else {
                val i = PyRt.indexValue(a[0]) ?: throw PyRt.typeError("'${PyTypes.name(a[0])}' object cannot be interpreted as an integer")
                i as? Long ?: throw PyException("OverflowError", "Python int too large to convert to C ssize_t")
            }
            if (size != 0L && size < MIN_STACK_SIZE) throw valueError("size must be at least $MIN_STACK_SIZE bytes")
            val old = stackSize
            stackSize = size
            old
        }
        oneArg("_excepthook") { v -> excepthook(ctx, v) }
        noArgs("_is_main_interpreter") { PyBool.TRUE }
        // (no thread to wait for)
        noArgs("_shutdown") { PyNone }
        oneArg("_make_thread_handle") { v ->
            val ident = identOf(v)
            PyThreadHandle(THREAD_HANDLE).also {
                it.ident = ident
                it.state = RUNNING
            }
        }
        noArgs("_get_main_thread_ident") { IDENT }
        // (pthread_setname_np's: UTF-8, "replace", cut to 15 bytes; pthread_getname_np's decoded with surrogateescape)
        var threadName = ""
        fun setName(a: Array<Any?>, kw: PyDict?): Any? {
            val v = PyMath.clinic("set_name", a, kw, listOf("name"), 0, 1, 1)[0]
            val name = v as? PyStr ?: throw PyRt.typeError("set_name() argument 'name' must be str, not ${PyTypes.name(v)}")
            val bytes = PyCodecs.encodeUtf8(ctx, name, "replace")
            threadName = PyCodecs.decodeText(ctx, if (bytes.size > NAME_MAXLEN) bytes.copyOf(NAME_MAXLEN.toInt()) else bytes, "utf-8", "surrogateescape").s
            return PyNone
        }
        d[PyStr("set_name")] = PyBuiltin("set_name", { _, a -> setName(a, null) }, { _, a, kw -> setName(a, kw) })
        noArgs("_get_name") { PyStr(threadName) }
    }
}
