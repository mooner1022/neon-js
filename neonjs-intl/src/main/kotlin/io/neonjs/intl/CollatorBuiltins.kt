package io.neonjs.intl

import com.ibm.icu.lang.UCharacter
import com.ibm.icu.text.Collator
import com.ibm.icu.text.RuleBasedCollator
import com.ibm.icu.util.ULocale
import io.neonjs.runtime.*

/** Intl.Collator instance (`[[InitializedCollator]]`). */
class JSIntlCollator internal constructor(proto: JSObject?) : JSObject(proto) {
    @JvmField var locale: String = ""
    @JvmField internal var dataLocale: String = ""
    @JvmField var usage: String = "sort"
    @JvmField var sensitivity: String = "variant"
    @JvmField var ignorePunctuation: Boolean = false
    @JvmField var collation: String = "default"
    @JvmField var numeric: Boolean = false
    @JvmField var caseFirst: String = "false"
    @JvmField var boundCompare: JSObject? = null
    /** The frozen (hence thread-safe) ICU collator, created on first comparison. */
    @JvmField internal var icu: Collator? = null
}

/** Intl.Collator (ECMA-402 §10) and the Collator-based String.prototype methods. */
internal object CollatorBuiltins {
    private val USAGES = arrayOf("sort", "search")
    private val CASE_FIRST = arrayOf("upper", "lower", "false")
    private val SENSITIVITIES = arrayOf("base", "accent", "case", "variant")
    private val KEYS = listOf("co", "kf", "kn")

    fun install(realm: Realm, intl: JSObject) {
        val proto = JSObject(realm.objectPrototype)
        realm.intrinsics["%Intl.Collator.prototype%"] = proto
        val ctor = makeCtor(realm, "Collator", 0, proto) { f, _, args, nt ->
            create(f.realm, nt ?: f, args.arg(0), args.arg(1))
        }
        realm.intrinsics["%Intl.Collator%"] = ctor
        intl.defineOwn("Collator", ctor, Attr.WC)
        Locales.installSupportedLocalesOf(realm, ctor)
        proto.defineOwn(JSSymbol.toStringTag, "Intl.Collator", Attr.CONFIGURABLE)
        proto.getter(realm, "compare") { f, t, _, _ ->
            val c = thisCollator(t, "compare")
            c.boundCompare ?: NativeFunction(f.realm, "", 2, { _, _, a, _ ->
                compareStrings(c, Ops.toString(a.arg(0)), Ops.toString(a.arg(1)))
            }).also { c.boundCompare = it }
        }
        proto.method(realm, "resolvedOptions", 0) { f, t, _, _ -> resolvedOptions(f.realm, thisCollator(t, "resolvedOptions")) }
        installStringMethods(realm)
    }

    private fun thisCollator(t: Any?, method: String): JSIntlCollator =
        t as? JSIntlCollator ?: typeErr("Method Intl.Collator.prototype.$method called on incompatible receiver ${Ops.describe(t)}")

    private fun resolvedOptions(realm: Realm, c: JSIntlCollator): JSObject {
        val o = plainObject(realm)
        o.createDataPropertyOrThrow("locale", c.locale)
        o.createDataPropertyOrThrow("usage", c.usage)
        o.createDataPropertyOrThrow("sensitivity", c.sensitivity)
        o.createDataPropertyOrThrow("ignorePunctuation", c.ignorePunctuation)
        o.createDataPropertyOrThrow("collation", c.collation)
        o.createDataPropertyOrThrow("numeric", c.numeric)
        o.createDataPropertyOrThrow("caseFirst", c.caseFirst)
        return o
    }

    /** OrdinaryCreateFromConstructor + InitializeCollator */
    fun create(realm: Realm, newTarget: JSObject, locales: Any?, optionsArg: Any?): JSIntlCollator {
        val c = JSIntlCollator(protoFromCtor(newTarget, "%Intl.Collator.prototype%", realm))
        val requested = Locales.canonicalizeLocaleList(realm, locales)
        val options = Opt.coerceToObject(optionsArg)
        val usage = Opt.str(options, "usage", USAGES, "sort")
        c.usage = usage
        Opt.localeMatcher(options)
        val collation = Opt.string(options, "collation", null, null) as String?
        if (collation != null && !Opt.isUnicodeType(collation)) rangeErr("Invalid collation : $collation")
        val numeric = Opt.boolean(options, "numeric", null)
        val caseFirst = Opt.string(options, "caseFirst", CASE_FIRST, null) as String?
        val opt = HashMap<String, String?>()
        opt["co"] = collation
        opt["kn"] = numeric?.toString()
        opt["kf"] = caseFirst
        val r = Locales.resolve(realm, requested, KEYS, opt) { loc, key -> keyData(loc, key, usage) }
        c.locale = r.locale
        c.dataLocale = r.dataLocale
        c.collation = r.values["co"] ?: "default"
        c.numeric = r.values["kn"] == "true"
        c.caseFirst = r.values["kf"] ?: "false"
        c.sensitivity = Opt.str(options, "sensitivity", SENSITIVITIES, "variant")
        val defaultIgnorePunctuation = localeDefaults(r.dataLocale).ignorePunctuation
        c.ignorePunctuation = Opt.boolean(options, "ignorePunctuation", defaultIgnorePunctuation) as Boolean
        return c
    }

    /** `[[SortLocaleData]]` / `[[SearchLocaleData]]` for the relevant extension keys. */
    private fun keyData(locale: String, key: String, usage: String): List<String?> = when (key) {
        "co" -> if (usage == "search") listOf(null) else listOf<String?>(null) + collationsFor(locale)
        "kn" -> listOf("false", "true")
        "kf" -> {
            val def = localeDefaults(locale).caseFirst
            listOf(def) + CASE_FIRST.filter { it != def }
        }
        else -> listOf(null)
    }

    private fun collationsFor(locale: String): List<String> = icuCall {
        Collator.getKeywordValuesForLocale("collation", LocaleInfo.dataLocale(locale), true)
            .map { LocaleInfo.collationToBcp47(it) }.filter { it != "standard" && it != "search" }.distinct()
    }

    /** Locale-dependent defaults of the root collation of a locale (immutable values). */
    private class Defaults(val caseFirst: String, val ignorePunctuation: Boolean)

    /** Bounded, thread-safe cache of [Defaults] by data locale (holds only immutable values). */
    private val defaultsCache = java.util.concurrent.ConcurrentHashMap<String, Defaults>()

    private fun localeDefaults(locale: String): Defaults {
        defaultsCache[locale]?.let { return it }
        val d = icuCall {
            val b = Collator.getInstance(LocaleInfo.dataLocale(locale)) as RuleBasedCollator
            Defaults(if (b.isUpperCaseFirst) "upper" else if (b.isLowerCaseFirst) "lower" else "false", b.isAlternateHandlingShifted)
        }
        if (defaultsCache.size >= 256) defaultsCache.clear()
        defaultsCache[locale] = d
        return d
    }

    private inline fun <T> icuCall(block: () -> T): T = try {
        block()
    } catch (e: JSException) {
        throw e
    } catch (e: TerminationException) {
        throw e
    } catch (_: RuntimeException) {
        rangeErr("Internal error in collation data")
    }

    private fun icuCollator(c: JSIntlCollator): Collator {
        c.icu?.let { return it }
        val coll = icuCall {
            var ul = LocaleInfo.dataLocale(c.dataLocale)
            val icuCollation = if (c.usage == "search") "search" else if (c.collation != "default") ULocale.toLegacyType("collation", c.collation) else null
            if (icuCollation != null) ul = ul.setKeywordValue("collation", icuCollation)
            val rb = Collator.getInstance(ul) as RuleBasedCollator
            rb.decomposition = Collator.CANONICAL_DECOMPOSITION
            when (c.sensitivity) {
                "base" -> rb.strength = Collator.PRIMARY
                "accent" -> rb.strength = Collator.SECONDARY
                "case" -> {
                    rb.strength = Collator.PRIMARY
                    rb.isCaseLevel = true
                }
                else -> rb.strength = Collator.TERTIARY
            }
            rb.numericCollation = c.numeric
            when (c.caseFirst) {
                "upper" -> rb.isUpperCaseFirst = true
                "lower" -> rb.isLowerCaseFirst = true
                else -> {
                    rb.isUpperCaseFirst = false
                    rb.isLowerCaseFirst = false
                }
            }
            rb.isAlternateHandlingShifted = c.ignorePunctuation
            rb.freeze()
        }
        c.icu = coll
        return coll
    }

    /** CompareStrings */
    fun compareStrings(c: JSIntlCollator, x: String, y: String): Double {
        val coll = icuCollator(c)
        if (x.length + y.length > (1 shl 16)) Agent.current.get()?.checkInterrupt()
        val r = coll.compare(x, y)
        return if (r < 0) -1.0 else if (r > 0) 1.0 else 0.0
    }

    // ------------------------------------------------------------------ String.prototype

    private fun thisString(t: Any?): String = Ops.toString(Ops.requireObjectCoercible(t))

    private fun installStringMethods(realm: Realm) {
        val sp = realm.stringPrototype
        sp.method(realm, "localeCompare", 1) { f, t, args, _ ->
            val s = thisString(t)
            val that = Ops.toString(args.arg(0))
            val locales = args.arg(1)
            val options = args.arg(2)
            val ctor = f.realm.intrinsic("%Intl.Collator%")
            // a collator built from a primitive locale string and no options is unobservable to construct: reuse it
            val coll = if (options === Undefined && (locales === Undefined || locales is CharSequence)) {
                val key = if (locales === Undefined) "Collator:" else "Collator:$locales"
                IntlState.of(f.realm).cached(key) { create(f.realm, ctor, locales, Undefined) }
            } else create(f.realm, ctor, locales, options)
            compareStrings(coll, s, that)
        }
        sp.method(realm, "toLocaleLowerCase", 0) { f, t, args, _ -> transformCase(f.realm, thisString(t), args.arg(0), false) }
        sp.method(realm, "toLocaleUpperCase", 0) { f, t, args, _ -> transformCase(f.realm, thisString(t), args.arg(0), true) }
    }

    /** Languages with language-sensitive case mappings in the Unicode Character Database (SpecialCasing / ICU). */
    private val CASE_LANGUAGES = setOf("az", "el", "hy", "lt", "nl", "tr")

    /** TransformCase(S, locales, targetCase) */
    private fun transformCase(realm: Realm, s: String, locales: Any?, upper: Boolean): String {
        val requested = Locales.canonicalizeLocaleList(realm, locales)
        val requestedLocale = if (requested.isNotEmpty()) requested[0] else Locales.defaultLocale(realm)
        val noExt = LanguageTag.removeUnicodeExtension(requestedLocale)
        // LookupMatchingLocaleByPrefix over the case-mapping languages: only the language subtag can match
        val language = LanguageTag.parse(noExt)?.language
        val ul = if (language != null && language in CASE_LANGUAGES) ULocale(language) else ULocale.ROOT
        val out = if (upper) UCharacter.toUpperCase(ul, s) else UCharacter.toLowerCase(ul, s)
        return realm.checkedString(out)
    }
}
