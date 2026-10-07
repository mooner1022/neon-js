package dev.mooner.neonjs.builtins

import dev.mooner.neonjs.runtime.*
import dev.mooner.neonjs.vm.*

class StringIteratorObject(proto: JSObject?, @JvmField var s: String?) : JSObject(proto) {
    @JvmField var pos = 0
}

internal object StringBuiltins {
    /**
     * Full default lowercase mapping. The JDK applies Final_Sigma with its own (older) Unicode data, so strings with
     * U+03A3 are mapped per code point with Final_Sigma decided from the engine's Unicode tables.
     */
    /**
     * Simple case mappings added in Unicode 17 that the JDK's (older) Unicode data lacks: code point, uppercase,
     * lowercase. Generated from UnicodeData.txt 17.0.0 by diffing against the JDK's mappings.
     */
    private val CASE_17 = intArrayOf(0xA7CE, 0xA7CE, 0xA7CF, 0xA7CF, 0xA7CE, 0xA7CF, 0xA7D2, 0xA7D2, 0xA7D3, 0xA7D3, 0xA7D2, 0xA7D3, 0xA7D4, 0xA7D4, 0xA7D5, 0xA7D5, 0xA7D4, 0xA7D5, 0x16EA0, 0x16EA0, 0x16EBB, 0x16EA1, 0x16EA1, 0x16EBC, 0x16EA2, 0x16EA2, 0x16EBD, 0x16EA3, 0x16EA3, 0x16EBE, 0x16EA4, 0x16EA4, 0x16EBF, 0x16EA5, 0x16EA5, 0x16EC0, 0x16EA6, 0x16EA6, 0x16EC1, 0x16EA7, 0x16EA7, 0x16EC2, 0x16EA8, 0x16EA8, 0x16EC3, 0x16EA9, 0x16EA9, 0x16EC4, 0x16EAA, 0x16EAA, 0x16EC5, 0x16EAB, 0x16EAB, 0x16EC6, 0x16EAC, 0x16EAC, 0x16EC7, 0x16EAD, 0x16EAD, 0x16EC8, 0x16EAE, 0x16EAE, 0x16EC9, 0x16EAF, 0x16EAF, 0x16ECA, 0x16EB0, 0x16EB0, 0x16ECB, 0x16EB1, 0x16EB1, 0x16ECC, 0x16EB2, 0x16EB2, 0x16ECD, 0x16EB3, 0x16EB3, 0x16ECE, 0x16EB4, 0x16EB4, 0x16ECF, 0x16EB5, 0x16EB5, 0x16ED0, 0x16EB6, 0x16EB6, 0x16ED1, 0x16EB7, 0x16EB7, 0x16ED2, 0x16EB8, 0x16EB8, 0x16ED3, 0x16EBB, 0x16EA0, 0x16EBB, 0x16EBC, 0x16EA1, 0x16EBC, 0x16EBD, 0x16EA2, 0x16EBD, 0x16EBE, 0x16EA3, 0x16EBE, 0x16EBF, 0x16EA4, 0x16EBF, 0x16EC0, 0x16EA5, 0x16EC0, 0x16EC1, 0x16EA6, 0x16EC1, 0x16EC2, 0x16EA7, 0x16EC2, 0x16EC3, 0x16EA8, 0x16EC3, 0x16EC4, 0x16EA9, 0x16EC4, 0x16EC5, 0x16EAA, 0x16EC5, 0x16EC6, 0x16EAB, 0x16EC6, 0x16EC7, 0x16EAC, 0x16EC7, 0x16EC8, 0x16EAD, 0x16EC8, 0x16EC9, 0x16EAE, 0x16EC9, 0x16ECA, 0x16EAF, 0x16ECA, 0x16ECB, 0x16EB0, 0x16ECB, 0x16ECC, 0x16EB1, 0x16ECC, 0x16ECD, 0x16EB2, 0x16ECD, 0x16ECE, 0x16EB3, 0x16ECE, 0x16ECF, 0x16EB4, 0x16ECF, 0x16ED0, 0x16EB5, 0x16ED0, 0x16ED1, 0x16EB6, 0x16ED1, 0x16ED2, 0x16EB7, 0x16ED2, 0x16ED3, 0x16EB8, 0x16ED3)
    private val case17: Map<Int, IntArray> by lazy {
        val m = HashMap<Int, IntArray>()
        var i = 0
        while (i < CASE_17.size) {
            m[CASE_17[i]] = intArrayOf(CASE_17[i + 1], CASE_17[i + 2])
            i += 3
        }
        m
    }

    /** True if [s] may contain a character covered by [CASE_17] (U+A7CE..U+A7D5, or U+16EA0..U+16ED3). */
    private fun mayNeedCase17(s: String): Boolean {
        for (c in s) if (c in '\uA7CE'..'\uA7D5' || c == '\uD81A') return true
        return false
    }

    fun toUpperCase(s: String): String {
        if (!mayNeedCase17(s)) return s.uppercase(java.util.Locale.ROOT)
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            val m = case17[cp]
            if (m != null) sb.appendCodePoint(m[0]) else sb.append(String(Character.toChars(cp)).uppercase(java.util.Locale.ROOT))
        }
        return sb.toString()
    }

    fun toLowerCase(s: String): String {
        if (s.indexOf('Σ') < 0 && !mayNeedCase17(s)) return s.lowercase(java.util.Locale.ROOT)
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = Character.charCount(cp)
            val m17 = case17[cp]
            if (cp == 0x3A3) sb.append(if (isFinalSigma(s, i)) 'ς' else 'σ')
            else if (m17 != null) sb.appendCodePoint(m17[1])
            else sb.append(String(Character.toChars(cp)).lowercase(java.util.Locale.ROOT))
            i += n
        }
        return sb.toString()
    }

    private val cased by lazy { dev.mooner.neonjs.unicode.UnicodeTables.binaryProperty("Cased")!! }
    private val caseIgnorable by lazy { dev.mooner.neonjs.unicode.UnicodeTables.binaryProperty("Case_Ignorable")!! }

    /** Final_Sigma: preceded by cased (+ case-ignorables) and not followed by (case-ignorables +) cased. */
    private fun isFinalSigma(s: String, at: Int): Boolean {
        var i = at
        var before = false
        while (i > 0) {
            val cp = s.codePointBefore(i)
            i -= Character.charCount(cp)
            if (dev.mooner.neonjs.unicode.CharRanges.contains(caseIgnorable, cp)) continue
            before = dev.mooner.neonjs.unicode.CharRanges.contains(cased, cp)
            break
        }
        if (!before) return false
        i = at + 1
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (dev.mooner.neonjs.unicode.CharRanges.contains(caseIgnorable, cp)) continue
            return !dev.mooner.neonjs.unicode.CharRanges.contains(cased, cp)
        }
        return true
    }

    fun thisStr(t: Any?, m: String): String {
        if (t === Undefined || t === Null) typeErr("String.prototype.$m called on null or undefined")
        return Ops.toString(t)
    }

    fun thisStringValue(t: Any?, m: String): String = when (t) {
        is CharSequence -> t.toString()
        is JSStringObject -> t.value
        else -> typeErr("String.prototype.$m requires that 'this' be a String")
    }

    private fun isRegExp(v: Any?): Boolean {
        if (v !is JSObject) return false
        val m = v.get(JSSymbol.match, v)
        if (m !== Undefined) return Ops.toBoolean(m)
        return v.className == "RegExp"
    }

    fun install(realm: Realm) {
        val proto = JSStringObject(realm.objectPrototype, "")
        realm.stringPrototype = proto
        val ctor = makeCtor(realm, "String", 1, proto) { _, _, args, nt ->
            val s: String = if (args.isEmpty()) "" else {
                val v = args[0]
                if (nt == null && v is JSSymbol) return@makeCtor v.toString()
                Ops.toString(v)
            }
            if (nt == null) s else JSStringObject(Ops.getPrototypeFromConstructor(nt) { it.stringPrototype }, s)
        }
        realm.global("String", ctor)
        ctor.method(realm, "fromCharCode", 1) { _, _, args, _ ->
            val sb = StringBuilder(args.size)
            for (a in args) sb.append(Ops.toUint16(a).toChar())
            sb.toString()
        }
        ctor.method(realm, "fromCodePoint", 1) { _, _, args, _ ->
            val sb = StringBuilder(args.size)
            for (a in args) {
                val n = Ops.toNumber(a)
                if (!Ops.isIntegral(n) || n < 0 || n > 0x10FFFF) rangeErr("Invalid code point ${Ops.toDisplayString(a)}")
                sb.appendCodePoint(n.toInt())
            }
            sb.toString()
        }
        ctor.method(realm, "raw", 1) { f, _, args, _ ->
            val cooked = Ops.toObject(args.arg(0))
            val raw = Ops.toObject(cooked.get("raw", cooked))
            val n = Ops.lengthOfArrayLike(raw)
            val sb = StringBuilder()
            val agent = f.realm.agent
            var i = 0L
            while (i < n) {
                if (i and 1023L == 1023L) agent.checkInterrupt()
                if (sb.length > 65536) agent.checkStringLength(sb.length.toLong())
                sb.append(Ops.toString(raw.get(PK.fromIndex(i), raw)))
                if (i + 1 < n && i + 1 < args.size) sb.append(Ops.toString(args[(i + 1).toInt()]))
                i++
            }
            sb.toString()
        }

        proto.method(realm, "at", 1) { _, t, args, _ ->
            val s = thisStr(t, "at")
            val rel = Ops.toIntegerOrInfinity(args.arg(0))
            val k = if (rel >= 0) rel else s.length + rel
            if (k < 0 || k >= s.length) Undefined else s[k.toInt()].toString()
        }
        proto.method(realm, "charAt", 1) { _, t, args, _ ->
            val s = thisStr(t, "charAt")
            val p = Ops.toIntegerOrInfinity(args.arg(0))
            if (p < 0 || p >= s.length) "" else s[p.toInt()].toString()
        }
        proto.method(realm, "charCodeAt", 1) { _, t, args, _ ->
            val s = thisStr(t, "charCodeAt")
            val p = Ops.toIntegerOrInfinity(args.arg(0))
            if (p < 0 || p >= s.length) Double.NaN else s[p.toInt()].code.toDouble()
        }
        proto.method(realm, "codePointAt", 1) { _, t, args, _ ->
            val s = thisStr(t, "codePointAt")
            val p = Ops.toIntegerOrInfinity(args.arg(0))
            if (p < 0 || p >= s.length) Undefined else s.codePointAt(p.toInt()).toDouble()
        }
        proto.method(realm, "concat", 1) { _, t, args, _ ->
            var r: CharSequence = thisStr(t, "concat")
            for (a in args) r = Rope.concat(r, Ops.toString(a))
            r
        }
        proto.method(realm, "endsWith", 1) { _, t, args, _ ->
            val s = thisStr(t, "endsWith")
            if (isRegExp(args.arg(0))) typeErr("First argument to String.prototype.endsWith must not be a regular expression")
            val search = Ops.toString(args.arg(0))
            val end = if (args.arg(1) === Undefined) s.length else clamp(Ops.toIntegerOrInfinity(args.arg(1)), s.length)
            val start = end - search.length
            start >= 0 && s.regionMatches(start, search, 0, search.length)
        }
        proto.method(realm, "includes", 1) { _, t, args, _ ->
            val s = thisStr(t, "includes")
            if (isRegExp(args.arg(0))) typeErr("First argument to String.prototype.includes must not be a regular expression")
            val search = Ops.toString(args.arg(0))
            val start = clamp(Ops.toIntegerOrInfinity(args.arg(1)), s.length)
            s.indexOf(search, start) >= 0
        }
        proto.method(realm, "indexOf", 1) { _, t, args, _ ->
            val s = thisStr(t, "indexOf")
            val search = Ops.toString(args.arg(0))
            val start = clamp(Ops.toIntegerOrInfinity(args.arg(1)), s.length)
            s.indexOf(search, start).toDouble()
        }
        proto.method(realm, "isWellFormed", 0) { _, t, _, _ -> isWellFormed(thisStr(t, "isWellFormed")) }
        proto.method(realm, "lastIndexOf", 1) { _, t, args, _ ->
            val s = thisStr(t, "lastIndexOf")
            val search = Ops.toString(args.arg(0))
            val numPos = Ops.toNumber(args.arg(1))
            val pos = if (numPos.isNaN()) Double.POSITIVE_INFINITY else Ops.integerPart(numPos)
            val start = clamp(pos, s.length)
            s.lastIndexOf(search, start).toDouble()
        }
        proto.method(realm, "localeCompare", 1) { _, t, args, _ ->
            val s = thisStr(t, "localeCompare")
            val that = Ops.toString(args.arg(0))
            val c = collator.compare(java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFC), java.text.Normalizer.normalize(that, java.text.Normalizer.Form.NFC))
            (if (c < 0) -1 else if (c > 0) 1 else 0).toDouble()
        }
        for ((name, sym) in listOf("match" to JSSymbol.match, "search" to JSSymbol.search)) {
            proto.method(realm, name, 1) { f, t, args, _ ->
                Ops.requireObjectCoercible(t)
                val regexp = args.arg(0)
                if (regexp is JSObject) {
                    val m = Ops.getMethod(regexp, sym)
                    if (m !== Undefined) return@method Ops.call(m, regexp, arrayOf(t))
                }
                val s = Ops.toString(t)
                val rx = regexpCreate(f.realm, regexp, Undefined)
                Ops.invoke(rx, sym, arrayOf(s))
            }
        }
        proto.method(realm, "matchAll", 1) { f, t, args, _ ->
            Ops.requireObjectCoercible(t)
            val regexp = args.arg(0)
            if (regexp is JSObject) {
                if (isRegExp(regexp)) {
                    val flags = regexp.get("flags", regexp)
                    Ops.requireObjectCoercible(flags)
                    if (Ops.toString(flags).indexOf('g') < 0) typeErr("String.prototype.matchAll called with a non-global RegExp argument")
                }
                val m = Ops.getMethod(regexp, JSSymbol.matchAll)
                if (m !== Undefined) return@method Ops.call(m, regexp, arrayOf(t))
            }
            val s = Ops.toString(t)
            val rx = regexpCreate(f.realm, regexp, "g")
            Ops.invoke(rx, JSSymbol.matchAll, arrayOf(s))
        }
        proto.method(realm, "normalize", 0) { _, t, args, _ ->
            val s = thisStr(t, "normalize")
            val fs = if (args.arg(0) === Undefined) "NFC" else Ops.toString(args.arg(0))
            val form = when (fs) {
                "NFC" -> java.text.Normalizer.Form.NFC
                "NFD" -> java.text.Normalizer.Form.NFD
                "NFKC" -> java.text.Normalizer.Form.NFKC
                "NFKD" -> java.text.Normalizer.Form.NFKD
                else -> rangeErr("The normalization form should be one of NFC, NFD, NFKC, NFKD.")
            }
            java.text.Normalizer.normalize(s, form)
        }
        for ((name, atStart) in listOf("padEnd" to false, "padStart" to true)) {
            proto.method(realm, name, 1) { f, t, args, _ ->
                val s = thisStr(t, name)
                val maxLen = Ops.toLength(args.arg(0))
                if (maxLen <= s.length) s
                else {
                    val fill = if (args.arg(1) === Undefined) " " else Ops.toString(args.arg(1))
                    if (fill.isEmpty()) s
                    else {
                        if (maxLen > Rope.MAX_LENGTH) rangeErr("Invalid string length")
                        f.realm.agent.checkStringLength(maxLen)
                        val fillLen = (maxLen - s.length).toInt()
                        val sb = StringBuilder(maxLen.toInt())
                        if (!atStart) sb.append(s)
                        while (sb.length - (if (atStart) 0 else s.length) < fillLen) sb.append(fill)
                        sb.setLength((if (atStart) 0 else s.length) + fillLen)
                        if (atStart) sb.append(s)
                        sb.toString()
                    }
                }
            }
        }
        proto.method(realm, "repeat", 1) { f, t, args, _ ->
            val s = thisStr(t, "repeat")
            val n = Ops.toIntegerOrInfinity(args.arg(0))
            if (n < 0 || n == Double.POSITIVE_INFINITY) rangeErr("Invalid count value: ${Ops.toDisplayString(args.arg(0))}")
            if (s.isEmpty() || n == 0.0) ""
            else {
                if (s.length * n > Rope.MAX_LENGTH) rangeErr("Invalid string length")
                f.realm.agent.checkStringLength((s.length * n).toLong())
                s.repeat(n.toInt())
            }
        }
        proto.method(realm, "replace", 2) { f, t, args, _ ->
            Ops.requireObjectCoercible(t)
            val search = args.arg(0)
            val replace = args.arg(1)
            if (search is JSObject) {
                val m = Ops.getMethod(search, JSSymbol.replace)
                if (m !== Undefined) return@method Ops.call(m, search, arrayOf(t, replace))
            }
            val s = Ops.toString(t)
            val ss = Ops.toString(search)
            val fnReplace = Ops.isCallable(replace)
            val rv = if (fnReplace) "" else Ops.toString(replace)
            val pos = s.indexOf(ss)
            if (pos < 0) s
            else {
                val repl = if (fnReplace) Ops.toString((replace as JSObject).call(Undefined, arrayOf(ss, pos.toDouble(), s)))
                else getSubstitution(f.realm, ss, s, pos, emptyList(), Undefined, rv)
                s.substring(0, pos) + repl + s.substring(pos + ss.length)
            }
        }
        proto.method(realm, "replaceAll", 2) { f, t, args, _ ->
            Ops.requireObjectCoercible(t)
            val search = args.arg(0)
            val replace = args.arg(1)
            if (search is JSObject) {
                if (isRegExp(search)) {
                    val flags = search.get("flags", search)
                    Ops.requireObjectCoercible(flags)
                    if (Ops.toString(flags).indexOf('g') < 0) typeErr("replaceAll must be called with a global RegExp")
                }
                val m = Ops.getMethod(search, JSSymbol.replace)
                if (m !== Undefined) return@method Ops.call(m, search, arrayOf(t, replace))
            }
            val s = Ops.toString(t)
            val ss = Ops.toString(search)
            val fnReplace = Ops.isCallable(replace)
            val rv = if (fnReplace) "" else Ops.toString(replace)
            val adv = maxOf(1, ss.length)
            val positions = ArrayList<Int>()
            var p = s.indexOf(ss, 0)
            while (p >= 0) {
                positions.add(p)
                p = if (p + adv > s.length) -1 else s.indexOf(ss, p + adv)
            }
            var end = 0
            val sb = StringBuilder()
            for (pp in positions) {
                sb.append(s, end, pp)
                val repl = if (fnReplace) Ops.toString((replace as JSObject).call(Undefined, arrayOf(ss, pp.toDouble(), s)))
                else getSubstitution(f.realm, ss, s, pp, emptyList(), Undefined, rv)
                sb.append(repl)
                end = pp + ss.length
            }
            if (end < s.length) sb.append(s, end, s.length)
            sb.toString()
        }
        proto.method(realm, "slice", 2) { _, t, args, _ ->
            val s = thisStr(t, "slice")
            val n = s.length.toLong()
            val from = relIndex(args.arg(0), n, 0)
            val to = relIndex(args.arg(1), n, n)
            if (from >= to) "" else s.substring(from.toInt(), to.toInt())
        }
        proto.method(realm, "split", 2) { f, t, args, _ ->
            Ops.requireObjectCoercible(t)
            val sep = args.arg(0)
            val limit = args.arg(1)
            if (sep is JSObject) {
                val m = Ops.getMethod(sep, JSSymbol.split)
                if (m !== Undefined) return@method Ops.call(m, sep, arrayOf(t, limit))
            }
            val s = Ops.toString(t)
            val lim = if (limit === Undefined) 4294967295L else Ops.toUint32(limit)
            val r = Ops.toString(sep)
            val out = ArrayList<Any?>()
            if (lim == 0L) return@method Builtins.arrayOf(f.realm, out)
            if (sep === Undefined) return@method Builtins.arrayOf(f.realm, listOf(s))
            if (s.isEmpty()) {
                if (r.isNotEmpty()) out.add(s)
                return@method Builtins.arrayOf(f.realm, out)
            }
            if (r.isEmpty()) {
                for (c in s) {
                    out.add(c.toString())
                    if (out.size.toLong() >= lim) break
                }
                return@method Builtins.arrayOf(f.realm, out)
            }
            var p = 0
            var q = s.indexOf(r, 0)
            while (q >= 0) {
                out.add(s.substring(p, q))
                if (out.size.toLong() >= lim) return@method Builtins.arrayOf(f.realm, out)
                p = q + r.length
                q = s.indexOf(r, p)
            }
            out.add(s.substring(p))
            Builtins.arrayOf(f.realm, out)
        }
        proto.method(realm, "startsWith", 1) { _, t, args, _ ->
            val s = thisStr(t, "startsWith")
            if (isRegExp(args.arg(0))) typeErr("First argument to String.prototype.startsWith must not be a regular expression")
            val search = Ops.toString(args.arg(0))
            val start = clamp(Ops.toIntegerOrInfinity(args.arg(1)), s.length)
            start + search.length <= s.length && s.regionMatches(start, search, 0, search.length)
        }
        proto.method(realm, "substring", 2) { _, t, args, _ ->
            val s = thisStr(t, "substring")
            val a = clamp(Ops.toIntegerOrInfinity(args.arg(0)), s.length)
            val b = if (args.arg(1) === Undefined) s.length else clamp(Ops.toIntegerOrInfinity(args.arg(1)), s.length)
            s.substring(minOf(a, b), maxOf(a, b))
        }
        proto.method(realm, "substr", 2) { _, t, args, _ ->
            val s = thisStr(t, "substr")
            val size = s.length
            var start = Ops.toIntegerOrInfinity(args.arg(0))
            start = if (start == Double.NEGATIVE_INFINITY) 0.0 else if (start < 0) maxOf(size + start, 0.0) else minOf(start, size.toDouble())
            val len = if (args.arg(1) === Undefined) size.toDouble() else Ops.toIntegerOrInfinity(args.arg(1))
            val end = minOf(start + len, size.toDouble())
            if (start >= end) "" else s.substring(start.toInt(), end.toInt())
        }
        proto.method(realm, "toLocaleLowerCase", 0) { _, t, _, _ -> toLowerCase(thisStr(t, "toLocaleLowerCase")) }
        proto.method(realm, "toLocaleUpperCase", 0) { _, t, _, _ -> toUpperCase(thisStr(t, "toLocaleUpperCase")) }
        proto.method(realm, "toLowerCase", 0) { _, t, _, _ -> toLowerCase(thisStr(t, "toLowerCase")) }
        proto.method(realm, "toString", 0) { _, t, _, _ -> thisStringValue(t, "toString") }
        proto.method(realm, "toUpperCase", 0) { _, t, _, _ -> toUpperCase(thisStr(t, "toUpperCase")) }
        proto.method(realm, "toWellFormed", 0) { _, t, _, _ ->
            val s = thisStr(t, "toWellFormed")
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) {
                    sb.append(c).append(s[i + 1])
                    i += 2
                    continue
                }
                if (c.isSurrogate()) sb.append('�') else sb.append(c)
                i++
            }
            sb.toString()
        }
        proto.method(realm, "trim", 0) { _, t, _, _ -> NumberConv.trim(thisStr(t, "trim")) }
        val trimStart = proto.method(realm, "trimStart", 0) { _, t, _, _ ->
            val s = thisStr(t, "trimStart")
            var i = 0
            while (i < s.length && NumberConv.isJSWhitespace(s[i])) i++
            s.substring(i)
        }
        val trimEnd = proto.method(realm, "trimEnd", 0) { _, t, _, _ ->
            val s = thisStr(t, "trimEnd")
            var e = s.length
            while (e > 0 && NumberConv.isJSWhitespace(s[e - 1])) e--
            s.substring(0, e)
        }
        proto.defineOwn("trimLeft", trimStart, Attr.WC)
        proto.defineOwn("trimRight", trimEnd, Attr.WC)
        proto.method(realm, "valueOf", 0) { _, t, _, _ -> thisStringValue(t, "valueOf") }
        // Annex B HTML methods
        fun html(name: String, tag: String, attr: String?) {
            proto.method(realm, name, if (attr != null) 1 else 0) { _, t, args, _ ->
                val s = thisStr(t, name)
                val sb = StringBuilder("<").append(tag)
                if (attr != null) {
                    val v = Ops.toString(args.arg(0)).replace("\"", "&quot;")
                    sb.append(' ').append(attr).append("=\"").append(v).append('"')
                }
                sb.append('>').append(s).append("</").append(tag).append('>').toString()
            }
        }
        html("anchor", "a", "name"); html("big", "big", null); html("blink", "blink", null); html("bold", "b", null)
        html("fixed", "tt", null); html("fontcolor", "font", "color"); html("fontsize", "font", "size")
        html("italics", "i", null); html("link", "a", "href"); html("small", "small", null)
        html("strike", "strike", null); html("sub", "sub", null); html("sup", "sup", null)

        // iterator
        val sip = JSObject(realm.iteratorPrototype)
        realm.intrinsics["%StringIteratorPrototype%"] = sip
        sip.method(realm, "next", 0) { f, t, _, _ ->
            val it = t as? StringIteratorObject ?: typeErr("next method called on incompatible receiver")
            val s = it.s
            if (s == null || it.pos >= s.length) {
                it.s = null
                Iteration.createIterResult(f.realm, Undefined, true)
            } else {
                val cp = s.codePointAt(it.pos)
                val n = Character.charCount(cp)
                val r = s.substring(it.pos, it.pos + n)
                it.pos += n
                Iteration.createIterResult(f.realm, r, false)
            }
        }
        sip.value(JSSymbol.toStringTag, "String Iterator", Attr.CONFIGURABLE)
        proto.method(realm, JSSymbol.iterator, 0) { _, t, _, _ -> StringIteratorObject(sip, thisStr(t, "[Symbol.iterator]")) }
    }

    private val collator: java.text.Collator = java.text.Collator.getInstance(java.util.Locale.ROOT).also {
        it.decomposition = java.text.Collator.CANONICAL_DECOMPOSITION
    }

    fun clamp(d: Double, len: Int): Int = if (d < 0) 0 else if (d > len) len else d.toInt()

    fun isWellFormed(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate()) {
                if (i + 1 >= s.length || !s[i + 1].isLowSurrogate()) return false
                i += 2
                continue
            }
            if (c.isLowSurrogate()) return false
            i++
        }
        return true
    }

    fun regexpCreate(realm: Realm, pattern: Any?, flags: Any?): JSObject {
        val ctor = realm.intrinsics["%RegExp%"] ?: typeErr("RegExp is not supported")
        return ctor.construct(arrayOf(pattern, flags), ctor) as JSObject
    }

    /** GetSubstitution(matched, str, position, captures, namedCaptures, replacementTemplate) */
    fun getSubstitution(realm: Realm, matched: String, str: String, position: Int, captures: List<Any?>, namedCaptures: Any?, template: String): String {
        val sb = StringBuilder()
        val agent = realm.agent
        // checked before appending: the expansion of a short template can be huge ($& / $1 repeated)
        fun room(extra: Int) {
            val total = sb.length.toLong() + extra
            if (total > 65536) agent.checkStringLength(total)
        }
        val m = captures.size
        val tailPos = minOf(position + matched.length, str.length)
        var i = 0
        val n = template.length
        while (i < n) {
            val c = template[i]
            if (c != '$' || i + 1 >= n) {
                sb.append(c)
                i++
                continue
            }
            val d = template[i + 1]
            when (d) {
                '$' -> { sb.append('$'); i += 2 }
                '&' -> { room(matched.length); sb.append(matched); i += 2 }
                '`' -> { room(minOf(position, str.length)); sb.append(str, 0, minOf(position, str.length)); i += 2 }
                '\'' -> { if (tailPos < str.length) { room(str.length - tailPos); sb.append(str, tailPos, str.length) }; i += 2 }
                in '0'..'9' -> {
                    var digits = 1
                    var idx = d - '0'
                    if (i + 2 < n && template[i + 2] in '0'..'9') {
                        val two = idx * 10 + (template[i + 2] - '0')
                        if (two in 1..m) {
                            idx = two
                            digits = 2
                        }
                    }
                    if (idx in 1..m) {
                        val cap = captures[idx - 1]
                        if (cap !== Undefined) Ops.toString(cap).let { cs -> room(cs.length); sb.append(cs) }
                        i += 1 + digits
                    } else {
                        sb.append('$')
                        i++
                    }
                }
                '<' -> {
                    if (namedCaptures === Undefined) {
                        sb.append("$<")
                        i += 2
                    } else {
                        val close = template.indexOf('>', i + 2)
                        if (close < 0) {
                            sb.append("$<")
                            i += 2
                        } else {
                            val groupName = template.substring(i + 2, close)
                            val cap = Ops.getV(realm, namedCaptures, PK.fromString(groupName))
                            if (cap !== Undefined) sb.append(Ops.toString(cap))
                            i = close + 1
                        }
                    }
                }
                else -> { sb.append('$'); i++ }
            }
        }
        return sb.toString()
    }
}
