package io.neonjs.intl

import com.ibm.icu.number.LocalizedNumberFormatter
import com.ibm.icu.number.Notation
import com.ibm.icu.number.NumberFormatter
import com.ibm.icu.number.NumberRangeFormatter
import com.ibm.icu.number.UnlocalizedNumberFormatter
import com.ibm.icu.text.PluralRules
import com.ibm.icu.util.ULocale
import io.neonjs.runtime.*

/** Intl.PluralRules instance ([[InitializedPluralRules]]). */
class JSIntlPluralRules internal constructor(proto: JSObject?) : JSObject(proto) {
    @JvmField var locale: String = ""
    @JvmField internal var dataLocale: String = ""
    @JvmField var type: String = "cardinal"
    @JvmField var notation: String = "standard"
    @JvmField var compactDisplay: String? = null
    @JvmField internal val digits = DigitOptions()
    /** ICU PluralRules (immutable) and LocalizedNumberFormatters by sign; created on first use. */
    @JvmField internal var icuRules: Any? = null
    @JvmField internal val icuFormatters = arrayOfNulls<Any>(2)
}

/** Intl.PluralRules (ECMA-402 §17). */
internal object PluralRulesBuiltins {
    private val TYPES = arrayOf("cardinal", "ordinal")
    private val NOTATIONS = arrayOf("standard", "scientific", "engineering", "compact")
    private val COMPACT_DISPLAYS = arrayOf("short", "long")

    fun install(realm: Realm, intl: JSObject) {
        val proto = JSObject(realm.objectPrototype)
        realm.intrinsics["%Intl.PluralRules.prototype%"] = proto
        val ctor = makeCtor(realm, "PluralRules", 0, proto) { f, _, args, nt ->
            if (nt == null) typeErr("Constructor Intl.PluralRules requires 'new'")
            create(f.realm, nt, args.arg(0), args.arg(1))
        }
        realm.intrinsics["%Intl.PluralRules%"] = ctor
        intl.defineOwn("PluralRules", ctor, Attr.WC)
        Locales.installSupportedLocalesOf(realm, ctor)
        proto.defineOwn(JSSymbol.toStringTag, "Intl.PluralRules", Attr.CONFIGURABLE)
        proto.method(realm, "select", 1) { _, t, args, _ ->
            val pr = thisPR(t, "select")
            PluralRulesIcu.select(pr, Ops.toNumber(args.arg(0)))
        }
        proto.method(realm, "selectRange", 2) { _, t, args, _ ->
            val pr = thisPR(t, "selectRange")
            val start = args.arg(0)
            val end = args.arg(1)
            if (start === Undefined || end === Undefined) typeErr("start or end is undefined")
            val x = Ops.toNumber(start)
            val y = Ops.toNumber(end)
            if (x.isNaN() || y.isNaN()) rangeErr("start or end is NaN")
            PluralRulesIcu.selectRange(pr, x, y)
        }
        proto.method(realm, "resolvedOptions", 0) { f, t, _, _ -> resolvedOptions(f.realm, thisPR(t, "resolvedOptions")) }
    }

    private fun thisPR(t: Any?, method: String): JSIntlPluralRules =
        t as? JSIntlPluralRules ?: typeErr("Method Intl.PluralRules.prototype.$method called on incompatible receiver ${Ops.describe(t)}")

    private fun create(realm: Realm, newTarget: JSObject, locales: Any?, optionsArg: Any?): JSIntlPluralRules {
        val pr = JSIntlPluralRules(protoFromCtor(newTarget, "%Intl.PluralRules.prototype%", realm))
        val requested = Locales.canonicalizeLocaleList(realm, locales)
        val options = Opt.coerceToObject(optionsArg)
        Opt.localeMatcher(options)
        val r = Locales.resolve(realm, requested, emptyList(), emptyMap()) { _, _ -> emptyList() }
        pr.locale = r.locale
        pr.dataLocale = r.dataLocale
        pr.type = Opt.str(options, "type", TYPES, "cardinal")
        val notation = Opt.str(options, "notation", NOTATIONS, "standard")
        pr.notation = notation
        val compactDisplay = Opt.str(options, "compactDisplay", COMPACT_DISPLAYS, "short")
        if (notation == "compact") pr.compactDisplay = compactDisplay
        pr.digits.read(options, 0, 3, notation)
        return pr
    }

    private fun resolvedOptions(realm: Realm, pr: JSIntlPluralRules): JSObject {
        val o = plainObject(realm)
        o.createDataPropertyOrThrow("locale", pr.locale)
        o.createDataPropertyOrThrow("type", pr.type)
        o.createDataPropertyOrThrow("notation", pr.notation)
        pr.compactDisplay?.let { o.createDataPropertyOrThrow("compactDisplay", it) }
        pr.digits.addDigitsTo(o)
        o.createDataPropertyOrThrow("pluralCategories", jsArray(realm, PluralRulesIcu.categories(pr)))
        o.createDataPropertyOrThrow("roundingIncrement", pr.digits.roundingIncrement.toDouble())
        o.createDataPropertyOrThrow("roundingMode", pr.digits.roundingMode)
        o.createDataPropertyOrThrow("roundingPriority", pr.digits.computedRoundingPriority)
        o.createDataPropertyOrThrow("trailingZeroDisplay", pr.digits.trailingZeroDisplay)
        return o
    }
}

/** ICU side of Intl.PluralRules (loaded on first use). */
internal object PluralRulesIcu {
    private val ORDER = listOf("zero", "one", "two", "few", "many", "other")

    private fun rules(pr: JSIntlPluralRules): PluralRules {
        pr.icuRules?.let { return it as PluralRules }
        val r = NumberFormatIcu.icuCall {
            PluralRules.forLocale(ULocale.forLanguageTag(pr.dataLocale), if (pr.type == "ordinal") PluralRules.PluralType.ORDINAL else PluralRules.PluralType.CARDINAL)
        }
        pr.icuRules = r
        return r
    }

    private fun settings(pr: JSIntlPluralRules, signum: Int): UnlocalizedNumberFormatter {
        var f = NumberFormatter.with().notation(when (pr.notation) {
            "scientific" -> Notation.scientific()
            "engineering" -> Notation.engineering()
            "compact" -> if (pr.compactDisplay == "long") Notation.compactLong() else Notation.compactShort()
            else -> Notation.simple()
        })
        f = pr.digits.applyTo(f, signum)
        return f
    }

    private fun formatter(pr: JSIntlPluralRules, signum: Int): LocalizedNumberFormatter {
        val idx = if (pr.digits.signDependent && signum < 0) 1 else 0
        pr.icuFormatters[idx]?.let { return it as LocalizedNumberFormatter }
        val f = NumberFormatIcu.icuCall { settings(pr, signum).locale(ULocale.forLanguageTag(pr.dataLocale)) }
        pr.icuFormatters[idx] = f
        return f
    }

    private fun sign(d: Double): Int = if (d < 0 || (d == 0.0 && 1.0 / d < 0)) -1 else if (d > 0) 1 else 0

    /** ResolvePlural */
    fun select(pr: JSIntlPluralRules, n: Double): String {
        if (n.isNaN() || n.isInfinite()) return "other"
        return NumberFormatIcu.icuCall { rules(pr).select(formatter(pr, sign(n)).format(n)) }
    }

    /** ResolvePluralRange */
    fun selectRange(pr: JSIntlPluralRules, x: Double, y: Double): String {
        if (x.isInfinite() || y.isInfinite()) {
            // no formatted operands for non-finite values: use the category of the end
            return select(pr, y)
        }
        return NumberFormatIcu.icuCall {
            val first = settings(pr, sign(x))
            val rf = NumberRangeFormatter.withLocale(ULocale.forLanguageTag(pr.dataLocale))
            val withNumbers = if (!pr.digits.signDependent || sign(x) == sign(y)) rf.numberFormatterBoth(first)
            else rf.numberFormatterFirst(first).numberFormatterSecond(settings(pr, sign(y)))
            rules(pr).select(withNumbers.identityFallback(NumberRangeFormatter.RangeIdentityFallback.APPROXIMATELY).formatRange(x, y))
        }
    }

    fun categories(pr: JSIntlPluralRules): List<Any?> {
        val kw = NumberFormatIcu.icuCall { rules(pr).keywords }
        return ORDER.filter { it in kw }
    }
}
