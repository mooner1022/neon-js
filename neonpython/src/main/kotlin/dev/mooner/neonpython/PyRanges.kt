package dev.mooner.neonpython

import java.math.BigInteger

/**
 * range as CPython 3.14 has it: its bounds any ints. A range whose values all fit a Long, and whose length does, keeps
 * them as Longs (its iterator the fast one, [PyRt.iterValues]); one past them keeps them as BigIntegers too
 * ([PyRange.big]: start, stop, step), its iterator a longrange_iterator.
 */
object PyRanges {
    private val LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE)
    private val LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE)

    @JvmField val LONGRANGE_ITERATOR = PyLibrary.type("longrange_iterator")

    private fun fits(v: BigInteger) = v >= LONG_MIN && v <= LONG_MAX

    private fun big(v: Any?): BigInteger = when (v) {
        is Long -> BigInteger.valueOf(v)
        is BigInteger -> v
        is PyBool -> if (v.value) BigInteger.ONE else BigInteger.ZERO
        else -> throw IllegalStateException("not an int: $v")
    }

    /** An argument of range(): an int, or what __index__ gives. */
    private fun arg(v: Any?): Any = when (val i = PyRt.indexValue(v)) {
        is Long, is BigInteger -> i
        is PyBool -> if (i.value) 1L else 0L
        else -> throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer")
    }

    /** range(start, stop, step) of ints ([arg]s). */
    @JvmStatic
    fun make(start: Any, stop: Any, step: Any): PyRange {
        if (step is Long && step == 0L || step is BigInteger && step.signum() == 0) throw PyException("ValueError", "range() arg 3 must not be zero")
        if (start is Long && stop is Long && step is Long) {
            // (as Longs where its last value and the one past it fit too: the iterator adds the step to the last)
            try {
                val n = lengthOf(start, stop, step)
                if (n > 0) Math.addExact(Math.addExact(start, Math.multiplyExact(n - 1, step)), step)
                return PyRange(start, stop, step)
            } catch (e: ArithmeticException) {
                // (past a Long somewhere: as BigIntegers)
            }
        }
        val b = arrayOf(big(start), big(stop), big(step))
        return PyRange(0L, 0L, 1L, b)
    }

    @JvmStatic
    fun make(args: Array<Any?>): PyRange = when (args.size) {
        1 -> make(0L, arg(args[0]), 1L)
        2 -> make(arg(args[0]), arg(args[1]), 1L)
        else -> make(arg(args[0]), arg(args[1]), arg(args[2]))
    }

    private fun lengthOf(start: Long, stop: Long, step: Long): Long = when {
        step > 0 && start < stop -> Math.addExact(Math.subtractExact(stop, start) - 1, step) / step
        step < 0 && start > stop -> Math.addExact(Math.subtractExact(start, stop) - 1, -step) / -step
        else -> 0L
    }

    @JvmStatic fun start(r: PyRange): Any = r.big?.let { norm(it[0]) } ?: r.start
    @JvmStatic fun stop(r: PyRange): Any = r.big?.let { norm(it[1]) } ?: r.stop
    @JvmStatic fun step(r: PyRange): Any = r.big?.let { norm(it[2]) } ?: r.step

    private fun norm(v: BigInteger): Any = PyRt.norm(v)

    private fun triple(r: PyRange): Array<BigInteger> = r.big ?: arrayOf(BigInteger.valueOf(r.start), BigInteger.valueOf(r.stop), BigInteger.valueOf(r.step))

    /** Its length, any int. */
    @JvmStatic
    fun lengthBig(r: PyRange): BigInteger {
        if (r.big == null) return BigInteger.valueOf(r.length)
        val (a, b, s) = r.big
        return when {
            s.signum() > 0 && a < b -> (b - a - BigInteger.ONE) / s + BigInteger.ONE
            s.signum() < 0 && a > b -> (a - b - BigInteger.ONE) / s.negate() + BigInteger.ONE
            else -> BigInteger.ZERO
        }
    }

    /** len(r): CPython's OverflowError past a C ssize_t. */
    @JvmStatic
    fun len(r: PyRange): Long {
        if (r.big == null) return r.length
        val n = lengthBig(r)
        if (!fits(n)) throw PyException("OverflowError", "Python int too large to convert to C ssize_t")
        return n.toLong()
    }

    @JvmStatic fun isEmpty(r: PyRange): Boolean = if (r.big == null) r.length == 0L else lengthBig(r).signum() == 0

    /** r[i]. */
    @JvmStatic
    fun item(r: PyRange, key: Any?): Any? {
        val i = PyRt.indexValue(key)
        if (i !is Long && i !is BigInteger) throw PyRt.typeError("range indices must be integers or slices, not ${PyTypes.name(key)}")
        if (r.big == null && i is Long) {
            val n = r.length
            val k = if (i < 0) i + n else i
            if (k < 0 || k >= n) throw PyException("IndexError", "range object index out of range")
            return r.start + r.step * k
        }
        val n = lengthBig(r)
        var k = big(i)
        if (k.signum() < 0) k += n
        if (k.signum() < 0 || k >= n) throw PyException("IndexError", "range object index out of range")
        val (a, _, s) = triple(r)
        return norm(a + s * k)
    }

    /** r[slice]: CPython's compute_slice over the slice's indices of any size. */
    @JvmStatic
    fun slice(r: PyRange, sl: PySlice): PyRange {
        val n = lengthBig(r)
        fun idx(v: Any?): BigInteger? {
            if (v == null || v === PyNone) return null
            val i = PyRt.indexValue(v)
            if (i !is Long && i !is BigInteger && i !is PyBool) throw PyRt.typeError("slice indices must be integers or None or have an __index__ method")
            return big(i)
        }
        val st = idx(sl.step) ?: BigInteger.ONE
        if (st.signum() == 0) throw PyException("ValueError", "slice step cannot be zero")
        val neg = st.signum() < 0
        val lower = if (neg) BigInteger.ONE.negate() else BigInteger.ZERO
        val upper = if (neg) n - BigInteger.ONE else n
        fun bound(v: BigInteger?, dflt: BigInteger): BigInteger {
            if (v == null) return dflt
            var x = v
            if (x.signum() < 0) {
                x += n
                if (x < lower) x = lower
            } else if (x > upper) x = upper
            return x
        }
        val a = bound(idx(sl.start), if (neg) upper else lower)
        val b = bound(idx(sl.stop), if (neg) lower else upper)
        val (s0, _, step0) = triple(r)
        return make(norm(s0 + a * step0), norm(s0 + b * step0), norm(step0 * st))
    }

    /** v in r: arithmetic for an int (or a bool), else each value compared with ==, as CPython does. */
    @JvmStatic
    fun contains(r: PyRange, v: Any?): Boolean {
        if (v is Long && r.big == null) {
            return (if (r.step > 0) v >= r.start && v < r.stop else v <= r.start && v > r.stop) && (v - r.start) % r.step == 0L
        }
        if (v is Long || v is BigInteger || v is PyBool) return indexOfInt(r, big(v)) != null
        return search(r, v, false) >= 0
    }

    /** The index of int [v] in [r], or null. */
    private fun indexOfInt(r: PyRange, v: BigInteger): BigInteger? {
        val (a, b, s) = triple(r)
        val inside = if (s.signum() > 0) v >= a && v < b else v <= a && v > b
        if (!inside) return null
        val (q, m) = (v - a).divideAndRemainder(s)
        return if (m.signum() == 0) q else null
    }

    /** The first index of a value == [v] (-1: none), or with [count] how many are. */
    private fun search(r: PyRange, v: Any?, count: Boolean): Long {
        val it = iter(r)
        var i = 0L
        var n = 0L
        while (true) {
            val x = it.next()
            if (x === PyIterator.STOP) break
            if (PyRt.eq(x, v)) {
                if (!count) return i
                n++
            }
            i++
        }
        return if (count) n else -1L
    }

    @JvmStatic
    fun index(r: PyRange, v: Any?): Any? {
        if (v is Long || v is BigInteger || v is PyBool) {
            return indexOfInt(r, big(v))?.let { norm(it) } ?: throw PyException("ValueError", "range.index(x): x not in range")
        }
        val i = search(r, v, false)
        if (i < 0) throw PyException("ValueError", "sequence.index(x): x not in sequence")
        return i
    }

    @JvmStatic
    fun count(r: PyRange, v: Any?): Long =
        if (v is Long || v is BigInteger || v is PyBool) (if (indexOfInt(r, big(v)) != null) 1L else 0L) else search(r, v, true)

    /** a == b: the same values (lengths, and the first and the step where they matter). */
    @JvmStatic
    fun eq(a: PyRange, b: PyRange): Boolean {
        if (a === b) return true
        val n = lengthBig(a)
        if (n != lengthBig(b)) return false
        if (n.signum() == 0) return true
        val (sa, _, ta) = triple(a)
        val (sb, _, tb) = triple(b)
        if (sa != sb) return false
        return n == BigInteger.ONE || ta == tb
    }

    /** hash(r): CPython's, a tuple's of (length, start, step), None for those that do not matter. */
    @JvmStatic
    fun hash(r: PyRange): Long {
        val n = lengthBig(r)
        val (s, _, t) = triple(r)
        val items: Array<Any?> = when {
            n.signum() == 0 -> arrayOf(0L, PyNone, PyNone)
            n == BigInteger.ONE -> arrayOf(1L, norm(s), PyNone)
            else -> arrayOf(norm(n), norm(s), norm(t))
        }
        return PyHash.tuple(items)
    }

    @JvmStatic
    fun repr(r: PyRange): String {
        val step = step(r)
        return if (step == 1L) "range(${start(r)}, ${stop(r)})" else "range(${start(r)}, ${stop(r)}, $step)"
    }

    /** iter(r): the runtime's iterator where its values are Longs, else a longrange_iterator. */
    @JvmStatic
    fun iter(r: PyRange): PyIterator {
        val big = r.big ?: return PyRt.iterValues(r)
        var cur = big[0]
        val stop = big[1]
        val step = big[2]
        val up = step.signum() > 0
        return PyIterator {
            if (if (up) cur < stop else cur > stop) norm(cur).also { cur += step } else PyIterator.STOP
        }.also { it.type = LONGRANGE_ITERATOR }
    }

    /** reversed(r): a range's from its last value down (a longrange_iterator where those are past a Long). */
    @JvmStatic
    fun reversed(r: PyRange): PyIterator {
        val n = lengthBig(r)
        val (s, _, t) = triple(r)
        val last = s + (n - BigInteger.ONE) * t
        val rev = make(norm(last), norm(s - t), norm(t.negate()))
        if (n.signum() == 0) return PyRt.iterValues(PyRange(0L, 0L, 1L)).also { it.type = PyLibrary.type("range_iterator") }
        return iter(rev)
    }
}
