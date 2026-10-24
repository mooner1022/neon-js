package dev.mooner.neonjs.jit.opt

import dev.mooner.neonjs.compiler.Op
import dev.mooner.neonjs.jit.*
import dev.mooner.neonjs.runtime.MathIntrinsic
import dev.mooner.neonjs.runtime.Null
import dev.mooner.neonjs.runtime.Undefined
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*

private const val GLOBAL_ENV = "dev/mooner/neonjs/runtime/GlobalEnv"
private const val NATIVE = "dev/mooner/neonjs/runtime/NativeFunction"
private const val ARRAY = "dev/mooner/neonjs/runtime/JSArray"
private const val HOLE = "dev/mooner/neonjs/runtime/Hole"
private const val GLOBAL_ENV_D = "L$GLOBAL_ENV;"

/**
 * Generates `CompiledCode.run` for a [Graph]: every value used gets a JVM local of its kind (a `double`, an `int`, or an
 * Object), blocks become labels, and a phi's inputs are copied into its local at the end of each predecessor (all
 * loaded, then all stored: a parallel copy through the JVM stack). The JVM operand stack is empty at every block
 * boundary. Instructions get the present compiler's code for their kinds (the same JVM operations and `JitRt`
 * helpers, [JvmEmitter.emitHelper] for those not specialized), so the code does what the baseline does.
 *
 * Like the baseline, the code takes a frame made for compiled code from the start, and an interpreted one at a loop
 * header with an empty operand stack (on-stack replacement), where each register's value is loaded from the frame's
 * slots into the local of the value the register holds there; `f.pc` is the current statement whenever something can
 * throw or call; loop back edges count down the interrupt budget.
 */
internal class IrCodegen(
    private val g: Graph,
    mv: MethodVisitor,
    private val kinds: Kinds,
    private val ints: Boolean,
    /** The class being generated (its exit methods, [exits], are added to it after `run`). */
    private val cw: org.objectweb.asm.ClassWriter,
    private val className: String,
) : JvmEmitter(mv) {
    private var next = L_REGS
    override fun alloc(n: Int): Int = next.also { next += n }

    private val local = HashMap<Value, Int>()
    private val label = HashMap<Block, Label>()
    private val index = HashMap<Block, Int>()
    private val intKind = if (ints) JT.INT else JT.NUM
    /** The statement position stored in Frame.pc since the current block started (-2: none). */
    private var storedPc = -2
    /** The inlined call whose body the block being generated belongs to (null: the function compiled). */
    private var curSite: InlineSite? = null
    /** The locals holding T2Code.data and each code block's constants (the function compiled's: L_CONSTS). */
    private var dataLocal = -1
    private val constsLocal = HashMap<dev.mooner.neonjs.compiler.CodeBlock, Int>()

    // an inlined body reads its own constants and environment, and its calls record their position in Frame.inlinePc
    override fun consts() = mv.visitVarInsn(ALOAD, curSite?.let { constsLocal[it.code]!! } ?: L_CONSTS)
    override fun inlinedEnv(): Int = curSite?.let { local[it.env]!! } ?: -1
    override val inInlinedBody: Boolean get() = curSite != null

    /** The exits reached from `run`: the label of each, its state, and whether an assumption failed there. */
    private class Exit(val label: Label, val state: FrameState, val counted: Boolean, val spec: Int = -1)

    /** The exit of a speculation [i] makes (counted, and noted where it failed: T2Code.exit). */
    private fun specExit(l: Label, i: Inst): Exit {
        val code = i.origin ?: i.block!!.site?.code ?: g.code
        return Exit(l, i.state!!, counted = true, spec = g.dataIndex(Speculation(code, if (i.op == Ir.CHECK_NUMBER || i.op == Ir.CHECK_INT32) (if (i.pc < 0) code.code.size else i.pc) else i.pc)))
    }
    private val exits = ArrayList<Exit>()

    /** The end of each block's code, and the local and kind of each register's home (exception handlers). */
    private val endLabel = HashMap<Block, Label>()
    private val homeLocal = HashMap<Int, Int>()
    private val homeKind = HashMap<Int, Byte>()

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
            homeKind[i.a] = homeKind[i.a]?.let { JT.join(it, k) } ?: k
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
        val params = expand(e.state.values.asList() + (e.state.site?.values() ?: emptyList()))
        mv.visitVarInsn(ALOAD, 0)
        frame()
        for (v in params) load(v, v.kind)
        val desc = "($FRAME_D" + params.joinToString("") { typeOf(it.kind) } + ")$OBJ"
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

    private fun typeOf(kind: Byte) = when (kind) {
        JT.NUM -> "D"
        JT.INT, JT.BOOL -> "I"
        else -> OBJ
    }

    private fun exitMethod(n: Int, e: Exit, params: List<Value>, desc: String) {
        val m = cw.visitMethod(ACC_PRIVATE or ACC_FINAL, "exit$n", desc, null, null)
        m.visitCode()
        val slot = HashMap<Value, Int>()
        var next = 2 // this, the frame
        for (v in params) {
            slot[v] = next
            next += if (v.kind == JT.NUM) 2 else 1
        }
        val firstFree = next
        val em = object : JvmEmitter(m) {
            var top = firstFree
            override fun alloc(n: Int): Int = top.also { top += n }
        }
        val made = HashMap<Value, Int>()
        fun value(v: Value) {
            if (v is Const) constObject(em, v)
            else if (v is Inst && v.virtual) {
                // a literal not allocated: allocated now, once for every place the state holds it
                made[v]?.let { m.visitVarInsn(ALOAD, it); return }
                em.iconst(v.inputs.size)
                m.visitTypeInsn(ANEWARRAY, "java/lang/Object")
                for ((j, x) in v.inputs.withIndex()) {
                    m.visitInsn(DUP)
                    em.iconst(j)
                    value(x)
                    m.visitInsn(AASTORE)
                }
                constantsOf(em, ScalarReplacement.codeOf(g, v))
                em.iconst(v.a)
                m.visitInsn(AALOAD)
                m.visitVarInsn(ALOAD, L_FRAME)
                em.rt("newObjectLiteral", "([$OBJ$OBJ$FRAME_D)$OBJ")
                m.visitInsn(DUP)
                val l = em.temp()
                m.visitVarInsn(ASTORE, l)
                made[v] = l
            } else {
                m.visitVarInsn(when (v.kind) { JT.NUM -> DLOAD; JT.INT, JT.BOOL -> ILOAD; else -> ALOAD }, slot[v]!!)
                em.box(v.kind)
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
        // the calls compiled inlined, innermost first (Deopt.Inlined, each inside the next), then the frame's state
        val inner = em.temp()
        m.visitInsn(ACONST_NULL)
        m.visitVarInsn(ASTORE, inner)
        var s = e.state
        while (true) {
            val site = s.site ?: break
            value(site.fn)
            value(site.thisValue)
            array(site.args)
            value(site.env)
            em.iconst(s.pc)
            array(s.values)
            m.visitVarInsn(ALOAD, inner)
            em.rt("inlined", "($OBJ$OBJ$CONSTS_D${OBJ}I$CONSTS_D$OBJ)$OBJ")
            m.visitVarInsn(ASTORE, inner)
            s = site.callerState
        }
        if (e.counted) m.visitVarInsn(ALOAD, 0)
        m.visitVarInsn(ALOAD, L_FRAME)
        em.iconst(s.pc)
        array(s.values)
        m.visitVarInsn(ALOAD, inner)
        if (e.counted) {
            em.iconst(e.spec)
            m.visitMethodInsn(INVOKESTATIC, SUPER, "exit", "(L$SUPER;${FRAME_D}I$CONSTS_D${OBJ}I)$OBJ", false)
        } else em.rt("deoptChain", "(${FRAME_D}I$CONSTS_D$OBJ)$OBJ")
        m.visitInsn(ARETURN)
        m.visitMaxs(0, 0)
        m.visitEnd()
    }

    /** In an exit method: pushes the constants of [cb], the frame's code block's or one in T2Code.data. */
    private fun constantsOf(em: JvmEmitter, cb: dev.mooner.neonjs.compiler.CodeBlock) {
        if (cb === g.code) {
            em.mv.visitVarInsn(ALOAD, L_FRAME)
            em.mv.visitFieldInsn(GETFIELD, FRAME, "code", CODEBLOCK_D)
            em.mv.visitFieldInsn(GETFIELD, CODEBLOCK, "constants", CONSTS_D)
        } else {
            em.mv.visitVarInsn(ALOAD, 0)
            em.mv.visitFieldInsn(GETFIELD, SUPER, "data", CONSTS_D)
            em.iconst(g.dataIndex(cb.constants))
            em.mv.visitInsn(AALOAD)
            em.mv.visitTypeInsn(CHECKCAST, CONSTS_D)
        }
    }

    /** Pushes constant [c] as the Object the interpreter holds (as [pushConst] does with an Object wanted). */
    private fun constObject(em: JvmEmitter, c: Const) {
        val v = c.value
        when {
            v === Undefined -> em.pushUndefined()
            v === Null -> em.getstatic("dev/mooner/neonjs/runtime/Null", "INSTANCE", "Ldev/mooner/neonjs/runtime/Null;")
            v == true -> em.getstatic("java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;")
            v == false -> em.getstatic("java/lang/Boolean", "FALSE", "Ljava/lang/Boolean;")
            c.k >= 0 -> {
                constantsOf(em, c.code ?: g.code)
                em.iconst(c.k)
                em.mv.visitInsn(AALOAD)
            }
            v is Double -> {
                em.iconst(v.toInt())
                em.mv.visitMethodInsn(INVOKESTATIC, "dev/mooner/neonjs/runtime/Ops", "num", "(I)$OBJ", false)
            }
            else -> throw JitBailout("constant $v")
        }
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
            b.site?.let { used.add(it.env) }
        }
        for (b in g.blocks) {
            for (p in b.phis) local[p] = slotFor(p.kind)
            for (i in b.insts) if (i.hasResult && i in used && !i.virtual) local[i] = slotFor(i.kind)
        }
    }

    private fun slotFor(kind: Byte): Int = if (kind == JT.NUM) tempD() else temp()

    private fun kindOf(v: Value): Byte = kinds.of(v)

    /** Pushes [v] as kind [want]: unchanged, an int widened, or boxed. */
    private fun load(v: Value, want: Byte) {
        if (v is Const) return pushConst(v, want)
        val l = local[v] ?: throw JitBailout("v${v.id} has no local (${v.javaClass.simpleName} ${(v as? Inst)?.let { Ir.name(it.op) + " result " + it.hasResult } ?: ""})")
        val k = v.kind
        mv.visitVarInsn(when (k) { JT.NUM -> DLOAD; JT.INT, JT.BOOL -> ILOAD; else -> ALOAD }, l)
        convert(k, want)
    }

    private fun pushConst(c: Const, want: Byte) {
        val v = c.value
        val k = kinds.constKind(v)
        if (want == JT.ANY) {
            when {
                v === Undefined -> pushUndefined()
                v === Null -> getstatic("dev/mooner/neonjs/runtime/Null", "INSTANCE", "Ldev/mooner/neonjs/runtime/Null;")
                v == true -> getstatic("java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;")
                v == false -> getstatic("java/lang/Boolean", "FALSE", "Ljava/lang/Boolean;")
                c.k >= 0 -> {
                    // from the constants of its code block
                    mv.visitVarInsn(ALOAD, c.code?.let { constsLocal[it] } ?: L_CONSTS)
                    iconst(c.k)
                    mv.visitInsn(AALOAD)
                }
                v is Double -> {
                    // PUSH_INT: an int32, boxed as the baseline boxes it
                    iconst(v.toInt())
                    mv.visitMethodInsn(INVOKESTATIC, "dev/mooner/neonjs/runtime/Ops", "num", "(I)$OBJ", false)
                }
                else -> throw JitBailout("constant $v")
            }
            return
        }
        when (want) {
            JT.BOOL -> if (k == JT.BOOL) iconst(if (v == true) 1 else 0) else throw JitBailout("constant $v as a boolean")
            JT.INT -> if (k == JT.INT) iconst((v as Double).toInt()) else throw JitBailout("constant $v as an int")
            JT.NUM -> if (JT.isNum(k)) {
                val d = v as Double
                when {
                    d == 0.0 && 1.0 / d > 0 -> mv.visitInsn(DCONST_0)
                    d == 1.0 -> mv.visitInsn(DCONST_1)
                    else -> mv.visitLdcInsn(d)
                }
            } else throw JitBailout("constant $v as a number")
        }
    }

    /** Stores the value of kind [k] on the JVM stack as [i]'s result, or drops it when nothing uses [i]. */
    private fun done(i: Inst, k: Byte) {
        if (k != i.kind) throw JitBailout("${Ir.name(i.op)} gives kind $k, not ${i.kind}")
        val l = local[i]
        if (l == null) mv.visitInsn(if (k == JT.NUM) POP2 else POP)
        else mv.visitVarInsn(when (k) { JT.NUM -> DSTORE; JT.INT, JT.BOOL -> ISTORE; else -> ASTORE }, l)
    }

    private fun store(v: Value) {
        mv.visitVarInsn(when (v.kind) { JT.NUM -> DSTORE; JT.INT, JT.BOOL -> ISTORE; else -> ASTORE }, local[v]!!)
    }

    // ------------------------------------------------------------------ entries

    private fun prologue() {
        frame()
        mv.visitFieldInsn(GETFIELD, FRAME, "code", CODEBLOCK_D)
        mv.visitFieldInsn(GETFIELD, CODEBLOCK, "constants", CONSTS_D)
        mv.visitVarInsn(ASTORE, L_CONSTS)
        frame()
        mv.visitFieldInsn(GETFIELD, FRAME, "realm", REALM_D)
        mv.visitFieldInsn(GETFIELD, "dev/mooner/neonjs/runtime/Realm", "agent", AGENT_D)
        mv.visitVarInsn(ASTORE, L_AGENT)
        iconst(4096)
        mv.visitVarInsn(ISTORE, L_BUDGET)
        // homes start with a value of their kind (the verifier wants them assigned where a handler reads them)
        for ((r, k) in homeKind) {
            val l = slotFor(k)
            homeLocal[r] = l
            when (k) {
                JT.NUM -> { mv.visitInsn(DCONST_0); mv.visitVarInsn(DSTORE, l) }
                JT.INT, JT.BOOL -> { mv.visitInsn(ICONST_0); mv.visitVarInsn(ISTORE, l) }
                else -> { pushUndefined(); mv.visitVarInsn(ASTORE, l) }
            }
        }
        constsLocal[g.code] = L_CONSTS
        val codes = LinkedHashSet<dev.mooner.neonjs.compiler.CodeBlock>()
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
        val targets = JvmCompiler.backEdgeTargets(g.code.code)
        if (targets.isEmpty()) return
        val live = liveIn()
        // (a loop copied on a closure test, Unswitch: entered at its header, which tests first, the copy's header not)
        fun enterable(b: Block, regs: Array<Value>): Boolean {
            // the values LICM moved before the loop are made again (below), from registers
            val e = b.loopEntry
            fun ok(v: Value) = regs.any { it === v } || (e != null && v is Inst && v.block === e)
            return live[b]!!.all { ok(it) } &&
                (e == null || (e.insts.all { i -> i.inputs.all { it is Const || ok(it) } } && regs.all { it is Const || local[it] != null }))
        }
        val entries = g.blocks.filter { b ->
            val regs = b.entryRegs
            val tw = b.twin
            b.site == null && regs != null && b.entryDepth == 0 && b.twinOf == null && java.util.Arrays.binarySearch(targets, b.pc) >= 0 &&
                enterable(b, regs) &&
                (tw == null || (tw.entryRegs != null && enterable(tw, tw.entryRegs!!) &&
                    b.dispatch!!.insts.all { i -> i.isTerminator || i.inputs.all { v -> v is Const || regs.any { it === v } } }))
        }.sortedBy { it.pc }
        g.osrPcs = entries.map { it.pc }.distinct().toIntArray()
        val normal = Label()
        val none = Label()
        frame()
        mv.visitFieldInsn(GETFIELD, FRAME, "slots", CONSTS_D)
        getstatic(FRAME, "NO_SLOTS", CONSTS_D)
        mv.visitJumpInsn(IF_ACMPEQ, normal)
        if (entries.isNotEmpty()) {
            val starts = Array(entries.size) { Label() }
            frame()
            mv.visitFieldInsn(GETFIELD, FRAME, "pc", "I")
            mv.visitLookupSwitchInsn(none, IntArray(entries.size) { entries[it].pc }, starts)
            val obj = temp()
            val d = tempD()
            /** The interpreter's registers into the locals of header [b]'s values. */
            fun registers(b: Block) {
                for ((r, v) in b.entryRegs!!.withIndex()) {
                    if (v is Const || local[v] == null) continue
                    val next = Label()
                    frame()
                    mv.visitFieldInsn(GETFIELD, FRAME, "slots", CONSTS_D)
                    iconst(r)
                    mv.visitInsn(AALOAD)
                    if (v.kind == JT.ANY) {
                        store(v)
                        continue
                    }
                    // a kind may rest on a speculation the interpreter did not make (a profiled load, say): a register
                    // without a value of it there means no entry this time (the frame is not changed before); but a
                    // register dead at the header (undefined where an exit left it out) takes any value of the kind
                    mv.visitVarInsn(ASTORE, obj)
                    mv.visitVarInsn(ALOAD, obj)
                    mv.visitTypeInsn(INSTANCEOF, if (v.kind == JT.BOOL) "java/lang/Boolean" else "java/lang/Double")
                    if (v in live[b]!!) mv.visitJumpInsn(IFEQ, none)
                    else {
                        val has = Label()
                        mv.visitJumpInsn(IFNE, has)
                        if (v.kind == JT.NUM) mv.visitInsn(DCONST_0) else mv.visitInsn(ICONST_0)
                        store(v)
                        mv.visitJumpInsn(GOTO, next)
                        mv.visitLabel(has)
                    }
                    mv.visitVarInsn(ALOAD, obj)
                    when (v.kind) {
                        JT.NUM -> unboxNum()
                        JT.INT -> {
                            unboxNum()
                            mv.visitVarInsn(DSTORE, d)
                            mv.visitVarInsn(DLOAD, d)
                            rt("isInt32", "(D)Z")
                            mv.visitJumpInsn(IFEQ, none)
                            mv.visitVarInsn(DLOAD, d)
                            mv.visitInsn(D2I)
                        }
                        else -> {
                            mv.visitTypeInsn(CHECKCAST, "java/lang/Boolean")
                            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false)
                        }
                    }
                    store(v)
                    mv.visitLabel(next)
                }
            }
            /** Into header [b]: what LICM moved before the loop, its exits resuming at the header with the registers loaded. */
            fun enter(b: Block) {
                b.loopEntry?.let { e ->
                    val state = FrameState(b.pc, b.entryRegs!!.copyOf(), b.site)
                    for (i in e.insts) if (!i.isTerminator) {
                        val s = i.state
                        if (s != null) i.state = state
                        inst(i)
                        i.state = s
                    }
                }
                mv.visitJumpInsn(GOTO, label[b])
            }
            for ((k, b) in entries.withIndex()) {
                mv.visitLabel(starts[k])
                registers(b)
                val tw = b.twin
                if (tw == null) {
                    enter(b)
                    continue
                }
                // a loop copied on a closure test: the test, then the loop or its copy (whose registers are its own)
                val test = b.dispatch!!
                for (i in test.insts) if (!i.isTerminator) inst(i)
                val copy = Label()
                load(test.term.inputs[0], JT.BOOL)
                mv.visitJumpInsn(if (test.term.a == Op.JUMP_IF_TRUE) IFNE else IFEQ, copy)
                enter(b)
                mv.visitLabel(copy)
                registers(tw)
                enter(tw)
            }
        }
        mv.visitLabel(none)
        getstatic(RT, "NO_OSR", OBJ)
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
            if (i.isTerminator) terminator(b, i) else inst(i)
        }
        mv.visitLabel(endLabel[b])
    }

    /** The edge from [b] to [s]: the interrupt check of a back edge, the copies into [s]'s phis, the jump. */
    private fun edge(b: Block, s: Block) {
        if (index[s]!! <= index[b]!!) interruptCheck()
        if (s.phis.isNotEmpty()) {
            val k = s.preds.indexOf(b)
            for (p in s.phis) load(p.inputs[k], p.kind)
            for (p in s.phis.asReversed()) store(p)
        }
        mv.visitJumpInsn(GOTO, label[s])
    }

    private fun trivialEdge(b: Block, s: Block) = s.phis.isEmpty() && index[s]!! > index[b]!!

    private fun terminator(b: Block, i: Inst) {
        when (i.op) {
            Ir.GOTO -> edge(b, i.targets[0])
            Ir.SWITCH -> {
                load(i.inputs[0], JT.ANY)
                rt("toIntIndex", "($OBJ)I")
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
                val cond = i.inputs[0]
                val jump = when (i.a) {
                    // the next value of an iteration: NotFound or null when it is done
                    Op.ITER_STEP -> {
                        load(cond, JT.ANY)
                        rt("isNotFound", "($OBJ)Z")
                        IFNE
                    }
                    Op.FOR_IN_NEXT -> {
                        load(cond, JT.ANY)
                        IFNULL
                    }
                    Op.JUMP_IF_TRUE, Op.JUMP_IF_FALSE -> {
                        val k = kindOf(cond)
                        load(cond, k)
                        truth(k)
                        if (i.a == Op.JUMP_IF_TRUE) IFNE else IFEQ
                    }
                    Op.JUMP_IF_NULLISH, Op.JUMP_IF_NOT_NULLISH -> {
                        load(cond, JT.ANY)
                        rt("isNullish", "($OBJ)Z")
                        if (i.a == Op.JUMP_IF_NULLISH) IFNE else IFEQ
                    }
                    else -> {
                        load(cond, JT.ANY)
                        rt("isUndefined", "($OBJ)Z")
                        if (i.a == Op.JUMP_IF_UNDEFINED) IFNE else IFEQ
                    }
                }
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
                load(i.inputs[0], JT.ANY)
                mv.visitInsn(ARETURN)
            }
            Ir.THROW -> {
                storePc(i)
                load(i.inputs[0], JT.ANY)
                rt("throwValue", "($OBJ)Ljava/lang/Throwable;")
                mv.visitInsn(ATHROW)
            }
            Ir.THROW_ERROR -> {
                storePc(i)
                iconst(i.a)
                iconst(i.b)
                consts()
                rt("throwError", "(II$CONSTS_D)Ljava/lang/Throwable;")
                mv.visitInsn(ATHROW)
            }
            else -> throw JitBailout("terminator ${Ir.name(i.op)}")
        }
    }

    /**
     * The receiver of LOAD_SLOT, STORE_SLOT or ADD_SLOT [i] (its first input), a JSObject, in local [o]: checked for the
     * shape its cache saw (else to [exit]) unless that is known there (KnownShapes).
     */
    private fun receiver(i: Inst, o: Int, exit: Label) {
        val known = i.known and KnownShapes.SHAPE != 0
        load(i.inputs[0], JT.ANY)
        if (!known) {
            mv.visitTypeInsn(INSTANCEOF, "dev/mooner/neonjs/runtime/JSObject")
            mv.visitJumpInsn(IFEQ, exit)
            load(i.inputs[0], JT.ANY)
        }
        mv.visitTypeInsn(CHECKCAST, "dev/mooner/neonjs/runtime/JSObject")
        mv.visitVarInsn(ASTORE, o)
        if (!known) shapeCheck(o, i.a, exit)
    }

    /** Jumps to [exit] unless the object in local [obj] has a property map whose shape is the graph's data [shapeIndex]. */
    private fun shapeCheck(obj: Int, shapeIndex: Int, exit: Label) {
        val t = temp()
        mv.visitVarInsn(ALOAD, dataLocal)
        iconst(shapeIndex)
        mv.visitInsn(AALOAD)
        mv.visitVarInsn(ASTORE, t)
        shapeCheckWith(obj, t, exit)
    }

    /**
     * [shapeCheck] with the shape in local [shape]: the shape of the object's property map, or of an object without
     * one (an empty array, say) the empty shape of its prototype and class, as the caches compare (PropCache.shapeOf).
     */
    private fun shapeCheckWith(obj: Int, shape: Int, exit: Label) {
        mv.visitVarInsn(ALOAD, obj)
        mv.visitFieldInsn(GETFIELD, "dev/mooner/neonjs/runtime/JSObject", "props", PMAP_D)
        mv.visitInsn(DUP)
        val has = Label()
        val cmp = Label()
        mv.visitJumpInsn(IFNONNULL, has)
        mv.visitInsn(POP)
        mv.visitVarInsn(ALOAD, obj)
        mv.visitMethodInsn(INVOKESTATIC, "dev/mooner/neonjs/runtime/PropCache", "shapeOf", "(Ldev/mooner/neonjs/runtime/JSObject;)Ldev/mooner/neonjs/runtime/Shape;", false)
        mv.visitJumpInsn(GOTO, cmp)
        mv.visitLabel(has)
        mv.visitFieldInsn(GETFIELD, PMAP, "shape", "Ldev/mooner/neonjs/runtime/Shape;")
        mv.visitLabel(cmp)
        mv.visitVarInsn(ALOAD, shape)
        mv.visitJumpInsn(IF_ACMPNE, exit)
    }

    /** Frame.pc = the statement of [i], before something that can throw or call (stack traces and errors read it). */
    private fun storePc(i: Inst) {
        if (i.stmt < 0 || i.stmt == storedPc) return
        frame()
        iconst(i.stmt)
        // an inlined body's position: Frame.pc stays at its call
        mv.visitFieldInsn(PUTFIELD, FRAME, if (curSite != null) "inlinePc" else "pc", "I")
        storedPc = i.stmt
    }

    // ------------------------------------------------------------------ instructions

    private fun inst(i: Inst) {
        val op = i.op
        if (i.virtual) return // allocated by the exits that need it
        when (op) {
            Ir.PARAM -> {
                // f.args[a], undefined past its end (JitRt.arg, without a call: ART does not inline helpers here)
                val none = Label()
                val end = Label()
                frame()
                mv.visitFieldInsn(GETFIELD, FRAME, "args", CONSTS_D)
                mv.visitInsn(DUP)
                mv.visitInsn(ARRAYLENGTH)
                iconst(i.a)
                mv.visitJumpInsn(IF_ICMPLE, none)
                iconst(i.a)
                mv.visitInsn(AALOAD)
                mv.visitJumpInsn(GOTO, end)
                mv.visitLabel(none)
                mv.visitInsn(POP)
                pushUndefined()
                mv.visitLabel(end)
                done(i, JT.ANY)
                return
            }
            Ir.CHECK_INTRINSIC -> {
                val l = Label()
                load(i.inputs[0], JT.ANY)
                mv.visitInsn(DUP)
                mv.visitTypeInsn(INSTANCEOF, NATIVE)
                val ok = Label()
                mv.visitJumpInsn(IFNE, ok)
                mv.visitInsn(POP)
                mv.visitJumpInsn(GOTO, l)
                mv.visitLabel(ok)
                mv.visitTypeInsn(CHECKCAST, NATIVE)
                mv.visitFieldInsn(GETFIELD, NATIVE, "intrinsic", "I")
                iconst(i.a)
                mv.visitJumpInsn(IF_ICMPNE, l)
                exits.add(specExit(l, i))
                return
            }
            Ir.MATH -> {
                // the arguments as numbers (ToNumber may run valueOf: the position first), then the builtin's Java method
                for (v in i.inputs) {
                    val k = kindOf(v)
                    if (JT.isNum(k)) load(v, JT.NUM)
                    else {
                        storePc(i)
                        load(v, JT.ANY)
                        rt("toNumberD", "($OBJ)D")
                    }
                }
                when (i.a) {
                    MathIntrinsic.POW -> mv.visitMethodInsn(INVOKESTATIC, "dev/mooner/neonjs/runtime/Ops", "pow", "(DD)D", false)
                    MathIntrinsic.ATAN2 -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "atan2", "(DD)D", false)
                    else -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", when (i.a) {
                        MathIntrinsic.ABS -> "abs"
                        MathIntrinsic.SQRT -> "sqrt"
                        MathIntrinsic.SIN -> "sin"
                        MathIntrinsic.COS -> "cos"
                        MathIntrinsic.TAN -> "tan"
                        MathIntrinsic.ATAN -> "atan"
                        MathIntrinsic.EXP -> "exp"
                        MathIntrinsic.LOG -> "log"
                        MathIntrinsic.FLOOR -> "floor"
                        MathIntrinsic.CEIL -> "ceil"
                        else -> throw JitBailout("intrinsic ${i.a}")
                    }, "(D)D", false)
                }
                done(i, JT.NUM)
                return
            }
            Ir.NEW_THIS -> {
                // (a class's field initializers run code: the position first)
                storePc(i)
                load(i.inputs[0], JT.ANY)
                rt("newThis", "($OBJ)$OBJ")
                done(i, JT.ANY)
                return
            }
            Ir.CONSTRUCT_RESULT -> {
                val end = Label()
                load(i.inputs[0], JT.ANY)
                mv.visitInsn(DUP)
                mv.visitTypeInsn(INSTANCEOF, "dev/mooner/neonjs/runtime/JSObject")
                mv.visitJumpInsn(IFNE, end)
                mv.visitInsn(POP)
                load(i.inputs[1], JT.ANY)
                mv.visitLabel(end)
                done(i, JT.ANY)
                return
            }
            Ir.IS_CLOSURE -> {
                // CHECK_CLOSURE's test, as a boolean
                val fn = i.inputs[0]
                val no = Label()
                val end = Label()
                load(fn, JT.ANY)
                mv.visitTypeInsn(INSTANCEOF, CLOSURE)
                mv.visitJumpInsn(IFEQ, no)
                load(fn, JT.ANY)
                mv.visitTypeInsn(CHECKCAST, CLOSURE)
                mv.visitFieldInsn(GETFIELD, CLOSURE, "code", CODEBLOCK_D)
                mv.visitVarInsn(ALOAD, dataLocal)
                iconst(i.a)
                mv.visitInsn(AALOAD)
                mv.visitJumpInsn(IF_ACMPNE, no)
                load(fn, JT.ANY)
                mv.visitTypeInsn(CHECKCAST, CLOSURE)
                mv.visitFieldInsn(GETFIELD, CLOSURE, "realm", REALM_D)
                frame()
                mv.visitFieldInsn(GETFIELD, FRAME, "realm", REALM_D)
                mv.visitJumpInsn(IF_ACMPNE, no)
                mv.visitInsn(ICONST_1)
                mv.visitJumpInsn(GOTO, end)
                mv.visitLabel(no)
                mv.visitInsn(ICONST_0)
                mv.visitLabel(end)
                done(i, JT.BOOL)
                return
            }
            Ir.CHECK_CLOSURE -> {
                // a closure of the code block inlined, in the frame's realm (as Gen.emitInlineCall's guard)
                val fn = i.inputs[0]
                val l = Label()
                load(fn, JT.ANY)
                mv.visitTypeInsn(INSTANCEOF, CLOSURE)
                mv.visitJumpInsn(IFEQ, l)
                load(fn, JT.ANY)
                mv.visitTypeInsn(CHECKCAST, CLOSURE)
                mv.visitFieldInsn(GETFIELD, CLOSURE, "code", CODEBLOCK_D)
                mv.visitVarInsn(ALOAD, dataLocal)
                iconst(i.a)
                mv.visitInsn(AALOAD)
                mv.visitJumpInsn(IF_ACMPNE, l)
                load(fn, JT.ANY)
                mv.visitTypeInsn(CHECKCAST, CLOSURE)
                mv.visitFieldInsn(GETFIELD, CLOSURE, "realm", REALM_D)
                frame()
                mv.visitFieldInsn(GETFIELD, FRAME, "realm", REALM_D)
                mv.visitJumpInsn(IF_ACMPNE, l)
                exits.add(specExit(l, i))
                return
            }
            Ir.INLINE_ENTER -> {
                if (i.callee!!.quiet) return // nothing sees it: no record (QuietInlines)
                if (curSite == null) {
                    inlineEnter(i)
                    storedPc = -2
                    return
                }
                frame()
                load(i.inputs[0], JT.ANY)
                mv.visitTypeInsn(CHECKCAST, CLOSURE)
                iconst(i.a)
                // a call inlined in an inlined body: that one goes on the frame's stack of outer calls
                rt(if (curSite != null) "inlineEnterNested" else "inlineEnter", "($FRAME_D$CLOSURE_D" + "I)V")
                storedPc = -2
                return
            }
            Ir.INLINE_EXIT -> {
                if (i.callee!!.quiet) return
                if (curSite == null) {
                    // JitRt.inlineExit
                    frame()
                    mv.visitInsn(ACONST_NULL)
                    mv.visitFieldInsn(PUTFIELD, FRAME, "inlineFn", CLOSURE_D)
                    mv.visitVarInsn(ALOAD, L_AGENT)
                    mv.visitInsn(DUP)
                    mv.visitFieldInsn(GETFIELD, AGENT, "depth", "I")
                    mv.visitInsn(ICONST_1)
                    mv.visitInsn(ISUB)
                    mv.visitFieldInsn(PUTFIELD, AGENT, "depth", "I")
                    storedPc = -2
                    return
                }
                frame()
                rt(if (curSite != null) "inlineExitNested" else "inlineExit", "($FRAME_D)V")
                storedPc = -2
                return
            }
            Ir.CLOSURE_ENV -> {
                load(i.inputs[0], JT.ANY)
                mv.visitTypeInsn(CHECKCAST, CLOSURE)
                mv.visitFieldInsn(GETFIELD, CLOSURE, "env", "Ldev/mooner/neonjs/runtime/Env;")
                done(i, JT.ANY)
                return
            }
            Ir.INLINE_THIS -> {
                load(i.inputs[0], JT.ANY)
                mv.visitTypeInsn(CHECKCAST, CLOSURE)
                load(i.inputs[1], JT.ANY)
                rt("inlineThis", "($CLOSURE_D$OBJ)$OBJ")
                done(i, JT.ANY)
                return
            }
            Ir.CHECK_NUMBER, Ir.CHECK_INT32 -> {
                val v = i.inputs[0]
                val l = Label()
                val vk = kindOf(v)
                if (JT.isNum(vk)) {
                    // a number already (a read replaced by the value it reads): only an int32 check is left, if any
                    // (IrBuilder.foldChecks takes the state of the others)
                    if (op == Ir.CHECK_NUMBER || vk == JT.INT) {
                        load(v, if (op == Ir.CHECK_NUMBER) JT.NUM else JT.INT)
                        done(i, if (op == Ir.CHECK_NUMBER) JT.NUM else JT.INT)
                        return
                    }
                    val t = tempD()
                    load(v, JT.NUM)
                    mv.visitVarInsn(DSTORE, t)
                    mv.visitVarInsn(DLOAD, t)
                    rt("isInt32", "(D)Z")
                    mv.visitJumpInsn(IFEQ, l)
                    mv.visitVarInsn(DLOAD, t)
                    mv.visitInsn(D2I)
                    done(i, JT.INT)
                    exits.add(specExit(l, i))
                    return
                }
                load(v, JT.ANY)
                mv.visitTypeInsn(INSTANCEOF, "java/lang/Double")
                mv.visitJumpInsn(IFEQ, l)
                load(v, JT.ANY)
                unboxNum()
                if (op == Ir.CHECK_INT32) {
                    val t = tempD()
                    mv.visitVarInsn(DSTORE, t)
                    mv.visitVarInsn(DLOAD, t)
                    rt("isInt32", "(D)Z")
                    mv.visitJumpInsn(IFEQ, l)
                    mv.visitVarInsn(DLOAD, t)
                    mv.visitInsn(D2I)
                    done(i, JT.INT)
                } else done(i, JT.NUM)
                exits.add(specExit(l, i))
                return
            }
            Ir.ADD_SLOT -> {
                // the receiver's shape and its prototypes' as the cache saw them, an extensible object (PropCache.put's
                // ADD case), then the transition
                val l = Label()
                val o = temp()
                receiver(i, o, l)
                if (i.known and KnownShapes.EXTENSIBLE == 0) {
                    mv.visitVarInsn(ALOAD, o)
                    mv.visitFieldInsn(GETFIELD, "dev/mooner/neonjs/runtime/JSObject", "extensible", "Z")
                    mv.visitJumpInsn(IFEQ, l)
                }
                if (i.c >= 0 && i.known and KnownShapes.CHAIN == 0) {
                    val chain = g.data[i.c] as Array<*>
                    val h = temp()
                    mv.visitVarInsn(ALOAD, o)
                    mv.visitVarInsn(ASTORE, h)
                    for (k in chain.indices) {
                        mv.visitVarInsn(ALOAD, h)
                        mv.visitFieldInsn(GETFIELD, "dev/mooner/neonjs/runtime/JSObject", "proto", "Ldev/mooner/neonjs/runtime/JSObject;")
                        mv.visitInsn(DUP)
                        mv.visitVarInsn(ASTORE, h)
                        mv.visitJumpInsn(IFNULL, l)
                        mv.visitVarInsn(ALOAD, dataLocal)
                        iconst(i.c)
                        mv.visitInsn(AALOAD)
                        mv.visitTypeInsn(CHECKCAST, "[Ljava/lang/Object;")
                        iconst(k)
                        mv.visitInsn(AALOAD)
                        val t = temp()
                        mv.visitVarInsn(ASTORE, t)
                        shapeCheckWith(h, t, l)
                    }
                }
                mv.visitVarInsn(ALOAD, o)
                mv.visitVarInsn(ALOAD, dataLocal)
                iconst(i.b)
                mv.visitInsn(AALOAD)
                mv.visitTypeInsn(CHECKCAST, "dev/mooner/neonjs/runtime/Shape")
                load(i.inputs[1], JT.ANY)
                rt("addSlot", "(Ldev/mooner/neonjs/runtime/JSObject;Ldev/mooner/neonjs/runtime/Shape;$OBJ)V")
                if (i.state != null) exits.add(specExit(l, i))
                return
            }
            Ir.LOAD_SLOT, Ir.STORE_SLOT -> {
                // the receiver's property map has the shape the cache saw (then the prototypes' do), else an exit
                val l = Label()
                val holder = temp()
                receiver(i, holder, l)
                if (i.c >= 0) {
                    // the prototypes up to the holder, each with the shape the cache saw (where not known already)
                    val chain = g.data[i.c] as Array<*>
                    val check = i.known and KnownShapes.CHAIN == 0
                    for (k in chain.indices) {
                        mv.visitVarInsn(ALOAD, holder)
                        mv.visitFieldInsn(GETFIELD, "dev/mooner/neonjs/runtime/JSObject", "proto", "Ldev/mooner/neonjs/runtime/JSObject;")
                        if (!check) {
                            mv.visitVarInsn(ASTORE, holder)
                            continue
                        }
                        mv.visitInsn(DUP)
                        mv.visitVarInsn(ASTORE, holder)
                        mv.visitJumpInsn(IFNULL, l)
                        mv.visitVarInsn(ALOAD, dataLocal)
                        iconst(i.c)
                        mv.visitInsn(AALOAD)
                        mv.visitTypeInsn(CHECKCAST, "[Ljava/lang/Object;")
                        iconst(k)
                        mv.visitInsn(AALOAD)
                        val t = temp()
                        mv.visitVarInsn(ASTORE, t)
                        shapeCheckWith(holder, t, l)
                    }
                }
                mv.visitVarInsn(ALOAD, holder)
                mv.visitFieldInsn(GETFIELD, "dev/mooner/neonjs/runtime/JSObject", "props", PMAP_D)
                mv.visitFieldInsn(GETFIELD, PMAP, "values", CONSTS_D)
                iconst(i.b)
                if (op == Ir.LOAD_SLOT) {
                    mv.visitInsn(AALOAD)
                    done(i, JT.ANY)
                } else {
                    load(i.inputs[1], JT.ANY)
                    mv.visitInsn(AASTORE)
                }
                if (i.state != null) exits.add(specExit(l, i))
                return
            }
            Ir.LOAD_GLOBAL_LEX, Ir.LOAD_GLOBAL_SLOT -> {
                // the checks GlobalCache.load makes, against the entry the site's cache held; else an exit
                val l = Label()
                val ge = temp()
                frame()
                mv.visitFieldInsn(GETFIELD, FRAME, "realm", REALM_D)
                mv.visitFieldInsn(GETFIELD, "dev/mooner/neonjs/runtime/Realm", "globalEnv", GLOBAL_ENV_D)
                mv.visitVarInsn(ASTORE, ge)
                if (op == Ir.LOAD_GLOBAL_LEX) {
                    mv.visitVarInsn(ALOAD, ge)
                    mv.visitFieldInsn(GETFIELD, GLOBAL_ENV, "token", OBJ)
                    mv.visitVarInsn(ALOAD, dataLocal)
                    iconst(i.a)
                    mv.visitInsn(AALOAD)
                    mv.visitJumpInsn(IF_ACMPNE, l)
                    mv.visitVarInsn(ALOAD, ge)
                    mv.visitFieldInsn(GETFIELD, GLOBAL_ENV, "lexicalSlots", "[Ldev/mooner/neonjs/runtime/GlobalBinding;")
                    iconst(i.b)
                    mv.visitInsn(AALOAD)
                    mv.visitFieldInsn(GETFIELD, "dev/mooner/neonjs/runtime/GlobalBinding", "value", OBJ)
                    mv.visitInsn(DUP)
                    mv.visitFieldInsn(GETSTATIC, "dev/mooner/neonjs/runtime/Uninitialized", "INSTANCE", "Ldev/mooner/neonjs/runtime/Uninitialized;")
                    val ok = Label()
                    mv.visitJumpInsn(IF_ACMPNE, ok)
                    mv.visitInsn(POP)
                    mv.visitJumpInsn(GOTO, l)
                    mv.visitLabel(ok)
                } else {
                    mv.visitVarInsn(ALOAD, ge)
                    mv.visitFieldInsn(GETFIELD, GLOBAL_ENV, "lexicalEpoch", "I")
                    iconst(i.c)
                    mv.visitJumpInsn(IF_ICMPNE, l)
                    val g0 = temp()
                    mv.visitVarInsn(ALOAD, ge)
                    mv.visitFieldInsn(GETFIELD, GLOBAL_ENV, "global", "Ldev/mooner/neonjs/runtime/JSObject;")
                    mv.visitVarInsn(ASTORE, g0)
                    shapeCheck(g0, i.a, l)
                    mv.visitVarInsn(ALOAD, g0)
                    mv.visitFieldInsn(GETFIELD, "dev/mooner/neonjs/runtime/JSObject", "props", PMAP_D)
                    mv.visitFieldInsn(GETFIELD, PMAP, "values", CONSTS_D)
                    iconst(i.b)
                    mv.visitInsn(AALOAD)
                }
                done(i, JT.ANY)
                exits.add(specExit(l, i))
                return
            }
            Ir.CATCH_VALUE -> {
                // a catch entry: the JVM's exception on the stack becomes the JS value caught (and inlined calls end)
                frame()
                rt("catchValue", "(Ljava/lang/Throwable;$FRAME_D)$OBJ")
                done(i, JT.ANY)
                return
            }
            Ir.SET_HOME -> {
                val k = homeKind[i.a]!!
                load(i.inputs[0], k)
                mv.visitVarInsn(when (k) { JT.NUM -> DSTORE; JT.INT, JT.BOOL -> ISTORE; else -> ASTORE }, homeLocal[i.a]!!)
                return
            }
            Ir.LOAD_HOME -> {
                val k = homeKind[i.a] ?: JT.ANY
                val l = homeLocal[i.a]
                if (l == null) pushUndefined() // never set: the register keeps its initial value
                else mv.visitVarInsn(when (k) { JT.NUM -> DLOAD; JT.INT, JT.BOOL -> ILOAD; else -> ALOAD }, l)
                done(i, if (l == null) JT.ANY else k)
                return
            }
            Op.ITER_STEP, Op.ITER_STEP_U -> {
                storePc(i)
                load(i.inputs[0], JT.ANY)
                rt(if (op == Op.ITER_STEP) "iterStep" else "iterStepU", "($OBJ)$OBJ")
                done(i, JT.ANY)
                return
            }
            Op.FOR_IN_NEXT -> {
                storePc(i)
                load(i.inputs[0], JT.ANY)
                rt("forInNext", "($OBJ)$OBJ")
                done(i, JT.ANY)
                return
            }
            Op.ITER_REST -> {
                storePc(i)
                load(i.inputs[0], JT.ANY)
                frame()
                rt("iterRest", "($OBJ$FRAME_D)$OBJ")
                done(i, JT.ANY)
                return
            }
            Op.ITER_CLOSE, Op.ITER_CLOSE_THROW -> {
                storePc(i)
                load(i.inputs[0], JT.ANY)
                rt(if (op == Op.ITER_CLOSE) "iterClose" else "iterCloseThrow", "($OBJ)V")
                return
            }
            Op.MAKE_METHOD -> {
                storePc(i)
                load(i.inputs[0], JT.ANY)
                frame()
                consts()
                iconst(i.a)
                rt("makeMethod", "($OBJ$FRAME_D${CONSTS_D}I)$OBJ")
                done(i, JT.ANY)
                return
            }
            Ir.STRESS_EXIT -> {
                val l = Label()
                mv.visitVarInsn(ALOAD, L_AGENT)
                rt("stressExit", "($AGENT_D)Z")
                mv.visitJumpInsn(IFNE, l)
                exits.add(Exit(l, i.state!!, counted = false))
                return
            }
            Op.ADD, Op.SUB, Op.MUL, Op.DIV, Op.MOD, Op.EXP, Op.BAND, Op.BOR, Op.BXOR, Op.SHL, Op.SAR, Op.SHR,
            Op.LT, Op.GT, Op.LE, Op.GE, Op.EQ, Op.NE, Op.SEQ, Op.SNE -> if (binary(i)) return
            Op.NEG, Op.BNOT, Op.INC, Op.DEC, Op.TO_NUMERIC -> {
                if (i.kind == JT.INT && Ir.intArith(op)) return intUnary(i)
                val t = kindOf(i.inputs[0])
                if (JT.isNum(t)) {
                    val result = kinds.unaryResult(op, t)
                    when (op) {
                        Op.NEG -> { load(i.inputs[0], JT.NUM); mv.visitInsn(DNEG) } // -0 for 0
                        Op.BNOT -> if (result == JT.INT) {
                            load(i.inputs[0], t)
                            toInt32(t)
                            mv.visitInsn(ICONST_M1)
                            mv.visitInsn(IXOR)
                        } else {
                            load(i.inputs[0], JT.NUM)
                            rt("bnotD", "(D)D")
                        }
                        Op.INC -> { load(i.inputs[0], JT.NUM); mv.visitInsn(DCONST_1); mv.visitInsn(DADD) }
                        Op.DEC -> { load(i.inputs[0], JT.NUM); mv.visitInsn(DCONST_1); mv.visitInsn(DSUB) }
                        else -> load(i.inputs[0], t) // TO_NUMERIC of a number
                    }
                    done(i, result)
                    return
                }
            }
            Op.TO_NUMBER -> {
                val t = kindOf(i.inputs[0])
                if (t != JT.ANY) {
                    val result = kinds.result(i)
                    load(i.inputs[0], t)
                    if (t == JT.BOOL && result == JT.NUM) mv.visitInsn(I2D)
                    done(i, result)
                } else {
                    storePc(i)
                    load(i.inputs[0], JT.ANY)
                    rt("toNumberD", "($OBJ)D")
                    done(i, JT.NUM)
                }
                return
            }
            Op.NOT -> {
                val t = kindOf(i.inputs[0])
                load(i.inputs[0], t)
                truth(t, normalized = true)
                mv.visitInsn(ICONST_1)
                mv.visitInsn(IXOR)
                done(i, JT.BOOL)
                return
            }
            Op.GET_ELEM -> {
                val key = kindOf(i.inputs[1])
                if (key == JT.INT) {
                    // an array's dense element in place (JSArray.getIndexFast), the helper otherwise
                    storePc(i)
                    val o = temp()
                    val k = temp()
                    load(i.inputs[0], JT.ANY)
                    mv.visitVarInsn(ASTORE, o)
                    load(i.inputs[1], JT.INT)
                    mv.visitVarInsn(ISTORE, k)
                    val slow = Label()
                    val hole = Label()
                    val end = Label()
                    val a = denseIndex(o, k, slow)
                    mv.visitVarInsn(ALOAD, a)
                    mv.visitFieldInsn(GETFIELD, ARRAY, "dense", CONSTS_D)
                    mv.visitVarInsn(ILOAD, k)
                    mv.visitInsn(AALOAD)
                    mv.visitInsn(DUP)
                    mv.visitFieldInsn(GETSTATIC, HOLE, "INSTANCE", "L$HOLE;")
                    mv.visitJumpInsn(IF_ACMPEQ, hole)
                    mv.visitJumpInsn(GOTO, end)
                    mv.visitLabel(hole)
                    mv.visitInsn(POP)
                    mv.visitLabel(slow)
                    mv.visitVarInsn(ALOAD, o)
                    mv.visitVarInsn(ILOAD, k)
                    frame()
                    rt("getElemI", "(${OBJ}I$FRAME_D)$OBJ")
                    mv.visitLabel(end)
                    done(i, JT.ANY)
                    return
                }
                if (JT.isNum(key)) {
                    storePc(i)
                    load(i.inputs[0], JT.ANY)
                    load(i.inputs[1], key)
                    frame()
                    rt("getElemD", "(${OBJ}D$FRAME_D)$OBJ")
                    done(i, JT.ANY)
                    return
                }
            }
            Ir.LENGTH -> {
                val l = Label()
                val not = Label()
                val big = Label()
                val end = Label()
                val o = temp()
                load(i.inputs[0], JT.ANY)
                mv.visitVarInsn(ASTORE, o)
                mv.visitVarInsn(ALOAD, o)
                mv.visitTypeInsn(INSTANCEOF, ARRAY)
                mv.visitJumpInsn(IFEQ, not)
                mv.visitVarInsn(ALOAD, o)
                mv.visitTypeInsn(CHECKCAST, ARRAY)
                mv.visitFieldInsn(GETFIELD, ARRAY, "length", "J")
                mv.visitInsn(DUP2)
                mv.visitLdcInsn(Int.MAX_VALUE.toLong())
                mv.visitInsn(LCMP)
                mv.visitJumpInsn(IFGT, big)
                mv.visitInsn(L2I)
                mv.visitJumpInsn(GOTO, end)
                mv.visitLabel(big)
                mv.visitInsn(POP2)
                mv.visitJumpInsn(GOTO, l)
                mv.visitLabel(not)
                // a JS string (a CharSequence): its UTF-16 length
                mv.visitVarInsn(ALOAD, o)
                mv.visitTypeInsn(INSTANCEOF, "java/lang/CharSequence")
                mv.visitJumpInsn(IFEQ, l)
                mv.visitVarInsn(ALOAD, o)
                mv.visitTypeInsn(CHECKCAST, "java/lang/CharSequence")
                mv.visitMethodInsn(INVOKEINTERFACE, "java/lang/CharSequence", "length", "()I", true)
                mv.visitLabel(end)
                if (ints) done(i, JT.INT) else {
                    mv.visitInsn(I2D)
                    done(i, JT.NUM)
                }
                exits.add(specExit(l, i))
                return
            }
            Op.PUT_ELEM -> {
                val key = kindOf(i.inputs[1])
                val value = kindOf(i.inputs[2])
                val stored = kinds.result(i)
                if (JT.isNum(stored)) {
                    storePc(i)
                    load(i.inputs[0], JT.ANY)
                    load(i.inputs[1], key)
                    load(i.inputs[2], value)
                    frame()
                    val k = if (key == JT.INT) "I" else "D"
                    val v = if (stored == JT.INT) "I" else "D"
                    rt("setElem$k$v", "($OBJ$k$v$FRAME_D)$v")
                    done(i, stored)
                    return
                }
                if (key == JT.INT && value != JT.NUM) {
                    // an array's dense element written in place (JSArray.trySetIndexFast's first case), the helper
                    // otherwise
                    storePc(i)
                    val o = temp()
                    val k = temp()
                    load(i.inputs[0], JT.ANY)
                    mv.visitVarInsn(ASTORE, o)
                    load(i.inputs[1], JT.INT)
                    mv.visitVarInsn(ISTORE, k)
                    val slow = Label()
                    val end = Label()
                    val a = denseIndex(o, k, slow)
                    mv.visitVarInsn(ALOAD, a)
                    mv.visitFieldInsn(GETFIELD, ARRAY, "dense", CONSTS_D)
                    mv.visitVarInsn(ILOAD, k)
                    mv.visitInsn(AALOAD)
                    mv.visitFieldInsn(GETSTATIC, HOLE, "INSTANCE", "L$HOLE;")
                    mv.visitJumpInsn(IF_ACMPEQ, slow)
                    mv.visitVarInsn(ALOAD, a)
                    mv.visitFieldInsn(GETFIELD, ARRAY, "dense", CONSTS_D)
                    mv.visitVarInsn(ILOAD, k)
                    load(i.inputs[2], JT.ANY)
                    mv.visitInsn(AASTORE)
                    load(i.inputs[2], JT.ANY)
                    mv.visitJumpInsn(GOTO, end)
                    mv.visitLabel(slow)
                    mv.visitVarInsn(ALOAD, o)
                    mv.visitVarInsn(ILOAD, k)
                    load(i.inputs[2], JT.ANY)
                    frame()
                    rt("putElemIA", "(${OBJ}I$OBJ$FRAME_D)$OBJ")
                    mv.visitLabel(end)
                    done(i, JT.ANY)
                    return
                }
                if (JT.isNum(key)) {
                    storePc(i)
                    val vNum = key == JT.NUM && value == JT.NUM
                    load(i.inputs[0], JT.ANY)
                    load(i.inputs[1], key)
                    load(i.inputs[2], if (vNum) JT.NUM else JT.ANY)
                    frame()
                    when {
                        key == JT.INT -> rt("putElemIA", "(${OBJ}I$OBJ$FRAME_D)$OBJ")
                        vNum -> rt("putElemDD", "(${OBJ}DD$FRAME_D)$OBJ")
                        else -> rt("putElemDA", "(${OBJ}D$OBJ$FRAME_D)$OBJ")
                    }
                    done(i, JT.ANY)
                    return
                }
            }
            Ir.CHECK_TDZ -> {
                storePc(i)
                load(i.inputs[0], JT.ANY)
                iconst(i.a)
                consts()
                rt("tdz", "(${OBJ}I$CONSTS_D)$OBJ")
                if (i.hasResult) done(i, JT.ANY) else mv.visitInsn(POP)
                return
            }
            Op.TO_KEY_FOR_BASE -> {
                storePc(i)
                load(i.inputs[0], JT.ANY)
                load(i.inputs[1], JT.ANY)
                rt("keyForBase", "($OBJ$OBJ)$OBJ")
                done(i, JT.ANY)
                return
            }
            Op.SET_FUNCTION_NAME -> {
                storePc(i)
                load(i.inputs[0], JT.ANY)
                load(i.inputs[1], JT.ANY)
                iconst(i.a)
                consts()
                rt("setFunctionName", "($OBJ${OBJ}I$CONSTS_D)V")
                return
            }
        }
        // the baseline's code for the instruction, with Object operands
        storePc(i)
        for (v in i.inputs) load(v, JT.ANY)
        if (!emitHelper(op, i.pc, i.a, i.b, i.c)) throw JitBailout("no code for ${Ir.name(op)}")
        if (i.hasResult) done(i, JT.ANY)
    }

    /**
     * A binary operator with at least one number operand, as the baseline compiles it (Gen.emitBinary): bitwise
     * operators on two numbers on ToInt32 ints, comparisons of two ints on ints, the rest on doubles or through the
     * mixed helpers. False when neither operand is a number (the generic code then).
     */
    private fun binary(i: Inst): Boolean {
        val op = i.op
        val l = i.inputs[0]
        val r = i.inputs[1]
        val kl = kindOf(l)
        val kr = kindOf(r)
        val cls = JT.binaryClass(op, kl, kr)
        if (cls == JT.GENERIC) return false
        if (i.kind == JT.INT && (Ir.intArith(op) || i.wraps)) {
            intBinary(i)
            return true
        }
        val result = JT.binaryResult(op, cls, intKind)
        if (JT.isBitwise(op) && cls == JT.NN && ints) {
            load(l, kl)
            toInt32(kl)
            load(r, kr)
            toInt32(kr)
            // the JVM masks shift counts to 5 bits, as JS does
            when (op) {
                Op.BAND -> mv.visitInsn(IAND)
                Op.BOR -> mv.visitInsn(IOR)
                Op.BXOR -> mv.visitInsn(IXOR)
                Op.SHL -> mv.visitInsn(ISHL)
                Op.SAR -> mv.visitInsn(ISHR)
                else -> {
                    // >>>: the uint32 as a double
                    mv.visitInsn(IUSHR)
                    mv.visitInsn(I2L)
                    mv.visitLdcInsn(0xFFFFFFFFL)
                    mv.visitInsn(LAND)
                    mv.visitInsn(L2D)
                }
            }
            done(i, result)
            return true
        }
        if (JT.isCompare(op) && kl == JT.INT && kr == JT.INT) {
            load(l, JT.INT)
            load(r, JT.INT)
            compareInts(op)
            done(i, JT.BOOL)
            return true
        }
        val base = when (op) {
            Op.ADD -> "add"; Op.SUB -> "sub"; Op.MUL -> "mul"; Op.DIV -> "div"; Op.MOD -> "mod"; Op.EXP -> "exp"
            Op.BAND -> "band"; Op.BOR -> "bor"; Op.BXOR -> "bxor"; Op.SHL -> "shl"; Op.SAR -> "sar"; Op.SHR -> "shr"
            Op.LT -> "lt"; Op.GT -> "gt"; Op.LE -> "le"; Op.GE -> "ge"; Op.EQ -> "eq"; Op.NE -> "ne"
            Op.SEQ -> "seq"; else -> "sne"
        }
        // the helpers of the bitwise operators (but >>>) return the int32
        val intResult = JT.isBitwise(op) && op != Op.SHR
        val ret = when {
            JT.isCompare(op) -> "Z"
            op == Op.ADD && cls != JT.NN -> OBJ
            intResult -> "I"
            else -> "D"
        }
        when (cls) {
            JT.NN -> {
                load(l, JT.NUM)
                load(r, JT.NUM)
                when (op) {
                    Op.ADD -> mv.visitInsn(DADD)
                    Op.SUB -> mv.visitInsn(DSUB)
                    Op.MUL -> mv.visitInsn(DMUL)
                    Op.DIV -> mv.visitInsn(DDIV)
                    Op.MOD -> mv.visitInsn(DREM) // fmod: the sign of the dividend, as JS %
                    Op.EXP -> rt("powDD", "(DD)D") // not Math.pow: 1 ** Infinity is NaN in JS
                    // bitwise ones without INT
                    else -> if (JT.isCompare(op)) compareDoubles(op) else rt(base + "DD", "(DD)D")
                }
            }
            JT.NA -> {
                storePc(i)
                load(l, JT.NUM)
                load(r, JT.ANY)
                if (JT.isCompare(op)) compareMixed(op, base, numLeft = true) else rt(base + "DA", "(D$OBJ)$ret")
            }
            else -> {
                storePc(i)
                load(l, JT.ANY)
                load(r, JT.NUM)
                if (JT.isCompare(op)) compareMixed(op, base, numLeft = false) else rt(base + "AD", "(${OBJ}D)$ret")
            }
        }
        if (intResult && cls != JT.NN && result == JT.NUM) mv.visitInsn(I2D)
        done(i, result)
        return true
    }

    /**
     * JitRt.inlineEnter for a call inlined in the function compiled: the depth and the call budget counted in place,
     * the helper called only where one runs out (it counts them again, then throws or checks for an interrupt).
     */
    private fun inlineEnter(i: Inst) {
        val slow = Label()
        val done = Label()
        frame()
        iconst(i.a)
        mv.visitFieldInsn(PUTFIELD, FRAME, "pc", "I")
        mv.visitVarInsn(ALOAD, L_AGENT)
        mv.visitFieldInsn(GETFIELD, AGENT, "depth", "I")
        mv.visitVarInsn(ALOAD, L_AGENT)
        mv.visitFieldInsn(GETFIELD, AGENT, "maxDepth", "I")
        mv.visitJumpInsn(IF_ICMPGE, slow)
        mv.visitVarInsn(ALOAD, L_AGENT)
        mv.visitFieldInsn(GETFIELD, AGENT, "callBudget", "I")
        mv.visitJumpInsn(IFLE, slow)
        mv.visitVarInsn(ALOAD, L_AGENT)
        mv.visitInsn(DUP)
        mv.visitFieldInsn(GETFIELD, AGENT, "depth", "I")
        mv.visitInsn(ICONST_1)
        mv.visitInsn(IADD)
        mv.visitFieldInsn(PUTFIELD, AGENT, "depth", "I")
        mv.visitVarInsn(ALOAD, L_AGENT)
        mv.visitInsn(DUP)
        mv.visitFieldInsn(GETFIELD, AGENT, "callBudget", "I")
        mv.visitInsn(ICONST_1)
        mv.visitInsn(ISUB)
        mv.visitFieldInsn(PUTFIELD, AGENT, "callBudget", "I")
        frame()
        mv.visitInsn(ICONST_0)
        mv.visitFieldInsn(PUTFIELD, FRAME, "inlinePc", "I")
        frame()
        load(i.inputs[0], JT.ANY)
        mv.visitTypeInsn(CHECKCAST, CLOSURE)
        mv.visitFieldInsn(PUTFIELD, FRAME, "inlineFn", CLOSURE_D)
        mv.visitJumpInsn(GOTO, done)
        mv.visitLabel(slow)
        frame()
        load(i.inputs[0], JT.ANY)
        mv.visitTypeInsn(CHECKCAST, CLOSURE)
        iconst(i.a)
        rt("inlineEnter", "($FRAME_D$CLOSURE_D" + "I)V")
        mv.visitLabel(done)
    }

    /**
     * The array in local [o] (a JSArray, not sparse) with int [k] (local) within its dense elements, in a new local;
     * anything else jumps to [slow].
     */
    private fun denseIndex(o: Int, k: Int, slow: Label): Int {
        mv.visitVarInsn(ALOAD, o)
        mv.visitTypeInsn(INSTANCEOF, ARRAY)
        mv.visitJumpInsn(IFEQ, slow)
        val a = temp()
        mv.visitVarInsn(ALOAD, o)
        mv.visitTypeInsn(CHECKCAST, ARRAY)
        mv.visitVarInsn(ASTORE, a)
        mv.visitVarInsn(ALOAD, a)
        mv.visitFieldInsn(GETFIELD, ARRAY, "sparse", "Z")
        mv.visitJumpInsn(IFNE, slow)
        mv.visitVarInsn(ILOAD, k)
        mv.visitJumpInsn(IFLT, slow)
        mv.visitVarInsn(ILOAD, k)
        mv.visitVarInsn(ALOAD, a)
        mv.visitFieldInsn(GETFIELD, ARRAY, "denseLen", "I")
        mv.visitJumpInsn(IF_ICMPGE, slow)
        return a
    }

    /** [v] (a number) as an int in a new local. */
    private fun intLocal(v: Value): Int {
        val t = temp()
        val k = kindOf(v)
        load(v, k)
        toInt32(k)
        mv.visitVarInsn(ISTORE, t)
        return t
    }

    /**
     * Int32 arithmetic of ints (Ir.intArith, Kinds): an exit before the instruction where the result is not an int32
     * or is -0, the int otherwise; an ADD, SUB or `>>>` that wraps (Inst.wraps) has no exit.
     */
    private fun intBinary(i: Inst) {
        val a = intLocal(i.inputs[0])
        val b = intLocal(i.inputs[1])
        val l = Label()
        val r = temp()
        fun ab() {
            mv.visitVarInsn(ILOAD, a)
            mv.visitVarInsn(ILOAD, b)
        }
        when (i.op) {
            Op.SHR -> { ab(); mv.visitInsn(IUSHR) }
            Op.ADD, Op.SUB -> {
                ab()
                mv.visitInsn(if (i.op == Op.ADD) IADD else ISUB)
                if (!i.wraps) {
                    // overflow: for a + b, a and b of one sign and r of the other; for a - b, a and b of different signs
                    // and r of b's
                    mv.visitVarInsn(ISTORE, r)
                    mv.visitVarInsn(ILOAD, a)
                    mv.visitVarInsn(ILOAD, r)
                    mv.visitInsn(IXOR)
                    mv.visitVarInsn(ILOAD, if (i.op == Op.ADD) b else a)
                    if (i.op == Op.ADD) mv.visitVarInsn(ILOAD, r) else mv.visitVarInsn(ILOAD, b)
                    mv.visitInsn(IXOR)
                    mv.visitInsn(IAND)
                    mv.visitJumpInsn(IFLT, l)
                    mv.visitVarInsn(ILOAD, r)
                }
            }
            Op.MUL -> {
                // the exact product as a long: an int32 that is not -0 (0 of a negative operand)
                val p = tempD()
                mv.visitVarInsn(ILOAD, a)
                mv.visitInsn(I2L)
                mv.visitVarInsn(ILOAD, b)
                mv.visitInsn(I2L)
                mv.visitInsn(LMUL)
                mv.visitVarInsn(LSTORE, p)
                mv.visitVarInsn(LLOAD, p)
                mv.visitInsn(L2I)
                mv.visitVarInsn(ISTORE, r)
                mv.visitVarInsn(ILOAD, r)
                mv.visitInsn(I2L)
                mv.visitVarInsn(LLOAD, p)
                mv.visitInsn(LCMP)
                mv.visitJumpInsn(IFNE, l)
                val ok = Label()
                mv.visitVarInsn(ILOAD, r)
                mv.visitJumpInsn(IFNE, ok)
                ab()
                mv.visitInsn(IOR)
                mv.visitJumpInsn(IFLT, l)
                mv.visitLabel(ok)
                mv.visitVarInsn(ILOAD, r)
            }
            else -> {
                // MOD: NaN for a divisor 0, -0 for a remainder 0 of a negative dividend (MIN_VALUE % -1 included)
                mv.visitVarInsn(ILOAD, b)
                mv.visitJumpInsn(IFEQ, l)
                ab()
                mv.visitInsn(IREM)
                mv.visitVarInsn(ISTORE, r)
                val ok = Label()
                mv.visitVarInsn(ILOAD, r)
                mv.visitJumpInsn(IFNE, ok)
                mv.visitVarInsn(ILOAD, a)
                mv.visitJumpInsn(IFLT, l)
                mv.visitLabel(ok)
                mv.visitVarInsn(ILOAD, r)
            }
        }
        done(i, JT.INT)
        if (i.state != null) exits.add(specExit(l, i))
        else if (!i.wraps) throw JitBailout("int arithmetic without its exit")
    }

    /** INC, DEC and NEG of an int (Ir.intArith): an exit before the instruction where the result leaves the int32 range or is -0. */
    private fun intUnary(i: Inst) {
        val a = intLocal(i.inputs[0])
        val l = Label()
        // (without a state: a step that cannot leave the range, IrBuilder.boundedSteps)
        if (i.state != null) {
            mv.visitVarInsn(ILOAD, a)
            when (i.op) {
                Op.INC -> { mv.visitLdcInsn(Int.MAX_VALUE); mv.visitJumpInsn(IF_ICMPEQ, l) }
                Op.DEC -> { mv.visitLdcInsn(Int.MIN_VALUE); mv.visitJumpInsn(IF_ICMPEQ, l) }
                // -0 and -MIN_VALUE: 0 and MIN_VALUE are those with no bit but the sign's
                else -> { mv.visitLdcInsn(Int.MAX_VALUE); mv.visitInsn(IAND); mv.visitJumpInsn(IFEQ, l) }
            }
        } else if (i.op == Op.NEG) throw JitBailout("int negation without its exit")
        mv.visitVarInsn(ILOAD, a)
        when (i.op) {
            Op.INC -> { mv.visitInsn(ICONST_1); mv.visitInsn(IADD) }
            Op.DEC -> { mv.visitInsn(ICONST_1); mv.visitInsn(ISUB) }
            else -> mv.visitInsn(INEG)
        }
        done(i, JT.INT)
        if (i.state != null) exits.add(specExit(l, i))
    }

    companion object {
        /** The class [name] (internal form) implementing CompiledCode with [g]'s code, for class file [version]. */
        private const val SUPER = "dev/mooner/neonjs/jit/opt/T2Code"
        private const val PMAP = "dev/mooner/neonjs/runtime/PropertyMap"
        private const val PMAP_D = "L$PMAP;"
        private const val INIT_D = "([Ljava/lang/Object;$CODEBLOCK_D)V"

        /**
         * The class [name] (internal form): a T2Code with [g]'s code, constructed with the graph's data and the code
         * block, for class file [version].
         */
        fun generate(g: Graph, name: String, version: Int, ints: Boolean, elems: Boolean): ByteArray {
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
            val mv = cw.visitMethod(ACC_PUBLIC or ACC_FINAL, "run", "($FRAME_D)$OBJ", null, null)
            mv.visitCode()
            IrCodegen(g, mv, Kinds(ints, elems), ints, cw, name).emit()
            mv.visitMaxs(0, 0)
            mv.visitEnd()
            cw.visitEnd()
            return cw.toByteArray()
        }
    }
}
