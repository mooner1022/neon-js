package io.neonjs.builtins.temporal

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Calendar Date Record; [eraYear] is meaningful only when [era] is not null. */
internal class CalDate(
    @JvmField val year: Int,
    @JvmField val month: Int,
    @JvmField val monthCode: Int,
    @JvmField val day: Int,
    @JvmField val dayOfYear: Int,
    @JvmField val daysInMonth: Int,
    @JvmField val daysInYear: Int,
    @JvmField val monthsInYear: Int,
    @JvmField val inLeapYear: Boolean,
    @JvmField val era: String?,
    @JvmField val eraYear: Int,
)

/** Month codes encoded as `number * 2 + (1 if leap)`, the encoding of [TemporalParser.parseMonthCodeString]. */
internal object MonthCodes {
    fun of(number: Int, leap: Boolean): Int = (number shl 1) or (if (leap) 1 else 0)

    fun number(code: Int): Int = code shr 1

    fun toString(code: Int): String {
        val n = code shr 1
        val sb = StringBuilder(4).append('M')
        if (n < 10) sb.append('0')
        sb.append(n)
        if (code and 1 != 0) sb.append('L')
        return sb.toString()
    }
}

/**
 * A non-ISO calendar: the calendar-independent algorithms of the Intl era and monthCode proposal (eras, month codes,
 * NonISODateAdd / NonISODateUntil / NonISOResolveFields / NonISOMonthDayToISOReferenceDate) on top of the year
 * structure supplied by a [TemporalCalendarSource].
 *
 * DoS discipline: every operation touches a bounded number of calendar years. Month and day balancing use absolute
 * month indices ([TemporalCalendarSource.monthsBeforeYear]) and epoch days instead of the spec's unit-by-unit loops,
 * and NonISODateUntil computes its years / months / weeks / days in closed form (the spec's candidate loops are
 * monotone, so the results are identical). All remaining loops have small constant bounds.
 *
 * Thread safety: instances are shared JVM-wide; the only mutable state is the [years] cache of immutable records.
 */
internal class NonIsoCalendar private constructor(@JvmField val id: String, private val source: TemporalCalendarSource) {
    private val years = ConcurrentHashMap<Int, CalendarYearInfo>()
    private val monthsBefore = ConcurrentHashMap<Int, Long>()
    private val eras: Array<Era>? = ERAS[id]
    private val chineseLike = id == "chinese" || id == "dangi"
    private val leapToCommon = when (id) {
        "chinese", "dangi" -> SKIP_BACKWARD
        "hebrew" -> SKIP_FORWARD
        else -> NONE
    }
    private val hasMonth13 = id == "coptic" || id == "ethiopic" || id == "ethioaa"

    @Volatile private var meanMonthsPerYear = 0.0
    @Volatile private var monthsBeforeOrigin = 0L
    @Volatile private var maxDaysByCode: IntArray? = null

    val supportsEra: Boolean get() = eras != null

    /** CalendarHasMidYearEras */
    val hasMidYearEras: Boolean get() = id == "japanese"

    // ------------------------------------------------------------------ year structure

    fun info(y: Int): CalendarYearInfo {
        if (y > MAX_YEAR || y < -MAX_YEAR) tRangeErr("date outside of supported range")
        years[y]?.let { return it }
        val inf = guarded { source.yearInfo(y) }
        if (inf.monthCount !in 12..13) tRangeErr("invalid calendar data")
        if (years.size >= CACHE_LIMIT) years.clear()
        years[y] = inf
        return inf
    }

    private fun mby(y: Int): Long {
        if (y > MAX_YEAR + 1 || y < -MAX_YEAR) tRangeErr("date outside of supported range")
        monthsBefore[y]?.let { return it }
        val v = guarded { source.monthsBeforeYear(y) }
        if (monthsBefore.size >= CACHE_LIMIT) monthsBefore.clear()
        monthsBefore[y] = v
        return v
    }

    /** Provider failures (bad data, ICU errors) surface as RangeErrors, not as engine-internal errors. */
    private inline fun <T> guarded(f: () -> T): T = try {
        f()
    } catch (e: io.neonjs.runtime.JSException) {
        throw e
    } catch (_: RuntimeException) {
        tRangeErr("calendar data unavailable for the $id calendar")
    }

    private fun daysInMonth(inf: CalendarYearInfo, m: Int): Int = (inf.monthStarts[m] - inf.monthStarts[m - 1]).toInt()

    /** The arithmetic year containing [epochDay]; the estimate is corrected in jumps that never overshoot. */
    fun yearOf(epochDay: Long): Int {
        var y = guarded { source.estimateYear(epochDay) }.coerceIn(-MAX_YEAR, MAX_YEAR)
        repeat(64) {
            val inf = info(y)
            y = when {
                // no calendar year is longer than 390 days, so these jumps stay on the near side of the target
                epochDay < inf.start -> y - max(1L, (inf.start - epochDay) / 390).toInt()
                epochDay >= inf.end -> y + max(1L, (epochDay - inf.end) / 390).toInt()
                else -> return y
            }
        }
        tRangeErr("calendar computation did not converge")
    }

    /** Absolute index of month [m] of year [y]. */
    fun monthIndex(y: Int, m: Int): Long = mby(y) + (m - 1)

    /** The arithmetic year containing absolute month index [idx]. */
    fun yearOfMonthIndex(idx: Long): Int {
        if (meanMonthsPerYear == 0.0) {
            val origin = mby(0)
            monthsBeforeOrigin = origin
            meanMonthsPerYear = (mby(1000) - origin) / 1000.0
        }
        val est = floor((idx - monthsBeforeOrigin) / meanMonthsPerYear)
        var y = est.coerceIn(-MAX_YEAR.toDouble(), MAX_YEAR.toDouble()).toInt()
        repeat(64) {
            val a = mby(y)
            if (idx < a) {
                y -= max(1L, (a - idx) / 14).toInt()
                return@repeat
            }
            val b = mby(y + 1)
            if (idx >= b) {
                y += max(1L, (idx - b) / 14).toInt()
                return@repeat
            }
            return y
        }
        tRangeErr("calendar computation did not converge")
    }

    // ------------------------------------------------------------------ conversions

    /** NonISOCalendarISOToDate */
    fun isoToDate(iso: IsoDate): CalDate = fromEpochDay(TM.epochDays(iso))

    fun fromEpochDay(day: Long): CalDate {
        val y = yearOf(day)
        val inf = info(y)
        var m = 1
        while (m < inf.monthCount && day >= inf.monthStarts[m]) m++
        val d = (day - inf.monthStarts[m - 1] + 1).toInt()
        var era: String? = null
        var eraYear = 0
        if (eras != null) {
            val e = eraOf(y, day)
            era = e.code
            eraYear = eraYearOf(e, y)
        }
        return CalDate(
            y, m, inf.monthCodes[m - 1], d, (day - inf.start + 1).toInt(), daysInMonth(inf, m), (inf.end - inf.start).toInt(),
            inf.monthCount, inf.inLeapYear, era, eraYear,
        )
    }

    /** CalendarIntegersToISO */
    fun integersToIso(y: Int, m: Int, d: Int): IsoDate {
        val inf = info(y)
        if (m < 1 || m > inf.monthCount || d < 1 || d > daysInMonth(inf, m)) tRangeErr("invalid date in the $id calendar")
        // callers check ISODateWithinLimits / ISOYearMonthWithinLimits (the first of a month may precede the limit)
        return TM.dateFromEpochDays(inf.monthStarts[m - 1] + d - 1)
    }

    private fun isoFromEpochDay(day: Long): IsoDate {
        if (day < -TM.MAX_EPOCH_DAYS - 1 || day > TM.MAX_EPOCH_DAYS) tRangeErr("date outside of supported range")
        return TM.dateFromEpochDays(day)
    }

    // ------------------------------------------------------------------ month codes

    /** IsValidMonthCodeForCalendar */
    fun isValidMonthCode(code: Int): Boolean {
        val n = code shr 1
        if (code and 1 == 0) return n in 1..12 || (n == 13 && hasMonth13)
        return when (leapToCommon) {
            SKIP_BACKWARD -> n in 1..12
            SKIP_FORWARD -> n == 5
            else -> false
        }
    }

    /** YearContainsMonthCode */
    fun yearContainsMonthCode(y: Int, code: Int): Boolean {
        if (code and 1 == 0) return true
        for (c in info(y).monthCodes) if (c == code) return true
        return false
    }

    /** ConstrainMonthCode */
    fun constrainMonthCode(y: Int, code: Int, overflow: Overflow): Int {
        if (yearContainsMonthCode(y, code)) return code
        if (overflow == Overflow.REJECT) tRangeErr("month code ${MonthCodes.toString(code)} does not exist in year $y")
        return if (leapToCommon == SKIP_FORWARD) MonthCodes.of(6, false) else code and 1.inv()
    }

    /** MonthCodeToOrdinal */
    fun monthCodeToOrdinal(y: Int, code: Int): Int {
        val codes = info(y).monthCodes
        for (i in codes.indices) if (codes[i] == code) return i + 1
        tRangeErr("month code ${MonthCodes.toString(code)} does not exist in year $y")
    }

    // ------------------------------------------------------------------ eras

    private fun eraOf(y: Int, epochDay: Long): Era {
        val es = eras!!
        if (id == "japanese") {
            for (k in JAPANESE_ERA_STARTS.indices) if (epochDay >= JAPANESE_ERA_STARTS[k]) return es[k]
        }
        if (es.size == 1) return es[0]
        // the era containing the epoch year comes first, the era counting backwards second
        return if (y >= es[es.size - 2].minArithmeticYear) es[es.size - 2] else es[es.size - 1]
    }

    private fun eraYearOf(e: Era, y: Int): Int = when (e.kind) {
        EPOCH -> y
        NEGATIVE -> 1 - y
        else -> y - e.offset + 1
    }

    /** CanonicalizeEraInCalendar; null when [era] is not an era of this calendar. */
    fun canonicalizeEra(era: String): Era? {
        for (e in eras ?: return null) if (e.code == era || e.alias == era) return e
        return null
    }

    /** CalendarDateArithmeticYearForEraYear */
    fun arithmeticYearForEraYear(e: Era, eraYear: Double): Double = when (e.kind) {
        EPOCH -> eraYear
        NEGATIVE -> 1 - eraYear
        else -> e.offset + eraYear - 1
    }

    // ------------------------------------------------------------------ fields

    /** NonISOFieldKeysToIgnore on a field mask. */
    fun fieldKeysToIgnore(keys: Int): Int {
        var ignored = keys
        if (keys and TCal.F_MONTH != 0) ignored = ignored or TCal.F_MONTHCODE
        if (keys and TCal.F_MONTHCODE != 0) ignored = ignored or TCal.F_MONTH
        val eraKeys = TCal.F_ERA or TCal.F_ERAYEAR or TCal.F_YEAR
        if (keys and eraKeys != 0 && supportsEra) ignored = ignored or eraKeys
        if (keys and (TCal.F_DAY or TCal.F_MONTH or TCal.F_MONTHCODE) != 0 && hasMidYearEras) {
            ignored = ignored or TCal.F_ERA or TCal.F_ERAYEAR
        }
        return ignored
    }

    /** NonISOResolveFields */
    fun resolveFields(f: CalFields, type: Int) {
        var needsYear = type == TCal.TYPE_DATE || type == TCal.TYPE_YEAR_MONTH
        if (f.monthCode == null) needsYear = true
        if (!f.month.isNaN()) needsYear = true
        val needsDay = type == TCal.TYPE_DATE || type == TCal.TYPE_MONTH_DAY
        val hasEras = supportsEra
        if (needsYear && f.year.isNaN()) {
            if (!hasEras) tTypeErr("year is required")
            if (f.era == null || f.eraYear.isNaN()) tTypeErr("year, or era and eraYear, are required")
        }
        if (hasEras) {
            if (f.era != null && f.eraYear.isNaN()) tTypeErr("eraYear is required when era is present")
            if (!f.eraYear.isNaN() && f.era == null) tTypeErr("era is required when eraYear is present")
        }
        if (needsDay && f.day.isNaN()) tTypeErr("day is required")
        if (f.month.isNaN() && f.monthCode == null) tTypeErr("month or monthCode is required")
        if (hasEras && !f.eraYear.isNaN()) {
            val e = canonicalizeEra(f.era!!) ?: tRangeErr("invalid era ${f.era!!.take(30)} for the $id calendar")
            val y = arithmeticYearForEraYear(e, f.eraYear)
            if (!f.year.isNaN() && f.year != y) tRangeErr("year and era/eraYear do not agree")
            f.year = y
            f.era = null
            f.eraYear = Double.NaN
        }
        val mc = f.monthCode
        if (mc != null) {
            val code = TemporalParser.parseMonthCodeString(mc)
            if (!isValidMonthCode(code)) tRangeErr("invalid monthCode $mc for the $id calendar")
            if (!f.year.isNaN()) {
                val y = checkYear(f.year)
                val constrained = if (yearContainsMonthCode(y, code)) code else constrainMonthCode(y, code, Overflow.CONSTRAIN)
                val month = monthCodeToOrdinal(y, constrained)
                if (!f.month.isNaN() && f.month != month.toDouble()) tRangeErr("month and monthCode do not agree")
                f.month = month.toDouble()
            }
        }
    }

    private fun checkYear(y: Double): Int {
        if (y > MAX_YEAR || y < -MAX_YEAR) tRangeErr("date outside of supported range")
        return y.toInt()
    }

    /** NonISOCalendarDateToISO (after resolving the fields). */
    fun dateToIso(f: CalFields, overflow: Overflow): IsoDate {
        val y = checkYear(f.year)
        f.monthCode?.let { constrainMonthCode(y, TemporalParser.parseMonthCodeString(it), overflow) }
        val inf = info(y)
        val n = inf.monthCount
        val month: Int
        if (f.month > n) {
            if (overflow == Overflow.REJECT) tRangeErr("month out of range")
            month = n
        } else {
            month = f.month.toInt()
        }
        val dim = daysInMonth(inf, month)
        val day: Int
        if (f.day > dim) {
            if (overflow == Overflow.REJECT) tRangeErr("day out of range")
            day = dim
        } else {
            day = f.day.toInt()
        }
        return integersToIso(y, month, day)
    }

    /** NonISOMonthDayToISOReferenceDate (after resolving the fields). */
    fun monthDayToIso(f: CalFields, overflow: Overflow): IsoDate {
        var code: Int
        val maxDays: Int
        if (!f.year.isNaN()) {
            val y = checkYear(f.year)
            val inf = info(y)
            if (inf.start > TM.MAX_EPOCH_DAYS || inf.end <= -TM.MAX_EPOCH_DAYS - 1) tRangeErr("date outside of supported range")
            val n = inf.monthCount
            val month: Int
            if (f.month > n) {
                if (overflow == Overflow.REJECT) tRangeErr("month out of range")
                month = n
            } else {
                month = f.month.toInt()
            }
            val mc = f.monthCode
            code = if (mc == null) inf.monthCodes[month - 1] else constrainMonthCode(y, TemporalParser.parseMonthCodeString(mc), overflow)
            maxDays = daysInMonth(inf, month)
        } else {
            code = TemporalParser.parseMonthCodeString(f.monthCode!!)
            maxDays = if (chineseLike) 30 else maxDaysInMonthCode(code)
        }
        val day: Int
        if (f.day > maxDays) {
            if (overflow == Overflow.REJECT) tRangeErr("day out of range")
            day = maxDays
        } else {
            day = f.day.toInt()
        }
        if (chineseLike) {
            if (chineseReferenceYear(code, day) == 0) {
                if (overflow == Overflow.REJECT) tRangeErr("month-day ${MonthCodes.toString(code)} $day does not exist")
                code = code and 1.inv()
            }
            val ref = chineseReferenceYear(code, day)
            findInIsoYear(code, day, ref)?.let { return it }
        }
        return referenceDate(code, day) ?: tRangeErr("month-day ${MonthCodes.toString(code)} $day does not exist")
    }

    /** Table 6 (ISO reference years for "chinese" and "dangi"); 0 for "—". */
    private fun chineseReferenceYear(code: Int, day: Int): Int {
        val n = code shr 1
        if (code and 1 == 0) {
            if (day < 30) return 1972
            return when (n) {
                1, 4, 11 -> 1970
                3 -> if (id == "dangi") 1968 else 1966
                6, 8 -> 1971
                else -> 1972
            }
        }
        if (day < 30) {
            return when (n) {
                2 -> 1947; 3 -> 1966; 4 -> 1963; 5 -> 1971; 6 -> 1960; 7 -> 1968; 8 -> 1957; 9 -> 2014; 10 -> 1984
                11 -> if (day <= 10) 2033 else 2034
                else -> 0
            }
        }
        return when (n) {
            3 -> 1955; 4 -> 1944; 5 -> 1952; 6 -> 1941; 7 -> 1938
            else -> 0
        }
    }

    /** The latest date in ISO year [isoYear] with [code] and [day], or null. */
    private fun findInIsoYear(code: Int, day: Int, isoYear: Int): IsoDate? {
        val first = TM.epochDays(isoYear.toLong(), 1, 1)
        val last = TM.epochDays(isoYear.toLong(), 12, 31)
        var best = Long.MIN_VALUE
        val y1 = yearOf(last)
        for (y in y1 downTo y1 - 2) {
            val d = dayInYear(y, code, day) ?: continue
            if (d in first..last && d > best) best = d
        }
        return if (best == Long.MIN_VALUE) null else TM.dateFromEpochDays(best)
    }

    /** Epoch day of [code] / [day] in year [y], or null when that year lacks it. */
    private fun dayInYear(y: Int, code: Int, day: Int): Long? {
        val inf = info(y)
        for (i in inf.monthCodes.indices) {
            if (inf.monthCodes[i] == code) return if (day <= daysInMonth(inf, i + 1)) inf.monthStarts[i] + day - 1 else null
        }
        return null
    }

    /** The latest date in 1900–1972 with [code] / [day], else the earliest in 1973–2035 (bounded scans). */
    private fun referenceDate(code: Int, day: Int): IsoDate? {
        val lo = TM.epochDays(1900, 1, 1)
        val mid = TM.epochDays(1972, 12, 31)
        val hi = TM.epochDays(2035, 12, 31)
        val yMid = yearOf(mid)
        val yLo = yearOf(lo)
        for (y in yMid downTo yLo) {
            val d = dayInYear(y, code, day) ?: continue
            if (d in lo..mid) return TM.dateFromEpochDays(d)
        }
        val yHi = yearOf(hi)
        for (y in yearOf(mid + 1)..yHi) {
            val d = dayInYear(y, code, day) ?: continue
            if (d in (mid + 1)..hi) return TM.dateFromEpochDays(d)
        }
        return null
    }

    /** The largest number of days the month [code] has in any year (sampled over the reference-date window). */
    private fun maxDaysInMonthCode(code: Int): Int {
        var table = maxDaysByCode
        if (table == null) {
            table = IntArray(28)
            val yLo = yearOf(TM.epochDays(1900, 1, 1))
            val yHi = yearOf(TM.epochDays(2035, 12, 31))
            for (y in yLo..yHi) {
                val inf = info(y)
                for (i in inf.monthCodes.indices) {
                    val c = inf.monthCodes[i]
                    if (c < table.size) table[c] = max(table[c], daysInMonth(inf, i + 1))
                }
            }
            maxDaysByCode = table
        }
        return if (code < table.size && table[code] > 0) table[code] else 30
    }

    // ------------------------------------------------------------------ arithmetic

    /** Packs (year, month) of absolute month index [idx]. */
    private fun yearMonthOfIndex(idx: Long): Long {
        val y = yearOfMonthIndex(idx)
        val m = (idx - mby(y)).toInt() + 1
        return (y.toLong() shl 8) or m.toLong()
    }

    /** NonISODateAdd */
    fun dateAdd(iso: IsoDate, dur: DateDuration, overflow: Overflow): IsoDate {
        val p = isoToDate(iso)
        val y0l = p.year.toLong() + dur.years
        if (y0l > MAX_YEAR || y0l < -MAX_YEAR) tRangeErr("date outside of supported range")
        val y0 = y0l.toInt()
        val m0 = monthCodeToOrdinal(y0, constrainMonthCode(y0, p.monthCode, overflow))
        val ym = yearMonthOfIndex(monthIndex(y0, m0) + dur.months)
        val inf = info((ym shr 8).toInt())
        val m = (ym and 0xff).toInt()
        val dim = daysInMonth(inf, m)
        val regulated: Int
        if (p.day <= dim) {
            regulated = p.day
        } else {
            if (overflow == Overflow.REJECT) tRangeErr("day out of range")
            regulated = dim
        }
        val days = dur.days + 7 * dur.weeks
        if (abs(days) > 4 * TM.MAX_EPOCH_DAYS) tRangeErr("date outside of supported range")
        return isoFromEpochDay(inf.monthStarts[m - 1] + (regulated - 1) + days)
    }

    /** CompareSurpasses with a month code. */
    private fun surpassesCode(sign: Int, year: Long, code: Int, day: Int, t: CalDate): Boolean {
        if (year != t.year.toLong()) return sign * (year - t.year) > 0
        if (code != t.monthCode) return sign * (code - t.monthCode) > 0
        if (day != t.day) return sign * (day - t.day) > 0
        return false
    }

    /** CompareSurpasses with an ordinal month. */
    private fun surpassesMonth(sign: Int, year: Long, month: Int, day: Int, t: CalDate): Boolean {
        if (year != t.year.toLong()) return sign * (year - t.year) > 0
        if (month != t.month) return sign * (month - t.month) > 0
        if (day != t.day) return sign * (day - t.day) > 0
        return false
    }

    /** NonISODateSurpasses with only years. */
    private fun surpassesYears(sign: Int, p1: CalDate, p2: CalDate, years: Long): Boolean {
        val y0l = p1.year + years
        if (surpassesCode(sign, y0l, p1.monthCode, p1.day, p2)) return true
        if (y0l > MAX_YEAR || y0l < -MAX_YEAR) return true
        val y0 = y0l.toInt()
        val m0 = monthCodeToOrdinal(y0, constrainMonthCode(y0, p1.monthCode, Overflow.CONSTRAIN))
        return surpassesMonth(sign, y0l, m0, p1.day, p2)
    }

    /** NonISODateUntil, in closed form. */
    fun dateUntil(one: IsoDate, two: IsoDate, largest: TUnit): DateDuration {
        val sign = -TM.compareDate(one, two)
        if (sign == 0) return DateDuration.ZERO
        val p1 = isoToDate(one)
        val p2 = isoToDate(two)
        var years = 0L
        if (largest == TUnit.YEAR) {
            // start just short of the target year: that candidate never surpasses, and the answer is within 2 steps
            var candidate = (p2.year - p1.year).toLong()
            if (candidate != 0L) candidate -= sign
            var guard = 0
            while (!surpassesYears(sign, p1, p2, candidate)) {
                years = candidate
                candidate += sign
                if (++guard > 8) tRangeErr("calendar computation did not converge")
            }
        }
        val y0 = (p1.year + years).toInt()
        val m0 = monthCodeToOrdinal(y0, constrainMonthCode(y0, p1.monthCode, Overflow.CONSTRAIN))
        val idx0 = monthIndex(y0, m0)
        var months = 0L
        if (largest == TUnit.YEAR || largest == TUnit.MONTH) {
            // the month index where (year, month) meets the target's; one less if the day would pass it
            var k = monthIndex(p2.year, p2.month) - idx0
            if (sign * (p1.day - p2.day) > 0) k -= sign
            if (k * sign < 0) k = 0
            months = k
        }
        val ym = yearMonthOfIndex(idx0 + months)
        val inf = info((ym shr 8).toInt())
        val m = (ym and 0xff).toInt()
        val regulated = min(p1.day, daysInMonth(inf, m))
        var days = TM.epochDays(two) - (inf.monthStarts[m - 1] + regulated - 1)
        var weeks = 0L
        if (largest == TUnit.WEEK) {
            weeks = days / 7
            days %= 7
        }
        return DateDuration(years, months, weeks, days)
    }

    // ------------------------------------------------------------------ registry and tables

    class Era(@JvmField val code: String, @JvmField val alias: String?, @JvmField val kind: Int, @JvmField val offset: Int) {
        /** The smallest arithmetic year counted in this era when it counts forward. */
        val minArithmeticYear: Int get() = if (kind == OFFSET) offset else 1
    }

    companion object {
        /** Arithmetic years beyond this are never within the Temporal date range in any supported calendar. */
        const val MAX_YEAR = 300_000
        private const val CACHE_LIMIT = 4096
        private const val NONE = 0
        private const val SKIP_BACKWARD = 1
        private const val SKIP_FORWARD = 2
        const val EPOCH = 0
        const val NEGATIVE = 1
        const val OFFSET = 2

        private fun single(code: String) = arrayOf(Era(code, null, EPOCH, 0))
        private fun twoWay(pos: String, posAlias: String?, neg: String, negAlias: String?) =
            arrayOf(Era(pos, posAlias, EPOCH, 0), Era(neg, negAlias, NEGATIVE, 0))

        /** Table 2. Order: (japanese regnal eras newest first), the forward era, then the backward era. */
        private val ERAS: Map<String, Array<Era>> = hashMapOf(
            "buddhist" to single("be"),
            "coptic" to single("am"),
            "ethioaa" to single("aa"),
            "ethiopic" to arrayOf(Era("am", null, EPOCH, 0), Era("aa", null, OFFSET, -5499)),
            "gregory" to twoWay("ce", "ad", "bce", "bc"),
            "hebrew" to single("am"),
            "indian" to single("shaka"),
            "islamic-civil" to twoWay("ah", null, "bh", null),
            "islamic-tbla" to twoWay("ah", null, "bh", null),
            "islamic-umalqura" to twoWay("ah", null, "bh", null),
            "japanese" to arrayOf(
                Era("reiwa", null, OFFSET, 2019), Era("heisei", null, OFFSET, 1989), Era("showa", null, OFFSET, 1926),
                Era("taisho", null, OFFSET, 1912), Era("meiji", null, OFFSET, 1868), Era("ce", "ad", EPOCH, 0),
                Era("bce", "bc", NEGATIVE, 0),
            ),
            "persian" to single("ap"),
            "roc" to twoWay("roc", null, "broc", null),
        )

        /** First days of reiwa, heisei, showa, taisho and (as counted in Temporal) meiji. */
        private val JAPANESE_ERA_STARTS = longArrayOf(
            TM.epochDays(2019, 5, 1), TM.epochDays(1989, 1, 8), TM.epochDays(1926, 12, 25), TM.epochDays(1912, 7, 30),
            TM.epochDays(1873, 1, 1),
        )

        private val instances = ConcurrentHashMap<String, NonIsoCalendar>()

        /** The calendar for canonical id [id], or null when no provider implements it. */
        fun of(id: String): NonIsoCalendar? {
            instances[id]?.let { return it }
            val provider = TemporalProviders.provider ?: return null
            if (id !in supportedIds()) return null
            val source = provider.calendarSource(id) ?: return null
            return instances.computeIfAbsent(id) { NonIsoCalendar(id, source) }
        }

        @Volatile private var ids: Set<String>? = null

        /** Canonical ids of the available non-ISO calendars. */
        fun supportedIds(): Set<String> {
            ids?.let { return it }
            val s = TemporalProviders.provider?.calendarIds()?.toHashSet() ?: emptySet()
            ids = s
            return s
        }
    }
}
