package dev.mooner.neonpython

import dev.mooner.neonjs.compiler.CodeUnit

/**
 * What a Python code block keeps besides its bytecode ([CodeUnit.dialectData]): its function's name, the names of its
 * locals by register, and the source line of each statement's pc (pairs in [CodeUnit.lineTable]).
 */
class PyCodeInfo(@JvmField val name: String, @JvmField val localNames: Array<String>, @JvmField val module: Boolean) {
    /** Its function's qualified name (`f.<locals>.g`), which call errors give. */
    @JvmField var qualname: String = name
    /** Positional parameters (positional-only ones among them, first), keyword-only ones, *args, **kwargs. */
    @JvmField var argcount = 0
    @JvmField var posonlyCount = 0
    @JvmField var kwonlyCount = 0
    @JvmField var varargs = false
    @JvmField var varkw = false
    /** The parameters' names, in their registers' order. */
    @JvmField var paramNames: List<String> = emptyList()
    /** Its docstring. */
    @JvmField var doc: String? = null

    /**
     * The operand types the interpreter saw at each arithmetic and comparison instruction, by pc ([PyProfile]): made
     * when the optimizing tier is on (VmConfig.tier2), read by its speculating graphs ([PyBuilder]). Written without
     * synchronization: a hint, the code checks what it speculates on.
     */
    @Volatile @JvmField var profile: ByteArray? = null

    fun profile(size: Int): ByteArray = profile ?: ByteArray(size).also { profile = it }

    /** The line of the statement at or before [pc] of [cb]. */
    fun lineAt(cb: CodeUnit, pc: Int): Int {
        val lt = cb.lineTable
        var line = -1
        var k = 0
        while (k < lt.size && lt[k] <= pc) {
            line = lt[k + 1]
            k += 2
        }
        return line
    }
}

/**
 * Compiles a module's syntax trees ([PyParser]) to code blocks of [PyOp]'s bytecode, of the [PyDialect], with the
 * scopes [PySymtable] found: a module's code, whose names are its globals; each function's, whose locals are
 * registers (its parameters first), whose cells (locals its nested functions read) and free variables (an enclosing
 * function's cells) are registers holding a cell, and whose other names are globals or builtins. Registers after the
 * names hold temporaries (an iterator, a comparison's middle operand); the operand stack is empty at every statement,
 * and so at every loop header.
 */
class PyCompiler private constructor(private val st: PySymtable, private val scope: Scope, private val name: String) {
    private val module = scope.type == Scope.Type.MODULE
    private val isClass = scope.type == Scope.Type.CLASS
    /** A module's or a class body's: names in a dict (the globals, the class's namespace), not registers. */
    private val namespace = module || isClass
    private val code = IntArrayList()
    private val constants = ArrayList<Any?>()
    private val constIndex = HashMap<Any, Int>()
    /** The register of each local, cell and free variable. */
    private val locals = LinkedHashMap<String, Int>()
    private var regs = 0
    private val lines = IntArrayList()
    private var line = 0

    /** The loops around the statement compiled: where `continue` and `break` go (patched once known). */
    private class Loop(val top: Int) {
        val breaks = ArrayList<Int>()
    }
    private val loops = ArrayList<Loop>()

    init {
        if (isClass) {
            for (n in scope.frees) locals[n] = regs++
            if (scope.classCell) locals["__class__"] = regs++
        } else if (!module) {
            for (p in scope.params) locals[p] = regs++
            for (n in scope.flags.keys) {
                val k = scope.kind(n)
                if ((k == Scope.Kind.LOCAL || k == Scope.Kind.CELL) && n !in locals) locals[n] = regs++
            }
            for (n in scope.frees) if (n !in locals) locals[n] = regs++
        }
    }

    companion object {
        /** The code block of module [src]. */
        @JvmStatic fun compileModule(src: String, name: String = "<module>"): CodeUnit {
            val m = PyParser(src).parseModule()
            val st = PySymtable.build(m)
            return PyCompiler(st, st.scopes[m]!!, name).compileBody(m.body, null)
        }
    }

    /** The code of [body]; [args], a function's parameters. */
    private fun compileBody(body: List<Stmt>, args: Arguments?): CodeUnit {
        prologue()
        val doc = docstring(body)
        if (isClass) {
            // a class's __module__, __qualname__ and __doc__, first in its namespace
            emit(PyOp.LOAD_GLOBAL, name("__name__"))
            emit(PyOp.STORE_NAME, name("__module__"))
            loadConst(PyStr(scope.qualname))
            emit(PyOp.STORE_NAME, name("__qualname__"))
            if (doc != null) {
                loadConst(PyStr(doc))
                emit(PyOp.STORE_NAME, name("__doc__"))
            }
        }
        for (s in body) stmt(s)
        // a class's __class__ cell, which type.__new__ fills with the class
        if (isClass && scope.classCell) {
            emit(PyOp.LOAD_FAST, locals["__class__"]!!)
            emit(PyOp.STORE_NAME, name("__classcell__"))
        }
        // the end of the code: return None
        loadConst(PyNone)
        emit(PyOp.RETURN)
        val cb = CodeUnit(name)
        cb.code = code.toArray()
        markUnbound(cb.code)
        cb.constants = constants.toTypedArray()
        cb.numRegs = regs
        cb.maxStack = maxStack(cb.code)
        cb.formalCount = scope.params.size
        cb.paramRegs = IntArray(scope.params.size) { it }
        cb.lineTable = lines.toArray()
        cb.dialect = PyDialect
        val names = Array(regs) { "" }
        for ((n, r) in locals) names[r] = n
        val info = PyCodeInfo(name, names, module)
        info.qualname = scope.qualname
        if (!namespace) info.doc = doc
        if (args != null) {
            info.posonlyCount = args.posonlyargs.size
            info.argcount = args.posonlyargs.size + args.args.size
            info.kwonlyCount = args.kwonlyargs.size
            info.varargs = args.vararg != null
            info.varkw = args.kwarg != null
            info.paramNames = scope.params.toList()
        }
        cb.dialectData = info
        return cb
    }

    /** A body's docstring: its first statement, where that is a string. */
    private fun docstring(body: List<Stmt>): String? = ((body.firstOrNull() as? ExprStmt)?.value as? Constant)?.value.let { (it as? PyStr)?.s }

    /** A function's start: its cells made (a parameter's holding its argument), its free variables' cells taken. */
    private fun prologue() {
        if (module) return
        if (isClass) {
            for ((k, n) in scope.frees.withIndex()) {
                emit(PyOp.LOAD_FREE, k)
                emit(PyOp.STORE_FAST, locals[n]!!)
            }
            if (scope.classCell) {
                emit(PyOp.NEW_CELL)
                emit(PyOp.STORE_FAST, locals["__class__"]!!)
            }
            return
        }
        for (n in scope.cells) {
            val r = locals[n]!!
            if (n in scope.params) {
                emit(PyOp.LOAD_FAST, r)
                emit(PyOp.MAKE_CELL)
            } else emit(PyOp.NEW_CELL)
            emit(PyOp.STORE_FAST, r)
        }
        for ((k, n) in scope.frees.withIndex()) {
            emit(PyOp.LOAD_FREE, k)
            emit(PyOp.STORE_FAST, locals[n]!!)
        }
    }

    // ------------------------------------------------------------------ emitting

    private fun emit(op: Int, vararg operands: Int): Int {
        val at = code.size
        code.add(op)
        for (o in operands) code.add(o)
        return at
    }

    /** Sets the target of the jump at [at] (its last operand) to [target]. */
    private fun patch(at: Int, target: Int) {
        code[at + PyOp.operands[code[at]]] = target
    }

    private fun const(v: Any?): Int {
        // (1, 1.0 and True are different constants: the key holds the class)
        val key: Any = if (v == null) PyNone else Pair(v.javaClass, if (v is Double) java.lang.Double.doubleToRawLongBits(v) else v)
        if (v is CodeUnit) return constants.size.also { constants.add(v) }
        return constIndex.getOrPut(key) { constants.size.also { constants.add(v) } }
    }

    private fun loadConst(v: Any?) = emit(PyOp.LOAD_CONST, const(v))

    private fun name(n: String): Int = const(PyStr(n))

    private fun temp(): Int = regs++

    private fun unsupported(what: String, n: Node): Nothing = throw PySyntaxError("$what is not supported yet", n.line, n.col)

    // ------------------------------------------------------------------ names

    private fun loadName(n: String) {
        if (isClass) {
            when (scope.kind(n)) {
                Scope.Kind.GLOBAL_EXPLICIT -> emit(PyOp.LOAD_GLOBAL, name(n))
                Scope.Kind.FREE, Scope.Kind.CELL -> {
                    emit(PyOp.LOAD_FAST, locals[n]!!)
                    emit(PyOp.LOAD_FROM_DICT_OR_DEREF, name(n))
                }
                else -> emit(PyOp.LOAD_NAME, name(n))
            }
            return
        }
        when (scope.kind(n)) {
            Scope.Kind.LOCAL -> if (module) emit(PyOp.LOAD_GLOBAL, name(n)) else emit(PyOp.LOAD_FAST, locals[n]!!)
            Scope.Kind.CELL, Scope.Kind.FREE -> {
                emit(PyOp.LOAD_FAST, locals[n]!!)
                emit(PyOp.CELL_GET, name(n), if (scope.kind(n) == Scope.Kind.FREE) 1 else 0)
            }
            else -> emit(PyOp.LOAD_GLOBAL, name(n))
        }
    }

    private fun storeName(n: String) {
        if (isClass) {
            when (scope.kind(n)) {
                Scope.Kind.GLOBAL_EXPLICIT -> emit(PyOp.STORE_GLOBAL, name(n))
                Scope.Kind.FREE -> if ((scope.flags[n] ?: 0) and Scope.DEF_NONLOCAL != 0) {
                    emit(PyOp.LOAD_FAST, locals[n]!!)
                    emit(PyOp.CELL_SET)
                } else emit(PyOp.STORE_NAME, name(n))
                else -> emit(PyOp.STORE_NAME, name(n))
            }
            return
        }
        when (scope.kind(n)) {
            Scope.Kind.LOCAL -> if (module) emit(PyOp.STORE_GLOBAL, name(n)) else emit(PyOp.STORE_FAST, locals[n]!!)
            Scope.Kind.CELL, Scope.Kind.FREE -> {
                emit(PyOp.LOAD_FAST, locals[n]!!)
                emit(PyOp.CELL_SET)
            }
            else -> emit(PyOp.STORE_GLOBAL, name(n))
        }
    }

    private fun deleteName(n: String) {
        if (isClass) {
            when (scope.kind(n)) {
                Scope.Kind.GLOBAL_EXPLICIT -> emit(PyOp.DELETE_GLOBAL, name(n))
                else -> emit(PyOp.DELETE_NAME, name(n))
            }
            return
        }
        when (scope.kind(n)) {
            Scope.Kind.LOCAL -> if (module) emit(PyOp.DELETE_GLOBAL, name(n)) else {
                // (a local without a value is an UnboundLocalError: loading it, as the check after this does)
                emit(PyOp.LOAD_FAST, locals[n]!!)
                emit(PyOp.POP)
                emit(PyOp.DELETE_FAST, locals[n]!!)
            }
            Scope.Kind.CELL, Scope.Kind.FREE -> {
                emit(PyOp.LOAD_FAST, locals[n]!!)
                emit(PyOp.CELL_DELETE, name(n), if (scope.kind(n) == Scope.Kind.FREE) 1 else 0)
            }
            else -> emit(PyOp.DELETE_GLOBAL, name(n))
        }
    }

    // ------------------------------------------------------------------ statements

    private fun stmt(s: Stmt) {
        // each statement's first pc and its line (the optimizing tier stores the pc where something can throw)
        line = s.line
        lines.add(code.size)
        lines.add(s.line)
        when (s) {
            is ExprStmt -> {
                expr(s.value)
                emit(PyOp.POP)
            }
            is Assign -> {
                expr(s.value)
                for ((k, t) in s.targets.withIndex()) {
                    if (k < s.targets.size - 1) emit(PyOp.DUP)
                    store(t)
                }
            }
            is AugAssign -> augAssign(s)
            is AnnAssign -> {
                if (s.value != null) {
                    expr(s.value)
                    store(s.target)
                } else when (val t = s.target) {
                    // (an annotation alone evaluates its target's parts, not the target)
                    is Attribute -> { expr(t.value); emit(PyOp.POP) }
                    is Subscript -> { expr(t.value); emit(PyOp.POP); expr(t.slice); emit(PyOp.POP) }
                    else -> {}
                }
            }
            is If -> {
                expr(s.test)
                val toElse = emit(PyOp.POP_JUMP_IF_FALSE, 0)
                block(s.body)
                if (s.orelse.isEmpty()) patch(toElse, code.size)
                else {
                    val toEnd = emit(PyOp.JUMP, 0)
                    patch(toElse, code.size)
                    block(s.orelse)
                    patch(toEnd, code.size)
                }
            }
            is While -> {
                val top = code.size
                expr(s.test)
                val exit = emit(PyOp.POP_JUMP_IF_FALSE, 0)
                val loop = Loop(top)
                loops.add(loop)
                block(s.body)
                loops.removeAt(loops.size - 1)
                emit(PyOp.JUMP, top)
                patch(exit, code.size)
                block(s.orelse)
                for (b in loop.breaks) patch(b, code.size)
            }
            is For -> {
                if (s.isAsync) unsupported("async for", s)
                expr(s.iter)
                emit(PyOp.GET_ITER)
                val it = temp()
                val value = temp()
                emit(PyOp.STORE_FAST, it)
                val top = code.size
                val exit = emit(PyOp.FOR_ITER, it, value, 0)
                emit(PyOp.LOAD_FAST, value)
                store(s.target)
                val loop = Loop(top)
                loops.add(loop)
                block(s.body)
                loops.removeAt(loops.size - 1)
                emit(PyOp.JUMP, top)
                patch(exit, code.size)
                block(s.orelse)
                for (b in loop.breaks) patch(b, code.size)
            }
            is FunctionDef -> {
                if (s.isAsync) unsupported("async def", s)
                if (s.typeParams.isNotEmpty()) unsupported("type parameters", s)
                for (d in s.decoratorList) expr(d)
                makeFunction(s, s.name, s.args, s.body)
                repeat(s.decoratorList.size) { emit(PyOp.CALL, 1) }
                storeName(s.name)
            }
            is Return -> {
                if (namespace) throw PySyntaxError("'return' outside function", s.line, s.col)
                if (s.value == null) loadConst(PyNone) else expr(s.value)
                emit(PyOp.RETURN)
            }
            is Delete -> for (t in s.targets) delete(t)
            is Assert -> {
                expr(s.test)
                val ok = emit(PyOp.POP_JUMP_IF_TRUE, 0)
                if (s.msg != null) {
                    expr(s.msg)
                    emit(PyOp.ASSERT_FAIL, 1)
                } else emit(PyOp.ASSERT_FAIL, 0)
                patch(ok, code.size)
            }
            is Global, is Nonlocal, is Pass -> {}
            is Break -> {
                val loop = loops.lastOrNull() ?: throw PySyntaxError("'break' outside loop", s.line, s.col)
                loop.breaks.add(emit(PyOp.JUMP, 0))
            }
            is Continue -> {
                val loop = loops.lastOrNull() ?: throw PySyntaxError("'continue' not properly in loop", s.line, s.col)
                emit(PyOp.JUMP, loop.top)
            }
            is ClassDef -> classDef(s)
            is Try -> unsupported(if (s.star) "try with except*" else "try", s)
            is Raise -> unsupported("raise", s)
            is With -> unsupported(if (s.isAsync) "async with" else "with", s)
            is Match -> unsupported("match", s)
            is Import, is ImportFrom -> unsupported("import", s)
            is TypeAlias -> unsupported("type", s)
        }
    }

    private fun block(ss: List<Stmt>) {
        for (s in ss) stmt(s)
    }

    /**
     * A function (or a lambda, [body] a return of its expression): its defaults, keyword-only defaults and the cells
     * of its free variables evaluated here, then the function made of its code.
     */
    private fun makeFunction(node: Node, fname: String, args: Arguments, body: List<Stmt>) {
        val child = st.scopes[node]!!
        var flags = 0
        if (args.defaults.isNotEmpty()) {
            for (d in args.defaults) expr(d)
            emit(PyOp.BUILD_TUPLE, args.defaults.size)
            flags = flags or 1
        }
        val kwd = args.kwonlyargs.indices.filter { args.kwDefaults[it] != null }
        if (kwd.isNotEmpty()) {
            for (i in kwd) {
                loadConst(PyStr(args.kwonlyargs[i].arg))
                expr(args.kwDefaults[i]!!)
            }
            emit(PyOp.BUILD_MAP, kwd.size)
            flags = flags or 2
        }
        if (child.frees.isNotEmpty()) {
            for (n in child.frees) emit(PyOp.LOAD_FAST, locals[n] ?: throw IllegalStateException("no cell for $n in $name"))
            emit(PyOp.BUILD_TUPLE, child.frees.size)
            flags = flags or 8
        }
        val cb = PyCompiler(st, child, fname).compileBody(body, args)
        emit(PyOp.MAKE_FUNCTION, const(cb))
        for (flag in intArrayOf(8, 2, 1)) if (flags and flag != 0) emit(PyOp.SET_FUNCTION_ATTRIBUTE, flag)
    }

    /** Stores the value on top of the stack to [t]. */
    private fun store(t: Expr) {
        when (t) {
            is Name -> storeName(t.id)
            is Subscript -> {
                if (t.slice is Slice) unsupported("slicing", t)
                expr(t.value)
                expr(t.slice)
                emit(PyOp.STORE_SUBSCR)
            }
            is TupleExpr, is ListExpr -> {
                val elts = if (t is TupleExpr) t.elts else (t as ListExpr).elts
                val star = elts.indexOfFirst { it is Starred }
                if (star < 0) emit(PyOp.UNPACK, elts.size)
                else emit(PyOp.UNPACK_EX, star, elts.size - star - 1)
                for (e in elts) store(if (e is Starred) e.value else e)
            }
            is Attribute -> {
                expr(t.value)
                emit(PyOp.STORE_ATTR, name(t.attr))
            }
            else -> unsupported("assignment to this target", t)
        }
    }

    private fun delete(t: Expr) {
        when (t) {
            is Name -> deleteName(t.id)
            is Subscript -> {
                if (t.slice is Slice) unsupported("slicing", t)
                expr(t.value)
                expr(t.slice)
                emit(PyOp.DELETE_SUBSCR)
            }
            is Attribute -> {
                expr(t.value)
                emit(PyOp.DELETE_ATTR, name(t.attr))
            }
            is TupleExpr -> t.elts.forEach { delete(it) }
            is ListExpr -> t.elts.forEach { delete(it) }
            else -> throw PySyntaxError("cannot delete expression", t.line, t.col)
        }
    }

    private fun augAssign(s: AugAssign) {
        val op = binaryOp(s.op, s)
        when (val t = s.target) {
            is Name -> {
                loadName(t.id)
                expr(s.value)
                emit(PyOp.INPLACE, op)
                storeName(t.id)
            }
            is Subscript -> {
                if (t.slice is Slice) unsupported("slicing", t)
                // obj and key once: in temporaries
                val o = temp()
                val k = temp()
                expr(t.value)
                emit(PyOp.STORE_FAST, o)
                expr(t.slice)
                emit(PyOp.STORE_FAST, k)
                emit(PyOp.LOAD_FAST, o)
                emit(PyOp.LOAD_FAST, k)
                emit(PyOp.SUBSCR)
                expr(s.value)
                emit(PyOp.INPLACE, op)
                emit(PyOp.LOAD_FAST, o)
                emit(PyOp.LOAD_FAST, k)
                emit(PyOp.STORE_SUBSCR)
            }
            is Attribute -> {
                // obj once: in a temporary
                val o = temp()
                expr(t.value)
                emit(PyOp.STORE_FAST, o)
                emit(PyOp.LOAD_FAST, o)
                emit(PyOp.LOAD_ATTR, name(t.attr))
                expr(s.value)
                emit(PyOp.INPLACE, op)
                emit(PyOp.LOAD_FAST, o)
                emit(PyOp.STORE_ATTR, name(t.attr))
            }
            else -> unsupported("augmented assignment to this target", s)
        }
    }

    /**
     * `class`: __build_class__(the body as a function, its name, *bases, **keywords), the decorators applied, the
     * class stored under its name.
     */
    private fun classDef(s: ClassDef) {
        if (s.typeParams.isNotEmpty()) unsupported("type parameters", s)
        for (d in s.decoratorList) expr(d)
        emit(PyOp.LOAD_BUILD_CLASS)
        val child = st.scopes[s]!!
        if (child.frees.isNotEmpty()) {
            for (n in child.frees) emit(PyOp.LOAD_FAST, locals[n] ?: throw IllegalStateException("no cell for $n in $name"))
            emit(PyOp.BUILD_TUPLE, child.frees.size)
        }
        val cb = PyCompiler(st, child, s.name).compileBody(s.body, null)
        emit(PyOp.MAKE_FUNCTION, const(cb))
        if (child.frees.isNotEmpty()) emit(PyOp.SET_FUNCTION_ATTRIBUTE, 8)
        loadConst(PyStr(s.name))
        callArgs(2, s.bases, s.keywords)
        repeat(s.decoratorList.size) { emit(PyOp.CALL, 1) }
        storeName(s.name)
    }

    // ------------------------------------------------------------------ expressions

    private fun binaryOp(op: OpKind, n: Node): Int = when (op) {
        OpKind.Add -> PyOp.ADD
        OpKind.Sub -> PyOp.SUB
        OpKind.Mult -> PyOp.MUL
        OpKind.Div -> PyOp.TRUEDIV
        OpKind.FloorDiv -> PyOp.FLOORDIV
        OpKind.Mod -> PyOp.MOD
        OpKind.Pow -> PyOp.POW
        OpKind.LShift -> PyOp.LSHIFT
        OpKind.RShift -> PyOp.RSHIFT
        OpKind.BitAnd -> PyOp.BITAND
        OpKind.BitOr -> PyOp.BITOR
        OpKind.BitXor -> PyOp.BITXOR
        OpKind.MatMult -> PyOp.MATMUL
    }

    private fun compareOp(op: CmpOpKind): Int = when (op) {
        CmpOpKind.Lt -> PyOp.LT
        CmpOpKind.LtE -> PyOp.LE
        CmpOpKind.Gt -> PyOp.GT
        CmpOpKind.GtE -> PyOp.GE
        CmpOpKind.Eq -> PyOp.EQ
        CmpOpKind.NotEq -> PyOp.NE
        CmpOpKind.Is -> PyOp.IS
        CmpOpKind.IsNot -> PyOp.IS_NOT
        CmpOpKind.In -> PyOp.IN
        CmpOpKind.NotIn -> PyOp.NOT_IN
    }

    private fun expr(e: Expr) {
        when (e) {
            is Constant -> loadConst(e.value)
            is Name -> loadName(e.id)
            is BinOp -> {
                expr(e.left)
                expr(e.right)
                emit(binaryOp(e.op, e))
            }
            is UnaryOp -> {
                expr(e.operand)
                emit(when (e.op) {
                    UnaryOpKind.Not -> PyOp.NOT
                    UnaryOpKind.USub -> PyOp.NEG
                    UnaryOpKind.UAdd -> PyOp.POS
                    UnaryOpKind.Invert -> PyOp.INVERT
                })
            }
            is BoolOp -> {
                // a or b: a where it is true, else b (a and b: a where it is false)
                val jumps = ArrayList<Int>()
                for ((k, v) in e.values.withIndex()) {
                    expr(v)
                    if (k < e.values.size - 1) {
                        emit(PyOp.DUP)
                        jumps.add(emit(if (e.op == BoolOpKind.Or) PyOp.POP_JUMP_IF_TRUE else PyOp.POP_JUMP_IF_FALSE, 0))
                        emit(PyOp.POP)
                    }
                }
                for (j in jumps) patch(j, code.size)
            }
            is Compare -> {
                expr(e.left)
                if (e.ops.size == 1) {
                    expr(e.comparators[0])
                    emit(compareOp(e.ops[0]))
                    return
                }
                // a < b < c: b once, in a temporary; the first false comparison is the result
                val mid = temp()
                val jumps = ArrayList<Int>()
                for ((k, op) in e.ops.withIndex()) {
                    expr(e.comparators[k])
                    val last = k == e.ops.size - 1
                    if (!last) {
                        emit(PyOp.STORE_FAST, mid)
                        emit(PyOp.LOAD_FAST, mid)
                    }
                    emit(compareOp(op))
                    if (!last) {
                        emit(PyOp.DUP)
                        jumps.add(emit(PyOp.POP_JUMP_IF_FALSE, 0))
                        emit(PyOp.POP)
                        emit(PyOp.LOAD_FAST, mid)
                    }
                }
                for (j in jumps) patch(j, code.size)
            }
            is IfExp -> {
                expr(e.test)
                val toElse = emit(PyOp.POP_JUMP_IF_FALSE, 0)
                expr(e.body)
                val toEnd = emit(PyOp.JUMP, 0)
                patch(toElse, code.size)
                expr(e.orelse)
                patch(toEnd, code.size)
            }
            is NamedExpr -> {
                expr(e.value)
                emit(PyOp.DUP)
                storeName(e.target.id)
            }
            is Lambda -> makeFunction(e, "<lambda>", e.args, listOf(Return(e.body).also { it.line = e.line; it.col = e.col }))
            is Call -> call(e)
            is Attribute -> {
                expr(e.value)
                emit(PyOp.LOAD_ATTR, name(e.attr))
            }
            is Subscript -> {
                if (e.slice is Slice || e.slice is TupleExpr && (e.slice as TupleExpr).elts.any { it is Slice }) unsupported("slicing", e)
                expr(e.value)
                expr(e.slice)
                emit(PyOp.SUBSCR)
            }
            is ListExpr -> {
                if (e.elts.none { it is Starred }) {
                    for (x in e.elts) expr(x)
                    emit(PyOp.BUILD_LIST, e.elts.size)
                } else unpackingList(e.elts)
            }
            is TupleExpr -> {
                if (e.elts.none { it is Starred }) {
                    for (x in e.elts) expr(x)
                    emit(PyOp.BUILD_TUPLE, e.elts.size)
                } else {
                    unpackingList(e.elts)
                    emit(PyOp.LIST_TO_TUPLE)
                }
            }
            is DictExpr -> {
                val first = e.keys.indexOfFirst { it == null }.let { if (it < 0) e.keys.size else it }
                for (k in 0 until first) {
                    expr(e.keys[k]!!)
                    expr(e.values[k])
                }
                emit(PyOp.BUILD_MAP, first)
                for (k in first until e.keys.size) {
                    val key = e.keys[k]
                    if (key == null) {
                        expr(e.values[k])
                        emit(PyOp.DICT_UPDATE)
                    } else {
                        expr(key)
                        expr(e.values[k])
                        emit(PyOp.MAP_ADD)
                    }
                }
            }
            is Starred -> throw PySyntaxError("can't use starred expression here", e.line, e.col)
            is SetExpr, is SetComp -> unsupported("sets", e)
            is ListComp, is DictComp, is GeneratorExp -> unsupported("comprehensions", e)
            is Yield, is YieldFrom -> unsupported("yield", e)
            is Await -> unsupported("await", e)
            is JoinedStr, is FormattedValue -> unsupported("f-strings", e)
            is TemplateStr, is Interpolation -> unsupported("t-strings", e)
            is Slice -> unsupported("slicing", e)
        }
    }

    /** A list of [elts], `*x` among them: the items before the first `*x` built, the rest appended or extended. */
    private fun unpackingList(elts: List<Expr>) {
        val first = elts.indexOfFirst { it is Starred }.let { if (it < 0) elts.size else it }
        for (k in 0 until first) expr(elts[k])
        emit(PyOp.BUILD_LIST, first)
        for (k in first until elts.size) {
            val x = elts[k]
            if (x is Starred) {
                expr(x.value)
                emit(PyOp.LIST_EXTEND)
            } else {
                expr(x)
                emit(PyOp.LIST_APPEND)
            }
        }
    }

    /**
     * A call: CALL with positional arguments, CALL_KW with keyword arguments too (their names a tuple constant),
     * CALL_EX with `*` or `**` (the positional arguments a list, the keyword arguments a list of dicts).
     */
    private fun call(e: Call) {
        // super() in a method: super(__class__, its first parameter)
        if (e.func is Name && (e.func as Name).id == "super" && e.args.isEmpty() && e.keywords.isEmpty() &&
            scope.kind("super") == Scope.Kind.GLOBAL_IMPLICIT && scope.type == Scope.Type.FUNCTION &&
            scope.params.isNotEmpty() && scope.kind("__class__") == Scope.Kind.FREE) {
            emit(PyOp.LOAD_GLOBAL, name("super"))
            loadName("__class__")
            loadName(scope.params[0])
            emit(PyOp.CALL, 2)
            return
        }
        expr(e.func)
        callArgs(0, e.args, e.keywords)
    }

    /** A call's arguments, after the callable and [pushed] arguments already on the stack, and the call. */
    private fun callArgs(pushed: Int, args: List<Expr>, keywords: List<Keyword>) {
        val seen = HashSet<String>()
        for (k in keywords) if (k.arg != null && !seen.add(k.arg)) throw PySyntaxError("keyword argument repeated: ${k.arg}", k.line, k.col)
        val unpacking = args.any { it is Starred } || keywords.any { it.arg == null }
        if (!unpacking) {
            for (a in args) expr(a)
            if (keywords.isEmpty()) {
                emit(PyOp.CALL, pushed + args.size)
                return
            }
            for (k in keywords) expr(k.value)
            loadConst(PyTuple(Array(keywords.size) { PyStr(keywords[it].arg!!) }))
            emit(PyOp.CALL_KW, pushed + args.size + keywords.size)
            return
        }
        val first = args.indexOfFirst { it is Starred }.let { if (it < 0) args.size else it }
        for (k in 0 until first) expr(args[k])
        emit(PyOp.BUILD_LIST, pushed + first)
        for (k in first until args.size) {
            val x = args[k]
            if (x is Starred) {
                expr(x.value)
                emit(PyOp.WRAP_STAR)
            } else expr(x)
            emit(PyOp.LIST_APPEND)
        }
        if (keywords.isEmpty()) {
            emit(PyOp.CALL_EX, 0)
            return
        }
        emit(PyOp.BUILD_LIST, 0)
        var k = 0
        while (k < keywords.size) {
            val kw = keywords[k]
            if (kw.arg == null) {
                expr(kw.value)
                k++
            } else {
                var n = 0
                while (k < keywords.size && keywords[k].arg != null) {
                    loadConst(PyStr(keywords[k].arg!!))
                    expr(keywords[k].value)
                    k++
                    n++
                }
                emit(PyOp.BUILD_MAP, n)
            }
            emit(PyOp.LIST_APPEND)
        }
        emit(PyOp.CALL_EX, 1)
    }

    // ------------------------------------------------------------------ after emitting

    /** The successors of the instruction at [pc] of [bc]. */
    private fun successors(bc: IntArray, pc: Int): IntArray {
        val op = bc[pc]
        val next = pc + PyOp.length(op)
        return when (op) {
            PyOp.JUMP -> intArrayOf(bc[pc + 1])
            PyOp.POP_JUMP_IF_FALSE, PyOp.POP_JUMP_IF_TRUE -> intArrayOf(bc[pc + 1], next)
            PyOp.FOR_ITER -> intArrayOf(bc[pc + 3], next)
            PyOp.RETURN -> IntArray(0)
            else -> intArrayOf(next)
        }
    }

    /** The deepest the operand stack gets (every path reaches an instruction at one depth). */
    private fun maxStack(bc: IntArray): Int {
        val depth = IntArray(bc.size) { -1 }
        val work = ArrayDeque<Int>()
        depth[0] = 0
        work.add(0)
        var max = 0
        while (work.isNotEmpty()) {
            val pc = work.removeLast()
            val op = bc[pc]
            val n = PyOp.operands[op]
            val d = depth[pc] + PyOp.stackEffect(op, if (n > 0) bc[pc + 1] else 0, if (n > 1) bc[pc + 2] else 0)
            if (d < 0) throw IllegalStateException("operand stack underflow at $pc in $name")
            if (d > max) max = d
            for (s in successors(bc, pc)) {
                if (depth[s] < 0) {
                    depth[s] = d
                    work.add(s)
                } else if (depth[s] != d) throw IllegalStateException("operand stack depth differs at $s in $name")
            }
        }
        return max
    }

    /**
     * Each LOAD_FAST of a local that may have no value there (on some path from the start, nothing stored it, or a
     * del took it) becomes a LOAD_FAST_CHECK, which raises the UnboundLocalError (CPython's compiler does the same): a
     * forward dataflow of the registers that may be unbound, parameters bound at the start.
     */
    private fun markUnbound(bc: IntArray) {
        if (namespace) return
        val n = regs
        val maybe = arrayOfNulls<java.util.BitSet>(bc.size)
        val start = java.util.BitSet(n)
        start.set(scope.params.size, n)
        maybe[0] = start
        val work = ArrayDeque<Int>()
        work.add(0)
        while (work.isNotEmpty()) {
            val pc = work.removeLast()
            val out = maybe[pc]!!.clone() as java.util.BitSet
            val op = bc[pc]
            if (op == PyOp.STORE_FAST) out.clear(bc[pc + 1])
            if (op == PyOp.DELETE_FAST) out.set(bc[pc + 1])
            for (s in successors(bc, pc)) {
                // FOR_ITER writes its value register only on the way into the loop's body
                val o = if (op == PyOp.FOR_ITER && s == pc + PyOp.length(op)) (out.clone() as java.util.BitSet).also { it.clear(bc[pc + 2]) } else out
                val cur = maybe[s]
                if (cur == null) {
                    maybe[s] = o.clone() as java.util.BitSet
                    work.add(s)
                } else {
                    val before = cur.cardinality()
                    cur.or(o)
                    if (cur.cardinality() != before) work.add(s)
                }
            }
        }
        var pc = 0
        while (pc < bc.size) {
            val m = maybe[pc]
            if (bc[pc] == PyOp.LOAD_FAST && m != null && m[bc[pc + 1]]) bc[pc] = PyOp.LOAD_FAST_CHECK
            pc += PyOp.length(bc[pc])
        }
    }
}

/** A growable array of ints. */
internal class IntArrayList {
    private var a = IntArray(64)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }

    operator fun get(i: Int) = a[i]
    operator fun set(i: Int, v: Int) {
        a[i] = v
    }

    fun toArray(): IntArray = a.copyOf(size)
}
