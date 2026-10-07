package io.neonjs.builtins

import io.neonjs.compiler.RegExpSite
import io.neonjs.regexp.RegExpEngine
import io.neonjs.regexp.RegExpMatcher
import io.neonjs.regexp.RegExpProgram
import io.neonjs.regexp.RegExpProgram.Companion.FLAG_DOTALL
import io.neonjs.regexp.RegExpProgram.Companion.FLAG_GLOBAL
import io.neonjs.regexp.RegExpProgram.Companion.FLAG_IGNORECASE
import io.neonjs.regexp.RegExpProgram.Companion.FLAG_INDICES
import io.neonjs.regexp.RegExpProgram.Companion.FLAG_MULTILINE
import io.neonjs.regexp.RegExpProgram.Companion.FLAG_STICKY
import io.neonjs.regexp.RegExpProgram.Companion.FLAG_UNICODE
import io.neonjs.regexp.RegExpProgram.Companion.FLAG_UNICODE_SETS
import io.neonjs.regexp.RegExpStackOverflowError
import io.neonjs.regexp.RegExpSyntaxError
import io.neonjs.runtime.*
import io.neonjs.vm.*

/** RegExp instance: an ordinary object with the `[[OriginalSource]]`, `[[OriginalFlags]]` and `[[RegExpMatcher]]` slots. */
class JSRegExp(proto: JSObject?) : JSObject(proto) {
    @JvmField var originalSource: String = ""
    @JvmField var originalFlags: String = ""
    @JvmField var program: RegExpProgram? = null
    /** `[[Realm]]` (legacy RegExp features): the current realm when the object was allocated. */
    @JvmField var realm: Realm? = null
    /** `[[LegacyFeaturesEnabled]]`: allocated by the realm's own %RegExp% (not a subclass or a cross-realm newTarget). */
    @JvmField var legacyFeatures = false

    override val className: String get() = "RegExp"
}

/**
 * The legacy static slots of a realm's %RegExp% (proposal-regexp-legacy-features): `[[RegExpInput]]`,
 * `[[RegExpLastMatch]]`, `[[RegExpParen1]]`-`[[RegExpParen9]]`, `[[RegExpLastParen]]`, `[[RegExpLeftContext]]` and
 * `[[RegExpRightContext]]`. The match slots are kept as positions into [subject] and only materialized by the accessors,
 * so a successful exec copies no substrings.
 */
internal class LegacyRegExpStatics {
    /** `[[RegExpInput]]`; null when empty (invalidated). */
    @JvmField var input: String? = ""
    /** Subject of the recorded match; null when the match slots are empty (invalidated). */
    @JvmField var subject: String? = ""
    /** Start / end of the match (0, 1), of captures 1-9 (2..19, -1 when unmatched) and of the last capture (20, 21). */
    @JvmField val pos = IntArray(22).also { it.fill(-1); it[0] = 0; it[1] = 0 }

    /** UpdateLegacyRegExpStaticProperties from the current match of [m] ([captures] = number of capture groups). */
    fun update(s: String, start: Int, end: Int, m: RegExpMatcher, captures: Int) {
        input = s
        subject = s
        pos[0] = start
        pos[1] = end
        for (i in 1..9) {
            pos[2 * i] = if (i <= captures) m.start(i) else -1
            pos[2 * i + 1] = if (i <= captures) m.end(i) else -1
        }
        pos[20] = if (captures > 0) m.start(captures) else -1
        pos[21] = if (captures > 0) m.end(captures) else -1
    }

    /** UpdateLegacyRegExpStaticProperties from capture positions in the layout of RegExpBuiltins.capturePositions. */
    fun update(s: String, caps: IntArray) {
        input = s
        subject = s
        val captures = caps.size / 2 - 1
        pos[0] = caps[0]
        pos[1] = caps[1]
        for (i in 1..9) {
            pos[2 * i] = if (i <= captures) caps[2 * i] else -1
            pos[2 * i + 1] = if (i <= captures) caps[2 * i + 1] else -1
        }
        pos[20] = if (captures > 0) caps[2 * captures] else -1
        pos[21] = if (captures > 0) caps[2 * captures + 1] else -1
    }

    /** InvalidateLegacyRegExpStaticProperties */
    fun invalidate() {
        input = null
        subject = null
    }

    /** Match slot [k] (0 = lastMatch, 1-9 = $1-$9, 10 = lastParen); "" for unmatched captures, null when empty. */
    fun slot(k: Int): String? {
        val s = subject ?: return null
        val i = if (k == 10) 20 else 2 * k
        val a = pos[i]
        val b = pos[i + 1]
        return if (a < 0 || b < 0) "" else s.substring(a, b)
    }

    fun leftContext(): String? = subject?.substring(0, pos[0])
    fun rightContext(): String? = subject?.substring(pos[1])
}

/** %RegExpStringIteratorPrototype% instance (result of `RegExp.prototype[Symbol.matchAll]`). */
class RegExpStringIterator(
    proto: JSObject?,
    @JvmField val matcher: JSObject,
    @JvmField val string: String,
    @JvmField val global: Boolean,
    @JvmField val fullUnicode: Boolean,
) : JSObject(proto) {
    @JvmField var done = false
}

internal object RegExpBuiltins {
    private const val PROTO = "%RegExp.prototype%"
    private const val EXEC = "%RegExp.prototype.exec%"

    fun install(realm: Realm) {
        val proto = JSObject(realm.objectPrototype)
        realm.intrinsics[PROTO] = proto
        val ctor = makeCtor(realm, "RegExp", 2, proto) { f, _, args, nt -> construct(f, args, nt) }
        realm.intrinsics["%RegExp%"] = ctor
        realm.global("RegExp", ctor)
        ctor.getter(realm, JSSymbol.species) { _, t, _, _ -> t }
        ctor.method(realm, "escape", 1) { _, _, args, _ ->
            val s = args.arg(0) as? CharSequence ?: typeErr("RegExp.escape requires a string argument")
            escape(s.toString())
        }

        val exec = proto.method(realm, "exec", 1) { f, t, args, _ ->
            val r = t as? JSRegExp ?: typeErr("RegExp.prototype.exec called on incompatible receiver ${Ops.describe(t)}")
            builtinExec(f.realm, r, Ops.toString(args.arg(0)))
        }
        realm.intrinsics[EXEC] = exec
        proto.getter(realm, "dotAll") { f, t, _, _ -> flagGetter(f, t, FLAG_DOTALL, "dotAll") }
        proto.getter(realm, "flags") { _, t, _, _ ->
            val r = t as? JSObject ?: typeErr("RegExp.prototype.flags getter called on non-object ${Ops.describe(t)}")
            val sb = StringBuilder()
            if (Ops.toBoolean(r.get("hasIndices", r))) sb.append('d')
            if (Ops.toBoolean(r.get("global", r))) sb.append('g')
            if (Ops.toBoolean(r.get("ignoreCase", r))) sb.append('i')
            if (Ops.toBoolean(r.get("multiline", r))) sb.append('m')
            if (Ops.toBoolean(r.get("dotAll", r))) sb.append('s')
            if (Ops.toBoolean(r.get("unicode", r))) sb.append('u')
            if (Ops.toBoolean(r.get("unicodeSets", r))) sb.append('v')
            if (Ops.toBoolean(r.get("sticky", r))) sb.append('y')
            sb.toString()
        }
        proto.getter(realm, "global") { f, t, _, _ -> flagGetter(f, t, FLAG_GLOBAL, "global") }
        proto.getter(realm, "hasIndices") { f, t, _, _ -> flagGetter(f, t, FLAG_INDICES, "hasIndices") }
        proto.getter(realm, "ignoreCase") { f, t, _, _ -> flagGetter(f, t, FLAG_IGNORECASE, "ignoreCase") }
        proto.method(realm, JSSymbol.match, 1) { f, t, args, _ -> symbolMatch(f.realm, t, args.arg(0)) }
        proto.method(realm, JSSymbol.matchAll, 1) { f, t, args, _ -> symbolMatchAll(f.realm, t, args.arg(0)) }
        proto.getter(realm, "multiline") { f, t, _, _ -> flagGetter(f, t, FLAG_MULTILINE, "multiline") }
        proto.method(realm, JSSymbol.replace, 2) { f, t, args, _ -> symbolReplace(f.realm, t, args.arg(0), args.arg(1)) }
        proto.method(realm, JSSymbol.search, 1) { f, t, args, _ -> symbolSearch(f.realm, t, args.arg(0)) }
        proto.getter(realm, "source") { f, t, _, _ ->
            when {
                t is JSRegExp -> escapePattern(t.originalSource)
                t !is JSObject -> typeErr("RegExp.prototype.source getter called on non-object ${Ops.describe(t)}")
                t === f.realm.intrinsic(PROTO) -> "(?:)"
                else -> typeErr("RegExp.prototype.source getter called on non-RegExp object")
            }
        }
        proto.method(realm, JSSymbol.split, 2) { f, t, args, _ -> symbolSplit(f.realm, t, args.arg(0), args.arg(1)) }
        proto.getter(realm, "sticky") { f, t, _, _ -> flagGetter(f, t, FLAG_STICKY, "sticky") }
        proto.method(realm, "test", 1) { f, t, args, _ ->
            val r = t as? JSObject ?: typeErr("RegExp.prototype.test called on non-object ${Ops.describe(t)}")
            val str = Ops.toString(args.arg(0))
            if (r is JSRegExp && r.get("exec", r) === f.realm.intrinsics[EXEC]) builtinExec(f.realm, r, str, false) !== Null
            else regExpExec(f.realm, r, str) !== Null
        }
        proto.method(realm, "toString", 0) { _, t, _, _ ->
            val r = t as? JSObject ?: typeErr("RegExp.prototype.toString called on non-object ${Ops.describe(t)}")
            val p = Ops.toString(r.get("source", r))
            val fl = Ops.toString(r.get("flags", r))
            "/$p/$fl"
        }
        proto.getter(realm, "unicode") { f, t, _, _ -> flagGetter(f, t, FLAG_UNICODE, "unicode") }
        proto.getter(realm, "unicodeSets") { f, t, _, _ -> flagGetter(f, t, FLAG_UNICODE_SETS, "unicodeSets") }
        // Annex B
        proto.method(realm, "compile", 2) { f, t, args, _ ->
            val o = t as? JSRegExp ?: typeErr("RegExp.prototype.compile called on incompatible receiver ${Ops.describe(t)}")
            if (o.realm !== f.realm) typeErr("RegExp.prototype.compile called on a RegExp of another realm")
            if (!o.legacyFeatures) typeErr("RegExp.prototype.compile called on a RegExp subclass instance")
            val pattern = args.arg(0)
            val flags = args.arg(1)
            if (pattern is JSRegExp) {
                if (flags !== Undefined) typeErr("Cannot supply flags when constructing one RegExp from another")
                initialize(o, pattern.originalSource, pattern.originalFlags)
            } else {
                initialize(o, pattern, flags)
            }
        }

        // %RegExpStringIteratorPrototype%
        val rsip = JSObject(realm.iteratorPrototype)
        realm.intrinsics["%RegExpStringIteratorPrototype%"] = rsip
        rsip.method(realm, "next", 0) { f, t, _, _ -> iteratorNext(f.realm, t) }
        rsip.value(JSSymbol.toStringTag, "RegExp String Iterator", Attr.CONFIGURABLE)

        installLegacyStatics(realm, ctor)
        realm.agent.regexpFactory = ::literal
    }

    // ------------------------------------------------------------------ legacy static properties

    private const val STATICS = "%RegExpLegacyStatics%"

    private fun statics(realm: Realm): LegacyRegExpStatics =
        realm.intrinsicsAny.getOrPut(STATICS) { LegacyRegExpStatics() } as LegacyRegExpStatics

    /**
     * The statics to update after a successful match of [r] while [realm] is the current realm, or null: after
     * InvalidateLegacyRegExpStaticProperties for a RegExp of this realm without legacy features; untouched for a
     * RegExp of another realm.
     */
    private fun legacyTarget(realm: Realm, r: JSRegExp): LegacyRegExpStatics? {
        if (r.realm !== realm) return null
        val st = statics(realm)
        if (!r.legacyFeatures) {
            st.invalidate()
            return null
        }
        return st
    }

    /** The receiver check of Get/SetLegacyRegExpStaticProperty: this must be the accessor realm's %RegExp%. */
    private fun staticsFor(f: NativeFunction, t: Any?, name: String): LegacyRegExpStatics {
        if (t !== f.realm.intrinsics["%RegExp%"]) typeErr("RegExp.$name accessor called on incompatible receiver ${Ops.describe(t)}")
        return statics(f.realm)
    }

    private fun emptySlot(name: String): Nothing =
        typeErr("RegExp.$name is unavailable after a match of a RegExp subclass instance")

    /**
     * Getter of a legacy static named by a punctuator ("$&", "$+", "$`", "$'"): such a name is no IdentifierName, so
     * the source text uses a computed property name to remain valid NativeFunction syntax.
     */
    private class PunctuatorGetter(realm: Realm, private val key: String, impl: NativeImpl) :
        NativeFunction(realm, key, 0, impl, namePrefix = "get") {
        override fun sourceText(): String = "function get [\"$key\"]() { [native code] }"
    }

    private fun legacyGetter(realm: Realm, ctor: JSObject, name: String, impl: NativeImpl) {
        val g = if (name.length == 2 && name[0] == '$' && !name[1].isLetterOrDigit()) PunctuatorGetter(realm, name, impl)
        else NativeFunction(realm, name, 0, impl, namePrefix = "get")
        ctor.defineAccessor(name, g, Undefined, Attr.CONFIGURABLE)
    }

    private fun installLegacyStatics(realm: Realm, ctor: JSObject) {
        for (name in listOf("input", $$"$_")) {
            ctor.accessor(realm, name, { f, t, _, _ ->
                staticsFor(f, t, name).input ?: emptySlot(name)
            }, { f, t, args, _ ->
                val st = staticsFor(f, t, name)
                st.input = Ops.toString(args.arg(0))
                Undefined
            })
        }
        for ((name, k) in listOf("lastMatch" to 0, "$&" to 0, "lastParen" to 10, "$+" to 10)) {
            legacyGetter(realm, ctor, name) { f, t, _, _ -> staticsFor(f, t, name).slot(k) ?: emptySlot(name) }
        }
        for (name in listOf("leftContext", "$`")) {
            legacyGetter(realm, ctor, name) { f, t, _, _ -> staticsFor(f, t, name).leftContext() ?: emptySlot(name) }
        }
        for (name in listOf("rightContext", "$'")) {
            legacyGetter(realm, ctor, name) { f, t, _, _ -> staticsFor(f, t, name).rightContext() ?: emptySlot(name) }
        }
        for (k in 1..9) {
            val name = "$" + k
            ctor.getter(realm, name) { f, t, _, _ -> staticsFor(f, t, name).slot(k) ?: emptySlot(name) }
        }
    }

    // ------------------------------------------------------------------ construction

    private fun isRegExp(v: Any?): Boolean {
        if (v !is JSObject) return false
        val m = v.get(JSSymbol.match, v)
        if (m !== Undefined) return Ops.toBoolean(m)
        return v is JSRegExp
    }

    private fun construct(f: NativeFunction, args: Array<Any?>, nt: JSObject?): Any? {
        val pattern = args.arg(0)
        val flags = args.arg(1)
        val patternIsRegExp = isRegExp(pattern)
        val newTarget: JSObject
        if (nt == null) {
            newTarget = f
            if (patternIsRegExp && flags === Undefined) {
                val pc = (pattern as JSObject).get("constructor", pattern)
                if (pc === newTarget) return pattern
            }
        } else {
            newTarget = nt
        }
        val p: Any?
        val fl: Any?
        if (pattern is JSRegExp) {
            p = pattern.originalSource
            fl = if (flags === Undefined) pattern.originalFlags else flags
        } else if (patternIsRegExp) {
            pattern as JSObject
            p = pattern.get("source", pattern)
            fl = if (flags === Undefined) pattern.get("flags", pattern) else flags
        } else {
            p = pattern
            fl = flags
        }
        val o = JSRegExp(Ops.getPrototypeFromConstructor(newTarget) { it.intrinsic(PROTO) })
        o.realm = f.realm
        o.legacyFeatures = newTarget === f
        o.defineOwn("lastIndex", Undefined, Attr.WRITABLE)
        return initialize(o, p, fl)
    }

    /** RegExpInitialize */
    private fun initialize(o: JSRegExp, pattern: Any?, flags: Any?): JSRegExp {
        val p = if (pattern === Undefined) "" else Ops.toString(pattern)
        val f = if (flags === Undefined) "" else Ops.toString(flags)
        val mask = RegExpProgram.parseFlags(f)
        if (mask < 0) throw JSException.syntaxError("Invalid flags supplied to RegExp constructor '$f'")
        val prog = try {
            RegExpEngine.compile(p, mask)
        } catch (e: RegExpSyntaxError) {
            throw JSException.syntaxError("Invalid regular expression: /$p/$f: ${e.message}")
        }
        o.originalSource = p
        o.originalFlags = f
        o.program = prog
        o.setOrThrow("lastIndex", 0.0)
        return o
    }

    /** Creates the object for a regular expression literal (the program is compiled once per site). */
    private fun literal(realm: Realm, site: RegExpSite): JSObject {
        var prog = site.compiled as? RegExpProgram
        if (prog == null) {
            prog = try {
                RegExpEngine.compile(site.pattern, site.flags)
            } catch (e: RegExpSyntaxError) {
                throw JSException.syntaxError("Invalid regular expression: /${site.pattern}/${site.flags}: ${e.message}")
            }
            site.compiled = prog
        }
        val o = JSRegExp(realm.intrinsic(PROTO))
        o.realm = realm
        o.legacyFeatures = true
        o.defineOwn("lastIndex", 0.0, Attr.WRITABLE)
        o.originalSource = site.pattern
        o.originalFlags = site.flags
        o.program = prog
        return o
    }

    private fun flagGetter(f: NativeFunction, t: Any?, flag: Int, name: String): Any? {
        if (t is JSRegExp) return (t.program?.flags ?: 0) and flag != 0
        if (t !is JSObject) typeErr("RegExp.prototype.$name getter called on non-object ${Ops.describe(t)}")
        if (t === f.realm.intrinsic(PROTO)) return Undefined
        typeErr("RegExp.prototype.$name getter called on non-RegExp object")
    }

    // ------------------------------------------------------------------ exec

    private fun programOf(r: JSRegExp): RegExpProgram = r.program ?: typeErr("RegExp object is not initialized")

    private fun newMatcher(realm: Realm, prog: RegExpProgram, s: String): RegExpMatcher {
        val agent = realm.agent
        return prog.matcher(s) { agent.checkInterrupt() }
    }

    private inline fun <T> guardStack(block: () -> T): T = try {
        block()
    } catch (_: RegExpStackOverflowError) {
        throw JSException.rangeError("Maximum call stack size exceeded (regular expression too complex)")
    }

    /** RegExpBuiltinExec(R, S); when ![wantResult] returns `true` instead of the match array on success. */
    fun builtinExec(realm: Realm, r: JSRegExp, s: String, wantResult: Boolean = true): Any? {
        val length = s.length
        var lastIndex = Ops.toLength(r.get("lastIndex", r))
        val prog = programOf(r)
        val flags = prog.flags
        val global = flags and FLAG_GLOBAL != 0
        val sticky = flags and FLAG_STICKY != 0
        if (!global && !sticky) lastIndex = 0
        if (lastIndex > length) {
            r.setOrThrow("lastIndex", 0.0) // only global or sticky regexps get here: lastIndex is 0 otherwise
            return Null
        }
        val m = newMatcher(realm, prog, s)
        if (!guardStack { m.match(lastIndex.toInt()) }) {
            if (global || sticky) r.setOrThrow("lastIndex", 0.0)
            return Null
        }
        val matchStart = maxOf(m.start(0), lastIndex.toInt())
        val e = m.end(0)
        if (global || sticky) r.setOrThrow("lastIndex", e.toDouble())
        legacyTarget(realm, r)?.update(s, matchStart, e, m, prog.captureCount - 1)
        return if (wantResult) buildResult(realm, prog, s, m, matchStart, e) else true
    }

    /** True if [o]'s "exec" is the intrinsic %RegExp.prototype.exec% data property (found without side effects). */
    private fun hasBuiltinExec(realm: Realm, o: JSObject): Boolean {
        val exec = realm.intrinsics[EXEC] ?: return false
        var p: JSObject? = o
        while (p != null) {
            if (p is ProxyObject) return false
            val d = p.getOwnProperty("exec")
            if (d != null) return d.isData && d.value === exec
            p = p.getPrototypeOf()
        }
        return false
    }

    /** Captures of the current match of [m] as code unit positions (start, end pairs; -1 = undefined). */
    private fun capturePositions(m: RegExpMatcher, n: Int, matchStart: Int): IntArray {
        val a = IntArray(2 * n)
        for (i in 0 until n) {
            a[2 * i] = m.start(i)
            a[2 * i + 1] = m.end(i)
        }
        a[0] = matchStart
        return a
    }

    private fun buildResult(realm: Realm, prog: RegExpProgram, s: String, m: RegExpMatcher, matchStart: Int, matchEnd: Int): JSArray {
        val n = prog.captureCount - 1
        val values = arrayOfNulls<Any?>(n + 1)
        values[0] = s.substring(matchStart, matchEnd)
        for (i in 1..n) {
            val cs = m.start(i)
            val ce = m.end(i)
            values[i] = if (cs < 0 || ce < 0) Undefined else s.substring(cs, ce)
        }
        val a = JSArray.of(realm.arrayPrototype, values)
        a.defineOwn("index", matchStart.toDouble(), Attr.ALL)
        a.defineOwn("input", s, Attr.ALL)
        val names = prog.groupNames
        var groups: Any? = Undefined
        // groupNames used for the indices array (null = no group property)
        var indexNames: Array<String?>? = null
        if (names != null) {
            val g = JSObject(null)
            groups = g
            indexNames = arrayOfNulls(n + 1)
            var matched: HashSet<String>? = null
            for (i in 1..n) {
                val name = names[i] ?: continue
                if (matched != null && name in matched) continue
                val v = values[i]
                if (v !== Undefined) {
                    if (matched == null) matched = HashSet()
                    matched.add(name)
                }
                g.createDataPropertyOrThrow(PK.fromString(name), v)
                indexNames[i] = name
            }
        }
        a.defineOwn("groups", groups, Attr.ALL)
        if (prog.flags and FLAG_INDICES != 0) {
            val pairs = arrayOfNulls<Any?>(n + 1)
            pairs[0] = pair(realm, matchStart, matchEnd)
            for (i in 1..n) {
                val cs = m.start(i)
                val ce = m.end(i)
                pairs[i] = if (cs < 0 || ce < 0) Undefined else pair(realm, cs, ce)
            }
            val ia = JSArray.of(realm.arrayPrototype, pairs)
            var igroups: Any? = Undefined
            if (indexNames != null) {
                val g = JSObject(null)
                igroups = g
                for (i in 1..n) {
                    val name = indexNames[i] ?: continue
                    g.createDataPropertyOrThrow(PK.fromString(name), pairs[i])
                }
            }
            ia.defineOwn("groups", igroups, Attr.ALL)
            a.defineOwn("indices", ia, Attr.ALL)
        }
        return a
    }

    private fun pair(realm: Realm, a: Int, b: Int): JSArray = JSArray.of(realm.arrayPrototype, arrayOf(a.toDouble(), b.toDouble()))

    /** RegExpExec(R, S) */
    fun regExpExec(realm: Realm, r: JSObject, s: String): Any? {
        val exec = r.get("exec", r)
        if (exec === realm.intrinsics[EXEC] && r is JSRegExp) return builtinExec(realm, r, s)
        if (Ops.isCallable(exec)) {
            val result = (exec as JSObject).call(r, arrayOf(s))
            if (result !is JSObject && result !== Null) typeErr("exec result must be an object or null")
            return result
        }
        if (r !is JSRegExp) typeErr("RegExp exec method called on incompatible receiver ${Ops.describe(r)}")
        return builtinExec(realm, r, s)
    }

    /** AdvanceStringIndex(S, index, unicode) */
    private fun advance(s: String, index: Long, unicode: Boolean): Long {
        if (!unicode || index + 1 >= s.length) return index + 1
        val i = index.toInt()
        return if (s[i].isHighSurrogate() && s[i + 1].isLowSurrogate()) index + 2 else index + 1
    }

    private fun thisObject(t: Any?, name: String): JSObject =
        t as? JSObject ?: typeErr("RegExp.prototype[$name] called on non-object ${Ops.describe(t)}")

    private fun hasUnicodeFlag(flags: String) = flags.indexOf('u') >= 0 || flags.indexOf('v') >= 0

    // ------------------------------------------------------------------ Symbol methods

    private fun symbolMatch(realm: Realm, t: Any?, string: Any?): Any? {
        val rx = thisObject(t, "Symbol.match")
        val s = Ops.toString(string)
        val flags = Ops.toString(rx.get("flags", rx))
        if (flags.indexOf('g') < 0) return regExpExec(realm, rx, s)
        val fullUnicode = hasUnicodeFlag(flags)
        rx.setOrThrow("lastIndex", 0.0)
        val out = ArrayList<Any?>()
        if (rx is JSRegExp && programOf(rx).flags and FLAG_GLOBAL != 0 && hasBuiltinExec(realm, rx)) {
            // no user code can run during the loop: the intermediate lastIndex values are unobservable
            val prog = programOf(rx)
            val m = newMatcher(realm, prog, s)
            var li = 0L
            var legacy: LegacyRegExpStatics? = null
            while (li <= s.length && guardStack { m.match(li.toInt()) }) {
                val start = maxOf(m.start(0), li.toInt())
                val e = m.end(0)
                out.add(s.substring(start, e))
                if (out.size == 1) legacy = legacyTarget(realm, rx)
                legacy?.update(s, start, e, m, prog.captureCount - 1)
                li = if (e == start) advance(s, e.toLong(), fullUnicode) else e.toLong()
            }
            rx.setOrThrow("lastIndex", 0.0)
            return if (out.isEmpty()) Null else Builtins.arrayOf(realm, out)
        }
        var iterations = 0
        while (true) {
            if (++iterations and 1023 == 0) realm.agent.checkInterrupt()
            val result = regExpExec(realm, rx, s)
            if (result === Null) return if (out.isEmpty()) Null else Builtins.arrayOf(realm, out)
            result as JSObject
            val matchStr = Ops.toString(result.get(0, result))
            out.add(matchStr)
            if (matchStr.isEmpty()) {
                val thisIndex = Ops.toLength(rx.get("lastIndex", rx))
                rx.setOrThrow("lastIndex", advance(s, thisIndex, fullUnicode).toDouble())
            }
        }
    }

    private fun symbolMatchAll(realm: Realm, t: Any?, string: Any?): Any? {
        val r = thisObject(t, "Symbol.matchAll")
        val s = Ops.toString(string)
        val c = Ops.speciesConstructor(r, realm.intrinsic("%RegExp%"))
        val flags = Ops.toString(r.get("flags", r))
        val matcher = Ops.construct(c, arrayOf(r, flags)) as JSObject
        val lastIndex = Ops.toLength(r.get("lastIndex", r))
        matcher.setOrThrow("lastIndex", lastIndex.toDouble())
        return RegExpStringIterator(realm.intrinsic("%RegExpStringIteratorPrototype%"), matcher, s,
            flags.indexOf('g') >= 0, hasUnicodeFlag(flags))
    }

    private fun iteratorNext(realm: Realm, t: Any?): Any? {
        val it = t as? RegExpStringIterator ?: typeErr("RegExp String Iterator next called on incompatible receiver ${Ops.describe(t)}")
        if (it.done) return Iteration.createIterResult(realm, Undefined, true)
        val match = regExpExec(realm, it.matcher, it.string)
        if (match === Null) {
            it.done = true
            return Iteration.createIterResult(realm, Undefined, true)
        }
        if (!it.global) {
            it.done = true
            return Iteration.createIterResult(realm, match, false)
        }
        match as JSObject
        val matchStr = Ops.toString(match.get(0, match))
        if (matchStr.isEmpty()) {
            val r = it.matcher
            val thisIndex = Ops.toLength(r.get("lastIndex", r))
            r.setOrThrow("lastIndex", advance(it.string, thisIndex, it.fullUnicode).toDouble())
        }
        return Iteration.createIterResult(realm, match, false)
    }

    private fun symbolReplace(realm: Realm, t: Any?, string: Any?, replaceValue: Any?): Any? {
        val rx = thisObject(t, "Symbol.replace")
        val s = Ops.toString(string)
        val lengthS = s.length
        val functionalReplace = Ops.isCallable(replaceValue)
        val replaceStr = if (functionalReplace) "" else Ops.toString(replaceValue)
        val flags = Ops.toString(rx.get("flags", rx))
        val global = flags.indexOf('g') >= 0
        var fullUnicode = false
        if (global) {
            fullUnicode = hasUnicodeFlag(flags)
            rx.setOrThrow("lastIndex", 0.0)
        }
        if (global && rx is JSRegExp && programOf(rx).flags and FLAG_GLOBAL != 0 && hasBuiltinExec(realm, rx)) {
            return fastReplaceAll(realm, rx, s, fullUnicode, if (functionalReplace) replaceValue as JSObject else null, replaceStr)
        }
        val results = ArrayList<JSObject>()
        var iterations = 0
        while (true) {
            if (++iterations and 1023 == 0) realm.agent.checkInterrupt()
            val result = regExpExec(realm, rx, s)
            if (result === Null) break
            result as JSObject
            results.add(result)
            if (!global) break
            val matchStr = Ops.toString(result.get(0, result))
            if (matchStr.isEmpty()) {
                val thisIndex = Ops.toLength(rx.get("lastIndex", rx))
                rx.setOrThrow("lastIndex", advance(s, thisIndex, fullUnicode).toDouble())
            }
        }
        val acc = StringBuilder()
        var nextSourcePosition = 0
        for (result in results) {
            val resultLength = Ops.lengthOfArrayLike(result)
            val nCaptures = maxOf(resultLength - 1, 0L)
            val matched = Ops.toString(result.get(0, result))
            val matchLength = matched.length
            val position = Ops.toIntegerOrInfinity(result.get("index", result)).coerceIn(0.0, lengthS.toDouble()).toInt()
            val captures = ArrayList<Any?>()
            var n = 1L
            while (n <= nCaptures) {
                // the result comes from a user-defined exec, so its length is arbitrary
                if (n and 1023L == 1023L) realm.agent.checkInterrupt()
                var capN = result.get(PK.fromIndex(n), result)
                if (capN !== Undefined) capN = Ops.toString(capN)
                captures.add(capN)
                n++
            }
            var namedCaptures = result.get("groups", result)
            val replacement: String
            if (functionalReplace) {
                val replacerArgs = ArrayList<Any?>(captures.size + 4)
                replacerArgs.add(matched)
                replacerArgs.addAll(captures)
                replacerArgs.add(position.toDouble())
                replacerArgs.add(s)
                if (namedCaptures !== Undefined) replacerArgs.add(namedCaptures)
                replacement = Ops.toString((replaceValue as JSObject).call(Undefined, replacerArgs.toTypedArray()))
            } else {
                if (namedCaptures !== Undefined) namedCaptures = Ops.toObject(namedCaptures)
                replacement = StringBuiltins.getSubstitution(realm, matched, s, position, captures, namedCaptures, replaceStr)
            }
            if (position >= nextSourcePosition) {
                val total = acc.length.toLong() + (position - nextSourcePosition) + replacement.length
                if (total > 65536) realm.agent.checkStringLength(total)
                acc.append(s, nextSourcePosition, position).append(replacement)
                nextSourcePosition = position + matchLength
            }
        }
        if (nextSourcePosition >= lengthS) return acc.toString()
        return acc.append(s, nextSourcePosition, lengthS).toString()
    }

    /**
     * Global `RegExp.prototype[Symbol.replace]` for a regexp using the builtin exec: same observable behaviour as the
     * generic algorithm, without materializing the intermediate match objects.
     */
    private fun fastReplaceAll(realm: Realm, rx: JSRegExp, s: String, fullUnicode: Boolean, fn: JSObject?, template: String): String {
        val prog = programOf(rx)
        val n = prog.captureCount
        val m = newMatcher(realm, prog, s)
        val matches = ArrayList<IntArray>()
        var li = 0L
        while (li <= s.length && guardStack { m.match(li.toInt()) }) {
            val start = maxOf(m.start(0), li.toInt())
            val e = m.end(0)
            matches.add(capturePositions(m, n, start))
            li = if (e == start) advance(s, e.toLong(), fullUnicode) else e.toLong()
        }
        if (matches.isNotEmpty()) legacyTarget(realm, rx)?.update(s, matches[matches.size - 1])
        rx.setOrThrow("lastIndex", 0.0)
        val names = prog.groupNames
        val simpleTemplate = fn == null && template.indexOf('$') < 0
        val acc = StringBuilder()
        var nextSourcePosition = 0
        for (caps in matches) {
            val position = caps[0]
            val matched = s.substring(position, caps[1])
            val replacement: String
            if (simpleTemplate) {
                replacement = template
            } else {
                val captures = ArrayList<Any?>(n)
                for (i in 1 until n) {
                    val cs = caps[2 * i]
                    val ce = caps[2 * i + 1]
                    captures.add(if (cs < 0 || ce < 0) Undefined else s.substring(cs, ce))
                }
                var groups: Any? = Undefined
                if (names != null) {
                    val g = JSObject(null)
                    var matchedNames: HashSet<String>? = null
                    for (i in 1 until n) {
                        val name = names[i] ?: continue
                        if (matchedNames != null && name in matchedNames) continue
                        val v = captures[i - 1]
                        if (v !== Undefined) {
                            if (matchedNames == null) matchedNames = HashSet()
                            matchedNames.add(name)
                        }
                        g.createDataPropertyOrThrow(PK.fromString(name), v)
                    }
                    groups = g
                }
                replacement = if (fn != null) {
                    val args = ArrayList<Any?>(n + 3)
                    args.add(matched)
                    args.addAll(captures)
                    args.add(position.toDouble())
                    args.add(s)
                    if (groups !== Undefined) args.add(groups)
                    Ops.toString(fn.call(Undefined, args.toTypedArray()))
                } else {
                    StringBuiltins.getSubstitution(realm, matched, s, position, captures, groups, template)
                }
            }
            if (position >= nextSourcePosition) {
                val total = acc.length.toLong() + (position - nextSourcePosition) + replacement.length
                if (total > 65536) realm.agent.checkStringLength(total)
                acc.append(s, nextSourcePosition, position).append(replacement)
                nextSourcePosition = position + matched.length
            }
        }
        if (nextSourcePosition < s.length) acc.append(s, nextSourcePosition, s.length)
        return acc.toString()
    }

    private fun symbolSearch(realm: Realm, t: Any?, string: Any?): Any? {
        val rx = thisObject(t, "Symbol.search")
        val s = Ops.toString(string)
        val previousLastIndex = rx.get("lastIndex", rx)
        if (!Ops.sameValue(previousLastIndex, 0.0)) rx.setOrThrow("lastIndex", 0.0)
        val result = regExpExec(realm, rx, s)
        val currentLastIndex = rx.get("lastIndex", rx)
        if (!Ops.sameValue(currentLastIndex, previousLastIndex)) rx.setOrThrow("lastIndex", previousLastIndex)
        if (result === Null) return -1.0
        result as JSObject
        return result.get("index", result)
    }

    private fun symbolSplit(realm: Realm, t: Any?, string: Any?, limit: Any?): Any? {
        val rx = thisObject(t, "Symbol.split")
        val s = Ops.toString(string)
        val c = Ops.speciesConstructor(rx, realm.intrinsic("%RegExp%"))
        val flags = Ops.toString(rx.get("flags", rx))
        val unicodeMatching = hasUnicodeFlag(flags)
        val newFlags = if (flags.indexOf('y') >= 0) flags else flags + "y"
        val splitter = Ops.construct(c, arrayOf(rx, newFlags)) as JSObject
        val out = ArrayList<Any?>()
        val lim = if (limit === Undefined) 4294967295L else Ops.toUint32(limit)
        if (lim == 0L) return Builtins.arrayOf(realm, out)
        val size = s.length
        if (size == 0) {
            val z = regExpExec(realm, splitter, s)
            if (z !== Null) return Builtins.arrayOf(realm, out)
            out.add(s)
            return Builtins.arrayOf(realm, out)
        }
        if (c === realm.intrinsic("%RegExp%") && splitter is JSRegExp && hasBuiltinExec(realm, splitter)) {
            // the splitter is not reachable from user code: search directly instead of trying each position
            return fastSplit(realm, splitter, s, lim, unicodeMatching)
        }
        var p = 0
        var q = 0L
        var iterations = 0
        while (q < size) {
            if (++iterations and 1023 == 0) realm.agent.checkInterrupt()
            splitter.setOrThrow("lastIndex", q.toDouble())
            val z = regExpExec(realm, splitter, s)
            if (z === Null) {
                q = advance(s, q, unicodeMatching)
                continue
            }
            z as JSObject
            val e = minOf(Ops.toLength(splitter.get("lastIndex", splitter)), size.toLong())
            if (e == p.toLong()) {
                q = advance(s, q, unicodeMatching)
                continue
            }
            out.add(s.substring(p, q.toInt()))
            if (out.size.toLong() == lim) return Builtins.arrayOf(realm, out)
            p = e.toInt()
            val numberOfCaptures = maxOf(Ops.lengthOfArrayLike(z) - 1, 0L)
            var i = 1L
            while (i <= numberOfCaptures) {
                out.add(z.get(PK.fromIndex(i), z))
                if (out.size.toLong() == lim) return Builtins.arrayOf(realm, out)
                i++
            }
            q = p.toLong()
        }
        out.add(s.substring(p, size))
        return Builtins.arrayOf(realm, out)
    }

    private fun fastSplit(realm: Realm, splitter: JSRegExp, s: String, lim: Long, unicodeMatching: Boolean): JSArray {
        val prog = programOf(splitter)
        val m = newMatcher(realm, prog, s)
        val size = s.length
        val out = ArrayList<Any?>()
        var p = 0
        var q = 0
        var legacy: LegacyRegExpStatics? = null
        var matched = false
        while (q < size) {
            if (!guardStack { m.search(q) }) break
            val qm = maxOf(m.start(0), q)
            if (qm >= size) break
            if (!matched) {
                matched = true
                legacy = legacyTarget(realm, splitter)
            }
            legacy?.update(s, qm, m.end(0), m, prog.captureCount - 1)
            val e = minOf(m.end(0), size)
            if (e == p) {
                q = advance(s, qm.toLong(), unicodeMatching).toInt()
                continue
            }
            out.add(s.substring(p, qm))
            if (out.size.toLong() == lim) return Builtins.arrayOf(realm, out)
            p = e
            for (i in 1 until prog.captureCount) {
                val cs = m.start(i)
                val ce = m.end(i)
                out.add(if (cs < 0 || ce < 0) Undefined else s.substring(cs, ce))
                if (out.size.toLong() == lim) return Builtins.arrayOf(realm, out)
            }
            q = p
        }
        out.add(s.substring(p, size))
        return Builtins.arrayOf(realm, out)
    }

    // ------------------------------------------------------------------ source / escape

    /** EscapeRegExpPattern: escapes '/' and line terminators so that `/source/flags` parses as an equivalent literal. */
    fun escapePattern(src: String): String {
        if (src.isEmpty()) return "(?:)"
        var needs = false
        for (c in src) if (c == '/' || c == '\n' || c == '\r' || c == ' ' || c == ' ') { needs = true; break }
        if (!needs) return src
        val sb = StringBuilder(src.length + 8)
        var inClass = false
        var i = 0
        while (i < src.length) {
            val c = src[i]
            if (c == '\\' && i + 1 < src.length) {
                val d = src[i + 1]
                sb.append('\\')
                when (d) {
                    '\n' -> sb.append('n')
                    '\r' -> sb.append('r')
                    ' ' -> sb.append("u2028")
                    ' ' -> sb.append("u2029")
                    else -> sb.append(d)
                }
                i += 2
                continue
            }
            when (c) {
                '/' -> if (inClass) sb.append(c) else sb.append("\\/")
                '[' -> { inClass = true; sb.append(c) }
                ']' -> { inClass = false; sb.append(c) }
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                ' ' -> sb.append("\\u2028")
                ' ' -> sb.append("\\u2029")
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /** RegExp.escape(S) */
    private fun escape(s: String): String {
        val sb = StringBuilder(s.length * 2)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = Character.charCount(cp)
            if (sb.isEmpty() && (cp in '0'.code..'9'.code || cp in 'a'.code..'z'.code || cp in 'A'.code..'Z'.code)) {
                sb.append("\\x").append(Integer.toHexString(cp))
            } else {
                encodeForEscape(cp, sb)
            }
            i += n
        }
        return sb.toString()
    }

    private const val SYNTAX_CHARS = "^$\\.*+?()[]{}|/"
    private const val OTHER_PUNCTUATORS = ",-=<>#&!%:;@~'`\""

    private fun encodeForEscape(cp: Int, sb: StringBuilder) {
        if (cp < 128 && SYNTAX_CHARS.indexOf(cp.toChar()) >= 0) {
            sb.append('\\').append(cp.toChar())
            return
        }
        when (cp) {
            9 -> { sb.append("\\t"); return }
            10 -> { sb.append("\\n"); return }
            11 -> { sb.append("\\v"); return }
            12 -> { sb.append("\\f"); return }
            13 -> { sb.append("\\r"); return }
        }
        val isWhite = cp == 0xFEFF || cp == 0x2028 || cp == 0x2029 || cp == 0xA0 || cp == 0x20 ||
            (cp > 127 && io.neonjs.unicode.UnicodeTables.isSpaceSeparator(cp))
        if ((cp < 128 && OTHER_PUNCTUATORS.indexOf(cp.toChar()) >= 0) || isWhite || cp in 0xD800..0xDFFF) {
            if (cp <= 0xFF) {
                sb.append("\\x")
                if (cp < 16) sb.append('0')
                sb.append(Integer.toHexString(cp))
                return
            }
            for (cu in Character.toChars(cp)) {
                sb.append("\\u")
                val h = Integer.toHexString(cu.code)
                repeat(4 - h.length) { sb.append('0') }
                sb.append(h)
            }
            return
        }
        sb.appendCodePoint(cp)
    }
}
