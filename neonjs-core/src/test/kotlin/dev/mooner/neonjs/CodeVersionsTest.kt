package dev.mooner.neonjs

import dev.mooner.neonjs.compiler.CodeBlock
import dev.mooner.neonjs.jit.CompiledCode
import dev.mooner.neonjs.jit.Jit
import dev.mooner.neonjs.jit.JvmCompiler
import dev.mooner.neonjs.vm.Frame
import dev.mooner.neonjs.vm.JSClosure
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * A block's code versions (docs/OPTIMIZING_TIER.md, "Code versions"): the baseline stays, the code new activations run
 * can change to a later version and back.
 */
class CodeVersionsTest {
    private fun context() = NeonEngine.builder().executionMode(ExecutionMode.COMPILED).backgroundCompilation(false).console(null).build().newContext()

    private fun codeOf(ctx: NeonContext, fn: String): CodeBlock = (ctx.eval(fn).raw as JSClosure).code

    /** A later version that counts its activations and runs them with [base]. */
    private class Counting(val base: CompiledCode) : CompiledCode {
        var runs = 0
        override fun run(f: Frame): Any? {
            runs++
            return base.run(f)
        }
    }

    @Test
    fun laterVersionRunsNewActivationsUntilReverted() = context().use { ctx ->
        // called through `call`: a direct call of a declared function is compiled inlined in compiled mode
        ctx.eval("function f(x) { return x + 1 } f.call(null, 1)")
        val cb = codeOf(ctx, "f")
        val base = cb.baseline as CompiledCode
        assertSame(base, cb.compiled)
        val later = Counting(base)
        assertTrue(Jit.install(cb, later))
        assertSame(later, cb.compiled)
        assertSame(base, cb.baseline, "the baseline stays")
        assertEquals("3", ctx.eval("String(f.call(null, 2))").asString())
        assertEquals(1, later.runs)
        // compiling the block for the baseline tier again gives the baseline, not the version installed over it
        assertSame(base, JvmCompiler.compile(cb))
        Jit.revert(cb, later)
        assertSame(base, cb.compiled)
        assertEquals("4", ctx.eval("String(f.call(null, 3))").asString())
        assertEquals(1, later.runs)
    }

    @Test
    fun revertOnlyReplacesTheVersionItNames() = context().use { ctx ->
        ctx.eval("function f(x) { return x * 2 } f.call(null, 1)")
        val cb = codeOf(ctx, "f")
        val base = cb.baseline as CompiledCode
        val first = Counting(base)
        val second = Counting(base)
        Jit.install(cb, first)
        Jit.install(cb, second)
        Jit.revert(cb, first) // stale: the block runs `second` now
        assertSame(second, cb.compiled)
        Jit.revert(cb, second)
        assertSame(base, cb.compiled)
    }

    @Test
    fun nothingIsInstalledWithoutABaseline() {
        NeonEngine.builder().executionMode(ExecutionMode.INTERPRETER).console(null).build().newContext().use { ctx ->
            ctx.eval("function f() { return 1 } f()")
            val cb = codeOf(ctx, "f")
            assertNull(cb.baseline)
            assertFalse(Jit.install(cb, object : CompiledCode { override fun run(f: Frame): Any? = error("not installed") }))
            assertNull(cb.compiled)
            assertEquals("1", ctx.eval("String(f())").asString())
        }
    }
}
