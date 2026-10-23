package dev.mooner.neonjs

import dev.mooner.neonjs.compiler.CodeBlock
import dev.mooner.neonjs.compiler.Op
import dev.mooner.neonjs.jit.CompiledCode
import dev.mooner.neonjs.jit.Deopt
import dev.mooner.neonjs.jit.Jit
import dev.mooner.neonjs.jit.JitInput
import dev.mooner.neonjs.jit.JvmCompiler
import dev.mooner.neonjs.jit.TypeAnalysis
import dev.mooner.neonjs.runtime.Undefined
import dev.mooner.neonjs.vm.Frame
import dev.mooner.neonjs.vm.JSClosure
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * The deoptimization runtime (Deopt) with hand-made exits: code versions that leave every activation to the interpreter
 * at once, or in the middle of a call they pretend to have compiled inlined. Each program must give what the
 * interpreter gives, stack traces included.
 */
class DeoptTest {
    private fun context(mode: ExecutionMode = ExecutionMode.ADAPTIVE) =
        NeonEngine.builder().executionMode(mode).jitThreshold(1 shl 30).backgroundCompilation(false).console(null).build().newContext()

    private fun closure(ctx: NeonContext, name: String): JSClosure = ctx.eval(name).raw as JSClosure

    /** Installs [version] (made from the baseline) over [cb], compiling the baseline first. */
    private fun install(cb: CodeBlock, version: (CompiledCode) -> CompiledCode) {
        if (cb.baseline == null) {
            val base = JvmCompiler.compile(cb) ?: error("${cb.name} not compiled: ${JvmCompiler.failureReasons.keys}")
            cb.baseline = base
            cb.compiled = base
        }
        assertTrue(Jit.install(cb, version(cb.baseline as CompiledCode)))
    }

    /** The interpreter's registers at the start of [f]'s code: undefined, the parameters from the arguments. */
    private fun entryRegisters(f: Frame): Array<Any?> = entryRegisters(f.code, f.args)

    private fun entryRegisters(cb: CodeBlock, args: Array<Any?>): Array<Any?> {
        val v = arrayOfNulls<Any?>(cb.numRegs)
        v.fill(Undefined)
        cb.paramRegs?.forEachIndexed { i, r -> if (r >= 0 && i < args.size) v[r] = args[i] }
        return v
    }

    /** Leaves every activation at its start, and every on-stack replacement where the interpreter is. */
    private class ExitAtOnce(val test: DeoptTest) : CompiledCode {
        var exits = 0
        override fun run(f: Frame): Any? {
            exits++
            if (!f.isCompiled) return Deopt.resume(f, f.pc, f.slots.copyOf(f.sp), null)
            return Deopt.resume(f, 0, test.entryRegisters(f), null)
        }
    }

    private fun interpreted(src: String, expr: String): String =
        context(ExecutionMode.INTERPRETER).use { it.eval(src); it.eval(expr).asString() }

    private val programs = """
        function loop(n) { var s = 0; for (var i = 0; i < n; i++) s += i % 7; return s }
        function fact(n) { return n <= 1 ? 1 : n * fact(n - 1) }
        function guarded(x) { try { if (x > 2) throw new TypeError('big ' + x); return 'ok ' + x } catch (e) { return e.message } finally { guarded.count = (guarded.count | 0) + 1 } }
        function counter() { let c = 0; return { inc: () => ++c, get: function () { return c } } }
        function self() { 'use strict'; return typeof this }
        function args() { return arguments.length + ':' + Array.prototype.join.call(arguments, ',') }
        function stack(n) { if (n === 0) return new Error('deep').stack; return stack(n - 1) }
        function thrower(x) { null.x = x }
    """.trimIndent()

    private val names = listOf("loop", "fact", "guarded", "counter", "self", "args", "stack", "thrower")

    private val expr = "[loop(10000), fact(10), guarded(1), guarded(5), guarded.count, (() => { const k = counter(); k.inc(); k.inc(); return k.get() })()," +
        " self.call(3), args(1, 'a', null), stack(3), (() => { try { thrower(1) } catch (e) { return e.stack } })()].join('|')"

    @Test
    fun activationsLeftAtTheirStart() {
        val expected = interpreted(programs, expr)
        context().use { ctx ->
            ctx.eval(programs)
            val versions = names.map { n ->
                var v: ExitAtOnce? = null
                install(closure(ctx, n).code) { ExitAtOnce(this).also { v = it } }
                v!!
            }
            val before = Deopt.count.get()
            assertEquals(expected, ctx.eval(expr).asString())
            assertTrue(versions.all { it.exits > 0 }, "every version ran")
            assertTrue(Deopt.count.get() - before >= versions.sumOf { it.exits })
        }
    }

    @Test
    fun loopsEnteringAndLeavingAgain() {
        // the function's activation leaves at its start; then each loop check enters the code by on-stack replacement,
        // which leaves at once (JitRt.DEOPTED): the interpreter goes on without nesting
        val src = "function f(n) { var s = 0, t = ''; for (var i = 0; i < n; i++) { s += i & 3; if (i % 9999 === 0) t += i } return s + ' ' + t }"
        val expected = interpreted(src, "f(100000)")
        context().use { ctx ->
            ctx.eval(src)
            var v: ExitAtOnce? = null
            install(closure(ctx, "f").code) { ExitAtOnce(this).also { v = it } }
            val entered = Jit.osrEntered.get()
            assertEquals(expected, ctx.eval("f(100000)").asString())
            assertTrue(v!!.exits > 20, "on-stack replacements: ${v!!.exits}")
            assertTrue(Jit.osrEntered.get() - entered > 20)
        }
    }

    /**
     * The registers of [cb] at its first CALL, called with [args] in [env]: the entry registers, but for an environment
     * saved before a `try` (GET_ENV, STORE_REG), which is all these functions store before their call.
     */
    private fun registersAtCall(cb: CodeBlock, args: Array<Any?>, env: Any?): Array<Any?> {
        val v = entryRegisters(cb, args)
        var pc = 0
        while (cb.code[pc] != Op.CALL) {
            val op = cb.code[pc]
            if (op == Op.STORE_REG) {
                assertTrue(pc > 0 && cb.code[pc - 1] == Op.GET_ENV, "a register stored before the call in ${cb.name}")
                v[cb.code[pc + 1]] = env
            }
            pc += Op.length(cb.code, pc)
        }
        return v
    }

    /** The pc of the first CALL in [cb], checking that [below] values are under its operands there. */
    private fun callWith(cb: CodeBlock, below: Int): Int {
        var pc = 0
        while (cb.code[pc] != Op.CALL) pc += Op.length(cb.code, pc)
        val ta = TypeAnalysis(JitInput(cb, planInlining = false), ints = true, elems = true).run()
        assertEquals(below + 2 + cb.code[pc + 1], ta.stackIn[pc]!!.size, "operand stack at the call of ${cb.name}")
        return pc
    }

    /**
     * Leaves at the start of [caller]'s activation as if it had reached its first CALL (with [below] under its
     * operands) and the callee chain [callees] were inlined: each callee but the last at its own first CALL (with the
     * value under its operands given), the last at its start.
     */
    private inner class ExitInCall(
        val below: List<Any?>,
        val callees: List<Pair<JSClosure, List<Any?>>>,
        val args: List<Array<Any?>>,
    ) : CompiledCode {
        override fun run(f: Frame): Any? {
            val cb = f.code
            val pc = callWith(cb, below.size)
            var inner: Deopt.Inlined? = null
            for (k in callees.indices.reversed()) {
                val (fn, calleeBelow) = callees[k]
                val last = k == callees.size - 1
                val cpc = if (last) 0 else callWith(fn.code, calleeBelow.size)
                inner = Deopt.Inlined(fn, Undefined, args[k], fn.env, cpc, arrayOf(*(if (last) entryRegisters(fn.code, args[k]) else registersAtCall(fn.code, args[k], fn.env)), *calleeBelow.toTypedArray()), inner)
            }
            return Deopt.resume(f, pc, arrayOf(*registersAtCall(cb, f.args, f.env), *below.toTypedArray()), inner)
        }
    }

    @Test
    fun inlinedCallsCompleteFirst() {
        val src = """
            function inner(a, b) { return a * 10 + b }
            function outer(x) { return 1 + inner(x, 2) }
            function leaf(a) { return a + 1 }
            function mid(a) { return 2 * leaf(a) }
            function top(x) { return 3 + mid(x) }
            function boom(v) { throw new RangeError('boom ' + v) }
            function guarded(x) { try { return 1 + boom(x) } catch (e) { return 'caught ' + e.message + ' ' + (e.stack.split('\n').length) } }
            function unguarded(x) { return 1 + boom(x) }
            function traced(v) { return new Error('e' + v).stack }
            function outerTrace(x) { return 1 + traced(x) }
        """.trimIndent()
        val e = "[outer(4), top(5), guarded(6), (() => { try { unguarded(7) } catch (e) { return e.stack } })(), outerTrace(8)].join('|')"
        val expected = interpreted(src, e)
        context().use { ctx ->
            ctx.eval(src)
            val one = 1.0
            install(closure(ctx, "outer").code) { ExitInCall(listOf(one), listOf(closure(ctx, "inner") to emptyList()), listOf(arrayOf(4.0, 2.0))) }
            install(closure(ctx, "top").code) {
                ExitInCall(listOf(3.0), listOf(closure(ctx, "mid") to listOf<Any?>(2.0), closure(ctx, "leaf") to emptyList()), listOf(arrayOf(5.0), arrayOf(5.0)))
            }
            install(closure(ctx, "guarded").code) { ExitInCall(listOf(one), listOf(closure(ctx, "boom") to emptyList()), listOf(arrayOf(6.0))) }
            install(closure(ctx, "unguarded").code) { ExitInCall(listOf(one), listOf(closure(ctx, "boom") to emptyList()), listOf(arrayOf(7.0))) }
            install(closure(ctx, "outerTrace").code) { ExitInCall(listOf(one), listOf(closure(ctx, "traced") to emptyList()), listOf(arrayOf(8.0))) }
            assertEquals(expected, ctx.eval(e).asString())
        }
    }
}
