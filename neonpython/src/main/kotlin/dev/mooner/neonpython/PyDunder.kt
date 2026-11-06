package dev.mooner.neonpython

import java.math.BigInteger

/**
 * The special methods (docs/PYTHON_PLATFORM.md §11, M2): what an operation does where its operands are instances of
 * classes, after [PyRt]'s paths for builtin values found nothing. Binary operators with their reflected methods,
 * NotImplemented and the subclass's priority; rich comparisons; hash, str, repr, truth, len; iteration, items,
 * containment; the unary operators; augmented assignment (`__iadd__`...). Messages are CPython's.
 */
object PyDunder {
    private val NO_ARGS = arrayOfNulls<Any?>(0)

    /** The binary operators by opcode: their methods, reflected methods and symbols. */
    private val NAMES = HashMap<Int, Triple<String, String, String>>().apply {
        put(PyOp.ADD, Triple("__add__", "__radd__", "+"))
        put(PyOp.SUB, Triple("__sub__", "__rsub__", "-"))
        put(PyOp.MUL, Triple("__mul__", "__rmul__", "*"))
        put(PyOp.TRUEDIV, Triple("__truediv__", "__rtruediv__", "/"))
        put(PyOp.FLOORDIV, Triple("__floordiv__", "__rfloordiv__", "//"))
        put(PyOp.MOD, Triple("__mod__", "__rmod__", "%"))
        put(PyOp.POW, Triple("__pow__", "__rpow__", "**"))
        put(PyOp.LSHIFT, Triple("__lshift__", "__rlshift__", "<<"))
        put(PyOp.RSHIFT, Triple("__rshift__", "__rrshift__", ">>"))
        put(PyOp.BITAND, Triple("__and__", "__rand__", "&"))
        put(PyOp.BITOR, Triple("__or__", "__ror__", "|"))
        put(PyOp.BITXOR, Triple("__xor__", "__rxor__", "^"))
        put(PyOp.MATMUL, Triple("__matmul__", "__rmatmul__", "@"))
    }

    private fun ctx() = PyContext.current()

    /** Special method [f] (found on [self]'s type) called on [self]: a function with self first, else as it binds. */
    private fun call(f: Any?, self: Any?, vararg rest: Any?): Any? {
        val c = ctx()
        if (f is PyFunction) {
            val all = arrayOfNulls<Any?>(rest.size + 1)
            all[0] = self
            rest.copyInto(all, 1)
            return PyCalls.callWith(c, f, all, NO_ARGS, NO_ARGS)
        }
        return PyCalls.callWith(c, PyClasses.descrGet(c, f, self, PyTypes.typeOf(self)), arrayOf(*rest), NO_ARGS, NO_ARGS)
    }

    private fun unsupported(sym: String, a: Any?, b: Any?) =
        PyRt.typeError("unsupported operand type(s) for ${if (sym == "**") "** or pow()" else sym}: '${PyTypes.name(a)}' and '${PyTypes.name(b)}'")

    /** A class's own special method [n] (not object's default, nor a builtin type's): what dispatch calls. */
    @JvmStatic
    fun userMethod(t: PyType, n: String): Any? {
        val m = t.lookup(n) ?: return null
        return if (m is PyBuiltin) null else m
    }

    // ------------------------------------------------------------------ binary operators

    /** a op b for binary opcode [op], where a builtin path found nothing; [sym] for the message. */
    @JvmStatic
    fun binary(a: Any?, b: Any?, op: Int, sym: String = NAMES[op]!!.third): Any? {
        val (name, rname, _) = NAMES[op]!!
        val ta = PyTypes.typeOf(a)
        val tb = PyTypes.typeOf(b)
        val fa = userMethod(ta, name)
        var fb = if (tb !== ta) userMethod(tb, rname) else null
        // a subclass's reflected method first, where it overrides it
        if (fb != null && tb.isSubtype(ta) && fb !== userMethod(ta, rname)) {
            val r = call(fb, b, a)
            if (r !== PyNotImplemented) return r
            fb = null
        }
        if (fa != null) {
            val r = call(fa, a, b)
            if (r !== PyNotImplemented) return r
        }
        if (fb != null) {
            val r = call(fb, b, a)
            if (r !== PyNotImplemented) return r
        }
        throw unsupported(sym, a, b)
    }

    @JvmStatic fun matmul(a: Any?, b: Any?): Any? = binary(a, b, PyOp.MATMUL)

    /** `a op= b` (binary opcode [op]): `__iop__` where a's class has it, a list changed in place, else `a op b`. */
    @JvmStatic
    fun inplace(a: Any?, b: Any?, op: Int): Any? {
        if (a is PyObject) {
            val f = userMethod(a.type, "__i" + NAMES[op]!!.first.substring(2))
            if (f != null) {
                val r = call(f, a, b)
                if (r !== PyNotImplemented) return r
            }
            return binary(a, b, op, NAMES[op]!!.third + "=")
        }
        if (b is PyObject) {
            if (a is PyList && op == PyOp.ADD && userMethod(b.type, "__radd__") == null) return extend(a, b)
            return binary(a, b, op, NAMES[op]!!.third + "=")
        }
        if (a is PyList) {
            if (op == PyOp.ADD) return extend(a, b)
            if (op == PyOp.MUL && PyRt.isInt(b)) {
                val r = PyRt.mul(a, b) as PyList
                a.items.clear()
                a.items.addAll(r.items)
                return a
            }
        }
        try {
            return byCode(op, a, b)
        } catch (e: PyException) {
            // (the message names the augmented operator)
            val sym = NAMES[op]!!.third
            if (e.type == "TypeError" && e.message == unsupported(sym, a, b).message) throw unsupported("$sym=", a, b)
            throw e
        }
    }

    /** list += iterable. */
    private fun extend(a: PyList, b: Any?): PyList {
        a.items.addAll(if (b is PyList) ArrayList(b.items) else PyBuiltins.toList(b))
        return a
    }

    private fun byCode(op: Int, a: Any?, b: Any?): Any? = when (op) {
        PyOp.ADD -> PyRt.add(a, b)
        PyOp.SUB -> PyRt.sub(a, b)
        PyOp.MUL -> PyRt.mul(a, b)
        PyOp.TRUEDIV -> PyRt.truediv(a, b)
        PyOp.FLOORDIV -> PyRt.floordiv(a, b)
        PyOp.MOD -> PyRt.mod(a, b)
        PyOp.POW -> PyRt.pow(a, b)
        PyOp.LSHIFT -> PyRt.lshift(a, b)
        PyOp.RSHIFT -> PyRt.rshift(a, b)
        PyOp.BITAND -> PyRt.bitAnd(a, b)
        PyOp.BITOR -> PyRt.bitOr(a, b)
        PyOp.BITXOR -> PyRt.bitXor(a, b)
        else -> matmul(a, b)
    }

    // ------------------------------------------------------------------ comparisons

    /** a == b with a class among them: __eq__, reflected, then identity. */
    @JvmStatic
    fun eq(a: Any?, b: Any?): Boolean = rich(a, b, "__eq__", "__eq__") ?: (a === b)

    @JvmStatic
    fun ne(a: Any?, b: Any?): Boolean {
        rich(a, b, "__ne__", "__ne__")?.let { return it }
        // object's __ne__: the inverse of __eq__
        return !eq(a, b)
    }

    private val ORDER = arrayOf("__lt__" to "__gt__", "__le__" to "__ge__", "__gt__" to "__lt__", "__ge__" to "__le__")
    private val ORDER_SYM = arrayOf("<", "<=", ">", ">=")

    /** a < b (op 0), <= (1), > (2), >= (3) with a class among them. */
    @JvmStatic
    fun order(op: Int, a: Any?, b: Any?): Boolean {
        val (n, r) = ORDER[op]
        return rich(a, b, n, r) ?: throw PyRt.typeError("'${ORDER_SYM[op]}' not supported between instances of '${PyTypes.name(a)}' and '${PyTypes.name(b)}'")
    }

    /**
     * A rich comparison's truth, or null where no side has it (or each said NotImplemented): b's reflected method
     * first where b's type derives from a's, then a's, then b's reflected one (also where the types are the same).
     */
    private fun rich(a: Any?, b: Any?, name: String, rname: String): Boolean? {
        val ta = PyTypes.typeOf(a)
        val tb = PyTypes.typeOf(b)
        var checkedReverse = false
        if (ta !== tb && tb.isSubtype(ta)) {
            checkedReverse = true
            userMethod(tb, rname)?.let { f ->
                val r = call(f, b, a)
                if (r !== PyNotImplemented) return PyRt.truthy(r)
            }
        }
        userMethod(ta, name)?.let { f ->
            val r = call(f, a, b)
            if (r !== PyNotImplemented) return PyRt.truthy(r)
        }
        if (!checkedReverse) userMethod(tb, rname)?.let { f ->
            val r = call(f, b, a)
            if (r !== PyNotImplemented) return PyRt.truthy(r)
        }
        return null
    }

    // ------------------------------------------------------------------ hash, str, repr, truth, len

    /** An instance's hash as dicts use it: its class's __hash__ (None: unhashable), else its identity. */
    @JvmStatic
    fun hash(v: PyObject): Int {
        val h = v.type.lookup("__hash__")
        if (h === PyNone) throw PyRt.typeError("unhashable type: '${v.type.name}'")
        if (h == null || h is PyBuiltin) return System.identityHashCode(v)
        return when (val r = call(h, v)) {
            is Long -> java.lang.Long.hashCode(r)
            is BigInteger -> r.hashCode()
            is PyBool -> if (r.value) 1 else 0
            else -> throw PyRt.typeError("__hash__ method should return an integer")
        }
    }

    private const val HASH_MOD = (1L shl 61) - 1

    /** hash(v), the builtin: an int's as CPython reduces it (modulo 2**61 - 1, -1 being -2), an instance's __hash__'s. */
    @JvmStatic
    fun hashValue(v: Any?): Long = when (v) {
        is PyBool -> if (v.value) 1L else 0L
        is Long -> intHash(BigInteger.valueOf(v))
        is BigInteger -> intHash(v)
        is PyObject -> {
            val h = v.type.lookup("__hash__")
            when {
                h === PyNone -> throw PyRt.typeError("unhashable type: '${v.type.name}'")
                h == null || h is PyBuiltin -> System.identityHashCode(v).toLong()
                else -> when (val r = call(h, v)) {
                    is Long -> intHash(BigInteger.valueOf(r))
                    is BigInteger -> intHash(r)
                    is PyBool -> if (r.value) 1L else 0L
                    else -> throw PyRt.typeError("__hash__ method should return an integer")
                }
            }
        }
        else -> PyRt.hash(v).toLong()
    }

    private fun intHash(v: BigInteger): Long {
        val m = v.abs().mod(BigInteger.valueOf(HASH_MOD)).toLong()
        val r = if (v.signum() < 0) -m else m
        return if (r == -1L) -2L else r
    }

    @JvmStatic
    fun str(v: PyObject): String {
        val f = userMethod(v.type, "__str__") ?: return repr(v)
        val r = call(f, v)
        return (r as? PyStr ?: throw PyRt.typeError("__str__ returned non-string (type ${PyTypes.name(r)})")).s
    }

    @JvmStatic
    fun repr(v: PyObject): String {
        val f = userMethod(v.type, "__repr__") ?: return defaultRepr(v)
        val r = call(f, v)
        return (r as? PyStr ?: throw PyRt.typeError("__repr__ returned non-string (type ${PyTypes.name(r)})")).s
    }

    /** object.__repr__: `<module.Qualname object at 0x...>`. */
    @JvmStatic
    fun defaultRepr(v: Any?): String {
        val t = PyTypes.typeOf(v)
        return "<${t.module}.${t.qualname} object at 0x${Integer.toHexString(System.identityHashCode(v)).padStart(16, '0').uppercase()}>"
    }

    @JvmStatic
    fun truthy(v: PyObject): Boolean {
        userMethod(v.type, "__bool__")?.let { f ->
            val r = call(f, v)
            return (r as? PyBool ?: throw PyRt.typeError("__bool__ should return bool, returned ${PyTypes.name(r)}")).value
        }
        if (userMethod(v.type, "__len__") != null) return len(v) > 0
        return true
    }

    /** len(v) of an instance: its __len__, an int not below 0. */
    @JvmStatic
    fun len(v: Any?): Long {
        val f = (if (v is PyObject) userMethod(v.type, "__len__") else null)
            ?: throw PyRt.typeError("object of type '${PyTypes.name(v)}' has no len()")
        val n = when (val r = call(f, v)) {
            is Long -> r
            is PyBool -> if (r.value) 1L else 0L
            is BigInteger -> if (r.signum() < 0) -1L else throw PyException("OverflowError", "cannot fit 'int' into an index-sized integer")
            else -> throw PyRt.typeError("'${PyTypes.name(r)}' object cannot be interpreted as an integer")
        }
        if (n < 0) throw PyException("ValueError", "__len__() should return >= 0")
        return n
    }

    // ------------------------------------------------------------------ iteration, items, containment

    /** An instance's __iter__ called: an iterator (the runtime's, or an instance with __next__), else the TypeError. */
    private fun callIter(v: PyObject, f: Any?): Any? {
        if (f === PyNone) throw PyRt.typeError("'${v.type.name}' object is not iterable")
        val it = call(f, v)
        if (it is PyIterator || it is PyObject && userMethod(it.type, "__next__") != null) return it
        throw PyRt.typeError("iter() returned non-iterator of type '${PyTypes.name(it)}'")
    }

    /** iter(v) as `for` uses it: __iter__'s iterator (stepped by its __next__), or the sequence protocol of __getitem__. */
    @JvmStatic
    fun iter(v: PyObject): PyIterator {
        val f = userMethod(v.type, "__iter__")
        if (f != null) {
            val it = callIter(v, f)
            return it as? PyIterator ?: wrap(it as PyObject)
        }
        val gi = userMethod(v.type, "__getitem__") ?: throw PyRt.typeError("'${v.type.name}' object is not iterable")
        var i = 0L
        var done = false
        return PyIterator {
            if (done) PyIterator.STOP else try {
                call(gi, v, i++)
            } catch (e: PyException) {
                if (e.type == "IndexError" || e.type == "StopIteration") {
                    done = true
                    PyIterator.STOP
                } else throw e
            }
        }
    }

    /** iter(v), the builtin: the iterator __iter__ gave as it is (an instance stays one). */
    @JvmStatic
    fun iterObject(v: Any?): Any? {
        if (v is PyObject) {
            val f = userMethod(v.type, "__iter__")
            if (f != null) return callIter(v, f)
        }
        return PyRt.iter(v)
    }

    /** An iterator of a class's: its __next__ until StopIteration. */
    @JvmStatic
    fun wrap(it: PyObject): PyIterator {
        val next = userMethod(it.type, "__next__")!!
        return PyIterator {
            try {
                call(next, it)
            } catch (e: PyException) {
                if (e.type == "StopIteration") PyIterator.STOP else throw e
            }
        }
    }

    /** next(it): a runtime iterator's next value, or a class's __next__'s; [PyIterator.STOP] at the end. */
    @JvmStatic
    fun next(it: Any?): Any? = when (it) {
        is PyIterator -> it.next()
        is PyObject -> {
            val f = userMethod(it.type, "__next__") ?: throw PyRt.typeError("'${it.type.name}' object is not an iterator")
            try {
                call(f, it)
            } catch (e: PyException) {
                if (e.type == "StopIteration") PyIterator.STOP else throw e
            }
        }
        else -> throw PyRt.typeError("'${PyTypes.name(it)}' object is not an iterator")
    }

    @JvmStatic
    fun getItem(obj: PyObject, key: Any?): Any? {
        val f = userMethod(obj.type, "__getitem__") ?: throw PyRt.typeError("'${obj.type.name}' object is not subscriptable")
        return call(f, obj, key)
    }

    @JvmStatic
    fun setItem(obj: PyObject, key: Any?, v: Any?) {
        val f = userMethod(obj.type, "__setitem__") ?: throw PyRt.typeError("'${obj.type.name}' object does not support item assignment")
        call(f, obj, key, v)
    }

    @JvmStatic
    fun delItem(obj: PyObject, key: Any?) {
        val f = userMethod(obj.type, "__delitem__") ?: throw PyRt.typeError("'${obj.type.name}' object doesn't support item deletion")
        call(f, obj, key)
    }

    /** `v in container`: __contains__, else iteration (__iter__ or __getitem__). */
    @JvmStatic
    fun contains(container: PyObject, v: Any?): Boolean {
        userMethod(container.type, "__contains__")?.let { return PyRt.truthy(call(it, container, v)) }
        if (userMethod(container.type, "__iter__") == null && userMethod(container.type, "__getitem__") == null) {
            throw PyRt.typeError("argument of type '${container.type.name}' is not a container or iterable")
        }
        val it = iter(container)
        while (true) {
            val x = it.next()
            if (x === PyIterator.STOP) return false
            if (x === v || PyRt.eq(x, v)) return true
        }
    }

    // ------------------------------------------------------------------ unary

    /** -v, +v, ~v ([name] __neg__, __pos__, __invert__; [sym] for the message) of an instance. */
    @JvmStatic
    fun unary(v: Any?, name: String, sym: String): Any? {
        val f = (if (v is PyObject) userMethod(v.type, name) else null) ?: throw PyRt.typeError("bad operand type for unary $sym: '${PyTypes.name(v)}'")
        return call(f, v)
    }

    @JvmStatic
    fun abs(v: Any?): Any? {
        val f = (if (v is PyObject) userMethod(v.type, "__abs__") else null) ?: throw PyRt.typeError("bad operand type for abs(): '${PyTypes.name(v)}'")
        return call(f, v)
    }

    // ------------------------------------------------------------------ object's own

    /** object's special methods: what `super().__eq__(o)`, `object.__setattr__(self, n, v)` and the like call. */
    internal fun defineObjectMethods() {
        val o = PyTypes.OBJECT
        o.def("__eq__", PyBuiltin("__eq__") { _, a -> if (a[0] === a[1]) PyBool.TRUE else PyNotImplemented })
        o.def("__ne__", PyBuiltin("__ne__") { ctx, a ->
            val eqm = PyTypes.typeOf(a[0]).lookup("__eq__")
            val r = PyCalls.callWith(ctx, eqm, arrayOf(a[0], a[1]), NO_ARGS, NO_ARGS)
            if (r === PyNotImplemented) r else PyBool.of(!PyRt.truthy(r))
        })
        for (n in arrayOf("__lt__", "__le__", "__gt__", "__ge__")) o.def(n, PyBuiltin(n) { _, _ -> PyNotImplemented })
        o.def("__hash__", PyBuiltin("__hash__") { _, a -> System.identityHashCode(a[0]).toLong() })
        o.def("__repr__", PyBuiltin("__repr__") { _, a -> PyStr(defaultRepr(a[0])) })
        o.def("__str__", PyBuiltin("__str__") { _, a -> PyStr(PyRt.repr(a[0])) })
        o.def("__getattribute__", PyBuiltin("__getattribute__") { _, a ->
            PyClasses.genericGetAttr(a[0], (a[1] as? PyStr ?: throw PyRt.typeError("attribute name must be string, not '${PyTypes.name(a[1])}'")).s)
        })
        o.def("__setattr__", PyBuiltin("__setattr__") { _, a ->
            PyClasses.genericSetAttr(a[0], (a[1] as? PyStr ?: throw PyRt.typeError("attribute name must be string, not '${PyTypes.name(a[1])}'")).s, a[2])
            PyNone
        })
        o.def("__delattr__", PyBuiltin("__delattr__") { _, a ->
            PyClasses.genericDelAttr(a[0], (a[1] as? PyStr ?: throw PyRt.typeError("attribute name must be string, not '${PyTypes.name(a[1])}'")).s)
            PyNone
        })
    }
}
