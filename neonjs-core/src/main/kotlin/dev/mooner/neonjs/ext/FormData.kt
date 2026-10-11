package dev.mooner.neonjs.ext

import dev.mooner.neonjs.builtins.Builtins
import dev.mooner.neonjs.builtins.typeErr
import dev.mooner.neonjs.runtime.*

/**
 * `FormData` (XMLHttpRequest Standard), without form elements: a list of entries whose values are strings or files;
 * and its `multipart/form-data` encoding (HTML Standard) and parsing, for bodies of requests and responses.
 */
internal object FormData {
    class JSFormData(proto: JSObject?) : JSObject(proto) {
        override val className: String get() = "FormData"
        /** The entry list: (name, value) with a String or a [Blob.JSFile] value. */
        @JvmField val entries = ArrayList<Pair<String, Any>>()
    }

    /**
     * "create an entry": [value] is a string or a blob; a blob becomes a File named [filename], "blob" for a blob
     * that is no file, or its own name.
     */
    fun entryValue(realm: Realm, value: Any, filename: String?): Any {
        if (value !is Blob.JSBlob) return value
        if (value is Blob.JSFile && filename == null) return value
        val name = filename ?: if (value is Blob.JSFile) value.name else "blob"
        val lastModified = if (value is Blob.JSFile) value.lastModified else Math.floor(realm.agent.currentTimeMillis())
        return Blob.JSFile(realm.intrinsic("%File.prototype%"), value.data, value.offset, value.size, value.type, name, lastModified)
    }

    /** The (name, value, filename) of `append` / `set`: the Blob overload with three arguments or a Blob value. */
    private fun entryArgs(realm: Realm, a: Array<Any?>, what: String): Pair<String, Any> {
        Idl.required(a, 2, what)
        val name = Idl.usvString(a[0])
        if (a.size >= 3 || a[1] is Blob.JSBlob) {
            val blob = a[1] as? Blob.JSBlob ?: typeErr("$what: the value is not a Blob")
            val filename = if (a.size >= 3 && a[2] !== Undefined) Idl.usvString(a[2]) else null
            return name to entryValue(realm, blob, filename)
        }
        return name to Idl.usvString(a[1])
    }

    fun newFormData(realm: Realm): JSFormData = JSFormData(realm.intrinsic("%FormData.prototype%"))

    /** CR not followed by LF and LF not preceded by CR, each made CRLF. */
    private fun crlf(s: String): String {
        if (s.indexOf('\r') < 0 && s.indexOf('\n') < 0) return s
        val sb = StringBuilder(s.length + 8)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\r') {
                sb.append("\r\n")
                if (i + 1 < s.length && s[i + 1] == '\n') i++
            } else if (c == '\n') {
                sb.append("\r\n")
            } else {
                sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /** A name or filename in a Content-Disposition header: LF, CR and '"' percent-encoded. */
    private fun escapeName(s: String): String = s.replace("\n", "%0A").replace("\r", "%0D").replace("\"", "%22")

    /**
     * The multipart/form-data encoding of [fd]'s entries: the body and its Content-Type (with a boundary of random
     * characters that the body is not searched for, as in browsers: it is long enough not to occur by chance).
     */
    fun multipart(realm: Realm, fd: JSFormData): Pair<ByteArray, String> {
        val random = ByteArray(16)
        Crypto.randomBytes(realm, random)
        val boundary = "----formdata-neonjs-" + random.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val parts = ArrayList<ByteArray>(fd.entries.size * 3 + 1)
        var total = 0L
        fun add(b: ByteArray) {
            total += b.size
            if (total > Int.MAX_VALUE - 8) throw JSException.rangeError("FormData: the body is too large")
            parts.add(b)
        }
        for ((name, value) in fd.entries) {
            val head = StringBuilder("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(escapeName(crlf(name))).append('"')
            if (value is Blob.JSFile) {
                head.append("; filename=\"").append(escapeName(value.name)).append("\"\r\nContent-Type: ")
                    .append(value.type.ifEmpty { "application/octet-stream" }).append("\r\n\r\n")
                add(WebGlobals.utf8(head.toString()))
                add(value.bytes())
                add(CRLF)
            } else {
                head.append("\r\n\r\n").append(crlf(value as String)).append("\r\n")
                add(WebGlobals.utf8(head.toString()))
            }
        }
        add(WebGlobals.utf8("--$boundary--\r\n"))
        realm.agent.reserveAllocation(total)
        val out = ByteArray(total.toInt())
        var at = 0
        for (b in parts) {
            System.arraycopy(b, 0, out, at, b.size)
            at += b.size
        }
        return out to "multipart/form-data; boundary=$boundary"
    }

    private val CRLF = byteArrayOf(13, 10)

    private fun indexOf(data: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty()) return from
        val first = needle[0]
        var i = from
        val last = data.size - needle.size
        outer@ while (i <= last) {
            if (data[i] != first) {
                i++
                continue
            }
            for (k in 1 until needle.size) if (data[i + k] != needle[k]) {
                i++
                continue@outer
            }
            return i
        }
        return -1
    }

    /** A parameter of a Content-Disposition (or Content-Type) header value, unquoted, or null. */
    internal fun headerParam(value: String, param: String): String? {
        var i = value.indexOf(';')
        while (i >= 0 && i < value.length) {
            var j = i + 1
            while (j < value.length && (value[j] == ' ' || value[j] == '\t')) j++
            val eq = value.indexOf('=', j)
            if (eq < 0) return null
            val key = value.substring(j, eq).trim()
            var k = eq + 1
            val v: String
            if (k < value.length && value[k] == '"') {
                val sb = StringBuilder()
                k++
                while (k < value.length && value[k] != '"') {
                    if (value[k] == '\\' && k + 1 < value.length) k++
                    sb.append(value[k])
                    k++
                }
                v = sb.toString()
                k++
            } else {
                val end = value.indexOf(';', k).let { if (it < 0) value.length else it }
                v = value.substring(k, end).trim()
                k = end
            }
            if (key.equals(param, ignoreCase = true)) return v
            i = value.indexOf(';', k)
        }
        return null
    }

    /** Parses a multipart/form-data body with [boundary] into a new FormData (names, filenames and text as UTF-8), or a TypeError. */
    fun parseMultipart(realm: Realm, data: ByteArray, boundary: String): JSFormData {
        val fd = newFormData(realm)
        fun bad(): Nothing = typeErr("FormData: the body is not valid multipart/form-data")
        val delimiter = WebGlobals.utf8("--$boundary")
        var at = indexOf(data, delimiter, 0)
        if (at < 0) bad()
        while (true) {
            at += delimiter.size
            // "--" ends the body; else the part's line ends
            if (at + 1 < data.size && data[at] == '-'.code.toByte() && data[at + 1] == '-'.code.toByte()) return fd
            if (at + 1 >= data.size || data[at] != 13.toByte() || data[at + 1] != 10.toByte()) bad()
            at += 2
            val headersEnd = indexOf(data, byteArrayOf(13, 10, 13, 10), at)
            if (headersEnd < 0) bad()
            var name: String? = null
            var filename: String? = null
            var type = ""
            for (line in String(data, at, headersEnd - at, Charsets.UTF_8).split("\r\n")) {
                val colon = line.indexOf(':')
                if (colon <= 0) bad()
                val key = line.substring(0, colon).trim().lowercase(java.util.Locale.ROOT)
                val value = line.substring(colon + 1).trim()
                if (key == "content-disposition") {
                    if (!value.lowercase(java.util.Locale.ROOT).startsWith("form-data")) bad()
                    name = headerParam(value, "name")
                    filename = headerParam(value, "filename")
                } else if (key == "content-type") {
                    type = value
                }
            }
            if (name == null) bad()
            val bodyStart = headersEnd + 4
            val next = indexOf(data, WebGlobals.utf8("\r\n--$boundary"), bodyStart)
            if (next < 0) bad()
            val bytes = data.copyOfRange(bodyStart, next)
            val value: Any = if (filename != null) {
                Blob.newFile(realm, bytes, type, filename, Math.floor(realm.agent.currentTimeMillis()))
            } else {
                WebGlobals.utf8Decode(realm, bytes)
            }
            fd.entries.add(name to value)
            at = next + 2
            if ((fd.entries.size and 255) == 0) realm.agent.checkInterrupt()
        }
    }

    fun install(realm: Realm) {
        val i = WebInterface.define(realm, "FormData", 0) { a, proto ->
            // no form elements here: a form or submitter argument can only be of a wrong type
            if (a.arg(0) !== Undefined) typeErr("FormData constructor: the argument is not an HTMLFormElement")
            JSFormData(proto)
        }
        fun fd(t: Any?, m: String) = Idl.self<JSFormData>(t, "FormData", m)
        i.operation("append", 2) { f, t, a, _ ->
            val x = fd(t, "append")
            x.entries.add(entryArgs(f.realm, a, "FormData.append"))
            Undefined
        }
        i.operation("delete", 1) { _, t, a, _ ->
            val x = fd(t, "delete")
            Idl.required(a, 1, "FormData.delete")
            val name = Idl.usvString(a[0])
            x.entries.removeIf { it.first == name }
            Undefined
        }
        i.operation("get", 1) { _, t, a, _ ->
            val x = fd(t, "get")
            Idl.required(a, 1, "FormData.get")
            val name = Idl.usvString(a[0])
            x.entries.firstOrNull { it.first == name }?.second ?: Null
        }
        i.operation("getAll", 1) { f, t, a, _ ->
            val x = fd(t, "getAll")
            Idl.required(a, 1, "FormData.getAll")
            val name = Idl.usvString(a[0])
            Builtins.arrayOf(f.realm, x.entries.filter { it.first == name }.map { it.second })
        }
        i.operation("has", 1) { _, t, a, _ ->
            val x = fd(t, "has")
            Idl.required(a, 1, "FormData.has")
            val name = Idl.usvString(a[0])
            x.entries.any { it.first == name }
        }
        i.operation("set", 2) { f, t, a, _ ->
            val x = fd(t, "set")
            val entry = entryArgs(f.realm, a, "FormData.set")
            // replace the first entry of that name, remove the others
            val first = x.entries.indexOfFirst { it.first == entry.first }
            if (first < 0) x.entries.add(entry) else {
                x.entries[first] = entry
                var k = -1
                x.entries.removeIf { k++; k > first && it.first == entry.first }
            }
            Undefined
        }
        i.pairIterable(JSFormData::class.java) { x -> x.entries }
    }
}
