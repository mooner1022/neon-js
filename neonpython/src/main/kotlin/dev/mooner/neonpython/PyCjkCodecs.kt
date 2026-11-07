package dev.mooner.neonpython

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.zip.GZIPInputStream

// ------------------------------------------------------------------ cjkcodecs.h and multibytecodec.h

/** multibytecodec.h's error returns (a positive one: the illegal sequence's length) and encode flags; cjkcodecs.h's code points. */
internal object Mb {
    const val TOOSMALL = -1
    const val TOOFEW = -2
    const val INTERNAL = -3
    const val FLUSH = 1
    const val RESET = 2
    const val UNIINV = 0xFFFE
    const val NOCHAR = 0xFFFF
    const val MULTIC = 0xFFFE
    const val DBCINV = 0xFFFD
}

/**
 * A mapping table of CPython's cjkcodecs (cjkcodecs.h's dbcs_index and unim_index): rows by a first byte (a decmap)
 * or a code point's high byte (an encmap), each its values from [bottom] through [top], [start] the first's index in
 * [values] (-1: no row).
 */
internal class CjkMap(@JvmField val start: IntArray, @JvmField val bottom: IntArray, @JvmField val top: IntArray, @JvmField val values: CharArray) {
    /** TRYMAP_DEC: the value at ([c1], [c2]), or -1 where there is none (no row, outside it, UNIINV). */
    fun dec(c1: Int, c2: Int): Int {
        if (c1 ushr 8 != 0) return -1
        val s = start[c1]
        if (s < 0 || c2 < bottom[c1] || c2 > top[c1]) return -1
        val v = values[s + c2 - bottom[c1]].code
        return if (v == Mb.UNIINV) -1 else v
    }

    /** TRYMAP_ENC: the code of [u] (a code point of the BMP, or the low 16 bits of one), or -1 (NOCHAR). */
    fun enc(u: Int): Int {
        val c1 = u ushr 8
        if (c1 ushr 8 != 0) return -1
        val s = start[c1]
        val c2 = u and 0xFF
        if (s < 0 || c2 < bottom[c1] || c2 > top[c1]) return -1
        val v = values[s + c2 - bottom[c1]].code
        return if (v == Mb.NOCHAR) -1 else v
    }
}

/** A decmap of Py_UCS4 (widedbcs_index: jisx0213_pair_decmap, a pair as its two code points' halves). */
internal class CjkWideMap(@JvmField val start: IntArray, @JvmField val bottom: IntArray, @JvmField val top: IntArray, @JvmField val values: IntArray) {
    fun dec(c1: Int, c2: Int): Int {
        if (c1 ushr 8 != 0) return -1
        val s = start[c1]
        if (s < 0 || c2 < bottom[c1] || c2 > top[c1]) return -1
        val v = values[s + c2 - bottom[c1]]
        return if (v == Mb.UNIINV) -1 else v
    }
}

/** jisx0213_pair_encmap (pair_encodemap): the pairs' sequences (first code point << 16 | second), sorted, and their codes. */
internal class CjkPairs(@JvmField val seqs: LongArray, @JvmField val codes: IntArray) {
    /** find_pairencmap: the code of ([body], [modifier]) (each a ucs2_t), by its binary search, else DBCINV. */
    fun find(body: Int, modifier: Int): Int {
        val value = ((body and 0xFFFF).toLong() shl 16) or (modifier and 0xFFFF).toLong()
        var min = 0
        var max = seqs.size
        var pos = seqs.size shr 1
        while (min != max) {
            if (value < seqs[pos]) {
                if (max != pos) {
                    max = pos
                    pos = (min + max) shr 1
                    continue
                }
            } else if (value > seqs[pos]) {
                if (min != pos) {
                    min = pos
                    pos = (min + max) shr 1
                    continue
                }
            }
            break
        }
        return if (value == seqs[pos]) codes[pos] else Mb.DBCINV
    }
}

/**
 * CPython's mapping tables (Modules/cjkcodecs/mappings_*.h), which neonpython/tools/gen_cjkcodecs.py puts in
 * cjkcodecs.bin: each read from it, and inflated, when a codec first needs it, then kept (every context's).
 */
internal object CjkTables {
    private const val KIND_DEC = 0
    private const val KIND_ENC = 1
    private const val KIND_WIDE = 2
    private const val KIND_PAIRS = 3
    private const val KIND_RANGES = 4
    private const val KIND_BYTES = 5

    // (no lock of their own: [table] is the one, so that no two threads wait on each other's)
    private val PUB = LazyThreadSafetyMode.PUBLICATION

    private class Entry(@JvmField val kind: Int, @JvmField val offset: Int, @JvmField val length: Int)

    private class Reader(val b: ByteArray) {
        var p = 0

        fun u(): Int {
            var r = 0
            var shift = 0
            while (true) {
                val x = b[p++].toInt() and 0xFF
                r = r or ((x and 0x7F) shl shift)
                if (x and 0x80 == 0) return r
                shift += 7
            }
        }

        fun ul(): Long {
            var r = 0L
            var shift = 0
            while (true) {
                val x = b[p++].toInt() and 0xFF
                r = r or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) return r
                shift += 7
            }
        }
    }

    private fun open() = CjkTables::class.java.getResourceAsStream("cjkcodecs.bin")!!

    /** The tables by name, and where they start (after the directory). */
    private val directory: Pair<Map<String, Entry>, Int> by lazy {
        DataInputStream(open().buffered()).use { s ->
            val magic = ByteArray(5)
            s.readFully(magic)
            check(String(magic, 0, 4, Charsets.ISO_8859_1) == "NPCJ" && magic[4].toInt() == 1) { "cjkcodecs.bin: not version 1" }
            var read = 5
            fun u(): Int {
                var r = 0
                var shift = 0
                while (true) {
                    val x = s.readUnsignedByte()
                    read++
                    r = r or ((x and 0x7F) shl shift)
                    if (x and 0x80 == 0) return r
                    shift += 7
                }
            }
            val n = u()
            val m = HashMap<String, Entry>()
            repeat(n) {
                val len = u()
                val name = ByteArray(len)
                s.readFully(name)
                read += len
                val kind = u()
                val offset = u()
                m[String(name, Charsets.ISO_8859_1)] = Entry(kind, offset, u())
            }
            m to read
        }
    }

    /** The table [name]'s bytes, inflated; checked to be of [kind]. */
    private fun section(name: String, kind: Int): Reader {
        val (dir, base) = directory
        val e = dir[name] ?: error("cjkcodecs.bin: no table $name")
        check(e.kind == kind) { "cjkcodecs.bin: $name is not of kind $kind" }
        val z = ByteArray(e.length)
        DataInputStream(open()).use { s ->
            var skip = (base + e.offset).toLong()
            while (skip > 0) {
                val k = s.skip(skip)
                if (k <= 0) {
                    s.readByte()
                    skip--
                } else {
                    skip -= k
                }
            }
            s.readFully(z)
        }
        return Reader(GZIPInputStream(ByteArrayInputStream(z)).use { it.readBytes() })
    }

    private class Rows(val start: IntArray, val bottom: IntArray, val top: IntArray, val count: Int)

    private fun rows(r: Reader): Rows {
        val start = IntArray(256) { -1 }
        val bottom = IntArray(256)
        val top = IntArray(256)
        var at = 0
        repeat(r.u()) {
            val c1 = r.u()
            bottom[c1] = r.u()
            top[c1] = r.u()
            start[c1] = at
            at += top[c1] - bottom[c1] + 1
        }
        return Rows(start, bottom, top, at)
    }

    private fun unzigzag(z: Long): Long = (z ushr 1) xor -(z and 1)

    /** The tables read so far, by name. */
    private val cache = HashMap<String, Any>()

    @Synchronized
    private fun table(name: String, load: () -> Any): Any = cache[name] ?: load().also { cache[name] = it }

    /** A decmap: its values, each the difference from the one before it. */
    private fun dec(name: String): CjkMap = table(name) {
        val r = section(name, KIND_DEC)
        val rows = rows(r)
        val v = CharArray(rows.count)
        var prev = 0
        for (i in v.indices) {
            prev = (prev + unzigzag(r.ul()).toInt()) and 0xFFFF
            v[i] = prev.toChar()
        }
        CjkMap(rows.start, rows.bottom, rows.top, v)
    } as CjkMap

    /**
     * An encmap: mostly the inverse of the decmaps it names (each a mask on its codes, and which of big5hkscs's
     * characters, those of the BMP or those past it), the first code of a character kept, NOCHAR elsewhere; then the
     * values that are not that, by position (gen_cjkcodecs.py's derive()).
     */
    private fun enc(name: String): CjkMap = table(name) {
        val r = section(name, KIND_ENC)
        val rows = rows(r)
        val v = CharArray(rows.count) { Mb.NOCHAR.toChar() }
        repeat(r.u()) {
            val n = r.u()
            val src = String(r.b, r.p, n, Charsets.ISO_8859_1)
            r.p += n
            val mask = r.u()
            val which = r.u()
            val d = dec(src)
            for (c1 in 0 until 256) {
                val s = d.start[c1]
                if (s < 0) continue
                for (c2 in d.bottom[c1]..d.top[c1]) {
                    val u = d.values[s + c2 - d.bottom[c1]].code
                    if (u == Mb.UNIINV) continue
                    if (which != 0 && hkscsHint(c1, c2) != which - 1) continue
                    val e1 = u ushr 8
                    val e2 = u and 0xFF
                    if (rows.start[e1] < 0 || e2 < rows.bottom[e1] || e2 > rows.top[e1]) continue
                    val k = rows.start[e1] + e2 - rows.bottom[e1]
                    if (v[k].code == Mb.NOCHAR) v[k] = ((c1 shl 8) or c2 or mask).toChar()
                }
            }
        }
        var k = -1
        repeat(r.u()) {
            k += r.u() + 1
            v[k] = r.u().toChar()
        }
        CjkMap(rows.start, rows.bottom, rows.top, v)
    } as CjkMap

    private fun bytes(name: String): ByteArray = table(name) {
        val r = section(name, KIND_BYTES)
        val n = r.u()
        r.b.copyOfRange(r.p, r.p + n)
    } as ByteArray

    private fun bh2s(c1: Int, c2: Int) = (c1 - 0x87) * (0xfe - 0x40 + 1) + (c2 - 0x40)

    /**
     * _codecs_hk.c's plane-2 hints: whether big5hkscs's ([c1], [c2]) is a character past the BMP (1) or not (0), -1
     * outside the hints' ranges.
     */
    fun hkscsHint(c1: Int, c2: Int): Int {
        var s = bh2s(c1, c2)
        val hint: ByteArray
        if (bh2s(0x87, 0x40) <= s && s <= bh2s(0xa0, 0xfe)) {
            hint = big5hkscsPhint0
            s -= bh2s(0x87, 0x40)
        } else if (bh2s(0xc6, 0xa1) <= s && s <= bh2s(0xc8, 0xfe)) {
            hint = big5hkscsPhint12130
            s -= bh2s(0xc6, 0xa1)
        } else if (bh2s(0xf9, 0xd6) <= s && s <= bh2s(0xfe, 0xfe)) {
            hint = big5hkscsPhint21924
            s -= bh2s(0xf9, 0xd6)
        } else {
            return -1
        }
        return if (hint[s shr 3].toInt() and (1 shl (s and 7)) != 0) 1 else 0
    }

    val gb2312 by lazy(PUB) { dec("gb2312_decmap") }
    val gbkext by lazy(PUB) { dec("gbkext_decmap") }
    val gbcommon by lazy(PUB) { enc("gbcommon_encmap") }
    val gb18030extDec by lazy(PUB) { dec("gb18030ext_decmap") }
    val gb18030extEnc by lazy(PUB) { enc("gb18030ext_encmap") }

    /** gb18030_to_unibmp_ranges: first, last and base of each, the last (first 0) the end. */
    val gb18030Ranges: IntArray by lazy(PUB) {
        val r = section("gb18030_to_unibmp_ranges", KIND_RANGES)
        IntArray(r.u() * 3) { r.u() }
    }

    val jisx0208 by lazy(PUB) { dec("jisx0208_decmap") }
    val jisx0212 by lazy(PUB) { dec("jisx0212_decmap") }
    val jisxcommon by lazy(PUB) { enc("jisxcommon_encmap") }
    val cp932extDec by lazy(PUB) { dec("cp932ext_decmap") }
    val cp932extEnc by lazy(PUB) { enc("cp932ext_encmap") }
    val jisx0213_1Bmp by lazy(PUB) { dec("jisx0213_1_bmp_decmap") }
    val jisx0213_2Bmp by lazy(PUB) { dec("jisx0213_2_bmp_decmap") }
    val jisx0213Bmp by lazy(PUB) { enc("jisx0213_bmp_encmap") }
    val jisx0213_1Emp by lazy(PUB) { dec("jisx0213_1_emp_decmap") }
    val jisx0213_2Emp by lazy(PUB) { dec("jisx0213_2_emp_decmap") }
    val jisx0213Emp by lazy(PUB) { enc("jisx0213_emp_encmap") }
    val jisx0213PairDec: CjkWideMap by lazy(PUB) {
        val r = section("jisx0213_pair_decmap", KIND_WIDE)
        val rows = rows(r)
        val v = IntArray(rows.count)
        var prev = 0L
        for (i in v.indices) {
            prev = (prev + unzigzag(r.ul())) and 0xFFFFFFFFL
            v[i] = prev.toInt()
        }
        CjkWideMap(rows.start, rows.bottom, rows.top, v)
    }
    val jisx0213PairEnc: CjkPairs by lazy(PUB) {
        val r = section("jisx0213_pair_encmap", KIND_PAIRS)
        val n = r.u()
        val seqs = LongArray(n)
        val codes = IntArray(n)
        for (i in 0 until n) {
            seqs[i] = r.ul()
            codes[i] = r.u()
        }
        CjkPairs(seqs, codes)
    }

    val ksx1001 by lazy(PUB) { dec("ksx1001_decmap") }
    val cp949 by lazy(PUB) { enc("cp949_encmap") }
    val cp949ext by lazy(PUB) { dec("cp949ext_decmap") }

    val big5Dec by lazy(PUB) { dec("big5_decmap") }
    val big5Enc by lazy(PUB) { enc("big5_encmap") }
    val cp950extDec by lazy(PUB) { dec("cp950ext_decmap") }
    val cp950extEnc by lazy(PUB) { enc("cp950ext_encmap") }

    val big5hkscs by lazy(PUB) { dec("big5hkscs_decmap") }
    val big5hkscsBmp by lazy(PUB) { enc("big5hkscs_bmp_encmap") }
    val big5hkscsNonBmp by lazy(PUB) { enc("big5hkscs_nonbmp_encmap") }
    val big5hkscsPhint0 by lazy(PUB) { bytes("big5hkscs_phint_0") }
    val big5hkscsPhint12130 by lazy(PUB) { bytes("big5hkscs_phint_12130") }
    val big5hkscsPhint21924 by lazy(PUB) { bytes("big5hkscs_phint_21924") }
}

// ------------------------------------------------------------------ the codecs' interface

/**
 * An encoding call's buffers (MultibyteEncodeBuffer's cursors): the code points from [inpos] to [inlen], the output from
 * [outpos] to [outend] (what is left: REQUIRE_OUTBUF's MBERR_TOOSMALL past it, as CPython's buffer grows).
 */
internal class Enc(@JvmField var inp: IntArray, @JvmField var inlen: Int, @JvmField var out: ByteArray) {
    @JvmField var inpos = 0
    @JvmField var outpos = 0
    @JvmField var outend = out.size

    val left: Int get() = outend - outpos

    fun o(i: Int, c: Int) {
        out[outpos + i] = c.toByte()
    }

    fun next(i: Int, o: Int) {
        inpos += i
        outpos += o
    }
}

/** A decoding call's buffer (MultibyteDecodeBuffer): the bytes from [pos] to [end], what they decode to in [w]. */
internal class Dec(@JvmField var b: ByteArray, @JvmField var pos: Int, @JvmField var end: Int) {
    @JvmField val w = StringBuilder()
    /** The UnicodeDecodeError made at the first error and given each one's start, end and reason after (excobj). */
    @JvmField var exc: PyBaseException? = null

    val left: Int get() = end - pos

    /** INBYTE1 (i 0), INBYTE2 (1)... */
    fun at(i: Int): Int = b[pos + i].toInt() and 0xFF

    fun out(c: Int) {
        w.appendCodePoint(c)
    }
}

/**
 * A MultibyteCodec (multibytecodec.h): an encoding's encoder and decoder, with their init and reset where it is
 * stateful, over the 8 bytes of its MultibyteCodec_State ([st]). They return 0, the length of an illegal sequence, or
 * one of [Mb]'s errors.
 */
internal abstract class MbCodec(@JvmField val encoding: String, @JvmField val config: Int = 0) {
    /** codecinit: the maps it imports from the modules that have them (IMPORT_MAP), made where CPython makes them. */
    open fun init(ctx: PyContext) {}

    abstract fun encode(st: ByteArray, e: Enc, flags: Int): Int

    open fun encInit(st: ByteArray) {}

    /** encreset: 0 where a stateless codec has none. */
    open fun encReset(st: ByteArray, e: Enc): Int = 0

    abstract fun decode(st: ByteArray, d: Dec): Int

    open fun decInit(st: ByteArray) {}

    open fun decReset(st: ByteArray) {}
}

/** emu_jisx0213_2000.h's: what JIS X 0213:2000 lacks of 2004's (the config 2000 of euc_jisx0213, shift_jisx0213, iso2022_jp_3). */
internal object Jisx0213Emu {
    private val BMP_INVALID = intArrayOf(0x9B1C, 0x4FF1, 0x525D, 0x541E, 0x5653, 0x59F8, 0x5C5B, 0x5E77, 0x7626, 0x7E6B)
    private val PLANE1_INVALID = intArrayOf(0x2E21, 0x2F7E, 0x4F54, 0x4F7E, 0x7427, 0x7E7A, 0x7E7B, 0x7E7C, 0x7E7D, 0x7E7E)

    /** EMULATE_JISX0213_2000_ENCODE_BMP's refusal. */
    fun bmpInvalid(config: Int, c: Int) = config == 2000 && c in BMP_INVALID

    /** Its code of U+9B1D. */
    fun bmpCode(config: Int, c: Int) = config == 2000 && c == 0x9B1D

    /** EMULATE_JISX0213_2000_ENCODE_EMP's refusal. */
    fun empInvalid(config: Int, c: Int) = config == 2000 && c == 0x20B9F

    /** EMULATE_JISX0213_2000_DECODE_PLANE1's refusal. */
    fun plane1Invalid(config: Int, c1: Int, c2: Int) = config == 2000 && ((c1 shl 8) or c2) in PLANE1_INVALID

    /** EMULATE_JISX0213_2000_DECODE_PLANE2's (U+9B1D). */
    fun plane2(config: Int, c1: Int, c2: Int) = config == 2000 && c1 == 0x7D && c2 == 0x3B
}

// ------------------------------------------------------------------ _codecs_cn.c

/** Modules/cjkcodecs/_codecs_cn.c: gb2312, gbk, gb18030 and hz. */
internal object CjkCn {
    private val T = CjkTables

    /** GBK_DECODE: the character of ([c1], [c2]), or -1. */
    private fun gbkDecode(c1: Int, c2: Int): Int = when {
        c1 == 0xa1 && c2 == 0xaa -> 0x2014
        c1 == 0xa8 && c2 == 0x44 -> 0x2015
        c1 == 0xa1 && c2 == 0xa4 -> 0x00b7
        else -> T.gb2312.dec(c1 xor 0x80, c2 xor 0x80).let { if (it >= 0) it else T.gbkext.dec(c1, c2) }
    }

    /** GBK_ENCODE: the code of [c], or -1. */
    private fun gbkEncode(c: Int): Int = when (c) {
        0x2014 -> 0xa1aa
        0x2015 -> 0xa844
        0x00b7 -> 0xa1a4
        0x30fb -> -1
        else -> T.gbcommon.enc(c)
    }

    val GB2312 = object : MbCodec("gb2312") {
        override fun encode(st: ByteArray, e: Enc, flags: Int): Int {
            while (e.inpos < e.inlen) {
                val c = e.inp[e.inpos]
                if (c < 0x80) {
                    if (e.left < 1) return Mb.TOOSMALL
                    e.o(0, c)
                    e.next(1, 1)
                    continue
                }
                if (c > 0xFFFF) return 1
                if (e.left < 2) return Mb.TOOSMALL
                val code = T.gbcommon.enc(c)
                if (code < 0) return 1
                if (code and 0x8000 != 0) return 1
                e.o(0, (code shr 8) or 0x80)
                e.o(1, (code and 0xFF) or 0x80)
                e.next(1, 2)
            }
            return 0
        }

        override fun decode(st: ByteArray, d: Dec): Int {
            while (d.left > 0) {
                val c = d.at(0)
                if (c < 0x80) {
                    d.out(c)
                    d.pos++
                    continue
                }
                if (d.left < 2) return Mb.TOOFEW
                val u = T.gb2312.dec(c xor 0x80, d.at(1) xor 0x80)
                if (u < 0) return 1
                d.out(u)
                d.pos += 2
            }
            return 0
        }
    }

    val GBK = object : MbCodec("gbk") {
        override fun encode(st: ByteArray, e: Enc, flags: Int): Int {
            while (e.inpos < e.inlen) {
                val c = e.inp[e.inpos]
                if (c < 0x80) {
                    if (e.left < 1) return Mb.TOOSMALL
                    e.o(0, c)
                    e.next(1, 1)
                    continue
                }
                if (c > 0xFFFF) return 1
                if (e.left < 2) return Mb.TOOSMALL
                val code = gbkEncode(c)
                if (code < 0) return 1
                e.o(0, (code shr 8) or 0x80)
                e.o(1, if (code and 0x8000 != 0) code and 0xFF else (code and 0xFF) or 0x80)
                e.next(1, 2)
            }
            return 0
        }

        override fun decode(st: ByteArray, d: Dec): Int {
            while (d.left > 0) {
                val c = d.at(0)
                if (c < 0x80) {
                    d.out(c)
                    d.pos++
                    continue
                }
                if (d.left < 2) return Mb.TOOFEW
                val u = gbkDecode(c, d.at(1))
                if (u < 0) return 1
                d.out(u)
                d.pos += 2
            }
            return 0
        }
    }

    val GB18030 = object : MbCodec("gb18030") {
        private fun four(e: Enc, tc0: Int, first: Int) {
            var tc = tc0
            e.o(3, tc % 10 + 0x30)
            tc /= 10
            e.o(2, tc % 126 + 0x81)
            tc /= 126
            e.o(1, tc % 10 + 0x30)
            tc /= 10
            e.o(0, tc + first)
        }

        override fun encode(st: ByteArray, e: Enc, flags: Int): Int {
            while (e.inpos < e.inlen) {
                val c = e.inp[e.inpos]
                if (c < 0x80) {
                    if (e.left < 1) return Mb.TOOSMALL
                    e.o(0, c)
                    e.next(1, 1)
                    continue
                }
                if (c >= 0x10000) {
                    if (e.left < 4) return Mb.TOOSMALL
                    four(e, c - 0x10000, 0x90)
                    e.next(1, 4)
                    continue
                }
                if (e.left < 2) return Mb.TOOSMALL
                var code = gbkEncode(c)
                if (code < 0) code = T.gb18030extEnc.enc(c)
                if (code < 0) {
                    if (e.left < 4) return Mb.TOOSMALL
                    val r = T.gb18030Ranges
                    var i = 0
                    while (r[i] != 0) {
                        if (r[i] <= c && c <= r[i + 1]) {
                            four(e, c - r[i] + r[i + 2], 0x81)
                            e.next(1, 4)
                            break
                        }
                        i += 3
                    }
                    if (r[i] == 0) return 1
                    continue
                }
                e.o(0, (code shr 8) or 0x80)
                e.o(1, if (code and 0x8000 != 0) code and 0xFF else (code and 0xFF) or 0x80)
                e.next(1, 2)
            }
            return 0
        }

        override fun decode(st: ByteArray, d: Dec): Int {
            while (d.left > 0) {
                var c = d.at(0)
                if (c < 0x80) {
                    d.out(c)
                    d.pos++
                    continue
                }
                if (d.left < 2) return Mb.TOOFEW
                var c2 = d.at(1)
                if (c2 in 0x30..0x39) {
                    // (a four-byte sequence)
                    if (d.left < 4) return Mb.TOOFEW
                    var c3 = d.at(2)
                    var c4 = d.at(3)
                    if (c < 0x81 || c > 0xFE || c3 < 0x81 || c3 > 0xFE || c4 < 0x30 || c4 > 0x39) return 1
                    c -= 0x81
                    c2 -= 0x30
                    c3 -= 0x81
                    c4 -= 0x30
                    if (c < 4) {
                        // (U+0080 to U+FFFF)
                        val lseq = (c * 10 + c2) * 1260 + c3 * 10 + c4
                        if (lseq < 39420) {
                            val r = T.gb18030Ranges
                            var i = 0
                            while (lseq >= r[i + 3 + 2]) i += 3
                            d.out(r[i] - r[i + 2] + lseq)
                            d.pos += 4
                            continue
                        }
                    } else if (c >= 15) {
                        // (U+10000 to U+10FFFF)
                        val lseq = 0x10000 + ((c - 15) * 10 + c2) * 1260 + c3 * 10 + c4
                        if (lseq <= 0x10FFFF) {
                            d.out(lseq)
                            d.pos += 4
                            continue
                        }
                    }
                    return 1
                }
                var u = gbkDecode(c, c2)
                if (u < 0) u = T.gb18030extDec.dec(c, c2)
                if (u < 0) return 1
                d.out(u)
                d.pos += 2
            }
            return 0
        }
    }

    /** hz: the first byte of its state 1 while in GB mode. */
    val HZ = object : MbCodec("hz") {
        override fun encInit(st: ByteArray) {
            st[0] = 0
        }

        override fun encReset(st: ByteArray, e: Enc): Int {
            if (st[0].toInt() != 0) {
                if (e.left < 2) return Mb.TOOSMALL
                e.o(0, '~'.code)
                e.o(1, '}'.code)
                st[0] = 0
                e.outpos += 2
            }
            return 0
        }

        override fun encode(st: ByteArray, e: Enc, flags: Int): Int {
            while (e.inpos < e.inlen) {
                val c = e.inp[e.inpos]
                if (c < 0x80) {
                    if (st[0].toInt() != 0) {
                        if (e.left < 2) return Mb.TOOSMALL
                        e.o(0, '~'.code)
                        e.o(1, '}'.code)
                        e.outpos += 2
                        st[0] = 0
                    }
                    if (e.left < 1) return Mb.TOOSMALL
                    e.o(0, c)
                    e.next(1, 1)
                    if (c == '~'.code) {
                        if (e.left < 1) return Mb.TOOSMALL
                        e.o(0, '~'.code)
                        e.outpos++
                    }
                    continue
                }
                if (c > 0xFFFF) return 1
                val code = T.gbcommon.enc(c)
                if (code < 0) return 1
                if (code and 0x8000 != 0) return 1
                if (st[0].toInt() == 0) {
                    if (e.left < 4) return Mb.TOOSMALL
                    e.o(0, '~'.code)
                    e.o(1, '{'.code)
                    e.o(2, code shr 8)
                    e.o(3, code and 0xff)
                    e.next(1, 4)
                    st[0] = 1
                } else {
                    if (e.left < 2) return Mb.TOOSMALL
                    e.o(0, code shr 8)
                    e.o(1, code and 0xff)
                    e.next(1, 2)
                }
            }
            return 0
        }

        override fun decInit(st: ByteArray) {
            st[0] = 0
        }

        override fun decReset(st: ByteArray) {
            st[0] = 0
        }

        override fun decode(st: ByteArray, d: Dec): Int {
            while (d.left > 0) {
                val c = d.at(0)
                if (c == '~'.code) {
                    if (d.left < 2) return Mb.TOOFEW
                    val c2 = d.at(1)
                    val gb = st[0].toInt()
                    if (c2 == '~'.code && gb == 0) d.out('~'.code)
                    else if (c2 == '{'.code && gb == 0) st[0] = 1
                    else if (c2 == '\n'.code && gb == 0) Unit // (a line continued)
                    else if (c2 == '}'.code && gb == 1) st[0] = 0
                    else return 1
                    d.pos += 2
                    continue
                }
                if (c and 0x80 != 0) return 1
                if (st[0].toInt() == 0) {
                    d.out(c)
                    d.pos++
                } else {
                    if (d.left < 2) return Mb.TOOFEW
                    val u = T.gb2312.dec(c, d.at(1))
                    if (u < 0) return 1
                    d.out(u)
                    d.pos += 2
                }
            }
            return 0
        }
    }

    val CODECS = listOf(GB2312, GBK, GB18030, HZ)
    val MAPS = listOf("gb2312", "gbkext", "gbcommon", "gb18030ext")
}

// ------------------------------------------------------------------ _codecs_jp.c

/** Modules/cjkcodecs/_codecs_jp.c: shift_jis, cp932, euc_jp, shift_jis_2004, euc_jis_2004, euc_jisx0213, shift_jisx0213. */
internal object CjkJp {
    private val T = CjkTables
    private const val EMPBASE = 0x20000

    /** JIS X 0208's code (row and cell from 0x21) as Shift_JIS's two bytes. */
    private fun sjis(e: Enc, code: Int) {
        var c1 = code shr 8
        var c2 = code and 0xff
        c2 = ((if ((c1 - 0x21) and 1 != 0) 0x5e else 0) + (c2 - 0x21)) and 0xFF
        c1 = ((c1 - 0x21) shr 1) and 0xFF
        e.o(0, if (c1 < 0x1f) c1 + 0x81 else c1 + 0xc1)
        e.o(1, if (c2 < 0x3f) c2 + 0x40 else c2 + 0x41)
    }

    val CP932 = object : MbCodec("cp932") {
        override fun encode(st: ByteArray, e: Enc, flags: Int): Int {
            while (e.inpos < e.inlen) {
                val c = e.inp[e.inpos]
                if (c <= 0x80) {
                    if (e.left < 1) return Mb.TOOSMALL
                    e.o(0, c)
                    e.next(1, 1)
                    continue
                } else if (c in 0xff61..0xff9f) {
                    if (e.left < 1) return Mb.TOOSMALL
                    e.o(0, c - 0xfec0)
                    e.next(1, 1)
                    continue
                } else if (c in 0xf8f0..0xf8f3) {
                    // (Windows' compatibility)
                    if (e.left < 1) return Mb.TOOSMALL
                    e.o(0, if (c == 0xf8f0) 0xa0 else c - 0xf8f1 + 0xfd)
                    e.next(1, 1)
                    continue
                }
                if (c > 0xFFFF) return 1
                if (e.left < 2) return Mb.TOOSMALL
                var code = T.cp932extEnc.enc(c)
                if (code >= 0) {
                    e.o(0, code shr 8)
                    e.o(1, code and 0xff)
                } else {
                    code = T.jisxcommon.enc(c)
                    if (code >= 0) {
                        // (JIS X 0212's refused)
                        if (code and 0x8000 != 0) return 1
                        sjis(e, code)
                    } else if (c in 0xe000 until 0xe758) {
                        // (the user-defined area)
                        val c1 = (c - 0xe000) / 188
                        val c2 = (c - 0xe000) % 188
                        e.o(0, c1 + 0xf0)
                        e.o(1, if (c2 < 0x3f) c2 + 0x40 else c2 + 0x41)
                    } else {
                        return 1
                    }
                }
                e.next(1, 2)
            }
            return 0
        }

        override fun decode(st: ByteArray, d: Dec): Int {
            while (d.left > 0) {
                var c = d.at(0)
                if (c <= 0x80) {
                    d.out(c)
                    d.pos++
                    continue
                } else if (c in 0xa0..0xdf) {
                    d.out(if (c == 0xa0) 0xf8f0 else 0xfec0 + c)
                    d.pos++
                    continue
                } else if (c >= 0xfd) {
                    // (Windows' compatibility)
                    d.out(0xf8f1 - 0xfd + c)
                    d.pos++
                    continue
                }
                if (d.left < 2) return Mb.TOOFEW
                var c2 = d.at(1)
                val u = T.cp932extDec.dec(c, c2)
                if (u >= 0) {
                    d.out(u)
                } else if (c in 0x81..0x9f || c in 0xe0..0xea) {
                    if (c2 < 0x40 || (c2 in 0x7f..0x7f) || c2 > 0xfc) return 1
                    c = (if (c < 0xe0) c - 0x81 else c - 0xc1) and 0xFF
                    c2 = (if (c2 < 0x80) c2 - 0x40 else c2 - 0x41) and 0xFF
                    c = (2 * c + (if (c2 < 0x5e) 0 else 1) + 0x21) and 0xFF
                    c2 = ((if (c2 < 0x5e) c2 else c2 - 0x5e) + 0x21) and 0xFF
                    val v = T.jisx0208.dec(c, c2)
                    if (v < 0) return 1
                    d.out(v)
                } else if (c in 0xf0..0xf9) {
                    if (c2 in 0x40..0x7e || c2 in 0x80..0xfc) d.out(0xe000 + 188 * (c - 0xf0) + (if (c2 < 0x80) c2 - 0x40 else c2 - 0x41))
                    else return 1
                } else {
                    return 1
                }
                d.pos += 2
            }
            return 0
        }
    }

    /**
     * jisx0213_bmp's MULTIC: the code of [c] with the character after it where they are a pair (find_pairencmap;
     * that one a ucs2_t, its high bits dropped, as the C's), else alone; [size] gets 2 where the pair is taken. A code,
     * or -1 (illegal), or TOOFEW where the next character is still to come.
     */
    private fun pair(e: Enc, c: Int, flags: Int, size: IntArray): Int {
        val p = T.jisx0213PairEnc
        if (e.inlen - e.inpos < 2) {
            if (flags and Mb.FLUSH != 0) {
                val code = p.find(c, 0)
                return if (code == Mb.DBCINV) -1 else code
            }
            return Mb.TOOFEW
        }
        val c2 = e.inp[e.inpos + 1]
        var code = p.find(c, c2)
        if (code == Mb.DBCINV) {
            code = p.find(c, 0)
            if (code == Mb.DBCINV) return -1
        } else if (c2 != 0) {
            // (a NUL is not taken as a pair's)
            size[0] = 2
        }
        return code
    }

    /** euc_jis_2004, and euc_jisx0213 (its config 2000). */
    class EucJis2004(name: String, config: Int) : MbCodec(name, config) {
        override fun encode(st: ByteArray, e: Enc, flags: Int): Int {
            val size = IntArray(1)
            while (e.inpos < e.inlen) {
                val c = e.inp[e.inpos]
                var code: Int
                if (c < 0x80) {
                    if (e.left < 1) return Mb.TOOSMALL
                    e.o(0, c)
                    e.next(1, 1)
                    continue
                }
                size[0] = 1
                if (c <= 0xFFFF) {
                    if (Jisx0213Emu.bmpInvalid(config, c)) return 1
                    if (Jisx0213Emu.bmpCode(config, c)) {
                        code = 0x8000 or 0x7d3b
                    } else {
                        code = T.jisx0213Bmp.enc(c)
                        if (code >= 0) {
                            if (code == Mb.MULTIC) {
                                code = pair(e, c, flags, size)
                                if (code == Mb.TOOFEW) return Mb.TOOFEW
                                if (code < 0) return 1
                            }
                        } else {
                            code = T.jisxcommon.enc(c)
                            if (code < 0) {
                                if (c in 0xff61..0xff9f) {
                                    // (JIS X 0201's half-width katakana)
                                    if (e.left < 2) return Mb.TOOSMALL
                                    e.o(0, 0x8e)
                                    e.o(1, c - 0xfec0)
                                    e.next(1, 2)
                                    continue
                                } else if (c == 0xff3c) {
                                    // (FULLWIDTH REVERSE SOLIDUS)
                                    code = 0x2140
                                } else if (c == 0xff5e) {
                                    // (FULLWIDTH TILDE)
                                    code = 0x2232
                                } else {
                                    return 1
                                }
                            }
                        }
                    }
                } else if (c shr 16 == EMPBASE shr 16) {
                    if (Jisx0213Emu.empInvalid(config, c)) return 1
                    code = T.jisx0213Emp.enc(c and 0xffff)
                    if (code < 0) return size[0]
                } else {
                    return size[0]
                }
                if (code and 0x8000 != 0) {
                    // (codeset 2)
                    if (e.left < 3) return Mb.TOOSMALL
                    e.o(0, 0x8f)
                    e.o(1, code shr 8)
                    e.o(2, (code and 0xFF) or 0x80)
                    e.next(size[0], 3)
                } else {
                    // (codeset 1)
                    if (e.left < 2) return Mb.TOOSMALL
                    e.o(0, (code shr 8) or 0x80)
                    e.o(1, (code and 0xFF) or 0x80)
                    e.next(size[0], 2)
                }
            }
            return 0
        }

        override fun decode(st: ByteArray, d: Dec): Int {
            while (d.left > 0) {
                var c = d.at(0)
                if (c < 0x80) {
                    d.out(c)
                    d.pos++
                    continue
                }
                if (c == 0x8e) {
                    // (JIS X 0201's half-width katakana)
                    if (d.left < 2) return Mb.TOOFEW
                    val c2 = d.at(1)
                    if (c2 in 0xa1..0xdf) {
                        d.out(0xfec0 + c2)
                        d.pos += 2
                    } else {
                        return 1
                    }
                } else if (c == 0x8f) {
                    if (d.left < 3) return Mb.TOOFEW
                    val c2 = d.at(1) xor 0x80
                    val c3 = d.at(2) xor 0x80
                    // (JIS X 0213's plane 2, or JIS X 0212)
                    if (Jisx0213Emu.plane2(config, c2, c3)) {
                        d.out(0x9B1D)
                    } else {
                        var u = T.jisx0213_2Bmp.dec(c2, c3)
                        if (u >= 0) {
                            d.out(u)
                        } else {
                            u = T.jisx0213_2Emp.dec(c2, c3)
                            if (u >= 0) {
                                d.out(EMPBASE or u)
                                d.pos += 3
                                continue
                            }
                            u = T.jisx0212.dec(c2, c3)
                            if (u < 0) return 1
                            d.out(u)
                        }
                    }
                    d.pos += 3
                } else {
                    if (d.left < 2) return Mb.TOOFEW
                    c = c xor 0x80
                    val c2 = d.at(1) xor 0x80
                    // (JIS X 0213's plane 1)
                    if (Jisx0213Emu.plane1Invalid(config, c, c2)) return 2
                    if (c == 0x21 && c2 == 0x40) {
                        d.out(0xff3c)
                    } else if (c == 0x22 && c2 == 0x32) {
                        d.out(0xff5e)
                    } else {
                        var u = T.jisx0208.dec(c, c2)
                        if (u < 0) u = T.jisx0213_1Bmp.dec(c, c2)
                        if (u >= 0) {
                            d.out(u)
                        } else {
                            u = T.jisx0213_1Emp.dec(c, c2)
                            if (u >= 0) {
                                d.out(EMPBASE or u)
                                d.pos += 2
                                continue
                            }
                            u = T.jisx0213PairDec.dec(c, c2)
                            if (u >= 0) {
                                d.out(u ushr 16)
                                d.out(u and 0xffff)
                                d.pos += 2
                                continue
                            }
                            return 1
                        }
                    }
                    d.pos += 2
                }
            }
            return 0
        }
    }

    val EUC_JP = object : MbCodec("euc_jp") {
        override fun encode(st: ByteArray, e: Enc, flags: Int): Int {
            while (e.inpos < e.inlen) {
                val c = e.inp[e.inpos]
                if (c < 0x80) {
                    if (e.left < 1) return Mb.TOOSMALL
                    e.o(0, c)
                    e.next(1, 1)
                    continue
                }
                if (c > 0xFFFF) return 1
                var code = T.jisxcommon.enc(c)
                if (code < 0) {
                    if (c in 0xff61..0xff9f) {
                        // (JIS X 0201's half-width katakana)
                        if (e.left < 2) return Mb.TOOSMALL
                        e.o(0, 0x8e)
                        e.o(1, c - 0xfec0)
                        e.next(1, 2)
                        continue
                    } else if (c == 0xff3c) {
                        // (FULLWIDTH REVERSE SOLIDUS)
                        code = 0x2140
                    } else if (c == 0xa5) {
                        // (YEN SIGN)
                        if (e.left < 1) return Mb.TOOSMALL
                        e.o(0, 0x5c)
                        e.next(1, 1)
                        continue
                    } else if (c == 0x203e) {
                        // (OVERLINE)
                        if (e.left < 1) return Mb.TOOSMALL
                        e.o(0, 0x7e)
                        e.next(1, 1)
                        continue
                    } else {
                        return 1
                    }
                }
                if (code and 0x8000 != 0) {
                    // (JIS X 0212)
                    if (e.left < 3) return Mb.TOOSMALL
                    e.o(0, 0x8f)
                    e.o(1, code shr 8)
                    e.o(2, (code and 0xFF) or 0x80)
                    e.next(1, 3)
                } else {
                    // (JIS X 0208)
                    if (e.left < 2) return Mb.TOOSMALL
                    e.o(0, (code shr 8) or 0x80)
                    e.o(1, (code and 0xFF) or 0x80)
                    e.next(1, 2)
                }
            }
            return 0
        }

        override fun decode(st: ByteArray, d: Dec): Int {
            while (d.left > 0) {
                val c = d.at(0)
                if (c < 0x80) {
                    d.out(c)
                    d.pos++
                    continue
                }
                if (c == 0x8e) {
                    // (JIS X 0201's half-width katakana)
                    if (d.left < 2) return Mb.TOOFEW
                    val c2 = d.at(1)
                    if (c2 in 0xa1..0xdf) {
                        d.out(0xfec0 + c2)
                        d.pos += 2
                    } else {
                        return 1
                    }
                } else if (c == 0x8f) {
                    if (d.left < 3) return Mb.TOOFEW
                    // (JIS X 0212)
                    val u = T.jisx0212.dec(d.at(1) xor 0x80, d.at(2) xor 0x80)
                    if (u < 0) return 1
                    d.out(u)
                    d.pos += 3
                } else {
                    if (d.left < 2) return Mb.TOOFEW
                    val c2 = d.at(1)
                    // (JIS X 0208; FULLWIDTH REVERSE SOLIDUS first)
                    if (c == 0xa1 && c2 == 0xc0) {
                        d.out(0xff3c)
                    } else {
                        val u = T.jisx0208.dec(c xor 0x80, c2 xor 0x80)
                        if (u < 0) return 1
                        d.out(u)
                    }
                    d.pos += 2
                }
            }
            return 0
        }
    }

    val SHIFT_JIS = object : MbCodec("shift_jis") {
        override fun encode(st: ByteArray, e: Enc, flags: Int): Int {
            while (e.inpos < e.inlen) {
                val c = e.inp[e.inpos]
                var code = when {
                    c < 0x80 -> c
                    c == 0x00a5 -> 0x5c // (YEN SIGN)
                    c == 0x203e -> 0x7e // (OVERLINE)
                    c in 0xff61..0xff9f -> c - 0xfec0
                    c > 0xFFFF -> return 1
                    else -> Mb.NOCHAR
                }
                if (code < 0x80 || code in 0xa1..0xdf) {
                    if (e.left < 1) return Mb.TOOSMALL
                    e.o(0, code)
                    e.next(1, 1)
                    continue
                }
                if (e.left < 2) return Mb.TOOSMALL
                if (code == Mb.NOCHAR) {
                    code = T.jisxcommon.enc(c)
                    if (code < 0) {
                        // (FULLWIDTH REVERSE SOLIDUS)
                        if (c == 0xff3c) code = 0x2140 else return 1
                    }
                    // (JIS X 0212's refused)
                    if (code and 0x8000 != 0) return 1
                }
                sjis(e, code)
                e.next(1, 2)
            }
            return 0
        }

        override fun decode(st: ByteArray, d: Dec): Int {
            while (d.left > 0) {
                val c = d.at(0)
                if (c < 0x80) {
                    d.out(c)
                } else if (c in 0xa1..0xdf) {
                    d.out(0xfec0 + c)
                } else if (c in 0x81..0x9f || c in 0xe0..0xea) {
                    if (d.left < 2) return Mb.TOOFEW
                    var c2 = d.at(1)
                    if (c2 < 0x40 || c2 == 0x7f || c2 > 0xfc) return 1
                    var c1 = (if (c < 0xe0) c - 0x81 else c - 0xc1) and 0xFF
                    c2 = (if (c2 < 0x80) c2 - 0x40 else c2 - 0x41) and 0xFF
                    c1 = (2 * c1 + (if (c2 < 0x5e) 0 else 1) + 0x21) and 0xFF
                    c2 = ((if (c2 < 0x5e) c2 else c2 - 0x5e) + 0x21) and 0xFF
                    if (c1 == 0x21 && c2 == 0x40) {
                        // (FULLWIDTH REVERSE SOLIDUS)
                        d.out(0xff3c)
                        d.pos += 2
                        continue
                    }
                    val u = T.jisx0208.dec(c1, c2)
                    if (u < 0) return 1
                    d.out(u)
                    d.pos += 2
                    continue
                } else {
                    return 1
                }
                // (JIS X 0201)
                d.pos++
            }
            return 0
        }
    }

    /** shift_jis_2004, and shift_jisx0213 (its config 2000). */
    class ShiftJis2004(name: String, config: Int) : MbCodec(name, config) {
        override fun encode(st: ByteArray, e: Enc, flags: Int): Int {
            val size = IntArray(1)
            while (e.inpos < e.inlen) {
                val c = e.inp[e.inpos]
                // (JISX0201_ENCODE)
                var code = when {
                    c < 0x80 && c != 0x5c && c != 0x7e -> c
                    c == 0x00a5 -> 0x5c
                    c == 0x203e -> 0x7e
                    c in 0xff61..0xff9f -> c - 0xfec0
                    else -> Mb.NOCHAR
                }
                if (code < 0x80 || code in 0xa1..0xdf) {
                    if (e.left < 1) return Mb.TOOSMALL
                    e.o(0, code)
                    e.next(1, 1)
                    continue
                }
                if (e.left < 2) return Mb.TOOSMALL
                size[0] = 1
                if (code == Mb.NOCHAR) {
                    if (c <= 0xffff) {
                        if (Jisx0213Emu.bmpInvalid(config, c)) return 1
                        if (Jisx0213Emu.bmpCode(config, c)) {
                            code = 0x8000 or 0x7d3b
                        } else {
                            code = T.jisx0213Bmp.enc(c)
                            if (code >= 0) {
                                if (code == Mb.MULTIC) {
                                    code = pair(e, c, flags, size)
                                    if (code == Mb.TOOFEW) return Mb.TOOFEW
                                    if (code < 0) return 1
                                }
                            } else {
                                code = T.jisxcommon.enc(c)
                                if (code < 0) return 1
                                // (JIS X 0212's abandoned)
                                if (code and 0x8000 != 0) return 1
                            }
                        }
                    } else if (c shr 16 == EMPBASE shr 16) {
                        if (Jisx0213Emu.empInvalid(config, c)) return 1
                        code = T.jisx0213Emp.enc(c and 0xffff)
                        if (code < 0) return size[0]
                    } else {
                        return size[0]
                    }
                }
                var c1 = code shr 8
                var c2 = (code and 0xff) - 0x21
                if (c1 and 0x80 != 0) {
                    // (plane 2)
                    c1 -= if (c1 >= 0xee) 0x87 else if (c1 >= 0xac || c1 == 0xa8) 0x49 else 0x43
                } else {
                    // (plane 1)
                    c1 -= 0x21
                }
                if (c1 and 1 != 0) c2 += 0x5e
                c1 = c1 shr 1
                e.o(0, c1 + (if (c1 < 0x1f) 0x81 else 0xc1))
                e.o(1, c2 + (if (c2 < 0x3f) 0x40 else 0x41))
                e.next(size[0], 2)
            }
            return 0
        }

        override fun decode(st: ByteArray, d: Dec): Int {
            while (d.left > 0) {
                val c = d.at(0)
                // (JISX0201_DECODE)
                if (c < 0x5c) d.out(c)
                else if (c == 0x5c) d.out(0x00a5)
                else if (c < 0x7e) d.out(c)
                else if (c == 0x7e) d.out(0x203e)
                else if (c == 0x7f) d.out(0x7f)
                else if (c in 0xa1..0xdf) d.out(0xfec0 + c)
                else if (c in 0x81..0x9f || c in 0xe0..0xfc) {
                    if (d.left < 2) return Mb.TOOFEW
                    var c2 = d.at(1)
                    if (c2 < 0x40 || c2 == 0x7f || c2 > 0xfc) return 1
                    var c1 = (if (c < 0xe0) c - 0x81 else c - 0xc1) and 0xFF
                    c2 = (if (c2 < 0x80) c2 - 0x40 else c2 - 0x41) and 0xFF
                    c1 = (2 * c1 + (if (c2 < 0x5e) 0 else 1)) and 0xFF
                    c2 = ((if (c2 < 0x5e) c2 else c2 - 0x5e) + 0x21) and 0xFF
                    if (c1 < 0x5e) {
                        // (plane 1)
                        c1 += 0x21
                        if (Jisx0213Emu.plane1Invalid(config, c1, c2)) return 2
                        var u = T.jisx0208.dec(c1, c2)
                        if (u < 0) u = T.jisx0213_1Bmp.dec(c1, c2)
                        if (u >= 0) {
                            d.out(u)
                        } else {
                            u = T.jisx0213_1Emp.dec(c1, c2)
                            if (u >= 0) {
                                d.out(EMPBASE or u)
                            } else {
                                u = T.jisx0213PairDec.dec(c1, c2)
                                if (u < 0) return 1
                                d.out(u ushr 16)
                                d.out(u and 0xffff)
                            }
                        }
                        d.pos += 2
                    } else {
                        // (plane 2)
                        c1 = (if (c1 >= 0x67) c1 + 0x07 else if (c1 >= 0x63 || c1 == 0x5f) c1 - 0x37 else c1 - 0x3d) and 0xFF
                        if (Jisx0213Emu.plane2(config, c1, c2)) {
                            d.out(0x9B1D)
                        } else {
                            var u = T.jisx0213_2Bmp.dec(c1, c2)
                            if (u >= 0) {
                                d.out(u)
                            } else {
                                u = T.jisx0213_2Emp.dec(c1, c2)
                                if (u < 0) return 1
                                d.out(EMPBASE or u)
                                d.pos += 2
                                continue
                            }
                        }
                        d.pos += 2
                    }
                    continue
                } else {
                    return 1
                }
                // (JIS X 0201)
                d.pos++
            }
            return 0
        }
    }

    val SHIFT_JIS_2004 = ShiftJis2004("shift_jis_2004", 0)
    val EUC_JIS_2004 = EucJis2004("euc_jis_2004", 0)

    val CODECS = listOf(SHIFT_JIS, CP932, EUC_JP, SHIFT_JIS_2004, EUC_JIS_2004, EucJis2004("euc_jisx0213", 2000),
        ShiftJis2004("shift_jisx0213", 2000))
    val MAPS = listOf("jisx0208", "jisx0212", "jisxcommon", "jisx0213_1_bmp", "jisx0213_2_bmp", "jisx0213_bmp",
        "jisx0213_1_emp", "jisx0213_2_emp", "jisx0213_emp", "jisx0213_pair", "cp932ext")
}

// ------------------------------------------------------------------ _codecs_kr.c

/** Modules/cjkcodecs/_codecs_kr.c: euc_kr, cp949 and johab. */
internal object CjkKr {
    private val T = CjkTables
    private const val JAMO_FIRSTBYTE = 0xA4
    private const val JAMO_FILLER = 0xD4

    private val U2CGK_CHOSEONG = intArrayOf(0xa1, 0xa2, 0xa4, 0xa7, 0xa8, 0xa9, 0xb1, 0xb2, 0xb3, 0xb5, 0xb6, 0xb7, 0xb8,
        0xb9, 0xba, 0xbb, 0xbc, 0xbd, 0xbe)
    private val U2CGK_JUNGSEONG = intArrayOf(0xbf, 0xc0, 0xc1, 0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xcb,
        0xcc, 0xcd, 0xce, 0xcf, 0xd0, 0xd1, 0xd2, 0xd3)
    private val U2CGK_JONGSEONG = intArrayOf(0xd4, 0xa1, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7, 0xa9, 0xaa, 0xab, 0xac, 0xad,
        0xae, 0xaf, 0xb0, 0xb1, 0xb2, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xba, 0xbb, 0xbc, 0xbd, 0xbe)

    private const val NONE_KR = 127
    // (by the byte from 0xA1 to 0xBE)
    private val CGK2U_CHOSEONG = intArrayOf(0, 1, NONE_KR, 2, NONE_KR, NONE_KR, 3, 4, 5, NONE_KR, NONE_KR, NONE_KR, NONE_KR,
        NONE_KR, NONE_KR, NONE_KR, 6, 7, 8, NONE_KR, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18)
    private val CGK2U_JONGSEONG = intArrayOf(1, 2, 3, 4, 5, 6, 7, NONE_KR, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, NONE_KR,
        18, 19, 20, 21, 22, NONE_KR, 23, 24, 25, 26, 27)

    val EUC_KR = object : MbCodec("euc_kr") {
        override fun encode(st: ByteArray, e: Enc, flags: Int): Int {
            while (e.inpos < e.inlen) {
                var c = e.inp[e.inpos]
                if (c < 0x80) {
                    if (e.left < 1) return Mb.TOOSMALL
                    e.o(0, c)
                    e.next(1, 1)
                    continue
                }
                if (c > 0xFFFF) return 1
                if (e.left < 2) return Mb.TOOSMALL
                val code = T.cp949.enc(c)
                if (code < 0) return 1
                if (code and 0x8000 == 0) {
                    // (a character of KS X 1001)
                    e.o(0, (code shr 8) or 0x80)
                    e.o(1, (code and 0xFF) or 0x80)
                    e.next(1, 2)
                } else {
                    // (in CP949's extension: KS X 1001:1998's make-up sequence, EUC-KR's, a syllable of the Hangul ones)
                    if (e.left < 8) return Mb.TOOSMALL
                    e.o(0, JAMO_FIRSTBYTE)
                    e.o(1, JAMO_FILLER)
                    c -= 0xac00
                    e.o(2, JAMO_FIRSTBYTE)
                    e.o(3, U2CGK_CHOSEONG[c / 588])
                    e.outpos += 4
                    e.o(0, JAMO_FIRSTBYTE)
                    e.o(1, U2CGK_JUNGSEONG[(c / 28) % 21])
                    e.o(2, JAMO_FIRSTBYTE)
                    e.o(3, U2CGK_JONGSEONG[c % 28])
                    e.next(1, 4)
                }
            }
            return 0
        }

        override fun decode(st: ByteArray, d: Dec): Int {
            while (d.left > 0) {
                var c = d.at(0)
                if (c < 0x80) {
                    d.out(c)
                    d.pos++
                    continue
                }
                if (d.left < 2) return Mb.TOOFEW
                if (c == JAMO_FIRSTBYTE && d.at(1) == JAMO_FILLER) {
                    // (KS X 1001:1998's make-up sequence)
                    if (d.left < 8) return Mb.TOOFEW
                    if (d.at(2) != JAMO_FIRSTBYTE || d.at(4) != JAMO_FIRSTBYTE || d.at(6) != JAMO_FIRSTBYTE) return 1
                    c = d.at(3)
                    val cho = if (c in 0xa1..0xbe) CGK2U_CHOSEONG[c - 0xa1] else NONE_KR
                    c = d.at(5)
                    val jung = if (c in 0xbf..0xd3) c - 0xbf else NONE_KR
                    c = d.at(7)
                    val jong = if (c == JAMO_FILLER) 0 else if (c in 0xa1..0xbe) CGK2U_JONGSEONG[c - 0xa1] else NONE_KR
                    if (cho == NONE_KR || jung == NONE_KR || jong == NONE_KR) return 1
                    d.out(0xac00 + cho * 588 + jung * 28 + jong)
                    d.pos += 8
                } else {
                    val u = T.ksx1001.dec(c xor 0x80, d.at(1) xor 0x80)
                    if (u < 0) return 1
                    d.out(u)
                    d.pos += 2
                }
            }
            return 0
        }
    }

    val CP949 = object : MbCodec("cp949") {
        override fun encode(st: ByteArray, e: Enc, flags: Int): Int {
            while (e.inpos < e.inlen) {
                val c = e.inp[e.inpos]
                if (c < 0x80) {
                    if (e.left < 1) return Mb.TOOSMALL
                    e.o(0, c)
                    e.next(1, 1)
                    continue
                }
                if (c > 0xFFFF) return 1
                if (e.left < 2) return Mb.TOOSMALL
                val code = T.cp949.enc(c)
                if (code < 0) return 1
                e.o(0, (code shr 8) or 0x80)
                // (MSB set: CP949's; else KS X 1001's)
                e.o(1, if (code and 0x8000 != 0) code and 0xFF else (code and 0xFF) or 0x80)
                e.next(1, 2)
            }
            return 0
        }

        override fun decode(st: ByteArray, d: Dec): Int {
            while (d.left > 0) {
                val c = d.at(0)
                if (c < 0x80) {
                    d.out(c)
                    d.pos++
                    continue
                }
                if (d.left < 2) return Mb.TOOFEW
                var u = T.ksx1001.dec(c xor 0x80, d.at(1) xor 0x80)
                if (u < 0) u = T.cp949ext.dec(c, d.at(1))
                if (u < 0) return 1
                d.out(u)
                d.pos += 2
            }
            return 0
        }
    }

    private val U2JOHABIDX_CHOSEONG = IntArray(32).also {
        intArrayOf(0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11, 0x12,
            0x13, 0x14).copyInto(it)
    }
    private val U2JOHABIDX_JUNGSEONG = IntArray(32).also {
        intArrayOf(0x03, 0x04, 0x05, 0x06, 0x07, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
            0x1a, 0x1b, 0x1c, 0x1d).copyInto(it)
    }
    private val U2JOHABIDX_JONGSEONG = IntArray(32).also {
        intArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11,
            0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d).copyInto(it)
    }
    private val U2JOHABJAMO = intArrayOf(0x8841, 0x8c41, 0x8444, 0x9041, 0x8446, 0x8447, 0x9441, 0x9841, 0x9c41, 0x844a,
        0x844b, 0x844c, 0x844d, 0x844e, 0x844f, 0x8450, 0xa041, 0xa441, 0xa841, 0x8454, 0xac41, 0xb041, 0xb441, 0xb841,
        0xbc41, 0xc041, 0xc441, 0xc841, 0xcc41, 0xd041, 0x8461, 0x8481, 0x84a1, 0x84c1, 0x84e1, 0x8541, 0x8561, 0x8581,
        0x85a1, 0x85c1, 0x85e1, 0x8641, 0x8661, 0x8681, 0x86a1, 0x86c1, 0x86e1, 0x8741, 0x8761, 0x8781, 0x87a1)

    private const val FILL = 0xfd
    private const val NONE = 0xff

    private val JOHABIDX_CHOSEONG = intArrayOf(NONE, FILL, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a,
        0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11, 0x12, NONE, NONE, NONE, NONE, NONE, NONE, NONE, NONE, NONE, NONE, NONE)
    private val JOHABIDX_JUNGSEONG = intArrayOf(NONE, NONE, FILL, 0x00, 0x01, 0x02, 0x03, 0x04, NONE, NONE, 0x05, 0x06,
        0x07, 0x08, 0x09, 0x0a, NONE, NONE, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, NONE, NONE, 0x11, 0x12, 0x13, 0x14, NONE, NONE)
    private val JOHABIDX_JONGSEONG = intArrayOf(NONE, FILL, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a,
        0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, NONE, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x1b, NONE, NONE)
    private val JOHABJAMO_CHOSEONG = intArrayOf(NONE, FILL, 0x31, 0x32, 0x34, 0x37, 0x38, 0x39, 0x41, 0x42, 0x43, 0x45,
        0x46, 0x47, 0x48, 0x49, 0x4a, 0x4b, 0x4c, 0x4d, 0x4e, NONE, NONE, NONE, NONE, NONE, NONE, NONE, NONE, NONE, NONE, NONE)
    private val JOHABJAMO_JUNGSEONG = intArrayOf(NONE, NONE, FILL, 0x4f, 0x50, 0x51, 0x52, 0x53, NONE, NONE, 0x54, 0x55,
        0x56, 0x57, 0x58, 0x59, NONE, NONE, 0x5a, 0x5b, 0x5c, 0x5d, 0x5e, 0x5f, NONE, NONE, 0x60, 0x61, 0x62, 0x63, NONE, NONE)
    private val JOHABJAMO_JONGSEONG = intArrayOf(NONE, FILL, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x39, 0x3a, 0x3b,
        0x3c, 0x3d, 0x3e, 0x3f, 0x40, 0x41, NONE, 0x42, 0x44, 0x45, 0x46, 0x47, 0x48, 0x4a, 0x4b, 0x4c, 0x4d, 0x4e, NONE, NONE)

    val JOHAB = object : MbCodec("johab") {
        override fun encode(st: ByteArray, e: Enc, flags: Int): Int {
            while (e.inpos < e.inlen) {
                var c = e.inp[e.inpos]
                val code: Int
                if (c < 0x80) {
                    if (e.left < 1) return Mb.TOOSMALL
                    e.o(0, c)
                    e.next(1, 1)
                    continue
                }
                if (c > 0xFFFF) return 1
                if (e.left < 2) return Mb.TOOSMALL
                if (c in 0xac00..0xd7a3) {
                    c -= 0xac00
                    code = 0x8000 or (U2JOHABIDX_CHOSEONG[c / 588] shl 10) or (U2JOHABIDX_JUNGSEONG[(c / 28) % 21] shl 5) or
                        U2JOHABIDX_JONGSEONG[c % 28]
                } else if (c in 0x3131..0x3163) {
                    code = U2JOHABJAMO[c - 0x3131]
                } else {
                    val k = T.cp949.enc(c)
                    if (k < 0) return 1
                    val c1 = (k shr 8) and 0xFF
                    val c2 = k and 0xff
                    if ((c1 in 0x21..0x2c || c1 in 0x4a..0x7d) && c2 in 0x21..0x7e) {
                        val t1 = (if (c1 < 0x4a) c1 - 0x21 + 0x1b2 else c1 - 0x21 + 0x197) and 0xFFFF
                        val t2 = ((if (t1 and 1 != 0) 0x5e else 0) + (c2 - 0x21)) and 0xFF
                        e.o(0, t1 shr 1)
                        e.o(1, if (t2 < 0x4e) t2 + 0x31 else t2 + 0x43)
                        e.next(1, 2)
                        continue
                    }
                    return 1
                }
                e.o(0, code shr 8)
                e.o(1, code and 0xff)
                e.next(1, 2)
            }
            return 0
        }

        override fun decode(st: ByteArray, d: Dec): Int {
            while (d.left > 0) {
                val c = d.at(0)
                if (c < 0x80) {
                    d.out(c)
                    d.pos++
                    continue
                }
                if (d.left < 2) return Mb.TOOFEW
                val c2 = d.at(1)
                if (c < 0xd8) {
                    // (Johab's Hangul)
                    val cCho = (c shr 2) and 0x1f
                    val cJung = ((c shl 3) or (c2 shr 5)) and 0x1f
                    val cJong = c2 and 0x1f
                    val iCho = JOHABIDX_CHOSEONG[cCho]
                    val iJung = JOHABIDX_JUNGSEONG[cJung]
                    val iJong = JOHABIDX_JONGSEONG[cJong]
                    if (iCho == NONE || iJung == NONE || iJong == NONE) return 1
                    // (U+1100's jamo not used)
                    if (iCho == FILL) {
                        if (iJung == FILL) {
                            d.out(if (iJong == FILL) 0x3000 else 0x3100 or JOHABJAMO_JONGSEONG[cJong])
                        } else {
                            if (iJong == FILL) d.out(0x3100 or JOHABJAMO_JUNGSEONG[cJung]) else return 1
                        }
                    } else {
                        if (iJung == FILL) {
                            if (iJong == FILL) d.out(0x3100 or JOHABJAMO_CHOSEONG[cCho]) else return 1
                        } else {
                            d.out(0xac00 + iCho * 588 + iJung * 28 + (if (iJong == FILL) 0 else iJong))
                        }
                    }
                    d.pos += 2
                } else {
                    // (KS X 1001 but its Hangul jamo and syllables)
                    if (c == 0xdf || c > 0xf9 || c2 < 0x31 || (c2 in 0x80 until 0x91) || (c2 and 0x7f) == 0x7f ||
                        (c == 0xda && c2 in 0xa1..0xd3)) return 1
                    var t1 = (if (c < 0xe0) 2 * (c - 0xd9) else 2 * c - 0x197) and 0xFF
                    var t2 = (if (c2 < 0x91) c2 - 0x31 else c2 - 0x43) and 0xFF
                    t1 = (t1 + (if (t2 < 0x5e) 0 else 1) + 0x21) and 0xFF
                    t2 = ((if (t2 < 0x5e) t2 else t2 - 0x5e) + 0x21) and 0xFF
                    val u = T.ksx1001.dec(t1, t2)
                    if (u < 0) return 1
                    d.out(u)
                    d.pos += 2
                }
            }
            return 0
        }
    }

    val CODECS = listOf(EUC_KR, CP949, JOHAB)
    val MAPS = listOf("ksx1001", "cp949", "cp949ext")
}

// ------------------------------------------------------------------ _codecs_tw.c

/** Modules/cjkcodecs/_codecs_tw.c: big5 and cp950. */
internal object CjkTw {
    private val T = CjkTables

    val BIG5 = object : MbCodec("big5") {
        override fun encode(st: ByteArray, e: Enc, flags: Int): Int {
            while (e.inpos < e.inlen) {
                val c = e.inp[e.inpos]
                if (c < 0x80) {
                    if (e.left < 1) return Mb.TOOSMALL
                    e.o(0, c)
                    e.next(1, 1)
                    continue
                }
                if (c > 0xFFFF) return 1
                if (e.left < 2) return Mb.TOOSMALL
                val code = T.big5Enc.enc(c)
                if (code < 0) return 1
                e.o(0, code shr 8)
                e.o(1, code and 0xFF)
                e.next(1, 2)
            }
            return 0
        }

        override fun decode(st: ByteArray, d: Dec): Int {
            while (d.left > 0) {
                val c = d.at(0)
                if (c < 0x80) {
                    d.out(c)
                    d.pos++
                    continue
                }
                if (d.left < 2) return Mb.TOOFEW
                val u = T.big5Dec.dec(c, d.at(1))
                if (u < 0) return 1
                d.out(u)
                d.pos += 2
            }
            return 0
        }
    }

    val CP950 = object : MbCodec("cp950") {
        override fun encode(st: ByteArray, e: Enc, flags: Int): Int {
            while (e.inpos < e.inlen) {
                val c = e.inp[e.inpos]
                if (c < 0x80) {
                    if (e.left < 1) return Mb.TOOSMALL
                    e.o(0, c)
                    e.next(1, 1)
                    continue
                }
                if (c > 0xFFFF) return 1
                if (e.left < 2) return Mb.TOOSMALL
                var code = T.cp950extEnc.enc(c)
                if (code < 0) code = T.big5Enc.enc(c)
                if (code < 0) return 1
                e.o(0, code shr 8)
                e.o(1, code and 0xFF)
                e.next(1, 2)
            }
            return 0
        }

        override fun decode(st: ByteArray, d: Dec): Int {
            while (d.left > 0) {
                val c = d.at(0)
                if (c < 0x80) {
                    d.out(c)
                    d.pos++
                    continue
                }
                if (d.left < 2) return Mb.TOOFEW
                var u = T.cp950extDec.dec(c, d.at(1))
                if (u < 0) u = T.big5Dec.dec(c, d.at(1))
                if (u < 0) return 1
                d.out(u)
                d.pos += 2
            }
            return 0
        }
    }

    val CODECS = listOf(BIG5, CP950)
    val MAPS = listOf("big5", "cp950ext")
}

// ------------------------------------------------------------------ _codecs_hk.c

/** Modules/cjkcodecs/_codecs_hk.c: big5hkscs (over big5's maps, which it imports from _codecs_tw). */
internal object CjkHk {
    private val T = CjkTables
    /** The four pairs HKSCS 2004 maps (U+00CA or U+00EA, then U+0304 or U+030C): hand-written, as CPython's. */
    private val PAIRENC = intArrayOf(0x8862, 0x8864, 0x88a3, 0x88a5)

    val BIG5HKSCS = object : MbCodec("big5hkscs") {
        override fun init(ctx: PyContext) {
            PyMultibytecodec.importMap(ctx, "tw", "big5")
        }

        override fun encode(st: ByteArray, e: Enc, flags: Int): Int {
            while (e.inpos < e.inlen) {
                val c = e.inp[e.inpos]
                var code: Int
                if (c < 0x80) {
                    if (e.left < 1) return Mb.TOOSMALL
                    e.o(0, c)
                    e.next(1, 1)
                    continue
                }
                var insize = 1
                if (e.left < 2) return Mb.TOOSMALL
                if (c < 0x10000) {
                    code = T.big5hkscsBmp.enc(c)
                    if (code >= 0) {
                        if (code == Mb.MULTIC) {
                            val two = e.inlen - e.inpos >= 2
                            val c2 = if (two) e.inp[e.inpos + 1] else 0
                            if (two && (c and 0xffdf) == 0x00ca && (c2 and 0xfff7) == 0x0304) {
                                code = PAIRENC[((c shr 4) or (c2 shr 3)) and 3]
                                insize = 2
                            } else if (!two && flags and Mb.FLUSH == 0) {
                                return Mb.TOOFEW
                            } else {
                                code = if (c == 0xca) 0x8866 else 0x88a7
                            }
                        }
                    } else {
                        code = T.big5Enc.enc(c)
                        if (code < 0) return 1
                    }
                } else if (c < 0x20000) {
                    return insize
                } else if (c < 0x30000) {
                    code = T.big5hkscsNonBmp.enc(c and 0xffff)
                    if (code < 0) return insize
                } else {
                    return insize
                }
                e.o(0, code shr 8)
                e.o(1, code and 0xFF)
                e.next(insize, 2)
            }
            return 0
        }

        override fun decode(st: ByteArray, d: Dec): Int {
            while (d.left > 0) {
                val c = d.at(0)
                if (c < 0x80) {
                    d.out(c)
                    d.pos++
                    continue
                }
                if (d.left < 2) return Mb.TOOFEW
                val c2 = d.at(1)
                if (0xc6 > c || c > 0xc8 || (c < 0xc7 && c2 < 0xa1)) {
                    val u = T.big5Dec.dec(c, c2)
                    if (u >= 0) {
                        d.out(u)
                        d.pos += 2
                        continue
                    }
                }
                val u = T.big5hkscs.dec(c, c2)
                if (u >= 0) {
                    // (its plane-2 hint: past the BMP or not)
                    val h = T.hkscsHint(c, c2)
                    if (h < 0) return Mb.INTERNAL
                    d.out(if (h == 1) u or 0x20000 else u)
                    d.pos += 2
                    continue
                }
                when ((c shl 8) or c2) {
                    0x8862 -> { d.out(0x00ca); d.out(0x0304) }
                    0x8864 -> { d.out(0x00ca); d.out(0x030c) }
                    0x88a3 -> { d.out(0x00ea); d.out(0x0304) }
                    0x88a5 -> { d.out(0x00ea); d.out(0x030c) }
                    else -> return 1
                }
                // (each above a pair)
                d.pos += 2
            }
            return 0
        }
    }

    val CODECS = listOf(BIG5HKSCS)
    val MAPS = listOf("big5hkscs", "big5hkscs_bmp", "big5hkscs_nonbmp")
}
