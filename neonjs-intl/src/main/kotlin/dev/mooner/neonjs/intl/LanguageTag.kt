package dev.mooner.neonjs.intl

import com.ibm.icu.util.ULocale
import java.util.concurrent.ConcurrentHashMap

/**
 * Unicode BCP 47 locale identifiers: structural validation (ECMA-402 IsStructurallyValidLanguageTag, i.e. the UTS 35
 * `unicode_locale_id` grammar without its backwards-compatibility forms) and canonicalization
 * (CanonicalizeUnicodeLocaleId, done by ICU's UTS 35 alias replacement plus the syntactic normalisation below).
 *
 * The only mutable state is a bounded, concurrent cache of canonicalized tags (immutable strings).
 */
internal object LanguageTag {
    /** Longest tag accepted (well above any real tag; keeps ICU from processing adversarially long input). */
    const val MAX_LENGTH = 512

    fun isAlpha(c: Char) = (c in 'a'..'z') || (c in 'A'..'Z')
    fun isDigit(c: Char) = c in '0'..'9'
    fun isAlnum(c: Char) = isAlpha(c) || isDigit(c)
    private fun allAlpha(s: String) = s.all { isAlpha(it) }
    private fun allDigit(s: String) = s.all { isDigit(it) }

    fun isLanguageSubtag(s: String) = (s.length in 2..3 || s.length in 5..8) && allAlpha(s)
    fun isScriptSubtag(s: String) = s.length == 4 && allAlpha(s)
    fun isRegionSubtag(s: String) = (s.length == 2 && allAlpha(s)) || (s.length == 3 && allDigit(s))
    fun isVariantSubtag(s: String) = (s.length in 5..8 && s.all { isAlnum(it) }) || (s.length == 4 && isDigit(s[0]) && s.all { isAlnum(it) })
    private fun isAlnumRange(s: String, min: Int, max: Int) = s.length in min..max && s.all { isAlnum(it) }

    /** A parsed (lowercase) locale identifier. Extensions keep their singleton, e.g. "u-ca-gregory". */
    class Parsed(
        val language: String,
        val script: String?,
        val region: String?,
        val variants: List<String>,
        val extensions: List<String>,
        val privateUse: String?,
    )

    /** Parses [tag] (any case) as a `unicode_locale_id`; null when it is not structurally valid. */
    fun parse(tag: String): Parsed? {
        if (tag.isEmpty() || tag.length > MAX_LENGTH) return null
        for (c in tag) if (!isAlnum(c) && c != '-') return null
        val st = tag.lowercase().split('-')
        for (s in st) if (s.isEmpty()) return null
        val n = st.size
        var i = 0
        if (!isLanguageSubtag(st[0])) return null
        val language = st[i++]
        var script: String? = null
        var region: String? = null
        if (i < n && isScriptSubtag(st[i])) script = st[i++]
        if (i < n && isRegionSubtag(st[i])) region = st[i++]
        val variants = ArrayList<String>()
        while (i < n && isVariantSubtag(st[i])) {
            if (st[i] in variants) return null
            variants.add(st[i++])
        }
        val extensions = ArrayList<String>()
        val seen = HashSet<Char>()
        var privateUse: String? = null
        while (i < n) {
            val s = st[i]
            if (s.length != 1) return null
            val singleton = s[0]
            if (singleton == 'x') {
                i++
                if (i >= n) return null
                val start = i
                while (i < n) {
                    if (!isAlnumRange(st[i], 1, 8)) return null
                    i++
                }
                privateUse = "x-" + st.subList(start, n).joinToString("-")
                break
            }
            if (!seen.add(singleton)) return null
            val start = i
            i++
            i = when (singleton) {
                'u' -> parseUnicodeExtension(st, i)
                't' -> parseTransformedExtension(st, i)
                else -> parseOtherExtension(st, i)
            }
            if (i < 0) return null
            extensions.add(st.subList(start, i).joinToString("-"))
        }
        return Parsed(language, script, region, variants, extensions, privateUse)
    }

    /** Returns the index after the extension, or -1. */
    private fun parseUnicodeExtension(st: List<String>, start: Int): Int {
        var i = start
        var count = 0
        while (i < st.size && isAlnumRange(st[i], 3, 8)) { i++; count++ } // attributes
        while (i < st.size && isUnicodeKey(st[i])) {
            i++
            count++
            while (i < st.size && isAlnumRange(st[i], 3, 8)) i++
        }
        return if (count == 0) -1 else i
    }

    fun isUnicodeKey(s: String) = s.length == 2 && isAlnum(s[0]) && isAlpha(s[1])

    private fun isTKey(s: String) = s.length == 2 && isAlpha(s[0]) && isDigit(s[1])

    private fun parseTransformedExtension(st: List<String>, start: Int): Int {
        var i = start
        var count = 0
        if (i < st.size && isLanguageSubtag(st[i])) {
            i++
            count++
            if (i < st.size && isScriptSubtag(st[i])) i++
            if (i < st.size && isRegionSubtag(st[i])) i++
            val vs = HashSet<String>()
            while (i < st.size && isVariantSubtag(st[i])) {
                if (!vs.add(st[i])) return -1
                i++
            }
        }
        while (i < st.size && isTKey(st[i])) {
            i++
            var values = 0
            while (i < st.size && isAlnumRange(st[i], 3, 8)) { i++; values++ }
            if (values == 0) return -1
            count++
        }
        return if (count == 0) -1 else i
    }

    private fun parseOtherExtension(st: List<String>, start: Int): Int {
        var i = start
        while (i < st.size && isAlnumRange(st[i], 2, 8)) i++
        return if (i == start) -1 else i
    }

    /** IsStructurallyValidLanguageTag */
    fun isStructurallyValid(tag: String): Boolean = parse(tag) != null

    // ------------------------------------------------------------------ canonicalization

    private const val CACHE_LIMIT = 4096
    private val cache = ConcurrentHashMap<String, String>()

    /**
     * CanonicalizeUnicodeLocaleId for a tag that [parse] accepted. Throws a RangeError for invalid tags so callers
     * can use it as "validate and canonicalize".
     */
    fun canonicalize(tag: String): String {
        cache[tag]?.let { return it }
        val p = parse(tag) ?: rangeErr("Incorrect locale information provided: $tag")
        val result = canonicalizeParsed(p)
        if (cache.size >= CACHE_LIMIT) cache.clear()
        cache[tag] = result
        return result
    }

    private fun canonicalizeParsed(p: Parsed): String {
        // ICU turns the variant "posix" into "-u-va-posix"; keep it as a variant
        val posix = "posix" in p.variants
        val input = build(p.language, p.script, p.region, if (posix) p.variants - "posix" else p.variants, p.extensions, p.privateUse)
        val icu = try {
            ULocale.createCanonical(ULocale.Builder().setLanguageTag(input).build()).toLanguageTag()
        } catch (_: Exception) {
            null
        }
        val q = (if (icu != null) parse(icu) else null) ?: p
        val variants = if (posix && "posix" !in q.variants) q.variants + "posix" else q.variants
        val extensions = restoreYesValues(p.extensions, q.extensions)
        return normalize(q.language, q.script, q.region, variants, extensions, q.privateUse)
    }

    /** Keys whose "yes" value is an alias of "true" (UTS 35); ICU drops "yes" for every key. */
    private val YES_IS_TRUE = setOf("kb", "kc", "kh", "kk", "kn")

    private fun restoreYesValues(original: List<String>, canonical: List<String>): List<String> {
        val ou = original.firstOrNull { it[0] == 'u' } ?: return canonical
        val yesKeys = unicodeComponents(ou.split('-'), 1).second.filter { it.second == "yes" && it.first !in YES_IS_TRUE }.map { it.first }
        if (yesKeys.isEmpty()) return canonical
        return canonical.map { ext ->
            if (ext[0] != 'u') ext
            else {
                val (attrs, kws) = unicodeComponents(ext.split('-'), 1)
                buildUnicodeExtension(attrs, kws.map { if (it.first in yesKeys && it.second.isEmpty()) it.first to "yes" else it })
            }
        }
    }

    /** Case regularization and canonical ordering (variants, extensions, u keywords and attributes, t fields). */
    private fun normalize(language: String, script: String?, region: String?, variants: List<String>, extensions: List<String>, privateUse: String?): String {
        val exts = extensions.map { normalizeExtension(it.lowercase()) }.filter { it.isNotEmpty() }.sortedBy { it[0] }
        return build(language.lowercase(), script, region, variants.map { it.lowercase() }.sorted(), exts, privateUse?.lowercase())
    }

    private fun normalizeExtension(ext: String): String {
        val st = ext.split('-')
        return when (ext[0]) {
            'u' -> {
                val (attrs, kws) = unicodeComponents(st, 1)
                buildUnicodeExtension(attrs, kws)
            }
            't' -> normalizeTransformed(st)
            else -> ext
        }
    }

    private fun normalizeTransformed(st: List<String>): String {
        var i = 1
        val tlang = ArrayList<String>()
        if (i < st.size && isLanguageSubtag(st[i])) {
            tlang.add(st[i++])
            if (i < st.size && isScriptSubtag(st[i])) tlang.add(st[i++])
            if (i < st.size && isRegionSubtag(st[i])) tlang.add(st[i++])
            val vs = ArrayList<String>()
            while (i < st.size && isVariantSubtag(st[i])) vs.add(st[i++])
            tlang.addAll(vs.sorted())
        }
        val fields = ArrayList<Pair<String, String>>()
        while (i < st.size) {
            val key = st[i++]
            val vals = ArrayList<String>()
            while (i < st.size && !isTKey(st[i])) vals.add(st[i++])
            fields.add(key to vals.joinToString("-"))
        }
        val sb = StringBuilder("t")
        for (s in tlang) sb.append('-').append(s)
        for ((k, v) in fields.sortedBy { it.first }) sb.append('-').append(k).append('-').append(v)
        return sb.toString()
    }

    fun build(language: String, script: String?, region: String?, variants: List<String>, extensions: List<String>, privateUse: String?): String {
        val sb = StringBuilder(language)
        if (script != null) sb.append('-').append(script[0].uppercaseChar()).append(script.substring(1).lowercase())
        if (region != null) sb.append('-').append(region.uppercase())
        for (v in variants) sb.append('-').append(v)
        for (e in extensions) sb.append('-').append(e)
        if (privateUse != null) sb.append('-').append(privateUse)
        return sb.toString()
    }

    // ------------------------------------------------------------------ Unicode extension helpers

    /** Attributes and (key, value) keywords of a "u-..." extension split into subtags, starting at [from]. */
    fun unicodeComponents(st: List<String>, from: Int): Pair<List<String>, List<Pair<String, String>>> {
        val attrs = ArrayList<String>()
        val kws = ArrayList<Pair<String, String>>()
        var i = from
        while (i < st.size && !isUnicodeKey(st[i])) {
            if (st[i] !in attrs) attrs.add(st[i])
            i++
        }
        while (i < st.size) {
            val key = st[i++]
            val vals = ArrayList<String>()
            while (i < st.size && !isUnicodeKey(st[i])) vals.add(st[i++])
            if (kws.none { it.first == key }) kws.add(key to vals.joinToString("-"))
        }
        return attrs to kws
    }

    /** Builds a canonical "u-..." extension ("" if empty); "true" values are dropped, keys and attributes sorted. */
    fun buildUnicodeExtension(attrs: List<String>, kws: List<Pair<String, String>>): String {
        if (attrs.isEmpty() && kws.isEmpty()) return ""
        val sb = StringBuilder("u")
        for (a in attrs.distinct().sorted()) sb.append('-').append(a)
        for ((k, v) in kws.sortedBy { it.first }) {
            sb.append('-').append(k)
            if (v.isNotEmpty() && v != "true") sb.append('-').append(v)
        }
        return sb.toString()
    }

    /** Splits a canonical tag into (baseName, extensions, privateUse). */
    fun split(tag: String): Parsed = parse(tag) ?: throw IllegalStateException("not a valid tag: $tag")

    /** The Unicode locale extension of a canonical tag as (attributes, keywords), or empty lists. */
    fun unicodeExtensionOf(tag: String): Pair<List<String>, List<Pair<String, String>>> {
        val p = parse(tag) ?: return emptyList<String>() to emptyList()
        val u = p.extensions.firstOrNull { it[0] == 'u' } ?: return emptyList<String>() to emptyList()
        return unicodeComponents(u.split('-'), 1)
    }

    /** The value of Unicode extension [key] in canonical [tag]: null if absent, "" for a key without a value. */
    fun unicodeKeyword(tag: String, key: String): String? = unicodeExtensionOf(tag).second.firstOrNull { it.first == key }?.second

    /** The locale without Unicode locale extension sequences. */
    fun removeUnicodeExtension(tag: String): String {
        val p = parse(tag) ?: return tag
        if (p.extensions.none { it[0] == 'u' }) return tag
        return build(p.language, p.script, p.region, p.variants, p.extensions.filter { it[0] != 'u' }, p.privateUse)
    }

    /** GetLocaleBaseName: language, script, region and variants. */
    fun baseName(tag: String): String {
        val p = parse(tag) ?: return tag
        return build(p.language, p.script, p.region, p.variants, emptyList(), null)
    }

    /** InsertUnicodeExtensionAndCanonicalize */
    fun insertUnicodeExtensionAndCanonicalize(locale: String, attrs: List<String>, kws: List<Pair<String, String>>): String {
        val p = parse(locale) ?: return locale
        val u = buildUnicodeExtension(attrs, kws)
        val others = p.extensions.filter { it[0] != 'u' }
        val exts = if (u.isEmpty()) others else (others + u).sortedBy { it[0] }
        return canonicalize(build(p.language, p.script, p.region, p.variants, exts, p.privateUse))
    }

    /** CanonicalizeUValue(key, value) for a lowercase value: the canonical value per UTS 35 ("" for "true"). */
    fun canonicalizeUValue(key: String, value: String): String {
        if (value.isEmpty() || value == "true") return ""
        val tag = "und-u-$key-$value"
        if (!isStructurallyValid(tag)) return value
        return unicodeKeyword(canonicalize(tag), key) ?: value
    }
}
