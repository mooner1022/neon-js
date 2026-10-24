package dev.mooner.neonjs.jit

import dev.mooner.neonjs.compiler.Op
import dev.mooner.neonjs.runtime.Env
import dev.mooner.neonjs.runtime.JSObject
import dev.mooner.neonjs.runtime.Undefined
import dev.mooner.neonjs.vm.Frame
import dev.mooner.neonjs.vm.Interpreter
import dev.mooner.neonjs.vm.JSClosure
import java.util.concurrent.atomic.AtomicLong

/**
 * Deoptimization (docs/OPTIMIZING_TIER.md, "Deoptimization"): compiled code leaves an activation to the interpreter in
 * the middle of its function, at an exit, with the interpreter state of every frame live there.
 *
 * The frame compiled code runs becomes an interpreted frame in place (it keeps its identity, which error objects,
 * frame chains and the agent's top frame hold). A call compiled inlined that is live at the exit becomes a frame of
 * its own: the innermost runs first, its result goes on its caller's operand stack and the caller continues after the
 * CALL; an exception it throws is thrown in the caller at the CALL, through the caller's handlers. A construction (at a
 * NEW) is resumed as one: the frame's `new.target` is the constructor, and the result is `this` unless the body
 * returned an object.
 */
object Deopt {
    /**
     * A call compiled inlined, live at an exit: the closure called, its `this` (bound as the call bound it), arguments
     * and environment, and its state: at [pc] with [values] (registers, then the operand stack), or, with [inner],
     * at the CALL at [pc] with [values] below that call's operands.
     */
    class Inlined(
        @JvmField val fn: JSClosure,
        @JvmField val thisValue: Any?,
        @JvmField val args: Array<Any?>,
        @JvmField val env: Env?,
        @JvmField val pc: Int,
        @JvmField val values: Array<Any?>,
        @JvmField val inner: Inlined?,
    )

    /** Exits taken, and those inside calls compiled inlined; loops moved to a later version of their code ([tierUp]). */
    @JvmField val count = AtomicLong()
    @JvmField val inlinedCount = AtomicLong()
    @JvmField val tierUps = AtomicLong()

    /**
     * Continues [f], whose code found a later version of its block's code installed at a loop back edge (JitRt.canTierUp),
     * in that version: the frame becomes interpreted at the loop header [pc] with the registers [values], then enters
     * the new code there by on-stack replacement; where that code declines the entry or deoptimizes, the interpreter goes
     * on. Returns the frame's result, or [JitRt.DEOPTED] when the code was given [f] interpreted (as [resume]).
     */
    @JvmStatic
    fun tierUp(f: Frame, pc: Int, values: Array<Any?>): Any? {
        tierUps.incrementAndGet()
        val entered = !f.isCompiled
        JitRt.dropInlined(f)
        write(f, values)
        f.pc = pc
        val r = Jit.osr(f)
        if (r !== JitRt.NO_OSR && r !== JitRt.DEOPTED) return r
        return if (entered) JitRt.DEOPTED else Interpreter.run(f)
    }

    /**
     * Continues [f], the frame of an activation of compiled code, in the interpreter: at [pc] with [values] (registers,
     * then the operand stack), or, with [inner], after the CALL at [pc] (with [values] below its operands) once the call
     * [inner] completed. Returns the frame's result; when the code was given [f] interpreted (on-stack replacement), it
     * returns [JitRt.DEOPTED] instead and the interpreter that called it continues the frame, so that a loop entering
     * compiled code and leaving it again does not nest interpreters.
     *
     * The call of this method must lie outside every exception range of the compiled method: the interpreter runs the
     * frame's handlers on what the rest of the function throws.
     */
    @JvmStatic
    fun resume(f: Frame, pc: Int, values: Array<Any?>, inner: Inlined?): Any? {
        count.incrementAndGet()
        val entered = !f.isCompiled
        // the calls compiled code ran inlined in the frame end here: they get frames of their own (finishCall)
        JitRt.dropInlined(f)
        write(f, values)
        f.pc = pc
        if (inner != null) {
            inlinedCount.incrementAndGet()
            f.deoptCall = inner
            // the frame is the agent's top frame already (compiled code or the interpreter runs it)
            finishCall(f)
        }
        return if (entered) JitRt.DEOPTED else Interpreter.run(f)
    }

    /**
     * Completes the call [f] waits for ([Frame.deoptCall]) at its CALL at [Frame.pc]: the callee runs in the
     * interpreter as a frame of its own, then its result is pushed and [f] is after the CALL, or the exception it threw
     * is [f]'s pending throw at the CALL. [f] is the agent's top frame.
     */
    @JvmStatic
    fun finishCall(f: Frame) {
        val c = f.deoptCall as Inlined
        f.deoptCall = null
        val callPc = f.pc
        try {
            val r = call(c, f.code.code[callPc] == Op.NEW)
            f.slots[f.sp++] = r
        } catch (t: Throwable) {
            f.pendingThrow = t
            f.throwPc = callPc
        }
        f.pc = callPc + Op.length(f.code.code, callPc)
    }

    private fun call(c: Inlined, construct: Boolean): Any? {
        val fn = c.fn
        val g = Frame(fn, fn.code, fn.realm, c.thisValue, c.args, if (construct) fn else Undefined, c.env, null)
        write(g, c.values)
        g.pc = c.pc
        // a call of its own completes once the frame is on the agent's frame chain (Interpreter.run)
        if (c.inner != null) g.deoptCall = c.inner
        val r0 = Interpreter.execute(g, null)
        val r = if (r0 === Interpreter.TAIL) Interpreter.callClosure(g.tailFn!!, g.tailThis, g.tailArgs!!) else r0
        // (as Interpreter.constructClosure)
        return if (construct) r as? JSObject ?: c.thisValue else r
    }

    /** Gives [f] slots (it keeps them if it has some) holding [values]: its registers, then its operand stack. */
    private fun write(f: Frame, values: Array<Any?>) {
        val code = f.code
        if (values.size < code.numRegs || values.size > code.numRegs + code.maxStack) {
            throw IllegalStateException("deoptimization state of ${values.size} values for ${code.numRegs} registers")
        }
        var s = f.slots
        if (s === Frame.NO_SLOTS) {
            s = arrayOfNulls(code.numRegs + code.maxStack)
            f.slots = s
        }
        values.copyInto(s)
        java.util.Arrays.fill(s, values.size, s.size, null)
        f.sp = values.size
    }
}
