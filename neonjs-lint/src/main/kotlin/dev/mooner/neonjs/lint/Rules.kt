@file:OptIn(LintTreeApi::class)

package dev.mooner.neonjs.lint

import dev.mooner.neonjs.compiler.BKind
import dev.mooner.neonjs.compiler.Binding
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

/**
 * What several rules use, found once. [problemsAt]: the offsets of the syntax errors the parse recorded, in order.
 * [misreadFrom]: where the source may be misread on (a template left open), else Int.MAX_VALUE.
 */
internal class Facts(
    val tree: Tree, val sourceType: SourceType, val scopes: ScopeAnalyzer,
    private val problemsAt: IntArray = IntArray(0), private val misreadFrom: Int = Int.MAX_VALUE,
) {
    /** The parse recorded a syntax error: the tree may lack code the source has. */
    val syntaxErrors: Boolean get() = problemsAt.isNotEmpty()

    /**
     * Whether what the source has from [pos] on may not be what the tree has there: a template left open takes the
     * next template's "`" as its end, so text is read as code and code as text.
     */
    fun misread(pos: Int): Boolean = pos >= misreadFrom

    /**
     * Whether a syntax error is at [pos] or before it on its line. From there to the line's end the recovery mode may
     * have made the code it reads: it pretends a `,`, `:` or `;` and starts a new expression (`value undefined ? a : b`),
     * hides the errors that follow a token it repaired, and reads on mid-line after a statement it passed over.
     */
    fun errorEarlierOnLine(src: String, pos: Int): Boolean {
        if (problemsAt.isEmpty()) return false
        var i = java.util.Arrays.binarySearch(problemsAt, pos).let { if (it < 0) -it - 2 else it }
        while (i + 1 < problemsAt.size && problemsAt[i + 1] == pos) i++
        if (i < 0) return false
        val p = problemsAt[i]
        return p <= pos && pos <= src.length && (p until pos).none { src[it] == '\n' || src[it] == '\r' || src[it] == '\u2028' || src[it] == '\u2029' }
    }

    /** Whether a syntax error follows [end] with only white space and ')' between: the code before it may be meant as something else. */
    fun errorRightAfter(src: String, end: Int): Boolean {
        val i = java.util.Arrays.binarySearch(problemsAt, end).let { if (it < 0) -it - 1 else it }
        if (i >= problemsAt.size) return false
        val p = problemsAt[i]
        return p <= src.length && (end until p).all { src[it].isWhitespace() || src[it] == ')' }
    }

    /** Whether the source declares a name `undefined` (then a reference to it may not be the global one, which the engine misses in a case). */
    val declaresUndefined: Boolean by lazy {
        tree.identifiers.any { (it.role == Role.DECLARE || it.role == Role.NAME) && (it.node as Identifier).name == "undefined" }
    }

    /**
     * Whether a syntax error is in the run of word characters around [start, end) of [src], or right after it: where the
     * recovery mode, repairing a token it could not read, rewrites the run (`1x` and `0x` become `0`).
     */
    fun errorAround(src: String, start: Int, end: Int): Boolean {
        if (problemsAt.isEmpty()) return false
        fun word(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$' || c == '.'
        var from = minOf(start, src.length)
        var to = minOf(end, src.length)
        while (from > 0 && word(src[from - 1])) from--
        while (to < src.length && word(src[to])) to++
        val i = java.util.Arrays.binarySearch(problemsAt, from).let { if (it < 0) -it - 1 else it }
        return i < problemsAt.size && problemsAt[i] <= to
    }

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
     * A derived class's constructor, and what the rules about `super()` ask of it: its `super(...)` calls (in its own
     * code; [arrowSuperCalls]: in its arrow functions'), its `this` and `super.x`, whether it returns a value, whether an
     * ErrorNode is in it, and its member.
     */
    class DerivedConstructor(val ctor: FunctionNode) {
        val superCalls = ArrayList<CallExpression>()
        var arrowSuperCalls = false
        val thisAndSuper = ArrayList<Node>()
        var returnsValue = false
        var hasErrorNode = false
        var member: MethodDefinition? = null
    }

    /** The derived classes' constructors (but those with a direct eval, which may call `super()`), found in one pass. */
    val derivedConstructors: List<DerivedConstructor> by lazy {
        val byCtor = java.util.IdentityHashMap<Node, DerivedConstructor>()
        for (v in tree.visits) {
            val cls = v.node as? ClassNode ?: continue
            val sup = cls.superClass ?: continue
            if (sup is NullLiteral || sup is NumberLiteral || sup is StringLiteral || sup is BooleanLiteral) continue
            val ctor = cls.constructor?.takeIf { it.kind == FunctionKind.DERIVED_CONSTRUCTOR } ?: continue
            if ((ctor.scope as? Scope)?.fn?.containsEval == true) continue
            byCtor[ctor] = DerivedConstructor(ctor)
        }
        if (byCtor.isEmpty()) return@lazy emptyList()
        // (each arrow function's enclosing function that is not one, found once)
        val arrowOwner = java.util.IdentityHashMap<Node, Node?>()
        fun ownerOf(arrow: FunctionNode): Node? = arrowOwner.getOrPut(arrow) {
            var f: Node? = arrow
            while (f is FunctionNode && f.kind == FunctionKind.ARROW) f = (f.scope as? Scope)?.fn?.parent?.node ?: break
            f
        }
        val all = byCtor.values.sortedBy { it.ctor.start }
        for (v in tree.visits) {
            val n = v.node
            if (n is ErrorNode) {
                for (d in all) if (n.start >= d.ctor.start && n.end <= d.ctor.end) d.hasErrorNode = true
                continue
            }
            if (n is MethodDefinition) byCtor[n.value]?.let { if (it.member == null) it.member = n }
            val own = byCtor[v.function]
            if (own != null) when {
                n is CallExpression && n.callee is Super -> own.superCalls.add(n)
                n is ThisExpression || (n is MemberExpression && n.obj is Super) -> own.thisAndSuper.add(n)
                n is ReturnStatement && n.argument != null -> own.returnsValue = true
            } else if (n is CallExpression && n.callee is Super) {
                val f = v.function
                if (f is FunctionNode && f.kind == FunctionKind.ARROW) {
                    val d = byCtor[ownerOf(f)]
                    if (d != null && n.start >= d.ctor.start && n.end <= d.ctor.end) d.arrowSuperCalls = true
                }
            }
        }
        all
    }
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
            for (d in c.facts.derivedConstructors) {
                if (d.hasErrorNode) continue
                val ctor = d.ctor
                val first = d.superCalls.minByOrNull { it.start } ?: continue
                val body = (ctor.body as? BlockStatement)?.body ?: continue
                val statement = body.firstOrNull { first.start >= it.start && first.start < it.end } ?: continue
                for (n in d.thisAndSuper) {
                    val kind = if (n is MemberExpression) "super" else "this"
                    val before = n.start < statement.start || (n.start > first.start && n.end <= first.end && first.arguments.any { n.start >= it.start && n.end <= it.end })
                    if (before) c.report(n.start, if (n is MemberExpression) n.obj.end else n.end, "'$kind' is not allowed before 'super()'.", listOf(kind))
                }
            }
        },
        rule("missing-super-call", Severity.ERROR, emptyList(), "A derived class's constructor that never calls `super()` (nor returns an object): a ReferenceError when it runs.") { c ->
            for (d in c.facts.derivedConstructors) {
                if (d.hasErrorNode || d.superCalls.isNotEmpty() || d.arrowSuperCalls || d.returnsValue) continue
                val ctor = d.ctor
                // (a constructor that always throws never needs `this`)
                if (throws(ctor.body)) continue
                val at = d.member?.key ?: ctor
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
        rule("sparse-array", Severity.WARNING, emptyList(), "An empty slot in an array literal (`[1, , 2]`): a ',' too many, or a value left out. Not in a destructuring pattern.") { c ->
            for (v in c.tree.visits) {
                val a = v.node as? ArrayLiteral ?: continue
                if (a.elements.none { it == null } || c.facts.hasErrorNode(a)) continue
                if (c.facts.misread(a.start) || c.facts.errorEarlierOnLine(c.source, a.start) || c.facts.errorRightAfter(c.source, a.end)) continue
                // (an array literal in an assignment's target is a pattern the parser did not take: `[, x] += y`)
                if (inTarget(a, v.parent, c.tree)) continue
                for (comma in holeCommas(c.source, a) ?: continue) c.report(comma, comma + 1, "This array has an empty slot: a ',' too many, or a value left out.")
            }
        },
        rule("precision-loss", Severity.WARNING, listOf("value"), "A number literal its value does not keep as written: it is another number when it runs.") { c ->
            for (v in c.tree.visits) {
                val n = v.node as? NumberLiteral ?: continue
                if (n.end > c.source.length || n.start >= n.end || c.facts.errorAround(c.source, n.start, n.end) || c.facts.misread(n.start) ||
                    c.facts.errorEarlierOnLine(c.source, n.start)) continue
                if (losesPrecision(c.source.substring(n.start, n.end), n.value)) {
                    val value = NumberConv.toString(n.value)
                    c.report(n, "This number cannot be kept exactly: it becomes $value.", listOf(value))
                }
            }
        },
        rule("useless-escape", Severity.WARNING, listOf("char"), "A '\\' in a string or an untagged template before a character that is no escape (a letter, or a regular expression's punctuation): the '\\' is dropped.") { c ->
            for (v in c.tree.visits) {
                val node = v.node
                if ((node is StringLiteral || node is TemplateLiteral) && (c.facts.misread(node.start) || c.facts.errorEarlierOnLine(c.source, node.start))) continue
                when (val n = node) {
                    // (from after the opening quote; not a literal the recovery mode made, repairing a token)
                    is StringLiteral -> if (asWritten(c, n)) uselessEscapes(c, n.start + 1, n.end)
                    // (each piece from after its "`" or "}"; a tagged template's pieces are read raw, the tag is not one)
                    is TemplateLiteral -> if ((v.parent as? TaggedTemplate)?.quasi !== n && asWritten(c, n) && !c.facts.hasErrorNode(n))
                        for (q in n.quasis) uselessEscapes(c, q.start + 1, q.end)
                    else -> {}
                }
            }
        },
        rule("unused-variable", Severity.WARNING, listOf("name", "kind"), "A name declared and never read: a let, const, var, function or class in a function or a module (not exported), or an import. Not a parameter, a `using`, a name starting with '_', a name a `with` or a direct eval may reach, nor a script's top level; off while the source has a syntax error.") { c ->
            unusedVariables(c)
        },
        rule("constant-condition", Severity.WARNING, listOf("value"), "An `if` or `? :` whose condition is a literal, an array, object, function, class or `new`, a `typeof` or `void`, or `!` of one: it always goes the same way. Not loops.") { c ->
            for (v in c.tree.visits) {
                val test = when (val n = v.node) {
                    is IfStatement -> n.test.takeUnless { c.facts.hasErrorNode(it) }
                    // (a conditional with an ErrorNode anywhere in it is one expression the parse could not read)
                    is ConditionalExpression -> n.test.takeUnless { c.facts.hasErrorNode(n) }
                    else -> null
                } ?: continue
                // (not a condition the recovery mode may have made: after a syntax error on its line)
                if (c.facts.misread(test.start) || c.facts.errorEarlierOnLine(c.source, test.start)) continue
                val value = truthiness(c, test) ?: continue
                c.report(test, "This condition is always $value.", listOf(value.toString()))
            }
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

    /** The commas that make [a]'s holes (each the one after its hole), or null if a gap holds what it should not. */
    private fun holeCommas(src: String, a: ArrayLiteral): List<Int>? {
        if (a.start >= src.length || src[a.start] != '[') return null
        val out = ArrayList<Int>()
        var p = a.start + 1
        for ((i, e) in a.elements.withIndex()) {
            if (e != null) {
                if (i == a.elements.size - 1) break
                p = e.end
            }
            val comma = nextComma(src, p, a.end) ?: return null
            if (e == null) out.add(comma)
            p = comma + 1
        }
        return out
    }

    /** Whether [a] is in an assignment's target (an update's, a for-in/of's left side), through arrays, objects and spreads. */
    private fun inTarget(a: Node, parent: Node?, tree: Tree): Boolean {
        var parentOf: java.util.IdentityHashMap<Node, Node?>? = null
        var n: Node = a
        var p: Node? = parent
        while (true) {
            when {
                p is AssignmentExpression -> return p.target === n
                p is UpdateExpression -> return p.argument === n
                p is ForInStatement -> return p.left === n
                p is ForOfStatement -> return p.left === n
                p is ArrayLiteral || p is ObjectLiteral || p is Property || p is SpreadElement -> {
                    if (p is Property && p.value !== n) return false
                    val map = parentOf ?: java.util.IdentityHashMap<Node, Node?>().also { m -> for (v in tree.visits) m.putIfAbsent(v.node, v.parent); parentOf = m }
                    n = p
                    p = map[p]
                }
                else -> return false
            }
        }
    }

    /** The first ',' from [from], before [limit], with only spaces, comments and ')' before it; null if anything else is. */
    private fun nextComma(src: String, from: Int, limit: Int): Int? {
        val end = minOf(limit, src.length)
        var i = from
        while (i < end) {
            val ch = src[i]
            when {
                ch == ',' -> return i
                ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r' || ch == '\u000B' || ch == '\u000C' || ch == '\u00A0' || ch == '\uFEFF' ||
                    ch == '\u2028' || ch == '\u2029' || Character.getType(ch) == Character.SPACE_SEPARATOR.toInt() || ch == ')' -> i++
                ch == '/' && i + 1 < end && src[i + 1] == '/' -> while (i < end && src[i] != '\n' && src[i] != '\r' && src[i] != '\u2028' && src[i] != '\u2029') i++
                ch == '/' && i + 1 < end && src[i + 1] == '*' -> {
                    val close = src.indexOf("*/", i + 2)
                    if (close < 0 || close + 2 > end) return null
                    i = close + 2
                }
                else -> return null
            }
        }
        return null
    }

    /**
     * A decimal literal's text (without separators) as its whole part's digits and its fraction's (null when it has no
     * '.'), or null if the text is no decimal literal. (No regular expression: the first lint in a new JVM would pay for
     * compiling one.)
     */
    private fun decimalParts(t: String): Pair<String, String?>? {
        var i = 0
        while (i < t.length && t[i] in '0'..'9') i++
        val whole = t.substring(0, i)
        var fraction: String? = null
        if (i < t.length && t[i] == '.') {
            val from = ++i
            while (i < t.length && t[i] in '0'..'9') i++
            fraction = t.substring(from, i)
        }
        if (whole.isEmpty() && fraction.isNullOrEmpty()) return null
        if (i < t.length && (t[i] == 'e' || t[i] == 'E')) {
            i++
            if (i < t.length && (t[i] == '+' || t[i] == '-')) i++
            val from = i
            while (i < t.length && t[i] in '0'..'9') i++
            if (i == from) return null
        }
        return if (i == t.length) whole to fraction else null
    }

    /** A number literal's radix, from its text without separators: 16, 8 or 2 for `0x`, `0o`, `0b`; 0 for a legacy octal `017`; else 10. */
    private fun radixOf(t: String): Int = when {
        t.length > 2 && t[0] == '0' && (t[1] == 'x' || t[1] == 'X') -> 16
        t.length > 2 && t[0] == '0' && (t[1] == 'o' || t[1] == 'O') -> 8
        t.length > 2 && t[0] == '0' && (t[1] == 'b' || t[1] == 'B') -> 2
        t.length > 1 && t[0] == '0' && t.all { it in '0'..'7' } -> 0
        else -> 10
    }

    /** The integer a hex, octal or binary literal's text (without separators) writes, or null. */
    private fun integerOf(t: String, radix: Int): java.math.BigInteger? =
        try { java.math.BigInteger(if (radix == 0) t.substring(1) else t.substring(2), if (radix == 0) 8 else radix) } catch (e: NumberFormatException) { null }

    /** The value a number literal's text has, or null if the text is no number literal. */
    private fun numberValue(text: String): Double? {
        val t = if ('_' in text) text.replace("_", "") else text
        if (t.isEmpty()) return null
        val radix = radixOf(t)
        if (radix != 10) return integerOf(t, radix)?.toDouble()
        decimalParts(t) ?: return null
        return try { java.lang.Double.parseDouble(t) } catch (e: NumberFormatException) { null }
    }

    /**
     * Whether a number literal's [value] is not what its [text] writes. A decimal literal: its value rounded to as many
     * significant digits as the text has (an integer's trailing zeros are none) is not the number written, so `0.1` is
     * kept and `9007199254740993` is not. A hex, octal or binary one: its value is not the integer written. Infinity is
     * never kept. False for a text that is no number literal, and for a value that is not the text's (a literal the
     * recovery mode made, repairing the token: `1x` is read as `0`).
     */
    internal fun losesPrecision(text: String, value: Double): Boolean {
        val t = if ('_' in text) text.replace("_", "") else text
        if (t.isEmpty() || value.isNaN()) return false
        val radix = radixOf(t)
        if (radix != 10) {
            val exact = integerOf(t, radix) ?: return false
            if (exact.toDouble() != value) return false
            return value.isInfinite() || java.math.BigDecimal(value).compareTo(java.math.BigDecimal(exact)) != 0
        }
        // (at most 15 significant digits, in the normal range: a double keeps them)
        if (t.length <= 15 && value >= java.lang.Double.MIN_NORMAL && value <= Double.MAX_VALUE) return false
        val (whole, fraction) = decimalParts(t) ?: return false
        val digits = (whole + (fraction ?: "")).let { if (fraction == null) it.trimEnd('0') else it }.trimStart('0')
        // (a zero written as one, `0.0`)
        if (digits.isEmpty()) return false
        val parsed = try { java.lang.Double.parseDouble(t) } catch (e: NumberFormatException) { return false }
        if (parsed != value) return false
        if (value.isInfinite() || value == 0.0) return true
        val written = try { java.math.BigDecimal(t) } catch (e: NumberFormatException) { return false }
        // (a value halfway between two numbers of as many digits rounds to the even one, as JavaScript prints it:
        // 95.00259399414062 is 95.002593994140625, printed as written)
        val n = maxOf(digits.length, 1)
        if (java.math.BigDecimal(value).round(java.math.MathContext(n, java.math.RoundingMode.HALF_EVEN)).compareTo(written) == 0) return false
        // (nor what JavaScript prints, when it has as many digits: by a power of two the doubles' spacing halves, and the
        // nearest number of n digits may not read back as the same double, 2^-24 printed 5.960464477539063e-8)
        val printed = java.math.BigDecimal(NumberConv.toString(value))
        return !(printed.compareTo(written) == 0 && printed.stripTrailingZeros().precision() == n)
    }

    /**
     * Reports each '\' in [from, to) of the source before a character that is no escape in a string or a template: a
     * letter but b f n r t v x u, or a regular expression's punctuation. (`\\` is one escape, passed over.)
     */
    private fun uselessEscapes(c: LintContext, from: Int, to: Int) {
        val s = c.source
        val end = minOf(to, s.length)
        var i = from
        while (i < end - 1) {
            if (s[i] != '\\') { i++; continue }
            val ch = s[i + 1]
            if (ch in 'A'..'Z' || (ch in 'a'..'z' && ch !in "bfnrtvxu") || ch in ".*+?^|()[]-") {
                c.report(i, i + 2, "'\\$ch' is no escape in a string: it is just '$ch'. For a regular expression's \\$ch, write '\\\\$ch'.", listOf(ch.toString()))
            }
            i += 2
        }
    }

    /** Whether [n] is truthy (true) or falsy (false) by what it is alone; null when it takes more to know. */
    private fun truthiness(c: LintContext, n: Node): Boolean? {
        if ((n is BooleanLiteral || n is NumberLiteral || n is BigIntLiteral || n is StringLiteral || n is NullLiteral || n is TemplateLiteral) && !asWritten(c, n)) return null
        return when (n) {
            is BooleanLiteral -> n.value
            is NumberLiteral -> n.value != 0.0
            is BigIntLiteral -> n.value.signum() != 0
            is StringLiteral -> n.value.isNotEmpty()
            is NullLiteral -> false
            is RegExpLiteral, is ArrayLiteral, is ObjectLiteral, is FunctionNode, is ClassNode, is NewExpression -> true
            is TemplateLiteral -> if (n.expressions.isEmpty()) n.quasis[0].cooked?.isNotEmpty() else null
            is Identifier -> if (n.name == "undefined" && n.ref is GlobalRef && !c.facts.declaresUndefined) false else null
            is UnaryExpression -> when (n.op) {
                "void" -> false
                "typeof" -> true
                "!" -> truthiness(c, n.argument)?.not()
                "-" -> if (n.argument is NumberLiteral || n.argument is BigIntLiteral) truthiness(c, n.argument) else null
                else -> null
            }
            else -> null
        }
    }

    /**
     * Whether a literal is as the source writes it, and not one the recovery mode made by repairing a token: `"abc` is
     * read as `""`, `1x` and `0x` as `0`, a regular expression left open as `0`, a template left open as closing at the
     * source's end.
     */
    private fun asWritten(c: LintContext, n: Node): Boolean {
        val src = c.source
        if (n.start < 0 || n.end > src.length || n.start >= n.end) return false
        val length = n.end - n.start
        return when (n) {
            is NumberLiteral -> !c.facts.errorAround(src, n.start, n.end) && numberValue(src.substring(n.start, n.end)) == n.value
            is BigIntLiteral -> !c.facts.errorAround(src, n.start, n.end) && src[n.end - 1] == 'n' && src.substring(n.start, n.end - 1).replace("_", "").let { t ->
                val radix = radixOf(t)
                (if (radix == 10) t.toBigIntegerOrNull() else integerOf(t, radix)) == n.value
            }
            is StringLiteral -> n.raw.length == length && src.regionMatches(n.start, n.raw, 0, length)
            is BooleanLiteral -> (if (n.value) "true" else "false").let { it.length == length && src.startsWith(it, n.start) }
            is NullLiteral -> length == 4 && src.startsWith("null", n.start)
            // (each piece between its "`" or "}" and its "`" or "${"; a template's raw text has its line ends as \n)
            is TemplateLiteral -> src[n.start] == '`' && src[n.end - 1] == '`' && n.quasis.withIndex().all { (i, q) ->
                val to = q.end - (if (i == n.quasis.size - 1) 1 else 2)
                q.start + 1 <= to && to <= src.length && src.substring(q.start + 1, to).replace("\r\n", "\n").replace('\r', '\n') == q.raw
            }
            else -> true
        }
    }

    /**
     * `unused-variable`: each binding a declaration makes that nothing reads. A binding's reads are its references as
     * READ or TYPEOF, `export { a }`, a logical assignment (`a ??= b` reads `a` to decide), an update or compound
     * assignment whose value is used (`y = x++`), and a for-in/of's names when its body is a `return` (`for (const x of
     * xs) return true`, as ESLint) or nothing (`for (const x of it);` drains an iterator); a class's are also those of
     * its inner name (references inside it), a sloppy block function's those of its Annex B var, or of any reference to
     * its name when the engine made none for it where the language does (a function with a "use strict" of its own).
     */
    private fun unusedVariables(c: LintContext) {
        if (c.facts.syntaxErrors) return
        val programScope = c.program.scope as? Scope ?: return
        fun bindingSet() = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Binding, Boolean>())
        val read = bindingSet()
        val assigned = bindingSet()
        val skipped = bindingSet()
        val dynamicNames = HashSet<String>()
        val readNames = HashSet<String>()
        // the scopes a `with` is in: every name of theirs may be read through it
        val withScopes = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Scope, Boolean>())
        // the classes that use themselves as they are declared, to register themselves: with a decorator (on the class
        // or a member), a static block, or a static field whose value calls something or uses `this`
        val registering = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Node, Boolean>())
        val staticFieldOf = java.util.IdentityHashMap<Node, ClassNode>()

        // the updates and compound assignments whose value is used (a logical assignment's always is), the names beside
        // a rest element, the names of a for-in/of that returns at once or does nothing
        val valueUsed = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Node, Boolean>())
        val sequenceParent = java.util.IdentityHashMap<Node, Node?>()
        fun used(n: Node, parent: Node?): Boolean = when (parent) {
            is ExpressionStatement -> false
            is ForStatement -> n === parent.test
            is SequenceExpression -> n === parent.expressions.last() && used(parent, sequenceParent[parent])
            else -> true
        }
        for (v in c.tree.visits) {
            when (val n = v.node) {
                is SequenceExpression -> sequenceParent[n] = v.parent
                is UpdateExpression -> if (used(n, v.parent)) valueUsed.add(n)
                is AssignmentExpression -> if (n.op != "=" && (n.op == "&&=" || n.op == "||=" || n.op == "??=" || used(n, v.parent))) valueUsed.add(n)
                is ForInStatement, is ForOfStatement -> {
                    val (left, body) = if (n is ForInStatement) n.left to n.body else (n as ForOfStatement).left to n.body
                    val first = if (body is BlockStatement) body.body.firstOrNull() else body
                    if (first is ReturnStatement || first == null || first is EmptyStatement) {
                        val targets = if (left is VariableDeclaration) left.declarations.flatMap { Facts.boundNames(it.id) } else Facts.boundNames(left)
                        for (id in targets) (id.ref as? LocalRef)?.let { read.add(it.binding) }
                    }
                }
                // (`const { password, ...rest } = user`: what is taken out of the rest)
                is ObjectPattern -> if (n.properties.any { it is RestElement }) for (p in n.properties) {
                    if (p is Property) for (id in Facts.boundNames(p.value)) (id.ref as? LocalRef)?.let { skipped.add(it.binding) }
                }
                is ExportNamedDeclaration -> if (n.source == null) for (sp in n.specifiers) ((sp.ref as? Identifier)?.ref as? LocalRef)?.let { read.add(it.binding) }
                is WithStatement -> {
                    var s = (n.scope as? Scope)?.parent
                    while (s != null && withScopes.add(s)) s = s.parent
                }
                is ClassNode -> {
                    if (n.decorators.isNotEmpty() || n.body.any { m -> m is StaticBlock || (m is MethodDefinition && m.decorators.isNotEmpty()) || (m is PropertyDefinition && m.decorators.isNotEmpty()) }) registering.add(n)
                    for (m in n.body) if (m is PropertyDefinition && m.isStatic) m.initializer?.let { staticFieldOf[it] = n }
                }
                is CallExpression, is NewExpression, is ThisExpression, is TaggedTemplate -> staticFieldOf[v.function]?.let { registering.add(it) }
                else -> {}
            }
        }

        // each binding's first declaring identifier; a function's, class's and import's name has no ref of its own
        val declaredAt = java.util.IdentityHashMap<Binding, Identifier>()
        val alias = java.util.IdentityHashMap<Binding, Binding>()
        val blockFunction = java.util.IdentityHashMap<Binding, FunctionNode>()
        fun declared(b: Binding, id: Identifier) {
            val prev = declaredAt[b]
            if (prev == null || id.start < prev.start) declaredAt[b] = id
        }
        fun lookup(from: Scope?, name: String): Binding? {
            var s = from
            while (s != null) {
                s.bindings[name]?.let { return it }
                s = s.parent
            }
            return null
        }
        for (v in c.tree.identifiers) {
            val id = v.id
            val ref = id.ref
            if (ref is DynamicRef) { dynamicNames.add(ref.name); continue }
            when (v.role) {
                Role.READ, Role.TYPEOF -> {
                    readNames.add(id.name)
                    if (ref is LocalRef) read.add(ref.binding)
                }
                Role.WRITE -> {
                    val readToo = v.parent?.let { it in valueUsed } == true
                    if (readToo) readNames.add(id.name)
                    if (ref is LocalRef) {
                        assigned.add(ref.binding)
                        if (readToo) read.add(ref.binding)
                    }
                }
                // (a function's or class's binding is told at its name, not at a parameter or var of the same name)
                Role.DECLARE -> ((ref as? LocalRef)?.binding ?: (if (v.parent is ImportSpecifier) programScope.bindings[id.name] else null))?.let {
                    if (it.kind != BKind.FUNCTION && it.kind != BKind.BLOCK_FUNCTION && it.kind != BKind.CLASS) declared(it, id)
                }
                Role.NAME -> when (val p = v.parent) {
                    is FunctionNode -> if (p.isDeclaration) lookup((p.scope as? Scope)?.parent, id.name)?.let { b ->
                        declared(b, id)
                        (b.annexBVar as? Binding)?.let { alias[b] = it }
                        if (b.kind == BKind.BLOCK_FUNCTION) blockFunction[b] = p
                    }
                    is ClassNode -> if (p.isDeclaration && p !in registering) {
                        val inner = p.scope as? Scope
                        lookup(inner?.parent, id.name)?.let { b ->
                            declared(b, id)
                            inner?.bindings?.get(id.name)?.let { alias[b] = it }
                        }
                    }
                    else -> {}
                }
                Role.KEY -> {}
            }
        }

        // a module's exported declarations
        for (st in c.program.body) {
            val d = when (st) {
                is ExportNamedDeclaration -> st.declaration
                is ExportDefaultDeclaration -> st.declaration
                else -> null
            }
            val names = when (d) {
                is VariableDeclaration -> d.declarations.flatMap { Facts.boundNames(it.id) }.map { it.name }
                is FunctionDeclaration -> listOfNotNull(d.function.id?.name)
                is ClassDeclaration -> listOfNotNull(d.cls.id?.name)
                else -> emptyList()
            }
            for (name in names) programScope.bindings[name]?.let { skipped.add(it) }
        }

        for ((b, id) in declaredAt.entries.sortedBy { it.value.start }) {
            if (b.kind !in UNUSED_KINDS || b.name.startsWith("_") || b in skipped || b.name in dynamicNames || b.scope in withScopes) continue
            // (a direct eval in its function or one inside may read it; a block function in a script's top level is global)
            if (b.scope.fn.containsEval || b.annexBVar is String) continue
            // (a sloppy block function with a "use strict" of its own: the engine makes it no var, the language does, global
            // in a script's top level)
            val f = blockFunction[b]
            if (f != null && b.annexBVar == null && f.strict && !f.isAsync && !f.isGenerator && !b.scope.fn.strict &&
                ((b.scope.fn.isTopLevel && c.sourceType == SourceType.SCRIPT) || b.name in readNames)) continue
            // (a function of a parameter's name in a sloppy function with simple parameters: `arguments` may read it)
            if (b.kind == BKind.FUNCTION && !b.scope.fn.strict && b.scope.fn.argumentsBinding != null &&
                (b.scope.fn.node as? FunctionNode)?.params?.any { p -> Facts.boundNames(p).any { it.name == b.name } } == true) continue
            // (a var of a parameter's name, in a function with parameter expressions: its own binding, which is the parameter's)
            if (b.kind == BKind.VAR && b.scope.kind == dev.mooner.neonjs.compiler.ScopeKind.BODY && b.scope.parent?.bindings?.get(b.name)?.kind == BKind.PARAM) continue
            val a = alias[b]
            if (b in read || (a != null && a in read)) continue
            if (b in assigned || (a != null && a in assigned)) c.report(id, "'${b.name}' is assigned and never read.", listOf(b.name, "assigned"))
            else c.report(id, "'${b.name}' is declared and never used.", listOf(b.name, "declared"))
        }
    }

    /** What `unused-variable` reports: not parameters, catch parameters, `using`, nor a function or class expression's own name. */
    private val UNUSED_KINDS = setOf(BKind.VAR, BKind.LET, BKind.CONST, BKind.FUNCTION, BKind.BLOCK_FUNCTION, BKind.CLASS, BKind.IMPORT)

    /**
     * The constructors of the classes with an `extends` (not `null`): the FunctionNodes. Not one with a direct eval,
     * which may call `super()` where no rule sees it.
     */
    @Suppress("unused")
    private fun isDynamic(id: Identifier) = id.ref is DynamicRef
}
