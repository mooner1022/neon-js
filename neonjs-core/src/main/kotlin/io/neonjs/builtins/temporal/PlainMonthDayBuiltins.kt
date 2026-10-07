package io.neonjs.builtins.temporal

import io.neonjs.builtins.makeCtor
import io.neonjs.builtins.method
import io.neonjs.runtime.*

internal object PlainMonthDayBuiltins {
    private fun thisMD(t: Any?, name: String): JSTemporalPlainMonthDay =
        t as? JSTemporalPlainMonthDay ?: tTypeErr("Temporal.PlainMonthDay.prototype.$name called on incompatible receiver")

    fun install(realm: Realm, temporal: JSObject) {
        val proto = JSObject(realm.objectPrototype)
        realm.intrinsics[TCreate.P_MONTHDAY] = proto
        val ctor = makeCtor(realm, "PlainMonthDay", 2, proto) { _, _, args, nt ->
            if (nt == null) tTypeErr("Constructor Temporal.PlainMonthDay requires 'new'")
            val refV = args.arg(3)
            val m = toIntegerWithTruncation(args.arg(0))
            val d = toIntegerWithTruncation(args.arg(1))
            val cal = TCal.ctorCalendar(args.arg(2))
            val y = if (refV === Undefined) 1972.0 else toIntegerWithTruncation(refV)
            if (!TM.isValidIsoDate(y, m, d)) tRangeErr("invalid month-day")
            TCreate.plainMonthDay(TM.makeDate(y, m.toInt(), d.toInt()), cal, nt)
        }
        temporal.defineOwn("PlainMonthDay", ctor, Attr.WC)
        ctor.method(realm, "from", 1) { _, _, args, _ -> TConv.toMonthDay(args.arg(0), args.arg(1)) }
        proto.defineOwn(JSSymbol.toStringTag, "Temporal.PlainMonthDay", Attr.CONFIGURABLE)
        CalendarGetters.install(realm, proto, CalendarGetters.KIND_MONTH_DAY, { t, n -> thisMD(t, n).calendar }, { t, n -> thisMD(t, n).date })
        installMethods(realm, proto)
    }

    private fun installMethods(realm: Realm, proto: JSObject) {
        proto.method(realm, "with", 1) { _, t, args, _ ->
            val md = thisMD(t, "with")
            val like = args.arg(0)
            if (!TConv.isPartialTemporalObject(like)) tTypeErr("argument must be a partial month-day object")
            val cal = md.calendar
            var fields = TCal.isoDateToFields(cal, md.date, TCal.TYPE_MONTH_DAY)
            val partial = TCal.prepareFields(cal, like as JSObject, TCal.DATE_FIELDS, TCal.PARTIAL)
            fields = TCal.mergeFields(cal, fields, partial)
            val overflow = TOpt.overflow(TOpt.optionsObject(args.arg(1)))
            TCreate.plainMonthDay(TCal.monthDayFromFields(cal, fields, overflow), cal)
        }
        proto.method(realm, "equals", 1) { _, t, args, _ ->
            val md = thisMD(t, "equals")
            val other = TConv.toMonthDay(args.arg(0))
            TM.compareDate(md.date, other.date) == 0 && md.calendar == other.calendar
        }
        proto.method(realm, "toString", 0) { _, t, args, _ ->
            val md = thisMD(t, "toString")
            val show = TOpt.showCalendar(TOpt.optionsObject(args.arg(0)))
            TFormat.monthDayToString(md.date, md.calendar, show)
        }
        proto.method(realm, "toLocaleString", 0) { _, t, _, _ ->
            val md = thisMD(t, "toLocaleString")
            TFormat.monthDayToString(md.date, md.calendar, ShowCalendar.AUTO)
        }
        proto.method(realm, "toJSON", 0) { _, t, _, _ ->
            val md = thisMD(t, "toJSON")
            TFormat.monthDayToString(md.date, md.calendar, ShowCalendar.AUTO)
        }
        proto.method(realm, "valueOf", 0) { _, _, _, _ -> tTypeErr("use Temporal.PlainMonthDay.prototype.equals() instead of valueOf()") }
        proto.method(realm, "toPlainDate", 1) { _, t, args, _ ->
            val md = thisMD(t, "toPlainDate")
            val item = args.arg(0)
            if (item !is JSObject) tTypeErr("argument must be an object")
            val cal = md.calendar
            val fields = TCal.isoDateToFields(cal, md.date, TCal.TYPE_MONTH_DAY)
            val input = TCal.prepareFields(cal, item, TCal.F_YEAR, 0)
            val merged = TCal.mergeFields(cal, fields, input)
            TCreate.plainDate(TCal.dateFromFields(cal, merged, Overflow.CONSTRAIN), cal)
        }
    }
}
