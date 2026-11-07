package dev.mooner.neonpython

/**
 * compile() of an AST object (docs/PYTHON_PLATFORM.md §12, S5): `_ast` instances read as CPython's
 * Python/Python-ast.c reads them (obj2ast, by the ASDL in [PyAsdl]: its TypeErrors for a missing field or a value
 * of the wrong kind, its ValueErrors for a required one that is None), checked as Python/ast.c's _PyAST_Validate
 * checks them (its ValueErrors for a tree no source gives), then made into NeonPy's syntax tree and compiled as a
 * parsed source is. Positions are the nodes' (columns as they are: no source to make UTF-8 offsets characters).
 */
object PyAstCompile {
    private const val ONLY_AST = 0x400

    /** A node as read: its constructor, its fields' values in ASDL order, its attributes' (positions). */
    private class A(@JvmField val c: PyAsdl.Cons, @JvmField val v: Array<Any?>) {
        @JvmField var line = 0
        @JvmField var col = 0
        @JvmField var endLine = 0
        @JvmField var endCol = 0
        val name: String get() = c.name

        operator fun get(f: String): Any? = v[c.at(f)]
        fun node(f: String): A? = get(f) as A?
        fun req(f: String): A = get(f) as A
        @Suppress("UNCHECKED_CAST")
        fun list(f: String): List<Any?> = get(f) as List<Any?>
        fun str(f: String): String? = get(f) as String?
        fun int(f: String): Int = get(f) as Int
    }

    /** Whether [o] is an `ast.AST` (PyAST_Check). */
    @JvmStatic
    fun isAst(ctx: PyContext, o: Any?): Boolean = PyTypes.isInstance(o, PyRt.getAttr(PyImport.importModule(ctx, "_ast"), "AST"))

    /**
     * compile([source], [filename], [mode], [flags]), an AST object: the node mode wants (PyAst_CheckMode), read and
     * validated; with PyCF_ONLY_AST a new tree of it (as PyAST_mod2obj makes one), else its code.
     */
    @JvmStatic
    fun compile(ctx: PyContext, source: Any?, filename: String, mode: String, flags: Int, future: Boolean): Any? {
        val r = Reader(ctx)
        val want = when (mode) {
            "exec" -> "Module"
            "eval" -> "Expression"
            else -> "Interactive"
        }
        if (!r.isA(source, want)) throw PyRt.typeError("expected $want node, got ${PyTypes.name(source)}")
        val m = r.read(PyAsdl.TYPES["mod"]!!, source) as A
        Validator.mod(m)
        val tree = Builder.mod(m)
        if (flags and ONLY_AST != 0) {
            // (_PyCompile_AstPreprocess's check of the features `from __future__` imports)
            PyCompiler.futureFeatures((tree as? Module)?.body ?: (tree as? Interactive)?.body ?: emptyList())
            return Writer(r).node(m)
        }
        val cb = PyCompiler.compileTree(tree, mode, future)
        return PyCode(PyCompiler.withFilename(cb, filename), mode, filename).also { (cb.dialectData as PyCodeInfo).codeObject = it }
    }

    // ---------------------------------------------------------------------- obj2ast

    /** Python-ast.c's obj2ast_* functions, by the ASDL: `_ast` objects read into [A]s. */
    private class Reader(ctx: PyContext) {
        val ast: Any? = PyImport.importModule(ctx, "_ast")
        val classes = HashMap<String, Any?>()

        fun cls(name: String): Any? = classes.getOrPut(name) { PyRt.getAttr(ast, name) }
        fun isA(o: Any?, name: String): Boolean = PyTypes.isInstance(o, cls(name))

        private fun expected(t: PyAsdl.Type, o: Any?) = PyRt.typeError("expected some sort of ${t.name}, but got ${PyRt.repr(o)}")

        /**
         * obj2ast_<t>: an operator's or a context's constructor name; a product's fields read from any object; a
         * sum's node (None null), its attributes read first, then its class's constructor's fields.
         */
        fun read(t: PyAsdl.Type, o: Any?): Any? {
            if (t.simple) {
                for (c in t.cons) if (isA(o, c.name)) return c.name
                throw expected(t, o)
            }
            if (t.product) {
                val c = t.cons[0]
                val v = fields(c, o)
                return made(c, v, attributes(t, o, c.name))
            }
            if (o === PyNone) return null
            val pos = attributes(t, o, t.name)
            for (c in t.cons) if (isA(o, c.name)) return made(c, fields(c, o), pos)
            throw expected(t, o)
        }

        /** The attributes (lineno, col_offset, end_lineno, end_col_offset) of [o]; an end the start where None. */
        private fun attributes(t: PyAsdl.Type, o: Any?, owner: String): IntArray? {
            if (t.attributes.isEmpty()) return null
            val a = IntArray(4)
            for ((i, f) in t.attributes.withIndex()) a[i] = field(f, owner, o, if (i >= 2) a[i - 2] else 0) as Int
            return a
        }

        private fun fields(c: PyAsdl.Cons, o: Any?): Array<Any?> = Array(c.fields.size) { i -> field(c.fields[i], c.name, o, 0) }

        /** The constructor's call (_PyAST_<name>): its required fields given (a list never is missing). */
        private fun made(c: PyAsdl.Cons, v: Array<Any?>, pos: IntArray?): A {
            for ((i, f) in c.fields.withIndex()) {
                if (v[i] == null && !f.opt && !f.seq && f.type != "int") throw PyException("ValueError", "field '${f.name}' is required for ${c.name}")
            }
            return A(c, v).also { a ->
                if (pos != null) {
                    a.line = pos[0]; a.col = pos[1]; a.endLine = pos[2]; a.endCol = pos[3]
                }
            }
        }

        /**
         * Field [f] of [o] ([owner] its constructor's name, or its type's for an attribute): a list's items each
         * read (a missing list empty); a missing optional one null ([default] for an int).
         */
        private fun field(f: PyAsdl.Field, owner: String, o: Any?, default: Int): Any? {
            val x = PyClasses.attrOrNull(o, f.name)
            if (f.seq) {
                if (x == null) return emptyList<Any?>()
                if (x !is PyList) throw PyRt.typeError("$owner field \"${f.name}\" must be a list, not a ${PyTypes.name(x)}")
                val n = x.items.size
                val out = ArrayList<Any?>(n)
                for (i in 0 until n) {
                    out.add(value(f, x.items[i]))
                    if (x.items.size != n) throw PyException("RuntimeError", "$owner field \"${f.name}\" changed size during iteration")
                }
                return out
            }
            if (x == null && !f.opt) throw PyRt.typeError("required field \"${f.name}\" missing from $owner")
            if (x == null || f.opt && x === PyNone) return if (f.type == "int") default else null
            return value(f, x)
        }

        /** A value of [f]'s type: an identifier a str (or None), a string a str or bytes, an int a C int, a node read. */
        private fun value(f: PyAsdl.Field, x: Any?): Any? = when (f.type) {
            "identifier" -> when {
                x === PyNone -> null
                x is PyStr && PyTypes.typeOf(x) === PyTypes.STR -> x.s
                else -> throw PyRt.typeError("AST identifier must be of type str")
            }
            "string" -> if (PyTypes.typeOf(x).let { it === PyTypes.STR || it === PyTypes.BYTES }) x else throw PyRt.typeError("AST string must be of type str")
            "int" -> {
                if (!PyTypes.typeOf(x).isSubtype(PyTypes.INT)) throw PyException("ValueError", "invalid integer value: ${PyRt.repr(x)}")
                PyBinascii.cInt(x)
            }
            "constant" -> x
            else -> read(PyAsdl.TYPES[f.type]!!, x)
        }
    }

    // ---------------------------------------------------------------------- _PyAST_Validate

    /** Python/ast.c's checks, its ValueErrors (and a TypeError or two) as it raises them, in its order. */
    private object Validator {
        private fun fail(msg: String): Nothing = throw PyException("ValueError", msg)

        private fun positions(n: A) {
            if (n.line > n.endLine) fail("AST node line range (${n.line}, ${n.endLine}) is not valid")
            if (n.line < 0 && n.endLine != n.line || n.col < 0 && n.col != n.endCol) {
                fail("AST node column range (${n.col}, ${n.endCol}) for line range (${n.line}, ${n.endLine}) is not valid")
            }
            if (n.line == n.endLine && n.col > n.endCol) fail("line ${n.line}, column ${n.col}-${n.endCol} is not a valid range")
        }

        private fun name(id: String?) {
            if (id == "None" || id == "True" || id == "False") fail("identifier field can't represent '$id' constant")
        }

        fun mod(m: A) = when (m.name) {
            "Module", "Interactive" -> stmts(m.list("body"))
            "Expression" -> expr(m.req("body"), "Load")
            else -> {
                exprs(m.list("argtypes"), "Load", false)
                expr(m.req("returns"), "Load")
            }
        }

        private fun stmts(seq: List<Any?>) {
            for (s in seq) stmt(s as A? ?: fail("None disallowed in statement list"))
        }

        private fun exprs(seq: List<Any?>, ctx: String, nullOk: Boolean) {
            for (e in seq) {
                if (e != null) expr(e as A, ctx) else if (!nullOk) fail("None disallowed in expression list")
            }
        }

        private fun nonEmpty(seq: List<Any?>, what: String, owner: String) {
            if (seq.isEmpty()) fail("empty $what on $owner")
        }

        private fun body(seq: List<Any?>, owner: String) {
            nonEmpty(seq, "body", owner)
            stmts(seq)
        }

        /** (where CPython's validator would read a None: its type's list, as for statements and expressions) */
        private fun noneIn(seq: List<Any?>, what: String) {
            if (seq.any { it == null }) fail("None disallowed in $what list")
        }

        private fun comprehension(gens: List<Any?>) {
            if (gens.isEmpty()) fail("comprehension with no generators")
            for (g in gens) {
                g as A
                expr(g.req("target"), "Store")
                expr(g.req("iter"), "Load")
                exprs(g.list("ifs"), "Load", false)
            }
        }

        private fun keywords(seq: List<Any?>) {
            for (k in seq) expr((k as A).req("value"), "Load")
        }

        private fun args(seq: List<Any?>) {
            for (a in seq) {
                a as A
                positions(a)
                a.node("annotation")?.let { expr(it, "Load") }
            }
        }

        private fun arguments(a: A) {
            args(a.list("posonlyargs"))
            args(a.list("args"))
            a.node("vararg")?.node("annotation")?.let { expr(it, "Load") }
            args(a.list("kwonlyargs"))
            a.node("kwarg")?.node("annotation")?.let { expr(it, "Load") }
            if (a.list("defaults").size > a.list("posonlyargs").size + a.list("args").size) fail("more positional defaults than args on arguments")
            if (a.list("kw_defaults").size != a.list("kwonlyargs").size) fail("length of kwonlyargs is not the same as kw_defaults on arguments")
            exprs(a.list("defaults"), "Load", false)
            exprs(a.list("kw_defaults"), "Load", true)
        }

        /** A Constant's value: None, Ellipsis, an int, float, complex, bool, str or bytes, or a tuple or frozenset of them. */
        private fun constant(v: Any?) {
            if (v === PyNone || v === PyEllipsis) return
            val t = PyTypes.typeOf(v)
            if (t === PyTypes.INT || t === PyTypes.FLOAT || t === PyTypes.COMPLEX || t === PyTypes.BOOL || t === PyTypes.STR || t === PyTypes.BYTES) return
            if (t === PyTypes.TUPLE || t === PyTypes.FROZENSET) {
                val it = PyRt.iter(v)
                while (true) {
                    val x = it.next()
                    if (x === PyIterator.STOP) break
                    constant(x)
                }
                return
            }
            throw PyRt.typeError("got an invalid type in Constant: ${PyTypes.name(v)}")
        }

        fun expr(e: A, ctx: String) {
            positions(e)
            val actual = when (e.name) {
                "Attribute", "Subscript", "Starred", "List", "Tuple" -> e["ctx"] as String
                "Name" -> {
                    name(e.str("id"))
                    e["ctx"] as String
                }
                else -> {
                    if (ctx != "Load") fail("expression which can't be assigned to in $ctx context")
                    null
                }
            }
            if (actual != null && actual != ctx) fail("expression must have $ctx context but has $actual instead")
            when (e.name) {
                "BoolOp" -> {
                    if (e.list("values").size < 2) fail("BoolOp with less than 2 values")
                    exprs(e.list("values"), "Load", false)
                }
                "BinOp" -> {
                    expr(e.req("left"), "Load")
                    expr(e.req("right"), "Load")
                }
                "UnaryOp" -> expr(e.req("operand"), "Load")
                "Lambda" -> {
                    arguments(e.req("args"))
                    expr(e.req("body"), "Load")
                }
                "IfExp" -> for (f in listOf("test", "body", "orelse")) expr(e.req(f), "Load")
                "Dict" -> {
                    if (e.list("keys").size != e.list("values").size) fail("Dict doesn't have the same number of keys as values")
                    // (a None key: `**d` in the display)
                    exprs(e.list("keys"), "Load", true)
                    exprs(e.list("values"), "Load", false)
                }
                "Set" -> exprs(e.list("elts"), "Load", false)
                "ListComp", "SetComp", "GeneratorExp" -> {
                    comprehension(e.list("generators"))
                    expr(e.req("elt"), "Load")
                }
                "DictComp" -> {
                    comprehension(e.list("generators"))
                    expr(e.req("key"), "Load")
                    expr(e.req("value"), "Load")
                }
                "Yield" -> e.node("value")?.let { expr(it, "Load") }
                "YieldFrom", "Await" -> expr(e.req("value"), "Load")
                "Compare" -> {
                    if (e.list("comparators").isEmpty()) fail("Compare with no comparators")
                    if (e.list("comparators").size != e.list("ops").size) fail("Compare has a different number of comparators and operands")
                    exprs(e.list("comparators"), "Load", false)
                    expr(e.req("left"), "Load")
                }
                "Call" -> {
                    expr(e.req("func"), "Load")
                    exprs(e.list("args"), "Load", false)
                    keywords(e.list("keywords"))
                }
                "Constant" -> constant(e["value"])
                "JoinedStr", "TemplateStr" -> exprs(e.list("values"), "Load", false)
                "FormattedValue", "Interpolation" -> {
                    expr(e.req("value"), "Load")
                    e.node("format_spec")?.let { expr(it, "Load") }
                }
                "Attribute" -> expr(e.req("value"), "Load")
                "Subscript" -> {
                    expr(e.req("slice"), "Load")
                    expr(e.req("value"), "Load")
                }
                "Starred" -> expr(e.req("value"), ctx)
                "Slice" -> for (f in listOf("lower", "upper", "step")) e.node(f)?.let { expr(it, "Load") }
                "List", "Tuple" -> exprs(e.list("elts"), ctx, false)
                "NamedExpr" -> {
                    if (e.req("target").name != "Name") throw PyRt.typeError("NamedExpr target must be a Name")
                    expr(e.req("value"), "Load")
                }
            }
        }

        private fun isConst(e: A) = e.name == "Constant"

        /** A literal number: a real (an int, a float) or an imaginary (a complex), exactly. */
        private fun literalNumber(e: A, real: Boolean, imaginary: Boolean): Boolean {
            val t = PyTypes.typeOf(e["value"])
            return real && (t === PyTypes.FLOAT || t === PyTypes.INT) || imaginary && t === PyTypes.COMPLEX
        }

        private fun literalNegative(e: A, real: Boolean, imaginary: Boolean): Boolean {
            if (e["op"] != "USub") return false
            val operand = e.req("operand")
            return isConst(operand) && literalNumber(operand, real, imaginary)
        }

        /** `real + imaginary` or `real - imaginary`, the real maybe negated. */
        private fun literalComplex(e: A): Boolean {
            if (e["op"] != "Add" && e["op"] != "Sub") return false
            val left = e.req("left")
            val right = e.req("right")
            val leftOk = when (left.name) {
                "Constant" -> literalNumber(left, real = true, imaginary = false)
                "UnaryOp" -> literalNegative(left, real = true, imaginary = false)
                else -> false
            }
            return leftOk && isConst(right) && literalNumber(right, real = false, imaginary = true)
        }

        /** A value pattern's expression: a literal (not None, True, False, `...` or a tuple), an attribute lookup. */
        private fun matchValue(e: A) {
            expr(e, "Load")
            when (e.name) {
                "Constant" -> {
                    val t = PyTypes.typeOf(e["value"])
                    if (t === PyTypes.INT || t === PyTypes.FLOAT || t === PyTypes.BYTES || t === PyTypes.COMPLEX || t === PyTypes.STR) return
                    fail("unexpected constant inside of a literal pattern")
                }
                "Attribute", "JoinedStr", "TemplateStr" -> return
                "UnaryOp" -> if (literalNegative(e, real = true, imaginary = true)) return
                "BinOp" -> if (literalComplex(e)) return
            }
            fail("patterns may only match literals and attribute lookups")
        }

        private fun capture(n: String) {
            if (n == "_") fail("can't capture name '_' in patterns")
            name(n)
        }

        private fun patterns(seq: List<Any?>, starOk: Boolean) {
            noneIn(seq, "pattern")
            for (p in seq) pattern(p as A, starOk)
        }

        private fun pattern(p: A, starOk: Boolean) {
            positions(p)
            when (p.name) {
                "MatchValue" -> matchValue(p.req("value"))
                "MatchSingleton" -> {
                    val v = p["value"]
                    if (v !== PyNone && v !is PyBool) fail("MatchSingleton can only contain True, False and None")
                }
                "MatchSequence" -> patterns(p.list("patterns"), true)
                "MatchMapping" -> {
                    if (p.list("keys").size != p.list("patterns").size) fail("MatchMapping doesn't have the same number of keys as patterns")
                    p.str("rest")?.let { capture(it) }
                    for (k in p.list("keys")) {
                        k as A? ?: fail("None disallowed in expression list")
                        // (None, True and False keys: valid syntax, which the value check would refuse)
                        if (isConst(k) && (k["value"] === PyNone || k["value"] is PyBool)) continue
                        matchValue(k)
                    }
                    patterns(p.list("patterns"), false)
                }
                "MatchClass" -> {
                    if (p.list("kwd_attrs").size != p.list("kwd_patterns").size) {
                        fail("MatchClass doesn't have the same number of keyword attributes as patterns")
                    }
                    expr(p.req("cls"), "Load")
                    var cls = p.req("cls")
                    while (cls.name == "Attribute") cls = cls.req("value")
                    if (cls.name != "Name") fail("MatchClass cls field can only contain Name or Attribute nodes.")
                    noneIn(p.list("kwd_attrs"), "identifier")
                    for (n in p.list("kwd_attrs")) name(n as String)
                    patterns(p.list("patterns"), false)
                    patterns(p.list("kwd_patterns"), false)
                }
                "MatchStar" -> {
                    if (!starOk) fail("can't use MatchStar here")
                    p.str("name")?.let { capture(it) }
                }
                "MatchAs" -> {
                    p.str("name")?.let { capture(it) }
                    val sub = p.node("pattern")
                    if (sub != null) {
                        if (p.str("name") == null) fail("MatchAs must specify a target name if a pattern is given")
                        pattern(sub, false)
                    }
                }
                "MatchOr" -> {
                    if (p.list("patterns").size < 2) fail("MatchOr requires at least 2 patterns")
                    patterns(p.list("patterns"), false)
                }
            }
        }

        private fun typeParams(seq: List<Any?>) {
            noneIn(seq, "type_param")
            for (tp in seq) {
                tp as A
                positions(tp)
                name(tp.str("name"))
                if (tp.name == "TypeVar") tp.node("bound")?.let { expr(it, "Load") }
                tp.node("default_value")?.let { expr(it, "Load") }
            }
        }

        private fun withItems(s: A) {
            nonEmpty(s.list("items"), "items", s.name)
            for (i in s.list("items")) {
                i as A
                expr(i.req("context_expr"), "Load")
                i.node("optional_vars")?.let { expr(it, "Store") }
            }
            body(s.list("body"), s.name)
        }

        private fun tryStmt(s: A) {
            body(s.list("body"), s.name)
            val handlers = s.list("handlers")
            if (handlers.isEmpty() && s.list("finalbody").isEmpty()) fail("${s.name} has neither except handlers nor finalbody")
            if (handlers.isEmpty() && s.list("orelse").isNotEmpty()) fail("${s.name} has orelse but no except handlers")
            noneIn(handlers, "excepthandler")
            for (h in handlers) {
                h as A
                // (CPython checks a Try's handlers' positions, not a TryStar's)
                if (s.name == "Try") positions(h)
                h.node("type")?.let { expr(it, "Load") }
                body(h.list("body"), "ExceptHandler")
            }
            stmts(s.list("finalbody"))
            stmts(s.list("orelse"))
        }

        private fun stmt(s: A) {
            positions(s)
            when (s.name) {
                "FunctionDef", "AsyncFunctionDef" -> {
                    body(s.list("body"), s.name)
                    typeParams(s.list("type_params"))
                    arguments(s.req("args"))
                    exprs(s.list("decorator_list"), "Load", false)
                    s.node("returns")?.let { expr(it, "Load") }
                }
                "ClassDef" -> {
                    body(s.list("body"), "ClassDef")
                    typeParams(s.list("type_params"))
                    exprs(s.list("bases"), "Load", false)
                    keywords(s.list("keywords"))
                    exprs(s.list("decorator_list"), "Load", false)
                }
                "Return" -> s.node("value")?.let { expr(it, "Load") }
                "Delete" -> {
                    nonEmpty(s.list("targets"), "targets", "Delete")
                    exprs(s.list("targets"), "Del", false)
                }
                "Assign" -> {
                    nonEmpty(s.list("targets"), "targets", "Assign")
                    exprs(s.list("targets"), "Store", false)
                    expr(s.req("value"), "Load")
                }
                "AugAssign" -> {
                    expr(s.req("target"), "Store")
                    expr(s.req("value"), "Load")
                }
                "AnnAssign" -> {
                    if (s.req("target").name != "Name" && s.int("simple") != 0) throw PyRt.typeError("AnnAssign with simple non-Name target")
                    expr(s.req("target"), "Store")
                    s.node("value")?.let { expr(it, "Load") }
                    expr(s.req("annotation"), "Load")
                }
                "TypeAlias" -> {
                    if (s.req("name").name != "Name") throw PyRt.typeError("TypeAlias with non-Name name")
                    expr(s.req("name"), "Store")
                    typeParams(s.list("type_params"))
                    expr(s.req("value"), "Load")
                }
                "For", "AsyncFor" -> {
                    expr(s.req("target"), "Store")
                    expr(s.req("iter"), "Load")
                    body(s.list("body"), s.name)
                    stmts(s.list("orelse"))
                }
                "While", "If" -> {
                    expr(s.req("test"), "Load")
                    body(s.list("body"), s.name)
                    stmts(s.list("orelse"))
                }
                "With", "AsyncWith" -> withItems(s)
                "Match" -> {
                    expr(s.req("subject"), "Load")
                    nonEmpty(s.list("cases"), "cases", "Match")
                    for (c in s.list("cases")) {
                        c as A
                        pattern(c.req("pattern"), false)
                        c.node("guard")?.let { expr(it, "Load") }
                        body(c.list("body"), "match_case")
                    }
                }
                "Raise" -> {
                    val exc = s.node("exc")
                    if (exc != null) {
                        expr(exc, "Load")
                        s.node("cause")?.let { expr(it, "Load") }
                    } else if (s.node("cause") != null) fail("Raise with cause but no exception")
                }
                "Try", "TryStar" -> tryStmt(s)
                "Assert" -> {
                    expr(s.req("test"), "Load")
                    s.node("msg")?.let { expr(it, "Load") }
                }
                "Import" -> nonEmpty(s.list("names"), "names", "Import")
                "ImportFrom" -> {
                    if (s.int("level") < 0) fail("Negative ImportFrom level")
                    nonEmpty(s.list("names"), "names", "ImportFrom")
                }
                "Global", "Nonlocal" -> {
                    nonEmpty(s.list("names"), "names", s.name)
                    noneIn(s.list("names"), "identifier")
                }
                "Expr" -> expr(s.req("value"), "Load")
            }
        }
    }

    // ---------------------------------------------------------------------- mod2obj

    /** PyAST_mod2obj: a new tree of `_ast` objects (operators and contexts _ast's single instances). */
    private class Writer(val r: Reader) {
        val singletons = PyClasses.attrOrNull(r.ast, "_singletons") as? PyDict

        fun node(a: A): PyObject {
            val o = PyObject(r.cls(a.name) as PyType).also { it.dict = PyDict() }
            val d = o.dict!!
            for ((i, f) in a.c.fields.withIndex()) d[PyStr(f.name)] = value(f, a.v[i])
            if (a.c.type.attributes.isNotEmpty()) {
                d[PyStr("lineno")] = a.line.toLong()
                d[PyStr("col_offset")] = a.col.toLong()
                d[PyStr("end_lineno")] = a.endLine.toLong()
                d[PyStr("end_col_offset")] = a.endCol.toLong()
            }
            return o
        }

        private fun value(f: PyAsdl.Field, v: Any?): Any? = when {
            f.seq -> PyList(ArrayList<Any?>().also { l -> for (x in v as List<*>) l.add(item(f, x)) })
            else -> item(f, v)
        }

        private fun item(f: PyAsdl.Field, v: Any?): Any? = when {
            v == null -> PyNone
            v is A -> node(v)
            f.type == "identifier" -> PyStr(v as String)
            f.type == "int" -> (v as Int).toLong()
            v is String -> singletons?.get(PyStr(v)) ?: PyObject(r.cls(v) as PyType).also { it.dict = PyDict() }
            else -> v
        }
    }

    // ---------------------------------------------------------------------- NeonPy's tree

    /** The validated tree as NeonPy's [Node]s: a field's value as the parser would give it, positions the nodes'. */
    private object Builder {
        private fun <T : Node> T.at(a: A): T = also {
            it.line = a.line
            it.col = a.col
            it.endLine = a.endLine
            it.endCol = a.endCol
        }

        /** (a product without positions: its first child's, as the parser gives it) */
        private fun <T : Node> T.like(n: Node): T = also {
            it.line = n.line
            it.col = n.col
            it.endLine = n.endLine
            it.endCol = n.endCol
        }

        fun mod(a: A): Node = when (a.name) {
            "Module" -> Module(stmts(a.list("body"))).also { it.line = 1 }
            "Interactive" -> Interactive(stmts(a.list("body"))).also { it.line = 1 }
            else -> Expression(expr(a.req("body"))).also { it.line = 1 }
        }

        private fun stmts(l: List<Any?>): List<Stmt> = l.map { stmt(it as A) }
        private fun exprs(l: List<Any?>): List<Expr> = l.map { expr(it as A) }
        private fun opt(a: A?): Expr? = a?.let { expr(it) }
        @Suppress("UNCHECKED_CAST")
        private fun names(l: List<Any?>): List<String> = l as List<String>

        private fun arguments(a: A) = Arguments(a.list("posonlyargs").map { arg(it as A) }, a.list("args").map { arg(it as A) },
            a.node("vararg")?.let { arg(it) }, a.list("kwonlyargs").map { arg(it as A) }, a.list("kw_defaults").map { opt(it as A?) },
            a.node("kwarg")?.let { arg(it) }, exprs(a.list("defaults")))

        private fun arg(a: A) = Arg(a.str("arg")!!, opt(a.node("annotation"))).at(a)
        private fun keyword(a: A) = Keyword(a.str("arg"), expr(a.req("value"))).at(a)
        private fun alias(a: A) = Alias(a.str("name")!!, a.str("asname")).at(a)

        private fun typeParam(a: A): TypeParam = when (a.name) {
            "TypeVar" -> TypeVar(a.str("name")!!, opt(a.node("bound")), opt(a.node("default_value")))
            "ParamSpec" -> ParamSpec(a.str("name")!!, opt(a.node("default_value")))
            else -> TypeVarTuple(a.str("name")!!, opt(a.node("default_value")))
        }.at(a)

        private fun typeParams(l: List<Any?>) = l.map { typeParam(it as A) }

        private fun handler(a: A) = ExceptHandler(opt(a.node("type")), a.str("name"), stmts(a.list("body"))).at(a)

        private fun withItem(a: A): WithItem {
            val e = expr(a.req("context_expr"))
            return WithItem(e, opt(a.node("optional_vars"))).like(e)
        }

        private fun matchCase(a: A): MatchCase {
            val p = pattern(a.req("pattern"))
            return MatchCase(p, opt(a.node("guard")), stmts(a.list("body"))).like(p)
        }

        private fun comprehension(a: A): Comprehension {
            val target = expr(a.req("target"))
            return Comprehension(target, expr(a.req("iter")), exprs(a.list("ifs")), if (a.int("is_async") != 0) 1 else 0).like(target)
        }

        private fun comprehensions(l: List<Any?>) = l.map { comprehension(it as A) }

        private fun ctx(a: A) = Ctx.valueOf(a["ctx"] as String)
        private fun op(a: A) = OpKind.valueOf(a["op"] as String)

        private fun stmt(a: A): Stmt = when (val n = a.name) {
            "FunctionDef", "AsyncFunctionDef" -> FunctionDef(a.str("name")!!, arguments(a.req("args")), stmts(a.list("body")),
                exprs(a.list("decorator_list")), opt(a.node("returns")), typeParams(a.list("type_params")), n == "AsyncFunctionDef")
            "ClassDef" -> ClassDef(a.str("name")!!, exprs(a.list("bases")), a.list("keywords").map { keyword(it as A) },
                stmts(a.list("body")), exprs(a.list("decorator_list")), typeParams(a.list("type_params")))
            "Return" -> Return(opt(a.node("value")))
            "Delete" -> Delete(exprs(a.list("targets")))
            "Assign" -> Assign(exprs(a.list("targets")), expr(a.req("value")))
            "TypeAlias" -> TypeAlias(expr(a.req("name")) as Name, typeParams(a.list("type_params")), expr(a.req("value")))
            "AugAssign" -> AugAssign(expr(a.req("target")), op(a), expr(a.req("value")))
            "AnnAssign" -> AnnAssign(expr(a.req("target")), expr(a.req("annotation")), opt(a.node("value")), if (a.int("simple") != 0) 1 else 0)
            "For", "AsyncFor" -> For(expr(a.req("target")), expr(a.req("iter")), stmts(a.list("body")), stmts(a.list("orelse")), n == "AsyncFor")
            "While" -> While(expr(a.req("test")), stmts(a.list("body")), stmts(a.list("orelse")))
            "If" -> If(expr(a.req("test")), stmts(a.list("body")), stmts(a.list("orelse")))
            "With", "AsyncWith" -> With(a.list("items").map { withItem(it as A) }, stmts(a.list("body")), n == "AsyncWith")
            "Match" -> Match(expr(a.req("subject")), a.list("cases").map { matchCase(it as A) })
            "Raise" -> Raise(opt(a.node("exc")), opt(a.node("cause")))
            "Try", "TryStar" -> Try(stmts(a.list("body")), a.list("handlers").map { handler(it as A) }, stmts(a.list("orelse")),
                stmts(a.list("finalbody")), n == "TryStar")
            "Assert" -> Assert(expr(a.req("test")), opt(a.node("msg")))
            "Import" -> Import(a.list("names").map { alias(it as A) })
            "ImportFrom" -> ImportFrom(a.str("module"), a.list("names").map { alias(it as A) }, a.int("level"))
            "Global" -> Global(names(a.list("names")))
            "Nonlocal" -> Nonlocal(names(a.list("names")))
            "Expr" -> ExprStmt(expr(a.req("value")))
            "Pass" -> Pass()
            "Break" -> Break()
            else -> Continue()
        }.at(a)

        private fun expr(a: A): Expr = when (a.name) {
            "BoolOp" -> BoolOp(BoolOpKind.valueOf(a["op"] as String), exprs(a.list("values")))
            "NamedExpr" -> NamedExpr(expr(a.req("target")) as Name, expr(a.req("value")))
            "BinOp" -> BinOp(expr(a.req("left")), op(a), expr(a.req("right")))
            "UnaryOp" -> UnaryOp(UnaryOpKind.valueOf(a["op"] as String), expr(a.req("operand")))
            "Lambda" -> Lambda(arguments(a.req("args")), expr(a.req("body")))
            "IfExp" -> IfExp(expr(a.req("test")), expr(a.req("body")), expr(a.req("orelse")))
            "Dict" -> DictExpr(a.list("keys").map { opt(it as A?) }, exprs(a.list("values")))
            "Set" -> SetExpr(exprs(a.list("elts")))
            "ListComp" -> ListComp(expr(a.req("elt")), comprehensions(a.list("generators")))
            "SetComp" -> SetComp(expr(a.req("elt")), comprehensions(a.list("generators")))
            "DictComp" -> DictComp(expr(a.req("key")), expr(a.req("value")), comprehensions(a.list("generators")))
            "GeneratorExp" -> GeneratorExp(expr(a.req("elt")), comprehensions(a.list("generators")))
            "Await" -> Await(expr(a.req("value")))
            "Yield" -> Yield(opt(a.node("value")))
            "YieldFrom" -> YieldFrom(expr(a.req("value")))
            "Compare" -> Compare(expr(a.req("left")), a.list("ops").map { CmpOpKind.valueOf(it as String) }, exprs(a.list("comparators")))
            "Call" -> Call(expr(a.req("func")), exprs(a.list("args")), a.list("keywords").map { keyword(it as A) })
            "FormattedValue" -> FormattedValue(expr(a.req("value")), a.int("conversion"), opt(a.node("format_spec")))
            "Interpolation" -> Interpolation(expr(a.req("value")), (a["str"] as? PyStr)?.s ?: PyRt.str(a["str"]), a.int("conversion"),
                opt(a.node("format_spec")))
            "JoinedStr" -> JoinedStr(exprs(a.list("values")))
            "TemplateStr" -> TemplateStr(exprs(a.list("values")))
            "Constant" -> Constant(a["value"], (a["kind"] as? PyStr)?.s)
            "Attribute" -> Attribute(expr(a.req("value")), a.str("attr")!!, ctx(a))
            "Subscript" -> Subscript(expr(a.req("value")), expr(a.req("slice")), ctx(a))
            "Starred" -> Starred(expr(a.req("value")), ctx(a))
            "Name" -> Name(a.str("id")!!, ctx(a))
            "List" -> ListExpr(exprs(a.list("elts")), ctx(a))
            "Tuple" -> TupleExpr(exprs(a.list("elts")), ctx(a))
            else -> Slice(opt(a.node("lower")), opt(a.node("upper")), opt(a.node("step")))
        }.at(a)

        private fun pattern(a: A): Pattern = when (a.name) {
            "MatchValue" -> MatchValue(expr(a.req("value")))
            "MatchSingleton" -> MatchSingleton(a["value"])
            "MatchSequence" -> MatchSequence(a.list("patterns").map { pattern(it as A) })
            "MatchMapping" -> MatchMapping(exprs(a.list("keys")), a.list("patterns").map { pattern(it as A) }, a.str("rest"))
            "MatchClass" -> MatchClass(expr(a.req("cls")), a.list("patterns").map { pattern(it as A) }, names(a.list("kwd_attrs")),
                a.list("kwd_patterns").map { pattern(it as A) })
            "MatchStar" -> MatchStar(a.str("name"))
            "MatchAs" -> MatchAs(a.node("pattern")?.let { pattern(it) }, a.str("name"))
            else -> MatchOr(a.list("patterns").map { pattern(it as A) })
        }.at(a)
    }
}
