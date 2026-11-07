package dev.mooner.neonpython

import dev.mooner.neonjs.runtime.VmConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.security.MessageDigest

/**
 * The CJK codecs (PyCjkCodecs.kt, PyIso2022.kt, PyMultibytecodec.kt) against CPython 3.14.6, exhaustively, past what
 * programs/cjk_codecs.py samples: every code point encoded alone, and every sequence of one and of two bytes decoded
 * alone (and gb18030's of four, the EUC codecs' of three, euc_kr's make-up sequences, what hz's and the iso2022 codecs'
 * escapes designate), strictly, by the codec object `_codecs_*.getcodec` gives. Each codec's results are digested as
 * neonpython/tools/gen_cjkcodecs.py --digests digests CPython's, whose output is [CPYTHON]: (the code points', the
 * sequences', how many sequences).
 */
class PyCjkCodecsTest {
    companion object {
        private val CPYTHON = mapOf(
            "gb2312" to Triple("6994609ff93addbe32fad2b0b3e0e9a4", "81757e51cbdfec3ac60af7bcad17f77a", 65792),
            "gbk" to Triple("fcc83c9e2e4f7758c8b37a8a359a2ea2", "f83bd7c30e6a2bc081432ece3d5df598", 65792),
            "gb18030" to Triple("2d710a683e2dde9803ea13e446a248aa", "98ad226c99a959ffd02efaf8747e3952", 2701968),
            "hz" to Triple("2b79f404371cb9e041d3b89ca38a69a2", "c4956322f90964e7735b40ae5dfdb89d", 131328),
            "big5" to Triple("e183ce01ce69b4faeedff6b7698448ad", "01489961b3c8ed6c688c9b2aebe41282", 65792),
            "cp950" to Triple("854e2917997decb66445ceec94726990", "902d1bfe2f5e82622c61327c0d269b9c", 65792),
            "big5hkscs" to Triple("01f2476ad7ac33fa92bf222dc0a38e3a", "ce797349df70e70d80015b887348d2d9", 65792),
            "shift_jis" to Triple("a83a0f66688788274379dcc86dd7cb52", "71de3b83d3e84fe7316b19d4ea1f9b7a", 65792),
            "cp932" to Triple("b6eebe3c8acce0970fe76edc2ac6f367", "92ac09f5af725fc4e551a48c35d1c702", 65792),
            "euc_jp" to Triple("06355857a9b33e96d0aa4a4be4e75ca0", "65075c6db92c3990e2c6e53426a4c0eb", 131328),
            "shift_jis_2004" to Triple("726acbd1d3ac16117568207fb5c7e3f6", "2fd671ddc39a672115952f9496643495", 65792),
            "euc_jis_2004" to Triple("8cf911708978f46546fa59c1cc126f5d", "731e826248b33848a3188e68792b5d11", 131328),
            "euc_jisx0213" to Triple("047a3260643fcfd5da806b3b45066b58", "e20e1db57969b36d3cd4d08b0b0905d0", 131328),
            "shift_jisx0213" to Triple("faa44f0ecc1cf3966b8e7aa2b9df22da", "46dc038c6574d83d2691dcd3d1a265f6", 65792),
            "euc_kr" to Triple("915a6e8a5a79b8848469370e626bcd8e", "a0eb0e02f65ed7ad652b43baff489b9e", 90816),
            "cp949" to Triple("f7a28fdf76738b59daa85d6f359315ee", "2347f767be2dd2bf671d09eb6c992c65", 65792),
            "johab" to Triple("0806674f6c511a2e8c750430bd819eb8", "438f4b11705b0cc467642efae386e17d", 65792),
            "iso2022_kr" to Triple("d1f268486ad5f91fe0148490df285e27", "a068aa50b2529dddf98b3362148bc99d", 131328),
            "iso2022_jp" to Triple("ed0fdb5d112caa057d4de90380594967", "0c975b8b22df37d126992840e8972994", 262400),
            "iso2022_jp_1" to Triple("629973d9e7c77b5b89847fefb8f721e6", "7df892afd0e23383ee8542251b53d93a", 196864),
            "iso2022_jp_2" to Triple("4dd5764007887273559750a1aeceaed6", "28ab7ff3775b2a083a0cccd1510eb54d", 327936),
            "iso2022_jp_2004" to Triple("e7263f7196dde0194c915094feead23f", "7b51e3092cdb7d8869dbd31a831c65bc", 196864),
            "iso2022_jp_3" to Triple("28a13e9f193e883236d848b54733a0a6", "7db9f21092b0603db292e37132122167", 196864),
            "iso2022_jp_ext" to Triple("92129f63811b2bfbf5151410a3afbf71", "1cc47b6b2beab1569ce8490a3da110fe", 196864),
        )

        private val MODULES = mapOf("gb2312" to "cn", "gbk" to "cn", "gb18030" to "cn", "hz" to "cn", "big5" to "tw",
            "cp950" to "tw", "big5hkscs" to "hk", "shift_jis" to "jp", "cp932" to "jp", "euc_jp" to "jp",
            "shift_jis_2004" to "jp", "euc_jis_2004" to "jp", "euc_jisx0213" to "jp", "shift_jisx0213" to "jp",
            "euc_kr" to "kr", "cp949" to "kr", "johab" to "kr", "iso2022_kr" to "iso2022", "iso2022_jp" to "iso2022",
            "iso2022_jp_1" to "iso2022", "iso2022_jp_2" to "iso2022", "iso2022_jp_2004" to "iso2022",
            "iso2022_jp_3" to "iso2022", "iso2022_jp_ext" to "iso2022")

        /** The sequences each codec's escapes designate, each followed by every two bytes (DIGEST_PREFIXES). */
        private val PREFIXES = mapOf(
            "hz" to listOf("~{"),
            "iso2022_kr" to listOf("\u001b$)C\u000e"),
            "iso2022_jp" to listOf("\u001b\$B", "\u001b(J", "\u001b&@\u001b\$B"),
            "iso2022_jp_1" to listOf("\u001b\$B", "\u001b$(D"),
            "iso2022_jp_2" to listOf("\u001b$(C", "\u001b\$A", "\u001b.A\u001bN", "\u001b.F\u001bN"),
            "iso2022_jp_2004" to listOf("\u001b$(Q", "\u001b$(P"),
            "iso2022_jp_3" to listOf("\u001b$(O", "\u001b$(P"),
            "iso2022_jp_ext" to listOf("\u001b(I", "\u001b$(D"),
        )

        @JvmStatic
        fun codecs(): List<String> = MODULES.keys.toList()

        private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

        /** sequences(): every one of one and two bytes, then the codec's longer ones, in gen_cjkcodecs.py's order. */
        private fun sequences(name: String, f: (ByteArray) -> Unit) {
            for (b1 in 0 until 256) f(bytes(b1))
            for (b1 in 0 until 256) for (b2 in 0 until 256) f(bytes(b1, b2))
            when (name) {
                "gb18030" -> {
                    for (b1 in 0x81 until 0xff) for (b2 in 0x30 until 0x3a) for (b3 in 0x81 until 0xff) for (b4 in 0x30 until 0x3a) f(bytes(b1, b2, b3, b4))
                    for (b1 in intArrayOf(0x80, 0x81, 0x84, 0x8f, 0x90, 0xe3, 0xfe, 0xff)) for (b2 in intArrayOf(0x30, 0x39)) {
                        for (b3 in 0 until 256) for (b4 in 0 until 256) f(bytes(b1, b2, b3, b4))
                    }
                }
                "euc_jp", "euc_jis_2004", "euc_jisx0213" -> for (b2 in 0 until 256) for (b3 in 0 until 256) f(bytes(0x8f, b2, b3))
                "euc_kr" -> for (x in 0xa0 until 0xc0) for (y in 0xbe until 0xd5) for (z in (0xa0 until 0xc0) + listOf(0xd4, 0xd5)) {
                    f(bytes(0xa4, 0xd4, 0xa4, x, 0xa4, y, 0xa4, z))
                }
            }
            for (p in PREFIXES[name] ?: emptyList()) {
                val pb = p.toByteArray(Charsets.ISO_8859_1)
                for (b1 in 0 until 256) for (b2 in 0 until 256) f(pb + bytes(b1, b2))
            }
        }

        private fun hex(sb: StringBuilder, b: ByteArray) {
            for (x in b) {
                val v = x.toInt() and 0xFF
                if (v < 16) sb.append('0')
                sb.append(Integer.toHexString(v))
            }
        }

        private fun error(ctx: PyContext, sb: StringBuilder, e: PyException) {
            val f = PyExceptions.objectOf(e, ctx).fields!!
            sb.append('E').append(f["start"]).append(',').append(f["end"]).append(',').append((f["reason"] as PyStr).s)
        }

        private fun md5(md: MessageDigest) = md.digest().joinToString("") { "%02x".format(it) }

        /** What each code point encodes to, alone (or its error), digested. */
        private fun encodings(ctx: PyContext, c: PyMbCodecObj): String {
            val md = MessageDigest.getInstance("MD5")
            val sb = StringBuilder()
            for (cp in 0 until 0x110000) {
                sb.setLength(0)
                sb.append(Integer.toHexString(cp)).append(' ')
                try {
                    hex(sb, (PyMultibytecodec.codecEncode(ctx, c, PyStr(String(Character.toChars(cp))), null).items[0] as PyBytes).b)
                } catch (e: PyException) {
                    error(ctx, sb, e)
                }
                md.update(sb.append('\n').toString().toByteArray(Charsets.US_ASCII))
            }
            return md5(md)
        }

        /** What each sequence decodes to (or its error), digested; and how many there are. */
        private fun decodings(ctx: PyContext, c: PyMbCodecObj, name: String): Pair<String, Int> {
            val md = MessageDigest.getInstance("MD5")
            val sb = StringBuilder()
            var n = 0
            sequences(name) { b ->
                sb.setLength(0)
                hex(sb, b)
                sb.append(' ')
                try {
                    val s = (PyMultibytecodec.codecDecode(ctx, c, b, null).items[0] as PyStr).s
                    sb.append('.')
                    var i = 0
                    while (i < s.length) {
                        val cp = s.codePointAt(i)
                        if (i > 0) sb.append('.')
                        sb.append(Integer.toHexString(cp))
                        i += Character.charCount(cp)
                    }
                } catch (e: PyException) {
                    error(ctx, sb, e)
                }
                md.update(sb.append('\n').toString().toByteArray(Charsets.US_ASCII))
                n++
            }
            return md5(md) to n
        }
    }

    @ParameterizedTest
    @MethodSource("codecs")
    fun everyCodePointAndShortSequenceAsCPython(name: String) {
        val ctx = PyContext(StringBuilder(), VmConfig(), null, StringBuilder())
        var got: Triple<String, String, Int>? = null
        ctx.builtins[PyStr("_digest")] = PyBuiltin("_digest") { cx, a ->
            val c = a[0] as PyMbCodecObj
            val (d, n) = decodings(cx, c, name)
            got = Triple(encodings(cx, c), d, n)
            PyNone
        }
        ctx.exec("import _codecs_${MODULES[name]}\n_digest(_codecs_${MODULES[name]}.getcodec('$name'))\n")
        assertEquals(CPYTHON[name], got, name)
    }
}
