package dev.mooner.neonjs.parser

/**
 * ECMAScript parser producing an ESTree-like AST and reporting early errors as [JSSyntaxError].
 */
object Parser {
    /** Parses a Script or Module. */
    @JvmStatic
    fun parse(src: String, options: ParseOptions = ParseOptions()): Program = ParserImpl(src, options).parseTopLevel()

    /**
     * Parses the parts of a dynamic function (Function / GeneratorFunction / AsyncFunction constructors).
     * Returns the function node and the synthesized source text used for toString.
     */
    @JvmStatic
    fun parseDynamicFunction(params: String, body: String, isAsync: Boolean, isGenerator: Boolean, sourceName: String? = null): Pair<FunctionNode, String> {
        val prefix = when {
            isAsync && isGenerator -> "async function*"
            isAsync -> "async function"
            isGenerator -> "function*"
            else -> "function"
        }
        val full = "$prefix anonymous($params\n) {\n$body\n}"
        // Parameters and body must each be well-formed on their own.
        ParserImpl("($params\n)", ParseOptions(sourceName = sourceName)).checkStandaloneParams(isAsync, isGenerator)
        ParserImpl(body, ParseOptions(sourceName = sourceName)).checkStandaloneBody(isAsync, isGenerator)
        val fn = ParserImpl(full, ParseOptions(sourceName = sourceName)).parseSingleFunction(isAsync, isGenerator)
        fn.name = "anonymous"
        return fn to full
    }
}

internal class ParserImpl(src: String, options: ParseOptions) : ExpressionParser(src, options) {

    private val undefinedExports = LinkedHashMap<String, Int>()
    private val exportedNames = HashSet<String>()

    // ============================================================ entry points

    fun parseTopLevel(): Program {
        enterScope(SCOPE_TOP)
        next()
        val body = parseStatementListTop()
        if (inModule) {
            resolvePendingExports()
            for ((name, pos) in undefinedExports) {
                raise(pos, "Export '$name' is not defined")
            }
        }
        val prog = Program(body, inModule)
        prog.start = 0; prog.end = src.length; prog.line = 1; prog.col = 0
        prog.strict = strict
        prog.source = src
        exitScope()
        return prog
    }

    private fun parseStatementListTop(): List<Node> {
        val body = ArrayList<Node>()
        var allowDirectives = true
        var firstOctal = -1
        while (type != T.EOF) {
            val stmt = parseStatement(null, topLevel = true)
            if (allowDirectives) {
                val d = directiveOf(stmt)
                if (d != null) {
                    if (d.hasLegacyOctal && firstOctal < 0) firstOctal = d.start
                    if (d.raw.length == 12 && d.raw.substring(1, 11) == "use strict") {
                        if (firstOctal >= 0) raise(firstOctal, "Octal escape sequences are not allowed in strict mode")
                        strict = true
                    }
                } else allowDirectives = false
            }
            body.add(stmt)
        }
        return body
    }

    private fun directiveOf(stmt: Node): StringLiteral? {
        if (stmt is ExpressionStatement) {
            val e = stmt.expression
            if (e is StringLiteral && !e.parenthesized) {
                stmt.directive = e.raw.substring(1, e.raw.length - 1)
                return e
            }
        }
        return null
    }

    internal fun checkStandaloneParams(isAsync: Boolean, isGenerator: Boolean) {
        enterScope(SCOPE_TOP)
        enterScope(functionFlags(isAsync, isGenerator))
        next()
        expect(T.LPAREN)
        parseBindingList(T.RPAREN, false, true)
        if (type != T.EOF) unexpected()
    }

    internal fun checkStandaloneBody(isAsync: Boolean, isGenerator: Boolean) {
        enterScope(SCOPE_TOP)
        enterScope(functionFlags(isAsync, isGenerator))
        next()
        parseBlockBody(false, true, null)
    }

    internal fun parseSingleFunction(isAsync: Boolean, isGenerator: Boolean): FunctionNode {
        enterScope(SCOPE_TOP)
        next()
        val p = sp()
        if (isAsync) expectContextual("async")
        expectKw("function")
        if (isGenerator) expect(T.STAR)
        if (!isContextual("anonymous")) unexpected()
        next()
        val fn = parseFunctionRest(p, null, isAsync, isGenerator, FunctionKind.NORMAL, false)
        if (type != T.EOF) unexpected()
        return fn
    }

    // ============================================================ statements

    override fun parseBlockBody(endsWithBrace: Boolean, directives: Boolean, onDirective: ((StringLiteral) -> Unit)?): List<Node> {
        val body = ArrayList<Node>()
        var allowDirectives = directives
        var firstOctal = -1
        val end = if (endsWithBrace) T.RBRACE else T.EOF
        while (type != end) {
            if (type == T.EOF) unexpected()
            val stmt = parseStatement(null)
            if (allowDirectives) {
                val d = directiveOf(stmt)
                if (d != null) {
                    if (d.hasLegacyOctal && firstOctal < 0) firstOctal = d.start
                    if (onDirective != null) onDirective(d)
                    if (d.raw.length == 12 && d.raw.substring(1, 11) == "use strict" && firstOctal >= 0) {
                        raise(firstOctal, "Octal escape sequences are not allowed in strict mode")
                    }
                } else allowDirectives = false
            }
            body.add(stmt)
        }
        if (endsWithBrace) next()
        return body
    }

    override fun parseBlock(newScope: Boolean): BlockStatement {
        val p = sp()
        expect(T.LBRACE)
        if (newScope) enterScope(0)
        val body = parseBlockBody(true, false, null)
        if (newScope) exitScope()
        return fin(BlockStatement(body), p)
    }

    /** Is the current `let` the start of a lexical declaration? */
    private fun isLet(context: String?): Boolean {
        return isContextual("let") && lookahead {
            if (type == T.LBRACKET) return@lookahead true
            if (context != null) return@lookahead false
            if (type == T.LBRACE) return@lookahead true
            if (type == T.NAME) {
                val v = lex.value as String
                // `let` followed by an identifier on the next line is still a declaration, except `let \n let`?
                return@lookahead lex.escaped || (v != "in" && v != "instanceof")
            }
            false
        }
    }

    private fun isAsyncFunction(): Boolean {
        return isContextual("async") && lookahead { isKw("function") && !lex.nlBefore }
    }

    private fun isUsing(isFor: Boolean): Boolean {
        return isContextual("using") && lookahead {
            if (lex.nlBefore || type != T.NAME) return@lookahead false
            val v = lex.value as String
            if (isFor && v == "of" && !lex.escaped) {
                // `for (using of ...)`: `using of = x` is a declaration only when followed by '='
                return@lookahead lookahead { type == T.ASSIGN }
            }
            !(v == "in" || v == "instanceof") || lex.escaped
        }
    }

    private fun isAwaitUsing(): Boolean {
        return isContextual("await") && inAsync && lookahead {
            if (lex.nlBefore || !isContextual("using")) return@lookahead false
            lookahead { !lex.nlBefore && type == T.NAME }
        }
    }

    fun parseStatement(context: String?, topLevel: Boolean = false): Node {
        val p = sp()
        if (type == T.LBRACE) return parseBlock(true)
        if (type == T.AT) {
            // `@dec class C {}` or `@dec export [default] class C {}`
            if (context != null) unexpected()
            pendingDecorators = parseDecorators()
            val exp = isKw("export")
            if (!isKw("class") && !exp) unexpected()
            if (exp && lookahead { isKw("default") && lookahead { type == T.AT } || type == T.AT }) raise(p.start, "Decorators cannot appear both before and after 'export'")
            val st = parseStatement(context, topLevel)
            if (pendingDecorators != null) raise(p.start, "Decorators must be followed by a class declaration")
            return st
        }
        if (type == T.SEMI) {
            next(); return fin(EmptyStatement(), p)
        }
        if (type == T.NAME && !lex.escaped) {
            if (isLet(context)) {
                if (context != null) raise(p.start, "Lexical declaration cannot appear in a single-statement context")
                next()
                val decl = parseVar(p, false, VarKind.LET)
                semicolon()
                return fin(decl, p)
            }
            when (lex.value) {
                "break", "continue" -> return parseBreakContinue(p)
                "debugger" -> { next(); semicolon(); return fin(DebuggerStatement(), p) }
                "do" -> return parseDoWhile(p)
                "for" -> return parseFor(p)
                "function" -> {
                    if (context != null && (strict || (context != "if" && context != "label"))) {
                        raise(p.start, if (strict) "In strict mode code, functions can only be declared at top level or inside a block" else "Function declarations are not allowed in this context")
                    }
                    next()
                    return parseFunctionStatement(p, false, context)
                }
                "class" -> {
                    if (context != null) unexpected()
                    val cls = parseClass(true, false)
                    return fin(ClassDeclaration(cls), p)
                }
                "if" -> return parseIf(p)
                "return" -> return parseReturn(p)
                "switch" -> return parseSwitch(p)
                "throw" -> return parseThrow(p)
                "try" -> return parseTry(p)
                "const", "var" -> {
                    val kind = if (lex.value == "var") VarKind.VAR else VarKind.CONST
                    if (context != null && kind != VarKind.VAR) raise(p.start, "Lexical declaration cannot appear in a single-statement context")
                    next()
                    val decl = parseVar(p, false, kind)
                    semicolon()
                    return fin(decl, p)
                }
                "while" -> return parseWhile(p)
                "with" -> return parseWith(p)
                "import" -> {
                    val nt = lookahead { type }
                    if (nt != T.LPAREN && nt != T.DOT) {
                        if (!topLevel || !inModule) raise(p.start, if (!inModule) "Cannot use import statement outside a module" else "'import' and 'export' may only appear at the top level")
                        return parseImport(p)
                    }
                }
                "export" -> {
                    if (!topLevel || !inModule) raise(p.start, if (!inModule) "Unexpected token 'export'" else "'import' and 'export' may only appear at the top level")
                    return parseExport(p)
                }
                "async" -> if (isAsyncFunction()) {
                    if (context != null) raise(p.start, "Async functions can only be declared at the top level or inside a block")
                    next()
                    next()
                    return parseFunctionStatement(p, true, context)
                }
                "using" -> if (isUsing(false)) {
                    if (context != null) raise(p.start, "Lexical declaration cannot appear in a single-statement context")
                    if (scopeStack.size == 1 && !inModule) raise(p.start, "Using declaration cannot appear in the top level of a script")
                    if (isInSwitchCaseDirectly()) raise(p.start, "Using declaration cannot appear directly in a switch case")
                    next()
                    val decl = parseVar(p, false, VarKind.USING)
                    semicolon()
                    return fin(decl, p)
                }
                "await" -> if (isAwaitUsing()) {
                    if (context != null) raise(p.start, "Lexical declaration cannot appear in a single-statement context")
                    if (scopeStack.size == 1 && !inModule) raise(p.start, "Using declaration cannot appear in the top level of a script")
                    if (isInSwitchCaseDirectly()) raise(p.start, "Using declaration cannot appear directly in a switch case")
                    next(); next()
                    val decl = parseVar(p, false, VarKind.AWAIT_USING)
                    semicolon()
                    return fin(decl, p)
                }
            }
        }
        val startType = type
        val expr = parseExpression()
        if (startType == T.NAME && expr is Identifier && !expr.parenthesized && type == T.COLON) {
            next()
            return parseLabeled(p, expr, context)
        }
        semicolon()
        return fin(ExpressionStatement(expr), p)
    }

    private var switchCaseDepth = -1

    private fun isInSwitchCaseDirectly(): Boolean = switchCaseDepth == scopeStack.size

    private fun parseFunctionStatement(p: SP, isAsync: Boolean, context: String?): Node {
        val hanging = context != null
        if (type == T.STAR && hanging) unexpected()
        val isGenerator = eat(T.STAR)
        if (hanging && isAsync) unexpected()
        val id = parseBindingIdent()
        if (strict && (id.name == "eval" || id.name == "arguments")) raise(id.start, "Unexpected eval or arguments in strict mode")
        // Annex B: function in if-statement body behaves as if in its own block; label bodies are declarations
        if (context == null || !context.contains("if")) {
            val bind = if (strict || isGenerator || isAsync) {
                if (treatFunctionsAsVarInScope(currentScope)) Bind.VAR else Bind.LEXICAL
            } else Bind.FUNCTION
            declareName(id.name, bind, id.start)
        }
        val fn = parseFunctionRest(p, id, isAsync, isGenerator, FunctionKind.NORMAL, isStatement = true)
        return fin(FunctionDeclaration(fn), p)
    }

    private fun parseBreakContinue(p: SP): Node {
        val isBreak = lex.value == "break"
        next()
        var label: String? = null
        if (!eat(T.SEMI) && !canInsertSemicolon()) {
            if (type != T.NAME) unexpected()
            val lp = lex.start
            label = parseLabelIdent()
            semicolon()
            var found = false
            for (i in labels.indices.reversed()) {
                val l = labels[i]
                if (l.name == label) {
                    if (!isBreak && l.kind != 1) raise(lp, "Illegal continue statement: '$label' does not denote an iteration statement")
                    found = true
                    break
                }
            }
            if (!found) raise(lp, "Undefined label '$label'")
        } else {
            var found = false
            for (i in labels.indices.reversed()) {
                val l = labels[i]
                if (l.name == null && (l.kind == 1 || (isBreak && l.kind == 2))) { found = true; break }
            }
            if (!found) raise(p.start, if (isBreak) "Illegal break statement" else "Illegal continue statement: no surrounding iteration statement")
        }
        return fin(if (isBreak) BreakStatement(label) else ContinueStatement(label), p)
    }

    private fun parseLabelIdent(): String {
        val pos = lex.start
        val name = lex.value as String
        if (lex.escaped && keywords.contains(name)) raise(pos, "Keyword must not contain escaped characters")
        checkUnreserved(name, pos)
        next()
        return name
    }

    private fun pushLoopLabel() {
        labels.add(Label(null, 1, lex.start))
    }

    private fun parseDoWhile(p: SP): Node {
        next()
        pushLoopLabel()
        val body = parseStatement("do")
        labels.removeAt(labels.size - 1)
        expectKw("while")
        val test = parseParenExpression()
        eat(T.SEMI)
        return fin(DoWhileStatement(body, test), p)
    }

    private fun parseWhile(p: SP): Node {
        next()
        val test = parseParenExpression()
        pushLoopLabel()
        val body = parseStatement("while")
        labels.removeAt(labels.size - 1)
        return fin(WhileStatement(test, body), p)
    }

    private fun parseParenExpression(): Node {
        expect(T.LPAREN)
        val e = parseExpression()
        expect(T.RPAREN)
        return e
    }

    private fun parseIf(p: SP): Node {
        next()
        val test = parseParenExpression()
        val cons = parseStatement("if")
        val alt = if (eatKw("else")) parseStatement("if") else null
        return fin(IfStatement(test, cons, alt), p)
    }

    private fun parseReturn(p: SP): Node {
        val vs = currentVarScope()
        val inFn = vs.flags and SCOPE_FUNCTION != 0 && !vs.inClassFieldInit
        if (!inFn && !(vs.flags and SCOPE_TOP != 0 && options.allowReturnOutsideFunction)) raise(p.start, "Illegal return statement")
        next()
        var arg: Node? = null
        if (!eat(T.SEMI) && !canInsertSemicolon()) {
            arg = parseExpression()
            semicolon()
        }
        return fin(ReturnStatement(arg), p)
    }

    private fun parseThrow(p: SP): Node {
        next()
        if (lex.nlBefore) raise(lex.prevEnd, "Illegal newline after throw")
        val arg = parseExpression()
        semicolon()
        return fin(ThrowStatement(arg), p)
    }

    private fun parseWith(p: SP): Node {
        if (strict) raise(p.start, "Strict mode code may not include a with statement")
        next()
        val obj = parseParenExpression()
        val body = parseStatement("with")
        return fin(WithStatement(obj, body), p)
    }

    private fun parseSwitch(p: SP): Node {
        next()
        val disc = parseParenExpression()
        val cases = ArrayList<SwitchCase>()
        expect(T.LBRACE)
        labels.add(Label(null, 2, lex.start))
        enterScope(0)
        val oldSwitchDepth = switchCaseDepth
        switchCaseDepth = scopeStack.size
        var sawDefault = false
        var curTest: Node? = null
        var curStmts: ArrayList<Node>? = null
        var curP: SP? = null
        fun flush() {
            if (curStmts != null) cases.add(fin(SwitchCase(curTest, curStmts!!), curP!!))
        }
        while (type != T.RBRACE) {
            if (isKw("case") || isKw("default")) {
                val isCase = isKw("case")
                flush()
                curP = sp()
                next()
                if (isCase) {
                    curTest = parseExpression()
                } else {
                    if (sawDefault) raise(lex.prevEnd, "More than one default clause in switch statement")
                    sawDefault = true
                    curTest = null
                }
                expect(T.COLON)
                curStmts = ArrayList()
            } else {
                if (curStmts == null) unexpected()
                curStmts.add(parseStatement(null))
            }
        }
        flush()
        next()
        switchCaseDepth = oldSwitchDepth
        exitScope()
        labels.removeAt(labels.size - 1)
        return fin(SwitchStatement(disc, cases), p)
    }

    private fun parseTry(p: SP): Node {
        next()
        val block = parseBlock(true)
        var handler: CatchClause? = null
        if (isKw("catch")) {
            val cp = sp()
            next()
            var param: Node? = null
            if (eat(T.LPAREN)) {
                param = parseBindingAtom()
                val simple = param is Identifier
                enterScope(if (simple) SCOPE_SIMPLE_CATCH else 0)
                if (simple) checkLValSimple(param, Bind.SIMPLE_CATCH) else checkLValPattern(param, Bind.LEXICAL)
                expect(T.RPAREN)
            } else enterScope(0)
            val body = parseBlock(false)
            exitScope()
            handler = fin(CatchClause(param, body), cp)
        }
        val finalizer = if (eatKw("finally")) parseBlock(true) else null
        if (handler == null && finalizer == null) raise(lex.start, "Missing catch or finally after try")
        return fin(TryStatement(block, handler, finalizer), p)
    }

    private fun parseLabeled(p: SP, label: Identifier, context: String?): Node {
        val name = label.name
        if (name == "await" && (inAsync || inModule)) raise(label.start, "Cannot use 'await' as a label")
        if (name == "yield" && (inGenerator || strict)) raise(label.start, "Cannot use 'yield' as a label")
        for (l in labels) if (l.name == name) raise(label.start, "Label '$name' has already been declared")
        val kind = if (isKw("for") || isKw("while") || isKw("do")) 1 else if (isKw("switch")) 2 else 0
        for (i in labels.indices.reversed()) {
            val l = labels[i]
            if (l.statementStart == p.start) {
                l.statementStart = lex.start
                l.kind = kind
            } else break
        }
        labels.add(Label(name, kind, lex.start))
        val newContext = if (context == null) "label" else if (!context.contains("label")) context + "label" else context
        val body = parseStatement(newContext)
        labels.removeAt(labels.size - 1)
        return fin(LabeledStatement(name, body), p)
    }

    private fun parseVar(p: SP, isFor: Boolean, kind: VarKind): VariableDeclaration {
        val decls = ArrayList<VariableDeclarator>()
        while (true) {
            val dp = sp()
            val id: Node = if (kind == VarKind.USING || kind == VarKind.AWAIT_USING) {
                if (type != T.NAME) unexpected()
                parseBindingIdent()
            } else parseBindingAtom()
            val bind = if (kind == VarKind.VAR) Bind.VAR else Bind.LEXICAL
            if (kind != VarKind.VAR && id is Identifier && id.name == "let") raise(id.start, "let is disallowed as a lexically bound name")
            checkLValPattern(id, bind)
            var init: Node? = null
            if (eat(T.ASSIGN)) {
                init = parseMaybeAssign(isFor)
            } else {
                val forInOf = isFor && (isKw("in") || isContextual("of"))
                if (kind == VarKind.CONST && !forInOf) raise(lex.start, "Missing initializer in const declaration")
                if ((kind == VarKind.USING || kind == VarKind.AWAIT_USING) && !(isFor && isContextual("of"))) raise(lex.start, "Missing initializer in using declaration")
                if (id !is Identifier && !forInOf) raise(lex.start, "Missing initializer in destructuring declaration")
            }
            decls.add(fin(VariableDeclarator(id, init), dp))
            if (!eat(T.COMMA)) break
        }
        return fin(VariableDeclaration(kind, decls), p)
    }

    private fun parseFor(p: SP): Node {
        next()
        var isAwait = false
        if (isContextual("await")) {
            if (!inAsync) raise(lex.start, "for await is only valid in async functions and the top level bodies of modules")
            isAwait = true
            next()
        }
        pushLoopLabel()
        enterScope(0)
        expect(T.LPAREN)
        if (type == T.SEMI) {
            if (isAwait) unexpected()
            return parseForRest(p, null)
        }
        val isLetDecl = isLet(null)
        if (isKw("var") || isKw("const") || isLetDecl) {
            val dp = sp()
            val kind = if (isLetDecl) VarKind.LET else if (lex.value == "var") VarKind.VAR else VarKind.CONST
            next()
            val init = parseVar(dp, true, kind)
            return parseForAfterInit(p, init, isAwait)
        }
        val usingKind = if (isUsing(true)) VarKind.USING else if (isContextual("await") && inAsync && lookahead { isContextual("using") && !lex.nlBefore && lookahead { type == T.NAME && !lex.nlBefore } }) VarKind.AWAIT_USING else null
        if (usingKind != null) {
            val dp = sp()
            if (usingKind == VarKind.AWAIT_USING) next()
            next()
            val init = parseVar(dp, true, usingKind)
            if (isKw("in")) raise(lex.start, "using declarations are not allowed in for-in")
            return parseForAfterInit(p, init, isAwait)
        }
        val startsWithLet = isContextual("let")
        val containsEsc = lex.escaped
        val refErrors = DestructuringErrors()
        val initStart = lex.start
        val init = if (isAwait) parseExprSubscripts(refErrors, true) else parseExpression(true, refErrors)
        val isForOf = isContextual("of")
        if (isKw("in") || isForOf) {
            if (isAwait && isKw("in")) unexpected()
            if (isForOf && !isAwait && init.start == initStart && !containsEsc && init is Identifier && init.name == "async" && !init.parenthesized) {
                raise(init.start, "The left-hand side of a for-of loop may not be 'async'.")
            }
            if (startsWithLet && isForOf) raise(init.start, "The left-hand side of a for-of loop may not start with 'let'.")
            if (!strict && init is CallExpression && !init.parenthesized) {
                // Annex B: runtime ReferenceError
                return parseForInOf(p, init, isAwait)
            }
            val target = toAssignable(init, false, refErrors)
            checkLValPattern(target)
            return parseForInOf(p, target, isAwait)
        } else {
            checkExpressionErrors(refErrors, true)
        }
        if (isAwait) unexpected()
        return parseForRest(p, init)
    }

    private fun parseForAfterInit(p: SP, init: VariableDeclaration, isAwait: Boolean): Node {
        if ((isKw("in") || isContextual("of")) && init.declarations.size == 1) {
            if (isKw("in") && isAwait) unexpected()
            return parseForInOf(p, init, isAwait)
        }
        if (isAwait) unexpected()
        for (d in init.declarations) {
            if (d.init == null && (init.kind == VarKind.CONST || init.kind == VarKind.USING || init.kind == VarKind.AWAIT_USING)) raise(d.end, "Missing initializer in declaration")
            if (d.init == null && d.id !is Identifier) raise(d.end, "Missing initializer in destructuring declaration")
        }
        return parseForRest(p, init)
    }

    private fun parseForRest(p: SP, init: Node?): Node {
        expect(T.SEMI)
        val test = if (type == T.SEMI) null else parseExpression()
        expect(T.SEMI)
        val update = if (type == T.RPAREN) null else parseExpression()
        expect(T.RPAREN)
        val body = parseStatement("for")
        exitScope()
        labels.removeAt(labels.size - 1)
        return fin(ForStatement(init, test, update, body), p)
    }

    private fun parseForInOf(p: SP, left: Node, isAwait: Boolean): Node {
        val isIn = isKw("in")
        next()
        if (left is VariableDeclaration && left.declarations[0].init != null) {
            val d = left.declarations[0]
            if (!isIn || strict || left.kind != VarKind.VAR || d.id !is Identifier) {
                raise(left.start, (if (isIn) "for-in" else "for-of") + " loop variable declaration may not have an initializer")
            }
        }

        val right = if (isIn) parseExpression() else parseMaybeAssign(false)
        expect(T.RPAREN)
        val body = parseStatement("for")
        exitScope()
        labels.removeAt(labels.size - 1)
        return if (isIn) fin(ForInStatement(left, right, body), p) else fin(ForOfStatement(left, right, body, isAwait), p)
    }

    // ============================================================ modules

    private fun parseModuleExportName(): Pair<String, Boolean> {
        if (type == T.STRING) {
            val s = lex.value as String
            if (!isWellFormed(s)) raise(lex.start, "An export name cannot include a lone surrogate")
            next()
            return s to true
        }
        if (type != T.NAME) unexpected()
        val n = lex.value as String
        next()
        return n to false
    }

    private fun isWellFormed(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.isHighSurrogate()) {
                if (i + 1 >= s.length || !s[i + 1].isLowSurrogate()) return false
                i += 2
                continue
            }
            if (c.isLowSurrogate()) return false
            i++
        }
        return true
    }

    private fun parseModuleSource(): String {
        if (type != T.STRING) unexpected()
        val s = lex.value as String
        next()
        return s
    }

    private fun parseWithClause(): List<ImportAttribute> {
        if (!isKw("with")) return emptyList()
        next()
        expect(T.LBRACE)
        val attrs = ArrayList<ImportAttribute>()
        val seen = HashSet<String>()
        var first = true
        while (!eat(T.RBRACE)) {
            if (!first) {
                expect(T.COMMA)
                if (eat(T.RBRACE)) break
            }
            first = false
            val ap = sp()
            val key = when (type) {
                T.STRING -> lex.value as String
                T.NAME -> lex.value as String
                else -> unexpected()
            }
            next()
            if (!seen.add(key)) raise(ap.start, "Duplicate import attribute '$key'")
            expect(T.COLON)
            if (type != T.STRING) unexpected()
            val v = lex.value as String
            next()
            attrs.add(fin(ImportAttribute(key, v), ap))
        }
        return attrs
    }

    private fun parseImport(p: SP): Node {
        next()
        val specs = ArrayList<ImportSpecifier>()
        var phase: String? = null
        val source: String
        if (type == T.STRING) {
            source = parseModuleSource()
        } else {
            if ((isContextual("source") || isContextual("defer")) && lookahead { (type == T.NAME && !isContextual("from")) || type == T.STAR || (isContextual("from") && lookahead { isContextual("from") }) }) {
                phase = lex.value as String
                next()
            }
            if (type == T.NAME) {
                val ip = sp()
                val local = parseBindingIdent()
                checkLValSimple(local, Bind.LEXICAL)
                specs.add(fin(ImportSpecifier("default", local), ip))
                if (phase == null) eat(T.COMMA).let { hasMore ->
                    if (hasMore) parseImportSpecifiersRest(specs)
                }
            } else parseImportSpecifiersRest(specs)
            expectContextual("from")
            source = parseModuleSource()
        }
        val attrs = parseWithClause()
        semicolon()
        val node = fin(ImportDeclaration(specs, source, attrs), p)
        node.phase = phase
        return node
    }

    private fun parseImportSpecifiersRest(specs: MutableList<ImportSpecifier>) {
        if (type == T.STAR) {
            val ip = sp()
            next()
            expectContextual("as")
            val local = parseBindingIdent()
            checkLValSimple(local, Bind.LEXICAL)
            specs.add(fin(ImportSpecifier(ModuleNames.NAMESPACE, local), ip))
            return
        }
        expect(T.LBRACE)
        var first = true
        while (!eat(T.RBRACE)) {
            if (!first) {
                expect(T.COMMA)
                if (eat(T.RBRACE)) break
            }
            first = false
            val ip = sp()
            val namePos = lex.start
            val nameEscaped = lex.escaped
            val (imported, isString) = parseModuleExportName()
            val local: Identifier
            if (eatContextual("as")) {
                local = parseBindingIdent()
            } else {
                if (isString) raise(namePos, "String import names must be followed by 'as'")
                if (nameEscaped && keywords.contains(imported)) raise(namePos, "Keyword must not contain escaped characters")
                checkUnreserved(imported, namePos, true)
                local = Identifier(imported)
                local.start = namePos; local.end = lex.prevEnd; local.line = ip.line; local.col = ip.col
            }
            checkLValSimple(local, Bind.LEXICAL)
            specs.add(fin(ImportSpecifier(imported, local), ip))
        }
    }

    private fun addExport(name: String, pos: Int) {
        if (!exportedNames.add(name)) raise(pos, "Duplicate export of '$name'")
    }

    private fun declaredAtTop(name: String): Boolean {
        val top = scopeStack[0]
        return top.vars.contains(name) || top.lexical.contains(name) || top.functions.contains(name)
    }

    private fun parseExport(p: SP): Node {
        next()
        if (type == T.STAR) {
            next()
            var exported: String? = null
            if (eatContextual("as")) {
                val ep = lex.start
                exported = parseModuleExportName().first
                addExport(exported, ep)
            }
            expectContextual("from")
            val source = parseModuleSource()
            val attrs = parseWithClause()
            semicolon()
            return fin(ExportAllDeclaration(exported, source, attrs), p)
        }
        if (isKw("default")) {
            addExport("default", lex.start)
            next()
            val dp = sp()
            if (type == T.AT) {
                pendingDecorators = parseDecorators()
                if (!isKw("class")) unexpected()
            }
            if (isKw("function") || isAsyncFunction()) {
                val isAsync = isContextual("async")
                if (isAsync) next()
                next()
                val isGenerator = eat(T.STAR)
                var id: Identifier? = null
                if (type == T.NAME) {
                    id = parseBindingIdent()
                    declareName(id.name, Bind.FUNCTION, id.start)
                }
                val fn = parseFunctionRest(dp, id, isAsync, isGenerator, FunctionKind.NORMAL, isStatement = true)
                return fin(ExportDefaultDeclaration(fin(FunctionDeclaration(fn), dp)), p)
            }
            if (isKw("class")) {
                val cls = parseClass(true, true)
                return fin(ExportDefaultDeclaration(fin(ClassDeclaration(cls), dp)), p)
            }
            val expr = parseMaybeAssign(false)
            semicolon()
            return fin(ExportDefaultDeclaration(expr), p)
        }
        if (type == T.AT) {
            pendingDecorators = parseDecorators()
            if (!isKw("class")) unexpected()
        }
        if (type == T.NAME && !lex.escaped && (lex.value == "var" || lex.value == "let" || lex.value == "const" || lex.value == "function" || lex.value == "class" || isAsyncFunction() ||
                    (lex.value == "using" && isUsing(false)) || (lex.value == "await" && isAwaitUsing()))
        ) {
            val decl = parseStatement(null, topLevel = false)
            val names = ArrayList<Identifier>()
            when (decl) {
                is VariableDeclaration -> {
                    if (decl.kind == VarKind.USING || decl.kind == VarKind.AWAIT_USING) raise(decl.start, "Using declarations cannot be exported")
                    for (d in decl.declarations) collectBoundIdentifiers(d.id, names)
                }
                is FunctionDeclaration -> names.add(decl.function.id!!)
                is ClassDeclaration -> names.add(decl.cls.id!!)
            }
            for (n in names) addExport(n.name, n.start)
            return fin(ExportNamedDeclaration(decl, emptyList(), null, emptyList()), p)
        }
        // export { ... } [from "..."]
        expect(T.LBRACE)
        val specs = ArrayList<ExportSpecifier>()
        val localChecks = ArrayList<Triple<String, Int, Boolean>>()
        var first = true
        while (!eat(T.RBRACE)) {
            if (!first) {
                expect(T.COMMA)
                if (eat(T.RBRACE)) break
            }
            first = false
            val sp0 = sp()
            val localEscaped = lex.escaped
            val (local, localIsString) = parseModuleExportName()
            var exported = local
            if (eatContextual("as")) {
                exported = parseModuleExportName().first
            }
            addExport(exported, sp0.start)
            localChecks.add(Triple(local, sp0.start, localIsString || localEscaped))
            specs.add(fin(ExportSpecifier(local, exported, localIsString), sp0))
        }
        var source: String? = null
        var attrs: List<ImportAttribute> = emptyList()
        if (eatContextual("from")) {
            source = parseModuleSource()
            attrs = parseWithClause()
        } else {
            for ((local, pos, _) in localChecks) {
                if (specs.first { it.start == pos }.localIsString) raise(pos, "A string literal cannot be used as an exported binding without 'from'")
                if (keywords.contains(local) || strictReserved.contains(local) || local == "await") raise(pos, "Unexpected reserved word '$local'")
                if (!declaredAtTop(local)) undefinedExports.putIfAbsent(local, pos)
            }
        }
        semicolon()
        return fin(ExportNamedDeclaration(null, specs, source, attrs), p)
    }

    /** After the whole module is parsed, names declared later still satisfy pending exports. */
    private fun resolvePendingExports() {
        val it = undefinedExports.keys.iterator()
        while (it.hasNext()) if (declaredAtTop(it.next())) it.remove()
    }

}
