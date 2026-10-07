package dev.mooner.neonjs.intl

import com.ibm.icu.impl.ICUData
import com.ibm.icu.impl.ICUResourceBundle
import com.ibm.icu.text.ConstrainedFieldPosition
import com.ibm.icu.text.ListFormatter
import com.ibm.icu.util.ULocale
import com.ibm.icu.util.UResourceBundle
import dev.mooner.neonjs.runtime.*
import java.math.BigDecimal
import java.math.BigInteger

/** A Duration Record: the ten fields in [DurationFormatBuiltins.UNITS] order (integral Numbers, never -0). */
internal class DurationRecord(@JvmField val values: DoubleArray) {
    /** DurationSign */
    fun sign(): Int {
        for (v in values) {
            if (v < 0) return -1
            if (v > 0) return 1
        }
        return 0
    }

    companion object {
        /** Get order of ToDurationRecord (alphabetical), as indexes into the UNITS order. */
        private val READ_ORDER = intArrayOf(3, 4, 8, 7, 5, 1, 9, 6, 2, 0)
        private val TWO_POW_32 = BigDecimal(BigInteger.ONE.shiftLeft(32))
        private val TWO_POW_53 = BigDecimal(BigInteger.ONE.shiftLeft(53))

        /** ToDurationRecord(input) */
        fun from(input: Any?): DurationRecord {
            if (input is dev.mooner.neonjs.builtins.temporal.JSTemporalDuration) {
                return DurationRecord(doubleArrayOf(input.years, input.months, input.weeks, input.days, input.hours, input.minutes,
                    input.seconds, input.milliseconds, input.microseconds, input.nanoseconds).also { a -> for (k in a.indices) a[k] += 0.0 })
            }
            if (input !is JSObject) {
                if (input is CharSequence) return parseIso(input.toString())
                typeErr("Invalid duration: ${Ops.toDisplayString(input)}")
            }
            val values = DoubleArray(10)
            var any = false
            for (i in READ_ORDER) {
                val v = input.get(DurationFormatBuiltins.UNITS[i], input)
                if (v !== Undefined) {
                    any = true
                    values[i] = toIntegerIfIntegral(v)
                }
            }
            if (!any) typeErr("Invalid duration: no duration fields")
            if (!isValid(values)) rangeErr("Invalid duration")
            return DurationRecord(values)
        }

        private fun toIntegerIfIntegral(v: Any?): Double {
            val d = Ops.toNumber(v)
            if (!Ops.isIntegral(d)) rangeErr("Duration field is not an integral number: ${Ops.toDisplayString(v)}")
            return d + 0.0
        }

        /** IsValidDuration */
        fun isValid(v: DoubleArray): Boolean {
            var sign = 0
            for (x in v) {
                if (!x.isFinite()) return false
                if (x < 0) { if (sign > 0) return false; sign = -1 }
                if (x > 0) { if (sign < 0) return false; sign = 1 }
            }
            for (i in 0..2) if (BigDecimal(v[i]).abs() >= TWO_POW_32) return false
            val secs = BigDecimal(v[3]).multiply(BigDecimal(86400)) + BigDecimal(v[4]).multiply(BigDecimal(3600)) +
                BigDecimal(v[5]).multiply(BigDecimal(60)) + BigDecimal(v[6]) + BigDecimal(v[7]).movePointLeft(3) +
                BigDecimal(v[8]).movePointLeft(6) + BigDecimal(v[9]).movePointLeft(9)
            return secs.abs() < TWO_POW_53
        }

        /** ParseTemporalDurationString + CreateDurationRecord (RangeError for anything else). */
        fun parseIso(s: String): DurationRecord {
            if (s.length > 256) rangeErr("Invalid duration string")
            val r = IsoDurationParser(s).parse() ?: rangeErr("Invalid duration string: $s")
            if (!isValid(r)) rangeErr("Invalid duration")
            return DurationRecord(r)
        }
    }
}

/** ISO 8601 duration strings as accepted by Temporal (sign, P, Y/M/W/D, T, H/M/S, one trailing fraction). */
private class IsoDurationParser(val s: String) {
    var i = 0

    private fun digits(): String? {
        val start = i
        while (i < s.length && s[i] in '0'..'9') i++
        return if (i > start) s.substring(start, i) else null
    }

    private fun fraction(): String? {
        if (i < s.length && (s[i] == '.' || s[i] == ',')) {
            i++
            val start = i
            while (i < s.length && s[i] in '0'..'9') i++
            val n = i - start
            if (n !in 1..9) return INVALID
            return s.substring(start, i)
        }
        return null
    }

    private fun designator(set: String): Char? {
        if (i < s.length && s[i].uppercaseChar() in set) return s[i++].uppercaseChar()
        return null
    }

    fun parse(): DoubleArray? {
        var negative = false
        if (i < s.length && (s[i] == '+' || s[i] == '-' || s[i] == '−')) {
            negative = s[i] != '+'
            i++
        }
        if (designator("P") == null) return null
        val out = arrayOfNulls<BigDecimal>(10)
        var any = false
        var lastDate = -1
        // date part
        while (i < s.length && s[i] in '0'..'9') {
            val d = digits()!!
            val idx = when (designator("YMWD")) {
                'Y' -> 0
                'M' -> 1
                'W' -> 2
                'D' -> 3
                else -> return null
            }
            if (idx <= lastDate) return null
            lastDate = idx
            out[idx] = BigDecimal(d)
            any = true
        }
        var fracUnit = -1
        var frac: String? = null
        if (designator("T") != null) {
            var lastTime = -1
            var anyTime = false
            while (i < s.length && s[i] in '0'..'9') {
                if (frac != null) return null
                val d = digits()!!
                val f = fraction()
                if (f === INVALID) return null
                val idx = when (designator("HMS")) {
                    'H' -> 4
                    'M' -> 5
                    'S' -> 6
                    else -> return null
                }
                if (idx <= lastTime) return null
                lastTime = idx
                out[idx] = BigDecimal(d)
                if (f != null) { frac = f; fracUnit = idx }
                anyTime = true
            }
            if (!anyTime) return null
            any = true
        }
        if (!any || i != s.length) return null
        return toRecord(out, frac, fracUnit, negative)
    }

    private fun toRecord(out: Array<BigDecimal?>, frac: String?, fracUnit: Int, negative: Boolean): DoubleArray {
        // spread the fraction of the last time unit over the smaller units (in nanoseconds)
        val unitNs = when (fracUnit) {
            4 -> BigDecimal("3600000000000")
            5 -> BigDecimal("60000000000")
            else -> BigDecimal("1000000000")
        }
        val values = Array(10) { out[it] ?: BigDecimal.ZERO }
        if (frac != null) {
            var ns = BigDecimal("0.$frac").multiply(unitNs).toBigInteger()
            val sizes = longArrayOf(60_000_000_000L, 1_000_000_000L, 1_000_000L, 1_000L, 1L)
            for ((k, u) in (5..9).withIndex()) {
                if (u <= fracUnit) continue
                val q = ns.divide(BigInteger.valueOf(sizes[k]))
                values[u] = values[u].add(BigDecimal(q))
                ns = ns.subtract(q.multiply(BigInteger.valueOf(sizes[k])))
            }
        }
        return DoubleArray(10) { val d = values[it].toDouble(); if (negative && d != 0.0) -d else d }
    }

    companion object {
        val INVALID = String(charArrayOf('!'))
    }
}

/** One part of a formatted duration. */
internal class DurationPart(@JvmField val type: String, @JvmField val value: String, @JvmField val unit: String?)

/** PartitionDurationFormatPattern and its ICU-backed helpers. */
internal object DurationFormatPattern {
    private fun digitalPatterns(dataLocale: String): Array<String> = try {
        val b = UResourceBundle.getBundleInstance(ICUData.ICU_UNIT_BASE_NAME, ULocale.forLanguageTag(dataLocale)) as ICUResourceBundle
        arrayOf(b.getStringWithFallback("durationUnits/hm"), b.getStringWithFallback("durationUnits/ms"))
    } catch (_: Exception) {
        arrayOf("h:mm", "m:ss")
    }

    /** [hoursMinutesSeparator, minutesSecondsSeparator, twoDigitHours ("true"/"false")] */
    private fun separators(df: JSIntlDurationFormat): Array<String> {
        df.separators?.let { return it }
        val (hm, ms) = digitalPatterns(df.dataLocale)
        val hmSep = hm.trim('h', 'H', 'm').ifEmpty { ":" }
        val msSep = ms.trim('m', 's').ifEmpty { ":" }
        val r = arrayOf(hmSep, msSep, if (hm.startsWith("hh") || hm.startsWith("HH")) "true" else "false")
        df.separators = r
        return r
    }

    fun twoDigitHours(df: JSIntlDurationFormat): Boolean = separators(df)[2] == "true"

    private fun listFormatter(df: JSIntlDurationFormat): ListFormatter {
        df.listFormatter?.let { return it as ListFormatter }
        val width = when (df.style) {
            "long" -> ListFormatter.Width.WIDE
            "narrow" -> ListFormatter.Width.NARROW
            else -> ListFormatter.Width.SHORT
        }
        val lf = NumberFormatIcu.icuCall { ListFormatter.getInstance(ULocale.forLanguageTag(df.dataLocale), ListFormatter.Type.UNITS, width) }
        df.listFormatter = lf
        return lf
    }

    /** The exact value of [unit] plus all smaller sub-second units (seconds + ms/1e3 + ...), or the plain Number. */
    private fun fractionalValue(d: DurationRecord, unit: Int): IntlMV {
        var zero = true
        for (k in unit + 1..9) if (d.values[k] != 0.0) zero = false
        if (zero) return IntlMV.of(d.values[unit])
        var sum = BigDecimal(d.values[unit])
        for (k in unit + 1..9) sum = sum.add(BigDecimal(d.values[k]).movePointLeft(3 * (k - unit)))
        if (sum.signum() == 0) return if (d.sign() < 0) IntlMV.NegZero else IntlMV.of(BigDecimal.ZERO)
        return IntlMV.of(sum)
    }

    private fun numberFormat(realm: Realm, df: JSIntlDurationFormat, unit: Int, style: String, signNever: Boolean, fractional: Boolean): JSIntlNumberFormat {
        val key = "$unit|$style|$signNever|$fractional"
        df.numberFormats[key]?.let { return it }
        val opts = JSObject(null)
        opts.createDataPropertyOrThrow("numberingSystem", df.numberingSystem)
        if (signNever) opts.createDataPropertyOrThrow("signDisplay", "never")
        if (fractional) {
            val fd = df.fractionalDigits
            opts.createDataPropertyOrThrow("maximumFractionDigits", (fd ?: 9).toDouble())
            opts.createDataPropertyOrThrow("minimumFractionDigits", (fd ?: 0).toDouble())
            opts.createDataPropertyOrThrow("roundingMode", "trunc")
        }
        if (style == "2-digit") opts.createDataPropertyOrThrow("minimumIntegerDigits", 2.0)
        if (style != "numeric" && style != "2-digit") {
            opts.createDataPropertyOrThrow("style", "unit")
            opts.createDataPropertyOrThrow("unit", DurationFormatBuiltins.NF_UNITS[unit])
            opts.createDataPropertyOrThrow("unitDisplay", style)
        } else {
            opts.createDataPropertyOrThrow("useGrouping", false)
        }
        val nf = NumberFormatBuiltins.create(realm, null, df.locale, opts)
        df.numberFormats[key] = nf
        return nf
    }

    private fun isZero(x: IntlMV): Boolean = x.kind == IntlMV.NEG_ZERO || (x.kind == IntlMV.FINITE && x.signum() == 0)

    /** PartitionDurationFormatPattern */
    fun partition(realm: Realm, df: JSIntlDurationFormat, d: DurationRecord): List<DurationPart> {
        val groups = ArrayList<ArrayList<DurationPart>>()
        var lastNumeric = -1
        var signPending = true
        val negative = d.sign() < 0
        for (i in 0 until 10) {
            var value = IntlMV.of(d.values[i])
            val style = df.styles[i]!!
            val display = df.displays[i]!!
            var done = false
            if (i in 6..8 && df.styles[i + 1] == "fractional") {
                value = fractionalValue(d, i)
                done = true
            }
            var displayRequired = false
            if (i == 5 && lastNumeric == 4) {
                displayRequired = df.displays[6] == "always" || (6..9).any { d.values[it] != 0.0 }
            }
            if (!isZero(value) || display != "auto" || displayRequired) {
                var signNever = false
                if (signPending) {
                    signPending = false
                    if (isZero(value) && negative) value = IntlMV.NegZero
                } else signNever = true
                val nf = numberFormat(realm, df, i, style, signNever, done)
                val numeric = style == "numeric" || style == "2-digit"
                val list: ArrayList<DurationPart>
                if (lastNumeric >= 0 && numeric) {
                    list = groups[groups.size - 1]
                    val seps = separators(df)
                    list.add(DurationPart("literal", if (lastNumeric == 4) seps[0] else seps[1], null))
                } else {
                    list = ArrayList()
                    groups.add(list)
                }
                val fv = NumberFormatIcu.format(nf, value)
                for (p in NumberFormatIcu.parts(realm, fv, arrayOf(value), false)) {
                    list.add(DurationPart(p.type, p.value, DurationFormatBuiltins.NF_UNITS[i]))
                }
                if (numeric) lastNumeric = i
            }
            if (done) break
        }
        return listFormat(realm, df, groups)
    }

    /** ListFormatParts */
    private fun listFormat(realm: Realm, df: JSIntlDurationFormat, groups: List<List<DurationPart>>): List<DurationPart> {
        val strings = groups.map { g -> g.joinToString("") { it.value } }
        val fl = NumberFormatIcu.icuCall { listFormatter(df).formatToValue(strings) }
        val text = fl.toString()
        realm.agent.checkStringLength(text.length.toLong())
        val out = ArrayList<DurationPart>()
        val cfp = ConstrainedFieldPosition()
        cfp.constrainField(ListFormatter.Field.ELEMENT)
        var pos = 0
        var g = 0
        while (fl.nextPosition(cfp)) {
            if (cfp.start > pos) out.add(DurationPart("literal", text.substring(pos, cfp.start), null))
            if (g < groups.size) out.addAll(groups[g++])
            pos = cfp.limit
        }
        if (pos < text.length) out.add(DurationPart("literal", text.substring(pos), null))
        return out
    }
}
