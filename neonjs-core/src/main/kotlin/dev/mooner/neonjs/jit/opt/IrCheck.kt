package dev.mooner.neonjs.jit.opt

import dev.mooner.neonjs.compiler.Op
import dev.mooner.neonjs.jit.JT

/** Text form of a graph, for tests and debugging. */
object IrPrinter {
    fun print(g: Graph): String {
        val sb = StringBuilder()
        for (b in g.blocks) {
            sb.append(b).append(" (pc ").append(b.pc).append(')')
            if (b.preds.isNotEmpty()) sb.append(" <- ").append(b.preds.joinToString(", "))
            if (b.loopHeader) sb.append(" loop")
            sb.append('\n')
            for (p in b.phis) sb.append("  ").append(p).append(" = phi ").append(p.inputs.joinToString(", ")).append(kind(p)).append('\n')
            for (i in b.insts) {
                sb.append("  ")
                if (i.hasResult) sb.append(i).append(" = ")
                sb.append(Ir.name(i.op))
                if (i.op == Ir.BRANCH) sb.append('(').append(Op.names[i.a]).append(')')
                else if (i.op < Op.COUNT && Op.operands[i.op] > 0) {
                    sb.append(' ').append(i.a)
                    if (Op.operands[i.op] > 1) sb.append(' ').append(i.b)
                } else if (i.op == Ir.PARAM || i.op == Ir.CHECK_TDZ) sb.append(' ').append(i.a)
                if (i.inputs.isNotEmpty()) sb.append(' ').append(i.inputs.joinToString(", "))
                i.state?.let { s -> sb.append(" [pc ").append(s.pc).append(": ").append(s.values.joinToString(", ")).append(']') }
                if (i.targets.isNotEmpty()) sb.append(" -> ").append(i.targets.joinToString(", "))
                if (i.hasResult) sb.append(kind(i))
                if (i.virtual) sb.append(" virtual")
                if (i.wraps) sb.append(" wraps")
                if (i.callee?.quiet == true) sb.append(" quiet")
                sb.append('\n')
            }
        }
        return sb.toString()
    }

    private fun kind(v: Value): String = when (v.kind) {
        JT.NUM -> " :num"
        JT.INT -> " :int"
        JT.BOOL -> " :bool"
        else -> ""
    }
}

/**
 * Checks the invariants the passes and the code generator rely on: every block ends with its one terminator, its
 * predecessors are the blocks whose terminators target it (as often as they do), every phi has an input per
 * predecessor, and every value is defined before its uses on every path (SSA dominance).
 */
object IrVerifier {
    class Error(msg: String) : IllegalStateException(msg)

    fun verify(g: Graph) {
        val index = HashMap<Block, Int>()
        for ((k, b) in g.blocks.withIndex()) index[b] = k
        val defined = HashMap<Value, Block>()
        for (b in g.blocks) {
            if (b.insts.isEmpty() || !b.term.isTerminator) fail("$b does not end with a terminator")
            for ((k, i) in b.insts.withIndex()) {
                if (i.isTerminator && k != b.insts.size - 1) fail("$b: terminator $i before its end")
                if (i.block !== b) fail("$i is not in $b")
                defined[i] = b
            }
            for (p in b.phis) {
                if (p.block !== b) fail("$p is not in $b")
                if (p.inputs.size != b.preds.size) fail("$p has ${p.inputs.size} inputs for ${b.preds.size} predecessors")
                defined[p] = b
            }
            for (s in b.succs) if (index[s] == null) fail("$b targets $s, not in the graph")
        }
        for (b in g.blocks) {
            val expected = g.blocks.flatMap { p -> p.succs.filter { it === b }.map { p } }
            if (expected.sortedBy { index[it] } != b.preds.sortedBy { index[it] }) fail("$b has predecessors ${b.preds}, targeted by $expected")
        }
        val idom = dominators(g, index)
        fun dominates(a: Block, b: Block): Boolean {
            var x: Block? = b
            while (x != null) {
                if (x === a) return true
                val d = idom[x]
                x = if (d === x || d == null) null else d
            }
            return false
        }
        for (b in g.blocks) {
            for (p in b.phis) for ((k, v) in p.inputs.withIndex()) {
                val d = defined[v] ?: continue // a constant
                if (!dominates(d, b.preds[k])) fail("$p: input $v (in $d) does not dominate predecessor ${b.preds[k]}")
            }
            val seen = HashSet<Value>()
            for (i in b.insts) {
                for (v in i.inputs + (i.state?.values ?: emptyArray())) {
                    if (v is Const) continue
                    val d = defined[v] ?: fail("$i uses $v, defined nowhere")
                    if (d === b) {
                        if (v is Inst && v !in seen) fail("$i uses $v before its definition")
                    } else if (!dominates(d, b)) fail("$i uses $v (in $d), which does not dominate $b")
                }
                seen.add(i)
            }
        }
    }

    /**
     * Immediate dominators (Cooper, Harvey and Kennedy). The entry and the catch entries (which exceptions reach) hang
     * from a root that is no block ([ROOT], index -1).
     */
    fun dominators(g: Graph, index0: Map<Block, Int>): Map<Block, Block> {
        val index = HashMap(index0)
        index[ROOT] = -1
        val idom = HashMap<Block, Block>()
        idom[ROOT] = ROOT
        val roots = g.blocks.filter { it === g.entry || it.preds.isEmpty() }
        for (r in roots) idom[r] = ROOT
        var changed = true
        while (changed) {
            changed = false
            for (b in g.blocks) {
                if (b in roots) continue
                var d: Block? = null
                for (p in b.preds) {
                    if (idom[p] == null) continue
                    d = if (d == null) p else intersect(p, d, idom, index)
                }
                if (d != null && idom[b] !== d) {
                    idom[b] = d
                    changed = true
                }
            }
        }
        return idom
    }

    private val ROOT = Block(-1, -1)

    private fun intersect(a0: Block, b0: Block, idom: Map<Block, Block>, index: Map<Block, Int>): Block {
        var a = a0
        var b = b0
        while (a !== b) {
            while (index[a]!! > index[b]!!) a = idom[a]!!
            while (index[b]!! > index[a]!!) b = idom[b]!!
        }
        return a
    }

    private fun fail(msg: String): Nothing = throw Error(msg)
}
