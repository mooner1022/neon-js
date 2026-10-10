package dev.mooner.neonjs.ext

import dev.mooner.neonjs.builtins.ArrayBufferBuiltins
import dev.mooner.neonjs.builtins.ElementType
import dev.mooner.neonjs.builtins.JSArrayBuffer
import dev.mooner.neonjs.builtins.JSDataView
import dev.mooner.neonjs.builtins.JSTypedArray
import dev.mooner.neonjs.builtins.TypedArrayBuiltins
import dev.mooner.neonjs.builtins.typeErr
import dev.mooner.neonjs.runtime.*

/**
 * `Blob` and `File` (File API). The bytes of a blob never change, so a slice shares its blob's array. Reading
 * (`text()`, `arrayBuffer()`, `bytes()`) settles at once; `stream()` and `textStream()` wait for ReadableStream.
 */
internal object Blob {
    open class JSBlob(proto: JSObject?, @JvmField val data: ByteArray, @JvmField val offset: Int, @JvmField val size: Int, @JvmField val type: String) : JSObject(proto) {
        override val className: String get() = "Blob"

        fun bytes(): ByteArray = data.copyOfRange(offset, offset + size)
    }

    class JSFile(proto: JSObject?, data: ByteArray, offset: Int, size: Int, type: String, @JvmField val name: String, @JvmField val lastModified: Double) :
        JSBlob(proto, data, offset, size, type) {
        override val className: String get() = "File"
    }

    fun newBlob(realm: Realm, bytes: ByteArray, type: String): JSBlob = JSBlob(realm.intrinsic("%Blob.prototype%"), bytes, 0, bytes.size, type)

    fun newFile(realm: Realm, bytes: ByteArray, type: String, name: String, lastModified: Double): JSFile =
        JSFile(realm.intrinsic("%File.prototype%"), bytes, 0, bytes.size, type, name, lastModified)

    /** A blob's type: "" unless all of it is printable ASCII, then in lower case. */
    private fun normalizeType(t: String): String {
        for (c in t) if (c.code < 0x20 || c.code > 0x7E) return ""
        return t.lowercase(java.util.Locale.ROOT)
    }

    /** A `sequence<BlobPart>` argument: each part's bytes, blob or string. */
    private fun convertParts(realm: Realm, parts: Any?, what: String): List<Any> =
        if (parts === Undefined) emptyList() else Idl.sequence(realm, parts, "$what blobParts") { p -> blobPart(p, what) }

    /** "process blob parts": the bytes of the parts, strings with their line endings converted for "native". */
    private fun processParts(realm: Realm, items: List<Any>, native: Boolean, what: String): ByteArray {
        // strings become bytes first; blobs are copied from their own arrays once the size is known and charged
        var total = 0L
        val chunks = ArrayList<Any>(items.size)
        for (item in items) {
            val chunk = if (item is String) WebGlobals.utf8(if (native) nativeLineEndings(item) else item) else item
            total += if (chunk is JSBlob) chunk.size.toLong() else (chunk as ByteArray).size.toLong()
            if (total > Int.MAX_VALUE - 8) throw JSException.rangeError("$what: the blob is too large")
            chunks.add(chunk)
        }
        realm.agent.reserveAllocation(total)
        val out = ByteArray(total.toInt())
        var at = 0
        for (c in chunks) {
            if (c is JSBlob) {
                System.arraycopy(c.data, c.offset, out, at, c.size)
                at += c.size
            } else {
                c as ByteArray
                System.arraycopy(c, 0, out, at, c.size)
                at += c.size
            }
        }
        return out
    }

    /** A `(BufferSource or Blob or USVString)` element: the bytes of a buffer, a blob, or a string. */
    private fun blobPart(v: Any?, what: String): Any = when (v) {
        is JSArrayBuffer -> if (v.isShared) Idl.usvString(v) else Idl.bytes(v, "$what part", allowShared = false)
        is JSTypedArray, is JSDataView -> Idl.bytes(v, "$what part", allowShared = false)
        is JSBlob -> v
        else -> Idl.usvString(v)
    }

    private fun nativeLineEndings(s: String): String {
        val native = System.lineSeparator()
        if (s.indexOf('\r') < 0 && native == "\n") return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\r' -> {
                    sb.append(native)
                    if (i + 1 < s.length && s[i + 1] == '\n') i++
                }
                c == '\n' -> sb.append(native)
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /** BlobPropertyBag members in lexicographic order: whether endings are "native", then the type. */
    private fun blobOptions(o: JSObject?, what: String): Pair<Boolean, String> {
        val endings = Idl.member(o, "endings").let { if (it === Undefined) "transparent" else Idl.domString(it) }
        if (endings != "transparent" && endings != "native") typeErr("$what: '$endings' is not a valid value for endings")
        val type = Idl.member(o, "type").let { if (it === Undefined) "" else normalizeType(Idl.domString(it)) }
        return (endings == "native") to type
    }

    fun install(realm: Realm) {
        val b = WebInterface.define(realm, "Blob", 0) { a, proto ->
            // the arguments are converted in order (the parts, then the options), then the parts are processed
            val parts = convertParts(realm, a.arg(0), "Blob")
            val (native, type) = blobOptions(Idl.dictionary(a.arg(1), "BlobPropertyBag"), "Blob")
            val data = processParts(realm, parts, native, "Blob")
            JSBlob(proto, data, 0, data.size, type)
        }
        fun blob(t: Any?, m: String) = Idl.self<JSBlob>(t, "Blob", m)
        b.attribute("size", { _, t, _, _ -> blob(t, "size").size.toDouble() })
        b.attribute("type", { _, t, _, _ -> blob(t, "type").type })
        b.operation("slice", 0) { f, t, a, _ ->
            val x = blob(t, "slice")
            val start = a.arg(0).let { if (it === Undefined) null else Idl.integer(it, 64, signed = true, what = "Blob.slice start", clamp = true) }
            val end = a.arg(1).let { if (it === Undefined) null else Idl.integer(it, 64, signed = true, what = "Blob.slice end", clamp = true) }
            val contentType = a.arg(2).let { if (it === Undefined) "" else normalizeType(Idl.domString(it)) }
            val size = x.size.toDouble()
            val from = if (start == null) 0.0 else if (start < 0) maxOf(size + start, 0.0) else minOf(start, size)
            val to = if (end == null) size else if (end < 0) maxOf(size + end, 0.0) else minOf(end, size)
            val span = maxOf(to - from, 0.0).toInt()
            JSBlob(f.realm.intrinsic("%Blob.prototype%"), x.data, x.offset + from.toInt(), span, contentType)
        }
        b.promiseOperation("text", 0) { f, t, _, _ -> WebGlobals.utf8Decode(f.realm, blob(t, "text").bytes()) }
        b.promiseOperation("arrayBuffer", 0) { f, t, _, _ ->
            val x = blob(t, "arrayBuffer")
            val buf = ArrayBufferBuiltins.allocate(f.realm, null, x.size.toLong(), -1L)
            System.arraycopy(x.data, x.offset, buf.data, 0, x.size)
            buf
        }
        b.promiseOperation("bytes", 0) { f, t, _, _ ->
            val x = blob(t, "bytes")
            val ta = TypedArrayBuiltins.allocate(f.realm, ElementType.UINT8, TypedArrayBuiltins.protoOf(f.realm, ElementType.UINT8), x.size.toLong())
            System.arraycopy(x.data, x.offset, ta.buffer.data, 0, x.size)
            ta
        }

        val file = WebInterface.define(realm, "File", 2, parent = "Blob") { a, proto ->
            Idl.required(a, 2, "File constructor")
            val parts = convertParts(realm, a[0], "File")
            val name = Idl.usvString(a[1])
            val o = Idl.dictionary(a.arg(2), "FilePropertyBag")
            // FilePropertyBag: the inherited BlobPropertyBag members, then lastModified
            val (native, type) = blobOptions(o, "File")
            val lm = Idl.member(o, "lastModified")
            val lastModified = if (lm === Undefined) Math.floor(realm.agent.currentTimeMillis()) else Idl.integer(lm, 64, signed = true, what = "File lastModified")
            val data = processParts(realm, parts, native, "File")
            JSFile(proto, data, 0, data.size, type, name, lastModified)
        }
        file.attribute("name", { _, t, _, _ -> Idl.self<JSFile>(t, "File", "name").name })
        file.attribute("lastModified", { _, t, _, _ -> Idl.self<JSFile>(t, "File", "lastModified").lastModified })
    }
}
