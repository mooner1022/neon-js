package dev.mooner.neonjs.parser

/** Early-error validation of regular expression literals. Returns an error message or null. */
object RegExpSyntaxValidator {
    /** Optional override of the default validator (the regular expression compiler). */
    @JvmStatic
    var validator: ((String, String) -> String?)? = null

    fun validate(pattern: String, flags: String): String? {
        val v = validator
        if (v != null) return v(pattern, flags)
        return dev.mooner.neonjs.regexp.RegExpEngine.validate(pattern, flags)
    }
}
