package dev.mooner.neonpython

/** A collections.deque (its subclass's instance too): its items, its maxlen (-1: none), a count of its changes. */
class PyDeque(type: PyType) : PyObject(type) {
    @JvmField val items = ArrayDeque<Any?>()
    @JvmField var maxlen = -1
    @JvmField var state = 0L

    fun push(x: Any?, left: Boolean) {
        if (maxlen == 0) return
        if (left) items.addFirst(x) else items.addLast(x)
        if (maxlen > 0 && items.size > maxlen) if (left) items.removeLast() else items.removeFirst()
        state++
    }
}

/**
 * `_collections` (docs/PYTHON_PLATFORM.md §12, S2): what CPython writes in C for collections, as CPython 3.14 has
 * it: deque and its iterators, defaultdict, _count_elements, _tuplegetter (collections/__init__.py keeps its own
 * OrderedDict where `_collections` has none).
 */
object PyCollections {
    private val NO_ARGS = arrayOfNulls<Any?>(0)

    private fun type(name: String, base: PyType? = null, subclassable: Boolean = true): PyType {
        val t = if (base == null) PyTypes.newBuiltin(name, subclassable) else PyType(name, arrayOf(base)).also {
            it.mro = PyTypes.c3(it, it.bases)
            it.subclassable = subclassable
        }
        t.module = "collections"
        t.tpName = "collections.$name"
        // (its methods builtins dispatch takes)
        t.dispatchBuiltins = true
        return t
    }

    @JvmField val DEQUE = type("deque")
    @JvmField val DEQUE_ITER = type("_deque_iterator", subclassable = false)
    @JvmField val DEQUE_REVERSE_ITER = type("_deque_reverse_iterator", subclassable = false)
    @JvmField val DEFAULTDICT = type("defaultdict", PyTypes.DICT)
    @JvmField val TUPLEGETTER = type("_tuplegetter", subclassable = false)

    private fun ctx() = PyContext.current()

    private fun call(ctx: PyContext, f: Any?, vararg a: Any?): Any? = PyCalls.callWith(ctx, f, arrayOf(*a), NO_ARGS, NO_ARGS)

    private fun deque(v: Any?, what: String): PyDeque =
        v as? PyDeque ?: throw PyRt.typeError("descriptor '$what' for 'collections.deque' objects doesn't apply to a '${PyTypes.name(v)}' object")

    private fun index(d: PyDeque, i0: Any?): Int {
        val i = PyRt.indexValue(i0) ?: throw PyRt.typeError("sequence index must be integer, not '${PyTypes.name(i0)}'")
        val n = d.items.size
        val k = if (i is Long) (if (i < 0) i + n else i) else -1L
        if (k < 0 || k >= n) throw PyException("IndexError", "deque index out of range")
        return k.toInt()
    }

    /** Each of [d]'s items with [f] (false: stop), a RuntimeError where [f] changed it. */
    private inline fun scan(d: PyDeque, f: (Int, Any?) -> Boolean) {
        val s = d.state
        for (i in 0 until d.items.size) {
            val x = d.items[i]
            val go = f(i, x)
            if (d.state != s) throw PyException("RuntimeError", "deque mutated during iteration")
            if (!go) return
        }
    }

    private fun extend(d: PyDeque, src: Any?, left: Boolean) {
        val xs = if (src === d) ArrayList(d.items) else null
        if (xs != null) {
            for (x in xs) d.push(x, left)
            return
        }
        val it = PyRt.iter(src)
        while (true) {
            val x = it.next()
            if (x === PyIterator.STOP) break
            d.push(x, left)
        }
    }

    private fun maxlenOf(v: Any?): Int {
        if (v == null || v === PyNone) return -1
        val n = PyRt.indexValue(v) as? Long ?: throw PyRt.typeError("an integer is required")
        if (n < 0) throw PyException("ValueError", "maxlen must be non-negative")
        return n.toInt()
    }

    private fun newDeque(ctx: PyContext, d: PyDeque, xs: Iterable<Any?>): PyDeque {
        // (a subclass's copy: its class called, as CPython calls it)
        if (d.type === DEQUE) return PyDeque(DEQUE).also { n -> n.maxlen = d.maxlen; for (x in xs) n.push(x, false) }
        val list = PyList(ArrayList(xs.toList()))
        val r = if (d.maxlen < 0) call(ctx, d.type, list) else PyCalls.callWith(ctx, d.type, arrayOf(list, d.maxlen.toLong()), NO_ARGS, NO_ARGS)
        return r as? PyDeque ?: throw PyRt.typeError("${d.type.name}() must return a deque, not ${PyTypes.name(r)}")
    }

    private fun repr(d: PyDeque): String {
        val name = d.type.name
        return PyRt.reprGuarded(ctx(), d, "[...]") {
            val items = d.items.joinToString(", ", "[", "]") { PyRt.repr(it) }
            if (d.maxlen < 0) "$name($items)" else "$name($items, maxlen=${d.maxlen})"
        }
    }

    private fun compare(op: Int, a: PyDeque, b: Any?): Any? {
        if (b !is PyDeque) return PyNotImplemented
        val x = ArrayList(a.items)
        val y = ArrayList(b.items)
        if ((op == PyOp.EQ || op == PyOp.NE) && x.size != y.size) return PyBool.of(op == PyOp.NE)
        var i = 0
        while (i < x.size && i < y.size && PyRt.eq(x[i], y[i])) i++
        if (i < x.size && i < y.size) {
            if (op == PyOp.EQ) return PyBool.FALSE
            if (op == PyOp.NE) return PyBool.TRUE
            return PyRt.compareValue(op, x[i], y[i])
        }
        return PyBool.of(PyRt.compare(op, x.size.toLong(), y.size.toLong()))
    }

    private fun iterator(d: PyDeque, reverse: Boolean): PyIterator {
        val s = d.state
        var i = 0
        return PyIterator {
            if (d.state != s) throw PyException("RuntimeError", "deque mutated during iteration")
            if (i >= d.items.size) PyIterator.STOP else {
                val x = if (reverse) d.items[d.items.size - 1 - i] else d.items[i]
                i++
                x
            }
        }.also { it.type = if (reverse) DEQUE_REVERSE_ITER else DEQUE_ITER }
    }

    init {
        val t = DEQUE
        fun m(n: String, impl: (PyContext, PyDeque, Array<Any?>) -> Any?) =
            t.def(n, PyBuiltin(n) { ctx, a -> impl(ctx, deque(a.getOrNull(0), n), a) })
        t.def("__new__", PyBuiltin("__new__", { _, a -> PyDeque(a[0] as PyType) }, { _, a, _ -> PyDeque(a[0] as PyType) }))
        fun init(a: Array<Any?>, kw: PyDict?): Any? {
            val d = deque(a[0], "__init__")
            if (a.size > 3) throw PyRt.typeError("deque expected at most 2 arguments, got ${a.size - 1}")
            kw?.map?.keys?.forEach { k ->
                val n = (k.v as PyStr).s
                if (n != "iterable" && n != "maxlen") throw PyRt.typeError("deque() got an unexpected keyword argument '$n'")
            }
            val src = a.getOrNull(1) ?: kw?.get(PyStr("iterable"))
            d.maxlen = maxlenOf(a.getOrNull(2) ?: kw?.get(PyStr("maxlen")))
            if (d.items.isNotEmpty()) {
                d.items.clear()
                d.state++
            }
            if (src != null) extend(d, src, false)
            return PyNone
        }
        t.def("__init__", PyBuiltin("__init__", { _, a -> init(a, null) }, { _, a, kw -> init(a, kw) }))
        m("append") { _, d, a -> d.push(a[1], false); PyNone }
        m("appendleft") { _, d, a -> d.push(a[1], true); PyNone }
        m("pop") { _, d, _ ->
            if (d.items.isEmpty()) throw PyException("IndexError", "pop from an empty deque")
            d.state++
            d.items.removeLast()
        }
        m("popleft") { _, d, _ ->
            if (d.items.isEmpty()) throw PyException("IndexError", "pop from an empty deque")
            d.state++
            d.items.removeFirst()
        }
        m("extend") { _, d, a -> extend(d, a[1], false); PyNone }
        m("extendleft") { _, d, a -> extend(d, a[1], true); PyNone }
        m("clear") { _, d, _ -> d.items.clear(); d.state++; PyNone }
        m("copy") { ctx, d, _ -> newDeque(ctx, d, ArrayList(d.items)) }
        t.def("__copy__", t.dict[PyStr("copy")])
        m("count") { _, d, a ->
            var n = 0L
            scan(d) { _, x -> if (PyRt.eq(x, a[1])) n++; true }
            n
        }
        m("index") { _, d, a ->
            val size = d.items.size.toLong()
            fun bound(v: Any?, dflt: Long): Long {
                if (v == null) return dflt
                val i = PyRt.indexValue(v) as? Long ?: throw PyRt.typeError("slice indices must be integers or have an __index__ method")
                return if (i < 0) maxOf(0L, i + size) else minOf(i, size)
            }
            val start = bound(a.getOrNull(2), 0L)
            val stop = bound(a.getOrNull(3), size)
            var found = -1L
            val s = d.state
            var i = start
            while (i < stop && i < d.items.size) {
                val eq = PyRt.eq(d.items[i.toInt()], a[1])
                if (d.state != s) throw PyException("RuntimeError", "deque mutated during iteration")
                if (eq) { found = i; break }
                i++
            }
            if (found < 0) throw PyException("ValueError", "deque.index(x): x not in deque")
            found
        }
        m("insert") { _, d, a ->
            if (d.maxlen >= 0 && d.items.size >= d.maxlen) throw PyException("IndexError", "deque already at its maximum size")
            val i0 = PyRt.indexValue(a[1]) as? Long ?: throw PyRt.typeError("'${PyTypes.name(a[1])}' object cannot be interpreted as an integer")
            val n = d.items.size.toLong()
            val i = (if (i0 < 0) maxOf(0L, i0 + n) else minOf(i0, n)).toInt()
            d.items.add(i, a[2])
            d.state++
            PyNone
        }
        m("remove") { _, d, a ->
            var at = -1
            scan(d) { i, x -> if (PyRt.eq(x, a[1])) { at = i; false } else true }
            if (at < 0) throw PyException("ValueError", "deque.remove(x): x not in deque")
            d.items.removeAt(at)
            d.state++
            PyNone
        }
        m("reverse") { _, d, _ ->
            val xs = d.items.reversed()
            d.items.clear()
            d.items.addAll(xs)
            d.state++
            PyNone
        }
        m("rotate") { _, d, a ->
            val n0 = a.getOrNull(1)?.let { PyRt.indexValue(it) as? Long ?: throw PyRt.typeError("'${PyTypes.name(it)}' object cannot be interpreted as an integer") } ?: 1L
            val size = d.items.size
            if (size > 1) {
                var n = (n0 % size).toInt()
                if (n < 0) n += size
                repeat(n) { d.items.addFirst(d.items.removeLast()) }
                if (n != 0) d.state++
            }
            PyNone
        }
        t.def("maxlen", PyProperty(PyBuiltin("maxlen") { _, a -> deque(a[0], "maxlen").maxlen.let { if (it < 0) PyNone else it.toLong() } },
            PyBuiltin("maxlen") { _, _ -> throw PyException("AttributeError", "attribute 'maxlen' of 'collections.deque' objects is not writable") },
            null, null).also { it.name = "maxlen" })
        m("__len__") { _, d, _ -> d.items.size.toLong() }
        m("__bool__") { _, d, _ -> PyBool.of(d.items.isNotEmpty()) }
        m("__getitem__") { _, d, a -> d.items[index(d, a[1])] }
        m("__setitem__") { _, d, a -> d.items[index(d, a[1])] = a[2]; PyNone }
        m("__delitem__") { _, d, a -> d.items.removeAt(index(d, a[1])); d.state++; PyNone }
        m("__contains__") { _, d, a ->
            var found = false
            scan(d) { _, x -> if (PyRt.eq(x, a[1])) { found = true; false } else true }
            PyBool.of(found)
        }
        m("__iter__") { _, d, _ -> iterator(d, false) }
        m("__reversed__") { _, d, _ -> iterator(d, true) }
        m("__repr__") { _, d, _ -> PyStr(repr(d)) }
        for ((n, op) in listOf("__lt__" to PyOp.LT, "__le__" to PyOp.LE, "__gt__" to PyOp.GT, "__ge__" to PyOp.GE, "__eq__" to PyOp.EQ, "__ne__" to PyOp.NE)) {
            m(n) { _, d, a -> compare(op, d, a[1]) }
        }
        t.def("__hash__", PyNone)
        m("__add__") { ctx, d, a ->
            val o = a[1] as? PyDeque ?: throw PyRt.typeError("can only concatenate deque (not \"${PyTypes.name(a[1])}\") to deque")
            newDeque(ctx, d, ArrayList(d.items) + ArrayList(o.items))
        }
        fun times(a: Any?): Long = PyRt.indexValue(a) as? Long ?: throw PyRt.typeError("can't multiply sequence by non-int of type '${PyTypes.name(a)}'")
        m("__mul__") { ctx, d, a ->
            val n = times(a[1])
            val xs = ArrayList<Any?>()
            repeat(maxOf(0L, n).toInt()) { xs.addAll(d.items) }
            newDeque(ctx, d, xs)
        }
        t.def("__rmul__", t.dict[PyStr("__mul__")])
        m("__iadd__") { _, d, a -> extend(d, a[1], false); d }
        m("__imul__") { _, d, a ->
            val n = times(a[1])
            if (n <= 0) d.items.clear() else {
                val xs = ArrayList(d.items)
                repeat((n - 1).toInt()) { for (x in xs) d.push(x, false) }
            }
            d.state++
            d
        }
        m("__reduce__") { _, d, _ ->
            val list = PyList(ArrayList(d.items))
            val args = if (d.maxlen < 0) PyTuple(arrayOf(list)) else PyTuple(arrayOf(list, d.maxlen.toLong()))
            PyTuple(arrayOf(d.type, args, d.dict?.takeIf { it.size > 0 } ?: PyNone))
        }
        t.def("__class_getitem__", PyClassMethod(PyBuiltin("__class_getitem__") { _, a -> PyGenericAlias(a[0], PyTyping.argsOf(a[1])) }))
        for (it in listOf(DEQUE_ITER, DEQUE_REVERSE_ITER)) {
            it.newImpl = { _, _, a, _ ->
                val d = a.getOrNull(0) as? PyDeque ?: throw PyRt.typeError("${it.name}() argument must be a deque")
                iterator(d, it === DEQUE_REVERSE_ITER)
            }
        }
        defineDefaultdict()
        TUPLEGETTER.newImpl = { _, _, a, _ ->
            val i = (PyRt.indexValue(a[0]) as Long).toInt()
            // (a namedtuple's field: the tuple's item, read-only)
            PyProperty(PyBuiltin("_tuplegetter") { _, b ->
                val tu = b[0] as? PyTuple ?: throw PyRt.typeError("descriptor for index '$i' for tuple subclasses doesn't apply to '${PyTypes.name(b[0])}' object")
                if (i >= tu.items.size) throw PyException("IndexError", "tuple index out of range")
                tu.items[i]
            }, null, null, a.getOrNull(1)?.takeIf { it !== PyNone })
        }
    }

    /** defaultdict(default_factory=None, /, *args, **kwargs): a dict whose __missing__ calls default_factory. */
    private fun defineDefaultdict() {
        val t = DEFAULTDICT
        fun factory(d: Any?): Any? = ((d as? PyDict)?.sub?.state) ?: PyNone
        t.def("__new__", PyBuiltin("__new__", { _, a -> newDefault(a[0] as PyType) }, { _, a, _ -> newDefault(a[0] as PyType) }))
        fun init(a: Array<Any?>, kw: PyDict?): Any? {
            val d = a[0] as PyDict
            val f = a.getOrNull(1) ?: PyNone
            if (f !== PyNone && !PyCalls.isCallable(f)) throw PyRt.typeError("first argument must be callable or None")
            d.sub!!.state = f
            PyBuiltins.dictFill(d, if (a.size > 2) a.copyOfRange(2, a.size) else NO_ARGS, kw?.takeIf { it.size > 0 })
            return PyNone
        }
        t.def("__init__", PyBuiltin("__init__", { _, a -> init(a, null) }, { _, a, kw -> init(a, kw) }))
        t.def("default_factory", PyProperty(PyBuiltin("default_factory") { _, a -> factory(a[0]) },
            PyBuiltin("default_factory") { _, a -> (a[0] as PyDict).sub!!.state = a[1]; PyNone }, null, null).also { it.name = "default_factory" })
        t.def("__missing__", PyBuiltin("__missing__") { ctx, a ->
            val d = a[0] as PyDict
            val f = factory(d)
            if (f === PyNone) throw PyException.keyError(a[1])
            val v = PyCalls.callWith(ctx, f, NO_ARGS, NO_ARGS, NO_ARGS)
            PyRt.setItem(d, a[1], v)
            v
        })
        fun copy(ctx: PyContext, d: PyDict): Any? = PyCalls.callWith(ctx, PyTypes.typeOf(d), arrayOf(factory(d), d), NO_ARGS, NO_ARGS)
        t.def("copy", PyBuiltin("copy") { ctx, a -> copy(ctx, a[0] as PyDict) })
        t.def("__copy__", t.dict[PyStr("copy")])
        t.def("__repr__", PyBuiltin("__repr__") { ctx, a ->
            val d = a[0] as PyDict
            val f = factory(d)
            // (the dict's part as dict's repr makes it, `{...}` where it holds itself; the factory's `...` where it is being made)
            val base = if (d.size == 0) "{}" else PyRt.reprGuarded(ctx, d, "{...}") {
                d.map.entries.joinToString(", ", "{", "}") { PyRt.repr(it.key.v) + ": " + PyRt.repr(it.value) }
            }
            val fr = if (f === PyNone) "None" else if (f == null) "None" else PyRt.reprGuarded(ctx, f, "...") { PyRt.repr(f) }
            PyStr("${d.sub!!.cls.name}($fr, $base)")
        })
        t.def("__reduce__", PyBuiltin("__reduce__") { _, a ->
            val d = a[0] as PyDict
            val f = factory(d)
            PyTuple(arrayOf(PyTypes.typeOf(d), if (f === PyNone) PyTuple.EMPTY else PyTuple(arrayOf(f)), PyNone, PyNone, PyRt.iter(PyDictView(PyDictView.ITEMS, d))))
        })
        // (d | other: a copy of d, other's pairs in it; other | d where other is a dict)
        t.def("__or__", PyBuiltin("__or__") { ctx, a ->
            val o = a[1] as? PyDict ?: return@PyBuiltin PyNotImplemented
            val n = copy(ctx, a[0] as PyDict) as PyDict
            for ((k, v) in o.map) PyRt.setItem(n, k.v, v)
            n
        })
        t.def("__ror__", PyBuiltin("__ror__") { ctx, a ->
            val o = a[1] as? PyDict ?: return@PyBuiltin PyNotImplemented
            val self = a[0] as PyDict
            val n = PyCalls.callWith(ctx, PyTypes.typeOf(self), arrayOf(factory(self), o), NO_ARGS, NO_ARGS) as PyDict
            for ((k, v) in self.map) PyRt.setItem(n, k.v, v)
            n
        })
    }

    private fun newDefault(cls: PyType): PyDict = PyDict().also { it.sub = PySub(cls).also { s -> s.state = PyNone } }

    /** `_collections` as a module. */
    @JvmStatic
    fun fill(ctx: PyContext, d: PyDict) {
        d[PyStr("deque")] = DEQUE
        d[PyStr("_deque_iterator")] = DEQUE_ITER
        d[PyStr("_deque_reverse_iterator")] = DEQUE_REVERSE_ITER
        d[PyStr("defaultdict")] = DEFAULTDICT
        d[PyStr("_tuplegetter")] = TUPLEGETTER
        d[PyStr("_count_elements")] = PyBuiltin("_count_elements") { c, a ->
            if (a.size != 2) throw PyRt.typeError("_count_elements expected 2 arguments, got ${a.size}")
            val m = a[0]
            val it = PyRt.iter(a[1])
            // (a plain dict: its items counted where they are; a mapping of another kind through its get and []=)
            val plain = m is PyDict && (m.sub == null || PyClasses.classOf(m)?.let { cls ->
                PyDunder.userMethod(cls, "__setitem__") == null && PyDunder.userMethod(cls, "get") == null
            } == true)
            while (true) {
                val x = it.next()
                if (x === PyIterator.STOP) break
                if (plain) {
                    val dm = m as PyDict
                    dm[x] = PyRt.add(dm[x] ?: 0L, 1L)
                } else {
                    val old = PyCalls.callWith(c, PyRt.getAttr(m, "get"), arrayOf(x, 0L), NO_ARGS, NO_ARGS)
                    PyRt.setItem(m, x, PyRt.add(old, 1L))
                }
            }
            PyNone
        }
    }
}
