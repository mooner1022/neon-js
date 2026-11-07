package dev.mooner.neonpython

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/**
 * str's Unicode as CPython 3.14 has it (Objects/unicodectype.c over Objects/unicodetype_db.h): each code point's
 * flags (alpha, decimal, digit, numeric, lower, upper, title, cased, case-ignorable, XID_Start, XID_Continue,
 * printable, whitespace, line break), its decimal and digit values and its full and simple case mappings, and the str
 * methods made of them as unicodeobject.c makes them (do_upper, do_lower with its final sigma, do_casefold, do_title,
 * do_capitalize, do_swapcase, the is* loops, _PyUnicode_ScanIdentifier, _PyUnicode_TransformDecimalAndSpaceToASCII).
 * Its data is CPython's, not the JVM's or Android's (whose Unicode versions and rules differ):
 * neonpython/tools/gen_unicodetype.py dumps what CPython 3.14.6 answers for every code point into unicodetype.bin,
 * makeunicodedata.py's records and two-level index, read when a code point past ASCII is first asked about. ASCII is
 * answered apart, without the table.
 */
object PyUnicodeType {
    // unicodectype.c's flags (whitespace and line breaks: bits makeunicodedata.py's records keep)
    const val ALPHA = 0x01
    const val DECIMAL = 0x02
    const val DIGIT = 0x04
    const val LOWER = 0x08
    const val LINEBREAK = 0x10
    const val SPACE = 0x20
    const val TITLE = 0x40
    const val UPPER = 0x80
    const val XID_START = 0x100
    const val XID_CONTINUE = 0x200
    const val PRINTABLE = 0x400
    const val NUMERIC = 0x800
    const val CASE_IGNORABLE = 0x1000
    const val CASED = 0x2000
    private const val EXTENDED_CASE = 0x4000
    /** Py_UNICODE_ISALNUM's: alpha, decimal, digit or numeric. */
    const val ALNUM = ALPHA or DECIMAL or DIGIT or NUMERIC

    private const val MAX = 0x110000

    /** ASCII's flags, apart from the table (gen_unicodetype.py checks the table's ASCII is these). */
    private val ASCII = IntArray(128).also { a ->
        for (c in 0 until 128) {
            var f = 0
            if (c in 'A'.code..'Z'.code) f = ALPHA or UPPER or CASED or XID_START or XID_CONTINUE
            if (c in 'a'.code..'z'.code) f = ALPHA or LOWER or CASED or XID_START or XID_CONTINUE
            if (c in '0'.code..'9'.code) f = DECIMAL or DIGIT or NUMERIC or XID_CONTINUE
            if (c == '_'.code) f = XID_CONTINUE
            if (c in 0x20..0x7E) f = f or PRINTABLE
            if (c == '\''.code || c == '.'.code || c == ':'.code || c == '^'.code || c == '`'.code) f = f or CASE_IGNORABLE
            if (c in 0x09..0x0D || c in 0x1C..0x20) f = f or SPACE
            if (c in 0x0A..0x0D || c in 0x1C..0x1E) f = f or LINEBREAK
            a[c] = f
        }
    }

    // ------------------------------------------------------------------ the table

    /** The records (flags, decimal, digit, the upper, lower and title deltas or extended case indices), the
     *  extended case table, each code point's record through index1 and index2. */
    private class Db(b: ByteArray) {
        @JvmField val flags: IntArray
        @JvmField val decimal: IntArray
        @JvmField val digit: IntArray
        @JvmField val upper: IntArray
        @JvmField val lower: IntArray
        @JvmField val title: IntArray
        @JvmField val extended: IntArray
        @JvmField val shift: Int
        @JvmField val mask: Int
        @JvmField val index1: CharArray
        @JvmField val index2: CharArray

        init {
            var p = 0
            fun u(): Int {
                var r = 0
                var sh = 0
                while (true) {
                    val x = b[p++].toInt() and 0xFF
                    r = r or ((x and 0x7F) shl sh)
                    if (x and 0x80 == 0) return r
                    sh += 7
                }
            }
            fun z(): Int = u().let { (it ushr 1) xor -(it and 1) }
            val n = u()
            flags = IntArray(n)
            decimal = IntArray(n)
            digit = IntArray(n)
            upper = IntArray(n)
            lower = IntArray(n)
            title = IntArray(n)
            for (i in 0 until n) {
                flags[i] = u()
                decimal[i] = u()
                digit[i] = u()
                upper[i] = z()
                lower[i] = z()
                title[i] = z()
            }
            extended = IntArray(u()) { u() }
            shift = u()
            mask = (1 shl shift) - 1
            index1 = CharArray(u()) { u().toChar() }
            index2 = CharArray(u()) { u().toChar() }
            check(p == b.size && index1.size == MAX shr shift)
        }

        /** gettyperecord: [c]'s record (0 past the code points). */
        fun record(c: Int): Int = if (c < 0 || c >= MAX) 0 else index2[(index1[c ushr shift].code shl shift) + (c and mask)].code

        /** Latin-1's full lower, upper and casefold mappings ([LATIN1_LOWER]...) where each is one char, else 0
         *  (U+00DF's upper and casefold): str's case methods over Latin-1 a char at a time. */
        @JvmField val latin1 = Array(3) { CharArray(256) }
    }

    private const val LATIN1_LOWER = 0
    private const val LATIN1_UPPER = 1
    private const val LATIN1_FOLD = 2

    private val db: Db by lazy {
        val raw = PyUnicodeType::class.java.getResourceAsStream("unicodetype.bin")!!.use { it.readBytes() }
        check(String(raw, 0, 4, Charsets.ISO_8859_1) == "NPUT" && raw[4].toInt() == 1) { "unicodetype.bin: not version 1" }
        val n = ((raw[5].toInt() and 0xFF) shl 24) or ((raw[6].toInt() and 0xFF) shl 16) or ((raw[7].toInt() and 0xFF) shl 8) or (raw[8].toInt() and 0xFF)
        val d = Db(GZIPInputStream(ByteArrayInputStream(raw, 9, n)).use { it.readBytes() })
        val sb = StringBuilder()
        for (c in 0 until 256) {
            for (k in 0 until 3) {
                sb.setLength(0)
                val r = d.record(c)
                if (k == LATIN1_FOLD) foldFull(d, r, c, sb) else full(d, r, c, if (k == LATIN1_LOWER) d.lower[r] else d.upper[r], sb)
                d.latin1[k][c] = if (sb.length == 1) sb[0] else 0.toChar()
            }
        }
        d
    }

    // ------------------------------------------------------------------ code points

    /** [c]'s flags. */
    @JvmStatic fun flags(c: Int): Int {
        if (c and 0x7F.inv() == 0) return ASCII[c]
        val d = db
        return d.flags[d.record(c)]
    }

    @JvmStatic fun isAlpha(c: Int) = flags(c) and ALPHA != 0
    @JvmStatic fun isAlnum(c: Int) = flags(c) and ALNUM != 0
    @JvmStatic fun isDecimal(c: Int) = flags(c) and DECIMAL != 0
    @JvmStatic fun isDigit(c: Int) = flags(c) and DIGIT != 0
    @JvmStatic fun isNumeric(c: Int) = flags(c) and NUMERIC != 0
    @JvmStatic fun isSpace(c: Int) = flags(c) and SPACE != 0
    @JvmStatic fun isLinebreak(c: Int) = flags(c) and LINEBREAK != 0
    @JvmStatic fun isPrintable(c: Int) = flags(c) and PRINTABLE != 0
    @JvmStatic fun isLower(c: Int) = flags(c) and LOWER != 0
    @JvmStatic fun isUpper(c: Int) = flags(c) and UPPER != 0
    @JvmStatic fun isCased(c: Int) = flags(c) and CASED != 0
    @JvmStatic fun isCaseIgnorable(c: Int) = flags(c) and CASE_IGNORABLE != 0
    @JvmStatic fun isXidStart(c: Int) = flags(c) and XID_START != 0
    @JvmStatic fun isXidContinue(c: Int) = flags(c) and XID_CONTINUE != 0

    /** _PyUnicode_ToDecimalDigit: [c]'s decimal value, or -1. */
    @JvmStatic fun toDecimal(c: Int): Int {
        if (c and 0x7F.inv() == 0) return if (c in '0'.code..'9'.code) c - '0'.code else -1
        val d = db
        val r = d.record(c)
        return if (d.flags[r] and DECIMAL != 0) d.decimal[r] else -1
    }

    /** _PyUnicode_ToDigit: [c]'s digit value, or -1. */
    @JvmStatic fun toDigit(c: Int): Int {
        if (c and 0x7F.inv() == 0) return if (c in '0'.code..'9'.code) c - '0'.code else -1
        val d = db
        val r = d.record(c)
        return if (d.flags[r] and DIGIT != 0) d.digit[r] else -1
    }

    /** _PyUnicode_ToLowercase, the simple mapping (re's): the full one's first code point. */
    @JvmStatic fun toLower(c: Int): Int {
        if (c and 0x7F.inv() == 0) return if (c in 'A'.code..'Z'.code) c + 32 else c
        val d = db
        val r = d.record(c)
        return if (d.flags[r] and EXTENDED_CASE != 0) d.extended[d.lower[r] and 0xFFFF] else c + d.lower[r]
    }

    /** _PyUnicode_ToUppercase, the simple mapping (re's): the full one's first code point. */
    @JvmStatic fun toUpper(c: Int): Int {
        if (c and 0x7F.inv() == 0) return if (c in 'a'.code..'z'.code) c - 32 else c
        val d = db
        val r = d.record(c)
        return if (d.flags[r] and EXTENDED_CASE != 0) d.extended[d.upper[r] and 0xFFFF] else c + d.upper[r]
    }

    // ------------------------------------------------------------------ full case mappings

    /** A full mapping's field [v] of record [r] of [c] (a delta, else an index and a count): appended to [sb]. */
    private fun full(d: Db, r: Int, c: Int, v: Int, sb: StringBuilder) {
        if (d.flags[r] and EXTENDED_CASE == 0) {
            sb.appendCodePoint(c + v)
            return
        }
        val at = v and 0xFFFF
        for (k in at until at + (v ushr 24)) sb.appendCodePoint(d.extended[k])
    }

    /** _PyUnicode_ToLowerFull. */
    private fun lowerFull(c: Int, sb: StringBuilder) {
        if (c and 0x7F.inv() == 0) {
            sb.append((if (c in 'A'.code..'Z'.code) c + 32 else c).toChar())
            return
        }
        val d = db
        val r = d.record(c)
        full(d, r, c, d.lower[r], sb)
    }

    /** _PyUnicode_ToUpperFull. */
    private fun upperFull(c: Int, sb: StringBuilder) {
        if (c and 0x7F.inv() == 0) {
            sb.append((if (c in 'a'.code..'z'.code) c - 32 else c).toChar())
            return
        }
        val d = db
        val r = d.record(c)
        full(d, r, c, d.upper[r], sb)
    }

    /** _PyUnicode_ToTitleFull. */
    private fun titleFull(c: Int, sb: StringBuilder) {
        if (c and 0x7F.inv() == 0) {
            sb.append((if (c in 'a'.code..'z'.code) c - 32 else c).toChar())
            return
        }
        val d = db
        val r = d.record(c)
        full(d, r, c, d.title[r], sb)
    }

    /** _PyUnicode_ToFoldedFull: the casefold kept after the lower mapping where it differs, else the lower one. */
    private fun foldFull(c: Int, sb: StringBuilder) {
        if (c and 0x7F.inv() == 0) {
            sb.append((if (c in 'A'.code..'Z'.code) c + 32 else c).toChar())
            return
        }
        val d = db
        foldFull(d, d.record(c), c, sb)
    }

    private fun foldFull(d: Db, r: Int, c: Int, sb: StringBuilder) {
        val lo = d.lower[r]
        val n = (lo shr 20) and 7
        if (d.flags[r] and EXTENDED_CASE != 0 && n != 0) {
            val at = (lo and 0xFFFF) + (lo ushr 24)
            for (k in at until at + n) sb.appendCodePoint(d.extended[k])
        } else full(d, r, c, lo, sb)
    }

    /** handle_capital_sigma: whether the U+03A3 at [i] (a UTF-16 index of [s]) ends a word: after something cased
     *  and what is case-ignorable, and not before what is case-ignorable and then something cased. */
    private fun finalSigma(s: String, i: Int): Boolean {
        var j = i
        var c = 0
        var found = false
        while (j > 0) {
            c = s.codePointBefore(j)
            j -= Character.charCount(c)
            if (!isCaseIgnorable(c)) {
                found = true
                break
            }
        }
        if (!found || !isCased(c)) return false
        j = i + 1
        while (j < s.length) {
            c = s.codePointAt(j)
            if (!isCaseIgnorable(c)) return !isCased(c)
            j += Character.charCount(c)
        }
        return true
    }

    /** lower_ucs4: [c] (at [i] of [s]) lowered, the final sigma where U+03A3 is in that context. */
    private fun lowerAt(s: String, i: Int, c: Int, sb: StringBuilder) {
        if (c == 0x3A3) sb.append(if (finalSigma(s, i)) 'ς' else 'σ') else lowerFull(c, sb)
    }

    // ------------------------------------------------------------------ str's case methods

    private fun nonAscii(s: String): Boolean {
        for (i in 0 until s.length) if (s[i].code >= 0x80) return true
        return false
    }

    /**
     * [s] mapped a char at a time where each is Latin-1 and maps to one char: ASCII's [from]..[to] by [delta] (the
     * rest as they are), the rest by Latin-1's table [kind]; null where one is not. ([s] itself where none changes.)
     */
    private fun latin1Case(s: String, from: Char, to: Char, delta: Int, kind: Int): String? {
        // (ASCII that stays as it is: passed over in [s])
        var first = 0
        while (first < s.length) {
            val ch = s[first]
            if (ch.code >= 0x80 || ch in from..to) break
            first++
        }
        if (first == s.length) return s
        val a = s.toCharArray()
        var changed = false
        var map: CharArray? = null
        for (i in first until a.size) {
            val ch = a[i]
            if (ch.code < 0x80) {
                if (ch in from..to) {
                    a[i] = ch + delta
                    changed = true
                }
            } else if (ch.code < 0x100) {
                val m = (map ?: db.latin1[kind].also { map = it })[ch.code]
                if (m.code == 0) return null
                if (m != ch) {
                    a[i] = m
                    changed = true
                }
            } else return null
        }
        return if (changed) String(a) else s
    }

    /** str.upper (do_upper: each code point's full uppercase). */
    @JvmStatic fun upper(s: String): String {
        latin1Case(s, 'a', 'z', -32, LATIN1_UPPER)?.let { return it }
        val sb = StringBuilder(s.length + 8)
        var i = 0
        while (i < s.length) {
            val c = s.codePointAt(i)
            upperFull(c, sb)
            i += Character.charCount(c)
        }
        return sb.toString()
    }

    /** str.lower (do_lower: each code point's full lowercase, U+03A3 the final sigma where it ends a word). */
    @JvmStatic fun lower(s: String): String {
        latin1Case(s, 'A', 'Z', 32, LATIN1_LOWER)?.let { return it }
        val sb = StringBuilder(s.length + 8)
        var i = 0
        while (i < s.length) {
            val c = s.codePointAt(i)
            lowerAt(s, i, c, sb)
            i += Character.charCount(c)
        }
        return sb.toString()
    }

    /** str.casefold (do_casefold). */
    @JvmStatic fun casefold(s: String): String {
        latin1Case(s, 'A', 'Z', 32, LATIN1_FOLD)?.let { return it }
        val sb = StringBuilder(s.length + 8)
        var i = 0
        while (i < s.length) {
            val c = s.codePointAt(i)
            foldFull(c, sb)
            i += Character.charCount(c)
        }
        return sb.toString()
    }

    /** str.title (do_title): titlecase after what is not cased, lowercase after what is. */
    @JvmStatic fun title(s: String): String {
        val sb = StringBuilder(s.length + 8)
        var prevCased = false
        var i = 0
        while (i < s.length) {
            val c = s.codePointAt(i)
            if (prevCased) lowerAt(s, i, c, sb) else titleFull(c, sb)
            prevCased = isCased(c)
            i += Character.charCount(c)
        }
        return sb.toString()
    }

    /** str.capitalize (do_capitalize): the first code point's titlecase, the rest lowercase. */
    @JvmStatic fun capitalize(s: String): String {
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length + 8)
        val c0 = s.codePointAt(0)
        titleFull(c0, sb)
        var i = Character.charCount(c0)
        while (i < s.length) {
            val c = s.codePointAt(i)
            lowerAt(s, i, c, sb)
            i += Character.charCount(c)
        }
        return sb.toString()
    }

    /** str.swapcase (do_swapcase): what is uppercase lowered (the final sigma too), what is lowercase uppered. */
    @JvmStatic fun swapcase(s: String): String {
        val sb = StringBuilder(s.length + 8)
        var i = 0
        while (i < s.length) {
            val c = s.codePointAt(i)
            val f = flags(c)
            when {
                f and UPPER != 0 -> lowerAt(s, i, c, sb)
                f and LOWER != 0 -> upperFull(c, sb)
                else -> sb.appendCodePoint(c)
            }
            i += Character.charCount(c)
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ str's predicates

    /** Whether [s] has code points and each has one of [mask]'s flags (isalpha, isalnum, isdecimal, isdigit,
     *  isnumeric, isspace). */
    @JvmStatic fun all(s: String, mask: Int): Boolean {
        if (s.isEmpty()) return false
        return each(s, mask)
    }

    /** Whether each of [s]'s code points has one of [mask]'s flags. */
    private fun each(s: String, mask: Int): Boolean {
        var i = 0
        val n = s.length
        while (i < n) {
            val ch = s[i].code
            if (ch < 0x80) {
                if (ASCII[ch] and mask == 0) return false
                i++
                continue
            }
            val c = s.codePointAt(i)
            if (flags(c) and mask == 0) return false
            i += Character.charCount(c)
        }
        return true
    }

    /** str.isprintable (true where it is empty). */
    @JvmStatic fun isPrintable(s: String): Boolean = each(s, PRINTABLE)

    /** str.islower: something lowercase, nothing uppercase or titlecase. */
    @JvmStatic fun isLower(s: String): Boolean {
        var cased = false
        var i = 0
        while (i < s.length) {
            val c = s.codePointAt(i)
            val f = flags(c)
            if (f and (UPPER or TITLE) != 0) return false
            if (f and LOWER != 0) cased = true
            i += Character.charCount(c)
        }
        return cased
    }

    /** str.isupper: something uppercase, nothing lowercase or titlecase. */
    @JvmStatic fun isUpper(s: String): Boolean {
        var cased = false
        var i = 0
        while (i < s.length) {
            val c = s.codePointAt(i)
            val f = flags(c)
            if (f and (LOWER or TITLE) != 0) return false
            if (f and UPPER != 0) cased = true
            i += Character.charCount(c)
        }
        return cased
    }

    /** str.istitle: uppercase and titlecase only after what is not cased, lowercase only after what is. */
    @JvmStatic fun isTitle(s: String): Boolean {
        var cased = false
        var prev = false
        var i = 0
        while (i < s.length) {
            val c = s.codePointAt(i)
            val f = flags(c)
            if (f and (UPPER or TITLE) != 0) {
                if (prev) return false
                prev = true
                cased = true
            } else if (f and LOWER != 0) {
                if (!prev) return false
                prev = true
                cased = true
            } else prev = false
            i += Character.charCount(c)
        }
        return cased
    }

    /** _PyUnicode_ScanIdentifier: the code point index of [s]'s first that may not be in an identifier there (its
     *  first XID_Start or '_', the rest XID_Continue), its length where none. */
    @JvmStatic fun scanIdentifier(s: String): Int {
        if (s.isEmpty()) return 0
        val c0 = s.codePointAt(0)
        if (c0 != '_'.code && !isXidStart(c0)) return 0
        var k = 1
        var i = Character.charCount(c0)
        while (i < s.length) {
            val c = s.codePointAt(i)
            if (!isXidContinue(c)) return k
            k++
            i += Character.charCount(c)
        }
        return k
    }

    /** str.isidentifier (PyUnicode_IsIdentifier). */
    @JvmStatic fun isIdentifier(s: String): Boolean {
        if (s.isEmpty()) return false
        var i = 0
        while (i < s.length) {
            val ch = s[i].code
            if (ch >= 0x80) return scanIdentifier(s) == s.codePointCount(0, s.length)
            if (ASCII[ch] and (if (i == 0) XID_START else XID_CONTINUE) == 0 && ch != '_'.code) return false
            i++
        }
        return true
    }

    // ------------------------------------------------------------------ numbers

    /**
     * _PyUnicode_TransformDecimalAndSpaceToASCII (what int(), float() and complex() read): [s] with each whitespace
     * code point past ASCII a space and each decimal digit its ASCII one, cut after the first other code point past
     * ASCII (or DEL), which becomes '?'.
     */
    @JvmStatic fun decimalAndSpaceToAscii(s: String): String {
        if (!nonAscii(s) && s.indexOf('\u007f') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s.codePointAt(i)
            i += Character.charCount(c)
            when {
                c < 127 -> sb.append(c.toChar())
                isSpace(c) -> sb.append(' ')
                else -> {
                    val d = toDecimal(c)
                    if (d < 0) return sb.append('?').toString()
                    sb.append('0' + d)
                }
            }
        }
        return sb.toString()
    }
}
