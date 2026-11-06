package dev.mooner.neonpython

/**
 * `_operator`, as far as CPython's C differs from the pure operator.py that otherwise runs (docs/PYTHON_PLATFORM.md
 * §12.2): index (PyNumber_Index's TypeError where the pure one's `a.__index__()` raises AttributeError) and
 * _compare_digest (hmac's, which the pure module has none of). operator.py's `from _operator import *` takes these over
 * its own.
 */
object PyOperator {
    @JvmStatic
    fun fill(d: PyDict) {
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
