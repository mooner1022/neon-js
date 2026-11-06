package dev.mooner.neonpython

/** A contextvars.ContextVar: its name and default (null: none). */
class PyContextVar(type: PyType, @JvmField val name: PyStr, @JvmField val default: Any?) : PyObject(type)

/** A contextvars.Token: what [ContextVar.set] changed, to [reset] it. */
class PyContextToken(type: PyType, @JvmField val ctx: PyCtx, @JvmField val variable: PyContextVar, @JvmField val old: Any?) : PyObject(type) {
    @JvmField var used = false
}

/** A contextvars.Context: its variables' values ([vars], replaced, never changed, as CPython's HAMT), entered or not. */
class PyCtx(type: PyType) : PyObject(type) {
    @JvmField var vars: Map<PyContextVar, Any?> = emptyMap()
    @JvmField var entered = false
    @JvmField var prev: PyCtx? = null
}

/**
 * `_contextvars` (docs/PYTHON_PLATFORM.md §12, S6's basis; `_py_warnings` imports it): each interpreter's current
 * Context ([PyContext.currentCtx]), ContextVar.get/set/reset, Context.run, copy_context, CPython 3.14's messages.
 */
object PyContextVars {
    private val NO_ARGS = arrayOfNulls<Any?>(0)

    private fun type(name: String): PyType = PyTypes.newBuiltin(name, subclassable = false).also {
        it.module = "_contextvars"
        it.tpName = "_contextvars.$name"
        it.dispatchBuiltins = true
    }

    @JvmField val CONTEXTVAR = type("ContextVar")
    @JvmField val TOKEN = type("Token")
    @JvmField val CONTEXT = type("Context")

    /** Token.MISSING: the old value of a variable that had none. */
    @JvmField val MISSING: PyObject = PyObject(PyTypes.newBuiltin("Token.MISSING", subclassable = false).also {
        it.module = "_contextvars"
        it.def("__repr__", PyBuiltin("__repr__") { _, _ -> PyStr("<Token.MISSING>") })
        it.dispatchBuiltins = true
    })

    private fun addr(o: Any) = "0x" + Integer.toHexString(System.identityHashCode(o)).padStart(16, '0').uppercase()

    private fun varRepr(v: PyContextVar): String =
        "<ContextVar name=${PyRt.repr(v.name)}${if (v.default != null) " default=${PyRt.repr(v.default)}" else ""} at ${addr(v)}>"

    private fun tokenRepr(t: PyContextToken): String = "<Token${if (t.used) " used" else ""} var=${varRepr(t.variable)} at ${addr(t)}>"

    /** The interpreter's current Context (an empty one made the first time). */
    @JvmStatic
    fun current(ctx: PyContext): PyCtx = ctx.currentCtx ?: PyCtx(CONTEXT).also { ctx.currentCtx = it }

    private fun cv(v: Any?): PyContextVar = v as? PyContextVar ?: throw PyRt.typeError("a ContextVar key was expected, got ${PyRt.repr(v)}")

    @JvmStatic
    fun get(ctx: PyContext, v: PyContextVar, default: Any?): Any? {
        val c = current(ctx)
        if (c.vars.containsKey(v)) return c.vars[v]
        if (default != null) return default
        if (v.default != null) return v.default
        throw PyException("LookupError", varRepr(v)).also { it.args = arrayOf(v) }
    }

    @JvmStatic
    fun set(ctx: PyContext, v: PyContextVar, value: Any?): PyContextToken {
        val c = current(ctx)
        val old = if (c.vars.containsKey(v)) c.vars[v] else MISSING
        c.vars = LinkedHashMap(c.vars).also { it[v] = value }
        return PyContextToken(TOKEN, c, v, old)
    }

    @JvmStatic
    fun reset(ctx: PyContext, v: PyContextVar, t: Any?) {
        val tok = t as? PyContextToken ?: throw PyRt.typeError("expected an instance of Token, got ${PyRt.repr(t)}")
        if (tok.used) throw PyException("RuntimeError", "${tokenRepr(tok)} has already been used once")
        if (tok.variable !== v) throw PyException("ValueError", "${tokenRepr(tok)} was created by a different ContextVar")
        val c = current(ctx)
        if (tok.ctx !== c) throw PyException("ValueError", "${tokenRepr(tok)} was created in a different Context")
        tok.used = true
        c.vars = LinkedHashMap(c.vars).also { if (tok.old === MISSING) it.remove(v) else it[v] = tok.old }
    }

    /** Context.run(callable, *args, **kwargs): [c] the current context while it runs. */
    @JvmStatic
    fun run(ctx: PyContext, c: PyCtx, fn: Any?, args: Array<Any?>, kwNames: Array<Any?>, kwValues: Array<Any?>): Any? {
        if (c.entered) throw PyException("RuntimeError", "cannot enter context: <_contextvars.Context object at ${addr(c)}> is already entered")
        c.entered = true
        c.prev = ctx.currentCtx
        ctx.currentCtx = c
        try {
            return PyCalls.callWith(ctx, fn, args, kwNames, kwValues)
        } finally {
            ctx.currentCtx = c.prev
            c.prev = null
            c.entered = false
        }
    }

    @JvmStatic
    fun copy(c: PyCtx): PyCtx = PyCtx(CONTEXT).also { it.vars = c.vars }

    init {
        val v = CONTEXTVAR
        v.newImpl = { _, _, a, kw ->
            if (a.size != 1) throw PyRt.typeError("ContextVar() takes exactly 1 positional argument (${a.size} given)")
            val name = a[0] as? PyStr ?: throw PyRt.typeError("context variable name must be a str")
            kw?.map?.keys?.forEach { k -> if ((k.v as PyStr).s != "default") throw PyRt.typeError("ContextVar() got an unexpected keyword argument '${(k.v as PyStr).s}'") }
            PyContextVar(CONTEXTVAR, name, kw?.get(PyStr("default")))
        }
        fun self(a: Array<Any?>): PyContextVar = a[0] as PyContextVar
        v.def("name", PyProperty(PyBuiltin("name") { _, a -> self(a).name }, null, null, null).also { it.name = "name" })
        v.def("get", PyBuiltin("get") { ctx, a -> get(ctx, self(a), a.getOrNull(1)) })
        v.def("set", PyBuiltin("set") { ctx, a ->
            if (a.size != 2) throw PyRt.typeError("ContextVar.set() takes exactly one argument (${a.size - 1} given)")
            set(ctx, self(a), a[1])
        })
        v.def("reset", PyBuiltin("reset") { ctx, a -> reset(ctx, self(a), a.getOrNull(1)); PyNone })
        v.def("__repr__", PyBuiltin("__repr__") { _, a -> PyStr(varRepr(self(a))) })
        v.def("__hash__", PyBuiltin("__hash__") { _, a -> System.identityHashCode(a[0]).toLong() })
        v.def("__class_getitem__", PyClassMethod(PyBuiltin("__class_getitem__") { _, a -> PyGenericAlias(a[0], PyTyping.argsOf(a[1])) }))

        val t = TOKEN
        t.newImpl = { _, _, _, _ -> throw PyException("RuntimeError", "Tokens can only be created by ContextVars") }
        fun tok(a: Array<Any?>): PyContextToken = a[0] as PyContextToken
        t.def("var", PyProperty(PyBuiltin("var") { _, a -> tok(a).variable }, null, null, null).also { it.name = "var" })
        t.def("old_value", PyProperty(PyBuiltin("old_value") { _, a -> tok(a).old }, null, null, null).also { it.name = "old_value" })
        t.def("MISSING", MISSING)
        t.def("__repr__", PyBuiltin("__repr__") { _, a -> PyStr(tokenRepr(tok(a))) })
        t.def("__enter__", PyBuiltin("__enter__") { _, a -> a[0] })
        t.def("__exit__", PyBuiltin("__exit__") { ctx, a -> reset(ctx, tok(a).variable, a[0]); PyNone })
        t.def("__class_getitem__", PyClassMethod(PyBuiltin("__class_getitem__") { _, a -> PyGenericAlias(a[0], PyTyping.argsOf(a[1])) }))

        val c = CONTEXT
        c.newImpl = { _, _, a, kw ->
            if (a.isNotEmpty() || kw != null && kw.size > 0) throw PyRt.typeError("Context() does not accept any arguments")
            PyCtx(CONTEXT)
        }
        fun cx(a: Array<Any?>): PyCtx = a[0] as PyCtx
        fun kwOf(kw: PyDict?): Pair<Array<Any?>, Array<Any?>> =
            if (kw == null || kw.size == 0) NO_ARGS to NO_ARGS else kw.map.keys.map { it.v }.toTypedArray() to kw.map.values.toTypedArray()
        fun runImpl(ctx: PyContext, a: Array<Any?>, kw: PyDict?): Any? {
            if (a.size < 2) throw PyRt.typeError("run() missing 1 required positional argument")
            val (n, vs) = kwOf(kw)
            return run(ctx, cx(a), a[1], a.copyOfRange(2, a.size), n, vs)
        }
        c.def("run", PyBuiltin("run", { ctx, a -> runImpl(ctx, a, null) }, { ctx, a, kw -> runImpl(ctx, a, kw) }))
        c.def("copy", PyBuiltin("copy") { _, a -> copy(cx(a)) })
        c.def("__getitem__", PyBuiltin("__getitem__") { _, a ->
            val k = cv(a[1])
            val m = cx(a).vars
            if (m.containsKey(k)) m[k] else throw PyException.keyError(k)
        })
        c.def("get", PyBuiltin("get") { _, a ->
            val k = cv(a.getOrNull(1))
            val m = cx(a).vars
            if (m.containsKey(k)) m[k] else a.getOrNull(2) ?: PyNone
        })
        c.def("__contains__", PyBuiltin("__contains__") { _, a -> PyBool.of(cx(a).vars.containsKey(cv(a[1]))) })
        c.def("__len__", PyBuiltin("__len__") { _, a -> cx(a).vars.size.toLong() })
        c.def("__iter__", PyBuiltin("__iter__") { _, a -> PyRt.iter(PyList(ArrayList<Any?>(cx(a).vars.keys))) })
        c.def("keys", PyBuiltin("keys") { _, a -> PyList(ArrayList<Any?>(cx(a).vars.keys)) })
        c.def("values", PyBuiltin("values") { _, a -> PyList(ArrayList(cx(a).vars.values)) })
        c.def("items", PyBuiltin("items") { _, a -> PyList(ArrayList<Any?>(cx(a).vars.map { (k, x) -> PyTuple(arrayOf(k, x)) })) })
        c.def("__eq__", PyBuiltin("__eq__") { _, a ->
            val o = a[1] as? PyCtx ?: return@PyBuiltin PyNotImplemented
            val x = cx(a).vars
            PyBool.of(x.size == o.vars.size && x.all { (k, value) -> o.vars.containsKey(k) && PyRt.eq(value, o.vars[k]) })
        })
        c.def("__hash__", PyNone)
    }

    /** `_contextvars` as a module. */
    @JvmStatic
    fun fill(ctx: PyContext, d: PyDict) {
        d[PyStr("ContextVar")] = CONTEXTVAR
        d[PyStr("Token")] = TOKEN
        d[PyStr("Context")] = CONTEXT
        d[PyStr("copy_context")] = PyBuiltin("copy_context") { c, _ -> copy(current(c)) }
    }
}
