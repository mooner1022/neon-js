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
}

object PyAttrDescrs {
    private val NO_ARGS = arrayOfNulls<Any?>(0)

    @JvmField val GETSET: PyType = PyTypes.newBuiltin("getset_descriptor", subclassable = false)
    @JvmField val MEMBER: PyType = PyTypes.newBuiltin("member_descriptor", subclassable = false)

    /** Each type's: name to (a member, read-only), as CPython 3.14's. */
    private val TABLE: Map<PyType, Map<String, Pair<Boolean, Boolean>>> = mapOf(
        PyTypes.FUNCTION to mapOf(
            "__annotate__" to (false to false), "__annotations__" to (false to false), "__builtins__" to (true to true),
            "__closure__" to (true to true), "__code__" to (false to false), "__defaults__" to (false to false),
            "__dict__" to (false to false), "__doc__" to (true to false), "__globals__" to (true to true),
            "__kwdefaults__" to (false to false), "__module__" to (true to false), "__name__" to (false to false),
            "__qualname__" to (false to false), "__type_params__" to (false to false),
        ),
    )

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

    /** [t]'s descriptor of attribute [name], or null. */
    @JvmStatic
    fun of(t: PyType, name: String): PyAttrDescr? {
        val spec = TABLE[t]?.get(name) ?: return null
        return made.computeIfAbsent(t to name) { PyAttrDescr(if (spec.first) MEMBER else GETSET, t, name, spec.second) }
    }

    private fun self(a: Array<Any?>): PyAttrDescr = a[0] as PyAttrDescr

    private fun target(d: PyAttrDescr, obj: Any?): Any? {
        if (!PyTypes.typeOf(obj).isSubtype(d.owner)) {
            throw PyRt.typeError("descriptor '${d.name}' for '${d.owner.name}' objects doesn't apply to a '${PyTypes.name(obj)}' object")
        }
        return obj
    }

    init {
        for (t in listOf(GETSET, MEMBER)) {
            t.dispatchBuiltins = true
            t.def("__get__", PyBuiltin("__get__") { _, a ->
                val d = self(a)
                val obj = a.getOrNull(1)
                if (obj == null || obj === PyNone) d else d.getter?.invoke(target(d, obj)) ?: PyRt.getAttr(target(d, obj), d.name)
            })
            t.def("__set__", PyBuiltin("__set__") { _, a ->
                val d = self(a)
                if (d.readonly) throw PyException("AttributeError", "readonly attribute")
                val s = d.setter
                if (s != null) s(target(d, a[1]), a[2]) else PyClasses.setAttr(target(d, a[1]), d.name, a[2])
                PyNone
            })
            t.def("__delete__", PyBuiltin("__delete__") { _, a ->
                val d = self(a)
                if (d.readonly) throw PyException("AttributeError", "readonly attribute")
                PyClasses.delAttr(target(d, a[1]), d.name)
                PyNone
            })
            t.def("__repr__", PyBuiltin("__repr__") { _, a ->
                val d = self(a)
                PyStr("<${if (d.type === MEMBER) "member" else "attribute"} '${d.name}' of '${d.owner.name}' objects>")
            })
            fun prop(n: String, get: (PyAttrDescr) -> Any?) = t.def(n, PyProperty(PyBuiltin(n) { _, a -> get(self(a)) }, null, null, null).also { it.name = n })
            prop("__name__") { PyStr(it.name) }
            prop("__qualname__") { PyStr("${it.owner.name}.${it.name}") }
            prop("__objclass__") { it.owner }
            prop("__doc__") { PyNone }
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
