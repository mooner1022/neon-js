package dev.mooner.neonjs.parser

import java.math.BigInteger

/** Syntax error raised by the lexer/parser. Positions are 0-based offsets, 1-based lines/columns. */
class JSSyntaxError(message: String, val pos: Int, val line: Int, val column: Int, val sourceName: String? = null) :
    RuntimeException(message, null, false, false) {
    val rawMessage: String get() = super.message ?: ""
    override val message: String get() = "${super.message} (${sourceName ?: "<input>"}:$line:$column)"
}

enum class T(val text: String) {
    EOF("end of input"), NAME("identifier"), PRIVATE_NAME("private name"), NUM("number"), BIGINT("bigint"),
    STRING("string"), TEMPLATE("template"), REGEXP("regular expression"),
    LBRACE("{"), RBRACE("}"), LPAREN("("), RPAREN(")"), LBRACKET("["), RBRACKET("]"),
    DOT("."), ELLIPSIS("..."), SEMI(";"), COMMA(","), QUESTION("?"), QDOT("?."), COLON(":"), ARROW("=>"), AT("@"),
    LT("<"), GT(">"), LE("<="), GE(">="), EQ("=="), NE("!="), SEQ("==="), SNE("!=="),
    PLUS("+"), MINUS("-"), STAR("*"), SLASH("/"), PERCENT("%"), STARSTAR("**"), INC("++"), DEC("--"),
    SHL("<<"), SAR(">>"), SHR(">>>"), AMP("&"), BAR("|"), CARET("^"), BANG("!"), TILDE("~"),
    AND("&&"), OR("||"), NULLISH("??"),
    ASSIGN("="), PLUS_ASSIGN("+="), MINUS_ASSIGN("-="), STAR_ASSIGN("*="), SLASH_ASSIGN("/="), PERCENT_ASSIGN("%="),
    STARSTAR_ASSIGN("**="), SHL_ASSIGN("<<="), SAR_ASSIGN(">>="), SHR_ASSIGN(">>>="), AMP_ASSIGN("&="),
    BAR_ASSIGN("|="), CARET_ASSIGN("^="), AND_ASSIGN("&&="), OR_ASSIGN("||="), NULLISH_ASSIGN("??=");

    val isAssign: Boolean get() = ordinal >= ASSIGN.ordinal
}

object Chars {
    fun isLineTerminator(c: Int) = c == 0x0A || c == 0x0D || c == 0x2028 || c == 0x2029

    fun isWhitespace(c: Int): Boolean = when (c) {
        0x09, 0x0B, 0x0C, 0x20, 0xA0, 0xFEFF -> true
        else -> c > 0x7F && c != 0x180E && Character.getType(c) == Character.SPACE_SEPARATOR.toInt()
    }

    fun isIdStart(c: Int): Boolean = when {
        c < 0x80 -> (c >= 'a'.code && c <= 'z'.code) || (c >= 'A'.code && c <= 'Z'.code) || c == '$'.code || c == '_'.code
        else -> UnicodeId.isIdStart(c)
    }

    fun isIdPart(c: Int): Boolean = when {
        c < 0x80 -> (c >= 'a'.code && c <= 'z'.code) || (c >= 'A'.code && c <= 'Z'.code) ||
                (c >= '0'.code && c <= '9'.code) || c == '$'.code || c == '_'.code
        c == 0x200C || c == 0x200D -> true
        else -> UnicodeId.isIdContinue(c)
    }

    fun hexVal(c: Int): Int = when (c) {
        in '0'.code..'9'.code -> c - '0'.code
        in 'a'.code..'f'.code -> c - 'a'.code + 10
        in 'A'.code..'F'.code -> c - 'A'.code + 10
        else -> -1
    }
}

/** Snapshot of the lexer state used for arbitrary lookahead. */
class LexState(
    val pos: Int, val line: Int, val lineStart: Int, val type: T, val start: Int, val end: Int,
    val tokLine: Int, val tokCol: Int, val nlBefore: Boolean, val value: Any?, val escaped: Boolean,
    val legacyOctalPos: Int, val raw: String?, val cooked: String?, val templateTail: Boolean,
    val regexFlags: String?, val prevEnd: Int, val prevLine: Int, val prevCol: Int, val invalidEscapePos: Int,
)

class Lexer(val src: String, val sourceName: String? = null, val isModule: Boolean = false) {
    var pos = 0
    var line = 1
    var lineStart = 0

    // current token
    var type: T = T.EOF
    var start = 0
    var end = 0
    var tokLine = 1
    var tokCol = 0
    var nlBefore = false
    var value: Any? = null
    var escaped = false
    /** Position of the first legacy octal literal/escape or \8 \9 escape in the current token, -1 if none. */
    var legacyOctalPos = -1
    /** Template raw text / cooked value (cooked is null when the template has an invalid escape). */
    var raw: String? = null
    var cooked: String? = null
    var templateTail = false
    var regexFlags: String? = null
    /** For templates: position of the invalid escape (for error reporting in untagged templates). */
    var invalidEscapePos = -1

    // previous token end, for ASI and node end positions
    var prevEnd = 0
    var prevLine = 1
    var prevCol = 0

    private val names = HashMap<String, String>()
    private val sb = StringBuilder()

    init {
        // hashbang
        if (src.startsWith("#!")) {
            pos = 2
            while (pos < src.length && !Chars.isLineTerminator(src[pos].code)) pos++
        }
    }

    fun save() = LexState(pos, line, lineStart, type, start, end, tokLine, tokCol, nlBefore, value, escaped,
        legacyOctalPos, raw, cooked, templateTail, regexFlags, prevEnd, prevLine, prevCol, invalidEscapePos)

    fun restore(s: LexState) {
        pos = s.pos; line = s.line; lineStart = s.lineStart; type = s.type; start = s.start; end = s.end
        tokLine = s.tokLine; tokCol = s.tokCol; nlBefore = s.nlBefore; value = s.value; escaped = s.escaped
        legacyOctalPos = s.legacyOctalPos; raw = s.raw; cooked = s.cooked; templateTail = s.templateTail
        regexFlags = s.regexFlags; prevEnd = s.prevEnd; prevLine = s.prevLine; prevCol = s.prevCol
        invalidEscapePos = s.invalidEscapePos
    }

    fun error(msg: String, at: Int = start): Nothing {
        val (l, c) = lineCol(at)
        throw JSSyntaxError(msg, at, l, c, sourceName)
    }

    fun lineCol(at: Int): Pair<Int, Int> {
        var l = 1
        var ls = 0
        var i = 0
        val lim = minOf(at, src.length)
        while (i < lim) {
            val c = src[i].code
            if (c == 0x0A || c == 0x2028 || c == 0x2029) { l++; ls = i + 1 }
            else if (c == 0x0D) {
                if (i + 1 < lim && src[i + 1].code == 0x0A) i++
                l++; ls = i + 1
            }
            i++
        }
        return l to (at - ls + 1)
    }

    private fun cur(): Int = if (pos < src.length) src[pos].code else -1
    private fun peekc(off: Int = 1): Int = if (pos + off < src.length) src[pos + off].code else -1

    private fun codePointAt(i: Int): Int {
        val c = src[i]
        if (c.isHighSurrogate() && i + 1 < src.length && src[i + 1].isLowSurrogate()) {
            return Character.toCodePoint(c, src[i + 1])
        }
        return c.code
    }

    private fun newline(c: Int) {
        // pos points at the line terminator
        if (c == 0x0D && peekc() == 0x0A) pos++
        pos++
        line++
        lineStart = pos
    }

    /** Skips whitespace and comments, sets nlBefore. */
    private fun skipSpace() {
        val atStart = pos == 0 || (src.startsWith("#!") && start == 0 && type == T.EOF && prevEnd == 0)
        var sawOnlyTrivia = atStart
        while (pos < src.length) {
            val c = src[pos].code
            when {
                c == 0x0A || c == 0x0D || c == 0x2028 || c == 0x2029 -> { newline(c); nlBefore = true }
                c == 0x20 || c == 0x09 -> pos++
                c == '/'.code -> {
                    val n = peekc()
                    if (n == '/'.code) {
                        pos += 2
                        skipLineComment()
                    } else if (n == '*'.code) {
                        val cs = pos
                        pos += 2
                        var closed = false
                        while (pos < src.length) {
                            val d = src[pos].code
                            if (d == '*'.code && peekc() == '/'.code) { pos += 2; closed = true; break }
                            if (Chars.isLineTerminator(d)) { newline(d); nlBefore = true } else pos++
                        }
                        if (!closed) error("Unterminated comment", cs)
                    } else return
                }
                c == '<'.code && !isModule && src.startsWith("<!--", pos) -> { pos += 4; skipLineComment() }
                c == '-'.code && !isModule && (nlBefore || sawOnlyTrivia) && src.startsWith("-->", pos) -> {
                    pos += 3; skipLineComment()
                }
                c > 0x7F && Chars.isWhitespace(c) -> pos++
                c == 0x0B || c == 0x0C -> pos++
                else -> return
            }
        }
    }

    private fun skipLineComment() {
        while (pos < src.length && !Chars.isLineTerminator(src[pos].code)) pos++
    }

    /** Advances to the next token. */
    fun next() {
        prevEnd = end
        prevLine = tokLine
        prevCol = tokCol
        nlBefore = false
        skipSpace()
        start = pos
        tokLine = line
        tokCol = pos - lineStart
        value = null
        escaped = false
        legacyOctalPos = -1
        if (pos >= src.length) {
            type = T.EOF
            end = pos
            return
        }
        readToken()
        end = pos
    }

    private fun punct(t: T, len: Int) {
        type = t
        pos += len
    }

    private fun readToken() {
        val c = src[pos].code
        when (c) {
            '('.code -> punct(T.LPAREN, 1)
            ')'.code -> punct(T.RPAREN, 1)
            '{'.code -> punct(T.LBRACE, 1)
            '}'.code -> punct(T.RBRACE, 1)
            '['.code -> punct(T.LBRACKET, 1)
            ']'.code -> punct(T.RBRACKET, 1)
            ';'.code -> punct(T.SEMI, 1)
            ','.code -> punct(T.COMMA, 1)
            ':'.code -> punct(T.COLON, 1)
            '~'.code -> punct(T.TILDE, 1)
            '@'.code -> punct(T.AT, 1)
            '.'.code -> {
                val n = peekc()
                if (n >= '0'.code && n <= '9'.code) readNumber()
                else if (n == '.'.code && peekc(2) == '.'.code) punct(T.ELLIPSIS, 3)
                else punct(T.DOT, 1)
            }
            '?'.code -> {
                val n = peekc()
                if (n == '?'.code) { if (peekc(2) == '='.code) punct(T.NULLISH_ASSIGN, 3) else punct(T.NULLISH, 2) }
                else if (n == '.'.code && !(peekc(2) >= '0'.code && peekc(2) <= '9'.code)) punct(T.QDOT, 2)
                else punct(T.QUESTION, 1)
            }
            '='.code -> {
                val n = peekc()
                if (n == '>'.code) punct(T.ARROW, 2)
                else if (n == '='.code) { if (peekc(2) == '='.code) punct(T.SEQ, 3) else punct(T.EQ, 2) }
                else punct(T.ASSIGN, 1)
            }
            '!'.code -> if (peekc() == '='.code) { if (peekc(2) == '='.code) punct(T.SNE, 3) else punct(T.NE, 2) } else punct(T.BANG, 1)
            '+'.code -> when (peekc()) { '+'.code -> punct(T.INC, 2); '='.code -> punct(T.PLUS_ASSIGN, 2); else -> punct(T.PLUS, 1) }
            '-'.code -> when (peekc()) { '-'.code -> punct(T.DEC, 2); '='.code -> punct(T.MINUS_ASSIGN, 2); else -> punct(T.MINUS, 1) }
            '*'.code -> if (peekc() == '*'.code) { if (peekc(2) == '='.code) punct(T.STARSTAR_ASSIGN, 3) else punct(T.STARSTAR, 2) }
                else if (peekc() == '='.code) punct(T.STAR_ASSIGN, 2) else punct(T.STAR, 1)
            '/'.code -> if (peekc() == '='.code) punct(T.SLASH_ASSIGN, 2) else punct(T.SLASH, 1)
            '%'.code -> if (peekc() == '='.code) punct(T.PERCENT_ASSIGN, 2) else punct(T.PERCENT, 1)
            '^'.code -> if (peekc() == '='.code) punct(T.CARET_ASSIGN, 2) else punct(T.CARET, 1)
            '&'.code -> when (peekc()) {
                '&'.code -> if (peekc(2) == '='.code) punct(T.AND_ASSIGN, 3) else punct(T.AND, 2)
                '='.code -> punct(T.AMP_ASSIGN, 2)
                else -> punct(T.AMP, 1)
            }
            '|'.code -> when (peekc()) {
                '|'.code -> if (peekc(2) == '='.code) punct(T.OR_ASSIGN, 3) else punct(T.OR, 2)
                '='.code -> punct(T.BAR_ASSIGN, 2)
                else -> punct(T.BAR, 1)
            }
            '<'.code -> when (peekc()) {
                '<'.code -> if (peekc(2) == '='.code) punct(T.SHL_ASSIGN, 3) else punct(T.SHL, 2)
                '='.code -> punct(T.LE, 2)
                else -> punct(T.LT, 1)
            }
            '>'.code -> when (peekc()) {
                '>'.code -> when (peekc(2)) {
                    '>'.code -> if (peekc(3) == '='.code) punct(T.SHR_ASSIGN, 4) else punct(T.SHR, 3)
                    '='.code -> punct(T.SAR_ASSIGN, 3)
                    else -> punct(T.SAR, 2)
                }
                '='.code -> punct(T.GE, 2)
                else -> punct(T.GT, 1)
            }
            '"'.code, '\''.code -> readString(c)
            '`'.code -> { pos++; readTemplate() }
            '#'.code -> {
                pos++
                if (pos < src.length && (Chars.isIdStart(codePointAt(pos)) || src[pos] == '\\')) {
                    val name = readIdentifierName()
                    type = T.PRIVATE_NAME
                    value = name
                } else error("Invalid or unexpected token '#'", pos - 1)
            }
            else -> {
                if (c >= '0'.code && c <= '9'.code) readNumber()
                else {
                    val cp = codePointAt(pos)
                    if (Chars.isIdStart(cp) || c == '\\'.code) {
                        value = readIdentifierName()
                        type = T.NAME
                    } else error("Invalid or unexpected token", pos)
                }
            }
        }
    }

    private fun intern(s: String): String = names.getOrPut(s) { s }

    /** Reads an IdentifierName starting at pos; handles unicode escapes. Sets [escaped]. */
    private fun readIdentifierName(): String {
        var simpleStart = pos
        var b: StringBuilder? = null
        var first = true
        while (pos < src.length) {
            val c = src[pos].code
            if (c == '\\'.code) {
                if (b == null) b = StringBuilder()
                b.append(src, simpleStart, pos)
                val escPos = pos
                if (peekc() != 'u'.code) error("Invalid Unicode escape sequence", escPos)
                pos += 2
                val cp = readUnicodeEscapeBody(escPos)
                if (if (first) !Chars.isIdStart(cp) else !Chars.isIdPart(cp)) error("Invalid Unicode escape sequence", escPos)
                b.appendCodePoint(cp)
                escaped = true
                simpleStart = pos
            } else {
                val cp = codePointAt(pos)
                if (if (first) !Chars.isIdStart(cp) else !Chars.isIdPart(cp)) break
                pos += Character.charCount(cp)
            }
            first = false
        }
        val s = if (b == null) src.substring(simpleStart, pos) else { b.append(src, simpleStart, pos); b.toString() }
        return intern(s)
    }

    /** After "\\u" consumed. Returns code point. */
    private fun readUnicodeEscapeBody(escPos: Int): Int {
        if (cur() == '{'.code) {
            pos++
            var v = 0
            var digits = 0
            while (true) {
                val c = cur()
                if (c == '}'.code) break
                val h = Chars.hexVal(c)
                if (h < 0) error("Invalid Unicode escape sequence", escPos)
                v = v * 16 + h
                if (v > 0x10FFFF) error("Undefined Unicode code-point", escPos)
                digits++
                pos++
            }
            if (digits == 0) error("Invalid Unicode escape sequence", escPos)
            pos++
            return v
        }
        var v = 0
        for (i in 0 until 4) {
            val h = Chars.hexVal(cur())
            if (h < 0) error("Invalid Unicode escape sequence", escPos)
            v = v * 16 + h
            pos++
        }
        return v
    }

    private fun isDigitIn(c: Int, radix: Int): Boolean = when (radix) {
        2 -> c == '0'.code || c == '1'.code
        8 -> c >= '0'.code && c <= '7'.code
        10 -> c >= '0'.code && c <= '9'.code
        else -> Chars.hexVal(c) >= 0
    }

    /** Reads digits with optional numeric separators into sb. Returns number of digits read. */
    private fun readDigits(radix: Int, allowSep: Boolean): Int {
        var n = 0
        var lastSep = false
        while (pos < src.length) {
            val c = src[pos].code
            if (c == '_'.code) {
                if (!allowSep) error("Numeric separators are not allowed here", pos)
                if (n == 0 || lastSep) error("Numeric separators are not allowed here", pos)
                lastSep = true
                pos++
                continue
            }
            if (!isDigitIn(c, radix)) break
            sb.append(c.toChar())
            lastSep = false
            n++
            pos++
        }
        if (lastSep) error("Numeric separators are not allowed at the end of numeric literals", pos - 1)
        return n
    }

    private fun readNumber() {
        sb.setLength(0)
        val numStart = pos
        val c = src[pos].code
        if (c == '0'.code) {
            val n = peekc() or 0x20
            val radix = when (n) { 'x'.code -> 16; 'o'.code -> 8; 'b'.code -> 2; else -> 0 }
            if (radix != 0) {
                pos += 2
                if (readDigits(radix, true) == 0) error("Invalid or unexpected token", numStart)
                if (cur() == 'n'.code) {
                    pos++
                    type = T.BIGINT
                    value = BigInteger(sb.toString(), radix)
                } else {
                    type = T.NUM
                    value = BigInteger(sb.toString(), radix).toDouble()
                }
                checkAfterNumber()
                return
            }
            val d = peekc()
            if (d >= '0'.code && d <= '9'.code || d == '_'.code) {
                // legacy octal or NonOctalDecimalIntegerLiteral
                legacyOctalPos = numStart
                pos++
                if (cur() == '_'.code) error("Numeric separator can not be used after leading 0", pos)
                var octal = true
                while (pos < src.length) {
                    val e = src[pos].code
                    if (e == '_'.code) error("Numeric separators are not allowed here", pos)
                    if (e < '0'.code || e > '9'.code) break
                    if (e >= '8'.code) octal = false
                    sb.append(e.toChar())
                    pos++
                }
                if (octal) {
                    if (cur() == 'n'.code) error("Invalid BigInt literal", numStart)
                    type = T.NUM
                    value = BigInteger(sb.toString(), 8).toDouble()
                    checkAfterNumber()
                    return
                }
                // NonOctalDecimalIntegerLiteral: may have fraction/exponent
                if (cur() == 'n'.code) error("Invalid BigInt literal", numStart)
                readFractionAndExponent(numStart, allowSep = false)
                return
            }
        }
        if (c != '.'.code) {
            readDigits(10, true)
            if (cur() == 'n'.code) {
                pos++
                type = T.BIGINT
                value = BigInteger(sb.toString())
                checkAfterNumber()
                return
            }
        }
        readFractionAndExponent(numStart, allowSep = true)
    }

    private fun readFractionAndExponent(numStart: Int, allowSep: Boolean) {
        if (cur() == '.'.code) {
            sb.append('.')
            pos++
            if (cur() == '_'.code) error("Numeric separators are not allowed here", pos)
            readDigits(10, allowSep)
        }
        val e = cur()
        if (e == 'e'.code || e == 'E'.code) {
            sb.append('e')
            pos++
            val s = cur()
            if (s == '+'.code || s == '-'.code) { sb.append(s.toChar()); pos++ }
            if (cur() == '_'.code) error("Numeric separators are not allowed here", pos)
            if (readDigits(10, allowSep) == 0) error("Invalid or unexpected token", numStart)
        }
        if (cur() == 'n'.code) error("Invalid BigInt literal", numStart)
        type = T.NUM
        val s = sb.toString()
        value = if (s == ".") 0.0 else java.lang.Double.parseDouble(if (s.startsWith(".")) "0$s" else s)
        checkAfterNumber()
    }

    private fun checkAfterNumber() {
        if (pos < src.length) {
            val cp = codePointAt(pos)
            if (Chars.isIdStart(cp) || (cp >= '0'.code && cp <= '9'.code) || cp == '\\'.code)
                error("Invalid or unexpected token", pos)
        }
    }

    private fun readString(quote: Int) {
        pos++
        sb.setLength(0)
        var chunk = pos
        while (true) {
            if (pos >= src.length) error("Unterminated string constant", start)
            val c = src[pos].code
            if (c == quote) {
                sb.append(src, chunk, pos)
                pos++
                break
            }
            if (c == '\\'.code) {
                sb.append(src, chunk, pos)
                readEscape(false)
                chunk = pos
                continue
            }
            if (c == 0x0A || c == 0x0D) error("Unterminated string constant", start)
            pos++
        }
        type = T.STRING
        value = sb.toString()
    }

    /**
     * Reads an escape sequence at pos (pointing at backslash) and appends the result to sb.
     * In templates, returns false for invalid escapes (and does not throw).
     */
    private fun readEscape(inTemplate: Boolean): Boolean {
        val escPos = pos
        pos++
        if (pos >= src.length) error("Unterminated string constant", start)
        val c = src[pos].code
        when (c) {
            'n'.code -> { sb.append('\n'); pos++ }
            't'.code -> { sb.append('\t'); pos++ }
            'r'.code -> { sb.append('\r'); pos++ }
            'b'.code -> { sb.append('\b'); pos++ }
            'f'.code -> { sb.append('\u000C'); pos++ }
            'v'.code -> { sb.append('\u000B'); pos++ }
            0x0D -> { pos++; if (cur() == 0x0A) pos++; line++; lineStart = pos }
            0x0A, 0x2028, 0x2029 -> { pos++; line++; lineStart = pos }
            'x'.code -> {
                pos++
                val h1 = Chars.hexVal(cur())
                val h2 = Chars.hexVal(peekc())
                if (h1 < 0 || h2 < 0) {
                    if (inTemplate) return false
                    error("Invalid hexadecimal escape sequence", escPos)
                }
                pos += 2
                sb.append((h1 * 16 + h2).toChar())
            }
            'u'.code -> {
                pos++
                if (inTemplate) {
                    val save = pos
                    val cp = tryReadUnicodeEscapeBody()
                    if (cp < 0) { pos = save; return false }
                    sb.appendCodePoint(cp)
                } else {
                    sb.appendCodePoint(readUnicodeEscapeBody(escPos))
                }
            }
            in '0'.code..'7'.code -> {
                if (c == '0'.code && !(peekc() >= '0'.code && peekc() <= '9'.code)) {
                    sb.append('\u0000'); pos++
                } else {
                    if (inTemplate) return false
                    if (legacyOctalPos < 0) legacyOctalPos = escPos
                    var v = c - '0'.code
                    pos++
                    val d1 = cur()
                    if (d1 >= '0'.code && d1 <= '7'.code) {
                        v = v * 8 + (d1 - '0'.code); pos++
                        val d2 = cur()
                        if (c <= '3'.code && d2 >= '0'.code && d2 <= '7'.code) { v = v * 8 + (d2 - '0'.code); pos++ }
                    }
                    sb.append(v.toChar())
                }
            }
            '8'.code, '9'.code -> {
                if (inTemplate) return false
                if (legacyOctalPos < 0) legacyOctalPos = escPos
                sb.append(c.toChar()); pos++
            }
            else -> {
                val cp = codePointAt(pos)
                sb.appendCodePoint(cp)
                pos += Character.charCount(cp)
            }
        }
        return true
    }

    private fun tryReadUnicodeEscapeBody(): Int {
        if (cur() == '{'.code) {
            pos++
            var v = 0
            var digits = 0
            while (true) {
                val c = cur()
                if (c == '}'.code) break
                val h = Chars.hexVal(c)
                if (h < 0) return -1
                v = v * 16 + h
                if (v > 0x10FFFF) return -1
                digits++
                pos++
            }
            if (digits == 0) return -1
            pos++
            return v
        }
        var v = 0
        for (i in 0 until 4) {
            val h = Chars.hexVal(cur())
            if (h < 0) return -1
            v = v * 16 + h
            pos++
        }
        return v
    }

    /** Reads template characters after "`" or "}" until "${" or "`". */
    private fun readTemplate() {
        sb.setLength(0)
        val rawSb = StringBuilder()
        var valid = true
        invalidEscapePos = -1
        var chunk = pos
        while (true) {
            if (pos >= src.length) error("Unterminated template literal", start)
            val c = src[pos].code
            if (c == '`'.code) {
                appendTemplateChunk(chunk, pos, rawSb, valid)
                pos++
                templateTail = true
                break
            }
            if (c == '$'.code && peekc() == '{'.code) {
                appendTemplateChunk(chunk, pos, rawSb, valid)
                pos += 2
                templateTail = false
                break
            }
            if (c == '\\'.code) {
                appendTemplateChunk(chunk, pos, rawSb, valid)
                val escStart = pos
                val ok = readEscape(true)
                if (!ok) {
                    if (valid) invalidEscapePos = escStart
                    valid = false
                    // skip the remainder of the bad escape: only the escape char itself; following chars are normal
                    pos = escStart + 2
                }
                appendRaw(rawSb, escStart, pos)
                chunk = pos
                continue
            }
            if (c == 0x0D || c == 0x0A || c == 0x2028 || c == 0x2029) {
                appendTemplateChunk(chunk, pos, rawSb, valid)
                if (c == 0x0D) {
                    pos++
                    if (cur() == 0x0A) pos++
                    if (valid) sb.append('\n')
                    rawSb.append('\n')
                } else {
                    pos++
                    if (valid) sb.append(c.toChar())
                    rawSb.append(c.toChar())
                }
                line++
                lineStart = pos
                chunk = pos
                continue
            }
            pos++
        }
        type = T.TEMPLATE
        raw = rawSb.toString()
        cooked = if (valid) sb.toString() else null
    }

    private fun appendTemplateChunk(from: Int, to: Int, rawSb: StringBuilder, valid: Boolean) {
        if (to > from) {
            if (valid) sb.append(src, from, to)
            rawSb.append(src, from, to)
        }
    }

    private fun appendRaw(rawSb: StringBuilder, from: Int, to: Int) {
        var i = from
        while (i < to) {
            val ch = src[i]
            if (ch == '\r') {
                rawSb.append('\n')
                if (i + 1 < to && src[i + 1] == '\n') i++
            } else rawSb.append(ch)
            i++
        }
    }

    /** Called by the parser when it sees '}' that closes a template substitution. */
    fun rescanTemplateContinuation() {
        // current token must be RBRACE starting at start
        pos = start + 1
        readTemplate()
        end = pos
    }

    /** Called by the parser when a '/' or '/=' token starts a primary expression. */
    fun rescanRegExp() {
        pos = start + 1
        var inClass = false
        val bodyStart = pos
        while (true) {
            if (pos >= src.length) error("Invalid regular expression: missing /", start)
            val c = src[pos].code
            if (Chars.isLineTerminator(c)) error("Invalid regular expression: missing /", start)
            if (c == '\\'.code) {
                pos++
                if (pos >= src.length || Chars.isLineTerminator(src[pos].code)) error("Invalid regular expression: missing /", start)
                pos++
                continue
            }
            if (c == '['.code) inClass = true
            else if (c == ']'.code) inClass = false
            else if (c == '/'.code && !inClass) break
            pos++
        }
        val body = src.substring(bodyStart, pos)
        pos++
        val flagsStart = pos
        while (pos < src.length) {
            val cp = codePointAt(pos)
            if (cp == '\\'.code) error("Invalid regular expression flags", pos)
            if (!Chars.isIdPart(cp)) break
            pos += Character.charCount(cp)
        }
        type = T.REGEXP
        value = body
        regexFlags = src.substring(flagsStart, pos)
        end = pos
    }
}
