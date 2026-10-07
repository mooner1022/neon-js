package dev.mooner.neonjs.regexp

import dev.mooner.neonjs.unicode.CharRanges
import dev.mooner.neonjs.unicode.UnicodeTables

/** Thrown when a match needs more backtracking memory than allowed. */
class RegExpStackOverflowError : RuntimeException("regular expression backtracking stack overflow", null, false, false)

/**
 * Backtracking interpreter for [RegExpProgram] bytecode (design after QuickJS lre_exec_backtrack). It never recurses:
 * choice points and capture undo records live on an explicit IntArray stack whose size is bounded by [MAX_STACK].
 *
 * Positions are UTF-16 code unit indices into [input]; in unicode mode surrogate pairs are read as one character.
 * Not thread-safe: create one matcher per match operation.
 */
class RegExpMatcher internal constructor(
    private val prog: RegExpProgram,
    private val input: String,
    private val interrupt: Runnable?,
) {
    private val code = prog.code
    private val unicode = prog.isUnicode
    private val captureSlots = prog.captureCount * 2

    /** Capture positions (start, end per group; -1 = undefined) followed by the loop registers. */
    @JvmField internal val cap = IntArray(captureSlots + prog.registerCount)
    private var stack = IntArray(48)
    private var interruptCounter = INTERRUPT_INTERVAL
    private var sb: StringBuilder? = null
    private var caseMap: UnicodeTables.CaseMap? = null

    /** Start index of capture group [i] after a successful match, or -1. */
    fun start(i: Int): Int = cap[2 * i]

    /** End index of capture group [i] after a successful match, or -1. */
    fun end(i: Int): Int = cap[2 * i + 1]

    /**
     * Runs the pattern starting at code unit [index] (0 <= index <= input.length). Non-sticky programs search
     * forward from [index]. Returns true on success; the captures are then available through [start]/[end].
     */
    fun match(index: Int): Boolean {
        if (prog.flags and RegExpProgram.FLAG_STICKY == 0) return search(index)
        var p = index
        val s = input
        if (unicode && p > 0 && p < s.length && s[p].isLowSurrogate() && s[p - 1].isHighSurrogate()) p--
        return attempt(p)
    }

    /** Like [match], but always searches forward from [index] (ignores the sticky flag). */
    fun search(index: Int): Boolean {
        val s = input
        val end = s.length
        var p = index
        if (unicode && p > 0 && p < end && s[p].isLowSurrogate() && s[p - 1].isHighSurrogate()) p--
        val kind = prog.startKind
        if (kind == RegExpProgram.START_ANCHOR) return p == 0 && attempt(0)
        while (true) {
            if (kind != RegExpProgram.START_ANY) {
                p = nextCandidate(p, kind)
                if (p < 0) return false
            }
            if (attempt(p)) return true
            if (p >= end) return false
            p += if (unicode && s[p].isHighSurrogate() && p + 1 < end && s[p + 1].isLowSurrogate()) 2 else 1
            if (--interruptCounter <= 0) poll()
        }
    }

    private fun attempt(p: Int): Boolean {
        java.util.Arrays.fill(cap, 0, captureSlots, -1)
        return run(p)
    }

    /** Finds the next position >= [from] where the first character of the pattern can match, or -1. */
    private fun nextCandidate(from: Int, kind: Int): Int {
        val s = input
        val end = s.length
        val arg = prog.startArg
        if (kind == RegExpProgram.START_CHAR) {
            if (arg < 0x10000) return s.indexOf(arg.toChar(), from)
            return s.indexOf(String(Character.toChars(arg)), from)
        }
        var p = from
        while (p < end) {
            var c = s[p].code
            var n = 1
            if (unicode && c in 0xD800..0xDBFF && p + 1 < end && s[p + 1].isLowSurrogate()) {
                c = Character.toCodePoint(c.toChar(), s[p + 1])
                n = 2
            }
            val ok = when (kind) {
                RegExpProgram.START_CHAR_I -> canon(c) == arg
                RegExpProgram.START_RANGE -> inRangeSet(code, arg, c)
                else -> inRangeSet(code, arg, canon(c))
            }
            if (ok) return p
            p += n
            if (--interruptCounter <= 0) poll()
        }
        return -1
    }

    private fun poll() {
        interruptCounter = INTERRUPT_INTERVAL
        interrupt?.run()
    }

    private fun grow(st: IntArray, needed: Int): IntArray {
        if (needed > MAX_STACK) throw RegExpStackOverflowError()
        val n = minOf(maxOf(needed, st.size * 2), MAX_STACK)
        val r = st.copyOf(n)
        stack = r
        return r
    }

    /** Canonicalize(rer, c) for case-insensitive matching. */
    private fun canon(c: Int): Int {
        if (c < 128) {
            return if (unicode) (if (c in 'A'.code..'Z'.code) c + 32 else c)
            else (if (c in 'a'.code..'z'.code) c - 32 else c)
        }
        if (!unicode && c > 0xFFFF) return c
        val m = caseMap ?: (if (unicode) UnicodeTables.simpleFolding else UnicodeTables.nonUnicodeCanonical).also { caseMap = it }
        return m.map(c)
    }

    private fun isWordChar(c: Int, ignoreCaseUnicode: Boolean): Boolean =
        if (c < 128) c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || c == '_'.code
        else ignoreCaseUnicode && (c == 0x017F || c == 0x212A)

    private fun isSpace(c: Int): Boolean =
        if (c < 128) c == 32 || c in 9..13 else CharRanges.contains(RegExpCompiler.spaceSet, c)

    /** Membership test for the RANGE instruction at [insn]: ASCII bitmap, then binary search of the ranges. */
    private fun inRangeSet(code: IntArray, insn: Int, c: Int): Boolean {
        if (c < 128) return code[insn + 2 + (c ushr 5)] and (1 shl (c and 31)) != 0
        val n = code[insn + 1]
        val base = insn + 6
        if (c < code[base] || c > code[base + 2 * n - 1]) return false
        var lo = 0
        var hi = n - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val k = base + 2 * mid
            if (c < code[k]) hi = mid - 1
            else if (c > code[k + 1]) lo = mid + 1
            else return true
        }
        return false
    }

    /** Matches a string group at [pos]; returns the new position or -1. */
    private fun matchStringGroup(g: StringGroup, pos: Int): Int {
        val s = input
        val sb = sb ?: StringBuilder().also { sb = it }
        sb.setLength(0)
        var p = pos
        if (!g.backward) {
            repeat(g.length) {
                if (p >= s.length) return -1
                var c = s[p++].code
                if (c in 0xD800..0xDBFF && p < s.length && s[p].isLowSurrogate()) c = Character.toCodePoint(c.toChar(), s[p++])
                if (g.ignoreCase) c = canon(c)
                sb.appendCodePoint(c)
            }
        } else {
            repeat(g.length) {
                if (p <= 0) return -1
                var c = s[--p].code
                if (c in 0xDC00..0xDFFF && p > 0 && s[p - 1].isHighSurrogate()) c = Character.toCodePoint(s[--p], c.toChar())
                if (g.ignoreCase) c = canon(c)
                sb.insert(0, Character.toChars(c))
            }
        }
        return if (sb.toString() in g.strings) p else -1
    }

    private fun run(startPos: Int): Boolean {
        val code = code
        val s = input
        val end = s.length
        val unicode = unicode
        val cap = cap
        val regBase = captureSlots
        var st = stack
        var sp = 0
        var bp = 0
        var pc = 0
        var cpos = startPos

        while (true) {
            exec@ while (true) {
                when (val op = code[pc++]) {
                    Op.MATCH -> return true
                    Op.CHAR, Op.CHAR_I -> {
                        val v = code[pc++]
                        if (cpos >= end) break@exec
                        var c = s[cpos++].code
                        if (unicode && c in 0xD800..0xDBFF && cpos < end) {
                            val d = s[cpos].code
                            if (d in 0xDC00..0xDFFF) {
                                c = Character.toCodePoint(c.toChar(), d.toChar())
                                cpos++
                            }
                        }
                        if (op == Op.CHAR_I) c = canon(c)
                        if (c != v) break@exec
                    }
                    Op.DOT, Op.ANY, Op.SPACE, Op.NOT_SPACE -> {
                        if (cpos >= end) break@exec
                        var c = s[cpos++].code
                        if (unicode && c in 0xD800..0xDBFF && cpos < end) {
                            val d = s[cpos].code
                            if (d in 0xDC00..0xDFFF) {
                                c = Character.toCodePoint(c.toChar(), d.toChar())
                                cpos++
                            }
                        }
                        when (op) {
                            Op.DOT -> if (c == 10 || c == 13 || c == 0x2028 || c == 0x2029) break@exec
                            Op.SPACE -> if (!isSpace(c)) break@exec
                            Op.NOT_SPACE -> if (isSpace(c)) break@exec
                        }
                    }
                    Op.RANGE, Op.RANGE_I -> {
                        val insn = pc - 1
                        pc = insn + 6 + 2 * code[pc]
                        if (cpos >= end) break@exec
                        var c = s[cpos++].code
                        if (unicode && c in 0xD800..0xDBFF && cpos < end) {
                            val d = s[cpos].code
                            if (d in 0xDC00..0xDFFF) {
                                c = Character.toCodePoint(c.toChar(), d.toChar())
                                cpos++
                            }
                        }
                        if (op == Op.RANGE_I) c = canon(c)
                        if (!inRangeSet(code, insn, c)) break@exec
                    }
                    Op.SPLIT_GOTO_FIRST, Op.SPLIT_NEXT_FIRST -> {
                        val off = code[pc++]
                        val alt: Int
                        if (op == Op.SPLIT_NEXT_FIRST) {
                            alt = pc + off
                        } else {
                            alt = pc
                            pc += off
                        }
                        if (sp + 3 > st.size) st = grow(st, sp + 3)
                        st[sp] = alt
                        st[sp + 1] = cpos
                        st[sp + 2] = (bp shl 2) or STATE_SPLIT
                        sp += 3
                        bp = sp
                    }
                    Op.LOOKAHEAD, Op.NEGATIVE_LOOKAHEAD -> {
                        val off = code[pc++]
                        if (sp + 3 > st.size) st = grow(st, sp + 3)
                        st[sp] = pc + off
                        st[sp + 1] = cpos
                        st[sp + 2] = (bp shl 2) or (if (op == Op.LOOKAHEAD) STATE_LOOKAHEAD else STATE_NEGATIVE_LOOKAHEAD)
                        sp += 3
                        bp = sp
                    }
                    Op.GOTO -> {
                        val off = code[pc++]
                        pc += off
                        if (--interruptCounter <= 0) poll()
                    }
                    Op.LINE_START, Op.LINE_START_M -> {
                        if (cpos == 0) continue@exec
                        if (op == Op.LINE_START) break@exec
                        val c = s[cpos - 1].code
                        if (!(c == 10 || c == 13 || c == 0x2028 || c == 0x2029)) break@exec
                    }
                    Op.LINE_END, Op.LINE_END_M -> {
                        if (cpos == end) continue@exec
                        if (op == Op.LINE_END) break@exec
                        val c = s[cpos].code
                        if (!(c == 10 || c == 13 || c == 0x2028 || c == 0x2029)) break@exec
                    }
                    Op.SAVE_START, Op.SAVE_END -> {
                        val idx = 2 * code[pc++] + (op - Op.SAVE_START)
                        if (sp + 2 > st.size) st = grow(st, sp + 2)
                        st[sp] = idx
                        st[sp + 1] = cap[idx]
                        sp += 2
                        cap[idx] = cpos
                    }
                    Op.SAVE_RESET -> {
                        var g = code[pc]
                        val last = code[pc + 1]
                        pc += 2
                        val need = sp + 4 * (last - g + 1)
                        if (need > st.size) st = grow(st, need)
                        while (g <= last) {
                            val idx = 2 * g
                            st[sp] = idx
                            st[sp + 1] = cap[idx]
                            st[sp + 2] = idx + 1
                            st[sp + 3] = cap[idx + 1]
                            sp += 4
                            cap[idx] = -1
                            cap[idx + 1] = -1
                            g++
                        }
                    }
                    Op.SET_I32, Op.SET_CHAR_POS -> {
                        val idx = regBase + code[pc]
                        val value = if (op == Op.SET_I32) code[pc + 1] else cpos
                        pc += if (op == Op.SET_I32) 2 else 1
                        // save the previous value unless already saved since the last choice point
                        var k = sp
                        var saved = false
                        while (k > bp) {
                            if (st[k - 2] == idx) {
                                saved = true
                                break
                            }
                            k -= 2
                        }
                        if (!saved) {
                            if (sp + 2 > st.size) st = grow(st, sp + 2)
                            st[sp] = idx
                            st[sp + 1] = cap[idx]
                            sp += 2
                        }
                        cap[idx] = value
                    }
                    Op.CHECK_ADVANCE -> {
                        val idx = regBase + code[pc++]
                        if (cap[idx] == cpos) break@exec
                    }
                    Op.LOOP, Op.LOOP_SPLIT_GOTO_FIRST, Op.LOOP_SPLIT_NEXT_FIRST,
                    Op.LOOP_CHECK_ADV_SPLIT_GOTO_FIRST, Op.LOOP_CHECK_ADV_SPLIT_NEXT_FIRST -> {
                        val idx = regBase + code[pc]
                        val limit: Int
                        val off: Int
                        if (op == Op.LOOP) {
                            limit = 0
                            off = code[pc + 1]
                            pc += 2
                        } else {
                            limit = code[pc + 1]
                            off = code[pc + 2]
                            pc += 3
                        }
                        val counter = cap[idx] - 1
                        var k = sp
                        var saved = false
                        while (k > bp) {
                            if (st[k - 2] == idx) {
                                saved = true
                                break
                            }
                            k -= 2
                        }
                        if (!saved) {
                            if (sp + 2 > st.size) st = grow(st, sp + 2)
                            st[sp] = idx
                            st[sp + 1] = cap[idx]
                            sp += 2
                        }
                        cap[idx] = counter
                        if (op == Op.LOOP) {
                            if (counter != 0) {
                                pc += off
                                if (--interruptCounter <= 0) poll()
                            }
                        } else if (counter > limit) {
                            // mandatory iterations
                            pc += off
                            if (--interruptCounter <= 0) poll()
                        } else {
                            if ((op == Op.LOOP_CHECK_ADV_SPLIT_GOTO_FIRST || op == Op.LOOP_CHECK_ADV_SPLIT_NEXT_FIRST) &&
                                cap[idx + 1] == cpos && counter != limit) break@exec
                            if (counter != 0) {
                                val alt: Int
                                if (op == Op.LOOP_SPLIT_NEXT_FIRST || op == Op.LOOP_CHECK_ADV_SPLIT_NEXT_FIRST) {
                                    alt = pc + off
                                } else {
                                    alt = pc
                                    pc += off
                                }
                                if (sp + 3 > st.size) st = grow(st, sp + 3)
                                st[sp] = alt
                                st[sp + 1] = cpos
                                st[sp + 2] = (bp shl 2) or STATE_SPLIT
                                sp += 3
                                bp = sp
                            }
                        }
                    }
                    Op.WORD_BOUNDARY, Op.WORD_BOUNDARY_I, Op.NOT_WORD_BOUNDARY, Op.NOT_WORD_BOUNDARY_I -> {
                        val ic = op == Op.WORD_BOUNDARY_I || op == Op.NOT_WORD_BOUNDARY_I
                        val isBoundary = op == Op.WORD_BOUNDARY || op == Op.WORD_BOUNDARY_I
                        val v1 = if (cpos == 0) false else {
                            var c = s[cpos - 1].code
                            if (unicode && c in 0xDC00..0xDFFF && cpos >= 2 && s[cpos - 2].isHighSurrogate()) {
                                c = Character.toCodePoint(s[cpos - 2], c.toChar())
                            }
                            isWordChar(c, ic)
                        }
                        val v2 = if (cpos >= end) false else {
                            var c = s[cpos].code
                            if (unicode && c in 0xD800..0xDBFF && cpos + 1 < end && s[cpos + 1].isLowSurrogate()) {
                                c = Character.toCodePoint(c.toChar(), s[cpos + 1])
                            }
                            isWordChar(c, ic)
                        }
                        if ((v1 != v2) != isBoundary) break@exec
                    }
                    Op.BACK_REFERENCE, Op.BACK_REFERENCE_I, Op.BACKWARD_BACK_REFERENCE, Op.BACKWARD_BACK_REFERENCE_I -> {
                        val n = code[pc]
                        val base = pc + 1
                        pc = base + n
                        val ic = op == Op.BACK_REFERENCE_I || op == Op.BACKWARD_BACK_REFERENCE_I
                        val forward = op == Op.BACK_REFERENCE || op == Op.BACK_REFERENCE_I
                        for (i in 0 until n) {
                            val g = code[base + i]
                            val cs = cap[2 * g]
                            val ce = cap[2 * g + 1]
                            if (cs < 0 || ce < 0) continue
                            if (forward) {
                                if (!ic && !unicode) {
                                    val len = ce - cs
                                    if (cpos + len > end || !s.regionMatches(cpos, s, cs, len)) break@exec
                                    cpos += len
                                } else {
                                    var p1 = cs
                                    while (p1 < ce) {
                                        if (cpos >= end) break@exec
                                        var c1 = s[p1++].code
                                        if (unicode && c1 in 0xD800..0xDBFF && p1 < ce && s[p1].isLowSurrogate()) {
                                            c1 = Character.toCodePoint(c1.toChar(), s[p1++])
                                        }
                                        var c2 = s[cpos++].code
                                        if (unicode && c2 in 0xD800..0xDBFF && cpos < end && s[cpos].isLowSurrogate()) {
                                            c2 = Character.toCodePoint(c2.toChar(), s[cpos++])
                                        }
                                        if (ic) {
                                            c1 = canon(c1)
                                            c2 = canon(c2)
                                        }
                                        if (c1 != c2) break@exec
                                    }
                                }
                            } else {
                                var p1 = ce
                                while (p1 > cs) {
                                    if (cpos == 0) break@exec
                                    var c1 = s[--p1].code
                                    if (unicode && c1 in 0xDC00..0xDFFF && p1 > cs && s[p1 - 1].isHighSurrogate()) {
                                        c1 = Character.toCodePoint(s[--p1], c1.toChar())
                                    }
                                    var c2 = s[--cpos].code
                                    if (unicode && c2 in 0xDC00..0xDFFF && cpos > 0 && s[cpos - 1].isHighSurrogate()) {
                                        c2 = Character.toCodePoint(s[--cpos], c2.toChar())
                                    }
                                    if (ic) {
                                        c1 = canon(c1)
                                        c2 = canon(c2)
                                    }
                                    if (c1 != c2) break@exec
                                }
                            }
                            break
                        }
                    }
                    Op.PREV -> {
                        if (cpos == 0) break@exec
                        cpos--
                        if (unicode && s[cpos].isLowSurrogate() && cpos > 0 && s[cpos - 1].isHighSurrogate()) cpos--
                    }
                    Op.STRING_SET -> {
                        val np = matchStringGroup(prog.stringGroups[code[pc++]], cpos)
                        if (np < 0) break@exec
                        cpos = np
                    }
                    Op.LOOKAHEAD_MATCH -> {
                        // pop the choice points up to the lookahead start, keeping the capture undo records
                        val spTop = sp
                        while (true) {
                            val sp1 = sp
                            sp = bp
                            pc = st[sp - 3]
                            cpos = st[sp - 2]
                            val word = st[sp - 1]
                            bp = word ushr 2
                            st[sp - 1] = sp1 // end of this frame's undo records, for the copy below
                            sp -= 3
                            if (word and 3 == STATE_LOOKAHEAD) break
                        }
                        if (sp != 0) {
                            var sp1 = sp
                            while (sp1 < spTop) {
                                val next = st[sp1 + 2]
                                sp1 += 3
                                while (sp1 < next) st[sp++] = st[sp1++]
                            }
                        }
                    }
                    Op.NEGATIVE_LOOKAHEAD_MATCH -> {
                        // the negative lookahead body matched: undo everything up to its start, then fail
                        while (true) {
                            while (sp > bp) {
                                cap[st[sp - 2]] = st[sp - 1]
                                sp -= 2
                            }
                            // pc and cpos are restored by the backtracking below
                            val word = st[sp - 1]
                            bp = word ushr 2
                            sp -= 3
                            if (word and 3 == STATE_NEGATIVE_LOOKAHEAD) break
                        }
                        break@exec
                    }
                    else -> throw IllegalStateException("bad regexp opcode $op")
                }
            }
            // backtrack
            while (true) {
                if (bp == 0) {
                    stack = st
                    return false
                }
                while (sp > bp) {
                    cap[st[sp - 2]] = st[sp - 1]
                    sp -= 2
                }
                pc = st[sp - 3]
                cpos = st[sp - 2]
                val word = st[sp - 1]
                bp = word ushr 2
                sp -= 3
                if (word and 3 != STATE_LOOKAHEAD) break
            }
            if (--interruptCounter <= 0) poll()
        }
    }

    companion object {
        private const val STATE_SPLIT = 0
        private const val STATE_LOOKAHEAD = 1
        private const val STATE_NEGATIVE_LOOKAHEAD = 2
        private const val INTERRUPT_INTERVAL = 4096

        /** Maximum backtracking stack size in ints (256 MB). */
        const val MAX_STACK = 1 shl 26
    }
}
