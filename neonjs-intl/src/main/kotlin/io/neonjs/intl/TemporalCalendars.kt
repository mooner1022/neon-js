package io.neonjs.intl

import com.ibm.icu.util.Calendar
import com.ibm.icu.util.ChineseCalendar
import com.ibm.icu.util.DangiCalendar
import com.ibm.icu.util.IslamicCalendar
import com.ibm.icu.util.PersianCalendar
import com.ibm.icu.util.TimeZone
import com.ibm.icu.util.ULocale
import io.neonjs.builtins.temporal.CalendarYearInfo
import io.neonjs.builtins.temporal.TemporalCalendarProvider
import io.neonjs.builtins.temporal.TemporalCalendarSource
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Temporal's non-ISO calendars (ECMA-402 / Intl era and monthCode proposal) and IANA time zone link resolution.
 * Registered through META-INF/services/io.neonjs.builtins.temporal.TemporalCalendarProvider.
 *
 * Arithmetic calendars are computed in closed form here; ICU4J supplies the data-driven ones: "persian" (Iranian
 * authority leap years), "islamic-umalqura" (KACST table, 1300–1600 AH) and "chinese" / "dangi" (astronomical
 * computation, used for related ISO years [ChineseSource.ICU_FIRST, ChineseSource.ICU_LAST]). ICU calendars are
 * created per computation and never shared between threads. ICU's ChineseCalendar and HebrewCalendar caches
 * (com.ibm.icu.impl.CalendarCache) can loop forever on negative keys, so neither is ever asked about a year <= 0.
 */
class IcuTemporalCalendarProvider : TemporalCalendarProvider {
    override fun calendarIds(): Collection<String> = IDS

    override fun calendarSource(calendarId: String): TemporalCalendarSource? = SOURCES[calendarId]

    override fun primaryTimeZoneId(id: String): String? {
        val iana = try {
            TimeZone.getIanaID(id)
        } catch (_: RuntimeException) {
            null
        } ?: return null
        return if (iana == TimeZone.UNKNOWN_ZONE_ID) null else iana
    }

    private companion object {
        val IDS = listOf(
            "buddhist", "chinese", "coptic", "dangi", "ethioaa", "ethiopic", "gregory", "hebrew", "indian", "islamic-civil",
            "islamic-tbla", "islamic-umalqura", "japanese", "persian", "roc",
        )

        val SOURCES: Map<String, TemporalCalendarSource> = hashMapOf(
            "gregory" to GregorianSource(0),
            "japanese" to GregorianSource(0),
            "buddhist" to GregorianSource(543),
            "roc" to GregorianSource(-1911),
            "coptic" to CopticSource(CopticSource.COPTIC_EPOCH, 0),
            "ethiopic" to CopticSource(CopticSource.ETHIOPIC_EPOCH, 0),
            "ethioaa" to CopticSource(CopticSource.ETHIOPIC_EPOCH, 5500),
            "indian" to IndianSource(),
            "islamic-civil" to TabularIslamicSource(TabularIslamicSource.CIVIL_EPOCH),
            "islamic-tbla" to TabularIslamicSource(TabularIslamicSource.CIVIL_EPOCH - 1),
            "islamic-umalqura" to UmalquraSource(),
            "hebrew" to HebrewSource(),
            "persian" to PersianSource(),
            "chinese" to ChineseSource(false),
            "dangi" to ChineseSource(true),
        )
    }
}

/** Proleptic Gregorian arithmetic on epoch days (days since 1970-01-01). */
internal object Civil {
    /** Epoch day of a proleptic Gregorian date. */
    fun epochDay(y: Long, m: Int, d: Int): Long {
        val yy = if (m <= 2) y - 1 else y
        val era = Math.floorDiv(yy, 400L)
        val yoe = yy - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    /** Proleptic Gregorian year of an epoch day. */
    fun yearOf(day: Long): Long {
        val z = day + 719468
        val era = Math.floorDiv(z, 146097L)
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val m = if (mp < 10) mp + 3 else mp - 9
        return yoe + era * 400 + (if (m <= 2) 1 else 0)
    }

    fun isLeap(y: Long): Boolean = y % 4 == 0L && (y % 100 != 0L || y % 400 == 0L)

    /** R.D. (Calendrical Calculations fixed day, R.D. 1 = 0001-01-01) to epoch day. */
    const val RD_TO_EPOCH = -719163L

    /** Julian day number of epoch day 0 (1970-01-01): epoch day = `jd - JD_EPOCH`. */
    const val JD_EPOCH = 2440588L

    fun info(starts: LongArray, codes: IntArray, leap: Boolean) = CalendarYearInfo(starts, codes, leap)

    /** Month codes "M01".."M[n]". */
    fun plainCodes(n: Int): IntArray = IntArray(n) { (it + 1) shl 1 }

    val CODES_12 = plainCodes(12)
    val CODES_13 = plainCodes(13)
}

/** "gregory", "japanese" (arithmetic year = ISO year), "buddhist" (ISO + 543), "roc" (ISO - 1911). */
internal class GregorianSource(private val offset: Int) : TemporalCalendarSource {
    override fun yearInfo(year: Int): CalendarYearInfo {
        val iso = year.toLong() - offset
        val starts = LongArray(13)
        for (m in 1..12) starts[m - 1] = Civil.epochDay(iso, m, 1)
        starts[12] = Civil.epochDay(iso + 1, 1, 1)
        return Civil.info(starts, Civil.CODES_12, Civil.isLeap(iso))
    }

    override fun monthsBeforeYear(year: Int): Long = 12L * year

    override fun estimateYear(epochDay: Long): Int = (Civil.yearOf(epochDay) + offset).toInt()
}

/** "coptic", "ethiopic" and "ethioaa": twelve 30-day months and a 5- or 6-day thirteenth, leap when year % 4 == 3. */
internal class CopticSource(private val epochRd: Long, private val yearOffset: Int) : TemporalCalendarSource {
    private fun startOfYear(y: Long): Long = epochRd + Civil.RD_TO_EPOCH + 365 * (y - 1) + Math.floorDiv(y, 4L)

    override fun yearInfo(year: Int): CalendarYearInfo {
        val y = year.toLong() - yearOffset
        val s = startOfYear(y)
        val starts = LongArray(14)
        for (m in 0 until 13) starts[m] = s + 30 * m
        starts[13] = startOfYear(y + 1)
        return Civil.info(starts, Civil.CODES_13, Math.floorMod(y, 4L) == 3L)
    }

    override fun monthsBeforeYear(year: Int): Long = 13L * year

    override fun estimateYear(epochDay: Long): Int =
        (Math.floorDiv((epochDay - (epochRd + Civil.RD_TO_EPOCH)) * 4, 1461L) + 1 + yearOffset).toInt()

    companion object {
        /** R.D. of 1 Thout 1 AM (Julian 284-08-29). */
        const val COPTIC_EPOCH = 103605L
        /** R.D. of 1 Maskaram 1 (Amete Mihret, Julian 8-08-29). */
        const val ETHIOPIC_EPOCH = 2796L
    }
}

/** "indian" (Saka): year Y starts on Gregorian March 22 (21 in leap years) of Y + 78. */
internal class IndianSource : TemporalCalendarSource {
    override fun yearInfo(year: Int): CalendarYearInfo {
        val g = year.toLong() + 78
        val leap = Civil.isLeap(g)
        val starts = LongArray(13)
        var s = Civil.epochDay(g, 3, if (leap) 21 else 22)
        for (m in 1..12) {
            starts[m - 1] = s
            s += when {
                m == 1 -> if (leap) 31 else 30
                m <= 6 -> 31
                else -> 30
            }
        }
        starts[12] = Civil.epochDay(g + 1, 3, if (Civil.isLeap(g + 1)) 21 else 22)
        return Civil.info(starts, Civil.CODES_12, leap)
    }

    override fun monthsBeforeYear(year: Int): Long = 12L * year

    override fun estimateYear(epochDay: Long): Int = (Civil.yearOf(epochDay) - 78).toInt()
}

/** Tabular Hijri calendar (leap years 2, 5, 7, 10, 13, 16, 18, 21, 24, 26, 29 of 30). */
internal class TabularIslamicSource(private val epochRd: Long) : TemporalCalendarSource {
    fun startOfYear(y: Long): Long = epochRd + Civil.RD_TO_EPOCH - 1 + (y - 1) * 354 + Math.floorDiv(3 + 11 * y, 30L) + 1

    fun isLeap(y: Long): Boolean = Math.floorMod(14 + 11 * y, 30L) < 11

    override fun yearInfo(year: Int): CalendarYearInfo {
        val y = year.toLong()
        val starts = LongArray(13)
        var s = startOfYear(y)
        for (m in 1..12) {
            starts[m - 1] = s
            s += if (m % 2 == 1) 30 else 29
        }
        starts[12] = startOfYear(y + 1)
        return Civil.info(starts, Civil.CODES_12, isLeap(y))
    }

    override fun monthsBeforeYear(year: Int): Long = 12L * year

    override fun estimateYear(epochDay: Long): Int =
        Math.floorDiv((epochDay - (epochRd + Civil.RD_TO_EPOCH)) * 30, 10631L).toInt() + 1

    companion object {
        /** R.D. of 1 Muharram 1 AH, civil epoch (Friday, Julian 622-07-16). */
        const val CIVIL_EPOCH = 227015L
    }
}

/** "islamic-umalqura": ICU's KACST table for 1300–1600 AH, "islamic-civil" outside that range. */
internal class UmalquraSource : TemporalCalendarSource {
    private val civil = TabularIslamicSource(TabularIslamicSource.CIVIL_EPOCH)

    private fun icuStart(c: IslamicCalendar, y: Int, m: Int): Long {
        c.clear()
        c.set(Calendar.EXTENDED_YEAR, y)
        c.set(Calendar.ORDINAL_MONTH, m)
        c.set(Calendar.DAY_OF_MONTH, 1)
        return c.get(Calendar.JULIAN_DAY) - Civil.JD_EPOCH
    }

    private fun newCalendar(): IslamicCalendar {
        val c = IslamicCalendar(TimeZone.GMT_ZONE, ULocale.ROOT)
        c.calculationType = IslamicCalendar.CalculationType.ISLAMIC_UMALQURA
        return c
    }

    override fun yearInfo(year: Int): CalendarYearInfo {
        if (year < FIRST - 1 || year > LAST) return civil.yearInfo(year)
        val c = newCalendar()
        if (year == FIRST - 1) {
            // the civil year before the table ends where the table's first year starts
            val inf = civil.yearInfo(year)
            val starts = inf.monthStarts.copyOf()
            starts[12] = icuStart(c, FIRST, 0)
            return Civil.info(starts, Civil.CODES_12, starts[12] - starts[0] > 354)
        }
        val starts = LongArray(13)
        for (m in 0 until 12) starts[m] = icuStart(c, year, m)
        starts[12] = if (year == LAST) civil.startOfYear(LAST + 1L) else icuStart(c, year + 1, 0)
        return Civil.info(starts, Civil.CODES_12, starts[12] - starts[0] > 354)
    }

    override fun monthsBeforeYear(year: Int): Long = 12L * year

    override fun estimateYear(epochDay: Long): Int = civil.estimateYear(epochDay)

    companion object {
        const val FIRST = 1300
        const val LAST = 1600
    }
}

/** "persian": ICU4J (33-year rule corrected by the Iranian calendar authority's table). */
internal class PersianSource : TemporalCalendarSource {
    private fun start(c: Calendar, y: Int): Long {
        c.clear()
        c.set(Calendar.EXTENDED_YEAR, y)
        c.set(Calendar.ORDINAL_MONTH, 0)
        c.set(Calendar.DAY_OF_MONTH, 1)
        return c.get(Calendar.JULIAN_DAY) - Civil.JD_EPOCH
    }

    override fun yearInfo(year: Int): CalendarYearInfo {
        @Suppress("DEPRECATION") // ICU marks this constructor internal-only; it still builds the calendar directly
        val c = PersianCalendar(TimeZone.GMT_ZONE, ULocale.ROOT)
        val s = start(c, year)
        val e = start(c, year + 1)
        val starts = LongArray(13)
        var d = s
        for (m in 1..12) {
            starts[m - 1] = d
            d += if (m <= 6) 31 else 30
        }
        starts[12] = e
        return Civil.info(starts, Civil.CODES_12, e - s > 365)
    }

    override fun monthsBeforeYear(year: Int): Long = 12L * year

    override fun estimateYear(epochDay: Long): Int = (Civil.yearOf(epochDay) - 621).toInt()
}

/** "hebrew": the arithmetic (molad and dehiyyot) calendar of Calendrical Calculations. */
internal class HebrewSource : TemporalCalendarSource {
    private fun isLeap(y: Long): Boolean = Math.floorMod(7 * y + 1, 19L) < 7

    private fun monthsElapsed(y: Long): Long = Math.floorDiv(235 * y - 234, 19L)

    private fun elapsedDays(y: Long): Long {
        val months = monthsElapsed(y)
        val parts = 12084 + 13753 * months
        val days = 29 * months + Math.floorDiv(parts, 25920L)
        return if (Math.floorMod(3 * (days + 1), 7L) < 3) days + 1 else days
    }

    private fun yearLengthCorrection(y: Long): Int {
        val ny0 = elapsedDays(y - 1)
        val ny1 = elapsedDays(y)
        val ny2 = elapsedDays(y + 1)
        return when {
            ny2 - ny1 == 356L -> 2
            ny1 - ny0 == 382L -> 1
            else -> 0
        }
    }

    private fun newYear(y: Long): Long = EPOCH + Civil.RD_TO_EPOCH + elapsedDays(y) + yearLengthCorrection(y)

    override fun yearInfo(year: Int): CalendarYearInfo {
        val y = year.toLong()
        val s = newYear(y)
        val e = newYear(y + 1)
        val length = (e - s).toInt()
        val leap = isLeap(y)
        val longHeshvan = length % 10 == 5
        val shortKislev = length % 10 == 3
        val codes = if (leap) LEAP_CODES else Civil.CODES_12
        val starts = LongArray(codes.size + 1)
        var d = s
        for (i in codes.indices) {
            starts[i] = d
            d += when (codes[i]) {
                M02 -> if (longHeshvan) 30 else 29
                M03 -> if (shortKislev) 29 else 30
                M05L -> 30
                else -> if ((codes[i] shr 1) % 2 == 1) 30 else 29
            }
        }
        starts[codes.size] = e
        return Civil.info(starts, codes, leap)
    }

    override fun monthsBeforeYear(year: Int): Long = monthsElapsed(year.toLong())

    override fun estimateYear(epochDay: Long): Int =
        (floor((epochDay - (EPOCH + Civil.RD_TO_EPOCH)) / 365.2468) + 1).toInt()

    companion object {
        /** R.D. of 1 Tishri 1 AM (Julian -3761-10-07). */
        const val EPOCH = -1373427L
        const val M02 = 4
        const val M03 = 6
        const val M05L = 11
        val LEAP_CODES = intArrayOf(2, 4, 6, 8, 10, 11, 12, 14, 16, 18, 20, 22, 24)
    }
}

/**
 * "chinese" and "dangi". ICU4J's astronomical calendar for related ISO years [ICU_FIRST, ICU_LAST] (which include
 * the 1900–2100 range of published data); outside, an implementation-defined approximation using mean lunations and
 * mean principal solar terms with the modern leap-month rule. The month index of a year is the number of the mean
 * lunation its new year falls in, which is exact for ICU's true new moons too (they stay within two days of the
 * mean), so month arithmetic is consistent across the boundary.
 */
internal class ChineseSource(private val korean: Boolean) : TemporalCalendarSource {
    /** UTC offset of the reference meridian, in days (Beijing +08:00, Seoul +09:00). */
    private val tz = if (korean) 9.0 / 24 else 8.0 / 24
    private val icuNewYears = ConcurrentHashMap<Int, Long>()

    // ---------------------------------------------------------------- mean model

    /** Local epoch-day time of mean new moon [k] (k = 0: 2000-01-06 14:20 UTC). */
    private fun meanNewMoon(k: Long): Double = NM0 + tz + k * SYN

    private fun newMoonDay(k: Long): Long = floor(meanNewMoon(k)).toLong()

    /** The lunation containing local day [day]. */
    private fun lunationOf(day: Long): Long {
        var k = floor((day + 0.5 - (NM0 + tz)) / SYN).toLong()
        while (newMoonDay(k + 1) <= day) k++
        while (newMoonDay(k) > day) k--
        return k
    }

    /** Local epoch-day time of the mean winter solstice of Gregorian year [y]. */
    private fun solstice(y: Int): Double = WS0 + tz + (y - 2000) * TROPICAL

    /** Whether lunation [k] contains a mean principal solar term. */
    private fun hasPrincipalTerm(k: Long): Boolean {
        val a = newMoonDay(k).toDouble()
        val b = newMoonDay(k + 1).toDouble()
        val step = TROPICAL / 12
        return ceil((b - (WS0 + tz)) / step) - ceil((a - (WS0 + tz)) / step) > 0
    }

    /** Lunation of the 11th month (containing the winter solstice) of Gregorian year [y]. */
    private fun month11(y: Int): Long = lunationOf(floor(solstice(y)).toLong())

    /** Month codes of the sui from the 11th month of [y] - 1 (inclusive) to that of [y] (exclusive). */
    private fun suiCodes(y: Int): IntArray {
        val k0 = month11(y - 1)
        val n = (month11(y) - k0).toInt()
        var leap = -1
        if (n == 13) {
            for (i in 1 until 13) {
                if (!hasPrincipalTerm(k0 + i)) {
                    leap = i
                    break
                }
            }
        }
        val codes = IntArray(n)
        var num = 11
        codes[0] = num shl 1
        for (i in 1 until n) {
            if (i == leap) {
                codes[i] = (num shl 1) or 1
            } else {
                num = num % 12 + 1
                codes[i] = num shl 1
            }
        }
        return codes
    }

    /** Lunation of the model's new year of [y]. */
    private fun modelNewYear(y: Int): Long {
        val codes = suiCodes(y)
        for (i in codes.indices) if (codes[i] == 2) return month11(y - 1) + i
        throw IllegalStateException("no first month")
    }

    private fun modelYearInfo(y: Int): CalendarYearInfo {
        val kStart = modelNewYear(y)
        val kEnd = modelNewYear(y + 1)
        val n = (kEnd - kStart).toInt()
        val split = month11(y)
        val sA = suiCodes(y)
        val sB = suiCodes(y + 1)
        val kA = month11(y - 1)
        val codes = IntArray(n)
        val starts = LongArray(n + 1)
        for (i in 0 until n) {
            val k = kStart + i
            codes[i] = if (k < split) sA[(k - kA).toInt()] else sB[(k - split).toInt()]
            starts[i] = newMoonDay(k)
        }
        starts[n] = newMoonDay(kEnd)
        // stitch to ICU's new year at the boundaries of the ICU range
        if (y == ICU_FIRST - 1) starts[n] = icuNewYear(ICU_FIRST)
        if (y == ICU_LAST + 1) starts[0] = icuNewYear(ICU_LAST + 1)
        return CalendarYearInfo(starts, codes, n == 13)
    }

    // ---------------------------------------------------------------- ICU

    @Suppress("DEPRECATION") // DangiCalendar's constructor is ICU-internal-only, like PersianCalendar's
    private fun newCalendar(): Calendar =
        if (korean) DangiCalendar(TimeZone.GMT_ZONE, ULocale.ROOT) else ChineseCalendar(TimeZone.GMT_ZONE, ULocale.ROOT)

    private fun icuMonthStart(c: Calendar, y: Int, m: Int): Long {
        c.clear()
        c.set(Calendar.EXTENDED_YEAR, y)
        c.set(Calendar.ORDINAL_MONTH, m)
        c.set(Calendar.DAY_OF_MONTH, 1)
        return c.get(Calendar.JULIAN_DAY) - Civil.JD_EPOCH
    }

    private fun icuNewYear(y: Int): Long {
        if (!korean && y in OFFICIAL_FIRST..OFFICIAL_LAST + 1) return Official.newYear(y)
        icuNewYears[y]?.let { return it }
        require(y >= 1) { "ICU Chinese calendar must not be used for year $y" }
        val v = icuMonthStart(newCalendar(), y, 0)
        if (icuNewYears.size > 4096) icuNewYears.clear()
        icuNewYears[y] = v
        return v
    }

    /**
     * Walks the months of year [y] day-wise (julian day → fields), which is more robust than ICU's field → julian
     * day direction (that one mislabels or merges months in some years, e.g. around the 2033 leap month).
     */
    private fun icuYearInfo(y: Int): CalendarYearInfo {
        val c = newCalendar()
        val end = icuNewYear(y + 1)
        val starts = LongArray(14)
        val codes = IntArray(13)
        var n = 0
        var s = icuNewYear(y)
        while (s < end && n < 13) {
            c.clear()
            c.set(Calendar.JULIAN_DAY, (s + Civil.JD_EPOCH).toInt())
            starts[n] = s
            codes[n] = parseCode(c.temporalMonthCode)
            n++
            // months have 29 or 30 days: the 30th day either is day 30 of this month or day 1 of the next
            c.clear()
            c.set(Calendar.JULIAN_DAY, (s + 29 + Civil.JD_EPOCH).toInt())
            s += if (c.get(Calendar.DAY_OF_MONTH) == 1) 29 else 30
        }
        starts[n] = end
        return CalendarYearInfo(starts.copyOf(n + 1), codes.copyOf(n), n == 13)
    }

    private fun parseCode(s: String): Int {
        val num = (s[1] - '0') * 10 + (s[2] - '0')
        return (num shl 1) or (if (s.length == 4) 1 else 0)
    }

    // ---------------------------------------------------------------- source

    override fun yearInfo(year: Int): CalendarYearInfo = when {
        !korean && year in OFFICIAL_FIRST..OFFICIAL_LAST -> Official.yearInfo(year)
        year in ICU_FIRST..ICU_LAST -> icuYearInfo(year)
        else -> modelYearInfo(year)
    }

    override fun monthsBeforeYear(year: Int): Long {
        if (year !in ICU_FIRST..ICU_LAST + 1) return modelNewYear(year)
        // true new moons stay within a couple of days of the mean ones: the nearest mean lunation is exact
        return Math.round((icuNewYear(year) + 0.5 - (NM0 + tz)) / SYN)
    }

    override fun estimateYear(epochDay: Long): Int = Civil.yearOf(epochDay).toInt()

    companion object {
        const val SYN = 29.530588861
        const val TROPICAL = 365.242189
        /** Mean new moon of 2000-01-06 (JD 2451550.09766), in epoch days, UTC. */
        const val NM0 = 2451550.09766 - 2440587.5
        /** Winter solstice of 2000 (2000-12-21 13:37 UTC), in epoch days. */
        const val WS0 = 2451900.0674 - 2440587.5
        const val ICU_FIRST = 1700
        const val ICU_LAST = 2300

        const val OFFICIAL_FIRST = 1901
        const val OFFICIAL_LAST = 2099
    }

    /**
     * The official "chinese" calendar for 1901–2099 (Hong Kong Observatory conversion tables, which follow the
     * Purple Mountain Observatory data required by the specification). ICU's astronomical computation differs in
     * 15 of these years, where a new moon or principal term falls within minutes of midnight (e.g. 1987, 2027, 2030).
     * Each year is 5 hex digits: bits 0–12 mark the 30-day months, bits 13+ the 0-based position of the leap month.
     */
    private object Official {
        private const val FIRST_NEW_YEAR = -25153L // 1901-02-19
        private const val DATA =
            "0075200ea50b64a0064b00a9b095560056a00b5905752007520db2500b2500a4b0b4ab002ad0056b04b6900da90fd9200e9200d25" +
                "0ba4d00a56002b6095b5006d400ea905e9200e920cd260052b00a570b2b600b5a006d406ec9007490f69300a930052b0ca5b00a" +
                "ad0056a09b5500ba400b4905a9300a950f52d0053600aad0b5aa005b200da507d4a00d4a10a9500a97005560cab500ad5006d20" +
                "8ea500ea50064a06c9700a9b0f55a0056a00b690b75200b5200b250964b00a4b114ab002ad0056d0cb6900da900d9209d2500d2" +
                "515a4d00a56002b60c5b5006d500ea90be9200e9200d2606a5600a57114d60035a006d50b6c900749006930952b0052b00a5b05" +
                "55a0056a0fb5500ba400b490ba9300a950052d08aad00ab5135aa005d200da50dd4a00d4a00c950952e0055600ab5055b2006d2" +
                "0cea5007250064b0ac9700cab0055a06ad600b691775200b5200b250da4b00a4b004ab0a55b005ad00b6a05b5200d920fd2500d" +
                "2500a550b4ad004b6005b506daa00ec911e9200e9200d260ca5600a5700556086d5007550074906e93006930f52b0052b00a5b0" +
                "b55a0056a00b650974a00b4a11a9500a950052d0caad00ab5005aa08ba500da500d4a07c9500c960f94e0055600ab50b5b2006d" +
                "200ea508e4a0068b10c97004ab0055b0cad600b6a007520972500b4500a8b0549b"

        private val years: Array<CalendarYearInfo>
        private val newYears: LongArray

        init {
            val n = OFFICIAL_LAST - OFFICIAL_FIRST + 1
            check(DATA.length == n * 5)
            newYears = LongArray(n + 1)
            var ny = FIRST_NEW_YEAR
            years = Array(n) { i ->
                val v = DATA.substring(i * 5, i * 5 + 5).toInt(16)
                val leap = v shr 13
                val count = if (leap != 0) 13 else 12
                val starts = LongArray(count + 1)
                val codes = IntArray(count)
                var num = 0
                for (m in 0 until count) {
                    starts[m] = ny
                    ny += if (v and (1 shl m) != 0) 30 else 29
                    if (m == leap && leap != 0) codes[m] = (num shl 1) or 1 else codes[m] = (++num) shl 1
                }
                starts[count] = ny
                newYears[i] = starts[0]
                CalendarYearInfo(starts, codes, leap != 0)
            }
            newYears[n] = ny
        }

        fun yearInfo(y: Int): CalendarYearInfo = years[y - OFFICIAL_FIRST]

        /** New year of [y] in [OFFICIAL_FIRST], [OFFICIAL_LAST] + 1. */
        fun newYear(y: Int): Long = newYears[y - OFFICIAL_FIRST]
    }
}
