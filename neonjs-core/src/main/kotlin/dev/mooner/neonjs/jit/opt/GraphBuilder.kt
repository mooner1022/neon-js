package dev.mooner.neonjs.jit.opt

import dev.mooner.neonjs.compiler.CodeBlock
import dev.mooner.neonjs.jit.JitBailout

/**
 * Builds the [Graph] of a code block from its bytecode, whatever its language: registers and operand stack slots
 * become SSA values, with a phi at every merge for every one of them, then the phis that merge one value (or only
 * themselves) are replaced by it and those nothing uses are dropped. The language's subclass decodes its bytecode
 * ([Body.length], [Body.targets], [Body.regsRead]), builds each instruction ([Body.buildOp]) and makes the graph's start
 * ([entry]); the order of the passes after is the core's ([build]).
 *
 * Exception handlers: each handler's entry is a block of its own that only exceptions reach; the registers a handler
 * may read are kept in homes ([Body.At.home]) wherever they change in a range it covers. Exits ([FrameState]) leave
 * registers dead at their pc as [dead], so that they keep no value alive.
 */
internal abstract class GraphBuilder(
    protected val cb: CodeBlock,
    @JvmField protected val g: Graph,
    /** Whether the code gets the deopt stress mode's exits: at the start and the loop headers of each body. */
    protected val stressExits: Boolean,
    /** Whether the code speculates on profiles and caches (T2.SPECULATE): checks that exit, and the passes using them. */
    protected val speculate: Boolean,
) {
    /** Every block made, in the order made; the graph keeps those reachable, in reverse post order. */
    protected val made = ArrayList<Block>()

    /** Why [cb] cannot be built, or null (generators and async functions: not yet). */
    protected abstract fun unbuildable(): String?

    /** The body of [cb], the function compiled. */
    protected abstract fun root(): Body

    /** The registers of the function compiled at its start, made in [start] (its parameters, their checks). */
    protected abstract fun entry(start: Block): Array<Value>

    /** After the first inference of kinds: the language's refinements (which values stay ints, say), inferring again. */
    protected open fun refineKinds(kinds: Kinds) {}

    /** After the facts after guards ([KnownShapes]) replaced values, kinds inferred again: the language's cleanup. */
    protected open fun afterFacts(kinds: Kinds) {}

    fun build(): Graph {
        unbuildable()?.let { throw JitBailout(it) }
        val start = newBlock(-1, null)
        val root = root()
        root.build(start, entry(start))
        // values moved before a loop, or copies of it, would not be there where a suspended activation resumes: a
        // graph that suspends gets neither (LICM, Unswitch)
        val suspends = made.any { it.resumeEntry }
        order(start)
        removeTrivialPhis()
        removeDeadPhis()
        if (!suspends && Unswitch.run(g, made)) {
            // (blocks no longer reached, a version's other side, go)
            g.blocks.clear()
            order(start)
            removeTrivialPhis()
            removeDeadPhis()
        }
        if (speculate) ScalarReplacement.run(g)
        dropBoxedChecks()
        val kinds = Kinds(g.kindSystem)
        kinds.infer(g)
        refineKinds(kinds)
        removeDeadCode(kinds)
        if (speculate && !suspends) Licm.run(g, kinds)
        val facts = if (speculate) g.dialect.facts(g, kinds) else null
        if (facts != null && Facts.run(g, facts) { i, v -> replaced[i] = v }) {
            // loads that were the values written or read before: their uses have those values' kinds
            resolveAll()
            kinds.infer(g)
            afterFacts(kinds)
            removeDeadCode(kinds)
        }
        QuietInlines.run(g, kinds)
        number()
        return g
    }

    /**
     * Results nothing uses (no instruction, phi, exit state, inlined call or on-stack replacement entry) of instructions
     * that only compute them go: arithmetic and comparisons of numbers, guarded loads, number checks (their exits only
     * test speculations nothing relies on then). HotSpot's compilers remove such code; ART's keep it where an exit's
     * call in the loop keeps the registers alive (an inlined call whose result is unused: 2x on ART).
     */
    protected fun removeDeadCode(kinds: Kinds) {
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
            for (b in g.blocks) if (b.insts.removeAll { it.hasResult && !it.virtual && it !in used && g.ops.removable(it, kinds) }) removed = true
        } while (removed)
        removeDeadPhis()
    }
    protected fun newBlock(pc: Int, site: InlineSite?): Block = Block(-1, pc, site).also { made.add(it) }

    protected fun goto(from: Block, to: Block) {
        add(from, Inst(Ir.GOTO, NO_VALUES, 0, 0, 0, -1, -1)).targets = listOf(to)
    }

    protected fun add(b: Block, i: Inst): Inst {
        i.block = b
        b.insts.add(i)
        return i
    }

    /** What a register written before it is read holds there (an exit passes it: the interpreter's own). */
    protected fun dead() = Const(g.deadValue)
    /**
     * The code of one function in the graph: the function compiled ([site] null) or the body of a call compiled inlined.
     * Its bytecode blocks become blocks (an inlined call splits one: the instructions after the call continue in the
     * block where the callee's returns meet).
     */
    protected abstract inner class Body(
        @JvmField val code: CodeBlock,
        @JvmField val site: InlineSite?,
        /** For an inlined body: the handler around its call. */
        @JvmField val outerHandler: Block? = null,
    ) {
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
                if (live[r]) add(e, Inst(Ir.LOAD_HOME, NO_VALUES, r, 0, 0, pc, -1).also { it.hasResult = true }) else dead()
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
        fun regAt(pc: Int, r: Int, v: Value): Value = if (pc < bc.size && !liveIn[pc][r]) dead() else v

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
                p += length(p)
            }
            liveIn = Array(bc.size) { BooleanArray(nregs) }
            var changed = true
            while (changed) {
                changed = false
                for (pc in pcs.asReversed()) {
                    val live = BooleanArray(nregs)
                    for (s in instSuccessors(pc)) {
                        val l = liveIn[s]
                        for (r in 0 until nregs) if (l[r]) live[r] = true
                    }
                    val def = regWritten(pc)
                    if (def >= 0) live[def] = false
                    for (r in regsRead(pc)) live[r] = true
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
            targets(pc)?.let { return it.toList() }
            val next = pc + length(pc)
            return if (next < bc.size) listOf(next) else emptyList()
        }

        // ---------------------------------------------------------- the language's bytecode

        /** The length of the instruction at [pc], in ints. */
        protected abstract fun length(pc: Int): Int

        /** Operand [k] (1, 2 or 3) of the instruction at [pc], 0 where it has none. */
        protected abstract fun operand(pc: Int, k: Int): Int

        /**
         * Where control goes after the instruction at [pc]: null when to the next instruction only (it ends no block);
         * otherwise every target, in order (none: it leaves the function), a conditional jump listing its target and
         * then the next instruction, once where they are the same.
         */
        protected abstract fun targets(pc: Int): IntArray?

        /** Whether the instruction at [pc] is one the IR has ([buildOp] builds it); a body with another is not built. */
        protected abstract fun supported(pc: Int): Boolean

        /** The registers the instruction at [pc] reads, and the one it writes (or -1). */
        protected abstract fun regsRead(pc: Int): IntArray
        protected abstract fun regWritten(pc: Int): Int

        /** The name of the instruction at [pc], for messages. */
        protected abstract fun nameAt(pc: Int): String

        /**
         * Builds the instruction [at] is at into [At.cur] (its operands on [At.stack], its registers [At.regs]).
         * Returns whether it ended the block (a jump, a return: [At.end] called and the terminator added).
         */
        protected abstract fun buildOp(at: At): Boolean

        private fun scan() {
            val leader = BooleanArray(bc.size + 1)
            leader[0] = true
            var pc = 0
            while (pc < bc.size) {
                val len = length(pc)
                if (!supported(pc)) throw JitBailout("unsupported op ${nameAt(pc)}")
                targets(pc)?.let { t ->
                    for (x in t) leader[x] = true
                    leader[pc + len] = true
                }
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
                targets(pc)?.let { return it.toList() }
                val next = pc + length(pc)
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
            while (pc < p) pc += length(pc)
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
            // in a handler's range: the registers it may read where it finds them
            val at = At(b, regs, stack, homed = site == null && b.handler != null && homeRegs.isNotEmpty())
            if (at.homed) for (r in homeRegs) at.home(r)
            if (stressExits && (b.pc == 0 || b.loopHeader)) {
                add(at.cur, Inst(Ir.STRESS_EXIT, NO_VALUES, 0, 0, 0, b.pc, stmtOf[b.pc])).state = at.state(b.pc)
            }
            at.pc = b.pc
            while (true) {
                at.decode()
                if (buildOp(at)) return
                at.pc += at.len
                val nb = blockAt[at.pc]
                if (nb != null) {
                    at.end()
                    goto(at.cur, nb)
                    return
                }
            }
        }

        /**
         * Where a block's building is ([buildBlock], [buildOp]): its first block [b] and the block instructions go to now
         * ([cur]: another after an inlined call), the registers and the operand stack, the instruction ([pc], its
         * opcode, length, operands and statement).
         */
        protected inner class At(
            @JvmField val b: Block,
            @JvmField val regs: Array<Value?>,
            @JvmField val stack: ArrayList<Value>,
            /** Whether a handler's range covers the block: registers it may read are kept in their homes ([home]). */
            @JvmField val homed: Boolean,
        ) {
            @JvmField var cur: Block = b
            @JvmField var pc = 0
            @JvmField var op = 0
            @JvmField var len = 0
            @JvmField var a = 0
            @JvmField var bb = 0
            @JvmField var c = 0
            @JvmField var stmt = -1

            internal fun decode() {
                op = bc[pc]
                len = length(pc)
                a = operand(pc, 1)
                bb = operand(pc, 2)
                c = operand(pc, 3)
                stmt = stmtOf[pc]
            }

            /** The interpreter's state at [pc] with the registers and stack as they are. */
            fun state(pc: Int) = FrameState(pc, Array(nregs + stack.size) { if (it < nregs) regAt(pc, it, regs[it]!!) else stack[it - nregs] }, site)

            /** Register [r] changed: its home too, where a handler may read it. */
            fun home(r: Int) {
                if (homed && r in homeRegs) add(cur, Inst(Ir.SET_HOME, arrayOf(regs[r]!!), r, 0, 0, -1, -1))
            }

            /** The block's code ends here: its state out is the registers and the stack as they are. */
            fun end() {
                tail[b] = cur
                stateOut[b] = Array(nregs + stack.size) { if (it < nregs) regs[it]!! else stack[it - nregs] }
                depthOut[b] = stack.size
            }

            fun pop(): Value = if (stack.isEmpty()) throw JitBailout("operand stack underflow at $pc") else stack.removeAt(stack.size - 1)

            /** The top [n] values of the stack in [order] (indices into them, the deepest 0). */
            fun permute(n: Int, order: IntArray) {
                if (stack.size < n) throw JitBailout("operand stack underflow at $pc")
                val top = ArrayList(stack.subList(stack.size - n, stack.size))
                repeat(n) { stack.removeAt(stack.size - 1) }
                for (k in order) stack.add(top[k])
            }

            /** Instruction [op] of the instruction's operands, added to [cur]; its result pushed where it has one. */
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

            /**
             * The activation suspends here (a generator's yield, an async function's await): SUSPEND of kind [kind] with
             * [value] (or none), the interpreter's state at the next instruction, where it resumes; then a block of its own
             * where it does ([Block.resumeEntry]), the registers live there and the operand stack read from the frame, and,
             * where [received], the value the resumption pushed. The instructions after go on in that block.
             */
            fun suspend(kind: Int, value: Value?, received: Boolean) {
                if (site != null) throw JitBailout("a suspension in an inlined body")
                val next = pc + len
                val s = add(cur, Inst(Ir.SUSPEND, if (value == null) NO_VALUES else arrayOf(value), kind, 0, 0, pc, stmt))
                s.state = state(next)
                val e = newBlock(next, site)
                e.resumeEntry = true
                e.handler = cur.handler
                s.targets = listOf(e)
                e.preds.add(cur)
                cur = e
                fun slot(k: Int) = add(e, Inst(Ir.RESUME_SLOT, NO_VALUES, k, 0, 0, next, stmt).also { it.hasResult = true })
                for (r in 0 until nregs) regs[r] = if (!liveIn[next][r]) dead() else slot(r)
                for (k in stack.indices) stack[k] = slot(nregs + k)
                if (received) stack.add(slot(nregs + stack.size))
                if (homed) for (r in homeRegs) home(r)
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
    protected val replaced = HashMap<Value, Value>()

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
                    replaced[p] = same ?: dead() // a phi of only itself: a loop never entered
                    changed = true
                }
            }
        }
        resolveAll()
    }

    /**
     * A check that unboxes ([OpInfo.UNBOXES]: a number check) pays where its value is taken unboxed. Where every use takes
     * an Object (a return, a call's operand, a store), the value would only be boxed again, a new box at each check, so
     * the check goes and its uses take the value as it came.
     */
    private fun dropBoxedChecks() {
        val uses = HashMap<Value, ArrayList<Any>>()
        for (b in g.blocks) {
            for (p in b.phis) for (v in p.inputs) uses.getOrPut(v) { ArrayList() }.add(p)
            for (i in b.insts) for (v in i.inputs) uses.getOrPut(v) { ArrayList() }.add(i)
        }
        var dropped = false
        for (b in g.blocks) for (i in b.insts) {
            if (g.ops.info(i.op).flagsOf(i) and OpInfo.UNBOXES == 0) continue
            if (uses[i].orEmpty().all { it is Inst && g.ops.objectOperands(it) }) {
                replaced[i] = i.inputs[0]
                dropped = true
            }
        }
        if (dropped) resolveAll()
    }

    /** Puts the values [replaced] gives in place of those replaced, everywhere, and removes those (phis, instructions). */
    protected fun resolveAll() {
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
                site.mapFrame { resolve(it) }
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
    protected companion object {
        @JvmField val NO_VALUES = arrayOf<Value>()
    }
}
