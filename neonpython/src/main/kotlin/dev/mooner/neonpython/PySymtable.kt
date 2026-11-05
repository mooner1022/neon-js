package dev.mooner.neonpython

/**
 * A scope: a module, a function (a lambda), a class body, or a comprehension. What each name is there ([kind]):
 * local (a register, or the namespace's dict in a module or class), a cell (local, and read by a nested function),
 * free (a nested function's view of an enclosing function's cell), global (declared, or not bound in any enclosing
 * function). As CPython's symtable.c decides it.
 */
class Scope(@JvmField val type: Type, @JvmField val name: String, @JvmField val parent: Scope?, @JvmField val node: Node?) {
    enum class Type { MODULE, FUNCTION, CLASS, COMPREHENSION }
    enum class Kind { LOCAL, CELL, FREE, GLOBAL_EXPLICIT, GLOBAL_IMPLICIT }

    /** Names in the order the scope first saw them, with how it uses them ([DEF_LOCAL]...). */
    @JvmField val flags = LinkedHashMap<String, Int>()
    @JvmField val children = ArrayList<Scope>()
    /** The parameters, in a code block's order: positional, keyword-only, *args, **kwargs. */
    @JvmField val params = ArrayList<String>()
    @JvmField val kinds = HashMap<String, Kind>()
    /** Its cells and free variables, in order. */
    @JvmField val cells = LinkedHashSet<String>()
    @JvmField val frees = LinkedHashSet<String>()
    /** Where each name was declared nonlocal (its error's line). */
    @JvmField val nonlocalAt = HashMap<String, Node>()
    /** A class whose methods use `__class__` (super()): it makes that cell. */
    @JvmField var classCell = false
    /** Its qualified name (`f.<locals>.g`, `C.m`). */
    @JvmField var qualname = name
    @JvmField var isGenerator = false
    @JvmField var isCoroutine = false

    fun kind(n: String): Kind = kinds[n] ?: if (type == Type.MODULE || type == Type.CLASS) Kind.GLOBAL_IMPLICIT else Kind.GLOBAL_IMPLICIT

    val isFunction: Boolean get() = type == Type.FUNCTION || type == Type.COMPREHENSION

    fun add(n: String, flag: Int) {
        flags[n] = (flags[n] ?: 0) or flag
    }

    companion object {
        const val DEF_LOCAL = 1
        const val DEF_PARAM = 2
        const val DEF_GLOBAL = 4
        const val DEF_NONLOCAL = 8
        const val USE = 16
        /** A name a comprehension's walrus binds in its enclosing function. */
        const val DEF_COMP_ITER = 32
    }
}

/** Builds the scopes of a module ([build]): what each name is, in each function, class and comprehension. */
class PySymtable private constructor() {
    /** The scope of each function, lambda, class and comprehension node. */
    val scopes = HashMap<Node, Scope>()
    private lateinit var cur: Scope

    companion object {
        @JvmStatic
        fun build(m: Module): PySymtable {
            val t = PySymtable()
            val top = Scope(Scope.Type.MODULE, "<module>", null, m)
            t.scopes[m] = top
            t.cur = top
            t.stmts(m.body)
            t.analyze(top, emptySet())
            return t
        }

        private fun err(msg: String, n: Node): Nothing = throw PySyntaxError(msg, n.line, n.col)
    }

    private fun push(type: Scope.Type, name: String, node: Node): Scope {
        val s = Scope(type, name, cur, node)
        s.qualname = when (cur.type) {
            Scope.Type.MODULE -> name
            Scope.Type.CLASS -> cur.qualname + "." + name
            else -> cur.qualname + ".<locals>." + name
        }
        cur.children.add(s)
        scopes[node] = s
        cur = s
        return s
    }

    private fun pop() {
        cur = cur.parent!!
    }

    private fun bind(n: String, node: Node) {
        val f = cur.flags[n] ?: 0
        if (f and Scope.DEF_GLOBAL != 0 || f and Scope.DEF_NONLOCAL != 0) return
        cur.add(n, Scope.DEF_LOCAL)
    }

    private fun use(n: String) {
        cur.add(n, Scope.USE)
        // super() reads the __class__ cell of the class around its function
        if (n == "super" && cur.type == Scope.Type.FUNCTION) cur.add("__class__", Scope.USE)
    }

    // ------------------------------------------------------------------ the tree

    private fun stmts(ss: List<Stmt>) {
        for (s in ss) stmt(s)
    }

    private fun stmt(s: Stmt) {
        when (s) {
            is FunctionDef -> {
                s.decoratorList.forEach { expr(it) }
                defaults(s.args)
                bind(s.name, s)
                val f = push(Scope.Type.FUNCTION, s.name, s)
                f.isCoroutine = s.isAsync
                params(s.args, s)
                stmts(s.body)
                pop()
            }
            is ClassDef -> {
                s.decoratorList.forEach { expr(it) }
                s.bases.forEach { expr(it) }
                s.keywords.forEach { expr(it.value) }
                bind(s.name, s)
                push(Scope.Type.CLASS, s.name, s)
                stmts(s.body)
                pop()
            }
            is Return -> s.value?.let { expr(it) }
            is Delete -> s.targets.forEach { target(it) }
            is Assign -> {
                expr(s.value)
                s.targets.forEach { target(it) }
            }
            is TypeAlias -> {
                bind(s.name.id, s)
                expr(s.value)
            }
            is AugAssign -> {
                if (s.target is Name) use((s.target as Name).id)
                expr(s.value)
                target(s.target)
            }
            is AnnAssign -> {
                s.value?.let { expr(it) }
                target(s.target)
            }
            is For -> {
                expr(s.iter)
                target(s.target)
                stmts(s.body)
                stmts(s.orelse)
            }
            is While -> {
                expr(s.test)
                stmts(s.body)
                stmts(s.orelse)
            }
            is If -> {
                expr(s.test)
                stmts(s.body)
                stmts(s.orelse)
            }
            is With -> {
                for (i in s.items) {
                    expr(i.contextExpr)
                    i.optionalVars?.let { target(it) }
                }
                stmts(s.body)
            }
            is Match -> {
                expr(s.subject)
                for (c in s.cases) {
                    pattern(c.pattern)
                    c.guard?.let { expr(it) }
                    stmts(c.body)
                }
            }
            is Raise -> {
                s.exc?.let { expr(it) }
                s.cause?.let { expr(it) }
            }
            is Try -> {
                stmts(s.body)
                for (h in s.handlers) {
                    h.type?.let { expr(it) }
                    h.name?.let { bind(it, h) }
                    stmts(h.body)
                }
                stmts(s.orelse)
                stmts(s.finalbody)
            }
            is Assert -> {
                expr(s.test)
                s.msg?.let { expr(it) }
            }
            is Import -> for (a in s.names) bind(a.asname ?: a.name.substringBefore('.'), a)
            is ImportFrom -> for (a in s.names) if (a.name != "*") bind(a.asname ?: a.name, a)
            is Global -> for (n in s.names) {
                val f = cur.flags[n] ?: 0
                if (cur.type == Scope.Type.MODULE) { cur.add(n, Scope.DEF_GLOBAL); continue }
                if (f and Scope.DEF_PARAM != 0) err("name '$n' is parameter and global", s)
                if (f and Scope.DEF_NONLOCAL != 0) err("name '$n' is nonlocal and global", s)
                if (f and Scope.USE != 0) err("name '$n' is used prior to global declaration", s)
                if (f and Scope.DEF_LOCAL != 0) err("name '$n' is assigned to before global declaration", s)
                cur.add(n, Scope.DEF_GLOBAL)
            }
            is Nonlocal -> for (n in s.names) {
                val f = cur.flags[n] ?: 0
                if (cur.type == Scope.Type.MODULE) err("nonlocal declaration not allowed at module level", s)
                if (f and Scope.DEF_PARAM != 0) err("name '$n' is parameter and nonlocal", s)
                if (f and Scope.DEF_GLOBAL != 0) err("name '$n' is nonlocal and global", s)
                if (f and Scope.USE != 0) err("name '$n' is used prior to nonlocal declaration", s)
                if (f and Scope.DEF_LOCAL != 0) err("name '$n' is assigned to before nonlocal declaration", s)
                cur.add(n, Scope.DEF_NONLOCAL)
                cur.nonlocalAt[n] = s
            }
            is ExprStmt -> expr(s.value)
            is Pass, is Break, is Continue -> {}
        }
    }

    private fun defaults(a: Arguments) {
        a.defaults.forEach { expr(it) }
        a.kwDefaults.forEach { it?.let { d -> expr(d) } }
    }

    private fun params(a: Arguments, node: Node) {
        val seen = HashSet<String>()
        // (a code block's order: positional, keyword-only, *args, **kwargs)
        for (p in a.posonlyargs + a.args + a.kwonlyargs + listOfNotNull(a.vararg, a.kwarg)) {
            if (!seen.add(p.arg)) err("duplicate argument '${p.arg}' in function definition", p)
            cur.add(p.arg, Scope.DEF_PARAM)
            cur.params.add(p.arg)
        }
    }

    private fun target(e: Expr) {
        when (e) {
            is Name -> if (e.ctx == Ctx.Load) use(e.id) else bind(e.id, e)
            is Attribute -> expr(e.value)
            is Subscript -> {
                expr(e.value)
                expr(e.slice)
            }
            is Starred -> target(e.value)
            is TupleExpr -> e.elts.forEach { target(it) }
            is ListExpr -> e.elts.forEach { target(it) }
            else -> expr(e)
        }
    }

    private fun pattern(p: Pattern) {
        when (p) {
            is MatchValue -> expr(p.value)
            is MatchSingleton -> {}
            is MatchSequence -> p.patterns.forEach { pattern(it) }
            is MatchMapping -> {
                p.keys.forEach { expr(it) }
                p.patterns.forEach { pattern(it) }
                p.rest?.let { bind(it, p) }
            }
            is MatchClass -> {
                expr(p.cls)
                p.patterns.forEach { pattern(it) }
                p.kwdPatterns.forEach { pattern(it) }
            }
            is MatchStar -> p.name?.let { bind(it, p) }
            is MatchAs -> {
                p.pattern?.let { pattern(it) }
                p.name?.let { bind(it, p) }
            }
            is MatchOr -> p.patterns.forEach { pattern(it) }
        }
    }

    private fun expr(e: Expr) {
        when (e) {
            is Name -> if (e.ctx == Ctx.Load) use(e.id) else bind(e.id, e)
            is BoolOp -> e.values.forEach { expr(it) }
            is NamedExpr -> {
                expr(e.value)
                namedTarget(e)
            }
            is BinOp -> { expr(e.left); expr(e.right) }
            is UnaryOp -> expr(e.operand)
            is Lambda -> {
                defaults(e.args)
                push(Scope.Type.FUNCTION, "<lambda>", e)
                params(e.args, e)
                expr(e.body)
                pop()
            }
            is IfExp -> { expr(e.test); expr(e.body); expr(e.orelse) }
            is DictExpr -> {
                e.keys.forEach { it?.let { k -> expr(k) } }
                e.values.forEach { expr(it) }
            }
            is SetExpr -> e.elts.forEach { expr(it) }
            is ListComp -> comprehension(e, "<listcomp>", e.generators) { expr(e.elt) }
            is SetComp -> comprehension(e, "<setcomp>", e.generators) { expr(e.elt) }
            is DictComp -> comprehension(e, "<dictcomp>", e.generators) { expr(e.key); expr(e.value) }
            is GeneratorExp -> comprehension(e, "<genexpr>", e.generators) { expr(e.elt) }
            is Await -> {
                expr(e.value)
                markCoroutine()
            }
            is Yield -> {
                e.value?.let { expr(it) }
                markGenerator(e)
            }
            is YieldFrom -> {
                expr(e.value)
                markGenerator(e)
            }
            is Compare -> {
                expr(e.left)
                e.comparators.forEach { expr(it) }
            }
            is Call -> {
                expr(e.func)
                e.args.forEach { expr(it) }
                e.keywords.forEach { expr(it.value) }
            }
            is FormattedValue -> {
                expr(e.value)
                e.formatSpec?.let { expr(it) }
            }
            is Interpolation -> {
                expr(e.value)
                e.formatSpec?.let { expr(it) }
            }
            is JoinedStr -> e.values.forEach { expr(it) }
            is TemplateStr -> e.values.forEach { expr(it) }
            is Constant -> {}
            is Attribute -> expr(e.value)
            is Subscript -> { expr(e.value); expr(e.slice) }
            is Starred -> expr(e.value)
            is ListExpr -> e.elts.forEach { if (e.ctx == Ctx.Load) expr(it) else target(it) }
            is TupleExpr -> e.elts.forEach { if (e.ctx == Ctx.Load) expr(it) else target(it) }
            is Slice -> {
                e.lower?.let { expr(it) }
                e.upper?.let { expr(it) }
                e.step?.let { expr(it) }
            }
        }
    }

    private fun markGenerator(e: Expr) {
        when (cur.type) {
            Scope.Type.FUNCTION -> cur.isGenerator = true
            Scope.Type.COMPREHENSION -> if (cur.name != "<genexpr>") err("'yield' inside ${cur.name.removePrefix("<").removeSuffix(">").let {
                when (it) { "listcomp" -> "list comprehension"; "setcomp" -> "set comprehension"; else -> "dict comprehension" } }}", e)
                else cur.isGenerator = true
            else -> err("'yield' outside function", e)
        }
    }

    private fun markCoroutine() {
        if (cur.type == Scope.Type.COMPREHENSION) cur.isCoroutine = true
    }

    /** A walrus binds in the function around its comprehensions (PEP 572). */
    private fun namedTarget(e: NamedExpr) {
        val n = e.target.id
        if (cur.type == Scope.Type.COMPREHENSION) {
            var s: Scope = cur
            while (s.type == Scope.Type.COMPREHENSION) {
                if ((s.flags[n] ?: 0) and Scope.DEF_COMP_ITER != 0) {
                    err("assignment expression cannot rebind comprehension iteration variable '$n'", e)
                }
                s = s.parent!!
            }
            if (s.type == Scope.Type.CLASS) err("assignment expression within a comprehension cannot be used in a class body", e)
            val f = s.flags[n] ?: 0
            if (f and Scope.DEF_GLOBAL == 0) s.add(n, Scope.DEF_LOCAL)
            // in the comprehension it is free (or global, where its function is the module)
            cur.add(n, if (s.type == Scope.Type.MODULE || f and Scope.DEF_GLOBAL != 0) Scope.DEF_GLOBAL else Scope.DEF_NONLOCAL)
            var c = cur.parent
            while (c != null && c !== s) {
                c.add(n, if (s.type == Scope.Type.MODULE || f and Scope.DEF_GLOBAL != 0) Scope.DEF_GLOBAL else Scope.DEF_NONLOCAL)
                c = c.parent
            }
            return
        }
        bind(n, e)
    }

    /** A comprehension: its first iterable in the scope around it, the rest in a scope of its own. */
    private fun comprehension(e: Expr, name: String, gens: List<Comprehension>, elt: () -> Unit) {
        expr(gens[0].iter)
        val s = push(Scope.Type.COMPREHENSION, name, e)
        s.params.add(".0")
        s.add(".0", Scope.DEF_PARAM)
        for ((i, g) in gens.withIndex()) {
            if (g.isAsync == 1) s.isCoroutine = true
            if (i > 0) expr(g.iter)
            compTarget(g.target)
            g.ifs.forEach { expr(it) }
        }
        elt()
        pop()
    }

    private fun compTarget(e: Expr) {
        when (e) {
            is Name -> {
                cur.add(e.id, Scope.DEF_LOCAL or Scope.DEF_COMP_ITER)
            }
            is Starred -> compTarget(e.value)
            is TupleExpr -> e.elts.forEach { compTarget(it) }
            is ListExpr -> e.elts.forEach { compTarget(it) }
            else -> target(e)
        }
    }

    // ------------------------------------------------------------------ analysis

    /**
     * What each name of [s] is, given the names [bound] in the functions around it (a class's names are not seen
     * by its methods); a function's free names become cells where they are bound, and free in the scopes between.
     */
    private fun analyze(s: Scope, bound: Set<String>) {
        for ((n, f) in s.flags) {
            s.kinds[n] = when {
                f and Scope.DEF_GLOBAL != 0 -> Scope.Kind.GLOBAL_EXPLICIT
                f and Scope.DEF_NONLOCAL != 0 -> {
                    if (n !in bound) err("no binding for nonlocal '$n' found", s.nonlocalAt[n] ?: s.node!!)
                    Scope.Kind.FREE
                }
                f and (Scope.DEF_LOCAL or Scope.DEF_PARAM) != 0 -> if (s.type == Scope.Type.MODULE) Scope.Kind.GLOBAL_IMPLICIT else Scope.Kind.LOCAL
                n in bound && s.type != Scope.Type.MODULE -> Scope.Kind.FREE
                else -> Scope.Kind.GLOBAL_IMPLICIT
            }
        }
        // a class binds __class__ for its methods (super()); its own names it does not give them
        val inner = when (s.type) {
            Scope.Type.MODULE -> emptySet()
            Scope.Type.CLASS -> bound + "__class__"
            else -> bound + s.kinds.filter { it.value == Scope.Kind.LOCAL || it.value == Scope.Kind.FREE || it.value == Scope.Kind.CELL }.keys
        }
        for (c in s.children) analyze(c, inner)
        for (c in s.children) for (n in c.frees) {
            val k = s.kinds[n]
            when {
                s.type == Scope.Type.CLASS && n == "__class__" -> s.classCell = true
                k == Scope.Kind.LOCAL && s.isFunction -> s.kinds[n] = Scope.Kind.CELL
                // (a class's own name stays its own, in its dict: the method's comes from further out)
                s.type == Scope.Type.CLASS -> s.frees.add(n)
                k == Scope.Kind.CELL || k == Scope.Kind.FREE -> {}
                // passed through this scope from one further out
                else -> s.kinds[n] = Scope.Kind.FREE
            }
        }
        for ((n, k) in s.kinds) {
            if (k == Scope.Kind.CELL) s.cells.add(n)
            if (k == Scope.Kind.FREE) s.frees.add(n)
        }
    }
}
