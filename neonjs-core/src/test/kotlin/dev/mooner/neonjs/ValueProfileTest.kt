package dev.mooner.neonjs

import dev.mooner.neonjs.compiler.CodeBlock
import dev.mooner.neonjs.compiler.Op
import dev.mooner.neonjs.compiler.ValueProfile
import dev.mooner.neonjs.vm.JSClosure
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** The interpreter's value profiles (CodeBlock.profile), recorded for the optimizing tier when it is on. */
class ValueProfileTest {
    private fun context(mode: ExecutionMode, tier2: Boolean): NeonContext {
        val ctx = NeonEngine.builder().executionMode(mode).jitThreshold(1 shl 30).console(null).build().newContext()
        ctx.agent.config.tier2 = tier2
        return ctx
    }

    private fun codeOf(ctx: NeonContext, fn: String): CodeBlock = (ctx.eval(fn).raw as JSClosure).code

    /** The profile at the [n]th instruction [op] of [cb]. */
    private fun at(cb: CodeBlock, op: Int, n: Int = 0): Int {
        var pc = 0
        var k = 0
        while (pc < cb.code.size) {
            if (cb.code[pc] == op && k++ == n) return cb.profile!!.values[pc].toInt() and 0xFF
            pc += Op.length(cb.code, pc)
        }
        error("no ${Op.names[op]} #$n")
    }

    private val src = """
        function id(v) { return v }
        function f(o, a, i, x) {
          var p = o.p;          // GET_PROP
          var e = a[i];         // GET_ELEM
          var r = id(x);        // CALL
          return [p, e, r]
        }
        function counter() { var c = 0; return function () { return c++ } }  // LOAD_ENV of c
    """.trimIndent()

    @Test
    fun kindsSeenByTheInterpreter() = context(ExecutionMode.ADAPTIVE, tier2 = true).use { ctx ->
        ctx.eval(src)
        ctx.eval("f({ p: 1 }, [1.5], 0, 'x'); f({ p: -0 }, ['s'], 0, null); f({ p: 2 }, [], 3, f); var g = counter(); g(); g()")
        val f = codeOf(ctx, "f")
        assertEquals(ValueProfile.INT or ValueProfile.DOUBLE, at(f, Op.GET_PROP), "1, -0 and 2")
        assertEquals(ValueProfile.DOUBLE or ValueProfile.STRING or ValueProfile.NULLISH, at(f, Op.GET_ELEM), "1.5, 's', a hole")
        assertEquals(ValueProfile.STRING or ValueProfile.NULLISH or ValueProfile.FUNCTION, at(f, Op.CALL), "'x', null, f")
        val params = f.profile!!.params
        assertEquals(4, params.size)
        assertEquals(ValueProfile.OBJECT, params[0].toInt())
        assertEquals(ValueProfile.INT, params[2].toInt() and 0xFF)
        assertEquals(ValueProfile.STRING or ValueProfile.NULLISH or ValueProfile.FUNCTION, params[3].toInt() and 0xFF)
        val inc = codeOf(ctx, "g")
        assertEquals(ValueProfile.INT, at(inc, Op.LOAD_ENV))
    }

    @Test
    fun nothingRecordedWithoutTheTierOrInterpretation() {
        for ((mode, tier2) in listOf(ExecutionMode.ADAPTIVE to false, ExecutionMode.INTERPRETER to true)) {
            context(mode, tier2).use { ctx ->
                ctx.eval(src)
                ctx.eval("f({ p: 1 }, [1], 0, 1)")
                assertNull(codeOf(ctx, "f").profile, "$mode, tier2 $tier2")
            }
        }
    }
}
