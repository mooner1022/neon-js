package io.neonjs.parser

internal class SP(@JvmField val start: Int, @JvmField val line: Int, @JvmField val col: Int)

internal abstract class ExpressionParser(src: String, options: ParseOptions) : ParserBase(src, options) {

    fun sp() = SP(lex.start, lex.tokLine, lex.tokCol)
    fun spOf(n: Node) = SP(n.start, n.line, n.col)
    fun <N : Node> fin(n: N, p: SP): N {
        n.start = p.start; n.line = p.line; n.col = p.col; n.end = lex.prevEnd
        return n
    }

    abstract fun parseBlockBody(endsWithBrace: Boolean, directives: Boolean, onDirective: ((StringLiteral) -> Unit)?): List<Node>
    abstract fun parseBlock(newScope: Boolean = true): BlockStatement

    // ============================================================ expressions

    fun parseExpression(noIn: Boolean = false, refErrors: DestructuringErrors? = null): Node {
        val p = sp()
        val expr = parseMaybeAssign(noIn, refErrors)
        if (type == T.COMMA) {
            val list = arrayListOf(expr)
            while (eat(T.COMMA)) list.add(parseMaybeAssign(noIn, refErrors))
            return fin(SequenceExpression(list), p)
        }
        return expr
    }

    fun checkStrictOctal() {
        if (strict && lex.legacyOctalPos >= 0) {
            raise(lex.legacyOctalPos, if (type == T.STRING) "Octal escape sequences are not allowed in strict mode" else "Octal literals are not allowed in strict mode")
        }
    }

    fun parseMaybeAssign(noIn: Boolean = false, refErrors0: DestructuringErrors? = null): Node {
        if (isContextual("yield")) {
            if (inGenerator) return parseYield(noIn)
        }
        var ownErrors = false
        var refErrors = refErrors0
        var oldParenAssign = -1
        var oldTrailingComma = -1
        var oldDoubleProto = -1
        if (refErrors != null) {
            oldTrailingComma = refErrors.trailingComma
            oldDoubleProto = refErrors.doubleProto
            refErrors.trailingComma = -1
            refErrors.doubleProto = -1
        } else {
            refErrors = DestructuringErrors()
            ownErrors = true
        }
        val p = sp()
        val startType = type
        var left = parseMaybeConditional(noIn, refErrors)
        if (type.isAssign) {
            val op = type.text
            val opPos = lex.start
            val annexBCall = !strict && left is CallExpression && !left.parenthesized && type != T.AND_ASSIGN && type != T.OR_ASSIGN && type != T.NULLISH_ASSIGN
            if (type == T.ASSIGN && !annexBCall) {
                left = toAssignable(left, false, refErrors)
            }
            if (!ownErrors) {
                refErrors.trailingComma = -1
                refErrors.doubleProto = -1
            }
            if (refErrors.shorthandAssign >= left.start) refErrors.shorthandAssign = -1
            if (annexBCall) {
                // Annex B: f() = x throws ReferenceError at runtime in sloppy mode
            } else if (type == T.ASSIGN) checkLValPattern(left)
            else {
                checkLValSimple(left)
                if (left !is Identifier && left !is MemberExpression) raise(left.start, "Invalid left-hand side in assignment")
            }
            next()
            val right = parseMaybeAssign(noIn)
            if (oldDoubleProto > -1) refErrors.doubleProto = oldDoubleProto
            return fin(AssignmentExpression(op, left, right), p)
        } else {
            if (ownErrors) checkExpressionErrors(refErrors, true)
        }
        if (oldTrailingComma > -1) refErrors.trailingComma = oldTrailingComma
        if (oldDoubleProto > -1 && refErrors.doubleProto < 0) refErrors.doubleProto = oldDoubleProto
        @Suppress("UNUSED_VARIABLE") val unused = startType
        return left
    }

    fun checkExpressionErrors(refErrors: DestructuringErrors?, andThrow: Boolean): Boolean {
        if (refErrors == null) return false
        val sa = refErrors.shorthandAssign
        val dp = refErrors.doubleProto
        if (!andThrow) return sa >= 0 || dp >= 0
        if (sa >= 0) raise(sa, "Shorthand property assignments are valid only in destructuring patterns")
        if (dp >= 0) raise(dp, "Redefinition of __proto__ property")
        return false
    }

    fun checkPatternErrors(refErrors: DestructuringErrors?, isAssign: Boolean) {
        if (refErrors == null) return
        if (refErrors.trailingComma > -1) raise(refErrors.trailingComma, "Comma is not permitted after the rest element")
    }

    fun parseMaybeConditional(noIn: Boolean, refErrors: DestructuringErrors?): Node {
        val p = sp()
        val expr = parseExprOps(noIn, refErrors)
        if (checkExpressionErrors(refErrors, false)) return expr
        if (isBareArrow(expr)) return expr
        if (eat(T.QUESTION)) {
            val cons = parseMaybeAssign(false)
            expect(T.COLON)
            val alt = parseMaybeAssign(noIn)
            return fin(ConditionalExpression(expr, cons, alt), p)
        }
        return expr
    }

    fun parseExprOps(noIn: Boolean, refErrors: DestructuringErrors?): Node {
        val p = sp()
        val expr = parseMaybeUnary(refErrors, false, noIn)
        if (checkExpressionErrors(refErrors, false)) return expr
        if (isBareArrow(expr)) return expr
        return parseExprOp(expr, p, -1, noIn)
    }

    private fun binaryPrec(t: T, noIn: Boolean): Int = when (t) {
        T.NULLISH -> 1
        T.OR -> 1
        T.AND -> 2
        T.BAR -> 3
        T.CARET -> 4
        T.AMP -> 5
        T.EQ, T.NE, T.SEQ, T.SNE -> 6
        T.LT, T.GT, T.LE, T.GE -> 7
        T.SHL, T.SAR, T.SHR -> 8
        T.PLUS, T.MINUS -> 9
        T.STAR, T.SLASH, T.PERCENT -> 10
        T.NAME -> if (lex.escaped) -1 else when (lex.value) {
            "instanceof" -> 7
            "in" -> if (noIn) -1 else 7
            else -> -1
        }
        else -> -1
    }

    fun parseExprOp(left: Node, leftP: SP, minPrec: Int, noIn: Boolean): Node {
        if (left is PrivateIdentifier && !(isKw("in") && !noIn)) unexpected()
        val prec = binaryPrec(type, noIn)
        if (prec >= 0 && prec > minPrec) {
            val logical = type == T.OR || type == T.AND
            val coalesce = type == T.NULLISH
            val op = if (type == T.NAME) lex.value as String else type.text
            if (left is PrivateIdentifier && op != "in") unexpected()
            next()
            val rp = sp()
            val rightStart = parseMaybeUnary(null, false, noIn)
            if (isBareArrow(rightStart)) raise(rightStart.start, "Malformed arrow function parameter list")
            val right = parseExprOp(rightStart, rp, if (coalesce) 2 else prec, noIn)
            if (right is PrivateIdentifier) raise(right.start, "Private identifier can only be left side of binary expression")
            val node: Node = if (left is PrivateIdentifier) fin(PrivateInExpression(left, right), leftP)
            else if (logical || coalesce) fin(LogicalExpression(op, left, right), leftP)
            else fin(BinaryExpression(op, left, right), leftP)
            if ((logical && type == T.NULLISH) || (coalesce && (type == T.OR || type == T.AND))) {
                raise(lex.start, "Logical expressions and coalesce expressions cannot be mixed. Wrap either by parentheses")
            }
            return parseExprOp(node, leftP, minPrec, noIn)
        }
        if (left is PrivateIdentifier) unexpected(left.start)
        return left
    }

    fun parseMaybeUnary(refErrors: DestructuringErrors?, sawUnary0: Boolean, noIn: Boolean, updateOperand: Boolean = false): Node {
        val p = sp()
        var sawUnary = sawUnary0
        val expr: Node
        if (isContextual("await") && inAsync) {
            expr = parseAwait(noIn)
            sawUnary = true
        } else if (type == T.INC || type == T.DEC) {
            val op = type.text
            next()
            val arg = parseMaybeUnary(null, true, noIn, updateOperand = true)
            checkLValSimple(arg)
            if (arg !is Identifier && arg !is MemberExpression && !(arg is CallExpression && !strict && !arg.parenthesized)) raise(arg.start, "Invalid left-hand side expression in prefix operation")
            expr = fin(UpdateExpression(op, true, arg), p)
        } else if (type == T.BANG || type == T.TILDE || type == T.PLUS || type == T.MINUS ||
            (type == T.NAME && !lex.escaped && (lex.value == "typeof" || lex.value == "void" || lex.value == "delete"))
        ) {
            val op = if (type == T.NAME) lex.value as String else type.text
            next()
            val arg = parseMaybeUnary(null, true, noIn)
            checkExpressionErrors(refErrors, true)
            if (op == "delete") {
                if (strict && unparen(arg) is Identifier) raise(p.start, "Deleting local variable in strict mode")
                if (isPrivateFieldAccess(arg)) raise(p.start, "Private fields can not be deleted")
            }
            expr = fin(UnaryExpression(op, arg), p)
            sawUnary = true
        } else if (!sawUnary && type == T.PRIVATE_NAME) {
            if (privateNames == null && options.privateNames.isEmpty()) unexpected()
            val pid = parsePrivateIdent()
            if (!isKw("in") || noIn) unexpected()
            expr = pid
        } else {
            var e = parseExprSubscripts(refErrors, noIn)
            if (checkExpressionErrors(refErrors, false)) return e
            while ((type == T.INC || type == T.DEC) && !lex.nlBefore) {
                checkLValSimple(e)
                if (e !is Identifier && e !is MemberExpression && !(e is CallExpression && !strict && !e.parenthesized)) raise(e.start, "Invalid left-hand side expression in postfix operation")
                val op = type.text
                next()
                e = fin(UpdateExpression(op, false, e), p)
            }
            expr = e
        }
        if (isBareArrow(expr) || updateOperand) return expr
        if (type == T.STARSTAR) {
            if (sawUnary) unexpected()
            next()
            val rp = sp()
            val right = parseMaybeUnary(null, false, noIn)
            if (right is PrivateIdentifier) unexpected(right.start)
            return fin(BinaryExpression("**", expr, right), p)
        }
        return expr
    }

    private fun unparen(n: Node): Node = n

    fun isBareArrow(n: Node): Boolean = n is FunctionNode && n.kind == FunctionKind.ARROW && !n.parenthesized

    private fun isPrivateFieldAccess(n: Node): Boolean {
        if (n is MemberExpression && n.property is PrivateIdentifier) return true
        if (n is ChainExpression) return isPrivateFieldAccess(n.expression)
        return false
    }

    fun parseExprSubscripts(refErrors: DestructuringErrors?, noIn: Boolean): Node {
        val p = sp()
        val expr = parseExprAtom(refErrors, noIn)
        if (isBareArrow(expr)) return expr
        val result = parseSubscripts(expr, p, false, noIn)
        if (refErrors != null && result !== expr) {
            // with subscripts the atom is an ordinary expression (never a pattern): its literal errors apply now,
            // e.g. `[{a = 0}.x] = []`
            if (refErrors.shorthandAssign >= expr.start) raise(refErrors.shorthandAssign, "Invalid shorthand property initializer")
            if (refErrors.doubleProto >= expr.start) raise(refErrors.doubleProto, "Redefinition of __proto__ property")
        }
        if (refErrors != null && result is MemberExpression) {
            if (refErrors.trailingComma >= result.start) refErrors.trailingComma = -1
        }
        return result
    }

    fun parseSubscripts(base0: Node, p: SP, noCalls: Boolean, noIn: Boolean): Node {
        var base = base0
        val maybeAsyncArrow = base is Identifier && base.name == "async" && lex.prevEnd == base.end &&
                !canInsertSemicolon() && base.end - base.start == 5 && !base.parenthesized
        var optionalChained = false
        while (true) {
            val optional = type == T.QDOT
            if (optional) {
                if (noCalls) raise(lex.start, "Optional chaining cannot appear in the callee of new expressions")
                next()
                optionalChained = true
            }
            if (type == T.LBRACKET) {
                next()
                val prop = parseExpression()
                expect(T.RBRACKET)
                base = fin(MemberExpression(base, prop, true, optional), p)
            } else if (optional && type != T.LPAREN && type != T.TEMPLATE) {
                base = fin(MemberExpression(base, parsePropertyAccessName(), false, true), p)
            } else if (!optional && eat(T.DOT)) {
                if (base is Super && type == T.PRIVATE_NAME) raise(lex.start, "Unexpected private field access on super")
                base = fin(MemberExpression(base, parsePropertyAccessName(), false, false), p)
            } else if (!noCalls && type == T.LPAREN) {
                val refErrors = DestructuringErrors()
                val oldYield = yieldPos
                val oldAwait = awaitPos
                val oldAwaitIdent = awaitIdentPos
                yieldPos = -1; awaitPos = -1; awaitIdentPos = -1
                next()
                val args = parseExprList(T.RPAREN, true, false, refErrors, maybeAsyncArrow && !optionalChained)
                if (maybeAsyncArrow && !optional && !optionalChained && type == T.ARROW && !lex.nlBefore) {
                    checkPatternErrors(refErrors, false)
                    checkYieldAwaitInDefaultParams()
                    if (awaitIdentPos > -1) raise(awaitIdentPos, "Cannot use 'await' as identifier inside an async function")
                    yieldPos = oldYield; awaitPos = oldAwait; awaitIdentPos = oldAwaitIdent
                    next()
                    return parseArrowExpression(p, args.toMutableList(), true, noIn)
                }
                checkExpressionErrors(refErrors, true)
                if (oldYield > -1) yieldPos = oldYield
                if (oldAwait > -1) awaitPos = oldAwait
                if (oldAwaitIdent > -1) awaitIdentPos = oldAwaitIdent
                base = fin(CallExpression(base, args.map { it!! }, optional), p)
            } else if (type == T.TEMPLATE) {
                if (optional || optionalChained) raise(lex.start, "Optional chaining cannot appear in the tag of tagged template expressions")
                val quasi = parseTemplate(true)
                base = fin(TaggedTemplate(base, quasi), p)
            } else {
                if (optional) unexpected()
                break
            }
        }
        if (optionalChained) base = fin(ChainExpression(base), p)
        return base
    }

    fun parsePropertyAccessName(): Node {
        if (type == T.PRIVATE_NAME) return parsePrivateIdent()
        if (type != T.NAME) unexpected()
        val p = sp()
        val name = lex.value as String
        next()
        return fin(Identifier(name), p)
    }

    fun parsePrivateIdent(): PrivateIdentifier {
        val p = sp()
        val name = lex.value as String
        next()
        val node = fin(PrivateIdentifier(name), p)
        val pn = privateNames
        if (pn != null) pn.used.add(node)
        else if (!options.privateNames.contains(name)) raise(node.start, "Private field '#$name' must be declared in an enclosing class")
        return node
    }

    fun parseExprAtom(refErrors: DestructuringErrors?, noIn: Boolean): Node {
        val p = sp()
        if (type == T.AT) {
            pendingDecorators = parseDecorators()
            if (!isKw("class")) unexpected()
            return parseClass(false, false)
        }
        when (type) {
            T.NAME -> {
                val name = lex.value as String
                if (!lex.escaped) {
                    when (name) {
                        "this" -> { next(); return fin(ThisExpression(), p) }
                        "null" -> { next(); return fin(NullLiteral(), p) }
                        "true" -> { next(); return fin(BooleanLiteral(true), p) }
                        "false" -> { next(); return fin(BooleanLiteral(false), p) }
                        "function" -> { next(); return parseFunctionExpression(p, false) }
                        "class" -> return parseClass(false, false)
                        "new" -> return parseNew()
                        "super" -> return parseSuper()
                        "import" -> return parseImportExpr()
                        "async" -> {
                            val la = lex.save()
                            next()
                            if (isKw("function") && !lex.nlBefore) {
                                next()
                                return parseFunctionExpression(p, true)
                            }
                            if (type == T.NAME && !lex.nlBefore) {
                                // async x => ...
                                val idP = sp()
                                val idName = lex.value as String
                                val esc = lex.escaped
                                val la2 = lex.save()
                                next()
                                if (type == T.ARROW && !lex.nlBefore) {
                                    if (esc && isReservedWord(idName)) raise(idP.start, "Keyword must not contain escaped characters")
                                    if (idName == "await") raise(idP.start, "Cannot use 'await' as identifier inside an async function")
                                    val id = fin(Identifier(idName), idP)
                                    id.end = la2.end
                                    next()
                                    return parseArrowExpression(p, mutableListOf(id), true, noIn)
                                }
                                lex.restore(la)
                            } else lex.restore(la)
                        }
                    }
                }
                if (lex.escaped && keywords.contains(name) && name != "await" && name != "yield") {
                    raise(lex.start, "Keyword must not contain escaped characters")
                }
                val id = parseIdentReference()
                if (type == T.ARROW && !canInsertSemicolon()) {
                    next()
                    return parseArrowExpression(p, mutableListOf(id), false, noIn)
                }
                return id
            }
            T.NUM -> {
                checkStrictOctal()
                val v = lex.value as Double
                next()
                return fin(NumberLiteral(v), p)
            }
            T.BIGINT -> {
                val v = lex.value as java.math.BigInteger
                next()
                return fin(BigIntLiteral(v), p)
            }
            T.STRING -> {
                checkStrictOctal()
                val node = StringLiteral(lex.value as String)
                node.raw = src.substring(lex.start, lex.end)
                node.hasLegacyOctal = lex.legacyOctalPos >= 0
                next()
                return fin(node, p)
            }
            T.SLASH, T.SLASH_ASSIGN -> {
                lex.rescanRegExp()
                val pattern = lex.value as String
                val flags = lex.regexFlags!!
                next()
                val node = fin(RegExpLiteral(pattern, flags), p)
                validateRegExp(node)
                return node
            }
            T.LPAREN -> return parseParenAndDistinguishExpression(noIn, refErrors)
            T.LBRACKET -> {
                next()
                val elements = parseExprList(T.RBRACKET, true, true, refErrors, false)
                return fin(ArrayLiteral(elements.toMutableList()), p)
            }
            T.LBRACE -> return parseObj(false, refErrors)
            T.TEMPLATE -> return parseTemplate(false)
            T.PRIVATE_NAME -> unexpected()
            else -> unexpected()
        }
    }

    open fun validateRegExp(node: RegExpLiteral) {
        val err = RegExpSyntaxValidator.validate(node.pattern, node.flags)
        if (err != null) raise(node.start, "Invalid regular expression: /${node.pattern}/${node.flags}: $err")
    }

    fun parseIdentReference(): Identifier {
        val p = sp()
        val name = lex.value as? String ?: unexpected()
        val escaped = lex.escaped
        checkUnreserved(name, p.start)
        if (name == "await" && awaitIdentPos < 0) awaitIdentPos = p.start
        next()
        return fin(Identifier(name), p)
    }

    /** Parses a binding identifier (declarations, params). Validation happens in checkLVal. */
    fun parseBindingIdent(): Identifier {
        if (type != T.NAME) unexpected()
        val p = sp()
        val name = lex.value as String
        if (lex.escaped && keywords.contains(name)) raise(p.start, "Keyword must not contain escaped characters")
        checkUnreserved(name, p.start, true)
        if (name == "await" && awaitIdentPos < 0) awaitIdentPos = p.start
        next()
        return fin(Identifier(name), p)
    }

    fun parseSuper(): Node {
        val p = sp()
        next()
        when (type) {
            T.LPAREN -> if (!allowSuperCall) raise(p.start, "'super' keyword unexpected here")
            T.DOT, T.LBRACKET -> if (!allowSuperProperty) raise(p.start, "'super' keyword unexpected here")
            else -> raise(p.start, "'super' keyword unexpected here")
        }
        return fin(Super(), p)
    }

    fun parseNew(): Node {
        val p = sp()
        next()
        if (type == T.DOT) {
            next()
            if (!isContextual("target")) {
                if (type == T.NAME && lex.value == "target") raise(lex.start, "'new.target' must not contain escaped characters")
                raise(lex.start, "The only valid meta property for new is 'new.target'")
            }
            next()
            if (!allowNewTarget) raise(p.start, "'new.target' can only be used in functions and class static block")
            return fin(MetaProperty("new", "target"), p)
        }
        if (isKw("import") && lookahead { type == T.LPAREN || (type == T.DOT && lookahead { isContextual("source") || isContextual("defer") }) }) {
            raise(lex.start, "Cannot use new with import()")
        }
        val cp = sp()
        val callee = parseSubscripts(parseExprAtom(null, false), cp, true, false)
        val args = if (eat(T.LPAREN)) parseExprList(T.RPAREN, true, false, null, false).map { it!! } else emptyList()
        return fin(NewExpression(callee, args), p)
    }

    fun parseImportExpr(): Node {
        val p = sp()
        next()
        if (type == T.DOT) {
            next()
            if (isContextual("meta")) {
                if (!inModule) raise(p.start, "Cannot use 'import.meta' outside a module")
                next()
                return fin(MetaProperty("import", "meta"), p)
            }
            if (isContextual("source") || isContextual("defer")) {
                val phase = lex.value as String
                next()
                if (type != T.LPAREN) unexpected()
                return parseImportCall(p, phase)
            }
            if (type == T.NAME && (lex.value == "meta")) raise(lex.start, "'import.meta' must not contain escaped characters")
            unexpected()
        }
        if (type == T.LPAREN) return parseImportCall(p, null)
        unexpected(p.start)
    }

    private fun parseImportCall(p: SP, phase: String?): Node {
        expect(T.LPAREN)
        if (type == T.ELLIPSIS) unexpected()
        val source = parseMaybeAssign(false)
        var opts: Node? = null
        if (eat(T.COMMA)) {
            if (type != T.RPAREN) {
                if (type == T.ELLIPSIS) unexpected()
                opts = parseMaybeAssign(false)
                eat(T.COMMA)
            }
        }
        expect(T.RPAREN)
        return fin(ImportCall(source, opts, phase), p)
    }

    /** Parses comma separated expressions until [close]. Allows holes for arrays. */
    fun parseExprList(close: T, allowTrailingComma: Boolean, allowEmpty: Boolean, refErrors: DestructuringErrors?, trackAwaitIdent: Boolean): List<Node?> {
        val elts = ArrayList<Node?>()
        var first = true
        while (!eat(close)) {
            if (!first) {
                expect(T.COMMA)
                if (allowTrailingComma && type == close) {
                    next()
                    break
                }
            } else first = false
            if (allowEmpty && type == T.COMMA) {
                elts.add(null)
            } else if (type == T.ELLIPSIS) {
                val sp = sp()
                next()
                val arg = parseMaybeAssign(false, refErrors)
                elts.add(fin(SpreadElement(arg), sp))
                if (refErrors != null && type == T.COMMA && refErrors.trailingComma < 0) refErrors.trailingComma = lex.start
            } else {
                elts.add(parseMaybeAssign(false, refErrors))
            }
        }
        return elts
    }

    fun checkYieldAwaitInDefaultParams() {
        if (yieldPos > -1 && (awaitPos < 0 || yieldPos < awaitPos)) raise(yieldPos, "Yield expression cannot be a default value")
        if (awaitPos > -1) raise(awaitPos, "Await expression cannot be a default value")
    }

    fun parseParenAndDistinguishExpression(noIn: Boolean, refErrors0: DestructuringErrors?): Node {
        val p = sp()
        next()
        val innerP = sp()
        val exprList = ArrayList<Node>()
        var first = true
        var lastIsComma = false
        val refErrors = DestructuringErrors()
        val oldYield = yieldPos
        val oldAwait = awaitPos
        yieldPos = -1; awaitPos = -1
        var spreadStart = -1
        while (type != T.RPAREN) {
            if (first) first = false else expect(T.COMMA)
            if (type == T.RPAREN) {
                lastIsComma = true
                break
            } else if (type == T.ELLIPSIS) {
                spreadStart = lex.start
                val rp = sp()
                next()
                val arg = parseBindingAtom()
                if (type == T.ASSIGN) raise(lex.start, "Rest parameter may not have a default initializer")
                exprList.add(fin(RestElement(arg), rp))
                if (type == T.COMMA) raise(lex.start, "Comma is not permitted after the rest element")
                break
            } else {
                exprList.add(parseMaybeAssign(false, refErrors))
            }
        }
        val innerEnd = lex.prevEnd
        expect(T.RPAREN)
        if (type == T.ARROW && !canInsertSemicolon()) {
            checkPatternErrors(refErrors, false)
            checkYieldAwaitInDefaultParams()
            yieldPos = oldYield
            awaitPos = oldAwait
            next()
            return parseArrowExpression(p, exprList.toMutableList<Node?>(), false, noIn)
        }
        if (exprList.isEmpty() || lastIsComma) unexpected(lex.prevEnd - 1)
        if (spreadStart > -1) unexpected(spreadStart)
        checkExpressionErrors(refErrors, true)
        if (oldYield > -1) yieldPos = oldYield
        if (oldAwait > -1) awaitPos = oldAwait
        val v: Node = if (exprList.size > 1) {
            val seq = SequenceExpression(exprList)
            seq.start = innerP.start; seq.line = innerP.line; seq.col = innerP.col; seq.end = innerEnd
            seq
        } else exprList[0]
        v.parenthesized = true
        return v
    }

    // ============================================================ templates

    fun parseTemplate(isTagged: Boolean): TemplateLiteral {
        val p = sp()
        val quasis = ArrayList<TemplateElement>()
        val exprs = ArrayList<Node>()
        while (true) {
            if (type != T.TEMPLATE) unexpected()
            if (!isTagged && lex.cooked == null) raise(lex.invalidEscapePos, "Invalid escape sequence in template")
            val el = TemplateElement(lex.cooked, lex.raw!!)
            el.start = lex.start; el.end = lex.end; el.line = lex.tokLine; el.col = lex.tokCol
            quasis.add(el)
            val tail = lex.templateTail
            next()
            if (tail) break
            exprs.add(parseExpression())
            if (type != T.RBRACE) unexpected()
            lex.rescanTemplateContinuation()
        }
        return fin(TemplateLiteral(quasis, exprs), p)
    }

    // ============================================================ yield / await

    fun parseYield(noIn: Boolean): Node {
        val p = sp()
        if (yieldPos < 0) yieldPos = lex.start
        next()
        if (type == T.SEMI || canInsertSemicolon() || (type != T.STAR && !startsExpr())) {
            return fin(YieldExpression(null, false), p)
        }
        val delegate = eat(T.STAR)
        val arg = parseMaybeAssign(noIn)
        return fin(YieldExpression(arg, delegate), p)
    }

    private fun startsExpr(): Boolean = when (type) {
        T.NAME -> !(lex.value == "in" || lex.value == "instanceof" || lex.value == "of") || lex.escaped
        T.NUM, T.BIGINT, T.STRING, T.TEMPLATE, T.REGEXP, T.LPAREN, T.LBRACKET, T.LBRACE, T.PLUS, T.MINUS, T.BANG,
        T.TILDE, T.INC, T.DEC, T.SLASH, T.SLASH_ASSIGN, T.PRIVATE_NAME, T.LT, T.AT -> true
        else -> false
    }

    fun parseAwait(noIn: Boolean): Node {
        val p = sp()
        if (awaitPos < 0) awaitPos = lex.start
        next()
        val arg = parseMaybeUnary(null, true, noIn)
        return fin(AwaitExpression(arg), p)
    }

    // ============================================================ object literals

    fun parseObj(isPattern: Boolean, refErrors: DestructuringErrors?): Node {
        val p = sp()
        next()
        val props = ArrayList<Node>()
        var first = true
        var hasProto = false
        while (!eat(T.RBRACE)) {
            if (!first) {
                expect(T.COMMA)
                if (eat(T.RBRACE)) break
            } else first = false
            val prop = parseObjProperty(isPattern, refErrors)
            if (!isPattern && prop is Property && !prop.computed && !prop.shorthand && !prop.method && prop.kind == PropKind.INIT) {
                val k = prop.key
                val kn = if (k is Identifier) k.name else if (k is StringLiteral) k.value else null
                if (kn == "__proto__") {
                    if (hasProto) {
                        if (refErrors != null) {
                            if (refErrors.doubleProto < 0) refErrors.doubleProto = k.start
                        } else raise(k.start, "Redefinition of __proto__ property")
                    }
                    hasProto = true
                }
            }
            props.add(prop)
        }
        return if (isPattern) fin(ObjectPattern(props), p) else fin(ObjectLiteral(props), p)
    }

    fun parseObjProperty(isPattern: Boolean, refErrors: DestructuringErrors?): Node {
        val p = sp()
        if (type == T.ELLIPSIS) {
            next()
            if (isPattern) {
                val arg = parseBindingIdent()
                if (type == T.COMMA) raise(lex.start, "Comma is not permitted after the rest element")
                return fin(RestElement(arg), p)
            }
            val arg = parseMaybeAssign(false, refErrors)
            if (type == T.COMMA && refErrors != null && refErrors.trailingComma < 0) refErrors.trailingComma = lex.start
            return fin(SpreadElement(arg), p)
        }
        var isAsync = false
        var isGenerator = false
        var kind = PropKind.INIT
        if (!isPattern) {
            isGenerator = eat(T.STAR)
        }
        val containsEsc = lex.escaped
        var keyInfo = parsePropertyName()
        if (!isPattern && !containsEsc && !isGenerator && !keyInfo.computed && keyInfo.key is Identifier && isAsyncProp(keyInfo.key as Identifier)) {
            isAsync = true
            isGenerator = eat(T.STAR)
            keyInfo = parsePropertyName()
        } else if (!isPattern && !containsEsc && !isGenerator && !keyInfo.computed && keyInfo.key is Identifier &&
            ((keyInfo.key as Identifier).name == "get" || (keyInfo.key as Identifier).name == "set") &&
            type != T.COMMA && type != T.RBRACE && type != T.COLON && type != T.LPAREN && type != T.ASSIGN
        ) {
            kind = if ((keyInfo.key as Identifier).name == "get") PropKind.GET else PropKind.SET
            keyInfo = parsePropertyName()
        }
        return parsePropertyValue(p, keyInfo, isPattern, isGenerator, isAsync, kind, refErrors, containsEsc)
    }

    private fun isAsyncProp(key: Identifier): Boolean =
        key.name == "async" && !lex.nlBefore &&
                (type == T.NAME || type == T.NUM || type == T.STRING || type == T.BIGINT || type == T.LBRACKET || type == T.STAR || type == T.PRIVATE_NAME)

    class KeyInfo(val key: Node, val computed: Boolean)

    fun parsePropertyName(allowPrivate: Boolean = false): KeyInfo {
        val p = sp()
        return when (type) {
            T.LBRACKET -> {
                next()
                val k = parseMaybeAssign(false)
                expect(T.RBRACKET)
                KeyInfo(k, true)
            }
            T.NUM -> {
                checkStrictOctal()
                val v = lex.value as Double
                next()
                KeyInfo(fin(NumberLiteral(v), p), false)
            }
            T.BIGINT -> {
                val v = lex.value as java.math.BigInteger
                next()
                KeyInfo(fin(BigIntLiteral(v), p), false)
            }
            T.STRING -> {
                checkStrictOctal()
                val s = StringLiteral(lex.value as String)
                s.raw = src.substring(lex.start, lex.end)
                next()
                KeyInfo(fin(s, p), false)
            }
            T.NAME -> {
                val n = lex.value as String
                next()
                KeyInfo(fin(Identifier(n), p), false)
            }
            T.PRIVATE_NAME -> {
                if (!allowPrivate) unexpected()
                val n = lex.value as String
                next()
                KeyInfo(fin(PrivateIdentifier(n), p), false)
            }
            else -> unexpected()
        }
    }

    private fun parsePropertyValue(
        p: SP, keyInfo: KeyInfo, isPattern: Boolean, isGenerator: Boolean, isAsync: Boolean, kind: PropKind,
        refErrors: DestructuringErrors?, containsEsc: Boolean,
    ): Node {
        val key = keyInfo.key
        if ((isGenerator || isAsync) && type == T.COLON) unexpected()
        if (eat(T.COLON)) {
            if (kind != PropKind.INIT) unexpected(lex.prevEnd - 1)
            val value = if (isPattern) parseMaybeDefault() else parseMaybeAssign(false, refErrors)
            return fin(Property(key, value, PropKind.INIT, keyInfo.computed, false, false), p)
        }
        if (type == T.LPAREN) {
            if (isPattern) unexpected()
            val fk = when (kind) { PropKind.GET -> FunctionKind.GETTER; PropKind.SET -> FunctionKind.SETTER; else -> FunctionKind.METHOD }
            val fn = parseMethod(isGenerator, isAsync, fk, false, p)
            if (kind == PropKind.GET && fn.params.isNotEmpty()) raise(fn.start, "Getter must not have any formal parameters")
            if (kind == PropKind.SET && (fn.params.size != 1 || fn.params[0] is RestElement)) raise(fn.start, "Setter must have exactly one formal parameter")
            return fin(Property(key, fn, kind, keyInfo.computed, false, kind == PropKind.INIT), p)
        }
        if (kind != PropKind.INIT || isGenerator || isAsync) unexpected()
        if (!keyInfo.computed && key is Identifier) {
            // shorthand
            val name = key.name
            if (containsEsc && keywords.contains(name)) raise(key.start, "Keyword must not contain escaped characters")
            checkUnreserved(name, key.start)
            if (name == "await" && awaitIdentPos < 0) awaitIdentPos = key.start
            val id = Identifier(name)
            id.start = key.start; id.end = key.end; id.line = key.line; id.col = key.col
            if (isPattern) {
                val v = parseMaybeDefaultFrom(id)
                return fin(Property(key, v, PropKind.INIT, false, true, false), p)
            }
            if (type == T.ASSIGN && refErrors != null) {
                if (refErrors.shorthandAssign < 0) refErrors.shorthandAssign = lex.start
                next()
                val right = parseMaybeAssign(false)
                val ap = AssignmentPattern(id, right)
                ap.start = id.start; ap.line = id.line; ap.col = id.col; ap.end = lex.prevEnd
                return fin(Property(key, ap, PropKind.INIT, false, true, false), p)
            }
            return fin(Property(key, id, PropKind.INIT, false, true, false), p)
        }
        unexpected()
    }

    // ============================================================ functions

    fun functionFlags(isAsync: Boolean, isGenerator: Boolean): Int =
        SCOPE_FUNCTION or (if (isAsync) SCOPE_ASYNC else 0) or (if (isGenerator) SCOPE_GENERATOR else 0)

    fun parseFunctionExpression(p: SP, isAsync: Boolean): FunctionNode {
        val isGenerator = eat(T.STAR)
        var id: Identifier? = null
        if (type == T.NAME) {
            // name is checked in the context of the function itself for generators / async
            val ip = sp()
            val name = lex.value as String
            if (lex.escaped && keywords.contains(name)) raise(ip.start, "Keyword must not contain escaped characters")
            if (isGenerator && name == "yield") raise(ip.start, "Cannot use 'yield' as function name here")
            if (isAsync && name == "await") raise(ip.start, "Cannot use 'await' as function name here")
            if (keywords.contains(name)) unexpected()
            if (strict && strictReserved.contains(name)) raise(ip.start, "Unexpected strict mode reserved word '$name'")
            if (strict && (name == "eval" || name == "arguments")) raise(ip.start, "Unexpected eval or arguments in strict mode")
            if (!isAsync && name == "await" && (inModule)) raise(ip.start, "Cannot use 'await' as identifier")

            next()
            id = fin(Identifier(name), ip)
        }
        val fn = parseFunctionRest(p, id, isAsync, isGenerator, FunctionKind.NORMAL, isStatement = false)
        return fn
    }

    /** Parses params and body. Current token must be '('. */
    fun parseFunctionRest(p: SP, id: Identifier?, isAsync: Boolean, isGenerator: Boolean, kind: FunctionKind, isStatement: Boolean): FunctionNode {
        val oldYield = yieldPos
        val oldAwait = awaitPos
        val oldAwaitIdent = awaitIdentPos
        yieldPos = -1; awaitPos = -1; awaitIdentPos = -1
        enterScope(functionFlags(isAsync, isGenerator) or (if (kind == FunctionKind.METHOD || kind == FunctionKind.GETTER || kind == FunctionKind.SETTER || kind == FunctionKind.CLASS_CONSTRUCTOR) SCOPE_SUPER else 0)
                or (if (kind == FunctionKind.DERIVED_CONSTRUCTOR) SCOPE_SUPER or SCOPE_DIRECT_SUPER else 0))
        val srcStart = p.start
        expect(T.LPAREN)
        val params = parseBindingList(T.RPAREN, false, true)
        checkYieldAwaitInDefaultParams()
        val fn = FunctionNode(id, params, EmptyStatement(), kind, isAsync, isGenerator)
        fn.isDeclaration = isStatement
        parseFunctionBody(fn, false, kind != FunctionKind.NORMAL, false)
        yieldPos = oldYield; awaitPos = oldAwait; awaitIdentPos = oldAwaitIdent
        fin(fn, p)
        fn.srcStart = srcStart
        fn.srcEnd = lex.prevEnd
        if (id != null) fn.name = id.name
        return fn
    }

    fun parseMethod(isGenerator: Boolean, isAsync: Boolean, kind: FunctionKind, isStatic: Boolean, nameStart: SP?): FunctionNode {
        val p = sp()
        val oldYield = yieldPos
        val oldAwait = awaitPos
        val oldAwaitIdent = awaitIdentPos
        yieldPos = -1; awaitPos = -1; awaitIdentPos = -1
        var flags = functionFlags(isAsync, isGenerator) or SCOPE_SUPER
        if (kind == FunctionKind.DERIVED_CONSTRUCTOR) flags = flags or SCOPE_DIRECT_SUPER
        enterScope(flags)
        expect(T.LPAREN)
        val params = parseBindingList(T.RPAREN, false, true)
        checkYieldAwaitInDefaultParams()
        val fn = FunctionNode(null, params, EmptyStatement(), kind, isAsync, isGenerator)
        fn.isStatic = isStatic
        parseFunctionBody(fn, false, true, false)
        yieldPos = oldYield; awaitPos = oldAwait; awaitIdentPos = oldAwaitIdent
        fin(fn, p)
        fn.srcStart = nameStart?.start ?: p.start
        fn.srcEnd = lex.prevEnd
        return fn
    }

    fun parseArrowExpression(p: SP, params0: MutableList<Node?>, isAsync: Boolean, noIn: Boolean): FunctionNode {
        val oldYield = yieldPos
        val oldAwait = awaitPos
        val oldAwaitIdent = awaitIdentPos
        enterScope(functionFlags(isAsync, false) or SCOPE_ARROW)
        val params = ArrayList<Node>()
        for (e in params0) {
            if (e == null) unexpected()
            params.add(toAssignable(e, true, null))
        }
        yieldPos = -1; awaitPos = -1; awaitIdentPos = -1
        val fn = FunctionNode(null, params, EmptyStatement(), FunctionKind.ARROW, isAsync, false)
        parseFunctionBody(fn, true, false, noIn)
        yieldPos = oldYield; awaitPos = oldAwait; awaitIdentPos = oldAwaitIdent
        fin(fn, p)
        fn.srcStart = p.start
        fn.srcEnd = lex.prevEnd
        return fn
    }

    fun isSimpleParamList(params: List<Node>): Boolean = params.all { it is Identifier }

    /** Parses the function body; enterScope must have been called (this exits it). */
    fun parseFunctionBody(fn: FunctionNode, isArrow: Boolean, isMethod: Boolean, noIn: Boolean) {
        val isExpression = isArrow && type != T.LBRACE
        val oldStrict = strict
        fn.hasSimpleParams = isSimpleParamList(fn.params)
        // declare params now so that body lexical declarations conflict with them
        val paramNames = ArrayList<Identifier>()
        for (prm in fn.params) collectBoundIdentifiers(prm, paramNames)
        for (id in paramNames) declareName(id.name, Bind.VAR, id.start)
        if (isExpression) {
            fn.body = parseMaybeAssign(noIn)
            fn.isExpressionBody = true
            checkParams(fn, false)
        } else {
            val oldLabels = withFreshLabels()
            val bp = sp()
            expect(T.LBRACE)
            var sawUseStrict = false
            val body = parseBlockBody(true, true) { directive ->
                if (directive.raw.length == 12 && directive.raw.substring(1, 11) == "use strict") {
                    if (!fn.hasSimpleParams) raise(directive.start, "Illegal 'use strict' directive in function with non-simple parameter list")
                    sawUseStrict = true
                    strict = true
                }
            }
            fn.body = fin(BlockStatement(body), bp)
            labels = oldLabels
            val allowDup = !strict && !isArrow && !isMethod && fn.hasSimpleParams && fn.kind == FunctionKind.NORMAL
            checkParams(fn, allowDup)
            if (strict && fn.id != null && fn.kind == FunctionKind.NORMAL) {
                val n = fn.id!!.name
                if (n == "eval" || n == "arguments") raise(fn.id!!.start, "Unexpected eval or arguments in strict mode")
                if (strictReserved.contains(n) && !oldStrict) raise(fn.id!!.start, "Unexpected strict mode reserved word '$n'")
            }
            @Suppress("UNUSED_VARIABLE") val u = sawUseStrict
        }
        fn.strict = strict
        strict = oldStrict
        exitScope()
    }

    fun checkParams(fn: FunctionNode, allowDuplicates: Boolean) {
        val seen = HashSet<String>()
        val ids = ArrayList<Identifier>()
        for (prm in fn.params) collectBoundIdentifiers(prm, ids)
        for (id in ids) {
            val n = id.name
            if (strict) {
                if (n == "eval" || n == "arguments") raise(id.start, "Unexpected eval or arguments in strict mode")
                if (strictReserved.contains(n)) raise(id.start, "Unexpected strict mode reserved word '$n'")
            }
            if (!seen.add(n) && !allowDuplicates) raise(id.start, "Duplicate parameter name not allowed in this context")
        }
    }

    fun collectBoundIdentifiers(n: Node?, out: MutableList<Identifier>) {
        when (n) {
            null -> {}
            is Identifier -> out.add(n)
            is AssignmentPattern -> collectBoundIdentifiers(n.left, out)
            is RestElement -> collectBoundIdentifiers(n.argument, out)
            is ArrayPattern -> n.elements.forEach { collectBoundIdentifiers(it, out) }
            is ObjectPattern -> n.properties.forEach {
                if (it is Property) collectBoundIdentifiers(it.value, out) else collectBoundIdentifiers(it, out)
            }
            else -> {}
        }
    }

    // ============================================================ binding patterns

    fun parseBindingAtom(): Node {
        val p = sp()
        if (type == T.LBRACKET) {
            next()
            val elts = parseBindingList(T.RBRACKET, true, true)
            return fin(ArrayPattern(elts.toMutableList<Node?>().also { l -> for (i in l.indices) if (l[i] is EmptyHole) l[i] = null }), p)
        }
        if (type == T.LBRACE) return parseObj(true, null)
        return parseBindingIdent()
    }

    object EmptyHole : Node()

    fun parseBindingList(close: T, allowEmpty: Boolean, allowTrailingComma: Boolean): MutableList<Node> {
        val elts = ArrayList<Node>()
        var first = true
        while (!eat(close)) {
            if (first) first = false else expect(T.COMMA)
            if (allowEmpty && type == T.COMMA) {
                elts.add(EmptyHole)
            } else if (allowTrailingComma && type == close) {
                next()
                break
            } else if (type == T.ELLIPSIS) {
                val p = sp()
                next()
                val arg = parseBindingAtom()
                if (type == T.ASSIGN) raise(lex.start, "Rest element may not have a default initializer")
                elts.add(fin(RestElement(arg), p))
                if (type == T.COMMA) raise(lex.start, "Comma is not permitted after the rest element")
                expect(close)
                break
            } else {
                elts.add(parseMaybeDefault())
            }
        }
        return elts
    }

    fun parseMaybeDefault(): Node {
        val left = parseBindingAtom()
        return parseMaybeDefaultFrom(left)
    }

    fun parseMaybeDefaultFrom(left: Node): Node {
        if (!eat(T.ASSIGN)) return left
        val right = parseMaybeAssign(false)
        val ap = AssignmentPattern(left, right)
        ap.start = left.start; ap.line = left.line; ap.col = left.col; ap.end = lex.prevEnd
        return ap
    }

    // ============================================================ assignable conversion

    /** Converts an expression into an assignment/binding pattern. */
    fun toAssignable(node: Node, isBinding: Boolean, refErrors: DestructuringErrors?): Node {
        when (node) {
            is Identifier -> {
                if (node.parenthesized && isBinding) raise(node.start, "Invalid destructuring assignment target")
                if (inAsync && node.name == "await") raise(node.start, "Cannot use 'await' as identifier inside an async function")
                return node
            }
            is ObjectPattern, is ArrayPattern, is AssignmentPattern, is RestElement -> return node
            is ObjectLiteral -> {
                if (node.parenthesized) raise(node.start, "Invalid destructuring assignment target")
                if (refErrors != null) checkPatternErrors(refErrors, true)
                val props = ArrayList<Node>()
                for ((i, prop) in node.properties.withIndex()) {
                    if (prop is SpreadElement) {
                        val arg = prop.argument
                        if (i != node.properties.size - 1) raise(prop.start, "Rest element must be last element")
                        if (arg !is Identifier && (isBinding || arg !is MemberExpression)) raise(arg.start, "Invalid rest element")
                        if (arg.parenthesized && isBinding) raise(arg.start, "Invalid rest element")
                        val r = RestElement(toAssignable(arg, isBinding, null))
                        r.start = prop.start; r.end = prop.end; r.line = prop.line; r.col = prop.col
                        props.add(r)
                    } else if (prop is Property) {
                        if (prop.kind != PropKind.INIT || prop.method) raise(prop.key.start, "Object pattern can't contain getter or setter")
                        prop.value = toAssignable(prop.value, isBinding, null)
                        props.add(prop)
                    } else props.add(prop)
                }
                val pat = ObjectPattern(props)
                pat.start = node.start; pat.end = node.end; pat.line = node.line; pat.col = node.col
                return pat
            }
            is ArrayLiteral -> {
                if (node.parenthesized) raise(node.start, "Invalid destructuring assignment target")
                if (refErrors != null) checkPatternErrors(refErrors, true)
                val elts = ArrayList<Node?>()
                for ((i, e) in node.elements.withIndex()) {
                    if (e is SpreadElement) {
                        if (i != node.elements.size - 1) raise(e.start, "Rest element must be last element")
                        if (isBinding && e.argument is AssignmentExpression) raise(e.argument.start, "Rest elements cannot have a default value")
                        val arg = e.argument
                        if (arg is AssignmentExpression) raise(arg.start, "Rest elements cannot have a default value")
                        val r = RestElement(toAssignable(arg, isBinding, null))
                        r.start = e.start; r.end = e.end; r.line = e.line; r.col = e.col
                        elts.add(r)
                    } else elts.add(if (e == null) null else toAssignable(e, isBinding, null))
                }
                val pat = ArrayPattern(elts)
                pat.start = node.start; pat.end = node.end; pat.line = node.line; pat.col = node.col
                return pat
            }
            is SpreadElement -> {
                if (node.argument is AssignmentExpression) raise(node.argument.start, "Rest elements cannot have a default value")
                val r = RestElement(toAssignable(node.argument, isBinding, null))
                r.start = node.start; r.end = node.end; r.line = node.line; r.col = node.col
                return r
            }
            is AssignmentExpression -> {
                if (node.op != "=") raise(node.target.end, "Only '=' operator can be used for specifying default value.")
                if (node.parenthesized) raise(node.start, "Invalid destructuring assignment target")
                val ap = AssignmentPattern(toAssignable(node.target, isBinding, null), node.value)
                ap.start = node.start; ap.end = node.end; ap.line = node.line; ap.col = node.col
                return ap
            }
            is MemberExpression -> {
                if (!isBinding) {
                    if (node.optional) raise(node.start, "Invalid destructuring assignment target")
                    return node
                }
                raise(node.start, "Invalid destructuring assignment target")
            }
            is ChainExpression -> raise(node.start, "Optional chaining cannot appear in left-hand side")
            else -> raise(node.start, if (isBinding) "Invalid destructuring assignment target" else "Invalid left-hand side in assignment")
        }
    }

    /** Validates simple assignment target / binding identifier. [bind] declares the name when not NONE. */
    fun checkLValSimple(expr: Node, bind: Bind = Bind.NONE) {
        val isBind = bind != Bind.NONE
        when (expr) {
            is Identifier -> {
                if (strict && (expr.name == "eval" || expr.name == "arguments")) {
                    raise(expr.start, (if (isBind) "Binding " else "Assigning to ") + expr.name + " in strict mode")
                }
                if (isBind) {
                    if (bind == Bind.LEXICAL && expr.name == "let") raise(expr.start, "let is disallowed as a lexically bound name")
                    if (bind != Bind.OUTSIDE) declareName(expr.name, bind, expr.start)
                }
            }
            is ChainExpression -> raise(expr.start, "Optional chaining cannot appear in left-hand side")
            is MemberExpression -> if (isBind) raise(expr.start, "Binding member expression")
            else -> if (isBind) raise(expr.start, "Binding ${expr::class.simpleName}")
        }
    }

    fun checkLValPattern(expr: Node, bind: Bind = Bind.NONE, fromForOf: Boolean = false) {
        when (expr) {
            is ObjectPattern -> for (prop in expr.properties) checkLValInnerPattern(prop, bind, fromForOf)
            is ArrayPattern -> for (e in expr.elements) if (e != null) checkLValInnerPattern(e, bind, fromForOf)
            is Identifier -> if (fromForOf && bind == Bind.VAR) {
                if (strict && (expr.name == "eval" || expr.name == "arguments")) raise(expr.start, "Binding ${expr.name} in strict mode")
                declareName(expr.name, Bind.VAR, expr.start, true)
            } else checkLValSimple(expr, bind)
            else -> {
                checkLValSimple(expr, bind)
                if (bind == Bind.NONE && expr !is Identifier && expr !is MemberExpression) raise(expr.start, "Invalid left-hand side in assignment")
            }
        }
    }

    fun checkLValInnerPattern(expr: Node, bind: Bind, fromForOf: Boolean) {
        when (expr) {
            is Property -> checkLValInnerPattern(expr.value, bind, fromForOf)
            is AssignmentPattern -> checkLValPattern(expr.left, bind, fromForOf)
            is RestElement -> checkLValPattern(expr.argument, bind, fromForOf)
            else -> checkLValPattern(expr, bind, fromForOf)
        }
    }

    // ============================================================ classes

    /** Class decorators parsed just before `class` (by a statement, `export` or expression), attached by [parseClass]. */
    var pendingDecorators: List<Node>? = null

    /**
     * DecoratorList: `@` followed by an identifier reference with property accesses (including private names), an
     * optional argument list, or a parenthesized expression.
     */
    fun parseDecorators(): List<Node> {
        val out = ArrayList<Node>()
        while (type == T.AT) {
            next()
            val p = sp()
            var e: Node
            if (type == T.LPAREN) {
                next()
                e = parseExpression()
                expect(T.RPAREN)
            } else {
                if (type != T.NAME) unexpected()
                e = parseIdentReference()
                while (type == T.DOT) {
                    next()
                    e = fin(MemberExpression(e, parsePropertyAccessName(), false, false), p)
                }
                if (eat(T.LPAREN)) {
                    val args = parseExprList(T.RPAREN, true, false, null, false).map { it!! }
                    e = fin(CallExpression(e, args, false), p)
                }
            }
            out.add(e)
        }
        return out
    }

    fun parseClass(isStatement: Boolean, optionalId: Boolean): ClassNode {
        val classDecorators = pendingDecorators ?: emptyList()
        pendingDecorators = null
        val p = sp()
        next() // class
        val oldStrict = strict
        strict = true
        var id: Identifier? = null
        if (type == T.NAME && !isKw("extends")) {
            run {
                id = parseBindingIdent()
                if (id.name == "let" || id.name == "static" || id.name == "yield" || id.name == "implements" ||
                    id.name == "interface" || id.name == "package" || id.name == "private" || id.name == "protected" || id.name == "public")
                    raise(id.start, "Unexpected strict mode reserved word '${id.name}'")
                if (id.name == "eval" || id.name == "arguments") raise(id.start, "Unexpected eval or arguments in strict mode")
                if (isStatement) declareName(id.name, Bind.LEXICAL, id.start)
            }
        } else if (isStatement && !optionalId) unexpected()
        var superClass: Node? = null
        if (eatKw("extends")) {
            superClass = parseExprSubscripts(null, false)
            if (isBareArrow(superClass)) raise(superClass.start, "Unexpected arrow function in class heritage")
        }
        val pn = PrivateNameScope(privateNames)
        privateNames = pn
        expect(T.LBRACE)
        val body = ArrayList<Node>()
        var ctor: FunctionNode? = null
        while (!eat(T.RBRACE)) {
            if (eat(T.SEMI)) continue
            val decStart = lex.start
            val decs = if (type == T.AT) parseDecorators() else emptyList()
            val el = parseClassElement(superClass != null)
            if (el is MethodDefinition && el.kind == MethodKind.CONSTRUCTOR) {
                if (ctor != null) raise(el.start, "A class may only have one constructor")
                ctor = el.value
            }
            if (decs.isNotEmpty()) {
                when {
                    el is MethodDefinition && el.kind != MethodKind.CONSTRUCTOR -> el.decorators = decs
                    el is PropertyDefinition -> el.decorators = decs
                    else -> raise(decStart, "Decorators are not valid here")
                }
            }
            body.add(el)
        }
        strict = oldStrict
        // validate private names
        privateNames = pn.parent
        for (u in pn.used) {
            if (!pn.declared.containsKey(u.name)) {
                val parent = pn.parent
                if (parent != null) parent.used.add(u)
                else if (!options.privateNames.contains(u.name)) raise(u.start, "Private field '#${u.name}' must be declared in an enclosing class")
            }
        }
        val cls = fin(ClassNode(id, superClass, body), p)
        cls.constructor = ctor
        cls.decorators = classDecorators
        cls.isDeclaration = isStatement
        cls.srcStart = p.start
        cls.srcEnd = lex.prevEnd
        return cls
    }

    private fun parseClassElement(derived: Boolean): Node {
        val p = sp()
        var isStatic = false
        var isAsync = false
        var isGenerator = false
        var kind = MethodKind.METHOD
        var keyName: String? = null
        var isAccessor = false

        if (isContextual("static")) {
            val save = lex.save()
            next()
            if (type == T.LBRACE) {
                return parseStaticBlock(p)
            }
            if (type == T.ASSIGN || type == T.SEMI || type == T.RBRACE || type == T.LPAREN) {
                lex.restore(save)
                keyName = "static"
            } else {
                isStatic = true
            }
        }
        val mp = sp()
        if (keyName == null && isContextual("async")) {
            val save = lex.save()
            next()
            if ((type == T.NAME || type == T.STRING || type == T.NUM || type == T.BIGINT || type == T.LBRACKET || type == T.PRIVATE_NAME || type == T.STAR) && !lex.nlBefore) {
                isAsync = true
            } else {
                lex.restore(save)
                keyName = "async"
            }
        }
        if (keyName == null && type == T.STAR) {
            next()
            isGenerator = true
        }
        if (keyName == null && !isAsync && !isGenerator && (isContextual("get") || isContextual("set"))) {
            val save = lex.save()
            val which = lex.value as String
            next()
            if (type == T.NAME || type == T.STRING || type == T.NUM || type == T.BIGINT || type == T.LBRACKET || type == T.PRIVATE_NAME) {
                kind = if (which == "get") MethodKind.GET else MethodKind.SET
            } else {
                lex.restore(save)
            }
        }
        if (keyName == null && !isAsync && !isGenerator && kind == MethodKind.METHOD && isContextual("accessor")) {
            val save = lex.save()
            next()
            if ((type == T.NAME || type == T.STRING || type == T.NUM || type == T.BIGINT || type == T.LBRACKET || type == T.PRIVATE_NAME) && !lex.nlBefore) {
                isAccessor = true
            } else lex.restore(save)
        }
        val keyP = sp()
        val keyInfo = parsePropertyName(allowPrivate = true)
        val key = keyInfo.key
        val keyStr: String? = if (keyInfo.computed) null else when (key) {
            is Identifier -> key.name
            is StringLiteral -> key.value
            else -> null
        }
        if (key is PrivateIdentifier && key.name == "constructor") raise(key.start, "Classes may not have a private field named '#constructor'")

        if (type == T.LPAREN && !isAccessor) {
            // method
            val isCtor = !isStatic && keyStr == "constructor" && key !is PrivateIdentifier
            if (isCtor) {
                if (kind != MethodKind.METHOD) raise(key.start, "Class constructor may not be an accessor")
                if (isGenerator) raise(key.start, "Class constructor may not be a generator")
                if (isAsync) raise(key.start, "Class constructor may not be an async method")
                kind = MethodKind.CONSTRUCTOR
            }
            if (isStatic && keyStr == "prototype" && key !is PrivateIdentifier) raise(key.start, "Classes may not have a static property named 'prototype'")
            val fk = when (kind) {
                MethodKind.CONSTRUCTOR -> if (derived) FunctionKind.DERIVED_CONSTRUCTOR else FunctionKind.CLASS_CONSTRUCTOR
                MethodKind.GET -> FunctionKind.GETTER
                MethodKind.SET -> FunctionKind.SETTER
                else -> FunctionKind.METHOD
            }
            val fn = parseMethod(isGenerator, isAsync, fk, isStatic, mp)
            if (kind == MethodKind.GET && fn.params.isNotEmpty()) raise(fn.start, "Getter must not have any formal parameters")
            if (kind == MethodKind.SET && (fn.params.size != 1 || fn.params[0] is RestElement)) raise(fn.start, "Setter must have exactly one formal parameter")
            if (key is PrivateIdentifier) declarePrivate(key, if (kind == MethodKind.GET) "get" else if (kind == MethodKind.SET) "set" else "method", isStatic)
            return fin(MethodDefinition(key, fn, kind, isStatic, keyInfo.computed), p)
        }
        // field
        if (isGenerator || isAsync || kind != MethodKind.METHOD) unexpected()
        if (keyStr == "constructor" && key !is PrivateIdentifier) raise(key.start, "Classes may not have a field named 'constructor'")
        if (isStatic && keyStr == "prototype" && key !is PrivateIdentifier) raise(key.start, "Classes may not have a static property named 'prototype'")
        if (key is PrivateIdentifier) declarePrivate(key, if (isAccessor) "accessor" else "field", isStatic)
        var value: Node? = null
        var initFn: FunctionNode? = null
        if (eat(T.ASSIGN)) {
            val ip = sp()
            val oldYield = yieldPos
            val oldAwait = awaitPos
            val oldAwaitIdent = awaitIdentPos
            yieldPos = -1; awaitPos = -1; awaitIdentPos = -1
            enterScope(SCOPE_FUNCTION or SCOPE_SUPER or SCOPE_CLASS_FIELD_INIT)
            currentScope.inClassFieldInit = true
            val oldLabels = withFreshLabels()
            value = parseMaybeAssign(false)
            labels = oldLabels
            exitScope()
            yieldPos = oldYield; awaitPos = oldAwait; awaitIdentPos = oldAwaitIdent
            initFn = FunctionNode(null, ArrayList(), value, FunctionKind.CLASS_FIELD_INIT, false, false)
            initFn.isExpressionBody = true
            initFn.strict = true
            initFn.isStatic = isStatic
            fin(initFn, ip)
            initFn.srcStart = ip.start
            initFn.srcEnd = lex.prevEnd
        }
        if (!eat(T.SEMI)) {
            if (type != T.RBRACE && !lex.nlBefore) unexpected()
        }
        val pd = fin(PropertyDefinition(key, value, isStatic, keyInfo.computed), p)
        pd.initializer = initFn
        pd.isAccessor = isAccessor
        return pd
    }

    private fun declarePrivate(key: PrivateIdentifier, kind: String, isStatic: Boolean) {
        val pn = privateNames!!
        val existing = pn.declared[key.name]
        val k = (if (isStatic) "s" else "i") + kind
        if (existing != null) {
            val ok = (existing == (if (isStatic) "sget" else "iget") && kind == "set") ||
                    (existing == (if (isStatic) "sset" else "iset") && kind == "get")
            if (!ok) raise(key.start, "Identifier '#${key.name}' has already been declared")
            pn.declared[key.name] = (if (isStatic) "s" else "i") + "getset"
        } else pn.declared[key.name] = k
    }

    private fun parseStaticBlock(p: SP): Node {
        // current token is '{'
        val oldLabels = withFreshLabels()
        val oldYield = yieldPos
        val oldAwait = awaitPos
        val oldAwaitIdent = awaitIdentPos
        yieldPos = -1; awaitPos = -1; awaitIdentPos = -1
        enterScope(SCOPE_CLASS_STATIC_BLOCK or SCOPE_SUPER)
        val bp = sp()
        expect(T.LBRACE)
        val body = parseBlockBody(true, false, null)
        exitScope()
        labels = oldLabels
        yieldPos = oldYield; awaitPos = oldAwait; awaitIdentPos = oldAwaitIdent
        val sb = fin(StaticBlock(body), p)
        val fn = FunctionNode(null, ArrayList(), fin(BlockStatement(body), bp), FunctionKind.STATIC_BLOCK, false, false)
        fn.strict = true
        fn.isStatic = true
        fin(fn, bp)
        fn.srcStart = bp.start
        fn.srcEnd = lex.prevEnd
        sb.function = fn
        return sb
    }
}
