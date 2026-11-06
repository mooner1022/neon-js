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
        o.traceback = e.tb ?: PyNone
        e.exc = o
        // (raised while another was handled: that one is its context, as CPython sets it raising)
        val h = ctx?.currentHandled()
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
            t.isSubtype(type("SyntaxError")) -> {
                if (a.isNotEmpty()) o.setField("msg", a[0])
                val info = (a.getOrNull(1) as? PyTuple)?.items
                if (a.size == 2 && info != null) {
                    for ((i, n) in listOf("filename", "lineno", "offset", "text", "end_lineno", "end_offset").withIndex()) {
                        if (i < info.size) o.setField(n, info[i])
                    }
                }
            }
            t.isSubtype(type("OSError")) -> if (a.size in 2..5) {
                o.setField("errno", a[0])
                o.setField("strerror", a[1])
                if (a.size > 2) o.setField("filename", a[2])
                if (a.size > 4) o.setField("filename2", a[4])
            }
        }
    }

    /** [o] raised by the runtime (PyErr_SetObject): the exception handled its context, the JVM exception that raises it. */
    @JvmStatic
    fun raiseObject(o: PyBaseException, ctx: PyContext?): PyException {
        val h = ctx?.currentHandled()
        if (h != null && h !== o) setContext(o, h)
        return PyException(o).also { o.thrown = it }
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
        // (this frame's traceback entry, where a handler takes it: the interpreter's or compiled code's)
        PyFrames.addTraceback(e, f)
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
            val h = ctx.currentHandled() ?: return PyException("RuntimeError", "No active exception to reraise")
            // (raised again as it was: no traceback entry in this frame, CPython's RAISE_VARARGS 0)
            return thrown(h).also { it.tbFrame = f }
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
        val h = ctx.currentHandled()
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
        val n = SPECIAL[k]
        val t = PyTypes.typeOf(mgr)
        val m = t.lookup(n) ?: throw PyRt.typeError(specialMissing(t, k))
        return PyClasses.descrGet(null, m, mgr, t)
    }

    /** LOAD_SPECIAL's names: a with's, an async with's. */
    private val SPECIAL = arrayOf("__enter__", "__exit__", "__aenter__", "__aexit__")

    /** 3.14's message where [t] has no SPECIAL[k], with its hint where [t] has the other protocol's two. */
    private fun specialMissing(t: PyType, k: Int): String {
        val async = k >= 2
        val msg = "'${PyTypes.fullName(t)}' object does not support the ${if (async) "asynchronous " else ""}context manager protocol (missed ${SPECIAL[k]} method)"
        val other = if (async) 0 else 2
        if (t.lookup(SPECIAL[other]) == null || t.lookup(SPECIAL[other + 1]) == null) return msg
        return msg + if (async) " but it supports the context manager protocol. Did you mean to use 'with'?"
            else " but it supports the asynchronous context manager protocol. Did you mean to use 'async with'?"
    }

    /** WITH_EXCEPT: [exit] (a with's bound __exit__) called for [exc]: its type, it, its traceback; whether to suppress it. */
    @JvmStatic
    fun withExcept(f: VmFrame, exit: Any?, exc: Any?): Any? {
        val e = exc as PyBaseException
        return PyCalls.callWith((f as PyFrame).ctx, exit, arrayOf(e.type, e, e.traceback), NO_ARGS, NO_ARGS)
    }

    // ------------------------------------------------------------------ exception groups (PEP 654)

    @JvmField val BASE_GROUP = type("BaseExceptionGroup")
    private val EXCEPTION = type("Exception")

    @JvmStatic fun isGroup(v: Any?): Boolean = v is PyBaseException && v.type.isSubtype(BASE_GROUP)

    /** A group's exceptions (its `exceptions`, a tuple). */
    private fun members(g: PyBaseException): Array<Any?> = (g.field("exceptions") as PyTuple).items

    /**
     * BaseExceptionGroup(message, exceptions) of class [cls]: BaseExceptionGroup itself gives an ExceptionGroup where
     * every exception is an Exception's; an ExceptionGroup holds Exceptions only. Its args are the two as given.
     */
    @JvmStatic
    fun newGroup(cls: PyType, message: Any?, excs: Any?): PyBaseException {
        if (message !is PyStr) throw PyRt.typeError("BaseExceptionGroup.__new__() argument 1 must be str, not ${PyTypes.name(message)}")
        // (a sequence: a list or tuple, or what has __getitem__, not a mapping; its repr now, as it is now, for the group's)
        var reprNow: String? = null
        val items = when (excs) {
            is PyList -> excs.items.toTypedArray()
            is PyTuple -> excs.items
            else -> {
                val t = PyTypes.typeOf(excs)
                if (!PyRt.special(excs) || t.lookup("__getitem__") == null || excs is PyDict) throw PyRt.typeError("second argument (exceptions) must be a sequence")
                reprNow = PyRt.repr(excs)
                PyBuiltins.toList(excs).toTypedArray()
            }
        }
        if (items.isEmpty()) throw PyException("ValueError", "second argument (exceptions) must be a non-empty sequence")
        for ((i, e) in items.withIndex()) if (e !is PyBaseException) throw PyException("ValueError", "Item $i of second argument (exceptions) is not an exception")
        val all = items.all { (it as PyBaseException).type.isSubtype(EXCEPTION) }
        val c = if (cls === BASE_GROUP && all) type("ExceptionGroup") else cls
        if (c.isSubtype(EXCEPTION) && !all) throw PyRt.typeError("Cannot nest BaseExceptions in ${if (c === type("ExceptionGroup")) "an ExceptionGroup" else "'${c.name}'"}")
        val g = PyBaseException(c)
        g.args = PyTuple(arrayOf(message, excs))
        g.setField("message", message)
        g.setField("exceptions", PyTuple(items.copyOf()))
        if (reprNow != null) g.setField("__excs_repr__", PyStr(reprNow))
        return g
    }

    /** eg.derive(excs) (a class's own too), with eg's cause, context, traceback and notes. */
    private fun derive(eg: PyBaseException, excs: List<Any?>): PyBaseException {
        val d = PyCalls.callWith(PyContext.current(), PyRt.getAttr(eg, "derive"), arrayOf(PyList(ArrayList(excs))), NO_ARGS, NO_ARGS)
            as? PyBaseException ?: throw PyRt.typeError("derive must return an instance of BaseExceptionGroup")
        d.cause = eg.cause
        d.context = eg.context
        d.suppressContext = eg.suppressContext
        d.traceback = eg.traceback
        // (its notes a copy of the group's, where they are a sequence)
        eg.dict?.get(PyStr("__notes__"))?.let { n ->
            if (n is PyList || n is PyTuple || PyRt.special(n) && PyTypes.typeOf(n).lookup("__getitem__") != null) {
                (d.dict ?: PyDict().also { d.dict = it })[PyStr("__notes__")] = PyList(ArrayList(PyBuiltins.toList(n)))
            }
        }
        // (where it was raised: the group's)
        eg.thrown?.let { t -> d.thrown = PyException(d).also { it.line = t.line; it.function = t.function } }
        return d
    }

    /**
     * eg.split(matches): the part of [eg] whose exceptions [matches] (a group matching whole: itself), and the rest,
     * each a group of eg's shape (derive) or null.
     */
    private fun split(eg: Any?, depth: Int = 0, matches: (Any?) -> Boolean): Pair<Any?, Any?> {
        if (matches(eg)) return eg to null
        if (!isGroup(eg)) return null to eg
        // (CPython's Py_EnterRecursiveCall(" in split"))
        if (depth > PyContext.current().agent.maxDepth) throw PyException("RecursionError", "maximum recursion depth exceeded in split")
        val g = eg as PyBaseException
        val m = ArrayList<Any?>()
        val r = ArrayList<Any?>()
        for (e in members(g)) {
            val (em, er) = split(e, depth + 1, matches)
            if (em != null) m.add(em)
            if (er != null) r.add(er)
        }
        return (if (m.isEmpty()) null else derive(g, m)) to (if (r.isEmpty()) null else derive(g, r))
    }

    /** What split and subgroup take: an exception class, a tuple of them, or a predicate. */
    private fun matcher(cond: Any?): (Any?) -> Boolean {
        fun cls(t: Any?) = t is PyType && t.isSubtype(BASE_EXCEPTION)
        if (cls(cond) || cond is PyTuple && cond.items.all { cls(it) }) return { PyTypes.isInstance(it, cond) }
        if (cond is PyFunction || cond is PyBuiltin || cond is PyMethod || cond is PyBoundMethod || PyClasses.classOf(cond)?.lookup("__call__") != null) {
            return { PyRt.truthy(PyCalls.callWith(PyContext.current(), cond, arrayOf(it), NO_ARGS, NO_ARGS)) }
        }
        throw PyRt.typeError("expected an exception type, a tuple of exception types, or a callable (other than a class)")
    }

    /** The leaves of [e] (itself, or a group's exceptions', all the way down). */
    private fun leaves(e: Any?, out: MutableSet<Any?>) {
        if (isGroup(e)) for (x in members(e as PyBaseException)) leaves(x, out) else out.add(e)
    }

    /**
     * An except* statement's state (EXC_STAR_BEGIN to EXC_STAR_END): the exception raised in its body, what the
     * clauses have not matched yet, the match the running clause handles, what its clauses raised (anew) and raised
     * again (the match itself).
     */
    class ExcStar(@JvmField val orig: PyBaseException) {
        @JvmField var rest: Any? = orig
        @JvmField var current: Any? = null
        @JvmField val raised = ArrayList<PyBaseException>()
        @JvmField val reraised = ArrayList<PyBaseException>()
    }

    /** EXC_STAR_BEGIN: the state of the clauses that handle [exc]. */
    @JvmStatic fun excStarBegin(exc: Any?): Any? = ExcStar(exc as PyBaseException)

    /**
     * EXC_STAR_MATCH: the part of what is left that [type] matches, taken from it (a naked exception that matches in a
     * group of its own), or None.
     */
    @JvmStatic
    fun excStarMatch(state: Any?, type: Any?): Any? {
        val st = state as ExcStar
        fun valid(t: Any?) = t is PyType && t.isSubtype(BASE_EXCEPTION)
        val types = if (type is PyTuple) type.items.asList() else listOf(type)
        if (!types.all { valid(it) }) throw PyRt.typeError("catching classes that do not inherit from BaseException is not allowed")
        if (types.any { (it as PyType).isSubtype(BASE_GROUP) }) throw PyRt.typeError("catching ExceptionGroup with except* is not allowed. Use except instead.")
        val rest = st.rest ?: return PyNone
        if (!isGroup(rest)) {
            if (!PyTypes.isInstance(rest, type)) return PyNone
            st.rest = null
            val e = rest as PyBaseException
            val g = newGroup(BASE_GROUP, PyStr.EMPTY, PyTuple(arrayOf(e)))
            g.traceback = e.traceback
            st.current = g
            return g
        }
        // (a subclass's own split, as CPython calls it: a tuple of at least two)
        val own = PyClasses.classOf(rest)?.lookup("split")?.takeIf { it !is PyBuiltin }
        val (m, r) = if (own == null) split(rest) { PyTypes.isInstance(it, type) } else {
            val pair = PyCalls.callWith(PyContext.current(), PyClasses.getAttr(rest, "split"), arrayOf(type), NO_ARGS, NO_ARGS)
            val what = PyTypes.name(rest)
            if (pair !is PyTuple || PyTypes.typeOf(pair) !== PyTypes.TUPLE) throw PyRt.typeError("$what.split must return a tuple, not ${PyTypes.name(pair)}")
            if (pair.items.size < 2) throw PyRt.typeError("$what.split must return a 2-tuple, got tuple of size ${pair.items.size}")
            pair.items[0].takeIf { it !== PyNone } to pair.items[1].takeIf { it !== PyNone }
        }
        st.rest = r
        st.current = m
        return m ?: PyNone
    }

    /** EXC_STAR_RAISED: [exc] left a clause's body: raised again (the clause's match) or anew. */
    @JvmStatic
    fun excStarRaised(state: Any?, exc: Any?) {
        val st = state as ExcStar
        val e = exc as PyBaseException
        if (e === st.current) st.reraised.add(e) else st.raised.add(e)
    }

    /**
     * EXC_STAR_END: what the statement raises, or None: what the clauses raised anew, then what is left and what they
     * raised again, in the shape of the exception raised (one alone as it is, more in an ExceptionGroup).
     */
    @JvmStatic
    fun excStarEnd(state: Any?): Any? {
        val st = state as ExcStar
        val orig = st.orig
        if (!isGroup(orig)) {
            // (a naked exception: one clause at most took it)
            return st.raised.firstOrNull() ?: st.reraised.firstOrNull() ?: st.rest ?: PyNone
        }
        val keep = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any?, Boolean>())
        st.rest?.let { leaves(it, keep) }
        for (r in st.reraised) leaves(r, keep)
        val again = if (keep.isEmpty()) null else split(orig) { !isGroup(it) && it in keep }.first
        if (st.raised.isEmpty()) return again ?: PyNone
        val all = ArrayList<Any?>(st.raised)
        if (again != null) all.add(again)
        return if (all.size == 1) all[0] else newGroup(BASE_GROUP, PyStr.EMPTY, PyList(all))
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
            // (ImportError's keyword-only name, path and name_from: ImportError_init's)
            val importError = o.type.isSubtype(type("ImportError"))
            if (kw != null && kw.size > 0 && !importError) throw PyRt.typeError("${o.type.name}() takes no keyword arguments")
            o.args = if (a.size > 1) PyTuple(a.copyOfRange(1, a.size)) else PyTuple.EMPTY
            initFields(o)
            if (importError) kw?.map?.forEach { (k, v) ->
                when (val n = (k.v as PyStr).s) {
                    "name", "path", "name_from" -> o.setField(n, v)
                    else -> throw PyRt.typeError("'$n' is an invalid keyword argument for ImportError()")
                }
            }
            return PyNone
        }
        b.def("__init__", PyBuiltin("__init__", { _, a -> initImpl(a, null) }, { _, a, kw -> initImpl(a, kw) }))
        method(b, "__str__") { a -> PyStr(baseStr(self(a, "__str__"))) }
        method(b, "__repr__") { a ->
            val o = self(a, "__repr__")
            val items = o.args.items
            // (a group made of another sequence: that sequence's repr as it was then)
            (o.field("__excs_repr__") as? PyStr)?.let { r -> if (items.size == 2) return@method PyStr(o.type.name + "(" + PyRt.repr(items[0]) + ", " + r.s + ")") }
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
            type("OSError") to listOf("errno", "strerror", "filename", "filename2"), type("NameError") to listOf("name"),
            type("AttributeError") to listOf("name", "obj"), type("ImportError") to listOf("name", "path"),
            type("SyntaxError") to listOf("msg", "filename", "lineno", "offset", "text", "end_lineno", "end_offset", "print_file_and_line"),
        )) for (n in names) prop(t, n, { it.field(n) }) { o, v -> o.setField(n, v) }
        // exception groups
        val bg = BASE_GROUP
        fun groupNew(a: Array<Any?>): Any? {
            val cls = a.getOrNull(0) as? PyType ?: throw PyRt.typeError("BaseExceptionGroup.__new__(X): X is not a type object")
            if (a.size != 3) throw PyRt.typeError("BaseExceptionGroup.__new__() takes exactly 2 arguments (${a.size - 1} given)")
            return newGroup(cls, a[1], a[2])
        }
        bg.def("__new__", PyBuiltin("__new__", { _, a -> groupNew(a) }, { _, a, _ -> groupNew(a) }))
        // (__init__ takes what __new__ took; the arguments stay __new__'s)
        bg.def("__init__", PyBuiltin("__init__", { _, _ -> PyNone }, { _, _, _ -> PyNone }))
        method(bg, "__str__") { a ->
            val g = self(a, "__str__")
            val n = members(g).size
            PyStr("${(g.field("message") as PyStr).s} ($n sub-exception${if (n == 1) "" else "s"})")
        }
        prop(bg, "message", { it.field("message") }, null)
        prop(bg, "exceptions", { it.field("exceptions") }, null)
        method(bg, "derive") { a -> newGroup(BASE_GROUP, self(a, "derive").field("message"), a.getOrNull(1)) }
        method(bg, "split") { a ->
            val (m, r) = split(self(a, "split"), matches = matcher(a.getOrNull(1)))
            PyTuple(arrayOf(m ?: PyNone, r ?: PyNone))
        }
        method(bg, "subgroup") { a -> split(self(a, "subgroup"), matches = matcher(a.getOrNull(1))).first ?: PyNone }
        method(type("KeyError"), "__str__") { a ->
            val o = self(a, "__str__")
            PyStr(if (o.args.items.size == 1) PyRt.repr(o.args.items[0]) else baseStr(o))
        }
        // msg (file, line N): the file's last part, either where it has none
        method(type("SyntaxError"), "__str__") { a ->
            val o = self(a, "__str__")
            val fn = (o.field("filename") as? PyStr)?.s?.substringAfterLast('/')
            val ln = o.field("lineno") as? Long
            val msg = PyRt.str(o.field("msg"))
            PyStr(when {
                fn != null && ln != null -> "$msg ($fn, line $ln)"
                fn != null -> "$msg ($fn)"
                ln != null -> "$msg (line $ln)"
                else -> msg
            })
        }
        method(type("OSError"), "__str__") { a ->
            val o = self(a, "__str__")
            val errno = o.field("errno")
            PyStr(if (errno !== PyNone) {
                val fn = o.field("filename")
                val fn2 = o.field("filename2")
                "[Errno ${PyRt.str(errno)}] ${PyRt.str(o.field("strerror"))}" +
                    if (fn !== PyNone && fn2 !== PyNone) ": ${PyRt.repr(fn)} -> ${PyRt.repr(fn2)}" else if (fn !== PyNone) ": ${PyRt.repr(fn)}" else ""
            } else baseStr(o))
        }
        PyCodecs.defineExceptions()
    }
}
