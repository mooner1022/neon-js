package io.neonjs.unicode

import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Unicode 17 character data used by the lexer and the regular expression engine: identifier classes,
 * General_Category / Script / Script_Extensions / binary properties, properties of strings and case folding.
 *
 * The data is generated into [UnicodeData] by `neonjs-core/tools/gen_unicode.py`; tables are decoded lazily on first
 * use and cached. All results are immutable and safe to share between threads.
 */
object UnicodeTables {
    const val VERSION = UnicodeData.VERSION

    // ------------------------------------------------------------------ decoding

    private const val HALF = 46

    private val digitValue = IntArray(128).also { t ->
        var d = 0
        for (c in 0x20 until 0x7F) {
            if (c == '"'.code || c == '$'.code || c == '\\'.code) continue
            t[c] = d++
        }
    }

    internal fun decodeInts(s: String): IntArray {
        var out = IntArray(maxOf(16, s.length / 2))
        var n = 0
        var v = 0
        for (ch in s) {
            val d = digitValue[ch.code]
            if (d >= HALF) {
                v = v * HALF + (d - HALF)
            } else {
                if (n == out.size) out = out.copyOf(out.size * 2)
                out[n++] = v * HALF + d
                v = 0
            }
        }
        return out.copyOf(n)
    }

    internal fun decodeSet(s: String): IntArray {
        val v = decodeInts(s)
        for (i in 1 until v.size) v[i] += v[i - 1] + 1
        return v
    }

    private class Table(names: Array<String>, private val data: Array<String>) {
        val index = HashMap<String, Int>()
        private val cache = AtomicReferenceArray<IntArray>(data.size)

        init {
            for ((i, n) in names.withIndex()) for (alias in n.split(',')) index[alias] = i
        }

        fun get(i: Int): IntArray {
            cache.get(i)?.let { return it }
            val s = decodeSet(data[i])
            cache.compareAndSet(i, null, s)
            return cache.get(i)
        }

        fun lookup(name: String): IntArray? = index[name]?.let { get(it) }
    }

    private val gc by lazy { Table(UnicodeData.GC_NAMES, UnicodeData.GC_DATA) }
    private val scripts by lazy { Table(UnicodeData.SCRIPT_NAMES, UnicodeData.SCRIPT_DATA) }
    private val scriptExtensions by lazy { Table(UnicodeData.SCRIPT_NAMES, UnicodeData.SCX_DATA) }
    private val binary by lazy { Table(UnicodeData.BINARY_NAMES, UnicodeData.BINARY_DATA) }

    // ------------------------------------------------------------------ identifiers / whitespace

    private val idStart: IntArray by lazy { binary.lookup("ID_Start")!! }
    private val idContinue: IntArray by lazy { binary.lookup("ID_Continue")!! }
    private val spaceSeparator: IntArray by lazy { gc.lookup("Zs")!! }

    /** Unicode ID_Start. */
    @JvmStatic
    fun isIdStart(cp: Int): Boolean = CharRanges.contains(idStart, cp)

    /** Unicode ID_Continue. */
    @JvmStatic
    fun isIdContinue(cp: Int): Boolean = CharRanges.contains(idContinue, cp)

    /** General_Category = Space_Separator (Zs). */
    @JvmStatic
    fun isSpaceSeparator(cp: Int): Boolean = CharRanges.contains(spaceSeparator, cp)

    // ------------------------------------------------------------------ properties (exact, case-sensitive names)

    /** General_Category value (short name, long name or alias), or null if unknown. */
    fun generalCategory(value: String): IntArray? = gc.lookup(value)

    /** Script or Script_Extensions value, or null if unknown. */
    fun script(value: String, extensions: Boolean): IntArray? = (if (extensions) scriptExtensions else scripts).lookup(value)

    /** One of the binary properties allowed by ECMA-262 (name or alias), or null. */
    fun binaryProperty(name: String): IntArray? = binary.lookup(name)

    /** A binary property of strings: the single code points and the multi-code-point sequences. */
    class StringProperty(@JvmField val chars: IntArray, @JvmField val strings: List<IntArray>)

    private val stringProps = AtomicReferenceArray<StringProperty>(UnicodeData.SEQ_NAMES.size + 1)

    private const val RGI_EMOJI = "RGI_Emoji"

    fun isStringPropertyName(name: String): Boolean = name == RGI_EMOJI || name in UnicodeData.SEQ_NAMES

    /** Properties of strings (`\p{RGI_Emoji}` etc., `v` flag only), or null if [name] is not one. */
    fun stringProperty(name: String): StringProperty? {
        val idx = if (name == RGI_EMOJI) UnicodeData.SEQ_NAMES.size else UnicodeData.SEQ_NAMES.indexOf(name)
        if (idx < 0) return null
        stringProps.get(idx)?.let { return it }
        val p = if (idx == UnicodeData.SEQ_NAMES.size) {
            var chars = CharRanges.EMPTY
            val strings = ArrayList<IntArray>()
            for (i in UnicodeData.SEQ_NAMES.indices) {
                val sp = stringProperty(UnicodeData.SEQ_NAMES[i])!!
                chars = CharRanges.union(chars, sp.chars)
                strings.addAll(sp.strings)
            }
            StringProperty(chars, strings)
        } else {
            val v = decodeInts(UnicodeData.SEQ_STRINGS[idx])
            val strings = ArrayList<IntArray>(v[0])
            var k = 1
            repeat(v[0]) {
                val len = v[k++]
                strings.add(v.copyOfRange(k, k + len))
                k += len
            }
            StringProperty(decodeSet(UnicodeData.SEQ_CHARS[idx]), strings)
        }
        stringProps.compareAndSet(idx, null, p)
        return stringProps.get(idx)
    }

    // ------------------------------------------------------------------ case folding

    /** Sorted mapping (keys/values) of code points whose canonical form differs from themselves. */
    class CaseMap(@JvmField val keys: IntArray, @JvmField val values: IntArray) {
        /** Direct lookup table for the BMP. */
        private val bmp: CharArray = CharArray(0x10000) { it.toChar() }
        /** True if some BMP code point maps outside the BMP (then [bmp] is not used). */
        private val bmpOverflow: Boolean

        init {
            var overflow = false
            for (i in keys.indices) {
                if (keys[i] >= 0x10000) continue
                if (values[i] >= 0x10000) overflow = true else bmp[keys[i]] = values[i].toChar()
            }
            bmpOverflow = overflow
        }

        fun map(c: Int): Int {
            if (c < 0x10000 && !bmpOverflow) return bmp[c].code
            val i = keys.binarySearch(c)
            return if (i >= 0) values[i] else c
        }
    }

    private fun decodeMap(s: String): CaseMap {
        val v = decodeInts(s)
        val n = v.size / 2
        val keys = IntArray(n)
        val values = IntArray(n)
        var prev = -1
        for (i in 0 until n) {
            val k = prev + 1 + v[2 * i]
            val z = v[2 * i + 1]
            val d = if (z and 1 == 0) z ushr 1 else -((z + 1) ushr 1)
            keys[i] = k
            values[i] = k + d
            prev = k
        }
        return CaseMap(keys, values)
    }

    /** Simple case folding (scf). */
    val simpleFolding: CaseMap by lazy { decodeMap(UnicodeData.SIMPLE_CASE_FOLDING) }

    /** Canonicalize for non-unicode case-insensitive matching (BMP only). */
    val nonUnicodeCanonical: CaseMap by lazy { decodeMap(UnicodeData.NON_UNICODE_CANONICALIZE) }

    /** ECMA-262 Canonicalize(rer, ch) for case-insensitive matching. */
    @JvmStatic
    fun canonicalize(c: Int, unicode: Boolean): Int {
        if (c < 128) {
            return if (unicode) {
                if (c in 'A'.code..'Z'.code) c + 32 else c
            } else {
                if (c in 'a'.code..'z'.code) c - 32 else c
            }
        }
        return if (unicode) simpleFolding.map(c) else if (c < 0x10000) nonUnicodeCanonical.map(c) else c
    }

    /** The set { Canonicalize(c) | c in set }. */
    fun canonicalizeSet(set: IntArray, unicode: Boolean): IntArray {
        if (set.isEmpty()) return set
        val map = if (unicode) simpleFolding else nonUnicodeCanonical
        val b = CharRanges.Builder()
        var changed = false
        val size = CharRanges.size(set)
        if (size <= map.keys.size) {
            // iterate over the members of the set
            var i = 0
            var runStart = -1
            var runEnd = -1
            while (i < set.size) {
                for (c in set[i] until set[i + 1]) {
                    val d = canonicalize(c, unicode)
                    if (d != c) changed = true
                    if (d == runEnd) runEnd++ else {
                        if (runStart >= 0) b.addRange(runStart, runEnd - 1)
                        runStart = d
                        runEnd = d + 1
                    }
                }
                i += 2
            }
            if (runStart >= 0) b.addRange(runStart, runEnd - 1)
            return if (changed) b.build() else set
        }
        // iterate over the mapping table: (set \ keys) U map(set & keys)
        val removed = CharRanges.Builder()
        for (k in map.keys.indices) {
            val c = map.keys[k]
            if (CharRanges.contains(set, c)) {
                removed.add(c)
                b.add(map.values[k])
            }
        }
        val rem = removed.build()
        if (rem.isEmpty()) return set
        return CharRanges.union(CharRanges.subtract(set, rem), b.build())
    }
}
