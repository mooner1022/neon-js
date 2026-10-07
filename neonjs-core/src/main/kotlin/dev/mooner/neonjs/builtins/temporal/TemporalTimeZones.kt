package dev.mooner.neonjs.builtins.temporal

import dev.mooner.neonjs.runtime.*
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.zone.ZoneRules
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * An available time zone: an offset time zone, or a named IANA time zone backed by the JDK's tz database
 * ([rules] is null only for "UTC" itself, which needs no lookup).
 */
class TimeZone(
    @JvmField val id: String,
    @JvmField val isOffset: Boolean,
    @JvmField val offsetMinutes: Int,
    @JvmField val rules: ZoneRules? = null,
) {
    val offsetNs: Long get() = offsetMinutes * 60_000_000_000L

    /** Fixed offset in nanoseconds when the zone never changes its offset, else null. */
    @JvmField internal val fixedOffsetNs: Long? =
        if (rules == null) offsetNs else if (rules.isFixedOffset) rules.getOffset(Instant.EPOCH).totalSeconds * 1_000_000_000L else null

    /** Primary identifier (computed lazily; offset zones are their own primary identifier). */
    @Volatile internal var primary: String? = null

    companion object {
        @JvmField val UTC = TimeZone("UTC", false, 0)

        fun ofOffsetMinutes(minutes: Int): TimeZone = TimeZone(TFormat.formatOffsetMinutes(minutes, true), true, minutes)
    }
}

internal object TZ {
    private const val NS_PER_S = 1_000_000_000L
    private val BI_NS_PER_S: BigInteger = BigInteger.valueOf(NS_PER_S)

    /** IANA names the JDK's tz database lacks, with the zone whose rules they use (and primary identifier). */
    private val EXTRA_LINKS = mapOf(
        "EST" to "America/Panama", "MST" to "America/Phoenix", "HST" to "Pacific/Honolulu", "ROC" to "Asia/Taipei",
        "GMT+0" to "Etc/GMT", "GMT-0" to "Etc/GMT",
    )

    /** Links whose primary identifier is "UTC" (ECMA-402: the targets Etc/UTC, Etc/GMT and GMT become "UTC"). */
    private val UTC_FAMILY = setOf(
        "Etc/UTC", "Etc/GMT", "GMT", "Etc/UCT", "Etc/Universal", "Etc/Zulu", "UCT", "Universal", "Zulu", "Etc/GMT+0",
        "Etc/GMT-0", "Etc/GMT0", "Etc/Greenwich", "GMT+0", "GMT-0", "GMT0", "Greenwich",
    )

    /** ASCII-lowercase name -> canonical spelling, for every available named time zone identifier. */
    private val NAMES: Map<String, String> by lazy {
        val m = HashMap<String, String>(1024)
        for (id in ZoneId.getAvailableZoneIds()) {
            // SystemV/* are JDK-only legacy names, not IANA identifiers
            if (id.startsWith("SystemV/")) continue
            m[asciiLower(id)] = id
        }
        for (id in EXTRA_LINKS.keys) m[asciiLower(id)] = id
        m["utc"] = "UTC"
        m
    }

    private val zoneCache = ConcurrentHashMap<String, TimeZone>()

    private fun asciiLower(s: String): String {
        var i = 0
        while (i < s.length && s[i] !in 'A'..'Z') i++
        if (i == s.length) return s
        val sb = StringBuilder(s)
        for (k in i until s.length) {
            val c = s[k]
            if (c in 'A'..'Z') sb.setCharAt(k, c + 32)
        }
        return sb.toString()
    }

    /** GetAvailableNamedTimeZoneIdentifier: the zone for an ASCII-case-insensitive match of [name], or null. */
    fun namedZone(name: String): TimeZone? {
        if (asciiEqualsIgnoreCase(name, "utc")) return TimeZone.UTC
        if (name.length > 64) return null
        val canonical = NAMES[asciiLower(name)] ?: return null
        return zoneCache.computeIfAbsent(canonical) { id ->
            val rules = ZoneId.of(EXTRA_LINKS[id] ?: id).rules
            TimeZone(id, false, 0, rules)
        }
    }

    /** ParseTemporalTimeZoneString: returns a name (String) or offset minutes (Int). */
    fun parseTimeZoneString(s: String): Any {
        TemporalParser.parseTimeZoneIdentifier(s)?.let { return it }
        val r = TemporalParser.parseIsoDateTime(s, TemporalParser.ALL_GOALS)
        val ann = r.tzAnnotation
        if (ann != null) return TemporalParser.parseTimeZoneIdentifier(ann)!!
        if (r.z) return "UTC"
        val off = r.offset
        if (off != null) return TemporalParser.parseTimeZoneIdentifier(off) ?: tRangeErr("invalid time zone offset $off")
        tRangeErr("string does not contain a time zone: ${s.take(60)}")
    }

    /** ToTemporalTimeZoneIdentifier */
    fun toTimeZone(v: Any?): TimeZone {
        if (v is JSTemporalZonedDateTime) return v.timeZone
        if (v !is CharSequence) tTypeErr("time zone must be a string")
        val parsed = parseTimeZoneString(v.toString())
        if (parsed is Int) return TimeZone.ofOffsetMinutes(parsed)
        return namedZone(parsed as String) ?: tRangeErr("unknown time zone ${parsed.take(60)}")
    }

    /** Time zone from a (constructor) identifier string via ParseTimeZoneIdentifier. */
    fun fromIdentifier(s: String): TimeZone {
        val parsed = TemporalParser.parseTimeZoneIdentifier(s) ?: tRangeErr("invalid time zone identifier ${s.take(60)}")
        if (parsed is Int) return TimeZone.ofOffsetMinutes(parsed)
        return namedZone(parsed as String) ?: tRangeErr("unknown time zone ${parsed.take(60)}")
    }

    private fun instantOf(epochNs: BigInteger): Instant {
        val qr = epochNs.divideAndRemainder(BI_NS_PER_S)
        var sec = qr[0].toLong()
        var nano = qr[1].toLong()
        if (nano < 0) { nano += NS_PER_S; sec -= 1 }
        return Instant.ofEpochSecond(sec, nano)
    }

    /** GetOffsetNanosecondsFor */
    fun offsetNs(tz: TimeZone, epochNs: BigInteger): Long {
        tz.fixedOffsetNs?.let { return it }
        return tz.rules!!.getOffset(instantOf(epochNs)).totalSeconds * NS_PER_S
    }

    /** GetISODateTimeFor */
    fun isoDateTimeFor(tz: TimeZone, epochNs: BigInteger): IsoDateTime {
        val off = offsetNs(tz, epochNs)
        return TM.isoPartsFromEpoch(if (off == 0L) epochNs else epochNs.add(BigInteger.valueOf(off)))
    }

    private fun localDateTime(dt: IsoDateTime): LocalDateTime {
        val d = dt.date
        // dates this far out are outside the representable range of epoch nanoseconds in any case
        if (abs(d.year) > 400_000) tRangeErr("date-time outside of supported range")
        val t = dt.time
        return LocalDateTime.of(d.year, d.month, d.day, t.hour, t.minute, t.second, t.subSecondNanos())
    }

    /** GetPossibleEpochNanoseconds */
    fun possibleEpochNs(tz: TimeZone, dt: IsoDateTime): List<BigInteger> {
        val fixed = tz.fixedOffsetNs
        val result: List<BigInteger>
        if (tz.isOffset) {
            val balanced = TM.balanceDateTime(dt.date, dt.time, -tz.offsetNs)
            TM.checkIsoDaysRange(balanced.date)
            result = listOf(TM.utcEpochNs(balanced))
        } else if (fixed != null) {
            val utc = TM.utcEpochNs(dt)
            result = listOf(if (fixed == 0L) utc else utc.subtract(BigInteger.valueOf(fixed)))
        } else {
            val utc = TM.utcEpochNs(dt)
            val offsets = tz.rules!!.getValidOffsets(localDateTime(dt))
            result = when (offsets.size) {
                0 -> emptyList()
                1 -> listOf(utc.subtract(BigInteger.valueOf(offsets[0].totalSeconds * NS_PER_S)))
                else -> offsets.map { utc.subtract(BigInteger.valueOf(it.totalSeconds * NS_PER_S)) }.sorted()
            }
        }
        for (ns in result) if (!TM.isValidEpochNs(ns)) tRangeErr("date-time outside of supported range")
        return result
    }

    /** GetEpochNanosecondsFor */
    fun epochNsFor(tz: TimeZone, dt: IsoDateTime, disambiguation: Disambiguation): BigInteger {
        val possible = possibleEpochNs(tz, dt)
        return disambiguate(possible, tz, dt, disambiguation)
    }

    /** DisambiguatePossibleEpochNanoseconds */
    fun disambiguate(possible: List<BigInteger>, tz: TimeZone, dt: IsoDateTime, disambiguation: Disambiguation): BigInteger {
        val n = possible.size
        if (n == 1) return possible[0]
        if (n != 0) {
            return when (disambiguation) {
                Disambiguation.EARLIER, Disambiguation.COMPATIBLE -> possible[0]
                Disambiguation.LATER -> possible[n - 1]
                Disambiguation.REJECT -> tRangeErr("ambiguous date-time")
            }
        }
        if (disambiguation == Disambiguation.REJECT) tRangeErr("nonexistent date-time")
        // n = 0: a wall-clock time skipped by a positive offset transition
        val transition = tz.rules?.getTransition(localDateTime(dt)) ?: tRangeErr("nonexistent date-time")
        val nanoseconds = (transition.offsetAfter.totalSeconds - transition.offsetBefore.totalSeconds) * NS_PER_S
        if (disambiguation == Disambiguation.EARLIER) {
            val t = TM.addTime(dt.time, BigInteger.valueOf(-nanoseconds))
            val earlier = IsoDateTime(TM.addDays(dt.date, t.days), t)
            val p = possibleEpochNs(tz, earlier)
            if (p.isEmpty()) tRangeErr("nonexistent date-time")
            return p[0]
        }
        val t = TM.addTime(dt.time, BigInteger.valueOf(nanoseconds))
        val later = IsoDateTime(TM.addDays(dt.date, t.days), t)
        val p = possibleEpochNs(tz, later)
        if (p.isEmpty()) tRangeErr("nonexistent date-time")
        return p[p.size - 1]
    }

    /** GetStartOfDay */
    fun startOfDay(tz: TimeZone, d: IsoDate): BigInteger {
        val dt = IsoDateTime(d, TimeRec.MIDNIGHT)
        val possible = possibleEpochNs(tz, dt)
        if (possible.isNotEmpty()) return possible[0]
        // midnight is skipped: the day starts at the first instant after the transition
        val transition = tz.rules?.getTransition(localDateTime(dt)) ?: tRangeErr("nonexistent date-time")
        val ns = BigInteger.valueOf(transition.toEpochSecond()).multiply(BI_NS_PER_S)
        if (!TM.isValidEpochNs(ns)) tRangeErr("date-time outside of supported range")
        return ns
    }

    /** GetNamedTimeZoneNextTransition / GetNamedTimeZonePreviousTransition; null when there is none. */
    fun transition(tz: TimeZone, epochNs: BigInteger, next: Boolean): BigInteger? {
        if (tz.isOffset || tz.fixedOffsetNs != null) return null
        val rules = tz.rules!!
        val instant = instantOf(epochNs)
        val t = (if (next) rules.nextTransition(instant) else rules.previousTransition(instant)) ?: return null
        val ns = BigInteger.valueOf(t.toEpochSecond()).multiply(BI_NS_PER_S)
        return if (TM.isValidEpochNs(ns)) ns else null
    }

    /** The primary identifier of a named zone (offset zones are compared by offset). */
    fun primaryId(tz: TimeZone): String {
        tz.primary?.let { return it }
        var p = if (tz.isOffset || tz === TimeZone.UTC) tz.id else {
            val fromProvider = try {
                TemporalProviders.provider?.primaryTimeZoneId(tz.id)
            } catch (_: RuntimeException) {
                null
            }
            fromProvider ?: EXTRA_LINKS[tz.id] ?: tz.id
        }
        if (p in UTC_FAMILY || asciiEqualsIgnoreCase(p, "utc")) p = "UTC"
        tz.primary = p
        return p
    }

    /** TimeZoneEquals */
    fun equals(a: TimeZone, b: TimeZone): Boolean {
        if (a.id == b.id) return true
        if (a.isOffset || b.isOffset) return a.isOffset && b.isOffset && a.offsetMinutes == b.offsetMinutes
        return primaryId(a) == primaryId(b)
    }

    /** The available time zone (with its primary identifier) for a host ZoneId; "UTC" when not representable. */
    fun fromZoneId(z: ZoneId): TimeZone {
        try {
            if (z !is ZoneOffset) {
                val named = namedZone(z.id)
                if (named != null) {
                    val p = primaryId(named)
                    return if (p == named.id) named else namedZone(p) ?: named
                }
            }
            val n = z.normalized()
            if (n is ZoneOffset) {
                val seconds = n.totalSeconds
                return if (seconds == 0 || seconds % 60 != 0) TimeZone.UTC else TimeZone.ofOffsetMinutes(seconds / 60)
            }
        } catch (_: RuntimeException) {
            // fall through
        }
        return TimeZone.UTC
    }

    /** SystemTimeZoneIdentifier: the agent's configured zone (sandbox policy), else the host zone used by Date. */
    fun system(realm: Realm): TimeZone = fromZoneId(realm.agent.config.timeZone ?: dev.mooner.neonjs.builtins.DateTime.hostZone)
}
