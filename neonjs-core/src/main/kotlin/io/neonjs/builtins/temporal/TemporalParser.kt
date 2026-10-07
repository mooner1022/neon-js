package io.neonjs.builtins.temporal

import io.neonjs.runtime.Agent
import io.neonjs.runtime.NumberConv
import java.math.BigInteger

/** ISO Date-Time Parse Record (plus parser bookkeeping). */
internal class IsoParse {
    @JvmField var year = 0
    @JvmField var yearAbsent = false
    @JvmField var month = 1
    @JvmField var day = 1
    @JvmField var hasDay = true
    @JvmField var hasTime = false
    @JvmField var hour = 0
    @JvmField var minute = 0
    @JvmField var second = 0
    @JvmField var millisecond = 0
    @JvmField var microsecond = 0
    @JvmField var nanosecond = 0
    @JvmField var z = false
    @JvmField var offset: String? = null
    @JvmField var offsetHasSeconds = false
    @JvmField var tzAnnotation: String? = null
    @JvmField var calendar: String? = null

    // annotation processing state (applied while parsing, so nothing proportional to the input is retained)
    @JvmField var haveCalendar = false
    @JvmField var calendarCritical = false
    @JvmField var annotationError: String? = null

    fun time(): TimeRec = TimeRec(hour, minute, second, millisecond, microsecond, nanosecond)
    fun date(): IsoDate = IsoDate(year, month, day)
}

/** Hand-written parser for the RFC 9557 / ISO 8601 grammar used by Temporal. */
internal object TemporalParser {
    private const val INTERRUPT_STRIDE = 1 shl 16

    const val G_DATETIME = 0 // TemporalDateTimeString[~Zoned]
    const val G_ZONED = 1 // TemporalDateTimeString[+Zoned]
    const val G_INSTANT = 2
    const val G_TIME = 3
    const val G_MONTHDAY = 4
    const val G_YEARMONTH = 5

    @JvmField val ALL_GOALS = intArrayOf(G_ZONED, G_DATETIME, G_INSTANT, G_TIME, G_MONTHDAY, G_YEARMONTH)

    private class Cur(@JvmField val s: String) {
        @JvmField var i = 0
        @JvmField var nextCheck = INTERRUPT_STRIDE

        /** Keeps long scans over huge inputs interruptible. */
        fun tick() {
            if (i >= nextCheck) {
                nextCheck = i + INTERRUPT_STRIDE
                Agent.current.get()?.checkInterrupt()
            }
        }
        val eof: Boolean get() = i >= s.length
        fun peek(off: Int = 0): Char = if (i + off < s.length) s[i + off] else '\u0000'
        fun isDigit(off: Int = 0): Boolean = peek(off) in '0'..'9'
        fun dig(off: Int = 0): Int = peek(off) - '0'
    }

    // ------------------------------------------------------------------ public entry points

    /** ParseISODateTime(isoString, allowedFormats) */
    fun parseIsoDateTime(s: String, goals: IntArray): IsoParse =
        parseImpl(s, goals, true) ?: tRangeErr("invalid ISO 8601 string: ${s.take(60)}")

    /** ParseISODateTime returning null instead of throwing. */
    fun tryParseIsoDateTime(s: String, goals: IntArray): IsoParse? = parseImpl(s, goals, false)

    private fun parseImpl(s: String, goals: IntArray, throwing: Boolean): IsoParse? {
        for (goal in goals) {
            val r = tryGoal(s, goal) ?: continue
            val err = processAnnotations(r) ?: when (goal) {
                G_YEARMONTH if !r.hasDay && !isoOrAbsent(r.calendar) -> "calendar annotation not allowed"
                G_MONTHDAY if r.yearAbsent && !isoOrAbsent(r.calendar) -> "calendar annotation not allowed"
                else -> null
            }
            if (err != null) {
                if (throwing) tRangeErr(err)
                return null
            }
            return r
        }
        return null
    }

    private fun isoOrAbsent(c: String?): Boolean = c == null || asciiEqualsIgnoreCase(c, "iso8601")

    fun parseIsoDateTime(s: String, goal: Int): IsoParse = parseIsoDateTime(s, intArrayOf(goal))

    /**
     * ParseTimeZoneIdentifier: returns null when the identifier is syntactically invalid. The result is either a name
     * (String) or offset minutes (Int).
     */
    fun parseTimeZoneIdentifier(s: String): Any? {
        val c = Cur(s)
        val r = parseTzIdentifier(c) ?: return null
        if (!c.eof) return null
        return r
    }

    /** ParseDateTimeUTCOffset: offset in nanoseconds, or null if [s] is not a UTCOffset[+SubMinutePrecision]. */
    fun parseUtcOffset(s: String): Long? {
        val c = Cur(s)
        val r = parseOffset(c, true) ?: return null
        if (!c.eof) return null
        return r
    }

    /** True if [s] matches AnnotationValue (used for bare calendar ids). */
    fun isAnnotationValue(s: String): Boolean {
        if (s.isEmpty()) return false
        var compLen = 0
        for (ch in s) {
            when (ch) {
                '-' -> {
                    if (compLen == 0) return false
                    compLen = 0
                }
                in 'a'..'z', in 'A'..'Z', in '0'..'9' -> compLen++
                else -> return false
            }
        }
        return compLen > 0
    }

    // ------------------------------------------------------------------ annotations

    /** The first annotation rule violation found while parsing, or null. */
    private fun processAnnotations(r: IsoParse): String? = r.annotationError

    /** Applies the annotation rules of ParseISODateTime to one annotation. */
    private fun applyAnnotation(r: IsoParse, s: String, critical: Boolean, keyStart: Int, keyEnd: Int, valueStart: Int, valueEnd: Int) {
        if (r.annotationError != null) return
        if (keyEnd - keyStart == 4 && s.regionMatches(keyStart, "u-ca", 0, 4)) {
            if (!r.haveCalendar) {
                r.haveCalendar = true
                r.calendar = s.substring(valueStart, valueEnd)
                if (critical) r.calendarCritical = true
            } else if (critical || r.calendarCritical) {
                r.annotationError = "multiple calendar annotations with critical flag"
            }
        } else if (critical) {
            r.annotationError = "unknown critical annotation"
        }
    }

    // ------------------------------------------------------------------ goals

    private fun tryGoal(s: String, goal: Int): IsoParse? = when (goal) {
        G_DATETIME -> annotatedDateTime(s, zoned = false, timeRequired = false)
        G_ZONED -> annotatedDateTime(s, zoned = true, timeRequired = false)
        G_INSTANT -> instant(s)
        G_TIME -> annotatedTime(s) ?: annotatedDateTime(s, zoned = false, timeRequired = true)
        G_MONTHDAY -> annotatedMonthDay(s) ?: annotatedDateTime(s, zoned = false, timeRequired = false)
        G_YEARMONTH -> annotatedYearMonth(s) ?: annotatedDateTime(s, zoned = false, timeRequired = false)
        else -> null
    }

    private fun annotatedDateTime(s: String, zoned: Boolean, timeRequired: Boolean): IsoParse? {
        val c = Cur(s)
        val r = IsoParse()
        if (!parseDate(c, r)) return null
        val sep = c.peek()
        if (sep == 'T' || sep == 't' || sep == ' ') {
            c.i++
            if (!parseTime(c, r)) return null
            if (!parseDateTimeOffset(c, r, zoned)) return null
        } else if (timeRequired) return null
        if (!parseAnnotations(c, r, zoned)) return null
        return r
    }

    private fun instant(s: String): IsoParse? {
        val c = Cur(s)
        val r = IsoParse()
        if (!parseDate(c, r)) return null
        val sep = c.peek()
        if (sep != 'T' && sep != 't' && sep != ' ') return null
        c.i++
        if (!parseTime(c, r)) return null
        if (!parseDateTimeOffset(c, r, true)) return null
        if (!r.z && r.offset == null) return null
        if (!parseAnnotations(c, r, false)) return null
        return r
    }

    private fun annotatedTime(s: String): IsoParse? {
        val c = Cur(s)
        val r = IsoParse()
        val designator = c.peek() == 'T' || c.peek() == 't'
        if (designator) c.i++
        if (!parseTime(c, r)) return null
        if (!parseDateTimeOffset(c, r, false)) return null
        val endOfTime = c.i
        if (!parseAnnotations(c, r, false)) return null
        if (!designator) {
            val text = s.substring(0, endOfTime)
            if (isDateSpecMonthDay(text) || isDateSpecYearMonth(text)) return null
        }
        r.yearAbsent = false
        return r.also { it.year = 1970 }
    }

    private fun annotatedMonthDay(s: String): IsoParse? {
        val c = Cur(s)
        val r = IsoParse()
        if (!parseMonthDaySpec(c, r)) return null
        if (!parseAnnotations(c, r, false)) return null
        r.yearAbsent = true
        r.year = 1972
        return r
    }

    private fun annotatedYearMonth(s: String): IsoParse? {
        val c = Cur(s)
        val r = IsoParse()
        if (!parseYearMonthSpec(c, r)) return null
        if (!parseAnnotations(c, r, false)) return null
        r.hasDay = false
        r.day = 1
        return r
    }

    private fun isDateSpecMonthDay(text: String): Boolean {
        val c = Cur(text)
        return parseMonthDaySpec(c, IsoParse()) && c.eof
    }

    private fun isDateSpecYearMonth(text: String): Boolean {
        val c = Cur(text)
        return parseYearMonthSpec(c, IsoParse()) && c.eof
    }

    // ------------------------------------------------------------------ date parts

    private fun parseYear(c: Cur, r: IsoParse): Boolean {
        val ch = c.peek()
        if (ch == '+' || ch == '-') {
            for (k in 1..6) if (!c.isDigit(k)) return false
            var v = 0
            for (k in 1..6) v = v * 10 + c.dig(k)
            if (ch == '-' && v == 0) return false
            r.year = if (ch == '-') -v else v
            c.i += 7
            return true
        }
        for (k in 0..3) if (!c.isDigit(k)) return false
        r.year = c.dig(0) * 1000 + c.dig(1) * 100 + c.dig(2) * 10 + c.dig(3)
        c.i += 4
        return true
    }

    private fun parseMonth(c: Cur): Int {
        if (!c.isDigit(0) || !c.isDigit(1)) return -1
        val m = c.dig(0) * 10 + c.dig(1)
        if (m !in 1..12) return -1
        c.i += 2
        return m
    }

    private fun parseDay(c: Cur): Int {
        if (!c.isDigit(0) || !c.isDigit(1)) return -1
        val d = c.dig(0) * 10 + c.dig(1)
        if (d !in 1..31) return -1
        c.i += 2
        return d
    }

    private fun validMonthDay(m: Int, d: Int): Boolean {
        if (d == 31 && (m == 2 || m == 4 || m == 6 || m == 9 || m == 11)) return false
        if (m == 2 && d == 30) return false
        return true
    }

    /** DateSpec[Extended] with IsValidDate. */
    private fun parseDate(c: Cur, r: IsoParse): Boolean {
        val start = c.i
        if (!parseYear(c, r)) { c.i = start; return false }
        val extended = c.peek() == '-'
        if (extended) c.i++
        val m = parseMonth(c)
        if (m < 0) { c.i = start; return false }
        if (extended) {
            if (c.peek() != '-') { c.i = start; return false }
            c.i++
        }
        val d = parseDay(c)
        if (d < 0) { c.i = start; return false }
        if (!validMonthDay(m, d)) { c.i = start; return false }
        if (m == 2 && d == 29 && !TM.isLeap(r.year.toLong())) { c.i = start; return false }
        r.month = m
        r.day = d
        return true
    }

    private fun parseMonthDaySpec(c: Cur, r: IsoParse): Boolean {
        val start = c.i
        if (c.peek() == '-' && c.peek(1) == '-') c.i += 2
        val m = parseMonth(c)
        if (m < 0) { c.i = start; return false }
        if (c.peek() == '-') c.i++
        val d = parseDay(c)
        if (d < 0 || !validMonthDay(m, d)) { c.i = start; return false }
        r.month = m
        r.day = d
        return true
    }

    private fun parseYearMonthSpec(c: Cur, r: IsoParse): Boolean {
        val start = c.i
        if (!parseYear(c, r)) { c.i = start; return false }
        if (c.peek() == '-') c.i++
        val m = parseMonth(c)
        if (m < 0) { c.i = start; return false }
        r.month = m
        return true
    }

    // ------------------------------------------------------------------ time parts

    private fun parseHour(c: Cur): Int {
        if (!c.isDigit(0) || !c.isDigit(1)) return -1
        val h = c.dig(0) * 10 + c.dig(1)
        if (h > 23) return -1
        c.i += 2
        return h
    }

    private fun parseMinuteSecond(c: Cur): Int {
        if (!c.isDigit(0) || !c.isDigit(1)) return -1
        val v = c.dig(0) * 10 + c.dig(1)
        if (v > 59) return -1
        c.i += 2
        return v
    }

    /** TemporalDecimalFraction: returns the nanoseconds (0..999999999) or -1 if absent/invalid; [c] advanced. */
    private fun parseFraction(c: Cur): Int {
        val sep = c.peek()
        if (sep != '.' && sep != ',') return -1
        var k = 1
        var v = 0
        while (c.isDigit(k)) {
            if (k > 9) return -2
            v = v * 10 + c.dig(k)
            k++
        }
        val n = k - 1
        if (n == 0) return -2
        repeat(9 - n) { v *= 10 }
        c.i += k
        return v
    }

    /** TimeSpec */
    private fun parseTime(c: Cur, r: IsoParse): Boolean {
        val start = c.i
        val h = parseHour(c)
        if (h < 0) { c.i = start; return false }
        r.hour = h
        r.hasTime = true
        r.minute = 0; r.second = 0; r.millisecond = 0; r.microsecond = 0; r.nanosecond = 0
        val extended = c.peek() == ':'
        if (extended) {
            c.i++
            val mi = parseMinuteSecond(c)
            if (mi < 0) { c.i = start; return false }
            r.minute = mi
            if (c.peek() == ':') {
                c.i++
                if (!parseTimeSecond(c, r)) { c.i = start; return false }
            }
        } else if (c.isDigit(0) && c.isDigit(1)) {
            val mi = parseMinuteSecond(c)
            if (mi < 0) { c.i = start; return false }
            r.minute = mi
            if (c.isDigit(0) && c.isDigit(1)) {
                if (!parseTimeSecond(c, r)) { c.i = start; return false }
            }
        }
        return true
    }

    private fun parseTimeSecond(c: Cur, r: IsoParse): Boolean {
        if (!c.isDigit(0) || !c.isDigit(1)) return false
        val v = c.dig(0) * 10 + c.dig(1)
        if (v > 60) return false
        c.i += 2
        r.second = if (v == 60) 59 else v
        val f = parseFraction(c)
        if (f == -2) return false
        if (f >= 0) {
            r.millisecond = f / 1_000_000
            r.microsecond = (f / 1000) % 1000
            r.nanosecond = f % 1000
        }
        return true
    }

    // ------------------------------------------------------------------ offsets

    /**
     * UTCOffset[SubMinutePrecision]: returns offset nanoseconds or null, advancing [c] past the match. [hadSecondsOut]
     * (if given) receives whether a seconds component was present.
     */
    private fun parseOffset(c: Cur, subMinute: Boolean, hadSecondsOut: BooleanArray? = null): Long? {
        val start = c.i
        val sign = c.peek()
        if (sign != '+' && sign != '-') return null
        c.i++
        val h = parseHour(c)
        if (h < 0) { c.i = start; return null }
        var minutes = 0
        var seconds = 0
        var nanos = 0
        var hadSeconds = false
        if (c.peek() == ':') {
            c.i++
            val m = parseMinuteSecond(c)
            if (m < 0) { c.i = start; return null }
            minutes = m
            if (subMinute && c.peek() == ':') {
                val save = c.i
                c.i++
                val sec = parseMinuteSecond(c)
                if (sec < 0) { c.i = save } else {
                    seconds = sec
                    hadSeconds = true
                    val f = parseFraction(c)
                    if (f == -2) { c.i = start; return null }
                    if (f >= 0) nanos = f
                }
            }
        } else if (c.isDigit(0) && c.isDigit(1)) {
            val m = parseMinuteSecond(c)
            if (m < 0) { c.i = start; return null }
            minutes = m
            if (subMinute && c.isDigit(0) && c.isDigit(1)) {
                val sec = parseMinuteSecond(c)
                if (sec < 0) { c.i = start; return null }
                seconds = sec
                hadSeconds = true
                val f = parseFraction(c)
                if (f == -2) { c.i = start; return null }
                if (f >= 0) nanos = f
            }
        }
        if (hadSecondsOut != null) hadSecondsOut[0] = hadSeconds
        val total = ((h * 60L + minutes) * 60L + seconds) * 1_000_000_000L + nanos
        return if (sign == '-') -total else total
    }

    /** DateTimeUTCOffset[Z]? (optional); returns false on a malformed offset. */
    private fun parseDateTimeOffset(c: Cur, r: IsoParse, allowZ: Boolean): Boolean {
        val ch = c.peek()
        if (ch == 'Z' || ch == 'z') {
            if (!allowZ) return false
            c.i++
            r.z = true
            return true
        }
        if (ch == '+' || ch == '-') {
            val start = c.i
            val h = BooleanArray(1)
            parseOffset(c, true, h) ?: return false
            r.offset = c.s.substring(start, c.i)
            r.offsetHasSeconds = h[0]
        }
        return true
    }

    // ------------------------------------------------------------------ time zone identifiers and annotations

    /** TimeZoneIdentifier: returns offset minutes (Int) or the IANA-like name (String); null on failure. */
    private fun parseTzIdentifier(c: Cur): Any? {
        val start = c.i
        val ch = c.peek()
        if (ch == '+' || ch == '-') {
            val ns = parseOffset(c, false) ?: return null
            return (ns / 60_000_000_000L).toInt()
        }
        // TimeZoneIANAName
        while (true) {
            val l = c.peek()
            if (!(l in 'a'..'z' || l in 'A'..'Z' || l == '.' || l == '_')) { c.i = start; return null }
            c.i++
            while (true) {
                val t = c.peek()
                if (t in 'a'..'z' || t in 'A'..'Z' || t == '.' || t == '_' || t in '0'..'9' || t == '-' || t == '+') c.i++ else break
                c.tick()
            }
            c.tick()
            if (c.peek() == '/') { c.i++; continue }
            break
        }
        return c.s.substring(start, c.i)
    }

    private fun parseTzAnnotation(c: Cur, r: IsoParse): Boolean {
        val start = c.i
        if (c.peek() != '[') return false
        c.i++
        if (c.peek() == '!') c.i++
        val idStart = c.i
        if (parseTzIdentifier(c) == null || c.peek() != ']') { c.i = start; return false }
        r.tzAnnotation = c.s.substring(idStart, c.i)
        c.i++
        return true
    }

    private fun parseAnnotation(c: Cur, r: IsoParse): Boolean {
        val start = c.i
        if (c.peek() != '[') return false
        c.i++
        var critical = false
        if (c.peek() == '!') { critical = true; c.i++ }
        val keyStart = c.i
        val k0 = c.peek()
        if (!(k0 in 'a'..'z' || k0 == '_')) { c.i = start; return false }
        c.i++
        while (true) {
            val k = c.peek()
            if (k in 'a'..'z' || k == '_' || k in '0'..'9' || k == '-') c.i++ else break
            c.tick()
        }
        val keyEnd = c.i
        if (c.peek() != '=') { c.i = start; return false }
        c.i++
        val valueStart = c.i
        var compLen = 0
        while (true) {
            val v = c.peek()
            if (v in 'a'..'z' || v in 'A'..'Z' || v in '0'..'9') { compLen++; c.i++ } else if (v == '-' && compLen > 0) { compLen = 0; c.i++ } else break
            c.tick()
        }
        if (compLen == 0 || c.peek() != ']') { c.i = start; return false }
        applyAnnotation(r, c.s, critical, keyStart, keyEnd, valueStart, c.i)
        c.i++
        return true
    }

    /** TimeZoneAnnotation? Annotations? followed by end of input; [tzRequired] for the Zoned form. */
    private fun parseAnnotations(c: Cur, r: IsoParse, tzRequired: Boolean): Boolean {
        if (c.peek() == '[') parseTzAnnotation(c, r)
        if (tzRequired && r.tzAnnotation == null) return false
        while (c.peek() == '[') {
            if (!parseAnnotation(c, r)) return false
            c.tick()
        }
        return c.eof
    }

    // ------------------------------------------------------------------ durations

    /** ParseTemporalDurationString: returns the ten fields, or throws RangeError. */
    fun parseDuration(s: String): DoubleArray {
        val fail = { tRangeErr("invalid duration string: ${s.take(60)}") }
        var i = 0
        val n = s.length
        var negative = false
        if (i < n && (s[i] == '+' || s[i] == '-')) { negative = s[i] == '-'; i++ }
        if (i >= n || (s[i] != 'P' && s[i] != 'p')) fail()
        i++
        val out = DoubleArray(10)
        // date part: Y M W D in order
        var dateIdx = 0 // next allowed date unit index (0 Y,1 M,2 W,3 D)
        var any = false
        while (i < n && s[i] != 'T' && s[i] != 't') {
            val ds = i
            while (i < n && s[i] in '0'..'9') i++
            if (i == ds || i >= n) fail()
            val unit = when (s[i]) {
                'Y', 'y' -> 0
                'M', 'm' -> 1
                'W', 'w' -> 2
                'D', 'd' -> 3
                else -> -1
            }
            if (unit < dateIdx) fail()
            out[unit] = digitsToNumber(s, ds, i)
            dateIdx = unit + 1
            any = true
            i++
        }
        var fracNs: BigInteger? = null
        var fracUnit = -1
        if (i < n) {
            // time designator
            i++
            var timeIdx = 0 // 0 H, 1 M, 2 S
            var anyTime = false
            while (i < n) {
                if (fracUnit >= 0) fail()
                val ds = i
                while (i < n && s[i] in '0'..'9') i++
                if (i == ds || i >= n) fail()
                val digitsEnd = i
                var fDigits: String? = null
                if (s[i] == '.' || s[i] == ',') {
                    val fs = i + 1
                    i = fs
                    while (i < n && s[i] in '0'..'9') i++
                    val fl = i - fs
                    if (fl !in 1..9 || i >= n) fail()
                    fDigits = s.substring(fs, i)
                }
                val unit = when (s[i]) {
                    'H', 'h' -> 0
                    'M', 'm' -> 1
                    'S', 's' -> 2
                    else -> -1
                }
                if (unit < timeIdx) fail()
                out[4 + unit] = digitsToNumber(s, ds, digitsEnd)
                if (fDigits != null) {
                    fracUnit = unit
                    val unitNs = when (unit) { 0 -> 3_600_000_000_000L; 1 -> 60_000_000_000L; else -> 1_000_000_000L }
                    var scale = BigInteger.ONE
                    repeat(fDigits.length) { scale = scale.multiply(BigInteger.TEN) }
                    fracNs = BigInteger(fDigits).multiply(BigInteger.valueOf(unitNs)).divide(scale)
                }
                timeIdx = unit + 1
                anyTime = true
                i++
            }
            if (!anyTime) fail()
            any = true
        }
        if (!any) fail()
        if (fracNs != null) {
            // distribute the fractional part into smaller units (exact integer nanoseconds)
            var f = fracNs.toLong()
            if (fracUnit == 0) {
                out[5] = (f / 60_000_000_000L).toDouble()
                f %= 60_000_000_000L
                out[6] = (f / 1_000_000_000L).toDouble()
                f %= 1_000_000_000L
            } else if (fracUnit == 1) {
                out[6] = (f / 1_000_000_000L).toDouble()
                f %= 1_000_000_000L
            }
            out[7] = (f / 1_000_000L).toDouble()
            out[8] = ((f / 1000L) % 1000L).toDouble()
            out[9] = (f % 1000L).toDouble()
        }
        if (negative) for (k in 0 until 10) out[k] = TNum.neg(out[k])
        return out
    }

    /** ToIntegerWithTruncation of a digit string. */
    private fun digitsToNumber(s: String, from0: Int, to: Int): Double {
        var from = from0
        while (from < to - 1 && s[from] == '0') from++
        // more than 309 significant digits cannot be a finite Number
        if (to - from > 309) tRangeErr("duration component too large")
        val d = NumberConv.stringToNumber(s.substring(from, to))
        if (d.isInfinite() || d.isNaN()) tRangeErr("duration component too large")
        return d
    }

    // ------------------------------------------------------------------ month codes

    /** MonthCode grammar: returns month number shl 1 | leap, or -1. */
    fun parseMonthCodeString(s: String): Int {
        if (s.length != 3 && s.length != 4) return -1
        if (s[0] != 'M') return -1
        if (s[1] !in '0'..'9' || s[2] !in '0'..'9') return -1
        val num = (s[1] - '0') * 10 + (s[2] - '0')
        val leap = s.length == 4
        if (leap && s[3] != 'L') return -1
        if (num == 0 && !leap) return -1
        return (num shl 1) or (if (leap) 1 else 0)
    }
}
