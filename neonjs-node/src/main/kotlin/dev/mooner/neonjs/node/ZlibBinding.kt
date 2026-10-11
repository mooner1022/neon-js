package dev.mooner.neonjs.node

import dev.mooner.neonjs.builtins.JSDataView
import dev.mooner.neonjs.builtins.JSTypedArray
import dev.mooner.neonjs.runtime.*
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * The native side of node:zlib (`binding.zlib`), as Node's zlib handle is: a stream (deflate, inflate, gzip written as
 * zlib writes it, gunzip of every member, raw, unzip) over java.util.zip, which `write` advances with a slice of input
 * into a slice of output that JS allocated, reporting what is left of both. Node's own lib/zlib.js drives it, so the
 * output limits (`maxOutputLength`, the allocation limit) are those of its loop; here each call makes at most the output
 * it is given room for and checks the agent's time limit as it goes. Errors are zlib's, with Node's codes.
 */
internal object ZlibBinding {
    private const val DEFLATE = 1
    private const val INFLATE = 2
    private const val GZIP = 3
    private const val GUNZIP = 4
    private const val DEFLATERAW = 5
    private const val INFLATERAW = 6
    private const val UNZIP = 7

    private const val Z_NO_FLUSH = 0
    private const val Z_PARTIAL_FLUSH = 1
    private const val Z_SYNC_FLUSH = 2
    private const val Z_FULL_FLUSH = 3
    private const val Z_FINISH = 4

    /** At most this much input goes to java.util.zip at once, so that the time limit is checked in between. */
    private const val SLICE = 1 shl 20

    /** zlib's error, with Node's code and errno. */
    private class ZError(message: String, val code: String, val errno: Int) : RuntimeException(message)

    private fun dataError(message: String) = ZError(message, "Z_DATA_ERROR", -3)

    /** Where a gzip member's reading is. */
    private enum class Gz { HEADER, BODY, TRAILER, END }

    /** A zlib stream: opaque to scripts (node:zlib's handle class keeps it in a private field). */
    private class Stream(val kind: Int) : JSObject(null) {
        /** The mode: [kind], or for unzip what its first byte made it (gunzip or inflate). */
        var mode = kind
        var level = -1
        var strategy = 0
        var dictionary: ByteArray? = null
        var deflater: Deflater? = null
        var inflater: Inflater? = null
        var closed = false
        var error: ZError? = null
        /** Gunzip: input after a member is not read as another one (node:zlib then says ERR_TRAILING_JUNK_AFTER_STREAM_END). */
        var rejectGarbage = false

        // compressing
        var finished = false
        /** Gzip: whether output of a member not yet complete went out (it cannot be reset then, as in Node 26). */
        var outputEmitted = false
        var streamComplete = true
        /** Bytes to write before (a gzip header) or after (a gzip trailer) the deflater's. */
        var pending = ByteArray(0)
        var pendingAt = 0
        var headerWritten = false

        // decompressing gzip
        var gz = Gz.HEADER
        var headerState = 0
        var headerFlags = 0
        var headerSkip = 0
        val trailer = ByteArray(8)
        var trailerLength = 0
        var crc = 0
        var size = 0L
        var ended = false
    }

    private fun bytesOf(v: Any?): Triple<ByteArray, Int, Int> = when (v) {
        is JSTypedArray -> Triple(v.buffer.data, v.byteOffset, v.byteLengthOrZero())
        is JSDataView -> Triple(v.buffer.data, v.byteOffset, maxOf(v.byteLengthOrOOB(), 0))
        else -> throw JSException.typeError("an ArrayBufferView was expected")
    }

    private fun stream(v: Any?): Stream = v as? Stream ?: throw JSException.typeError("not a zlib handle")

    fun create(rt: NodeRuntime): JSObject {
        val realm = rt.realm
        val b = JSObject(null)
        fun fn(name: String, length: Int, impl: NativeImpl) = b.defineOwn(name, NativeFunction(realm, name, length, impl), Attr.NONE)

        fn("create", 1) { _, _, a, _ ->
            val mode = Ops.toInt32(a.arg(0))
            if (mode !in DEFLATE..UNZIP) throw JSException.typeError("Bad argument")
            Stream(mode)
        }
        // (handle, level, strategy, dictionary, rejectGarbageAfterEnd): what zlib's deflateInit2 / inflateInit2 take
        // (windowBits and memLevel are checked by node:zlib; java.util.zip uses zlib's defaults)
        fn("init", 5) { _, _, a, _ ->
            val s = stream(a.arg(0))
            s.level = Ops.toInt32(a.arg(1))
            s.strategy = Ops.toInt32(a.arg(2))
            s.dictionary = a.arg(3).let { if (it is JSTypedArray || it is JSDataView) bytesOf(it).let { (d, o, n) -> d.copyOfRange(o, o + n) } else null }
            s.rejectGarbage = Ops.toBoolean(a.arg(4))
            start(s)
            Undefined
        }
        fn("params", 3) { _, _, a, _ ->
            val s = stream(a.arg(0))
            s.level = Ops.toInt32(a.arg(1))
            s.strategy = Ops.toInt32(a.arg(2))
            s.deflater?.let {
                it.setLevel(s.level)
                it.setStrategy(strategyOf(s.strategy))
            }
            Undefined
        }
        // undefined, or [message, errno, code] when the stream cannot be reset
        fn("reset", 1) { f, _, a, _ ->
            val s = stream(a.arg(0))
            if (s.kind == GZIP && !s.streamComplete && s.outputEmitted) {
                return@fn dev.mooner.neonjs.builtins.Builtins.arrayOf(f.realm, listOf(
                    "Cannot reset a gzip stream with an incomplete member; end the stream or start a new gzip compressor",
                    -2.0, "ERR_ZLIB_INCOMPLETE_FRAME",
                ))
            }
            if (!s.closed) {
                s.deflater?.end()
                s.inflater?.end()
                start(s)
            }
            Undefined
        }
        fn("close", 1) { _, _, a, _ ->
            val s = stream(a.arg(0))
            s.closed = true
            s.deflater?.end()
            s.inflater?.end()
            s.deflater = null
            s.inflater = null
            Undefined
        }
        // (handle, flush, in, inOff, inLen, out, outOff, outLen, writeState): writeState gets [availOut, availIn];
        // undefined, or [message, errno, code] for an error (the stream is then unusable)
        fn("write", 9) { f, _, a, _ ->
            val s = stream(a.arg(0))
            val flush = Ops.toInt32(a.arg(1))
            val (inData, inBase, inSize) = bytesOf(a.arg(2))
            val inOff = Ops.toInt32(a.arg(3))
            val inLen = Ops.toInt32(a.arg(4))
            val (outData, outBase, outSize) = bytesOf(a.arg(5))
            val outOff = Ops.toInt32(a.arg(6))
            val outLen = Ops.toInt32(a.arg(7))
            val state = a.arg(8) as? JSTypedArray ?: throw JSException.typeError("writeState must be a Uint32Array")
            if (inOff < 0 || inLen < 0 || inOff.toLong() + inLen > inSize) throw JSException.rangeError("Invalid input range")
            if (outOff < 0 || outLen < 0 || outOff.toLong() + outLen > outSize) throw JSException.rangeError("Invalid output range")
            if (state.lengthOrOOB() < 2) throw JSException.rangeError("writeState is too short")
            if (s.closed) throw JSException.typeError("zlib binding closed")
            val io = IO(inData, inBase + inOff, inBase + inOff + inLen, outData, outBase + outOff, outBase + outOff + outLen)
            try {
                s.error?.let { throw it }
                if (s.mode == DEFLATE || s.mode == DEFLATERAW || s.mode == GZIP) compress(s, flush, io, f.realm.agent)
                else decompress(s, flush, io, f.realm.agent)
            } catch (e: ZError) {
                s.error = e
                return@fn dev.mooner.neonjs.builtins.Builtins.arrayOf(f.realm, listOf(e.message, e.errno.toDouble(), e.code))
            }
            state.setIndex(0, (io.outEnd - io.o).toDouble())
            state.setIndex(1, (io.inEnd - io.i).toDouble())
            Undefined
        }
        // (view, value) -> the CRC-32 of the bytes, continuing from value
        fn("crc32", 2) { _, _, a, _ ->
            val (data, off, len) = bytesOf(a.arg(0))
            (crc32(Ops.toUint32(a.arg(1)).toInt(), data, off, len).toLong() and 0xFFFFFFFFL).toDouble()
        }
        return b
    }

    /** The input and output of one write, and how far it got in each. */
    private class IO(val inData: ByteArray, var i: Int, val inEnd: Int, val out: ByteArray, var o: Int, val outEnd: Int)

    private fun strategyOf(s: Int) = when (s) {
        1 -> Deflater.FILTERED
        2 -> Deflater.HUFFMAN_ONLY
        // RLE and FIXED: java.util.zip has neither; they compress as the default does
        else -> Deflater.DEFAULT_STRATEGY
    }

    /** A new stream state (init and reset): zlib's, the dictionary set where zlib sets it at once. */
    private fun start(s: Stream) {
        s.mode = s.kind
        s.finished = false
        s.outputEmitted = false
        s.streamComplete = true
        s.pending = ByteArray(0)
        s.pendingAt = 0
        s.headerWritten = false
        s.gz = Gz.HEADER
        s.headerState = 0
        s.trailerLength = 0
        s.crc = 0
        s.size = 0
        s.ended = false
        s.error = null
        s.deflater = null
        s.inflater = null
        when (s.mode) {
            DEFLATE, DEFLATERAW, GZIP -> {
                val d = Deflater(s.level, s.mode != DEFLATE)
                d.setStrategy(strategyOf(s.strategy))
                if (s.mode != GZIP) s.dictionary?.let { d.setDictionary(it) }
                s.deflater = d
            }
            INFLATE -> s.inflater = Inflater(false)
            INFLATERAW -> s.inflater = Inflater(true).also { i -> s.dictionary?.let { i.setDictionary(it) } }
            GUNZIP -> s.inflater = Inflater(true)
            // the first byte decides (decompress)
            UNZIP -> {}
        }
    }

    private fun emitPending(s: Stream, io: IO) {
        while (s.pendingAt < s.pending.size && io.o < io.outEnd) io.out[io.o++] = s.pending[s.pendingAt++]
    }

    private fun compress(s: Stream, flush: Int, io: IO, agent: Agent) {
        val outBefore = io.o
        doCompress(s, flush, io, agent)
        if (io.o > outBefore) s.outputEmitted = true
        if (s.finished) {
            s.streamComplete = true
            s.outputEmitted = false
        } else {
            s.streamComplete = false
        }
    }

    private fun doCompress(s: Stream, flush: Int, io: IO, agent: Agent) {
        val d = s.deflater!!
        if (s.mode == GZIP && !s.headerWritten) {
            // as zlib writes it: no name, no time, the extra flags for the level, OS 3 (Unix)
            val xfl = if (s.level == 9) 2 else if (s.strategy >= 2 || s.level in 0..1) 4 else 0
            s.pending = byteArrayOf(0x1f, 0x8b.toByte(), 8, 0, 0, 0, 0, 0, xfl.toByte(), 3)
            s.pendingAt = 0
            s.headerWritten = true
        }
        emitPending(s, io)
        if (s.pendingAt < s.pending.size) return
        while (!s.finished && io.o < io.outEnd) {
            val n = minOf(io.inEnd - io.i, SLICE)
            val last = io.i + n == io.inEnd
            d.setInput(io.inData, io.i, n)
            val readBefore = d.bytesRead
            if (last && flush == Z_FINISH) d.finish()
            val mode = if (!last) Deflater.NO_FLUSH else when (flush) {
                Z_PARTIAL_FLUSH, Z_SYNC_FLUSH -> Deflater.SYNC_FLUSH
                Z_FULL_FLUSH -> Deflater.FULL_FLUSH
                else -> Deflater.NO_FLUSH
            }
            val made = d.deflate(io.out, io.o, io.outEnd - io.o, mode)
            val used = (d.bytesRead - readBefore).toInt()
            if (s.mode == GZIP) {
                s.crc = crc32(s.crc, io.inData, io.i, used)
                s.size += used
            }
            io.o += made
            io.i += used
            if (d.finished()) {
                s.finished = true
                if (s.mode == GZIP) {
                    s.pending = ByteArray(8).also { t ->
                        writeIntLE(t, 0, s.crc)
                        writeIntLE(t, 4, s.size.toInt())
                    }
                    s.pendingAt = 0
                }
                break
            }
            if (made == 0 && used == 0) break
            agent.checkInterrupt()
        }
        if (s.finished) emitPending(s, io)
    }

    private fun decompress(s: Stream, flush: Int, io: IO, agent: Agent) {
        if (s.mode == UNZIP) {
            // the first byte decides, as zlib's detection does: gzip's magic, else zlib
            if (io.i == io.inEnd) return
            if (io.inData[io.i] == 0x1f.toByte()) {
                s.mode = GUNZIP
                s.inflater = Inflater(true)
            } else {
                s.mode = INFLATE
                s.inflater = Inflater(false)
            }
        }
        if (s.mode == GUNZIP) gunzip(s, io, agent) else inflate(s, io, agent)
        if (!s.ended && flush == Z_FINISH && io.i == io.inEnd && io.o < io.outEnd) throw ZError("unexpected end of file", "Z_BUF_ERROR", -5)
    }

    /** Inflates [io]'s input while there is room for output; the end of the compressed data ends the stream. */
    private fun inflate(s: Stream, io: IO, agent: Agent) {
        val inf = s.inflater!!
        var stalled = 0
        while (!s.ended && io.o < io.outEnd) {
            val n = minOf(io.inEnd - io.i, SLICE)
            inf.setInput(io.inData, io.i, n)
            val made = try {
                inf.inflate(io.out, io.o, io.outEnd - io.o)
            } catch (e: DataFormatException) {
                throw dataError(e.message ?: "invalid data")
            }
            val used = n - inf.remaining
            if (s.mode == GUNZIP) {
                s.crc = crc32(s.crc, io.out, io.o, made)
                s.size += made
            }
            io.o += made
            io.i += used
            if (inf.finished()) {
                s.ended = true
                break
            }
            if (made == 0 && used == 0) {
                if (inf.needsDictionary()) {
                    val dict = s.dictionary ?: throw ZError("Missing dictionary", "Z_NEED_DICT", 2)
                    try {
                        inf.setDictionary(dict)
                    } catch (_: IllegalArgumentException) {
                        throw dataError("Bad dictionary")
                    }
                } else if (io.i == io.inEnd) {
                    // all of the input taken: more is needed
                    break
                } else if (++stalled > 2) {
                    throw dataError("invalid data")
                }
            } else {
                stalled = 0
            }
            agent.checkInterrupt()
        }
    }

    /** Gunzip: each member's header, body and trailer; after a member, a byte other than 0 starts the next one. */
    private fun gunzip(s: Stream, io: IO, agent: Agent) {
        while (true) {
            when (s.gz) {
                Gz.HEADER -> {
                    while (s.gz == Gz.HEADER && io.i < io.inEnd) header(s, io.inData[io.i++].toInt() and 0xFF)
                    if (s.gz == Gz.HEADER) return
                }
                Gz.BODY -> {
                    s.ended = false
                    inflate(s, io, agent)
                    if (!s.ended) return
                    s.ended = false
                    s.gz = Gz.TRAILER
                    s.trailerLength = 0
                }
                Gz.TRAILER -> {
                    while (s.trailerLength < 8 && io.i < io.inEnd) s.trailer[s.trailerLength++] = io.inData[io.i++]
                    if (s.trailerLength < 8) return
                    if (readIntLE(s.trailer, 0) != s.crc) throw dataError("incorrect data check")
                    if (readIntLE(s.trailer, 4) != s.size.toInt()) throw dataError("incorrect length check")
                    s.gz = Gz.END
                    s.ended = true
                }
                Gz.END -> {
                    // another member follows, unless the rest is padding (zeros, left unread), not there yet, or not
                    // to be read (rejectGarbageAfterEnd)
                    if (io.i >= io.inEnd || io.inData[io.i] == 0.toByte() || s.rejectGarbage) return
                    s.inflater!!.reset()
                    s.gz = Gz.HEADER
                    s.headerState = 0
                    s.crc = 0
                    s.size = 0
                    s.ended = false
                }
            }
        }
    }

    /** Reads one byte of a gzip header: zlib's checks, the optional fields skipped as they come (none is kept). */
    private fun header(s: Stream, byte: Int) {
        when (s.headerState) {
            0 -> if (byte != 0x1f) throw dataError("incorrect header check") else s.headerState = 1
            1 -> if (byte != 0x8b) throw dataError("incorrect header check") else s.headerState = 2
            2 -> if (byte != 8) throw dataError("unknown compression method") else s.headerState = 3
            3 -> {
                if (byte and 0xE0 != 0) throw dataError("unknown header flags set")
                s.headerFlags = byte
                // MTIME, XFL, OS
                s.headerSkip = 6
                s.headerState = 4
            }
            4 -> if (--s.headerSkip == 0) afterFixed(s)
            // FEXTRA: its length (two bytes), then that many bytes
            5 -> {
                s.headerSkip = byte
                s.headerState = 6
            }
            6 -> {
                s.headerSkip = s.headerSkip or (byte shl 8)
                if (s.headerSkip == 0) afterExtra(s) else s.headerState = 7
            }
            7 -> if (--s.headerSkip == 0) afterExtra(s)
            // FNAME, FCOMMENT: up to a 0
            8 -> if (byte == 0) afterName(s)
            9 -> if (byte == 0) afterComment(s)
            // FHCRC
            10 -> if (--s.headerSkip == 0) s.gz = Gz.BODY
        }
    }

    private fun afterFixed(s: Stream) = if (s.headerFlags and 4 != 0) s.headerState = 5 else afterExtra(s)

    private fun afterExtra(s: Stream) = if (s.headerFlags and 8 != 0) s.headerState = 8 else afterName(s)

    private fun afterName(s: Stream) = if (s.headerFlags and 16 != 0) s.headerState = 9 else afterComment(s)

    private fun afterComment(s: Stream) {
        if (s.headerFlags and 2 != 0) {
            s.headerSkip = 2
            s.headerState = 10
        } else {
            s.gz = Gz.BODY
        }
    }

    private fun readIntLE(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun writeIntLE(b: ByteArray, at: Int, v: Int) {
        for (k in 0 until 4) b[at + k] = (v ushr (8 * k)).toByte()
    }

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
