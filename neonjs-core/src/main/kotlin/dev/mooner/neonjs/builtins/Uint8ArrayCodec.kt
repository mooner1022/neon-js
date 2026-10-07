package dev.mooner.neonjs.builtins

import dev.mooner.neonjs.runtime.*

/** Uint8Array base64 / hex conversion methods (fromBase64, fromHex, setFromBase64, setFromHex, toBase64, toHex). */
internal object Uint8ArrayCodec {
    private const val LOOSE = 0
    private const val STRICT = 1
    private const val STOP_BEFORE_PARTIAL = 2

    private const val STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private const val HEX = "0123456789abcdef"

    /** Decoding result: [bytes] (first [length] used), number of characters [read], and an optional error. */
    private class Decoded(@JvmField val read: Int, @JvmField val bytes: ByteArray, @JvmField val length: Int, @JvmField val error: String?)

    fun install(realm: Realm, ctor: JSObject, proto: JSObject) {
        ctor.method(realm, "fromBase64", 1) { f, _, args, _ ->
            val s = args.arg(0) as? CharSequence ?: typeErr("Uint8Array.fromBase64 requires a string")
            val opts = optionsObject(args.arg(1))
            val url = alphabetOption(opts)
            val lastChunk = lastChunkOption(opts)
            val r = fromBase64(s.toString(), url, lastChunk, Int.MAX_VALUE)
            if (r.error != null) throw JSException.syntaxError(r.error)
            newUint8Array(f.realm, r)
        }
        ctor.method(realm, "fromHex", 1) { f, _, args, _ ->
            val s = args.arg(0) as? CharSequence ?: typeErr("Uint8Array.fromHex requires a string")
            val r = fromHex(s.toString(), Int.MAX_VALUE)
            if (r.error != null) throw JSException.syntaxError(r.error)
            newUint8Array(f.realm, r)
        }
        proto.method(realm, "setFromBase64", 1) { f, t, args, _ ->
            val into = thisUint8Array(t, "setFromBase64", true)
            val s = args.arg(0) as? CharSequence ?: typeErr("Uint8Array.prototype.setFromBase64 requires a string")
            val opts = optionsObject(args.arg(1))
            val url = alphabetOption(opts)
            val lastChunk = lastChunkOption(opts)
            val len = into.lengthOrOOB()
            if (len < 0) typeErr("Uint8Array.prototype.setFromBase64: typed array is detached or out of bounds")
            setResult(f.realm, into, fromBase64(s.toString(), url, lastChunk, len))
        }
        proto.method(realm, "setFromHex", 1) { f, t, args, _ ->
            val into = thisUint8Array(t, "setFromHex", true)
            val s = args.arg(0) as? CharSequence ?: typeErr("Uint8Array.prototype.setFromHex requires a string")
            val len = into.lengthOrOOB()
            if (len < 0) typeErr("Uint8Array.prototype.setFromHex: typed array is detached or out of bounds")
            setResult(f.realm, into, fromHex(s.toString(), len))
        }
        proto.method(realm, "toBase64", 0) { _, t, args, _ ->
            val o = thisUint8Array(t, "toBase64")
            val opts = optionsObject(args.arg(0))
            val url = alphabetOption(opts)
            val omitPadding = opts != null && Ops.toBoolean(opts.get("omitPadding", opts))
            val len = o.lengthOrOOB()
            if (len < 0) typeErr("Uint8Array.prototype.toBase64: typed array is detached or out of bounds")
            toBase64(o.buffer.data, o.byteOffset, len, if (url) URL else STD, !omitPadding)
        }
        proto.method(realm, "toHex", 0) { _, t, _, _ ->
            val o = thisUint8Array(t, "toHex")
            val len = o.lengthOrOOB()
            if (len < 0) typeErr("Uint8Array.prototype.toHex: typed array is detached or out of bounds")
            val data = o.buffer.data
            val sb = StringBuilder(len * 2)
            for (i in o.byteOffset until o.byteOffset + len) {
                val b = data[i].toInt() and 0xFF
                sb.append(HEX[b ushr 4]).append(HEX[b and 15])
            }
            sb.toString()
        }
    }

    /** ValidateUint8Array; [write] = accessMode ~write~ (the buffer must not be immutable). */
    private fun thisUint8Array(t: Any?, method: String, write: Boolean = false): JSTypedArray {
        if (t !is JSTypedArray || t.type != ElementType.UINT8) typeErr("Method Uint8Array.prototype.$method called on incompatible receiver ${Ops.describe(t)}")
        if (write && t.buffer.immutable) typeErr("Uint8Array.prototype.$method: typed array is backed by an immutable ArrayBuffer")
        return t
    }

    /** GetOptionsObject: null stands for an empty options object. */
    private fun optionsObject(v: Any?): JSObject? = when (v) {
        Undefined -> null
        is JSObject -> v
        else -> typeErr("options must be an object")
    }

    /** Reads the "alphabet" option; true for base64url. */
    private fun alphabetOption(opts: JSObject?): Boolean {
        val a = opts?.get("alphabet", opts) ?: Undefined
        if (a === Undefined) return false
        if (a is CharSequence) {
            when (a.toString()) {
                "base64" -> return false
                "base64url" -> return true
            }
        }
        typeErr("expected alphabet to be either \"base64\" or \"base64url\"")
    }

    private fun lastChunkOption(opts: JSObject?): Int {
        val v = opts?.get("lastChunkHandling", opts) ?: Undefined
        if (v === Undefined) return LOOSE
        if (v is CharSequence) {
            when (v.toString()) {
                "loose" -> return LOOSE
                "strict" -> return STRICT
                "stop-before-partial" -> return STOP_BEFORE_PARTIAL
            }
        }
        typeErr("expected lastChunkHandling to be either \"loose\", \"strict\", or \"stop-before-partial\"")
    }

    private fun newUint8Array(realm: Realm, r: Decoded): JSTypedArray {
        val ta = TypedArrayBuiltins.allocate(realm, ElementType.UINT8, TypedArrayBuiltins.protoOf(realm, ElementType.UINT8), r.length.toLong())
        System.arraycopy(r.bytes, 0, ta.buffer.data, 0, r.length)
        return ta
    }

    /** SetUint8ArrayBytes, then throws a pending error or returns { read, written }. */
    private fun setResult(realm: Realm, into: JSTypedArray, r: Decoded): JSObject {
        System.arraycopy(r.bytes, 0, into.buffer.data, into.byteOffset, r.length)
        if (r.error != null) throw JSException.syntaxError(r.error)
        val o = JSObject(realm.objectPrototype)
        o.createDataPropertyOrThrow("read", r.read.toDouble())
        o.createDataPropertyOrThrow("written", r.length.toDouble())
        return o
    }

    private fun toBase64(data: ByteArray, off: Int, len: Int, alphabet: String, pad: Boolean): String {
        val sb = StringBuilder((len + 2) / 3 * 4)
        var i = off
        val end = off + len
        while (end - i >= 3) {
            val n = ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8) or (data[i + 2].toInt() and 0xFF)
            sb.append(alphabet[n ushr 18]).append(alphabet[(n ushr 12) and 63]).append(alphabet[(n ushr 6) and 63]).append(alphabet[n and 63])
            i += 3
        }
        when (end - i) {
            1 -> {
                val n = (data[i].toInt() and 0xFF) shl 16
                sb.append(alphabet[n ushr 18]).append(alphabet[(n ushr 12) and 63])
                if (pad) sb.append("==")
            }
            2 -> {
                val n = ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8)
                sb.append(alphabet[n ushr 18]).append(alphabet[(n ushr 12) and 63]).append(alphabet[(n ushr 6) and 63])
                if (pad) sb.append('=')
            }
        }
        return sb.toString()
    }

    private fun skipWhitespace(s: String, from: Int): Int {
        var i = from
        while (i < s.length) {
            val c = s[i]
            if (c != '\t' && c != '\n' && c != '\u000C' && c != '\r' && c != ' ') break
            i++
        }
        return i
    }

    private fun base64Value(c: Char): Int = when (c) {
        in 'A'..'Z' -> c - 'A'
        in 'a'..'z' -> c - 'a' + 26
        in '0'..'9' -> c - '0' + 52
        '+' -> 62
        '/' -> 63
        else -> -1
    }

    /** FromBase64(string, alphabet, lastChunkHandling, maxLength) */
    private fun fromBase64(s: String, url: Boolean, lastChunk: Int, maxLength: Int): Decoded {
        val out = ByteArray(minOf(maxLength.toLong(), s.length.toLong() / 4 * 3 + 3).toInt())
        if (maxLength == 0) return Decoded(0, out, 0, null)
        var n = 0
        var read = 0
        val chunk = IntArray(4)
        var chunkLength = 0
        var index = 0
        val length = s.length
        val bad = "Invalid base64 string"

        /** DecodeBase64Chunk for a partial chunk; returns false when extra bits are set and must be rejected. */
        fun decodePartial(throwOnExtraBits: Boolean): Boolean {
            if (chunkLength == 2) {
                if (throwOnExtraBits && chunk[1] and 15 != 0) return false
                out[n++] = ((chunk[0] shl 2) or (chunk[1] ushr 4)).toByte()
            } else {
                if (throwOnExtraBits && chunk[2] and 3 != 0) return false
                out[n++] = ((chunk[0] shl 2) or (chunk[1] ushr 4)).toByte()
                out[n++] = (((chunk[1] and 15) shl 4) or (chunk[2] ushr 2)).toByte()
            }
            return true
        }

        while (true) {
            index = skipWhitespace(s, index)
            if (index == length) {
                if (chunkLength > 0) {
                    when (lastChunk) {
                        STOP_BEFORE_PARTIAL -> return Decoded(read, out, n, null)
                        LOOSE -> {
                            if (chunkLength == 1) return Decoded(read, out, n, bad)
                            decodePartial(false)
                        }
                        else -> return Decoded(read, out, n, bad)
                    }
                }
                return Decoded(length, out, n, null)
            }
            var c = s[index++]
            if (c == '=') {
                if (chunkLength < 2) return Decoded(read, out, n, bad)
                index = skipWhitespace(s, index)
                if (chunkLength == 2) {
                    if (index == length) {
                        if (lastChunk == STOP_BEFORE_PARTIAL) return Decoded(read, out, n, null)
                        return Decoded(read, out, n, bad)
                    }
                    c = s[index]
                    if (c == '=') index = skipWhitespace(s, index + 1)
                }
                if (index < length) return Decoded(read, out, n, bad)
                if (!decodePartial(lastChunk == STRICT)) return Decoded(read, out, n, bad)
                return Decoded(length, out, n, null)
            }
            if (url) {
                if (c == '+' || c == '/') return Decoded(read, out, n, bad)
                if (c == '-') c = '+' else if (c == '_') c = '/'
            }
            val d = base64Value(c)
            if (d < 0) return Decoded(read, out, n, bad)
            val remaining = maxLength - n
            if ((remaining == 1 && chunkLength == 2) || (remaining == 2 && chunkLength == 3)) return Decoded(read, out, n, null)
            chunk[chunkLength++] = d
            if (chunkLength == 4) {
                out[n++] = ((chunk[0] shl 2) or (chunk[1] ushr 4)).toByte()
                out[n++] = (((chunk[1] and 15) shl 4) or (chunk[2] ushr 2)).toByte()
                out[n++] = (((chunk[2] and 3) shl 6) or chunk[3]).toByte()
                chunkLength = 0
                read = index
                if (n == maxLength) return Decoded(read, out, n, null)
            }
        }
    }

    /** FromHex(string, maxLength) */
    private fun fromHex(s: String, maxLength: Int): Decoded {
        val length = s.length
        val out = ByteArray(minOf(maxLength, length / 2))
        if (length % 2 != 0) return Decoded(0, out, 0, "Hex string must have an even length")
        var read = 0
        var n = 0
        while (read < length && n < maxLength) {
            val hi = NumberConv.digitVal(s[read], 16)
            val lo = NumberConv.digitVal(s[read + 1], 16)
            if (hi < 0 || lo < 0 || !isAsciiHex(s[read]) || !isAsciiHex(s[read + 1])) return Decoded(read, out, n, "Invalid hex string")
            read += 2
            out[n++] = ((hi shl 4) or lo).toByte()
        }
        return Decoded(read, out, n, null)
    }

    private fun isAsciiHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
}
