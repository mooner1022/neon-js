package dev.mooner.neonpython

/** A C pointer that a scanner sets (nextTokPtr, badPtr): a position in the buffer scanned. */
class XPtr {
    @JvmField var p = 0
}

/** POSITION: a line and a column, both from 0. */
class XPosition {
    @JvmField var lineNumber = 0L
    @JvmField var columnNumber = 0L
}

/** ATTRIBUTE: an attribute XmlGetAttributes found, its name's and value's positions, whether its value is normalized. */
class XAttribute {
    @JvmField var name = 0
    @JvmField var valuePtr = 0
    @JvmField var valueEnd = 0
    @JvmField var normalized = false
}

/**
 * Expat 2.8.1's tokenizer (Modules/expat/xmltok.c, xmltok_impl.c, xmltok_ns.c, as CPython 3.14.6 bundles it), for
 * pyexpat ([PyExpat]): an [ExpatEnc] scans one encoding's bytes. xmltok_impl.c's scanners are written once over what
 * its macros ask of an encoding (BYTE_TYPE, CHAR_MATCHES, IS_NAME_CHAR...), not three times as the C preprocessor
 * makes them; the encodings are Expat's: UTF-8, ISO-8859-1, US-ASCII, UTF-16 (BE and LE), the internal UTF-8, and
 * an unknown encoding's byte map; each with its namespace variant (':' a BT_COLON). Pointers are offsets into the
 * buffer scanned.
 */
object ExpatTok {
    const val BT_NONXML = 0
    const val BT_MALFORM = 1
    const val BT_LT = 2
    const val BT_AMP = 3
    const val BT_RSQB = 4
    const val BT_LEAD2 = 5
    const val BT_LEAD3 = 6
    const val BT_LEAD4 = 7
    const val BT_TRAIL = 8
    const val BT_CR = 9
    const val BT_LF = 10
    const val BT_GT = 11
    const val BT_QUOT = 12
    const val BT_APOS = 13
    const val BT_EQUALS = 14
    const val BT_QUEST = 15
    const val BT_EXCL = 16
    const val BT_SOL = 17
    const val BT_SEMI = 18
    const val BT_NUM = 19
    const val BT_LSQB = 20
    const val BT_S = 21
    const val BT_NMSTRT = 22
    const val BT_COLON = 23
    const val BT_HEX = 24
    const val BT_DIGIT = 25
    const val BT_NAME = 26
    const val BT_MINUS = 27
    const val BT_OTHER = 28
    const val BT_NONASCII = 29
    const val BT_PERCNT = 30
    const val BT_LPAR = 31
    const val BT_RPAR = 32
    const val BT_AST = 33
    const val BT_PLUS = 34
    const val BT_COMMA = 35
    const val BT_VERBAR = 36

    const val XML_TOK_TRAILING_RSQB = -5
    const val XML_TOK_NONE = -4
    const val XML_TOK_TRAILING_CR = -3
    const val XML_TOK_PARTIAL_CHAR = -2
    const val XML_TOK_PARTIAL = -1
    const val XML_TOK_INVALID = 0
    const val XML_TOK_START_TAG_WITH_ATTS = 1
    const val XML_TOK_START_TAG_NO_ATTS = 2
    const val XML_TOK_EMPTY_ELEMENT_WITH_ATTS = 3
    const val XML_TOK_EMPTY_ELEMENT_NO_ATTS = 4
    const val XML_TOK_END_TAG = 5
    const val XML_TOK_DATA_CHARS = 6
    const val XML_TOK_DATA_NEWLINE = 7
    const val XML_TOK_CDATA_SECT_OPEN = 8
    const val XML_TOK_ENTITY_REF = 9
    const val XML_TOK_CHAR_REF = 10
    const val XML_TOK_PI = 11
    const val XML_TOK_XML_DECL = 12
    const val XML_TOK_COMMENT = 13
    const val XML_TOK_BOM = 14
    const val XML_TOK_PROLOG_S = 15
    const val XML_TOK_DECL_OPEN = 16
    const val XML_TOK_DECL_CLOSE = 17
    const val XML_TOK_NAME = 18
    const val XML_TOK_NMTOKEN = 19
    const val XML_TOK_POUND_NAME = 20
    const val XML_TOK_OR = 21
    const val XML_TOK_PERCENT = 22
    const val XML_TOK_OPEN_PAREN = 23
    const val XML_TOK_CLOSE_PAREN = 24
    const val XML_TOK_OPEN_BRACKET = 25
    const val XML_TOK_CLOSE_BRACKET = 26
    const val XML_TOK_LITERAL = 27
    const val XML_TOK_PARAM_ENTITY_REF = 28
    const val XML_TOK_INSTANCE_START = 29
    const val XML_TOK_NAME_QUESTION = 30
    const val XML_TOK_NAME_ASTERISK = 31
    const val XML_TOK_NAME_PLUS = 32
    const val XML_TOK_COND_SECT_OPEN = 33
    const val XML_TOK_COND_SECT_CLOSE = 34
    const val XML_TOK_CLOSE_PAREN_QUESTION = 35
    const val XML_TOK_CLOSE_PAREN_ASTERISK = 36
    const val XML_TOK_CLOSE_PAREN_PLUS = 37
    const val XML_TOK_COMMA = 38
    const val XML_TOK_ATTRIBUTE_VALUE_S = 39
    const val XML_TOK_CDATA_SECT_CLOSE = 40
    const val XML_TOK_PREFIXED_NAME = 41
    const val XML_TOK_IGNORE_SECT = 42

    const val XML_PROLOG_STATE = 0
    const val XML_CONTENT_STATE = 1
    const val XML_CDATA_SECTION_STATE = 2
    const val XML_IGNORE_SECTION_STATE = 3

    const val XML_CONVERT_COMPLETED = 0
    const val XML_CONVERT_INPUT_INCOMPLETE = 1
    const val XML_CONVERT_OUTPUT_EXHAUSTED = 2

    const val XML_UTF8_ENCODE_MAX = 4

    /** The encodings' indices (getEncodingIndex): encodingNames up to UTF_16LE_ENC. */
    const val UNKNOWN_ENC = -1
    const val ISO_8859_1_ENC = 0
    const val US_ASCII_ENC = 1
    const val UTF_8_ENC = 2
    const val UTF_16_ENC = 3
    const val UTF_16BE_ENC = 4
    const val UTF_16LE_ENC = 5
    const val NO_ENC = 6


    private fun hexBytes(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun hexInts(s: String): IntArray = IntArray(s.length / 8) { s.substring(8 * it, 8 * it + 8).toLong(16).toInt() }

    /** asciitab.h: the types of 0x00..0x7F (':' BT_COLON), two hex digits each. */
    @JvmField val ASCII_TAB = hexBytes(
            "000000000000000000150a00000900000000000000000000000000000000000015100c131c1e030d1f202122231b1a11" +
            "191919191919191919191712020e0b0f1c1818181818181616161616161616161616161616161616161616141c041c16" +
            "1c18181818181816161616161616161616161616161616161616161c241c1c1c")

    /** iasciitab.h: asciitab.h's, but CR a BT_S (the internal encoding's). */
    @JvmField val IASCII_TAB = hexBytes(
            "000000000000000000150a00001500000000000000000000000000000000000015100c131c1e030d1f202122231b1a11" +
            "191919191919191919191712020e0b0f1c1818181818181616161616161616161616161616161616161616141c041c16" +
            "1c18181818181816161616161616161616161616161616161616161c241c1c1c")

    /** latin1tab.h: the types of 0x80..0xFF in ISO-8859-1. */
    @JvmField val LATIN1_TAB = hexBytes(
            "1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c1c161c1c1c1c1c" +
            "1c1c1c1c1c161c1a1c1c161c1c1c1c1c16161616161616161616161616161616161616161616161c1616161616161616" +
            "16161616161616161616161616161616161616161616161c1616161616161616")

    /** utf8tab.h: the types of 0x80..0xFF in UTF-8. */
    @JvmField val UTF8_TAB = hexBytes(
            "080808080808080808080808080808080808080808080808080808080808080808080808080808080808080808080808" +
            "080808080808080808080808080808080505050505050505050505050505050505050505050505050505050505050505" +
            "0606060606060606060606060606060607070707070000000000000000000101")

    /** nametab.h's namingBitmap, eight hex digits each. */
    @JvmField val NAMING_BITMAP = hexInts(
            "0000000000000000000000000000000000000000000000000000000000000000ffffffffffffffffffffffffffffffff" +
            "ffffffffffffffffffffffffffffffff000000000400000087fffffe07fffffe0000000000000000ff7fffffff7fffff" +
            "ffffffff7ff3fffffffffdfe7fffffffffffffffffffffffffffe00ffc31ffff00ffffff00000000ffff0000ffffffff" +
            "fffffffff80001ff000000030000000000000000000000000000000000000000ffffd740fffffffb547f7fff000ffffd" +
            "ffffdffeffffffffdffeffffffffffffffff0003ffffffffffff199f033fcfff00000000fffe0000027ffffffffffffe" +
            "0000007f00000000ffff0000000707ff0000000007fffffe000007fefffe0000ffffffff7cffffff002f7fff00000060" +
            "ffffffe023ffffffff00000000000003fff99fe003c5fdffb000000000030003fff987e0036dfdff5e000000001c0000" +
            "fffbafe023edfdff0000000000000001fff99fe023cdfdffb000000000000003d63dc7e003bfc7180000000000000000" +
            "fffddfe003effdff0000000000000003fffddfe003effdff4000000000000003fffddfe003fffdff0000000000000003" +
            "00000000000000000000000000000000fffffffe000d7fff0000003f00000000fef02596200d6cae0000001f00000000" +
            "0000000000000000fffffeff000003ff0000000000000000000000000000000000000000000000000000000000000000" +
            "00000000ffffffffffff003f007fffff0007daed5000000082315001002c62ab40000000f580c9000000000702010800" +
            "ffffffffffffffffffffffffffffffff0fffffffffffffffffffffff03ffffff3f3fffffffffffffaaff3f3f3fffffff" +
            "ffffffff5fdfffff0fcf1fdc1fdc1fff0000000000004c40000000000000000000000007000000000000000000000000" +
            "00000080000003fefffffffeffffffff001ffffffffffffeffffffff07ffffffffffffe000001fff0000000000000000" +
            "00000000000000000000000000000000ffffffffffffffffffffffffffffffffffffffff0000003f0000000000000000" +
            "ffffffffffffffffffffffffffffffffffffffff0000000f00000000000000000000000007ff600087fffffe07fffffe" +
            "0000000000800000ff7fffffff7fffff00ffffff00000000ffff0000fffffffffffffffff80001ff0003000300000000" +
            "ffffffffffffffff0000003f00000003ffffd7c0fffffffb547f7fff000ffffdffffdffeffffffffdffeffffffffffff" +
            "ffff007bffffffffffff199f033fcfff00000000fffe0000027ffffffffffffefffe007fbbfffffbffff0016000707ff" +
            "0000000007fffffe0007ffffffff03ffffffffff7cffffffffef7fff03ff3dffffffffeef3ffffffff1e3fff0000ffcf" +
            "fff99feed3c5fdffb080399f0003ffcffff987e4d36dfdff5e003987001fffc0fffbafeef3edfdff00003bbf0000ffc1" +
            "fff99feef3cdfdffb0c0398f0000ffc3d63dc7ecc3bfc71800803dc70000ff80fffddfeec3effdff00603ddf0000ffc3" +
            "fffddfecc3effdff40603ddf0000ffc3fffddfecc3fffdff00803dcf0000ffc300000000000000000000000000000000" +
            "fffffffe07ff7fff03ff7fff00000000fef025963bff6cae03ff3f5f0000000003000000c2a003fffffffefffffe03ff" +
            "febf0fdf02fe3fff00000000000000000000000000000000000000000000000000000000000000001fff000000000002" +
            "000000a0003efffefffffffeffffffff661ffffffffffffeffffffff77ffffff")

    /** nametab.h's nmstrtPages. */
    @JvmField val NMSTRT_PAGES = hexBytes(
            "020304050607080000090a0b0c0d0e0f1011000000000000000000000000121300140000000000000000000000000000" +
            "151600000000000000000000000000000000000000000000000000000000010101010101010101010101010101010101" +
            "010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101" +
            "010101010101010101010101010101170000000000000000000000000101010101010101010101010101010101010101" +
            "010101010101010101010101010101010101010101010118000000000000000000000000000000000000000000000000" +
            "00000000000000000000000000000000")

    /** nametab.h's namePages. */
    @JvmField val NAME_PAGES = hexBytes(
            "19031a1b1c1d1e00001f2021222324251011000000000000000000000000121326140000000000000000000000000000" +
            "271600000000000000000000000000000000000000000000000000000000010101010101010101010101010101010101" +
            "010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101010101" +
            "010101010101010101010101010101170000000000000000000000000101010101010101010101010101010101010101" +
            "010101010101010101010101010101010101010101010118000000000000000000000000000000000000000000000000" +
            "00000000000000000000000000000000")

    /** UCS2_GET_NAMING: whether the BMP character [hi]:[lo] is in [pages]' set. */
    @JvmStatic
    fun ucs2Naming(pages: ByteArray, hi: Int, lo: Int): Boolean =
        NAMING_BITMAP[(pages[hi].toInt() shl 3) + (lo shr 5)] and (1 shl (lo and 0x1F)) != 0

    /** UTF8_GET_NAMING2 */
    @JvmStatic
    fun utf8Naming2(pages: ByteArray, b: ByteArray, p: Int): Boolean {
        val b0 = b[p].toInt() and 0xFF
        val b1 = b[p + 1].toInt() and 0xFF
        return NAMING_BITMAP[(pages[(b0 shr 2) and 7].toInt() shl 3) + ((b0 and 3) shl 1) + ((b1 shr 5) and 1)] and (1 shl (b1 and 0x1F)) != 0
    }

    /** UTF8_GET_NAMING3 */
    @JvmStatic
    fun utf8Naming3(pages: ByteArray, b: ByteArray, p: Int): Boolean {
        val b0 = b[p].toInt() and 0xFF
        val b1 = b[p + 1].toInt() and 0xFF
        val b2 = b[p + 2].toInt() and 0xFF
        return NAMING_BITMAP[(pages[((b0 and 0xF) shl 4) + ((b1 shr 2) and 0xF)].toInt() shl 3) + ((b1 and 3) shl 1) + ((b2 shr 5) and 1)] and
            (1 shl (b2 and 0x1F)) != 0
    }

    /** UTF8_INVALID2..4: Unicode 3.2's Table 3.1B, U+FFFE and U+FFFF too. */
    @JvmStatic
    fun utf8Invalid(b: ByteArray, p: Int, n: Int): Boolean {
        val p0 = b[p].toInt() and 0xFF
        val p1 = b[p + 1].toInt() and 0xFF
        if (n == 2) return p0 < 0xC2 || (p1 and 0x80) == 0 || (p1 and 0xC0) == 0xC0
        val p2 = b[p + 2].toInt() and 0xFF
        if (n == 3) {
            return (p2 and 0x80) == 0 ||
                (if (p0 == 0xEF && p1 == 0xBF) p2 > 0xBD else (p2 and 0xC0) == 0xC0) ||
                (if (p0 == 0xE0) p1 < 0xA0 || (p1 and 0xC0) == 0xC0
                else (p1 and 0x80) == 0 || (if (p0 == 0xED) p1 > 0x9F else (p1 and 0xC0) == 0xC0))
        }
        val p3 = b[p + 3].toInt() and 0xFF
        return (p3 and 0x80) == 0 || (p3 and 0xC0) == 0xC0 || (p2 and 0x80) == 0 || (p2 and 0xC0) == 0xC0 ||
            (if (p0 == 0xF0) p1 < 0x90 || (p1 and 0xC0) == 0xC0
            else (p1 and 0x80) == 0 || (if (p0 == 0xF4) p1 > 0x8F else (p1 and 0xC0) == 0xC0))
    }

    /** unicode_byte_type: a UTF-16 unit's type whose high byte is not 0. */
    @JvmStatic
    fun unicodeByteType(hi: Int, lo: Int): Int = when (hi) {
        0xD8, 0xD9, 0xDA, 0xDB -> BT_LEAD4
        0xDC, 0xDD, 0xDE, 0xDF -> BT_TRAIL
        0xFF -> if (lo == 0xFF || lo == 0xFE) BT_NONXML else BT_NONASCII
        else -> BT_NONASCII
    }

    /** checkCharRefNumber: [result], or -1 where it is no XML character. */
    @JvmStatic
    fun checkCharRefNumber(result: Int): Int {
        when (result shr 8) {
            0xD8, 0xD9, 0xDA, 0xDB, 0xDC, 0xDD, 0xDE, 0xDF -> return -1
            0 -> if (LATIN1.type[result].toInt() == BT_NONXML) return -1
            0xFF -> if (result == 0xFFFE || result == 0xFFFF) return -1
        }
        return result
    }

    /** XmlUtf8Encode: [c]'s UTF-8 into [buf] at [at]: how many bytes. */
    @JvmStatic
    fun utf8Encode(c: Int, buf: ByteArray, at: Int): Int {
        if (c < 0) return 0
        if (c < 0x80) {
            buf[at] = c.toByte()
            return 1
        }
        if (c < 0x800) {
            buf[at] = ((c shr 6) or 0xC0).toByte()
            buf[at + 1] = ((c and 0x3F) or 0x80).toByte()
            return 2
        }
        if (c < 0x10000) {
            buf[at] = ((c shr 12) or 0xE0).toByte()
            buf[at + 1] = (((c shr 6) and 0x3F) or 0x80).toByte()
            buf[at + 2] = ((c and 0x3F) or 0x80).toByte()
            return 3
        }
        if (c < 0x110000) {
            buf[at] = ((c shr 18) or 0xF0).toByte()
            buf[at + 1] = (((c shr 12) and 0x3F) or 0x80).toByte()
            buf[at + 2] = (((c shr 6) and 0x3F) or 0x80).toByte()
            buf[at + 3] = ((c and 0x3F) or 0x80).toByte()
            return 4
        }
        return 0
    }

    /** streqci: [s1] equal to [s2] (upper case) ignoring ASCII case. */
    @JvmStatic
    fun streqci(s1: String, s2: String): Boolean {
        if (s1.length != s2.length) return false
        for (i in s1.indices) {
            var c1 = s1[i]
            if (c1 in 'a'..'z') c1 -= 32
            if (c1 != s2[i]) return false
        }
        return true
    }

    private val ENCODING_NAMES = arrayOf("ISO-8859-1", "US-ASCII", "UTF-8", "UTF-16", "UTF-16BE", "UTF-16LE")

    /** getEncodingIndex: [name]'s index among the built-in encodings, NO_ENC without one, UNKNOWN_ENC. */
    @JvmStatic
    fun getEncodingIndex(name: String?): Int {
        if (name == null) return NO_ENC
        // (streqci stops at a NUL, as the C string does)
        val n = name.substringBefore('\u0000')
        for (i in ENCODING_NAMES.indices) if (streqci(n, ENCODING_NAMES[i])) return i
        return UNKNOWN_ENC
    }

    // ------------------------------------------------------------------ the encodings

    private fun normalTable(ascii: ByteArray, high: ByteArray, ns: Boolean): ByteArray {
        val t = ByteArray(256)
        ascii.copyInto(t, 0)
        high.copyInto(t, 128)
        if (!ns) t[':'.code] = BT_NMSTRT.toByte()
        return t
    }

    @JvmField val UTF8 = Utf8Enc(normalTable(ASCII_TAB, UTF8_TAB, false))
    @JvmField val UTF8_NS = Utf8Enc(normalTable(ASCII_TAB, UTF8_TAB, true))
    @JvmField val INTERNAL_UTF8 = Utf8Enc(normalTable(IASCII_TAB, UTF8_TAB, false))
    @JvmField val INTERNAL_UTF8_NS = Utf8Enc(normalTable(IASCII_TAB, UTF8_TAB, true))
    @JvmField val LATIN1 = Latin1Enc(normalTable(ASCII_TAB, LATIN1_TAB, false))
    @JvmField val LATIN1_NS = Latin1Enc(normalTable(ASCII_TAB, LATIN1_TAB, true))
    @JvmField val ASCII = AsciiEnc(normalTable(ASCII_TAB, ByteArray(128), false))
    @JvmField val ASCII_NS = AsciiEnc(normalTable(ASCII_TAB, ByteArray(128), true))
    @JvmField val LITTLE2 = Utf16Enc(normalTable(ASCII_TAB, LATIN1_TAB, false), little = true)
    @JvmField val LITTLE2_NS = Utf16Enc(normalTable(ASCII_TAB, LATIN1_TAB, true), little = true)
    @JvmField val BIG2 = Utf16Enc(normalTable(ASCII_TAB, LATIN1_TAB, false), little = false)
    @JvmField val BIG2_NS = Utf16Enc(normalTable(ASCII_TAB, LATIN1_TAB, true), little = false)

    /** NS(encodings): by index, NO_ENC's UTF-8. */
    @JvmField val ENCODINGS = arrayOf<ExpatEnc>(LATIN1, ASCII, UTF8, BIG2, BIG2, LITTLE2, UTF8)
    @JvmField val ENCODINGS_NS = arrayOf<ExpatEnc>(LATIN1_NS, ASCII_NS, UTF8_NS, BIG2_NS, BIG2_NS, LITTLE2_NS, UTF8_NS)

    /** XmlGetUtf8InternalEncoding(NS) */
    @JvmStatic
    fun internalEncoding(ns: Boolean): ExpatEnc = if (ns) INTERNAL_UTF8_NS else INTERNAL_UTF8

    /** XmlInitEncoding(NS): [p] set to scan for an encoding as [name] says, false where [name] is unknown. */
    @JvmStatic
    fun initEncoding(p: InitEnc, name: String?): Boolean {
        val i = getEncodingIndex(name)
        if (i == UNKNOWN_ENC) return false
        p.index = i
        return true
    }

    /**
     * XmlInitUnknownEncoding(NS): an encoding of [table] (each byte's character, -1 malformed; pyexpat's converter is
     * NULL, so no byte begins a longer sequence), or null where Expat refuses the table.
     */
    @JvmStatic
    fun initUnknownEncoding(table: IntArray, ns: Boolean): ExpatEnc? {
        val type = LATIN1.type.copyOf()
        for (i in 0 until 128) {
            val t = LATIN1.type[i].toInt()
            if (t != BT_OTHER && t != BT_NONXML && table[i] != i) return null
        }
        val utf8 = arrayOfNulls<ByteArray>(256)
        for (i in 0 until 256) {
            val c = table[i]
            if (c == -1) {
                type[i] = BT_MALFORM.toByte()
                utf8[i] = byteArrayOf(0)
            } else if (c < 0) {
                // (a longer sequence needs a converter, and pyexpat gives none)
                return null
            } else if (c < 0x80) {
                val t = LATIN1.type[c].toInt()
                if (t != BT_OTHER && t != BT_NONXML && c != i) return null
                type[i] = t.toByte()
                utf8[i] = byteArrayOf(c.toByte())
            } else if (checkCharRefNumber(c) < 0) {
                type[i] = BT_NONXML.toByte()
                utf8[i] = byteArrayOf(0)
            } else {
                if (c > 0xFFFF) return null
                type[i] = (if (ucs2Naming(NMSTRT_PAGES, c shr 8, c and 0xFF)) BT_NMSTRT
                    else if (ucs2Naming(NAME_PAGES, c shr 8, c and 0xFF)) BT_NAME else BT_OTHER).toByte()
                val buf = ByteArray(4)
                utf8[i] = buf.copyOf(utf8Encode(c, buf, 0))
            }
        }
        if (ns) type[':'.code] = BT_COLON.toByte()
        @Suppress("UNCHECKED_CAST")
        return UnknownEnc(type, utf8 as Array<ByteArray>)
    }

    // ------------------------------------------------------------------ the XML declaration

    /** toAscii: the ASCII character at [ptr], -1 where there is none (another character, the end). */
    private fun toAscii(enc: ExpatEnc, b: ByteArray, ptr: Int, end: Int): Int {
        val out = ByteArray(1)
        val from = XPtr().also { it.p = ptr }
        val to = XPtr()
        enc.toUtf8(b, from, end, out, to, 1)
        return if (to.p == 0) -1 else out[0].toInt()
    }

    private fun isSpace(c: Int) = c == 0x20 || c == 0xD || c == 0xA || c == 0x9

    /** What parsePseudoAttribute found: a name's bounds (no name: [name] -1), its value's start, where it stopped. */
    private class Pseudo {
        @JvmField var name = -1
        @JvmField var nameEnd = 0
        @JvmField var value = 0
        @JvmField var next = 0
    }

    /** parsePseudoAttribute: true where there is only white space left or an S then name=value. */
    private fun parsePseudoAttribute(enc: ExpatEnc, b: ByteArray, ptr0: Int, end: Int, r: Pseudo): Boolean {
        var ptr = ptr0
        val mb = enc.minBytesPerChar
        if (ptr == end) {
            r.name = -1
            return true
        }
        if (!isSpace(toAscii(enc, b, ptr, end))) {
            r.next = ptr
            return false
        }
        do {
            ptr += mb
        } while (isSpace(toAscii(enc, b, ptr, end)))
        if (ptr == end) {
            r.name = -1
            return true
        }
        r.name = ptr
        var c: Int
        while (true) {
            c = toAscii(enc, b, ptr, end)
            if (c == -1) {
                r.next = ptr
                return false
            }
            if (c == '='.code) {
                r.nameEnd = ptr
                break
            }
            if (isSpace(c)) {
                r.nameEnd = ptr
                do {
                    ptr += mb
                    c = toAscii(enc, b, ptr, end)
                } while (isSpace(c))
                if (c != '='.code) {
                    r.next = ptr
                    return false
                }
                break
            }
            ptr += mb
        }
        if (ptr == r.name) {
            r.next = ptr
            return false
        }
        ptr += mb
        c = toAscii(enc, b, ptr, end)
        while (isSpace(c)) {
            ptr += mb
            c = toAscii(enc, b, ptr, end)
        }
        if (c != '"'.code && c != '\''.code) {
            r.next = ptr
            return false
        }
        val open = c
        ptr += mb
        r.value = ptr
        while (true) {
            c = toAscii(enc, b, ptr, end)
            if (c == open) break
            if (c !in 'a'.code..'z'.code && c !in 'A'.code..'Z'.code && c !in '0'.code..'9'.code && c != '.'.code && c != '-'.code && c != '_'.code) {
                r.next = ptr
                return false
            }
            ptr += mb
        }
        r.next = ptr + mb
        return true
    }

    /** What XmlParseXmlDecl gives: each pointer -1 where the declaration has none. */
    class XmlDecl {
        @JvmField var badPtr = 0
        @JvmField var version = -1
        @JvmField var versionEnd = -1
        @JvmField var encodingName = -1
        /** The encoding the declaration names (null: unknown, or none named: then [encodingName] is -1). */
        @JvmField var encoding: ExpatEnc? = null
        @JvmField var standalone = -1
    }

    /** NS(findEncoding): the built-in encoding [b][ptr, end) names, or null. */
    private fun findEncoding(enc: ExpatEnc, b: ByteArray, ptr: Int, end: Int, ns: Boolean): ExpatEnc? {
        val buf = ByteArray(127)
        val from = XPtr().also { it.p = ptr }
        val to = XPtr()
        enc.toUtf8(b, from, end, buf, to, 127)
        if (from.p != end) return null
        val name = String(buf, 0, to.p, Charsets.ISO_8859_1)
        if (streqci(name, "UTF-16") && enc.minBytesPerChar == 2) return enc
        val i = getEncodingIndex(name)
        if (i == UNKNOWN_ENC) return null
        return (if (ns) ENCODINGS_NS else ENCODINGS)[i]
    }

    /** XmlParseXmlDecl(NS) (doParseXmlDecl): the declaration [b][ptr, end) read into [r]; false where it is malformed. */
    @JvmStatic
    fun parseXmlDecl(isGeneralTextEntity: Boolean, enc: ExpatEnc, b: ByteArray, ptr0: Int, end0: Int, r: XmlDecl, ns: Boolean): Boolean {
        val mb = enc.minBytesPerChar
        val ptr = ptr0 + 5 * mb
        val end = end0 - 2 * mb
        val a = Pseudo()
        if (!parsePseudoAttribute(enc, b, ptr, end, a) || a.name < 0) {
            r.badPtr = a.next
            return false
        }
        if (!enc.nameMatchesAscii(b, a.name, a.nameEnd, "version")) {
            if (!isGeneralTextEntity) {
                r.badPtr = a.name
                return false
            }
        } else {
            r.version = a.value
            r.versionEnd = a.next
            if (!parsePseudoAttribute(enc, b, a.next, end, a)) {
                r.badPtr = a.next
                return false
            }
            if (a.name < 0) {
                if (isGeneralTextEntity) {
                    // (a TextDecl must have an EncodingDecl)
                    r.badPtr = a.next
                    return false
                }
                return true
            }
        }
        if (enc.nameMatchesAscii(b, a.name, a.nameEnd, "encoding")) {
            val c = toAscii(enc, b, a.value, end)
            if (c !in 'a'.code..'z'.code && c !in 'A'.code..'Z'.code) {
                r.badPtr = a.value
                return false
            }
            r.encodingName = a.value
            r.encoding = findEncoding(enc, b, a.value, a.next - mb, ns)
            if (!parsePseudoAttribute(enc, b, a.next, end, a)) {
                r.badPtr = a.next
                return false
            }
            if (a.name < 0) return true
        }
        if (!enc.nameMatchesAscii(b, a.name, a.nameEnd, "standalone") || isGeneralTextEntity) {
            r.badPtr = a.name
            return false
        }
        if (enc.nameMatchesAscii(b, a.value, a.next - mb, "yes")) r.standalone = 1
        else if (enc.nameMatchesAscii(b, a.value, a.next - mb, "no")) r.standalone = 0
        else {
            r.badPtr = a.value
            return false
        }
        var p = a.next
        while (isSpace(toAscii(enc, b, p, end))) p += mb
        if (p != end) {
            r.badPtr = p
            return false
        }
        return true
    }
}

/**
 * ENCODING: an encoding's scanners (xmltok_impl.c's, written once over the encoding's byte types) and conversion to
 * UTF-8. [minBytesPerChar] is MINBPC: 1, or 2 for UTF-16.
 */
abstract class ExpatEnc(@JvmField val type: ByteArray, @JvmField val minBytesPerChar: Int, @JvmField val isUtf8: Boolean, @JvmField val isUtf16: Boolean) {
    /** BYTE_TYPE */
    abstract fun bt(b: ByteArray, p: Int): Int
    /** BYTE_TO_ASCII: the character's ASCII, -1 (UTF-16) or the byte (signed, as a C char) where it is not ASCII. */
    abstract fun ascii(b: ByteArray, p: Int): Int
    /** CHAR_MATCHES: whether the character is the ASCII [c]. */
    abstract fun matches(b: ByteArray, p: Int, c: Char): Boolean
    /** IS_NAME_CHAR_MINBPC, IS_NMSTRT_CHAR_MINBPC: of a BT_NONASCII character. */
    open fun nameMin(b: ByteArray, p: Int): Boolean = false
    open fun nmstrtMin(b: ByteArray, p: Int): Boolean = false
    /** IS_NAME_CHAR, IS_NMSTRT_CHAR, IS_INVALID_CHAR: of an [n]-byte character. */
    open fun isName(b: ByteArray, p: Int, n: Int): Boolean = false
    open fun isNmstrt(b: ByteArray, p: Int, n: Int): Boolean = false
    open fun isInvalid(b: ByteArray, p: Int, n: Int): Boolean = false
    /** utf8Convert: from [from][fromP, fromLim) to [to][toP, toLim), both pointers moved; an XML_CONVERT_ result. */
    abstract fun toUtf8(from: ByteArray, fromP: XPtr, fromLim: Int, to: ByteArray, toP: XPtr, toLim: Int): Int

    /** XmlTok */
    fun tok(state: Int, b: ByteArray, ptr: Int, end: Int, nt: XPtr): Int = when (state) {
        ExpatTok.XML_PROLOG_STATE -> prologTok(b, ptr, end, nt)
        ExpatTok.XML_CONTENT_STATE -> contentTok(b, ptr, end, nt)
        ExpatTok.XML_CDATA_SECTION_STATE -> cdataSectionTok(b, ptr, end, nt)
        else -> ignoreSectionTok(b, ptr, end, nt)
    }

    // (what the scanners' CHECK_NAME_CASES, CHECK_NMSTRT_CASES and INVALID_CASES give: the bytes to step over, 0 for
    // another type, or one of these: XML_TOK_PARTIAL_CHAR, or XML_TOK_INVALID with nextTokPtr set)
    private fun stepTok(s: Int) = if (s == STEP_PARTIAL_CHAR) ExpatTok.XML_TOK_PARTIAL_CHAR else ExpatTok.XML_TOK_INVALID

    private fun nameStep(t: Int, b: ByteArray, ptr: Int, end: Int, nt: XPtr): Int = when (t) {
        ExpatTok.BT_NONASCII -> if (!nameMin(b, ptr)) {
            nt.p = ptr
            STEP_INVALID
        } else minBytesPerChar
        ExpatTok.BT_NMSTRT, ExpatTok.BT_HEX, ExpatTok.BT_DIGIT, ExpatTok.BT_NAME, ExpatTok.BT_MINUS -> minBytesPerChar
        ExpatTok.BT_LEAD2, ExpatTok.BT_LEAD3, ExpatTok.BT_LEAD4 -> {
            val n = t - 3
            if (end - ptr < n) STEP_PARTIAL_CHAR
            else if (isInvalid(b, ptr, n) || !isName(b, ptr, n)) {
                nt.p = ptr
                STEP_INVALID
            } else n
        }
        else -> 0
    }

    private fun nmstrtStep(t: Int, b: ByteArray, ptr: Int, end: Int, nt: XPtr): Int = when (t) {
        ExpatTok.BT_NONASCII -> if (!nmstrtMin(b, ptr)) {
            nt.p = ptr
            STEP_INVALID
        } else minBytesPerChar
        ExpatTok.BT_NMSTRT, ExpatTok.BT_HEX -> minBytesPerChar
        ExpatTok.BT_LEAD2, ExpatTok.BT_LEAD3, ExpatTok.BT_LEAD4 -> {
            val n = t - 3
            if (end - ptr < n) STEP_PARTIAL_CHAR
            else if (isInvalid(b, ptr, n) || !isNmstrt(b, ptr, n)) {
                nt.p = ptr
                STEP_INVALID
            } else n
        }
        else -> 0
    }

    private fun invalidStep(t: Int, b: ByteArray, ptr: Int, end: Int, nt: XPtr): Int = when (t) {
        ExpatTok.BT_LEAD2, ExpatTok.BT_LEAD3, ExpatTok.BT_LEAD4 -> {
            val n = t - 3
            if (end - ptr < n) STEP_PARTIAL_CHAR
            else if (isInvalid(b, ptr, n)) {
                nt.p = ptr
                STEP_INVALID
            } else n
        }
        ExpatTok.BT_NONXML, ExpatTok.BT_MALFORM, ExpatTok.BT_TRAIL -> {
            nt.p = ptr
            STEP_INVALID
        }
        else -> 0
    }

    private fun hasChars(ptr: Int, end: Int, count: Int) = end - ptr >= count * minBytesPerChar
    private fun hasChar(ptr: Int, end: Int) = end - ptr >= minBytesPerChar

    /** [end] cut to whole characters (a 2-byte encoding's), -1 where none is left. */
    private fun wholeChars(ptr: Int, end: Int): Int {
        if (minBytesPerChar > 1) {
            var n = end - ptr
            if (n and (minBytesPerChar - 1) != 0) {
                n = n and (minBytesPerChar - 1).inv()
                if (n == 0) return -1
                return ptr + n
            }
        }
        return end
    }

    /** scanComment: [ptr] after "<!-". */
    private fun scanComment(b: ByteArray, ptr0: Int, end: Int, nt: XPtr): Int {
        var ptr = ptr0
        val mb = minBytesPerChar
        if (hasChar(ptr, end)) {
            if (!matches(b, ptr, '-')) {
                nt.p = ptr
                return ExpatTok.XML_TOK_INVALID
            }
            ptr += mb
            while (hasChar(ptr, end)) {
                val t = bt(b, ptr)
                val s = invalidStep(t, b, ptr, end, nt)
                if (s > 0) {
                    ptr += s
                    continue
                }
                if (s < 0) return stepTok(s)
                if (t == ExpatTok.BT_MINUS) {
                    ptr += mb
                    if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_PARTIAL
                    if (matches(b, ptr, '-')) {
                        ptr += mb
                        if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_PARTIAL
                        if (!matches(b, ptr, '>')) {
                            nt.p = ptr
                            return ExpatTok.XML_TOK_INVALID
                        }
                        nt.p = ptr + mb
                        return ExpatTok.XML_TOK_COMMENT
                    }
                } else ptr += mb
            }
        }
        return ExpatTok.XML_TOK_PARTIAL
    }

    /** scanDecl: [ptr] after "<!". */
    private fun scanDecl(b: ByteArray, ptr0: Int, end: Int, nt: XPtr): Int {
        var ptr = ptr0
        val mb = minBytesPerChar
        if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_PARTIAL
        when (bt(b, ptr)) {
            ExpatTok.BT_MINUS -> return scanComment(b, ptr + mb, end, nt)
            ExpatTok.BT_LSQB -> {
                nt.p = ptr + mb
                return ExpatTok.XML_TOK_COND_SECT_OPEN
            }
            ExpatTok.BT_NMSTRT, ExpatTok.BT_HEX -> ptr += mb
            else -> {
                nt.p = ptr
                return ExpatTok.XML_TOK_INVALID
            }
        }
        while (hasChar(ptr, end)) {
            when (bt(b, ptr)) {
                ExpatTok.BT_PERCNT -> {
                    if (!hasChars(ptr, end, 2)) return ExpatTok.XML_TOK_PARTIAL
                    // (don't allow <!ENTITY% foo "whatever">)
                    when (bt(b, ptr + mb)) {
                        ExpatTok.BT_S, ExpatTok.BT_CR, ExpatTok.BT_LF, ExpatTok.BT_PERCNT -> {
                            nt.p = ptr
                            return ExpatTok.XML_TOK_INVALID
                        }
                    }
                    nt.p = ptr
                    return ExpatTok.XML_TOK_DECL_OPEN
                }
                ExpatTok.BT_S, ExpatTok.BT_CR, ExpatTok.BT_LF -> {
                    nt.p = ptr
                    return ExpatTok.XML_TOK_DECL_OPEN
                }
                ExpatTok.BT_NMSTRT, ExpatTok.BT_HEX -> ptr += mb
                else -> {
                    nt.p = ptr
                    return ExpatTok.XML_TOK_INVALID
                }
            }
        }
        return ExpatTok.XML_TOK_PARTIAL
    }

    /** checkPiTarget: 0 where the target [ptr, end) is "xml" in another case; else 1, the token in [tok]'s slot 0. */
    private fun checkPiTarget(b: ByteArray, ptr0: Int, end: Int, tok: IntArray): Boolean {
        var ptr = ptr0
        val mb = minBytesPerChar
        var upper = false
        tok[0] = ExpatTok.XML_TOK_PI
        if (end - ptr != mb * 3) return true
        when (ascii(b, ptr)) {
            'x'.code -> {}
            'X'.code -> upper = true
            else -> return true
        }
        ptr += mb
        when (ascii(b, ptr)) {
            'm'.code -> {}
            'M'.code -> upper = true
            else -> return true
        }
        ptr += mb
        when (ascii(b, ptr)) {
            'l'.code -> {}
            'L'.code -> upper = true
            else -> return true
        }
        if (upper) return false
        tok[0] = ExpatTok.XML_TOK_XML_DECL
        return true
    }

    /** scanPi: [ptr] after "<?". */
    private fun scanPi(b: ByteArray, ptr0: Int, end: Int, nt: XPtr): Int {
        var ptr = ptr0
        val mb = minBytesPerChar
        val tok = IntArray(1)
        val target = ptr
        if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_PARTIAL
        val s0 = nmstrtStep(bt(b, ptr), b, ptr, end, nt)
        if (s0 < 0) return stepTok(s0)
        if (s0 == 0) {
            nt.p = ptr
            return ExpatTok.XML_TOK_INVALID
        }
        ptr += s0
        while (hasChar(ptr, end)) {
            val t = bt(b, ptr)
            val s = nameStep(t, b, ptr, end, nt)
            if (s > 0) {
                ptr += s
                continue
            }
            if (s < 0) return stepTok(s)
            when (t) {
                ExpatTok.BT_S, ExpatTok.BT_CR, ExpatTok.BT_LF -> {
                    if (!checkPiTarget(b, target, ptr, tok)) {
                        nt.p = ptr
                        return ExpatTok.XML_TOK_INVALID
                    }
                    ptr += mb
                    while (hasChar(ptr, end)) {
                        val t2 = bt(b, ptr)
                        val s2 = invalidStep(t2, b, ptr, end, nt)
                        if (s2 > 0) {
                            ptr += s2
                            continue
                        }
                        if (s2 < 0) return stepTok(s2)
                        if (t2 == ExpatTok.BT_QUEST) {
                            ptr += mb
                            if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_PARTIAL
                            if (matches(b, ptr, '>')) {
                                nt.p = ptr + mb
                                return tok[0]
                            }
                        } else ptr += mb
                    }
                    return ExpatTok.XML_TOK_PARTIAL
                }
                ExpatTok.BT_QUEST -> {
                    if (!checkPiTarget(b, target, ptr, tok)) {
                        nt.p = ptr
                        return ExpatTok.XML_TOK_INVALID
                    }
                    ptr += mb
                    if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_PARTIAL
                    if (matches(b, ptr, '>')) {
                        nt.p = ptr + mb
                        return tok[0]
                    }
                    nt.p = ptr
                    return ExpatTok.XML_TOK_INVALID
                }
                else -> {
                    nt.p = ptr
                    return ExpatTok.XML_TOK_INVALID
                }
            }
        }
        return ExpatTok.XML_TOK_PARTIAL
    }

    /** scanCdataSection: [ptr] after "<![". */
    private fun scanCdataSection(b: ByteArray, ptr0: Int, end: Int, nt: XPtr): Int {
        var ptr = ptr0
        if (!hasChars(ptr, end, 6)) return ExpatTok.XML_TOK_PARTIAL
        for (c in "CDATA[") {
            if (!matches(b, ptr, c)) {
                nt.p = ptr
                return ExpatTok.XML_TOK_INVALID
            }
            ptr += minBytesPerChar
        }
        nt.p = ptr
        return ExpatTok.XML_TOK_CDATA_SECT_OPEN
    }

    /** cdataSectionTok */
    open fun cdataSectionTok(b: ByteArray, ptr0: Int, end0: Int, nt: XPtr): Int {
        var ptr = ptr0
        val mb = minBytesPerChar
        if (ptr >= end0) return ExpatTok.XML_TOK_NONE
        val end = wholeChars(ptr, end0)
        if (end < 0) return ExpatTok.XML_TOK_PARTIAL
        val t0 = bt(b, ptr)
        when (t0) {
            ExpatTok.BT_RSQB -> {
                ptr += mb
                if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_PARTIAL
                if (matches(b, ptr, ']')) {
                    ptr += mb
                    if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_PARTIAL
                    if (!matches(b, ptr, '>')) ptr -= mb
                    else {
                        nt.p = ptr + mb
                        return ExpatTok.XML_TOK_CDATA_SECT_CLOSE
                    }
                }
            }
            ExpatTok.BT_CR -> {
                ptr += mb
                if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_PARTIAL
                if (bt(b, ptr) == ExpatTok.BT_LF) ptr += mb
                nt.p = ptr
                return ExpatTok.XML_TOK_DATA_NEWLINE
            }
            ExpatTok.BT_LF -> {
                nt.p = ptr + mb
                return ExpatTok.XML_TOK_DATA_NEWLINE
            }
            else -> {
                val s = invalidStep(t0, b, ptr, end, nt)
                if (s < 0) return stepTok(s)
                ptr += if (s > 0) s else mb
            }
        }
        while (hasChar(ptr, end)) {
            when (val t = bt(b, ptr)) {
                ExpatTok.BT_LEAD2, ExpatTok.BT_LEAD3, ExpatTok.BT_LEAD4 -> {
                    val n = t - 3
                    if (end - ptr < n || isInvalid(b, ptr, n)) {
                        nt.p = ptr
                        return ExpatTok.XML_TOK_DATA_CHARS
                    }
                    ptr += n
                }
                ExpatTok.BT_NONXML, ExpatTok.BT_MALFORM, ExpatTok.BT_TRAIL, ExpatTok.BT_CR, ExpatTok.BT_LF, ExpatTok.BT_RSQB -> {
                    nt.p = ptr
                    return ExpatTok.XML_TOK_DATA_CHARS
                }
                else -> ptr += mb
            }
        }
        nt.p = ptr
        return ExpatTok.XML_TOK_DATA_CHARS
    }

    /** scanEndTag: [ptr] after "</". */
    private fun scanEndTag(b: ByteArray, ptr0: Int, end: Int, nt: XPtr): Int {
        var ptr = ptr0
        val mb = minBytesPerChar
        if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_PARTIAL
        val s0 = nmstrtStep(bt(b, ptr), b, ptr, end, nt)
        if (s0 < 0) return stepTok(s0)
        if (s0 == 0) {
            nt.p = ptr
            return ExpatTok.XML_TOK_INVALID
        }
        ptr += s0
        while (hasChar(ptr, end)) {
            val t = bt(b, ptr)
            val s = nameStep(t, b, ptr, end, nt)
            if (s > 0) {
                ptr += s
                continue
            }
            if (s < 0) return stepTok(s)
            when (t) {
                ExpatTok.BT_S, ExpatTok.BT_CR, ExpatTok.BT_LF -> {
                    ptr += mb
                    while (hasChar(ptr, end)) {
                        when (bt(b, ptr)) {
                            ExpatTok.BT_S, ExpatTok.BT_CR, ExpatTok.BT_LF -> {}
                            ExpatTok.BT_GT -> {
                                nt.p = ptr + mb
                                return ExpatTok.XML_TOK_END_TAG
                            }
                            else -> {
                                nt.p = ptr
                                return ExpatTok.XML_TOK_INVALID
                            }
                        }
                        ptr += mb
                    }
                    return ExpatTok.XML_TOK_PARTIAL
                }
                // (no need to check qname syntax here, since end-tag must match exactly)
                ExpatTok.BT_COLON -> ptr += mb
                ExpatTok.BT_GT -> {
                    nt.p = ptr + mb
                    return ExpatTok.XML_TOK_END_TAG
                }
                else -> {
                    nt.p = ptr
                    return ExpatTok.XML_TOK_INVALID
                }
            }
        }
        return ExpatTok.XML_TOK_PARTIAL
    }

    /** scanHexCharRef: [ptr] after "&#x". */
    private fun scanHexCharRef(b: ByteArray, ptr0: Int, end: Int, nt: XPtr): Int {
        var ptr = ptr0
        val mb = minBytesPerChar
        if (hasChar(ptr, end)) {
            when (bt(b, ptr)) {
                ExpatTok.BT_DIGIT, ExpatTok.BT_HEX -> {}
                else -> {
                    nt.p = ptr
                    return ExpatTok.XML_TOK_INVALID
                }
            }
            ptr += mb
            while (hasChar(ptr, end)) {
                when (bt(b, ptr)) {
                    ExpatTok.BT_DIGIT, ExpatTok.BT_HEX -> {}
                    ExpatTok.BT_SEMI -> {
                        nt.p = ptr + mb
                        return ExpatTok.XML_TOK_CHAR_REF
                    }
                    else -> {
                        nt.p = ptr
                        return ExpatTok.XML_TOK_INVALID
                    }
                }
                ptr += mb
            }
        }
        return ExpatTok.XML_TOK_PARTIAL
    }

    /** scanCharRef: [ptr] after "&#". */
    private fun scanCharRef(b: ByteArray, ptr0: Int, end: Int, nt: XPtr): Int {
        var ptr = ptr0
        val mb = minBytesPerChar
        if (hasChar(ptr, end)) {
            if (matches(b, ptr, 'x')) return scanHexCharRef(b, ptr + mb, end, nt)
            if (bt(b, ptr) != ExpatTok.BT_DIGIT) {
                nt.p = ptr
                return ExpatTok.XML_TOK_INVALID
            }
            ptr += mb
            while (hasChar(ptr, end)) {
                when (bt(b, ptr)) {
                    ExpatTok.BT_DIGIT -> {}
                    ExpatTok.BT_SEMI -> {
                        nt.p = ptr + mb
                        return ExpatTok.XML_TOK_CHAR_REF
                    }
                    else -> {
                        nt.p = ptr
                        return ExpatTok.XML_TOK_INVALID
                    }
                }
                ptr += mb
            }
        }
        return ExpatTok.XML_TOK_PARTIAL
    }

    /** scanRef: [ptr] after "&". */
    private fun scanRef(b: ByteArray, ptr0: Int, end: Int, nt: XPtr): Int {
        var ptr = ptr0
        val mb = minBytesPerChar
        if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_PARTIAL
        val t0 = bt(b, ptr)
        val s0 = nmstrtStep(t0, b, ptr, end, nt)
        if (s0 < 0) return stepTok(s0)
        if (s0 == 0) {
            if (t0 == ExpatTok.BT_NUM) return scanCharRef(b, ptr + mb, end, nt)
            nt.p = ptr
            return ExpatTok.XML_TOK_INVALID
        }
        ptr += s0
        while (hasChar(ptr, end)) {
            val t = bt(b, ptr)
            val s = nameStep(t, b, ptr, end, nt)
            if (s > 0) {
                ptr += s
                continue
            }
            if (s < 0) return stepTok(s)
            if (t == ExpatTok.BT_SEMI) {
                nt.p = ptr + mb
                return ExpatTok.XML_TOK_ENTITY_REF
            }
            nt.p = ptr
            return ExpatTok.XML_TOK_INVALID
        }
        return ExpatTok.XML_TOK_PARTIAL
    }

    /** scanAtts: [ptr] after the first character of an attribute's name. */
    private fun scanAtts(b: ByteArray, ptr0: Int, end: Int, nt: XPtr): Int {
        var ptr = ptr0
        val mb = minBytesPerChar
        var hadColon = false
        while (hasChar(ptr, end)) {
            var t = bt(b, ptr)
            val s = nameStep(t, b, ptr, end, nt)
            if (s > 0) {
                ptr += s
                continue
            }
            if (s < 0) return stepTok(s)
            if (t == ExpatTok.BT_COLON) {
                if (hadColon) {
                    nt.p = ptr
                    return ExpatTok.XML_TOK_INVALID
                }
                hadColon = true
                ptr += mb
                if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_PARTIAL
                val s1 = nmstrtStep(bt(b, ptr), b, ptr, end, nt)
                if (s1 < 0) return stepTok(s1)
                if (s1 == 0) {
                    nt.p = ptr
                    return ExpatTok.XML_TOK_INVALID
                }
                ptr += s1
                continue
            }
            if (t == ExpatTok.BT_S || t == ExpatTok.BT_CR || t == ExpatTok.BT_LF) {
                while (true) {
                    ptr += mb
                    if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_PARTIAL
                    t = bt(b, ptr)
                    if (t == ExpatTok.BT_EQUALS) break
                    if (t != ExpatTok.BT_S && t != ExpatTok.BT_LF && t != ExpatTok.BT_CR) {
                        nt.p = ptr
                        return ExpatTok.XML_TOK_INVALID
                    }
                }
            } else if (t != ExpatTok.BT_EQUALS) {
                nt.p = ptr
                return ExpatTok.XML_TOK_INVALID
            }
            // (BT_EQUALS)
            hadColon = false
            var open: Int
            while (true) {
                ptr += mb
                if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_PARTIAL
                open = bt(b, ptr)
                if (open == ExpatTok.BT_QUOT || open == ExpatTok.BT_APOS) break
                if (open != ExpatTok.BT_S && open != ExpatTok.BT_LF && open != ExpatTok.BT_CR) {
                    nt.p = ptr
                    return ExpatTok.XML_TOK_INVALID
                }
            }
            ptr += mb
            // (in attribute value)
            while (true) {
                if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_PARTIAL
                val t2 = bt(b, ptr)
                if (t2 == open) break
                val s2 = invalidStep(t2, b, ptr, end, nt)
                if (s2 > 0) {
                    ptr += s2
                    continue
                }
                if (s2 < 0) return stepTok(s2)
                when (t2) {
                    ExpatTok.BT_AMP -> {
                        // (scanRef moves ptr, not nextTokPtr, unless the reference is invalid)
                        val r = XPtr()
                        val tok = scanRef(b, ptr + mb, end, r)
                        if (tok <= 0) {
                            if (tok == ExpatTok.XML_TOK_INVALID) nt.p = r.p
                            return tok
                        }
                        ptr = r.p
                    }
                    ExpatTok.BT_LT -> {
                        nt.p = ptr
                        return ExpatTok.XML_TOK_INVALID
                    }
                    else -> ptr += mb
                }
            }
            ptr += mb
            if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_PARTIAL
            when (bt(b, ptr)) {
                ExpatTok.BT_S, ExpatTok.BT_CR, ExpatTok.BT_LF -> {}
                ExpatTok.BT_SOL -> return sol(b, ptr, end, nt, ExpatTok.XML_TOK_EMPTY_ELEMENT_WITH_ATTS)
                ExpatTok.BT_GT -> {
                    nt.p = ptr + mb
                    return ExpatTok.XML_TOK_START_TAG_WITH_ATTS
                }
                else -> {
                    nt.p = ptr
                    return ExpatTok.XML_TOK_INVALID
                }
            }
            // (ptr points to closing quote)
            while (true) {
                ptr += mb
                if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_PARTIAL
                val t3 = bt(b, ptr)
                val s3 = nmstrtStep(t3, b, ptr, end, nt)
                if (s3 < 0) return stepTok(s3)
                if (s3 > 0) {
                    ptr += s3
                    break
                }
                when (t3) {
                    ExpatTok.BT_S, ExpatTok.BT_CR, ExpatTok.BT_LF -> continue
                    ExpatTok.BT_GT -> {
                        nt.p = ptr + mb
                        return ExpatTok.XML_TOK_START_TAG_WITH_ATTS
                    }
                    ExpatTok.BT_SOL -> return sol(b, ptr, end, nt, ExpatTok.XML_TOK_EMPTY_ELEMENT_WITH_ATTS)
                    else -> {
                        nt.p = ptr
                        return ExpatTok.XML_TOK_INVALID
                    }
                }
            }
        }
        return ExpatTok.XML_TOK_PARTIAL
    }

    /** The `sol:` of scanAtts and scanLt: [ptr] at the '/', [tok] where '>' follows. */
    private fun sol(b: ByteArray, ptr0: Int, end: Int, nt: XPtr, tok: Int): Int {
        val ptr = ptr0 + minBytesPerChar
        if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_PARTIAL
        if (!matches(b, ptr, '>')) {
            nt.p = ptr
            return ExpatTok.XML_TOK_INVALID
        }
        nt.p = ptr + minBytesPerChar
        return tok
    }

    /** scanLt: [ptr] after "<". */
    private fun scanLt(b: ByteArray, ptr0: Int, end: Int, nt: XPtr): Int {
        var ptr = ptr0
        val mb = minBytesPerChar
        if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_PARTIAL
        val t0 = bt(b, ptr)
        val s0 = nmstrtStep(t0, b, ptr, end, nt)
        if (s0 < 0) return stepTok(s0)
        if (s0 == 0) {
            when (t0) {
                ExpatTok.BT_EXCL -> {
                    ptr += mb
                    if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_PARTIAL
                    when (bt(b, ptr)) {
                        ExpatTok.BT_MINUS -> return scanComment(b, ptr + mb, end, nt)
                        ExpatTok.BT_LSQB -> return scanCdataSection(b, ptr + mb, end, nt)
                    }
                    nt.p = ptr
                    return ExpatTok.XML_TOK_INVALID
                }
                ExpatTok.BT_QUEST -> return scanPi(b, ptr + mb, end, nt)
                ExpatTok.BT_SOL -> return scanEndTag(b, ptr + mb, end, nt)
                else -> {
                    nt.p = ptr
                    return ExpatTok.XML_TOK_INVALID
                }
            }
        }
        ptr += s0
        var hadColon = false
        // (we have a start-tag)
        while (hasChar(ptr, end)) {
            val t = bt(b, ptr)
            val s = nameStep(t, b, ptr, end, nt)
            if (s > 0) {
                ptr += s
                continue
            }
            if (s < 0) return stepTok(s)
            when (t) {
                ExpatTok.BT_COLON -> {
                    if (hadColon) {
                        nt.p = ptr
                        return ExpatTok.XML_TOK_INVALID
                    }
                    hadColon = true
                    ptr += mb
                    if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_PARTIAL
                    val s1 = nmstrtStep(bt(b, ptr), b, ptr, end, nt)
                    if (s1 < 0) return stepTok(s1)
                    if (s1 == 0) {
                        nt.p = ptr
                        return ExpatTok.XML_TOK_INVALID
                    }
                    ptr += s1
                }
                ExpatTok.BT_S, ExpatTok.BT_CR, ExpatTok.BT_LF -> {
                    ptr += mb
                    while (hasChar(ptr, end)) {
                        val t2 = bt(b, ptr)
                        val s2 = nmstrtStep(t2, b, ptr, end, nt)
                        if (s2 < 0) return stepTok(s2)
                        if (s2 > 0) return scanAtts(b, ptr + s2, end, nt)
                        when (t2) {
                            ExpatTok.BT_GT -> {
                                nt.p = ptr + mb
                                return ExpatTok.XML_TOK_START_TAG_NO_ATTS
                            }
                            ExpatTok.BT_SOL -> return sol(b, ptr, end, nt, ExpatTok.XML_TOK_EMPTY_ELEMENT_NO_ATTS)
                            ExpatTok.BT_S, ExpatTok.BT_CR, ExpatTok.BT_LF -> ptr += mb
                            else -> {
                                nt.p = ptr
                                return ExpatTok.XML_TOK_INVALID
                            }
                        }
                    }
                    return ExpatTok.XML_TOK_PARTIAL
                }
                ExpatTok.BT_GT -> {
                    nt.p = ptr + mb
                    return ExpatTok.XML_TOK_START_TAG_NO_ATTS
                }
                ExpatTok.BT_SOL -> return sol(b, ptr, end, nt, ExpatTok.XML_TOK_EMPTY_ELEMENT_NO_ATTS)
                else -> {
                    nt.p = ptr
                    return ExpatTok.XML_TOK_INVALID
                }
            }
        }
        return ExpatTok.XML_TOK_PARTIAL
    }

    /** contentTok */
    open fun contentTok(b: ByteArray, ptr0: Int, end0: Int, nt: XPtr): Int {
        var ptr = ptr0
        val mb = minBytesPerChar
        if (ptr >= end0) return ExpatTok.XML_TOK_NONE
        val end = wholeChars(ptr, end0)
        if (end < 0) return ExpatTok.XML_TOK_PARTIAL
        val t0 = bt(b, ptr)
        when (t0) {
            ExpatTok.BT_LT -> return scanLt(b, ptr + mb, end, nt)
            ExpatTok.BT_AMP -> return scanRef(b, ptr + mb, end, nt)
            ExpatTok.BT_CR -> {
                ptr += mb
                if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_TRAILING_CR
                if (bt(b, ptr) == ExpatTok.BT_LF) ptr += mb
                nt.p = ptr
                return ExpatTok.XML_TOK_DATA_NEWLINE
            }
            ExpatTok.BT_LF -> {
                nt.p = ptr + mb
                return ExpatTok.XML_TOK_DATA_NEWLINE
            }
            ExpatTok.BT_RSQB -> {
                ptr += mb
                if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_TRAILING_RSQB
                if (matches(b, ptr, ']')) {
                    ptr += mb
                    if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_TRAILING_RSQB
                    if (!matches(b, ptr, '>')) ptr -= mb
                    else {
                        nt.p = ptr
                        return ExpatTok.XML_TOK_INVALID
                    }
                }
            }
            else -> {
                val s = invalidStep(t0, b, ptr, end, nt)
                if (s < 0) return stepTok(s)
                ptr += if (s > 0) s else mb
            }
        }
        while (hasChar(ptr, end)) {
            when (val t = bt(b, ptr)) {
                ExpatTok.BT_LEAD2, ExpatTok.BT_LEAD3, ExpatTok.BT_LEAD4 -> {
                    val n = t - 3
                    if (end - ptr < n || isInvalid(b, ptr, n)) {
                        nt.p = ptr
                        return ExpatTok.XML_TOK_DATA_CHARS
                    }
                    ptr += n
                }
                ExpatTok.BT_RSQB -> {
                    if (hasChars(ptr, end, 2)) {
                        if (!matches(b, ptr + mb, ']')) {
                            ptr += mb
                            continue
                        }
                        if (hasChars(ptr, end, 3)) {
                            if (!matches(b, ptr + 2 * mb, '>')) {
                                ptr += mb
                                continue
                            }
                            nt.p = ptr + 2 * mb
                            return ExpatTok.XML_TOK_INVALID
                        }
                    }
                    nt.p = ptr
                    return ExpatTok.XML_TOK_DATA_CHARS
                }
                ExpatTok.BT_AMP, ExpatTok.BT_LT, ExpatTok.BT_NONXML, ExpatTok.BT_MALFORM, ExpatTok.BT_TRAIL, ExpatTok.BT_CR, ExpatTok.BT_LF -> {
                    nt.p = ptr
                    return ExpatTok.XML_TOK_DATA_CHARS
                }
                else -> ptr += mb
            }
        }
        nt.p = ptr
        return ExpatTok.XML_TOK_DATA_CHARS
    }

    /** scanPercent: [ptr] after "%". */
    private fun scanPercent(b: ByteArray, ptr0: Int, end: Int, nt: XPtr): Int {
        var ptr = ptr0
        val mb = minBytesPerChar
        if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_PARTIAL
        val t0 = bt(b, ptr)
        val s0 = nmstrtStep(t0, b, ptr, end, nt)
        if (s0 < 0) return stepTok(s0)
        if (s0 == 0) {
            nt.p = ptr
            return when (t0) {
                ExpatTok.BT_S, ExpatTok.BT_LF, ExpatTok.BT_CR, ExpatTok.BT_PERCNT -> ExpatTok.XML_TOK_PERCENT
                else -> ExpatTok.XML_TOK_INVALID
            }
        }
        ptr += s0
        while (hasChar(ptr, end)) {
            val t = bt(b, ptr)
            val s = nameStep(t, b, ptr, end, nt)
            if (s > 0) {
                ptr += s
                continue
            }
            if (s < 0) return stepTok(s)
            if (t == ExpatTok.BT_SEMI) {
                nt.p = ptr + mb
                return ExpatTok.XML_TOK_PARAM_ENTITY_REF
            }
            nt.p = ptr
            return ExpatTok.XML_TOK_INVALID
        }
        return ExpatTok.XML_TOK_PARTIAL
    }

    /** scanPoundName: [ptr] after "#". */
    private fun scanPoundName(b: ByteArray, ptr0: Int, end: Int, nt: XPtr): Int {
        var ptr = ptr0
        if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_PARTIAL
        val s0 = nmstrtStep(bt(b, ptr), b, ptr, end, nt)
        if (s0 < 0) return stepTok(s0)
        if (s0 == 0) {
            nt.p = ptr
            return ExpatTok.XML_TOK_INVALID
        }
        ptr += s0
        while (hasChar(ptr, end)) {
            val t = bt(b, ptr)
            val s = nameStep(t, b, ptr, end, nt)
            if (s > 0) {
                ptr += s
                continue
            }
            if (s < 0) return stepTok(s)
            nt.p = ptr
            return when (t) {
                ExpatTok.BT_CR, ExpatTok.BT_LF, ExpatTok.BT_S, ExpatTok.BT_RPAR, ExpatTok.BT_GT, ExpatTok.BT_PERCNT, ExpatTok.BT_VERBAR -> ExpatTok.XML_TOK_POUND_NAME
                else -> ExpatTok.XML_TOK_INVALID
            }
        }
        return -ExpatTok.XML_TOK_POUND_NAME
    }

    /** scanLit: [ptr] after the quote [open] (its type). */
    private fun scanLit(open: Int, b: ByteArray, ptr0: Int, end: Int, nt: XPtr): Int {
        var ptr = ptr0
        val mb = minBytesPerChar
        while (hasChar(ptr, end)) {
            val t = bt(b, ptr)
            val s = invalidStep(t, b, ptr, end, nt)
            if (s > 0) {
                ptr += s
                continue
            }
            if (s < 0) return stepTok(s)
            if (t == ExpatTok.BT_QUOT || t == ExpatTok.BT_APOS) {
                ptr += mb
                if (t != open) continue
                if (!hasChar(ptr, end)) return -ExpatTok.XML_TOK_LITERAL
                nt.p = ptr
                return when (bt(b, ptr)) {
                    ExpatTok.BT_S, ExpatTok.BT_CR, ExpatTok.BT_LF, ExpatTok.BT_GT, ExpatTok.BT_PERCNT, ExpatTok.BT_LSQB -> ExpatTok.XML_TOK_LITERAL
                    else -> ExpatTok.XML_TOK_INVALID
                }
            }
            ptr += mb
        }
        return ExpatTok.XML_TOK_PARTIAL
    }

    /** prologTok */
    open fun prologTok(b: ByteArray, ptr0: Int, end0: Int, nt: XPtr): Int {
        var ptr = ptr0
        val mb = minBytesPerChar
        if (ptr >= end0) return ExpatTok.XML_TOK_NONE
        val end = wholeChars(ptr, end0)
        if (end < 0) return ExpatTok.XML_TOK_PARTIAL
        var tok: Int
        when (val t0 = bt(b, ptr)) {
            ExpatTok.BT_QUOT -> return scanLit(ExpatTok.BT_QUOT, b, ptr + mb, end, nt)
            ExpatTok.BT_APOS -> return scanLit(ExpatTok.BT_APOS, b, ptr + mb, end, nt)
            ExpatTok.BT_LT -> {
                ptr += mb
                if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_PARTIAL
                when (bt(b, ptr)) {
                    ExpatTok.BT_EXCL -> return scanDecl(b, ptr + mb, end, nt)
                    ExpatTok.BT_QUEST -> return scanPi(b, ptr + mb, end, nt)
                    ExpatTok.BT_NMSTRT, ExpatTok.BT_HEX, ExpatTok.BT_NONASCII, ExpatTok.BT_LEAD2, ExpatTok.BT_LEAD3, ExpatTok.BT_LEAD4 -> {
                        nt.p = ptr - mb
                        return ExpatTok.XML_TOK_INSTANCE_START
                    }
                }
                nt.p = ptr
                return ExpatTok.XML_TOK_INVALID
            }
            ExpatTok.BT_CR, ExpatTok.BT_S, ExpatTok.BT_LF -> {
                if (t0 == ExpatTok.BT_CR && ptr + mb == end) {
                    nt.p = end
                    // (indicate that this might be part of a CR/LF pair)
                    return -ExpatTok.XML_TOK_PROLOG_S
                }
                while (true) {
                    ptr += mb
                    if (!hasChar(ptr, end)) break
                    when (bt(b, ptr)) {
                        ExpatTok.BT_S, ExpatTok.BT_LF -> {}
                        // (don't split CR/LF pair)
                        ExpatTok.BT_CR -> if (ptr + mb == end) {
                            nt.p = ptr
                            return ExpatTok.XML_TOK_PROLOG_S
                        }
                        else -> {
                            nt.p = ptr
                            return ExpatTok.XML_TOK_PROLOG_S
                        }
                    }
                }
                nt.p = ptr
                return ExpatTok.XML_TOK_PROLOG_S
            }
            ExpatTok.BT_PERCNT -> return scanPercent(b, ptr + mb, end, nt)
            ExpatTok.BT_COMMA -> {
                nt.p = ptr + mb
                return ExpatTok.XML_TOK_COMMA
            }
            ExpatTok.BT_LSQB -> {
                nt.p = ptr + mb
                return ExpatTok.XML_TOK_OPEN_BRACKET
            }
            ExpatTok.BT_RSQB -> {
                ptr += mb
                if (!hasChar(ptr, end)) return -ExpatTok.XML_TOK_CLOSE_BRACKET
                if (matches(b, ptr, ']')) {
                    if (!hasChars(ptr, end, 2)) return ExpatTok.XML_TOK_PARTIAL
                    if (matches(b, ptr + mb, '>')) {
                        nt.p = ptr + 2 * mb
                        return ExpatTok.XML_TOK_COND_SECT_CLOSE
                    }
                }
                nt.p = ptr
                return ExpatTok.XML_TOK_CLOSE_BRACKET
            }
            ExpatTok.BT_LPAR -> {
                nt.p = ptr + mb
                return ExpatTok.XML_TOK_OPEN_PAREN
            }
            ExpatTok.BT_RPAR -> {
                ptr += mb
                if (!hasChar(ptr, end)) return -ExpatTok.XML_TOK_CLOSE_PAREN
                when (bt(b, ptr)) {
                    ExpatTok.BT_AST -> {
                        nt.p = ptr + mb
                        return ExpatTok.XML_TOK_CLOSE_PAREN_ASTERISK
                    }
                    ExpatTok.BT_QUEST -> {
                        nt.p = ptr + mb
                        return ExpatTok.XML_TOK_CLOSE_PAREN_QUESTION
                    }
                    ExpatTok.BT_PLUS -> {
                        nt.p = ptr + mb
                        return ExpatTok.XML_TOK_CLOSE_PAREN_PLUS
                    }
                    ExpatTok.BT_CR, ExpatTok.BT_LF, ExpatTok.BT_S, ExpatTok.BT_GT, ExpatTok.BT_COMMA, ExpatTok.BT_VERBAR, ExpatTok.BT_RPAR -> {
                        nt.p = ptr
                        return ExpatTok.XML_TOK_CLOSE_PAREN
                    }
                }
                nt.p = ptr
                return ExpatTok.XML_TOK_INVALID
            }
            ExpatTok.BT_VERBAR -> {
                nt.p = ptr + mb
                return ExpatTok.XML_TOK_OR
            }
            ExpatTok.BT_GT -> {
                nt.p = ptr + mb
                return ExpatTok.XML_TOK_DECL_CLOSE
            }
            ExpatTok.BT_NUM -> return scanPoundName(b, ptr + mb, end, nt)
            ExpatTok.BT_LEAD2, ExpatTok.BT_LEAD3, ExpatTok.BT_LEAD4 -> {
                val n = t0 - 3
                if (end - ptr < n) return ExpatTok.XML_TOK_PARTIAL_CHAR
                if (isInvalid(b, ptr, n)) {
                    nt.p = ptr
                    return ExpatTok.XML_TOK_INVALID
                }
                if (isNmstrt(b, ptr, n)) {
                    ptr += n
                    tok = ExpatTok.XML_TOK_NAME
                } else if (isName(b, ptr, n)) {
                    ptr += n
                    tok = ExpatTok.XML_TOK_NMTOKEN
                } else {
                    nt.p = ptr
                    return ExpatTok.XML_TOK_INVALID
                }
            }
            ExpatTok.BT_NMSTRT, ExpatTok.BT_HEX -> {
                tok = ExpatTok.XML_TOK_NAME
                ptr += mb
            }
            ExpatTok.BT_DIGIT, ExpatTok.BT_NAME, ExpatTok.BT_MINUS, ExpatTok.BT_COLON -> {
                tok = ExpatTok.XML_TOK_NMTOKEN
                ptr += mb
            }
            ExpatTok.BT_NONASCII -> {
                if (nmstrtMin(b, ptr)) {
                    ptr += mb
                    tok = ExpatTok.XML_TOK_NAME
                } else if (nameMin(b, ptr)) {
                    ptr += mb
                    tok = ExpatTok.XML_TOK_NMTOKEN
                } else {
                    nt.p = ptr
                    return ExpatTok.XML_TOK_INVALID
                }
            }
            else -> {
                nt.p = ptr
                return ExpatTok.XML_TOK_INVALID
            }
        }
        return prologName(b, ptr, end, nt, tok)
    }

    /** The rest of a name or name token prologTok began: [tok] what it is so far. */
    private fun prologName(b: ByteArray, ptr0: Int, end: Int, nt: XPtr, tok0: Int): Int {
        var ptr = ptr0
        var tok = tok0
        val mb = minBytesPerChar
        while (hasChar(ptr, end)) {
            val t = bt(b, ptr)
            val s = nameStep(t, b, ptr, end, nt)
            if (s > 0) {
                ptr += s
                continue
            }
            if (s < 0) return stepTok(s)
            when (t) {
                ExpatTok.BT_GT, ExpatTok.BT_RPAR, ExpatTok.BT_COMMA, ExpatTok.BT_VERBAR, ExpatTok.BT_LSQB, ExpatTok.BT_PERCNT, ExpatTok.BT_S, ExpatTok.BT_CR, ExpatTok.BT_LF -> {
                    nt.p = ptr
                    return tok
                }
                ExpatTok.BT_COLON -> {
                    ptr += mb
                    when (tok) {
                        ExpatTok.XML_TOK_NAME -> {
                            if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_PARTIAL
                            tok = ExpatTok.XML_TOK_PREFIXED_NAME
                            val s1 = nameStep(bt(b, ptr), b, ptr, end, nt)
                            if (s1 < 0) return stepTok(s1)
                            if (s1 > 0) ptr += s1 else tok = ExpatTok.XML_TOK_NMTOKEN
                        }
                        ExpatTok.XML_TOK_PREFIXED_NAME -> tok = ExpatTok.XML_TOK_NMTOKEN
                    }
                }
                ExpatTok.BT_PLUS, ExpatTok.BT_AST, ExpatTok.BT_QUEST -> {
                    if (tok == ExpatTok.XML_TOK_NMTOKEN) {
                        nt.p = ptr
                        return ExpatTok.XML_TOK_INVALID
                    }
                    nt.p = ptr + mb
                    return when (t) {
                        ExpatTok.BT_PLUS -> ExpatTok.XML_TOK_NAME_PLUS
                        ExpatTok.BT_AST -> ExpatTok.XML_TOK_NAME_ASTERISK
                        else -> ExpatTok.XML_TOK_NAME_QUESTION
                    }
                }
                else -> {
                    nt.p = ptr
                    return ExpatTok.XML_TOK_INVALID
                }
            }
        }
        return -tok
    }

    /** attributeValueTok */
    fun attributeValueTok(b: ByteArray, ptr0: Int, end: Int, nt: XPtr): Int {
        var ptr = ptr0
        val mb = minBytesPerChar
        if (ptr >= end) return ExpatTok.XML_TOK_NONE
        if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_PARTIAL
        val start = ptr
        while (hasChar(ptr, end)) {
            when (val t = bt(b, ptr)) {
                // (the encoding has already been validated)
                ExpatTok.BT_LEAD2, ExpatTok.BT_LEAD3, ExpatTok.BT_LEAD4 -> ptr += t - 3
                ExpatTok.BT_AMP -> {
                    if (ptr == start) return scanRef(b, ptr + mb, end, nt)
                    nt.p = ptr
                    return ExpatTok.XML_TOK_DATA_CHARS
                }
                ExpatTok.BT_LT -> {
                    // (this is for inside entity references)
                    nt.p = ptr
                    return ExpatTok.XML_TOK_INVALID
                }
                ExpatTok.BT_LF -> {
                    if (ptr == start) {
                        nt.p = ptr + mb
                        return ExpatTok.XML_TOK_DATA_NEWLINE
                    }
                    nt.p = ptr
                    return ExpatTok.XML_TOK_DATA_CHARS
                }
                ExpatTok.BT_CR -> {
                    if (ptr == start) {
                        ptr += mb
                        if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_TRAILING_CR
                        if (bt(b, ptr) == ExpatTok.BT_LF) ptr += mb
                        nt.p = ptr
                        return ExpatTok.XML_TOK_DATA_NEWLINE
                    }
                    nt.p = ptr
                    return ExpatTok.XML_TOK_DATA_CHARS
                }
                ExpatTok.BT_S -> {
                    if (ptr == start) {
                        nt.p = ptr + mb
                        return ExpatTok.XML_TOK_ATTRIBUTE_VALUE_S
                    }
                    nt.p = ptr
                    return ExpatTok.XML_TOK_DATA_CHARS
                }
                else -> ptr += mb
            }
        }
        nt.p = ptr
        return ExpatTok.XML_TOK_DATA_CHARS
    }

    /** entityValueTok */
    fun entityValueTok(b: ByteArray, ptr0: Int, end: Int, nt: XPtr): Int {
        var ptr = ptr0
        val mb = minBytesPerChar
        if (ptr >= end) return ExpatTok.XML_TOK_NONE
        if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_PARTIAL
        val start = ptr
        while (hasChar(ptr, end)) {
            when (val t = bt(b, ptr)) {
                ExpatTok.BT_LEAD2, ExpatTok.BT_LEAD3, ExpatTok.BT_LEAD4 -> ptr += t - 3
                ExpatTok.BT_AMP -> {
                    if (ptr == start) return scanRef(b, ptr + mb, end, nt)
                    nt.p = ptr
                    return ExpatTok.XML_TOK_DATA_CHARS
                }
                ExpatTok.BT_PERCNT -> {
                    if (ptr == start) {
                        val tok = scanPercent(b, ptr + mb, end, nt)
                        return if (tok == ExpatTok.XML_TOK_PERCENT) ExpatTok.XML_TOK_INVALID else tok
                    }
                    nt.p = ptr
                    return ExpatTok.XML_TOK_DATA_CHARS
                }
                ExpatTok.BT_LF -> {
                    if (ptr == start) {
                        nt.p = ptr + mb
                        return ExpatTok.XML_TOK_DATA_NEWLINE
                    }
                    nt.p = ptr
                    return ExpatTok.XML_TOK_DATA_CHARS
                }
                ExpatTok.BT_CR -> {
                    if (ptr == start) {
                        ptr += mb
                        if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_TRAILING_CR
                        if (bt(b, ptr) == ExpatTok.BT_LF) ptr += mb
                        nt.p = ptr
                        return ExpatTok.XML_TOK_DATA_NEWLINE
                    }
                    nt.p = ptr
                    return ExpatTok.XML_TOK_DATA_CHARS
                }
                else -> ptr += mb
            }
        }
        nt.p = ptr
        return ExpatTok.XML_TOK_DATA_CHARS
    }

    /** ignoreSectionTok */
    open fun ignoreSectionTok(b: ByteArray, ptr0: Int, end0: Int, nt: XPtr): Int {
        var ptr = ptr0
        val mb = minBytesPerChar
        var level = 0
        var end = end0
        if (mb > 1) {
            val n = end - ptr
            if (n and (mb - 1) != 0) end = ptr + (n and (mb - 1).inv())
        }
        while (hasChar(ptr, end)) {
            val t = bt(b, ptr)
            val s = invalidStep(t, b, ptr, end, nt)
            if (s > 0) {
                ptr += s
                continue
            }
            if (s < 0) return stepTok(s)
            when (t) {
                ExpatTok.BT_LT -> {
                    ptr += mb
                    if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_PARTIAL
                    if (matches(b, ptr, '!')) {
                        ptr += mb
                        if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_PARTIAL
                        if (matches(b, ptr, '[')) {
                            ++level
                            ptr += mb
                        }
                    }
                }
                ExpatTok.BT_RSQB -> {
                    ptr += mb
                    if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_PARTIAL
                    if (matches(b, ptr, ']')) {
                        ptr += mb
                        if (!hasChar(ptr, end)) return ExpatTok.XML_TOK_PARTIAL
                        if (matches(b, ptr, '>')) {
                            ptr += mb
                            if (level == 0) {
                                nt.p = ptr
                                return ExpatTok.XML_TOK_IGNORE_SECT
                            }
                            --level
                        }
                    }
                }
                else -> ptr += mb
            }
        }
        return ExpatTok.XML_TOK_PARTIAL
    }

    /** isPublicId: whether the literal [ptr, end) (its quotes too) is a public id; else [bad] set to the character. */
    fun isPublicId(b: ByteArray, ptr0: Int, end0: Int, bad: XPtr): Boolean {
        val mb = minBytesPerChar
        var ptr = ptr0 + mb
        val end = end0 - mb
        while (hasChar(ptr, end)) {
            when (bt(b, ptr)) {
                ExpatTok.BT_DIGIT, ExpatTok.BT_HEX, ExpatTok.BT_MINUS, ExpatTok.BT_APOS, ExpatTok.BT_LPAR, ExpatTok.BT_RPAR, ExpatTok.BT_PLUS,
                ExpatTok.BT_COMMA, ExpatTok.BT_SOL, ExpatTok.BT_EQUALS, ExpatTok.BT_QUEST, ExpatTok.BT_CR, ExpatTok.BT_LF, ExpatTok.BT_SEMI,
                ExpatTok.BT_EXCL, ExpatTok.BT_AST, ExpatTok.BT_PERCNT, ExpatTok.BT_NUM, ExpatTok.BT_COLON -> {}
                ExpatTok.BT_S -> if (matches(b, ptr, '\t')) {
                    bad.p = ptr
                    return false
                }
                ExpatTok.BT_NAME, ExpatTok.BT_NMSTRT -> if (ascii(b, ptr) and 0x7F.inv() != 0 && !publicIdOther(b, ptr)) {
                    bad.p = ptr
                    return false
                }
                else -> if (!publicIdOther(b, ptr)) {
                    bad.p = ptr
                    return false
                }
            }
            ptr += mb
        }
        return true
    }

    private fun publicIdOther(b: ByteArray, ptr: Int): Boolean {
        val c = ascii(b, ptr)
        return c == 0x24 || c == 0x40
    }

    /**
     * getAtts: the attributes of a well-formed start-tag at [ptr]: how many; the first [atts]' size of them into
     * [atts].
     */
    fun getAtts(b: ByteArray, ptr0: Int, atts: Array<XAttribute>): Int {
        val mb = minBytesPerChar
        val attsMax = atts.size
        // (other 0, inName 1, inValue 2)
        var state = 1
        var nAtts = 0
        var open = 0
        var ptr = ptr0 + mb
        while (true) {
            when (val t = bt(b, ptr)) {
                ExpatTok.BT_LEAD2, ExpatTok.BT_LEAD3, ExpatTok.BT_LEAD4, ExpatTok.BT_NONASCII, ExpatTok.BT_NMSTRT, ExpatTok.BT_HEX -> {
                    if (state == 0) {
                        if (nAtts < attsMax) {
                            atts[nAtts].name = ptr
                            atts[nAtts].normalized = true
                        }
                        state = 1
                    }
                    if (t <= ExpatTok.BT_LEAD4) ptr += t - 3 - mb
                }
                ExpatTok.BT_QUOT, ExpatTok.BT_APOS -> {
                    if (state != 2) {
                        if (nAtts < attsMax) atts[nAtts].valuePtr = ptr + mb
                        state = 2
                        open = t
                    } else if (open == t) {
                        state = 0
                        if (nAtts < attsMax) atts[nAtts].valueEnd = ptr
                        nAtts++
                    }
                }
                ExpatTok.BT_AMP -> if (nAtts < attsMax) atts[nAtts].normalized = false
                ExpatTok.BT_S -> {
                    if (state == 1) state = 0
                    else if (state == 2 && nAtts < attsMax && atts[nAtts].normalized &&
                        (ptr == atts[nAtts].valuePtr || ascii(b, ptr) != 0x20 || ascii(b, ptr + mb) == 0x20 || bt(b, ptr + mb) == open)) {
                        atts[nAtts].normalized = false
                    }
                }
                // (this case ensures that the first attribute name is counted; apart from that we could just change
                // state on the quote)
                ExpatTok.BT_CR, ExpatTok.BT_LF -> {
                    if (state == 1) state = 0
                    else if (state == 2 && nAtts < attsMax) atts[nAtts].normalized = false
                }
                ExpatTok.BT_GT, ExpatTok.BT_SOL -> if (state != 2) return nAtts
            }
            ptr += mb
        }
    }

    /** charRefNumber: the character the reference "&#...;" at [ptr] is, or -1. */
    fun charRefNumber(b: ByteArray, ptr0: Int): Int {
        val mb = minBytesPerChar
        var result = 0
        var ptr = ptr0 + 2 * mb
        if (matches(b, ptr, 'x')) {
            ptr += mb
            while (!matches(b, ptr, ';')) {
                val c = ascii(b, ptr)
                when (c) {
                    in '0'.code..'9'.code -> result = (result shl 4) or (c - '0'.code)
                    in 'A'.code..'F'.code -> result = (result shl 4) + 10 + (c - 'A'.code)
                    in 'a'.code..'f'.code -> result = (result shl 4) + 10 + (c - 'a'.code)
                }
                if (result >= 0x110000) return -1
                ptr += mb
            }
        } else {
            while (!matches(b, ptr, ';')) {
                val c = ascii(b, ptr)
                result *= 10
                result += c - '0'.code
                if (result >= 0x110000) return -1
                ptr += mb
            }
        }
        return ExpatTok.checkCharRefNumber(result)
    }

    /** predefinedEntityName: the character lt, gt, amp, quot or apos at [ptr, end) names, or 0. */
    fun predefinedEntityName(b: ByteArray, ptr0: Int, end: Int): Int {
        var ptr = ptr0
        val mb = minBytesPerChar
        when ((end - ptr) / mb) {
            2 -> if (matches(b, ptr + mb, 't')) {
                when (ascii(b, ptr)) {
                    'l'.code -> return '<'.code
                    'g'.code -> return '>'.code
                }
            }
            3 -> if (matches(b, ptr, 'a')) {
                ptr += mb
                if (matches(b, ptr, 'm')) {
                    ptr += mb
                    if (matches(b, ptr, 'p')) return '&'.code
                }
            }
            4 -> when (ascii(b, ptr)) {
                'q'.code -> {
                    ptr += mb
                    if (matches(b, ptr, 'u')) {
                        ptr += mb
                        if (matches(b, ptr, 'o')) {
                            ptr += mb
                            if (matches(b, ptr, 't')) return '"'.code
                        }
                    }
                }
                'a'.code -> {
                    ptr += mb
                    if (matches(b, ptr, 'p')) {
                        ptr += mb
                        if (matches(b, ptr, 'o')) {
                            ptr += mb
                            if (matches(b, ptr, 's')) return '\''.code
                        }
                    }
                }
            }
        }
        return 0
    }

    /** nameMatchesAscii: whether [ptr1, end1) is the ASCII [s]. */
    fun nameMatchesAscii(b: ByteArray, ptr1: Int, end1: Int, s: String): Boolean {
        var p = ptr1
        for (c in s) {
            if (end1 - p < minBytesPerChar) return false
            if (!matches(b, p, c)) return false
            p += minBytesPerChar
        }
        return p == end1
    }

    /** nameLength: the bytes of the name at [ptr]. */
    fun nameLength(b: ByteArray, ptr0: Int): Int {
        var ptr = ptr0
        while (true) {
            when (val t = bt(b, ptr)) {
                ExpatTok.BT_LEAD2, ExpatTok.BT_LEAD3, ExpatTok.BT_LEAD4 -> ptr += t - 3
                ExpatTok.BT_NONASCII, ExpatTok.BT_NMSTRT, ExpatTok.BT_COLON, ExpatTok.BT_HEX, ExpatTok.BT_DIGIT, ExpatTok.BT_NAME, ExpatTok.BT_MINUS -> ptr += minBytesPerChar
                else -> return ptr - ptr0
            }
        }
    }

    /** skipS: past the white space at [ptr]. */
    fun skipS(b: ByteArray, ptr0: Int): Int {
        var ptr = ptr0
        while (true) {
            when (bt(b, ptr)) {
                ExpatTok.BT_LF, ExpatTok.BT_CR, ExpatTok.BT_S -> ptr += minBytesPerChar
                else -> return ptr
            }
        }
    }

    /** updatePosition: [pos] moved over [ptr, end). */
    open fun updatePosition(b: ByteArray, ptr0: Int, end: Int, pos: XPosition) {
        var ptr = ptr0
        val mb = minBytesPerChar
        while (end - ptr >= mb) {
            when (val t = bt(b, ptr)) {
                ExpatTok.BT_LEAD2, ExpatTok.BT_LEAD3, ExpatTok.BT_LEAD4 -> {
                    ptr += t - 3
                    pos.columnNumber++
                }
                ExpatTok.BT_LF -> {
                    pos.columnNumber = 0
                    pos.lineNumber++
                    ptr += mb
                }
                ExpatTok.BT_CR -> {
                    pos.lineNumber++
                    ptr += mb
                    if (end - ptr >= mb && bt(b, ptr) == ExpatTok.BT_LF) ptr += mb
                    pos.columnNumber = 0
                }
                else -> {
                    ptr += mb
                    pos.columnNumber++
                }
            }
        }
    }

    private companion object {
        const val STEP_PARTIAL_CHAR = -1
        const val STEP_INVALID = -2
    }
}

/** A normal_encoding of one byte a character (MINBPC 1): its byte types [type]. */
abstract class NormalEnc(type: ByteArray, isUtf8: Boolean) : ExpatEnc(type, 1, isUtf8, false) {
    override fun bt(b: ByteArray, p: Int): Int = type[b[p].toInt() and 0xFF].toInt()
    override fun ascii(b: ByteArray, p: Int): Int = b[p].toInt()
    override fun matches(b: ByteArray, p: Int, c: Char): Boolean = b[p].toInt() == c.code
}

/** utf8_encoding (and its _ns and internal_ variants): UTF-8, its multi-byte characters checked. */
class Utf8Enc(type: ByteArray) : NormalEnc(type, true) {
    override fun isName(b: ByteArray, p: Int, n: Int): Boolean = when (n) {
        2 -> ExpatTok.utf8Naming2(ExpatTok.NAME_PAGES, b, p)
        3 -> ExpatTok.utf8Naming3(ExpatTok.NAME_PAGES, b, p)
        else -> false
    }

    override fun isNmstrt(b: ByteArray, p: Int, n: Int): Boolean = when (n) {
        2 -> ExpatTok.utf8Naming2(ExpatTok.NMSTRT_PAGES, b, p)
        3 -> ExpatTok.utf8Naming3(ExpatTok.NMSTRT_PAGES, b, p)
        else -> false
    }

    override fun isInvalid(b: ByteArray, p: Int, n: Int): Boolean = ExpatTok.utf8Invalid(b, p, n)

    /** utf8_toUtf8: as many whole characters as fit. */
    override fun toUtf8(from: ByteArray, fromP: XPtr, fromLim0: Int, to: ByteArray, toP: XPtr, toLim: Int): Int {
        var inputIncomplete = false
        var outputExhausted = false
        var fromLim = fromLim0
        // (avoid copying partial characters, due to limited space)
        if (fromLim - fromP.p > toLim - toP.p) {
            fromLim = fromP.p + (toLim - toP.p)
            outputExhausted = true
        }
        // (avoid copying partial characters, from incomplete input)
        val before = fromLim
        fromLim = trimToCompleteUtf8(from, fromP.p, fromLim)
        if (fromLim < before) inputIncomplete = true
        val n = fromLim - fromP.p
        System.arraycopy(from, fromP.p, to, toP.p, n)
        fromP.p += n
        toP.p += n
        return if (outputExhausted) ExpatTok.XML_CONVERT_OUTPUT_EXHAUSTED
        else if (inputIncomplete) ExpatTok.XML_CONVERT_INPUT_INCOMPLETE else ExpatTok.XML_CONVERT_COMPLETED
    }

    companion object {
        /** _INTERNAL_trim_to_complete_utf8_characters: [fromLim] moved back to the end of a whole character. */
        @JvmStatic
        fun trimToCompleteUtf8(b: ByteArray, from: Int, fromLim0: Int): Int {
            var fromLim = fromLim0
            var walked = 0
            while (fromLim > from) {
                val prev = b[fromLim - 1].toInt() and 0xFF
                if (prev and 0xF8 == 0xF0) {
                    if (walked + 1 >= 4) {
                        fromLim += 4 - 1
                        break
                    } else walked = 0
                } else if (prev and 0xF0 == 0xE0) {
                    if (walked + 1 >= 3) {
                        fromLim += 3 - 1
                        break
                    } else walked = 0
                } else if (prev and 0xE0 == 0xC0) {
                    if (walked + 1 >= 2) {
                        fromLim += 2 - 1
                        break
                    } else walked = 0
                } else if (prev and 0x80 == 0) break
                fromLim--
                walked++
            }
            return fromLim
        }
    }
}

/** latin1_encoding: ISO-8859-1. */
class Latin1Enc(type: ByteArray) : NormalEnc(type, false) {
    override fun toUtf8(from: ByteArray, fromP: XPtr, fromLim: Int, to: ByteArray, toP: XPtr, toLim: Int): Int {
        while (true) {
            if (fromP.p == fromLim) return ExpatTok.XML_CONVERT_COMPLETED
            val c = from[fromP.p].toInt() and 0xFF
            if (c and 0x80 != 0) {
                if (toLim - toP.p < 2) return ExpatTok.XML_CONVERT_OUTPUT_EXHAUSTED
                to[toP.p++] = ((c shr 6) or 0xC0).toByte()
                to[toP.p++] = ((c and 0x3F) or 0x80).toByte()
                fromP.p++
            } else {
                if (toP.p == toLim) return ExpatTok.XML_CONVERT_OUTPUT_EXHAUSTED
                to[toP.p++] = from[fromP.p++]
            }
        }
    }
}

/** ascii_encoding: US-ASCII (a byte from 0x80 BT_NONXML). */
class AsciiEnc(type: ByteArray) : NormalEnc(type, true) {
    override fun toUtf8(from: ByteArray, fromP: XPtr, fromLim: Int, to: ByteArray, toP: XPtr, toLim: Int): Int {
        while (fromP.p < fromLim && toP.p < toLim) to[toP.p++] = from[fromP.p++]
        return if (toP.p == toLim && fromP.p < fromLim) ExpatTok.XML_CONVERT_OUTPUT_EXHAUSTED else ExpatTok.XML_CONVERT_COMPLETED
    }
}

/** unknown_encoding: a byte map an unknown-encoding handler gave, each byte's UTF-8 in [utf8] (malformed: a 0 byte). */
class UnknownEnc(type: ByteArray, @JvmField val utf8: Array<ByteArray>) : NormalEnc(type, false) {
    override fun toUtf8(from: ByteArray, fromP: XPtr, fromLim: Int, to: ByteArray, toP: XPtr, toLim: Int): Int {
        while (true) {
            if (fromP.p == fromLim) return ExpatTok.XML_CONVERT_COMPLETED
            val u = utf8[from[fromP.p].toInt() and 0xFF]
            if (u.size > toLim - toP.p) return ExpatTok.XML_CONVERT_OUTPUT_EXHAUSTED
            fromP.p++
            u.copyInto(to, toP.p)
            toP.p += u.size
        }
    }
}

/** little2_encoding, big2_encoding: UTF-16 (MINBPC 2), a character below U+0100 typed by [type]. */
class Utf16Enc(type: ByteArray, @JvmField val little: Boolean) : ExpatEnc(type, 2, false, false) {
    private fun lo(b: ByteArray, p: Int) = b[if (little) p else p + 1].toInt() and 0xFF
    private fun hi(b: ByteArray, p: Int) = b[if (little) p + 1 else p].toInt() and 0xFF

    override fun bt(b: ByteArray, p: Int): Int {
        val h = hi(b, p)
        return if (h == 0) type[lo(b, p)].toInt() else ExpatTok.unicodeByteType(h, lo(b, p))
    }

    override fun ascii(b: ByteArray, p: Int): Int = if (hi(b, p) == 0) b[if (little) p else p + 1].toInt() else -1
    override fun matches(b: ByteArray, p: Int, c: Char): Boolean = hi(b, p) == 0 && lo(b, p) == c.code
    override fun nameMin(b: ByteArray, p: Int): Boolean = ExpatTok.ucs2Naming(ExpatTok.NAME_PAGES, hi(b, p), lo(b, p))
    override fun nmstrtMin(b: ByteArray, p: Int): Boolean = ExpatTok.ucs2Naming(ExpatTok.NMSTRT_PAGES, hi(b, p), lo(b, p))

    /** DEFINE_UTF16_TO_UTF8 */
    override fun toUtf8(from: ByteArray, fromP: XPtr, fromLim0: Int, to: ByteArray, toP: XPtr, toLim: Int): Int {
        var f = fromP.p
        // (shrink to even)
        val fromLim = f + (((fromLim0 - f) shr 1) shl 1)
        while (f < fromLim) {
            val lo = lo(from, f)
            val hi = hi(from, f)
            if (hi == 0 && lo < 0x80) {
                if (toP.p == toLim) {
                    fromP.p = f
                    return ExpatTok.XML_CONVERT_OUTPUT_EXHAUSTED
                }
                to[toP.p++] = lo.toByte()
            } else if (hi < 8) {
                if (toLim - toP.p < 2) {
                    fromP.p = f
                    return ExpatTok.XML_CONVERT_OUTPUT_EXHAUSTED
                }
                to[toP.p++] = ((lo shr 6) or (hi shl 2) or 0xC0).toByte()
                to[toP.p++] = ((lo and 0x3F) or 0x80).toByte()
            } else if (hi in 0xD8..0xDB) {
                if (toLim - toP.p < 4) {
                    fromP.p = f
                    return ExpatTok.XML_CONVERT_OUTPUT_EXHAUSTED
                }
                if (fromLim - f < 4) {
                    fromP.p = f
                    return ExpatTok.XML_CONVERT_INPUT_INCOMPLETE
                }
                val plane = (((hi and 0x3) shl 2) or ((lo shr 6) and 0x3)) + 1
                to[toP.p++] = ((plane shr 2) or 0xF0).toByte()
                to[toP.p++] = (((lo shr 2) and 0xF) or ((plane and 0x3) shl 4) or 0x80).toByte()
                f += 2
                val lo2 = lo(from, f)
                to[toP.p++] = (((lo and 0x3) shl 4) or ((hi(from, f) and 0x3) shl 2) or (lo2 shr 6) or 0x80).toByte()
                to[toP.p++] = ((lo2 and 0x3F) or 0x80).toByte()
            } else {
                if (toLim - toP.p < 3) {
                    fromP.p = f
                    return ExpatTok.XML_CONVERT_OUTPUT_EXHAUSTED
                }
                // (16 bits divided 4, 6, 6 amongst 3 bytes)
                to[toP.p++] = ((hi shr 4) or 0xE0).toByte()
                to[toP.p++] = (((hi and 0xF) shl 2) or (lo shr 6) or 0x80).toByte()
                to[toP.p++] = ((lo and 0x3F) or 0x80).toByte()
            }
            f += 2
        }
        fromP.p = f
        return if (f < fromLim) ExpatTok.XML_CONVERT_INPUT_INCOMPLETE else ExpatTok.XML_CONVERT_COMPLETED
    }
}

/**
 * INIT_ENCODING: what scans the first bytes of an entity to find its encoding (initScan), from the encoding given
 * by name ([index], getEncodingIndex's); the encoding it finds goes to [found]. Otherwise it is UTF-8 (C leaves the
 * rest of the struct unset; updatePosition is initUpdatePosition, UTF-8's).
 */
class InitEnc(@JvmField val ns: Boolean, @JvmField val found: (ExpatEnc) -> Unit) : ExpatEnc(ExpatTok.UTF8.type, 1, true, false) {
    @JvmField var index = ExpatTok.NO_ENC
    private val utf8 = if (ns) ExpatTok.UTF8_NS else ExpatTok.UTF8

    override fun bt(b: ByteArray, p: Int): Int = utf8.bt(b, p)
    override fun ascii(b: ByteArray, p: Int): Int = utf8.ascii(b, p)
    override fun matches(b: ByteArray, p: Int, c: Char): Boolean = utf8.matches(b, p, c)
    override fun isName(b: ByteArray, p: Int, n: Int): Boolean = utf8.isName(b, p, n)
    override fun isNmstrt(b: ByteArray, p: Int, n: Int): Boolean = utf8.isNmstrt(b, p, n)
    override fun isInvalid(b: ByteArray, p: Int, n: Int): Boolean = utf8.isInvalid(b, p, n)
    override fun toUtf8(from: ByteArray, fromP: XPtr, fromLim: Int, to: ByteArray, toP: XPtr, toLim: Int): Int = utf8.toUtf8(from, fromP, fromLim, to, toP, toLim)
    override fun updatePosition(b: ByteArray, ptr0: Int, end: Int, pos: XPosition) = ExpatTok.UTF8.updatePosition(b, ptr0, end, pos)
    override fun prologTok(b: ByteArray, ptr0: Int, end0: Int, nt: XPtr): Int = initScan(ExpatTok.XML_PROLOG_STATE, b, ptr0, end0, nt)
    override fun contentTok(b: ByteArray, ptr0: Int, end0: Int, nt: XPtr): Int = initScan(ExpatTok.XML_CONTENT_STATE, b, ptr0, end0, nt)

    /** initScan: the encoding found from the first bytes (a BOM, "<" in UTF-16), and its first token scanned. */
    private fun initScan(state: Int, b: ByteArray, ptr: Int, end: Int, nt: XPtr): Int {
        val table = if (ns) ExpatTok.ENCODINGS_NS else ExpatTok.ENCODINGS
        if (ptr >= end) return ExpatTok.XML_TOK_NONE
        if (ptr + 1 == end) {
            // (only a single byte available for auto-detection; so we're parsing an external text entity... if UTF-16
            // was externally specified, then we need at least 2 bytes)
            when (index) {
                ExpatTok.UTF_16_ENC, ExpatTok.UTF_16LE_ENC, ExpatTok.UTF_16BE_ENC -> return ExpatTok.XML_TOK_PARTIAL
            }
            when (b[ptr].toInt() and 0xFF) {
                0xFE, 0xFF, 0xEF -> if (!(index == ExpatTok.ISO_8859_1_ENC && state == ExpatTok.XML_CONTENT_STATE)) return ExpatTok.XML_TOK_PARTIAL
                0x00, 0x3C -> return ExpatTok.XML_TOK_PARTIAL
            }
        } else {
            val b0 = b[ptr].toInt() and 0xFF
            val b1 = b[ptr + 1].toInt() and 0xFF
            when ((b0 shl 8) or b1) {
                0xFEFF -> if (!(index == ExpatTok.ISO_8859_1_ENC && state == ExpatTok.XML_CONTENT_STATE)) {
                    nt.p = ptr + 2
                    found(table[ExpatTok.UTF_16BE_ENC])
                    return ExpatTok.XML_TOK_BOM
                }
                // (00 3C is handled in the default case)
                0x3C00 -> if (!((index == ExpatTok.UTF_16BE_ENC || index == ExpatTok.UTF_16_ENC) && state == ExpatTok.XML_CONTENT_STATE)) {
                    val e = table[ExpatTok.UTF_16LE_ENC]
                    found(e)
                    return e.tok(state, b, ptr, end, nt)
                }
                0xFFFE -> if (!(index == ExpatTok.ISO_8859_1_ENC && state == ExpatTok.XML_CONTENT_STATE)) {
                    nt.p = ptr + 2
                    found(table[ExpatTok.UTF_16LE_ENC])
                    return ExpatTok.XML_TOK_BOM
                }
                // (maybe a UTF-8 BOM (EF BB BF); with an external encoding of ISO-8859-1 or UTF-16 in an external text
                // entity, it might be data)
                0xEFBB -> if (!(state == ExpatTok.XML_CONTENT_STATE && (index == ExpatTok.ISO_8859_1_ENC || index == ExpatTok.UTF_16BE_ENC ||
                        index == ExpatTok.UTF_16LE_ENC || index == ExpatTok.UTF_16_ENC))) {
                    if (ptr + 2 == end) return ExpatTok.XML_TOK_PARTIAL
                    if (b[ptr + 2].toInt() and 0xFF == 0xBF) {
                        nt.p = ptr + 3
                        found(table[ExpatTok.UTF_8_ENC])
                        return ExpatTok.XML_TOK_BOM
                    }
                }
                else -> if (b0 == 0) {
                    // (0 isn't a legal data character, and a document entity can only start with ASCII: big-endian
                    // UTF-16, unless an external parsed general entity labelled UTF-16LE)
                    if (!(state == ExpatTok.XML_CONTENT_STATE && index == ExpatTok.UTF_16LE_ENC)) {
                        val e = table[ExpatTok.UTF_16BE_ENC]
                        found(e)
                        return e.tok(state, b, ptr, end, nt)
                    }
                } else if (b1 == 0) {
                    if (state != ExpatTok.XML_CONTENT_STATE) {
                        val e = table[ExpatTok.UTF_16LE_ENC]
                        found(e)
                        return e.tok(state, b, ptr, end, nt)
                    }
                }
            }
        }
        val e = table[index]
        found(e)
        return e.tok(state, b, ptr, end, nt)
    }
}
