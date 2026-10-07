package dev.mooner.neonjs.builtins.temporal

import dev.mooner.neonjs.builtins.getter
import dev.mooner.neonjs.builtins.makeCtor
import dev.mooner.neonjs.builtins.method
import dev.mooner.neonjs.runtime.*
import java.math.BigInteger

internal object ZonedDateTimeBuiltins {
    private fun thisZ(t: Any?, name: String): JSTemporalZonedDateTime =
        t as? JSTemporalZonedDateTime ?: tTypeErr("Temporal.ZonedDateTime.prototype.$name called on incompatible receiver")

    private fun dtOf(z: JSTemporalZonedDateTime): IsoDateTime = TZ.isoDateTimeFor(z.timeZone, z.epochNs)

    fun install(realm: Realm, temporal: JSObject) {
        val proto = JSObject(realm.objectPrototype)
        realm.intrinsics[TCreate.P_ZONED] = proto
        val ctor = makeCtor(realm, "ZonedDateTime", 2, proto) { _, _, args, nt ->
            if (nt == null) tTypeErr("Constructor Temporal.ZonedDateTime requires 'new'")
            val ns = Ops.toBigInt(args.arg(0))
            if (!TM.isValidEpochNs(ns)) tRangeErr("epoch nanoseconds outside of supported range")
            val tzV = args.arg(1)
            if (tzV !is CharSequence) tTypeErr("time zone must be a string")
            val tz = TZ.fromIdentifier(tzV.toString())
            val cal = TCal.ctorCalendar(args.arg(2))
            TCreate.zoned(ns, tz, cal, nt)
        }
        temporal.defineOwn("ZonedDateTime", ctor, Attr.WC)
        ctor.method(realm, "from", 1) { _, _, args, _ -> TConv.toZoned(args.arg(0), args.arg(1)) }
        ctor.method(realm, "compare", 2) { _, _, args, _ ->
            val a = TConv.toZoned(args.arg(0))
            val b = TConv.toZoned(args.arg(1))
            a.epochNs.compareTo(b.epochNs).coerceIn(-1, 1).toDouble()
        }
        proto.defineOwn(JSSymbol.toStringTag, "Temporal.ZonedDateTime", Attr.CONFIGURABLE)
        CalendarGetters.install(realm, proto, CalendarGetters.KIND_DATE, { t, n -> thisZ(t, n).calendar }, { t, n -> dtOf(thisZ(t, n)).date })
        installGetters(realm, proto)
        installMethods(realm, proto)
        installConversions(realm, proto)
    }

    private fun installGetters(realm: Realm, proto: JSObject) {
        proto.getter(realm, "timeZoneId") { _, t, _, _ -> thisZ(t, "timeZoneId").timeZone.id }
        proto.getter(realm, "hour") { _, t, _, _ -> dtOf(thisZ(t, "hour")).time.hour.toDouble() }
        proto.getter(realm, "minute") { _, t, _, _ -> dtOf(thisZ(t, "minute")).time.minute.toDouble() }
        proto.getter(realm, "second") { _, t, _, _ -> dtOf(thisZ(t, "second")).time.second.toDouble() }
        proto.getter(realm, "millisecond") { _, t, _, _ -> dtOf(thisZ(t, "millisecond")).time.millisecond.toDouble() }
        proto.getter(realm, "microsecond") { _, t, _, _ -> dtOf(thisZ(t, "microsecond")).time.microsecond.toDouble() }
        proto.getter(realm, "nanosecond") { _, t, _, _ -> dtOf(thisZ(t, "nanosecond")).time.nanosecond.toDouble() }
        proto.getter(realm, "epochMilliseconds") { _, t, _, _ ->
            InstantBuiltins.floorDiv(thisZ(t, "epochMilliseconds").epochNs, TNum.BI_1E6).toDouble()
        }
        proto.getter(realm, "epochNanoseconds") { _, t, _, _ -> thisZ(t, "epochNanoseconds").epochNs }
        proto.getter(realm, "hoursInDay") { _, t, _, _ ->
            val z = thisZ(t, "hoursInDay")
            val today = dtOf(z).date
            val tomorrow = TM.addDays(today, 1)
            val todayNs = TZ.startOfDay(z.timeZone, today)
            val tomorrowNs = TZ.startOfDay(z.timeZone, tomorrow)
            TDur.totalTime(tomorrowNs.subtract(todayNs), TUnit.HOUR)
        }
        proto.getter(realm, "offsetNanoseconds") { _, t, _, _ ->
            val z = thisZ(t, "offsetNanoseconds")
            TZ.offsetNs(z.timeZone, z.epochNs).toDouble()
        }
        proto.getter(realm, "offset") { _, t, _, _ ->
            val z = thisZ(t, "offset")
            TFormat.formatUtcOffsetNs(TZ.offsetNs(z.timeZone, z.epochNs))
        }
    }

    private fun installMethods(realm: Realm, proto: JSObject) {
        proto.method(realm, "with", 1) { _, t, args, _ -> with(thisZ(t, "with"), args.arg(0), args.arg(1)) }
        proto.method(realm, "withPlainTime", 0) { _, t, args, _ ->
            val z = thisZ(t, "withPlainTime")
            val dt = dtOf(z)
            val v = args.arg(0)
            val ns = if (v === Undefined) {
                TZ.startOfDay(z.timeZone, dt.date)
            } else {
                val time = TConv.toTime(v)
                TZ.epochNsFor(z.timeZone, IsoDateTime(dt.date, time), Disambiguation.COMPATIBLE)
            }
            TCreate.zoned(ns, z.timeZone, z.calendar)
        }
        proto.method(realm, "withTimeZone", 1) { _, t, args, _ ->
            val z = thisZ(t, "withTimeZone")
            TCreate.zoned(z.epochNs, TZ.toTimeZone(args.arg(0)), z.calendar)
        }
        proto.method(realm, "withCalendar", 1) { _, t, args, _ ->
            val z = thisZ(t, "withCalendar")
            TCreate.zoned(z.epochNs, z.timeZone, TCal.toCalendar(args.arg(0)))
        }
        proto.method(realm, "add", 1) { _, t, args, _ -> addDuration(false, thisZ(t, "add"), args.arg(0), args.arg(1)) }
        proto.method(realm, "subtract", 1) { _, t, args, _ -> addDuration(true, thisZ(t, "subtract"), args.arg(0), args.arg(1)) }
        proto.method(realm, "until", 1) { _, t, args, _ -> difference(false, thisZ(t, "until"), args.arg(0), args.arg(1)) }
        proto.method(realm, "since", 1) { _, t, args, _ -> difference(true, thisZ(t, "since"), args.arg(0), args.arg(1)) }
        proto.method(realm, "round", 1) { _, t, args, _ -> round(thisZ(t, "round"), args.arg(0)) }
        proto.method(realm, "equals", 1) { _, t, args, _ ->
            val z = thisZ(t, "equals")
            val other = TConv.toZoned(args.arg(0))
            z.epochNs == other.epochNs && TZ.equals(z.timeZone, other.timeZone) && z.calendar == other.calendar
        }
        proto.method(realm, "toString", 0) { _, t, args, _ -> toStringImpl(thisZ(t, "toString"), args.arg(0)) }
        proto.method(realm, "toLocaleString", 0) { _, t, _, _ ->
            TFormat.zonedToString(thisZ(t, "toLocaleString"), Precision.AUTO, ShowCalendar.AUTO, 0, true)
        }
        proto.method(realm, "toJSON", 0) { _, t, _, _ ->
            TFormat.zonedToString(thisZ(t, "toJSON"), Precision.AUTO, ShowCalendar.AUTO, 0, true)
        }
        proto.method(realm, "valueOf", 0) { _, _, _, _ -> tTypeErr("use Temporal.ZonedDateTime.compare() or equals() instead of valueOf()") }
        proto.method(realm, "startOfDay", 0) { _, t, _, _ ->
            val z = thisZ(t, "startOfDay")
            TCreate.zoned(TZ.startOfDay(z.timeZone, dtOf(z).date), z.timeZone, z.calendar)
        }
        proto.method(realm, "getTimeZoneTransition", 1) { _, t, args, _ ->
            val z = thisZ(t, "getTimeZoneTransition")
            val v = args.arg(0)
            if (v === Undefined) tTypeErr("direction argument is required")
            val o = if (v is CharSequence) {
                JSObject(null).also { it.createDataPropertyOrThrow("direction", v.toString()) }
            } else TOpt.optionsObject(v)
            val next = TOpt.directionNext(o)
            val ns = TZ.transition(z.timeZone, z.epochNs, next)
            if (ns == null) Null else TCreate.zoned(ns, z.timeZone, z.calendar)
        }
    }

    private fun installConversions(realm: Realm, proto: JSObject) {
        proto.method(realm, "toInstant", 0) { _, t, _, _ -> TCreate.instant(thisZ(t, "toInstant").epochNs) }
        proto.method(realm, "toPlainDate", 0) { _, t, _, _ ->
            val z = thisZ(t, "toPlainDate")
            TCreate.plainDate(dtOf(z).date, z.calendar)
        }
        proto.method(realm, "toPlainTime", 0) { _, t, _, _ -> TCreate.plainTime(dtOf(thisZ(t, "toPlainTime")).time) }
        proto.method(realm, "toPlainDateTime", 0) { _, t, _, _ ->
            val z = thisZ(t, "toPlainDateTime")
            TCreate.plainDateTime(dtOf(z), z.calendar)
        }
    }

    private fun with(z: JSTemporalZonedDateTime, like: Any?, options: Any?): JSTemporalZonedDateTime {
        if (!TConv.isPartialTemporalObject(like)) tTypeErr("argument must be a partial date-time object")
        val tz = z.timeZone
        val offsetNs = TZ.offsetNs(tz, z.epochNs)
        val dt = dtOf(z)
        val cal = z.calendar
        var fields = TCal.isoDateToFields(cal, dt.date, TCal.TYPE_DATE)
        fields.setTime(dt.time)
        fields.offset = TFormat.formatUtcOffsetNs(offsetNs)
        val partial = TCal.prepareFields(cal, like as JSObject, TCal.DATE_FIELDS or TCal.TIME_FIELDS or TCal.F_OFFSET, TCal.PARTIAL)
        fields = TCal.mergeFields(cal, fields, partial)
        val o = TOpt.optionsObject(options)
        val disambiguation = TOpt.disambiguation(o)
        val offsetOption = TOpt.offset(o, OffsetOption.PREFER)
        val overflow = TOpt.overflow(o)
        val r = TConv.interpretDateTimeFields(cal, fields, overflow)
        val newOffsetNs = TemporalParser.parseUtcOffset(fields.offset!!)!!
        val ns = TConv.interpretOffset(r.date, r.time, TConv.OFFSET_OPTION, newOffsetNs, tz, disambiguation, offsetOption, false)
        return TCreate.zoned(ns, tz, z.calendar)
    }

    private fun addDuration(subtract: Boolean, z: JSTemporalZonedDateTime, like: Any?, options: Any?): JSTemporalZonedDateTime {
        var d = TDur.toDuration(like)
        if (subtract) d = TDur.negated(d)
        val overflow = TOpt.overflow(TOpt.optionsObject(options))
        val id = TDur.toInternal(d)
        val ns = TDur.addZoned(z.epochNs, z.timeZone, z.calendar, id, overflow)
        return TCreate.zoned(ns, z.timeZone, z.calendar)
    }

    private fun difference(since: Boolean, z: JSTemporalZonedDateTime, otherV: Any?, options: Any?): JSTemporalDuration {
        val other = TConv.toZoned(otherV)
        if (z.calendar != other.calendar) tRangeErr("cannot compute the difference between date-times of different calendars")
        val o = TOpt.optionsObject(options)
        val s = TDur.differenceSettings(since, o, 2, emptyArray(), TUnit.NANOSECOND, TUnit.HOUR)
        if (!s.largest.isDate) {
            val id = TDur.differenceInstant(z.epochNs, other.epochNs, s.increment, s.smallest, s.mode)
            val r = TDur.fromInternal(id, s.largest)
            return if (since) TDur.negated(r) else r
        }
        if (!TZ.equals(z.timeZone, other.timeZone)) tRangeErr("cannot compute day-based differences between different time zones")
        if (z.epochNs == other.epochNs) return TCreate.duration(DoubleArray(10))
        val id = TDur.differenceZonedWithRounding(z.epochNs, other.epochNs, z.timeZone, z.calendar, s.largest, s.increment, s.smallest, s.mode)
        val r = TDur.fromInternal(id, TUnit.HOUR)
        return if (since) TDur.negated(r) else r
    }

    private fun round(z: JSTemporalZonedDateTime, roundToV: Any?): JSTemporalZonedDateTime {
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
        if (smallest == TUnit.NANOSECOND && inc == 1L) return TCreate.zoned(z.epochNs, z.timeZone, z.calendar)
        val tz = z.timeZone
        val thisNs = z.epochNs
        val dt = dtOf(z)
        val ns: BigInteger
        if (smallest == TUnit.DAY) {
            val dateStart = dt.date
            val dateEnd = TM.addDays(dateStart, 1)
            val startNs = TZ.startOfDay(tz, dateStart)
            val endNs = TZ.startOfDay(tz, dateEnd)
            val dayLength = endNs.subtract(startNs)
            var progress = thisNs.subtract(startNs)
            // a backward transition across midnight can put a wall-clock time of this date after the start of the next
            // one (proposal-temporal#3312): such times round down to this date and up/nearest to the next
            if (progress >= dayLength) progress = dayLength.subtract(BigInteger.ONE)
            val rounded = TNum.roundToIncrement(progress, dayLength, mode)
            ns = startNs.add(rounded)
        } else {
            val r = TM.roundDateTime(dt, inc, smallest, mode)
            val offsetNs = TZ.offsetNs(tz, thisNs)
            ns = TConv.interpretOffset(r.date, r.time, TConv.OFFSET_OPTION, offsetNs, tz, Disambiguation.COMPATIBLE, OffsetOption.PREFER, false)
        }
        if (!TM.isValidEpochNs(ns)) tRangeErr("result outside of supported range")
        return TCreate.zoned(ns, tz, z.calendar)
    }

    private fun toStringImpl(z: JSTemporalZonedDateTime, options: Any?): String {
        val o = TOpt.optionsObject(options)
        val showCalendar = TOpt.showCalendar(o)
        val digits = TOpt.fractionalSecondDigits(o)
        val showOffset = TOpt.showOffset(o)
        val mode = TOpt.roundingMode(o, RMode.TRUNC)
        val smallest = TOpt.unit(o, "smallestUnit", false)
        val showTimeZone = TOpt.showTimeZone(o)
        TOpt.validateUnit(smallest, 1)
        if (smallest == TUnit.HOUR.ordinal) tRangeErr("smallestUnit must not be hour")
        val p = Precision.of(smallest, digits)
        return TFormat.zonedToString(z, p.precision, showCalendar, showTimeZone, showOffset, p.increment, p.unit, mode)
    }
}
