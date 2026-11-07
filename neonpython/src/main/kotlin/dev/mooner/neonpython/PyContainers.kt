package dev.mooner.neonpython

/** A dict's keys(), values() or items(): a live view of it (keys and items set-like). */
class PyDictView(@JvmField val kind: Int, @JvmField val dict: PyDict) {
    companion object {
        const val KEYS = 0
        const val VALUES = 1
        const val ITEMS = 2
    }
}

/**
 * The methods of lists, tuples and dicts (docs/PYTHON_PLATFORM.md §11: the types behind literals), and dicts' views,
 * as CPython 3.14's listobject.c, tupleobject.c and dictobject.c have them.
 */
object PyContainers {
    private val NO_ARGS = arrayOfNulls<Any?>(0)

    @JvmField val KEYS_TYPE = PyTypes.newBuiltin("dict_keys", subclassable = false)
    @JvmField val VALUES_TYPE = PyTypes.newBuiltin("dict_values", subclassable = false)
    @JvmField val ITEMS_TYPE = PyTypes.newBuiltin("dict_items", subclassable = false)

    @JvmStatic fun typeOf(v: PyDictView): PyType = when (v.kind) {
        PyDictView.KEYS -> KEYS_TYPE
        PyDictView.VALUES -> VALUES_TYPE
        else -> ITEMS_TYPE
    }

    /** A dict iterated (its keys, values or items) as CPython does: live, refusing a change of its size meanwhile. */
    @JvmStatic
    fun iter(d: PyDict, kind: Int): PyIterator {
        val it = d.map.entries.iterator()
        val size0 = d.size
        val t = when (kind) {
            PyDictView.KEYS -> PyLibrary.DICT_KEYITERATOR
            PyDictView.VALUES -> PyLibrary.type("dict_valueiterator")
            else -> PyLibrary.type("dict_itemiterator")
        }
        return PyIterator {
            if (d.size != size0) throw PyException("RuntimeError", "dictionary changed size during iteration")
            try {
                if (!it.hasNext()) PyIterator.STOP else {
                    val e = it.next()
                    when (kind) {
                        PyDictView.KEYS -> e.key.v
                        PyDictView.VALUES -> e.value
                        else -> PyTuple(arrayOf(e.key.v, e.value))
                    }
                }
            } catch (x: ConcurrentModificationException) {
                throw PyException("RuntimeError", "dictionary keys changed during iteration")
            }
        }.also { it.type = t }
    }

    @JvmStatic
    fun contains(v: PyDictView, x: Any?): Boolean = when (v.kind) {
        PyDictView.KEYS -> v.dict.containsKey(x)
        PyDictView.VALUES -> v.dict.map.values.any { it === x || PyRt.eq(it, x) }
        else -> x is PyTuple && x.items.size == 2 && v.dict.containsKey(x.items[0]) && PyRt.eq(v.dict[x.items[0]], x.items[1])
    }

    @JvmStatic
    fun repr(v: PyDictView): String {
        val name = typeOf(v).name
        val items = ArrayList<Any?>()
        val it = iter(v.dict, v.kind)
        while (true) {
            val x = it.next()
            if (x === PyIterator.STOP) break
            items.add(x)
        }
        return "$name(${PyRt.repr(PyList(items))})"
    }

    /** A keys or items view as a set (its set operations': `d.keys() & other` is a set). */
    @JvmStatic fun asSet(v: Any?): PySet = if (v is PySet) v else PySets.of(if (v is PyDictView) iter(v.dict, v.kind) else v, false)

    /** a op b where one is a keys or items view: as sets; null where neither is. */
    @JvmStatic
    fun setOp(op: Int, a: Any?, b: Any?): Any? {
        val va = a is PyDictView && a.kind != PyDictView.VALUES
        val vb = b is PyDictView && b.kind != PyDictView.VALUES
        if (!va && !vb) return null
        if (op != PyOp.BITOR && op != PyOp.BITAND && op != PyOp.SUB && op != PyOp.BITXOR) return null
        return PySets.binary(op, asSet(a), asSet(b))
    }

    /** A keys or items view == a set or another such view: as sets. */
    @JvmStatic
    fun viewEq(a: Any?, b: Any?): Boolean? {
        val va = a is PyDictView && a.kind != PyDictView.VALUES
        val vb = b is PyDictView && b.kind != PyDictView.VALUES
        if (!va && !vb) return null
        if (va && vb || (va && b is PySet) || (vb && a is PySet)) return PySets.equal(asSet(a), asSet(b))
        return false
    }

    // ------------------------------------------------------------------ the methods

    @JvmStatic
    fun define() {
        fun m(t: PyType, n: String, impl: (Array<Any?>, PyDict?) -> Any?) = t.def(n, PyBuiltin(n, { _, a -> impl(a, null) }, { _, a, kw -> impl(a, kw) }))
        fun list(a: Array<Any?>, n: String): ArrayList<Any?> = (a.getOrNull(0) as? PyList ?: throw PyRt.typeError("descriptor '$n' for 'list' objects doesn't apply to a '${PyTypes.name(a.getOrNull(0))}' object")).items
        fun dict(a: Array<Any?>, n: String): PyDict = a.getOrNull(0) as? PyDict ?: throw PyRt.typeError("descriptor '$n' for 'dict' objects doesn't apply to a '${PyTypes.name(a.getOrNull(0))}' object")
        fun idx(v: Any?): Long = v as? Long ?: if (v is PyBool) (if (v.value) 1L else 0L) else throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer")
        fun bounds(size: Int, a: Array<Any?>, from: Int): Pair<Int, Int> {
            fun at(v: Any?, dflt: Int): Int {
                if (v == null) return dflt
                var x = idx(v)
                if (x < 0) x += size
                return x.coerceIn(0L, size.toLong()).toInt()
            }
            return at(a.getOrNull(from), 0) to at(a.getOrNull(from + 1), size)
        }
        val lt = PyTypes.LIST
        m(lt, "extend") { a, _ -> val l = list(a, "extend"); l.addAll(if (a[1] === a[0]) ArrayList(l) else PyBuiltins.toList(a[1])); PyNone }
        m(lt, "insert") { a, _ ->
            val l = list(a, "insert")
            if (a.size != 3) throw PyRt.typeError("insert expected 2 arguments, got ${a.size - 1}")
            var i = idx(a[1])
            if (i < 0) i = maxOf(0L, i + l.size)
            l.add(minOf(i, l.size.toLong()).toInt(), a[2])
            PyNone
        }
        m(lt, "remove") { a, _ ->
            val l = list(a, "remove")
            val i = l.indexOfFirst { it === a[1] || PyRt.eq(it, a[1]) }
            if (i < 0) throw PyException("ValueError", "list.remove(x): x not in list")
            l.removeAt(i)
            PyNone
        }
        m(lt, "index") { a, _ ->
            val l = list(a, "index")
            val (st, end) = bounds(l.size, a, 2)
            var r = -1
            for (i in st until end) if (l[i] === a[1] || PyRt.eq(l[i], a[1])) { r = i; break }
            if (r < 0) throw PyException("ValueError", "list.index(x): x not in list")
            r.toLong()
        }
        m(lt, "count") { a, _ -> list(a, "count").count { it === a[1] || PyRt.eq(it, a[1]) }.toLong() }
        m(lt, "sort") { a, kw ->
            val l = list(a, "sort")
            if (a.size > 1) throw PyRt.typeError("sort() takes no positional arguments")
            if (kw != null) for (k in kw.map.keys) if ((k.v as PyStr).s !in setOf("key", "reverse")) throw PyRt.typeError("sort() got an unexpected keyword argument '${(k.v as PyStr).s}'")
            PyLibrary.sort(l, kw?.get(PyStr("key")), PyRt.truthy(kw?.get(PyStr("reverse")) ?: PyBool.FALSE))
            PyNone
        }
        m(lt, "reverse") { a, _ -> list(a, "reverse").reverse(); PyNone }
        m(lt, "clear") { a, _ -> list(a, "clear").clear(); PyNone }
        m(lt, "copy") { a, _ -> PyList(ArrayList(list(a, "copy"))) }
        val tt = PyTypes.TUPLE
        m(tt, "count") { a, _ -> (a[0] as PyTuple).items.count { it === a[1] || PyRt.eq(it, a[1]) }.toLong() }
        m(tt, "index") { a, _ ->
            val items = (a[0] as PyTuple).items
            val (st, end) = bounds(items.size, a, 2)
            var r = -1
            for (i in st until end) if (items[i] === a[1] || PyRt.eq(items[i], a[1])) { r = i; break }
            if (r < 0) throw PyException("ValueError", "tuple.index(x): x not in tuple")
            r.toLong()
        }
        val dt = PyTypes.DICT
        m(dt, "keys") { a, _ -> PyDictView(PyDictView.KEYS, dict(a, "keys")) }
        m(dt, "values") { a, _ -> PyDictView(PyDictView.VALUES, dict(a, "values")) }
        m(dt, "items") { a, _ -> PyDictView(PyDictView.ITEMS, dict(a, "items")) }
        m(dt, "pop") { a, _ ->
            val d = dict(a, "pop")
            if (a.size < 2 || a.size > 3) throw PyRt.typeError("pop expected at least 1 argument, got ${a.size - 1}")
            val key = PyDict.Key(a[1])
            if (d.map.containsKey(key)) d.map.remove(key) else if (a.size == 3) a[2] else throw PyException.keyError(a[1])
        }
        m(dt, "popitem") { a, _ ->
            val d = dict(a, "popitem")
            val last = d.map.entries.lastOrNull() ?: throw PyException.keyError(PyStr("popitem(): dictionary is empty"))
            d.map.remove(last.key)
            PyTuple(arrayOf(last.key.v, last.value))
        }
        m(dt, "setdefault") { a, _ ->
            val d = dict(a, "setdefault")
            val dflt = a.getOrNull(2) ?: PyNone
            if (d.containsKey(a[1])) d[a[1]] else { d[a[1]] = dflt; dflt }
        }
        m(dt, "update") { a, kw ->
            val d = dict(a, "update")
            if (a.size > 2) throw PyRt.typeError("update expected at most 1 argument, got ${a.size - 1}")
            if (a.size == 2) update(d, a[1])
            if (kw != null) for ((k, v) in kw.map) d.map[k] = v
            PyNone
        }
        m(dt, "copy") { a, _ -> PyDict().also { it.map.putAll(dict(a, "copy").map) } }
        m(dt, "clear") { a, _ -> dict(a, "clear").map.clear(); PyNone }
        dt.def("fromkeys", PyClassMethod(PyBuiltin("fromkeys") { _, a ->
            val d = PyDict()
            val value = a.getOrNull(2) ?: PyNone
            for (k in PyBuiltins.toList(a.getOrNull(1))) d[k] = value
            d
        }))
        for (t in arrayOf(KEYS_TYPE, VALUES_TYPE, ITEMS_TYPE)) {
            m(t, "__len__") { a, _ -> (a[0] as PyDictView).dict.size.toLong() }
            m(t, "__iter__") { a, _ -> (a[0] as PyDictView).let { iter(it.dict, it.kind) } }
        }
    }

    /** dict.update(other): a mapping (keys() and []) or an iterable of pairs. */
    @JvmStatic
    fun update(d: PyDict, other: Any?) {
        if (other is PyDict) {
            for ((k, v) in other.map) d.map[k] = v
            return
        }
        val keys = if (PyRt.special(other) || other !is PyList && other !is PyTuple) try {
            PyRt.getAttr(other, "keys")
        } catch (e: PyException) {
            if (e.matches(PyExceptions.type("AttributeError"))) null else throw e
        } else null
        if (keys != null) {
            for (k in PyBuiltins.toList(PyCalls.callWith(PyContext.current(), keys, NO_ARGS, NO_ARGS, NO_ARGS))) d[k] = PyRt.getItem(other, k)
            return
        }
        for ((i, item) in PyBuiltins.toList(other).withIndex()) {
            val pair = try {
                PyBuiltins.toList(item)
            } catch (e: PyException) {
                throw PyRt.typeError("object is not iterable")
            }
            if (pair.size != 2) throw PyException("ValueError", "dictionary update sequence element #$i has length ${pair.size}; 2 is required")
            d[pair[0]] = pair[1]
        }
    }
}
