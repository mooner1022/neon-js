package dev.mooner.neonjs.node

/**
 * The string encodings of Node's Buffer, as Node has them: `utf8` (lone surrogates written as U+FFFD, invalid
 * sequences read as U+FFFD), `utf16le`, `latin1`, `ascii` (the high bit dropped when read), `hex` (up to the first
 * pair that is not hex) and `base64` / `base64url` (either alphabet read, whitespace and other characters skipped).
 */
internal object Codecs {
    enum class Enc { UTF8, UTF16LE, LATIN1, ASCII, HEX, BASE64, BASE64URL }

    /** The encoding a Node encoding name denotes, or null. */
    fun of(name: String): Enc? = when (name.lowercase()) {
        "utf8", "utf-8" -> Enc.UTF8
        "ucs2", "ucs-2", "utf16le", "utf-16le" -> Enc.UTF16LE
        "latin1", "binary" -> Enc.LATIN1
        "ascii" -> Enc.ASCII
        "hex" -> Enc.HEX
        "base64" -> Enc.BASE64
        "base64url" -> Enc.BASE64URL
        else -> null
    }

    // ---------------------------------------------------------------- encoding

    fun byteLength(s: String, enc: Enc): Long = when (enc) {
        Enc.UTF8 -> {
            var n = 0L
            var i = 0
            while (i < s.length) {
                val c = s[i]
                n += when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { i++; 4 }
                    else -> 3
                }
                i++
            }
            n
        }
        Enc.UTF16LE -> s.length * 2L
        Enc.LATIN1, Enc.ASCII -> s.length.toLong()
        Enc.HEX -> (s.length ushr 1).toLong()
        Enc.BASE64, Enc.BASE64URL -> {
            // the characters base64Write takes, six bits each
            var n = 0L
            for (c in s) {
                if (c == '=') break
                if (base64Value(c) >= 0) n++
            }
            n * 6 / 8
        }
    }

    /** Writes [s] into [out] from [offset], at most [max] bytes (no partial character); returns the bytes written. */
    fun write(s: String, enc: Enc, out: ByteArray, offset: Int, max: Int): Int {
        when (enc) {
            Enc.UTF8 -> {
                var at = offset
                val end = offset + max
                var i = 0
                while (i < s.length) {
                    var cp = s[i].code
                    var units = 1
                    if (Character.isHighSurrogate(s[i]) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                        cp = Character.toCodePoint(s[i], s[i + 1])
                        units = 2
                    } else if (Character.isSurrogate(s[i])) cp = 0xFFFD
                    val n = if (cp < 0x80) 1 else if (cp < 0x800) 2 else if (cp < 0x10000) 3 else 4
                    if (at + n > end) break
                    when (n) {
                        1 -> out[at] = cp.toByte()
                        2 -> { out[at] = (0xC0 or (cp shr 6)).toByte(); out[at + 1] = (0x80 or (cp and 0x3F)).toByte() }
                        3 -> {
                            out[at] = (0xE0 or (cp shr 12)).toByte(); out[at + 1] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                            out[at + 2] = (0x80 or (cp and 0x3F)).toByte()
                        }
                        else -> {
                            out[at] = (0xF0 or (cp shr 18)).toByte(); out[at + 1] = (0x80 or ((cp shr 12) and 0x3F)).toByte()
                            out[at + 2] = (0x80 or ((cp shr 6) and 0x3F)).toByte(); out[at + 3] = (0x80 or (cp and 0x3F)).toByte()
                        }
                    }
                    at += n
                    i += units
                }
                return at - offset
            }
            Enc.UTF16LE -> {
                val n = minOf(s.length, max / 2)
                for (i in 0 until n) {
                    out[offset + 2 * i] = (s[i].code and 0xFF).toByte()
                    out[offset + 2 * i + 1] = (s[i].code ushr 8).toByte()
                }
                return n * 2
            }
            Enc.LATIN1, Enc.ASCII -> {
                val n = minOf(s.length, max)
                for (i in 0 until n) out[offset + i] = (s[i].code and 0xFF).toByte()
                return n
            }
            Enc.HEX -> {
                var n = 0
                while (n < max && 2 * n + 1 < s.length) {
                    val h = Character.digit(s[2 * n], 16)
                    val l = Character.digit(s[2 * n + 1], 16)
                    if (h < 0 || l < 0) break
                    out[offset + n] = (h * 16 + l).toByte()
                    n++
                }
                return n
            }
            Enc.BASE64, Enc.BASE64URL -> {
                // Node's lenient base64: both alphabets, other characters skipped, decoding ends at '='
                var n = 0
                var acc = 0
                var bits = 0
                for (c in s) {
                    if (c == '=' || n >= max) break
                    val v = base64Value(c)
                    if (v < 0) continue
                    acc = (acc shl 6) or v
                    bits += 6
                    if (bits >= 8) {
                        bits -= 8
                        out[offset + n++] = (acc shr bits).toByte()
                    }
                }
                return n
            }
        }
    }

    private fun base64Value(c: Char): Int = when (c) {
        in 'A'..'Z' -> c - 'A'
        in 'a'..'z' -> c - 'a' + 26
        in '0'..'9' -> c - '0' + 52
        '+', '-' -> 62
        '/', '_' -> 63
        else -> -1
    }

    // ---------------------------------------------------------------- decoding

    fun decode(b: ByteArray, from: Int, to: Int, enc: Enc): String = when (enc) {
        Enc.UTF8 -> utf8(b, from, to)
        Enc.UTF16LE -> {
            val sb = StringBuilder((to - from) / 2)
            var i = from
            while (i + 1 < to) {
                sb.append(((b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)).toChar())
                i += 2
            }
            sb.toString()
        }
        Enc.LATIN1 -> String(CharArray(to - from) { (b[from + it].toInt() and 0xFF).toChar() })
        Enc.ASCII -> String(CharArray(to - from) { (b[from + it].toInt() and 0x7F).toChar() })
        Enc.HEX -> {
            val sb = StringBuilder((to - from) * 2)
            for (i in from until to) {
                val v = b[i].toInt() and 0xFF
                sb.append(HEX[v shr 4]).append(HEX[v and 0xF])
            }
            sb.toString()
        }
        Enc.BASE64 -> java.util.Base64.getEncoder().encodeToString(b.copyOfRange(from, to))
        Enc.BASE64URL -> java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b.copyOfRange(from, to))
    }

    private const val HEX = "0123456789abcdef"

    /** UTF-8 decode (Encoding Standard): invalid sequences as U+FFFD, a BOM kept. */
    fun utf8(b: ByteArray, from: Int, to: Int): String {
        val out = StringBuilder(to - from)
        var cp = 0
        var needed = 0
        var seen = 0
        var lower = 0x80
        var upper = 0xBF
        var i = from
        while (i < to) {
            val x = b[i].toInt() and 0xFF
            if (needed == 0) {
                when (x) {
                    in 0x00..0x7F -> out.append(x.toChar())
                    in 0xC2..0xDF -> { needed = 1; cp = x and 0x1F }
                    in 0xE0..0xEF -> {
                        if (x == 0xE0) lower = 0xA0
                        if (x == 0xED) upper = 0x9F
                        needed = 2; cp = x and 0xF
                    }
                    in 0xF0..0xF4 -> {
                        if (x == 0xF0) lower = 0x90
                        if (x == 0xF4) upper = 0x8F
                        needed = 3; cp = x and 0x7
                    }
                    else -> out.append('�')
                }
                i++
                continue
            }
            if (x !in lower..upper) {
                cp = 0; needed = 0; seen = 0; lower = 0x80; upper = 0xBF
                out.append('�')
                continue
            }
            lower = 0x80; upper = 0xBF
            cp = (cp shl 6) or (x and 0x3F)
            seen++
            i++
            if (seen == needed) {
                out.appendCodePoint(cp)
                cp = 0; needed = 0; seen = 0
            }
        }
        if (needed != 0) out.append('�')
        return out.toString()
    }

    fun isUtf8(b: ByteArray, from: Int, to: Int): Boolean {
        var i = from
        while (i < to) {
            val x = b[i].toInt() and 0xFF
            val n = when (x) {
                in 0x00..0x7F -> 0
                in 0xC2..0xDF -> 1
                in 0xE0..0xEF -> 2
                in 0xF0..0xF4 -> 3
                else -> return false
            }
            if (i + n >= to && n > 0) return false
            var lower = 0x80
            var upper = 0xBF
            if (x == 0xE0) lower = 0xA0
            if (x == 0xED) upper = 0x9F
            if (x == 0xF0) lower = 0x90
            if (x == 0xF4) upper = 0x8F
            for (k in 1..n) {
                val y = b[i + k].toInt() and 0xFF
                if (y !in lower..upper) return false
                lower = 0x80
                upper = 0xBF
            }
            i += n + 1
        }
        return true
    }
}
