package dev.mooner.neonjs.intl

import com.ibm.icu.util.ULocale
import dev.mooner.neonjs.runtime.*

/** Locale negotiation of ECMA-402 §9.2: CanonicalizeLocaleList, ResolveLocale, SupportedLocales, DefaultLocale. */
internal object Locales {
    /** Maximum number of locales taken from a requested-locales list (keeps adversarial lists bounded). */
    const val MAX_REQUESTED = 1000

    /** Canonical tags of all locales ICU has data for (computed on first use; immutable afterwards). */
    val available: Set<String> by lazy { computeAvailable() }

    private fun computeAvailable(): Set<String> {
        val out = HashSet<String>()
        val all = ULocale.getAvailableLocalesByType(ULocale.AvailableType.WITH_LEGACY_ALIASES)
        for (l in all) {
            val tag = l.toLanguageTag()
            if (tag.contains("-u-") || tag.contains("-x-") || tag.contains("-t-")) continue
            if (!LanguageTag.isStructurallyValid(tag)) continue
            val c = try { LanguageTag.canonicalize(tag) } catch (_: Exception) { continue }
            out.add(c)
        }
        // language-region aliases of language-script-region locales whose script is the likely one (e.g. "zh-TW")
        for (t in out.toList()) {
            val p = LanguageTag.parse(t) ?: continue
            if (p.script == null || p.region == null || p.variants.isNotEmpty()) continue
            val lr = p.language + "-" + p.region.uppercase()
            if (lr in out) continue
            val max = ULocale.addLikelySubtags(ULocale.forLanguageTag(lr))
            if (max.script.equals(p.script, ignoreCase = true)) out.add(lr)
        }
        return out
    }

    // ------------------------------------------------------------------ DefaultLocale

    /** DefaultLocale(): the configured (or host) locale, canonicalized, without extensions, and available. */
    fun defaultLocale(realm: Realm): String {
        val st = IntlState.of(realm)
        st.defaultLocale?.let { return it }
        val configured = realm.agent.config.defaultLocale
        val raw = configured ?: java.util.Locale.getDefault().toLanguageTag()
        val tag = try {
            LanguageTag.baseName(LanguageTag.canonicalize(raw))
        } catch (_: Exception) {
            "en-US"
        }
        val resolved = bestAvailable(tag) ?: "en-US"
        st.defaultLocale = resolved
        return resolved
    }

    /** BestAvailableLocale over [available] by prefix truncation. */
    fun bestAvailable(locale: String): String? {
        var prefix = locale
        while (prefix.isNotEmpty()) {
            if (prefix in available) return prefix
            var pos = prefix.lastIndexOf('-')
            if (pos < 0) pos = 0
            while (pos >= 2 && prefix[pos - 2] == '-') pos -= 2
            prefix = prefix.substring(0, pos)
        }
        return null
    }

    // ------------------------------------------------------------------ CanonicalizeLocaleList

    fun canonicalizeLocaleList(realm: Realm, locales: Any?): List<String> {
        if (locales === Undefined) return emptyList()
        val seen = ArrayList<String>()
        val o = if (locales is CharSequence || locales is JSIntlLocale) jsArray(realm, listOf(locales)) else Ops.toObject(locales)
        val len = Ops.toLength(o.get("length", o))
        var k = 0L
        while (k < len) {
            realm.tick(k)
            val pk = PK.fromIndex(k)
            if (o.hasProperty(pk)) {
                val kValue = o.get(pk, o)
                if (kValue !is CharSequence && kValue !is JSObject) typeErr("Language ID should be string or object.")
                val tag = if (kValue is JSIntlLocale) kValue.locale else Ops.toString(kValue)
                val canonical = LanguageTag.canonicalize(tag)
                if (canonical !in seen) {
                    if (seen.size >= MAX_REQUESTED) rangeErr("Too many locales requested")
                    seen.add(canonical)
                }
            }
            k++
        }
        return seen
    }

    // ------------------------------------------------------------------ matching

    class Match(val locale: String, val extension: String?)

    /** LookupMatchingLocaleByPrefix (also used for best fit). */
    fun lookupMatch(requested: List<String>): Match? {
        for (locale in requested) {
            val p = LanguageTag.parse(locale) ?: continue
            val u = p.extensions.firstOrNull { it[0] == 'u' }
            val noExt = if (u == null) locale else LanguageTag.removeUnicodeExtension(locale)
            val found = bestAvailable(noExt)
            if (found != null) return Match(found, u)
        }
        return null
    }

    /** Result of ResolveLocale: the locale and the resolved value of each relevant key (null when none). */
    class Resolved(val locale: String, val dataLocale: String, val values: Map<String, String?>)

    /**
     * ResolveLocale. [keyData] gives, for the found (data) locale and a key, the supported values with the default
     * first (null for "no default"). [optionValues] are the option values for the keys (null when absent).
     */
    fun resolve(
        realm: Realm,
        requested: List<String>,
        relevantKeys: List<String>,
        optionValues: Map<String, String?>,
        keyData: (String, String) -> List<String?>,
    ): Resolved {
        val r = lookupMatch(requested) ?: Match(defaultLocale(realm), null)
        var foundLocale = r.locale
        val keywords = if (r.extension != null) LanguageTag.unicodeComponents(r.extension.split('-'), 1).second else emptyList()
        val supported = ArrayList<Pair<String, String>>()
        val values = HashMap<String, String?>()
        for (key in relevantKeys) {
            val keyLocaleData = keyData(foundLocale, key)
            var value = keyLocaleData.firstOrNull()
            var supportedKeyword: Pair<String, String>? = null
            val entry = keywords.firstOrNull { it.first == key }
            if (entry != null) {
                val requestedValue = entry.second
                if (requestedValue.isNotEmpty()) {
                    if (requestedValue in keyLocaleData) {
                        value = requestedValue
                        supportedKeyword = key to value
                    }
                } else if ("true" in keyLocaleData) {
                    value = "true"
                    supportedKeyword = key to ""
                }
            }
            var optionsValue = optionValues[key]
            if (optionsValue != null) {
                optionsValue = LanguageTag.canonicalizeUValue(key, optionsValue.lowercase())
                if (optionsValue.isEmpty()) optionsValue = "true"
            }
            if (optionsValue != null && optionsValue != value && optionsValue in keyLocaleData) {
                value = optionsValue
                supportedKeyword = null
            }
            if (supportedKeyword != null) supported.add(supportedKeyword)
            values[key] = value
        }
        val dataLocale = foundLocale
        if (supported.isNotEmpty()) foundLocale = LanguageTag.insertUnicodeExtensionAndCanonicalize(foundLocale, emptyList(), supported)
        return Resolved(foundLocale, dataLocale, values)
    }

    /** SupportedLocales(availableLocales, requestedLocales, options) */
    fun supportedLocales(realm: Realm, requested: List<String>, options: Any?): JSArray {
        val opts = Opt.coerceToObject(options)
        Opt.localeMatcher(opts)
        val out = ArrayList<Any?>()
        for (locale in requested) {
            if (lookupMatch(listOf(locale)) != null) out.add(locale)
        }
        return jsArray(realm, out)
    }

    /** Installs `supportedLocalesOf` on a service constructor. */
    fun installSupportedLocalesOf(realm: Realm, ctor: JSObject) {
        ctor.method(realm, "supportedLocalesOf", 1) { f, _, args, _ ->
            val requested = canonicalizeLocaleList(f.realm, args.arg(0))
            supportedLocales(f.realm, requested, args.arg(1))
        }
    }
}
