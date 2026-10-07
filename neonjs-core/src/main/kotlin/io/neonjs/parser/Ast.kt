package io.neonjs.parser

import java.math.BigInteger

/**
 * ESTree-like AST. Positions are source offsets; [line]/[col] are 1-based line and 0-based column of the node start.
 */
abstract class Node {
    var start = 0
    var end = 0
    var line = 0
    var col = 0
    /** True when the expression was wrapped in parentheses. */
    var parenthesized = false
    /** Compiler annotation: scope created by this node (set by the scope analyzer). */
    @JvmField var scope: Any? = null
    /** Compiler annotation: resolved reference for identifiers / this / super / new.target. */
    @JvmField var ref: Any? = null
}

// ---------------------------------------------------------------- expressions

class Identifier(val name: String) : Node()
class PrivateIdentifier(val name: String) : Node()
class NumberLiteral(val value: Double) : Node()
class StringLiteral(val value: String) : Node() {
    /** Raw source text including quotes, used for directive detection. */
    var raw: String = ""
    var hasLegacyOctal = false
}
class BigIntLiteral(val value: BigInteger) : Node()
class BooleanLiteral(val value: Boolean) : Node()
class NullLiteral : Node()
class RegExpLiteral(val pattern: String, val flags: String) : Node()
class TemplateElement(val cooked: String?, val raw: String) : Node()
class TemplateLiteral(val quasis: List<TemplateElement>, val expressions: List<Node>) : Node()
class TaggedTemplate(val tag: Node, val quasi: TemplateLiteral) : Node()
class ThisExpression : Node()
class Super : Node()
class ArrayLiteral(val elements: MutableList<Node?>) : Node()
class ObjectLiteral(val properties: MutableList<Node>) : Node()

enum class PropKind { INIT, GET, SET }

/** Object literal / object pattern property. In patterns, [value] is the target pattern. */
class Property(
    var key: Node, var value: Node, val kind: PropKind, val computed: Boolean,
    val shorthand: Boolean, val method: Boolean,
) : Node()

class SpreadElement(var argument: Node) : Node()
class UnaryExpression(val op: String, val argument: Node) : Node()
class UpdateExpression(val op: String, val prefix: Boolean, val argument: Node) : Node()
class BinaryExpression(val op: String, val left: Node, val right: Node) : Node()
class LogicalExpression(val op: String, val left: Node, val right: Node) : Node()
class AssignmentExpression(val op: String, var target: Node, val value: Node) : Node()
class ConditionalExpression(val test: Node, val consequent: Node, val alternate: Node) : Node()
class CallExpression(val callee: Node, val arguments: List<Node>, val optional: Boolean) : Node()
class NewExpression(val callee: Node, val arguments: List<Node>) : Node()
class MemberExpression(val obj: Node, val property: Node, val computed: Boolean, val optional: Boolean) : Node()
/** Wraps an optional chain (a?.b.c) so short-circuiting covers the whole chain. */
class ChainExpression(val expression: Node) : Node()
class SequenceExpression(val expressions: List<Node>) : Node()
class YieldExpression(val argument: Node?, val delegate: Boolean) : Node()
class AwaitExpression(val argument: Node) : Node()
class MetaProperty(val meta: String, val property: String) : Node()
class ImportCall(val source: Node, val options: Node?, val phase: String? = null) : Node()
/** `#x in obj` */
class PrivateInExpression(val name: PrivateIdentifier, val right: Node) : Node()

// ---------------------------------------------------------------- patterns

class ArrayPattern(val elements: MutableList<Node?>) : Node()
class ObjectPattern(val properties: MutableList<Node>) : Node()
class AssignmentPattern(var left: Node, val right: Node) : Node()
class RestElement(var argument: Node) : Node()

// ---------------------------------------------------------------- functions & classes

enum class FunctionKind {
    NORMAL, ARROW, METHOD, GETTER, SETTER, CLASS_CONSTRUCTOR, DERIVED_CONSTRUCTOR,
    /** Synthetic function evaluating class field initializers (and static blocks). */
    CLASS_FIELD_INIT, STATIC_BLOCK,
}

class FunctionNode(
    var id: Identifier?,
    val params: MutableList<Node>,
    var body: Node,
    var kind: FunctionKind,
    val isAsync: Boolean,
    val isGenerator: Boolean,
) : Node() {
    /** Arrow function with expression body. */
    var isExpressionBody = false
    var strict = false
    var hasSimpleParams = true
    /** Function declaration (statement) vs expression. */
    var isDeclaration = false
    var isStatic = false
    /** For class constructors and methods: home object info is resolved during compilation. */
    var classNode: ClassNode? = null
    /** Parser-computed facts used by the compiler. */
    var usesArguments = false
    var usesThis = false
    var usesSuperProperty = false
    var usesSuperCall = false
    var usesNewTarget = false
    var hasDirectEval = false
    /** Contains a direct eval anywhere inside (including nested arrow functions). */
    var containsEval = false
    /** Sloppy-mode functions whose name is in scope in the function body (named function expressions). */
    var selfBinding: String? = null
    /** Index into the source string range used for Function.prototype.toString. */
    var srcStart = 0
    var srcEnd = 0
    var name: String = ""
    /** Inferred function name used for anonymous functions (NamedEvaluation is applied at runtime). */
    var hasNameProperty = true
}

class ClassNode(val id: Identifier?, val superClass: Node?, val body: List<Node>) : Node() {
    var constructor: FunctionNode? = null
    /** Class decorators (`@dec class ...`), in source order. */
    var decorators: List<Node> = emptyList()
    var isDeclaration = false
    var srcStart = 0
    var srcEnd = 0
}

enum class MethodKind { METHOD, GET, SET, CONSTRUCTOR }

class MethodDefinition(val key: Node, val value: FunctionNode, val kind: MethodKind, val isStatic: Boolean, val computed: Boolean) : Node() {
    var decorators: List<Node> = emptyList()
}
class PropertyDefinition(val key: Node, val value: Node?, val isStatic: Boolean, val computed: Boolean) : Node() {
    /** Synthetic function wrapping the initializer (so `this`, `arguments` checks, scope work like a method). */
    var initializer: FunctionNode? = null
    var isAccessor = false
    var decorators: List<Node> = emptyList()
}
class StaticBlock(val body: List<Node>) : Node() {
    var function: FunctionNode? = null
}

// ---------------------------------------------------------------- statements

class Program(val body: List<Node>, val isModule: Boolean) : Node() {
    var strict = false
    var hasDirectEval = false
    var containsEval = false
    var usesThis = false
    var hasTopLevelAwait = false
    var source: String = ""
}

class ExpressionStatement(val expression: Node) : Node() {
    var directive: String? = null
}
class BlockStatement(val body: List<Node>) : Node()
class EmptyStatement : Node()
class DebuggerStatement : Node()
class WithStatement(val obj: Node, val body: Node) : Node()
class ReturnStatement(val argument: Node?) : Node()
class LabeledStatement(val label: String, val body: Node) : Node()
class BreakStatement(val label: String?) : Node()
class ContinueStatement(val label: String?) : Node()
class IfStatement(val test: Node, val consequent: Node, val alternate: Node?) : Node()
class SwitchCase(val test: Node?, val consequent: List<Node>) : Node()
class SwitchStatement(val discriminant: Node, val cases: List<SwitchCase>) : Node()
class ThrowStatement(val argument: Node) : Node()
class CatchClause(val param: Node?, val body: BlockStatement) : Node()
class TryStatement(val block: BlockStatement, val handler: CatchClause?, val finalizer: BlockStatement?) : Node()
class WhileStatement(val test: Node, val body: Node) : Node()
class DoWhileStatement(val body: Node, val test: Node) : Node()
class ForStatement(val init: Node?, val test: Node?, val update: Node?, val body: Node) : Node()
class ForInStatement(val left: Node, val right: Node, val body: Node) : Node()
class ForOfStatement(val left: Node, val right: Node, val body: Node, val isAwait: Boolean) : Node()

enum class VarKind { VAR, LET, CONST, USING, AWAIT_USING }

class VariableDeclarator(val id: Node, val init: Node?) : Node()
class VariableDeclaration(val kind: VarKind, val declarations: List<VariableDeclarator>) : Node()
class FunctionDeclaration(val function: FunctionNode) : Node()
class ClassDeclaration(val cls: ClassNode) : Node()

// ---------------------------------------------------------------- modules

/**
 * Internal import/export names. They contain a lone surrogate, which no module export name may contain (export names
 * must be well-formed Unicode), so they cannot collide with names like `"*"` in `export { x as "*" }`.
 */
object ModuleNames {
    const val NAMESPACE = "\uD800*"
    const val DEFERRED_NAMESPACE = "\uD800defer"
    const val MODULE_SOURCE = "\uD800source"
}

/** import specifier: imported is "default", [ModuleNames.NAMESPACE] or a name/string. */
class ImportSpecifier(val imported: String, val local: Identifier) : Node()
class ImportAttribute(val key: String, val value: String) : Node()
class ImportDeclaration(val specifiers: List<ImportSpecifier>, val source: String, val attributes: List<ImportAttribute>) : Node() {
    var phase: String? = null
}
class ExportSpecifier(val local: String, val exported: String, val localIsString: Boolean = false) : Node()
class ExportNamedDeclaration(val declaration: Node?, val specifiers: List<ExportSpecifier>, val source: String?, val attributes: List<ImportAttribute>) : Node()
/** declaration is a FunctionDeclaration, ClassDeclaration, or an expression. */
class ExportDefaultDeclaration(val declaration: Node) : Node()
class ExportAllDeclaration(val exported: String?, val source: String, val attributes: List<ImportAttribute>) : Node()
