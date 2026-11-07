package dev.mooner.neonpython

import java.math.BigInteger

/**
 * An array.array (its subclass's instance too): [n] items of typecode [tc], [itemsize] bytes each, the first
 * n * itemsize bytes of [b] as Linux x86_64 holds them (little-endian), room in [b] for [allocated] (CPython's ob_item
 * and allocated: what __sizeof__ counts). A memoryview of it reads and writes [b] where it is at the time.
 */
class PyArrayObj(type: PyType, @JvmField val tc: Char, @JvmField val itemsize: Int) : PyObject(type) {
    @JvmField var b: ByteArray = PyArray.NO_BYTES
    @JvmField var n = 0
    @JvmField var allocated = 0

    /** A copy of its bytes. */
    fun bytes(): ByteArray = b.copyOf(n * itemsize)
}

/**
 * `array` (docs/PYTHON_PLATFORM.md §12, S2) as CPython 3.14 has it: Modules/arraymodule.c in Kotlin, its typecodes,
 * its checks and messages, its growth (so __sizeof__ is CPython's), its pickling (`_array_reconstructor` and its
 * machine format codes), its iterator. NeonPy presents Linux x86_64: `l` and `L` are 8 bytes, `u` a 4-byte wchar_t (a
 * signed int), items little-endian. An array is a buffer exporter: memoryview, bytes(), struct, the files' write and
 * readinto, hashlib, zlib, binascii take it as CPython does. Like CPython's, the module's types are each module's: each
 * context's here.
 *
 * An array stays resizable while memoryviews of it live (CPython raises BufferError): as for a bytearray, no reference
 * count tells when a view is gone (§12.2), so only a resize NeonPy can tell is refused, frombytes() of the array's own
 * bytes; a view the resize left past the end raises where it is read.
 */
object PyArray {
    private val NO_ARGS = arrayOfNulls<Any?>(0)
    @JvmField val NO_BYTES = ByteArray(0)
    private val TWO64: BigInteger = BigInteger.ONE.shiftLeft(64)
    /** The most bytes an array holds (a JVM array's limit): past it, MemoryError. */
    private const val MAX_BYTES = Int.MAX_VALUE - 8L
    private const val SSIZE_MAX = Long.MAX_VALUE
    /** sizeof(arrayobject) on LP64: what __sizeof__ counts before the items. */
    private const val ARRAY_SIZE = 64L

    const val TYPECODES = "bBuwhHiIlLqQfd"

    const val DOC = "This module defines an object type which can efficiently represent\n" +
        "an array of basic values: characters, integers, floating-point\n" +
        "numbers.  Arrays are sequence types and behave very much like lists,\n" +
        "except that the type of objects stored in them is constrained.\n"

    private const val ARRAY_DOC = "array(typecode [, initializer]) -> array\n" +
        "\n" +
        "Return a new array whose items are restricted by typecode, and\n" +
        "initialized from the optional initializer value, which must be a list,\n" +
        "string or iterable over elements of the appropriate type.\n" +
        "\n" +
        "Arrays represent basic values and behave very much like lists, except\n" +
        "the type of objects stored in them is constrained. The type is specified\n" +
        "at object creation time by using a type code, which is a single character.\n" +
        "The following type codes are defined:\n" +
        "\n" +
        "    Type code   C Type             Minimum size in bytes\n" +
        "    'b'         signed integer     1\n" +
        "    'B'         unsigned integer   1\n" +
        "    'u'         Unicode character  2 (see note)\n" +
        "    'h'         signed integer     2\n" +
        "    'H'         unsigned integer   2\n" +
        "    'i'         signed integer     2\n" +
        "    'I'         unsigned integer   2\n" +
        "    'l'         signed integer     4\n" +
        "    'L'         unsigned integer   4\n" +
        "    'q'         signed integer     8 (see note)\n" +
        "    'Q'         unsigned integer   8 (see note)\n" +
        "    'f'         floating-point     4\n" +
        "    'd'         floating-point     8\n" +
        "\n" +
        "NOTE: The 'u' typecode corresponds to Python's unicode character. On\n" +
        "narrow builds this is 2-bytes on wide builds this is 4-bytes.\n" +
        "\n" +
        "NOTE: The 'q' and 'Q' type codes are only available if the platform\n" +
        "C compiler used to build Python supports 'long long', or, on Windows,\n" +
        "'__int64'.\n" +
        "\n" +
        "Methods:\n" +
        "\n" +
        "append() -- append a new item to the end of the array\n" +
        "buffer_info() -- return information giving the current memory info\n" +
        "byteswap() -- byteswap all the items of the array\n" +
        "count() -- return number of occurrences of an object\n" +
        "extend() -- extend array by appending multiple elements from an iterable\n" +
        "fromfile() -- read items from a file object\n" +
        "fromlist() -- append items from the list\n" +
        "frombytes() -- append items from the string\n" +
        "index() -- return index of first occurrence of an object\n" +
        "insert() -- insert a new item into the array at a provided position\n" +
        "pop() -- remove and return item (default last)\n" +
        "remove() -- remove first occurrence of an object\n" +
        "reverse() -- reverse the order of the items in the array\n" +
        "tofile() -- write all items to a file object\n" +
        "tolist() -- return the array converted to an ordinary list\n" +
        "tobytes() -- return the array converted to a string\n" +
        "\n" +
        "Attributes:\n" +
        "\n" +
        "typecode -- the typecode character used to create the array\n" +
        "itemsize -- the length in bytes of one array item\n"

    // ------------------------------------------------------------------ the typecodes

    /** A typecode's item size (Linux x86_64's C types). */
    private fun itemsizeOf(c: Char): Int = when (c) {
        'b', 'B' -> 1
        'h', 'H' -> 2
        'u', 'w', 'i', 'I', 'f' -> 4
        else -> 8
    }

    /** The format a buffer of the typecode's gives (`u` a wchar_t of 4 bytes: Py_UNICODE_WIDE's "w"). */
    @JvmStatic fun format(c: Char): String = if (c == 'u') "w" else c.toString()

    /** The machine format code of each typecode (typecode_to_mformat_code, little-endian). */
    private fun mformat(c: Char): Int = when (c) {
        'b' -> SIGNED_INT8
        'B' -> UNSIGNED_INT8
        'u', 'w' -> UTF32_LE
        'f' -> IEEE_754_FLOAT_LE
        'd' -> IEEE_754_DOUBLE_LE
        'h' -> SIGNED_INT16_LE
        'H' -> UNSIGNED_INT16_LE
        'i' -> SIGNED_INT32_LE
        'I' -> UNSIGNED_INT32_LE
        'l', 'q' -> SIGNED_INT64_LE
        else -> UNSIGNED_INT64_LE
    }

    private const val UNSIGNED_INT8 = 0
    private const val SIGNED_INT8 = 1
    private const val UNSIGNED_INT16_LE = 2
    private const val SIGNED_INT16_LE = 4
    private const val UNSIGNED_INT32_LE = 6
    private const val SIGNED_INT32_LE = 8
    private const val UNSIGNED_INT64_LE = 10
    private const val SIGNED_INT64_LE = 12
    private const val IEEE_754_FLOAT_LE = 14
    private const val IEEE_754_FLOAT_BE = 15
    private const val IEEE_754_DOUBLE_LE = 16
    private const val IEEE_754_DOUBLE_BE = 17
    private const val UTF16_LE = 18
    private const val UTF16_BE = 19
    private const val UTF32_LE = 20
    private const val UTF32_BE = 21

    // ------------------------------------------------------------------ the items

    private fun u8(b: ByteArray, at: Int): Int = b[at].toInt() and 0xFF
    private fun get16(b: ByteArray, at: Int): Int = u8(b, at) or (u8(b, at + 1) shl 8)
    private fun get32(b: ByteArray, at: Int): Int = u8(b, at) or (u8(b, at + 1) shl 8) or (u8(b, at + 2) shl 16) or (u8(b, at + 3) shl 24)
    private fun get64(b: ByteArray, at: Int): Long = (get32(b, at).toLong() and 0xFFFFFFFFL) or (get32(b, at + 4).toLong() shl 32)

    private fun put16(b: ByteArray, at: Int, x: Int) {
        b[at] = x.toByte()
        b[at + 1] = (x shr 8).toByte()
    }

    private fun put32(b: ByteArray, at: Int, x: Int) {
        b[at] = x.toByte()
        b[at + 1] = (x shr 8).toByte()
        b[at + 2] = (x shr 16).toByte()
        b[at + 3] = (x shr 24).toByte()
    }

    private fun put64(b: ByteArray, at: Int, x: Long) {
        put32(b, at, x.toInt())
        put32(b, at + 4, (x ushr 32).toInt())
    }

    private fun unsigned(x: Long): Any = if (x >= 0) x else BigInteger.valueOf(x).add(TWO64)

    /** PyUnicode_FromOrdinal: the character of [cp] (a wchar_t's or a Py_UCS4's int). */
    private fun ordinal(cp: Int): PyStr {
        if (cp < 0 || cp > 0x10FFFF) throw PyException("ValueError", "chr() arg not in range(0x110000)")
        return PyStr(String(Character.toChars(cp)))
    }

    /** The typecode's getitem: item [i] (0 <= i < n) as Python's value. */
    private fun item(a: PyArrayObj, i: Int): Any? {
        val b = a.b
        return when (a.tc) {
            'b' -> b[i].toLong()
            'B' -> u8(b, i).toLong()
            'h' -> get16(b, 2 * i).toShort().toLong()
            'H' -> get16(b, 2 * i).toLong()
            'i' -> get32(b, 4 * i).toLong()
            'I' -> get32(b, 4 * i).toLong() and 0xFFFFFFFFL
            'l', 'q' -> get64(b, 8 * i)
            'L', 'Q' -> unsigned(get64(b, 8 * i))
            'f' -> PyStruct.castToDouble(get32(b, 4 * i))
            'd' -> java.lang.Double.longBitsToDouble(get64(b, 8 * i))
            // ('u' a wchar_t, signed; 'w' a Py_UCS4: both PyUnicode_FromOrdinal's int)
            else -> ordinal(get32(b, 4 * i))
        }
    }

    private fun overflow(msg: String) = PyException("OverflowError", msg)

    /** _PyNumber_Index: an int, or what __index__ gives (a Long, a BigInteger past 64 bits). */
    private fun index(v: Any?): Any = when (val i = PyRt.indexValue(v)) {
        is Long, is BigInteger -> i
        is PyBool -> if (i.value) 1L else 0L
        else -> throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer")
    }

    /** PyLong_AsLong. */
    private fun cLong(v: Any?): Long = when (val i = index(v)) {
        is Long -> i
        else -> (i as BigInteger).takeIf { it.bitLength() < 64 }?.toLong() ?: throw overflow("Python int too large to convert to C long")
    }

    /** PyArg_Parse's "h": a C short. */
    private fun cShort(v: Any?): Long {
        val x = cLong(v)
        if (x < Short.MIN_VALUE) throw overflow("signed short integer is less than minimum")
        if (x > Short.MAX_VALUE) throw overflow("signed short integer is greater than maximum")
        return x
    }

    /** PyArg_Parse's "i": a C int. */
    private fun cInt(v: Any?): Long {
        val x = cLong(v)
        if (x > Int.MAX_VALUE) throw overflow("signed integer is greater than maximum")
        if (x < Int.MIN_VALUE) throw overflow("signed integer is less than minimum")
        return x
    }

    /** PyLong_AsUnsignedLong (of what _PyNumber_Index gives): its 64 bits. */
    private fun cULong(v: Any?): Long = when (val i = index(v)) {
        is Long -> if (i < 0) throw overflow("can't convert negative value to unsigned int") else i
        else -> {
            val b = i as BigInteger
            if (b.signum() < 0) throw overflow("can't convert negative value to unsigned int")
            if (b.bitLength() > 64) throw overflow("Python int too large to convert to C unsigned long")
            b.toLong()
        }
    }

    /** PyLong_AsLongLong. */
    private fun cLongLong(v: Any?): Long = when (val i = index(v)) {
        is Long -> i
        else -> (i as BigInteger).takeIf { it.bitLength() < 64 }?.toLong() ?: throw overflow("int too big to convert")
    }

    /** PyLong_AsUnsignedLongLong (of what _PyNumber_Index gives): its 64 bits. */
    private fun cULongLong(v: Any?): Long = when (val i = index(v)) {
        is Long -> if (i < 0) throw overflow("can't convert negative int to unsigned") else i
        else -> {
            val b = i as BigInteger
            if (b.signum() < 0) throw overflow("can't convert negative int to unsigned")
            if (b.bitLength() > 64) throw overflow("int too big to convert")
            b.toLong()
        }
    }

    /** CHECK_ARRAY_BOUNDS: [i] still an item after the conversion (whose __index__ may have changed [a]). */
    private fun bounds(a: PyArrayObj, i: Int) {
        if (i >= 0 && i >= a.n) throw PyException("IndexError", "array assignment index out of range")
    }

    /** A 'u' or 'w' item: a str of one character, its code point. */
    private fun character(v: Any?): Int {
        val s = v as? PyStr ?: throw PyRt.typeError("array item must be a unicode character, not ${PyTypes.name(v)}")
        val len = s.length
        if (len != 1) throw PyRt.typeError("array item must be a unicode character, not a string of length $len")
        return s.s.codePointAt(0)
    }

    /**
     * The typecode's setitem: [v] converted, with CPython's checks and messages, then stored at [i] where i >= 0
     * (-1: only checked, as ins1 checks before it makes room).
     */
    private fun setItem(a: PyArrayObj, i: Int, v: Any?) {
        when (a.tc) {
            'b' -> {
                val x = cShort(v)
                bounds(a, i)
                if (x < -128) throw overflow("signed char is less than minimum")
                if (x > 127) throw overflow("signed char is greater than maximum")
                if (i >= 0) a.b[i] = x.toByte()
            }
            'B' -> {
                val x = cLong(v)
                if (x < 0) throw overflow("unsigned byte integer is less than minimum")
                if (x > 255) throw overflow("unsigned byte integer is greater than maximum")
                bounds(a, i)
                if (i >= 0) a.b[i] = x.toByte()
            }
            'h' -> {
                val x = cShort(v)
                bounds(a, i)
                if (i >= 0) put16(a.b, 2 * i, x.toInt())
            }
            'H' -> {
                val x = cInt(v)
                if (x < 0) throw overflow("unsigned short is less than minimum")
                if (x > 65535) throw overflow("unsigned short is greater than maximum")
                bounds(a, i)
                if (i >= 0) put16(a.b, 2 * i, x.toInt())
            }
            'i' -> {
                val x = cInt(v)
                bounds(a, i)
                if (i >= 0) put32(a.b, 4 * i, x.toInt())
            }
            'I' -> {
                val x = cULong(v)
                if (x < 0 || x > 0xFFFFFFFFL) throw overflow("unsigned int is greater than maximum")
                bounds(a, i)
                if (i >= 0) put32(a.b, 4 * i, x.toInt())
            }
            'l' -> setLong(a, i, cLong(v))
            'L' -> setLong(a, i, cULong(v))
            'q' -> setLong(a, i, cLongLong(v))
            'Q' -> setLong(a, i, cULongLong(v))
            'f' -> {
                val bits = PyStruct.castToFloat(PyMath.asDouble(v))
                bounds(a, i)
                if (i >= 0) put32(a.b, 4 * i, bits)
            }
            'd' -> {
                val x = PyMath.asDouble(v)
                bounds(a, i)
                if (i >= 0) put64(a.b, 8 * i, java.lang.Double.doubleToRawLongBits(x))
            }
            else -> {
                val c = character(v)
                if (i >= 0) put32(a.b, 4 * i, c)
            }
        }
    }

    private fun setLong(a: PyArrayObj, i: Int, x: Long) {
        bounds(a, i)
        if (i >= 0) put64(a.b, 8 * i, x)
    }

    // ------------------------------------------------------------------ size

    private fun noMemory() = PyException("MemoryError", "")

    /** newarrayobject: [size] items of [tc] (their bytes zeros), of [type]. */
    private fun newArray(type: PyType, size: Long, tc: Char): PyArrayObj {
        val itemsize = itemsizeOf(tc)
        if (size > MAX_BYTES / itemsize) throw noMemory()
        val a = PyArrayObj(type, tc, itemsize)
        if (size > 0) a.b = ByteArray(size.toInt() * itemsize)
        a.n = size.toInt()
        a.allocated = size.toInt()
        // (a subclass's __del__)
        if (type.mro.size > 2) PyWeakrefs.made(a)
        return a
    }

    /**
     * array_resize: [newsize] items, room made as CPython makes it (0, 4, 8, 16, 25, 34, 46, ...: a 16th more),
     * the buffer kept where it has room and does not shrink by 16 or more. (CPython refuses while buffers are
     * exported; NeonPy cannot tell when a view is gone, docs/PYTHON_PLATFORM.md §12.2.)
     */
    private fun resize(a: PyArrayObj, newsize: Long) {
        if (a.allocated >= newsize && a.n < newsize + 16 && a.b.isNotEmpty()) {
            a.n = newsize.toInt()
            return
        }
        if (newsize == 0L) {
            a.b = NO_BYTES
            a.n = 0
            a.allocated = 0
            return
        }
        val alloc = (newsize shr 4) + (if (a.n < 8) 3 else 7) + newsize
        if (alloc > MAX_BYTES / a.itemsize) throw noMemory()
        a.b = a.b.copyOf(alloc.toInt() * a.itemsize)
        a.n = newsize.toInt()
        a.allocated = alloc.toInt()
    }

    /** memmove within [b]: [len] bytes from [from] to [to]. */
    private fun move(b: ByteArray, to: Long, from: Long, len: Long) {
        if (len > 0) System.arraycopy(b, from.toInt(), b, to.toInt(), len.toInt())
    }

    /** ins1: [v] (checked first) inserted before [where] (clamped). */
    private fun ins1(a: PyArrayObj, where0: Long, v: Any?) {
        val n = a.n
        setItem(a, -1, v)
        resize(a, n + 1L)
        var where = where0
        if (where < 0) {
            where += n
            if (where < 0) where = 0
        }
        if (where > n) where = n.toLong()
        val s = a.itemsize
        if (where != n.toLong()) move(a.b, (where + 1) * s, where * s, (n - where) * s)
        setItem(a, where.toInt(), v)
    }

    /** array_slice: items [lo, hi) (clamped) as a new array (of array.array, not of a subclass). */
    private fun slice(st: State, a: PyArrayObj, lo0: Long, hi0: Long): PyArrayObj {
        val n = a.n.toLong()
        val lo = lo0.coerceIn(0, n)
        val hi = if (hi0 < lo) lo else minOf(hi0, n)
        val r = newArray(st.arrayType, hi - lo, a.tc)
        if (hi > lo) System.arraycopy(a.b, (lo * a.itemsize).toInt(), r.b, 0, ((hi - lo) * a.itemsize).toInt())
        return r
    }

    /** array_del_slice: items [lo, hi) (clamped) removed. */
    private fun delSlice(a: PyArrayObj, lo0: Long, hi0: Long) {
        val n = a.n.toLong()
        val lo = lo0.coerceIn(0, n)
        val hi = if (hi0 < lo) lo else minOf(hi0, n)
        val d = hi - lo
        if (d > 0) {
            val s = a.itemsize
            move(a.b, (hi - d) * s, hi * s, (n - hi) * s)
            resize(a, n - d)
        }
    }

    /** array_do_extend: another array's items (of the same typecode), else an iterable's, appended. */
    private fun extend(a: PyArrayObj, bb: Any?) {
        if (bb !is PyArrayObj) {
            val it = PyRt.iter(bb)
            while (true) {
                val v = it.next()
                if (v === PyIterator.STOP) break
                ins1(a, a.n.toLong(), v)
            }
            return
        }
        if (a.tc != bb.tc) throw PyRt.typeError("can only extend with array of same kind")
        val old = a.n
        val count = bb.n
        if (old.toLong() + count > MAX_BYTES / a.itemsize) throw noMemory()
        resize(a, old.toLong() + count)
        // (bb may be a itself: its bytes after the resize, the first count items)
        if (count > 0) System.arraycopy(bb.b, 0, a.b, old * a.itemsize, count * a.itemsize)
    }

    /** _PyBytes_Repeat: the first [len] bytes of [src] [times] times into [dst] from 0. */
    private fun repeat(dst: ByteArray, src: ByteArray, len: Int, times: Long) {
        if (len == 0) return
        if (src !== dst) System.arraycopy(src, 0, dst, 0, len)
        var done = len.toLong()
        val total = len * times
        while (done < total) {
            val k = minOf(done, total - done)
            System.arraycopy(dst, 0, dst, done.toInt(), k.toInt())
            done += k
        }
    }

    // ------------------------------------------------------------------ indices and arguments

    /** PyIndex_Check: an int, or an object whose class has __index__. */
    private fun isIndex(v: Any?): Boolean = v is Long || v is BigInteger || v is PyBool ||
        v is PyNumSub && (v.num is Long || v.num is BigInteger) || PyClasses.classOf(v)?.let { PyDunder.userMethod(it, "__index__") != null } == true

    /** PyNumber_AsSsize_t([v], [err]): past a Py_ssize_t, [err] "cannot fit 'int' into an index-sized integer". */
    private fun asSsize(v: Any?, err: String): Long = when (val i = index(v)) {
        is Long -> i
        else -> (i as BigInteger).takeIf { it.bitLength() < 64 }?.toLong() ?: throw PyException(err, "cannot fit 'int' into an index-sized integer")
    }

    /** _PyNumber_Index then PyLong_AsSsize_t (Argument Clinic's Py_ssize_t). */
    private fun ssize(v: Any?): Long = when (val i = index(v)) {
        is Long -> i
        else -> (i as BigInteger).takeIf { it.bitLength() < 64 }?.toLong() ?: throw overflow("Python int too large to convert to C ssize_t")
    }

    /** _PyEval_SliceIndexNotNone: an int, clamped to a Py_ssize_t. */
    private fun sliceIndex(v: Any?): Long {
        if (!isIndex(v)) throw PyRt.typeError("slice indices must be integers or have an __index__ method")
        return when (val i = index(v)) {
            is Long -> i
            else -> if ((i as BigInteger).signum() < 0) Long.MIN_VALUE else SSIZE_MAX
        }
    }

    private const val NAME = "array.array"

    /** A method's receiver: an array, else the method descriptor's TypeError. */
    private fun self(a: Array<Any?>, n: String): PyArrayObj {
        if (a.isEmpty()) throw PyRt.typeError("unbound method array.$n() needs an argument")
        return a[0] as? PyArrayObj ?: throw PyRt.typeError("descriptor '$n' for '$NAME' objects doesn't apply to a '${PyTypes.name(a[0])}' object")
    }

    /** A slot wrapper's receiver (`__len__`...): an array, else its TypeError. */
    private fun slotSelf(a: Array<Any?>, n: String): PyArrayObj {
        if (a.isEmpty()) throw PyRt.typeError("descriptor '$n' of '$NAME' object needs an argument")
        return a[0] as? PyArrayObj ?: throw PyRt.typeError("descriptor '$n' requires a '$NAME' object but received a '${PyTypes.name(a[0])}'")
    }

    private fun noKw(n: String): (PyContext, Array<Any?>, PyDict) -> Any? = { _, _, _ ->
        throw PyRt.typeError("$n() takes no keyword arguments")
    }

    private fun s(n: Int) = if (n == 1) "" else "s"

    // ------------------------------------------------------------------ the module's state

    private class State(@JvmField val ctx: PyContext) {
        @JvmField val arrayType: PyType = moduleType(ctx, "array", true)
        @JvmField val iterType: PyType = moduleType(ctx, "arrayiterator", false)
        /** array._array_reconstructor, imported where __reduce_ex__ first needs it. */
        @JvmField var reconstructor: Any? = null
    }

    /** A type of the module: a heap type, as CPython's made from a spec are, each module's (each context's here). */
    private fun moduleType(ctx: PyContext, name: String, subclassable: Boolean): PyType {
        val t = PyType(name, arrayOf(PyTypes.OBJECT), heap = true)
        t.mro = PyTypes.c3(t, t.bases)
        t.module = "array"
        t.tpName = "array.$name"
        t.subclassable = subclassable
        // (its instances have no __dict__; a subclass's have)
        t.hasDict = false
        t.dispatchBuiltins = true
        PyTypes.OBJECT.addSubclass(ctx, t)
        return t
    }

    // ------------------------------------------------------------------ array()

    /** array.__new__(cls, typecode[, initializer]) (array_new). */
    private fun new(st: State, a: Array<Any?>, kw: PyDict?): PyArrayObj {
        if (a.isEmpty()) throw PyRt.typeError("array.array.__new__(): not enough arguments")
        val type = a[0] as? PyType ?: throw PyRt.typeError("array.array.__new__(X): X is not a type object (${PyTypes.name(a[0])})")
        if (!type.isSubtype(st.arrayType)) {
            val tn = type.tpName ?: type.name
            throw PyRt.typeError("array.array.__new__($tn): $tn is not a subtype of array.array")
        }
        // (keywords are refused unless a subclass's __init__ takes them)
        if (kw != null && kw.size > 0 && type.lookup("__init__") === PyClasses.OBJECT_INIT) throw PyRt.typeError("array.array() takes no keyword arguments")
        val nargs = a.size - 1
        if (nargs < 1) throw PyRt.typeError("array() takes at least 1 argument (0 given)")
        if (nargs > 2) throw PyRt.typeError("array() takes at most 2 arguments ($nargs given)")
        val tcArg = a[1] as? PyStr ?: throw PyRt.typeError("array() argument 1 must be a unicode character, not ${PyTypes.name(a[1])}")
        if (tcArg.length != 1) throw PyRt.typeError("array() argument 1 must be a unicode character, not a string of length ${tcArg.length}")
        val cp = tcArg.s.codePointAt(0)
        val initial0 = a.getOrNull(2)
        st.ctx.sysModule.dict[PyStr("audit")]?.let {
            PyCalls.callWith(st.ctx, it, arrayOf(PyStr("array.__new__"), PyStr(String(Character.toChars(cp))), initial0 ?: PyNone), NO_ARGS, NO_ARGS)
        }
        if (cp == 'u'.code) warn(st.ctx, "The 'u' type code is deprecated and will be removed in Python 3.16")
        return make(st, type, cp, initial0)
    }

    /** warnings.warn(msg, DeprecationWarning, 1): the Python code calling array(). */
    private fun warn(ctx: PyContext, msg: String) {
        val w = PyImport.importModule(ctx, "warnings")
        PyCalls.callWith(ctx, PyRt.getAttr(w, "warn"), arrayOf(PyStr(msg), PyExceptions.type("DeprecationWarning"), 1L), NO_ARGS, NO_ARGS)
    }

    /** array_new's work after its arguments: an array of [type] of typecode [cp] made of [initial0] (null: none). */
    private fun make(st: State, type: PyType, cp: Int, initial0: Any?): PyArrayObj {
        val unicode = cp == 'u'.code || cp == 'w'.code
        val c = String(Character.toChars(cp))
        if (initial0 != null && !unicode) {
            if (initial0 is PyStr) throw PyRt.typeError("cannot use a str to initialize an array with typecode '$c'")
            if (initial0 is PyArrayObj && (initial0.tc == 'u' || initial0.tc == 'w')) {
                throw PyRt.typeError("cannot use a unicode array to initialize an array with typecode '$c'")
            }
        }
        var initial = initial0
        var it: PyIterator? = null
        if (!(initial == null || initial is PyList || initial is PyByteArray || isBytes(initial) || initial is PyTuple ||
                unicode && initial is PyStr || initial is PyArrayObj && cp == initial.tc.code)) {
            it = PyRt.iter(initial)
            initial = null
        }
        if (cp > 0xFFFF || TYPECODES.indexOf(cp.toChar()) < 0) {
            throw PyException("ValueError", "bad typecode (must be b, B, u, w, h, H, i, I, l, L, q, Q, f or d)")
        }
        val tc = cp.toChar()
        val len = when (initial) {
            is PyList -> initial.items.size
            is PyTuple -> initial.items.size
            is PyArrayObj -> initial.n
            else -> 0
        }
        val r = newArray(type, len.toLong(), tc)
        when {
            initial is PyList || initial is PyTuple -> {
                val items = if (initial is PyList) initial.items else (initial as PyTuple).items.asList()
                for (i in 0 until len) {
                    // (PySequence_GetItem's check, then array_ass_item's: what __index__ ran may have changed either)
                    if (i >= items.size) throw PyException("IndexError", if (initial is PyList) "list index out of range" else "tuple index out of range")
                    if (i >= r.n) throw PyException("IndexError", "array assignment index out of range")
                    setItem(r, i, items[i])
                }
            }
            initial is PyArrayObj -> if (len > 0) System.arraycopy(initial.b, 0, r.b, 0, len * r.itemsize)
            initial is PyStr -> {
                val cps = initial.s.codePoints().toArray()
                if (cps.isNotEmpty()) {
                    r.b = ByteArray(cps.size * 4)
                    for ((k, x) in cps.withIndex()) put32(r.b, 4 * k, x)
                    r.n = cps.size
                    r.allocated = cps.size
                }
            }
            initial != null -> frombytes(r, PyByteSeqs.content(initial)!!, 1, false)
        }
        if (it != null) {
            while (true) {
                val v = it.next()
                if (v === PyIterator.STOP) break
                ins1(r, r.n.toLong(), v)
            }
        }
        return r
    }

    /** PyBytes_Check: bytes, or a subclass's instance. */
    private fun isBytes(v: Any?): Boolean = v is PyBytes || v is PyNumSub && v.num is PyBytes

    // ------------------------------------------------------------------ buffers

    /** PyObject_GetBuffer([v], PyBUF_SIMPLE): its bytes, its itemsize, whether they are [a]'s own. */
    private class Simple(@JvmField val bytes: ByteArray, @JvmField val itemsize: Int, @JvmField val own: Boolean)

    private fun simpleBuffer(a: PyArrayObj?, v: Any?): Simple = when (v) {
        is PyBytes -> Simple(v.b, 1, false)
        is PyByteArray -> Simple(v.bytes(), 1, false)
        is PyArrayObj -> Simple(v.bytes(), v.itemsize, v === a)
        is PyMemoryView -> {
            v.check()
            if (!v.contiguous()) throw PyException("BufferError", "memoryview: underlying buffer is not C-contiguous")
            Simple(v.bytes(), v.itemsize, v.root === a)
        }
        is PyNumSub -> (v.num as? PyBytes)?.let { Simple(it.b, 1, false) } ?: throw notBytesLike(v)
        else -> throw notBytesLike(v)
    }

    private fun notBytesLike(v: Any?) = PyRt.typeError("a bytes-like object is required, not '${PyTypes.name(v)}'")

    /** frombytes: [data] (a buffer of [itemsize]) appended as machine values; [own]: the array's own, exported. */
    private fun frombytes(a: PyArrayObj, data: ByteArray, itemsize: Int, own: Boolean) {
        if (itemsize != 1) throw PyRt.typeError("a bytes-like object is required")
        val s = a.itemsize
        if (data.size % s != 0) throw PyException("ValueError", "bytes length not a multiple of item size")
        val n = data.size / s
        if (n > 0) {
            // (what NeonPy can tell: its own bytes are being read, a buffer of it exported for the call)
            if (own) throw PyException("BufferError", "cannot resize an array that is exporting buffers")
            val old = a.n
            if (old.toLong() + n > MAX_BYTES / s) throw noMemory()
            resize(a, old.toLong() + n)
            System.arraycopy(data, 0, a.b, old * s, n * s)
        }
    }

    // ------------------------------------------------------------------ the methods

    /** METH_NOARGS. */
    private fun noArgs(t: PyType, n: String, impl: (PyArrayObj) -> Any?) {
        t.def(n, PyBuiltin(n, { _, a ->
            val x = self(a, n)
            if (a.size != 1) throw PyRt.typeError("array.$n() takes no arguments (${a.size - 1} given)")
            impl(x)
        }, { _, a, kw ->
            self(a, n)
            if (kw.size > 0) throw PyRt.typeError("array.$n() takes no keyword arguments")
            if (a.size != 1) throw PyRt.typeError("array.$n() takes no arguments (${a.size - 1} given)")
            impl(a[0] as PyArrayObj)
        }))
    }

    /** METH_O. */
    private fun one(t: PyType, n: String, impl: (PyArrayObj, Any?) -> Any?) {
        t.def(n, PyBuiltin(n, { _, a ->
            val x = self(a, n)
            if (a.size != 2) throw PyRt.typeError("array.$n() takes exactly one argument (${a.size - 1} given)")
            impl(x, a[1])
        }, { _, a, kw ->
            self(a, n)
            if (kw.size > 0) throw PyRt.typeError("array.$n() takes no keyword arguments")
            if (a.size != 2) throw PyRt.typeError("array.$n() takes exactly one argument (${a.size - 1} given)")
            impl(a[0] as PyArrayObj, a[1])
        }))
    }

    /** METH_FASTCALL: [min] to [max] arguments by position. */
    private fun fast(t: PyType, n: String, min: Int, max: Int, impl: (PyArrayObj, Array<Any?>) -> Any?) {
        t.def(n, PyBuiltin(n, { _, a ->
            val x = self(a, n)
            PyMath.checkPositional(n, a.size - 1, min, max)
            impl(x, a)
        }, { _, a, kw ->
            self(a, n)
            if (kw.size > 0) throw PyRt.typeError("array.$n() takes no keyword arguments")
            PyMath.checkPositional(n, a.size - 1, min, max)
            impl(a[0] as PyArrayObj, a)
        }))
    }

    /** METH_METHOD|METH_FASTCALL|METH_KEYWORDS of [count] positional-only parameters: Argument Clinic's messages. */
    private fun clinic(t: PyType, n: String, count: Int, impl: (PyArrayObj, Array<Any?>) -> Any?) {
        val names = List(count) { "" }
        fun call(a: Array<Any?>, kw: PyDict?): Any? {
            val x = self(a, n)
            return impl(x, PyMath.clinic(n, a.copyOfRange(1, a.size), kw, names, count, count, count))
        }
        t.def(n, PyBuiltin(n, { _, a -> call(a, null) }, { _, a, kw -> call(a, kw) }))
    }

    /** A slot wrapper of [args] arguments (`__len__` 0, `__getitem__` 1), "expected N arguments" otherwise. */
    private fun slot(t: PyType, n: String, args: Int, impl: (PyArrayObj, Array<Any?>) -> Any?) {
        fun call(a: Array<Any?>, kw: PyDict?): Any? {
            val x = slotSelf(a, n)
            if (kw != null && kw.size > 0) throw PyRt.typeError("wrapper $n() takes no keyword arguments")
            if (a.size - 1 != args) {
                val pre = if (args == 2 || n.endsWith("buffer__")) "$n " else ""
                throw PyRt.typeError("${pre}expected $args argument${s(args)}, got ${a.size - 1}")
            }
            return impl(x, a)
        }
        t.def(n, PyBuiltin(n, { _, a -> call(a, null) }, { _, a, kw -> call(a, kw) }))
    }

    private fun defineArray(st: State) {
        val t = st.arrayType
        t.def("__new__", PyBuiltin("__new__", { _, a -> new(st, a, null) }, { _, a, kw -> new(st, a, kw) }))
        defineItems(st, t)
        defineSequence(st, t)
        defineBytes(st, t)
        defineAttributes(st, t)
        t.def("__hash__", PyNone)
        t.def("__doc__", PyStr(ARRAY_DOC))
        t.def("__module__", PyStr("array"))
    }

    /** append, insert, pop, remove, extend, fromlist, count, index, reverse, clear, tolist. */
    private fun defineItems(st: State, t: PyType) {
        one(t, "append") { a, v -> ins1(a, a.n.toLong(), v); PyNone }
        fast(t, "insert", 2, 2) { a, x -> ins1(a, ssize(x[1]), x[2]); PyNone }
        fast(t, "pop", 0, 1) { a, x ->
            var i = if (x.size > 1) ssize(x[1]) else -1L
            if (a.n == 0) throw PyException("IndexError", "pop from empty array")
            if (i < 0) i += a.n
            if (i < 0 || i >= a.n) throw PyException("IndexError", "pop index out of range")
            val v = item(a, i.toInt())
            delSlice(a, i, i + 1)
            v
        }
        one(t, "remove") { a, v ->
            var i = 0
            while (i < a.n) {
                if (PyRt.eq(item(a, i), v)) {
                    delSlice(a, i.toLong(), i + 1L)
                    return@one PyNone
                }
                i++
            }
            throw PyException("ValueError", "array.remove(x): x not in array")
        }
        clinic(t, "extend", 1) { a, x -> extend(a, x[0]); PyNone }
        one(t, "fromlist") { a, list -> fromlist(a, list); PyNone }
        one(t, "count") { a, v ->
            var k = 0L
            var i = 0
            while (i < a.n) {
                if (PyRt.eq(item(a, i), v)) k++
                i++
            }
            k
        }
        fast(t, "index", 1, 3) { a, x ->
            var start = if (x.size > 2) sliceIndex(x[2]) else 0L
            var stop = if (x.size > 3) sliceIndex(x[3]) else SSIZE_MAX
            if (start < 0) {
                start += a.n
                if (start < 0) start = 0
            }
            if (stop < 0) stop += a.n
            // (its size read each time: the comparison may change it)
            var i = start
            while (i < stop && i < a.n) {
                if (PyRt.eq(item(a, i.toInt()), x[1])) return@fast i
                i++
            }
            throw PyException("ValueError", "array.index(x): x not in array")
        }
        noArgs(t, "reverse") { a -> reverse(a); PyNone }
        noArgs(t, "clear") { a -> resize(a, 0); PyNone }
        noArgs(t, "tolist") { a -> tolist(a) }
    }

    private fun fromlist(a: PyArrayObj, list: Any?) {
        if (list !is PyList) throw PyRt.typeError("arg must be list")
        val n = list.items.size
        if (n == 0) return
        val old = a.n
        resize(a, old.toLong() + n)
        for (i in 0 until n) {
            try {
                setItem(a, a.n - n + i, list.items[i])
            } catch (e: PyException) {
                resize(a, old.toLong())
                throw e
            }
            if (n != list.items.size) {
                resize(a, old.toLong())
                throw PyException("RuntimeError", "list changed size during iteration")
            }
        }
    }

    private fun reverse(a: PyArrayObj) {
        val s = a.itemsize
        val tmp = ByteArray(s)
        var p = 0
        var q = (a.n - 1) * s
        while (p < q) {
            System.arraycopy(a.b, p, tmp, 0, s)
            System.arraycopy(a.b, q, a.b, p, s)
            System.arraycopy(tmp, 0, a.b, q, s)
            p += s
            q -= s
        }
    }

    private fun tolist(a: PyArrayObj): PyList {
        val out = ArrayList<Any?>(a.n)
        for (i in 0 until a.n) out.add(item(a, i))
        return PyList(out)
    }

    /** The sequence's slots: len, items, slices, +, *, in, comparisons, iteration, repr. */
    private fun defineSequence(st: State, t: PyType) {
        slot(t, "__len__", 0) { a, _ -> a.n.toLong() }
        slot(t, "__getitem__", 1) { a, x -> subscript(st, a, x[1]) }
        slot(t, "__setitem__", 2) { a, x -> assign(st, a, x[1], x[2]); PyNone }
        slot(t, "__delitem__", 1) { a, x -> assign(st, a, x[1], null); PyNone }
        slot(t, "__contains__", 1) { a, x ->
            var i = 0
            var found = false
            while (!found && i < a.n) {
                found = PyRt.eq(item(a, i), x[1])
                i++
            }
            PyBool.of(found)
        }
        slot(t, "__iter__", 0) { a, _ -> ArrayIter(IterState(a)).also { it.type = st.iterType } }
        slot(t, "__repr__", 0) { a, _ -> PyStr(repr(a)) }
        slot(t, "__add__", 1) { a, x -> concat(st, a, x[1]) }
        slot(t, "__mul__", 1) { a, x -> if (!isIndex(x[1]) && reflected(x[1], "__rmul__")) PyNotImplemented else times(st, a, x[1]) }
        slot(t, "__rmul__", 1) { a, x -> times(st, a, x[1]) }
        slot(t, "__iadd__", 1) { a, x ->
            val o = x[1]
            if (o !is PyArrayObj) {
                if (reflected(o, "__radd__")) return@slot PyNotImplemented
                throw PyRt.typeError("can only extend array with array (not \"${PyTypes.name(o)}\")")
            }
            extend(a, o)
            a
        }
        slot(t, "__imul__", 1) { a, x ->
            if (!isIndex(x[1]) && reflected(x[1], "__rmul__")) return@slot PyNotImplemented
            inplaceRepeat(a, repeatCount(x[1]))
            a
        }
        for ((n, op) in listOf("__lt__" to PyOp.LT, "__le__" to PyOp.LE, "__eq__" to PyOp.EQ, "__ne__" to PyOp.NE, "__gt__" to PyOp.GT, "__ge__" to PyOp.GE)) {
            slot(t, n, 1) { a, x -> richcompare(a, x[1], op) }
        }
    }

    /**
     * Whether [o]'s class has its own reflected [name] (`__radd__`, `__rmul__`): CPython's number slots try it before
     * the array's sequence ones, so the array gives way (NotImplemented) rather than raise.
     */
    private fun reflected(o: Any?, name: String): Boolean =
        o !is PyArrayObj && PyClasses.classOf(o)?.let { PyDunder.userMethod(it, name) != null } == true

    /** sq_repeat's count: PyNumber_AsSsize_t(n, OverflowError), else the sequence's TypeError. */
    private fun repeatCount(v: Any?): Long {
        if (!isIndex(v)) throw PyRt.typeError("can't multiply sequence by non-int of type '${PyTypes.name(v)}'")
        return asSsize(v, "OverflowError")
    }

    private fun times(st: State, a: PyArrayObj, v: Any?): Any? {
        val count = maxOf(0L, repeatCount(v))
        val len = a.n.toLong()
        if (len != 0L && count > SSIZE_MAX / len) throw noMemory()
        val size = len * count
        val r = newArray(st.arrayType, size, a.tc)
        if (size > 0) repeat(r.b, a.b, a.n * a.itemsize, count)
        return r
    }

    private fun inplaceRepeat(a: PyArrayObj, n0: Long) {
        val size = a.n
        if (size > 0 && n0 != 1L) {
            val n = maxOf(0L, n0)
            val bytes = size.toLong() * a.itemsize
            if (n > 0 && bytes > SSIZE_MAX / n) throw noMemory()
            if (n * bytes > MAX_BYTES) throw noMemory()
            resize(a, n * size)
            repeat(a.b, a.b, bytes.toInt(), n)
        }
    }

    private fun concat(st: State, a: PyArrayObj, o: Any?): Any? {
        if (o !is PyArrayObj) {
            if (reflected(o, "__radd__")) return PyNotImplemented
            throw PyRt.typeError("can only append array (not \"${PyTypes.name(o)}\") to array")
        }
        if (a.tc != o.tc) throw PyRt.typeError("bad argument type for built-in operation")
        val r = newArray(st.arrayType, a.n.toLong() + o.n, a.tc)
        System.arraycopy(a.b, 0, r.b, 0, a.n * a.itemsize)
        System.arraycopy(o.b, 0, r.b, a.n * a.itemsize, o.n * o.itemsize)
        return r
    }

    /** array_subscr: an item by index, a new array by slice. */
    private fun subscript(st: State, a: PyArrayObj, key: Any?): Any? {
        if (isIndex(key)) {
            var i = asSsize(key, "IndexError")
            if (i < 0) i += a.n
            if (i < 0 || i >= a.n) throw PyException("IndexError", "array index out of range")
            return item(a, i.toInt())
        }
        if (key !is PySlice) throw PyRt.typeError("array indices must be integers")
        val (start, _, step, len) = key.indices(a.n.toLong())
        if (len <= 0) return newArray(st.arrayType, 0, a.tc)
        val s = a.itemsize
        val r = newArray(st.arrayType, len, a.tc)
        if (step == 1L) {
            System.arraycopy(a.b, (start * s).toInt(), r.b, 0, (len * s).toInt())
            return r
        }
        var cur = start
        for (i in 0 until len.toInt()) {
            System.arraycopy(a.b, (cur * s).toInt(), r.b, i * s, s)
            cur += step
        }
        return r
    }

    /** array_ass_subscr: an item set (by index), a slice replaced by another array's items, or deleted ([v] null). */
    private fun assign(st: State, a: PyArrayObj, key: Any?, v0: Any?) {
        var start: Long
        var stop: Long
        var step: Long
        val slicelength: Long
        if (isIndex(key)) {
            var i = asSsize(key, "IndexError")
            if (i < 0) i += a.n
            if (i < 0 || i >= a.n) throw PyException("IndexError", "array assignment index out of range")
            if (v0 != null) {
                setItem(a, i.toInt(), v0)
                return
            }
            start = i
            stop = i + 1
            step = 1
            slicelength = 1
        } else if (key is PySlice) {
            val r = key.indices(a.n.toLong())
            start = r[0]
            stop = r[1]
            step = r[2]
            slicelength = r[3]
        } else throw PyRt.typeError("array indices must be integers")
        val other: PyArrayObj?
        val needed: Long
        if (v0 == null) {
            other = null
            needed = 0
        } else if (v0 is PyArrayObj) {
            if (v0 === a) {
                // (a[i:j] = a: a copy of it first)
                assign(st, a, key, slice(st, v0, 0, v0.n.toLong()))
                return
            }
            if (v0.tc != a.tc) throw PyRt.typeError("bad argument type for built-in operation")
            other = v0
            needed = v0.n.toLong()
        } else throw PyRt.typeError("can only assign array (not \"${PyTypes.name(v0)}\") to array slice")
        val s = a.itemsize
        // (for a[2:1] = ..., the insertion point is start, not stop)
        if (step > 0 && stop < start || step < 0 && stop > start) stop = start
        if (step == 1L) {
            if (slicelength > needed) {
                move(a.b, (start + needed) * s, stop * s, (a.n - stop) * s)
                resize(a, a.n + needed - slicelength)
            } else if (slicelength < needed) {
                resize(a, a.n + needed - slicelength)
                move(a.b, (start + needed) * s, stop * s, (a.n - start - needed) * s)
            }
            if (needed > 0) System.arraycopy(other!!.b, 0, a.b, (start * s).toInt(), (needed * s).toInt())
            return
        }
        if (needed == 0L) {
            deleteExtended(a, start, step, slicelength)
            return
        }
        if (needed != slicelength) throw PyException("ValueError", "attempt to assign array of size $needed to extended slice of size $slicelength")
        var cur = start
        for (i in 0 until slicelength.toInt()) {
            System.arraycopy(other!!.b, i * s, a.b, (cur * s).toInt(), s)
            cur += step
        }
    }

    /** An extended slice deleted: its items' gaps closed up, as array_ass_subscr moves them. */
    private fun deleteExtended(a: PyArrayObj, start0: Long, step0: Long, slicelength: Long) {
        var start = start0
        var step = step0
        if (step < 0) {
            val stop = start + 1
            start = stop + step * (slicelength - 1) - 1
            step = -step
        }
        val s = a.itemsize
        var cur = start
        for (i in 0 until slicelength) {
            var lim = step - 1
            if (cur + step >= a.n) lim = a.n - cur - 1
            move(a.b, (cur - i) * s, (cur + 1) * s, lim * s)
            cur += step
        }
        cur = start + slicelength * step
        if (cur < a.n) move(a.b, (cur - slicelength) * s, cur * s, (a.n - cur) * s)
        resize(a, a.n - slicelength)
    }

    /** compareitems of two arrays of one typecode (not 'f' or 'd'): the first [len] items, as the C type compares. */
    private fun compareItems(x: PyArrayObj, y: PyArrayObj, len: Int): Int {
        val p = x.b
        val q = y.b
        for (i in 0 until len) {
            val c = when (x.tc) {
                'b' -> p[i].compareTo(q[i])
                'B' -> u8(p, i).compareTo(u8(q, i))
                'h' -> get16(p, 2 * i).toShort().compareTo(get16(q, 2 * i).toShort())
                'H' -> get16(p, 2 * i).compareTo(get16(q, 2 * i))
                'i', 'u' -> get32(p, 4 * i).compareTo(get32(q, 4 * i))
                'I', 'w' -> Integer.compareUnsigned(get32(p, 4 * i), get32(q, 4 * i))
                'l', 'q' -> get64(p, 8 * i).compareTo(get64(q, 8 * i))
                else -> java.lang.Long.compareUnsigned(get64(p, 8 * i), get64(q, 8 * i))
            }
            if (c != 0) return if (c < 0) -1 else 1
        }
        return 0
    }

    private fun sizes(op: Int, vs: Int, ws: Int): Boolean = when (op) {
        PyOp.LT -> vs < ws
        PyOp.LE -> vs <= ws
        PyOp.EQ -> true
        PyOp.NE -> false
        PyOp.GT -> vs > ws
        else -> vs >= ws
    }

    /** array_richcompare: arrays of one typecode by their C values, else item by item as Python's values. */
    private fun richcompare(v: PyArrayObj, w: Any?, op: Int): Any? {
        if (w !is PyArrayObj) return PyNotImplemented
        if (v.n != w.n && (op == PyOp.EQ || op == PyOp.NE)) return PyBool.of(op == PyOp.NE)
        if (v.tc == w.tc && v.tc != 'f' && v.tc != 'd') {
            val r = compareItems(v, w, minOf(v.n, w.n))
            if (r == 0) return PyBool.of(sizes(op, v.n, w.n))
            return PyBool.of(when (op) {
                PyOp.LT -> r < 0
                PyOp.LE -> r <= 0
                PyOp.EQ -> false
                PyOp.NE -> true
                PyOp.GT -> r > 0
                else -> r >= 0
            })
        }
        var i = 0
        while (i < v.n && i < w.n) {
            val vi = item(v, i)
            val wi = item(w, i)
            if (!PyRt.eq(vi, wi)) {
                if (op == PyOp.EQ) return PyBool.FALSE
                if (op == PyOp.NE) return PyBool.TRUE
                return PyRt.compareValue(op, vi, wi)
            }
            i++
        }
        return PyBool.of(sizes(op, v.n, w.n))
    }

    private fun repr(a: PyArrayObj): String {
        val name = (a.type.tpName ?: a.type.name).substringAfterLast('.')
        if (a.n == 0) return "$name('${a.tc}')"
        val v: Any? = if (a.tc == 'u' || a.tc == 'w') PyStr(tounicode(a)) else tolist(a)
        return "$name('${a.tc}', ${PyRt.repr(v)})"
    }

    /** tounicode's str: 'u' as PyUnicode_FromWideChar takes a wchar_t string, 'w' as strict UTF-32 decoding does. */
    private fun tounicode(a: PyArrayObj): String {
        if (a.tc == 'w') return PyCodecs.decodeUtf32(PyContext.current(), a.bytes(), null, -1, true).text
        val sb = StringBuilder(a.n)
        for (i in 0 until a.n) {
            val c = get32(a.b, 4 * i)
            if (c < 0 || c > 0x10FFFF) throw PyException("ValueError", "character U+${Integer.toHexString(c)} is not in range [U+0000; U+10ffff]")
            sb.appendCodePoint(c)
        }
        return sb.toString()
    }

    /** frombytes, tobytes, fromunicode, tounicode, fromfile, tofile, byteswap, buffer_info, __buffer__. */
    private fun defineBytes(st: State, t: PyType) {
        one(t, "frombytes") { a, v ->
            val buf = simpleBuffer(a, v)
            frombytes(a, buf.bytes, buf.itemsize, buf.own)
            PyNone
        }
        noArgs(t, "tobytes") { a -> PyBytes(a.bytes()) }
        one(t, "fromunicode") { a, v ->
            val u = v as? PyStr ?: throw PyRt.typeError("fromunicode() argument must be str, not ${PyTypes.name(v)}")
            if (a.tc != 'u' && a.tc != 'w') throw PyException("ValueError", "fromunicode() may only be called on unicode type arrays ('u' or 'w')")
            val cps = u.s.codePoints().toArray()
            if (cps.isNotEmpty()) {
                val old = a.n
                resize(a, old.toLong() + cps.size)
                for ((k, c) in cps.withIndex()) put32(a.b, 4 * (old + k), c)
            }
            PyNone
        }
        noArgs(t, "tounicode") { a ->
            if (a.tc != 'u' && a.tc != 'w') throw PyException("ValueError", "tounicode() may only be called on unicode type arrays ('u' or 'w')")
            PyStr(tounicode(a))
        }
        clinic(t, "fromfile", 2) { a, x -> fromfile(st, a, x[0], ssize(x[1])) }
        clinic(t, "tofile", 1) { a, x ->
            val nbytes = a.n * a.itemsize
            // (64K blocks at a time)
            var at = 0
            while (at < nbytes) {
                val size = minOf(65536, nbytes - at)
                PyCalls.callWith(st.ctx, PyRt.getAttr(x[0], "write"), arrayOf(PyBytes(a.b.copyOfRange(at, at + size))), NO_ARGS, NO_ARGS)
                at += size
            }
            PyNone
        }
        noArgs(t, "byteswap") { a -> byteswap(a); PyNone }
        // (the buffer's address: none where it has no items allocated, else one for it)
        noArgs(t, "buffer_info") { a ->
            PyTuple(arrayOf(if (a.b.isEmpty()) 0L else (System.identityHashCode(a.b).toLong() and 0xFFFFFFFFL) shl 4, a.n.toLong()))
        }
        // (the format where the flags ask for it, PyBUF_FORMAT; else bytes')
        slot(t, "__buffer__", 1) { a, x ->
            val flags = when (val f = index(x[1])) {
                is Long -> f.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }
                else -> null
            } ?: throw overflow("buffer flags out of range")
            PyMemoryView(a, a, 0, a.n, a.itemsize, if (flags and 4L != 0L) format(a.tc) else "B", a.itemsize, false)
        }
        slot(t, "__release_buffer__", 1) { _, x ->
            val m = x[1] as? PyMemoryView ?: throw PyRt.typeError("expected a memoryview object")
            m.released = true
            PyNone
        }
    }

    private fun fromfile(st: State, a: PyArrayObj, f: Any?, n: Long): Any? {
        val s = a.itemsize
        if (n < 0) throw PyException("ValueError", "negative count")
        if (n > SSIZE_MAX / s) throw noMemory()
        val nbytes = n * s
        val r = PyCalls.callWith(st.ctx, PyRt.getAttr(f, "read"), arrayOf(nbytes), NO_ARGS, NO_ARGS)
        if (!isBytes(r)) throw PyRt.typeError("read() didn't return bytes")
        val data = PyByteSeqs.content(r)!!
        frombytes(a, data, 1, false)
        if (data.size.toLong() != nbytes) throw PyException("EOFError", "read() didn't return enough bytes")
        return PyNone
    }

    private fun byteswap(a: PyArrayObj) {
        val s = a.itemsize
        if (s == 1) return
        val b = a.b
        var p = 0
        for (i in 0 until a.n) {
            var lo = p
            var hi = p + s - 1
            while (lo < hi) {
                val x = b[lo]
                b[lo] = b[hi]
                b[hi] = x
                lo++
                hi--
            }
            p += s
        }
    }

    /** typecode, itemsize, __copy__, __deepcopy__, __reduce_ex__, __sizeof__, __class_getitem__. */
    private fun defineAttributes(st: State, t: PyType) {
        // (getset descriptors with no setter)
        fun getter(n: String, get: (PyArrayObj) -> Any?) {
            t.dict[PyStr(n)] = PyAttrDescr(PyAttrDescrs.GETSET, t, n, true).also { d -> d.getter = { get(it as PyArrayObj) } }
        }
        getter("typecode") { PyStr(it.tc.toString()) }
        getter("itemsize") { it.itemsize.toLong() }
        noArgs(t, "__copy__") { a -> slice(st, a, 0, a.n.toLong()) }
        one(t, "__deepcopy__") { a, _ -> slice(st, a, 0, a.n.toLong()) }
        clinic(t, "__reduce_ex__", 1) { a, x -> reduceEx(st, a, x[0]) }
        noArgs(t, "__sizeof__") { a -> ARRAY_SIZE + 8L * a.type.slotCount + a.allocated.toLong() * a.itemsize }
        t.def("__class_getitem__", PyClassMethod(PyBuiltin("__class_getitem__") { _, a -> PyGenericAlias(a[0], PyTyping.argsOf(a[1])) }))
    }

    /**
     * array.__reduce_ex__: (array._array_reconstructor, (type, typecode, mformat_code, bytes), __dict__); before
     * protocol 3, (type, (typecode, list), __dict__).
     */
    private fun reduceEx(st: State, a: PyArrayObj, value: Any?): Any? {
        val rec = st.reconstructor ?: PyRt.getAttr(PyImport.importModule(st.ctx, "array"), "_array_reconstructor").also { st.reconstructor = it }
        if (!(value is Long || value is BigInteger || value is PyBool || value is PyNumSub && (value.num is Long || value.num is BigInteger))) {
            throw PyRt.typeError("__reduce_ex__ argument should be an integer")
        }
        val protocol = cLong(value)
        val dict = PyClasses.attrOrNull(a, "__dict__") ?: PyNone
        val tc = PyStr(a.tc.toString())
        if (protocol < 3) return PyTuple(arrayOf(a.type, PyTuple(arrayOf(tc, tolist(a))), dict))
        return PyTuple(arrayOf(rec, PyTuple(arrayOf(a.type, tc, mformat(a.tc).toLong(), PyBytes(a.bytes()))), dict))
    }

    // ------------------------------------------------------------------ the iterator

    /** An arrayiterator's state: its array (null once exhausted, let go) and where it is. */
    private class IterState(@JvmField var ao: PyArrayObj?) {
        @JvmField var index = 0

        fun next(): Any? {
            val a = ao ?: return PyIterator.STOP
            if (index < a.n) return item(a, index++)
            ao = null
            return PyIterator.STOP
        }
    }

    private class ArrayIter(@JvmField val state: IterState) : PyIterator({ state.next() })

    /** A method's receiver ([slot]: a slot wrapper's, `__next__`'s), else its descriptor's TypeError. */
    private fun iterSelf(a: Array<Any?>, n: String, slot: Boolean = false): ArrayIter {
        val it = a.getOrNull(0) as? ArrayIter
        if (it != null) return it
        if (slot) {
            if (a.isEmpty()) throw PyRt.typeError("descriptor '$n' of 'array.arrayiterator' object needs an argument")
            throw PyRt.typeError("descriptor '$n' requires a 'array.arrayiterator' object but received a '${PyTypes.name(a[0])}'")
        }
        if (a.isEmpty()) throw PyRt.typeError("unbound method arrayiterator.$n() needs an argument")
        throw PyRt.typeError("descriptor '$n' for 'array.arrayiterator' objects doesn't apply to a '${PyTypes.name(a[0])}' object")
    }

    private fun defineIter(st: State) {
        val t = st.iterType
        t.def("__next__", PyBuiltin("__next__") { _, a ->
            val v = iterSelf(a, "__next__", true).next()
            if (v === PyIterator.STOP) throw PyException("StopIteration", "") else v
        })
        t.def("__iter__", PyBuiltin("__iter__") { _, a -> iterSelf(a, "__iter__", true) })
        fun reduce(a: Array<Any?>, kw: PyDict?): Any? {
            val it = iterSelf(a, "__reduce__").state
            if (a.size > 1 || kw != null && kw.size > 0) throw PyRt.typeError("__reduce__() takes no arguments")
            val iter = st.ctx.builtins[PyStr("iter")]
            val ao = it.ao ?: return PyTuple(arrayOf(iter, PyTuple(arrayOf(PyTuple.EMPTY))))
            return PyTuple(arrayOf(iter, PyTuple(arrayOf(ao)), it.index.toLong()))
        }
        t.def("__reduce__", PyBuiltin("__reduce__", { _, a -> reduce(a, null) }, { _, a, kw -> reduce(a, kw) }))
        t.def("__setstate__", PyBuiltin("__setstate__", { _, a ->
            val it = iterSelf(a, "__setstate__")
            if (a.size != 2) throw PyRt.typeError("arrayiterator.__setstate__() takes exactly one argument (${a.size - 1} given)")
            setstate(it.state, a[1])
        }, noKw("arrayiterator.__setstate__")))
        t.def("__module__", PyStr("array"))
        t.newImpl = { _, _, _, _ -> throw PyRt.typeError("cannot create 'array.arrayiterator' instances") }
    }

    /** arrayiterator.__setstate__: PyLong_AsSsize_t of [state], clamped to the array. */
    private fun setstate(it: IterState, state: Any?): Any? {
        val v = PyRt.num(state)
        val index = when (v) {
            is Long -> v
            is PyBool -> if (v.value) 1L else 0L
            is BigInteger -> throw overflow("Python int too large to convert to C ssize_t")
            else -> throw PyRt.typeError("an integer is required")
        }
        val ao = it.ao ?: return PyNone
        it.index = index.coerceIn(0, ao.n.toLong()).toInt()
        return PyNone
    }

    // ------------------------------------------------------------------ the module

    /** array._array_reconstructor(arraytype, typecode, mformat_code, items): an array from its pickled bytes. */
    private fun reconstruct(st: State, a: Array<Any?>): Any? {
        PyMath.checkPositional("_array_reconstructor", a.size, 4, 4)
        val tcArg = a[1] as? PyStr ?: throw PyRt.typeError("_array_reconstructor() argument 2 must be a unicode character, not ${PyTypes.name(a[1])}")
        if (tcArg.length != 1) throw PyRt.typeError("_array_reconstructor(): argument 2 must be a unicode character, not a string of length ${tcArg.length}")
        val cp = tcArg.s.codePointAt(0)
        val code = PyBinascii.cInt(a[2])
        val items = a[3]
        val type = a[0] as? PyType ?: throw PyRt.typeError("first argument must be a type object, not ${PyTypes.name(a[0])}")
        if (!type.isSubtype(st.arrayType)) throw PyRt.typeError("${type.tpName ?: type.name} is not a subtype of array.array")
        if (cp > 0xFFFF || TYPECODES.indexOf(cp.toChar()) < 0) throw PyException("ValueError", "second argument must be a valid type code")
        if (code < 0 || code > UTF32_BE) throw PyException("ValueError", "third argument must be a valid machine format code.")
        if (!isBytes(items)) throw PyRt.typeError("fourth argument should be bytes, not ${PyTypes.name(items)}")
        val tc = cp.toChar()
        // (no decoding where the bytes are this machine's; array_new either way, its audit event and warning too)
        if (code == mformat(tc)) return new(st, arrayOf(type, tcArg, items), null)
        val b = PyByteSeqs.content(items)!!
        val size = when (code) {
            UNSIGNED_INT8, SIGNED_INT8 -> 1
            UNSIGNED_INT16_LE, UNSIGNED_INT16_LE + 1, SIGNED_INT16_LE, SIGNED_INT16_LE + 1 -> 2
            UNSIGNED_INT32_LE, UNSIGNED_INT32_LE + 1, SIGNED_INT32_LE, SIGNED_INT32_LE + 1, IEEE_754_FLOAT_LE, IEEE_754_FLOAT_BE, UTF16_LE, UTF16_BE -> 4
            else -> 8
        }
        if (b.size % size != 0) throw PyException("ValueError", "string length not a multiple of item size")
        val big = code % 2 == 1 && code != SIGNED_INT8
        fun at(i: Int, n: Int): Long {
            var x = 0L
            if (big) for (k in 0 until n) x = (x shl 8) or (b[i + k].toLong() and 0xFF)
            else for (k in n - 1 downTo 0) x = (x shl 8) or (b[i + k].toLong() and 0xFF)
            return x
        }
        val converted: Any
        var typecode = cp
        when (code) {
            IEEE_754_FLOAT_LE, IEEE_754_FLOAT_BE -> converted = PyList(ArrayList<Any?>((0 until b.size / 4).map { PyStruct.unpack4(at(it * 4, 4).toInt()) }))
            IEEE_754_DOUBLE_LE, IEEE_754_DOUBLE_BE -> converted = PyList(ArrayList<Any?>((0 until b.size / 8).map { java.lang.Double.longBitsToDouble(at(it * 8, 8)) }))
            UTF16_LE, UTF16_BE -> converted = PyStr(PyCodecs.decodeUtf16(st.ctx, b, "strict", if (code == UTF16_LE) -1 else 1, true).text)
            UTF32_LE, UTF32_BE -> converted = PyStr(PyCodecs.decodeUtf32(st.ctx, b, "strict", if (code == UTF32_LE) -1 else 1, true).text)
            else -> {
                // (the integer typecode of the size and signedness, the last of descriptors' order: an L of 4
                // bytes pickled on a 32-bit machine comes back an I)
                val signed = code == SIGNED_INT8 || code >= SIGNED_INT16_LE && (code - SIGNED_INT16_LE) % 4 < 2
                for (c in "bBhHiIlLqQ") if (itemsizeOf(c) == size && (c.isLowerCase() == signed)) typecode = c.code
                converted = PyList(ArrayList<Any?>((0 until b.size / size).map { i ->
                    val x = at(i * size, size)
                    when {
                        size == 8 && !signed -> unsigned(x)
                        size == 8 -> x
                        signed -> (x shl (64 - 8 * size)) shr (64 - 8 * size)
                        else -> x
                    }
                }))
            }
        }
        return new(st, arrayOf(type, PyStr(String(Character.toChars(typecode))), converted), null)
    }

    @JvmStatic
    fun fill(ctx: PyContext, d: PyDict) {
        val st = State(ctx)
        defineArray(st)
        defineIter(st)
        d[PyStr("ArrayType")] = st.arrayType
        d[PyStr("array")] = st.arrayType
        d[PyStr("typecodes")] = PyStr(TYPECODES)
        d[PyStr("_array_reconstructor")] = PyBuiltin("_array_reconstructor", { _, a -> reconstruct(st, a) }, noKw("array._array_reconstructor"))
        // (a MutableSequence, as the module registers it)
        val ms = PyRt.getAttr(PyImport.importModule(ctx, "collections.abc"), "MutableSequence")
        PyCalls.callWith(ctx, PyRt.getAttr(ms, "register"), arrayOf(st.arrayType), NO_ARGS, NO_ARGS)
    }
}
