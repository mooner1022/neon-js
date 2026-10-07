package io.neonjs.builtins.temporal

import io.neonjs.builtins.getter
import io.neonjs.builtins.makeCtor
import io.neonjs.builtins.method
import io.neonjs.runtime.*
import java.math.BigInteger

internal object DurationBuiltins {
    private fun thisDuration(t: Any?, name: String): JSTemporalDuration =
        t as? JSTemporalDuration ?: tTypeErr("Temporal.Duration.prototype.$name called on incompatible receiver")

    fun install(realm: Realm, temporal: JSObject) {
        val proto = JSObject(realm.objectPrototype)
        realm.intrinsics[TCreate.P_DURATION] = proto
        val ctor = makeCtor(realm, "Duration", 0, proto) { _, _, args, nt ->
            if (nt == null) tTypeErr("Constructor Temporal.Duration requires 'new'")
            val f = DoubleArray(10)
            for (i in 0 until 10) {
                val v = args.arg(i)
                f[i] = if (v === Undefined) 0.0 else toIntegerIfIntegral(v)
            }
            TCreate.duration(f, nt)
        }
        temporal.defineOwn("Duration", ctor, Attr.WC)
        ctor.method(realm, "from", 1) { _, _, args, _ -> TDur.toDuration(args.arg(0)) }
        ctor.method(realm, "compare", 2) { _, _, args, _ -> compare(args.arg(0), args.arg(1), args.arg(2)) }
        proto.defineOwn(JSSymbol.toStringTag, "Temporal.Duration", Attr.CONFIGURABLE)
        installGetters(realm, proto)
        installMethods(realm, proto)
    }

    private fun installGetters(realm: Realm, proto: JSObject) {
        proto.getter(realm, "years") { _, t, _, _ -> thisDuration(t, "years").years }
        proto.getter(realm, "months") { _, t, _, _ -> thisDuration(t, "months").months }
        proto.getter(realm, "weeks") { _, t, _, _ -> thisDuration(t, "weeks").weeks }
        proto.getter(realm, "days") { _, t, _, _ -> thisDuration(t, "days").days }
        proto.getter(realm, "hours") { _, t, _, _ -> thisDuration(t, "hours").hours }
        proto.getter(realm, "minutes") { _, t, _, _ -> thisDuration(t, "minutes").minutes }
        proto.getter(realm, "seconds") { _, t, _, _ -> thisDuration(t, "seconds").seconds }
        proto.getter(realm, "milliseconds") { _, t, _, _ -> thisDuration(t, "milliseconds").milliseconds }
        proto.getter(realm, "microseconds") { _, t, _, _ -> thisDuration(t, "microseconds").microseconds }
        proto.getter(realm, "nanoseconds") { _, t, _, _ -> thisDuration(t, "nanoseconds").nanoseconds }
        proto.getter(realm, "sign") { _, t, _, _ -> TDur.sign(thisDuration(t, "sign")).toDouble() }
        proto.getter(realm, "blank") { _, t, _, _ -> TDur.sign(thisDuration(t, "blank")) == 0 }
    }

    private fun installMethods(realm: Realm, proto: JSObject) {
        proto.method(realm, "with", 1) { _, t, args, _ ->
            val d = thisDuration(t, "with")
            val p = TDur.toPartial(args.arg(0))
            val cur = doubleArrayOf(d.years, d.months, d.weeks, d.days, d.hours, d.minutes, d.seconds, d.milliseconds, d.microseconds, d.nanoseconds)
            for (k in 0 until 10) if (!p[k].isNaN()) cur[k] = p[k]
            TCreate.duration(cur)
        }
        proto.method(realm, "negated", 0) { _, t, _, _ -> TDur.negated(thisDuration(t, "negated")) }
        proto.method(realm, "abs", 0) { _, t, _, _ ->
            val d = thisDuration(t, "abs")
            TCreate.duration(
                Math.abs(d.years), Math.abs(d.months), Math.abs(d.weeks), Math.abs(d.days), Math.abs(d.hours), Math.abs(d.minutes),
                Math.abs(d.seconds), Math.abs(d.milliseconds), Math.abs(d.microseconds), Math.abs(d.nanoseconds),
            )
        }
        proto.method(realm, "add", 1) { _, t, args, _ -> TDur.addDurations(false, thisDuration(t, "add"), args.arg(0)) }
        proto.method(realm, "subtract", 1) { _, t, args, _ -> TDur.addDurations(true, thisDuration(t, "subtract"), args.arg(0)) }
        proto.method(realm, "round", 1) { _, t, args, _ -> round(thisDuration(t, "round"), args.arg(0)) }
        proto.method(realm, "total", 1) { _, t, args, _ -> total(thisDuration(t, "total"), args.arg(0)) }
        proto.method(realm, "toString", 0) { _, t, args, _ -> toStringImpl(thisDuration(t, "toString"), args.arg(0)) }
        proto.method(realm, "toJSON", 0) { _, t, _, _ -> TFormat.durationToString(thisDuration(t, "toJSON"), Precision.AUTO) }
        proto.method(realm, "toLocaleString", 0) { _, t, _, _ -> TFormat.durationToString(thisDuration(t, "toLocaleString"), Precision.AUTO) }
        proto.method(realm, "valueOf", 0) { _, _, _, _ ->
            tTypeErr("use Temporal.Duration.compare() or toString() instead of valueOf()")
        }
    }

    private fun compare(a: Any?, b: Any?, options: Any?): Double {
        val one = TDur.toDuration(a)
        val two = TDur.toDuration(b)
        val o = TOpt.optionsObject(options)
        val rel = TConv.relativeTo(o)
        if (one.years == two.years && one.months == two.months && one.weeks == two.weeks && one.days == two.days &&
            one.hours == two.hours && one.minutes == two.minutes && one.seconds == two.seconds &&
            one.milliseconds == two.milliseconds && one.microseconds == two.microseconds && one.nanoseconds == two.nanoseconds
        ) return 0.0
        val zoned = rel as? JSTemporalZonedDateTime
        val plain = rel as? JSTemporalPlainDate
        val l1 = TDur.defaultLargestUnit(one)
        val l2 = TDur.defaultLargestUnit(two)
        val d1 = TDur.toInternal(one)
        val d2 = TDur.toInternal(two)
        if (zoned != null && (l1.isDate || l2.isDate)) {
            val after1 = TDur.addZoned(zoned.epochNs, zoned.timeZone, zoned.calendar, d1, Overflow.CONSTRAIN)
            val after2 = TDur.addZoned(zoned.epochNs, zoned.timeZone, zoned.calendar, d2, Overflow.CONSTRAIN)
            return after1.compareTo(after2).coerceIn(-1, 1).toDouble()
        }
        val days1: Long
        val days2: Long
        if (l1.isCalendar || l2.isCalendar) {
            if (plain == null) tRangeErr("a relativeTo date is required to compare durations with calendar units")
            days1 = TDur.dateDurationDays(d1.date, plain)
            days2 = TDur.dateDurationDays(d2.date, plain)
        } else {
            days1 = one.days.toLong()
            days2 = two.days.toLong()
        }
        val t1 = TDur.add24HourDays(d1.time, days1)
        val t2 = TDur.add24HourDays(d2.time, days2)
        return t1.compareTo(t2).coerceIn(-1, 1).toDouble()
    }

    private fun optionsFromString(v: Any?, key: String): JSObject {
        if (v === Undefined) tTypeErr("options argument is required")
        if (v is CharSequence) {
            val o = JSObject(null)
            o.createDataPropertyOrThrow(key, v.toString())
            return o
        }
        return TOpt.optionsObject(v)
    }

    private fun round(d: JSTemporalDuration, roundToV: Any?): JSTemporalDuration {
        val roundTo = optionsFromString(roundToV, "smallestUnit")
        var smallestPresent = true
        var largestPresent = true
        val largestOpt = TOpt.unit(roundTo, "largestUnit", false)
        val rel = TConv.relativeTo(roundTo)
        val zoned = rel as? JSTemporalZonedDateTime
        val plain = rel as? JSTemporalPlainDate
        val inc = TOpt.roundingIncrement(roundTo)
        val mode = TOpt.roundingMode(roundTo, RMode.HALF_EXPAND)
        var smallestOpt = TOpt.unit(roundTo, "smallestUnit", false)
        TOpt.validateUnit(smallestOpt, 2)
        if (smallestOpt == TOpt.UNIT_UNSET) {
            smallestPresent = false
            smallestOpt = TUnit.NANOSECOND.ordinal
        }
        val smallest = TUnit.ALL[smallestOpt]
        val existingLargest = TDur.defaultLargestUnit(d)
        val defaultLargest = TUnit.larger(existingLargest, smallest)
        var largest: TUnit
        if (largestOpt == TOpt.UNIT_UNSET) {
            largestPresent = false
            largest = defaultLargest
        } else if (largestOpt == TOpt.UNIT_AUTO) {
            largest = defaultLargest
        } else {
            largest = TUnit.ALL[largestOpt]
        }
        if (!smallestPresent && !largestPresent) tRangeErr("at least one of smallestUnit or largestUnit is required")
        if (TUnit.larger(largest, smallest) != largest) tRangeErr("largestUnit must be larger than smallestUnit")
        if (smallest.maxIncrement != 0) TOpt.validateIncrement(inc, smallest.maxIncrement.toLong(), false)
        if (inc > 1 && largest != smallest && smallest.isDate) tRangeErr("roundingIncrement must be 1 for this rounding")
        if (zoned != null) {
            val id = TDur.toInternal(d)
            val target = TDur.addZoned(zoned.epochNs, zoned.timeZone, zoned.calendar, id, Overflow.CONSTRAIN)
            val r = TDur.differenceZonedWithRounding(zoned.epochNs, target, zoned.timeZone, zoned.calendar, largest, inc, smallest, mode)
            if (largest.isDate) largest = TUnit.HOUR
            return TDur.fromInternal(r, largest)
        }
        if (plain != null) {
            val id = TDur.toInternal24(d)
            val targetTime = TM.addTime(TimeRec.MIDNIGHT, id.time)
            val dd = TDur.adjust(id.date, targetTime.days)
            val targetDate = TCal.dateAdd(plain.calendar, plain.date, dd, Overflow.CONSTRAIN)
            val r = TDur.differencePlainDateTimeWithRounding(
                IsoDateTime(plain.date, TimeRec.MIDNIGHT), IsoDateTime(targetDate, targetTime), plain.calendar, largest, inc, smallest, mode,
            )
            return TDur.fromInternal(r, largest)
        }
        if (existingLargest.isCalendar || largest.isCalendar) tRangeErr("a relativeTo date is required to round durations with calendar units")
        val id = TDur.toInternal24(d)
        val r = if (smallest == TUnit.DAY) {
            val inc2 = TM.BI_NS_PER_DAY.multiply(BigInteger.valueOf(inc))
            val days = TNum.roundToIncrement(id.time, inc2, mode).divide(TM.BI_NS_PER_DAY)
            if (days.abs() > BigInteger.valueOf(Long.MAX_VALUE / 2)) tRangeErr("duration out of range")
            InternalDuration(TDur.dateDuration(0, 0, 0, days.toLong()), BigInteger.ZERO)
        } else {
            InternalDuration(DateDuration.ZERO, TDur.roundTime(id.time, inc, smallest, mode))
        }
        return TDur.fromInternal(r, largest)
    }

    private fun total(d: JSTemporalDuration, totalOfV: Any?): Double {
        val totalOf = optionsFromString(totalOfV, "unit")
        val rel = TConv.relativeTo(totalOf)
        val zoned = rel as? JSTemporalZonedDateTime
        val plain = rel as? JSTemporalPlainDate
        val unitOpt = TOpt.unit(totalOf, "unit", true)
        TOpt.validateUnit(unitOpt, 2)
        val unit = TUnit.ALL[unitOpt]
        if (zoned != null) {
            val id = TDur.toInternal(d)
            val target = TDur.addZoned(zoned.epochNs, zoned.timeZone, zoned.calendar, id, Overflow.CONSTRAIN)
            return TDur.differenceZonedWithTotal(zoned.epochNs, target, zoned.timeZone, zoned.calendar, unit)
        }
        if (plain != null) {
            val id = TDur.toInternal24(d)
            val targetTime = TM.addTime(TimeRec.MIDNIGHT, id.time)
            val dd = TDur.adjust(id.date, targetTime.days)
            val targetDate = TCal.dateAdd(plain.calendar, plain.date, dd, Overflow.CONSTRAIN)
            return TDur.differencePlainDateTimeWithTotal(
                IsoDateTime(plain.date, TimeRec.MIDNIGHT), IsoDateTime(targetDate, targetTime), plain.calendar, unit,
            )
        }
        val largest = TDur.defaultLargestUnit(d)
        if (largest.isCalendar || unit.isCalendar) tRangeErr("a relativeTo date is required for calendar units")
        val id = TDur.toInternal24(d)
        return TDur.totalTime(id.time, unit)
    }

    private fun toStringImpl(d: JSTemporalDuration, options: Any?): String {
        val o = TOpt.optionsObject(options)
        val digits = TOpt.fractionalSecondDigits(o)
        val mode = TOpt.roundingMode(o, RMode.TRUNC)
        val smallest = TOpt.unit(o, "smallestUnit", false)
        TOpt.validateUnit(smallest, 1)
        if (smallest == TUnit.HOUR.ordinal || smallest == TUnit.MINUTE.ordinal) tRangeErr("smallestUnit must be seconds or smaller")
        val p = Precision.of(smallest, digits)
        if (p.unit == TUnit.NANOSECOND && p.increment == 1L) return TFormat.durationToString(d, p.precision)
        val largest = TDur.defaultLargestUnit(d)
        val id = TDur.toInternal(d)
        val t = TDur.roundTime(id.time, p.increment, p.unit, mode)
        val rd = TDur.fromInternal(InternalDuration(id.date, t), TUnit.larger(largest, TUnit.SECOND))
        return TFormat.durationToString(rd, p.precision)
    }
}
