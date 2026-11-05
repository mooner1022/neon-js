package dev.mooner.neonjs.jit.opt

import dev.mooner.neonjs.compiler.CodeBlock
import dev.mooner.neonjs.compiler.Op
import dev.mooner.neonjs.jit.Inlining
import dev.mooner.neonjs.jit.JT
import dev.mooner.neonjs.jit.JitBailout
import dev.mooner.neonjs.compiler.ValueProfile
import dev.mooner.neonjs.runtime.Null
import dev.mooner.neonjs.runtime.PropCache
import dev.mooner.neonjs.runtime.PropIC
import dev.mooner.neonjs.runtime.GlobalSite
import dev.mooner.neonjs.runtime.JSObject
import dev.mooner.neonjs.runtime.PropSite
import dev.mooner.neonjs.runtime.Undefined

/**
 * JavaScript's builder of the optimizing tier's graphs ([GraphBuilder]): decodes the bytecode ([Op]), builds each
 * instruction (the generic form, or what the profiles and caches allow: a shape-guarded slot, a number checked once,
 * a builtin computed in place), and refines the kinds (which numbers stay int32s).
 *
 * Calls whose callee the interpreter's feedback or the compiler's static targets name (Inlining.plan's sources), and
 * that Inlining.eligible accepts, have the callee's body built in place ([JsBody.inline]) behind a guard that exits to
 * the interpreter, before the CALL, when the function called is not a closure of that code block in the frame's realm.
 * The callee's registers are values of the graph like the caller's, so arguments and results stay unboxed.
 *
 * Instructions the IR does not have yet ([arity] -1) are not built ([JitBailout]): such blocks keep their baseline
 * code.
 */
internal class IrBuilder(
    cb: CodeBlock,
    private val ints: Boolean,
    elems: Boolean,
    /** Whether the code gets the deopt stress mode's exits: at the start and the loop headers of each body. */
    stressExits: Boolean = false,
    /** Calls compiled inlined into one another at most (0: none). */
    private val maxInlineDepth: Int = if (Inlining.ENABLED) MAX_INLINE_DEPTH else 0,
    /** Whether the code speculates on the interpreter's value profiles and the property caches (T2.SPECULATE). */
    speculate: Boolean = T2.SPECULATE,
    /** Whether loops with a call of a parameter are copied on the closure it is (Unswitch). */
    private val unswitch: Boolean = true,
    /**
     * Whether the code may exit (false: a baseline, docs/IR_NEUTRALITY.md §5): without, a call inlined is a branch on
     * the closure to its body or to the call made as a call, and nothing else speculates ([speculate] false too).
     */
    private val exits: Boolean = true,
) : GraphBuilder(cb, Graph(cb, JsOps, JsKinds.of(ints, elems), Undefined, JsDialect), stressExits, speculate) {
    /** Bytecode ints of the bodies inlined so far (a budget). */
    private var inlined = 0

    override fun unbuildable(): String? = if (cb.isGenerator || cb.isAsync) "generator or async" else null

    override fun root(): Body = JsBody(cb, null)

    override fun entry(start: Block): Array<Value> {
        val regs = Array<Value>(cb.numRegs) { dead() }
        cb.paramRegs?.forEachIndexed { i, r ->
            if (r >= 0) regs[r] = add(start, Inst(Ir.PARAM, NO_VALUES, i, 0, 0, -1, -1).also { it.hasResult = true })
        }
        // parameters that only ever received numbers are taken as numbers, leaving at the start otherwise
        val params = cb.profile?.params
        // (not where such a check failed before: CodeBlock.t2Failed, the parameters' bit)
        if (speculate && params != null && !cb.t2FailedAt(cb.code.size)) {
            val entry = FrameState(0, regs.copyOf())
            cb.paramRegs?.forEachIndexed { i, r ->
                if (r >= 0 && i < params.size) numberCheck(params[i].toInt() and 0xFF)?.let { op ->
                    val check = add(start, Inst(op, arrayOf(regs[r]), 0, 0, 0, -1, -1).also { it.hasResult = true })
                    check.state = entry
                    regs[r] = check
                }
            }
        }
        return regs
    }

    override fun refineKinds(kinds: Kinds) {
        if (demandInts()) kinds.infer(g)
        if (preferDoubles()) kinds.infer(g)
        intArith(kinds)
        foldChecks(kinds)
    }

    override fun afterFacts(kinds: Kinds) = foldChecks(kinds)

    /**
     * A value checked as an int32 (CHECK_INT32) is held as an int, which pays only where it is used as one: a bitwise
     * operand, an element key, a comparison with another int, an int phi. Where it is only used as a double, it would be
     * widened at every use (in a loop, every iteration), so it is checked as a number instead, once. Returns whether a
     * check changed (kinds are inferred again then).
     */
    private fun preferDoubles(): Boolean {
        val asInt = HashSet<Value>()
        for (b in g.blocks) {
            for (p in b.phis) if (p.kind == JT.INT) asInt.addAll(p.inputs)
            for (i in b.insts) when {
                JT.isBitwise(i.op) || i.op == Op.BNOT -> asInt.addAll(i.inputs)
                JT.isCompare(i.op) -> if (i.inputs[0].kind == JT.INT && i.inputs[1].kind == JT.INT) asInt.addAll(i.inputs)
                Ir.intArith(i.op) && i.kind == JT.INT -> asInt.addAll(i.inputs)
                i.op == Op.GET_ELEM || i.op == Op.PUT_ELEM -> asInt.add(i.inputs[1])
            }
        }
        var changed = false
        for (b in g.blocks) for (i in b.insts) if (i.op == Ir.CHECK_INT32 && i !in asInt) {
            i.op = Ir.CHECK_NUMBER
            changed = true
        }
        return changed
    }

    /**
     * Int32 arithmetic (Ir.intArith) pays where its int is used as one: a bitwise operand, an element key, or the
     * operand of such arithmetic (through phis); and a remainder always (the double one is a library call). A comparison
     * costs the same on doubles: a counter only compared stays one, as the baseline's (no overflow check). Elsewhere (a sum only read as a number) it would cost an overflow check where doubles cost nothing and can
     * be moved out of a loop, so it is made on doubles: its state goes. Returns whether one changed (kinds are inferred
     * again then).
     */
    private fun demandInts(): Boolean {
        val demanded = HashSet<Value>()
        val work = ArrayDeque<Value>()
        fun demand(v: Value) {
            if (v.kind == JT.INT && demanded.add(v)) work.add(v)
        }
        for (b in g.blocks) for (i in b.insts) when {
            JT.isBitwise(i.op) || i.op == Op.BNOT -> i.inputs.forEach { demand(it) }
            i.op == Op.GET_ELEM || i.op == Op.PUT_ELEM -> demand(i.inputs[1])
            i.op == Op.MOD && i.state != null && i.kind == JT.INT -> demand(i)
        }
        while (work.isNotEmpty()) {
            when (val v = work.removeLast()) {
                is Phi -> v.inputs.forEach { demand(it) }
                is Inst -> if (Ir.intArith(v.op) || v.op == Op.TO_NUMERIC) v.inputs.forEach { demand(it) }
            }
        }
        var changed = false
        for (b in g.blocks) for (i in b.insts) if (Ir.intArith(i.op) && i.state != null && i.kind == JT.INT && i !in demanded) {
            i.state = null
            changed = true
        }
        return changed
    }

    /**
     * After the kinds: the int32 arithmetic that stays on doubles needs no exit (its state goes); an int ADD or SUB whose
     * every use is a bitwise operator (which takes its operands to 32 bits) wraps around instead of exiting, as its
     * exact result would be truncated the same way; and so does `>>>` of numbers whose uses all are bitwise: its uint32
     * stays the int of the same bits (Inst.wraps).
     */
    private fun intArith(kinds: Kinds) {
        val uses = HashMap<Value, ArrayList<Any>>()
        for (b in g.blocks) {
            for (p in b.phis) for (v in p.inputs) uses.getOrPut(v) { ArrayList() }.add(p)
            for (i in b.insts) for (v in i.inputs) uses.getOrPut(v) { ArrayList() }.add(i)
        }
        fun truncated(v: Inst) = uses[v]?.all { it is Inst && JT.isBitwise(it.op) } == true
        for (b in g.blocks) for (i in b.insts) {
            if (Ir.intArith(i.op) && i.state != null) {
                if (i.kind != JT.INT) i.state = null
                else if ((i.op == Op.ADD || i.op == Op.SUB) && truncated(i)) {
                    i.wraps = true
                    i.state = null
                }
            } else if (i.op == Op.SHR && ints && i.inputs.all { JT.isNum(kinds.of(it)) } && truncated(i)) {
                i.kind = JT.INT
                i.wraps = true
            }
        }
        boundedSteps(kinds)
    }

    /**
     * A loop counter's `++` where `i < n` holds (n an int: i is below the largest int32) cannot leave the int32 range,
     * nor `--` where `i > n` holds: no check, no exit (an exit in a loop also keeps ART from optimizing it). The test
     * holds where the branch on it goes, if that block has no other way in and dominates the step.
     */
    private fun boundedSteps(kinds: Kinds) {
        val index = HashMap<Block, Int>()
        for ((k, b) in g.blocks.withIndex()) index[b] = k
        val idom by lazy { IrVerifier.dominators(g, index) }
        fun dominates(a: Block, b: Block): Boolean {
            var x: Block? = b
            while (x != null) {
                if (x === a) return true
                val d = idom[x]
                x = if (d == null || d === x || index[d] == null) null else d
            }
            return false
        }
        // the blocks where v < n (below) or v > n (above) holds, n an int, by the branches testing it
        val below = HashMap<Value, ArrayList<Block>>()
        val above = HashMap<Value, ArrayList<Block>>()
        for (b in g.blocks) {
            val t = b.insts.lastOrNull() ?: continue
            val c = t.inputs.firstOrNull() as? Inst ?: continue
            if (t.op != Ir.BRANCH || (t.a != Op.JUMP_IF_FALSE && t.a != Op.JUMP_IF_TRUE) || c.inputs.size != 2) continue
            val holds = if (t.a == Op.JUMP_IF_FALSE) t.targets[1] else t.targets[0]
            if (holds.preds.size != 1) continue
            val (l, r) = c.inputs[0] to c.inputs[1]
            when {
                c.op == Op.LT && kinds.of(r) == JT.INT -> below.getOrPut(l) { ArrayList() }.add(holds)
                c.op == Op.GT && kinds.of(l) == JT.INT -> below.getOrPut(r) { ArrayList() }.add(holds)
                c.op == Op.GT && kinds.of(r) == JT.INT -> above.getOrPut(l) { ArrayList() }.add(holds)
                c.op == Op.LT && kinds.of(l) == JT.INT -> above.getOrPut(r) { ArrayList() }.add(holds)
            }
        }
        if (below.isEmpty() && above.isEmpty()) return
        for (b in g.blocks) for (i in b.insts) {
            if ((i.op != Op.INC && i.op != Op.DEC) || i.state == null || i.kind != JT.INT) continue
            var v = i.inputs[0]
            if (v is Inst && v.op == Op.TO_NUMERIC) v = v.inputs[0]
            val where = (if (i.op == Op.INC) below[v] else above[v]) ?: continue
            if (where.any { dominates(it, b) }) i.state = null
        }
    }

    /**
     * Number checks of values that are numbers already (a literal's property read as the value it was made of, a
     * checked load checked again): one of a number of its own kind goes, its uses taking the value; another (a double
     * of an int) only converts it and needs no exit, so its state goes (an exit in a loop keeps ART from optimizing it).
     */
    private fun foldChecks(kinds: Kinds) {
        var folded = false
        for (b in g.blocks) for (i in b.insts) {
            if (i.op != Ir.CHECK_NUMBER && i.op != Ir.CHECK_INT32) continue
            val k = kinds.of(i.inputs[0])
            if (!JT.isNum(k) || (i.op == Ir.CHECK_INT32 && k != JT.INT)) continue
            if (k == i.kind) {
                replaced[i] = i.inputs[0]
                folded = true
            } else i.state = null
        }
        if (folded) resolveAll()
    }
    /** A JavaScript function's body: its bytecode decoded, each instruction built. */
    private inner class JsBody(code: CodeBlock, site: InlineSite?, outerHandler: Block? = null) : Body(code, site, outerHandler) {
        override fun length(pc: Int): Int = Op.length(bc, pc)

        override fun operand(pc: Int, k: Int): Int = if (Op.operands[bc[pc]] >= k) bc[pc + k] else 0

        override fun targets(pc: Int): IntArray? {
            val op = bc[pc]
            val next = pc + Op.length(bc, pc)
            return when {
                op == Op.JUMP -> intArrayOf(bc[pc + 1])
                // a conditional jump to the next instruction is one edge (built as a GOTO: ToBoolean and the nullish
                // tests have no effect)
                isJumpOp(op) -> if (bc[pc + 1] == next) intArrayOf(next) else intArrayOf(bc[pc + 1], next)
                op == Op.ITER_STEP || op == Op.FOR_IN_NEXT -> intArrayOf(bc[pc + 2], next)
                op == Op.JUMP_TABLE -> IntArray(bc[pc + 2] + 1) { bc[pc + 3 + it] }
                op == Op.RETURN || op == Op.THROW || op == Op.THROW_ERROR -> IntArray(0)
                else -> null
            }
        }

        override fun supported(pc: Int): Boolean {
            val op = bc[pc]
            return isJumpOp(op) || op == Op.RETURN || op == Op.THROW || op == Op.THROW_ERROR || arity(bc, op, pc) >= 0 ||
                op in BUILT_OPS
        }

        override fun regUse(pc: Int): Pair<Int, Int> = when (bc[pc]) {
            Op.LOAD_REG, Op.LOAD_REG_TDZ, Op.CHECK_REG_TDZ, Op.ITER_STEP, Op.ITER_STEP_U, Op.ITER_REST, Op.ITER_CLOSE,
            Op.ITER_CLOSE_THROW, Op.FOR_IN_NEXT, Op.JUMP_TABLE -> bc[pc + 1] to -1
            Op.MAKE_METHOD -> bc[pc + 2] to -1
            Op.STORE_REG -> -1 to bc[pc + 1]
            // (its old value read: `this` once only)
            Op.INIT_THIS_REG -> bc[pc + 1] to bc[pc + 1]
            else -> -1 to -1
        }

        override fun nameAt(pc: Int): String = Op.names[bc[pc]]

        override fun buildOp(at: At): Boolean = with(at) {
            when (op) {
                Op.NOP -> {}
                Op.PUSH_UNDEF -> stack.add(dead())
                Op.PUSH_NULL -> stack.add(Const(Null))
                Op.PUSH_TRUE -> stack.add(Const(true))
                Op.PUSH_FALSE -> stack.add(Const(false))
                Op.PUSH_INT -> stack.add(Const(a.toDouble()))
                Op.PUSH_CONST -> stack.add(Const(code.constants[a], a, code))
                Op.POP -> pop()
                Op.DUP -> stack.add(stack.lastOrNull() ?: throw JitBailout("operand stack underflow at $pc"))
                Op.DUP2 -> permute(2, intArrayOf(0, 1, 0, 1))
                Op.DUP3 -> permute(3, intArrayOf(0, 1, 2, 0, 1, 2))
                Op.SWAP -> permute(2, intArrayOf(1, 0))
                Op.ROT3 -> permute(3, intArrayOf(2, 0, 1))
                Op.ROT4 -> permute(4, intArrayOf(3, 0, 1, 2))
                Op.LOAD_REG -> stack.add(regs[a]!!)
                Op.STORE_REG -> {
                    regs[a] = pop()
                    home(a)
                }
                Op.LOAD_REG_TDZ -> stack.add(add(cur, Inst(Ir.CHECK_TDZ, arrayOf(regs[a]!!), bb, 0, 0, pc, stmt).also { it.hasResult = true }))
                Op.CHECK_REG_TDZ -> add(cur, Inst(Ir.CHECK_TDZ, arrayOf(regs[a]!!), bb, 0, 0, pc, stmt))
                // an inlined body's `this` was bound when it was entered
                Op.LOAD_THIS -> if (site != null) stack.add(site.thisValue) else inst(op, NO_VALUES, result = true)
                Op.TO_KEY_FOR_BASE -> {
                    // (obj key -- obj key')
                    val key = pop()
                    val obj = pop()
                    stack.add(obj)
                    inst(op, arrayOf(obj, key), result = true)
                }
                Op.SET_FUNCTION_NAME -> {
                    // (key fn -- key fn)
                    if (stack.size < 2) throw JitBailout("operand stack underflow at $pc")
                    inst(op, arrayOf(stack[stack.size - 2], stack[stack.size - 1]), result = false)
                }
                Op.JUMP -> {
                    end()
                    goto(cur, blockAt[a]!!)
                    return true
                }
                Op.ITER_STEP, Op.FOR_IN_NEXT -> {
                    // the next value (ITER_STEP: NotFound when done; FOR_IN_NEXT: null), pushed where it goes on
                    val v = add(cur, Inst(op, arrayOf(regs[a]!!), a, bb, 0, pc, stmt).also { it.hasResult = true })
                    val done = blockAt[bb]!!
                    edgeOut[b] = done to Array(nregs + stack.size) { if (it < nregs) regs[it]!! else stack[it - nregs] }
                    stack.add(v)
                    end()
                    add(cur, Inst(Ir.BRANCH, arrayOf(v), op, 0, 0, pc, stmt)).targets = listOf(done, blockAt[pc + len]!!)
                    return true
                }
                Op.JUMP_TABLE -> {
                    // reg count t0..t(count-1) default: operands the fixed decoding above does not read
                    val reg = bc[pc + 1]
                    val count = bc[pc + 2]
                    end()
                    add(cur, Inst(Ir.SWITCH, arrayOf(regs[reg]!!), 0, 0, 0, pc, stmt)).targets = List(count + 1) { blockAt[bc[pc + 3 + it]]!! }
                    return true
                }
                Op.ITER_STEP_U, Op.ITER_REST -> inst(op, arrayOf(regs[a]!!), result = true)
                Op.ITER_CLOSE, Op.ITER_CLOSE_THROW -> inst(op, arrayOf(regs[a]!!), result = false)
                Op.MAKE_METHOD -> inst(op, arrayOf(regs[bb]!!), result = true)
                Op.JUMP_IF_TRUE, Op.JUMP_IF_FALSE, Op.JUMP_IF_NULLISH, Op.JUMP_IF_NOT_NULLISH, Op.JUMP_IF_UNDEFINED,
                Op.JUMP_IF_NOT_UNDEFINED -> {
                    val cond = pop()
                    end()
                    if (a == pc + len) {
                        goto(cur, blockAt[a]!!)
                        return true
                    }
                    val i = add(cur, Inst(Ir.BRANCH, arrayOf(cond), op, 0, 0, pc, stmt))
                    i.targets = listOf(blockAt[a]!!, blockAt[pc + len]!!)
                    return true
                }
                Op.RETURN -> {
                    var v = pop()
                    val s = site
                    if (s != null && s.construct && v !== s.thisValue) {
                        // a construction gives `this` unless the body returns an object
                        v = if (v is Const && v.value !is JSObject) s.thisValue
                        else add(cur, Inst(Ir.CONSTRUCT_RESULT, arrayOf(v, s.thisValue), 0, 0, 0, pc, stmt).also { it.hasResult = true })
                    }
                    end()
                    val j = join
                    if (j == null) add(cur, Inst(Ir.RETURN, arrayOf(v), 0, 0, 0, pc, stmt))
                    else {
                        // an inlined body returns to the block after its call
                        goto(cur, j)
                        j.preds.add(cur)
                        returned.add(v)
                    }
                    return true
                }
                Op.THROW -> {
                    add(cur, Inst(Ir.THROW, arrayOf(pop()), 0, 0, 0, pc, stmt))
                    end()
                    return true
                }
                Op.THROW_ERROR -> {
                    add(cur, Inst(Ir.THROW_ERROR, NO_VALUES, a, bb, 0, pc, stmt))
                    end()
                    return true
                }
                Op.GET_PROP -> {
                    val ic = if (code.t2FailedAt(pc)) null else monomorphic(code.constants[a], PropCache.DATA)
                    if (ic == null && ints && speculate && !code.t2FailedAt(pc) && (code.constants[a] as? PropSite)?.key == "length" &&
                        (code.profile?.values?.get(pc)?.toInt() ?: 0) and 0xFF == ValueProfile.INT) {
                        // an array's or a string's length, read in place
                        val l = before(Inst(Ir.LENGTH, arrayOf(stack.last()), 0, 0, 0, pc, stmt))
                        pop()
                        add(cur, l).hasResult = true
                        stack.add(l)
                    } else if (ic == null) inst(op, arrayOf(pop()), result = true)
                    else {
                        // the receiver's shape (and its prototypes', for an inherited property) as the cache saw it,
                        // then the slot; anything else leaves before the instruction
                        val obj = stack.last()
                        val chain = ic.chain
                        val load = before(Inst(Ir.LOAD_SLOT, arrayOf(obj), g.dataIndex(ic.shape), ic.slot,
                            if (chain == null) -1 else g.dataIndex(chain), pc, stmt))
                        pop()
                        add(cur, load).hasResult = true
                        stack.add(load)
                    }
                }
                Op.LOAD_GLOBAL -> {
                    val ic = if (speculate && !code.t2FailedAt(pc)) (code.constants[a] as? GlobalSite)?.cache else null
                    if (ic == null) inst(op, NO_VALUES, result = true)
                    else {
                        // the binding, or the global object's slot, the cache saw; anything else leaves before the
                        // instruction
                        val load = before(
                            if (ic.shape == null) Inst(Ir.LOAD_GLOBAL_LEX, NO_VALUES, g.dataIndex(ic.token), ic.index, 0, pc, stmt)
                            else Inst(Ir.LOAD_GLOBAL_SLOT, NO_VALUES, g.dataIndex(ic.shape), ic.slot, ic.epoch, pc, stmt),
                        )
                        add(cur, load).hasResult = true
                        stack.add(load)
                    }
                }
                Op.PUT_PROP -> {
                    val ic = if (code.t2FailedAt(pc)) null else monomorphic(code.constants[a], PropCache.STORE)
                    val add = if (ic != null || code.t2FailedAt(pc)) null else monomorphic(code.constants[a], PropCache.ADD)
                    if (add != null) {
                        // (obj v -- v): a new own property, through the transition the cache saw
                        val chain = add.chain
                        val store = before(Inst(Ir.ADD_SLOT, arrayOf(stack[stack.size - 2], stack[stack.size - 1]),
                            g.dataIndex(add.shape), g.dataIndex(add.next), if (chain == null) -1 else g.dataIndex(chain), pc, stmt))
                        val v = pop()
                        pop()
                        add(cur, store)
                        stack.add(v)
                    } else if (ic == null) {
                        val v = pop()
                        inst(op, arrayOf(pop(), v), result = true)
                    } else {
                        // (obj v -- v): a writable own data property of the shape the cache saw
                        val store = before(Inst(Ir.STORE_SLOT, arrayOf(stack[stack.size - 2], stack[stack.size - 1]),
                            g.dataIndex(ic.shape), ic.slot, -1, pc, stmt))
                        val v = pop()
                        pop()
                        add(cur, store)
                        stack.add(v)
                    }
                }
                Op.INIT_THIS_REG -> {
                    // a derived constructor's `this`, from super(): its register and the frame's (JitRt.initThisReg)
                    regs[a] = inst(op, arrayOf(pop(), regs[a]!!), result = false).also { it.hasResult = true }
                    home(a)
                }
                Op.MAKE_CLASS -> {
                    // ([heritage] [name] -- proto ctor): the helper's pair, then each value of it
                    val n = (if (bb and 1 != 0) 1 else 0) + (if (bb and 2 != 0) 1 else 0)
                    if (stack.size < n) throw JitBailout("operand stack underflow at $pc")
                    val inputs = Array(n) { stack[stack.size - n + it] }
                    repeat(n) { stack.removeAt(stack.size - 1) }
                    val pair = add(cur, Inst(op, inputs, a, bb, c, pc, stmt).also { it.hasResult = true })
                    for (k in 0..1) stack.add(add(cur, Inst(Ir.PART, arrayOf<Value>(pair), k, 0, 0, pc, stmt).also { it.hasResult = true }))
                }
                Op.NEW -> {
                    val target = inlineTarget(pc, construct = true)
                    if (target == null || stack.size < a + 1) {
                        val n = a + 1
                        if (stack.size < n) throw JitBailout("operand stack underflow at $pc")
                        val inputs = Array(n) { stack[stack.size - n + it] }
                        repeat(n) { stack.removeAt(stack.size - 1) }
                        inst(op, inputs, result = true)
                    } else {
                        cur = inline(cur, target, pc, a, stmt, regs, stack, construct = true)
                    }
                }
                Op.CALL -> {
                    val target = inlineTarget(pc)
                    val intrinsic = if (target == null) intrinsicAt(pc, a) else 0
                    if (intrinsic != 0) {
                        // a Math builtin the feedback saw called there: computed in place behind a guard that
                        // leaves before the call for anything else
                        val n = a + 2
                        if (stack.size < n) throw JitBailout("operand stack underflow at $pc")
                        val fn = stack[stack.size - n]
                        val thisV = stack[stack.size - n + 1]
                        val args = Array(a) { stack[stack.size - a + it] }
                        add(cur, Inst(Ir.CHECK_INTRINSIC, arrayOf(fn), intrinsic, 0, 0, pc, stmt)).state = state(pc)
                        repeat(n) { stack.removeAt(stack.size - 1) }
                        val r = if (intrinsic == dev.mooner.neonjs.runtime.MathIntrinsic.CHAR_CODE_AT) {
                            Inst(Ir.CHAR_CODE, arrayOf(thisV, args[0]), intrinsic, 0, 0, pc, stmt)
                        } else Inst(Ir.MATH, args, intrinsic, 0, 0, pc, stmt)
                        stack.add(add(cur, r.also { it.hasResult = true }))
                    } else if (target == null || stack.size < a + 2) {
                        val n = a + 2
                        if (stack.size < n) throw JitBailout("operand stack underflow at $pc")
                        val inputs = Array(n) { stack[stack.size - n + it] }
                        repeat(n) { stack.removeAt(stack.size - 1) }
                        inst(op, inputs, result = true)
                    } else {
                        cur = inline(cur, target, pc, a, stmt, regs, stack)
                    }
                }
                else -> {
                    val n = arity(bc, op, pc)
                    if (stack.size < n) throw JitBailout("operand stack underflow at $pc")
                    // arithmetic that may stay on ints (Kinds): an exit before it, its operands on the stack, where the
                    // result is not an int32 (not where that failed before)
                    val before = if (speculate && ints && Ir.intArith(op) && !code.t2FailedAt(pc)) state(pc) else null
                    val inputs = Array(n) { stack[stack.size - n + it] }
                    repeat(n) { stack.removeAt(stack.size - 1) }
                    inst(op, inputs, result = pushes(op)).state = before
                }
            }
            // a load whose values were only numbers: a number from here on, or an exit after the load
            if (speculate && (op == Op.GET_PROP || op == Op.GET_ELEM || op == Op.LOAD_ENV || op == Op.LOAD_ENV_TDZ ||
                    op == Op.LOAD_GLOBAL || op == Op.CALL) && !code.t2FailedAt(pc)) {
                val top = stack.lastOrNull()
                val bits = code.profile?.values?.get(pc)?.toInt()?.and(0xFF) ?: 0
                if (top is Inst && top.pc == pc && top.block?.site == site) numberCheck(bits)?.let { check ->
                    val i = Inst(check, arrayOf(top), 0, 0, 0, pc, stmt)
                    i.state = state(pc + len)
                    i.hasResult = true
                    add(cur, i)
                    stack[stack.size - 1] = i
                }
            }
            false
        }

        // -------------------------------------------------------------- inlined calls

        /** The code block to compile the CALL at [pc] (or, [construct], the NEW) inlined with, or null. */
        private fun inlineTarget(pc: Int, construct: Boolean = false): CodeBlock? {
            val depth = site?.depth ?: 0
            // (where a call made there was another function before, CodeBlock.t2Failed, the call is made as a call when
            // it is another again: inline)
            if (depth >= maxInlineDepth) return null
            val fb = code.callFeedback
            val target = (if (fb == null) (if (construct) null else code.callHint(pc))
            else fb.getOrNull(pc)?.takeIf { it.count >= Inlining.MIN_COUNT }?.target) ?: return null
            // (helpers read the strictness of the innermost call the frame records, JitRt.strict: a body that runs one is
            // not quiet, QuietInlines, and records its call)
            if (if (construct) !Inlining.constructible(target, MAX_CALLEE_CODE) else !Inlining.eligible(target, MAX_CALLEE_CODE)) return null
            // recursion is inlined once, at the function compiled (as the baseline does): further copies only grow it
            if (depth > 0) {
                if (target === cb) return null
                var s = site
                while (s != null) {
                    if (s.code === target) return null
                    s = s.parent
                }
            }
            if (inlined + target.code.size > INLINE_BUDGET) return null
            return target
        }

        /** The Math builtin (MathIntrinsic) the CALL at [pc] with [argc] arguments always called, or 0. */
        private fun intrinsicAt(pc: Int, argc: Int): Int {
            if (!speculate || code.t2FailedAt(pc)) return 0
            val id = code.callFeedback?.getOrNull(pc)?.intrinsic ?: return 0
            return if (id > 0 && dev.mooner.neonjs.runtime.MathIntrinsic.arity(id) == argc) id else 0
        }

        /**
         * The CALL at [pc] of [argc] arguments (or, [construct], the NEW) with [target]'s body built in place, from block
         * [cur], with the caller's [regs] and [stack] (the call's operands on top, its result there after). Returns the
         * block where the body's returns meet, where the caller goes on.
         */
        private fun inline(cur: Block, target: CodeBlock, pc: Int, argc: Int, stmt: Int, regs: Array<Value?>, stack: ArrayList<Value>,
                           construct: Boolean = false): Block {
            // (fn, this, args) or (fn, args)
            val n = if (construct) argc + 1 else argc + 2
            if (stack.size < n) throw JitBailout("operand stack underflow at $pc")
            inlined += target.code.size
            T2.inlinedCalls.incrementAndGet()
            val ops = ArrayList(stack.subList(stack.size - n, stack.size))
            val fn = ops[0]
            // where the guard failed before (CodeBlock.t2Failed: a callback site that gets several functions), another
            // function is called as a call, on a branch of its own; elsewhere the guard leaves before the call, which the
            // interpreter then makes. A parameter never assigned is the same all through a loop: its site is built so
            // tentatively, the guard's state kept (Unswitch copies the loop on the test, or makes it a guard)
            val polymorphic = code.t2FailedAt(pc) || !exits
            val tentative = !polymorphic && unswitch && speculate && site == null && unassignedParams.any { regs[it] === fn }
            var start = cur
            var other: Block? = null
            if (polymorphic || tentative) {
                val test = add(cur, Inst(Ir.IS_CLOSURE, arrayOf(fn), g.dataIndex(target), if (tentative) 1 else 0, 0, pc, stmt).also { it.hasResult = true })
                if (tentative) test.state = FrameState(pc, Array(nregs + stack.size) { if (it < nregs) regAt(pc, it, regs[it]!!) else stack[it - nregs] }, site)
                start = newBlock(pc, site).also { it.handler = cur.handler }
                other = newBlock(pc, site).also { it.handler = cur.handler }
                add(cur, Inst(Ir.BRANCH, arrayOf(test), Op.JUMP_IF_FALSE, 0, 0, pc, stmt)).targets = listOf(other, start)
                start.preds.add(cur)
                other.preds.add(cur)
            } else {
                val guard = add(cur, Inst(Ir.CHECK_CLOSURE, arrayOf(fn), g.dataIndex(target), 0, 0, pc, stmt))
                guard.state = FrameState(pc, Array(nregs + stack.size) { if (it < nregs) regAt(pc, it, regs[it]!!) else stack[it - nregs] }, site)
            }
            repeat(n) { stack.removeAt(stack.size - 1) }
            // (where the caller goes on after the call: what is live at the call is)
            val callerState = FrameState(pc, Array(nregs + stack.size) { if (it < nregs) regAt(pc, it, regs[it]!!) else stack[it - nregs] }, site)
            // a construction's `this`, made before the body is entered (as Interpreter.constructClosure makes it)
            val made = if (!construct) null else {
                val cls = if (target.flags and CodeBlock.CLASS_CTOR != 0) 1 else 0
                add(start, Inst(Ir.NEW_THIS, arrayOf(fn), cls, 0, 0, pc, stmt).also { it.hasResult = true })
            }
            val enter = add(start, Inst(Ir.INLINE_ENTER, arrayOf(fn), pc, 0, 0, pc, stmt))
            val env = add(start, Inst(Ir.CLOSURE_ENV, arrayOf(fn), 0, 0, 0, pc, stmt).also { it.hasResult = true })
            val thisValue = if (made != null) made else if (readsThis(target)) {
                val bound = if (target.flags and (CodeBlock.STRICT or CodeBlock.ARROW) != 0) 1 else 0
                add(start, Inst(Ir.INLINE_THIS, arrayOf(fn, ops[1]), bound, 0, 0, pc, stmt).also { it.hasResult = true })
            } else dead()
            val args = Array(argc) { ops[n - argc + it] }
            val calleeSite = InlineSite(site, target, pc, callerState, jsFrame(fn, thisValue, args, env), if (construct) SITE_CONSTRUCT else 0)
            enter.callee = calleeSite
            g.dataIndex(target.constants)
            val body = JsBody(target, calleeSite, cur.handler)
            // where the body's returns meet (and the call ends), then, with a call made as a call, where both meet
            val end = newBlock(pc, site)
            end.handler = cur.handler
            body.join = end
            val calleeRegs = Array<Value>(target.numRegs) { dead() }
            target.paramRegs?.forEachIndexed { i, r -> if (r >= 0 && i < argc) calleeRegs[r] = args[i] }
            body.build(start, calleeRegs)
            val result: Value = when (body.returned.size) {
                0 -> dead() // it never returns: the block is not reached
                1 -> body.returned[0]
                else -> Phi(end).also { p -> end.phis.add(p); p.inputs.addAll(body.returned) }
            }
            add(end, Inst(Ir.INLINE_EXIT, NO_VALUES, 0, 0, 0, pc, stmt)).callee = calleeSite
            if (other == null) {
                stack.add(result)
                return end
            }
            val called = add(other, Inst(if (construct) Op.NEW else Op.CALL, ops.toTypedArray(), argc, 0, 0, pc, stmt).also { it.hasResult = true })
            val join = newBlock(pc, site)
            join.handler = cur.handler
            if (body.returned.isNotEmpty()) {
                goto(end, join)
                join.preds.add(end)
            }
            goto(other, join)
            join.preds.add(other)
            stack.add(if (join.preds.size == 1) called else Phi(join).also { p -> join.phis.add(p); p.inputs.add(result); p.inputs.add(called) })
            return join
        }
    }


    /**
     * The check that makes a value of profile kinds [bits] (ValueProfile) a number: CHECK_INT32 when it was always an
     * int32, CHECK_NUMBER when always a number, null when something else was seen or nothing was.
     */
    private fun numberCheck(bits: Int): Int? = when {
        bits == 0 || bits and (ValueProfile.INT or ValueProfile.DOUBLE).inv() != 0 -> null
        bits == ValueProfile.INT && ints -> Ir.CHECK_INT32
        else -> Ir.CHECK_NUMBER
    }

    /**
     * The entry of property site [site] (a PropSite) when it is monomorphic with the one [kind] the optimizing tier
     * compiles: an own or inherited data property to read (DATA), a writable own data property to write (STORE), a new
     * own property to add (ADD).
     */
    private fun monomorphic(site: Any?, kind: Int): PropIC? {
        if (!speculate || site !is PropSite) return null
        val e = site.entry ?: return null
        // (a string primitive's entry: Realm.stringShape, for receivers that are no objects)
        if (e.link != null || e.kind != kind || !e.shape.cacheable || e.shape.ownerClass === CharSequence::class.java) return null
        if (kind == PropCache.STORE && e.chain != null) return null
        return e
    }

    /** The registers of parameters the function never assigns (no STORE_REG there): the same value all through it. */
    private val unassignedParams: IntArray by lazy {
        val stored = HashSet<Int>()
        val code = cb.code
        var pc = 0
        while (pc < code.size) {
            if (code[pc] == Op.STORE_REG) stored.add(code[pc + 1])
            pc += Op.length(code, pc)
        }
        (cb.paramRegs ?: IntArray(0)).filter { it >= 0 && it !in stored }.toIntArray()
    }

    private fun readsThis(target: CodeBlock): Boolean {
        val code = target.code
        var pc = 0
        while (pc < code.size) {
            if (code[pc] == Op.LOAD_THIS) return true
            pc += Op.length(code, pc)
        }
        return false
    }
    companion object {
        /** Instructions with no [arity] that the builder builds itself (iteration, the finally dispatch, methods). */
        private val BUILT_OPS = setOf(Op.ITER_STEP, Op.FOR_IN_NEXT, Op.JUMP_TABLE, Op.ITER_STEP_U, Op.ITER_REST,
            Op.ITER_CLOSE, Op.ITER_CLOSE_THROW, Op.MAKE_METHOD, Op.MAKE_CLASS, Op.INIT_THIS_REG)

        /** Calls compiled inlined into one another at most; bytecode ints of one callee and of all of them, at most. */
        const val MAX_INLINE_DEPTH = 3
        const val MAX_CALLEE_CODE = 400
        const val INLINE_BUDGET = 2000

        fun isJumpOp(op: Int) = op == Op.JUMP || op == Op.JUMP_IF_TRUE || op == Op.JUMP_IF_FALSE || op == Op.JUMP_IF_NULLISH ||
            op == Op.JUMP_IF_NOT_NULLISH || op == Op.JUMP_IF_UNDEFINED || op == Op.JUMP_IF_NOT_UNDEFINED

        /** Whether bytecode instruction [op] (one [arity] knows) leaves a result on the stack. */
        fun pushes(op: Int): Boolean = when (op) {
            Op.STORE_ENV, Op.CHECK_ENV_TDZ, Op.STORE_NAME, Op.STORE_NAME_VAR, Op.INIT_NAME, Op.STORE_GLOBAL,
            Op.INIT_GLOBAL_LEX, Op.INIT_THIS_ENV, Op.INIT_THIS_NAME, Op.PUSH_SCOPE, Op.POP_SCOPE, Op.COPY_SCOPE, Op.SET_ENV,
            Op.PUSH_WITH, Op.ADD_FIELD, Op.ADD_PRIVATE_METHOD, Op.STATIC_PRIVATE_METHOD, Op.RUN_FIELD, Op.DEBUGGER,
            Op.DECLARE_GLOBALS, Op.DECLARE_EVAL, Op.ADD_DISPOSABLE, Op.DISPOSE_SYNC, Op.CLASS_ELEMENT, Op.CLASS_FINISH,
            Op.CLASS_STATIC_INIT -> false
            else -> true
        }

        /**
         * The number of stack values bytecode instruction [op] at [pc] of [code] takes as inputs, for those the IR has as
         * instructions of their own (a helper call, JvmEmitter.emitHelper, or a specialized form); -1 for the others.
         * Each pushes at most one value ([pushes]).
         */
        fun arity(code: IntArray, op: Int, pc: Int): Int {
            val a = if (Op.operands[op] >= 1) code[pc + 1] else 0
            val b = if (Op.operands[op] >= 2) code[pc + 2] else 0
            return when (op) {
                Op.NOP, Op.PUSH_UNDEF, Op.PUSH_NULL, Op.PUSH_TRUE, Op.PUSH_FALSE, Op.PUSH_INT, Op.PUSH_CONST, Op.POP, Op.DUP,
                Op.DUP2, Op.DUP3, Op.SWAP, Op.ROT3, Op.ROT4, Op.LOAD_REG, Op.STORE_REG, Op.LOAD_REG_TDZ, Op.CHECK_REG_TDZ,
                Op.TO_KEY_FOR_BASE, Op.SET_FUNCTION_NAME -> 0 // built by IrBuilder itself
                Op.LOAD_ENV, Op.LOAD_ENV_TDZ, Op.CHECK_ENV_TDZ, Op.LOAD_IMPORT, Op.LOAD_NAME, Op.LOAD_NAME_TYPEOF,
                Op.DELETE_NAME, Op.RESOLVE_NAME, Op.LOAD_GLOBAL, Op.LOAD_GLOBAL_TYPEOF, Op.LOAD_THIS, Op.LOAD_FUNCTION,
                Op.LOAD_NEW_TARGET, Op.LOAD_HOME, Op.CREATE_ARGUMENTS, Op.CREATE_REST, Op.LOAD_ARG, Op.NEW_OBJECT,
                Op.NEW_ARRAY, Op.MAKE_CLOSURE, Op.NEW_PRIVATE_NAME, Op.TEMPLATE_OBJECT, Op.NEW_REGEXP, Op.IMPORT_META,
                Op.DISPOSE_NEW, Op.PUSH_SCOPE, Op.POP_SCOPE, Op.COPY_SCOPE, Op.GET_ENV, Op.DEBUGGER -> 0
                Op.STORE_ENV, Op.STORE_NAME, Op.STORE_NAME_VAR, Op.INIT_NAME, Op.STORE_GLOBAL, Op.INIT_GLOBAL_LEX,
                Op.INIT_THIS_ENV, Op.INIT_THIS_NAME, Op.SET_ENV, Op.PUSH_WITH, Op.GET_REF, Op.GET_PROP, Op.DELETE_PROP,
                Op.TO_PROPERTY_KEY, Op.GET_SUPER_BASE, Op.NEG, Op.TO_NUMBER, Op.NOT, Op.BNOT, Op.TYPEOF, Op.TO_NUMERIC,
                Op.INC, Op.DEC, Op.TO_STRING, Op.TO_OBJECT, Op.REQUIRE_COERCIBLE, Op.CHECK_OBJECT, Op.GET_PROTO_OF,
                Op.ARRAY_HOLE, Op.GET_ITERATOR, Op.FOR_IN_START, Op.CLASS_FINISH, Op.CLASS_STATIC_INIT -> 1
                Op.PUT_REF, Op.PUT_PROP, Op.GET_ELEM, Op.DELETE_ELEM, Op.GET_PRIVATE, Op.HAS_PRIVATE, Op.ADD, Op.SUB, Op.MUL,
                Op.DIV, Op.MOD, Op.EXP, Op.SHL, Op.SAR, Op.SHR, Op.BAND, Op.BOR, Op.BXOR, Op.EQ, Op.NE, Op.SEQ, Op.SNE,
                Op.LT, Op.GT, Op.LE, Op.GE, Op.INSTANCEOF, Op.IN, Op.CONCAT, Op.NEW_SPREAD, Op.INIT_INSTANCE, Op.ARRAY_PUSH,
                Op.ARRAY_SPREAD, Op.DEFINE_FIELD, Op.COPY_DATA_PROPS, Op.SET_PROTO, Op.DYNAMIC_IMPORT,
                Op.DYNAMIC_IMPORT_PHASE, Op.ADD_DISPOSABLE, Op.CLASS_DEF_NEW, Op.CLASS_DECORATE, Op.DERIVED_RETURN -> 2
                Op.GET_SUPER, Op.PUT_ELEM, Op.PUT_PRIVATE, Op.CALL_SPREAD, Op.SUPER_CALL_SPREAD, Op.DEFINE_FIELD_ELEM,
                Op.DEFINE_GETTER, Op.DEFINE_SETTER, Op.DEFINE_METHOD, Op.ADD_FIELD, Op.ADD_PRIVATE_METHOD,
                Op.STATIC_PRIVATE_METHOD, Op.RUN_FIELD, Op.DISPOSE_SYNC, Op.CALL_EVAL_SPREAD -> 3
                Op.PUT_SUPER, Op.CLASS_ELEMENT -> 4
                Op.CALL, Op.TAIL_CALL, Op.CALL_EVAL, Op.SUPER_CALL -> a + 2
                Op.NEW -> a + 1
                Op.COPY_DATA_PROPS_EXCL -> a + 2
                Op.NEW_OBJECT_LITERAL -> b
                Op.DECLARE_GLOBALS, Op.DECLARE_EVAL -> b
                else -> -1
            }
        }
    }
}
