package io.neonjs.builtins.temporal

import io.neonjs.builtins.getter
import io.neonjs.builtins.makeCtor
import io.neonjs.builtins.method
import io.neonjs.runtime.*
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.math.floor

internal object InstantBuiltins {
    private fun thisInstant(t: Any?, name: String): JSTemporalInstant =
        t as? JSTemporalInstant ?: tTypeErr("Temporal.Instant.prototype.$name called on incompatible receiver")

    private fun checkNs(ns: BigInteger): BigInteger {
        if (!TM.isValidEpochNs(ns)) tRangeErr("epoch nanoseconds outside of supported range")
        return ns
    }

    /** NumberToBigInt(ms) × 10^6 */
    fun msToNs(ms: Double): BigInteger {
        if (ms.isNaN() || ms.isInfinite() || ms != floor(ms)) tRangeErr("${Ops.toString(ms)} is not an integer")
        return TNum.big(ms).multiply(TNum.BI_1E6)
    }

    fun install(realm: Realm, temporal: JSObject) {
        val proto = JSObject(realm.objectPrototype)
        realm.intrinsics[TCreate.P_INSTANT] = proto
        val ctor = makeCtor(realm, "Instant", 1, proto) { _, _, args, nt ->
            if (nt == null) tTypeErr("Constructor Temporal.Instant requires 'new'")
            val ns = checkNs(Ops.toBigInt(args.arg(0)))
            TCreate.instant(ns, nt)
        }
        temporal.defineOwn("Instant", ctor, Attr.WC)
        ctor.method(realm, "from", 1) { _, _, args, _ -> TCreate.instant(TConv.toInstantNs(args.arg(0))) }
        ctor.method(realm, "fromEpochMilliseconds", 1) { _, _, args, _ ->
            val ms = Ops.toNumber(args.arg(0))
            TCreate.instant(checkNs(msToNs(ms)))
        }
        ctor.method(realm, "fromEpochNanoseconds", 1) { _, _, args, _ ->
            TCreate.instant(checkNs(Ops.toBigInt(args.arg(0))))
        }
        ctor.method(realm, "compare", 2) { _, _, args, _ ->
            val a = TConv.toInstantNs(args.arg(0))
            val b = TConv.toInstantNs(args.arg(1))
            a.compareTo(b).coerceIn(-1, 1).toDouble()
        }
        proto.defineOwn(JSSymbol.toStringTag, "Temporal.Instant", Attr.CONFIGURABLE)
        proto.getter(realm, "epochMilliseconds") { _, t, _, _ ->
            val ns = thisInstant(t, "epochMilliseconds").epochNs
            floorDiv(ns, TNum.BI_1E6).toDouble()
        }
        proto.getter(realm, "epochNanoseconds") { _, t, _, _ -> thisInstant(t, "epochNanoseconds").epochNs }
        installMethods(realm, proto)
    }

    fun floorDiv(a: BigInteger, b: BigInteger): BigInteger {
        val qr = a.divideAndRemainder(b)
        return if (qr[1].signum() < 0) qr[0].subtract(BigInteger.ONE) else qr[0]
    }

    private fun installMethods(realm: Realm, proto: JSObject) {
        proto.method(realm, "add", 1) { _, t, args, _ -> addDuration(false, thisInstant(t, "add"), args.arg(0)) }
        proto.method(realm, "subtract", 1) { _, t, args, _ -> addDuration(true, thisInstant(t, "subtract"), args.arg(0)) }
        proto.method(realm, "until", 1) { _, t, args, _ -> difference(false, thisInstant(t, "until"), args.arg(0), args.arg(1)) }
        proto.method(realm, "since", 1) { _, t, args, _ -> difference(true, thisInstant(t, "since"), args.arg(0), args.arg(1)) }
        proto.method(realm, "round", 1) { _, t, args, _ -> round(thisInstant(t, "round"), args.arg(0)) }
        proto.method(realm, "equals", 1) { _, t, args, _ ->
            val i = thisInstant(t, "equals")
            i.epochNs == TConv.toInstantNs(args.arg(0))
        }
        proto.method(realm, "toString", 0) { _, t, args, _ -> toStringImpl(thisInstant(t, "toString"), args.arg(0)) }
        proto.method(realm, "toLocaleString", 0) { _, t, _, _ ->
            TFormat.instantToString(thisInstant(t, "toLocaleString").epochNs, null, Precision.AUTO)
        }
        proto.method(realm, "toJSON", 0) { _, t, _, _ -> TFormat.instantToString(thisInstant(t, "toJSON").epochNs, null, Precision.AUTO) }
        proto.method(realm, "valueOf", 0) { _, _, _, _ -> tTypeErr("use Temporal.Instant.compare() or equals() instead of valueOf()") }
        proto.method(realm, "toZonedDateTimeISO", 1) { _, t, args, _ ->
            val i = thisInstant(t, "toZonedDateTimeISO")
            val tz = TZ.toTimeZone(args.arg(0))
            TCreate.zoned(i.epochNs, tz, TCal.ISO)
        }
    }

    private fun addDuration(subtract: Boolean, i: JSTemporalInstant, like: Any?): JSTemporalInstant {
        var d = TDur.toDuration(like)
        if (subtract) d = TDur.negated(d)
        val largest = TDur.defaultLargestUnit(d)
        if (largest.isDate) tRangeErr("cannot add a duration with date units to an Instant")
        val id = TDur.toInternal24(d)
        return TCreate.instant(TDur.addInstant(i.epochNs, id.time))
    }

    private fun difference(since: Boolean, i: JSTemporalInstant, otherV: Any?, options: Any?): JSTemporalDuration {
        val other = TConv.toInstantNs(otherV)
        val o = TOpt.optionsObject(options)
        val s = TDur.differenceSettings(since, o, 1, emptyArray(), TUnit.NANOSECOND, TUnit.SECOND)
        val id = TDur.differenceInstant(i.epochNs, other, s.increment, s.smallest, s.mode)
        val r = TDur.fromInternal(id, s.largest)
        return if (since) TDur.negated(r) else r
    }

    private fun round(i: JSTemporalInstant, roundToV: Any?): JSTemporalInstant {
        if (roundToV === Undefined) tTypeErr("options argument is required")
        val roundTo = if (roundToV is CharSequence) {
            JSObject(null).also { it.createDataPropertyOrThrow("smallestUnit", roundToV.toString()) }
        } else TOpt.optionsObject(roundToV)
        val inc = TOpt.roundingIncrement(roundTo)
        val mode = TOpt.roundingMode(roundTo, RMode.HALF_EXPAND)
        val smallestOpt = TOpt.unit(roundTo, "smallestUnit", true)
        TOpt.validateUnit(smallestOpt, 1)
        val smallest = TUnit.ALL[smallestOpt]
        val maximum = TM.NS_PER_DAY / smallest.nanos
        TOpt.validateIncrement(inc, maximum, true)
        val ns = TNum.roundAsIfPositive(i.epochNs, BigInteger.valueOf(inc * smallest.nanos), mode)
        return TCreate.instant(ns)
    }

    private fun toStringImpl(i: JSTemporalInstant, options: Any?): String {
        val o = TOpt.optionsObject(options)
        val digits = TOpt.fractionalSecondDigits(o)
        val mode = TOpt.roundingMode(o, RMode.TRUNC)
        val smallest = TOpt.unit(o, "smallestUnit", false)
        val tzV = o.get("timeZone", o)
        TOpt.validateUnit(smallest, 1)
        if (smallest == TUnit.HOUR.ordinal) tRangeErr("smallestUnit must not be hour")
        val tz = if (tzV !== Undefined) TZ.toTimeZone(tzV) else null
        val p = Precision.of(smallest, digits)
        val ns = TNum.roundAsIfPositive(i.epochNs, BigInteger.valueOf(p.increment * p.unit.nanos), mode)
        return TFormat.instantToString(ns, tz, p.precision)
    }

    /** SystemUTCEpochNanoseconds from the agent's (possibly deterministic) clock. */
    fun systemNs(realm: Realm): BigInteger {
        val ms = realm.agent.currentTimeMillis()
        if (ms.isNaN()) return BigInteger.ZERO
        val ns = BigDecimal(ms).multiply(BigDecimal.valueOf(1_000_000L)).setScale(0, java.math.RoundingMode.FLOOR).toBigInteger()
        return ns.max(TM.NS_MIN_INSTANT).min(TM.NS_MAX_INSTANT)
    }
}
