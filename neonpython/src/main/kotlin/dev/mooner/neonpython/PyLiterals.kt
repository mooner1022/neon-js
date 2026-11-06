package dev.mooner.neonpython

import java.math.BigInteger

/** string.templatelib.Template: a t-string's strings (one more than its interpolations) and interpolations. */
class PyTemplate(@JvmField val strings: Array<Any?>, @JvmField val interpolations: Array<Any?>)

/** string.templatelib.Interpolation: a t-string's field: its value, its source, its conversion, its spec. */
class PyInterpolation(@JvmField val value: Any?, @JvmField val expression: Any?, @JvmField val conversion: Any?, @JvmField val formatSpec: Any?)

/**
 * The types behind literals that are not the slice's first ones (docs/PYTHON_PLATFORM.md §11, M5): complex numbers
 * (CPython's arithmetic: real operands not made complex, as 3.14's; integer powers by squaring), bytes, and t-strings'
 * templates (PEP 750).
 */
object PyLiterals {
    // ------------------------------------------------------------------ complex

    private fun cx(v: Any?): PyComplex? = when (v) {
        is PyComplex -> v
        is Long, is BigInteger, is PyBool, is Double -> null
        else -> null
    }

    private fun real(v: Any?): Double? = if (PyRt.isNumber(v)) PyRt.toDouble(v) else null

    private fun prod(a: PyComplex, b: PyComplex) = PyComplex(a.real * b.real - a.imag * b.imag, a.real * b.imag + a.imag * b.real)

    /** _Py_c_quot: Smith's division. */
    private fun quot(a: PyComplex, b: PyComplex): PyComplex {
        val abr = Math.abs(b.real)
        val abi = Math.abs(b.imag)
        if (abr >= abi) {
            if (abr == 0.0) throw PyException("ZeroDivisionError", "division by zero")
            val ratio = b.imag / b.real
            val denom = b.real + b.imag * ratio
            return PyComplex((a.real + a.imag * ratio) / denom, (a.imag - a.real * ratio) / denom)
        }
        if (abi >= abr) {
            val ratio = b.real / b.imag
            val denom = b.real * ratio + b.imag
            return PyComplex((a.real * ratio + a.imag) / denom, (a.imag * ratio - a.real) / denom)
        }
        return PyComplex(Double.NaN, Double.NaN)
    }

    private val ONE = PyComplex(1.0, 0.0)

    private fun powu(x: PyComplex, n: Long): PyComplex {
        var r = ONE
        var p = x
        var mask = 1L
        while (mask > 0 && n >= mask) {
            if (n and mask != 0L) r = prod(r, p)
            mask = mask shl 1
            p = prod(p, p)
        }
        return r
    }

    /** complex ** complex: by squaring for an integral exponent up to 100 (c_powi), else _Py_c_pow. */
    private fun pow(a: PyComplex, b: PyComplex): PyComplex {
        if (b.imag == 0.0 && b.real == Math.floor(b.real) && Math.abs(b.real) <= 100.0) {
            val n = b.real.toLong()
            if (n > 0) return powu(a, n)
            if (n == 0L) return ONE
            if (a.real == 0.0 && a.imag == 0.0) throw PyException("ZeroDivisionError", "zero to a negative or complex power")
            return quot(ONE, powu(a, -n))
        }
        if (b.real == 0.0 && b.imag == 0.0) return ONE
        if (a.real == 0.0 && a.imag == 0.0) {
            if (b.imag != 0.0 || b.real < 0.0) throw PyException("ZeroDivisionError", "zero to a negative or complex power")
            return PyComplex(0.0, 0.0)
        }
        val vabs = Math.hypot(a.real, a.imag)
        var len = Math.pow(vabs, b.real)
        val at = Math.atan2(a.imag, a.real)
        var phase = at * b.real
        if (b.imag != 0.0) {
            len /= Math.exp(at * b.imag)
            phase += b.imag * Math.log(vabs)
        }
        return PyComplex(len * Math.cos(phase), len * Math.sin(phase))
    }

    /** a op b where one is a complex number (+ - * / **); null where they are not numbers. */
    @JvmStatic
    fun complexOp(op: Int, a: Any?, b: Any?): Any? {
        val ca = cx(a)
        val cb = cx(b)
        if (ca == null && cb == null) return null
        val ra = real(a)
        val rb = real(b)
        if (ca == null && ra == null || cb == null && rb == null) return null
        return when (op) {
            PyOp.ADD -> if (ca == null) PyComplex(ra!! + cb!!.real, cb.imag) else if (cb == null) PyComplex(ca.real + rb!!, ca.imag) else PyComplex(ca.real + cb.real, ca.imag + cb.imag)
            PyOp.SUB -> if (ca == null) PyComplex(ra!! - cb!!.real, -cb.imag) else if (cb == null) PyComplex(ca.real - rb!!, ca.imag) else PyComplex(ca.real - cb.real, ca.imag - cb.imag)
            PyOp.MUL -> if (ca == null) PyComplex(ra!! * cb!!.real, ra * cb.imag) else if (cb == null) PyComplex(ca.real * rb!!, ca.imag * rb) else prod(ca, cb)
            PyOp.TRUEDIV -> if (cb == null) {
                if (rb == 0.0) throw PyException("ZeroDivisionError", "division by zero")
                PyComplex(ca!!.real / rb!!, ca.imag / rb)
            } else quot(ca ?: PyComplex(ra!!, 0.0), cb)
            PyOp.POW -> pow(ca ?: PyComplex(ra!!, 0.0), cb ?: PyComplex(rb!!, 0.0))
            else -> null
        }
    }

    @JvmStatic fun complexEq(a: Any?, b: Any?): Boolean {
        val ca = cx(a)
        val cb = cx(b)
        if (ca != null && cb != null) return ca.real == cb.real && ca.imag == cb.imag
        val c = ca ?: cb!!
        val other = if (ca == null) a else b
        if (!PyRt.isNumber(other)) return false
        return c.imag == 0.0 && PyRt.eq(c.real, other)
    }

    /** complex(s): CPython's grammar of a complex in a str ("1+2j", " -3.5j ", "(1-1j)", "inf", "j"). */
    @JvmStatic
    fun parseComplex(str: String): PyComplex {
        fun bad(): Nothing = throw PyException("ValueError", "complex() arg is a malformed string")
        var s = str.trim()
        if (s.startsWith("(") && s.endsWith(")")) s = s.substring(1, s.length - 1).trim()
        if (s.isEmpty()) bad()
        fun num(t: String): Double {
            if (t.isEmpty() || t == "+" || t == "-") return if (t == "-") -1.0 else 1.0
            return when (t.lowercase().removePrefix("+")) {
                "inf", "infinity" -> Double.POSITIVE_INFINITY
                "-inf", "-infinity" -> Double.NEGATIVE_INFINITY
                "nan", "-nan" -> Double.NaN
                else -> t.replace("_", "").toDoubleOrNull()?.takeIf { !t.contains("__") && !t.startsWith("_") && !t.endsWith("_") } ?: bad()
            }
        }
        if (!s.endsWith("j") && !s.endsWith("J")) return PyComplex(num(s), 0.0)
        val body = s.substring(0, s.length - 1)
        // the split between the real part and the imaginary one: the last sign not after an exponent's e
        var k = body.length - 1
        while (k > 0 && !((body[k] == '+' || body[k] == '-') && body[k - 1] != 'e' && body[k - 1] != 'E')) k--
        return if (k <= 0) PyComplex(0.0, num(body)) else PyComplex(num(body.substring(0, k)), num(body.substring(k)))
    }

    // ------------------------------------------------------------------ bytes

    private fun u(b: Byte) = b.toInt() and 0xFF

    /**
     * bytes + a bytes-like object (bytes), a bytearray + one (a bytearray); null where a is neither, CPython's error
     * where b is not bytes-like.
     */
    @JvmStatic
    fun bytesAdd(a: Any?, b: Any?): Any? {
        if (a !is PyBytes && a !is PyByteArray) return null
        val y = PyByteSeqs.content(b) ?: throw PyRt.typeError("can't concat ${PyTypes.name(b)} to ${if (a is PyBytes) "bytes" else "bytearray"}")
        val x = PyByteSeqs.content(a)!!
        return if (a is PyBytes) PyBytes(x + y) else PyByteArray(x + y)
    }

    @JvmStatic
    fun bytesMul(a: Any?, b: Any?): Any? {
        val (bs, n) = if (a is PyBytes || a is PyByteArray) a to b else if (b is PyBytes || b is PyByteArray) b to a else return null
        if (!PyRt.isInt(n)) throw PyRt.typeError("can't multiply sequence by non-int of type '${PyTypes.name(n)}'")
        val k = (n as? Long ?: if (n is PyBool) (if (n.value) 1L else 0L) else throw PyException("OverflowError", "cannot fit 'int' into an index-sized integer")).toInt().coerceAtLeast(0)
        val one = PyByteSeqs.content(bs)!!
        val out = ByteArray(one.size * k)
        for (i in 0 until k) one.copyInto(out, i * one.size)
        return if (bs is PyBytes) PyBytes(out) else PyByteArray(out)
    }

    @JvmStatic
    fun bytesItem(bs: PyBytes, key: Any?): Any? {
        if (key is PySlice) {
            val (a, _, st, n) = key.indices(bs.b.size.toLong())
            val out = ByteArray(n.toInt())
            var i = a
            for (k in 0 until n.toInt()) { out[k] = bs.b[i.toInt()]; i += st }
            return PyBytes(out)
        }
        val i = key as? Long ?: if (key is PyBool) (if (key.value) 1L else 0L) else throw PyRt.typeError("byte indices must be integers or slices, not ${PyTypes.name(key)}")
        val k = if (i < 0) i + bs.b.size else i
        if (k < 0 || k >= bs.b.size) throw PyException("IndexError", "index out of range")
        return u(bs.b[k.toInt()]).toLong()
    }

    private fun indexOf(h: ByteArray, n: ByteArray, from: Int): Int {
        outer@ for (i in from..h.size - n.size) {
            for (j in n.indices) if (h[i + j] != n[j]) continue@outer
            return i
        }
        return -1
    }

    @JvmStatic
    fun bytesContains(bs: PyBytes, v: Any?): Boolean = when (v) {
        is PyBytes -> indexOf(bs.b, v.b, 0) >= 0
        is Long -> {
            if (v < 0 || v > 255) throw PyException("ValueError", "byte must be in range(0, 256)")
            bs.b.any { u(it).toLong() == v }
        }
        else -> throw PyRt.typeError("a bytes-like object is required, not '${PyTypes.name(v)}'")
    }

    @JvmStatic
    fun bytesCompare(a: PyBytes, b: PyBytes): Int {
        val n = minOf(a.b.size, b.b.size)
        for (i in 0 until n) if (a.b[i] != b.b[i]) return u(a.b[i]).compareTo(u(b.b[i]))
        return a.b.size.compareTo(b.b.size)
    }

    private fun charset(enc: String): java.nio.charset.Charset = when (enc.lowercase().replace('_', '-')) {
        "utf-8", "utf8" -> Charsets.UTF_8
        "ascii", "us-ascii" -> Charsets.US_ASCII
        "latin-1", "latin1", "iso-8859-1", "iso8859-1" -> Charsets.ISO_8859_1
        "utf-16" -> Charsets.UTF_16
        else -> throw PyException("LookupError", "unknown encoding: $enc")
    }

    /** str.encode: utf-8, ascii and latin-1, strictly (CPython's UnicodeEncodeError). */
    @JvmStatic
    fun encode(s: String, enc: String): PyBytes {
        val cs = charset(enc)
        val e = cs.newEncoder()
        for ((i, c) in s.withIndex()) if (!e.canEncode(c)) {
            val name = if (cs == Charsets.US_ASCII) "ascii" else if (cs == Charsets.ISO_8859_1) "latin-1" else "utf-8"
            val esc = if (c.code < 0x100) "\\x" + Integer.toHexString(c.code).padStart(2, '0') else "\\u" + Integer.toHexString(c.code).padStart(4, '0')
            throw PyException("UnicodeEncodeError", "'$name' codec can't encode character '$esc' in position $i: ordinal not in range(${if (name == "ascii") 128 else 256})")
        }
        return PyBytes(s.toByteArray(cs))
    }

    /** bytes.decode: strictly (CPython's UnicodeDecodeError at the first bad byte), or bad bytes left out ('ignore') or replaced ('replace'). */
    @JvmStatic
    fun decode(b: ByteArray, enc: String, errors: String = "strict"): String {
        val cs = charset(enc)
        val d = cs.newDecoder()
        if (errors == "ignore" || errors == "replace") {
            val action = if (errors == "ignore") java.nio.charset.CodingErrorAction.IGNORE else java.nio.charset.CodingErrorAction.REPLACE
            return d.onMalformedInput(action).onUnmappableCharacter(action).decode(java.nio.ByteBuffer.wrap(b)).toString()
        }
        if (errors != "strict") throw PyException("LookupError", "unknown error handler name '$errors'")
        return try {
            d.decode(java.nio.ByteBuffer.wrap(b)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            val pos = (0 until b.size).firstOrNull { i -> runCatching { cs.newDecoder().decode(java.nio.ByteBuffer.wrap(b, 0, i + 1)) }.isFailure && u(b[i]) >= 0x80 } ?: 0
            val name = if (cs == Charsets.US_ASCII) "ascii" else "utf-8"
            val why = if (name == "ascii") "ordinal not in range(128)" else "invalid start byte"
            throw PyException("UnicodeDecodeError", "'$name' codec can't decode byte 0x${Integer.toHexString(u(b[pos])).padStart(2, '0')} in position $pos: $why")
        }
    }

    /** bytes(source=b'', encoding, errors), as CPython takes them (PyByteSeqs.bytesOf). */
    @JvmStatic
    fun newBytes(args: Array<Any?>, kw: PyDict? = null): PyBytes {
        if (args.size > 3) throw PyRt.typeError("bytes() takes at most 3 arguments (${args.size} given)")
        val x = arrayOfNulls<Any?>(3)
        args.copyInto(x)
        kw?.map?.forEach { (k, v) ->
            val i = listOf("source", "encoding", "errors").indexOf((k.v as PyStr).s)
            if (i < 0) throw PyRt.typeError("bytes() got an unexpected keyword argument '${(k.v as PyStr).s}'")
            x[i] = v
        }
        if (x[0] == null) {
            if (x[1] != null || x[2] != null) throw PyRt.typeError("encoding or errors without sequence argument")
            return PyBytes(ByteArray(0))
        }
        return PyBytes(PyByteSeqs.bytesOf(x[0], x[1], x[2], "bytes must be in range(0, 256)", true))
    }

    // ------------------------------------------------------------------ t-strings

    /** BUILD_INTERPOLATION */
    @JvmStatic
    fun buildInterpolation(v: Any?, expr: Any?, conv: Any?, spec: Any?): Any? = PyInterpolation(v, expr, conv, spec)

    /** BUILD_TEMPLATE: [strings] (a tuple, one more than the interpolations) and [interps] (a tuple). */
    @JvmStatic
    fun buildTemplate(strings: Any?, interps: Any?): Any? = PyTemplate((strings as PyTuple).items, (interps as PyTuple).items)

    /** template + template: the last string of the one and the first of the other joined. */
    @JvmStatic
    fun templateAdd(a: Any?, b: Any?): Any? {
        if (a !is PyTemplate && b !is PyTemplate) return null
        if (a !is PyTemplate || b !is PyTemplate) {
            val other = if (a is PyTemplate) b else a
            throw PyRt.typeError(if (a is PyTemplate) "can only concatenate string.templatelib.Template (not \"${PyTypes.name(other)}\") to string.templatelib.Template"
                else "can only concatenate str (not \"string.templatelib.Template\") to str")
        }
        val s = ArrayList<Any?>(a.strings.asList().dropLast(1))
        s.add(PyStr((a.strings.last() as PyStr).s + (b.strings.first() as PyStr).s))
        s.addAll(b.strings.asList().drop(1))
        val interps = arrayOfNulls<Any?>(a.interpolations.size + b.interpolations.size)
        a.interpolations.copyInto(interps)
        b.interpolations.copyInto(interps, a.interpolations.size)
        return PyTemplate(s.toTypedArray(), interps)
    }

    /** Iterating a template: its strs that are not empty and its interpolations, in order. */
    @JvmStatic
    fun templateIter(t: PyTemplate): PyIterator {
        val parts = ArrayList<Any?>()
        for (i in t.strings.indices) {
            if ((t.strings[i] as PyStr).s.isNotEmpty()) parts.add(t.strings[i])
            if (i < t.interpolations.size) parts.add(t.interpolations[i])
        }
        var k = 0
        return PyIterator { if (k < parts.size) parts[k++] else PyIterator.STOP }
    }

    @JvmStatic
    fun templateRepr(t: PyTemplate): String =
        "Template(strings=${PyRt.repr(PyTuple(t.strings))}, interpolations=${PyRt.repr(PyTuple(t.interpolations))})"

    @JvmStatic
    fun interpolationRepr(i: PyInterpolation): String =
        "Interpolation(${PyRt.repr(i.value)}, ${PyRt.repr(i.expression)}, ${PyRt.repr(i.conversion)}, ${PyRt.repr(i.formatSpec)})"

    // ------------------------------------------------------------------ the types' methods

    @JvmStatic
    fun define(d: PyDict) {
        val ct = PyTypes.COMPLEX
        fun prop(t: PyType, n: String, get: (Any?) -> Any?) =
            t.def(n, PyProperty(PyBuiltin(n) { _, a -> get(a[0]) }, null, null, null).also { it.name = n })
        fun m(t: PyType, n: String, impl: (Array<Any?>) -> Any?) = t.def(n, PyBuiltin(n) { _, a -> impl(a) })
        // complex, and the real and imaginary parts of ints and floats
        prop(ct, "real") { (it as PyComplex).real }
        prop(ct, "imag") { (it as PyComplex).imag }
        m(ct, "conjugate") { a -> val c = a[0] as PyComplex; PyComplex(c.real, -c.imag) }
        for (t in arrayOf(PyTypes.INT, PyTypes.BOOL)) {
            prop(t, "real") { if (it is PyBool) (if (it.value) 1L else 0L) else it }
            prop(t, "imag") { 0L }
            m(t, "conjugate") { a -> a[0].let { if (it is PyBool) (if (it.value) 1L else 0L) else it } }
        }
        prop(PyTypes.FLOAT, "real") { it }
        prop(PyTypes.FLOAT, "imag") { 0.0 }
        m(PyTypes.FLOAT, "conjugate") { a -> a[0] }
        ct.newImpl = { _, _, a, kw ->
            if (kw != null && kw.size > 0) throw PyRt.typeError("complex() takes no keyword arguments")
            if (a.size > 2) throw PyRt.typeError("complex() takes at most 2 arguments (${a.size} given)")
            val re = a.getOrNull(0)
            if (re is PyStr) {
                if (a.size > 1) throw PyRt.typeError("complex() can't take second arg if first is a string")
                parseComplex(re.s)
            } else {
                // (complex_new: a part added only where an argument is complex, so that a zero keeps its sign)
                val im = a.getOrNull(1)
                var crReal = when (re) { null -> 0.0; is PyComplex -> re.real; else -> PyRt.toDouble(re) }
                val crImag = if (re is PyComplex) re.imag else 0.0
                var ciReal = when (im) { null -> 0.0; is PyComplex -> im.real; else -> PyRt.toDouble(im) }
                if (im is PyComplex) crReal -= im.imag
                if (re is PyComplex && im != null) ciReal += crImag
                if (im == null) PyComplex(crReal, crImag) else PyComplex(crReal, ciReal)
            }
        }
        d[PyStr("complex")] = ct
        // bytes
        val bt = PyTypes.BYTES
        bt.newImpl = { _, _, a, kw -> newBytes(a, kw?.takeIf { it.size > 0 }) }
        d[PyStr("bytes")] = bt
        PyTypes.STR.def("encode", PyBuiltin("encode") { _, a -> encode((a[0] as PyStr).s, (a.getOrNull(1) as? PyStr)?.s ?: "utf-8") })
    }
}
