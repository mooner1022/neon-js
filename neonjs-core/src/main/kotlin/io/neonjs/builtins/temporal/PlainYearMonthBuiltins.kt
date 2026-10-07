package io.neonjs.builtins.temporal

import io.neonjs.builtins.makeCtor
import io.neonjs.builtins.method
import io.neonjs.runtime.*
import java.math.BigInteger

internal object PlainYearMonthBuiltins {
    private fun thisYM(t: Any?, name: String): JSTemporalPlainYearMonth =
        t as? JSTemporalPlainYearMonth ?: tTypeErr("Temporal.PlainYearMonth.prototype.$name called on incompatible receiver")

    fun install(realm: Realm, temporal: JSObject) {
        val proto = JSObject(realm.objectPrototype)
        realm.intrinsics[TCreate.P_YEARMONTH] = proto
        val ctor = makeCtor(realm, "PlainYearMonth", 2, proto) { _, _, args, nt ->
            if (nt == null) tTypeErr("Constructor Temporal.PlainYearMonth requires 'new'")
            val refV = args.arg(3)
            val y = toIntegerWithTruncation(args.arg(0))
            val m = toIntegerWithTruncation(args.arg(1))
            val cal = TCal.ctorCalendar(args.arg(2))
            val ref = if (refV === Undefined) 1.0 else toIntegerWithTruncation(refV)
            if (!TM.isValidIsoDate(y, m, ref)) tRangeErr("invalid year-month")
            TCreate.plainYearMonth(TM.makeDate(y, m.toInt(), ref.toInt()), cal, nt)
        }
        temporal.defineOwn("PlainYearMonth", ctor, Attr.WC)
        ctor.method(realm, "from", 1) { _, _, args, _ -> TConv.toYearMonth(args.arg(0), args.arg(1)) }
        ctor.method(realm, "compare", 2) { _, _, args, _ ->
            val a = TConv.toYearMonth(args.arg(0))
            val b = TConv.toYearMonth(args.arg(1))
            TM.compareDate(a.date, b.date).toDouble()
        }
        proto.defineOwn(JSSymbol.toStringTag, "Temporal.PlainYearMonth", Attr.CONFIGURABLE)
        CalendarGetters.install(realm, proto, CalendarGetters.KIND_YEAR_MONTH, { t, n -> thisYM(t, n).calendar }, { t, n -> thisYM(t, n).date })
        installMethods(realm, proto)
    }

    private fun installMethods(realm: Realm, proto: JSObject) {
        proto.method(realm, "with", 1) { _, t, args, _ ->
            val ym = thisYM(t, "with")
            val like = args.arg(0)
            if (!TConv.isPartialTemporalObject(like)) tTypeErr("argument must be a partial year-month object")
            val cal = ym.calendar
            var fields = TCal.isoDateToFields(cal, ym.date, TCal.TYPE_YEAR_MONTH)
            val partial = TCal.prepareFields(cal, like as JSObject, TCal.F_YEAR or TCal.F_MONTH or TCal.F_MONTHCODE, TCal.PARTIAL)
            fields = TCal.mergeFields(cal, fields, partial)
            val overflow = TOpt.overflow(TOpt.optionsObject(args.arg(1)))
            TCreate.plainYearMonth(TCal.yearMonthFromFields(cal, fields, overflow), cal)
        }
        proto.method(realm, "add", 1) { _, t, args, _ -> addDuration(false, thisYM(t, "add"), args.arg(0), args.arg(1)) }
        proto.method(realm, "subtract", 1) { _, t, args, _ -> addDuration(true, thisYM(t, "subtract"), args.arg(0), args.arg(1)) }
        proto.method(realm, "until", 1) { _, t, args, _ -> difference(false, thisYM(t, "until"), args.arg(0), args.arg(1)) }
        proto.method(realm, "since", 1) { _, t, args, _ -> difference(true, thisYM(t, "since"), args.arg(0), args.arg(1)) }
        proto.method(realm, "equals", 1) { _, t, args, _ ->
            val ym = thisYM(t, "equals")
            val other = TConv.toYearMonth(args.arg(0))
            TM.compareDate(ym.date, other.date) == 0 && ym.calendar == other.calendar
        }
        proto.method(realm, "toString", 0) { _, t, args, _ ->
            val ym = thisYM(t, "toString")
            val show = TOpt.showCalendar(TOpt.optionsObject(args.arg(0)))
            TFormat.yearMonthToString(ym.date, ym.calendar, show)
        }
        proto.method(realm, "toLocaleString", 0) { _, t, _, _ ->
            val ym = thisYM(t, "toLocaleString")
            TFormat.yearMonthToString(ym.date, ym.calendar, ShowCalendar.AUTO)
        }
        proto.method(realm, "toJSON", 0) { _, t, _, _ ->
            val ym = thisYM(t, "toJSON")
            TFormat.yearMonthToString(ym.date, ym.calendar, ShowCalendar.AUTO)
        }
        proto.method(realm, "valueOf", 0) { _, _, _, _ -> tTypeErr("use Temporal.PlainYearMonth.compare() or equals() instead of valueOf()") }
        proto.method(realm, "toPlainDate", 1) { _, t, args, _ ->
            val ym = thisYM(t, "toPlainDate")
            val item = args.arg(0)
            if (item !is JSObject) tTypeErr("argument must be an object")
            val cal = ym.calendar
            val fields = TCal.isoDateToFields(cal, ym.date, TCal.TYPE_YEAR_MONTH)
            val input = TCal.prepareFields(cal, item, TCal.F_DAY, 0)
            val merged = TCal.mergeFields(cal, fields, input)
            TCreate.plainDate(TCal.dateFromFields(cal, merged, Overflow.CONSTRAIN), cal)
        }
    }

    private fun addDuration(subtract: Boolean, ym: JSTemporalPlainYearMonth, like: Any?, options: Any?): JSTemporalPlainYearMonth {
        var d = TDur.toDuration(like)
        if (subtract) d = TDur.negated(d)
        val id = TDur.toInternal(d)
        val overflow = TOpt.overflow(TOpt.optionsObject(options))
        val toAdd = id.date
        if (toAdd.weeks != 0L || toAdd.days != 0L || id.time.signum() != 0) tRangeErr("only years and months can be added to a PlainYearMonth")
        val cal = ym.calendar
        val fields = TCal.isoDateToFields(cal, ym.date, TCal.TYPE_YEAR_MONTH)
        fields.day = 1.0
        val date = TCal.dateFromFields(cal, fields, Overflow.CONSTRAIN)
        val added = TCal.dateAdd(cal, date, toAdd, overflow)
        val addedFields = TCal.isoDateToFields(cal, added, TCal.TYPE_YEAR_MONTH)
        return TCreate.plainYearMonth(TCal.yearMonthFromFields(cal, addedFields, overflow), cal)
    }

    private fun difference(since: Boolean, ym: JSTemporalPlainYearMonth, otherV: Any?, options: Any?): JSTemporalDuration {
        val other = TConv.toYearMonth(otherV)
        if (ym.calendar != other.calendar) tRangeErr("cannot compute the difference between year-months of different calendars")
        val o = TOpt.optionsObject(options)
        val s = TDur.differenceSettings(since, o, 0, arrayOf(TUnit.WEEK, TUnit.DAY), TUnit.MONTH, TUnit.YEAR)
        if (TM.compareDate(ym.date, other.date) == 0) return TCreate.duration(DoubleArray(10))
        val cal = ym.calendar
        val thisFields = TCal.isoDateToFields(cal, ym.date, TCal.TYPE_YEAR_MONTH)
        thisFields.day = 1.0
        val thisDate = TCal.dateFromFields(cal, thisFields, Overflow.CONSTRAIN)
        val otherFields = TCal.isoDateToFields(cal, other.date, TCal.TYPE_YEAR_MONTH)
        otherFields.day = 1.0
        val otherDate = TCal.dateFromFields(cal, otherFields, Overflow.CONSTRAIN)
        val dateDiff = TCal.dateUntil(cal, thisDate, otherDate, s.largest)
        var dur = InternalDuration(TDur.adjust(dateDiff, 0, 0), BigInteger.ZERO)
        if (s.smallest != TUnit.MONTH || s.increment != 1L) {
            val dt = IsoDateTime(thisDate, TimeRec.MIDNIGHT)
            val origin = TM.utcEpochNs(dt)
            val dest = TM.utcEpochNs(otherDate, TimeRec.MIDNIGHT)
            dur = TDur.roundRelative(dur, origin, dest, dt, null, ym.calendar, s.largest, s.increment, s.smallest, s.mode)
        }
        val r = TDur.fromInternal(dur, TUnit.DAY)
        return if (since) TDur.negated(r) else r
    }
}
