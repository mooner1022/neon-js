package dev.mooner.neonpython

/** types.MappingProxyType: a read-only view of [mapping] (a class's `__dict__`, enum's `__members__`). */
class PyMappingProxy(type: PyType, @JvmField val mapping: Any?) : PyObject(type)

object PyMappingProxies {
    private val NO_ARGS = arrayOfNulls<Any?>(0)

    @JvmField val MAPPINGPROXY: PyType = PyTypes.newBuiltin("mappingproxy", subclassable = false).also { it.dispatchBuiltins = true }

    @JvmStatic fun of(mapping: Any?): PyMappingProxy = PyMappingProxy(MAPPINGPROXY, mapping)

    private fun m(a: Array<Any?>): Any? = (a[0] as PyMappingProxy).mapping

    private fun call(ctx: PyContext, obj: Any?, name: String, vararg a: Any?): Any? =
        PyCalls.callWith(ctx, PyRt.getAttr(obj, name), arrayOf(*a), NO_ARGS, NO_ARGS)

    init {
        val t = MAPPINGPROXY
        t.newImpl = { _, _, a, kw ->
            val v = a.getOrNull(0) ?: kw?.get(PyStr("mapping")) ?: throw PyRt.typeError("mappingproxy() missing required argument 'mapping' (pos 1)")
            // (CPython's PyMapping_Check, a list and a tuple refused)
            val mapping = v is PyDict || v is PyMappingProxy || v is PyObject && v.type.lookup("__getitem__") != null && v.type.lookup("keys") != null
            if (!mapping || v is PyList || v is PyTuple) throw PyRt.typeError("mappingproxy() argument must be a mapping, not ${PyTypes.name(v)}")
            of(v)
        }
        fun def(n: String, impl: (PyContext, Array<Any?>) -> Any?) = t.def(n, PyBuiltin(n) { ctx, a -> impl(ctx, a) })
        def("__getitem__") { _, a -> PyRt.getItem(m(a), a[1]) }
        def("__len__") { _, a -> PyRt.len(m(a)) }
        def("__iter__") { _, a -> PyRt.iter(m(a)) }
        def("__contains__") { _, a -> PyBool.of(PyRt.contains(m(a), a[1])) }
        def("__reversed__") { ctx, a -> PyCalls.callWith(ctx, ctx.builtins[PyStr("reversed")], arrayOf(m(a)), NO_ARGS, NO_ARGS) }
        def("get") { ctx, a -> call(ctx, m(a), "get", a.getOrNull(1), a.getOrNull(2) ?: PyNone) }
        def("keys") { ctx, a -> call(ctx, m(a), "keys") }
        def("values") { ctx, a -> call(ctx, m(a), "values") }
        def("items") { ctx, a -> call(ctx, m(a), "items") }
        def("copy") { ctx, a -> call(ctx, m(a), "copy") }
        def("__eq__") { _, a -> PyBool.of(PyRt.eq(m(a), a[1])) }
        def("__ne__") { _, a -> PyBool.of(!PyRt.eq(m(a), a[1])) }
        def("__hash__") { _, a -> PyRt.hash(m(a)).toLong() }
        def("__repr__") { _, a -> PyStr("mappingproxy(${PyRt.repr(m(a))})") }
        def("__str__") { _, a -> PyStr(PyRt.str(m(a))) }
        // (| gives a dict: a copy of the mapping, the other's pairs in it)
        fun union(x: Any?, y: Any?): PyDict = PyBuiltins.dictFill(PyDict(), arrayOf(x), null).also { PyBuiltins.dictFill(it, arrayOf(y), null) }
        def("__or__") { _, a ->
            val o = a[1]
            if (o !is PyDict && o !is PyMappingProxy) PyNotImplemented else union(m(a), if (o is PyMappingProxy) o.mapping else o)
        }
        def("__ror__") { _, a -> val o = a[1]; if (o !is PyDict) PyNotImplemented else union(o, m(a)) }
        t.def("__class_getitem__", PyClassMethod(PyBuiltin("__class_getitem__") { _, a -> PyGenericAlias(a[0], PyTyping.argsOf(a[1])) }))
        // (no attributes of their own, and no items assigned: CPython's messages)
        t.def("__setattr__", PyBuiltin("__setattr__") { _, a ->
            throw PyException("AttributeError", "'mappingproxy' object has no attribute '${(a[1] as PyStr).s}' and no __dict__ for setting new attributes")
        })
        t.hooks = 2
    }
}
