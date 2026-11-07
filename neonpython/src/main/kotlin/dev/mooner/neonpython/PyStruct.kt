package dev.mooner.neonpython

import java.math.BigInteger

/** A struct.Struct (its subclass's instance too): its compiled format, none until __init__ gave it one. */
class PyStructObj(type: PyType) : PyObject(type) {
    @JvmField var layout: PyStruct.Layout? = null
}

/**
 * `_struct` (docs/PYTHON_PLATFORM.md §12, S2), what `struct` is made of, as CPython 3.14 has it: Modules/_struct.c in
 * Kotlin, its tables, its format compiler, its messages, its cache. NeonPy presents Linux x86_64 (LP64, little-endian):
 * native sizes and alignments are that system's (a long 8 bytes), the standard tables use the native routines where
 * CPython's init_endian_tables swaps them in (`<F`, `=F` packed by a C cast, not PyFloat_Pack4), and the C casts
 * between float and double are x86_64's (a NaN made quiet, its payload kept). Floats pack and unpack as
 * Objects/floatobject.c's PyFloat_Pack2/4/8 and PyFloat_Unpack2/4/8 do (half floats rounded half to even, NaN payloads
 * and signalling NaNs kept). Like CPython's, the module's types, its error and its cache are each module's: each
 * context's here.
 */
object PyStruct {
    private val NO_ARGS = arrayOfNulls<Any?>(0)
    private const val MAX = Long.MAX_VALUE
    private const val MAXCACHE = 100
    /** sizeof(PyStructObject) and sizeof(formatcode) on LP64: what __sizeof__ counts. */
    private const val STRUCT_SIZE = 56L
    private const val CODE_SIZE = 32L

    const val DOC = "Functions to convert between Python values and C structs.\n" +
        "Python bytes objects are used to hold the data representing the C struct.\n" +
        "The format string (explained below) describes the layout of data\n" +
        "in the C struct.\n" +
        "\n" +
        "The optional first format char indicates byte order, size and alignment:\n" +
        "  @: native order, size & alignment (default)\n" +
        "  =: native order, std. size & alignment\n" +
        "  <: little-endian, std. size & alignment\n" +
        "  >: big-endian, std. size & alignment\n" +
        "  !: same as >\n" +
        "\n" +
        "The remaining characters indicate types of args and must match exactly;\n" +
        "these can be preceded by a decimal repeat count:\n" +
        "  x: pad byte (no data); c: char; b: signed byte; B: unsigned byte;\n" +
        "  ?: _Bool; h: short; H: unsigned short; i: int; I: unsigned int;\n" +
        "  l: long; L: unsigned long; q: long long; Q: unsigned long long;\n" +
        "  f: float; d: double; e: half-float;\n" +
        "  F: float complex; D: double complex.\n" +
        "Special cases (preceding decimal count indicates length):\n" +
        "  s: byte string (array of char); p: Pascal string (with count byte).\n" +
        "Special cases (only available in native format):\n" +
        "  n: ssize_t; N: size_t;\n" +
        "  P: an integer type that is wide enough to hold a pointer.\n" +
        "Whitespace between formats is ignored.\n" +
        "\n" +
        "The variable struct.error is an exception raised on errors.\n"

    private const val STRUCT_DOC = "Struct(fmt) --> compiled struct object\n\n"

    // ------------------------------------------------------------------ the tables

    private const val PAD = 0
    private const val CHAR = 1
    private const val STR = 2
    private const val PSTR = 3
    private const val INT = 4
    private const val UINT = 5
    private const val BOOL = 6
    private const val HALF = 7
    private const val FLOAT = 8
    private const val DOUBLE = 9
    private const val CFLOAT = 10
    private const val CDOUBLE = 11
    private const val VOIDP = 12

    /**
     * A format character of a table (CPython's formatdef): its size and alignment, its kind, its byte order; [cast]:
     * floats go through C's casts (native `f` unpacked, native and little-endian `F`), not PyFloat_Pack4/Unpack4.
     */
    class Def(@JvmField val c: Char, @JvmField val size: Int, @JvmField val align: Int, @JvmField val kind: Int, @JvmField val big: Boolean, @JvmField val cast: Boolean)

    private fun table(vararg defs: Def): Array<Def?> {
        val t = arrayOfNulls<Def>(128)
        for (d in defs) t[d.c.code] = d
        return t
    }

    private val NATIVE = run {
        fun d(c: Char, size: Int, align: Int, kind: Int, cast: Boolean = false) = Def(c, size, align, kind, false, cast)
        table(
            d('x', 1, 0, PAD), d('b', 1, 0, INT), d('B', 1, 0, UINT), d('c', 1, 0, CHAR), d('s', 1, 0, STR), d('p', 1, 0, PSTR),
            d('h', 2, 2, INT), d('H', 2, 2, UINT), d('i', 4, 4, INT), d('I', 4, 4, UINT), d('l', 8, 8, INT), d('L', 8, 8, UINT),
            d('n', 8, 8, INT), d('N', 8, 8, UINT), d('q', 8, 8, INT), d('Q', 8, 8, UINT), d('?', 1, 1, BOOL), d('e', 2, 2, HALF),
            d('f', 4, 4, FLOAT, cast = true), d('d', 8, 8, DOUBLE), d('F', 8, 4, CFLOAT, cast = true), d('D', 16, 8, CDOUBLE),
            d('P', 8, 8, VOIDP))
    }

    private fun standard(big: Boolean): Array<Def?> {
        fun d(c: Char, size: Int, kind: Int, cast: Boolean = false) = Def(c, size, 0, kind, big, cast)
        return table(
            d('x', 1, PAD), d('b', 1, INT), d('B', 1, UINT), d('c', 1, CHAR), d('s', 1, STR), d('p', 1, PSTR),
            d('h', 2, INT), d('H', 2, UINT), d('i', 4, INT), d('I', 4, UINT), d('l', 4, INT), d('L', 4, UINT),
            d('q', 8, INT), d('Q', 8, UINT), d('?', 1, BOOL), d('e', 2, HALF), d('f', 4, FLOAT), d('d', 8, DOUBLE),
            // (the native F swapped in on a little-endian host: a C cast, as init_endian_tables leaves it)
            d('F', 8, CFLOAT, cast = !big), d('D', 16, CDOUBLE))
    }

    private val LITTLE = standard(false)
    private val BIG = standard(true)

    // ------------------------------------------------------------------ the compiled format

    /**
     * A compiled format (prepare_s's work): its bytes, its size and number of items; its codes, each a format
     * character's [defs] at [offsets], of [sizes] (an item's; `s` and `p`: the string's), [repeats] times.
     */
    class Layout(@JvmField val format: ByteArray, @JvmField val size: Long, @JvmField val len: Long,
                 @JvmField val defs: Array<Def>, @JvmField val offsets: LongArray, @JvmField val sizes: LongArray, @JvmField val repeats: LongArray)

    private fun isSpace(c: Int) = c == 32 || c in 9..13
    private fun isDigit(c: Int) = c in 48..57

    /** [size] aligned for [e] (native items only), -1 past PY_SSIZE_T_MAX. */
    private fun align(size: Long, e: Def): Long {
        if (e.align != 0 && size > 0) {
            val extra = (e.align - 1) - (size - 1) % e.align
            if (extra > MAX - size) return -1
            return size + extra
        }
        return size
    }

    private fun prepare(st: State, fmt: ByteArray): Layout {
        for (b in fmt) if (b.toInt() == 0) throw st.error("embedded null character")
        val first = if (fmt.isEmpty()) 0 else fmt[0].toInt() and 0xFF
        val table = when (first.toChar()) {
            '<', '=' -> LITTLE
            '>', '!' -> BIG
            else -> NATIVE
        }
        val start = if (first.toChar() in "<>!=@") 1 else 0
        fun at(i: Int) = if (i < fmt.size) fmt[i].toInt() and 0xFF else 0
        fun overflow() = st.error("total struct size too long")
        fun entry(c: Int) = (if (c < 128) table[c] else null) ?: throw st.error("bad char in struct format")

        var s = start
        var size = 0L
        var len = 0L
        var ncodes = 0
        while (true) {
            var c = at(s++)
            if (c == 0) break
            if (isSpace(c)) continue
            var num: Long
            if (isDigit(c)) {
                num = (c - 48).toLong()
                while (true) {
                    c = at(s++)
                    if (!isDigit(c)) break
                    if (num >= MAX / 10 && (num > MAX / 10 || (c - 48) > MAX % 10)) throw overflow()
                    num = num * 10 + (c - 48)
                }
                if (c == 0) throw st.error("repeat count given without format specifier")
            } else num = 1
            val e = entry(c)
            when (c.toChar()) {
                's', 'p' -> {
                    if (len == MAX) throw overflow()
                    len++
                    ncodes++
                }
                'x' -> {}
                else -> {
                    if (num > MAX - len) throw overflow()
                    len += num
                    if (num != 0L) ncodes++
                }
            }
            size = align(size, e)
            if (size == -1L) throw overflow()
            if (num > (MAX - size) / e.size) throw overflow()
            size += num * e.size
        }

        val defs = arrayOfNulls<Def>(ncodes)
        val offsets = LongArray(ncodes)
        val sizes = LongArray(ncodes)
        val repeats = LongArray(ncodes)
        var k = 0
        s = start
        size = 0L
        while (true) {
            var c = at(s++)
            if (c == 0) break
            if (isSpace(c)) continue
            var num: Long
            if (isDigit(c)) {
                num = (c - 48).toLong()
                while (true) {
                    c = at(s++)
                    if (!isDigit(c)) break
                    num = num * 10 + (c - 48)
                }
            } else num = 1
            val e = entry(c)
            size = align(size, e)
            if (c == 's'.code || c == 'p'.code) {
                defs[k] = e; offsets[k] = size; sizes[k] = num; repeats[k] = 1
                k++
                size += num
            } else if (c == 'x'.code) {
                size += num
            } else if (num != 0L) {
                defs[k] = e; offsets[k] = size; sizes[k] = e.size.toLong(); repeats[k] = num
                k++
                size += e.size * num
            }
        }
        @Suppress("UNCHECKED_CAST")
        return Layout(fmt, size, len, defs as Array<Def>, offsets, sizes, repeats)
    }

    // ------------------------------------------------------------------ the module's state

    /** The module's state (each context's): its error, its cache, its types. */
    private class State(@JvmField val ctx: PyContext) {
        @JvmField val error: PyType = errorType(ctx)
        @JvmField val cache = PyDict()
        @JvmField val structType: PyType = moduleType(ctx, "Struct", true)
        @JvmField val iterType: PyType = moduleType(ctx, "unpack_iterator", false)

        /** struct.error(msg), raised: its context the exception being handled, as CPython's PyErr_SetString gives it. */
        fun error(msg: String): PyException {
            val o = PyBaseException(error)
            o.args = PyTuple(arrayOf(PyStr(msg)))
            val h = ctx.currentHandled()
            if (h != null) o.context = h
            return PyException(o).also { o.thrown = it }
        }
    }

    /** struct.error: PyErr_NewException("struct.error"), a class as type() makes it. */
    private fun errorType(ctx: PyContext): PyType {
        val base = PyExceptions.type("Exception")
        val t = PyType("error", arrayOf(base), heap = true)
        t.mro = PyTypes.c3(t, t.bases)
        t.module = "struct"
        t.dict[PyStr("__module__")] = PyStr("struct")
        t.dict[PyStr("__doc__")] = PyNone
        base.addSubclass(ctx, t)
        t.hooks = PyClasses.hooksOf(t)
        return t
    }

    /**
     * A type of the module: a heap type, as CPython's made from a spec are, each module's (each context's here, so
     * not among object's builtin subclasses, which every context sees).
     */
    private fun moduleType(ctx: PyContext, name: String, subclassable: Boolean): PyType {
        val t = PyType(name, arrayOf(PyTypes.OBJECT), heap = true)
        t.mro = PyTypes.c3(t, t.bases)
        t.module = "_struct"
        t.tpName = "_struct.$name"
        t.subclassable = subclassable
        PyTypes.OBJECT.addSubclass(ctx, t)
        return t
    }

    // ------------------------------------------------------------------ values

    /** Whether [v] is an int (PyLong_Check: a bool, a subclass's instance too). */
    private fun isInt(v: Any?): Boolean = v is Long || v is BigInteger || v is PyBool || v is PyNumSub && v.num !is Double && (v.num is Long || v.num is BigInteger)

    /** get_pylong: [v] an int, or what its __index__ gives. */
    private fun pylong(st: State, v: Any?): Any = PyRt.indexValue(v) ?: throw st.error("required argument is not an integer")

    /** [n] as 64 bits where it fits the C type ([signed], [size] bytes), else null. */
    private fun fits(n0: Any, signed: Boolean, size: Int): Long? {
        val n: Any = if (n0 is BigInteger && n0.bitLength() < 64) n0.toLong() else n0
        if (n is Long) {
            if (signed) return if (size == 8 || n >= -(1L shl (8 * size - 1)) && n < (1L shl (8 * size - 1))) n else null
            return if (n >= 0 && (size == 8 || n < (1L shl (8 * size)))) n else null
        }
        val b = n as BigInteger
        return if (!signed && size == 8 && b.signum() > 0 && b.bitLength() <= 64) b.toLong() else null
    }

    /** _range_error: the range of [e]'s C type. */
    private fun rangeError(st: State, e: Def, unsigned: Boolean): PyException {
        val ulargest = if (e.size == 8) "18446744073709551615" else ((1L shl (8 * e.size)) - 1).toString()
        if (unsigned) return st.error("'${e.c}' format requires 0 <= number <= $ulargest")
        val largest = if (e.size == 8) Long.MAX_VALUE else (1L shl (8 * e.size - 1)) - 1
        return st.error("'${e.c}' format requires ${-largest - 1} <= number <= $largest")
    }

    /** PyFloat_AsDouble, its failure "required argument is not a float". */
    private fun asDouble(st: State, v: Any?): Double = try {
        PyMath.asDouble(v)
    } catch (e: PyException) {
        throw st.error("required argument is not a float")
    }

    private fun callSpecial(ctx: PyContext, f: Any?, v: Any?): Any? {
        if (f is PyFunction) return PyCalls.callWith(ctx, f, arrayOf(v), NO_ARGS, NO_ARGS)
        return PyCalls.callWith(ctx, PyClasses.descrGet(ctx, f, v, PyTypes.typeOf(v)), NO_ARGS, NO_ARGS, NO_ARGS)
    }

    /** PyComplex_AsCComplex: a complex, what a class's __complex__ gives, else a float of no imaginary part. */
    private fun asComplex(st: State, v: Any?): PyComplex = try {
        when {
            v is PyComplex -> v
            v is PyNumSub && v.num is PyComplex -> v.num
            else -> {
                val f = if (PyRt.special(v) || PyRt.metaSpecial(v)) PyDunder.userMethod(PyTypes.typeOf(v), "__complex__") else null
                if (f != null) when (val r = callSpecial(st.ctx, f, v)) {
                    is PyComplex -> r
                    is PyNumSub -> r.num as? PyComplex ?: throw PyRt.typeError("__complex__ returned non-complex (type ${PyTypes.name(r)})")
                    else -> throw PyRt.typeError("__complex__ returned non-complex (type ${PyTypes.name(r)})")
                } else PyComplex(PyMath.asDouble(v), 0.0)
            }
        }
    } catch (e: PyException) {
        throw st.error("required argument is not a complex")
    }

    // ------------------------------------------------------------------ floats (Objects/floatobject.c)

    private fun overflow(c: Char) = PyException("OverflowError", "float too large to pack with $c format")

    /** PyFloat_Pack2: [x] as IEEE binary16, rounded half to even. */
    private fun pack2(x0: Double): Int {
        var x = x0
        val sign: Int
        var e: Int
        var bits: Int
        if (x == 0.0) {
            sign = if (java.lang.Double.doubleToRawLongBits(x) < 0) 1 else 0
            e = 0
            bits = 0
        } else if (x.isInfinite()) {
            sign = if (x < 0.0) 1 else 0
            e = 0x1F
            bits = 0
        } else if (x.isNaN()) {
            val v = java.lang.Double.doubleToRawLongBits(x)
            sign = if (v < 0) 1 else 0
            e = 0x1F
            // (the NaN's type and payload)
            bits = ((v and 0xFFC0000000000L) ushr 42).toInt()
            // (a quiet NaN where it has no payload)
            if (bits == 0) bits = 1 shl 9
        } else {
            sign = if (x < 0.0) 1 else 0
            if (sign == 1) x = -x
            // (frexp's, normalized to [1.0, 2.0): a double's own exponent; a subnormal double underflows below)
            e = Math.getExponent(x)
            var f = Math.scalb(x, -e)
            if (e >= 16) throw overflow('e')
            else if (e < -25) {
                // (|x| < 2**-25: underflow to zero)
                f = 0.0
                e = 0
            } else if (e < -14) {
                // (|x| < 2**-14: gradual underflow)
                f = Math.scalb(f, 14 + e)
                e = 0
            } else {
                e += 15
                f -= 1.0
            }
            f *= 1024.0
            // (round to even, from the truncation)
            bits = f.toInt()
            if (f - bits > 0.5 || (f - bits == 0.5 && bits % 2 == 1)) {
                ++bits
                if (bits == 1024) {
                    // (the carry out of ten 1 bits)
                    bits = 0
                    ++e
                    if (e == 31) throw overflow('e')
                }
            }
        }
        return bits or (e shl 10) or (sign shl 15)
    }

    /** PyFloat_Unpack2. */
    private fun unpack2(h: Int): Double {
        val sign = (h shr 15) and 1
        var e = (h shr 10) and 0x1F
        val f = h and 0x3FF
        if (e == 0x1F) {
            if (f == 0) return if (sign == 1) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
            // (a NaN: its type and payload)
            val v = (if (sign == 1) -0x10000000000000L else 0x7FF0000000000000L) + (f.toLong() shl 42)
            return java.lang.Double.longBitsToDouble(v)
        }
        var x = f / 1024.0
        if (e == 0) e = -14 else {
            x += 1.0
            e -= 15
        }
        x = Math.scalb(x, e)
        return if (sign == 1) -x else x
    }

    /** C's (float) cast of [x] on x86_64, its bits: rounded to nearest; a NaN made quiet, its payload's top bits kept. */
    @JvmStatic
    internal fun castToFloat(x: Double): Int {
        if (x.isNaN()) {
            val v = java.lang.Double.doubleToRawLongBits(x)
            return ((v ushr 32).toInt() and Int.MIN_VALUE) or 0x7FC00000 or ((v and 0xFFFFFFFFFFFFFL) ushr 29).toInt()
        }
        return java.lang.Float.floatToRawIntBits(x.toFloat())
    }

    /** C's (double) cast of the float [bits] on x86_64: exact; a NaN made quiet, its payload kept. */
    @JvmStatic
    internal fun castToDouble(bits: Int): Double {
        if (bits and 0x7F800000 == 0x7F800000 && bits and 0x7FFFFF != 0) {
            return java.lang.Double.longBitsToDouble(((bits.toLong() and 0x80000000L) shl 32) or 0x7FF8000000000000L or ((bits.toLong() and 0x7FFFFFL) shl 29))
        }
        return java.lang.Float.intBitsToFloat(bits).toDouble()
    }

    /** PyFloat_Pack4 (an IEEE platform's): the cast, an OverflowError where it made an infinity, a signalling NaN kept. */
    private fun pack4(x: Double): Int {
        if (x.isNaN()) {
            val v = java.lang.Double.doubleToRawLongBits(x)
            var u = castToFloat(x)
            if (v and (1L shl 51) == 0L && u and 0x3FFFFF != 0) u = u and (1 shl 22).inv()
            return u
        }
        val y = x.toFloat()
        if (y.isInfinite() && !x.isInfinite()) throw overflow('f')
        return java.lang.Float.floatToRawIntBits(y)
    }

    /** PyFloat_Unpack4 (an IEEE platform's): the cast, a signalling NaN kept. */
    @JvmStatic
    internal fun unpack4(bits: Int): Double {
        if (bits and 0x7F800000 == 0x7F800000 && bits and 0x7FFFFF != 0) {
            return java.lang.Double.longBitsToDouble(((bits.toLong() and 0x80000000L) shl 32) or 0x7FF0000000000000L or ((bits.toLong() and 0x7FFFFFL) shl 29))
        }
        return java.lang.Float.intBitsToFloat(bits).toDouble()
    }

    // ------------------------------------------------------------------ packing and unpacking

    private fun read(a: ByteArray, at: Int, n: Int, big: Boolean): Long {
        var x = 0L
        if (big) for (k in 0 until n) x = (x shl 8) or (a[at + k].toLong() and 0xFF)
        else for (k in n - 1 downTo 0) x = (x shl 8) or (a[at + k].toLong() and 0xFF)
        return x
    }

    private fun write(a: ByteArray, at: Int, n: Int, big: Boolean, x0: Long) {
        var x = x0
        if (big) for (k in n - 1 downTo 0) {
            a[at + k] = x.toByte()
            x = x ushr 8
        } else for (k in 0 until n) {
            a[at + k] = x.toByte()
            x = x ushr 8
        }
    }

    private fun unsigned(x: Long): Any = if (x >= 0) x else BigInteger.valueOf(x).add(BigInteger.ONE.shiftLeft(64))

    private fun unpackOne(e: Def, a: ByteArray, at: Int): Any? = when (e.kind) {
        INT -> {
            val x = read(a, at, e.size, e.big)
            val shift = 64 - 8 * e.size
            (x shl shift) shr shift
        }
        UINT, VOIDP -> unsigned(read(a, at, e.size, e.big))
        CHAR -> PyBytes(byteArrayOf(a[at]))
        BOOL -> PyBool.of(a[at].toInt() != 0)
        HALF -> unpack2(read(a, at, 2, e.big).toInt())
        FLOAT -> read(a, at, 4, e.big).toInt().let { if (e.cast) castToDouble(it) else unpack4(it) }
        DOUBLE -> java.lang.Double.longBitsToDouble(read(a, at, 8, e.big))
        CFLOAT -> {
            val re = read(a, at, 4, e.big).toInt()
            val im = read(a, at + 4, 4, e.big).toInt()
            if (e.cast) PyComplex(castToDouble(re), castToDouble(im)) else PyComplex(unpack4(re), unpack4(im))
        }
        CDOUBLE -> PyComplex(java.lang.Double.longBitsToDouble(read(a, at, 8, e.big)), java.lang.Double.longBitsToDouble(read(a, at + 8, 8, e.big)))
        else -> throw IllegalStateException()
    }

    /** s_unpack_internal: the values of [l] in [a] from [from]. */
    private fun unpackInternal(l: Layout, a: ByteArray, from: Int): PyTuple {
        val out = arrayOfNulls<Any?>(l.len.toInt())
        var i = 0
        for (k in l.defs.indices) {
            val e = l.defs[k]
            val size = l.sizes[k].toInt()
            var res = from + l.offsets[k].toInt()
            for (j in 0 until l.repeats[k]) {
                out[i++] = when (e.kind) {
                    STR -> PyBytes(if (size == 0) ByteArray(0) else a.copyOfRange(res, res + size))
                    PSTR -> {
                        var n = if (size == 0) 0 else a[res].toInt() and 0xFF
                        if (size != 0 && n >= size) n = size - 1
                        PyBytes(if (n == 0) ByteArray(0) else a.copyOfRange(res + 1, res + 1 + n))
                    }
                    else -> unpackOne(e, a, res)
                }
                res += size
            }
        }
        return PyTuple(out)
    }

    /** The bytes of a bytes or a bytearray (a subclass's instance's too) for `s` and `p`, or null. */
    private fun stringBytes(v: Any?): ByteArray? = when (v) {
        is PyBytes -> v.b
        is PyNumSub -> (v.num as? PyBytes)?.b
        is PyByteArray -> v.b.copyOf(v.size)
        else -> null
    }

    private fun packOne(st: State, e: Def, v: Any?, a: ByteArray, at: Int) {
        when (e.kind) {
            INT, UINT -> {
                val n = pylong(st, v)
                val x = fits(n, e.kind == INT, e.size) ?: throw rangeError(st, e, e.kind == UINT)
                write(a, at, e.size, e.big, x)
            }
            CHAR -> {
                val b = when (v) {
                    is PyBytes -> v.b
                    is PyNumSub -> (v.num as? PyBytes)?.b
                    else -> null
                }
                if (b == null || b.size != 1) throw st.error("char format requires a bytes object of length 1")
                a[at] = b[0]
            }
            BOOL -> a[at] = if (PyRt.truthy(v)) 1 else 0
            HALF -> write(a, at, 2, e.big, pack2(asDouble(st, v)).toLong())
            FLOAT -> write(a, at, 4, e.big, pack4(asDouble(st, v)).toLong())
            DOUBLE -> write(a, at, 8, e.big, java.lang.Double.doubleToRawLongBits(asDouble(st, v)))
            CFLOAT -> {
                val c = asComplex(st, v)
                if (e.cast) {
                    write(a, at, 4, e.big, castToFloat(c.real).toLong())
                    write(a, at + 4, 4, e.big, castToFloat(c.imag).toLong())
                } else {
                    write(a, at, 4, e.big, pack4(c.real).toLong())
                    write(a, at + 4, 4, e.big, pack4(c.imag).toLong())
                }
            }
            CDOUBLE -> {
                val c = asComplex(st, v)
                write(a, at, 8, e.big, java.lang.Double.doubleToRawLongBits(c.real))
                write(a, at + 8, 8, e.big, java.lang.Double.doubleToRawLongBits(c.imag))
            }
            VOIDP -> {
                // (PyLong_AsVoidPtr: a negative int as a C long, another as an unsigned long)
                val n = pylong(st, v)
                val negative = if (n is Long) n < 0 else (n as BigInteger).signum() < 0
                val x = if (negative) fits(n, true, 8) ?: throw PyException("OverflowError", "Python int too large to convert to C long")
                    else fits(n, false, 8) ?: throw PyException("OverflowError", "Python int too large to convert to C unsigned long")
                write(a, at, 8, e.big, x)
            }
            else -> throw IllegalStateException()
        }
    }

    /**
     * s_pack_internal: [args] from [first] packed into [a] at [from] (the struct's bytes zeroed first, as CPython's
     * memset; [limit] where the buffer ends, which `0p`'s count byte, written past its item as CPython writes it,
     * stays within).
     */
    private fun packInternal(st: State, l: Layout, args: Array<Any?>, first: Int, a: ByteArray, from: Int, limit: Int) {
        java.util.Arrays.fill(a, from, from + l.size.toInt(), 0)
        var i = first
        for (k in l.defs.indices) {
            val e = l.defs[k]
            val size = l.sizes[k].toInt()
            var res = from + l.offsets[k].toInt()
            for (j in 0 until l.repeats[k]) {
                val v = args[i++]
                when (e.kind) {
                    STR -> {
                        val b = stringBytes(v) ?: throw st.error("argument for 's' must be a bytes object")
                        val n = minOf(b.size, size)
                        if (n > 0) b.copyInto(a, res, 0, n)
                    }
                    PSTR -> {
                        val b = stringBytes(v) ?: throw st.error("argument for 'p' must be a bytes object")
                        var n = if (size == 0) 0 else minOf(b.size, size - 1)
                        if (n > 0) b.copyInto(a, res + 1, 0, n)
                        if (n > 255) n = 255
                        if (res < limit) a[res] = n.toByte()
                    }
                    else -> try {
                        packOne(st, e, v, a, res)
                    } catch (x: PyException) {
                        // (an int's OverflowError: CPython's message for it)
                        if (isInt(v) && x.matches(PyExceptions.type("OverflowError"))) throw st.error("int too large to convert")
                        throw x
                    }
                }
                res += size
            }
        }
    }

    // ------------------------------------------------------------------ buffers

    /** A buffer's bytes: [len] of them from [off] in the array [obj] holds (read where it is used: a bytearray's changes). */
    private class Buf(@JvmField val obj: Any, @JvmField val off: Int, @JvmField val len: Int) {
        fun arr(): ByteArray = when (obj) {
            is PyBytes -> obj.b
            is PyByteArray -> {
                if (off + len > obj.size) throw PyException("BufferError", "the bytearray being read was resized past what is read")
                obj.b
            }
            is PyMemoryView -> obj.arr()
            is PyArrayObj -> {
                if (off + len > obj.n * obj.itemsize) throw PyException("BufferError", "the array being read was resized past what is read")
                obj.b
            }
            else -> throw IllegalStateException()
        }
    }

    /** PyObject_GetBuffer(v, PyBUF_SIMPLE): a bytes-like object's bytes (a memoryview's, contiguous). */
    private fun readBuffer(v: Any?): Buf = when (v) {
        is PyBytes -> Buf(v, 0, v.b.size)
        is PyByteArray -> Buf(v, 0, v.size)
        is PyArrayObj -> Buf(v, 0, v.n * v.itemsize)
        is PyMemoryView -> {
            v.check()
            if (!v.contiguous()) throw PyException("BufferError", "memoryview: underlying buffer is not C-contiguous")
            Buf(v, v.offset, v.count * v.itemsize)
        }
        is PyNumSub -> (v.num as? PyBytes)?.let { Buf(it, 0, it.b.size) } ?: throw notBytesLike(v)
        else -> throw notBytesLike(v)
    }

    private fun notBytesLike(v: Any?) = PyRt.typeError("a bytes-like object is required, not '${PyTypes.name(v)}'")

    /** PyArg_Parse "w*": a writable, contiguous buffer, else CPython's message whatever kept it from one. */
    private fun writeBuffer(v: Any?): Buf = when {
        v is PyByteArray -> Buf(v, 0, v.size)
        v is PyArrayObj -> Buf(v, 0, v.n * v.itemsize)
        v is PyMemoryView && !v.released && !v.readonly && v.contiguous() -> Buf(v, v.offset, v.count * v.itemsize)
        else -> throw PyRt.typeError("argument must be read-write bytes-like object, not ${PyTypes.typeOf(v).name}")
    }

    /** _PyNumber_Index, then PyLong_AsSsize_t. */
    private fun ssize(v: Any?): Long {
        val n = PyRt.indexValue(v) ?: throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer")
        return n as? Long ?: (n as BigInteger).takeIf { it.bitLength() < 64 }?.toLong()
            ?: throw PyException("OverflowError", "Python int too large to convert to C ssize_t")
    }

    /** PyNumber_AsSsize_t(v, IndexError). */
    private fun indexSsize(v: Any?): Long {
        val n = PyRt.indexValue(v) ?: throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer")
        return n as? Long ?: (n as BigInteger).takeIf { it.bitLength() < 64 }?.toLong()
            ?: throw PyException("IndexError", "cannot fit 'int' into an index-sized integer")
    }

    // ------------------------------------------------------------------ Struct

    private fun ready(s: PyStructObj): Layout = s.layout ?: throw PyException("RuntimeError", "Struct object is not initialized")

    /** Struct.__init__'s format: a str's ASCII bytes, a bytes'. */
    private fun formatBytes(v: Any?): ByteArray = when (v) {
        is PyStr -> PyCodecs.encodeUcs1(PyContext.current(), v, "strict", 128)
        is PyBytes -> v.b
        is PyNumSub -> (v.num as? PyBytes)?.b
        else -> null
    } ?: throw PyRt.typeError("Struct() argument 1 must be a str or bytes object, not ${PyTypes.typeOf(v).name}")

    private fun pack(st: State, s: PyStructObj, args: Array<Any?>, first: Int): PyBytes {
        val l = ready(s)
        val n = args.size - first
        if (n.toLong() != l.len) throw st.error("pack expected ${l.len} items for packing (got $n)")
        if (l.size > Int.MAX_VALUE - 16) throw PyException("MemoryError", "")
        val out = ByteArray(l.size.toInt())
        packInternal(st, l, args, first, out, 0, out.size)
        return PyBytes(out)
    }

    private fun packInto(st: State, s: PyStructObj, args: Array<Any?>, first: Int): Any? {
        val l = ready(s)
        val n = args.size - first
        if (n.toLong() != l.len + 2) {
            throw st.error(when (n) {
                0 -> "pack_into expected buffer argument"
                1 -> "pack_into expected offset argument"
                else -> "pack_into expected ${l.len} items for packing (got ${n - 2})"
            })
        }
        val buf = writeBuffer(args[first])
        val len = buf.len.toLong()
        var offset = indexSsize(args[first + 1])
        if (offset < 0) {
            if (offset + l.size > 0) throw st.error("no space to pack ${l.size} bytes at offset $offset")
            if (offset + len < 0) throw st.error("offset $offset out of range for $len-byte buffer")
            offset += len
        }
        if (len - offset < l.size) {
            throw st.error("pack_into requires a buffer of at least ${java.lang.Long.toUnsignedString(l.size + offset)} bytes for packing ${l.size} bytes at offset $offset (actual buffer size is $len)")
        }
        packInternal(st, l, args, first + 2, buf.arr(), buf.off + offset.toInt(), buf.off + buf.len)
        return PyNone
    }

    private fun unpack(st: State, s: PyStructObj, buf: Buf): PyTuple {
        val l = ready(s)
        if (buf.len.toLong() != l.size) throw st.error("unpack requires a buffer of ${l.size} bytes")
        return unpackInternal(l, buf.arr(), buf.off)
    }

    private fun unpackFrom(st: State, s: PyStructObj, buf: Buf, offset0: Long): PyTuple {
        val l = ready(s)
        val len = buf.len.toLong()
        var offset = offset0
        if (offset < 0) {
            if (offset + l.size > 0) throw st.error("not enough data to unpack ${l.size} bytes at offset $offset")
            if (offset + len < 0) throw st.error("offset $offset out of range for $len-byte buffer")
            offset += len
        }
        if (len - offset < l.size) {
            throw st.error("unpack_from requires a buffer of at least ${java.lang.Long.toUnsignedString(l.size + offset)} bytes for unpacking ${l.size} bytes at offset $offset (actual buffer size is $len)")
        }
        return unpackInternal(l, buf.arr(), buf.off + offset.toInt())
    }

    /** An unpack_iterator: its struct's layout, its buffer, where it is ([done]: exhausted, its buffer let go). */
    private class Iter(@JvmField val layout: Layout, @JvmField var buf: Buf?) {
        @JvmField var index = 0L

        fun next(): Any? {
            val b = buf ?: return PyIterator.STOP
            if (index >= b.len) {
                buf = null
                return PyIterator.STOP
            }
            val r = unpackInternal(layout, b.arr(), b.off + index.toInt())
            index += layout.size
            return r
        }

        fun lengthHint(): Long = buf?.let { (it.len - index) / layout.size } ?: 0L
    }

    private class StructIterator(@JvmField val state: Iter) : PyIterator({ state.next() })

    private fun iterUnpack(st: State, s: PyStructObj, buffer: Any?): PyIterator {
        val l = ready(s)
        if (l.size == 0L) throw st.error("cannot iteratively unpack with a struct of length 0")
        val buf = readBuffer(buffer)
        if (buf.len % l.size != 0L) throw st.error("iterative unpacking requires a buffer of a multiple of ${l.size} bytes")
        return StructIterator(Iter(l, buf)).also { it.type = st.iterType }
    }

    /** A method's receiver: a Struct, else the descriptor's TypeError. */
    private fun self(a: Array<Any?>, n: String): PyStructObj {
        if (a.isEmpty()) throw PyRt.typeError("unbound method Struct.$n() needs an argument")
        return a[0] as? PyStructObj ?: throw PyRt.typeError("descriptor '$n' for '_struct.Struct' objects doesn't apply to a '${PyTypes.name(a[0])}' object")
    }

    /** METH_O's argument (the receiver's first). */
    private fun one(a: Array<Any?>, n: String): Any? {
        if (a.size != 2) throw PyRt.typeError("$n() takes exactly one argument (${a.size - 1} given)")
        return a[1]
    }

    private fun noKw(n: String): (PyContext, Array<Any?>, PyDict) -> Any? = { _, _, _ -> throw PyRt.typeError("$n() takes no keyword arguments") }

    private fun defineStruct(st: State) {
        val t = st.structType
        // (methods by position only: METH_O, METH_FASTCALL, METH_NOARGS)
        fun method(n: String, impl: (PyStructObj, Array<Any?>) -> Any?) =
            t.def(n, PyBuiltin(n, { _, a -> impl(self(a, n), a) }, noKw("Struct.$n")))
        t.def("__new__", PyBuiltin("__new__", { _, a -> newStruct(st, a) }, { _, a, _ -> newStruct(st, a) }))
        fun init(a: Array<Any?>, kw: PyDict?): Any? {
            val s = a.getOrNull(0) as? PyStructObj
                ?: throw PyRt.typeError("descriptor '__init__' requires a '_struct.Struct' object but received a '${PyTypes.name(a.getOrNull(0))}'")
            val p = PyMath.clinic("Struct", a.copyOfRange(1, a.size), kw, listOf("format"), 0, 1, 1)
            s.layout = prepare(st, formatBytes(p[0]))
            return PyNone
        }
        t.def("__init__", PyBuiltin("__init__", { _, a -> init(a, null) }, { _, a, kw -> init(a, kw) }))
        method("pack") { s, a -> pack(st, s, a, 1) }
        method("pack_into") { s, a -> packInto(st, s, a, 1) }
        method("unpack") { s, a -> unpack(st, s, readBuffer(one(a, "Struct.unpack"))) }
        fun unpackFromImpl(a: Array<Any?>, kw: PyDict?): Any? {
            val s = self(a, "unpack_from")
            val p = PyMath.clinic("unpack_from", a.copyOfRange(1, a.size), kw, listOf("buffer", "offset"), 0, 1, 2)
            val buf = readBuffer(p[0])
            val offset = if (p[1] != null) ssize(p[1]) else 0L
            return unpackFrom(st, s, buf, offset)
        }
        t.def("unpack_from", PyBuiltin("unpack_from", { _, a -> unpackFromImpl(a, null) }, { _, a, kw -> unpackFromImpl(a, kw) }))
        method("iter_unpack") { s, a -> iterUnpack(st, s, one(a, "Struct.iter_unpack")) }
        method("__sizeof__") { s, a ->
            if (a.size != 1) throw PyRt.typeError("Struct.__sizeof__() takes no arguments (${a.size - 1} given)")
            STRUCT_SIZE + CODE_SIZE * (ready(s).defs.size + 1)
        }
        method("__repr__") { s, _ -> PyStr("${s.type.name}(${PyRt.repr(PyStr(String(ready(s).format, Charsets.ISO_8859_1)))})") }
        fun getter(n: String, get: (PyStructObj) -> Any?) {
            val notWritable = PyBuiltin(n) { _, _ -> throw PyException("AttributeError", "attribute '$n' of '_struct.Struct' objects is not writable") }
            t.def(n, PyProperty(PyBuiltin(n) { _, a -> get(self(a, n)) }, notWritable, notWritable, null).also { it.name = n })
        }
        getter("format") { PyStr(String(ready(it).format, Charsets.ISO_8859_1)) }
        getter("size") { it.layout?.size ?: -1L }
        // (a Struct has no __dict__; a subclass's instance has)
        t.def("__setattr__", PyBuiltin("__setattr__") { _, a ->
            val s = self(a, "__setattr__")
            val n = (a[1] as? PyStr ?: throw PyRt.typeError("attribute name must be string, not '${PyTypes.name(a[1])}'")).s
            if (s.type !== t || t.lookup(n) is PyProperty) PyClasses.genericSetAttr(s, n, a[2])
            else throw PyException("AttributeError", "'_struct.Struct' object has no attribute '$n' and no __dict__ for setting new attributes")
            PyNone
        })
        t.def("__delattr__", PyBuiltin("__delattr__") { _, a ->
            val s = self(a, "__delattr__")
            val n = (a[1] as? PyStr ?: throw PyRt.typeError("attribute name must be string, not '${PyTypes.name(a[1])}'")).s
            if (s.type !== t || t.lookup(n) is PyProperty) PyClasses.genericDelAttr(s, n)
            else throw PyException("AttributeError", "'_struct.Struct' object has no attribute '$n' and no __dict__ for setting new attributes")
            PyNone
        })
        t.hooks = 2 or 4
        t.def("__reduce_ex__", REDUCE_EX)
        t.def("__doc__", PyStr(STRUCT_DOC))
        t.def("__module__", PyStr("_struct"))
        t.dispatchBuiltins = true

        val it = st.iterType
        it.def("__next__", PyBuiltin("__next__") { _, a ->
            val v = (a[0] as PyIterator).next()
            if (v === PyIterator.STOP) throw PyException("StopIteration", "") else v
        })
        it.def("__iter__", PyBuiltin("__iter__") { _, a -> a[0] })
        it.def("__length_hint__", PyBuiltin("__length_hint__") { _, a ->
            if (a.size != 1) throw PyRt.typeError("unpack_iterator.__length_hint__() takes no arguments (${a.size - 1} given)")
            (a[0] as StructIterator).state.lengthHint()
        })
        it.def("__module__", PyStr("_struct"))
        it.def("__reduce_ex__", REDUCE_EX)
        it.newImpl = { _, _, _, _ -> throw PyRt.typeError("cannot create '_struct.unpack_iterator' instances") }
    }

    /**
     * object.__reduce_ex__ as CPython's is for these types: from protocol 2, where nothing (__reduce__,
     * __getnewargs__) gives what makes one again, the state object_getstate requires is one a C struct holds: "cannot
     * pickle"; else object's.
     */
    private val REDUCE_EX = PyBuiltin("__reduce_ex__") { ctx, a ->
        val v = a.getOrNull(0)
        val proto = PyRt.indexValue(a.getOrNull(1)) as? Long ?: throw PyRt.typeError("__reduce_ex__() missing required argument 'protocol' (pos 1)")
        val t = PyTypes.typeOf(v)
        val reduce = t.lookup("__reduce__")
        if (proto >= 2 && (reduce == null || reduce === PyTypes.OBJECT.dict[PyStr("__reduce__")]) && t.lookup("__getnewargs_ex__") == null && t.lookup("__getnewargs__") == null) {
            throw PyRt.typeError("cannot pickle '${PyTypes.name(v)}' object")
        }
        PyDunder.reduceEx(ctx, v, proto.toInt())
    }

    /** Struct.__new__(cls, ...): an instance not initialized (its arguments __init__'s). */
    private fun newStruct(st: State, a: Array<Any?>): PyStructObj {
        if (a.isEmpty()) throw PyRt.typeError("_struct.Struct.__new__(): not enough arguments")
        val cls = a[0] as? PyType ?: throw PyRt.typeError("_struct.Struct.__new__(X): X is not a type object (${PyTypes.name(a[0])})")
        if (!cls.isSubtype(st.structType)) {
            val n = cls.tpName ?: cls.name
            throw PyRt.typeError("_struct.Struct.__new__($n): $n is not a subtype of _struct.Struct")
        }
        return PyStructObj(cls).also { if (cls.heap) PyWeakrefs.made(it) }
    }

    // ------------------------------------------------------------------ the module

    /** cache_struct_converter: the Struct of [fmt], from the cache (cleared when full) or made now. */
    private fun cached(st: State, fmt: Any?): PyStructObj {
        st.cache[fmt]?.let { return it as PyStructObj }
        val s = PyCalls.callWith(st.ctx, st.structType, arrayOf(fmt), NO_ARGS, NO_ARGS) as PyStructObj
        if (st.cache.size >= MAXCACHE) st.cache.map.clear()
        try {
            st.cache[fmt] = s
        } catch (e: PyException) {
            // (not cached: the error dropped, as CPython's PyErr_Clear)
        }
        return s
    }

    @JvmStatic
    fun fill(ctx: PyContext, d: PyDict) {
        val st = State(ctx)
        defineStruct(st)
        d[PyStr("Struct")] = st.structType
        d[PyStr("error")] = st.error
        fun def(n: String, impl: (Array<Any?>) -> Any?) {
            d[PyStr(n)] = PyBuiltin(n, { _, a -> impl(a) }, noKw("_struct.$n"))
        }
        def("_clearcache") { a ->
            if (a.isNotEmpty()) throw PyRt.typeError("_struct._clearcache() takes no arguments (${a.size} given)")
            st.cache.map.clear()
            PyNone
        }
        def("calcsize") { a ->
            if (a.size != 1) throw PyRt.typeError("_struct.calcsize() takes exactly one argument (${a.size} given)")
            cached(st, a[0]).layout!!.size
        }
        def("pack") { a ->
            if (a.isEmpty()) throw PyRt.typeError("missing format argument")
            pack(st, cached(st, a[0]), a, 1)
        }
        def("pack_into") { a ->
            if (a.isEmpty()) throw PyRt.typeError("missing format argument")
            packInto(st, cached(st, a[0]), a, 1)
        }
        def("unpack") { a ->
            PyMath.checkPositional("unpack", a.size, 2, 2)
            val s = cached(st, a[0])
            unpack(st, s, readBuffer(a[1]))
        }
        def("iter_unpack") { a ->
            PyMath.checkPositional("iter_unpack", a.size, 2, 2)
            iterUnpack(st, cached(st, a[0]), a[1])
        }
        fun unpackFromImpl(a: Array<Any?>, kw: PyDict?): Any? {
            val p = PyMath.clinic("unpack_from", a, kw, listOf("", "buffer", "offset"), 1, 2, 3)
            val s = cached(st, p[0])
            val buf = readBuffer(p[1])
            val offset = if (p[2] != null) ssize(p[2]) else 0L
            return unpackFrom(st, s, buf, offset)
        }
        d[PyStr("unpack_from")] = PyBuiltin("unpack_from", { _, a -> unpackFromImpl(a, null) }, { _, a, kw -> unpackFromImpl(a, kw) })
    }
}
