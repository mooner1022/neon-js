package dev.mooner.neonpython

/**
 * `_tokenize` (docs/PYTHON_PLATFORM.md §12), as CPython 3.14 has it: TokenizerIter (Python/Python-tokenize.c), what
 * tokenize.py's generate_tokens and tokenize stand on (and so inspect's getsource, linecache's), over CPython's lexer
 * (Parser/lexer/lexer.c, state.c, Parser/tokenizer/readline_tokenizer.c, helpers.c, Parser/token.c) ported as it is.
 * It is not NeonPy's own lexer ([PyLexer], the parser's): that one gives no COMMENT, NL nor CPython's positions.
 *
 * As CPython's, it reads the source's UTF-8 bytes: a buffer of the lines readline gave (one line, or the lines of a
 * token or an f-string still open), the C's pointers indices in it (-1 for NULL); columns in characters are
 * TokenizerIter's, from bytes, with its cache of the current line (and its quirks: a comment the parser's mode skips
 * counts in bytes after it). Its errors are CPython's, their offsets too (some in bytes, as CPython's).
 *
 * Where it departs from CPython: the text of an f-string's `=` expression (the token's metadata, the parser's only)
 * is not kept; type comments and the interactive prompt are not this tokenizer's in CPython either. Identifiers are
 * checked (the parser's mode) by str.isidentifier's rules (Java's identifier classes, close to XID). A str with a
 * lone surrogate raises UnicodeEncodeError with its message only (NeonPy's UnicodeErrors have no fields).
 */
object PyTokenize {
    // Lib/token.py's numbers
    const val ENDMARKER = 0
    const val NAME = 1
    const val NUMBER = 2
    const val STRING = 3
    const val NEWLINE = 4
    const val INDENT = 5
    const val DEDENT = 6
    const val LPAR = 7
    const val RPAR = 8
    const val LSQB = 9
    const val RSQB = 10
    const val COLON = 11
    const val COMMA = 12
    const val SEMI = 13
    const val PLUS = 14
    const val MINUS = 15
    const val STAR = 16
    const val SLASH = 17
    const val VBAR = 18
    const val AMPER = 19
    const val LESS = 20
    const val GREATER = 21
    const val EQUAL = 22
    const val DOT = 23
    const val PERCENT = 24
    const val LBRACE = 25
    const val RBRACE = 26
    const val EQEQUAL = 27
    const val NOTEQUAL = 28
    const val LESSEQUAL = 29
    const val GREATEREQUAL = 30
    const val TILDE = 31
    const val CIRCUMFLEX = 32
    const val LEFTSHIFT = 33
    const val RIGHTSHIFT = 34
    const val DOUBLESTAR = 35
    const val PLUSEQUAL = 36
    const val MINEQUAL = 37
    const val STAREQUAL = 38
    const val SLASHEQUAL = 39
    const val PERCENTEQUAL = 40
    const val AMPEREQUAL = 41
    const val VBAREQUAL = 42
    const val CIRCUMFLEXEQUAL = 43
    const val LEFTSHIFTEQUAL = 44
    const val RIGHTSHIFTEQUAL = 45
    const val DOUBLESTAREQUAL = 46
    const val DOUBLESLASH = 47
    const val DOUBLESLASHEQUAL = 48
    const val AT = 49
    const val ATEQUAL = 50
    const val RARROW = 51
    const val ELLIPSIS = 52
    const val COLONEQUAL = 53
    const val EXCLAMATION = 54
    const val OP = 55
    const val FSTRING_START = 59
    const val FSTRING_MIDDLE = 60
    const val FSTRING_END = 61
    const val TSTRING_START = 62
    const val TSTRING_MIDDLE = 63
    const val TSTRING_END = 64
    const val COMMENT = 65
    const val NL = 66
    const val ERRORTOKEN = 67

    private val NO_ARGS = arrayOfNulls<Any?>(0)

    /** _tokenize's one name: TokenizerIter(readline, /, *, extra_tokens, encoding='utf-8') (no encoding: str lines). */
    @JvmStatic
    fun fill(ctx: PyContext, d: PyDict) {
        val ty = PyTypes.newBuiltin("TokenizerIter", subclassable = false)
        ty.module = "_tokenize"
        ty.tpName = "_tokenize.TokenizerIter"
        ty.newImpl = { c, _, a, kw -> newIter(c, ty, a, kw?.takeIf { it.size > 0 }) }
        d[PyStr("TokenizerIter")] = ty
    }

    /** tokenizeriter_new, its arguments as Argument Clinic takes them (CPython's messages, its name tokenizeriter). */
    private fun newIter(ctx: PyContext, ty: PyType, a: Array<Any?>, kw: PyDict?): PyIterator {
        if (a.size != 1) throw PyRt.typeError("tokenizeriter() takes exactly 1 positional argument (${a.size} given)")
        var extra: Any? = null
        var encoding: Any? = null
        var unexpected: String? = null
        if (kw != null) for ((k, v) in kw.map) {
            when (val n = (k.v as PyStr).s) {
                "extra_tokens" -> extra = v
                "encoding" -> encoding = v
                else -> if (unexpected == null) unexpected = n
            }
        }
        if (extra == null) throw PyRt.typeError("tokenizeriter() missing required argument 'extra_tokens' (pos 2)")
        if (unexpected != null) throw PyRt.typeError("tokenizeriter() got an unexpected keyword argument '$unexpected'")
        val extraTokens = PyRt.truthy(extra)
        val enc = when (encoding) {
            null -> null
            is PyStr -> encoding.s.also { if (it.indexOf('\u0000') >= 0) throw PyException("ValueError", "embedded null character") }
            // (_PyArg_BadArgument's: None by its name)
            else -> throw PyRt.typeError("tokenizeriter() argument 'encoding' must be str, not ${if (encoding === PyNone) "None" else PyTypes.name(encoding)}")
        }
        val it = TokenizerIter(Lexer(ctx, a[0], enc, extraTokens))
        return PyIterator { it.next() }.also { i -> i.type = ty }
    }

    // ------------------------------------------------------------------ TokenizerIter

    /** Python-tokenize.c's tokenizeriterobject: the lexer, and the line its last token was on (bytes and str). */
    private class TokenizerIter(@JvmField val tok: Lexer) {
        var done = false
        var lastLine: PyStr? = null
        var lastLineBytes = ByteArray(0)
        var lastLineno = 0
        var byteColOffsetDiff = 0

        /** tokenizeriter_next */
        fun next(): Any? {
            val tok = tok
            var type = tok.get()
            if (type == ERRORTOKEN) {
                // (raised, the C's exception is no longer set: a next call's error is the state's)
                val e = tok.error ?: tokenizerError()
                tok.error = null
                throw e
            }
            if (done) return PyIterator.STOP
            if (type == ENDMARKER) done = true
            val str0 = if (tok.tokStart < 0 || tok.tokEnd < 0) "" else tok.decodeStrict(tok.tokStart, tok.tokEnd)
            var str: String = str0
            val trailing = type == ENDMARKER || (type == DEDENT && tok.done == E_EOF)
            val stringLit = type == STRING || type == FSTRING_MIDDLE || type == TSTRING_MIDDLE
            val lineStart = if (stringLit) tok.multiLineStart else tok.lineStart
            val line: PyStr
            val lineBytes: ByteArray
            var lineChanged = true
            if (tok.extra && trailing) {
                line = PyStr.EMPTY
                lineBytes = ByteArray(0)
            } else {
                var size = tok.inp - lineStart
                if (size >= 1 && tok.implicitNewline) size -= 1
                // _get_current_line
                if (tok.lineno != lastLineno) {
                    lineBytes = tok.buf.copyOfRange(lineStart, lineStart + size)
                    line = PyStr(tok.decodeReplace(lineBytes, 0, lineBytes.size))
                    lastLine = line
                    lastLineBytes = lineBytes
                    byteColOffsetDiff = 0
                } else {
                    // (no line read yet: CPython's NULL line, the iteration's end)
                    line = lastLine ?: return PyIterator.STOP
                    lineBytes = lastLineBytes
                    lineChanged = false
                }
            }
            var lineno = if (stringLit) tok.firstLineno else tok.lineno
            var endLineno = tok.lineno
            var col = -1
            var endCol = -1
            // _get_col_offsets
            var byteOffset = -1
            if (tok.tokStart >= 0 && tok.tokStart >= lineStart) {
                byteOffset = tok.tokStart - lineStart
                if (lineChanged) {
                    col = charOffsetLine(lineBytes, 0, byteOffset)
                    byteColOffsetDiff = byteOffset - col
                } else {
                    col = byteOffset - byteColOffsetDiff
                }
            }
            if (tok.tokEnd >= 0 && tok.tokEnd >= tok.lineStart) {
                val endByteOffset = tok.tokEnd - tok.lineStart
                if (lineno == endLineno) {
                    val tokenCol = charOffsetLine(lineBytes, maxOf(byteOffset, 0), endByteOffset)
                    endCol = col + tokenCol
                    byteColOffsetDiff += tok.tokEnd - tok.tokStart - tokenCol
                } else {
                    endCol = tok.charOffsetRaw(tok.lineStart, endByteOffset)
                    byteColOffsetDiff += endByteOffset - endCol
                }
            }
            lastLineno = lineno
            if (tok.extra) {
                if (trailing) {
                    lineno = lineno + 1
                    endLineno = lineno
                    col = 0
                    endCol = 0
                }
                // (as tokenize.py's tokens were: an operator's type OP, a newline's text the newline)
                if (type in (DEDENT + 1) until OP) {
                    type = OP
                } else if (type == NEWLINE) {
                    // (where the newline was made up, the token's own text: '', or '\r')
                    if (!tok.implicitNewline) str = if (tok.buf[tok.start] == '\r'.code.toByte()) "\r\n" else "\n"
                    endCol++
                } else if (type == NL) {
                    if (tok.implicitNewline) str = ""
                }
            }
            return PyTuple(arrayOf(type.toLong(), PyStr(str), PyTuple(arrayOf(lineno.toLong(), col.toLong())),
                PyTuple(arrayOf(endLineno.toLong(), endCol.toLong())), line))
        }

        /** _tokenizer_error: the error of the lexer's state (no exception raised yet). */
        private fun tokenizerError(): PyException {
            val tok = tok
            var errtype = "SyntaxError"
            val msg = when (tok.done) {
                E_TOKEN -> "invalid token"
                E_EOF -> {
                    // (PyErr_SyntaxLocationObject: the location's fields, the arguments the message only)
                    val m = "unexpected EOF in multi-line statement"
                    return PyException("SyntaxError", m).also {
                        it.attrs = mapOf("filename" to PyStr("<string>"), "lineno" to tok.lineno.toLong(), "offset" to maxOf(tok.inp, 0).toLong(),
                            "text" to PyNone, "end_lineno" to tok.lineno.toLong(), "end_offset" to PyNone)
                    }
                }
                E_DEDENT -> { errtype = "IndentationError"; "unindent does not match any outer indentation level" }
                E_TABSPACE -> { errtype = "TabError"; "inconsistent use of tabs and spaces in indentation" }
                E_TOODEEP -> { errtype = "IndentationError"; "too many levels of indentation" }
                E_LINECONT -> "unexpected character after line continuation character"
                else -> "unknown tokenization error"
            }
            // (the buffer's text, its newline left out; the offset just past its end)
            val size = maxOf(tok.inp - 1, 0)
            val errorLine = tok.decodeReplace(tok.buf, 0, size)
            val offset = tok.charOffsetRaw(0, tok.inp, errorLineLen = size)
            return PyException(errtype, msg).also {
                it.args = arrayOf(PyStr(msg), PyTuple(arrayOf(PyStr("<string>"), tok.lineno.toLong(), offset.toLong(), PyStr(errorLine), PyNone, PyNone)))
            }
        }
    }

    /** _PyPegen_byte_offset_to_character_offset_line: the characters of [data]'s bytes [from], [to] (by lead bytes). */
    private fun charOffsetLine(data: ByteArray, from: Int, to: Int): Int {
        var i = from
        var len = 0
        while (i < to) {
            // (past the line's end, its NUL)
            val ch = if (i < data.size) data[i].toInt() and 0xFF else 0
            i += when {
                ch < 0x80 -> 1
                ch and 0xE0 == 0xC0 -> 2
                ch and 0xF0 == 0xE0 -> 3
                ch and 0xF8 == 0xF0 -> 4
                else -> return -1
            }
            len++
        }
        return len
    }

    // ------------------------------------------------------------------ the lexer

    // errcode.h's
    private const val E_OK = 10
    private const val E_EOF = 11
    private const val E_TOKEN = 13
    private const val E_ERROR = 17
    private const val E_TABSPACE = 18
    private const val E_TOODEEP = 20
    private const val E_DEDENT = 21
    private const val E_DECODE = 22
    private const val E_EOFS = 23
    private const val E_EOLS = 24
    private const val E_LINECONT = 25

    private const val EOF = -1
    private const val NULL = -1
    private const val TABSIZE = 8
    private const val ALTTABSIZE = 1
    private const val MAXINDENT = 100
    private const val MAXLEVEL = 200
    private const val MAXFSTRINGLEVEL = 150
    private const val MAX_EXPR_NESTING = 3

    private const val REGULAR_MODE = 0
    private const val FSTRING_MODE = 1

    /** state.h's tokenizer_mode: the regular mode's, or an f-string's (t-string's) being read. */
    private class Mode(@JvmField var kind: Int) {
        @JvmField var curlyBracketDepth = 0
        @JvmField var curlyBracketExprStartDepth = 0
        @JvmField var quote = 0
        @JvmField var quoteSize = 0
        @JvmField var raw = false
        @JvmField var start = NULL
        @JvmField var multiLineStart = NULL
        @JvmField var firstLine = 0
        @JvmField var inDebug = false
        @JvmField var inFormatSpec = false
        @JvmField var tstring = false
    }

    private fun isDigit(c: Int) = c >= '0'.code && c <= '9'.code
    private fun isXDigit(c: Int) = isDigit(c) || (c >= 'a'.code && c <= 'f'.code) || (c >= 'A'.code && c <= 'F'.code)
    private fun isPotentialIdentifierStart(c: Int) =
        (c >= 'a'.code && c <= 'z'.code) || (c >= 'A'.code && c <= 'Z'.code) || c == '_'.code || c >= 128
    private fun isPotentialIdentifierChar(c: Int) = isPotentialIdentifierStart(c) || isDigit(c)
    private fun lower(c: Int) = if (c >= 'A'.code && c <= 'Z'.code) c + 32 else c
    private fun hex4(c: Int) = Integer.toHexString(c).uppercase().padStart(4, '0')

    /** Parser/token.c's _PyToken_OneChar */
    private fun oneChar(c1: Int): Int = when (c1.toChar()) {
        '!' -> EXCLAMATION; '%' -> PERCENT; '&' -> AMPER; '(' -> LPAR; ')' -> RPAR; '*' -> STAR; '+' -> PLUS
        ',' -> COMMA; '-' -> MINUS; '.' -> DOT; '/' -> SLASH; ':' -> COLON; ';' -> SEMI; '<' -> LESS; '=' -> EQUAL
        '>' -> GREATER; '@' -> AT; '[' -> LSQB; ']' -> RSQB; '^' -> CIRCUMFLEX; '{' -> LBRACE; '|' -> VBAR
        '}' -> RBRACE; '~' -> TILDE
        else -> OP
    }

    /** _PyToken_TwoChars */
    private fun twoChars(c1: Int, c2: Int): Int {
        val b = c2.toChar()
        return when (c1.toChar()) {
            '!' -> if (b == '=') NOTEQUAL else OP
            '%' -> if (b == '=') PERCENTEQUAL else OP
            '&' -> if (b == '=') AMPEREQUAL else OP
            '*' -> when (b) { '*' -> DOUBLESTAR; '=' -> STAREQUAL; else -> OP }
            '+' -> if (b == '=') PLUSEQUAL else OP
            '-' -> when (b) { '=' -> MINEQUAL; '>' -> RARROW; else -> OP }
            '/' -> when (b) { '/' -> DOUBLESLASH; '=' -> SLASHEQUAL; else -> OP }
            ':' -> if (b == '=') COLONEQUAL else OP
            '<' -> when (b) { '<' -> LEFTSHIFT; '=' -> LESSEQUAL; '>' -> NOTEQUAL; else -> OP }
            '=' -> if (b == '=') EQEQUAL else OP
            '>' -> when (b) { '=' -> GREATEREQUAL; '>' -> RIGHTSHIFT; else -> OP }
            '@' -> if (b == '=') ATEQUAL else OP
            '^' -> if (b == '=') CIRCUMFLEXEQUAL else OP
            '|' -> if (b == '=') VBAREQUAL else OP
            else -> OP
        }
    }

    /** _PyToken_ThreeChars */
    private fun threeChars(c1: Int, c2: Int, c3: Int): Int = when {
        c1 == '*'.code && c2 == '*'.code && c3 == '='.code -> DOUBLESTAREQUAL
        c1 == '.'.code && c2 == '.'.code && c3 == '.'.code -> ELLIPSIS
        c1 == '/'.code && c2 == '/'.code && c3 == '='.code -> DOUBLESLASHEQUAL
        c1 == '<'.code && c2 == '<'.code && c3 == '='.code -> LEFTSHIFTEQUAL
        c1 == '>'.code && c2 == '>'.code && c3 == '='.code -> RIGHTSHIFTEQUAL
        else -> OP
    }

    /**
     * state.h's tok_state for readline_tokenizer.c's tokenizer, and lexer.c's functions over it. The C's exception
     * set (PyErr_Occurred) is [error], raised by TokenizerIter where the lexer gives ERRORTOKEN.
     */
    private class Lexer(@JvmField val ctx: PyContext, @JvmField val readline: Any?, @JvmField val encoding: String?, @JvmField val extra: Boolean) {
        @JvmField var buf = ByteArray(8192)
        @JvmField var cur = 0
        @JvmField var inp = 0
        @JvmField var start = NULL
        @JvmField var done = E_OK
        @JvmField var indent = 0
        @JvmField val indstack = IntArray(MAXINDENT)
        @JvmField val altindstack = IntArray(MAXINDENT)
        @JvmField var atbol = true
        @JvmField var pendin = 0
        @JvmField var lineno = 0
        @JvmField var firstLineno = 0
        @JvmField var level = 0
        @JvmField val parenstack = IntArray(MAXLEVEL)
        @JvmField val parenlinenostack = IntArray(MAXLEVEL)
        @JvmField var decodingErred = false
        @JvmField var contLine = false
        @JvmField var lineStart = NULL
        @JvmField var multiLineStart = NULL
        @JvmField var commentNewline = false
        @JvmField var implicitNewline = false
        @JvmField val modes = arrayOfNulls<Mode>(MAXFSTRINGLEVEL).also { it[0] = Mode(REGULAR_MODE) }
        @JvmField var modeIndex = 0
        @JvmField var error: PyException? = null
        // the token made (struct token's start and end)
        @JvmField var tokStart = NULL
        @JvmField var tokEnd = NULL

        private fun insideFstring() = modeIndex > 0
        private fun prefix() = if (modes[modeIndex]!!.tstring) 't' else 'f'

        /** MAKE_TOKEN (_PyLexer_token_setup): the token's bytes, its type returned. */
        private fun make(type: Int, s: Int = NULL, e: Int = NULL): Int {
            tokStart = s
            tokEnd = e
            return type
        }

        // -------------------------------------------------------------- bytes and text

        private fun reserve(n: Int) {
            if (inp + n > buf.size) buf = buf.copyOf(maxOf(inp + n, buf.size + (buf.size shr 1)))
        }

        /** strlen from [i]: to the buffer's NUL (its end, or a NUL in it). */
        fun strlen(i: Int): Int {
            var k = i
            while (k < inp && buf[k] != 0.toByte()) k++
            return k - i
        }

        fun decodeReplace(b: ByteArray, from: Int, len: Int): String =
            if (len <= 0) "" else String(b, from, len, Charsets.UTF_8)

        fun decodeStrict(from: Int, to: Int): String {
            var ascii = true
            for (i in from until to) if (buf[i] < 0) { ascii = false; break }
            if (ascii) return String(buf, from, to - from, Charsets.ISO_8859_1)
            return try {
                Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(buf, from, to - from)).toString()
            } catch (e: java.nio.charset.CharacterCodingException) {
                throw PyException("UnicodeDecodeError", "'utf-8' codec can't decode bytes in position 0-${to - from - 1}: invalid continuation byte")
            }
        }

        /** _PyPegen_byte_offset_to_character_offset_raw: the characters of the first [col] bytes from [from] (its NUL one). */
        fun charOffsetRaw(from: Int, col: Int, errorLineLen: Int = -1): Int {
            val len = if (errorLineLen >= 0) errorLineLen else strlen(from)
            val c = minOf(col, len + 1)
            val text = decodeReplace(buf, from, minOf(c, len))
            return text.codePointCount(0, text.length) + (if (c > len) 1 else 0)
        }

        private fun utf8(s: String): ByteArray {
            var i = 0
            var cp = 0
            while (i < s.length) {
                val ch = s[i]
                if (Character.isHighSurrogate(ch) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                    i += 2
                } else if (Character.isSurrogate(ch)) {
                    throw PyException("UnicodeEncodeError", "'utf-8' codec can't encode character '\\u${Integer.toHexString(ch.code)}' in position $cp: surrogates not allowed")
                } else {
                    i++
                }
                cp++
            }
            return s.toByteArray(Charsets.UTF_8)
        }

        // -------------------------------------------------------------- errors (helpers.c)

        /** _syntaxerror_range: a SyntaxError at the current line, [col] -1 the characters to the cursor. */
        fun syntaxErrorRange(msg: String, col0: Int, endCol0: Int): Int {
            if (done == E_ERROR) return ERRORTOKEN
            var errtext = decodeReplace(buf, lineStart, cur - lineStart)
            val col = if (col0 == -1) errtext.codePointCount(0, errtext.length) else col0
            val endCol = if (endCol0 == -1) col else endCol0
            // (strcspn(line_start, "\n"))
            var lineLen = 0
            while (lineStart + lineLen < inp && buf[lineStart + lineLen] != '\n'.code.toByte() && buf[lineStart + lineLen] != 0.toByte()) lineLen++
            if (lineLen != cur - lineStart) errtext = decodeReplace(buf, lineStart, lineLen)
            error = PyException("SyntaxError", msg).also {
                it.args = arrayOf(PyStr(msg), PyTuple(arrayOf(PyStr("<string>"), lineno.toLong(), col.toLong(), PyStr(errtext), lineno.toLong(), endCol.toLong())))
            }
            done = E_ERROR
            return ERRORTOKEN
        }

        fun syntaxError(msg: String): Int = syntaxErrorRange(msg, -1, -1)

        private fun indentError(): Int {
            done = E_TABSPACE
            cur = inp
            return ERRORTOKEN
        }

        /** _PyTokenizer_error_ret: the input given up (an exception set), as at its end. */
        private fun errorRet() {
            decodingErred = true
            cur = 0
            inp = 0
            start = NULL
            done = E_DECODE
        }

        /** PyErr_WarnExplicitObject(SyntaxWarning, msg, "<string>", lineno): warnings.warn_explicit's; null where it warned. */
        private fun warn(msg: String): PyException? = try {
            val w = PyImport.importModule(ctx, "warnings")
            PyCalls.callWith(ctx, PyRt.getAttr(w, "warn_explicit"), arrayOf(PyStr(msg), PyExceptions.type("SyntaxWarning"), PyStr("<string>"), lineno.toLong()), NO_ARGS, NO_ARGS)
            null
        } catch (e: PyException) {
            e
        }

        /** _PyTokenizer_parser_warn: true where it failed (the warning an error: a SyntaxError). */
        private fun parserWarn(msg: String): Boolean {
            val e = warn(msg) ?: return false
            if (e.matches(PyExceptions.type("SyntaxWarning"))) syntaxError(msg) else error = e
            done = E_ERROR
            return true
        }

        /** _PyTokenizer_warn_invalid_escape_sequence: true where it failed. */
        private fun warnInvalidEscapeSequence(c: Int): Boolean {
            val ch = c.toChar()
            val e = warn("\"\\$ch\" is an invalid escape sequence. Such sequences will not work in the future. Did you mean \"\\\\$ch\"? A raw string is also an option.")
                ?: return false
            if (e.matches(PyExceptions.type("SyntaxWarning"))) {
                syntaxError("\"\\$ch\" is an invalid escape sequence. Did you mean \"\\\\$ch\"? A raw string is also an option.")
            } else {
                error = e
            }
            return true
        }

        // -------------------------------------------------------------- input (readline_tokenizer.c)

        /** tok_readline_string: readline's line appended (StopIteration: none), false where it failed. */
        private fun readlineString(): Boolean {
            val raw = try {
                PyCalls.callWith(ctx, readline, NO_ARGS, NO_ARGS, NO_ARGS)
            } catch (e: PyException) {
                if (e.matches(PyExceptions.type("StopIteration"))) return true
                error = e
                errorRet()
                return false
            }
            val bytes = try {
                val line: String = if (encoding != null) {
                    if (raw !is PyBytes && !(raw is PyNumSub && raw.num is PyBytes)) throw PyRt.typeError("readline() returned a non-bytes object")
                    val d = PyCalls.callWith(ctx, PyRt.getAttr(raw, "decode"), arrayOf(PyStr(encoding), PyStr("replace")), NO_ARGS, NO_ARGS)
                    (d as? PyStr)?.s ?: throw PyRt.typeError("decoder did not return a str object")
                } else {
                    (raw as? PyStr)?.s ?: throw PyRt.typeError("readline() returned a non-string object")
                }
                utf8(line)
            } catch (e: PyException) {
                error = e
                errorRet()
                return false
            }
            reserve(bytes.size + 2)
            bytes.copyInto(buf, inp)
            inp += bytes.size
            lineStart = cur
            return true
        }

        /** tok_underflow_readline: the next line read (the buffer kept where a token or an f-string spans lines). */
        private fun underflow(): Boolean {
            if (start == NULL && !insideFstring()) {
                cur = 0
                inp = 0
            }
            if (!readlineString()) return false
            if (inp == cur) {
                done = E_EOF
                return false
            }
            implicitNewline = false
            if (buf[inp - 1] != '\n'.code.toByte()) {
                // (the last line has no newline: one made up)
                reserve(1)
                buf[inp++] = '\n'.code.toByte()
                implicitNewline = true
            }
            // (_PyLexer_update_ftstring_expr: the `=` expression's text, the parser's only)
            lineno++
            return true
        }

        /** tok_nextc: the next byte, or EOF (the error, if one, in [done]). */
        fun nextc(): Int {
            while (true) {
                if (cur != inp) return buf[cur++].toInt() and 0xFF
                if (done != E_OK) return EOF
                if (!underflow()) {
                    cur = inp
                    return EOF
                }
                lineStart = cur
                var nul = false
                for (i in lineStart until inp) if (buf[i] == 0.toByte()) { nul = true; break }
                if (nul) {
                    syntaxError("source code cannot contain null bytes")
                    cur = inp
                    return EOF
                }
            }
        }

        /** tok_backup */
        fun backup(c: Int) {
            if (c != EOF) cur--
        }

        // -------------------------------------------------------------- the lexer's parts

        private fun lookahead(test: String): Boolean {
            var s = 0
            while (true) {
                val c = nextc()
                var res = false
                if (s == test.length) {
                    res = !isPotentialIdentifierChar(c)
                } else if (c == test[s].code) {
                    s++
                    continue
                }
                backup(c)
                while (s != 0) backup(test[--s].code)
                return res
            }
        }

        private fun verifyEndOfNumber(c: Int, kind: String): Boolean {
            // (tokenize's mode is more liberal: no warning, no error)
            if (extra) return true
            var r = false
            when (c) {
                'a'.code -> r = lookahead("nd")
                'e'.code -> r = lookahead("lse")
                'f'.code -> r = lookahead("or")
                'i'.code -> {
                    val c2 = nextc()
                    if (c2 == 'f'.code || c2 == 'n'.code || c2 == 's'.code) r = true
                    backup(c2)
                }
                'o'.code -> r = lookahead("r")
                'n'.code -> r = lookahead("ot")
            }
            if (r) {
                backup(c)
                if (parserWarn("invalid $kind literal")) return false
                nextc()
            } else if (c < 128 && isPotentialIdentifierChar(c)) {
                backup(c)
                syntaxError("invalid $kind literal")
                return false
            }
            return true
        }

        /** verify_identifier: PEP 3131's (the parser's mode only). */
        private fun verifyIdentifier(): Boolean {
            if (extra) return true
            if (decodingErred) return false
            val s = decodeStrict(start, cur)
            val cps = s.codePoints().toArray()
            val invalid = PyUnicodeType.scanIdentifier(s)
            if (invalid < cps.size) {
                val ch = cps[invalid]
                if (invalid + 1 < cps.size) {
                    var n = 0
                    for (k in 0..invalid) n += String(Character.toChars(cps[k])).toByteArray(Charsets.UTF_8).size
                    cur = start + n
                }
                if (PyRt.printable(ch)) syntaxError("invalid character '${String(Character.toChars(ch))}' (U+${hex4(ch)})")
                else syntaxError("invalid non-printable character U+${hex4(ch)}")
                return false
            }
            return true
        }

        /** tok_decimal_tail: the digits (with underscores), the byte after them returned; 0 on an error. */
        private fun decimalTail(): Int {
            var c: Int
            while (true) {
                do { c = nextc() } while (isDigit(c))
                if (c != '_'.code) break
                c = nextc()
                if (!isDigit(c)) {
                    backup(c)
                    syntaxError("invalid decimal literal")
                    return 0
                }
            }
            return c
        }

        /** tok_continuation_line: past a backslash's newline; -1 on an error. */
        private fun continuationLine(): Int {
            var c = nextc()
            if (c == '\r'.code) c = nextc()
            if (c != '\n'.code) {
                done = E_LINECONT
                return -1
            }
            c = nextc()
            if (c == EOF) {
                done = E_EOF
                cur = inp
                return -1
            } else {
                backup(c)
            }
            return c
        }

        /** maybe_raise_syntax_error_for_string_prefixes: true where it raised. */
        private fun prefixError(b: Boolean, r: Boolean, u: Boolean, f: Boolean, t: Boolean): Boolean {
            val pair = when {
                u && b -> "u" to "b"
                u && r -> "u" to "r"
                u && f -> "u" to "f"
                u && t -> "u" to "t"
                b && f -> "b" to "f"
                b && t -> "b" to "t"
                f && t -> "f" to "t"
                else -> return false
            }
            syntaxErrorRange("'${pair.first}' and '${pair.second}' prefixes are incompatible", start + 1 - lineStart, cur - lineStart)
            return true
        }

        // -------------------------------------------------------------- the regular mode

        /** tok_get_normal_mode */
        fun normalMode(cm: Mode): Int {
            var c: Int
            var blankline: Boolean
            nextline@ while (true) {
                start = NULL
                blankline = false
                // the indentation
                if (atbol) {
                    var col = 0
                    var altcol = 0
                    atbol = false
                    var contLineCol = 0
                    while (true) {
                        c = nextc()
                        if (c == ' '.code) {
                            col++
                            altcol++
                        } else if (c == '\t'.code) {
                            col = (col / TABSIZE + 1) * TABSIZE
                            altcol = (altcol / ALTTABSIZE + 1) * ALTTABSIZE
                        } else if (c == 0x0C) {
                            // (form feed: for Emacs users)
                            col = 0
                            altcol = 0
                        } else if (c == '\\'.code) {
                            // (the first backslash's column the indentation of what follows)
                            contLineCol = if (contLineCol != 0) contLineCol else col
                            c = continuationLine()
                            if (c == -1) return make(ERRORTOKEN)
                        } else if (c == EOF && error != null) {
                            return make(ERRORTOKEN)
                        } else {
                            break
                        }
                    }
                    backup(c)
                    // (a line of only spaces and a comment: no indentation)
                    if (c == '#'.code || c == '\n'.code || c == '\r'.code) blankline = true
                    if (!blankline && level == 0) {
                        col = if (contLineCol != 0) contLineCol else col
                        altcol = if (contLineCol != 0) contLineCol else altcol
                        if (col == indstack[indent]) {
                            if (altcol != altindstack[indent]) return make(indentError())
                        } else if (col > indstack[indent]) {
                            if (indent + 1 >= MAXINDENT) {
                                done = E_TOODEEP
                                cur = inp
                                return make(ERRORTOKEN)
                            }
                            if (altcol <= altindstack[indent]) return make(indentError())
                            pendin++
                            indstack[++indent] = col
                            altindstack[indent] = altcol
                        } else {
                            while (indent > 0 && col < indstack[indent]) {
                                pendin--
                                indent--
                            }
                            if (col != indstack[indent]) {
                                done = E_DEDENT
                                cur = inp
                                return make(ERRORTOKEN)
                            }
                            if (altcol != altindstack[indent]) return make(indentError())
                        }
                    }
                }

                start = cur
                // the indents and dedents pending
                if (pendin != 0) {
                    return if (pendin < 0) {
                        pendin++
                        if (extra) make(DEDENT, cur, cur) else make(DEDENT)
                    } else {
                        pendin--
                        if (extra) make(INDENT, 0, cur) else make(INDENT)
                    }
                }

                // (a peek at the next byte)
                c = nextc()
                backup(c)

                again@ while (true) {
                    start = NULL
                    do { c = nextc() } while (c == ' '.code || c == '\t'.code || c == 0x0C)
                    start = cur - 1

                    // a comment
                    if (c == '#'.code) {
                        while (c != EOF && c != '\n'.code && c != '\r'.code) c = nextc()
                        if (extra) {
                            val p = start
                            backup(c)
                            commentNewline = blankline
                            return make(COMMENT, p, cur)
                        }
                    }

                    if (c == EOF) {
                        if (level != 0) return make(ERRORTOKEN)
                        return make(if (done == E_EOF) ENDMARKER else ERRORTOKEN)
                    }

                    // an identifier, or a string's prefix
                    var nonascii = false
                    if (isPotentialIdentifierStart(c)) {
                        var sawB = false
                        var sawR = false
                        var sawU = false
                        var sawF = false
                        var sawT = false
                        while (true) {
                            if (!sawB && (c == 'b'.code || c == 'B'.code)) sawB = true
                            else if (!sawU && (c == 'u'.code || c == 'U'.code)) sawU = true
                            else if (!sawR && (c == 'r'.code || c == 'R'.code)) sawR = true
                            else if (!sawF && (c == 'f'.code || c == 'F'.code)) sawF = true
                            else if (!sawT && (c == 't'.code || c == 'T'.code)) sawT = true
                            else break
                            c = nextc()
                            if (c == '"'.code || c == '\''.code) {
                                if (prefixError(sawB, sawR, sawU, sawF, sawT)) return make(ERRORTOKEN)
                                return if (sawF || sawT) ftstringQuote(c) else letterQuote(c)
                            }
                        }
                        while (isPotentialIdentifierChar(c)) {
                            if (c >= 128) nonascii = true
                            c = nextc()
                        }
                        backup(c)
                        if (nonascii && !verifyIdentifier()) return make(ERRORTOKEN)
                        return make(NAME, start, cur)
                    }

                    if (c == '\r'.code) c = nextc()

                    // a newline
                    if (c == '\n'.code) {
                        atbol = true
                        if (blankline || level > 0) {
                            if (extra) {
                                if (commentNewline) commentNewline = false
                                return make(NL, start, cur)
                            }
                            continue@nextline
                        }
                        if (commentNewline && extra) {
                            commentNewline = false
                            return make(NL, start, cur)
                        }
                        contLine = false
                        // (the newline left out)
                        return make(NEWLINE, start, cur - 1)
                    }

                    // a period, or a number starting with one
                    if (c == '.'.code) {
                        c = nextc()
                        if (isDigit(c)) {
                            return number(c, true)
                        } else if (c == '.'.code) {
                            c = nextc()
                            if (c == '.'.code) return make(ELLIPSIS, start, cur)
                            backup(c)
                            backup('.'.code)
                        } else {
                            backup(c)
                        }
                        return make(DOT, start, cur)
                    }

                    if (isDigit(c)) return number(c, false)

                    // a string (an f-string's quote only reached from its prefix)
                    if (c == '\''.code || c == '"'.code) return letterQuote(c)

                    // a line's continuation
                    if (c == '\\'.code) {
                        c = continuationLine()
                        if (c == -1) return make(ERRORTOKEN)
                        contLine = true
                        continue@again
                    }

                    return punctuation(c, cm)
                }
            }
        }

        /** tok_get_normal_mode's number, from its first digit ([fraction]: after a period); its gotos states. */
        private fun number(c0: Int, fraction: Boolean): Int {
            var c = c0
            var state: Int
            if (fraction) {
                state = S_FRACTION
            } else if (c == '0'.code) {
                // hexadecimal, octal or binary, maybe
                c = nextc()
                if (c == 'x'.code || c == 'X'.code) {
                    c = nextc()
                    do {
                        if (c == '_'.code) c = nextc()
                        if (!isXDigit(c)) {
                            backup(c)
                            return make(syntaxError("invalid hexadecimal literal"))
                        }
                        do { c = nextc() } while (isXDigit(c))
                    } while (c == '_'.code)
                    if (!verifyEndOfNumber(c, "hexadecimal")) return make(ERRORTOKEN)
                    state = S_END
                } else if (c == 'o'.code || c == 'O'.code) {
                    c = nextc()
                    do {
                        if (c == '_'.code) c = nextc()
                        if (c < '0'.code || c >= '8'.code) {
                            if (isDigit(c)) return make(syntaxError("invalid digit '${c.toChar()}' in octal literal"))
                            backup(c)
                            return make(syntaxError("invalid octal literal"))
                        }
                        do { c = nextc() } while ('0'.code <= c && c < '8'.code)
                    } while (c == '_'.code)
                    if (isDigit(c)) return make(syntaxError("invalid digit '${c.toChar()}' in octal literal"))
                    if (!verifyEndOfNumber(c, "octal")) return make(ERRORTOKEN)
                    state = S_END
                } else if (c == 'b'.code || c == 'B'.code) {
                    c = nextc()
                    do {
                        if (c == '_'.code) c = nextc()
                        if (c != '0'.code && c != '1'.code) {
                            if (isDigit(c)) return make(syntaxError("invalid digit '${c.toChar()}' in binary literal"))
                            backup(c)
                            return make(syntaxError("invalid binary literal"))
                        }
                        do { c = nextc() } while (c == '0'.code || c == '1'.code)
                    } while (c == '_'.code)
                    if (isDigit(c)) return make(syntaxError("invalid digit '${c.toChar()}' in binary literal"))
                    if (!verifyEndOfNumber(c, "binary")) return make(ERRORTOKEN)
                    state = S_END
                } else {
                    var nonzero = false
                    // (maybe an old-style octal: '0' a literal still)
                    while (true) {
                        if (c == '_'.code) {
                            c = nextc()
                            if (!isDigit(c)) {
                                backup(c)
                                return make(syntaxError("invalid decimal literal"))
                            }
                        }
                        if (c != '0'.code) break
                        c = nextc()
                    }
                    val zerosEnd = cur
                    if (isDigit(c)) {
                        nonzero = true
                        c = decimalTail()
                        if (c == 0) return make(ERRORTOKEN)
                    }
                    if (c == '.'.code) {
                        c = nextc()
                        state = S_FRACTION
                    } else if (c == 'e'.code || c == 'E'.code) {
                        state = S_EXPONENT
                    } else if (c == 'j'.code || c == 'J'.code) {
                        state = S_IMAGINARY
                    } else if (nonzero && !extra) {
                        // (an old-style octal: no more)
                        backup(c)
                        return make(syntaxErrorRange("leading zeros in decimal integer literals are not permitted; use an 0o prefix for octal integers",
                            start + 1 - lineStart, zerosEnd - lineStart, ))
                    } else {
                        if (!verifyEndOfNumber(c, "decimal")) return make(ERRORTOKEN)
                        state = S_END
                    }
                }
            } else {
                // a decimal
                c = decimalTail()
                if (c == 0) return make(ERRORTOKEN)
                if (c == '.'.code) {
                    c = nextc()
                    state = S_FRACTION
                } else {
                    state = S_EXPONENT_CHECK
                }
            }
            while (true) {
                when (state) {
                    S_FRACTION -> {
                        if (isDigit(c)) {
                            c = decimalTail()
                            if (c == 0) return make(ERRORTOKEN)
                        }
                        state = S_EXPONENT_CHECK
                    }
                    S_EXPONENT_CHECK -> state = if (c == 'e'.code || c == 'E'.code) S_EXPONENT else S_IMAGINARY_CHECK
                    S_EXPONENT -> {
                        val e = c
                        c = nextc()
                        if (c == '+'.code || c == '-'.code) {
                            c = nextc()
                            if (!isDigit(c)) {
                                backup(c)
                                return make(syntaxError("invalid decimal literal"))
                            }
                        } else if (!isDigit(c)) {
                            backup(c)
                            if (!verifyEndOfNumber(e, "decimal")) return make(ERRORTOKEN)
                            backup(e)
                            return make(NUMBER, start, cur)
                        }
                        c = decimalTail()
                        if (c == 0) return make(ERRORTOKEN)
                        state = S_IMAGINARY_CHECK
                    }
                    S_IMAGINARY_CHECK -> {
                        if (c == 'j'.code || c == 'J'.code) {
                            state = S_IMAGINARY
                        } else {
                            if (!verifyEndOfNumber(c, "decimal")) return make(ERRORTOKEN)
                            state = S_END
                        }
                    }
                    S_IMAGINARY -> {
                        c = nextc()
                        if (!verifyEndOfNumber(c, "imaginary")) return make(ERRORTOKEN)
                        state = S_END
                    }
                    else -> {
                        backup(c)
                        return make(NUMBER, start, cur)
                    }
                }
            }
        }

        /** tok_get_normal_mode's f_string_quote: an f-string's (t-string's) start, its mode pushed. */
        private fun ftstringQuote(c: Int): Int {
            val quote = c
            var quoteSize = 1
            firstLineno = lineno
            multiLineStart = lineStart
            val afterQuote = nextc()
            if (afterQuote == quote) {
                val afterAfterQuote = nextc()
                if (afterAfterQuote == quote) {
                    quoteSize = 3
                } else {
                    backup(afterAfterQuote)
                    backup(afterQuote)
                }
            }
            if (afterQuote != quote) backup(afterQuote)
            val pStart = start
            val pEnd = cur
            if (modeIndex + 1 >= MAXFSTRINGLEVEL) return make(syntaxError("too many nested f-strings or t-strings"))
            val m = Mode(FSTRING_MODE)
            modes[++modeIndex] = m
            m.quote = quote
            m.quoteSize = quoteSize
            m.start = start
            m.multiLineStart = lineStart
            m.firstLine = lineno
            when (lower(buf[start].toInt() and 0xFF)) {
                't'.code -> {
                    m.raw = lower(buf[start + 1].toInt() and 0xFF) == 'r'.code
                    m.tstring = true
                }
                'f'.code -> m.raw = lower(buf[start + 1].toInt() and 0xFF) == 'r'.code
                else -> {
                    m.raw = true
                    if (lower(buf[start + 1].toInt() and 0xFF) == 't'.code) m.tstring = true
                }
            }
            m.curlyBracketDepth = 0
            m.curlyBracketExprStartDepth = -1
            return make(if (m.tstring) TSTRING_START else FSTRING_START, pStart, pEnd)
        }

        /** tok_get_normal_mode's letter_quote: a string. */
        private fun letterQuote(c0: Int): Int {
            var c = c0
            val quote = c
            var quoteSize = 1
            var endQuoteSize = 0
            var hasEscapedQuote = false
            firstLineno = lineno
            multiLineStart = lineStart
            c = nextc()
            if (c == quote) {
                c = nextc()
                if (c == quote) quoteSize = 3 else endQuoteSize = 1
            }
            if (c != quote) backup(c)
            while (endQuoteSize != quoteSize) {
                c = nextc()
                if (done == E_ERROR) return make(ERRORTOKEN)
                if (done == E_DECODE) break
                if (c == EOF || (quoteSize == 1 && c == '\n'.code)) {
                    // (the error at the string's first quote)
                    cur = start + 1
                    lineStart = multiLineStart
                    val startLine = lineno
                    lineno = firstLineno
                    if (insideFstring()) {
                        // (an f-string's quote: a '}' missing)
                        val m = modes[modeIndex]!!
                        if (m.quote == quote && m.quoteSize == quoteSize) return make(syntaxError("${prefix()}-string: expecting '}'"))
                    }
                    if (quoteSize == 3) {
                        syntaxError("unterminated triple-quoted string literal (detected at line $startLine)")
                        if (c != '\n'.code) done = E_EOFS
                        return make(ERRORTOKEN)
                    } else {
                        if (hasEscapedQuote) syntaxError("unterminated string literal (detected at line $startLine); perhaps you escaped the end quote?")
                        else syntaxError("unterminated string literal (detected at line $startLine)")
                        if (c != '\n'.code) done = E_EOLS
                        return make(ERRORTOKEN)
                    }
                }
                if (c == quote) {
                    endQuoteSize += 1
                } else {
                    endQuoteSize = 0
                    if (c == '\\'.code) {
                        c = nextc()
                        if (c == quote) hasEscapedQuote = true
                        if (c == '\r'.code) c = nextc()
                    }
                }
            }
            return make(STRING, start, cur)
        }

        /** tok_get_normal_mode's operators (an f-string's replacement field's ':' and '}' too). */
        private fun punctuation(c: Int, cm: Mode): Int {
            val isPunctuation = c == ':'.code || c == '}'.code || c == '!'.code || c == '{'.code
            if (isPunctuation && insideFstring() && cm.curlyBracketExprStartDepth >= 0) {
                // (curly_bracket_depth not yet counting a '{')
                val cursor = cm.curlyBracketDepth - (if (c != '{'.code) 1 else 0)
                // (_PyLexer_update_ftstring_expr, set_ftstring_expr: the `=` expression's text, the parser's)
                if (c == ':'.code && cursor == cm.curlyBracketExprStartDepth) {
                    cm.kind = FSTRING_MODE
                    cm.inFormatSpec = true
                    return make(oneChar(c), start, cur)
                }
            }

            // two characters, or three
            val c2 = nextc()
            var current = twoChars(c, c2)
            if (current != OP) {
                val c3 = nextc()
                val current3 = threeChars(c, c2, c3)
                if (current3 != OP) current = current3 else backup(c3)
                return make(current, start, cur)
            }
            backup(c2)

            // the brackets' nesting
            when (c) {
                '('.code, '['.code, '{'.code -> {
                    if (level >= MAXLEVEL) return make(syntaxError("too many nested parentheses"))
                    parenstack[level] = c
                    parenlinenostack[level] = lineno
                    level++
                    if (insideFstring()) cm.curlyBracketDepth++
                }
                ')'.code, ']'.code, '}'.code -> {
                    if (insideFstring() && cm.curlyBracketDepth == 0 && c == '}'.code) {
                        return make(syntaxError("${prefix()}-string: single '}' is not allowed"))
                    }
                    if (!extra && level == 0) return make(syntaxError("unmatched '${c.toChar()}'"))
                    if (level > 0) {
                        level--
                        val opening = parenstack[level]
                        if (!extra && !((opening == '('.code && c == ')'.code) || (opening == '['.code && c == ']'.code) ||
                                (opening == '{'.code && c == '}'.code))) {
                            // (an f-string's field's bracket: unmatched, not mismatched)
                            if (insideFstring() && opening == '{'.code) {
                                val previousBracket = cm.curlyBracketDepth - 1
                                if (previousBracket == cm.curlyBracketExprStartDepth) {
                                    return make(syntaxError("${prefix()}-string: unmatched '${c.toChar()}'"))
                                }
                            }
                            return if (parenlinenostack[level] != lineno) {
                                make(syntaxError("closing parenthesis '${c.toChar()}' does not match opening parenthesis '${opening.toChar()}' on line ${parenlinenostack[level]}"))
                            } else {
                                make(syntaxError("closing parenthesis '${c.toChar()}' does not match opening parenthesis '${opening.toChar()}'"))
                            }
                        }
                    }
                    if (insideFstring()) {
                        cm.curlyBracketDepth--
                        if (cm.curlyBracketDepth < 0) return make(syntaxError("${prefix()}-string: unmatched '${c.toChar()}'"))
                        if (c == '}'.code && cm.curlyBracketDepth == cm.curlyBracketExprStartDepth) {
                            cm.curlyBracketExprStartDepth--
                            cm.kind = FSTRING_MODE
                            cm.inFormatSpec = false
                            cm.inDebug = false
                        }
                    }
                }
            }

            if (!PyRt.printable(c)) return make(syntaxError("invalid non-printable character U+${hex4(c)}"))

            if (c == '='.code && cm.curlyBracketDepth - cm.curlyBracketExprStartDepth == 1) cm.inDebug = true

            return make(oneChar(c), start, cur)
        }

        // -------------------------------------------------------------- an f-string's mode

        private fun middle(m: Mode) = if (m.tstring) TSTRING_MIDDLE else FSTRING_MIDDLE

        /** tok_get_fstring_mode: an f-string's (t-string's) text to its next field or its end. */
        private fun fstringMode(cm: Mode): Int {
            var endQuoteSize = 0
            var unicodeEscape = false
            start = cur
            firstLineno = lineno

            // (a field at once: the regular mode's)
            val startChar = nextc()
            if (startChar == '{'.code) {
                val peek1 = nextc()
                backup(peek1)
                backup(startChar)
                if (peek1 != '{'.code) {
                    cm.curlyBracketExprStartDepth++
                    if (cm.curlyBracketExprStartDepth >= MAX_EXPR_NESTING) {
                        return make(syntaxError("${prefix()}-string: expressions nested too deeply"))
                    }
                    modes[modeIndex]!!.kind = REGULAR_MODE
                    return normalMode(cm)
                }
            } else {
                backup(startChar)
            }

            // the string's end?
            var atEnd = true
            for (i in 0 until cm.quoteSize) {
                val q = nextc()
                if (q != cm.quote) {
                    backup(q)
                    atEnd = false
                    break
                }
            }
            if (atEnd) {
                val pStart = start
                val pEnd = cur
                modeIndex--
                return make(if (cm.tstring) TSTRING_END else FSTRING_END, pStart, pEnd)
            }

            multiLineStart = lineStart
            while (endQuoteSize != cm.quoteSize) {
                val c = nextc()
                if (done == E_ERROR || done == E_DECODE) return make(ERRORTOKEN)
                val inFormatSpec = cm.inFormatSpec && cm.curlyBracketExprStartDepth >= 0

                if (c == EOF || (cm.quoteSize == 1 && c == '\n'.code)) {
                    if (decodingErred) return make(ERRORTOKEN)
                    // (a format spec's newline: its end, the regular mode's again)
                    if (inFormatSpec && c == '\n'.code) {
                        if (cm.quoteSize == 1) {
                            return make(syntaxError("${prefix()}-string: newlines are not allowed in format specifiers for single quoted ${prefix()}-strings"))
                        }
                        backup(c)
                        modes[modeIndex]!!.kind = REGULAR_MODE
                        cm.inFormatSpec = false
                        return make(middle(cm), start, cur)
                    }
                    // (the error at the string's start)
                    cur = cm.start + 1
                    lineStart = cm.multiLineStart
                    val startLine = lineno
                    lineno = modes[modeIndex]!!.firstLine
                    if (cm.quoteSize == 3) {
                        syntaxError("unterminated triple-quoted ${prefix()}-string literal (detected at line $startLine)")
                        if (c != '\n'.code) done = E_EOFS
                        return make(ERRORTOKEN)
                    }
                    return make(syntaxError("unterminated ${prefix()}-string literal (detected at line $startLine)"))
                }

                if (c == cm.quote) {
                    endQuoteSize += 1
                    continue
                } else {
                    endQuoteSize = 0
                }

                if (c == '{'.code) {
                    val peek = nextc()
                    if (peek != '{'.code || inFormatSpec) {
                        backup(peek)
                        backup(c)
                        cm.curlyBracketExprStartDepth++
                        if (cm.curlyBracketExprStartDepth >= MAX_EXPR_NESTING) {
                            return make(syntaxError("${prefix()}-string: expressions nested too deeply"))
                        }
                        modes[modeIndex]!!.kind = REGULAR_MODE
                        cm.inFormatSpec = false
                        return make(middle(cm), start, cur)
                    }
                    return make(middle(cm), start, cur - 1)
                } else if (c == '}'.code) {
                    if (unicodeEscape) return make(middle(cm), start, cur)
                    val peek = nextc()
                    // (a format spec has no doubled braces)
                    val cursor = cm.curlyBracketDepth
                    if (peek == '}'.code && !inFormatSpec && cursor == 0) return make(middle(cm), start, cur - 1)
                    backup(peek)
                    backup(c)
                    modes[modeIndex]!!.kind = REGULAR_MODE
                    cm.inFormatSpec = false
                    return make(middle(cm), start, cur)
                } else if (c == '\\'.code) {
                    var peek = nextc()
                    if (peek == '\r'.code) peek = nextc()
                    // (a backslash before a brace: the brace the loop's again)
                    if (peek == '{'.code || peek == '}'.code) {
                        if (!cm.raw && warnInvalidEscapeSequence(peek)) return make(ERRORTOKEN)
                        backup(peek)
                        continue
                    }
                    if (!cm.raw) {
                        if (peek == 'N'.code) {
                            // (a named escape, \N{BULLET})
                            peek = nextc()
                            if (peek == '{'.code) unicodeEscape = true else backup(peek)
                        }
                    }
                }
            }

            // (the quotes the FSTRING_END's, next)
            for (i in 0 until cm.quoteSize) backup(cm.quote)
            return make(middle(cm), start, cur)
        }

        /** _PyTokenizer_Get */
        fun get(): Int {
            val cm = modes[modeIndex]!!
            var result = if (cm.kind == REGULAR_MODE) normalMode(cm) else fstringMode(cm)
            if (decodingErred) {
                result = ERRORTOKEN
                done = E_DECODE
            }
            return result
        }
    }

    private const val S_FRACTION = 0
    private const val S_EXPONENT_CHECK = 1
    private const val S_EXPONENT = 2
    private const val S_IMAGINARY_CHECK = 3
    private const val S_IMAGINARY = 4
    private const val S_END = 5
}
