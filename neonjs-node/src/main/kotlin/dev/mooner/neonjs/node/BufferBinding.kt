package dev.mooner.neonjs.node

import dev.mooner.neonjs.builtins.JSTypedArray
import dev.mooner.neonjs.runtime.*

/**
 * The byte work of node:buffer: encoding strings into a Buffer, decoding one, filling and searching. buffer.js
 * allocates every Buffer (so allocation stays the engine's own, within its limits); these functions only read and
 * write the bytes a view already has, from its offset up to its length (never the capacity behind it).
 */
internal object BufferBinding {
    /** The encodings by the index buffer.js passes (Codecs.Enc order). */
    private val ENCODINGS = Codecs.Enc.entries.toTypedArray()

    private fun enc(v: Any?): Codecs.Enc = ENCODINGS[Ops.toNumber(v).toInt().coerceIn(0, ENCODINGS.size - 1)]

    private fun view(v: Any?): JSTypedArray = v as? JSTypedArray ?: throw JSException.typeError("argument must be a Uint8Array")

    /** A view's byte length: 0 when its buffer is detached or it is out of bounds. */
    private fun length(t: JSTypedArray): Int = t.byteLengthOrZero()

    private fun writable(t: JSTypedArray): JSTypedArray {
        if (t.buffer.immutable) throw JSException.typeError("Cannot write to an immutable ArrayBuffer")
        return t
    }

    /** An index argument clamped to 0..[len]. */
    private fun index(v: Any?, len: Int): Int {
        val d = Ops.toNumber(v)
        return if (d.isNaN() || d <= 0) 0 else if (d >= len) len else d.toInt()
    }

    /** The `binding.buffer` object. */
    fun create(realm: Realm): JSObject {
        val b = JSObject(null)
        fun fn(name: String, length: Int, impl: NativeImpl) = b.defineOwn(name, NativeFunction(realm, name, length, impl), Attr.NONE)

        b.defineOwn("kMaxLength", (Int.MAX_VALUE - 8).toDouble(), Attr.NONE)
        b.defineOwn("kStringMaxLength", realm.agent.config.maxStringLength.toDouble(), Attr.NONE)

        fn("byteLength", 2) { _, _, a, _ -> Codecs.byteLength(Ops.toString(a.arg(0)), enc(a.arg(1))).toDouble() }

        // (view, string, encoding, offset, max) -> bytes written: no partial character, as in Node
        fn("write", 5) { _, _, a, _ ->
            val t = writable(view(a.arg(0)))
            val len = length(t)
            val offset = index(a.arg(3), len)
            val max = minOf(index(a.arg(4), len), len - offset)
            Codecs.write(Ops.toString(a.arg(1)), enc(a.arg(2)), t.buffer.data, t.byteOffset + offset, max).toDouble()
        }

        // (view, encoding, start, end) -> string
        fn("toString", 4) { f, _, a, _ ->
            val t = view(a.arg(0))
            val len = length(t)
            val start = index(a.arg(2), len)
            val end = maxOf(start, index(a.arg(3), len))
            val e = enc(a.arg(1))
            val chars = when (e) {
                Codecs.Enc.UTF16LE -> (end - start) / 2L
                Codecs.Enc.HEX -> (end - start) * 2L
                Codecs.Enc.BASE64, Codecs.Enc.BASE64URL -> (end - start + 2) / 3 * 4L
                else -> (end - start).toLong()
            }
            f.realm.agent.checkStringLength(chars)
            Codecs.decode(t.buffer.data, t.byteOffset + start, t.byteOffset + end, e)
        }

        // (view, value: string | Uint8Array, encoding, start, end) -> 0, or -1 when the value gives no bytes
        fn("fill", 5) { f, _, a, _ ->
            val t = writable(view(a.arg(0)))
            val len = length(t)
            val start = index(a.arg(3), len)
            val end = maxOf(start, index(a.arg(4), len))
            val pattern = bytesOf(f.realm, a.arg(1), enc(a.arg(2)))
            if (pattern.isEmpty()) return@fn -1.0
            val data = t.buffer.data
            var at = t.byteOffset + start
            val stop = t.byteOffset + end
            while (at < stop) {
                val n = minOf(pattern.size, stop - at)
                System.arraycopy(pattern, 0, data, at, n)
                at += n
            }
            0.0
        }

        // (view, needle: number | string | Uint8Array, byteOffset, encoding, forward) -> index or -1
        fn("indexOf", 5) { f, _, a, _ ->
            val t = view(a.arg(0))
            val needleArg = a.arg(1)
            val e = enc(a.arg(3))
            val offset = Ops.toNumber(a.arg(2))
            val forward = Ops.toBoolean(a.arg(4))
            val agent = f.realm.agent
            when (needleArg) {
                is Double -> indexOf(agent, t, byteArrayOf(Ops.toUint32(needleArg).toByte()), 0, 1, offset, forward, false)
                // a view's bytes are searched where they are (nothing runs during the search to change them)
                is JSTypedArray -> indexOf(agent, t, needleArg.buffer.data, needleArg.byteOffset, length(needleArg), offset, forward, false)
                else -> {
                    val needle = bytesOf(f.realm, needleArg, e)
                    indexOf(agent, t, needle, 0, needle.size, offset, forward, e == Codecs.Enc.UTF16LE)
                }
            }.toDouble()
        }

        // (a, aStart, aEnd, b, bStart, bEnd) -> -1, 0 or 1, comparing bytes as unsigned
        fn("compare", 6) { _, _, a, _ ->
            val x = view(a.arg(0))
            val y = view(a.arg(3))
            val xs = index(a.arg(1), length(x))
            val xe = maxOf(xs, index(a.arg(2), length(x)))
            val ys = index(a.arg(4), length(y))
            val ye = maxOf(ys, index(a.arg(5), length(y)))
            compare(x.buffer.data, x.byteOffset + xs, xe - xs, y.buffer.data, y.byteOffset + ys, ye - ys).toDouble()
        }

        fn("isUtf8", 1) { _, _, a, _ ->
            val t = view(a.arg(0))
            Codecs.isUtf8(t.buffer.data, t.byteOffset, t.byteOffset + length(t))
        }
        fn("isAscii", 1) { _, _, a, _ ->
            val t = view(a.arg(0))
            val d = t.buffer.data
            (t.byteOffset until t.byteOffset + length(t)).all { d[it] >= 0 }
        }
        return b
    }

    /** The bytes of a fill or search value: a string in [e], or a copy of a view's bytes. */
    private fun bytesOf(realm: Realm, v: Any?, e: Codecs.Enc): ByteArray {
        if (v is JSTypedArray) {
            val n = length(v)
            return allocate(realm, n.toLong()).also { System.arraycopy(v.buffer.data, v.byteOffset, it, 0, n) }
        }
        val s = Ops.toString(v)
        val out = allocate(realm, Codecs.byteLength(s, e))
        val n = Codecs.write(s, e, out, 0, out.size)
        return if (n == out.size) out else out.copyOf(n)
    }

    /** Bytes the agent accounts for: a RangeError, not the host's OutOfMemoryError, past what it can give. */
    private fun allocate(realm: Realm, n: Long): ByteArray {
        if (n > Int.MAX_VALUE - 8) throw JSException.rangeError("Array buffer allocation failed")
        realm.agent.reserveAllocation(n)
        try {
            return ByteArray(n.toInt())
        } catch (_: OutOfMemoryError) {
            throw JSException.rangeError("Array buffer allocation failed")
        }
    }

    /** Node's IndexOfOffset: where a search of [needle] bytes starts in [len] bytes, or -1 for no match. */
    private fun startOf(len: Int, offset: Long, needle: Int, forward: Boolean): Long = when {
        offset < 0 -> if (offset + len >= 0) len + offset else if (forward || needle == 0) 0 else -1
        offset + needle <= len -> offset
        needle == 0 -> len.toLong()
        forward -> -1
        else -> len - 1L
    }

    /**
     * Where [nLen] bytes of [needle] from [nOff] are in [t], searching from [offsetArg] forward or backward. Naive (and
     * so as slow as haystack times needle), it checks the agent's limits as it goes.
     */
    private fun indexOf(
        agent: Agent, t: JSTypedArray, needle: ByteArray, nOff: Int, nLen: Int, offsetArg: Double, forward: Boolean, aligned: Boolean,
    ): Int {
        val len = length(t)
        val offset = if (offsetArg.isNaN()) (if (forward) 0L else len.toLong()) else offsetArg.coerceIn(-2147483648.0, 2147483647.0).toLong()
        var start = startOf(len, offset, nLen, forward)
        if (nLen == 0) return start.toInt()
        if (len == 0 || start < 0 || nLen > len) return -1
        if (forward && start + nLen > len) return -1
        val data = t.buffer.data
        val base = t.byteOffset
        // utf16le strings are searched in whole code units from the unit the offset falls in, as Node does
        val step = if (aligned) 2 else 1
        if (aligned) start -= start % 2
        var i = if (forward) start.toInt() else minOf(start, (len - nLen).toLong()).toInt().let { if (aligned && it % 2 != 0) it - 1 else it }
        var work = 0
        while (if (forward) i + nLen <= len else i >= 0) {
            var k = 0
            while (k < nLen && data[base + i + k] == needle[nOff + k]) k++
            if (k == nLen) return i
            work += k + 1
            if (work >= 1 shl 16) {
                work = 0
                agent.checkInterrupt()
            }
            i += if (forward) step else -step
        }
        return -1
    }

    private fun compare(a: ByteArray, ao: Int, an: Int, b: ByteArray, bo: Int, bn: Int): Int {
        val n = minOf(an, bn)
        for (i in 0 until n) {
            val x = a[ao + i].toInt() and 0xFF
            val y = b[bo + i].toInt() and 0xFF
            if (x != y) return if (x < y) -1 else 1
        }
        return an.compareTo(bn).coerceIn(-1, 1)
    }
}
