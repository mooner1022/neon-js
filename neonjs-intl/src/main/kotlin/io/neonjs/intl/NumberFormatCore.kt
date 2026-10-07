package io.neonjs.intl

import com.ibm.icu.number.FormattedNumber
import com.ibm.icu.number.IntegerWidth
import com.ibm.icu.number.LocalizedNumberFormatter
import com.ibm.icu.number.Notation
import com.ibm.icu.number.NumberFormatter
import com.ibm.icu.number.NumberRangeFormatter
import com.ibm.icu.number.Precision
import com.ibm.icu.number.Scale
import com.ibm.icu.number.UnlocalizedNumberFormatter
import com.ibm.icu.text.ConstrainedFieldPosition
import com.ibm.icu.text.FormattedValue
import com.ibm.icu.text.NumberingSystem
import com.ibm.icu.util.Currency
import com.ibm.icu.util.MeasureUnit
import com.ibm.icu.util.NoUnit
import com.ibm.icu.util.ULocale
import io.neonjs.runtime.*
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/**
 * Intl mathematical value: a finite decimal ([value]) or one of the special values. Numbers keep their Double (ICU
 * formats doubles by their shortest round-trip decimal, as the spec's tests expect).
 */
internal class IntlMV private constructor(@JvmField val kind: Int, @JvmField val value: Number?) {
    val isNaN get() = kind == NAN
    val isFinite get() = kind == FINITE || kind == NEG_ZERO

    /** -1, 0 or 1 (negative zero and negative infinity count as negative). */
    fun signum(): Int = when (kind) {
        NEG_ZERO, NEG_INF -> -1
        POS_INF -> 1
        NAN -> 0
        else -> when (val v = value!!) {
            is Double -> if (v < 0) -1 else if (v > 0) 1 else 0
            is BigInteger -> v.signum()
            else -> (v as BigDecimal).signum()
        }
    }

    /** The value handed to ICU. */
    fun toIcu(): Number = when (kind) {
        NAN -> Double.NaN
        POS_INF -> Double.POSITIVE_INFINITY
        NEG_INF -> Double.NEGATIVE_INFINITY
        NEG_ZERO -> -0.0
        else -> value!!
    }

    /** Exact decimal value (finite values only). */
    fun toBigDecimal(): BigDecimal = when (val v = value) {
        is Double -> BigDecimal(NumberConvShortest.toShortest(v))
        is BigInteger -> BigDecimal(v)
        is BigDecimal -> v
        else -> BigDecimal.ZERO
    }

    companion object {
        const val FINITE = 0
        const val NEG_ZERO = 1
        const val POS_INF = 2
        const val NEG_INF = 3
        const val NAN = 4

        val NaN = IntlMV(NAN, null)
        val PosInf = IntlMV(POS_INF, null)
        val NegInf = IntlMV(NEG_INF, null)
        val NegZero = IntlMV(NEG_ZERO, null)

        fun of(d: Double): IntlMV = when {
            d.isNaN() -> NaN
            d == Double.POSITIVE_INFINITY -> PosInf
            d == Double.NEGATIVE_INFINITY -> NegInf
            d == 0.0 && 1.0 / d < 0 -> NegZero
            else -> IntlMV(FINITE, d)
        }

        fun of(b: BigInteger): IntlMV = IntlMV(FINITE, b)
        fun of(b: BigDecimal): IntlMV = IntlMV(FINITE, b)

        /** ToIntlMathematicalValue(value) */
        fun from(value: Any?): IntlMV {
            if (value is Double) return of(value)
            val prim = Ops.toPrimitive(value, Ops.HINT_NUMBER)
            if (prim is BigInteger) return of(prim)
            if (prim is CharSequence) return parse(prim.toString())
            val x = Ops.toNumber(prim)
            return of(x)
        }

        private fun isJsWhitespace(c: Char): Boolean = when (c) {
            '\t', '\n', '\u000B', '\u000C', '\r', ' ', ' ', ' ', ' ', ' ', ' ', ' ', '　', '﻿' -> true
            else -> c in ' '..' '
        }

        /** StringIntlMV of a StringNumericLiteral, followed by RoundMVResult range checks. */
        fun parse(s0: String): IntlMV {
            var a = 0
            var b = s0.length
            while (a < b && isJsWhitespace(s0[a])) a++
            while (b > a && isJsWhitespace(s0[b - 1])) b--
            val s = s0.substring(a, b)
            if (s.isEmpty()) return of(BigDecimal.ZERO)
            if (s.length > 2 && s[0] == '0') {
                val radix = when (s[1]) {
                    'x', 'X' -> 16
                    'o', 'O' -> 8
                    'b', 'B' -> 2
                    else -> 0
                }
                if (radix != 0) {
                    val digits = s.substring(2)
                    if (digits.any { Character.digit(it, radix) < 0 || it.code > 127 }) return NaN
                    return checkRange(BigDecimal(BigInteger(digits, radix)), false)
                }
            }
            var i = 0
            var negative = false
            if (s[0] == '+' || s[0] == '-') {
                negative = s[0] == '-'
                i = 1
            }
            val body = s.substring(i)
            if (body == "Infinity") return if (negative) NegInf else PosInf
            if (!isDecimalLiteral(body)) return NaN
            val bd = try {
                BigDecimal(body)
            } catch (e: RuntimeException) {
                // the literal is valid, so its exponent is out of BigDecimal's range: the value is 0 or infinite
                if (body.all { it == '0' || it == '.' || it == 'e' || it == 'E' || it == '+' || it == '-' || it in '0'..'9' } &&
                    body.substringBefore('e').substringBefore('E').all { it == '0' || it == '.' }) {
                    return if (negative) NegZero else of(BigDecimal.ZERO)
                }
                return if (body.contains("e-") || body.contains("E-")) (if (negative) NegZero else of(BigDecimal.ZERO)) else if (negative) NegInf else PosInf
            }
            return checkRange(if (negative) bd.negate() else bd, negative)
        }

        /** StrUnsignedDecimalLiteral (digits with optional '.' and exponent; no separators). */
        private fun isDecimalLiteral(s: String): Boolean {
            var i = 0
            val n = s.length
            var digits = 0
            while (i < n && s[i] in '0'..'9') { i++; digits++ }
            if (i < n && s[i] == '.') {
                i++
                while (i < n && s[i] in '0'..'9') { i++; digits++ }
            }
            if (digits == 0) return false
            if (i < n && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < n && (s[i] == '+' || s[i] == '-')) i++
                val start = i
                while (i < n && s[i] in '0'..'9') i++
                if (i == start) return false
            }
            return i == n
        }

        /** RoundMVResult(abs(mv)): values rounding to 0 or Infinity as a Number become the special values. */
        private fun checkRange(v: BigDecimal, negative: Boolean): IntlMV {
            if (v.signum() == 0) return if (negative) NegZero else of(BigDecimal.ZERO)
            val abs = v.abs()
            // precision - scale - 1 is the decimal exponent
            val exp = abs.precision().toLong() - abs.scale().toLong() - 1
            if (exp > 400 || (exp > 300 && abs.toDouble().isInfinite())) return if (v.signum() < 0) NegInf else PosInf
            if (exp < -400 || (exp < -300 && abs.toDouble() == 0.0)) return if (v.signum() < 0) NegZero else of(BigDecimal.ZERO)
            return of(v)
        }
    }
}

/** Shortest round-trip decimal string of a double, via the engine's Number::toString. */
internal object NumberConvShortest {
    fun toShortest(d: Double): String = NumberConv.toString(d)
}

/** The digit options of SetNumberFormatDigitOptions (shared by NumberFormat and PluralRules). */
internal class DigitOptions {
    @JvmField var minimumIntegerDigits = 1
    @JvmField var minimumFractionDigits: Int? = null
    @JvmField var maximumFractionDigits: Int? = null
    @JvmField var minimumSignificantDigits: Int? = null
    @JvmField var maximumSignificantDigits: Int? = null
    /** fractionDigits, significantDigits, morePrecision or lessPrecision */
    @JvmField var roundingType = "fractionDigits"
    @JvmField var computedRoundingPriority = "auto"
    @JvmField var roundingIncrement = 1
    @JvmField var roundingMode = "halfExpand"
    @JvmField var trailingZeroDisplay = "auto"

    companion object {
        private val INCREMENTS = intArrayOf(1, 2, 5, 10, 20, 25, 50, 100, 200, 250, 500, 1000, 2000, 2500, 5000)
        private val ROUNDING_MODES = arrayOf("ceil", "floor", "expand", "trunc", "halfCeil", "halfFloor", "halfExpand", "halfTrunc", "halfEven")
        private val PRIORITIES = arrayOf("auto", "morePrecision", "lessPrecision")
        private val TRAILING = arrayOf("auto", "stripIfInteger")
    }

    /** SetNumberFormatDigitOptions(intlObj, options, mnfdDefault, mxfdDefault, notation) */
    fun read(options: JSObject, mnfdDefault: Int, mxfdDefault0: Int, notation: String) {
        var mxfdDefault = mxfdDefault0
        val mnid = Opt.number(options, "minimumIntegerDigits", 1, 21, 1)!!
        val mnfd = options.get("minimumFractionDigits", options)
        val mxfd = options.get("maximumFractionDigits", options)
        val mnsd = options.get("minimumSignificantDigits", options)
        val mxsd = options.get("maximumSignificantDigits", options)
        minimumIntegerDigits = mnid
        val increment = Opt.number(options, "roundingIncrement", 1, 5000, 1)!!
        if (increment !in INCREMENTS) rangeErr("roundingIncrement value is out of range.")
        val mode = Opt.str(options, "roundingMode", ROUNDING_MODES, "halfExpand")
        val priority = Opt.str(options, "roundingPriority", PRIORITIES, "auto")
        val trailing = Opt.str(options, "trailingZeroDisplay", TRAILING, "auto")
        if (increment != 1) mxfdDefault = mnfdDefault
        roundingIncrement = increment
        roundingMode = mode
        trailingZeroDisplay = trailing
        val hasSd = mnsd !== Undefined || mxsd !== Undefined
        val hasFd = mnfd !== Undefined || mxfd !== Undefined
        var needSd = true
        var needFd = true
        if (priority == "auto") {
            needSd = hasSd
            if (needSd || (!hasFd && notation == "compact")) needFd = false
        }
        if (needSd) {
            if (hasSd) {
                val min = Opt.defaultNumber(mnsd, 1, 21, 1)!!
                minimumSignificantDigits = min
                maximumSignificantDigits = Opt.defaultNumber(mxsd, min, 21, 21)
            } else {
                minimumSignificantDigits = 1
                maximumSignificantDigits = 21
            }
        }
        if (needFd) readFractionDigits(hasFd, mnfd, mxfd, mnfdDefault, mxfdDefault)
        resolveRoundingType(needSd, needFd, priority, hasSd)
        if (increment != 1) {
            if (roundingType != "fractionDigits") typeErr("roundingIncrement requires fraction digits rounding")
            if (maximumFractionDigits != minimumFractionDigits) rangeErr("maximumFractionDigits and minimumFractionDigits must be equal with roundingIncrement")
        }
    }

    private fun readFractionDigits(hasFd: Boolean, mnfd0: Any?, mxfd0: Any?, mnfdDefault: Int, mxfdDefault: Int) {
        if (hasFd) {
            var mnfd = Opt.defaultNumber(mnfd0, 0, 100, null)
            var mxfd = Opt.defaultNumber(mxfd0, 0, 100, null)
            if (mnfd == null) mnfd = minOf(mnfdDefault, mxfd!!)
            else if (mxfd == null) mxfd = maxOf(mxfdDefault, mnfd)
            else if (mnfd > mxfd) rangeErr("minimumFractionDigits is greater than maximumFractionDigits")
            minimumFractionDigits = mnfd
            maximumFractionDigits = mxfd
        } else {
            minimumFractionDigits = mnfdDefault
            maximumFractionDigits = mxfdDefault
        }
    }

    private fun resolveRoundingType(needSd: Boolean, needFd: Boolean, priority: String, hasSd: Boolean) {
        if (!needSd && !needFd) {
            minimumFractionDigits = 0
            maximumFractionDigits = 0
            minimumSignificantDigits = 1
            maximumSignificantDigits = 2
            roundingType = "morePrecision"
            computedRoundingPriority = "morePrecision"
        } else if (priority == "morePrecision" || priority == "lessPrecision") {
            roundingType = priority
            computedRoundingPriority = priority
        } else if (hasSd) {
            roundingType = "significantDigits"
            computedRoundingPriority = "auto"
        } else {
            roundingType = "fractionDigits"
            computedRoundingPriority = "auto"
        }
    }

    /** Adds the digit options to [obj] in resolvedOptions order (minimumIntegerDigits .. maximumSignificantDigits). */
    fun addDigitsTo(obj: JSObject) {
        obj.createDataPropertyOrThrow("minimumIntegerDigits", minimumIntegerDigits.toDouble())
        minimumFractionDigits?.let { obj.createDataPropertyOrThrow("minimumFractionDigits", it.toDouble()) }
        maximumFractionDigits?.let { obj.createDataPropertyOrThrow("maximumFractionDigits", it.toDouble()) }
        minimumSignificantDigits?.let { obj.createDataPropertyOrThrow("minimumSignificantDigits", it.toDouble()) }
        maximumSignificantDigits?.let { obj.createDataPropertyOrThrow("maximumSignificantDigits", it.toDouble()) }
    }

    /** Applies precision, integer width and rounding mode for values of sign [signum]. */
    fun applyTo(f0: UnlocalizedNumberFormatter, signum: Int): UnlocalizedNumberFormatter {
        var f = f0.integerWidth(IntegerWidth.zeroFillTo(minimumIntegerDigits))
        var p: Precision = when (roundingType) {
            "significantDigits" -> Precision.minMaxSignificantDigits(minimumSignificantDigits!!, maximumSignificantDigits!!)
            "morePrecision", "lessPrecision" -> Precision.minMaxFraction(minimumFractionDigits!!, maximumFractionDigits!!)
                .withSignificantDigits(minimumSignificantDigits!!, maximumSignificantDigits!!,
                    if (roundingType == "morePrecision") NumberFormatter.RoundingPriority.RELAXED else NumberFormatter.RoundingPriority.STRICT)
            else -> if (roundingIncrement != 1) {
                Precision.increment(BigDecimal.valueOf(roundingIncrement.toLong()).scaleByPowerOfTen(-maximumFractionDigits!!))
            } else Precision.minMaxFraction(minimumFractionDigits!!, maximumFractionDigits!!)
        }
        if (trailingZeroDisplay == "stripIfInteger") p = p.trailingZeroDisplay(NumberFormatter.TrailingZeroDisplay.HIDE_IF_WHOLE)
        f = f.precision(p)
        return f.roundingMode(icuRoundingMode(signum))
    }

    fun icuRoundingMode(signum: Int): RoundingMode = when (roundingMode) {
        "ceil" -> RoundingMode.CEILING
        "floor" -> RoundingMode.FLOOR
        "expand" -> RoundingMode.UP
        "trunc" -> RoundingMode.DOWN
        "halfCeil" -> if (signum < 0) RoundingMode.HALF_DOWN else RoundingMode.HALF_UP
        "halfFloor" -> if (signum < 0) RoundingMode.HALF_UP else RoundingMode.HALF_DOWN
        "halfTrunc" -> RoundingMode.HALF_DOWN
        "halfEven" -> RoundingMode.HALF_EVEN
        else -> RoundingMode.HALF_UP
    }

    /** Whether the rounding mode depends on the sign of the value (halfCeil / halfFloor). */
    val signDependent: Boolean get() = roundingMode == "halfCeil" || roundingMode == "halfFloor"
}

/** One part of a formatted value: type, text and (for ranges) source. */
internal class FmtPart(@JvmField val type: String, @JvmField val value: String, @JvmField val source: String?)

/** Builds ICU number formatters for Intl.NumberFormat objects and converts ICU output to parts. */
internal object NumberFormatIcu {
    /** The default numbering system of an (available) locale. */
    fun defaultNumberingSystem(dataLocale: String): String = try {
        val ns = NumberingSystem.getInstance(ULocale.forLanguageTag(dataLocale))
        if (SupportedValues.isSupportedNumberingSystem(ns.name)) ns.name else "latn"
    } catch (e: Exception) {
        "latn"
    }

    fun icuLocale(dataLocale: String, nu: String): ULocale = ULocale.forLanguageTag("$dataLocale-u-nu-$nu")

    /** CurrencyDigits(currency) */
    fun currencyDigits(code: String): Int = try {
        Currency.getInstance(code).defaultFractionDigits
    } catch (e: Exception) {
        2
    }

    fun unitOf(unit: String): MeasureUnit {
        val i = unit.indexOf("-per-")
        return if (i < 0) MeasureUnit.forIdentifier(unit)
        else MeasureUnit.forIdentifier(unit.substring(0, i)).product(MeasureUnit.forIdentifier(unit.substring(i + 5)).reciprocal())
    }

    /** The unlocalized formatter for [nf] and values of sign [signum] (sign matters only for halfCeil/halfFloor). */
    fun settings(nf: JSIntlNumberFormat, signum: Int): UnlocalizedNumberFormatter {
        var f = NumberFormatter.with()
        when (nf.style) {
            "percent" -> f = f.unit(NoUnit.PERCENT).scale(Scale.powerOfTen(2))
            "currency" -> {
                f = f.unit(Currency.getInstance(nf.currency!!)).unitWidth(when (nf.currencyDisplay) {
                    "code" -> NumberFormatter.UnitWidth.ISO_CODE
                    "narrowSymbol" -> NumberFormatter.UnitWidth.NARROW
                    "name" -> NumberFormatter.UnitWidth.FULL_NAME
                    else -> NumberFormatter.UnitWidth.SHORT
                })
            }
            "unit" -> {
                f = f.unit(unitOf(nf.unit!!)).unitWidth(when (nf.unitDisplay) {
                    "narrow" -> NumberFormatter.UnitWidth.NARROW
                    "long" -> NumberFormatter.UnitWidth.FULL_NAME
                    else -> NumberFormatter.UnitWidth.SHORT
                })
            }
        }
        f = f.notation(when (nf.notation) {
            "scientific" -> Notation.scientific()
            "engineering" -> Notation.engineering()
            "compact" -> if (nf.compactDisplay == "long") Notation.compactLong() else Notation.compactShort()
            else -> Notation.simple()
        })
        f = nf.digits.applyTo(f, signum)
        f = f.grouping(when (nf.useGrouping) {
            false -> NumberFormatter.GroupingStrategy.OFF
            "min2" -> NumberFormatter.GroupingStrategy.MIN2
            "always" -> NumberFormatter.GroupingStrategy.ON_ALIGNED
            else -> NumberFormatter.GroupingStrategy.AUTO
        })
        f = f.sign(signDisplay(nf.signDisplay, nf.style == "currency" && nf.currencySign == "accounting"))
        return f
    }

    private fun signDisplay(sd: String, accounting: Boolean): NumberFormatter.SignDisplay = when (sd) {
        "never" -> NumberFormatter.SignDisplay.NEVER
        "always" -> if (accounting) NumberFormatter.SignDisplay.ACCOUNTING_ALWAYS else NumberFormatter.SignDisplay.ALWAYS
        "exceptZero" -> if (accounting) NumberFormatter.SignDisplay.ACCOUNTING_EXCEPT_ZERO else NumberFormatter.SignDisplay.EXCEPT_ZERO
        "negative" -> if (accounting) NumberFormatter.SignDisplay.ACCOUNTING_NEGATIVE else NumberFormatter.SignDisplay.NEGATIVE
        else -> if (accounting) NumberFormatter.SignDisplay.ACCOUNTING else NumberFormatter.SignDisplay.AUTO
    }

    fun formatter(nf: JSIntlNumberFormat, signum: Int): LocalizedNumberFormatter {
        val dependent = nf.digits.signDependent
        val idx = if (dependent && signum < 0) 1 else 0
        nf.icuFormatters[idx]?.let { return it as LocalizedNumberFormatter }
        val f = icuCall { settings(nf, signum).locale(icuLocale(nf.dataLocale, nf.numberingSystem)) }
        nf.icuFormatters[idx] = f
        return f
    }

    fun format(nf: JSIntlNumberFormat, x: IntlMV): FormattedNumber = icuCall { formatter(nf, x.signum()).format(x.toIcu()) }

    fun formatRange(nf: JSIntlNumberFormat, x: IntlMV, y: IntlMV): FormattedValue = icuCall {
        val first = settings(nf, x.signum())
        val sameMode = !nf.digits.signDependent || (x.signum() < 0) == (y.signum() < 0)
        val base = NumberRangeFormatter.withLocale(icuLocale(nf.dataLocale, nf.numberingSystem))
        val withNumbers = if (sameMode) base.numberFormatterBoth(first) else base.numberFormatterFirst(first).numberFormatterSecond(settings(nf, y.signum()))
        withNumbers
            .identityFallback(NumberRangeFormatter.RangeIdentityFallback.APPROXIMATELY)
            .formatRange(x.toIcu(), y.toIcu())
    }

    /** Runs an ICU call, converting ICU exceptions (whose messages and classes must not reach JS) to RangeErrors. */
    inline fun <T> icuCall(block: () -> T): T = try {
        block()
    } catch (e: JSException) {
        throw e
    } catch (e: TerminationException) {
        throw e
    } catch (e: RuntimeException) {
        throw JSException.rangeError("Unable to format the number")
    }

    // ------------------------------------------------------------------ parts

    /** Maps an ICU number field to its ECMA-402 part type ([text] decides plus/minus). */
    private fun partType(field: java.text.Format.Field, text: String, x: IntlMV?): String = when (field) {
        com.ibm.icu.text.NumberFormat.Field.INTEGER -> when {
            x == null -> "integer"
            x.isNaN -> "nan"
            !x.isFinite -> "infinity"
            else -> "integer"
        }
        com.ibm.icu.text.NumberFormat.Field.FRACTION -> "fraction"
        com.ibm.icu.text.NumberFormat.Field.DECIMAL_SEPARATOR -> "decimal"
        com.ibm.icu.text.NumberFormat.Field.GROUPING_SEPARATOR -> "group"
        com.ibm.icu.text.NumberFormat.Field.CURRENCY -> "currency"
        com.ibm.icu.text.NumberFormat.Field.PERCENT -> "percentSign"
        com.ibm.icu.text.NumberFormat.Field.PERMILLE -> "unknown"
        com.ibm.icu.text.NumberFormat.Field.SIGN -> if (text.contains('+') || text.contains('＋')) "plusSign" else "minusSign"
        com.ibm.icu.text.NumberFormat.Field.EXPONENT_SYMBOL -> "exponentSeparator"
        com.ibm.icu.text.NumberFormat.Field.EXPONENT_SIGN -> if (text.contains('+')) "exponentPlusSign" else "exponentMinusSign"
        com.ibm.icu.text.NumberFormat.Field.EXPONENT -> "exponentInteger"
        com.ibm.icu.text.NumberFormat.Field.COMPACT -> "compact"
        com.ibm.icu.text.NumberFormat.Field.MEASURE_UNIT -> "unit"
        com.ibm.icu.text.NumberFormat.Field.APPROXIMATELY_SIGN -> "approximatelySign"
        else -> "literal"
    }

    /**
     * Converts ICU field positions to non-overlapping parts. Inner fields (e.g. a group inside an integer) win over
     * outer ones; for ranges [values] gives the start/end values and the range spans give each part's source.
     */
    fun parts(realm: Realm, fv: FormattedValue, values: Array<IntlMV>, range: Boolean, unitStyle: Boolean = false): List<FmtPart> {
        val s = fv.toString()
        val n = s.length
        realm.agent.checkStringLength(n.toLong())
        val types = arrayOfNulls<String>(n)
        val depth = IntArray(n) { -1 }
        val source = IntArray(n) { -1 }
        val cfp = ConstrainedFieldPosition()
        val spans = ArrayList<IntArray>()
        val fields = ArrayList<Pair<java.text.Format.Field, IntArray>>()
        var count = 0L
        while (fv.nextPosition(cfp)) {
            realm.tick(count++)
            val field = cfp.field
            if (field is NumberRangeFormatter.SpanField) {
                spans.add(intArrayOf(cfp.start, cfp.limit, (cfp.fieldValue as Number).toInt()))
            } else fields.add(field to intArrayOf(cfp.start, cfp.limit))
        }
        for (sp in spans) for (i in sp[0] until sp[1]) source[i] = sp[2]
        for ((field, pos) in fields) {
            val len = pos[1] - pos[0]
            val text = s.substring(pos[0], pos[1])
            val side = if (range && pos[0] < n && source[pos[0]] == 1) 1 else 0
            var type = partType(field, text, values.getOrNull(side) ?: values.firstOrNull())
            if (unitStyle && type == "percentSign") type = "unit"
            for (i in pos[0] until pos[1]) {
                // smaller spans are nested inside larger ones
                if (types[i] == null || depth[i] > len) {
                    types[i] = type
                    depth[i] = len
                }
            }
        }
        val out = ArrayList<FmtPart>()
        var i = 0
        while (i < n) {
            val t = types[i] ?: "literal"
            val src = source[i]
            var j = i + 1
            while (j < n && (types[j] ?: "literal") == t && source[j] == src) j++
            val srcName = if (!range) null else when (src) {
                0 -> "startRange"
                1 -> "endRange"
                else -> "shared"
            }
            out.add(FmtPart(t, s.substring(i, j), srcName))
            i = j
        }
        return out
    }

    fun partsArray(realm: Realm, parts: List<FmtPart>, unit: String? = null): JSArray {
        val list = ArrayList<Any?>(parts.size)
        for ((k, p) in parts.withIndex()) {
            realm.tick(k.toLong())
            val o = partObject(realm, p.type, p.value)
            if (p.source != null) o.createDataPropertyOrThrow("source", p.source)
            if (unit != null) o.createDataPropertyOrThrow("unit", unit)
            list.add(o)
        }
        return jsArray(realm, list)
    }
}
