package io.neonjs.regexp

/**
 * Bytecode of the backtracking regular expression matcher (design after QuickJS libregexp).
 *
 * Instructions are stored in an IntArray: the opcode followed by its operands. Jump offsets are always the last
 * operand of an instruction and are relative to the end of that instruction.
 */
internal object Op {
    const val CHAR = 1                        // c: match one character
    const val CHAR_I = 2                      // c: match one character, case-insensitive (c is canonical)
    const val DOT = 3                         // any character except line terminators
    const val ANY = 4                         // any character
    const val SPACE = 5                       // \s
    const val NOT_SPACE = 6                   // \S
    const val LINE_START = 7
    const val LINE_START_M = 8
    const val LINE_END = 9
    const val LINE_END_M = 10
    const val GOTO = 11                       // off
    const val SPLIT_GOTO_FIRST = 12           // off: try the jump target first
    const val SPLIT_NEXT_FIRST = 13           // off: try the next instruction first
    const val MATCH = 14
    const val LOOKAHEAD_MATCH = 15
    const val NEGATIVE_LOOKAHEAD_MATCH = 16
    const val SAVE_START = 17                 // capture index
    const val SAVE_END = 18                   // capture index
    const val SAVE_RESET = 19                 // first, last capture index: reset to undefined
    const val LOOP = 20                       // reg, off: decrement counter and jump if not zero
    const val LOOP_SPLIT_GOTO_FIRST = 21      // reg, limit, off
    const val LOOP_SPLIT_NEXT_FIRST = 22      // reg, limit, off
    const val LOOP_CHECK_ADV_SPLIT_GOTO_FIRST = 23 // reg, limit, off (reg + 1 holds the iteration start position)
    const val LOOP_CHECK_ADV_SPLIT_NEXT_FIRST = 24 // reg, limit, off
    const val SET_I32 = 25                    // reg, value
    const val WORD_BOUNDARY = 26
    const val WORD_BOUNDARY_I = 27
    const val NOT_WORD_BOUNDARY = 28
    const val NOT_WORD_BOUNDARY_I = 29
    const val BACK_REFERENCE = 30             // n, capture indices...
    const val BACK_REFERENCE_I = 31
    const val BACKWARD_BACK_REFERENCE = 32
    const val BACKWARD_BACK_REFERENCE_I = 33
    const val RANGE = 34                      // n, ASCII bitmap (4 ints), (lo, hi inclusive) * n
    const val RANGE_I = 35
    const val LOOKAHEAD = 36                  // off (to the instruction after the matching LOOKAHEAD_MATCH)
    const val NEGATIVE_LOOKAHEAD = 37         // off
    const val SET_CHAR_POS = 38               // reg
    const val CHECK_ADVANCE = 39              // reg
    const val PREV = 40                       // move to the previous character
    const val STRING_SET = 41                 // index into RegExpProgram.stringGroups

    /** Size in ints of the instruction at [pc]. */
    fun size(code: IntArray, pc: Int): Int = when (code[pc]) {
        CHAR, CHAR_I, GOTO, SPLIT_GOTO_FIRST, SPLIT_NEXT_FIRST, SAVE_START, SAVE_END, LOOKAHEAD, NEGATIVE_LOOKAHEAD,
        SET_CHAR_POS, CHECK_ADVANCE, STRING_SET -> 2
        SAVE_RESET, LOOP, SET_I32 -> 3
        LOOP_SPLIT_GOTO_FIRST, LOOP_SPLIT_NEXT_FIRST, LOOP_CHECK_ADV_SPLIT_GOTO_FIRST, LOOP_CHECK_ADV_SPLIT_NEXT_FIRST -> 4
        BACK_REFERENCE, BACK_REFERENCE_I, BACKWARD_BACK_REFERENCE, BACKWARD_BACK_REFERENCE_I -> 2 + code[pc + 1]
        RANGE, RANGE_I -> 6 + 2 * code[pc + 1]
        else -> 1
    }
}

/**
 * Alternatives of a class string set (`v` flag) that have the same length in code points: matches one of [strings]
 * (UTF-16 encoded, canonicalized when [ignoreCase]) at the current position, forward or [backward].
 */
internal class StringGroup(
    @JvmField val length: Int,
    @JvmField val strings: Set<String>,
    @JvmField val ignoreCase: Boolean,
    @JvmField val backward: Boolean,
)

/** A compiled regular expression. Immutable and safe to share between threads and realms. */
class RegExpProgram internal constructor(
    @JvmField internal val code: IntArray,
    /** Number of capture groups including the implicit group 0. */
    @JvmField val captureCount: Int,
    @JvmField internal val registerCount: Int,
    /** Group name of each capture index (index 0 is always null), or null if the pattern has no named groups. */
    @JvmField val groupNames: Array<String?>?,
    @JvmField val flags: Int,
    @JvmField internal val stringGroups: Array<StringGroup>,
) {
    val isUnicode: Boolean get() = flags and (FLAG_UNICODE or FLAG_UNICODE_SETS) != 0

    /** How a non-sticky search can skip start positions: one of the START_ constants. */
    @JvmField internal val startKind: Int
    /** START_CHAR(_I): the character; START_RANGE(_I): the pc of the RANGE instruction. */
    @JvmField internal val startArg: Int

    init {
        // find the first instruction that consumes input or asserts, skipping capture/register bookkeeping
        var pc = 0
        while (code[pc] == Op.SAVE_START || code[pc] == Op.SAVE_END || code[pc] == Op.SET_I32 || code[pc] == Op.SET_CHAR_POS) {
            pc += Op.size(code, pc)
        }
        var kind = START_ANY
        var arg = 0
        when (code[pc]) {
            Op.LINE_START -> kind = START_ANCHOR
            Op.CHAR -> {
                val c = code[pc + 1]
                // a lone surrogate could be found inside a surrogate pair in unicode mode
                if (c >= 0 && (!isUnicode || c !in 0xD800..0xDFFF)) {
                    kind = START_CHAR
                    arg = c
                }
            }
            Op.CHAR_I -> {
                kind = START_CHAR_I
                arg = code[pc + 1]
            }
            Op.RANGE, Op.RANGE_I -> {
                kind = if (code[pc] == Op.RANGE) START_RANGE else START_RANGE_I
                arg = pc
            }
        }
        startKind = kind
        startArg = arg
    }

    /** Creates a matcher for [input]; [interrupt] is called periodically during long matches. */
    fun matcher(input: String, interrupt: Runnable? = null): RegExpMatcher = RegExpMatcher(this, input, interrupt)

    companion object {
        internal const val START_ANY = 0
        internal const val START_ANCHOR = 1
        internal const val START_CHAR = 2
        internal const val START_CHAR_I = 3
        internal const val START_RANGE = 4
        internal const val START_RANGE_I = 5

        const val FLAG_GLOBAL = 1
        const val FLAG_IGNORECASE = 2
        const val FLAG_MULTILINE = 4
        const val FLAG_DOTALL = 8
        const val FLAG_UNICODE = 16
        const val FLAG_STICKY = 32
        const val FLAG_INDICES = 64
        const val FLAG_UNICODE_SETS = 128

        /** Parses a flags string; returns -1 if it contains an unknown or repeated flag or both `u` and `v`. */
        @JvmStatic
        fun parseFlags(flags: String): Int {
            var mask = 0
            for (c in flags) {
                val f = when (c) {
                    'd' -> FLAG_INDICES
                    'g' -> FLAG_GLOBAL
                    'i' -> FLAG_IGNORECASE
                    'm' -> FLAG_MULTILINE
                    's' -> FLAG_DOTALL
                    'u' -> FLAG_UNICODE
                    'v' -> FLAG_UNICODE_SETS
                    'y' -> FLAG_STICKY
                    else -> return -1
                }
                if (mask and f != 0) return -1
                mask = mask or f
            }
            if (mask and FLAG_UNICODE != 0 && mask and FLAG_UNICODE_SETS != 0) return -1
            return mask
        }
    }
}
