package dev.mooner.neonpython

import java.util.Locale

/**
 * str's methods (docs/PYTHON_PLATFORM.md §11: the types behind literals), as CPython 3.14's unicodeobject.c has
 * them: indices by code point, Python's whitespace (str.isspace: '\xa0' and '\x1c'..'\x1f' among it), Unicode's
 * categories for the predicates, CPython's messages.
 */
object PyStrings {
    private fun self(a: Array<Any?>, n: String): PyStr =
        a.getOrNull(0) as? PyStr ?: throw PyRt.typeError("descriptor '$n' requires a 'str' object but received a '${PyTypes.name(a.getOrNull(0))}'")

    private fun str(v: Any?, n: String, i: Int): String =
        (v as? PyStr ?: throw PyRt.typeError("$n() argument $i must be str, not ${PyTypes.name(v)}")).s

    @JvmStatic
    fun isSpace(c: Int): Boolean = c in 0x09..0x0d || c in 0x1c..0x1f || c == 0x85 || Character.isSpaceChar(c)

    private fun cps(s: String): IntArray = s.codePoints().toArray()

    private fun ofCps(c: IntArray, from: Int = 0, to: Int = c.size): String = String(c, from, to - from)

    // ------------------------------------------------------------------ splitting

    @JvmStatic
    fun split(s: String, sep: String?, maxsplit: Int): PyList {
        val out = ArrayList<Any?>()
        if (sep == null) {
            val c = cps(s)
            var i = 0
            var n = 0
            while (true) {
                while (i < c.size && isSpace(c[i])) i++
                if (i >= c.size) break
                if (maxsplit >= 0 && n == maxsplit) {
                    out.add(PyStr(ofCps(c, i)))
                    break
                }
                val st = i
                while (i < c.size && !isSpace(c[i])) i++
                out.add(PyStr(ofCps(c, st, i)))
                n++
            }
            return PyList(out)
        }
        if (sep.isEmpty()) throw PyException("ValueError", "empty separator")
        var from = 0
        var n = 0
        while (maxsplit < 0 || n < maxsplit) {
            val k = s.indexOf(sep, from)
            if (k < 0) break
            out.add(PyStr(s.substring(from, k)))
            from = k + sep.length
            n++
        }
        out.add(PyStr(s.substring(from)))
        return PyList(out)
    }

    @JvmStatic
    fun rsplit(s: String, sep: String?, maxsplit: Int): PyList {
        val out = ArrayList<Any?>()
        if (sep == null) {
            val c = cps(s)
            var i = c.size - 1
            var n = 0
            while (true) {
                while (i >= 0 && isSpace(c[i])) i--
                if (i < 0) break
                if (maxsplit >= 0 && n == maxsplit) {
                    out.add(PyStr(ofCps(c, 0, i + 1)))
                    break
                }
                val end = i + 1
                while (i >= 0 && !isSpace(c[i])) i--
                out.add(PyStr(ofCps(c, i + 1, end)))
                n++
            }
            out.reverse()
            return PyList(out)
        }
        if (sep.isEmpty()) throw PyException("ValueError", "empty separator")
        var end = s.length
        var n = 0
        while (maxsplit < 0 || n < maxsplit) {
            val k = s.lastIndexOf(sep, end - sep.length)
            if (k < 0 || end - sep.length < 0) break
            out.add(PyStr(s.substring(k + sep.length, end)))
            end = k
            n++
        }
        out.add(PyStr(s.substring(0, end)))
        out.reverse()
        return PyList(out)
    }

    private fun isLineBreak(c: Int) = c == 0x0a || c == 0x0d || c == 0x0b || c == 0x0c || c in 0x1c..0x1e || c == 0x85 || c == 0x2028 || c == 0x2029

    @JvmStatic
    fun splitlines(s: String, keep: Boolean): PyList {
        val out = ArrayList<Any?>()
        val c = cps(s)
        var i = 0
        var st = 0
        while (i < c.size) {
            if (isLineBreak(c[i])) {
                var end = i + 1
                if (c[i] == 0x0d && end < c.size && c[end] == 0x0a) end++
                out.add(PyStr(ofCps(c, st, if (keep) end else i)))
                st = end
                i = end
            } else i++
        }
        if (st < c.size) out.add(PyStr(ofCps(c, st)))
        return PyList(out)
    }

    // ------------------------------------------------------------------ stripping and padding

    @JvmStatic
    fun strip(s: String, chars: String?, left: Boolean, right: Boolean): String {
        val c = cps(s)
        val set = chars?.let { cps(it).toHashSet() }
        fun strip(x: Int) = if (set == null) isSpace(x) else x in set
        var i = 0
        var j = c.size
        if (left) while (i < j && strip(c[i])) i++
        if (right) while (j > i && strip(c[j - 1])) j--
        return ofCps(c, i, j)
    }

    private fun fill(v: Any?, n: String): String {
        if (v == null) return " "
        val f = v as? PyStr ?: throw PyRt.typeError("$n() argument 2 must be str, not ${PyTypes.name(v)}")
        if (f.length != 1) throw PyRt.typeError("The fill character must be exactly one character long")
        return f.s
    }

    // ------------------------------------------------------------------ finding

    /** [s]'s code points from [start] to [end] (slice-adjusted, None allowed), as code point indices. */
    private fun range(len: Int, start: Any?, end: Any?): IntArray {
        fun idx(v0: Any?, dflt: Int): Int = when (val v = PyRt.indexValue(v0) ?: v0) {
            null, PyNone -> dflt
            is Long -> {
                var x = v
                if (x < 0) x += len
                x.coerceIn(0L, len.toLong()).toInt()
            }
            is PyBool -> (if (v.value) 1 else 0).coerceAtMost(len)
            else -> throw PyRt.typeError("slice indices must be integers or None or have an __index__ method")
        }
        val a = idx(start, 0)
        val b = idx(end, len)
        return intArrayOf(a, b)
    }

    private fun find(hay: IntArray, needle: IntArray, a: Int, b: Int, last: Boolean): Int {
        if (needle.size > b - a) return -1
        if (!last) {
            outer@ for (i in a..b - needle.size) {
                for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
                return i
            }
        } else {
            outer@ for (i in b - needle.size downTo a) {
                for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
                return i
            }
        }
        return -1
    }

    private fun findIn(a: Array<Any?>, n: String, last: Boolean): Int {
        val s = cps(self(a, n).s)
        val sub = cps((a.getOrNull(1) as? PyStr ?: throw PyRt.typeError("must be str, not ${PyTypes.name(a.getOrNull(1))}")).s)
        val st0 = a.getOrNull(2)
        // (an empty needle at a start past the end: not found, as CPython)
        if (st0 is Long && sub.isEmpty()) {
            val x = if (st0 < 0) st0 + s.size else st0
            if (x > s.size) return -1
        }
        val (st, end) = range(s.size, st0, a.getOrNull(3)).let { it[0] to it[1] }
        if (end < st) return -1
        return find(s, sub, st, end, last)
    }

    private fun count(a: Array<Any?>): Long {
        val s = cps(self(a, "count").s)
        val sub = cps((a.getOrNull(1) as? PyStr ?: throw PyRt.typeError("must be str, not ${PyTypes.name(a.getOrNull(1))}")).s)
        val (st, end) = range(s.size, a.getOrNull(2), a.getOrNull(3)).let { it[0] to it[1] }
        if (end < st) return 0
        if (sub.isEmpty()) return (end - st + 1).toLong()
        var n = 0L
        var i = st
        while (true) {
            val k = find(s, sub, i, end, false)
            if (k < 0) break
            n++
            i = k + sub.size
        }
        return n
    }

    private fun affix(a: Array<Any?>, n: String, end: Boolean): Boolean {
        val s = cps(self(a, n).s)
        val (st, e) = range(s.size, a.getOrNull(2), a.getOrNull(3)).let { it[0] to it[1] }
        val p = a.getOrNull(1)
        val options = when (p) {
            is PyStr -> listOf(p)
            is PyTuple -> p.items.map { it as? PyStr ?: throw PyRt.typeError("tuple for $n must only contain str, not ${PyTypes.name(it)}") }
            else -> throw PyRt.typeError("$n first arg must be str or a tuple of str, not ${PyTypes.name(p)}")
        }
        for (o in options) {
            val x = cps(o.s)
            if (e - st < x.size) continue
            val at = if (end) e - x.size else st
            if ((0 until x.size).all { s[at + it] == x[it] }) return true
        }
        return false
    }

    // ------------------------------------------------------------------ case

    private fun isCased(c: Int) = Character.isUpperCase(c) || Character.isLowerCase(c) || Character.isTitleCase(c)

    private fun cpLower(c: Int): String = String(Character.toChars(c)).lowercase(Locale.ROOT)
    private fun cpUpper(c: Int): String = String(Character.toChars(c)).uppercase(Locale.ROOT)
    private fun cpTitle(c: Int): String = if (c == 'ß'.code) "Ss" else String(Character.toChars(Character.toTitleCase(c)))

    @JvmStatic
    fun title(s: String): String {
        val sb = StringBuilder()
        var prev = false
        for (c in cps(s)) {
            sb.append(if (prev) cpLower(c) else cpTitle(c))
            prev = isCased(c)
        }
        return sb.toString()
    }

    @JvmStatic
    fun capitalize(s: String): String {
        val c = cps(s)
        if (c.isEmpty()) return s
        val sb = StringBuilder(cpTitle(c[0]))
        for (i in 1 until c.size) sb.append(cpLower(c[i]))
        return sb.toString()
    }

    @JvmStatic
    fun swapcase(s: String): String {
        val sb = StringBuilder()
        for (c in cps(s)) sb.append(when {
            Character.isUpperCase(c) || Character.isTitleCase(c) -> cpLower(c)
            Character.isLowerCase(c) -> cpUpper(c)
            else -> String(Character.toChars(c))
        })
        return sb.toString()
    }

    // ------------------------------------------------------------------ predicates

    private fun isDecimal(c: Int) = Character.getType(c) == Character.DECIMAL_DIGIT_NUMBER.toInt()
    private fun isDigit(c: Int) = isDecimal(c) || Character.getType(c) == Character.OTHER_NUMBER.toInt() && Character.getNumericValue(c) in 0..9
    private fun isNumeric(c: Int) = when (Character.getType(c).toByte()) {
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
    }
    private fun isAlpha(c: Int) = when (Character.getType(c).toByte()) {
        Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER, Character.OTHER_LETTER -> true
        else -> false
    }

    private fun all(s: String, p: (Int) -> Boolean): Boolean {
        val c = cps(s)
        return c.isNotEmpty() && c.all(p)
    }

    @JvmStatic
    fun isLower(s: String): Boolean {
        var cased = false
        for (c in cps(s)) {
            if (Character.isUpperCase(c) || Character.isTitleCase(c)) return false
            if (Character.isLowerCase(c)) cased = true
        }
        return cased
    }

    @JvmStatic
    fun isUpper(s: String): Boolean {
        var cased = false
        for (c in cps(s)) {
            if (Character.isLowerCase(c) || Character.isTitleCase(c)) return false
            if (Character.isUpperCase(c)) cased = true
        }
        return cased
    }

    @JvmStatic
    fun isTitle(s: String): Boolean {
        var cased = false
        var prev = false
        for (c in cps(s)) {
            if (Character.isUpperCase(c) || Character.isTitleCase(c)) {
                if (prev) return false
                prev = true
                cased = true
            } else if (Character.isLowerCase(c)) {
                if (!prev) return false
                prev = true
                cased = true
            } else prev = false
        }
        return cased
    }

    @JvmStatic
    fun isIdentifier(s: String): Boolean {
        val c = cps(s)
        if (c.isEmpty()) return false
        fun start(x: Int) = x == '_'.code || Character.isUnicodeIdentifierStart(x)
        fun part(x: Int) = start(x) || Character.isUnicodeIdentifierPart(x) && !Character.isIdentifierIgnorable(x)
        return start(c[0]) && (1 until c.size).all { part(c[it]) }
    }

    // ------------------------------------------------------------------ the methods

    @JvmStatic
    fun define() {
        val t = PyTypes.STR
        fun m(n: String, impl: (Array<Any?>, PyDict?) -> Any?) = t.def(n, PyBuiltin(n, { _, a -> impl(a, null) }, { _, a, kw -> impl(a, kw) }))
        fun kw(kw: PyDict?, n: String, a: Array<Any?>, i: Int): Any? = kw?.get(PyStr(n)) ?: a.getOrNull(i)
        fun maxsplit(v: Any?): Int = when (v) { null, PyNone -> -1; is Long -> v.toInt(); else -> throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer") }
        fun sepArg(v: Any?): String? = when (v) { null, PyNone -> null; is PyStr -> v.s; else -> throw PyRt.typeError("must be str or None, not ${PyTypes.name(v)}") }
        m("split") { a, k -> split(self(a, "split").s, sepArg(kw(k, "sep", a, 1)), maxsplit(kw(k, "maxsplit", a, 2))) }
        m("rsplit") { a, k -> rsplit(self(a, "rsplit").s, sepArg(kw(k, "sep", a, 1)), maxsplit(kw(k, "maxsplit", a, 2))) }
        m("splitlines") { a, k -> splitlines(self(a, "splitlines").s, PyRt.truthy(kw(k, "keepends", a, 1) ?: PyBool.FALSE)) }
        m("partition") { a, _ ->
            val s = self(a, "partition").s
            val sep = str(a.getOrNull(1), "partition", 1)
            if (sep.isEmpty()) throw PyException("ValueError", "empty separator")
            val k = s.indexOf(sep)
            if (k < 0) PyTuple(arrayOf(PyStr(s), PyStr.EMPTY, PyStr.EMPTY)) else PyTuple(arrayOf(PyStr(s.substring(0, k)), PyStr(sep), PyStr(s.substring(k + sep.length))))
        }
        m("rpartition") { a, _ ->
            val s = self(a, "rpartition").s
            val sep = str(a.getOrNull(1), "rpartition", 1)
            if (sep.isEmpty()) throw PyException("ValueError", "empty separator")
            val k = s.lastIndexOf(sep)
            if (k < 0) PyTuple(arrayOf(PyStr.EMPTY, PyStr.EMPTY, PyStr(s))) else PyTuple(arrayOf(PyStr(s.substring(0, k)), PyStr(sep), PyStr(s.substring(k + sep.length))))
        }
        for ((n, l, r) in listOf(Triple("strip", true, true), Triple("lstrip", true, false), Triple("rstrip", false, true))) {
            m(n) { a, _ -> PyStr(strip(self(a, n).s, sepArg(a.getOrNull(1)), l, r)) }
        }
        m("center") { a, _ ->
            val s = self(a, "center")
            val w = (a.getOrNull(1) as? Long ?: throw PyRt.typeError("'${PyTypes.name(a.getOrNull(1))}' object cannot be interpreted as an integer")).toInt()
            val f = fill(a.getOrNull(2), "center")
            val marg = w - s.length
            if (marg <= 0) s else {
                // (CPython's: the extra on the left where the width is odd)
                val left = marg / 2 + (marg and w and 1)
                PyStr(f.repeat(left) + s.s + f.repeat(marg - left))
            }
        }
        m("ljust") { a, _ ->
            val s = self(a, "ljust")
            val w = (a.getOrNull(1) as Long).toInt()
            if (w <= s.length) s else PyStr(s.s + fill(a.getOrNull(2), "ljust").repeat(w - s.length))
        }
        m("rjust") { a, _ ->
            val s = self(a, "rjust")
            val w = (a.getOrNull(1) as Long).toInt()
            if (w <= s.length) s else PyStr(fill(a.getOrNull(2), "rjust").repeat(w - s.length) + s.s)
        }
        m("zfill") { a, _ ->
            val s = self(a, "zfill")
            val w = (a.getOrNull(1) as Long).toInt()
            if (w <= s.length) s else {
                val sign = s.s.isNotEmpty() && (s.s[0] == '+' || s.s[0] == '-')
                val z = "0".repeat(w - s.length)
                PyStr(if (sign) s.s[0] + z + s.s.substring(1) else z + s.s)
            }
        }
        m("expandtabs") { a, k ->
            val s = self(a, "expandtabs").s
            val size = (kw(k, "tabsize", a, 1) as? Long ?: 8L).toInt()
            val sb = StringBuilder()
            var col = 0
            for (c in cps(s)) {
                when (c) {
                    '\t'.code -> if (size > 0) {
                        val n = size - col % size
                        sb.append(" ".repeat(n))
                        col += n
                    }
                    '\n'.code, '\r'.code -> { sb.appendCodePoint(c); col = 0 }
                    else -> { sb.appendCodePoint(c); col++ }
                }
            }
            PyStr(sb.toString())
        }
        m("capitalize") { a, _ -> PyStr(capitalize(self(a, "capitalize").s)) }
        m("title") { a, _ -> PyStr(title(self(a, "title").s)) }
        m("swapcase") { a, _ -> PyStr(swapcase(self(a, "swapcase").s)) }
        m("casefold") { a, _ -> PyStr(self(a, "casefold").s.uppercase(Locale.ROOT).lowercase(Locale.ROOT)) }
        m("upper") { a, _ -> PyStr(self(a, "upper").s.uppercase(Locale.ROOT)) }
        m("lower") { a, _ -> PyStr(self(a, "lower").s.lowercase(Locale.ROOT)) }
        m("find") { a, _ -> findIn(a, "find", false).toLong() }
        m("rfind") { a, _ -> findIn(a, "rfind", true).toLong() }
        m("index") { a, _ -> findIn(a, "index", false).let { if (it < 0) throw PyException("ValueError", "substring not found") else it.toLong() } }
        m("rindex") { a, _ -> findIn(a, "rindex", true).let { if (it < 0) throw PyException("ValueError", "substring not found") else it.toLong() } }
        m("count") { a, _ -> count(a) }
        m("startswith") { a, _ -> PyBool.of(affix(a, "startswith", false)) }
        m("endswith") { a, _ -> PyBool.of(affix(a, "endswith", true)) }
        m("replace") { a, k ->
            val s = self(a, "replace").s
            val old = str(a.getOrNull(1), "replace", 1)
            val new = str(a.getOrNull(2), "replace", 2)
            var count = (kw(k, "count", a, 3) as? Long ?: -1L)
            if (count < 0) count = Long.MAX_VALUE
            val sb = StringBuilder()
            if (old.isEmpty()) {
                val c = cps(s)
                var n = 0L
                for (x in c) {
                    if (n < count) { sb.append(new); n++ }
                    sb.appendCodePoint(x)
                }
                if (n < count) sb.append(new)
            } else {
                var from = 0
                var n = 0L
                while (n < count) {
                    val i = s.indexOf(old, from)
                    if (i < 0) break
                    sb.append(s, from, i).append(new)
                    from = i + old.length
                    n++
                }
                sb.append(s, from, s.length)
            }
            PyStr(sb.toString())
        }
        m("removeprefix") { a, _ -> val s = self(a, "removeprefix").s; val p = str(a.getOrNull(1), "removeprefix", 1); PyStr(if (s.startsWith(p)) s.substring(p.length) else s) }
        m("removesuffix") { a, _ -> val s = self(a, "removesuffix").s; val p = str(a.getOrNull(1), "removesuffix", 1); PyStr(if (p.isNotEmpty() && s.endsWith(p)) s.substring(0, s.length - p.length) else s) }
        m("isalpha") { a, _ -> PyBool.of(all(self(a, "isalpha").s, ::isAlpha)) }
        m("isalnum") { a, _ -> PyBool.of(all(self(a, "isalnum").s) { isAlpha(it) || isNumeric(it) }) }
        m("isdigit") { a, _ -> PyBool.of(all(self(a, "isdigit").s, ::isDigit)) }
        m("isdecimal") { a, _ -> PyBool.of(all(self(a, "isdecimal").s, ::isDecimal)) }
        m("isnumeric") { a, _ -> PyBool.of(all(self(a, "isnumeric").s, ::isNumeric)) }
        m("isspace") { a, _ -> PyBool.of(all(self(a, "isspace").s, ::isSpace)) }
        m("islower") { a, _ -> PyBool.of(isLower(self(a, "islower").s)) }
        m("isupper") { a, _ -> PyBool.of(isUpper(self(a, "isupper").s)) }
        m("istitle") { a, _ -> PyBool.of(isTitle(self(a, "istitle").s)) }
        m("isidentifier") { a, _ -> PyBool.of(isIdentifier(self(a, "isidentifier").s)) }
        m("isascii") { a, _ -> PyBool.of(self(a, "isascii").s.all { it.code < 0x80 }) }
        m("isprintable") { a, _ -> PyBool.of(cps(self(a, "isprintable").s).all { PyRt.printable(it) }) }
        m("translate") { a, _ ->
            val s = self(a, "translate").s
            val table = a.getOrNull(1)
            val sb = StringBuilder()
            for (c in cps(s)) {
                val r = try {
                    PyRt.getItem(table, c.toLong())
                } catch (e: PyException) {
                    if (e.matches(PyExceptions.type("LookupError"))) { sb.appendCodePoint(c); continue } else throw e
                }
                when (r) {
                    PyNone -> {}
                    is Long -> sb.appendCodePoint(r.toInt())
                    is PyStr -> sb.append(r.s)
                    else -> throw PyRt.typeError("character mapping must return integer, None or str")
                }
            }
            PyStr(sb.toString())
        }
        t.def("maketrans", PyStaticMethod(PyBuiltin("maketrans") { _, a ->
            val d = PyDict()
            when (a.size) {
                1 -> {
                    val src = a[0] as? PyDict ?: throw PyRt.typeError("if you give only one argument to maketrans it must be a dict")
                    for ((k, v) in src.map) {
                        val key = when (val kv = k.v) {
                            is PyStr -> if (kv.length == 1) kv.s.codePointAt(0).toLong() else throw PyException("ValueError", "string keys in translate table must be of length 1")
                            is Long -> kv
                            else -> throw PyRt.typeError("keys in translate table must be strings or integers")
                        }
                        d[key] = v
                    }
                }
                2, 3 -> {
                    val x = cps(str(a[0], "maketrans", 1))
                    val y = cps(str(a[1], "maketrans", 2))
                    if (x.size != y.size) throw PyException("ValueError", "the first two maketrans arguments must have equal length")
                    for (i in x.indices) d[x[i].toLong()] = y[i].toLong()
                    if (a.size == 3) for (c in cps(str(a[2], "maketrans", 3))) d[c.toLong()] = PyNone
                }
                else -> throw PyRt.typeError("maketrans() takes at most 3 arguments (${a.size} given)")
            }
            d
        }))
    }
}
