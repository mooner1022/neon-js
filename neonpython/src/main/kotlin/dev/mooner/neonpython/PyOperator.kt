package dev.mooner.neonpython

/**
 * `_operator`: the C functions operator.py's `from _operator import *` takes over its own (docs/PYTHON_PLATFORM.md
 * §12.2), builtins as CPython's are, so that a class holding one (`concat_path = operator.add`) gives it unbound;
 * index (PyNumber_Index's TypeError) and _compare_digest (hmac's). concat, length_hint and the getter classes stay
 * operator.py's.
 */
object PyOperator {
    @JvmStatic
    fun fill(d: PyDict) {
        fun def(name: String, impl: (PyContext, Array<Any?>) -> Any?) {
            d[PyStr(name)] = PyBuiltin(name, impl)
        }
        fun unary(name: String, f: (Any?) -> Any?) = def(name) { _, a ->
            if (a.size != 1) throw PyRt.typeError("_operator.$name() takes exactly one argument (${a.size} given)")
            f(a[0])
        }
        fun binary(name: String, f: (Any?, Any?) -> Any?) = def(name) { _, a ->
            if (a.size != 2) throw PyRt.typeError("$name expected 2 arguments, got ${a.size}")
            f(a[0], a[1])
        }
        for ((name, op) in listOf("lt" to PyOp.LT, "le" to PyOp.LE, "eq" to PyOp.EQ, "ne" to PyOp.NE, "ge" to PyOp.GE, "gt" to PyOp.GT)) {
            binary(name) { x, y -> PyRt.compareValue(op, x, y) }
        }
        unary("not_") { PyRt.not(it) }
        unary("truth") { PyBool.of(PyRt.truthy(it)) }
        binary("is_") { x, y -> PyBool.of(PyRt.same(x, y)) }
        binary("is_not") { x, y -> PyBool.of(!PyRt.same(x, y)) }
        unary("is_none") { PyBool.of(it === PyNone) }
        unary("is_not_none") { PyBool.of(it !== PyNone) }
        unary("abs") { v ->
            when (v) {
                is Double -> Math.abs(v)
                is PyComplex -> Math.hypot(v.real, v.imag)
                else -> if (PyRt.isInt(v)) (if (PyRt.order(0, v, 0L)) PyRt.neg(v) else PyRt.pos(v)) else PyDunder.abs(v)
            }
        }
        binary("add", PyRt::add)
        binary("sub", PyRt::sub)
        binary("mul", PyRt::mul)
        binary("truediv", PyRt::truediv)
        binary("floordiv", PyRt::floordiv)
        binary("mod", PyRt::mod)
        binary("pow", PyRt::pow)
        binary("lshift", PyRt::lshift)
        binary("rshift", PyRt::rshift)
        binary("and_", PyRt::bitAnd)
        binary("or_", PyRt::bitOr)
        binary("xor", PyRt::bitXor)
        binary("matmul", PyDunder::matmul)
        unary("neg", PyRt::neg)
        unary("pos", PyRt::pos)
        unary("inv", PyRt::invert)
        unary("invert", PyRt::invert)
        for ((name, op) in listOf("iadd" to PyOp.ADD, "isub" to PyOp.SUB, "imul" to PyOp.MUL, "itruediv" to PyOp.TRUEDIV,
            "ifloordiv" to PyOp.FLOORDIV, "imod" to PyOp.MOD, "ipow" to PyOp.POW, "ilshift" to PyOp.LSHIFT, "irshift" to PyOp.RSHIFT,
            "iand" to PyOp.BITAND, "ior" to PyOp.BITOR, "ixor" to PyOp.BITXOR, "imatmul" to PyOp.MATMUL)) {
            binary(name) { x, y -> PyRt.inplace(x, y, op) }
        }
        binary("contains") { x, y -> PyBool.of(PyRt.contains(x, y)) }
        binary("getitem", PyRt::getItem)
        binary("delitem") { x, y -> PyCalls.deleteSubscr(x, y); PyNone }
        def("setitem") { _, a ->
            if (a.size != 3) throw PyRt.typeError("setitem expected 3 arguments, got ${a.size}")
            PyRt.setItem(a[0], a[1], a[2])
            PyNone
        }
        // (PySequence_Count, PySequence_Index: by identity, else ==)
        binary("countOf") { seq, x ->
            val it = PyRt.iter(seq)
            var n = 0L
            while (true) {
                val v = it.next()
                if (v === PyIterator.STOP) break
                if (v === x || PyRt.eq(v, x)) n++
            }
            n
        }
        binary("indexOf") { seq, x ->
            val it = PyRt.iter(seq)
            var i = 0L
            while (true) {
                val v = it.next()
                if (v === PyIterator.STOP) throw PyException("ValueError", "sequence.index(x): x not in sequence")
                if (v === x || PyRt.eq(v, x)) return@binary i
                i++
            }
            @Suppress("UNREACHABLE_CODE") i
        }
        val noKw = arrayOfNulls<Any?>(0)
        d[PyStr("call")] = PyBuiltin("call", { ctx, a ->
            if (a.isEmpty()) throw PyRt.typeError("call() missing required argument 'obj' (pos 1)")
            PyCalls.callWith(ctx, a[0], a.copyOfRange(1, a.size), noKw, noKw)
        }, { ctx, a, kw ->
            if (a.isEmpty()) throw PyRt.typeError("call() missing required argument 'obj' (pos 1)")
            val names = kw.map.keys.map { it.v }.toTypedArray<Any?>()
            val values = kw.map.values.toTypedArray<Any?>()
            PyCalls.callWith(ctx, a[0], a.copyOfRange(1, a.size), names, values)
        })
        d[PyStr("index")] = PyBuiltin("index") { _, a ->
            if (a.size != 1) throw PyRt.typeError("index() takes exactly one argument (${a.size} given)")
            index(a[0])
        }
        d[PyStr("_compare_digest")] = PyBuiltin("_compare_digest") { _, a ->
            if (a.size != 2) throw PyRt.typeError("_compare_digest expected 2 arguments, got ${a.size}")
            PyBool.of(compareDigest(a[0], a[1]))
        }
    }

    /** PyNumber_Index: an int as it is, else its `__index__`'s, which must be an int. */
    @JvmStatic
    fun index(v: Any?): Any? {
        if (v is Long || v is java.math.BigInteger) return v
        if (v is PyBool) return if (v === PyBool.TRUE) 1L else 0L
        val r = PyRt.indexValue(v) ?: throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer")
        return r
    }

    /** _compare_digest(a, b): ASCII strs or bytes-likes compared in time that does not depend on where they differ. */
    private fun compareDigest(a: Any?, b: Any?): Boolean {
        val x: ByteArray
        val y: ByteArray
        if (a is PyStr && b is PyStr) {
            if (a.s.any { it.code > 127 } || b.s.any { it.code > 127 }) {
                throw PyRt.typeError("comparing strings with non-ASCII characters is not supported")
            }
            x = a.s.toByteArray(Charsets.US_ASCII)
            y = b.s.toByteArray(Charsets.US_ASCII)
        } else {
            if (a is PyStr || b is PyStr) throw PyRt.typeError("a bytes-like object is required, not '${PyTypes.name(if (a is PyStr) a else b)}'")
            x = PyByteSeqs.content(a) ?: throw PyRt.typeError("unsupported operand types(s) or combination of types: '${PyTypes.name(a)}' and '${PyTypes.name(b)}'")
            y = PyByteSeqs.content(b) ?: throw PyRt.typeError("unsupported operand types(s) or combination of types: '${PyTypes.name(a)}' and '${PyTypes.name(b)}'")
        }
        return java.security.MessageDigest.isEqual(x, y)
    }
}
