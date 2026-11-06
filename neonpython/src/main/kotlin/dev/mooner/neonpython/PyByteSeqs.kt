package dev.mooner.neonpython

import java.io.ByteArrayOutputStream
import java.math.BigInteger

/** A bytearray (its subclass's instance too): the first [size] bytes of [b]. */
class PyByteArray(@JvmField var b: ByteArray, @JvmField var size: Int) : PyValue() {
    constructor(content: ByteArray) : this(content, content.size)

    /** A copy of its bytes. */
    fun bytes(): ByteArray = b.copyOf(size)

    fun capacity(n: Int) {
        if (n > b.size) b = b.copyOf(maxOf(n, b.size * 2, 8))
    }

    /** Its bytes [from, to) replaced by [with]. */
    fun splice(from: Int, to: Int, with: ByteArray) {
        val n = size - (to - from) + with.size
        val out = ByteArray(maxOf(n, 8))
        b.copyInto(out, 0, 0, from)
        with.copyInto(out, from)
        b.copyInto(out, from + with.size, to, size)
        b = out
        size = n
    }
}

/**
 * A memoryview: [count] items of [format] ([itemsize] bytes each) of the bytes of [root] (a bytes' or a bytearray's),
 * from byte [offset], [stride] bytes apart; [obj] what it was made of. A bytearray may be resized while views of it
 * live (no reference count tells when a view is gone, docs/PYTHON_PLATFORM.md §12.2): a view the resize left past its
 * end raises where it is read.
 */
class PyMemoryView(@JvmField val obj: Any, @JvmField val root: Any, @JvmField val offset: Int, @JvmField val count: Int,
                   @JvmField val stride: Int, @JvmField val format: String, @JvmField val itemsize: Int, @JvmField val readonly: Boolean) {
    @JvmField var released = false

    fun check() {
        if (released) throw PyException("ValueError", "operation forbidden on released memoryview object")
    }

    /** The array its bytes are in, checked to hold them all still. */
    fun arr(): ByteArray {
        check()
        return when (root) {
            is PyBytes -> root.b
            is PyByteArray -> {
                if (count > 0 && offset + (count - 1) * stride + itemsize > root.size) {
                    throw PyException("BufferError", "memoryview: the bytearray it views was resized past it")
                }
                root.b
            }
            else -> throw IllegalStateException()
        }
    }

    fun contiguous() = count <= 1 || stride == itemsize

    /** Its bytes, in order. */
    fun bytes(): ByteArray {
        val a = arr()
        if (contiguous()) return a.copyOfRange(offset, offset + count * itemsize)
        val out = ByteArray(count * itemsize)
        for (i in 0 until count) a.copyInto(out, i * itemsize, offset + i * stride, offset + i * stride + itemsize)
        return out
    }
}

/**
 * bytes, bytearray and memoryview as CPython 3.14 has them: bytes' methods (and bytearray's, the same over its
 * content, giving bytearrays) where any "bytes-like object" (bytes, bytearray, memoryview) is taken, bytearray's
 * changes, memoryview's views and casts.
 */
object PyByteSeqs {
    private val NO_ARGS = arrayOfNulls<Any?>(0)

    @JvmField val BYTEARRAY_ITERATOR = PyLibrary.type("bytearray_iterator")
    @JvmField val MEMORY_ITERATOR = PyLibrary.type("memory_iterator")

    private fun u(b: Byte) = b.toInt() and 0xFF

    // ------------------------------------------------------------------ bytes-like objects

    /** The bytes of a bytes-like object (bytes, bytearray, memoryview), or null: a bytes' own array (not to be changed). */
    @JvmStatic
    fun content(v: Any?): ByteArray? = when (v) {
        is PyBytes -> v.b
        is PyByteArray -> v.bytes()
        is PyMemoryView -> v.bytes()
        is PyNumSub -> (v.num as? PyBytes)?.b
        else -> null
    }

    @JvmStatic fun isBytesLike(v: Any?): Boolean = v is PyBytes || v is PyByteArray || v is PyMemoryView || v is PyNumSub && v.num is PyBytes

    private fun need(v: Any?): ByteArray = content(v) ?: throw PyRt.typeError("a bytes-like object is required, not '${PyTypes.name(v)}'")

    /** A method's receiver's bytes (a bytes', a bytearray's, a subclass's instance's). */
    private fun self(v: Any?): ByteArray = when (v) {
        is PyBytes -> v.b
        is PyByteArray -> v.bytes()
        is PyNumSub -> (v.num as PyBytes).b
        else -> throw PyRt.typeError("descriptor requires a 'bytes' object but received a '${PyTypes.name(v)}'")
    }

    /** A result of a method of [receiver]'s kind: bytes for bytes, a bytearray for a bytearray (a subclass's too). */
    private fun wrap(receiver: Any?, b: ByteArray): Any = if (receiver is PyByteArray) PyByteArray(b) else PyBytes(b)

    private fun byteValue(v: Any?, what: String = "byte must be in range(0, 256)"): Int {
        val i = PyRt.indexValue(v) ?: throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer")
        val n = (i as? Long) ?: if (i is PyBool) (if (i.value) 1L else 0L) else throw PyException("ValueError", what)
        if (n < 0 || n > 255) throw PyException("ValueError", what)
        return n.toInt()
    }

    private fun isSpace(c: Int) = c == 32 || c in 9..13
    private fun isLower(c: Int) = c in 97..122
    private fun isUpper(c: Int) = c in 65..90
    private fun isAlpha(c: Int) = isLower(c) || isUpper(c)
    private fun isDigit(c: Int) = c in 48..57
    private fun lower(c: Int) = if (isUpper(c)) c + 32 else c
    private fun upper(c: Int) = if (isLower(c)) c - 32 else c

    @JvmStatic
    fun indexOf(h: ByteArray, n: ByteArray, from: Int, to: Int = h.size): Int {
        if (n.isEmpty()) return if (from <= to) from else -1
        outer@ for (i in from..to - n.size) {
            for (j in n.indices) if (h[i + j] != n[j]) continue@outer
            return i
        }
        return -1
    }

    private fun lastIndexOf(h: ByteArray, n: ByteArray, from: Int, to: Int): Int {
        if (n.isEmpty()) return if (from <= to) to else -1
        outer@ for (i in to - n.size downTo from) {
            for (j in n.indices) if (h[i + j] != n[j]) continue@outer
            return i
        }
        return -1
    }

    /** start and end as a slice takes them, over [n] bytes. */
    private fun bounds(n: Int, start: Any?, end: Any?): Pair<Int, Int> {
        fun one(v: Any?, dflt: Int): Int {
            if (v == null || v === PyNone) return dflt
            val i = PyRt.indexValue(v) ?: throw PyRt.typeError("slice indices must be integers or None or have an __index__ method")
            val x = when (i) {
                is Long -> i
                is PyBool -> if (i.value) 1L else 0L
                is BigInteger -> if (i.signum() < 0) Long.MIN_VALUE / 2 else Long.MAX_VALUE / 2
                else -> 0L
            }
            return (if (x < 0) maxOf(0L, x + n) else minOf(x, n.toLong())).toInt()
        }
        return one(start, 0) to one(end, n)
    }

    /** A find's needle: bytes-like, or an int (one byte). */
    private fun needle(v: Any?): ByteArray {
        content(v)?.let { return it }
        if (PyRt.indexValue(v) != null) return byteArrayOf(byteValue(v).toByte())
        throw PyRt.typeError("argument should be integer or bytes-like object, not '${PyTypes.name(v)}'")
    }

    private fun find(a: Array<Any?>, last: Boolean): Int {
        val h = self(a[0])
        val n = needle(a.getOrNull(1))
        val (s, e) = bounds(h.size, a.getOrNull(2), a.getOrNull(3))
        if (s > e || e - s < n.size) return -1
        return if (last) lastIndexOf(h, n, s, e) else indexOf(h, n, s, e)
    }

    private fun stripChars(v: Any?): ((Int) -> Boolean) {
        if (v == null || v === PyNone) return ::isSpace
        val set = need(v)
        return { c -> set.any { u(it) == c } }
    }

    private fun split(h: ByteArray, sep: ByteArray?, max: Int, right: Boolean): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        if (sep == null) {
            if (!right) {
                var i = 0
                var splits = 0
                while (true) {
                    while (i < h.size && isSpace(u(h[i]))) i++
                    if (i >= h.size) break
                    if (max >= 0 && splits >= max) {
                        var j = h.size
                        while (j > i && isSpace(u(h[j - 1]))) j--
                        out.add(h.copyOfRange(i, j))
                        break
                    }
                    val s = i
                    while (i < h.size && !isSpace(u(h[i]))) i++
                    out.add(h.copyOfRange(s, i))
                    splits++
                }
            } else {
                var j = h.size
                var splits = 0
                while (true) {
                    while (j > 0 && isSpace(u(h[j - 1]))) j--
                    if (j <= 0) break
                    if (max >= 0 && splits >= max) {
                        var i = 0
                        while (i < j && isSpace(u(h[i]))) i++
                        out.add(h.copyOfRange(i, j))
                        break
                    }
                    val e = j
                    while (j > 0 && !isSpace(u(h[j - 1]))) j--
                    out.add(h.copyOfRange(j, e))
                    splits++
                }
                out.reverse()
            }
            return out
        }
        if (sep.isEmpty()) throw PyException("ValueError", "empty separator")
        if (!right) {
            var from = 0
            var splits = 0
            while (max < 0 || splits < max) {
                val k = indexOf(h, sep, from)
                if (k < 0) break
                out.add(h.copyOfRange(from, k))
                from = k + sep.size
                splits++
            }
            out.add(h.copyOfRange(from, h.size))
        } else {
            var to = h.size
            var splits = 0
            while (max < 0 || splits < max) {
                val k = lastIndexOf(h, sep, 0, to)
                if (k < 0) break
                out.add(h.copyOfRange(k + sep.size, to))
                to = k
                splits++
            }
            out.add(h.copyOfRange(0, to))
            out.reverse()
        }
        return out
    }

    private fun pad(h: ByteArray, width: Int, fill: Int, left: Int): ByteArray {
        if (width <= h.size) return h.copyOf()
        val out = ByteArray(width) { fill.toByte() }
        h.copyInto(out, left)
        return out
    }

    private fun fillchar(fn: String, v: Any?): Int {
        if (v == null) return 32
        val b = content(v)
        if (b == null || b.size != 1 || v is PyMemoryView) {
            throw PyRt.typeError("$fn() argument 2 must be a byte string of length 1, not ${PyTypes.name(v)}")
        }
        return u(b[0])
    }

    private fun intArg(v: Any?): Int {
        val i = PyRt.indexValue(v) as? Long ?: throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer")
        return i.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }

    private fun hex(b: ByteArray, sep: Any?, per: Any?): String {
        val digits = "0123456789abcdef"
        if (sep == null || sep === PyNone) {
            val sb = StringBuilder(b.size * 2)
            for (x in b) sb.append(digits[u(x) shr 4]).append(digits[u(x) and 15])
            return sb.toString()
        }
        val s = when (sep) {
            is PyStr -> sep.s
            else -> content(sep)?.let { String(it, Charsets.ISO_8859_1) } ?: throw PyRt.typeError("sep must be str or bytes.")
        }
        if (s.length != 1) throw PyException("ValueError", "sep must be length 1.")
        val n = if (per == null) 1 else intArg(per)
        if (n == 0) return hex(b, null, null)
        val group = Math.abs(n)
        val sb = StringBuilder()
        for ((i, x) in b.withIndex()) {
            // (positive: groups counted from the right; negative: from the left)
            if (i > 0) {
                val at = if (n > 0) (b.size - i) % group == 0 else i % group == 0
                if (at) sb.append(s)
            }
            sb.append(digits[u(x) shr 4]).append(digits[u(x) and 15])
        }
        return sb.toString()
    }

    private fun fromhex(v: Any?): ByteArray {
        val s = when (v) {
            is PyStr -> v.s
            else -> content(v)?.let { String(it, Charsets.ISO_8859_1) } ?: throw PyRt.typeError("fromhex() argument must be str or bytes-like, not ${PyTypes.name(v)}")
        }
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            if (s[i] == ' ' || s[i] in "\t\n\r\u000b\u000c") { i++; continue }
            val hi = Character.digit(s[i], 16).takeIf { s[i].code < 128 } ?: -1
            if (hi < 0) throw PyException("ValueError", "non-hexadecimal number found in fromhex() arg at position $i")
            val lo = if (i + 1 < s.length && s[i + 1].code < 128) Character.digit(s[i + 1], 16) else -1
            if (lo < 0) throw PyException("ValueError", "non-hexadecimal number found in fromhex() arg at position ${i + 1}")
            out.write(hi * 16 + lo)
            i += 2
        }
        return out.toByteArray()
    }

    private fun title(h: ByteArray): ByteArray {
        val out = h.copyOf()
        var prevCased = false
        for (i in out.indices) {
            val c = u(out[i])
            out[i] = (if (prevCased) lower(c) else upper(c)).toByte()
            prevCased = isAlpha(c)
        }
        return out
    }

    private fun istitle(h: ByteArray): Boolean {
        var cased = false
        var prev = false
        for (x in h) {
            val c = u(x)
            if (isUpper(c)) {
                if (prev) return false
                prev = true
                cased = true
            } else if (isLower(c)) {
                if (!prev) return false
                prev = true
                cased = true
            } else prev = false
        }
        return cased
    }

    private fun splitlines(h: ByteArray, keep: Boolean): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        var s = 0
        while (i < h.size) {
            val c = u(h[i])
            if (c == 10 || c == 13) {
                var e = i + 1
                if (c == 13 && e < h.size && u(h[e]) == 10) e++
                out.add(h.copyOfRange(s, if (keep) e else i))
                s = e
                i = e
            } else i++
        }
        if (s < h.size) out.add(h.copyOfRange(s, h.size))
        return out
    }

    private fun replace(h: ByteArray, old: ByteArray, new: ByteArray, count: Int): ByteArray {
        val o = ByteArrayOutputStream()
        var n = 0
        if (old.isEmpty()) {
            for (i in 0..h.size) {
                if (count < 0 || n < count) {
                    o.write(new)
                    n++
                }
                if (i < h.size) o.write(u(h[i]))
            }
            return o.toByteArray()
        }
        var from = 0
        while (count < 0 || n < count) {
            val k = indexOf(h, old, from)
            if (k < 0) break
            o.write(h, from, k - from)
            o.write(new)
            from = k + old.size
            n++
        }
        o.write(h, from, h.size - from)
        return o.toByteArray()
    }

    private fun expandtabs(h: ByteArray, tab: Int): ByteArray {
        val o = ByteArrayOutputStream()
        var col = 0
        for (x in h) {
            val c = u(x)
            when (c) {
                9 -> if (tab > 0) {
                    val n = tab - col % tab
                    repeat(n) { o.write(32) }
                    col += n
                }
                10, 13 -> { o.write(c); col = 0 }
                else -> { o.write(c); col++ }
            }
        }
        return o.toByteArray()
    }

    private fun translate(h: ByteArray, table: Any?, delete: Any?): ByteArray {
        val t = if (table == null || table === PyNone) null else (content(table) ?: throw PyRt.typeError("a bytes-like object is required, not '${PyTypes.name(table)}'"))
        if (t != null && t.size != 256) throw PyException("ValueError", "translation table must be 256 characters long")
        val del = if (delete == null) ByteArray(0) else need(delete)
        val o = ByteArrayOutputStream()
        for (x in h) {
            if (del.any { it == x }) continue
            o.write(if (t == null) u(x) else u(t[u(x)]))
        }
        return o.toByteArray()
    }

    /** Arguments by position or by name: [names], the first [required] of them required. */
    private fun args(fn: String, a: Array<Any?>, kw: PyDict?, names: List<String>, required: Int = 0, posOnly: Int = 0): Array<Any?> {
        val out = arrayOfNulls<Any?>(names.size)
        val pos = a.size - 1
        if (pos > names.size) throw PyRt.typeError("$fn() takes at most ${names.size} argument${if (names.size == 1) "" else "s"} ($pos given)")
        for (i in 0 until pos) out[i] = a[i + 1]
        if (kw != null) for ((k, v) in kw.map) {
            val n = (k.v as PyStr).s
            val i = names.indexOf(n)
            if (i < posOnly) throw PyRt.typeError("$fn() got an unexpected keyword argument '$n'")
            if (i < pos) throw PyRt.typeError("argument for $fn() given by name ('$n') and position (${i + 1})")
            out[i] = v
        }
        for (i in 0 until required) if (out[i] == null) throw PyRt.typeError("$fn() missing required argument '${names[i]}' (pos ${i + 1})")
        return out
    }

    // ------------------------------------------------------------------ the methods

    /** The methods bytes and bytearray share: on [t]. */
    private fun shared(t: PyType) {
        fun m(n: String, impl: (Array<Any?>) -> Any?) = t.def(n, PyBuiltin(n) { _, a -> impl(a) })
        fun mk(n: String, impl: (Array<Any?>, PyDict?) -> Any?) = t.def(n, PyBuiltin(n, { _, a -> impl(a, null) }, { _, a, kw -> impl(a, kw) }))
        fun map(n: String, f: (Int) -> Int) = m(n) { a -> val h = self(a[0]); wrap(a[0], ByteArray(h.size) { f(u(h[it])).toByte() }) }
        map("upper", ::upper)
        map("lower", ::lower)
        map("swapcase") { c -> if (isUpper(c)) c + 32 else if (isLower(c)) c - 32 else c }
        m("capitalize") { a -> val h = self(a[0]); wrap(a[0], ByteArray(h.size) { (if (it == 0) upper(u(h[0])) else lower(u(h[it]))).toByte() }) }
        m("title") { a -> wrap(a[0], title(self(a[0]))) }
        fun test(n: String, f: (ByteArray) -> Boolean) = m(n) { a -> PyBool.of(f(self(a[0]))) }
        test("isalnum") { h -> h.isNotEmpty() && h.all { isAlpha(u(it)) || isDigit(u(it)) } }
        test("isalpha") { h -> h.isNotEmpty() && h.all { isAlpha(u(it)) } }
        test("isdigit") { h -> h.isNotEmpty() && h.all { isDigit(u(it)) } }
        test("isspace") { h -> h.isNotEmpty() && h.all { isSpace(u(it)) } }
        test("isascii") { h -> h.all { u(it) < 128 } }
        test("islower") { h -> h.any { isLower(u(it)) } && h.none { isUpper(u(it)) } }
        test("isupper") { h -> h.any { isUpper(u(it)) } && h.none { isLower(u(it)) } }
        test("istitle", ::istitle)
        m("center") { a ->
            val h = self(a[0])
            val w = intArg(a.getOrNull(1) ?: throw PyRt.typeError("center expected at least 1 argument, got 0"))
            val pad = w - h.size
            // (CPython's: the odd one on the left where the width is odd)
            val left = pad / 2 + (pad and w and 1)
            wrap(a[0], pad(h, w, fillchar("center", a.getOrNull(2)), maxOf(0, left)))
        }
        m("ljust") { a -> val h = self(a[0]); wrap(a[0], pad(h, intArg(a.getOrNull(1) ?: throw PyRt.typeError("ljust expected at least 1 argument, got 0")), fillchar("ljust", a.getOrNull(2)), 0)) }
        m("rjust") { a ->
            val h = self(a[0])
            val w = intArg(a.getOrNull(1) ?: throw PyRt.typeError("rjust expected at least 1 argument, got 0"))
            wrap(a[0], pad(h, w, fillchar("rjust", a.getOrNull(2)), maxOf(0, w - h.size)))
        }
        m("zfill") { a ->
            val h = self(a[0])
            val w = intArg(a.getOrNull(1) ?: throw PyRt.typeError("zfill() takes exactly one argument (0 given)"))
            if (w <= h.size) return@m wrap(a[0], h.copyOf())
            val out = pad(h, w, '0'.code, w - h.size)
            if (h.isNotEmpty() && (u(h[0]) == '+'.code || u(h[0]) == '-'.code)) {
                out[0] = h[0]
                out[w - h.size] = '0'.code.toByte()
            }
            wrap(a[0], out)
        }
        mk("expandtabs") { a, kw -> val x = args("expandtabs", a, kw, listOf("tabsize")); wrap(a[0], expandtabs(self(a[0]), x[0]?.let { intArg(it) } ?: 8)) }
        fun strip(n: String, l: Boolean, r: Boolean) = m(n) { a ->
            val h = self(a[0])
            val p = stripChars(a.getOrNull(1))
            var i = 0
            var j = h.size
            if (l) while (i < j && p(u(h[i]))) i++
            if (r) while (j > i && p(u(h[j - 1]))) j--
            wrap(a[0], h.copyOfRange(i, j))
        }
        strip("strip", true, true)
        strip("lstrip", true, false)
        strip("rstrip", false, true)
        m("find") { a -> find(a, false).toLong() }
        m("rfind") { a -> find(a, true).toLong() }
        m("index") { a -> find(a, false).also { if (it < 0) throw PyException("ValueError", "subsection not found") }.toLong() }
        m("rindex") { a -> find(a, true).also { if (it < 0) throw PyException("ValueError", "subsection not found") }.toLong() }
        m("count") { a ->
            val h = self(a[0])
            val n = needle(a.getOrNull(1))
            val (s, e) = bounds(h.size, a.getOrNull(2), a.getOrNull(3))
            if (s > e) return@m 0L
            if (n.isEmpty()) return@m (e - s + 1).toLong()
            var c = 0L
            var from = s
            while (true) {
                val k = indexOf(h, n, from, e)
                if (k < 0) break
                c++
                from = k + n.size
            }
            c
        }
        fun affix(n: String, end: Boolean) = m(n) { a ->
            val h = self(a[0])
            val (s, e) = bounds(h.size, a.getOrNull(2), a.getOrNull(3))
            val what = a.getOrNull(1)
            val opts = if (what is PyTuple) what.items.map { content(it) ?: throw PyRt.typeError("a bytes-like object is required, not '${PyTypes.name(it)}'") }
                else listOf(content(what) ?: throw PyRt.typeError("$n first arg must be bytes or a tuple of bytes, not ${PyTypes.name(what)}"))
            PyBool.of(s <= e && opts.any { p ->
                e - s >= p.size && (if (end) h.copyOfRange(e - p.size, e) else h.copyOfRange(s, s + p.size)).contentEquals(p)
            })
        }
        affix("startswith", false)
        affix("endswith", true)
        mk("split") { a, kw -> val x = args("split", a, kw, listOf("sep", "maxsplit")); PyList(ArrayList<Any?>(split(self(a[0]), x[0]?.takeIf { it !== PyNone }?.let(::need), x[1]?.let { intArg(it) } ?: -1, false).map { wrap(a[0], it) })) }
        mk("rsplit") { a, kw -> val x = args("rsplit", a, kw, listOf("sep", "maxsplit")); PyList(ArrayList<Any?>(split(self(a[0]), x[0]?.takeIf { it !== PyNone }?.let(::need), x[1]?.let { intArg(it) } ?: -1, true).map { wrap(a[0], it) })) }
        mk("splitlines") { a, kw -> val x = args("splitlines", a, kw, listOf("keepends")); PyList(ArrayList<Any?>(splitlines(self(a[0]), x[0]?.let { PyRt.truthy(it) } ?: false).map { wrap(a[0], it) })) }
        fun partition(n: String, last: Boolean) = m(n) { a ->
            val h = self(a[0])
            val sep = need(a.getOrNull(1))
            if (sep.isEmpty()) throw PyException("ValueError", "empty separator")
            val k = if (last) lastIndexOf(h, sep, 0, h.size) else indexOf(h, sep, 0)
            val e = ByteArray(0)
            val parts = if (k < 0) (if (last) arrayOf(e, e, h) else arrayOf(h, e, e))
                else arrayOf(h.copyOfRange(0, k), sep, h.copyOfRange(k + sep.size, h.size))
            PyTuple(Array(3) { wrap(a[0], parts[it].copyOf()) })
        }
        partition("partition", false)
        partition("rpartition", true)
        m("join") { a ->
            val sep = self(a[0])
            val parts = PyBuiltins.toList(a.getOrNull(1))
            val o = ByteArrayOutputStream()
            for ((i, p) in parts.withIndex()) {
                if (i > 0) o.write(sep)
                o.write(content(p) ?: throw PyRt.typeError("sequence item $i: expected a bytes-like object, ${PyTypes.name(p)} found"))
            }
            wrap(a[0], o.toByteArray())
        }
        mk("replace") { a, kw ->
            val x = args("replace", a, kw, listOf("old", "new", "count"), 2, posOnly = 2)
            wrap(a[0], replace(self(a[0]), need(x[0]), need(x[1]), x[2]?.let { intArg(it) } ?: -1))
        }
        m("removeprefix") { a -> val h = self(a[0]); val p = need(a.getOrNull(1)); wrap(a[0], if (h.size >= p.size && h.copyOfRange(0, p.size).contentEquals(p)) h.copyOfRange(p.size, h.size) else h.copyOf()) }
        m("removesuffix") { a -> val h = self(a[0]); val p = need(a.getOrNull(1)); wrap(a[0], if (p.isNotEmpty() && h.size >= p.size && h.copyOfRange(h.size - p.size, h.size).contentEquals(p)) h.copyOfRange(0, h.size - p.size) else h.copyOf()) }
        mk("hex") { a, kw -> val x = args("hex", a, kw, listOf("sep", "bytes_per_sep")); PyStr(hex(self(a[0]), x[0], x[1])) }
        mk("translate") { a, kw ->
            val x = args("translate", a, kw, listOf("table", "delete"), 1, posOnly = 1)
            wrap(a[0], translate(self(a[0]), x[0], x[1]))
        }
        mk("decode") { a, kw -> PyCodecs.bytesDecode(self(a[0]), a.copyOfRange(1, a.size), kw) }
        t.def("maketrans", PyStaticMethod(PyBuiltin("maketrans") { _, a ->
            if (a.size != 2) throw PyRt.typeError("maketrans expected 2 arguments, got ${a.size}")
            val f = need(a[0])
            val to = need(a[1])
            if (f.size != to.size) throw PyException("ValueError", "maketrans arguments must have same length")
            val table = ByteArray(256) { it.toByte() }
            for (i in f.indices) table[u(f[i])] = to[i]
            PyBytes(table)
        }))
        t.def("fromhex", PyClassMethod(PyBuiltin("fromhex") { ctx, a ->
            val cls = a[0] as PyType
            val b = fromhex(a.getOrNull(1))
            when (cls) {
                PyTypes.BYTES -> PyBytes(b)
                PyTypes.BYTEARRAY -> PyByteArray(b)
                else -> PyCalls.callWith(ctx, cls, arrayOf(if (cls.isSubtype(PyTypes.BYTEARRAY)) PyByteArray(b) else PyBytes(b)), NO_ARGS, NO_ARGS)
            }
        }))
        m("__bytes__") { a -> PyBytes(self(a[0]).copyOf()) }
        m("__len__") { a -> PyRt.lenValues(PyRt.num(a[0])) }
        m("__getitem__") { a -> PyRt.getItemValues(PyRt.num(a[0]), a.getOrNull(1)) }
        m("__contains__") { a -> PyBool.of(PyRt.containsValues(PyRt.num(a[0]), a.getOrNull(1))) }
        m("__iter__") { a -> PyRt.iterValues(PyRt.num(a[0])) }
        m("__add__") { a -> PyLiterals.bytesAdd(PyRt.num(a[0]), a.getOrNull(1)) }
        m("__mul__") { a -> PyLiterals.bytesMul(PyRt.num(a[0]), a.getOrNull(1)) }
        m("__rmul__") { a -> PyLiterals.bytesMul(PyRt.num(a[0]), a.getOrNull(1)) }
        m("__repr__") { a -> PyStr(PyRt.reprValues(PyRt.num(a[0]))) }
        m("__str__") { a -> PyStr(PyRt.reprValues(PyRt.num(a[0]))) }
        for ((n, op) in listOf("__lt__" to PyOp.LT, "__le__" to PyOp.LE, "__gt__" to PyOp.GT, "__ge__" to PyOp.GE)) m(n) { a ->
            val x = PyRt.num(a[0])
            val y = PyRt.num(a.getOrNull(1))
            if (y is PyBytes || y is PyByteArray) PyBool.of(PyRt.compare(op, x, y)) else PyNotImplemented
        }
        m("__eq__") { a -> val y = PyRt.num(a.getOrNull(1)); if (isBytesLike(y)) PyBool.of(PyRt.eqValues(PyRt.num(a[0]), y)) else PyNotImplemented }
        m("__ne__") { a -> val y = PyRt.num(a.getOrNull(1)); if (isBytesLike(y)) PyBool.of(!PyRt.eqValues(PyRt.num(a[0]), y)) else PyNotImplemented }
        m("__getnewargs__") { a -> PyTuple(arrayOf(PyBytes(self(a[0]).copyOf()))) }
        m("__mod__") { a -> wrap(a[0], percent(self(a[0]), a.getOrNull(1))) }
        m("__rmod__") { _ -> PyNotImplemented }
    }

    /** bytes % args: str's formatting over the bytes as Latin-1, %s and %b taking bytes-like objects (or __bytes__), %a and %r ascii(). */
    @JvmStatic
    fun percent(fmt: ByteArray, arg: Any?): ByteArray {
        val s = PyFormat.percent(String(fmt, Charsets.ISO_8859_1), arg, bytes = true)
        return s.toByteArray(Charsets.ISO_8859_1)
    }

    /** What %s and %b give an argument in bytes formatting, as Latin-1. */
    @JvmStatic
    fun percentBytes(ctx: PyContext, v: Any?, type: Char): String {
        content(v)?.let { return String(it, Charsets.ISO_8859_1) }
        val m = PyClasses.classOf(v)?.let { PyDunder.userMethod(it, "__bytes__") }
        if (m != null) {
            val r = PyCalls.callWith(ctx, m, arrayOf(v), NO_ARGS, NO_ARGS)
            return String(content(r) ?: throw PyRt.typeError("__bytes__ returned non-bytes (type ${PyTypes.name(r)})"), Charsets.ISO_8859_1)
        }
        throw PyRt.typeError("%b requires a bytes-like object, or an object that implements __bytes__, not '${PyTypes.name(v)}'")
    }

    /** %c in bytes formatting: an int in range(256) or one byte. */
    @JvmStatic
    fun percentChar(v: Any?): String {
        content(v)?.let {
            if (it.size != 1) throw PyRt.typeError("%c requires an integer in range(256) or a single byte, not a bytes object of length ${it.size}")
            return String(it, Charsets.ISO_8859_1)
        }
        val i = PyRt.indexValue(v) ?: throw PyRt.typeError("%c requires an integer in range(256) or a single byte, not ${PyTypes.name(v)}")
        val n = i as? Long ?: if (i is PyBool) (if (i.value) 1L else 0L) else -1L
        if (n < 0 || n > 255) throw PyException("OverflowError", "%c arg not in range(256)")
        return n.toInt().toChar().toString()
    }

    // ------------------------------------------------------------------ bytearray

    @JvmStatic
    fun newByteArray(args: Array<Any?>, kw: PyDict?): PyByteArray = PyByteArray(ByteArray(0)).also { fill(it, args, kw) }

    /** bytearray(source=b'', encoding, errors): what bytearray() takes, into [ba]. */
    private fun fill(ba: PyByteArray, args: Array<Any?>, kw: PyDict?) {
        if (args.size > 3) throw PyRt.typeError("bytearray() takes at most 3 arguments (${args.size} given)")
        val x = arrayOfNulls<Any?>(3)
        args.copyInto(x)
        kw?.map?.forEach { (k, v) ->
            val i = listOf("source", "encoding", "errors").indexOf((k.v as PyStr).s)
            if (i < 0) throw PyRt.typeError("bytearray() got an unexpected keyword argument '${(k.v as PyStr).s}'")
            x[i] = v
        }
        val b = if (x[0] == null) {
            if (x[1] != null || x[2] != null) throw PyRt.typeError("encoding or errors without sequence argument")
            ByteArray(0)
        } else bytesOf(x[0], x[1], x[2], "byte must be in range(0, 256)", false)
        ba.b = b
        ba.size = b.size
    }

    /**
     * bytes(...) and bytearray(...)'s content, as CPython takes [v]: a str with its [encoding], (bytes() only) what
     * its __bytes__ gives, an int a count of zeros, a bytes-like object's bytes, an iterable's ints.
     */
    @JvmStatic
    fun bytesOf(v: Any?, encoding: Any?, errors: Any?, rangeMsg: String, bytesHook: Boolean): ByteArray {
        // (encoding and errors C strings, as Argument Clinic takes them first)
        val fn = if (bytesHook) "bytes" else "bytearray"
        val enc = encoding?.let { PyCodecs.cstr(it, "$fn() argument 'encoding'") }
        val err = errors?.let { PyCodecs.cstr(it, "$fn() argument 'errors'") }
        if (v is PyStr) {
            if (enc == null) throw PyRt.typeError("string argument without an encoding")
            return content(PyCodecs.encodeText(PyContext.current(), v, enc, err))!!.copyOf()
        }
        if (encoding != null) throw PyRt.typeError("encoding without a string argument")
        if (errors != null) throw PyRt.typeError("errors without a string argument")
        if (bytesHook && v !is PyBytes) PyClasses.classOf(v)?.let { cls ->
            PyDunder.userMethod(cls, "__bytes__")?.let { m ->
                val r = PyCalls.callWith(PyContext.current(), m, arrayOf(v), NO_ARGS, NO_ARGS)
                return (content(r) ?: throw PyRt.typeError("__bytes__ returned non-bytes (type ${PyTypes.name(r)})")).copyOf()
            }
        }
        val n = when {
            v is Long || v is BigInteger || v is PyBool -> v
            v is PyNumSub && (v.num is Long || v.num is BigInteger) -> v.num
            v is PyObject && v.type.lookup("__index__") != null -> PyRt.indexValue(v)
            else -> null
        }
        if (n == null) content(v)?.let { return it.copyOf() }
        if (n != null) {
            val k = n as? Long ?: if (n is PyBool) (if (n.value) 1L else 0L) else throw PyException("OverflowError", "cannot fit 'int' into an index-sized integer")
            if (k < 0) throw PyException("ValueError", "negative count")
            return ByteArray(k.toInt())
        }
        val it = try {
            PyRt.iter(v)
        } catch (e: PyException) {
            if (e.matches(PyExceptions.type("TypeError"))) throw PyRt.typeError("cannot convert '${PyTypes.name(v)}' object to bytes") else throw e
        }
        val o = ByteArrayOutputStream()
        while (true) {
            val x = it.next()
            if (x === PyIterator.STOP) break
            o.write(byteValue(x, rangeMsg))
        }
        return o.toByteArray()
    }

    private fun ba(v: Any?, what: String): PyByteArray =
        v as? PyByteArray ?: throw PyRt.typeError("descriptor '$what' for 'bytearray' objects doesn't apply to a '${PyTypes.name(v)}' object")

    /** ba[key] = v: an int a byte, a slice the bytes of a bytes-like object (or an iterable of ints). */
    @JvmStatic
    fun setItem(ba: PyByteArray, key: Any?, v: Any?) {
        if (key is PySlice) {
            val with = content(v) ?: if (v is PyStr) throw PyRt.typeError("can assign only bytes, buffers, or iterables of ints in range(0, 256)")
                else if (PyRt.indexValue(v) != null && v !is PyValue) throw PyRt.typeError("can assign only bytes, buffers, or iterables of ints in range(0, 256)")
                else bytesOf(v, null, null, "byte must be in range(0, 256)", false)
            val (a, b, st, n) = key.indices(ba.size.toLong())
            if (st == 1L) {
                ba.splice(a.toInt(), maxOf(a, b).toInt(), with)
                return
            }
            if (with.size.toLong() != n) throw PyException("ValueError", "attempt to assign bytes of size ${with.size} to extended slice of size $n")
            var i = a
            for (k in 0 until n.toInt()) { ba.b[i.toInt()] = with[k]; i += st }
            return
        }
        val i = index(ba, key, "bytearray")
        ba.b[i] = byteValue(v).toByte()
    }

    @JvmStatic
    fun delItem(ba: PyByteArray, key: Any?) {
        if (key is PySlice) {
            val (a, b, st, n) = key.indices(ba.size.toLong())
            if (st == 1L) {
                if (b > a) ba.splice(a.toInt(), b.toInt(), ByteArray(0))
                return
            }
            val drop = HashSet<Int>()
            var i = a
            repeat(n.toInt()) { drop.add(i.toInt()); i += st }
            val keep = ByteArrayOutputStream()
            for (k in 0 until ba.size) if (k !in drop) keep.write(u(ba.b[k]))
            val nb = keep.toByteArray()
            ba.b = nb
            ba.size = nb.size
            return
        }
        val i = index(ba, key, "bytearray")
        ba.splice(i, i + 1, ByteArray(0))
    }

    private fun index(ba: PyByteArray, key: Any?, what: String): Int {
        val i = PyRt.indexValue(key) ?: throw PyRt.typeError("$what indices must be integers or slices, not ${PyTypes.name(key)}")
        val n = (i as? Long) ?: if (i is PyBool) (if (i.value) 1L else 0L) else throw PyException("IndexError", "$what index out of range")
        val k = if (n < 0) n + ba.size else n
        if (k < 0 || k >= ba.size) throw PyException("IndexError", "$what index out of range")
        return k.toInt()
    }

    @JvmStatic
    fun item(ba: PyByteArray, key: Any?): Any? {
        if (key is PySlice) {
            val (a, _, st, n) = key.indices(ba.size.toLong())
            val out = ByteArray(n.toInt())
            var i = a
            for (k in 0 until n.toInt()) { out[k] = ba.b[i.toInt()]; i += st }
            return PyByteArray(out)
        }
        return u(ba.b[index(ba, key, "bytearray")]).toLong()
    }

    @JvmStatic
    fun iter(ba: PyByteArray): PyIterator {
        var i = 0
        return PyIterator { if (i < ba.size) u(ba.b[i++]).toLong() else PyIterator.STOP }.also { it.type = BYTEARRAY_ITERATOR }
    }

    @JvmStatic
    fun repr(ba: PyByteArray): String {
        val name = ba.sub?.cls?.name ?: "bytearray"
        val b = ba.bytes()
        val q = if (b.contains('\''.code.toByte()) && !b.contains('"'.code.toByte())) '"' else '\''
        val sb = StringBuilder(name).append("(b").append(q)
        for (x in b) {
            val c = u(x)
            when {
                c == '\''.code || c == '\\'.code -> sb.append('\\').append(c.toChar())
                c == '\t'.code -> sb.append("\\t")
                c == '\n'.code -> sb.append("\\n")
                c == '\r'.code -> sb.append("\\r")
                c < 32 || c >= 127 -> sb.append("\\x").append(Integer.toHexString(c).padStart(2, '0'))
                else -> sb.append(c.toChar())
            }
        }
        return sb.append(q).append(')').toString()
    }

    private fun extend(ba: PyByteArray, v: Any?) {
        val b = content(v) ?: run {
            if (v is PyStr) throw PyRt.typeError("expected iterable of integers; got: 'str'")
            if (!isIterable(v)) throw PyRt.typeError("can't extend bytearray with ${PyTypes.name(v)}")
            bytesOf(v, null, null, "byte must be in range(0, 256)", false)
        }
        ba.splice(ba.size, ba.size, b)
    }

    private fun isIterable(v: Any?): Boolean = v is PyList || v is PyTuple || v is PyRange || v is PySet || v is PyDict || v is PyIterator ||
        v is PyGenBase || PyClasses.classOf(v)?.let { it.lookup("__iter__") != null || it.lookup("__getitem__") != null } == true

    private fun defineByteArray() {
        val t = PyTypes.BYTEARRAY
        t.newImpl = { _, _, a, kw -> newByteArray(a, kw?.takeIf { it.size > 0 }) }
        // (a subclass: __new__ makes it empty, __init__ fills it, as CPython's)
        t.def("__new__", PyBuiltin("__new__", { _, a -> newSub(a) }, { _, a, _ -> newSub(a) }))
        t.def("__init__", PyBuiltin("__init__", { _, a -> fill(ba(a[0], "__init__"), a.copyOfRange(1, a.size), null); PyNone },
            { _, a, kw -> fill(ba(a[0], "__init__"), a.copyOfRange(1, a.size), kw.takeIf { it.size > 0 }); PyNone }))
        fun m(n: String, impl: (PyByteArray, Array<Any?>) -> Any?) = t.def(n, PyBuiltin(n) { _, a -> impl(ba(a.getOrNull(0), n), a) })
        m("append") { b, a -> if (a.size != 2) throw PyRt.typeError("bytearray.append() takes exactly one argument (${a.size - 1} given)"); b.splice(b.size, b.size, byteArrayOf(byteValue(a[1]).toByte())); PyNone }
        m("extend") { b, a -> extend(b, a.getOrNull(1)); PyNone }
        m("insert") { b, a ->
            val i0 = PyRt.indexValue(a.getOrNull(1)) as? Long ?: throw PyRt.typeError("'${PyTypes.name(a.getOrNull(1))}' object cannot be interpreted as an integer")
            val v = byteValue(a.getOrNull(2))
            val i = (if (i0 < 0) maxOf(0L, i0 + b.size) else minOf(i0, b.size.toLong())).toInt()
            b.splice(i, i, byteArrayOf(v.toByte()))
            PyNone
        }
        m("pop") { b, a ->
            if (b.size == 0) throw PyException("IndexError", "pop from empty bytearray")
            val i0 = a.getOrNull(1)?.let { PyRt.indexValue(it) as? Long ?: throw PyRt.typeError("'${PyTypes.name(it)}' object cannot be interpreted as an integer") } ?: -1L
            val i = if (i0 < 0) i0 + b.size else i0
            if (i < 0 || i >= b.size) throw PyException("IndexError", "pop index out of range")
            val v = u(b.b[i.toInt()]).toLong()
            b.splice(i.toInt(), i.toInt() + 1, ByteArray(0))
            v
        }
        m("remove") { b, a ->
            val v = byteValue(a.getOrNull(1)).toByte()
            val i = (0 until b.size).firstOrNull { b.b[it] == v } ?: throw PyException("ValueError", "value not found in bytearray")
            b.splice(i, i + 1, ByteArray(0))
            PyNone
        }
        m("reverse") { b, _ -> val r = b.bytes().reversedArray(); b.b = r; b.size = r.size; PyNone }
        m("clear") { b, _ -> b.b = ByteArray(0); b.size = 0; PyNone }
        m("copy") { b, _ -> PyByteArray(b.bytes()) }
        m("resize") { b, a ->
            val n = PyRt.indexValue(a.getOrNull(1)) as? Long ?: throw PyRt.typeError("'${PyTypes.name(a.getOrNull(1))}' object cannot be interpreted as an integer")
            if (n < 0) throw PyException("ValueError", "Can only resize to positive sizes, got $n")
            if (n < b.size) b.size = n.toInt() else {
                b.capacity(n.toInt())
                java.util.Arrays.fill(b.b, b.size, n.toInt(), 0)
                b.size = n.toInt()
            }
            PyNone
        }
        m("__alloc__") { b, _ -> (b.b.size + 1).toLong() }
        m("__iadd__") { b, a ->
            val with = content(a.getOrNull(1)) ?: throw PyRt.typeError("can't concat ${PyTypes.name(a.getOrNull(1))} to bytearray")
            b.splice(b.size, b.size, with)
            b
        }
        m("__imul__") { b, a ->
            val n = PyRt.indexValue(a.getOrNull(1)) as? Long ?: throw PyRt.typeError("can't multiply sequence by non-int of type '${PyTypes.name(a.getOrNull(1))}'")
            val one = b.bytes()
            val out = ByteArrayOutputStream()
            repeat(maxOf(0L, n).toInt()) { out.write(one) }
            val r = out.toByteArray()
            b.b = r
            b.size = r.size
            b
        }
        m("__setitem__") { b, a -> setItem(b, a[1], a[2]); PyNone }
        m("__delitem__") { b, a -> delItem(b, a[1]); PyNone }
        t.def("__hash__", PyNone)
    }

    private fun newSub(a: Array<Any?>): Any {
        val cls = a.getOrNull(0) as? PyType ?: throw PyRt.typeError("bytearray.__new__(X): X is not a type object (${PyTypes.name(a.getOrNull(0))})")
        if (!cls.isSubtype(PyTypes.BYTEARRAY)) throw PyRt.typeError("bytearray.__new__(${cls.name}): ${cls.name} is not a subtype of bytearray")
        return PyByteArray(ByteArray(0)).also { if (cls !== PyTypes.BYTEARRAY) it.sub = PySub(cls) }
    }

    // ------------------------------------------------------------------ memoryview

    private val SIZES = mapOf('B' to 1, 'b' to 1, 'c' to 1, '?' to 1, 'h' to 2, 'H' to 2, 'i' to 4, 'I' to 4, 'l' to 8, 'L' to 8,
        'q' to 8, 'Q' to 8, 'n' to 8, 'N' to 8, 'f' to 4, 'd' to 8, 'e' to 2)

    private fun formatChar(f: String): Char? {
        val s = if (f.startsWith("@")) f.substring(1) else f
        return s.singleOrNull()?.takeIf { it in SIZES }
    }

    @JvmStatic
    fun newView(v: Any?): PyMemoryView = when (v) {
        is PyBytes -> PyMemoryView(v, v, 0, v.b.size, 1, "B", 1, true)
        is PyNumSub -> (v.num as? PyBytes)?.let { b -> PyMemoryView(v, b, 0, b.b.size, 1, "B", 1, true) }
            ?: throw PyRt.typeError("memoryview: a bytes-like object is required, not '${PyTypes.name(v)}'")
        is PyByteArray -> PyMemoryView(v, v, 0, v.size, 1, "B", 1, false)
        is PyMemoryView -> {
            v.check()
            PyMemoryView(v.obj, v.root, v.offset, v.count, v.stride, v.format, v.itemsize, v.readonly)
        }
        else -> throw PyRt.typeError("memoryview: a bytes-like object is required, not '${PyTypes.name(v)}'")
    }

    private fun unpack(m: PyMemoryView, a: ByteArray, at: Int): Any? {
        var x = 0L
        for (k in m.itemsize - 1 downTo 0) x = (x shl 8) or u(a[at + k]).toLong()
        return when (formatChar(m.format)) {
            'B', 'H', 'I' -> x
            'b' -> a[at].toLong()
            'h' -> x.toShort().toLong()
            'i' -> x.toInt().toLong()
            'l', 'q', 'n' -> x
            'L', 'Q', 'N' -> if (x >= 0) x else BigInteger.valueOf(x).add(BigInteger.ONE.shiftLeft(64))
            'c' -> PyBytes(byteArrayOf(a[at]))
            '?' -> PyBool.of(x != 0L)
            'f' -> java.lang.Float.intBitsToFloat(x.toInt()).toDouble()
            'd' -> java.lang.Double.longBitsToDouble(x)
            'e' -> halfToDouble(x.toInt())
            else -> throw PyException("NotImplementedError", "memoryview: format ${m.format} not supported")
        }
    }

    private fun halfToDouble(h: Int): Double {
        val s = if (h and 0x8000 != 0) -1.0 else 1.0
        val e = (h shr 10) and 0x1F
        val f = h and 0x3FF
        return when (e) {
            0 -> s * f * Math.pow(2.0, -24.0)
            31 -> if (f == 0) s * Double.POSITIVE_INFINITY else Double.NaN
            else -> s * (1 + f / 1024.0) * Math.pow(2.0, (e - 15).toDouble())
        }
    }

    private fun pack(m: PyMemoryView, v: Any?, a: ByteArray, at: Int) {
        val f = formatChar(m.format)
        val bad = { PyException("ValueError", "memoryview: invalid value for format '${m.format}'") }
        val bits: Long = when (f) {
            'c' -> {
                val b = content(v) ?: throw PyRt.typeError("memoryview: invalid type for format '${m.format}'")
                if (b.size != 1) throw bad()
                u(b[0]).toLong()
            }
            '?' -> if (PyRt.truthy(v)) 1L else 0L
            'f' -> java.lang.Float.floatToIntBits(PyRt.toDouble(v).toFloat()).toLong()
            'd' -> java.lang.Double.doubleToLongBits(PyRt.toDouble(v))
            else -> {
                val i = PyRt.indexValue(v) ?: throw PyRt.typeError("memoryview: invalid type for format '${m.format}'")
                val n = i as? Long ?: if (i is PyBool) (if (i.value) 1L else 0L) else if (i is BigInteger && (f == 'Q' || f == 'L' || f == 'N') && i.signum() >= 0 && i.bitLength() <= 64) i.toLong() else throw bad()
                val ok = when (f) {
                    'B' -> n in 0..255
                    'b' -> n in -128..127
                    'H' -> n in 0..65535
                    'h' -> n in -32768..32767
                    'I' -> n in 0..0xFFFFFFFFL
                    'i' -> n in Int.MIN_VALUE..Int.MAX_VALUE
                    'L', 'Q', 'N' -> i is BigInteger || n >= 0
                    else -> true
                }
                if (!ok) throw bad()
                n
            }
        }
        if (m.readonly) throw PyRt.typeError("cannot modify read-only memory")
        var x = bits
        for (k in 0 until m.itemsize) {
            a[at + k] = (x and 0xFF).toByte()
            x = x ushr 8
        }
    }

    private fun mv(v: Any?, n: String): PyMemoryView =
        v as? PyMemoryView ?: throw PyRt.typeError("descriptor '$n' for 'memoryview' objects doesn't apply to a '${PyTypes.name(v)}' object")

    @JvmStatic
    fun viewItem(m: PyMemoryView, key: Any?): Any? {
        m.check()
        if (key === PyEllipsis) return m
        if (key is PySlice) {
            val (a, _, st, n) = key.indices(m.count.toLong())
            return PyMemoryView(m.obj, m.root, m.offset + a.toInt() * m.stride, n.toInt(), m.stride * st.toInt(), m.format, m.itemsize, m.readonly)
        }
        val i = viewIndex(m, key)
        return unpack(m, m.arr(), m.offset + i * m.stride)
    }

    private fun viewIndex(m: PyMemoryView, key: Any?): Int {
        val i = PyRt.indexValue(key) ?: throw PyRt.typeError("memoryview: invalid slice key")
        val n = (i as? Long) ?: if (i is PyBool) (if (i.value) 1L else 0L) else throw PyException("IndexError", "index out of bounds on dimension 1")
        val k = if (n < 0) n + m.count else n
        if (k < 0 || k >= m.count) throw PyException("IndexError", "index out of bounds on dimension 1")
        return k.toInt()
    }

    @JvmStatic
    fun viewSetItem(m: PyMemoryView, key: Any?, v: Any?) {
        m.check()
        if (m.readonly) throw PyRt.typeError("cannot modify read-only memory")
        val a = m.arr()
        if (key is PySlice) {
            val (s, _, st, n) = key.indices(m.count.toLong())
            val src = newView(v.let { if (it is PyMemoryView || isBytesLike(it)) it else throw PyRt.typeError("a bytes-like object is required, not '${PyTypes.name(it)}'") })
            if (src.count.toLong() != n || src.format != m.format) throw PyException("ValueError", "memoryview assignment: lvalue and rvalue have different structures")
            val bytes = src.bytes()
            for (k in 0 until n.toInt()) bytes.copyInto(a, m.offset + (s.toInt() + k * st.toInt()) * m.stride, k * m.itemsize, (k + 1) * m.itemsize)
            return
        }
        pack(m, v, a, m.offset + viewIndex(m, key) * m.stride)
    }

    @JvmStatic
    fun tolist(m: PyMemoryView): PyList {
        val a = m.arr()
        return PyList(ArrayList<Any?>(m.count).also { l -> for (i in 0 until m.count) l.add(unpack(m, a, m.offset + i * m.stride)) })
    }

    private fun viewEq(m: PyMemoryView, o: Any?): Any? {
        if (m.released) return PyBool.of(m === o)
        val other = when (o) {
            is PyMemoryView -> if (o.released) return PyBool.FALSE else o
            else -> if (isBytesLike(o)) newView(o) else return PyNotImplemented
        }
        if (m.count != other.count) return PyBool.FALSE
        if (m.format == other.format) return PyBool.of(m.bytes().contentEquals(other.bytes()))
        return PyBool.of(PyRt.eq(tolist(m), tolist(other)))
    }

    /** hash(m): its bytes' where it is read-only and of bytes. */
    @JvmStatic
    fun viewHash(v: PyMemoryView): Long {
        v.check()
        if (!v.readonly) throw PyException("ValueError", "cannot hash writable memoryview object")
        if (formatChar(v.format) !in setOf('B', 'b', 'c')) throw PyException("ValueError", "memoryview: hashing is restricted to formats 'B', 'b' or 'c'")
        return PyHash.hash(PyBytes(v.bytes()))
    }

    @JvmStatic fun viewRepr(v: PyMemoryView): String =
        "<${if (v.released) "released " else ""}memory at 0x${Integer.toHexString(System.identityHashCode(v)).padStart(16, '0').uppercase()}>"

    @JvmStatic
    fun viewIter(v: PyMemoryView): PyIterator {
        v.check()
        var i = 0
        return PyIterator { if (i < v.count) unpack(v, v.arr(), v.offset + (i++) * v.stride) else PyIterator.STOP }.also { it.type = MEMORY_ITERATOR }
    }

    /** a == b of two bytes-like objects, one a memoryview; NotImplemented where the other is none. */
    @JvmStatic fun viewEquals(m: PyMemoryView, o: Any?): Any? = viewEq(m, o)

    private fun defineMemoryView() {
        val t = PyTypes.MEMORYVIEW
        t.dispatchBuiltins = true
        t.newImpl = { _, _, a, kw ->
            val o = a.getOrNull(0) ?: kw?.get(PyStr("object")) ?: throw PyRt.typeError("memoryview() missing required argument 'object' (pos 1)")
            newView(o)
        }
        fun m(n: String, impl: (PyMemoryView, Array<Any?>) -> Any?) = t.def(n, PyBuiltin(n) { _, a -> impl(mv(a.getOrNull(0), n), a) })
        fun mk(n: String, impl: (PyMemoryView, Array<Any?>, PyDict?) -> Any?) =
            t.def(n, PyBuiltin(n, { _, a -> impl(mv(a.getOrNull(0), n), a, null) }, { _, a, kw -> impl(mv(a.getOrNull(0), n), a, kw) }))
        fun prop(n: String, get: (PyMemoryView) -> Any?) =
            t.def(n, PyProperty(PyBuiltin(n) { _, a -> val v = mv(a[0], n); v.check(); get(v) }, null, null, null).also { it.name = n })
        prop("obj") { it.obj }
        prop("nbytes") { (it.count * it.itemsize).toLong() }
        prop("readonly") { PyBool.of(it.readonly) }
        prop("itemsize") { it.itemsize.toLong() }
        prop("format") { PyStr(it.format) }
        prop("ndim") { 1L }
        prop("shape") { PyTuple(arrayOf(it.count.toLong())) }
        prop("strides") { PyTuple(arrayOf(it.stride.toLong())) }
        prop("suboffsets") { PyTuple.EMPTY }
        prop("c_contiguous") { PyBool.of(it.contiguous()) }
        prop("f_contiguous") { PyBool.of(it.contiguous()) }
        prop("contiguous") { PyBool.of(it.contiguous()) }
        t.def("released", PyProperty(PyBuiltin("released") { _, a -> PyBool.of(mv(a[0], "released").released) }, null, null, null).also { it.name = "released" })
        mk("tobytes") { v, _, _ -> PyBytes(v.bytes()) }
        m("tolist") { v, _ -> tolist(v) }
        mk("hex") { v, a, kw -> val x = args("hex", a, kw, listOf("sep", "bytes_per_sep")); PyStr(hex(v.bytes(), x[0], x[1])) }
        m("toreadonly") { v, _ -> v.check(); PyMemoryView(v.obj, v.root, v.offset, v.count, v.stride, v.format, v.itemsize, true) }
        m("release") { v, _ -> v.released = true; PyNone }
        m("__enter__") { v, _ -> v.check(); v }
        m("__exit__") { v, _ -> v.released = true; PyNone }
        mk("cast") { v, a, kw ->
            v.check()
            val x = args("cast", a, kw, listOf("format", "shape"), 1)
            val f = (x[0] as? PyStr ?: throw PyRt.typeError("memoryview: format argument must be a string")).s
            val fc = formatChar(f) ?: throw PyException("ValueError", "memoryview: destination format must be a native single character format prefixed with an optional '@'")
            if (!v.contiguous()) throw PyRt.typeError("memoryview: casts are restricted to C-contiguous views")
            val src = formatChar(v.format)
            if (src !in setOf('B', 'b', 'c') && fc !in setOf('B', 'b', 'c')) throw PyRt.typeError("memoryview: cannot cast between two non-byte formats")
            val size = SIZES[fc]!!
            val nbytes = v.count * v.itemsize
            if (nbytes % size != 0) throw PyRt.typeError("memoryview: length is not a multiple of itemsize")
            if (x[1] != null && x[1] !== PyNone) {
                val shape = PyBuiltins.toList(x[1])
                if (shape.size != 1) throw PyException("NotImplementedError", "memoryview: casts to more than one dimension are not supported here")
            }
            PyMemoryView(v.obj, v.root, v.offset, nbytes / size, size, f, size, v.readonly)
        }
        m("__len__") { v, _ -> v.check(); v.count.toLong() }
        m("__getitem__") { v, a -> viewItem(v, a[1]) }
        m("__setitem__") { v, a -> viewSetItem(v, a[1], a[2]); PyNone }
        m("__iter__") { v, _ -> viewIter(v) }
        m("__eq__") { v, a -> viewEq(v, a[1]) }
        m("__ne__") { v, a -> viewEq(v, a[1]).let { if (it is PyBool) PyBool.of(!it.value) else it } }
        m("__hash__") { v, _ -> viewHash(v) }
        m("__repr__") { v, _ -> PyStr(viewRepr(v)) }
        m("count") { v, a -> var n = 0L; for (x in tolist(v).items) if (PyRt.eq(x, a.getOrNull(1))) n++; n }
        m("index") { v, a ->
            val items = tolist(v).items
            val (s, e) = bounds(items.size, a.getOrNull(2), a.getOrNull(3))
            (s until e).firstOrNull { PyRt.eq(items[it], a.getOrNull(1)) }?.toLong() ?: throw PyException("ValueError", "memoryview.index(x): x not found")
        }
        m("__buffer__") { v, _ -> newView(v) }
        m("__release_buffer__") { v, a -> (a.getOrNull(1) as? PyMemoryView)?.released = true; PyNone }
        t.def("__class_getitem__", PyClassMethod(PyBuiltin("__class_getitem__") { _, a -> PyGenericAlias(a[0], PyTyping.argsOf(a[1])) }))
    }

    /** The types' methods, and their names in builtins. */
    @JvmStatic
    fun define(d: PyDict) {
        shared(PyTypes.BYTES)
        shared(PyTypes.BYTEARRAY)
        defineByteArray()
        defineMemoryView()
        d[PyStr("bytearray")] = PyTypes.BYTEARRAY
        d[PyStr("memoryview")] = PyTypes.MEMORYVIEW
    }
}
