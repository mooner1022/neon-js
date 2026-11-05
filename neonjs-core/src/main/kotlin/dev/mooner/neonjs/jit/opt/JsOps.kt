package dev.mooner.neonjs.jit.opt

import dev.mooner.neonjs.compiler.Op
import dev.mooner.neonjs.runtime.ObjectLiteralSite
import dev.mooner.neonjs.runtime.PropSite
import dev.mooner.neonjs.jit.opt.OpInfo.Companion.ALLOCATES
import dev.mooner.neonjs.jit.opt.OpInfo.Companion.ANYTHING
import dev.mooner.neonjs.jit.opt.OpInfo.Companion.HOIST_ALWAYS
import dev.mooner.neonjs.jit.opt.OpInfo.Companion.HOIST_ENV_LOAD
import dev.mooner.neonjs.jit.opt.OpInfo.Companion.HOIST_LOAD
import dev.mooner.neonjs.jit.opt.OpInfo.Companion.HOIST_NEVER
import dev.mooner.neonjs.jit.opt.OpInfo.Companion.HOIST_NUMERIC
import dev.mooner.neonjs.jit.opt.OpInfo.Companion.HOIST_NUMERIC_NO_EXIT
import dev.mooner.neonjs.jit.opt.OpInfo.Companion.HOIST_SLOT_LOAD
import dev.mooner.neonjs.jit.opt.OpInfo.Companion.LICM_BARRIER
import dev.mooner.neonjs.jit.opt.OpInfo.Companion.NUMERIC_PURE
import dev.mooner.neonjs.jit.opt.OpInfo.Companion.OBJECT_OPERANDS
import dev.mooner.neonjs.jit.opt.OpInfo.Companion.REMOVABLE
import dev.mooner.neonjs.jit.opt.OpInfo.Companion.RUNS_CODE
import dev.mooner.neonjs.jit.opt.OpInfo.Companion.THROWS
import dev.mooner.neonjs.jit.opt.OpInfo.Companion.UNBOXES
import dev.mooner.neonjs.jit.opt.OpInfo.Companion.WRITES_ENV
import dev.mooner.neonjs.jit.opt.OpInfo.Companion.WRITES_HEAP
import dev.mooner.neonjs.jit.opt.OpInfo.Companion.WRITES_SLOT

/**
 * The JavaScript bytecode's operations ([Op]) and those the optimizing tier's builder makes for it ([Ir]), as the
 * passes see them. An operation not listed may do anything ([OpInfo.ANYTHING]).
 */
internal object JsOps : OpTable {
    private val ops = arrayOfNulls<OpInfo>(Op.COUNT)
    private val irOps = arrayOfNulls<OpInfo>(Ir.SWITCH - Ir.PARAM + 1)

    override fun info(op: Int): OpInfo {
        val table = if (op >= Ir.PARAM) irOps else ops
        val k = if (op >= Ir.PARAM) op - Ir.PARAM else op
        return table[k] ?: OpInfo(Ir.name(op), ANYTHING).also { table[k] = it }
    }

    private fun def(op: Int, flags: Int, hoist: Int = HOIST_NEVER) = def(op, OpInfo(Ir.name(op), flags, hoist))

    private fun def(op: Int, info: OpInfo) {
        if (op >= Ir.PARAM) irOps[op - Ir.PARAM] = info else ops[op] = info
    }

    /** A slot of an object of the shape the graph's data holds at [Inst.a] ([Ir.LOAD_SLOT]: the holder's, past a chain at [Inst.c]). */
    private open class SlotOp(op: Int, flags: Int, hoist: Int) : OpInfo(Ir.name(op), flags, hoist) {
        override fun location(i: Inst, g: Graph): Pair<Any?, Int> =
            (if (i.op == Ir.LOAD_SLOT && i.c >= 0) (g.data[i.c] as Array<*>).last() else g.data[i.a]) to i.b
    }

    init {
        // arithmetic, comparisons and conversions: numbers only compute (no valueOf, no BigInt mixing); arithmetic
        // without an exit moves out of loops, comparisons and steps do not (they stay with their branch)
        val moved = intArrayOf(Op.ADD, Op.SUB, Op.MUL, Op.DIV, Op.MOD, Op.EXP, Op.BAND, Op.BOR, Op.BXOR, Op.SHL, Op.SAR,
            Op.SHR, Op.NEG, Op.BNOT, Op.TO_NUMERIC, Op.TO_NUMBER)
        for (op in moved) def(op, ANYTHING or NUMERIC_PURE, HOIST_NUMERIC_NO_EXIT)
        for (op in intArrayOf(Op.LT, Op.GT, Op.LE, Op.GE, Op.EQ, Op.NE, Op.INC, Op.DEC)) def(op, ANYTHING or NUMERIC_PURE)
        def(Ir.MATH, ANYTHING or NUMERIC_PURE, HOIST_NUMERIC)

        for (op in intArrayOf(Op.SEQ, Op.SNE, Op.NOT)) def(op, REMOVABLE)
        def(Op.LOAD_ENV, REMOVABLE, HOIST_ENV_LOAD)
        // a TDZ check throws
        def(Op.LOAD_ENV_TDZ, THROWS)
        def(Ir.CHECK_TDZ, THROWS or LICM_BARRIER)
        def(Op.STORE_ENV, WRITES_ENV or OBJECT_OPERANDS)

        // objects made: an object literal's map is made with its layout; the rest are not looked at by LICM yet
        // (its keys are static and distinct, its properties writable data ones: a read of one is its value until written)
        def(Op.NEW_OBJECT_LITERAL, object : OpInfo(Ir.name(Op.NEW_OBJECT_LITERAL), ALLOCATES or THROWS or OBJECT_OPERANDS) {
            override fun fields(i: Inst, g: Graph): Array<Any>? =
                (ScalarReplacement.codeOf(g, i).constants[i.a] as? ObjectLiteralSite)?.keys
        })
        def(Op.NEW_OBJECT, ALLOCATES or THROWS or LICM_BARRIER)
        def(Op.NEW_ARRAY, ALLOCATES or THROWS or LICM_BARRIER)
        def(Op.ARRAY_PUSH, ALLOCATES or THROWS or LICM_BARRIER or OBJECT_OPERANDS)
        def(Op.ARRAY_HOLE, ALLOCATES or THROWS or LICM_BARRIER)
        // `this` of a construction compiled inlined: a class constructor's fields are defined by code
        def(Ir.NEW_THIS, object : OpInfo(Ir.name(Ir.NEW_THIS), ALLOCATES or OBJECT_OPERANDS) {
            override fun flagsOf(i: Inst) = if (i.a == 0) flags else flags or ANYTHING
        })
        def(Ir.CONSTRUCT_RESULT, REMOVABLE or OBJECT_OPERANDS)

        // the builder's own: parameters, exception handlers' registers, guards, inlined calls
        def(Ir.PARAM, LICM_BARRIER)
        def(Ir.CATCH_VALUE, LICM_BARRIER)
        def(Ir.LOAD_HOME, LICM_BARRIER)
        def(Ir.SET_HOME, 0)
        def(Ir.STRESS_EXIT, 0)
        for (op in intArrayOf(Ir.CHECK_NUMBER, Ir.CHECK_INT32)) def(op, REMOVABLE or UNBOXES, HOIST_ALWAYS)
        def(Ir.CLOSURE_ENV, REMOVABLE, HOIST_ALWAYS)
        for (op in intArrayOf(Ir.CHECK_CLOSURE, Ir.IS_CLOSURE, Ir.CHECK_INTRINSIC)) def(op, 0, HOIST_ALWAYS)
        // (`this` bound without allocating, a == 1, moves with its call's guard)
        def(Ir.INLINE_THIS, object : OpInfo(Ir.name(Ir.INLINE_THIS), 0) {
            override fun hoistOf(i: Inst) = if (i.a == 1) HOIST_ALWAYS else HOIST_NEVER
        })
        def(Ir.INLINE_ENTER, 0)
        def(Ir.INLINE_EXIT, 0)

        // loads and stores through what the caches saw
        def(Ir.LOAD_SLOT, object : SlotOp(Ir.LOAD_SLOT, REMOVABLE, HOIST_SLOT_LOAD) {
            // an own property's, made from the GET_PROP at its pc
            override fun readsField(i: Inst, g: Graph): Any? {
                if (i.c >= 0) return null
                val code = ScalarReplacement.codeOf(g, i)
                return (code.constants[code.code[i.pc + 1]] as? PropSite)?.key
            }
        })
        def(Ir.STORE_SLOT, SlotOp(Ir.STORE_SLOT, WRITES_SLOT or OBJECT_OPERANDS, HOIST_NEVER))
        // (the object's shape moves on)
        def(Ir.ADD_SLOT, WRITES_HEAP or OBJECT_OPERANDS)
        def(Ir.LOAD_GLOBAL_LEX, REMOVABLE, HOIST_LOAD)
        def(Ir.LOAD_GLOBAL_SLOT, SlotOp(Ir.LOAD_GLOBAL_SLOT, REMOVABLE, HOIST_SLOT_LOAD))
        def(Ir.LENGTH, REMOVABLE, HOIST_LOAD)

        // terminators
        for (op in intArrayOf(Ir.GOTO, Ir.BRANCH, Ir.SWITCH)) def(op, 0)
        def(Ir.RETURN, OBJECT_OPERANDS)
        def(Ir.THROW, THROWS or LICM_BARRIER or OBJECT_OPERANDS)
        def(Ir.THROW_ERROR, THROWS or LICM_BARRIER)

        // operations that may do anything and take Objects
        for (op in intArrayOf(Op.CALL, Op.NEW, Op.STORE_GLOBAL, Op.PUT_PROP)) def(op, ANYTHING or OBJECT_OPERANDS)
        def(Op.GET_PROP, object : OpInfo(Ir.name(Op.GET_PROP), ANYTHING) {
            override fun readsField(i: Inst, g: Graph): Any? = (ScalarReplacement.codeOf(g, i).constants[i.a] as? PropSite)?.key
        })
    }
}
