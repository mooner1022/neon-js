package io.neonjs.builtins.temporal

import io.neonjs.builtins.makeCtor
import io.neonjs.builtins.method
import io.neonjs.runtime.*
import java.math.BigInteger

internal object PlainDateBuiltins {
    private fun thisDate(t: Any?, name: String): JSTemporalPlainDate =
        t as? JSTemporalPlainDate ?: tTypeErr("Temporal.PlainDate.prototype.$name called on incompatible receiver")

    fun install(realm: Realm, temporal: JSObject) {
        val proto = JSObject(realm.objectPrototype)
        realm.intrinsics[TCreate.P_DATE] = proto
        val ctor = makeCtor(realm, "PlainDate", 3, proto) { _, _, args, nt ->
            if (nt == null) tTypeErr("Constructor Temporal.PlainDate requires 'new'")
            val y = toIntegerWithTruncation(args.arg(0))
            val m = toIntegerWithTruncation(args.arg(1))
            val d = toIntegerWithTruncation(args.arg(2))
            val cal = TCal.ctorCalendar(args.arg(3))
            if (!TM.isValidIsoDate(y, m, d)) tRangeErr("invalid date")
            TCreate.plainDate(TM.makeDate(y, m.toInt(), d.toInt()), cal, nt)
        }
        temporal.defineOwn("PlainDate", ctor, Attr.WC)
        ctor.method(realm, "from", 1) { _, _, args, _ -> TConv.toDate(args.arg(0), args.arg(1)) }
        ctor.method(realm, "compare", 2) { _, _, args, _ ->
            val a = TConv.toDate(args.arg(0))
            val b = TConv.toDate(args.arg(1))
            TM.compareDate(a.date, b.date).toDouble()
        }
        proto.defineOwn(JSSymbol.toStringTag, "Temporal.PlainDate", Attr.CONFIGURABLE)
        CalendarGetters.install(realm, proto, CalendarGetters.KIND_DATE, { t, n -> thisDate(t, n).calendar }, { t, n -> thisDate(t, n).date })
        installMethods(realm, proto)
        installConversions(realm, proto)
    }

    private fun installMethods(realm: Realm, proto: JSObject) {
        proto.method(realm, "add", 1) { _, t, args, _ -> addDuration(false, thisDate(t, "add"), args.arg(0), args.arg(1)) }
        proto.method(realm, "subtract", 1) { _, t, args, _ -> addDuration(true, thisDate(t, "subtract"), args.arg(0), args.arg(1)) }
        proto.method(realm, "with", 1) { _, t, args, _ ->
            val pd = thisDate(t, "with")
            val like = args.arg(0)
            if (!TConv.isPartialTemporalObject(like)) tTypeErr("argument must be a partial date object")
            val cal = pd.calendar
            var fields = TCal.isoDateToFields(cal, pd.date, TCal.TYPE_DATE)
            val partial = TCal.prepareFields(cal, like as JSObject, TCal.DATE_FIELDS, TCal.PARTIAL)
            fields = TCal.mergeFields(cal, fields, partial)
            val overflow = TOpt.overflow(TOpt.optionsObject(args.arg(1)))
            TCreate.plainDate(TCal.dateFromFields(cal, fields, overflow), cal)
        }
        proto.method(realm, "withCalendar", 1) { _, t, args, _ ->
            val pd = thisDate(t, "withCalendar")
            TCreate.plainDate(pd.date, TCal.toCalendar(args.arg(0)))
        }
        proto.method(realm, "until", 1) { _, t, args, _ -> difference(false, thisDate(t, "until"), args.arg(0), args.arg(1)) }
        proto.method(realm, "since", 1) { _, t, args, _ -> difference(true, thisDate(t, "since"), args.arg(0), args.arg(1)) }
        proto.method(realm, "equals", 1) { _, t, args, _ ->
            val pd = thisDate(t, "equals")
            val other = TConv.toDate(args.arg(0))
            TM.compareDate(pd.date, other.date) == 0 && pd.calendar == other.calendar
        }
        proto.method(realm, "toString", 0) { _, t, args, _ ->
            val pd = thisDate(t, "toString")
            val show = TOpt.showCalendar(TOpt.optionsObject(args.arg(0)))
            TFormat.dateToString(pd.date, pd.calendar, show)
        }
        proto.method(realm, "toLocaleString", 0) { _, t, _, _ ->
            val pd = thisDate(t, "toLocaleString")
            TFormat.dateToString(pd.date, pd.calendar, ShowCalendar.AUTO)
        }
        proto.method(realm, "toJSON", 0) { _, t, _, _ ->
            val pd = thisDate(t, "toJSON")
            TFormat.dateToString(pd.date, pd.calendar, ShowCalendar.AUTO)
        }
        proto.method(realm, "valueOf", 0) { _, _, _, _ -> tTypeErr("use Temporal.PlainDate.compare() or equals() instead of valueOf()") }
    }

    private fun installConversions(realm: Realm, proto: JSObject) {
        proto.method(realm, "toPlainYearMonth", 0) { _, t, _, _ ->
            val pd = thisDate(t, "toPlainYearMonth")
            val f = TCal.isoDateToFields(pd.calendar, pd.date, TCal.TYPE_DATE)
            TCreate.plainYearMonth(TCal.yearMonthFromFields(pd.calendar, f, Overflow.CONSTRAIN), pd.calendar)
        }
        proto.method(realm, "toPlainMonthDay", 0) { _, t, _, _ ->
            val pd = thisDate(t, "toPlainMonthDay")
            val f = TCal.isoDateToFields(pd.calendar, pd.date, TCal.TYPE_DATE)
            TCreate.plainMonthDay(TCal.monthDayFromFields(pd.calendar, f, Overflow.CONSTRAIN), pd.calendar)
        }
        proto.method(realm, "toPlainDateTime", 0) { _, t, args, _ ->
            val pd = thisDate(t, "toPlainDateTime")
            val time = TConv.toTimeOrMidnight(args.arg(0))
            TCreate.plainDateTime(IsoDateTime(pd.date, time), pd.calendar)
        }
        proto.method(realm, "toZonedDateTime", 1) { _, t, args, _ -> toZoned(thisDate(t, "toZonedDateTime"), args.arg(0)) }
    }

    private fun toZoned(pd: JSTemporalPlainDate, item: Any?): JSTemporalZonedDateTime {
        val tz: TimeZone
        var temporalTime: Any? = Undefined
        if (item is JSObject) {
            val tzLike = item.get("timeZone", item)
            if (tzLike === Undefined) {
                tz = TZ.toTimeZone(item)
            } else {
                tz = TZ.toTimeZone(tzLike)
                temporalTime = item.get("plainTime", item)
            }
        } else {
            tz = TZ.toTimeZone(item)
        }
        val ns: BigInteger
        if (temporalTime === Undefined) {
            ns = TZ.startOfDay(tz, pd.date)
        } else {
            val time = TConv.toTime(temporalTime)
            val dt = IsoDateTime(pd.date, time)
            if (!TM.isoDateTimeWithinLimits(dt)) tRangeErr("date-time outside of supported range")
            ns = TZ.epochNsFor(tz, dt, Disambiguation.COMPATIBLE)
        }
        return TCreate.zoned(ns, tz, pd.calendar)
    }

    private fun addDuration(subtract: Boolean, pd: JSTemporalPlainDate, like: Any?, options: Any?): JSTemporalPlainDate {
        var d = TDur.toDuration(like)
        if (subtract) d = TDur.negated(d)
        val dd = TDur.toDateDurationWithoutTime(d)
        val overflow = TOpt.overflow(TOpt.optionsObject(options))
        return TCreate.plainDate(TCal.dateAdd(pd.calendar, pd.date, dd, overflow), pd.calendar)
    }

    private fun difference(since: Boolean, pd: JSTemporalPlainDate, otherV: Any?, options: Any?): JSTemporalDuration {
        val other = TConv.toDate(otherV)
        if (pd.calendar != other.calendar) tRangeErr("cannot compute the difference between dates of different calendars")
        val o = TOpt.optionsObject(options)
        val s = TDur.differenceSettings(since, o, 0, emptyArray(), TUnit.DAY, TUnit.DAY)
        if (TM.compareDate(pd.date, other.date) == 0) return TCreate.duration(DoubleArray(10))
        val dateDiff = TCal.dateUntil(pd.calendar, pd.date, other.date, s.largest)
        var dur = InternalDuration(dateDiff, BigInteger.ZERO)
        if (s.smallest != TUnit.DAY || s.increment != 1L) {
            val dt = IsoDateTime(pd.date, TimeRec.MIDNIGHT)
            val origin = TM.utcEpochNs(dt)
            val dest = TM.utcEpochNs(other.date, TimeRec.MIDNIGHT)
            dur = TDur.roundRelative(dur, origin, dest, dt, null, pd.calendar, s.largest, s.increment, s.smallest, s.mode)
        }
        val r = TDur.fromInternal(dur, TUnit.DAY)
        return if (since) TDur.negated(r) else r
    }
}
