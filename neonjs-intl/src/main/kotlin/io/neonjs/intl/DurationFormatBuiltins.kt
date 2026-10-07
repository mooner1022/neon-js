package io.neonjs.intl

import io.neonjs.runtime.*

/** Intl.DurationFormat instance (`[[InitializedDurationFormat]]`). */
class JSIntlDurationFormat internal constructor(proto: JSObject?) : JSObject(proto) {
    @JvmField var locale: String = ""
    @JvmField internal var dataLocale: String = ""
    @JvmField var numberingSystem: String = "latn"
    @JvmField var style: String = "short"
    /** Style and display of each unit of [DurationFormatBuiltins.UNITS] ("fractional" for fractional sub-seconds). */
    @JvmField internal val styles = arrayOfNulls<String>(10)
    @JvmField internal val displays = arrayOfNulls<String>(10)
    @JvmField var fractionalDigits: Int? = null
    /** Number formats used by format(), by option key (per object; never shared). */
    @JvmField internal val numberFormats = HashMap<String, JSIntlNumberFormat>()
    /** ICU ListFormatter (immutable) and digital separators; computed on first use. */
    @JvmField internal var listFormatter: Any? = null
    @JvmField internal var separators: Array<String>? = null
}

/** Intl.DurationFormat (ECMA-402 §13). */
internal object DurationFormatBuiltins {
    val UNITS = arrayOf("years", "months", "weeks", "days", "hours", "minutes", "seconds", "milliseconds", "microseconds", "nanoseconds")
    val NF_UNITS = arrayOf("year", "month", "week", "day", "hour", "minute", "second", "millisecond", "microsecond", "nanosecond")
    private val DATE_STYLES = arrayOf("long", "short", "narrow")
    private val HMS_STYLES = arrayOf("long", "short", "narrow", "numeric", "2-digit")
    private val SUBSECOND_STYLES = arrayOf("long", "short", "narrow", "numeric")
    private val STYLES = arrayOf("long", "short", "narrow", "digital")
    private val DISPLAYS = arrayOf("auto", "always")

    fun install(realm: Realm, intl: JSObject) {
        val proto = JSObject(realm.objectPrototype)
        realm.intrinsics["%Intl.DurationFormat.prototype%"] = proto
        val ctor = makeCtor(realm, "DurationFormat", 0, proto) { f, _, args, nt ->
            if (nt == null) typeErr("Constructor Intl.DurationFormat requires 'new'")
            create(f.realm, nt, args.arg(0), args.arg(1))
        }
        realm.intrinsics["%Intl.DurationFormat%"] = ctor
        intl.defineOwn("DurationFormat", ctor, Attr.WC)
        Locales.installSupportedLocalesOf(realm, ctor)
        proto.defineOwn(JSSymbol.toStringTag, "Intl.DurationFormat", Attr.CONFIGURABLE)
        proto.method(realm, "format", 1) { f, t, args, _ ->
            val df = thisDF(t, "format")
            val record = DurationRecord.from(args.arg(0))
            val sb = StringBuilder()
            for (p in DurationFormatPattern.partition(f.realm, df, record)) {
                sb.append(p.value)
                f.realm.agent.checkStringLength(sb.length.toLong())
            }
            sb.toString()
        }
        proto.method(realm, "formatToParts", 1) { f, t, args, _ ->
            val df = thisDF(t, "formatToParts")
            val record = DurationRecord.from(args.arg(0))
            val out = ArrayList<Any?>()
            for (p in DurationFormatPattern.partition(f.realm, df, record)) {
                val o = partObject(f.realm, p.type, p.value)
                if (p.unit != null) o.createDataPropertyOrThrow("unit", p.unit)
                out.add(o)
            }
            jsArray(f.realm, out)
        }
        proto.method(realm, "resolvedOptions", 0) { f, t, _, _ -> resolvedOptions(f.realm, thisDF(t, "resolvedOptions")) }
    }

    /** `new Intl.DurationFormat(locales, options).format(duration)` (Temporal.Duration.prototype.toLocaleString). */
    fun format(realm: Realm, locales: Any?, options: Any?, duration: Any?): String {
        val df = create(realm, realm.intrinsic("%Intl.DurationFormat%"), locales, options)
        val record = DurationRecord.from(duration)
        val sb = StringBuilder()
        for (p in DurationFormatPattern.partition(realm, df, record)) {
            sb.append(p.value)
            realm.agent.checkStringLength(sb.length.toLong())
        }
        return sb.toString()
    }

    private fun thisDF(t: Any?, method: String): JSIntlDurationFormat =
        t as? JSIntlDurationFormat ?: typeErr("Method Intl.DurationFormat.prototype.$method called on incompatible receiver ${Ops.describe(t)}")

    private fun create(realm: Realm, newTarget: JSObject, locales: Any?, optionsArg: Any?): JSIntlDurationFormat {
        val df = JSIntlDurationFormat(protoFromCtor(newTarget, "%Intl.DurationFormat.prototype%", realm))
        val requested = Locales.canonicalizeLocaleList(realm, locales)
        val options = Opt.getOptionsObject(optionsArg)
        Opt.localeMatcher(options)
        val nu = Opt.string(options, "numberingSystem", null, null) as String?
        if (nu != null && !Opt.isUnicodeType(nu)) rangeErr("Invalid numberingSystem: $nu")
        val r = Locales.resolve(realm, requested, listOf("nu"), mapOf("nu" to nu)) { loc, _ -> NumberFormatBuiltins.numberingSystemData(loc) }
        df.locale = r.locale
        df.dataLocale = r.dataLocale
        df.numberingSystem = r.values["nu"] ?: "latn"
        val style = Opt.str(options, "style", STYLES, "short")
        df.style = style
        var prevStyle = ""
        for (i in 0 until 10) {
            val values = when {
                i < 4 -> DATE_STYLES
                i < 7 -> HMS_STYLES
                else -> SUBSECOND_STYLES
            }
            val digitalBase = if (i < 4) "short" else "numeric"
            val (s, d) = unitOptions(df, i, options, style, values, digitalBase, prevStyle)
            df.styles[i] = s
            df.displays[i] = d
            if (i >= 4) prevStyle = s
        }
        df.fractionalDigits = Opt.number(options, "fractionalDigits", 0, 9, null)
        return df
    }

    /** GetDurationUnitOptions */
    private fun unitOptions(df: JSIntlDurationFormat, i: Int, options: JSObject, baseStyle: String, values: Array<String>, digitalBase: String, prevStyle: String): Pair<String, String> {
        val unit = UNITS[i]
        var style = Opt.string(options, unit, values, null) as String?
        var displayDefault = "always"
        if (style == null) {
            if (baseStyle == "digital") {
                style = digitalBase
                if (unit != "hours" && unit != "minutes" && unit != "seconds") displayDefault = "auto"
            } else if (prevStyle == "fractional" || prevStyle == "numeric" || prevStyle == "2-digit") {
                style = "numeric"
                if (unit != "minutes" && unit != "seconds") displayDefault = "auto"
            } else {
                style = baseStyle
                displayDefault = "auto"
            }
        }
        if (style == "numeric" && i >= 7) {
            style = "fractional"
            displayDefault = "auto"
        }
        val display = Opt.str(options, unit + "Display", DISPLAYS, displayDefault)
        // ValidateDurationUnitStyle
        if (display == "always" && style == "fractional") rangeErr("$unit: fractional style cannot be displayed always")
        if (prevStyle == "fractional" && style != "fractional") rangeErr("$unit: invalid style $style after a fractional unit")
        if ((prevStyle == "numeric" || prevStyle == "2-digit") && style != "fractional" && style != "numeric" && style != "2-digit") {
            rangeErr("$unit: invalid style $style after a numeric unit")
        }
        if (style == "numeric") {
            if (unit == "hours" && DurationFormatPattern.twoDigitHours(df)) style = "2-digit"
            if ((unit == "minutes" || unit == "seconds") && (prevStyle == "numeric" || prevStyle == "2-digit")) style = "2-digit"
        }
        return style to display
    }

    private fun resolvedOptions(realm: Realm, df: JSIntlDurationFormat): JSObject {
        val o = plainObject(realm)
        o.createDataPropertyOrThrow("locale", df.locale)
        o.createDataPropertyOrThrow("numberingSystem", df.numberingSystem)
        o.createDataPropertyOrThrow("style", df.style)
        for (i in 0 until 10) {
            val s = df.styles[i]!!
            o.createDataPropertyOrThrow(UNITS[i], if (s == "fractional") "numeric" else s)
            o.createDataPropertyOrThrow(UNITS[i] + "Display", df.displays[i]!!)
        }
        df.fractionalDigits?.let { o.createDataPropertyOrThrow("fractionalDigits", it.toDouble()) }
        return o
    }
}
