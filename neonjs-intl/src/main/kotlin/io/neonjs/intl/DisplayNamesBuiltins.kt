package io.neonjs.intl

import com.ibm.icu.text.CurrencyDisplayNames
import com.ibm.icu.text.DateTimePatternGenerator
import com.ibm.icu.text.DisplayContext
import com.ibm.icu.text.LocaleDisplayNames
import com.ibm.icu.util.ULocale
import io.neonjs.runtime.*

/** Intl.DisplayNames instance ([[InitializedDisplayNames]]). */
class JSIntlDisplayNames internal constructor(proto: JSObject?) : JSObject(proto) {
    @JvmField var locale: String = ""
    @JvmField internal var dataLocale: String = ""
    @JvmField var style: String = "long"
    @JvmField var type: String = ""
    @JvmField var fallback: String = "code"
    @JvmField var languageDisplay: String? = null
    /** ICU display-name provider for this object's locale/style/type (created on first use). */
    @JvmField internal var icu: Any? = null
}

/** Intl.DisplayNames (ECMA-402 §12). */
internal object DisplayNamesBuiltins {
    private val STYLES = arrayOf("narrow", "short", "long")
    private val TYPES = arrayOf("language", "region", "script", "currency", "calendar", "dateTimeField")
    private val FALLBACKS = arrayOf("code", "none")
    private val LANGUAGE_DISPLAYS = arrayOf("dialect", "standard")
    private val DATE_TIME_FIELDS = mapOf(
        "era" to DateTimePatternGenerator.ERA, "year" to DateTimePatternGenerator.YEAR,
        "quarter" to DateTimePatternGenerator.QUARTER, "month" to DateTimePatternGenerator.MONTH,
        "weekOfYear" to DateTimePatternGenerator.WEEK_OF_YEAR, "weekday" to DateTimePatternGenerator.WEEKDAY,
        "day" to DateTimePatternGenerator.DAY, "dayPeriod" to DateTimePatternGenerator.DAYPERIOD,
        "hour" to DateTimePatternGenerator.HOUR, "minute" to DateTimePatternGenerator.MINUTE,
        "second" to DateTimePatternGenerator.SECOND, "timeZoneName" to DateTimePatternGenerator.ZONE,
    )

    fun install(realm: Realm, intl: JSObject) {
        val proto = JSObject(realm.objectPrototype)
        realm.intrinsics["%Intl.DisplayNames.prototype%"] = proto
        val ctor = makeCtor(realm, "DisplayNames", 2, proto) { f, _, args, nt ->
            if (nt == null) typeErr("Constructor Intl.DisplayNames requires 'new'")
            create(f.realm, nt, args.arg(0), args.arg(1))
        }
        realm.intrinsics["%Intl.DisplayNames%"] = ctor
        intl.defineOwn("DisplayNames", ctor, Attr.WC)
        Locales.installSupportedLocalesOf(realm, ctor)
        proto.defineOwn(JSSymbol.toStringTag, "Intl.DisplayNames", Attr.CONFIGURABLE)
        proto.method(realm, "of", 1) { _, t, args, _ ->
            val dn = thisDisplayNames(t, "of")
            val code = canonicalCode(dn.type, Ops.toString(args.arg(0)))
            displayName(dn, code) ?: if (dn.fallback == "code") code else Undefined
        }
        proto.method(realm, "resolvedOptions", 0) { f, t, _, _ ->
            val dn = thisDisplayNames(t, "resolvedOptions")
            val o = plainObject(f.realm)
            o.createDataPropertyOrThrow("locale", dn.locale)
            o.createDataPropertyOrThrow("style", dn.style)
            o.createDataPropertyOrThrow("type", dn.type)
            o.createDataPropertyOrThrow("fallback", dn.fallback)
            dn.languageDisplay?.let { o.createDataPropertyOrThrow("languageDisplay", it) }
            o
        }
    }

    private fun thisDisplayNames(t: Any?, method: String): JSIntlDisplayNames =
        t as? JSIntlDisplayNames ?: typeErr("Method Intl.DisplayNames.prototype.$method called on incompatible receiver ${Ops.describe(t)}")

    private fun create(realm: Realm, nt: JSObject, locales: Any?, optionsArg: Any?): JSIntlDisplayNames {
        val dn = JSIntlDisplayNames(protoFromCtor(nt, "%Intl.DisplayNames.prototype%", realm))
        val requested = Locales.canonicalizeLocaleList(realm, locales)
        if (optionsArg === Undefined) typeErr("Intl.DisplayNames requires an options object with a type")
        val options = Opt.getOptionsObject(optionsArg)
        Opt.localeMatcher(options)
        val r = Locales.resolve(realm, requested, emptyList(), emptyMap()) { _, _ -> listOf(null) }
        dn.style = Opt.str(options, "style", STYLES, "long")
        val type = Opt.string(options, "type", TYPES, null) as String? ?: typeErr("Intl.DisplayNames requires a type option")
        dn.type = type
        dn.fallback = Opt.str(options, "fallback", FALLBACKS, "code")
        dn.locale = r.locale
        dn.dataLocale = r.dataLocale
        val languageDisplay = Opt.str(options, "languageDisplay", LANGUAGE_DISPLAYS, "dialect")
        if (type == "language") dn.languageDisplay = languageDisplay
        return dn
    }

    /** CanonicalCodeForDisplayNames */
    private fun canonicalCode(type: String, code: String): String = when (type) {
        "language" -> {
            val p = LanguageTag.parse(code)
            if (p == null || p.extensions.isNotEmpty() || p.privateUse != null) rangeErr("Invalid language code: $code")
            LanguageTag.canonicalize(code)
        }
        "region" -> {
            if (!LanguageTag.isRegionSubtag(code)) rangeErr("Invalid region code: $code")
            code.uppercase()
        }
        "script" -> {
            if (!LanguageTag.isScriptSubtag(code)) rangeErr("Invalid script code: $code")
            code.substring(0, 1).uppercase() + code.substring(1).lowercase()
        }
        "calendar" -> {
            if (!Opt.isUnicodeType(code)) rangeErr("Invalid calendar code: $code")
            code.lowercase()
        }
        "dateTimeField" -> {
            if (code !in DATE_TIME_FIELDS) rangeErr("Invalid dateTimeField code: $code")
            code
        }
        else -> {
            if (code.length != 3 || !code.all { it in 'a'..'z' || it in 'A'..'Z' }) rangeErr("Invalid currency code: $code")
            code.uppercase()
        }
    }

    private fun ulocale(dn: JSIntlDisplayNames): ULocale = LocaleInfo.dataLocale(dn.dataLocale)

    private fun localeDisplayNames(dn: JSIntlDisplayNames): LocaleDisplayNames {
        (dn.icu as? LocaleDisplayNames)?.let { return it }
        val dialect = if (dn.languageDisplay == "standard") DisplayContext.STANDARD_NAMES else DisplayContext.DIALECT_NAMES
        val length = if (dn.style == "long") DisplayContext.LENGTH_FULL else DisplayContext.LENGTH_SHORT
        val ldn = LocaleDisplayNames.getInstance(ulocale(dn), dialect, length, DisplayContext.NO_SUBSTITUTE)
        dn.icu = ldn
        return ldn
    }

    /** The display name of an already canonical [code], or null when the locale data has none. */
    private fun displayName(dn: JSIntlDisplayNames, code: String): String? = try {
        when (dn.type) {
            "language" -> localeDisplayNames(dn).localeDisplayName(ULocale.forLanguageTag(code))
            "region" -> localeDisplayNames(dn).regionDisplayName(code)
            "script" -> localeDisplayNames(dn).scriptDisplayName(code)
            "calendar" -> localeDisplayNames(dn).keyValueDisplayName("calendar", ULocale.toLegacyType("calendar", code) ?: code)
            "dateTimeField" -> {
                val g = (dn.icu as? DateTimePatternGenerator) ?: DateTimePatternGenerator.getInstance(ulocale(dn)).also { dn.icu = it }
                val width = when (dn.style) {
                    "short" -> DateTimePatternGenerator.DisplayWidth.ABBREVIATED
                    "narrow" -> DateTimePatternGenerator.DisplayWidth.NARROW
                    else -> DateTimePatternGenerator.DisplayWidth.WIDE
                }
                g.getFieldDisplayName(DATE_TIME_FIELDS.getValue(code), width)
            }
            else -> {
                val c = (dn.icu as? CurrencyDisplayNames) ?: CurrencyDisplayNames.getInstance(ulocale(dn), true).also { dn.icu = it }
                when (dn.style) {
                    "short" -> c.getSymbol(code)
                    "narrow" -> c.getNarrowSymbol(code)
                    else -> c.getName(code)
                }
            }
        }
    } catch (e: JSException) {
        throw e
    } catch (e: TerminationException) {
        throw e
    } catch (_: RuntimeException) {
        null
    }
}
