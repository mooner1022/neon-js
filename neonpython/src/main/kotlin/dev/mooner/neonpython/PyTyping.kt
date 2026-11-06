package dev.mooner.neonpython

/**
 * types.GenericAlias (PEP 585): a type subscripted, `list[int]`; [starred] the `*tuple[int, ...]` that iterating
 * one gives (PEP 646). CPython's Objects/genericaliasobject.c. (A Generic class subscripted is typing.py's
 * _GenericAlias, as in CPython.)
 */
class PyGenericAlias(@JvmField val origin: Any?, @JvmField val args: PyTuple, @JvmField val starred: Boolean = false) : PyValue() {
    /** Its `__parameters__`, made the first time they are asked for. */
    @JvmField var params: PyTuple? = null

    override fun equals(other: Any?) = other is PyGenericAlias && starred == other.starred &&
        PyRt.eq(origin, other.origin) && PyRt.eq(args, other.args)

    override fun hashCode() = PyRt.hash(origin) xor PyRt.hash(args)
}

/** typing.Union (3.14's `X | Y` of types): its [args] flattened, without duplicates, None as NoneType. */
class PyUnion(@JvmField val args: PyTuple) {
    /** Its `__parameters__`, made the first time they are asked for. */
    @JvmField var params: PyTuple? = null

    override fun equals(other: Any?) = other is PyUnion && args.items.size == other.args.items.size &&
        args.items.all { a -> other.args.items.any { PyRt.eq(a, it) } }

    override fun hashCode() = args.items.fold(0x2f1b) { h, a -> h xor PyRt.hash(a) }
}

/**
 * A type parameter (PEP 695): a TypeVar, a TypeVarTuple or a ParamSpec ([kind]); its bound, constraints and default
 * each a function evaluated when first asked for (their `evaluate_...`, `(format=1, /)` as an `__annotate__`).
 */
class PyTypeParam(@JvmField val kind: Int, @JvmField val name: String) {
    @JvmField var evaluateBound: Any? = null
    @JvmField var evaluateConstraints: Any? = null
    @JvmField var evaluateDefault: Any? = null
    @JvmField var bound: Any? = null
    @JvmField var constraints: Any? = null
    @JvmField var default: Any? = null
    /** Attributes set on it. */
    @JvmField var dict: PyDict? = null
    /** Its variance (a type parameter of the syntax's infers it) and module (a constructed one's: its caller's). */
    @JvmField var covariant = false
    @JvmField var contravariant = false
    @JvmField var inferVariance = true
    @JvmField var module: Any? = null

    companion object {
        const val TYPEVAR = 0
        const val TYPEVARTUPLE = 1
        const val PARAMSPEC = 2
    }
}

/** P.args, P.kwargs ([kwargs]) of a ParamSpec. */
class PyParamSpecPart(@JvmField val origin: PyTypeParam, @JvmField val kwargs: Boolean) {
    override fun equals(other: Any?) = other is PyParamSpecPart && other.origin === origin && other.kwargs == kwargs
    override fun hashCode() = System.identityHashCode(origin) * 2 + if (kwargs) 1 else 0
}

/** typing.NoDefault: a type parameter's default where it has none. */
object PyNoDefault

/**
 * A `type` statement's alias (typing.TypeAliasType): its value a function evaluated when first asked for; its
 * [typeParams] null where it is not generic.
 */
class PyTypeAlias(@JvmField val name: String, @JvmField val typeParams: PyTuple?, @JvmField val evaluate: Any?, @JvmField val module: Any?) {
    @JvmField var value: Any? = null
}

/**
 * The runtime of type expressions the syntax makes (docs/PYTHON_PLATFORM.md §11, M9): generic aliases, unions, and
 * the classes their subscriptions and `|` reach. What CPython implements in C; none of the typing module's own code.
 */
object PyTyping {
    private val NO_ARGS = arrayOfNulls<Any?>(0)

    @JvmField val GENERIC_ALIAS = PyTypes.newBuiltin("GenericAlias").also {
        it.module = "types"
        it.tpName = "types.GenericAlias"
        // GenericAlias(origin, args); a subclass's instance (collections.abc's _CallableGenericAlias) one with its class
        fun make(cls: Any?, a: Array<Any?>, from: Int): Any {
            val n = a.size - from
            if (n != 2) throw PyRt.typeError("GenericAlias expected 2 arguments, got $n")
            val ga = PyGenericAlias(a[from], argsOf(a[from + 1]))
            if (cls is PyType && cls !== it) ga.sub = PySub(cls)
            return ga
        }
        it.newImpl = { _, _, a, kw ->
            if (kw != null && kw.size > 0) throw PyRt.typeError("GenericAlias() takes no keyword arguments")
            make(it, a, 0)
        }
        it.def("__new__", PyBuiltin("__new__", { _, a -> make(a[0], a, 1) }, { _, a, _ -> make(a[0], a, 1) }))
    }
    @JvmField val UNION = PyTypes.newBuiltin("Union", subclassable = false).also {
        it.module = "typing"
        it.tpName = "typing.Union"
    }

    @JvmField val TYPEVAR = typing("TypeVar")
    @JvmField val TYPEVARTUPLE = typing("TypeVarTuple")
    @JvmField val PARAMSPEC = typing("ParamSpec")
    @JvmField val PARAMSPEC_ARGS = typing("ParamSpecArgs")
    @JvmField val PARAMSPEC_KWARGS = typing("ParamSpecKwargs")
    @JvmField val NODEFAULT = typing("NoDefaultType")
    @JvmField val TYPE_ALIAS = typing("TypeAliasType")
    /** typing.Generic: a generic class's base, its subscriptions and its subclasses' parameters typing.py's. */
    @JvmField val GENERIC = PyTypes.newBuiltin("Generic").also { it.module = "typing" }

    private fun typing(name: String): PyType = PyTypes.newBuiltin(name, subclassable = false).also {
        it.module = "typing"
        it.tpName = "typing.$name"
    }

    /** The builtin types whose `__class_getitem__` gives a GenericAlias (`type[...]`'s is type's own case). */
    private fun genericTypes(): List<PyType> = listOf(PyTypes.LIST, PyTypes.DICT, PyTypes.SET, PyTypes.FROZENSET, PyTypes.TUPLE,
        PyTypes.CLASSMETHOD, PyTypes.STATICMETHOD, PyExceptions.type("BaseExceptionGroup"), PyExceptions.type("ExceptionGroup"))

    @JvmStatic
    fun define() {
        defineConstructors()
        if (!GENERIC.dict.containsKey(PyStr("__class_getitem__"))) {
            // (typing.py's, as typevarobject.c's generic_class_getitem and generic_init_subclass call them)
            GENERIC.def("__class_getitem__", PyClassMethod(typingArgsKwargs("__class_getitem__", "_generic_class_getitem")))
            GENERIC.def("__init_subclass__", PyClassMethod(typingArgsKwargs("__init_subclass__", "_generic_init_subclass")))
        }
        for (t in genericTypes()) {
            if (t.dict.containsKey(PyStr("__class_getitem__"))) continue
            t.def("__class_getitem__", PyClassMethod(PyBuiltin("__class_getitem__") { _, a ->
                if (a.size != 2) throw PyRt.typeError("__class_getitem__() takes exactly one argument (${a.size - 1} given)")
                PyGenericAlias(a[0], argsOf(a[1]))
            }))
        }
    }

    /** The typing module, imported where it is not yet (CPython's C calls into typing.py so). */
    private fun typingModule(ctx: PyContext): Any? = PyImport.importModule(ctx, "typing")

    /** typing.[name](*args) (call_typing_func_object). */
    private fun callTyping(ctx: PyContext, name: String, vararg args: Any?): Any? =
        PyCalls.callWith(ctx, PyRt.getAttr(typingModule(ctx), name), arrayOf(*args), NO_ARGS, NO_ARGS)

    /** A classmethod's function that calls typing.[fn](cls, *args, **kwargs) (call_typing_args_kwargs). */
    private fun typingArgsKwargs(name: String, fn: String): PyBuiltin = PyBuiltin(name,
        { ctx, a -> PyCalls.callWith(ctx, PyRt.getAttr(typingModule(ctx), fn), a, NO_ARGS, NO_ARGS) },
        { ctx, a, kw ->
            val names = arrayOfNulls<Any?>(kw.size)
            val values = arrayOfNulls<Any?>(kw.size)
            var i = 0
            for ((k, v) in kw.map) {
                names[i] = k.v
                values[i++] = v
            }
            PyCalls.callWith(ctx, PyRt.getAttr(typingModule(ctx), fn), a, names, values)
        })

    /** typing.Unpack[v] (typevarobject.c's unpack): `*Ts`, `*Alias`. */
    private fun unpack(ctx: PyContext, v: Any?): Any? = PyRt.getItem(PyRt.getAttr(typingModule(ctx), "Unpack"), v)

    /** [params] with each TypeVarTuple unpacked (unpack_typevartuples: Generic's and a type alias's parameters). */
    private fun unpackTypeVarTuples(ctx: PyContext, params: PyTuple): PyTuple {
        fun tvt(v: Any?) = v is PyTypeParam && v.kind == PyTypeParam.TYPEVARTUPLE
        if (params.items.none { tvt(it) }) return params
        return PyTuple(Array(params.items.size) { i -> params.items[i].let { if (tvt(it)) unpack(ctx, it) else it } })
    }

    /** getattr(v, name, None) where only an AttributeError is its absence (PyObject_GetOptionalAttr); null for none. */
    private fun optionalAttr(v: Any?, name: String): Any? = try {
        PyRt.getAttr(v, name)
    } catch (e: PyException) {
        if (e.matches(PyExceptions.type("AttributeError"))) null else throw e
    }

    /** Values that have none of the typing protocol's attributes (`__parameters__`, `__typing_subst__`, ...). */
    private fun plain(v: Any?): Boolean = v == null || v === PyNone || v === PyEllipsis || v is Long || v is Double ||
        v is Boolean || v is PyBool || v is PyStr && v.sub == null || v is java.math.BigInteger

    /** A subscription's key as a tuple of arguments (`list[int]` one, `dict[str, int]` two). */
    @JvmStatic fun argsOf(key: Any?): PyTuple = if (key is PyTuple && key.sub == null) key else PyTuple(arrayOf(key))

    // ------------------------------------------------------------------ subscriptions

    /**
     * t[key] of a type: its metaclass's __getitem__, else its __class_getitem__ (a classmethod: list's gives a
     * GenericAlias), else `type[...]`'s GenericAlias; CPython's message where it has none.
     */
    @JvmStatic
    fun typeGetItem(t: PyType, key: Any?): Any? {
        val ctx = PyContext.current()
        val meta = PyTypes.typeOf(t)
        if (meta !== PyTypes.TYPE) meta.lookup("__getitem__")?.let {
            return PyCalls.callWith(ctx, PyClasses.descrGet(ctx, it, t, meta), arrayOf(key), NO_ARGS, NO_ARGS)
        }
        val cg = t.lookup("__class_getitem__")
        if (cg != null) return PyCalls.callWith(ctx, PyClasses.descrGet(ctx, cg, null, t), arrayOf(key), NO_ARGS, NO_ARGS)
        if (t === PyTypes.TYPE) return PyGenericAlias(t, argsOf(key))
        throw PyRt.typeError("type '${t.name}' is not subscriptable")
    }

    /**
     * ga[key] (ga_getitem), union[key] (union_getitem): its parameters replaced by the arguments
     * (_Py_subs_parameters), a GenericAlias of the same origin (starred as it is), a union of them.
     */
    @JvmStatic
    fun aliasGetItem(alias: Any?, key: Any?): Any? = when (alias) {
        is PyGenericAlias -> PyGenericAlias(alias.origin, subsParameters(alias, alias.args, parameters(alias).items, key), alias.starred)
        is PyUnion -> unionOf(subsParameters(alias, alias.args, parameters(alias).items, key).items.asList())
        else -> throw IllegalStateException("not an alias: $alias")
    }

    /**
     * `__parameters__` of an alias or a union: the type variables of its arguments (_Py_make_parameters), each
     * once, in order: an argument with `__typing_subst__` itself, else its `__parameters__` (a list's or a tuple's
     * arguments' where it has none); a class none.
     */
    @JvmStatic
    fun parameters(alias: Any?): PyTuple = when (alias) {
        is PyGenericAlias -> alias.params ?: makeParameters(alias.args.items).also { alias.params = it }
        is PyUnion -> alias.params ?: makeParameters(alias.args.items).also { alias.params = it }
        else -> PyTuple.EMPTY
    }

    private fun makeParameters(args: Array<Any?>): PyTuple {
        val out = ArrayList<Any?>()
        fun add(t: Any?) {
            if (out.none { it === t }) out.add(t)
        }
        for (t in args) {
            if (t is PyType || plain(t)) continue
            if (optionalAttr(t, "__typing_subst__") != null) {
                add(t)
                continue
            }
            var sub = optionalAttr(t, "__parameters__")
            if (sub == null && t is PyTuple) sub = makeParameters(t.items)
            if (sub == null && t is PyList) sub = makeParameters(t.items.toTypedArray())
            if (sub is PyTuple) for (x in sub.items) add(x)
        }
        return PyTuple(out.toTypedArray())
    }

    /**
     * [args] with [params] replaced by [key]'s arguments (_Py_subs_parameters): the key's `*tuple[...]`s spread, each
     * parameter's `__typing_prepare_subst__` (a missing one's default, a TypeVarTuple's arguments gathered), one
     * argument for each; then each type variable's `__typing_subst__`, an alias's own subscription
     * ([subsTvars]), a list's or a tuple's arguments the same way.
     */
    private fun subsParameters(self: Any?, args: Any?, params: Array<Any?>, key: Any?): PyTuple {
        if (params.isEmpty()) throw PyRt.typeError("${PyRt.repr(self)} is not a generic class")
        val ctx = PyContext.current()
        var item: Any? = unpackArgs(key)
        for (p in params) {
            val prepare = if (plain(p)) null else optionalAttr(p, "__typing_prepare_subst__")
            if (prepare != null && prepare !== PyNone) {
                item = PyCalls.callWith(ctx, prepare, arrayOf(self, if (item is PyTuple) item else PyTuple(arrayOf(item))), NO_ARGS, NO_ARGS)
            }
        }
        val argItems = if (item is PyTuple) item.items else arrayOf(item)
        if (argItems.size != params.size) {
            throw PyRt.typeError("Too ${if (argItems.size > params.size) "many" else "few"} arguments for ${PyRt.repr(self)}; actual ${argItems.size}, expected ${params.size}")
        }
        val src = if (args is PyList) args.items.toTypedArray() else (args as PyTuple).items
        val out = ArrayList<Any?>()
        for (arg in src) {
            if (arg is PyType) {
                out.add(arg)
                continue
            }
            if (arg is PyTuple || arg is PyList) {
                val sub = subsParameters(self, arg, params, item)
                out.add(if (arg is PyList) PyList(ArrayList(sub.items.asList())) else sub)
                continue
            }
            if (plain(arg)) {
                out.add(arg)
                continue
            }
            val unpack = isUnpackedTypeVarTuple(arg)
            val subst = optionalAttr(arg, "__typing_subst__")
            val i = params.indexOfFirst { it === arg }
            val r = if (subst != null && i >= 0) PyCalls.callWith(ctx, subst, arrayOf(argItems[i]), NO_ARGS, NO_ARGS)
                else subsTvars(arg, params, argItems)
            if (unpack) {
                if (r !is PyTuple) {
                    throw PyRt.typeError("expected __typing_subst__ of ${fullName(PyTypes.typeOf(arg))} objects to return a tuple, not ${fullName(PyTypes.typeOf(r))}")
                }
                out.addAll(r.items)
            } else out.add(r)
        }
        return PyTuple(out.toTypedArray())
    }

    private fun fullName(t: PyType): String = if (t.module == "builtins") t.qualname else "${t.module}.${t.qualname}"

    /**
     * [obj] (an alias in an alias's arguments) subscripted with what its own parameters become (subs_tvars), a
     * TypeVarTuple's arguments spread; as it is where it has none.
     */
    private fun subsTvars(obj: Any?, params: Array<Any?>, argItems: Array<Any?>): Any? {
        val sub = optionalAttr(obj, "__parameters__")
        if (sub !is PyTuple || sub.items.isEmpty()) return obj
        val subargs = ArrayList<Any?>()
        for (a in sub.items) {
            val i = params.indexOfFirst { it === a }
            if (i < 0) {
                subargs.add(a)
                continue
            }
            val p = params[i]
            val v = argItems[i]
            val iterable = p is PyTypeParam && p.kind == PyTypeParam.TYPEVARTUPLE || p is PyTypeAlias || PyTypes.typeOf(p).lookup("__iter__") != null
            if (iterable && v is PyTuple) subargs.addAll(v.items) else subargs.add(v)
        }
        return PyRt.getItem(obj, PyTuple(subargs.toTypedArray()))
    }

    /** A key's arguments with each `*tuple[X, Y]` (not `*tuple[X, ...]`) spread (_unpack_args). */
    private fun unpackArgs(key: Any?): PyTuple {
        val items = if (key is PyTuple) key.items else arrayOf(key)
        val out = ArrayList<Any?>()
        for (x in items) {
            if (x !is PyType) {
                val sub = unpackedTupleArgs(x)
                if (sub is PyTuple && !(sub.items.isNotEmpty() && sub.items.last() === PyEllipsis)) {
                    out.addAll(sub.items)
                    continue
                }
            }
            out.add(x)
        }
        return PyTuple(out.toTypedArray())
    }

    private fun unpackedTupleArgs(v: Any?): Any? {
        if (v is PyGenericAlias && v.starred && v.origin === PyTypes.TUPLE) return v.args
        if (plain(v)) return null
        return optionalAttr(v, "__typing_unpacked_tuple_args__")?.takeIf { it !== PyNone }
    }

    private fun isUnpackedTypeVarTuple(v: Any?): Boolean {
        if (v is PyType || plain(v)) return false
        return optionalAttr(v, "__typing_is_unpacked_typevartuple__")?.let { PyRt.truthy(it) } ?: false
    }

    /** Whether [v] is a type variable (TypeVar, ParamSpec, TypeVarTuple: M9b's type parameters). */
    @JvmStatic fun isTypeVar(v: Any?): Boolean = v is PyTypeParam

    // ------------------------------------------------------------------ unions

    /** Whether `|` makes a union of [v] (Py_union_type_or's is_unionable). */
    private fun unionable(v: Any?): Boolean = v === PyNone || v is PyType || v is PyGenericAlias || v is PyUnion || v is PyTypeAlias

    /**
     * a | b where either is a type, an alias or a union (else null: the operator's TypeError); a type variable's
     * `|` takes anything.
     */
    @JvmStatic
    fun or(a: Any?, b: Any?): Any? {
        if (a is PyTypeParam || b is PyTypeParam) return unionOf(listOf(a, b))
        if (!(unionable(a) && a !== PyNone || unionable(b) && b !== PyNone)) return null
        if (!unionable(a) || !unionable(b)) return null
        return unionOf(listOf(a, b))
    }

    /** The union of [items]: unions flattened, None as NoneType, duplicates dropped; one left is itself. */
    @JvmStatic
    fun unionOf(items: List<Any?>): Any? {
        val out = ArrayList<Any?>()
        fun add(x: Any?) {
            val v = if (x === PyNone) PyTypes.NONE else x
            if (out.none { it === v || PyRt.eq(it, v) }) out.add(v)
        }
        for (x in items) if (x is PyUnion) x.args.items.forEach { add(it) } else add(x)
        return if (out.size == 1) out[0] else PyUnion(PyTuple(out.toTypedArray()))
    }

    // ------------------------------------------------------------------ the rest of their behaviour

    /** An argument as an alias's or a union's repr writes it: a class by its qualified name, `...`, else its repr. */
    private fun reprItem(p: Any?): String {
        if (p === PyEllipsis) return "..."
        if (typeOf(p) != null) return repr(p)
        if (p is PyGenericAlias || p is PyUnion) return PyRt.repr(p)
        val (qualname, module) = when (p) {
            is PyType -> p.qualname to p.module
            is PyFunction -> p.qualname to ((p.globals[PyStr("__name__")] as? PyStr)?.s ?: return PyRt.repr(p))
            is PyBuiltin -> p.name to "builtins"
            else -> return PyRt.repr(p)
        }
        return if (module == "builtins") qualname else "$module.$qualname"
    }


    @JvmStatic
    fun repr(v: Any?): String = when (v) {
        is PyGenericAlias -> gaRepr(v)
        // (a PEP 695 type variable's variance is inferred: no +, - or ~)
        is PyTypeParam -> when {
            v.kind == PyTypeParam.TYPEVARTUPLE || v.inferVariance -> v.name
            v.covariant -> "+" + v.name
            v.contravariant -> "-" + v.name
            else -> "~" + v.name
        }
        is PyParamSpecPart -> v.origin.name + if (v.kwargs) ".kwargs" else ".args"
        is PyTypeAlias -> v.name
        PyNoDefault -> "typing.NoDefault"
        else -> throw IllegalStateException("not a type expression: $v")
    }

    private fun gaRepr(v: PyGenericAlias): String = when (v) {
        else -> buildString {
            if (v.starred) append('*')
            append(reprItem(v.origin)).append('[')
            for ((i, a) in v.args.items.withIndex()) {
                if (i > 0) append(", ")
                append(if (a is PyList && a.sub == null) a.items.joinToString(", ", "[", "]") { reprItem(it) } else reprItem(a))
            }
            if (v.args.items.isEmpty()) append("()")
            append(']')
        }
    }

    @JvmStatic
    fun unionRepr(v: Any?): String = when (v) {
        is PyUnion -> v.args.items.joinToString(" | ") { if (it === PyTypes.NONE) "None" else reprItem(it) }
        else -> throw IllegalStateException("not a type expression: $v")
    }

    /** ga(...): its origin called, the instance given `__orig_class__` where it takes one. */
    @JvmStatic
    fun call(ctx: PyContext, ga: PyGenericAlias, pos: Array<Any?>, kwNames: Array<Any?>, kwValues: Array<Any?>): Any? {
        val r = PyCalls.callWith(ctx, ga.origin, pos, kwNames, kwValues)
        try {
            PyClasses.setAttr(r, "__orig_class__", ga)
        } catch (e: PyException) {
            if (!e.matches(PyExceptions.type("AttributeError")) && !e.matches(PyExceptions.type("TypeError"))) throw e
        }
        return r
    }

    /** A GenericAlias's own attributes; the others are its origin's (ga_getattro). */
    private val OWN = setOf("__class__", "__bases__", "__origin__", "__args__", "__unpacked__", "__parameters__",
        "__typing_unpacked_tuple_args__", "__mro_entries__", "__reduce_ex__", "__reduce__", "__copy__", "__deepcopy__")

    @JvmStatic
    fun aliasAttr(ga: PyGenericAlias, name: String): Any? {
        if (name !in OWN) return PyRt.getAttr(ga.origin, name)
        return when (name) {
            "__class__" -> GENERIC_ALIAS
            "__origin__" -> ga.origin
            "__args__" -> ga.args
            "__unpacked__" -> PyBool.of(ga.starred)
            "__parameters__" -> parameters(ga)
            "__typing_unpacked_tuple_args__" -> if (ga.starred && ga.origin === PyTypes.TUPLE) ga.args else PyNone
            "__mro_entries__" -> PyBuiltin("__mro_entries__") { _, _ -> PyTuple(arrayOf(ga.origin)) }
            else -> throw PyException("AttributeError", "'types.GenericAlias' object has no attribute '$name'")
        }
    }

    @JvmStatic
    fun unionAttr(u: PyUnion, name: String): Any? = when (name) {
        "__class__" -> UNION
        "__args__" -> u.args
        "__origin__" -> UNION
        "__parameters__" -> parameters(u)
        "__name__", "__qualname__" -> PyStr("Union")
        "__module__" -> PyStr("typing")
        else -> throw PyException("AttributeError", "'typing.Union' object has no attribute '$name'")
    }

    // ------------------------------------------------------------------ Generic (typing.py's)

    /** SUBSCRIPT_GENERIC: a generic class's base, typing._GenericAlias(Generic, its type parameters, TypeVarTuples unpacked). */
    @JvmStatic
    fun subscriptGeneric(params: Any?): Any? {
        val ctx = PyContext.current()
        return callTyping(ctx, "_GenericAlias", GENERIC, unpackTypeVarTuples(ctx, argsOf(params)))
    }

    // ------------------------------------------------------------------ type parameters (PEP 695, 696)

    /** MAKE_TYPEVAR [kind] (TypeVar, TypeVarTuple, ParamSpec): one named [name]. */
    @JvmStatic fun makeTypeVar(name: Any?, kind: Int): Any? = PyTypeParam(kind, (name as PyStr).s)

    // ------------------------------------------------------------------ the constructors (_typing's, typing.py's)

    /** CPython's type_check: typing._type_check(arg, msg) (None its type). */
    private fun typeCheck(ctx: PyContext, v: Any?, msg: String): Any? {
        if (v === PyNone || v == null) return PyTypes.NONE
        return callTyping(ctx, "_type_check", v, PyStr(msg))
    }

    /** The module of the code calling a constructor (CPython's caller()). */
    private fun callerModule(ctx: PyContext): Any? = try {
        PyFrames.getFrame(ctx, 0L).f.globals[PyStr("__name__")] ?: PyNone
    } catch (e: PyException) {
        PyNone
    }

    private fun kwArgs(fn: String, kw: PyDict?, names: Set<String>): Map<String, Any?> {
        val out = HashMap<String, Any?>()
        kw?.map?.forEach { (k, v) ->
            val n = (k.v as PyStr).s
            if (n !in names) throw PyRt.typeError("$fn() got an unexpected keyword argument '$n'")
            out[n] = v
        }
        return out
    }

    private fun nameArg(fn: String, a: Array<Any?>, kw: Map<String, Any?>): String {
        val n = a.getOrNull(0) ?: kw["name"] ?: throw PyRt.typeError("$fn() missing required argument 'name' (pos 1)")
        return (n as? PyStr ?: throw PyRt.typeError("$fn() argument 'name' must be str, not ${PyTypes.name(n)}")).s
    }

    private fun variance(t: PyTypeParam, kw: Map<String, Any?>) {
        t.covariant = kw["covariant"]?.let { PyRt.truthy(it) } ?: false
        t.contravariant = kw["contravariant"]?.let { PyRt.truthy(it) } ?: false
        t.inferVariance = kw["infer_variance"]?.let { PyRt.truthy(it) } ?: false
        if (t.covariant && t.contravariant) throw PyException("ValueError", "Bivariant types are not supported.")
        if (t.inferVariance && (t.covariant || t.contravariant)) throw PyException("ValueError", "Variance cannot be specified with infer_variance.")
    }

    private fun defineConstructors() {
        TYPEVAR.newImpl = { ctx, _, a, kw0 ->
            val kw = kwArgs("TypeVar", kw0, setOf("name", "bound", "default", "covariant", "contravariant", "infer_variance"))
            val t = PyTypeParam(PyTypeParam.TYPEVAR, nameArg("TypeVar", a, kw))
            variance(t, kw)
            val bound = kw["bound"]?.takeIf { it !== PyNone }?.let { typeCheck(ctx, it, "Bound must be a type.") }
            val constraints = if (a.size > 1) a.copyOfRange(1, a.size) else emptyArray()
            if (constraints.size == 1) throw PyRt.typeError("A single constraint is not allowed")
            if (constraints.isNotEmpty() && bound != null) throw PyRt.typeError("Constraints cannot be combined with bound=...")
            t.bound = bound
            t.evaluateBound = null
            if (bound != null) t.evaluateBound = PyBuiltin("evaluate_bound") { _, _ -> bound }
            if (constraints.isNotEmpty()) {
                val cs = PyTuple(Array(constraints.size) { typeCheck(ctx, constraints[it], "TypeVar(name, constraint, ...): constraints must be types.") })
                t.constraints = cs
                t.evaluateConstraints = PyBuiltin("evaluate_constraints") { _, _ -> cs }
            }
            kw["default"]?.takeIf { it !== PyNoDefault }?.let { d -> t.default = d; t.evaluateDefault = PyBuiltin("evaluate_default") { _, _ -> d } }
            t.module = callerModule(ctx)
            t
        }
        PARAMSPEC.newImpl = { ctx, _, a, kw0 ->
            val kw = kwArgs("ParamSpec", kw0, setOf("name", "bound", "default", "covariant", "contravariant", "infer_variance"))
            if (a.size > 1) throw PyRt.typeError("ParamSpec() takes at most 1 positional argument (${a.size} given)")
            val t = PyTypeParam(PyTypeParam.PARAMSPEC, nameArg("ParamSpec", a, kw))
            variance(t, kw)
            kw["bound"]?.takeIf { it !== PyNone }?.let { b ->
                val v = typeCheck(ctx, b, "Bound must be a type.")
                t.bound = v
                t.evaluateBound = PyBuiltin("evaluate_bound") { _, _ -> v }
            }
            kw["default"]?.takeIf { it !== PyNoDefault }?.let { d -> t.default = d; t.evaluateDefault = PyBuiltin("evaluate_default") { _, _ -> d } }
            t.module = callerModule(ctx)
            t
        }
        TYPEVARTUPLE.newImpl = { ctx, _, a, kw0 ->
            val kw = kwArgs("TypeVarTuple", kw0, setOf("name", "default"))
            if (a.size > 1) throw PyRt.typeError("TypeVarTuple() takes at most 1 positional argument (${a.size} given)")
            val t = PyTypeParam(PyTypeParam.TYPEVARTUPLE, nameArg("TypeVarTuple", a, kw))
            kw["default"]?.takeIf { it !== PyNoDefault }?.let { d -> t.default = d; t.evaluateDefault = PyBuiltin("evaluate_default") { _, _ -> d } }
            t.module = callerModule(ctx)
            t
        }
        for ((ty, kwargs) in listOf(PARAMSPEC_ARGS to false, PARAMSPEC_KWARGS to true)) ty.newImpl = { _, _, a, kw ->
            if (kw != null && kw.size > 0) throw PyRt.typeError("${ty.name}() takes no keyword arguments")
            if (a.size != 1) throw PyRt.typeError("${ty.name}() takes exactly one argument (${a.size} given)")
            PyParamSpecPart(a[0] as? PyTypeParam ?: throw PyRt.typeError("${ty.name}() argument must be a ParamSpec"), kwargs)
        }
        TYPE_ALIAS.newImpl = { ctx, _, a, kw0 ->
            val kw = kwArgs("TypeAliasType", kw0, setOf("name", "value", "type_params"))
            if (a.size > 2) throw PyRt.typeError("TypeAliasType() takes at most 2 positional arguments (${a.size} given)")
            val name = nameArg("TypeAliasType", a, kw)
            val value = a.getOrNull(1) ?: kw["value"] ?: throw PyRt.typeError("TypeAliasType() missing required argument 'value' (pos 2)")
            val tps = kw["type_params"]?.let { it as? PyTuple ?: throw PyRt.typeError("type_params must be a tuple") }
            tps?.items?.forEach { if (it !is PyTypeParam) throw PyRt.typeError("Expected a type param, got ${PyRt.repr(it)}") }
            PyTypeAlias(name, tps, null, callerModule(ctx)).also { it.value = value }
        }
        UNION.def("__class_getitem__", PyClassMethod(PyBuiltin("__class_getitem__") { ctx, a -> unionGetItem(ctx, a.getOrNull(1)) }))
        NODEFAULT.newImpl = { _, _, a, kw ->
            if (a.isNotEmpty() || kw != null && kw.size > 0) throw PyRt.typeError("NoDefaultType takes no arguments")
            PyNoDefault
        }
    }

    /**
     * Union[X, Y, ...] (union_class_getitem): each argument typing._type_check's, None as NoneType, unions flattened,
     * duplicates dropped; one left is itself, none an error.
     */
    private fun unionGetItem(ctx: PyContext, args: Any?): Any? {
        val items = if (args is PyTuple && args.sub == null) args.items.toList() else listOf(args)
        val checked = ArrayList<Any?>()
        fun add(x: Any?) {
            when {
                x === PyNone -> checked.add(PyTypes.NONE)
                x is PyUnion -> x.args.items.forEach { add(it) }
                else -> checked.add(typeCheck(ctx, x, "Union[arg, ...]: each arg must be a type."))
            }
        }
        for (x in items) add(x)
        if (checked.isEmpty()) throw PyRt.typeError("Cannot take a Union of no types.")
        return unionOf(checked)
    }

    /** `_typing` as a module: the types typing.py is made of. */
    @JvmStatic
    fun fillTyping(ctx: PyContext, d: PyDict) {
        d[PyStr("_idfunc")] = PyBuiltin("_idfunc") { _, a -> a.getOrNull(0) }
        d[PyStr("TypeVar")] = TYPEVAR
        d[PyStr("ParamSpec")] = PARAMSPEC
        d[PyStr("TypeVarTuple")] = TYPEVARTUPLE
        d[PyStr("ParamSpecArgs")] = PARAMSPEC_ARGS
        d[PyStr("ParamSpecKwargs")] = PARAMSPEC_KWARGS
        d[PyStr("TypeAliasType")] = TYPE_ALIAS
        d[PyStr("Generic")] = GENERIC
        d[PyStr("Union")] = UNION
        d[PyStr("NoDefault")] = PyNoDefault
    }

    /** MAKE_TYPEVAR_BOUND: a TypeVar with its bound ([kind] 1) or constraints (2) evaluated by [fn]. */
    @JvmStatic
    fun makeTypeVarBound(name: Any?, fn: Any?, kind: Int): Any? {
        val t = PyTypeParam(PyTypeParam.TYPEVAR, (name as PyStr).s)
        if (kind == 1) t.evaluateBound = fn else t.evaluateConstraints = fn
        return t
    }

    /** SET_TYPEPARAM_DEFAULT: [tp]'s default evaluated by [fn]. */
    @JvmStatic
    fun setTypeParamDefault(tp: Any?, fn: Any?): Any? {
        (tp as PyTypeParam).evaluateDefault = fn
        return tp
    }

    /** MAKE_TYPEALIAS: a `type` statement's alias, its module the code's. */
    @JvmStatic
    fun makeTypeAlias(f: dev.mooner.neonjs.vm.VmFrame, name: Any?, params: Any?, fn: Any?): Any? =
        PyTypeAlias((name as PyStr).s, params as? PyTuple, fn, (f as PyFrame).globals[PyStr("__name__")] ?: PyNone)

    private fun evaluate(fn: Any?): Any? = PyCalls.callWith(PyContext.current(), fn, NO_ARGS, NO_ARGS, NO_ARGS)

    @JvmStatic fun hasDefault(t: PyTypeParam): Boolean = t.evaluateDefault != null

    @JvmStatic
    fun defaultOf(t: PyTypeParam): Any? {
        if (t.evaluateDefault == null) return PyNoDefault
        return t.default ?: evaluate(t.evaluateDefault).also { t.default = it }
    }

    private fun typeParamType(t: PyTypeParam): PyType = when (t.kind) {
        PyTypeParam.TYPEVAR -> TYPEVAR
        PyTypeParam.TYPEVARTUPLE -> TYPEVARTUPLE
        else -> PARAMSPEC
    }

    @JvmStatic
    fun typeOf(v: Any?): PyType? = when (v) {
        is PyTypeParam -> typeParamType(v)
        is PyParamSpecPart -> if (v.kwargs) PARAMSPEC_KWARGS else PARAMSPEC_ARGS
        is PyTypeAlias -> TYPE_ALIAS
        PyNoDefault -> NODEFAULT
        else -> null
    }

    /** A type parameter's attributes (typevarobject.c's members and getters), then those set on it. */
    @JvmStatic
    fun typeParamAttr(t: PyTypeParam, name: String): Any? {
        val tv = t.kind == PyTypeParam.TYPEVAR
        when (name) {
            "__name__" -> return PyStr(t.name)
            "__module__" -> return t.module ?: PyStr("typing")
            "__class__" -> return typeParamType(t)
            "__default__" -> return defaultOf(t)
            "evaluate_default" -> return t.evaluateDefault ?: PyNone
            "has_default" -> return PyBuiltin("has_default") { _, _ -> PyBool.of(hasDefault(t)) }
            // (typing.py's _typevar_subst and _paramspec_subst; a TypeVarTuple's is not substituted bare)
            "__typing_subst__" -> return PyBuiltin("__typing_subst__") { ctx, a ->
                if (a.size != 1) throw PyRt.typeError("__typing_subst__() takes exactly one argument (${a.size} given)")
                when (t.kind) {
                    PyTypeParam.TYPEVAR -> callTyping(ctx, "_typevar_subst", t, a[0])
                    PyTypeParam.PARAMSPEC -> callTyping(ctx, "_paramspec_subst", t, a[0])
                    else -> throw PyRt.typeError("Substitution of bare TypeVarTuple is not supported")
                }
            }
            // (a TypeVar's here, as CPython's C has it; a ParamSpec's and a TypeVarTuple's typing.py's)
            "__typing_prepare_subst__" -> return PyBuiltin("__typing_prepare_subst__") { ctx, a ->
                if (a.size != 2) throw PyRt.typeError("__typing_prepare_subst__ expected 2 arguments, got ${a.size}")
                when (t.kind) {
                    PyTypeParam.TYPEVAR -> typeVarPrepareSubst(t, a[0], a[1])
                    PyTypeParam.PARAMSPEC -> callTyping(ctx, "_paramspec_prepare_subst", t, a[0], a[1])
                    else -> callTyping(ctx, "_typevartuple_prepare_subst", t, a[0], a[1])
                }
            }
        }
        if (t.kind != PyTypeParam.TYPEVARTUPLE) when (name) {
            "__covariant__" -> return PyBool.of(t.covariant)
            "__contravariant__" -> return PyBool.of(t.contravariant)
            "__infer_variance__" -> return PyBool.of(t.inferVariance)
            "__bound__" -> return if (!tv || t.evaluateBound == null) PyNone else t.bound ?: evaluate(t.evaluateBound).also { t.bound = it }
        }
        if (tv) when (name) {
            "__constraints__" -> return if (t.evaluateConstraints == null) PyTuple.EMPTY else t.constraints ?: evaluate(t.evaluateConstraints).also { t.constraints = it }
            "evaluate_bound" -> return t.evaluateBound ?: PyNone
            "evaluate_constraints" -> return t.evaluateConstraints ?: PyNone
        }
        if (t.kind == PyTypeParam.PARAMSPEC) when (name) {
            "args" -> return PyParamSpecPart(t, false)
            "kwargs" -> return PyParamSpecPart(t, true)
        }
        t.dict?.get(PyStr(name))?.let { return it }
        throw PyException("AttributeError", "'${typeParamType(t).tpName}' object has no attribute '$name'")
    }

    private val TYPE_PARAM_ATTRS = setOf("__name__", "__module__", "__default__", "evaluate_default", "__covariant__",
        "__contravariant__", "__infer_variance__", "__bound__", "__constraints__", "evaluate_bound", "evaluate_constraints")

    @JvmStatic
    fun typeParamSet(t: PyTypeParam, name: String, v: Any?) {
        if (name in TYPE_PARAM_ATTRS) throw PyException("AttributeError", "attribute '$name' of '${typeParamType(t).tpName}' objects is not writable")
        (t.dict ?: PyDict().also { t.dict = it })[PyStr(name)] = v
    }

    /**
     * A TypeVar's __typing_prepare_subst__(alias, args) (typevar_typing_prepare_subst): the arguments as they are where
     * one is there for it, with its default where it is the first missing.
     */
    private fun typeVarPrepareSubst(t: PyTypeParam, alias: Any?, args: Any?): Any? {
        val params = PyBuiltins.toList(PyRt.getAttr(alias, "__parameters__"))
        val i = params.indexOfFirst { PyRt.eq(it, t) }
        if (i < 0) throw PyException("ValueError", "sequence.index(x): x not in sequence")
        val given = PyBuiltins.toList(args)
        if (i < given.size) return args
        if (i == given.size) {
            val d = defaultOf(t)
            if (d !== PyNoDefault) return PyTuple((given + d).toTypedArray())
        }
        throw PyRt.typeError("Too few arguments for ${PyRt.str(alias)}; actual ${given.size}, expected at least ${i + 1}")
    }

    /** iter(Ts): its one unpacked form, typing.Unpack[Ts]; the other type parameters are not iterable. */
    @JvmStatic
    fun typeParamIter(t: PyTypeParam): PyIterator {
        if (t.kind != PyTypeParam.TYPEVARTUPLE) throw PyRt.typeError("'${typeParamType(t).tpName}' object is not iterable")
        return unpackIter(t)
    }

    /** iter(alias) of a `type` alias: typing.Unpack[alias], as a TypeVarTuple's (unpack_iter). */
    @JvmStatic fun typeAliasIter(a: PyTypeAlias): PyIterator = unpackIter(a)

    private fun unpackIter(v: Any?): PyIterator {
        val u = unpack(PyContext.current(), v)
        var done = false
        return PyIterator { if (done) PyIterator.STOP else { done = true; u } }
    }

    @JvmStatic
    fun partAttr(v: Any?, name: String): Any? = when (v) {
        is PyParamSpecPart -> when (name) {
            "__origin__" -> v.origin
            "__class__" -> typeOf(v)
            else -> throw PyException("AttributeError", "'${typeOf(v)!!.tpName}' object has no attribute '$name'")
        }
        else -> throw PyException("AttributeError", "'${PyTypes.name(v)}' object has no attribute '$name'")
    }

    /** A `type` alias's attributes; its value evaluated once. */
    @JvmStatic
    fun aliasTypeAttr(a: PyTypeAlias, name: String): Any? = when (name) {
        "__name__" -> PyStr(a.name)
        "__module__" -> a.module
        "__value__" -> a.value ?: evaluate(a.evaluate).also { a.value = it }
        "evaluate_value" -> a.evaluate
        "__type_params__" -> a.typeParams ?: PyTuple.EMPTY
        // (its TypeVarTuples unpacked)
        "__parameters__" -> a.typeParams?.let { unpackTypeVarTuples(PyContext.current(), it) } ?: PyTuple.EMPTY
        "__class__" -> TYPE_ALIAS
        else -> throw PyException("AttributeError", "'typing.TypeAliasType' object has no attribute '$name'")
    }

    @JvmStatic
    fun aliasTypeSet(name: String) {
        if (name in setOf("__name__", "__module__", "__value__", "evaluate_value", "__type_params__", "__parameters__")) {
            throw PyException("AttributeError", "attribute '$name' of 'typing.TypeAliasType' objects is not writable")
        }
        throw PyException("AttributeError", "'typing.TypeAliasType' object has no attribute '$name' and no __dict__ for setting new attributes")
    }

    /** alias[args]: a generic alias's GenericAlias (unchecked, as CPython's); a plain one is not subscriptable. */
    @JvmStatic
    fun aliasTypeGetItem(a: PyTypeAlias, key: Any?): Any? {
        if (a.typeParams == null) throw PyRt.typeError("Only generic type aliases are subscriptable")
        return PyGenericAlias(a, argsOf(key))
    }

    /** f.__type_params__ = v: a tuple only (deleting it too is that error). */
    @JvmStatic
    fun setFunctionTypeParams(f: PyFunction, v: Any?) {
        if (v !is PyTuple || v.sub != null) throw PyRt.typeError("__type_params__ must be set to a tuple")
        f.typeParams = v
    }

    /** iter(ga): its one starred form (`*tuple[int, ...]`, what `*` unpacks a type to). */
    @JvmStatic
    fun iter(ga: PyGenericAlias): PyIterator {
        var done = false
        return PyIterator { if (done) PyIterator.STOP else { done = true; PyGenericAlias(ga.origin, ga.args, true) } }
    }

    /** isinstance(v, union) and issubclass(c, union): any of its arguments. */
    @JvmStatic fun unionInstance(v: Any?, u: PyUnion): Boolean = u.args.items.any { PyTypes.isInstance(v, it) }
    @JvmStatic fun unionSubclass(c: Any?, u: PyUnion): Boolean = u.args.items.any { PyTypes.isSubclass(c, it) }

    /**
     * A class statement's bases resolved (PEP 560): each base that is not a type replaced by what its
     * `__mro_entries__(bases)` gives, where it has one; null where none changed.
     */
    @JvmStatic
    fun mroEntries(ctx: PyContext, bases: Array<Any?>): Array<Any?>? {
        var out: ArrayList<Any?>? = null
        val all = PyTuple(bases)
        for ((i, b) in bases.withIndex()) {
            if (b is PyType) {
                out?.add(b)
                continue
            }
            val me = if (b is PyGenericAlias) aliasAttr(b, "__mro_entries__") else try {
                PyRt.getAttr(b, "__mro_entries__")
            } catch (e: PyException) {
                if (e.matches(PyExceptions.type("AttributeError"))) null else throw e
            }
            if (me == null) {
                out?.add(b)
                continue
            }
            val r = PyCalls.callWith(ctx, me, arrayOf(all), NO_ARGS, NO_ARGS)
            if (r !is PyTuple) throw PyRt.typeError("__mro_entries__ must return a tuple")
            if (out == null) out = ArrayList<Any?>().also { for (j in 0 until i) it.add(bases[j]) }
            out.addAll(r.items)
        }
        return out?.toTypedArray()
    }
}
