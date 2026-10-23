package dev.mooner.neonjs.jit.opt

import dev.mooner.neonjs.compiler.CodeBlock
import dev.mooner.neonjs.compiler.Op
import dev.mooner.neonjs.jit.JitBailout
import dev.mooner.neonjs.runtime.Null
import dev.mooner.neonjs.runtime.Undefined

/**
 * Builds the [Graph] of a code block from its bytecode: registers and operand stack slots become SSA values, with a phi
 * at every merge for every one of them, then the phis that merge one value (or only themselves) are replaced by it and
 * those nothing uses are dropped. Kinds come from [Kinds], as the present compiler's type analysis gives them.
 *
 * Code blocks with exception handlers, and instructions the IR does not have yet ([arity] -1), are not built
 * ([JitBailout]): such blocks keep their baseline code.
 */
internal class IrBuilder(
    private val cb: CodeBlock,
    private val ints: Boolean,
    private val elems: Boolean,
    /** Whether the code gets the deopt stress mode's exits: at its start and its loop headers. */
    private val stressExits: Boolean = false,
) {
    private val code = cb.code
    private val nregs = cb.numRegs
    private val g = Graph(cb)

    /** Block starting at each pc that starts one. */
    private val blockAt = HashMap<Int, Block>()
    /** State at the end of each block built: registers then the operand stack. */
    private val exits = HashMap<Block, Array<Value>>()
    private val exitDepth = HashMap<Block, Int>()
    /** Statement start in effect at each pc (the line table's entries). */
    private val stmtOf = IntArray(code.size)

    fun build(): Graph {
        if (cb.handlers.isNotEmpty()) throw JitBailout("exception handlers")
        if (cb.isGenerator || cb.isAsync) throw JitBailout("generator or async")
        scan()
        // a start block: the parameters, then the code (whose first block may be a loop header)
        val start = Block(g.newId(), -1)
        val order = reversePostOrder(blockAt[0]!!)
        val first = order[0]
        g.blocks.add(start)
        g.blocks.addAll(order)
        // every predecessor, before any block is built: a block with more than one gets phis
        first.preds.add(start)
        for (b in order) for (s in successors(b.pc)) blockAt[s]!!.preds.add(b)
        val regs = Array<Value>(nregs) { undefined() }
        cb.paramRegs?.forEachIndexed { i, r ->
            if (r >= 0) regs[r] = add(start, Inst(Ir.PARAM, NO_VALUES, i, 0, 0, -1, -1).also { it.hasResult = true })
        }
        goto(start, first)
        exits[start] = regs
        exitDepth[start] = 0
        for (b in order) buildBlock(b)
        for (b in order) fillPhis(b)
        removeTrivialPhis()
        removeDeadPhis()
        Kinds(ints, elems).infer(g)
        number()
        return g
    }

    // ------------------------------------------------------------------ blocks

    private fun scan() {
        val leader = BooleanArray(code.size + 1)
        leader[0] = true
        var pc = 0
        while (pc < code.size) {
            val op = code[pc]
            val len = Op.length(code, pc)
            if (!isJumpOp(op) && op != Op.RETURN && op != Op.THROW && op != Op.THROW_ERROR && arity(op, pc) < 0) {
                throw JitBailout("unsupported op ${Op.names[op]}")
            }
            if (isJumpOp(op)) {
                leader[code[pc + 1]] = true
                leader[pc + len] = true
            } else if (op == Op.RETURN || op == Op.THROW || op == Op.THROW_ERROR) leader[pc + len] = true
            pc += len
        }
        for (p in 0 until code.size) if (leader[p]) blockAt[p] = Block(-1, p)
        val lt = cb.lineTable
        var stmt = -1
        var k = 0
        for (p in code.indices) {
            while (k < lt.size && lt[k] <= p) {
                stmt = lt[k]
                k += 2
            }
            stmtOf[p] = stmt
        }
    }

    /**
     * The successors of the block starting at [start], from its last instruction: a conditional jump's target, then the
     * next instruction. A conditional jump to the next instruction is one edge (built as a GOTO: ToBoolean and the
     * nullish tests have no effect).
     */
    private fun successors(start: Int): List<Int> {
        var pc = start
        while (true) {
            val op = code[pc]
            val next = pc + Op.length(code, pc)
            when {
                op == Op.JUMP -> return listOf(code[pc + 1])
                isJumpOp(op) -> return if (code[pc + 1] == next) listOf(next) else listOf(code[pc + 1], next)
                op == Op.RETURN || op == Op.THROW || op == Op.THROW_ERROR -> return emptyList()
            }
            if (next >= code.size) throw JitBailout("control flow leaves the code")
            if (blockAt.containsKey(next)) return listOf(next)
            pc = next
        }
    }

    private fun reversePostOrder(first: Block): List<Block> {
        val post = ArrayList<Block>()
        val state = HashMap<Block, Int>() // 1 on the DFS stack, 2 done
        // iterative DFS: (block, successors left)
        val stack = ArrayDeque<Pair<Block, Iterator<Int>>>()
        state[first] = 1
        stack.addLast(first to successors(first.pc).iterator())
        while (stack.isNotEmpty()) {
            val (b, succ) = stack.last()
            if (succ.hasNext()) {
                val s = blockAt[succ.next()]!!
                when (state[s]) {
                    null -> {
                        state[s] = 1
                        stack.addLast(s to successors(s.pc).iterator())
                    }
                    1 -> s.loopHeader = true
                }
            } else {
                stack.removeLast()
                state[b] = 2
                post.add(b)
            }
        }
        post.reverse()
        return post.mapIndexed { i, b -> Block(i + 1, b.pc).also { nb -> nb.loopHeader = b.loopHeader; blockAt[b.pc] = nb } }
    }

    private fun goto(from: Block, to: Block) {
        add(from, Inst(Ir.GOTO, NO_VALUES, 0, 0, 0, -1, -1)).targets = listOf(to)
    }

    private fun add(b: Block, i: Inst): Inst {
        i.block = b
        b.insts.add(i)
        return i
    }

    // ------------------------------------------------------------------ instructions

    private fun undefined() = Const(Undefined)

    private fun buildBlock(b: Block) {
        val regs: Array<Value?>
        val stack = ArrayList<Value>()
        val preds = b.preds
        // the state of its one predecessor (built before it), or a phi per register and stack slot
        val built = preds.firstOrNull { exits.containsKey(it) } ?: throw JitBailout("block B${b.id} has no predecessor built before it")
        val depth = exitDepth[built]!!
        if (preds.size == 1) {
            val e = exits[built]!!
            regs = Array(nregs) { e[it] }
            for (i in 0 until depth) stack.add(e[nregs + i])
        } else {
            regs = arrayOfNulls(nregs)
            for (r in 0 until nregs) regs[r] = Phi(b).also { b.phis.add(it) }
            for (i in 0 until depth) stack.add(Phi(b).also { b.phis.add(it) })
        }
        if (b.loopHeader) {
            b.entryRegs = Array(nregs) { regs[it]!! }
            b.entryDepth = stack.size
        }
        if (stressExits && (b.pc == 0 || b.loopHeader)) {
            val e = add(b, Inst(Ir.STRESS_EXIT, NO_VALUES, 0, 0, 0, b.pc, stmtOf[b.pc]))
            e.state = FrameState(b.pc, Array(nregs + stack.size) { if (it < nregs) regs[it]!! else stack[it - nregs] })
        }
        var pc = b.pc
        while (true) {
            val op = code[pc]
            val len = Op.length(code, pc)
            val a = if (Op.operands[op] >= 1) code[pc + 1] else 0
            val bb = if (Op.operands[op] >= 2) code[pc + 2] else 0
            val c = if (Op.operands[op] >= 3) code[pc + 3] else 0
            val stmt = stmtOf[pc]
            fun pop(): Value = if (stack.isEmpty()) throw JitBailout("operand stack underflow at $pc") else stack.removeAt(stack.size - 1)
            fun permute(n: Int, order: IntArray) {
                if (stack.size < n) throw JitBailout("operand stack underflow at $pc")
                val top = ArrayList(stack.subList(stack.size - n, stack.size))
                repeat(n) { stack.removeAt(stack.size - 1) }
                for (k in order) stack.add(top[k])
            }
            fun inst(op: Int, inputs: Array<Value>, result: Boolean): Inst {
                val i = add(b, Inst(op, inputs, a, bb, c, pc, stmt))
                i.hasResult = result
                if (result) stack.add(i)
                return i
            }
            when (op) {
                Op.NOP -> {}
                Op.PUSH_UNDEF -> stack.add(undefined())
                Op.PUSH_NULL -> stack.add(Const(Null))
                Op.PUSH_TRUE -> stack.add(Const(true))
                Op.PUSH_FALSE -> stack.add(Const(false))
                Op.PUSH_INT -> stack.add(Const(a.toDouble()))
                Op.PUSH_CONST -> stack.add(Const(cb.constants[a], a))
                Op.POP -> pop()
                Op.DUP -> stack.add(stack.lastOrNull() ?: throw JitBailout("operand stack underflow at $pc"))
                Op.DUP2 -> permute(2, intArrayOf(0, 1, 0, 1))
                Op.DUP3 -> permute(3, intArrayOf(0, 1, 2, 0, 1, 2))
                Op.SWAP -> permute(2, intArrayOf(1, 0))
                Op.ROT3 -> permute(3, intArrayOf(2, 0, 1))
                Op.ROT4 -> permute(4, intArrayOf(3, 0, 1, 2))
                Op.LOAD_REG -> stack.add(regs[a]!!)
                Op.STORE_REG -> regs[a] = pop()
                Op.LOAD_REG_TDZ -> stack.add(add(b, Inst(Ir.CHECK_TDZ, arrayOf(regs[a]!!), bb, 0, 0, pc, stmt).also { it.hasResult = true }))
                Op.CHECK_REG_TDZ -> add(b, Inst(Ir.CHECK_TDZ, arrayOf(regs[a]!!), bb, 0, 0, pc, stmt))
                Op.TO_KEY_FOR_BASE -> {
                    // (obj key -- obj key')
                    val key = pop()
                    val obj = pop()
                    stack.add(obj)
                    inst(op, arrayOf(obj, key), result = true)
                }
                Op.SET_FUNCTION_NAME -> {
                    // (key fn -- key fn)
                    if (stack.size < 2) throw JitBailout("operand stack underflow at $pc")
                    inst(op, arrayOf(stack[stack.size - 2], stack[stack.size - 1]), result = false)
                }
                Op.JUMP -> {
                    end(b, regs, stack)
                    goto(b, blockAt[a]!!)
                    return
                }
                Op.JUMP_IF_TRUE, Op.JUMP_IF_FALSE, Op.JUMP_IF_NULLISH, Op.JUMP_IF_NOT_NULLISH, Op.JUMP_IF_UNDEFINED,
                Op.JUMP_IF_NOT_UNDEFINED -> {
                    val cond = pop()
                    end(b, regs, stack)
                    if (a == pc + len) {
                        goto(b, blockAt[a]!!)
                        return
                    }
                    val i = add(b, Inst(Ir.BRANCH, arrayOf(cond), op, 0, 0, pc, stmt))
                    i.targets = listOf(blockAt[a]!!, blockAt[pc + len]!!)
                    return
                }
                Op.RETURN -> {
                    add(b, Inst(Ir.RETURN, arrayOf(pop()), 0, 0, 0, pc, stmt))
                    end(b, regs, stack)
                    return
                }
                Op.THROW -> {
                    add(b, Inst(Ir.THROW, arrayOf(pop()), 0, 0, 0, pc, stmt))
                    end(b, regs, stack)
                    return
                }
                Op.THROW_ERROR -> {
                    add(b, Inst(Ir.THROW_ERROR, NO_VALUES, a, bb, 0, pc, stmt))
                    end(b, regs, stack)
                    return
                }
                else -> {
                    val n = arity(op, pc)
                    if (stack.size < n) throw JitBailout("operand stack underflow at $pc")
                    val inputs = Array(n) { stack[stack.size - n + it] }
                    repeat(n) { stack.removeAt(stack.size - 1) }
                    inst(op, inputs, result = pushes(op))
                }
            }
            pc += len
            val nb = blockAt[pc]
            if (nb != null) {
                end(b, regs, stack)
                goto(b, nb)
                return
            }
        }
    }

    private fun end(b: Block, regs: Array<Value?>, stack: List<Value>) {
        exits[b] = Array(nregs + stack.size) { if (it < nregs) regs[it]!! else stack[it - nregs] }
        exitDepth[b] = stack.size
    }

    private fun fillPhis(b: Block) {
        if (b.phis.isEmpty()) return
        val depth = b.phis.size - nregs
        for (p in b.preds) {
            val e = exits[p] ?: throw JitBailout("unbuilt predecessor of B${b.id}")
            if (e.size != nregs + depth) throw JitBailout("operand stack depth differs at ${b.pc}")
            for ((k, phi) in b.phis.withIndex()) phi.inputs.add(e[k])
        }
    }

    // ------------------------------------------------------------------ cleanup

    /** Phis replaced by the one value they merge. */
    private val replaced = HashMap<Value, Value>()

    private fun resolve(v: Value): Value {
        var x = v
        while (true) x = replaced[x] ?: return x
    }

    private fun removeTrivialPhis() {
        var changed = true
        while (changed) {
            changed = false
            for (b in g.blocks) for (p in b.phis) {
                if (replaced.containsKey(p)) continue
                var same: Value? = null
                var trivial = true
                for (i in p.inputs) {
                    val v = resolve(i)
                    if (v === p || v === same) continue
                    if (same != null) {
                        trivial = false
                        break
                    }
                    same = v
                }
                if (trivial) {
                    replaced[p] = same ?: undefined() // a phi of only itself: a loop never entered
                    changed = true
                }
            }
        }
        for (b in g.blocks) {
            b.phis.removeAll { replaced.containsKey(it) }
            for (p in b.phis) for (k in p.inputs.indices) p.inputs[k] = resolve(p.inputs[k])
            for (i in b.insts) {
                for (k in i.inputs.indices) i.inputs[k] = resolve(i.inputs[k])
                i.state?.values?.let { s -> for (k in s.indices) s[k] = resolve(s[k]) }
            }
            b.entryRegs?.let { e -> for (k in e.indices) e[k] = resolve(e[k]) }
        }
    }

    private fun removeDeadPhis() {
        val live = HashSet<Phi>()
        val work = ArrayDeque<Phi>()
        for (b in g.blocks) for (i in b.insts) {
            for (v in i.inputs) if (v is Phi && live.add(v)) work.add(v)
            i.state?.values?.forEach { v -> if (v is Phi && live.add(v)) work.add(v) }
        }
        while (work.isNotEmpty()) {
            val p = work.removeLast()
            for (v in p.inputs) if (v is Phi && live.add(v)) work.add(v)
        }
        for (b in g.blocks) b.phis.retainAll(live)
    }

    private fun number() {
        var n = 0
        for (b in g.blocks) {
            for (p in b.phis) p.id = n++
            for (i in b.insts) if (i.hasResult) i.id = n++
        }
    }

    companion object {
        private val NO_VALUES = arrayOf<Value>()

        fun isJumpOp(op: Int) = op == Op.JUMP || op == Op.JUMP_IF_TRUE || op == Op.JUMP_IF_FALSE || op == Op.JUMP_IF_NULLISH ||
            op == Op.JUMP_IF_NOT_NULLISH || op == Op.JUMP_IF_UNDEFINED || op == Op.JUMP_IF_NOT_UNDEFINED

        /** Whether bytecode instruction [op] (one [arity] knows) leaves a result on the stack. */
        fun pushes(op: Int): Boolean = when (op) {
            Op.STORE_ENV, Op.CHECK_ENV_TDZ, Op.STORE_NAME, Op.STORE_NAME_VAR, Op.INIT_NAME, Op.STORE_GLOBAL,
            Op.INIT_GLOBAL_LEX, Op.INIT_THIS_ENV, Op.INIT_THIS_NAME, Op.PUSH_SCOPE, Op.POP_SCOPE, Op.COPY_SCOPE, Op.SET_ENV,
            Op.PUSH_WITH, Op.ADD_FIELD, Op.ADD_PRIVATE_METHOD, Op.STATIC_PRIVATE_METHOD, Op.RUN_FIELD, Op.DEBUGGER,
            Op.DECLARE_GLOBALS, Op.DECLARE_EVAL, Op.ADD_DISPOSABLE, Op.DISPOSE_SYNC, Op.CLASS_ELEMENT, Op.CLASS_FINISH,
            Op.CLASS_STATIC_INIT -> false
            else -> true
        }

        /**
         * The number of stack values bytecode instruction [op] at [pc] of [code] takes as inputs, for those the IR has as
         * instructions of their own (a helper call, JvmEmitter.emitHelper, or a specialized form); -1 for the others.
         * Each pushes at most one value ([pushes]).
         */
        fun arity(code: IntArray, op: Int, pc: Int): Int {
            val a = if (Op.operands[op] >= 1) code[pc + 1] else 0
            val b = if (Op.operands[op] >= 2) code[pc + 2] else 0
            return when (op) {
                Op.NOP, Op.PUSH_UNDEF, Op.PUSH_NULL, Op.PUSH_TRUE, Op.PUSH_FALSE, Op.PUSH_INT, Op.PUSH_CONST, Op.POP, Op.DUP,
                Op.DUP2, Op.DUP3, Op.SWAP, Op.ROT3, Op.ROT4, Op.LOAD_REG, Op.STORE_REG, Op.LOAD_REG_TDZ, Op.CHECK_REG_TDZ,
                Op.TO_KEY_FOR_BASE, Op.SET_FUNCTION_NAME -> 0 // built by IrBuilder itself
                Op.LOAD_ENV, Op.LOAD_ENV_TDZ, Op.CHECK_ENV_TDZ, Op.LOAD_IMPORT, Op.LOAD_NAME, Op.LOAD_NAME_TYPEOF,
                Op.DELETE_NAME, Op.RESOLVE_NAME, Op.LOAD_GLOBAL, Op.LOAD_GLOBAL_TYPEOF, Op.LOAD_THIS, Op.LOAD_FUNCTION,
                Op.LOAD_NEW_TARGET, Op.LOAD_HOME, Op.CREATE_ARGUMENTS, Op.CREATE_REST, Op.LOAD_ARG, Op.NEW_OBJECT,
                Op.NEW_ARRAY, Op.MAKE_CLOSURE, Op.NEW_PRIVATE_NAME, Op.TEMPLATE_OBJECT, Op.NEW_REGEXP, Op.IMPORT_META,
                Op.DISPOSE_NEW, Op.PUSH_SCOPE, Op.POP_SCOPE, Op.COPY_SCOPE, Op.GET_ENV, Op.DEBUGGER -> 0
                Op.STORE_ENV, Op.STORE_NAME, Op.STORE_NAME_VAR, Op.INIT_NAME, Op.STORE_GLOBAL, Op.INIT_GLOBAL_LEX,
                Op.INIT_THIS_ENV, Op.INIT_THIS_NAME, Op.SET_ENV, Op.PUSH_WITH, Op.GET_REF, Op.GET_PROP, Op.DELETE_PROP,
                Op.TO_PROPERTY_KEY, Op.GET_SUPER_BASE, Op.NEG, Op.TO_NUMBER, Op.NOT, Op.BNOT, Op.TYPEOF, Op.TO_NUMERIC,
                Op.INC, Op.DEC, Op.TO_STRING, Op.TO_OBJECT, Op.REQUIRE_COERCIBLE, Op.CHECK_OBJECT, Op.GET_PROTO_OF,
                Op.ARRAY_HOLE, Op.GET_ITERATOR, Op.FOR_IN_START, Op.CLASS_FINISH, Op.CLASS_STATIC_INIT -> 1
                Op.PUT_REF, Op.PUT_PROP, Op.GET_ELEM, Op.DELETE_ELEM, Op.GET_PRIVATE, Op.HAS_PRIVATE, Op.ADD, Op.SUB, Op.MUL,
                Op.DIV, Op.MOD, Op.EXP, Op.SHL, Op.SAR, Op.SHR, Op.BAND, Op.BOR, Op.BXOR, Op.EQ, Op.NE, Op.SEQ, Op.SNE,
                Op.LT, Op.GT, Op.LE, Op.GE, Op.INSTANCEOF, Op.IN, Op.CONCAT, Op.NEW_SPREAD, Op.INIT_INSTANCE, Op.ARRAY_PUSH,
                Op.ARRAY_SPREAD, Op.DEFINE_FIELD, Op.COPY_DATA_PROPS, Op.SET_PROTO, Op.DYNAMIC_IMPORT,
                Op.DYNAMIC_IMPORT_PHASE, Op.ADD_DISPOSABLE, Op.CLASS_DEF_NEW, Op.CLASS_DECORATE, Op.DERIVED_RETURN -> 2
                Op.GET_SUPER, Op.PUT_ELEM, Op.PUT_PRIVATE, Op.CALL_SPREAD, Op.SUPER_CALL_SPREAD, Op.DEFINE_FIELD_ELEM,
                Op.DEFINE_GETTER, Op.DEFINE_SETTER, Op.DEFINE_METHOD, Op.ADD_FIELD, Op.ADD_PRIVATE_METHOD,
                Op.STATIC_PRIVATE_METHOD, Op.RUN_FIELD, Op.DISPOSE_SYNC, Op.CALL_EVAL_SPREAD -> 3
                Op.PUT_SUPER, Op.CLASS_ELEMENT -> 4
                Op.CALL, Op.TAIL_CALL, Op.CALL_EVAL, Op.SUPER_CALL -> a + 2
                Op.NEW -> a + 1
                Op.COPY_DATA_PROPS_EXCL -> a + 2
                Op.NEW_OBJECT_LITERAL -> b
                Op.DECLARE_GLOBALS, Op.DECLARE_EVAL -> b
                else -> -1
            }
        }
    }

    private fun arity(op: Int, pc: Int) = arity(code, op, pc)
}
