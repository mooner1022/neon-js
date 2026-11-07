package dev.mooner.neonpython

/**
 * A builtin type's attribute as the type gives it (FunctionType.__code__): a getset_descriptor or a
 * member_descriptor, read-only where CPython's is. Its instances' attributes stay where the runtime keeps them
 * (PyClasses); this is what the class shows of them (types.GetSetDescriptorType, inspect).
 */
class PyAttrDescr(type: PyType, @JvmField val owner: PyType, @JvmField val name: String, @JvmField val readonly: Boolean) : PyObject(type) {
    /** What reads and sets the attribute where it is not the instance's generic getattr's (type's own, whose lookups would come back here). */
    @JvmField var getter: ((Any?) -> Any?)? = null
    @JvmField var setter: ((Any?, Any?) -> Unit)? = null
    @JvmField var deleter: ((Any?) -> Unit)? = null
    /** Its __doc__ (CPython's getset's), or null for None. */
    @JvmField var doc: String? = null
}

object PyAttrDescrs {
    private val NO_ARGS = arrayOfNulls<Any?>(0)

    @JvmField val GETSET: PyType = PyTypes.newBuiltin("getset_descriptor", subclassable = false)
    // (one member_descriptor type with the slots' members: PySlots' handles both kinds)
    @JvmField val MEMBER: PyType = PySlots.MEMBER

    /** Each type's: name to (a member, read-only), as CPython 3.14's. */
    private val TABLE: Map<PyType, Map<String, Pair<Boolean, Boolean>>> = mapOf(
        PyTypes.FUNCTION to mapOf(
            "__annotate__" to (false to false), "__annotations__" to (false to false), "__builtins__" to (true to true),
            "__closure__" to (true to true), "__code__" to (false to false), "__defaults__" to (false to false),
            "__dict__" to (false to false), "__doc__" to (true to false), "__globals__" to (true to true),
            "__kwdefaults__" to (false to false), "__module__" to (true to false), "__name__" to (false to false),
            "__qualname__" to (false to false), "__type_params__" to (false to false),
        ),
        PyEval.CODE to (listOf("co_argcount", "co_consts", "co_exceptiontable", "co_filename", "co_firstlineno", "co_flags",
            "co_kwonlyargcount", "co_linetable", "co_name", "co_names", "co_nlocals", "co_posonlyargcount", "co_qualname",
            "co_stacksize").associateWith { true to true } +
            listOf("co_cellvars", "co_code", "co_freevars", "co_lnotab", "co_varnames").associateWith { false to true }),
    )

    /** The methods a builtin type's instances have where the runtime makes them (a code object's), for dir(). */
    private val METHODS: Map<PyType, List<String>> = mapOf(PyEval.CODE to listOf("__replace__", "co_lines", "co_positions", "replace"),
        // (object's __class__, a getset in CPython's dict; NeonPy's attribute lookups answer it themselves; so the
        // attributes of a property, a staticmethod and classmethod, a method and super)
        PyTypes.OBJECT to listOf("__class__"),
        PyTypes.PROPERTY to listOf("fget", "fset", "fdel", "__isabstractmethod__", "__name__"),
        PyTypes.STATICMETHOD to listOf("__func__", "__wrapped__", "__isabstractmethod__", "__dict__", "__annotations__", "__annotate__"),
        PyTypes.CLASSMETHOD to listOf("__func__", "__wrapped__", "__isabstractmethod__", "__dict__", "__annotations__", "__annotate__"),
        PyTypes.METHOD to listOf("__func__", "__self__"),
        PyTypes.SUPER to listOf("__thisclass__", "__self__", "__self_class__"))

    /** The names of [t]'s attributes kept here, not in its dict (dir() lists them, as CPython's type dict has them). */
    @JvmStatic
    fun names(t: PyType): List<String> = (TABLE[t]?.keys?.toList() ?: emptyList()) + (METHODS[t] ?: emptyList())

    // (one each: FunctionType.__code__ is FunctionType.__code__; every context's, as the types are)
    private val made = java.util.concurrent.ConcurrentHashMap<Pair<PyType, String>, PyAttrDescr>()

    /**
     * type's own getset descriptors, in its dict (`type.__dict__["__annotations__"].__get__`, annotationlib's and
     * inspect's way to read a class's own): each reads what type's attribute lookup gives for the name.
     */
    @JvmStatic
    fun defineTypeDescriptors() {
        val t = PyTypes.TYPE
        fun def(n: String, readonly: Boolean, get: (PyType) -> Any?, set: ((PyType, Any?) -> Unit)? = null) {
            val d = PyAttrDescr(GETSET, t, n, readonly)
            d.getter = { o -> get(o as PyType) }
            if (set != null) d.setter = { o, v -> set(o as PyType, v) }
            t.dict[PyStr(n)] = d
        }
        def("__annotations__", false, { PyAnnotations.typeGet(it, "__annotations__") }) { c, v -> PyAnnotations.typeSet(c, "__annotations__", v) }
        def("__annotate__", false, { PyAnnotations.typeGet(it, "__annotate__") }) { c, v -> PyAnnotations.typeSet(c, "__annotate__", v) }
        def("__mro__", true, { PyTuple(arrayOf(*it.mro)) })
        def("__dict__", true, { PyMappingProxies.of(it.dict) })
        def("__name__", false, { PyStr(it.name) }) { c, v -> PyClasses.setAttr(c, "__name__", v) }
        def("__qualname__", false, { PyStr(it.qualname) }) { c, v -> PyClasses.setAttr(c, "__qualname__", v) }
        def("__bases__", true, { PyTuple(arrayOf(*it.bases)) })
        // (a class's own, not these descriptors in type's dict)
        def("__module__", false, { c -> (c.dict[PyStr("__module__")] as? PyStr) ?: PyStr(c.module) }) { c, v ->
            if (c.module == "builtins" && !c.heap) throw PyRt.typeError("cannot set '__module__' attribute of immutable type '${c.name}'")
            c.dict[PyStr("__module__")] = v
            (v as? PyStr)?.let { c.module = it.s }
        }
        def("__type_params__", false, { c -> (c.dict[PyStr("__type_params__")] as? PyTuple) ?: PyTuple.EMPTY }) { c, v -> PyClasses.setAttr(c, "__type_params__", v) }
    }

    /**
     * A class's own `__dict__` and `__weakref__` getset descriptors, where it adds a dict or weak references to its
     * bases' layout (CPython's subtype_getsets), unless its namespace has the name: the instance's dict, read, replaced
     * and emptied; its first weak reference (or None), read only.
     */
    @JvmStatic
    fun defineInstanceDescriptors(t: PyType, dict: Boolean, weakref: Boolean) {
        if (dict && !t.dict.containsKey(PyStr("__dict__"))) {
            val d = PyAttrDescr(GETSET, t, "__dict__", false)
            d.doc = "dictionary for instance variables"
            d.getter = { o -> PyClasses.ownDict(o) }
            d.setter = { o, v -> PyClasses.replaceDict(o, v) }
            d.deleter = { o -> PyClasses.replaceDict(o, PyDict()) }
            t.dict[PyStr("__dict__")] = d
        }
        if (weakref && !t.dict.containsKey(PyStr("__weakref__"))) {
            val d = PyAttrDescr(GETSET, t, "__weakref__", true)
            d.doc = "list of weak references to the object"
            d.getter = { o -> PyWeakrefs.firstRef(o) ?: PyNone }
            t.dict[PyStr("__weakref__")] = d
        }
    }

    /** [t]'s descriptor of attribute [name], or null. */
    @JvmStatic
    fun of(t: PyType, name: String): PyAttrDescr? {
        val spec = TABLE[t]?.get(name) ?: return null
        return made.computeIfAbsent(t to name) { PyAttrDescr(if (spec.first) MEMBER else GETSET, t, name, spec.second) }
    }

    private fun self(a: Array<Any?>): PyAttrDescr = a[0] as PyAttrDescr

    private fun target(d: PyAttrDescr, obj: Any?): Any? {
        if (!PyTypes.typeOf(obj).isSubtype(d.owner)) {
            throw PyRt.typeError("descriptor '${d.name}' for '${d.owner.tpName ?: d.owner.name}' objects doesn't apply to a '${PyTypes.name(obj)}' object")
        }
        return obj
    }

    /** A read-only descriptor's AttributeError: a member's "readonly attribute", a getset's (no setter) its own. */
    private fun readonly(d: PyAttrDescr): PyException = PyException("AttributeError",
        if (d.type === MEMBER) "readonly attribute" else "attribute '${d.name}' of '${d.owner.tpName ?: d.owner.name}' objects is not writable")

    /** A builtin type's getset or member descriptor's __get__ (args: the descriptor, the object, its type). */
    @JvmStatic
    fun get(a: Array<Any?>): Any? {
        val d = self(a)
        val obj = a.getOrNull(1)
        return if (obj == null || obj === PyNone) d else d.getter?.invoke(target(d, obj)) ?: PyRt.getAttr(target(d, obj), d.name)
    }

    @JvmStatic
    fun set(a: Array<Any?>): Any? {
        val d = self(a)
        if (d.readonly) throw readonly(d)
        val s = d.setter
        if (s != null) s(target(d, a[1]), a[2]) else PyClasses.setAttr(target(d, a[1]), d.name, a[2])
        return PyNone
    }

    @JvmStatic
    fun delete(a: Array<Any?>): Any? {
        val d = self(a)
        if (d.readonly) throw readonly(d)
        val del = d.deleter
        if (del != null) del(target(d, a[1])) else PyClasses.delAttr(target(d, a[1]), d.name)
        return PyNone
    }

    init {
        for (t in listOf(GETSET)) {
            t.dispatchBuiltins = true
            t.def("__get__", PyBuiltin("__get__") { _, a -> get(a) })
            t.def("__set__", PyBuiltin("__set__") { _, a -> set(a) })
            t.def("__delete__", PyBuiltin("__delete__") { _, a -> delete(a) })
            t.def("__repr__", PyBuiltin("__repr__") { _, a ->
                val d = self(a)
                PyStr("<${if (d.type === MEMBER) "member" else "attribute"} '${d.name}' of '${d.owner.tpName ?: d.owner.name}' objects>")
            })
            fun prop(n: String, get: (PyAttrDescr) -> Any?) = t.def(n, PyProperty(PyBuiltin(n) { _, a -> get(self(a)) }, null, null, null).also { it.name = n })
            prop("__name__") { PyStr(it.name) }
            prop("__qualname__") { PyStr("${it.owner.qualname}.${it.name}") }
            prop("__objclass__") { it.owner }
            prop("__doc__") { d -> d.doc?.let { PyStr(it) } ?: PyNone }
            // (no attributes of their own: they are every context's)
            t.def("__setattr__", PyBuiltin("__setattr__") { _, a ->
                throw PyException("AttributeError", "'${t.name}' object has no attribute '${(a[1] as PyStr).s}' and no __dict__ for setting new attributes")
            })
            t.def("__delattr__", PyBuiltin("__delattr__") { _, a ->
                throw PyException("AttributeError", "'${t.name}' object has no attribute '${(a[1] as PyStr).s}'")
            })
            t.hooks = 2 or 4
        }
    }
}
