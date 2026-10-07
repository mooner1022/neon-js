package dev.mooner.neonjs.builtins.temporal

import dev.mooner.neonjs.runtime.*
import java.math.BigDecimal
import java.math.BigInteger

// ====================================================================== errors

internal fun tTypeErr(msg: String): Nothing = throw JSException.typeError(msg)

/** ASCII-case-insensitive match; [lower] must already be ASCII-lowercase. */
internal fun asciiEqualsIgnoreCase(s: String, lower: String): Boolean {
    if (s.length != lower.length) return false
    for (i in s.indices) {
        var c = s[i]
        if (c in 'A'..'Z') c = c + 32
        if (c != lower[i]) return false
    }
    return true
}
internal fun tRangeErr(msg: String): Nothing = throw JSException.rangeError(msg)

// ====================================================================== records

/** ISO Date Record. The year may lie outside the Temporal range but always fits an Int. */
class IsoDate(@JvmField val year: Int, @JvmField val month: Int, @JvmField val day: Int)

/** Time Record; [days] carries overflow days from balancing. */
class TimeRec(
    @JvmField val hour: Int,
    @JvmField val minute: Int,
    @JvmField val second: Int,
    @JvmField val millisecond: Int,
    @JvmField val microsecond: Int,
    @JvmField val nanosecond: Int,
    @JvmField val days: Long = 0,
) {
    fun nanosOfDay(): Long =
        ((((hour * 60L + minute) * 60L + second) * 1000L + millisecond) * 1000L + microsecond) * 1000L + nanosecond

    fun subSecondNanos(): Int = millisecond * 1_000_000 + microsecond * 1000 + nanosecond

    companion object {
        @JvmField val MIDNIGHT = TimeRec(0, 0, 0, 0, 0, 0)
        @JvmField val NOON = TimeRec(12, 0, 0, 0, 0, 0)

        /** Time of day for 0 <= [ns] < nsPerDay. */
        fun fromNanosOfDay(ns: Long, days: Long = 0): TimeRec {
            var r = ns
            val nanosecond = (r % 1000).toInt(); r /= 1000
            val microsecond = (r % 1000).toInt(); r /= 1000
            val millisecond = (r % 1000).toInt(); r /= 1000
            val second = (r % 60).toInt(); r /= 60
            val minute = (r % 60).toInt(); r /= 60
            return TimeRec(r.toInt(), minute, second, millisecond, microsecond, nanosecond, days)
        }
    }
}

class IsoDateTime(@JvmField val date: IsoDate, @JvmField val time: TimeRec)

/** Date Duration Record (all components are integers well within the Long range). */
class DateDuration(@JvmField val years: Long, @JvmField val months: Long, @JvmField val weeks: Long, @JvmField val days: Long) {
    fun sign(): Int = when {
        years != 0L -> java.lang.Long.signum(years)
        months != 0L -> java.lang.Long.signum(months)
        weeks != 0L -> java.lang.Long.signum(weeks)
        else -> java.lang.Long.signum(days)
    }

    companion object {
        @JvmField val ZERO = DateDuration(0, 0, 0, 0)
    }
}

/** Internal Duration Record: a date duration plus a time duration in nanoseconds. */
class InternalDuration(@JvmField val date: DateDuration, @JvmField val time: BigInteger) {
    fun sign(): Int {
        val d = date.sign()
        return if (d != 0) d else time.signum()
    }
}

// ====================================================================== units, rounding modes

internal enum class TUnit(val singular: String, val plural: String, val nanos: Long, val maxIncrement: Int, val isDate: Boolean) {
    YEAR("year", "years", 0, 0, true),
    MONTH("month", "months", 0, 0, true),
    WEEK("week", "weeks", 0, 0, true),
    DAY("day", "days", 86_400_000_000_000L, 0, true),
    HOUR("hour", "hours", 3_600_000_000_000L, 24, false),
    MINUTE("minute", "minutes", 60_000_000_000L, 60, false),
    SECOND("second", "seconds", 1_000_000_000L, 60, false),
    MILLISECOND("millisecond", "milliseconds", 1_000_000L, 1000, false),
    MICROSECOND("microsecond", "microseconds", 1000L, 1000, false),
    NANOSECOND("nanosecond", "nanoseconds", 1L, 1000, false);

    val isCalendar: Boolean get() = this == YEAR || this == MONTH || this == WEEK

    companion object {
        @JvmField val ALL = entries.toTypedArray()

        fun larger(a: TUnit, b: TUnit): TUnit = if (a.ordinal <= b.ordinal) a else b
    }
}

internal enum class RMode(val id: String) {
    CEIL("ceil"), FLOOR("floor"), EXPAND("expand"), TRUNC("trunc"),
    HALF_CEIL("halfCeil"), HALF_FLOOR("halfFloor"), HALF_EXPAND("halfExpand"), HALF_TRUNC("halfTrunc"), HALF_EVEN("halfEven");

    fun negate(): RMode = when (this) {
        CEIL -> FLOOR
        FLOOR -> CEIL
        HALF_CEIL -> HALF_FLOOR
        HALF_FLOOR -> HALF_CEIL
        else -> this
    }

    /** GetUnsignedRoundingMode */
    fun unsigned(negative: Boolean): URM = when (this) {
        CEIL -> if (negative) URM.ZERO else URM.INFINITY
        FLOOR -> if (negative) URM.INFINITY else URM.ZERO
        EXPAND -> URM.INFINITY
        TRUNC -> URM.ZERO
        HALF_CEIL -> if (negative) URM.HALF_ZERO else URM.HALF_INFINITY
        HALF_FLOOR -> if (negative) URM.HALF_INFINITY else URM.HALF_ZERO
        HALF_EXPAND -> URM.HALF_INFINITY
        HALF_TRUNC -> URM.HALF_ZERO
        HALF_EVEN -> URM.HALF_EVEN
    }

    companion object {
        @JvmField val ALL = entries.toTypedArray()
    }
}

internal enum class URM { ZERO, INFINITY, HALF_ZERO, HALF_INFINITY, HALF_EVEN }

internal enum class Overflow { CONSTRAIN, REJECT }
internal enum class Disambiguation { COMPATIBLE, EARLIER, LATER, REJECT }
internal enum class OffsetOption { PREFER, USE, IGNORE, REJECT }
internal enum class ShowCalendar { AUTO, ALWAYS, NEVER, CRITICAL }

// ====================================================================== numeric helpers

internal object TNum {
    @JvmField val BI_1000: BigInteger = BigInteger.valueOf(1000)
    @JvmField val BI_1E6: BigInteger = BigInteger.valueOf(1_000_000)
    @JvmField val BI_1E9: BigInteger = BigInteger.valueOf(1_000_000_000)
    @JvmField val BI_60: BigInteger = BigInteger.valueOf(60)
    @JvmField val BI_3600: BigInteger = BigInteger.valueOf(3600)

    /** Exact BigInteger of an integral Double. */
    fun big(d: Double): BigInteger =
        if (Math.abs(d) < 9.0e18) BigInteger.valueOf(d.toLong()) else BigDecimal(d).toBigInteger()

    /** Normalizes -0 to +0. */
    fun pz(d: Double): Double = if (d == 0.0) 0.0 else d

    /** Negation without producing -0. */
    fun neg(d: Double): Double = if (d == 0.0) 0.0 else -d

    /** Correctly rounded n / d as a Double. */
    fun divToDouble(n: BigInteger, d: BigInteger): Double {
        if (n.signum() == 0) return 0.0
        var num = n.abs()
        var den = d.abs()
        val negative = (n.signum() < 0) != (d.signum() < 0)
        // scale so that the integer quotient has at least 66 significant bits
        var shift = 66 - (num.bitLength() - den.bitLength())
        if (shift > 0) num = num.shiftLeft(shift) else if (shift < 0) den = den.shiftLeft(-shift)
        val qr = num.divideAndRemainder(den)
        var q = qr[0]
        if (qr[1].signum() != 0) q = q.setBit(0)
        val r = Math.scalb(q.toDouble(), -shift)
        return if (negative) -r else r
    }

    fun divToDouble(n: BigInteger, d: Long): Double = divToDouble(n, BigInteger.valueOf(d))

    /** ApplyUnsignedRoundingMode on |quotient| between r1 (floor) and r1+1, given the comparison of the fraction with 1/2. */
    private fun applyURM(urm: URM, r1Odd: Boolean, halfCmp: Int): Boolean /* round up? */ = when (urm) {
        URM.ZERO -> false
        URM.INFINITY -> true
        else -> when {
            halfCmp < 0 -> false
            halfCmp > 0 -> true
            urm == URM.HALF_ZERO -> false
            urm == URM.HALF_INFINITY -> true
            else -> r1Odd
        }
    }

    /** RoundNumberToIncrement(x, increment, mode) for integers. */
    fun roundToIncrement(x: BigInteger, inc: BigInteger, mode: RMode): BigInteger {
        val qr = x.divideAndRemainder(inc)
        val r = qr[1]
        if (r.signum() == 0) return x
        val negative = x.signum() < 0
        val absQ = qr[0].abs()
        val up = applyURM(mode.unsigned(negative), absQ.testBit(0), r.abs().shiftLeft(1).compareTo(inc))
        val rounded = if (up) absQ.add(BigInteger.ONE) else absQ
        return (if (negative) rounded.negate() else rounded).multiply(inc)
    }

    fun roundToIncrement(x: Long, inc: Long, mode: RMode): Long {
        val r = x % inc
        if (r == 0L) return x
        val negative = x < 0
        val absQ = Math.abs(x / inc)
        val twice = Math.abs(r) * 2
        val up = applyURM(mode.unsigned(negative), absQ and 1L == 1L, twice.compareTo(inc))
        val rounded = if (up) absQ + 1 else absQ
        return (if (negative) -rounded else rounded) * inc
    }

    /** RoundNumberToIncrementAsIfPositive */
    fun roundAsIfPositive(x: BigInteger, inc: BigInteger, mode: RMode): BigInteger {
        val qr = x.divideAndRemainder(inc)
        var q = qr[0]
        var r = qr[1]
        if (r.signum() == 0) return x
        if (r.signum() < 0) { q = q.subtract(BigInteger.ONE); r = r.add(inc) }
        val up = applyURM(mode.unsigned(false), q.testBit(0), r.shiftLeft(1).compareTo(inc))
        return (if (up) q.add(BigInteger.ONE) else q).multiply(inc)
    }

    /** truncate(x / inc) * inc for integer x. */
    fun truncToIncrement(x: Long, inc: Long): Long = (x / inc) * inc
}

// ====================================================================== ISO calendar math

internal object TM {
    const val NS_PER_DAY = 86_400_000_000_000L
    @JvmField val BI_NS_PER_DAY: BigInteger = BigInteger.valueOf(NS_PER_DAY)
    @JvmField val NS_MAX_INSTANT: BigInteger = BI_NS_PER_DAY.multiply(BigInteger.valueOf(100_000_000L))
    @JvmField val NS_MIN_INSTANT: BigInteger = NS_MAX_INSTANT.negate()
    @JvmField val MAX_TIME_DURATION: BigInteger = BigInteger.ONE.shiftLeft(53).multiply(TNum.BI_1E9).subtract(BigInteger.ONE)
    @JvmField val TWO_POW_53_NS: BigInteger = BigInteger.ONE.shiftLeft(53).multiply(TNum.BI_1E9)
    const val MAX_EPOCH_DAYS = 100_000_000L

    fun isLeap(y: Long): Boolean = y % 4 == 0L && (y % 100 != 0L || y % 400 == 0L)

    fun isLeapD(y: Double): Boolean = y % 4.0 == 0.0 && (y % 100.0 != 0.0 || y % 400.0 == 0.0)

    fun daysInMonth(y: Long, m: Int): Int = when (m) {
        2 -> if (isLeap(y)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    fun daysInMonthD(y: Double, m: Int): Int = when (m) {
        2 -> if (isLeapD(y)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    fun daysInYear(y: Long): Int = if (isLeap(y)) 366 else 365

    /** ISODateToEpochDays(y, m - 1, d) for 1 <= m <= 12. */
    fun epochDays(y: Long, m: Int, d: Int): Long {
        val yy = if (m <= 2) y - 1 else y
        val era = Math.floorDiv(yy, 400L)
        val yoe = yy - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    fun epochDays(d: IsoDate): Long = epochDays(d.year.toLong(), d.month, d.day)

    /** The ISO date of epoch day [days]. */
    fun dateFromEpochDays(days: Long): IsoDate {
        val z = days + 719468
        val era = Math.floorDiv(z, 146097L)
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + (if (m <= 2) 1 else 0)
        if (y > Int.MAX_VALUE || y < Int.MIN_VALUE) tRangeErr("date outside of supported range")
        return IsoDate(y.toInt(), m.toInt(), d.toInt())
    }

    /** AddDaysToISODate */
    fun addDays(d: IsoDate, days: Long): IsoDate = if (days == 0L) d else dateFromEpochDays(epochDays(d) + days)

    /** BalanceISOYearMonth: returns the year; [outMonth] receives the month. */
    fun balanceYear(y: Long, m: Long): Long = y + Math.floorDiv(m - 1, 12L)
    fun balanceMonth(m: Long): Int = (Math.floorMod(m - 1, 12L) + 1).toInt()

    fun isValidIsoDate(y: Double, m: Double, d: Double): Boolean {
        if (m < 1 || m > 12) return false
        if (d < 1 || d > daysInMonthD(y, m.toInt())) return false
        return true
    }

    /** Builds an IsoDate from a year that must lie well outside the Temporal range to be rejected. */
    fun makeDate(y: Double, m: Int, d: Int): IsoDate {
        if (Math.abs(y) > 1.0e9) tRangeErr("date outside of supported range")
        return IsoDate(y.toInt(), m, d)
    }

    /** RegulateISODate; years far outside the representable range are rejected with a RangeError. */
    fun regulateIsoDate(y: Double, m: Double, d: Double, overflow: Overflow): IsoDate {
        if (overflow == Overflow.CONSTRAIN) {
            val mm = m.coerceIn(1.0, 12.0).toInt()
            val dim = daysInMonthD(y, mm)
            val dd = d.coerceIn(1.0, dim.toDouble()).toInt()
            return makeDate(y, mm, dd)
        }
        if (!isValidIsoDate(y, m, d)) tRangeErr("date is out of range")
        return makeDate(y, m.toInt(), d.toInt())
    }

    fun compareDate(a: IsoDate, b: IsoDate): Int = when {
        a.year != b.year -> if (a.year > b.year) 1 else -1
        a.month != b.month -> if (a.month > b.month) 1 else -1
        a.day != b.day -> if (a.day > b.day) 1 else -1
        else -> 0
    }

    fun compareTime(a: TimeRec, b: TimeRec): Int = java.lang.Long.compare(a.nanosOfDay(), b.nanosOfDay()).let { if (it > 0) 1 else if (it < 0) -1 else 0 }

    fun compareDateTime(a: IsoDateTime, b: IsoDateTime): Int {
        val c = compareDate(a.date, b.date)
        return if (c != 0) c else compareTime(a.time, b.time)
    }

    fun isoDateWithinLimits(d: IsoDate): Boolean {
        if (Math.abs(d.year) > 300000) return false
        val days = epochDays(d)
        return days >= -MAX_EPOCH_DAYS - 1 && days <= MAX_EPOCH_DAYS
    }

    fun isoDateTimeWithinLimits(dt: IsoDateTime): Boolean {
        if (Math.abs(dt.date.year) > 300000) return false
        val days = epochDays(dt.date)
        if (days < -MAX_EPOCH_DAYS - 1 || days > MAX_EPOCH_DAYS) return false
        if (days == -MAX_EPOCH_DAYS - 1 && dt.time.nanosOfDay() == 0L) return false
        return true
    }

    fun isoYearMonthWithinLimits(y: Int, m: Int): Boolean {
        if (y < -271821 || y > 275760) return false
        if (y == -271821 && m < 4) return false
        if (y == 275760 && m > 9) return false
        return true
    }

    /** CheckISODaysRange */
    fun checkIsoDaysRange(d: IsoDate) {
        if (Math.abs(d.year) > 300000 || Math.abs(epochDays(d)) > MAX_EPOCH_DAYS) tRangeErr("date outside of supported range")
    }

    fun isValidEpochNs(ns: BigInteger): Boolean = ns >= NS_MIN_INSTANT && ns <= NS_MAX_INSTANT

    /** GetUTCEpochNanoseconds */
    fun utcEpochNs(dt: IsoDateTime): BigInteger =
        BigInteger.valueOf(epochDays(dt.date)).multiply(BI_NS_PER_DAY).add(BigInteger.valueOf(dt.time.nanosOfDay()))

    fun utcEpochNs(d: IsoDate, t: TimeRec): BigInteger =
        BigInteger.valueOf(epochDays(d)).multiply(BI_NS_PER_DAY).add(BigInteger.valueOf(t.nanosOfDay()))

    /** GetISOPartsFromEpoch (also usable for epoch values shifted by an offset). */
    fun isoPartsFromEpoch(ns: BigInteger): IsoDateTime {
        val qr = ns.divideAndRemainder(BI_NS_PER_DAY)
        var days = qr[0].toLong()
        var rem = qr[1].toLong()
        if (rem < 0) { rem += NS_PER_DAY; days -= 1 }
        return IsoDateTime(dateFromEpochDays(days), TimeRec.fromNanosOfDay(rem))
    }

    /** BalanceTime with arbitrary Long components. */
    fun balanceTime(h: Long, mi: Long, s: Long, ms: Long, us: Long, ns: Long): TimeRec {
        var microsecond = us + Math.floorDiv(ns, 1000L)
        val nanosecond = Math.floorMod(ns, 1000L)
        var millisecond = ms + Math.floorDiv(microsecond, 1000L)
        microsecond = Math.floorMod(microsecond, 1000L)
        var second = s + Math.floorDiv(millisecond, 1000L)
        millisecond = Math.floorMod(millisecond, 1000L)
        var minute = mi + Math.floorDiv(second, 60L)
        second = Math.floorMod(second, 60L)
        var hour = h + Math.floorDiv(minute, 60L)
        minute = Math.floorMod(minute, 60L)
        val days = Math.floorDiv(hour, 24L)
        hour = Math.floorMod(hour, 24L)
        return TimeRec(hour.toInt(), minute.toInt(), second.toInt(), millisecond.toInt(), microsecond.toInt(), nanosecond.toInt(), days)
    }

    /** BalanceISODateTime where only the nanosecond component may be out of range (|extraNs| small). */
    fun balanceDateTime(d: IsoDate, t: TimeRec, extraNs: Long): IsoDateTime {
        val total = t.nanosOfDay() + extraNs
        val days = Math.floorDiv(total, NS_PER_DAY)
        val rem = Math.floorMod(total, NS_PER_DAY)
        return IsoDateTime(addDays(d, days), TimeRec.fromNanosOfDay(rem))
    }

    /** AddTime */
    fun addTime(t: TimeRec, d: BigInteger): TimeRec {
        if (d.signum() == 0) return TimeRec(t.hour, t.minute, t.second, t.millisecond, t.microsecond, t.nanosecond, 0)
        val total = d.add(BigInteger.valueOf(t.nanosOfDay()))
        val qr = total.divideAndRemainder(BI_NS_PER_DAY)
        var days = qr[0].toLong()
        var rem = qr[1].toLong()
        if (rem < 0) { rem += NS_PER_DAY; days -= 1 }
        return TimeRec.fromNanosOfDay(rem, days)
    }

    /** DifferenceTime as nanoseconds. */
    fun differenceTime(a: TimeRec, b: TimeRec): Long = b.nanosOfDay() - a.nanosOfDay()

    /** RoundTime */
    fun roundTime(t: TimeRec, increment: Long, unit: TUnit, mode: RMode): TimeRec {
        val quantity = when (unit) {
            TUnit.DAY, TUnit.HOUR -> t.nanosOfDay()
            TUnit.MINUTE -> (((t.minute * 60L + t.second) * 1000L + t.millisecond) * 1000L + t.microsecond) * 1000L + t.nanosecond
            TUnit.SECOND -> ((t.second * 1000L + t.millisecond) * 1000L + t.microsecond) * 1000L + t.nanosecond
            TUnit.MILLISECOND -> (t.millisecond * 1000L + t.microsecond) * 1000L + t.nanosecond
            TUnit.MICROSECOND -> t.microsecond * 1000L + t.nanosecond
            else -> t.nanosecond.toLong()
        }
        val unitLength = unit.nanos
        val result = TNum.roundToIncrement(quantity, increment * unitLength, mode) / unitLength
        return when (unit) {
            TUnit.DAY -> TimeRec(0, 0, 0, 0, 0, 0, result)
            TUnit.HOUR -> balanceTime(result, 0, 0, 0, 0, 0)
            TUnit.MINUTE -> balanceTime(t.hour.toLong(), result, 0, 0, 0, 0)
            TUnit.SECOND -> balanceTime(t.hour.toLong(), t.minute.toLong(), result, 0, 0, 0)
            TUnit.MILLISECOND -> balanceTime(t.hour.toLong(), t.minute.toLong(), t.second.toLong(), result, 0, 0)
            TUnit.MICROSECOND -> balanceTime(t.hour.toLong(), t.minute.toLong(), t.second.toLong(), t.millisecond.toLong(), result, 0)
            else -> balanceTime(t.hour.toLong(), t.minute.toLong(), t.second.toLong(), t.millisecond.toLong(), t.microsecond.toLong(), result)
        }
    }

    /** RoundISODateTime */
    fun roundDateTime(dt: IsoDateTime, increment: Long, unit: TUnit, mode: RMode): IsoDateTime {
        val rt = roundTime(dt.time, increment, unit, mode)
        return IsoDateTime(addDays(dt.date, rt.days), rt)
    }

    fun dayOfWeek(d: IsoDate): Int {
        val wd = Math.floorMod(epochDays(d) + 4, 7L).toInt()
        return if (wd == 0) 7 else wd
    }

    fun dayOfYear(d: IsoDate): Int = (epochDays(d) - epochDays(d.year.toLong(), 1, 1) + 1).toInt()

    /** ISOWeekOfYear: returns week shl 32 | (year as unsigned int) packed in a LongArray of size 2. */
    fun weekOfYear(d: IsoDate): IntArray {
        val year = d.year
        val dayOfYear = dayOfYear(d)
        val dayOfWeek = dayOfWeek(d)
        val week = Math.floorDiv(dayOfYear + 7 - dayOfWeek + 3, 7)
        if (week < 1) {
            val dayOfJan1st = dayOfWeek(IsoDate(year, 1, 1))
            if (dayOfJan1st == 5) return intArrayOf(53, year - 1)
            if (dayOfJan1st == 6 && isLeap(year - 1L)) return intArrayOf(53, year - 1)
            return intArrayOf(52, year - 1)
        }
        if (week == 53) {
            val daysLater = daysInYear(year.toLong()) - dayOfYear
            if (daysLater < 4 - dayOfWeek) return intArrayOf(1, year + 1)
        }
        return intArrayOf(week, year)
    }
}

// ====================================================================== option reading

internal object TOpt {
    /** GetOptionsObject */
    fun optionsObject(v: Any?): JSObject = when (v) {
        Undefined -> JSObject(null)
        is JSObject -> v
        else -> tTypeErr("options must be an object or undefined")
    }

    /** GetOption(options, key, string, values, default) with default `null` meaning undefined. */
    fun getString(o: JSObject, key: String, allowed: Array<String>?, default: String?): String? {
        val v = o.get(key, o)
        if (v === Undefined) return default
        val s = Ops.toString(v)
        if (allowed != null && s !in allowed) tRangeErr("${s.take(50)} is not a valid value for $key")
        return s
    }

    private val OVERFLOW = arrayOf("constrain", "reject")
    private val DISAMBIGUATION = arrayOf("compatible", "earlier", "later", "reject")
    private val OFFSET = arrayOf("prefer", "use", "ignore", "reject")
    private val CALENDAR_NAME = arrayOf("auto", "always", "never", "critical")
    private val TZ_NAME = arrayOf("auto", "never", "critical")
    private val SHOW_OFFSET = arrayOf("auto", "never")
    private val DIRECTION = arrayOf("next", "previous")
    private val ROUNDING_MODES = RMode.ALL.map { it.id }.toTypedArray()
    private val UNIT_STRINGS: Array<String> = (TUnit.ALL.map { it.singular } + TUnit.ALL.map { it.plural } + listOf("auto")).toTypedArray()

    fun overflow(o: JSObject): Overflow =
        if (getString(o, "overflow", OVERFLOW, "constrain") == "constrain") Overflow.CONSTRAIN else Overflow.REJECT

    fun disambiguation(o: JSObject): Disambiguation = when (getString(o, "disambiguation", DISAMBIGUATION, "compatible")) {
        "compatible" -> Disambiguation.COMPATIBLE
        "earlier" -> Disambiguation.EARLIER
        "later" -> Disambiguation.LATER
        else -> Disambiguation.REJECT
    }

    fun offset(o: JSObject, fallback: OffsetOption): OffsetOption {
        val fb = when (fallback) {
            OffsetOption.PREFER -> "prefer"
            OffsetOption.USE -> "use"
            OffsetOption.IGNORE -> "ignore"
            OffsetOption.REJECT -> "reject"
        }
        return when (getString(o, "offset", OFFSET, fb)) {
            "prefer" -> OffsetOption.PREFER
            "use" -> OffsetOption.USE
            "ignore" -> OffsetOption.IGNORE
            else -> OffsetOption.REJECT
        }
    }

    fun showCalendar(o: JSObject): ShowCalendar = when (getString(o, "calendarName", CALENDAR_NAME, "auto")) {
        "always" -> ShowCalendar.ALWAYS
        "never" -> ShowCalendar.NEVER
        "critical" -> ShowCalendar.CRITICAL
        else -> ShowCalendar.AUTO
    }

    /** GetTemporalShowTimeZoneNameOption: 0 auto, 1 never, 2 critical. */
    fun showTimeZone(o: JSObject): Int = when (getString(o, "timeZoneName", TZ_NAME, "auto")) {
        "never" -> 1
        "critical" -> 2
        else -> 0
    }

    /** GetTemporalShowOffsetOption: true when the offset is shown. */
    fun showOffset(o: JSObject): Boolean = getString(o, "offset", SHOW_OFFSET, "auto") != "never"

    /** GetDirectionOption: true for next. */
    fun directionNext(o: JSObject): Boolean {
        val s = getString(o, "direction", DIRECTION, null) ?: tRangeErr("direction is required")
        return s == "next"
    }

    fun roundingMode(o: JSObject, fallback: RMode): RMode {
        val s = getString(o, "roundingMode", ROUNDING_MODES, fallback.id)
        for (m in RMode.ALL) if (m.id == s) return m
        return fallback
    }

    /** GetRoundingIncrementOption */
    fun roundingIncrement(o: JSObject): Long {
        val v = o.get("roundingIncrement", o)
        if (v === Undefined) return 1
        val i = toIntegerWithTruncation(v)
        if (i < 1 || i > 1e9) tRangeErr("roundingIncrement out of range")
        return i.toLong()
    }

    /** GetTemporalFractionalSecondDigitsOption: -1 for auto. */
    fun fractionalSecondDigits(o: JSObject): Int {
        val v = o.get("fractionalSecondDigits", o)
        if (v === Undefined) return -1
        if (v !is Double) {
            if (Ops.toString(v) != "auto") tRangeErr("fractionalSecondDigits must be 'auto' or 0 through 9")
            return -1
        }
        if (v.isNaN() || v.isInfinite()) tRangeErr("fractionalSecondDigits must be 'auto' or 0 through 9")
        val c = Math.floor(v)
        if (c < 0 || c > 9) tRangeErr("fractionalSecondDigits must be 'auto' or 0 through 9")
        return c.toInt()
    }

    const val UNIT_UNSET = -1
    const val UNIT_AUTO = -2

    /** GetTemporalUnitValuedOption: returns a TUnit ordinal, UNIT_UNSET or UNIT_AUTO. */
    fun unit(o: JSObject, key: String, required: Boolean): Int {
        val s = getString(o, key, UNIT_STRINGS, null)
        if (s == null) {
            if (required) tRangeErr("$key is required")
            return UNIT_UNSET
        }
        if (s == "auto") return UNIT_AUTO
        for (u in TUnit.ALL) if (u.singular == s || u.plural == s) return u.ordinal
        return UNIT_UNSET
    }

    /** ValidateTemporalUnitValue; [group]: 0 date, 1 time, 2 datetime. */
    fun validateUnit(value: Int, group: Int, extra: IntArray? = null) {
        if (value == UNIT_UNSET) return
        if (extra != null && value in extra) return
        if (value == UNIT_AUTO) tRangeErr("auto is not allowed here")
        val u = TUnit.ALL[value]
        if (u.isDate && (group == 0 || group == 2)) return
        if (!u.isDate && (group == 1 || group == 2)) return
        tRangeErr("${u.singular} is not allowed here")
    }

    /** ValidateTemporalRoundingIncrement */
    fun validateIncrement(increment: Long, dividend: Long, inclusive: Boolean) {
        val maximum = if (inclusive) dividend else dividend - 1
        if (increment > maximum) tRangeErr("roundingIncrement out of range")
        if (dividend % increment != 0L) tRangeErr("roundingIncrement must evenly divide $dividend")
    }
}

// ====================================================================== conversions

/** ToIntegerWithTruncation */
internal fun toIntegerWithTruncation(v: Any?): Double {
    val n = Ops.toNumber(v)
    if (n.isNaN() || n.isInfinite()) tRangeErr("value must be a finite number")
    val t = if (n < 0) Math.ceil(n) else Math.floor(n)
    return t + 0.0
}

/** ToPositiveIntegerWithTruncation */
internal fun toPositiveIntegerWithTruncation(v: Any?): Double {
    val i = toIntegerWithTruncation(v)
    if (i <= 0) tRangeErr("value must be positive")
    return i
}

/** ToIntegerIfIntegral */
internal fun toIntegerIfIntegral(v: Any?): Double {
    val n = Ops.toNumber(v)
    if (n.isNaN() || n.isInfinite() || n != Math.floor(n)) tRangeErr("value must be an integer")
    return n + 0.0
}

// ====================================================================== seconds-string precision

/** ToSecondsStringPrecisionRecord: precision (-1 auto, -2 minute, else digits), unit, increment. */
internal class Precision(@JvmField val precision: Int, @JvmField val unit: TUnit, @JvmField val increment: Long) {
    companion object {
        const val AUTO = -1
        const val MINUTE = -2

        fun of(smallestUnit: Int, digits: Int): Precision {
            if (smallestUnit >= 0) {
                return when (TUnit.ALL[smallestUnit]) {
                    TUnit.MINUTE -> Precision(MINUTE, TUnit.MINUTE, 1)
                    TUnit.SECOND -> Precision(0, TUnit.SECOND, 1)
                    TUnit.MILLISECOND -> Precision(3, TUnit.MILLISECOND, 1)
                    TUnit.MICROSECOND -> Precision(6, TUnit.MICROSECOND, 1)
                    else -> Precision(9, TUnit.NANOSECOND, 1)
                }
            }
            if (digits < 0) return Precision(AUTO, TUnit.NANOSECOND, 1)
            if (digits == 0) return Precision(0, TUnit.SECOND, 1)
            if (digits <= 3) return Precision(digits, TUnit.MILLISECOND, pow10(3 - digits))
            if (digits <= 6) return Precision(digits, TUnit.MICROSECOND, pow10(6 - digits))
            return Precision(digits, TUnit.NANOSECOND, pow10(9 - digits))
        }

        private fun pow10(n: Int): Long {
            var r = 1L
            repeat(n) { r *= 10 }
            return r
        }
    }
}
