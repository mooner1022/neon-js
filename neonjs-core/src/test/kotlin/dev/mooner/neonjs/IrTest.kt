package dev.mooner.neonjs

import dev.mooner.neonjs.compiler.CodeBlock
import dev.mooner.neonjs.compiler.Compiler
import dev.mooner.neonjs.compiler.Source
import dev.mooner.neonjs.jit.JitBailout
import dev.mooner.neonjs.jit.opt.Graph
import dev.mooner.neonjs.jit.opt.IrBuilder
import dev.mooner.neonjs.jit.opt.IrPrinter
import dev.mooner.neonjs.jit.opt.IrVerifier
import dev.mooner.neonjs.parser.ParseOptions
import dev.mooner.neonjs.parser.Parser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The optimizing tier's IR (jit/opt): graphs built from bytecode, in SSA form, with the present compiler's kinds. */
class IrTest {
    private fun function(src: String, name: String = "f"): CodeBlock {
        val script = Compiler.compileScript(Parser.parse(src, ParseOptions(sourceName = "t.js")), Source("t.js", src))
        fun find(cb: CodeBlock): CodeBlock? {
            if (cb.name == name && cb !== script) return cb
            for (c in cb.constants) if (c is CodeBlock) find(c)?.let { return it }
            return null
        }
        return find(script) ?: error("no function $name")
    }

    private fun build(src: String, name: String = "f"): Graph =
        IrBuilder(function(src, name), ints = true, elems = true).build().also { IrVerifier.verify(it) }

    @Test
    fun straightLine() {
        val g = build("function f(a, b) { return a * b + 1 }")
        assertEquals(
            """
            B0 (pc -1)
              v0 = Param 0
              v1 = Param 1
              Goto -> B1
            B1 (pc 0) <- B0
              v2 = MUL v0, v1
              v3 = ADD v2, 1.0
              Return v3
            """.trimIndent() + "\n",
            IrPrinter.print(g),
        )
    }

    @Test
    fun loopCountersArePhis() {
        val g = build("function f(n) { var s = 0; for (var i = 0; i < n; i++) s = (s + i) | 0; return s }")
        val text = IrPrinter.print(g)
        // the loop header merges the counter and the sum, kept unboxed as ints: the sum's add wraps (its use truncates),
        // the counter's increment exits where it would leave the int32 range
        val header = g.blocks.first { it.loopHeader }
        assertEquals(2, header.phis.size, text)
        assertTrue(header.phis.all { it.kind == dev.mooner.neonjs.jit.JT.INT }, text)
        assertTrue(text.contains("wraps") && g.blocks.any { b -> b.insts.any { it.op == dev.mooner.neonjs.compiler.Op.INC && it.state != null } }, text)
        assertTrue(text.contains("Branch(JUMP_IF_FALSE)"), text)
    }

    @Test
    fun unusedResultsGo() {
        // t is never read: its arithmetic goes (the counter's increment, with its exit, stays)
        val g = build("function f(n) { for (var i = 0; i < n; i++) { var t = i * 2 + 1 } return n }")
        val text = IrPrinter.print(g)
        assertTrue(g.blocks.none { b -> b.insts.any { it.op == dev.mooner.neonjs.compiler.Op.MUL || it.op == dev.mooner.neonjs.compiler.Op.ADD } }, text)
        assertTrue(g.blocks.any { b -> b.insts.any { it.op == dev.mooner.neonjs.compiler.Op.INC } }, text)
    }

    @Test
    fun valuesOfUnknownTypeStayObjects() {
        val g = build("function f(o) { var t = 0; for (var k = 0; k < 3; k++) t = t + o.x; return t }")
        val header = g.blocks.first { it.loopHeader }
        // t + o.x: o.x may be a string, so t is not a number
        assertTrue(header.phis.any { it.kind == dev.mooner.neonjs.jit.JT.ANY }, IrPrinter.print(g))
    }

    @Test
    fun stackValuesAcrossBlocks() {
        // the conditional expression leaves its value on the operand stack across the join: a phi of a stack slot
        val g = build("function f(c, a) { return 10 + (c ? a : 2) }")
        assertTrue(g.blocks.any { b -> b.phis.isNotEmpty() }, IrPrinter.print(g))
    }

    @Test
    fun unsupportedCodeIsNotBuilt() {
        assertThrows<JitBailout> { build("function f(o) { with (o) { return g() } }") }
        assertThrows<JitBailout> { build("function* f() { yield 1 }") }
    }
}
