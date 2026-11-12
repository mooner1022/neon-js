@file:OptIn(LintTreeApi::class)

package dev.mooner.neonjs.lint

import dev.mooner.neonjs.parser.*

/** What an identifier is where it stands. */
@LintTreeApi
enum class Role {
    /** Read (an expression's value). */
    READ,
    /** The operand of `typeof` (read without throwing when undeclared). */
    TYPEOF,
    /** Assigned: an assignment's target, an update's operand, a for-in/of's left side without a declaration. */
    WRITE,
    /** Declared: a variable, parameter, catch parameter or import name. */
    DECLARE,
    /** A function's or class's own name. */
    NAME,
    /** A property name: an object key, a member's property, a class member's key (not a reference). */
    KEY,
}

/** A node as the walk met it: its parent, the function (or program) whose code it is, and an identifier's role. */
@LintTreeApi
class Visit(val node: Node, val parent: Node?, val function: Node, val role: Role)

/**
 * The tree, walked once in source order: each node with its context ([visits]), the identifiers among them
 * ([identifiers]) and every statement list ([statementLists]). Every node is visited once: a class field's value
 * through its initializer function, a static block's statements through its function, a class's constructor through
 * its member.
 */
@LintTreeApi
class Tree internal constructor(val program: Program, private val cancelled: () -> Boolean) {
    val visits = ArrayList<Visit>()
    val identifiers = ArrayList<Visit>()
    val statementLists = ArrayList<List<Node>>()
    private var steps = 0

    init {
        visit(program, null, program, Role.READ)
    }

    private fun visit(n: Node?, parent: Node?, fn: Node, role: Role) {
        if (n == null) return
        if (++steps and 1023 == 0 && cancelled()) throw Cancelled()
        val v = Visit(n, parent, fn, role)
        visits.add(v)
        if (n is Identifier) {
            identifiers.add(v)
            return
        }
        val f = if (n is FunctionNode) n else fn
        fun read(c: Node?) = visit(c, n, f, Role.READ)
        fun key(c: Node, computed: Boolean) = visit(c, n, f, if (computed) Role.READ else Role.KEY)
        fun list(l: List<Node?>) = l.forEach { read(it) }
        when (n) {
            is Program -> { statementLists.add(n.body); list(n.body) }
            is ExpressionStatement -> read(n.expression)
            is BlockStatement -> { statementLists.add(n.body); list(n.body) }
            is WithStatement -> { read(n.obj); read(n.body) }
            is ReturnStatement -> read(n.argument)
            is LabeledStatement -> read(n.body)
            is IfStatement -> { read(n.test); read(n.consequent); read(n.alternate) }
            is SwitchStatement -> { read(n.discriminant); list(n.cases) }
            is SwitchCase -> { read(n.test); statementLists.add(n.consequent); list(n.consequent) }
            is ThrowStatement -> read(n.argument)
            is TryStatement -> { read(n.block); read(n.handler); read(n.finalizer) }
            is CatchClause -> { pattern(n.param, n, f, Role.DECLARE); read(n.body) }
            is WhileStatement -> { read(n.test); read(n.body) }
            is DoWhileStatement -> { read(n.body); read(n.test) }
            is ForStatement -> { read(n.init); read(n.test); read(n.update); read(n.body) }
            is ForInStatement -> { forLeft(n.left, n, f); read(n.right); read(n.body) }
            is ForOfStatement -> { forLeft(n.left, n, f); read(n.right); read(n.body) }
            is VariableDeclaration -> list(n.declarations)
            is VariableDeclarator -> { pattern(n.id, n, f, Role.DECLARE); read(n.init) }
            is FunctionDeclaration -> read(n.function)
            is ClassDeclaration -> read(n.cls)
            is ImportDeclaration -> list(n.specifiers)
            is ImportSpecifier -> visit(n.local, n, f, Role.DECLARE)
            is ExportNamedDeclaration -> read(n.declaration)
            is ExportDefaultDeclaration -> read(n.declaration)
            is TemplateLiteral -> list(n.expressions)
            is TaggedTemplate -> { read(n.tag); read(n.quasi) }
            is ArrayLiteral -> list(n.elements)
            is ObjectLiteral -> list(n.properties)
            is Property -> { key(n.key, n.computed); read(n.value) }
            is SpreadElement -> read(n.argument)
            is UnaryExpression -> visit(n.argument, n, f, if (n.op == "typeof" && n.argument is Identifier) Role.TYPEOF else Role.READ)
            is UpdateExpression -> pattern(n.argument, n, f, Role.WRITE)
            is BinaryExpression -> { read(n.left); read(n.right) }
            is LogicalExpression -> { read(n.left); read(n.right) }
            is AssignmentExpression -> { pattern(n.target, n, f, Role.WRITE); read(n.value) }
            is ConditionalExpression -> { read(n.test); read(n.consequent); read(n.alternate) }
            is CallExpression -> { read(n.callee); list(n.arguments) }
            is NewExpression -> { read(n.callee); list(n.arguments) }
            is MemberExpression -> { read(n.obj); key(n.property, n.computed) }
            is ChainExpression -> read(n.expression)
            is SequenceExpression -> list(n.expressions)
            is YieldExpression -> read(n.argument)
            is AwaitExpression -> read(n.argument)
            is ImportCall -> { read(n.source); read(n.options) }
            is PrivateInExpression -> { read(n.name); read(n.right) }
            is FunctionNode -> {
                n.id?.let { visit(it, n, fn, Role.NAME) }
                for (p in n.params) pattern(p, n, f, Role.DECLARE)
                read(n.body)
            }
            is ClassNode -> {
                list(n.decorators)
                n.id?.let { visit(it, n, fn, Role.NAME) }
                read(n.superClass)
                list(n.body)
            }
            is MethodDefinition -> { list(n.decorators); key(n.key, n.computed); read(n.value) }
            is PropertyDefinition -> {
                list(n.decorators)
                key(n.key, n.computed)
                val init = n.initializer
                if (init != null) read(init) else read(n.value)
            }
            is StaticBlock -> {
                val sf = n.function
                if (sf != null) read(sf) else { statementLists.add(n.body); list(n.body) }
            }
            is ArrayPattern, is ObjectPattern, is AssignmentPattern, is RestElement -> pattern(n, parent, fn, Role.WRITE, visited = true)
            else -> {}
        }
    }

    private fun forLeft(left: Node, parent: Node, fn: Node) {
        if (left is VariableDeclaration) visit(left, parent, fn, Role.READ) else pattern(left, parent, fn, Role.WRITE)
    }

    /** A binding or assignment target: its names get [role]; defaults, computed keys and members' parts are read. */
    private fun pattern(n: Node?, parent: Node?, fn: Node, role: Role, visited: Boolean = false) {
        if (n == null) return
        if (!visited) {
            if (n is Identifier) { visit(n, parent, fn, role); return }
            if (n !is ArrayPattern && n !is ObjectPattern && n !is AssignmentPattern && n !is RestElement) {
                visit(n, parent, fn, Role.READ)
                return
            }
            visits.add(Visit(n, parent, fn, role))
        }
        when (n) {
            is ArrayPattern -> n.elements.forEach { pattern(it, n, fn, role) }
            is ObjectPattern -> for (p in n.properties) {
                if (p is Property) {
                    visits.add(Visit(p, n, fn, role))
                    visit(p.key, p, fn, if (p.computed) Role.READ else Role.KEY)
                    pattern(p.value, p, fn, role)
                } else pattern(p, n, fn, role)
            }
            is AssignmentPattern -> { pattern(n.left, n, fn, role); visit(n.right, n, fn, Role.READ) }
            is RestElement -> pattern(n.argument, n, fn, role)
            else -> {}
        }
    }

    /** The check was cancelled. */
    internal class Cancelled : RuntimeException(null, null, false, false)
}
