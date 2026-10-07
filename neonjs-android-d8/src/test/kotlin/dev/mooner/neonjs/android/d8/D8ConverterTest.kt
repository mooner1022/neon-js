package dev.mooner.neonjs.android.d8

import dev.mooner.neonjs.ExecutionMode
import dev.mooner.neonjs.HostAccess
import dev.mooner.neonjs.NeonEngine
import dev.mooner.neonjs.SandboxPolicy
import dev.mooner.neonjs.android.DexCheckingDefiner
import dev.mooner.neonjs.android.DexCodeDefiner
import dev.mooner.neonjs.android.DexConverters
import dev.mooner.neonjs.jit.JvmCompiler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

abstract class Greeter {
    abstract fun name(): String
    fun greet() = "hello " + name()
}

/** Runs on a standard JVM: D8 must accept every kind of class the engine generates, as dx does. */
class D8ConverterTest {
    @Test
    fun selectedThroughServiceLoader() {
        assertTrue(DexConverters.default is D8Converter, "neonjs-android-d8 on the class path selects D8")
        assertEquals(61, DexCodeDefiner().classFileVersion)
    }

    @Test
    fun compiledCodeTranslates() {
        val before = DexCheckingDefiner.converted.get()
        val engine = NeonEngine.builder().executionMode(ExecutionMode.COMPILED).codeDefiner(DexCheckingDefiner(D8Converter())).console(null).build()
        engine.newContext().use { ctx ->
            val r = ctx.eval("""
                function fib(n) { return n < 2 ? n : fib(n - 1) + fib(n - 2) }
                class P { #x; constructor(x) { this.#x = x } get x() { return this.#x } static of(x) { return new P(x) } }
                function tryIt(f) { try { return f() } catch (e) { return e.name } finally { } }
                function* gen() { yield 1; yield 2 }
                async function later() { return 5 }
                const sum = [1, 2, 3].map(x => x * 2).reduce((a, b) => a + b, 0);
                const o = { a: 1, b: 'two' };
                let s = 0; for (const [k, v] of Object.entries(o)) s += k.length;
                [fib(15), P.of(4).x, tryIt(() => null.x), sum, s, [...gen()].join('+'), typeof later(), `t${1 + 1}`].join()
            """).asString()
            assertEquals("610,4,TypeError,12,2,1+2,object,t2", r)
        }
        assertTrue(DexCheckingDefiner.converted.get() - before >= 6, "the functions were compiled and converted")
    }

    @Test
    fun javaExtendAdaptersTranslate() {
        val before = DexCheckingDefiner.converted.get()
        val access = HostAccess.builder(HostAccess.Level.ALL).allowLookup { it.startsWith("dev.mooner.neonjs.android.d8.") }.build()
        val engine = NeonEngine.builder().hostAccess(access).codeDefiner(DexCheckingDefiner(D8Converter())).console(null)
            .sandbox(SandboxPolicy.builder().exposeJavaGlobal(true).build()).build()
        engine.newContext().use { ctx ->
            val r = ctx.eval("var G = Java.extend(Java.type('dev.mooner.neonjs.android.d8.Greeter'), { name() { return 'd8' } }); new G().greet()")
            assertEquals("hello d8", r.asString())
        }
        assertTrue(DexCheckingDefiner.converted.get() > before)
    }

    @Test
    fun concurrentConversions() {
        val d8 = D8Converter()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = pool.invokeAll((1..64).map { Callable { d8.toDex(JvmCompiler.PROBE_CLASS, JvmCompiler.probeClass(61), 26) } })
            val first = results[0].get()
            for (f in results) {
                val dex = f.get()
                assertTrue(DexConverters.isDex(dex))
                assertArrayEquals(first, dex, "D8 output is deterministic")
            }
        } finally {
            pool.shutdown()
        }
    }

    @Test
    fun malformedClassFileFails() {
        val e = assertThrows(IllegalStateException::class.java) { D8Converter().toDex("x.Y", byteArrayOf(1, 2, 3, 4), 26) }
        assertTrue(e.message!!.startsWith("D8 cannot translate x.Y"), e.message)
    }
}
