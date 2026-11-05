package dev.mooner.neonjs.jit.opt

/**
 * What the shape checks established, used again (docs/OPTIMIZING_TIER.md, P4). A LOAD_SLOT, STORE_SLOT or ADD_SLOT past
 * its checks makes facts about its object: its shape (an ADD_SLOT's: the one it moved to), that it is extensible (an
 * ADD_SLOT checked it; NewThis makes such an object), the prototypes' shapes it checked; and the value of a slot read,
 * written or added, by object and slot (and, read from a prototype, by the receiver's shape and the prototypes'): a
 * slot of an object holds one property while its shape only moves on by additions. The facts hold until something may change shapes or run code (any instruction
 * but those [keeps] knows), an ADD_SLOT of another object of the same shape (that object, perhaps: it moved on), and,
 * for the prototypes' shapes and the values read from them, an ADD_SLOT of any other object (one of the prototypes,
 * perhaps, its new property hiding the one read); a STORE_SLOT ends the
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

    /** JavaScript's facts for [g] (with kinds [kinds]: which instructions keep them). */
    fun rules(g: Graph, kinds: Kinds): FactRules = Rules(g, kinds)

    /** What is known of an object: its shape, whether it is extensible, its prototypes' shapes. */
    private data class Obj(val v: Value)
    private data class Fact(val shape: Any?, val extensible: Boolean, val chain: List<Any?>?)
    /** A slot's value: an own slot's by object and slot, a prototype's by the receiver, its shape and the prototypes'. */
    private data class Key(val obj: Value, val shape: Any?, val slot: Int, val chain: List<Any?>?)
    /** A number check of a value: the check (CHECK_NUMBER or CHECK_INT32) and the value. */
    private data class Check(val op: Int, val v: Value)

    private class Rules(private val g: Graph, private val kinds: Kinds) : FactRules {
        override fun transfer(i: Inst, facts: HashMap<Any, Any>, ctx: Facts.Ctx) {
            fun objs(v: Value) = facts[Obj(v)] as Fact?
            when (i.op) {
                Ir.CHECK_NUMBER, Ir.CHECK_INT32 -> {
                    val k = Check(i.op, ctx.r(i.inputs[0]))
                    val held = facts[k]
                    if (held != null) ctx.drop(held as Value) else facts[k] = i
                }
                Ir.LOAD_SLOT, Ir.STORE_SLOT -> {
                    val obj = ctx.r(i.inputs[0])
                    val shape = g.data[i.a]
                    val chain = chainOf(g, i.c)
                    val key = Key(obj, if (chain == null) null else shape, i.b, chain)
                    if (i.op == Ir.LOAD_SLOT) {
                        // (the value read before, unless written since: also where the object's shape changed since
                        // through an ADD_SLOT, which leaves the values; no check is made, so no fact either)
                        val held = facts[key]
                        if (held != null) {
                            ctx.drop(held as Value)
                            return
                        }
                    }
                    val fact = objs(obj)
                    val same = fact != null && fact.shape === shape
                    var known = if (same) SHAPE else 0
                    if (chain == null || (same && fact!!.chain == chain)) known = known or CHAIN
                    mark(i, known, SHAPE or CHAIN)
                    facts[Obj(obj)] = Fact(shape, same && fact!!.extensible, chain ?: if (same) fact!!.chain else null)
                    if (i.op == Ir.LOAD_SLOT) facts[key] = i
                    else {
                        // (another object written there may be this one)
                        facts.keys.removeIf { k -> k is Key && k.slot == i.b }
                        facts[key] = ctx.r(i.inputs[1])
                    }
                }
                Ir.ADD_SLOT -> {
                    val obj = ctx.r(i.inputs[0])
                    val shape = g.data[i.a]
                    val chain = chainOf(g, i.c)
                    val fact = objs(obj)
                    val same = fact != null && fact.shape === shape
                    var known = if (same) SHAPE else 0
                    if (fact != null && fact.extensible) known = known or EXTENSIBLE
                    if (chain == null || (same && fact!!.chain == chain)) known = known or CHAIN
                    mark(i, known, SHAPE or EXTENSIBLE or CHAIN)
                    // another object of that shape may be this one, and any other's prototypes may include it: then
                    // the property added hides the one a prototype's slot held for its receivers
                    facts.entries.removeIf { (k, v) -> k is Obj && k.v !== obj && (v as Fact).shape === shape }
                    for (e in facts.entries) {
                        val v = e.value
                        if (e.key is Obj && (v as Fact).chain != null) e.setValue(v.copy(chain = null))
                    }
                    facts.keys.removeIf { k -> k is Key && k.chain != null }
                    val next = g.data[i.b] as dev.mooner.neonjs.runtime.Shape
                    facts[Obj(obj)] = Fact(next, true, chain)
                    // (appended: the last slot of the shape it moved to)
                    facts[Key(obj, null, next.size - 1, null)] = ctx.r(i.inputs[1])
                }
                // a new object: extensible (a class's field initializers run code, which may change that)
                Ir.NEW_THIS -> if (i.a == 0) facts[Obj(i)] = Fact(null, true, null) else forgetObjects(facts)
                else -> if (!g.ops.keepsObjects(i, kinds)) forgetObjects(facts)
            }
        }
    }

    /** Something may change shapes or run code: what is known of objects goes (number checks stay). */
    private fun forgetObjects(facts: HashMap<Any, Any>) {
        facts.keys.removeIf { it is Obj || it is Key }
    }

    /** [i] needs no check of [known]; with all of [needed] known, it has no exit. */
    private fun mark(i: Inst, known: Int, needed: Int) {
        i.known = known
        if (known and needed == needed) i.state = null
    }

    /** The prototypes' shapes a check compares (the graph's data [c], an array), or null for none. */
    private fun chainOf(g: Graph, c: Int): List<Any?>? = if (c < 0) null else (g.data[c] as Array<*>).toList()
}
