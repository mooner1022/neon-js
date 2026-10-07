package dev.mooner.neonjs.intl

import dev.mooner.neonjs.runtime.*
import java.math.BigInteger

/** Intl.NumberFormat instance (`[[InitializedNumberFormat]]`). */
class JSIntlNumberFormat internal constructor(proto: JSObject?) : JSObject(proto) {
    @JvmField var locale: String = ""
    @JvmField internal var dataLocale: String = ""
    @JvmField var numberingSystem: String = "latn"
    @JvmField var style: String = "decimal"
    @JvmField var currency: String? = null
    @JvmField var currencyDisplay: String? = null
    @JvmField var currencySign: String? = null
    @JvmField var unit: String? = null
    @JvmField var unitDisplay: String? = null
    @JvmField internal val digits = DigitOptions()
    @JvmField var notation: String = "standard"
    @JvmField var compactDisplay: String? = null
    /** "min2", "auto", "always" or false */
    @JvmField var useGrouping: Any = "auto"
    @JvmField var signDisplay: String = "auto"
    @JvmField var boundFormat: JSObject? = null
    /** ICU LocalizedNumberFormatters (immutable), by sign for halfCeil/halfFloor; created on first use. */
    @JvmField internal val icuFormatters = arrayOfNulls<Any>(2)
}

/** Intl.NumberFormat (ECMA-402 §16) and Number/BigInt.prototype.toLocaleString. */
internal object NumberFormatBuiltins {
    private val STYLES = arrayOf("decimal", "percent", "currency", "unit")
    private val CURRENCY_DISPLAYS = arrayOf("code", "symbol", "narrowSymbol", "name")
    private val CURRENCY_SIGNS = arrayOf("standard", "accounting")
    private val UNIT_DISPLAYS = arrayOf("short", "narrow", "long")
    private val NOTATIONS = arrayOf("standard", "scientific", "engineering", "compact")
    private val COMPACT_DISPLAYS = arrayOf("short", "long")
    private val USE_GROUPING = arrayOf("min2", "auto", "always", "true", "false")
    private val SIGN_DISPLAYS = arrayOf("auto", "never", "always", "exceptZero", "negative")

    fun install(realm: Realm, intl: JSObject) {
        val proto = JSObject(realm.objectPrototype)
        realm.intrinsics["%Intl.NumberFormat.prototype%"] = proto
        val ctor = makeCtor(realm, "NumberFormat", 0, proto) { f, t, args, nt ->
            val nf = create(f.realm, nt ?: f, args.arg(0), args.arg(1))
            if (nt == null) chain(f.realm, f, t, nf) else nf
        }
        realm.intrinsics["%Intl.NumberFormat%"] = ctor
        intl.defineOwn("NumberFormat", ctor, Attr.WC)
        Locales.installSupportedLocalesOf(realm, ctor)
        proto.defineOwn(JSSymbol.toStringTag, "Intl.NumberFormat", Attr.CONFIGURABLE)
        proto.getter(realm, "format") { f, t, _, _ ->
            val nf = unwrap(f.realm, t, "format")
            nf.boundFormat ?: NativeFunction(f.realm, "", 1, { _, _, a, _ -> formatToString(f.realm, nf, IntlMV.from(a.arg(0))) }).also { nf.boundFormat = it }
        }
        proto.method(realm, "formatToParts", 1) { f, t, args, _ ->
            val nf = thisNF(t, "formatToParts")
            formatToParts(f.realm, nf, IntlMV.from(args.arg(0)))
        }
        proto.method(realm, "formatRange", 2) { f, t, args, _ ->
            val nf = thisNF(t, "formatRange")
            val (x, y) = rangeArgs(args)
            f.realm.checkedString(NumberFormatIcu.formatRange(nf, x, y).toString())
        }
        proto.method(realm, "formatRangeToParts", 2) { f, t, args, _ ->
            val nf = thisNF(t, "formatRangeToParts")
            val (x, y) = rangeArgs(args)
            val fv = NumberFormatIcu.formatRange(nf, x, y)
            NumberFormatIcu.partsArray(f.realm, NumberFormatIcu.parts(f.realm, fv, arrayOf(x, y), true, nf.style == "unit"))
        }
        proto.method(realm, "resolvedOptions", 0) { f, t, _, _ -> resolvedOptions(f.realm, unwrap(f.realm, t, "resolvedOptions")) }

        realm.numberPrototype.method(realm, "toLocaleString", 0) { f, t, args, _ ->
            val x = when (t) {
                is Double -> t
                is JSPrimitiveWrapper -> t.primitive as? Double ?: typeErr("Number.prototype.toLocaleString requires that 'this' be a Number")
                else -> typeErr("Number.prototype.toLocaleString requires that 'this' be a Number")
            }
            formatToString(f.realm, forLocaleMethod(f.realm, args.arg(0), args.arg(1)), IntlMV.of(x))
        }
        realm.bigintPrototype.method(realm, "toLocaleString", 0) { f, t, args, _ ->
            val x = when (t) {
                is BigInteger -> t
                is JSPrimitiveWrapper -> t.primitive as? BigInteger ?: typeErr("BigInt.prototype.toLocaleString requires that 'this' be a BigInt")
                else -> typeErr("BigInt.prototype.toLocaleString requires that 'this' be a BigInt")
            }
            formatToString(f.realm, forLocaleMethod(f.realm, args.arg(0), args.arg(1)), IntlMV.of(x))
        }
    }

    /** The NumberFormat for toLocaleString (cached per realm when locales and options are undefined). */
    fun forLocaleMethod(realm: Realm, locales: Any?, options: Any?): JSIntlNumberFormat {
        if (locales === Undefined && options === Undefined) {
            return IntlState.of(realm).cached("NumberFormat:default") { create(realm, null, Undefined, Undefined) }
        }
        return create(realm, null, locales, options)
    }

    private fun rangeArgs(args: Array<Any?>): Pair<IntlMV, IntlMV> {
        val start = args.arg(0)
        val end = args.arg(1)
        if (start === Undefined || end === Undefined) typeErr("start or end is undefined")
        val x = IntlMV.from(start)
        val y = IntlMV.from(end)
        if (x.isNaN || y.isNaN) rangeErr("start or end is NaN")
        return x to y
    }

    private fun thisNF(t: Any?, method: String): JSIntlNumberFormat =
        t as? JSIntlNumberFormat ?: typeErr("Method Intl.NumberFormat.prototype.$method called on incompatible receiver ${Ops.describe(t)}")

    /** UnwrapNumberFormat + RequireInternalSlot */
    private fun unwrap(realm: Realm, t: Any?, method: String): JSIntlNumberFormat {
        if (t !is JSObject) typeErr("Method Intl.NumberFormat.prototype.$method called on incompatible receiver ${Ops.describe(t)}")
        if (t is JSIntlNumberFormat) return t
        if (Ops.ordinaryHasInstance(realm.intrinsic("%Intl.NumberFormat%"), t)) {
            return thisNF(t.get(IntlState.of(realm).fallbackSymbol, t), method)
        }
        return thisNF(t, method)
    }

    /** ChainNumberFormat (legacy constructor semantics). */
    private fun chain(realm: Realm, ctor: JSObject, thisValue: Any?, nf: JSIntlNumberFormat): Any {
        if (thisValue is JSObject && Ops.ordinaryHasInstance(ctor, thisValue)) {
            thisValue.definePropertyOrThrow(IntlState.of(realm).fallbackSymbol, PropertyDescriptor.data(nf, Attr.NONE))
            return thisValue
        }
        return nf
    }

    /** The Intl.NumberFormat constructor steps (CreateNumberFormat). */
    fun create(realm: Realm, newTarget: JSObject?, locales: Any?, optionsArg: Any?): JSIntlNumberFormat {
        val nf = JSIntlNumberFormat(protoFromCtor(newTarget, "%Intl.NumberFormat.prototype%", realm))
        val requested = Locales.canonicalizeLocaleList(realm, locales)
        val options = Opt.coerceToObject(optionsArg)
        Opt.localeMatcher(options)
        val nu = Opt.string(options, "numberingSystem", null, null) as String?
        if (nu != null && !Opt.isUnicodeType(nu)) rangeErr("Invalid numberingSystem: $nu")
        val r = Locales.resolve(realm, requested, listOf("nu"), mapOf("nu" to nu)) { loc, _ -> numberingSystemData(loc) }
        nf.locale = r.locale
        nf.dataLocale = r.dataLocale
        nf.numberingSystem = r.values["nu"] ?: "latn"
        setUnitOptions(nf, options)
        val notation = Opt.str(options, "notation", NOTATIONS, "standard")
        nf.notation = notation
        val mnfdDefault: Int
        val mxfdDefault: Int
        if (nf.style == "currency" && notation == "standard") {
            val c = NumberFormatIcu.currencyDigits(nf.currency!!)
            mnfdDefault = c
            mxfdDefault = c
        } else {
            mnfdDefault = 0
            mxfdDefault = if (nf.style == "percent") 0 else 3
        }
        nf.digits.read(options, mnfdDefault, mxfdDefault, notation)
        val compactDisplay = Opt.str(options, "compactDisplay", COMPACT_DISPLAYS, "short")
        var defaultUseGrouping = "auto"
        if (notation == "compact") {
            nf.compactDisplay = compactDisplay
            defaultUseGrouping = "min2"
        }
        var useGrouping = Opt.booleanOrString(options, "useGrouping", USE_GROUPING, defaultUseGrouping)
        if (useGrouping == "true" || useGrouping == "false") useGrouping = defaultUseGrouping
        if (useGrouping == true) useGrouping = "always"
        nf.useGrouping = useGrouping
        nf.signDisplay = Opt.str(options, "signDisplay", SIGN_DISPLAYS, "auto")
        return nf
    }

    /** Locale data for "nu": the locale's default numbering system first, then every supported one. */
    fun numberingSystemData(dataLocale: String): List<String?> {
        val def = NumberFormatIcu.defaultNumberingSystem(dataLocale)
        val all = SupportedValues.numberingSystems
        val out = ArrayList<String?>(all.size + 1)
        out.add(def)
        for (n in all) if (n != def) out.add(n)
        return out
    }

    /** SetNumberFormatUnitOptions */
    private fun setUnitOptions(nf: JSIntlNumberFormat, options: JSObject) {
        val style = Opt.str(options, "style", STYLES, "decimal")
        nf.style = style
        val currency = Opt.string(options, "currency", null, null) as String?
        if (currency == null) {
            if (style == "currency") typeErr("Currency code is required with currency style.")
        } else if (!isWellFormedCurrencyCode(currency)) rangeErr("Invalid currency code : $currency")
        val currencyDisplay = Opt.str(options, "currencyDisplay", CURRENCY_DISPLAYS, "symbol")
        val currencySign = Opt.str(options, "currencySign", CURRENCY_SIGNS, "standard")
        val unit = Opt.string(options, "unit", null, null) as String?
        if (unit == null) {
            if (style == "unit") typeErr("Unit is required with unit style.")
        } else if (!isWellFormedUnitIdentifier(unit)) rangeErr("Invalid unit argument for Intl.NumberFormat() '$unit'")
        val unitDisplay = Opt.str(options, "unitDisplay", UNIT_DISPLAYS, "short")
        if (style == "currency") {
            nf.currency = currency!!.uppercase()
            nf.currencyDisplay = currencyDisplay
            nf.currencySign = currencySign
        }
        if (style == "unit") {
            nf.unit = unit
            nf.unitDisplay = unitDisplay
        }
    }

    fun isWellFormedCurrencyCode(c: String): Boolean = c.length == 3 && c.all { it in 'a'..'z' || it in 'A'..'Z' }

    fun isWellFormedUnitIdentifier(unit: String): Boolean {
        if (unit in SupportedValues.unitSet) return true
        val i = unit.indexOf("-per-")
        return i >= 0 && unit.indexOf("-per-", i + 1) < 0 &&
            unit.substring(0, i) in SupportedValues.unitSet && unit.substring(i + 5) in SupportedValues.unitSet
    }

    fun formatToString(realm: Realm, nf: JSIntlNumberFormat, x: IntlMV): String =
        realm.checkedString(NumberFormatIcu.format(nf, x).toString())

    fun formatToParts(realm: Realm, nf: JSIntlNumberFormat, x: IntlMV): JSArray {
        val fv = NumberFormatIcu.format(nf, x)
        return NumberFormatIcu.partsArray(realm, NumberFormatIcu.parts(realm, fv, arrayOf(x), false, nf.style == "unit"))
    }

    private fun resolvedOptions(realm: Realm, nf: JSIntlNumberFormat): JSObject {
        val o = plainObject(realm)
        o.createDataPropertyOrThrow("locale", nf.locale)
        o.createDataPropertyOrThrow("numberingSystem", nf.numberingSystem)
        o.createDataPropertyOrThrow("style", nf.style)
        nf.currency?.let { o.createDataPropertyOrThrow("currency", it) }
        nf.currencyDisplay?.let { o.createDataPropertyOrThrow("currencyDisplay", it) }
        nf.currencySign?.let { o.createDataPropertyOrThrow("currencySign", it) }
        nf.unit?.let { o.createDataPropertyOrThrow("unit", it) }
        nf.unitDisplay?.let { o.createDataPropertyOrThrow("unitDisplay", it) }
        nf.digits.addDigitsTo(o)
        o.createDataPropertyOrThrow("useGrouping", nf.useGrouping)
        o.createDataPropertyOrThrow("notation", nf.notation)
        nf.compactDisplay?.let { o.createDataPropertyOrThrow("compactDisplay", it) }
        o.createDataPropertyOrThrow("signDisplay", nf.signDisplay)
        o.createDataPropertyOrThrow("roundingIncrement", nf.digits.roundingIncrement.toDouble())
        o.createDataPropertyOrThrow("roundingMode", nf.digits.roundingMode)
        o.createDataPropertyOrThrow("roundingPriority", nf.digits.computedRoundingPriority)
        o.createDataPropertyOrThrow("trailingZeroDisplay", nf.digits.trailingZeroDisplay)
        return o
    }
}
