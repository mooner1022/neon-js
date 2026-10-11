package dev.mooner.neonjs.node

import dev.mooner.neonjs.builtins.JSDataView
import dev.mooner.neonjs.builtins.JSTypedArray
import dev.mooner.neonjs.runtime.*
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * The native side of node:zlib's one-shot functions (`binding.zlib`): deflate, inflate, gzip (written as zlib writes
 * it), gunzip (every member) and unzip over java.util.zip. Decompression is a bomb's way in, so what it makes counts
 * against the agent's allocation limit, stops at `maxOutputLength` (ERR_BUFFER_TOO_LARGE, as in Node) and checks the
 * agent's time limit as it goes. Errors are zlib's, with Node's codes (Z_DATA_ERROR...).
 */
internal object ZlibBinding {
    private const val DEFLATE = 1
    private const val INFLATE = 2
    private const val GZIP = 3
    private const val GUNZIP = 4
    private const val DEFLATERAW = 5
    private const val INFLATERAW = 6
    private const val UNZIP = 7

    private const val CHUNK = 64 * 1024

    /** What a one-shot call made: its bytes, until node:zlib takes them into a Buffer. */
    class Output(@JvmField val bytes: ByteArray, @JvmField val length: Int) : JSObject(null)

    /** zlib's error, with Node's code and errno. */
    private class ZError(message: String, val code: String, val errno: Int) : RuntimeException(message)

    /** Bytes as they come, within [max] and the agent's limits. */
    private class Sink(private val agent: Agent, private val max: Long, private val rt: NodeRuntime) {
        var bytes = ByteArray(256)
        var size = 0

        fun ensure(extra: Int) {
            val need = size.toLong() + extra
            if (need > max) {
                throw rt.nodeError(ErrorKind.RANGE, "Cannot create a Buffer larger than $max bytes", "ERR_BUFFER_TOO_LARGE")
            }
            if (need > bytes.size) {
                val cap = minOf(maxOf(need, bytes.size * 2L), max).toInt()
                agent.reserveAllocation(cap.toLong())
                bytes = try {
                    bytes.copyOf(cap)
                } catch (_: OutOfMemoryError) {
                    throw JSException.rangeError("Array buffer allocation failed")
                }
            }
        }

        fun write(b: Int) {
            ensure(1)
            bytes[size++] = b.toByte()
        }

        fun writeIntLE(v: Int) {
            for (i in 0 until 4) write((v ushr (8 * i)) and 0xFF)
        }

        /** Room for up to [n] more bytes at [size] (fewer near the cap). */
        fun room(n: Int): Int {
            val left = (max - size).coerceAtMost(n.toLong()).toInt()
            if (left <= 0) ensure(1)
            ensure(left)
            return left
        }
    }

    private fun bytesOf(v: Any?): Triple<ByteArray, Int, Int> = when (v) {
        is JSTypedArray -> Triple(v.buffer.data, v.byteOffset, v.byteLengthOrZero())
        is JSDataView -> Triple(v.buffer.data, v.byteOffset, maxOf(v.byteLengthOrOOB(), 0))
        Null, Undefined, null -> Triple(ByteArray(0), 0, 0)
        else -> throw JSException.typeError("an ArrayBufferView was expected")
    }

    fun create(rt: NodeRuntime): JSObject {
        val realm = rt.realm
        val b = JSObject(null)
        fun fn(name: String, length: Int, impl: NativeImpl) = b.defineOwn(name, NativeFunction(realm, name, length, impl), Attr.NONE)

        // (mode, input, level, strategy, maxOutputLength, dictionary) -> Output
        fn("run", 6) { f, _, a, _ ->
            val mode = Ops.toInt32(a.arg(0))
            val (data, off, len) = bytesOf(a.arg(1))
            val level = Ops.toInt32(a.arg(2))
            val strategy = Ops.toInt32(a.arg(3))
            val max = Ops.toNumber(a.arg(4)).let { if (it.isNaN() || it <= 0) Long.MAX_VALUE else it.toLong() }
            val dict = a.arg(5).let { if (it is JSTypedArray || it is JSDataView) bytesOf(it).let { (d, o, n) -> d.copyOfRange(o, o + n) } else null }
            val sink = Sink(f.realm.agent, max, rt)
            try {
                when (mode) {
                    DEFLATE, DEFLATERAW -> deflate(sink, data, off, len, level, strategy, nowrap = mode == DEFLATERAW, dict)
                    GZIP -> gzip(sink, data, off, len, level, strategy)
                    INFLATE, INFLATERAW -> inflate(sink, data, off, len, nowrap = mode == INFLATERAW, dict, f.realm.agent)
                    GUNZIP -> gunzip(sink, data, off, len, f.realm.agent)
                    UNZIP -> if (len >= 2 && data[off] == 0x1f.toByte() && data[off + 1] == 0x8b.toByte()) {
                        gunzip(sink, data, off, len, f.realm.agent)
                    } else {
                        inflate(sink, data, off, len, nowrap = false, dict, f.realm.agent)
                    }
                    else -> throw JSException.typeError("unknown zlib mode $mode")
                }
            } catch (e: ZError) {
                val err = rt.nodeError(ErrorKind.ERROR, e.message ?: "zlib error", e.code)
                (err.value as JSObject).createDataProperty("errno", e.errno.toDouble())
                throw err
            }
            Output(sink.bytes, sink.size)
        }
        fn("length", 1) { _, _, a, _ -> (a.arg(0) as Output).length.toDouble() }
        fn("take", 2) { _, _, a, _ ->
            val o = a.arg(0) as Output
            val t = a.arg(1) as JSTypedArray
            System.arraycopy(o.bytes, 0, t.buffer.data, t.byteOffset, minOf(o.length, t.byteLengthOrZero()))
            Undefined
        }
        // (view, value) -> the CRC-32 of the bytes, continuing from value
        fn("crc32", 2) { _, _, a, _ ->
            val (data, off, len) = bytesOf(a.arg(0))
            (crc32(Ops.toUint32(a.arg(1)).toInt(), data, off, len).toLong() and 0xFFFFFFFFL).toDouble()
        }
        return b
    }

    private fun deflate(sink: Sink, data: ByteArray, off: Int, len: Int, level: Int, strategy: Int, nowrap: Boolean, dict: ByteArray?) {
        val d = Deflater(level, nowrap)
        try {
            d.setStrategy(when (strategy) {
                1 -> Deflater.FILTERED
                2 -> Deflater.HUFFMAN_ONLY
                else -> Deflater.DEFAULT_STRATEGY
            })
            if (dict != null) d.setDictionary(dict)
            d.setInput(data, off, len)
            d.finish()
            while (!d.finished()) {
                val n = sink.room(CHUNK)
                sink.size += d.deflate(sink.bytes, sink.size, n)
            }
        } finally {
            d.end()
        }
    }

    /** gzip as zlib writes it: no name, no time, the extra flags for the level, OS 3 (Unix). */
    private fun gzip(sink: Sink, data: ByteArray, off: Int, len: Int, level: Int, strategy: Int) {
        val xfl = if (level == 9) 2 else if (strategy >= 2 || (level in 0..1)) 4 else 0
        for (byte in intArrayOf(0x1f, 0x8b, 8, 0, 0, 0, 0, 0, xfl, 3)) sink.write(byte)
        deflate(sink, data, off, len, level, strategy, nowrap = true, dict = null)
        sink.writeIntLE(crc32(0, data, off, len))
        sink.writeIntLE(len)
    }

    /** Inflates from [off]; returns where the compressed data ended. */
    private fun inflate(sink: Sink, data: ByteArray, off: Int, len: Int, nowrap: Boolean, dict: ByteArray?, agent: Agent): Int {
        val inf = Inflater(nowrap)
        var stalled = 0
        try {
            inf.setInput(data, off, len)
            while (!inf.finished()) {
                val n = sink.room(CHUNK)
                val made = try {
                    inf.inflate(sink.bytes, sink.size, n)
                } catch (e: DataFormatException) {
                    throw ZError(e.message ?: "invalid data", "Z_DATA_ERROR", -3)
                }
                sink.size += made
                if (made == 0) {
                    if (inf.needsDictionary()) {
                        if (dict == null) throw ZError("Missing dictionary", "Z_NEED_DICT", 2)
                        try {
                            inf.setDictionary(dict)
                        } catch (_: IllegalArgumentException) {
                            throw ZError("Bad dictionary", "Z_DATA_ERROR", -3)
                        }
                    } else if (inf.needsInput()) {
                        throw ZError("unexpected end of file", "Z_BUF_ERROR", -5)
                    } else if (++stalled > 2) {
                        throw ZError("invalid data", "Z_DATA_ERROR", -3)
                    }
                } else {
                    stalled = 0
                }
                agent.checkInterrupt()
            }
            return off + len - inf.remaining
        } finally {
            inf.end()
        }
    }

    /** Every gzip member, one after the other; bytes after the last that start no member are ignored, as in Node. */
    private fun gunzip(sink: Sink, data: ByteArray, off: Int, len: Int, agent: Agent) {
        val end = off + len
        var pos = off
        var first = true
        while (pos < end) {
            if (end - pos < 2 || data[pos] != 0x1f.toByte() || data[pos + 1] != 0x8b.toByte()) {
                if (first) throw ZError("incorrect header check", "Z_DATA_ERROR", -3)
                return
            }
            if (end - pos < 10) throw ZError("unexpected end of file", "Z_BUF_ERROR", -5)
            if (data[pos + 2] != 8.toByte()) throw ZError("unknown compression method", "Z_DATA_ERROR", -3)
            val flags = data[pos + 3].toInt() and 0xFF
            var p = pos + 10
            fun need(n: Int) {
                if (p + n > end) throw ZError("unexpected end of file", "Z_BUF_ERROR", -5)
            }
            if (flags and 4 != 0) {
                need(2)
                val xlen = (data[p].toInt() and 0xFF) or ((data[p + 1].toInt() and 0xFF) shl 8)
                p += 2
                need(xlen)
                p += xlen
            }
            for (bit in intArrayOf(8, 16)) {
                if (flags and bit != 0) {
                    while (true) {
                        need(1)
                        if (data[p++] == 0.toByte()) break
                    }
                }
            }
            if (flags and 2 != 0) {
                need(2)
                p += 2
            }
            val start = sink.size
            p = inflate(sink, data, p, end - p, nowrap = true, dict = null, agent = agent)
            if (end - p < 8) throw ZError("unexpected end of file", "Z_BUF_ERROR", -5)
            val crc = readIntLE(data, p)
            val size = readIntLE(data, p + 4)
            if (crc != crc32(0, sink.bytes, start, sink.size - start)) throw ZError("incorrect data check", "Z_DATA_ERROR", -3)
            if (size != (sink.size - start)) throw ZError("incorrect length check", "Z_DATA_ERROR", -3)
            pos = p + 8
            first = false
        }
    }

    private fun readIntLE(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)

    private val CRC_TABLE = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }

    /** CRC-32 (zlib's), continuing from [value]. */
    fun crc32(value: Int, b: ByteArray, off: Int, len: Int): Int {
        var c = value.inv()
        for (i in off until off + len) c = CRC_TABLE[(c xor b[i].toInt()) and 0xFF] xor (c ushr 8)
        return c.inv()
    }
}
