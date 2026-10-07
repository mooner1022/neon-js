package io.neonjs.builtins

import io.neonjs.runtime.*
import io.neonjs.vm.*

internal object PromiseBuiltins {
    fun install(realm: Realm) {
        val proto = JSObject(realm.objectPrototype)
        realm.promisePrototype = proto
        val ctor = makeCtor(realm, "Promise", 1, proto) { f, _, args, nt ->
            if (nt == null) typeErr("Promise constructor cannot be invoked without 'new'")
            val executor = args.arg(0)
            if (!Ops.isCallable(executor)) typeErr("Promise resolver ${Ops.describe(executor)} is not a function")
            val p = JSPromise(Ops.getPrototypeFromConstructor(nt) { it.promisePrototype })
            val (res, rej) = Promises.createResolvingFunctions(f.realm, p)
            try {
                (executor as JSObject).call(Undefined, arrayOf(res, rej))
            } catch (e: JSException) {
                rej.call(Undefined, arrayOf(e.value))
            }
            p
        }
        realm.promiseConstructor = ctor
        realm.global("Promise", ctor)
        ctor.getter(realm, JSSymbol.species) { _, t, _, _ -> t }

        ctor.method(realm, "all", 1) { f, t, args, _ -> combinator(f.realm, t, args.arg(0), 0) }
        ctor.method(realm, "allSettled", 1) { f, t, args, _ -> combinator(f.realm, t, args.arg(0), 1) }
        ctor.method(realm, "allKeyed", 1) { f, t, args, _ -> keyedCombinator(f.realm, t, args.arg(0), false) }
        ctor.method(realm, "allSettledKeyed", 1) { f, t, args, _ -> keyedCombinator(f.realm, t, args.arg(0), true) }
        ctor.method(realm, "any", 1) { f, t, args, _ -> combinator(f.realm, t, args.arg(0), 2) }
        ctor.method(realm, "race", 1) { f, t, args, _ -> combinator(f.realm, t, args.arg(0), 3) }
        ctor.method(realm, "reject", 1) { f, t, args, _ ->
            val cap = Promises.newPromiseCapability(f.realm, t)
            Ops.call(cap.reject, Undefined, arrayOf(args.arg(0)))
            cap.promise
        }
        ctor.method(realm, "resolve", 1) { f, t, args, _ ->
            if (t !is JSObject) typeErr("PromiseResolve called on non-object")
            Promises.promiseResolve(f.realm, t, args.arg(0))
        }
        ctor.method(realm, "withResolvers", 0) { f, t, _, _ ->
            val cap = Promises.newPromiseCapability(f.realm, t)
            val o = JSObject(f.realm.objectPrototype)
            o.createDataPropertyOrThrow("promise", cap.promise)
            o.createDataPropertyOrThrow("resolve", cap.resolve)
            o.createDataPropertyOrThrow("reject", cap.reject)
            o
        }
        ctor.method(realm, "try", 1) { f, t, args, _ ->
            if (t !is JSObject) typeErr("Promise.try called on non-object")
            val r = try {
                Ops.call(args.arg(0), Undefined, if (args.size > 1) args.copyOfRange(1, args.size) else EMPTY_ARGS)
            } catch (e: JSException) {
                val cap = Promises.newPromiseCapability(f.realm, t)
                Ops.call(cap.reject, Undefined, arrayOf(e.value))
                return@method cap.promise
            }
            // a returned promise of the same constructor is not wrapped
            Promises.promiseResolve(f.realm, t, r)
        }

        proto.method(realm, "then", 2) { f, t, args, _ ->
            val p = t as? JSPromise ?: typeErr("Method Promise.prototype.then called on incompatible receiver ${Ops.describe(t)}")
            val c = Ops.speciesConstructor(p, f.realm.promiseConstructor)
            val cap = Promises.newPromiseCapability(f.realm, c)
            Promises.performThen(f.realm, p, args.arg(0), args.arg(1), cap)
        }
        proto.method(realm, "catch", 1) { _, t, args, _ -> Ops.invoke(t, "then", arrayOf(Undefined, args.arg(0))) }
        proto.method(realm, "finally", 1) { f, t, args, _ ->
            if (t !is JSObject) typeErr("Promise.prototype.finally called on non-object")
            val c = Ops.speciesConstructor(t, f.realm.promiseConstructor)
            val onFinally = args.arg(0)
            val thenFinally: Any?
            val catchFinally: Any?
            if (!Ops.isCallable(onFinally)) {
                thenFinally = onFinally
                catchFinally = onFinally
            } else {
                thenFinally = NativeFunction(f.realm, "", 1, { ff, _, a, _ ->
                    val value = a.arg(0)
                    val result = (onFinally as JSObject).call(Undefined, EMPTY_ARGS)
                    val promise = Promises.promiseResolve(ff.realm, c, result)
                    val valueThunk = NativeFunction(ff.realm, "", 0, { _, _, _, _ -> value })
                    Ops.invoke(promise, "then", arrayOf(valueThunk))
                })
                catchFinally = NativeFunction(f.realm, "", 1, { ff, _, a, _ ->
                    val reason = a.arg(0)
                    val result = (onFinally as JSObject).call(Undefined, EMPTY_ARGS)
                    val promise = Promises.promiseResolve(ff.realm, c, result)
                    val thrower = NativeFunction(ff.realm, "", 0, { _, _, _, _ -> throw JSException(reason) })
                    Ops.invoke(promise, "then", arrayOf(thrower))
                })
            }
            Ops.invoke(t, "then", arrayOf(thenFinally, catchFinally))
        }
        proto.value(JSSymbol.toStringTag, "Promise", Attr.CONFIGURABLE)
    }

    /** kind: 0 all, 1 allSettled, 2 any, 3 race */
    private fun combinator(realm: Realm, c: Any?, iterable: Any?, kind: Int): Any? {
        val cap = Promises.newPromiseCapability(realm, c)
        c as JSObject
        val promiseResolve: Any?
        try {
            promiseResolve = c.get("resolve", c)
            if (!Ops.isCallable(promiseResolve)) typeErr("Promise resolve is not a function")
        } catch (e: JSException) {
            Ops.call(cap.reject, Undefined, arrayOf(e.value))
            return cap.promise
        }
        val rec: IteratorRecord
        try {
            rec = Iteration.getIterator(realm, iterable, false)
        } catch (e: JSException) {
            Ops.call(cap.reject, Undefined, arrayOf(e.value))
            return cap.promise
        }
        try {
            return performCombinator(realm, rec, c, cap, promiseResolve, kind)
        } catch (e: JSException) {
            if (!rec.done) Iteration.closeOnThrow(rec)
            Ops.call(cap.reject, Undefined, arrayOf(e.value))
            return cap.promise
        }
    }

    /** Promise.allKeyed ([settled] false) and Promise.allSettledKeyed ([settled] true), proposal-await-dictionary. */
    private fun keyedCombinator(realm: Realm, c: Any?, promises: Any?, settled: Boolean): Any? {
        val cap = Promises.newPromiseCapability(realm, c)
        c as JSObject
        val promiseResolve: Any?
        try {
            promiseResolve = c.get("resolve", c)
            if (!Ops.isCallable(promiseResolve)) typeErr("Promise resolve is not a function")
        } catch (e: JSException) {
            Ops.call(cap.reject, Undefined, arrayOf(e.value))
            return cap.promise
        }
        if (promises !is JSObject) {
            val name = if (settled) "allSettledKeyed" else "allKeyed"
            Ops.call(cap.reject, Undefined, arrayOf(realm.newError(ErrorKind.TYPE, "Promise.$name requires an object argument")))
            return cap.promise
        }
        try {
            performKeyed(realm, promises, c, cap, promiseResolve, settled)
        } catch (e: JSException) {
            Ops.call(cap.reject, Undefined, arrayOf(e.value))
        }
        return cap.promise
    }

    /** Shared state of one PerformPromiseAllKeyed: the entries list and `[[RemainingElements]]`. */
    private class KeyedState(@JvmField val cap: PromiseCapability) {
        @JvmField val keys = ArrayList<Any>()
        @JvmField val values = ArrayList<Any?>()
        @JvmField var remaining = 1

        /** Decrements `[[RemainingElements]]`; at zero resolves with CreateKeyedPromiseCombinatorResultObject(entries). */
        fun elementDone(): Any? {
            if (--remaining != 0) return Undefined
            val obj = JSObject(null)
            for (i in keys.indices) obj.createDataPropertyOrThrow(keys[i], values[i])
            return Ops.call(cap.resolve, Undefined, arrayOf(obj))
        }
    }

    /** PerformPromiseAllKeyed(variant, promises, ctor, resultCapability, promiseResolve) */
    private fun performKeyed(realm: Realm, promises: JSObject, c: JSObject, cap: PromiseCapability, promiseResolve: Any?, settled: Boolean) {
        val st = KeyedState(cap)
        var steps = 0
        for (key in promises.ownPropertyKeys()) {
            if (++steps and 1023 == 0) realm.agent.checkInterrupt()
            val desc = promises.getOwnProperty(key) ?: continue
            if (!desc.enumerable) continue
            val propertyValue = promises.get(key, promises)
            val index = st.keys.size
            st.keys.add(key)
            st.values.add(Undefined)
            val nextPromise = Ops.call(promiseResolve, c, arrayOf(propertyValue))
            val alreadyCalled = BooleanArray(1)
            val onFulfilled = keyedElement(realm, st, index, alreadyCalled, if (settled) "fulfilled" else null)
            val onRejected = if (settled) keyedElement(realm, st, index, alreadyCalled, "rejected") else cap.reject
            st.remaining++
            Ops.invoke(nextPromise, "then", arrayOf(onFulfilled, onRejected))
        }
        st.elementDone()
    }

    /**
     * A keyed combinator element function: stores the value (or, with a [status], a settlement record) at [index]
     * once, sharing [alreadyCalled] with its sibling.
     */
    private fun keyedElement(realm: Realm, st: KeyedState, index: Int, alreadyCalled: BooleanArray, status: String?): NativeFunction =
        NativeFunction(realm, "", 1, { ff, _, a, _ ->
            if (alreadyCalled[0]) Undefined
            else {
                alreadyCalled[0] = true
                val v = a.arg(0)
                st.values[index] = if (status == null) v else {
                    val o = JSObject(ff.realm.objectPrototype)
                    o.createDataPropertyOrThrow("status", status)
                    o.createDataPropertyOrThrow(if (status == "fulfilled") "value" else "reason", v)
                    o
                }
                st.elementDone()
            }
        })

    private fun performCombinator(realm: Realm, rec: IteratorRecord, c: JSObject, cap: PromiseCapability, promiseResolve: Any?, kind: Int): Any? {
        val values = ArrayList<Any?>()
        val remaining = IntArray(1) { 1 }
        var index = 0
        fun finish() {
            when (kind) {
                0, 1 -> Ops.call(cap.resolve, Undefined, arrayOf(Builtins.arrayOf(realm, values)))
                2 -> {
                    val err = realm.newError(ErrorKind.AGGREGATE, "All promises were rejected")
                    err.defineOwn("errors", Builtins.arrayOf(realm, values), Attr.WC)
                    Ops.call(cap.reject, Undefined, arrayOf(err))
                }
            }
        }
        while (true) {
            val next = Iteration.stepValue(rec)
            if (next === NotFound) {
                if (kind != 3) {
                    remaining[0]--
                    if (remaining[0] == 0) finish()
                }
                return cap.promise
            }
            if (kind != 3) values.add(Undefined)
            val nextPromise = Ops.call(promiseResolve, c, arrayOf(next))
            val i = index
            when (kind) {
                0 -> {
                    val called = BooleanArray(1)
                    val onFul = NativeFunction(realm, "", 1, { _, _, a, _ ->
                        if (!called[0]) {
                            called[0] = true
                            values[i] = a.arg(0)
                            remaining[0]--
                            if (remaining[0] == 0) finish()
                        }
                        Undefined
                    })
                    remaining[0]++
                    Ops.invoke(nextPromise, "then", arrayOf(onFul, cap.reject))
                }
                1 -> {
                    val called = BooleanArray(1)
                    fun settle(status: String, key: String) = NativeFunction(realm, "", 1, { ff, _, a, _ ->
                        if (!called[0]) {
                            called[0] = true
                            val o = JSObject(ff.realm.objectPrototype)
                            o.createDataPropertyOrThrow("status", status)
                            o.createDataPropertyOrThrow(key, a.arg(0))
                            values[i] = o
                            remaining[0]--
                            if (remaining[0] == 0) finish()
                        }
                        Undefined
                    })
                    remaining[0]++
                    Ops.invoke(nextPromise, "then", arrayOf(settle("fulfilled", "value"), settle("rejected", "reason")))
                }
                2 -> {
                    val called = BooleanArray(1)
                    val onRej = NativeFunction(realm, "", 1, { _, _, a, _ ->
                        if (!called[0]) {
                            called[0] = true
                            values[i] = a.arg(0)
                            remaining[0]--
                            if (remaining[0] == 0) finish()
                        }
                        Undefined
                    })
                    remaining[0]++
                    Ops.invoke(nextPromise, "then", arrayOf(cap.resolve, onRej))
                }
                else -> Ops.invoke(nextPromise, "then", arrayOf(cap.resolve, cap.reject))
            }
            index++
        }
    }
}

internal object GlobalBuiltins {
    fun install(realm: Realm) {
        val g = realm.globalObject
        g.defineOwn("globalThis", g, Attr.WC)
        g.defineOwn("Infinity", Double.POSITIVE_INFINITY, Attr.NONE)
        g.defineOwn("NaN", Double.NaN, Attr.NONE)
        g.defineOwn("undefined", Undefined, Attr.NONE)
        realm.evalFunction = g.method(realm, "eval", 1) { f, _, args, _ -> Evaluator.indirectEval(f.realm, args.arg(0)) }
        g.method(realm, "isFinite", 1) { _, _, args, _ -> val d = Ops.toNumber(args.arg(0)); !d.isNaN() && !d.isInfinite() }
        g.method(realm, "isNaN", 1) { _, _, args, _ -> Ops.toNumber(args.arg(0)).isNaN() }
        val pf = g.method(realm, "parseFloat", 1) { _, _, args, _ -> parseFloat(Ops.toString(args.arg(0))) }
        val pi = g.method(realm, "parseInt", 2) { _, _, args, _ -> parseInt(Ops.toString(args.arg(0)), args.arg(1)) }
        val numCtor = g.get("Number") as JSObject
        numCtor.defineOwn("parseFloat", pf, Attr.WC)
        numCtor.defineOwn("parseInt", pi, Attr.WC)
        g.method(realm, "decodeURI", 1) { _, _, args, _ -> URICoding.decode(Ops.toString(args.arg(0)), ";/?:@&=+$,#") }
        g.method(realm, "decodeURIComponent", 1) { _, _, args, _ -> URICoding.decode(Ops.toString(args.arg(0)), "") }
        g.method(realm, "encodeURI", 1) { _, _, args, _ -> URICoding.encode(Ops.toString(args.arg(0)), ";/?:@&=+$,-_.!~*'()#") }
        g.method(realm, "encodeURIComponent", 1) { _, _, args, _ -> URICoding.encode(Ops.toString(args.arg(0)), "-_.!~*'()") }
        g.method(realm, "escape", 1) { _, _, args, _ -> URICoding.escape(Ops.toString(args.arg(0))) }
        g.method(realm, "unescape", 1) { _, _, args, _ -> URICoding.unescape(Ops.toString(args.arg(0))) }
    }

    fun parseFloat(input: String): Double {
        val s = trimStart(input)
        val n = s.length
        var i = 0
        var neg = false
        if (i < n && (s[i] == '+' || s[i] == '-')) { neg = s[i] == '-'; i++ }
        if (s.startsWith("Infinity", i)) return if (neg) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        val numStart = i
        var intDigits = 0
        while (i < n && s[i] in '0'..'9') { i++; intDigits++ }
        var fracDigits = 0
        var end = i
        if (i < n && s[i] == '.') {
            var j = i + 1
            while (j < n && s[j] in '0'..'9') { j++; fracDigits++ }
            if (intDigits > 0 || fracDigits > 0) { i = j; end = j }
        }
        if (intDigits == 0 && fracDigits == 0) return Double.NaN
        if (i < n && (s[i] == 'e' || s[i] == 'E')) {
            var j = i + 1
            if (j < n && (s[j] == '+' || s[j] == '-')) j++
            var ed = 0
            while (j < n && s[j] in '0'..'9') { j++; ed++ }
            if (ed > 0) end = j
        }
        var lit = s.substring(numStart, end)
        if (lit.startsWith(".")) lit = "0$lit"
        lit = lit.replace(".e", ".0e").replace(".E", ".0E")
        if (lit.endsWith(".")) lit += "0"
        val v = try { java.lang.Double.parseDouble(lit) } catch (_: NumberFormatException) { return Double.NaN }
        return if (neg) -v else v
    }

    private fun trimStart(s: String): String {
        var i = 0
        while (i < s.length && NumberConv.isJSWhitespace(s[i])) i++
        return s.substring(i)
    }

    fun parseInt(input: String, radixArg: Any?): Double {
        var s = trimStart(input)
        var sign = 1
        if (s.isNotEmpty() && (s[0] == '-' || s[0] == '+')) {
            if (s[0] == '-') sign = -1
            s = s.substring(1)
        }
        var r = Ops.toInt32(radixArg)
        var stripPrefix = true
        if (r != 0) {
            if (r !in 2..36) return Double.NaN
            if (r != 16) stripPrefix = false
        } else r = 10
        if (stripPrefix && s.length >= 2 && s[0] == '0' && (s[1] == 'x' || s[1] == 'X')) {
            s = s.substring(2)
            r = 16
        }
        var end = 0
        while (end < s.length && NumberConv.digitVal(s[end], r) >= 0) end++
        if (end == 0) return Double.NaN
        val z = s.substring(0, end)
        val v = if (r == 10 && end > 15) {
            java.lang.Double.parseDouble(z)
        } else if (end <= 10) java.lang.Long.parseLong(z, r).toDouble()
        else java.math.BigInteger(z, r).toDouble()
        return sign * v
    }
}

internal object URICoding {
    private const val ALNUM = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private const val HEX = "0123456789ABCDEF"

    fun encode(s: String, extra: String): String {
        val sb = StringBuilder()
        var k = 0
        while (k < s.length) {
            val c = s[k]
            if (ALNUM.indexOf(c) >= 0 || extra.indexOf(c) >= 0) {
                sb.append(c)
                k++
                continue
            }
            val cp: Int
            if (c.isLowSurrogate()) throw JSException.uriError("URI malformed")
            if (c.isHighSurrogate()) {
                if (k + 1 >= s.length || !s[k + 1].isLowSurrogate()) throw JSException.uriError("URI malformed")
                cp = Character.toCodePoint(c, s[k + 1])
                k += 2
            } else {
                cp = c.code
                k++
            }
            val bytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                sb.append('%').append(HEX[v shr 4]).append(HEX[v and 15])
            }
        }
        return sb.toString()
    }

    /** ASCII hex digit value or -1 (Character.digit would also accept non-ASCII digits such as U+0661). */
    private fun hexVal(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    fun decode(s: String, reserved: String): String {
        val sb = StringBuilder()
        var k = 0
        val n = s.length
        while (k < n) {
            val c = s[k]
            if (c != '%') {
                sb.append(c)
                k++
                continue
            }
            val start = k
            if (k + 2 >= n) throw JSException.uriError("URI malformed")
            val h1 = hexVal(s[k + 1])
            val h2 = hexVal(s[k + 2])
            if (h1 < 0 || h2 < 0) throw JSException.uriError("URI malformed")
            val b = (h1 shl 4) or h2
            k += 3
            if (b and 0x80 == 0) {
                val ch = b.toChar()
                if (reserved.indexOf(ch) >= 0) sb.append(s, start, k) else sb.append(ch)
                continue
            }
            val nBytes = when {
                b and 0xE0 == 0xC0 -> 2
                b and 0xF0 == 0xE0 -> 3
                b and 0xF8 == 0xF0 -> 4
                else -> throw JSException.uriError("URI malformed")
            }
            val octets = IntArray(nBytes)
            octets[0] = b
            for (j in 1 until nBytes) {
                if (k + 2 >= n || s[k] != '%') throw JSException.uriError("URI malformed")
                val a1 = hexVal(s[k + 1])
                val a2 = hexVal(s[k + 2])
                if (a1 < 0 || a2 < 0) throw JSException.uriError("URI malformed")
                val bb = (a1 shl 4) or a2
                if (bb and 0xC0 != 0x80) throw JSException.uriError("URI malformed")
                octets[j] = bb
                k += 3
            }
            val cp = when (nBytes) {
                2 -> ((octets[0] and 0x1F) shl 6) or (octets[1] and 0x3F)
                3 -> ((octets[0] and 0x0F) shl 12) or ((octets[1] and 0x3F) shl 6) or (octets[2] and 0x3F)
                else -> ((octets[0] and 0x07) shl 18) or ((octets[1] and 0x3F) shl 12) or ((octets[2] and 0x3F) shl 6) or (octets[3] and 0x3F)
            }
            val min = when (nBytes) { 2 -> 0x80; 3 -> 0x800; else -> 0x10000 }
            if (cp !in min..0x10FFFF || cp in 0xD800..0xDFFF) throw JSException.uriError("URI malformed")
            sb.appendCodePoint(cp)
        }
        return sb.toString()
    }

    fun escape(s: String): String {
        val ok = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789@*_+-./"
        val sb = StringBuilder()
        for (c in s) {
            if (ok.indexOf(c) >= 0) sb.append(c)
            else if (c.code < 256) sb.append('%').append(HEX[c.code shr 4]).append(HEX[c.code and 15])
            else sb.append("%u").append(HEX[c.code shr 12]).append(HEX[(c.code shr 8) and 15]).append(HEX[(c.code shr 4) and 15]).append(HEX[c.code and 15])
        }
        return sb.toString()
    }

    fun unescape(s: String): String {
        val sb = StringBuilder()
        var k = 0
        val n = s.length
        while (k < n) {
            val c = s[k]
            if (c == '%') {
                if (k + 6 <= n && s[k + 1] == 'u') {
                    val v = s.substring(k + 2, k + 6)
                    if (v.all { hexVal(it) >= 0 }) {
                        sb.append(v.toInt(16).toChar())
                        k += 6
                        continue
                    }
                }
                if (k + 3 <= n) {
                    val v = s.substring(k + 1, k + 3)
                    if (v.all { hexVal(it) >= 0 }) {
                        sb.append(v.toInt(16).toChar())
                        k += 3
                        continue
                    }
                }
            }
            sb.append(c)
            k++
        }
        return sb.toString()
    }
}
