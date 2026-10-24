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
import dev.mooner.neonjs.runtime.GlobalSite
import dev.mooner.neonjs.runtime.JSObject
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
    /** Whether loops with a call of a parameter are copied on the closure it is (Unswitch). */
    private val unswitch: Boolean = true,
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
        if (Unswitch.run(g, made)) {
            // (blocks no longer reached, a version's other side, go)
            g.blocks.clear()
            order(start)
            removeTrivialPhis()
            removeDeadPhis()
        }
        if (speculate) ScalarReplacement.run(g)
        dropBoxedChecks()
        val kinds = Kinds(ints, elems)
        kinds.infer(g)
        if (demandInts()) kinds.infer(g)
        if (preferDoubles()) kinds.infer(g)
        intArith(kinds)
        foldChecks(kinds)
        removeDeadCode(kinds)
        if (speculate) Licm.run(g, kinds)
        if (speculate && KnownShapes.run(g, kinds) { i, v -> replaced[i] = v }) {
            // loads that were the values written or read before: their uses have those values' kinds
            resolveAll()
            kinds.infer(g)
            foldChecks(kinds)
            removeDeadCode(kinds)
        }
        QuietInlines.run(g, kinds)
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
                Ir.intArith(i.op) && i.kind == JT.INT -> asInt.addAll(i.inputs)
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

    /**
     * Int32 arithmetic (Ir.intArith) pays where its int is used as one: a bitwise operand, an element key, or the
     * operand of such arithmetic (through phis); and a remainder always (the double one is a library call). A comparison
     * costs the same on doubles: a counter only compared stays one, as the baseline's (no overflow check). Elsewhere (a sum only read as a number) it would cost an overflow check where doubles cost nothing and can
     * be moved out of a loop, so it is made on doubles: its state goes. Returns whether one changed (kinds are inferred
     * again then).
     */
    private fun demandInts(): Boolean {
        val demanded = HashSet<Value>()
        val work = ArrayDeque<Value>()
        fun demand(v: Value) {
            if (v.kind == JT.INT && demanded.add(v)) work.add(v)
        }
        for (b in g.blocks) for (i in b.insts) when {
            JT.isBitwise(i.op) || i.op == Op.BNOT -> i.inputs.forEach { demand(it) }
            i.op == Op.GET_ELEM || i.op == Op.PUT_ELEM -> demand(i.inputs[1])
            i.op == Op.MOD && i.state != null && i.kind == JT.INT -> demand(i)
        }
        while (work.isNotEmpty()) {
            when (val v = work.removeLast()) {
                is Phi -> v.inputs.forEach { demand(it) }
                is Inst -> if (Ir.intArith(v.op) || v.op == Op.TO_NUMERIC) v.inputs.forEach { demand(it) }
            }
        }
        var changed = false
        for (b in g.blocks) for (i in b.insts) if (Ir.intArith(i.op) && i.state != null && i.kind == JT.INT && i !in demanded) {
            i.state = null
            changed = true
        }
        return changed
    }

    /**
     * After the kinds: the int32 arithmetic that stays on doubles needs no exit (its state goes); an int ADD or SUB whose
     * every use is a bitwise operator (which takes its operands to 32 bits) wraps around instead of exiting, as its
     * exact result would be truncated the same way; and so does `>>>` of numbers whose uses all are bitwise: its uint32
     * stays the int of the same bits (Inst.wraps).
     */
    private fun intArith(kinds: Kinds) {
        val uses = HashMap<Value, ArrayList<Any>>()
        for (b in g.blocks) {
            for (p in b.phis) for (v in p.inputs) uses.getOrPut(v) { ArrayList() }.add(p)
            for (i in b.insts) for (v in i.inputs) uses.getOrPut(v) { ArrayList() }.add(i)
        }
        fun truncated(v: Inst) = uses[v]?.all { it is Inst && JT.isBitwise(it.op) } == true
        for (b in g.blocks) for (i in b.insts) {
            if (Ir.intArith(i.op) && i.state != null) {
                if (i.kind != JT.INT) i.state = null
                else if ((i.op == Op.ADD || i.op == Op.SUB) && truncated(i)) {
                    i.wraps = true
                    i.state = null
                }
            } else if (i.op == Op.SHR && ints && i.inputs.all { JT.isNum(kinds.of(it)) } && truncated(i)) {
                i.kind = JT.INT
                i.wraps = true
            }
        }
        boundedSteps(kinds)
    }

    /**
     * A loop counter's `++` where `i < n` holds (n an int: i is below the largest int32) cannot leave the int32 range,
     * nor `--` where `i > n` holds: no check, no exit (an exit in a loop also keeps ART from optimizing it). The test
     * holds where the branch on it goes, if that block has no other way in and dominates the step.
     */
    private fun boundedSteps(kinds: Kinds) {
        val index = HashMap<Block, Int>()
        for ((k, b) in g.blocks.withIndex()) index[b] = k
        val idom by lazy { IrVerifier.dominators(g, index) }
        fun dominates(a: Block, b: Block): Boolean {
            var x: Block? = b
            while (x != null) {
                if (x === a) return true
                val d = idom[x]
                x = if (d == null || d === x || index[d] == null) null else d
            }
            return false
        }
        // the blocks where v < n (below) or v > n (above) holds, n an int, by the branches testing it
        val below = HashMap<Value, ArrayList<Block>>()
        val above = HashMap<Value, ArrayList<Block>>()
        for (b in g.blocks) {
            val t = b.insts.lastOrNull() ?: continue
            val c = t.inputs.firstOrNull() as? Inst ?: continue
            if (t.op != Ir.BRANCH || (t.a != Op.JUMP_IF_FALSE && t.a != Op.JUMP_IF_TRUE) || c.inputs.size != 2) continue
            val holds = if (t.a == Op.JUMP_IF_FALSE) t.targets[1] else t.targets[0]
            if (holds.preds.size != 1) continue
            val (l, r) = c.inputs[0] to c.inputs[1]
            when {
                c.op == Op.LT && kinds.of(r) == JT.INT -> below.getOrPut(l) { ArrayList() }.add(holds)
                c.op == Op.GT && kinds.of(l) == JT.INT -> below.getOrPut(r) { ArrayList() }.add(holds)
                c.op == Op.GT && kinds.of(r) == JT.INT -> above.getOrPut(l) { ArrayList() }.add(holds)
                c.op == Op.LT && kinds.of(l) == JT.INT -> above.getOrPut(r) { ArrayList() }.add(holds)
            }
        }
        if (below.isEmpty() && above.isEmpty()) return
        for (b in g.blocks) for (i in b.insts) {
            if ((i.op != Op.INC && i.op != Op.DEC) || i.state == null || i.kind != JT.INT) continue
            var v = i.inputs[0]
            if (v is Inst && v.op == Op.TO_NUMERIC) v = v.inputs[0]
            val where = (if (i.op == Op.INC) below[v] else above[v]) ?: continue
            if (where.any { dominates(it, b) }) i.state = null
        }
    }

    /**
     * Number checks of values that are numbers already (a literal's property read as the value it was made of, a
     * checked load checked again): one of a number of its own kind goes, its uses taking the value; another (a double
     * of an int) only converts it and needs no exit, so its state goes (an exit in a loop keeps ART from optimizing it).
     */
    private fun foldChecks(kinds: Kinds) {
        var folded = false
        for (b in g.blocks) for (i in b.insts) {
            if (i.op != Ir.CHECK_NUMBER && i.op != Ir.CHECK_INT32) continue
            val k = kinds.of(i.inputs[0])
            if (!JT.isNum(k) || (i.op == Ir.CHECK_INT32 && k != JT.INT)) continue
            if (k == i.kind) {
                replaced[i] = i.inputs[0]
                folded = true
            } else i.state = null
        }
        if (folded) resolveAll()
    }

    /**
     * Results nothing uses (no instruction, phi, exit state, inlined call or on-stack replacement entry) of instructions
     * that only compute them go: arithmetic and comparisons of numbers, guarded loads, number checks (their exits only
     * test speculations nothing relies on then). HotSpot's compilers remove such code; ART's keep it where an exit's
     * call in the loop keeps the registers alive (an inlined call whose result is unused: 2x on ART).
     */
    private fun removeDeadCode(kinds: Kinds) {
        do {
            val used = HashSet<Value>()
            val sites = HashSet<InlineSite>()
            fun site(s0: InlineSite?) {
                var s = s0
                while (s != null && sites.add(s)) {
                    used.addAll(s.values())
                    s = s.parent
                }
            }
            for (b in g.blocks) {
                for (p in b.phis) used.addAll(p.inputs)
                for (i in b.insts) {
                    used.addAll(i.inputs)
                    i.state?.let { s ->
                        used.addAll(s.values)
                        site(s.site)
                    }
                }
                b.entryRegs?.let { used.addAll(it) }
                site(b.site)
            }
            var removed = false
            for (b in g.blocks) if (b.insts.removeAll { it.hasResult && !it.virtual && it !in used && pure(it, kinds) }) removed = true
        } while (removed)
        removeDeadPhis()
    }

    /** Whether [i] does nothing but compute its result (or exit: a speculation). */
    private fun pure(i: Inst, kinds: Kinds): Boolean = when (i.op) {
        Op.ADD, Op.SUB, Op.MUL, Op.DIV, Op.MOD, Op.EXP, Op.BAND, Op.BOR, Op.BXOR, Op.SHL, Op.SAR, Op.SHR,
        Op.LT, Op.GT, Op.LE, Op.GE, Op.EQ, Op.NE, Op.NEG, Op.BNOT, Op.INC, Op.DEC, Op.TO_NUMERIC, Op.TO_NUMBER, Ir.MATH ->
            i.inputs.all { JT.isNum(kinds.of(it)) }
        Op.SEQ, Op.SNE, Op.NOT, Op.LOAD_ENV, Ir.CHECK_NUMBER, Ir.CHECK_INT32, Ir.LOAD_SLOT, Ir.LOAD_GLOBAL_LEX,
        Ir.LOAD_GLOBAL_SLOT, Ir.CLOSURE_ENV, Ir.LENGTH, Ir.CONSTRUCT_RESULT -> true
        else -> false
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
            liveness()
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

        /** Registers live before each instruction, exceptions included ([liveness]). */
        lateinit var liveIn: Array<BooleanArray>

        /**
         * [v], register [r]'s value, in the state at [pc]: undefined where the register is dead there (the interpreter
         * writes it before it reads it), so that an exit does not keep the value (nor a phi of it) alive.
         */
        fun regAt(pc: Int, r: Int, v: Value): Value = if (pc < bc.size && !liveIn[pc][r]) undefined() else v

        /** The registers live at some handler's start: those kept in homes. */
        private fun homes(): IntArray {
            val out = ArrayList<Int>()
            for (r in 0 until nregs) if (catchEntries.keys.any { liveIn[it][r] }) out.add(r)
            return out.toIntArray()
        }

        /** [liveIn]: the registers the bytecode may read before writing them, from each instruction on. */
        private fun liveness() {
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
            if (b.loopHeader) {
                // where on-stack replacement may enter (the function's own loops), and the state where LICM's exits
                // resume; a register dead there is undefined (an entry does not load it: a frame may hold anything there,
                // the baseline's a hole, which loaded would overwrite a value another register holds too)
                b.entryRegs = Array(nregs) { regAt(b.pc, it, regs[it]!!) }
                b.entryDepth = stack.size
            }
            var cur = b
            fun state(pc: Int) = FrameState(pc, Array(nregs + stack.size) { if (it < nregs) regAt(pc, it, regs[it]!!) else stack[it - nregs] }, site)
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
                        var v = pop()
                        val s = site
                        if (s != null && s.construct && v !== s.thisValue) {
                            // a construction gives `this` unless the body returns an object
                            v = if (v is Const && v.value !is JSObject) s.thisValue
                            else add(cur, Inst(Ir.CONSTRUCT_RESULT, arrayOf(v, s.thisValue), 0, 0, 0, pc, stmt).also { it.hasResult = true })
                        }
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
                        if (ic == null && ints && speculate && !code.t2FailedAt(pc) && (code.constants[a] as? PropSite)?.key == "length" &&
                            (code.profile?.values?.get(pc)?.toInt() ?: 0) and 0xFF == ValueProfile.INT) {
                            // an array's or a string's length, read in place
                            val l = before(Inst(Ir.LENGTH, arrayOf(stack.last()), 0, 0, 0, pc, stmt))
                            pop()
                            add(cur, l).hasResult = true
                            stack.add(l)
                        } else if (ic == null) inst(op, arrayOf(pop()), result = true)
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
                    Op.LOAD_GLOBAL -> {
                        val ic = if (speculate && !code.t2FailedAt(pc)) (code.constants[a] as? GlobalSite)?.cache else null
                        if (ic == null) inst(op, NO_VALUES, result = true)
                        else {
                            // the binding, or the global object's slot, the cache saw; anything else leaves before the
                            // instruction
                            val load = before(
                                if (ic.shape == null) Inst(Ir.LOAD_GLOBAL_LEX, NO_VALUES, g.dataIndex(ic.token), ic.index, 0, pc, stmt)
                                else Inst(Ir.LOAD_GLOBAL_SLOT, NO_VALUES, g.dataIndex(ic.shape), ic.slot, ic.epoch, pc, stmt),
                            )
                            add(cur, load).hasResult = true
                            stack.add(load)
                        }
                    }
                    Op.PUT_PROP -> {
                        val ic = if (code.t2FailedAt(pc)) null else monomorphic(code.constants[a], PropCache.STORE)
                        val add = if (ic != null || code.t2FailedAt(pc)) null else monomorphic(code.constants[a], PropCache.ADD)
                        if (add != null) {
                            // (obj v -- v): a new own property, through the transition the cache saw
                            val chain = add.chain
                            val store = before(Inst(Ir.ADD_SLOT, arrayOf(stack[stack.size - 2], stack[stack.size - 1]),
                                g.dataIndex(add.shape), g.dataIndex(add.next), if (chain == null) -1 else g.dataIndex(chain), pc, stmt))
                            val v = pop()
                            pop()
                            add(cur, store)
                            stack.add(v)
                        } else if (ic == null) {
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
                    Op.MAKE_CLASS -> {
                        // ([heritage] [name] -- proto ctor): the helper's pair, then each value of it
                        val n = (if (bb and 1 != 0) 1 else 0) + (if (bb and 2 != 0) 1 else 0)
                        if (stack.size < n) throw JitBailout("operand stack underflow at $pc")
                        val inputs = Array(n) { stack[stack.size - n + it] }
                        repeat(n) { stack.removeAt(stack.size - 1) }
                        val pair = add(cur, Inst(op, inputs, a, bb, c, pc, stmt).also { it.hasResult = true })
                        for (k in 0..1) stack.add(add(cur, Inst(Ir.PART, arrayOf<Value>(pair), k, 0, 0, pc, stmt).also { it.hasResult = true }))
                    }
                    Op.NEW -> {
                        val target = inlineTarget(pc, construct = true)
                        if (target == null || stack.size < a + 1) {
                            val n = a + 1
                            if (stack.size < n) throw JitBailout("operand stack underflow at $pc")
                            val inputs = Array(n) { stack[stack.size - n + it] }
                            repeat(n) { stack.removeAt(stack.size - 1) }
                            inst(op, inputs, result = true)
                        } else {
                            cur = inline(cur, target, pc, a, stmt, regs, stack, construct = true)
                        }
                    }
                    Op.CALL -> {
                        val target = inlineTarget(pc)
                        val intrinsic = if (target == null) intrinsicAt(pc, a) else 0
                        if (intrinsic != 0) {
                            // a Math builtin the feedback saw called there: computed in place behind a guard that
                            // leaves before the call for anything else
                            val n = a + 2
                            if (stack.size < n) throw JitBailout("operand stack underflow at $pc")
                            val fn = stack[stack.size - n]
                            val args = Array(a) { stack[stack.size - a + it] }
                            add(cur, Inst(Ir.CHECK_INTRINSIC, arrayOf(fn), intrinsic, 0, 0, pc, stmt)).state = state(pc)
                            repeat(n) { stack.removeAt(stack.size - 1) }
                            stack.add(add(cur, Inst(Ir.MATH, args, intrinsic, 0, 0, pc, stmt).also { it.hasResult = true }))
                        } else if (target == null || stack.size < a + 2) {
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
                        // arithmetic that may stay on ints (Kinds): an exit before it, its operands on the stack, where the
                        // result is not an int32 (not where that failed before)
                        val before = if (speculate && ints && Ir.intArith(op) && !code.t2FailedAt(pc)) state(pc) else null
                        val inputs = Array(n) { stack[stack.size - n + it] }
                        repeat(n) { stack.removeAt(stack.size - 1) }
                        inst(op, inputs, result = pushes(op)).state = before
                    }
                }
                // a load whose values were only numbers: a number from here on, or an exit after the load
                if (speculate && (op == Op.GET_PROP || op == Op.GET_ELEM || op == Op.LOAD_ENV || op == Op.LOAD_ENV_TDZ ||
                        op == Op.LOAD_GLOBAL || op == Op.CALL) && !code.t2FailedAt(pc)) {
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

        /** The code block to compile the CALL at [pc] (or, [construct], the NEW) inlined with, or null. */
        private fun inlineTarget(pc: Int, construct: Boolean = false): CodeBlock? {
            val depth = site?.depth ?: 0
            // (where a call made there was another function before, CodeBlock.t2Failed, the call is made as a call when
            // it is another again: inline)
            if (depth >= maxInlineDepth) return null
            val fb = code.callFeedback
            val target = (if (fb == null) (if (construct) null else code.callHint(pc))
            else fb.getOrNull(pc)?.takeIf { it.count >= Inlining.MIN_COUNT }?.target) ?: return null
            // (helpers read the strictness of the innermost call the frame records, JitRt.strict: a body that runs one is
            // not quiet, QuietInlines, and records its call)
            if (if (construct) !Inlining.constructible(target, MAX_CALLEE_CODE) else !Inlining.eligible(target, MAX_CALLEE_CODE)) return null
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

        /** The Math builtin (MathIntrinsic) the CALL at [pc] with [argc] arguments always called, or 0. */
        private fun intrinsicAt(pc: Int, argc: Int): Int {
            if (!speculate || code.t2FailedAt(pc)) return 0
            val id = code.callFeedback?.getOrNull(pc)?.intrinsic ?: return 0
            return if (id > 0 && dev.mooner.neonjs.runtime.MathIntrinsic.arity(id) == argc) id else 0
        }

        /**
         * The CALL at [pc] of [argc] arguments (or, [construct], the NEW) with [target]'s body built in place, from block
         * [cur], with the caller's [regs] and [stack] (the call's operands on top, its result there after). Returns the
         * block where the body's returns meet, where the caller goes on.
         */
        private fun inline(cur: Block, target: CodeBlock, pc: Int, argc: Int, stmt: Int, regs: Array<Value?>, stack: ArrayList<Value>,
                           construct: Boolean = false): Block {
            // (fn, this, args) or (fn, args)
            val n = if (construct) argc + 1 else argc + 2
            if (stack.size < n) throw JitBailout("operand stack underflow at $pc")
            inlined += target.code.size
            T2.inlinedCalls.incrementAndGet()
            val ops = ArrayList(stack.subList(stack.size - n, stack.size))
            val fn = ops[0]
            // where the guard failed before (CodeBlock.t2Failed: a callback site that gets several functions), another
            // function is called as a call, on a branch of its own; elsewhere the guard leaves before the call, which the
            // interpreter then makes. A parameter never assigned is the same all through a loop: its site is built so
            // tentatively, the guard's state kept (Unswitch copies the loop on the test, or makes it a guard)
            val polymorphic = code.t2FailedAt(pc)
            val tentative = !polymorphic && unswitch && speculate && site == null && unassignedParams.any { regs[it] === fn }
            var start = cur
            var other: Block? = null
            if (polymorphic || tentative) {
                val test = add(cur, Inst(Ir.IS_CLOSURE, arrayOf(fn), g.dataIndex(target), if (tentative) 1 else 0, 0, pc, stmt).also { it.hasResult = true })
                if (tentative) test.state = FrameState(pc, Array(nregs + stack.size) { if (it < nregs) regAt(pc, it, regs[it]!!) else stack[it - nregs] }, site)
                start = newBlock(pc, site).also { it.handler = cur.handler }
                other = newBlock(pc, site).also { it.handler = cur.handler }
                add(cur, Inst(Ir.BRANCH, arrayOf(test), Op.JUMP_IF_FALSE, 0, 0, pc, stmt)).targets = listOf(other, start)
                start.preds.add(cur)
                other.preds.add(cur)
            } else {
                val guard = add(cur, Inst(Ir.CHECK_CLOSURE, arrayOf(fn), g.dataIndex(target), 0, 0, pc, stmt))
                guard.state = FrameState(pc, Array(nregs + stack.size) { if (it < nregs) regAt(pc, it, regs[it]!!) else stack[it - nregs] }, site)
            }
            repeat(n) { stack.removeAt(stack.size - 1) }
            // (where the caller goes on after the call: what is live at the call is)
            val callerState = FrameState(pc, Array(nregs + stack.size) { if (it < nregs) regAt(pc, it, regs[it]!!) else stack[it - nregs] }, site)
            // a construction's `this`, made before the body is entered (as Interpreter.constructClosure makes it)
            val made = if (!construct) null else {
                val cls = if (target.flags and CodeBlock.CLASS_CTOR != 0) 1 else 0
                add(start, Inst(Ir.NEW_THIS, arrayOf(fn), cls, 0, 0, pc, stmt).also { it.hasResult = true })
            }
            val enter = add(start, Inst(Ir.INLINE_ENTER, arrayOf(fn), pc, 0, 0, pc, stmt))
            val env = add(start, Inst(Ir.CLOSURE_ENV, arrayOf(fn), 0, 0, 0, pc, stmt).also { it.hasResult = true })
            val thisValue = if (made != null) made else if (readsThis(target)) {
                val bound = if (target.flags and (CodeBlock.STRICT or CodeBlock.ARROW) != 0) 1 else 0
                add(start, Inst(Ir.INLINE_THIS, arrayOf(fn, ops[1]), bound, 0, 0, pc, stmt).also { it.hasResult = true })
            } else undefined()
            val args = Array(argc) { ops[n - argc + it] }
            val calleeSite = InlineSite(site, target, pc, callerState, fn, thisValue, args, env, construct)
            enter.callee = calleeSite
            g.dataIndex(target.constants)
            val body = Body(target, calleeSite, cur.handler)
            // where the body's returns meet (and the call ends), then, with a call made as a call, where both meet
            val end = newBlock(pc, site)
            end.handler = cur.handler
            body.join = end
            val calleeRegs = Array<Value>(target.numRegs) { undefined() }
            target.paramRegs?.forEachIndexed { i, r -> if (r >= 0 && i < argc) calleeRegs[r] = args[i] }
            body.build(start, calleeRegs)
            val result: Value = when (body.returned.size) {
                0 -> undefined() // it never returns: the block is not reached
                1 -> body.returned[0]
                else -> Phi(end).also { p -> end.phis.add(p); p.inputs.addAll(body.returned) }
            }
            add(end, Inst(Ir.INLINE_EXIT, NO_VALUES, 0, 0, 0, pc, stmt)).callee = calleeSite
            if (other == null) {
                stack.add(result)
                return end
            }
            val called = add(other, Inst(if (construct) Op.NEW else Op.CALL, ops.toTypedArray(), argc, 0, 0, pc, stmt).also { it.hasResult = true })
            val join = newBlock(pc, site)
            join.handler = cur.handler
            if (body.returned.isNotEmpty()) {
                goto(end, join)
                join.preds.add(end)
            }
            goto(other, join)
            join.preds.add(other)
            stack.add(if (join.preds.size == 1) called else Phi(join).also { p -> join.phis.add(p); p.inputs.add(result); p.inputs.add(called) })
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
     * compiles: an own or inherited data property to read (DATA), a writable own data property to write (STORE), a new
     * own property to add (ADD).
     */
    private fun monomorphic(site: Any?, kind: Int): PropIC? {
        if (!speculate || site !is PropSite) return null
        val e = site.entry ?: return null
        if (e.link != null || e.kind != kind || !e.shape.cacheable) return null
        if (kind == PropCache.STORE && e.chain != null) return null
        return e
    }

    /** The registers of parameters the function never assigns (no STORE_REG there): the same value all through it. */
    private val unassignedParams: IntArray by lazy {
        val stored = HashSet<Int>()
        val code = cb.code
        var pc = 0
        while (pc < code.size) {
            if (code[pc] == Op.STORE_REG) stored.add(code[pc + 1])
            pc += Op.length(code, pc)
        }
        (cb.paramRegs ?: IntArray(0)).filter { it >= 0 && it !in stored }.toIntArray()
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
        resolveAll()
    }

    /**
     * A number check pays where its number is taken unboxed. Where every use takes an Object (a return, a call's
     * operand, a store), the number would only be boxed again, a new box at each check, so the check goes and its
     * uses take the value as it came.
     */
    private fun dropBoxedChecks() {
        val uses = HashMap<Value, ArrayList<Any>>()
        for (b in g.blocks) {
            for (p in b.phis) for (v in p.inputs) uses.getOrPut(v) { ArrayList() }.add(p)
            for (i in b.insts) for (v in i.inputs) uses.getOrPut(v) { ArrayList() }.add(i)
        }
        var dropped = false
        for (b in g.blocks) for (i in b.insts) {
            if (i.op != Ir.CHECK_NUMBER && i.op != Ir.CHECK_INT32) continue
            if (uses[i].orEmpty().all { it is Inst && takesObject(it) }) {
                replaced[i] = i.inputs[0]
                dropped = true
            }
        }
        if (dropped) resolveAll()
    }

    /** Whether [i] takes all its operands as Objects, whatever their kinds. */
    private fun takesObject(i: Inst): Boolean = when (i.op) {
        Ir.RETURN, Ir.THROW, Op.CALL, Op.NEW, Op.STORE_ENV, Op.STORE_GLOBAL, Op.PUT_PROP, Ir.STORE_SLOT, Ir.ADD_SLOT,
        Op.NEW_OBJECT_LITERAL, Op.ARRAY_PUSH, Ir.NEW_THIS, Ir.CONSTRUCT_RESULT -> true
        else -> false
    }

    /** Puts the values [replaced] gives in place of those replaced, everywhere, and removes those (phis, instructions). */
    private fun resolveAll() {
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
            b.insts.removeAll { replaced.containsKey(it) }
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
            Op.ITER_CLOSE, Op.ITER_CLOSE_THROW, Op.MAKE_METHOD, Op.MAKE_CLASS)

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
