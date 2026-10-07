package io.neonjs.intl

import com.ibm.icu.text.ConstrainedFieldPosition
import com.ibm.icu.text.DisplayContext
import com.ibm.icu.text.NumberFormat
import com.ibm.icu.text.RelativeDateTimeFormatter
import io.neonjs.runtime.*
import kotlin.math.abs

/** Intl.RelativeTimeFormat instance ([[InitializedRelativeTimeFormat]]). */
class JSIntlRelativeTimeFormat internal constructor(proto: JSObject?) : JSObject(proto) {
    @JvmField var locale: String = ""
    @JvmField internal var dataLocale: String = ""
    @JvmField var numberingSystem: String = "latn"
    @JvmField var style: String = "long"
    @JvmField var numeric: String = "always"
    /** ICU RelativeDateTimeFormatter (owns a DecimalFormat, so never shared); created on first use. */
    @JvmField internal var icu: Any? = null
    /** [[NumberFormat]]: formats the number inside ICU's pattern (Intl.NumberFormat semantics, e.g. min2 grouping). */
    @JvmField internal var numberFormat: JSIntlNumberFormat? = null
}

/** Intl.RelativeTimeFormat (ECMA-402 §18). */
internal object RelativeTimeFormatBuiltins {
    private val STYLES = arrayOf("long", "short", "narrow")
    private val NUMERICS = arrayOf("always", "auto")

    fun install(realm: Realm, intl: JSObject) {
        val proto = JSObject(realm.objectPrototype)
        realm.intrinsics["%Intl.RelativeTimeFormat.prototype%"] = proto
        val ctor = makeCtor(realm, "RelativeTimeFormat", 0, proto) { f, _, args, nt ->
            if (nt == null) typeErr("Constructor Intl.RelativeTimeFormat requires 'new'")
            create(f.realm, nt, args.arg(0), args.arg(1))
        }
        realm.intrinsics["%Intl.RelativeTimeFormat%"] = ctor
        intl.defineOwn("RelativeTimeFormat", ctor, Attr.WC)
        Locales.installSupportedLocalesOf(realm, ctor)
        proto.defineOwn(JSSymbol.toStringTag, "Intl.RelativeTimeFormat", Attr.CONFIGURABLE)
        proto.method(realm, "format", 2) { f, t, args, _ ->
            val rtf = thisRTF(t, "format")
            val value = Ops.toNumber(args.arg(0))
            val unit = Ops.toString(args.arg(1))
            val sb = StringBuilder()
            for (p in RelativeTimeFormatIcu.partition(f.realm, rtf, value, unit)) sb.append(p.value)
            f.realm.checkedString(sb)
        }
        proto.method(realm, "formatToParts", 2) { f, t, args, _ ->
            val rtf = thisRTF(t, "formatToParts")
            val value = Ops.toNumber(args.arg(0))
            val unit = Ops.toString(args.arg(1))
            RelativeTimeFormatIcu.formatToParts(f.realm, rtf, value, unit)
        }
        proto.method(realm, "resolvedOptions", 0) { f, t, _, _ ->
            val rtf = thisRTF(t, "resolvedOptions")
            val o = plainObject(f.realm)
            o.createDataPropertyOrThrow("locale", rtf.locale)
            o.createDataPropertyOrThrow("style", rtf.style)
            o.createDataPropertyOrThrow("numeric", rtf.numeric)
            o.createDataPropertyOrThrow("numberingSystem", rtf.numberingSystem)
            o
        }
    }

    private fun thisRTF(t: Any?, method: String): JSIntlRelativeTimeFormat =
        t as? JSIntlRelativeTimeFormat ?: typeErr("Method Intl.RelativeTimeFormat.prototype.$method called on incompatible receiver ${Ops.describe(t)}")

    private fun create(realm: Realm, newTarget: JSObject, locales: Any?, optionsArg: Any?): JSIntlRelativeTimeFormat {
        val rtf = JSIntlRelativeTimeFormat(protoFromCtor(newTarget, "%Intl.RelativeTimeFormat.prototype%", realm))
        val requested = Locales.canonicalizeLocaleList(realm, locales)
        val options = Opt.coerceToObject(optionsArg)
        Opt.localeMatcher(options)
        val nu = Opt.string(options, "numberingSystem", null, null) as String?
        if (nu != null && !Opt.isUnicodeType(nu)) rangeErr("Invalid numberingSystem: $nu")
        val r = Locales.resolve(realm, requested, listOf("nu"), mapOf("nu" to nu)) { loc, _ -> NumberFormatBuiltins.numberingSystemData(loc) }
        rtf.locale = r.locale
        rtf.dataLocale = r.dataLocale
        rtf.numberingSystem = r.values["nu"] ?: "latn"
        rtf.style = Opt.str(options, "style", STYLES, "long")
        rtf.numeric = Opt.str(options, "numeric", NUMERICS, "always")
        return rtf
    }
}

/** ICU side of Intl.RelativeTimeFormat (loaded on first use). */
internal object RelativeTimeFormatIcu {
    /** SingularRelativeTimeUnit */
    private fun singularUnit(unit: String): String = when (unit) {
        "seconds" -> "second"
        "minutes" -> "minute"
        "hours" -> "hour"
        "days" -> "day"
        "weeks" -> "week"
        "months" -> "month"
        "quarters" -> "quarter"
        "years" -> "year"
        "second", "minute", "hour", "day", "week", "month", "quarter", "year" -> unit
        else -> rangeErr("Invalid unit argument for format() '$unit'")
    }

    private fun icuUnit(unit: String): RelativeDateTimeFormatter.RelativeDateTimeUnit = when (unit) {
        "second" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.SECOND
        "minute" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.MINUTE
        "hour" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.HOUR
        "day" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.DAY
        "week" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.WEEK
        "month" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.MONTH
        "quarter" -> RelativeDateTimeFormatter.RelativeDateTimeUnit.QUARTER
        else -> RelativeDateTimeFormatter.RelativeDateTimeUnit.YEAR
    }

    private fun formatter(rtf: JSIntlRelativeTimeFormat): RelativeDateTimeFormatter {
        rtf.icu?.let { return it as RelativeDateTimeFormatter }
        val f = NumberFormatIcu.icuCall {
            val loc = NumberFormatIcu.icuLocale(rtf.dataLocale, rtf.numberingSystem)
            val nf = NumberFormat.getInstance(loc)
            nf.minimumFractionDigits = 0
            nf.maximumFractionDigits = 3
            nf.roundingMode = com.ibm.icu.math.BigDecimal.ROUND_HALF_UP
            nf.isGroupingUsed = true
            val style = when (rtf.style) {
                "short" -> RelativeDateTimeFormatter.Style.SHORT
                "narrow" -> RelativeDateTimeFormatter.Style.NARROW
                else -> RelativeDateTimeFormatter.Style.LONG
            }
            RelativeDateTimeFormatter.getInstance(loc, nf, style, DisplayContext.CAPITALIZATION_NONE)
        }
        rtf.icu = f
        return f
    }

    private fun numberFormat(realm: Realm, rtf: JSIntlRelativeTimeFormat): JSIntlNumberFormat {
        rtf.numberFormat?.let { return it }
        val opts = JSObject(null)
        opts.createDataPropertyOrThrow("numberingSystem", rtf.numberingSystem)
        val nf = NumberFormatBuiltins.create(realm, null, rtf.locale, opts)
        rtf.numberFormat = nf
        return nf
    }

    /**
     * PartitionRelativeTimePattern: ICU picks the pattern (and plural form); the number inside it is formatted by
     * [[NumberFormat]]. Number parts carry the unit.
     */
    fun partition(realm: Realm, rtf: JSIntlRelativeTimeFormat, value: Double, unit0: String): List<FmtPart> {
        if (value.isNaN() || value.isInfinite()) rangeErr("Invalid value: ${NumberConvShortest.toShortest(value)}")
        val unit = singularUnit(unit0)
        val f = formatter(rtf)
        val fv = NumberFormatIcu.icuCall {
            if (rtf.numeric == "auto") f.formatToValue(value, icuUnit(unit)) else f.formatNumericToValue(value, icuUnit(unit))
        }
        val text = fv.toString()
        val cfp = ConstrainedFieldPosition()
        cfp.constrainField(RelativeDateTimeFormatter.Field.NUMERIC)
        if (!fv.nextPosition(cfp)) return listOf(FmtPart("literal", text, null))
        val out = ArrayList<FmtPart>()
        if (cfp.start > 0) out.add(FmtPart("literal", text.substring(0, cfp.start), null))
        val x = IntlMV.of(abs(value))
        val nf = numberFormat(realm, rtf)
        for (p in NumberFormatIcu.parts(realm, NumberFormatIcu.format(nf, x), arrayOf(x), false)) out.add(FmtPart(p.type, p.value, unit))
        if (cfp.limit < text.length) out.add(FmtPart("literal", text.substring(cfp.limit), null))
        return out
    }

    fun formatToParts(realm: Realm, rtf: JSIntlRelativeTimeFormat, value: Double, unit0: String): JSArray {
        val list = ArrayList<Any?>()
        for (p in partition(realm, rtf, value, unit0)) {
            val o = partObject(realm, p.type, p.value)
            if (p.source != null) o.createDataPropertyOrThrow("unit", p.source)
            list.add(o)
        }
        return jsArray(realm, list)
    }
}
