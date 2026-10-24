package dev.mooner.neonjs.jit.opt

import dev.mooner.neonjs.compiler.Op
import dev.mooner.neonjs.jit.JT
import dev.mooner.neonjs.jit.JitInput

/**
 * The kinds of the IR's values: the rules of the present compiler's type analysis (JitTypes, ARCHITECTURE "Unboxed
 * numbers") applied to SSA values instead of stack slots and registers. A value is a number only when every way of
 * producing it gives one, nothing comes from profiles, and the code generator emits exactly the kinds [result] gives.
 * Without [ints], nothing is INT; without [elems], an element store's result is boxed.
 */
internal class Kinds(private val ints: Boolean, private val elems: Boolean) {
    private val intKind = if (ints) JT.INT else JT.NUM

    /** Kind of a constant. */
    fun constKind(v: Any?): Byte = when (JitInput.constKind(v)) {
        JitInput.CONST_INT -> intKind
        JitInput.CONST_NUMBER -> JT.NUM
        else -> if (v is Boolean) JT.BOOL else JT.ANY
    }

    /** Kind of [v] (a constant's, or what [infer] gave it). */
    fun of(v: Value): Byte = if (v is Const) constKind(v.value) else v.kind

    /** Kind of the result of [i] given its inputs' kinds. */
    fun result(i: Inst): Byte {
        val op = i.op
        val inp = i.inputs
        return when (op) {
            Op.ADD, Op.SUB, Op.MUL, Op.DIV, Op.MOD, Op.EXP, Op.BAND, Op.BOR, Op.BXOR, Op.SHL, Op.SAR, Op.SHR,
            Op.LT, Op.GT, Op.LE, Op.GE, Op.EQ, Op.NE, Op.SEQ, Op.SNE ->
                // int32 arithmetic speculated (Ir.intArith): an int of ints
                if (i.state != null && Ir.intArith(op) && of(inp[0]) == JT.INT && of(inp[1]) == JT.INT) JT.INT
                else JT.binaryResult(op, JT.binaryClass(op, of(inp[0]), of(inp[1])), intKind)
            Op.NEG, Op.BNOT, Op.INC, Op.DEC, Op.TO_NUMERIC ->
                if (i.state != null && Ir.intArith(op) && of(inp[0]) == JT.INT) JT.INT else unaryResult(op, of(inp[0]))
            Op.TO_NUMBER -> when (of(inp[0])) {
                JT.INT -> JT.INT
                JT.BOOL -> intKind
                else -> JT.NUM
            }
            Op.NOT -> JT.BOOL
            Ir.CHECK_NUMBER -> JT.NUM
            Ir.IS_CLOSURE -> JT.BOOL
            Ir.MATH -> JT.NUM
            Ir.LENGTH -> intKind
            Ir.CHECK_INT32 -> JT.INT
            Op.PUT_ELEM -> {
                val k = of(inp[1])
                val v = of(inp[2])
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

    /**
     * Gives every phi and instruction its kind: a forward fixpoint in which phis start with no kind and take the join
     * of their inputs' (kinds only rise: INT, then NUM, or ANY).
     */
    fun infer(g: Graph) {
        val none: Byte = -1
        for (b in g.blocks) {
            for (p in b.phis) p.kind = none
            for (i in b.insts) i.kind = if (i.hasResult) none else JT.ANY
        }
        // a register's home holds every value SET_HOME stores in it: their join
        val homes = HashMap<Int, Byte>()
        var changed = true
        while (changed) {
            changed = false
            homes.clear()
            for (b in g.blocks) for (i in b.insts) if (i.op == Ir.SET_HOME) {
                val k = of(i.inputs[0])
                if (k == none) continue
                val h = homes[i.a]
                homes[i.a] = if (h == null) k else JT.join(h, k)
            }
            for (b in g.blocks) {
                for (p in b.phis) {
                    var k = none
                    for (v in p.inputs) {
                        val vk = of(v)
                        if (vk == none) continue
                        k = if (k == none) vk else JT.join(k, vk)
                    }
                    if (k != p.kind) {
                        p.kind = k
                        changed = true
                    }
                }
                for (i in b.insts) {
                    if (!i.hasResult) continue
                    // an input without a kind yet (a phi of a loop not seen through): wait for it
                    if (i.inputs.any { of(it) == none }) continue
                    val k = if (i.op == Ir.LOAD_HOME) homes[i.a] ?: JT.ANY else result(i)
                    if (k != i.kind) {
                        i.kind = k
                        changed = true
                    }
                }
            }
        }
        // a phi whose inputs never got a kind only merges itself: nothing reads a number from it
        for (b in g.blocks) {
            for (p in b.phis) if (p.kind == none) p.kind = JT.ANY
            for (i in b.insts) if (i.kind == none) i.kind = JT.ANY
        }
    }
}
