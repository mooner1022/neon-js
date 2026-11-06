package dev.mooner.neonpython

/**
 * `_sre`, the engine of CPython's `re` (docs/PYTHON_PLATFORM.md §12, S3): re/_parser.py and re/_compiler.py are
 * CPython's, bundled; they compile a pattern to SRE code, which this runs as CPython's sre_lib.h does (its opcodes,
 * its marks and repeat contexts, its backtracking), on an explicit stack of contexts (no JVM recursion per
 * backtrack). Pattern, Match, Scanner and the sub templates as CPython 3.14's.
 */
object PySre {
    const val MAGIC = 20230612L
    const val CODESIZE = 4L
    const val MAXREPEAT = 4294967295L
    const val MAXGROUPS = 1073741823L

    // opcodes (re/_constants.py's OPCODES, in order)
    private const val FAILURE = 0
    private const val SUCCESS = 1
    private const val ANY = 2
    private const val ANY_ALL = 3
    private const val ASSERT = 4
    private const val ASSERT_NOT = 5
    private const val AT = 6
    private const val BRANCH = 7
    private const val CATEGORY = 8
    private const val CHARSET = 9
    private const val BIGCHARSET = 10
    private const val GROUPREF = 11
    private const val GROUPREF_EXISTS = 12
    private const val IN = 13
    private const val INFO = 14
    private const val JUMP = 15
    private const val LITERAL = 16
    private const val MARK = 17
    private const val MAX_UNTIL = 18
    private const val MIN_UNTIL = 19
    private const val NOT_LITERAL = 20
    private const val NEGATE = 21
    private const val RANGE = 22
    private const val REPEAT = 23
    private const val REPEAT_ONE = 24
    private const val SUBPATTERN = 25
    private const val MIN_REPEAT_ONE = 26
    private const val ATOMIC_GROUP = 27
    private const val POSSESSIVE_REPEAT = 28
    private const val POSSESSIVE_REPEAT_ONE = 29
    private const val GROUPREF_IGNORE = 30
    private const val IN_IGNORE = 31
    private const val LITERAL_IGNORE = 32
    private const val NOT_LITERAL_IGNORE = 33
    private const val GROUPREF_LOC_IGNORE = 34
    private const val IN_LOC_IGNORE = 35
    private const val LITERAL_LOC_IGNORE = 36
    private const val NOT_LITERAL_LOC_IGNORE = 37
    private const val GROUPREF_UNI_IGNORE = 38
    private const val IN_UNI_IGNORE = 39
    private const val LITERAL_UNI_IGNORE = 40
    private const val NOT_LITERAL_UNI_IGNORE = 41
    private const val RANGE_UNI_IGNORE = 42

    // AT codes
    private const val AT_BEGINNING = 0
    private const val AT_BEGINNING_LINE = 1
    private const val AT_BEGINNING_STRING = 2
    private const val AT_BOUNDARY = 3
    private const val AT_NON_BOUNDARY = 4
    private const val AT_END = 5
    private const val AT_END_LINE = 6
    private const val AT_END_STRING = 7
    private const val AT_LOC_BOUNDARY = 8
    private const val AT_LOC_NON_BOUNDARY = 9
    private const val AT_UNI_BOUNDARY = 10
    private const val AT_UNI_NON_BOUNDARY = 11

    private const val FLAG_IGNORECASE = 2L
    private const val FLAG_LOCALE = 4L
    private const val FLAG_MULTILINE = 8L
    private const val FLAG_DOTALL = 16L
    private const val FLAG_UNICODE = 32L
    private const val FLAG_VERBOSE = 64L
    private const val FLAG_DEBUG = 128L
    private const val FLAG_ASCII = 256L

    private val NO_ARGS = arrayOfNulls<Any?>(0)

    // ------------------------------------------------------------------ characters

    private fun isAsciiDigit(c: Int) = c in 48..57
    private fun isAsciiSpace(c: Int) = c == 32 || c in 9..13
    private fun isAsciiAlnum(c: Int) = c in 48..57 || c in 65..90 || c in 97..122
    private fun isWord(c: Int) = isAsciiAlnum(c) || c == '_'.code
    private fun isUniDecimal(c: Int) = Character.getType(c) == Character.DECIMAL_DIGIT_NUMBER.toInt()
    private fun isUniSpace(c: Int) = PyStrings.isSpace(c)
    private fun isUniAlnum(c: Int): Boolean = when (Character.getType(c).toByte()) {
        Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER, Character.OTHER_LETTER,
        Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
        else -> false
    }
    private fun isUniWord(c: Int) = isUniAlnum(c) || c == '_'.code
    private fun isUniLinebreak(c: Int) = c in 0x0a..0x0d || c in 0x1c..0x1e || c == 0x85 || c == 0x2028 || c == 0x2029

    private fun lowerAscii(c: Int) = if (c in 65..90) c + 32 else c
    private fun upperAscii(c: Int) = if (c in 97..122) c - 32 else c
    @JvmStatic fun lowerUnicode(c: Int): Int = Character.toLowerCase(c)
    @JvmStatic fun upperUnicode(c: Int): Int = Character.toUpperCase(c)

    private fun category(code: Int, c: Int): Boolean = when (code) {
        0 -> isAsciiDigit(c)
        1 -> !isAsciiDigit(c)
        2 -> isAsciiSpace(c)
        3 -> !isAsciiSpace(c)
        4 -> isWord(c)
        5 -> !isWord(c)
        6 -> c == 10
        7 -> c != 10
        8 -> isWord(c)
        9 -> !isWord(c)
        10 -> isUniDecimal(c)
        11 -> !isUniDecimal(c)
        12 -> isUniSpace(c)
        13 -> !isUniSpace(c)
        14 -> isUniWord(c)
        15 -> !isUniWord(c)
        16 -> isUniLinebreak(c)
        17 -> !isUniLinebreak(c)
        else -> false
    }

    // ------------------------------------------------------------------ subjects

    /** What a pattern runs over: a str's code points or a bytes-like object's bytes. */
    abstract class Subject(@JvmField val length: Int) {
        abstract fun at(i: Int): Int
        abstract fun slice(a: Int, b: Int): Any
    }

    private class BmpSubject(val s: String) : Subject(s.length) {
        override fun at(i: Int) = s[i].code
        override fun slice(a: Int, b: Int): Any = PyStr(s.substring(a, b))
    }

    private class CpSubject(val cps: IntArray) : Subject(cps.size) {
        override fun at(i: Int) = cps[i]
        override fun slice(a: Int, b: Int): Any = PyStr(String(cps, a, b - a))
    }

    private class BytesSubject(val b: ByteArray) : Subject(b.size) {
        override fun at(i: Int) = b[i].toInt() and 0xFF
        override fun slice(a: Int, b: Int): Any = PyBytes(this.b.copyOfRange(a, b))
    }

    private fun subject(v: Any?, pattern: PyPattern): Subject {
        val s = (v as? PyStr) ?: (v as? PyNumSub)?.num as? PyStr
        if (s != null) {
            if (pattern.isBytes) throw PyRt.typeError("cannot use a bytes pattern on a string-like object")
            return if (s.bmp) BmpSubject(s.s) else CpSubject(s.s.codePoints().toArray())
        }
        val b = PyByteSeqs.content(v) ?: throw PyRt.typeError("expected string or bytes-like object, got '${PyTypes.name(v)}'")
        if (!pattern.isBytes) throw PyRt.typeError("cannot use a string pattern on a bytes-like object")
        return BytesSubject(b)
    }

    // ------------------------------------------------------------------ the state

    private class Repeat(@JvmField val pc: Int, @JvmField val prev: Repeat?) {
        @JvmField var count = -1
        @JvmField var lastPtr = -1
    }

    class State(@JvmField val str: Subject, @JvmField var start: Int, @JvmField val end: Int, @JvmField val code: IntArray) {
        @JvmField var ptr = start
        @JvmField var marks = IntArray(16) { -1 }
        @JvmField var lastmark = -1
        @JvmField var lastindex = -1
        @JvmField internal var repeat: Any? = null
        @JvmField var matchAll = false
        @JvmField var mustAdvance = false
        @JvmField val pos = start
        @JvmField val endpos = end

        fun reset() {
            lastmark = -1
            lastindex = -1
            repeat = null
            java.util.Arrays.fill(marks, -1)
        }

        fun mark(i: Int): Int = if (i < marks.size) marks[i] else -1

        fun setMark(i: Int, v: Int) {
            if (i >= marks.size) marks = marks.copyOf(maxOf(i + 1, marks.size * 2)).also { java.util.Arrays.fill(it, marks.size, it.size, -1) }
            marks[i] = v
        }
    }

    // ------------------------------------------------------------------ matching

    private fun at(st: State, ptr: Int, code: Int): Boolean {
        val s = st.str
        return when (code) {
            AT_BEGINNING, AT_BEGINNING_STRING -> ptr == 0
            AT_BEGINNING_LINE -> ptr == 0 || s.at(ptr - 1) == 10
            AT_END -> (st.end - ptr == 1 && s.at(ptr) == 10) || ptr == st.end
            AT_END_LINE -> ptr == st.end || s.at(ptr) == 10
            AT_END_STRING -> ptr == st.end
            AT_BOUNDARY, AT_LOC_BOUNDARY -> (ptr > 0 && isWord(s.at(ptr - 1))) != (ptr < st.end && isWord(s.at(ptr)))
            AT_NON_BOUNDARY, AT_LOC_NON_BOUNDARY -> (ptr > 0 && isWord(s.at(ptr - 1))) == (ptr < st.end && isWord(s.at(ptr)))
            AT_UNI_BOUNDARY -> (ptr > 0 && isUniWord(s.at(ptr - 1))) != (ptr < st.end && isUniWord(s.at(ptr)))
            AT_UNI_NON_BOUNDARY -> (ptr > 0 && isUniWord(s.at(ptr - 1))) == (ptr < st.end && isUniWord(s.at(ptr)))
            else -> false
        }
    }

    /** Whether [ch] is in the set at [pc] (CPython's SRE(charset)). */
    private fun charset(code: IntArray, pc0: Int, ch: Int): Boolean {
        var pc = pc0
        var ok = true
        while (true) {
            when (code[pc++]) {
                FAILURE -> return !ok
                LITERAL -> { if (ch == code[pc]) return ok; pc++ }
                CATEGORY -> { if (category(code[pc], ch)) return ok; pc++ }
                CHARSET -> {
                    if (ch in 0..255 && (code[pc + (ch ushr 5)] ushr (ch and 31)) and 1 != 0) return ok
                    pc += 8
                }
                RANGE -> { if (code[pc] <= ch && ch <= code[pc + 1]) return ok; pc += 2 }
                RANGE_UNI_IGNORE -> {
                    if (code[pc] <= ch && ch <= code[pc + 1]) return ok
                    val u = upperUnicode(ch)
                    if (code[pc] <= u && u <= code[pc + 1]) return ok
                    pc += 2
                }
                NEGATE -> ok = !ok
                BIGCHARSET -> {
                    val count = code[pc++]
                    val block = if (ch in 0..0xFFFF) (code[pc + ((ch ushr 8) ushr 2)] ushr (((ch ushr 8) and 3) * 8)) and 0xFF else -1
                    pc += 64
                    if (block >= 0) {
                        val bit = block * 256 + (ch and 255)
                        if ((code[pc + (bit ushr 5)] ushr (bit and 31)) and 1 != 0) return ok
                    }
                    pc += count * 8
                }
                else -> throw PyException("RuntimeError", "internal error in regular expression engine")
            }
        }
    }

    private fun lowerLocale(c: Int) = if (c < 128) lowerAscii(c) else c
    private fun upperLocale(c: Int) = if (c < 128) upperAscii(c) else c
    private fun charLocIgnore(pattern: Int, ch: Int) = ch == pattern || lowerLocale(ch) == pattern || upperLocale(ch) == pattern

    private fun charsetLocIgnore(code: IntArray, pc: Int, ch: Int): Boolean {
        val lo = lowerLocale(ch)
        if (charset(code, pc, lo)) return true
        val up = upperLocale(ch)
        return up != lo && charset(code, pc, up)
    }

    /** How many times the one-character item at [pc] matches from st.ptr, at most [max] (CPython's SRE(count)). */
    private fun count(st: State, pc: Int, max: Long): Int {
        val code = st.code
        val s = st.str
        val start = st.ptr
        var end = st.end
        if (max != MAXREPEAT && max < end - start) end = start + max.toInt()
        var ptr = start
        when (code[pc]) {
            IN -> while (ptr < end && charset(code, pc + 2, s.at(ptr))) ptr++
            ANY -> while (ptr < end && s.at(ptr) != 10) ptr++
            ANY_ALL -> ptr = end
            LITERAL -> { val c = code[pc + 1]; while (ptr < end && s.at(ptr) == c) ptr++ }
            LITERAL_IGNORE -> { val c = code[pc + 1]; while (ptr < end && lowerAscii(s.at(ptr)) == c) ptr++ }
            LITERAL_UNI_IGNORE -> { val c = code[pc + 1]; while (ptr < end && lowerUnicode(s.at(ptr)) == c) ptr++ }
            LITERAL_LOC_IGNORE -> { val c = code[pc + 1]; while (ptr < end && charLocIgnore(c, s.at(ptr))) ptr++ }
            NOT_LITERAL -> { val c = code[pc + 1]; while (ptr < end && s.at(ptr) != c) ptr++ }
            NOT_LITERAL_IGNORE -> { val c = code[pc + 1]; while (ptr < end && lowerAscii(s.at(ptr)) != c) ptr++ }
            NOT_LITERAL_UNI_IGNORE -> { val c = code[pc + 1]; while (ptr < end && lowerUnicode(s.at(ptr)) != c) ptr++ }
            NOT_LITERAL_LOC_IGNORE -> { val c = code[pc + 1]; while (ptr < end && !charLocIgnore(c, s.at(ptr))) ptr++ }
            else -> {
                // (another one-character item: matched, as CPython does, one at a time)
                while (st.ptr < end) {
                    val p = st.ptr
                    if (!run(st, pc, false)) {
                        st.ptr = p
                        break
                    }
                    if (st.ptr == p) break
                }
                val n = st.ptr - start
                st.ptr = start
                return n
            }
        }
        return ptr - start
    }

    /** A context of the matcher: CPython's SRE(match_context). */
    private class Ctx(@JvmField var pc: Int, @JvmField var ptr: Int, @JvmField val toplevel: Boolean) {
        @JvmField var jump = 0
        @JvmField var count = 0
        @JvmField var lastmark = -1
        @JvmField var lastindex = -1
        @JvmField var rep: Repeat? = null
        @JvmField var chr = 0
        @JvmField var base = 0
        @JvmField var alt = 0
        @JvmField var marks: IntArray? = null
        @JvmField var savedLastPtr = -1
    }

    private const val A_DISPATCH = 0
    private const val A_PUSH = 1
    private const val A_SUCCESS = 2
    private const val A_FAILURE = 3

    private const val J_BRANCH = 1
    private const val J_REPEAT_ONE_1 = 2
    private const val J_REPEAT_ONE_2 = 3
    private const val J_MIN_REPEAT_ONE = 4
    private const val J_REPEAT = 5
    private const val J_MAX_UNTIL_1 = 6
    private const val J_MAX_UNTIL_2 = 7
    private const val J_MAX_UNTIL_3 = 8
    private const val J_MIN_UNTIL_1 = 9
    private const val J_MIN_UNTIL_2 = 10
    private const val J_MIN_UNTIL_3 = 11
    private const val J_ASSERT = 12
    private const val J_ASSERT_NOT = 13
    private const val J_POSS_REPEAT_1 = 14
    private const val J_POSS_REPEAT_2 = 15
    private const val J_ATOMIC_GROUP = 16

    private class Machine(val st: State) {
        val code = st.code
        val stack = ArrayList<Ctx>()
        var child: Ctx? = null
        var repeatOfTail: Repeat? = null

        fun markPush(ctx: Ctx) {
            ctx.marks = if (ctx.lastmark >= 0) st.marks.copyOf(ctx.lastmark + 1) else IntArray(0)
        }

        fun markRestore(ctx: Ctx) {
            val m = ctx.marks ?: return
            for (i in m.indices) st.setMark(i, m[i])
        }

        fun lastmarkSave(ctx: Ctx) {
            ctx.lastmark = st.lastmark
            ctx.lastindex = st.lastindex
        }

        fun lastmarkRestore(ctx: Ctx) {
            st.lastmark = ctx.lastmark
            st.lastindex = ctx.lastindex
        }

        fun push(ctx: Ctx, jump: Int, pc: Int, toplevel: Boolean): Int {
            ctx.jump = jump
            child = Ctx(pc, st.ptr, toplevel)
            return A_PUSH
        }

        val repeatState: Repeat? get() = st.repeat as Repeat?

        fun run(pc: Int, toplevel: Boolean): Boolean {
            var ctx = Ctx(pc, st.ptr, toplevel)
            var action = enter(ctx)
            while (true) {
                when (action) {
                    A_DISPATCH -> action = dispatch(ctx)
                    A_PUSH -> {
                        stack.add(ctx)
                        ctx = child!!
                        child = null
                        action = enter(ctx)
                    }
                    else -> {
                        val ok = action == A_SUCCESS
                        if (stack.isEmpty()) return ok
                        ctx = stack.removeAt(stack.size - 1)
                        action = resume(ctx, ok)
                    }
                }
            }
        }

        fun enter(ctx: Ctx): Int {
            if (code[ctx.pc] == INFO) {
                // (<INFO> <skip> <flags> <min> ...: fewer characters left than the least it matches)
                val min = code[ctx.pc + 3].toLong() and 0xFFFFFFFFL
                if (min != 0L && (st.end - ctx.ptr).toLong() < min) return A_FAILURE
                ctx.pc += code[ctx.pc + 1] + 1
            }
            return A_DISPATCH
        }

        fun dispatch(ctx: Ctx): Int {
            val s = st.str
            val end = st.end
            while (true) {
                val op = code[ctx.pc++]
                when (op) {
                    MARK -> {
                        val i = code[ctx.pc]
                        if (i and 1 != 0) st.lastindex = i / 2 + 1
                        if (i > st.lastmark) {
                            var j = st.lastmark + 1
                            while (j < i) st.setMark(j++, -1)
                            st.lastmark = i
                        }
                        st.setMark(i, ctx.ptr)
                        ctx.pc++
                    }
                    LITERAL -> { if (ctx.ptr >= end || s.at(ctx.ptr) != code[ctx.pc]) return A_FAILURE; ctx.pc++; ctx.ptr++ }
                    NOT_LITERAL -> { if (ctx.ptr >= end || s.at(ctx.ptr) == code[ctx.pc]) return A_FAILURE; ctx.pc++; ctx.ptr++ }
                    SUCCESS -> {
                        if (ctx.toplevel && ((st.matchAll && ctx.ptr != st.end) || (st.mustAdvance && ctx.ptr == st.start))) return A_FAILURE
                        st.ptr = ctx.ptr
                        return A_SUCCESS
                    }
                    AT -> { if (!at(st, ctx.ptr, code[ctx.pc])) return A_FAILURE; ctx.pc++ }
                    CATEGORY -> { if (ctx.ptr >= end || !category(code[ctx.pc], s.at(ctx.ptr))) return A_FAILURE; ctx.pc++; ctx.ptr++ }
                    ANY -> { if (ctx.ptr >= end || s.at(ctx.ptr) == 10) return A_FAILURE; ctx.ptr++ }
                    ANY_ALL -> { if (ctx.ptr >= end) return A_FAILURE; ctx.ptr++ }
                    IN -> { if (ctx.ptr >= end || !charset(code, ctx.pc + 1, s.at(ctx.ptr))) return A_FAILURE; ctx.pc += code[ctx.pc]; ctx.ptr++ }
                    LITERAL_IGNORE -> { if (ctx.ptr >= end || lowerAscii(s.at(ctx.ptr)) != code[ctx.pc]) return A_FAILURE; ctx.pc++; ctx.ptr++ }
                    LITERAL_UNI_IGNORE -> { if (ctx.ptr >= end || lowerUnicode(s.at(ctx.ptr)) != code[ctx.pc]) return A_FAILURE; ctx.pc++; ctx.ptr++ }
                    LITERAL_LOC_IGNORE -> { if (ctx.ptr >= end || !charLocIgnore(code[ctx.pc], s.at(ctx.ptr))) return A_FAILURE; ctx.pc++; ctx.ptr++ }
                    NOT_LITERAL_IGNORE -> { if (ctx.ptr >= end || lowerAscii(s.at(ctx.ptr)) == code[ctx.pc]) return A_FAILURE; ctx.pc++; ctx.ptr++ }
                    NOT_LITERAL_UNI_IGNORE -> { if (ctx.ptr >= end || lowerUnicode(s.at(ctx.ptr)) == code[ctx.pc]) return A_FAILURE; ctx.pc++; ctx.ptr++ }
                    NOT_LITERAL_LOC_IGNORE -> { if (ctx.ptr >= end || charLocIgnore(code[ctx.pc], s.at(ctx.ptr))) return A_FAILURE; ctx.pc++; ctx.ptr++ }
                    IN_IGNORE -> { if (ctx.ptr >= end || !charset(code, ctx.pc + 1, lowerAscii(s.at(ctx.ptr)))) return A_FAILURE; ctx.pc += code[ctx.pc]; ctx.ptr++ }
                    IN_UNI_IGNORE -> { if (ctx.ptr >= end || !charset(code, ctx.pc + 1, lowerUnicode(s.at(ctx.ptr)))) return A_FAILURE; ctx.pc += code[ctx.pc]; ctx.ptr++ }
                    IN_LOC_IGNORE -> { if (ctx.ptr >= end || !charsetLocIgnore(code, ctx.pc + 1, s.at(ctx.ptr))) return A_FAILURE; ctx.pc += code[ctx.pc]; ctx.ptr++ }
                    JUMP, INFO -> ctx.pc += code[ctx.pc]
                    BRANCH -> {
                        lastmarkSave(ctx)
                        if (st.repeat != null) markPush(ctx)
                        ctx.alt = ctx.pc
                        return branchNext(ctx)
                    }
                    REPEAT_ONE -> {
                        val b = ctx.pc
                        ctx.base = b
                        val min = code[b + 1].toLong() and 0xFFFFFFFFL
                        if (min > end - ctx.ptr) return A_FAILURE
                        st.ptr = ctx.ptr
                        val n = count(st, b + 3, code[b + 2].toLong() and 0xFFFFFFFFL)
                        ctx.count = n
                        ctx.ptr += n
                        if (n < min) return A_FAILURE
                        val tail = b + code[b]
                        if (code[tail] == SUCCESS && ctx.ptr == st.end && !(ctx.toplevel && st.mustAdvance && ctx.ptr == st.start)) {
                            st.ptr = ctx.ptr
                            return A_SUCCESS
                        }
                        lastmarkSave(ctx)
                        if (st.repeat != null) markPush(ctx)
                        if (code[tail] == LITERAL) {
                            ctx.chr = code[tail + 1]
                            return repeatOneLiteral(ctx)
                        }
                        return repeatOneGeneral(ctx)
                    }
                    MIN_REPEAT_ONE -> {
                        val b = ctx.pc
                        ctx.base = b
                        val min = code[b + 1].toLong() and 0xFFFFFFFFL
                        if (min > end - ctx.ptr) return A_FAILURE
                        st.ptr = ctx.ptr
                        if (min == 0L) ctx.count = 0 else {
                            val n = count(st, b + 3, min)
                            if (n < min) return A_FAILURE
                            ctx.count = n
                            ctx.ptr += n
                        }
                        val tail = b + code[b]
                        if (code[tail] == SUCCESS && !(ctx.toplevel && ((st.matchAll && ctx.ptr != st.end) || (st.mustAdvance && ctx.ptr == st.start)))) {
                            st.ptr = ctx.ptr
                            return A_SUCCESS
                        }
                        lastmarkSave(ctx)
                        if (st.repeat != null) markPush(ctx)
                        return minRepeatOneNext(ctx)
                    }
                    POSSESSIVE_REPEAT_ONE -> {
                        val b = ctx.pc
                        val min = code[b + 1].toLong() and 0xFFFFFFFFL
                        if (min > end - ctx.ptr) return A_FAILURE
                        st.ptr = ctx.ptr
                        val n = count(st, b + 3, code[b + 2].toLong() and 0xFFFFFFFFL)
                        ctx.count = n
                        ctx.ptr += n
                        if (n < min) return A_FAILURE
                        ctx.pc = b + code[b]
                    }
                    REPEAT -> {
                        val rep = Repeat(ctx.pc, repeatState)
                        ctx.rep = rep
                        st.repeat = rep
                        st.ptr = ctx.ptr
                        return push(ctx, J_REPEAT, ctx.pc + code[ctx.pc], ctx.toplevel)
                    }
                    MAX_UNTIL -> {
                        val rep = repeatState ?: throw PyException("RuntimeError", "internal error in regular expression engine")
                        ctx.rep = rep
                        st.ptr = ctx.ptr
                        ctx.count = rep.count + 1
                        val min = code[rep.pc + 1].toLong() and 0xFFFFFFFFL
                        val max = code[rep.pc + 2].toLong() and 0xFFFFFFFFL
                        if (ctx.count < min) {
                            rep.count = ctx.count
                            return push(ctx, J_MAX_UNTIL_1, rep.pc + 3, ctx.toplevel)
                        }
                        if ((ctx.count < max || max == MAXREPEAT) && st.ptr != rep.lastPtr) {
                            rep.count = ctx.count
                            lastmarkSave(ctx)
                            markPush(ctx)
                            ctx.savedLastPtr = rep.lastPtr
                            rep.lastPtr = st.ptr
                            return push(ctx, J_MAX_UNTIL_2, rep.pc + 3, ctx.toplevel)
                        }
                        st.repeat = rep.prev
                        return push(ctx, J_MAX_UNTIL_3, ctx.pc, ctx.toplevel)
                    }
                    MIN_UNTIL -> {
                        val rep = repeatState ?: throw PyException("RuntimeError", "internal error in regular expression engine")
                        ctx.rep = rep
                        st.ptr = ctx.ptr
                        ctx.count = rep.count + 1
                        val min = code[rep.pc + 1].toLong() and 0xFFFFFFFFL
                        if (ctx.count < min) {
                            rep.count = ctx.count
                            return push(ctx, J_MIN_UNTIL_1, rep.pc + 3, ctx.toplevel)
                        }
                        st.repeat = rep.prev
                        lastmarkSave(ctx)
                        if (st.repeat != null) markPush(ctx)
                        return push(ctx, J_MIN_UNTIL_2, ctx.pc, ctx.toplevel)
                    }
                    POSSESSIVE_REPEAT -> {
                        ctx.base = ctx.pc
                        st.ptr = ctx.ptr
                        val rep = Repeat(-1, repeatState)
                        ctx.rep = rep
                        st.repeat = rep
                        ctx.count = 0
                        return possRepeatMin(ctx)
                    }
                    ATOMIC_GROUP -> {
                        ctx.base = ctx.pc
                        st.ptr = ctx.ptr
                        return push(ctx, J_ATOMIC_GROUP, ctx.pc + 1, false)
                    }
                    GROUPREF, GROUPREF_IGNORE, GROUPREF_UNI_IGNORE, GROUPREF_LOC_IGNORE -> {
                        val g = code[ctx.pc] * 2
                        if (g >= st.lastmark) return A_FAILURE
                        var p = st.mark(g)
                        val e = st.mark(g + 1)
                        if (p < 0 || e < 0 || e < p) return A_FAILURE
                        while (p < e) {
                            if (ctx.ptr >= end) return A_FAILURE
                            val a = s.at(ctx.ptr)
                            val b = s.at(p)
                            val same = when (op) {
                                GROUPREF -> a == b
                                GROUPREF_IGNORE -> lowerAscii(a) == lowerAscii(b)
                                GROUPREF_UNI_IGNORE -> lowerUnicode(a) == lowerUnicode(b)
                                else -> lowerLocale(a) == lowerLocale(b)
                            }
                            if (!same) return A_FAILURE
                            p++
                            ctx.ptr++
                        }
                        ctx.pc++
                    }
                    GROUPREF_EXISTS -> {
                        val g = code[ctx.pc] * 2
                        val matched = g < st.lastmark && st.mark(g) >= 0 && st.mark(g + 1) >= 0 && st.mark(g) <= st.mark(g + 1)
                        ctx.pc += if (matched) 2 else code[ctx.pc + 1]
                    }
                    ASSERT -> {
                        val back = code[ctx.pc + 1]
                        if (ctx.ptr < back) return A_FAILURE
                        st.ptr = ctx.ptr - back
                        return push(ctx, J_ASSERT, ctx.pc + 2, false)
                    }
                    ASSERT_NOT -> {
                        val back = code[ctx.pc + 1]
                        if (ctx.ptr >= back) {
                            st.ptr = ctx.ptr - back
                            lastmarkSave(ctx)
                            if (st.repeat != null) markPush(ctx)
                            return push(ctx, J_ASSERT_NOT, ctx.pc + 2, false)
                        }
                        ctx.pc += code[ctx.pc]
                    }
                    FAILURE -> return A_FAILURE
                    else -> throw PyException("RuntimeError", "internal error in regular expression engine")
                }
            }
        }

        fun branchNext(ctx: Ctx): Int {
            val s = st.str
            while (code[ctx.alt] != 0) {
                val a = ctx.alt
                if (code[a + 1] == LITERAL && (ctx.ptr >= st.end || s.at(ctx.ptr) != code[a + 2])) { ctx.alt += code[a]; continue }
                if (code[a + 1] == IN && (ctx.ptr >= st.end || !charset(code, a + 3, s.at(ctx.ptr)))) { ctx.alt += code[a]; continue }
                st.ptr = ctx.ptr
                return push(ctx, J_BRANCH, a + 1, ctx.toplevel)
            }
            ctx.marks = null
            return A_FAILURE
        }

        fun repeatOneLiteral(ctx: Ctx): Int {
            val min = code[ctx.base + 1].toLong() and 0xFFFFFFFFL
            val s = st.str
            while (ctx.count >= min && (ctx.ptr >= st.end || s.at(ctx.ptr) != ctx.chr)) {
                ctx.ptr--
                ctx.count--
            }
            if (ctx.count < min) {
                ctx.marks = null
                return A_FAILURE
            }
            st.ptr = ctx.ptr
            return push(ctx, J_REPEAT_ONE_1, ctx.base + code[ctx.base], ctx.toplevel)
        }

        fun repeatOneGeneral(ctx: Ctx): Int {
            val min = code[ctx.base + 1].toLong() and 0xFFFFFFFFL
            if (ctx.count >= min) {
                st.ptr = ctx.ptr
                return push(ctx, J_REPEAT_ONE_2, ctx.base + code[ctx.base], ctx.toplevel)
            }
            ctx.marks = null
            return A_FAILURE
        }

        fun minRepeatOneNext(ctx: Ctx): Int {
            val max = code[ctx.base + 2].toLong() and 0xFFFFFFFFL
            if (max == MAXREPEAT || ctx.count <= max) {
                st.ptr = ctx.ptr
                return push(ctx, J_MIN_REPEAT_ONE, ctx.base + code[ctx.base], ctx.toplevel)
            }
            ctx.marks = null
            return A_FAILURE
        }

        fun possRepeatMin(ctx: Ctx): Int {
            val min = code[ctx.base + 1].toLong() and 0xFFFFFFFFL
            if (ctx.count < min) return push(ctx, J_POSS_REPEAT_1, ctx.base + 3, false)
            // (the context's position cleared: the loop below runs at least once)
            ctx.ptr = -1
            return possRepeatMore(ctx)
        }

        fun possRepeatMore(ctx: Ctx): Int {
            val max = code[ctx.base + 2].toLong() and 0xFFFFFFFFL
            if ((ctx.count < max || max == MAXREPEAT) && st.ptr != ctx.ptr) {
                lastmarkSave(ctx)
                markPush(ctx)
                ctx.ptr = st.ptr
                return push(ctx, J_POSS_REPEAT_2, ctx.base + 3, false)
            }
            return possRepeatDone(ctx)
        }

        fun possRepeatDone(ctx: Ctx): Int {
            st.repeat = ctx.rep!!.prev
            ctx.pc = ctx.base + code[ctx.base] + 1
            ctx.ptr = st.ptr
            return A_DISPATCH
        }

        fun resume(ctx: Ctx, ok: Boolean): Int {
            when (ctx.jump) {
                J_BRANCH -> {
                    if (ok) {
                        ctx.marks = null
                        return A_SUCCESS
                    }
                    if (st.repeat != null) markRestore(ctx)
                    lastmarkRestore(ctx)
                    ctx.alt += code[ctx.alt]
                    return branchNext(ctx)
                }
                J_REPEAT_ONE_1 -> {
                    if (ok) {
                        ctx.marks = null
                        return A_SUCCESS
                    }
                    if (st.repeat != null) markRestore(ctx)
                    lastmarkRestore(ctx)
                    ctx.ptr--
                    ctx.count--
                    return repeatOneLiteral(ctx)
                }
                J_REPEAT_ONE_2 -> {
                    if (ok) {
                        ctx.marks = null
                        return A_SUCCESS
                    }
                    if (st.repeat != null) markRestore(ctx)
                    lastmarkRestore(ctx)
                    ctx.ptr--
                    ctx.count--
                    return repeatOneGeneral(ctx)
                }
                J_MIN_REPEAT_ONE -> {
                    if (ok) {
                        ctx.marks = null
                        return A_SUCCESS
                    }
                    if (st.repeat != null) markRestore(ctx)
                    lastmarkRestore(ctx)
                    st.ptr = ctx.ptr
                    val n = count(st, ctx.base + 3, 1)
                    if (n == 0) {
                        ctx.marks = null
                        return A_FAILURE
                    }
                    ctx.ptr++
                    ctx.count++
                    return minRepeatOneNext(ctx)
                }
                J_REPEAT -> {
                    st.repeat = ctx.rep!!.prev
                    return if (ok) A_SUCCESS else A_FAILURE
                }
                J_MAX_UNTIL_1 -> {
                    if (ok) return A_SUCCESS
                    ctx.rep!!.count = ctx.count - 1
                    st.ptr = ctx.ptr
                    return A_FAILURE
                }
                J_MAX_UNTIL_2 -> {
                    val rep = ctx.rep!!
                    rep.lastPtr = ctx.savedLastPtr
                    if (ok) {
                        ctx.marks = null
                        return A_SUCCESS
                    }
                    markRestore(ctx)
                    ctx.marks = null
                    lastmarkRestore(ctx)
                    rep.count = ctx.count - 1
                    st.ptr = ctx.ptr
                    st.repeat = rep.prev
                    return push(ctx, J_MAX_UNTIL_3, ctx.pc, ctx.toplevel)
                }
                J_MAX_UNTIL_3 -> {
                    st.repeat = ctx.rep
                    if (ok) return A_SUCCESS
                    st.ptr = ctx.ptr
                    return A_FAILURE
                }
                J_MIN_UNTIL_1 -> {
                    if (ok) return A_SUCCESS
                    ctx.rep!!.count = ctx.count - 1
                    st.ptr = ctx.ptr
                    return A_FAILURE
                }
                J_MIN_UNTIL_2 -> {
                    val rep = ctx.rep!!
                    val tailRepeat = st.repeat
                    st.repeat = rep
                    if (ok) {
                        ctx.marks = null
                        return A_SUCCESS
                    }
                    if (tailRepeat != null) markRestore(ctx)
                    ctx.marks = null
                    lastmarkRestore(ctx)
                    st.ptr = ctx.ptr
                    val max = code[rep.pc + 2].toLong() and 0xFFFFFFFFL
                    if ((ctx.count >= max && max != MAXREPEAT) || st.ptr == rep.lastPtr) return A_FAILURE
                    rep.count = ctx.count
                    ctx.savedLastPtr = rep.lastPtr
                    rep.lastPtr = st.ptr
                    return push(ctx, J_MIN_UNTIL_3, rep.pc + 3, ctx.toplevel)
                }
                J_MIN_UNTIL_3 -> {
                    val rep = ctx.rep!!
                    rep.lastPtr = ctx.savedLastPtr
                    if (ok) return A_SUCCESS
                    rep.count = ctx.count - 1
                    st.ptr = ctx.ptr
                    return A_FAILURE
                }
                J_ASSERT -> {
                    if (!ok) return A_FAILURE
                    ctx.pc += code[ctx.pc]
                    return A_DISPATCH
                }
                J_ASSERT_NOT -> {
                    if (ok) {
                        ctx.marks = null
                        return A_FAILURE
                    }
                    if (st.repeat != null) markRestore(ctx)
                    ctx.marks = null
                    lastmarkRestore(ctx)
                    ctx.pc += code[ctx.pc]
                    return A_DISPATCH
                }
                J_POSS_REPEAT_1 -> {
                    if (ok) {
                        ctx.count++
                        return possRepeatMin(ctx)
                    }
                    st.ptr = ctx.ptr
                    st.repeat = ctx.rep!!.prev
                    return A_FAILURE
                }
                J_POSS_REPEAT_2 -> {
                    if (ok) {
                        ctx.marks = null
                        ctx.count++
                        return possRepeatMore(ctx)
                    }
                    markRestore(ctx)
                    ctx.marks = null
                    lastmarkRestore(ctx)
                    // (the position before the last try: that one did not match)
                    st.ptr = ctx.ptr
                    return possRepeatDone(ctx)
                }
                J_ATOMIC_GROUP -> {
                    if (!ok) {
                        st.ptr = ctx.ptr
                        return A_FAILURE
                    }
                    ctx.pc = ctx.base + code[ctx.base]
                    ctx.ptr = st.ptr
                    return A_DISPATCH
                }
            }
            throw IllegalStateException("sre: jump ${ctx.jump}")
        }
    }

    /** SRE(match) from st.ptr with the code at [pc]. */
    private fun run(st: State, pc: Int, toplevel: Boolean): Boolean = Machine(st).run(pc, toplevel)

    /** pattern.match: at st.start. */
    @JvmStatic
    fun match(st: State): Boolean {
        if (st.start > st.end) return false
        st.ptr = st.start
        return run(st, 0, true)
    }

    /** pattern.search: the first position from st.start a match begins at (CPython's SRE(search), its general case). */
    @JvmStatic
    fun search(st: State): Boolean {
        var ptr = st.start
        var end = st.end
        if (ptr > end) return false
        val code = st.code
        var pc = 0
        if (code[0] == INFO) {
            val min = code[3].toLong() and 0xFFFFFFFFL
            if (min != 0L && (end - ptr).toLong() < min) return false
            if (min > 1) {
                end -= (min - 1).toInt()
                if (end <= ptr) end = ptr
            }
            pc = code[1] + 1
        }
        st.start = ptr
        st.ptr = ptr
        var ok = Machine(st).run(pc, true)
        st.mustAdvance = false
        if (!ok && code[pc] == AT && (code[pc + 1] == AT_BEGINNING || code[pc + 1] == AT_BEGINNING_STRING)) {
            st.start = end
            st.ptr = end
            return false
        }
        while (!ok && ptr < end) {
            ptr++
            st.lastmark = -1
            st.lastindex = -1
            st.start = ptr
            st.ptr = ptr
            ok = Machine(st).run(pc, false)
        }
        return ok
    }

    // ------------------------------------------------------------------ the objects

    /** A compiled pattern: CPython's _sre.SRE_Pattern. */
    class PyPattern(type: PyType, @JvmField val pattern: Any?, @JvmField val flags: Long, @JvmField val code: IntArray,
                    @JvmField val groups: Int, @JvmField val groupindex: PyDict, @JvmField val indexgroup: Any?) : PyObject(type) {
        @JvmField val isBytes = pattern != null && pattern !is PyStr && PyByteSeqs.isBytesLike(pattern)
    }

    /** A match: its pattern and string, the span it searched, its groups' spans (-1 unmatched). */
    class PyMatch(type: PyType, @JvmField val pattern: PyPattern, @JvmField val string: Any?, @JvmField val subject: Subject,
                  @JvmField val pos: Int, @JvmField val endpos: Int, @JvmField val spans: IntArray, @JvmField val lastindex: Int) : PyObject(type)

    /** pattern.scanner(string): its state, kept from one search to the next. */
    class PyScanner(type: PyType, @JvmField val pattern: PyPattern, @JvmField val string: Any?, @JvmField val state: State) : PyObject(type) {
        @JvmField var done = false
    }

    /** _sre.template(pattern, list): literal chunks (str or bytes, or None) and group indices, alternating. */
    class PyTemplate(type: PyType, @JvmField val literals: Array<Any?>, @JvmField val groupIndices: IntArray) : PyObject(type)

    private fun t(name: String, sub: Boolean = false): PyType = PyTypes.newBuiltin(name, subclassable = sub).also {
        it.module = "re"
        it.tpName = "re.$name"
        it.dispatchBuiltins = true
    }

    @JvmField val PATTERN = t("Pattern")
    @JvmField val MATCH = t("Match")
    @JvmField val SCANNER = PyTypes.newBuiltin("SRE_Scanner", subclassable = false).also { it.module = "_sre"; it.tpName = "_sre.SRE_Scanner"; it.dispatchBuiltins = true }
    @JvmField val TEMPLATE = PyTypes.newBuiltin("SRE_Template", subclassable = false).also { it.module = "_sre"; it.tpName = "_sre.SRE_Template"; it.dispatchBuiltins = true }

    private fun bound(v: Any?, length: Int, dflt: Int): Int {
        if (v == null) return dflt
        val i = PyRt.indexValue(v) ?: throw PyRt.typeError("'${PyTypes.name(v)}' object cannot be interpreted as an integer")
        val n: Long = when (i) {
            is Long -> i
            is PyBool -> if (i.value) 1L else 0L
            is java.math.BigInteger -> if (i.signum() < 0) Long.MIN_VALUE else Long.MAX_VALUE
            else -> 0L
        }
        return n.coerceIn(0L, length.toLong()).toInt()
    }

    private fun state(p: PyPattern, string: Any?, pos: Any?, endpos: Any?): State {
        val subj = subject(string, p)
        val start = bound(pos, subj.length, 0)
        val end = bound(endpos, subj.length, subj.length)
        return State(subj, start, end, p.code)
    }

    private fun newMatch(p: PyPattern, string: Any?, st: State): PyMatch {
        val spans = IntArray((p.groups + 1) * 2) { -1 }
        spans[0] = st.start
        spans[1] = st.ptr
        for (g in 1..p.groups) {
            val j = 2 * (g - 1)
            if (j + 1 <= st.lastmark && st.mark(j) >= 0 && st.mark(j + 1) >= 0) {
                val a = st.mark(j)
                val b = st.mark(j + 1)
                if (a > b) throw PyException("SystemError", "The span of capturing group is wrong, please report a bug for the re module.")
                spans[2 * g] = a
                spans[2 * g + 1] = b
            }
        }
        return PyMatch(MATCH, p, string, st.str, st.pos, st.endpos, spans, st.lastindex)
    }

    /** A group's value: its substring, or [dflt] where it did not match. */
    private fun groupValue(m: PyMatch, g: Int, dflt: Any?): Any? {
        val a = m.spans[2 * g]
        val b = m.spans[2 * g + 1]
        if (a < 0 || b < 0) return dflt
        if (a == 0 && b == m.subject.length && m.string is PyStr && (m.string as PyStr).sub == null) return m.string
        if (a == 0 && b == m.subject.length && m.string is PyBytes) return m.string
        return m.subject.slice(a, b)
    }

    /** A group's number from an int or a name; CPython's IndexError for any other. */
    private fun groupIndex(m: PyMatch, g: Any?): Int {
        val i = when (g) {
            is Long -> g
            is PyBool -> if (g.value) 1L else 0L
            is PyStr -> (m.pattern.groupindex[g] as? Long) ?: -1L
            else -> (PyRt.indexValue(g) as? Long) ?: (m.pattern.groupindex[g] as? Long) ?: -1L
        }
        if (i < 0 || i > m.pattern.groups) throw PyException("IndexError", "no such group")
        return i.toInt()
    }

    private fun emptyOf(p: PyPattern): Any = if (p.isBytes) PyBytes(ByteArray(0)) else PyStr.EMPTY

    private fun join(p: PyPattern, items: List<Any?>): Any {
        if (p.isBytes) {
            val o = java.io.ByteArrayOutputStream()
            for ((i, x) in items.withIndex()) {
                val b = PyByteSeqs.content(x) ?: throw PyRt.typeError("sequence item $i: expected a bytes-like object, ${PyTypes.name(x)} found")
                o.write(b)
            }
            return PyBytes(o.toByteArray())
        }
        val sb = StringBuilder()
        for ((i, x) in items.withIndex()) {
            val s = (x as? PyStr)?.s ?: ((x as? PyNumSub)?.num as? PyStr)?.s ?: throw PyRt.typeError("sequence item $i: expected str instance, ${PyTypes.name(x)} found")
            sb.append(s)
        }
        return PyStr(sb.toString())
    }

    /** What a template gives for [m]: its literals, the groups' values between (empty where one did not match). */
    private fun expand(tpl: PyTemplate, m: PyMatch): List<Any?> {
        val out = ArrayList<Any?>()
        for (i in tpl.literals.indices) {
            tpl.literals[i]?.let { if (it !== PyNone) out.add(it) }
            if (i < tpl.groupIndices.size) {
                val g = tpl.groupIndices[i]
                if (g > m.pattern.groups) throw PyException("IndexError", "no such group")
                groupValue(m, g, null)?.let { out.add(it) }
            }
        }
        return out
    }

    private fun compileTemplate(ctx: PyContext, p: PyPattern, repl: Any?): Any? {
        val re = PyImport.importModule(ctx, "re")
        return PyCalls.callWith(ctx, PyRt.getAttr(re, "_compile_template"), arrayOf(p, repl), NO_ARGS, NO_ARGS)
    }

    private fun hasBackslash(v: Any?): Boolean? = when {
        v is PyStr -> v.s.indexOf('\\') >= 0
        PyByteSeqs.isBytesLike(v) -> PyByteSeqs.content(v)!!.any { it == '\\'.code.toByte() }
        else -> null
    }

    private fun subx(ctx: PyContext, p: PyPattern, repl: Any?, string: Any?, countArg: Long, wantCount: Boolean): Any? {
        // (a callable called with each match; a str with no backslash as it is; else a template, re._compile_template's)
        var callable: Any? = null
        var literal: Any? = null
        var template: PyTemplate? = null
        if (PyCalls.isCallable(repl)) callable = repl else {
            val bs = hasBackslash(repl)
            if (bs == false) literal = repl else {
                val t = compileTemplate(ctx, p, repl)
                if (t is PyTemplate) {
                    if (t.groupIndices.isEmpty()) literal = t.literals.getOrNull(0) ?: emptyOf(p) else template = t
                } else literal = t
            }
        }
        val st = state(p, string, null, null)
        val items = ArrayList<Any?>()
        var i = 0
        var n = 0L
        while (countArg == 0L || n < countArg) {
            st.ptr = st.start
            if (!search(st)) break
            val b = st.start
            val e = st.ptr
            if (i < b) items.add(st.str.slice(i, b))
            when {
                callable != null -> {
                    val r = PyCalls.callWith(ctx, callable, arrayOf(newMatch(p, string, st)), NO_ARGS, NO_ARGS)
                    if (r !== PyNone && r != null) items.add(r)
                }
                template != null -> items.addAll(expand(template, newMatch(p, string, st)))
                else -> if (literal !== PyNone) items.add(literal)
            }
            i = e
            n++
            st.mustAdvance = st.ptr == st.start
            st.start = st.ptr
            st.reset()
        }
        if (i < st.endpos) items.add(st.str.slice(i, st.endpos))
        val joined = if (n == 0L && (string is PyStr && string.sub == null || string is PyBytes)) string else join(p, items)
        return if (wantCount) PyTuple(arrayOf(joined, n)) else joined
    }

    /** Arguments of (name, ...) by position or keyword, CPython's messages. */
    private fun args(fn: String, a: Array<Any?>, kw: PyDict?, names: List<String>, required: Int): Array<Any?> {
        val out = arrayOfNulls<Any?>(names.size)
        val pos = a.size - 1
        if (pos > names.size) throw PyRt.typeError("$fn() takes at most ${names.size} arguments ($pos given)")
        for (i in 0 until pos) out[i] = a[i + 1]
        kw?.map?.forEach { (k, v) ->
            val n = (k.v as PyStr).s
            val i = names.indexOf(n)
            if (i < 0) throw PyRt.typeError("$fn() got an unexpected keyword argument '$n'")
            if (i < pos) throw PyRt.typeError("argument for $fn() given by name ('$n') and position (${i + 1})")
            out[i] = v
        }
        for (i in 0 until required) if (out[i] == null) throw PyRt.typeError("$fn() missing required argument '${names[i]}' (pos ${i + 1})")
        return out
    }

    private fun flagsRepr(p: PyPattern): String {
        val names = listOf("IGNORECASE" to FLAG_IGNORECASE, "LOCALE" to FLAG_LOCALE, "MULTILINE" to FLAG_MULTILINE, "DOTALL" to FLAG_DOTALL,
            "UNICODE" to FLAG_UNICODE, "VERBOSE" to FLAG_VERBOSE, "DEBUG" to FLAG_DEBUG, "ASCII" to FLAG_ASCII)
        var flags = p.flags
        // (a str pattern's UNICODE is the default: not said)
        if (!p.isBytes && flags and (FLAG_LOCALE or FLAG_UNICODE or FLAG_ASCII) == FLAG_UNICODE) flags = flags and FLAG_UNICODE.inv()
        if (flags == 0L) return ""
        val parts = ArrayList<String>()
        for ((n, f) in names) if (flags and f != 0L) {
            parts.add("re.$n")
            flags = flags and f.inv()
        }
        if (flags != 0L) parts.add("0x" + java.lang.Long.toHexString(flags))
        return ", " + parts.joinToString("|")
    }

    init {
        definePattern()
        defineMatch()
        defineScanner()
    }

    private fun pat(a: Array<Any?>, n: String): PyPattern =
        a.getOrNull(0) as? PyPattern ?: throw PyRt.typeError("descriptor '$n' for 're.Pattern' objects doesn't apply to a '${PyTypes.name(a.getOrNull(0))}' object")

    private fun definePattern() {
        val t = PATTERN
        t.newImpl = { _, _, _, _ -> throw PyRt.typeError("cannot create 're.Pattern' instances") }
        fun m(n: String, impl: (PyContext, PyPattern, Array<Any?>, PyDict?) -> Any?) =
            t.def(n, PyBuiltin(n, { ctx, a -> impl(ctx, pat(a, n), a, null) }, { ctx, a, kw -> impl(ctx, pat(a, n), a, kw) }))
        fun prop(n: String, get: (PyPattern) -> Any?) = t.def(n, PyProperty(PyBuiltin(n) { _, a -> get(pat(a, n)) }, null, null, null).also { it.name = n })
        for ((n, kind) in listOf("match" to 0, "fullmatch" to 1, "search" to 2)) m(n) { _, p, a, kw ->
            val x = args(n, a, kw, listOf("string", "pos", "endpos"), 1)
            val st = state(p, x[0], x[1], x[2])
            if (kind == 1) st.matchAll = true
            val ok = if (kind == 2) search(st) else match(st)
            if (ok) newMatch(p, x[0], st) else PyNone
        }
        m("findall") { _, p, a, kw ->
            val x = args("findall", a, kw, listOf("string", "pos", "endpos"), 1)
            val st = state(p, x[0], x[1], x[2])
            val out = ArrayList<Any?>()
            while (st.start <= st.end) {
                st.reset()
                st.ptr = st.start
                if (!search(st)) break
                val item: Any? = when (p.groups) {
                    0 -> st.str.slice(st.start, st.ptr)
                    1 -> groupSlice(st, 1) ?: emptyOf(p)
                    else -> PyTuple(Array(p.groups) { groupSlice(st, it + 1) ?: emptyOf(p) })
                }
                out.add(item)
                st.mustAdvance = st.ptr == st.start
                st.start = st.ptr
            }
            PyList(out)
        }
        m("finditer") { ctx, p, a, kw ->
            val x = args("finditer", a, kw, listOf("string", "pos", "endpos"), 1)
            val sc = PyScanner(SCANNER, p, x[0], state(p, x[0], x[1], x[2]))
            val search = PyRt.getAttr(sc, "search")
            PyCalls.callWith(ctx, ctx.builtins[PyStr("iter")], arrayOf(search, PyNone), NO_ARGS, NO_ARGS)
        }
        m("scanner") { _, p, a, kw ->
            val x = args("scanner", a, kw, listOf("string", "pos", "endpos"), 1)
            PyScanner(SCANNER, p, x[0], state(p, x[0], x[1], x[2]))
        }
        for ((n, cnt) in listOf("sub" to false, "subn" to true)) m(n) { ctx, p, a, kw ->
            val x = args(n, a, kw, listOf("repl", "string", "count"), 2)
            val c = x[2]?.let { PyRt.indexValue(it) as? Long ?: throw PyRt.typeError("'${PyTypes.name(it)}' object cannot be interpreted as an integer") } ?: 0L
            // (a negative count: no substitution, as CPython's loop has it)
            subx(ctx, p, x[0], x[1], c, cnt)
        }
        m("split") { _, p, a, kw ->
            val x = args("split", a, kw, listOf("string", "maxsplit"), 1)
            val max = x[1]?.let { PyRt.indexValue(it) as? Long ?: throw PyRt.typeError("'${PyTypes.name(it)}' object cannot be interpreted as an integer") } ?: 0L
            val st = state(p, x[0], null, null)
            val out = ArrayList<Any?>()
            if (max < 0) {
                out.add(st.str.slice(0, st.str.length))
                return@m PyList(out)
            }
            var n = 0L
            var last = st.start
            while (max == 0L || n < max) {
                st.reset()
                st.ptr = st.start
                if (!search(st)) break
                out.add(st.str.slice(last, st.start))
                for (g in 1..p.groups) out.add(groupSlice(st, g) ?: PyNone)
                n++
                st.mustAdvance = st.ptr == st.start
                last = st.ptr
                st.start = st.ptr
            }
            out.add(st.str.slice(last, st.endpos))
            PyList(out)
        }
        prop("pattern") { it.pattern }
        prop("flags") { it.flags }
        prop("groups") { it.groups.toLong() }
        prop("groupindex") { PyMappingProxies.of(it.groupindex) }
        m("__repr__") { _, p, _, _ ->
            val r = PyRt.repr(p.pattern)
            PyStr("re.compile(${if (r.length > 200) r.substring(0, 200) else r}${flagsRepr(p)})")
        }
        m("__eq__") { _, p, a, _ ->
            val o = a[1] as? PyPattern ?: return@m PyNotImplemented
            PyBool.of(o === p || o.flags == p.flags && o.isBytes == p.isBytes && PyRt.eq(o.pattern, p.pattern) && o.code.contentEquals(p.code))
        }
        m("__ne__") { _, p, a, _ ->
            val o = a[1] as? PyPattern ?: return@m PyNotImplemented
            PyBool.of(!(o === p || o.flags == p.flags && o.isBytes == p.isBytes && PyRt.eq(o.pattern, p.pattern) && o.code.contentEquals(p.code)))
        }
        m("__hash__") { _, p, _, _ -> (PyRt.hash(p.pattern).toLong() * 31 + p.flags) }
        m("__copy__") { _, p, _, _ -> p }
        m("__deepcopy__") { _, p, _, _ -> p }
        m("__reduce__") { ctx, p, _, _ -> PyTuple(arrayOf(PyRt.getAttr(PyImport.importModule(ctx, "re"), "_compile"), PyTuple(arrayOf(p.pattern, p.flags)))) }
        t.def("__class_getitem__", PyClassMethod(PyBuiltin("__class_getitem__") { _, a -> PyGenericAlias(a[0], PyTyping.argsOf(a[1])) }))
    }

    private fun groupSlice(st: State, g: Int): Any? {
        val j = 2 * (g - 1)
        if (j + 1 > st.lastmark || st.mark(j) < 0 || st.mark(j + 1) < 0) return null
        return st.str.slice(st.mark(j), st.mark(j + 1))
    }

    private fun mt(a: Array<Any?>, n: String): PyMatch =
        a.getOrNull(0) as? PyMatch ?: throw PyRt.typeError("descriptor '$n' for 're.Match' objects doesn't apply to a '${PyTypes.name(a.getOrNull(0))}' object")

    private fun defineMatch() {
        val t = MATCH
        t.newImpl = { _, _, _, _ -> throw PyRt.typeError("cannot create 're.Match' instances") }
        fun m(n: String, impl: (PyContext, PyMatch, Array<Any?>, PyDict?) -> Any?) =
            t.def(n, PyBuiltin(n, { ctx, a -> impl(ctx, mt(a, n), a, null) }, { ctx, a, kw -> impl(ctx, mt(a, n), a, kw) }))
        fun prop(n: String, get: (PyMatch) -> Any?) = t.def(n, PyProperty(PyBuiltin(n) { _, a -> get(mt(a, n)) }, null, null, null).also { it.name = n })
        m("group") { _, x, a, _ ->
            when (a.size) {
                1 -> groupValue(x, 0, PyNone)
                2 -> groupValue(x, groupIndex(x, a[1]), PyNone)
                else -> PyTuple(Array(a.size - 1) { groupValue(x, groupIndex(x, a[it + 1]), PyNone) })
            }
        }
        m("__getitem__") { _, x, a, _ -> groupValue(x, groupIndex(x, a[1]), PyNone) }
        m("groups") { _, x, a, kw ->
            val d = args("groups", a, kw, listOf("default"), 0)[0] ?: PyNone
            PyTuple(Array(x.pattern.groups) { groupValue(x, it + 1, d) })
        }
        m("groupdict") { _, x, a, kw ->
            val d = args("groupdict", a, kw, listOf("default"), 0)[0] ?: PyNone
            val out = PyDict()
            for ((k, v) in x.pattern.groupindex.map) out.map[k] = groupValue(x, (v as Long).toInt(), d)
            out
        }
        fun span(x: PyMatch, a: Array<Any?>): Int = if (a.size > 1) groupIndex(x, a[1]) else 0
        m("start") { _, x, a, _ -> x.spans[2 * span(x, a)].toLong() }
        m("end") { _, x, a, _ -> x.spans[2 * span(x, a) + 1].toLong() }
        m("span") { _, x, a, _ -> val g = span(x, a); PyTuple(arrayOf(x.spans[2 * g].toLong(), x.spans[2 * g + 1].toLong())) }
        m("expand") { ctx, x, a, _ ->
            val tpl = compileTemplate(ctx, x.pattern, a.getOrNull(1))
            if (tpl is PyTemplate) join(x.pattern, expand(tpl, x)) else tpl
        }
        m("__repr__") { _, x, _, _ ->
            PyStr("<re.Match object; span=(${x.spans[0]}, ${x.spans[1]}), match=${PyRt.repr(groupValue(x, 0, PyNone))}>")
        }
        m("__copy__") { _, x, _, _ -> x }
        m("__deepcopy__") { _, x, _, _ -> x }
        prop("regs") { x -> PyTuple(Array(x.pattern.groups + 1) { PyTuple(arrayOf(x.spans[2 * it].toLong(), x.spans[2 * it + 1].toLong())) }) }
        prop("string") { it.string }
        prop("re") { it.pattern }
        prop("pos") { it.pos.toLong() }
        prop("endpos") { it.endpos.toLong() }
        prop("lastindex") { if (it.lastindex >= 0) it.lastindex.toLong() else PyNone }
        prop("lastgroup") { x ->
            val ig = x.pattern.indexgroup
            if (x.lastindex < 0 || ig == null || ig === PyNone) PyNone
            else (ig as? PyTuple)?.items?.getOrNull(x.lastindex) ?: PyNone
        }
        t.def("__class_getitem__", PyClassMethod(PyBuiltin("__class_getitem__") { _, a -> PyGenericAlias(a[0], PyTyping.argsOf(a[1])) }))
    }

    private fun defineScanner() {
        val t = SCANNER
        fun sc(a: Array<Any?>): PyScanner = a[0] as PyScanner
        for ((n, isSearch) in listOf("match" to false, "search" to true)) t.def(n, PyBuiltin(n) { _, a ->
            val s = sc(a)
            if (s.done) return@PyBuiltin PyNone
            val st = s.state
            st.reset()
            st.ptr = st.start
            val ok = if (isSearch) search(st) else match(st)
            if (!ok) {
                s.done = true
                return@PyBuiltin PyNone
            }
            val m = newMatch(s.pattern, s.string, st)
            st.mustAdvance = st.ptr == st.start
            st.start = st.ptr
            m
        })
        t.def("pattern", PyProperty(PyBuiltin("pattern") { _, a -> sc(a).pattern }, null, null, null).also { it.name = "pattern" })
    }

    /** `_sre` as a module. */
    @JvmStatic
    fun fill(ctx: PyContext, d: PyDict) {
        d[PyStr("MAGIC")] = MAGIC
        d[PyStr("CODESIZE")] = CODESIZE
        d[PyStr("MAXREPEAT")] = MAXREPEAT
        d[PyStr("MAXGROUPS")] = MAXGROUPS
        d[PyStr("copyright")] = PyStr(" SRE 2.2.2 Copyright (c) 1997-2002 by Secret Labs AB ")
        d[PyStr("getcodesize")] = PyBuiltin("getcodesize") { _, _ -> CODESIZE }
        fun ch(a: Array<Any?>): Int = (PyRt.indexValue(a.getOrNull(0)) as? Long ?: throw PyRt.typeError("an integer is required")).toInt()
        d[PyStr("ascii_iscased")] = PyBuiltin("ascii_iscased") { _, a -> val c = ch(a); PyBool.of(c < 128 && (c in 65..90 || c in 97..122)) }
        d[PyStr("unicode_iscased")] = PyBuiltin("unicode_iscased") { _, a -> val c = ch(a); PyBool.of(c != lowerUnicode(c) || c != upperUnicode(c)) }
        d[PyStr("ascii_tolower")] = PyBuiltin("ascii_tolower") { _, a -> lowerAscii(ch(a)).toLong() }
        d[PyStr("unicode_tolower")] = PyBuiltin("unicode_tolower") { _, a -> lowerUnicode(ch(a)).toLong() }
        d[PyStr("compile")] = PyBuiltin("compile", { _, a -> compile(a, null) }, { _, a, kw -> compile(a, kw) })
        d[PyStr("template")] = PyBuiltin("template") { _, a ->
            if (a.size != 2) throw PyRt.typeError("template expected 2 arguments, got ${a.size}")
            val items = PyBuiltins.toList(a[1])
            // (literal, group, literal, group, ..., literal)
            val lits = arrayOfNulls<Any?>((items.size + 1) / 2)
            val groups = IntArray(items.size / 2)
            for ((i, x) in items.withIndex()) {
                if (i % 2 == 0) lits[i / 2] = x else {
                    val g = PyRt.indexValue(x) as? Long ?: throw PyRt.typeError("an integer is required")
                    if (g < 0) throw PyRt.typeError("invalid template")
                    groups[i / 2] = g.toInt()
                }
            }
            PyTemplate(TEMPLATE, lits, groups)
        }
    }

    /** _sre.compile(pattern, flags, code, groups, groupindex, indexgroup). */
    private fun compile(a: Array<Any?>, kw: PyDict?): PyPattern {
        val x = args("compile", arrayOf<Any?>(null, *a), kw, listOf("pattern", "flags", "code", "groups", "groupindex", "indexgroup"), 6)
        val codeList = PyBuiltins.toList(x[2])
        val code = IntArray(codeList.size) { i ->
            val v = PyRt.indexValue(codeList[i]) ?: throw PyRt.typeError("regular expression code must be integers")
            when (v) {
                is Long -> v.toInt()
                is java.math.BigInteger -> v.toLong().toInt()
                is PyBool -> if (v.value) 1 else 0
                else -> 0
            }
        }
        val flags = PyRt.indexValue(x[1]) as Long
        val groups = (PyRt.indexValue(x[3]) as Long).toInt()
        val gi = x[4] as? PyDict ?: PyBuiltins.dictFill(PyDict(), arrayOf(x[4]), null)
        return PyPattern(PATTERN, x[0], flags, code, groups, gi, x[5])
    }
}
