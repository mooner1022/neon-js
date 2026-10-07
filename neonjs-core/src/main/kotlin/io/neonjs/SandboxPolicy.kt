package io.neonjs

/**
 * Resource and capability limits applied to every context of an engine.
 *
 * Limits are enforced cooperatively by the engine at loop back-edges and calls, so a script cannot block the host
 * indefinitely. Host code always runs unrestricted.
 */
class SandboxPolicy private constructor(b: Builder) {
    /** Wall-clock limit for a single top-level evaluation (eval / call from host), in milliseconds. 0 = unlimited. */
    val maxExecutionMillis: Long = b.maxExecutionMillis
    /** Approximate instruction budget per top-level evaluation. 0 = unlimited. */
    val maxStatements: Long = b.maxStatements
    /** Maximum JS call depth. */
    val maxCallDepth: Int = b.maxCallDepth
    /** Maximum length of a JS string. */
    val maxStringLength: Int = b.maxStringLength
    /** Maximum bytes the evaluating thread may allocate during one top-level evaluation (HotSpot only). 0 = unlimited. */
    val maxAllocatedBytes: Long = b.maxAllocatedBytes
    /** Whether `eval` and the Function/GeneratorFunction/AsyncFunction constructors may compile code at runtime. */
    val allowEval: Boolean = b.allowEval
    /** Whether the `Java` global (Java.type etc.) is installed. Requires [HostAccess] permitting class lookup. */
    val exposeJavaGlobal: Boolean = b.exposeJavaGlobal
    /** Makes Math.random deterministic (seeded) and Date.now return [fixedTimeMillis] when set. */
    val randomSeed: Long? = b.randomSeed
    val fixedTimeMillis: Long? = b.fixedTimeMillis
    /** Default locale for Intl (BCP 47); null = the host's ("en-US" when [Builder.deterministic] is used). */
    val defaultLocale: String? = b.defaultLocale ?: if (b.randomSeed != null) "en-US" else null
    /** Local time zone seen by Date and Temporal.Now; null = the host's (UTC when [Builder.deterministic] is used). */
    val timeZone: java.time.ZoneId? = b.timeZone ?: if (b.randomSeed != null) java.time.ZoneOffset.UTC else null
    /** Most timers (`setTimeout` / `setInterval`, see NeonEngine.Builder.webGlobals) a context may have pending. */
    val maxTimers: Int = b.maxTimers

    class Builder {
        var maxExecutionMillis = 0L
        var maxStatements = 0L
        var maxCallDepth = 3000
        var maxStringLength = (1 shl 30) - 25
        var maxAllocatedBytes = 0L
        var allowEval = true
        var exposeJavaGlobal = false
        var randomSeed: Long? = null
        var fixedTimeMillis: Long? = null
        var timeZone: java.time.ZoneId? = null
        var defaultLocale: String? = null
        var maxTimers = 10_000

        fun maxExecutionTime(millis: Long) = apply { maxExecutionMillis = millis }
        fun maxStatements(n: Long) = apply { maxStatements = n }
        fun maxCallDepth(n: Int) = apply { maxCallDepth = n }
        fun maxStringLength(n: Int) = apply { maxStringLength = n }
        fun maxAllocatedBytes(n: Long) = apply { maxAllocatedBytes = n }
        fun allowEval(b: Boolean) = apply { allowEval = b }
        fun exposeJavaGlobal(b: Boolean) = apply { exposeJavaGlobal = b }
        fun deterministic(seed: Long, fixedTime: Long?) = apply { randomSeed = seed; fixedTimeMillis = fixedTime }
        /** Local time zone for scripts (e.g. "UTC", "Asia/Seoul", "+09:00"); hides the host's zone. */
        fun timeZone(id: String) = apply { timeZone = java.time.ZoneId.of(id) }
        /** Default locale for Intl (e.g. "en-US", "ko-KR"); hides the host's locale. */
        fun defaultLocale(tag: String) = apply { defaultLocale = tag }
        /** Caps the pending timers of a context (RangeError beyond); default 10,000. */
        fun maxTimers(n: Int) = apply { require(n >= 0) { "maxTimers must be >= 0" }; maxTimers = n }
        fun build() = SandboxPolicy(this)
    }

    companion object {
        /** No limits (trusted code). */
        @JvmField val UNRESTRICTED: SandboxPolicy = Builder().build()

        /** Reasonable defaults for untrusted code: 5s, depth 1000, no eval, no Java global. */
        @JvmField val STRICT: SandboxPolicy = Builder().maxExecutionTime(5000).maxCallDepth(1000).allowEval(false).build()

        @JvmStatic fun builder() = Builder()
    }
}
