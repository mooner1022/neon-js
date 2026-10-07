package io.neonjs.test262

import io.neonjs.ExecutionMode
import io.neonjs.HostAccess
import io.neonjs.NeonEngine
import io.neonjs.SandboxPolicy
import io.neonjs.android.DexCodeDefiner
import io.neonjs.android.DexConverter
import io.neonjs.android.DexConverters
import io.neonjs.jit.CodeDefiners
import io.neonjs.jit.GeneratedClass
import io.neonjs.jit.JitQueue
import io.neonjs.jit.JvmCompiler
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

/** Extended from JS in [javaExtendThroughDex]. */
abstract class CheckShape {
    abstract fun area(): Double
    fun describe() = "area=" + area()
}

/** Calls a default method of an interface a JS object implements ([defaultInterfaceMethods]). */
class CheckHost {
    fun reversedCompare(c: Comparator<Any?>): Int = c.reversed().compare(1, 2)
}

private class CountingConverter(private val inner: DexConverter) : DexConverter {
    val calls = AtomicInteger()
    override val id: String get() = inner.id
    override val classFileVersion: Int get() = inner.classFileVersion
    override fun toDex(classes: List<GeneratedClass>, minSdk: Int): ByteArray {
        calls.incrementAndGet()
        return inner.toDex(classes, minSdk)
    }
}

private val apiLevel: Int = try {
    Class.forName($$"android.os.Build$VERSION").getField("SDK_INT").getInt(null)
} catch (_: Throwable) {
    0
}

private fun check(cond: Boolean, message: () -> String) {
    if (!cond) throw AssertionError(message())
}

private fun waitFor(what: String, cond: () -> Boolean) {
    val end = System.nanoTime() + 60_000_000_000L
    while (!cond()) {
        check(System.nanoTime() < end) { "timed out waiting for $what" }
        Thread.sleep(10)
    }
}

private fun definerIsDex(): String {
    val d = CodeDefiners.default
    check(d is DexCodeDefiner) { "default definer is ${d.javaClass.name}" }
    return "${d.javaClass.simpleName} with ${(d as DexCodeDefiner).converter.id}, class files up to ${d.classFileVersion}"
}

private fun compiledCode(): String {
    val before = JvmCompiler.compiledCount.get()
    val engine = NeonEngine.builder().executionMode(ExecutionMode.COMPILED).console(null).build()
    engine.newContext().use { ctx ->
        val r = ctx.eval("""
            function fib(n) { return n < 2 ? n : fib(n - 1) + fib(n - 2) }
            class P { #x; constructor(x) { this.#x = x } get x() { return this.#x } }
            function tryIt(f) { try { return f() } catch (e) { return e.name } finally { } }
            const ta = new Float64Array(4).map((_, i) => i * 1.5), dv = new DataView(new ArrayBuffer(8));
            dv.setInt32(0, -123456, true); dv.setFloat32(4, 2.5);
            [fib(20), new P(4).x, tryIt(() => null.x), ta.join(' '), dv.getInt32(0, true), dv.getFloat32(4),
             [3, 1, 2].sort((a, b) => a - b).join(''), JSON.stringify({ a: [1, { b: 2 }] })].join()
        """).asString()
        check(r == "6765,4,TypeError,0 1.5 3 4.5,-123456,2.5,123,{\"a\":[1,{\"b\":2}]}") { "got $r" }
    }
    val n = JvmCompiler.compiledCount.get() - before
    check(n >= 5) { "only $n classes compiled" }
    return "$n classes"
}

private fun backgroundBatches(): String {
    val compiled = JvmCompiler.compiledCount.get()
    val batches = JitQueue.batches.get()
    val blocks = JitQueue.batchedBlocks.get()
    val engine = NeonEngine.builder().executionMode(ExecutionMode.ADAPTIVE).jitThreshold(5).console(null).build()
    engine.newContext().use { ctx ->
        // 40 functions with distinct code, hot at about the same time
        val r = ctx.eval("""
            const fs = [];
            for (let i = 0; i < 40; i++) fs.push(new Function('x', 'return (x * ' + (i + 3) + ' + ' + (i * 7 + 1) + ') | 0'));
            let acc = 0;
            for (let round = 0; round < 10; round++) for (const f of fs) acc = (acc + f(round)) | 0;
            acc
        """).asInt()
        var expect = 0
        for (round in 0 until 10) for (i in 0 until 40) expect += round * (i + 3) + (i * 7 + 1)
        check(r == expect) { "got $r, expected $expect" }
        waitFor("40 functions compiled in the background") { JvmCompiler.compiledCount.get() - compiled >= 40 }
        check(ctx.eval("fs[39](2)").asInt() == 2 * 42 + 274) { "compiled code gives a wrong result" }
    }
    return "${JitQueue.batchedBlocks.get() - blocks} blocks in ${JitQueue.batches.get() - batches} batches"
}

private fun javaExtendThroughDex(): String {
    val access = HostAccess.builder(HostAccess.Level.ALL).allowLookup { it.startsWith("io.neonjs.test262.") }.build()
    val engine = NeonEngine.builder().hostAccess(access).console(null).sandbox(SandboxPolicy.builder().exposeJavaGlobal(true).build()).build()
    engine.newContext().use { ctx ->
        val r = ctx.eval("var S = Java.extend(Java.type('io.neonjs.test262.CheckShape'), { area() { return 2.5 } }); new S().describe()").asString()
        check(r == "area=2.5") { "got $r" }
    }
    return "adapter defined and called"
}

private fun defaultInterfaceMethods(): String {
    val engine = NeonEngine.builder().hostAccess(HostAccess.builder(HostAccess.Level.ALL).build()).console(null).build()
    engine.newContext().use { ctx ->
        ctx["host"] = CheckHost()
        // Comparator.reversed() is a default method: the proxy runs its implementation, which calls compare in JS
        val r = ctx.eval("host.reversedCompare({ compare(a, b) { return a - b } })").asInt()
        check(r == 1) { "got $r" }
    }
    return "default method of a JS-implemented interface ran"
}

private fun cacheDirectory(dir: File): String {
    dir.deleteRecursively()
    val src = "function sq(x) { return x * x } function cube(x) { return x * x * x } [sq(7), cube(3)].join()"
    val first = CountingConverter(DexConverters.default)
    val e1 = NeonEngine.builder().executionMode(ExecutionMode.COMPILED).backgroundCompilation(false)
        .codeDefiner(DexCodeDefiner(dir, 26, first)).console(null).build()
    e1.newContext().use { check(it.eval(src).asString() == "49,27") { "first run" } }
    check(first.calls.get() > 0) { "nothing converted" }
    val dexFiles = dir.listFiles { f -> f.name.endsWith(".dex") }!!
    check(dexFiles.isNotEmpty() && dexFiles.none { it.canWrite() }) { "dex files missing or writable: ${dexFiles.map { "${it.name} ${it.canWrite()}" }}" }
    // a new definer over the same directory, as after a restart
    val second = CountingConverter(DexConverters.default)
    val e2 = NeonEngine.builder().executionMode(ExecutionMode.COMPILED).backgroundCompilation(false)
        .codeDefiner(DexCodeDefiner(dir, 26, second)).console(null).build()
    e2.newContext().use { check(it.eval(src).asString() == "49,27") { "second run" } }
    check(second.calls.get() == 0) { "${second.calls.get()} conversions on the second run" }
    return "${first.calls.get()} conversions, then 0 from ${dexFiles.size} cached dex files"
}

/**
 * Checks of the Android-specific code paths, for a device or emulator (`tools/android/device.py run`): the dex definer
 * is selected, compiled code runs, background compilation batches, `Java.extend` adapters are defined through dex,
 * default methods of host interfaces behave as documented for the API level, and a cache directory serves a second
 * definer. Argument: a writable directory for the cache check. Exits with status 1 if a check fails.
 */
fun androidCheck(args: Array<String>) {
    val cacheDir = File(args.getOrNull(0) ?: "/data/local/tmp/neonjs-check-cache")
    println("AndroidCheck on API level $apiLevel (${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")})")
    val checks = listOf<Pair<String, () -> String>>(
        "definer" to ::definerIsDex,
        "compiled code" to ::compiledCode,
        "background batches" to ::backgroundBatches,
        "Java.extend" to ::javaExtendThroughDex,
        "default interface methods" to ::defaultInterfaceMethods,
        "cache directory" to { cacheDirectory(cacheDir) },
    )
    var failed = 0
    for ((name, run) in checks) {
        try {
            println("PASS $name: ${run()}")
        } catch (t: Throwable) {
            failed++
            println("FAIL $name: $t")
            t.printStackTrace(System.out)
        }
    }
    println("AndroidCheck: ${checks.size - failed} passed, $failed failed")
    System.out.flush()
    exitProcess(if (failed == 0) 0 else 1)
}

object AndroidCheck {
    @JvmStatic
    fun main(args: Array<String>) = androidCheck(args)
}
