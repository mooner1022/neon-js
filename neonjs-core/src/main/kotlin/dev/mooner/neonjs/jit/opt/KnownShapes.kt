package dev.mooner.neonjs.jit.opt

import dev.mooner.neonjs.compiler.Op
import dev.mooner.neonjs.jit.JT

/**
 * What the shape checks established, used again (docs/OPTIMIZING_TIER.md, P4). A LOAD_SLOT, STORE_SLOT or ADD_SLOT past
 * its checks makes facts about its object: its shape (an ADD_SLOT's: the one it moved to), that it is extensible (an
 * ADD_SLOT checked it; NewThis makes such an object), the prototypes' shapes it checked; and the value of a slot read,
 * written or added, by object and slot (and, read from a prototype, by the receiver's shape and the prototypes'): a
 * slot of an object holds one property while its shape only moves on by additions. The facts hold until something may change shapes or run code (any instruction
 * but those [keeps] knows), an ADD_SLOT of another object of the same shape (that object, perhaps: it moved on), and,
 * for the prototypes' shapes, an ADD_SLOT of any other object (one of the prototypes, perhaps); a STORE_SLOT ends the
 * values held of its slot. A number check (CHECK_NUMBER, CHECK_INT32) of a value checked so before is that check.
 *
 * A check whose fact holds is not made ([Inst.known]; with none left, no exit), and a LOAD_SLOT of a value held is that
 * value. A block with one predecessor starts with its facts, one with several (all built before it) with those all of
 * them hold, and a loop header (its back edges are built after it) or a catch entry with none.
 */
internal object KnownShapes {
    /** [Inst.known] bits: the receiver's shape, that it is extensible, the prototypes' shapes. */
    const val SHAPE = 1
    const val EXTENSIBLE = 2
    const val CHAIN = 4

    private data class Fact(val shape: Any?, val extensible: Boolean, val chain: List<Any?>?)
    /** A slot's value: an own slot's by object and slot, a prototype's by the receiver, its shape and the prototypes'. */
    private data class Key(val obj: Value, val shape: Any?, val slot: Int, val chain: List<Any?>?)
    private class Facts(
        val objs: HashMap<Value, Fact> = HashMap(),
        val loads: HashMap<Key, Value> = HashMap(),
        val checks: HashMap<Pair<Int, Value>, Value> = HashMap(),
    ) {
        fun copy() = Facts(HashMap(objs), HashMap(loads), HashMap(checks))
    }

    /**
     * Applies the facts to [g]; [replace] puts a value held in place of a LOAD_SLOT or a number check (removed). Returns
     * whether one was.
     */
    fun run(g: Graph, kinds: Kinds, replace: (Inst, Value) -> Unit): Boolean {
        val out = HashMap<Block, Facts>()
        // (replaced here, the builder resolves the uses after)
        val sub = HashMap<Value, Value>()
        fun r(v: Value): Value = sub[v] ?: v
        fun drop(i: Inst, held: Value, it: MutableIterator<Inst>) {
            replace(i, held)
            sub[i] = held
            it.remove()
        }
        for (b in g.blocks) {
            val f = entry(b, out)
            val it = b.insts.iterator()
            while (it.hasNext()) {
                val i = it.next()
                when (i.op) {
                    Ir.CHECK_NUMBER, Ir.CHECK_INT32 -> {
                        val k = i.op to r(i.inputs[0])
                        val held = f.checks[k]
                        if (held != null) drop(i, held, it) else f.checks[k] = i
                    }
                    Ir.LOAD_SLOT, Ir.STORE_SLOT -> {
                        val obj = r(i.inputs[0])
                        val shape = g.data[i.a]
                        val chain = chainOf(g, i.c)
                        val key = Key(obj, if (chain == null) null else shape, i.b, chain)
                        if (i.op == Ir.LOAD_SLOT) {
                            // (the value read before, unless written since: also where the object's shape changed since
                            // through an ADD_SLOT, which leaves the values; no check is made, so no fact either)
                            val held = f.loads[key]
                            if (held != null) {
                                drop(i, held, it)
                                continue
                            }
                        }
                        val fact = f.objs[obj]
                        val same = fact != null && fact.shape === shape
                        var known = if (same) SHAPE else 0
                        if (chain == null || (same && fact!!.chain == chain)) known = known or CHAIN
                        mark(i, known, SHAPE or CHAIN)
                        f.objs[obj] = Fact(shape, same && fact!!.extensible, chain ?: if (same) fact!!.chain else null)
                        if (i.op == Ir.LOAD_SLOT) f.loads[key] = i
                        else {
                            // (another object written there may be this one)
                            f.loads.keys.removeIf { k -> k.slot == i.b }
                            f.loads[key] = r(i.inputs[1])
                        }
                    }
                    Ir.ADD_SLOT -> {
                        val obj = r(i.inputs[0])
                        val shape = g.data[i.a]
                        val chain = chainOf(g, i.c)
                        val fact = f.objs[obj]
                        val same = fact != null && fact.shape === shape
                        var known = if (same) SHAPE else 0
                        if (fact != null && fact.extensible) known = known or EXTENSIBLE
                        if (chain == null || (same && fact!!.chain == chain)) known = known or CHAIN
                        mark(i, known, SHAPE or EXTENSIBLE or CHAIN)
                        // another object of that shape may be this one, and any other's prototypes may include it
                        f.objs.entries.removeIf { (k, v) -> k !== obj && v.shape === shape }
                        for (e in f.objs.entries) if (e.value.chain != null) e.setValue(e.value.copy(chain = null))
                        val next = g.data[i.b] as dev.mooner.neonjs.runtime.Shape
                        f.objs[obj] = Fact(next, true, chain)
                        // (appended: the last slot of the shape it moved to)
                        f.loads[Key(obj, null, next.size - 1, null)] = r(i.inputs[1])
                    }
                    // a new object: extensible (a class's field initializers run code, which may change that)
                    Ir.NEW_THIS -> if (i.a == 0) f.objs[i] = Fact(null, true, null) else {
                        f.objs.clear()
                        f.loads.clear()
                    }
                    else -> if (!keeps(i, kinds)) {
                        f.objs.clear()
                        f.loads.clear()
                    }
                }
            }
            out[b] = f
        }
        return sub.isNotEmpty()
    }

    /** [i] needs no check of [known]; with all of [needed] known, it has no exit. */
    private fun mark(i: Inst, known: Int, needed: Int) {
        i.known = known
        if (known and needed == needed) i.state = null
    }

    /** The prototypes' shapes a check compares (the graph's data [c], an array), or null for none. */
    private fun chainOf(g: Graph, c: Int): List<Any?>? = if (c < 0) null else (g.data[c] as Array<*>).toList()

    private fun entry(b: Block, out: Map<Block, Facts>): Facts {
        if (b.catchEntry || b.preds.isEmpty()) return Facts()
        val ins = b.preds.map { out[it] ?: return Facts() }
        val f = ins[0].copy()
        for (o in ins.subList(1, ins.size)) {
            f.objs.entries.removeIf { (k, v) -> o.objs[k] != v }
            f.loads.entries.removeIf { (k, v) -> o.loads[k] !== v }
            f.checks.entries.removeIf { (k, v) -> o.checks[k] !== v }
        }
        return f
    }

    /** Whether [i] can neither run code nor change an object's shape (nor write a slot). */
    private fun keeps(i: Inst, kinds: Kinds): Boolean = when (i.op) {
        // numbers only: no valueOf
        Op.ADD, Op.SUB, Op.MUL, Op.DIV, Op.MOD, Op.EXP, Op.BAND, Op.BOR, Op.BXOR, Op.SHL, Op.SAR, Op.SHR,
        Op.LT, Op.GT, Op.LE, Op.GE, Op.EQ, Op.NE, Op.NEG, Op.BNOT, Op.INC, Op.DEC, Op.TO_NUMERIC, Op.TO_NUMBER, Ir.MATH ->
            i.inputs.all { JT.isNum(kinds.of(it)) }
        // (a TDZ check throws: to a catch entry, which starts without facts)
        Op.SEQ, Op.SNE, Op.NOT, Op.LOAD_ENV, Op.LOAD_ENV_TDZ, Op.STORE_ENV, Op.NEW_OBJECT, Op.NEW_ARRAY, Op.ARRAY_PUSH,
        Op.ARRAY_HOLE, Op.NEW_OBJECT_LITERAL, Ir.CONSTRUCT_RESULT, Ir.PARAM, Ir.CHECK_TDZ, Ir.CHECK_NUMBER, Ir.CHECK_INT32,
        Ir.CHECK_CLOSURE, Ir.LENGTH, Ir.LOAD_GLOBAL_LEX, Ir.LOAD_GLOBAL_SLOT, Ir.STRESS_EXIT, Ir.CLOSURE_ENV, Ir.IS_CLOSURE,
        Ir.CHECK_INTRINSIC, Ir.INLINE_THIS, Ir.INLINE_ENTER, Ir.INLINE_EXIT, Ir.SET_HOME, Ir.LOAD_HOME, Ir.CATCH_VALUE,
        Ir.GOTO, Ir.BRANCH, Ir.SWITCH -> true
        else -> false
    }
}
