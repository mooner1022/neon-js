package dev.mooner.neonpython

import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * `binascii` (docs/PYTHON_PLATFORM.md §12), as CPython 3.14 has it: Modules/binascii.c in Kotlin (uu, base64 with its
 * strict mode, hex with Python/pystrhex.c's separators, quoted-printable, CRC-CCITT and CRC-32), its two argument
 * converters (a bytes-like object; the ascii buffer that takes an ASCII str too), its messages; `Error` and
 * `Incomplete` each module's (each context's here). Also what `zlib` shares with it: buffers, C ints, checksums.
 */
object PyBinascii {
    const val DOC = "Conversion between binary data and ASCII"

    // ------------------------------------------------------------------ what binascii and zlib share

    /**
     * PyErr_NewException("[module].[name]", [base]): a class as type() makes it, its __module__ [module], its
     * __doc__ None.
     */
    internal fun newException(ctx: PyContext, module: String, name: String, base: PyType): PyType {
        val t = PyType(name, arrayOf(base), heap = true)
        t.mro = PyTypes.c3(t, t.bases)
        t.module = module
        t.dict[PyStr("__module__")] = PyStr(module)
        t.dict[PyStr("__doc__")] = PyNone
        base.addSubclass(ctx, t)
        t.hooks = PyClasses.hooksOf(t)
        return t
    }

    /** PyErr_SetString([type], [msg]): its context the exception being handled. */
    internal fun error(ctx: PyContext, type: PyType, msg: String): PyException {
        val o = PyBaseException(type)
        o.args = PyTuple(arrayOf(PyStr(msg)))
        val h = ctx.currentHandled()
        if (h != null) o.context = h
        return PyException(o).also { o.thrown = it }
    }

    /**
     * PyObject_GetBuffer(v, PyBUF_SIMPLE)'s bytes: a bytes' own array (not to be changed), a copy of a bytearray's or
     * of a (C-contiguous) memoryview's; else CPython's TypeError.
     */
    internal fun buffer(v: Any?): ByteArray = when (v) {
        is PyBytes -> v.b
        is PyByteArray -> v.bytes()
        is PyMemoryView -> {
            v.check()
            if (!v.contiguous()) throw PyException("BufferError", "memoryview: underlying buffer is not C-contiguous")
            v.bytes()
        }
        is PyNumSub -> (v.num as? PyBytes)?.b ?: throw notBytesLike(v)
        else -> throw notBytesLike(v)
    }

    /** Whether [v] has the buffer protocol (PyObject_CheckBuffer). */
    internal fun hasBuffer(v: Any?): Boolean = PyByteSeqs.isBytesLike(v)

    private fun notBytesLike(v: Any?) = PyRt.typeError("a bytes-like object is required, not '${PyTypes.name(v)}'")

    /** PyLong_AsInt. */
    internal fun cInt(v: Any?): Int = when (val i = PyRt.indexValue(v)) {
        is Long -> if (i < Int.MIN_VALUE || i > Int.MAX_VALUE) throw PyException("OverflowError", "Python int too large to convert to C int") else i.toInt()
        is BigInteger -> throw PyException("OverflowError", "Python int too large to convert to C int")
        else -> throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer")
    }

    /** `unsigned_int(bitwise=True)`: (unsigned int)PyLong_AsUnsignedLongMask, the int's low 32 bits. */
    internal fun uintMask(v: Any?): Long = when (val i = PyRt.indexValue(v)) {
        is Long -> i and 0xFFFFFFFFL
        is BigInteger -> i.toLong() and 0xFFFFFFFFL
        else -> throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer")
    }

    /** `Py_ssize_t`: _PyNumber_Index, then PyLong_AsSsize_t. */
    internal fun ssize(v: Any?): Long {
        val i = PyRt.indexValue(v) ?: throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer")
        return i as? Long ?: throw PyException("OverflowError", "Python int too large to convert to C ssize_t")
    }

    private val CRC_TABLE = IntArray(256).also { t ->
        for (n in 0 until 256) {
            var c = n
            for (k in 0 until 8) c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1
            t[n] = c
        }
    }

    /** CRC-32 of [len] bytes of [b] from [off], from [crc] (zlib's crc32(), binascii's internal_crc32). */
    @JvmStatic
    fun crc32(crc: Long, b: ByteArray, off: Int, len: Int): Long {
        var c = crc.toInt().inv()
        for (i in off until off + len) c = CRC_TABLE[(c xor b[i].toInt()) and 0xFF] xor (c ushr 8)
        return c.inv().toLong() and 0xFFFFFFFFL
    }

    /** Adler-32 of [len] bytes of [b] from [off], from [adler] (zlib's adler32()). */
    @JvmStatic
    fun adler32(adler: Long, b: ByteArray, off: Int, len: Int): Long {
        var a = adler and 0xFFFF
        var s = (adler ushr 16) and 0xFFFF
        var i = off
        val end = off + len
        while (i < end) {
            // (NMAX: the most bytes before the sums must be reduced)
            val n = minOf(5552, end - i)
            for (k in 0 until n) {
                a += b[i + k].toInt() and 0xFF
                s += a
            }
            i += n
            a %= 65521
            s %= 65521
        }
        return (s shl 16) or a
    }

    // ------------------------------------------------------------------ the module's state

    private class State(@JvmField val ctx: PyContext) {
        @JvmField val error: PyType = newException(ctx, "binascii", "Error", PyExceptions.type("ValueError"))
        @JvmField val incomplete: PyType = newException(ctx, "binascii", "Incomplete", PyExceptions.type("Exception"))

        fun error(msg: String) = error(ctx, error, msg)
    }

    /** ascii_buffer_converter: an ASCII str's bytes, else a bytes-like object's. */
    private fun asciiBuffer(v: Any?): ByteArray {
        if (v is PyStr) {
            val s = v.s
            for (c in s) if (c.code >= 128) throw PyException("ValueError", "string argument should contain only ASCII characters")
            return s.toByteArray(Charsets.ISO_8859_1)
        }
        return try {
            buffer(v)
        } catch (e: PyException) {
            throw PyRt.typeError("argument should be bytes, buffer or ASCII string, not '${PyTypes.name(v)}'")
        }
    }

    // ------------------------------------------------------------------ uu

    private fun a2bUu(st: State, data: ByteArray): PyBytes {
        var i = 0
        var asciiLen = data.size
        if (asciiLen == 0) throw st.error("Missing length byte")
        var binLen = ((data[i++].toInt() and 0xFF) - ' '.code) and 0x3F
        asciiLen--
        val out = ByteArray(binLen)
        var o = 0
        var leftbits = 0
        var leftchar = 0
        while (binLen > 0) {
            var ch = if (asciiLen > 0) data[i].toInt() and 0xFF else 0
            if (ch == '\n'.code || ch == '\r'.code || asciiLen <= 0) {
                // (whitespace: spaces eaten at the end of the line, assumed)
                ch = 0
            } else {
                // (64, not 63: some uuencodes write '`' for zero)
                if (ch < ' '.code || ch > ' '.code + 64) throw st.error("Illegal char")
                ch = (ch - ' '.code) and 0x3F
            }
            leftchar = (leftchar shl 6) or ch
            leftbits += 6
            if (leftbits >= 8) {
                leftbits -= 8
                out[o++] = (leftchar ushr leftbits).toByte()
                leftchar = leftchar and ((1 shl leftbits) - 1)
                binLen--
            }
            asciiLen--
            i++
        }
        // (what is left on the line: whitespace only, or '`' as padding)
        while (asciiLen-- > 0) {
            val ch = data[i++].toInt() and 0xFF
            if (ch != ' '.code && ch != ' '.code + 64 && ch != '\n'.code && ch != '\r'.code) throw st.error("Trailing garbage")
        }
        return PyBytes(out)
    }

    private fun b2aUu(st: State, data: ByteArray, backtick: Boolean): PyBytes {
        var binLen = data.size
        if (binLen > 45) throw st.error("At most 45 bytes at once")
        val out = ByteArrayOutputStream(2 + (binLen + 2) / 3 * 4)
        out.write(if (backtick && binLen == 0) '`'.code else ' '.code + binLen)
        var i = 0
        var leftbits = 0
        var leftchar = 0
        while (binLen > 0 || leftbits != 0) {
            leftchar = if (binLen > 0) (leftchar shl 8) or (data[i].toInt() and 0xFF) else leftchar shl 8
            leftbits += 8
            while (leftbits >= 6) {
                val ch = (leftchar ushr (leftbits - 6)) and 0x3F
                leftbits -= 6
                out.write(if (backtick && ch == 0) '`'.code else ch + ' '.code)
            }
            binLen--
            i++
        }
        out.write('\n'.code)
        return PyBytes(out.toByteArray())
    }

    // ------------------------------------------------------------------ base64

    private val A2B_BASE64 = IntArray(256) { 0xFF }.also { t ->
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        for ((k, c) in chars.withIndex()) t[c.code] = k
        // (the pad: 0)
        t['='.code] = 0
    }
    private val B2A_BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toByteArray(Charsets.ISO_8859_1)

    private fun a2bBase64(st: State, data: ByteArray, strict: Boolean): PyBytes {
        val out = ByteArray((data.size + 3) / 4 * 3)
        var o = 0
        var quadPos = 0
        var leftchar = 0
        var pads = 0
        var i = 0
        while (i < data.size) {
            var ch = data[i].toInt() and 0xFF
            // (pads: the valid ones skipped; others too, but in strict mode)
            if (ch == '='.code) {
                pads++
                if (quadPos >= 2 && quadPos + pads <= 4) {
                    i++
                    continue
                }
                // (RFC 4648, section 3.3: pads before the end of the data, and excess ones, may be ignored)
                if (!strict) {
                    i++
                    continue
                }
                // (the error below)
                if (quadPos == 1) break
                throw st.error(if (quadPos == 0 && i == 0) "Leading padding not allowed" else "Excess padding not allowed")
            }
            ch = A2B_BASE64[ch]
            if (ch >= 64) {
                if (strict) throw st.error("Only base64 data is allowed")
                i++
                continue
            }
            // (data among the pads, which strict mode does not allow)
            if (pads != 0 && strict) throw st.error(if (quadPos + pads == 4) "Excess data after padding" else "Discontinuous padding not allowed")
            pads = 0
            when (quadPos) {
                0 -> {
                    quadPos = 1
                    leftchar = ch
                }
                1 -> {
                    quadPos = 2
                    out[o++] = ((leftchar shl 2) or (ch shr 4)).toByte()
                    leftchar = ch and 0x0F
                }
                2 -> {
                    quadPos = 3
                    out[o++] = ((leftchar shl 4) or (ch shr 2)).toByte()
                    leftchar = ch and 0x03
                }
                else -> {
                    quadPos = 0
                    out[o++] = ((leftchar shl 6) or ch).toByte()
                    leftchar = 0
                }
            }
            i++
        }
        if (quadPos == 1) {
            // (one data character more than a multiple of 4: what no input encodes)
            throw st.error("Invalid base64-encoded string: number of data characters (${o / 3 * 4 + 1}) cannot be 1 more than a multiple of 4")
        }
        if (quadPos != 0 && quadPos + pads < 4) throw st.error("Incorrect padding")
        return PyBytes(out.copyOf(o))
    }

    private fun b2aBase64(data: ByteArray, newline: Boolean): PyBytes {
        val out = ByteArray(data.size * 4 / 3 + 4)
        var o = 0
        var leftbits = 0
        var leftchar = 0
        for (b in data) {
            leftchar = (leftchar shl 8) or (b.toInt() and 0xFF)
            leftbits += 8
            while (leftbits >= 6) {
                out[o++] = B2A_BASE64[(leftchar ushr (leftbits - 6)) and 0x3F]
                leftbits -= 6
            }
            leftchar = leftchar and 0xFF
        }
        if (leftbits == 2) {
            out[o++] = B2A_BASE64[(leftchar and 3) shl 4]
            out[o++] = '='.code.toByte()
            out[o++] = '='.code.toByte()
        } else if (leftbits == 4) {
            out[o++] = B2A_BASE64[(leftchar and 0xF) shl 2]
            out[o++] = '='.code.toByte()
        }
        if (newline) out[o++] = '\n'.code.toByte()
        return PyBytes(out.copyOf(o))
    }

    // ------------------------------------------------------------------ CRC-CCITT

    private val CRC_HQX = IntArray(256).also { t ->
        for (n in 0 until 256) {
            var c = n shl 8
            for (k in 0 until 8) c = if (c and 0x8000 != 0) ((c shl 1) xor 0x1021) and 0xFFFF else (c shl 1) and 0xFFFF
            t[n] = c
        }
    }

    private fun crcHqx(data: ByteArray, crc0: Long): Long {
        var crc = (crc0 and 0xFFFF).toInt()
        for (b in data) crc = ((crc shl 8) and 0xFF00) xor CRC_HQX[(crc shr 8) xor (b.toInt() and 0xFF)]
        return crc.toLong()
    }

    // ------------------------------------------------------------------ hex

    private val HEX = "0123456789abcdef".toByteArray(Charsets.ISO_8859_1)

    /** _Py_strhex_bytes_with_sep (Python/pystrhex.c): [b] in hex digits, [sep] (null: none) every [per] bytes. */
    private fun strhexBytes(b: ByteArray, sep: Any?, per0: Int): PyBytes {
        var per = per0
        var sepChar = 0
        if (sep != null) {
            val n = PyRt.len(sep)
            if (n != 1L) throw PyException("ValueError", "sep must be length 1.")
            sepChar = when {
                sep is PyStr -> {
                    // (PyUnicode_1BYTE_KIND: a code point below 256, which bytes may have)
                    val c = sep.s.codePointAt(0)
                    if (c > 255) throw PyException("ValueError", "sep must be ASCII.")
                    c
                }
                sep is PyBytes -> sep.b[0].toInt() and 0xFF
                sep is PyNumSub && sep.num is PyBytes -> sep.num.b[0].toInt() and 0xFF
                else -> throw PyRt.typeError("sep must be str or bytes.")
            }
        } else per = 0
        // (the group's size as an unsigned int: INT_MIN's too)
        var abs = Math.abs(per.toLong())
        val arglen = b.size
        var resultlen = if (per != 0 && arglen > 0) ((arglen - 1) / abs).toInt() else 0
        resultlen += arglen * 2
        if (abs >= arglen) {
            per = 0
            abs = 0
        }
        val out = ByteArray(resultlen)
        if (per == 0) {
            var j = 0
            for (x in b) {
                val c = x.toInt() and 0xFF
                out[j++] = HEX[c shr 4]
                out[j++] = HEX[c and 0x0F]
            }
        } else {
            val chunks = ((arglen - 1) / abs).toInt()
            val group = abs.toInt()
            if (per < 0) {
                var i = 0
                var j = 0
                for (chunk in 0 until chunks) {
                    for (k in 0 until group) {
                        val c = b[i++].toInt() and 0xFF
                        out[j++] = HEX[c shr 4]
                        out[j++] = HEX[c and 0x0F]
                    }
                    out[j++] = sepChar.toByte()
                }
                while (i < arglen) {
                    val c = b[i++].toInt() and 0xFF
                    out[j++] = HEX[c shr 4]
                    out[j++] = HEX[c and 0x0F]
                }
            } else {
                var i = arglen - 1
                var j = resultlen - 1
                for (chunk in 0 until chunks) {
                    for (k in 0 until group) {
                        val c = b[i--].toInt() and 0xFF
                        out[j--] = HEX[c and 0x0F]
                        out[j--] = HEX[c shr 4]
                    }
                    out[j--] = sepChar.toByte()
                }
                while (i >= 0) {
                    val c = b[i--].toInt() and 0xFF
                    out[j--] = HEX[c and 0x0F]
                    out[j--] = HEX[c shr 4]
                }
            }
        }
        return PyBytes(out)
    }

    /** _PyLong_DigitValue for a hex digit: its value, else 37 (not one). */
    private fun digit(c: Int): Int = when (c) {
        in 48..57 -> c - 48
        in 65..70 -> c - 55
        in 97..102 -> c - 87
        else -> 37
    }

    private fun a2bHex(st: State, s: ByteArray): PyBytes {
        if (s.size % 2 != 0) throw st.error("Odd-length string")
        val out = ByteArray(s.size / 2)
        var j = 0
        var i = 0
        while (i < s.size) {
            val top = digit(s[i].toInt() and 0xFF)
            val bot = digit(s[i + 1].toInt() and 0xFF)
            if (top >= 16 || bot >= 16) throw st.error("Non-hexadecimal digit found")
            out[j++] = ((top shl 4) + bot).toByte()
            i += 2
        }
        return PyBytes(out)
    }

    // ------------------------------------------------------------------ quoted-printable

    private const val MAXLINESIZE = 76

    private fun isHex(c: Int) = c in 65..70 || c in 97..102 || c in 48..57

    private fun a2bQp(data: ByteArray, header: Boolean): PyBytes {
        val n = data.size
        val out = ByteArray(n)
        var o = 0
        var i = 0
        fun at(k: Int) = data[k].toInt() and 0xFF
        while (i < n) {
            if (at(i) == '='.code) {
                i++
                if (i >= n) break
                if (at(i) == '\n'.code || at(i) == '\r'.code) {
                    // (a soft line break)
                    if (at(i) != '\n'.code) while (i < n && at(i) != '\n'.code) i++
                    if (i < n) i++
                } else if (at(i) == '='.code) {
                    // (the broken case of a broken python qp)
                    out[o++] = '='.code.toByte()
                    i++
                } else if (i + 1 < n && isHex(at(i)) && isHex(at(i + 1))) {
                    out[o++] = ((digit(at(i)) shl 4) or digit(at(i + 1))).toByte()
                    i += 2
                } else out[o++] = '='.code.toByte()
            } else if (header && at(i) == '_'.code) {
                out[o++] = ' '.code.toByte()
                i++
            } else {
                out[o++] = data[i]
                i++
            }
        }
        return PyBytes(out.copyOf(o))
    }

    private val QP_HEX = "0123456789ABCDEF".toByteArray(Charsets.ISO_8859_1)

    /** b2a_qp's output pass (its first pass only sizes the buffer). */
    private fun b2aQp(data: ByteArray, quotetabs: Boolean, istext: Boolean, header: Boolean): PyBytes {
        val n = data.size
        fun at(k: Int) = data[k].toInt() and 0xFF
        // (whether the data uses CRLF line ends: its first LF after a CR)
        val lf = data.indexOf('\n'.code.toByte())
        val crlf = lf > 0 && at(lf - 1) == '\r'.code
        var out = ByteArray(maxOf(16, n * 2))
        var o = 0
        fun put(c: Int) {
            if (o == out.size) out = out.copyOf(out.size * 2)
            out[o++] = c.toByte()
        }
        fun hex(c: Int) {
            put(QP_HEX[c / 16].toInt())
            put(QP_HEX[c % 16].toInt())
        }
        var i = 0
        var linelen = 0
        while (i < n) {
            val c = at(i)
            if (c > 126 || c == '='.code || (header && c == '_'.code) ||
                (c == '.'.code && linelen == 0 && (i + 1 == n || at(i + 1) == '\n'.code || at(i + 1) == '\r'.code || at(i + 1) == 0)) ||
                (!istext && (c == '\r'.code || c == '\n'.code)) ||
                ((c == '\t'.code || c == ' '.code) && i + 1 == n) ||
                (c < 33 && c != '\r'.code && c != '\n'.code && (quotetabs || (c != '\t'.code && c != ' '.code)))) {
                if (linelen + 3 >= MAXLINESIZE) {
                    put('='.code)
                    if (crlf) put('\r'.code)
                    put('\n'.code)
                    linelen = 0
                }
                put('='.code)
                hex(c)
                i++
                linelen += 3
            } else if (istext && (c == '\n'.code || (i + 1 < n && c == '\r'.code && at(i + 1) == '\n'.code))) {
                linelen = 0
                // (whitespace at the end of a line, encoded)
                if (o > 0 && (out[o - 1].toInt() == ' '.code || out[o - 1].toInt() == '\t'.code)) {
                    val ch = out[o - 1].toInt()
                    out[o - 1] = '='.code.toByte()
                    hex(ch)
                }
                if (crlf) put('\r'.code)
                put('\n'.code)
                i += if (c == '\r'.code) 2 else 1
            } else {
                if (i + 1 != n && at(i + 1) != '\n'.code && linelen + 1 >= MAXLINESIZE) {
                    put('='.code)
                    if (crlf) put('\r'.code)
                    put('\n'.code)
                    linelen = 0
                }
                linelen++
                if (header && c == ' '.code) {
                    put('_'.code)
                    i++
                } else {
                    put(c)
                    i++
                }
            }
        }
        return PyBytes(out.copyOf(o))
    }

    // ------------------------------------------------------------------ the module

    @JvmStatic
    fun fill(ctx: PyContext, d: PyDict) {
        val st = State(ctx)
        d[PyStr("Error")] = st.error
        d[PyStr("Incomplete")] = st.incomplete
        fun noKw(n: String): (PyContext, Array<Any?>, PyDict) -> Any? = { _, _, _ -> throw PyRt.typeError("binascii.$n() takes no keyword arguments") }
        // (METH_O)
        fun def1(n: String, impl: (Any?) -> Any?) {
            d[PyStr(n)] = PyBuiltin(n, { _, a ->
                if (a.size != 1) throw PyRt.typeError("binascii.$n() takes exactly one argument (${a.size} given)")
                impl(a[0])
            }, noKw(n))
        }
        // (METH_FASTCALL: by position only)
        fun defN(n: String, min: Int, max: Int, impl: (Array<Any?>) -> Any?) {
            d[PyStr(n)] = PyBuiltin(n, { _, a ->
                PyMath.checkPositional(n, a.size, min, max)
                impl(a)
            }, noKw(n))
        }
        // (METH_FASTCALL|METH_KEYWORDS: Argument Clinic's parser)
        fun defKw(n: String, names: List<String>, posOnly: Int, minPos: Int, maxPos: Int, impl: (Array<Any?>) -> Any?) {
            d[PyStr(n)] = PyBuiltin(n, { _, a -> impl(PyMath.clinic(n, a, null, names, posOnly, minPos, maxPos)) },
                { _, a, kw -> impl(PyMath.clinic(n, a, kw, names, posOnly, minPos, maxPos)) })
        }
        fun flag(v: Any?, dflt: Boolean) = if (v == null) dflt else PyRt.truthy(v)

        def1("a2b_uu") { a2bUu(st, asciiBuffer(it)) }
        defKw("b2a_uu", listOf("", "backtick"), 1, 1, 1) { p ->
            val data = buffer(p[0])
            b2aUu(st, data, flag(p[1], false))
        }
        defKw("a2b_base64", listOf("", "strict_mode"), 1, 1, 1) { p ->
            val data = asciiBuffer(p[0])
            a2bBase64(st, data, flag(p[1], false))
        }
        defKw("b2a_base64", listOf("", "newline"), 1, 1, 1) { p ->
            val data = buffer(p[0])
            b2aBase64(data, flag(p[1], true))
        }
        defN("crc_hqx", 2, 2) { a ->
            val data = buffer(a[0])
            crcHqx(data, uintMask(a[1]))
        }
        defN("crc32", 1, 2) { a ->
            val data = buffer(a[0])
            val crc = if (a.size > 1) uintMask(a[1]) else 0L
            crc32(crc, data, 0, data.size)
        }
        for (n in listOf("b2a_hex", "hexlify")) {
            defKw(n, listOf("data", "sep", "bytes_per_sep"), 0, 1, 3) { p ->
                val data = buffer(p[0])
                val per = if (p[2] != null) cInt(p[2]) else 1
                strhexBytes(data, p[1], per)
            }
        }
        def1("a2b_hex") { a2bHex(st, asciiBuffer(it)) }
        def1("unhexlify") { a2bHex(st, asciiBuffer(it)) }
        defKw("a2b_qp", listOf("data", "header"), 0, 1, 2) { p ->
            val data = asciiBuffer(p[0])
            a2bQp(data, flag(p[1], false))
        }
        defKw("b2a_qp", listOf("data", "quotetabs", "istext", "header"), 0, 1, 4) { p ->
            val data = buffer(p[0])
            val quotetabs = flag(p[1], false)
            val istext = flag(p[2], true)
            b2aQp(data, quotetabs, istext, flag(p[3], false))
        }
    }
}
