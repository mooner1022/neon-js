package dev.mooner.neonpython

/*
 * Python's syntax trees, as Python's `ast` module has them (3.14's Parser/Python.asdl): the same nodes, fields and
 * order, so that [PyAst.dump] prints what `ast.dump` prints and the parser can be checked against CPython's. Kotlin
 * names differ where they would clash (ListExpr for List, ExprStmt for Expr...); [Node.pyName] is Python's.
 */

/** A node: its position (line from 1, column from 0, of its first and last characters). */
sealed class Node {
    @JvmField var line = 0
    @JvmField var col = 0
    @JvmField var endLine = 0
    @JvmField var endCol = 0

    /** Its class's name in Python's `ast`. */
    open val pyName: String get() = javaClass.simpleName

    /** Its fields, in `_fields` order (lists, nodes, strings, ints, constants, null). */
    abstract fun fields(): List<Pair<String, Any?>>
}

enum class Ctx { Load, Store, Del }
enum class BoolOpKind { And, Or }
enum class OpKind(val symbol: String) {
    Add("+"), Sub("-"), Mult("*"), MatMult("@"), Div("/"), Mod("%"), Pow("**"), LShift("<<"), RShift(">>"),
    BitOr("|"), BitXor("^"), BitAnd("&"), FloorDiv("//");

    companion object {
        private val bySymbol = entries.associateBy { it.symbol }
        fun of(symbol: String): OpKind? = bySymbol[symbol]
    }
}
enum class UnaryOpKind { Invert, Not, UAdd, USub }
enum class CmpOpKind(val symbol: String) {
    Eq("=="), NotEq("!="), Lt("<"), LtE("<="), Gt(">"), GtE(">="), Is("is"), IsNot("is not"), In("in"), NotIn("not in")
}

class Module(@JvmField val body: List<Stmt>) : Node() {
    override fun fields() = listOf("body" to body)
}

/** An expression to evaluate (eval's mode). */
/** compile()'s 'single' mode's tree (ast.Interactive): what compile(..., PyCF_ONLY_AST) gives of it. */
class Interactive(@JvmField val body: List<Stmt>) : Node() {
    override fun fields() = listOf("body" to body)
}

class Expression(@JvmField val body: Expr) : Node() {
    override fun fields() = listOf("body" to body)
}

// ---------------------------------------------------------------------- statements

sealed class Stmt : Node()

class FunctionDef(@JvmField val name: String, @JvmField val args: Arguments, @JvmField val body: List<Stmt>,
                  @JvmField val decoratorList: List<Expr>, @JvmField val returns: Expr?, @JvmField val typeParams: List<TypeParam>,
                  @JvmField val isAsync: Boolean) : Stmt() {
    override val pyName get() = if (isAsync) "AsyncFunctionDef" else "FunctionDef"
    override fun fields() = listOf("name" to name, "args" to args, "body" to body, "decorator_list" to decoratorList,
        "returns" to returns, "type_comment" to null, "type_params" to typeParams)
}

class ClassDef(@JvmField val name: String, @JvmField val bases: List<Expr>, @JvmField val keywords: List<Keyword>,
               @JvmField val body: List<Stmt>, @JvmField val decoratorList: List<Expr>, @JvmField val typeParams: List<TypeParam>) : Stmt() {
    override fun fields() = listOf("name" to name, "bases" to bases, "keywords" to keywords, "body" to body,
        "decorator_list" to decoratorList, "type_params" to typeParams)
}

class Return(@JvmField val value: Expr?) : Stmt() {
    override fun fields() = listOf("value" to value)
}

class Delete(@JvmField val targets: List<Expr>) : Stmt() {
    override fun fields() = listOf("targets" to targets)
}

class Assign(@JvmField val targets: List<Expr>, @JvmField val value: Expr) : Stmt() {
    override fun fields() = listOf("targets" to targets, "value" to value, "type_comment" to null)
}

class TypeAlias(@JvmField val name: Name, @JvmField val typeParams: List<TypeParam>, @JvmField val value: Expr) : Stmt() {
    override fun fields() = listOf("name" to name, "type_params" to typeParams, "value" to value)
}

class AugAssign(@JvmField val target: Expr, @JvmField val op: OpKind, @JvmField val value: Expr) : Stmt() {
    override fun fields() = listOf("target" to target, "op" to op, "value" to value)
}

class AnnAssign(@JvmField val target: Expr, @JvmField val annotation: Expr, @JvmField val value: Expr?, @JvmField val simple: Int) : Stmt() {
    override fun fields() = listOf("target" to target, "annotation" to annotation, "value" to value, "simple" to simple)
}

class For(@JvmField val target: Expr, @JvmField val iter: Expr, @JvmField val body: List<Stmt>, @JvmField val orelse: List<Stmt>,
          @JvmField val isAsync: Boolean) : Stmt() {
    override val pyName get() = if (isAsync) "AsyncFor" else "For"
    override fun fields() = listOf("target" to target, "iter" to iter, "body" to body, "orelse" to orelse, "type_comment" to null)
}

class While(@JvmField val test: Expr, @JvmField val body: List<Stmt>, @JvmField val orelse: List<Stmt>) : Stmt() {
    override fun fields() = listOf("test" to test, "body" to body, "orelse" to orelse)
}

class If(@JvmField val test: Expr, @JvmField val body: List<Stmt>, @JvmField val orelse: List<Stmt>) : Stmt() {
    override fun fields() = listOf("test" to test, "body" to body, "orelse" to orelse)
}

class With(@JvmField val items: List<WithItem>, @JvmField val body: List<Stmt>, @JvmField val isAsync: Boolean) : Stmt() {
    override val pyName get() = if (isAsync) "AsyncWith" else "With"
    override fun fields() = listOf("items" to items, "body" to body, "type_comment" to null)
}

class Match(@JvmField val subject: Expr, @JvmField val cases: List<MatchCase>) : Stmt() {
    override fun fields() = listOf("subject" to subject, "cases" to cases)
}

class Raise(@JvmField val exc: Expr?, @JvmField val cause: Expr?) : Stmt() {
    override fun fields() = listOf("exc" to exc, "cause" to cause)
}

class Try(@JvmField val body: List<Stmt>, @JvmField val handlers: List<ExceptHandler>, @JvmField val orelse: List<Stmt>,
          @JvmField val finalbody: List<Stmt>, @JvmField val star: Boolean) : Stmt() {
    override val pyName get() = if (star) "TryStar" else "Try"
    override fun fields() = listOf("body" to body, "handlers" to handlers, "orelse" to orelse, "finalbody" to finalbody)
}

class Assert(@JvmField val test: Expr, @JvmField val msg: Expr?) : Stmt() {
    override fun fields() = listOf("test" to test, "msg" to msg)
}

class Import(@JvmField val names: List<Alias>) : Stmt() {
    override fun fields() = listOf("names" to names)
}

class ImportFrom(@JvmField val module: String?, @JvmField val names: List<Alias>, @JvmField val level: Int) : Stmt() {
    override fun fields() = listOf("module" to module, "names" to names, "level" to level)
}

class Global(@JvmField val names: List<String>) : Stmt() {
    override fun fields() = listOf("names" to names)
}

class Nonlocal(@JvmField val names: List<String>) : Stmt() {
    override fun fields() = listOf("names" to names)
}

class ExprStmt(@JvmField val value: Expr) : Stmt() {
    override val pyName get() = "Expr"
    override fun fields() = listOf("value" to value)
}

class Pass : Stmt() {
    override fun fields() = emptyList<Pair<String, Any?>>()
}

class Break : Stmt() {
    override fun fields() = emptyList<Pair<String, Any?>>()
}

class Continue : Stmt() {
    override fun fields() = emptyList<Pair<String, Any?>>()
}

// ---------------------------------------------------------------------- expressions

sealed class Expr : Node() {
    /** Whether the source had it in parentheses (`(x): int` is not a simple annotation target). */
    @JvmField var parenthesized = false
}

class BoolOp(@JvmField val op: BoolOpKind, @JvmField val values: List<Expr>) : Expr() {
    override fun fields() = listOf("op" to op, "values" to values)
}

class NamedExpr(@JvmField val target: Name, @JvmField val value: Expr) : Expr() {
    override fun fields() = listOf("target" to target, "value" to value)
}

class BinOp(@JvmField val left: Expr, @JvmField val op: OpKind, @JvmField val right: Expr) : Expr() {
    override fun fields() = listOf("left" to left, "op" to op, "right" to right)
}

class UnaryOp(@JvmField val op: UnaryOpKind, @JvmField val operand: Expr) : Expr() {
    override fun fields() = listOf("op" to op, "operand" to operand)
}

class Lambda(@JvmField val args: Arguments, @JvmField val body: Expr) : Expr() {
    override fun fields() = listOf("args" to args, "body" to body)
}

class IfExp(@JvmField val test: Expr, @JvmField val body: Expr, @JvmField val orelse: Expr) : Expr() {
    override fun fields() = listOf("test" to test, "body" to body, "orelse" to orelse)
}

/** A dict display: a null key is `**value`. */
class DictExpr(@JvmField val keys: List<Expr?>, @JvmField val values: List<Expr>) : Expr() {
    override val pyName get() = "Dict"
    override fun fields() = listOf("keys" to keys, "values" to values)
}

class SetExpr(@JvmField val elts: List<Expr>) : Expr() {
    override val pyName get() = "Set"
    override fun fields() = listOf("elts" to elts)
}

class ListComp(@JvmField val elt: Expr, @JvmField val generators: List<Comprehension>) : Expr() {
    override fun fields() = listOf("elt" to elt, "generators" to generators)
}

class SetComp(@JvmField val elt: Expr, @JvmField val generators: List<Comprehension>) : Expr() {
    override fun fields() = listOf("elt" to elt, "generators" to generators)
}

class DictComp(@JvmField val key: Expr, @JvmField val value: Expr, @JvmField val generators: List<Comprehension>) : Expr() {
    override fun fields() = listOf("key" to key, "value" to value, "generators" to generators)
}

class GeneratorExp(@JvmField val elt: Expr, @JvmField val generators: List<Comprehension>) : Expr() {
    override fun fields() = listOf("elt" to elt, "generators" to generators)
}

class Await(@JvmField val value: Expr) : Expr() {
    override fun fields() = listOf("value" to value)
}

class Yield(@JvmField val value: Expr?) : Expr() {
    override fun fields() = listOf("value" to value)
}

class YieldFrom(@JvmField val value: Expr) : Expr() {
    override fun fields() = listOf("value" to value)
}

class Compare(@JvmField val left: Expr, @JvmField val ops: List<CmpOpKind>, @JvmField val comparators: List<Expr>) : Expr() {
    override fun fields() = listOf("left" to left, "ops" to ops, "comparators" to comparators)
}

class Call(@JvmField val func: Expr, @JvmField val args: List<Expr>, @JvmField val keywords: List<Keyword>) : Expr() {
    override fun fields() = listOf("func" to func, "args" to args, "keywords" to keywords)
}

/** An f-string's replacement field: [conversion] -1 for none, else 's', 'r' or 'a' as an int. */
class FormattedValue(@JvmField val value: Expr, @JvmField val conversion: Int, @JvmField val formatSpec: Expr?) : Expr() {
    override fun fields() = listOf("value" to value, "conversion" to conversion, "format_spec" to formatSpec)
}

/** A t-string's replacement field: [str] its expression's source text. */
class Interpolation(@JvmField val value: Expr, @JvmField val str: String, @JvmField val conversion: Int, @JvmField val formatSpec: Expr?) : Expr() {
    override fun fields() = listOf("value" to value, "str" to PyStr(str), "conversion" to conversion, "format_spec" to formatSpec)
}

class JoinedStr(@JvmField val values: List<Expr>) : Expr() {
    override fun fields() = listOf("values" to values)
}

class TemplateStr(@JvmField val values: List<Expr>) : Expr() {
    override fun fields() = listOf("values" to values)
}

/** A literal: None, True, False, an int, a float, a complex, a str, bytes, `...`; [kind] "u" for a u-prefixed str. */
class Constant(@JvmField val value: Any?, @JvmField val kind: String? = null) : Expr() {
    override fun fields() = listOf("value" to ConstValue(value), "kind" to kind)
}

/** A Constant's value, which [PyAst.dump] prints even where it is None. */
class ConstValue(@JvmField val v: Any?)

class Attribute(@JvmField val value: Expr, @JvmField val attr: String, @JvmField var ctx: Ctx = Ctx.Load) : Expr() {
    override fun fields() = listOf("value" to value, "attr" to attr, "ctx" to ctx)
}

class Subscript(@JvmField val value: Expr, @JvmField val slice: Expr, @JvmField var ctx: Ctx = Ctx.Load) : Expr() {
    override fun fields() = listOf("value" to value, "slice" to slice, "ctx" to ctx)
}

class Starred(@JvmField val value: Expr, @JvmField var ctx: Ctx = Ctx.Load) : Expr() {
    override fun fields() = listOf("value" to value, "ctx" to ctx)
}

class Name(@JvmField val id: String, @JvmField var ctx: Ctx = Ctx.Load) : Expr() {
    override fun fields() = listOf("id" to id, "ctx" to ctx)
}

class ListExpr(@JvmField val elts: List<Expr>, @JvmField var ctx: Ctx = Ctx.Load) : Expr() {
    override val pyName get() = "List"
    override fun fields() = listOf("elts" to elts, "ctx" to ctx)
}

class TupleExpr(@JvmField val elts: List<Expr>, @JvmField var ctx: Ctx = Ctx.Load) : Expr() {
    override val pyName get() = "Tuple"
    override fun fields() = listOf("elts" to elts, "ctx" to ctx)
}

class Slice(@JvmField val lower: Expr?, @JvmField val upper: Expr?, @JvmField val step: Expr?) : Expr() {
    override fun fields() = listOf("lower" to lower, "upper" to upper, "step" to step)
}

// ---------------------------------------------------------------------- the other nodes

class Comprehension(@JvmField val target: Expr, @JvmField val iter: Expr, @JvmField val ifs: List<Expr>, @JvmField val isAsync: Int) : Node() {
    override val pyName get() = "comprehension"
    override fun fields() = listOf("target" to target, "iter" to iter, "ifs" to ifs, "is_async" to isAsync)
}

class ExceptHandler(@JvmField val type: Expr?, @JvmField val name: String?, @JvmField val body: List<Stmt>) : Node() {
    override fun fields() = listOf("type" to type, "name" to name, "body" to body)
}

class Arguments(@JvmField val posonlyargs: List<Arg>, @JvmField val args: List<Arg>, @JvmField val vararg: Arg?,
                @JvmField val kwonlyargs: List<Arg>, @JvmField val kwDefaults: List<Expr?>, @JvmField val kwarg: Arg?,
                @JvmField val defaults: List<Expr>) : Node() {
    override val pyName get() = "arguments"
    override fun fields() = listOf("posonlyargs" to posonlyargs, "args" to args, "vararg" to vararg, "kwonlyargs" to kwonlyargs,
        "kw_defaults" to kwDefaults, "kwarg" to kwarg, "defaults" to defaults)

    /** Every parameter, in order: positional-only, positional, *args, keyword-only, **kwargs. */
    fun all(): List<Arg> = posonlyargs + args + listOfNotNull(vararg) + kwonlyargs + listOfNotNull(kwarg)

    companion object {
        val EMPTY = Arguments(emptyList(), emptyList(), null, emptyList(), emptyList(), null, emptyList())
    }
}

class Arg(@JvmField val arg: String, @JvmField val annotation: Expr?) : Node() {
    override val pyName get() = "arg"
    override fun fields() = listOf("arg" to arg, "annotation" to annotation, "type_comment" to null)
}

/** A keyword argument: [arg] null for `**value`. */
class Keyword(@JvmField val arg: String?, @JvmField val value: Expr) : Node() {
    override val pyName get() = "keyword"
    override fun fields() = listOf("arg" to arg, "value" to value)
}

class Alias(@JvmField val name: String, @JvmField val asname: String?) : Node() {
    override val pyName get() = "alias"
    override fun fields() = listOf("name" to name, "asname" to asname)
}

class WithItem(@JvmField val contextExpr: Expr, @JvmField val optionalVars: Expr?) : Node() {
    override val pyName get() = "withitem"
    override fun fields() = listOf("context_expr" to contextExpr, "optional_vars" to optionalVars)
}

class MatchCase(@JvmField val pattern: Pattern, @JvmField val guard: Expr?, @JvmField val body: List<Stmt>) : Node() {
    override val pyName get() = "match_case"
    override fun fields() = listOf("pattern" to pattern, "guard" to guard, "body" to body)
}

sealed class Pattern : Node()

class MatchValue(@JvmField val value: Expr) : Pattern() {
    override fun fields() = listOf("value" to value)
}

/** `None`, `True`, `False`. */
class MatchSingleton(@JvmField val value: Any?) : Pattern() {
    override fun fields() = listOf("value" to ConstValue(value))
}

class MatchSequence(@JvmField val patterns: List<Pattern>) : Pattern() {
    override fun fields() = listOf("patterns" to patterns)
}

class MatchMapping(@JvmField val keys: List<Expr>, @JvmField val patterns: List<Pattern>, @JvmField val rest: String?) : Pattern() {
    override fun fields() = listOf("keys" to keys, "patterns" to patterns, "rest" to rest)
}

class MatchClass(@JvmField val cls: Expr, @JvmField val patterns: List<Pattern>, @JvmField val kwdAttrs: List<String>,
                 @JvmField val kwdPatterns: List<Pattern>) : Pattern() {
    override fun fields() = listOf("cls" to cls, "patterns" to patterns, "kwd_attrs" to kwdAttrs, "kwd_patterns" to kwdPatterns)
}

class MatchStar(@JvmField val name: String?) : Pattern() {
    override fun fields() = listOf("name" to name)
}

/** `p as name`; a capture (`name`) without [pattern]; `_` without either. */
class MatchAs(@JvmField val pattern: Pattern?, @JvmField val name: String?) : Pattern() {
    override fun fields() = listOf("pattern" to pattern, "name" to name)
}

class MatchOr(@JvmField val patterns: List<Pattern>) : Pattern() {
    override fun fields() = listOf("patterns" to patterns)
}

sealed class TypeParam : Node() {
    abstract val name: String
}

class TypeVar(override val name: String, @JvmField val bound: Expr?, @JvmField val defaultValue: Expr?) : TypeParam() {
    override fun fields() = listOf("name" to name, "bound" to bound, "default_value" to defaultValue)
}

class ParamSpec(override val name: String, @JvmField val defaultValue: Expr?) : TypeParam() {
    override fun fields() = listOf("name" to name, "default_value" to defaultValue)
}

class TypeVarTuple(override val name: String, @JvmField val defaultValue: Expr?) : TypeParam() {
    override fun fields() = listOf("name" to name, "default_value" to defaultValue)
}

/** `ast.dump`, as CPython 3.14 prints it by default (no positions; empty lists and None fields left out). */
object PyAst {
    @JvmStatic
    fun dump(n: Any?): String = StringBuilder().also { dump(n, it) }.toString()

    private fun dump(v: Any?, sb: StringBuilder) {
        when (v) {
            is Node -> {
                sb.append(v.pyName).append('(')
                var first = true
                for ((name, value) in v.fields()) {
                    if (value == null || value is List<*> && value.isEmpty()) continue
                    if (!first) sb.append(", ")
                    first = false
                    sb.append(name).append('=')
                    dump(value, sb)
                }
                sb.append(')')
            }
            is List<*> -> {
                sb.append('[')
                for ((i, x) in v.withIndex()) {
                    if (i > 0) sb.append(", ")
                    if (x == null) sb.append("None") else dump(x, sb)
                }
                sb.append(']')
            }
            is Enum<*> -> sb.append(v.name).append("()")
            is String -> sb.append(PyRt.strRepr(v))
            is ConstValue -> sb.append(constRepr(v.v))
            is Int -> sb.append(v)
            else -> sb.append(constRepr(v))
        }
    }

    /** A constant's repr, as Python prints it. */
    @JvmStatic
    fun constRepr(v: Any?): String = when (v) {
        null, PyNone -> "None"
        PyEllipsis -> "Ellipsis"
        is PyBytes -> v.repr()
        is PyComplex -> v.repr()
        else -> PyRt.repr(v)
    }
}
