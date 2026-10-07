package io.neonjs.unicode

/**
 * Operations on code point sets represented as inversion lists: a sorted IntArray `p0 < p1 < ...` describing the
 * half-open ranges `[p0, p1), [p2, p3), ...`. Arrays are treated as immutable values.
 */
object CharRanges {
    const val MAX = 0x110000

    @JvmField val EMPTY = IntArray(0)
    @JvmField val ALL = intArrayOf(0, MAX)

    fun of(lo: Int, hiInclusive: Int): IntArray = intArrayOf(lo, hiInclusive + 1)

    fun single(c: Int): IntArray = intArrayOf(c, c + 1)

    /** Membership test (binary search). */
    @JvmStatic
    fun contains(set: IntArray, c: Int): Boolean {
        var lo = 0
        var hi = set.size - 1
        if (hi < 0 || c < set[0] || c >= set[hi]) return false
        // find the largest i with set[i] <= c
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (set[mid] <= c) lo = mid else hi = mid - 1
        }
        return lo and 1 == 0
    }

    fun isEmpty(set: IntArray) = set.isEmpty()

    /** Number of code points in the set. */
    fun size(set: IntArray): Long {
        var n = 0L
        var i = 0
        while (i < set.size) {
            n += set[i + 1] - set[i]
            i += 2
        }
        return n
    }

    const val OP_UNION = 0
    const val OP_INTER = 1
    const val OP_SUB = 2

    /** Generic merge of two inversion lists. */
    fun op(a: IntArray, b: IntArray, op: Int): IntArray {
        val out = IntArray(a.size + b.size)
        var n = 0
        var ai = 0
        var bi = 0
        while (true) {
            val v: Int
            if (ai < a.size && bi < b.size) {
                val x = a[ai]
                val y = b[bi]
                if (x < y) { v = x; ai++ } else if (x == y) { v = x; ai++; bi++ } else { v = y; bi++ }
            } else if (ai < a.size) {
                v = a[ai++]
            } else if (bi < b.size) {
                v = b[bi++]
            } else break
            val inA = ai and 1
            val inB = bi and 1
            val isIn = when (op) {
                OP_UNION -> inA or inB
                OP_INTER -> inA and inB
                OP_SUB -> inA and (inB xor 1)
                else -> inA xor inB
            }
            if (isIn != (n and 1)) {
                if (n > 0 && out[n - 1] == v) n-- else out[n++] = v
            }
        }
        return if (n == out.size) out else out.copyOf(n)
    }

    fun union(a: IntArray, b: IntArray): IntArray = if (a.isEmpty()) b else if (b.isEmpty()) a else op(a, b, OP_UNION)
    fun subtract(a: IntArray, b: IntArray): IntArray = if (a.isEmpty() || b.isEmpty()) a else op(a, b, OP_SUB)

    fun invert(a: IntArray): IntArray = op(ALL, a, OP_SUB)

    /** Builds an inversion list from arbitrary (unsorted, overlapping) inclusive ranges. */
    class Builder {
        private var buf = IntArray(16)
        private var n = 0
        private var sorted = true

        fun add(c: Int) = addRange(c, c)

        fun addRange(lo: Int, hiInclusive: Int): Builder {
            if (n + 2 > buf.size) buf = buf.copyOf(buf.size * 2)
            if (n > 0 && lo < buf[n - 2]) sorted = false
            buf[n++] = lo
            buf[n++] = hiInclusive + 1
            return this
        }

        fun addSet(set: IntArray): Builder {
            var i = 0
            while (i < set.size) {
                addRange(set[i], set[i + 1] - 1)
                i += 2
            }
            return this
        }

        fun build(): IntArray {
            val count = n / 2
            if (!sorted) {
                // sort ranges by start
                val idx = (0 until count).sortedBy { buf[it * 2] }
                val nb = IntArray(n)
                for ((k, i) in idx.withIndex()) {
                    nb[k * 2] = buf[i * 2]
                    nb[k * 2 + 1] = buf[i * 2 + 1]
                }
                buf = nb
                sorted = true
            }
            val out = IntArray(n)
            var m = 0
            var i = 0
            while (i < n) {
                val s = buf[i]
                val e = buf[i + 1]
                i += 2
                if (s >= e) continue
                if (m > 0 && s <= out[m - 1]) {
                    if (e > out[m - 1]) out[m - 1] = e
                } else {
                    out[m++] = s
                    out[m++] = e
                }
            }
            return out.copyOf(m)
        }
    }
}
