package io.neonjs.builtins.temporal

import io.neonjs.runtime.*

// ====================================================================== calendars

/** Calendar Fields Record; NaN / null mean ~unset~. */
internal class CalFields {
    @JvmField var era: String? = null
    @JvmField var eraYear = Double.NaN
    @JvmField var year = Double.NaN
    @JvmField var month = Double.NaN
    @JvmField var monthCode: String? = null
    @JvmField var day = Double.NaN
    @JvmField var hour = Double.NaN
    @JvmField var minute = Double.NaN
    @JvmField var second = Double.NaN
    @JvmField var millisecond = Double.NaN
    @JvmField var microsecond = Double.NaN
    @JvmField var nanosecond = Double.NaN
    @JvmField var offset: String? = null
    @JvmField var timeZone: TimeZone? = null

    fun presentMask(): Int {
        var m = 0
        if (era != null) m = m or TCal.F_ERA
        if (!eraYear.isNaN()) m = m or TCal.F_ERAYEAR
        if (!year.isNaN()) m = m or TCal.F_YEAR
        if (!month.isNaN()) m = m or TCal.F_MONTH
        if (monthCode != null) m = m or TCal.F_MONTHCODE
        if (!day.isNaN()) m = m or TCal.F_DAY
        if (!hour.isNaN()) m = m or TCal.F_HOUR
        if (!minute.isNaN()) m = m or TCal.F_MINUTE
        if (!second.isNaN()) m = m or TCal.F_SECOND
        if (!millisecond.isNaN()) m = m or TCal.F_MS
        if (!microsecond.isNaN()) m = m or TCal.F_US
        if (!nanosecond.isNaN()) m = m or TCal.F_NS
        if (offset != null) m = m or TCal.F_OFFSET
        if (timeZone != null) m = m or TCal.F_TZ
        return m
    }

    fun setTime(t: TimeRec) {
        hour = t.hour.toDouble()
        minute = t.minute.toDouble()
        second = t.second.toDouble()
        millisecond = t.millisecond.toDouble()
        microsecond = t.microsecond.toDouble()
        nanosecond = t.nanosecond.toDouble()
    }
}

/** Calendar operations, dispatching between the ISO 8601 calendar and [NonIsoCalendar]. */
internal object TCal {
    const val ISO = "iso8601"

    const val F_YEAR = 1
    const val F_MONTH = 2
    const val F_MONTHCODE = 4
    const val F_DAY = 8
    const val F_HOUR = 16
    const val F_MINUTE = 32
    const val F_SECOND = 64
    const val F_MS = 128
    const val F_US = 256
    const val F_NS = 512
    const val F_OFFSET = 1024
    const val F_TZ = 2048
    const val F_ERA = 4096
    const val F_ERAYEAR = 8192
    const val DATE_FIELDS = F_YEAR or F_MONTH or F_MONTHCODE or F_DAY
    const val TIME_FIELDS = F_HOUR or F_MINUTE or F_SECOND or F_MS or F_US or F_NS
    const val PARTIAL = -1

    const val TYPE_DATE = 0
    const val TYPE_YEAR_MONTH = 1
    const val TYPE_MONTH_DAY = 2

    // property names sorted by code unit order, with their field flags
    private val SORTED_NAMES = arrayOf(
        "day", "era", "eraYear", "hour", "microsecond", "millisecond", "minute", "month", "monthCode", "nanosecond",
        "offset", "second", "timeZone", "year",
    )
    private val SORTED_FLAGS = intArrayOf(
        F_DAY, F_ERA, F_ERAYEAR, F_HOUR, F_US, F_MS, F_MINUTE, F_MONTH, F_MONTHCODE, F_NS, F_OFFSET, F_SECOND, F_TZ, F_YEAR,
    )

    /** The non-ISO calendar for a canonical calendar id, or null for iso8601. */
    fun nonIso(cal: String): NonIsoCalendar? {
        if (cal == ISO) return null
        return NonIsoCalendar.of(cal) ?: tRangeErr("unsupported calendar ${cal.take(60)}")
    }

    private fun asciiLower(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c in 'A'..'Z') c + 32 else c)
        return sb.toString()
    }

    /** CanonicalizeCalendar */
    fun canonicalize(id: String): String {
        if (asciiEqualsIgnoreCase(id, ISO)) return ISO
        if (id.length <= 40) {
            val c = when (val lower = asciiLower(id)) {
                "islamicc" -> "islamic-civil"
                "ethiopic-amete-alem" -> "ethioaa"
                else -> lower
            }
            if (c in NonIsoCalendar.supportedIds()) return c
        }
        tRangeErr("unsupported calendar ${id.take(60)}")
    }

    /** ParseTemporalCalendarString */
    fun parseCalendarString(s: String): String {
        val r = TemporalParser.tryParseIsoDateTime(s, TemporalParser.ALL_GOALS)
        if (r != null) return r.calendar ?: ISO
        if (!TemporalParser.isAnnotationValue(s)) tRangeErr("invalid calendar ${s.take(60)}")
        return s
    }

    /** ToTemporalCalendarIdentifier */
    fun toCalendar(v: Any?): String {
        when (v) {
            is JSTemporalPlainDate -> return v.calendar
            is JSTemporalPlainDateTime -> return v.calendar
            is JSTemporalPlainMonthDay -> return v.calendar
            is JSTemporalPlainYearMonth -> return v.calendar
            is JSTemporalZonedDateTime -> return v.calendar
        }
        if (v !is CharSequence) tTypeErr("calendar must be a string")
        return canonicalize(parseCalendarString(v.toString()))
    }

    /** Calendar argument of a constructor: undefined → iso8601, String → canonicalized, else TypeError. */
    fun ctorCalendar(v: Any?): String {
        if (v === Undefined) return ISO
        if (v !is CharSequence) tTypeErr("calendar must be a string")
        return canonicalize(v.toString())
    }

    /** GetTemporalCalendarIdentifierWithISODefault */
    fun calendarWithISODefault(item: JSObject): String {
        when (item) {
            is JSTemporalPlainDate -> return item.calendar
            is JSTemporalPlainDateTime -> return item.calendar
            is JSTemporalPlainMonthDay -> return item.calendar
            is JSTemporalPlainYearMonth -> return item.calendar
            is JSTemporalZonedDateTime -> return item.calendar
        }
        val c = item.get("calendar", item)
        if (c === Undefined) return ISO
        return toCalendar(c)
    }

    /** ParseMonthCode → normalized month code string (CreateMonthCode). */
    fun toMonthCode(v: Any?): String {
        val p = Ops.toPrimitive(v, Ops.HINT_STRING)
        if (p !is CharSequence) tTypeErr("monthCode must be a string")
        val s = p.toString()
        val code = TemporalParser.parseMonthCodeString(s)
        if (code < 0) tRangeErr("invalid monthCode ${s.take(20)}")
        return s
    }

    /** ToOffsetString */
    fun toOffsetString(v: Any?): String {
        val p = Ops.toPrimitive(v, Ops.HINT_STRING)
        if (p !is CharSequence) tTypeErr("offset must be a string")
        val s = p.toString()
        TemporalParser.parseUtcOffset(s) ?: tRangeErr("invalid offset ${s.take(40)}")
        return s
    }

    /** CalendarSupportsEra */
    fun supportsEra(cal: String): Boolean = cal != ISO && nonIso(cal)!!.supportsEra

    /** PrepareCalendarFields; [required] is a field mask or PARTIAL. CalendarExtraFields adds era / eraYear. */
    fun prepareFields(cal: String, obj: JSObject, fieldMask0: Int, required: Int): CalFields {
        var fieldMask = fieldMask0
        if (fieldMask and F_YEAR != 0 && supportsEra(cal)) fieldMask = fieldMask or F_ERA or F_ERAYEAR
        val r = CalFields()
        var any = false
        for (k in SORTED_NAMES.indices) {
            val flag = SORTED_FLAGS[k]
            if (fieldMask and flag == 0) continue
            val v = obj.get(SORTED_NAMES[k], obj)
            if (v !== Undefined) {
                any = true
                when (flag) {
                    F_ERA -> r.era = Ops.toString(v).toString()
                    F_ERAYEAR -> r.eraYear = toIntegerWithTruncation(v)
                    F_YEAR -> r.year = toIntegerWithTruncation(v)
                    F_MONTH -> r.month = toPositiveIntegerWithTruncation(v)
                    F_MONTHCODE -> r.monthCode = toMonthCode(v)
                    F_DAY -> r.day = toPositiveIntegerWithTruncation(v)
                    F_HOUR -> r.hour = toIntegerWithTruncation(v)
                    F_MINUTE -> r.minute = toIntegerWithTruncation(v)
                    F_SECOND -> r.second = toIntegerWithTruncation(v)
                    F_MS -> r.millisecond = toIntegerWithTruncation(v)
                    F_US -> r.microsecond = toIntegerWithTruncation(v)
                    F_NS -> r.nanosecond = toIntegerWithTruncation(v)
                    F_OFFSET -> r.offset = toOffsetString(v)
                    F_TZ -> r.timeZone = TZ.toTimeZone(v)
                }
            } else if (required != PARTIAL) {
                if (required and flag != 0) tTypeErr("${SORTED_NAMES[k]} is required")
                when (flag) {
                    F_HOUR -> r.hour = 0.0
                    F_MINUTE -> r.minute = 0.0
                    F_SECOND -> r.second = 0.0
                    F_MS -> r.millisecond = 0.0
                    F_US -> r.microsecond = 0.0
                    F_NS -> r.nanosecond = 0.0
                }
            }
        }
        if (required == PARTIAL && !any) tTypeErr("at least one recognized property is required")
        return r
    }

    fun monthCodeOf(month: Int): String = if (month < 10) "M0$month" else "M$month"

    /** CalendarISOToDate */
    fun isoToDate(cal: String, d: IsoDate): CalDate {
        val c = nonIso(cal) ?: return CalDate(
            d.year, d.month, MonthCodes.of(d.month, false), d.day, TM.dayOfYear(d), TM.daysInMonth(d.year.toLong(), d.month),
            TM.daysInYear(d.year.toLong()), 12, TM.isLeap(d.year.toLong()), null, 0,
        )
        return c.isoToDate(d)
    }

    /** ISODateToFields */
    fun isoDateToFields(cal: String, d: IsoDate, type: Int): CalFields {
        val f = CalFields()
        val c = nonIso(cal)
        if (c == null) {
            f.monthCode = monthCodeOf(d.month)
            if (type == TYPE_MONTH_DAY || type == TYPE_DATE) f.day = d.day.toDouble()
            if (type == TYPE_YEAR_MONTH || type == TYPE_DATE) f.year = d.year.toDouble()
            return f
        }
        val cd = c.isoToDate(d)
        f.monthCode = MonthCodes.toString(cd.monthCode)
        if (type == TYPE_MONTH_DAY || type == TYPE_DATE) f.day = cd.day.toDouble()
        if (type == TYPE_YEAR_MONTH || type == TYPE_DATE) f.year = cd.year.toDouble()
        return f
    }

    /** CalendarFieldKeysToIgnore on a field mask. */
    private fun fieldKeysToIgnore(cal: String, keys: Int): Int {
        val c = nonIso(cal)
        if (c != null) return c.fieldKeysToIgnore(keys)
        var ignored = keys
        if (keys and F_MONTH != 0) ignored = ignored or F_MONTHCODE
        if (keys and F_MONTHCODE != 0) ignored = ignored or F_MONTH
        return ignored
    }

    /** CalendarMergeFields */
    fun mergeFields(cal: String, fields: CalFields, add: CalFields): CalFields {
        val addKeys = add.presentMask()
        val ignored = fieldKeysToIgnore(cal, addKeys)
        val fk = fields.presentMask()
        val m = CalFields()
        fun take(flag: Int): Int = if (addKeys and flag != 0) 2 else if (fk and flag != 0 && ignored and flag == 0) 1 else 0
        when (take(F_ERA)) { 2 -> m.era = add.era; 1 -> m.era = fields.era }
        when (take(F_ERAYEAR)) { 2 -> m.eraYear = add.eraYear; 1 -> m.eraYear = fields.eraYear }
        when (take(F_YEAR)) { 2 -> m.year = add.year; 1 -> m.year = fields.year }
        when (take(F_MONTH)) { 2 -> m.month = add.month; 1 -> m.month = fields.month }
        when (take(F_MONTHCODE)) { 2 -> m.monthCode = add.monthCode; 1 -> m.monthCode = fields.monthCode }
        when (take(F_DAY)) { 2 -> m.day = add.day; 1 -> m.day = fields.day }
        when (take(F_HOUR)) { 2 -> m.hour = add.hour; 1 -> m.hour = fields.hour }
        when (take(F_MINUTE)) { 2 -> m.minute = add.minute; 1 -> m.minute = fields.minute }
        when (take(F_SECOND)) { 2 -> m.second = add.second; 1 -> m.second = fields.second }
        when (take(F_MS)) { 2 -> m.millisecond = add.millisecond; 1 -> m.millisecond = fields.millisecond }
        when (take(F_US)) { 2 -> m.microsecond = add.microsecond; 1 -> m.microsecond = fields.microsecond }
        when (take(F_NS)) { 2 -> m.nanosecond = add.nanosecond; 1 -> m.nanosecond = fields.nanosecond }
        when (take(F_OFFSET)) { 2 -> m.offset = add.offset; 1 -> m.offset = fields.offset }
        when (take(F_TZ)) { 2 -> m.timeZone = add.timeZone; 1 -> m.timeZone = fields.timeZone }
        return m
    }

    /** CalendarResolveFields */
    fun resolveFields(cal: String, f: CalFields, type: Int) {
        val c = nonIso(cal)
        if (c != null) {
            c.resolveFields(f, type)
            return
        }
        val needsYear = type == TYPE_DATE || type == TYPE_YEAR_MONTH
        val needsDay = type == TYPE_DATE || type == TYPE_MONTH_DAY
        if (needsYear && f.year.isNaN()) tTypeErr("year is required")
        if (needsDay && f.day.isNaN()) tTypeErr("day is required")
        val mc = f.monthCode
        if (f.month.isNaN() && mc == null) tTypeErr("month or monthCode is required")
        if (mc != null) {
            val code = TemporalParser.parseMonthCodeString(mc)
            if (code and 1 != 0) tRangeErr("leap months are not supported in the ISO 8601 calendar")
            val month = code shr 1
            if (month > 12) tRangeErr("invalid monthCode $mc")
            if (!f.month.isNaN() && f.month != month.toDouble()) tRangeErr("month and monthCode do not agree")
            f.month = month.toDouble()
        }
    }

    /** CalendarDateToISO (fields already resolved). */
    private fun dateToIso(cal: String, f: CalFields, overflow: Overflow): IsoDate {
        val c = nonIso(cal) ?: return TM.regulateIsoDate(f.year, f.month, f.day, overflow)
        return c.dateToIso(f, overflow)
    }

    /** CalendarDateFromFields */
    fun dateFromFields(cal: String, f: CalFields, overflow: Overflow): IsoDate {
        resolveFields(cal, f, TYPE_DATE)
        val r = dateToIso(cal, f, overflow)
        if (!TM.isoDateWithinLimits(r)) tRangeErr("date outside of supported range")
        return r
    }

    /** CalendarYearMonthFromFields */
    fun yearMonthFromFields(cal: String, f: CalFields, overflow: Overflow): IsoDate {
        f.day = 1.0
        resolveFields(cal, f, TYPE_YEAR_MONTH)
        val r = dateToIso(cal, f, overflow)
        if (!TM.isoYearMonthWithinLimits(r.year, r.month)) tRangeErr("year-month outside of supported range")
        return r
    }

    /** CalendarMonthDayFromFields */
    fun monthDayFromFields(cal: String, f: CalFields, overflow: Overflow): IsoDate {
        resolveFields(cal, f, TYPE_MONTH_DAY)
        val c = nonIso(cal)
        if (c != null) {
            val r = c.monthDayToIso(f, overflow)
            if (!TM.isoDateWithinLimits(r)) tRangeErr("month-day outside of supported range")
            return r
        }
        val y = if (f.year.isNaN()) 1972.0 else f.year
        val m = f.month
        val d = f.day
        if (overflow == Overflow.CONSTRAIN) {
            val mm = m.coerceIn(1.0, 12.0).toInt()
            val dd = d.coerceIn(1.0, TM.daysInMonthD(y, mm).toDouble()).toInt()
            return IsoDate(1972, mm, dd)
        }
        if (!TM.isValidIsoDate(y, m, d)) tRangeErr("month-day is out of range")
        return IsoDate(1972, m.toInt(), d.toInt())
    }

    /** CalendarDateAdd */
    fun dateAdd(cal: String, d: IsoDate, dur: DateDuration, overflow: Overflow): IsoDate {
        val c = nonIso(cal)
        if (c != null) {
            val r = c.dateAdd(d, dur, overflow)
            if (!TM.isoDateWithinLimits(r)) tRangeErr("date outside of supported range")
            return r
        }
        val y = d.year.toLong() + dur.years
        val m = d.month.toLong() + dur.months
        val by = TM.balanceYear(y, m)
        val bm = TM.balanceMonth(m)
        val intermediate = TM.regulateIsoDate(by.toDouble(), bm.toDouble(), d.day.toDouble(), overflow)
        val days = dur.days + 7 * dur.weeks
        val result = TM.addDays(intermediate, days)
        if (!TM.isoDateWithinLimits(result)) tRangeErr("date outside of supported range")
        return result
    }

    private fun compareSurpasses(sign: Int, year: Long, month: Int, day: Int, target: IsoDate): Boolean {
        if (year != target.year.toLong()) return sign * (year - target.year) > 0
        if (month != target.month) return sign * (month - target.month) > 0
        if (day != target.day) return sign * (day - target.day) > 0
        return false
    }

    private fun isoDateSurpasses(sign: Int, base: IsoDate, two: IsoDate, years: Long, months: Long): Boolean {
        val y0 = base.year + years
        if (compareSurpasses(sign, y0, base.month, base.day, two)) return true
        if (months == 0L) return false
        val m0 = base.month + months
        return compareSurpasses(sign, TM.balanceYear(y0, m0), TM.balanceMonth(m0), base.day, two)
    }

    /** CalendarDateUntil, in closed form (no loops proportional to the size of the difference). */
    fun dateUntil(cal: String, one: IsoDate, two: IsoDate, largest: TUnit): DateDuration {
        val c = nonIso(cal)
        if (c != null) return c.dateUntil(one, two, largest)
        val sign = -TM.compareDate(one, two)
        if (sign == 0) return DateDuration.ZERO
        var years = 0L
        var months = 0L
        if (largest == TUnit.YEAR || largest == TUnit.MONTH) {
            var candidateYears = (two.year - one.year).toLong()
            if (candidateYears != 0L) candidateYears -= sign
            while (!isoDateSurpasses(sign, one, two, candidateYears, 0)) {
                years = candidateYears
                candidateYears += sign
            }
            var candidateMonths = sign.toLong()
            while (!isoDateSurpasses(sign, one, two, years, candidateMonths)) {
                months = candidateMonths
                candidateMonths += sign
            }
            if (largest == TUnit.MONTH) {
                months += years * 12
                years = 0
            }
        }
        val iy = TM.balanceYear(one.year + years, one.month + months)
        val im = TM.balanceMonth(one.month + months)
        val iday = Math.min(one.day, TM.daysInMonth(iy, im))
        var days = TM.epochDays(two) - TM.epochDays(iy, im, iday)
        var weeks = 0L
        if (largest == TUnit.WEEK) {
            weeks = days / 7
            days %= 7
        }
        return DateDuration(years, months, weeks, days)
    }
}
