package dev.mooner.neonpython

/**
 * A builtin type's attribute as the type gives it (FunctionType.__code__): a getset_descriptor or a
 * member_descriptor, read-only where CPython's is. Its instances' attributes stay where the runtime keeps them
 * (PyClasses); this is what the class shows of them (types.GetSetDescriptorType, inspect).
 */
class PyAttrDescr(type: PyType, @JvmField val owner: PyType, @JvmField val name: String, @JvmField val readonly: Boolean) : PyObject(type)

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
                if (obj == null || obj === PyNone) d else PyRt.getAttr(target(d, obj), d.name)
            })
            t.def("__set__", PyBuiltin("__set__") { _, a ->
                val d = self(a)
                if (d.readonly) throw PyException("AttributeError", "readonly attribute")
                PyClasses.setAttr(target(d, a[1]), d.name, a[2])
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
