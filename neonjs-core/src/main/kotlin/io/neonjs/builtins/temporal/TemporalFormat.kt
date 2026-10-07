package io.neonjs.builtins.temporal

import java.math.BigDecimal
import java.math.BigInteger
import kotlin.math.abs

/** String serialization of Temporal values. */
internal object TFormat {
    private fun pad2(sb: StringBuilder, v: Int) {
        if (v < 10) sb.append('0')
        sb.append(v)
    }

    /** PadISOYear */
    fun padIsoYear(sb: StringBuilder, y: Int) {
        if (y in 0..9999) {
            val s = y.toString()
            repeat(4 - s.length) { sb.append('0') }
            sb.append(s)
            return
        }
        sb.append(if (y > 0) '+' else '-')
        val s = abs(y.toLong()).toString()
        repeat(6 - s.length) { sb.append('0') }
        sb.append(s)
    }

    /** FormatFractionalSeconds; precision -1 = auto. */
    fun fractionalSeconds(sb: StringBuilder, subSecondNs: Int, precision: Int) {
        if (precision == Precision.AUTO) {
            if (subSecondNs == 0) return
            var s = subSecondNs.toString()
            s = "0".repeat(9 - s.length) + s
            var end = s.length
            while (end > 0 && s[end - 1] == '0') end--
            sb.append('.').append(s, 0, end)
            return
        }
        if (precision == 0) return
        var s = subSecondNs.toString()
        s = "0".repeat(9 - s.length) + s
        sb.append('.').append(s, 0, precision)
    }

    /** FormatTimeString; precision: digits, -1 auto, -2 minute. */
    fun timeString(sb: StringBuilder, hour: Int, minute: Int, second: Int, subSecondNs: Int, precision: Int, separated: Boolean = true) {
        pad2(sb, hour)
        if (separated) sb.append(':')
        pad2(sb, minute)
        if (precision == Precision.MINUTE) return
        if (separated) sb.append(':')
        pad2(sb, second)
        fractionalSeconds(sb, subSecondNs, precision)
    }

    fun timeRecordToString(t: TimeRec, precision: Int): String {
        val sb = StringBuilder(18)
        timeString(sb, t.hour, t.minute, t.second, t.subSecondNanos(), precision)
        return sb.toString()
    }

    /** FormatOffsetTimeZoneIdentifier */
    fun formatOffsetMinutes(offsetMinutes: Int, separated: Boolean): String {
        val sb = StringBuilder(6)
        sb.append(if (offsetMinutes >= 0) '+' else '-')
        val minutes = abs(offsetMinutes)
        timeString(sb, minutes / 60, minutes % 60, 0, 0, Precision.MINUTE, separated)
        return sb.toString()
    }

    /** FormatUTCOffsetNanoseconds */
    fun formatUtcOffsetNs(offsetNs: Long): String {
        val sb = StringBuilder(20)
        sb.append(if (offsetNs >= 0) '+' else '-')
        val ns = abs(offsetNs)
        val hour = (ns / 3_600_000_000_000L).toInt()
        val minute = ((ns / 60_000_000_000L) % 60).toInt()
        val second = ((ns / 1_000_000_000L) % 60).toInt()
        val sub = (ns % 1_000_000_000L).toInt()
        val precision = if (second == 0 && sub == 0) Precision.MINUTE else Precision.AUTO
        timeString(sb, hour, minute, second, sub, precision)
        return sb.toString()
    }

    /** FormatDateTimeUTCOffsetRounded */
    fun formatOffsetRounded(offsetNs: Long): String {
        val rounded = TNum.roundToIncrement(offsetNs, 60_000_000_000L, RMode.HALF_EXPAND)
        return formatOffsetMinutes((rounded / 60_000_000_000L).toInt(), true)
    }

    /** FormatCalendarAnnotation */
    fun calendarAnnotation(sb: StringBuilder, id: String, show: ShowCalendar) {
        if (show == ShowCalendar.NEVER) return
        if (show == ShowCalendar.AUTO && id == TCal.ISO) return
        sb.append('[')
        if (show == ShowCalendar.CRITICAL) sb.append('!')
        sb.append("u-ca=").append(id).append(']')
    }

    private fun dateParts(sb: StringBuilder, d: IsoDate) {
        padIsoYear(sb, d.year)
        sb.append('-')
        pad2(sb, d.month)
        sb.append('-')
        pad2(sb, d.day)
    }

    /** TemporalDateToString */
    fun dateToString(d: IsoDate, cal: String, show: ShowCalendar): String {
        val sb = StringBuilder(24)
        dateParts(sb, d)
        calendarAnnotation(sb, cal, show)
        return sb.toString()
    }

    /** ISODateTimeToString */
    fun dateTimeToString(sb: StringBuilder, dt: IsoDateTime, cal: String, precision: Int, show: ShowCalendar) {
        dateParts(sb, dt.date)
        sb.append('T')
        val t = dt.time
        timeString(sb, t.hour, t.minute, t.second, t.subSecondNanos(), precision)
        calendarAnnotation(sb, cal, show)
    }

    fun dateTimeToString(dt: IsoDateTime, cal: String, precision: Int, show: ShowCalendar): String {
        val sb = StringBuilder(40)
        dateTimeToString(sb, dt, cal, precision, show)
        return sb.toString()
    }

    /** TemporalYearMonthToString */
    fun yearMonthToString(d: IsoDate, cal: String, show: ShowCalendar): String {
        val sb = StringBuilder(24)
        padIsoYear(sb, d.year)
        sb.append('-')
        pad2(sb, d.month)
        if (show == ShowCalendar.ALWAYS || show == ShowCalendar.CRITICAL || cal != TCal.ISO) {
            sb.append('-')
            pad2(sb, d.day)
        }
        calendarAnnotation(sb, cal, show)
        return sb.toString()
    }

    /** TemporalMonthDayToString */
    fun monthDayToString(d: IsoDate, cal: String, show: ShowCalendar): String {
        val sb = StringBuilder(24)
        if (show == ShowCalendar.ALWAYS || show == ShowCalendar.CRITICAL || cal != TCal.ISO) {
            padIsoYear(sb, d.year)
            sb.append('-')
        }
        pad2(sb, d.month)
        sb.append('-')
        pad2(sb, d.day)
        calendarAnnotation(sb, cal, show)
        return sb.toString()
    }

    /** TemporalInstantToString */
    fun instantToString(ns: BigInteger, tz: TimeZone?, precision: Int): String {
        val out = tz ?: TimeZone.UTC
        val dt = TZ.isoDateTimeFor(out, ns)
        val sb = StringBuilder(40)
        dateTimeToString(sb, dt, TCal.ISO, precision, ShowCalendar.NEVER)
        if (tz == null) sb.append('Z') else sb.append(formatOffsetRounded(TZ.offsetNs(out, ns)))
        return sb.toString()
    }

    /** TemporalZonedDateTimeToString */
    fun zonedToString(
        z: JSTemporalZonedDateTime, precision: Int, showCalendar: ShowCalendar, showTimeZone: Int, showOffset: Boolean,
        increment: Long = 1, unit: TUnit = TUnit.NANOSECOND, mode: RMode = RMode.TRUNC,
    ): String {
        var ns = z.epochNs
        if (!(increment == 1L && unit == TUnit.NANOSECOND)) {
            ns = TNum.roundAsIfPositive(ns, BigInteger.valueOf(increment * unit.nanos), mode)
        }
        val tz = z.timeZone
        val offsetNs = TZ.offsetNs(tz, ns)
        val dt = TZ.isoDateTimeFor(tz, ns)
        val sb = StringBuilder(48)
        dateTimeToString(sb, dt, TCal.ISO, precision, ShowCalendar.NEVER)
        if (showOffset) sb.append(formatOffsetRounded(offsetNs))
        if (showTimeZone != 1) {
            sb.append('[')
            if (showTimeZone == 2) sb.append('!')
            sb.append(tz.id).append(']')
        }
        calendarAnnotation(sb, z.calendar, showCalendar)
        return sb.toString()
    }

    private fun decimal(d: Double): String {
        val a = abs(d)
        if (a < 9.0e15) return a.toLong().toString()
        return BigDecimal(a).toBigInteger().toString()
    }

    /** TemporalDurationToString */
    fun durationToString(d: JSTemporalDuration, precision: Int): String {
        val sign = TDur.sign(d)
        val sb = StringBuilder(32)
        if (sign < 0) sb.append('-')
        sb.append('P')
        if (d.years != 0.0) sb.append(decimal(d.years)).append('Y')
        if (d.months != 0.0) sb.append(decimal(d.months)).append('M')
        if (d.weeks != 0.0) sb.append(decimal(d.weeks)).append('W')
        if (d.days != 0.0) sb.append(decimal(d.days)).append('D')
        val time = StringBuilder()
        if (d.hours != 0.0) time.append(decimal(d.hours)).append('H')
        if (d.minutes != 0.0) time.append(decimal(d.minutes)).append('M')
        val largest = TDur.defaultLargestUnit(d)
        val zeroMinutesAndHigher = largest.ordinal >= TUnit.SECOND.ordinal
        val secondsDuration = TDur.timeFromComponents(0.0, 0.0, d.seconds, d.milliseconds, d.microseconds, d.nanoseconds)
        if (secondsDuration.signum() != 0 || zeroMinutesAndHigher || precision != Precision.AUTO) {
            val qr = secondsDuration.abs().divideAndRemainder(TNum.BI_1E9)
            time.append(qr[0].toString())
            fractionalSeconds(time, qr[1].toInt(), precision)
            time.append('S')
        }
        if (time.isNotEmpty()) sb.append('T').append(time)
        return sb.toString()
    }
}
