package dev.mooner.neonpython

/**
 * types.GenericAlias (PEP 585): a type subscripted, `list[int]`; [starred] the `*tuple[int, ...]` that iterating
 * one gives (PEP 646). CPython's Objects/genericaliasobject.c.
 */
class PyGenericAlias(@JvmField val origin: Any?, @JvmField val args: PyTuple, @JvmField val starred: Boolean = false) {
    override fun equals(other: Any?) =
        other is PyGenericAlias && starred == other.starred && PyRt.eq(origin, other.origin) && PyRt.eq(args, other.args)

    override fun hashCode() = PyRt.hash(origin) xor PyRt.hash(args)
}

/** typing.Union (3.14's `X | Y` of types): its [args] flattened, without duplicates, None as NoneType. */
class PyUnion(@JvmField val args: PyTuple) {
    override fun equals(other: Any?) = other is PyUnion && args.items.size == other.args.items.size &&
        args.items.all { a -> other.args.items.any { PyRt.eq(a, it) } }

    override fun hashCode() = args.items.fold(0x2f1b) { h, a -> h xor PyRt.hash(a) }
}

/**
 * The runtime of type expressions the syntax makes (docs/PYTHON_PLATFORM.md §11, M9): generic aliases, unions, and
 * the classes their subscriptions and `|` reach. What CPython implements in C; none of the typing module's own code.
 */
object PyTyping {
    private val NO_ARGS = arrayOfNulls<Any?>(0)

    @JvmField val GENERIC_ALIAS = PyTypes.newBuiltin("GenericAlias", subclassable = false).also {
        it.module = "types"
        it.tpName = "types.GenericAlias"
    }
    @JvmField val UNION = PyTypes.newBuiltin("Union", subclassable = false).also {
        it.module = "typing"
        it.tpName = "typing.Union"
    }

    /** The builtin types whose `__class_getitem__` gives a GenericAlias (`type[...]`'s is type's own case). */
    private fun genericTypes(): List<PyType> = listOf(PyTypes.LIST, PyTypes.DICT, PyTypes.SET, PyTypes.FROZENSET, PyTypes.TUPLE,
        PyTypes.CLASSMETHOD, PyTypes.STATICMETHOD, PyExceptions.type("BaseExceptionGroup"), PyExceptions.type("ExceptionGroup"))

    @JvmStatic
    fun define() {
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

    /** What [alias]'s type variables become with [args] (TypeVars, M9b's): their count checked as CPython does. */
    private fun substitute(alias: Any?, params: PyTuple, args: PyTuple): Any? {
        val n = params.items.size
        if (args.items.size != n) {
            throw PyRt.typeError("Too ${if (args.items.size > n) "many" else "few"} arguments for ${PyRt.repr(alias)}; actual ${args.items.size}, expected $n")
        }
        val map = HashMap<Any?, Any?>()
        for (i in 0 until n) map[params.items[i]] = args.items[i]
        return replace(alias, map)
    }

    private fun replace(v: Any?, map: Map<Any?, Any?>): Any? = when {
        map.containsKey(v) -> map[v]
        v is PyGenericAlias -> PyGenericAlias(v.origin, PyTuple(Array(v.args.items.size) { replace(v.args.items[it], map) }), v.starred)
        v is PyUnion -> unionOf(v.args.items.map { replace(it, map) })
        v is PyList -> PyList(ArrayList(v.items.map { replace(it, map) }))
        else -> v
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
                a is PyList -> for (x in a.items) collect(x)
            }
        }
        for (a in args) collect(a)
        return PyTuple(out.toTypedArray())
    }

    /** Whether [v] is a type variable (TypeVar, ParamSpec, TypeVarTuple: M9b's type parameters). */
    @JvmStatic fun isTypeVar(v: Any?): Boolean = false

    // ------------------------------------------------------------------ unions

    /** Whether `|` makes a union of [v] (Py_union_type_or's is_unionable). */
    private fun unionable(v: Any?): Boolean = v === PyNone || v is PyType || v is PyGenericAlias || v is PyUnion

    /** a | b where either is a type, an alias or a union (else null: the operator's TypeError). */
    @JvmStatic
    fun or(a: Any?, b: Any?): Any? {
        if (!(a is PyType || a is PyGenericAlias || a is PyUnion || b is PyType || b is PyGenericAlias || b is PyUnion)) return null
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
        is PyGenericAlias -> buildString {
            if (v.starred) append('*')
            append(reprItem(v.origin)).append('[')
            for ((i, a) in v.args.items.withIndex()) {
                if (i > 0) append(", ")
                append(if (a is PyList && a.sub == null) a.items.joinToString(", ", "[", "]") { reprItem(it) } else reprItem(a))
            }
            if (v.args.items.isEmpty()) append("()")
            append(']')
        }
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
