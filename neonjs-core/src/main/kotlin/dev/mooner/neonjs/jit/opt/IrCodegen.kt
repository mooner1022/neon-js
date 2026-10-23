package dev.mooner.neonjs.jit.opt

import dev.mooner.neonjs.compiler.Op
import dev.mooner.neonjs.jit.*
import dev.mooner.neonjs.runtime.Null
import dev.mooner.neonjs.runtime.Undefined
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*

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
        val code = i.block!!.site?.code ?: g.code
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
        val params = (e.state.values.asList() + (e.state.site?.values() ?: emptyList())).filter { it !is Const }.distinct()
        mv.visitVarInsn(ALOAD, 0)
        frame()
        for (v in params) load(v, v.kind)
        val desc = "($FRAME_D" + params.joinToString("") { typeOf(it.kind) } + ")$OBJ"
        mv.visitMethodInsn(INVOKESPECIAL, className, "exit$n", desc, false)
        mv.visitInsn(ARETURN)
        exitMethod(n, e, params, desc)
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
        fun value(v: Value) {
            if (v is Const) constObject(em, v)
            else {
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

    /** Pushes constant [c] as the Object the interpreter holds (as [pushConst] does with an Object wanted). */
    private fun constObject(em: JvmEmitter, c: Const) {
        val v = c.value
        when {
            v === Undefined -> em.pushUndefined()
            v === Null -> em.getstatic("dev/mooner/neonjs/runtime/Null", "INSTANCE", "Ldev/mooner/neonjs/runtime/Null;")
            v == true -> em.getstatic("java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;")
            v == false -> em.getstatic("java/lang/Boolean", "FALSE", "Ljava/lang/Boolean;")
            c.k >= 0 -> {
                // from the constants of its code block: the frame's, or one in T2Code.data
                val cb = c.code
                if (cb == null || cb === g.code) {
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
            for (i in b.insts) if (i.hasResult && i in used) local[i] = slotFor(i.kind)
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
        val entries = g.blocks.filter { b ->
            val regs = b.entryRegs
            regs != null && b.entryDepth == 0 && java.util.Arrays.binarySearch(targets, b.pc) >= 0 &&
                live[b]!!.all { v -> regs.any { it === v } }
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
            for ((k, b) in entries.withIndex()) {
                mv.visitLabel(starts[k])
                for ((r, v) in b.entryRegs!!.withIndex()) {
                    if (v is Const || local[v] == null) continue
                    frame()
                    mv.visitFieldInsn(GETFIELD, FRAME, "slots", CONSTS_D)
                    iconst(r)
                    mv.visitInsn(AALOAD)
                    if (v.kind == JT.ANY) {
                        store(v)
                        continue
                    }
                    // a kind may rest on a speculation the interpreter did not make (a profiled load, say): a register
                    // without a value of it there means no entry this time (the frame is not changed before)
                    mv.visitVarInsn(ASTORE, obj)
                    mv.visitVarInsn(ALOAD, obj)
                    mv.visitTypeInsn(INSTANCEOF, if (v.kind == JT.BOOL) "java/lang/Boolean" else "java/lang/Double")
                    mv.visitJumpInsn(IFEQ, none)
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
                }
                mv.visitJumpInsn(GOTO, label[b])
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
                        for (v in s.values) if (v !is Const) live.add(v)
                        s.site?.values()?.forEach { v -> if (v !is Const) live.add(v) }
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

    /** Jumps to [exit] unless the object in local [obj] has a property map whose shape is the graph's data [shapeIndex]. */
    private fun shapeCheck(obj: Int, shapeIndex: Int, exit: Label) {
        val t = temp()
        mv.visitVarInsn(ALOAD, dataLocal)
        iconst(shapeIndex)
        mv.visitInsn(AALOAD)
        mv.visitVarInsn(ASTORE, t)
        shapeCheckWith(obj, t, exit)
    }

    /** [shapeCheck] with the shape in local [shape]. */
    private fun shapeCheckWith(obj: Int, shape: Int, exit: Label) {
        mv.visitVarInsn(ALOAD, obj)
        mv.visitFieldInsn(GETFIELD, "dev/mooner/neonjs/runtime/JSObject", "props", PMAP_D)
        mv.visitInsn(DUP)
        val ok = Label()
        mv.visitJumpInsn(IFNONNULL, ok)
        mv.visitInsn(POP)
        mv.visitJumpInsn(GOTO, exit)
        mv.visitLabel(ok)
        mv.visitFieldInsn(GETFIELD, PMAP, "shape", "Ldev/mooner/neonjs/runtime/Shape;")
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
        when (op) {
            Ir.PARAM -> {
                frame()
                iconst(i.a)
                rt("arg", "(${FRAME_D}I)$OBJ")
                done(i, JT.ANY)
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
            Ir.LOAD_SLOT, Ir.STORE_SLOT -> {
                // the receiver's property map has the shape the cache saw (then the prototypes' do), else an exit
                val l = Label()
                val obj = i.inputs[0]
                load(obj, JT.ANY)
                mv.visitTypeInsn(INSTANCEOF, "dev/mooner/neonjs/runtime/JSObject")
                mv.visitJumpInsn(IFEQ, l)
                load(obj, JT.ANY)
                mv.visitTypeInsn(CHECKCAST, "dev/mooner/neonjs/runtime/JSObject")
                val holder = temp()
                mv.visitVarInsn(ASTORE, holder)
                shapeCheck(holder, i.a, l)
                if (i.c >= 0) {
                    // the prototypes up to the holder, each with the shape the cache saw
                    val chain = g.data[i.c] as Array<*>
                    for (k in chain.indices) {
                        mv.visitVarInsn(ALOAD, holder)
                        mv.visitFieldInsn(GETFIELD, "dev/mooner/neonjs/runtime/JSObject", "proto", "Ldev/mooner/neonjs/runtime/JSObject;")
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
                if (JT.isNum(key)) {
                    storePc(i)
                    load(i.inputs[0], JT.ANY)
                    load(i.inputs[1], key)
                    frame()
                    if (key == JT.INT) rt("getElemI", "(${OBJ}I$FRAME_D)$OBJ") else rt("getElemD", "(${OBJ}D$FRAME_D)$OBJ")
                    done(i, JT.ANY)
                    return
                }
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
