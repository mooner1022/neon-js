package dev.mooner.neonpython

/**
 * An expression's source as CPython's Python/ast_unparse.c writes it: what `from __future__ import annotations`
 * makes an annotation (PEP 563). Not Lib/ast.py's unparse, which differs in its parentheses and its quotes.
 */
object PyUnparse {
    // the precedence levels, lowest first (ast_unparse.c's PR_...)
    private const val TUPLE = 0
    private const val TEST = 1     // if-else, lambda
    private const val OR = 2
    private const val AND = 3
    private const val NOT = 4
    private const val CMP = 5
    private const val EXPR = 6
    private const val BOR = EXPR
    private const val BXOR = 7
    private const val BAND = 8
    private const val SHIFT = 9
    private const val ARITH = 10
    private const val TERM = 11
    private const val FACTOR = 12
    private const val POWER = 13
    private const val AWAIT = 14
    private const val ATOM = 15

    /** [e] as an annotation's string (_PyAST_ExprAsUnicode). */
    @JvmStatic fun annotation(e: Expr): String = of(e, TEST)

    private fun of(e: Expr, level: Int): String = StringBuilder().also { expr(it, e, level) }.toString()

    private fun expr(w: StringBuilder, e: Expr, level: Int) {
        when (e) {
            is BoolOp -> {
                val (op, pr) = if (e.op == BoolOpKind.And) " and " to AND else " or " to OR
                if (level > pr) w.append('(')
                for ((i, v) in e.values.withIndex()) {
                    if (i > 0) w.append(op)
                    expr(w, v, pr + 1)
                }
                if (level > pr) w.append(')')
            }
            is BinOp -> {
                val (op, pr) = when (e.op) {
                    OpKind.Add -> " + " to ARITH
                    OpKind.Sub -> " - " to ARITH
                    OpKind.Mult -> " * " to TERM
                    OpKind.MatMult -> " @ " to TERM
                    OpKind.Div -> " / " to TERM
                    OpKind.Mod -> " % " to TERM
                    OpKind.LShift -> " << " to SHIFT
                    OpKind.RShift -> " >> " to SHIFT
                    OpKind.BitOr -> " | " to BOR
                    OpKind.BitXor -> " ^ " to BXOR
                    OpKind.BitAnd -> " & " to BAND
                    OpKind.FloorDiv -> " // " to TERM
                    OpKind.Pow -> " ** " to POWER
                }
                // (** is right-associative)
                val rassoc = if (e.op == OpKind.Pow) 1 else 0
                if (level > pr) w.append('(')
                expr(w, e.left, pr + rassoc)
                w.append(op)
                expr(w, e.right, pr + 1 - rassoc)
                if (level > pr) w.append(')')
            }
            is UnaryOp -> {
                val (op, pr) = when (e.op) {
                    UnaryOpKind.Invert -> "~" to FACTOR
                    UnaryOpKind.Not -> "not " to NOT
                    UnaryOpKind.UAdd -> "+" to FACTOR
                    UnaryOpKind.USub -> "-" to FACTOR
                }
                if (level > pr) w.append('(')
                w.append(op)
                expr(w, e.operand, pr)
                if (level > pr) w.append(')')
            }
            is Lambda -> {
                if (level > TEST) w.append('(')
                val positional = e.args.args.size + e.args.posonlyargs.size
                w.append(if (positional > 0) "lambda " else "lambda")
                args(w, e.args)
                w.append(": ")
                expr(w, e.body, TEST)
                if (level > TEST) w.append(')')
            }
            is IfExp -> {
                if (level > TEST) w.append('(')
                expr(w, e.body, TEST + 1)
                w.append(" if ")
                expr(w, e.test, TEST + 1)
                w.append(" else ")
                expr(w, e.orelse, TEST)
                if (level > TEST) w.append(')')
            }
            is DictExpr -> {
                w.append('{')
                for (i in e.values.indices) {
                    if (i > 0) w.append(", ")
                    val k = e.keys[i]
                    if (k != null) {
                        expr(w, k, TEST)
                        w.append(": ")
                        expr(w, e.values[i], TEST)
                    } else {
                        w.append("**")
                        expr(w, e.values[i], EXPR)
                    }
                }
                w.append('}')
            }
            is SetExpr -> elements(w, e.elts, "{", "}")
            is GeneratorExp -> {
                w.append('(')
                expr(w, e.elt, TEST)
                comprehensions(w, e.generators)
                w.append(')')
            }
            is ListComp -> {
                w.append('[')
                expr(w, e.elt, TEST)
                comprehensions(w, e.generators)
                w.append(']')
            }
            is SetComp -> {
                w.append('{')
                expr(w, e.elt, TEST)
                comprehensions(w, e.generators)
                w.append('}')
            }
            is DictComp -> {
                w.append('{')
                expr(w, e.key, TEST)
                w.append(": ")
                expr(w, e.value, TEST)
                comprehensions(w, e.generators)
                w.append('}')
            }
            is Yield -> {
                if (e.value == null) w.append("(yield)") else {
                    w.append("(yield ")
                    expr(w, e.value, TEST)
                    w.append(')')
                }
            }
            is YieldFrom -> {
                w.append("(yield from ")
                expr(w, e.value, TEST)
                w.append(')')
            }
            is Await -> {
                if (level > AWAIT) w.append('(')
                w.append("await ")
                expr(w, e.value, ATOM)
                if (level > AWAIT) w.append(')')
            }
            is Compare -> {
                if (level > CMP) w.append('(')
                expr(w, e.left, CMP + 1)
                for (i in e.ops.indices) {
                    w.append(when (e.ops[i]) {
                        CmpOpKind.Eq -> " == "
                        CmpOpKind.NotEq -> " != "
                        CmpOpKind.Lt -> " < "
                        CmpOpKind.LtE -> " <= "
                        CmpOpKind.Gt -> " > "
                        CmpOpKind.GtE -> " >= "
                        CmpOpKind.Is -> " is "
                        CmpOpKind.IsNot -> " is not "
                        CmpOpKind.In -> " in "
                        CmpOpKind.NotIn -> " not in "
                    })
                    expr(w, e.comparators[i], CMP + 1)
                }
                if (level > CMP) w.append(')')
            }
            is Call -> {
                expr(w, e.func, ATOM)
                // (a single generator expression argument: its own parentheses)
                val only = e.args.singleOrNull()
                if (only is GeneratorExp && e.keywords.isEmpty()) {
                    expr(w, only, TEST)
                    return
                }
                w.append('(')
                var first = true
                for (a in e.args) {
                    if (!first) w.append(", ")
                    first = false
                    expr(w, a, TEST)
                }
                for (k in e.keywords) {
                    if (!first) w.append(", ")
                    first = false
                    if (k.arg == null) w.append("**") else w.append(k.arg).append('=')
                    expr(w, k.value, TEST)
                }
                w.append(')')
            }
            is Constant -> {
                if (e.value === PyEllipsis) {
                    w.append("...")
                    return
                }
                if (e.kind != null) w.append(e.kind)
                constant(w, e.value)
            }
            is JoinedStr -> joinedStr(w, e, false)
            is TemplateStr -> {
                w.append('t')
                w.append(PyRt.repr(PyStr(body(e.values, false))))
            }
            is FormattedValue -> {
                fieldValue(w, e.value)
                conversion(w, e.conversion)
                formatSpec(w, e.formatSpec)
                w.append('}')
            }
            is Interpolation -> {
                // (its expression as its source wrote it)
                w.append(if (e.str.startsWith("{")) "{ " else "{").append(e.str)
                conversion(w, e.conversion)
                formatSpec(w, e.formatSpec)
                w.append('}')
            }
            is Attribute -> {
                expr(w, e.value, ATOM)
                // (an int needs a space before the dot: `1 .real`)
                val v = e.value
                w.append(if (v is Constant && (v.value is Long || v.value is java.math.BigInteger)) " ." else ".")
                w.append(e.attr)
            }
            is Subscript -> {
                expr(w, e.value, ATOM)
                w.append('[')
                expr(w, e.slice, TUPLE)
                w.append(']')
            }
            is Starred -> {
                w.append('*')
                expr(w, e.value, EXPR)
            }
            is Slice -> {
                e.lower?.let { expr(w, it, TEST) }
                w.append(':')
                e.upper?.let { expr(w, it, TEST) }
                e.step?.let {
                    w.append(':')
                    expr(w, it, TEST)
                }
            }
            is Name -> w.append(e.id)
            is ListExpr -> elements(w, e.elts, "[", "]")
            is TupleExpr -> {
                if (e.elts.isEmpty()) {
                    w.append("()")
                    return
                }
                if (level > TUPLE) w.append('(')
                for ((i, x) in e.elts.withIndex()) {
                    if (i > 0) w.append(", ")
                    expr(w, x, TEST)
                }
                if (e.elts.size == 1) w.append(',')
                if (level > TUPLE) w.append(')')
            }
            is NamedExpr -> {
                if (level > TUPLE) w.append('(')
                expr(w, e.target, ATOM)
                w.append(" := ")
                expr(w, e.value, ATOM)
                if (level > TUPLE) w.append(')')
            }
        }
    }

    private fun elements(w: StringBuilder, elts: List<Expr>, open: String, close: String) {
        w.append(open)
        for ((i, x) in elts.withIndex()) {
            if (i > 0) w.append(", ")
            expr(w, x, TEST)
        }
        w.append(close)
    }

    private fun comprehensions(w: StringBuilder, gens: List<Comprehension>) {
        for (g in gens) {
            w.append(if (g.isAsync == 1) " async for " else " for ")
            expr(w, g.target, TUPLE)
            w.append(" in ")
            expr(w, g.iter, TEST + 1)
            for (c in g.ifs) {
                w.append(" if ")
                expr(w, c, TEST + 1)
            }
        }
    }

    private fun arg(w: StringBuilder, a: Arg) {
        w.append(a.arg)
        if (a.annotation != null) {
            w.append(": ")
            expr(w, a.annotation, TEST)
        }
    }

    private fun args(w: StringBuilder, a: Arguments) {
        var first = true
        fun sep() {
            if (!first) w.append(", ")
            first = false
        }
        val posonly = a.posonlyargs.size
        val n = posonly + a.args.size
        for (i in 0 until n) {
            sep()
            arg(w, if (i < posonly) a.posonlyargs[i] else a.args[i - posonly])
            val di = i - n + a.defaults.size
            if (di >= 0) {
                w.append('=')
                expr(w, a.defaults[di], TEST)
            }
            if (posonly > 0 && i + 1 == posonly) w.append(", /")
        }
        // *args, or a bare * before keyword-only parameters
        if (a.vararg != null || a.kwonlyargs.isNotEmpty()) {
            sep()
            w.append('*')
            a.vararg?.let { arg(w, it) }
        }
        for ((i, k) in a.kwonlyargs.withIndex()) {
            sep()
            arg(w, k)
            a.kwDefaults.getOrNull(i)?.let {
                w.append('=')
                expr(w, it, TEST)
            }
        }
        if (a.kwarg != null) {
            sep()
            w.append("**")
            arg(w, a.kwarg)
        }
    }

    private fun constant(w: StringBuilder, v: Any?) {
        if (v is PyTuple) {
            w.append('(')
            for ((i, x) in v.items.withIndex()) {
                if (i > 0) w.append(", ")
                constant(w, x)
            }
            if (v.items.size == 1) w.append(',')
            w.append(')')
            return
        }
        var r = PyRt.repr(v)
        // (an infinity as a literal that reads back as one)
        if (v is Double && v.isInfinite() || v is PyComplex) r = r.replace("inf", "1e309")
        w.append(r)
    }

    /** An f-string's (or a t-string's) parts as its source between the quotes would be, braces doubled. */
    private fun body(values: List<Expr>, isFormatSpec: Boolean): String {
        val w = StringBuilder()
        for (v in values) element(w, v, isFormatSpec)
        return w.toString()
    }

    private fun element(w: StringBuilder, e: Expr, isFormatSpec: Boolean) {
        when (e) {
            is Constant -> w.append((e.value as PyStr).s.replace("{", "{{").replace("}", "}}"))
            is JoinedStr -> joinedStr(w, e, isFormatSpec)
            is FormattedValue, is Interpolation -> expr(w, e, TEST)
            else -> throw IllegalStateException("unknown expression kind inside f-string")
        }
    }

    private fun joinedStr(w: StringBuilder, e: JoinedStr, isFormatSpec: Boolean) {
        val b = body(e.values, isFormatSpec)
        if (isFormatSpec) w.append(b) else w.append('f').append(PyRt.repr(PyStr(b)))
    }

    /** A replacement field's `{` and value: parenthesized above a lambda's level, a space after a `{` it starts with. */
    private fun fieldValue(w: StringBuilder, v: Expr) {
        val s = of(v, TEST + 1)
        w.append(if (s.startsWith("{")) "{ " else "{").append(s)
    }

    private fun conversion(w: StringBuilder, c: Int) {
        if (c > 0) w.append('!').append(c.toChar())
    }

    private fun formatSpec(w: StringBuilder, spec: Expr?) {
        if (spec == null) return
        w.append(':')
        element(w, spec, true)
    }
}
