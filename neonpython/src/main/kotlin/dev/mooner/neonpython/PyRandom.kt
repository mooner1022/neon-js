package dev.mooner.neonpython

import java.math.BigInteger

/** A _random.Random (or a subclass's instance): the Mersenne Twister's state. */
class PyMersenne(t: PyType) : PyObject(t) {
    @JvmField val mt = IntArray(PyRandom.N)
    @JvmField var index = PyRandom.N + 1
}

/**
 * `_random` (docs/PYTHON_PLATFORM.md §12, S2): Modules/_randommodule.c, the Mersenne Twister (MT19937) as CPython
 * seeds and draws from it, so a seeded random.Random gives CPython's numbers (random.seed's sha512 of a str or bytes
 * seed is `_sha2`'s, PyHashlib).
 */
object PyRandom {
    const val N = 624
    private const val M = 397
    private const val MATRIX_A = 0x9908b0df.toInt()
    private const val UPPER_MASK = 0x80000000.toInt()
    private const val LOWER_MASK = 0x7fffffff
    private val NO_ARGS = arrayOfNulls<Any?>(0)
    private val ENTROPY = java.security.SecureRandom()

    @JvmField val RANDOM = PyTypes.newBuiltin("Random").also {
        it.module = "_random"
        it.tpName = "_random.Random"
        it.dispatchBuiltins = true
    }

    private fun genrand(r: PyMersenne): Int {
        val mt = r.mt
        if (r.index >= N) {
            var kk = 0
            while (kk < N - M) {
                val y = (mt[kk] and UPPER_MASK) or (mt[kk + 1] and LOWER_MASK)
                mt[kk] = mt[kk + M] xor (y ushr 1) xor (if (y and 1 != 0) MATRIX_A else 0)
                kk++
            }
            while (kk < N - 1) {
                val y = (mt[kk] and UPPER_MASK) or (mt[kk + 1] and LOWER_MASK)
                mt[kk] = mt[kk + (M - N)] xor (y ushr 1) xor (if (y and 1 != 0) MATRIX_A else 0)
                kk++
            }
            val y = (mt[N - 1] and UPPER_MASK) or (mt[0] and LOWER_MASK)
            mt[N - 1] = mt[M - 1] xor (y ushr 1) xor (if (y and 1 != 0) MATRIX_A else 0)
            r.index = 0
        }
        var y = mt[r.index++]
        y = y xor (y ushr 11)
        y = y xor ((y shl 7) and 0x9d2c5680.toInt())
        y = y xor ((y shl 15) and 0xefc60000.toInt())
        y = y xor (y ushr 18)
        return y
    }

    private fun initGenrand(r: PyMersenne, s: Int) {
        val mt = r.mt
        mt[0] = s
        for (i in 1 until N) mt[i] = 1812433253 * (mt[i - 1] xor (mt[i - 1] ushr 30)) + i
        r.index = N
    }

    private fun initByArray(r: PyMersenne, key: IntArray) {
        val mt = r.mt
        initGenrand(r, 19650218)
        var i = 1
        var j = 0
        var k = maxOf(N, key.size)
        while (k > 0) {
            mt[i] = (mt[i] xor ((mt[i - 1] xor (mt[i - 1] ushr 30)) * 1664525)) + key[j] + j
            i++
            j++
            if (i >= N) {
                mt[0] = mt[N - 1]
                i = 1
            }
            if (j >= key.size) j = 0
            k--
        }
        k = N - 1
        while (k > 0) {
            mt[i] = (mt[i] xor ((mt[i - 1] xor (mt[i - 1] ushr 30)) * 1566083941)) - i
            i++
            if (i >= N) {
                mt[0] = mt[N - 1]
                i = 1
            }
            k--
        }
        mt[0] = UPPER_MASK
    }

    /** random_seed: None from the platform's entropy; an int's absolute value; anything else its hash, unsigned. */
    private fun seed(r: PyMersenne, arg: Any?) {
        if (arg == null || arg === PyNone) {
            val key = IntArray(N)
            val b = ByteArray(N * 4)
            ENTROPY.nextBytes(b)
            for (i in 0 until N) key[i] = (b[4 * i].toInt() and 0xFF) or ((b[4 * i + 1].toInt() and 0xFF) shl 8) or
                ((b[4 * i + 2].toInt() and 0xFF) shl 16) or ((b[4 * i + 3].toInt() and 0xFF) shl 24)
            initByArray(r, key)
            return
        }
        val n: BigInteger = when (val v = intValue(arg)) {
            null -> BigInteger.valueOf(PyHash.hash(arg)).let { h -> if (h.signum() < 0) h.add(BigInteger.ONE.shiftLeft(64)) else h }
            else -> v.abs()
        }
        val bits = n.bitLength()
        val words = if (bits == 0) 1 else (bits - 1) / 32 + 1
        val key = IntArray(words) { n.shiftRight(32 * it).toInt() }
        initByArray(r, key)
    }

    /** An int's value (a subclass's too, as int.__abs__ takes it), or null for anything else. */
    private fun intValue(v: Any?): BigInteger? = when (v) {
        is Long -> BigInteger.valueOf(v)
        is BigInteger -> v
        is PyBool -> if (v === PyBool.TRUE) BigInteger.ONE else BigInteger.ZERO
        is PyNumSub -> (v.num as? Long)?.let { BigInteger.valueOf(it) } ?: v.num as? BigInteger
        else -> null
    }

    private fun self(a: Array<Any?>, fn: String): PyMersenne =
        a.getOrNull(0) as? PyMersenne ?: throw PyRt.typeError("descriptor '$fn' for '_random.Random' objects doesn't apply to a '${PyTypes.name(a.getOrNull(0))}' object")

    private fun noKw(fn: String, kw: PyDict?) {
        if (kw != null && kw.size > 0) throw PyRt.typeError("$fn() takes no keyword arguments")
    }

    private fun random(r: PyMersenne): Double {
        val a = genrand(r) ushr 5
        val b = genrand(r) ushr 6
        return (a * 67108864.0 + b) * (1.0 / 9007199254740992.0)
    }

    private fun getrandbits(r: PyMersenne, kArg: Any?): Any? {
        val kv = PyRt.indexValue(kArg) ?: throw PyRt.typeError("'${PyTypes.name(kArg)}' object cannot be interpreted as an integer")
        val k = when (kv) {
            is Long -> kv
            is PyBool -> if (kv === PyBool.TRUE) 1L else 0L
            is BigInteger -> if (kv.signum() < 0) -1L else throw PyException("OverflowError", "Python int too large to convert to C unsigned long long")
            else -> 0L
        }
        if (k < 0) throw PyException("ValueError", "Cannot convert negative int")
        if (k == 0L) return 0L
        if (k <= 32) return (genrand(r).toLong() and 0xFFFFFFFFL) ushr (32 - k).toInt()
        if (k > Int.MAX_VALUE.toLong() * 8) throw PyException("MemoryError", "")
        val words = ((k - 1) / 32 + 1).toInt()
        val bytes = ByteArray(words * 4)
        var left = k
        for (i in 0 until words) {
            var w = genrand(r)
            if (left < 32) w = w ushr (32 - left).toInt()
            // (little-endian words, least significant first, into a big-endian magnitude)
            val at = bytes.size - 4 * (i + 1)
            bytes[at] = (w ushr 24).toByte()
            bytes[at + 1] = (w ushr 16).toByte()
            bytes[at + 2] = (w ushr 8).toByte()
            bytes[at + 3] = w.toByte()
            left -= 32
        }
        val v = BigInteger(1, bytes)
        return if (v.bitLength() < 64) v.toLong() else v
    }

    private fun unsigned32(v: Any?): Int {
        val n = intValue(PyRt.indexValue(v) ?: throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer"))!!
        if (n.signum() < 0) throw PyException("OverflowError", "can't convert negative value to unsigned int")
        if (n.bitLength() > 64) throw PyException("OverflowError", "Python int too large to convert to C unsigned long")
        return n.toLong().toInt()
    }

    init {
        val t = RANDOM
        t.def("__new__", PyBuiltin("__new__", { _, a -> PyMersenne(a[0] as PyType) }, { _, a, kw ->
            val cls = a[0] as PyType
            // (PyType_GenericNew; a subclass may take keywords in its own __init__)
            if (cls === t) noKw("Random", kw)
            PyMersenne(cls)
        }))
        t.def("__init__", PyBuiltin("__init__", { _, a ->
            val r = self(a, "__init__")
            if (a.size > 2) throw PyRt.typeError("Random expected at most 1 argument, got ${a.size - 1}")
            seed(r, a.getOrNull(1))
            PyNone
        }, { _, a, kw ->
            val r = self(a, "__init__")
            noKw("Random", kw)
            if (a.size > 2) throw PyRt.typeError("Random expected at most 1 argument, got ${a.size - 1}")
            seed(r, a.getOrNull(1))
            PyNone
        }))
        t.def("random", PyBuiltin("random") { _, a -> random(self(a, "random")) })
        t.def("seed", PyBuiltin("seed") { _, a ->
            if (a.size > 2) throw PyRt.typeError("seed expected at most 1 argument, got ${a.size - 1}")
            seed(self(a, "seed"), a.getOrNull(1))
            PyNone
        })
        t.def("getstate", PyBuiltin("getstate") { _, a ->
            val r = self(a, "getstate")
            PyTuple(Array(N + 1) { i -> if (i < N) r.mt[i].toLong() and 0xFFFFFFFFL else r.index.toLong() })
        })
        t.def("setstate", PyBuiltin("setstate") { _, a ->
            val r = self(a, "setstate")
            if (a.size != 2) throw PyRt.typeError("setstate() takes exactly one argument (${a.size - 1} given)")
            val st = a[1] as? PyTuple ?: throw PyRt.typeError("state vector must be a tuple")
            if (st.items.size != N + 1) throw PyException("ValueError", "state vector is the wrong size")
            val fresh = IntArray(N) { unsigned32(st.items[it]) }
            val index = intValue(PyRt.indexValue(st.items[N]) ?: throw PyRt.typeError("'${PyTypes.name(st.items[N])}' object cannot be interpreted as an integer"))!!
            if (index.signum() < 0 || index > BigInteger.valueOf(N.toLong())) throw PyException("ValueError", "invalid state")
            r.index = index.toInt()
            fresh.copyInto(r.mt)
            PyNone
        })
        t.def("getrandbits", PyBuiltin("getrandbits") { _, a ->
            if (a.size != 2) throw PyRt.typeError("getrandbits() takes exactly one argument (${a.size - 1} given)")
            getrandbits(self(a, "getrandbits"), a[1])
        })
    }

    @JvmStatic
    fun fill(d: PyDict) {
        d[PyStr("Random")] = RANDOM
        d[PyStr("__doc__")] = PyStr("Module implements the Mersenne Twister random number generator.")
    }
}
