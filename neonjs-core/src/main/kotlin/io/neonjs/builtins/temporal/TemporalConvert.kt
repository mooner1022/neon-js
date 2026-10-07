package io.neonjs.builtins.temporal

import io.neonjs.runtime.*
import java.math.BigInteger

/** ToTemporalX conversions and related abstract operations. */
internal object TConv {
    const val OFFSET_OPTION = 0
    const val OFFSET_EXACT = 1
    const val OFFSET_WALL = 2

    private val TIME_KEYS = arrayOf("hour", "microsecond", "millisecond", "minute", "nanosecond", "second")
    private val TIME_SLOTS = intArrayOf(0, 4, 3, 1, 5, 2)

    /** ToTemporalTimeRecord: [h, mi, s, ms, us, ns]; NaN marks unset fields when [partial]. */
    fun toTimeRecordFields(obj: JSObject, partial: Boolean): DoubleArray {
        val r = DoubleArray(6) { if (partial) Double.NaN else 0.0 }
        var any = false
        for (k in TIME_KEYS.indices) {
            val v = obj.get(TIME_KEYS[k], obj)
            if (v !== Undefined) {
                r[TIME_SLOTS[k]] = toIntegerWithTruncation(v)
                any = true
            }
        }
        if (!any) tTypeErr("time-like object must have at least one time property")
        return r
    }

    fun isValidTime(h: Double, mi: Double, s: Double, ms: Double, us: Double, ns: Double): Boolean =
        h in 0.0..23.0 && mi in 0.0..59.0 && s in 0.0..59.0 && ms in 0.0..999.0 && us in 0.0..999.0 && ns in 0.0..999.0

    /** RegulateTime */
    fun regulateTime(h: Double, mi: Double, s: Double, ms: Double, us: Double, ns: Double, overflow: Overflow): TimeRec {
        if (overflow == Overflow.CONSTRAIN) {
            return TimeRec(
                h.coerceIn(0.0, 23.0).toInt(), mi.coerceIn(0.0, 59.0).toInt(), s.coerceIn(0.0, 59.0).toInt(),
                ms.coerceIn(0.0, 999.0).toInt(), us.coerceIn(0.0, 999.0).toInt(), ns.coerceIn(0.0, 999.0).toInt(),
            )
        }
        if (!isValidTime(h, mi, s, ms, us, ns)) tRangeErr("time is out of range")
        return TimeRec(h.toInt(), mi.toInt(), s.toInt(), ms.toInt(), us.toInt(), ns.toInt())
    }

    /** ToTemporalTime, returning the Time Record. */
    fun toTime(item: Any?, options: Any? = Undefined): TimeRec {
        if (item is JSObject) {
            when (item) {
                is JSTemporalPlainTime -> {
                    TOpt.overflow(TOpt.optionsObject(options))
                    return item.time
                }
                is JSTemporalPlainDateTime -> {
                    TOpt.overflow(TOpt.optionsObject(options))
                    return item.dt.time
                }
                is JSTemporalZonedDateTime -> {
                    val dt = TZ.isoDateTimeFor(item.timeZone, item.epochNs)
                    TOpt.overflow(TOpt.optionsObject(options))
                    return dt.time
                }
            }
            val f = toTimeRecordFields(item, false)
            val overflow = TOpt.overflow(TOpt.optionsObject(options))
            return regulateTime(f[0], f[1], f[2], f[3], f[4], f[5], overflow)
        }
        if (item !is CharSequence) tTypeErr("cannot convert ${Ops.typeOf(item)} to a PlainTime")
        val r = TemporalParser.parseIsoDateTime(item.toString(), TemporalParser.G_TIME)
        TOpt.overflow(TOpt.optionsObject(options))
        return r.time()
    }

    /** ToTimeRecordOrMidnight */
    fun toTimeOrMidnight(item: Any?): TimeRec = if (item === Undefined) TimeRec.MIDNIGHT else toTime(item)

    /** InterpretTemporalDateTimeFields */
    fun interpretDateTimeFields(cal: String, f: CalFields, overflow: Overflow): IsoDateTime {
        val d = TCal.dateFromFields(cal, f, overflow)
        val t = regulateTime(f.hour, f.minute, f.second, f.millisecond, f.microsecond, f.nanosecond, overflow)
        return IsoDateTime(d, t)
    }

    /** ToTemporalDate */
    fun toDate(item: Any?, options: Any? = Undefined): JSTemporalPlainDate {
        if (item is JSObject) {
            when (item) {
                is JSTemporalPlainDate -> {
                    TOpt.overflow(TOpt.optionsObject(options))
                    return TCreate.plainDate(item.date, item.calendar)
                }
                is JSTemporalZonedDateTime -> {
                    val dt = TZ.isoDateTimeFor(item.timeZone, item.epochNs)
                    TOpt.overflow(TOpt.optionsObject(options))
                    return TCreate.plainDate(dt.date, item.calendar)
                }
                is JSTemporalPlainDateTime -> {
                    TOpt.overflow(TOpt.optionsObject(options))
                    return TCreate.plainDate(item.dt.date, item.calendar)
                }
            }
            val cal = TCal.calendarWithISODefault(item)
            val f = TCal.prepareFields(cal, item, TCal.DATE_FIELDS, 0)
            val overflow = TOpt.overflow(TOpt.optionsObject(options))
            val d = TCal.dateFromFields(cal, f, overflow)
            return TCreate.plainDate(d, cal)
        }
        if (item !is CharSequence) tTypeErr("cannot convert ${Ops.typeOf(item)} to a PlainDate")
        val r = TemporalParser.parseIsoDateTime(item.toString(), TemporalParser.G_DATETIME)
        val cal = TCal.canonicalize(r.calendar ?: TCal.ISO)
        TOpt.overflow(TOpt.optionsObject(options))
        return TCreate.plainDate(r.date(), cal)
    }

    /** ToTemporalDateTime */
    fun toDateTime(item: Any?, options: Any? = Undefined): JSTemporalPlainDateTime {
        if (item is JSObject) {
            when (item) {
                is JSTemporalPlainDateTime -> {
                    TOpt.overflow(TOpt.optionsObject(options))
                    return TCreate.plainDateTime(item.dt, item.calendar)
                }
                is JSTemporalZonedDateTime -> {
                    val dt = TZ.isoDateTimeFor(item.timeZone, item.epochNs)
                    TOpt.overflow(TOpt.optionsObject(options))
                    return TCreate.plainDateTime(dt, item.calendar)
                }
                is JSTemporalPlainDate -> {
                    TOpt.overflow(TOpt.optionsObject(options))
                    return TCreate.plainDateTime(IsoDateTime(item.date, TimeRec.MIDNIGHT), item.calendar)
                }
            }
            val cal = TCal.calendarWithISODefault(item)
            val f = TCal.prepareFields(cal, item, TCal.DATE_FIELDS or TCal.TIME_FIELDS, 0)
            val overflow = TOpt.overflow(TOpt.optionsObject(options))
            val dt = interpretDateTimeFields(cal, f, overflow)
            return TCreate.plainDateTime(dt, cal)
        }
        if (item !is CharSequence) tTypeErr("cannot convert ${Ops.typeOf(item)} to a PlainDateTime")
        val r = TemporalParser.parseIsoDateTime(item.toString(), TemporalParser.G_DATETIME)
        val time = if (r.hasTime) r.time() else TimeRec.MIDNIGHT
        val cal = TCal.canonicalize(r.calendar ?: TCal.ISO)
        TOpt.overflow(TOpt.optionsObject(options))
        return TCreate.plainDateTime(IsoDateTime(r.date(), time), cal)
    }

    /** ToTemporalYearMonth */
    fun toYearMonth(item: Any?, options: Any? = Undefined): JSTemporalPlainYearMonth {
        if (item is JSObject) {
            if (item is JSTemporalPlainYearMonth) {
                TOpt.overflow(TOpt.optionsObject(options))
                return TCreate.plainYearMonth(item.date, item.calendar)
            }
            val cal = TCal.calendarWithISODefault(item)
            val f = TCal.prepareFields(cal, item, TCal.F_YEAR or TCal.F_MONTH or TCal.F_MONTHCODE, 0)
            val overflow = TOpt.overflow(TOpt.optionsObject(options))
            val d = TCal.yearMonthFromFields(cal, f, overflow)
            return TCreate.plainYearMonth(d, cal)
        }
        if (item !is CharSequence) tTypeErr("cannot convert ${Ops.typeOf(item)} to a PlainYearMonth")
        val r = TemporalParser.parseIsoDateTime(item.toString(), TemporalParser.G_YEARMONTH)
        val cal = TCal.canonicalize(r.calendar ?: TCal.ISO)
        TOpt.overflow(TOpt.optionsObject(options))
        val d = r.date()
        if (!TM.isoYearMonthWithinLimits(d.year, d.month)) tRangeErr("year-month outside of supported range")
        val f = TCal.isoDateToFields(cal, d, TCal.TYPE_YEAR_MONTH)
        return TCreate.plainYearMonth(TCal.yearMonthFromFields(cal, f, Overflow.CONSTRAIN), cal)
    }

    /** ToTemporalMonthDay */
    fun toMonthDay(item: Any?, options: Any? = Undefined): JSTemporalPlainMonthDay {
        if (item is JSObject) {
            if (item is JSTemporalPlainMonthDay) {
                TOpt.overflow(TOpt.optionsObject(options))
                return TCreate.plainMonthDay(item.date, item.calendar)
            }
            val cal = TCal.calendarWithISODefault(item)
            val f = TCal.prepareFields(cal, item, TCal.DATE_FIELDS, 0)
            val overflow = TOpt.overflow(TOpt.optionsObject(options))
            val d = TCal.monthDayFromFields(cal, f, overflow)
            return TCreate.plainMonthDay(d, cal)
        }
        if (item !is CharSequence) tTypeErr("cannot convert ${Ops.typeOf(item)} to a PlainMonthDay")
        val r = TemporalParser.parseIsoDateTime(item.toString(), TemporalParser.G_MONTHDAY)
        val cal = TCal.canonicalize(r.calendar ?: TCal.ISO)
        TOpt.overflow(TOpt.optionsObject(options))
        if (cal == TCal.ISO) return TCreate.plainMonthDay(IsoDate(1972, r.month, r.day), cal)
        val d = r.date()
        if (!TM.isoDateWithinLimits(d)) tRangeErr("date outside of supported range")
        val f = TCal.isoDateToFields(cal, d, TCal.TYPE_MONTH_DAY)
        return TCreate.plainMonthDay(TCal.monthDayFromFields(cal, f, Overflow.CONSTRAIN), cal)
    }

    /** ToTemporalInstant, returning epoch nanoseconds. */
    fun toInstantNs(item0: Any?): BigInteger {
        var item = item0
        if (item is JSObject) {
            if (item is JSTemporalInstant) return item.epochNs
            if (item is JSTemporalZonedDateTime) return item.epochNs
            item = Ops.toPrimitive(item, Ops.HINT_STRING)
        }
        if (item !is CharSequence) tTypeErr("cannot convert ${Ops.typeOf(item)} to an Instant")
        val r = TemporalParser.parseIsoDateTime(item.toString(), TemporalParser.G_INSTANT)
        val offsetNs = if (r.z) 0L else TemporalParser.parseUtcOffset(r.offset!!)!!
        val balanced = TM.balanceDateTime(r.date(), r.time(), -offsetNs)
        TM.checkIsoDaysRange(balanced.date)
        val ns = TM.utcEpochNs(balanced)
        if (!TM.isValidEpochNs(ns)) tRangeErr("instant outside of supported range")
        return ns
    }

    /** InterpretISODateTimeOffset; [time] null means ~start-of-day~. */
    fun interpretOffset(
        date: IsoDate, time: TimeRec?, behaviour: Int, offsetNs: Long, tz: TimeZone, disambiguation: Disambiguation,
        offsetOption: OffsetOption, matchMinutes: Boolean,
    ): BigInteger {
        if (time == null) return TZ.startOfDay(tz, date)
        val dt = IsoDateTime(date, time)
        if (behaviour == OFFSET_WALL || (behaviour == OFFSET_OPTION && offsetOption == OffsetOption.IGNORE)) {
            return TZ.epochNsFor(tz, dt, disambiguation)
        }
        if (behaviour == OFFSET_EXACT || (behaviour == OFFSET_OPTION && offsetOption == OffsetOption.USE)) {
            val balanced = TM.balanceDateTime(date, time, -offsetNs)
            TM.checkIsoDaysRange(balanced.date)
            val ns = TM.utcEpochNs(balanced)
            if (!TM.isValidEpochNs(ns)) tRangeErr("instant outside of supported range")
            return ns
        }
        TM.checkIsoDaysRange(date)
        val utc = TM.utcEpochNs(dt)
        val possible = TZ.possibleEpochNs(tz, dt)
        for (candidate in possible) {
            val candidateOffset = utc.subtract(candidate).toLong()
            if (candidateOffset == offsetNs) return candidate
            if (matchMinutes) {
                val rounded = TNum.roundToIncrement(candidateOffset, 60_000_000_000L, RMode.HALF_EXPAND)
                if (rounded == offsetNs) return candidate
            }
        }
        if (offsetOption == OffsetOption.REJECT) tRangeErr("offset does not match the time zone")
        return TZ.disambiguate(possible, tz, dt, disambiguation)
    }

    /** ToTemporalZonedDateTime */
    fun toZoned(item: Any?, options: Any? = Undefined): JSTemporalZonedDateTime {
        var hasUTCDesignator = false
        var matchMinutes = false
        val tz: TimeZone
        val offsetString: String?
        val cal: String
        val date: IsoDate
        val time: TimeRec?
        val disambiguation: Disambiguation
        val offsetOption: OffsetOption
        if (item is JSObject) {
            if (item is JSTemporalZonedDateTime) {
                val o = TOpt.optionsObject(options)
                TOpt.disambiguation(o)
                TOpt.offset(o, OffsetOption.REJECT)
                TOpt.overflow(o)
                return TCreate.zoned(item.epochNs, item.timeZone, item.calendar)
            }
            cal = TCal.calendarWithISODefault(item)
            val f = TCal.prepareFields(cal, item, TCal.DATE_FIELDS or TCal.TIME_FIELDS or TCal.F_OFFSET or TCal.F_TZ, TCal.F_TZ)
            tz = f.timeZone!!
            offsetString = f.offset
            val o = TOpt.optionsObject(options)
            disambiguation = TOpt.disambiguation(o)
            offsetOption = TOpt.offset(o, OffsetOption.REJECT)
            val overflow = TOpt.overflow(o)
            val r = interpretDateTimeFields(cal, f, overflow)
            date = r.date
            time = r.time
        } else {
            if (item !is CharSequence) tTypeErr("cannot convert ${Ops.typeOf(item)} to a ZonedDateTime")
            val r = TemporalParser.parseIsoDateTime(item.toString(), TemporalParser.G_ZONED)
            tz = TZ.toTimeZone(r.tzAnnotation!!)
            offsetString = r.offset
            if (r.z) hasUTCDesignator = true
            cal = TCal.canonicalize(r.calendar ?: TCal.ISO)
            matchMinutes = true
            if (offsetString != null && r.offsetHasSeconds) matchMinutes = false
            val o = TOpt.optionsObject(options)
            disambiguation = TOpt.disambiguation(o)
            offsetOption = TOpt.offset(o, OffsetOption.REJECT)
            TOpt.overflow(o)
            date = r.date()
            time = if (r.hasTime) r.time() else null
        }
        val behaviour = if (hasUTCDesignator) OFFSET_EXACT else if (offsetString == null) OFFSET_WALL else OFFSET_OPTION
        val offsetNs = if (behaviour == OFFSET_OPTION) TemporalParser.parseUtcOffset(offsetString!!)!! else 0L
        val ns = interpretOffset(date, time, behaviour, offsetNs, tz, disambiguation, offsetOption, matchMinutes)
        return TCreate.zoned(ns, tz, cal)
    }

    /** IsPartialTemporalObject */
    fun isPartialTemporalObject(v: Any?): Boolean {
        if (v !is JSObject) return false
        if (v is JSTemporalPlainDate || v is JSTemporalPlainDateTime || v is JSTemporalPlainMonthDay ||
            v is JSTemporalPlainTime || v is JSTemporalPlainYearMonth || v is JSTemporalZonedDateTime
        ) return false
        if (v.get("calendar", v) !== Undefined) return false
        if (v.get("timeZone", v) !== Undefined) return false
        return true
    }

    /** GetTemporalRelativeToOption: returns a PlainDate, a ZonedDateTime, or null. */
    fun relativeTo(options: JSObject): JSObject? {
        val value = options.get("relativeTo", options)
        if (value === Undefined) return null
        var behaviour = OFFSET_OPTION
        var matchMinutes = false
        val cal: String
        val tz: TimeZone?
        val offsetString: String?
        val date: IsoDate
        val time: TimeRec?
        if (value is JSObject) {
            if (value is JSTemporalZonedDateTime) return value
            if (value is JSTemporalPlainDate) return value
            if (value is JSTemporalPlainDateTime) return TCreate.plainDate(value.dt.date, value.calendar)
            cal = TCal.calendarWithISODefault(value)
            val f = TCal.prepareFields(cal, value, TCal.DATE_FIELDS or TCal.TIME_FIELDS or TCal.F_OFFSET or TCal.F_TZ, 0)
            val r = interpretDateTimeFields(cal, f, Overflow.CONSTRAIN)
            tz = f.timeZone
            offsetString = f.offset
            if (offsetString == null) behaviour = OFFSET_WALL
            date = r.date
            time = r.time
        } else {
            if (value !is CharSequence) tTypeErr("relativeTo must be a string or object")
            val r = TemporalParser.parseIsoDateTime(value.toString(), intArrayOf(TemporalParser.G_ZONED, TemporalParser.G_DATETIME))
            offsetString = r.offset
            val ann = r.tzAnnotation
            if (ann == null) {
                tz = null
            } else {
                tz = TZ.toTimeZone(ann)
                if (r.z) behaviour = OFFSET_EXACT else if (offsetString == null) behaviour = OFFSET_WALL
                matchMinutes = true
                if (offsetString != null && r.offsetHasSeconds) matchMinutes = false
            }
            cal = TCal.canonicalize(r.calendar ?: TCal.ISO)
            date = r.date()
            time = if (r.hasTime) r.time() else null
        }
        if (tz == null) return TCreate.plainDate(date, cal)
        val offsetNs = if (behaviour == OFFSET_OPTION) TemporalParser.parseUtcOffset(offsetString!!)!! else 0L
        val ns = interpretOffset(date, time, behaviour, offsetNs, tz, Disambiguation.COMPATIBLE, OffsetOption.REJECT, matchMinutes)
        return TCreate.zoned(ns, tz, cal)
    }
}
