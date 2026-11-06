package dev.mooner.neonpython

import java.math.BigInteger

/**
 * `math` (docs/PYTHON_PLATFORM.md §12, S2), as CPython 3.14 has it: Modules/mathmodule.c in Kotlin, its messages,
 * its own algorithms ported as they are (gamma, lgamma and sinpi, remainder, the logarithms of ints of any size,
 * hypot's and dist's vector norm, fsum, isqrt, factorial, comb and perm, sumprod, prod, nextafter's steps, ulp,
 * isclose, ldexp, frexp, modf, fma's errors, the ints of floor, ceil, trunc, gcd and lcm). What CPython takes from
 * libm is java.lang.Math's (the platform's, as CPython's is the platform's C library; its last digit can differ from
 * one libm's to another's); where Java has none, ports of fdlibm (erf, erfc, acosh, asinh, atanh) and of FreeBSD's
 * log2. A C long is 64 bits here (LP64, as on Linux and Android): factorial's limit is 2**63-1.
 */
object PyMath {
    private val NO_ARGS = arrayOfNulls<Any?>(0)
    private val STOP = PyIterator.STOP
    private val TWO_64: BigInteger = BigInteger.ONE.shiftLeft(64)
    private const val DBL_MIN = java.lang.Double.MIN_NORMAL
    private const val TWO54 = 1.80143985094819840000e+16
    private const val EDOM = 33
    private const val ERANGE = 34

    const val DOC = "This module provides access to the mathematical functions\ndefined by the C standard."

    private fun ctx() = PyContext.current()
    private fun valueError(msg: String) = PyException("ValueError", msg)
    private fun overflowError(msg: String) = PyException("OverflowError", msg)
    private fun domainError() = valueError("math domain error")
    private fun rangeError() = overflowError("math range error")
    private fun s(n: Int) = if (n == 1) "" else "s"

    // ------------------------------------------------------------------ arguments

    /** [f], a class's special method, called on [v] (CPython's lookup of one: bound as a descriptor). */
    private fun callSpecial(f: Any?, v: Any?): Any? {
        val c = ctx()
        if (f is PyFunction) return PyCalls.callWith(c, f, arrayOf(v), NO_ARGS, NO_ARGS)
        return PyCalls.callWith(c, PyClasses.descrGet(c, f, v, PyTypes.typeOf(v)), NO_ARGS, NO_ARGS, NO_ARGS)
    }

    /** The special method [n] [v]'s class (a class's metaclass) has of its own (a builtin value's: none). */
    private fun special(v: Any?, n: String): Any? =
        if (PyRt.special(v) || PyRt.metaSpecial(v)) PyDunder.userMethod(PyTypes.typeOf(v), n) else null

    /** An int too large for a double: CPython's PyLong_AsDouble. */
    private fun bigDouble(b: BigInteger): Double {
        val d = b.toDouble()
        if (d.isInfinite()) throw overflowError("int too large to convert to float")
        return d
    }

    /**
     * [v] as a C double, as PyFloat_AsDouble takes it: a float (a subclass's instance's value, its __float__ or not),
     * an int, what a class's __float__ returns (else its __index__).
     */
    @JvmStatic
    fun asDouble(v: Any?): Double = when (v) {
        is Double -> v
        is Long -> v.toDouble()
        is PyBool -> if (v.value) 1.0 else 0.0
        is BigInteger -> bigDouble(v)
        else -> objectDouble(v)
    }

    private fun objectDouble(v: Any?): Double {
        if (v is PyNumSub && v.num is Double) return v.num
        val f = special(v, "__float__")
        if (f != null) return when (val r = callSpecial(f, v)) {
            is Double -> r
            // (a float subclass's instance: CPython warns, DeprecationWarning, and takes its value)
            is PyNumSub -> r.num as? Double ?: throw PyRt.typeError("${PyTypes.name(v)}.__float__ returned non-float (type ${PyTypes.name(r)})")
            else -> throw PyRt.typeError("${PyTypes.name(v)}.__float__ returned non-float (type ${PyTypes.name(r)})")
        }
        if (v is PyNumSub) return asDouble(v.num)
        return asDouble(PyRt.indexValue(v) ?: throw PyRt.typeError("must be real number, not ${PyTypes.name(v)}"))
    }

    /** [v] as an int (PyNumber_Index): a Long or a BigInteger past 64 bits. */
    private fun index(v: Any?): Any =
        PyRt.indexValue(v) ?: throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer")

    /** Whether [v] is an int (PyLong_Check: a bool, a subclass's instance too), and its value. */
    private fun intValue(v: Any?): Any? = when (v) {
        is Long, is BigInteger -> v
        is PyBool -> if (v.value) 1L else 0L
        is PyNumSub -> v.num.takeIf { it !is Double }
        else -> null
    }

    private fun big(v: Any): BigInteger = v as? BigInteger ?: BigInteger.valueOf(v as Long)
    private fun signum(v: Any): Int = if (v is Long) java.lang.Long.signum(v) else (v as BigInteger).signum()
    private fun unsigned(v: Long): BigInteger = if (v >= 0) BigInteger.valueOf(v) else BigInteger.valueOf(v).add(TWO_64)
    private fun absInt(v: Any): Any = when {
        v is Long && v != Long.MIN_VALUE -> Math.abs(v)
        else -> PyRt.norm(big(v).abs())
    }

    /** An int of a float's integral value: PyLong_FromDouble. */
    private fun intOf(x: Double): Any {
        if (x.isNaN()) throw valueError("cannot convert float NaN to integer")
        if (x.isInfinite()) throw overflowError("cannot convert float infinity to integer")
        if (x > -9.223372036854775808E18 && x < 9.223372036854775808E18) return x.toLong()
        return PyRt.norm(java.math.BigDecimal(x).toBigInteger())
    }

    /** _PyArg_CheckPositional's messages: "atan2 expected 2 arguments, got 1". */
    private fun checkPositional(name: String, n: Int, min: Int, max: Int) {
        if (n < min) throw PyRt.typeError("$name expected ${if (min == max) "" else "at least "}$min argument${s(min)}, got $n")
        if (n > max) throw PyRt.typeError("$name expected ${if (min == max) "" else "at most "}$max argument${s(max)}, got $n")
    }

    /**
     * Arguments as Argument Clinic's parser takes them (_PyArg_UnpackKeywords): [names] in order, the first [posOnly]
     * by position only, the first [maxPos] by position, the first [minPos] required; its messages, 3.14's suggestion
     * of a keyword too.
     */
    @JvmStatic
    internal fun clinic(fn: String, a: Array<Any?>, kw: PyDict?, names: List<String>, posOnly: Int, minPos: Int, maxPos: Int): Array<Any?> {
        val given = LinkedHashMap<String, Any?>()
        if (kw != null) for ((k, v) in kw.map) given[(k.v as PyStr).s] = v
        val nargs = a.size
        val maxArgs = names.size
        if (nargs + given.size > maxArgs) {
            throw PyRt.typeError("$fn() takes at most $maxArgs ${if (nargs == 0) "keyword " else ""}argument${s(maxArgs)} (${nargs + given.size} given)")
        }
        if (nargs > maxPos) {
            throw PyRt.typeError(if (maxPos == 0) "$fn() takes no positional arguments"
                else "$fn() takes ${if (minPos < maxPos) "at most" else "exactly"} $maxPos positional argument${s(maxPos)} ($nargs given)")
        }
        val minPosOnly = minOf(posOnly, minPos)
        if (nargs < minPosOnly) {
            throw PyRt.typeError("$fn() takes ${if (minPosOnly < maxPos) "at least" else "exactly"} $minPosOnly positional argument${s(minPosOnly)} ($nargs given)")
        }
        val out = arrayOfNulls<Any?>(maxArgs)
        a.copyInto(out)
        var nkw = given.size
        for (i in maxOf(nargs, posOnly) until maxArgs) {
            val cur: Any?
            if (nkw > 0) cur = given[names[i]]
            else if (i >= minPos) break
            else cur = null
            out[i] = cur
            if (cur != null) nkw--
            else if (i < minPos) throw PyRt.typeError("$fn() missing required argument '${names[i]}' (pos ${i + 1})")
        }
        if (nkw > 0) {
            for (i in posOnly until nargs) {
                if (given.containsKey(names[i])) throw PyRt.typeError("argument for $fn() given by name ('${names[i]}') and position (${i + 1})")
            }
            val kwNames = names.subList(posOnly, names.size)
            for (k in given.keys) {
                if (k in kwNames) continue
                val hint = suggestion(kwNames, k)
                throw PyRt.typeError("$fn() got an unexpected keyword argument '$k'" + if (hint != null) ". Did you mean '$hint'?" else "")
            }
            throw PyRt.typeError("invalid keyword argument for $fn()")
        }
        return out
    }

    /** The closest of [candidates] to [name], as CPython's _Py_CalculateSuggestions finds it (Python/suggestions.c). */
    private fun suggestion(candidates: List<String>, name: String): String? {
        val n = name.toByteArray(Charsets.UTF_8)
        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        for (item in candidates) {
            if (item == name) continue
            val b = item.toByteArray(Charsets.UTF_8)
            // (no more than a third of the characters changed)
            val max = minOf((n.size + b.size + 3) * 2 / 6, bestDistance - 1)
            val d = levenshtein(n, b, max)
            if (d > max) continue
            if (best == null || d < bestDistance) {
                best = item
                bestDistance = d
            }
        }
        return best
    }

    private fun substitutionCost(a0: Byte, b0: Byte): Int {
        var a = a0.toInt()
        var b = b0.toInt()
        if ((a and 31) != (b and 31)) return 2
        if (a == b) return 0
        if (a in 'A'.code..'Z'.code) a += 'a'.code - 'A'.code
        if (b in 'A'.code..'Z'.code) b += 'a'.code - 'A'.code
        return if (a == b) 1 else 2
    }

    private fun levenshtein(a0: ByteArray, b0: ByteArray, maxCost: Int): Int {
        var aStart = 0
        var bStart = 0
        var aSize = a0.size
        var bSize = b0.size
        // (common affixes trimmed)
        while (aSize > 0 && bSize > 0 && a0[aStart] == b0[bStart]) { aStart++; aSize--; bStart++; bSize-- }
        while (aSize > 0 && bSize > 0 && a0[aStart + aSize - 1] == b0[bStart + bSize - 1]) { aSize--; bSize-- }
        if (aSize == 0 || bSize == 0) return (aSize + bSize) * 2
        if (aSize > 40 || bSize > 40) return maxCost + 1
        var a = a0
        var b = b0
        if (bSize < aSize) {
            a = b0.also { b = a0 }
            aStart = bStart.also { bStart = aStart }
            aSize = bSize.also { bSize = aSize }
        }
        if ((bSize - aSize) * 2 > maxCost) return maxCost + 1
        val buffer = IntArray(aSize)
        var tmp = 2
        for (i in 0 until aSize) { buffer[i] = tmp; tmp += 2 }
        var result = 0
        for (bi in 0 until bSize) {
            val code = b[bStart + bi]
            var distance = bi * 2
            result = distance
            var minimum = Int.MAX_VALUE
            for (i in 0 until aSize) {
                val substitute = distance + substitutionCost(code, a[aStart + i])
                distance = buffer[i]
                val insertDelete = minOf(result, distance) + 2
                result = minOf(insertDelete, substitute)
                buffer[i] = result
                if (result < minimum) minimum = result
            }
            if (minimum > maxCost) return maxCost + 1
        }
        return result
    }

    // ------------------------------------------------------------------ what libm gives (Java's, and ports of fdlibm)

    private fun hi(x: Double): Int = (java.lang.Double.doubleToRawLongBits(x) ushr 32).toInt()
    private fun lo(x: Double): Int = java.lang.Double.doubleToRawLongBits(x).toInt()
    private fun withHigh(x: Double, h: Int): Double =
        java.lang.Double.longBitsToDouble((h.toLong() shl 32) or (java.lang.Double.doubleToRawLongBits(x) and 0xffffffffL))
    private fun withLow(x: Double, l: Int): Double =
        java.lang.Double.longBitsToDouble((java.lang.Double.doubleToRawLongBits(x) and -0x100000000L) or (l.toLong() and 0xffffffffL))

    private const val LN2 = 6.93147180559945286227e-01

    /** fdlibm's e_acosh.c. */
    private fun acosh(x: Double): Double {
        val hx = hi(x)
        val lx = lo(x)
        return when {
            hx < 0x3ff00000 -> (x - x) / (x - x)
            hx >= 0x41b00000 -> if (hx >= 0x7ff00000) x + x else Math.log(x) + LN2
            ((hx - 0x3ff00000) or lx) == 0 -> 0.0
            hx > 0x40000000 -> { val t = x * x; Math.log(2.0 * x - 1.0 / (x + Math.sqrt(t - 1.0))) }
            else -> { val t = x - 1.0; Math.log1p(t + Math.sqrt(2.0 * t + t * t)) }
        }
    }

    /** fdlibm's s_asinh.c. */
    private fun asinh(x: Double): Double {
        val hx = hi(x)
        val ix = hx and 0x7fffffff
        if (ix >= 0x7ff00000) return x + x
        if (ix < 0x3e300000) return x
        val w = when {
            ix > 0x41b00000 -> Math.log(Math.abs(x)) + LN2
            ix > 0x40000000 -> { val t = Math.abs(x); Math.log(2.0 * t + 1.0 / (Math.sqrt(x * x + 1.0) + t)) }
            else -> { val t = x * x; Math.log1p(Math.abs(x) + t / (1.0 + Math.sqrt(1.0 + t))) }
        }
        return if (hx > 0) w else -w
    }

    /** fdlibm's e_atanh.c. */
    private fun atanh(x0: Double): Double {
        val hx = hi(x0)
        val lx = lo(x0)
        val ix = hx and 0x7fffffff
        if ((ix or ((lx or -lx) ushr 31)) > 0x3ff00000) return (x0 - x0) / (x0 - x0)
        if (ix == 0x3ff00000) return Math.copySign(Double.POSITIVE_INFINITY, x0)
        if (ix < 0x3e300000) return x0
        val x = withHigh(x0, ix)
        val t = if (ix < 0x3fe00000) {
            val t2 = x + x
            0.5 * Math.log1p(t2 + t2 * x / (1.0 - x))
        } else 0.5 * Math.log1p((x + x) / (1.0 - x))
        return if (hx >= 0) t else -t
    }

    private const val IVLN2HI = 1.44269504072144627571e+00
    private const val IVLN2LO = 1.67517131648865118353e-10
    private const val LG1 = 6.666666666666735130e-01
    private const val LG2 = 3.999999999940941908e-01
    private const val LG3 = 2.857142874366239149e-01
    private const val LG4 = 2.222219843214978396e-01
    private const val LG5 = 1.818357216161805012e-01
    private const val LG6 = 1.531383769920937332e-01
    private const val LG7 = 1.479819860511658591e-01

    /** k_log.h's k_log1p: log(1 + f) - f + f*f/2 for f in [sqrt(2)/2 - 1, sqrt(2) - 1]. */
    private fun kLog1p(f: Double): Double {
        val s = f / (2.0 + f)
        val z = s * s
        val w = z * z
        val t1 = w * (LG2 + w * (LG4 + w * LG6))
        val t2 = z * (LG1 + w * (LG3 + w * (LG5 + w * LG7)))
        val r = t2 + t1
        val hfsq = 0.5 * f * f
        return s * (hfsq + r)
    }

    /** FreeBSD's e_log2.c (Java has no log2): exact for powers of 2, monotonic. */
    private fun log2(x0: Double): Double {
        var x = x0
        var hx = hi(x)
        val lx = lo(x)
        var k = 0
        if (hx < 0x00100000) {
            if (((hx and 0x7fffffff) or lx) == 0) return Double.NEGATIVE_INFINITY
            if (hx < 0) return Double.NaN
            k -= 54
            x *= TWO54
            hx = hi(x)
        }
        if (hx >= 0x7ff00000) return x + x
        if (hx == 0x3ff00000 && lx == 0) return 0.0
        k += (hx shr 20) - 1023
        hx = hx and 0x000fffff
        val i = (hx + 0x95f64) and 0x100000
        x = withHigh(x, hx or (i xor 0x3ff00000))
        k += i shr 20
        val y = k.toDouble()
        val f = x - 1.0
        val hfsq = 0.5 * f * f
        val r = kLog1p(f)
        val h = withLow(f - hfsq, 0)
        val l = (f - h) - hfsq + r
        var valHi = h * IVLN2HI
        var valLo = (l + h) * IVLN2LO + l * IVLN2HI
        val w = y + valHi
        valLo += (y - w) + valHi
        valHi = w
        return valLo + valHi
    }

    private const val ERX = 8.45062911510467529297e-01
    private const val EFX = 1.28379167095512586316e-01
    private const val EFX8 = 1.02703333676410069053e+00
    private const val PP0 = 1.28379167095512558561e-01
    private const val PP1 = -3.25042107247001499370e-01
    private const val PP2 = -2.84817495755985104766e-02
    private const val PP3 = -5.77027029648944159157e-03
    private const val PP4 = -2.37630166566501626084e-05
    private const val QQ1 = 3.97917223959155352819e-01
    private const val QQ2 = 6.50222499887672944485e-02
    private const val QQ3 = 5.08130628187576562776e-03
    private const val QQ4 = 1.32494738004321644526e-04
    private const val QQ5 = -3.96022827877536812320e-06
    private const val PA0 = -2.36211856075265944077e-03
    private const val PA1 = 4.14856118683748331666e-01
    private const val PA2 = -3.72207876035701323847e-01
    private const val PA3 = 3.18346619901161753674e-01
    private const val PA4 = -1.10894694282396677476e-01
    private const val PA5 = 3.54783043256182359371e-02
    private const val PA6 = -2.16637559486879084300e-03
    private const val QA1 = 1.06420880400844228286e-01
    private const val QA2 = 5.40397917702171048937e-01
    private const val QA3 = 7.18286544141962662868e-02
    private const val QA4 = 1.26171219808761642112e-01
    private const val QA5 = 1.36370839120290507362e-02
    private const val QA6 = 1.19844998467991074170e-02
    private const val RA0 = -9.86494403484714822705e-03
    private const val RA1 = -6.93858572707181764372e-01
    private const val RA2 = -1.05586262253232909814e+01
    private const val RA3 = -6.23753324503260060396e+01
    private const val RA4 = -1.62396669462573470355e+02
    private const val RA5 = -1.84605092906711035994e+02
    private const val RA6 = -8.12874355063065934246e+01
    private const val RA7 = -9.81432934416914548592e+00
    private const val SA1 = 1.96512716674392571292e+01
    private const val SA2 = 1.37657754143519042600e+02
    private const val SA3 = 4.34565877475229228821e+02
    private const val SA4 = 6.45387271733267880336e+02
    private const val SA5 = 4.29008140027567833386e+02
    private const val SA6 = 1.08635005541779435134e+02
    private const val SA7 = 6.57024977031928170135e+00
    private const val SA8 = -6.04244152148580987438e-02
    private const val RB0 = -9.86494292470009928597e-03
    private const val RB1 = -7.99283237680523006574e-01
    private const val RB2 = -1.77579549177547519889e+01
    private const val RB3 = -1.60636384855821916062e+02
    private const val RB4 = -6.37566443368389627722e+02
    private const val RB5 = -1.02509513161107724954e+03
    private const val RB6 = -4.83519191608651397019e+02
    private const val SB1 = 3.03380607434824582924e+01
    private const val SB2 = 3.25792512996573918826e+02
    private const val SB3 = 1.53672958608443695994e+03
    private const val SB4 = 3.19985821950859553908e+03
    private const val SB5 = 2.55305040643316442583e+03
    private const val SB6 = 4.74528541206955367215e+02
    private const val SB7 = -2.24409524465858183362e+01
    private const val TINY = 1e-300

    /** erfc(x) * x for 1.25 <= x < 28 (fdlibm's tail of erf and erfc), [ix] x's high word. */
    private fun erfTail(x: Double, ix: Int, bound: Int): Double {
        val s = 1.0 / (x * x)
        val r: Double
        val ss: Double
        if (ix < bound) {
            r = RA0 + s * (RA1 + s * (RA2 + s * (RA3 + s * (RA4 + s * (RA5 + s * (RA6 + s * RA7))))))
            ss = 1.0 + s * (SA1 + s * (SA2 + s * (SA3 + s * (SA4 + s * (SA5 + s * (SA6 + s * (SA7 + s * SA8)))))))
        } else {
            r = RB0 + s * (RB1 + s * (RB2 + s * (RB3 + s * (RB4 + s * (RB5 + s * RB6)))))
            ss = 1.0 + s * (SB1 + s * (SB2 + s * (SB3 + s * (SB4 + s * (SB5 + s * (SB6 + s * SB7))))))
        }
        val z = withLow(x, 0)
        return Math.exp(-z * z - 0.5625) * Math.exp((z - x) * (z + x) + r / ss)
    }

    /**
     * fdlibm's s_erf.c (Java has no erf; CPython takes the C library's, whose last digit differs between platforms:
     * this one's from the Windows C runtime's too, now and then).
     */
    private fun erf(x: Double): Double {
        val hx = hi(x)
        val ix = hx and 0x7fffffff
        if (ix >= 0x7ff00000) return (1 - ((hx ushr 31) shl 1)).toDouble() + 1.0 / x
        if (ix < 0x3feb0000) {
            if (ix < 0x3e300000) return if (ix < 0x00800000) 0.125 * (8.0 * x + EFX8 * x) else x + EFX * x
            val z = x * x
            val r = PP0 + z * (PP1 + z * (PP2 + z * (PP3 + z * PP4)))
            val s = 1.0 + z * (QQ1 + z * (QQ2 + z * (QQ3 + z * (QQ4 + z * QQ5))))
            return x + x * (r / s)
        }
        if (ix < 0x3ff40000) {
            val s = Math.abs(x) - 1.0
            val p = PA0 + s * (PA1 + s * (PA2 + s * (PA3 + s * (PA4 + s * (PA5 + s * PA6)))))
            val q = 1.0 + s * (QA1 + s * (QA2 + s * (QA3 + s * (QA4 + s * (QA5 + s * QA6)))))
            return if (hx >= 0) ERX + p / q else -ERX - p / q
        }
        if (ix >= 0x40180000) return if (hx >= 0) 1.0 - TINY else TINY - 1.0
        val ax = Math.abs(x)
        val r = erfTail(ax, ix, 0x4006DB6E)
        return if (hx >= 0) 1.0 - r / ax else r / ax - 1.0
    }

    /** fdlibm's s_erf.c's erfc (see [erf]). */
    private fun erfc(x: Double): Double {
        val hx = hi(x)
        val ix = hx and 0x7fffffff
        if (ix >= 0x7ff00000) return ((hx ushr 31) shl 1).toDouble() + 1.0 / x
        if (ix < 0x3feb0000) {
            if (ix < 0x3c700000) return 1.0 - x
            val z = x * x
            val r = PP0 + z * (PP1 + z * (PP2 + z * (PP3 + z * PP4)))
            val s = 1.0 + z * (QQ1 + z * (QQ2 + z * (QQ3 + z * (QQ4 + z * QQ5))))
            val y = r / s
            if (hx < 0x3fd00000) return 1.0 - (x + x * y)
            var t = x * y
            t += x - 0.5
            return 0.5 - t
        }
        if (ix < 0x3ff40000) {
            val s = Math.abs(x) - 1.0
            val p = PA0 + s * (PA1 + s * (PA2 + s * (PA3 + s * (PA4 + s * (PA5 + s * PA6)))))
            val q = 1.0 + s * (QA1 + s * (QA2 + s * (QA3 + s * (QA4 + s * (QA5 + s * QA6)))))
            return if (hx >= 0) (1.0 - ERX) - p / q else 1.0 + (ERX + p / q)
        }
        if (ix < 0x403c0000) {
            if (hx < 0 && ix >= 0x40180000) return 2.0 - TINY
            val ax = Math.abs(x)
            val r = erfTail(ax, ix, 0x4006DB6D)
            return if (hx > 0) r / ax else 2.0 - r / ax
        }
        return if (hx > 0) TINY * TINY else 2.0 - TINY
    }

    private val HAS_FMA = try { Math.fma(1.0, 1.0, 1.0) == 2.0 } catch (e: Throwable) { false }

    /** fma(x, y, z): x*y + z rounded once (Math.fma, where the platform has it: not Android before 13). */
    private fun fma(x: Double, y: Double, z: Double): Double = if (HAS_FMA) Math.fma(x, y, z) else exactFma(x, y, z)

    private fun exactFma(x: Double, y: Double, z: Double): Double {
        if (!x.isFinite() || !y.isFinite()) return x * y + z
        // (x*y finite, however large)
        if (!z.isFinite()) return z
        if (x == 0.0 || y == 0.0) return x * y + z
        if (z == 0.0) return x * y
        // (x*y + z exactly, a BigInteger times a power of 2, then rounded once)
        val px = significand(x)
        val py = significand(y)
        val pz = significand(z)
        val ep = exponentOf(x) + exponentOf(y)
        val ez = exponentOf(z)
        val e = minOf(ep, ez)
        val sum = BigInteger.valueOf(px).multiply(BigInteger.valueOf(py)).shiftLeft(ep - e).add(BigInteger.valueOf(pz).shiftLeft(ez - e))
        if (sum.signum() == 0) return 0.0
        return scaled(sum, e)
    }

    /** A finite nonzero double's integer significand (signed) and the power of 2 it is scaled by. */
    private fun significand(x: Double): Long {
        val bits = java.lang.Double.doubleToRawLongBits(x)
        val exp = ((bits ushr 52) and 0x7ff).toInt()
        val m = bits and 0xfffffffffffffL
        val v = if (exp == 0) m else m or (1L shl 52)
        return if (bits < 0) -v else v
    }

    private fun exponentOf(x: Double): Int {
        val exp = ((java.lang.Double.doubleToRawLongBits(x) ushr 52) and 0x7ff).toInt()
        return (if (exp == 0) 1 else exp) - 1075
    }

    /** n * 2**e rounded to a double, half to even (subnormals and overflow included). */
    private fun scaled(n: BigInteger, e: Int): Double {
        val neg = n.signum() < 0
        val a = n.abs()
        val bits = a.bitLength()
        val top = e + bits - 1
        val lsb = maxOf(top - 52, -1074)
        val shift = lsb - e
        var m: BigInteger
        if (shift <= 0) m = a.shiftLeft(-shift)
        else {
            m = a.shiftRight(shift)
            val half = a.testBit(shift - 1)
            val sticky = a.lowestSetBit < shift - 1
            if (half && (sticky || m.testBit(0))) m = m.add(BigInteger.ONE)
        }
        val r = Math.scalb(m.toLong().toDouble(), lsb)
        return if (neg) -r else r
    }

    // ------------------------------------------------------------------ CPython's own: gamma, lgamma, remainder

    private const val PI = 3.141592653589793238462643383279502884197
    private const val LOGPI = 1.144729885849400174143427351353058711647
    private const val LANCZOS_G = 6.024680040776729583740234375
    private const val LANCZOS_G_MINUS_HALF = 5.524680040776729583740234375
    private val LANCZOS_NUM = doubleArrayOf(
        23531376880.410759688572007674451636754734846804940,
        42919803642.649098768957899047001988850926355848959,
        35711959237.355668049440185451547166705960488635843,
        17921034426.037209699919755754458931112671403265390,
        6039542586.3520280050642916443072979210699388420708,
        1439720407.3117216736632230727949123939715485786772,
        248874557.86205415651146038641322942321632125127801,
        31426415.585400194380614231628318205362874684987640,
        2876370.6289353724412254090516208496135991145378768,
        186056.26539522349504029498971604569928220784236328,
        8071.6720023658162106380029022722506138218516325024,
        210.82427775157934587250973392071336271166969580291,
        2.5066282746310002701649081771338373386264310793408)
    private val LANCZOS_DEN = doubleArrayOf(0.0, 39916800.0, 120543840.0, 150917976.0, 105258076.0, 45995730.0,
        13339535.0, 2637558.0, 357423.0, 32670.0, 1925.0, 66.0, 1.0)
    private val GAMMA_INTEGRAL = doubleArrayOf(1.0, 1.0, 2.0, 6.0, 24.0, 120.0, 720.0, 5040.0, 40320.0, 362880.0,
        3628800.0, 39916800.0, 479001600.0, 6227020800.0, 87178291200.0, 1307674368000.0, 20922789888000.0,
        355687428096000.0, 6402373705728000.0, 121645100408832000.0, 2432902008176640000.0, 51090942171709440000.0,
        1124000727777607680000.0)

    /** sin(pi*x), accurate for all finite x (m_sinpi). */
    private fun sinpi(x: Double): Double {
        val y = Math.abs(x) % 2.0
        val r = when (Math.round(2.0 * y).toInt()) {
            0 -> Math.sin(PI * y)
            1 -> Math.cos(PI * (y - 0.5))
            // (-sin(pi*(y-1.0)) would give -0.0 for y == 1.0)
            2 -> Math.sin(PI * (1.0 - y))
            3 -> -Math.cos(PI * (y - 1.5))
            else -> Math.sin(PI * (y - 2.0))
        }
        return Math.copySign(1.0, x) * r
    }

    /** Lanczos' sum L_g(x), for positive x. */
    private fun lanczosSum(x: Double): Double {
        var num = 0.0
        var den = 0.0
        if (x < 5.0) {
            for (i in 12 downTo 0) {
                num = num * x + LANCZOS_NUM[i]
                den = den * x + LANCZOS_DEN[i]
            }
        } else {
            for (i in 0 until 13) {
                num = num / x + LANCZOS_NUM[i]
                den = den / x + LANCZOS_DEN[i]
            }
        }
        return num / den
    }

    /** CPython's m_tgamma; [errno] set to EDOM or ERANGE where its C sets errno. */
    private fun tgamma(x: Double, errno: IntArray): Double {
        if (!x.isFinite()) {
            if (x.isNaN() || x > 0.0) return x
            errno[0] = EDOM
            return Double.NaN
        }
        if (x == 0.0) {
            errno[0] = EDOM
            return Math.copySign(Double.POSITIVE_INFINITY, x)
        }
        if (x == Math.floor(x)) {
            if (x < 0.0) {
                errno[0] = EDOM
                return Double.NaN
            }
            if (x <= GAMMA_INTEGRAL.size) return GAMMA_INTEGRAL[x.toInt() - 1]
        }
        val absx = Math.abs(x)
        if (absx < 1e-20) {
            val r = 1.0 / x
            if (r.isInfinite()) errno[0] = ERANGE
            return r
        }
        if (absx > 200.0) {
            if (x < 0.0) return 0.0 / sinpi(x)
            errno[0] = ERANGE
            return Double.POSITIVE_INFINITY
        }
        val y = absx + LANCZOS_G_MINUS_HALF
        var z = if (absx > LANCZOS_G_MINUS_HALF) {
            val q = y - absx
            q - LANCZOS_G_MINUS_HALF
        } else {
            val q = y - LANCZOS_G_MINUS_HALF
            q - absx
        }
        z = z * LANCZOS_G / y
        var r: Double
        if (x < 0.0) {
            r = -PI / sinpi(absx) / absx * Math.exp(y) / lanczosSum(absx)
            r -= z * r
            if (absx < 140.0) r /= Math.pow(y, absx - 0.5)
            else {
                val sqrtpow = Math.pow(y, absx / 2.0 - 0.25)
                r /= sqrtpow
                r /= sqrtpow
            }
        } else {
            r = lanczosSum(absx) / Math.exp(y)
            r += z * r
            if (absx < 140.0) r *= Math.pow(y, absx - 0.5)
            else {
                val sqrtpow = Math.pow(y, absx / 2.0 - 0.25)
                r *= sqrtpow
                r *= sqrtpow
            }
        }
        if (r.isInfinite()) errno[0] = ERANGE
        return r
    }

    /** CPython's m_lgamma: the log of |gamma(x)|. */
    private fun lgamma(x: Double, errno: IntArray): Double {
        if (!x.isFinite()) return if (x.isNaN()) x else Double.POSITIVE_INFINITY
        if (x == Math.floor(x) && x <= 2.0) {
            if (x <= 0.0) {
                errno[0] = EDOM
                return Double.POSITIVE_INFINITY
            }
            return 0.0
        }
        val absx = Math.abs(x)
        if (absx < 1e-20) return -Math.log(absx)
        var r = Math.log(lanczosSum(absx)) - LANCZOS_G
        r += (absx - 0.5) * (Math.log(absx + LANCZOS_G - 0.5) - 1)
        if (x < 0.0) r = LOGPI - Math.log(Math.abs(sinpi(absx))) - Math.log(absx) - r
        if (r.isInfinite()) errno[0] = ERANGE
        return r
    }

    /** IEEE 754's remainder: x - n*y, n*y the nearest multiple of y to x (the even one in a tie); exact (m_remainder). */
    private fun remainder(x: Double, y: Double): Double {
        if (x.isFinite() && y.isFinite()) {
            if (y == 0.0) return Double.NaN
            val absx = Math.abs(x)
            val absy = Math.abs(y)
            val m = absx % absy
            val c = absy - m
            val r = if (m < c) m else if (m > c) -c else m - 2.0 * ((0.5 * (absx - m)) % absy)
            return Math.copySign(1.0, x) * r
        }
        if (x.isNaN()) return x
        if (y.isNaN()) return y
        if (x.isInfinite()) return Double.NaN
        return x
    }

    /** log(x) where x > 0 (m_log): the C library's, its special values handled here. */
    private fun mLog(x: Double): Double = when {
        x.isFinite() -> if (x > 0.0) Math.log(x) else if (x == 0.0) Double.NEGATIVE_INFINITY else Double.NaN
        x.isNaN() || x > 0.0 -> x
        else -> Double.NaN
    }

    private fun mLog10(x: Double): Double = when {
        x.isFinite() -> if (x > 0.0) Math.log10(x) else if (x == 0.0) Double.NEGATIVE_INFINITY else Double.NaN
        x.isNaN() || x > 0.0 -> x
        else -> Double.NaN
    }

    private fun mLog2(x: Double): Double = when {
        !x.isFinite() -> if (x.isNaN() || x > 0.0) x else Double.NaN
        x > 0.0 -> log2(x)
        x == 0.0 -> Double.NEGATIVE_INFINITY
        else -> Double.NaN
    }

    // ------------------------------------------------------------------ the wrappers' errors (math_1, math_1a, math_2)

    /** [f] of a float argument as math_1 calls it: a NaN of a number, an infinity of a finite one ValueError or OverflowError. */
    private fun math1(arg: Any?, f: (Double) -> Double, canOverflow: Boolean, msg: String?): Double {
        val x = asDouble(arg)
        val r = f(x)
        if (r.isNaN() && !x.isNaN()) throw domain(x, msg)
        if (r.isInfinite() && x.isFinite()) throw if (canOverflow) rangeError() else domain(x, msg)
        return r
    }

    private fun domain(x: Double, msg: String?): PyException = if (msg == null) domainError() else valueError(msg.replace("%s", PyRt.floatRepr(x)))

    /** math_1a's errors, of what [f] set errno to. */
    private fun math1a(arg: Any?, f: (Double, IntArray) -> Double, msg: String): Double {
        val x = asDouble(arg)
        val errno = IntArray(1)
        val r = f(x, errno)
        if (errno[0] == EDOM) throw valueError(msg.replace("%s", PyRt.floatRepr(x)))
        if (errno[0] == ERANGE && Math.abs(r) >= 1.5) throw rangeError()
        return r
    }

    /** [f] of two floats as math_2 calls it. */
    private fun math2(a: Array<Any?>, f: (Double, Double) -> Double): Double {
        val x = asDouble(a[0])
        val y = asDouble(a[1])
        val r = f(x, y)
        if (r.isNaN()) {
            if (!x.isNaN() && !y.isNaN()) throw domainError()
        } else if (r.isInfinite()) {
            if (x.isFinite() && y.isFinite() && Math.abs(r) >= 1.5) throw rangeError()
        }
        return r
    }

    // ------------------------------------------------------------------ floor, ceil, trunc

    /** math.floor and math.ceil: a float's own, else the class's __floor__ (__ceil__), else of the number as a float. */
    private fun rounded(v: Any?, n: String, f: (Double) -> Double): Any? {
        if (v is Double) return intOf(f(v))
        special(v, n)?.let { return callSpecial(it, v) }
        return when (v) {
            // (int's own __floor__, __ceil__: the int; float's: of its value)
            is Long, is BigInteger -> v
            is PyBool -> if (v.value) 1L else 0L
            is PyNumSub -> v.num.let { if (it is Double) intOf(f(it)) else it }
            else -> intOf(f(asDouble(v)))
        }
    }

    private fun trunc(v: Any?): Any? {
        if (v is Double) return intOf(v)
        special(v, "__trunc__")?.let { return callSpecial(it, v) }
        return when (v) {
            is Long, is BigInteger -> v
            is PyBool -> if (v.value) 1L else 0L
            is PyNumSub -> v.num.let { if (it is Double) intOf(it) else it }
            else -> throw PyRt.typeError("type ${PyTypes.name(v)} doesn't define __trunc__ method")
        }
    }

    // ------------------------------------------------------------------ frexp, ldexp, modf, fma, fmod

    /** C's frexp's exponent of a finite nonzero x: x == m * 2**e, 0.5 <= |m| < 1. */
    private fun frexpExponent(x: Double): Int {
        val e = Math.getExponent(x)
        return if (e == java.lang.Double.MIN_EXPONENT - 1) Math.getExponent(x * TWO54) - 53 else e + 1
    }

    private fun frexp(x: Double): PyTuple {
        if (x.isNaN() || x.isInfinite() || x == 0.0) return PyTuple(arrayOf(x, 0L))
        val e = frexpExponent(x)
        return PyTuple(arrayOf(Math.scalb(x, -e), e.toLong()))
    }

    private fun ldexp(x: Double, i: Any?): Double {
        val n = intValue(i) ?: throw PyRt.typeError("Expected an int as second argument to ldexp.")
        // (past a C long: LONG_MIN or LONG_MAX)
        val exp = n as? Long ?: if (signum(n) < 0) Long.MIN_VALUE else Long.MAX_VALUE
        val r = when {
            x == 0.0 || !x.isFinite() -> x
            exp > Int.MAX_VALUE -> throw rangeError()
            exp < Int.MIN_VALUE -> Math.copySign(0.0, x)
            else -> Math.scalb(x, exp.toInt())
        }
        if (r.isInfinite() && x.isFinite()) throw rangeError()
        return r
    }

    private fun modf(x: Double): PyTuple {
        if (x.isInfinite()) return PyTuple(arrayOf(Math.copySign(0.0, x), x))
        if (x.isNaN()) return PyTuple(arrayOf(x, x))
        val ip = if (x >= 0.0) Math.floor(x) else Math.ceil(x)
        val fp = x - ip
        return PyTuple(arrayOf(if (fp == 0.0) Math.copySign(0.0, x) else fp, Math.copySign(ip, x)))
    }

    private fun mathFma(x: Double, y: Double, z: Double): Double {
        val r = fma(x, y, z)
        if (r.isFinite()) return r
        if (r.isNaN()) {
            if (!x.isNaN() && !y.isNaN() && !z.isNaN()) throw valueError("invalid operation in fma")
        } else if (x.isFinite() && y.isFinite() && z.isFinite()) throw overflowError("overflow in fma")
        return r
    }

    private fun fmod(x: Double, y: Double): Double {
        // (fmod(x, +-inf) is x for a finite x)
        if (y.isInfinite() && x.isFinite()) return x
        val r = x % y
        if (r.isNaN() && !x.isNaN() && !y.isNaN()) throw domainError()
        return r
    }

    private fun pow(x: Double, y: Double): Double {
        var r: Double
        var errno = 0
        if (!x.isFinite() || !y.isFinite()) {
            r = when {
                x.isNaN() -> if (y == 0.0) 1.0 else x
                y.isNaN() -> if (x == 1.0) 1.0 else y
                x.isInfinite() -> {
                    val oddY = y.isFinite() && Math.abs(y) % 2.0 == 1.0
                    if (y > 0.0) (if (oddY) x else Math.abs(x)) else if (y == 0.0) 1.0 else if (oddY) Math.copySign(0.0, x) else 0.0
                }
                Math.abs(x) == 1.0 -> 1.0
                y > 0.0 && Math.abs(x) > 1.0 -> y
                y < 0.0 && Math.abs(x) < 1.0 -> -y
                else -> 0.0
            }
        } else {
            r = Math.pow(x, y)
            if (r.isNaN()) errno = EDOM
            else if (r.isInfinite()) errno = if (x == 0.0) EDOM else ERANGE
        }
        if (errno == EDOM) throw domainError()
        if (errno == ERANGE && Math.abs(r) >= 1.5) throw rangeError()
        return r
    }

    // ------------------------------------------------------------------ logarithms of ints of any size

    /** An int's frexp, rounded half to even to 53 bits (_PyLong_Frexp): x in [0.5, 1) and e, n ~ x * 2**e. */
    private fun bigFrexp(n: BigInteger, e: LongArray): Double {
        var bits = n.bitLength().toLong()
        // (its first DBL_MANT_DIG + 2 bits, the last sticky)
        val shift = (bits - 55).toInt()
        var x = n.shiftRight(shift).toLong()
        if (n.lowestSetBit < shift) x = x or 1L
        x += HALF_EVEN_CORRECTION[(x and 7L).toInt()]
        var dx = x.toDouble() / (4.0 * 9007199254740992.0)
        if (dx == 1.0) {
            dx = 0.5
            bits += 1
        }
        e[0] = bits
        return dx
    }

    private val HALF_EVEN_CORRECTION = longArrayOf(0, -1, -2, 1, 0, -1, 2, 1)

    /** log, log2, log10 of an argument (loghelper): an int of any size by its bits, anything else as a float. */
    private fun logHelper(arg: Any?, f: (Double) -> Double): Double {
        val n = intValue(arg)
        if (n != null) {
            if (signum(n) <= 0) throw valueError("expected a positive input")
            if (n is Long) return f(n.toDouble())
            val x = (n as BigInteger).toDouble()
            if (!x.isInfinite()) return f(x)
            val e = LongArray(1)
            val m = bigFrexp(n, e)
            return f(m) + f(2.0) * e[0].toDouble()
        }
        return math1(arg, f, false, "expected a positive input, got %s")
    }

    // ------------------------------------------------------------------ gcd, lcm

    private fun gcd(a: Any, b: Any): Any {
        if (a is Long && b is Long && a != Long.MIN_VALUE && b != Long.MIN_VALUE) {
            var x = Math.abs(a)
            var y = Math.abs(b)
            while (y != 0L) {
                val t = x % y
                x = y
                y = t
            }
            return x
        }
        return PyRt.norm(big(a).gcd(big(b)))
    }

    private fun lcm(a: Any, b: Any): Any {
        if (signum(a) == 0 || signum(b) == 0) return 0L
        val g = gcd(a, b)
        val f = PyRt.floordiv(a, g)!!
        return absInt(PyRt.mul(f, b)!!)
    }

    // ------------------------------------------------------------------ fsum, hypot, dist, sumprod, prod

    /** fsum: Shewchuk's exact partials, then their sum rounded once (math_fsum). */
    private fun fsum(seq: Any?): Double {
        val it = PyRt.iter(seq)
        var p = DoubleArray(32)
        var n = 0
        var specialSum = 0.0
        var infSum = 0.0
        while (true) {
            val item = it.next()
            if (item === STOP) break
            var x = asDouble(item)
            val xsave = x
            var i = 0
            for (j in 0 until n) {
                var y = p[j]
                if (Math.abs(x) < Math.abs(y)) {
                    val t = x
                    x = y
                    y = t
                }
                val hi = x + y
                val yr = hi - x
                val lo = y - yr
                if (lo != 0.0) p[i++] = lo
                x = hi
            }
            n = i
            if (x != 0.0) {
                if (!x.isFinite()) {
                    // (a nonfinite x: an intermediate overflow, or a nan or inf among the summands)
                    if (xsave.isFinite()) throw overflowError("intermediate overflow in fsum")
                    if (xsave.isInfinite()) infSum += xsave
                    specialSum += xsave
                    n = 0
                } else {
                    if (n >= p.size) p = p.copyOf(p.size * 2)
                    p[n++] = x
                }
            }
        }
        if (specialSum != 0.0) {
            if (infSum.isNaN()) throw valueError("-inf + inf in fsum")
            return specialSum
        }
        var hi = 0.0
        if (n > 0) {
            hi = p[--n]
            var lo = 0.0
            // (the partials summed from the top, until the sum is inexact)
            while (n > 0) {
                val x = hi
                val y = p[--n]
                hi = x + y
                val yr = hi - x
                lo = y - yr
                if (lo != 0.0) break
            }
            // (half-even rounding across the partials: sum([1e-16, 1, 1e16]) rounds up)
            if (n > 0 && ((lo < 0.0 && p[n - 1] < 0.0) || (lo > 0.0 && p[n - 1] > 0.0))) {
                val y = lo * 2.0
                val x = hi + y
                val yr = x - hi
                if (y == yr) hi = x
            }
        }
        return hi
    }

    /**
     * sqrt(sum(x**2 for x in vec)), [max] the largest |x| (vector_norm): scaled by a power of 2, squared exactly,
     * summed compensated, its square root corrected once; almost always correctly rounded.
     */
    private fun vectorNorm(n: Int, vec: DoubleArray, max: Double, foundNan: Boolean): Double {
        if (max.isInfinite()) return max
        if (foundNan) return Double.NaN
        if (max == 0.0 || n <= 1) return max
        val maxE = frexpExponent(max)
        if (maxE < -1023) {
            // (ldexp(1.0, -max_e) would overflow: subnormals made normals)
            for (i in 0 until n) vec[i] /= DBL_MIN
            return DBL_MIN * vectorNorm(n, vec, max / DBL_MIN, foundNan)
        }
        val scale = Math.scalb(1.0, -maxE)
        var csum = 1.0
        var frac1 = 0.0
        var frac2 = 0.0
        for (i in 0 until n) {
            val x = vec[i] * scale
            val prHi = x * x
            val prLo = fma(x, x, -prHi)
            val smHi = csum + prHi
            val smLo = (csum - smHi) + prHi
            csum = smHi
            frac1 += prLo
            frac2 += smLo
        }
        var h = Math.sqrt(csum - 1.0 + (frac1 + frac2))
        val prHi = -h * h
        val prLo = fma(-h, h, -prHi)
        val smHi = csum + prHi
        val smLo = (csum - smHi) + prHi
        csum = smHi
        frac1 += prLo
        frac2 += smLo
        val x = csum - 1.0 + (frac1 + frac2)
        h += x / (2.0 * h)
        return h / scale
    }

    private fun hypot(a: Array<Any?>): Double {
        val coordinates = DoubleArray(a.size)
        var max = 0.0
        var foundNan = false
        for (i in a.indices) {
            val x = Math.abs(asDouble(a[i]))
            coordinates[i] = x
            foundNan = foundNan or x.isNaN()
            if (x > max) max = x
        }
        return vectorNorm(a.size, coordinates, max, foundNan)
    }

    private fun dist(p0: Any?, q0: Any?): Double {
        val p = (p0 as? PyTuple)?.items ?: PyBuiltins.toList(p0).toTypedArray()
        val q = (q0 as? PyTuple)?.items ?: PyBuiltins.toList(q0).toTypedArray()
        if (p.size != q.size) throw valueError("both points must have the same number of dimensions")
        val diffs = DoubleArray(p.size)
        var max = 0.0
        var foundNan = false
        for (i in p.indices) {
            val px = asDouble(p[i])
            val qx = asDouble(q[i])
            val x = Math.abs(px - qx)
            diffs[i] = x
            foundNan = foundNan or x.isNaN()
            if (x > max) max = x
        }
        return vectorNorm(p.size, diffs, max, foundNan)
    }

    /** Whether a * b leaves a C long (_check_long_mult_overflow: the long product against the double one). */
    private fun longMulOverflows(a: Long, b: Long): Boolean {
        val longprod = a * b
        val doubleprod = a.toDouble() * b.toDouble()
        val doubledLongprod = longprod.toDouble()
        if (doubledLongprod == doubleprod) return false
        val absdiff = Math.abs(doubledLongprod - doubleprod)
        val absprod = Math.abs(doubleprod)
        return !(32.0 * absdiff <= absprod)
    }

    private fun longAddOverflows(a: Long, b: Long): Boolean = if (a > 0) b > Long.MAX_VALUE - a else b < Long.MIN_VALUE - a

    private fun exactInt(v: Any?) = v is Long || v is BigInteger

    /**
     * sumprod (math_sumprod_impl): exact ints summed in a C long while they fit, floats (and an int with a float) in
     * triple length while they stay finite, anything else by its * and +.
     */
    private fun sumprod(p: Any?, q: Any?): Any? {
        val pIt = PyRt.iter(p)
        val qIt = PyRt.iter(q)
        var total: Any? = 0L
        var pStopped = false
        var qStopped = false
        var intPath = true
        var intInUse = false
        var intTotal = 0L
        var fltPath = true
        var fltInUse = false
        val flt = DoubleArray(3)
        val next = DoubleArray(3)
        while (true) {
            val pi = pIt.next()
            if (pi === STOP) pStopped = true
            val qi = qIt.next()
            if (qi === STOP) qStopped = true
            if (pStopped != qStopped) throw valueError("Inputs are not the same length")
            val finished = pStopped && qStopped
            if (intPath) {
                if (!finished && pi is Long && qi is Long && !longMulOverflows(pi, qi)) {
                    val prod = pi * qi
                    if (!longAddOverflows(intTotal, prod)) {
                        intTotal += prod
                        intInUse = true
                        continue
                    }
                }
                // (finished, overflowed, or not two ints)
                intPath = false
                if (intInUse) {
                    total = PyRt.add(total, intTotal)
                    intTotal = 0L
                    intInUse = false
                }
            }
            if (fltPath) {
                if (!finished) {
                    var fp = 0.0
                    var fq = 0.0
                    var ok = true
                    if (pi is Double && qi is Double) {
                        fp = pi
                        fq = qi
                    } else if (pi is Double && (exactInt(qi) || qi is PyBool)) {
                        fp = pi
                        fq = intDouble(qi)
                        ok = !fq.isNaN()
                    } else if (qi is Double && (exactInt(pi) || pi is PyBool)) {
                        fq = qi
                        fp = intDouble(pi)
                        ok = !fp.isNaN()
                    } else ok = false
                    if (ok) {
                        tlFma(fp, fq, flt, next)
                        if (next[0].isFinite()) {
                            next.copyInto(flt)
                            fltInUse = true
                            continue
                        }
                    }
                }
                // (finished, overflowed, not floats, or not finite)
                fltPath = false
                if (fltInUse) {
                    total = PyRt.add(total, tlToDouble(flt))
                    flt.fill(0.0)
                    fltInUse = false
                }
            }
            if (finished) return total
            total = PyRt.add(total, PyRt.mul(pi, qi))
        }
    }

    /** An int as a double, NaN where it is too large (PyLong_AsDouble's error, cleared). */
    private fun intDouble(v: Any?): Double = when (v) {
        is Long -> v.toDouble()
        is PyBool -> if (v.value) 1.0 else 0.0
        else -> (v as BigInteger).toDouble().let { if (it.isInfinite()) Double.NaN else it }
    }

    /** total + x*y in triple length (tl_fma: Ogita, Rump and Oishi's algorithm 5.10 with SumKVert for K=3). */
    private fun tlFma(x: Double, y: Double, total: DoubleArray, out: DoubleArray) {
        // dl_mul(x, y)
        val prHi = x * y
        val prLo = fma(x, y, -prHi)
        // dl_sum(total.hi, pr.hi), dl_sum(total.lo, pr.lo), dl_sum(r1.hi, sm.lo)
        val smHi = total[0] + prHi
        var z = smHi - total[0]
        val smLo = (total[0] - (smHi - z)) + (prHi - z)
        val r1Hi = total[1] + prLo
        z = r1Hi - total[1]
        val r1Lo = (total[1] - (r1Hi - z)) + (prLo - z)
        val r2Hi = r1Hi + smLo
        z = r2Hi - r1Hi
        val r2Lo = (r1Hi - (r2Hi - z)) + (smLo - z)
        out[0] = smHi
        out[1] = r2Hi
        out[2] = total[2] + r1Lo + r2Lo
    }

    private fun tlToDouble(t: DoubleArray): Double {
        // dl_sum(total.lo, total.hi)
        val hi = t[1] + t[0]
        val z = hi - t[1]
        val lo = (t[1] - (hi - z)) + (t[0] - z)
        return t[2] + lo + hi
    }

    /** prod (math_prod_impl): ints in a C long while they fit, floats in a double, anything else by its *. */
    private fun prod(iterable: Any?, start: Any?): Any? {
        val it = PyRt.iter(iterable)
        var result: Any? = start ?: 1L
        if (result is Long) {
            var iResult: Long = result
            var r: Any? = null
            while (r == null) {
                val item = it.next()
                if (item === STOP) return iResult
                if (item is Long && !longMulOverflows(iResult, item)) {
                    iResult *= item
                    continue
                }
                // (overflowed or not an int: the objects' own *)
                r = PyRt.mul(iResult, item)
            }
            result = r
        }
        if (result is Double) {
            var fResult: Double = result
            var r: Any? = null
            while (r == null) {
                val item = it.next()
                if (item === STOP) return fResult
                if (item is Double) {
                    fResult *= item
                    continue
                }
                if (item is Long) {
                    fResult *= item.toDouble()
                    continue
                }
                r = PyRt.mul(fResult, item)
            }
            result = r
        }
        while (true) {
            val item = it.next()
            if (item === STOP) return result
            result = PyRt.mul(result, item)
        }
    }

    // ------------------------------------------------------------------ isqrt

    private val ISQRT_TAB = intArrayOf(
        128, 129, 130, 131, 132, 133, 134, 135, 136, 137, 138, 139,
        140, 141, 142, 143, 144, 144, 145, 146, 147, 148, 149, 150,
        151, 151, 152, 153, 154, 155, 156, 156, 157, 158, 159, 160,
        160, 161, 162, 163, 164, 164, 165, 166, 167, 167, 168, 169,
        170, 170, 171, 172, 173, 173, 174, 175, 176, 176, 177, 178,
        179, 179, 180, 181, 181, 182, 183, 183, 184, 185, 186, 186,
        187, 188, 188, 189, 190, 190, 191, 192, 192, 193, 194, 194,
        195, 196, 196, 197, 198, 198, 199, 200, 200, 201, 201, 202,
        203, 203, 204, 205, 205, 206, 206, 207, 208, 208, 209, 210,
        210, 211, 211, 212, 213, 213, 214, 214, 215, 216, 216, 217,
        217, 218, 219, 219, 220, 220, 221, 221, 222, 223, 223, 224,
        224, 225, 225, 226, 227, 227, 228, 228, 229, 229, 230, 230,
        231, 232, 232, 233, 233, 234, 234, 235, 235, 236, 237, 237,
        238, 238, 239, 239, 240, 240, 241, 241, 242, 242, 243, 243,
        244, 244, 245, 246, 246, 247, 247, 248, 248, 249, 249, 250,
        250, 251, 251, 252, 252, 253, 253, 254, 254, 255, 255, 255)

    /** An approximate square root a of n (unsigned, 2**62 <= n < 2**64): (a - 1)**2 < n < (a + 1)**2. */
    private fun approximateIsqrt(n: Long): Long {
        var u = ISQRT_TAB[((n ushr 56) - 64).toInt()].toLong()
        u = (u shl 7) + (n ushr 41) / u
        return ((u shl 15) + (n ushr 17) / u) and 0xffffffffL
    }

    /** isqrt: Newton's iteration in adaptive precision (math_isqrt). */
    private fun isqrt(arg: Any?): Any {
        val n = index(arg)
        if (signum(n) < 0) throw valueError("isqrt() argument must be nonnegative")
        if (signum(n) == 0) return 0L
        val bits = if (n is Long) 64 - java.lang.Long.numberOfLeadingZeros(n) else (n as BigInteger).bitLength()
        val c = (bits - 1L) / 2
        if (c <= 31) {
            // (n < 2**64: in 64 bits)
            val shift = (31 - c).toInt()
            val m = if (n is Long) n else (n as BigInteger).toLong()
            var u = approximateIsqrt(m shl 2 * shift) ushr shift
            if (java.lang.Long.compareUnsigned(u * u, m) > 0) u -= 1
            return u
        }
        val nb = n as BigInteger
        var cBitLength = 6
        while ((c shr cBitLength) > 0) ++cBitLength
        var d = c shr (cBitLength - 5)
        val m = nb.shiftRight((2 * c - 62).toInt()).toLong()
        val u = approximateIsqrt(m) ushr (31 - d).toInt()
        var a = BigInteger.valueOf(u)
        for (s in cBitLength - 6 downTo 0) {
            val e = d
            d = c shr s
            val q = nb.shiftRight((2 * c - d - e + 1).toInt()).divide(a)
            a = a.shiftLeft((d - 1 - e).toInt()).add(q)
        }
        if (nb < a.multiply(a)) a = a.subtract(BigInteger.ONE)
        return PyRt.norm(a)
    }

    // ------------------------------------------------------------------ factorial, perm, comb

    private fun bitLength(v: Long): Int = 64 - java.lang.Long.numberOfLeadingZeros(v)

    /** The product of the odd numbers in range(start, stop, 2), split in halves (factorial_partial_product). */
    private fun factorialPartialProduct(start: Long, stop: Long, maxBits: Int): BigInteger {
        val numOperands = (stop - start) / 2
        // (what fits an unsigned long multiplied in one)
        if (numOperands <= 64 && numOperands * maxBits <= 64) {
            var total = start
            var j = start + 2
            while (j < stop) {
                total *= j
                j += 2
            }
            return unsigned(total)
        }
        val midpoint = (start + numOperands) or 1L
        val left = factorialPartialProduct(start, midpoint, bitLength(midpoint - 2))
        val right = factorialPartialProduct(midpoint, stop, maxBits)
        return left.multiply(right)
    }

    /** The odd part of n! (factorial_odd_part). */
    private fun factorialOddPart(n: Long): BigInteger {
        var inner = BigInteger.ONE
        var outer = BigInteger.ONE
        var upper = 3L
        for (i in bitLength(n) - 2 downTo 0) {
            val v = n shr i
            if (v <= 2) continue
            val lower = upper
            // (the least odd integer past n / 2**i)
            upper = (v + 1) or 1L
            inner = inner.multiply(factorialPartialProduct(lower, upper, bitLength(upper - 2)))
            outer = outer.multiply(inner)
        }
        return outer
    }

    private val SMALL_FACTORIALS = longArrayOf(1, 1, 2, 6, 24, 120, 720, 5040, 40320, 362880, 3628800, 39916800,
        479001600, 6227020800, 87178291200, 1307674368000, 20922789888000, 355687428096000, 6402373705728000,
        121645100408832000, 2432902008176640000)

    private fun factorial(arg: Any?): Any {
        val n = index(arg)
        if (n is BigInteger) {
            if (n.signum() > 0) throw overflowError("factorial() argument should not exceed ${Long.MAX_VALUE}")
            throw valueError("factorial() not defined for negative values")
        }
        val x = n as Long
        if (x < 0) throw valueError("factorial() not defined for negative values")
        if (x < SMALL_FACTORIALS.size) return SMALL_FACTORIALS[x.toInt()]
        // (odd_part * 2**two_valuation)
        return PyRt.norm(factorialOddPart(x).shiftLeft((x - java.lang.Long.bitCount(x)).toInt()))
    }

    // (the odd part of n! mod 2**64, its inverse mod 2**64, the power of 2 dividing n!, for n in range(128): what
    // CPython's tables hold, made as its comments make them)
    private val REDUCED_FACTORIAL_ODD_PART = LongArray(128)
    private val INVERTED_FACTORIAL_ODD_PART = LongArray(128)
    private val FACTORIAL_TRAILING_ZEROS = IntArray(128)

    init {
        var odd = 1L
        var zeros = 0
        for (n in 0 until 128) {
            if (n > 0) {
                val t = Integer.numberOfTrailingZeros(n)
                odd *= (n shr t).toLong()
                zeros += t
            }
            REDUCED_FACTORIAL_ODD_PART[n] = odd
            FACTORIAL_TRAILING_ZEROS[n] = zeros
            // (Newton's iteration for the inverse of an odd number mod 2**64)
            var inv = odd
            repeat(6) { inv *= 2 - odd * inv }
            INVERTED_FACTORIAL_ODD_PART[n] = inv
        }
    }

    private val FAST_COMB_LIMITS1 = intArrayOf(
        0, 0, 127, 127, 127, 127, 127, 127,
        127, 127, 127, 127, 127, 127, 127, 127,
        116, 105, 97, 91, 86, 82, 78, 76,
        74, 72, 71, 70, 69, 68, 68, 67,
        67, 67, 67)
    private val FAST_COMB_LIMITS2 = longArrayOf(0, -1L /* ULLONG_MAX */, 4294967296L, 3329022, 102570, 13467, 3612, 1449,
        746, 453, 308, 227, 178, 147)
    private val FAST_PERM_LIMITS = longArrayOf(0, -1L /* ULLONG_MAX */, 4294967296L, 2642246, 65537, 7133, 1627, 568,
        259, 142, 88, 61, 45, 36, 30, 26, 24, 22, 21, 20, 20)

    private fun ule(a: Long, b: Long) = java.lang.Long.compareUnsigned(a, b) <= 0

    /** P(n, k) or C(n, k) for n in 63 bits, k > 0 (perm_comb_small). */
    private fun permCombSmall(n: Long, k: Long, comb: Boolean): BigInteger {
        if (comb) {
            // (C(n, k) in 64 bits: its odd part mod 2**64 shifted)
            if (k < FAST_COMB_LIMITS1.size && n <= FAST_COMB_LIMITS1[k.toInt()]) {
                val ni = n.toInt()
                val ki = k.toInt()
                val odd = REDUCED_FACTORIAL_ODD_PART[ni] * INVERTED_FACTORIAL_ODD_PART[ki] * INVERTED_FACTORIAL_ODD_PART[ni - ki]
                val shift = FACTORIAL_TRAILING_ZEROS[ni] - FACTORIAL_TRAILING_ZEROS[ki] - FACTORIAL_TRAILING_ZEROS[ni - ki]
                return unsigned(odd shl shift)
            }
            // (C(n, k) * k in 64 bits: C(n, k) = C(n, k-1) * (n-k+1) / k)
            if (k < FAST_COMB_LIMITS2.size && ule(n, FAST_COMB_LIMITS2[k.toInt()])) {
                var result = n
                var m = n
                var i = 1L
                while (i < k) {
                    result *= --m
                    result = java.lang.Long.divideUnsigned(result, ++i)
                }
                return unsigned(result)
            }
        } else {
            if (k < FAST_PERM_LIMITS.size && ule(n, FAST_PERM_LIMITS[k.toInt()])) {
                if (n <= 127) {
                    val ni = n.toInt()
                    val nk = (n - k).toInt()
                    val odd = REDUCED_FACTORIAL_ODD_PART[ni] * INVERTED_FACTORIAL_ODD_PART[nk]
                    val shift = FACTORIAL_TRAILING_ZEROS[ni] - FACTORIAL_TRAILING_ZEROS[nk]
                    return unsigned(odd shl shift)
                }
                // (P(n, k) = P(n, k-1) * (n-k+1))
                var result = n
                var m = n
                var i = 1L
                while (i < k) {
                    result *= --m
                    ++i
                }
                return unsigned(result)
            }
        }
        // (P(n, k) = P(n, j) * P(n-j, k-j), C(n, k) = C(n, j) * C(n-j, k-j) // C(k, j))
        val j = k / 2
        var a = permCombSmall(n, j, comb).multiply(permCombSmall(n - j, k - j, comb))
        if (comb) a = a.divide(permCombSmall(k, j, true))
        return a
    }

    /** P(n, k) or C(n, k) by the same halving, for n of any size (perm_comb). */
    private fun permComb(n: Any, k: Long, comb: Boolean): Any {
        if (k == 0L) return 1L
        if (k == 1L) return n
        val j = k / 2
        val a = permComb(n, j, comb)
        val b = permComb(PyRt.sub(n, j)!!, k - j, comb)
        var r = big(a).multiply(big(b))
        if (comb) r = r.divide(permCombSmall(k, j, true))
        return PyRt.norm(r)
    }

    private fun perm(n0: Any?, k0: Any?): Any {
        if (k0 == null || k0 === PyNone) return factorial(n0)
        val n = index(n0)
        val k = index(k0)
        if (signum(n) < 0) throw valueError("n must be a non-negative integer")
        if (signum(k) < 0) throw valueError("k must be a non-negative integer")
        if (PyRt.lt(n, k)) return 0L
        val ki = k as? Long ?: throw overflowError("k must not exceed ${Long.MAX_VALUE}")
        if (n is Long && ki > 1) return PyRt.norm(permCombSmall(n, ki, false))
        return permComb(n, ki, false)
    }

    private fun comb(n0: Any?, k0: Any?): Any {
        var n = index(n0)
        var k = index(k0)
        if (signum(n) < 0) throw valueError("n must be a non-negative integer")
        if (signum(k) < 0) throw valueError("k must be a non-negative integer")
        val ki: Long
        if (n is Long) {
            if (k !is Long || k > n) return 0L
            ki = minOf(k, n - k)
            if (ki > 1) return PyRt.norm(permCombSmall(n, ki, true))
            // (k == 1: n itself, from perm_comb)
        } else {
            val temp = PyRt.sub(n, k)!!
            if (signum(temp) < 0) return 0L
            if (PyRt.lt(temp, k)) k = temp
            ki = k as? Long ?: throw overflowError("min(n - k, k) must not exceed ${Long.MAX_VALUE}")
        }
        return permComb(n, ki, true)
    }

    // ------------------------------------------------------------------ nextafter, ulp, isclose

    private fun nextafter(x: Double, y: Double, steps: Any?): Double {
        if (steps == null || steps === PyNone) return Math.nextAfter(x, y)
        val st = index(steps)
        if (signum(st) < 0) throw valueError("steps must be a non-negative integer")
        // (as an unsigned 64-bit number; past it, UINT64_MAX)
        val usteps = if (st is Long) st else (st as BigInteger).let { if (it.bitLength() <= 64) it.toLong() else -1L }
        if (usteps == 0L) return x
        if (x.isNaN()) return x
        if (y.isNaN()) return y
        val ux = java.lang.Double.doubleToRawLongBits(x)
        val uy = java.lang.Double.doubleToRawLongBits(y)
        if (ux == uy) return x
        val signBit = Long.MIN_VALUE
        val ax = ux and signBit.inv()
        val ay = uy and signBit.inv()
        val r = if ((ux xor uy) and signBit != 0L) {
            // (opposite signs: ax + ay never overflows, their top bits clear)
            if (ule(ax + ay, usteps)) uy
            else if (java.lang.Long.compareUnsigned(ax, usteps) < 0) (uy and signBit) or (usteps - ax)
            else ux - usteps
        } else if (java.lang.Long.compareUnsigned(ax, ay) > 0) {
            if (java.lang.Long.compareUnsigned(ax - ay, usteps) >= 0) ux - usteps else uy
        } else {
            if (java.lang.Long.compareUnsigned(ay - ax, usteps) >= 0) ux + usteps else uy
        }
        return java.lang.Double.longBitsToDouble(r)
    }

    private fun ulp(x0: Double): Double {
        if (x0.isNaN()) return x0
        val x = Math.abs(x0)
        if (x.isInfinite()) return x
        val x2 = Math.nextAfter(x, Double.POSITIVE_INFINITY)
        // (the largest float: the gap below it)
        if (x2.isInfinite()) return x - Math.nextAfter(x, Double.NEGATIVE_INFINITY)
        return x2 - x
    }

    private fun isclose(a: Double, b: Double, relTol: Double, absTol: Double): Boolean {
        if (relTol < 0.0 || absTol < 0.0) throw valueError("tolerances must be non-negative")
        // (equal: two infinities of a sign too)
        if (a == b) return true
        // (an infinity and anything else, the other infinity too)
        if (a.isInfinite() || b.isInfinite()) return false
        val diff = Math.abs(b - a)
        return diff <= Math.abs(relTol * b) || diff <= Math.abs(relTol * a) || diff <= absTol
    }

    // ------------------------------------------------------------------ the module

    private fun noKw(name: String): (PyContext, Array<Any?>, PyDict) -> Any? = { _, _, _ -> throw PyRt.typeError("math.$name() takes no keyword arguments") }

    @JvmStatic
    fun fill(ctx: PyContext, d: PyDict) {
        // (one argument: METH_O)
        fun def1(name: String, f: (Any?) -> Any?) {
            d[PyStr(name)] = PyBuiltin(name, { _, a ->
                if (a.size != 1) throw PyRt.typeError("math.$name() takes exactly one argument (${a.size} given)")
                f(a[0])
            }, noKw(name))
        }
        // (by position, from min to max of them)
        fun defN(name: String, min: Int, max: Int, f: (Array<Any?>) -> Any?) {
            d[PyStr(name)] = PyBuiltin(name, { _, a ->
                checkPositional(name, a.size, min, max)
                f(a)
            }, noKw(name))
        }
        // (any number of them)
        fun defVar(name: String, f: (Array<Any?>) -> Any?) {
            d[PyStr(name)] = PyBuiltin(name, { _, a -> f(a) }, noKw(name))
        }
        fun defKw(name: String, f: (Array<Any?>, PyDict?) -> Any?) {
            d[PyStr(name)] = PyBuiltin(name, { _, a -> f(a, null) }, { _, a, kw -> f(a, kw) })
        }
        fun fun1(name: String, f: (Double) -> Double, canOverflow: Boolean, msg: String? = null) = def1(name) { math1(it, f, canOverflow, msg) }
        fun fun2(name: String, f: (Double, Double) -> Double) = defN(name, 2, 2) { math2(it, f) }

        val inRange = "expected a number in range from -1 up to 1, got %s"
        val finite = "expected a finite input, got %s"
        val gammaMsg = "expected a noninteger or positive integer, got %s"
        fun1("acos", Math::acos, false, inRange)
        fun1("acosh", ::acosh, false, "expected argument value not less than 1, got %s")
        fun1("asin", Math::asin, false, inRange)
        fun1("asinh", ::asinh, false)
        fun1("atan", Math::atan, false)
        fun2("atan2", Math::atan2)
        fun1("atanh", ::atanh, false, "expected a number between -1 and 1, got %s")
        fun1("cbrt", Math::cbrt, false)
        def1("ceil") { rounded(it, "__ceil__", Math::ceil) }
        fun2("copysign") { x, y ->
            // (the sign bit as it is, a NaN's too)
            java.lang.Double.longBitsToDouble((java.lang.Double.doubleToRawLongBits(x) and Long.MAX_VALUE) or (java.lang.Double.doubleToRawLongBits(y) and Long.MIN_VALUE))
        }
        fun1("cos", Math::cos, false, finite)
        fun1("cosh", Math::cosh, true)
        def1("degrees") { asDouble(it) * (180.0 / Math.PI) }
        defN("dist", 2, 2) { dist(it[0], it[1]) }
        def1("erf") { erf(asDouble(it)) }
        def1("erfc") { erfc(asDouble(it)) }
        fun1("exp", Math::exp, true)
        fun1("exp2", { Math.pow(2.0, it) }, true)
        fun1("expm1", Math::expm1, true)
        fun1("fabs", Math::abs, false)
        def1("factorial") { factorial(it) }
        def1("floor") { rounded(it, "__floor__", Math::floor) }
        defN("fma", 3, 3) { mathFma(asDouble(it[0]), asDouble(it[1]), asDouble(it[2])) }
        defN("fmod", 2, 2) { fmod(asDouble(it[0]), asDouble(it[1])) }
        def1("frexp") { frexp(asDouble(it)) }
        def1("fsum") { fsum(it) }
        def1("gamma") { math1a(it, ::tgamma, gammaMsg) }
        defVar("gcd") { a ->
            if (a.size == 2 && exactInt(a[0]) && exactInt(a[1])) return@defVar gcd(a[0]!!, a[1]!!)
            if (a.isEmpty()) return@defVar 0L
            var res = index(a[0])
            if (a.size == 1) return@defVar absInt(res)
            for (i in 1 until a.size) {
                val x = index(a[i])
                // (1 already: the others only checked)
                if (res == 1L) continue
                res = gcd(res, x)
            }
            res
        }
        defVar("hypot") { hypot(it) }
        defKw("isclose") { a, kw ->
            val p = clinic("isclose", a, kw, listOf("a", "b", "rel_tol", "abs_tol"), 0, 2, 2)
            val x = asDouble(p[0])
            val y = asDouble(p[1])
            val relTol = if (p[2] != null) asDouble(p[2]) else 1e-09
            val absTol = if (p[3] != null) asDouble(p[3]) else 0.0
            PyBool.of(isclose(x, y, relTol, absTol))
        }
        def1("isfinite") { PyBool.of(asDouble(it).isFinite()) }
        def1("isinf") { PyBool.of(asDouble(it).isInfinite()) }
        def1("isnan") { PyBool.of(asDouble(it).isNaN()) }
        def1("isqrt") { isqrt(it) }
        defVar("lcm") { a ->
            if (a.isEmpty()) return@defVar 1L
            var res = index(a[0])
            if (a.size == 1) return@defVar absInt(res)
            for (i in 1 until a.size) {
                val x = index(a[i])
                // (0 already: the others only checked)
                if (res == 0L) continue
                res = lcm(res, x)
            }
            res
        }
        defN("ldexp", 2, 2) { ldexp(asDouble(it[0]), it[1]) }
        def1("lgamma") { math1a(it, ::lgamma, gammaMsg) }
        defN("log", 1, 2) { a ->
            val num = logHelper(a[0], ::mLog)
            if (a.size == 1) return@defN num
            val den = logHelper(a[1], ::mLog)
            PyRt.truediv(num, den)
        }
        fun1("log1p", { if (it == 0.0) it else Math.log1p(it) }, false, "expected argument value > -1, got %s")
        def1("log10") { logHelper(it, ::mLog10) }
        def1("log2") { logHelper(it, ::mLog2) }
        def1("modf") { modf(asDouble(it)) }
        defKw("nextafter") { a, kw ->
            val p = clinic("nextafter", a, kw, listOf("x", "y", "steps"), 2, 2, 2)
            nextafter(asDouble(p[0]), asDouble(p[1]), p[2])
        }
        defN("perm", 1, 2) { perm(it[0], it.getOrNull(1)) }
        defN("pow", 2, 2) { pow(asDouble(it[0]), asDouble(it[1])) }
        defKw("prod") { a, kw ->
            val p = clinic("prod", a, kw, listOf("iterable", "start"), 1, 1, 1)
            prod(p[0], p[1])
        }
        def1("radians") { asDouble(it) * (Math.PI / 180.0) }
        fun2("remainder", ::remainder)
        fun1("sin", Math::sin, false, finite)
        fun1("sinh", Math::sinh, true)
        fun1("sqrt", Math::sqrt, false, "expected a nonnegative input, got %s")
        defN("sumprod", 2, 2) { sumprod(it[0], it[1]) }
        fun1("tan", Math::tan, false, finite)
        fun1("tanh", Math::tanh, false)
        def1("trunc") { trunc(it) }
        defN("comb", 2, 2) { comb(it[0], it[1]) }
        def1("ulp") { ulp(asDouble(it)) }
        d[PyStr("pi")] = Math.PI
        d[PyStr("e")] = Math.E
        d[PyStr("tau")] = 2.0 * Math.PI
        d[PyStr("inf")] = Double.POSITIVE_INFINITY
        d[PyStr("nan")] = Double.NaN
    }
}
