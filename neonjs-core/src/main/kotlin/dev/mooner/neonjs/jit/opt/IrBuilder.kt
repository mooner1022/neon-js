package dev.mooner.neonjs.jit.opt

import dev.mooner.neonjs.compiler.CodeBlock
import dev.mooner.neonjs.compiler.Op
import dev.mooner.neonjs.jit.Inlining
import dev.mooner.neonjs.jit.JT
import dev.mooner.neonjs.jit.JitBailout
import dev.mooner.neonjs.compiler.ValueProfile
import dev.mooner.neonjs.runtime.Null
import dev.mooner.neonjs.runtime.PropCache
import dev.mooner.neonjs.runtime.PropIC
import dev.mooner.neonjs.runtime.PropSite
import dev.mooner.neonjs.runtime.Undefined

/**
 * Builds the [Graph] of a code block from its bytecode: registers and operand stack slots become SSA values, with a phi
 * at every merge for every one of them, then the phis that merge one value (or only themselves) are replaced by it and
 * those nothing uses are dropped. Kinds come from [Kinds], as the present compiler's type analysis gives them.
 *
 * Calls whose callee the interpreter's feedback or the compiler's static targets name (Inlining.plan's sources), and
 * that Inlining.eligible accepts, have the callee's body built in place ([Body.inline]) behind a guard that exits to the
 * interpreter, before the CALL, when the function called is not a closure of that code block in the frame's realm.
 * The callee's registers are values of the graph like the caller's, so arguments and results stay unboxed.
 *
 * Code blocks with exception handlers, and instructions the IR does not have yet ([arity] -1), are not built
 * ([JitBailout]): such blocks keep their baseline code.
 */
internal class IrBuilder(
    private val cb: CodeBlock,
    private val ints: Boolean,
    private val elems: Boolean,
    /** Whether the code gets the deopt stress mode's exits: at the start and the loop headers of each body. */
    private val stressExits: Boolean = false,
    /** Calls compiled inlined into one another at most (0: none). */
    private val maxInlineDepth: Int = if (Inlining.ENABLED) MAX_INLINE_DEPTH else 0,
    /** Whether the code speculates on the interpreter's value profiles and the property caches (T2.SPECULATE). */
    private val speculate: Boolean = T2.SPECULATE,
) {
    private val g = Graph(cb)
    /** Every block made, in the order made; the graph keeps those reachable, in reverse post order. */
    private val made = ArrayList<Block>()
    /** Bytecode ints of the bodies inlined so far (a budget). */
    private var inlined = 0

    fun build(): Graph {
        if (cb.isGenerator || cb.isAsync) throw JitBailout("generator or async")
        val start = newBlock(-1, null)
        val root = Body(cb, null)
        val regs = Array<Value>(cb.numRegs) { undefined() }
        cb.paramRegs?.forEachIndexed { i, r ->
            if (r >= 0) regs[r] = add(start, Inst(Ir.PARAM, NO_VALUES, i, 0, 0, -1, -1).also { it.hasResult = true })
        }
        // parameters that only ever received numbers are taken as numbers, leaving at the start otherwise
        val params = cb.profile?.params
        // (not where such a check failed before: CodeBlock.t2Failed, the parameters' bit)
        if (speculate && params != null && !cb.t2FailedAt(cb.code.size)) {
            val entry = FrameState(0, regs.copyOf())
            cb.paramRegs?.forEachIndexed { i, r ->
                if (r >= 0 && i < params.size) numberCheck(params[i].toInt() and 0xFF)?.let { op ->
                    val check = add(start, Inst(op, arrayOf(regs[r]), 0, 0, 0, -1, -1).also { it.hasResult = true })
                    check.state = entry
                    regs[r] = check
                }
            }
        }
        root.build(start, regs)
        order(start)
        removeTrivialPhis()
        removeDeadPhis()
        val kinds = Kinds(ints, elems)
        kinds.infer(g)
        if (preferDoubles()) kinds.infer(g)
        number()
        return g
    }

    /**
     * A value checked as an int32 (CHECK_INT32) is held as an int, which pays only where it is used as one: a bitwise
     * operand, an element key, a comparison with another int, an int phi. Where it is only used as a double, it would be
     * widened at every use (in a loop, every iteration), so it is checked as a number instead, once. Returns whether a
     * check changed (kinds are inferred again then).
     */
    private fun preferDoubles(): Boolean {
        val asInt = HashSet<Value>()
        for (b in g.blocks) {
            for (p in b.phis) if (p.kind == JT.INT) asInt.addAll(p.inputs)
            for (i in b.insts) when {
                JT.isBitwise(i.op) || i.op == Op.BNOT -> asInt.addAll(i.inputs)
                JT.isCompare(i.op) -> if (i.inputs[0].kind == JT.INT && i.inputs[1].kind == JT.INT) asInt.addAll(i.inputs)
                i.op == Op.GET_ELEM || i.op == Op.PUT_ELEM -> asInt.add(i.inputs[1])
            }
        }
        var changed = false
        for (b in g.blocks) for (i in b.insts) if (i.op == Ir.CHECK_INT32 && i !in asInt) {
            i.op = Ir.CHECK_NUMBER
            changed = true
        }
        return changed
    }

    private fun newBlock(pc: Int, site: InlineSite?): Block = Block(-1, pc, site).also { made.add(it) }

    private fun goto(from: Block, to: Block) {
        add(from, Inst(Ir.GOTO, NO_VALUES, 0, 0, 0, -1, -1)).targets = listOf(to)
    }

    private fun add(b: Block, i: Inst): Inst {
        i.block = b
        b.insts.add(i)
        return i
    }

    private fun undefined() = Const(Undefined)

    /**
     * The code of one function in the graph: the function compiled ([site] null) or the body of a call compiled inlined.
     * Its bytecode blocks become blocks (an inlined call splits one: the instructions after the call continue in the
     * block where the callee's returns meet).
     */
    private inner class Body(val code: CodeBlock, val site: InlineSite?, /** For an inlined body: the handler around its call. */ val outerHandler: Block? = null) {
        val bc = code.code
        val nregs = code.numRegs
        /** The block of each bytecode block, by its first pc. */
        val blockAt = HashMap<Int, Block>()
        /** Bytecode blocks in reverse post order. */
        lateinit var order: List<Block>
        /** The state at the end of each bytecode block built (registers, then the stack), and of the block entering the body. */
        val stateOut = HashMap<Block, Array<Value>>()
        val depthOut = HashMap<Block, Int>()
        /** The last block of each bytecode block (itself unless a call inlined in it split it). */
        val tail = HashMap<Block, Block>()
        val stmtOf = IntArray(bc.size)
        /** For an inlined body: the block its returns jump to, and the values they return, by predecessor of it. */
        var join: Block? = null
        val returned = ArrayList<Value>()
        /** The state on the edge to [Pair.first] where it differs from [stateOut] (ITER_STEP's: without the value). */
        val edgeOut = HashMap<Block, Pair<Block, Array<Value>>>()
        /** The exception entry of each handler, by its pc; the registers some handler may read (kept in homes). */
        val catchEntries = HashMap<Int, Block>()
        var homeRegs = IntArray(0)

        /** Builds the body entered from [from] (which jumps to its first block) with registers [regs]. */
        fun build(from: Block, regs: Array<Value>) {
            if (site != null && code.handlers.isNotEmpty()) throw JitBailout("exception handlers in an inlined body")
            scan()
            val h = code.handlers
            for (k in h.indices step 4) catchEntries.getOrPut(h[k + 2]) { newBlock(h[k + 2], site).also { it.catchEntry = true } }
            // the code reached from the start, then the handlers (reached by exceptions only)
            order = reversePostOrder(listOf(blockAt[0]!!) + catchEntries.keys.sorted().map { blockAt[it]!! })
            val first = blockAt[0]!!
            // every predecessor, before any block is built: a block with more than one gets phis
            first.preds.add(from)
            for (b in order) for (s in successors(b.pc)) blockAt[s]!!.preds.add(b)
            for ((pc, e) in catchEntries) blockAt[pc]!!.preds.add(e)
            for (b in order) b.handler = coverOf(b.pc) ?: outerHandler
            if (catchEntries.isNotEmpty()) homeRegs = homes()
            goto(from, first)
            stateOut[from] = regs
            depthOut[from] = 0
            for ((pc, e) in catchEntries) buildCatchEntry(e, pc)
            for (b in order) buildBlock(b)
            for (b in order) fillPhis(b)
            // the edges out of a bytecode block leave from its last block
            for (b in order) for (k in b.preds.indices) b.preds[k] = tail[b.preds[k]] ?: b.preds[k]
        }

        /** The exception entry of the innermost handler whose range covers [pc] (handlers list inner ones first). */
        private fun coverOf(pc: Int): Block? {
            val h = code.handlers
            for (k in h.indices step 4) if (pc >= h[k] && pc < h[k + 1]) return catchEntries[h[k + 2]]
            return null
        }

        /**
         * A handler's entry: the exception's value on the stack (the JVM empties it, as T1's code does: handlers whose
         * code needs values below it are not built) and the registers the handler may read from their homes.
         */
        private fun buildCatchEntry(e: Block, pc: Int) {
            val caught = add(e, Inst(Ir.CATCH_VALUE, NO_VALUES, 0, 0, 0, pc, stmtOf[pc]).also { it.hasResult = true })
            val live = liveIn[pc]
            val regs = Array<Value>(nregs) { r ->
                if (live[r]) add(e, Inst(Ir.LOAD_HOME, NO_VALUES, r, 0, 0, pc, -1).also { it.hasResult = true }) else undefined()
            }
            goto(e, blockAt[pc]!!)
            stateOut[e] = arrayOf(*regs, caught)
            depthOut[e] = 1
        }

        /** Registers live before each instruction, exceptions included (computed by [homes]). */
        lateinit var liveIn: Array<BooleanArray>

        /** The registers live at some handler's start: those kept in homes. */
        private fun homes(): IntArray {
            val pcs = ArrayList<Int>()
            var p = 0
            while (p < bc.size) {
                pcs.add(p)
                p += Op.length(bc, p)
            }
            liveIn = Array(bc.size) { BooleanArray(nregs) }
            var changed = true
            while (changed) {
                changed = false
                for (pc in pcs.asReversed()) {
                    val op = bc[pc]
                    val live = BooleanArray(nregs)
                    for (s in instSuccessors(pc)) {
                        val l = liveIn[s]
                        for (r in 0 until nregs) if (l[r]) live[r] = true
                    }
                    val (use, def) = regUse(pc, op)
                    if (def >= 0) live[def] = false
                    if (use >= 0) live[use] = true
                    // an exception before the instruction ends goes to the handler with the registers as they are
                    coverOf(pc)?.let { _ -> handlerOf(pc)?.let { hp -> val l = liveIn[hp]; for (r in 0 until nregs) if (l[r]) live[r] = true } }
                    if (!live.contentEquals(liveIn[pc])) {
                        liveIn[pc] = live
                        changed = true
                    }
                }
            }
            val out = ArrayList<Int>()
            for (r in 0 until nregs) if (catchEntries.keys.any { liveIn[it][r] }) out.add(r)
            return out.toIntArray()
        }

        private fun handlerOf(pc: Int): Int? {
            val h = code.handlers
            for (k in h.indices step 4) if (pc >= h[k] && pc < h[k + 1]) return h[k + 2]
            return null
        }

        /** The pcs control goes to after the instruction at [pc] (exceptions aside). */
        private fun instSuccessors(pc: Int): List<Int> {
            val op = bc[pc]
            val next = pc + Op.length(bc, pc)
            return when {
                op == Op.JUMP -> listOf(bc[pc + 1])
                isJumpOp(op) -> listOf(bc[pc + 1], next)
                op == Op.ITER_STEP || op == Op.FOR_IN_NEXT -> listOf(bc[pc + 2], next)
                op == Op.JUMP_TABLE -> List(bc[pc + 2] + 1) { bc[pc + 3 + it] }
                op == Op.RETURN || op == Op.THROW || op == Op.THROW_ERROR -> emptyList()
                next < bc.size -> listOf(next)
                else -> emptyList()
            }
        }

        /** The register the instruction at [pc] reads (or -1), and the one it writes (or -1). */
        private fun regUse(pc: Int, op: Int): Pair<Int, Int> = when (op) {
            Op.LOAD_REG, Op.LOAD_REG_TDZ, Op.CHECK_REG_TDZ, Op.ITER_STEP, Op.ITER_STEP_U, Op.ITER_REST, Op.ITER_CLOSE,
            Op.ITER_CLOSE_THROW, Op.FOR_IN_NEXT, Op.JUMP_TABLE -> bc[pc + 1] to -1
            Op.MAKE_METHOD -> bc[pc + 2] to -1
            Op.STORE_REG -> -1 to bc[pc + 1]
            else -> -1 to -1
        }

        private fun scan() {
            val leader = BooleanArray(bc.size + 1)
            leader[0] = true
            var pc = 0
            while (pc < bc.size) {
                val op = bc[pc]
                val len = Op.length(bc, pc)
                if (!isJumpOp(op) && op != Op.RETURN && op != Op.THROW && op != Op.THROW_ERROR && arity(bc, op, pc) < 0 &&
                    op !in BUILT_OPS) {
                    throw JitBailout("unsupported op ${Op.names[op]}")
                }
                if (isJumpOp(op)) {
                    leader[bc[pc + 1]] = true
                    leader[pc + len] = true
                } else if (op == Op.ITER_STEP || op == Op.FOR_IN_NEXT) {
                    leader[bc[pc + 2]] = true
                    leader[pc + len] = true
                } else if (op == Op.JUMP_TABLE) {
                    for (k in 0..bc[pc + 2]) leader[bc[pc + 3 + k]] = true
                    leader[pc + len] = true
                } else if (op == Op.RETURN || op == Op.THROW || op == Op.THROW_ERROR) leader[pc + len] = true
                pc += len
            }
            // a handler's range starts and ends blocks, so that each block is in a range or not
            val h = code.handlers
            for (k in h.indices step 4) {
                leader[h[k]] = true
                leader[h[k + 1]] = true
                leader[h[k + 2]] = true
            }
            for (p in 0 until bc.size) if (leader[p] && isInstStart(p)) blockAt[p] = Block(-1, p, site)
            val lt = code.lineTable
            var stmt = -1
            var k = 0
            for (p in bc.indices) {
                while (k < lt.size && lt[k] <= p) {
                    stmt = lt[k]
                    k += 2
                }
                stmtOf[p] = stmt
            }
        }

        /**
         * The successors of the block starting at [start], from its last instruction: a conditional jump's target, then
         * the next instruction. A conditional jump to the next instruction is one edge (built as a GOTO: ToBoolean and
         * the nullish tests have no effect).
         */
        fun successors(start: Int): List<Int> {
            var pc = start
            while (true) {
                val op = bc[pc]
                val next = pc + Op.length(bc, pc)
                when {
                    op == Op.JUMP -> return listOf(bc[pc + 1])
                    isJumpOp(op) -> return if (bc[pc + 1] == next) listOf(next) else listOf(bc[pc + 1], next)
                    op == Op.ITER_STEP || op == Op.FOR_IN_NEXT -> return listOf(bc[pc + 2], next)
                    op == Op.JUMP_TABLE -> return List(bc[pc + 2] + 1) { bc[pc + 3 + it] }
                    op == Op.RETURN || op == Op.THROW || op == Op.THROW_ERROR -> return emptyList()
                }
                if (next >= bc.size) throw JitBailout("control flow leaves the code")
                if (blockAt.containsKey(next)) return listOf(next)
                pc = next
            }
        }

        private fun reversePostOrder(roots: List<Block>): List<Block> {
            val post = ArrayList<Block>()
            val state = HashMap<Block, Int>() // 1 on the DFS stack, 2 done
            val stack = ArrayDeque<Pair<Block, Iterator<Int>>>()
            for (first in roots.asReversed()) {
            if (state.containsKey(first)) continue
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
            }
            post.reverse()
            for (b in post) made.add(b)
            return post
        }

        private fun isInstStart(p: Int): Boolean {
            var pc = 0
            while (pc < p) pc += Op.length(bc, pc)
            return pc == p
        }

        private fun buildBlock(b: Block) {
            val regs: Array<Value?>
            val stack = ArrayList<Value>()
            val preds = b.preds
            // the state of its one predecessor (built before it), or a phi per register and stack slot
            val built = preds.firstOrNull { stateOut.containsKey(it) } ?: throw JitBailout("block at ${b.pc} has no predecessor built before it")
            val depth = stateOn(built, b).size - nregs
            if (preds.size == 1) {
                val e = stateOn(built, b)
                regs = Array(nregs) { e[it] }
                for (i in 0 until depth) stack.add(e[nregs + i])
            } else {
                regs = arrayOfNulls(nregs)
                for (r in 0 until nregs) regs[r] = Phi(b).also { b.phis.add(it) }
                for (i in 0 until depth) stack.add(Phi(b).also { b.phis.add(it) })
            }
            if (b.loopHeader && site == null) {
                // where on-stack replacement may enter
                b.entryRegs = Array(nregs) { regs[it]!! }
                b.entryDepth = stack.size
            }
            var cur = b
            fun state(pc: Int) = FrameState(pc, Array(nregs + stack.size) { if (it < nregs) regs[it]!! else stack[it - nregs] }, site)
            // in a handler's range: the registers it may read where it finds them
            val homed = site == null && b.handler != null && homeRegs.isNotEmpty()
            fun home(r: Int) {
                if (homed && r in homeRegs) add(cur, Inst(Ir.SET_HOME, arrayOf(regs[r]!!), r, 0, 0, -1, -1))
            }
            if (homed) for (r in homeRegs) home(r)
            if (stressExits && (b.pc == 0 || b.loopHeader)) {
                add(cur, Inst(Ir.STRESS_EXIT, NO_VALUES, 0, 0, 0, b.pc, stmtOf[b.pc])).state = state(b.pc)
            }
            fun end() {
                tail[b] = cur
                stateOut[b] = Array(nregs + stack.size) { if (it < nregs) regs[it]!! else stack[it - nregs] }
                depthOut[b] = stack.size
            }
            var pc = b.pc
            while (true) {
                val op = bc[pc]
                val len = Op.length(bc, pc)
                val a = if (Op.operands[op] >= 1) bc[pc + 1] else 0
                val bb = if (Op.operands[op] >= 2) bc[pc + 2] else 0
                val c = if (Op.operands[op] >= 3) bc[pc + 3] else 0
                val stmt = stmtOf[pc]
                fun pop(): Value = if (stack.isEmpty()) throw JitBailout("operand stack underflow at $pc") else stack.removeAt(stack.size - 1)
                fun permute(n: Int, order: IntArray) {
                    if (stack.size < n) throw JitBailout("operand stack underflow at $pc")
                    val top = ArrayList(stack.subList(stack.size - n, stack.size))
                    repeat(n) { stack.removeAt(stack.size - 1) }
                    for (k in order) stack.add(top[k])
                }
                fun inst(op: Int, inputs: Array<Value>, result: Boolean): Inst {
                    val i = add(cur, Inst(op, inputs, a, bb, c, pc, stmt))
                    i.hasResult = result
                    if (result) stack.add(i)
                    return i
                }
                /** An exit to the interpreter before this instruction, with its operands on the stack. */
                fun before(i: Inst): Inst {
                    i.state = state(pc)
                    return i
                }
                when (op) {
                    Op.NOP -> {}
                    Op.PUSH_UNDEF -> stack.add(undefined())
                    Op.PUSH_NULL -> stack.add(Const(Null))
                    Op.PUSH_TRUE -> stack.add(Const(true))
                    Op.PUSH_FALSE -> stack.add(Const(false))
                    Op.PUSH_INT -> stack.add(Const(a.toDouble()))
                    Op.PUSH_CONST -> stack.add(Const(code.constants[a], a, code))
                    Op.POP -> pop()
                    Op.DUP -> stack.add(stack.lastOrNull() ?: throw JitBailout("operand stack underflow at $pc"))
                    Op.DUP2 -> permute(2, intArrayOf(0, 1, 0, 1))
                    Op.DUP3 -> permute(3, intArrayOf(0, 1, 2, 0, 1, 2))
                    Op.SWAP -> permute(2, intArrayOf(1, 0))
                    Op.ROT3 -> permute(3, intArrayOf(2, 0, 1))
                    Op.ROT4 -> permute(4, intArrayOf(3, 0, 1, 2))
                    Op.LOAD_REG -> stack.add(regs[a]!!)
                    Op.STORE_REG -> {
                        regs[a] = pop()
                        home(a)
                    }
                    Op.LOAD_REG_TDZ -> stack.add(add(cur, Inst(Ir.CHECK_TDZ, arrayOf(regs[a]!!), bb, 0, 0, pc, stmt).also { it.hasResult = true }))
                    Op.CHECK_REG_TDZ -> add(cur, Inst(Ir.CHECK_TDZ, arrayOf(regs[a]!!), bb, 0, 0, pc, stmt))
                    // an inlined body's `this` was bound when it was entered
                    Op.LOAD_THIS -> if (site != null) stack.add(site.thisValue) else inst(op, NO_VALUES, result = true)
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
                        end()
                        goto(cur, blockAt[a]!!)
                        return
                    }
                    Op.ITER_STEP, Op.FOR_IN_NEXT -> {
                        // the next value (ITER_STEP: NotFound when done; FOR_IN_NEXT: null), pushed where it goes on
                        val v = add(cur, Inst(op, arrayOf(regs[a]!!), a, bb, 0, pc, stmt).also { it.hasResult = true })
                        val done = blockAt[bb]!!
                        edgeOut[b] = done to Array(nregs + stack.size) { if (it < nregs) regs[it]!! else stack[it - nregs] }
                        stack.add(v)
                        end()
                        add(cur, Inst(Ir.BRANCH, arrayOf(v), op, 0, 0, pc, stmt)).targets = listOf(done, blockAt[pc + len]!!)
                        return
                    }
                    Op.JUMP_TABLE -> {
                        // reg count t0..t(count-1) default: operands the fixed decoding above does not read
                        val reg = bc[pc + 1]
                        val count = bc[pc + 2]
                        end()
                        add(cur, Inst(Ir.SWITCH, arrayOf(regs[reg]!!), 0, 0, 0, pc, stmt)).targets = List(count + 1) { blockAt[bc[pc + 3 + it]]!! }
                        return
                    }
                    Op.ITER_STEP_U, Op.ITER_REST -> inst(op, arrayOf(regs[a]!!), result = true)
                    Op.ITER_CLOSE, Op.ITER_CLOSE_THROW -> inst(op, arrayOf(regs[a]!!), result = false)
                    Op.MAKE_METHOD -> inst(op, arrayOf(regs[bb]!!), result = true)
                    Op.JUMP_IF_TRUE, Op.JUMP_IF_FALSE, Op.JUMP_IF_NULLISH, Op.JUMP_IF_NOT_NULLISH, Op.JUMP_IF_UNDEFINED,
                    Op.JUMP_IF_NOT_UNDEFINED -> {
                        val cond = pop()
                        end()
                        if (a == pc + len) {
                            goto(cur, blockAt[a]!!)
                            return
                        }
                        val i = add(cur, Inst(Ir.BRANCH, arrayOf(cond), op, 0, 0, pc, stmt))
                        i.targets = listOf(blockAt[a]!!, blockAt[pc + len]!!)
                        return
                    }
                    Op.RETURN -> {
                        val v = pop()
                        end()
                        val j = join
                        if (j == null) add(cur, Inst(Ir.RETURN, arrayOf(v), 0, 0, 0, pc, stmt))
                        else {
                            // an inlined body returns to the block after its call
                            goto(cur, j)
                            j.preds.add(cur)
                            returned.add(v)
                        }
                        return
                    }
                    Op.THROW -> {
                        add(cur, Inst(Ir.THROW, arrayOf(pop()), 0, 0, 0, pc, stmt))
                        end()
                        return
                    }
                    Op.THROW_ERROR -> {
                        add(cur, Inst(Ir.THROW_ERROR, NO_VALUES, a, bb, 0, pc, stmt))
                        end()
                        return
                    }
                    Op.GET_PROP -> {
                        val ic = if (code.t2FailedAt(pc)) null else monomorphic(code.constants[a], PropCache.DATA)
                        if (ic == null) inst(op, arrayOf(pop()), result = true)
                        else {
                            // the receiver's shape (and its prototypes', for an inherited property) as the cache saw it,
                            // then the slot; anything else leaves before the instruction
                            val obj = stack.last()
                            val chain = ic.chain
                            val load = before(Inst(Ir.LOAD_SLOT, arrayOf(obj), g.dataIndex(ic.shape), ic.slot,
                                if (chain == null) -1 else g.dataIndex(chain), pc, stmt))
                            pop()
                            add(cur, load).hasResult = true
                            stack.add(load)
                        }
                    }
                    Op.PUT_PROP -> {
                        val ic = if (code.t2FailedAt(pc)) null else monomorphic(code.constants[a], PropCache.STORE)
                        if (ic == null) {
                            val v = pop()
                            inst(op, arrayOf(pop(), v), result = true)
                        } else {
                            // (obj v -- v): a writable own data property of the shape the cache saw
                            val store = before(Inst(Ir.STORE_SLOT, arrayOf(stack[stack.size - 2], stack[stack.size - 1]),
                                g.dataIndex(ic.shape), ic.slot, -1, pc, stmt))
                            val v = pop()
                            pop()
                            add(cur, store)
                            stack.add(v)
                        }
                    }
                    Op.CALL -> {
                        val target = inlineTarget(pc)
                        if (target == null) {
                            val n = a + 2
                            if (stack.size < n) throw JitBailout("operand stack underflow at $pc")
                            val inputs = Array(n) { stack[stack.size - n + it] }
                            repeat(n) { stack.removeAt(stack.size - 1) }
                            inst(op, inputs, result = true)
                        } else {
                            cur = inline(cur, target, pc, a, stmt, regs, stack)
                        }
                    }
                    else -> {
                        val n = arity(bc, op, pc)
                        if (stack.size < n) throw JitBailout("operand stack underflow at $pc")
                        val inputs = Array(n) { stack[stack.size - n + it] }
                        repeat(n) { stack.removeAt(stack.size - 1) }
                        inst(op, inputs, result = pushes(op))
                    }
                }
                // a load whose values were only numbers: a number from here on, or an exit after the load
                if (speculate && (op == Op.GET_PROP || op == Op.GET_ELEM || op == Op.LOAD_ENV || op == Op.CALL) && !code.t2FailedAt(pc)) {
                    val top = stack.lastOrNull()
                    val bits = code.profile?.values?.get(pc)?.toInt()?.and(0xFF) ?: 0
                    if (top is Inst && top.pc == pc && top.block?.site == site) numberCheck(bits)?.let { check ->
                        val i = Inst(check, arrayOf(top), 0, 0, 0, pc, stmt)
                        i.state = state(pc + len)
                        i.hasResult = true
                        add(cur, i)
                        stack[stack.size - 1] = i
                    }
                }
                pc += len
                val nb = blockAt[pc]
                if (nb != null) {
                    end()
                    goto(cur, nb)
                    return
                }
            }
        }

        /** The state on the edge from [p] to [b]. */
        private fun stateOn(p: Block, b: Block): Array<Value> = edgeOut[p]?.takeIf { it.first === b }?.second ?: stateOut[p]!!

        private fun fillPhis(b: Block) {
            if (b.phis.isEmpty()) return
            val depth = b.phis.size - nregs
            for (p in b.preds) {
                if (!stateOut.containsKey(p)) throw JitBailout("unbuilt predecessor of the block at ${b.pc}")
                val e = stateOn(p, b)
                if (e.size != nregs + depth) throw JitBailout("operand stack depth differs at ${b.pc}")
                for ((k, phi) in b.phis.withIndex()) phi.inputs.add(e[k])
            }
        }

        // -------------------------------------------------------------- inlined calls

        /** The code block to compile the CALL at [pc] inlined with, or null. */
        private fun inlineTarget(pc: Int): CodeBlock? {
            val depth = site?.depth ?: 0
            // not where a call made there was another function before (CodeBlock.t2Failed)
            if (depth >= maxInlineDepth || code.t2FailedAt(pc)) return null
            val fb = code.callFeedback
            val target = (if (fb == null) code.callHint(pc) else fb.getOrNull(pc)?.takeIf { it.count >= Inlining.MIN_COUNT }?.target) ?: return null
            // helpers read the strictness from the frame: the function compiled's
            if (!Inlining.eligible(cb, target, MAX_CALLEE_CODE)) return null
            // recursion is inlined once, at the function compiled (as the baseline does): further copies only grow it
            if (depth > 0) {
                if (target === cb) return null
                var s = site
                while (s != null) {
                    if (s.code === target) return null
                    s = s.parent
                }
            }
            if (inlined + target.code.size > INLINE_BUDGET) return null
            return target
        }

        /**
         * The CALL at [pc] of [argc] arguments with [target]'s body built in place, from block [cur], with the caller's
         * [regs] and [stack] (the call's operands on top, its result there after). Returns the block where the body's
         * returns meet, where the caller goes on.
         */
        private fun inline(cur: Block, target: CodeBlock, pc: Int, argc: Int, stmt: Int, regs: Array<Value?>, stack: ArrayList<Value>): Block {
            val n = argc + 2
            if (stack.size < n) throw JitBailout("operand stack underflow at $pc")
            inlined += target.code.size
            T2.inlinedCalls.incrementAndGet()
            val ops = ArrayList(stack.subList(stack.size - n, stack.size))
            val fn = ops[0]
            // the guard: anything else than a closure of the target here leaves before the call, which the
            // interpreter then makes
            val guard = add(cur, Inst(Ir.CHECK_CLOSURE, arrayOf(fn), g.dataIndex(target), 0, 0, pc, stmt))
            guard.state = FrameState(pc, Array(nregs + stack.size) { if (it < nregs) regs[it]!! else stack[it - nregs] }, site)
            repeat(n) { stack.removeAt(stack.size - 1) }
            val callerState = FrameState(pc, Array(nregs + stack.size) { if (it < nregs) regs[it]!! else stack[it - nregs] }, site)
            add(cur, Inst(Ir.INLINE_ENTER, arrayOf(fn), pc, 0, 0, pc, stmt))
            val env = add(cur, Inst(Ir.CLOSURE_ENV, arrayOf(fn), 0, 0, 0, pc, stmt).also { it.hasResult = true })
            val thisValue = if (readsThis(target)) {
                add(cur, Inst(Ir.INLINE_THIS, arrayOf(fn, ops[1]), 0, 0, 0, pc, stmt).also { it.hasResult = true })
            } else undefined()
            val args = Array(argc) { ops[2 + it] }
            val calleeSite = InlineSite(site, target, pc, callerState, fn, thisValue, args, env)
            g.dataIndex(target.constants)
            val body = Body(target, calleeSite, cur.handler)
            val join = newBlock(pc, site)
            join.handler = cur.handler
            body.join = join
            val calleeRegs = Array<Value>(target.numRegs) { undefined() }
            target.paramRegs?.forEachIndexed { i, r -> if (r >= 0 && i < argc) calleeRegs[r] = args[i] }
            body.build(cur, calleeRegs)
            val result: Value = when (body.returned.size) {
                0 -> undefined() // it never returns: the block is not reached
                1 -> body.returned[0]
                else -> Phi(join).also { p -> join.phis.add(p); p.inputs.addAll(body.returned) }
            }
            add(join, Inst(Ir.INLINE_EXIT, NO_VALUES, 0, 0, 0, pc, stmt))
            stack.add(result)
            return join
        }
    }

    /**
     * The check that makes a value of profile kinds [bits] (ValueProfile) a number: CHECK_INT32 when it was always an
     * int32, CHECK_NUMBER when always a number, null when something else was seen or nothing was.
     */
    private fun numberCheck(bits: Int): Int? = when {
        bits == 0 || bits and (ValueProfile.INT or ValueProfile.DOUBLE).inv() != 0 -> null
        bits == ValueProfile.INT && ints -> Ir.CHECK_INT32
        else -> Ir.CHECK_NUMBER
    }

    /**
     * The entry of property site [site] (a PropSite) when it is monomorphic with the one [kind] the optimizing tier
     * compiles: an own or inherited data property to read (DATA), a writable own data property to write (STORE).
     */
    private fun monomorphic(site: Any?, kind: Int): PropIC? {
        if (!speculate || site !is PropSite) return null
        val e = site.entry ?: return null
        if (e.link != null || e.kind != kind || !e.shape.cacheable) return null
        if (kind == PropCache.STORE && e.chain != null) return null
        return e
    }

    private fun readsThis(target: CodeBlock): Boolean {
        val code = target.code
        var pc = 0
        while (pc < code.size) {
            if (code[pc] == Op.LOAD_THIS) return true
            pc += Op.length(code, pc)
        }
        return false
    }

    // ------------------------------------------------------------------ the graph

    /**
     * The graph's blocks: those reachable from [start], in reverse post order, numbered so; predecessors not reachable
     * (the block after a call that never returns) are dropped with their phi inputs, and loop headers found again.
     */
    private fun order(start: Block) {
        val post = ArrayList<Block>()
        val state = HashMap<Block, Int>()
        val stack = ArrayDeque<Pair<Block, Iterator<Block>>>()
        for (b in made) b.loopHeader = false
        // from the start, and from each exception entry (which only exceptions reach)
        for (root in (listOf(start) + made.filter { it.catchEntry }).asReversed()) {
        if (state.containsKey(root)) continue
        state[root] = 1
        stack.addLast(root to root.succs.iterator())
        while (stack.isNotEmpty()) {
            val (b, succ) = stack.last()
            if (succ.hasNext()) {
                val s = succ.next()
                when (state[s]) {
                    null -> {
                        state[s] = 1
                        stack.addLast(s to s.succs.iterator())
                    }
                    1 -> s.loopHeader = true
                }
            } else {
                stack.removeLast()
                state[b] = 2
                post.add(b)
            }
        }
        }
        post.reverse()
        for ((k, b) in post.withIndex()) b.id = k
        for (b in post) {
            var k = 0
            while (k < b.preds.size) {
                if (state[b.preds[k]] == null) {
                    b.preds.removeAt(k)
                    for (p in b.phis) p.inputs.removeAt(k)
                } else k++
            }
            if (!b.loopHeader) b.entryRegs = null
        }
        g.blocks.addAll(post)
    }

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
        val sites = HashSet<InlineSite>()
        lateinit var resolveSite: (InlineSite?) -> Unit
        fun resolveState(s: FrameState) {
            for (k in s.values.indices) s.values[k] = resolve(s.values[k])
            resolveSite(s.site)
        }
        resolveSite = { s0 ->
            var site = s0
            while (site != null && sites.add(site)) {
                resolveState(site.callerState)
                for (k in site.args.indices) site.args[k] = resolve(site.args[k])
                site.fn = resolve(site.fn)
                site.thisValue = resolve(site.thisValue)
                site.env = resolve(site.env)
                site = site.parent
            }
        }
        for (b in g.blocks) {
            b.phis.removeAll { replaced.containsKey(it) }
            for (p in b.phis) for (k in p.inputs.indices) p.inputs[k] = resolve(p.inputs[k])
            for (i in b.insts) {
                for (k in i.inputs.indices) i.inputs[k] = resolve(i.inputs[k])
                i.state?.let { resolveState(it) }
            }
            b.entryRegs?.let { e -> for (k in e.indices) e[k] = resolve(e[k]) }
            // an inlined body reads its site's values (its environment) even where no exit is
            resolveSite(b.site)
        }
    }

    private fun removeDeadPhis() {
        val live = HashSet<Phi>()
        val work = ArrayDeque<Phi>()
        fun use(v: Value) {
            if (v is Phi && live.add(v)) work.add(v)
        }
        for (b in g.blocks) for (i in b.insts) {
            i.inputs.forEach { use(it) }
            i.state?.let { s ->
                s.values.forEach { use(it) }
                s.site?.values()?.forEach { use(it) }
            }
        }
        while (work.isNotEmpty()) {
            val p = work.removeLast()
            for (v in p.inputs) use(v)
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

        /** Instructions with no [arity] that the builder builds itself (iteration, the finally dispatch, methods). */
        private val BUILT_OPS = setOf(Op.ITER_STEP, Op.FOR_IN_NEXT, Op.JUMP_TABLE, Op.ITER_STEP_U, Op.ITER_REST,
            Op.ITER_CLOSE, Op.ITER_CLOSE_THROW, Op.MAKE_METHOD)

        /** Calls compiled inlined into one another at most; bytecode ints of one callee and of all of them, at most. */
        const val MAX_INLINE_DEPTH = 3
        const val MAX_CALLEE_CODE = 400
        const val INLINE_BUDGET = 2000

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
}
