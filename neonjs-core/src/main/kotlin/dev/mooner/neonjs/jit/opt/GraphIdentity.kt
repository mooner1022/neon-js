package dev.mooner.neonjs.jit.opt

import dev.mooner.neonjs.runtime.Null
import dev.mooner.neonjs.runtime.Undefined
import java.io.DataOutputStream

/**
 * The identity of a baseline's graph (T2.compileBaselines): a hash of everything the code generator reads, so that two
 * graphs of the same identity get the same class, which is then generated once. What a block holds of its own (its
 * code blocks, constants arrays: the graph's data) is not in it: the instance gets it ([Graph.data]), indexed the same.
 *
 * Read: the blocks (order, pcs, predecessors, loop and handler structure, the entries of on-stack replacement and of
 * resumption), each phi and instruction (operation, operands, position, kind, inputs, state, targets, flags), the
 * calls compiled inlined, constants (a number's value, a constant read from an array by index and array), the graph's
 * data by type, and what the language adds ([Dialect.identityExtras]). Anything else a code generator came to read
 * would have to be added here: `-Dneonjs.jit.baselineCheck` generates every class anyway and compares.
 */
internal object GraphIdentity {
    /** Changes when what is hashed, or the code generated from it, changes. */
    private const val FORMAT = 1

    fun of(g: Graph, classFileVersion: Int): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val out = DataOutputStream(java.security.DigestOutputStream(NullOutput, md))
        out.writeInt(FORMAT)
        out.writeInt(classFileVersion)
        out.writeBoolean(g.startsInterpreted)
        g.dialect.identityExtras(g, out)
        // every value and block numbered first (a value may be used before the block defining it, through a phi)
        val ids = HashMap<Value, Int>()
        val blocks = HashMap<Block, Int>()
        for ((k, b) in g.blocks.withIndex()) {
            blocks[b] = k
            for (p in b.phis) ids[p] = ids.size
            for (i in b.insts) ids[i] = ids.size
        }
        val sites = LinkedHashMap<InlineSite, Int>()
        fun site(s: InlineSite?): Int {
            if (s == null) return -1
            sites[s]?.let { return it }
            site(s.parent)
            site(s.callerState.site)
            sites[s] = sites.size
            return sites[s]!!
        }
        fun block(b: Block?) = out.writeInt(if (b == null) -1 else blocks[b] ?: -2)
        fun codeRef(cb: dev.mooner.neonjs.compiler.CodeBlock?) =
            out.writeInt(if (cb == null || cb === g.code) -1 else g.dataIndex(cb.constants))
        fun value(v: Value) {
            if (v !is Const) {
                out.writeByte('v'.code)
                out.writeInt(ids[v] ?: -1)
                return
            }
            val c = v.value
            when {
                c === Undefined -> out.writeByte('u'.code)
                c === Null -> out.writeByte('n'.code)
                c == null -> out.writeByte('0'.code)
                c is Boolean -> { out.writeByte('b'.code); out.writeBoolean(c) }
                v.k >= 0 -> { out.writeByte('k'.code); out.writeInt(v.k); codeRef(v.code) }
                c is Double -> { out.writeByte('d'.code); out.writeLong(java.lang.Double.doubleToRawLongBits(c)) }
                // (a value of another type, as its class and text: never shared where the text is an identity)
                else -> { out.writeByte('o'.code); out.writeUTF(c.javaClass.name); out.writeUTF(c.toString().take(200)) }
            }
        }
        fun values(vs: Array<Value>) {
            out.writeInt(vs.size)
            for (v in vs) value(v)
        }
        fun state(s: FrameState?) {
            if (s == null) return out.writeInt(-1)
            out.writeInt(s.pc)
            values(s.values)
            out.writeInt(site(s.site))
        }
        for (b in g.blocks) {
            out.writeInt(b.pc)
            out.writeInt(site(b.site))
            out.writeInt((if (b.loopHeader) 1 else 0) or (if (b.catchEntry) 2 else 0) or (if (b.resumeEntry) 4 else 0))
            block(b.handler)
            block(b.loopEntry)
            block(b.twin)
            block(b.dispatch)
            block(b.twinOf)
            val regs = b.entryRegs
            if (regs == null) out.writeInt(-1) else values(regs)
            out.writeInt(b.entryDepth)
            out.writeInt(b.preds.size)
            for (p in b.preds) block(p)
            out.writeInt(b.phis.size)
            for (p in b.phis) {
                out.writeByte(p.kind.toInt())
                out.writeInt(p.inputs.size)
                for (v in p.inputs) value(v)
            }
            out.writeInt(b.insts.size)
            for (i in b.insts) {
                out.writeInt(i.op)
                out.writeInt(i.a)
                out.writeInt(i.b)
                out.writeInt(i.c)
                out.writeInt(i.pc)
                out.writeInt(i.stmt)
                out.writeByte(i.kind.toInt())
                out.writeInt((if (i.hasResult) 1 else 0) or (if (i.virtual) 2 else 0) or (if (i.wraps) 4 else 0))
                out.writeInt(i.known)
                values(i.inputs)
                state(i.state)
                out.writeInt(i.targets.size)
                for (t in i.targets) block(t)
                out.writeInt(site(i.callee))
            }
        }
        // the calls compiled inlined, in the order first met
        out.writeInt(sites.size)
        for ((s, _) in sites.entries.toList()) {
            out.writeInt(site(s.parent))
            codeRef(s.code)
            out.writeInt(s.callPc)
            state(s.callerState)
            values(s.frame)
            out.writeInt(s.flags)
            out.writeInt(s.depth)
            out.writeBoolean(s.quiet)
        }
        // the data the code reads, by type (what it casts them to)
        out.writeInt(g.data.size)
        for (d in g.data) out.writeUTF(d?.javaClass?.name ?: "null")
        out.flush()
        return md.digest().copyOf(16).joinToString("") { "%02x".format(it) }
    }

    private object NullOutput : java.io.OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }
}
