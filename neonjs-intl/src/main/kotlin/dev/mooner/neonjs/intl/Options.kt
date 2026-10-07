package dev.mooner.neonjs.intl

import dev.mooner.neonjs.runtime.*
import kotlin.math.floor

/** Option reading abstract operations of ECMA-402 §9.2 (GetOption, GetNumberOption, ...). */
internal object Opt {
    /** Marker for GetOption's ~required~ default. */
    val REQUIRED = Any()

    /** CoerceOptionsToObject: undefined becomes an empty null-prototype object. */
    fun coerceToObject(options: Any?): JSObject = if (options === Undefined) JSObject(null) else Ops.toObject(options)

    /** GetOptionsObject: undefined becomes an empty null-prototype object; other non-objects throw a TypeError. */
    fun getOptionsObject(options: Any?): JSObject = when (options) {
        Undefined -> JSObject(null)
        is JSObject -> options
        else -> typeErr("Options must be an object")
    }

    /** GetOption(options, property, string, values, default): returns a String or [default]. */
    fun string(options: JSObject, property: String, values: Array<String>?, default: Any?): Any? {
        val v = options.get(property, options)
        if (v === Undefined) {
            if (default === REQUIRED) rangeErr("Option $property is required")
            return default
        }
        val s = Ops.toString(v)
        if (values != null && s !in values) rangeErr("Value $s out of range for option $property")
        return s
    }

    /** GetOption returning a non-null String (the default is used for undefined). */
    fun str(options: JSObject, property: String, values: Array<String>?, default: String): String =
        string(options, property, values, default) as String

    /** GetOption(options, property, boolean, empty, default): returns a Boolean or [default]. */
    fun boolean(options: JSObject, property: String, default: Any?): Any? {
        val v = options.get(property, options)
        if (v === Undefined) return default
        return Ops.toBoolean(v)
    }

    /** DefaultNumberOption(value, minimum, maximum, fallback) */
    fun defaultNumber(value: Any?, minimum: Int, maximum: Int, fallback: Int?): Int? {
        if (value === Undefined) return fallback
        val d = Ops.toNumber(value)
        if (d.isNaN() || d < minimum || d > maximum) rangeErr("Value ${Ops.toDisplayString(value)} out of range")
        return floor(d).toInt()
    }

    /** GetNumberOption(options, property, minimum, maximum, fallback) */
    fun number(options: JSObject, property: String, minimum: Int, maximum: Int, fallback: Int?): Int? =
        defaultNumber(options.get(property, options), minimum, maximum, fallback)

    /** GetBooleanOrStringNumberFormatOption(options, property, stringValues, fallback) */
    fun booleanOrString(options: JSObject, property: String, stringValues: Array<String>, fallback: Any): Any {
        val v = options.get(property, options)
        if (v === Undefined) return fallback
        if (v == true) return true
        if (!Ops.toBoolean(v)) return false
        val s = Ops.toString(v)
        if (s !in stringValues) rangeErr("Value $s out of range for option $property")
        return s
    }

    /** Reads `localeMatcher` (shared by every constructor and supportedLocalesOf). */
    fun localeMatcher(options: JSObject): String = str(options, "localeMatcher", MATCHERS, "best fit")

    private val MATCHERS = arrayOf("lookup", "best fit")

    /** Validates the type sequence of a Unicode extension value given as an option (`(3*8alphanum) *("-" (3*8alphanum))`). */
    fun isUnicodeType(s: String): Boolean {
        if (s.isEmpty()) return false
        var start = 0
        while (true) {
            var end = s.indexOf('-', start)
            if (end < 0) end = s.length
            val n = end - start
            if (n !in 3..8) return false
            for (i in start until end) if (!LanguageTag.isAlnum(s[i])) return false
            if (end == s.length) return true
            start = end + 1
        }
    }
}
