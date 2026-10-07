package dev.mooner.neonjs.regexp

import java.util.concurrent.ConcurrentHashMap

/** Entry points of the regular expression engine: compilation (with a shared cache) and early-error validation. */
object RegExpEngine {
    private class Key(val pattern: String, val flags: Int) {
        override fun equals(other: Any?) = other is Key && other.flags == flags && other.pattern == pattern
        override fun hashCode() = pattern.hashCode() * 31 + flags
    }

    private const val CACHE_LIMIT = 1024
    private val cache = ConcurrentHashMap<Key, RegExpProgram>()

    /** Compiles [pattern] with flag bits [flags] (see [RegExpProgram.parseFlags]); throws [RegExpSyntaxError]. */
    @JvmStatic
    fun compile(pattern: String, flags: Int): RegExpProgram {
        if (pattern.length > 4096) return RegExpCompiler.compile(pattern, flags)
        val key = Key(pattern, flags)
        cache[key]?.let { return it }
        val p = RegExpCompiler.compile(pattern, flags)
        if (cache.size >= CACHE_LIMIT) cache.clear()
        cache[key] = p
        return p
    }

    /** Compiles a pattern with a flags string; throws [RegExpSyntaxError] for invalid flags or pattern. */
    @JvmStatic
    fun compile(pattern: String, flags: String): RegExpProgram {
        val f = RegExpProgram.parseFlags(flags)
        if (f < 0) throw RegExpSyntaxError("invalid regular expression flags")
        return compile(pattern, f)
    }

    /** Returns an error message if the literal `/pattern/flags` is not a valid regular expression, else null. */
    @JvmStatic
    fun validate(pattern: String, flags: String): String? {
        val f = RegExpProgram.parseFlags(flags)
        if (f < 0) return "Invalid regular expression flags"
        return try {
            compile(pattern, f)
            null
        } catch (e: RegExpSyntaxError) {
            e.message
        }
    }
}
