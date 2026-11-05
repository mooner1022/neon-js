package dev.mooner.neonjs.python

/** Python's syntax trees (the slice's statements and expressions; docs/PYTHON_PLATFORM.md §10). */
sealed class PyNode {
    @JvmField var line = 0
}

sealed class Expr : PyNode()
class Name(val id: String) : Expr()
class Constant(val value: Any?) : Expr()
class BinOp(val op: String, val left: Expr, val right: Expr) : Expr()
class UnaryOp(val op: String, val operand: Expr) : Expr()
class BoolOp(val op: String, val values: List<Expr>) : Expr()
/** `a < b <= c`: each comparison of neighbours, and of them all. */
class Compare(val left: Expr, val ops: List<String>, val comparators: List<Expr>) : Expr()
class IfExp(val test: Expr, val body: Expr, val orelse: Expr) : Expr()
class Call(val func: Expr, val args: List<Expr>) : Expr()
class Attribute(val value: Expr, val attr: String) : Expr()
class Subscript(val value: Expr, val index: Expr) : Expr()
class ListExpr(val elts: List<Expr>) : Expr()
class TupleExpr(val elts: List<Expr>) : Expr()
class DictExpr(val keys: List<Expr>, val values: List<Expr>) : Expr()

sealed class Stmt : PyNode()
class ExprStmt(val value: Expr) : Stmt()
class Assign(val targets: List<Expr>, val value: Expr) : Stmt()
class AugAssign(val target: Expr, val op: String, val value: Expr) : Stmt()
class If(val test: Expr, val body: List<Stmt>, val orelse: List<Stmt>) : Stmt()
class While(val test: Expr, val body: List<Stmt>, val orelse: List<Stmt>) : Stmt()
class For(val target: Expr, val iter: Expr, val body: List<Stmt>, val orelse: List<Stmt>) : Stmt()
class FunctionDef(val name: String, val params: List<String>, val body: List<Stmt>) : Stmt()
class Return(val value: Expr?) : Stmt()
class Global(val names: List<String>) : Stmt()
object Pass : Stmt()
object Break : Stmt()
object Continue : Stmt()

/**
 * A recursive-descent parser of Python's grammar (CPython's Grammar/python.gram, the slice's part of it): statements by
 * their first token, expressions by precedence from the conditional expression down to atoms and their trailers.
 */
class PyParser(src: String) {
    private val toks = PyLexer(src).tokenize()
    private var p = 0

    fun parseModule(): List<Stmt> {
        val body = ArrayList<Stmt>()
        while (!at(PyToken.Kind.END)) {
            if (at(PyToken.Kind.NEWLINE)) { p++; continue }
            body.addAll(statement())
        }
        return body
    }

    // ------------------------------------------------------------------ tokens

    private val tok: PyToken get() = toks[p]
    private fun at(k: PyToken.Kind) = tok.kind == k
    private fun atOp(s: String) = tok.kind == PyToken.Kind.OP && tok.text == s
    private fun atName(s: String) = tok.kind == PyToken.Kind.NAME && tok.text == s
    private fun next(): PyToken = toks[p++]
    private fun error(msg: String = "invalid syntax"): Nothing = throw PySyntaxError(msg, tok.line, tok.col)
    private fun expectOp(s: String): PyToken = if (atOp(s)) next() else error(if (tok.kind == PyToken.Kind.END) "unexpected EOF while parsing" else "expected '$s'")
    private fun expect(k: PyToken.Kind): PyToken = if (at(k)) next() else error(
        when (k) {
            PyToken.Kind.INDENT -> "expected an indented block"
            PyToken.Kind.NEWLINE -> "invalid syntax"
            else -> "expected $k"
        },
    )

    private fun <T : PyNode> T.at(line: Int): T = also { it.line = line }

    // ------------------------------------------------------------------ statements

    private fun statement(): List<Stmt> {
        val line = tok.line
        if (tok.kind == PyToken.Kind.NAME) when (tok.text) {
            "if" -> return listOf(ifStmt().at(line))
            "while" -> {
                next()
                val test = expression()
                val body = block()
                val orelse = if (atName("else")) { next(); block() } else emptyList()
                return listOf(While(test, body, orelse).at(line))
            }
            "for" -> {
                next()
                val target = targetList()
                if (!atName("in")) error("expected 'in'")
                next()
                val iter = expressionList()
                val body = block()
                val orelse = if (atName("else")) { next(); block() } else emptyList()
                return listOf(For(target, iter, body, orelse).at(line))
            }
            "def" -> {
                next()
                val name = expect(PyToken.Kind.NAME).text
                expectOp("(")
                val params = ArrayList<String>()
                while (!atOp(")")) {
                    val n = expect(PyToken.Kind.NAME).text
                    if (n in params) throw PySyntaxError("duplicate argument '$n' in function definition", tok.line, tok.col)
                    if (atOp("=") || atOp(":")) error("parameter defaults and annotations are not supported yet")
                    params.add(n)
                    if (!atOp(")")) expectOp(",")
                }
                next()
                return listOf(FunctionDef(name, params, block()).at(line))
            }
        }
        val out = ArrayList<Stmt>()
        out.add(simpleStatement().at(line))
        while (atOp(";")) {
            next()
            if (at(PyToken.Kind.NEWLINE)) break
            out.add(simpleStatement().at(tok.line))
        }
        expect(PyToken.Kind.NEWLINE)
        return out
    }

    private fun ifStmt(): Stmt {
        val line = tok.line
        next() // if / elif
        val test = expression()
        val body = block()
        val orelse = when {
            atName("elif") -> listOf(ifStmt())
            atName("else") -> { next(); block() }
            else -> emptyList()
        }
        return If(test, body, orelse).at(line)
    }

    /** `:` and the statements of a block: indented lines, or simple statements on the same line. */
    private fun block(): List<Stmt> {
        expectOp(":")
        if (!at(PyToken.Kind.NEWLINE)) {
            val out = ArrayList<Stmt>()
            out.add(simpleStatement().at(tok.line))
            while (atOp(";")) {
                next()
                if (at(PyToken.Kind.NEWLINE)) break
                out.add(simpleStatement().at(tok.line))
            }
            expect(PyToken.Kind.NEWLINE)
            return out
        }
        next()
        expect(PyToken.Kind.INDENT)
        val out = ArrayList<Stmt>()
        while (!at(PyToken.Kind.DEDENT) && !at(PyToken.Kind.END)) {
            if (at(PyToken.Kind.NEWLINE)) { p++; continue }
            out.addAll(statement())
        }
        if (at(PyToken.Kind.DEDENT)) next()
        return out
    }

    private fun simpleStatement(): Stmt {
        if (tok.kind == PyToken.Kind.NAME) when (tok.text) {
            "pass" -> { next(); return Pass }
            "break" -> { next(); return Break }
            "continue" -> { next(); return Continue }
            "return" -> {
                next()
                return Return(if (at(PyToken.Kind.NEWLINE) || atOp(";")) null else expressionList())
            }
            "global" -> {
                next()
                val names = arrayListOf(expect(PyToken.Kind.NAME).text)
                while (atOp(",")) { next(); names.add(expect(PyToken.Kind.NAME).text) }
                return Global(names)
            }
            "import", "from", "class", "try", "with", "raise", "assert", "del", "lambda", "yield", "async", "nonlocal" ->
                error("'${tok.text}' is not supported yet")
        }
        val first = expressionList()
        if (tok.kind == PyToken.Kind.OP && tok.text.length >= 2 && tok.text.endsWith("=") && tok.text !in setOf("==", "<=", ">=", "!=")) {
            val op = next().text.dropLast(1)
            checkTarget(first, aug = true)
            return AugAssign(first, op, expressionList())
        }
        if (atOp("=")) {
            val targets = arrayListOf(first)
            var value: Expr
            while (true) {
                next()
                value = expressionList()
                if (!atOp("=")) break
                targets.add(value)
            }
            for (t in targets) checkTarget(t, aug = false)
            return Assign(targets, value)
        }
        return ExprStmt(first)
    }

    private fun checkTarget(e: Expr, aug: Boolean) {
        when (e) {
            is Name, is Subscript, is Attribute -> {}
            is TupleExpr, is ListExpr -> if (aug) error("'tuple' is an illegal expression for augmented assignment")
                else (if (e is TupleExpr) e.elts else (e as ListExpr).elts).forEach { checkTarget(it, false) }
            else -> error("cannot assign to ${if (e is Call) "function call" else "expression"}")
        }
    }

    /** A for loop's target: a name, or names separated by commas (a tuple). */
    private fun targetList(): Expr {
        val line = tok.line
        val first = orExpr()
        if (!atOp(",")) return first.also { checkTarget(it, false) }
        val elts = arrayListOf(first)
        while (atOp(",")) {
            next()
            if (atName("in")) break
            elts.add(orExpr())
        }
        return TupleExpr(elts).at(line).also { checkTarget(it, false) }
    }

    // ------------------------------------------------------------------ expressions

    /** Expressions separated by commas: a tuple where there are several (or a trailing comma). */
    private fun expressionList(): Expr {
        val line = tok.line
        val first = expression()
        if (!atOp(",")) return first
        val elts = arrayListOf(first)
        while (atOp(",")) {
            next()
            if (at(PyToken.Kind.NEWLINE) || atOp("=") || atOp(")") || atOp(";") || tok.kind == PyToken.Kind.OP && tok.text.endsWith("=") && tok.text.length >= 2) break
            elts.add(expression())
        }
        return TupleExpr(elts).at(line)
    }

    private fun expression(): Expr {
        val line = tok.line
        val body = orTest()
        if (!atName("if")) return body
        next()
        val test = orTest()
        if (!atName("else")) error("expected 'else' after 'if' expression")
        next()
        return IfExp(test, body, expression()).at(line)
    }

    private fun orTest(): Expr {
        val line = tok.line
        val first = andTest()
        if (!atName("or")) return first
        val values = arrayListOf(first)
        while (atName("or")) { next(); values.add(andTest()) }
        return BoolOp("or", values).at(line)
    }

    private fun andTest(): Expr {
        val line = tok.line
        val first = notTest()
        if (!atName("and")) return first
        val values = arrayListOf(first)
        while (atName("and")) { next(); values.add(notTest()) }
        return BoolOp("and", values).at(line)
    }

    private fun notTest(): Expr {
        if (atName("not")) {
            val line = next().line
            return UnaryOp("not", notTest()).at(line)
        }
        return comparison()
    }

    private fun comparison(): Expr {
        val line = tok.line
        val left = orExpr()
        val ops = ArrayList<String>()
        val rights = ArrayList<Expr>()
        while (true) {
            val op = when {
                tok.kind == PyToken.Kind.OP && tok.text in setOf("<", ">", "==", ">=", "<=", "!=") -> next().text
                atName("in") -> { next(); "in" }
                atName("not") && toks[p + 1].kind == PyToken.Kind.NAME && toks[p + 1].text == "in" -> { next(); next(); "not in" }
                atName("is") -> { next(); if (atName("not")) { next(); "is not" } else "is" }
                else -> break
            }
            ops.add(op)
            rights.add(orExpr())
        }
        return if (ops.isEmpty()) left else Compare(left, ops, rights).at(line)
    }

    private fun binary(operand: () -> Expr, vararg ops: String): Expr {
        val line = tok.line
        var left = operand()
        while (tok.kind == PyToken.Kind.OP && tok.text in ops) {
            val op = next().text
            left = BinOp(op, left, operand()).at(line)
        }
        return left
    }

    private fun orExpr(): Expr = binary(::xorExpr, "|")
    private fun xorExpr(): Expr = binary(::andExpr, "^")
    private fun andExpr(): Expr = binary(::shiftExpr, "&")
    private fun shiftExpr(): Expr = binary(::arith, "<<", ">>")
    private fun arith(): Expr = binary(::term, "+", "-")
    private fun term(): Expr = binary(::factor, "*", "/", "//", "%", "@")

    private fun factor(): Expr {
        if (tok.kind == PyToken.Kind.OP && tok.text in setOf("-", "+", "~")) {
            val t = next()
            return UnaryOp(t.text, factor()).at(t.line)
        }
        return power()
    }

    private fun power(): Expr {
        val line = tok.line
        val base = primary()
        if (!atOp("**")) return base
        next()
        // right-associative, and binds tighter than a unary minus on its left but not on its right: -2 ** 2 is -4
        return BinOp("**", base, factor()).at(line)
    }

    private fun primary(): Expr {
        var e = atom()
        while (true) {
            val line = tok.line
            e = when {
                atOp("(") -> {
                    next()
                    val args = ArrayList<Expr>()
                    while (!atOp(")")) {
                        if (tok.kind == PyToken.Kind.NAME && toks[p + 1].kind == PyToken.Kind.OP && toks[p + 1].text == "=") error("keyword arguments are not supported yet")
                        if (atOp("*") || atOp("**")) error("argument unpacking is not supported yet")
                        args.add(expression())
                        if (!atOp(")")) expectOp(",")
                    }
                    next()
                    Call(e, args).at(line)
                }
                atOp("[") -> {
                    next()
                    if (atOp(":")) error("slices are not supported yet")
                    val index = expressionList()
                    if (atOp(":")) error("slices are not supported yet")
                    expectOp("]")
                    Subscript(e, index).at(line)
                }
                atOp(".") -> {
                    next()
                    Attribute(e, expect(PyToken.Kind.NAME).text).at(line)
                }
                else -> return e
            }
        }
    }

    private fun atom(): Expr {
        val t = tok
        when (t.kind) {
            PyToken.Kind.NUMBER -> { next(); return Constant(t.value).at(t.line) }
            PyToken.Kind.STRING -> {
                // adjacent strings are one
                val sb = StringBuilder()
                while (at(PyToken.Kind.STRING)) sb.append(next().value as String)
                return Constant(PyStr(sb.toString())).at(t.line)
            }
            PyToken.Kind.NAME -> {
                next()
                return when (t.text) {
                    "None" -> Constant(PyNone)
                    "True" -> Constant(PyBool.TRUE)
                    "False" -> Constant(PyBool.FALSE)
                    in KEYWORDS -> { p--; error() }
                    else -> Name(t.text)
                }.at(t.line)
            }
            PyToken.Kind.OP -> when (t.text) {
                "(" -> {
                    next()
                    if (atOp(")")) { next(); return TupleExpr(emptyList()).at(t.line) }
                    val first = expression()
                    if (atOp(")")) { next(); return first }
                    val elts = arrayListOf(first)
                    while (atOp(",")) {
                        next()
                        if (atOp(")")) break
                        elts.add(expression())
                    }
                    expectOp(")")
                    return TupleExpr(elts).at(t.line)
                }
                "[" -> {
                    next()
                    val elts = ArrayList<Expr>()
                    while (!atOp("]")) {
                        elts.add(expression())
                        if (atName("for")) error("comprehensions are not supported yet")
                        if (!atOp("]")) expectOp(",")
                    }
                    next()
                    return ListExpr(elts).at(t.line)
                }
                "{" -> {
                    next()
                    val keys = ArrayList<Expr>()
                    val values = ArrayList<Expr>()
                    while (!atOp("}")) {
                        keys.add(expression())
                        if (!atOp(":")) error("sets are not supported yet")
                        next()
                        values.add(expression())
                        if (!atOp("}")) expectOp(",")
                    }
                    next()
                    return DictExpr(keys, values).at(t.line)
                }
                else -> error()
            }
            PyToken.Kind.NEWLINE, PyToken.Kind.END -> error(if (t.kind == PyToken.Kind.END) "unexpected EOF while parsing" else "invalid syntax")
            else -> error()
        }
    }

    private companion object {
        val KEYWORDS = setOf("and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif",
            "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "nonlocal", "not",
            "or", "pass", "raise", "return", "try", "while", "with", "yield")
    }
}
