package dev.mooner.neonjs.jit.opt

import dev.mooner.neonjs.jit.*
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.tree.IincInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode


/**
 * Generates `CompiledCode.run` for a [Graph], whatever its language: every value used gets a JVM local of its kind's
 * representation ([KindSystem.rep]), blocks become labels, and a phi's inputs are copied into its local at the end of
 * each predecessor (all loaded, then all stored: a parallel copy through the JVM stack). The JVM operand stack is
 * empty at every block boundary. The language's subclass gives each instruction its code ([inst]) and the tests of
 * branches; exits, on-stack replacement entries, interrupt checks on back edges and the size of the code are the
 * core's.
 *
 * Like the baseline, the code takes a frame made for compiled code from the start, and an interpreted one at a loop
 * header with an empty operand stack (on-stack replacement), where each register's value is loaded from the frame's
 * slots into the local of the value the register holds there; `f.pc` is the current statement whenever something can
 * throw or call; loop back edges count down the interrupt budget.
 */
abstract class IrCodegen(
    protected val g: Graph,
    mv: MethodVisitor,
    protected val kinds: Kinds,
    /** The class being generated (its exit methods, [exits], are added to it after `run`). */
    private val cw: org.objectweb.asm.ClassWriter,
    private val className: String,
) : JvmEmitter(mv) {
    private var next = L_REGS
    override fun alloc(n: Int): Int = next.also { next += n }

    protected val local = HashMap<Value, Int>()
    protected val label = HashMap<Block, Label>()
    protected val index = HashMap<Block, Int>()
    /** The language's kinds: how each value is held. */
    protected val ks = kinds.system
    /** The statement position stored in Frame.pc since the current block started (-2: none). */
    protected var storedPc = -2
    /** The inlined call whose body the block being generated belongs to (null: the function compiled). */
    protected var curSite: InlineSite? = null
    /** The locals holding T2Code.data and each code block's constants (the function compiled's: L_CONSTS). */
    protected var dataLocal = -1
    protected val constsLocal = HashMap<dev.mooner.neonjs.compiler.CodeUnit, Int>()

    /** The exits reached from `run`: the label of each, its state, and whether an assumption failed there. */
    /** An exit, or ([suspend]) a suspension, which writes the state to the frame and returns SUSPENDED instead. */
    protected class Exit(
        val label: Label, val state: FrameState, val counted: Boolean, val spec: Int = -1, val suspend: Inst? = null,
        /** A move to a later version of the code at a loop header (Graph.tierUp), with the header's registers. */
        val tierUp: Boolean = false,
    )

    /** The exit of a speculation [i] makes (counted, and noted where it failed: T2Code.exit). */
    protected fun specExit(l: Label, i: Inst): Exit {
        val code = i.origin ?: i.block!!.site?.code ?: g.code
        return Exit(l, i.state!!, counted = true, spec = g.dataIndex(Speculation(code, if (i.pc < 0) code.code.size else i.pc)))
    }
    protected val exits = ArrayList<Exit>()

    /** The end of each block's code, and the local and kind of each register's home (exception handlers). */
    private val endLabel = HashMap<Block, Label>()
    protected val homeLocal = HashMap<Int, Int>()
    protected val homeKind = HashMap<Int, Byte>()

    fun emit() {
        for ((k, b) in g.blocks.withIndex()) {
            index[b] = k
            label[b] = Label()
            endLabel[b] = Label()
        }
        // each block a handler's range covers is in the JVM's exception table (an inlined body's: its call's handler);
        // exits come after the body, out of every range
        for (b in g.blocks) b.handler?.let { h -> mv.visitTryCatchBlock(label[b], endLabel[b], label[h], "java/lang/Throwable") }
        for (b in g.blocks) for (i in b.insts) if (i.op == Ir.SET_HOME) {
            val k = kindOf(i.inputs[0])
            homeKind[i.a] = homeKind[i.a]?.let { ks.join(it, k) } ?: k
        }
        allocate()
        prologue()
        osrEntries()
        for (b in g.blocks) emitBlock(b)
        for ((n, e) in exits.withIndex()) emitExit(n, e)
    }

    // ------------------------------------------------------------------ exits

    /**
     * The code at an exit, after the body: `this.exitN(f, values...)` with the state's values as they are held (its
     * constants are the method's own), returning what it returns. The method (built after `run`, which stays smaller
     * for it) boxes them into the interpreter's registers and stack and leaves for the interpreter: T2Code.exit for an
     * assumption that failed (counted against the code), JitRt.deopt for the stress mode's.
     */
    private fun emitExit(n: Int, e: Exit) {
        mv.visitLabel(e.label)
        val params = expand(e.state.values.asList() + (e.state.site?.values() ?: emptyList()) + (e.suspend?.inputs?.asList() ?: emptyList()))
        mv.visitVarInsn(ALOAD, 0)
        frame()
        for (v in params) load(v, v.kind)
        val desc = "($frameDesc" + params.joinToString("") { typeOf(it.kind) } + ")$OBJ"
        mv.visitMethodInsn(INVOKESPECIAL, className, "exit$n", desc, false)
        mv.visitInsn(ARETURN)
        exitMethod(n, e, params, desc)
    }

    /** [values] without constants, a virtual allocation standing for its inputs (recursively), each once. */
    private fun expand(values: List<Value>): List<Value> {
        val out = LinkedHashSet<Value>()
        fun add(v: Value) {
            if (v is Const) return
            if (v is Inst && v.virtual) v.inputs.forEach { add(it) } else out.add(v)
        }
        values.forEach { add(it) }
        return out.toList()
    }

    private fun typeOf(kind: Byte) = ks.rep(kind).desc

    private fun exitMethod(n: Int, e: Exit, params: List<Value>, desc: String) {
        val m = cw.visitMethod(ACC_PRIVATE or ACC_FINAL, "exit$n", desc, null, null)
        m.visitCode()
        val slot = HashMap<Value, Int>()
        var next = 2 // this, the frame
        for (v in params) {
            slot[v] = next
            next += ks.rep(v.kind).slots
        }
        val firstFree = next
        val em = newEmitter(m, firstFree)
        val made = HashMap<Value, Int>()
        fun value(v: Value) {
            if (v is Const) constObject(em, v)
            else if (v is Inst && v.virtual) {
                // an object not allocated: allocated now from its inputs (an array of them), once for every place the
                // state holds it
                made[v]?.let { m.visitVarInsn(ALOAD, it); return }
                em.iconst(v.inputs.size)
                m.visitTypeInsn(ANEWARRAY, "java/lang/Object")
                for ((j, x) in v.inputs.withIndex()) {
                    m.visitInsn(DUP)
                    em.iconst(j)
                    value(x)
                    m.visitInsn(AASTORE)
                }
                allocateVirtual(em, v)
                m.visitInsn(DUP)
                val l = em.temp()
                m.visitVarInsn(ASTORE, l)
                made[v] = l
            } else {
                m.visitVarInsn(ks.rep(v.kind).load, slot[v]!!)
                ks.convert(em, v.kind, ks.any)
            }
        }
        fun array(values: Array<Value>) {
            em.iconst(values.size)
            m.visitTypeInsn(ANEWARRAY, "java/lang/Object")
            for ((j, v) in values.withIndex()) {
                m.visitInsn(DUP)
                em.iconst(j)
                value(v)
                m.visitInsn(AASTORE)
            }
        }
        // the calls compiled inlined, innermost first (the language's records, each inside the next), then the frame's state
        val inner = em.temp()
        m.visitInsn(ACONST_NULL)
        m.visitVarInsn(ASTORE, inner)
        var s = e.state
        while (true) {
            val site = s.site ?: break
            inlinedRecord(em, site, s, inner, ::value, ::array)
            m.visitVarInsn(ASTORE, inner)
            s = site.callerState
        }
        if (e.suspend != null) {
            // (a suspension: in the function compiled, never in a call inlined) the state straight into the frame's slots
            // (its frame is an interpreted one: they are there), then the language's suspension
            val slots = em.temp()
            m.visitVarInsn(ALOAD, L_FRAME)
            m.visitFieldInsn(GETFIELD, frameClass, "slots", CONSTS_D)
            m.visitVarInsn(ASTORE, slots)
            for ((k, v) in s.values.withIndex()) {
                m.visitVarInsn(ALOAD, slots)
                em.iconst(k)
                value(v)
                m.visitInsn(AASTORE)
            }
            m.visitVarInsn(ALOAD, L_FRAME)
            em.iconst(s.pc)
            em.iconst(s.values.size)
            suspendCall(em, e.suspend, ::value)
            m.visitInsn(ARETURN)
            m.visitMaxs(0, 0)
            m.visitEnd()
            return
        }
        if (e.tierUp) {
            // (at a loop header of the function compiled) the later version goes on, from the header's registers
            m.visitVarInsn(ALOAD, L_FRAME)
            em.iconst(s.pc)
            array(s.values)
            m.visitMethodInsn(INVOKESTATIC, VM_DEOPT, "tierUp", "(${VM_FRAME_D}I$CONSTS_D)$OBJ", false)
            m.visitInsn(ARETURN)
            m.visitMaxs(0, 0)
            m.visitEnd()
            return
        }
        if (e.counted) m.visitVarInsn(ALOAD, 0)
        m.visitVarInsn(ALOAD, L_FRAME)
        em.iconst(s.pc)
        array(s.values)
        m.visitVarInsn(ALOAD, inner)
        if (e.counted) {
            em.iconst(e.spec)
            m.visitMethodInsn(INVOKESTATIC, SUPER, "exit", "(L$SUPER;${VM_FRAME_D}I$CONSTS_D${OBJ}I)$OBJ", false)
        } else stressExit(em)
        m.visitInsn(ARETURN)
        m.visitMaxs(0, 0)
        m.visitEnd()
    }

    // ------------------------------------------------------------------ values

    private fun allocate() {
        val used = HashSet<Value>()
        for (b in g.blocks) {
            for (p in b.phis) used.addAll(p.inputs)
            for (i in b.insts) {
                used.addAll(i.inputs)
                i.state?.let { s -> used.addAll(s.values); s.site?.let { used.addAll(it.values()) } } // an exit reads them
            }
            // an inlined body reads its environment from its local
            b.site?.let { used.addAll(siteLocals(it)) }
            // a move to the tier's code at a loop header passes the header's registers as each back edge gives them
            if (g.tierUp && b.site == null && b.loopHeader) b.entryRegs?.let { regs -> for (p in b.preds) used.addAll(edgeRegs(p, b, regs)) }
        }
        for (b in g.blocks) {
            for (p in b.phis) local[p] = slotFor(p.kind)
            for (i in b.insts) if (i.hasResult && i in used && !i.virtual) local[i] = slotFor(i.kind)
        }
    }

    protected fun slotFor(kind: Byte): Int = alloc(ks.rep(kind).slots)

    protected fun kindOf(v: Value): Byte = kinds.of(v)

    /** Pushes [v] as kind [want]: unchanged, an int widened, or boxed. */
    protected fun load(v: Value, want: Byte) {
        if (v is Const) return pushConst(v, want)
        val l = local[v] ?: throw JitBailout("v${v.id} has no local (${v.javaClass.simpleName} ${(v as? Inst)?.let { g.ops.info(it.op).name + " result " + it.hasResult } ?: ""})")
        val k = v.kind
        mv.visitVarInsn(ks.rep(k).load, l)
        ks.convert(this, k, want)
    }

    /** Stores the value of kind [k] on the JVM stack as [i]'s result, or drops it when nothing uses [i]. */
    protected fun done(i: Inst, k: Byte) {
        if (k != i.kind) throw JitBailout("${g.ops.info(i.op).name} gives kind $k, not ${i.kind}")
        val l = local[i]
        if (l == null) ks.rep(k).pop(mv)
        else mv.visitVarInsn(ks.rep(k).store, l)
    }

    protected fun store(v: Value) {
        mv.visitVarInsn(ks.rep(v.kind).store, local[v]!!)
    }

    // ------------------------------------------------------------------ entries

    private fun prologue() {
        entry()
        loadConstants()
        mv.visitVarInsn(ASTORE, L_CONSTS)
        loadAgent()
        mv.visitVarInsn(ASTORE, L_AGENT)
        iconst(4096)
        mv.visitVarInsn(ISTORE, L_BUDGET)
        // homes start with a value of their kind (the verifier wants them assigned where a handler reads them)
        for ((r, k) in homeKind) {
            val l = slotFor(k)
            homeLocal[r] = l
            ks.initial(this, k)
            mv.visitVarInsn(ks.rep(k).store, l)
        }
        constsLocal[g.code] = L_CONSTS
        val codes = LinkedHashSet<dev.mooner.neonjs.compiler.CodeUnit>()
        for (b in g.blocks) {
            b.site?.let { codes.add(it.code) }
            for (p in b.phis) for (v in p.inputs) if (v is Const && v.k >= 0 && v.code != null) codes.add(v.code)
            for (i in b.insts) for (v in i.inputs) if (v is Const && v.k >= 0 && v.code != null) codes.add(v.code)
        }
        codes.remove(g.code)
        dataLocal = temp()
        mv.visitVarInsn(ALOAD, 0)
        mv.visitFieldInsn(GETFIELD, SUPER, "data", CONSTS_D)
        mv.visitVarInsn(ASTORE, dataLocal)
        for (cb in codes) {
            val l = temp()
            mv.visitVarInsn(ALOAD, dataLocal)
            iconst(g.dataIndex(cb.constants))
            mv.visitInsn(AALOAD)
            mv.visitTypeInsn(CHECKCAST, CONSTS_D)
            mv.visitVarInsn(ASTORE, l)
            constsLocal[cb] = l
        }
    }

    /**
     * On-stack replacement: an interpreted frame continues at the loop header in its pc. Entries are the loop headers
     * the interpreter may stop at (targets of backward jumps) with an empty operand stack whose live values are all in
     * registers there (always so in bytecode, checked anyway); elsewhere the code returns JitRt.NO_OSR.
     */
    private fun osrEntries() {
        val targets = backEdgeTargets()
        // where suspended activations resume (an entry each), and, for code that suspends, its start (pc 0: a generator's
        // or an async function's frame is an interpreted one from the start)
        val resumes = g.blocks.filter { it.resumeEntry }.sortedBy { it.pc }
        val start = g.startsInterpreted
        if (targets.isEmpty() && resumes.isEmpty() && !start) return
        val live = liveIn()
        // the blocks of each loop LICM made an entry block for: an entry inside it makes that block's code again too (what
        // the loop it enters relies on, guards included, was moved out of the loops around it as well)
        val index = HashMap<Block, Int>()
        for ((k, b) in g.blocks.withIndex()) index[b] = k
        val bodies = HashMap<Block, Set<Block>>()
        for (h in g.blocks) if (h.loopHeader && h.loopEntry != null) {
            val body = HashSet<Block>()
            body.add(h)
            val work = ArrayDeque(h.preds.filter { index[it]!! >= index[h]!! })
            while (work.isNotEmpty()) {
                val b = work.removeLast()
                if (body.add(b)) work.addAll(b.preds)
            }
            bodies[h] = body
        }
        /** The entry blocks of the loops around [b], outermost first. */
        fun around(b: Block): List<Block> = g.blocks.filter { h -> h !== b && bodies[h]?.contains(b) == true }.map { it.loopEntry!! }
        /**
         * Whether an entry at [b] with registers [regs] has every value live there, the entry blocks [made] (made again
         * in order, from registers) having theirs (a register's value without a local, dead there, is not loaded).
         */
        fun enterable(b: Block, regs: Array<Value>, made: List<Block>, more: List<Value> = emptyList()): Boolean {
            val defined = HashSet<Value>()
            fun ok(v: Value) = v is Const || v in defined || (regs.any { it === v } && local[v] != null)
            for (e in made) for (i in e.insts) if (!i.isTerminator) {
                if (!i.inputs.all { ok(it) }) return false
                defined.add(i)
            }
            return live[b]!!.all { ok(it) } && more.all { ok(it) }
        }
        // (a loop copied on a closure test, Unswitch: entered at its header, which tests first, the copy's header not)
        val entries = g.blocks.filter { b ->
            val regs = b.entryRegs
            val tw = b.twin
            if (b.site != null || regs == null || b.entryDepth != 0 || b.twinOf != null || java.util.Arrays.binarySearch(targets, b.pc) < 0) return@filter false
            // (a pc where an activation resumes is that entry, with the stack it resumes with; pc 0 of code whose frames
            // start interpreted is the start)
            if (resumes.any { it.pc == b.pc } || (start && b.pc == 0)) return@filter false
            val out = around(b)
            if (tw == null) return@filter enterable(b, regs, out + listOfNotNull(b.loopEntry))
            val test = b.dispatch!!
            tw.entryRegs != null && enterable(b, regs, out + test + listOfNotNull(b.loopEntry), test.term.inputs.toList()) &&
                enterable(tw, tw.entryRegs!!, out + test + listOfNotNull(tw.loopEntry))
        }.sortedBy { it.pc }
        g.osrPcs = entries.map { it.pc }.distinct().toIntArray()
        val normal = Label()
        val none = Label()
        frame()
        mv.visitFieldInsn(GETFIELD, frameClass, "slots", CONSTS_D)
        getstatic(frameClass, "NO_SLOTS", CONSTS_D)
        mv.visitJumpInsn(IF_ACMPEQ, normal)
        if (entries.isNotEmpty() || resumes.isNotEmpty() || start) {
            val starts = Array(entries.size) { Label() }
            frame()
            mv.visitFieldInsn(GETFIELD, frameClass, "pc", "I")
            if (resumes.isEmpty() && !start) mv.visitLookupSwitchInsn(none, IntArray(entries.size) { entries[it].pc }, starts)
            else {
                val keys = java.util.TreeMap<Int, Label>()
                for ((k, b) in entries.withIndex()) keys[b.pc] = starts[k]
                for (r in resumes) keys[r.pc] = label[r]!!
                keys[0] = normal
                mv.visitLookupSwitchInsn(none, keys.keys.toIntArray(), keys.values.toTypedArray())
            }
            val slots = temp()
            /**
             * The interpreter's registers into the locals of header [b]'s values. A kind may rest on a speculation the
             * interpreter did not make (a profiled load, say): a register live at the header without a value of it there
             * means no entry this time (osrKinds, before the frame is read); a register dead at the header (undefined
             * where an exit left it out) takes any value of the kind.
             */
            fun registers(b: Block) {
                frame()
                mv.visitFieldInsn(GETFIELD, frameClass, "slots", CONSTS_D)
                mv.visitVarInsn(ASTORE, slots)
                val regs = b.entryRegs!!
                val kinds = CharArray(regs.size) { r ->
                    val v = regs[r]
                    if (v is Const || local[v] == null || v !in live[b]!!) '.' else ks.osrCode(v.kind)
                }
                if (kinds.any { it != '.' }) {
                    mv.visitVarInsn(ALOAD, slots)
                    ks.osrCheck(this, String(kinds).trimEnd('.'))
                    mv.visitJumpInsn(IFEQ, none)
                }
                for ((r, v) in regs.withIndex()) {
                    if (v is Const || local[v] == null) continue
                    mv.visitVarInsn(ALOAD, slots)
                    iconst(r)
                    ks.osrRead(this, v.kind)
                    store(v)
                }
            }
            /**
             * Block [e]'s code again (an entry block, the test before a copied loop), its exits resuming at header [b]
             * (a register's value without a local, dead there, as undefined).
             */
            fun again(e: Block, b: Block) {
                val regs = b.entryRegs!!
                val state = FrameState(b.pc, Array(regs.size) { r -> regs[r].let { v -> if (v is Const || local[v] != null) v else Const(deadValue) } }, b.site)
                for (i in e.insts) if (!i.isTerminator) {
                    val s = i.state
                    if (s != null) i.state = state
                    inst(i)
                    i.state = s
                }
            }
            /** Into header [b]: what LICM moved before the loop, its exits resuming at the header with the registers loaded. */
            fun enter(b: Block) {
                b.loopEntry?.let { again(it, b) }
                mv.visitJumpInsn(GOTO, label[b])
            }
            for ((k, b) in entries.withIndex()) {
                mv.visitLabel(starts[k])
                registers(b)
                for (e in around(b)) again(e, b)
                val tw = b.twin
                if (tw == null) {
                    enter(b)
                    continue
                }
                // a loop copied on a closure test: the test, then the loop or its copy (whose registers are its own)
                val test = b.dispatch!!
                again(test, b)
                val copy = Label()
                mv.visitJumpInsn(branch(test.term), copy)
                enter(b)
                mv.visitLabel(copy)
                registers(tw)
                enter(tw)
            }
        }
        mv.visitLabel(none)
        getstatic(VM_RT, "NO_OSR", OBJ)
        mv.visitInsn(ARETURN)
        mv.visitLabel(normal)
    }

    /** The values live when each block starts (its phis included), but constants. */
    private fun liveIn(): Map<Block, Set<Value>> {
        val liveIn = HashMap<Block, HashSet<Value>>()
        for (b in g.blocks) liveIn[b] = HashSet()
        var changed = true
        while (changed) {
            changed = false
            for (b in g.blocks.asReversed()) {
                val live = HashSet<Value>()
                for (s in b.succs) {
                    for (v in liveIn[s]!!) if (!(v is Phi && v.block === s)) live.add(v)
                    val k = s.preds.indexOf(b)
                    for (p in s.phis) p.inputs[k].let { if (it !is Const) live.add(it) }
                }
                for (i in b.insts.asReversed()) {
                    live.remove(i)
                    for (v in i.inputs) if (v !is Const) live.add(v)
                    i.state?.let { s ->
                        for (v in expand(s.values.asList() + (s.site?.values() ?: emptyList()))) live.add(v)
                    }
                }
                live.addAll(b.phis)
                if (live != liveIn[b]) {
                    liveIn[b] = live
                    changed = true
                }
            }
        }
        return liveIn
    }

    // ------------------------------------------------------------------ blocks

    private fun emitBlock(b: Block) {
        mv.visitLabel(label[b])
        storedPc = -2
        curSite = b.site
        for (i in b.insts) {
            if (i.isTerminator) terminator(b, i)
            else if (i.op == Ir.RESUME_SLOT) resumeSlot(i)
            else inst(i)
        }
        mv.visitLabel(endLabel[b])
    }

    /** Where a suspended activation resumes: slot [Inst.a] of the frame, as the interpreter holds it. */
    private fun resumeSlot(i: Inst) {
        frame()
        mv.visitFieldInsn(GETFIELD, frameClass, "slots", CONSTS_D)
        iconst(i.a)
        mv.visitInsn(AALOAD)
        done(i, ks.any)
    }

    /** The edge from [b] to [s]: the interrupt check of a back edge, the copies into [s]'s phis, the jump. */
    protected fun edge(b: Block, s: Block) {
        if (index[s]!! <= index[b]!!) {
            if (g.tierUp && s.site == null && java.util.Arrays.binarySearch(g.osrPcs, s.pc) >= 0) tierUpCheck(b, s)
            else interruptCheck()
        }
        if (s.phis.isNotEmpty()) {
            val k = s.preds.indexOf(b)
            for (p in s.phis) load(p.inputs[k], p.kind)
            for (p in s.phis.asReversed()) store(p)
        }
        mv.visitJumpInsn(GOTO, label[s])
    }

    protected fun trivialEdge(b: Block, s: Block) = s.phis.isEmpty() && index[s]!! > index[b]!!

    /** Header [s]'s registers [regs] as the edge from [b] gives them (a phi of the header: its input on that edge). */
    private fun edgeRegs(b: Block, s: Block, regs: Array<Value>): Array<Value> {
        val k = s.preds.indexOf(b)
        return Array(regs.size) { r -> regs[r].let { v -> if (v is Phi && v.block === s && k >= 0) v.inputs[k] else v } }
    }

    /**
     * A back edge to loop header [s] of a baseline compiled with the tier on: the interrupt check, and where its budget
     * ran out, whether the block's code installed now is a later version with an entry at [s] (JitRt.canTierUp); if it
     * is, the code leaves for it with the header's registers (Deopt.tierUp), as the baseline compiler's code does.
     */
    private fun tierUpCheck(b: Block, s: Block) {
        val ok = Label()
        mv.visitIincInsn(L_BUDGET, -1)
        mv.visitVarInsn(ILOAD, L_BUDGET)
        mv.visitJumpInsn(IFGT, ok)
        mv.visitVarInsn(ALOAD, L_AGENT)
        mv.visitMethodInsn(INVOKEVIRTUAL, agentClass, "checkInterrupt", "()V", false)
        iconst(4096)
        mv.visitVarInsn(ISTORE, L_BUDGET)
        frame()
        mv.visitVarInsn(ALOAD, 0)
        iconst(s.pc)
        mv.visitMethodInsn(INVOKESTATIC, VM_RT, "canTierUp", "(${VM_FRAME_D}${OBJ}I)Z", false)
        mv.visitJumpInsn(IFEQ, ok)
        val l = Label()
        exits.add(Exit(l, FrameState(s.pc, edgeRegs(b, s, s.entryRegs!!), null), counted = false, tierUp = true))
        mv.visitJumpInsn(GOTO, l)
        mv.visitLabel(ok)
    }

    private fun terminator(b: Block, i: Inst) {
        when (i.op) {
            Ir.GOTO -> edge(b, i.targets[0])
            Ir.SWITCH -> {
                switchIndex(i)
                val stubs = Array(i.targets.size) { Label() }
                if (i.targets.size == 1) {
                    mv.visitInsn(POP)
                    mv.visitJumpInsn(GOTO, stubs[0])
                } else mv.visitTableSwitchInsn(0, i.targets.size - 2, stubs.last(), *stubs.copyOf(i.targets.size - 1))
                for ((k, t) in i.targets.withIndex()) {
                    mv.visitLabel(stubs[k])
                    edge(b, t)
                }
            }
            Ir.BRANCH -> {
                val jump = branch(i)
                val (taken, notTaken) = i.targets
                if (trivialEdge(b, taken)) {
                    mv.visitJumpInsn(jump, label[taken])
                    edge(b, notTaken)
                } else {
                    val t = Label()
                    mv.visitJumpInsn(jump, t)
                    edge(b, notTaken)
                    mv.visitLabel(t)
                    edge(b, taken)
                }
            }
            Ir.RETURN -> {
                load(i.inputs[0], ks.any)
                mv.visitInsn(ARETURN)
            }
            // (its target, where the activation resumes, is entered by the entry dispatch only)
            Ir.SUSPEND -> {
                val l = Label()
                exits.add(Exit(l, i.state!!, counted = false, suspend = i))
                mv.visitJumpInsn(GOTO, l)
            }
            else -> if (!otherTerminator(i)) throw JitBailout("terminator ${g.ops.info(i.op).name}")
        }
    }

    // ------------------------------------------------------------------ the language's

    /**
     * The class of the language's frames: the owner of the frame fields the code reads (`slots`, `pc`), the parameter of
     * its exit methods. The core's [VmFrame] (dev.mooner.neonjs.vm.VmFrame), or the language's subclass, which [entry]
     * casts the frame `run` gets to.
     */
    protected open val frameClass: String get() = VM_FRAME
    private val frameDesc: String get() = "L$frameClass;"

    /** At the start of `run`: the frame in L_FRAME as the language's [frameClass] (`run` takes a VmFrame). */
    protected open fun entry() {}

    /** Pushes the constants of the code unit compiled. */
    protected open fun loadConstants() {
        frame()
        mv.visitMethodInsn(INVOKEVIRTUAL, VM_FRAME, "getUnit", "()$CODE_UNIT_D", false)
        mv.visitFieldInsn(GETFIELD, CODE_UNIT, "constants", CONSTS_D)
    }

    /** Pushes the agent running the frame (as [agentClass]). */
    protected open fun loadAgent() {
        frame()
        mv.visitMethodInsn(INVOKEVIRTUAL, VM_FRAME, "getAgent", "()$VM_AGENT_D", false)
    }

    /** The code of [i], an instruction that is no terminator (nothing for a virtual one, which exits allocate). */
    protected abstract fun inst(i: Inst)

    /**
     * The test of BRANCH [i]: its condition evaluated on the JVM stack, as the int an `IFxx` jump tests; returns that
     * jump's opcode, taken towards [Inst.targets]' first.
     */
    protected abstract fun branch(i: Inst): Int

    /** The index SWITCH [i] goes by, an int on the JVM stack (k: its target k, anything else: its last). */
    protected abstract fun switchIndex(i: Inst)

    /** The code of a terminator but GOTO, BRANCH, SWITCH and RETURN (a throw); false where [i] is none of the language's. */
    protected abstract fun otherTerminator(i: Inst): Boolean

    /** Pushes constant [c] as kind [want]. */
    protected abstract fun pushConst(c: Const, want: Byte)

    /** In an exit method ([em] its emitter): pushes constant [c] as the Object the interpreter holds. */
    protected abstract fun constObject(em: Emit, c: Const)

    /** In an exit method: allocates virtual [v] (ScalarReplacement) from the array of its inputs' values on the stack. */
    protected abstract fun allocateVirtual(em: Emit, v: Inst)

    /**
     * In an exit method: the record of call [site] compiled inlined, at [state] in its body, the record of the call
     * inlined in it in local [inner] (or null), left on the JVM stack: what the language's resumer ([T2Code.resumer])
     * takes. [value] pushes one value as the interpreter holds it, [array] several in an Object[].
     */
    protected abstract fun inlinedRecord(em: Emit, site: InlineSite, state: FrameState, inner: Int, value: (Value) -> Unit, array: (Array<Value>) -> Unit)

    /** In an exit method of the deopt stress mode: leaves for the interpreter, given the frame, pc, values and record. */
    protected abstract fun stressExit(em: Emit)

    /**
     * In a suspension's exit method ([Ir.SUSPEND] [i]), the state written to the frame's slots (registers, then the
     * operand stack) and the frame, the pc it resumes at and the state's size (its sp) on the JVM stack: the language's
     * suspension, [value] pushing its input as the interpreter holds it; leaves what the code returns (the interpreter's
     * SUSPENDED) on the stack.
     */
    protected abstract fun suspendCall(em: Emit, i: Inst, value: (Value) -> Unit)

    /** The values the code of an inlined call's body reads from locals besides its instructions' (its environment). */
    protected abstract fun siteLocals(site: InlineSite): List<Value>

    /** An emitter of the language's code for exit method [m], its locals from [first] on. */
    protected abstract fun newEmitter(m: MethodVisitor, first: Int): Emit

    /** What the interpreter holds in a register it writes before reading it again (a dead one), as exits pass it. */
    protected abstract val deadValue: Any?

    /** The loop headers the interpreter may enter the code at (on-stack replacement), sorted. */
    protected abstract fun backEdgeTargets(): IntArray

    /** Frame.pc = the statement of [i], before something that can throw or call (stack traces and errors read it). */
    protected fun storePc(i: Inst) {
        if (i.stmt < 0 || i.stmt == storedPc) return
        frame()
        iconst(i.stmt)
        // an inlined body's position: Frame.pc stays at its call
        mv.visitFieldInsn(PUTFIELD, frameClass, if (curSite != null) "inlinePc" else "pc", "I")
        storedPc = i.stmt
    }

    companion object {
        /** The class [name] (internal form) implementing CompiledCode with [g]'s code, for class file [version]. */
        internal const val SUPER = "dev/mooner/neonjs/jit/opt/T2Code"
        private const val INIT_D = "([Ljava/lang/Object;$CODE_UNIT_D)V"

        /**
         * The class [name] (internal form): a T2Code with [g]'s code, constructed with the graph's data and the code
         * block, for class file [version].
         */
        fun generate(g: Graph, name: String, version: Int): ByteArray {
            val cw = JitClassWriter()
            cw.visit(version, ACC_PUBLIC or ACC_FINAL or ACC_SUPER, name, null, SUPER, null)
            val init = cw.visitMethod(ACC_PUBLIC, "<init>", INIT_D, null, null)
            init.visitCode()
            init.visitVarInsn(ALOAD, 0)
            init.visitVarInsn(ALOAD, 1)
            init.visitVarInsn(ALOAD, 2)
            init.visitMethodInsn(INVOKESPECIAL, SUPER, "<init>", INIT_D, false)
            init.visitInsn(RETURN)
            init.visitMaxs(0, 0)
            init.visitEnd()
            // run is made in a tree first, to number its locals by use (renumber)
            val mv = MethodNode(ASM9, ACC_PUBLIC or ACC_FINAL, "run", "($VM_FRAME_D)$OBJ", null, null)
            mv.visitCode()
            g.dialect.codegen(g, mv, Kinds(g.kindSystem), cw, name).emit()
            mv.visitMaxs(0, 0)
            mv.visitEnd()
            renumber(mv, L_REGS)
            mv.accept(cw)
            cw.visitEnd()
            return cw.toByteArray()
        }

        /**
         * Numbers [m]'s locals from [first] on by how often its code uses them, the most used first. Every value of the
         * graph has a local of its own (and every temporary of the code), often hundreds; a local past 255 takes a
         * `wide` instruction of twice the size, and HotSpot and ART leave a method above a size uncompiled (HotSpot
         * past 8,000 bytes). Locals are kept as they are if one is used with two sizes (a double's second half alone).
         */
        internal fun renumber(m: MethodNode, first: Int) {
            val uses = HashMap<Int, Int>()
            val size = HashMap<Int, Int>()
            fun use(v: Int, n: Int): Boolean {
                if (v < first) return true
                uses.merge(v, 1, Int::plus)
                return size.put(v, n).let { it == null || it == n }
            }
            for (insn in m.instructions) {
                val ok = when (insn) {
                    is VarInsnNode -> use(insn.`var`, if (insn.opcode in WIDE_VARS) 2 else 1)
                    is IincInsnNode -> use(insn.`var`, 1)
                    else -> true
                }
                if (!ok) return
            }
            val taken = HashSet<Int>()
            for ((v, n) in size) if (n == 2) taken.add(v + 1)
            if (size.keys.any { it in taken }) return // a double's second half used on its own
            val to = HashMap<Int, Int>()
            var next = first
            for (v in uses.keys.sortedWith(compareByDescending<Int> { uses[it]!! }.thenBy { it })) {
                to[v] = next
                next += size[v]!!
            }
            for (insn in m.instructions) when (insn) {
                is VarInsnNode -> to[insn.`var`]?.let { insn.`var` = it }
                is IincInsnNode -> to[insn.`var`]?.let { insn.`var` = it }
            }
        }

        private val WIDE_VARS = intArrayOf(DLOAD, DSTORE, LLOAD, LSTORE)

        /** The bytes of code of method [method] of class file [bytes] (its first of that name), or -1 without one. */
        internal fun codeLength(bytes: ByteArray, method: String): Int {
            val r = org.objectweb.asm.ClassReader(bytes)
            val buf = CharArray(r.maxStringLength)
            var p = r.header + 6
            p += 2 + 2 * r.readUnsignedShort(p) // interfaces
            fun skipMembers(): Int {
                var q = p + 2
                repeat(r.readUnsignedShort(p)) {
                    var attrs = r.readUnsignedShort(q + 6)
                    q += 8
                    while (attrs-- > 0) q += 6 + r.readInt(q + 2)
                }
                return q
            }
            p = skipMembers() // fields
            var q = p + 2
            repeat(r.readUnsignedShort(p)) {
                val name = r.readUTF8(q + 2, buf)
                var attrs = r.readUnsignedShort(q + 6)
                q += 8
                while (attrs-- > 0) {
                    // Code: max_stack, max_locals, code_length
                    if (name == method && r.readUTF8(q, buf) == "Code") return r.readInt(q + 10)
                    q += 6 + r.readInt(q + 2)
                }
            }
            return -1
        }
    }
}
