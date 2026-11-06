package dev.mooner.neonpython

import dev.mooner.neonjs.vm.VmFrame

/**
 * Annotations evaluated when asked for (PEP 649 and 749, docs/PYTHON_PLATFORM.md §11, M9): a function's, a class's
 * and a module's `__annotate__(format)` gives their dict, which `__annotations__` caches. Their instructions, and
 * the attributes as CPython's function, type and module objects give them.
 */
object PyAnnotations {
    private val NO_ARGS = arrayOfNulls<Any?>(0)
    private val ANNOTATE = PyStr("__annotate__")
    private val ANNOTATE_FUNC = PyStr("__annotate_func__")
    private val ANNOTATIONS = PyStr("__annotations__")
    private val CACHE = PyStr("__annotations_cache__")

    @JvmStatic fun isName(name: String): Boolean = name == "__annotations__" || name == "__annotate__"

    // ------------------------------------------------------------------ the instructions

    /** ANNOTATE_CHECK: an `__annotate__`'s start: a format it cannot give (above VALUE_WITH_FAKE_GLOBALS, 2). */
    @JvmStatic
    fun annotateCheck(format: Any?) {
        if (PyRt.compare(PyOp.GT, format, 2L)) throw PyException("NotImplementedError", "")
    }

    /** SETUP_ANNOTATIONS: a module's or class body's `__annotations__` dict (`from __future__ import annotations`). */
    @JvmStatic
    fun setupAnnotations(f: VmFrame) {
        val ns = loadNamespace(f) as PyDict
        if (!ns.containsKey(ANNOTATIONS)) ns[ANNOTATIONS] = PyDict()
    }

    /** LOAD_NAMESPACE: a class body's namespace (its `__classdict__`), a module's globals. */
    @JvmStatic fun loadNamespace(f: VmFrame): Any? = (f as PyFrame).locals ?: f.globals

    /** LOAD_FROM_DICT_OR_GLOBALS: [name] in a class's namespace [dict], else a global or a builtin. */
    @JvmStatic
    fun loadFromDictOrGlobals(f: VmFrame, dict: Any?, name: Any?): Any? = lookup(dict, name) ?: PyRt.loadGlobal(f, name)

    /** LOAD_FROM_DICT_OR_CELL: [name] in a class's namespace [dict], else the enclosing function's [cell]. */
    @JvmStatic
    fun loadFromDictOrCell(dict: Any?, cell: Any?, name: Any?): Any? = lookup(dict, name) ?: PyCalls.cellGet(cell, name, 1)

    private fun lookup(dict: Any?, name: Any?): Any? {
        if (dict is PyDict) return PyClasses.namespaceGet(dict, name)
        return try {
            PyRt.getItem(dict, name)
        } catch (e: PyException) {
            if (e.matches(PyExceptions.type("KeyError"))) null else throw e
        }
    }

    // ------------------------------------------------------------------ the attributes

    /** `__annotate__(1)`: its dict, CPython's TypeError for anything else. */
    private fun call(annotate: Any?): PyDict {
        val r = PyCalls.callWith(PyContext.current(), annotate, arrayOf(1L), NO_ARGS, NO_ARGS)
        return r as? PyDict ?: throw PyRt.typeError("__annotate__ returned non-dict of type '${PyTypes.name(r)}'")
    }

    private fun checkAnnotate(v: Any?) {
        if (v !== PyNone && !PyCalls.isCallable(v)) throw PyRt.typeError("__annotate__ must be callable or None")
    }

    /** f.__annotations__ and f.__annotate__ (funcobject.c's getters). */
    @JvmStatic
    fun functionGet(f: PyFunction, name: String): Any? {
        if (name == "__annotate__") return f.annotate ?: PyNone
        f.annotations?.let { return it }
        val a = f.annotate
        val d = if (a == null || !PyCalls.isCallable(a)) PyDict() else call(a)
        f.annotations = d
        return d
    }

    /** Setting ([v]) or deleting (null) them: an `__annotations__` set drops `__annotate__`; a callable `__annotate__` the cache. */
    @JvmStatic
    fun functionSet(f: PyFunction, name: String, v: Any?) {
        if (name == "__annotate__") {
            if (v == null) throw PyRt.typeError("__annotate__ cannot be deleted")
            checkAnnotate(v)
            f.annotate = v
            if (v !== PyNone) f.annotations = null
            return
        }
        val d = if (v === PyNone) null else v
        if (d != null && d !is PyDict) throw PyRt.typeError("__annotations__ must be set to a dict object")
        f.annotations = d as PyDict?
        f.annotate = null
    }

    /** C.__annotations__ and C.__annotate__, type's data descriptors (a class's only, not a builtin type's). */
    @JvmStatic
    fun typeGet(t: PyType, name: String): Any? {
        if (!t.heap) throw PyException("AttributeError", "type object '${t.name}' has no attribute '$name'")
        val d = t.dict
        if (name == "__annotate__") {
            val a = d[ANNOTATE] ?: d[ANNOTATE_FUNC]
            if (a == null) {
                d[ANNOTATE_FUNC] = PyNone
                return PyNone
            }
            return PyClasses.descrGet(null, a, null, t)
        }
        (d[ANNOTATIONS] ?: d[CACHE])?.let { return PyClasses.descrGet(null, it, null, t) }
        val annotate = PyClasses.typeAttr(t, "__annotate__")
        val r = if (PyCalls.isCallable(annotate)) call(annotate) else PyDict()
        d[CACHE] = r
        return r
    }

    @JvmStatic
    fun typeSet(t: PyType, name: String, v: Any?) {
        val d = t.dict
        if (name == "__annotate__") {
            if (v == null) throw PyRt.typeError("cannot delete __annotate__ attribute")
            checkAnnotate(v)
            d[ANNOTATE] = v
            if (v !== PyNone) d.map.remove(PyDict.Key(CACHE))
            return
        }
        if (v != null) d[CACHE] = v
        else if (d.map.remove(PyDict.Key(CACHE)) == null) throw PyException("AttributeError", "__annotations__")
        d.map.remove(PyDict.Key(ANNOTATE_FUNC))
        d.map.remove(PyDict.Key(ANNOTATE))
    }

    /** m.__annotations__ (its dict's, else its `__annotate__`'s, kept once it has run) and m.__annotate__. */
    @JvmStatic
    fun moduleGet(m: PyModule, name: String): Any? {
        val d = m.dict
        if (name == "__annotate__") return d[ANNOTATE] ?: PyNone.also { d[ANNOTATE] = it }
        d[ANNOTATIONS]?.let { return it }
        val a = d[ANNOTATE]
        val r = if (a != null && PyCalls.isCallable(a)) call(a) else PyDict()
        // (not kept while its body runs: annotations after are still to come)
        if (!m.initializing) d[ANNOTATIONS] = r
        return r
    }

    @JvmStatic
    fun moduleSet(m: PyModule, name: String, v: Any?) {
        val d = m.dict
        if (name == "__annotate__") {
            if (v == null) throw PyRt.typeError("cannot delete __annotate__ attribute")
            checkAnnotate(v)
            d[ANNOTATE] = v
            if (v !== PyNone) d.map.remove(PyDict.Key(ANNOTATIONS))
            return
        }
        if (v != null) d[ANNOTATIONS] = v
        else if (d.map.remove(PyDict.Key(ANNOTATIONS)) == null) throw PyException("AttributeError", "__annotations__")
        d.map.remove(PyDict.Key(ANNOTATE))
    }
}
