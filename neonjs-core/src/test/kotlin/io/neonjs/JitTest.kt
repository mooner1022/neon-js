package io.neonjs

import io.neonjs.compiler.CodeBlock
import io.neonjs.compiler.Compiler
import io.neonjs.compiler.Source
import io.neonjs.jit.ClassBackend
import io.neonjs.jit.CodeCache
import io.neonjs.jit.CodeDefiner
import io.neonjs.jit.CompiledCode
import io.neonjs.jit.GeneratedClass
import io.neonjs.jit.JitInput
import io.neonjs.jit.JitQueue
import io.neonjs.jit.JvmCodeDefiner
import io.neonjs.jit.JvmCompiler
import io.neonjs.parser.ParseOptions
import io.neonjs.parser.Parser
import io.neonjs.runtime.Agent
import io.neonjs.vm.JSClosure
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
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

    /** Records the batch sizes it is given; rejects classes whose name contains [reject]. */
    private class BatchRecorder(val reject: String? = null) : CodeDefiner {
        val batches = ArrayList<Int>()
        private val jvm = JvmCodeDefiner()
        override fun define(name: String, bytes: ByteArray, parent: ClassLoader): Class<*> {
            if (reject != null && name.contains(reject)) throw IllegalStateException("rejected")
            return jvm.define(name, bytes, parent)
        }

        override fun defineAll(classes: List<GeneratedClass>, parent: ClassLoader): List<Class<*>> {
            synchronized(batches) { batches.add(classes.size) }
            if (reject != null && classes.any { it.name.contains(reject) }) throw IllegalStateException("batch rejected")
            return classes.map { jvm.define(it.name, it.bytes, parent) }
        }
    }

    /** Records the thread defining each class; with a [gate], definitions (but the probe's) wait for it to open. */
    private class ThreadRecorder(private val gate: CountDownLatch? = null, private val entered: Semaphore? = null) : CodeDefiner {
        val threads = ConcurrentHashMap<String, String>()
        private val jvm = JvmCodeDefiner()
        override fun define(name: String, bytes: ByteArray, parent: ClassLoader): Class<*> {
            threads[name] = Thread.currentThread().name
            if (gate != null && name != JvmCompiler.PROBE_CLASS) {
                entered?.release()
                gate.await()
            }
            return jvm.define(name, bytes, parent)
        }

        fun threadOf(function: String): String? = threads.entries.firstOrNull { it.key.contains($$"JS$$$function$") }?.value
    }

    private fun codeOf(ctx: NeonContext, fn: String): CodeBlock = (ctx.eval(fn).raw as JSClosure).code

    private fun waitFor(what: String, cond: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!cond()) {
            if (System.nanoTime() > end) fail<Unit>("timed out waiting for $what")
            Thread.sleep(5)
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

    @Test
    fun aBatchIsDefinedAtOnceWithOneClassPerIdentity() {
        val d = BatchRecorder()
        val f = function("function f(x) { return x + 'one' }")
        val g = function("function f(x) { return x * 2 }")
        val sameAsF = function("function f(x) { return x + 'two' }")
        val gen = function("function* f() { yield 1 }")
        val shared = JvmCompiler.sharedCount.get()
        val r = JvmCompiler.compileAll(listOf(f, g, sameAsF, gen), d)
        assertNotNull(r[0])
        assertNotNull(r[1])
        assertSame(r[0], r[2], "one class for both")
        assertNull(r[3], "generators are not compiled")
        assertEquals(listOf(2), d.batches)
        assertEquals(1, JvmCompiler.sharedCount.get() - shared)
    }

    @Test
    fun aClassTheDefinerRejectsFailsAlone() {
        val d = BatchRecorder(reject = "bad")
        val r = JvmCompiler.compileAll(listOf(function("function good(x) { return x }", "good"), function("function bad(x) { return -x }", "bad")), d)
        assertNotNull(r[0])
        assertNull(r[1])
        assertEquals(listOf(2), d.batches, "the batch failed, then the classes were defined one by one")
    }

    @Test
    fun adaptiveModeCompilesHotFunctionsInTheBackground() {
        assumeTrue(JitQueue.THREADS > 0)
        val d = ThreadRecorder()
        val engine = NeonEngine.builder().executionMode(ExecutionMode.ADAPTIVE).jitThreshold(10).codeDefiner(d).console(null).build()
        engine.newContext().use { ctx ->
            assertEquals(380, ctx.eval("function hot(x) { return x * 2 } let s = 0; for (let i = 0; i < 20; i++) s += hot(i); s").asInt())
            val cb = codeOf(ctx, "hot")
            waitFor("hot to be compiled") { cb.compiled != null }
            assertTrue(d.threadOf("hot")!!.startsWith("neonjs-jit-"), d.threadOf("hot"))
            assertEquals(84, ctx.eval("hot(42)").asInt())
        }
    }

    @Test
    fun withoutBackgroundCompilationTheCallerCompiles() {
        val d = ThreadRecorder()
        val engine = NeonEngine.builder().executionMode(ExecutionMode.ADAPTIVE).jitThreshold(10).backgroundCompilation(false)
            .codeDefiner(d).console(null).build()
        engine.newContext().use { ctx ->
            ctx.eval("function hot(x) { return x * 2 } for (let i = 0; i < 20; i++) hot(i)")
            assertNotNull(codeOf(ctx, "hot").compiled, "compiled at the 10th call")
            assertEquals(Thread.currentThread().name, d.threadOf("hot"))
        }
    }

    @Test
    fun compiledModeCompilesTheFunctionsAScriptDefinesAhead() {
        assumeTrue(JitQueue.THREADS > 0)
        val d = ThreadRecorder()
        val engine = NeonEngine.builder().executionMode(ExecutionMode.COMPILED).codeDefiner(d).console(null).build()
        engine.newContext().use { ctx ->
            assertEquals(1, ctx.eval("function used() { return 1 } function later() { return 2 } used()").asInt())
            val later = codeOf(ctx, "later")
            waitFor("later to be compiled") { later.compiled != null }
            assertTrue(d.threadOf("later")!!.startsWith("neonjs-jit-"), d.threadOf("later"))
            assertEquals(2, ctx.eval("later()").asInt())
            // code made by the Function constructor is queued when it is created
            val g = codeOf(ctx, "new Function('a', 'return a * 3')")
            waitFor("the new function to be compiled") { g.compiled != null }
        }
    }

    @Test
    fun closingAContextWithdrawsItsQueuedBlocks() {
        assumeTrue(JitQueue.THREADS > 0)
        val gate = CountDownLatch(1)
        val entered = Semaphore(0)
        val backend = ClassBackend(ThreadRecorder(gate, entered))
        val owner = Agent()
        try {
            // keep every worker busy with a block whose definition waits at the gate
            val busy = (0 until JitQueue.THREADS).map { function("function f(x) { return x" + " + x".repeat(it + 1) + " }") }
            for (cb in busy) {
                assertTrue(JitQueue.submit(cb, backend, owner))
                assertTrue(entered.tryAcquire(10, TimeUnit.SECONDS), "a worker took the block")
            }
            val queued = function("function f(x) { return x - x }")
            assertTrue(JitQueue.submit(queued, backend, owner))
            assertNotNull(queued.jitTask)
            JitQueue.cancel(owner)
            assertNull(queued.jitTask, "withdrawn")
            gate.countDown()
            for (cb in busy) waitFor("the busy blocks") { cb.compiled != null }
            Thread.sleep(50)
            assertNull(queued.compiled, "never compiled")
        } finally {
            gate.countDown()
        }
    }
}
