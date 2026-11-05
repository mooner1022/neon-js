package dev.mooner.neonjs.jit.opt

/**
 * The kinds of the IR's values ([KindSystem]): a forward fixpoint in which phis start with no kind and take the join
 * of their inputs' (kinds only rise), and every instruction's result is the one its language's kind system gives for
 * its inputs' kinds. A register's home (exception handlers) holds the join of what is stored in it.
 */
internal class Kinds(@JvmField val system: KindSystem) {
    /** Kind of a constant. */
    fun constKind(v: Any?): Byte = system.constKind(v)

    /** Kind of [v] (a constant's, or what [infer] gave it). */
    fun of(v: Value): Byte = if (v is Const) system.constKind(v.value) else v.kind

    /** Kind of the result of [i] given its inputs' kinds. */
    fun result(i: Inst): Byte = system.resultKind(i, this)

    /** Gives every phi and instruction its kind. */
    fun infer(g: Graph) {
        val none: Byte = -1
        val any = system.any
        for (b in g.blocks) {
            for (p in b.phis) p.kind = none
            for (i in b.insts) i.kind = if (i.hasResult) none else any
        }
        // a register's home holds every value SET_HOME stores in it: their join
        val homes = HashMap<Int, Byte>()
        var changed = true
        while (changed) {
            changed = false
            homes.clear()
            for (b in g.blocks) for (i in b.insts) if (i.op == Ir.SET_HOME) {
                val k = of(i.inputs[0])
                if (k == none) continue
                val h = homes[i.a]
                homes[i.a] = if (h == null) k else system.join(h, k)
            }
            for (b in g.blocks) {
                for (p in b.phis) {
                    var k = none
                    for (v in p.inputs) {
                        val vk = of(v)
                        if (vk == none) continue
                        k = if (k == none) vk else system.join(k, vk)
                    }
                    if (k != p.kind) {
                        p.kind = k
                        changed = true
                    }
                }
                for (i in b.insts) {
                    if (!i.hasResult) continue
                    // an input without a kind yet (a phi of a loop not seen through): wait for it
                    if (i.inputs.any { of(it) == none }) continue
                    val k = if (i.op == Ir.LOAD_HOME) homes[i.a] ?: any else result(i)
                    if (k != i.kind) {
                        i.kind = k
                        changed = true
                    }
                }
            }
        }
        // a phi whose inputs never got a kind only merges itself: nothing reads a number from it
        for (b in g.blocks) {
            for (p in b.phis) if (p.kind == none) p.kind = any
            for (i in b.insts) if (i.kind == none) i.kind = any
        }
    }
}
