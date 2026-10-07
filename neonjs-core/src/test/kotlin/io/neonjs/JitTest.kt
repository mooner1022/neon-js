package io.neonjs

import io.neonjs.compiler.CodeBlock
import io.neonjs.compiler.Compiler
import io.neonjs.compiler.Source
import io.neonjs.jit.CodeCache
import io.neonjs.jit.CodeDefiner
import io.neonjs.jit.CompiledCode
import io.neonjs.jit.JitInput
import io.neonjs.jit.JvmCodeDefiner
import io.neonjs.jit.JvmCompiler
import io.neonjs.parser.ParseOptions
import io.neonjs.parser.Parser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

/** How code blocks become compiled code: class identity, sharing, batching, background compilation. */
class JitTest {
    /** Defines classes like the JVM definer and counts them (the first one is the definer probe). */
    private class CountingDefiner : CodeDefiner {
        val defined = AtomicInteger()
        private val jvm = JvmCodeDefiner()
        override fun define(name: String, bytes: ByteArray, parent: ClassLoader): Class<*> {
            defined.incrementAndGet()
            return jvm.define(name, bytes, parent)
        }
    }

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

    @Test
    fun blocksWithTheSameIdentityShareCompiledCode() {
        val d = CountingDefiner()
        val a = JvmCompiler.compile(function("function f(x) { return x + 'p' }"), d)
        val b = JvmCompiler.compile(function("\n\nfunction f(x) { return x + 'q' }"), d)
        assertNotNull(a)
        assertSame(a, b)
        assertEquals(2, d.defined.get(), "the probe and one class")
        assertNotSame(a, JvmCompiler.compile(function("function f(x) { return x + 'p' }"), CountingDefiner()), "one cache per definer")
    }

    @Test
    fun contextsOfAnEngineShareCompiledCode() {
        val d = CountingDefiner()
        val engine = NeonEngine.builder().executionMode(ExecutionMode.COMPILED).codeDefiner(d).console(null).build()
        val src = "function g(x) { return x * 2 } g(21)"
        engine.newContext().use { assertEquals(42, it.eval(src).asInt()) }
        val afterFirst = d.defined.get()
        engine.newContext().use { assertEquals(42, it.eval(src).asInt()) }
        assertEquals(afterFirst, d.defined.get(), "the second context compiles nothing")
    }

    @Test
    fun sharedCodeIsNotKeptAliveByTheCache() {
        val d = CountingDefiner()
        var cb: CodeBlock? = function("function f(x) { return x - 1 }")
        var code: CompiledCode? = JvmCompiler.compile(cb!!, d)
        val ref = WeakReference(code)
        val identity = JitInput(cb).identity
        assertSame(code, CodeCache.of(d)[identity])
        cb = null
        code = null
        for (i in 0 until 100) {
            if (ref.get() == null) break
            System.gc()
            Thread.sleep(10)
        }
        assertNull(ref.get(), "unused compiled code is collected")
        assertNull(CodeCache.of(d)[identity])
    }
}
