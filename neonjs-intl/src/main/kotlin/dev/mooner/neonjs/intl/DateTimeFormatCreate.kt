package dev.mooner.neonjs.intl

import dev.mooner.neonjs.runtime.*
import kotlin.math.abs

/** CreateDateTimeFormat and the time zone / Temporal format-record operations it relies on. */
internal object DtfCreate {
    /** Java-style three-letter ids that ICU knows but that are not IANA names. */
    private val NON_IANA = setOf(
        "ACT", "AET", "AGT", "ART", "AST", "BET", "BST", "CAT", "CNT", "CST", "CTT", "EAT", "ECT", "IET", "IST", "JST",
        "MIT", "NET", "NST", "PLT", "PNT", "PRT", "PST", "SST", "VST",
    )

    /** CreateDateTimeFormat; [toLocaleStringTimeZone] is the time zone of a ZonedDateTime being formatted. */
    fun create(realm: Realm, newTarget: JSObject, locales: Any?, optionsArg: Any?, required: String, defaults: String,
               toLocaleStringTimeZone: String? = null): JSIntlDateTimeFormat {
        val dtf = JSIntlDateTimeFormat(protoFromCtor(newTarget, "%Intl.DateTimeFormat.prototype%", realm))
        val requested = Locales.canonicalizeLocaleList(realm, locales)
        val options = Opt.coerceToObject(optionsArg)
        Opt.localeMatcher(options)
        val calendar = typeOption(options, "calendar")
        val nu = typeOption(options, "numberingSystem")
        val hour12 = Opt.boolean(options, "hour12", null) as Boolean?
        var hourCycle = Opt.string(options, "hourCycle", DtfTables.HOUR_CYCLES, null) as String?
        if (hour12 != null) hourCycle = null
        try {
            resolveLocale(realm, dtf, requested, calendar, nu, hourCycle, hour12)
        } catch (e: JSException) {
            throw e
        } catch (e: RuntimeException) {
            if (e is TerminationException) throw e
            rangeErr("Incorrect locale information provided")
        }
        dtf.timeZone = readTimeZone(options, toLocaleStringTimeZone)
        readComponents(options, dtf)
        val matcher = Opt.str(options, "formatMatcher", DtfTables.MATCHERS, "best fit")
        dtf.formatMatcher = matcher
        val dateStyle = Opt.string(options, "dateStyle", DtfTables.STYLES, null) as String?
        dtf.dateStyle = dateStyle
        val timeStyle = Opt.string(options, "timeStyle", DtfTables.STYLES, null) as String?
        dtf.timeStyle = timeStyle
        if (dateStyle != null || timeStyle != null) {
            if (hasExplicitComponents(dtf)) typeErr("Can't set option ${explicitName(dtf)} when dateStyle or timeStyle is used")
            if (required == "date" && timeStyle != null) typeErr("Invalid option : timeStyle")
            if (required == "time" && dateStyle != null) typeErr("Invalid option : dateStyle")
        }
        // ZonedDateTime.prototype.toLocaleString without any field or style option also shows the time zone
        if (toLocaleStringTimeZone != null && dateStyle == null && timeStyle == null && dtf.formatOptions.isEmpty()) {
            dtf.formatOptions = linkedMapOf("timeZoneName" to "short")
        }
        dtf.format = try {
            val icu = DtfIcu.of(dtf)
            if (dateStyle != null || timeStyle != null) {
                DtfData.styleFormat(icu.gen, icu.uloc, dateStyle, timeStyle, dtf.hc)
            } else {
                DtfData.formatFor(icu.gen, getDateTimeFormat(dtf.formatOptions, required, defaults, "all")!!, dtf.hc)
            }
        } catch (e: JSException) {
            throw e
        } catch (e: RuntimeException) {
            if (e is TerminationException) throw e
            rangeErr("Unable to create the date-time format")
        }
        dtf.hourCycle = if (dtf.format.hasHour) dtf.hc else null
        return dtf
    }

    private fun typeOption(options: JSObject, name: String): String? {
        val v = Opt.string(options, name, null, null) as? String ?: return null
        if (!Opt.isUnicodeType(v)) rangeErr("Invalid $name : $v")
        return v
    }

    private fun resolveLocale(realm: Realm, dtf: JSIntlDateTimeFormat, requested: List<String>, calendar: String?, nu: String?, hourCycle: String?, hour12: Boolean?) {
        val opt = mapOf("ca" to calendar, "hc" to hourCycle, "nu" to nu)
        val r = Locales.resolve(realm, requested, listOf("ca", "hc", "nu"), opt) { loc, key -> keyData(loc, key) }
        var locale = r.locale
        var rHc = r.values["hc"]
        if (hour12 != null) {
            // options.[[hc]] is null: ResolveLocale drops a requested "hc" keyword
            rHc = null
            if (LanguageTag.unicodeKeyword(locale, "hc") != null) {
                val (attrs, kws) = LanguageTag.unicodeExtensionOf(locale)
                locale = LanguageTag.insertUnicodeExtensionAndCanonicalize(
                    LanguageTag.removeUnicodeExtension(locale), attrs, kws.filter { it.first != "hc" })
            }
        }
        dtf.locale = locale
        dtf.dataLocale = r.dataLocale
        dtf.calendar = r.values["ca"] ?: "gregory"
        dtf.numberingSystem = r.values["nu"] ?: "latn"
        val (hcDefault, hc12, hc24) = DtfData.hourCycles(r.dataLocale)
        dtf.hc = when (hour12) {
            true -> hc12
            false -> hc24
            null -> rHc ?: hcDefault
        }
    }

    private fun keyData(locale: String, key: String): List<String?> = when (key) {
        "ca" -> listOf(DtfData.defaultCalendar(locale)) + SupportedValues.calendars
        "nu" -> listOf(DtfData.defaultNumberingSystem(locale)) + SupportedValues.numberingSystems
        "hc" -> listOf(null, "h11", "h12", "h23", "h24")
        else -> listOf(null)
    }

    private fun readComponents(options: JSObject, dtf: JSIntlDateTimeFormat) {
        val out = LinkedHashMap<String, String>()
        for ((prop, values) in DtfTables.COMPONENTS) {
            val v: String? = if (values == null) {
                Opt.number(options, prop, 1, 3, null)?.toString()
            } else Opt.string(options, prop, values, null) as String?
            if (v != null) out[prop] = v
        }
        dtf.formatOptions = out
    }

    private fun hasExplicitComponents(dtf: JSIntlDateTimeFormat) = dtf.formatOptions.isNotEmpty()

    private fun explicitName(dtf: JSIntlDateTimeFormat) = dtf.formatOptions.keys.first()

    // ------------------------------------------------------------------ time zones

    fun readTimeZone(options: JSObject, toLocaleStringTimeZone: String? = null): String {
        val v = options.get("timeZone", options)
        val s = if (v === Undefined) {
            toLocaleStringTimeZone ?: return systemTimeZone()
        } else {
            if (toLocaleStringTimeZone != null) typeErr("The timeZone option cannot be used with ZonedDateTime.prototype.toLocaleString")
            Ops.toString(v)
        }
        if (s.isNotEmpty() && (s[0] == '+' || s[0] == '-')) return parseOffset(s) ?: rangeErr("Invalid time zone specified: $s")
        if (s.uppercase() in NON_IANA) rangeErr("Invalid time zone specified: $s")
        val rec = TimeZones.find(s) ?: rangeErr("Invalid time zone specified: $s")
        return rec.first
    }

    /** UTCOffset with at most hours and minutes ("+HH", "+HHMM", "+HH:MM") -> FormatOffsetTimeZoneIdentifier. */
    fun parseOffset(s: String): String? {
        fun d2(i: Int): Int = if (i + 1 < s.length && LanguageTag.isDigit(s[i]) && LanguageTag.isDigit(s[i + 1])) (s[i] - '0') * 10 + (s[i + 1] - '0') else -1
        val sign = if (s[0] == '-') -1 else 1
        val h = d2(1)
        if (h !in 0..23) return null
        val m = when (s.length) {
            3 -> 0
            5 -> d2(3)
            6 -> if (s[3] == ':') d2(4) else -1
            else -> -1
        }
        if (m !in 0..59) return null
        return formatOffset(sign * (h * 60 + m))
    }

    fun formatOffset(minutes: Int): String {
        val sign = if (minutes < 0) '-' else '+'
        val a = abs(minutes)
        return "$sign${(a / 60).toString().padStart(2, '0')}:${(a % 60).toString().padStart(2, '0')}"
    }

    /** SystemTimeZoneIdentifier: the configured (or host) zone as a primary identifier or offset identifier. */
    fun systemTimeZone(): String {
        val z = dev.mooner.neonjs.builtins.DateTime.zone
        if (z is java.time.ZoneOffset) return offsetId(z)
        TimeZones.find(z.id)?.let { return it.second }
        val n = try { z.normalized() } catch (_: Exception) { null }
        if (n is java.time.ZoneOffset) return offsetId(n)
        return "UTC"
    }

    private fun offsetId(o: java.time.ZoneOffset): String = if (o.totalSeconds == 0) "UTC" else formatOffset(o.totalSeconds / 60)

    // ------------------------------------------------------------------ Temporal format records

    private val YEAR_MONTH = listOf("year", "month")
    private val MONTH_DAY = listOf("month", "day")
    private val ALL_FIELDS = DtfTables.DATE_FIELDS + DtfTables.TIME_FIELDS
    private val ANY_PRESENT = listOf("weekday", "year", "month", "day", "dayPeriod", "hour", "minute", "second", "fractionalSecondDigits")

    /** GetDateTimeFormat: the component record for [required]/[defaults], or null (no relevant fields). */
    fun getDateTimeFormat(opts: Map<String, String>, required: String, defaults: String, inherit: String): Map<String, String>? {
        val requiredOptions = when (required) {
            "date" -> DtfTables.DATE_FIELDS
            "time" -> DtfTables.TIME_FIELDS
            "year-month" -> YEAR_MONTH
            "month-day" -> MONTH_DAY
            else -> ALL_FIELDS
        }
        val defaultOptions = when (defaults) {
            "date" -> listOf("year", "month", "day")
            "time" -> listOf("hour", "minute", "second")
            "year-month" -> YEAR_MONTH
            "month-day" -> MONTH_DAY
            else -> listOf("year", "month", "day", "hour", "minute", "second")
        }
        val formatOptions = LinkedHashMap<String, String>()
        if (inherit == "all") formatOptions.putAll(opts)
        else if (required == "date" || required == "year-month" || required == "any") opts["era"]?.let { formatOptions["era"] = it }
        val anyPresent = ANY_PRESENT.any { opts[it] != null }
        var needDefaults = true
        for (p in requiredOptions) {
            val v = opts[p] ?: continue
            formatOptions[p] = v
            needDefaults = false
        }
        if (needDefaults) {
            if (anyPresent && inherit == "relevant") return null
            for (p in defaultOptions) formatOptions[p] = "numeric"
        }
        return formatOptions
    }

    /** The format record used for a Temporal value of [kind] (null: no overlapping fields, a TypeError). */
    fun temporalFormat(dtf: JSIntlDateTimeFormat, kind: String): DtfFormat? {
        if (dtf.temporalFormats.containsKey(kind)) return dtf.temporalFormats[kind]
        val result = try {
            computeTemporalFormat(dtf, kind)
        } catch (e: JSException) {
            throw e
        } catch (e: RuntimeException) {
            if (e is TerminationException) throw e
            rangeErr("Unable to create the date-time format")
        }
        dtf.temporalFormats[kind] = result
        return result
    }

    private fun computeTemporalFormat(dtf: JSIntlDateTimeFormat, kind: String): DtfFormat? {
        val icu = DtfIcu.of(dtf)
        val result: DtfFormat? = if (dtf.dateStyle != null || dtf.timeStyle != null) {
            if (kind == "instant") dtf.format
            else {
                val allowed = when (kind) {
                    "date" -> listOf("weekday", "era", "year", "month", "day")
                    "year-month" -> listOf("era", "year", "month")
                    "month-day" -> MONTH_DAY
                    "time" -> DtfTables.TIME_FIELDS
                    else -> listOf("weekday", "era", "year", "month", "day") + DtfTables.TIME_FIELDS
                }
                val adjusted = LinkedHashMap<String, String>()
                for ((k, v) in dtf.format.fields) if (k in allowed) adjusted[k] = v
                when {
                    adjusted.isEmpty() -> null
                    adjusted.size == dtf.format.fields.size -> dtf.format
                    else -> DtfData.formatFor(icu.gen, adjusted, dtf.hc)
                }
            }
        } else {
            val comps = when (kind) {
                "date" -> getDateTimeFormat(dtf.formatOptions, "date", "date", "relevant")
                "year-month" -> getDateTimeFormat(dtf.formatOptions, "year-month", "year-month", "relevant")
                "month-day" -> getDateTimeFormat(dtf.formatOptions, "month-day", "month-day", "relevant")
                "time" -> getDateTimeFormat(dtf.formatOptions, "time", "time", "relevant")
                "datetime" -> getDateTimeFormat(dtf.formatOptions, "any", "all", "relevant")
                else -> getDateTimeFormat(dtf.formatOptions, "any", "all", "all")
            }
            if (comps == null) null else DtfData.formatFor(icu.gen, comps, dtf.hc)
        }
        return result
    }
}
