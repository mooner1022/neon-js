package dev.mooner.neonjs.builtins

import dev.mooner.neonjs.runtime.Ops
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.zone.ZoneRules
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.floor

/**
 * Time value arithmetic of ECMA-262 §21.4.1 (Day, YearFromTime, MakeDay, TimeClip, ...), the host local time zone
 * (LocalTime / UTC) and the string forms produced by Date.prototype methods.
 *
 * Time values are Doubles combined exactly as the spec's Number arithmetic. Calendar decompositions work on Long day
 * counts and expect finite arguments; time zone lookups clamp their argument, so intermediate values of any magnitude
 * (before TimeClip) remain computable.
 */
object DateTime {
    const val MS_PER_SECOND = 1000.0
    const val MS_PER_MINUTE = 60_000.0
    const val MS_PER_HOUR = 3_600_000.0
    const val MS_PER_DAY = 86_400_000.0

    /** Largest magnitude of a time value: 100,000,000 days either side of the epoch. */
    const val MAX_TIME = 8.64e15

    /** Years of larger magnitude cannot denote a time value; MakeDay returns NaN for them. */
    private const val MAX_YEAR = 1_000_000.0

    /** Bound (in days) for calendar decompositions, far outside the time value range. */
    private const val MAX_DAYS = 1e11

    /** Bound (in ms) for time zone lookups: well outside the time value range, well inside java.time's. */
    private const val ZONE_LOOKUP_LIMIT = 1e16

    const val INVALID_DATE = "Invalid Date"

    private val WEEKDAY_NAMES = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    internal val MONTH_NAMES = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** Current time as a time value. */
    fun now(): Double = dev.mooner.neonjs.runtime.Agent.current.get()?.currentTimeMillis() ?: System.currentTimeMillis().toDouble()

    // ------------------------------------------------------------------ decomposition of time values

    /** Day(t) */
    fun day(t: Double): Double = floor(t / MS_PER_DAY)

    /** TimeWithinDay(t) */
    fun timeWithinDay(t: Double): Double = posMod(t, MS_PER_DAY)

    /** WeekDay(t) */
    fun weekDay(t: Double): Double = posMod(day(t) + 4, 7.0)

    /** YearFromTime(t) */
    fun yearFromTime(t: Double): Double = civilYear(civilOf(t)).toDouble()

    /** MonthFromTime(t), 0-based. */
    fun monthFromTime(t: Double): Double = civilMonth(civilOf(t)).toDouble()

    /** DateFromTime(t), 1-based. */
    fun dateFromTime(t: Double): Double = civilDate(civilOf(t)).toDouble()

    fun hourFromTime(t: Double): Double = floor(timeWithinDay(t) / MS_PER_HOUR)
    fun minFromTime(t: Double): Double = floor(timeWithinDay(t) / MS_PER_MINUTE) % 60
    fun secFromTime(t: Double): Double = floor(timeWithinDay(t) / MS_PER_SECOND) % 60
    fun msFromTime(t: Double): Double = timeWithinDay(t) % MS_PER_SECOND

    /** Non-negative remainder; never returns -0. */
    private fun posMod(a: Double, n: Double): Double {
        val r = a % n
        return if (r < 0) r + n else r + 0.0
    }

    fun isLeapYear(year: Long): Boolean = year % 4 == 0L && (year % 100 != 0L || year % 400 == 0L)

    /** Number of days in [month] (0-based) of [year]. */
    fun daysInMonth(year: Long, month: Int): Int = when (month) {
        1 -> if (isLeapYear(year)) 29 else 28
        3, 5, 8, 10 -> 30
        else -> 31
    }

    /** Days from the epoch to [year]-[month]-[date] (month 0-based) in the proleptic Gregorian calendar. */
    private fun daysFromCivil(year: Long, month: Int, date: Int): Long {
        val y = if (month < 2) year - 1 else year
        val era = Math.floorDiv(y, 400L)
        val yoe = y - era * 400
        val mp = (month + 10) % 12 // months counted from March
        val doy = (153 * mp + 2) / 5 + date - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    /** Calendar date of day number [days], packed as `year shl 9 | month shl 5 | date` (month 0-based). */
    private fun civil(days: Long): Long {
        val z = days + 719468
        val era = Math.floorDiv(z, 146097L)
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val date = doy - (153 * mp + 2) / 5 + 1
        val month = if (mp < 10) mp + 2 else mp - 10
        val year = yoe + era * 400 + (if (month < 2) 1 else 0)
        return (year shl 9) or (month shl 5) or date
    }

    private fun civilOf(t: Double): Long = civil(day(t).coerceIn(-MAX_DAYS, MAX_DAYS).toLong())
    private fun civilYear(c: Long): Int = (c shr 9).toInt()
    private fun civilMonth(c: Long): Int = ((c shr 5) and 15).toInt()
    private fun civilDate(c: Long): Int = (c and 31).toInt()

    // ------------------------------------------------------------------ composition

    /** MakeTime(hour, min, sec, ms) */
    fun makeTime(hour: Double, min: Double, sec: Double, ms: Double): Double {
        if (!hour.isFinite() || !min.isFinite() || !sec.isFinite() || !ms.isFinite()) return Double.NaN
        val h = Ops.integerPart(hour)
        val m = Ops.integerPart(min)
        val s = Ops.integerPart(sec)
        val milli = Ops.integerPart(ms)
        return ((h * MS_PER_HOUR + m * MS_PER_MINUTE) + s * MS_PER_SECOND) + milli
    }

    /** MakeDay(year, month, date) */
    fun makeDay(year: Double, month: Double, date: Double): Double {
        if (!year.isFinite() || !month.isFinite() || !date.isFinite()) return Double.NaN
        val y = Ops.integerPart(year)
        val m = Ops.integerPart(month)
        val dt = Ops.integerPart(date)
        val ym = y + floor(m / 12)
        if (!ym.isFinite() || abs(ym) > MAX_YEAR) return Double.NaN
        val mn = posMod(m, 12.0).toInt()
        return daysFromCivil(ym.toLong(), mn, 1).toDouble() + dt - 1
    }

    /** MakeDate(day, time) */
    fun makeDate(day: Double, time: Double): Double {
        if (!day.isFinite() || !time.isFinite()) return Double.NaN
        val tv = day * MS_PER_DAY + time
        return if (tv.isFinite()) tv else Double.NaN
    }

    /** MakeFullYear(year): maps years 0..99 to 1900..1999. */
    fun makeFullYear(year: Double): Double {
        if (year.isNaN()) return Double.NaN
        val truncated = Ops.integerPart(year)
        return if (truncated in 0.0..99.0) 1900 + truncated else truncated
    }

    /** TimeClip(time) */
    fun timeClip(time: Double): Double =
        if (!time.isFinite() || abs(time) > MAX_TIME) Double.NaN else Ops.integerPart(time)

    // ------------------------------------------------------------------ local time zone

    /** The host time zone: system property `neonjs.timezone` (any ZoneId) if set and valid, else the JVM default. */
    val hostZone: ZoneId by lazy {
        System.getProperty("neonjs.timezone")?.let { id -> runCatching { ZoneId.of(id) }.getOrNull() } ?: ZoneId.systemDefault()
    }

    /** The local time zone of the running context ([dev.mooner.neonjs.runtime.RuntimeConfig.timeZone]), else [hostZone]. */
    val zone: ZoneId get() = dev.mooner.neonjs.runtime.Agent.current.get()?.config?.timeZone ?: hostZone

    private val rules: ZoneRules get() = zone.rules

    private val zoneNameCache = java.util.concurrent.ConcurrentHashMap<ZoneId, Array<String>>()

    /** Long names of the zone without and with daylight saving time, e.g. "Korean Standard Time". */
    private val zoneNames: Array<String>
        get() = zoneNameCache.computeIfAbsent(zone) { z ->
            val tz = TimeZone.getTimeZone(z)
            arrayOf(tz.getDisplayName(false, TimeZone.LONG, Locale.US), tz.getDisplayName(true, TimeZone.LONG, Locale.US))
        }

    private fun lookupMs(t: Double): Long = t.coerceIn(-ZONE_LOOKUP_LIMIT, ZONE_LOOKUP_LIMIT).toLong()

    /** Offset (ms) of local time from UTC at the instant [t] (a finite UTC time value). */
    fun offsetAt(t: Double): Double {
        val r = rules
        val offset = if (r.isFixedOffset) r.getOffset(Instant.EPOCH) else r.getOffset(Instant.ofEpochMilli(lookupMs(t)))
        return offset.totalSeconds * MS_PER_SECOND
    }

    /** LocalTime(t) */
    fun localTime(t: Double): Double = if (t.isFinite()) t + offsetAt(t) else Double.NaN

    /**
     * UTC(t): the instant denoted by local time [t]. A local time repeated at a negative transition maps to its
     * earlier instant and a local time skipped at a positive transition is interpreted with the offset before the
     * transition; ZoneRules.getOffset(LocalDateTime) returns exactly that offset in both cases.
     */
    fun utc(t: Double): Double {
        if (!t.isFinite()) return Double.NaN
        val r = rules
        val offset = if (r.isFixedOffset) r.getOffset(Instant.EPOCH) else {
            val ms = lookupMs(t)
            val local = LocalDateTime.ofEpochSecond(Math.floorDiv(ms, 1000L), Math.floorMod(ms, 1000L).toInt() * 1_000_000, ZoneOffset.UTC)
            r.getOffset(local)
        }
        return t - offset.totalSeconds * MS_PER_SECOND
    }

    // ------------------------------------------------------------------ string forms

    private fun pad(v: Int, width: Int): String = v.toString().padStart(width, '0')

    /** Year with at least four digits and a "-" sign for years before 1 BCE. */
    private fun yearString(year: Int): String = if (year < 0) "-" + pad(-year, 4) else pad(year, 4)

    /** DateString(t), e.g. "Thu Jan 01 1970". */
    fun dateString(t: Double): String {
        val c = civilOf(t)
        return "${WEEKDAY_NAMES[weekDay(t).toInt()]} ${MONTH_NAMES[civilMonth(c)]} ${pad(civilDate(c), 2)} ${yearString(civilYear(c))}"
    }

    /** TimeString(t), e.g. "09:00:00 GMT". */
    fun timeString(t: Double): String =
        "${pad(hourFromTime(t).toInt(), 2)}:${pad(minFromTime(t).toInt(), 2)}:${pad(secFromTime(t).toInt(), 2)} GMT"

    /** TimeZoneString(tv), e.g. "+0900 (Korean Standard Time)". */
    fun timeZoneString(tv: Double): String {
        val offset = offsetAt(tv)
        val a = abs(offset)
        val sign = if (offset >= 0) "+" else "-"
        val dst = !rules.isFixedOffset && rules.isDaylightSavings(Instant.ofEpochMilli(lookupMs(tv)))
        return "$sign${pad(hourFromTime(a).toInt(), 2)}${pad(minFromTime(a).toInt(), 2)} (${zoneNames[if (dst) 1 else 0]})"
    }

    /** ToDateString(tv): the format of Date.prototype.toString. */
    fun toDateString(tv: Double): String {
        if (tv.isNaN()) return INVALID_DATE
        val t = localTime(tv)
        return dateString(t) + " " + timeString(t) + timeZoneString(tv)
    }

    /** Date.prototype.toUTCString format, e.g. "Thu, 01 Jan 1970 00:00:00 GMT". */
    fun toUTCString(tv: Double): String {
        if (tv.isNaN()) return INVALID_DATE
        val c = civilOf(tv)
        return "${WEEKDAY_NAMES[weekDay(tv).toInt()]}, ${pad(civilDate(c), 2)} ${MONTH_NAMES[civilMonth(c)]} " +
            "${yearString(civilYear(c))} ${timeString(tv)}"
    }

    /** Date Time String Format with extended years outside 0..9999; [tv] must be finite. */
    fun toISOString(tv: Double): String {
        val c = civilOf(tv)
        val y = civilYear(c)
        val year = if (y in 0..9999) pad(y, 4) else (if (y < 0) "-" else "+") + pad(abs(y), 6)
        return "$year-${pad(civilMonth(c) + 1, 2)}-${pad(civilDate(c), 2)}T${pad(hourFromTime(tv).toInt(), 2)}:" +
            "${pad(minFromTime(tv).toInt(), 2)}:${pad(secFromTime(tv).toInt(), 2)}.${pad(msFromTime(tv).toInt(), 3)}Z"
    }

    /** Implementation-defined locale forms (en-US style, local time), e.g. "1/1/1970, 9:00:00 AM". */
    fun toLocaleString(tv: Double): String =
        if (tv.isNaN()) INVALID_DATE else localTime(tv).let { localeDate(it) + ", " + localeTime(it) }

    fun toLocaleDateString(tv: Double): String = if (tv.isNaN()) INVALID_DATE else localeDate(localTime(tv))

    fun toLocaleTimeString(tv: Double): String = if (tv.isNaN()) INVALID_DATE else localeTime(localTime(tv))

    private fun localeDate(t: Double): String {
        val c = civilOf(t)
        return "${civilMonth(c) + 1}/${civilDate(c)}/${civilYear(c)}"
    }

    private fun localeTime(t: Double): String {
        val h = hourFromTime(t).toInt()
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$h12:${pad(minFromTime(t).toInt(), 2)}:${pad(secFromTime(t).toInt(), 2)} ${if (h < 12) "AM" else "PM"}"
    }
}
