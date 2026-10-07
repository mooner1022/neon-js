package io.neonjs.builtins.temporal

import io.neonjs.builtins.getter
import io.neonjs.builtins.makeCtor
import io.neonjs.builtins.method
import io.neonjs.runtime.*

internal object PlainDateTimeBuiltins {
    private fun thisDT(t: Any?, name: String): JSTemporalPlainDateTime =
        t as? JSTemporalPlainDateTime ?: tTypeErr("Temporal.PlainDateTime.prototype.$name called on incompatible receiver")

    fun install(realm: Realm, temporal: JSObject) {
        val proto = JSObject(realm.objectPrototype)
        realm.intrinsics[TCreate.P_DATETIME] = proto
        val ctor = makeCtor(realm, "PlainDateTime", 3, proto) { _, _, args, nt ->
            if (nt == null) tTypeErr("Constructor Temporal.PlainDateTime requires 'new'")
            val y = toIntegerWithTruncation(args.arg(0))
            val m = toIntegerWithTruncation(args.arg(1))
            val d = toIntegerWithTruncation(args.arg(2))
            val f = DoubleArray(6)
            for (i in 0 until 6) {
                val v = args.arg(3 + i)
                f[i] = if (v === Undefined) 0.0 else toIntegerWithTruncation(v)
            }
            val cal = TCal.ctorCalendar(args.arg(9))
            if (!TM.isValidIsoDate(y, m, d)) tRangeErr("invalid date")
            val date = TM.makeDate(y, m.toInt(), d.toInt())
            if (!TConv.isValidTime(f[0], f[1], f[2], f[3], f[4], f[5])) tRangeErr("invalid time")
            val time = TimeRec(f[0].toInt(), f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5].toInt())
            TCreate.plainDateTime(IsoDateTime(date, time), cal, nt)
        }
        temporal.defineOwn("PlainDateTime", ctor, Attr.WC)
        ctor.method(realm, "from", 1) { _, _, args, _ -> TConv.toDateTime(args.arg(0), args.arg(1)) }
        ctor.method(realm, "compare", 2) { _, _, args, _ ->
            val a = TConv.toDateTime(args.arg(0))
            val b = TConv.toDateTime(args.arg(1))
            TM.compareDateTime(a.dt, b.dt).toDouble()
        }
        proto.defineOwn(JSSymbol.toStringTag, "Temporal.PlainDateTime", Attr.CONFIGURABLE)
        CalendarGetters.install(realm, proto, CalendarGetters.KIND_DATE, { t, n -> thisDT(t, n).calendar }, { t, n -> thisDT(t, n).dt.date })
        proto.getter(realm, "hour") { _, t, _, _ -> thisDT(t, "hour").dt.time.hour.toDouble() }
        proto.getter(realm, "minute") { _, t, _, _ -> thisDT(t, "minute").dt.time.minute.toDouble() }
        proto.getter(realm, "second") { _, t, _, _ -> thisDT(t, "second").dt.time.second.toDouble() }
        proto.getter(realm, "millisecond") { _, t, _, _ -> thisDT(t, "millisecond").dt.time.millisecond.toDouble() }
        proto.getter(realm, "microsecond") { _, t, _, _ -> thisDT(t, "microsecond").dt.time.microsecond.toDouble() }
        proto.getter(realm, "nanosecond") { _, t, _, _ -> thisDT(t, "nanosecond").dt.time.nanosecond.toDouble() }
        installMethods(realm, proto)
        installConversions(realm, proto)
    }

    private fun installMethods(realm: Realm, proto: JSObject) {
        proto.method(realm, "with", 1) { _, t, args, _ ->
            val p = thisDT(t, "with")
            val like = args.arg(0)
            if (!TConv.isPartialTemporalObject(like)) tTypeErr("argument must be a partial date-time object")
            val cal = p.calendar
            var fields = TCal.isoDateToFields(cal, p.dt.date, TCal.TYPE_DATE)
            fields.setTime(p.dt.time)
            val partial = TCal.prepareFields(cal, like as JSObject, TCal.DATE_FIELDS or TCal.TIME_FIELDS, TCal.PARTIAL)
            fields = TCal.mergeFields(cal, fields, partial)
            val overflow = TOpt.overflow(TOpt.optionsObject(args.arg(1)))
            TCreate.plainDateTime(TConv.interpretDateTimeFields(cal, fields, overflow), cal)
        }
        proto.method(realm, "withPlainTime", 0) { _, t, args, _ ->
            val p = thisDT(t, "withPlainTime")
            val time = TConv.toTimeOrMidnight(args.arg(0))
            TCreate.plainDateTime(IsoDateTime(p.dt.date, time), p.calendar)
        }
        proto.method(realm, "withCalendar", 1) { _, t, args, _ ->
            val p = thisDT(t, "withCalendar")
            TCreate.plainDateTime(p.dt, TCal.toCalendar(args.arg(0)))
        }
        proto.method(realm, "add", 1) { _, t, args, _ -> addDuration(false, thisDT(t, "add"), args.arg(0), args.arg(1)) }
        proto.method(realm, "subtract", 1) { _, t, args, _ -> addDuration(true, thisDT(t, "subtract"), args.arg(0), args.arg(1)) }
        proto.method(realm, "until", 1) { _, t, args, _ -> difference(false, thisDT(t, "until"), args.arg(0), args.arg(1)) }
        proto.method(realm, "since", 1) { _, t, args, _ -> difference(true, thisDT(t, "since"), args.arg(0), args.arg(1)) }
        proto.method(realm, "round", 1) { _, t, args, _ -> round(thisDT(t, "round"), args.arg(0)) }
        proto.method(realm, "equals", 1) { _, t, args, _ ->
            val p = thisDT(t, "equals")
            val other = TConv.toDateTime(args.arg(0))
            TM.compareDateTime(p.dt, other.dt) == 0 && p.calendar == other.calendar
        }
        proto.method(realm, "toString", 0) { _, t, args, _ -> toStringImpl(thisDT(t, "toString"), args.arg(0)) }
        proto.method(realm, "toLocaleString", 0) { _, t, _, _ ->
            val p = thisDT(t, "toLocaleString")
            TFormat.dateTimeToString(p.dt, p.calendar, Precision.AUTO, ShowCalendar.AUTO)
        }
        proto.method(realm, "toJSON", 0) { _, t, _, _ ->
            val p = thisDT(t, "toJSON")
            TFormat.dateTimeToString(p.dt, p.calendar, Precision.AUTO, ShowCalendar.AUTO)
        }
        proto.method(realm, "valueOf", 0) { _, _, _, _ -> tTypeErr("use Temporal.PlainDateTime.compare() or equals() instead of valueOf()") }
    }

    private fun installConversions(realm: Realm, proto: JSObject) {
        proto.method(realm, "toZonedDateTime", 1) { _, t, args, _ ->
            val p = thisDT(t, "toZonedDateTime")
            val tz = TZ.toTimeZone(args.arg(0))
            val disambiguation = TOpt.disambiguation(TOpt.optionsObject(args.arg(1)))
            val ns = TZ.epochNsFor(tz, p.dt, disambiguation)
            TCreate.zoned(ns, tz, p.calendar)
        }
        proto.method(realm, "toPlainDate", 0) { _, t, _, _ ->
            val p = thisDT(t, "toPlainDate")
            TCreate.plainDate(p.dt.date, p.calendar)
        }
        proto.method(realm, "toPlainTime", 0) { _, t, _, _ -> TCreate.plainTime(thisDT(t, "toPlainTime").dt.time) }
    }

    private fun addDuration(subtract: Boolean, p: JSTemporalPlainDateTime, like: Any?, options: Any?): JSTemporalPlainDateTime {
        var d = TDur.toDuration(like)
        if (subtract) d = TDur.negated(d)
        val overflow = TOpt.overflow(TOpt.optionsObject(options))
        val id = TDur.toInternal24(d)
        val timeResult = TM.addTime(p.dt.time, id.time)
        val dd = TDur.adjust(id.date, timeResult.days)
        val added = TCal.dateAdd(p.calendar, p.dt.date, dd, overflow)
        return TCreate.plainDateTime(IsoDateTime(added, timeResult), p.calendar)
    }

    private fun difference(since: Boolean, p: JSTemporalPlainDateTime, otherV: Any?, options: Any?): JSTemporalDuration {
        val other = TConv.toDateTime(otherV)
        if (p.calendar != other.calendar) tRangeErr("cannot compute the difference between date-times of different calendars")
        val o = TOpt.optionsObject(options)
        val s = TDur.differenceSettings(since, o, 2, emptyArray(), TUnit.NANOSECOND, TUnit.DAY)
        if (TM.compareDateTime(p.dt, other.dt) == 0) return TCreate.duration(DoubleArray(10))
        val id = TDur.differencePlainDateTimeWithRounding(p.dt, other.dt, p.calendar, s.largest, s.increment, s.smallest, s.mode)
        val r = TDur.fromInternal(id, s.largest)
        return if (since) TDur.negated(r) else r
    }

    private fun round(p: JSTemporalPlainDateTime, roundToV: Any?): JSTemporalPlainDateTime {
        if (roundToV === Undefined) tTypeErr("options argument is required")
        val roundTo = if (roundToV is CharSequence) {
            JSObject(null).also { it.createDataPropertyOrThrow("smallestUnit", roundToV.toString()) }
        } else TOpt.optionsObject(roundToV)
        val inc = TOpt.roundingIncrement(roundTo)
        val mode = TOpt.roundingMode(roundTo, RMode.HALF_EXPAND)
        val smallestOpt = TOpt.unit(roundTo, "smallestUnit", true)
        TOpt.validateUnit(smallestOpt, 1, intArrayOf(TUnit.DAY.ordinal))
        val smallest = TUnit.ALL[smallestOpt]
        if (smallest == TUnit.DAY) TOpt.validateIncrement(inc, 1, true) else TOpt.validateIncrement(inc, smallest.maxIncrement.toLong(), false)
        if (smallest == TUnit.NANOSECOND && inc == 1L) return TCreate.plainDateTime(p.dt, p.calendar)
        return TCreate.plainDateTime(TM.roundDateTime(p.dt, inc, smallest, mode), p.calendar)
    }

    private fun toStringImpl(p: JSTemporalPlainDateTime, options: Any?): String {
        val o = TOpt.optionsObject(options)
        val show = TOpt.showCalendar(o)
        val digits = TOpt.fractionalSecondDigits(o)
        val mode = TOpt.roundingMode(o, RMode.TRUNC)
        val smallest = TOpt.unit(o, "smallestUnit", false)
        TOpt.validateUnit(smallest, 1)
        if (smallest == TUnit.HOUR.ordinal) tRangeErr("smallestUnit must not be hour")
        val pr = Precision.of(smallest, digits)
        val r = TM.roundDateTime(p.dt, pr.increment, pr.unit, mode)
        if (!TM.isoDateTimeWithinLimits(r)) tRangeErr("date-time outside of supported range")
        return TFormat.dateTimeToString(r, p.calendar, pr.precision, show)
    }
}
