package dev.mooner.neonpython

/**
 * `match` (PEP 634; docs/PYTHON_PLATFORM.md §11, M6): the tests the patterns compile to, as CPython's MATCH_*
 * instructions do them. Sequences and mappings are what their types' Py_TPFLAGS_SEQUENCE and Py_TPFLAGS_MAPPING say
 * (PySlots.collection): lists, tuples, ranges, memoryviews, arrays and deques, never strs, bytes or bytearrays; dicts
 * and mappingproxies; collections.abc's Sequence's and Mapping's classes, and those registered with them. A class
 * pattern's positional sub-patterns name attributes by __match_args__ (the subject itself for the builtin types that
 * match themselves).
 */
object PyMatch {
    private val NO_ARGS = arrayOfNulls<Any?>(0)

    /** MATCH_SEQUENCE */
    @JvmStatic
    fun matchSequence(v: Any?): Any? =
        PyBool.of((v is PyList && v.sub == null) || (v is PyTuple && v.sub == null) || PySlots.collection(PyTypes.typeOf(v)) and PySlots.SEQUENCE != 0)

    /** MATCH_MAPPING */
    @JvmStatic
    fun matchMapping(v: Any?): Any? = PyBool.of((v is PyDict && v.sub == null) || PySlots.collection(PyTypes.typeOf(v)) and PySlots.MAPPING != 0)

    /** GET_LEN */
    @JvmStatic fun getLen(v: Any?): Any? = PyRt.len(v)

    /** MATCH_KEYS: the values of [keys] in mapping [v] (by its get, as CPython looks them up), or None where one is missing. */
    @JvmStatic
    fun matchKeys(v: Any?, keys: Any?): Any? {
        val ks = (keys as PyTuple).items
        val d = v as? PyDict
        val seen = PySet(false)
        val out = arrayOfNulls<Any?>(ks.size)
        val missing = Any()
        val get = if (d == null || d.sub != null) PyRt.getAttr(v, "get") else null
        for ((i, k) in ks.withIndex()) {
            if (seen.contains(k)) throw PyException("ValueError", "mapping pattern checks duplicate key (${PyRt.repr(k)})")
            seen.addKey(k)
            val x = if (get == null) (if (d!!.containsKey(k)) d[k] else missing)
                else PyCalls.callWith(PyContext.current(), get, arrayOf(k, missing), NO_ARGS, NO_ARGS)
            if (x === missing) return PyNone
            out[i] = x
        }
        return PyTuple(out)
    }

    /** COPY_DICT_WITHOUT_KEYS: a mapping pattern's **rest (a dict of the mapping's items, by its keys() where it is not a dict). */
    @JvmStatic
    fun copyWithoutKeys(v: Any?, keys: Any?): Any? {
        val d = PyDict()
        if (v is PyDict) d.map.putAll(v.map) else PyContainers.update(d, v)
        for (k in (keys as PyTuple).items) d.map.remove(PyDict.Key(k))
        return d
    }

    /** The builtin types a single positional sub-pattern matches as the subject itself (Py_TPFLAGS_MATCH_SELF). */
    private val SELF = setOf(PyTypes.BOOL, PyTypes.BYTES, PyTypes.BYTEARRAY, PyTypes.DICT, PyTypes.FLOAT, PyTypes.FROZENSET, PyTypes.INT, PyTypes.LIST,
        PyTypes.SET, PyTypes.STR, PyTypes.TUPLE)

    /**
     * MATCH_CLASS: whether [v] is an instance of [cls], and then its attributes for the [npos] positional sub-patterns
     * (by __match_args__) and the keyword ones ([names]), as a tuple; None where it is not or an attribute is missing.
     */
    @JvmStatic
    fun matchClass(v: Any?, cls: Any?, names: Any?, npos: Int): Any? {
        if (cls !is PyType) throw PyRt.typeError("called match pattern must be a class")
        // (isinstance's test: an ABC's, a runtime-checkable protocol's __instancecheck__ too)
        if (!PyTypes.isInstance(v, cls)) return PyNone
        val kw = (names as PyTuple).items
        val attrs = ArrayList<String>()
        val out = ArrayList<Any?>()
        if (npos > 0) {
            val ma = try {
                PyRt.getAttr(cls, "__match_args__")
            } catch (e: PyException) {
                if (e.matches(PyExceptions.type("AttributeError"))) null else throw e
            }
            if (ma == null) {
                if (npos == 1 && cls.mro.any { it in SELF }) {
                    out.add(v)
                } else throw PyRt.typeError("${cls.name}() accepts 0 positional sub-patterns ($npos given)")
            } else {
                val t = ma as? PyTuple ?: throw PyRt.typeError("${cls.name}.__match_args__ must be a tuple (got ${PyTypes.name(ma)})")
                if (npos > t.items.size) throw PyRt.typeError("${cls.name}() accepts ${t.items.size} positional sub-pattern${if (t.items.size == 1) "" else "s"} ($npos given)")
                for (i in 0 until npos) {
                    val n = t.items[i] as? PyStr ?: throw PyRt.typeError("__match_args__ elements must be strings (got ${PyTypes.name(t.items[i])})")
                    attrs.add(n.s)
                }
            }
        }
        for (k in kw) attrs.add((k as PyStr).s)
        // (each attribute checked against those before it as it is taken, as CPython's match_class_attr: a missing one
        // fails the match before a later one is looked at)
        val seen = HashSet<String>()
        for (n in attrs) {
            if (!seen.add(n)) throw PyRt.typeError("${cls.tpName ?: cls.name}() got multiple sub-patterns for attribute '$n'")
            val x = try {
                PyRt.getAttr(v, n)
            } catch (e: PyException) {
                if (e.matches(PyExceptions.type("AttributeError"))) return PyNone else throw e
            }
            out.add(x)
        }
        return PyTuple(out.toTypedArray())
    }
}
