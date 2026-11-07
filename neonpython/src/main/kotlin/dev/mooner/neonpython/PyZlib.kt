package dev.mooner.neonpython

import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/** A zlib.Compress, zlib.Decompress or zlib._ZlibDecompressor: its stream and what CPython's object keeps with it. */
class PyZlibObj(type: PyType) : PyObject(type) {
    @JvmField var comp: PyZlib.Deflate? = null
    @JvmField var decomp: PyZlib.Inflate? = null
    @JvmField var unusedData: PyBytes = PyZlib.EMPTY_BYTES
    @JvmField var unconsumedTail: PyBytes = PyZlib.EMPTY_BYTES
    @JvmField var eof = false
    @JvmField var zdict: Any? = null
    /** _ZlibDecompressor's: whether it needs input, and its input not consumed yet (none: null, its next_in NULL). */
    @JvmField var needsInput = true
    @JvmField var pending: ByteArray? = null
}

/**
 * `zlib` (docs/PYTHON_PLATFORM.md §12), as CPython 3.14 has it: Modules/zlibmodule.c in Kotlin over java.util.zip.
 * The deflate streams are java.util.zip's (Deflater and Inflater raw, `nowrap`: the platform's zlib); the rest of
 * what zlib's deflate() and inflate() do is written here as zlib does it: the zlib and gzip headers and trailers,
 * their checks and messages, wbits (and the header's window, 40..47's detection), dictionaries, the flush ranks,
 * the return codes, z_stream.msg. CPython's loops are ported over them as they are, its output buffers' block sizes
 * too (what decides how much input a call with max_length consumes). What java.util.zip has no way to do: the
 * strategies Z_RLE and Z_FIXED (Z_DEFAULT_STRATEGY's streams made), memLevel (8's), Z_PARTIAL_FLUSH and Z_BLOCK
 * (Z_SYNC_FLUSH's), a window below 15 bits when compressing (a Deflater's is 32K: the stream fully flushed each half
 * window, the last half its dictionary again, so that no match reaches further back than the header says; bytes
 * other than zlib's), and when decompressing (the Inflater's 32K window takes distances a smaller one would not);
 * copying a stream, which java.util.zip cannot, is done by making it again: a Compress and a Decompress keep what
 * their Deflater and Inflater were given until they are done with them, and copy() gives a new one the same calls.
 * ZLIB_VERSION and ZLIB_RUNTIME_VERSION are the Linux oracle's (NeonPy presents Linux x86_64); java.util.zip does
 * not say which zlib it has.
 */
object PyZlib {
    @JvmField val EMPTY = ByteArray(0)
    @JvmField val EMPTY_BYTES = PyBytes(EMPTY)

    const val DOC = "The functions in this module allow compression and decompression using the\n" +
        "zlib library, which is based on GNU zip.\n" +
        "\n" +
        "adler32(string[, start]) -- Compute an Adler-32 checksum.\n" +
        "compress(data[, level]) -- Compress data, with compression level 0-9 or -1.\n" +
        "compressobj([level[, ...]]) -- Return a compressor object.\n" +
        "crc32(string[, start]) -- Compute a CRC-32 checksum.\n" +
        "decompress(string,[wbits],[bufsize]) -- Decompresses a compressed string.\n" +
        "decompressobj([wbits[, zdict]]) -- Return a decompressor object.\n" +
        "\n" +
        "'wbits' is window buffer size and container format.\n" +
        "Compressor objects support compress() and flush() methods; decompressor\n" +
        "objects support decompress() and flush()."

    private const val DECOMPRESSOR_DOC = "Create a decompressor object for decompressing data incrementally.\n" +
        "\n" +
        "  wbits = 15\n" +
        "  zdict\n" +
        "     The predefined compression dictionary. This is a sequence of bytes\n" +
        "     (such as a bytes object) containing subsequences that are expected\n" +
        "     to occur frequently in the data that is to be compressed. Those\n" +
        "     subsequences that are expected to be most common should come at the\n" +
        "     end of the dictionary. This must be the same dictionary as used by the\n" +
        "     compressor that produced the input data.\n" +
        "\n"

    /** What zlib 1.3.2 (the Linux oracle's) says it is. */
    private const val ZLIB_VERSION = "1.3.2"

    const val Z_OK = 0
    const val Z_STREAM_END = 1
    const val Z_NEED_DICT = 2
    const val Z_STREAM_ERROR = -2
    const val Z_DATA_ERROR = -3
    const val Z_MEM_ERROR = -4
    const val Z_BUF_ERROR = -5
    const val Z_VERSION_ERROR = -6

    const val Z_NO_FLUSH = 0
    const val Z_PARTIAL_FLUSH = 1
    const val Z_SYNC_FLUSH = 2
    const val Z_FULL_FLUSH = 3
    const val Z_FINISH = 4
    const val Z_BLOCK = 5
    const val Z_TREES = 6

    private const val Z_FILTERED = 1
    private const val Z_HUFFMAN_ONLY = 2
    private const val Z_RLE = 3
    private const val Z_FIXED = 4
    private const val DEFLATED = 8
    private const val MAX_WBITS = 15
    private const val DEF_MEM_LEVEL = 8
    private const val DEF_BUF_SIZE = 16 * 1024
    private const val DEF_MAX_INITIAL_BUF_SIZE = 16 * 1024 * 1024
    /** OS_CODE: Unix's (NeonPy presents Linux). */
    private const val OS_CODE = 3

    // ------------------------------------------------------------------ the streams

    /** A z_stream's input, output and message. */
    open class ZStream {
        @JvmField var inBuf: ByteArray = EMPTY
        @JvmField var nextIn = 0
        @JvmField var availIn = 0
        @JvmField var outBuf: ByteArray = EMPTY
        @JvmField var nextOut = 0
        @JvmField var availOut = 0
        @JvmField var msg: String? = null

        fun input(b: ByteArray) {
            inBuf = b
            nextIn = 0
            availIn = 0
        }
    }

    /** A call made to a Deflater or an Inflater: the input it consumed, the output room it had, its flush. */
    private class Call(@JvmField val input: ByteArray, @JvmField val room: Int, @JvmField val flush: Int, @JvmField val finish: Boolean)

    /** A dictionary given to a Deflater or an Inflater. */
    private class Dict(@JvmField val dict: ByteArray)

    private const val INIT = 0
    private const val BUSY = 1
    private const val FINISH = 2

    private fun rank(f: Int) = f * 2 - if (f > 4) 9 else 0

    /**
     * zlib's deflate() of a stream deflateInit2() made: [level] 0..9, [wrap] 0 (raw), 1 (zlib) or 2 (gzip),
     * [wbits] 9..15, [strategy] as asked; the deflate stream a raw Deflater's.
     */
    class Deflate(@JvmField val level: Int, @JvmField val wrap: Int, @JvmField val wbits: Int, @JvmField val strategy: Int, logged: Boolean) : ZStream() {
        @JvmField var d: Deflater? = newDeflater()
        @JvmField var status = INIT
        @JvmField var lastFlush = -2
        @JvmField var check = if (wrap == 2) 0L else 1L
        @JvmField var totalIn = 0L
        @JvmField var dictLen = 0
        @JvmField var dictId = 1L
        @JvmField var trailer = false
        /** Bytes of the header or the trailer not written yet. */
        @JvmField var pending = EMPTY
        @JvmField var pendingAt = 0
        /** Whether the Deflater's last call filled its output (it may hold more). */
        @JvmField var full = false
        /**
         * A window below 15 bits ([limit] its size), which a Deflater's 32K one would reach past: the stream fully
         * flushed when what its matches may reach (the last [keep] bytes, a dictionary again after each flush, and
         * the input since) would fill the window.
         */
        @JvmField val limit = if (wbits < 15 && level != 0 && strategy != Z_HUFFMAN_ONLY) 1 shl wbits else Int.MAX_VALUE
        private val keep = if (limit == Int.MAX_VALUE) 0 else limit / 2
        @JvmField var sinceReset = 0
        @JvmField var resetDue = false
        @JvmField var recent = if (keep == 0) EMPTY else ByteArray(keep)
        @JvmField var recentLen = 0
        @JvmField var recentAt = 0
        @JvmField var log: ArrayList<Any>? = if (logged) ArrayList() else null

        private fun newDeflater(): Deflater {
            val d = Deflater(level, true)
            val s = when (strategy) {
                Z_FILTERED -> Deflater.FILTERED
                Z_HUFFMAN_ONLY -> Deflater.HUFFMAN_ONLY
                else -> Deflater.DEFAULT_STRATEGY
            }
            if (s != Deflater.DEFAULT_STRATEGY) {
                // (a Deflater's next call only sets its strategy: that call made now)
                d.setStrategy(s)
                d.deflate(EMPTY, 0, 0, Deflater.NO_FLUSH)
            }
            return d
        }

        /** deflateSetDictionary, before the first deflate(). */
        fun setDictionary(dict: ByteArray): Int {
            val d = d ?: return Z_STREAM_ERROR
            if (wrap == 2 || status != INIT) return Z_STREAM_ERROR
            if (wrap == 1) dictId = PyBinascii.adler32(1L, dict, 0, dict.size)
            dictLen = dict.size
            // (a small window: the dictionary's end, what a run may reach)
            val use = if (keep != 0 && dict.size > keep) dict.copyOfRange(dict.size - keep, dict.size) else dict
            if (keep != 0) {
                remember(use, 0, use.size)
                sinceReset = use.size
            }
            d.setDictionary(use)
            log?.add(Dict(use.copyOf()))
            return Z_OK
        }

        private fun pendingLen() = pending.size - pendingAt

        /** The last [keep] bytes of the stream kept. */
        private fun remember(b: ByteArray, off: Int, len: Int) {
            for (i in maxOf(off, off + len - keep) until off + len) {
                recent[recentAt] = b[i]
                recentAt = (recentAt + 1) % keep
                if (recentLen < keep) recentLen++
            }
        }

        private fun recentBytes(): ByteArray {
            val out = ByteArray(recentLen)
            val start = (recentAt - recentLen + keep) % keep
            for (i in 0 until recentLen) out[i] = recent[(start + i) % keep]
            return out
        }

        private fun flushPending() {
            val n = minOf(pendingLen(), availOut)
            if (n == 0) return
            pending.copyInto(outBuf, nextOut, pendingAt, pendingAt + n)
            pendingAt += n
            nextOut += n
            availOut -= n
        }

        private fun queue(b: ByteArray) {
            pending = b
            pendingAt = 0
        }

        private fun header() {
            if (wrap == 1) {
                var h = (DEFLATED + ((wbits - 8) shl 4)) shl 8
                val levelFlags = when {
                    strategy >= Z_HUFFMAN_ONLY || level < 2 -> 0
                    level < 6 -> 1
                    level == 6 -> 2
                    else -> 3
                }
                h = h or (levelFlags shl 6)
                if (dictLen != 0) h = h or 0x20
                h += 31 - (h % 31)
                queue(if (dictLen != 0) byteArrayOf((h shr 8).toByte(), h.toByte(), (dictId ushr 24).toByte(), (dictId ushr 16).toByte(), (dictId ushr 8).toByte(), dictId.toByte())
                    else byteArrayOf((h shr 8).toByte(), h.toByte()))
                check = 1L
            } else if (wrap == 2) {
                val xfl = if (level == 9) 2 else if (strategy >= Z_HUFFMAN_ONLY || level < 2) 4 else 0
                queue(byteArrayOf(31, 139.toByte(), 8, 0, 0, 0, 0, 0, xfl.toByte(), OS_CODE.toByte()))
                check = 0L
            }
        }

        /** The Deflater given [n] bytes of the input with [flush] (Java's): how many it consumed. */
        private fun run(n: Int, flush: Int, finish: Boolean): Int {
            val d = d!!
            if (finish) d.finish()
            d.setInput(inBuf, nextIn, n)
            val before = d.bytesRead
            val room = availOut
            val p = d.deflate(outBuf, nextOut, availOut, flush)
            val c = (d.bytesRead - before).toInt()
            log?.add(Call(inBuf.copyOfRange(nextIn, nextIn + c), room, flush, finish))
            if (keep != 0) remember(inBuf, nextIn, c)
            if (wrap == 1) check = PyBinascii.adler32(check, inBuf, nextIn, c)
            else if (wrap == 2) check = PyBinascii.crc32(check, inBuf, nextIn, c)
            totalIn += c
            nextIn += c
            availIn -= c
            nextOut += p
            availOut -= p
            full = availOut == 0
            return c
        }

        /** [flush] as a Deflater has it (Z_FINISH: its finish()). */
        private fun javaFlush(flush: Int) = when (flush) {
            Z_NO_FLUSH, Z_FINISH -> Deflater.NO_FLUSH
            Z_FULL_FLUSH -> Deflater.FULL_FLUSH
            // (Z_PARTIAL_FLUSH and Z_BLOCK, which a Deflater has not: Z_SYNC_FLUSH)
            else -> Deflater.SYNC_FLUSH
        }

        /** The block work: the input to the Deflater, a small window's runs each fully flushed. */
        private fun blocks(flush: Int) {
            val jflush = javaFlush(flush)
            while (true) {
                if (resetDue && availIn > 0) {
                    run(0, Deflater.FULL_FLUSH, false)
                    if (full) return
                    // (what was last, its dictionary: the matches after it reach no further than the window)
                    val dict = recentBytes()
                    d!!.setDictionary(dict)
                    log?.add(Dict(dict))
                    resetDue = false
                    sinceReset = dict.size
                }
                val n = minOf(availIn, limit - sinceReset)
                val last = n == availIn
                val c = run(n, if (last) jflush else Deflater.NO_FLUSH, last && flush == Z_FINISH)
                if (limit != Int.MAX_VALUE) {
                    sinceReset += c
                    if (sinceReset >= limit) resetDue = true
                }
                if (full || availIn == 0) return
            }
        }

        fun deflate(flush: Int): Int {
            val d = d ?: return Z_STREAM_ERROR
            if (flush > Z_BLOCK || flush < 0) return Z_STREAM_ERROR
            if (status == FINISH && flush != Z_FINISH) {
                msg = "stream error"
                return Z_STREAM_ERROR
            }
            if (availOut == 0) {
                msg = "buffer error"
                return Z_BUF_ERROR
            }
            val oldFlush = lastFlush
            lastFlush = flush
            if (pendingLen() > 0) {
                flushPending()
                if (availOut == 0) {
                    lastFlush = -1
                    return Z_OK
                }
            } else if (!full && availIn == 0 && rank(flush) <= rank(oldFlush) && flush != Z_FINISH) {
                // (a flush again with nothing to do: the Deflater told it too, as zlib's own check does)
                if (status != FINISH) run(0, javaFlush(flush), false)
                msg = "buffer error"
                return Z_BUF_ERROR
            }
            if (status == FINISH && availIn != 0) {
                msg = "buffer error"
                return Z_BUF_ERROR
            }
            if (status == INIT) {
                header()
                status = BUSY
                flushPending()
                if (pendingLen() > 0) {
                    lastFlush = -1
                    return Z_OK
                }
            }
            if (status != FINISH) {
                blocks(flush)
                if (d.finished()) status = FINISH
                if (full) {
                    lastFlush = -1
                    return Z_OK
                }
            }
            if (flush != Z_FINISH) return Z_OK
            if (wrap == 0 || trailer) return Z_STREAM_END
            trailer = true
            if (wrap == 1) {
                val a = check
                queue(byteArrayOf((a ushr 24).toByte(), (a ushr 16).toByte(), (a ushr 8).toByte(), a.toByte()))
            } else {
                val c = check
                val t = totalIn
                queue(byteArrayOf(c.toByte(), (c ushr 8).toByte(), (c ushr 16).toByte(), (c ushr 24).toByte(),
                    t.toByte(), (t ushr 8).toByte(), (t ushr 16).toByte(), (t ushr 24).toByte()))
            }
            flushPending()
            return if (pendingLen() > 0) Z_OK else Z_STREAM_END
        }

        /** deflateEnd. */
        fun end(): Int {
            val d = d ?: return Z_STREAM_ERROR
            d.end()
            this.d = null
            log = null
            return if (status == BUSY) Z_DATA_ERROR else Z_OK
        }

        /** deflateCopy: the same stream, its Deflater made again from the calls it had; null where it has ended. */
        fun copy(): Deflate? {
            if (d == null) return null
            val c = Deflate(level, wrap, wbits, strategy, true)
            val cd = c.d!!
            var scratch = EMPTY
            for (e in log!!) {
                if (e is Dict) cd.setDictionary(e.dict)
                else if (e is Call) {
                    if (scratch.size < e.room) scratch = ByteArray(e.room)
                    if (e.finish) cd.finish()
                    cd.setInput(e.input)
                    cd.deflate(scratch, 0, e.room, e.flush)
                }
            }
            c.log = ArrayList(log!!)
            c.status = status
            c.lastFlush = lastFlush
            c.check = check
            c.totalIn = totalIn
            c.dictLen = dictLen
            c.dictId = dictId
            c.trailer = trailer
            c.pending = pending
            c.pendingAt = pendingAt
            c.full = full
            c.sinceReset = sinceReset
            c.resetDue = resetDue
            c.recent = recent.copyOf()
            c.recentLen = recentLen
            c.recentAt = recentAt
            c.msg = msg
            return c
        }
    }

    /** deflateInit2: the stream, or null for Z_STREAM_ERROR. */
    private fun deflateInit(level0: Int, method: Int, wbits0: Int, memLevel: Int, strategy: Int, logged: Boolean): Deflate? {
        val level = if (level0 == -1) 6 else level0
        var wbits = wbits0
        var wrap = 1
        if (wbits < 0) {
            wrap = 0
            if (wbits < -15) return null
            wbits = -wbits
        } else if (wbits > 15) {
            wrap = 2
            wbits -= 16
        }
        if (memLevel < 1 || memLevel > 9 || method != DEFLATED || wbits < 8 || wbits > 15 || level < 0 || level > 9 ||
            strategy < 0 || strategy > Z_FIXED || (wbits == 8 && wrap != 1)) return null
        if (wbits == 8) wbits = 9
        return Deflate(level, wrap, wbits, strategy, logged)
    }

    private const val HEAD = 0
    private const val FLAGS = 1
    private const val TIME = 2
    private const val OS = 3
    private const val EXLEN = 4
    private const val EXTRA = 5
    private const val NAME = 6
    private const val COMMENT = 7
    private const val HCRC = 8
    private const val DICTID = 9
    private const val DICT = 10
    private const val BODY = 11
    private const val CHECK = 12
    private const val LENGTH = 13
    private const val DONE = 14
    private const val BAD = 15

    private fun swap32(x: Long): Long = ((x ushr 24) and 0xFF) or ((x ushr 8) and 0xFF00) or ((x and 0xFF00) shl 8) or ((x and 0xFF) shl 24)

    /**
     * zlib's inflate() of a stream inflateInit2() made: [wrap] 0 (raw) or (wbits >> 4) + 5 (1: zlib, 2: gzip, 4:
     * check), [wbits] 0 (the header's) or 8..15; the deflate stream a raw Inflater's, the rest inflate.c's states.
     */
    class Inflate(@JvmField val wrap: Int, @JvmField var wbits: Int, logged: Boolean) : ZStream() {
        @JvmField var inf: Inflater? = Inflater(true)
        @JvmField var mode = HEAD
        @JvmField var hold = 0L
        @JvmField var bits = 0
        @JvmField var flags = -1
        @JvmField var check = 0L
        @JvmField var total = 0L
        @JvmField var length = 0
        @JvmField var havedict = false
        @JvmField var ended = false
        @JvmField var log: ArrayList<Any>? = if (logged) ArrayList() else null

        private fun need(n: Int): Boolean {
            while (bits < n) {
                if (availIn == 0) return false
                hold = hold or ((inBuf[nextIn].toLong() and 0xFF) shl bits)
                nextIn++
                availIn--
                bits += 8
            }
            return true
        }

        private fun initBits() {
            hold = 0L
            bits = 0
        }

        private fun bad(m: String?) {
            msg = m
            mode = BAD
            log = null
        }

        private fun crcHold(n: Int) {
            if (flags and 0x0200 != 0 && wrap and 4 != 0) {
                val b = ByteArray(n) { (hold ushr (8 * it)).toByte() }
                check = PyBinascii.crc32(check, b, 0, n)
            }
        }

        /** The Inflater over the input, to the output: whether its stream ended. */
        private fun body(): Boolean {
            val inf = inf!!
            inf.setInput(inBuf, nextIn, availIn)
            val r0 = inf.bytesRead
            val w0 = inf.bytesWritten
            val room = availOut
            var error: DataFormatException? = null
            try {
                inf.inflate(outBuf, nextOut, availOut)
            } catch (e: DataFormatException) {
                error = e
            }
            val c = (inf.bytesRead - r0).toInt()
            val p = (inf.bytesWritten - w0).toInt()
            if (error == null) log?.add(Call(inBuf.copyOfRange(nextIn, nextIn + c), room, 0, false))
            if (wrap and 4 != 0 && p > 0) {
                check = if (flags != 0) PyBinascii.crc32(check, outBuf, nextOut, p) else PyBinascii.adler32(check, outBuf, nextOut, p)
            }
            total += p
            nextIn += c
            availIn -= c
            nextOut += p
            availOut -= p
            if (error != null) {
                bad(error.message)
                return false
            }
            if (inf.finished()) {
                inf.end()
                this.inf = null
                log = null
                mode = CHECK
                return true
            }
            return false
        }

        fun inflate(flush: Int): Int {
            if (ended) return Z_STREAM_ERROR
            val in0 = availIn
            val out0 = availOut
            var ret = Z_OK
            loop@ while (true) {
                when (mode) {
                    HEAD -> {
                        if (wrap == 0) {
                            mode = BODY
                            continue@loop
                        }
                        if (!need(16)) break@loop
                        if (wrap and 2 != 0 && hold == 0x8b1fL) {
                            if (wbits == 0) wbits = 15
                            check = PyBinascii.crc32(0L, byteArrayOf(0x1f, 0x8b.toByte()), 0, 2)
                            initBits()
                            mode = FLAGS
                            continue@loop
                        }
                        if (wrap and 1 == 0 || (((hold and 0xFF) shl 8) + (hold ushr 8)) % 31 != 0L) {
                            bad("incorrect header check")
                            continue@loop
                        }
                        if (hold and 0xF != DEFLATED.toLong()) {
                            bad("unknown compression method")
                            continue@loop
                        }
                        val len = ((hold ushr 4) and 0xF).toInt() + 8
                        if (wbits == 0) wbits = len
                        if (len > 15 || len > wbits) {
                            bad("invalid window size")
                            continue@loop
                        }
                        flags = 0
                        check = 1L
                        mode = if (hold and 0x2000 != 0L) DICTID else BODY
                        initBits()
                    }
                    FLAGS -> {
                        if (!need(16)) break@loop
                        flags = hold.toInt()
                        if (flags and 0xFF != DEFLATED) {
                            bad("unknown compression method")
                            continue@loop
                        }
                        if (flags and 0xE000 != 0) {
                            bad("unknown header flags set")
                            continue@loop
                        }
                        crcHold(2)
                        initBits()
                        mode = TIME
                    }
                    TIME -> {
                        if (!need(32)) break@loop
                        crcHold(4)
                        initBits()
                        mode = OS
                    }
                    OS -> {
                        if (!need(16)) break@loop
                        crcHold(2)
                        initBits()
                        mode = EXLEN
                    }
                    EXLEN -> {
                        if (flags and 0x0400 != 0) {
                            if (!need(16)) break@loop
                            length = hold.toInt()
                            crcHold(2)
                            initBits()
                        }
                        mode = EXTRA
                    }
                    EXTRA -> {
                        if (flags and 0x0400 != 0) {
                            val copy = minOf(length, availIn)
                            if (copy > 0) {
                                if (flags and 0x0200 != 0 && wrap and 4 != 0) check = PyBinascii.crc32(check, inBuf, nextIn, copy)
                                nextIn += copy
                                availIn -= copy
                                length -= copy
                            }
                            if (length != 0) break@loop
                        }
                        length = 0
                        mode = NAME
                    }
                    NAME, COMMENT -> {
                        if (flags and (if (mode == NAME) 0x0800 else 0x1000) != 0) {
                            if (availIn == 0) break@loop
                            var copy = 0
                            var len: Int
                            do {
                                len = inBuf[nextIn + copy++].toInt() and 0xFF
                            } while (len != 0 && copy < availIn)
                            if (flags and 0x0200 != 0 && wrap and 4 != 0) check = PyBinascii.crc32(check, inBuf, nextIn, copy)
                            nextIn += copy
                            availIn -= copy
                            if (len != 0) break@loop
                        }
                        mode = if (mode == NAME) COMMENT else HCRC
                    }
                    HCRC -> {
                        if (flags and 0x0200 != 0) {
                            if (!need(16)) break@loop
                            if (wrap and 4 != 0 && hold != (check and 0xFFFF)) {
                                bad("header crc mismatch")
                                continue@loop
                            }
                            initBits()
                        }
                        check = 0L
                        mode = BODY
                    }
                    DICTID -> {
                        if (!need(32)) break@loop
                        check = swap32(hold)
                        initBits()
                        mode = DICT
                    }
                    DICT -> {
                        if (!havedict) return Z_NEED_DICT
                        check = 1L
                        mode = BODY
                    }
                    BODY -> if (!body() && mode != BAD) break@loop
                    CHECK -> {
                        if (wrap != 0) {
                            if (!need(32)) break@loop
                            if (wrap and 4 != 0 && (if (flags != 0) hold else swap32(hold)) != check) {
                                bad("incorrect data check")
                                continue@loop
                            }
                            initBits()
                        }
                        mode = LENGTH
                    }
                    LENGTH -> {
                        if (wrap != 0 && flags != 0) {
                            if (!need(32)) break@loop
                            if (wrap and 4 != 0 && hold != (total and 0xFFFFFFFFL)) {
                                bad("incorrect length check")
                                continue@loop
                            }
                            initBits()
                        }
                        mode = DONE
                    }
                    DONE -> {
                        ret = Z_STREAM_END
                        break@loop
                    }
                    else -> {
                        ret = Z_DATA_ERROR
                        break@loop
                    }
                }
            }
            if (((in0 == availIn && out0 == availOut) || flush == Z_FINISH) && ret == Z_OK) ret = Z_BUF_ERROR
            return ret
        }

        /** inflateSetDictionary. */
        fun setDictionary(dict: ByteArray): Int {
            if (ended) return Z_STREAM_ERROR
            if (wrap != 0 && mode != DICT) return Z_STREAM_ERROR
            if (mode == DICT && PyBinascii.adler32(1L, dict, 0, dict.size) != check) return Z_DATA_ERROR
            inf!!.setDictionary(dict)
            log?.add(Dict(dict.copyOf()))
            havedict = true
            return Z_OK
        }

        /** inflateEnd. */
        fun end(): Int {
            if (ended) return Z_STREAM_ERROR
            inf?.end()
            inf = null
            log = null
            ended = true
            return Z_OK
        }

        /** inflateCopy: the same stream, its Inflater made again from the calls it had; null where it has ended. */
        fun copy(): Inflate? {
            if (ended) return null
            val c = Inflate(wrap, wbits, true)
            val ci = c.inf!!
            if (inf == null || log == null) {
                // (its Inflater done with: the body's end reached, or an error)
                ci.end()
                c.inf = null
                c.log = null
            } else {
                var scratch = EMPTY
                for (e in log!!) {
                    if (e is Dict) ci.setDictionary(e.dict)
                    else if (e is Call) {
                        if (scratch.size < e.room) scratch = ByteArray(e.room)
                        ci.setInput(e.input)
                        ci.inflate(scratch, 0, e.room)
                    }
                }
                c.log = ArrayList(log!!)
            }
            c.mode = mode
            c.hold = hold
            c.bits = bits
            c.flags = flags
            c.check = check
            c.total = total
            c.length = length
            c.havedict = havedict
            c.msg = msg
            return c
        }
    }

    /** inflateInit2: the stream, or null for Z_STREAM_ERROR. */
    private fun inflateInit(wbits0: Int, logged: Boolean): Inflate? {
        var wbits = wbits0
        val wrap: Int
        if (wbits < 0) {
            if (wbits < -15) return null
            wrap = 0
            wbits = -wbits
        } else {
            wrap = (wbits shr 4) + 5
            if (wbits < 48) wbits = wbits and 15
        }
        if (wbits != 0 && (wbits < 8 || wbits > 15)) return null
        return Inflate(wrap, wbits, logged)
    }

    // ------------------------------------------------------------------ output buffers (pycore_blocks_output_buffer.h)

    private val BLOCK_SIZE = intArrayOf(32 * 1024, 64 * 1024, 256 * 1024, 1024 * 1024, 4 * 1024 * 1024, 8 * 1024 * 1024,
        16 * 1024 * 1024, 16 * 1024 * 1024, 32 * 1024 * 1024, 32 * 1024 * 1024, 32 * 1024 * 1024, 32 * 1024 * 1024,
        64 * 1024 * 1024, 64 * 1024 * 1024, 128 * 1024 * 1024, 128 * 1024 * 1024, 256 * 1024 * 1024)

    /** _BlocksOutputBuffer: blocks of output, each the stream's output while it is the last. */
    private class Blocks {
        @JvmField val list = ArrayList<ByteArray>()
        @JvmField var allocated = 0L
        @JvmField var maxLength = -1L

        private fun block(z: ZStream, size: Long) {
            if (size > Int.MAX_VALUE - 16) throw PyException("MemoryError", "")
            val b = try {
                ByteArray(size.toInt())
            } catch (e: OutOfMemoryError) {
                throw PyException("MemoryError", "")
            }
            list.add(b)
            allocated += size
            z.outBuf = b
            z.nextOut = 0
            z.availOut = b.size
        }

        fun initAndGrow(z: ZStream, maxLength: Long) {
            this.maxLength = maxLength
            block(z, if (maxLength in 0 until BLOCK_SIZE[0]) maxLength else BLOCK_SIZE[0].toLong())
        }

        fun initWithSize(z: ZStream, size: Long) {
            maxLength = -1
            block(z, size)
        }

        fun grow(z: ZStream) {
            var size = BLOCK_SIZE[minOf(list.size, BLOCK_SIZE.size - 1)].toLong()
            if (maxLength >= 0) size = minOf(size, maxLength - allocated)
            block(z, size)
        }

        fun dataSize(z: ZStream): Long = allocated - z.availOut

        fun finish(z: ZStream): PyBytes {
            if (list.size == 1 && z.availOut == 0) return PyBytes(list[0])
            val out = ByteArray(dataSize(z).toInt())
            var at = 0
            for ((k, b) in list.withIndex()) {
                val n = if (k == list.size - 1) b.size - z.availOut else b.size
                b.copyInto(out, at, 0, n)
                at += n
            }
            return PyBytes(out)
        }
    }

    // ------------------------------------------------------------------ the module's state

    private class State(@JvmField val ctx: PyContext) {
        @JvmField val error: PyType = PyBinascii.newException(ctx, "zlib", "error", PyExceptions.type("Exception"))
        @JvmField val compType: PyType = moduleType(ctx, "Compress")
        @JvmField val decompType: PyType = moduleType(ctx, "Decompress")
        @JvmField val decompressorType: PyType = moduleType(ctx, "_ZlibDecompressor")

        fun error(msg: String) = PyBinascii.error(ctx, error, msg)

        /** zlib_error: "Error [err] [msg]", the stream's message (else the code's) after it. */
        fun zlibError(z: ZStream, err: Int, msg: String): PyException {
            var zmsg: String? = if (err == Z_VERSION_ERROR) "library version mismatch" else null
            if (zmsg == null) zmsg = z.msg
            if (zmsg == null) zmsg = when (err) {
                Z_BUF_ERROR -> "incomplete or truncated stream"
                Z_STREAM_ERROR -> "inconsistent stream state"
                Z_DATA_ERROR -> "invalid input data"
                else -> null
            }
            return error(if (zmsg == null) "Error $err $msg" else "Error $err $msg: ${zmsg.take(200)}")
        }
    }

    /** A type of the module: a heap type, as CPython's made from a spec are, not subclassable. */
    private fun moduleType(ctx: PyContext, name: String): PyType {
        val t = PyType(name, arrayOf(PyTypes.OBJECT), heap = true)
        t.mro = PyTypes.c3(t, t.bases)
        t.module = "zlib"
        t.tpName = "zlib.$name"
        t.subclassable = false
        PyTypes.OBJECT.addSubclass(ctx, t)
        return t
    }

    // ------------------------------------------------------------------ zlib.compress and zlib.decompress

    private fun compress(st: State, data: ByteArray, level: Int, wbits: Int): PyBytes {
        val z = deflateInit(level, DEFLATED, wbits, DEF_MEM_LEVEL, 0, false) ?: throw st.error("Bad compression level")
        val buf = Blocks()
        buf.initAndGrow(z, -1)
        z.input(data)
        z.availIn = data.size
        var err: Int
        do {
            if (z.availOut == 0) buf.grow(z)
            err = z.deflate(Z_FINISH)
            if (err == Z_STREAM_ERROR) {
                z.end()
                throw st.zlibError(z, err, "while compressing data")
            }
        } while (z.availOut == 0)
        err = z.end()
        if (err != Z_OK) throw st.zlibError(z, err, "while finishing compression")
        return buf.finish(z)
    }

    private fun decompress(st: State, data: ByteArray, wbits: Int, bufsize0: Long): PyBytes {
        if (bufsize0 < 0) throw PyException("ValueError", "bufsize must be non-negative")
        val bufsize = if (bufsize0 == 0L) 1L else bufsize0
        val buf = Blocks()
        val z0 = ZStream()
        buf.initWithSize(z0, bufsize)
        val z = inflateInit(wbits, false) ?: throw st.zlibError(ZStream(), Z_STREAM_ERROR, "while preparing to decompress data")
        z.outBuf = z0.outBuf
        z.availOut = z0.availOut
        z.input(data)
        z.availIn = data.size
        var err: Int
        do {
            if (z.availOut == 0) buf.grow(z)
            err = z.inflate(Z_FINISH)
            when (err) {
                Z_OK, Z_BUF_ERROR, Z_STREAM_END -> {}
                else -> {
                    z.end()
                    throw st.zlibError(z, err, "while decompressing data")
                }
            }
        } while (z.availOut == 0)
        if (err != Z_STREAM_END) {
            z.end()
            throw st.zlibError(z, err, "while decompressing data")
        }
        z.end()
        return buf.finish(z)
    }

    // ------------------------------------------------------------------ Compress

    private fun ready(o: PyZlibObj): Deflate = o.comp!!

    private fun compCompress(st: State, o: PyZlibObj, data: ByteArray): PyBytes {
        val z = ready(o)
        val buf = Blocks()
        z.input(data)
        z.availIn = data.size
        buf.initAndGrow(z, -1)
        do {
            if (z.availOut == 0) buf.grow(z)
            val err = z.deflate(Z_NO_FLUSH)
            if (err == Z_STREAM_ERROR) throw st.zlibError(z, err, "while compressing data")
        } while (z.availOut == 0)
        return buf.finish(z)
    }

    private fun compFlush(st: State, o: PyZlibObj, mode: Int): PyBytes {
        if (mode == Z_NO_FLUSH) return PyBytes(EMPTY)
        val z = ready(o)
        z.input(EMPTY)
        val buf = Blocks()
        buf.initAndGrow(z, -1)
        var err: Int
        do {
            if (z.availOut == 0) buf.grow(z)
            err = z.deflate(mode)
            if (err == Z_STREAM_ERROR) throw st.zlibError(z, err, "while flushing")
        } while (z.availOut == 0)
        if (err == Z_STREAM_END && mode == Z_FINISH) {
            err = z.end()
            if (err != Z_OK) throw st.zlibError(z, err, "while finishing compression")
        } else if (err != Z_OK && err != Z_BUF_ERROR) throw st.zlibError(z, err, "while flushing")
        return buf.finish(z)
    }

    private fun compCopy(st: State, o: PyZlibObj): PyZlibObj {
        val c = ready(o).copy() ?: throw PyException("ValueError", "Inconsistent stream state")
        return PyZlibObj(st.compType).also {
            it.comp = c
            it.unusedData = o.unusedData
            it.unconsumedTail = o.unconsumedTail
            it.zdict = o.zdict
            it.eof = o.eof
        }
    }

    // ------------------------------------------------------------------ Decompress

    /** set_inflate_zdict: the object's zdict given to its stream. */
    private fun setInflateZdict(st: State, o: PyZlibObj, z: Inflate) {
        val dict = PyBinascii.buffer(o.zdict)
        val err = z.setDictionary(dict)
        if (err != Z_OK) throw st.zlibError(z, err, "while setting zdict")
    }

    /** save_unconsumed_input: what of [data] the stream left, in unused_data or unconsumed_tail. */
    private fun saveUnconsumed(o: PyZlibObj, z: Inflate, data: ByteArray, err: Int) {
        if (err == Z_STREAM_END && z.availIn > 0) {
            val left = data.copyOfRange(z.nextIn, data.size)
            o.unusedData = PyBytes(if (o.unusedData.b.isEmpty()) left else o.unusedData.b + left)
            z.availIn = 0
        }
        if (z.availIn > 0 || o.unconsumedTail.b.isNotEmpty()) o.unconsumedTail = PyBytes(data.copyOfRange(z.nextIn, data.size))
    }

    private fun decompDecompress(st: State, o: PyZlibObj, data: ByteArray, maxLength0: Long): PyBytes {
        if (maxLength0 < 0) throw PyException("ValueError", "max_length must be non-negative")
        val maxLength = if (maxLength0 == 0L) -1L else maxLength0
        val z = o.decomp!!
        z.input(data)
        val buf = Blocks()
        buf.initAndGrow(z, maxLength)
        z.availIn = data.size
        var err = Z_OK
        save@ do {
            do {
                if (z.availOut == 0) {
                    if (buf.dataSize(z) == maxLength) break@save
                    buf.grow(z)
                }
                err = z.inflate(Z_SYNC_FLUSH)
                when (err) {
                    Z_OK, Z_BUF_ERROR, Z_STREAM_END -> {}
                    else -> {
                        if (err != Z_NEED_DICT || o.zdict == null) break@save
                        setInflateZdict(st, o, z)
                    }
                }
            } while (z.availOut == 0 || err == Z_NEED_DICT)
        } while (false)
        saveUnconsumed(o, z, data, err)
        if (err == Z_STREAM_END) o.eof = true
        else if (err != Z_OK && err != Z_BUF_ERROR) throw st.zlibError(z, err, "while decompressing data")
        return buf.finish(z)
    }

    private fun decompFlush(st: State, o: PyZlibObj, length: Long): PyBytes {
        if (length <= 0) throw PyException("ValueError", "length must be greater than zero")
        val z = o.decomp!!
        val data = o.unconsumedTail.b
        z.input(data)
        val buf = Blocks()
        buf.initWithSize(z, length)
        z.availIn = data.size
        var err: Int
        save@ do {
            do {
                if (z.availOut == 0) buf.grow(z)
                err = z.inflate(Z_FINISH)
                when (err) {
                    Z_OK, Z_BUF_ERROR, Z_STREAM_END -> {}
                    else -> break@save
                }
            } while (z.availOut == 0 || err == Z_NEED_DICT)
        } while (false)
        saveUnconsumed(o, z, data, err)
        if (err == Z_STREAM_END) {
            o.eof = true
            z.end()
        }
        return buf.finish(z)
    }

    private fun decompCopy(st: State, o: PyZlibObj): PyZlibObj {
        val c = o.decomp!!.copy() ?: throw PyException("ValueError", "Inconsistent stream state")
        return PyZlibObj(st.decompType).also {
            it.decomp = c
            it.unusedData = o.unusedData
            it.unconsumedTail = o.unconsumedTail
            it.zdict = o.zdict
            it.eof = o.eof
        }
    }

    // ------------------------------------------------------------------ _ZlibDecompressor

    /** decompress_buf: at most [maxLength] bytes (-1: any) of the input from the stream's next_in. */
    private fun decompressBuf(st: State, o: PyZlibObj, z: Inflate, total: Int, maxLength: Long): PyBytes {
        val hardLimit: Long
        var obuflen: Long
        if (maxLength < 0 || maxLength == Long.MAX_VALUE) {
            hardLimit = Long.MAX_VALUE
            obuflen = DEF_BUF_SIZE.toLong()
        } else {
            hardLimit = maxLength
            obuflen = minOf(maxLength, DEF_MAX_INITIAL_BUF_SIZE.toLong())
        }
        var out: ByteArray? = null
        z.availIn = total
        var err = Z_OK
        do {
            // (arrange_output_buffer_with_maximum)
            val b = out
            if (b == null) {
                if (obuflen > Int.MAX_VALUE - 16) throw PyException("MemoryError", "Insufficient memory for buffer allocation")
                out = ByteArray(obuflen.toInt())
                z.outBuf = out
                z.nextOut = 0
            } else if (obuflen == z.nextOut.toLong()) {
                if (obuflen == hardLimit) break
                obuflen = if (obuflen <= hardLimit shr 1) obuflen shl 1 else hardLimit
                if (obuflen > Int.MAX_VALUE - 16) throw PyException("MemoryError", "Insufficient memory for buffer allocation")
                out = b.copyOf(obuflen.toInt())
                z.outBuf = out
            }
            z.availOut = (obuflen - z.nextOut).toInt()
            err = z.inflate(Z_SYNC_FLUSH)
            when (err) {
                Z_OK, Z_BUF_ERROR, Z_STREAM_END -> {}
                // (no exception set: CPython's SystemError)
                Z_NEED_DICT -> throw PyException("SystemError", "error return without exception set")
            }
        } while (z.availOut == 0)
        if (err == Z_STREAM_END) {
            o.eof = true
            z.end()
        } else if (err != Z_OK && err != Z_BUF_ERROR) throw st.zlibError(z, err, "while decompressing data")
        return PyBytes(out.copyOf(z.nextOut))
    }

    private fun decompressorDecompress(st: State, o: PyZlibObj, data: ByteArray, maxLength: Long): PyBytes {
        if (o.eof) throw PyException("EOFError", "End of stream already reached")
        val z = o.decomp!!
        val p = o.pending
        val input = if (p != null) p + data else data
        z.input(input)
        val r = try {
            decompressBuf(st, o, z, input.size, maxLength)
        } catch (e: PyException) {
            o.pending = null
            throw e
        }
        val left = z.availIn
        if (o.eof) {
            o.needsInput = false
            if (left > 0) o.unusedData = PyBytes(input.copyOfRange(z.nextIn, input.size))
        } else if (left == 0) {
            o.pending = null
            o.needsInput = true
        } else {
            o.needsInput = false
            o.pending = input.copyOfRange(z.nextIn, input.size)
        }
        return r
    }

    /** PyArg_ParseTuple's "i": a C int (__index__ taken). */
    private fun argInt(v: Any?): Int {
        val i = PyRt.indexValue(v) ?: throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer")
        if (i !is Long) throw PyException("OverflowError", "Python int too large to convert to C long")
        if (i > Int.MAX_VALUE) throw PyException("OverflowError", "signed integer is greater than maximum")
        if (i < Int.MIN_VALUE) throw PyException("OverflowError", "signed integer is less than minimum")
        return i.toInt()
    }

    private fun newDecompressor(st: State, a: Array<Any?>, kw: PyDict?): PyZlibObj {
        // (PyArg_ParseTupleAndKeywords "|iO:_ZlibDecompressor")
        val names = listOf("wbits", "zdict")
        val n = a.size + (kw?.size ?: 0)
        if (n > 2) throw PyRt.typeError("_ZlibDecompressor() takes at most 2 arguments ($n given)")
        val p = arrayOfNulls<Any?>(2)
        a.copyInto(p)
        if (kw != null) for ((k, v) in kw.map) {
            val name = (k.v as PyStr).s
            val i = names.indexOf(name)
            if (i < 0) throw PyRt.typeError("_ZlibDecompressor() got an unexpected keyword argument '$name'")
            if (i < a.size) throw PyRt.typeError("argument for _ZlibDecompressor() given by name ('$name') and position (${i + 1})")
            p[i] = v
        }
        val wbits = if (p[0] != null) argInt(p[0]) else MAX_WBITS
        val o = PyZlibObj(st.decompressorType)
        o.zdict = p[1]
        val z = inflateInit(wbits, false) ?: throw PyException("ValueError", "Invalid initialization option")
        o.decomp = z
        if (o.zdict != null && wbits < 0) setInflateZdict(st, o, z)
        return o
    }

    // ------------------------------------------------------------------ the types

    private fun self(t: PyType, a: Array<Any?>, n: String): PyZlibObj {
        val name = t.tpName ?: t.name
        if (a.isEmpty()) throw PyRt.typeError("unbound method ${t.name}.$n() needs an argument")
        val o = a[0]
        if (o !is PyZlibObj || o.type !== t) throw PyRt.typeError("descriptor '$n' for '$name' objects doesn't apply to a '${PyTypes.name(o)}' object")
        return o
    }

    private fun defineType(t: PyType, doc: Any?) {
        t.def("__setattr__", PyBuiltin("__setattr__") { _, a ->
            val n = (a[1] as? PyStr ?: throw PyRt.typeError("attribute name must be string, not '${PyTypes.name(a[1])}'")).s
            if (t.lookup(n) is PyProperty) PyClasses.genericSetAttr(a[0], n, a[2])
            else throw PyException("AttributeError", "'${t.tpName}' object has no attribute '$n' and no __dict__ for setting new attributes")
            PyNone
        })
        t.def("__delattr__", PyBuiltin("__delattr__") { _, a ->
            val n = (a[1] as? PyStr ?: throw PyRt.typeError("attribute name must be string, not '${PyTypes.name(a[1])}'")).s
            if (t.lookup(n) is PyProperty) PyClasses.genericDelAttr(a[0], n)
            else throw PyException("AttributeError", "'${t.tpName}' object has no attribute '$n'")
            PyNone
        })
        t.hooks = 2 or 4
        t.def("__reduce_ex__", REDUCE_EX)
        t.def("__module__", PyStr("zlib"))
        t.def("__doc__", doc)
        t.dispatchBuiltins = true
    }

    /**
     * object.__reduce_ex__ as CPython's is for these types: from protocol 2, the state object_getstate requires is
     * one a C struct holds, "cannot pickle"; else object's.
     */
    private val REDUCE_EX = PyBuiltin("__reduce_ex__") { ctx, a ->
        val v = a.getOrNull(0)
        val proto = PyRt.indexValue(a.getOrNull(1)) as? Long ?: throw PyRt.typeError("__reduce_ex__() missing required argument 'protocol' (pos 1)")
        if (proto >= 2) throw PyRt.typeError("cannot pickle '${PyTypes.name(v)}' object")
        PyDunder.reduceEx(ctx, v, proto.toInt())
    }

    /** A read-only member. */
    private fun member(t: PyType, n: String, get: (PyZlibObj) -> Any?) {
        val readonly = PyBuiltin(n) { _, _ -> throw PyException("AttributeError", "readonly attribute") }
        t.def(n, PyProperty(PyBuiltin(n) { _, a -> get(self(t, a, n)) }, readonly, readonly, null).also { it.name = n })
    }

    /** A method by Argument Clinic: [names] (its receiver's not among them), the first [posOnly] by position only. */
    private fun method(t: PyType, n: String, names: List<String>, posOnly: Int, minPos: Int, maxPos: Int, impl: (PyZlibObj, Array<Any?>) -> Any?) {
        fun call(a: Array<Any?>, kw: PyDict?): Any? {
            val o = self(t, a, n)
            return impl(o, PyMath.clinic(n, a.copyOfRange(1, a.size), kw, names, posOnly, minPos, maxPos))
        }
        t.def(n, PyBuiltin(n, { _, a -> call(a, null) }, { _, a, kw -> call(a, kw) }))
    }

    /** A method of no arguments (`copy`, `__copy__`): "copy() takes no arguments". */
    private fun method0(t: PyType, n: String, impl: (PyZlibObj) -> Any?) {
        t.def(n, PyBuiltin(n, { _, a ->
            val o = self(t, a, n)
            if (a.size > 1) throw PyRt.typeError("$n() takes no arguments")
            impl(o)
        }, { _, a, kw ->
            val o = self(t, a, n)
            if (a.size > 1 || kw.size > 0) throw PyRt.typeError("$n() takes no arguments")
            impl(o)
        }))
    }

    private fun defineTypes(st: State) {
        val c = st.compType
        defineType(c, PyNone)
        c.newImpl = { _, _, _, _ -> throw PyRt.typeError("cannot create 'zlib.Compress' instances") }
        method(c, "compress", listOf(""), 1, 1, 1) { o, p -> compCompress(st, o, PyBinascii.buffer(p[0])) }
        method(c, "flush", listOf(""), 1, 0, 1) { o, p -> compFlush(st, o, if (p[0] != null) PyBinascii.cInt(p[0]) else Z_FINISH) }
        method0(c, "copy") { compCopy(st, it) }
        method0(c, "__copy__") { compCopy(st, it) }
        method(c, "__deepcopy__", listOf(""), 1, 1, 1) { o, _ -> compCopy(st, o) }

        val d = st.decompType
        defineType(d, PyNone)
        d.newImpl = { _, _, _, _ -> throw PyRt.typeError("cannot create 'zlib.Decompress' instances") }
        method(d, "decompress", listOf("", "max_length"), 1, 1, 2) { o, p ->
            val data = PyBinascii.buffer(p[0])
            decompDecompress(st, o, data, if (p[1] != null) PyBinascii.ssize(p[1]) else 0L)
        }
        method(d, "flush", listOf(""), 1, 0, 1) { o, p -> decompFlush(st, o, if (p[0] != null) PyBinascii.ssize(p[0]) else DEF_BUF_SIZE.toLong()) }
        method0(d, "copy") { decompCopy(st, it) }
        method0(d, "__copy__") { decompCopy(st, it) }
        method(d, "__deepcopy__", listOf(""), 1, 1, 1) { o, _ -> decompCopy(st, o) }
        member(d, "unused_data") { it.unusedData }
        member(d, "unconsumed_tail") { it.unconsumedTail }
        member(d, "eof") { PyBool.of(it.eof) }

        val z = st.decompressorType
        defineType(z, PyStr(DECOMPRESSOR_DOC))
        z.newImpl = { _, _, a, kw -> newDecompressor(st, a, kw?.takeIf { it.size > 0 }) }
        method(z, "decompress", listOf("data", "max_length"), 0, 1, 2) { o, p ->
            val data = PyBinascii.buffer(p[0])
            decompressorDecompress(st, o, data, if (p[1] != null) PyBinascii.ssize(p[1]) else -1L)
        }
        member(z, "eof") { PyBool.of(it.eof) }
        member(z, "unused_data") { it.unusedData }
        member(z, "needs_input") { PyBool.of(it.needsInput) }
    }

    // ------------------------------------------------------------------ the module

    @JvmStatic
    fun fill(ctx: PyContext, d: PyDict) {
        val st = State(ctx)
        defineTypes(st)
        fun noKw(n: String): (PyContext, Array<Any?>, PyDict) -> Any? = { _, _, _ -> throw PyRt.typeError("zlib.$n() takes no keyword arguments") }
        fun defKw(n: String, names: List<String>, posOnly: Int, minPos: Int, maxPos: Int, impl: (Array<Any?>) -> Any?) {
            d[PyStr(n)] = PyBuiltin(n, { _, a -> impl(PyMath.clinic(n, a, null, names, posOnly, minPos, maxPos)) },
                { _, a, kw -> impl(PyMath.clinic(n, a, kw, names, posOnly, minPos, maxPos)) })
        }
        fun defN(n: String, min: Int, max: Int, impl: (Array<Any?>) -> Any?) {
            d[PyStr(n)] = PyBuiltin(n, { _, a ->
                PyMath.checkPositional(n, a.size, min, max)
                impl(a)
            }, noKw(n))
        }
        defN("adler32", 1, 2) { a ->
            val data = PyBinascii.buffer(a[0])
            PyBinascii.adler32(if (a.size > 1) PyBinascii.uintMask(a[1]) else 1L, data, 0, data.size)
        }
        defKw("compress", listOf("", "level", "wbits"), 1, 1, 3) { p ->
            val data = PyBinascii.buffer(p[0])
            val level = if (p[1] != null) PyBinascii.cInt(p[1]) else -1
            compress(st, data, level, if (p[2] != null) PyBinascii.cInt(p[2]) else MAX_WBITS)
        }
        defKw("compressobj", listOf("level", "method", "wbits", "memLevel", "strategy", "zdict"), 0, 0, 6) { p ->
            val level = if (p[0] != null) PyBinascii.cInt(p[0]) else -1
            val method = if (p[1] != null) PyBinascii.cInt(p[1]) else DEFLATED
            val wbits = if (p[2] != null) PyBinascii.cInt(p[2]) else MAX_WBITS
            val memLevel = if (p[3] != null) PyBinascii.cInt(p[3]) else DEF_MEM_LEVEL
            val strategy = if (p[4] != null) PyBinascii.cInt(p[4]) else 0
            val zdict = if (p[5] != null) PyBinascii.buffer(p[5]) else null
            val o = PyZlibObj(st.compType)
            val z = deflateInit(level, method, wbits, memLevel, strategy, true) ?: throw PyException("ValueError", "Invalid initialization option")
            o.comp = z
            if (zdict != null) {
                val err = z.setDictionary(zdict)
                if (err == Z_STREAM_ERROR) throw PyException("ValueError", "Invalid dictionary")
                if (err != Z_OK) throw PyException("ValueError", "deflateSetDictionary()")
            }
            o
        }
        defN("crc32", 1, 2) { a ->
            val data = PyBinascii.buffer(a[0])
            PyBinascii.crc32(if (a.size > 1) PyBinascii.uintMask(a[1]) else 0L, data, 0, data.size)
        }
        defKw("decompress", listOf("", "wbits", "bufsize"), 1, 1, 3) { p ->
            val data = PyBinascii.buffer(p[0])
            val wbits = if (p[1] != null) PyBinascii.cInt(p[1]) else MAX_WBITS
            decompress(st, data, wbits, if (p[2] != null) PyBinascii.ssize(p[2]) else DEF_BUF_SIZE.toLong())
        }
        defKw("decompressobj", listOf("wbits", "zdict"), 0, 0, 2) { p ->
            val wbits = if (p[0] != null) PyBinascii.cInt(p[0]) else MAX_WBITS
            val zdict = p[1]
            if (zdict != null && !PyBinascii.hasBuffer(zdict)) throw PyRt.typeError("zdict argument must support the buffer protocol")
            val o = PyZlibObj(st.decompType)
            o.zdict = zdict
            val z = inflateInit(wbits, true) ?: throw PyException("ValueError", "Invalid initialization option")
            o.decomp = z
            if (zdict != null && wbits < 0) setInflateZdict(st, o, z)
            o
        }
        d[PyStr("error")] = st.error
        d[PyStr("_ZlibDecompressor")] = st.decompressorType
        val ints = listOf("MAX_WBITS" to MAX_WBITS, "DEFLATED" to DEFLATED, "DEF_MEM_LEVEL" to DEF_MEM_LEVEL,
            "DEF_BUF_SIZE" to DEF_BUF_SIZE, "Z_NO_COMPRESSION" to 0, "Z_BEST_SPEED" to 1, "Z_BEST_COMPRESSION" to 9,
            "Z_DEFAULT_COMPRESSION" to -1, "Z_FILTERED" to Z_FILTERED, "Z_HUFFMAN_ONLY" to Z_HUFFMAN_ONLY, "Z_RLE" to Z_RLE,
            "Z_FIXED" to Z_FIXED, "Z_DEFAULT_STRATEGY" to 0, "Z_NO_FLUSH" to Z_NO_FLUSH, "Z_PARTIAL_FLUSH" to Z_PARTIAL_FLUSH,
            "Z_SYNC_FLUSH" to Z_SYNC_FLUSH, "Z_FULL_FLUSH" to Z_FULL_FLUSH, "Z_FINISH" to Z_FINISH, "Z_BLOCK" to Z_BLOCK,
            "Z_TREES" to Z_TREES)
        for ((n, v) in ints) d[PyStr(n)] = v.toLong()
        d[PyStr("ZLIB_VERSION")] = PyStr(ZLIB_VERSION)
        d[PyStr("ZLIB_RUNTIME_VERSION")] = PyStr(ZLIB_VERSION)
        d[PyStr("__version__")] = PyStr("1.0")
    }
}
