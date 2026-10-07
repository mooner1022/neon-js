package io.neonjs.builtins.temporal

import io.neonjs.runtime.*
import java.math.BigInteger

internal class NudgeResult(@JvmField val duration: InternalDuration, @JvmField val nudgedNs: BigInteger, @JvmField val didExpand: Boolean)

internal class DiffSettings(@JvmField val smallest: TUnit, @JvmField val largest: TUnit, @JvmField val mode: RMode, @JvmField val increment: Long)

/** Duration records, time durations and relative rounding (the "Abstract Operations" of Temporal.Duration). */
internal object TDur {
    private val BI_NS_HOUR = BigInteger.valueOf(3_600_000_000_000L)
    private val BI_NS_MINUTE = BigInteger.valueOf(60_000_000_000L)

    // ------------------------------------------------------------------ records

    /** CreateDateDurationRecord */
    fun dateDuration(y: Long, mo: Long, w: Long, d: Long): DateDuration {
        if (!TCreate.isValidDuration(y.toDouble(), mo.toDouble(), w.toDouble(), d.toDouble(), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)) {
            tRangeErr("invalid duration")
        }
        return DateDuration(y, mo, w, d)
    }

    /** AdjustDateDurationRecord */
    fun adjust(dd: DateDuration, days: Long, weeks: Long = dd.weeks, months: Long = dd.months): DateDuration =
        dateDuration(dd.years, months, weeks, days)

    /** TimeDurationFromComponents */
    fun timeFromComponents(h: Double, mi: Double, s: Double, ms: Double, us: Double, ns: Double): BigInteger {
        // each term stays below 6e17 in magnitude, so the Long sum cannot overflow
        if (Math.abs(h) < 1e5 && Math.abs(mi) < 1e7 && Math.abs(s) < 1e8 && Math.abs(ms) < 1e11 && Math.abs(us) < 1e14 && Math.abs(ns) < 1e17) {
            val total = h.toLong() * 3_600_000_000_000L + mi.toLong() * 60_000_000_000L + s.toLong() * 1_000_000_000L +
                ms.toLong() * 1_000_000L + us.toLong() * 1000L + ns.toLong()
            return BigInteger.valueOf(total)
        }
        return TNum.big(h).multiply(BI_NS_HOUR).add(TNum.big(mi).multiply(BI_NS_MINUTE)).add(TNum.big(s).multiply(TNum.BI_1E9))
            .add(TNum.big(ms).multiply(TNum.BI_1E6)).add(TNum.big(us).multiply(TNum.BI_1000)).add(TNum.big(ns))
    }

    private fun checkTime(d: BigInteger): BigInteger {
        if (d.abs() > TM.MAX_TIME_DURATION) tRangeErr("time duration out of range")
        return d
    }

    /** AddTimeDuration */
    fun addTime(a: BigInteger, b: BigInteger): BigInteger = checkTime(a.add(b))

    /** Add24HourDaysToTimeDuration */
    fun add24HourDays(d: BigInteger, days: Long): BigInteger =
        if (days == 0L) checkTime(d) else checkTime(d.add(BigInteger.valueOf(days).multiply(TM.BI_NS_PER_DAY)))

    /** RoundTimeDurationToIncrement */
    fun roundTimeToIncrement(d: BigInteger, inc: BigInteger, mode: RMode): BigInteger = checkTime(TNum.roundToIncrement(d, inc, mode))

    /** RoundTimeDuration */
    fun roundTime(d: BigInteger, inc: Long, unit: TUnit, mode: RMode): BigInteger =
        roundTimeToIncrement(d, BigInteger.valueOf(unit.nanos).multiply(BigInteger.valueOf(inc)), mode)

    /** TotalTimeDuration */
    fun totalTime(d: BigInteger, unit: TUnit): Double = TNum.divToDouble(d, unit.nanos)

    /** ToInternalDurationRecord */
    fun toInternal(d: JSTemporalDuration): InternalDuration = InternalDuration(
        DateDuration(d.years.toLong(), d.months.toLong(), d.weeks.toLong(), d.days.toLong()),
        timeFromComponents(d.hours, d.minutes, d.seconds, d.milliseconds, d.microseconds, d.nanoseconds),
    )

    /** ToInternalDurationRecordWith24HourDays */
    fun toInternal24(d: JSTemporalDuration): InternalDuration {
        val t = add24HourDays(timeFromComponents(d.hours, d.minutes, d.seconds, d.milliseconds, d.microseconds, d.nanoseconds), d.days.toLong())
        return InternalDuration(DateDuration(d.years.toLong(), d.months.toLong(), d.weeks.toLong(), 0), t)
    }

    /** ToDateDurationRecordWithoutTime */
    fun toDateDurationWithoutTime(d: JSTemporalDuration): DateDuration {
        val id = toInternal24(d)
        val days = id.time.divide(TM.BI_NS_PER_DAY).toLong()
        return DateDuration(id.date.years, id.date.months, id.date.weeks, days)
    }

    /** TemporalDurationFromInternal */
    fun fromInternal(id: InternalDuration, largest: TUnit): JSTemporalDuration {
        val sign = id.time.signum()
        var ns = id.time.abs()
        var us = BigInteger.ZERO
        var ms = BigInteger.ZERO
        var s = BigInteger.ZERO
        var mi = BigInteger.ZERO
        var h = BigInteger.ZERO
        var days = BigInteger.ZERO
        if (largest.ordinal <= TUnit.MICROSECOND.ordinal) {
            val a = ns.divideAndRemainder(TNum.BI_1000); us = a[0]; ns = a[1]
            if (largest.ordinal <= TUnit.MILLISECOND.ordinal) {
                val b = us.divideAndRemainder(TNum.BI_1000); ms = b[0]; us = b[1]
                if (largest.ordinal <= TUnit.SECOND.ordinal) {
                    val c = ms.divideAndRemainder(TNum.BI_1000); s = c[0]; ms = c[1]
                    if (largest.ordinal <= TUnit.MINUTE.ordinal) {
                        val e = s.divideAndRemainder(TNum.BI_60); mi = e[0]; s = e[1]
                        if (largest.ordinal <= TUnit.HOUR.ordinal) {
                            val f = mi.divideAndRemainder(TNum.BI_60); h = f[0]; mi = f[1]
                            if (largest.isDate) {
                                val g = h.divideAndRemainder(BigInteger.valueOf(24)); days = g[0]; h = g[1]
                            }
                        }
                    }
                }
            }
        }
        fun sd(v: BigInteger): Double = if (sign < 0) -v.toDouble() + 0.0 else v.toDouble()
        val totalDays = BigInteger.valueOf(id.date.days).add(if (sign < 0) days.negate() else days).toDouble()
        return TCreate.duration(
            id.date.years.toDouble(), id.date.months.toDouble(), id.date.weeks.toDouble(), totalDays,
            sd(h), sd(mi), sd(s), sd(ms), sd(us), sd(ns),
        )
    }

    fun negated(d: JSTemporalDuration): JSTemporalDuration = TCreate.duration(
        TNum.neg(d.years), TNum.neg(d.months), TNum.neg(d.weeks), TNum.neg(d.days), TNum.neg(d.hours),
        TNum.neg(d.minutes), TNum.neg(d.seconds), TNum.neg(d.milliseconds), TNum.neg(d.microseconds), TNum.neg(d.nanoseconds),
    )

    fun sign(d: JSTemporalDuration): Int {
        for (v in doubleArrayOf(d.years, d.months, d.weeks, d.days, d.hours, d.minutes, d.seconds, d.milliseconds, d.microseconds, d.nanoseconds)) {
            if (v < 0) return -1
            if (v > 0) return 1
        }
        return 0
    }

    /** DefaultTemporalLargestUnit */
    fun defaultLargestUnit(d: JSTemporalDuration): TUnit = when {
        d.years != 0.0 -> TUnit.YEAR
        d.months != 0.0 -> TUnit.MONTH
        d.weeks != 0.0 -> TUnit.WEEK
        d.days != 0.0 -> TUnit.DAY
        d.hours != 0.0 -> TUnit.HOUR
        d.minutes != 0.0 -> TUnit.MINUTE
        d.seconds != 0.0 -> TUnit.SECOND
        d.milliseconds != 0.0 -> TUnit.MILLISECOND
        d.microseconds != 0.0 -> TUnit.MICROSECOND
        else -> TUnit.NANOSECOND
    }

    private val PARTIAL_KEYS = arrayOf("days", "hours", "microseconds", "milliseconds", "minutes", "months", "nanoseconds", "seconds", "weeks", "years")
    private val PARTIAL_SLOTS = intArrayOf(3, 4, 8, 7, 5, 1, 9, 6, 2, 0)

    /** ToTemporalPartialDurationRecord: NaN marks undefined fields. */
    fun toPartial(item: Any?): DoubleArray {
        if (item !is JSObject) tTypeErr("duration-like must be an object")
        val out = DoubleArray(10) { Double.NaN }
        var any = false
        for (k in PARTIAL_KEYS.indices) {
            val v = item.get(PARTIAL_KEYS[k], item)
            if (v !== Undefined) {
                out[PARTIAL_SLOTS[k]] = toIntegerIfIntegral(v)
                any = true
            }
        }
        if (!any) tTypeErr("duration-like object must have at least one duration property")
        return out
    }

    /** ToTemporalDuration */
    fun toDuration(item: Any?): JSTemporalDuration {
        if (item is JSTemporalDuration) {
            return TCreate.duration(item.years, item.months, item.weeks, item.days, item.hours, item.minutes, item.seconds, item.milliseconds, item.microseconds, item.nanoseconds)
        }
        if (item !is JSObject) {
            if (item !is CharSequence) tTypeErr("cannot convert ${Ops.typeOf(item)} to a duration")
            return TCreate.duration(TemporalParser.parseDuration(item.toString()))
        }
        val p = toPartial(item)
        for (k in 0 until 10) if (p[k].isNaN()) p[k] = 0.0
        return TCreate.duration(p)
    }

    /** AddDurations */
    fun addDurations(subtract: Boolean, d: JSTemporalDuration, otherV: Any?): JSTemporalDuration {
        var other = toDuration(otherV)
        if (subtract) other = negated(other)
        val largest = TUnit.larger(defaultLargestUnit(d), defaultLargestUnit(other))
        if (largest.isCalendar) tRangeErr("cannot add durations with calendar units without a relative date")
        val d1 = toInternal24(d)
        val d2 = toInternal24(other)
        val t = addTime(d1.time, d2.time)
        return fromInternal(InternalDuration(DateDuration.ZERO, t), largest)
    }

    /** DateDurationDays */
    fun dateDurationDays(dd: DateDuration, rel: JSTemporalPlainDate): Long {
        val ymw = adjust(dd, 0)
        if (ymw.sign() == 0) return dd.days
        val later = TCal.dateAdd(rel.calendar, rel.date, ymw, Overflow.CONSTRAIN)
        return dd.days + (TM.epochDays(later) - TM.epochDays(rel.date))
    }

    // ------------------------------------------------------------------ difference settings

    /** GetDifferenceSettings; [group]: 0 date, 1 time, 2 datetime. */
    fun differenceSettings(since: Boolean, options: JSObject, group: Int, disallowed: Array<TUnit>, fallbackSmallest: TUnit, smallestLargestDefault: TUnit): DiffSettings {
        var largest = TOpt.unit(options, "largestUnit", false)
        val inc = TOpt.roundingIncrement(options)
        var mode = TOpt.roundingMode(options, RMode.TRUNC)
        var smallest = TOpt.unit(options, "smallestUnit", false)
        TOpt.validateUnit(largest, group, intArrayOf(TOpt.UNIT_AUTO))
        if (largest == TOpt.UNIT_UNSET) largest = TOpt.UNIT_AUTO
        if (largest >= 0 && TUnit.ALL[largest] in disallowed) tRangeErr("largestUnit ${TUnit.ALL[largest].singular} is not allowed")
        TOpt.validateUnit(smallest, group)
        if (smallest == TOpt.UNIT_UNSET) smallest = fallbackSmallest.ordinal
        val smallestU = TUnit.ALL[smallest]
        if (smallestU in disallowed) tRangeErr("smallestUnit ${smallestU.singular} is not allowed")
        val defaultLargest = TUnit.larger(smallestLargestDefault, smallestU)
        val largestU = if (largest == TOpt.UNIT_AUTO) defaultLargest else TUnit.ALL[largest]
        if (TUnit.larger(largestU, smallestU) != largestU) tRangeErr("largestUnit must be larger than smallestUnit")
        if (smallestU.maxIncrement != 0) TOpt.validateIncrement(inc, smallestU.maxIncrement.toLong(), false)
        if (since) mode = mode.negate()
        return DiffSettings(smallestU, largestU, mode, inc)
    }

    // ------------------------------------------------------------------ epoch helpers

    private fun epochNsOf(d: IsoDate, t: TimeRec, tz: TimeZone?): BigInteger =
        if (tz == null) TM.utcEpochNs(d, t) else TZ.epochNsFor(tz, IsoDateTime(d, t), Disambiguation.COMPATIBLE)

    /** AddInstant */
    fun addInstant(ns: BigInteger, d: BigInteger): BigInteger {
        val r = ns.add(d)
        if (!TM.isValidEpochNs(r)) tRangeErr("instant outside of supported range")
        return r
    }

    /** AddZonedDateTime */
    fun addZoned(ns: BigInteger, tz: TimeZone, cal: String, d: InternalDuration, overflow: Overflow): BigInteger {
        if (d.date.sign() == 0) return addInstant(ns, d.time)
        val dt = TZ.isoDateTimeFor(tz, ns)
        val added = TCal.dateAdd(cal, dt.date, d.date, overflow)
        val intermediate = IsoDateTime(added, dt.time)
        if (!TM.isoDateTimeWithinLimits(intermediate)) tRangeErr("date-time outside of supported range")
        val intermediateNs = TZ.epochNsFor(tz, intermediate, Disambiguation.COMPATIBLE)
        return addInstant(intermediateNs, d.time)
    }

    // ------------------------------------------------------------------ differences

    /** DifferenceISODateTime */
    fun differenceIsoDateTime(dt1: IsoDateTime, dt2: IsoDateTime, cal: String, largest: TUnit): InternalDuration {
        var timeNs = TM.differenceTime(dt1.time, dt2.time)
        val timeSign = java.lang.Long.signum(timeNs)
        val dateSign = TM.compareDate(dt1.date, dt2.date)
        var adjusted = dt2.date
        if (timeSign == dateSign) {
            adjusted = TM.addDays(adjusted, timeSign.toLong())
            timeNs -= timeSign * TM.NS_PER_DAY
        }
        val dateLargest = TUnit.larger(TUnit.DAY, largest)
        var dateDiff = TCal.dateUntil(cal, dt1.date, adjusted, dateLargest)
        var time = BigInteger.valueOf(timeNs)
        if (largest != dateLargest) {
            time = add24HourDays(time, dateDiff.days)
            dateDiff = DateDuration(dateDiff.years, dateDiff.months, dateDiff.weeks, 0)
        }
        return InternalDuration(dateDiff, time)
    }

    /** DifferencePlainDateTimeWithRounding */
    fun differencePlainDateTimeWithRounding(dt1: IsoDateTime, dt2: IsoDateTime, cal: String, largest: TUnit, inc: Long, smallest: TUnit, mode: RMode): InternalDuration {
        if (TM.compareDateTime(dt1, dt2) == 0) return InternalDuration(DateDuration.ZERO, BigInteger.ZERO)
        if (!TM.isoDateTimeWithinLimits(dt1) || !TM.isoDateTimeWithinLimits(dt2)) tRangeErr("date-time outside of supported range")
        val diff = differenceIsoDateTime(dt1, dt2, cal, largest)
        if (smallest == TUnit.NANOSECOND && inc == 1L) return diff
        val origin = TM.utcEpochNs(dt1)
        val dest = TM.utcEpochNs(dt2)
        return roundRelative(diff, origin, dest, dt1, null, cal, largest, inc, smallest, mode)
    }

    /** DifferencePlainDateTimeWithTotal */
    fun differencePlainDateTimeWithTotal(dt1: IsoDateTime, dt2: IsoDateTime, cal: String, unit: TUnit): Double {
        if (TM.compareDateTime(dt1, dt2) == 0) return 0.0
        if (!TM.isoDateTimeWithinLimits(dt1) || !TM.isoDateTimeWithinLimits(dt2)) tRangeErr("date-time outside of supported range")
        val diff = differenceIsoDateTime(dt1, dt2, cal, unit)
        if (unit == TUnit.NANOSECOND) return diff.time.toDouble()
        val origin = TM.utcEpochNs(dt1)
        val dest = TM.utcEpochNs(dt2)
        return totalRelative(diff, origin, dest, dt1, null, cal, unit)
    }

    /** DifferenceInstant */
    fun differenceInstant(ns1: BigInteger, ns2: BigInteger, inc: Long, smallest: TUnit, mode: RMode): InternalDuration =
        InternalDuration(DateDuration.ZERO, roundTime(ns2.subtract(ns1), inc, smallest, mode))

    /** DifferenceZonedDateTime */
    fun differenceZoned(ns1: BigInteger, ns2: BigInteger, tz: TimeZone, cal: String, largest: TUnit): InternalDuration {
        if (ns1 == ns2) return InternalDuration(DateDuration.ZERO, BigInteger.ZERO)
        val start = TZ.isoDateTimeFor(tz, ns1)
        val end = TZ.isoDateTimeFor(tz, ns2)
        if (TM.compareDate(start.date, end.date) == 0) return InternalDuration(DateDuration.ZERO, ns2.subtract(ns1))
        val sign = if (ns2 < ns1) 1 else -1
        val maxDayCorrection = if (sign == -1) 2 else 1
        var dayCorrection = 0
        var timeDuration = BigInteger.valueOf(TM.differenceTime(start.time, end.time))
        if (timeDuration.signum() == sign) dayCorrection++
        var success = false
        var intermediate = end
        while (dayCorrection <= maxDayCorrection && !success) {
            val idate = TM.addDays(end.date, (dayCorrection * sign).toLong())
            intermediate = IsoDateTime(idate, start.time)
            val ins = TZ.epochNsFor(tz, intermediate, Disambiguation.COMPATIBLE)
            timeDuration = ns2.subtract(ins)
            if (sign != timeDuration.signum()) success = true
            dayCorrection++
        }
        val dateLargest = TUnit.larger(largest, TUnit.DAY)
        val dateDiff = TCal.dateUntil(cal, start.date, intermediate.date, dateLargest)
        return InternalDuration(dateDiff, timeDuration)
    }

    /** DifferenceZonedDateTimeWithRounding */
    fun differenceZonedWithRounding(ns1: BigInteger, ns2: BigInteger, tz: TimeZone, cal: String, largest: TUnit, inc: Long, smallest: TUnit, mode: RMode): InternalDuration {
        if (!largest.isDate) return differenceInstant(ns1, ns2, inc, smallest, mode)
        val diff = differenceZoned(ns1, ns2, tz, cal, largest)
        if (smallest == TUnit.NANOSECOND && inc == 1L) return diff
        val dt = TZ.isoDateTimeFor(tz, ns1)
        return roundRelative(diff, ns1, ns2, dt, tz, cal, largest, inc, smallest, mode)
    }

    /** DifferenceZonedDateTimeWithTotal */
    fun differenceZonedWithTotal(ns1: BigInteger, ns2: BigInteger, tz: TimeZone, cal: String, unit: TUnit): Double {
        if (!unit.isDate) return totalTime(ns2.subtract(ns1), unit)
        val diff = differenceZoned(ns1, ns2, tz, cal, unit)
        val dt = TZ.isoDateTimeFor(tz, ns1)
        return totalRelative(diff, ns1, ns2, dt, tz, cal, unit)
    }

    // ------------------------------------------------------------------ relative rounding

    private class Window(
        @JvmField val r1: Long,
        @JvmField val r2: Long,
        @JvmField val startNs: BigInteger,
        @JvmField val endNs: BigInteger,
        @JvmField val startDur: DateDuration,
        @JvmField val endDur: DateDuration,
    )

    /** ComputeNudgeWindow */
    private fun nudgeWindow(sign: Int, d: InternalDuration, originNs: BigInteger, dt: IsoDateTime, tz: TimeZone?, cal: String, inc: Long, unit: TUnit, additionalShift: Boolean): Window {
        val r1: Long
        val r2: Long
        val startDD: DateDuration
        val endDD: DateDuration
        when (unit) {
            TUnit.YEAR -> {
                val years = TNum.truncToIncrement(d.date.years, inc)
                r1 = if (!additionalShift) years else years + inc * sign
                r2 = r1 + inc * sign
                startDD = dateDuration(r1, 0, 0, 0)
                endDD = dateDuration(r2, 0, 0, 0)
            }
            TUnit.MONTH -> {
                val months = TNum.truncToIncrement(d.date.months, inc)
                r1 = if (!additionalShift) months else months + inc * sign
                r2 = r1 + inc * sign
                startDD = adjust(d.date, 0, 0, r1)
                endDD = adjust(d.date, 0, 0, r2)
            }
            TUnit.WEEK -> {
                val yearsMonths = adjust(d.date, 0, 0)
                val weeksStart = TCal.dateAdd(cal, dt.date, yearsMonths, Overflow.CONSTRAIN)
                val weeksEnd = TM.addDays(weeksStart, d.date.days)
                val untilResult = TCal.dateUntil(cal, weeksStart, weeksEnd, TUnit.WEEK)
                val weeks = TNum.truncToIncrement(d.date.weeks + untilResult.weeks, inc)
                r1 = weeks
                r2 = weeks + inc * sign
                startDD = adjust(d.date, 0, r1)
                endDD = adjust(d.date, 0, r2)
            }
            else -> {
                val days = TNum.truncToIncrement(d.date.days, inc)
                r1 = days
                r2 = days + inc * sign
                startDD = adjust(d.date, r1)
                endDD = adjust(d.date, r2)
            }
        }
        // when the window starts at the origin itself, use the origin's exact epoch nanoseconds
        val startNs = if (startDD.sign() == 0) originNs else {
            val start = TCal.dateAdd(cal, dt.date, startDD, Overflow.CONSTRAIN)
            epochNsOf(start, dt.time, tz)
        }
        val end = TCal.dateAdd(cal, dt.date, endDD, Overflow.CONSTRAIN)
        val endNs = epochNsOf(end, dt.time, tz)
        return Window(r1, r2, startNs, endNs, startDD, endDD)
    }

    private fun between(lo: BigInteger, x: BigInteger, hi: BigInteger): Boolean = lo <= x && x <= hi

    /** NudgeToCalendarUnit; the total is stored in [totalOut][0] when given. */
    private fun nudgeToCalendarUnit(
        sign: Int, d: InternalDuration, originNs: BigInteger, destNs: BigInteger, dt: IsoDateTime, tz: TimeZone?, cal: String, inc: Long,
        unit: TUnit, mode: RMode, totalOut: DoubleArray?,
    ): NudgeResult {
        var didExpand = false
        var w = nudgeWindow(sign, d, originNs, dt, tz, cal, inc, unit, false)
        val inside = if (sign == 1) between(w.startNs, destNs, w.endNs) else between(w.endNs, destNs, w.startNs)
        if (!inside) {
            w = nudgeWindow(sign, d, originNs, dt, tz, cal, inc, unit, true)
            didExpand = true
        }
        val num = destNs.subtract(w.startNs)
        val den = w.endNs.subtract(w.startNs)
        if (totalOut != null) {
            // total = r1 + (num / den) * inc * sign
            val n = BigInteger.valueOf(w.r1).multiply(den).add(num.multiply(BigInteger.valueOf(inc * sign)))
            totalOut[0] = TNum.divToDouble(n, den)
        }
        val up: Boolean
        if (num == den) {
            up = true
        } else if (num.signum() == 0) {
            up = false
        } else {
            val urm = mode.unsigned(sign < 0)
            val cmp = num.abs().shiftLeft(1).compareTo(den.abs())
            up = when (urm) {
                URM.ZERO -> false
                URM.INFINITY -> true
                else -> when {
                    cmp < 0 -> false
                    cmp > 0 -> true
                    urm == URM.HALF_ZERO -> false
                    urm == URM.HALF_INFINITY -> true
                    else -> (Math.abs(w.r1) / inc) % 2 != 0L
                }
            }
        }
        return if (up) {
            NudgeResult(InternalDuration(w.endDur, BigInteger.ZERO), w.endNs, true)
        } else {
            NudgeResult(InternalDuration(w.startDur, BigInteger.ZERO), w.startNs, didExpand)
        }
    }

    /** NudgeToZonedTime */
    private fun nudgeToZonedTime(sign: Int, d: InternalDuration, dt: IsoDateTime, tz: TimeZone, cal: String, inc: Long, unit: TUnit, mode: RMode): NudgeResult {
        val start = TCal.dateAdd(cal, dt.date, d.date, Overflow.CONSTRAIN)
        val endDate = TM.addDays(start, sign.toLong())
        val startNs = TZ.epochNsFor(tz, IsoDateTime(start, dt.time), Disambiguation.COMPATIBLE)
        val endNs = TZ.epochNsFor(tz, IsoDateTime(endDate, dt.time), Disambiguation.COMPATIBLE)
        val daySpan = endNs.subtract(startNs)
        val incNs = BigInteger.valueOf(unit.nanos).multiply(BigInteger.valueOf(inc))
        var rounded = roundTimeToIncrement(d.time, incNs, mode)
        val beyond = rounded.subtract(daySpan)
        val didRoundBeyondDay: Boolean
        val dayDelta: Long
        val nudged: BigInteger
        if (beyond.signum() != -sign) {
            didRoundBeyondDay = true
            dayDelta = sign.toLong()
            rounded = roundTimeToIncrement(beyond, incNs, mode)
            nudged = endNs.add(rounded)
        } else {
            didRoundBeyondDay = false
            dayDelta = 0
            nudged = startNs.add(rounded)
        }
        val dd = adjust(d.date, d.date.days + dayDelta)
        return NudgeResult(InternalDuration(dd, rounded), nudged, didRoundBeyondDay)
    }

    /** NudgeToDayOrTime */
    private fun nudgeToDayOrTime(d: InternalDuration, destNs: BigInteger, largest: TUnit, inc: Long, smallest: TUnit, mode: RMode): NudgeResult {
        val time = add24HourDays(d.time, d.date.days)
        val incNs = BigInteger.valueOf(smallest.nanos).multiply(BigInteger.valueOf(inc))
        val roundedTime = roundTimeToIncrement(time, incNs, mode)
        val diffTime = roundedTime.subtract(time)
        val wholeDays = time.divide(TM.BI_NS_PER_DAY)
        val roundedWholeDays = roundedTime.divide(TM.BI_NS_PER_DAY)
        val dayDelta = roundedWholeDays.subtract(wholeDays)
        val didExpandDays = dayDelta.signum() == time.signum()
        val nudged = destNs.add(diffTime)
        var days = 0L
        var remainder = roundedTime
        if (largest.isDate) {
            days = roundedWholeDays.toLong()
            remainder = roundedTime.subtract(roundedWholeDays.multiply(TM.BI_NS_PER_DAY))
        }
        val dd = adjust(d.date, days)
        return NudgeResult(InternalDuration(dd, remainder), nudged, didExpandDays)
    }

    /** BubbleRelativeDuration */
    private fun bubble(sign: Int, d0: InternalDuration, nudgedNs: BigInteger, dt: IsoDateTime, tz: TimeZone?, cal: String, largest: TUnit, smallest: TUnit): InternalDuration {
        var d = d0
        if (smallest == largest) return d
        var idx = smallest.ordinal - 1
        while (idx >= largest.ordinal) {
            val unit = TUnit.ALL[idx]
            if (unit != TUnit.WEEK || largest == TUnit.WEEK) {
                val endDD = when (unit) {
                    TUnit.YEAR -> dateDuration(d.date.years + sign, 0, 0, 0)
                    TUnit.MONTH -> adjust(d.date, 0, 0, d.date.months + sign)
                    else -> adjust(d.date, 0, d.date.weeks + sign)
                }
                val end = TCal.dateAdd(cal, dt.date, endDD, Overflow.CONSTRAIN)
                val endNs = epochNsOf(end, dt.time, tz)
                val beyondSign = nudgedNs.subtract(endNs).signum()
                if (beyondSign != -sign) {
                    d = InternalDuration(endDD, BigInteger.ZERO)
                } else {
                    break
                }
            }
            idx--
        }
        return d
    }

    /** RoundRelativeDuration */
    fun roundRelative(
        d: InternalDuration, originNs: BigInteger, destNs: BigInteger, dt: IsoDateTime, tz: TimeZone?, cal: String,
        largest: TUnit, inc: Long, smallest: TUnit, mode: RMode,
    ): InternalDuration {
        val irregular = smallest.isCalendar || (tz != null && smallest == TUnit.DAY)
        val sign = if (d.sign() < 0) -1 else 1
        val nudge = when {
            irregular -> nudgeToCalendarUnit(sign, d, originNs, destNs, dt, tz, cal, inc, smallest, mode, null)
            tz != null -> nudgeToZonedTime(sign, d, dt, tz, cal, inc, smallest, mode)
            else -> nudgeToDayOrTime(d, destNs, largest, inc, smallest, mode)
        }
        var result = nudge.duration
        if (nudge.didExpand && smallest != TUnit.WEEK) {
            val startUnit = TUnit.larger(smallest, TUnit.DAY)
            result = bubble(sign, result, nudge.nudgedNs, dt, tz, cal, largest, startUnit)
        }
        return result
    }

    /** TotalRelativeDuration */
    fun totalRelative(d: InternalDuration, originNs: BigInteger, destNs: BigInteger, dt: IsoDateTime, tz: TimeZone?, cal: String, unit: TUnit): Double {
        if (unit.isCalendar || (tz != null && unit == TUnit.DAY)) {
            val sign = if (d.sign() < 0) -1 else 1
            val out = DoubleArray(1)
            nudgeToCalendarUnit(sign, d, originNs, destNs, dt, tz, cal, 1, unit, RMode.TRUNC, out)
            return out[0]
        }
        val time = add24HourDays(d.time, d.date.days)
        return totalTime(time, unit)
    }
}
