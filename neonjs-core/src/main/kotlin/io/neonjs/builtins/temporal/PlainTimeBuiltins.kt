package io.neonjs.builtins.temporal

import io.neonjs.builtins.getter
import io.neonjs.builtins.makeCtor
import io.neonjs.builtins.method
import io.neonjs.runtime.*
import java.math.BigInteger

internal object PlainTimeBuiltins {
    private fun thisTime(t: Any?, name: String): JSTemporalPlainTime =
        t as? JSTemporalPlainTime ?: tTypeErr("Temporal.PlainTime.prototype.$name called on incompatible receiver")

    fun install(realm: Realm, temporal: JSObject) {
        val proto = JSObject(realm.objectPrototype)
        realm.intrinsics[TCreate.P_TIME] = proto
        val ctor = makeCtor(realm, "PlainTime", 0, proto) { _, _, args, nt ->
            if (nt == null) tTypeErr("Constructor Temporal.PlainTime requires 'new'")
            val f = DoubleArray(6)
            for (i in 0 until 6) {
                val v = args.arg(i)
                f[i] = if (v === Undefined) 0.0 else toIntegerWithTruncation(v)
            }
            if (!TConv.isValidTime(f[0], f[1], f[2], f[3], f[4], f[5])) tRangeErr("time is out of range")
            TCreate.plainTime(TimeRec(f[0].toInt(), f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5].toInt()), nt)
        }
        temporal.defineOwn("PlainTime", ctor, Attr.WC)
        ctor.method(realm, "from", 1) { _, _, args, _ -> TCreate.plainTime(TConv.toTime(args.arg(0), args.arg(1))) }
        ctor.method(realm, "compare", 2) { _, _, args, _ ->
            val a = TConv.toTime(args.arg(0))
            val b = TConv.toTime(args.arg(1))
            TM.compareTime(a, b).toDouble()
        }
        proto.defineOwn(JSSymbol.toStringTag, "Temporal.PlainTime", Attr.CONFIGURABLE)
        proto.getter(realm, "hour") { _, t, _, _ -> thisTime(t, "hour").time.hour.toDouble() }
        proto.getter(realm, "minute") { _, t, _, _ -> thisTime(t, "minute").time.minute.toDouble() }
        proto.getter(realm, "second") { _, t, _, _ -> thisTime(t, "second").time.second.toDouble() }
        proto.getter(realm, "millisecond") { _, t, _, _ -> thisTime(t, "millisecond").time.millisecond.toDouble() }
        proto.getter(realm, "microsecond") { _, t, _, _ -> thisTime(t, "microsecond").time.microsecond.toDouble() }
        proto.getter(realm, "nanosecond") { _, t, _, _ -> thisTime(t, "nanosecond").time.nanosecond.toDouble() }
        installMethods(realm, proto)
    }

    private fun installMethods(realm: Realm, proto: JSObject) {
        proto.method(realm, "add", 1) { _, t, args, _ -> addDuration(false, thisTime(t, "add"), args.arg(0)) }
        proto.method(realm, "subtract", 1) { _, t, args, _ -> addDuration(true, thisTime(t, "subtract"), args.arg(0)) }
        proto.method(realm, "with", 1) { _, t, args, _ ->
            val pt = thisTime(t, "with")
            val like = args.arg(0)
            if (!TConv.isPartialTemporalObject(like)) tTypeErr("argument must be a partial time object")
            val p = TConv.toTimeRecordFields(like as JSObject, true)
            val cur = pt.time
            val h = if (p[0].isNaN()) cur.hour.toDouble() else p[0]
            val mi = if (p[1].isNaN()) cur.minute.toDouble() else p[1]
            val s = if (p[2].isNaN()) cur.second.toDouble() else p[2]
            val ms = if (p[3].isNaN()) cur.millisecond.toDouble() else p[3]
            val us = if (p[4].isNaN()) cur.microsecond.toDouble() else p[4]
            val ns = if (p[5].isNaN()) cur.nanosecond.toDouble() else p[5]
            val overflow = TOpt.overflow(TOpt.optionsObject(args.arg(1)))
            TCreate.plainTime(TConv.regulateTime(h, mi, s, ms, us, ns, overflow))
        }
        proto.method(realm, "until", 1) { _, t, args, _ -> difference(false, thisTime(t, "until"), args.arg(0), args.arg(1)) }
        proto.method(realm, "since", 1) { _, t, args, _ -> difference(true, thisTime(t, "since"), args.arg(0), args.arg(1)) }
        proto.method(realm, "round", 1) { _, t, args, _ -> round(thisTime(t, "round"), args.arg(0)) }
        proto.method(realm, "equals", 1) { _, t, args, _ ->
            val pt = thisTime(t, "equals")
            TM.compareTime(pt.time, TConv.toTime(args.arg(0))) == 0
        }
        proto.method(realm, "toString", 0) { _, t, args, _ -> toStringImpl(thisTime(t, "toString"), args.arg(0)) }
        proto.method(realm, "toLocaleString", 0) { _, t, _, _ -> TFormat.timeRecordToString(thisTime(t, "toLocaleString").time, Precision.AUTO) }
        proto.method(realm, "toJSON", 0) { _, t, _, _ -> TFormat.timeRecordToString(thisTime(t, "toJSON").time, Precision.AUTO) }
        proto.method(realm, "valueOf", 0) { _, _, _, _ -> tTypeErr("use Temporal.PlainTime.compare() or equals() instead of valueOf()") }
    }

    private fun addDuration(subtract: Boolean, pt: JSTemporalPlainTime, like: Any?): JSTemporalPlainTime {
        var d = TDur.toDuration(like)
        if (subtract) d = TDur.negated(d)
        val id = TDur.toInternal(d)
        return TCreate.plainTime(TM.addTime(pt.time, id.time))
    }

    private fun difference(since: Boolean, pt: JSTemporalPlainTime, otherV: Any?, options: Any?): JSTemporalDuration {
        val other = TConv.toTime(otherV)
        val o = TOpt.optionsObject(options)
        val s = TDur.differenceSettings(since, o, 1, emptyArray(), TUnit.NANOSECOND, TUnit.HOUR)
        var td = BigInteger.valueOf(TM.differenceTime(pt.time, other))
        td = TDur.roundTime(td, s.increment, s.smallest, s.mode)
        val r = TDur.fromInternal(InternalDuration(DateDuration.ZERO, td), s.largest)
        return if (since) TDur.negated(r) else r
    }

    private fun round(pt: JSTemporalPlainTime, roundToV: Any?): JSTemporalPlainTime {
        if (roundToV === Undefined) tTypeErr("options argument is required")
        val roundTo = if (roundToV is CharSequence) {
            JSObject(null).also { it.createDataPropertyOrThrow("smallestUnit", roundToV.toString()) }
        } else TOpt.optionsObject(roundToV)
        val inc = TOpt.roundingIncrement(roundTo)
        val mode = TOpt.roundingMode(roundTo, RMode.HALF_EXPAND)
        val smallestOpt = TOpt.unit(roundTo, "smallestUnit", true)
        TOpt.validateUnit(smallestOpt, 1)
        val smallest = TUnit.ALL[smallestOpt]
        TOpt.validateIncrement(inc, smallest.maxIncrement.toLong(), false)
        return TCreate.plainTime(TM.roundTime(pt.time, inc, smallest, mode))
    }

    private fun toStringImpl(pt: JSTemporalPlainTime, options: Any?): String {
        val o = TOpt.optionsObject(options)
        val digits = TOpt.fractionalSecondDigits(o)
        val mode = TOpt.roundingMode(o, RMode.TRUNC)
        val smallest = TOpt.unit(o, "smallestUnit", false)
        TOpt.validateUnit(smallest, 1)
        if (smallest == TUnit.HOUR.ordinal) tRangeErr("smallestUnit must not be hour")
        val p = Precision.of(smallest, digits)
        val r = TM.roundTime(pt.time, p.increment, p.unit, mode)
        return TFormat.timeRecordToString(r, p.precision)
    }
}
