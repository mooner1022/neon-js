package dev.mooner.neonjs.jit.opt

/**
 * Loops copied on a call site's closure test (docs/OPTIMIZING_TIER.md, P4). A call (or `new`) of a parameter the
 * function never assigns is built as a branch on whether the closure is the one the feedback names (IsClosure, marked
 * tentative: [Inst.b] 1), to the callee's body inlined or to a call made as a call (IrBuilder.inline). Such a parameter
 * is the same all through a loop, so a loop with such a site is copied: the loop takes the inlined body, its copy the
 * call, and a block before them ([Block.dispatch]) tests the closure once. Each version is then what a site of one
 * callee gives (the inlined one has no call left, so LICM moves its loads out), and a callee the first compilation did
 * not see runs the copy, with no exit and no compilation again.
 *
 * Only an innermost loop (a copied header inside would be a second on-stack replacement entry at its pc), without
 * exception entries, of at most [MAX_INSTS] instructions, whose exits all go to one block that only the loop reaches
 * (each value of the loop the code after it uses then merges in one phi there); one site per loop. A tentative site not
 * copied so becomes a site of one callee: its test the guard (CheckClosure, exiting to the state it kept), its branch a
 * jump to the inlined body. On-stack replacement enters the copied loop's header ([Block.twin]) with the test made
 * first (IrCodegen.osrEntries).
 */
internal object Unswitch {
    const val MAX_INSTS = 300

    /**
     * Applies it to [g] (blocks in reverse post order, before any other pass); [made] gets the blocks made. Returns
     * whether the graph changed (blocks no longer reached then: the builder orders it again).
     */
    fun run(g: Graph, made: MutableList<Block>): Boolean {
        val branches = g.blocks.mapNotNull { b ->
            val t = b.insts.lastOrNull()
            val c = t?.inputs?.firstOrNull()
            if (t != null && t.op == Ir.BRANCH && c is Inst && c.op == Ir.IS_CLOSURE && c.b == 1 && c.block === b) t else null
        }
        if (branches.isEmpty()) return false
        val index = HashMap<Block, Int>()
        for ((k, b) in g.blocks.withIndex()) index[b] = k
        // each loop: its header and the blocks that reach a back edge without passing it
        val loops = HashMap<Block, Set<Block>>()
        for (h in g.blocks) if (h.loopHeader) {
            val body = HashSet<Block>()
            body.add(h)
            val work = ArrayDeque(h.preds.filter { index[it]!! >= index[h]!! })
            while (work.isNotEmpty()) {
                val b = work.removeLast()
                if (body.add(b)) work.addAll(b.preds)
            }
            loops[h] = body
        }
        val copiedLoops = HashSet<Block>()
        for (br in branches) {
            if (br.op != Ir.BRANCH) continue // made a guard already (another site of a loop copied)
            val loop = loops.entries.filter { br.block in it.value }.minByOrNull { it.value.size }
            if (loop == null || loop.key in copiedLoops || !copy(g, loop.key, loop.value, br, made)) guard(br)
            else {
                copiedLoops.add(loop.key)
                g.unswitched = true
            }
        }
        return true
    }

    /** [br]'s site as a site of one callee: its test the guard, the branch a jump to the inlined body. */
    private fun guard(br: Inst) {
        val test = br.inputs[0] as Inst
        test.op = Ir.CHECK_CLOSURE
        test.hasResult = false
        cut(br, br.targets[1], br.targets[0])
    }

    /** [br] (a BRANCH) made a jump to [keep], [drop] losing it as a predecessor. */
    private fun cut(br: Inst, keep: Block, drop: Block) {
        br.op = Ir.GOTO
        br.inputs = arrayOf()
        br.targets = listOf(keep)
        val k = drop.preds.indexOf(br.block)
        drop.preds.removeAt(k)
        for (p in drop.phis) p.inputs.removeAt(k)
    }

    /** Copies loop [h] ([body]) on [br]'s test if it qualifies (see the class); returns whether it did. */
    private fun copy(g: Graph, h: Block, body: Set<Block>, br: Inst, made: MutableList<Block>): Boolean {
        val test = br.inputs[0] as Inst
        val fn = test.inputs[0]
        if (body.any { (it.loopHeader && it !== h) || it.catchEntry }) return false
        if ((fn is Inst && fn.block in body) || (fn is Phi && fn.block in body)) return false
        if (body.sumOf { it.insts.size + it.phis.size } > MAX_INSTS) return false
        val outside = h.preds.filter { it !in body }
        if (outside.size != 1) return false
        val pre = outside[0]
        if (pre.succs.count { it === h } != 1) return false
        val exits = body.flatMap { b -> b.succs.filter { it !in body } }.distinct()
        if (exits.size != 1) return false
        val x = exits[0]
        if (x.preds.any { it !in body } || x.catchEntry) return false
        val (other, start) = br.targets
        if (other !in body || start !in body) return false
        // other sites in the loop: sites of one callee in both versions
        for (b in body) if (b !== br.block) {
            val t = b.insts.last()
            val c = t.inputs.firstOrNull()
            if (t.op == Ir.BRANCH && c is Inst && c.op == Ir.IS_CLOSURE && c.b == 1 && c.block === b) guard(t)
        }

        val blocks = g.blocks.filter { it in body }
        // calls inlined in the loop (no block outside it belongs to them) get sites of their own in the copy
        val shared = HashSet<InlineSite>()
        for (b in g.blocks) if (b !in body) {
            var s = b.site
            while (s != null && shared.add(s)) s = s.parent
        }
        val smap = HashMap<InlineSite, InlineSite>()
        fun site(s: InlineSite?): InlineSite? {
            if (s == null || s in shared) return s
            smap[s]?.let { return it }
            val cs = s.callerState
            val n = InlineSite(site(s.parent), s.code, s.callPc, FrameState(cs.pc, cs.values.copyOf(), site(cs.site)), s.fn, s.thisValue,
                s.args.copyOf(), s.env, s.construct)
            n.quiet = s.quiet
            smap[s] = n
            return n
        }
        val bmap = HashMap<Block, Block>()
        for (b in blocks) {
            val n = Block(-1, b.pc, site(b.site))
            n.handler = b.handler
            n.loopHeader = b.loopHeader
            n.entryDepth = b.entryDepth
            bmap[b] = n
            made.add(n)
        }
        val vmap = HashMap<Value, Value>()
        for (b in blocks) {
            val n = bmap[b]!!
            for (p in b.phis) n.phis.add(Phi(n).also { it.kind = p.kind; vmap[p] = it })
            for (i in b.insts) {
                if (i === test) continue
                val c = Inst(i.op, i.inputs, i.a, i.b, i.c, i.pc, i.stmt)
                c.hasResult = i.hasResult
                c.kind = i.kind
                c.virtual = i.virtual
                c.origin = i.origin
                c.wraps = i.wraps
                c.known = i.known
                c.block = n
                n.insts.add(c)
                vmap[i] = c
            }
        }
        fun v(x: Value): Value = vmap[x] ?: x
        val states = HashMap<FrameState, FrameState>()
        fun state(s: FrameState?): FrameState? = if (s == null) null else states.getOrPut(s) {
            FrameState(s.pc, Array(s.values.size) { v(s.values[it]) }, site(s.site))
        }
        for (b in blocks) {
            val n = bmap[b]!!
            for ((k, p) in b.phis.withIndex()) p.inputs.mapTo(n.phis[k].inputs) { v(it) }
            var k = 0
            for (i in b.insts) {
                if (i === test) continue
                val c = n.insts[k++]
                c.inputs = Array(i.inputs.size) { v(i.inputs[it]) }
                c.state = state(i.state)
                c.targets = i.targets.map { bmap[it] ?: it }
                c.callee = i.callee?.let { site(it) }
            }
            b.preds.mapTo(n.preds) { bmap[it] ?: it }
            b.entryRegs?.let { r -> n.entryRegs = Array(r.size) { v(r[it]) } }
        }
        for ((s, n) in smap) {
            val vs = n.callerState.values
            for (k in vs.indices) vs[k] = v(vs[k])
            n.fn = v(s.fn)
            n.thisValue = v(s.thisValue)
            for (k in n.args.indices) n.args[k] = v(n.args[k])
            n.env = v(s.env)
        }

        // the test, once, before both: the loop on the inlined side, the copy on the call's
        val h2 = bmap[h]!!
        val d = Block(-1, h.pc, h.site)
        made.add(d)
        d.handler = if (pre.handler === h.handler) h.handler else null
        test.block!!.insts.remove(test)
        test.block = d
        test.state = null
        d.insts.add(test)
        val dbr = Inst(Ir.BRANCH, arrayOf(test), br.a, 0, 0, br.pc, br.stmt)
        dbr.block = d
        dbr.targets = listOf(h2, h)
        d.insts.add(dbr)
        pre.term.targets = pre.term.targets.map { if (it === h) d else it }
        d.preds.add(pre)
        h.preds[h.preds.indexOf(pre)] = d
        h2.preds[h2.preds.indexOf(pre)] = d
        cut(br, start, other)
        cut(vmap[br] as Inst, bmap[other]!!, bmap[start]!!)
        h.twin = h2
        h.dispatch = d
        h2.twinOf = h

        // the exit block: the copy's edges, then a phi for each value of the loop used after it
        val xPreds = ArrayList(x.preds)
        val xPhis = ArrayList(x.phis)
        for ((k, p) in xPreds.withIndex()) {
            x.preds.add(bmap[p]!!)
            for (ph in xPhis) ph.inputs.add(v(ph.inputs[k]))
        }
        val merged = HashMap<Value, Value>()
        fun after(y: Value): Value {
            if (y !in vmap) return y
            return merged.getOrPut(y) {
                Phi(x).also { p ->
                    p.kind = y.kind
                    for (pr in x.preds) p.inputs.add(if (pr in body) y else vmap[y]!!)
                    x.phis.add(p)
                }
            }
        }
        val seenStates = HashSet<FrameState>()
        fun repair(s: FrameState?) {
            if (s == null || !seenStates.add(s)) return
            for (k in s.values.indices) s.values[k] = after(s.values[k])
        }
        for (b in g.blocks) {
            if (b in body) continue
            // (not the exit block's phis: theirs are by edge already, and those made here are the merges themselves)
            for (p in b.phis) if (b !== x) for (k in p.inputs.indices) p.inputs[k] = after(p.inputs[k])
            for (i in b.insts) {
                for (k in i.inputs.indices) i.inputs[k] = after(i.inputs[k])
                repair(i.state)
            }
            b.entryRegs?.let { r -> for (k in r.indices) r[k] = after(r[k]) }
        }
        for (s in shared) {
            repair(s.callerState)
            s.fn = after(s.fn)
            s.thisValue = after(s.thisValue)
            for (k in s.args.indices) s.args[k] = after(s.args[k])
            s.env = after(s.env)
        }
        return true
    }
}
