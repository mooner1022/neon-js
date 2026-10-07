package dev.mooner.neonjs.compiler

import dev.mooner.neonjs.parser.*

enum class ScopeKind { GLOBAL, EVAL, MODULE, FUNCTION, BODY, CALLEE, BLOCK, CATCH, FOR, SWITCH, WITH, CLASS }

enum class BKind {
    VAR, FUNCTION, PARAM, LET, CONST, CLASS, CLASS_INNER, CALLEE, CATCH, IMPORT, BLOCK_FUNCTION, USING,
    THIS, NEW_TARGET, HOME, FN, ARGUMENTS, PRIVATE;

    val isLexical get() = this == LET || this == CONST || this == CLASS || this == CLASS_INNER || this == USING
    val isConst get() = this == CONST || this == CLASS_INNER || this == IMPORT || this == USING
}

class Binding(val name: String, var kind: BKind, val scope: Scope) {
    var captured = false
    var reg = -1
    var slot = -1
    /** Source position after which the binding is initialized (TDZ elision); -1 = always check. */
    var declEnd = -1
    /** For block-level function declarations in sloppy mode: the Annex B var binding to update. */
    var annexBVar: Any? = null
    /** For derived constructors, the `this` binding starts uninitialized. */
    var tdz = false
    /** Binding is used as an implicit arguments object holder. */
    var isArgumentsObject = false
    /** For private names: the private-name kind ("field", "method", "get", "set", "getset", "accessor"), static flag. */
    var privateKind: String? = null
    var privateStatic = false
    val inEnv get() = slot >= 0
    val needsTdz get() = kind.isLexical || tdz
    override fun toString() = "$name:$kind${if (inEnv) "@env$slot" else "@r$reg"}"
}

class Scope(val kind: ScopeKind, val parent: Scope?, val fn: FnInfo) {
    val bindings = LinkedHashMap<String, Binding>()
    var needsEnv = false
    var hasEval = false
    /** Sloppy direct eval may add var bindings to this scope at runtime. */
    var evalVarTarget = false
    /** All bindings must be env allocated (visible to eval). */
    var allCaptured = false
    var envSize = 0
    var info: ScopeInfo? = null
    /** For FOR scopes: per-iteration copy needed. */
    var perIteration = false
    @JvmField var depth = 0

    fun declare(name: String, kind: BKind): Binding {
        val b = Binding(name, kind, this)
        bindings[name] = b
        return b
    }

    override fun toString() = "Scope($kind ${bindings.keys})"
}

class FnInfo(val node: Node, val parent: FnInfo?) {
    lateinit var scope: Scope
    lateinit var varScope: Scope
    var calleeScope: Scope? = null
    /** Separate variable environment receiving `var`s of direct eval calls in parameter expressions. */
    var paramEvalScope: Scope? = null
    var isArrow = false
    var strict = false
    var isAsync = false
    var isGenerator = false
    var kind: FunctionKind = FunctionKind.NORMAL
    var hasParamExpressions = false
    var hasDirectEval = false
    var containsEval = false
    var thisBinding: Binding? = null
    var newTargetBinding: Binding? = null
    var homeBinding: Binding? = null
    var fnBinding: Binding? = null
    var argumentsBinding: Binding? = null
    var usesThis = false
    var usesArguments = false
    var mappedArguments = false
    var numRegs = 0
    /** True for script/module/eval top-level code. */
    var isTopLevel = false

    /** Functions that have their own this/arguments/new.target (non-arrow). */
    val hasOwnThis get() = !isArrow
}

/** Resolution result stored in Identifier.ref (and ThisExpression/MetaProperty/Super ref). */
sealed class Resolved
class LocalRef(@JvmField val binding: Binding, @JvmField val checkTdz: Boolean) : Resolved()
class GlobalRef(@JvmField val name: String) : Resolved()
class DynamicRef(@JvmField val name: String) : Resolved()

enum class CodeMode { SCRIPT, MODULE, EVAL_DIRECT, EVAL_INDIRECT, FUNCTION_CTOR }

/**
 * Builds the scope tree, declares bindings (with hoisting and Annex B semantics), resolves identifier references and
 * allocates registers / environment slots.
 */
class ScopeAnalyzer(val mode: CodeMode, val evalStrict: Boolean = false, val evalInFunction: Boolean = false) {

    private class RefSite(val node: Node, val name: String, val scope: Scope, val pos: Int)

    private val refs = ArrayList<RefSite>()
    private val allFunctions = ArrayList<FnInfo>()
    private val blockFunctions = ArrayList<Triple<FunctionDeclaration, Scope, Binding>>()
    private val evalScopes = ArrayList<Scope>()

    lateinit var root: FnInfo
    /** Global code: names declared at the top level. */
    val globalVarNames = LinkedHashSet<String>()
    val globalFunctions = ArrayList<FunctionDeclaration>()
    val globalLexNames = LinkedHashMap<String, Boolean>() // name -> isConst
    /** Annex B function names hoisted to the global/eval var scope. */
    val annexBGlobalNames = LinkedHashSet<String>()

    private lateinit var cur: Scope
    private lateinit var curFn: FnInfo

    fun analyze(prog: Program): FnInfo {
        val fi = FnInfo(prog, null)
        fi.isTopLevel = true
        fi.strict = prog.strict
        root = fi
        curFn = fi
        allFunctions.add(fi)
        val kind = when (mode) {
            CodeMode.SCRIPT -> ScopeKind.GLOBAL
            CodeMode.MODULE -> ScopeKind.MODULE
            else -> ScopeKind.EVAL
        }
        val s = Scope(kind, null, fi)
        fi.scope = s
        fi.varScope = s
        prog.scope = s
        cur = s
        if (mode == CodeMode.MODULE) s.allCaptured = true
        hoistDeclarations(prog.body, s, topLevel = true)
        for (st in prog.body) visitStatement(st)
        finish()
        return fi
    }

    /** Analyze a function created by the Function constructor (global scope parent). */
    fun analyzeFunction(fn: FunctionNode): FnInfo {
        val fi = FnInfo(fn, null)
        fi.isTopLevel = true
        root = fi
        curFn = fi
        allFunctions.add(fi)
        val s = Scope(ScopeKind.GLOBAL, null, fi)
        fi.scope = s
        fi.varScope = s
        cur = s
        visitFunction(fn)
        finish()
        return fi
    }

    private val isSloppyEval get() = (mode == CodeMode.EVAL_DIRECT || mode == CodeMode.EVAL_INDIRECT) && !evalStrict

    // ================================================================== declarations

    /** True when declarations in this var scope are global object properties (script / sloppy eval). */
    private fun varsAreDynamic(s: Scope): Boolean =
        (s.kind == ScopeKind.GLOBAL) || (s.kind == ScopeKind.EVAL && isSloppyEval)

    private fun declareVar(name: String, varScope: Scope) {
        if (varsAreDynamic(varScope)) {
            globalVarNames.add(name)
            return
        }
        val existing = varScope.bindings[name]
        if (existing == null) varScope.declare(name, BKind.VAR)
    }

    /**
     * Hoists var declarations (recursively, not into nested functions) and top-level function declarations of a
     * function body / program into [varScope]; lexical declarations of the statement list into [lexScope].
     */
    private fun hoistDeclarations(body: List<Node>, scope: Scope, topLevel: Boolean) {
        val varScope = scope.fn.varScope
        for (st in body) collectVars(st, varScope)
        declareLexical(body, scope, topLevel)
    }

    private fun collectVars(n: Node?, varScope: Scope) {
        when (n) {
            null -> {}
            is VariableDeclaration -> if (n.kind == VarKind.VAR) {
                for (d in n.declarations) boundNames(d.id).forEach { declareVar(it.name, varScope) }
            }
            is BlockStatement -> n.body.forEach { collectVars(it, varScope) }
            is IfStatement -> { collectVars(n.consequent, varScope); collectVars(n.alternate, varScope) }
            is ForStatement -> { collectVars(n.init, varScope); collectVars(n.body, varScope) }
            is ForInStatement -> { collectVars(n.left, varScope); collectVars(n.body, varScope) }
            is ForOfStatement -> { collectVars(n.left, varScope); collectVars(n.body, varScope) }
            is WhileStatement -> collectVars(n.body, varScope)
            is DoWhileStatement -> collectVars(n.body, varScope)
            is LabeledStatement -> collectVars(n.body, varScope)
            is TryStatement -> { collectVars(n.block, varScope); n.handler?.let { collectVars(it.body, varScope) }; collectVars(n.finalizer, varScope) }
            is SwitchStatement -> n.cases.forEach { c -> c.consequent.forEach { collectVars(it, varScope) } }
            is WithStatement -> collectVars(n.body, varScope)
            is ExportNamedDeclaration -> collectVars(n.declaration, varScope)
            else -> {}
        }
    }

    /** Declares lexically scoped declarations of a statement list (let/const/class, block functions). */
    private fun declareLexical(body: List<Node>, scope: Scope, topLevel: Boolean) {
        val isFnTop = topLevel
        for (st0 in body) {
            var st = st0
            while (st is LabeledStatement) st = st.body
            if (st is ExportNamedDeclaration) st = st.declaration ?: continue
            if (st is ExportDefaultDeclaration) {
                val d = st.declaration
                when (d) {
                    is FunctionDeclaration -> {
                        declareFunctionDecl(d, scope, isFnTop, d.function.id?.name ?: "*default*")
                    }
                    is ClassDeclaration -> {
                        val name = d.cls.id?.name ?: "*default*"
                        val b = scope.declare(name, BKind.CLASS)
                        b.declEnd = d.end
                    }
                    else -> {
                        val b = scope.declare("*default*", BKind.CONST)
                        b.declEnd = st.end
                    }
                }
                continue
            }
            when (st) {
                is VariableDeclaration -> if (st.kind != VarKind.VAR) {
                    for (d in st.declarations) {
                        for (id in boundNames(d.id)) {
                            if (scope.kind == ScopeKind.GLOBAL) {
                                globalLexNames[id.name] = st.kind == VarKind.CONST
                            } else {
                                val k = when (st.kind) {
                                    VarKind.CONST -> BKind.CONST
                                    VarKind.USING, VarKind.AWAIT_USING -> BKind.USING
                                    else -> BKind.LET
                                }
                                val b = scope.declare(id.name, k)
                                b.declEnd = d.end
                            }
                        }
                    }
                }
                is ClassDeclaration -> {
                    val name = st.cls.id!!.name
                    if (scope.kind == ScopeKind.GLOBAL) globalLexNames[name] = false
                    else {
                        val b = scope.declare(name, BKind.CLASS)
                        b.declEnd = st.end
                    }
                }
                is FunctionDeclaration -> declareFunctionDecl(st, scope, isFnTop, st.function.id!!.name)
                is ImportDeclaration -> {
                    for (sp in st.specifiers) {
                        val b = scope.declare(sp.local.name, BKind.IMPORT)
                        b.captured = true
                    }
                }
                else -> {}
            }
        }
    }

    private fun declareFunctionDecl(st: FunctionDeclaration, scope: Scope, isFnTop: Boolean, name: String) {
        if (isFnTop) {
            // function-level (or global/eval/module top-level) declaration: var-scoped
            val vs = scope.fn.varScope
            if (vs.kind == ScopeKind.GLOBAL || (vs.kind == ScopeKind.EVAL && isSloppyEval)) {
                globalFunctions.add(st)
                globalVarNames.add(name)
            } else if (vs.kind == ScopeKind.MODULE) {
                scope.declare(name, BKind.FUNCTION)
            } else {
                val b = vs.bindings[name]
                if (b == null || b.kind != BKind.PARAM || true) {
                    if (b == null) vs.declare(name, BKind.FUNCTION) else if (b.kind == BKind.VAR || b.kind == BKind.FUNCTION) b.kind = BKind.FUNCTION
                    else if (b.kind == BKind.PARAM) vs.declare(name, BKind.FUNCTION)
                }
            }
        } else {
            val b = scope.declare(name, BKind.BLOCK_FUNCTION)
            val fn = st.function
            if (!fn.strict && !scope.fn.strict && !fn.isAsync && !fn.isGenerator) {
                blockFunctions.add(Triple(st, scope, b))
            }
        }
    }

    private fun boundNames(n: Node?): List<Identifier> {
        val out = ArrayList<Identifier>()
        collectBound(n, out)
        return out
    }

    private fun collectBound(n: Node?, out: MutableList<Identifier>) {
        when (n) {
            null -> {}
            is Identifier -> out.add(n)
            is AssignmentPattern -> collectBound(n.left, out)
            is RestElement -> collectBound(n.argument, out)
            is ArrayPattern -> n.elements.forEach { collectBound(it, out) }
            is ObjectPattern -> n.properties.forEach { if (it is Property) collectBound(it.value, out) else collectBound(it, out) }
            is VariableDeclaration -> n.declarations.forEach { collectBound(it.id, out) }
            else -> {}
        }
    }

    // ================================================================== scopes

    private fun push(kind: ScopeKind, node: Node?): Scope {
        val s = Scope(kind, cur, curFn)
        node?.scope = s
        cur = s
        register(s)
        return s
    }

    private fun pop(s: Scope) {
        cur = s.parent!!
    }

    private fun ref(node: Node, name: String) {
        refs.add(RefSite(node, name, cur, node.start))
    }

    // ================================================================== statements

    private fun visitStatement(n: Node?) {
        when (n) {
            null -> {}
            is ExpressionStatement -> visitExpr(n.expression)
            is VariableDeclaration -> {
                for (d in n.declarations) {
                    if (n.kind == VarKind.VAR) {
                        visitPattern(d.id, isDeclaration = false)
                    } else visitPattern(d.id, isDeclaration = true)
                    visitExpr(d.init)
                }
            }
            is FunctionDeclaration -> visitFunction(n.function)
            is ClassDeclaration -> visitClass(n.cls)
            is BlockStatement -> visitBlock(n, n.body)
            is EmptyStatement, is DebuggerStatement -> {}
            is IfStatement -> {
                visitExpr(n.test)
                visitSubStatement(n.consequent)
                visitSubStatement(n.alternate)
            }
            is ForStatement -> {
                val init = n.init
                if (init is VariableDeclaration && init.kind != VarKind.VAR) {
                    val s = push(ScopeKind.FOR, n)
                    for (d in init.declarations) for (id in boundNames(d.id)) {
                        val b = s.declare(id.name, when (init.kind) {
                            VarKind.CONST -> BKind.CONST
                            VarKind.USING, VarKind.AWAIT_USING -> BKind.USING
                            else -> BKind.LET
                        })
                        b.declEnd = d.end
                    }
                    s.perIteration = init.kind == VarKind.LET
                    visitStatement(init)
                    visitExpr(n.test)
                    visitExpr(n.update)
                    visitSubStatement(n.body)
                    pop(s)
                } else {
                    if (init is VariableDeclaration) visitStatement(init) else visitExpr(init)
                    visitExpr(n.test)
                    visitExpr(n.update)
                    visitSubStatement(n.body)
                }
            }
            is ForInStatement -> visitForInOf(n, n.left, n.right, n.body)
            is ForOfStatement -> visitForInOf(n, n.left, n.right, n.body)
            is WhileStatement -> { visitExpr(n.test); visitSubStatement(n.body) }
            is DoWhileStatement -> { visitSubStatement(n.body); visitExpr(n.test) }
            is ReturnStatement -> visitExpr(n.argument)
            is ThrowStatement -> visitExpr(n.argument)
            is BreakStatement, is ContinueStatement -> {}
            is LabeledStatement -> visitStatementInList(n.body)
            is TryStatement -> {
                visitStatement(n.block)
                val h = n.handler
                if (h != null) {
                    val s = push(ScopeKind.CATCH, h)
                    val p = h.param
                    if (p != null && p !is Identifier) catchPatternScopes.add(s)
                    if (p != null) {
                        for (id in boundNames(p)) {
                            val b = s.declare(id.name, BKind.CATCH)
                            @Suppress("UNUSED_VARIABLE") val u = b
                        }
                        visitPattern(p, isDeclaration = true)
                    }
                    visitBlock(h.body, h.body.body)
                    pop(s)
                }
                n.finalizer?.let { visitStatement(it) }
            }
            is SwitchStatement -> {
                visitExpr(n.discriminant)
                val s = push(ScopeKind.SWITCH, n)
                val all = ArrayList<Node>()
                n.cases.forEach { all.addAll(it.consequent) }
                declareLexical(all, s, false)
                for (c in n.cases) {
                    visitExpr(c.test)
                    for (st in c.consequent) visitStatementInList(st)
                }
                pop(s)
            }
            is WithStatement -> {
                visitExpr(n.obj)
                val s = push(ScopeKind.WITH, n)
                s.needsEnv = true
                visitSubStatement(n.body)
                pop(s)
            }
            is ImportDeclaration -> {}
            is ExportNamedDeclaration -> {
                if (n.declaration != null) visitStatement(n.declaration)
                else if (n.source == null) for (sp in n.specifiers) {
                    val id = Identifier(sp.local)
                    id.start = sp.start; id.end = sp.end
                    sp.ref = id
                    ref(id, sp.local)
                }
            }
            is ExportDefaultDeclaration -> {
                val d = n.declaration
                if (d is FunctionDeclaration) visitFunction(d.function)
                else if (d is ClassDeclaration) visitClass(d.cls)
                else visitExpr(d)
            }
            is ExportAllDeclaration -> {}
            else -> throw IllegalStateException("Unknown statement ${n::class.simpleName}")
        }
    }

    /** Statement in a statement list (labels may wrap declarations). */
    private fun visitStatementInList(n: Node) = visitStatement(n)

    /** Statement in a single-statement position (if/loop bodies): sloppy function declarations get a block scope. */
    private fun visitSubStatement(n: Node?) {
        if (n is FunctionDeclaration) {
            // Annex B: if (x) function f(){}  behaves like  if (x) { function f(){} }
            val s = push(ScopeKind.BLOCK, n)
            declareLexical(listOf(n), s, false)
            visitFunction(n.function)
            pop(s)
            return
        }
        if (n is LabeledStatement) {
            var inner: Node = n
            while (inner is LabeledStatement) inner = inner.body
            if (inner is FunctionDeclaration) {
                val s = push(ScopeKind.BLOCK, n)
                declareLexical(listOf(inner), s, false)
                visitFunction(inner.function)
                pop(s)
                return
            }
        }
        visitStatement(n)
    }

    private fun visitBlock(node: Node, body: List<Node>) {
        val s = push(ScopeKind.BLOCK, node)
        declareLexical(body, s, false)
        for (st in body) visitStatementInList(st)
        pop(s)
    }

    private fun visitForInOf(n: Node, left: Node, right: Node, body: Node) {
        if (left is VariableDeclaration && left.kind != VarKind.VAR) {
            // TDZ scope for the RHS expression with the same names
            val tdz = push(ScopeKind.FOR, right)
            for (d in left.declarations) for (id in boundNames(d.id)) tdz.declare(id.name, BKind.LET)
            visitExpr(right)
            pop(tdz)
            val s = push(ScopeKind.FOR, n)
            val k = when (left.kind) {
                VarKind.CONST -> BKind.CONST
                VarKind.USING, VarKind.AWAIT_USING -> BKind.USING
                else -> BKind.LET
            }
            for (d in left.declarations) for (id in boundNames(d.id)) {
                val b = s.declare(id.name, k)
                b.declEnd = left.end
            }
            visitPattern(left.declarations[0].id, isDeclaration = true)
            visitSubStatement(body)
            pop(s)
        } else {
            if (left is VariableDeclaration) {
                visitPattern(left.declarations[0].id, isDeclaration = false)
                visitExpr(left.declarations[0].init)
            } else visitPattern(left, isDeclaration = false)
            visitExpr(right)
            visitSubStatement(body)
        }
    }

    // ================================================================== patterns

    /** Visits a binding/assignment pattern. Declaration identifiers are recorded as references to resolve. */
    private fun visitPattern(n: Node?, isDeclaration: Boolean) {
        when (n) {
            null -> {}
            is Identifier -> ref(n, n.name)
            is MemberExpression -> visitExpr(n)
            is AssignmentPattern -> { visitPattern(n.left, isDeclaration); visitExpr(n.right) }
            is RestElement -> visitPattern(n.argument, isDeclaration)
            is ArrayPattern -> n.elements.forEach { visitPattern(it, isDeclaration) }
            is ObjectPattern -> for (p in n.properties) {
                if (p is Property) {
                    if (p.computed) visitExpr(p.key)
                    visitPattern(p.value, isDeclaration)
                } else visitPattern(p, isDeclaration)
            }
            is CallExpression -> visitExpr(n)
            else -> visitExpr(n)
        }
    }

    // ================================================================== expressions

    private fun visitExpr(n: Node?) {
        when (n) {
            null -> {}
            is Identifier -> ref(n, n.name)
            is NumberLiteral, is StringLiteral, is BigIntLiteral, is BooleanLiteral, is NullLiteral, is RegExpLiteral -> {}
            is TemplateLiteral -> n.expressions.forEach { visitExpr(it) }
            is TaggedTemplate -> { visitExpr(n.tag); n.quasi.expressions.forEach { visitExpr(it) } }
            is ThisExpression -> ref(n, "this")
            is Super -> { ref(n, "%home") }
            is ArrayLiteral -> n.elements.forEach { visitExpr(it) }
            is ObjectLiteral -> for (p in n.properties) {
                if (p is Property) {
                    if (p.computed) visitExpr(p.key)
                    val v = p.value
                    if (v is FunctionNode) visitFunction(v) else visitExpr(v)
                } else visitExpr(p)
            }
            is SpreadElement -> visitExpr(n.argument)
            is UnaryExpression -> visitExpr(n.argument)
            is UpdateExpression -> visitExpr(n.argument)
            is BinaryExpression -> { visitExpr(n.left); visitExpr(n.right) }
            is LogicalExpression -> { visitExpr(n.left); visitExpr(n.right) }
            is PrivateInExpression -> { ref(n.name, "#" + n.name.name); visitExpr(n.right) }
            is AssignmentExpression -> {
                val t = n.target
                if (t is ObjectPattern || t is ArrayPattern) visitPattern(t, false) else visitExpr(t)
                visitExpr(n.value)
            }
            is ConditionalExpression -> { visitExpr(n.test); visitExpr(n.consequent); visitExpr(n.alternate) }
            is CallExpression -> {
                val c = n.callee
                if (c is Super) {
                    ref(c, "%fn")
                    val nt = MetaProperty("new", "target")
                    nt.start = c.start
                    c.scope = nt
                    ref(nt, "new.target")
                    val th = ThisExpression()
                    th.start = c.start
                    n.ref = th
                    ref(th, "this")
                } else visitExpr(c)
                n.arguments.forEach { visitExpr(it) }
                if (c is Identifier && c.name == "eval" && !n.optional) markDirectEval()
            }
            is NewExpression -> { visitExpr(n.callee); n.arguments.forEach { visitExpr(it) } }
            is MemberExpression -> {
                val o = n.obj
                if (o is Super) {
                    ref(o, "%home")
                    val th = ThisExpression()
                    th.start = o.start
                    n.ref = th
                    ref(th, "this")
                } else visitExpr(o)
                val prop = n.property
                if (n.computed) visitExpr(prop)
                else if (prop is PrivateIdentifier) ref(prop, "#" + prop.name)
            }
            is ChainExpression -> visitExpr(n.expression)
            is SequenceExpression -> n.expressions.forEach { visitExpr(it) }
            is YieldExpression -> visitExpr(n.argument)
            is AwaitExpression -> visitExpr(n.argument)
            is MetaProperty -> if (n.meta == "new") ref(n, "new.target")
            is ImportCall -> { visitExpr(n.source); visitExpr(n.options) }
            is FunctionNode -> visitFunction(n)
            is ClassNode -> visitClass(n)
            is ObjectPattern, is ArrayPattern -> visitPattern(n, false)
            is AssignmentPattern -> visitPattern(n, false)
            else -> throw IllegalStateException("Unknown expression ${n::class.simpleName}")
        }
    }

    private fun markDirectEval() {
        cur.hasEval = true
        evalScopes.add(cur)
        curFn.hasDirectEval = true
        var f: FnInfo? = curFn
        while (f != null) {
            f.containsEval = true
            f = f.parent
        }
        val vs = curFn.varScope
        // global / sloppy-eval code: eval vars go to the global object or the caller's var environment
        if (!curFn.strict && vs.kind != ScopeKind.GLOBAL && !(vs.kind == ScopeKind.EVAL && isSloppyEval)) {
            vs.evalVarTarget = true
            vs.needsEnv = true
        }
    }

    // ================================================================== functions

    private fun visitFunction(fn: FunctionNode) {
        val outerScope = cur
        val outerFn = curFn
        val fi = FnInfo(fn, outerFn)
        allFunctions.add(fi)
        fi.isArrow = fn.kind == FunctionKind.ARROW
        fi.strict = fn.strict
        fi.isAsync = fn.isAsync
        fi.isGenerator = fn.isGenerator
        fi.kind = fn.kind
        curFn = fi
        // callee scope for named function expressions
        val id = fn.id
        if (id != null && !fn.isDeclaration) {
            val cs = Scope(ScopeKind.CALLEE, cur, fi)
            cs.declare(id.name, BKind.CALLEE)
            fi.calleeScope = cs
            cur = cs
        }
        val hasParamExpr = fn.params.any { it !is Identifier }
        fi.hasParamExpressions = hasParamExpr && fn.params.any { containsExpression(it) }
        var pe: Scope? = null
        if (fi.hasParamExpressions && !fn.strict && fn.params.any { containsDirectEval(it) }) {
            pe = Scope(ScopeKind.BLOCK, cur, fi)
            pe.evalVarTarget = true
            pe.needsEnv = true
            fi.paramEvalScope = pe
            register(pe)
            cur = pe
        }
        val fs = Scope(ScopeKind.FUNCTION, cur, fi)
        fn.scope = fs
        fi.scope = fs
        fi.varScope = pe ?: fs
        cur = fs
        if (fn.kind == FunctionKind.DERIVED_CONSTRUCTOR) ensureThisBinding(fi)
        // parameters
        for (p in fn.params) for (pid in boundNames(p)) {
            if (fs.bindings[pid.name] == null) {
                val b = fs.declare(pid.name, BKind.PARAM)
                if (fi.hasParamExpressions) b.tdz = true
            }
        }
        if (fi.hasParamExpressions) {
            for (p in fn.params) visitPattern(p, true)
            val bs = Scope(ScopeKind.BODY, fs, fi)
            fi.varScope = bs
            cur = bs
            if (fn.body is BlockStatement) (fn.body as BlockStatement).scope = bs
        } else {
            for (p in fn.params) visitPattern(p, true)
            fi.varScope = fs
        }
        val body = fn.body
        if (body is BlockStatement) {
            hoistDeclarations(body.body, cur, topLevel = true)
            for (st in body.body) visitStatementInList(st)
        } else {
            visitExpr(body)
        }
        cur = outerScope
        curFn = outerFn
    }

    /** True if the subtree contains a direct eval call (not inside nested functions). */
    private fun containsDirectEval(n: Node?): Boolean {
        var found = false
        fun walk(x: Any?) {
            if (found || x == null) return
            when (x) {
                is FunctionNode, is ClassNode -> return
                is CallExpression -> {
                    val c = x.callee
                    if (c is Identifier && c.name == "eval" && !x.optional) { found = true; return }
                    walk(c); x.arguments.forEach { walk(it) }
                }
                is Node -> for (f in x.javaClass.declaredFields) {
                    if (f.name == "scope" || f.name == "ref") continue
                    f.isAccessible = true
                    val v = f.get(x)
                    if (v is Node) walk(v) else if (v is List<*>) v.forEach { walk(it) }
                }
            }
        }
        walk(n)
        return found
    }

    private fun containsExpression(n: Node?): Boolean = when (n) {
        null -> false
        is Identifier -> false
        is AssignmentPattern -> true
        is RestElement -> containsExpression(n.argument)
        is ArrayPattern -> n.elements.any { containsExpression(it) }
        is ObjectPattern -> n.properties.any { p -> if (p is Property) p.computed || containsExpression(p.value) else containsExpression(p) }
        else -> true
    }

    private fun visitClass(cls: ClassNode) {
        // class decorators are evaluated outside the class scope
        for (d in cls.decorators) visitExpr(d)
        val s = push(ScopeKind.CLASS, cls)
        val id = cls.id
        if (id != null) {
            val b = s.declare(id.name, BKind.CLASS_INNER)
            b.declEnd = cls.end
        }
        // private names
        for (el in cls.body) {
            val key = when (el) {
                is MethodDefinition -> el.key
                is PropertyDefinition -> el.key
                else -> null
            }
            if (key is PrivateIdentifier) {
                val nm = "#" + key.name
                var b = s.bindings[nm]
                if (b == null) {
                    b = s.declare(nm, BKind.PRIVATE)
                    b.captured = true
                    b.privateStatic = when (el) {
                        is MethodDefinition -> el.isStatic
                        is PropertyDefinition -> el.isStatic
                        else -> false
                    }
                }
                val k = when (el) {
                    is MethodDefinition -> when (el.kind) { MethodKind.GET -> "get"; MethodKind.SET -> "set"; else -> "method" }
                    is PropertyDefinition -> if (el.isAccessor) "accessor" else "field"
                    else -> "field"
                }
                b.privateKind = if (b.privateKind == null) k else if ((b.privateKind == "get" && k == "set") || (b.privateKind == "set" && k == "get")) "getset" else k
            }
        }
        visitExpr(cls.superClass)
        for (el in cls.body) {
            when (el) {
                is MethodDefinition -> {
                    for (d in el.decorators) visitExpr(d)
                    if (el.computed) visitExpr(el.key)
                    el.value.classNode = cls
                    visitFunction(el.value)
                }
                is PropertyDefinition -> {
                    for (d in el.decorators) visitExpr(d)
                    if (el.computed) visitExpr(el.key)
                    el.initializer?.let { visitFunction(it) }
                }
                is StaticBlock -> visitFunction(el.function!!)
                else -> {}
            }
        }
        pop(s)
    }

    // ================================================================== resolution

    private fun finish() {
        resolveAnnexB()
        // eval visibility: all bindings in scopes enclosing a direct eval must be env-allocated
        for (es in evalScopes) {
            var s: Scope? = es
            while (s != null) {
                s.allCaptured = true
                s = s.parent
            }
            // eval code may use this / arguments / new.target / super of the enclosing function (eval code nested in
            // eval code resolves them dynamically)
            var f: FnInfo? = es.fn
            while (f != null && f.isArrow) f = f.parent
            if (f != null && !f.isTopLevel) {
                ensureThisBinding(f).captured = true
                ensureFnBindings(f)
                if (f.kind != FunctionKind.CLASS_FIELD_INIT && f.kind != FunctionKind.STATIC_BLOCK) ensureArguments(f, true)
            }
        }
        for (r in refs) resolve(r)
        for (f in allFunctions) allocate(f)
    }

    private fun ensureThisBinding(f: FnInfo): Binding {
        var b = f.thisBinding
        if (b == null) {
            b = Binding("this", BKind.THIS, f.scope)
            f.scope.bindings["this"] = b
            f.thisBinding = b
            b.tdz = f.kind == FunctionKind.DERIVED_CONSTRUCTOR
        }
        return b
    }

    private fun ensureFnBindings(f: FnInfo) {
        if (f.newTargetBinding == null) {
            val b = Binding("new.target", BKind.NEW_TARGET, f.scope)
            f.scope.bindings["new.target"] = b
            f.newTargetBinding = b
            b.captured = true
        }
        if (f.homeBinding == null) {
            val b = Binding("%home", BKind.HOME, f.scope)
            f.scope.bindings["%home"] = b
            f.homeBinding = b
            b.captured = true
        }
        if (f.fnBinding == null) {
            val b = Binding("%fn", BKind.FN, f.scope)
            f.scope.bindings["%fn"] = b
            f.fnBinding = b
            b.captured = true
        }
    }

    private fun ensureArguments(f: FnInfo, capture: Boolean): Binding? {
        val existing = f.scope.bindings["arguments"]
        if (existing != null) {
            when (existing.kind) {
                BKind.ARGUMENTS -> {
                    if (capture) existing.captured = true
                    return existing
                }
                BKind.VAR -> {
                    existing.isArgumentsObject = true
                    f.argumentsBinding = existing
                    f.usesArguments = true
                    if (capture) existing.captured = true
                    return existing
                }
                else -> return existing
            }
        }
        val b = Binding("arguments", BKind.ARGUMENTS, f.scope)
        f.scope.bindings["arguments"] = b
        b.isArgumentsObject = true
        f.argumentsBinding = b
        f.usesArguments = true
        if (capture) b.captured = true
        return b
    }

    private fun resolve(r: RefSite) {
        val name = r.name
        when (name) {
            "this", "new.target", "%home", "%fn" -> { resolvePseudo(r); return }
        }
        var s: Scope? = r.scope
        var crossed = false
        var dynamic = false
        val refFn = r.scope.fn
        while (s != null) {
            if (s.kind == ScopeKind.WITH) dynamic = true
            var b = s.bindings[name]
            if (name == "arguments" && !s.fn.isArrow && !s.fn.isTopLevel &&
                s.fn.kind != FunctionKind.CLASS_FIELD_INIT && s.fn.kind != FunctionKind.STATIC_BLOCK
            ) {
                if (s.kind == ScopeKind.FUNCTION) b = ensureArguments(s.fn, false)
                else if (s.kind == ScopeKind.BODY && b != null && b.kind == BKind.VAR) ensureArguments(s.fn, false)
            }
            if (b != null) {
                if (crossed || dynamic) b.captured = true
                if (s.allCaptured) b.captured = true
                if (dynamic) {
                    r.node.ref = DynamicRef(name)
                    return
                }
                val checkTdz = b.needsTdz && !(b.scope.fn === refFn && b.declEnd >= 0 && r.pos >= b.declEnd && b.scope.kind != ScopeKind.SWITCH && !b.tdz)
                r.node.ref = LocalRef(b, checkTdz)
                return
            }
            if (s.evalVarTarget) dynamic = true
            if (s.kind == ScopeKind.FUNCTION && s.parent != null && s.parent.fn !== s.fn) crossed = true
            if (s.kind == ScopeKind.CALLEE) crossed = true
            if (s.kind == ScopeKind.FUNCTION && s.parent == null) crossed = true
            s = s.parent
        }
        // unresolved
        if (dynamic || mode == CodeMode.EVAL_DIRECT) r.node.ref = DynamicRef(name)
        else r.node.ref = GlobalRef(name)
    }

    private fun resolvePseudo(r: RefSite) {
        var f: FnInfo? = r.scope.fn
        var crossed = false
        while (f != null && f.isArrow) {
            f = f.parent
            crossed = true
        }
        if (f == null || f.isTopLevel) {
            // global / module / eval top level
            r.node.ref = if (mode == CodeMode.EVAL_DIRECT) DynamicRef(r.name) else null
            if (f != null && r.name == "this") f.usesThis = true
            return
        }
        val b = when (r.name) {
            "this" -> { f.usesThis = true; ensureThisBinding(f) }
            "new.target" -> {
                var nb = f.newTargetBinding
                if (nb == null) {
                    nb = Binding("new.target", BKind.NEW_TARGET, f.scope)
                    f.scope.bindings["new.target"] = nb
                    f.newTargetBinding = nb
                }
                nb
            }
            "%home" -> {
                var hb = f.homeBinding
                if (hb == null) {
                    hb = Binding("%home", BKind.HOME, f.scope)
                    f.scope.bindings["%home"] = hb
                    f.homeBinding = hb
                }
                hb
            }
            else -> {
                var fb = f.fnBinding
                if (fb == null) {
                    fb = Binding("%fn", BKind.FN, f.scope)
                    f.scope.bindings["%fn"] = fb
                    f.fnBinding = fb
                }
                fb
            }
        }
        if (crossed) b.captured = true
        if (f.scope.allCaptured) b.captured = true
        r.node.ref = LocalRef(b, b.tdz)
    }

    /** Annex B.3.3: block function declarations also create a var binding when no conflicts exist. */
    private fun resolveAnnexB() {
        for ((decl, blockScope, blockBinding) in blockFunctions) {
            val name = decl.function.id!!.name
            val fn = blockScope.fn
            val vs = fn.varScope
            // would `var name` conflict with a lexical declaration in an enclosing scope (excluding the block itself)?
            var s: Scope? = blockScope.parent
            var conflict = false
            while (s != null) {
                val b = s.bindings[name]
                if (b != null && (b.kind.isLexical || b.kind == BKind.BLOCK_FUNCTION) && s !== vs) { conflict = true; break }
                if (b != null && s === vs && (b.kind.isLexical)) { conflict = true; break }
                if (s.kind == ScopeKind.CATCH && b != null && b.kind == BKind.CATCH) {
                    // simple catch parameter: var redeclaration allowed (B.3.4), pattern params conflict
                    val h = findCatchParamIsPattern(s)
                    if (h) { conflict = true; break }
                }
                if (s === vs) break
                s = s.parent
            }
            if (conflict) continue
            if (vs.kind == ScopeKind.GLOBAL || (vs.kind == ScopeKind.EVAL && isSloppyEval)) {
                if (globalLexNames.containsKey(name)) continue
                annexBGlobalNames.add(name)
                globalVarNames.add(name)
                blockBinding.annexBVar = name
                continue
            }
            // parameter names are not overridden
            val fnScope = fn.scope
            val pb = fnScope.bindings[name]
            if (pb != null && pb.kind == BKind.PARAM) continue
            if (name == "arguments" && !fn.isArrow && !fn.isTopLevel) {
                // "arguments" is not hoisted if it would shadow the arguments object (it is then one of the
                // parameterNames of FunctionDeclarationInstantiation)
                val existing = vs.bindings[name]
                if (existing == null) continue
            }
            var vb = vs.bindings[name]
            if (vb == null) vb = vs.declare(name, BKind.VAR)
            if (vb.kind.isLexical) continue
            blockBinding.annexBVar = vb
        }
    }

    private val catchPatternScopes = HashSet<Scope>()
    private fun findCatchParamIsPattern(s: Scope): Boolean = catchPatternScopes.contains(s)

    // ================================================================== allocation

    private fun allocate(f: FnInfo) {
        var reg = 0
        fun alloc(s: Scope) {
            if (s.allCaptured || s.kind == ScopeKind.MODULE) for (b in s.bindings.values) b.captured = true
            var slot = 0
            for (b in s.bindings.values) {
                if (b.kind == BKind.PRIVATE) b.captured = true
                if (b.captured) b.slot = slot++ else b.reg = reg++
            }
            s.envSize = slot
            if (slot > 0) s.needsEnv = true
            if (s.kind == ScopeKind.WITH || s.kind == ScopeKind.MODULE) s.needsEnv = true
            if (s.needsEnv) s.info = ScopeInfo.from(s)
        }
        // mapped arguments: parameters must be env-allocated so the arguments object can alias them
        if (f.usesArguments && !f.strict && !f.isArrow && (f.node as? FunctionNode)?.hasSimpleParams == true) {
            f.mappedArguments = true
            for (b in f.scope.bindings.values) if (b.kind == BKind.PARAM) b.captured = true
        }
        val scopes = ArrayList<Scope>()
        collectScopes(f, scopes)
        for (s in scopes) alloc(s)
        f.numRegs = reg
    }

    /** Collects scopes belonging to function f (not nested functions) in creation order. */
    private fun collectScopes(f: FnInfo, out: MutableList<Scope>) {
        val all = LinkedHashSet<Scope>()
        f.calleeScope?.let { all.add(it) }
        f.paramEvalScope?.let { all.add(it) }
        all.add(f.scope)
        all.add(f.varScope)
        for (r in scopesByFn[f] ?: emptyList()) all.add(r)
        out.addAll(all)
    }

    private val scopesByFn = HashMap<FnInfo, MutableList<Scope>>()


    /** Registers every created scope by function (called through push). */
    private fun register(s: Scope) {
        scopesByFn.getOrPut(s.fn) { ArrayList() }.add(s)
    }
}
