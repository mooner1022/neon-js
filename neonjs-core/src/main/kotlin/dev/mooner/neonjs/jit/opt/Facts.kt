package dev.mooner.neonjs.jit.opt

/**
 * Facts after guards (docs/OPTIMIZING_TIER.md, P4), whatever the language: what a check established holds along each
 * path until something may change it, so a later check of it is not made and a load of a value known is that value.
 * The facts are the language's ([FactRules]): this pass walks the blocks in reverse post order with them.
 *
 * A block with one predecessor starts with its facts, one with several (all built before it) with those all of them
 * hold, and a loop header (its back edges are built after it), a catch entry or a resumption with none.
 */
object Facts {
    /**
     * What an instruction is given to change the facts with: a value an earlier one stood for ([r]: the value held in
     * place of one dropped), and [drop], which puts a value known in place of the instruction (removed).
     */
    class Ctx internal constructor(private val sub: HashMap<Value, Value>) {
        internal var dropped: Value? = null

        fun r(v: Value): Value = sub[v] ?: v

        fun drop(held: Value) {
            dropped = held
        }
    }

    /**
     * Applies [rules]' facts to [g]; [replace] puts a value held in place of an instruction dropped. Returns whether one
     * was.
     */
    fun run(g: Graph, rules: FactRules, replace: (Inst, Value) -> Unit): Boolean {
        val out = HashMap<Block, HashMap<Any, Any>>()
        // (replaced here, the builder resolves the uses after)
        val sub = HashMap<Value, Value>()
        val ctx = Ctx(sub)
        for (b in g.blocks) {
            val f = entry(b, out)
            val it = b.insts.iterator()
            while (it.hasNext()) {
                val i = it.next()
                ctx.dropped = null
                rules.transfer(i, f, ctx)
                ctx.dropped?.let { held ->
                    replace(i, held)
                    sub[i] = held
                    it.remove()
                }
            }
            out[b] = f
        }
        return sub.isNotEmpty()
    }

    private fun entry(b: Block, out: Map<Block, HashMap<Any, Any>>): HashMap<Any, Any> {
        // (what was known before a suspension may have changed while the activation was suspended)
        if (b.catchEntry || b.resumeEntry || b.preds.isEmpty()) return HashMap()
        val ins = b.preds.map { out[it] ?: return HashMap() }
        val f = HashMap(ins[0])
        for (o in ins.subList(1, ins.size)) f.entries.removeIf { (k, v) -> o[k] != v }
        return f
    }
}

/**
 * A language's facts after guards ([Facts]): keys and what they hold, in one map (the keys of different facts of
 * different classes, values compared with `equals`).
 */
interface FactRules {
    /** Instruction [i] with [facts] holding before it: changes them as it establishes or may end some. */
    fun transfer(i: Inst, facts: HashMap<Any, Any>, ctx: Facts.Ctx)
}
