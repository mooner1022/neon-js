package io.neonjs.builtins.temporal

import io.neonjs.runtime.*
import java.math.BigInteger

// ====================================================================== instances

class JSTemporalDuration(
    proto: JSObject?,
    @JvmField val years: Double,
    @JvmField val months: Double,
    @JvmField val weeks: Double,
    @JvmField val days: Double,
    @JvmField val hours: Double,
    @JvmField val minutes: Double,
    @JvmField val seconds: Double,
    @JvmField val milliseconds: Double,
    @JvmField val microseconds: Double,
    @JvmField val nanoseconds: Double,
) : JSObject(proto)

class JSTemporalInstant(proto: JSObject?, @JvmField val epochNs: BigInteger) : JSObject(proto)

class JSTemporalPlainDate(proto: JSObject?, @JvmField val date: IsoDate, @JvmField val calendar: String) : JSObject(proto)

class JSTemporalPlainTime(proto: JSObject?, @JvmField val time: TimeRec) : JSObject(proto)

class JSTemporalPlainDateTime(proto: JSObject?, @JvmField val dt: IsoDateTime, @JvmField val calendar: String) : JSObject(proto)

class JSTemporalPlainYearMonth(proto: JSObject?, @JvmField val date: IsoDate, @JvmField val calendar: String) : JSObject(proto)

class JSTemporalPlainMonthDay(proto: JSObject?, @JvmField val date: IsoDate, @JvmField val calendar: String) : JSObject(proto)

class JSTemporalZonedDateTime(
    proto: JSObject?,
    @JvmField val epochNs: BigInteger,
    @JvmField val timeZone: TimeZone,
    @JvmField val calendar: String,
) : JSObject(proto)

// ====================================================================== creation

internal object TCreate {
    private fun cur(): Realm = Agent.currentRealm()

    private fun proto(nt: JSObject?, name: String): JSObject {
        val realm = cur()
        return if (nt == null) realm.intrinsic(name) else Ops.getPrototypeFromConstructor(nt) { it.intrinsic(name) }
    }

    const val P_DURATION = "%Temporal.Duration.prototype%"
    const val P_INSTANT = "%Temporal.Instant.prototype%"
    const val P_DATE = "%Temporal.PlainDate.prototype%"
    const val P_TIME = "%Temporal.PlainTime.prototype%"
    const val P_DATETIME = "%Temporal.PlainDateTime.prototype%"
    const val P_YEARMONTH = "%Temporal.PlainYearMonth.prototype%"
    const val P_MONTHDAY = "%Temporal.PlainMonthDay.prototype%"
    const val P_ZONED = "%Temporal.ZonedDateTime.prototype%"

    /** IsValidDuration */
    fun isValidDuration(y: Double, mo: Double, w: Double, d: Double, h: Double, mi: Double, s: Double, ms: Double, us: Double, ns: Double): Boolean {
        var sign = 0
        for (v in doubleArrayOf(y, mo, w, d, h, mi, s, ms, us, ns)) {
            if (!v.isFinite()) return false
            if (v < 0) {
                if (sign > 0) return false
                sign = -1
            } else if (v > 0) {
                if (sign < 0) return false
                sign = 1
            }
        }
        val lim = 4294967296.0
        if (Math.abs(y) >= lim || Math.abs(mo) >= lim || Math.abs(w) >= lim) return false
        // fast path: everything comfortably small
        if (Math.abs(d) < 1e6 && Math.abs(h) < 1e7 && Math.abs(mi) < 1e9 && Math.abs(s) < 1e11 && Math.abs(ms) < 1e14 &&
            Math.abs(us) < 1e17 && Math.abs(ns) < 1e18
        ) return true
        val total = TNum.big(d).multiply(TM.BI_NS_PER_DAY)
            .add(TNum.big(h).multiply(BigInteger.valueOf(3_600_000_000_000L)))
            .add(TNum.big(mi).multiply(BigInteger.valueOf(60_000_000_000L)))
            .add(TNum.big(s).multiply(TNum.BI_1E9))
            .add(TNum.big(ms).multiply(TNum.BI_1E6))
            .add(TNum.big(us).multiply(TNum.BI_1000))
            .add(TNum.big(ns))
        return total.abs() < TM.TWO_POW_53_NS
    }

    /** CreateTemporalDuration */
    fun duration(
        y: Double, mo: Double, w: Double, d: Double, h: Double, mi: Double, s: Double, ms: Double, us: Double, ns: Double,
        nt: JSObject? = null,
    ): JSTemporalDuration {
        if (!isValidDuration(y, mo, w, d, h, mi, s, ms, us, ns)) tRangeErr("invalid duration")
        return JSTemporalDuration(
            proto(nt, P_DURATION), TNum.pz(y), TNum.pz(mo), TNum.pz(w), TNum.pz(d), TNum.pz(h), TNum.pz(mi), TNum.pz(s),
            TNum.pz(ms), TNum.pz(us), TNum.pz(ns),
        )
    }

    fun duration(f: DoubleArray, nt: JSObject? = null): JSTemporalDuration =
        duration(f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7], f[8], f[9], nt)

    fun instant(ns: BigInteger, nt: JSObject? = null): JSTemporalInstant = JSTemporalInstant(proto(nt, P_INSTANT), ns)

    fun plainDate(d: IsoDate, cal: String, nt: JSObject? = null): JSTemporalPlainDate {
        if (!TM.isoDateWithinLimits(d)) tRangeErr("date outside of supported range")
        return JSTemporalPlainDate(proto(nt, P_DATE), d, cal)
    }

    fun plainTime(t: TimeRec, nt: JSObject? = null): JSTemporalPlainTime =
        JSTemporalPlainTime(proto(nt, P_TIME), if (t.days == 0L) t else TimeRec(t.hour, t.minute, t.second, t.millisecond, t.microsecond, t.nanosecond))

    fun plainDateTime(dt: IsoDateTime, cal: String, nt: JSObject? = null): JSTemporalPlainDateTime {
        if (!TM.isoDateTimeWithinLimits(dt)) tRangeErr("date-time outside of supported range")
        return JSTemporalPlainDateTime(proto(nt, P_DATETIME), dt, cal)
    }

    fun plainYearMonth(d: IsoDate, cal: String, nt: JSObject? = null): JSTemporalPlainYearMonth {
        if (!TM.isoYearMonthWithinLimits(d.year, d.month)) tRangeErr("year-month outside of supported range")
        return JSTemporalPlainYearMonth(proto(nt, P_YEARMONTH), d, cal)
    }

    fun plainMonthDay(d: IsoDate, cal: String, nt: JSObject? = null): JSTemporalPlainMonthDay {
        if (!TM.isoDateWithinLimits(d)) tRangeErr("month-day outside of supported range")
        return JSTemporalPlainMonthDay(proto(nt, P_MONTHDAY), d, cal)
    }

    fun zoned(ns: BigInteger, tz: TimeZone, cal: String, nt: JSObject? = null): JSTemporalZonedDateTime =
        JSTemporalZonedDateTime(proto(nt, P_ZONED), ns, tz, cal)
}
