package dev.mooner.neonpython

import dev.mooner.neonjs.vm.VmFrame

/**
 * An instance of BaseException or of a class deriving from it (docs/PYTHON_PLATFORM.md §11.2: exceptions are
 * objects): its arguments, its cause and context (`raise ... from`, an exception raised while handling another), its
 * traceback; the fields of the builtin subclasses (StopIteration's value, OSError's errno...) in [fields], which their
 * properties read. Raised, it travels in a [PyException], [thrown].
 */
class PyBaseException(type: PyType) : PyObject(type) {
    @JvmField var args: PyTuple = PyTuple.EMPTY
    @JvmField var cause: Any? = PyNone
    @JvmField var context: Any? = PyNone
    @JvmField var suppressContext = false
    @JvmField var traceback: Any? = PyNone
    @JvmField var fields: HashMap<String, Any?>? = null
    /** The JVM exception carrying it since it was first raised: raised again, it keeps where it was raised first. */
    @JvmField var thrown: PyException? = null

    fun field(n: String): Any? = fields?.get(n) ?: PyNone
    fun setField(n: String, v: Any?) {
        (fields ?: HashMap<String, Any?>().also { fields = it })[n] = v
    }

    override fun toString() = "${type.name}: ${PyExceptions.strOf(this)}"
}

/**
 * Exceptions (docs/PYTHON_PLATFORM.md §11, M3): BaseException's behaviour and its builtin subclasses', the objects of
 * the exceptions the runtime raises (made where Python code first sees them: [objectOf]), and the instructions of
 * `raise`, `try` and `except` ([raise], [reraise], [pushExcInfo], [popExcept], [checkExcMatch], [catchValue]).
 *
 * The exception being handled (what a bare `raise` raises again, the context of an exception raised meanwhile) is
 * [PyContext.handled]: PUSH_EXC_INFO sets it entering a handler and keeps the one before in a register, which
 * POP_EXCEPT puts back leaving it, however it is left. (Generators will keep their own across a yield, M4.)
 */
object PyExceptions {
    private val NO_ARGS = arrayOfNulls<Any?>(0)

    @JvmStatic fun type(n: String): PyType = PyTypes.EXCEPTIONS[n]!!

    @JvmField val BASE_EXCEPTION = type("BaseException")

    /** OSError's subclasses by errno (the network ones Linux's, as on Android: the platform's own on others). */
    private val ERRNO_CLASSES = mapOf(
        1L to "PermissionError", 2L to "FileNotFoundError", 3L to "ProcessLookupError", 4L to "InterruptedError",
        10L to "ChildProcessError", 11L to "BlockingIOError", 13L to "PermissionError", 17L to "FileExistsError",
        20L to "NotADirectoryError", 21L to "IsADirectoryError", 32L to "BrokenPipeError", 103L to "ConnectionAbortedError",
        104L to "ConnectionResetError", 108L to "BrokenPipeError", 110L to "TimeoutError", 111L to "ConnectionRefusedError",
        114L to "BlockingIOError", 115L to "BlockingIOError",
    )

    // ------------------------------------------------------------------ objects

    /** The object of [e]: its own, or one made now from its type's name and message (KeyError's key, say). */
    @JvmStatic
    fun objectOf(e: PyException, ctx: PyContext?): PyBaseException {
        e.exc?.let { return it }
        val o = PyBaseException(PyTypes.EXCEPTIONS[e.typeName] ?: type("SystemError"))
        val msg = e.rawMessage ?: ""
        o.args = PyTuple(e.args ?: if (msg.isEmpty()) NO_ARGS else arrayOf(PyStr(msg)))
        e.attrs?.let { a -> for ((k, v) in a) o.setField(k, v) }
        initFields(o)
        o.thrown = e
        e.exc = o
        // (raised while another was handled: that one is its context, as CPython sets it raising)
        val h = ctx?.handled
        if (h != null && h !== o) setContext(o, h)
        return o
    }

    /** The fields a builtin subclass's __init__ sets from the arguments. */
    private fun initFields(o: PyBaseException) {
        val a = o.args.items
        val t = o.type
        when {
            t.isSubtype(type("StopIteration")) -> o.setField("value", if (a.isEmpty()) PyNone else a[0])
            t.isSubtype(type("SystemExit")) -> o.setField("code", when (a.size) { 0 -> PyNone; 1 -> a[0]; else -> o.args })
            t.isSubtype(type("OSError")) -> if (a.size in 2..5) {
                o.setField("errno", a[0])
                o.setField("strerror", a[1])
                if (a.size > 2) o.setField("filename", a[2])
            }
        }
    }

    /** exc.__context__ = ctx, breaking a cycle it would close (as CPython's _PyErr_SetObject). */
    private fun setContext(exc: PyBaseException, ctx: PyBaseException) {
        var o: PyBaseException = ctx
        var steps = 0
        while (steps++ < 10000) {
            val c = o.context as? PyBaseException ?: break
            if (c === exc) {
                o.context = PyNone
                break
            }
            o = c
        }
        exc.context = ctx
    }

    /** str(exc), as messages give it (the class's __str__, a user's too); never raising. */
    @JvmStatic
    fun strOf(exc: PyBaseException): String = try {
        PyDunder.str(exc)
    } catch (e: PyException) {
        "<exception str() failed>"
    } catch (e: IllegalStateException) {
        // (no Python code running: BaseException's own)
        baseStr(exc)
    }

    private fun baseStr(o: PyBaseException): String = when (o.args.items.size) {
        0 -> ""
        1 -> PyRt.str(o.args.items[0])
        else -> PyRt.repr(o.args)
    }

    // ------------------------------------------------------------------ the instructions

    /** The exception [t] is to Python code (its object; RecursionError for the JVM's stack), or null: it is not one. */
    @JvmStatic
    fun caught(t: Throwable, f: PyFrame): PyBaseException? {
        val e = when (t) {
            is PyException -> t
            is StackOverflowError -> PyException("RecursionError", "maximum recursion depth exceeded")
            // (a sandbox's termination, the host's errors: never Python's, no handler sees them)
            else -> return null
        }
        // where it was raised: the first frame it reaches, at the pc that raised it
        if (e.line < 0) {
            e.line = (f.code.dialectData as PyCodeInfo).lineAt(f.code, f.pc)
            e.function = f.code.name
        }
        return objectOf(e, f.ctx)
    }

    /** CATCH_VALUE of compiled code: the exception object caught, or [t] thrown on where Python does not catch it. */
    @JvmStatic
    fun catchValue(t: Throwable, f: VmFrame): Any? = caught(t, f as PyFrame) ?: throw t

    /** An exception's instance: [v] itself, or [v] (a class) called; else the TypeError [msg]. */
    private fun instance(ctx: PyContext, v: Any?, msg: String): PyBaseException {
        if (v is PyBaseException) return v
        if (v is PyType && v.isSubtype(BASE_EXCEPTION)) {
            val o = PyClasses.instantiate(ctx, v, NO_ARGS, NO_ARGS, NO_ARGS)
            return o as? PyBaseException
                ?: throw PyRt.typeError("calling ${PyRt.repr(v)} should have returned an instance of BaseException, not ${PyTypes.name(o)}")
        }
        throw PyRt.typeError(msg)
    }

    /** The JVM exception raising [o] (again: as it was first raised). */
    private fun thrown(o: PyBaseException): PyException = o.thrown ?: PyException(o).also { o.thrown = it }

    /** RAISE: `raise` (no [args]: the exception handled, again), `raise exc`, `raise exc from cause`; what to throw. */
    @JvmStatic
    fun raise(f: VmFrame, args: Array<Any?>): Any? {
        val ctx = (f as PyFrame).ctx
        if (args.isEmpty()) {
            val h = ctx.handled ?: return PyException("RuntimeError", "No active exception to reraise")
            return thrown(h)
        }
        val exc = try {
            instance(ctx, args[0], "exceptions must derive from BaseException")
        } catch (e: PyException) {
            return e
        }
        if (args.size > 1) {
            val c = args[1]
            exc.cause = if (c === PyNone) PyNone else try {
                instance(ctx, c, "exception causes must derive from BaseException")
            } catch (e: PyException) {
                return e
            }
            exc.suppressContext = true
        }
        val h = ctx.handled
        if (h != null && h !== exc) setContext(exc, h)
        // (raised anew: from here)
        return PyException(exc).also { exc.thrown = it }
    }

    /** RERAISE: the exception object [v] raised again, as it was (its context and line kept). */
    @JvmStatic
    fun reraise(v: Any?): Any? = thrown(v as PyBaseException)

    /** PUSH_EXC_INFO: [exc] becomes the exception handled; the one before (or None) to put back after. */
    @JvmStatic
    fun pushExcInfo(f: VmFrame, exc: Any?): Any? {
        val ctx = (f as PyFrame).ctx
        val prev = ctx.handled ?: PyNone
        ctx.handled = exc as PyBaseException
        return prev
    }

    /** POP_EXCEPT: the exception handled before the handler left is handled again ([prev], None for none). */
    @JvmStatic
    fun popExcept(f: VmFrame, prev: Any?) {
        (f as PyFrame).ctx.handled = prev as? PyBaseException
    }

    /** CHECK_EXC_MATCH: whether [exc] is an instance of [type] (a class or a tuple of them, which must be exceptions'). */
    @JvmStatic
    fun checkExcMatch(exc: Any?, type: Any?): Any? {
        fun valid(t: Any?) = t is PyType && t.isSubtype(BASE_EXCEPTION)
        val ok = if (type is PyTuple) type.items.all { valid(it) } else valid(type)
        if (!ok) throw PyRt.typeError("catching classes that do not inherit from BaseException is not allowed")
        return PyBool.of(PyTypes.isInstance(exc, type))
    }

    /** LOAD_SPECIAL: [mgr]'s __enter__ (k 0) or __exit__ (k 1), from its type (not its own attributes), bound to it. */
    @JvmStatic
    fun loadSpecial(mgr: Any?, k: Int): Any? {
        val n = if (k == 0) "__enter__" else "__exit__"
        val t = PyTypes.typeOf(mgr)
        val m = t.lookup(n) ?: throw PyRt.typeError("'${t.name}' object does not support the context manager protocol (missed $n method)")
        return PyClasses.descrGet(null, m, mgr, t)
    }

    /** WITH_EXCEPT: [exit] (a with's bound __exit__) called for [exc]: its type, it, its traceback; whether to suppress it. */
    @JvmStatic
    fun withExcept(f: VmFrame, exit: Any?, exc: Any?): Any? {
        val e = exc as PyBaseException
        return PyCalls.callWith((f as PyFrame).ctx, exit, arrayOf(e.type, e, e.traceback), NO_ARGS, NO_ARGS)
    }

    // ------------------------------------------------------------------ the classes' behaviour

    /** BaseException's methods and attributes and its builtin subclasses' (once: the types are the runtime's). */
    @JvmStatic
    fun define() {
        val b = BASE_EXCEPTION
        if (b.dict.containsKey(PyStr("__new__"))) return
        fun self(a: Array<Any?>, n: String): PyBaseException =
            a.getOrNull(0) as? PyBaseException ?: throw PyRt.typeError("descriptor '$n' requires a 'BaseException' object but received '${PyTypes.name(a.getOrNull(0))}'")
        fun method(t: PyType, n: String, impl: (Array<Any?>) -> Any?) = t.def(n, PyBuiltin(n) { _, a -> impl(a) })
        fun prop(t: PyType, n: String, get: (PyBaseException) -> Any?, set: ((PyBaseException, Any?) -> Unit)?) =
            t.def(n, PyProperty(PyBuiltin(n) { _, a -> get(self(a, n)) },
                set?.let { s -> PyBuiltin(n) { _, a -> s(self(a, n), a[1]); PyNone } }, null, null).also { it.name = n })

        // __new__ keeps the arguments (an __init__ of a subclass may not pass them on), __init__ sets them again
        fun newImpl(a: Array<Any?>): Any? {
            val cls = a.getOrNull(0) as? PyType ?: throw PyRt.typeError("BaseException.__new__(X): X is not a type object (${PyTypes.name(a.getOrNull(0))})")
            if (!cls.isSubtype(b)) throw PyRt.typeError("BaseException.__new__(${cls.name}): ${cls.name} is not a subtype of BaseException")
            // (OSError(errno, strerror) itself: the subclass of that errno)
            val errno = a.getOrNull(1) as? Long
            val sub = if (cls === type("OSError") && a.size >= 3 && errno != null) ERRNO_CLASSES[errno]?.let { type(it) } else null
            val o = PyBaseException(sub ?: cls)
            o.args = if (a.size > 1) PyTuple(a.copyOfRange(1, a.size)) else PyTuple.EMPTY
            initFields(o)
            return o
        }
        b.def("__new__", PyBuiltin("__new__", { _, a -> newImpl(a) }, { _, a, _ -> newImpl(a) }))
        fun initImpl(a: Array<Any?>, kw: PyDict?): Any? {
            val o = self(a, "__init__")
            if (kw != null && kw.size > 0) throw PyRt.typeError("${o.type.name}() takes no keyword arguments")
            o.args = if (a.size > 1) PyTuple(a.copyOfRange(1, a.size)) else PyTuple.EMPTY
            initFields(o)
            return PyNone
        }
        b.def("__init__", PyBuiltin("__init__", { _, a -> initImpl(a, null) }, { _, a, kw -> initImpl(a, kw) }))
        method(b, "__str__") { a -> PyStr(baseStr(self(a, "__str__"))) }
        method(b, "__repr__") { a ->
            val o = self(a, "__repr__")
            val items = o.args.items
            PyStr(o.type.name + if (items.size == 1) "(" + PyRt.repr(items[0]) + ")" else PyRt.repr(o.args))
        }
        method(b, "with_traceback") { a ->
            val o = self(a, "with_traceback")
            o.traceback = a.getOrNull(1) ?: PyNone
            o
        }
        method(b, "add_note") { a ->
            val o = self(a, "add_note")
            val note = a.getOrNull(1) as? PyStr ?: throw PyRt.typeError("add_note() argument must be str, not ${PyTypes.name(a.getOrNull(1))}")
            val d = o.dict ?: PyDict().also { o.dict = it }
            val notes = d[PyStr("__notes__")] ?: PyList().also { d[PyStr("__notes__")] = it }
            (notes as? PyList ?: throw PyRt.typeError("Cannot add note: __notes__ is not a list")).items.add(note)
            PyNone
        }
        prop(b, "args", { it.args }) { o, v -> o.args = if (v is PyTuple && v.sub == null) v else PyTuple(PyBuiltins.toList(v).toTypedArray()) }
        prop(b, "__cause__", { it.cause }) { o, v ->
            if (v !== PyNone && v !is PyBaseException) throw PyRt.typeError("exception cause must be None or derive from BaseException")
            o.cause = v
            o.suppressContext = true
        }
        prop(b, "__context__", { it.context }) { o, v ->
            if (v !== PyNone && v !is PyBaseException) throw PyRt.typeError("exception context must be None or derive from BaseException")
            o.context = v
        }
        prop(b, "__suppress_context__", { PyBool.of(it.suppressContext) }) { o, v -> o.suppressContext = PyRt.truthy(v) }
        prop(b, "__traceback__", { it.traceback }) { o, v -> o.traceback = v }

        // the builtin subclasses' fields and messages
        for ((t, names) in listOf(
            type("StopIteration") to listOf("value"), type("StopAsyncIteration") to listOf("value"), type("SystemExit") to listOf("code"),
            type("OSError") to listOf("errno", "strerror", "filename"), type("NameError") to listOf("name"),
            type("AttributeError") to listOf("name", "obj"), type("ImportError") to listOf("name", "path"),
        )) for (n in names) prop(t, n, { it.field(n) }) { o, v -> o.setField(n, v) }
        method(type("KeyError"), "__str__") { a ->
            val o = self(a, "__str__")
            PyStr(if (o.args.items.size == 1) PyRt.repr(o.args.items[0]) else baseStr(o))
        }
        method(type("OSError"), "__str__") { a ->
            val o = self(a, "__str__")
            val errno = o.field("errno")
            PyStr(if (errno !== PyNone) {
                val fn = o.field("filename")
                "[Errno ${PyRt.str(errno)}] ${PyRt.str(o.field("strerror"))}" + if (fn !== PyNone) ": ${PyRt.repr(fn)}" else ""
            } else baseStr(o))
        }
    }
}
