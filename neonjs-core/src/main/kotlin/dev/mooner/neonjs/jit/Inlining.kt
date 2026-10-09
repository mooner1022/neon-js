package dev.mooner.neonjs.jit

import dev.mooner.neonjs.compiler.CallFeedback
import dev.mooner.neonjs.compiler.CodeBlock
import dev.mooner.neonjs.compiler.Op

/**
 * Which calls compiled code inlines (docs/ARCHITECTURE.md, section JIT, "Inlined calls").
 *
 * The interpreter records, at each CALL, the code block of the JS function called most ([CallFeedback], a majority
 * vote). When the caller is compiled, a call whose recorded callee is small and simple enough gets the callee's body
 * compiled into the caller behind a guard: the function called is a [dev.mooner.neonjs.vm.JSClosure] whose code block
 * is that very block (`CodeBlock.inlineTargets`, an object identity) in the caller's realm. Anything else takes the
 * ordinary call. Nothing is assumed beyond the guard, and inlined bodies never inline further.
 */
internal object Inlining {
    /** `-Dneonjs.jit.inline=false` turns inlining off (part of the class identity). */
    @JvmField val ENABLED = System.getProperty("neonjs.jit.inline") != "false"

    /** Longest callee inlined, in bytecode ints. */
    const val MAX_CALLEE_CODE = 96
    /** Most calls inlined into one function. */
    const val MAX_SITES = 8
    /**
     * Calls a site must have recorded for its majority callee before it is inlined; `-Dneonjs.jit.inlineMinCount=N`
     * changes it (1 inlines nearly every call that ran: for stress tests).
     */
    @JvmField val MIN_COUNT = Integer.getInteger("neonjs.jit.inlineMinCount", 16)

    /** The instructions an inlined body may contain: those the code generator compiles for it (JvmCompiler, Gen). */
    private val ALLOWED = BooleanArray(256).also { a ->
        for (op in intArrayOf(
            Op.NOP, Op.PUSH_UNDEF, Op.PUSH_NULL, Op.PUSH_TRUE, Op.PUSH_FALSE, Op.PUSH_CONST, Op.PUSH_INT,
            Op.POP, Op.DUP, Op.DUP2, Op.SWAP, Op.ROT3,
            Op.LOAD_REG, Op.STORE_REG, Op.LOAD_REG_TDZ, Op.CHECK_REG_TDZ,
            Op.LOAD_ENV, Op.STORE_ENV, Op.LOAD_ENV_TDZ, Op.CHECK_ENV_TDZ,
            Op.LOAD_GLOBAL, Op.LOAD_GLOBAL_TYPEOF, Op.STORE_GLOBAL,
            Op.GET_PROP, Op.PUT_PROP, Op.GET_ELEM, Op.PUT_ELEM,
            Op.ADD, Op.SUB, Op.MUL, Op.DIV, Op.MOD, Op.EXP, Op.SHL, Op.SAR, Op.SHR, Op.BAND, Op.BOR, Op.BXOR,
            Op.EQ, Op.NE, Op.SEQ, Op.SNE, Op.LT, Op.GT, Op.LE, Op.GE, Op.INSTANCEOF, Op.IN,
            Op.NEG, Op.TO_NUMBER, Op.NOT, Op.BNOT, Op.TYPEOF, Op.TO_NUMERIC, Op.INC, Op.DEC, Op.TO_STRING, Op.CONCAT,
            Op.JUMP, Op.JUMP_IF_TRUE, Op.JUMP_IF_FALSE, Op.JUMP_IF_NULLISH, Op.JUMP_IF_NOT_NULLISH,
            Op.JUMP_IF_UNDEFINED, Op.JUMP_IF_NOT_UNDEFINED,
            Op.RETURN, Op.CALL, Op.NEW, Op.THROW, Op.THROW_ERROR,
            Op.NEW_OBJECT, Op.NEW_ARRAY, Op.ARRAY_PUSH, Op.ARRAY_HOLE, Op.NEW_OBJECT_LITERAL,
        )) a[op] = true
    }

    /**
     * Whether [callee] can be inlined into [caller]: an ordinary function (not a generator, async function or class
     * constructor), with the caller's strictness (helpers read it from the caller's frame), no exception handlers, its
     * parameters in registers, and only [ALLOWED] instructions: none that reads the callee's own frame (`this`,
     * `arguments`, `new.target`, the function itself), creates scopes or closures, or resolves names dynamically.
     */
    fun eligible(caller: CodeBlock, callee: CodeBlock): Boolean {
        val f = callee.flags
        if (f and (CodeBlock.GENERATOR or CodeBlock.ASYNC or CodeBlock.CLASS_CTOR or CodeBlock.DERIVED or CodeBlock.NO_JIT) != 0) return false
        if ((f and CodeBlock.STRICT) != (caller.flags and CodeBlock.STRICT)) return false
        if (callee.strictPcs != null || callee.handlers.isNotEmpty()) return false
        val code = callee.code
        if (code.size > MAX_CALLEE_CODE) return false
        // null: no parameter in a register (none at all, or ones read with LOAD_ARG, which is not allowed)
        callee.paramRegs?.let { pr -> for (r in pr) if (r < 0) return false }
        var pc = 0
        while (pc < code.size) {
            val op = code[pc]
            if (op < 0 || op >= ALLOWED.size || !ALLOWED[op]) return false
            pc += Op.length(code, pc)
        }
        return true
    }

    /** The calls of [cb] to inline: (pc of the CALL, callee), from the interpreter's feedback, at most [MAX_SITES]. */
    fun plan(cb: CodeBlock): List<Pair<Int, CodeBlock>> {
        if (!ENABLED) return emptyList()
        val fb = cb.callFeedback ?: return emptyList()
        val code = cb.code
        val out = ArrayList<Pair<Int, CodeBlock>>()
        var pc = 0
        while (pc < code.size && out.size < MAX_SITES) {
            if (code[pc] == Op.CALL && pc < fb.size) {
                val r = fb[pc]
                val target = r?.target
                if (target != null && r.count >= MIN_COUNT && eligible(cb, target)) out.add(pc to target)
            }
            pc += Op.length(code, pc)
        }
        return out
    }
}
