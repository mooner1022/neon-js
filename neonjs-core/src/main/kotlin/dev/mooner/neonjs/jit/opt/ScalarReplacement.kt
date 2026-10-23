package dev.mooner.neonjs.jit.opt

import dev.mooner.neonjs.compiler.CodeBlock
import dev.mooner.neonjs.compiler.Op
import dev.mooner.neonjs.runtime.ObjectLiteralSite
import dev.mooner.neonjs.runtime.PropSite

/**
 * Scalar replacement of object literals (docs/OPTIMIZING_TIER.md, P4): a NEW_OBJECT_LITERAL whose object is only read,
 * by GET_PROP (or LOAD_SLOT) of keys it has, and otherwise only held by FrameStates, is not allocated. Its reads become
 * the values it was made of (a literal's keys are static and distinct, its properties writable data ones, so a read of
 * one of them is that value while nothing writes it); the instruction stays as a virtual object ([Inst.virtual]) that
 * an exit allocates, once, where the interpreter needs it (IrCodegen).
 *
 * Anything else makes the object escape: a write, an input of any other instruction (a call, a store into another
 * object, a return), a phi, a register an on-stack replacement entry loads.
 */
internal object ScalarReplacement {
    /** Replaces what it can in [g]; returns whether anything changed. */
    fun run(g: Graph): Boolean {
        val candidates = HashMap<Inst, Array<Any>>()
        for (b in g.blocks) for (i in b.insts) {
            if (i.op != Op.NEW_OBJECT_LITERAL) continue
            val site = codeOf(g, i).constants[i.a] as? ObjectLiteralSite ?: continue
            if (site.keys.size == i.inputs.size) candidates[i] = site.keys
        }
        if (candidates.isEmpty()) return false
        val escaped = HashSet<Inst>()
        val reads = HashMap<Inst, Inst>() // read -> the literal it reads
        for (b in g.blocks) {
            for (p in b.phis) for (v in p.inputs) if (v is Inst && v in candidates) escaped.add(v)
            b.entryRegs?.let { regs -> for (v in regs) if (v is Inst && v in candidates) escaped.add(v) }
            for (i in b.insts) for ((k, v) in i.inputs.withIndex()) {
                if (v !is Inst || v !in candidates) continue
                val key = if (k == 0) readKey(g, i) else null
                if (key != null && candidates[v]!!.any { it == key }) reads[i] = v else escaped.add(v)
            }
        }
        val replaced = HashMap<Value, Value>()
        for ((read, lit) in reads) {
            if (lit in escaped) continue
            val keys = candidates[lit]!!
            val key = readKey(g, read)!!
            replaced[read] = lit.inputs[keys.indexOfLast { it == key }]
        }
        val virtual = candidates.keys.filter { it !in escaped }
        if (virtual.isEmpty()) return false
        for (v in virtual) v.virtual = true
        fun r(v: Value): Value {
            var x = v
            while (true) x = replaced[x] ?: return x
        }
        val sites = HashSet<InlineSite>()
        fun resolveSite(s0: InlineSite?) {
            var s = s0
            while (s != null && sites.add(s)) {
                val st = s.callerState
                for (k in st.values.indices) st.values[k] = r(st.values[k])
                for (k in s.args.indices) s.args[k] = r(s.args[k])
                s.fn = r(s.fn)
                s.thisValue = r(s.thisValue)
                s.env = r(s.env)
                s = s.parent
            }
        }
        for (b in g.blocks) {
            b.insts.removeAll { it in replaced }
            for (p in b.phis) for (k in p.inputs.indices) p.inputs[k] = r(p.inputs[k])
            for (i in b.insts) {
                for (k in i.inputs.indices) i.inputs[k] = r(i.inputs[k])
                i.state?.let { s ->
                    for (k in s.values.indices) s.values[k] = r(s.values[k])
                    resolveSite(s.site)
                }
            }
            resolveSite(b.site)
        }
        return true
    }

    /** The code block [i] belongs to: its inlined body's, or the function compiled's. */
    fun codeOf(g: Graph, i: Inst): CodeBlock = i.block?.site?.code ?: g.code

    /** The key [i] reads from its first input when it is a GET_PROP, or a LOAD_SLOT of an own property; else null. */
    private fun readKey(g: Graph, i: Inst): Any? {
        val code = codeOf(g, i)
        return when (i.op) {
            Op.GET_PROP -> (code.constants[i.a] as? PropSite)?.key
            // made from the GET_PROP at its pc
            Ir.LOAD_SLOT -> if (i.c >= 0) null else (code.constants[code.code[i.pc + 1]] as? PropSite)?.key
            else -> null
        }
    }
}
