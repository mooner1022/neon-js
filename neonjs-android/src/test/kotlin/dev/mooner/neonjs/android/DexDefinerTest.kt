package dev.mooner.neonjs.android

import dev.mooner.neonjs.ExecutionMode
import dev.mooner.neonjs.HostAccess
import dev.mooner.neonjs.NeonEngine
import dev.mooner.neonjs.SandboxPolicy
import dev.mooner.neonjs.jit.CodeDefiners
import dev.mooner.neonjs.jit.IsolatedCodeDefiner
import dev.mooner.neonjs.jit.JvmCodeDefiner
import dev.mooner.neonjs.jit.JvmCompiler
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

abstract class Shape {
    abstract fun area(): Double
    fun describe() = "area=" + area()
}

/** Runs on a standard JVM: checks the parts of the Android code path that do not need a device. */
class DexDefinerTest {
    @Test
    fun dexDefinerIsNotSelectedOnTheJvm() {
        assertFalse(DexCodeDefiner().isSupported(), "no dalvik.system class loaders here")
        assertTrue(CodeDefiners.default is JvmCodeDefiner, "the ServiceLoader registration is skipped when unsupported")
    }

    @Test
    fun dxIsTheDefaultConverter() {
        assertSame(DxConverter, DexConverters.default, "no other converter on the class path")
        assertEquals(52, DexCodeDefiner().classFileVersion)
        assertTrue(DexConverters.isDex(DxConverter.toDex(JvmCompiler.PROBE_CLASS, JvmCompiler.probeClass(52), 26)))
    }

    @Test
    fun compiledCodeTranslatesToDex() {
        val before = DexCheckingDefiner.converted.get()
        val engine = NeonEngine.builder().executionMode(ExecutionMode.COMPILED).codeDefiner(DexCheckingDefiner()).console(null).build()
        engine.newContext().use { ctx ->
            val r = ctx.eval("""
                function fib(n) { return n < 2 ? n : fib(n - 1) + fib(n - 2) }
                class P { #x; constructor(x) { this.#x = x } get x() { return this.#x } static of(x) { return new P(x) } }
                function tryIt(f) { try { return f() } catch (e) { return e.name } finally { } }
                const sum = [1, 2, 3].map(x => x * 2).reduce((a, b) => a + b, 0);
                const o = { a: 1, b: 'two' };
                let s = 0; for (const [k, v] of Object.entries(o)) s += k.length;
                [fib(15), P.of(4).x, tryIt(() => null.x), sum, s, JSON.stringify(o), `t${1 + 1}`].join()
            """).asString()
            assertEquals("610,4,TypeError,12,2,{\"a\":1,\"b\":\"two\"},t2", r)
        }
        assertTrue(DexCheckingDefiner.converted.get() - before >= 5, "the functions were compiled and converted")
    }

    @Test
    fun javaExtendAdaptersTranslateToDex() {
        val before = DexCheckingDefiner.converted.get()
        val access = HostAccess.builder(HostAccess.Level.ALL).allowLookup { it.startsWith("dev.mooner.neonjs.android.") }.build()
        val engine = NeonEngine.builder().hostAccess(access).codeDefiner(DexCheckingDefiner()).console(null)
            .sandbox(SandboxPolicy.builder().exposeJavaGlobal(true).build()).build()
        engine.newContext().use { ctx ->
            val r = ctx.eval("var S = Java.extend(Java.type('dev.mooner.neonjs.android.Shape'), { area() { return 2.5 } }); new S().describe()")
            assertEquals("area=2.5", r.asString())
        }
        assertTrue(DexCheckingDefiner.converted.get() > before)
    }

    @Test
    fun isolatedDefinerRunsGeneratedCodeOutsideTheEnginePackage() {
        val engine = NeonEngine.builder().executionMode(ExecutionMode.COMPILED).codeDefiner(IsolatedCodeDefiner()).console(null).build()
        engine.newContext().use { ctx ->
            assertEquals(6765, ctx.eval("function fib(n) { return n < 2 ? n : fib(n - 1) + fib(n - 2) } fib(20)").asInt())
        }
    }

    @Test
    fun unusableDefinerFallsBackToTheInterpreter() {
        val broken = object : dev.mooner.neonjs.jit.CodeDefiner {
            var calls = 0
            override fun define(name: String, bytes: ByteArray, parent: ClassLoader): Class<*> {
                calls++
                throw UnsupportedOperationException("no class definition here")
            }
        }
        val engine = NeonEngine.builder().executionMode(ExecutionMode.COMPILED).codeDefiner(broken).console(null).build()
        engine.newContext().use { ctx ->
            assertEquals(6765, ctx.eval("function fib(n) { return n < 2 ? n : fib(n - 1) + fib(n - 2) } fib(20)").asInt())
        }
        assertEquals(1, broken.calls, "only the probe class is attempted, no per-function code generation")
    }
}
