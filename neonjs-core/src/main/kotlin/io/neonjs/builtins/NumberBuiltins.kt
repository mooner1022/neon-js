package io.neonjs.builtins

import io.neonjs.runtime.*
import java.math.BigInteger

internal object NumberBuiltins {
    fun thisNumber(t: Any?, method: String): Double = when (t) {
        is Double -> t
        is JSPrimitiveWrapper -> t.primitive as? Double ?: typeErr("Number.prototype.$method requires that 'this' be a Number")
        else -> typeErr("Number.prototype.$method requires that 'this' be a Number")
    }

    fun install(realm: Realm) {
        val proto = JSPrimitiveWrapper(realm.objectPrototype, 0.0)
        realm.numberPrototype = proto
        val ctor = makeCtor(realm, "Number", 1, proto) { _, _, args, nt ->
            val n = if (args.isEmpty()) 0.0 else {
                val prim = Ops.toNumeric(args[0])
                if (prim is BigInteger) prim.toDouble() else prim as Double
            }
            if (nt == null) n else JSPrimitiveWrapper(Ops.getPrototypeFromConstructor(nt) { it.numberPrototype }, n)
        }
        realm.global("Number", ctor)
        ctor.value("EPSILON", Math.ulp(1.0), Attr.NONE)
        ctor.value("MAX_SAFE_INTEGER", 9007199254740991.0, Attr.NONE)
        ctor.value("MAX_VALUE", Double.MAX_VALUE, Attr.NONE)
        ctor.value("MIN_SAFE_INTEGER", -9007199254740991.0, Attr.NONE)
        ctor.value("MIN_VALUE", java.lang.Double.MIN_VALUE, Attr.NONE)
        ctor.value("NaN", Double.NaN, Attr.NONE)
        ctor.value("NEGATIVE_INFINITY", Double.NEGATIVE_INFINITY, Attr.NONE)
        ctor.value("POSITIVE_INFINITY", Double.POSITIVE_INFINITY, Attr.NONE)
        ctor.method(realm, "isFinite", 1) { _, _, args, _ -> val v = args.arg(0); v is Double && !v.isNaN() && !v.isInfinite() }
        ctor.method(realm, "isInteger", 1) { _, _, args, _ -> val v = args.arg(0); v is Double && Ops.isIntegral(v) }
        ctor.method(realm, "isNaN", 1) { _, _, args, _ -> val v = args.arg(0); v is Double && v.isNaN() }
        ctor.method(realm, "isSafeInteger", 1) { _, _, args, _ ->
            val v = args.arg(0)
            v is Double && Ops.isIntegral(v) && Math.abs(v) <= 9007199254740991.0
        }

        proto.method(realm, "toExponential", 1) { _, t, args, _ ->
            val x = thisNumber(t, "toExponential")
            val f = Ops.toIntegerOrInfinity(args.arg(0))
            if (x.isNaN()) "NaN"
            else if (x.isInfinite()) (if (x > 0) "Infinity" else "-Infinity")
            else {
                if (f < 0 || f > 100) rangeErr("toExponential() argument must be between 0 and 100")
                NumberConv.toExponential(x, if (args.arg(0) === Undefined) null else f.toInt())
            }
        }
        proto.method(realm, "toFixed", 1) { _, t, args, _ ->
            val x = thisNumber(t, "toFixed")
            val f = Ops.toIntegerOrInfinity(args.arg(0))
            if (f.isInfinite() || f < 0 || f > 100) rangeErr("toFixed() digits argument must be between 0 and 100")
            if (x.isNaN()) "NaN"
            else if (x.isInfinite()) NumberConv.toString(x)
            else NumberConv.toFixed(x, f.toInt())
        }
        proto.method(realm, "toLocaleString", 0) { _, t, _, _ -> NumberConv.toString(thisNumber(t, "toLocaleString")) }
        proto.method(realm, "toPrecision", 1) { _, t, args, _ ->
            val x = thisNumber(t, "toPrecision")
            if (args.arg(0) === Undefined) NumberConv.toString(x)
            else {
                val p = Ops.toIntegerOrInfinity(args.arg(0))
                if (x.isNaN()) "NaN"
                else if (x.isInfinite()) NumberConv.toString(x)
                else {
                    if (p < 1 || p > 100) rangeErr("toPrecision() argument must be between 1 and 100")
                    NumberConv.toPrecision(x, p.toInt())
                }
            }
        }
        proto.method(realm, "toString", 1) { _, t, args, _ ->
            val x = thisNumber(t, "toString")
            val r = args.arg(0)
            val radix = if (r === Undefined) 10.0 else Ops.toIntegerOrInfinity(r)
            if (radix < 2 || radix > 36) rangeErr("toString() radix must be between 2 and 36")
            NumberConv.toStringRadix(x, radix.toInt())
        }
        proto.method(realm, "valueOf", 0) { _, t, _, _ -> thisNumber(t, "valueOf") }
    }
}

internal object MathBuiltins {
    fun install(realm: Realm) {
        val m = JSObject(realm.objectPrototype)
        realm.global("Math", m)
        m.value("E", Math.E, Attr.NONE)
        m.value("LN10", Math.log(10.0), Attr.NONE)
        m.value("LN2", Math.log(2.0), Attr.NONE)
        m.value("LOG10E", 1.0 / Math.log(10.0), Attr.NONE)
        m.value("LOG2E", 1.0 / Math.log(2.0), Attr.NONE)
        m.value("PI", Math.PI, Attr.NONE)
        m.value("SQRT1_2", Math.sqrt(0.5), Attr.NONE)
        m.value("SQRT2", Math.sqrt(2.0), Attr.NONE)
        m.value(JSSymbol.toStringTag, "Math", Attr.CONFIGURABLE)
        fun f1(name: String, op: (Double) -> Double) {
            m.method(realm, name, 1) { _, _, args, _ -> op(Ops.toNumber(args.arg(0))) }
        }
        f1("abs") { Math.abs(it) }
        f1("acos") { Math.acos(it) }
        f1("acosh") { x -> MathExtra.acosh(x) }
        f1("asin") { Math.asin(it) }
        f1("asinh") { x -> MathExtra.asinh(x) }
        f1("atan") { Math.atan(it) }
        f1("atanh") { x -> MathExtra.atanh(x) }
        f1("cbrt") { Math.cbrt(it) }
        f1("ceil") { Math.ceil(it) }
        f1("clz32") { x -> Integer.numberOfLeadingZeros(Ops.toInt32(x)).toDouble() }
        f1("cos") { Math.cos(it) }
        f1("cosh") { Math.cosh(it) }
        f1("exp") { Math.exp(it) }
        f1("expm1") { Math.expm1(it) }
        f1("floor") { Math.floor(it) }
        f1("fround") { x -> x.toFloat().toDouble() }
        f1("log") { Math.log(it) }
        f1("log1p") { Math.log1p(it) }
        f1("log10") { Math.log10(it) }
        f1("log2") { x ->
            val r = Math.log(x) / Math.log(2.0)
            val ri = Math.rint(r)
            if (x > 0 && Math.abs(r - ri) < 1e-12 && Math.pow(2.0, ri) == x) ri else r
        }
        f1("round") { x ->
            if (x.isNaN() || x.isInfinite()) x
            else if (x == 0.0) x
            else if (x > 0 && x < 0.5) 0.0
            else if (x < 0 && x >= -0.5) -0.0
            else {
                val fl = Math.floor(x)
                if (x - fl >= 0.5) fl + 1 else fl
            }
        }
        f1("sign") { x -> if (x.isNaN()) x else if (x > 0) 1.0 else if (x < 0) -1.0 else x }
        f1("sin") { Math.sin(it) }
        f1("sinh") { Math.sinh(it) }
        f1("sqrt") { Math.sqrt(it) }
        f1("tan") { Math.tan(it) }
        f1("tanh") { Math.tanh(it) }
        f1("trunc") { x -> if (x.isNaN() || x.isInfinite()) x else if (x < 0) Math.ceil(x) else Math.floor(x) }
        f1("f16round") { x -> Float16.round(x) }
        m.method(realm, "atan2", 2) { _, _, args, _ ->
            val y = Ops.toNumber(args.arg(0))
            val x = Ops.toNumber(args.arg(1))
            Math.atan2(y, x)
        }
        m.method(realm, "hypot", 2) { _, _, args, _ ->
            val nums = DoubleArray(args.size) { Ops.toNumber(args[it]) }
            var inf = false
            var nan = false
            for (d in nums) {
                if (d.isInfinite()) inf = true
                if (d.isNaN()) nan = true
            }
            if (inf) Double.POSITIVE_INFINITY
            else if (nan) Double.NaN
            else {
                var max = 0.0
                for (d in nums) max = maxOf(max, Math.abs(d))
                if (max == 0.0) 0.0
                else {
                    var sum = 0.0
                    var comp = 0.0
                    for (d in nums) {
                        val r = d / max
                        val y = r * r - comp
                        val t = sum + y
                        comp = (t - sum) - y
                        sum = t
                    }
                    Math.sqrt(sum) * max
                }
            }
        }
        m.method(realm, "imul", 2) { _, _, args, _ -> (Ops.toInt32(args.arg(0)) * Ops.toInt32(args.arg(1))).toDouble() }
        m.method(realm, "max", 2) { _, _, args, _ ->
            val nums = DoubleArray(args.size) { Ops.toNumber(args[it]) }
            var r = Double.NEGATIVE_INFINITY
            for (d in nums) {
                if (d.isNaN()) { r = Double.NaN; break }
                if (d > r || (d == 0.0 && r == 0.0 && 1.0 / r < 0)) r = d
            }
            r
        }
        m.method(realm, "min", 2) { _, _, args, _ ->
            val nums = DoubleArray(args.size) { Ops.toNumber(args[it]) }
            var r = Double.POSITIVE_INFINITY
            for (d in nums) {
                if (d.isNaN()) { r = Double.NaN; break }
                if (d < r || (d == 0.0 && r == 0.0 && 1.0 / d < 0)) r = d
            }
            r
        }
        m.method(realm, "pow", 2) { _, _, args, _ -> Ops.pow(Ops.toNumber(args.arg(0)), Ops.toNumber(args.arg(1))) }
        m.method(realm, "random", 0) { f, _, _, _ -> f.realm.agent.nextRandom() }
        m.method(realm, "sumPrecise", 1) { f, _, args, _ -> SumPrecise.sum(f.realm, args.arg(0)) }
    }
}

/** IEEE 754 binary16 rounding (Math.f16round, Float16Array). */
object Float16 {
    fun round(x: Double): Double = toDouble(fromDouble(x))

    fun fromDouble(d: Double): Short {
        if (d.isNaN()) return 0x7E00.toShort()
        val bits = java.lang.Double.doubleToRawLongBits(d)
        val sign = ((bits ushr 48) and 0x8000L).toInt()
        val ad = Math.abs(d)
        if (ad >= 65520.0) return (sign or 0x7C00).toShort()
        if (ad < 2.9802322387695312E-8) return sign.toShort() // < 2^-25 rounds to zero (ties to even at 2^-25 -> 0)
        // use exact rounding via BigDecimal-free approach: scale into subnormal/normal ranges
        val e = Math.getExponent(ad)
        if (e < -14) {
            // subnormal: value = m * 2^-24
            val m = ad / Math.scalb(1.0, -24)
            val r = Math.rint(m)
            return (sign or r.toInt()).toShort()
        }
        val mant = ad / Math.scalb(1.0, e) - 1.0 // in [0,1)
        var m = Math.rint(mant * 1024.0)
        var exp = e
        if (m >= 1024.0) {
            m = 0.0
            exp++
        }
        if (exp > 15) return (sign or 0x7C00).toShort()
        return (sign or ((exp + 15) shl 10) or m.toInt()).toShort()
    }

    fun toDouble(h: Short): Double {
        val v = h.toInt() and 0xFFFF
        val sign = if (v and 0x8000 != 0) -1.0 else 1.0
        val exp = (v ushr 10) and 0x1F
        val mant = v and 0x3FF
        return when (exp) {
            0 -> sign * Math.scalb(mant.toDouble(), -24)
            31 -> if (mant == 0) sign * Double.POSITIVE_INFINITY else Double.NaN
            else -> sign * Math.scalb(1.0 + mant / 1024.0, exp - 15)
        }
    }
}

/** Math.sumPrecise using exact BigDecimal accumulation. */
internal object SumPrecise {
    fun sum(realm: Realm, items: Any?): Double {
        Ops.requireObjectCoercible(items)
        val rec = io.neonjs.vm.Iteration.getIterator(realm, items, false)
        var acc = java.math.BigDecimal.ZERO
        var posInf = false
        var negInf = false
        var nan = false
        var allNegZero = true
        var count = 0L
        while (true) {
            val v = io.neonjs.vm.Iteration.stepValue(rec)
            if (v === NotFound) break
            count++
            if (count >= (1L shl 53)) {
                io.neonjs.vm.Iteration.closeOnThrow(rec)
                rangeErr("Too many values")
            }
            if (v !is Double) {
                io.neonjs.vm.Iteration.closeOnThrow(rec)
                typeErr("Math.sumPrecise requires numbers")
            }
            when {
                v.isNaN() -> nan = true
                v == Double.POSITIVE_INFINITY -> posInf = true
                v == Double.NEGATIVE_INFINITY -> negInf = true
                else -> {
                    if (!(v == 0.0 && 1.0 / v < 0)) allNegZero = false
                    if (v != 0.0) acc = acc.add(java.math.BigDecimal(v))
                }
            }
        }
        if (nan || (posInf && negInf)) return Double.NaN
        if (posInf) return Double.POSITIVE_INFINITY
        if (negInf) return Double.NEGATIVE_INFINITY
        if (acc.signum() == 0) return if (allNegZero) -0.0 else 0.0
        return acc.toDouble()
    }
}

internal object BigIntBuiltins {
    fun thisBigInt(t: Any?, m: String): BigInteger = when (t) {
        is BigInteger -> t
        is JSPrimitiveWrapper -> t.primitive as? BigInteger ?: typeErr("BigInt.prototype.$m requires that 'this' be a BigInt")
        else -> typeErr("BigInt.prototype.$m requires that 'this' be a BigInt")
    }

    fun install(realm: Realm) {
        val proto = JSObject(realm.objectPrototype)
        realm.bigintPrototype = proto
        val ctor = makeCtor(realm, "BigInt", 1, proto) { _, _, args, nt ->
            if (nt != null) typeErr("BigInt is not a constructor")
            val prim = Ops.toPrimitive(args.arg(0), Ops.HINT_NUMBER)
            if (prim is Double) {
                if (!Ops.isIntegral(prim)) rangeErr("The number ${NumberConv.toString(prim)} cannot be converted to a BigInt because it is not an integer")
                Ops.bigIntFromDouble(prim)
            } else Ops.toBigInt(prim)
        }
        realm.global("BigInt", ctor)
        ctor.method(realm, "asIntN", 2) { _, _, args, _ ->
            val bits = Ops.toIndex(args.arg(0))
            val b = Ops.toBigInt(args.arg(1))
            if (bits == 0L) BigInteger.ZERO
            else {
                if (bits > Ops.MAX_BIGINT_BITS) {
                    if (b.bitLength() < bits) b else rangeErr("Maximum BigInt size exceeded")
                } else {
                    val n = bits.toInt()
                    val mod = b.mod(BigInteger.ONE.shiftLeft(n))
                    if (mod.testBit(n - 1)) mod.subtract(BigInteger.ONE.shiftLeft(n)) else mod
                }
            }
        }
        ctor.method(realm, "asUintN", 2) { _, _, args, _ ->
            val bits = Ops.toIndex(args.arg(0))
            val b = Ops.toBigInt(args.arg(1))
            if (bits > Ops.MAX_BIGINT_BITS) {
                if (b.signum() >= 0 && b.bitLength() <= bits) b else rangeErr("Maximum BigInt size exceeded")
            } else b.mod(BigInteger.ONE.shiftLeft(bits.toInt()))
        }
        proto.method(realm, "toLocaleString", 0) { _, t, _, _ -> thisBigInt(t, "toLocaleString").toString() }
        proto.method(realm, "toString", 0) { _, t, args, _ ->
            val x = thisBigInt(t, "toString")
            val r = args.arg(0)
            val radix = if (r === Undefined) 10.0 else Ops.toIntegerOrInfinity(r)
            if (radix < 2 || radix > 36) rangeErr("toString() radix must be between 2 and 36")
            x.toString(radix.toInt())
        }
        proto.method(realm, "valueOf", 0) { _, t, _, _ -> thisBigInt(t, "valueOf") }
        proto.value(JSSymbol.toStringTag, "BigInt", Attr.CONFIGURABLE)
    }
}

/** Inverse hyperbolic functions after fdlibm (s_asinh.c, e_acosh.c, e_atanh.c): log1p forms avoid cancellation. */
internal object MathExtra {
    private const val LN2 = 0.6931471805599453
    private const val TWO28 = 268435456.0

    fun asinh(x: Double): Double {
        if (x == 0.0 || !x.isFinite()) return x
        val a = Math.abs(x)
        val r = when {
            a > TWO28 -> Math.log(a) + LN2
            a > 2.0 -> Math.log(2.0 * a + 1.0 / (Math.sqrt(a * a + 1.0) + a))
            a < 1.0 / TWO28 -> a
            else -> Math.log1p(a + a * a / (1.0 + Math.sqrt(1.0 + a * a)))
        }
        return if (x < 0) -r else r
    }

    fun acosh(x: Double): Double = when {
        x.isNaN() || x < 1.0 -> Double.NaN
        x >= TWO28 -> if (x.isInfinite()) x else Math.log(x) + LN2
        x == 1.0 -> 0.0
        x > 2.0 -> Math.log(2.0 * x - 1.0 / (x + Math.sqrt(x * x - 1.0)))
        else -> {
            val t = x - 1.0
            Math.log1p(t + Math.sqrt(2.0 * t + t * t))
        }
    }

    fun atanh(x: Double): Double {
        if (x.isNaN()) return x
        val a = Math.abs(x)
        if (a > 1.0) return Double.NaN
        if (a == 1.0) return if (x > 0) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY
        if (a < 1.0 / TWO28) return x
        val r = if (a < 0.5) {
            val t = a + a
            0.5 * Math.log1p(t + t * a / (1.0 - a))
        } else 0.5 * Math.log1p((a + a) / (1.0 - a))
        return if (x < 0) -r else r
    }
}
