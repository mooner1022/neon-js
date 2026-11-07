package dev.mooner.neonpython

/**
 * A scope: a module, a function (a lambda), a class body, a comprehension, or an annotation scope (the code of an
 * `__annotate__`: PEP 649, function-like). What each name is there ([kind]):
 * local (a register, or the namespace's dict in a module or class), a cell (local, and read by a nested function),
 * free (a nested function's view of an enclosing function's cell), global (declared, or not bound in any enclosing
 * function). As CPython's symtable.c decides it.
 */
class Scope(@JvmField val type: Type, @JvmField val name: String, @JvmField val parent: Scope?, @JvmField val node: Node?) {
    /**
     * TYPE_PARAMS a generic's `<generic parameters of X>` (its type parameters its locals), TYPE_VARIABLE a type
     * parameter's bound, constraints or default, TYPE_ALIAS a `type` alias's value: function-like, as annotation
     * scopes.
     */
    enum class Type { MODULE, FUNCTION, CLASS, COMPREHENSION, ANNOTATION, TYPE_PARAMS, TYPE_VARIABLE, TYPE_ALIAS }
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
    /** An annotation scope directly in a class: it reads the class's names through the `__classdict__` cell first. */
    @JvmField var canSeeClass = false
    /** A class whose annotation scopes read its namespace: it makes the `__classdict__` cell. */
    @JvmField var classDict = false
    /** A class with a def in its body: CPython 3.14 gives each one an annotation scope that sees the class, so the cell is made. */
    @JvmField var hasMethods = false
    /**
     * Comprehension iterables being visited, a scope made in one counting them too (CPython's ste_comp_iter_expr,
     * bpo-37757): no assignment expression there.
     */
    @JvmField var compIterExpr = 0
    /**
     * A module's or class's annotations recorded as they run (`__conditional_annotations__`): a module's all, a
     * class's those in a compound statement; [conditionCell] a class's set is a cell its annotation scope reads.
     */
    @JvmField var conditionalAnnotations = false
    @JvmField var conditionCell = false
    /** A module's or class's annotation scope, shared by its annotated assignments. */
    @JvmField var annotationScope: Scope? = null
    /** Whether an annotated assignment is in it (any target), or (an annotation scope's) any annotation. */
    @JvmField var annotationsUsed = false
    /** A module's or class's annotated simple names: what its `__annotate__` gives. */
    @JvmField var deferredAnnotations = false
    /** The compound statements the walk is in, here (a class's annotations there are conditional). */
    @JvmField var conditionalDepth = 0
    /** A TYPE_VARIABLE scope's errors' words: "a TypeVar bound"... */
    @JvmField var scopeInfo: String? = null
    /** A TYPE_PARAMS scope's type parameters. */
    @JvmField val typeParamNames = HashSet<String>()
    /**
     * The class whose private names (`__x`) it mangles (`_C__x`): its own, or the one around it; [mangledOnly] a
     * class's type parameters' scope's: only those names.
     */
    @JvmField var private: String? = null
    @JvmField var mangledOnly: Set<String>? = null

    /** [n] as this scope's code names it (CPython's _Py_MaybeMangle). */
    fun mangle(n: String): String = if (mangledOnly != null && n !in mangledOnly!!) n else PySymtable.mangle(private, n)

    fun kind(n: String): Kind = kinds[n] ?: if (type == Type.MODULE || type == Type.CLASS) Kind.GLOBAL_IMPLICIT else Kind.GLOBAL_IMPLICIT

    val isFunction: Boolean get() = type != Type.MODULE && type != Type.CLASS

    fun add(n: String, flag: Int) {
        val m = mangle(n)
        flags[m] = (flags[m] ?: 0) or flag
    }

    /** [n]'s flags here, [n] mangled. */
    fun flagsOf(n: String): Int = flags[mangle(n)] ?: 0

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
    /** The annotation scope of each function with annotations. */
    val functionAnnotations = HashMap<Node, Scope>()
    /** A generic def's or alias's `<generic parameters of X>`; its type parameters' bound (or constraints) and default scopes; an alias's value's. */
    val typeParamScopes = HashMap<Node, Scope>()
    val boundScopes = HashMap<Node, Scope>()
    val defaultScopes = HashMap<Node, Scope>()
    val aliasScopes = HashMap<Node, Scope>()
    /** `from __future__ import annotations`: annotations are strings (PEP 563), evaluated nowhere. */
    var futureAnnotations = false
        private set
    private lateinit var cur: Scope
    /** The module's scope, where a `global` anywhere makes its name global too (CPython's st_global). */
    private lateinit var top: Scope

    companion object {
        @JvmStatic
        fun build(m: Module, futureAnnotations: Boolean = false): PySymtable {
            val t = PySymtable()
            t.futureAnnotations = futureAnnotations
            val top = Scope(Scope.Type.MODULE, "<module>", null, m)
            t.scopes[m] = top
            t.cur = top
            t.top = top
            t.stmts(m.body)
            t.analyze(top, emptySet())
            globalQualnames(top)
            return t
        }

        private fun err(msg: String, n: Node): Nothing = throw PySyntaxError(msg, n.line, n.col)

        /**
         * CPython's compiler_set_qualname's part the analysis decides: a def or class whose name the scope around it
         * declares global is named from the top (`G`, not `f.<locals>.G`), and so are the scopes in it and its
         * annotations' (`G.m`, `g.__annotate__`).
         */
        private fun globalQualnames(s: Scope) {
            for (c in s.children.toList()) {
                if ((c.type == Scope.Type.FUNCTION && c.name != "<lambda>") || c.type == Scope.Type.CLASS) {
                    var p = c.parent
                    while (p != null && (p.type == Scope.Type.TYPE_PARAMS || p.type == Scope.Type.ANNOTATION)) p = p.parent
                    var k = p
                    while (k != null && k.type != Scope.Type.CLASS) k = k.parent
                    if (p != null && p.type != Scope.Type.MODULE && c.qualname != c.name &&
                        p.kinds[mangle(k?.name, c.name)] == Scope.Kind.GLOBAL_EXPLICIT) {
                        val old = c.qualname
                        for (a in c.parent!!.children) if (a.type == Scope.Type.ANNOTATION && a.node === c.node) renamed(a, old, c.name)
                        renamed(c, old, c.name)
                    }
                }
                globalQualnames(c)
            }
        }

        private fun renamed(s: Scope, old: String, new: String) {
            if (s.qualname == old) s.qualname = new else if (s.qualname.startsWith("$old.")) s.qualname = new + s.qualname.substring(old.length)
            for (c in s.children) renamed(c, old, new)
        }

        /**
         * A private name in class [private] (_Py_Mangle): `__x` as `_C__x`, the class's leading underscores dropped;
         * not a dunder, a dotted name, or in a class named only underscores.
         */
        @JvmStatic
        fun mangle(private: String?, n: String): String {
            if (private == null || !n.startsWith("__") || n.endsWith("__") || '.' in n) return n
            val p = private.trimStart('_')
            return if (p.isEmpty()) n else "_$p$n"
        }
    }

    /** A scope named [name] in [cur]: its node's in [scopes] where [register], one of [cur]'s children where [attach]. */
    private fun push(type: Scope.Type, name: String, node: Node, attach: Boolean = true, register: Boolean = true): Scope {
        val s = Scope(type, name, cur, node)
        s.qualname = qualnameOf(name)
        // (a class's names are private to it, and to what is in it)
        s.private = if (type == Scope.Type.CLASS) name else cur.private
        if (type == Scope.Type.TYPE_VARIABLE) s.mangledOnly = cur.mangledOnly
        s.compIterExpr = cur.compIterExpr
        if (attach) cur.children.add(s)
        if (register) scopes[node] = s
        cur = s
        return s
    }

    /** [name]'s qualified name in [cur] (a generic's type parameters' scope no part of it: `f`, `C.m`). */
    private fun qualnameOf(name: String): String {
        var c = cur
        while (c.type == Scope.Type.TYPE_PARAMS) c = c.parent!!
        return when (c.type) {
            Scope.Type.MODULE -> name
            Scope.Type.CLASS -> c.qualname + "." + name
            else -> c.qualname + ".<locals>." + name
        }
    }

    /**
     * A generic's `<generic parameters of [name]>` scope, entered: its type parameters its locals (a duplicate
     * CPython's error), each bound, constraints and default a lazily evaluated scope of its own; a def's defaults
     * passed to it (`.defaults`, `.kwdefaults`).
     */
    private fun pushTypeParams(node: Node, name: String, tps: List<TypeParam>, defaults: Boolean, kwDefaults: Boolean) {
        val inClass = cur.type == Scope.Type.CLASS
        val s = push(Scope.Type.TYPE_PARAMS, "<generic parameters of $name>", node, register = false)
        typeParamScopes[node] = s
        // (a generic class's type parameters are its private names; its bases' names are not)
        if (node is ClassDef) {
            s.private = name
            s.mangledOnly = tps.map { it.name }.toSet()
        }
        if (inClass) {
            s.canSeeClass = true
            s.add("__classdict__", Scope.USE)
        }
        for (p in listOfNotNull(".defaults".takeIf { defaults }, ".kwdefaults".takeIf { kwDefaults })) {
            s.params.add(p)
            s.add(p, Scope.DEF_PARAM)
        }
        for (tp in tps) {
            if (tp.name == "__classdict__") err("reserved name '__classdict__' cannot be used for type parameter", tp)
            if (!s.typeParamNames.add(s.mangle(tp.name))) err("duplicate type parameter '${tp.name}'", tp)
            s.add(tp.name, Scope.DEF_LOCAL)
            when (tp) {
                is TypeVar -> {
                    tp.bound?.let { lazyScope(tp, it, if (it is TupleExpr) "a TypeVar constraint" else "a TypeVar bound", boundScopes) }
                    tp.defaultValue?.let { lazyScope(tp, it, "a TypeVar default", defaultScopes) }
                }
                is TypeVarTuple -> tp.defaultValue?.let { lazyScope(tp, it, "a TypeVarTuple default", defaultScopes) }
                is ParamSpec -> tp.defaultValue?.let { lazyScope(tp, it, "a ParamSpec default", defaultScopes) }
            }
        }
    }

    /** A lazily evaluated expression's scope (a bound, a default, an alias's value): `(format=1, /)`, as an annotation's. */
    private fun lazyScope(node: Node, e: Expr, info: String?, into: HashMap<Node, Scope>, type: Scope.Type = Scope.Type.TYPE_VARIABLE,
                          inClass: Boolean = cur.canSeeClass) {
        val name = (node as? TypeParam)?.name ?: (node as TypeAlias).name.id
        val s = push(type, name, node, register = false)
        s.scopeInfo = info
        s.params.add(".format")
        s.add(".format", Scope.DEF_PARAM)
        if (inClass) {
            s.canSeeClass = true
            s.add("__classdict__", Scope.USE)
        }
        expr(e)
        pop()
        into[node] = s
    }

    /** An annotation scope in [cur]: its parameter (`.format`, CPython's, so that a name `format` stays the builtin's). */
    private fun pushAnnotations(node: Node, qualname: String, attach: Boolean = true): Scope {
        val inClass = cur.type == Scope.Type.CLASS || cur.canSeeClass
        val a = push(Scope.Type.ANNOTATION, "__annotate__", node, attach, register = false)
        a.qualname = qualname
        a.params.add(".format")
        a.add(".format", Scope.DEF_PARAM)
        if (inClass && !futureAnnotations) {
            a.canSeeClass = true
            a.add("__classdict__", Scope.USE)
        }
        return a
    }

    /**
     * A function's annotations, in a scope of their own around which the function is made ([functionAnnotations]):
     * its parameters are not visible there; directly in a class, the class's names are.
     */
    private fun functionAnnotations(s: FunctionDef) {
        val a = s.args
        if (cur.type == Scope.Type.CLASS && !futureAnnotations) cur.hasMethods = true
        if (a.all().none { it.annotation != null } && s.returns == null) return
        val ann = pushAnnotations(s, qualnameOf(s.name) + ".__annotate__")
        ann.annotationsUsed = true
        // (CPython's order: positional-only, positional, *args, **kwargs, keyword-only, the return)
        for (p in a.posonlyargs + a.args + listOfNotNull(a.vararg, a.kwarg) + a.kwonlyargs) p.annotation?.let { expr(it) }
        s.returns?.let { expr(it) }
        pop()
        functionAnnotations[s] = ann
    }

    /**
     * An annotated assignment's annotation: a module's or class's in their shared annotation scope (made at the
     * first); a function's local one is never evaluated, walked for its errors only, in a scope nothing sees.
     */
    private fun annotation(a: Expr, s: AnnAssign) {
        val holder = cur
        if (holder.isFunction) {
            push(Scope.Type.ANNOTATION, "__annotate__", s, attach = false, register = false)
            expr(a)
            pop()
            return
        }
        val conditional = holder.type == Scope.Type.MODULE || holder.conditionalDepth > 0
        var ann = holder.annotationScope
        if (ann == null) {
            ann = pushAnnotations(s, if (holder.type == Scope.Type.MODULE) "__annotate__" else holder.qualname + ".__annotate__")
            holder.annotationScope = ann
        } else cur = ann
        if (s.simple == 1 && s.target is Name) holder.deferredAnnotations = true
        if (conditional && !futureAnnotations) {
            holder.conditionalAnnotations = true
            ann.add("__conditional_annotations__", Scope.USE)
        } else if (conditional && holder.type == Scope.Type.MODULE) holder.conditionalAnnotations = true
        expr(a)
        pop()
    }

    /** The error of [what] (a yield, an await, a walrus) in an annotation scope, a type parameter's or an alias's. */
    private fun notInAnnotation(what: String, e: Expr) {
        when (cur.type) {
            Scope.Type.ANNOTATION -> err("$what cannot be used within an annotation", e)
            Scope.Type.TYPE_VARIABLE -> err("$what cannot be used within ${cur.scopeInfo}", e)
            Scope.Type.TYPE_ALIAS -> err("$what cannot be used within a type alias", e)
            Scope.Type.TYPE_PARAMS -> err("$what cannot be used within the definition of a generic", e)
            else -> {}
        }
    }

    private fun pop() {
        cur = cur.parent!!
    }

    private fun bind(n: String, node: Node) {
        val f = cur.flagsOf(n)
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
        // (a compound statement's body may not run: a class's annotations there are recorded as they run)
        val compound = s is For || s is While || s is If || s is With || s is Try || s is Match
        if (compound) cur.conditionalDepth++
        try {
            stmtBody(s)
        } finally {
            if (compound) cur.conditionalDepth--
        }
    }

    private fun stmtBody(s: Stmt) {
        when (s) {
            is FunctionDef -> {
                s.decoratorList.forEach { expr(it) }
                defaults(s.args)
                bind(s.name, s)
                // (a generic def: its function made in its type parameters' scope)
                val generic = s.typeParams.isNotEmpty()
                if (generic) pushTypeParams(s, s.name, s.typeParams, s.args.defaults.isNotEmpty(), s.args.kwDefaults.any { it != null })
                functionAnnotations(s)
                val f = push(Scope.Type.FUNCTION, s.name, s)
                f.isCoroutine = s.isAsync
                params(s.args, s)
                stmts(s.body)
                pop()
                if (generic) pop()
            }
            is ClassDef -> {
                s.decoratorList.forEach { expr(it) }
                bind(s.name, s)
                // (a generic class: its bases evaluated in its type parameters' scope, with Generic[...] after them)
                val generic = s.typeParams.isNotEmpty()
                if (generic) {
                    pushTypeParams(s, s.name, s.typeParams, false, false)
                    cur.add(".type_params", Scope.DEF_LOCAL or Scope.USE)
                    cur.add(".generic_base", Scope.DEF_LOCAL or Scope.USE)
                }
                s.bases.forEach { expr(it) }
                s.keywords.forEach { expr(it.value) }
                val c = push(Scope.Type.CLASS, s.name, s)
                if (generic) c.add(".type_params", Scope.USE)
                stmts(s.body)
                pop()
                if (generic) pop()
            }
            is Return -> s.value?.let { expr(it) }
            is Delete -> s.targets.forEach { target(it) }
            is Assign -> {
                expr(s.value)
                s.targets.forEach { target(it) }
            }
            is TypeAlias -> {
                bind(s.name.id, s)
                val inClass = cur.type == Scope.Type.CLASS
                val generic = s.typeParams.isNotEmpty()
                if (generic) pushTypeParams(s, s.name.id, s.typeParams, false, false)
                lazyScope(s, s.value, null, aliasScopes, Scope.Type.TYPE_ALIAS, inClass)
                if (generic) pop()
            }
            is AugAssign -> {
                if (s.target is Name) use((s.target as Name).id)
                expr(s.value)
                target(s.target)
            }
            is AnnAssign -> {
                cur.annotationsUsed = true
                val t = s.target
                if (t is Name) {
                    val f = cur.flagsOf(t.id)
                    if (f and (Scope.DEF_GLOBAL or Scope.DEF_NONLOCAL) != 0 && cur.type != Scope.Type.MODULE && s.simple == 1) {
                        err("annotated name '${t.id}' can't be ${if (f and Scope.DEF_GLOBAL != 0) "global" else "nonlocal"}", s)
                    }
                    // ((x): int alone binds nothing)
                    if (s.simple == 1 || s.value != null) bind(t.id, t)
                } else target(t)
                annotation(s.annotation, s)
                s.value?.let { expr(it) }
            }
            is For -> {
                if (s.isAsync && !cur.isCoroutine) err("'async for' outside async function", s)
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
                if (s.isAsync && !cur.isCoroutine) err("'async with' outside async function", s)
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
                val f = cur.flagsOf(n)
                if (cur.type == Scope.Type.MODULE) { cur.add(n, Scope.DEF_GLOBAL); continue }
                if (f and Scope.DEF_PARAM != 0) err("name '$n' is parameter and global", s)
                if (f and Scope.DEF_NONLOCAL != 0) err("name '$n' is nonlocal and global", s)
                if (f and Scope.USE != 0) err("name '$n' is used prior to global declaration", s)
                if (f and Scope.DEF_LOCAL != 0) err("name '$n' is assigned to before global declaration", s)
                cur.add(n, Scope.DEF_GLOBAL)
                // (the module's name then: exec's code stores it in the globals, not the locals it is given)
                top.add(n, Scope.DEF_GLOBAL)
            }
            is Nonlocal -> for (n in s.names) {
                val f = cur.flagsOf(n)
                if (cur.type == Scope.Type.MODULE) err("nonlocal declaration not allowed at module level", s)
                if (f and Scope.DEF_PARAM != 0) err("name '$n' is parameter and nonlocal", s)
                if (f and Scope.DEF_GLOBAL != 0) err("name '$n' is nonlocal and global", s)
                if (f and Scope.USE != 0) err("name '$n' is used prior to nonlocal declaration", s)
                if (f and Scope.DEF_LOCAL != 0) err("name '$n' is assigned to before nonlocal declaration", s)
                cur.add(n, Scope.DEF_NONLOCAL)
                cur.nonlocalAt[cur.mangle(n)] = s
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
            cur.params.add(cur.mangle(p.arg))
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
                notInAnnotation("named expression", e)
                if (cur.compIterExpr > 0) err("assignment expression cannot be used in a comprehension iterable expression", e)
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
                notInAnnotation("await expression", e)
                // (in a comprehension, it makes the comprehension's code a coroutine: an async def's it awaits)
                if (cur.type != Scope.Type.FUNCTION && cur.type != Scope.Type.COMPREHENSION) err("'await' outside function", e)
                if (cur.type == Scope.Type.FUNCTION && !cur.isCoroutine) err("'await' outside async function", e)
                expr(e.value)
                cur.isCoroutine = true
            }
            is Yield -> {
                notInAnnotation("yield expression", e)
                e.value?.let { expr(it) }
                markGenerator(e)
            }
            is YieldFrom -> {
                notInAnnotation("yield expression", e)
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
            Scope.Type.COMPREHENSION -> err("'yield' inside ${when (cur.name) {
                "<listcomp>" -> "list comprehension"; "<setcomp>" -> "set comprehension"; "<dictcomp>" -> "dict comprehension"
                else -> "generator expression" }}", e)
            else -> err("'yield' outside function", e)
        }
    }

    /** A walrus binds in the function around its comprehensions (PEP 572). */
    private fun namedTarget(e: NamedExpr) {
        val n = e.target.id
        if (cur.type == Scope.Type.COMPREHENSION) {
            var s: Scope = cur
            while (s.type == Scope.Type.COMPREHENSION) {
                if (s.flagsOf(n) and Scope.DEF_COMP_ITER != 0) {
                    err("assignment expression cannot rebind comprehension iteration variable '$n'", e)
                }
                s = s.parent!!
            }
            if (s.type == Scope.Type.CLASS) err("assignment expression within a comprehension cannot be used in a class body", e)
            val f = s.flagsOf(n)
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

    /**
     * A comprehension: its first iterable in the scope around it, the rest in a scope of its own. One with an async
     * for or an await is asynchronous: a generator expression's an async generator; another's a coroutine the scope
     * around awaits, an async def's or a comprehension's (which becomes asynchronous too).
     */
    private fun comprehension(e: Expr, name: String, gens: List<Comprehension>, elt: () -> Unit) {
        cur.compIterExpr++
        expr(gens[0].iter)
        cur.compIterExpr--
        val s = push(Scope.Type.COMPREHENSION, name, e)
        s.params.add(".0")
        s.add(".0", Scope.DEF_PARAM)
        for ((i, g) in gens.withIndex()) {
            if (g.isAsync == 1) s.isCoroutine = true
            if (i > 0) {
                s.compIterExpr++
                expr(g.iter)
                s.compIterExpr--
            }
            compTarget(g.target)
            g.ifs.forEach { expr(it) }
        }
        elt()
        pop()
        if (s.isCoroutine && name != "<genexpr>") {
            if (cur.type != Scope.Type.COMPREHENSION && !(cur.type == Scope.Type.FUNCTION && cur.isCoroutine)) {
                err("asynchronous comprehension outside of an asynchronous function", e)
            }
            cur.isCoroutine = true
        }
    }

    private fun compTarget(e: Expr) {
        when (e) {
            is Name -> {
                // (an assignment expression's target in the comprehension, bound around it)
                if (cur.flagsOf(e.id) and (Scope.DEF_GLOBAL or Scope.DEF_NONLOCAL) != 0) {
                    err("comprehension inner loop cannot rebind assignment expression target '${e.id}'", e)
                }
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
    /** Whether the binding of [n] a nonlocal in [s] reaches is a generic's type parameter. */
    private fun bindsTypeParam(s: Scope, n: String): Boolean {
        var p = s.parent
        while (p != null) {
            if (p.type != Scope.Type.CLASS) {
                val k = p.kinds[n]
                if (k == Scope.Kind.LOCAL || k == Scope.Kind.CELL) return p.type == Scope.Type.TYPE_PARAMS && n in p.typeParamNames
            }
            p = p.parent
        }
        return false
    }

    private fun analyze(s: Scope, bound: Set<String>) {
        for ((n, f) in s.flags) {
            s.kinds[n] = when {
                f and Scope.DEF_GLOBAL != 0 -> Scope.Kind.GLOBAL_EXPLICIT
                f and Scope.DEF_NONLOCAL != 0 -> {
                    if (n !in bound) err("no binding for nonlocal '$n' found", s.nonlocalAt[n] ?: s.node!!)
                    if (bindsTypeParam(s, n)) err("nonlocal binding not allowed for type parameter '$n'", s.nonlocalAt[n] ?: s.node!!)
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
            Scope.Type.CLASS -> bound + "__class__" + "__classdict__" + "__conditional_annotations__"
            // (a name a function declares global is global in what is nested in it too)
            else -> bound - s.kinds.filter { it.value == Scope.Kind.GLOBAL_EXPLICIT }.keys +
                s.kinds.filter { it.value == Scope.Kind.LOCAL || it.value == Scope.Kind.FREE || it.value == Scope.Kind.CELL }.keys
        }
        for (c in s.children) analyze(c, inner)
        if (s.hasMethods) s.classDict = true
        for (c in s.children) for (n in c.frees) {
            val k = s.kinds[n]
            when {
                s.type == Scope.Type.CLASS && n == "__class__" -> s.classCell = true
                s.type == Scope.Type.CLASS && n == "__classdict__" -> s.classDict = true
                s.type == Scope.Type.CLASS && n == "__conditional_annotations__" -> s.conditionCell = true
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
