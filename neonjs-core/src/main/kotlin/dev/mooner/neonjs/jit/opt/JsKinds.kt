package dev.mooner.neonjs.jit.opt

import dev.mooner.neonjs.compiler.Op
import dev.mooner.neonjs.jit.JT
import dev.mooner.neonjs.jit.JitInput
import dev.mooner.neonjs.jit.Emit
import dev.mooner.neonjs.jit.JsEmit

/**
 * JavaScript's kinds ([JT]): the rules of the baseline's type analysis (JitTypes, ARCHITECTURE "Unboxed numbers")
 * applied to SSA values. A value is a number only when every way of producing it gives one, nothing comes from
 * profiles but through a check that exits, and the code generator emits exactly the kinds [resultKind] gives.
 * Without [ints], nothing is INT; without [elems], an element store's result is boxed.
 */
internal class JsKinds private constructor(@JvmField val ints: Boolean, private val elems: Boolean) : KindSystem {
    private val intKind = if (ints) JT.INT else JT.NUM

    override fun rep(kind: Byte): Rep = when (kind) {
        JT.NUM -> Rep.DOUBLE
        JT.INT, JT.BOOL -> Rep.INT
        else -> Rep.OBJECT
    }

    override fun join(a: Byte, b: Byte): Byte = JT.join(a, b)

    override fun isNumeric(kind: Byte): Boolean = JT.isNum(kind)

    override fun constKind(value: Any?): Byte = when (JitInput.constKind(value)) {
        JitInput.CONST_INT -> intKind
        JitInput.CONST_NUMBER -> JT.NUM
        else -> if (value is Boolean) JT.BOOL else JT.ANY
    }

    override fun resultKind(i: Inst, kinds: Kinds): Byte {
        val op = i.op
        val inp = i.inputs
        return when (op) {
            Op.ADD, Op.SUB, Op.MUL, Op.DIV, Op.MOD, Op.EXP, Op.BAND, Op.BOR, Op.BXOR, Op.SHL, Op.SAR, Op.SHR,
            Op.LT, Op.GT, Op.LE, Op.GE, Op.EQ, Op.NE, Op.SEQ, Op.SNE ->
                // int32 arithmetic speculated (JsIr.intArith): an int of ints
                if (i.state != null && JsIr.intArith(op) && kinds.of(inp[0]) == JT.INT && kinds.of(inp[1]) == JT.INT) JT.INT
                else JT.binaryResult(op, JT.binaryClass(op, kinds.of(inp[0]), kinds.of(inp[1])), intKind)
            Op.NEG, Op.BNOT, Op.INC, Op.DEC, Op.TO_NUMERIC ->
                if (i.state != null && JsIr.intArith(op) && kinds.of(inp[0]) == JT.INT) JT.INT else unaryResult(op, kinds.of(inp[0]))
            Op.TO_NUMBER -> when (kinds.of(inp[0])) {
                JT.INT -> JT.INT
                JT.BOOL -> intKind
                else -> JT.NUM
            }
            Op.NOT, Op.RESUME_DISPATCH, JsIr.YSTAR_DONE -> JT.BOOL
            JsIr.CHECK_NUMBER -> JT.NUM
            Ir.IS_CLOSURE -> JT.BOOL
            JsIr.MATH, JsIr.CHAR_CODE -> JT.NUM
            JsIr.LENGTH -> intKind
            JsIr.CHECK_INT32 -> JT.INT
            Op.PUT_ELEM -> {
                val k = kinds.of(inp[1])
                val v = kinds.of(inp[2])
                if (elems && JT.isNum(k) && JT.isNum(v)) v else JT.ANY
            }
            else -> JT.ANY
        }
    }

    /** As TypeAnalysis.unaryResult. */
    fun unaryResult(op: Int, t: Byte): Byte = when {
        !JT.isNum(t) -> JT.ANY
        op == Op.BNOT -> intKind
        op == Op.TO_NUMERIC -> t
        else -> JT.NUM // -0 is -0, and ++ / -- can leave the int32 range
    }

    override fun initial(em: Emit, kind: Byte) {
        val r = rep(kind)
        if (r == Rep.OBJECT) (em as JsEmit).pushUndefined() else r.zero(em.mv)
    }

    override fun convert(em: Emit, from: Byte, to: Byte) = (em as JsEmit).convert(from, to)

    override fun osrCode(kind: Byte): Char = when (kind) {
        JT.NUM -> 'N'
        JT.INT -> 'I'
        JT.BOOL -> 'B'
        else -> '.'
    }

    override fun osrRead(em: Emit, kind: Byte) {
        when (kind) {
            JT.NUM -> em.rt("slotNum", "([Ljava/lang/Object;I)D")
            JT.INT -> em.rt("slotInt", "([Ljava/lang/Object;I)I")
            JT.BOOL -> em.rt("slotBool", "([Ljava/lang/Object;I)Z")
            else -> em.mv.visitInsn(org.objectweb.asm.Opcodes.AALOAD)
        }
    }

    override fun name(kind: Byte): String = when (kind) {
        JT.NUM -> " :num"
        JT.INT -> " :int"
        JT.BOOL -> " :bool"
        else -> ""
    }

    companion object {
        private val all = Array(4) { JsKinds(it and 1 != 0, it and 2 != 0) }

        /** The kinds with or without int32s ([ints]) and unboxed element stores ([elems]). */
        fun of(ints: Boolean, elems: Boolean): JsKinds = all[(if (ints) 1 else 0) or (if (elems) 2 else 0)]
    }
}
