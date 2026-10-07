package io.neonjs

import io.neonjs.compiler.CodeBlock
import io.neonjs.compiler.Compiler
import io.neonjs.compiler.Source
import io.neonjs.jit.JitInput
import io.neonjs.jit.JvmCompiler
import io.neonjs.parser.ParseOptions
import io.neonjs.parser.Parser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** How code blocks become compiled code: class identity, sharing, batching, background compilation. */
class JitTest {
    private fun function(src: String, name: String = "f"): CodeBlock {
        val script = Compiler.compileScript(Parser.parse(src, ParseOptions(sourceName = "t.js")), Source("t.js", src))
        fun find(cb: CodeBlock): CodeBlock? {
            if (cb.name == name && cb !== script) return cb
            for (c in cb.constants) if (c is CodeBlock) find(c)?.let { return it }
            return null
        }
        return find(script) ?: error("no function $name")
    }

    @Test
    fun classIdentityFollowsTheCodeNotItsConstantsOrPosition() {
        val a = function("function f(x) { return x + 'p' }")
        val b = function("\n\n// elsewhere\nlet y = 1;\nfunction f(x) { return x + 'q' }")
        val c = function("function f(x) { return x * 'p' }")
        assertEquals(JitInput(a).identity, JitInput(b).identity, "constants and source positions do not change the class")
        assertNotEquals(JitInput(a).identity, JitInput(c).identity)
        assertNotEquals(JitInput(a, debugInfo = true).identity, JitInput(b, debugInfo = true).identity, "line numbers do")
        val (nameA, bytesA) = JvmCompiler.generate(JitInput(a))
        val (nameB, bytesB) = JvmCompiler.generate(JitInput(b))
        assertEquals(nameA, nameB)
        assertArrayEquals(bytesA, bytesB, "equal identities mean equal class files")
    }

    @Test
    fun everyInputTheGeneratorReadsIsPartOfTheIdentity() {
        // the old name hash looked at the instructions and register count only
        val cb = function("function f(g, h) { try { g() } catch (e) { h() } return 1 }")
        val base = JitInput(cb).identity
        val handlers = cb.handlers
        cb.handlers = handlers.copyOf().also { it[1]-- }
        assertNotEquals(base, JitInput(cb).identity, "handler ranges")
        cb.handlers = handlers
        val params = cb.paramRegs
        cb.paramRegs = params?.reversedArray()
        assertNotEquals(base, JitInput(cb).identity, "parameter registers")
        cb.paramRegs = params
        val lines = cb.lineTable
        cb.lineTable = lines.copyOf(lines.size - 2)
        assertNotEquals(base, JitInput(cb).identity, "statement starts")
        cb.lineTable = lines
        assertEquals(base, JitInput(cb).identity)
    }
}
