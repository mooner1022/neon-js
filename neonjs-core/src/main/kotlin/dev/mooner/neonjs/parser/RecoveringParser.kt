@file:OptIn(UnstableParserApi::class)

package dev.mooner.neonjs.parser

/**
 * The parser's recovery mode (Parser.parseTolerant), for the linter: the engine's own grammar, read on after errors.
 *
 * - An early error ([raise]: a rule broken where the syntax is intact) is recorded, and the parse goes on from it.
 * - A syntax error the parse cannot go on from ([fail], or the lexer's) ends the statement being read. What it changed
 *   in the parser's state is undone, the tokens up to where a statement can begin again are passed over, and an
 *   [ErrorNode] stands for it in the tree with the names it had declared, so that scope analysis still finds them.
 *
 * The engine never makes one: it differs from [ParserImpl] only in the methods ParserImpl calls at a statement list's
 * statements, before the first token, when a name is declared and when an error is found.
 */
internal class RecoveringParser(src: String, options: ParseOptions, private val limits: ParseLimits) : ParserImpl(src, options) {

    val problems = ArrayList<ParseProblem>()
    /** Why the parse stopped before the end of the source, or null. */
    var stopped: ParseStop? = null
        private set

    private val recorded = HashSet<String>()
    /** A syntax error was recorded in the statement being read: the ones after it in that statement are its echoes. */
    private var structuralInStatement = false

    /** The names declared since the current top-level statement began, so that a failed statement's are undone. */
    private val declared = ArrayList<Declared>()
    /** Names declared at the top level by statements that failed (an `export { x }` of one is not an error). */
    private val recoveredTopNames = HashSet<String>()

    private class Declared(val scope: PScope, val depth: Int, val kind: Int, val name: String, val pos: Int)

    /** Unwinds to the statement being read: its syntax error was recorded where it was found. */
    private class Recover(val at: Int) : RuntimeException(null, null, false, false)

    /** Unwinds to the top level, where the rest is passed over: [stopped] says why. */
    private class Stop : RuntimeException(null, null, false, false)

    // ============================================================ errors

    /** Records an error; at the limit, [stopped] is set, and the next statement list's statement stops the parse. */
    private fun record(pos: Int, msg: String, kind: ProblemKind) {
        if (stopped != null) return
        val structural = kind != ProblemKind.EARLY
        if (structural && structuralInStatement) return
        // (the same error found twice: chains of checks on one token, a destructuring error checked again outside)
        if (!recorded.add("$pos\u0000$msg")) return
        if (structural) structuralInStatement = true
        val end = if (pos == lex.start && lex.end > lex.start) lex.end else -1
        problems.add(ParseProblem(pos, end, msg, kind))
        if (problems.size >= limits.maxProblems) stopped = ParseStop.LIMIT
    }

    override fun raise(pos: Int, msg: String) = record(pos, msg, ProblemKind.EARLY)

    override fun fail(pos: Int, msg: String): Nothing {
        record(pos, msg, if (msg.startsWith("Expected '")) ProblemKind.MISSING else ProblemKind.UNEXPECTED)
        throw Recover(maxOf(pos, lex.start))
    }

    // ============================================================ declarations

    override fun declareName(name: String, bind: Bind, pos: Int, fromForOf: Boolean) {
        val top = scopeStack.size - 1
        when (bind) {
            Bind.LEXICAL, Bind.SIMPLE_CATCH -> declared.add(Declared(scopeStack[top], top, D_LEXICAL, name, pos))
            Bind.FUNCTION -> if (!scopeStack[top].functions.contains(name)) declared.add(Declared(scopeStack[top], top, D_FUNCTION, name, pos))
            Bind.VAR -> for (i in top downTo 0) {
                // (the scopes ParserBase.declareName adds a var to; one it stops at for a redeclaration has the name)
                val s = scopeStack[i]
                if (!s.vars.contains(name)) declared.add(Declared(s, i, D_VAR, name, pos))
                if (s.flags and SCOPE_VAR != 0) break
            }
            else -> {}
        }
        super.declareName(name, bind, pos, fromForOf)
    }

    override fun declaredAtTop(name: String): Boolean = super.declaredAtTop(name) || recoveredTopNames.contains(name)

    // ============================================================ statements

    override fun readFirstToken() {
        while (true) {
            try {
                next()
                return
            } catch (e: JSSyntaxError) {
                record(e.pos, e.rawMessage, ProblemKind.LEXICAL)
                resumeAfterLexError()
            }
        }
    }

    override fun parseListedStatement(list: Int): Node {
        if (stopped == null && limits.isCancelled?.invoke() == true) stopped = ParseStop.CANCELLED
        if (stopped != null) {
            if (list != LIST_TOP) throw Stop()
            return stopHere()
        }
        // a switch's cases reaching the end: the statement around fails, as the engine's does
        if (type == T.EOF) fail(lex.start, "Unexpected end of input")
        val from = lex.save()
        val startPos = lex.start
        val depth = scopeStack.size
        val labelList = labels
        val labelCount = labels.size
        val names = privateNames
        val wasStrict = strict
        val y = yieldPos
        val a = awaitPos
        val ai = awaitIdentPos
        val switchDepth = switchCaseDepth
        val declaredMark = declared.size
        val exported = if (list == LIST_TOP && isKw("export")) HashSet(exportedNames) else null
        val undefinedExported = if (exported != null) LinkedHashMap(undefinedExports) else null
        val outerStructural = structuralInStatement
        structuralInStatement = false
        // (left by an early error after decorators, P:204; never set at a list's statement otherwise)
        pendingDecorators = null
        var errorAt: Int
        try {
            val st = parseStatement(null, list == LIST_TOP)
            // nothing around a top-level statement can fail and undo what it declared
            if (list == LIST_TOP) declared.clear()
            return st
        } catch (e: Recover) {
            errorAt = e.at
        } catch (e: JSSyntaxError) {
            record(e.pos, e.rawMessage, ProblemKind.LEXICAL)
            errorAt = maxOf(e.pos, lex.start)
        } catch (e: Stop) {
            if (list != LIST_TOP) throw e
            errorAt = -1
        } finally {
            structuralInStatement = outerStructural
        }
        // undo what the statement changed, keeping the names it declared in the scopes around it
        while (scopeStack.size > depth) scopeStack.removeAt(scopeStack.size - 1)
        labels = labelList
        while (labels.size > labelCount) labels.removeAt(labels.size - 1)
        privateNames = names
        strict = wasStrict
        yieldPos = y; awaitPos = a; awaitIdentPos = ai
        switchCaseDepth = switchDepth
        pendingDecorators = null
        if (exported != null) {
            exportedNames.clear(); exportedNames.addAll(exported)
            undefinedExports.clear(); undefinedExports.putAll(undefinedExported!!)
        }
        val node = undoDeclarations(declaredMark, depth)
        if (list == LIST_TOP) {
            for (id in node.lexicalNames) recoveredTopNames.add(id.name)
            for (id in node.varNames) recoveredTopNames.add(id.name)
        }
        if (errorAt < 0) {
            toEnd()
        } else {
            skipStatement(from, startPos, errorAt, list)
            // whatever the skip found, the next statement begins after this one's first token
            if (lex.start <= startPos && type != T.EOF) skipNext()
        }
        node.start = startPos
        node.end = maxOf(startPos, lex.prevEnd)
        node.line = from.tokLine
        node.col = from.tokCol
        return node
    }

    /** The rest of the source, not read: [stopped] says why. */
    private fun stopHere(): Node {
        val node = ErrorNode(emptyList(), emptyList())
        node.start = lex.start; node.line = lex.tokLine; node.col = lex.tokCol
        toEnd()
        node.end = src.length
        return node
    }

    private fun toEnd() {
        lex.pos = src.length
        lex.next()
    }

    /**
     * Removes the names declared since [mark] from the parser's books, and returns the node standing for the failed
     * statement with those declared in the scopes around it (below [depth]): a later declaration of one is no
     * redeclaration, and the scope analysis still finds it.
     */
    private fun undoDeclarations(mark: Int, depth: Int): ErrorNode {
        val lexical = ArrayList<Identifier>()
        val vars = ArrayList<Identifier>()
        val seenLexical = HashSet<String>()
        val seenVars = HashSet<String>()
        for (i in mark until declared.size) {
            val d = declared[i]
            if (d.depth >= depth) continue
            val asVar = d.kind == D_VAR || (d.kind == D_FUNCTION && treatFunctionsAsVarInScope(d.scope))
            if (d.kind != D_VAR && d.depth != depth - 1) continue
            if (asVar) { if (seenVars.add(d.name)) vars.add(identifierAt(d.name, d.pos)) }
            else if (seenLexical.add(d.name)) lexical.add(identifierAt(d.name, d.pos))
        }
        for (i in declared.size - 1 downTo mark) {
            val d = declared[i]
            when (d.kind) {
                D_LEXICAL -> {
                    val at = d.scope.lexical.lastIndexOf(d.name)
                    if (at >= 0) d.scope.lexical.removeAt(at)
                }
                D_FUNCTION -> d.scope.functions.remove(d.name)
                else -> d.scope.vars.remove(d.name)
            }
            declared.removeAt(i)
        }
        return ErrorNode(lexical, vars)
    }

    private fun identifierAt(name: String, pos: Int): Identifier {
        val id = Identifier(name)
        id.start = pos
        id.end = pos + name.length
        return id
    }

    // ============================================================ passing over a failed statement

    /**
     * Reads the failed statement again from its start ([from]), past the token it failed at ([errorAt]), to where the
     * next statement can begin: a `;` or the `}` of a block of the statement's own outside brackets, a line that begins
     * with a statement's keyword, the `}` of the block around, or a switch's next case. Brackets are counted from the
     * statement's start, so that one opened before the error is closed with it; a regular expression or a template
     * is read as one (a `}` in one is no bracket), which the parser alone decides when it reads one.
     */
    private fun skipStatement(from: LexState, startPos: Int, errorAt: Int, list: Int) {
        lex.restore(from)
        // open brackets: ( [ { , `f` a for's (whose `;`s are in it), '`' a template's substitution
        val stack = StringBuilder()
        var braces = 0
        var prevType: T? = null
        var prevWord: String? = null
        var prevPrevWord: String? = null
        var prevTail = true
        while (type != T.EOF) {
            val past = lex.start >= errorAt
            if (lex.start > startPos && lex.nlBefore && braces == 0 && startsStatement(stack.isEmpty()) && (past || stack.isEmpty())) return
            // (reserved words: in a case's statement, the next case)
            if (lex.start > startPos && list == LIST_CASE && braces == 0 && (isKw("case") || isKw("default"))) return
            when (type) {
                T.SLASH, T.SLASH_ASSIGN -> if (regexAllowed(prevType, prevWord, prevTail)) {
                    try {
                        lex.rescanRegExp()
                    } catch (e: JSSyntaxError) {
                        resumeAfterLexError()
                    }
                }
                T.LPAREN -> stack.append(if (prevWord == "for" || (prevWord == "await" && prevPrevWord == "for")) 'f' else '(')
                T.LBRACKET -> stack.append('[')
                T.LBRACE -> { stack.append('{'); braces++ }
                T.RPAREN, T.RBRACKET -> {
                    val open = if (type == T.RBRACKET) "[" else "(f"
                    // closes its opener, and whatever was opened after it and left open (`f(a, {)`), not past a template
                    var i = stack.length - 1
                    while (i >= 0 && stack[i] != '`' && stack[i] !in open) i--
                    if (i >= 0 && stack[i] in open) {
                        for (k in i until stack.length) if (stack[k] == '{') braces--
                        stack.setLength(i)
                    }
                }
                T.RBRACE -> when {
                    stack.isNotEmpty() && stack[stack.length - 1] == '`' -> {
                        stack.setLength(stack.length - 1)
                        try {
                            lex.rescanTemplateContinuation()
                            if (!lex.templateTail) stack.append('`')
                        } catch (e: JSSyntaxError) {
                            resumeAfterLexError()
                        }
                    }
                    braces > 0 -> {
                        stack.setLength(stack.lastIndexOf("{"))
                        braces--
                        // a block's end ends the statement, unless an expression goes on after it: `f({ ... })`
                        if (past && braces == 0 && (stack.isEmpty() || !nextContinuesExpression())) {
                            skipNext()
                            return
                        }
                    }
                    // the end of the block around (at the top level, a `}` that closes nothing goes with the statement)
                    list != LIST_TOP -> return
                }
                T.SEMI -> if (past && braces == 0 && stack.indexOf("f") < 0) {
                    skipNext()
                    return
                }
                T.TEMPLATE -> if (!lex.templateTail) stack.append('`')
                else -> {}
            }
            prevType = type
            prevPrevWord = prevWord
            prevWord = if (type == T.NAME && !lex.escaped) lex.value as String else null
            prevTail = type != T.TEMPLATE || lex.templateTail
            skipNext()
        }
    }

    /** Whether the token after this `}` goes on with an expression: `)`, `]`, `,`, `.`, `?.`, `(`. */
    private fun nextContinuesExpression(): Boolean {
        val s = lex.save()
        try {
            lex.next()
            return when (lex.type) {
                T.RPAREN, T.RBRACKET, T.COMMA, T.DOT, T.QDOT, T.LPAREN -> true
                else -> false
            }
        } catch (e: JSSyntaxError) {
            return false
        } finally {
            lex.restore(s)
        }
    }

    /** A statement's first token, at the start of a line: a keyword only statements begin with. */
    private fun startsStatement(outside: Boolean): Boolean {
        if (type != T.NAME || lex.escaped) return false
        val v = lex.value as String
        return v in STATEMENT_KEYWORDS || (outside && v in DECLARATION_WORDS)
    }

    /** The next token, the lexer's errors passed over (they are part of the statement that failed). */
    private fun skipNext() {
        while (true) {
            try {
                lex.next()
                return
            } catch (e: JSSyntaxError) {
                resumeAfterLexError()
            }
        }
    }

    /**
     * Where to read on after the lexer's error: where it stopped (on the line terminator of a string left open, at the
     * end, inside a token), or past the first character of the token it could not read. Never past a line terminator,
     * so that lines are counted.
     */
    private fun resumeAfterLexError() {
        val s = lex.start
        val p = lex.pos
        lex.pos = when {
            p > s -> minOf(p, src.length)
            s < src.length -> s + Character.charCount(src.codePointAt(s))
            else -> src.length
        }
    }

    private companion object {
        const val D_LEXICAL = 0
        const val D_FUNCTION = 1
        const val D_VAR = 2

        val STATEMENT_KEYWORDS = hashSetOf(
            "var", "let", "const", "if", "for", "while", "do", "return", "switch", "try", "throw", "break", "continue",
            "import", "export", "using", "debugger",
        )
        /** Words that begin declarations at a line's start, and expressions inside brackets (`f(\n function () {})`). */
        val DECLARATION_WORDS = hashSetOf("function", "class", "async")
        /** Words after which a `/` begins a regular expression. */
        val REGEX_AFTER_WORDS = hashSetOf(
            "return", "typeof", "instanceof", "in", "of", "new", "delete", "void", "throw", "case", "do", "else", "yield",
            "await", "extends",
        )

        fun regexAllowed(prevType: T?, prevWord: String?, prevTail: Boolean): Boolean = when (prevType) {
            null -> true
            T.NAME -> prevWord != null && prevWord in REGEX_AFTER_WORDS
            T.RPAREN, T.RBRACKET, T.RBRACE, T.INC, T.DEC, T.NUM, T.BIGINT, T.STRING, T.REGEXP, T.PRIVATE_NAME -> false
            T.TEMPLATE -> !prevTail
            else -> true
        }
    }
}
