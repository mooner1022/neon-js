package dev.mooner.neonjs.runtime

import java.math.BigInteger
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

/** ECMAScript abstract operations. */
object Ops {
    private val doubleCache = Array(1024) { it.toDouble() }

    /** Boxes a double, reusing cached boxes for small integers. */
    @JvmStatic
    fun num(d: Double): Any {
        val i = d.toInt()
        if (i in 0..<1024 && i.toDouble() == d && !(d == 0.0 && 1.0 / d < 0)) return doubleCache[i]
        return d
    }

    // Written with separate returns: as an `if` expression Kotlin types the result `Double`, unboxes the cached box
    // and allocates a new one, which defeats the cache.
    @JvmStatic
    fun num(i: Int): Any {
        if (i in 0..<1024) return doubleCache[i]
        return i.toDouble()
    }

    @JvmStatic
    fun num(l: Long): Any {
        if (l in 0L..<1024L) return doubleCache[l.toInt()]
        return l.toDouble()
    }

    // ------------------------------------------------------------------ type tests

    @JvmStatic
    fun typeOf(v: Any?): String = when (v) {
        Undefined -> "undefined"
        Null -> "object"
        is Boolean -> "boolean"
        is Double -> "number"
        is CharSequence -> "string"
        is JSSymbol -> "symbol"
        is BigInteger -> "bigint"
        is JSObject -> if (v.special and JSObject.HTMLDDA != 0) "undefined" else if (v.isCallable) "function" else "object"
        null -> "undefined"
        else -> "object"
    }

    @JvmStatic fun isCallable(v: Any?): Boolean = v is JSObject && v.special and JSObject.CALLABLE != 0
    @JvmStatic fun isConstructor(v: Any?): Boolean = v is JSObject && v.special and JSObject.CONSTRUCTOR != 0

    // ------------------------------------------------------------------ conversions

    @JvmStatic
    fun toBoolean(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is Double -> !(v == 0.0 || v.isNaN())
        is CharSequence -> v.isNotEmpty()
        Undefined, Null, null -> false
        is BigInteger -> v.signum() != 0
        is JSObject -> v.special and JSObject.HTMLDDA == 0
        else -> true
    }

    const val HINT_DEFAULT = 0
    const val HINT_NUMBER = 1
    const val HINT_STRING = 2

    @JvmStatic
    fun toPrimitive(v: Any?, hint: Int = HINT_DEFAULT): Any? {
        if (v !is JSObject) return v
        val exotic = getMethod(v, JSSymbol.toPrimitive)
        if (exotic !== Undefined) {
            val hs = when (hint) { HINT_NUMBER -> "number"; HINT_STRING -> "string"; else -> "default" }
            val r = exotic.cast<JSObject>().call(v, arrayOf(hs))
            if (r is JSObject) throw JSException.typeError("Cannot convert object to primitive value")
            return r
        }
        return ordinaryToPrimitive(v, if (hint == HINT_STRING) HINT_STRING else HINT_NUMBER)
    }

    @JvmStatic
    fun ordinaryToPrimitive(o: JSObject, hint: Int): Any? {
        val first = if (hint == HINT_STRING) "toString" else "valueOf"
        val second = if (hint == HINT_STRING) "valueOf" else "toString"
        var m = o.get(first, o)
        if (isCallable(m)) {
            val r = m.cast<JSObject>().call(o, EMPTY_ARGS)
            if (r !is JSObject) return r
        }
        m = o.get(second, o)
        if (isCallable(m)) {
            val r = m.cast<JSObject>().call(o, EMPTY_ARGS)
            if (r !is JSObject) return r
        }
        throw JSException.typeError("Cannot convert object to primitive value")
    }

    @JvmStatic
    fun toNumber(v: Any?): Double = when (v) {
        is Double -> v
        is Boolean -> if (v) 1.0 else 0.0
        Undefined -> Double.NaN
        Null -> 0.0
        is CharSequence -> NumberConv.stringToNumber(v)
        is JSSymbol -> throw JSException.typeError("Cannot convert a Symbol value to a number")
        is BigInteger -> throw JSException.typeError("Cannot convert a BigInt value to a number")
        is JSObject -> toNumber(toPrimitive(v, HINT_NUMBER))
        null -> Double.NaN
        is Number -> v.toDouble()
        else -> Double.NaN
    }

    /** ToNumeric: returns Double or BigInteger. */
    @JvmStatic
    fun toNumeric(v: Any?): Any {
        if (v is Double) return v
        if (v is BigInteger) return v
        val p = toPrimitive(v, HINT_NUMBER)
        if (p is BigInteger) return p
        return toNumber(p)
    }

    @JvmStatic
    fun toIntegerOrInfinity(v: Any?): Double {
        val d = toNumber(v)
        return integerPart(d)
    }

    @JvmStatic
    fun integerPart(d: Double): Double {
        if (d != d) return 0.0
        if (d == Double.POSITIVE_INFINITY || d == Double.NEGATIVE_INFINITY) return d
        val t = if (d < 0) ceil(d) else floor(d)
        return if (t == 0.0) 0.0 else t
    }

    @JvmStatic
    fun toInt32(d: Double): Int {
        // below 2^63 in magnitude, truncating to a long is exact (NaN gives 0) and its low 32 bits are the result; the
        // saturated longs (2^63 and beyond, infinities) take the slow path. (Testing the int range first is slower on
        // ART and HotSpot alike, for large values.)
        val l = d.toLong()
        if (l != Long.MAX_VALUE && l != Long.MIN_VALUE) return l.toInt()
        if (d != d || d == Double.POSITIVE_INFINITY || d == Double.NEGATIVE_INFINITY) return 0
        val t = if (d < 0) ceil(d) else floor(d)
        val m = t % 4294967296.0
        return m.toLong().toInt()
    }

    @JvmStatic fun toInt32(v: Any?): Int = if (v is Double) toInt32(v) else toInt32(toNumber(v))
    @JvmStatic fun toUint32(v: Any?): Long = toInt32(v).toLong() and 0xFFFFFFFFL
    @JvmStatic fun toUint32(d: Double): Long = toInt32(d).toLong() and 0xFFFFFFFFL
    @JvmStatic fun toUint16(v: Any?): Int = toInt32(v) and 0xFFFF

    @JvmStatic
    fun toLength(v: Any?): Long {
        val len = toIntegerOrInfinity(v)
        if (len <= 0) return 0
        return if (len >= 9007199254740991.0) 9007199254740991L else len.toLong()
    }

    @JvmStatic
    fun toIndex(v: Any?): Long {
        if (v === Undefined) return 0
        val i = toIntegerOrInfinity(v)
        if (i < 0 || i > 9007199254740991.0) throw JSException.rangeError("Invalid index")
        return i.toLong()
    }

    @JvmStatic
    fun toString(v: Any?): String = when (v) {
        is String -> v
        is CharSequence -> v.toString()
        is Double -> NumberConv.toString(v)
        is Boolean -> if (v) "true" else "false"
        Undefined -> "undefined"
        Null -> "null"
        is BigInteger -> v.toString()
        is JSSymbol -> throw JSException.typeError("Cannot convert a Symbol value to a string")
        is JSObject -> toString(toPrimitive(v, HINT_STRING))
        null -> "undefined"
        else -> v.toString()
    }

    /** ToString keeping ropes (for concatenation). */
    @JvmStatic
    fun toJSString(v: Any?): CharSequence = v as? CharSequence ?: toString(v)

    @JvmStatic
    fun toPropertyKey(v: Any?): Any {
        when (v) {
            // an Int is never a JS value, only an already canonical array-index key (TO_KEY_FOR_BASE output)
            is Int -> return v
            is String -> return PK.fromString(v)
            is Double -> return PK.fromDouble(v)
            is JSSymbol -> return v
            is CharSequence -> return PK.fromString(v.toString())
        }
        val p = toPrimitive(v, HINT_STRING)
        if (p is JSSymbol) return p
        return PK.fromString(toString(p))
    }

    @JvmStatic
    fun toObject(realm: Realm, v: Any?): JSObject = when (v) {
        is JSObject -> v
        Undefined, Null, null -> throw JSException.typeError("Cannot convert undefined or null to object")
        is Boolean -> JSPrimitiveWrapper(realm.booleanPrototype, v)
        is Double -> JSPrimitiveWrapper(realm.numberPrototype, v)
        is CharSequence -> JSStringObject(realm.stringPrototype, v.toString())
        is JSSymbol -> JSPrimitiveWrapper(realm.symbolPrototype, v)
        is BigInteger -> JSPrimitiveWrapper(realm.bigintPrototype, v)
        else -> throw JSException.typeError("Cannot convert to object")
    }

    @JvmStatic fun toObject(v: Any?): JSObject = v as? JSObject ?: toObject(Agent.currentRealm(), v)

    @JvmStatic
    fun requireObjectCoercible(v: Any?): Any? {
        if (v === Undefined || v === Null || v == null) throw JSException.typeError("Cannot convert undefined or null to object")
        return v
    }

    @JvmStatic
    fun canonicalNumericIndexString(s: String): Double? {
        if (s == "-0") return -0.0
        val n = NumberConv.stringToNumber(s)
        if (s == "NaN" && n != n) return n
        if (n != n) return null
        if (NumberConv.toString(n) != s) return null
        return n
    }

    // ------------------------------------------------------------------ BigInt

    const val MAX_BIGINT_BITS = 1 shl 30

    @JvmStatic
    fun checkBigInt(b: BigInteger): BigInteger {
        if (b.bitLength() > MAX_BIGINT_BITS) throw JSException.rangeError("Maximum BigInt size exceeded")
        return b
    }

    @JvmStatic
    fun stringToBigInt(str: CharSequence): BigInteger? {
        val s = NumberConv.trim(str)
        if (s.isEmpty()) return BigInteger.ZERO
        try {
            if (s.length > 2 && s[0] == '0') {
                val radix = when (s[1]) { 'x', 'X' -> 16; 'o', 'O' -> 8; 'b', 'B' -> 2; else -> 0 }
                if (radix != 0) {
                    for (i in 2 until s.length) if (NumberConv.digitVal(s[i], radix) < 0) return null
                    return BigInteger(s.substring(2), radix)
                }
            }
            var i = 0
            if (s[0] == '+' || s[0] == '-') i++
            if (i == s.length) return null
            for (j in i until s.length) if (s[j] !in '0'..'9') return null
            return BigInteger(if (s[0] == '+') s.substring(1) else s)
        } catch (_: NumberFormatException) {
            return null
        }
    }

    @JvmStatic
    fun toBigInt(v: Any?): BigInteger {
        return when (val p = toPrimitive(v, HINT_NUMBER)) {
            is BigInteger -> p
            is Boolean -> if (p) BigInteger.ONE else BigInteger.ZERO
            is CharSequence -> stringToBigInt(p) ?: throw JSException.syntaxError("Cannot convert $p to a BigInt")
            Undefined, Null -> throw JSException.typeError("Cannot convert ${toString(p)} to a BigInt")
            is Double -> throw JSException.typeError("Cannot convert ${NumberConv.toString(p)} to a BigInt")
            is JSSymbol -> throw JSException.typeError("Cannot convert a Symbol value to a BigInt")
            else -> throw JSException.typeError("Cannot convert to BigInt")
        }
    }

    @JvmStatic
    fun bigIntFromDouble(d: Double): BigInteger = java.math.BigDecimal(d).toBigIntegerExact()

    @JvmStatic
    fun isIntegral(d: Double): Boolean = d == floor(d) && !d.isInfinite()

    // ------------------------------------------------------------------ equality

    @JvmStatic
    fun strictEquals(a: Any?, b: Any?): Boolean {
        if (a is Double) return b is Double && a == b
        if (a is CharSequence) return b is CharSequence && (a === b || contentEquals(a, b))
        if (a is BigInteger) return b is BigInteger && a == b
        if (a is Boolean) return b is Boolean && a == b
        return a === b
    }

    @JvmStatic
    fun contentEquals(a: CharSequence, b: CharSequence): Boolean = a.length == b.length && a.toString() == b.toString()

    @JvmStatic
    fun sameValue(a: Any?, b: Any?): Boolean {
        if (a is Double && b is Double) {
            if (a.isNaN()) return b.isNaN()
            if (a == 0.0 && b == 0.0) return (1.0 / a) == (1.0 / b)
            return a == b
        }
        return strictEquals(a, b)
    }

    @JvmStatic
    fun sameValueZero(a: Any?, b: Any?): Boolean {
        if (a is Double && b is Double) {
            if (a.isNaN()) return b.isNaN()
            return a == b
        }
        return strictEquals(a, b)
    }

    @JvmStatic
    fun looseEquals(a: Any?, b: Any?): Boolean {
        var x = a
        var y = b
        while (true) {
            if (x is Double && y is Double) return x == y
            if (x is CharSequence && y is CharSequence) return contentEquals(x, y)
            if (sameType(x, y)) return strictEquals(x, y)
            val xn = x === Undefined || x === Null || (x is JSObject && x.special and JSObject.HTMLDDA != 0)
            val yn = y === Undefined || y === Null || (y is JSObject && y.special and JSObject.HTMLDDA != 0)
            if ((x === Undefined || x === Null) && yn) return true
            if ((y === Undefined || y === Null) && xn) return true
            if (x === Undefined || x === Null || y === Undefined || y === Null) return false
            if (x is Double && y is CharSequence) return x == NumberConv.stringToNumber(y)
            if (x is CharSequence && y is Double) return NumberConv.stringToNumber(x) == y
            if (x is BigInteger && y is CharSequence) {
                val n = stringToBigInt(y) ?: return false
                return x == n
            }
            if (x is CharSequence && y is BigInteger) {
                val n = stringToBigInt(x) ?: return false
                return n == y
            }
            if (x is Boolean) { x = if (x) 1.0 else 0.0; continue }
            if (y is Boolean) { y = if (y) 1.0 else 0.0; continue }
            if ((x is Double || x is CharSequence || x is BigInteger || x is JSSymbol) && y is JSObject) { y = toPrimitive(y); continue }
            if (x is JSObject && (y is Double || y is CharSequence || y is BigInteger || y is JSSymbol)) { x = toPrimitive(x); continue }
            if (x is BigInteger && y is Double) return bigIntEqualsNumber(x, y)
            if (x is Double && y is BigInteger) return bigIntEqualsNumber(y, x)
            return false
        }
    }

    private fun bigIntEqualsNumber(b: BigInteger, d: Double): Boolean {
        if (d != d || d.isInfinite()) return false
        if (d != floor(d)) return false
        return b == bigIntFromDouble(d)
    }

    private fun sameType(x: Any?, y: Any?): Boolean = when (x) {
        Undefined -> y === Undefined
        Null -> y === Null
        is Boolean -> y is Boolean
        is Double -> y is Double
        is CharSequence -> y is CharSequence
        is JSSymbol -> y is JSSymbol
        is BigInteger -> y is BigInteger
        is JSObject -> y is JSObject
        else -> false
    }

    // ------------------------------------------------------------------ relational

    /** IsLessThan(x, y); returns null for undefined (NaN involved). Operands must already be primitives. */
    @JvmStatic
    fun lessThanPrim(px: Any?, py: Any?): Boolean? {
        if (px is CharSequence && py is CharSequence) return px.toString() < py.toString()
        if (px is BigInteger && py is CharSequence) {
            val ny = stringToBigInt(py) ?: return null
            return px < ny
        }
        if (px is CharSequence && py is BigInteger) {
            val nx = stringToBigInt(px) ?: return null
            return nx < py
        }
        val nx = px as? BigInteger ?: toNumber(px)
        val ny = py as? BigInteger ?: toNumber(py)
        if (nx is Double && ny is Double) {
            if (nx.isNaN() || ny.isNaN()) return null
            return nx < ny
        }
        if (nx is BigInteger && ny is BigInteger) return nx < ny
        if (nx is BigInteger) {
            val d = ny as Double
            if (d != d) return null
            if (d == Double.POSITIVE_INFINITY) return true
            if (d == Double.NEGATIVE_INFINITY) return false
            return java.math.BigDecimal(nx) < java.math.BigDecimal(d)
        }
        val d = nx as Double
        if (d != d) return null
        if (d == Double.POSITIVE_INFINITY) return false
        if (d == Double.NEGATIVE_INFINITY) return true
        return java.math.BigDecimal(d) < java.math.BigDecimal(ny as BigInteger)
    }

    @JvmStatic
    fun lt(a: Any?, b: Any?): Boolean {
        if (a is Double && b is Double) return a < b
        val pa = toPrimitive(a, HINT_NUMBER)
        val pb = toPrimitive(b, HINT_NUMBER)
        return lessThanPrim(pa, pb) == true
    }

    @JvmStatic
    fun gt(a: Any?, b: Any?): Boolean {
        if (a is Double && b is Double) return a > b
        val pa = toPrimitive(a, HINT_NUMBER)
        val pb = toPrimitive(b, HINT_NUMBER)
        return lessThanPrim(pb, pa) == true
    }

    @JvmStatic
    fun le(a: Any?, b: Any?): Boolean {
        if (a is Double && b is Double) return a <= b
        val pa = toPrimitive(a, HINT_NUMBER)
        val pb = toPrimitive(b, HINT_NUMBER)
        val r = lessThanPrim(pb, pa)
        return r == false
    }

    @JvmStatic
    fun ge(a: Any?, b: Any?): Boolean {
        if (a is Double && b is Double) return a >= b
        val pa = toPrimitive(a, HINT_NUMBER)
        val pb = toPrimitive(b, HINT_NUMBER)
        val r = lessThanPrim(pa, pb)
        return r == false
    }

    // ------------------------------------------------------------------ arithmetic

    @JvmStatic
    fun add(a: Any?, b: Any?): Any? {
        if (a is Double && b is Double) return a + b
        if (a is CharSequence && b is CharSequence) return Rope.concat(a, b)
        val pa = toPrimitive(a)
        val pb = toPrimitive(b)
        if (pa is CharSequence || pb is CharSequence) return Rope.concat(toJSString(pa), toJSString(pb))
        val na = toNumericPrim(pa)
        val nb = toNumericPrim(pb)
        if (na is Double && nb is Double) return na + nb
        if (na is BigInteger && nb is BigInteger) return checkBigInt(na.add(nb))
        throw mixError()
    }

    private fun toNumericPrim(p: Any?): Any = p as? BigInteger ?: toNumber(p)

    private fun mixError() = JSException.typeError("Cannot mix BigInt and other types, use explicit conversions")

    @JvmStatic
    fun sub(a: Any?, b: Any?): Any? {
        if (a is Double && b is Double) return a - b
        val na = toNumeric(a)
        val nb = toNumeric(b)
        if (na is Double && nb is Double) return na - nb
        if (na is BigInteger && nb is BigInteger) return checkBigInt(na.subtract(nb))
        throw mixError()
    }

    @JvmStatic
    fun mul(a: Any?, b: Any?): Any? {
        if (a is Double && b is Double) return a * b
        val na = toNumeric(a)
        val nb = toNumeric(b)
        if (na is Double && nb is Double) return na * nb
        if (na is BigInteger && nb is BigInteger) {
            if (na.bitLength().toLong() + nb.bitLength() > MAX_BIGINT_BITS) throw JSException.rangeError("Maximum BigInt size exceeded")
            return na.multiply(nb)
        }
        throw mixError()
    }

    @JvmStatic
    fun div(a: Any?, b: Any?): Any? {
        if (a is Double && b is Double) return a / b
        val na = toNumeric(a)
        val nb = toNumeric(b)
        if (na is Double && nb is Double) return na / nb
        if (na is BigInteger && nb is BigInteger) {
            if (nb.signum() == 0) throw JSException.rangeError("Division by zero")
            return na.divide(nb)
        }
        throw mixError()
    }

    @JvmStatic
    fun mod(a: Any?, b: Any?): Any? {
        if (a is Double && b is Double) return a % b
        val na = toNumeric(a)
        val nb = toNumeric(b)
        if (na is Double && nb is Double) return na % nb
        if (na is BigInteger && nb is BigInteger) {
            if (nb.signum() == 0) throw JSException.rangeError("Division by zero")
            return na.rem(nb)
        }
        throw mixError()
    }

    @JvmStatic
    fun exp(a: Any?, b: Any?): Any? {
        val na = toNumeric(a)
        val nb = toNumeric(b)
        if (na is Double && nb is Double) return pow(na, nb)
        if (na is BigInteger && nb is BigInteger) return bigPow(na, nb)
        throw mixError()
    }

    @JvmStatic
    fun pow(x: Double, y: Double): Double {
        if (y != y) return Double.NaN
        if (y == 0.0) return 1.0
        if ((x == 1.0 || x == -1.0) && y.isInfinite()) return Double.NaN
        return x.pow(y)
    }

    @JvmStatic
    fun bigPow(base: BigInteger, e: BigInteger): BigInteger {
        if (e.signum() < 0) throw JSException.rangeError("Exponent must be non-negative")
        if (e.signum() == 0) return BigInteger.ONE
        if (base.signum() == 0 || base == BigInteger.ONE) return base
        if (base == BigInteger.ONE.negate()) return if (e.testBit(0)) base else BigInteger.ONE
        if (e.bitLength() > 31) throw JSException.rangeError("Maximum BigInt size exceeded")
        val ei = e.toInt()
        if (base.bitLength().toLong() * ei > MAX_BIGINT_BITS) throw JSException.rangeError("Maximum BigInt size exceeded")
        return base.pow(ei)
    }

    @JvmStatic
    fun neg(a: Any?): Any? {
        if (a is Double) return -a
        val n = toNumeric(a)
        if (n is Double) return -n
        return n.cast<BigInteger>().negate()
    }

    @JvmStatic
    fun bitNot(a: Any?): Any? {
        val n = toNumeric(a)
        if (n is Double) return toInt32(n).inv().toDouble()
        return n.cast<BigInteger>().not()
    }

    @JvmStatic
    fun inc(a: Any?): Any? {
        if (a is Double) return a + 1.0
        if (a is BigInteger) return a.add(BigInteger.ONE)
        throw IllegalStateException("inc on non-numeric")
    }

    @JvmStatic
    fun dec(a: Any?): Any? {
        if (a is Double) return a - 1.0
        if (a is BigInteger) return a.subtract(BigInteger.ONE)
        throw IllegalStateException("dec on non-numeric")
    }

    private inline fun bitwise(a: Any?, b: Any?, opI: (Int, Int) -> Int, opB: (BigInteger, BigInteger) -> BigInteger): Any? {
        if (a is Double && b is Double) return opI(toInt32(a), toInt32(b)).toDouble()
        val na = toNumeric(a)
        val nb = toNumeric(b)
        if (na is Double && nb is Double) return opI(toInt32(na), toInt32(nb)).toDouble()
        if (na is BigInteger && nb is BigInteger) return opB(na, nb)
        throw mixError()
    }

    @JvmStatic fun bitAnd(a: Any?, b: Any?): Any? = bitwise(a, b, { x, y -> x and y }, { x, y -> x.and(y) })
    @JvmStatic fun bitOr(a: Any?, b: Any?): Any? = bitwise(a, b, { x, y -> x or y }, { x, y -> x.or(y) })
    @JvmStatic fun bitXor(a: Any?, b: Any?): Any? = bitwise(a, b, { x, y -> x xor y }, { x, y -> x.xor(y) })

    @JvmStatic
    fun shl(a: Any?, b: Any?): Any? = bitwise(a, b, { x, y -> x shl (y and 31) }, { x, y -> bigShift(x, y) })

    @JvmStatic
    fun sar(a: Any?, b: Any?): Any? = bitwise(a, b, { x, y -> x shr (y and 31) }, { x, y -> bigShift(x, y.negate()) })

    @JvmStatic
    fun shr(a: Any?, b: Any?): Any? {
        if (a is Double && b is Double) return ((toInt32(a).toLong() and 0xFFFFFFFFL) ushr (toInt32(b) and 31)).toDouble()
        val na = toNumeric(a)
        val nb = toNumeric(b)
        if (na is Double && nb is Double) return ((toInt32(na).toLong() and 0xFFFFFFFFL) ushr (toInt32(nb) and 31)).toDouble()
        if (na is BigInteger && nb is BigInteger) throw JSException.typeError("BigInts have no unsigned right shift, use >> instead")
        throw mixError()
    }

    private fun bigShift(x: BigInteger, y: BigInteger): BigInteger {
        if (x.signum() == 0) return x
        if (y.bitLength() > 31) {
            if (y.signum() < 0) return if (x.signum() < 0) BigInteger.ONE.negate() else BigInteger.ZERO
            throw JSException.rangeError("Maximum BigInt size exceeded")
        }
        val n = y.toInt()
        if (n > 0) {
            if (x.bitLength().toLong() + n > MAX_BIGINT_BITS) throw JSException.rangeError("Maximum BigInt size exceeded")
            return x.shiftLeft(n)
        }
        return x.shiftRight(-n)
    }

    // ------------------------------------------------------------------ objects & properties

    /** GetV: property get on any value (primitives use their prototype). */
    @JvmStatic
    fun getV(realm: Realm, v: Any?, key: Any): Any? {
        when (v) {
            is JSObject -> return v.get(key, v)
            is CharSequence -> {
                if (key == "length") return v.length.toDouble()
                if (key is Int) {
                    if (key < v.length) return v[key].toString()
                }
                return realm.stringPrototype.get(key, v)
            }
            is Double -> return realm.numberPrototype.get(key, v)
            is Boolean -> return realm.booleanPrototype.get(key, v)
            is JSSymbol -> return realm.symbolPrototype.get(key, v)
            is BigInteger -> return realm.bigintPrototype.get(key, v)
            Undefined, Null, null -> throw JSException.typeError("Cannot read properties of ${toString(v)} (reading '${PK.toStringKey(key)}')")
            else -> throw JSException.typeError("Cannot read property of host value")
        }
    }

    @JvmStatic fun getV(v: Any?, key: Any): Any? = if (v is JSObject) v.get(key, v) else getV(Agent.currentRealm(), v, key)

    /** PutValue for property references; returns false on failure (caller decides on strict-mode error). */
    @JvmStatic
    fun putV(realm: Realm, base: Any?, key: Any, value: Any?): Boolean {
        if (base is JSObject) return base.set(key, value, base)
        val proto = when (base) {
            is CharSequence -> {
                if (key == "length" || (key is Int && key < base.length)) return false
                realm.stringPrototype
            }
            is Double -> realm.numberPrototype
            is Boolean -> realm.booleanPrototype
            is JSSymbol -> realm.symbolPrototype
            is BigInteger -> realm.bigintPrototype
            else -> throw JSException.typeError("Cannot set properties of ${toString(base)} (setting '${PK.toStringKey(key)}')")
        }
        return proto.set(key, value, base)
    }

    @JvmStatic
    fun getMethod(v: Any?, key: Any): Any? {
        val f = getV(v, key)
        if (f === Undefined || f === Null) return Undefined
        if (!isCallable(f)) throw JSException.typeError("${describeKey(key)} is not a function")
        return f
    }

    @JvmStatic
    fun call(f: Any?, thisArg: Any?, args: Array<Any?>): Any? {
        if (f is JSObject && f.special and JSObject.CALLABLE != 0) return f.call(thisArg, args)
        throw JSException.typeError("${describe(f)} is not a function")
    }

    @JvmStatic fun call(f: Any?, thisArg: Any?): Any? = call(f, thisArg, EMPTY_ARGS)

    @JvmStatic
    fun construct(f: Any?, args: Array<Any?>, newTarget: Any? = f): Any? {
        if (f is JSObject && f.special and JSObject.CONSTRUCTOR != 0) return f.construct(args, newTarget.cast<JSObject>())
        throw JSException.typeError("${describe(f)} is not a constructor")
    }

    @JvmStatic
    fun invoke(v: Any?, key: Any, args: Array<Any?>): Any? {
        val f = getV(v, key)
        return call(f, v, args)
    }

    @JvmStatic
    fun lengthOfArrayLike(o: JSObject): Long {
        if (o is JSArray) return o.length
        return toLength(o.get("length", o))
    }

    @JvmStatic
    fun createListFromArrayLike(v: Any?, onlyPropertyKeys: Boolean = false): Array<Any?> {
        if (v !is JSObject) throw JSException.typeError("CreateListFromArrayLike called on non-object")
        val len = lengthOfArrayLike(v)
        if (len > 10_000_000) throw JSException.rangeError("Too many arguments")
        val agent = Agent.current.get()
        if (len > 8192) agent?.reserveAllocation(len * 8)
        val out = arrayOfNulls<Any?>(len.toInt())
        for (i in 0 until len.toInt()) {
            if (i and 1023 == 1023) agent?.checkInterrupt()
            val e = v.get(i, v)
            if (onlyPropertyKeys && !(e is CharSequence || e is JSSymbol)) throw JSException.typeError("Invalid property key")
            out[i] = if (e is CharSequence) e.toString() else e
        }
        return out
    }

    @JvmStatic
    fun isArray(v: Any?): Boolean {
        if (v is JSArray) return true
        if (v is ProxyObject) {
            val t = v.target ?: throw JSException.typeError("Cannot perform 'IsArray' on a proxy that has been revoked")
            return isArray(t)
        }
        return false
    }

    @JvmStatic
    fun instanceOf(v: Any?, target: Any?): Boolean {
        if (target !is JSObject) throw JSException.typeError("Right-hand side of 'instanceof' is not an object")
        val handler = getMethod(target, JSSymbol.hasInstance)
        if (handler !== Undefined) return toBoolean(handler.cast<JSObject>().call(target, arrayOf(v)))
        if (!target.isCallable) throw JSException.typeError("Right-hand side of 'instanceof' is not callable")
        return ordinaryHasInstance(target, v)
    }

    @JvmStatic
    fun ordinaryHasInstance(c: Any?, o: Any?): Boolean {
        if (!isCallable(c)) return false
        if (c is BoundFunction) return instanceOf(o, c.target)
        if (o !is JSObject) return false
        val p = c.cast<JSObject>().get("prototype", c)
        if (p !is JSObject) throw JSException.typeError("Function has non-object prototype in instanceof check")
        var x: JSObject? = o
        while (true) {
            x = x!!.getPrototypeOf() ?: return false
            if (x === p) return true
        }
    }

    @JvmStatic
    fun hasPropertyOp(key: Any?, target: Any?): Boolean {
        if (target !is JSObject) throw JSException.typeError("Cannot use 'in' operator to search for '${if (key is JSSymbol) key.toString() else toDisplayString(key)}' in ${toDisplayString(target)}")
        return target.hasProperty(toPropertyKey(key))
    }

    @JvmStatic
    fun getFunctionRealm(obj: JSObject): Realm {
        if (obj is JSFunction) return obj.realm
        if (obj is BoundFunction) return getFunctionRealm(obj.target)
        if (obj is ProxyObject) {
            val t = obj.target ?: throw JSException.typeError("Cannot perform operation on a revoked proxy")
            return getFunctionRealm(t)
        }
        return Agent.currentRealm()
    }

    @JvmStatic
    fun getPrototypeFromConstructor(ctor: JSObject, default: (Realm) -> JSObject): JSObject {
        val p = ctor.get("prototype", ctor)
        if (p is JSObject) return p
        return default(getFunctionRealm(ctor))
    }

    @JvmStatic
    fun speciesConstructor(o: JSObject, default: JSObject): JSObject {
        val c = o.get("constructor", o)
        if (c === Undefined) return default
        if (c !is JSObject) throw JSException.typeError("object.constructor is not an object")
        val s = c.get(JSSymbol.species, c)
        if (s === Undefined || s === Null) return default
        if (isConstructor(s)) return s as JSObject
        throw JSException.typeError("object.constructor[Symbol.species] is not a constructor")
    }

    // ------------------------------------------------------------------ property descriptors

    @JvmStatic
    fun toPropertyDescriptor(v: Any?): PropertyDescriptor {
        if (v !is JSObject) throw JSException.typeError("Property description must be an object: ${toDisplayString(v)}")
        val d = PropertyDescriptor()
        if (v.hasProperty("enumerable")) d.enumerable(toBoolean(v.get("enumerable", v)))
        if (v.hasProperty("configurable")) d.configurable(toBoolean(v.get("configurable", v)))
        if (v.hasProperty("value")) d.value(v.get("value", v))
        if (v.hasProperty("writable")) d.writable(toBoolean(v.get("writable", v)))
        if (v.hasProperty("get")) {
            val g = v.get("get", v)
            if (g !== Undefined && !isCallable(g)) throw JSException.typeError("Getter must be a function: ${toDisplayString(g)}")
            d.getter(g)
        }
        if (v.hasProperty("set")) {
            val s = v.get("set", v)
            if (s !== Undefined && !isCallable(s)) throw JSException.typeError("Setter must be a function: ${toDisplayString(s)}")
            d.setter(s)
        }
        if (d.isAccessor && d.isData) throw JSException.typeError("Invalid property descriptor. Cannot both specify accessors and a value or writable attribute")
        return d
    }

    @JvmStatic
    fun fromPropertyDescriptor(realm: Realm, d: PropertyDescriptor?): Any? {
        if (d == null) return Undefined
        val o = JSObject(realm.objectPrototype)
        if (d.hasValue) o.createDataProperty("value", d.value)
        if (d.hasWritable) o.createDataProperty("writable", d.writable)
        if (d.hasGet) o.createDataProperty("get", d.getter)
        if (d.hasSet) o.createDataProperty("set", d.setter)
        if (d.hasEnumerable) o.createDataProperty("enumerable", d.enumerable)
        if (d.hasConfigurable) o.createDataProperty("configurable", d.configurable)
        return o
    }

    @JvmStatic
    fun completePropertyDescriptor(d: PropertyDescriptor): PropertyDescriptor {
        if (d.isGeneric || d.isData) {
            if (!d.hasValue) d.value(Undefined)
            if (!d.hasWritable) d.writable(false)
        } else {
            if (!d.hasGet) d.getter(Undefined)
            if (!d.hasSet) d.setter(Undefined)
        }
        if (!d.hasEnumerable) d.enumerable(false)
        if (!d.hasConfigurable) d.configurable(false)
        return d
    }

    /** ValidateAndApplyPropertyDescriptor with O = undefined. */
    @JvmStatic
    fun isCompatiblePropertyDescriptor(extensible: Boolean, desc: PropertyDescriptor, current: PropertyDescriptor?): Boolean {
        if (current == null) return extensible
        if (desc.present == 0) return true
        if (!current.configurable) {
            if (desc.hasConfigurable && desc.configurable) return false
            if (desc.hasEnumerable && desc.enumerable != current.enumerable) return false
            if (!desc.isGeneric && desc.isAccessor != current.isAccessor) return false
            if (current.isAccessor) {
                if (desc.hasGet && !sameValue(desc.getter, current.getter)) return false
                if (desc.hasSet && !sameValue(desc.setter, current.setter)) return false
            } else if (!current.writable) {
                if (desc.hasWritable && desc.writable) return false
                if (desc.hasValue && !sameValue(desc.value, current.value)) return false
            }
        }
        return true
    }

    // ------------------------------------------------------------------ descriptions for messages

    @JvmStatic
    fun describe(v: Any?): String = when (v) {
        is CharSequence -> "\"${if (v.length > 40) v.subSequence(0, 40).toString() + "..." else v}\""
        is JSFunction -> "function ${v.debugName()}".trimEnd()
        is JSArray -> "[object Array]"
        is JSObject -> if (v.isCallable) "function" else "#<${constructorNameOf(v)}>"
        is JSSymbol -> v.toString()
        is BigInteger -> "${v}n"
        else -> toDisplayString(v)
    }

    private fun constructorNameOf(o: JSObject): String {
        return try {
            var p: JSObject? = o
            var depth = 0
            while (p != null && depth < 32) {
                val pm = p.props
                if (pm != null) {
                    val i = pm.find("constructor")
                    if (i >= 0 && pm.flags[i] and Attr.ACCESSOR == 0) {
                        val c = pm.values[i]
                        if (c is JSFunction) {
                            val n = c.debugName()
                            if (n.isNotEmpty()) return n
                        }
                    }
                }
                p = p.proto
                depth++
            }
            o.className
        } catch (_: Throwable) {
            o.className
        }
    }

    @JvmStatic
    fun describeKey(key: Any): String = if (key is JSSymbol) key.toString() else PK.toStringKey(key)

    /** String conversion for display/messages that never invokes user code. */
    @JvmStatic
    fun toDisplayString(v: Any?): String = when (v) {
        is CharSequence -> v.toString()
        is Double -> NumberConv.toString(v)
        is Boolean -> v.toString()
        Undefined, null -> "undefined"
        Null -> "null"
        is BigInteger -> v.toString()
        is JSSymbol -> v.toString()
        is JSObject -> if (v.isCallable) "function" else "[object ${v.className}]"
        else -> v.toString()
    }
}
