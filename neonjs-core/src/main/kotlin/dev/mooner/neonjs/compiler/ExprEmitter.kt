package dev.mooner.neonjs.compiler

import dev.mooner.neonjs.parser.*

internal enum class BindMode { ASSIGN, INIT }

internal abstract class ExprEmitter(fi: FnInfo, source: Source, analyzer: ScopeAnalyzer) :
    EmitterBase(fi, source, analyzer) {

    /** Compiles a nested function into a template. */
    abstract fun compileNested(fn: FunctionNode, name: String, fieldKeyDynamic: Boolean = false): CodeBlock

    /** Emits a `return` of the value on top of the stack (through finally blocks / iterator closes). */
    abstract fun emitReturnValue()

    val isAsyncGenerator get() = fi.isAsync && fi.isGenerator

    // ================================================================== entry points

    fun expr(n: Node) {
        when (n) {
            is NumberLiteral -> pushConst(n.value)
            is StringLiteral -> emit(Op.PUSH_CONST, const(n.value))
            is BooleanLiteral -> emit(if (n.value) Op.PUSH_TRUE else Op.PUSH_FALSE)
            is NullLiteral -> emit(Op.PUSH_NULL)
            is BigIntLiteral -> emit(Op.PUSH_CONST, const(n.value))
            is RegExpLiteral -> emit(Op.NEW_REGEXP, const(RegExpSite(n.pattern, n.flags)))
            is Identifier -> identLoad(n)
            is ThisExpression -> thisLoad(n)
            is TemplateLiteral -> template(n)
            is TaggedTemplate -> taggedTemplate(n)
            is ArrayLiteral -> arrayLiteral(n)
            is ObjectLiteral -> objectLiteral(n)
            is FunctionNode -> function(n, null)
            is ClassNode -> classExpr(n, null)
            is UnaryExpression -> unary(n)
            is UpdateExpression -> update(n, true)
            is BinaryExpression -> binary(n)
            is LogicalExpression -> logical(n)
            is PrivateInExpression -> {
                privateNameLoad(n.name)
                expr(n.right)
                emit(Op.HAS_PRIVATE)
            }
            is AssignmentExpression -> assign(n, true)
            is ConditionalExpression -> {
                val elseL = newLabel()
                val end = newLabel()
                condJump(n.test, false, elseL)
                expr(n.consequent)
                emitJump(Op.JUMP, end)
                placeAfterJump(elseL)
                resetDepth(currentDepth)
                expr(n.alternate)
                place(end)
            }
            is CallExpression -> call(n)
            is NewExpression -> newExpr(n)
            is MemberExpression -> member(n)
            is ChainExpression -> chain(n)
            is SequenceExpression -> {
                for (i in n.expressions.indices) {
                    expr(n.expressions[i])
                    if (i < n.expressions.size - 1) emit(Op.POP)
                }
            }
            is YieldExpression -> yieldExpr(n)
            is AwaitExpression -> {
                expr(n.argument)
                emitAwait()
            }
            is MetaProperty -> metaProperty(n)
            is ImportCall -> {
                mark(n)
                expr(n.source)
                if (n.options != null) expr(n.options) else emit(Op.PUSH_UNDEF)
                if (n.phase == null) emit(Op.DYNAMIC_IMPORT) else emit(Op.DYNAMIC_IMPORT_PHASE, const(n.phase))
            }
            is Super -> throw CompileError("unexpected super")
            is SpreadElement -> throw CompileError("unexpected spread")
            else -> throw CompileError("unsupported expression ${n::class.simpleName}")
        }
    }

    fun exprDiscard(n: Node) {
        when (n) {
            is UpdateExpression -> update(n, false)
            is AssignmentExpression -> assign(n, false)
            else -> {
                expr(n)
                emit(Op.POP)
            }
        }
    }

    fun emitAwait() {
        emit(Op.AWAIT)
    }

    /** IsAnonymousFunctionDefinition */
    fun isAnonymousFn(n: Node?): Boolean = when (n) {
        is FunctionNode -> n.id == null && n.kind != FunctionKind.METHOD
        is ClassNode -> n.id == null
        else -> false
    }

    /** Evaluates [n] applying NamedEvaluation when it is an anonymous function/class. */
    fun exprNamed(n: Node, name: FnName?) {
        if (name != null && isAnonymousFn(n)) {
            if (n is FunctionNode) function(n, name)
            else classExpr(n as ClassNode, name)
        } else expr(n)
    }

    // ================================================================== identifiers

    fun identLoad(n: Identifier) {
        if (n.name == "undefined" && n.ref is GlobalRef) {
            emit(Op.LOAD_GLOBAL, const("undefined"))
            return
        }
        emitLoadRef(n.ref, n.name)
    }

    fun thisLoad(n: Node) {
        val ref = n.ref
        if (ref == null) {
            emit(Op.LOAD_THIS)
            return
        }
        emitLoadRef(ref, "this")
    }

    fun loadPseudo(n: Node, name: String) {
        val ref = n.ref
        if (ref == null) {
            when (name) {
                "this" -> emit(Op.LOAD_THIS)
                "new.target" -> emit(Op.LOAD_NEW_TARGET)
                "%home" -> emit(Op.LOAD_HOME)
                "%fn" -> emit(Op.LOAD_FUNCTION)
            }
            return
        }
        emitLoadRef(ref, name)
    }

    fun metaProperty(n: MetaProperty) {
        if (n.meta == "new") loadPseudo(n, "new.target")
        else emit(Op.IMPORT_META)
    }

    fun privateNameLoad(p: PrivateIdentifier) {
        val ref = p.ref
        if (ref is LocalRef) emitLoadBinding(ref.binding, false)
        else emit(Op.LOAD_NAME, const("#" + p.name))
    }

    // ================================================================== conditions

    /** Jumps to [target] if ToBoolean(n) == [jumpIf]. */
    fun condJump(n: Node, jumpIf: Boolean, target: Label) {
        when {
            n is UnaryExpression && n.op == "!" -> condJump(n.argument, !jumpIf, target)
            n is LogicalExpression && n.op == "&&" -> {
                if (jumpIf) {
                    val skip = newLabel()
                    condJump(n.left, false, skip)
                    condJump(n.right, true, target)
                    place(skip)
                } else {
                    condJump(n.left, false, target)
                    condJump(n.right, false, target)
                }
            }
            n is LogicalExpression && n.op == "||" -> {
                if (jumpIf) {
                    condJump(n.left, true, target)
                    condJump(n.right, true, target)
                } else {
                    val skip = newLabel()
                    condJump(n.left, true, skip)
                    condJump(n.right, false, target)
                    place(skip)
                }
            }
            n is BooleanLiteral -> if (n.value == jumpIf) emitJump(Op.JUMP, target)
            else -> {
                expr(n)
                emitJump(if (jumpIf) Op.JUMP_IF_TRUE else Op.JUMP_IF_FALSE, target)
            }
        }
    }

    // ================================================================== operators

    private fun unary(n: UnaryExpression) {
        when (n.op) {
            "typeof" -> {
                val a = n.argument
                if (a is Identifier) {
                    val ref = a.ref
                    if (ref is LocalRef) emitLoadRef(ref, a.name) else emitLoadRef(ref, a.name, forTypeof = true)
                } else expr(a)
                emit(Op.TYPEOF)
            }
            "delete" -> delete(n.argument)
            "void" -> {
                exprDiscard(n.argument)
                emit(Op.PUSH_UNDEF)
            }
            "!" -> {
                expr(n.argument)
                emit(Op.NOT)
            }
            "-" -> {
                val a = n.argument
                if (a is NumberLiteral) pushConst(-a.value)
                else if (a is BigIntLiteral) emit(Op.PUSH_CONST, const(a.value.negate()))
                else {
                    expr(a)
                    emit(Op.NEG)
                }
            }
            "+" -> {
                expr(n.argument)
                emit(Op.TO_NUMBER)
            }
            "~" -> {
                expr(n.argument)
                emit(Op.BNOT)
            }
            else -> throw CompileError("unary ${n.op}")
        }
    }

    private fun delete(a: Node) {
        when (a) {
            is MemberExpression -> {
                if (a.obj is Super) {
                    // delete super.x : evaluate this (TDZ), key, then ReferenceError
                    thisLoad(a.ref as Node)
                    emit(Op.POP)
                    if (a.computed) { expr(a.property); emit(Op.POP) }
                    throwError(1, "Unsupported reference to 'super'")
                    emit(Op.PUSH_UNDEF)
                    return
                }
                expr(a.obj)
                mark(a)
                if (a.computed) {
                    expr(a.property)
                    emit(Op.DELETE_ELEM)
                } else {
                    emit(Op.DELETE_PROP, keyConst((a.property as Identifier).name))
                }
            }
            is ChainExpression -> {
                // a short-circuited chain is not a reference: delete yields true
                chainWith(a, Op.PUSH_TRUE) { inner ->
                    if (inner is MemberExpression) {
                        memberObject(inner)
                        if (inner.computed) {
                            expr(inner.property)
                            emit(Op.DELETE_ELEM)
                        } else emit(Op.DELETE_PROP, keyConst((inner.property as Identifier).name))
                    } else {
                        expr(inner)
                        emit(Op.POP)
                        emit(Op.PUSH_TRUE)
                    }
                }
            }
            is Identifier -> {
                val ref = a.ref
                when (ref) {
                    is LocalRef -> emit(Op.PUSH_FALSE)
                    else -> emit(Op.DELETE_NAME, const(a.name))
                }
            }
            else -> {
                exprDiscard(a)
                emit(Op.PUSH_TRUE)
            }
        }
    }

    private fun binary(n: BinaryExpression) {
        expr(n.left)
        expr(n.right)
        mark(n)
        emit(
            when (n.op) {
                "+" -> Op.ADD; "-" -> Op.SUB; "*" -> Op.MUL; "/" -> Op.DIV; "%" -> Op.MOD; "**" -> Op.EXP
                "<<" -> Op.SHL; ">>" -> Op.SAR; ">>>" -> Op.SHR; "&" -> Op.BAND; "|" -> Op.BOR; "^" -> Op.BXOR
                "==" -> Op.EQ; "!=" -> Op.NE; "===" -> Op.SEQ; "!==" -> Op.SNE
                "<" -> Op.LT; ">" -> Op.GT; "<=" -> Op.LE; ">=" -> Op.GE
                "instanceof" -> Op.INSTANCEOF; "in" -> Op.IN
                else -> throw CompileError("binary ${n.op}")
            }
        )
    }

    private fun logical(n: LogicalExpression) {
        val end = newLabel()
        expr(n.left)
        emit(Op.DUP)
        when (n.op) {
            "&&" -> emitJump(Op.JUMP_IF_FALSE, end)
            "||" -> emitJump(Op.JUMP_IF_TRUE, end)
            "??" -> emitJump(Op.JUMP_IF_NOT_NULLISH, end)
        }
        emit(Op.POP)
        expr(n.right)
        place(end)
    }

    private fun binOpFor(op: String): Int = when (op) {
        "+=" -> Op.ADD; "-=" -> Op.SUB; "*=" -> Op.MUL; "/=" -> Op.DIV; "%=" -> Op.MOD; "**=" -> Op.EXP
        "<<=" -> Op.SHL; ">>=" -> Op.SAR; ">>>=" -> Op.SHR; "&=" -> Op.BAND; "|=" -> Op.BOR; "^=" -> Op.BXOR
        else -> throw CompileError("assign op $op")
    }

    // ================================================================== assignment

    /** True for global names declared by the code being compiled (always resolvable when assigned). */
    private fun declaredGlobal(name: String): Boolean =
        name in analyzer.globalVarNames || analyzer.globalLexNames.containsKey(name)

    /** Expressions whose evaluation cannot run user code (so cannot create the assigned global first). */
    private fun isInert(n: Node): Boolean = n is NumberLiteral || n is StringLiteral || n is BooleanLiteral || n is NullLiteral ||
        n is BigIntLiteral || n is FunctionNode || (n is TemplateLiteral && n.expressions.isEmpty())

    /** Reference kinds for assignment targets. */
    private fun assign(n: AssignmentExpression, keep: Boolean) {
        val t = n.target
        val op = n.op
        if (op == "=") {
            when (t) {
                is Identifier -> {
                    val nm = if (t.parenthesized) null else StaticName(t.name)
                    if (t.ref is DynamicRef || (t.ref is GlobalRef && strict && !declaredGlobal(t.name) && !isInert(n.value))) {
                        // the binding is resolved before evaluating the right-hand side
                        emit(Op.RESOLVE_NAME, const(t.name))
                        exprNamed(n.value, nm)
                        emit(Op.PUT_REF)
                        if (!keep) emit(Op.POP)
                        return
                    }
                    exprNamed(n.value, nm)
                    if (keep) emit(Op.DUP)
                    emitStoreRef(t.ref, t.name)
                }
                is MemberExpression -> {
                    if (t.obj is Super) {
                        superRefPrefix(t)
                        expr(n.value)
                        emitPutSuper(keep)
                        return
                    }
                    expr(t.obj)
                    if (t.property is PrivateIdentifier) {
                        privateNameLoad(t.property)
                        expr(n.value)
                        mark(t)
                        emit(Op.PUT_PRIVATE)
                    } else if (t.computed) {
                        expr(t.property)
                        expr(n.value)
                        mark(t)
                        emit(Op.PUT_ELEM)
                    } else {
                        expr(n.value)
                        mark(t)
                        emit(Op.PUT_PROP, keyConst((t.property as Identifier).name))
                    }
                    if (!keep) emit(Op.POP)
                }
                is ObjectPattern, is ArrayPattern -> {
                    expr(n.value)
                    if (keep) emit(Op.DUP)
                    pattern(t, BindMode.ASSIGN)
                }
                is CallExpression -> {
                    // Annex B: f() = v  -> evaluate call, then ReferenceError
                    expr(t)
                    emit(Op.POP)
                    throwError(1, "Invalid left-hand side in assignment")
                    if (keep) emit(Op.PUSH_UNDEF)
                }
                else -> throw CompileError("bad assignment target ${t::class.simpleName}")
            }
            return
        }
        if (op == "&&=" || op == "||=" || op == "??=") {
            logicalAssign(n, keep)
            return
        }
        val binop = binOpFor(op)
        when (t) {
            is Identifier -> {
                if (t.ref is DynamicRef) {
                    emit(Op.RESOLVE_NAME, const(t.name))
                    emit(Op.DUP)
                    emit(Op.GET_REF)
                    expr(n.value)
                    mark(n)
                    emit(binop)
                    emit(Op.PUT_REF)
                    if (!keep) emit(Op.POP)
                    return
                }
                identLoad(t)
                expr(n.value)
                mark(n)
                emit(binop)
                if (keep) emit(Op.DUP)
                emitStoreRef(t.ref, t.name)
            }
            is MemberExpression -> {
                if (t.obj is Super) {
                    superRefPrefix(t)               // this key base
                    superGetFromPrefix()            // this key base v
                    expr(n.value)
                    emit(binop)
                    emitPutSuper(keep)
                    return
                }
                expr(t.obj)
                if (t.property is PrivateIdentifier) {
                    privateNameLoad(t.property)     // o pn
                    emit(Op.DUP2)                   // o pn o pn
                    emit(Op.GET_PRIVATE)            // o pn v
                    expr(n.value)
                    emit(binop)
                    emit(Op.PUT_PRIVATE)
                } else if (t.computed) {
                    expr(t.property)
                    emit(Op.TO_KEY_FOR_BASE)      // o k  (converted after base check)
                    emit(Op.DUP2)
                    mark(t)
                    emit(Op.GET_ELEM)
                    expr(n.value)
                    mark(n)
                    emit(binop)
                    emit(Op.PUT_ELEM)
                } else {
                    val k = keyConst((t.property as Identifier).name)
                    emit(Op.DUP)
                    mark(t)
                    emit(Op.GET_PROP, k)
                    expr(n.value)
                    mark(n)
                    emit(binop)
                    emit(Op.PUT_PROP, k)
                }
                if (!keep) emit(Op.POP)
            }
            is CallExpression -> {
                expr(t)
                emit(Op.POP)
                throwError(1, "Invalid left-hand side in assignment")
                if (keep) emit(Op.PUSH_UNDEF)
            }
            else -> throw CompileError("bad compound target")
        }
    }

    private fun logicalAssign(n: AssignmentExpression, keep: Boolean) {
        val t = n.target
        val end = newLabel()
        val jumpOp = when (n.op) {
            "&&=" -> Op.JUMP_IF_FALSE
            "||=" -> Op.JUMP_IF_TRUE
            else -> Op.JUMP_IF_NOT_NULLISH
        }
        when (t) {
            is Identifier -> {
                if (t.ref is DynamicRef) {
                    emit(Op.RESOLVE_NAME, const(t.name))
                    emit(Op.DUP)
                    emit(Op.GET_REF)
                    emit(Op.DUP)
                    val skip = newLabel()
                    emitJump(jumpOp, skip)
                    emit(Op.POP)
                    exprNamed(n.value, if (t.parenthesized) null else StaticName(t.name))
                    emit(Op.PUT_REF)
                    emitJump(Op.JUMP, end)
                    placeAfterJump(skip)
                    emit(Op.SWAP)
                    emit(Op.POP)
                    place(end)
                    if (!keep) emit(Op.POP)
                    return
                }
                identLoad(t)
                emit(Op.DUP)
                emitJump(jumpOp, end)
                emit(Op.POP)
                exprNamed(n.value, if (t.parenthesized) null else StaticName(t.name))
                emit(Op.DUP)
                emitStoreRef(t.ref, t.name)
                place(end)
                if (!keep) emit(Op.POP)
            }
            is MemberExpression -> {
                if (t.obj is Super) {
                    superRefPrefix(t)           // this key base
                    superGetFromPrefix()        // this key base v
                    emit(Op.DUP)
                    val skip = newLabel()
                    emitJump(jumpOp, skip)
                    emit(Op.POP)
                    exprNamed(n.value, null)
                    emitPutSuper(true)          // v
                    val done = newLabel()
                    emitJump(Op.JUMP, done)
                    placeAfterJump(skip)        // this key base v
                    val tmp = allocReg()
                    emit(Op.STORE_REG, tmp)
                    emit(Op.POP); emit(Op.POP); emit(Op.POP)
                    emit(Op.LOAD_REG, tmp)
                    freeReg(tmp)
                    place(done)
                    if (!keep) emit(Op.POP)
                    return
                }
                expr(t.obj)
                val skip = newLabel()
                if (t.property is PrivateIdentifier) {
                    privateNameLoad(t.property)
                    emit(Op.DUP2)
                    emit(Op.GET_PRIVATE)       // o pn v
                    emit(Op.DUP)
                    emitJump(jumpOp, skip)
                    emit(Op.POP)
                    exprNamed(n.value, null)
                    emit(Op.PUT_PRIVATE)       // v
                } else if (t.computed) {
                    expr(t.property)
                    emit(Op.TO_KEY_FOR_BASE)
                    emit(Op.DUP2)
                    emit(Op.GET_ELEM)          // o k v
                    emit(Op.DUP)
                    emitJump(jumpOp, skip)
                    emit(Op.POP)
                    expr(n.value)
                    emit(Op.PUT_ELEM)
                } else {
                    val k = keyConst((t.property as Identifier).name)
                    emit(Op.DUP)
                    emit(Op.GET_PROP, k)       // o v
                    emit(Op.DUP)
                    val skip1 = newLabel()
                    emitJump(jumpOp, skip1)
                    emit(Op.POP)
                    expr(n.value)
                    emit(Op.PUT_PROP, k)       // v
                    emitJump(Op.JUMP, end)
                    placeAfterJump(skip1)      // o v
                    emit(Op.SWAP)
                    emit(Op.POP)
                    place(end)
                    if (!keep) emit(Op.POP)
                    return
                }
                emitJump(Op.JUMP, end)
                placeAfterJump(skip)           // o k v
                emit(Op.ROT3)                  // v o k
                emit(Op.POP)
                emit(Op.POP)
                place(end)
                if (!keep) emit(Op.POP)
            }
            else -> throw CompileError("bad logical assignment target")
        }
    }

    private fun update(n: UpdateExpression, keep: Boolean) {
        val t = n.argument
        val op = if (n.op == "++") Op.INC else Op.DEC
        when (t) {
            is Identifier -> {
                if (t.ref is DynamicRef) {
                    emit(Op.RESOLVE_NAME, const(t.name))
                    emit(Op.DUP)
                    emit(Op.GET_REF)
                    emit(Op.TO_NUMERIC)
                    if (n.prefix) {
                        emit(op)
                        emit(Op.PUT_REF)
                        if (!keep) emit(Op.POP)
                    } else {
                        val tmp = allocReg()
                        emit(Op.DUP)
                        emit(Op.STORE_REG, tmp)
                        emit(op)
                        emit(Op.PUT_REF)
                        emit(Op.POP)
                        if (keep) emit(Op.LOAD_REG, tmp)
                        freeReg(tmp)
                    }
                    return
                }
                identLoad(t)
                emit(Op.TO_NUMERIC)
                if (n.prefix) {
                    emit(op)
                    if (keep) emit(Op.DUP)
                } else {
                    if (keep) emit(Op.DUP)
                    emit(op)
                }
                emitStoreRef(t.ref, t.name)
            }
            is MemberExpression -> {
                if (t.obj is Super) {
                    superRefPrefix(t)
                    superGetFromPrefix()       // this key base v
                    emit(Op.TO_NUMERIC)
                    if (n.prefix) {
                        emit(op)
                        emitPutSuper(keep)
                    } else {
                        val tmp = allocReg()
                        emit(Op.DUP)
                        emit(Op.STORE_REG, tmp)
                        emit(op)
                        emitPutSuper(false)
                        if (keep) emit(Op.LOAD_REG, tmp)
                        freeReg(tmp)
                    }
                    return
                }
                expr(t.obj)
                if (t.property is PrivateIdentifier) {
                    privateNameLoad(t.property)   // o pn
                    emit(Op.DUP2)
                    emit(Op.GET_PRIVATE)          // o pn v
                    emit(Op.TO_NUMERIC)
                    if (n.prefix) {
                        emit(op)
                        emit(Op.PUT_PRIVATE)      // v'
                        if (!keep) emit(Op.POP)
                    } else {
                        val tmp = allocReg()
                        emit(Op.DUP)
                        emit(Op.STORE_REG, tmp)
                        emit(op)
                        emit(Op.PUT_PRIVATE)
                        emit(Op.POP)
                        if (keep) emit(Op.LOAD_REG, tmp)
                        freeReg(tmp)
                    }
                    return
                }
                if (t.computed) {
                    expr(t.property)
                    emit(Op.TO_KEY_FOR_BASE)
                    emit(Op.DUP2)
                    mark(t)
                    emit(Op.GET_ELEM)             // o k v
                } else {
                    emit(Op.DUP)
                    mark(t)
                    emit(Op.GET_PROP, keyConst((t.property as Identifier).name))  // o v
                }
                emit(Op.TO_NUMERIC)
                if (n.prefix) {
                    emit(op)
                    if (t.computed) emit(Op.PUT_ELEM) else emit(Op.PUT_PROP, keyConst((t.property as Identifier).name))
                    if (!keep) emit(Op.POP)
                } else {
                    val tmp = allocReg()
                    emit(Op.DUP)
                    emit(Op.STORE_REG, tmp)
                    emit(op)
                    if (t.computed) emit(Op.PUT_ELEM) else emit(Op.PUT_PROP, keyConst((t.property as Identifier).name))
                    emit(Op.POP)
                    if (keep) emit(Op.LOAD_REG, tmp)
                    freeReg(tmp)
                }
            }
            is CallExpression -> {
                expr(t)
                emit(Op.POP)
                throwError(1, "Invalid left-hand side expression in ${if (n.prefix) "prefix" else "postfix"} operation")
                if (keep) emit(Op.PUSH_UNDEF)
            }
            else -> throw CompileError("bad update target")
        }
    }

    // ================================================================== super

    /** Pushes [this key base] for a super property reference. */
    fun superRefPrefix(t: MemberExpression) {
        thisLoad(t.ref as Node)
        if (t.computed) {
            expr(t.property)
        } else emit(Op.PUSH_CONST, keyConst((t.property as Identifier).name))
        loadPseudo(t.obj, "%home")
        emit(Op.GET_SUPER_BASE)
    }

    /** (this key base -- this key base value): reads the super property keeping the reference. */
    fun superGetFromPrefix() {
        // stack: this key base  -> need value = base.[[Get]](key, this)
        emit(Op.DUP3)          // this key base this key base
        emit(Op.GET_SUPER)              // this key base v
    }

    /** (this key base v -- v?) */
    fun emitPutSuper(keep: Boolean) {
        emit(Op.PUT_SUPER)
        if (!keep) emit(Op.POP)
    }

    // ================================================================== members

    fun member(n: MemberExpression) {
        if (n.obj is Super) {
            superRefPrefix(n)
            mark(n)
            emit(Op.GET_SUPER)
            return
        }
        expr(n.obj)
        if (n.optional) optionalCheck()
        memberGet(n)
    }

    /** With the object on the stack, performs the property get of [n]. */
    fun memberGet(n: MemberExpression) {
        val p = n.property
        if (p is PrivateIdentifier) {
            privateNameLoad(p)
            mark(n)
            emit(Op.GET_PRIVATE)
        } else if (n.computed) {
            expr(p)
            mark(n)
            emit(Op.GET_ELEM)
        } else {
            mark(n)
            emit(Op.GET_PROP, keyConst((p as Identifier).name))
        }
    }

    // ================================================================== optional chains

    private var chainNil: HashMap<Int, Label>? = null
    private var chainBase = 0

    /** Evaluates an optional chain; a short-circuited chain produces undefined ([shortCircuitOp] pushes it). */
    private inline fun chainWith(n: ChainExpression, shortCircuitOp: Int = Op.PUSH_UNDEF, body: (Node) -> Unit) {
        val savedNil = chainNil
        val savedBase = chainBase
        val nil = HashMap<Int, Label>()
        chainNil = nil
        chainBase = currentDepth
        body(n.expression)
        val end = newLabel()
        emitJump(Op.JUMP, end)
        val resultDepth = currentDepth
        for ((d, l) in nil) {
            resetDepth(d)
            place(l)
            repeat(d - chainBase) { emit(Op.POP) }
            emit(shortCircuitOp)
            emitJump(Op.JUMP, end)
        }
        resetDepth(resultDepth)
        place(end)
        chainNil = savedNil
        chainBase = savedBase
    }

    /** Emits a nullish check for an optional link; value to test is on top of stack. */
    fun optionalCheck() {
        val nil = chainNil ?: throw CompileError("optional outside chain")
        emit(Op.DUP)
        val d = currentDepth - 1
        val l = nil.getOrPut(d) { Label() }
        emitJump(Op.JUMP_IF_NULLISH, l)
        l.depth = d
    }

    fun chain(n: ChainExpression) {
        chainWith(n) { expr(it) }
    }

    /** Evaluates the object part of a member expression, handling optional links. */
    fun memberObject(n: MemberExpression) {
        expr(n.obj)
        if (n.optional) optionalCheck()
    }

    // ================================================================== calls

    /** Pushes [f, this] for a call's callee. */
    fun callee(c: Node) {
        when (c) {
            is MemberExpression -> {
                if (c.obj is Super) {
                    thisLoad(c.ref as Node)
                    emit(Op.DUP)
                    if (c.computed) expr(c.property) else emit(Op.PUSH_CONST, keyConst((c.property as Identifier).name))
                    loadPseudo(c.obj, "%home")
                    emit(Op.GET_SUPER_BASE)
                    emit(Op.GET_SUPER)          // this f
                    emit(Op.SWAP)
                    return
                }
                expr(c.obj)
                if (c.optional) optionalCheck()
                emit(Op.DUP)
                memberGet(c)
                emit(Op.SWAP)
            }
            is Identifier -> {
                val ref = c.ref
                if (ref is DynamicRef) {
                    emit(Op.LOAD_NAME_CALL, const(c.name))
                } else {
                    identLoad(c)
                    emit(Op.PUSH_UNDEF)
                }
            }
            is ChainExpression -> {
                // (a?.b)() : this is preserved
                val inner = c.expression
                if (inner is MemberExpression && inner.obj !is Super) {
                    val savedNil = chainNil
                    val savedBase = chainBase
                    val nil = HashMap<Int, Label>()
                    chainNil = nil
                    chainBase = currentDepth
                    expr(inner.obj)
                    if (inner.optional) optionalCheck()
                    emit(Op.DUP)
                    memberGet(inner)
                    emit(Op.SWAP)
                    val end = newLabel()
                    emitJump(Op.JUMP, end)
                    val rd = currentDepth
                    for ((d, l) in nil) {
                        resetDepth(d)
                        place(l)
                        repeat(d - chainBase) { emit(Op.POP) }
                        emit(Op.PUSH_UNDEF)
                        emit(Op.PUSH_UNDEF)
                        emitJump(Op.JUMP, end)
                    }
                    resetDepth(rd)
                    place(end)
                    chainNil = savedNil
                    chainBase = savedBase
                } else {
                    expr(c)
                    emit(Op.PUSH_UNDEF)
                }
            }
            else -> {
                expr(c)
                emit(Op.PUSH_UNDEF)
            }
        }
    }

    private fun hasSpread(args: List<Node>) = args.any { it is SpreadElement }

    /** Pushes arguments; returns argc, or -1 when a spread array was pushed instead. */
    fun arguments(args: List<Node>): Int {
        if (!hasSpread(args)) {
            for (a in args) expr(a)
            return args.size
        }
        emit(Op.NEW_ARRAY)
        for (a in args) {
            if (a is SpreadElement) {
                expr(a.argument)
                emit(Op.ARRAY_SPREAD)
            } else {
                expr(a)
                emit(Op.ARRAY_PUSH)
            }
        }
        return -1
    }

    // ================================================================== proper tail calls

    /** Call nodes in tail position of the expression being returned (identity set), or null. */
    private var tailCalls: MutableSet<Node>? = null

    /** Number of enclosing `try` blocks (a call there is not in tail position: the handler must run). */
    protected var tryDepth = 0

    /**
     * Whether a `return` here can perform proper tail calls (ECMA-262 IsInTailPosition): strict code in an ordinary
     * function body, not inside try / finally / iterator-closing / disposal regions.
     */
    private fun tailCallsAllowed(): Boolean {
        if (!strict || fi.isTopLevel || fi.isGenerator || fi.isAsync || tryDepth > 0) return false
        when (fi.kind) {
            FunctionKind.NORMAL, FunctionKind.ARROW, FunctionKind.METHOD, FunctionKind.GETTER, FunctionKind.SETTER -> {}
            else -> return false
        }
        var c = ctl
        while (c != null) {
            if (c is FinallyCtl || c is IterCtl) return false
            c = c.parent
        }
        return true
    }

    private fun collectTailCalls(e: Node, out: MutableSet<Node>) {
        when (e) {
            is CallExpression -> if (e.callee !is Super && !e.optional && e.arguments.none { it is SpreadElement }) out.add(e)
            is TaggedTemplate -> out.add(e)
            is ConditionalExpression -> { collectTailCalls(e.consequent, out); collectTailCalls(e.alternate, out) }
            is LogicalExpression -> collectTailCalls(e.right, out)
            is SequenceExpression -> collectTailCalls(e.expressions.last(), out)
            else -> {}
        }
    }

    /** Evaluates the operand of a return (or a concise arrow body), marking calls in tail position. */
    fun exprReturned(e: Node) {
        if (!tailCallsAllowed()) {
            expr(e)
            return
        }
        val set = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Node, Boolean>())
        collectTailCalls(e, set)
        val saved = tailCalls
        tailCalls = if (set.isEmpty()) null else set
        try {
            expr(e)
        } finally {
            tailCalls = saved
        }
    }

    private fun isTail(n: Node): Boolean = tailCalls?.contains(n) == true

    fun call(n: CallExpression) {
        val c = n.callee
        if (c is Super) {
            superCall(n)
            return
        }
        val tail = isTail(n)
        if (c is Identifier && c.name == "eval" && !n.optional) {
            callee(c)
            val argc = arguments(n.arguments)
            mark(n)
            markCallee(c)
            val flags = (if (strict) 1 else 0) or (if (tail) 2 else 0)
            if (argc >= 0) emit(Op.CALL_EVAL, argc, flags) else emit(Op.CALL_EVAL_SPREAD, flags)
            return
        }
        callee(c)
        if (n.optional) {
            // f?.() : check the function value (below this)
            emit(Op.SWAP)
            optionalCheck()
            emit(Op.SWAP)
        }
        val argc = arguments(n.arguments)
        mark(n)
        markCallee(c)
        if (argc >= 0) emit(if (tail) Op.TAIL_CALL else Op.CALL, argc) else emit(Op.CALL_SPREAD)
    }

    private fun superCall(n: CallExpression) {
        // func = activeFunction.[[GetPrototypeOf]]() evaluated before arguments
        loadPseudo(n.callee, "%fn")
        emit(Op.GET_PROTO_OF)
        loadPseudo(n.callee.scope as Node, "new.target")
        val argc = arguments(n.arguments)
        mark(n)
        if (argc >= 0) emit(Op.SUPER_CALL, argc) else emit(Op.SUPER_CALL_SPREAD)
        // bind this
        emit(Op.DUP)
        val th = n.ref as Node
        val ref = th.ref
        if (ref is LocalRef) {
            val b = ref.binding
            if (b.inEnv) emit(Op.INIT_THIS_ENV, hops(b.scope), b.slot) else emit(Op.INIT_THIS_REG, b.reg)
        } else emit(Op.INIT_THIS_NAME)
        loadPseudo(n.callee, "%fn")
        emit(Op.INIT_INSTANCE)
    }

    private fun newExpr(n: NewExpression) {
        expr(n.callee)
        val argc = arguments(n.arguments)
        mark(n)
        markCallee(n.callee)
        if (argc >= 0) emit(Op.NEW, argc) else emit(Op.NEW_SPREAD)
    }

    // ================================================================== templates

    private fun template(n: TemplateLiteral) {
        emit(Op.PUSH_CONST, const(n.quasis[0].cooked ?: ""))
        for (i in n.expressions.indices) {
            expr(n.expressions[i])
            emit(Op.TO_STRING)
            emit(Op.CONCAT)
            val q = n.quasis[i + 1].cooked ?: ""
            if (q.isNotEmpty()) {
                emit(Op.PUSH_CONST, const(q))
                emit(Op.CONCAT)
            }
        }
    }

    private fun taggedTemplate(n: TaggedTemplate) {
        callee(n.tag)
        val q = n.quasi
        val site = TemplateSite(q.quasis.map { it.cooked }.toTypedArray(), q.quasis.map { it.raw }.toTypedArray())
        emit(Op.TEMPLATE_OBJECT, const(site))
        for (e in q.expressions) expr(e)
        mark(n)
        markCallee(n.tag)
        emit(if (isTail(n)) Op.TAIL_CALL else Op.CALL, q.expressions.size + 1)
    }

    // ================================================================== literals

    private fun arrayLiteral(n: ArrayLiteral) {
        emit(Op.NEW_ARRAY)
        for (e in n.elements) {
            when (e) {
                null -> emit(Op.ARRAY_HOLE)
                is SpreadElement -> {
                    expr(e.argument)
                    emit(Op.ARRAY_SPREAD)
                }
                else -> {
                    expr(e)
                    emit(Op.ARRAY_PUSH)
                }
            }
        }
    }

    fun propertyKey(key: Node, computed: Boolean) {
        if (computed) {
            expr(key)
            emit(Op.TO_PROPERTY_KEY)
            return
        }
        when (key) {
            is Identifier -> emit(Op.PUSH_CONST, keyConst(key.name))
            is StringLiteral -> emit(Op.PUSH_CONST, keyConst(key.value))
            is NumberLiteral -> emit(Op.PUSH_CONST, const(dev.mooner.neonjs.runtime.PK.fromDouble(key.value)))
            is BigIntLiteral -> emit(Op.PUSH_CONST, keyConst(key.value.toString()))
            else -> throw CompileError("bad key")
        }
    }

    fun staticKeyName(key: Node, computed: Boolean): String? {
        if (computed) return null
        return when (key) {
            is Identifier -> key.name
            is StringLiteral -> key.value
            is NumberLiteral -> dev.mooner.neonjs.runtime.NumberConv.toString(key.value)
            is BigIntLiteral -> key.value.toString()
            is PrivateIdentifier -> "#" + key.name
            else -> null
        }
    }

    private fun objectLiteral(n: ObjectLiteral) {
        literalKeys(n)?.let { keys ->
            // all `key: value` with distinct static keys: evaluate the values, then build the object in its final shape
            for (p in n.properties) {
                p as Property
                exprNamed(p.value, StaticName(staticKeyName(p.key, false)!!))
            }
            emit(Op.NEW_OBJECT_LITERAL, const(dev.mooner.neonjs.runtime.ObjectLiteralSite(keys)), keys.size)
            return
        }
        emit(Op.NEW_OBJECT)
        val needsHome = n.properties.any { it is Property && (it.method || it.kind != PropKind.INIT) }
        var homeReg = -1
        if (needsHome) {
            homeReg = allocReg()
            emit(Op.DUP)
            emit(Op.STORE_REG, homeReg)
        }
        for (p in n.properties) {
            if (p is SpreadElement) {
                expr(p.argument)
                emit(Op.COPY_DATA_PROPS)
                continue
            }
            p as Property
            val v = p.value
            if (p.kind == PropKind.INIT && !p.method && !p.computed && !p.shorthand && isProtoKey(p.key)) {
                expr(v)
                emit(Op.SET_PROTO)
                continue
            }
            val name = staticKeyName(p.key, p.computed)
            if (p.kind != PropKind.INIT || p.method) {
                propertyKey(p.key, p.computed)
                val fn = v as FunctionNode
                val cb = compileNested(fn, name ?: "")
                emit(Op.MAKE_METHOD, const(cb), homeReg)
                when (p.kind) {
                    PropKind.GET -> emit(Op.DEFINE_GETTER, 1)
                    PropKind.SET -> emit(Op.DEFINE_SETTER, 1)
                    else -> emit(Op.DEFINE_METHOD, 1)
                }
                continue
            }
            if (!p.computed && name != null) {
                exprNamed(v, StaticName(name))
                emit(Op.DEFINE_FIELD, keyConst(name))
            } else {
                propertyKey(p.key, true)
                if (isAnonymousFn(v)) {
                    if (v is ClassNode) {
                        emit(Op.DUP)
                        classExpr(v, DynamicName)
                    } else {
                        exprNamed(v, null)
                        emit(Op.SET_FUNCTION_NAME, -1)
                    }
                } else expr(v)
                emit(Op.DEFINE_FIELD_ELEM)
            }
        }
        if (homeReg >= 0) freeReg(homeReg)
    }

    /** The canonical keys of a literal eligible for NEW_OBJECT_LITERAL, else null. */
    private fun literalKeys(n: ObjectLiteral): Array<Any>? {
        val props = n.properties
        if (props.isEmpty() || props.size > dev.mooner.neonjs.runtime.PropertyMap.MAX_LAYOUT) return null
        val keys = ArrayList<Any>(props.size)
        for (p in props) {
            if (p !is Property || p.kind != PropKind.INIT || p.method || p.computed) return null
            if (!p.shorthand && isProtoKey(p.key)) return null
            val name = staticKeyName(p.key, false) ?: return null
            val key = dev.mooner.neonjs.runtime.PK.fromString(name)
            if (keys.contains(key)) return null
            keys.add(key)
        }
        return keys.toTypedArray()
    }

    private fun isProtoKey(k: Node): Boolean = (k is Identifier && k.name == "__proto__") || (k is StringLiteral && k.value == "__proto__")

    // ================================================================== functions

    fun function(fn: FunctionNode, name: FnName?) {
        val staticName = when {
            fn.id != null -> fn.id!!.name
            name is StaticName -> name.name
            else -> ""
        }
        val cb = compileNested(fn, staticName)
        emit(Op.MAKE_CLOSURE, const(cb))
    }

    // ================================================================== classes

    /** Compiles a class; [name]: DynamicName means the name is on top of the stack (consumed). */
    fun classExpr(cls: ClassNode, name: FnName?) {
        // all parts of a class are strict mode code, including what runs in the enclosing function: the heritage,
        // computed keys and decorators
        strictDepth++
        try {
            if (usesClassDef(cls)) classExprWithDef(cls, name) else plainClassExpr(cls, name)
        } finally {
            strictDepth--
        }
    }

    private fun plainClassExpr(cls: ClassNode, name: FnName?) {
        val cs = cls.scope as Scope
        val dynName = name is DynamicName && cls.id == null
        val className = cls.id?.name ?: (if (name is StaticName) name.name else "")
        var nameReg = -1
        if (dynName) {
            nameReg = allocReg()
            emit(Op.STORE_REG, nameReg)
        }
        val outerScope = scope
        enterScope(cs)
        // private names
        for (b in cs.bindings.values) {
            if (b.kind == BKind.PRIVATE) {
                emit(Op.NEW_PRIVATE_NAME, const(b.name))
                emitInitBinding(b)
            }
        }
        var flags = 0
        if (cls.superClass != null) {
            flags = flags or 1
            expr(cls.superClass)
        }
        if (dynName) {
            flags = flags or 2
            emit(Op.LOAD_REG, nameReg)
        }
        // constructor template
        val ctorFn = cls.constructor
        val ctorCb: CodeBlock = if (ctorFn != null) compileNested(ctorFn, className) else defaultConstructor(cls, className)
        if (ctorCb.sourceText() == null || true) {
            ctorCb.source = source
            ctorCb.srcStart = cls.srcStart
            ctorCb.srcEnd = cls.srcEnd
        }
        mark(cls)
        emit(Op.MAKE_CLASS, const(ctorCb), flags)     // proto ctor
        val rC = allocReg()
        val rP = allocReg()
        emit(Op.STORE_REG, rC)
        emit(Op.STORE_REG, rP)
        // static elements executed after definition: (kind, regs)
        class StaticEl(val isBlock: Boolean, val keyReg: Int, val fnReg: Int)
        val statics = ArrayList<StaticEl>()
        val staticPrivMethods = ArrayList<Triple<Int, Int, Int>>() // pnReg? -> (kind, pnRegIdx, fnReg)
        for (el in cls.body) {
            when (el) {
                is MethodDefinition -> {
                    if (el.kind == MethodKind.CONSTRUCTOR) continue
                    val homeReg = if (el.isStatic) rC else rP
                    val key = el.key
                    val fnName = staticKeyName(key, el.computed)
                    val cb = compileNested(el.value, fnName ?: "")
                    if (key is PrivateIdentifier) {
                        val kind = when (el.kind) { MethodKind.GET -> 1; MethodKind.SET -> 2; else -> 0 }
                        if (el.isStatic) {
                            val fnReg = allocReg()
                            emit(Op.MAKE_METHOD, const(cb), homeReg)
                            emit(Op.STORE_REG, fnReg)
                            val pnReg = allocReg()
                            privateNameLoad(key)
                            emit(Op.STORE_REG, pnReg)
                            staticPrivMethods.add(Triple(kind, pnReg, fnReg))
                        } else {
                            emit(Op.LOAD_REG, rC)
                            privateNameLoad(key)
                            emit(Op.MAKE_METHOD, const(cb), homeReg)
                            emit(Op.ADD_PRIVATE_METHOD, kind)
                        }
                        continue
                    }
                    emit(Op.LOAD_REG, homeReg)
                    propertyKey(key, el.computed)
                    emit(Op.MAKE_METHOD, const(cb), homeReg)
                    when (el.kind) {
                        MethodKind.GET -> emit(Op.DEFINE_GETTER, 0)
                        MethodKind.SET -> emit(Op.DEFINE_SETTER, 0)
                        else -> emit(Op.DEFINE_METHOD, 0)
                    }
                    emit(Op.POP)
                }
                is PropertyDefinition -> {
                    val key = el.key
                    val homeReg = if (el.isStatic) rC else rP
                    // key
                    val keyReg = allocReg()
                    if (key is PrivateIdentifier) privateNameLoad(key) else propertyKey(key, el.computed)
                    emit(Op.STORE_REG, keyReg)
                    val fnReg = allocReg()
                    val init = el.initializer
                    if (init != null) {
                        val cb = compileNested(init, staticKeyName(key, el.computed) ?: "", fieldKeyDynamic = el.computed)
                        emit(Op.MAKE_METHOD, const(cb), homeReg)
                    } else emit(Op.PUSH_UNDEF)
                    emit(Op.STORE_REG, fnReg)
                    if (el.isStatic) {
                        statics.add(StaticEl(false, keyReg, fnReg))
                    } else {
                        emit(Op.LOAD_REG, rC)
                        emit(Op.LOAD_REG, keyReg)
                        emit(Op.LOAD_REG, fnReg)
                        emit(Op.ADD_FIELD)
                        freeReg(keyReg)
                        freeReg(fnReg)
                    }
                }
                is StaticBlock -> {
                    val cb = compileNested(el.function!!, "")
                    val fnReg = allocReg()
                    emit(Op.MAKE_METHOD, const(cb), rC)
                    emit(Op.STORE_REG, fnReg)
                    statics.add(StaticEl(true, -1, fnReg))
                }
                else -> {}
            }
        }
        // inner class binding
        if (cls.id != null) {
            val b = cs.bindings[cls.id.name]!!
            emit(Op.LOAD_REG, rC)
            emitInitBinding(b)
        }
        for ((kind, pnReg, fnReg) in staticPrivMethods) {
            emit(Op.LOAD_REG, rC)
            emit(Op.LOAD_REG, pnReg)
            emit(Op.LOAD_REG, fnReg)
            emit(Op.STATIC_PRIVATE_METHOD, kind)
            freeReg(pnReg)
            freeReg(fnReg)
        }
        for (s in statics) {
            if (s.isBlock) {
                emit(Op.LOAD_REG, s.fnReg)
                emit(Op.LOAD_REG, rC)
                emit(Op.CALL, 0)
                emit(Op.POP)
            } else {
                emit(Op.LOAD_REG, rC)
                emit(Op.LOAD_REG, s.keyReg)
                emit(Op.LOAD_REG, s.fnReg)
                emit(Op.RUN_FIELD)
                freeReg(s.keyReg)
            }
            freeReg(s.fnReg)
        }
        emit(Op.LOAD_REG, rC)
        freeReg(rC)
        freeReg(rP)
        if (nameReg >= 0) freeReg(nameReg)
        exitScope(cs)
        scope = outerScope
    }

    abstract fun defaultConstructor(cls: ClassNode, name: String): CodeBlock

    /** Classes with decorators or `accessor` fields are built through a runtime class definition (vm/Decorators.kt). */
    private fun usesClassDef(cls: ClassNode): Boolean =
        cls.decorators.isNotEmpty() || cls.body.any {
            (it is MethodDefinition && it.decorators.isNotEmpty()) || (it is PropertyDefinition && (it.decorators.isNotEmpty() || it.isAccessor))
        }

    /** Pushes an array of evaluated decorators, or undefined when there are none. */
    private fun decoratorArray(decs: List<Node>) {
        if (decs.isEmpty()) {
            emit(Op.PUSH_UNDEF)
            return
        }
        emit(Op.NEW_ARRAY)
        for (d in decs) {
            expr(d)
            emit(Op.ARRAY_PUSH)
        }
    }

    private fun classExprWithDef(cls: ClassNode, name: FnName?) {
        val cs = cls.scope as Scope
        val dynName = name is DynamicName && cls.id == null
        val className = cls.id?.name ?: (if (name is StaticName) name.name else "")
        var nameReg = -1
        if (dynName) {
            nameReg = allocReg()
            emit(Op.STORE_REG, nameReg)
        }
        // class decorators are evaluated first, in the enclosing scope
        var classDecReg = -1
        if (cls.decorators.isNotEmpty()) {
            classDecReg = allocReg()
            decoratorArray(cls.decorators)
            emit(Op.STORE_REG, classDecReg)
        }
        val outerScope = scope
        enterScope(cs)
        for (b in cs.bindings.values) {
            if (b.kind == BKind.PRIVATE) {
                emit(Op.NEW_PRIVATE_NAME, const(b.name))
                emitInitBinding(b)
            }
        }
        var flags = 0
        if (cls.superClass != null) {
            flags = flags or 1
            expr(cls.superClass)
        }
        if (dynName) {
            flags = flags or 2
            emit(Op.LOAD_REG, nameReg)
        }
        val ctorFn = cls.constructor
        val ctorCb: CodeBlock = if (ctorFn != null) compileNested(ctorFn, className) else defaultConstructor(cls, className)
        ctorCb.source = source
        ctorCb.srcStart = cls.srcStart
        ctorCb.srcEnd = cls.srcEnd
        mark(cls)
        emit(Op.MAKE_CLASS, const(ctorCb), flags)     // proto ctor
        val rC = allocReg()
        val rP = allocReg()
        emit(Op.STORE_REG, rC)
        emit(Op.STORE_REG, rP)
        emit(Op.LOAD_REG, rC)
        emit(Op.LOAD_REG, rP)
        emit(Op.CLASS_DEF_NEW)
        val rDef = allocReg()
        emit(Op.STORE_REG, rDef)
        for (el in cls.body) {
            when (el) {
                is MethodDefinition -> {
                    if (el.kind == MethodKind.CONSTRUCTOR) continue
                    val homeReg = if (el.isStatic) rC else rP
                    emit(Op.LOAD_REG, rDef)
                    decoratorArray(el.decorators)
                    val key = el.key
                    if (key is PrivateIdentifier) privateNameLoad(key) else propertyKey(key, el.computed)
                    val cb = compileNested(el.value, staticKeyName(key, el.computed) ?: "")
                    emit(Op.MAKE_METHOD, const(cb), homeReg)
                    val kind = when (el.kind) { MethodKind.GET -> 1; MethodKind.SET -> 2; else -> 0 }
                    emit(Op.CLASS_ELEMENT, kind or (if (el.isStatic) 8 else 0))
                }
                is PropertyDefinition -> {
                    val key = el.key
                    val homeReg = if (el.isStatic) rC else rP
                    emit(Op.LOAD_REG, rDef)
                    decoratorArray(el.decorators)
                    if (key is PrivateIdentifier) privateNameLoad(key) else propertyKey(key, el.computed)
                    val init = el.initializer
                    if (init != null) {
                        val cb = compileNested(init, staticKeyName(key, el.computed) ?: "", fieldKeyDynamic = el.computed)
                        emit(Op.MAKE_METHOD, const(cb), homeReg)
                    } else emit(Op.PUSH_UNDEF)
                    emit(Op.CLASS_ELEMENT, (if (el.isAccessor) 4 else 3) or (if (el.isStatic) 8 else 0))
                }
                is StaticBlock -> {
                    emit(Op.LOAD_REG, rDef)
                    emit(Op.PUSH_UNDEF)
                    emit(Op.PUSH_UNDEF)
                    val cb = compileNested(el.function!!, "")
                    emit(Op.MAKE_METHOD, const(cb), rC)
                    emit(Op.CLASS_ELEMENT, 5 or 8)
                }
                else -> {}
            }
        }
        emit(Op.LOAD_REG, rDef)
        emit(Op.CLASS_FINISH)
        emit(Op.LOAD_REG, rDef)
        if (classDecReg >= 0) emit(Op.LOAD_REG, classDecReg) else emit(Op.PUSH_UNDEF)
        emit(Op.CLASS_DECORATE)
        val rFinal = allocReg()
        emit(Op.STORE_REG, rFinal)
        if (cls.id != null) {
            val b = cs.bindings[cls.id.name]!!
            emit(Op.LOAD_REG, rFinal)
            emitInitBinding(b)
        }
        emit(Op.LOAD_REG, rDef)
        emit(Op.CLASS_STATIC_INIT)
        emit(Op.LOAD_REG, rFinal)
        freeReg(rFinal)
        freeReg(rDef)
        freeReg(rC)
        freeReg(rP)
        if (classDecReg >= 0) freeReg(classDecReg)
        if (nameReg >= 0) freeReg(nameReg)
        exitScope(cs)
        scope = outerScope
    }

    // ================================================================== destructuring

    /** Binds the value on top of the stack (consumed) to [target]. */
    fun pattern(target: Node, mode: BindMode) {
        when (target) {
            is Identifier -> bindIdentifier(target, mode)
            is MemberExpression -> {
                // value on stack; evaluate reference afterwards (only used for simple member targets of rest/elements)
                memberAssignFromStack(target)
            }
            is AssignmentPattern -> {
                // value on stack; default if undefined
                val skip = newLabel()
                emit(Op.DUP)
                emitJump(Op.JUMP_IF_NOT_UNDEFINED, skip)
                emit(Op.POP)
                exprNamed(target.right, if (target.left is Identifier) StaticName((target.left as Identifier).name) else null)
                place(skip)
                pattern(target.left, mode)
            }
            is ObjectPattern -> objectPattern(target, mode)
            is ArrayPattern -> arrayPattern(target, mode)
            is CallExpression -> {
                emit(Op.POP)
                expr(target)
                emit(Op.POP)
                throwError(1, "Invalid destructuring assignment target")
            }
            else -> throw CompileError("bad pattern ${target::class.simpleName}")
        }
    }

    fun bindIdentifier(id: Identifier, mode: BindMode) {
        if (mode == BindMode.INIT) emitInitDeclared(id) else emitStoreRef(id.ref, id.name)
    }

    /** value on stack -> assign to member target (reference evaluated after value; used when order is unobservable). */
    private fun memberAssignFromStack(t: MemberExpression) {
        if (t.obj is Super) {
            val tmp = allocReg()
            emit(Op.STORE_REG, tmp)
            superRefPrefix(t)
            emit(Op.LOAD_REG, tmp)
            freeReg(tmp)
            emitPutSuper(false)
            return
        }
        val tmp = allocReg()
        emit(Op.STORE_REG, tmp)
        expr(t.obj)
        if (t.property is PrivateIdentifier) {
            privateNameLoad(t.property)
            emit(Op.LOAD_REG, tmp)
            emit(Op.PUT_PRIVATE)
        } else if (t.computed) {
            expr(t.property)
            emit(Op.LOAD_REG, tmp)
            emit(Op.PUT_ELEM)
        } else {
            emit(Op.LOAD_REG, tmp)
            emit(Op.PUT_PROP, keyConst((t.property as Identifier).name))
        }
        emit(Op.POP)
        freeReg(tmp)
    }

    /**
     * Target evaluation-first protocol for assignment patterns: evaluates the reference part of a simple target and
     * returns a lambda that stores the value on top of the stack.
     */
    private fun prepareTarget(target: Node, mode: BindMode): (() -> Unit) {
        val t = if (target is AssignmentPattern) target.left else target
        if (t is Identifier && t.ref is DynamicRef && (mode == BindMode.ASSIGN || mode == BindMode.INIT)) {
            // ResolveBinding happens before the value is read (observable through `with` objects)
            emit(Op.RESOLVE_NAME, const(t.name))
            return { emit(Op.PUT_REF); emit(Op.POP) }
        }
        if (t is MemberExpression && mode == BindMode.ASSIGN) {
            if (t.obj is Super) {
                superRefPrefix(t)       // this key base
                return {
                    emitPutSuper(false)
                }
            }
            expr(t.obj)
            if (t.property is PrivateIdentifier) {
                privateNameLoad(t.property)
                return { emit(Op.PUT_PRIVATE); emit(Op.POP) }
            } else if (t.computed) {
                expr(t.property)
                return { emit(Op.PUT_ELEM); emit(Op.POP) }
            } else {
                val k = keyConst((t.property as Identifier).name)
                return { emit(Op.PUT_PROP, k); emit(Op.POP) }
            }
        }
        return { pattern(t, mode) }
    }

    private fun applyDefault(target: Node) {
        if (target is AssignmentPattern) {
            val skip = newLabel()
            emit(Op.DUP)
            emitJump(Op.JUMP_IF_NOT_UNDEFINED, skip)
            emit(Op.POP)
            exprNamed(target.right, if (target.left is Identifier) StaticName((target.left as Identifier).name) else null)
            place(skip)
        }
    }

    private fun objectPattern(p: ObjectPattern, mode: BindMode) {
        emit(Op.REQUIRE_COERCIBLE)
        val src = allocReg()
        emit(Op.STORE_REG, src)
        val hasRest = p.properties.any { it is RestElement }
        val keyRegs = ArrayList<Int>()
        for (prop in p.properties) {
            if (prop is RestElement) {
                val store = prepareTarget(prop.argument, mode)
                emit(Op.NEW_OBJECT)
                emit(Op.LOAD_REG, src)
                for (kr in keyRegs) emit(Op.LOAD_REG, kr)
                emit(Op.COPY_DATA_PROPS_EXCL, keyRegs.size)
                store()
                continue
            }
            prop as Property
            // key evaluated first, then target reference, then GetV
            var keyReg = -1
            if (prop.computed || hasRest) {
                propertyKey(prop.key, prop.computed)
                keyReg = allocReg()
                emit(Op.STORE_REG, keyReg)
                if (hasRest) keyRegs.add(keyReg)
            }
            val store = prepareTarget(prop.value, mode)
            emit(Op.LOAD_REG, src)
            if (keyReg >= 0) {
                emit(Op.LOAD_REG, keyReg)
                emit(Op.GET_ELEM)
            } else {
                val name = staticKeyName(prop.key, false)!!
                emit(Op.GET_PROP, keyConst(name))
            }
            applyDefault(prop.value)
            store()
            if (keyReg >= 0 && !hasRest) freeReg(keyReg)
        }
        for (kr in keyRegs) freeReg(kr)
        freeReg(src)
    }

    private fun arrayPattern(p: ArrayPattern, mode: BindMode) {
        emit(Op.GET_ITERATOR)
        val it = allocReg()
        emit(Op.STORE_REG, it)
        val start = pc
        val d = currentDepth
        val savedCtl = ctl
        ctl = IterCtl(ctl, it, false)
        for (e in p.elements) {
            if (e == null) {
                emit(Op.ITER_STEP_U, it)
                emit(Op.POP)
                continue
            }
            if (e is RestElement) {
                val store = prepareTarget(e.argument, mode)
                emit(Op.ITER_REST, it)
                store()
                continue
            }
            val store = prepareTarget(e, mode)
            emit(Op.ITER_STEP_U, it)
            applyDefault(e)
            store()
        }
        val end = pc
        ctl = savedCtl
        // normal completion: close if not done
        emit(Op.ITER_CLOSE, it)
        val after = newLabel()
        emitJump(Op.JUMP, after)
        val h = pc
        resetDepth(d + 1)
        emit(Op.ITER_CLOSE_THROW, it)
        emit(Op.THROW)
        addHandler(start, end, h, d)
        placeAfterJump(after)
        resetDepth(d)
        freeReg(it)
    }

    // ================================================================== generators

    private fun yieldExpr(n: YieldExpression) {
        if (n.delegate) {
            yieldStar(n)
            return
        }
        if (n.argument != null) expr(n.argument) else emit(Op.PUSH_UNDEF)
        if (isAsyncGenerator) {
            emit(Op.AWAIT)
            emit(Op.YIELD)
            val ret = newLabel()
            val cont = newLabel()
            emitJump(Op.RESUME_DISPATCH, ret)
            emitJump(Op.JUMP, cont)
            placeAfterJump(ret)
            emit(Op.AWAIT)
            emitReturnValue()
            placeAfterJump(cont)
        } else {
            emit(Op.YIELD)
            val ret = newLabel()
            val cont = newLabel()
            emitJump(Op.RESUME_DISPATCH, ret)
            emitJump(Op.JUMP, cont)
            placeAfterJump(ret)
            emitReturnValue()
            placeAfterJump(cont)
        }
    }

    /** Patches the jump target (last operand) of the previous instruction; [depthAtTarget] is the stack depth there. */
    fun patchJumpOperand(l: Label, depthAtTarget: Int) {
        val at = pc - 1
        if (l.pos >= 0) code[at] = l.pos else l.fixups.add(at)
        if (l.depth < 0) l.depth = depthAtTarget
    }

    private fun yieldStar(n: YieldExpression) {
        expr(n.argument!!)
        val async = isAsyncGenerator
        emit(if (async) Op.GET_ASYNC_ITERATOR else Op.GET_ITERATOR)
        val it = allocReg()
        emit(Op.STORE_REG, it)
        val mode = allocReg()
        pushInt(0)
        emit(Op.STORE_REG, mode)
        emit(Op.PUSH_UNDEF)
        val loop = newLabel()
        val done = newLabel()
        place(loop)
        if (!async) {
            emit(Op.YSTAR, it, mode, 0)
            patchJumpOperand(done, currentDepth)
            emit(Op.YIELD_RAW)
            emit(Op.RESUME_MODE)
            emit(Op.STORE_REG, mode)
            emitJump(Op.JUMP, loop)
        } else {
            emit(Op.YSTAR_ASYNC_CALL, it, mode, 0)
            patchJumpOperand(done, currentDepth)
            emitAwait()
            emit(Op.YSTAR_ASYNC_RESULT, it, mode, 0)
            patchJumpOperand(done, currentDepth)
            emit(Op.YIELD)
            emit(Op.RESUME_MODE)
            emit(Op.STORE_REG, mode)
            emit(Op.LOAD_REG, mode)
            pushInt(2)
            emit(Op.SEQ)
            emitJump(Op.JUMP_IF_FALSE, loop)
            emit(Op.AWAIT_CATCH, mode)
            emitJump(Op.JUMP, loop)
        }
        placeAfterJump(done)                 // value
        if (async) {
            val notMissing = newLabel()
            emit(Op.LOAD_REG, mode)
            pushInt(3)
            emit(Op.SEQ)
            emitJump(Op.JUMP_IF_FALSE, notMissing)
            emit(Op.POP)
            val skip = newLabel()
            emit(Op.ASYNC_ITER_CLOSE, it, 0, 0)
            patchJumpOperand(skip, currentDepth - 1)
            emitAwait()
            emit(Op.CHECK_OBJECT, const("Iterator result is not an object"))
            emit(Op.POP)
            place(skip)
            throwError(0, "The iterator does not provide a 'throw' method")
            emit(Op.PUSH_UNDEF)
            place(notMissing)
        }
        val normal = newLabel()
        emit(Op.LOAD_REG, mode)
        pushInt(2)
        emit(Op.SEQ)
        emitJump(Op.JUMP_IF_FALSE, normal)
        // return completion: in async generators the value is awaited (missing return method, or done result)
        if (async) emitAwait()
        emitReturnValue()
        placeAfterJump(normal)
        freeReg(it)
        freeReg(mode)
    }
}
