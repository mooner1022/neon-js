package dev.mooner.neonpython

/**
 * Modules/cjkcodecs/_codecs_iso2022.c: iso2022_kr, iso2022_jp, iso2022_jp_1, iso2022_jp_2, iso2022_jp_2004,
 * iso2022_jp_3 and iso2022_jp_ext, each its designations (the character sets it may switch G0 or G1 to, by escape
 * sequences) over the maps of _codecs_kr, _codecs_jp and _codecs_cn, which it imports. The state: G0 to G3's
 * character sets in its first four bytes (a double-byte one's with 0x80), the shift and ESC-throughout flags in the fifth.
 */
internal object CjkIso2022 {
    private val T = CjkTables

    private const val ESC = 0x1B
    private const val SO = 0x0E
    private const val SI = 0x0F
    private const val LF = 0x0A
    private const val MAX_ESCSEQLEN = 16

    private const val CHARSET_ISO8859_1 = 'A'.code
    private const val CHARSET_ASCII = 'B'.code
    private const val CHARSET_ISO8859_7 = 'F'.code
    private const val CHARSET_JISX0201_K = 'I'.code
    private const val CHARSET_JISX0201_R = 'J'.code
    private const val CHARSET_DBCS = 0x80
    private const val CHARSET_GB2312 = 'A'.code or CHARSET_DBCS
    private const val CHARSET_JISX0208 = 'B'.code or CHARSET_DBCS
    private const val CHARSET_KSX1001 = 'C'.code or CHARSET_DBCS
    private const val CHARSET_JISX0212 = 'D'.code or CHARSET_DBCS
    private const val CHARSET_JISX0213_2000_1 = 'O'.code or CHARSET_DBCS
    private const val CHARSET_JISX0213_2 = 'P'.code or CHARSET_DBCS
    private const val CHARSET_JISX0213_2004_1 = 'Q'.code or CHARSET_DBCS
    private const val CHARSET_JISX0208_O = '@'.code or CHARSET_DBCS

    private const val MAP_UNMAPPABLE = 0xFFFF
    private const val MAP_MULTIPLE_AVAIL = 0xFFFE

    private const val F_SHIFTED = 0x01
    private const val F_ESCTHROUGHOUT = 0x02

    private const val NO_SHIFT = 0x01
    private const val USE_G2 = 0x02
    private const val USE_JISX0208_EXT = 0x04

    private fun isEscEnd(c: Int) = c in 'A'.code..'Z'.code || c == '@'.code
    private fun isIso2022Esc(c2: Int) = c2 == '('.code || c2 == ')'.code || c2 == '$'.code || c2 == '.'.code || c2 == '&'.code

    private fun g(st: ByteArray, i: Int) = st[i].toInt() and 0xFF
    private fun flag(st: ByteArray, f: Int) = g(st, 4) and f != 0
    private fun setFlag(st: ByteArray, f: Int) {
        st[4] = (g(st, 4) or f).toByte()
    }
    private fun clearFlag(st: ByteArray, f: Int) {
        st[4] = (g(st, 4) and f.inv()).toByte()
    }

    /**
     * A designation (iso2022_designation): its character set's mark, the plane (G0, G1, G2) it goes to, its width, the
     * maps it imports ([init]: (module, map)), its decoder of [width] bytes and its encoder, which is given the
     * character (and the next, [length] 2, where it said MAP_MULTIPLE_AVAIL; -1 where none is to come) and may say how
     * many it took in [length].
     */
    private class Designation(@JvmField val mark: Int, @JvmField val plane: Int, @JvmField val width: Int, @JvmField val init: List<Pair<String, String>>,
                              @JvmField val decoder: (ByteArray, Int) -> Int, @JvmField val encoder: (IntArray, IntArray) -> Int)

    // ------------------------------------------------------------------ the maps' access functions

    private val KSX1001_INIT = listOf("kr" to "cp949", "kr" to "ksx1001")
    private val JISX0208_INIT = listOf("jp" to "jisxcommon", "jp" to "jisx0208")
    private val JISX0212_INIT = listOf("jp" to "jisxcommon", "jp" to "jisx0212")
    private val JISX0213_INIT = JISX0208_INIT + listOf("jp" to "jisx0213_bmp", "jp" to "jisx0213_1_bmp", "jp" to "jisx0213_2_bmp",
        "jp" to "jisx0213_emp", "jp" to "jisx0213_1_emp", "jp" to "jisx0213_2_emp", "jp" to "jisx0213_pair")
    private val GB2312_INIT = listOf("cn" to "gbcommon", "cn" to "gb2312")

    private fun b(d: ByteArray, p: Int, i: Int) = d[p + i].toInt() and 0xFF

    private fun ksx1001Decoder(d: ByteArray, p: Int): Int = T.ksx1001.dec(b(d, p, 0), b(d, p, 1)).let { if (it >= 0) it else MAP_UNMAPPABLE }

    private fun ksx1001Encoder(data: IntArray, length: IntArray): Int {
        if (data[0] < 0x10000) {
            val coded = T.cp949.enc(data[0])
            if (coded >= 0 && coded and 0x8000 == 0) return coded
        }
        return MAP_UNMAPPABLE
    }

    private fun jisx0208Decoder(d: ByteArray, p: Int): Int {
        // (FULLWIDTH REVERSE SOLIDUS)
        if (b(d, p, 0) == 0x21 && b(d, p, 1) == 0x40) return 0xff3c
        return T.jisx0208.dec(b(d, p, 0), b(d, p, 1)).let { if (it >= 0) it else MAP_UNMAPPABLE }
    }

    private fun jisx0208Encoder(data: IntArray, length: IntArray): Int {
        if (data[0] < 0x10000) {
            if (data[0] == 0xff3c) return 0x2140
            val coded = T.jisxcommon.enc(data[0])
            if (coded >= 0 && coded and 0x8000 == 0) return coded
        }
        return MAP_UNMAPPABLE
    }

    private fun jisx0212Decoder(d: ByteArray, p: Int): Int = T.jisx0212.dec(b(d, p, 0), b(d, p, 1)).let { if (it >= 0) it else MAP_UNMAPPABLE }

    private fun jisx0212Encoder(data: IntArray, length: IntArray): Int {
        if (data[0] < 0x10000) {
            val coded = T.jisxcommon.enc(data[0])
            if (coded >= 0 && coded and 0x8000 != 0) return coded and 0x7fff
        }
        return MAP_UNMAPPABLE
    }

    /** jisx0213_2000_1_decoder and jisx0213_2004_1_decoder ([config] 2000 or 0). */
    private fun jisx0213Plane1Decoder(config: Int, d: ByteArray, p: Int): Int {
        val c1 = b(d, p, 0)
        val c2 = b(d, p, 1)
        if (Jisx0213Emu.plane1Invalid(config, c1, c2)) return MAP_UNMAPPABLE
        // (FULLWIDTH REVERSE SOLIDUS)
        if (c1 == 0x21 && c2 == 0x40) return 0xff3c
        var u = T.jisx0208.dec(c1, c2)
        if (u >= 0) return u
        u = T.jisx0213_1Bmp.dec(c1, c2)
        if (u >= 0) return u
        u = T.jisx0213_1Emp.dec(c1, c2)
        if (u >= 0) return u or 0x20000
        u = T.jisx0213PairDec.dec(c1, c2)
        return if (u >= 0) u else MAP_UNMAPPABLE
    }

    /**
     * jisx0213_2000_2_decoder and jisx0213_2004_2_decoder: the same, for the former's emulation of U+9B1D is not
     * followed by an else, the lookups after it giving the result all the same (as CPython's).
     */
    private fun jisx0213Plane2Decoder(d: ByteArray, p: Int): Int {
        val c1 = b(d, p, 0)
        val c2 = b(d, p, 1)
        var u = T.jisx0213_2Bmp.dec(c1, c2)
        if (u >= 0) return u
        u = T.jisx0213_2Emp.dec(c1, c2)
        return if (u >= 0) u or 0x20000 else MAP_UNMAPPABLE
    }

    /** jisx0213_encoder: [config] 2000 for JIS X 0213:2000's (emu_jisx0213_2000.h), else 0. */
    private fun jisx0213Encoder(data: IntArray, length: IntArray, config: Int): Int {
        when (length[0]) {
            1 -> {
                // (the first character)
                val c = data[0]
                if (c >= 0x10000) {
                    if (c shr 16 == 0x20000 shr 16) {
                        if (Jisx0213Emu.empInvalid(config, c)) return MAP_UNMAPPABLE
                        val coded = T.jisx0213Emp.enc(c and 0xffff)
                        if (coded >= 0) return coded
                    }
                    return MAP_UNMAPPABLE
                }
                if (Jisx0213Emu.bmpInvalid(config, c)) return MAP_UNMAPPABLE
                if (Jisx0213Emu.bmpCode(config, c)) return 0x8000 or 0x7d3b
                var coded = T.jisx0213Bmp.enc(c)
                if (coded >= 0) {
                    if (coded == Mb.MULTIC) return MAP_MULTIPLE_AVAIL
                } else {
                    coded = T.jisxcommon.enc(c)
                    if (coded < 0 || coded and 0x8000 != 0) return MAP_UNMAPPABLE
                }
                return coded
            }
            2, -1 -> {
                // (the second character of a pair; a NUL not taken as one; then, or where none is to come, alone)
                if (length[0] == 2 && data[1] != 0) {
                    val coded = T.jisx0213PairEnc.find(data[0], data[1])
                    if (coded != Mb.DBCINV) return coded
                }
                length[0] = 1
                val coded = T.jisx0213PairEnc.find(data[0], 0)
                return if (coded == Mb.DBCINV) MAP_UNMAPPABLE else coded
            }
            else -> return MAP_UNMAPPABLE
        }
    }

    /** jisx0213_2000_1_encoder and jisx0213_2004_1_encoder: plane 1's. */
    private fun jisx0213Plane1Encoder(data: IntArray, length: IntArray, config: Int): Int {
        val coded = jisx0213Encoder(data, length, config)
        return if (coded == MAP_UNMAPPABLE || coded == MAP_MULTIPLE_AVAIL) coded else if (coded and 0x8000 != 0) MAP_UNMAPPABLE else coded
    }

    /** The _paironly encoders: plane 1's pairs only. */
    private fun jisx0213PairOnlyEncoder(data: IntArray, length: IntArray, config: Int): Int {
        val ilength = length[0]
        val coded = jisx0213Encoder(data, length, config)
        return when (ilength) {
            1 -> if (coded == MAP_MULTIPLE_AVAIL) MAP_MULTIPLE_AVAIL else MAP_UNMAPPABLE
            2 -> if (length[0] != 2) MAP_UNMAPPABLE else coded
            else -> MAP_UNMAPPABLE
        }
    }

    /** jisx0213_2000_2_encoder and jisx0213_2004_2_encoder: plane 2's. */
    private fun jisx0213Plane2Encoder(data: IntArray, length: IntArray, config: Int): Int {
        val coded = jisx0213Encoder(data, length, config)
        return if (coded == MAP_UNMAPPABLE || coded == MAP_MULTIPLE_AVAIL) coded else if (coded and 0x8000 != 0) coded and 0x7fff else MAP_UNMAPPABLE
    }

    private fun jisx0201RDecoder(d: ByteArray, p: Int): Int {
        val c = b(d, p, 0)
        return when {
            c < 0x5c -> c
            c == 0x5c -> 0x00a5
            c < 0x7e -> c
            c == 0x7e -> 0x203e
            c == 0x7f -> 0x7f
            else -> MAP_UNMAPPABLE
        }
    }

    private fun jisx0201REncoder(data: IntArray, length: IntArray): Int {
        val c = data[0]
        return when {
            c < 0x80 && c != 0x5c && c != 0x7e -> c
            c == 0x00a5 -> 0x5c
            c == 0x203e -> 0x7e
            else -> MAP_UNMAPPABLE
        }
    }

    private fun jisx0201KDecoder(d: ByteArray, p: Int): Int {
        val c = b(d, p, 0) xor 0x80
        return if (c in 0xa1..0xdf) 0xfec0 + c else MAP_UNMAPPABLE
    }

    private fun jisx0201KEncoder(data: IntArray, length: IntArray): Int {
        val c = data[0]
        return if (c in 0xff61..0xff9f) c - 0xfec0 - 0x80 else MAP_UNMAPPABLE
    }

    private fun gb2312Decoder(d: ByteArray, p: Int): Int = T.gb2312.dec(b(d, p, 0), b(d, p, 1)).let { if (it >= 0) it else MAP_UNMAPPABLE }

    private fun gb2312Encoder(data: IntArray, length: IntArray): Int {
        if (data[0] < 0x10000) {
            val coded = T.gbcommon.enc(data[0])
            if (coded >= 0 && coded and 0x8000 == 0) return coded
        }
        return MAP_UNMAPPABLE
    }

    private val DUMMY_DECODER: (ByteArray, Int) -> Int = { _, _ -> MAP_UNMAPPABLE }
    private val DUMMY_ENCODER: (IntArray, IntArray) -> Int = { _, _ -> MAP_UNMAPPABLE }

    // ------------------------------------------------------------------ the registry

    private val KSX1001_G0 = Designation(CHARSET_KSX1001, 0, 2, KSX1001_INIT, ::ksx1001Decoder, ::ksx1001Encoder)
    private val KSX1001_G1 = Designation(CHARSET_KSX1001, 1, 2, KSX1001_INIT, ::ksx1001Decoder, ::ksx1001Encoder)
    private val JISX0201_R = Designation(CHARSET_JISX0201_R, 0, 1, emptyList(), ::jisx0201RDecoder, ::jisx0201REncoder)
    private val JISX0201_K = Designation(CHARSET_JISX0201_K, 0, 1, emptyList(), ::jisx0201KDecoder, ::jisx0201KEncoder)
    private val JISX0208 = Designation(CHARSET_JISX0208, 0, 2, JISX0208_INIT, ::jisx0208Decoder, ::jisx0208Encoder)
    private val JISX0208_O = Designation(CHARSET_JISX0208_O, 0, 2, JISX0208_INIT, ::jisx0208Decoder, ::jisx0208Encoder)
    private val JISX0212 = Designation(CHARSET_JISX0212, 0, 2, JISX0212_INIT, ::jisx0212Decoder, ::jisx0212Encoder)
    private val JISX0213_2000_1 = Designation(CHARSET_JISX0213_2000_1, 0, 2, JISX0213_INIT,
        { d, p -> jisx0213Plane1Decoder(2000, d, p) }, { d, l -> jisx0213Plane1Encoder(d, l, 2000) })
    private val JISX0213_2000_1_PAIRONLY = Designation(CHARSET_JISX0213_2000_1, 0, 2, JISX0213_INIT,
        { d, p -> jisx0213Plane1Decoder(2000, d, p) }, { d, l -> jisx0213PairOnlyEncoder(d, l, 2000) })
    private val JISX0213_2000_2 = Designation(CHARSET_JISX0213_2, 0, 2, JISX0213_INIT,
        ::jisx0213Plane2Decoder, { d, l -> jisx0213Plane2Encoder(d, l, 2000) })
    private val JISX0213_2004_1 = Designation(CHARSET_JISX0213_2004_1, 0, 2, JISX0213_INIT,
        { d, p -> jisx0213Plane1Decoder(0, d, p) }, { d, l -> jisx0213Plane1Encoder(d, l, 0) })
    private val JISX0213_2004_1_PAIRONLY = Designation(CHARSET_JISX0213_2004_1, 0, 2, JISX0213_INIT,
        { d, p -> jisx0213Plane1Decoder(0, d, p) }, { d, l -> jisx0213PairOnlyEncoder(d, l, 0) })
    private val JISX0213_2004_2 = Designation(CHARSET_JISX0213_2, 0, 2, JISX0213_INIT,
        ::jisx0213Plane2Decoder, { d, l -> jisx0213Plane2Encoder(d, l, 0) })
    private val GB2312 = Designation(CHARSET_GB2312, 0, 2, GB2312_INIT, ::gb2312Decoder, ::gb2312Encoder)
    private val ISO8859_1 = Designation(CHARSET_ISO8859_1, 2, 1, emptyList(), DUMMY_DECODER, DUMMY_ENCODER)
    private val ISO8859_7 = Designation(CHARSET_ISO8859_7, 2, 1, emptyList(), DUMMY_DECODER, DUMMY_ENCODER)

    /** An iso2022 codec: its config's flags and designations. */
    private class Iso2022(name: String, @JvmField val flags: Int, @JvmField val designations: Array<Designation>) : MbCodec(name) {
        private fun isSet(f: Int) = flags and f != 0

        override fun init(ctx: PyContext) {
            for (d in designations) for ((module, map) in d.init) PyMultibytecodec.importMap(ctx, module, map)
        }

        override fun encInit(st: ByteArray) {
            st[4] = 0
            st[0] = CHARSET_ASCII.toByte()
            st[1] = CHARSET_ASCII.toByte()
        }

        override fun encReset(st: ByteArray, e: Enc): Int {
            if (flag(st, F_SHIFTED)) {
                if (e.left < 1) return Mb.TOOSMALL
                e.o(0, SI)
                e.outpos++
                clearFlag(st, F_SHIFTED)
            }
            if (g(st, 0) != CHARSET_ASCII) {
                if (e.left < 3) return Mb.TOOSMALL
                e.o(0, ESC)
                e.o(1, '('.code)
                e.o(2, 'B'.code)
                e.outpos += 3
                st[0] = CHARSET_ASCII.toByte()
            }
            return 0
        }

        override fun encode(st: ByteArray, e: Enc, flags: Int): Int {
            val buf = IntArray(2)
            val length = IntArray(1)
            while (e.inpos < e.inlen) {
                val c = e.inp[e.inpos]
                if (c < 0x80) {
                    if (g(st, 0) != CHARSET_ASCII) {
                        if (e.left < 3) return Mb.TOOSMALL
                        e.o(0, ESC)
                        e.o(1, '('.code)
                        e.o(2, 'B'.code)
                        st[0] = CHARSET_ASCII.toByte()
                        e.outpos += 3
                    }
                    if (flag(st, F_SHIFTED)) {
                        if (e.left < 1) return Mb.TOOSMALL
                        e.o(0, SI)
                        clearFlag(st, F_SHIFTED)
                        e.outpos++
                    }
                    if (e.left < 1) return Mb.TOOSMALL
                    e.o(0, c)
                    e.next(1, 1)
                    continue
                }
                var insize = 1
                var encoded = MAP_UNMAPPABLE
                var dsg: Designation? = null
                for (d in designations) {
                    buf[0] = c
                    buf[1] = 0
                    length[0] = 1
                    encoded = d.encoder(buf, length)
                    if (encoded == MAP_MULTIPLE_AVAIL) {
                        // (not for a pair of characters past the BMP)
                        if (e.inlen - e.inpos < 2) {
                            if (flags and Mb.FLUSH == 0) return Mb.TOOFEW
                            length[0] = -1
                        } else {
                            buf[1] = e.inp[e.inpos + 1]
                            length[0] = 2
                        }
                        encoded = d.encoder(buf, length)
                        if (encoded != MAP_UNMAPPABLE) {
                            insize = length[0]
                            dsg = d
                            break
                        }
                    } else if (encoded != MAP_UNMAPPABLE) {
                        dsg = d
                        break
                    }
                }
                if (dsg == null) return 1
                when (dsg.plane) {
                    0 -> {
                        // (G0)
                        if (flag(st, F_SHIFTED)) {
                            if (e.left < 1) return Mb.TOOSMALL
                            e.o(0, SI)
                            clearFlag(st, F_SHIFTED)
                            e.outpos++
                        }
                        if (g(st, 0) != dsg.mark) {
                            if (dsg.width == 1) {
                                if (e.left < 3) return Mb.TOOSMALL
                                e.o(0, ESC)
                                e.o(1, '('.code)
                                e.o(2, dsg.mark and 0x7f)
                                st[0] = dsg.mark.toByte()
                                e.outpos += 3
                            } else if (dsg.mark == CHARSET_JISX0208) {
                                if (e.left < 3) return Mb.TOOSMALL
                                e.o(0, ESC)
                                e.o(1, '$'.code)
                                e.o(2, dsg.mark and 0x7f)
                                st[0] = dsg.mark.toByte()
                                e.outpos += 3
                            } else {
                                if (e.left < 4) return Mb.TOOSMALL
                                e.o(0, ESC)
                                e.o(1, '$'.code)
                                e.o(2, '('.code)
                                e.o(3, dsg.mark and 0x7f)
                                st[0] = dsg.mark.toByte()
                                e.outpos += 4
                            }
                        }
                    }
                    1 -> {
                        // (G1)
                        if (g(st, 1) != dsg.mark) {
                            if (dsg.width == 1) {
                                if (e.left < 3) return Mb.TOOSMALL
                                e.o(0, ESC)
                                e.o(1, ')'.code)
                                e.o(2, dsg.mark and 0x7f)
                                st[1] = dsg.mark.toByte()
                                e.outpos += 3
                            } else {
                                if (e.left < 4) return Mb.TOOSMALL
                                e.o(0, ESC)
                                e.o(1, '$'.code)
                                e.o(2, ')'.code)
                                e.o(3, dsg.mark and 0x7f)
                                st[1] = dsg.mark.toByte()
                                e.outpos += 4
                            }
                        }
                        if (!flag(st, F_SHIFTED)) {
                            if (e.left < 1) return Mb.TOOSMALL
                            e.o(0, SO)
                            setFlag(st, F_SHIFTED)
                            e.outpos++
                        }
                    }
                    // (G2 and G3: no codec's encoder uses them)
                    else -> return Mb.INTERNAL
                }
                if (dsg.width == 1) {
                    if (e.left < 1) return Mb.TOOSMALL
                    e.o(0, encoded)
                    e.outpos++
                } else {
                    if (e.left < 2) return Mb.TOOSMALL
                    e.o(0, encoded shr 8)
                    e.o(1, encoded and 0xff)
                    e.outpos += 2
                }
                e.inpos += insize
            }
            return 0
        }

        override fun decInit(st: ByteArray) {
            st[4] = 0
            st[0] = CHARSET_ASCII.toByte()
            st[1] = CHARSET_ASCII.toByte()
            st[2] = CHARSET_ASCII.toByte()
        }

        override fun decReset(st: ByteArray) {
            st[0] = CHARSET_ASCII.toByte()
            clearFlag(st, F_SHIFTED)
        }

        /** iso2022processesc: an escape sequence at the buffer's position designating a character set. */
        private fun processEsc(st: ByteArray, d: Dec): Int {
            var esclen = 0
            var i = 1
            while (i < MAX_ESCSEQLEN) {
                if (i >= d.left) return Mb.TOOFEW
                if (isEscEnd(d.at(i))) {
                    esclen = i + 1
                    break
                } else if (isSet(USE_JISX0208_EXT) && i + 1 < d.left && d.at(i) == '&'.code && d.at(i + 1) == '@'.code) {
                    i += 2
                }
                i++
            }
            val charset: Int
            val designation: Int
            when (esclen) {
                // (an escape sequence not ended)
                0 -> return 1
                3 -> {
                    if (d.at(1) == '$'.code) {
                        charset = d.at(2) or CHARSET_DBCS
                        designation = 0
                    } else {
                        charset = d.at(2)
                        designation = when {
                            d.at(1) == '('.code -> 0
                            d.at(1) == ')'.code -> 1
                            isSet(USE_G2) && d.at(1) == '.'.code -> 2
                            else -> return 3
                        }
                    }
                }
                4 -> {
                    if (d.at(1) != '$'.code) return 4
                    charset = d.at(3) or CHARSET_DBCS
                    designation = when {
                        d.at(2) == '('.code -> 0
                        d.at(2) == ')'.code -> 1
                        else -> return 4
                    }
                }
                6 -> {
                    // (a designation with a prefix)
                    if (isSet(USE_JISX0208_EXT) && d.at(3) == ESC && d.at(4) == '$'.code && d.at(5) == 'B'.code) {
                        charset = 'B'.code or CHARSET_DBCS
                        designation = 0
                    } else {
                        return 6
                    }
                }
                else -> return esclen
            }
            // (a character set the encoding does not designate: an error)
            if (charset != CHARSET_ASCII && designations.none { it.mark == charset }) return esclen
            st[designation] = charset.toByte()
            d.pos += esclen
            return 0
        }

        /** iso2022processg2: a character by SS2 (ESC N) in G2's set. */
        private fun processG2(st: ByteArray, d: Dec): Int {
            when (g(st, 2)) {
                CHARSET_ISO8859_1 -> {
                    if (d.at(2) < 0x80) d.out(d.at(2) + 0x80) else return 3
                }
                CHARSET_ISO8859_7 -> {
                    val c = d.at(2) xor 0x80
                    if (c < 0xa0) d.out(c)
                    else if (c < 0xc0 && (0x288f3bc9L and (1L shl (c - 0xa0))) != 0L) d.out(c)
                    else if (c in 0xb4..0xfe && (c >= 0xd4 || (0xbffffd77L and (1L shl (c - 0xb4))) != 0L)) d.out(0x02d0 + c)
                    else if (c == 0xa1) d.out(0x2018)
                    else if (c == 0xa2) d.out(0x2019)
                    else if (c == 0xaf) d.out(0x2015)
                    else return 3
                }
                CHARSET_ASCII -> {
                    if (d.at(2) and 0x80 != 0) return 3 else d.out(d.at(2))
                }
                else -> return Mb.INTERNAL
            }
            d.pos += 3
            return 0
        }

        override fun decode(st: ByteArray, d: Dec): Int {
            var cache: Designation? = null
            while (d.left > 0) {
                val c = d.at(0)
                if (flag(st, F_ESCTHROUGHOUT)) {
                    // (ESC throughout: an escape sequence not ISO 2022's, as ISO-8859-1)
                    d.out(c)
                    d.pos++
                    if (isEscEnd(c)) clearFlag(st, F_ESCTHROUGHOUT)
                    continue
                }
                var bypass = false
                when (c) {
                    ESC -> {
                        if (d.left < 2) return Mb.TOOFEW
                        if (isIso2022Esc(d.at(1))) {
                            val err = processEsc(st, d)
                            if (err != 0) return err
                        } else if (isSet(USE_G2) && d.at(1) == 'N'.code) {
                            // (SS2)
                            if (d.left < 3) return Mb.TOOFEW
                            val err = processG2(st, d)
                            if (err != 0) return err
                        } else {
                            d.out(ESC)
                            setFlag(st, F_ESCTHROUGHOUT)
                            d.pos++
                        }
                    }
                    SI -> if (isSet(NO_SHIFT)) bypass = true else {
                        clearFlag(st, F_SHIFTED)
                        d.pos++
                    }
                    SO -> if (isSet(NO_SHIFT)) bypass = true else {
                        setFlag(st, F_SHIFTED)
                        d.pos++
                    }
                    LF -> {
                        clearFlag(st, F_SHIFTED)
                        d.out(LF)
                        d.pos++
                    }
                    else -> {
                        if (c < 0x20) {
                            // (C0)
                            bypass = true
                        } else if (c >= 0x80) {
                            return 1
                        } else {
                            val charset = if (flag(st, F_SHIFTED)) g(st, 1) else g(st, 0)
                            if (charset == CHARSET_ASCII) {
                                bypass = true
                            } else {
                                val dsg = if (cache != null && cache.mark == charset) cache
                                    else (designations.firstOrNull { it.mark == charset } ?: return Mb.INTERNAL).also { cache = it }
                                if (d.left < dsg.width) return Mb.TOOFEW
                                val decoded = dsg.decoder(d.b, d.pos)
                                if (decoded == MAP_UNMAPPABLE) return dsg.width
                                if (decoded < 0x30000) {
                                    d.out(decoded)
                                } else {
                                    // (JIS X 0213's pairs)
                                    d.out(decoded ushr 16)
                                    d.out(decoded and 0xffff)
                                }
                                d.pos += dsg.width
                            }
                        }
                    }
                }
                if (bypass) {
                    d.out(c)
                    d.pos++
                }
            }
            return 0
        }
    }

    val CODECS: List<MbCodec> = listOf(
        Iso2022("iso2022_kr", 0, arrayOf(KSX1001_G1)),
        Iso2022("iso2022_jp", NO_SHIFT or USE_JISX0208_EXT, arrayOf(JISX0208, JISX0201_R, JISX0208_O)),
        Iso2022("iso2022_jp_1", NO_SHIFT or USE_JISX0208_EXT, arrayOf(JISX0208, JISX0212, JISX0201_R, JISX0208_O)),
        Iso2022("iso2022_jp_2", NO_SHIFT or USE_G2 or USE_JISX0208_EXT,
            arrayOf(JISX0208, JISX0212, KSX1001_G0, GB2312, JISX0201_R, JISX0208_O, ISO8859_1, ISO8859_7)),
        Iso2022("iso2022_jp_2004", NO_SHIFT or USE_JISX0208_EXT, arrayOf(JISX0213_2004_1_PAIRONLY, JISX0208, JISX0213_2004_1, JISX0213_2004_2)),
        Iso2022("iso2022_jp_3", NO_SHIFT or USE_JISX0208_EXT, arrayOf(JISX0213_2000_1_PAIRONLY, JISX0208, JISX0213_2000_1, JISX0213_2000_2)),
        Iso2022("iso2022_jp_ext", NO_SHIFT or USE_JISX0208_EXT, arrayOf(JISX0208, JISX0212, JISX0201_R, JISX0201_K, JISX0208_O)),
    )
}
