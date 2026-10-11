package dev.mooner.neonjs.ext

import dev.mooner.neonjs.builtins.Builtins
import dev.mooner.neonjs.builtins.typeErr
import dev.mooner.neonjs.runtime.*

/**
 * `Headers` (Fetch Standard): a header list whose names compare case-insensitively, with the "none" guard of
 * headers made by scripts, the "request" one of a Request's and the "immutable" one. Names and values are ByteStrings
 * (code units up to 0xFF).
 */
internal object Headers {
    class JSHeaders(proto: JSObject?) : JSObject(proto) {
        override val className: String get() = "Headers"
        /** The header list, in order: (name as given, value). */
        @JvmField val list = ArrayList<Pair<String, String>>()
        @JvmField var immutable = false
        /** The "request" guard: names the transport owns ([isForbiddenRequestHeader]) are left out silently. */
        @JvmField var requestGuard = false

        /** Whether the guard leaves the header [name] out (with [value], for a method override). */
        fun guards(name: String, value: String? = null) = requestGuard && (isForbiddenRequestHeader(name) || value != null && isForbiddenOverride(name, value))

        fun contains(name: String) = list.any { it.first.equals(name, ignoreCase = true) }

        /** "get": the values of [name] joined with ", ", or null. */
        fun get(name: String): String? {
            val values = list.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }
            return if (values.isEmpty()) null else values.joinToString(", ")
        }

        fun append(name: String, value: String) {
            // the name keeps the casing of the first header of that name
            val existing = list.firstOrNull { it.first.equals(name, ignoreCase = true) }?.first ?: name
            list.add(existing to value)
        }

        fun delete(name: String) {
            list.removeIf { it.first.equals(name, ignoreCase = true) }
        }

        fun set(name: String, value: String) {
            val first = list.indexOfFirst { it.first.equals(name, ignoreCase = true) }
            if (first < 0) {
                list.add(name to value)
                return
            }
            list[first] = list[first].first to value
            var k = -1
            list.removeIf { k++; k > first && it.first.equals(name, ignoreCase = true) }
        }

        /** "sort and combine": lower-case names in order, values combined except Set-Cookie's. */
        fun sortedAndCombined(): List<Pair<Any?, Any?>> {
            val values = java.util.TreeMap<String, ArrayList<String>>()
            for ((n, v) in list) values.getOrPut(n.lowercase(java.util.Locale.ROOT)) { ArrayList(1) }.add(v)
            val out = ArrayList<Pair<Any?, Any?>>(values.size)
            for ((n, vs) in values) {
                if (n == "set-cookie") for (v in vs) out.add(n to v) else out.add(n to vs.joinToString(", "))
            }
            return out
        }
    }

    private val FORBIDDEN_REQUEST_HEADERS = setOf(
        "accept-encoding", "connection", "content-length", "expect", "host", "keep-alive", "te", "trailer",
        "transfer-encoding", "upgrade",
    )

    /**
     * The request headers that the transport and not the script decides: the framing and routing of the request
     * (`Host`, `Content-Length`, `Connection`, `Transfer-Encoding`...), its content coding (`Accept-Encoding`: the
     * response comes decoded), proxies' (`Proxy-*`) and the WebSocket handshake's (`Sec-WebSocket-*`). A narrower
     * list than the Fetch Standard's forbidden request-header names: like Node's and Deno's fetch, scripts may set
     * `Cookie`, `Origin`, `Referer` and `User-Agent`.
     */
    fun isForbiddenRequestHeader(name: String): Boolean {
        val n = name.lowercase(java.util.Locale.ROOT)
        return n in FORBIDDEN_REQUEST_HEADERS || n.startsWith("proxy-") || n.startsWith("sec-websocket-")
    }

    private val METHOD_OVERRIDES = setOf("x-http-method", "x-http-method-override", "x-method-override")
    private val FORBIDDEN_METHODS = setOf("CONNECT", "TRACE", "TRACK")

    /** A method override header naming a forbidden method (CONNECT, TRACE, TRACK) among its comma-separated values. */
    private fun isForbiddenOverride(name: String, value: String): Boolean {
        if (name.lowercase(java.util.Locale.ROOT) !in METHOD_OVERRIDES) return false
        return value.split(',').any { it.trim(' ', '\t').uppercase(java.util.Locale.ROOT) in FORBIDDEN_METHODS }
    }

    private fun isTokenChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || "!#$%&'*+-.^_`|~".indexOf(c) >= 0

    fun isToken(s: String) = s.isNotEmpty() && s.all { isTokenChar(it) }

    /** A header name: a non-empty token, else a TypeError. */
    fun name(v: Any?, what: String): String {
        val s = Idl.byteString(v, what)
        if (s.isEmpty() || !s.all { isTokenChar(it) }) typeErr("$what: '$s' is not a valid header name")
        return s
    }

    private fun isHttpWhitespace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r'

    /** A header value: normalized (HTTP whitespace trimmed), without NUL, CR or LF, else a TypeError. */
    fun value(v: Any?, what: String): String {
        val s = Idl.byteString(v, what).trim { isHttpWhitespace(it) }
        for (c in s) if (c == '\u0000' || c == '\n' || c == '\r') typeErr("$what: the header value contains NUL, CR or LF")
        return s
    }

    private fun checkMutable(h: JSHeaders, what: String) {
        if (h.immutable) typeErr("$what: the headers are immutable")
    }

    /** "fill": from a `HeadersInit` (pairs or a record). */
    fun fill(realm: Realm, h: JSHeaders, init: Any?, what: String) {
        if (init !is JSObject) typeErr("$what: the init is not an object")
        if (Ops.getMethod(init, JSSymbol.iterator) !== Undefined) {
            val pairs = Idl.sequence(realm, init, what) { inner -> Idl.sequence(realm, inner, what) { Idl.byteString(it, what) } }
            for (p in pairs) {
                if (p.size != 2) typeErr("$what: a header pair must have exactly two items")
                val n = name(p[0], what)
                val v = value(p[1], what)
                if (!h.guards(n, v)) h.append(n, v)
            }
            return
        }
        for ((k, v) in Idl.record(init, { Idl.byteString(it, what) }, { Idl.byteString(it, what) })) {
            val n = name(k, what)
            val value = value(v, what)
            if (!h.guards(n, value)) h.append(n, value)
        }
    }

    fun install(realm: Realm) {
        val i = WebInterface.define(realm, "Headers", 0) { a, proto ->
            val h = JSHeaders(proto)
            if (a.arg(0) !== Undefined) fill(realm, h, a[0], "Headers constructor")
            h
        }
        fun hs(t: Any?, m: String) = Idl.self<JSHeaders>(t, "Headers", m)
        i.operation("append", 2) { _, t, a, _ ->
            val h = hs(t, "append")
            Idl.required(a, 2, "Headers.append")
            val name = name(a[0], "Headers.append")
            val value = value(a[1], "Headers.append")
            checkMutable(h, "Headers.append")
            if (!h.guards(name, value)) h.append(name, value)
            Undefined
        }
        i.operation("delete", 1) { _, t, a, _ ->
            val h = hs(t, "delete")
            Idl.required(a, 1, "Headers.delete")
            val name = name(a[0], "Headers.delete")
            checkMutable(h, "Headers.delete")
            if (!h.guards(name)) h.delete(name)
            Undefined
        }
        i.operation("get", 1) { _, t, a, _ ->
            val h = hs(t, "get")
            Idl.required(a, 1, "Headers.get")
            h.get(name(a[0], "Headers.get")) ?: Null
        }
        i.operation("getSetCookie", 0) { f, t, _, _ ->
            val h = hs(t, "getSetCookie")
            Builtins.arrayOf(f.realm, h.list.filter { it.first.equals("set-cookie", ignoreCase = true) }.map { it.second })
        }
        i.operation("has", 1) { _, t, a, _ ->
            val h = hs(t, "has")
            Idl.required(a, 1, "Headers.has")
            h.contains(name(a[0], "Headers.has"))
        }
        i.operation("set", 2) { _, t, a, _ ->
            val h = hs(t, "set")
            Idl.required(a, 2, "Headers.set")
            val name = name(a[0], "Headers.set")
            val value = value(a[1], "Headers.set")
            checkMutable(h, "Headers.set")
            if (!h.guards(name, value)) h.set(name, value)
            Undefined
        }
        i.pairIterable(JSHeaders::class.java) { h -> h.sortedAndCombined() }
    }
}
