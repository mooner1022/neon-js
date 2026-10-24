package dev.mooner.neonjs.jit.opt

import dev.mooner.neonjs.compiler.Op
import dev.mooner.neonjs.jit.JT

/**
 * Calls compiled inlined that nothing can see from outside the frame (docs/OPTIMIZING_TIER.md, P4): their body (with
 * the calls inlined in it) neither throws nor calls nor loops nor catches, so no stack trace, depth limit or handler
 * looks for them in the frame's record of inlined calls (Frame.inlineFn), and their InlineEnter and InlineExit generate
 * nothing ([InlineSite.quiet]). Their exits stay: an exit gives the call a frame of its own, as at any exit (Deopt.resume
 * gives back the depth of the calls recorded, these not being), and the interpreter then makes it as a call, its depth
 * limit included. A loop's interrupt check does not depend on calls (IrCodegen counts down at every back edge).
 */
internal object QuietInlines {
    fun run(g: Graph, kinds: Kinds) {
        val blocks = HashMap<InlineSite, ArrayList<Block>>()
        for (b in g.blocks) {
            var s = b.site
            while (s != null) {
                blocks.getOrPut(s) { ArrayList() }.add(b)
                s = s.parent
            }
        }
        // the innermost first: a body is quiet where the calls inlined in it are
        for (s in blocks.keys.sortedByDescending { it.depth }) {
            s.quiet = blocks[s]!!.all { b -> !b.loopHeader && !b.catchEntry && b.insts.all { quiet(it, kinds) } }
        }
    }

    /** Whether [i] can neither throw nor run code of its own (an exit is not a throw). */
    private fun quiet(i: Inst, kinds: Kinds): Boolean = when (i.op) {
        // numbers only: no valueOf, no BigInt mixing
        Op.ADD, Op.SUB, Op.MUL, Op.DIV, Op.MOD, Op.EXP, Op.BAND, Op.BOR, Op.BXOR, Op.SHL, Op.SAR, Op.SHR,
        Op.LT, Op.GT, Op.LE, Op.GE, Op.EQ, Op.NE, Op.NEG, Op.BNOT, Op.INC, Op.DEC, Op.TO_NUMERIC, Op.TO_NUMBER, Ir.MATH ->
            i.inputs.all { JT.isNum(kinds.of(it)) }
        Op.SEQ, Op.SNE, Op.NOT, Op.LOAD_ENV, Op.STORE_ENV -> true
        Ir.CHECK_NUMBER, Ir.CHECK_INT32, Ir.CHECK_CLOSURE, Ir.LOAD_SLOT, Ir.STORE_SLOT, Ir.LOAD_GLOBAL_LEX,
        Ir.LOAD_GLOBAL_SLOT, Ir.STRESS_EXIT, Ir.CLOSURE_ENV, Ir.IS_CLOSURE, Ir.CHECK_INTRINSIC, Ir.INLINE_THIS, Ir.SET_HOME, Ir.GOTO,
        Ir.BRANCH, Ir.SWITCH -> true
        Ir.INLINE_ENTER, Ir.INLINE_EXIT -> i.callee!!.quiet
        else -> false
    }
}
