package dev.mooner.neonjs.runtime

/**
 * An object that may be a host date-time (`Instant`, `ZonedDateTime`, `OffsetDateTime`, `LocalDateTime`, `LocalDate`,
 * `java.util.Date`) the policy shows. `new Date(x)` takes its instant from [foreignTimeValue] instead of parsing its
 * `toString()`, which fails for a `ZonedDateTime` (`…+09:00[Asia/Seoul]`).
 */
interface ForeignDate {
    /**
     * The time value of this host date-time, in milliseconds since the epoch, or null when this is no date-time (or the
     * policy hides it). A `LocalDateTime` is in the context's time zone; a `LocalDate` is its UTC midnight, as the date-only
     * string would be and as GraalJS has it.
     */
    fun foreignTimeValue(): Double?
}

/**
 * The time value of [t] when it is a host date-time shown as a JS Date ([JSObject.FOREIGN_DATE]: an `Instant`, a
 * `ZonedDateTime`...), clipped as a Date's is; null for anything else. What Date.prototype's getters, `toLocale*String`
 * and `toTemporalInstant` read of such a receiver (they take no other non-Date one).
 */
fun foreignDateTimeValue(t: Any?): Double? {
    if (t !is JSObject || t.special and JSObject.FOREIGN_DATE == 0 || t !is ForeignDate) return null
    val v = t.foreignTimeValue() ?: return Double.NaN
    return if (v.isNaN() || kotlin.math.abs(v) > 8.64e15) Double.NaN else v + 0.0
}
