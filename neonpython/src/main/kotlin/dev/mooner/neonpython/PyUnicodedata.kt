package dev.mooner.neonpython

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/** A unicodedata.UCD: a database other than the module's own (ucd_3_2_0, Unicode 3.2.0, idna's and stringprep's). */
class PyUcd(t: PyType, @JvmField val version: String) : PyObject(t)

/**
 * `unicodedata` as CPython 3.14 has it (Modules/unicodedata.c): Unicode 16.0.0, and `ucd_3_2_0`, the 3.2.0 database
 * idna and stringprep read. Its data is CPython's own, not the JVM's or Android's (whose Unicode versions differ):
 * neonpython/tools/gen_unicodedata.py dumps what CPython 3.14.6 answers for every code point into unicodedata.bin,
 * read when first used (its names when a name is first asked for). Normalization is unicodedata.c's (its quick checks,
 * its decomposition, canonical ordering and composition, Hangul's algorithmic) over those tables; names are its names
 * (Hangul syllables' and CJK unified ideographs' algorithmic, Tangut ideographs found by lookup and not named, as in
 * 3.14), lookup takes Unicode's aliases and named sequences, and its messages are CPython's. [code] and [name] are its
 * C API (`\N{...}` and namereplace). `_ucnhash_CAPI` is an opaque PyCapsule.
 */
object PyUnicodedata {
    const val VERSION = "16.0.0"

    const val DOC = "This module provides access to the Unicode Character Database which\n" +
        "defines character properties for all Unicode characters. The data in\n" +
        "this database is based on the UnicodeData.txt file version\n" +
        "16.0.0 which is publicly available from ftp://ftp.unicode.org/.\n\n" +
        "The module uses the same names and symbols as defined by the\n" +
        "UnicodeData File Format 16.0.0."

    private const val S_BASE = 0xAC00
    private const val L_BASE = 0x1100
    private const val V_BASE = 0x1161
    private const val T_BASE = 0x11A7
    private const val L_COUNT = 19
    private const val V_COUNT = 21
    private const val T_COUNT = 28
    private const val N_COUNT = V_COUNT * T_COUNT
    private const val S_COUNT = L_COUNT * N_COUNT
    private const val MAX = 0x110000
    private const val NAME_MAXLEN = 256

    // a record's fields (gen_unicodedata.py's order): 16.0.0's, then 3.2.0's
    private const val F_CAT = 0
    private const val F_BIDI = 1
    private const val F_CCC = 2
    private const val F_EAW = 3
    private const val F_MIRRORED = 4
    private const val F_DECIMAL = 5
    private const val F_DIGIT = 6
    private const val F_NUMERIC = 7
    private const val F_QC = 8
    private const val F_DECOMP = 9
    private const val F_CAT32 = 10
    private const val F_BIDI32 = 11
    private const val F_EAW32 = 12
    private const val F_MIRRORED32 = 13
    private const val F_DECIMAL32 = 14
    private const val F_NUMERIC32 = 15
    private const val FIELDS = 16
    private const val SHIFT = 7

    // the quick check's answers (unicodedata.c's QuickcheckResult)
    private const val YES = 0
    private const val MAYBE = 1
    private const val NO = 2

    private val JAMO_L = arrayOf("G", "GG", "N", "D", "DD", "R", "M", "B", "BB", "S", "SS", "", "J", "JJ", "C", "K", "T", "P", "H")
    private val JAMO_V = arrayOf("A", "AE", "YA", "YAE", "EO", "E", "YEO", "YE", "O", "WA", "WAE", "OE", "YO", "U", "WEO", "WE", "WI", "YU", "EU", "YI", "I")
    private val JAMO_T = arrayOf("", "G", "GG", "GS", "N", "NJ", "NH", "D", "L", "LG", "LM", "LB", "LS", "LT", "LP", "LH", "M", "B", "BS", "S", "SS", "NG", "J", "C", "K", "T", "P", "H")
    private val PREFIXES = arrayOf("HANGUL SYLLABLE ", "CJK UNIFIED IDEOGRAPH-", "TANGUT IDEOGRAPH-")

    // ------------------------------------------------------------------ the data

    private class Reader(val b: ByteArray) {
        var p = 0

        fun u(): Int {
            var r = 0
            var shift = 0
            while (true) {
                val x = b[p++].toInt() and 0xFF
                r = r or ((x and 0x7F) shl shift)
                if (x and 0x80 == 0) return r
                shift += 7
            }
        }

        fun s(): String {
            val n = u()
            val s = String(b, p, n, Charsets.ISO_8859_1)
            p += n
            return s
        }

        fun d(): Double {
            var v = 0L
            repeat(8) { v = (v shl 8) or (b[p++].toLong() and 0xFF) }
            return java.lang.Double.longBitsToDouble(v)
        }
    }

    /** Section [i] of unicodedata.bin, inflated. */
    private fun section(i: Int): ByteArray {
        val raw = PyUnicodedata::class.java.getResourceAsStream("unicodedata.bin")!!.use { it.readBytes() }
        check(String(raw, 0, 4, Charsets.ISO_8859_1) == "NPUD" && raw[4].toInt() == 1) { "unicodedata.bin: not version 1" }
        var p = 5
        fun int32(at: Int) = ((raw[at].toInt() and 0xFF) shl 24) or ((raw[at + 1].toInt() and 0xFF) shl 16) or
            ((raw[at + 2].toInt() and 0xFF) shl 8) or (raw[at + 3].toInt() and 0xFF)
        repeat(i) { p += 4 + int32(p) }
        return GZIPInputStream(ByteArrayInputStream(raw, p + 4, int32(p))).use { it.readBytes() }
    }

    /** The properties: records of fields, which record each code point has (a two-level table), decompositions,
     *  composition pairs, 3.2.0's corrections, the derived names' ranges. */
    private class Db(b: ByteArray) {
        val cats: Array<String>
        val bidis: Array<String>
        val eaws: Array<String>
        val tags: Array<String>
        val nums: DoubleArray
        val rec: IntArray
        val index1: ShortArray
        val index2: ShortArray
        val decCodes: IntArray
        val decOff: IntArray
        val decData: IntArray
        val pairs = HashMap<Long, Int>()
        val firsts = java.util.BitSet(MAX)
        val corrections = HashMap<Int, Int>()
        val derived: IntArray
        val cn32: Int

        init {
            val r = Reader(b)
            fun strings() = Array(r.u()) { r.s() }
            cats = strings()
            bidis = strings()
            eaws = strings()
            tags = strings()
            nums = DoubleArray(r.u()) { r.d() }
            val nrec = r.u()
            rec = IntArray(nrec * FIELDS) { r.u() }
            // (the runs made a table of each code point's record, then cut into blocks, equal blocks kept once)
            val all = ShortArray(MAX)
            var c = 0
            repeat(r.u()) {
                val n = r.u()
                val idx = r.u().toShort()
                java.util.Arrays.fill(all, c, c + n, idx)
                c += n
            }
            check(c == MAX)
            val blockSize = 1 shl SHIFT
            index1 = ShortArray(MAX shr SHIFT)
            val seen = HashMap<List<Short>, Int>()
            val blocks = ArrayList<ShortArray>()
            for (bi in index1.indices) {
                val blk = all.copyOfRange(bi shl SHIFT, (bi + 1) shl SHIFT)
                index1[bi] = seen.getOrPut(blk.asList()) { blocks.add(blk); blocks.size - 1 }.toShort()
            }
            index2 = ShortArray(blocks.size * blockSize)
            for ((i, blk) in blocks.withIndex()) blk.copyInto(index2, i * blockSize)
            val ndec = r.u()
            decCodes = IntArray(ndec)
            decOff = IntArray(ndec + 1)
            val data = ArrayList<Int>()
            var last = 0
            for (i in 0 until ndec) {
                last += r.u()
                decCodes[i] = last
                decOff[i] = data.size
                data.add(r.u())
                val n = r.u()
                data.add(n)
                repeat(n) { data.add(r.u()) }
            }
            decOff[ndec] = data.size
            decData = data.toIntArray()
            repeat(r.u()) {
                val a = r.u()
                val x = r.u()
                pairs[(a.toLong() shl 21) or x.toLong()] = r.u()
                firsts.set(a)
            }
            repeat(r.u()) { corrections[r.u()] = r.u() }
            derived = IntArray(r.u() * 3) { r.u() }
            cn32 = cats.indexOf("Cn")
        }

        fun field(c: Int, f: Int): Int {
            val blk = index1[c ushr SHIFT].toInt() and 0xFFFF
            val ri = index2[(blk shl SHIFT) + (c and ((1 shl SHIFT) - 1))].toInt() and 0xFFFF
            return rec[ri * FIELDS + f]
        }

        fun ccc(c: Int) = field(c, F_CCC)

        /** Whether [c] is unassigned in 3.2.0 (its change record's category_changed is 0). */
        fun unassigned32(c: Int) = field(c, F_CAT32) == cn32

        /** The index of [c]'s decomposition, or -1. */
        fun decomp(c: Int): Int = if (field(c, F_DECOMP) == 0) -1 else java.util.Arrays.binarySearch(decCodes, c).let { if (it < 0) -1 else it }
    }

    /** The names: each code point's (ascending), then the aliases', then the named sequences'. */
    private class Names(b: ByteArray) {
        val aStart: Int
        val aEnd: Int
        val sStart: Int
        val sEnd: Int
        val count: Int
        val codes: IntArray
        val blob: ByteArray
        val starts: IntArray
        /** What lookup finds by each name: its code point, its alias's or named sequence's private-use one. */
        val values: IntArray
        val aliasTargets: IntArray
        val sequences: Array<IntArray>

        init {
            val r = Reader(b)
            aStart = r.u()
            aEnd = r.u()
            sStart = r.u()
            sEnd = r.u()
            count = r.u()
            val out = java.io.ByteArrayOutputStream(1 shl 20)
            val st = ArrayList<Int>()
            val vals = ArrayList<Int>()
            codes = IntArray(count)
            var last = 0
            fun name() {
                val n = r.u()
                st.add(out.size())
                out.write(r.b, r.p, n)
                r.p += n
            }
            for (i in 0 until count) {
                last += r.u()
                codes[i] = last
                name()
                vals.add(last)
            }
            val na = r.u()
            aliasTargets = IntArray(na)
            for (i in 0 until na) {
                name()
                aliasTargets[i] = r.u()
                vals.add(aStart + i)
            }
            val ns = r.u()
            sequences = Array(ns) { i ->
                name()
                vals.add(sStart + i)
                IntArray(r.u()) { r.u() }
            }
            st.add(out.size())
            blob = out.toByteArray()
            starts = st.toIntArray()
            values = vals.toIntArray()
        }

        fun nameAt(i: Int) = String(blob, starts[i], starts[i + 1] - starts[i], Charsets.ISO_8859_1)

        /** The entries in the order of their names (bytes), for lookup. */
        val byName: IntArray by lazy {
            values.indices.sortedWith { x, y -> compareAt(x, y) }.toIntArray()
        }

        private fun compareAt(x: Int, y: Int): Int {
            val a0 = starts[x]
            val a1 = starts[x + 1]
            val b0 = starts[y]
            val b1 = starts[y + 1]
            var i = 0
            while (a0 + i < a1 && b0 + i < b1) {
                val d = (blob[a0 + i].toInt() and 0xFF) - (blob[b0 + i].toInt() and 0xFF)
                if (d != 0) return d
                i++
            }
            return (a1 - a0) - (b1 - b0)
        }

        /** The value of the entry named [name] (its bytes, as Latin-1 chars), or -1. */
        fun find(name: String): Int {
            val order = byName
            var lo = 0
            var hi = order.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val e = order[mid]
                val s0 = starts[e]
                val len = starts[e + 1] - s0
                var d = 0
                var i = 0
                while (i < len && i < name.length) {
                    d = (blob[s0 + i].toInt() and 0xFF) - name[i].code
                    if (d != 0) break
                    i++
                }
                if (d == 0) d = len - name.length
                if (d == 0) return values[e]
                if (d < 0) lo = mid + 1 else hi = mid - 1
            }
            return -1
        }
    }

    private val db: Db by lazy { Db(section(0)) }
    private val names: Names by lazy { Names(section(1)) }

    // ------------------------------------------------------------------ names (the C API's too)

    private fun prefixId(c: Int): Int {
        val d = db.derived
        var i = 0
        while (i < d.size) {
            if (c < d[i]) return -1
            if (c <= d[i + 1]) return d[i + 2]
            i += 3
        }
        return -1
    }

    private fun hex4(c: Int) = Integer.toHexString(c).uppercase().padStart(4, '0')

    /**
     * _getucname: the name of [c], in 3.2.0's database where [old]; the names of the private-use code points the
     * aliases and named sequences have where [aliasesAndSequences] (namereplace's), or null.
     */
    @JvmStatic
    fun name(c: Int, old: Boolean, aliasesAndSequences: Boolean): String? {
        if (c < 0 || c >= MAX) return null
        val n = names
        val alias = c >= n.aStart && c < n.aEnd
        val seq = c >= n.sStart && c < n.sEnd
        if (!aliasesAndSequences && (alias || seq)) return null
        if (old && (alias || seq || db.unassigned32(c))) return null
        when (prefixId(c)) {
            0 -> {
                val k = c - S_BASE
                return "HANGUL SYLLABLE " + JAMO_L[k / N_COUNT] + JAMO_V[(k % N_COUNT) / T_COUNT] + JAMO_T[k % T_COUNT]
            }
            1 -> return PREFIXES[1] + hex4(c)
        }
        if (alias) return n.nameAt(n.count + (c - n.aStart))
        if (seq) return n.nameAt(n.count + n.aliasTargets.size + (c - n.sStart))
        val i = java.util.Arrays.binarySearch(n.codes, c)
        return if (i < 0) null else n.nameAt(i)
    }

    private fun lowerAscii(c: Char) = if (c in 'A'..'Z') c + 32 else c

    /** PyOS_strnicmp(name + at, s, len(s)) == 0: [s] at [at] of [name], ASCII letters' case aside. */
    private fun matchesAt(name: String, at: Int, s: String): Boolean {
        if (at + s.length > name.length) return false
        for (i in s.indices) if (lowerAscii(name[at + i]) != lowerAscii(s[i])) return false
        return true
    }

    /** find_syllable: the longest of [column]'s first [count] at [at] of [name] (case aside): (its length, index). */
    private fun syllable(name: String, at: Int, column: Array<String>, count: Int): Long {
        var len = -1
        var pos = -1
        for (i in 0 until count) {
            val s = column[i]
            if (s.length <= len) continue
            if (matchesAt(name, at, s)) {
                len = s.length
                pos = i
            }
        }
        if (len == -1) len = 0
        return (len.toLong() shl 32) or (pos.toLong() and 0xFFFFFFFFL)
    }

    /** parse_hex_code: 4 to 6 hex digits, the first not 0, at most 0x10FFFF; -1 for anything else. */
    private fun hexCode(s: String, from: Int): Int {
        val n = s.length - from
        if (n < 4 || n > 6 || s[from] == '0') return -1
        var v = 0
        for (i in from until s.length) {
            val c = s[i]
            v = v * 16 + when (c) {
                in '0'..'9' -> c - '0'
                in 'A'..'F' -> c - 'A' + 10
                in 'a'..'f' -> c - 'a' + 10
                else -> return -1
            }
        }
        return if (v > 0x10FFFF) -1 else v
    }

    /**
     * _getcode: the code point named [name] (its bytes as Latin-1 chars), an alias's or a named sequence's
     * private-use one for theirs; -1 for none. Case is aside (ASCII's).
     */
    private fun getcode(name: String): Int {
        var i = 0
        while (i < PREFIXES.size && !matchesAt(name, 0, PREFIXES[i])) i++
        if (i == 0) {
            var pos = PREFIXES[0].length
            val l = syllable(name, pos, JAMO_L, L_COUNT)
            pos += (l ushr 32).toInt()
            val v = syllable(name, pos, JAMO_V, V_COUNT)
            pos += (v ushr 32).toInt()
            val t = syllable(name, pos, JAMO_T, T_COUNT)
            pos += (t ushr 32).toInt()
            val li = l.toInt()
            val vi = v.toInt()
            val ti = t.toInt()
            if (li != -1 && vi != -1 && ti != -1 && pos == name.length) return S_BASE + (li * V_COUNT + vi) * T_COUNT + ti
            return -1
        }
        if (i < PREFIXES.size) {
            val v = hexCode(name, PREFIXES[i].length)
            return if (v >= 0 && prefixId(v) == i) v else -1
        }
        val sb = StringBuilder(name.length)
        for (c in name) sb.append(if (c in 'a'..'z') c - 32 else c)
        return names.find(sb.toString())
    }

    /** capi_getcode: the code point named [name] (`\N{...}`: aliases taken, named sequences not), or -1. */
    @JvmStatic
    fun code(name: String): Int {
        val c = getcode(name)
        if (c < 0) return -1
        val n = names
        if (c >= n.sStart && c < n.sEnd) return -1
        if (c >= n.aStart && c < n.aEnd) return n.aliasTargets[c - n.aStart]
        return c
    }

    private fun str(c: Int) = PyStr(String(Character.toChars(c)))

    private fun lookup(ctx: PyContext, old: Boolean, arg: Any?): Any? {
        val bytes = when (arg) {
            is PyStr -> PyCodecs.encodeUtf8(ctx, arg, null)
            is PyBytes -> arg.b
            is PyNumSub -> (arg.num as? PyBytes)?.b ?: throw PyRt.typeError("a bytes-like object is required, not '${PyTypes.name(arg)}'")
            is PyByteArray, is PyMemoryView -> throw PyRt.typeError("lookup() argument must be read-only bytes-like object, not ${PyTypes.name(arg)}")
            else -> throw PyRt.typeError("a bytes-like object is required, not '${PyTypes.name(arg)}'")
        }
        if (bytes.size > NAME_MAXLEN) throw PyException.keyError(PyStr("name too long"))
        fun undefined(): PyException {
            // (the name as %s gives it: up to a NUL, decoded as UTF-8)
            var end = bytes.indexOf(0)
            if (end < 0) end = bytes.size
            return PyException.keyError(PyStr("undefined character name '${String(bytes, 0, end, Charsets.UTF_8)}'"))
        }
        val code = getcode(String(bytes, Charsets.ISO_8859_1))
        if (code < 0) throw undefined()
        val n = names
        val alias = code >= n.aStart && code < n.aEnd
        val seq = code >= n.sStart && code < n.sEnd
        if (old && (alias || seq)) throw undefined()
        if (seq) {
            val sb = StringBuilder()
            for (x in n.sequences[code - n.sStart]) sb.appendCodePoint(x)
            return PyStr(sb.toString())
        }
        return str(if (alias) n.aliasTargets[code - n.aStart] else code)
    }

    // ------------------------------------------------------------------ properties

    private fun decomposition(old: Boolean, c: Int): String {
        if (old && db.unassigned32(c)) return ""
        if (c >= S_BASE && c < S_BASE + S_COUNT) {
            val k = c - S_BASE
            val l = L_BASE + k / N_COUNT
            val v = V_BASE + (k % N_COUNT) / T_COUNT
            val t = T_BASE + k % T_COUNT
            return if (t != T_BASE) "${hex4(l)} ${hex4(v)} ${hex4(t)}" else "${hex4(l)} ${hex4(v)}"
        }
        val d = db
        val i = d.decomp(c)
        if (i < 0) return ""
        val off = d.decOff[i]
        val sb = StringBuilder(d.tags[d.decData[off]])
        for (j in 0 until d.decData[off + 1]) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(hex4(d.decData[off + 2 + j]))
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ normalization

    private class Ints(n: Int) {
        var a = IntArray(maxOf(n, 8))
        var size = 0

        fun add(x: Int) {
            if (size == a.size) a = a.copyOf(size * 2)
            a[size++] = x
        }
    }

    /** nfd_nfkd: [input] decomposed (compatibility decompositions too where [k]), each run of marks in order. */
    private fun nfd(old: Boolean, input: IntArray, k: Boolean): IntArray {
        val d = db
        val out = Ints(input.size + 10)
        val stack = Ints(32)
        for (c0 in input) {
            stack.add(c0)
            while (stack.size > 0) {
                val code = stack.a[--stack.size]
                if (code >= S_BASE && code < S_BASE + S_COUNT) {
                    val si = code - S_BASE
                    out.add(L_BASE + si / N_COUNT)
                    out.add(V_BASE + (si % N_COUNT) / T_COUNT)
                    val t = T_BASE + si % T_COUNT
                    if (t != T_BASE) out.add(t)
                    continue
                }
                if (old) {
                    val v = d.corrections[code]
                    if (v != null) {
                        stack.add(v)
                        continue
                    }
                }
                val i = if (old && d.unassigned32(code)) -1 else d.decomp(code)
                val off = if (i < 0) -1 else d.decOff[i]
                if (i < 0 || (d.decData[off] != 0 && !k)) {
                    out.add(code)
                    continue
                }
                var n = d.decData[off + 1]
                while (n > 0) stack.add(d.decData[off + 2 + --n])
            }
        }
        // (each run of marks sorted by combining class, stably)
        val a = out.a
        val o = out.size
        var i = 0
        while (i < o) {
            var prev = d.ccc(a[i])
            if (prev == 0) {
                i++
                continue
            }
            val start = i++
            var needsSort = false
            while (i < o) {
                val cur = d.ccc(a[i])
                if (cur == 0) break
                if (prev > cur) needsSort = true
                prev = cur
                i++
            }
            if (!needsSort) continue
            for (j in start + 1 until i) {
                val code = a[j]
                val comb = d.ccc(code)
                var m = j
                while (m > start && d.ccc(a[m - 1]) > comb) {
                    a[m] = a[m - 1]
                    m--
                }
                a[m] = code
            }
        }
        return a.copyOf(o)
    }

    /** nfc_nfkc: [input] decomposed, then composed again (the composition pairs, Hangul's algorithmic). */
    private fun nfc(old: Boolean, input: IntArray, k: Boolean): IntArray {
        val d = db
        val data = nfd(old, input, k)
        val len = data.size
        val output = IntArray(len)
        val skipped = Ints(8)
        var i = 0
        var o = 0
        again@ while (i < len) {
            for (s in 0 until skipped.size) {
                if (skipped.a[s] == i) {
                    skipped.a[s] = skipped.a[--skipped.size]
                    i++
                    continue@again
                }
            }
            var code = data[i]
            if (code >= L_BASE && code < L_BASE + L_COUNT && i + 1 < len && data[i + 1] >= V_BASE && data[i + 1] < V_BASE + V_COUNT) {
                code = S_BASE + ((code - L_BASE) * V_COUNT + (data[i + 1] - V_BASE)) * T_COUNT
                i += 2
                if (i < len && T_BASE < data[i] && data[i] < T_BASE + T_COUNT) {
                    code += data[i] - T_BASE
                    i++
                }
                output[o++] = code
                continue
            }
            if (!d.firsts.get(code)) {
                output[o++] = code
                i++
                continue
            }
            var i1 = i + 1
            var comb = 0
            output[o] = code
            while (i1 < len) {
                val code1 = data[i1]
                val comb1 = d.ccc(code1)
                if (comb != 0) {
                    if (comb1 == 0) break
                    if (comb >= comb1) {
                        i1++
                        continue
                    }
                }
                val composed = d.pairs[(output[o].toLong() shl 21) or code1.toLong()]
                if (composed == null) {
                    if (comb1 == 0) break
                    comb = comb1
                    i1++
                    continue
                }
                output[o] = composed
                skipped.add(i1)
                i1++
                if (!d.firsts.get(composed)) break
            }
            o++
            i++
        }
        return if (o == len) data else output.copyOf(o)
    }

    /** is_normalized_quickcheck: YES, MAYBE or NO for [cps] in the form (nfc, k); MAYBE at once where not YES if [yesOnly]. */
    private fun quickcheck(old: Boolean, cps: IntArray, nfc: Boolean, k: Boolean, yesOnly: Boolean): Int {
        if (old) return MAYBE
        if (cps.all { it < 0x80 }) return YES
        val d = db
        val shift = (if (nfc) 4 else 0) + (if (k) 2 else 0)
        var result = YES
        var prev = 0
        for (c in cps) {
            val comb = d.ccc(c)
            if (comb != 0 && prev > comb) return NO
            prev = comb
            val qc = d.field(c, F_QC)
            if (yesOnly) {
                if (qc and (3 shl shift) != 0) return MAYBE
            } else {
                when ((qc shr shift) and 3) {
                    NO -> return NO
                    MAYBE -> result = MAYBE
                }
            }
        }
        return result
    }

    private fun text(cps: IntArray): PyStr {
        val sb = StringBuilder(cps.size)
        for (c in cps) sb.appendCodePoint(c)
        return PyStr(sb.toString())
    }

    /** PyUnicode_FromObject: [s] itself, or a str of its value for a subclass's instance. */
    private fun exact(s: PyStr): PyStr = if (s.sub == null) s else PyStr(s.s)

    private fun form(f: PyStr): Int = when (f.s) {
        "NFD" -> 0
        "NFKD" -> 1
        "NFC" -> 2
        "NFKC" -> 3
        else -> -1
    }

    /** unicodedata.normalize: [s] in normal form [f] (as the [old] database has it). */
    @JvmStatic
    fun normalize(old: Boolean, f: PyStr, s: PyStr): PyStr {
        if (s.s.isEmpty()) return exact(s)
        val form = form(f)
        if (form < 0) throw PyException("ValueError", "invalid normalization form")
        val nfc = form >= 2
        val k = form and 1 == 1
        val cps = s.s.codePoints().toArray()
        if (quickcheck(old, cps, nfc, k, true) == YES) return exact(s)
        return text(if (nfc) nfc(old, cps, k) else nfd(old, cps, k))
    }

    private fun isNormalized(old: Boolean, f: PyStr, s: PyStr): Boolean {
        if (s.s.isEmpty()) return true
        val form = form(f)
        if (form < 0) throw PyException("ValueError", "invalid normalization form")
        val nfc = form >= 2
        val k = form and 1 == 1
        val cps = s.s.codePoints().toArray()
        return when (quickcheck(old, cps, nfc, k, false)) {
            MAYBE -> (if (nfc) nfc(old, cps, k) else nfd(old, cps, k)).contentEquals(cps)
            YES -> true
            else -> false
        }
    }

    // ------------------------------------------------------------------ the module

    @JvmField val UCD: PyType = PyTypes.newBuiltin("UCD", subclassable = false).also { t ->
        t.module = "unicodedata"
        t.tpName = "unicodedata.UCD"
        t.dispatchBuiltins = true
        t.hasDict = false
        t.newImpl = { _, _, _, _ -> throw PyRt.typeError("cannot create 'unicodedata.UCD' instances") }
    }

    @JvmField val CAPSULE: PyType = PyTypes.newBuiltin("PyCapsule", subclassable = false).also { t ->
        t.tpName = "PyCapsule"
        t.dispatchBuiltins = true
        t.hasDict = false
        t.newImpl = { _, _, _, _ -> throw PyRt.typeError("cannot create 'PyCapsule' instances") }
        t.def("__repr__", PyBuiltin("__repr__") { _, a ->
            PyStr("<capsule object \"unicodedata._ucnhash_CAPI\" at 0x${Integer.toHexString(System.identityHashCode(a[0])).padStart(16, '0').uppercase()}>")
        })
    }

    /** The functions' argument parsing and bodies: each the module's (16.0.0) and UCD's (its version). */
    private class Fn(val name: String, val o: Boolean, val min: Int, val max: Int, val body: (PyContext, Boolean, Array<Any?>) -> Any?)

    /** A unicode character argument: its code point (CPython's messages, `argument` for METH_O's, `argument 1` else). */
    private fun chr(fn: String, v: Any?, o: Boolean): Int {
        val arg = if (o) "argument" else "argument 1"
        val s = v as? PyStr ?: throw PyRt.typeError("$fn() $arg must be a unicode character, not ${PyTypes.name(v)}")
        val n = s.length
        if (n != 1) throw PyRt.typeError("$fn(): $arg must be a unicode character, not a string of length $n")
        return s.s.codePointAt(0)
    }

    private fun strArg(fn: String, v: Any?, i: Int): PyStr =
        v as? PyStr ?: throw PyRt.typeError("$fn() argument $i must be str, not ${PyTypes.name(v)}")

    private val FUNCTIONS: List<Fn> = listOf(
        Fn("decimal", false, 1, 2) { _, old, a ->
            val c = chr("decimal", a[0], false)
            val v = db.field(c, if (old) F_DECIMAL32 else F_DECIMAL) - 1
            if (v < 0) a.getOrNull(1) ?: throw PyException("ValueError", "not a decimal") else v.toLong()
        },
        Fn("digit", false, 1, 2) { _, _, a ->
            val v = db.field(chr("digit", a[0], false), F_DIGIT) - 1
            if (v < 0) a.getOrNull(1) ?: throw PyException("ValueError", "not a digit") else v.toLong()
        },
        Fn("numeric", false, 1, 2) { _, old, a ->
            val i = db.field(chr("numeric", a[0], false), if (old) F_NUMERIC32 else F_NUMERIC)
            if (i == 0) a.getOrNull(1) ?: throw PyException("ValueError", "not a numeric character") else db.nums[i - 1]
        },
        Fn("category", true, 1, 1) { _, old, a -> PyStr(db.cats[db.field(chr("category", a[0], true), if (old) F_CAT32 else F_CAT)]) },
        Fn("bidirectional", true, 1, 1) { _, old, a -> PyStr(db.bidis[db.field(chr("bidirectional", a[0], true), if (old) F_BIDI32 else F_BIDI)]) },
        Fn("combining", true, 1, 1) { _, old, a ->
            val c = chr("combining", a[0], true)
            if (old && db.unassigned32(c)) 0L else db.ccc(c).toLong()
        },
        Fn("mirrored", true, 1, 1) { _, old, a -> db.field(chr("mirrored", a[0], true), if (old) F_MIRRORED32 else F_MIRRORED).toLong() },
        Fn("east_asian_width", true, 1, 1) { _, old, a -> PyStr(db.eaws[db.field(chr("east_asian_width", a[0], true), if (old) F_EAW32 else F_EAW)]) },
        Fn("decomposition", true, 1, 1) { _, old, a -> PyStr(decomposition(old, chr("decomposition", a[0], true))) },
        Fn("name", false, 1, 2) { _, old, a ->
            val n = name(chr("name", a[0], false), old, false)
            if (n == null) a.getOrNull(1) ?: throw PyException("ValueError", "no such name") else PyStr(n)
        },
        Fn("lookup", true, 1, 1) { ctx, old, a -> lookup(ctx, old, a[0]) },
        Fn("is_normalized", false, 2, 2) { _, old, a ->
            PyBool.of(isNormalized(old, strArg("is_normalized", a[0], 1), strArg("is_normalized", a[1], 2)))
        },
        Fn("normalize", false, 2, 2) { _, old, a -> normalize(old, strArg("normalize", a[0], 1), strArg("normalize", a[1], 2)) },
    )

    /** [fn] as a builtin: [owner] its messages' name for it ("unicodedata.category" or "UCD.category"). */
    private fun builtin(fn: Fn, owner: String, method: Boolean): PyBuiltin {
        fun call(ctx: PyContext, a0: Array<Any?>): Any? {
            val old = method && (a0[0] as PyUcd).version != VERSION
            val a = if (method) a0.copyOfRange(1, a0.size) else a0
            if (fn.o) {
                if (a.size != 1) throw PyRt.typeError("$owner() takes exactly one argument (${a.size} given)")
            } else {
                PyMath.checkPositional(fn.name, a.size, fn.min, fn.max)
            }
            return fn.body(ctx, old, a)
        }
        return PyBuiltin(fn.name, { ctx, a -> call(ctx, a) }, { ctx, a, kw ->
            if (kw.map.isNotEmpty()) throw PyRt.typeError("$owner() takes no keyword arguments")
            call(ctx, a)
        })
    }

    init {
        val t = UCD
        for (fn in FUNCTIONS) t.def(fn.name, builtin(fn, "UCD.${fn.name}", true))
        t.def("unidata_version", PyAttrDescr(PyAttrDescrs.MEMBER, t, "unidata_version", true).also { d ->
            d.getter = { o -> PyStr((o as PyUcd).version) }
        })
        t.def("__doc__", PyNone)
        t.def("__module__", PyStr("unicodedata"))
    }

    @JvmStatic
    fun fill(d: PyDict) {
        for (fn in FUNCTIONS) d[PyStr(fn.name)] = builtin(fn, "unicodedata.${fn.name}", false)
        d[PyStr("unidata_version")] = PyStr(VERSION)
        d[PyStr("UCD")] = UCD
        d[PyStr("ucd_3_2_0")] = PyUcd(UCD, "3.2.0")
        d[PyStr("_ucnhash_CAPI")] = PyObject(CAPSULE)
    }
}
