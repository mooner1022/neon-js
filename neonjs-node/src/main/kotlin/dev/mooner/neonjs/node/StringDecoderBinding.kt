package dev.mooner.neonjs.node

import dev.mooner.neonjs.builtins.JSDataView
import dev.mooner.neonjs.builtins.JSTypedArray
import dev.mooner.neonjs.runtime.*

/**
 * The native side of node:string_decoder, after Node's src/string_decoder.cc (MIT license, see NOTICE): the decoder's
 * state lives in a Buffer (`kNativeDecoder`) as Node keeps it, the incomplete character's bytes first, then the bytes
 * still missing, the bytes buffered and the encoding. A character cut by a chunk boundary is held until the bytes it
 * needs arrive; what is decoded is decoded as Buffer#toString decodes it.
 */
internal object StringDecoderBinding {
    private const val START = 0
    private const val END = 4
    private const val MISSING = 4
    private const val BUFFERED = 5
    private const val ENCODING = 6
    private const val SIZE = 7

    /** Node's `enum encoding` order, which the state's encoding field holds. */
    private val ENCODINGS = arrayOf("ascii", "utf8", "base64", "utf16le", "latin1", "hex", "buffer", "base64url")
    private const val ASCII = 0
    private const val UTF8 = 1
    private const val BASE64 = 2
    private const val UCS2 = 3
    private const val BASE64URL = 7

    private fun codec(e: Int): Codecs.Enc = when (e) {
        ASCII -> Codecs.Enc.ASCII
        UTF8 -> Codecs.Enc.UTF8
        BASE64 -> Codecs.Enc.BASE64
        UCS2 -> Codecs.Enc.UTF16LE
        5 -> Codecs.Enc.HEX
        BASE64URL -> Codecs.Enc.BASE64URL
        else -> Codecs.Enc.LATIN1
    }

    fun create(rt: NodeRuntime): JSObject {
        val realm = rt.realm
        val b = JSObject(null)
        fun fn(name: String, length: Int, impl: NativeImpl) = b.defineOwn(name, NativeFunction(realm, name, length, impl), Attr.NONE)
        b.defineOwn("kIncompleteCharactersStart", START.toDouble(), Attr.NONE)
        b.defineOwn("kIncompleteCharactersEnd", END.toDouble(), Attr.NONE)
        b.defineOwn("kMissingBytes", MISSING.toDouble(), Attr.NONE)
        b.defineOwn("kBufferedBytes", BUFFERED.toDouble(), Attr.NONE)
        b.defineOwn("kEncodingField", ENCODING.toDouble(), Attr.NONE)
        b.defineOwn("kNumFields", SIZE.toDouble(), Attr.NONE)
        b.defineOwn("kSize", SIZE.toDouble(), Attr.NONE)
        b.defineOwn("encodings", JSArray.of(realm.arrayPrototype, ENCODINGS.map { it as Any? }.toTypedArray()), Attr.NONE)

        fn("decode", 2) { f, _, a, _ ->
            val state = State(a.arg(0))
            val (data, offset, length) = bytesOf(a.arg(1))
            decode(rt, state, data, offset, length)
        }
        fn("flush", 1) { _, _, a, _ -> flush(rt, State(a.arg(0))) }
        return b
    }

    /** The decoder's state: the bytes of its Buffer. */
    private class State(v: Any?) {
        val data: ByteArray
        val base: Int

        init {
            val t = v as? JSTypedArray ?: throw JSException.typeError("the decoder's state must be a Buffer")
            if (t.byteLengthOrZero() < SIZE) throw JSException.typeError("the decoder's state is too short")
            if (t.buffer.immutable) throw JSException.typeError("Cannot write to an immutable ArrayBuffer")
            data = t.buffer.data
            base = t.byteOffset
        }

        operator fun get(i: Int): Int = data[base + i].toInt() and 0xFF
        operator fun set(i: Int, v: Int) {
            data[base + i] = v.toByte()
        }

        val encoding get() = this[ENCODING]
        var missing
            get() = this[MISSING]
            set(v) { this[MISSING] = v }
        var buffered
            get() = this[BUFFERED]
            set(v) { this[BUFFERED] = v }
    }

    /** The bytes of an ArrayBufferView: its array, where they start, how many. */
    private fun bytesOf(v: Any?): Triple<ByteArray, Int, Int> = when (v) {
        is JSTypedArray -> Triple(v.buffer.data, v.byteOffset, v.byteLengthOrZero())
        is JSDataView -> Triple(v.buffer.data, v.byteOffset, maxOf(v.byteLengthOrOOB(), 0))
        else -> throw JSException.typeError("The \"buf\" argument must be an instance of Buffer, TypedArray, or DataView.")
    }

    private fun makeString(rt: NodeRuntime, data: ByteArray, from: Int, n: Int, enc: Int): String {
        val e = codec(enc)
        val chars = when (e) {
            Codecs.Enc.HEX -> n * 2L
            Codecs.Enc.UTF16LE -> n / 2L
            Codecs.Enc.BASE64, Codecs.Enc.BASE64URL -> (n + 2) / 3 * 4L
            else -> n.toLong()
        }
        val max = BufferBinding.stringMaxLength(rt.realm)
        if (chars > max) {
            throw rt.nodeError(ErrorKind.ERROR, "Cannot create a string longer than 0x${max.toString(16)} characters", "ERR_STRING_TOO_LONG")
        }
        rt.realm.agent.checkStringLength(chars)
        return Codecs.decode(data, from, from + n, e)
    }

    private fun decode(rt: NodeRuntime, s: State, input: ByteArray, start: Int, length: Int): String {
        val enc = s.encoding
        if (enc != UTF8 && enc != UCS2 && enc != BASE64 && enc != BASE64URL) return makeString(rt, input, start, length, enc)
        var data = start
        var nread = length
        var prepend: String? = null

        // bytes that finish a character of the previous chunk
        if (s.missing > 0) {
            if (enc == UTF8) {
                // a byte that is no continuation ends the incomplete character there (as V8's decoder would)
                var i = 0
                while (i < nread && i < s.missing) {
                    if ((input[data + i].toInt() and 0xC0) != 0x80) {
                        s.missing = 0
                        System.arraycopy(input, data, s.data, s.base + START + s.buffered, i)
                        s.buffered += i
                        data += i
                        nread -= i
                        break
                    }
                    i++
                }
            }
            val found = minOf(nread, s.missing)
            System.arraycopy(input, data, s.data, s.base + START + s.buffered, found)
            data += found
            nread -= found
            s.missing -= found
            s.buffered += found
            if (s.missing == 0) {
                prepend = makeString(rt, s.data, s.base + START, s.buffered, enc)
                s.buffered = 0
            }
        }

        val body: String
        if (nread == 0) {
            body = prepend ?: ""
            prepend = null
        } else {
            // a character this chunk ends in the middle of is kept for the next one
            if (enc == UTF8 && (input[data + nread - 1].toInt() and 0x80) != 0) {
                var i = nread - 1
                while (true) {
                    s.buffered += 1
                    val x = input[data + i].toInt() and 0xFF
                    if ((x and 0xC0) == 0x80) {
                        // a trailing byte
                        if (s.buffered >= 4 || i == 0) {
                            s.buffered = 0
                            break
                        }
                    } else {
                        val need = when {
                            (x and 0xE0) == 0xC0 -> 2
                            (x and 0xF0) == 0xE0 -> 3
                            (x and 0xF8) == 0xF0 -> 4
                            else -> -1
                        }
                        if (need < 0) {
                            // a lead byte of nothing representable
                            s.buffered = 0
                            break
                        }
                        s.missing = need
                        if (s.buffered >= s.missing) {
                            // as many trailing bytes as the lead byte asks (or more, which is invalid anyway)
                            s.missing = 0
                            s.buffered = 0
                        }
                        s.missing -= s.buffered
                        break
                    }
                    i--
                }
            } else if (enc == UCS2) {
                if (nread % 2 == 1) {
                    // half a code unit
                    s.buffered = 1
                    s.missing = 1
                } else if ((input[data + nread - 1].toInt() and 0xFC) == 0xD8) {
                    // a high surrogate, the low one in the next chunk
                    s.buffered = 2
                    s.missing = 2
                }
            } else if (enc == BASE64 || enc == BASE64URL) {
                s.buffered = nread % 3
                if (s.buffered > 0) s.missing = 3 - s.buffered
            }
            if (s.buffered > 0) {
                nread -= s.buffered
                System.arraycopy(input, data + nread, s.data, s.base + START, s.buffered)
            }
            body = if (nread > 0) makeString(rt, input, data, nread, enc) else ""
        }
        return if (prepend == null) body else prepend + body
    }

    private fun flush(rt: NodeRuntime, s: State): String {
        val enc = s.encoding
        if (enc == UCS2 && s.buffered % 2 == 1) {
            // a single trailing byte is dropped, as the JS decoder did
            s.missing -= 1
            s.buffered -= 1
        }
        if (s.buffered == 0) return ""
        val ret = makeString(rt, s.data, s.base + START, s.buffered, enc)
        s.missing = 0
        s.buffered = 0
        return ret
    }
}
