package dev.mooner.neonpython

import dev.mooner.neonpython.PyToken.Kind

/**
 * A recursive-descent parser of Python 3.14's grammar (CPython's Grammar/python.gram), building the trees of
 * [PyAst]: every statement and expression, soft keywords (`match`, `case`, `type`, `_`) by their context, f-strings
 * and t-strings from their tokens (PEP 701, 750). It backtracks in two places only, as the grammar's lookaheads do:
 * a parenthesized `with` (items, or an expression in parentheses) and a `match` statement (or an expression
 * starting with the name match).
 */
class PyParser(private val src: String) {
    private val lexer = PyLexer(src)
    private val toks = lexer.tokenize()
    /** The bracket the source leaves open ([PyLexer.unclosed]). */
    private val unclosed = lexer.unclosed
    private var p = 0
    /** The furthest token looked at. */
    private var far = 0

    fun parseModule(): Module = parse {
        val body = ArrayList<Stmt>()
        while (!at(Kind.END)) {
            if (at(Kind.NEWLINE)) { p++; continue }
            if (at(Kind.INDENT)) error("unexpected indent", "IndentationError")
            body.addAll(statement())
        }
        Module(body).also { it.line = 1 }
    }

    /** An expression alone (eval's input). */
    fun parseExpression(): Expression = parse {
        while (at(Kind.NEWLINE)) p++
        val e = starExpressions()
        while (at(Kind.NEWLINE)) p++
        if (!at(Kind.END)) error()
        Expression(e)
    }

    /**
     * With a bracket left open, CPython's error: reaching the end of the tokens is "'(' was never closed", and so
     * is another error once the tokens looked at went past the bracket's line (_PyPegen_tokenize_full_source_to_check_for_errors).
     */
    private inline fun <T> parse(body: () -> T): T {
        try {
            return body()
        } catch (e: PySyntaxError) {
            val u = unclosed ?: throw e
            if (toks[minOf(far, toks.size - 1)].line > u.line) neverClosed()
            throw e
        }
    }

    private fun neverClosed(): Nothing {
        val u = unclosed!!
        throw PySyntaxError("'${u.text}' was never closed", u.line, u.col).also { it.fatal = true }
    }

    // ------------------------------------------------------------------ tokens

    private val tok: PyToken get() {
        if (p > far) far = p
        val t = toks[p]
        if (t.kind == Kind.END && unclosed != null) neverClosed()
        return t
    }
    private fun peek(k: Int = 1): PyToken {
        val i = minOf(p + k, toks.size - 1)
        if (i > far) far = i
        val t = toks[i]
        if (t.kind == Kind.END && unclosed != null) neverClosed()
        return t
    }
    private fun at(k: Kind) = tok.kind == k
    private fun atOp(s: String) = tok.kind == Kind.OP && tok.text == s
    private fun atName(s: String) = tok.kind == Kind.NAME && tok.text == s
    private fun next(): PyToken = tok.also { p++ }
    private fun error(msg: String = "invalid syntax", type: String = "SyntaxError", t: PyToken = tok): Nothing =
        throw PySyntaxError(msg, t.line, t.col, type)

    private fun expectOp(s: String): PyToken = if (atOp(s)) next() else error(
        if (at(Kind.END)) "unexpected EOF while parsing" else if (s == ":") "expected ':'" else "expected '$s'")

    private fun expectName(): PyToken {
        if (tok.kind != Kind.NAME || tok.text in KEYWORDS) error(if (tok.kind == Kind.NAME) "invalid syntax" else "invalid syntax")
        return next()
    }

    private fun <T : Node> T.at(t: PyToken): T = also { it.line = t.line; it.col = t.col }

    /** A NAME that is no hard keyword. */
    private fun atIdentifier() = tok.kind == Kind.NAME && tok.text !in KEYWORDS

    // ------------------------------------------------------------------ statements

    private fun statement(): List<Stmt> = compound()?.let { listOf(it) } ?: simpleStatements()

    private fun simpleStatements(): List<Stmt> {
        val out = arrayListOf(simpleStatement())
        while (atOp(";")) {
            next()
            if (at(Kind.NEWLINE)) break
            out.add(simpleStatement())
        }
        if (!at(Kind.NEWLINE)) legacy(out.last())
        next()
        return out
    }

    /**
     * Python 2's `print x` and `exec code` (CPython's invalid_legacy_expression): a name print or exec followed by
     * expressions is the error of the missing parentheses; anything else after a statement invalid syntax.
     */
    private fun legacy(last: Stmt): Nothing {
        val n = ((last as? ExprStmt)?.value as? Name)?.takeIf { !it.parenthesized && (it.id == "print" || it.id == "exec") }
        if (n != null && !atOp("(")) {
            val save = p
            val ok = try {
                starExpressions()
                true
            } catch (e: PySyntaxError) {
                if (e.fatal) throw e
                false
            }
            p = save
            if (ok) throw PySyntaxError("Missing parentheses in call to '${n.id}'. Did you mean ${n.id}(...)?", n.line, n.col)
        }
        error()
    }

    /**
     * `:` and a block: indented statements, or simple statements on the same line; [what] the statement it is
     * ("'if' statement", "function definition") at [line], which CPython's IndentationError names.
     */
    private fun block(what: String, line: Int): List<Stmt> {
        expectOp(":")
        if (!at(Kind.NEWLINE)) return simpleStatements()
        next()
        if (!at(Kind.INDENT)) error("expected an indented block after $what on line $line", "IndentationError")
        next()
        val out = ArrayList<Stmt>()
        while (!at(Kind.DEDENT) && !at(Kind.END)) {
            if (at(Kind.NEWLINE)) { p++; continue }
            out.addAll(statement())
        }
        if (at(Kind.DEDENT)) next()
        return out
    }

    private fun compound(): Stmt? {
        val t = tok
        if (atOp("@")) return decorated()
        if (t.kind != Kind.NAME) return null
        return when (t.text) {
            "def" -> functionDef(emptyList(), false, t)
            "class" -> classDef(emptyList(), t)
            "if" -> ifStmt()
            "while" -> {
                next()
                val test = namedExpression()
                val body = block("'while' statement", t.line)
                While(test, body, elseBlock()).at(t)
            }
            "for" -> forStmt(false, t)
            "try" -> tryStmt()
            "with" -> withStmt(false, t)
            "async" -> {
                val n = peek()
                if (n.kind != Kind.NAME) error()
                when (n.text) {
                    "def" -> { next(); functionDef(emptyList(), true, t) }
                    "for" -> { next(); forStmt(true, t) }
                    "with" -> { next(); withStmt(true, t) }
                    else -> error()
                }
            }
            "match" -> matchStmt()
            else -> null
        }
    }

    private fun elseBlock(): List<Stmt> = if (atName("else")) block("'else' statement", next().line) else emptyList()

    private fun decorated(): Stmt {
        val decorators = ArrayList<Expr>()
        while (atOp("@")) {
            next()
            decorators.add(namedExpression())
            if (!at(Kind.NEWLINE)) error()
            next()
        }
        val t = tok
        return when {
            atName("def") -> functionDef(decorators, false, t)
            atName("async") && peek().kind == Kind.NAME && peek().text == "def" -> { next(); functionDef(decorators, true, t) }
            atName("class") -> classDef(decorators, t)
            else -> error()
        }
    }

    private fun functionDef(decorators: List<Expr>, async: Boolean, start: PyToken): Stmt {
        next() // def
        val name = expectName().text
        val tps = typeParams()
        expectOp("(")
        val args = parameters(")", annotations = true)
        expectOp(")")
        val returns = if (atOp("->")) { next(); expression() } else null
        val body = block("function definition", start.line)
        return FunctionDef(name, args, body, decorators, returns, tps, async).at(start)
    }

    private fun classDef(decorators: List<Expr>, start: PyToken): Stmt {
        next() // class
        val name = expectName().text
        val tps = typeParams()
        var bases: List<Expr> = emptyList()
        var keywords: List<Keyword> = emptyList()
        if (atOp("(")) {
            next()
            val (a, k) = arguments(allowGenexp = false)
            bases = a
            keywords = k
        }
        return ClassDef(name, bases, keywords, block("class definition", start.line), decorators, tps).at(start)
    }

    private fun typeParams(): List<TypeParam> {
        if (!atOp("[")) return emptyList()
        next()
        if (atOp("]")) error("Type parameter list cannot be empty")
        val out = ArrayList<TypeParam>()
        while (!atOp("]")) {
            val t = tok
            out.add(when {
                atOp("*") -> {
                    next()
                    val n = expectName().text
                    TypeVarTuple(n, if (atOp("=")) { next(); starExpression() } else null).at(t)
                }
                atOp("**") -> {
                    next()
                    val n = expectName().text
                    ParamSpec(n, if (atOp("=")) { next(); expression() } else null).at(t)
                }
                else -> {
                    val n = expectName().text
                    val bound = if (atOp(":")) { next(); expression() } else null
                    TypeVar(n, bound, if (atOp("=")) { next(); expression() } else null).at(t)
                }
            })
            if (!atOp("]")) expectOp(",")
        }
        next()
        return out
    }

    /**
     * A function's parameters up to [close] (`)`, or `:` for a lambda): positional-only ones before `/`, then
     * positional ones, `*args` or a bare `*`, keyword-only ones, `**kwargs`.
     */
    private fun parameters(close: String, annotations: Boolean): Arguments {
        val posonly = ArrayList<Arg>()
        val args = ArrayList<Arg>()
        val defaults = ArrayList<Expr>()
        var vararg: Arg? = null
        val kwonly = ArrayList<Arg>()
        val kwDefaults = ArrayList<Expr?>()
        var kwarg: Arg? = null
        var star = false
        var bareStar: PyToken? = null
        var slash = false
        fun param(starred: Boolean = false): Arg {
            val t = tok
            val n = expectName().text
            val ann = if (annotations && atOp(":")) { next(); if (starred) starExpression() else expression() } else null
            return Arg(n, ann).at(t)
        }
        while (!atOp(close)) {
            if (kwarg != null) error("arguments cannot follow var-keyword argument")
            when {
                atOp("/") -> {
                    if (slash) error("/ may appear only once")
                    if (star) error("/ must be ahead of *")
                    if (args.isEmpty()) error("at least one argument must precede /")
                    next()
                    slash = true
                    posonly.addAll(args)
                    args.clear()
                }
                atOp("*") -> {
                    if (star) error("* argument may appear only once")
                    val t = next()
                    star = true
                    if (atOp(",") || atOp(close)) bareStar = t
                    else {
                        if (atOp("=")) error("var-positional argument cannot have default value")
                        vararg = param(starred = true)
                    }
                }
                atOp("**") -> {
                    next()
                    kwarg = param()
                    if (atOp("=")) error("var-keyword argument cannot have default value")
                }
                else -> {
                    val a = param()
                    val d = if (atOp("=")) { next(); expression() } else null
                    if (star) {
                        kwonly.add(a)
                        kwDefaults.add(d)
                    } else {
                        args.add(a)
                        if (d != null) defaults.add(d)
                        else if (defaults.isNotEmpty()) error("parameter without a default follows parameter with a default", t = toks[p - 1])
                    }
                }
            }
            if (!atOp(close)) expectOp(",")
        }
        if (bareStar != null && kwonly.isEmpty()) error("named arguments must follow bare *", t = bareStar)
        return Arguments(posonly, args, vararg, kwonly, kwDefaults, kwarg, defaults)
    }

    private fun ifStmt(): Stmt {
        val t = next() // if / elif
        val test = namedExpression()
        val body = block("'${t.text}' statement", t.line)
        val orelse = when {
            atName("elif") -> listOf(ifStmt())
            atName("else") -> block("'else' statement", next().line)
            else -> emptyList()
        }
        return If(test, body, orelse).at(t)
    }

    private fun forStmt(async: Boolean, start: PyToken): Stmt {
        next() // for
        val target = targetList("in")
        if (!atName("in")) error("expected 'in'")
        next()
        val iter = starExpressions()
        val body = block("'for' statement", start.line)
        return For(target, iter, body, elseBlock(), async).at(start)
    }

    private fun withStmt(async: Boolean, start: PyToken): Stmt {
        next() // with
        // with (a as b, c): items in parentheses, where they are followed by ':' (else an expression in parentheses)
        if (atOp("(")) {
            val save = p
            try {
                next()
                val items = ArrayList<WithItem>()
                while (!atOp(")")) {
                    items.add(withItem())
                    if (!atOp(")")) expectOp(",")
                }
                next()
                if (atOp(":") && items.isNotEmpty()) return With(items, block("'with' statement", start.line), async).at(start)
            } catch (e: PySyntaxError) {
                if (e.fatal) throw e
            }
            p = save
        }
        val items = arrayListOf(withItem())
        while (atOp(",")) { next(); items.add(withItem()) }
        return With(items, block("'with' statement", start.line), async).at(start)
    }

    private fun withItem(): WithItem {
        val t = tok
        val e = expression()
        if (!atName("as")) return WithItem(e, null).at(t)
        next()
        val target = starTarget()
        toTarget(target, Ctx.Store)
        if (!atOp(",") && !atOp(")") && !atOp(":")) error()
        return WithItem(e, target).at(t)
    }

    private fun tryStmt(): Stmt {
        val t = next() // try
        val body = block("'try' statement", t.line)
        val handlers = ArrayList<ExceptHandler>()
        var star: Boolean? = null
        while (atName("except")) {
            val et = next()
            val isStar = atOp("*").also { if (it) next() }
            if (star != null && star != isStar) error("cannot have both 'except' and 'except*' on the same 'try'", t = et)
            star = isStar
            if (atOp(":")) {
                if (isStar) error("expected one or more exception types")
                handlers.add(ExceptHandler(null, null, block("'except' statement", et.line)).at(et))
                continue
            }
            var type = expression()
            // PEP 758: except A, B: (without parentheses, and without as)
            if (atOp(",")) {
                val elts = arrayListOf(type)
                while (atOp(",")) {
                    next()
                    elts.add(expression())
                }
                if (atName("as")) error("multiple exception types must be parenthesized when using 'as'")
                type = TupleExpr(elts).at(et)
            }
            val name = if (atName("as")) { next(); expectName().text } else null
            handlers.add(ExceptHandler(type, name, block(if (isStar) "'except*' statement" else "'except' statement", et.line)).at(et))
        }
        val orelse = if (handlers.isNotEmpty()) elseBlock() else emptyList()
        val finalbody = if (atName("finally")) block("'finally' statement", next().line) else emptyList()
        if (handlers.isEmpty() && finalbody.isEmpty()) error("expected 'except' or 'finally' block")
        return Try(body, handlers, orelse, finalbody, star == true).at(t)
    }

    private fun simpleStatement(): Stmt {
        val t = tok
        if (t.kind == Kind.NAME) when (t.text) {
            "pass" -> { next(); return Pass().at(t) }
            "break" -> { next(); return Break().at(t) }
            "continue" -> { next(); return Continue().at(t) }
            "return" -> {
                next()
                return Return(if (endOfSimple()) null else starExpressions()).at(t)
            }
            "raise" -> {
                next()
                if (endOfSimple()) return Raise(null, null).at(t)
                val exc = expression()
                return Raise(exc, if (atName("from")) { next(); expression() } else null).at(t)
            }
            "global", "nonlocal" -> {
                next()
                val names = arrayListOf(expectName().text)
                while (atOp(",")) { next(); names.add(expectName().text) }
                return if (t.text == "global") Global(names).at(t) else Nonlocal(names).at(t)
            }
            "del" -> {
                next()
                val targets = ArrayList<Expr>()
                while (true) {
                    val e = delTarget()
                    toTarget(e, Ctx.Del)
                    targets.add(e)
                    if (!atOp(",")) break
                    next()
                    if (endOfSimple()) break
                }
                return Delete(targets).at(t)
            }
            "assert" -> {
                next()
                val test = expression()
                return Assert(test, if (atOp(",")) { next(); expression() } else null).at(t)
            }
            "import" -> {
                next()
                val names = arrayListOf(dottedAlias())
                while (atOp(",")) { next(); names.add(dottedAlias()) }
                return Import(names).at(t)
            }
            "from" -> return importFrom()
            "type" -> {
                val n = peek()
                if (n.kind == Kind.NAME && n.text !in KEYWORDS && (peek(2).kind == Kind.OP && (peek(2).text == "=" || peek(2).text == "["))) {
                    next()
                    val nt = next()
                    val name = Name(nt.text, Ctx.Store).at(nt)
                    val tps = typeParams()
                    expectOp("=")
                    return TypeAlias(name, tps, expression()).at(t)
                }
            }
        }
        return expressionStatement()
    }

    private fun endOfSimple() = at(Kind.NEWLINE) || atOp(";") || at(Kind.END)

    private fun dottedName(): String {
        val sb = StringBuilder(expectName().text)
        while (atOp(".")) { next(); sb.append('.').append(expectName().text) }
        return sb.toString()
    }

    private fun dottedAlias(): Alias {
        val t = tok
        val name = dottedName()
        return Alias(name, if (atName("as")) { next(); expectName().text } else null).at(t)
    }

    private fun importFrom(): Stmt {
        val t = next() // from
        var level = 0
        while (atOp(".") || atOp("...")) level += if (next().text == ".") 1 else 3
        val module = if (atName("import")) {
            if (level == 0) error()
            null
        } else dottedName()
        if (!atName("import")) error("expected 'import'")
        next()
        val names = ArrayList<Alias>()
        if (atOp("*")) {
            val st = next()
            names.add(Alias("*", null).at(st))
            return ImportFrom(module, names, level).at(t)
        }
        val paren = atOp("(")
        if (paren) next()
        while (true) {
            val at = tok
            val n = expectName().text
            names.add(Alias(n, if (atName("as")) { next(); expectName().text } else null).at(at))
            if (!atOp(",")) break
            next()
            if (paren && atOp(")")) break
            if (!paren && endOfSimple()) error("trailing comma not allowed without surrounding parentheses")
        }
        if (paren) expectOp(")")
        return ImportFrom(module, names, level).at(t)
    }

    /** An expression statement, an assignment, an augmented or an annotated one. */
    private fun expressionStatement(): Stmt {
        val t = tok
        val first = if (atName("yield")) yieldExpr() else starExpressions()
        when {
            atOp(":") -> {
                next()
                if (first is TupleExpr && !first.parenthesized) error("only single target (not tuple) can be annotated", t = t)
                if (first is ListExpr) error("only single target (not list) can be annotated", t = t)
                if (first is TupleExpr) error("only single target (not tuple) can be annotated", t = t)
                if (first !is Name && first !is Attribute && first !is Subscript) error("illegal target for annotation", t = t)
                toTarget(first, Ctx.Store)
                val ann = expression()
                val value = if (atOp("=")) { next(); if (atName("yield")) yieldExpr() else starExpressions() } else null
                return AnnAssign(first, ann, value, if (first is Name && !first.parenthesized) 1 else 0).at(t)
            }
            atOp("=") -> {
                val targets = arrayListOf(first)
                var value: Expr
                while (true) {
                    next()
                    value = if (atName("yield")) yieldExpr() else starExpressions()
                    if (!atOp("=")) break
                    targets.add(value)
                }
                for (x in targets) toTarget(x, Ctx.Store, assign = true)
                return Assign(targets, value).at(t)
            }
            tok.kind == Kind.OP && tok.text in AUG -> {
                val op = OpKind.of(next().text.dropLast(1))!!
                if (first !is Name && first !is Attribute && first !is Subscript) {
                    error("'${targetWhat(first)}' is an illegal expression for augmented assignment", t = t)
                }
                toTarget(first, Ctx.Store)
                return AugAssign(first, op, if (atName("yield")) yieldExpr() else starExpressions()).at(t)
            }
        }
        if (first is Starred) error("can't use starred expression here", t = t)
        return ExprStmt(first).at(t)
    }

    // ------------------------------------------------------------------ targets

    /** A for loop's or a comprehension's targets, up to [stop] (`in`): a target, or targets separated by commas. */
    private fun targetList(stop: String): Expr {
        val t = tok
        val first = starTarget()
        if (!atOp(",")) return first.also { toTarget(it, Ctx.Store) }
        val elts = arrayListOf(first)
        while (atOp(",")) {
            next()
            if (atName(stop)) break
            elts.add(starTarget())
        }
        return TupleExpr(elts).at(t).also { toTarget(it, Ctx.Store) }
    }

    private fun starTarget(): Expr {
        if (atOp("*")) {
            val t = next()
            return Starred(starTarget()).at(t)
        }
        return bitwiseOr()
    }

    private fun delTarget(): Expr = bitwiseOr()

    /**
     * Marks [e] a target ([ctx] Store or Del), or the SyntaxError CPython gives for what it is ([assign]: in an
     * assignment's `=`, where CPython suggests `==`).
     */
    private fun toTarget(e: Expr, ctx: Ctx, assign: Boolean = false) {
        when (e) {
            is Name -> {
                if (ctx == Ctx.Store && e.id == "__debug__") error("cannot assign to __debug__", t = tokAt(e))
                e.ctx = ctx
            }
            is Attribute -> e.ctx = ctx
            is Subscript -> e.ctx = ctx
            is Starred -> {
                if (ctx == Ctx.Del) error("cannot delete starred", t = tokAt(e))
                e.ctx = ctx
                toTarget(e.value, ctx)
            }
            is TupleExpr, is ListExpr -> {
                val elts = if (e is TupleExpr) e.elts else (e as ListExpr).elts
                if (e is TupleExpr) e.ctx = ctx else (e as ListExpr).ctx = ctx
                if (ctx == Ctx.Store && elts.count { it is Starred } > 1) error("multiple starred expressions in assignment", t = tokAt(e))
                for (x in elts) toTarget(x, ctx, assign)
            }
            else -> {
                val what = targetWhat(e)
                val verb = if (ctx == Ctx.Del) "delete" else "assign to"
                val named = e is Constant && (e.value is PyBool || e.value === PyNone)
                error(if (assign && !named) "cannot assign to $what here. Maybe you meant '==' instead of '='?" else "cannot $verb $what", t = tokAt(e))
            }
        }
    }

    private fun tokAt(e: Node): PyToken = PyToken(Kind.OP, "", null, e.line, e.col)

    private fun targetWhat(e: Expr): String = when (e) {
        is Call -> "function call"
        is Constant -> when (e.value) {
            PyNone -> "None"
            PyBool.TRUE -> "True"
            PyBool.FALSE -> "False"
            PyEllipsis -> "ellipsis"
            else -> "literal"
        }
        is Compare -> "comparison"
        is Lambda -> "lambda"
        is IfExp -> "conditional expression"
        is NamedExpr -> "named expression"
        is Await -> "await expression"
        is Yield, is YieldFrom -> "yield expression"
        is JoinedStr -> "f-string expression"
        is TemplateStr -> "t-string expression"
        is DictExpr -> "dict literal"
        is SetExpr -> "set display"
        is ListComp -> "list comprehension"
        is SetComp -> "set comprehension"
        is DictComp -> "dict comprehension"
        is GeneratorExp -> "generator expression"
        is TupleExpr -> "tuple"
        is ListExpr -> "list"
        else -> "expression"
    }

    // ------------------------------------------------------------------ expressions

    /** Expressions separated by commas, `*x` among them: a tuple where there is a comma. */
    private fun starExpressions(): Expr {
        val t = tok
        val first = starExpression()
        if (!atOp(",")) return first
        val elts = arrayListOf(first)
        while (atOp(",")) {
            next()
            if (!startsExpression()) break
            elts.add(starExpression())
        }
        return TupleExpr(elts).at(t)
    }

    private fun starExpression(): Expr {
        if (atOp("*")) {
            val t = next()
            return Starred(bitwiseOr()).at(t)
        }
        return expression()
    }

    private fun starNamedExpression(): Expr {
        if (atOp("*")) {
            val t = next()
            return Starred(bitwiseOr()).at(t)
        }
        return namedExpression()
    }

    /** Whether the token can start an expression (after a trailing comma it cannot). */
    private fun startsExpression(): Boolean = when (tok.kind) {
        Kind.NAME -> tok.text !in KEYWORDS || tok.text in EXPR_KEYWORDS
        Kind.NUMBER, Kind.STRING, Kind.FSTRING_START -> true
        Kind.OP -> tok.text in setOf("(", "[", "{", "-", "+", "~", "*", "...")
        else -> false
    }

    private fun namedExpression(): Expr {
        if (atIdentifier() && peek().kind == Kind.OP && peek().text == ":=") {
            val t = next()
            next()
            return NamedExpr(Name(t.text, Ctx.Store).at(t), expression()).at(t)
        }
        val e = expression()
        if (atOp(":=")) error("cannot use assignment expressions with ${targetWhat(e)}")
        return e
    }

    private fun expression(): Expr {
        if (atName("lambda")) return lambda()
        val t = tok
        val body = disjunction()
        if (!atName("if")) return body
        next()
        val test = disjunction()
        if (!atName("else")) error("expected 'else' after 'if' expression")
        next()
        return IfExp(test, body, expression()).at(t)
    }

    private fun lambda(): Expr {
        val t = next() // lambda
        val args = parameters(":", annotations = false)
        expectOp(":")
        return Lambda(args, expression()).at(t)
    }

    private fun yieldExpr(): Expr {
        val t = next() // yield
        if (atName("from")) {
            next()
            return YieldFrom(expression()).at(t)
        }
        return Yield(if (startsExpression()) starExpressions() else null).at(t)
    }

    private fun disjunction(): Expr {
        val t = tok
        val first = conjunction()
        if (!atName("or")) return first
        val values = arrayListOf(first)
        while (atName("or")) { next(); values.add(conjunction()) }
        return BoolOp(BoolOpKind.Or, values).at(t)
    }

    private fun conjunction(): Expr {
        val t = tok
        val first = inversion()
        if (!atName("and")) return first
        val values = arrayListOf(first)
        while (atName("and")) { next(); values.add(inversion()) }
        return BoolOp(BoolOpKind.And, values).at(t)
    }

    private fun inversion(): Expr {
        if (atName("not")) {
            val t = next()
            return UnaryOp(UnaryOpKind.Not, inversion()).at(t)
        }
        return comparison()
    }

    private fun comparison(): Expr {
        val t = tok
        val left = bitwiseOr()
        val ops = ArrayList<CmpOpKind>()
        val rights = ArrayList<Expr>()
        while (true) {
            val op = when {
                tok.kind == Kind.OP && tok.text in CMP -> { val s = next().text; CmpOpKind.entries.first { it.symbol == s } }
                atName("in") -> { next(); CmpOpKind.In }
                atName("not") && peek().kind == Kind.NAME && peek().text == "in" -> { next(); next(); CmpOpKind.NotIn }
                atName("is") -> { next(); if (atName("not")) { next(); CmpOpKind.IsNot } else CmpOpKind.Is }
                else -> break
            }
            ops.add(op)
            rights.add(bitwiseOr())
        }
        return if (ops.isEmpty()) left else Compare(left, ops, rights).at(t)
    }

    private fun binary(operand: () -> Expr, vararg ops: String): Expr {
        val t = tok
        var left = operand()
        while (tok.kind == Kind.OP && tok.text in ops) {
            val op = OpKind.of(next().text)!!
            left = BinOp(left, op, operand()).at(t)
        }
        return left
    }

    private fun bitwiseOr(): Expr = binary(::bitwiseXor, "|")
    private fun bitwiseXor(): Expr = binary(::bitwiseAnd, "^")
    private fun bitwiseAnd(): Expr = binary(::shiftExpr, "&")
    private fun shiftExpr(): Expr = binary(::sum, "<<", ">>")
    private fun sum(): Expr = binary(::term, "+", "-")
    private fun term(): Expr = binary(::factor, "*", "/", "//", "%", "@")

    private fun factor(): Expr {
        if (tok.kind == Kind.OP && tok.text in setOf("-", "+", "~")) {
            val t = next()
            val k = when (t.text) { "-" -> UnaryOpKind.USub; "+" -> UnaryOpKind.UAdd; else -> UnaryOpKind.Invert }
            return UnaryOp(k, factor()).at(t)
        }
        return power()
    }

    private fun power(): Expr {
        val t = tok
        val base = awaitPrimary()
        if (!atOp("**")) return base
        next()
        // right-associative, and binds tighter than a unary minus on its left but not on its right: -2 ** 2 is -4
        return BinOp(base, OpKind.Pow, factor()).at(t)
    }

    private fun awaitPrimary(): Expr {
        if (atName("await")) {
            val t = next()
            return Await(primary()).at(t)
        }
        return primary()
    }

    private fun primary(): Expr {
        var e = atom()
        while (true) {
            val t = tok
            e = when {
                atOp(".") -> {
                    next()
                    Attribute(e, expectName().text).at(e.line, e.col)
                }
                atOp("(") -> {
                    next()
                    val (args, keywords) = arguments(allowGenexp = true)
                    Call(e, args, keywords).at(e.line, e.col)
                }
                atOp("[") -> {
                    next()
                    val s = slices()
                    expectOp("]")
                    Subscript(e, s).at(e.line, e.col)
                }
                else -> return e
            }
            if (t.kind == Kind.END) return e
        }
    }

    private fun <T : Node> T.at(line: Int, col: Int): T = also { it.line = line; it.col = col }

    /** A call's arguments, up to and with its `)`: positional ones (`*x` among them), then keyword ones (`**m`). */
    private fun arguments(allowGenexp: Boolean): Pair<List<Expr>, List<Keyword>> {
        val args = ArrayList<Expr>()
        val keywords = ArrayList<Keyword>()
        var sawKeyword = false
        var sawDoubleStar = false
        var genexp = false
        while (!atOp(")")) {
            val t = tok
            when {
                atOp("*") -> {
                    next()
                    if (sawDoubleStar) error("iterable argument unpacking follows keyword argument unpacking", t = t)
                    args.add(Starred(expression()).at(t))
                }
                atOp("**") -> {
                    next()
                    keywords.add(Keyword(null, expression()).at(t))
                    sawDoubleStar = true
                }
                atIdentifier() && peek().kind == Kind.OP && peek().text == "=" -> {
                    val n = next().text
                    next()
                    keywords.add(Keyword(n, expression()).at(t))
                    sawKeyword = true
                }
                else -> {
                    var e = namedExpression()
                    if (atName("for") || atName("async") && peek().text == "for") {
                        e = GeneratorExp(e, comprehensions()).at(t)
                        genexp = true
                    } else if (atOp("=")) {
                        error("expression cannot contain assignment, perhaps you meant \"==\"?", t = t)
                    }
                    if (sawDoubleStar) error("positional argument follows keyword argument unpacking", t = t)
                    if (sawKeyword) error("positional argument follows keyword argument", t = t)
                    args.add(e)
                }
            }
            if (!atOp(")")) expectOp(",")
        }
        next()
        if (genexp && (!allowGenexp || args.size + keywords.size > 1)) error("Generator expression must be parenthesized")
        return args to keywords
    }

    /** A subscript's slices: a slice, an expression, or several of them (a tuple, `*x` among them). */
    private fun slices(): Expr {
        val t = tok
        val first = sliceItem()
        // (a[*b] is a[(*b,)])
        if (!atOp(",")) return if (first is Starred) TupleExpr(listOf(first)).at(t) else first
        val elts = arrayListOf(first)
        while (atOp(",")) {
            next()
            if (atOp("]")) break
            elts.add(sliceItem())
        }
        return TupleExpr(elts).at(t)
    }

    private fun sliceItem(): Expr {
        val t = tok
        if (atOp("*")) {
            next()
            return Starred(bitwiseOr()).at(t)
        }
        val lower = if (atOp(":")) null else namedExpression()
        if (!atOp(":")) return lower!!
        next()
        val upper = if (atOp(":") || atOp("]") || atOp(",")) null else expression()
        var step: Expr? = null
        if (atOp(":")) {
            next()
            if (!atOp("]") && !atOp(",")) step = expression()
        }
        return Slice(lower, upper, step).at(t)
    }

    private fun comprehensions(): List<Comprehension> {
        val out = ArrayList<Comprehension>()
        while (atName("for") || atName("async") && peek().kind == Kind.NAME && peek().text == "for") {
            val t = tok
            val async = atName("async")
            if (async) next()
            next() // for
            val target = targetList("in")
            if (!atName("in")) error("expected 'in'")
            next()
            val iter = disjunction()
            val ifs = ArrayList<Expr>()
            while (atName("if")) { next(); ifs.add(disjunction()) }
            out.add(Comprehension(target, iter, ifs, if (async) 1 else 0).at(t))
        }
        return out
    }

    private fun atComprehension() = atName("for") || atName("async") && peek().kind == Kind.NAME && peek().text == "for"

    private fun atom(): Expr {
        val t = tok
        when (t.kind) {
            Kind.NUMBER -> { next(); return Constant(t.value).at(t) }
            Kind.STRING, Kind.FSTRING_START -> return strings()
            Kind.NAME -> {
                return when (t.text) {
                    "None" -> { next(); Constant(PyNone).at(t) }
                    "True" -> { next(); Constant(PyBool.TRUE).at(t) }
                    "False" -> { next(); Constant(PyBool.FALSE).at(t) }
                    in KEYWORDS -> error()
                    else -> { next(); Name(t.text).at(t) }
                }
            }
            Kind.OP -> when (t.text) {
                "..." -> { next(); return Constant(PyEllipsis).at(t) }
                "(" -> return parenthesized()
                "[" -> {
                    next()
                    if (atOp("]")) { next(); return ListExpr(emptyList()).at(t) }
                    val first = starNamedExpression()
                    if (atComprehension()) {
                        if (first is Starred) error("iterable unpacking cannot be used in comprehension", t = t)
                        val g = comprehensions()
                        expectOp("]")
                        return ListComp(first, g).at(t)
                    }
                    val elts = arrayListOf(first)
                    while (atOp(",")) {
                        next()
                        if (atOp("]")) break
                        elts.add(starNamedExpression())
                    }
                    expectOp("]")
                    return ListExpr(elts).at(t)
                }
                "{" -> return braces()
                else -> error()
            }
            Kind.NEWLINE, Kind.END -> error(if (t.kind == Kind.END) "unexpected EOF while parsing" else "invalid syntax")
            Kind.INDENT -> error("unexpected indent", "IndentationError")
            else -> error()
        }
    }

    private fun parenthesized(): Expr {
        val t = next() // (
        if (atOp(")")) { next(); return TupleExpr(emptyList()).at(t).also { it.parenthesized = true } }
        if (atName("yield")) {
            val y = yieldExpr()
            expectOp(")")
            return y.also { it.parenthesized = true }
        }
        val first = starNamedExpression()
        if (atComprehension()) {
            if (first is Starred) error("iterable unpacking cannot be used in comprehension", t = t)
            val g = comprehensions()
            expectOp(")")
            return GeneratorExp(first, g).at(t).also { it.parenthesized = true }
        }
        if (atOp(")")) {
            next()
            if (first is Starred) error("cannot use starred expression here", t = t)
            first.parenthesized = true
            return first
        }
        val elts = arrayListOf(first)
        while (atOp(",")) {
            next()
            if (atOp(")")) break
            elts.add(starNamedExpression())
        }
        expectOp(")")
        return TupleExpr(elts).at(t).also { it.parenthesized = true }
    }

    private fun braces(): Expr {
        val t = next() // {
        if (atOp("}")) { next(); return DictExpr(emptyList(), emptyList()).at(t) }
        if (atOp("**")) return dictRest(t, ArrayList(), ArrayList())
        val first = starNamedExpression()
        if (atOp(":") && first !is Starred) {
            next()
            val value = expression()
            if (atComprehension()) {
                val g = comprehensions()
                expectOp("}")
                return DictComp(first, value, g).at(t)
            }
            return dictRest(t, arrayListOf(first), arrayListOf(value))
        }
        if (atComprehension()) {
            if (first is Starred) error("iterable unpacking cannot be used in comprehension", t = t)
            val g = comprehensions()
            expectOp("}")
            return SetComp(first, g).at(t)
        }
        val elts = arrayListOf(first)
        while (atOp(",")) {
            next()
            if (atOp("}")) break
            elts.add(starNamedExpression())
        }
        expectOp("}")
        return SetExpr(elts).at(t)
    }

    /** A dict display's entries after the first (or from the first `**`), and its `}`. */
    private fun dictRest(t: PyToken, keys: ArrayList<Expr?>, values: ArrayList<Expr>): Expr {
        var first = keys.isEmpty()
        while (true) {
            if (!first) {
                if (!atOp(",")) break
                next()
                if (atOp("}")) break
            }
            first = false
            if (atOp("**")) {
                next()
                keys.add(null)
                values.add(bitwiseOr())
            } else {
                keys.add(expression())
                expectOp(":")
                values.add(expression())
            }
        }
        expectOp("}")
        return DictExpr(keys, values).at(t)
    }

    // ------------------------------------------------------------------ strings

    /**
     * Adjacent string literals as one: a str or bytes constant, an f-string (JoinedStr, its literal parts joined),
     * or a t-string (TemplateStr), which joins t-strings only.
     */
    private fun strings(): Expr {
        val t = tok
        val pieces = ArrayList<Expr>()
        var bytes: Boolean? = null
        var fstring = false
        var template: Boolean? = null
        var u = false
        var first = true
        while (at(Kind.STRING) || at(Kind.FSTRING_START)) {
            val isT: Boolean
            if (at(Kind.STRING)) {
                val s = next().value as StrLit
                if (bytes != null && bytes != s.bytes) error("cannot mix bytes and nonbytes literals", t = t)
                bytes = s.bytes
                if (first) u = s.u
                isT = false
                pieces.add(Constant(if (s.bytes) PyBytes(s.value as ByteArray) else PyStr(s.value as String)).at(t))
            } else {
                val info = tok.value as FStringInfo
                if (bytes == true) error("cannot mix bytes and nonbytes literals", t = t)
                bytes = false
                isT = info.template
                fstring = true
                pieces.addAll(fstringParts(info))
            }
            if (template != null && template != isT) error("cannot mix t-string literals with string or bytes literals", t = t)
            template = isT
            first = false
        }
        if (!fstring) {
            if (bytes == true) {
                val o = java.io.ByteArrayOutputStream()
                for (x in pieces) o.write(((x as Constant).value as PyBytes).b)
                return Constant(PyBytes(o.toByteArray())).at(t)
            }
            return Constant(PyStr(pieces.joinToString("") { ((it as Constant).value as PyStr).s }), if (u) "u" else null).at(t)
        }
        val values = merge(pieces)
        return if (template == true) TemplateStr(values).at(t) else JoinedStr(values).at(t)
    }

    /** Adjacent str constants as one; empty ones left out. */
    private fun merge(pieces: List<Expr>): List<Expr> {
        val out = ArrayList<Expr>()
        for (x in pieces) {
            if (x is Constant && x.value is PyStr) {
                if ((x.value as PyStr).s.isEmpty()) continue
                val last = out.lastOrNull()
                if (last is Constant && last.value is PyStr) {
                    out[out.size - 1] = Constant(PyStr((last.value as PyStr).s + (x.value as PyStr).s)).at(last.line, last.col)
                    continue
                }
            }
            out.add(x)
        }
        return out
    }

    /** One f-string's or t-string's parts, FSTRING_START to FSTRING_END. */
    private fun fstringParts(info: FStringInfo): List<Expr> {
        next() // FSTRING_START
        val out = ArrayList<Expr>()
        while (!at(Kind.FSTRING_END)) {
            val t = tok
            when {
                at(Kind.FSTRING_MIDDLE) -> { next(); out.add(Constant(PyStr(t.value as String)).at(t)) }
                atOp("{") -> out.addAll(field(info, false))
                else -> error()
            }
        }
        next()
        return out
    }

    /**
     * A replacement field, `{` to `}`: the debug text before it where it ends with `=`, then its value (an
     * Interpolation in a t-string, but for a field in a format spec, [inSpec]).
     */
    private fun field(info: FStringInfo, inSpec: Boolean): List<Expr> {
        val open = next() // {
        val what = if (info.template) "t-string" else "f-string"
        if (atOp("}")) error("$what: valid expression required before '}'")
        if (atName("lambda")) error("$what: lambda expressions are not allowed without parentheses")
        val value = if (atName("yield")) yieldExpr() else starExpressions()
        // (a t-string's expression as written: from after { to what ends it, but trailing spaces)
        val exprText = src.substring(open.end, tok.start).trimEnd()
        val out = ArrayList<Expr>()
        var debug = false
        if (atOp("=")) {
            next()
            debug = true
            // the text from after { to the conversion, format spec or }, without its comments
            out.add(Constant(PyStr(withoutComments(src.substring(open.end, tok.start)))).at(open))
        }
        var conversion = -1
        if (atOp("!")) {
            next()
            val c = tok
            if (c.kind != Kind.NAME || c.text !in setOf("s", "r", "a")) {
                error("$what: invalid conversion character ${if (c.kind == Kind.NAME) "'${c.text}'" else "'${c.text}'"}: expected 's', 'r', or 'a'")
            }
            next()
            conversion = c.text[0].code
        }
        var spec: Expr? = null
        if (atOp(":")) {
            val st = next()
            val parts = ArrayList<Expr>()
            while (!atOp("}")) {
                val t = tok
                when {
                    at(Kind.FSTRING_MIDDLE) -> { next(); parts.add(Constant(PyStr(t.value as String)).at(t)) }
                    atOp("{") -> parts.addAll(field(info, true))
                    else -> error("$what: expecting '}'")
                }
            }
            spec = JoinedStr(merge(parts)).at(st)
        }
        if (!atOp("}")) error("$what: expecting '}'")
        next()
        if (debug && conversion == -1 && spec == null) conversion = 'r'.code
        out.add(if (info.template && !inSpec) Interpolation(value, withoutComments(exprText), conversion, spec).at(open)
            else FormattedValue(value, conversion, spec).at(open))
        return out
    }

    /** [s] (a replacement field's source) without its comments: `#` to the end of a line, outside strings. */
    private fun withoutComments(s: String): String {
        if ('#' !in s) return s
        val sb = StringBuilder()
        var i = 0
        var quote: Char? = null
        while (i < s.length) {
            val c = s[i]
            when {
                quote != null -> {
                    sb.append(c)
                    if (c == '\\' && i + 1 < s.length) { sb.append(s[i + 1]); i++ }
                    else if (c == quote) quote = null
                }
                c == '\'' || c == '"' -> { quote = c; sb.append(c) }
                c == '#' -> { while (i < s.length && s[i] != '\n') i++; continue }
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ match

    /** `match subject:` with its cases, where the tokens say a match statement; null for an expression's `match`. */
    private fun matchStmt(): Stmt? {
        val t = tok
        val save = p
        next()
        val subject = try {
            if (atOp(":") || at(Kind.NEWLINE)) null else matchSubject()
        } catch (e: PySyntaxError) {
            if (e.fatal) throw e
            null
        }
        if (subject == null || !atOp(":") || peek().kind != Kind.NEWLINE) {
            p = save
            return null
        }
        next()
        next() // NEWLINE
        if (!at(Kind.INDENT)) error("expected an indented block after 'match' statement on line ${t.line}", "IndentationError")
        next()
        val cases = ArrayList<MatchCase>()
        while (!at(Kind.DEDENT) && !at(Kind.END)) {
            if (at(Kind.NEWLINE)) { p++; continue }
            if (!atName("case")) error("expected 'case'")
            val ct = next()
            val pattern = patterns()
            val guard = if (atName("if")) { next(); namedExpression() } else null
            cases.add(MatchCase(pattern, guard, block("'case' statement", ct.line)).at(ct))
        }
        if (at(Kind.DEDENT)) next()
        return Match(subject, cases).at(t)
    }

    private fun matchSubject(): Expr {
        val t = tok
        val first = starNamedExpression()
        if (!atOp(",")) {
            if (first is Starred) error()
            return first
        }
        val elts = arrayListOf(first)
        while (atOp(",")) {
            next()
            if (atOp(":")) break
            elts.add(starNamedExpression())
        }
        return TupleExpr(elts).at(t)
    }

    /** A case's patterns: a pattern, or patterns separated by commas (a sequence). */
    private fun patterns(): Pattern {
        val t = tok
        val first = maybeStarPattern()
        if (!atOp(",")) {
            if (first is MatchStar) error("can't use starred expression here", t = t)
            return first
        }
        val ps = arrayListOf(first)
        while (atOp(",")) {
            next()
            if (atOp(":") || atName("if")) break
            ps.add(maybeStarPattern())
        }
        return MatchSequence(ps).at(t)
    }

    private fun maybeStarPattern(): Pattern {
        if (atOp("*")) {
            val t = next()
            val n = expectName().text
            return MatchStar(if (n == "_") null else n).at(t)
        }
        return pattern()
    }

    private fun pattern(): Pattern {
        val t = tok
        val p = orPattern()
        if (!atName("as")) return p
        next()
        val nt = tok
        val n = expectName().text
        if (n == "_") error("cannot use '_' as a target", t = nt)
        return MatchAs(p, n).at(t)
    }

    private fun orPattern(): Pattern {
        val t = tok
        val first = closedPattern()
        if (!atOp("|")) return first
        val ps = arrayListOf(first)
        while (atOp("|")) { next(); ps.add(closedPattern()) }
        return MatchOr(ps).at(t)
    }

    private fun closedPattern(): Pattern {
        val t = tok
        when {
            at(Kind.NUMBER) || atOp("-") -> return MatchValue(signedNumber()).at(t)
            at(Kind.STRING) -> return MatchValue(strings()).at(t)
            at(Kind.FSTRING_START) -> error("patterns may only match literals and attribute lookups")
            atName("None") -> { next(); return MatchSingleton(PyNone).at(t) }
            atName("True") -> { next(); return MatchSingleton(PyBool.TRUE).at(t) }
            atName("False") -> { next(); return MatchSingleton(PyBool.FALSE).at(t) }
            atOp("(") -> {
                next()
                if (atOp(")")) { next(); return MatchSequence(emptyList()).at(t) }
                val first = maybeStarPattern()
                if (atOp(")")) {
                    next()
                    if (first is MatchStar) return MatchSequence(listOf(first)).at(t)
                    return first
                }
                val ps = arrayListOf(first)
                while (atOp(",")) {
                    next()
                    if (atOp(")")) break
                    ps.add(maybeStarPattern())
                }
                expectOp(")")
                return MatchSequence(ps).at(t)
            }
            atOp("[") -> {
                next()
                val ps = ArrayList<Pattern>()
                while (!atOp("]")) {
                    ps.add(maybeStarPattern())
                    if (!atOp("]")) expectOp(",")
                }
                next()
                return MatchSequence(ps).at(t)
            }
            atOp("{") -> return mappingPattern()
            atIdentifier() -> {
                var e: Expr = Name(next().text).at(t)
                var dotted = false
                while (atOp(".")) {
                    next()
                    e = Attribute(e, expectName().text).at(t)
                    dotted = true
                }
                if (atOp("(")) return classPattern(e, t)
                if (dotted) return MatchValue(e).at(t)
                val n = (e as Name).id
                return MatchAs(null, if (n == "_") null else n).at(t)
            }
            else -> error()
        }
    }

    /** A literal number pattern: `-1`, `1.5`, `1 + 2j`, `-1 - 2j`. */
    private fun signedNumber(): Expr {
        val t = tok
        var e: Expr = if (atOp("-")) {
            next()
            if (!at(Kind.NUMBER)) error()
            UnaryOp(UnaryOpKind.USub, Constant(next().value).at(toks[p - 1])).at(t)
        } else Constant(next().value).at(t)
        if ((atOp("+") || atOp("-")) && peek().kind == Kind.NUMBER) {
            val op = if (next().text == "+") OpKind.Add else OpKind.Sub
            val im = next()
            if (im.value !is PyComplex) error("imaginary number required in complex literal", t = im)
            e = BinOp(e, op, Constant(im.value).at(im)).at(t)
        }
        return e
    }

    private fun mappingPattern(): Pattern {
        val t = next() // {
        val keys = ArrayList<Expr>()
        val ps = ArrayList<Pattern>()
        var rest: String? = null
        while (!atOp("}")) {
            if (rest != null) error()
            if (atOp("**")) {
                next()
                rest = expectName().text
            } else {
                val kt = tok
                val key: Expr = when {
                    at(Kind.NUMBER) || atOp("-") -> signedNumber()
                    at(Kind.STRING) -> strings()
                    atName("None") -> { next(); Constant(PyNone).at(kt) }
                    atName("True") -> { next(); Constant(PyBool.TRUE).at(kt) }
                    atName("False") -> { next(); Constant(PyBool.FALSE).at(kt) }
                    atIdentifier() -> {
                        var e: Expr = Name(next().text).at(kt)
                        if (!atOp(".")) error("mapping pattern keys may only match literals and attribute lookups")
                        while (atOp(".")) { next(); e = Attribute(e, expectName().text).at(kt) }
                        e
                    }
                    else -> error()
                }
                expectOp(":")
                keys.add(key)
                ps.add(pattern())
            }
            if (!atOp("}")) expectOp(",")
        }
        next()
        return MatchMapping(keys, ps, rest).at(t)
    }

    private fun classPattern(cls: Expr, t: PyToken): Pattern {
        next() // (
        val ps = ArrayList<Pattern>()
        val kwds = ArrayList<String>()
        val kps = ArrayList<Pattern>()
        while (!atOp(")")) {
            if (atIdentifier() && peek().kind == Kind.OP && peek().text == "=") {
                kwds.add(next().text)
                next()
                kps.add(pattern())
            } else {
                if (kwds.isNotEmpty()) error("positional patterns follow keyword patterns")
                ps.add(pattern())
            }
            if (!atOp(")")) expectOp(",")
        }
        next()
        return MatchClass(cls, ps, kwds, kps).at(t)
    }

    private companion object {
        val KEYWORDS = setOf("False", "None", "True", "and", "as", "assert", "async", "await", "break", "class", "continue",
            "def", "del", "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is",
            "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try", "while", "with", "yield")
        /** Keywords that start an expression. */
        val EXPR_KEYWORDS = setOf("None", "True", "False", "not", "lambda", "await")
        val CMP = setOf("<", ">", "==", ">=", "<=", "!=")
        val AUG = setOf("+=", "-=", "*=", "@=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=", "**=", "//=")
    }
}
