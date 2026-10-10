package dev.mooner.neonjs.ext

import dev.mooner.neonjs.builtins.Builtins
import dev.mooner.neonjs.builtins.typeErr
import dev.mooner.neonjs.runtime.*

/**
 * `Headers` (Fetch Standard): a header list whose names compare case-insensitively, with the "none" guard of
 * headers made by scripts and the "immutable" one. Names and values are ByteStrings (code units up to 0xFF).
 */
internal object Headers {
    class JSHeaders(proto: JSObject?) : JSObject(proto) {
        override val className: String get() = "Headers"
        /** The header list, in order: (name as given, value). */
        @JvmField val list = ArrayList<Pair<String, String>>()
        @JvmField var immutable = false

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
            var k = list.size - 1
            while (k > first) {
                if (list[k].first.equals(name, ignoreCase = true)) list.removeAt(k)
                k--
            }
        }

        /** "sort and combine": lower-case names in order, values combined except Set-Cookie's. */
        fun sortedAndCombined(): List<Pair<Any?, Any?>> {
            val out = ArrayList<Pair<Any?, Any?>>()
            val names = list.map { it.first.lowercase(java.util.Locale.ROOT) }.distinct().sorted()
            for (n in names) {
                if (n == "set-cookie") {
                    for (h in list) if (h.first.equals(n, ignoreCase = true)) out.add(n to h.second)
                } else out.add(n to get(n))
            }
            return out
        }
    }

    private fun isTokenChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || "!#$%&'*+-.^_`|~".indexOf(c) >= 0

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
                h.append(name(p[0], what), value(p[1], what))
            }
            return
        }
        for ((k, v) in Idl.record(init, { Idl.byteString(it, what) }, { Idl.byteString(it, what) })) h.append(name(k, what), value(v, what))
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
            h.append(name, value)
            Undefined
        }
        i.operation("delete", 1) { _, t, a, _ ->
            val h = hs(t, "delete")
            Idl.required(a, 1, "Headers.delete")
            val name = name(a[0], "Headers.delete")
            checkMutable(h, "Headers.delete")
            h.delete(name)
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
            h.set(name, value)
            Undefined
        }
        i.pairIterable(JSHeaders::class.java) { h -> h.sortedAndCombined() }
    }
}
