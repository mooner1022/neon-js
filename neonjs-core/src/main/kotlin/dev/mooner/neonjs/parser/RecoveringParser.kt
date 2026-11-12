@file:OptIn(UnstableParserApi::class)

package dev.mooner.neonjs.parser

/**
 * The parser's recovery mode (Parser.parseTolerant), for the linter: the engine's own grammar, read on after errors.
 *
 * - An early error ([raise]: a rule broken where the syntax is intact) is recorded, and the parse goes on from it.
 * - A token missing where the token after it can follow it (`if (a {`, `{a: 1 b: 2}`, `let a = 1 let b = 2`) is
 *   recorded, and the parse goes on as if it were there.
 * - Any other syntax error ([fail], or the lexer's) ends the statement, or the class member, being read. What it changed
 *   in the parser's state is undone, the tokens up to where a statement (a member) can begin again are passed over, and
 *   an [ErrorNode] stands for it in the tree, with the names it had declared so that scope analysis still finds them.
 * - Once a syntax error is recorded in a statement, the next ones in it are its echoes: they are not recorded (early
 *   errors are).
 * - The source ending inside blocks is one error; the blocks keep their statements.
 *
 * The engine never makes one: it differs from [ParserImpl] only in the methods ParserImpl calls at statements and class
 * members, before the first token, when a name is declared and when an error is found.
 */
internal class RecoveringParser(
    src: String, options: ParseOptions, private val limits: ParseLimits,
    /** Where tokens the lexer could not read were repaired, in order: the errors after one in its statement are its echoes. */
    private val repaired: IntArray = IntArray(0),
) : ParserImpl(src, options) {

    val problems = ArrayList<ParseProblem>()
    /** Why the parse stopped before the end of the source, or null. */
    var stopped: ParseStop? = null
        private set

    private val recorded = HashSet<String>()
    /** A syntax error was recorded in the statement being read: the ones after it in that statement are its echoes. */
    private var structuralInStatement = false
    /** Where the statement (class member) being read begins. */
    private var statementStart = 0
    /** Where the parse last went on as if a token were there: not twice at one token. */
    private var lastPretended = -1
    /** The innermost `{` the source leaves open, once asked (-2: not yet). */
    private var unclosedBrace = -2

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

    /**
     * Records an error, unless it was recorded already or, for a syntax error, one was recorded in the statement
     * ([force]: all the same). At the limit, [stopped] is set, and the next statement list's statement stops the parse.
     */
    private fun record(pos: Int, msg: String, kind: ProblemKind, related: Int = -1, force: Boolean = false) {
        if (stopped != null) return
        val structural = kind != ProblemKind.EARLY
        if (structural && !force && (structuralInStatement || repairedBetween(statementStart, pos))) return
        // (the same error found twice: chains of checks on one token, a destructuring error checked again outside, the
        // end of the source in each block around)
        if (!recorded.add("$pos\u0000$msg")) return
        if (structural) structuralInStatement = true
        val end = if (pos == lex.start && lex.end > lex.start) lex.end else -1
        problems.add(ParseProblem(pos, end, msg, kind, related))
        if (problems.size >= limits.maxProblems) stopped = ParseStop.LIMIT
    }

    override fun raise(pos: Int, msg: String) = record(pos, msg, ProblemKind.EARLY)

    /** Whether a token in [from, to] was repaired: the statement's first syntax error was the lexer's there. */
    private fun repairedBetween(from: Int, to: Int): Boolean {
        for (r in repaired) if (r in from..to) return true
        return false
    }

    override fun fail(pos: Int, msg: String): Nothing {
        when {
            msg == "Unexpected end of input" -> record(pos, msg, ProblemKind.UNEXPECTED, unclosedBrace(), force = true)
            msg.startsWith("Expected '") -> record(pos, msg, ProblemKind.MISSING)
            else -> record(pos, msg, ProblemKind.UNEXPECTED)
        }
        throw Recover(maxOf(pos, lex.start))
    }

    override fun expectFailed(t: T) {
        if (type != T.EOF && lex.start != lastPretended && canPretend(t)) {
            lastPretended = lex.start
            record(lex.start, "Expected '${t.text}' but found ${describeToken()}", ProblemKind.MISSING)
            return
        }
        super.expectFailed(t)
    }

    /** Whether the parse can go on as if [t] were before the current token: the token can follow it. */
    private fun canPretend(t: T): Boolean = when (t) {
        // (what follows a condition or a subscript: the token the expression before it stopped at)
        T.RPAREN, T.RBRACKET -> true
        // (a list's next element; on the next line, only a property's `key:`: a list's end is missing more often)
        T.COMMA -> startsElement() && (!lex.nlBefore || (type != T.LBRACKET && lookahead { type == T.COLON }))
        T.COLON -> !lex.nlBefore && startsElement()
        else -> false
    }

    override fun semicolonFailed() {
        if (type != T.EOF && lex.start != lastPretended && startsStatementHere()) {
            lastPretended = lex.start
            record(lex.start, unexpectedMessage(), ProblemKind.UNEXPECTED)
            return
        }
        super.semicolonFailed()
    }

    /** Whether the current token can begin an expression, a list's element or a property. */
    private fun startsElement(): Boolean = when (type) {
        T.NAME -> lex.escaped || (lex.value != "in" && lex.value != "instanceof" && lex.value != "of")
        T.NUM, T.BIGINT, T.STRING, T.TEMPLATE, T.LPAREN, T.LBRACKET, T.LBRACE, T.PLUS, T.MINUS, T.BANG, T.TILDE,
        T.INC, T.DEC, T.SLASH, T.SLASH_ASSIGN, T.PRIVATE_NAME, T.ELLIPSIS, T.STAR, T.AT -> true
        else -> false
    }

    /** Whether the current token can begin a statement (after one with no `;`). */
    private fun startsStatementHere(): Boolean = when (type) {
        T.NAME -> lex.escaped || (lex.value != "in" && lex.value != "instanceof" && lex.value != "of")
        T.NUM, T.BIGINT, T.STRING, T.TEMPLATE, T.LBRACE, T.BANG, T.TILDE, T.INC, T.DEC, T.AT -> true
        else -> false
    }

    override fun blockEndsEarly() {
        record(lex.start, "Unexpected end of input", ProblemKind.UNEXPECTED, unclosedBrace(), force = true)
    }

    override fun casesEndEarly() = blockEndsEarly()

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

    override fun declarePrivate(key: PrivateIdentifier, kind: String, isStatic: Boolean) {
        // a failed member's name, declared for the members using it: one declaring it again is no duplicate
        val pn = privateNames!!
        if (pn.declared[key.name] == RECOVERED) pn.declared.remove(key.name)
        super.declarePrivate(key, kind, isStatic)
    }

    // ============================================================ statements

    override fun readFirstToken() {
        while (true) {
            try {
                next()
                return
            } catch (e: JSSyntaxError) {
                record(e.pos, e.rawMessage, ProblemKind.LEXICAL, force = true)
                resumeAfterLexError()
            }
        }
    }

    /** What a statement or a member may change of the parser's state, as it was when it began. */
    private inner class Mark {
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

        fun restore() {
            while (scopeStack.size > depth) scopeStack.removeAt(scopeStack.size - 1)
            labels = labelList
            while (labels.size > labelCount) labels.removeAt(labels.size - 1)
            privateNames = names
            strict = wasStrict
            yieldPos = y; awaitPos = a; awaitIdentPos = ai
            switchCaseDepth = switchDepth
            pendingDecorators = null
        }

        /** The node for what failed, over the source from its start to where the parse goes on. */
        fun finish(node: ErrorNode): ErrorNode {
            node.start = startPos
            node.end = maxOf(startPos, lex.prevEnd)
            node.line = from.tokLine
            node.col = from.tokCol
            return node
        }
    }

    override fun parseListedStatement(list: Int): Node {
        if (stopped == null && limits.isCancelled?.invoke() == true) stopped = ParseStop.CANCELLED
        if (stopped != null) {
            if (list != LIST_TOP) throw Stop()
            return stopHere()
        }
        val m = Mark()
        val exported = if (list == LIST_TOP && isKw("export")) HashSet(exportedNames) else null
        val undefinedExported = if (exported != null) LinkedHashMap(undefinedExports) else null
        val outerStructural = structuralInStatement
        val outerStart = statementStart
        structuralInStatement = false
        statementStart = m.startPos
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
            // (never an echo: parseTolerantly repairs the token and reads the source again)
            record(e.pos, e.rawMessage, ProblemKind.LEXICAL, force = true)
            errorAt = maxOf(e.pos, lex.start)
        } catch (e: Stop) {
            if (list != LIST_TOP) throw e
            errorAt = -1
        } finally {
            structuralInStatement = outerStructural
            statementStart = outerStart
        }
        // undo what the statement changed, keeping the names it declared in the scopes around it
        m.restore()
        if (exported != null) {
            exportedNames.clear(); exportedNames.addAll(exported)
            undefinedExports.clear(); undefinedExports.putAll(undefinedExported!!)
        }
        val node = withPatternNames(undoDeclarations(m.declaredMark, m.depth), m.from)
        if (list == LIST_TOP) {
            for (id in node.lexicalNames) recoveredTopNames.add(id.name)
            for (id in node.varNames) recoveredTopNames.add(id.name)
        }
        if (errorAt < 0) {
            toEnd()
        } else {
            skip(m.from, m.startPos, errorAt, list)
            // whatever the skip found, the next statement begins after this one's first token
            if (lex.start <= m.startPos && type != T.EOF) skipNext()
        }
        return m.finish(node)
    }

    override fun parseClassMembers(body: ArrayList<Node>, derived: Boolean): FunctionNode? {
        var ctor: FunctionNode? = null
        val pn = privateNames!!
        while (!eat(T.RBRACE)) {
            if (eat(T.SEMI)) continue
            if (type == T.EOF) {
                // the class keeps the members read: the blocks around end here too
                blockEndsEarly()
                break
            }
            val m = Mark()
            val outerStructural = structuralInStatement
            val outerStart = statementStart
            structuralInStatement = false
            statementStart = m.startPos
            var errorAt: Int
            try {
                ctor = parseClassMember(body, derived, ctor)
                continue
            } catch (e: Recover) {
                errorAt = e.at
            } catch (e: JSSyntaxError) {
                record(e.pos, e.rawMessage, ProblemKind.LEXICAL, force = true)
                errorAt = maxOf(e.pos, lex.start)
            } finally {
                structuralInStatement = outerStructural
                statementStart = outerStart
            }
            m.restore()
            // (a member declares nothing outside its own scopes)
            undoDeclarations(m.declaredMark, m.depth)
            privateNameOf(m.from)?.let { pn.declared.putIfAbsent(it, RECOVERED) }
            skip(m.from, m.startPos, errorAt, MEMBER)
            if (lex.start <= m.startPos && type != T.EOF) skipNext()
            body.add(m.finish(ErrorNode(emptyList(), emptyList())))
        }
        return ctor
    }

    /** The private name a member that begins at [from] declares (`static async *#m(`), if any. */
    private fun privateNameOf(from: LexState): String? {
        lex.restore(from)
        while (type == T.STAR || (type == T.NAME && !lex.escaped && lex.value in MEMBER_MODIFIERS)) {
            if (!skipNextOrFail()) return null
        }
        return if (type == T.PRIVATE_NAME) lex.value as String else null
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

    /**
     * [node] with the names of a failed `var`/`let`/`const` declaration's pattern that the parser had not declared yet
     * (`const { a, b } = (;` fails after them, `const ( { a, b } = x` before): read from the tokens before its first `=`
     * outside brackets, a name not followed by `:` (a key), `(` or `.`.
     */
    private fun withPatternNames(node: ErrorNode, from: LexState): ErrorNode {
        lex.restore(from)
        if (type != T.NAME || lex.escaped) return node
        val lexical = when (lex.value) {
            "var" -> false
            "let", "const", "using" -> true
            else -> return node
        }
        val found = ArrayList<Identifier>()
        var depth = 0
        var prev: T? = null
        var count = 0
        while (skipNextOrFail() && type != T.EOF && count++ < 1000) {
            if (lex.nlBefore && depth == 0 && count > 1 && startsStatement(true)) break
            when (type) {
                T.LBRACE, T.LBRACKET, T.LPAREN -> depth++
                T.RBRACE, T.RBRACKET, T.RPAREN -> depth = maxOf(0, depth - 1)
                T.SEMI -> break
                T.NAME -> if (!lex.escaped && prev != T.DOT && prev != T.QDOT && lex.value !in keywords) {
                    val name = lex.value as String
                    val pos = lex.start
                    val s = lex.save()
                    val after = if (skipNextOrFail()) type else T.EOF
                    lex.restore(s)
                    if (after != T.COLON && after != T.LPAREN && after != T.DOT && after != T.QDOT) found.add(identifierAt(name, pos))
                }
                else -> {}
            }
            if (type == T.ASSIGN && depth == 0) break
            prev = type
        }
        if (found.isEmpty()) return node
        val names = if (lexical) node.lexicalNames else node.varNames
        val known = names.mapTo(HashSet()) { it.name }
        val more = names + found.filter { known.add(it.name) }
        return if (lexical) ErrorNode(more, node.varNames) else ErrorNode(node.lexicalNames, more)
    }

    private fun identifierAt(name: String, pos: Int): Identifier {
        val id = Identifier(name)
        id.start = pos
        id.end = pos + name.length
        return id
    }

    // ============================================================ passing over what failed

    /**
     * Reads the failed statement ([MEMBER]: member) again from its start ([from]), past the token it failed at
     * ([errorAt]), to where the next can begin:
     * - a `;` outside the brackets the statement opened (and outside a `for (`), or the `}` of a block of its own;
     * - a line that begins with a statement's keyword (a member's name), outside the statement's blocks;
     * - the `}` of the block (class body) around, or a switch's next case.
     *
     * Brackets are counted from the statement's start, so that those opened before the error close with it, and a
     * closer closes what was left open inside it (`f(a, {)`). A regular expression and a template are read as one
     * token (no `}` or quote in one counts), which only the parser decides as it reads: here a `/` begins a regular
     * expression after a token no expression ends with.
     */
    private fun skip(from: LexState, startPos: Int, errorAt: Int, list: Int) {
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
            if (lex.start > startPos && lex.nlBefore && braces == 0) {
                if (list == MEMBER) {
                    if (past && startsMember()) return
                } else if (startsStatement(stack.isEmpty()) && (past || stack.isEmpty())) return
            }
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
                    // the end of the block (class body) around; at the top level, a `}` that closes nothing goes with
                    // the statement
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

    /** A class member's first token. */
    private fun startsMember(): Boolean = when (type) {
        T.NAME, T.PRIVATE_NAME, T.STRING, T.NUM, T.BIGINT, T.LBRACKET, T.STAR, T.AT -> true
        else -> false
    }

    /**
     * The innermost `{` the source does not close (counted as [skip] does, over the whole source), for an error at its
     * end; -1 if there is none.
     */
    private fun unclosedBrace(): Int {
        if (unclosedBrace != -2) return unclosedBrace
        val saved = lex.save()
        val opens = ArrayList<Int>()
        // (as skip's stack, with positions: a '`' is a template substitution, -1)
        lex.pos = 0
        lex.line = 1
        lex.lineStart = 0
        skipNext()
        var prevType: T? = null
        var prevWord: String? = null
        var prevTail = true
        while (type != T.EOF) {
            when (type) {
                T.SLASH, T.SLASH_ASSIGN -> if (regexAllowed(prevType, prevWord, prevTail)) {
                    try {
                        lex.rescanRegExp()
                    } catch (e: JSSyntaxError) {
                        resumeAfterLexError()
                    }
                }
                T.LBRACE -> opens.add(lex.start)
                T.TEMPLATE -> if (!lex.templateTail) opens.add(-1)
                T.RBRACE -> if (opens.isNotEmpty()) {
                    val top = opens.removeAt(opens.size - 1)
                    if (top == -1) {
                        try {
                            lex.rescanTemplateContinuation()
                            if (!lex.templateTail) opens.add(-1)
                        } catch (e: JSSyntaxError) {
                            resumeAfterLexError()
                        }
                    }
                }
                else -> {}
            }
            prevType = type
            prevWord = if (type == T.NAME && !lex.escaped) lex.value as String else null
            prevTail = type != T.TEMPLATE || lex.templateTail
            skipNext()
        }
        lex.restore(saved)
        unclosedBrace = opens.lastOrNull { it >= 0 } ?: -1
        return unclosedBrace
    }

    /** The next token, the lexer's errors passed over (they are part of what failed). */
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

    /** The next token; false if the lexer could not read it. */
    private fun skipNextOrFail(): Boolean = try {
        lex.next()
        true
    } catch (e: JSSyntaxError) {
        false
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

        /** [skip] for a class member (the statement lists are LIST_*). */
        const val MEMBER = 3

        /** A failed member's private name, declared so that the members using it are no errors. */
        const val RECOVERED = "recovered"

        val STATEMENT_KEYWORDS = hashSetOf(
            "var", "let", "const", "if", "for", "while", "do", "return", "switch", "try", "throw", "break", "continue",
            "import", "export", "using", "debugger",
        )
        /** Words that begin declarations at a line's start, and expressions inside brackets (`f(\n function () {})`). */
        val DECLARATION_WORDS = hashSetOf("function", "class", "async")
        val MEMBER_MODIFIERS = hashSetOf("static", "async", "get", "set", "accessor")
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

/**
 * Parser.parseTolerant: the recovery mode's parse, then again over a copy of the source in which the token the lexer
 * could not read is replaced by one it can, of the same length (so that every offset stays the source's): an
 * unterminated string by `""` and spaces to the line's end, a regular expression left open by `0`, a template or
 * comment left open by its close at the source's end, a bad escape's `\` by `_`, a malformed number by `0`, a
 * character no token begins with by a space. The parser then reads on in the statement where the lexer stopped (and
 * the one before it, still reading its last token). At most [MAX_REPAIRS] times; the lexer's errors after those end
 * their statements.
 */
@UnstableParserApi
internal fun parseTolerantly(src: String, options: ParseOptions, limits: ParseLimits): TolerantParse {
    var source = src
    val repaired = ArrayList<ParseProblem>()
    val repairedKeys = HashSet<String>()
    var first: ParseProblem? = null
    var parser: RecoveringParser
    var program: Program
    while (true) {
        parser = RecoveringParser(source, options, limits, repaired.map { it.pos }.sorted().toIntArray())
        program = parser.parseTopLevel()
        if (source === src) first = parser.problems.firstOrNull()
        if (repaired.size == MAX_REPAIRS || parser.stopped != null) break
        val lexical = parser.problems.firstOrNull { it.kind == ProblemKind.LEXICAL } ?: break
        if (!repairedKeys.add("${lexical.pos}\u0000${lexical.message}")) break
        source = repairToken(source, lexical) ?: break
        repaired.add(lexical)
    }
    program.source = src
    // the errors in the source's order, the engine's first
    val problems = ArrayList<ParseProblem>(parser.problems.size + repaired.size)
    problems.addAll(parser.problems)
    problems.addAll(repaired)
    problems.sortBy { it.pos }
    if (first != null) {
        val at = problems.indexOfFirst { it.pos == first.pos && it.message == first.message }
        if (at > 0) problems.add(0, problems.removeAt(at))
    }
    var stopped = parser.stopped
    if (problems.size > limits.maxProblems) {
        while (problems.size > limits.maxProblems) problems.removeAt(problems.size - 1)
        stopped = ParseStop.LIMIT
    }
    return TolerantParse(program, problems, stopped)
}

/** How many times [parseTolerantly] repairs a token and reads the source again. */
private const val MAX_REPAIRS = 20

/** [s] with the token the lexer could not read at [p] replaced by one it can, of the same length; null if it cannot. */
private fun repairToken(s: String, p: ParseProblem): String? {
    val pos = p.pos
    if (pos < 0 || pos >= s.length) return null
    val b = StringBuilder(s)
    fun lineEnd(from: Int): Int {
        var i = from
        while (i < s.length && !Chars.isLineTerminator(s[i].code)) i++
        return i
    }
    /** [from, to) as [with] then spaces. */
    fun fill(from: Int, to: Int, with: String) {
        for (i in from until to) b.setCharAt(i, if (i - from < with.length) with[i - from] else ' ')
    }
    val msg = p.message
    when {
        s[pos] == '`' || msg == "Unterminated template literal" -> {
            // the template (all the rest, as the engine reads it) closes at the source's end
            val last = s.length - 1
            if (last == pos) b.setCharAt(last, ' ') else b.setCharAt(last, '`')
        }
        msg == "Unterminated string constant" -> {
            val end = lineEnd(pos)
            fill(pos, end, if (end - pos >= 2) "\"\"" else "")
        }
        msg == "Unterminated comment" -> for (i in pos until s.length) if (!Chars.isLineTerminator(s[i].code)) b.setCharAt(i, ' ')
        msg.startsWith("Invalid regular expression: missing /") -> fill(pos, lineEnd(pos), "0")
        s[pos] == '\\' -> b.setCharAt(pos, '_')
        else -> {
            // a number with what cannot be in one (`0x`, `1_`, `3in`, `08n`): the whole run of word characters
            var from = pos
            var to = pos
            fun word(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$' || c == '.'
            while (from > 0 && word(s[from - 1])) from--
            while (to < s.length && word(s[to])) to++
            if (to > from && (s[from].isDigit() || (s[from] == '.' && from + 1 < s.length && s[from + 1].isDigit()))) fill(from, to, "0")
            else fill(pos, pos + Character.charCount(s.codePointAt(pos)), "")
        }
    }
    val r = b.toString()
    return if (r == s) null else r
}
