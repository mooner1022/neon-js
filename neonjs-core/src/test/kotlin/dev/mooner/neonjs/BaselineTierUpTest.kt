package dev.mooner.neonjs

import dev.mooner.neonjs.compiler.CodeBlock
import dev.mooner.neonjs.jit.CodeDefiners
import dev.mooner.neonjs.jit.Deopt
import dev.mooner.neonjs.jit.Jit
import dev.mooner.neonjs.jit.opt.T2
import dev.mooner.neonjs.jit.opt.T2Code
import dev.mooner.neonjs.vm.JSClosure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A baseline made by the optimizing tier's pipeline (T2.compileBaselines, a language's baseline: docs/IR_NEUTRALITY.md
 * §5) compiled with the tier on moves a running loop to the tier's code once it is installed (Graph.tierUp), as the
 * baseline compiler's code does: here the tier's code is installed by a host function the loop calls in its middle.
 */
class BaselineTierUpTest {
    @Test
    fun aRunningLoopMovesToTheTiersCode() {
        val src = """
            function loop(n) { var s = 0; for (var i = 0; i < n; i++) { s += i % 7; if (i === 100) install(); } return s }
        """.trimIndent()
        val n = 200_000
        var expected = 0.0
        for (i in 0 until n) expected += i % 7
        NeonEngine.builder().executionMode(ExecutionMode.ADAPTIVE).jitThreshold(1 shl 30).backgroundCompilation(false).console(null)
            .build().newContext().use { ctx ->
                ctx.eval(src)
                val cb: CodeBlock = (ctx.eval("loop").raw as JSClosure).code
                val baseline = T2.compileBaselines(listOf(cb), CodeDefiners.default, tierUp = true).single() as T2Code
                cb.baseline = baseline
                cb.compiled = baseline
                ctx.setFunction("install") { _ ->
                    Jit.install(cb, T2.compile(cb, CodeDefiners.default) ?: error("not compiled: ${T2.failureReasons.keys}"))
                    null
                }
                val before = Deopt.tierUps.get()
                assertEquals(expected, ctx.eval("loop($n)").asDouble())
                assertTrue(Deopt.tierUps.get() > before, "the loop stayed in the baseline")
                assertTrue(cb.compiled !== baseline)
            }
    }
}
