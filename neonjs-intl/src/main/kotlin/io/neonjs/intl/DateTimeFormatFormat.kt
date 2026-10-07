package io.neonjs.intl

import com.ibm.icu.text.ConstrainedFieldPosition
import com.ibm.icu.text.DateFormat
import com.ibm.icu.text.DateIntervalFormat
import com.ibm.icu.text.DateTimePatternGenerator
import com.ibm.icu.text.SimpleDateFormat
import com.ibm.icu.util.TimeZone
import com.ibm.icu.util.ULocale
import io.neonjs.builtins.temporal.*
import io.neonjs.runtime.*
import java.math.BigInteger
import java.util.IdentityHashMap

/** ICU objects of one DateTimeFormat. Not thread-safe; owned by a single Intl.DateTimeFormat instance. */
internal class DtfIcu(val uloc: ULocale, val gen: DateTimePatternGenerator, val zone: TimeZone) {
    private val formatters = IdentityHashMap<DtfFormat, SimpleDateFormat>()
    private val utcFormatters = IdentityHashMap<DtfFormat, SimpleDateFormat>()
    private val intervals = IdentityHashMap<DtfFormat, DateIntervalFormat>()
    private val utcIntervals = IdentityHashMap<DtfFormat, DateIntervalFormat>()

    private fun zoneFor(utc: Boolean): TimeZone = if (utc) TimeZone.getFrozenTimeZone("UTC") else zone

    fun formatter(f: DtfFormat, utc: Boolean): SimpleDateFormat = (if (utc) utcFormatters else formatters).getOrPut(f) {
        val z = zoneFor(utc)
        val sdf = SimpleDateFormat(f.pattern.replace(NNBSP, ' '), uloc)
        sdf.calendar = DtfData.newCalendar(uloc, z)
        sdf.timeZone = z
        sdf
    }

    fun interval(f: DtfFormat, utc: Boolean): DateIntervalFormat = (if (utc) utcIntervals else intervals).getOrPut(f) {
        // an explicit DateIntervalInfo bypasses ICU4J's static pattern cache, whose cached entries lose the
        // date/time sub-patterns (same-day ranges with seconds then repeat the date)
        val dif = DateIntervalFormat.getInstance(f.skeleton, uloc, com.ibm.icu.text.DateIntervalInfo(uloc))
        dif.timeZone = zoneFor(utc)
        dif
    }

    fun calendarAt(ms: Long, utc: Boolean): com.ibm.icu.util.Calendar {
        val c = DtfData.newCalendar(uloc, zoneFor(utc))
        c.timeInMillis = ms
        return c
    }

    companion object {
        fun of(dtf: JSIntlDateTimeFormat): DtfIcu {
            dtf.icu?.let { return it }
            val uloc = DtfData.icuLocale(dtf.dataLocale, dtf.calendar, dtf.numberingSystem)
            val gen = DateTimePatternGenerator.getInstance(uloc)
            val tz = dtf.timeZone
            val icuId = if (tz.startsWith("+") || tz.startsWith("-")) "GMT$tz" else tz
            var zone = TimeZone.getFrozenTimeZone(icuId)
            if (zone.id == TimeZone.UNKNOWN_ZONE_ID) zone = TimeZone.getFrozenTimeZone(TimeZones.find(tz)?.second ?: "UTC")
            val icu = DtfIcu(uloc, gen, zone)
            dtf.icu = icu
            return icu
        }
    }
}

/** U+202F NARROW NO-BREAK SPACE: replaced by U+0020 in output, like other engines, for web compatibility. */
internal const val NNBSP = ' '

/** A value to format: the format record, the epoch milliseconds and whether to format in UTC (plain Temporal types). */
internal class DtfValue(val format: DtfFormat, val ms: Long, val utc: Boolean, val kind: String)

/** FormatDateTime, FormatDateTimeToParts, FormatDateTimeRange(ToParts). */
internal object DtfFormatting {
    private val MS_PER_DAY = 86_400_000L
    private val NS_PER_MS: BigInteger = BigInteger.valueOf(1_000_000L)

    /** ToDateTimeFormattable */
    fun toFormattable(v: Any?): Any = if (isTemporal(v)) v!! else Ops.toNumber(v)

    fun isTemporal(v: Any?): Boolean = v is JSTemporalPlainDate || v is JSTemporalPlainDateTime || v is JSTemporalPlainTime ||
        v is JSTemporalPlainYearMonth || v is JSTemporalPlainMonthDay || v is JSTemporalInstant || v is JSTemporalZonedDateTime

    private fun epochMs(d: IsoDate, t: TimeRec): Long {
        val days = java.time.LocalDate.of(d.year, d.month, d.day).toEpochDay()
        return days * MS_PER_DAY + ((t.hour * 60L + t.minute) * 60L + t.second) * 1000L + t.millisecond
    }

    private fun checkCalendar(dtf: JSIntlDateTimeFormat, cal: String, isoAllowed: Boolean) {
        if (cal != dtf.calendar && !(isoAllowed && cal == "iso8601")) {
            rangeErr("Calendar $cal does not match the DateTimeFormat calendar ${dtf.calendar}")
        }
    }

    private fun temporal(dtf: JSIntlDateTimeFormat, kind: String): DtfFormat =
        DtfCreate.temporalFormat(dtf, kind) ?: typeErr("The DateTimeFormat options have no fields in common with the Temporal value")

    /** HandleDateTimeValue */
    fun handle(dtf: JSIntlDateTimeFormat, x: Any): DtfValue = when (x) {
        is Double -> {
            val tc = timeClip(x)
            if (tc.isNaN()) rangeErr("Invalid time value")
            DtfValue(dtf.format, tc.toLong(), false, "date")
        }
        is JSTemporalPlainDate -> {
            checkCalendar(dtf, x.calendar, true)
            DtfValue(temporal(dtf, "date"), epochMs(x.date, TimeRec.NOON), true, "date-plain")
        }
        is JSTemporalPlainDateTime -> {
            checkCalendar(dtf, x.calendar, true)
            DtfValue(temporal(dtf, "datetime"), epochMs(x.dt.date, x.dt.time), true, "datetime")
        }
        is JSTemporalPlainYearMonth -> {
            checkCalendar(dtf, x.calendar, false)
            DtfValue(temporal(dtf, "year-month"), epochMs(x.date, TimeRec.NOON), true, "year-month")
        }
        is JSTemporalPlainMonthDay -> {
            checkCalendar(dtf, x.calendar, false)
            DtfValue(temporal(dtf, "month-day"), epochMs(x.date, TimeRec.NOON), true, "month-day")
        }
        is JSTemporalPlainTime -> DtfValue(temporal(dtf, "time"), epochMs(IsoDate(1970, 1, 1), x.time), true, "time")
        is JSTemporalInstant -> {
            val ms = floorDiv(x.epochNs, NS_PER_MS).toLong()
            DtfValue(temporal(dtf, "instant"), ms, false, "instant")
        }
        is JSTemporalZonedDateTime -> typeErr("Temporal.ZonedDateTime is not supported by DateTimeFormat format methods; use toLocaleString")
        else -> typeErr("Invalid date value")
    }

    private fun floorDiv(a: BigInteger, b: BigInteger): BigInteger {
        val qr = a.divideAndRemainder(b)
        return if (qr[1].signum() < 0) qr[0].subtract(BigInteger.ONE) else qr[0]
    }

    /** TimeClip */
    fun timeClip(t: Double): Double {
        if (!t.isFinite() || Math.abs(t) > 8.64e15) return Double.NaN
        return Ops.integerPart(t) + 0.0
    }

    private fun <T> icuCall(block: () -> T): T = try {
        block()
    } catch (e: JSException) {
        throw e
    } catch (e: TerminationException) {
        throw e
    } catch (e: RuntimeException) {
        rangeErr("Unable to format the date")
    }

    fun format(realm: Realm, dtf: JSIntlDateTimeFormat, x: Any): String {
        val v = handle(dtf, x)
        val s = icuCall { DtfIcu.of(dtf).formatter(v.format, v.utc).format(java.util.Date(v.ms)) }
        return realm.checkedString(s)
    }

    /** A formatted part: type, value and (for ranges) source index (-1 shared, 0 start, 1 end). */
    class Part(val type: String, val value: String, val source: Int = -1)

    fun partsOf(dtf: JSIntlDateTimeFormat, v: DtfValue): List<Part> = icuCall {
        val sdf = DtfIcu.of(dtf).formatter(v.format, v.utc)
        val it = sdf.formatToCharacterIterator(java.util.Date(v.ms))
        val text = StringBuilder()
        var c = it.first()
        while (c != java.text.CharacterIterator.DONE) { text.append(c); c = it.next() }
        val usesYearName = patternHas(v.format.pattern, 'U')
        val out = ArrayList<Part>()
        var i = it.beginIndex
        while (i < it.endIndex) {
            it.index = i
            val limit = it.runLimit
            var type = "literal"
            for (k in it.attributes.keys) if (k is DateFormat.Field) type = typeOf(k, usesYearName)
            addPart(out, type, text.substring(i - it.beginIndex, limit - it.beginIndex), -1)
            i = limit
        }
        out
    }

    private fun addPart(out: ArrayList<Part>, type: String, value: String, source: Int) {
        if (value.isEmpty()) return
        val last = out.lastOrNull()
        if (type == "literal" && last != null && last.type == "literal" && last.source == source) {
            out[out.size - 1] = Part("literal", last.value + value, source)
        } else out.add(Part(type, value, source))
    }

    private fun patternHas(pattern: String, ch: Char): Boolean {
        var quoted = false
        for (c in pattern) {
            if (c == '\'') quoted = !quoted
            else if (!quoted && c == ch) return true
        }
        return false
    }

    private fun typeOf(f: DateFormat.Field, usesYearName: Boolean): String = when (f) {
        DateFormat.Field.ERA -> "era"
        DateFormat.Field.YEAR, DateFormat.Field.EXTENDED_YEAR, DateFormat.Field.YEAR_WOY -> if (usesYearName) "yearName" else "year"
        DateFormat.Field.RELATED_YEAR -> "relatedYear"
        DateFormat.Field.MONTH -> "month"
        DateFormat.Field.DAY_OF_MONTH -> "day"
        DateFormat.Field.DAY_OF_WEEK, DateFormat.Field.DOW_LOCAL -> "weekday"
        DateFormat.Field.AM_PM, DateFormat.Field.AM_PM_MIDNIGHT_NOON, DateFormat.Field.FLEXIBLE_DAY_PERIOD -> "dayPeriod"
        DateFormat.Field.HOUR0, DateFormat.Field.HOUR1, DateFormat.Field.HOUR_OF_DAY0, DateFormat.Field.HOUR_OF_DAY1 -> "hour"
        DateFormat.Field.MINUTE -> "minute"
        DateFormat.Field.SECOND -> "second"
        DateFormat.Field.MILLISECOND -> "fractionalSecond"
        DateFormat.Field.TIME_ZONE -> "timeZoneName"
        else -> "literal"
    }

    fun partsArray(realm: Realm, parts: List<Part>, withSource: Boolean): JSArray {
        val out = ArrayList<Any?>(parts.size)
        var total = 0L
        for ((i, p) in parts.withIndex()) {
            realm.tick(i.toLong())
            val o = partObject(realm, p.type, p.value)
            if (withSource) o.createDataPropertyOrThrow("source", when (p.source) { 0 -> "startRange"; 1 -> "endRange"; else -> "shared" })
            total += p.value.length
            out.add(o)
        }
        realm.agent.checkStringLength(total)
        return jsArray(realm, out)
    }

    // ------------------------------------------------------------------ ranges

    private fun sameKind(x: Any, y: Any): Boolean = when (x) {
        is Double -> y is Double
        else -> x.javaClass == y.javaClass
    }

    /** PartitionDateTimeRangePattern */
    fun rangeParts(dtf: JSIntlDateTimeFormat, x: Any, y: Any): List<Part> {
        if ((isTemporal(x) || isTemporal(y)) && !sameKind(x, y)) typeErr("Temporal values of different types cannot form a range")
        val vx = handle(dtf, x)
        val vy = handle(dtf, y)
        return icuCall {
            val icu = DtfIcu.of(dtf)
            val dif = icu.interval(vx.format, vx.utc)
            val fdi = dif.formatToValue(icu.calendarAt(vx.ms, vx.utc), icu.calendarAt(vy.ms, vy.utc))
            val text = fdi.toString().replace(NNBSP, ' ')
            val n = text.length
            val fieldType = arrayOfNulls<String>(n)
            val fieldId = IntArray(n) { -1 }
            val source = IntArray(n) { -1 }
            var hasSpan = false
            val usesYearName = patternHas(vx.format.pattern, 'U')
            val cfpos = ConstrainedFieldPosition()
            var id = 0
            while (fdi.nextPosition(cfpos)) {
                val f = cfpos.field
                if (f is DateIntervalFormat.SpanField) {
                    hasSpan = true
                    val sv = (cfpos.fieldValue as? Number)?.toInt() ?: -1
                    for (k in cfpos.start until cfpos.limit) source[k] = sv
                } else if (f is DateFormat.Field) {
                    val t = typeOf(f, usesYearName)
                    id++
                    for (k in cfpos.start until cfpos.limit) { fieldType[k] = t; fieldId[k] = id }
                }
            }
            if (!hasSpan) partsOf(dtf, vx)
            else {
                val out = ArrayList<Part>()
                var i = 0
                while (i < n) {
                    var j = i + 1
                    while (j < n && fieldId[j] == fieldId[i] && source[j] == source[i]) j++
                    var value = text.substring(i, j)
                    val type = fieldType[i] ?: "literal"
                    if (type == "hour") value = fixHour(value, dtf.hourCycle, vx.format.fields["hour"] == "2-digit")
                    addPart(out, type, value, source[i])
                    i = j
                }
                out
            }
        }
    }

    /** DateIntervalFormat ignores K/k skeleton letters: re-map hour values for h11 / h24. */
    private fun fixHour(value: String, hc: String?, twoDigit: Boolean): String {
        if (hc != "h11" && hc != "h24") return value
        if (value.isEmpty() || !value.all { it in '0'..'9' }) return value
        val h = value.toInt()
        val nh = when {
            hc == "h11" && h == 12 -> 0
            hc == "h24" && h == 0 -> 24
            else -> return value
        }
        return if (twoDigit) nh.toString().padStart(2, '0') else nh.toString()
    }
}
