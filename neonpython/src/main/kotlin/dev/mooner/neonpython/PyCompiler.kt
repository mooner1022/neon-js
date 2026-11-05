package dev.mooner.neonpython

import dev.mooner.neonjs.compiler.CodeUnit

/**
 * What a Python code block keeps besides its bytecode ([CodeUnit.dialectData]): its function's name, the names of its
 * locals by register, and the source line of each statement's pc (pairs in [CodeUnit.lineTable]).
 */
class PyCodeInfo(@JvmField val name: String, @JvmField val localNames: Array<String>, @JvmField val module: Boolean) {
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
 * Compiles a module's syntax trees ([PyParser]) to code blocks of [PyOp]'s bytecode, of the [PyDialect]: a module's
 * code, whose names are its globals, and each function's, whose names are locals (registers: its parameters first)
 * where it assigns them and does not declare them `global`, globals (or builtins) otherwise. Registers after the
 * locals hold temporaries (an iterator, a comparison's middle operand); the operand stack is empty at every
 * statement, and so at every loop header.
 */
class PyCompiler private constructor(
    private val name: String,
    private val module: Boolean,
    params: List<String>,
    /** The locals of the enclosing functions (nested functions do not close over them yet). */
    private val enclosing: Set<String>,
) {
    private val code = IntArrayList()
    private val constants = ArrayList<Any?>()
    private val constIndex = HashMap<Any, Int>()
    private val locals = LinkedHashMap<String, Int>()
    private val globals = HashSet<String>()
    private var regs = 0
    private val lines = IntArrayList()
    private var line = 0

    /** The loops around the statement compiled: where `continue` and `break` go (patched once known). */
    private class Loop(val top: Int) {
        val breaks = ArrayList<Int>()
    }
    private val loops = ArrayList<Loop>()

    private val paramCount = params.size

    init {
        for (p in params) locals[p] = regs++
    }

    companion object {
        /** The code block of module [src]. */
        @JvmStatic fun compileModule(src: String, name: String = "<module>"): CodeUnit {
            val body = PyParser(src).parseModule()
            return PyCompiler(name, module = true, params = emptyList(), enclosing = emptySet()).compileBody(body)
        }
    }

    private fun compileBody(body: List<Stmt>): CodeUnit {
        if (!module) scanLocals(body)
        for (s in body) stmt(s)
        // the end of the code: return None
        loadConst(PyNone)
        emit(PyOp.RETURN)
        val cb = CodeUnit(name)
        cb.code = code.toArray()
        markUnbound(cb.code)
        cb.constants = constants.toTypedArray()
        cb.numRegs = regs
        cb.maxStack = maxStack(cb.code)
        cb.formalCount = paramCount
        cb.paramRegs = IntArray(paramCount) { it }
        cb.lineTable = lines.toArray()
        cb.dialect = PyDialect
        val names = Array(regs) { "" }
        for ((n, r) in locals) names[r] = n
        cb.dialectData = PyCodeInfo(name, names, module)
        return cb
    }

    // ------------------------------------------------------------------ scopes

    /** A function's locals: the names it assigns (targets, `for`, `def`) and does not declare `global`. */
    private fun scanLocals(body: List<Stmt>) {
        val assigned = LinkedHashSet<String>()
        fun target(e: Expr) {
            when (e) {
                is Name -> assigned.add(e.id)
                is TupleExpr -> e.elts.forEach { target(it) }
                is ListExpr -> e.elts.forEach { target(it) }
                else -> {}
            }
        }
        fun visit(ss: List<Stmt>) {
            for (s in ss) when (s) {
                is Assign -> s.targets.forEach { target(it) }
                is AugAssign -> target(s.target)
                is For -> { target(s.target); visit(s.body); visit(s.orelse) }
                is While -> { visit(s.body); visit(s.orelse) }
                is If -> { visit(s.body); visit(s.orelse) }
                is FunctionDef -> assigned.add(s.name)
                is Global -> for (n in s.names) {
                    if (n in locals) throw PySyntaxError("name '$n' is parameter and global", s.line, 0)
                    globals.add(n)
                }
                else -> {}
            }
        }
        visit(body)
        for (n in assigned) if (n !in globals && n !in locals) locals[n] = regs++
    }

    private fun isLocal(n: String) = !module && n in locals

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
                val outer = if (module) emptySet() else enclosing + locals.keys
                val c = PyCompiler(s.name, module = false, params = s.params, enclosing = outer)
                val cb = c.compileBody(s.body)
                emit(PyOp.MAKE_FUNCTION, const(cb))
                storeName(s.name)
            }
            is Return -> {
                if (module) throw PySyntaxError("'return' outside function", s.line, 0)
                if (s.value == null) loadConst(PyNone) else expr(s.value)
                emit(PyOp.RETURN)
            }
            is Global -> if (module) for (n in s.names) globals.add(n)
            Pass -> {}
            Break -> {
                val loop = loops.lastOrNull() ?: throw PySyntaxError("'break' outside loop", line, 0)
                loop.breaks.add(emit(PyOp.JUMP, 0))
            }
            Continue -> {
                val loop = loops.lastOrNull() ?: throw PySyntaxError("'continue' not properly in loop", line, 0)
                emit(PyOp.JUMP, loop.top)
            }
        }
    }

    private fun block(ss: List<Stmt>) {
        for (s in ss) stmt(s)
    }

    private fun storeName(n: String) {
        if (isLocal(n)) emit(PyOp.STORE_FAST, locals[n]!!) else emit(PyOp.STORE_GLOBAL, name(n))
    }

    /** Stores the value on top of the stack to [t]. */
    private fun store(t: Expr) {
        when (t) {
            is Name -> storeName(t.id)
            is Subscript -> {
                expr(t.value)
                expr(t.index)
                emit(PyOp.STORE_SUBSCR)
            }
            is TupleExpr, is ListExpr -> {
                val elts = if (t is TupleExpr) t.elts else (t as ListExpr).elts
                emit(PyOp.UNPACK, elts.size)
                for (e in elts) store(e)
            }
            else -> throw PySyntaxError("cannot assign to this target yet", t.line, 0)
        }
    }

    private fun augAssign(s: AugAssign) {
        val op = binaryOp(s.op, s.line)
        when (val t = s.target) {
            is Name -> {
                loadName(t.id)
                expr(s.value)
                emit(op)
                storeName(t.id)
            }
            is Subscript -> {
                // obj and key once: in temporaries
                val o = temp()
                val k = temp()
                expr(t.value)
                emit(PyOp.STORE_FAST, o)
                expr(t.index)
                emit(PyOp.STORE_FAST, k)
                emit(PyOp.LOAD_FAST, o)
                emit(PyOp.LOAD_FAST, k)
                emit(PyOp.SUBSCR)
                expr(s.value)
                emit(op)
                emit(PyOp.LOAD_FAST, o)
                emit(PyOp.LOAD_FAST, k)
                emit(PyOp.STORE_SUBSCR)
            }
            else -> throw PySyntaxError("augmented assignment to this target is not supported yet", s.line, 0)
        }
    }

    // ------------------------------------------------------------------ expressions

    private fun loadName(n: String) {
        when {
            isLocal(n) -> emit(PyOp.LOAD_FAST, locals[n]!!)
            n in enclosing && n !in globals -> throw PySyntaxError("closures are not supported yet (name '$n' of an enclosing function)", line, 0)
            else -> emit(PyOp.LOAD_GLOBAL, name(n))
        }
    }

    private fun binaryOp(op: String, line: Int): Int = when (op) {
        "+" -> PyOp.ADD
        "-" -> PyOp.SUB
        "*" -> PyOp.MUL
        "/" -> PyOp.TRUEDIV
        "//" -> PyOp.FLOORDIV
        "%" -> PyOp.MOD
        "**" -> PyOp.POW
        "<<" -> PyOp.LSHIFT
        ">>" -> PyOp.RSHIFT
        "&" -> PyOp.BITAND
        "|" -> PyOp.BITOR
        "^" -> PyOp.BITXOR
        else -> throw PySyntaxError("operator '$op' is not supported yet", line, 0)
    }

    private fun compareOp(op: String): Int = when (op) {
        "<" -> PyOp.LT
        "<=" -> PyOp.LE
        ">" -> PyOp.GT
        ">=" -> PyOp.GE
        "==" -> PyOp.EQ
        "!=" -> PyOp.NE
        "is" -> PyOp.IS
        "is not" -> PyOp.IS_NOT
        "in" -> PyOp.IN
        else -> PyOp.NOT_IN
    }

    private fun expr(e: Expr) {
        when (e) {
            is Constant -> loadConst(e.value)
            is Name -> loadName(e.id)
            is BinOp -> {
                expr(e.left)
                expr(e.right)
                emit(binaryOp(e.op, e.line))
            }
            is UnaryOp -> {
                expr(e.operand)
                emit(when (e.op) {
                    "not" -> PyOp.NOT
                    "-" -> PyOp.NEG
                    "+" -> PyOp.POS
                    else -> PyOp.INVERT
                })
            }
            is BoolOp -> {
                // a or b: a where it is true, else b (a and b: a where it is false)
                val jumps = ArrayList<Int>()
                for ((k, v) in e.values.withIndex()) {
                    expr(v)
                    if (k < e.values.size - 1) {
                        emit(PyOp.DUP)
                        jumps.add(emit(if (e.op == "or") PyOp.POP_JUMP_IF_TRUE else PyOp.POP_JUMP_IF_FALSE, 0))
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
            is Call -> {
                expr(e.func)
                for (a in e.args) expr(a)
                emit(PyOp.CALL, e.args.size)
            }
            is Attribute -> {
                expr(e.value)
                emit(PyOp.LOAD_ATTR, name(e.attr))
            }
            is Subscript -> {
                expr(e.value)
                expr(e.index)
                emit(PyOp.SUBSCR)
            }
            is ListExpr -> {
                for (x in e.elts) expr(x)
                emit(PyOp.BUILD_LIST, e.elts.size)
            }
            is TupleExpr -> {
                for (x in e.elts) expr(x)
                emit(PyOp.BUILD_TUPLE, e.elts.size)
            }
            is DictExpr -> {
                for (k in e.keys.indices) {
                    expr(e.keys[k])
                    expr(e.values[k])
                }
                emit(PyOp.BUILD_MAP, e.keys.size)
            }
        }
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
            val d = depth[pc] + PyOp.stackEffect(op, if (PyOp.operands[op] > 0) bc[pc + 1] else 0)
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
     * Each LOAD_FAST of a local that may have no value there (on some path from the start, nothing stored it) becomes a
     * LOAD_FAST_CHECK, which raises the UnboundLocalError (CPython's compiler does the same): a forward dataflow of the
     * registers that may be unbound, parameters bound at the start.
     */
    private fun markUnbound(bc: IntArray) {
        if (module) return
        val n = regs
        val maybe = arrayOfNulls<java.util.BitSet>(bc.size)
        val start = java.util.BitSet(n)
        start.set(paramCount, n)
        maybe[0] = start
        val work = ArrayDeque<Int>()
        work.add(0)
        while (work.isNotEmpty()) {
            val pc = work.removeLast()
            val out = maybe[pc]!!.clone() as java.util.BitSet
            val op = bc[pc]
            if (op == PyOp.STORE_FAST) out.clear(bc[pc + 1])
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
