@file:OptIn(LintTreeApi::class)

package dev.mooner.neonjs.lint

import dev.mooner.neonjs.compiler.BKind
import dev.mooner.neonjs.compiler.DynamicRef
import dev.mooner.neonjs.compiler.GlobalRef
import dev.mooner.neonjs.compiler.LocalRef
import dev.mooner.neonjs.compiler.Scope
import dev.mooner.neonjs.compiler.ScopeAnalyzer
import dev.mooner.neonjs.parser.*
import dev.mooner.neonjs.runtime.NumberConv

/**
 * A lint rule: reads the tree and the scope analysis, and reports what it finds. The built-in ones are [BuiltinRules];
 * a host adds its own through [LintOptions.extraRules]. (What it reads is marked [LintTreeApi].)
 */
interface LintRule {
    /** The code of its diagnostics: stable, for settings and translations. */
    val code: String
    val defaultSeverity: Severity
    /** What its messages' arguments are, in order. */
    val args: List<String> get() = emptyList()
    val description: String get() = ""

    fun check(context: LintContext)
}

/**
 * What a rule reads, and where it reports. The scope analysis is the compiler's ([ScopeAnalyzer], run on the tree
 * with the source's type): each identifier's `ref` is a LocalRef, GlobalRef or DynamicRef. In a script, the top-level
 * declarations are no bindings ([isScriptTopLevel]).
 */
class LintContext internal constructor(
    val source: String,
    val sourceType: SourceType,
    /** The tree, walked once: each node with its context. */
    @property:LintTreeApi val tree: Tree,
    /** The scope analysis of [program]: each identifier's `ref`, the script's top-level names. */
    @property:LintTreeApi val scopes: ScopeAnalyzer,
    val globals: Globals?,
    internal val facts: Facts,
    private val emit: (start: Int, end: Int, message: String, args: List<String>, related: List<Related>) -> Unit,
) {
    /** The parsed source, with an ErrorNode for each statement the parser could not read. */
    @LintTreeApi
    val program: Program get() = tree.program

    /** Reports a finding of the rule over [start, end). */
    fun report(start: Int, end: Int, message: String, args: List<String> = emptyList(), related: List<Related> = emptyList()) =
        emit(start, end, message, args, related)

    @LintTreeApi
    fun report(node: Node, message: String, args: List<String> = emptyList(), related: List<Related> = emptyList()) =
        emit(node.start, node.end, message, args, related)

    /** Whether [name] is declared at a script's top level (var, let, const, class, function): no binding has it. */
    @LintTreeApi
    fun isScriptTopLevel(name: String): Boolean =
        sourceType == SourceType.SCRIPT && (name in scopes.globalVarNames || scopes.globalLexNames.containsKey(name))
}

/** What several rules use, found once. */
internal class Facts(val tree: Tree, val sourceType: SourceType, val scopes: ScopeAnalyzer) {
    /** A script's top-level let/const/class: where its declaration ends (until there, it is in its TDZ). */
    val topLexicalEnd = HashMap<String, Int>()
    val topFunctions = HashSet<String>()
    val topClasses = HashSet<String>()
    /** Per function (or program), each var's first declaring identifier. */
    val varStart = HashMap<Node, HashMap<String, Int>>()
    /** The nodes with one of the recovery mode's ErrorNodes in them (an expression missing, a statement it could not read). */
    private val withErrorNodes: Set<Node> = run {
        val visitOf = java.util.IdentityHashMap<Node, Visit>()
        for (v in tree.visits) visitOf[v.node] = v
        val out = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Node, Boolean>())
        for (v in tree.visits) if (v.node is ErrorNode) {
            var at: Visit? = v
            while (at != null && out.add(at.node)) at = at.parent?.let { visitOf[it] }
        }
        out
    }

    /** Whether [n] has an ErrorNode in it: two such are never the same (one stands for something missing). */
    fun hasErrorNode(n: Node): Boolean = n in withErrorNodes
    /**
     * Where a declared name is initialized in its pattern, and the pattern: in the pattern, a name is initialized once
     * its element (with its default) is bound (`let [a, b = a] = x` reads `a` initialized, `let { x = x } = o` does
     * not), elsewhere once the declaration ends.
     */
    class Declared(val initializedAt: Int, val patternStart: Int, val patternEnd: Int)
    val declared = java.util.IdentityHashMap<dev.mooner.neonjs.compiler.Binding, Declared>()
    val topDeclared = HashMap<String, Declared>()

    init {
        if (sourceType == SourceType.SCRIPT) for (st in tree.program.body) when (st) {
            is VariableDeclaration -> if (st.kind != VarKind.VAR) for (d in st.declarations) for (id in boundNames(d.id)) topLexicalEnd[id.name] = d.end
            is ClassDeclaration -> { st.cls.id?.let { topLexicalEnd[it.name] = st.end; topClasses.add(it.name) } }
            is FunctionDeclaration -> st.function.id?.let { topFunctions.add(it.name) }
            else -> {}
        }
        for (v in tree.visits) {
            val n = v.node
            if (n is VariableDeclaration && n.kind == VarKind.VAR) {
                val m = varStart.getOrPut(v.function) { HashMap() }
                for (d in n.declarations) for (id in boundNames(d.id)) m.putIfAbsent(id.name, id.start)
            }
            if (n is VariableDeclarator) for ((id, elementEnd) in boundElements(n.id)) {
                val info = Declared(elementEnd, n.id.start, n.id.end)
                when (val r = id.ref) {
                    is LocalRef -> declared.putIfAbsent(r.binding, info)
                    is GlobalRef -> if (v.function === tree.program) topDeclared.putIfAbsent(r.name, info)
                    else -> {}
                }
            }
        }
    }

    companion object {
        /** The names a pattern binds, each with the end of its element (its default's, when it has one). */
        fun boundElements(n: Node?, out: MutableList<Pair<Identifier, Int>> = ArrayList(), end: Int = -1): List<Pair<Identifier, Int>> {
            when (n) {
                is Identifier -> out.add(n to (if (end >= 0) end else n.end))
                is AssignmentPattern -> boundElements(n.left, out, n.end)
                is RestElement -> boundElements(n.argument, out)
                is ArrayPattern -> n.elements.forEach { boundElements(it, out) }
                is ObjectPattern -> n.properties.forEach { boundElements(if (it is Property) it.value else it, out) }
                else -> {}
            }
            return out
        }

        fun boundNames(n: Node?, out: MutableList<Identifier> = ArrayList()): List<Identifier> {
            when (n) {
                is Identifier -> out.add(n)
                is AssignmentPattern -> boundNames(n.left, out)
                is RestElement -> boundNames(n.argument, out)
                is ArrayPattern -> n.elements.forEach { boundNames(it, out) }
                is ObjectPattern -> n.properties.forEach { boundNames(if (it is Property) it.value else it, out) }
                else -> {}
            }
            return out
        }
    }
}

/** The built-in rules: the mistakes that are valid code a host's editor tells of (no style). */
internal object BuiltinRules {

    private fun rule(code: String, severity: Severity, args: List<String>, description: String, check: (LintContext) -> Unit) =
        object : LintRule {
            override val code = code
            override val defaultSeverity = severity
            override val args = args
            override val description = description
            override fun check(context: LintContext) = check(context)
        }

    private val Visit.id get() = node as Identifier

    /** Whether a reference ([Role.READ], [Role.TYPEOF], [Role.WRITE]). */
    private val Visit.isReference get() = role == Role.READ || role == Role.TYPEOF || role == Role.WRITE

    val all: List<LintRule> = listOf(
        rule("const-assign", Severity.ERROR, listOf("name"), "Assigning a constant (const, an import, using, a class's own name inside it): a TypeError when it runs.") { c ->
            for (v in c.tree.identifiers) {
                if (v.role != Role.WRITE) continue
                val id = v.id
                val message = when (val r = id.ref) {
                    is LocalRef -> when (r.binding.kind) {
                        BKind.CONST, BKind.USING -> "'${id.name}' is constant."
                        BKind.IMPORT -> "'${id.name}' is an import: it is read-only."
                        BKind.CLASS_INNER -> "'${id.name}' is constant inside its class."
                        else -> null
                    }
                    is GlobalRef -> if (c.sourceType == SourceType.SCRIPT && c.scopes.globalLexNames[r.name] == true) "'${id.name}' is constant." else null
                    else -> null
                }
                if (message != null) c.report(id, message, listOf(id.name))
            }
        },
        rule("use-before-define", Severity.ERROR, listOf("name"), "A let, const or class used before its declaration in the same function: a ReferenceError when it runs.") { c ->
            for (v in c.tree.identifiers) {
                if (!v.isReference) continue
                val id = v.id
                when (val r = id.ref) {
                    is LocalRef -> {
                        val b = r.binding
                        val lexical = b.kind == BKind.LET || b.kind == BKind.CONST || b.kind == BKind.CLASS || b.kind == BKind.CLASS_INNER || b.kind == BKind.USING
                        if (lexical && b.declEnd >= 0 && id.start < b.declEnd && b.scope.fn.node === v.function && b.scope.kind != dev.mooner.neonjs.compiler.ScopeKind.SWITCH &&
                            beforeInitialized(id, c.facts.declared[b])) {
                            c.report(id, "'${id.name}' is used before its declaration.", listOf(id.name))
                        }
                    }
                    is GlobalRef -> {
                        val end = c.facts.topLexicalEnd[r.name] ?: continue
                        if (v.function === c.program && id.start < end && beforeInitialized(id, c.facts.topDeclared[r.name])) {
                            c.report(id, "'${id.name}' is used before its declaration.", listOf(id.name))
                        }
                    }
                    else -> {}
                }
            }
        },
        rule("var-before-define", Severity.WARNING, listOf("name"), "A var used before its declaration in the same function: it is undefined there.") { c ->
            for (v in c.tree.identifiers) {
                if (!v.isReference) continue
                val id = v.id
                val isVar = when (val r = id.ref) {
                    is LocalRef -> r.binding.kind == BKind.VAR
                    is GlobalRef -> c.sourceType == SourceType.SCRIPT && r.name in c.scopes.globalVarNames
                    else -> false
                }
                if (!isVar) continue
                val declared = c.facts.varStart[v.function]?.get(id.name) ?: continue
                if (id.start < declared) c.report(id, "'${id.name}' is used before its var declaration: it is undefined there.", listOf(id.name))
            }
        },
        rule("this-before-super", Severity.ERROR, listOf("kind"), "`this` or `super.x` in a derived class's constructor before `super()`: a ReferenceError when it runs.") { c ->
            for (ctor in derivedConstructors(c)) {
                if (containsErrorNode(c, ctor)) continue
                val calls = superCalls(c, ctor, arrowsToo = false)
                val first = calls.minByOrNull { it.start } ?: continue
                val body = (ctor.body as? BlockStatement)?.body ?: continue
                val statement = body.firstOrNull { first.start >= it.start && first.start < it.end } ?: continue
                for (v in c.tree.visits) {
                    if (v.function !== ctor) continue
                    val n = v.node
                    val kind = when {
                        n is ThisExpression -> "this"
                        n is MemberExpression && n.obj is Super -> "super"
                        else -> continue
                    }
                    val before = n.start < statement.start || (n.start > first.start && n.end <= first.end && first.arguments.any { n.start >= it.start && n.end <= it.end })
                    if (before) c.report(n.start, if (n is MemberExpression) n.obj.end else n.end, "'$kind' is not allowed before 'super()'.", listOf(kind))
                }
            }
        },
        rule("missing-super-call", Severity.ERROR, emptyList(), "A derived class's constructor that never calls `super()` (nor returns an object): a ReferenceError when it runs.") { c ->
            for (ctor in derivedConstructors(c)) {
                if (containsErrorNode(c, ctor)) continue
                if (superCalls(c, ctor, arrowsToo = true).isNotEmpty()) continue
                if (c.tree.visits.any { it.function === ctor && it.node is ReturnStatement && (it.node as ReturnStatement).argument != null }) continue
                // (a constructor that always throws never needs `this`)
                if (throws(ctor.body)) continue
                val member = c.tree.visits.firstOrNull { it.node is MethodDefinition && (it.node as MethodDefinition).value === ctor }?.node as MethodDefinition?
                val at = member?.key ?: ctor
                c.report(at, "A derived class's constructor must call 'super()'.")
            }
        },
        rule("undefined-name", Severity.WARNING, listOf("name"), "A name declared nowhere, and no global the host knows (off when the host gives no globals).") { c ->
            val globals = c.globals ?: return@rule
            for (v in c.tree.identifiers) {
                if (v.role != Role.READ && v.role != Role.WRITE) continue
                val r = v.id.ref as? GlobalRef ?: continue
                if (r.name.startsWith("#") || c.isScriptTopLevel(r.name) || globals.kind(r.name) != null) continue
                c.report(v.node, "'${r.name}' is not defined.", listOf(r.name))
            }
        },
        rule("readonly-global-assign", Severity.WARNING, listOf("name"), "Assigning a global the host gives as read-only.") { c ->
            val globals = c.globals ?: return@rule
            for (v in c.tree.identifiers) {
                if (v.role != Role.WRITE) continue
                val r = v.id.ref as? GlobalRef ?: continue
                if (!c.isScriptTopLevel(r.name) && globals.kind(r.name) == GlobalKind.READ_ONLY) c.report(v.node, "'${r.name}' is a read-only global.", listOf(r.name))
            }
        },
        rule("class-assign", Severity.WARNING, listOf("name"), "Assigning a class declaration's name.") { c ->
            for (v in c.tree.identifiers) {
                if (v.role != Role.WRITE) continue
                val isClass = when (val r = v.id.ref) {
                    is LocalRef -> r.binding.kind == BKind.CLASS
                    is GlobalRef -> r.name in c.facts.topClasses
                    else -> false
                }
                if (isClass) c.report(v.node, "'${v.id.name}' is a class.", listOf(v.id.name))
            }
        },
        rule("func-assign", Severity.WARNING, listOf("name"), "Assigning a function declaration's name, or a function expression's own name.") { c ->
            for (v in c.tree.identifiers) {
                if (v.role != Role.WRITE) continue
                val message = when (val r = v.id.ref) {
                    is LocalRef -> when (r.binding.kind) {
                        BKind.FUNCTION, BKind.BLOCK_FUNCTION -> "'${v.id.name}' is a function."
                        BKind.CALLEE -> "'${v.id.name}' is the function's own name: assigning it does nothing (a TypeError in strict code)."
                        else -> null
                    }
                    is GlobalRef -> if (r.name in c.facts.topFunctions && !c.scopes.globalLexNames.containsKey(r.name)) "'${v.id.name}' is a function." else null
                    else -> null
                }
                if (message != null) c.report(v.node, message, listOf(v.id.name))
            }
        },
        rule("cond-assign", Severity.WARNING, emptyList(), "An assignment as a condition (`=` for `===`?): in parentheses, it is taken as meant.") { c ->
            for (v in c.tree.visits) {
                val test = when (val n = v.node) {
                    is IfStatement -> n.test
                    is WhileStatement -> n.test
                    is DoWhileStatement -> n.test
                    is ForStatement -> n.test
                    is ConditionalExpression -> n.test
                    else -> null
                }
                // (a conditional has no parentheses of its own: an assignment as its test needs two pairs to be meant)
                val meant = test is AssignmentExpression && test.parenthesized &&
                    (v.node !is ConditionalExpression || c.source.substring(v.node.start, test.start).count { it == '(' } >= 2)
                if (test is AssignmentExpression && !meant) c.report(test, "Expected a condition, and found an assignment.")
            }
        },
        rule("unreachable", Severity.WARNING, emptyList(), "Code after `return`, `throw`, `break` or `continue`.") { c ->
            val reported = ArrayList<IntArray>()
            for (list in c.tree.statementLists) {
                if (list.isEmpty() || reported.any { list[0].start >= it[0] && list[0].start < it[1] }) continue
                val first = list.indexOfFirst { abrupt(it) }
                if (first < 0) continue
                var from = -1
                var to = -1
                fun flush() {
                    if (from >= 0) { c.report(from, to, "Unreachable code."); reported.add(intArrayOf(from, to)) }
                    from = -1
                }
                for (i in first + 1 until list.size) {
                    val s = list[i]
                    val skip = s is FunctionDeclaration || s is EmptyStatement || s is ErrorNode || s is ImportDeclaration ||
                        (s is VariableDeclaration && s.kind == VarKind.VAR && s.declarations.all { it.init == null })
                    if (skip) { flush(); continue }
                    if (from < 0) from = s.start
                    to = s.end
                }
                flush()
            }
        },
        rule("fallthrough", Severity.WARNING, listOf("keyword"), "A case that goes on into the next (a comment saying `falls through` allows it).") { c ->
            for (v in c.tree.visits) {
                val sw = v.node as? SwitchStatement ?: continue
                for (i in 0 until sw.cases.size - 1) {
                    val case = sw.cases[i]
                    if (case.consequent.isEmpty() || case.consequent.any { abrupt(it) || it is ErrorNode }) continue
                    val next = sw.cases[i + 1]
                    val between = c.source.substring(case.consequent.last().start, next.start)
                    if (FALLS_THROUGH.containsMatchIn(between)) continue
                    val keyword = if (next.test == null) "default" else "case"
                    c.report(next.start, next.start + keyword.length, "Expected a 'break' statement before '$keyword'.", listOf(keyword))
                }
            }
        },
        rule("duplicate-key", Severity.WARNING, listOf("name"), "An object literal's key twice (the later value wins).") { c ->
            for (v in c.tree.visits) {
                val o = v.node as? ObjectLiteral ?: continue
                val seen = HashMap<String, MutableSet<PropKind>>()
                for (p in o.properties) {
                    if (p !is Property) continue
                    val name = staticName(p.key, p.computed) ?: continue
                    if (!p.computed && !p.shorthand && !p.method && p.kind == PropKind.INIT && name == "__proto__") continue
                    val kinds = seen.getOrPut(name) { HashSet() }
                    if (duplicate(kinds, p.kind)) c.report(p.key, "Duplicate key '$name'.", listOf(name))
                    kinds.add(p.kind)
                }
            }
        },
        rule("duplicate-param", Severity.WARNING, listOf("name"), "A parameter name twice (sloppy code allows it; the later wins).") { c ->
            for (v in c.tree.visits) {
                val f = v.node as? FunctionNode ?: continue
                if (f.kind != FunctionKind.NORMAL) continue
                val seen = HashSet<String>()
                for (p in f.params) if (p is Identifier && !seen.add(p.name)) c.report(p, "Duplicate parameter '${p.name}'.", listOf(p.name))
            }
        },
        rule("duplicate-class-member", Severity.WARNING, listOf("name"), "A class member's name twice (the later wins).") { c ->
            for (v in c.tree.visits) {
                val cls = v.node as? ClassNode ?: continue
                val seen = HashMap<String, MutableSet<PropKind>>()
                for (m in cls.body) {
                    val (key, computed, static, kind) = when (m) {
                        is MethodDefinition -> {
                            if (m.kind == MethodKind.CONSTRUCTOR) continue
                            Member(m.key, m.computed, m.isStatic, when (m.kind) { MethodKind.GET -> PropKind.GET; MethodKind.SET -> PropKind.SET; else -> PropKind.INIT })
                        }
                        is PropertyDefinition -> Member(m.key, m.computed, m.isStatic, PropKind.INIT)
                        else -> continue
                    }
                    if (key is PrivateIdentifier) continue
                    val name = staticName(key, computed) ?: continue
                    val kinds = seen.getOrPut((if (static) "static " else "") + name) { HashSet() }
                    if (duplicate(kinds, kind)) c.report(key, "Duplicate class member '$name'.", listOf(name))
                    kinds.add(kind)
                }
            }
        },
        rule("duplicate-case", Severity.WARNING, emptyList(), "A switch's case test twice (the later case is never reached by it).") { c ->
            for (v in c.tree.visits) {
                val sw = v.node as? SwitchStatement ?: continue
                val seen = HashSet<String>()
                for (case in sw.cases) {
                    val test = case.test ?: continue
                    if (c.facts.hasErrorNode(test)) continue
                    if (!seen.add(tokens(c.source, test))) c.report(test, "Duplicate case label.")
                }
            }
        },
        rule("duplicate-else-if", Severity.WARNING, emptyList(), "An `else if` whose condition an earlier one in the chain already covers (it can never run).") { c ->
            for (v in c.tree.visits) {
                val head = v.node as? IfStatement ?: continue
                if ((v.parent as? IfStatement)?.alternate === head) continue
                val earlier = ArrayList<List<Set<String>>>()
                var cur: IfStatement? = head
                while (cur != null) {
                    val test = cur.test
                    if (c.facts.hasErrorNode(test)) break
                    val candidates = listOf(test) + (if (test is LogicalExpression && test.op == "&&") splitAnd(test) else emptyList())
                    val covered = candidates.any { cand ->
                        var groups = splitOr(cand).map { g -> splitAnd(g).mapTo(HashSet()) { tokens(c.source, it) } }
                        for (e in earlier) groups = groups.filter { g -> e.none { eg -> g.containsAll(eg) } }
                        groups.isEmpty()
                    }
                    if (covered && earlier.isNotEmpty()) c.report(test, "This branch can never run: its condition is a duplicate of, or covered by, an earlier one in the chain.")
                    earlier.add(splitOr(test).map { g -> splitAnd(g).mapTo(HashSet()) { tokens(c.source, it) } })
                    cur = cur.alternate as? IfStatement
                }
            }
        },
        rule("self-assign", Severity.WARNING, listOf("name"), "Assigning something to itself.") { c ->
            for (v in c.tree.visits) {
                val a = v.node as? AssignmentExpression ?: continue
                if (a.op != "=" && a.op != "&&=" && a.op != "||=" && a.op != "??=") continue
                selfAssigned(a.target, a.value) { n -> val t = c.source.substring(n.start, n.end).filterNot { it.isWhitespace() }; c.report(n, "'$t' is assigned to itself.", listOf(t)) }
            }
        },
        rule("nan-comparison", Severity.WARNING, emptyList(), "Comparing with NaN, which equals nothing (use Number.isNaN).") { c ->
            for (v in c.tree.visits) {
                when (val n = v.node) {
                    is BinaryExpression -> if (n.op in COMPARISONS && (isNaN(n.left) || isNaN(n.right))) c.report(n, "Comparing with NaN is never true: use Number.isNaN.")
                    is SwitchStatement -> {
                        if (isNaN(n.discriminant)) c.report(n.discriminant, "'switch (NaN)' can never match a case: use Number.isNaN instead of the switch.")
                        for (case in n.cases) if (case.test != null && isNaN(case.test!!)) c.report(case.test!!, "'case NaN' can never match: use Number.isNaN before the switch.")
                    }
                    else -> {}
                }
            }
        },
        rule("invalid-typeof", Severity.WARNING, listOf("value"), "A typeof compared with a string typeof never gives.") { c ->
            for (v in c.tree.visits) {
                val n = v.node as? BinaryExpression ?: continue
                if (n.op != "==" && n.op != "===" && n.op != "!=" && n.op != "!==") continue
                val other = when {
                    n.left is UnaryExpression && (n.left as UnaryExpression).op == "typeof" -> n.right
                    n.right is UnaryExpression && (n.right as UnaryExpression).op == "typeof" -> n.left
                    else -> continue
                }
                val value = when {
                    other is StringLiteral -> other.value
                    other is TemplateLiteral && other.expressions.isEmpty() -> other.quasis[0].cooked ?: continue
                    other is Identifier && other.name == "undefined" && other.ref is GlobalRef -> "undefined (the value)"
                    else -> continue
                }
                if (value !in TYPEOF_VALUES) c.report(other, "Invalid typeof comparison value.", listOf(value))
            }
        },
        rule("unsafe-negation", Severity.WARNING, listOf("operator"), "`!a in b` or `!a instanceof b`: the `!` applies to `a` alone.") { c ->
            for (v in c.tree.visits) {
                val n = v.node as? BinaryExpression ?: continue
                if (n.op != "in" && n.op != "instanceof") continue
                val left = n.left
                if (left is UnaryExpression && left.op == "!" && !left.parenthesized) c.report(left, "'!' negates the left operand of '${n.op}', not the result.", listOf(n.op))
            }
        },
        rule("debugger", Severity.WARNING, emptyList(), "A `debugger` statement.") { c ->
            for (v in c.tree.visits) if (v.node is DebuggerStatement) c.report(v.node, "Unexpected 'debugger' statement.")
        },
    )

    private data class Member(val key: Node, val computed: Boolean, val static: Boolean, val kind: PropKind)

    /** Whether a reference before its declaration's end reads the name uninitialized (see [Facts.Declared]). */
    private fun beforeInitialized(id: Identifier, d: Facts.Declared?): Boolean =
        d == null || id.start < d.patternStart || id.start >= d.patternEnd || id.start < d.initializedAt

    /** Whether a statement always ends by throwing. */
    private fun throws(n: Node?): Boolean = when (n) {
        is ThrowStatement -> true
        is BlockStatement -> n.body.any { throws(it) }
        is IfStatement -> n.alternate != null && throws(n.consequent) && throws(n.alternate)
        is TryStatement -> (throws(n.block) && (n.handler == null || throws(n.handler!!.body))) || (n.finalizer != null && throws(n.finalizer))
        else -> false
    }

    /** The JavaScript strings a comment allowing a case's fall through says. */
    private val FALLS_THROUGH = Regex("(//|/\\*).*falls?\\s?through", RegexOption.IGNORE_CASE)
    private val COMPARISONS = setOf("<", "<=", ">", ">=", "==", "===", "!=", "!==")
    private val TYPEOF_VALUES = setOf("symbol", "undefined", "object", "boolean", "number", "string", "function", "bigint")

    /** Whether a statement always ends abruptly (return, throw, break, continue; through blocks, ifs and trys). */
    fun abrupt(n: Node?): Boolean = when (n) {
        is ReturnStatement, is ThrowStatement, is BreakStatement, is ContinueStatement -> true
        is BlockStatement -> n.body.any { abrupt(it) }
        is IfStatement -> n.alternate != null && abrupt(n.consequent) && abrupt(n.alternate)
        is TryStatement -> (abrupt(n.block) && (n.handler == null || abrupt(n.handler!!.body))) || (n.finalizer != null && abrupt(n.finalizer))
        else -> false
    }

    private fun duplicate(seen: Set<PropKind>, kind: PropKind): Boolean = when (kind) {
        PropKind.INIT -> seen.isNotEmpty()
        PropKind.GET -> PropKind.INIT in seen || PropKind.GET in seen
        PropKind.SET -> PropKind.INIT in seen || PropKind.SET in seen
    }

    /** A key's name as a string, when it is one without evaluating anything. */
    private fun staticName(key: Node, computed: Boolean): String? = when {
        !computed && key is Identifier -> key.name
        key is StringLiteral -> key.value
        key is NumberLiteral -> NumberConv.toString(key.value)
        key is BigIntLiteral -> key.value.toString()
        key is TemplateLiteral && key.expressions.isEmpty() -> key.quasis[0].cooked
        else -> null
    }

    /** A node's tokens as one string (spaces and comments left out), to compare two expressions. */
    private fun tokens(src: String, n: Node): String {
        val text = src.substring(n.start, n.end)
        return try {
            val lex = Lexer(text)
            val sb = StringBuilder()
            lex.next()
            while (lex.type != T.EOF) {
                sb.append(text, lex.start, lex.end).append('\u0000')
                lex.next()
            }
            sb.toString()
        } catch (e: JSSyntaxError) {
            text.filterNot { it.isWhitespace() }
        }
    }

    private fun splitOr(n: Node): List<Node> = if (n is LogicalExpression && n.op == "||") splitOr(n.left) + splitOr(n.right) else listOf(n)
    private fun splitAnd(n: Node): List<Node> = if (n is LogicalExpression && n.op == "&&") splitAnd(n.left) + splitAnd(n.right) else listOf(n)

    /** Reports each part of [value] the same as its place in [target]. */
    private fun selfAssigned(target: Node, value: Node, report: (Node) -> Unit) {
        when {
            target is Identifier && value is Identifier -> if (target.name == value.name && !value.parenthesized) report(value)
            target is MemberExpression && value is MemberExpression -> if (sameReference(target, value)) report(value)
            target is ArrayPattern && value is ArrayLiteral -> {
                for (i in 0 until minOf(target.elements.size, value.elements.size)) {
                    val t = target.elements[i] ?: continue
                    val v = value.elements[i] ?: continue
                    if (v is SpreadElement) break
                    if (t is RestElement) break
                    selfAssigned(t, v, report)
                }
            }
            else -> {}
        }
    }

    private fun sameReference(a: Node, b: Node): Boolean = when {
        a is Identifier && b is Identifier -> a.name == b.name
        a is ThisExpression && b is ThisExpression -> true
        a is MemberExpression && b is MemberExpression -> {
            val ka = if (a.computed) staticName(a.property, true) ?: (a.property as? Identifier)?.let { "\u0000" + it.name } else (a.property as? Identifier)?.name ?: (a.property as? PrivateIdentifier)?.let { "#" + it.name }
            val kb = if (b.computed) staticName(b.property, true) ?: (b.property as? Identifier)?.let { "\u0000" + it.name } else (b.property as? Identifier)?.name ?: (b.property as? PrivateIdentifier)?.let { "#" + it.name }
            ka != null && ka == kb && sameReference(a.obj, b.obj)
        }
        else -> false
    }

    private fun isNaN(n: Node): Boolean = when {
        n is Identifier -> n.name == "NaN" && n.ref is GlobalRef
        n is MemberExpression -> !n.computed && (n.property as? Identifier)?.name == "NaN" && (n.obj as? Identifier)?.let { it.name == "Number" && it.ref is GlobalRef } == true
        n is SequenceExpression -> isNaN(n.expressions.last())
        else -> false
    }

    /**
     * The constructors of the classes with an `extends` (not `null`): the FunctionNodes. Not one with a direct eval,
     * which may call `super()` where no rule sees it.
     */
    private fun derivedConstructors(c: LintContext): List<FunctionNode> = c.tree.visits.mapNotNull { v ->
        val cls = v.node as? ClassNode ?: return@mapNotNull null
        val sup = cls.superClass ?: return@mapNotNull null
        if (sup is NullLiteral || sup is NumberLiteral || sup is StringLiteral || sup is BooleanLiteral) return@mapNotNull null
        val ctor = cls.constructor?.takeIf { it.kind == FunctionKind.DERIVED_CONSTRUCTOR } ?: return@mapNotNull null
        if ((ctor.scope as? Scope)?.fn?.containsEval == true) return@mapNotNull null
        ctor
    }

    /** `super(...)` calls in [ctor]'s code ([arrowsToo]: and in its arrow functions'), not in a nested class's. */
    private fun superCalls(c: LintContext, ctor: FunctionNode, arrowsToo: Boolean): List<CallExpression> = c.tree.visits.mapNotNull { v ->
        val call = v.node as? CallExpression ?: return@mapNotNull null
        if (call.callee !is Super) return@mapNotNull null
        val f = v.function
        val mine = f === ctor || (arrowsToo && f is FunctionNode && f.kind == FunctionKind.ARROW && call.start >= ctor.start && call.end <= ctor.end && enclosingNonArrow(c, f) === ctor)
        if (mine) call else null
    }

    private fun enclosingNonArrow(c: LintContext, arrow: FunctionNode): Node? {
        var f: Node? = arrow
        while (f is FunctionNode && f.kind == FunctionKind.ARROW) {
            val scope = f.scope as? Scope ?: return null
            f = scope.fn.parent?.node
        }
        return f
    }

    private fun containsErrorNode(c: LintContext, f: FunctionNode): Boolean =
        c.tree.visits.any { it.node is ErrorNode && it.node.start >= f.start && it.node.end <= f.end }

    @Suppress("unused")
    private fun isDynamic(id: Identifier) = id.ref is DynamicRef
}
