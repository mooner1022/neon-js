package io.neonjs.regexp

import io.neonjs.regexp.RegExpProgram.Companion.FLAG_DOTALL
import io.neonjs.regexp.RegExpProgram.Companion.FLAG_IGNORECASE
import io.neonjs.regexp.RegExpProgram.Companion.FLAG_MULTILINE
import io.neonjs.regexp.RegExpProgram.Companion.FLAG_UNICODE
import io.neonjs.regexp.RegExpProgram.Companion.FLAG_UNICODE_SETS
import io.neonjs.unicode.CharRanges
import io.neonjs.unicode.UnicodeTables

/** Early error in a regular expression pattern. */
class RegExpSyntaxError(message: String) : RuntimeException(message, null, false, false)

/** Growable IntArray supporting insertion, used to emit bytecode. */
internal class IntBuf {
    @JvmField var a = IntArray(64)
    @JvmField var size = 0

    private fun ensure(n: Int) {
        if (n > a.size) {
            if (n > MAX_CODE_SIZE) throw RegExpSyntaxError("regular expression too large")
            a = a.copyOf(maxOf(n, a.size * 2))
        }
    }

    fun add(v: Int) {
        ensure(size + 1)
        a[size++] = v
    }

    /** Inserts [n] zero ints at [pos]. */
    fun insert(pos: Int, n: Int) {
        ensure(size + n)
        System.arraycopy(a, pos, a, pos + n, size - pos)
        java.util.Arrays.fill(a, pos, pos + n, 0)
        size += n
    }

    operator fun get(i: Int) = a[i]
    operator fun set(i: Int, v: Int) {
        a[i] = v
    }

    fun toArray(): IntArray = a.copyOf(size)

    companion object {
        const val MAX_CODE_SIZE = 1 shl 26
    }
}

/**
 * Regular expression parser and bytecode compiler (ECMAScript 2025 pattern grammar including Annex B legacy syntax
 * in non-unicode mode, named groups with duplicates, lookbehind, `v` flag set operations and pattern modifiers).
 * Structure after QuickJS libregexp.
 */
internal class RegExpCompiler private constructor(private val src: String, private val reFlags: Int) {
    private val end = src.length
    private var pos = 0
    private val isUnicode = reFlags and (FLAG_UNICODE or FLAG_UNICODE_SETS) != 0
    private val unicodeSets = reFlags and FLAG_UNICODE_SETS != 0
    private var ignoreCase = reFlags and FLAG_IGNORECASE != 0
    private var multiline = reFlags and FLAG_MULTILINE != 0
    private var dotAll = reFlags and FLAG_DOTALL != 0

    private val code = IntBuf()
    private var captureCount = 1
    private val groupNames = ArrayList<String?>().apply { add(null) }
    private var anyGroupName = false
    /** Group names that may participate together with a group defined at the current position. */
    private var visibleNames = HashSet<String>()
    private val stringGroups = ArrayList<StringGroup>()
    private var depth = 0

    // pre-scan results (computed on demand)
    private var prescanned = false
    private var prescanHasNames = false
    private val prescanNames = ArrayList<String?>()

    // result of getClassAtom when it returns CLASS
    private var atomClass: ClassSet? = null
    private var atomClassEscape = 0
    private var escEnd = 0

    /** A character class value: code points (inversion list) plus, in `v` mode, strings of length != 1. */
    private class ClassSet(@JvmField var chars: IntArray) {
        @JvmField var strings: HashSet<String>? = null
        @JvmField var mayContainStrings = false
    }

    private fun error(msg: String): Nothing = throw RegExpSyntaxError(msg)

    private fun ch(i: Int): Int = if (i < end) src[i].code else -1

    // ------------------------------------------------------------------ emission helpers

    private fun emit(op: Int) = code.add(op)

    private fun emit(op: Int, a: Int) {
        code.add(op)
        code.add(a)
    }

    /** Emits a jump instruction with a placeholder offset; returns the offset slot. */
    private fun emitJump(op: Int): Int {
        code.add(op)
        code.add(0)
        return code.size - 1
    }

    /** Emits a jump to [target]. */
    private fun emitJumpTo(op: Int, target: Int) {
        code.add(op)
        code.add(target - (code.size + 1))
    }

    /** Points the jump whose offset is at [slot] to the current end of the code. */
    private fun patch(slot: Int) {
        code[slot] = code.size - (slot + 1)
    }

    // ------------------------------------------------------------------ compile

    private fun compile(): RegExpProgram {
        // the search over start positions of non-sticky patterns is done by RegExpMatcher.match
        emit(Op.SAVE_START, 0)
        parseDisjunction(false)
        emit(Op.SAVE_END, 0)
        emit(Op.MATCH)
        if (pos < end) error(if (src[pos] == ')') "unmatched ')'" else "extraneous characters at the end")
        val codeArr = code.toArray()
        val registerCount = computeRegisterCount(codeArr)
        return RegExpProgram(
            codeArr, captureCount, registerCount,
            if (anyGroupName) groupNames.toTypedArray() else null,
            reFlags, stringGroups.toTypedArray(),
        )
    }

    /** Allocates loop registers as a stack (the control flow is properly nested). */
    private fun computeRegisterCount(c: IntArray): Int {
        var stackSize = 0
        var max = 0
        var pc = 0
        while (pc < c.size) {
            when (c[pc]) {
                Op.SET_I32, Op.SET_CHAR_POS -> {
                    c[pc + 1] = stackSize
                    stackSize++
                    if (stackSize > max) {
                        if (stackSize > MAX_REGISTERS) error("too many nested quantifiers")
                        max = stackSize
                    }
                }
                Op.CHECK_ADVANCE, Op.LOOP, Op.LOOP_SPLIT_GOTO_FIRST, Op.LOOP_SPLIT_NEXT_FIRST -> {
                    stackSize--
                    c[pc + 1] = stackSize
                }
                Op.LOOP_CHECK_ADV_SPLIT_GOTO_FIRST, Op.LOOP_CHECK_ADV_SPLIT_NEXT_FIRST -> {
                    stackSize -= 2
                    c[pc + 1] = stackSize
                }
            }
            pc += Op.size(c, pc)
        }
        return max
    }

    private fun enterNesting() {
        if (++depth > MAX_DEPTH) error("regular expression too deeply nested")
    }

    // ------------------------------------------------------------------ disjunction / alternative

    private fun parseDisjunction(backward: Boolean) {
        enterNesting()
        val start = code.size
        val snapshot: HashSet<String>? = if (hasNamedGroups()) HashSet(visibleNames) else null
        var accum: HashSet<String>? = null
        parseAlternative(backward)
        while (ch(pos) == '|'.code) {
            pos++
            if (snapshot != null) {
                if (accum == null) accum = HashSet()
                accum.addAll(visibleNames)
                visibleNames = HashSet(snapshot)
            }
            val len = code.size - start
            code.insert(start, 2)
            code[start] = Op.SPLIT_NEXT_FIRST
            code[start + 1] = len + 2
            val gotoSlot = emitJump(Op.GOTO)
            parseAlternative(backward)
            patch(gotoSlot)
        }
        if (accum != null) visibleNames.addAll(accum)
        depth--
    }

    private fun parseAlternative(backward: Boolean) {
        val start = code.size
        while (true) {
            val c = ch(pos)
            if (c < 0 || c == '|'.code || c == ')'.code) break
            val termStart = code.size
            parseTerm(backward)
            if (backward && termStart != start) {
                // reverse the order of the terms
                val termSize = code.size - termStart
                val term = code.a.copyOfRange(termStart, code.size)
                System.arraycopy(code.a, start, code.a, start + termSize, termStart - start)
                System.arraycopy(term, 0, code.a, start, termSize)
            }
        }
    }

    // ------------------------------------------------------------------ term

    private fun parseTerm(backward: Boolean) {
        var lastAtomStart = -1
        var lastCaptureCount = 0
        var c = ch(pos)
        var atomChar = -1 // a single character atom to emit, or CLASS
        when (c) {
            '^'.code -> {
                pos++
                emit(if (multiline) Op.LINE_START_M else Op.LINE_START)
            }
            '$'.code -> {
                pos++
                emit(if (multiline) Op.LINE_END_M else Op.LINE_END)
            }
            '.'.code -> {
                pos++
                lastAtomStart = code.size
                lastCaptureCount = captureCount
                if (backward) emit(Op.PREV)
                emit(if (dotAll) Op.ANY else Op.DOT)
                if (backward) emit(Op.PREV)
            }
            '{'.code -> {
                if (isUnicode) error("nothing to repeat")
                if (isDigit(ch(pos + 1))) {
                    // Annex B: error only if it looks like a complete repetition count
                    var p = pos + 1
                    while (isDigit(ch(p))) p++
                    if (ch(p) == ','.code) {
                        p++
                        while (isDigit(ch(p))) p++
                    }
                    if (ch(p) == '}'.code) error("nothing to repeat")
                }
                atomChar = getClassAtom(inClass = false, allowClass = true)
            }
            '*'.code, '+'.code, '?'.code -> error("nothing to repeat")
            '('.code -> {
                if (ch(pos + 1) == '?'.code) {
                    val c2 = ch(pos + 2)
                    when {
                        c2 == ':'.code -> {
                            pos += 3
                            lastAtomStart = code.size
                            lastCaptureCount = captureCount
                            parseDisjunction(backward)
                            expect(')')
                        }
                        c2 == 'i'.code || c2 == 'm'.code || c2 == 's'.code || c2 == '-'.code -> {
                            pos += 2
                            val addMask = parseModifiers()
                            var removeMask = 0
                            if (ch(pos) == '-'.code) {
                                pos++
                                removeMask = parseModifiers()
                            }
                            if ((addMask == 0 && removeMask == 0) || (addMask and removeMask) != 0) error("invalid modifiers")
                            expect(':')
                            val savedI = ignoreCase
                            val savedM = multiline
                            val savedS = dotAll
                            ignoreCase = updateModifier(ignoreCase, addMask, removeMask, FLAG_IGNORECASE)
                            multiline = updateModifier(multiline, addMask, removeMask, FLAG_MULTILINE)
                            dotAll = updateModifier(dotAll, addMask, removeMask, FLAG_DOTALL)
                            lastAtomStart = code.size
                            lastCaptureCount = captureCount
                            parseDisjunction(backward)
                            expect(')')
                            ignoreCase = savedI
                            multiline = savedM
                            dotAll = savedS
                        }
                        c2 == '='.code || c2 == '!'.code || (c2 == '<'.code && (ch(pos + 3) == '='.code || ch(pos + 3) == '!'.code)) -> {
                            val isBackwardLookaround = c2 == '<'.code
                            val isNeg = (if (isBackwardLookaround) ch(pos + 3) else c2) == '!'.code
                            pos += if (isBackwardLookaround) 4 else 3
                            // Annex B: lookaheads are quantifiable in non-unicode mode
                            if (!isUnicode && !isBackwardLookaround) {
                                lastAtomStart = code.size
                                lastCaptureCount = captureCount
                            }
                            val slot = emitJump(if (isNeg) Op.NEGATIVE_LOOKAHEAD else Op.LOOKAHEAD)
                            parseDisjunction(isBackwardLookaround)
                            expect(')')
                            emit(if (isNeg) Op.NEGATIVE_LOOKAHEAD_MATCH else Op.LOOKAHEAD_MATCH)
                            patch(slot)
                        }
                        c2 == '<'.code -> {
                            val name = parseGroupName(pos + 3) ?: error("invalid capture group name")
                            pos = escEnd
                            if (!visibleNames.add(name)) error("duplicate capture group name")
                            anyGroupName = true
                            lastAtomStart = code.size
                            lastCaptureCount = captureCount
                            parseCapture(name, backward)
                        }
                        else -> error("invalid group")
                    }
                } else {
                    pos++
                    lastAtomStart = code.size
                    lastCaptureCount = captureCount
                    parseCapture(null, backward)
                }
            }
            '\\'.code -> {
                when (ch(pos + 1)) {
                    'b'.code, 'B'.code -> {
                        val boundary = ch(pos + 1) == 'b'.code
                        val i = ignoreCase && isUnicode
                        emit(if (boundary) (if (i) Op.WORD_BOUNDARY_I else Op.WORD_BOUNDARY)
                             else (if (i) Op.NOT_WORD_BOUNDARY_I else Op.NOT_WORD_BOUNDARY))
                        pos += 2
                    }
                    'k'.code -> {
                        val indices = parseNamedBackReference()
                        if (indices == null) {
                            atomChar = getClassAtom(inClass = false, allowClass = true)
                        } else {
                            lastAtomStart = code.size
                            lastCaptureCount = captureCount
                            emitBackReference(indices, backward)
                        }
                    }
                    '0'.code -> {
                        pos += 2
                        c = 0
                        if (isUnicode) {
                            if (isDigit(ch(pos))) error("invalid decimal escape")
                        } else if (ch(pos) in '0'.code..'7'.code) {
                            // Annex B legacy octal escape
                            c = ch(pos++) - '0'.code
                            if (ch(pos) in '0'.code..'7'.code) c = c * 8 + (ch(pos++) - '0'.code)
                        }
                        atomChar = c
                    }
                    in '1'.code..'9'.code -> {
                        pos++
                        val q = pos
                        val n = parseDigits(false)
                        if (n < 0 || (n >= captureCount && n >= totalCaptureCount())) {
                            if (isUnicode) error("back reference out of range")
                            // Annex B: legacy octal escape or identity escape
                            pos = q
                            if (ch(pos) <= '7'.code) {
                                c = 0
                                if (ch(pos) <= '3'.code) c = ch(pos++) - '0'.code
                                if (ch(pos) in '0'.code..'7'.code) {
                                    c = c * 8 + (ch(pos++) - '0'.code)
                                    if (ch(pos) in '0'.code..'7'.code) c = c * 8 + (ch(pos++) - '0'.code)
                                }
                            } else {
                                c = ch(pos++)
                            }
                            atomChar = c
                        } else {
                            lastAtomStart = code.size
                            lastCaptureCount = captureCount
                            emitBackReference(intArrayOf(n), backward)
                        }
                    }
                    else -> atomChar = getClassAtom(inClass = false, allowClass = true)
                }
            }
            '['.code -> {
                lastAtomStart = code.size
                lastCaptureCount = captureCount
                val cs = if (unicodeSets) parseClassV() else parseClassLegacy()
                emitClassSet(cs, backward)
            }
            ']'.code, '}'.code -> {
                if (isUnicode) error("lone quantifier brackets")
                atomChar = getClassAtom(inClass = false, allowClass = true)
            }
            else -> atomChar = getClassAtom(inClass = false, allowClass = true)
        }
        if (atomChar != -1) {
            lastAtomStart = code.size
            lastCaptureCount = captureCount
            if (atomChar == CLASS) {
                val cs = atomClass!!
                atomClass = null
                if (atomClassEscape == 's'.code || atomClassEscape == 'S'.code) {
                    if (backward) emit(Op.PREV)
                    emit(if (atomClassEscape == 's'.code) Op.SPACE else Op.NOT_SPACE)
                    if (backward) emit(Op.PREV)
                } else {
                    if (!unicodeSets && ignoreCase) cs.chars = UnicodeTables.canonicalizeSet(cs.chars, isUnicode)
                    emitClassSet(cs, backward)
                }
            } else {
                if (backward) emit(Op.PREV)
                if (ignoreCase) emit(Op.CHAR_I, UnicodeTables.canonicalize(atomChar, isUnicode))
                else emit(Op.CHAR, atomChar)
                if (backward) emit(Op.PREV)
            }
        }
        if (lastAtomStart >= 0) parseQuantifier(lastAtomStart, lastCaptureCount)
    }

    private fun parseCapture(name: String?, backward: Boolean) {
        if (captureCount >= MAX_CAPTURES) error("too many captures")
        val idx = captureCount++
        groupNames.add(name)
        emit(if (backward) Op.SAVE_END else Op.SAVE_START, idx)
        parseDisjunction(backward)
        emit(if (backward) Op.SAVE_START else Op.SAVE_END, idx)
        expect(')')
    }

    private fun parseModifiers(): Int {
        var mask = 0
        while (true) {
            val v = when (ch(pos)) {
                'i'.code -> FLAG_IGNORECASE
                'm'.code -> FLAG_MULTILINE
                's'.code -> FLAG_DOTALL
                else -> break
            }
            if (mask and v != 0) error("duplicate modifier")
            mask = mask or v
            pos++
        }
        return mask
    }

    private fun updateModifier(v: Boolean, add: Int, remove: Int, mask: Int): Boolean =
        if (remove and mask != 0) false else if (add and mask != 0) true else v

    private fun expect(c: Char) {
        if (ch(pos) != c.code) error("expecting '$c'")
        pos++
    }

    /** Parses decimal digits at [pos]; clamps to Int.MAX_VALUE if [allowOverflow], else returns -1 on overflow. */
    private fun parseDigits(allowOverflow: Boolean): Int {
        var v = 0L
        while (isDigit(ch(pos))) {
            v = v * 10 + (ch(pos) - '0'.code)
            if (v >= Int.MAX_VALUE) {
                if (!allowOverflow) return -1
                v = Int.MAX_VALUE.toLong()
            }
            pos++
        }
        return v.toInt()
    }

    private fun emitBackReference(indices: IntArray, backward: Boolean) {
        val op = if (backward) {
            if (ignoreCase) Op.BACKWARD_BACK_REFERENCE_I else Op.BACKWARD_BACK_REFERENCE
        } else {
            if (ignoreCase) Op.BACK_REFERENCE_I else Op.BACK_REFERENCE
        }
        emit(op, indices.size)
        for (i in indices) code.add(i)
    }

    /**
     * Parses `\k<name>` at [pos]. Returns the capture indices of the groups with that name, or null if the
     * escape must be parsed as an identity escape (Annex B, non-unicode pattern without named groups).
     */
    private fun parseNamedBackReference(): IntArray? {
        val strict = isUnicode || hasNamedGroups()
        if (ch(pos + 2) != '<'.code) {
            if (strict) error("expecting group name")
            return null
        }
        val name = parseGroupName(pos + 3)
        if (name == null) {
            if (strict) error("invalid group name")
            return null
        }
        prescan()
        val idx = ArrayList<Int>()
        for (i in prescanNames.indices) if (prescanNames[i] == name) idx.add(i)
        if (idx.isEmpty()) {
            if (strict) error("group name not defined")
            return null
        }
        pos = escEnd
        return idx.toIntArray()
    }

    // ------------------------------------------------------------------ quantifiers

    private fun parseQuantifier(atomStart: Int, lastCaptureCount: Int) {
        var lastAtomStart = atomStart
        val quantMin: Int
        var quantMax: Int
        when (ch(pos)) {
            '*'.code -> { pos++; quantMin = 0; quantMax = INF }
            '+'.code -> { pos++; quantMin = 1; quantMax = INF }
            '?'.code -> { pos++; quantMin = 0; quantMax = 1 }
            '{'.code -> {
                val p1 = pos
                if (!isDigit(ch(pos + 1))) {
                    if (isUnicode) error("incomplete quantifier")
                    return
                }
                pos++
                quantMin = parseDigits(true)
                quantMax = quantMin
                if (ch(pos) == ','.code) {
                    pos++
                    quantMax = if (isDigit(ch(pos))) parseDigits(true) else INF
                }
                if (ch(pos) != '}'.code) {
                    if (!isUnicode) {
                        // Annex B: '{' is a literal character
                        pos = p1
                        return
                    }
                    error("incomplete quantifier")
                }
                pos++
                if (quantMax < quantMin) error("numbers out of order in {} quantifier")
            }
            else -> return
        }
        var greedy = true
        if (ch(pos) == '?'.code) {
            pos++
            greedy = false
        }
        // analyse the atom
        var addZeroAdvanceCheck = true
        var needCaptureInit = false
        run {
            var pc = lastAtomStart
            while (pc < code.size) {
                when (code[pc]) {
                    Op.RANGE, Op.RANGE_I, Op.CHAR, Op.CHAR_I, Op.DOT, Op.ANY, Op.SPACE, Op.NOT_SPACE -> addZeroAdvanceCheck = false
                    Op.LINE_START, Op.LINE_START_M, Op.LINE_END, Op.LINE_END_M, Op.SET_I32, Op.SET_CHAR_POS,
                    Op.WORD_BOUNDARY, Op.WORD_BOUNDARY_I, Op.NOT_WORD_BOUNDARY, Op.NOT_WORD_BOUNDARY_I, Op.PREV,
                    Op.SAVE_START, Op.SAVE_END, Op.SAVE_RESET -> {}
                    Op.BACK_REFERENCE, Op.BACK_REFERENCE_I, Op.BACKWARD_BACK_REFERENCE, Op.BACKWARD_BACK_REFERENCE_I ->
                        needCaptureInit = true
                    else -> {
                        needCaptureInit = true
                        return@run
                    }
                }
                pc += Op.size(code.a, pc)
            }
        }
        // reset the captures of the atom at each iteration when they may be stale
        if (needCaptureInit && lastCaptureCount != captureCount) {
            code.insert(lastAtomStart, 3)
            code[lastAtomStart] = Op.SAVE_RESET
            code[lastAtomStart + 1] = lastCaptureCount
            code[lastAtomStart + 2] = captureCount - 1
        }
        val len = code.size - lastAtomStart
        if (quantMin == 0) {
            // the captures must be reset if the atom is skipped
            if (!needCaptureInit && lastCaptureCount != captureCount) {
                code.insert(lastAtomStart, 3)
                code[lastAtomStart++] = Op.SAVE_RESET
                code[lastAtomStart++] = lastCaptureCount
                code[lastAtomStart++] = captureCount - 1
            }
            val adv = if (addZeroAdvanceCheck) 1 else 0
            if (quantMax == 0) {
                code.size = lastAtomStart
            } else if (quantMax == 1 || quantMax == INF) {
                val hasGoto = quantMax == INF
                code.insert(lastAtomStart, 2 + adv * 2)
                code[lastAtomStart] = if (greedy) Op.SPLIT_NEXT_FIRST else Op.SPLIT_GOTO_FIRST
                code[lastAtomStart + 1] = len + (if (hasGoto) 2 else 0) + adv * 4
                if (addZeroAdvanceCheck) {
                    code[lastAtomStart + 2] = Op.SET_CHAR_POS
                    code[lastAtomStart + 3] = 0
                    emit(Op.CHECK_ADVANCE, 0)
                }
                if (hasGoto) emitJumpTo(Op.GOTO, lastAtomStart)
            } else {
                code.insert(lastAtomStart, 5 + adv * 2)
                var p = lastAtomStart
                code[p++] = if (greedy) Op.SPLIT_NEXT_FIRST else Op.SPLIT_GOTO_FIRST
                code[p++] = 3 + adv * 2 + len + 4
                code[p++] = Op.SET_I32
                code[p++] = 0
                code[p++] = quantMax
                lastAtomStart = p
                if (addZeroAdvanceCheck) {
                    code[p++] = Op.SET_CHAR_POS
                    code[p] = 0
                }
                emitLoop(loopOp(addZeroAdvanceCheck, greedy), quantMax, lastAtomStart)
            }
        } else if (quantMin == 1 && quantMax == INF && !addZeroAdvanceCheck) {
            emitJumpTo(if (greedy) Op.SPLIT_GOTO_FIRST else Op.SPLIT_NEXT_FIRST, lastAtomStart)
        } else {
            if (quantMin == quantMax) addZeroAdvanceCheck = false
            val adv = if (addZeroAdvanceCheck) 1 else 0
            code.insert(lastAtomStart, 3 + adv * 2)
            var p = lastAtomStart
            code[p++] = Op.SET_I32
            code[p++] = 0
            code[p++] = quantMax
            lastAtomStart = p
            if (addZeroAdvanceCheck) {
                code[p++] = Op.SET_CHAR_POS
                code[p] = 0
            }
            if (quantMin == quantMax) {
                code.add(Op.LOOP)
                code.add(0)
                code.add(lastAtomStart - (code.size + 1))
            } else {
                emitLoop(loopOp(addZeroAdvanceCheck, greedy), quantMax - quantMin, lastAtomStart)
            }
        }
    }

    private fun loopOp(checkAdvance: Boolean, greedy: Boolean): Int =
        if (checkAdvance) (if (greedy) Op.LOOP_CHECK_ADV_SPLIT_GOTO_FIRST else Op.LOOP_CHECK_ADV_SPLIT_NEXT_FIRST)
        else (if (greedy) Op.LOOP_SPLIT_GOTO_FIRST else Op.LOOP_SPLIT_NEXT_FIRST)

    private fun emitLoop(op: Int, limit: Int, target: Int) {
        code.add(op)
        code.add(0)
        code.add(limit)
        code.add(target - (code.size + 1))
    }

    // ------------------------------------------------------------------ escapes and class atoms

    /**
     * Parses an escape sequence whose letter is at [p0] (after the backslash). [allowUtf16]: 0 = no `\u{...}`,
     * 1 = `\u{...}` allowed, 2 = unicode mode (`\u{...}`, surrogate pair escapes combined, no legacy octal).
     * Returns the code point (and sets [escEnd]), -1 if malformed, -2 if not a character escape.
     */
    private fun parseEscapeAt(p0: Int, allowUtf16: Int): Int {
        var p = p0
        var c = ch(p++)
        when (c) {
            'b'.code -> c = 8
            'f'.code -> c = 12
            'n'.code -> c = 10
            'r'.code -> c = 13
            't'.code -> c = 9
            'v'.code -> c = 11
            'x'.code -> {
                val h0 = hexVal(ch(p))
                val h1 = hexVal(ch(p + 1))
                if (h0 < 0 || h1 < 0) return -1
                c = h0 * 16 + h1
                p += 2
            }
            'u'.code -> {
                if (ch(p) == '{'.code && allowUtf16 != 0) {
                    p++
                    c = 0
                    while (true) {
                        val h = hexVal(ch(p))
                        if (h < 0) return -1
                        c = c * 16 + h
                        if (c > 0x10FFFF) return -1
                        p++
                        if (ch(p) == '}'.code) break
                    }
                    p++
                } else {
                    c = 0
                    for (i in 0 until 4) {
                        val h = hexVal(ch(p))
                        if (h < 0) return -1
                        c = c * 16 + h
                        p++
                    }
                    if (c in 0xD800..0xDBFF && allowUtf16 == 2 && ch(p) == '\\'.code && ch(p + 1) == 'u'.code) {
                        var c1 = 0
                        var i = 0
                        while (i < 4) {
                            val h = hexVal(ch(p + 2 + i))
                            if (h < 0) break
                            c1 = c1 * 16 + h
                            i++
                        }
                        if (i == 4 && c1 in 0xDC00..0xDFFF) {
                            p += 6
                            c = Character.toCodePoint(c.toChar(), c1.toChar())
                        }
                    }
                }
            }
            in '0'.code..'7'.code -> {
                c -= '0'.code
                if (allowUtf16 == 2) {
                    if (c != 0 || isDigit(ch(p))) return -1
                } else {
                    // legacy octal sequence
                    var v = ch(p) - '0'.code
                    if (v in 0..7) {
                        c = c * 8 + v
                        p++
                        if (c < 32) {
                            v = ch(p) - '0'.code
                            if (v in 0..7) {
                                c = c * 8 + v
                                p++
                            }
                        }
                    }
                }
            }
            else -> return -2
        }
        escEnd = p
        return c
    }

    private fun readSourceChar(): Int {
        val c = src[pos++].code
        if (isUnicode && c in 0xD800..0xDBFF && pos < end) {
            val d = src[pos].code
            if (d in 0xDC00..0xDFFF) {
                pos++
                return Character.toCodePoint(c.toChar(), d.toChar())
            }
        }
        return c
    }

    /**
     * Parses a class atom (or a pattern character when ![inClass]). Returns the code point, or [CLASS] with the set
     * in [atomClass] for class escapes (`\d`, `\p{..}`, `\q{..}`...) when [allowClass].
     */
    private fun getClassAtom(inClass: Boolean, allowClass: Boolean): Int {
        val c = ch(pos)
        if (c == '\\'.code) {
            if (pos + 1 >= end) error("\\ at end of pattern")
            val e = src[pos + 1].code
            pos += 2
            when (e) {
                'd'.code, 'D'.code, 's'.code, 'S'.code, 'w'.code, 'W'.code -> if (allowClass) {
                    atomClass = ClassSet(classEscape(e))
                    atomClassEscape = e
                    return CLASS
                }
                'c'.code -> {
                    val d = ch(pos)
                    if (isAsciiLetter(d) || ((isDigit(d) || d == '_'.code) && inClass && !isUnicode)) {
                        pos++
                        return d and 0x1f
                    }
                    if (isUnicode) error("invalid escape")
                    // Annex B: '\' is a literal character, 'c' is parsed next
                    pos--
                    return '\\'.code
                }
                '-'.code -> {
                    if (!inClass && isUnicode) error("invalid escape")
                    return e
                }
                '&'.code, '!'.code, '#'.code, '%'.code, ','.code, ':'.code, ';'.code, '<'.code, '='.code, '>'.code,
                '@'.code, '`'.code, '~'.code -> {
                    if (isUnicode && (!inClass || !unicodeSets)) error("invalid escape")
                    return e
                }
                '^'.code, '$'.code, '\\'.code, '.'.code, '*'.code, '+'.code, '?'.code, '('.code, ')'.code, '['.code,
                ']'.code, '{'.code, '}'.code, '|'.code, '/'.code -> return e
                'p'.code, 'P'.code -> if (isUnicode && allowClass) {
                    atomClass = parseUnicodeProperty(e == 'P'.code)
                    atomClassEscape = e
                    return CLASS
                }
                'q'.code -> if (unicodeSets && allowClass && inClass) {
                    atomClass = parseClassStringDisjunction()
                    atomClassEscape = e
                    return CLASS
                }
            }
            val r = parseEscapeAt(pos - 1, if (isUnicode) 2 else 0)
            if (r >= 0) {
                pos = escEnd
                return r
            }
            if (isUnicode) error("invalid escape")
            if (e == 'k'.code && hasNamedGroups()) error("invalid escape")
            // Annex B identity escape: the character after the backslash
            pos--
            return readSourceChar()
        }
        if (c < 0) error(if (inClass) "unterminated character class" else "unexpected end")
        if (inClass && unicodeSets) {
            when (c) {
                '&'.code, '!'.code, '#'.code, '$'.code, '%'.code, '*'.code, '+'.code, ','.code, '.'.code, ':'.code,
                ';'.code, '<'.code, '='.code, '>'.code, '?'.code, '@'.code, '^'.code, '`'.code, '~'.code ->
                    if (ch(pos + 1) == c) error("invalid set operation in character class")
                '('.code, ')'.code, '['.code, ']'.code, '{'.code, '}'.code, '/'.code, '-'.code, '|'.code ->
                    error("invalid character in character class")
            }
        }
        return readSourceChar()
    }

    private fun wordChars(): IntArray {
        var set = BASIC_WORD
        if (isUnicode && ignoreCase) set = CharRanges.union(set, EXTRA_WORD)
        return set
    }

    /** Set of a `\d \D \s \S \w \W` escape (case folded in `v` mode when ignoring case). */
    private fun classEscape(e: Int): IntArray = when (e) {
        'd'.code -> DIGITS
        'D'.code -> CharRanges.invert(DIGITS)
        's'.code -> spaceSet
        'S'.code -> CharRanges.invert(spaceSet)
        'w'.code -> maybeFold(wordChars())
        else -> CharRanges.invert(maybeFold(wordChars()))
    }

    /** MaybeSimpleCaseFolding: folds the set in `v` mode when ignoring case. */
    private fun maybeFold(set: IntArray): IntArray =
        if (unicodeSets && ignoreCase) UnicodeTables.canonicalizeSet(set, true) else set

    private fun maybeFold(cs: ClassSet): ClassSet {
        if (!(unicodeSets && ignoreCase)) return cs
        cs.chars = UnicodeTables.canonicalizeSet(cs.chars, true)
        val strings = cs.strings
        if (strings != null) {
            val folded = HashSet<String>()
            for (s in strings) {
                val sb = StringBuilder(s.length)
                var i = 0
                while (i < s.length) {
                    val cp = s.codePointAt(i)
                    sb.appendCodePoint(UnicodeTables.canonicalize(cp, true))
                    i += Character.charCount(cp)
                }
                folded.add(sb.toString())
            }
            cs.strings = folded
        }
        return cs
    }

    private fun parseUnicodeProperty(negate: Boolean): ClassSet {
        if (ch(pos) != '{'.code) error("invalid property name")
        pos++
        val nameStart = pos
        while (isPropertyChar(ch(pos))) pos++
        val name = src.substring(nameStart, pos)
        var value: String? = null
        if (ch(pos) == '='.code) {
            pos++
            val vs = pos
            while (isPropertyChar(ch(pos))) pos++
            value = src.substring(vs, pos)
        }
        if (ch(pos) != '}'.code) error("invalid property name")
        pos++
        var chars: IntArray?
        if (value != null) {
            chars = when (name) {
                "General_Category", "gc" -> UnicodeTables.generalCategory(value)
                "Script", "sc" -> UnicodeTables.script(value, false)
                "Script_Extensions", "scx" -> UnicodeTables.script(value, true)
                else -> null
            }
            if (chars == null) error("invalid property name")
        } else {
            chars = UnicodeTables.generalCategory(name) ?: UnicodeTables.binaryProperty(name)
            if (chars == null) {
                val sp = if (unicodeSets) UnicodeTables.stringProperty(name) else null
                if (sp == null || negate) error("invalid property name")
                val cs = ClassSet(sp.chars)
                val strings = HashSet<String>(sp.strings.size * 2)
                for (s in sp.strings) strings.add(String(s, 0, s.size))
                cs.strings = strings
                cs.mayContainStrings = true
                return maybeFold(cs)
            }
        }
        var set = maybeFold(chars)
        if (negate) set = CharRanges.invert(set)
        return ClassSet(set)
    }

    /** `\q{...}` (after the 'q'). */
    private fun parseClassStringDisjunction(): ClassSet {
        if (ch(pos) != '{'.code) error("expecting '{' after \\q")
        pos++
        val chars = CharRanges.Builder()
        val strings = HashSet<String>()
        var may = false
        while (true) {
            val sb = StringBuilder()
            var n = 0
            var last = 0
            while (ch(pos) != '}'.code && ch(pos) != '|'.code) {
                last = getClassAtom(inClass = true, allowClass = false)
                sb.appendCodePoint(last)
                n++
            }
            if (n == 1) chars.add(last) else {
                strings.add(sb.toString())
                may = true
            }
            if (ch(pos) == '}'.code) break
            pos++
        }
        pos++
        val cs = ClassSet(chars.build())
        if (strings.isNotEmpty()) cs.strings = strings
        cs.mayContainStrings = may
        return maybeFold(cs)
    }

    // ------------------------------------------------------------------ character classes

    /** `[...]` without the `v` flag (NonemptyClassRanges with Annex B extensions). */
    private fun parseClassLegacy(): ClassSet {
        pos++
        val invert = ch(pos) == '^'.code
        if (invert) pos++
        val b = CharRanges.Builder()
        while (ch(pos) != ']'.code) {
            val c1 = getClassAtom(inClass = true, allowClass = true)
            val set1 = if (c1 == CLASS) atomClass!!.chars else null
            if (ch(pos) == '-'.code && ch(pos + 1) != ']'.code) {
                if (set1 != null) {
                    if (isUnicode) error("invalid character class range")
                    b.addSet(set1) // Annex B: '-' is parsed as a character next
                    continue
                }
                val save = pos
                pos++
                val c2 = getClassAtom(inClass = true, allowClass = true)
                if (c2 == CLASS) {
                    if (isUnicode) error("invalid character class range")
                    pos = save // Annex B: '-' is parsed as a character next
                    b.add(c1)
                    continue
                }
                if (c2 < c1) error("range out of order in character class")
                b.addRange(c1, c2)
            } else if (set1 != null) {
                b.addSet(set1)
            } else {
                b.add(c1)
            }
        }
        pos++
        var set = b.build()
        if (ignoreCase) set = UnicodeTables.canonicalizeSet(set, isUnicode)
        if (invert) set = CharRanges.invert(set)
        return ClassSet(set)
    }

    /** `[...]` with the `v` flag (ClassSetExpression), also used for nested classes. */
    private fun parseClassV(): ClassSet {
        enterNesting()
        pos++
        val invert = ch(pos) == '^'.code
        if (invert) pos++
        var acc = ClassSet(CharRanges.EMPTY)
        var isFirst = true
        while (ch(pos) != ']'.code) {
            val operand: ClassSet
            if (ch(pos) == '['.code) {
                operand = parseClassV()
            } else {
                val c1 = getClassAtom(inClass = true, allowClass = true)
                if (ch(pos) == '-'.code && ch(pos + 1) != ']'.code && !(ch(pos + 1) == '-'.code && isFirst)) {
                    if (c1 == CLASS) error("invalid character class range")
                    pos++
                    val c2 = getClassAtom(inClass = true, allowClass = true)
                    if (c2 == CLASS) error("invalid character class range")
                    if (c2 < c1) error("range out of order in character class")
                    acc = setOp(acc, ClassSet(maybeFold(CharRanges.of(c1, c2))), CharRanges.OP_UNION)
                    isFirst = false
                    continue
                }
                operand = if (c1 == CLASS) atomClass!! else ClassSet(maybeFold(CharRanges.single(c1)))
            }
            acc = setOp(acc, operand, CharRanges.OP_UNION)
            acc.mayContainStrings = acc.mayContainStrings || operand.mayContainStrings
            if (isFirst) {
                if (ch(pos) == '&'.code && ch(pos + 1) == '&'.code && ch(pos + 2) != '&'.code) {
                    var may = acc.mayContainStrings
                    while (ch(pos) != ']'.code) {
                        if (ch(pos) == '&'.code && ch(pos + 1) == '&'.code && ch(pos + 2) != '&'.code) pos += 2
                        else error("invalid set operation in character class")
                        val op = parseClassSetOperand()
                        acc = setOp(acc, op, CharRanges.OP_INTER)
                        may = may && op.mayContainStrings
                    }
                    acc.mayContainStrings = may
                } else if (ch(pos) == '-'.code && ch(pos + 1) == '-'.code) {
                    val may = acc.mayContainStrings
                    while (ch(pos) != ']'.code) {
                        if (ch(pos) == '-'.code && ch(pos + 1) == '-'.code) pos += 2
                        else error("invalid set operation in character class")
                        acc = setOp(acc, parseClassSetOperand(), CharRanges.OP_SUB)
                    }
                    acc.mayContainStrings = may
                }
            }
            isFirst = false
        }
        pos++
        depth--
        if (invert) {
            if (acc.mayContainStrings) error("negated character class may contain strings")
            return ClassSet(CharRanges.invert(acc.chars))
        }
        return acc
    }

    private fun parseClassSetOperand(): ClassSet {
        if (ch(pos) == '['.code) return parseClassV()
        val c = getClassAtom(inClass = true, allowClass = true)
        if (c == CLASS) return atomClass!!
        return ClassSet(maybeFold(CharRanges.single(c)))
    }

    private fun setOp(a: ClassSet, b: ClassSet, op: Int): ClassSet {
        val r = ClassSet(CharRanges.op(a.chars, b.chars, op))
        val sa = a.strings
        val sb = b.strings
        r.mayContainStrings = a.mayContainStrings
        r.strings = when (op) {
            CharRanges.OP_UNION -> if (sa == null) sb?.let { HashSet(it) } else if (sb == null) sa else sa.apply { addAll(sb) }
            CharRanges.OP_INTER -> if (sa == null || sb == null) null else sa.apply { retainAll(sb) }
            else -> if (sa == null || sb == null) sa else sa.apply { removeAll(sb) }
        }
        return r
    }

    /** Emits a matcher for a class value (alternatives longest first when it contains strings). */
    private fun emitClassSet(cs: ClassSet, backward: Boolean) {
        val strings = cs.strings
        if (strings.isNullOrEmpty()) {
            emitSingleSet(cs.chars, backward)
            return
        }
        val byLength = java.util.TreeMap<Int, HashSet<String>>(Comparator.reverseOrder())
        var hasEmpty = false
        for (s in strings) {
            val n = s.codePointCount(0, s.length)
            if (n == 0) hasEmpty = true else byLength.getOrPut(n) { HashSet() }.add(s)
        }
        val gotoSlots = ArrayList<Int>()
        var gi = 0
        for ((len, group) in byLength) {
            val isLast = !hasEmpty && cs.chars.isEmpty() && gi == byLength.size - 1
            val split = if (!isLast) emitJump(Op.SPLIT_NEXT_FIRST) else -1
            stringGroups.add(StringGroup(len, group, ignoreCase, backward))
            emit(Op.STRING_SET, stringGroups.size - 1)
            if (!isLast) {
                gotoSlots.add(emitJump(Op.GOTO))
                patch(split)
            }
            gi++
        }
        if (cs.chars.isNotEmpty()) {
            val isLast = !hasEmpty
            val split = if (!isLast) emitJump(Op.SPLIT_NEXT_FIRST) else -1
            emitSingleSet(cs.chars, backward)
            if (!isLast) patch(split)
        }
        for (g in gotoSlots) patch(g)
    }

    private fun emitSingleSet(set: IntArray, backward: Boolean) {
        if (backward) emit(Op.PREV)
        when {
            set.isEmpty() -> emit(Op.CHAR, -1) // never matches
            set.size == 2 && set[1] == set[0] + 1 -> emit(if (ignoreCase) Op.CHAR_I else Op.CHAR, set[0])
            else -> {
                val n = set.size / 2
                emit(if (ignoreCase) Op.RANGE_I else Op.RANGE, n)
                val bitmap = IntArray(4)
                var i = 0
                while (i < set.size && set[i] < 128) {
                    for (c in set[i] until minOf(set[i + 1], 128)) bitmap[c ushr 5] = bitmap[c ushr 5] or (1 shl (c and 31))
                    i += 2
                }
                for (w in bitmap) code.add(w)
                i = 0
                while (i < set.size) {
                    code.add(set[i])
                    code.add(set[i + 1] - 1)
                    i += 2
                }
            }
        }
        if (backward) emit(Op.PREV)
    }

    // ------------------------------------------------------------------ group names

    /** Parses a group name starting at [start] (after '<'); returns it and sets [escEnd] after the '>', or null. */
    private fun parseGroupName(start: Int): String? {
        var p = start
        val sb = StringBuilder()
        while (true) {
            var c = ch(p)
            if (c == '\\'.code) {
                p++
                if (ch(p) != 'u'.code) return null
                c = parseEscapeAt(p, 2)
                if (c < 0) return null
                p = escEnd
            } else if (c == '>'.code) {
                break
            } else if (c < 0) {
                return null
            } else {
                p++
                if (c in 0xD800..0xDBFF && p < end && src[p].isLowSurrogate()) {
                    c = Character.toCodePoint(c.toChar(), src[p])
                    p++
                }
            }
            if (sb.isEmpty()) {
                if (!isIdentStart(c)) return null
            } else if (!isIdentPart(c)) return null
            sb.appendCodePoint(c)
        }
        if (sb.isEmpty()) return null
        escEnd = p + 1
        return sb.toString()
    }

    /** Scans the whole pattern for capture groups (needed for forward references and `\N` disambiguation). */
    private fun prescan() {
        if (prescanned) return
        prescanned = true
        val savedEscEnd = escEnd
        prescanNames.add(null)
        var i = 0
        var classDepth = 0
        while (i < end) {
            when (src[i]) {
                '\\' -> i++
                '[' -> if (classDepth == 0 || unicodeSets) classDepth++
                ']' -> if (classDepth > 0) classDepth--
                '(' -> if (classDepth == 0) {
                    if (ch(i + 1) == '?'.code) {
                        if (ch(i + 2) == '<'.code && ch(i + 3) != '='.code && ch(i + 3) != '!'.code) {
                            prescanHasNames = true
                            prescanNames.add(parseGroupName(i + 3))
                        }
                    } else {
                        prescanNames.add(null)
                    }
                }
            }
            i++
        }
        escEnd = savedEscEnd
    }

    private fun hasNamedGroups(): Boolean {
        prescan()
        return prescanHasNames
    }

    /** Total number of capture groups in the pattern + 1. */
    private fun totalCaptureCount(): Int {
        prescan()
        return prescanNames.size
    }

    companion object {
        private const val CLASS = -2
        private const val INF = Int.MAX_VALUE
        private const val MAX_DEPTH = 1000
        private const val MAX_CAPTURES = 65535
        private const val MAX_REGISTERS = 65535

        private val DIGITS = CharRanges.of('0'.code, '9'.code)
        private val BASIC_WORD = CharRanges.Builder()
            .addRange('0'.code, '9'.code).addRange('A'.code, 'Z'.code).addRange('_'.code, '_'.code)
            .addRange('a'.code, 'z'.code).build()
        /** Characters whose simple case folding is a basic word character (U+017F, U+212A). */
        private val EXTRA_WORD: IntArray by lazy {
            val b = CharRanges.Builder()
            val m = UnicodeTables.simpleFolding
            for (i in m.keys.indices) {
                if (CharRanges.contains(BASIC_WORD, m.values[i]) && !CharRanges.contains(BASIC_WORD, m.keys[i])) b.add(m.keys[i])
            }
            b.build()
        }

        /** WhiteSpace and LineTerminator code points (`\s`). */
        @JvmField internal val spaceSet: IntArray = CharRanges.union(
            CharRanges.Builder().addRange(9, 13).addRange(0xA0, 0xA0).addRange(0x2028, 0x2029).addRange(0xFEFF, 0xFEFF).build(),
            UnicodeTables.generalCategory("Zs")!!,
        )

        private fun isDigit(c: Int) = c in '0'.code..'9'.code
        private fun isAsciiLetter(c: Int) = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code
        private fun isPropertyChar(c: Int) = isAsciiLetter(c) || isDigit(c) || c == '_'.code

        private fun hexVal(c: Int): Int = when (c) {
            in '0'.code..'9'.code -> c - '0'.code
            in 'a'.code..'f'.code -> c - 'a'.code + 10
            in 'A'.code..'F'.code -> c - 'A'.code + 10
            else -> -1
        }

        private fun isIdentStart(c: Int): Boolean =
            if (c < 128) isAsciiLetter(c) || c == '$'.code || c == '_'.code else UnicodeTables.isIdStart(c)

        private fun isIdentPart(c: Int): Boolean =
            if (c < 128) isAsciiLetter(c) || isDigit(c) || c == '$'.code || c == '_'.code
            else c == 0x200C || c == 0x200D || UnicodeTables.isIdContinue(c)

        /** Compiles [pattern] with the given flag bits; throws [RegExpSyntaxError]. */
        fun compile(pattern: String, flags: Int): RegExpProgram = RegExpCompiler(pattern, flags).compile()
    }
}
