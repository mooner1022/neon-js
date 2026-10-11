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

    /** V8's longest string (`buffer.constants.MAX_STRING_LENGTH` on 64-bit Node), or the engine's own if shorter. */
    const val V8_STRING_MAX_LENGTH = 0x1fffffe8

    fun stringMaxLength(realm: Realm): Int = minOf(realm.agent.config.maxStringLength, V8_STRING_MAX_LENGTH)

    /** The `binding.buffer` object. */
    fun create(realm: Realm): JSObject {
        val b = JSObject(null)
        fun fn(name: String, length: Int, impl: NativeImpl) = b.defineOwn(name, NativeFunction(realm, name, length, impl), Attr.NONE)

        b.defineOwn("kMaxLength", (Int.MAX_VALUE - 8).toDouble(), Attr.NONE)
        b.defineOwn("kStringMaxLength", stringMaxLength(realm).toDouble(), Attr.NONE)

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

        // (view, needle: number | string | Uint8Array, byteOffset, encoding, forward, end) -> index or -1; only the
        // bytes before end are searched (Node 26's IndexOfNumber, IndexOfString, IndexOfBuffer)
        fn("indexOf", 6) { f, _, a, _ ->
            val t = view(a.arg(0))
            val needleArg = a.arg(1)
            val e = enc(a.arg(3))
            val offset = Ops.toNumber(a.arg(2)).let { if (it.isNaN()) 0L else it.coerceIn(-9.007199254740991E15, 9.007199254740991E15).toLong() }
            val forward = Ops.toBoolean(a.arg(4))
            val end = Ops.toNumber(a.arg(5)).let { if (it.isNaN()) 0L else it.coerceIn(-9.007199254740991E15, 9.007199254740991E15).toLong() }
            val agent = f.realm.agent
            when (needleArg) {
                is Double -> indexOfByte(t, Ops.toUint32(needleArg).toByte(), offset, end, forward)
                // a view's bytes are searched where they are (nothing runs during the search to change them); in
                // ucs2, whole code units of both, as Node does
                is JSTypedArray -> indexOf(agent, t, needleArg.buffer.data, needleArg.byteOffset, length(needleArg), offset, end, forward,
                    e == Codecs.Enc.UTF16LE, string = false)
                else -> {
                    val needle = bytesOf(f.realm, needleArg, e)
                    indexOf(agent, t, needle, 0, needle.size, offset, end, forward, e == Codecs.Enc.UTF16LE, string = true)
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

        // the length of the string a view's bytes decode to in UTF-8 (each invalid sequence one U+FFFD, as V8 decodes)
        fn("stringLengthUtf8", 1) { _, _, a, _ ->
            val t = view(a.arg(0))
            utf8Length(t.buffer.data, t.byteOffset, t.byteOffset + length(t)).toDouble()
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

    /** The UTF-16 length of [d]'s bytes from [from] to [to] decoded as UTF-8 (the WHATWG decoder's replacements). */
    private fun utf8Length(d: ByteArray, from: Int, to: Int): Long {
        var n = 0L
        var needed = 0
        var seen = 0
        var lower = 0x80
        var upper = 0xBF
        var four = false
        var i = from
        while (i < to) {
            val b = d[i].toInt() and 0xFF
            if (needed == 0) {
                when (b) {
                    in 0x00..0x7F -> n++
                    in 0xC2..0xDF -> needed = 1
                    in 0xE0..0xEF -> {
                        if (b == 0xE0) lower = 0xA0
                        if (b == 0xED) upper = 0x9F
                        needed = 2
                    }
                    in 0xF0..0xF4 -> {
                        if (b == 0xF0) lower = 0x90
                        if (b == 0xF4) upper = 0x8F
                        needed = 3
                        four = true
                    }
                    else -> n++
                }
                i++
                continue
            }
            if (b !in lower..upper) {
                // the bytes so far are one replacement; this byte starts over
                needed = 0; seen = 0; lower = 0x80; upper = 0xBF; four = false
                n++
                continue
            }
            lower = 0x80
            upper = 0xBF
            seen++
            i++
            if (seen == needed) {
                n += if (four) 2 else 1
                needed = 0; seen = 0; four = false
            }
        }
        if (needed != 0) n++
        return n
    }

    /** Node's IndexOfOffset: where a search of [needle] bytes starts in [len] bytes, or -1 for no match. */
    private fun startOf(len: Long, offset: Long, needle: Long, forward: Boolean): Long = when {
        offset < 0 -> if (offset + len >= 0) len + offset else if (forward || needle == 0L) 0 else -1
        offset + needle <= len -> offset
        needle == 0L -> len
        forward -> -1
        else -> len - 1
    }

    /** Node's IndexOfNumber: where the byte [b] is in [t] before [end], from [offset] forward or backward. */
    private fun indexOfByte(t: JSTypedArray, b: Byte, offset: Long, end: Long, forward: Boolean): Int {
        val len = length(t).toLong()
        val start = startOf(len, offset, 1, forward)
        if (start <= -1 || len == 0L) return -1
        val searchEnd = end.coerceIn(0, len).toInt()
        val data = t.buffer.data
        val base = t.byteOffset
        if (forward) {
            for (i in start.toInt() until searchEnd) if (data[base + i] == b) return i
        } else {
            for (i in minOf(start + 1, searchEnd.toLong()).toInt() - 1 downTo 0) if (data[base + i] == b) return i
        }
        return -1
    }

    /**
     * Node's IndexOfString / IndexOfBuffer: where [nLen] bytes of [needle] from [nOff] are in [t] before [end],
     * searching from [offsetArg] forward or backward; in ucs2 ([ucs2]), whole code units. Naive (and so as slow as
     * haystack times needle), it checks the agent's limits as it goes.
     */
    private fun indexOf(
        agent: Agent, t: JSTypedArray, needle: ByteArray, nOff: Int, nLen0: Int, offsetArg: Long, end: Long, forward: Boolean,
        ucs2: Boolean, string: Boolean,
    ): Int {
        val len = length(t).toLong()
        // a string's haystack is whole code units in ucs2
        val haystack = if (ucs2 && string) len and 1L.inv() else len
        var searchEnd = end.coerceIn(0, haystack)
        if (ucs2) searchEnd = searchEnd and 1L.inv()
        val nLen = nLen0.toLong()
        val opt = startOf(haystack, offsetArg, nLen, forward)
        if (nLen == 0L) return minOf(opt, searchEnd).toInt()
        if (haystack == 0L || opt <= -1) return -1
        var offset = opt
        if (!forward && offset >= searchEnd) {
            if (searchEnd == 0L) return -1
            offset = searchEnd - 1
        } else if (forward && offset >= searchEnd) {
            return -1
        }
        if (ucs2 && string && forward) {
            offset += offset % 2
            if (offset >= searchEnd) return -1
        }
        if ((forward && nLen + offset > searchEnd) || nLen > searchEnd) return -1
        // in ucs2, code units of both: the needle's whole ones, from the unit the offset falls in
        val step = if (ucs2) 2 else 1
        val n = if (ucs2) (nLen and 1L.inv()).toInt() else nLen.toInt()
        if (ucs2 && (searchEnd < 2 || n < 2)) return -1
        val units = if (ucs2) offset / 2 * 2 else offset
        val data = t.buffer.data
        val base = t.byteOffset
        val last = searchEnd - n
        var i = if (forward) units else minOf(units, last).let { if (ucs2 && it % 2 != 0L) it - 1 else it }
        var work = 0
        while (if (forward) i <= last else i >= 0) {
            var k = 0
            val at = base + i.toInt()
            while (k < n && data[at + k] == needle[nOff + k]) k++
            if (k == n) return i.toInt()
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
