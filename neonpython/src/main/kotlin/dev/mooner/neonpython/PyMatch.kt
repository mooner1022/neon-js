package dev.mooner.neonpython

/**
 * `match` (PEP 634; docs/PYTHON_PLATFORM.md §11, M6): the tests the patterns compile to, as CPython's MATCH_*
 * instructions do them. Sequences are lists, tuples and ranges (and their subclasses), never strs, bytes or dicts;
 * mappings are dicts; a class pattern's positional sub-patterns name attributes by __match_args__ (the subject itself
 * for the builtin types that match themselves).
 */
object PyMatch {
    private val NO_ARGS = arrayOfNulls<Any?>(0)

    /** MATCH_SEQUENCE */
    @JvmStatic fun matchSequence(v: Any?): Any? = PyBool.of(v is PyList || v is PyTuple || v is PyRange)

    /** MATCH_MAPPING */
    @JvmStatic fun matchMapping(v: Any?): Any? = PyBool.of(v is PyDict)

    /** GET_LEN */
    @JvmStatic fun getLen(v: Any?): Any? = PyRt.len(v)

    /** MATCH_KEYS: the values of [keys] in mapping [v] (by its get, as CPython looks them up), or None where one is missing. */
    @JvmStatic
    fun matchKeys(v: Any?, keys: Any?): Any? {
        val ks = (keys as PyTuple).items
        val d = v as PyDict
        val seen = PySet(false)
        val out = arrayOfNulls<Any?>(ks.size)
        val missing = Any()
        val get = if (d.sub != null) PyRt.getAttr(d, "get") else null
        for ((i, k) in ks.withIndex()) {
            if (seen.contains(k)) throw PyException("ValueError", "mapping pattern checks duplicate key (${PyRt.repr(k)})")
            seen.addKey(k)
            val x = if (get == null) (if (d.containsKey(k)) d[k] else missing)
                else PyCalls.callWith(PyContext.current(), get, arrayOf(k, missing), NO_ARGS, NO_ARGS)
            if (x === missing) return PyNone
            out[i] = x
        }
        return PyTuple(out)
    }

    /** COPY_DICT_WITHOUT_KEYS: a mapping pattern's **rest. */
    @JvmStatic
    fun copyWithoutKeys(v: Any?, keys: Any?): Any? {
        val d = PyDict()
        d.map.putAll((v as PyDict).map)
        for (k in (keys as PyTuple).items) d.map.remove(PyDict.Key(k))
        return d
    }

    /** The builtin types a single positional sub-pattern matches as the subject itself (Py_TPFLAGS_MATCH_SELF). */
    private val SELF = setOf(PyTypes.BOOL, PyTypes.BYTES, PyTypes.DICT, PyTypes.FLOAT, PyTypes.FROZENSET, PyTypes.INT, PyTypes.LIST,
        PyTypes.SET, PyTypes.STR, PyTypes.TUPLE)

    /**
     * MATCH_CLASS: whether [v] is an instance of [cls], and then its attributes for the [npos] positional sub-patterns
     * (by __match_args__) and the keyword ones ([names]), as a tuple; None where it is not or an attribute is missing.
     */
    @JvmStatic
    fun matchClass(v: Any?, cls: Any?, names: Any?, npos: Int): Any? {
        if (cls !is PyType) throw PyRt.typeError("called match pattern must be a class")
        if (!PyTypes.typeOf(v).isSubtype(cls)) return PyNone
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
        for (k in kw) {
            val n = (k as PyStr).s
            if (n in attrs) throw PyRt.typeError("${cls.name}() got multiple sub-patterns for attribute '$n'")
            attrs.add(n)
        }
        for (n in attrs) {
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
