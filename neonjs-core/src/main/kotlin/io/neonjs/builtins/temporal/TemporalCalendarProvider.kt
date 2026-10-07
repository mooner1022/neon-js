package io.neonjs.builtins.temporal

import java.util.ServiceLoader

/**
 * Service hook for the implementation-defined parts of Temporal that need locale data: non-ISO calendars and the
 * primary identifiers of IANA time zone links. Found with [ServiceLoader] (`META-INF/services`); without a provider
 * Temporal supports only the `iso8601` calendar and keeps link names as their own primary identifiers.
 *
 * Implementations are shared by every agent and thread of the JVM, so they must be stateless or thread-safe.
 */
interface TemporalCalendarProvider {
    /** Canonical (lowercase) calendar types this provider implements, not including "iso8601". */
    fun calendarIds(): Collection<String>

    /** The year-structure source for [calendarId] (one of [calendarIds]), or null. */
    fun calendarSource(calendarId: String): TemporalCalendarSource?

    /**
     * The IANA primary identifier of the available named time zone [id] (given in its canonical case), or null to
     * treat [id] as primary. The "UTC" special case of ECMA-402 is applied by the caller.
     */
    fun primaryTimeZoneId(id: String): String? = null
}

/**
 * Year-level structure of one calendar in terms of arithmetic years (the [[Year]] of a Calendar Date Record).
 * Everything else (eras, month codes in fields, arithmetic, reference dates) is computed by Temporal from this.
 *
 * Calls are pure, may happen on any thread, and are only made for |year| <= [NonIsoCalendar.MAX_YEAR]. Results are
 * cached by the caller, so each call may be moderately expensive but must run in bounded time.
 */
interface TemporalCalendarSource {
    /** Month boundaries, month codes and leap flag of arithmetic year [year]. */
    fun yearInfo(year: Int): CalendarYearInfo

    /**
     * Exact number of months in all years before [year] counted from a fixed, source-defined origin, so that
     * `monthsBeforeYear(y + 1) - monthsBeforeYear(y)` equals the month count of year y for every y.
     */
    fun monthsBeforeYear(year: Int): Long

    /** An arithmetic year within a few years of the one containing [epochDay] (days since 1970-01-01). */
    fun estimateYear(epochDay: Long): Int
}

/** Immutable structure of one calendar year. */
class CalendarYearInfo(
    /** Epoch day of the first day of each month, followed by the first day of the next year (size = months + 1). */
    @JvmField val monthStarts: LongArray,
    /** Month code of each month, encoded as `number * 2 + (1 if leap month)`; e.g. "M05L" = 11. */
    @JvmField val monthCodes: IntArray,
    /** InLeapYear of the Calendar Date Record. */
    @JvmField val inLeapYear: Boolean,
) {
    val monthCount: Int get() = monthCodes.size
    val start: Long get() = monthStarts[0]
    val end: Long get() = monthStarts[monthCodes.size]

    init {
        require(monthStarts.size == monthCodes.size + 1 && monthCodes.isNotEmpty()) { "malformed calendar year" }
        for (i in monthCodes.indices) require(monthStarts[i + 1] > monthStarts[i]) { "malformed calendar year" }
    }
}

internal object TemporalProviders {
    /** The installed provider, or null when none is on the class path. */
    @JvmStatic
    val provider: TemporalCalendarProvider? by lazy {
        val loaders = listOfNotNull(Thread.currentThread().contextClassLoader, TemporalCalendarProvider::class.java.classLoader).distinct()
        loaders.firstNotNullOfOrNull { l ->
            try {
                ServiceLoader.load(TemporalCalendarProvider::class.java, l).firstOrNull()
            } catch (_: java.util.ServiceConfigurationError) {
                null
            }
        }
    }
}
