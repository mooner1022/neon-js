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
    /** The bytecode's, the core's (from 1000) and JsIr's (from 2000). */
    private val ops = arrayOfNulls<OpInfo>(Op.COUNT)
    private val coreOps = arrayOfNulls<OpInfo>(1000)
    private val jsOps = arrayOfNulls<OpInfo>(1000)

    override fun info(op: Int): OpInfo {
        val (table, k) = when {
            op >= 2000 -> jsOps to op - 2000
            op >= 1000 -> coreOps to op - 1000
            else -> ops to op
        }
        return table[k] ?: OpInfo(JsIr.name(op), ANYTHING).also { table[k] = it }
    }

    override fun operandText(i: Inst): String = when {
        i.op < Op.COUNT && Op.operands[i.op] > 0 -> " ${i.a}" + (if (Op.operands[i.op] > 1) " ${i.b}" else "")
        i.op == JsIr.PARAM || i.op == JsIr.CHECK_TDZ -> " ${i.a}"
        else -> ""
    }

    private fun def(op: Int, flags: Int, hoist: Int = HOIST_NEVER) = def(op, OpInfo(JsIr.name(op), flags, hoist))

    private fun def(op: Int, info: OpInfo) {
        when {
            op >= 2000 -> jsOps[op - 2000] = info
            op >= 1000 -> coreOps[op - 1000] = info
            else -> ops[op] = info
        }
    }

    /** A slot of an object of the shape the graph's data holds at [Inst.a] ([JsIr.LOAD_SLOT]: the holder's, past a chain at [Inst.c]). */
    private open class SlotOp(op: Int, flags: Int, hoist: Int) : OpInfo(JsIr.name(op), flags, hoist) {
        override fun location(i: Inst, g: Graph): Pair<Any?, Int> =
            (if (i.op == JsIr.LOAD_SLOT && i.c >= 0) (g.data[i.c] as Array<*>).last() else g.data[i.a]) to i.b
    }

    init {
        // arithmetic, comparisons and conversions: numbers only compute (no valueOf, no BigInt mixing); arithmetic
        // without an exit moves out of loops, comparisons and steps do not (they stay with their branch)
        val moved = intArrayOf(Op.ADD, Op.SUB, Op.MUL, Op.DIV, Op.MOD, Op.EXP, Op.BAND, Op.BOR, Op.BXOR, Op.SHL, Op.SAR,
            Op.SHR, Op.NEG, Op.BNOT, Op.TO_NUMERIC, Op.TO_NUMBER)
        for (op in moved) def(op, ANYTHING or NUMERIC_PURE, HOIST_NUMERIC_NO_EXIT)
        for (op in intArrayOf(Op.LT, Op.GT, Op.LE, Op.GE, Op.EQ, Op.NE, Op.INC, Op.DEC)) def(op, ANYTHING or NUMERIC_PURE)
        def(JsIr.MATH, ANYTHING or NUMERIC_PURE, HOIST_NUMERIC)

        for (op in intArrayOf(Op.SEQ, Op.SNE, Op.NOT)) def(op, REMOVABLE)
        def(Op.LOAD_ENV, REMOVABLE, HOIST_ENV_LOAD)
        // a TDZ check throws
        def(Op.LOAD_ENV_TDZ, THROWS)
        def(JsIr.CHECK_TDZ, THROWS or LICM_BARRIER)
        def(Op.STORE_ENV, WRITES_ENV or OBJECT_OPERANDS)

        // objects made: an object literal's map is made with its layout; the rest are not looked at by LICM yet
        // (its keys are static and distinct, its properties writable data ones: a read of one is its value until written)
        def(Op.NEW_OBJECT_LITERAL, object : OpInfo(JsIr.name(Op.NEW_OBJECT_LITERAL), ALLOCATES or THROWS or OBJECT_OPERANDS) {
            override fun fields(i: Inst, g: Graph): Array<Any>? =
                (ScalarReplacement.codeOf(g, i).constants[i.a] as? ObjectLiteralSite)?.keys
        })
        def(Op.NEW_OBJECT, ALLOCATES or THROWS or LICM_BARRIER)
        def(Op.NEW_ARRAY, ALLOCATES or THROWS or LICM_BARRIER)
        def(Op.ARRAY_PUSH, ALLOCATES or THROWS or LICM_BARRIER or OBJECT_OPERANDS)
        def(Op.ARRAY_HOLE, ALLOCATES or THROWS or LICM_BARRIER)
        // `this` of a construction compiled inlined: a class constructor's fields are defined by code
        def(JsIr.NEW_THIS, object : OpInfo(JsIr.name(JsIr.NEW_THIS), ALLOCATES or OBJECT_OPERANDS) {
            override fun flagsOf(i: Inst) = if (i.a == 0) flags else flags or ANYTHING
        })
        def(JsIr.CONSTRUCT_RESULT, REMOVABLE or OBJECT_OPERANDS)

        // the builder's own: parameters, exception handlers' registers, guards, inlined calls
        def(JsIr.PARAM, LICM_BARRIER)
        def(Ir.CATCH_VALUE, LICM_BARRIER)
        def(Ir.LOAD_HOME, LICM_BARRIER)
        def(Ir.SET_HOME, 0)
        def(Ir.STRESS_EXIT, 0)
        for (op in intArrayOf(JsIr.CHECK_NUMBER, JsIr.CHECK_INT32)) def(op, REMOVABLE or UNBOXES, HOIST_ALWAYS)
        def(JsIr.CLOSURE_ENV, REMOVABLE, HOIST_ALWAYS)
        for (op in intArrayOf(Ir.CHECK_CLOSURE, Ir.IS_CLOSURE, JsIr.CHECK_INTRINSIC)) def(op, 0, HOIST_ALWAYS)
        // (`this` bound without allocating, a == 1, moves with its call's guard)
        def(JsIr.INLINE_THIS, object : OpInfo(JsIr.name(JsIr.INLINE_THIS), 0) {
            override fun hoistOf(i: Inst) = if (i.a == 1) HOIST_ALWAYS else HOIST_NEVER
        })
        def(Ir.INLINE_ENTER, 0)
        def(Ir.INLINE_EXIT, 0)

        // loads and stores through what the caches saw
        def(JsIr.LOAD_SLOT, object : SlotOp(JsIr.LOAD_SLOT, REMOVABLE, HOIST_SLOT_LOAD) {
            // an own property's, made from the GET_PROP at its pc
            override fun readsField(i: Inst, g: Graph): Any? {
                if (i.c >= 0) return null
                val code = ScalarReplacement.codeOf(g, i)
                return (code.constants[code.code[i.pc + 1]] as? PropSite)?.key
            }
        })
        def(JsIr.STORE_SLOT, SlotOp(JsIr.STORE_SLOT, WRITES_SLOT or OBJECT_OPERANDS, HOIST_NEVER))
        // (the object's shape moves on)
        def(JsIr.ADD_SLOT, WRITES_HEAP or OBJECT_OPERANDS)
        def(JsIr.LOAD_GLOBAL_LEX, REMOVABLE, HOIST_LOAD)
        def(JsIr.LOAD_GLOBAL_SLOT, SlotOp(JsIr.LOAD_GLOBAL_SLOT, REMOVABLE, HOIST_SLOT_LOAD))
        def(JsIr.LENGTH, REMOVABLE, HOIST_LOAD)

        // terminators
        for (op in intArrayOf(Ir.GOTO, Ir.BRANCH, Ir.SWITCH)) def(op, 0)
        def(Ir.RETURN, OBJECT_OPERANDS)
        def(Ir.THROW, THROWS or LICM_BARRIER or OBJECT_OPERANDS)
        def(Ir.THROW_ERROR, THROWS or LICM_BARRIER)

        // operations that may do anything and take Objects
        for (op in intArrayOf(Op.CALL, Op.NEW, Op.STORE_GLOBAL, Op.PUT_PROP)) def(op, ANYTHING or OBJECT_OPERANDS)
        def(Op.GET_PROP, object : OpInfo(JsIr.name(Op.GET_PROP), ANYTHING) {
            override fun readsField(i: Inst, g: Graph): Any? = (ScalarReplacement.codeOf(g, i).constants[i.a] as? PropSite)?.key
        })
    }
}
