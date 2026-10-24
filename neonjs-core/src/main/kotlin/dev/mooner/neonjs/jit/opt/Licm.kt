package dev.mooner.neonjs.jit.opt

import dev.mooner.neonjs.compiler.Op
import dev.mooner.neonjs.jit.JT

/**
 * Loop-invariant code motion (docs/OPTIMIZING_TIER.md, P4): guards and loads in a loop whose inputs come from outside
 * it, and whose result nothing in the loop can change, move to a block of their own before the loop's header (its
 * [Block.loopEntry]), which runs once each time the loop is entered instead of once per iteration.
 *
 * - Moved: shape-guarded slot loads, global loads, number checks, closure guards, closure environments and `this` bound
 *   without allocating, loads of the header's own environment, and arithmetic of numbers without an exit, from the
 *   blocks that run on every iteration (those dominating every back edge).
 * - Loads move only out of loops whose instructions cannot run code of their own nor write anything but slots through
 *   a shape ([clean]): there, shapes do not change, so a slot load stays only where the loop stores into the same slot
 *   of the same shape, and an environment load where the loop stores into any environment.
 * - A moved instruction that exits resumes the interpreter at the loop header as the loop is entered: the header's
 *   registers on the edge into it. On-stack replacement at the header runs the entry block's code again, with the
 *   registers it loads (IrCodegen.osrEntries).
 */
internal object Licm {
    fun run(g: Graph, kinds: Kinds) {
        // inner loops first (later in reverse post order): what leaves them can then leave the loops around them
        for (h in g.blocks.filter { it.loopHeader }.asReversed()) hoist(g, h, kinds)
        for ((k, b) in g.blocks.withIndex()) b.id = k
    }

    private fun hoist(g: Graph, h: Block, kinds: Kinds) {
        val regs = h.entryRegs ?: return
        if (h.entryDepth != 0 || h.catchEntry) return
        val index = HashMap<Block, Int>()
        for ((k, b) in g.blocks.withIndex()) index[b] = k
        val hi = index[h]!!
        val latches = h.preds.filter { index[it]!! >= hi }
        val outside = h.preds.filter { index[it]!! < hi }
        if (latches.isEmpty() || outside.size != 1) return
        val pre = outside[0]
        if (pre.succs.count { it === h } != 1) return
        // the loop: the header and the blocks that reach a back edge without passing it
        val body = HashSet<Block>()
        body.add(h)
        val work = ArrayDeque(latches)
        while (work.isNotEmpty()) {
            val b = work.removeLast()
            if (body.add(b)) work.addAll(b.preds)
        }
        var opaque = false
        var envStores = false
        val stores = ArrayList<Pair<Any?, Int>>()
        for (b in body) for (i in b.insts) when {
            i.op == Ir.STORE_SLOT -> stores.add(g.data[i.a] to i.b)
            i.op == Op.STORE_ENV -> envStores = true
            !clean(i, kinds) -> opaque = true
        }
        val idom = IrVerifier.dominators(g, index)
        fun dominates(a: Block, b: Block): Boolean {
            var x: Block? = b
            while (x != null) {
                if (x === a) return true
                val d = idom[x]
                x = if (d == null || d === x || index[d] == null) null else d
            }
            return false
        }
        fun stored(shape: Any?, slot: Int) = stores.any { it.first === shape && it.second == slot }
        fun movable(i: Inst, b: Block): Boolean = when (i.op) {
            Ir.CHECK_CLOSURE, Ir.CHECK_NUMBER, Ir.CHECK_INT32, Ir.CLOSURE_ENV, Ir.IS_CLOSURE, Ir.CHECK_INTRINSIC -> true
            Ir.MATH -> i.inputs.all { JT.isNum(kinds.of(it)) }
            // (after its call's guard, in the same block: moved together)
            Ir.INLINE_THIS -> i.a == 1
            // numbers of numbers, without an exit (ART does not move them where an exit's call is in the loop)
            Op.ADD, Op.SUB, Op.MUL, Op.DIV, Op.MOD, Op.EXP, Op.BAND, Op.BOR, Op.BXOR, Op.SHL, Op.SAR, Op.SHR, Op.NEG,
            Op.BNOT, Op.TO_NUMERIC, Op.TO_NUMBER -> i.state == null && i.inputs.all { JT.isNum(kinds.of(it)) }
            Ir.LOAD_GLOBAL_LEX, Ir.LENGTH -> !opaque
            Ir.LOAD_GLOBAL_SLOT -> !opaque && !stored(g.data[i.a], i.b)
            Ir.LOAD_SLOT -> !opaque && !stored(if (i.c < 0) g.data[i.a] else (g.data[i.c] as Array<*>).last(), i.b)
            // its environment: the header's body's, the same where no instruction of the loop changes the frame's
            Op.LOAD_ENV -> !opaque && !envStores && b.site === h.site
            else -> false
        }
        val e = Block(-1, h.pc, h.site)
        val k = h.preds.indexOf(pre)
        val state = FrameState(h.pc, Array(regs.size) { r -> regs[r].let { v -> if (v is Phi && v.block === h) v.inputs[k] else v } }, h.site)
        fun inside(v: Value) = (v is Inst && v.block in body) || (v is Phi && v.block in body)
        for (b in body.filter { b -> latches.all { dominates(b, it) } }.sortedBy { index[it] }) {
            val it = b.insts.iterator()
            while (it.hasNext()) {
                val i = it.next()
                if (i.isTerminator || i.virtual || !movable(i, b) || i.inputs.any { v -> inside(v) }) continue
                it.remove()
                if (i.origin == null) i.origin = b.site?.code ?: g.code
                i.block = e
                if (i.state != null) i.state = state
                e.insts.add(i)
            }
        }
        if (e.insts.isEmpty()) return
        val goto = Inst(Ir.GOTO, arrayOf<Value>(), 0, 0, 0, h.pc, -1)
        goto.targets = listOf(h)
        goto.block = e
        e.insts.add(goto)
        // an exception there (none but the JVM's own) goes where it would before the loop
        e.handler = if (pre.handler === h.handler) h.handler else null
        e.preds.add(pre)
        pre.term.targets = pre.term.targets.map { if (it === h) e else it }
        h.preds[k] = e
        h.loopEntry = e
        g.blocks.add(hi, e)
    }

    /** Whether [i] can neither run code of its own nor write anything (a slot through a shape aside). */
    private fun clean(i: Inst, kinds: Kinds): Boolean = when (i.op) {
        Op.ADD, Op.SUB, Op.MUL, Op.DIV, Op.MOD, Op.EXP, Op.BAND, Op.BOR, Op.BXOR, Op.SHL, Op.SAR, Op.SHR,
        Op.LT, Op.GT, Op.LE, Op.GE, Op.EQ, Op.NE, Op.NEG, Op.BNOT, Op.INC, Op.DEC, Op.TO_NUMERIC, Op.TO_NUMBER, Ir.MATH ->
            i.inputs.all { JT.isNum(kinds.of(it)) }
        // (a TDZ check throws, which leaves the loop: no write)
        Op.SEQ, Op.SNE, Op.NOT, Op.LOAD_ENV, Op.LOAD_ENV_TDZ, Op.NEW_OBJECT_LITERAL, Ir.CONSTRUCT_RESULT -> true
        // (a class's fields are defined by code)
        Ir.NEW_THIS -> i.a == 0
        Ir.CHECK_NUMBER, Ir.CHECK_INT32, Ir.CHECK_CLOSURE, Ir.LOAD_SLOT, Ir.LENGTH, Ir.LOAD_GLOBAL_LEX, Ir.LOAD_GLOBAL_SLOT,
        Ir.STRESS_EXIT, Ir.CLOSURE_ENV, Ir.IS_CLOSURE, Ir.CHECK_INTRINSIC, Ir.INLINE_THIS, Ir.INLINE_ENTER, Ir.INLINE_EXIT, Ir.SET_HOME, Ir.GOTO, Ir.BRANCH,
        Ir.SWITCH, Ir.RETURN -> true
        else -> false
    }
}
