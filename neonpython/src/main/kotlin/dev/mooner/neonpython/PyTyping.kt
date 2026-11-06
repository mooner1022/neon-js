package dev.mooner.neonpython

/**
 * types.GenericAlias (PEP 585): a type subscripted, `list[int]`; [starred] the `*tuple[int, ...]` that iterating
 * one gives (PEP 646). CPython's Objects/genericaliasobject.c. [typing]: typing._GenericAlias instead, what a
 * Generic class subscripted gives (`K[int]`, `Generic[T]`), as typing.py's.
 */
class PyGenericAlias(@JvmField val origin: Any?, @JvmField val args: PyTuple, @JvmField val starred: Boolean = false,
                     @JvmField val typing: Boolean = false) : PyValue() {
    override fun equals(other: Any?) = other is PyGenericAlias && starred == other.starred && typing == other.typing &&
        PyRt.eq(origin, other.origin) && PyRt.eq(args, other.args)

    override fun hashCode() = PyRt.hash(origin) xor PyRt.hash(args)
}

/** typing.Union (3.14's `X | Y` of types): its [args] flattened, without duplicates, None as NoneType. */
class PyUnion(@JvmField val args: PyTuple) {
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

/** typing.Unpack[Ts]: what iterating a TypeVarTuple gives (`*Ts`). */
class PyUnpacked(@JvmField val arg: Any?) {
    override fun equals(other: Any?) = other is PyUnpacked && PyRt.eq(arg, other.arg)
    override fun hashCode() = PyRt.hash(arg) xor 0x55
}

/** typing.NoDefault: a type parameter's default where it has none. */
object PyNoDefault

/** typing.Unpack, the special form (only its repr and its subscription's results here). */
object PyUnpackForm

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
    @JvmField val UNPACK_ALIAS = typing("_UnpackGenericAlias")
    @JvmField val NODEFAULT = typing("NoDefaultType")
    @JvmField val UNPACK_FORM = typing("_SpecialForm")
    @JvmField val TYPE_ALIAS = typing("TypeAliasType")
    @JvmField val TYPING_ALIAS = typing("_GenericAlias")
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
        if (!GENERIC.dict.containsKey(PyStr("__class_getitem__"))) {
            GENERIC.def("__class_getitem__", PyClassMethod(PyBuiltin("__class_getitem__") { _, a ->
                if (a.size != 2) throw PyRt.typeError("__class_getitem__() takes exactly one argument (${a.size - 1} given)")
                genericClassGetItem(a[0] as PyType, a[1])
            }))
            GENERIC.def("__init_subclass__", PyClassMethod(PyBuiltin("__init_subclass__", { _, a -> genericInitSubclass(a[0] as PyType); PyNone },
                { _, a, _ -> throw PyRt.typeError("${(a[0] as PyType).name}.__init_subclass__() takes no keyword arguments") })))
        }
        for (t in genericTypes()) {
            if (t.dict.containsKey(PyStr("__class_getitem__"))) continue
            t.def("__class_getitem__", PyClassMethod(PyBuiltin("__class_getitem__") { _, a ->
                if (a.size != 2) throw PyRt.typeError("__class_getitem__() takes exactly one argument (${a.size - 1} given)")
                PyGenericAlias(a[0], argsOf(a[1]))
            }))
        }
    }

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

    /** ga[key], a union[key]: its type variables replaced (M9b); none left is CPython's error. */
    @JvmStatic
    fun aliasGetItem(alias: Any?, key: Any?): Any? {
        val params = parameters(alias)
        if (params.items.isEmpty()) throw PyRt.typeError("${PyRt.repr(alias)} is not a generic class")
        return substitute(alias, params, argsOf(key))
    }

    /**
     * What [alias]'s type variables become with [args] (_Py_subs_parameters): a missing one its default (a type
     * variable's __typing_prepare_subst__), a TypeVarTuple the arguments between those before and after it.
     */
    private fun substitute(alias: Any?, params: PyTuple, args: PyTuple): Any? {
        val ps = params.items
        val items = ArrayList(args.items.asList())
        val tvt = ps.indexOfFirst { it is PyTypeParam && it.kind == PyTypeParam.TYPEVARTUPLE }
        val map = HashMap<Any?, Any?>()
        if (tvt >= 0) {
            val after = ps.size - tvt - 1
            if (items.size < ps.size - 1) {
                throw PyRt.typeError("Too few arguments for ${PyRt.str(alias)}; actual ${items.size}, expected at least ${ps.size - 1}")
            }
            for (i in 0 until tvt) map[ps[i]] = items[i]
            map[ps[tvt]] = Spread(items.subList(tvt, items.size - after).toList())
            for (i in 0 until after) map[ps[tvt + 1 + i]] = items[items.size - after + i]
            return replace(alias, map)
        }
        for ((i, p) in ps.withIndex()) {
            if (i < items.size) continue
            if (i == items.size && p is PyTypeParam && hasDefault(p)) {
                items.add(defaultOf(p))
                continue
            }
            throw PyRt.typeError("Too few arguments for ${PyRt.str(alias)}; actual ${items.size}, expected at least ${i + 1}")
        }
        if (items.size != ps.size) {
            throw PyRt.typeError("Too many arguments for ${PyRt.repr(alias)}; actual ${items.size}, expected ${ps.size}")
        }
        for (i in ps.indices) map[ps[i]] = items[i]
        return replace(alias, map)
    }

    /** A TypeVarTuple's value: the arguments `*Ts` stands for, spread where it is. */
    private class Spread(val items: List<Any?>)

    private fun replace(v: Any?, map: Map<Any?, Any?>): Any? = when {
        v is PyTypeParam && map.containsKey(v) -> map[v]
        v is PyGenericAlias -> PyGenericAlias(v.origin, PyTuple(replaceAll(v.args.items.asList(), map).toTypedArray()), v.starred)
        v is PyUnion -> unionOf(replaceAll(v.args.items.asList(), map))
        v is PyList -> PyList(ArrayList(replaceAll(v.items, map)))
        else -> v
    }

    private fun replaceAll(args: List<Any?>, map: Map<Any?, Any?>): List<Any?> {
        val out = ArrayList<Any?>()
        for (a in args) {
            val r = if (a is PyUnpacked && map.containsKey(a.arg)) map[a.arg] else replace(a, map)
            if (r is Spread) out.addAll(r.items) else out.add(r)
        }
        return out
    }

    /** `__parameters__`: the type variables in [alias]'s arguments, each once, in order (nested aliases' too). */
    @JvmStatic
    fun parameters(alias: Any?): PyTuple {
        val args = when (alias) {
            is PyGenericAlias -> alias.args.items
            is PyUnion -> alias.args.items
            else -> return PyTuple.EMPTY
        }
        val out = ArrayList<Any?>()
        fun collect(a: Any?) {
            when {
                isTypeVar(a) -> if (out.none { it === a }) out.add(a)
                a is PyGenericAlias || a is PyUnion -> for (p in parameters(a).items) if (out.none { it === p }) out.add(p)
                a is PyUnpacked -> collect(a.arg)
                a is PyList -> for (x in a.items) collect(x)
            }
        }
        for (a in args) collect(a)
        return PyTuple(out.toTypedArray())
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

    /** typing.py's _type_repr: a class by its qualified name, `...`, a function by its name, a tuple as a list. */
    private fun typeRepr(p: Any?): String = when {
        p is PyType -> if (p.module == "builtins") p.qualname else "${p.module}.${p.qualname}"
        p === PyEllipsis -> "..."
        p is PyFunction -> p.name
        p is PyTuple && p.sub == null -> p.items.joinToString(", ", "[", "]") { typeRepr(it) }
        else -> PyRt.repr(p)
    }

    @JvmStatic
    fun repr(v: Any?): String = when (v) {
        is PyGenericAlias -> if (v.typing) typeRepr(v.origin) + "[" + (if (v.args.items.isEmpty()) "()" else v.args.items.joinToString(", ") { typeRepr(it) }) + "]"
            else gaRepr(v)
        // (a PEP 695 type variable's variance is inferred: no +, - or ~)
        is PyTypeParam -> v.name
        is PyParamSpecPart -> v.origin.name + if (v.kwargs) ".kwargs" else ".args"
        is PyUnpacked -> "typing.Unpack[${reprItem(v.arg)}]"
        is PyTypeAlias -> v.name
        PyNoDefault -> "typing.NoDefault"
        PyUnpackForm -> "typing.Unpack"
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
        if (ga.typing) return typingAliasAttr(ga, name)
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

    /** A typing alias's: its own, its origin's name, its origin's other attributes but dunders (typing.py's __getattr__). */
    private fun typingAliasAttr(ga: PyGenericAlias, name: String): Any? = when (name) {
        "__origin__" -> ga.origin
        "__args__" -> ga.args
        "__parameters__" -> parameters(ga)
        "__class__" -> TYPING_ALIAS
        "__name__", "__qualname__" -> PyRt.getAttr(ga.origin, name)
        "__module__" -> PyRt.getAttr(ga.origin, "__module__")
        "__mro_entries__" -> PyBuiltin("__mro_entries__") { _, a -> typingMroEntries(ga, (a[0] as PyTuple).items) }
        else -> if (name.startsWith("__") && name.endsWith("__")) throw PyException("AttributeError", name) else PyRt.getAttr(ga.origin, name)
    }

    /** typing._GenericAlias.__mro_entries__: Generic[...]'s nothing where a later base is a generic alias, else the origin. */
    private fun typingMroEntries(ga: PyGenericAlias, bases: Array<Any?>): PyTuple {
        if (ga.origin === GENERIC) {
            // (by equality: typing caches its subscriptions, so equal ones are one object there)
            val i = bases.indexOfFirst { PyRt.eq(it, ga) }
            for (b in bases.drop(i + 1)) if (b is PyGenericAlias && b.typing && !PyRt.eq(b, ga)) return PyTuple.EMPTY
        }
        return PyTuple(arrayOf(ga.origin))
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

    // ------------------------------------------------------------------ Generic (typing.py's, natively)

    /** SUBSCRIPT_GENERIC: a generic class's base, Generic[*its type parameters]. */
    @JvmStatic fun subscriptGeneric(params: Any?): Any? = genericClassGetItem(GENERIC, params)

    /**
     * cls[args] of Generic or a generic class (typing._generic_class_getitem): Generic's only of unique type
     * variables; a class's checked against its __parameters__ (each type variable's __typing_prepare_subst__: a
     * missing one's default, a TypeVarTuple's arguments, a ParamSpec's list), a typing._GenericAlias.
     */
    @JvmStatic
    fun genericClassGetItem(cls: PyType, key: Any?): Any? {
        var args = argsOf(key).items.map { if (it === PyNone) PyTypes.NONE else it }
        if (cls === GENERIC) {
            if (args.isEmpty()) throw PyRt.typeError("Parameter list to Generic[...] cannot be empty")
            if (!args.all { it is PyTypeParam }) {
                throw PyRt.typeError("Parameters to Generic[...] must all be type variables or parameter specification variables.")
            }
            if (args.distinct().size != args.size) throw PyRt.typeError("Parameters to Generic[...] must all be unique")
            return PyGenericAlias(cls, PyTuple(args.toTypedArray()), typing = true)
        }
        val params = (PyClasses.typeAttr(cls, "__parameters__") as? PyTuple)?.items ?: emptyArray()
        val name = PyRt.str(cls)
        for (p in params) if (p is PyTypeParam) args = prepareSubst(p, name, params, args)
        checkSpecialization(name, params, args)
        val out = ArrayList<Any?>()
        for ((i, p) in params.withIndex()) {
            if (i >= args.size) break
            val a = args[i]
            if (p is PyTypeParam && p.kind == PyTypeParam.TYPEVARTUPLE && a is PyTuple) out.addAll(a.items) else out.add(a)
        }
        return PyGenericAlias(cls, PyTuple(out.toTypedArray()), typing = true)
    }

    private fun prepareSubst(p: PyTypeParam, alias: String, params: Array<Any?>, args0: List<Any?>): List<Any?> {
        val i = params.indexOfFirst { it === p }
        var args = args0
        when (p.kind) {
            PyTypeParam.TYPEVAR -> {
                if (i < args.size) return args
                if (i == args.size && hasDefault(p)) return args + defaultOf(p)
                throw PyRt.typeError("Too few arguments for $alias; actual ${args.size}, expected at least ${i + 1}")
            }
            PyTypeParam.TYPEVARTUPLE -> {
                val left = i
                val right = params.size - i - 1
                if (left + right > args.size) throw PyRt.typeError("Too few arguments for $alias; actual ${args.size}, expected at least ${params.size - 1}")
                val replacement = if (left == args.size - right && hasDefault(p)) {
                    val d = defaultOf(p)
                    PyTuple((if (d is PyGenericAlias && d.starred) d.args.items else arrayOf(d)))
                } else PyTuple(args.subList(left, args.size - right).toTypedArray())
                return args.subList(0, left) + listOf(replacement) + args.subList(args.size - right, args.size)
            }
            else -> {
                if (i == args.size && hasDefault(p)) args = args + defaultOf(p)
                if (i >= args.size) throw PyRt.typeError("Too few arguments for $alias")
                // (Z[int, str] is Z[[int, str]], PEP 612; a list kept as a tuple)
                if (params.size == 1 && !isParamExpr(args[0])) return listOf(PyTuple(args.toTypedArray()))
                val a = args[i]
                if (a is PyList) return args.toMutableList().also { it[i] = PyTuple(a.items.toTypedArray()) }
                return args
            }
        }
    }

    private fun isParamExpr(v: Any?): Boolean =
        v === PyEllipsis || v is PyList || v is PyTuple || v is PyTypeParam && v.kind == PyTypeParam.PARAMSPEC

    private fun checkSpecialization(name: String, params: Array<Any?>, args: List<Any?>) {
        var expected = params.size
        if (expected == 0) throw PyRt.typeError("$name is not a generic class")
        val actual = args.size
        if (actual == expected) return
        val expect = if (actual < expected) {
            if ((params[actual] as? PyTypeParam)?.let { hasDefault(it) } == true) return
            expected -= params.count { it is PyTypeParam && hasDefault(it) }
            "at least $expected"
        } else "$expected"
        throw PyRt.typeError("Too ${if (actual > expected) "many" else "few"} arguments for $name; actual $actual, expected $expect")
    }

    /**
     * Generic.__init_subclass__ (typing._generic_init_subclass): its __parameters__, the type variables of its
     * original bases, those of a Generic[...] among them where there is one; plain Generic, two Generic[...]s and
     * type variables Generic[...] leaves out CPython's errors.
     */
    @JvmStatic
    fun genericInitSubclass(cls: PyType) {
        val orig = (cls.dict[PyStr("__orig_bases__")] as? PyTuple)?.items
        val plain = if (orig != null) orig.any { it === GENERIC } else cls.bases.any { it === GENERIC } && cls.name != "Protocol"
        if (plain) throw PyRt.typeError("Cannot inherit from plain Generic")
        var tvars = emptyList<Any?>()
        if (orig != null) {
            tvars = parameters(PyGenericAlias(GENERIC, PyTuple(orig.filter { it !is PyType }.toTypedArray()))).items.toList()
            var gvars: List<Any?>? = null
            for (b in orig) if (b is PyGenericAlias && b.typing && b.origin === GENERIC) {
                if (gvars != null) throw PyRt.typeError("Cannot inherit from Generic[...] multiple times.")
                gvars = parameters(b).items.toList()
            }
            if (gvars != null) {
                val missing = tvars.filter { t -> gvars.none { it === t } }
                if (missing.isNotEmpty()) {
                    throw PyRt.typeError("Some type variables (${missing.joinToString(", ") { PyRt.str(it) }}) are not listed in Generic[${gvars.joinToString(", ") { PyRt.str(it) }}]")
                }
                tvars = gvars
            }
        }
        cls.dict[PyStr("__parameters__")] = PyTuple(tvars.toTypedArray())
    }

    // ------------------------------------------------------------------ type parameters (PEP 695, 696)

    /** MAKE_TYPEVAR [kind] (TypeVar, TypeVarTuple, ParamSpec): one named [name]. */
    @JvmStatic fun makeTypeVar(name: Any?, kind: Int): Any? = PyTypeParam(kind, (name as PyStr).s)

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
        is PyUnpacked -> UNPACK_ALIAS
        is PyTypeAlias -> TYPE_ALIAS
        PyNoDefault -> NODEFAULT
        PyUnpackForm -> UNPACK_FORM
        else -> null
    }

    /** A type parameter's attributes (typevarobject.c's members and getters), then those set on it. */
    @JvmStatic
    fun typeParamAttr(t: PyTypeParam, name: String): Any? {
        val tv = t.kind == PyTypeParam.TYPEVAR
        when (name) {
            "__name__" -> return PyStr(t.name)
            "__module__" -> return PyStr("typing")
            "__class__" -> return typeParamType(t)
            "__default__" -> return defaultOf(t)
            "evaluate_default" -> return t.evaluateDefault ?: PyNone
            "has_default" -> return PyBuiltin("has_default") { _, _ -> PyBool.of(hasDefault(t)) }
            "__typing_subst__" -> return PyBuiltin("__typing_subst__") { _, a -> a.getOrNull(0) }
        }
        if (t.kind != PyTypeParam.TYPEVARTUPLE) when (name) {
            "__covariant__", "__contravariant__" -> return PyBool.FALSE
            "__infer_variance__" -> return PyBool.TRUE
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

    /** iter(Ts): its one unpacked form, typing.Unpack[Ts]; the other type parameters are not iterable. */
    @JvmStatic
    fun typeParamIter(t: PyTypeParam): PyIterator {
        if (t.kind != PyTypeParam.TYPEVARTUPLE) throw PyRt.typeError("'${typeParamType(t).tpName}' object is not iterable")
        var done = false
        return PyIterator { if (done) PyIterator.STOP else { done = true; PyUnpacked(t) } }
    }

    @JvmStatic
    fun partAttr(v: Any?, name: String): Any? = when (v) {
        is PyParamSpecPart -> when (name) {
            "__origin__" -> v.origin
            "__class__" -> typeOf(v)
            else -> throw PyException("AttributeError", "'${typeOf(v)!!.tpName}' object has no attribute '$name'")
        }
        is PyUnpacked -> when (name) {
            "__origin__" -> PyUnpackForm
            "__args__" -> PyTuple(arrayOf(v.arg))
            "__parameters__" -> if (v.arg is PyTypeParam) PyTuple(arrayOf(v.arg)) else parameters(v.arg)
            "__class__" -> UNPACK_ALIAS
            else -> throw PyException("AttributeError", "'_UnpackGenericAlias' object has no attribute '$name'")
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
        "__parameters__" -> a.typeParams?.let { tp ->
            PyTuple(Array(tp.items.size) { i -> tp.items[i].let { if (it is PyTypeParam && it.kind == PyTypeParam.TYPEVARTUPLE) PyUnpacked(it) else it } })
        } ?: PyTuple.EMPTY
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
            val me = if (b is PyGenericAlias && b.typing) {
                out = out ?: ArrayList<Any?>().also { for (j in 0 until i) it.add(bases[j]) }
                out.addAll(typingMroEntries(b, bases).items)
                continue
            } else if (b is PyGenericAlias) aliasAttr(b, "__mro_entries__") else try {
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
