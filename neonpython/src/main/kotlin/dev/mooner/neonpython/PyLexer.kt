package dev.mooner.neonpython

import java.math.BigInteger

/** A token of Python's source: its kind, its text or value, and where it starts. */
class PyToken(@JvmField val kind: Kind, @JvmField val text: String, @JvmField val value: Any?, @JvmField val line: Int, @JvmField val col: Int) {
    enum class Kind { NAME, NUMBER, STRING, OP, NEWLINE, INDENT, DEDENT, END }

    override fun toString() = "$kind '$text' at $line:$col"
}

/** A SyntaxError (or IndentationError) at a position of the source. */
class PySyntaxError(message: String, @JvmField val line: Int, @JvmField val col: Int, val type: String = "SyntaxError") :
    RuntimeException("$message (line $line)", null, false, false)

/**
 * Python's tokenizer: logical lines (NEWLINE, none inside brackets or after a backslash), INDENT and DEDENT from their
 * indentation, names, numbers (ints of any size, floats; underscores, hex, octal, binary), strings (quotes single or
 * triple, escapes, the r prefix; adjacent ones are joined by the parser), operators; comments dropped.
 */
class PyLexer(private val src: String) {
    private var pos = 0
    private var line = 1
    private var lineStart = 0
    private val indents = ArrayList<Int>().apply { add(0) }
    private var depth = 0
    private val out = ArrayList<PyToken>()

    fun tokenize(): List<PyToken> {
        var atLineStart = true
        while (true) {
            if (atLineStart && depth == 0) {
                // the indentation of a line that is not blank nor only a comment
                var col = 0
                var p = pos
                while (p < src.length && (src[p] == ' ' || src[p] == '\t' || src[p] == '\u000c')) {
                    col = if (src[p] == '\t') (col / 8 + 1) * 8 else col + 1
                    p++
                }
                if (p >= src.length) { pos = p; break }
                val c = src[p]
                if (c == '#' || c == '\n' || c == '\r') {
                    pos = p
                    skipComment()
                    if (pos < src.length) newline()
                    continue
                }
                pos = p
                if (col > indents.last()) {
                    indents.add(col)
                    out.add(PyToken(PyToken.Kind.INDENT, "", null, line, col))
                } else {
                    while (col < indents.last()) {
                        indents.removeAt(indents.size - 1)
                        out.add(PyToken(PyToken.Kind.DEDENT, "", null, line, col))
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
                c == '\\' && pos + 1 < src.length && (src[pos + 1] == '\n' || src[pos + 1] == '\r') -> {
                    pos++
                    newline()
                }
                c == '\n' || c == '\r' -> {
                    if (depth == 0) {
                        out.add(PyToken(PyToken.Kind.NEWLINE, "", null, line, pos - lineStart))
                        atLineStart = true
                    }
                    newline()
                }
                c.isLetter() || c == '_' || c.code > 127 && Character.isUnicodeIdentifierStart(src.codePointAt(pos)) -> nameOrString()
                c.isDigit() || c == '.' && pos + 1 < src.length && src[pos + 1].isDigit() -> number()
                c == '"' || c == '\'' -> string("")
                else -> op()
            }
        }
        if (out.isNotEmpty() && out.last().kind != PyToken.Kind.NEWLINE) out.add(PyToken(PyToken.Kind.NEWLINE, "", null, line, 0))
        while (indents.size > 1) {
            indents.removeAt(indents.size - 1)
            out.add(PyToken(PyToken.Kind.DEDENT, "", null, line, 0))
        }
        out.add(PyToken(PyToken.Kind.END, "", null, line, 0))
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

    private fun col(start: Int) = start - lineStart

    private fun nameOrString() {
        val start = pos
        while (pos < src.length && (src[pos].isLetterOrDigit() || src[pos] == '_' || src[pos].code > 127 && Character.isUnicodeIdentifierPart(src.codePointAt(pos)))) {
            pos += Character.charCount(src.codePointAt(pos))
        }
        val text = src.substring(start, pos)
        if (pos < src.length && (src[pos] == '"' || src[pos] == '\'') && text.lowercase() in STRING_PREFIXES) return string(text.lowercase(), start)
        out.add(PyToken(PyToken.Kind.NAME, java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC), null, line, col(start)))
    }

    private fun number() {
        val start = pos
        fun digits(ok: (Char) -> Boolean) {
            while (pos < src.length && (ok(src[pos]) || src[pos] == '_' && pos + 1 < src.length && ok(src[pos + 1]))) pos++
        }
        if (src[pos] == '0' && pos + 1 < src.length && src[pos + 1].lowercaseChar() in "xob") {
            val radix = when (src[pos + 1].lowercaseChar()) { 'x' -> 16; 'o' -> 8; else -> 2 }
            pos += 2
            val ds = pos
            digits { Character.digit(it, radix) >= 0 }
            if (pos == ds) throw PySyntaxError("invalid ${if (radix == 16) "hexadecimal" else if (radix == 8) "octal" else "binary"} literal", line, col(start))
            val text = src.substring(start, pos)
            out.add(PyToken(PyToken.Kind.NUMBER, text, PyRt.norm(BigInteger(src.substring(ds, pos).replace("_", ""), radix)), line, col(start)))
            return
        }
        var isFloat = false
        digits { it.isDigit() }
        if (pos < src.length && src[pos] == '.') {
            isFloat = true
            pos++
            digits { it.isDigit() }
        }
        if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
            val save = pos
            pos++
            if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
            if (pos < src.length && src[pos].isDigit()) {
                isFloat = true
                digits { it.isDigit() }
            } else pos = save
        }
        if (pos < src.length && (src[pos] == 'j' || src[pos] == 'J')) throw PySyntaxError("complex numbers are not supported yet", line, col(start))
        val text = src.substring(start, pos)
        val clean = text.replace("_", "")
        val value: Any = if (isFloat) clean.toDouble() else {
            if (clean.length > 1 && clean[0] == '0' && clean.any { it != '0' }) {
                throw PySyntaxError("leading zeros in decimal integer literals are not permitted; use an 0o prefix for octal integers", line, col(start))
            }
            PyRt.norm(BigInteger(clean))
        }
        out.add(PyToken(PyToken.Kind.NUMBER, text, value, line, col(start)))
    }

    private fun string(prefix: String, start: Int = pos) {
        if ('b' in prefix) throw PySyntaxError("bytes literals are not supported yet", line, col(start))
        if ('f' in prefix) throw PySyntaxError("f-strings are not supported yet", line, col(start))
        val raw = 'r' in prefix
        val q = src[pos]
        val triple = pos + 2 < src.length && src[pos + 1] == q && src[pos + 2] == q
        pos += if (triple) 3 else 1
        val startLine = line
        val sb = StringBuilder()
        while (true) {
            if (pos >= src.length) throw PySyntaxError("unterminated ${if (triple) "triple-quoted " else ""}string literal", startLine, col(start))
            val c = src[pos]
            if (c == q && (!triple || pos + 2 < src.length && src[pos + 1] == q && src[pos + 2] == q)) {
                pos += if (triple) 3 else 1
                break
            }
            if ((c == '\n' || c == '\r') && !triple) throw PySyntaxError("unterminated string literal", startLine, col(start))
            if (c == '\\' && pos + 1 < src.length) {
                val n = src[pos + 1]
                if (raw) {
                    sb.append(c).append(n)
                    pos += 2
                    continue
                }
                pos += 2
                when (n) {
                    '\n' -> { line++; lineStart = pos }
                    '\\' -> sb.append('\\')
                    '\'' -> sb.append('\'')
                    '"' -> sb.append('"')
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '0', '1', '2', '3', '4', '5', '6', '7' -> {
                        var v = n - '0'
                        var k = 0
                        while (k < 2 && pos < src.length && src[pos] in '0'..'7') { v = v * 8 + (src[pos] - '0'); pos++; k++ }
                        sb.append(v.toChar())
                    }
                    'x' -> sb.appendCodePoint(hex(2))
                    'u' -> sb.appendCodePoint(hex(4))
                    'U' -> sb.appendCodePoint(hex(8))
                    'a' -> sb.append('\u0007')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000c')
                    'v' -> sb.append('\u000b')
                    else -> sb.append('\\').append(n)
                }
                continue
            }
            if (c == '\n') { line++; lineStart = pos + 1 }
            sb.append(c)
            pos++
        }
        out.add(PyToken(PyToken.Kind.STRING, src.substring(start, pos), sb.toString(), startLine, col(start)))
    }

    private fun hex(n: Int): Int {
        if (pos + n > src.length) throw PySyntaxError("truncated \\x escape", line, col(pos))
        val v = src.substring(pos, pos + n).toIntOrNull(16) ?: throw PySyntaxError("truncated escape", line, col(pos))
        pos += n
        return v
    }

    private fun op() {
        val start = pos
        for (len in 3 downTo 1) {
            if (pos + len > src.length) continue
            val s = src.substring(pos, pos + len)
            if (s in OPERATORS) {
                pos += len
                when (s) {
                    "(", "[", "{" -> depth++
                    ")", "]", "}" -> if (depth > 0) depth--
                }
                out.add(PyToken(PyToken.Kind.OP, s, null, line, col(start)))
                return
            }
        }
        throw PySyntaxError("invalid character '${src[pos]}' (U+${Integer.toHexString(src[pos].code).uppercase().padStart(4, '0')})", line, col(start))
    }

    private companion object {
        val STRING_PREFIXES = setOf("r", "u", "b", "br", "rb", "f", "fr", "rf")
        val OPERATORS = setOf(
            "**=", "//=", ">>=", "<<=", "...", "->", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "@=", ":=",
            "**", "//", "<<", ">>", "<=", ">=", "==", "!=", "+", "-", "*", "/", "%", "@", "&", "|", "^", "~", "<", ">",
            "(", ")", "[", "]", "{", "}", ",", ":", ".", ";", "=",
        )
    }
}
