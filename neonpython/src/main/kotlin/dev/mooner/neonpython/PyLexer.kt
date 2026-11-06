package dev.mooner.neonpython

import java.math.BigInteger

/**
 * A token of Python's source: its kind, its text, its value (a number, a [StrLit], an [FStringInfo]), and where it is
 * (line from 1 and column from 0; [start] and [end] are offsets in the source).
 */
class PyToken(@JvmField val kind: Kind, @JvmField val text: String, @JvmField val value: Any?, @JvmField val line: Int,
              @JvmField val col: Int, @JvmField val start: Int = 0, @JvmField val end: Int = 0) {
    enum class Kind { NAME, NUMBER, STRING, FSTRING_START, FSTRING_MIDDLE, FSTRING_END, OP, NEWLINE, INDENT, DEDENT, END }

    override fun toString() = "$kind '$text' at $line:$col"
}

/** A string literal's value: a String, or bytes ([bytes]); [u] where it had the u prefix. */
class StrLit(@JvmField val value: Any, @JvmField val bytes: Boolean, @JvmField val u: Boolean)

/** An f-string's or a t-string's start or end: whether it is a t-string, and its prefix and quotes. */
class FStringInfo(@JvmField val template: Boolean, @JvmField val raw: Boolean, @JvmField val quote: Char, @JvmField val triple: Boolean)

/** A SyntaxError (or IndentationError) at a position of the source. */
class PySyntaxError(message: String, @JvmField val line: Int, @JvmField val col: Int, val type: String = "SyntaxError") :
    RuntimeException("$message (line $line)", null, false, false) {
    /** The message without the line. */
    val msg: String = message
    /** What raised it, for compile()'s SyntaxError: the symbol table (0), the compiler (1), the tokenizer or parser (2). */
    @JvmField var stage = 0
    /** An error no speculative parse may take back (the tokenizer's, as CPython's aborts its parser). */
    @JvmField var fatal = false
}

/**
 * Python's tokenizer (as CPython 3.14's: PEP 701 f-strings and PEP 750 t-strings tokenized, not scanned):
 * - logical lines: NEWLINE, none inside brackets nor after a backslash; INDENT and DEDENT from indentation;
 * - names (NFKC-normalized), numbers (ints of any size, floats, imaginary; underscores, hex, octal, binary);
 * - strings with their prefixes (r, u, b, f, t and their combinations), single or triple quoted, escapes decoded;
 * - an f-string or t-string as FSTRING_START, its literal parts (FSTRING_MIDDLE), its replacement fields' tokens
 *   between `{` and `}` (`!` before a conversion, `=` for a self-documenting one, `:` before a format spec, whose
 *   literal parts are FSTRING_MIDDLE and may hold fields of their own), FSTRING_END;
 * - operators; comments dropped.
 */
class PyLexer(private val src: String) {
    private var pos = 0
    private var line = 1
    private var lineStart = 0
    private val indents = ArrayList<Int>().apply { add(0) }
    /** Brackets open, a replacement field's `{` among them; each one's character, line and column. */
    private var depth = 0
    private val parens = CharArray(MAXLEVEL)
    private val parenLines = IntArray(MAXLEVEL)
    private val parenCols = IntArray(MAXLEVEL)
    /**
     * The bracket the source leaves open, if any (its character, line, column): the tokens then end without a
     * NEWLINE, and [PyParser] reports it as CPython's parser does, reaching the end or failing on a later line.
     */
    var unclosed: PyToken? = null
        private set
    private val out = ArrayList<PyToken>()

    /** An f-string being tokenized: its literal text, or a replacement field's expression or format spec. */
    private class Mode(@JvmField val info: FStringInfo, @JvmField val field: Boolean, @JvmField val base: Int) {
        @JvmField var spec = false
    }
    private val modes = ArrayList<Mode>()

    fun tokenize(): List<PyToken> {
        var atLineStart = true
        while (true) {
            val mode = modes.lastOrNull()
            if (mode != null && !mode.field) { fstringLiteral(mode); continue }
            if (mode != null && mode.spec) { formatSpec(mode); continue }
            if (atLineStart && depth == 0) {
                // the indentation of a line that is not blank nor only a comment
                var col = 0
                var p = pos
                while (p < src.length && (src[p] == ' ' || src[p] == '\t' || src[p] == '\u000c')) {
                    col = if (src[p] == '\t') (col / 8 + 1) * 8 else if (src[p] == '\u000c') 0 else col + 1
                    p++
                }
                if (p >= src.length) { pos = p; break }
                val c = src[p]
                if (c == '#' || c == '\n' || c == '\r' || c == '\\' && p + 1 < src.length && (src[p + 1] == '\n' || src[p + 1] == '\r')) {
                    pos = p
                    if (c == '\\') { pos++; newline(); continue }
                    skipComment()
                    if (pos < src.length) newline()
                    continue
                }
                pos = p
                if (col > indents.last()) {
                    indents.add(col)
                    out.add(PyToken(PyToken.Kind.INDENT, "", null, line, col, pos, pos))
                } else {
                    while (col < indents.last()) {
                        indents.removeAt(indents.size - 1)
                        out.add(PyToken(PyToken.Kind.DEDENT, "", null, line, col, pos, pos))
                    }
                    if (col != indents.last()) throw PySyntaxError("unindent does not match any outer indentation level", line, col, "IndentationError")
                }
                atLineStart = false
            }
            if (pos >= src.length) break
            val c = src[pos]
            when {
                c == ' ' || c == '\t' || c == '\u000c' -> pos++
                c == '#' -> skipComment()
                c == '\\' -> {
                    if (pos + 1 < src.length && (src[pos + 1] == '\n' || src[pos + 1] == '\r')) {
                        pos++
                        newline()
                    } else throw PySyntaxError("unexpected character after line continuation character", line, col(pos))
                }
                c == '\n' || c == '\r' -> {
                    if (depth == 0) {
                        out.add(PyToken(PyToken.Kind.NEWLINE, "", null, line, pos - lineStart, pos, pos))
                        atLineStart = true
                    }
                    newline()
                }
                mode != null && depth == mode.base && fieldPunct(mode, c) -> {}
                c.isLetter() || c == '_' || c.code > 127 && Character.isUnicodeIdentifierStart(src.codePointAt(pos)) -> nameOrString()
                c in '0'..'9' || c == '.' && pos + 1 < src.length && src[pos + 1] in '0'..'9' -> number()
                c == '"' || c == '\'' -> string("", pos)
                else -> op()
            }
        }
        if (modes.isNotEmpty()) {
            val m = modes.first()
            throw PySyntaxError("unterminated ${if (m.info.triple) "triple-quoted " else ""}${if (m.info.template) "t" else "f"}-string literal (detected at line $line)", line, col(pos))
        }
        if (depth > 0) {
            unclosed = PyToken(PyToken.Kind.OP, parens[depth - 1].toString(), null, parenLines[depth - 1], parenCols[depth - 1])
            out.add(PyToken(PyToken.Kind.END, "", null, line, col(pos), pos, pos))
            return out
        }
        if (out.isNotEmpty() && out.last().kind != PyToken.Kind.NEWLINE) out.add(PyToken(PyToken.Kind.NEWLINE, "", null, line, col(pos), pos, pos))
        while (indents.size > 1) {
            indents.removeAt(indents.size - 1)
            out.add(PyToken(PyToken.Kind.DEDENT, "", null, line, 0, pos, pos))
        }
        out.add(PyToken(PyToken.Kind.END, "", null, line, 0, pos, pos))
        return out
    }

    private fun newline() {
        if (src[pos] == '\r' && pos + 1 < src.length && src[pos + 1] == '\n') pos++
        pos++
        line++
        lineStart = pos
    }

    private fun skipComment() {
        if (pos < src.length && src[pos] == '#') while (pos < src.length && src[pos] != '\n' && src[pos] != '\r') pos++
    }

    private fun col(at: Int) = at - lineStart

    private fun add(kind: PyToken.Kind, start: Int, value: Any? = null, text: String = src.substring(start, pos), startLine: Int = line, startCol: Int = col(start)) {
        out.add(PyToken(kind, text, value, startLine, startCol, start, pos))
    }

    private fun nameOrString() {
        val start = pos
        while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_' ||
                src[pos].code > 127 && Character.isUnicodeIdentifierPart(src.codePointAt(pos)))) {
            pos += Character.charCount(src.codePointAt(pos))
        }
        val text = src.substring(start, pos)
        if (pos < src.length && (src[pos] == '"' || src[pos] == '\'')) {
            val prefix = text.lowercase()
            if (prefix in STRING_PREFIXES) {
                string(prefix, start)
                return
            }
            // distinct prefix letters that may not go together (CPython's maybe_raise_syntax_error_for_string_prefixes)
            if (prefix.length <= 5 && prefix.all { it in "burft" } && prefix.toSet().size == prefix.length) {
                for (pair in INCOMPATIBLE_PREFIXES) {
                    if (pair[0] in prefix && pair[1] in prefix) {
                        throw PySyntaxError("'${pair[0]}' and '${pair[1]}' prefixes are incompatible", line, col(start))
                    }
                }
            }
        }
        out.add(PyToken(PyToken.Kind.NAME, java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC), null, line, col(start), start, pos))
    }

    /**
     * A number, as CPython's tokenizer reads one (Parser/lexer/lexer.c): single underscores between digits, its
     * messages for a bad digit, a missing one, leading zeros; what follows it checked (verify_end_of_number): a
     * keyword that may follow one (and, else, for, if, in, is, not, or) is taken (CPython warns), another name
     * character "invalid ... literal".
     */
    private fun number() {
        val start = pos
        fun c(): Char = if (pos < src.length) src[pos] else '\u0000'
        fun err(msg: String, at: Int = pos): Nothing = throw PySyntaxError(msg, line, col(minOf(at, src.length)))
        fun digit(ch: Char) = ch in '0'..'9'
        fun idChar(ch: Char) = ch.code >= 128 || ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_'
        // the next characters (after c()) [test], then no name character
        fun lookahead(test: String): Boolean {
            if (!src.startsWith(test, pos + 1)) return false
            val after = pos + 1 + test.length
            return after >= src.length || !idChar(src[after])
        }
        fun verifyEnd(kind: String) {
            val ch = c()
            val keyword = when (ch) {
                'a' -> lookahead("nd")
                'e' -> lookahead("lse")
                'f' -> lookahead("or")
                'o' -> lookahead("r")
                'n' -> lookahead("ot")
                'i' -> pos + 1 < src.length && src[pos + 1] in "fns"
                else -> false
            }
            if (!keyword && ch.code in 1..127 && idChar(ch)) err("invalid $kind literal")
        }
        // digits, single underscores between them (tok_decimal_tail)
        fun decimalTail() {
            while (true) {
                while (digit(c())) pos++
                if (c() != '_') return
                pos++
                if (!digit(c())) err("invalid decimal literal")
            }
        }
        fun value(text: String, float: Boolean): Any = if (float) text.replace("_", "").toDouble() else PyRt.norm(BigInteger(text.replace("_", "")))
        if (src[pos] == '0' && pos + 1 < src.length && src[pos + 1] in "xXoObB") {
            val r = src[pos + 1].lowercaseChar()
            val (kind, radix) = when (r) { 'x' -> "hexadecimal" to 16; 'o' -> "octal" to 8; else -> "binary" to 2 }
            fun ok(ch: Char) = Character.digit(ch, radix) >= 0 && (radix == 16 || digit(ch))
            pos += 2
            do {
                if (c() == '_') pos++
                if (!ok(c())) {
                    if (radix != 16 && digit(c())) err("invalid digit '${c()}' in $kind literal", pos + 1)
                    err("invalid $kind literal")
                }
                while (ok(c())) pos++
            } while (c() == '_')
            if (radix != 16 && digit(c())) err("invalid digit '${c()}' in $kind literal", pos + 1)
            verifyEnd(kind)
            add(PyToken.Kind.NUMBER, start, PyRt.norm(BigInteger(src.substring(start + 2, pos).replace("_", ""), radix)))
            return
        }
        var fraction = false
        var exponent = false
        var imaginary = false
        if (src[pos] == '.') {
            pos++
            fraction = true
        } else if (src[pos] == '0') {
            pos++
            var nonzero = false
            while (true) {
                if (c() == '_') {
                    pos++
                    if (!digit(c())) err("invalid decimal literal")
                }
                if (c() != '0') break
                pos++
            }
            if (digit(c())) {
                nonzero = true
                decimalTail()
            }
            when {
                c() == '.' -> { pos++; fraction = true }
                c() == 'e' || c() == 'E' -> exponent = true
                c() == 'j' || c() == 'J' -> imaginary = true
                nonzero -> err("leading zeros in decimal integer literals are not permitted; use an 0o prefix for octal integers", start)
                else -> {
                    verifyEnd("decimal")
                    add(PyToken.Kind.NUMBER, start, 0L)
                    return
                }
            }
        } else {
            decimalTail()
            if (c() == '.') {
                pos++
                fraction = true
            }
        }
        var float = fraction
        if (!imaginary) {
            if (fraction && digit(c())) decimalTail()
            if (exponent || c() == 'e' || c() == 'E') {
                val e = pos
                pos++
                if (c() == '+' || c() == '-') {
                    pos++
                    if (!digit(c())) err("invalid decimal literal")
                } else if (!digit(c())) {
                    // (`1else`: the number ends before the e)
                    pos = e
                    verifyEnd("decimal")
                    add(PyToken.Kind.NUMBER, start, value(src.substring(start, e), float))
                    return
                }
                decimalTail()
                float = true
            }
            if (c() == 'j' || c() == 'J') imaginary = true
        }
        if (imaginary) {
            val text = src.substring(start, pos)
            pos++
            verifyEnd("imaginary")
            add(PyToken.Kind.NUMBER, start, PyComplex(0.0, text.replace("_", "").toDouble()))
            return
        }
        verifyEnd("decimal")
        add(PyToken.Kind.NUMBER, start, value(src.substring(start, pos), float))
    }

    private fun string(prefix: String, start: Int) {
        val q = src[pos]
        val triple = pos + 2 < src.length && src[pos + 1] == q && src[pos + 2] == q
        val raw = 'r' in prefix
        if ('f' in prefix || 't' in prefix) {
            pos += if (triple) 3 else 1
            val info = FStringInfo('t' in prefix, raw, q, triple)
            add(PyToken.Kind.FSTRING_START, start, info)
            modes.add(Mode(info, field = false, base = depth))
            return
        }
        val bytes = 'b' in prefix
        pos += if (triple) 3 else 1
        val startLine = line
        val startCol = col(start)
        val sb = StringBuilder()
        while (true) {
            if (pos >= src.length) throw unterminated(triple, startLine, false)
            val c = src[pos]
            if (c == q && (!triple || pos + 2 < src.length && src[pos + 1] == q && src[pos + 2] == q)) {
                pos += if (triple) 3 else 1
                break
            }
            if ((c == '\n' || c == '\r') && !triple) throw unterminated(false, startLine, false)
            if (c == '\\' && pos + 1 < src.length) {
                if (raw) {
                    sb.append(c)
                    pos++
                    val n = src[pos]
                    if (n == '\n' || n == '\r') { sb.append(n); newline(); continue }
                    sb.append(n)
                    pos++
                    continue
                }
                escape(sb, bytes)
                continue
            }
            if (c == '\n' || c == '\r') {
                sb.append('\n')
                newline()
                continue
            }
            if (bytes && c.code > 127) throw PySyntaxError("bytes can only contain ASCII literal characters", line, col(pos))
            sb.append(c)
            pos++
        }
        val value: Any = if (bytes) ByteArray(sb.length) { sb[it].code.toByte() } else sb.toString()
        out.add(PyToken(PyToken.Kind.STRING, src.substring(start, pos), StrLit(value, bytes, 'u' in prefix), startLine, startCol, start, pos))
    }

    private fun unterminated(triple: Boolean, startLine: Int, f: Boolean) =
        PySyntaxError("unterminated ${if (triple) "triple-quoted " else ""}${if (f) "f-" else ""}string literal (detected at line ${if (triple) line else startLine})",
            startLine, 0)

    /** The escape at [pos] (a backslash) into [sb]; [pos] past it. */
    private fun escape(sb: StringBuilder, bytes: Boolean) {
        val n = src[pos + 1]
        pos += 2
        when (n) {
            '\n', '\r' -> { pos--; newline() }
            '\\' -> sb.append('\\')
            '\'' -> sb.append('\'')
            '"' -> sb.append('"')
            'n' -> sb.append('\n')
            't' -> sb.append('\t')
            'r' -> sb.append('\r')
            'a' -> sb.append('\u0007')
            'b' -> sb.append('\b')
            'f' -> sb.append('\u000c')
            'v' -> sb.append('\u000b')
            in '0'..'7' -> {
                var v = n - '0'
                var k = 0
                while (k < 2 && pos < src.length && src[pos] in '0'..'7') { v = v * 8 + (src[pos] - '0'); pos++; k++ }
                if (bytes) sb.append((v and 0xFF).toChar()) else sb.appendCodePoint(v)
            }
            'x' -> sb.appendCodePoint(hex(2, "x"))
            'u' -> if (bytes) sb.append("\\u") else sb.appendCodePoint(hex(4, "u"))
            'U' -> if (bytes) sb.append("\\U") else {
                val v = hex(8, "U")
                if (v > 0x10FFFF) throw PySyntaxError("(unicode error) 'unicodeescape' codec can't decode bytes: illegal Unicode character", line, col(pos))
                sb.appendCodePoint(v)
            }
            'N' -> if (bytes) sb.append("\\N") else {
                if (pos >= src.length || src[pos] != '{') throw PySyntaxError("(unicode error) 'unicodeescape' codec can't decode bytes: malformed \\N character escape", line, col(pos))
                val close = src.indexOf('}', pos)
                if (close < 0) throw PySyntaxError("(unicode error) 'unicodeescape' codec can't decode bytes: malformed \\N character escape", line, col(pos))
                val name = src.substring(pos + 1, close)
                val cp = try { Character.codePointOf(name) } catch (_: IllegalArgumentException) {
                    throw PySyntaxError("(unicode error) 'unicodeescape' codec can't decode bytes: unknown Unicode character name", line, col(pos))
                }
                sb.appendCodePoint(cp)
                pos = close + 1
            }
            // (an unknown escape stays as it is, with CPython's warning)
            else -> sb.append('\\').append(n)
        }
    }

    private fun hex(n: Int, what: String): Int {
        val s = if (pos + n <= src.length) src.substring(pos, pos + n) else ""
        if (s.length < n || s.any { Character.digit(it, 16) < 0 }) {
            throw PySyntaxError("(unicode error) 'unicodeescape' codec can't decode bytes: truncated \\$what${"X".repeat(n)} escape", line, col(pos))
        }
        pos += n
        return s.toInt(16)
    }

    // ------------------------------------------------------------------ f-strings and t-strings

    /** An f-string's literal text, up to a replacement field or its end. */
    private fun fstringLiteral(m: Mode) {
        val info = m.info
        val start = pos
        val startLine = line
        val startCol = col(pos)
        val sb = StringBuilder()
        while (true) {
            if (pos >= src.length) throw unterminatedF(info)
            val c = src[pos]
            if (c == info.quote && (!info.triple || pos + 2 < src.length && src[pos + 1] == c && src[pos + 2] == c)) {
                if (sb.isNotEmpty()) out.add(PyToken(PyToken.Kind.FSTRING_MIDDLE, src.substring(start, pos), sb.toString(), startLine, startCol, start, pos))
                val s = pos
                pos += if (info.triple) 3 else 1
                add(PyToken.Kind.FSTRING_END, s, info)
                modes.removeAt(modes.size - 1)
                return
            }
            if ((c == '\n' || c == '\r') && !info.triple) throw unterminatedF(info)
            if (c == '{') {
                if (pos + 1 < src.length && src[pos + 1] == '{') { sb.append('{'); pos += 2; continue }
                if (sb.isNotEmpty()) out.add(PyToken(PyToken.Kind.FSTRING_MIDDLE, src.substring(start, pos), sb.toString(), startLine, startCol, start, pos))
                openField(info)
                return
            }
            if (c == '}') {
                if (pos + 1 < src.length && src[pos + 1] == '}') { sb.append('}'); pos += 2; continue }
                throw PySyntaxError("${if (info.template) "t" else "f"}-string: single '}' is not allowed", line, col(pos))
            }
            // (a backslash does not escape a brace: \{x} is a backslash and a replacement field)
            if (c == '\\' && pos + 1 < src.length && (src[pos + 1] == '{' || src[pos + 1] == '}')) {
                sb.append(c)
                pos++
                continue
            }
            if (c == '\\' && pos + 1 < src.length && !info.raw) {
                escape(sb, false)
                continue
            }
            if (c == '\\' && pos + 1 < src.length) {
                sb.append(c).append(src[pos + 1])
                pos += 2
                continue
            }
            if (c == '\n' || c == '\r') { sb.append('\n'); newline(); continue }
            sb.append(c)
            pos++
        }
    }

    private fun unterminatedF(info: FStringInfo) = PySyntaxError(
        "unterminated ${if (info.triple) "triple-quoted " else ""}${if (info.template) "t" else "f"}-string literal (detected at line $line)", line, col(pos))

    private fun openField(info: FStringInfo) {
        if (modes.count { it.field } >= 3) throw PySyntaxError("${if (info.template) "t" else "f"}-string: expressions nested too deeply", line, col(pos))
        val s = pos
        pos++
        add(PyToken.Kind.OP, s)
        open('{', s)
        modes.add(Mode(info, field = true, base = depth))
    }

    /**
     * In a replacement field's expression, at its own bracket depth: `}` ends it, `:` starts its format spec, `!`
     * (not `!=`) its conversion, `=` (not `==`) makes it self-documenting. False for any other character.
     */
    private fun fieldPunct(m: Mode, c: Char): Boolean {
        val next = if (pos + 1 < src.length) src[pos + 1] else '\u0000'
        val s = pos
        when {
            c == '}' -> {
                pos++
                add(PyToken.Kind.OP, s)
                depth--
                modes.removeAt(modes.size - 1)
            }
            c == ':' -> {
                pos++
                add(PyToken.Kind.OP, s)
                m.spec = true
            }
            c == '!' && next != '=' -> {
                pos++
                add(PyToken.Kind.OP, s)
            }
            c == '=' && next != '=' -> {
                pos++
                add(PyToken.Kind.OP, s)
            }
            else -> return false
        }
        return true
    }

    /** A format spec's literal text, up to a nested replacement field or the field's end. */
    private fun formatSpec(m: Mode) {
        val info = m.info
        val start = pos
        val startLine = line
        val startCol = col(pos)
        val sb = StringBuilder()
        while (true) {
            if (pos >= src.length) throw unterminatedF(info)
            val c = src[pos]
            if (c == '{' || c == '}') {
                if (sb.isNotEmpty()) out.add(PyToken(PyToken.Kind.FSTRING_MIDDLE, src.substring(start, pos), sb.toString(), startLine, startCol, start, pos))
                if (c == '{') openField(info)
                else {
                    val s = pos
                    pos++
                    add(PyToken.Kind.OP, s)
                    depth--
                    modes.removeAt(modes.size - 1)
                }
                return
            }
            if (c == info.quote && (!info.triple || src.startsWith(info.quote.toString().repeat(3), pos))) {
                throw PySyntaxError("${if (info.template) "t" else "f"}-string: expecting '}'", line, col(pos))
            }
            if ((c == '\n' || c == '\r') && !info.triple) throw unterminatedF(info)
            if (c == '\\' && pos + 1 < src.length && !info.raw) {
                escape(sb, false)
                continue
            }
            if (c == '\n' || c == '\r') { sb.append('\n'); newline(); continue }
            sb.append(c)
            pos++
        }
    }

    private fun op() {
        val start = pos
        for (len in 3 downTo 1) {
            if (pos + len > src.length) continue
            val s = src.substring(pos, pos + len)
            if (s in OPERATORS) {
                pos += len
                when (s) {
                    "(", "[", "{" -> open(s[0], start)
                    ")", "]", "}" -> close(s[0], start)
                }
                out.add(PyToken(PyToken.Kind.OP, s, null, line, col(start), start, pos))
                return
            }
        }
        val cp = src.codePointAt(pos)
        val hex = Integer.toHexString(cp).uppercase().padStart(4, '0')
        if (!PyRt.printable(cp)) throw PySyntaxError("invalid non-printable character U+$hex", line, col(start))
        if (cp < 128) {
            // (`!`, `$`, `?`, a backquote: CPython's one-character tokens no rule takes)
            pos++
            out.add(PyToken(PyToken.Kind.OP, src.substring(start, pos), null, line, col(start), start, pos))
            return
        }
        throw PySyntaxError("invalid character '${String(Character.toChars(cp))}' (U+$hex)", line, col(start))
    }

    private fun open(c: Char, at: Int) {
        if (depth >= MAXLEVEL) throw PySyntaxError("too many nested parentheses", line, col(at))
        parens[depth] = c
        parenLines[depth] = line
        parenCols[depth] = col(at)
        depth++
    }

    /** A closing bracket: CPython's messages for one that closes nothing, or another kind of bracket. */
    private fun close(c: Char, at: Int) {
        if (depth == 0) throw PySyntaxError("unmatched '$c'", line, col(at))
        depth--
        val opening = parens[depth]
        if (c == ')' && opening == '(' || c == ']' && opening == '[' || c == '}' && opening == '{') return
        val m = modes.lastOrNull()
        if (m != null && opening == '{' && depth + 1 == m.base) {
            throw PySyntaxError("${if (m.info.template) "t" else "f"}-string: unmatched '$c'", line, col(at))
        }
        val where = if (parenLines[depth] != line) " on line ${parenLines[depth]}" else ""
        throw PySyntaxError("closing parenthesis '$c' does not match opening parenthesis '$opening'$where", line, col(at))
    }

    private companion object {
        val STRING_PREFIXES = setOf("r", "u", "b", "br", "rb", "f", "fr", "rf", "t", "tr", "rt")
        val INCOMPATIBLE_PREFIXES = arrayOf("ub", "ur", "uf", "ut", "bf", "bt", "ft")
        /** CPython's MAXLEVEL: brackets open at once. */
        const val MAXLEVEL = 200
        val OPERATORS = setOf(
            "**=", "//=", ">>=", "<<=", "...", "->", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "@=", ":=",
            "**", "//", "<<", ">>", "<=", ">=", "==", "!=", "+", "-", "*", "/", "%", "@", "&", "|", "^", "~", "<", ">",
            "(", ")", "[", "]", "{", "}", ",", ":", ".", ";", "=",
        )
    }
}
