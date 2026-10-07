package io.neonjs.runtime

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.nextUp
import kotlin.math.round

/** Number <-> String conversions following ECMA-262 (Number::toString, StringToNumber, toFixed, ...). */
object NumberConv {
    private val smallInts = Array(1024) { it.toString() }

    /** Shortest round-trip decimal digits and exponent: value = 0.d1d2...dk * 10^n. */
    class Decimal(val digits: String, val n: Int)

    @JvmStatic
    fun shortest(v: Double): Decimal {
        // JDK 19+ Double.toString produces the shortest uniquely-identifying decimal (Ryu-like).
        val s = v.toString()
        val ePos = s.indexOf('E')
        val mant = if (ePos >= 0) s.substring(0, ePos) else s
        val exp = if (ePos >= 0) s.substring(ePos + 1).toInt() else 0
        val dot = mant.indexOf('.')
        val intPart = if (dot >= 0) mant.substring(0, dot) else mant
        val fracPart = if (dot >= 0) mant.substring(dot + 1) else ""
        var digits = intPart + fracPart
        var pointPos = intPart.length + exp
        // strip leading zeros
        var lead = 0
        while (lead < digits.length - 1 && digits[lead] == '0') lead++
        digits = digits.substring(lead)
        pointPos -= lead
        // strip trailing zeros
        var end = digits.length
        while (end > 1 && digits[end - 1] == '0') end--
        digits = digits.substring(0, end)
        if (digits.length == 2) {
            // Double.toString prints at least two significant digits, choosing the closest such decimal even when a
            // single digit already round-trips (4.9E-324 for Number.MIN_VALUE); JS wants the shortest ("5e-324").
            oneDigit(v, digits, pointPos)?.let { return it }
        }
        return Decimal(digits, pointPos)
    }

    private fun oneDigit(v: Double, digits: String, pointPos: Int): Decimal? {
        val exact = BigDecimal(v)
        var best: Decimal? = null
        var bestDist: BigDecimal? = null
        val d0 = digits[0] - '0'
        for (c in intArrayOf(d0, d0 + 1)) {
            val (dg, pp) = if (c == 10) "1" to pointPos + 1 else c.toString() to pointPos
            if (dg == "0") continue
            val cand = BigDecimal(BigInteger(dg), -(pp - 1))
            if (cand.toDouble() != v) continue
            val dist = cand.subtract(exact).abs()
            if (bestDist == null || dist < bestDist) {
                best = Decimal(dg, pp)
                bestDist = dist
            }
        }
        return best
    }

    @JvmStatic
    fun toString(v: Double): String {
        if (v != v) return "NaN"
        if (v == 0.0) return "0"
        if (v >= 0 && v < 1024) {
            val i = v.toInt()
            if (i.toDouble() == v) return smallInts[i]
        }
        if (v < 0) return "-" + toString(-v)
        if (v == Double.POSITIVE_INFINITY) return "Infinity"
        if (v < 2147483647.0) {
            val i = v.toInt()
            if (i.toDouble() == v) return i.toString()
        }
        val d = shortest(v)
        val k = d.digits.length
        val n = d.n
        val sb = StringBuilder()
        if (n in k..21) {
            sb.append(d.digits)
            repeat(n - k) { sb.append('0') }
        } else if (n in 1..21) {
            sb.append(d.digits, 0, n).append('.').append(d.digits, n, k)
        } else if (n > -6 && n <= 0) {
            sb.append("0.")
            repeat(-n) { sb.append('0') }
            sb.append(d.digits)
        } else {
            val e = n - 1
            sb.append(d.digits[0])
            if (k > 1) sb.append('.').append(d.digits, 1, k)
            sb.append('e').append(if (e >= 0) '+' else '-').append(abs(e))
        }
        return sb.toString()
    }

    private const val CHARS = "0123456789abcdefghijklmnopqrstuvwxyz"

    /** Number::toString with radix 2..36 (V8's DoubleToRadixCString algorithm). */
    @JvmStatic
    fun toStringRadix(value0: Double, radix: Int): String {
        if (radix == 10) return toString(value0)
        if (value0 != value0) return "NaN"
        if (value0 == Double.POSITIVE_INFINITY) return "Infinity"
        if (value0 == Double.NEGATIVE_INFINITY) return "-Infinity"
        if (value0 == 0.0) return "0"
        // integers below 2^53 are exact in any radix: no need for the digit-generation buffer
        if (value0 == round(value0) && abs(value0) < 9.007199254740992E15) return value0.toLong().toString(radix)
        val size = 2200
        val buffer = CharArray(size)
        var integerCursor = size / 2
        var fractionCursor = integerCursor
        val negative = value0 < 0
        val value = if (negative) -value0 else value0
        var integer = floor(value)
        var fraction = value - integer
        var delta = 0.5 * (value.nextUp() - value)
        delta = maxOf(0.0.nextUp(), delta)
        if (fraction >= delta) {
            buffer[fractionCursor++] = '.'
            do {
                fraction *= radix
                delta *= radix
                val digit = fraction.toInt()
                buffer[fractionCursor++] = CHARS[digit]
                fraction -= digit
                if (fraction > 0.5 || (fraction == 0.5 && (digit and 1) != 0)) {
                    if (fraction + delta > 1) {
                        while (true) {
                            fractionCursor--
                            if (fractionCursor == size / 2) {
                                integer += 1
                                break
                            }
                            val c = buffer[fractionCursor]
                            val dg = if (c > '9') (c - 'a' + 10) else (c - '0')
                            if (dg + 1 < radix) {
                                buffer[fractionCursor++] = CHARS[dg + 1]
                                break
                            }
                        }
                        break
                    }
                }
            } while (fraction >= delta)
        }
        while (Math.getExponent(integer / radix) > 52) {
            integer /= radix
            buffer[--integerCursor] = '0'
        }
        do {
            val remainder = integer % radix
            buffer[--integerCursor] = CHARS[remainder.toInt()]
            integer = (integer - remainder) / radix
        } while (integer > 0)
        if (negative) buffer[--integerCursor] = '-'
        return String(buffer, integerCursor, fractionCursor - integerCursor)
    }

    // ------------------------------------------------------------------ StringToNumber

    @JvmStatic
    fun isJSWhitespace(c: Char): Boolean = when (c) {
        '\u0009', '\u000B', '\u000C', ' ', ' ', '﻿', '\n', '\r', ' ', ' ' -> true
        else -> c > '\u007F' && c != '᠎' && Character.getType(c) == Character.SPACE_SEPARATOR.toInt()
    }

    @JvmStatic
    fun trim(s: CharSequence): String {
        var a = 0
        var b = s.length
        while (a < b && isJSWhitespace(s[a])) a++
        while (b > a && isJSWhitespace(s[b - 1])) b--
        return s.subSequence(a, b).toString()
    }

    /** Value of an ASCII digit/letter [c] in [radix], or -1. Unlike Character.digit, rejects non-ASCII digits. */
    @JvmStatic
    fun digitVal(c: Char, radix: Int): Int {
        val v = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'z' -> c - 'a' + 10
            in 'A'..'Z' -> c - 'A' + 10
            else -> return -1
        }
        return if (v < radix) v else -1
    }

    @JvmStatic
    fun stringToNumber(str: CharSequence): Double {
        val s = trim(str)
        val n = s.length
        if (n == 0) return 0.0
        val c0 = s[0]
        if (n > 2 && c0 == '0') {
            val radix = when (s[1]) { 'x', 'X' -> 16; 'o', 'O' -> 8; 'b', 'B' -> 2; else -> 0 }
            if (radix != 0) return parseRadixDigits(s, radix)
        }
        var i = 0
        if (c0 == '+' || c0 == '-') i++
        if (s.regionMatches(i, "Infinity", 0, 8) && n == i + 8) {
            return if (c0 == '-') Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        }
        // StrUnsignedDecimalLiteral validation
        var digits = 0
        while (i < n && s[i] in '0'..'9') { i++; digits++ }
        if (i < n && s[i] == '.') {
            i++
            while (i < n && s[i] in '0'..'9') { i++; digits++ }
        }
        if (digits == 0) return Double.NaN
        if (i < n && (s[i] == 'e' || s[i] == 'E')) {
            i++
            if (i < n && (s[i] == '+' || s[i] == '-')) i++
            var ed = 0
            while (i < n && s[i] in '0'..'9') { i++; ed++ }
            if (ed == 0) return Double.NaN
        }
        if (i != n) return Double.NaN
        return try {
            java.lang.Double.parseDouble(s)
        } catch (_: NumberFormatException) {
            Double.NaN
        }
    }

    /** The digits of [s] after its two-character prefix (0x, 0o, 0b) in [radix]. */
    private fun parseRadixDigits(s: String, radix: Int): Double {
        val from = 2
        if (from >= s.length) return Double.NaN
        for (i in from until s.length) {
            if (digitVal(s[i], radix) < 0) return Double.NaN
        }
        val len = s.length - from
        if ((radix == 16 && len <= 13) || (radix == 8 && len <= 17) || (radix == 2 && len <= 53)) {
            return java.lang.Long.parseLong(s.substring(from), radix).toDouble()
        }
        return BigInteger(s.substring(from), radix).toDouble()
    }

    // ------------------------------------------------------------------ toFixed / toExponential / toPrecision

    @JvmStatic
    fun toFixed(x: Double, f: Int): String {
        if (x != x) return "NaN"
        if (abs(x) >= 1e21) return toString(x)
        val bd = BigDecimal(x).setScale(f, RoundingMode.HALF_UP)
        val s = bd.abs().toPlainString()
        val neg = x < 0
        if (bd.signum() == 0 && x < 0) {
            // (-0.0000001).toFixed(2) === "-0.00"
            return "-$s"
        }
        return if (neg) "-$s" else s
    }

    @JvmStatic
    fun toExponential(x: Double, fractionDigits: Int?): String {
        if (x != x) return "NaN"
        val neg = x < 0
        val v = abs(x)
        if (v == Double.POSITIVE_INFINITY) return if (neg) "-Infinity" else "Infinity"
        val digits: String
        val e: Int
        if (v == 0.0) {
            val f = fractionDigits ?: 0
            digits = "0".repeat(f + 1)
            e = 0
        } else if (fractionDigits == null) {
            val d = shortest(v)
            digits = d.digits
            e = d.n - 1
        } else {
            val bd = BigDecimal(v)
            val r = bd.round(java.math.MathContext(fractionDigits + 1, RoundingMode.HALF_UP))
            val unscaled = r.unscaledValue().toString()
            var dg = unscaled
            // exponent: value = unscaled * 10^-scale ; first digit position
            e = unscaled.length - 1 - r.scale()
            if (dg.length < fractionDigits + 1) dg += "0".repeat(fractionDigits + 1 - dg.length)
            digits = dg.substring(0, fractionDigits + 1)
        }
        val sb = StringBuilder()
        if (neg) sb.append('-')
        sb.append(digits[0])
        if (digits.length > 1) sb.append('.').append(digits, 1, digits.length)
        sb.append('e').append(if (e >= 0) '+' else '-').append(abs(e))
        return sb.toString()
    }

    @JvmStatic
    fun toPrecision(x: Double, p: Int): String {
        if (x != x) return "NaN"
        val neg = x < 0
        val v = abs(x)
        if (v == Double.POSITIVE_INFINITY) return if (neg) "-Infinity" else "Infinity"
        val digits: String
        val e: Int
        if (v == 0.0) {
            digits = "0".repeat(p)
            e = 0
        } else {
            val r = BigDecimal(v).round(java.math.MathContext(p, RoundingMode.HALF_UP))
            var dg = r.unscaledValue().toString()
            e = dg.length - 1 - r.scale()
            if (dg.length < p) dg += "0".repeat(p - dg.length)
            digits = dg.substring(0, p)
        }
        val sb = StringBuilder()
        if (neg) sb.append('-')
        if (e < -6 || e >= p) {
            sb.append(digits[0])
            if (p > 1) sb.append('.').append(digits, 1, p)
            sb.append('e').append(if (e >= 0) '+' else '-').append(abs(e))
            return sb.toString()
        }
        if (e == p - 1) return sb.append(digits).toString()
        if (e >= 0) {
            sb.append(digits, 0, e + 1).append('.').append(digits, e + 1, p)
            return sb.toString()
        }
        sb.append("0.")
        repeat(-(e + 1)) { sb.append('0') }
        sb.append(digits)
        return sb.toString()
    }
}
