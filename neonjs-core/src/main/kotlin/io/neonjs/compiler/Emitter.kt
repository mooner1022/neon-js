package io.neonjs.compiler

import io.neonjs.parser.*

internal class Emitter(fi: FnInfo, source: Source, analyzer: ScopeAnalyzer) :
    ExprEmitter(fi, source, analyzer) {

    private var finallyKinds = 2

    override fun compileNested(fn: FunctionNode, name: String, fieldKeyDynamic: Boolean): CodeBlock {
        val sub = Emitter((fn.scope as Scope).fn, source, analyzer)
        return sub.compileFunction(fn, name, fieldKeyDynamic)
    }

    override fun defaultConstructor(cls: ClassNode, name: String): CodeBlock {
        val cb = CodeBlock(name, if (cls.superClass != null) FunctionKind.DERIVED_CONSTRUCTOR else FunctionKind.CLASS_CONSTRUCTOR)
        cb.flags = CodeBlock.STRICT or CodeBlock.CLASS_CTOR or CodeBlock.DEFAULT_CTOR or CodeBlock.CONSTRUCTOR or CodeBlock.METHOD or
                (if (cls.superClass != null) CodeBlock.DERIVED else 0)
        cb.code = intArrayOf(Op.PUSH_UNDEF, Op.RETURN)
        cb.maxStack = 1
        cb.source = source
        cb.srcStart = cls.srcStart
        cb.srcEnd = cls.srcEnd
        return cb
    }

    // ================================================================== finishing

    private fun finishBlock(cb: CodeBlock) {
        cb.code = code.copyOf(pc)
        cb.constants = constants.toTypedArray()
        val hs = IntArray(handlers.size * 4)
        // inner (later-added for nested?) handlers: sort by range size so innermost first
        val sorted = handlers.sortedWith(compareBy({ it[1] - it[0] }, { it[0] }))
        sorted.forEachIndexed { i, h -> System.arraycopy(h, 0, hs, i * 4, 4) }
        cb.handlers = hs
        cb.numRegs = totalRegs
        cb.maxStack = maxDepth + 2
        cb.lineTable = lineTable.toIntArray()
        cb.source = cb.source ?: source
        cb.completionReg = completionReg
        // only regions with an instruction that behaves differently in strict code matter (and keep the block from
        // being JIT-compiled): a plain `class C {}` in sloppy code needs none
        cb.strictPcs = strictPcs?.takeIf { bits -> bits.stream().anyMatch { isStrictSensitive(code[it]) } }
    }

    /** Instructions whose semantics depend on the strictness of the code (the `strict` argument at run time). */
    private fun isStrictSensitive(op: Int) = when (op) {
        Op.PUT_PROP, Op.PUT_ELEM, Op.STORE_GLOBAL, Op.LOAD_NAME, Op.LOAD_NAME_TYPEOF, Op.LOAD_NAME_CALL, Op.STORE_NAME,
        Op.DELETE_PROP, Op.DELETE_ELEM, Op.PUT_SUPER, Op.RESOLVE_NAME, Op.GET_REF, Op.PUT_REF -> true
        else -> false
    }

    // ================================================================== programs

    fun compileProgram(prog: Program, mode: CodeMode): CodeBlock {
        val kind = FunctionKind.NORMAL
        val cb = CodeBlock(if (mode == CodeMode.SCRIPT) "<script>" else "<eval>", kind)
        cb.flags = CodeBlock.TOP_LEVEL or (if (prog.strict) CodeBlock.STRICT else 0)
        cb.source = source
        cb.srcStart = 0
        cb.srcEnd = prog.end
        initRegs(fi.numRegs)
        val root = prog.scope as Scope
        scope = root
        completionReg = allocReg()
        emit(Op.PUSH_UNDEF)
        emit(Op.STORE_REG, completionReg)
        when (mode) {
            CodeMode.SCRIPT -> {
                declareGlobals(prog, false)
                enterTopScope(root)
            }
            CodeMode.EVAL_DIRECT, CodeMode.EVAL_INDIRECT -> {
                if (!prog.strict) {
                    enterTopScope(root)
                    declareGlobals(prog, true)
                } else {
                    enterTopScope(root)
                    hoistFunctions(prog.body, root)
                }
            }
            else -> {}
        }
        for (st in prog.body) stmt(st)
        emit(Op.LOAD_REG, completionReg)
        emit(Op.RETURN)
        finishBlock(cb)
        return cb
    }

    /** Module function hoisting, run during InitializeEnvironment (frame env = module env). */
    fun compileModuleInit(prog: Program): CodeBlock {
        val cb = CodeBlock("<module-init>", FunctionKind.NORMAL)
        cb.flags = CodeBlock.TOP_LEVEL or CodeBlock.STRICT
        cb.source = source
        initRegs(fi.numRegs)
        scope = prog.scope as Scope
        hoistFunctions(prog.body, scope)
        emit(Op.PUSH_UNDEF)
        emit(Op.RETURN)
        finishBlock(cb)
        return cb
    }

    /** Module body; async when the module uses top-level await. */
    fun compileModuleBody(prog: Program, async: Boolean): CodeBlock {
        val cb = CodeBlock("<module>", FunctionKind.NORMAL)
        cb.flags = CodeBlock.TOP_LEVEL or CodeBlock.STRICT or (if (async) CodeBlock.ASYNC else 0)
        cb.source = source
        cb.srcStart = 0
        cb.srcEnd = prog.end
        initRegs(fi.numRegs)
        scope = prog.scope as Scope
        statements(prog.body)
        emit(Op.PUSH_UNDEF)
        emit(Op.RETURN)
        finishBlock(cb)
        return cb
    }

    private fun enterTopScope(root: Scope) {
        if (root.needsEnv) emit(Op.PUSH_SCOPE, const(root.info!!))
        initTdzRegisters(root)
        if (root.needsEnv) ctl = ScopeCtl(ctl, root)
    }

    private fun declareGlobals(prog: Program, isEval: Boolean) {
        val an = analyzer
        // function declarations: last declaration of a name wins
        val fnByName = LinkedHashMap<String, FunctionDeclaration>()
        for (f in an.globalFunctions) fnByName.remove(f.function.id!!.name).also { fnByName[f.function.id!!.name] = f }
        val templates = ArrayList<CodeBlock>()
        val names = ArrayList<String>()
        for ((name, f) in fnByName) {
            val cb = compileNested(f.function, name)
            emit(Op.MAKE_CLOSURE, const(cb))
            templates.add(cb)
            names.add(name)
        }
        val lexNames = an.globalLexNames.keys.toTypedArray()
        val lexConst = BooleanArray(lexNames.size) { an.globalLexNames[lexNames[it]] == true }
        val info = DeclInfo(
            an.globalVarNames.filter { it !in fnByName.keys }.toTypedArray(),
            names.toTypedArray(),
            if (isEval) emptyArray() else lexNames, if (isEval) BooleanArray(0) else lexConst,
            an.annexBGlobalNames.toTypedArray(), prog.strict,
        )
        emit(if (isEval) Op.DECLARE_EVAL else Op.DECLARE_GLOBALS, const(info), templates.size)
    }

    /** Hoists function declarations of a statement list into their (already declared) bindings. */
    private fun hoistFunctions(body: List<Node>, s: Scope) {
        val seen = HashSet<String>()
        val decls = ArrayList<FunctionDeclaration>()
        for (st0 in body) {
            var st = st0
            while (st is LabeledStatement) st = st.body
            if (st is ExportNamedDeclaration) st = st.declaration ?: continue
            if (st is ExportDefaultDeclaration) st = st.declaration
            if (st is FunctionDeclaration) decls.add(st)
        }
        // last declaration wins: initialize in order (later overwrite earlier)
        for (d in decls) {
            val name = d.function.id?.name ?: "*default*"
            val b = s.bindings[name] ?: fi.varScope.bindings[name] ?: continue
            val cb = compileNested(d.function, if (d.function.id == null) "default" else name)
            emit(Op.MAKE_CLOSURE, const(cb))
            emitInitBinding(b)
            seen.add(name)
        }
    }

    // ================================================================== functions

    fun compileFunction(fn: FunctionNode, name: String, fieldKeyDynamic: Boolean): CodeBlock {
        val f = fi
        val cb = CodeBlock(name, fn.kind)
        var flags = 0
        if (fn.strict) flags = flags or CodeBlock.STRICT
        if (fn.kind == FunctionKind.ARROW) flags = flags or CodeBlock.ARROW
        if (fn.isGenerator) flags = flags or CodeBlock.GENERATOR
        if (fn.isAsync) flags = flags or CodeBlock.ASYNC
        when (fn.kind) {
            FunctionKind.CLASS_CONSTRUCTOR -> flags = flags or CodeBlock.CLASS_CTOR or CodeBlock.CONSTRUCTOR or CodeBlock.METHOD
            FunctionKind.DERIVED_CONSTRUCTOR -> flags = flags or CodeBlock.CLASS_CTOR or CodeBlock.DERIVED or CodeBlock.CONSTRUCTOR or CodeBlock.METHOD
            FunctionKind.METHOD, FunctionKind.GETTER, FunctionKind.SETTER, FunctionKind.CLASS_FIELD_INIT, FunctionKind.STATIC_BLOCK -> flags = flags or CodeBlock.METHOD
            FunctionKind.NORMAL -> if (!fn.isAsync && !fn.isGenerator) flags = flags or CodeBlock.CONSTRUCTOR
            else -> {}
        }
        if (f.usesThis) flags = flags or CodeBlock.USES_THIS
        if (f.hasDirectEval) flags = flags or CodeBlock.HAS_EVAL
        cb.flags = flags
        if (f.hasDirectEval) computeEvalContext(cb)
        cb.source = source
        cb.srcStart = fn.srcStart
        cb.srcEnd = fn.srcEnd
        cb.formalCount = fn.params.size
        var len = 0
        for (p in fn.params) {
            if (p is AssignmentPattern || p is RestElement) break
            len++
        }
        cb.length = len
        initRegs(f.numRegs)
        val outer = (f.calleeScope ?: f.scope).parent ?: throw CompileError("function without outer scope")
        scope = outer
        mark(fn)
        // callee scope
        val cs = f.calleeScope
        if (cs != null) {
            enterScope(cs)
            val b = cs.bindings.values.first()
            if (b.inEnv) {
                emit(Op.LOAD_FUNCTION)
                emit(Op.STORE_ENV, 0, b.slot)
            }
        }
        f.paramEvalScope?.let { enterScope(it) }
        val fs = f.scope
        enterScope(fs)
        // pseudo bindings
        f.thisBinding?.let { b ->
            if (!b.tdz && b.inEnv) {
                emit(Op.LOAD_THIS)
                emitInitBinding(b)
            }
        }
        f.newTargetBinding?.let { b -> if (b.inEnv) { emit(Op.LOAD_NEW_TARGET); emitInitBinding(b) } }
        f.homeBinding?.let { b -> if (b.inEnv) { emit(Op.LOAD_HOME); emitInitBinding(b) } }
        f.fnBinding?.let { b -> if (b.inEnv) { emit(Op.LOAD_FUNCTION); emitInitBinding(b) } }
        // arguments object
        val ab = f.argumentsBinding
        if (ab != null && f.usesArguments) {
            if (f.mappedArguments) {
                val slots = IntArray(fn.params.size) { i -> ((fn.params[i] as Identifier).let { id -> fs.bindings[id.name]!!.slot }) }
                cb.mappedSlots = slots
                emit(Op.CREATE_ARGUMENTS, 1)
            } else emit(Op.CREATE_ARGUMENTS, 0)
            emitInitBinding(ab)
        }
        // parameters
        val simple = fn.params.all { it is Identifier }
        if (simple && !f.hasParamExpressions) {
            val regs = IntArray(fn.params.size) { -1 }
            var anyReg = false
            for ((i, p) in fn.params.withIndex()) {
                val b = fs.bindings[(p as Identifier).name]!!
                if (b.kind != BKind.PARAM) continue
                if (b.inEnv) {
                    // last duplicate wins: emit in order
                    emit(Op.LOAD_ARG, i)
                    emitInitBinding(b)
                } else {
                    regs[i] = b.reg
                    anyReg = true
                }
            }
            if (anyReg) {
                // duplicates: only the last occurrence of a register receives the argument
                val seenRegs = HashSet<Int>()
                for (i in regs.indices.reversed()) {
                    if (regs[i] >= 0 && !seenRegs.add(regs[i])) regs[i] = -1
                }
                cb.paramRegs = regs
            }
        } else {
            for ((i, p) in fn.params.withIndex()) {
                if (p is RestElement) {
                    emit(Op.CREATE_REST, i)
                    pattern(p.argument, BindMode.INIT)
                } else {
                    emit(Op.LOAD_ARG, i)
                    pattern(p, BindMode.INIT)
                }
            }
        }
        // body var scope (separate when parameters have expressions)
        val vs = f.varScope
        if (vs !== fs) {
            enterScope(vs)
            for (b in vs.bindings.values) {
                if (b.kind == BKind.VAR) {
                    val pb = fs.bindings[b.name]
                    if (pb != null && (pb.kind == BKind.PARAM || pb.kind == BKind.ARGUMENTS || pb.isArgumentsObject)) {
                        emitLoadBinding(pb, false)
                        emitInitBinding(b)
                    }
                }
            }
        }
        val body = fn.body
        if (body is BlockStatement) {
            hoistFunctions(body.body, vs)
            if (fn.isGenerator) emit(Op.GENERATOR_INIT)
            statements(body.body)
            implicitReturn(fn)
        } else {
            if (fn.isGenerator) emit(Op.GENERATOR_INIT)
            // expression body (arrow or class field initializer)
            if (fn.kind == FunctionKind.CLASS_FIELD_INIT) {
                if (fieldKeyDynamic && isAnonymousFn(body)) {
                    exprNamed(body, null)
                    emit(Op.LOAD_ARG, 0)
                    emit(Op.SWAP)
                    emit(Op.SET_FUNCTION_NAME, -1)
                    emit(Op.SWAP)
                    emit(Op.POP)
                } else if (isAnonymousFn(body)) {
                    exprNamed(body, StaticName(name))
                } else expr(body)
            } else exprReturned(body)
            emit(Op.RETURN)
        }
        finishBlock(cb)
        return cb
    }

    private fun computeEvalContext(cb: CodeBlock) {
        var g: FnInfo? = fi
        while (g != null && g.isArrow) g = g.parent
        var ctx = 0
        if (g != null && !g.isTopLevel) {
            ctx = ctx or CodeBlock.CTX_NEW_TARGET
            when (g.kind) {
                FunctionKind.METHOD, FunctionKind.GETTER, FunctionKind.SETTER, FunctionKind.CLASS_CONSTRUCTOR,
                FunctionKind.STATIC_BLOCK -> ctx = ctx or CodeBlock.CTX_SUPER_PROP
                FunctionKind.DERIVED_CONSTRUCTOR -> ctx = ctx or CodeBlock.CTX_SUPER_PROP or CodeBlock.CTX_SUPER_CALL
                FunctionKind.CLASS_FIELD_INIT -> ctx = ctx or CodeBlock.CTX_SUPER_PROP or CodeBlock.CTX_FIELD_INIT
                else -> {}
            }
        }
        cb.evalCtx = ctx
        val names = HashSet<String>()
        var s: Scope? = fi.scope
        while (s != null) {
            for (b in s.bindings.values) if (b.kind == BKind.PRIVATE) names.add(b.name.substring(1))
            s = s.parent
        }
        if (names.isNotEmpty()) cb.privateNames = names
    }

    private fun implicitReturn(fn: FunctionNode) {
        if (fn.kind == FunctionKind.DERIVED_CONSTRUCTOR) {
            emit(Op.PUSH_UNDEF)
            emitReturnValue()
            return
        }
        emit(Op.PUSH_UNDEF)
        emit(Op.RETURN)
    }

    // ================================================================== control flow

    override fun emitReturnValue() {
        var c = ctl
        while (c != null) {
            when (c) {
                is FinallyCtl -> {
                    emit(Op.STORE_REG, c.valueReg)
                    val k = finallyKinds++
                    c.pending.add(PendingJump(k, null, null, false))
                    pushInt(k)
                    emit(Op.STORE_REG, c.kindReg)
                    emitJump(Op.JUMP, c.entry)
                    return
                }
                is IterCtl -> {
                    val tmp = allocReg()
                    emit(Op.STORE_REG, tmp)
                    emitIterClose(c)
                    emit(Op.LOAD_REG, tmp)
                    freeReg(tmp)
                }
                else -> {}
            }
            c = c.parent
        }
        if (fi.kind == FunctionKind.DERIVED_CONSTRUCTOR) {
            val tb = fi.thisBinding!!
            emitLoadBinding(tb, false)
            emit(Op.DERIVED_RETURN)
        }
        emit(Op.RETURN)
    }

    private fun emitIterClose(c: IterCtl) {
        if (c.isAsync) {
            val skip = newLabel()
            emit(Op.ASYNC_ITER_CLOSE, c.iterReg, 0, 0)
            fixAsyncCloseJump(skip)
            emitAwait()
            emit(Op.CHECK_OBJECT, const("Iterator result is not an object"))
            emit(Op.POP)
            place(skip)
        } else emit(Op.ITER_CLOSE, c.iterReg)
    }

    /** Patches the jump operand of the just-emitted ASYNC_ITER_CLOSE (operand 2) to [l]. */
    private fun fixAsyncCloseJump(l: Label) {
        val at = pc - 1
        if (l.pos >= 0) code[at] = l.pos else l.fixups.add(at)
        if (l.depth < 0) l.depth = currentDepth - 1
    }

    /** Emits a break/continue jump through the control stack. */
    private fun emitJumpTo(target: Ctl, isContinue: Boolean) {
        var c = ctl
        while (c != null && c !== target) {
            when (c) {
                is ScopeCtl -> emit(Op.POP_SCOPE)
                is IterCtl -> if (!(isContinue && c.parent === target)) emitIterClose(c)
                is FinallyCtl -> {
                    val k = finallyKinds++
                    c.pending.add(PendingJump(k, target, null, isContinue))
                    pushInt(k)
                    emit(Op.STORE_REG, c.kindReg)
                    emitJump(Op.JUMP, c.entry)
                    return
                }
                else -> {}
            }
            c = c.parent
        }
        if (c == null) throw CompileError("jump target not found")
        val l = if (isContinue) (target as LoopCtl).continueLabel!! else when (target) {
            is LoopCtl -> target.breakLabel
            is LabelCtl -> target.breakLabel
            else -> throw CompileError("bad target")
        }
        emitJump(Op.JUMP, l)
    }

    private fun findBreakTarget(label: String?): Ctl {
        var c = ctl
        while (c != null) {
            if (label == null) {
                if (c is LoopCtl) return c
            } else {
                if (c is LoopCtl && label in c.labels) return c
                if (c is LabelCtl && label in c.labels) return c
            }
            c = c.parent
        }
        throw CompileError("break target not found: $label")
    }

    private fun findContinueTarget(label: String?): Ctl {
        var c = ctl
        while (c != null) {
            if (c is LoopCtl && !c.isSwitch && (label == null || label in c.labels)) return c
            c = c.parent
        }
        throw CompileError("continue target not found: $label")
    }

    // ================================================================== statements

    private var pendingLabels: List<String> = emptyList()

    private fun takeLabels(): List<String> {
        val l = pendingLabels
        pendingLabels = emptyList()
        return l
    }

    private fun setCompletionUndefined() {
        if (completionReg >= 0) {
            emit(Op.PUSH_UNDEF)
            emit(Op.STORE_REG, completionReg)
        }
    }

    fun stmt(n: Node) {
        if (n !is LabeledStatement && n !is ForStatement && n !is ForInStatement && n !is ForOfStatement &&
            n !is WhileStatement && n !is DoWhileStatement && pendingLabels.isNotEmpty()) {
            // labeled non-loop statement
            val labels = takeLabels()
            val brk = newLabel()
            ctl = LabelCtl(ctl, labels, brk)
            stmt(n)
            ctl = ctl!!.parent
            place(brk)
            return
        }
        when (n) {
            is ExpressionStatement -> {
                mark(n)
                expr(n.expression)
                if (completionReg >= 0) emit(Op.STORE_REG, completionReg) else emit(Op.POP)
            }
            is VariableDeclaration -> varDecl(n)
            is FunctionDeclaration -> {
                // hoisted; Annex B copy for block-level functions
                val s = scope
                val name = n.function.id!!.name
                val b = s.bindings[name]
                if (b != null && b.kind == BKind.BLOCK_FUNCTION) {
                    val av = b.annexBVar
                    if (av != null) {
                        emitLoadBinding(b, false)
                        if (av is Binding) {
                            // var binding may be in an outer scope of the same function
                            emitInitBinding(av)
                        } else {
                            emit(Op.STORE_NAME_VAR, const(av as String))
                        }
                    }
                }
            }
            is ClassDeclaration -> {
                mark(n)
                classExpr(n.cls, null)
                val id = n.cls.id!!
                val ref = id.ref
                if (ref == null) {
                    // resolve declaration binding in the current scope
                    val b = scope.bindings[id.name]
                    if (b != null) emitInitBinding(b) else emit(Op.INIT_GLOBAL_LEX, const(id.name))
                } else emitInitBindingFor(id)
            }
            is BlockStatement -> block(n)
            is EmptyStatement -> {}
            is DebuggerStatement -> emit(Op.DEBUGGER)
            is IfStatement -> {
                mark(n)
                setCompletionUndefined()
                val elseL = newLabel()
                condJump(n.test, false, elseL)
                subStmt(n.consequent)
                if (n.alternate != null) {
                    val end = newLabel()
                    emitJump(Op.JUMP, end)
                    placeAfterJump(elseL)
                    subStmt(n.alternate)
                    place(end)
                } else place(elseL)
            }
            is ReturnStatement -> {
                mark(n)
                if (n.argument != null) {
                    exprReturned(n.argument)
                    if (isAsyncGenerator) emitAwait()
                } else emit(Op.PUSH_UNDEF)
                emitReturnValue()
            }
            is ThrowStatement -> {
                mark(n)
                expr(n.argument)
                emit(Op.THROW)
            }
            is BreakStatement -> emitJumpTo(findBreakTarget(n.label), false)
            is ContinueStatement -> emitJumpTo(findContinueTarget(n.label), true)
            is LabeledStatement -> {
                pendingLabels = pendingLabels + n.label
                val body = n.body
                if (body is FunctionDeclaration) {
                    pendingLabels = emptyList()
                    stmt(body)
                    return
                }
                stmt(body)
                pendingLabels = emptyList()
            }
            is WhileStatement -> whileStmt(n)
            is DoWhileStatement -> doWhile(n)
            is ForStatement -> forStmt(n)
            is ForInStatement -> forIn(n)
            is ForOfStatement -> forOf(n)
            is SwitchStatement -> switchStmt(n)
            is TryStatement -> tryStmt(n)
            is WithStatement -> {
                mark(n)
                setCompletionUndefined()
                expr(n.obj)
                emit(Op.TO_OBJECT)
                emit(Op.PUSH_WITH)
                val ws = n.scope as Scope
                scope = ws
                ctl = ScopeCtl(ctl, ws)
                subStmt(n.body)
                ctl = ctl!!.parent
                emit(Op.POP_SCOPE)
                scope = ws.parent!!
            }
            is ImportDeclaration, is ExportAllDeclaration -> {}
            is ExportNamedDeclaration -> if (n.declaration != null) stmt(n.declaration)
            is ExportDefaultDeclaration -> {
                when (val d = n.declaration) {
                    is FunctionDeclaration -> {}
                    is ClassDeclaration -> {
                        classExpr(d.cls, StaticName("default"))
                        val name = d.cls.id?.name ?: "*default*"
                        val b = scope.bindings[name] ?: throw CompileError("default export binding")
                        emitInitBinding(b)
                    }
                    else -> {
                        exprNamed(d, StaticName("default"))
                        val b = scope.bindings["*default*"] ?: throw CompileError("default export binding")
                        emitInitBinding(b)
                    }
                }
            }
            else -> throw CompileError("unsupported statement ${n::class.simpleName}")
        }
    }

    private fun emitInitBindingFor(id: Identifier) {
        when (val ref = id.ref) {
            is LocalRef -> emitInitBinding(ref.binding)
            is DynamicRef -> emit(Op.INIT_NAME, const(id.name))
            else -> emit(Op.INIT_GLOBAL_LEX, const(id.name))
        }
    }

    /** Statement in single-statement position (may have an Annex B function scope). */
    private fun subStmt(n: Node) {
        val s = n.scope
        if ((n is FunctionDeclaration || (n is LabeledStatement)) && s is Scope && s.kind == ScopeKind.BLOCK) {
            enterScope(s)
            var inner: Node = n
            while (inner is LabeledStatement) inner = inner.body
            hoistBlockFunctions(listOf(inner), s)
            stmt(inner)
            exitScope(s)
            return
        }
        stmt(n)
    }

    private fun block(n: BlockStatement) {
        val s = n.scope as Scope?
        if (s == null || s.kind != ScopeKind.BLOCK) {
            for (st in n.body) stmt(st)
            return
        }
        enterScope(s)
        hoistBlockFunctions(n.body, s)
        statements(n.body)
        exitScope(s)
    }

    /** Emits a statement list, wrapping it in a disposal region when it declares `using` / `await using`. */
    private fun statements(list: List<Node>) {
        val u = usingKind(list)
        if (u == 0) {
            for (st in list) stmt(st)
        } else withDisposal(u == 2) { for (st in list) stmt(st) }
    }

    /** 0: no `using` declarations directly in [list]; 1: only `using`; 2: some `await using`. */
    private fun usingKind(list: List<Node>): Int {
        var r = 0
        for (st in list) {
            if (st is VariableDeclaration) {
                if (st.kind == VarKind.AWAIT_USING) return 2
                if (st.kind == VarKind.USING) r = 1
            }
        }
        return r
    }

    /** Capability registers of the enclosing disposal regions (innermost last). */
    private val disposeCaps = ArrayList<Int>()

    /**
     * Runs [body] in a region that performs DisposeResources on its capability when left by any completion (normal,
     * break/continue/return through the finally machinery, or throw), then continues that completion unless disposal
     * threw.
     */
    private fun withDisposal(async: Boolean, body: () -> Unit) {
        val capReg = allocReg()
        emit(Op.DISPOSE_NEW)
        emit(Op.STORE_REG, capReg)
        val kindReg = allocReg()
        val valueReg = allocReg()
        val envReg = allocReg()
        emit(Op.GET_ENV)
        emit(Op.STORE_REG, envReg)
        val d0 = currentDepth
        val entry = newLabel()
        val fctl = FinallyCtl(ctl, kindReg, valueReg, entry)
        ctl = fctl
        val savedScope = scope
        disposeCaps.add(capReg)
        val start = pc
        body()
        val end = pc
        disposeCaps.removeAt(disposeCaps.size - 1)
        ctl = fctl.parent
        emit(Op.PUSH_UNDEF)
        emit(Op.STORE_REG, valueReg)
        pushInt(0)
        emit(Op.STORE_REG, kindReg)
        emitJump(Op.JUMP, entry)
        val hpc = pc
        resetDepth(d0 + 1)
        addHandler(start, end, hpc, d0)
        emit(Op.STORE_REG, valueReg)
        emit(Op.LOAD_REG, envReg)
        emit(Op.SET_ENV)
        pushInt(1)
        emit(Op.STORE_REG, kindReg)
        place(entry)
        scope = savedScope
        emit(Op.LOAD_REG, capReg)
        emit(Op.LOAD_REG, kindReg)
        emit(Op.LOAD_REG, valueReg)
        if (!async) {
            emit(Op.DISPOSE_SYNC)
        } else {
            val ad = allocReg()
            val mode = allocReg()
            emit(Op.DISPOSE_BEGIN)
            emit(Op.STORE_REG, ad)
            val loop = newLabel()
            val done = newLabel()
            place(loop)
            emit(Op.LOAD_REG, ad)
            emit(Op.DISPOSE_STEP)
            emitJump(Op.JUMP_IF_FALSE, done)
            pushInt(0)
            emit(Op.STORE_REG, mode)
            emit(Op.AWAIT_CATCH, mode)
            emit(Op.LOAD_REG, ad)
            emit(Op.LOAD_REG, mode)
            emit(Op.DISPOSE_AWAITED)
            emitJump(Op.JUMP, loop)
            placeAfterJump(done)
            emit(Op.POP)
            emit(Op.LOAD_REG, ad)
            emit(Op.DISPOSE_END)
            freeReg(mode)
            freeReg(ad)
        }
        // continue the original completion
        val after = newLabel()
        val rethrow = newLabel()
        val maxKind = fctl.pending.maxOfOrNull { it.kind } ?: 1
        val table = ArrayList<Label>()
        repeat(maxKind + 1) { table.add(after) }
        if (maxKind >= 1) table[1] = rethrow
        val stubs = ArrayList<Pair<PendingJump, Label>>()
        for (p in fctl.pending) {
            val l = newLabel()
            table[p.kind] = l
            stubs.add(p to l)
        }
        emitJumpTable(kindReg, table, after)
        placeAfterJump(rethrow)
        emit(Op.LOAD_REG, valueReg)
        emit(Op.THROW)
        for ((p, l) in stubs) {
            placeAfterJump(l)
            if (p.target == null) {
                emit(Op.LOAD_REG, valueReg)
                emitReturnValue()
            } else {
                emitJumpTo(p.target, p.isContinue)
            }
        }
        placeAfterJump(after)
        freeReg(envReg)
        freeReg(valueReg)
        freeReg(kindReg)
        freeReg(capReg)
    }

    /** Value on the stack is the initializer of a `using` binding: registers it (keeping the value). */
    private fun emitAddDisposable(async: Boolean) {
        emit(Op.DUP)
        emit(Op.LOAD_REG, disposeCaps.lastOrNull() ?: throw CompileError("using declaration outside a disposal scope"))
        emit(Op.ADD_DISPOSABLE, if (async) 1 else 0)
    }

    private fun hoistBlockFunctions(body: List<Node>, s: Scope) {
        for (st0 in body) {
            var st = st0
            while (st is LabeledStatement) st = st.body
            if (st is FunctionDeclaration) {
                val name = st.function.id!!.name
                val b = s.bindings[name] ?: continue
                if (b.kind != BKind.BLOCK_FUNCTION) continue
                val cb = compileNested(st.function, name)
                emit(Op.MAKE_CLOSURE, const(cb))
                emitInitBinding(b)
            }
        }
    }

    private fun varDecl(n: VariableDeclaration) {
        mark(n)
        if (n.kind == VarKind.USING || n.kind == VarKind.AWAIT_USING) {
            for (d in n.declarations) {
                exprNamed(d.init!!, StaticName((d.id as Identifier).name))
                emitAddDisposable(n.kind == VarKind.AWAIT_USING)
                pattern(d.id, BindMode.INIT)
            }
            return
        }
        for (d in n.declarations) {
            val init = d.init
            if (n.kind == VarKind.VAR) {
                if (init == null) continue
                val id = d.id
                if (id is Identifier && id.ref is DynamicRef) {
                    emit(Op.RESOLVE_NAME, const(id.name))
                    exprNamed(init, StaticName(id.name))
                    emit(Op.PUT_REF)
                    emit(Op.POP)
                    continue
                }
                exprNamed(init, if (id is Identifier) StaticName(id.name) else null)
                pattern(id, BindMode.ASSIGN)
            } else {
                if (init == null) emit(Op.PUSH_UNDEF)
                else {
                    val id = d.id
                    exprNamed(init, if (id is Identifier) StaticName(id.name) else null)
                }
                pattern(d.id, BindMode.INIT)
            }
        }
    }

    // ------------------------------------------------------------------ loops

    private fun whileStmt(n: WhileStatement) {
        mark(n)
        setCompletionUndefined()
        val labels = takeLabels()
        val top = newLabel()
        val brk = newLabel()
        place(top)
        condJump(n.test, false, brk)
        ctl = LoopCtl(ctl, labels, brk, top, false)
        subStmt(n.body)
        ctl = ctl!!.parent
        emitJump(Op.JUMP, top)
        placeAfterJump(brk)
    }

    private fun doWhile(n: DoWhileStatement) {
        mark(n)
        setCompletionUndefined()
        val labels = takeLabels()
        val top = newLabel()
        val cont = newLabel()
        val brk = newLabel()
        place(top)
        ctl = LoopCtl(ctl, labels, brk, cont, false)
        subStmt(n.body)
        ctl = ctl!!.parent
        place(cont)
        condJump(n.test, true, top)
        place(brk)
    }

    private fun forStmt(n: ForStatement) {
        mark(n)
        setCompletionUndefined()
        val labels = takeLabels()
        val fs = n.scope as Scope?
        if (fs != null) enterScope(fs)
        val init = n.init
        if (init is VariableDeclaration && (init.kind == VarKind.USING || init.kind == VarKind.AWAIT_USING)) {
            // the loop environment's resources are disposed when the whole statement completes
            withDisposal(init.kind == VarKind.AWAIT_USING) {
                varDecl(init)
                forLoopRest(n, fs, labels)
            }
        } else {
            if (init != null) {
                if (init is VariableDeclaration) varDecl(init) else exprDiscard(init)
            }
            forLoopRest(n, fs, labels)
        }
        if (fs != null) exitScope(fs)
    }

    private fun forLoopRest(n: ForStatement, fs: Scope?, labels: List<String>) {
        val perIter = fs != null && fs.perIteration && fs.needsEnv
        if (perIter) emit(Op.COPY_SCOPE)
        val top = newLabel()
        val cont = newLabel()
        val brk = newLabel()
        place(top)
        if (n.test != null) condJump(n.test, false, brk)
        ctl = LoopCtl(ctl, labels, brk, cont, false)
        subStmt(n.body)
        ctl = ctl!!.parent
        place(cont)
        if (perIter) emit(Op.COPY_SCOPE)
        if (n.update != null) exprDiscard(n.update)
        emitJump(Op.JUMP, top)
        placeAfterJump(brk)
    }

    private fun forIn(n: ForInStatement) {
        mark(n)
        setCompletionUndefined()
        val labels = takeLabels()
        val left = n.left
        // Annex B: for (var x = init in obj)
        if (left is VariableDeclaration && left.kind == VarKind.VAR && left.declarations[0].init != null) {
            val d = left.declarations[0]
            exprNamed(d.init!!, StaticName((d.id as Identifier).name))
            pattern(d.id, BindMode.ASSIGN)
        }
        val tdz = n.right.scope as Scope?
        if (tdz != null) enterScope(tdz)
        expr(n.right)
        if (tdz != null) exitScope(tdz)
        val brk = newLabel()
        // null/undefined -> no iteration
        emit(Op.DUP)
        val nullish = newLabel()
        emitJump(Op.JUMP_IF_NULLISH, nullish)
        emit(Op.FOR_IN_START)
        val it = allocReg()
        emit(Op.STORE_REG, it)
        val top = newLabel()
        place(top)
        emit(Op.FOR_IN_NEXT, it, 0)
        patchLastJump(brk)
        val iterScope = n.scope as Scope?
        ctl = LoopCtl(ctl, labels, brk, top, false)
        bindLoopTarget(left, iterScope)
        subStmt(n.body)
        if (iterScope != null && left is VariableDeclaration && left.kind != VarKind.VAR) exitScope(iterScope)
        ctl = ctl!!.parent
        emitJump(Op.JUMP, top)
        placeAfterJump(nullish)
        emit(Op.POP)
        place(brk)
        freeReg(it)
    }

    /** Patches the last operand of the previously emitted instruction to jump to [l]. */
    private fun patchLastJump(l: Label) {
        val at = pc - 1
        if (l.pos >= 0) code[at] = l.pos else l.fixups.add(at)
        if (l.depth < 0) l.depth = currentDepth - 1
    }

    /** Binds the value on top of the stack to a for-in/of left side, entering the per-iteration scope. */
    private fun bindLoopTarget(left: Node, iterScope: Scope?) {
        if (left is VariableDeclaration) {
            if (left.kind == VarKind.VAR) {
                pattern(left.declarations[0].id, BindMode.ASSIGN)
            } else {
                if (iterScope != null) {
                    // enterScope pushes ScopeCtl so break/continue pop the env
                    enterScope(iterScope)
                }
                pattern(left.declarations[0].id, BindMode.INIT)
            }
        } else {
            pattern(left, BindMode.ASSIGN)
        }
    }

    private fun forOf(n: ForOfStatement) {
        mark(n)
        setCompletionUndefined()
        val labels = takeLabels()
        val tdz = n.right.scope as Scope?
        if (tdz != null) enterScope(tdz)
        expr(n.right)
        if (tdz != null) exitScope(tdz)
        val isAsync = n.isAwait
        emit(if (isAsync) Op.GET_ASYNC_ITERATOR else Op.GET_ITERATOR)
        val it = allocReg()
        emit(Op.STORE_REG, it)
        val top = newLabel()
        val brk = newLabel()
        place(top)
        if (isAsync) {
            emit(Op.ITER_NEXT_CALL, it)
            emitAwait()
            emit(Op.ITER_RESULT_STEP, it, 0)
            patchLastJump(brk)
        } else {
            emit(Op.ITER_STEP, it, 0)
            patchLastJump(brk)
        }
        val d = currentDepth - 1
        val start = pc
        val iterScope = n.scope as Scope?
        val loopCtl = LoopCtl(ctl, labels, brk, top, false)
        val icl = IterCtl(loopCtl, it, isAsync)
        ctl = icl
        val left = n.left
        if (left is VariableDeclaration && (left.kind == VarKind.USING || left.kind == VarKind.AWAIT_USING)) {
            // each iteration's resources are disposed at the end of that iteration
            if (iterScope != null) enterScope(iterScope)
            val tmp = allocReg()
            emit(Op.STORE_REG, tmp)
            withDisposal(left.kind == VarKind.AWAIT_USING) {
                emit(Op.LOAD_REG, tmp)
                emitAddDisposable(left.kind == VarKind.AWAIT_USING)
                pattern(left.declarations[0].id, BindMode.INIT)
                subStmt(n.body)
            }
            freeReg(tmp)
            if (iterScope != null) exitScope(iterScope)
        } else {
            bindLoopTarget(left, iterScope)
            subStmt(n.body)
            if (iterScope != null && left is VariableDeclaration && left.kind != VarKind.VAR) exitScope(iterScope)
        }
        ctl = loopCtl.parent
        emitJump(Op.JUMP, top)
        val end = pc
        // abrupt throw completion: close the iterator (ignoring errors) and rethrow
        val h = pc
        resetDepth(d + 1)
        if (isAsync) {
            val excReg = allocReg()
            emit(Op.STORE_REG, excReg)
            val skip = newLabel()
            emit(Op.ASYNC_ITER_CLOSE, it, 1, 0)
            fixAsyncCloseJump(skip)
            emit(Op.AWAIT_IGNORE)
            emit(Op.POP)
            place(skip)
            emit(Op.LOAD_REG, excReg)
            emit(Op.THROW)
            freeReg(excReg)
        } else {
            emit(Op.ITER_CLOSE_THROW, it)
            emit(Op.THROW)
        }
        addHandler(start, end, h, d)
        resetDepth(d)
        placeAfterJump(brk)
        freeReg(it)
    }

    private fun switchStmt(n: SwitchStatement) {
        mark(n)
        setCompletionUndefined()
        val labels = takeLabels()
        expr(n.discriminant)
        val tmp = allocReg()
        emit(Op.STORE_REG, tmp)
        val s = n.scope as Scope
        enterScope(s)
        val all = ArrayList<Node>()
        n.cases.forEach { all.addAll(it.consequent) }
        hoistBlockFunctions(all, s)
        val caseLabels = n.cases.map { newLabel() }
        var defaultIdx = -1
        for ((i, c) in n.cases.withIndex()) {
            if (c.test == null) {
                defaultIdx = i
                continue
            }
            emit(Op.LOAD_REG, tmp)
            expr(c.test)
            emit(Op.SEQ)
            emitJump(Op.JUMP_IF_TRUE, caseLabels[i])
        }
        val brk = newLabel()
        emitJump(Op.JUMP, if (defaultIdx >= 0) caseLabels[defaultIdx] else brk)
        ctl = LoopCtl(ctl, labels, brk, null, true)
        for ((i, c) in n.cases.withIndex()) {
            placeAfterJumpOrFall(caseLabels[i])
            for (st in c.consequent) stmt(st)
        }
        ctl = ctl!!.parent
        place(brk)
        exitScope(s)
        freeReg(tmp)
    }

    private fun placeAfterJumpOrFall(l: Label) {
        place(l)
    }

    // ------------------------------------------------------------------ try

    private fun tryStmt(n: TryStatement) {
        mark(n)
        setCompletionUndefined()
        if (n.finalizer != null) tryFinally(n) else tryCatch(n.block, n.handler!!)
    }

    private fun tryCatch(blockNode: BlockStatement, h: CatchClause) {
        val envReg = allocReg()
        emit(Op.GET_ENV)
        emit(Op.STORE_REG, envReg)
        val savedScope = scope
        val start = pc
        tryDepth++
        block(blockNode)
        tryDepth--
        val end = pc
        val after = newLabel()
        emitJump(Op.JUMP, after)
        val hpc = pc
        resetDepth(1)
        addHandler(start, end, hpc, 0)
        emit(Op.LOAD_REG, envReg)
        emit(Op.SET_ENV)
        // UpdateEmpty(C, undefined): the try block's partial completion value does not survive a throw
        if (completionReg >= 0) {
            emit(Op.PUSH_UNDEF)
            emit(Op.STORE_REG, completionReg)
        }
        scope = savedScope
        val cs = h.scope as Scope
        enterScope(cs)
        if (h.param != null) pattern(h.param, BindMode.INIT) else emit(Op.POP)
        block(h.body)
        exitScope(cs)
        place(after)
        freeReg(envReg)
    }

    private fun tryFinally(n: TryStatement) {
        val kindReg = allocReg()
        val valueReg = allocReg()
        val envReg = allocReg()
        emit(Op.GET_ENV)
        emit(Op.STORE_REG, envReg)
        val entry = newLabel()
        val fctl = FinallyCtl(ctl, kindReg, valueReg, entry)
        ctl = fctl
        val savedScope = scope
        val start = pc
        if (n.handler != null) tryCatch(n.block, n.handler) else block(n.block)
        val end = pc
        ctl = fctl.parent
        pushInt(0)
        emit(Op.STORE_REG, kindReg)
        emitJump(Op.JUMP, entry)
        val hpc = pc
        resetDepth(1)
        addHandler(start, end, hpc, 0)
        emit(Op.STORE_REG, valueReg)
        emit(Op.LOAD_REG, envReg)
        emit(Op.SET_ENV)
        pushInt(1)
        emit(Op.STORE_REG, kindReg)
        place(entry)
        scope = savedScope
        var savedCompletion = -1
        if (completionReg >= 0) {
            savedCompletion = allocReg()
            emit(Op.LOAD_REG, completionReg)
            emit(Op.STORE_REG, savedCompletion)
            // an abrupt finally completes with its own (possibly empty -> undefined) value
            emit(Op.PUSH_UNDEF)
            emit(Op.STORE_REG, completionReg)
        }
        block(n.finalizer!!)
        if (savedCompletion >= 0) {
            emit(Op.LOAD_REG, savedCompletion)
            emit(Op.STORE_REG, completionReg)
            freeReg(savedCompletion)
        }
        // dispatch
        val after = newLabel()
        val rethrow = newLabel()
        val targets = ArrayList<Label>()
        targets.add(after)
        targets.add(rethrow)
        val stubs = ArrayList<Pair<PendingJump, Label>>()
        // map kinds compactly: build table from 0..maxKind
        val maxKind = fctl.pending.maxOfOrNull { it.kind } ?: 1
        val table = ArrayList<Label>()
        repeat(maxKind + 1) { table.add(after) }
        table[0] = after
        if (maxKind >= 1) table[1] = rethrow
        for (p in fctl.pending) {
            val l = newLabel()
            table[p.kind] = l
            stubs.add(p to l)
        }
        emitJumpTable(kindReg, table, after)
        placeAfterJump(rethrow)
        emit(Op.LOAD_REG, valueReg)
        emit(Op.THROW)
        for ((p, l) in stubs) {
            placeAfterJump(l)
            if (p.target == null) {
                emit(Op.LOAD_REG, valueReg)
                emitReturnValue()
            } else {
                emitJumpTo(p.target, p.isContinue)
            }
        }
        placeAfterJump(after)
        freeReg(envReg)
        freeReg(valueReg)
        freeReg(kindReg)
    }
}

/** Compiler entry points. */
object Compiler {
    fun compileScript(prog: Program, source: Source): CodeBlock {
        val an = ScopeAnalyzer(CodeMode.SCRIPT)
        val fi = an.analyze(prog)
        return Emitter(fi, source, an).compileProgram(prog, CodeMode.SCRIPT)
    }

    fun compileEval(prog: Program, source: Source, direct: Boolean): CodeBlock {
        val mode = if (direct) CodeMode.EVAL_DIRECT else CodeMode.EVAL_INDIRECT
        val an = ScopeAnalyzer(mode, evalStrict = prog.strict)
        val fi = an.analyze(prog)
        return Emitter(fi, source, an).compileProgram(prog, mode)
    }

    fun compileModule(prog: Program, source: Source): CompiledModule {
        val an = ScopeAnalyzer(CodeMode.MODULE)
        val fi = an.analyze(prog)
        val root = prog.scope as Scope
        val tla = ModuleInfo.hasTopLevelAwait(prog)
        val init = Emitter(fi, source, an).compileModuleInit(prog)
        val body = Emitter(fi, source, an).compileModuleBody(prog, tla)
        val (requests, imports, exports) = ModuleInfo.requestsAndEntries(prog)
        val importedLocal = imports.associateBy { it.localName }
        val local = ArrayList<ExportEntry>()
        val indirect = ArrayList<ExportEntry>()
        val star = ArrayList<ExportEntry>()
        for (ee in exports) {
            if (ee.request < 0) {
                val ie = importedLocal[ee.localName]
                if (ie == null) local.add(ee)
                else indirect.add(ExportEntry(ee.exportName, ie.request, ie.importName, null))
            } else if (ee.importName == ImportEntry.NAMESPACE && ee.exportName == null) star.add(ee)
            else indirect.add(ee)
        }
        return CompiledModule(init, body, root.info!!, requests, imports, local, indirect, star, tla, source)
    }

    fun compileFunction(fn: FunctionNode, source: Source): CodeBlock {
        val an = ScopeAnalyzer(CodeMode.FUNCTION_CTOR)
        an.analyzeFunction(fn)
        val fi = (fn.scope as Scope).fn
        return Emitter(fi, source, an).compileFunction(fn, "anonymous", false)
    }
}
