package dev.mooner.neonpython

import java.math.BigInteger
import java.security.MessageDigest

/** A hash object of _md5, _sha1, _sha2, _blake2 or _sha3: its algorithm's state so far. */
class PyHashObject(t: PyType, @JvmField val state: PyHashlib.State) : PyObject(t)

/**
 * The hash modules hashlib is made of, as CPython 3.14 builds them without OpenSSL (its `_hashlib`, which NeonPy has
 * not): `_md5`, `_sha1` and `_sha2` (Modules/md5module.c, sha1module.c, sha2module.c) over the platform's
 * MessageDigest, and `_blake2` and `_sha3` (Modules/blake2module.c, sha3module.c: BLAKE2b and BLAKE2s with all their
 * parameters, SHA3-224 to 512, SHAKE128 and SHAKE256), whose algorithms are here, so that no JVM or Android provider
 * is needed. Their types, attributes, argument parsing and messages are CPython's; where a C `unsigned long` is
 * taken, NeonPy's is Linux's (64 bits), as elsewhere.
 */
object PyHashlib {
    private const val GIL_MINSIZE = 2048L

    /** An algorithm's state: [digest] what was hashed so far (the state goes on). */
    abstract class State {
        abstract val digestSize: Int
        abstract val blockSize: Int
        abstract fun update(b: ByteArray, off: Int, len: Int)
        abstract fun digest(): ByteArray
        abstract fun copy(): State
        /** SHAKE's output of [n] bytes. */
        open fun squeeze(n: Int): ByteArray = throw UnsupportedOperationException()
    }

    // ------------------------------------------------------------------ MD5, SHA-1, SHA-2

    private class Md(val md: MessageDigest, override val blockSize: Int) : State() {
        override val digestSize get() = md.digestLength
        override fun update(b: ByteArray, off: Int, len: Int) = md.update(b, off, len)
        override fun digest(): ByteArray = (md.clone() as MessageDigest).digest()
        override fun copy(): State = Md(md.clone() as MessageDigest, blockSize)
    }

    // ------------------------------------------------------------------ BLAKE2 (RFC 7693)

    private val SIGMA = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
        intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
        intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
        intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
        intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
        intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
        intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
        intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
        intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
        intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
    )

    private val IV_B = longArrayOf(
        0x6a09e667f3bcc908L, -0x4498517a7b3558c5L, 0x3c6ef372fe94f82bL, -0x5ab00ac5a0e2c90fL,
        0x510e527fade682d1L, -0x64fa9773d4c193e1L, 0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L,
    )

    private val IV_S = intArrayOf(
        0x6A09E667, 0xBB67AE85.toInt(), 0x3C6EF372, 0xA54FF53A.toInt(), 0x510E527F, 0x9B05688C.toInt(), 0x1F83D9AB, 0x5BE0CD19,
    )

    /** The parameters (blake2b's and blake2s's): its parameter block's fields. */
    private class Params(
        val digestSize: Int, val key: ByteArray, val salt: ByteArray, val person: ByteArray, val fanout: Int, val depth: Int,
        val leafSize: Long, val nodeOffset: Long, val nodeDepth: Int, val innerSize: Int, val lastNode: Boolean,
    )

    private fun le64(b: ByteArray, at: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }

    private fun le32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    private class Blake2b private constructor(override val digestSize: Int, val lastNode: Boolean) : State() {
        override val blockSize get() = 128
        val h = LongArray(8)
        val buf = ByteArray(128)
        var bufLen = 0
        var t0 = 0L
        var t1 = 0L

        constructor(p: Params) : this(p.digestSize, p.lastNode) {
            val pb = ByteArray(64)
            pb[0] = p.digestSize.toByte()
            pb[1] = p.key.size.toByte()
            pb[2] = p.fanout.toByte()
            pb[3] = p.depth.toByte()
            for (i in 0 until 4) pb[4 + i] = (p.leafSize ushr (8 * i)).toByte()
            for (i in 0 until 8) pb[8 + i] = (p.nodeOffset ushr (8 * i)).toByte()
            pb[16] = p.nodeDepth.toByte()
            pb[17] = p.innerSize.toByte()
            p.salt.copyInto(pb, 32)
            p.person.copyInto(pb, 48)
            for (i in 0 until 8) h[i] = IV_B[i] xor le64(pb, 8 * i)
            if (p.key.isNotEmpty()) {
                val block = ByteArray(128)
                p.key.copyInto(block)
                update(block, 0, 128)
            }
        }

        override fun update(b: ByteArray, off: Int, len: Int) {
            var o = off
            var n = len
            while (n > 0) {
                if (bufLen == 128) {
                    count(128)
                    compress(buf, false)
                    bufLen = 0
                }
                val take = minOf(128 - bufLen, n)
                System.arraycopy(b, o, buf, bufLen, take)
                bufLen += take
                o += take
                n -= take
            }
        }

        private fun count(n: Int) {
            val old = t0
            t0 += n
            if (java.lang.Long.compareUnsigned(t0, old) < 0) t1++
        }

        private fun compress(block: ByteArray, last: Boolean) {
            val m = LongArray(16) { le64(block, 8 * it) }
            val v = LongArray(16)
            h.copyInto(v)
            IV_B.copyInto(v, 8)
            v[12] = v[12] xor t0
            v[13] = v[13] xor t1
            if (last) {
                v[14] = v[14].inv()
                if (lastNode) v[15] = v[15].inv()
            }
            for (r in 0 until 12) {
                val s = SIGMA[r % 10]
                g(v, 0, 4, 8, 12, m[s[0]], m[s[1]])
                g(v, 1, 5, 9, 13, m[s[2]], m[s[3]])
                g(v, 2, 6, 10, 14, m[s[4]], m[s[5]])
                g(v, 3, 7, 11, 15, m[s[6]], m[s[7]])
                g(v, 0, 5, 10, 15, m[s[8]], m[s[9]])
                g(v, 1, 6, 11, 12, m[s[10]], m[s[11]])
                g(v, 2, 7, 8, 13, m[s[12]], m[s[13]])
                g(v, 3, 4, 9, 14, m[s[14]], m[s[15]])
            }
            for (i in 0 until 8) h[i] = h[i] xor v[i] xor v[i + 8]
        }

        private fun g(v: LongArray, a: Int, b: Int, c: Int, d: Int, x: Long, y: Long) {
            v[a] += v[b] + x
            v[d] = java.lang.Long.rotateRight(v[d] xor v[a], 32)
            v[c] += v[d]
            v[b] = java.lang.Long.rotateRight(v[b] xor v[c], 24)
            v[a] += v[b] + y
            v[d] = java.lang.Long.rotateRight(v[d] xor v[a], 16)
            v[c] += v[d]
            v[b] = java.lang.Long.rotateRight(v[b] xor v[c], 63)
        }

        override fun digest(): ByteArray {
            val c = copy() as Blake2b
            c.count(c.bufLen)
            java.util.Arrays.fill(c.buf, c.bufLen, 128, 0)
            c.compress(c.buf, true)
            val out = ByteArray(digestSize)
            for (i in 0 until digestSize) out[i] = (c.h[i / 8] ushr (8 * (i % 8))).toByte()
            return out
        }

        override fun copy(): State {
            val c = Blake2b(digestSize, lastNode)
            h.copyInto(c.h)
            buf.copyInto(c.buf)
            c.bufLen = bufLen
            c.t0 = t0
            c.t1 = t1
            return c
        }
    }

    private class Blake2s private constructor(override val digestSize: Int, val lastNode: Boolean) : State() {
        override val blockSize get() = 64
        val h = IntArray(8)
        val buf = ByteArray(64)
        var bufLen = 0
        var t0 = 0
        var t1 = 0

        constructor(p: Params) : this(p.digestSize, p.lastNode) {
            val pb = ByteArray(32)
            pb[0] = p.digestSize.toByte()
            pb[1] = p.key.size.toByte()
            pb[2] = p.fanout.toByte()
            pb[3] = p.depth.toByte()
            for (i in 0 until 4) pb[4 + i] = (p.leafSize ushr (8 * i)).toByte()
            for (i in 0 until 6) pb[8 + i] = (p.nodeOffset ushr (8 * i)).toByte()
            pb[14] = p.nodeDepth.toByte()
            pb[15] = p.innerSize.toByte()
            p.salt.copyInto(pb, 16)
            p.person.copyInto(pb, 24)
            for (i in 0 until 8) h[i] = IV_S[i] xor le32(pb, 4 * i)
            if (p.key.isNotEmpty()) {
                val block = ByteArray(64)
                p.key.copyInto(block)
                update(block, 0, 64)
            }
        }

        override fun update(b: ByteArray, off: Int, len: Int) {
            var o = off
            var n = len
            while (n > 0) {
                if (bufLen == 64) {
                    count(64)
                    compress(buf, false)
                    bufLen = 0
                }
                val take = minOf(64 - bufLen, n)
                System.arraycopy(b, o, buf, bufLen, take)
                bufLen += take
                o += take
                n -= take
            }
        }

        private fun count(n: Int) {
            val old = t0
            t0 += n
            if (Integer.compareUnsigned(t0, old) < 0) t1++
        }

        private fun compress(block: ByteArray, last: Boolean) {
            val m = IntArray(16) { le32(block, 4 * it) }
            val v = IntArray(16)
            h.copyInto(v)
            IV_S.copyInto(v, 8)
            v[12] = v[12] xor t0
            v[13] = v[13] xor t1
            if (last) {
                v[14] = v[14].inv()
                if (lastNode) v[15] = v[15].inv()
            }
            for (r in 0 until 10) {
                val s = SIGMA[r]
                g(v, 0, 4, 8, 12, m[s[0]], m[s[1]])
                g(v, 1, 5, 9, 13, m[s[2]], m[s[3]])
                g(v, 2, 6, 10, 14, m[s[4]], m[s[5]])
                g(v, 3, 7, 11, 15, m[s[6]], m[s[7]])
                g(v, 0, 5, 10, 15, m[s[8]], m[s[9]])
                g(v, 1, 6, 11, 12, m[s[10]], m[s[11]])
                g(v, 2, 7, 8, 13, m[s[12]], m[s[13]])
                g(v, 3, 4, 9, 14, m[s[14]], m[s[15]])
            }
            for (i in 0 until 8) h[i] = h[i] xor v[i] xor v[i + 8]
        }

        private fun g(v: IntArray, a: Int, b: Int, c: Int, d: Int, x: Int, y: Int) {
            v[a] += v[b] + x
            v[d] = Integer.rotateRight(v[d] xor v[a], 16)
            v[c] += v[d]
            v[b] = Integer.rotateRight(v[b] xor v[c], 12)
            v[a] += v[b] + y
            v[d] = Integer.rotateRight(v[d] xor v[a], 8)
            v[c] += v[d]
            v[b] = Integer.rotateRight(v[b] xor v[c], 7)
        }

        override fun digest(): ByteArray {
            val c = copy() as Blake2s
            c.count(c.bufLen)
            java.util.Arrays.fill(c.buf, c.bufLen, 64, 0)
            c.compress(c.buf, true)
            val out = ByteArray(digestSize)
            for (i in 0 until digestSize) out[i] = (c.h[i / 4] ushr (8 * (i % 4))).toByte()
            return out
        }

        override fun copy(): State {
            val c = Blake2s(digestSize, lastNode)
            h.copyInto(c.h)
            buf.copyInto(c.buf)
            c.bufLen = bufLen
            c.t0 = t0
            c.t1 = t1
            return c
        }
    }

    // ------------------------------------------------------------------ SHA-3 and SHAKE (FIPS 202)

    private val RC = longArrayOf(
        0x0000000000000001L, 0x0000000000008082L, -0x7fffffffffff7f76L, -0x7fffffff7fff8000L,
        0x000000000000808BL, 0x0000000080000001L, -0x7fffffff7fff7f7fL, -0x7fffffffffff7ff7L,
        0x000000000000008AL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000AL,
        0x000000008000808BL, -0x7fffffffffffff75L, -0x7fffffffffff7f77L, -0x7fffffffffff7ffdL,
        -0x7fffffffffff7ffeL, -0x7fffffffffffff80L, 0x000000000000800AL, -0x7fffffff7ffffff6L,
        -0x7fffffff7fff7f7fL, -0x7fffffffffff7f80L, 0x0000000080000001L, -0x7fffffff7fff7ff8L,
    )
    private val ROTC = intArrayOf(1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 2, 14, 27, 41, 56, 8, 25, 43, 62, 18, 39, 61, 20, 44)
    private val PILN = intArrayOf(10, 7, 11, 17, 18, 3, 5, 16, 8, 21, 24, 4, 15, 23, 19, 13, 12, 2, 20, 14, 22, 9, 6, 1)

    private fun keccakF(st: LongArray) {
        val bc = LongArray(5)
        for (round in 0 until 24) {
            for (i in 0 until 5) bc[i] = st[i] xor st[i + 5] xor st[i + 10] xor st[i + 15] xor st[i + 20]
            for (i in 0 until 5) {
                val t = bc[(i + 4) % 5] xor java.lang.Long.rotateLeft(bc[(i + 1) % 5], 1)
                var j = 0
                while (j < 25) {
                    st[j + i] = st[j + i] xor t
                    j += 5
                }
            }
            var t = st[1]
            for (i in 0 until 24) {
                val j = PILN[i]
                val x = st[j]
                st[j] = java.lang.Long.rotateLeft(t, ROTC[i])
                t = x
            }
            var j = 0
            while (j < 25) {
                for (i in 0 until 5) bc[i] = st[j + i]
                for (i in 0 until 5) st[j + i] = st[j + i] xor (bc[(i + 1) % 5].inv() and bc[(i + 2) % 5])
                j += 5
            }
            st[0] = st[0] xor RC[round]
        }
    }

    /** Keccak[c] with [rate] bytes a block: SHA3 (its [digestSize], suffix 0x06) or SHAKE (digest size 0, suffix 0x1F). */
    private class Keccak(val rate: Int, override val digestSize: Int, val suffix: Int) : State() {
        override val blockSize get() = rate
        val st = LongArray(25)
        val buf = ByteArray(rate)
        var bufLen = 0

        override fun update(b: ByteArray, off: Int, len: Int) {
            var o = off
            var n = len
            while (n > 0) {
                val take = minOf(rate - bufLen, n)
                System.arraycopy(b, o, buf, bufLen, take)
                bufLen += take
                o += take
                n -= take
                if (bufLen == rate) {
                    absorb(buf)
                    bufLen = 0
                }
            }
        }

        private fun absorb(block: ByteArray) {
            for (i in 0 until rate / 8) st[i] = st[i] xor le64(block, 8 * i)
            keccakF(st)
        }

        override fun squeeze(n: Int): ByteArray {
            val c = copy() as Keccak
            java.util.Arrays.fill(c.buf, c.bufLen, rate, 0)
            c.buf[c.bufLen] = (c.buf[c.bufLen].toInt() xor suffix).toByte()
            c.buf[rate - 1] = (c.buf[rate - 1].toInt() xor 0x80).toByte()
            c.absorb(c.buf)
            val out = ByteArray(n)
            var done = 0
            while (done < n) {
                val take = minOf(rate, n - done)
                for (i in 0 until take) out[done + i] = (c.st[i / 8] ushr (8 * (i % 8))).toByte()
                done += take
                if (done < n) keccakF(c.st)
            }
            return out
        }

        override fun digest(): ByteArray = squeeze(digestSize)

        override fun copy(): State {
            val c = Keccak(rate, digestSize, suffix)
            st.copyInto(c.st)
            buf.copyInto(c.buf)
            c.bufLen = bufLen
            return c
        }
    }

    // ------------------------------------------------------------------ the types

    private fun hex(b: ByteArray): String {
        val sb = StringBuilder(b.size * 2)
        for (x in b) {
            val v = x.toInt() and 0xFF
            sb.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 15])
        }
        return sb.toString()
    }

    /** GET_BUFFER_VIEW_OR_ERROUT: the bytes [v] gives a hash (CPython's messages). */
    private fun data(v: Any?): ByteArray {
        if (v is PyStr) throw PyRt.typeError("Strings must be encoded before hashing")
        if (v is PyMemoryView && !v.contiguous()) throw PyException("BufferError", "memoryview: underlying buffer is not C-contiguous")
        return PyByteSeqs.content(v) ?: throw PyRt.typeError("object supporting the buffer API required")
    }

    /** A Py_buffer argument (blake2's key, salt and person). */
    private fun buffer(v: Any?): ByteArray {
        if (v is PyMemoryView && !v.contiguous()) throw PyException("BufferError", "memoryview: underlying buffer is not C-contiguous")
        return PyByteSeqs.content(v) ?: throw PyRt.typeError("a bytes-like object is required, not '${PyTypes.name(v)}'")
    }

    /** _Py_hashlib_data_argument: data or string (not both), or null for neither. */
    private fun dataArgument(data: Any?, string: Any?): Any? {
        if (data != null && string != null) {
            throw PyRt.typeError("'data' and 'string' are mutually exclusive and support for 'string' keyword parameter is slated for removal in a future version.")
        }
        return data ?: string
    }

    /** PyLong_AsInt. */
    private fun cInt(v: Any?): Int = when (val i = PyRt.indexValue(v) ?: throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer")) {
        is Long -> if (i < Int.MIN_VALUE || i > Int.MAX_VALUE) throw PyException("OverflowError", "Python int too large to convert to C int") else i.toInt()
        else -> throw PyException("OverflowError", "Python int too large to convert to C int")
    }

    /** _PyLong_UnsignedLong_Converter (and its long long's: [what]): 64 bits, as an unsigned Long. */
    private fun cUnsigned(v: Any?, what: String): Long {
        val i = PyRt.indexValue(v) ?: throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer")
        return when (i) {
            is Long -> if (i < 0) throw PyException("ValueError", "Cannot convert negative int") else i
            is BigInteger -> {
                if (i.signum() < 0) throw PyException("ValueError", "Cannot convert negative int")
                if (i.bitLength() > 64) throw PyException("OverflowError", "Python int too large for C $what")
                i.toLong()
            }
            else -> throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer")
        }
    }

    private fun self(a: Array<Any?>, t: PyType, n: String): PyHashObject {
        val h = a.getOrNull(0)
        if (h !is PyHashObject || !h.type.isSubtype(t)) {
            throw PyRt.typeError("descriptor '$n' for '${t.tpName}' objects doesn't apply to a '${PyTypes.name(h)}' object")
        }
        return h
    }

    private val REDUCE_EX = PyBuiltin("__reduce_ex__") { _, a -> throw PyRt.typeError("cannot pickle '${PyTypes.name(a[0])}' object") }

    /**
     * A hash type of [module] named [typeName]: its methods (update, digest, hexdigest, copy; SHAKE's digest and
     * hexdigest take a length), name, digest_size and block_size (SHA-3's _capacity_bits, _rate_bits, _suffix).
     * [clinicCopy]: copy's messages are Argument Clinic's (md5's, sha1's, sha2's), else METH_NOARGS'.
     */
    private fun hashType(module: String, typeName: String, doc: Any?, clinicCopy: Boolean, shake: Boolean, sha3: Boolean): PyType {
        val t = PyTypes.newBuiltin(typeName, subclassable = false)
        t.module = module
        t.tpName = "$module.$typeName"
        t.dispatchBuiltins = true
        t.hasDict = false
        fun noArgs(n: String, body: (PyHashObject) -> Any?) {
            t.def(n, PyBuiltin(n, { _, a ->
                val h = self(a, t, n)
                if (a.size != 1) throw PyRt.typeError("$typeName.$n() takes no arguments (${a.size - 1} given)")
                body(h)
            }, { _, a, kw ->
                self(a, t, n)
                if (kw.map.isNotEmpty()) throw PyRt.typeError("$typeName.$n() takes no keyword arguments")
                if (a.size != 1) throw PyRt.typeError("$typeName.$n() takes no arguments (${a.size - 1} given)")
                body(a[0] as PyHashObject)
            }))
        }
        fun update(a: Array<Any?>): Any? {
            val h = self(a, t, "update")
            if (a.size != 2) throw PyRt.typeError("$typeName.update() takes exactly one argument (${a.size - 1} given)")
            val b = data(a[1])
            h.state.update(b, 0, b.size)
            return PyNone
        }
        t.def("update", PyBuiltin("update", { _, a -> update(a) }, { _, a, kw ->
            self(a, t, "update")
            if (kw.map.isNotEmpty()) throw PyRt.typeError("$typeName.update() takes no keyword arguments")
            update(a)
        }))
        if (shake) {
            fun squeeze(n: String, a: Array<Any?>, kw: PyDict?, hex: Boolean): Any? {
                val h = self(a, t, n)
                val p = PyMath.clinic(n, a.copyOfRange(1, a.size), kw, listOf("length"), 0, 1, 1)
                val len = cUnsigned(p[0], "unsigned long")
                if (len >= (1L shl 29)) throw PyException("ValueError", "length is too large")
                val out = if (len == 0L) ByteArray(0) else h.state.squeeze(len.toInt())
                return if (hex) PyStr(hex(out)) else PyBytes(out)
            }
            t.def("digest", PyBuiltin("digest", { _, a -> squeeze("digest", a, null, false) }, { _, a, kw -> squeeze("digest", a, kw, false) }))
            t.def("hexdigest", PyBuiltin("hexdigest", { _, a -> squeeze("hexdigest", a, null, true) }, { _, a, kw -> squeeze("hexdigest", a, kw, true) }))
        } else {
            noArgs("digest") { PyBytes(it.state.digest()) }
            noArgs("hexdigest") { PyStr(hex(it.state.digest())) }
        }
        if (clinicCopy) {
            fun copy(a: Array<Any?>, kw: PyDict?): Any? {
                val h = self(a, t, "copy")
                if (a.size != 1 || (kw != null && kw.map.isNotEmpty())) throw PyRt.typeError("copy() takes no arguments")
                return PyHashObject(h.type, h.state.copy())
            }
            t.def("copy", PyBuiltin("copy", { _, a -> copy(a, null) }, { _, a, kw -> copy(a, kw) }))
        } else {
            noArgs("copy") { PyHashObject(it.type, it.state.copy()) }
        }
        fun getter(n: String, get: (PyHashObject) -> Any?) {
            val notWritable = PyBuiltin(n) { _, _ -> throw PyException("AttributeError", "attribute '$n' of '${t.tpName}' objects is not writable") }
            t.def(n, PyProperty(PyBuiltin(n) { _, a -> get(self(a, t, n)) }, notWritable, notWritable, null).also { it.name = n })
        }
        getter("name") { PyStr(nameOf(it)) }
        getter("digest_size") { it.state.digestSize.toLong() }
        getter("block_size") { it.state.blockSize.toLong() }
        if (sha3) {
            getter("_capacity_bits") { 1600L - it.state.blockSize * 8 }
            getter("_rate_bits") { it.state.blockSize * 8L }
            getter("_suffix") { PyBytes(byteArrayOf(if (shake) 0x1f else 0x06)) }
        }
        t.def("__reduce_ex__", REDUCE_EX)
        t.def("__doc__", doc)
        t.def("__module__", PyStr(module))
        t.newImpl = { _, _, _, _ -> throw PyRt.typeError("cannot create '${t.tpName}' instances") }
        return t
    }

    /** The algorithm's name a hash object gives (its type's, sha2's by its digest size). */
    private fun nameOf(h: PyHashObject): String = when (val n = h.type.name) {
        "SHA224Type", "SHA256Type", "SHA384Type", "SHA512Type" -> "sha" + n.substring(3, 6)
        else -> n
    }

    // ------------------------------------------------------------------ the modules

    /** name(data=b'', *, usedforsecurity=True, string=None): md5's, sha1's, sha2's and (by [fn]) sha3's arguments. */
    private fun simpleArgs(fn: String, a: Array<Any?>, kw: PyDict?): ByteArray? {
        val p = PyMath.clinic(fn, a, kw, listOf("data", "usedforsecurity", "string"), 0, 0, 1)
        if (p[1] != null) PyRt.truthy(p[1])
        return dataArgument(p[0], p[2])?.let { data(it) }
    }

    private fun mdModule(module: String, d: PyDict, algos: List<Triple<String, String, String>>, blockSizes: List<Int>) {
        for ((i, x) in algos.withIndex()) {
            val (name, java, typeName) = x
            val attr = if (typeName == name) typeName.uppercase() + "Type" else typeName
            val t = hashType(module, typeName, PyNone, clinicCopy = true, shake = false, sha3 = false)
            val bs = blockSizes[i]
            fun make(a: Array<Any?>, kw: PyDict?): Any? {
                val src = simpleArgs(name, a, kw)
                val h = PyHashObject(t, Md(MessageDigest.getInstance(java), bs))
                if (src != null) h.state.update(src, 0, src.size)
                return h
            }
            d[PyStr(name)] = PyBuiltin(name, { _, a -> make(a, null) }, { _, a, kw -> make(a, kw) })
            d[PyStr(attr)] = t
        }
        d[PyStr("_GIL_MINSIZE")] = GIL_MINSIZE
    }

    private val BLAKE2_NAMES = listOf("data", "digest_size", "key", "salt", "person", "fanout", "depth", "leaf_size", "node_offset",
        "node_depth", "inner_size", "last_node", "usedforsecurity", "string")

    /** blake2b(data=b'', *, digest_size=..., key=b'', salt=b'', person=b'', ...): py_blake2b_or_s_new's checks and messages. */
    private fun blake2(t: PyType, b: Boolean, a: Array<Any?>, kw: PyDict?): PyHashObject {
        val fn = if (b) "blake2b" else "blake2s"
        val outBytes = if (b) 64 else 32
        val saltBytes = if (b) 16 else 8
        val p = PyMath.clinic(fn, a, kw, BLAKE2_NAMES, 0, 0, 1)
        val digestSize = p[1]?.let { cInt(it) } ?: outBytes
        val key = p[2]?.let { buffer(it) } ?: ByteArray(0)
        val salt = p[3]?.let { buffer(it) } ?: ByteArray(0)
        val person = p[4]?.let { buffer(it) } ?: ByteArray(0)
        val fanout = p[5]?.let { cInt(it) } ?: 1
        val depth = p[6]?.let { cInt(it) } ?: 1
        val leafSize = p[7]?.let { cUnsigned(it, "unsigned long") } ?: 0L
        val nodeOffset = p[8]?.let { cUnsigned(it, "unsigned long long") } ?: 0L
        val nodeDepth = p[9]?.let { cInt(it) } ?: 0
        val innerSize = p[10]?.let { cInt(it) } ?: 0
        val lastNode = p[11]?.let { PyRt.truthy(it) } ?: false
        if (p[12] != null) PyRt.truthy(p[12])
        val src = dataArgument(p[0], p[13])
        if (digestSize <= 0 || digestSize > outBytes) {
            throw PyException("ValueError", "digest_size for ${if (b) "Blake2b" else "Blake2s"} must be between 1 and $outBytes bytes, here it is $digestSize")
        }
        if (salt.size > saltBytes) throw PyException("ValueError", "maximum salt length is $saltBytes bytes")
        if (person.size > saltBytes) throw PyException("ValueError", "maximum person length is $saltBytes bytes")
        if (fanout < 0 || fanout > 255) throw PyException("ValueError", "fanout must be between 0 and 255")
        if (depth <= 0 || depth > 255) throw PyException("ValueError", "depth must be between 1 and 255")
        if (java.lang.Long.compareUnsigned(leafSize, 0xFFFFFFFFL) > 0) throw PyException("OverflowError", "leaf_size is too large")
        if (!b && java.lang.Long.compareUnsigned(nodeOffset, 0xFFFFFFFFFFFFL) > 0) throw PyException("OverflowError", "node_offset is too large")
        if (nodeDepth < 0 || nodeDepth > 255) throw PyException("ValueError", "node_depth must be between 0 and 255")
        if (innerSize < 0 || innerSize > outBytes) throw PyException("ValueError", "inner_size must be between 0 and is $outBytes")
        if (key.size > outBytes) throw PyException("ValueError", "maximum key length is $outBytes bytes")
        val params = Params(digestSize, key, salt, person, fanout, depth, leafSize, nodeOffset, nodeDepth, innerSize, lastNode)
        val h = PyHashObject(t, if (b) Blake2b(params) else Blake2s(params))
        if (src != null) {
            val bytes = data(src)
            h.state.update(bytes, 0, bytes.size)
        }
        return h
    }

    /** _md5, _sha1, _sha2, _blake2 or _sha3 ([module]): its constructors, types and constants. */
    @JvmStatic
    fun fill(module: String, d: PyDict) {
        when (module) {
            "_md5" -> mdModule(module, d, listOf(Triple("md5", "MD5", "md5")), listOf(64))
            "_sha1" -> mdModule(module, d, listOf(Triple("sha1", "SHA-1", "sha1")), listOf(64))
            "_sha2" -> mdModule(module, d, listOf(Triple("sha224", "SHA-224", "SHA224Type"), Triple("sha256", "SHA-256", "SHA256Type"),
                Triple("sha384", "SHA-384", "SHA384Type"), Triple("sha512", "SHA-512", "SHA512Type")), listOf(64, 64, 128, 128))
            "_blake2" -> {
                d[PyStr("_GIL_MINSIZE")] = GIL_MINSIZE
                for (b in listOf(true, false)) {
                    val n = if (b) "blake2b" else "blake2s"
                    val t = hashType(module, n, PyStr("Return a new BLAKE2${if (b) "b" else "s"} hash object."), clinicCopy = false, shake = false, sha3 = false)
                    t.newImpl = { _, cls, a, kw -> blake2(cls, b, a, kw) }
                    val (salt, digest) = if (b) 16L to 64L else 8L to 32L
                    t.def("SALT_SIZE", salt)
                    t.def("PERSON_SIZE", salt)
                    t.def("MAX_KEY_SIZE", digest)
                    t.def("MAX_DIGEST_SIZE", digest)
                    d[PyStr(n)] = t
                    val u = n.uppercase()
                    d[PyStr("${u}_SALT_SIZE")] = salt
                    d[PyStr("${u}_PERSON_SIZE")] = salt
                    d[PyStr("${u}_MAX_KEY_SIZE")] = digest
                    d[PyStr("${u}_MAX_DIGEST_SIZE")] = digest
                }
            }
            "_sha3" -> {
                for ((n, rate, size) in listOf(Triple("sha3_224", 144, 28), Triple("sha3_256", 136, 32), Triple("sha3_384", 104, 48),
                    Triple("sha3_512", 72, 64), Triple("shake_128", 168, 0), Triple("shake_256", 136, 0))) {
                    val shake = size == 0
                    val doc = if (shake) "$n([data], *, usedforsecurity=True) -> SHAKE object\n\nReturn a new SHAKE hash object."
                        else "$n([data], *, usedforsecurity=True) -> SHA3 object\n\nReturn a new SHA3 hash object with a hashbit length of $size bytes."
                    val t = hashType(module, n, PyStr(doc), clinicCopy = false, shake = shake, sha3 = true)
                    t.newImpl = { _, cls, a, kw ->
                        // (one parser for them all: its messages say sha3_224)
                        val src = simpleArgs("sha3_224", a, kw)
                        val h = PyHashObject(cls, Keccak(rate, size, if (shake) 0x1f else 0x06))
                        if (src != null) h.state.update(src, 0, src.size)
                        h
                    }
                    d[PyStr(n)] = t
                }
                d[PyStr("implementation")] = PyStr("HACL")
                d[PyStr("_GIL_MINSIZE")] = GIL_MINSIZE
            }
        }
    }
}
