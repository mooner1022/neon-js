package dev.mooner.neonjs.builtins

import dev.mooner.neonjs.runtime.NumberConv

/**
 * Date.parse. Strings in the Date Time String Format (ECMA-262 §21.4.1.32) are parsed and validated strictly:
 * date-only forms are UTC, date-time forms without an offset are local time. Any other string goes through a
 * permissive fallback that understands the output of toString, toUTCString and toLocaleString as well as common legacy
 * forms such as "Jan 2 2000 10:00:00 GMT+0100", "2000/01/02 10:00 PM" or "Sunday, January 2, 2000".
 */
internal object DateParser {
    /** Returns the (clipped) time value denoted by [s], or NaN. */
    fun parse(s: String): Double = parseIso(s) ?: Legacy(s).parse()

    /** Returns null when [s] does not have the Date Time String Format syntax, NaN when a field is out of range. */
    private fun parseIso(s: String): Double? {
        val n = s.length
        var p = 0

        /** Reads exactly [count] digits, or returns -1 without consuming anything. */
        fun digits(count: Int): Int {
            if (p + count > n) return -1
            var v = 0
            for (i in p until p + count) {
                val c = s[i]
                if (c !in '0'..'9') return -1
                v = v * 10 + (c - '0')
            }
            p += count
            return v
        }

        fun at(c: Char): Boolean = p < n && s[p] == c

        val year: Int
        if (at('+') || at('-')) {
            val negative = s[p++] == '-'
            val y = digits(6)
            if (y < 0) return null
            if (negative && y == 0) return Double.NaN // -000000 is not a valid year
            year = if (negative) -y else y
        } else {
            year = digits(4)
            if (year < 0) return null
        }
        var month = 1
        var day = 1
        if (at('-')) {
            p++
            month = digits(2)
            if (month < 0) return null
            if (at('-')) {
                p++
                day = digits(2)
                if (day < 0) return null
            }
        }
        var hour = 0
        var minute = 0
        var second = 0
        var ms = 0
        var hasTime = false
        var offsetMinutes = 0
        var hasOffset = false
        if (at('T')) {
            p++
            hasTime = true
            hour = digits(2)
            if (hour < 0 || !at(':')) return null
            p++
            minute = digits(2)
            if (minute < 0) return null
            if (at(':')) {
                p++
                second = digits(2)
                if (second < 0) return null
                if (at('.') || at(',')) {
                    // The format has exactly three fraction digits; more are accepted and truncated to milliseconds.
                    val start = ++p
                    while (p < n && s[p] in '0'..'9') {
                        if (p - start < 3) ms = ms * 10 + (s[p] - '0')
                        p++
                    }
                    val count = p - start
                    if (count == 0) return null
                    repeat(3 - count) { ms *= 10 }
                }
            }
            if (at('Z')) {
                p++
                hasOffset = true
            } else if (at('+') || at('-')) {
                val negative = s[p++] == '-'
                val hh = digits(2)
                if (hh < 0) return null
                if (at(':')) p++
                val mm = digits(2)
                if (mm < 0) return null
                if (hh > 23 || mm > 59) return Double.NaN
                offsetMinutes = if (negative) -(hh * 60 + mm) else hh * 60 + mm
                hasOffset = true
            }
        }
        if (p != n) return null

        if (month !in 1..12 || day < 1 || day > DateTime.daysInMonth(year.toLong(), month - 1)) return Double.NaN
        if (hour > 24 || minute > 59 || second > 59 || (hour == 24 && (minute != 0 || second != 0 || ms != 0))) return Double.NaN
        val date = DateTime.makeDate(
            DateTime.makeDay(year.toDouble(), (month - 1).toDouble(), day.toDouble()),
            DateTime.makeTime(hour.toDouble(), minute.toDouble(), second.toDouble(), ms.toDouble()),
        )
        val tv = when {
            hasOffset -> date - offsetMinutes * DateTime.MS_PER_MINUTE
            hasTime -> DateTime.utc(date)
            else -> date
        }
        return DateTime.timeClip(tv)
    }

    /** Time zone abbreviations understood by the fallback parser, with their offsets in minutes. */
    private val TZ_ABBREVIATIONS = listOf(
        "GMT" to 0, "UTC" to 0, "UT" to 0, "Z" to 0,
        "EDT" to -4 * 60, "EST" to -5 * 60, "CDT" to -5 * 60, "CST" to -6 * 60,
        "MDT" to -6 * 60, "MST" to -7 * 60, "PDT" to -7 * 60, "PST" to -8 * 60,
        "WEST" to 1 * 60, "WET" to 0, "CEST" to 2 * 60, "CET" to 1 * 60, "EEST" to 3 * 60, "EET" to 2 * 60,
    )

    private const val END = '\u0000'

    /**
     * Permissive tokenizer: numbers (year, month, day by position), month names, h:mm[:ss[.fff]] with AM/PM, time zone
     * abbreviations, `±hh[mm]` offsets after a time, and parenthesized comments. Leading words (weekdays) are ignored.
     * Missing fields default to 2001-01-01 00:00:00 local time.
     */
    private class Legacy(src: String) {
        private val s = buildString(src.length) {
            for (c in src) {
                append(
                    when {
                        c == ' ' || c == ' ' -> 'x'
                        NumberConv.isJSWhitespace(c) -> ' '
                        c == '−' -> '-'
                        c == END || c > 'ÿ' -> 'x'
                        else -> c
                    },
                )
            }
        }
        private var p = 0

        private var year = 2001
        private var month = 1
        private var day = 1
        private var hour = 0
        private var minute = 0
        private var second = 0
        private var ms = 0
        private var offsetMinutes = 0
        private var isLocal = true
        private var hasYear = false
        private var hasMonth = false
        private var hasTime = false
        private val nums = IntArray(3)
        private var numCount = 0

        private fun ch(): Char = if (p < s.length) s[p] else END

        private fun skipChar(c: Char): Boolean {
            if (ch() != c) return false
            p++
            return true
        }

        private fun skipSpaces(): Char {
            while (ch() == ' ') p++
            return ch()
        }

        private fun skipSeparators() {
            while (ch().let { it == '-' || it == '/' || it == '.' || it == ',' }) p++
        }

        private fun skipUntil(stop: String) {
            while (ch() != END && ch() !in stop) p++
        }

        /** Reads [min]..[max] digits (max 0: unbounded, at most 9 in any case); null without consuming on failure. */
        private fun digits(min: Int, max: Int): Int? {
            val start = p
            var q = p
            var v = 0
            while (q < s.length && s[q] in '0'..'9') {
                if (q - start == 9) return null
                v = v * 10 + (s[q] - '0')
                q++
                if (q - start == max) break
            }
            if (q - start < min) return null
            p = q
            return v
        }

        /** Optional fraction of a second, truncated to milliseconds. */
        private fun fraction() {
            if (ch() != '.' && ch() != ',') return
            var q = p + 1
            var mul = 100
            var v = 0
            while (q < s.length && s[q] in '0'..'9') {
                v += (s[q] - '0') * mul
                mul /= 10
                q++
            }
            if (q > p + 1) {
                ms = v
                p = q
            }
        }

        /** Case-insensitive match of [word] at the cursor, consuming it on success. */
        private fun match(word: String): Boolean {
            if (!s.regionMatches(p, word, 0, word.length, ignoreCase = true)) return false
            p += word.length
            return true
        }

        /** ±hh, ±hhmm or ±hh:mm after a time of day. */
        private fun offset(): Boolean {
            val start = p
            val negative = s[p++] == '-'
            val digitStart = p
            var hh = digits(1, 0)
            if (hh == null) {
                p = start
                return false
            }
            var count = p - digitStart
            while (count > 4) {
                count -= 2
                hh /= 100
            }
            var mm = 0
            if (count > 2) {
                mm = hh % 100
                hh /= 100
            } else if (skipChar(':')) {
                mm = digits(2, 2) ?: run { p = start; return false }
            }
            if (hh > 23 || mm > 59) {
                p = start
                return false
            }
            offsetMinutes = if (negative) -(hh * 60 + mm) else hh * 60 + mm
            return true
        }

        private fun monthName(): Boolean {
            for ((i, name) in DateTime.MONTH_NAMES.withIndex()) {
                if (s.regionMatches(p, name, 0, 3, ignoreCase = true)) {
                    month = i + 1
                    p += 3
                    return true
                }
            }
            return false
        }

        private fun zoneAbbreviation(): Boolean {
            for ((name, minutes) in TZ_ABBREVIATIONS) {
                if (match(name)) {
                    offsetMinutes = minutes
                    return true
                }
            }
            return false
        }

        private fun twoDigitYear(v: Int): Int = v + (if (v < 100) 1900 else 0) + (if (v < 50) 100 else 0)

        /** Consumes one token; false if the string is invalid. */
        private fun token(c: Char): Boolean {
            val start = p
            when {
                c == '+' || c == '-' -> {
                    if (hasTime && offset()) {
                        isLocal = false
                    } else {
                        p++
                        val v = digits(1, 0)
                        if (v != null) {
                            if (c == '-' && v == 0) return false // -0 is not a year
                            year = if (c == '-') -v else v
                            hasYear = true
                        }
                    }
                }
                c in '0'..'9' -> {
                    val v = digits(1, 0) ?: return false
                    if (skipChar(':')) {
                        hour = v
                        minute = digits(1, 2) ?: return false
                        if (skipChar(':')) {
                            second = digits(1, 2) ?: return false
                            fraction()
                        } else if (ch() != END && ch() != ' ') {
                            return false
                        }
                        hasTime = true
                    } else if (p - start > 2) {
                        year = v
                        hasYear = true
                    } else if (v !in 1..31) {
                        year = twoDigitYear(v)
                        hasYear = true
                    } else {
                        if (numCount == 3) return false
                        nums[numCount++] = v
                    }
                }
                monthName() -> {
                    hasMonth = true
                    skipUntil("0123456789 -/(")
                }
                hasTime && match("PM") -> {
                    if (hour != 12) hour += 12 // hours above 12 are rejected by the range check
                    return true
                }
                hasTime && match("AM") -> {
                    if (hour > 12) return false
                    if (hour == 12) hour = 0
                    return true
                }
                zoneAbbreviation() -> {
                    isLocal = false
                    return true
                }
                c == '(' -> {
                    var level = 0
                    while (ch() != END) {
                        val d = s[p++]
                        if (d == '(') level++ else if (d == ')') level--
                        if (level == 0) break
                    }
                    if (level > 0) return false
                }
                c == ')' -> return false
                else -> {
                    // Words are only allowed before any date field (e.g. a weekday name).
                    if (hasYear || hasMonth || hasTime || numCount > 0) return false
                    skipUntil(" -/(")
                }
            }
            skipSeparators()
            return true
        }

        fun parse(): Double {
            while (true) {
                val c = skipSpaces()
                if (c == END) break
                if (!token(c)) return Double.NaN
            }
            if (numCount + (if (hasYear) 1 else 0) + (if (hasMonth) 1 else 0) > 3) return Double.NaN
            when (numCount) {
                0 -> if (!hasYear) return Double.NaN
                1 -> if (hasMonth) day = nums[0] else month = nums[0]
                2 -> when {
                    hasYear -> { month = nums[0]; day = nums[1] }
                    hasMonth -> { year = twoDigitYear(nums[1]); day = nums[0] }
                    else -> { month = nums[0]; day = nums[1] }
                }
                else -> { year = twoDigitYear(nums[2]); month = nums[0]; day = nums[1] }
            }
            if (month !in 1..12 || day !in 1..31 || hour > 24 || minute > 59 || second > 59) return Double.NaN
            if (hour == 24 && (minute != 0 || second != 0 || ms != 0)) return Double.NaN
            var tv = DateTime.makeDate(
                DateTime.makeDay(year.toDouble(), (month - 1).toDouble(), day.toDouble()),
                DateTime.makeTime(hour.toDouble(), minute.toDouble(), second.toDouble(), ms.toDouble()),
            )
            if (isLocal) tv = DateTime.utc(tv)
            return DateTime.timeClip(tv - offsetMinutes * DateTime.MS_PER_MINUTE)
        }
    }
}
