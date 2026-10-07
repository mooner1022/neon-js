package dev.mooner.neonjs.builtins

import dev.mooner.neonjs.runtime.*
import java.math.BigInteger

/** Object created by JSON.rawJSON (`[[IsRawJSON]]`). */
class RawJSONObject(@JvmField val raw: String) : JSObject(null)

internal object JSONBuiltins {
    fun install(realm: Realm) {
        val json = JSObject(realm.objectPrototype)
        realm.global("JSON", json)
        json.value(JSSymbol.toStringTag, "JSON", Attr.CONFIGURABLE)
        json.method(realm, "parse", 2) { f, _, args, _ ->
            val text = Ops.toString(args.arg(0))
            val parser = JsonParser(f.realm, text)
            val root = parser.parseRoot()
            val reviver = args.arg(1)
            if (Ops.isCallable(reviver)) {
                val holder = JSObject(f.realm.objectPrototype)
                holder.createDataPropertyOrThrow("", root.value)
                internalize(f.realm, holder, "", reviver as JSObject, root)
            } else root.value
        }
        json.method(realm, "stringify", 3) { f, _, args, _ -> stringify(f.realm, args.arg(0), args.arg(1), args.arg(2)) }
        json.method(realm, "rawJSON", 1) { f, _, args, _ ->
            val s = Ops.toString(args.arg(0))
            if (s.isEmpty() || isJsonWs(s[0]) || isJsonWs(s[s.length - 1])) throw JSException.syntaxError("Invalid value for JSON.rawJSON")
            val node = JsonParser(f.realm, s).parseRoot()
            if (node.value is JSObject) throw JSException.syntaxError("Invalid value for JSON.rawJSON")
            val o = RawJSONObject(s)
            o.createDataPropertyOrThrow("rawJSON", s)
            o.preventExtensions()
            Builtins.freeze(o)
            o
        }
        json.method(realm, "isRawJSON", 1) { _, _, args, _ -> args.arg(0) is RawJSONObject }
    }

    private fun isJsonWs(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r'

    /** Parse tree node retaining source text of primitives (json-parse-with-source). */
    class Node(val value: Any?, val source: String?, val elements: List<Node>?, val members: LinkedHashMap<String, Node>?)

    class JsonParser(val realm: Realm, val s: String) {
        var i = 0

        fun err(msg: String = "Unexpected token in JSON at position $i"): Nothing = throw JSException.syntaxError(msg)

        private fun ws() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++ else break
            }
        }

        fun parseRoot(): Node {
            ws()
            val v = value()
            ws()
            if (i != s.length) err()
            return v
        }

        private fun value(): Node {
            if (i >= s.length) err("Unexpected end of JSON input")
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> {
                    val st = i
                    val str = string()
                    Node(str, s.substring(st, i), null, null)
                }
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", Null)
                else -> num()
            }
        }

        private fun lit(word: String, v: Any): Node {
            if (!s.startsWith(word, i)) err()
            i += word.length
            return Node(v, word, null, null)
        }

        private fun num(): Node {
            val st = i
            if (i < s.length && s[i] == '-') i++
            if (i >= s.length) err()
            if (s[i] == '0') i++
            else if (s[i] in '1'..'9') { while (i < s.length && s[i] in '0'..'9') i++ }
            else err()
            if (i < s.length && s[i] == '.') {
                i++
                if (i >= s.length || s[i] !in '0'..'9') err()
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i >= s.length || s[i] !in '0'..'9') err()
                while (i < s.length && s[i] in '0'..'9') i++
            }
            val text = s.substring(st, i)
            return Node(java.lang.Double.parseDouble(text), text, null, null)
        }

        private fun string(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) err("Unterminated string in JSON at position $i")
                val c = s[i]
                if (c == '"') { i++; break }
                if (c < ' ') err("Bad control character in string literal in JSON at position $i")
                if (c == '\\') {
                    i++
                    if (i >= s.length) err()
                    when (s[i]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 >= s.length) err()
                            var v = 0
                            for (j in 1..4) {
                                val h = NumberConv.digitVal(s[i + j], 16)
                                if (h < 0) err()
                                v = v * 16 + h
                            }
                            sb.append(v.toChar())
                            i += 4
                        }
                        else -> err("Bad escaped character in JSON at position $i")
                    }
                    i++
                    continue
                }
                sb.append(c)
                i++
            }
            return sb.toString()
        }

        private fun arr(): Node {
            i++
            val a = JSArray(realm.arrayPrototype)
            val nodes = ArrayList<Node>()
            ws()
            if (i < s.length && s[i] == ']') {
                i++
                return Node(a, null, nodes, null)
            }
            while (true) {
                ws()
                val v = value()
                nodes.add(v)
                a.pushInit(v.value)
                ws()
                if (i >= s.length) err("Unexpected end of JSON input")
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; break }
                err()
            }
            return Node(a, null, nodes, null)
        }

        private fun obj(): Node {
            i++
            val o = JSObject(realm.objectPrototype)
            val members = LinkedHashMap<String, Node>()
            ws()
            if (i < s.length && s[i] == '}') {
                i++
                return Node(o, null, null, members)
            }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') err()
                val k = string()
                ws()
                if (i >= s.length || s[i] != ':') err()
                i++
                ws()
                val v = value()
                o.createDataProperty(PK.fromString(k), v.value)
                members[k] = v
                ws()
                if (i >= s.length) err("Unexpected end of JSON input")
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; break }
                err()
            }
            return Node(o, null, null, members)
        }
    }

    /** InternalizeJSONProperty with source text context. */
    private fun internalize(realm: Realm, holder: JSObject, name: Any, reviver: JSObject, node: Node?): Any? {
        val v = holder.get(PK.fromString(PK.toStringKey(name)), holder)
        val n = if (node != null && Ops.sameValue(node.value, v)) node else null
        if (v is JSObject) {
            if (Ops.isArray(v)) {
                val len = Ops.lengthOfArrayLike(v)
                var k = 0L
                while (k < len) {
                    if (k and 1023L == 1023L) realm.agent.checkInterrupt()
                    val child = n?.elements?.getOrNull(k.toInt())
                    val newEl = internalize(realm, v, k.toString(), reviver, child)
                    val key = PK.fromIndex(k)
                    if (newEl === Undefined) v.delete(key) else v.createDataProperty(key, newEl)
                    k++
                }
            } else {
                val keys = v.ownPropertyKeys().filter { it !is JSSymbol && (v.getOwnProperty(it)?.enumerable == true) }
                for (key in keys) {
                    val ks = PK.toStringKey(key)
                    val child = n?.members?.get(ks)
                    val newEl = internalize(realm, v, ks, reviver, child)
                    if (newEl === Undefined) v.delete(key) else v.createDataProperty(key, newEl)
                }
            }
        }
        val ctx = JSObject(realm.objectPrototype)
        if (n?.source != null && v !is JSObject) ctx.createDataProperty("source", n.source)
        return reviver.call(holder, arrayOf(if (name is Int) name.toString() else name, v, ctx))
    }

    // ------------------------------------------------------------------ stringify

    private class State(val realm: Realm, val replacer: JSObject?, val propertyList: List<String>?, val gap: String) {
        val stack = ArrayList<JSObject>()
        var indent = ""
    }

    fun stringify(realm: Realm, value: Any?, replacerArg: Any?, spaceArg: Any?): Any? {
        var replacerFn: JSObject? = null
        var propList: ArrayList<String>? = null
        if (replacerArg is JSObject) {
            if (replacerArg.isCallable) replacerFn = replacerArg
            else if (Ops.isArray(replacerArg)) {
                propList = ArrayList()
                val seen = HashSet<String>()
                val len = Ops.lengthOfArrayLike(replacerArg)
                var k = 0L
                while (k < len) {
                    if (k and 1023L == 1023L) realm.agent.checkInterrupt()
                    val v = replacerArg.get(PK.fromIndex(k), replacerArg)
                    var item: String? = null
                    when (v) {
                        is CharSequence -> item = v.toString()
                        is Double -> item = NumberConv.toString(v)
                        is JSPrimitiveWrapper -> if (v.primitive is Double) item = Ops.toString(v)
                        is JSStringObject -> item = Ops.toString(v)
                    }
                    if (item != null && seen.add(item)) propList.add(item)
                    k++
                }
            }
        }
        var space = spaceArg
        if (space is JSPrimitiveWrapper && space.primitive is Double) space = Ops.toNumber(space)
        else if (space is JSStringObject) space = Ops.toString(space)
        val gap = when (space) {
            is Double -> {
                val n = minOf(10.0, Ops.integerPart(space))
                if (n >= 1) " ".repeat(n.toInt()) else ""
            }
            is CharSequence -> space.toString().let { if (it.length > 10) it.substring(0, 10) else it }
            else -> ""
        }
        val st = State(realm, replacerFn, propList, gap)
        val wrapper = JSObject(realm.objectPrototype)
        wrapper.createDataPropertyOrThrow("", value)
        val sb = StringBuilder()
        val ok = serializeProperty(st, "", wrapper, sb)
        return if (ok) sb.toString() else Undefined
    }

    private fun serializeProperty(st: State, key: Any, holder: JSObject, sb: StringBuilder): Boolean {
        var value = holder.get(key, holder)
        if (value is JSObject || value is BigInteger) {
            val toJSON = Ops.getV(st.realm, value, "toJSON")
            if (Ops.isCallable(toJSON)) value = (toJSON as JSObject).call(value, arrayOf(PK.toValue(key)))
        }
        if (st.replacer != null) value = st.replacer.call(holder, arrayOf(PK.toValue(key), value))
        if (value is JSObject) {
            when (value) {
                is JSPrimitiveWrapper -> when (value.primitive) {
                    is Double -> value = Ops.toNumber(value)
                    is Boolean -> value = value.primitive
                    is BigInteger -> value = value.primitive
                }
                is JSStringObject -> value = Ops.toString(value)
            }
        }
        when (value) {
            is RawJSONObject -> { sb.append(value.raw); return true }
            Null -> { sb.append("null"); return true }
            true -> { sb.append("true"); return true }
            false -> { sb.append("false"); return true }
            is CharSequence -> { quote(value, sb); return true }
            is Double -> {
                if (value.isNaN() || value.isInfinite()) sb.append("null") else sb.append(NumberConv.toString(value))
                return true
            }
            is BigInteger -> throw JSException.typeError("Do not know how to serialize a BigInt")
            is JSObject -> {
                if (value.isCallable) return false
                if (Ops.isArray(value)) serializeArray(st, value, sb) else serializeObject(st, value, sb)
                return true
            }
            else -> return false
        }
    }

    fun quote(v: CharSequence, sb: StringBuilder) {
        val s = v.toString()
        sb.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                c.isHighSurrogate() -> {
                    if (i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                        sb.append(c).append(s[i + 1])
                        i++
                    } else sb.append(String.format("\\u%04x", c.code))
                }
                c.isLowSurrogate() -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
            i++
        }
        sb.append('"')
    }

    private fun enter(st: State, o: JSObject) {
        for (x in st.stack) if (x === o) throw JSException.typeError("Converting circular structure to JSON")
        if (st.stack.size > 5000) throw JSException.rangeError("Maximum call stack size exceeded")
        st.stack.add(o)
    }

    private fun serializeObject(st: State, o: JSObject, sb: StringBuilder) {
        enter(st, o)
        val stepback = st.indent
        st.indent += st.gap
        val keys: List<Any> = st.propertyList?.map { PK.fromString(it) }
            ?: o.ownPropertyKeys().filter { it !is JSSymbol && (o.getOwnProperty(it)?.enumerable == true) }
        sb.append('{')
        var first = true
        for (k in keys) {
            val mark = sb.length
            if (!first) sb.append(',')
            if (st.gap.isNotEmpty()) sb.append('\n').append(st.indent)
            quote(PK.toStringKey(k), sb)
            sb.append(':')
            if (st.gap.isNotEmpty()) sb.append(' ')
            if (!serializeProperty(st, k, o, sb)) sb.setLength(mark)
            else first = false
        }
        if (!first && st.gap.isNotEmpty()) sb.append('\n').append(stepback)
        sb.append('}')
        st.stack.removeAt(st.stack.size - 1)
        st.indent = stepback
    }

    private fun serializeArray(st: State, a: JSObject, sb: StringBuilder) {
        enter(st, a)
        val stepback = st.indent
        st.indent += st.gap
        val len = Ops.lengthOfArrayLike(a)
        sb.append('[')
        var i = 0L
        while (i < len) {
            if (i > 0) sb.append(',')
            if (st.gap.isNotEmpty()) sb.append('\n').append(st.indent)
            if (!serializeProperty(st, PK.fromIndex(i), a, sb)) sb.append("null")
            i++
        }
        if (len > 0 && st.gap.isNotEmpty()) sb.append('\n').append(stepback)
        sb.append(']')
        st.stack.removeAt(st.stack.size - 1)
        st.indent = stepback
    }
}

internal object ReflectBuiltins {
    fun install(realm: Realm) {
        val r = JSObject(realm.objectPrototype)
        realm.global("Reflect", r)
        r.value(JSSymbol.toStringTag, "Reflect", Attr.CONFIGURABLE)
        fun target(v: Any?): JSObject = v as? JSObject ?: typeErr("Reflect method called on non-object")
        r.method(realm, "apply", 3) { _, _, args, _ ->
            val f = args.arg(0)
            if (!Ops.isCallable(f)) typeErr("${Ops.describe(f)} is not a function")
            (f as JSObject).call(args.arg(1), Ops.createListFromArrayLike(args.arg(2)))
        }
        r.method(realm, "construct", 2) { _, _, args, _ ->
            val f = args.arg(0)
            if (!Ops.isConstructor(f)) typeErr("${Ops.describe(f)} is not a constructor")
            val nt = if (args.size > 2) args[2] else f
            if (!Ops.isConstructor(nt)) typeErr("${Ops.describe(nt)} is not a constructor")
            (f as JSObject).construct(Ops.createListFromArrayLike(args.arg(1)), nt as JSObject)
        }
        r.method(realm, "defineProperty", 3) { _, _, args, _ ->
            val t = target(args.arg(0))
            val k = Ops.toPropertyKey(args.arg(1))
            t.defineOwnProperty(k, Ops.toPropertyDescriptor(args.arg(2)))
        }
        r.method(realm, "deleteProperty", 2) { _, _, args, _ -> target(args.arg(0)).delete(Ops.toPropertyKey(args.arg(1))) }
        r.method(realm, "get", 2) { _, _, args, _ ->
            val t = target(args.arg(0))
            val k = Ops.toPropertyKey(args.arg(1))
            t.get(k, if (args.size > 2) args[2] else t)
        }
        r.method(realm, "getOwnPropertyDescriptor", 2) { f, _, args, _ ->
            val t = target(args.arg(0))
            Ops.fromPropertyDescriptor(f.realm, t.getOwnProperty(Ops.toPropertyKey(args.arg(1))))
        }
        r.method(realm, "getPrototypeOf", 1) { _, _, args, _ -> target(args.arg(0)).getPrototypeOf() ?: Null }
        r.method(realm, "has", 2) { _, _, args, _ -> target(args.arg(0)).hasProperty(Ops.toPropertyKey(args.arg(1))) }
        r.method(realm, "isExtensible", 1) { _, _, args, _ -> target(args.arg(0)).isExtensible() }
        r.method(realm, "ownKeys", 1) { f, _, args, _ -> Builtins.arrayOf(f.realm, target(args.arg(0)).ownPropertyKeys().map { PK.toValue(it) }) }
        r.method(realm, "preventExtensions", 1) { _, _, args, _ -> target(args.arg(0)).preventExtensions() }
        r.method(realm, "set", 3) { _, _, args, _ ->
            val t = target(args.arg(0))
            val k = Ops.toPropertyKey(args.arg(1))
            t.set(k, args.arg(2), if (args.size > 3) args[3] else t)
        }
        r.method(realm, "setPrototypeOf", 2) { _, _, args, _ ->
            val t = target(args.arg(0))
            val p = args.arg(1)
            if (p !is JSObject && p !== Null) typeErr("Object prototype may only be an Object or null")
            t.setPrototypeOf(p as? JSObject)
        }

        // Proxy
        val proxy = NativeFunction(realm, "Proxy", 2, { _, _, args, nt ->
            if (nt == null) typeErr("Constructor Proxy requires 'new'")
            createProxy(args.arg(0), args.arg(1))
        }, isConstructor = true)
        realm.global("Proxy", proxy)
        proxy.method(realm, "revocable", 2) { f, _, args, _ ->
            val p = createProxy(args.arg(0), args.arg(1))
            val revoke = NativeFunction(f.realm, "", 0, { rf, _, _, _ ->
                val pp = rf.slot0 as ProxyObject?
                if (pp != null) {
                    rf.slot0 = null
                    pp.target = null
                    pp.handler = null
                }
                Undefined
            })
            revoke.slot0 = p
            val o = JSObject(f.realm.objectPrototype)
            o.createDataPropertyOrThrow("proxy", p)
            o.createDataPropertyOrThrow("revoke", revoke)
            o
        }
    }

    fun createProxy(target: Any?, handler: Any?): ProxyObject {
        if (target !is JSObject) typeErr("Cannot create proxy with a non-object as target or handler")
        if (handler !is JSObject) typeErr("Cannot create proxy with a non-object as target or handler")
        return ProxyObject(target, handler)
    }
}
