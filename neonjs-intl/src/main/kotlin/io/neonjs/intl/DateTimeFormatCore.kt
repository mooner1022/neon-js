package io.neonjs.intl

import com.ibm.icu.text.DateFormat
import com.ibm.icu.text.DateTimePatternGenerator
import com.ibm.icu.text.NumberingSystem
import com.ibm.icu.text.SimpleDateFormat
import com.ibm.icu.util.Calendar
import com.ibm.icu.util.GregorianCalendar
import com.ibm.icu.util.ULocale
import io.neonjs.runtime.*

/**
 * A resolved date-time format ("format record"): the component values (in table order) and the ICU pattern that
 * produces them. [skeleton] is used for range formatting.
 */
internal class DtfFormat(val fields: LinkedHashMap<String, String>, val pattern: String, val skeleton: String) {
    val hasHour: Boolean get() = "hour" in fields
}

/** Intl.DateTimeFormat instance (`[[InitializedDateTimeFormat]]`). */
internal class JSIntlDateTimeFormat(proto: JSObject?) : JSObject(proto) {
    @JvmField var locale = ""
    @JvmField var dataLocale = ""
    @JvmField var calendar = "gregory"
    @JvmField var numberingSystem = "latn"
    @JvmField var timeZone = "UTC"
    @JvmField var hourCycle: String? = null
    @JvmField var dateStyle: String? = null
    @JvmField var timeStyle: String? = null
    @JvmField var boundFormat: JSObject? = null

    /** `[[DateTimeFormat]]` */
    lateinit var format: DtfFormat

    /** The format options record of CreateDateTimeFormat (components requested by the caller) and its hc. */
    var formatOptions: Map<String, String> = emptyMap()
    var hc: String? = null
    var formatMatcher = "best fit"

    /** ICU objects, created on first use; owned by this instance (never shared, they are not thread-safe). */
    var icu: DtfIcu? = null

    /** Lazily computed `[[TemporalPlain...Format]]` records (null entry = no overlapping fields). */
    val temporalFormats = HashMap<String, DtfFormat?>()
}

/** Option tables of CreateDateTimeFormat (Table 15, in table order). */
internal object DtfTables {
    val COMPONENTS: List<Pair<String, Array<String>?>> = listOf(
        "weekday" to arrayOf("narrow", "short", "long"),
        "era" to arrayOf("narrow", "short", "long"),
        "year" to arrayOf("2-digit", "numeric"),
        "month" to arrayOf("2-digit", "numeric", "narrow", "short", "long"),
        "day" to arrayOf("2-digit", "numeric"),
        "dayPeriod" to arrayOf("narrow", "short", "long"),
        "hour" to arrayOf("2-digit", "numeric"),
        "minute" to arrayOf("2-digit", "numeric"),
        "second" to arrayOf("2-digit", "numeric"),
        "fractionalSecondDigits" to null,
        "timeZoneName" to arrayOf("short", "long", "shortOffset", "longOffset", "shortGeneric", "longGeneric"),
    )
    val ORDER: List<String> = COMPONENTS.map { it.first }
    val STYLES = arrayOf("full", "long", "medium", "short")
    val HOUR_CYCLES = arrayOf("h11", "h12", "h23", "h24")
    val MATCHERS = arrayOf("basic", "best fit")

    val DATE_FIELDS = listOf("weekday", "year", "month", "day")
    val TIME_FIELDS = listOf("dayPeriod", "hour", "minute", "second", "fractionalSecondDigits")
}

/** Locale data and pattern generation for DateTimeFormat (ICU; runs only once a DateTimeFormat is created). */
internal object DtfData {
    fun icuLocale(dataLocale: String, calendar: String, nu: String): ULocale =
        ULocale.forLanguageTag("$dataLocale-u-ca-$calendar-nu-$nu")

    /** Immutable per-locale defaults (default calendar, numbering system, hour cycles); bounded, thread-safe. */
    private class LocaleDefaults(val calendar: String, val nu: String, val hourCycles: Triple<String, String, String>)

    private val defaultsCache = java.util.concurrent.ConcurrentHashMap<String, LocaleDefaults>()

    private fun defaults(dataLocale: String): LocaleDefaults {
        defaultsCache[dataLocale]?.let { return it }
        val d = LocaleDefaults(computeDefaultCalendar(dataLocale), computeDefaultNumberingSystem(dataLocale), computeHourCycles(dataLocale))
        if (defaultsCache.size >= 512) defaultsCache.clear()
        defaultsCache[dataLocale] = d
        return d
    }

    fun defaultCalendar(dataLocale: String): String = defaults(dataLocale).calendar

    fun defaultNumberingSystem(dataLocale: String): String = defaults(dataLocale).nu

    /** (default hc, hc for hour12 = true, hc for hour12 = false) of a data locale. */
    fun hourCycles(dataLocale: String): Triple<String, String, String> = defaults(dataLocale).hourCycles

    private fun computeDefaultCalendar(dataLocale: String): String {
        val values = try {
            Calendar.getKeywordValuesForLocale("calendar", ULocale.forLanguageTag(dataLocale), true)
        } catch (_: Exception) {
            return "gregory"
        }
        val ca = values.firstOrNull()?.let { LocaleInfo.calendarToBcp47(it) } ?: "gregory"
        return if (SupportedValues.isSupportedCalendar(ca)) ca else "gregory"
    }

    private fun computeDefaultNumberingSystem(dataLocale: String): String {
        return try {
            val ns = NumberingSystem.getInstance(ULocale.forLanguageTag(dataLocale))
            if (!ns.isAlgorithmic && SupportedValues.isSupportedNumberingSystem(ns.name)) ns.name else "latn"
        } catch (_: Exception) {
            "latn"
        }
    }

    private fun computeHourCycles(dataLocale: String): Triple<String, String, String> {
        val gen = DateTimePatternGenerator.getInstance(ULocale.forLanguageTag(dataLocale))
        val def = when (gen.defaultHourCycle) {
            DateFormat.HourCycle.HOUR_CYCLE_11 -> "h11"
            DateFormat.HourCycle.HOUR_CYCLE_12 -> "h12"
            DateFormat.HourCycle.HOUR_CYCLE_24 -> "h24"
            else -> "h23"
        }
        val hc12 = when (def) {
            "h11", "h12" -> def
            else -> if (hourCharOf(gen.getBestPattern("h")) == 'K') "h11" else "h12"
        }
        val hc24 = if (def == "h24") "h24" else "h23"
        return Triple(def, hc12, hc24)
    }

    // ------------------------------------------------------------------ patterns

    /** Iterates the pattern letters outside quoted literals: (index, char). */
    private inline fun forEachPatternLetter(pattern: String, f: (Int, Char) -> Unit) {
        var quoted = false
        for (i in pattern.indices) {
            val c = pattern[i]
            if (c == '\'') quoted = !quoted
            else if (!quoted && ((c in 'a'..'z') || (c in 'A'..'Z'))) f(i, c)
        }
    }

    fun hourCharOf(pattern: String): Char? {
        forEachPatternLetter(pattern) { _, c -> if (c == 'h' || c == 'H' || c == 'k' || c == 'K') return c }
        return null
    }

    private fun hcChar(hc: String): Char = when (hc) {
        "h11" -> 'K'
        "h12" -> 'h'
        "h24" -> 'k'
        else -> 'H'
    }

    /** Replaces the hour letters of [pattern] by the letter of [hc] (same 12/24-hour family assumed). */
    fun replaceHourChars(pattern: String, hc: String): String {
        val target = hcChar(hc)
        val sb = StringBuilder(pattern)
        forEachPatternLetter(pattern) { i, c -> if (c == 'h' || c == 'H' || c == 'k' || c == 'K') sb.setCharAt(i, target) }
        return sb.toString()
    }

    private fun is12(c: Char) = c == 'h' || c == 'K'

    /** Makes a pattern use hour cycle [hc]: replaces hour letters, regenerating the pattern if the family changes. */
    fun applyHourCycle(gen: DateTimePatternGenerator, pattern: String, hc: String?): String {
        if (hc == null) return pattern
        val cur = hourCharOf(pattern) ?: return pattern
        val want = hcChar(hc)
        if (is12(cur) == is12(want)) return replaceHourChars(pattern, hc)
        val skel = StringBuilder()
        for (c in gen.getSkeleton(pattern)) {
            when (c) {
                'a', 'b', 'B' -> {}
                'h', 'H', 'k', 'K' -> skel.append(if (is12(want)) 'h' else 'H')
                else -> skel.append(c)
            }
        }
        return replaceHourChars(gen.getBestPattern(skel.toString(), DateTimePatternGenerator.MATCH_HOUR_FIELD_LENGTH), hc)
    }

    /** Skeleton for a component record (hour letter chosen from [hc]). */
    fun skeletonOf(comps: Map<String, String>, hc: String?): String {
        val sb = StringBuilder()
        fun rep(c: Char, n: Int) { repeat(n) { sb.append(c) } }
        when (comps["era"]) { "narrow" -> rep('G', 5); "short" -> rep('G', 1); "long" -> rep('G', 4) }
        when (comps["year"]) { "2-digit" -> rep('y', 2); "numeric" -> rep('y', 1) }
        when (comps["month"]) {
            "numeric" -> rep('M', 1); "2-digit" -> rep('M', 2); "short" -> rep('M', 3); "long" -> rep('M', 4); "narrow" -> rep('M', 5)
        }
        when (comps["weekday"]) { "narrow" -> rep('E', 5); "short" -> rep('E', 3); "long" -> rep('E', 4) }
        when (comps["day"]) { "2-digit" -> rep('d', 2); "numeric" -> rep('d', 1) }
        when (comps["dayPeriod"]) { "narrow" -> rep('B', 5); "short" -> rep('B', 1); "long" -> rep('B', 4) }
        val hourChar = when (hc) { null -> 'j'; "h11", "h12" -> 'h'; else -> 'H' }
        when (comps["hour"]) { "2-digit" -> rep(hourChar, 2); "numeric" -> rep(hourChar, 1) }
        when (comps["minute"]) { "2-digit" -> rep('m', 2); "numeric" -> rep('m', 1) }
        when (comps["second"]) { "2-digit" -> rep('s', 2); "numeric" -> rep('s', 1) }
        comps["fractionalSecondDigits"]?.let { rep('S', it.toInt()) }
        when (comps["timeZoneName"]) {
            "short" -> rep('z', 1); "long" -> rep('z', 4); "shortOffset" -> rep('O', 1); "longOffset" -> rep('O', 4)
            "shortGeneric" -> rep('v', 1); "longGeneric" -> rep('v', 4)
        }
        return sb.toString()
    }

    /** The component values a pattern displays (Table 15 order). */
    fun fieldsOfPattern(pattern: String): LinkedHashMap<String, String> {
        val found = HashMap<String, String>()
        var i = 0
        var quoted = false
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '\'') { quoted = !quoted; i++; continue }
            if (quoted || !((c in 'a'..'z') || (c in 'A'..'Z'))) { i++; continue }
            var n = 1
            while (i + n < pattern.length && pattern[i + n] == c) n++
            i += n
            fieldOf(c, n)?.let { (k, v) -> found.putIfAbsent(k, v) }
        }
        val out = LinkedHashMap<String, String>()
        for (k in DtfTables.ORDER) found[k]?.let { out[k] = it }
        return out
    }

    private fun textWidth(n: Int): String = when (n) {
        4 -> "long"
        5 -> "narrow"
        else -> "short"
    }

    private fun fieldOf(c: Char, n: Int): Pair<String, String>? = when (c) {
        'G' -> "era" to textWidth(n)
        'y', 'Y', 'u', 'U', 'r' -> "year" to (if (n == 2 && c == 'y') "2-digit" else "numeric")
        'M', 'L' -> "month" to when (n) { 1 -> "numeric"; 2 -> "2-digit"; 3 -> "short"; 4 -> "long"; else -> "narrow" }
        'd' -> "day" to (if (n == 2) "2-digit" else "numeric")
        'E' -> "weekday" to (if (n == 6) "short" else textWidth(n))
        'c', 'e' -> if (n >= 3) "weekday" to (if (n == 6) "short" else textWidth(n)) else null
        'B' -> "dayPeriod" to textWidth(n)
        'h', 'H', 'k', 'K' -> "hour" to (if (n == 2) "2-digit" else "numeric")
        'm' -> "minute" to (if (n == 2) "2-digit" else "numeric")
        's' -> "second" to (if (n == 2) "2-digit" else "numeric")
        'S' -> "fractionalSecondDigits" to minOf(n, 3).toString()
        'z' -> "timeZoneName" to (if (n == 4) "long" else "short")
        'O' -> "timeZoneName" to (if (n == 4) "longOffset" else "shortOffset")
        'v' -> "timeZoneName" to (if (n == 4) "longGeneric" else "shortGeneric")
        'V', 'Z', 'X', 'x' -> "timeZoneName" to "short"
        else -> null
    }

    /** BasicFormatMatcher / BestFitFormatMatcher (both via ICU's pattern generator) for a component record. */
    fun formatFor(gen: DateTimePatternGenerator, comps: Map<String, String>, hc: String?): DtfFormat {
        val skeleton = skeletonOf(comps, hc)
        var pattern = gen.getBestPattern(skeleton, DateTimePatternGenerator.MATCH_HOUR_FIELD_LENGTH)
        if (hc != null) pattern = replaceHourChars(pattern, hc)
        val fields = fieldsOfPattern(pattern)
        if ("dayPeriod" !in comps) fields.remove("dayPeriod")
        return DtfFormat(fields, pattern, skeleton)
    }

    private fun icuStyle(s: String): Int = when (s) {
        "full" -> DateFormat.FULL
        "long" -> DateFormat.LONG
        "medium" -> DateFormat.MEDIUM
        else -> DateFormat.SHORT
    }

    /** DateTimeStyleFormat */
    fun styleFormat(gen: DateTimePatternGenerator, uloc: ULocale, dateStyle: String?, timeStyle: String?, hc: String?): DtfFormat {
        val datePattern = if (dateStyle != null) (DateFormat.getDateInstance(icuStyle(dateStyle), uloc) as SimpleDateFormat).toPattern() else null
        val timePattern = if (timeStyle != null) {
            applyHourCycle(gen, (DateFormat.getTimeInstance(icuStyle(timeStyle), uloc) as SimpleDateFormat).toPattern(), hc)
        } else null
        val pattern = when {
            datePattern != null && timePattern != null -> {
                val glue = gen.getDateTimeFormat(icuStyle(dateStyle!!))
                glue.replace("{1}", datePattern).replace("{0}", timePattern)
            }
            datePattern != null -> datePattern
            else -> timePattern!!
        }
        val skeleton = gen.getSkeleton(pattern)
        return DtfFormat(fieldsOfPattern(pattern), pattern, skeleton)
    }

    /** A calendar for formatting: the locale's calendar type, proleptic Gregorian where applicable. */
    fun newCalendar(uloc: ULocale, zone: com.ibm.icu.util.TimeZone): Calendar {
        val cal = Calendar.getInstance(zone, uloc)
        if (cal is GregorianCalendar) cal.gregorianChange = java.util.Date(Long.MIN_VALUE)
        cal.isLenient = true
        return cal
    }
}
