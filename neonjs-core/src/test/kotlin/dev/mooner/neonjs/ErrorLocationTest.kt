package dev.mooner.neonjs

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The line, column and source name a NeonException reports for the code that failed, in every execution mode. Columns
 * are those of the stack trace's frames: where the failing expression starts.
 */
class ErrorLocationTest {
    private val modes = listOf(ExecutionMode.INTERPRETER, ExecutionMode.COMPILED, ExecutionMode.ADAPTIVE)

    private fun each(block: (NeonContext, ExecutionMode) -> Unit) {
        for (m in modes) {
            NeonEngine.builder().executionMode(m).jitThreshold(2).backgroundCompilation(false).console(null).build()
                .newContext().use { block(it, m) }
        }
    }

    private fun assertAt(e: NeonException, line: Int, column: Int, source: String?, mode: ExecutionMode) {
        assertEquals(listOf(line, column), listOf(e.line, e.column), "$mode: ${e.message}\n${e.jsStack}")
        assertEquals(source, e.sourceName, "$mode")
    }

    @Test
    fun syntaxErrorsWhereTheParserFoundThem() {
        each { ctx, m ->
            val e = assertThrows<NeonSyntaxException> { ctx.eval("var a = 1;\nvar b = 2;\nvar c = ;\n", "app.js") }
            assertAt(e, 3, 9, "app.js", m)
            // a script compiled first reports the same
            val c = assertThrows<NeonSyntaxException> { ctx.engine.compile("let x = 1;\n  let let = 2;", "lib.js") }
            assertEquals(2, c.line, "$m")
            assertEquals("lib.js", c.sourceName)
        }
    }

    @Test
    fun syntaxErrorsOfEvaluatedCodeInTheirCode() {
        each { ctx, m ->
            val e = assertThrows<NeonSyntaxException> { ctx.eval("var x = 1;\n\neval('1;\\n2;\\n3 +;')", "app.js") }
            assertAt(e, 3, 4, "eval", m)
        }
    }

    @Test
    fun runtimeErrorsWhereTheyArose() {
        each { ctx, m ->
            val src = "function f(o) {\n  return o.x.y;\n}\nf({ x: 1 });\nf({});\n"
            assertAt(assertThrows<NeonException> { ctx.eval(src, "rt.js") }, 2, 10, "rt.js", m)
            // an error made by the script: where it was made, the top of its stack
            val made = assertThrows<NeonException> { ctx.eval("function g() {\n  return new RangeError('r');\n}\nvar e = g();\n\nthrow e;", "made.js") }
            assertAt(made, 2, 10, "made.js", m)
            assertTrue(made.jsStack!!.lines().first().endsWith("(made.js:2:10)"), made.jsStack)
            assertAt(assertThrows<NeonException> { ctx.eval("var n = null;\n\n\nn.length", "null.js") }, 4, 1, "null.js", m)
        }
    }

    @Test
    fun valuesThatAreNoErrorsWhereTheyWereThrown() {
        each { ctx, m ->
            val src = "function check(v) {\n  if (v > 3) throw 'too big: ' + v;\n  return v;\n}\nfor (var i = 0; i < 10; i++) check(i);\n"
            val e = assertThrows<NeonException> { ctx.eval(src, "thr.js") }
            assertEquals("too big: 4", e.message)
            assertAt(e, 2, 20, "thr.js", m)
        }
    }

    @Test
    fun errorsInCodeOfTheOptimizingTier() {
        // a hot loop compiled by the optimizing tier (its callee inlined), failing once warm
        val src = """
            function inner(v) {
              if (v === 5000) throw new TypeError('at ' + v);
              return v + 1;
            }
            function outer(n) { var s = 0; for (var i = 0; i < n; i++) s += inner(i); return s }
            outer(3000); outer(3000);
            """.trimIndent()
        NeonEngine.builder().executionMode(ExecutionMode.ADAPTIVE).jitThreshold(2).backgroundCompilation(false).console(null)
            .build().newContext().use { ctx ->
                ctx.agent.config.tier2 = true
                ctx.eval(src, "t2.js")
                val e = assertThrows<NeonException> { ctx.eval("outer(10000)", "call.js") }
                assertAt(e, 2, 25, "t2.js", ExecutionMode.ADAPTIVE)
            }
    }

    @Test
    fun failuresOutsideJsHaveNoLocation() {
        NeonEngine.builder().sandbox(SandboxPolicy.builder().maxStatements(1000).build()).console(null).build().newContext().use { ctx ->
            val e = assertThrows<NeonTerminatedException> { ctx.eval("while (true) {}") }
            assertEquals(0, e.line)
            assertNull(e.sourceName)
        }
    }
}
