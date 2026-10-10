package dev.mooner.neonjs.test262

import dev.mooner.neonjs.ExecutionMode
import dev.mooner.neonjs.HostAccess
import dev.mooner.neonjs.NeonEngine
import dev.mooner.neonjs.SandboxPolicy
import dev.mooner.neonjs.android.DexCodeDefiner
import dev.mooner.neonjs.android.DexConverter
import dev.mooner.neonjs.android.DexConverters
import dev.mooner.neonjs.jit.CodeDefiners
import dev.mooner.neonjs.jit.GeneratedClass
import dev.mooner.neonjs.jit.JitQueue
import dev.mooner.neonjs.jit.JvmCompiler
import java.io.File
import java.lang.reflect.Modifier
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

/** A Kotlin `fun interface` ([hostInterop]): its lambdas are functions in JS. */
fun interface CheckFun {
    fun apply(s: String): String
}

/** Keeps a JS function given as a [CheckFun] ([hostInterop]). */
class CheckKeeper {
    var fn: CheckFun? = null
    fun keep(f: CheckFun) {
        fn = f
    }
}

/** A Kotlin CharSequence: its Java names (`length()`, `charAt()`) are bridges ([hostInterop]). */
class CheckChars(private val s: String) : CharSequence {
    override val length get() = s.length
    override fun get(index: Int) = s[index]
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = CheckChars(s.substring(startIndex, endIndex))
    override fun toString() = s
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
    val access = HostAccess.builder(HostAccess.Level.ALL).allowLookup { it.startsWith("dev.mooner.neonjs.test262.") }.build()
    val engine = NeonEngine.builder().hostAccess(access).console(null).sandbox(SandboxPolicy.builder().exposeJavaGlobal(true).build()).build()
    engine.newContext().use { ctx ->
        val r = ctx.eval("var S = Java.extend(Java.type('dev.mooner.neonjs.test262.CheckShape'), { area() { return 2.5 } }); new S().describe()").asString()
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

/** The public instance methods Java code outside its package can call on an instance of [cls] (as HostMemberSweepTest). */
private fun callableMethodNames(cls: Class<*>): Set<String> {
    val types = LinkedHashSet<Class<*>>()
    fun add(t: Class<*>?) {
        if (t == null || !types.add(t)) return
        add(t.superclass)
        t.interfaces.forEach { add(it) }
    }
    add(cls)
    val out = HashSet<String>()
    for (t in types) {
        if (!Modifier.isPublic(t.modifiers)) continue
        for (m in t.methods) {
            if (Modifier.isStatic(m.modifiers) || m.isSynthetic && !m.isBridge) continue
            if (m.declaringClass == Any::class.java && m.name in HostAccess.deniedMethods) continue
            out.add(m.name)
        }
    }
    return out
}

/**
 * Host objects on ART, whose class library and dex conversion differ from the JVM's: a StringBuilder is a Java object
 * with the methods it inherits from the package-private AbstractStringBuilder; null picks the same overload as on the
 * JVM; d8-desugared lambdas are functions while a list or a date is not; a JS function given to Java comes back as
 * itself; and every public method of a few classes of different shapes is a member.
 */
private fun hostInterop(): String {
    val access = HostAccess.builder(HostAccess.Level.ALL).allowLookup { true }.build()
    NeonEngine.builder().hostAccess(access).console(null).sandbox(SandboxPolicy.builder().exposeJavaGlobal(true).build()).build().newContext().use { ctx ->
        fun js(src: String) = ctx.eval(src, "<check>").asString()
        val sb = js("var SB = Java.type('java.lang.StringBuilder'); var sb = new SB(); var r = sb.append('a').append(1).append(null); " +
            "[typeof sb, r === sb, String(sb), sb.length(), (sb.setLength(1), String(sb)), sb.charAt(0)].join()")
        check(sb == "object,true,a1null,6,a,a") { "StringBuilder: $sb" }
        ctx["kl"] = { x: Int -> x + 1 }
        ctx["fl"] = CheckFun { "$it!" }
        ctx["ob"] = object : Runnable {
            override fun run() {}
        }
        ctx["al"] = arrayListOf(1)
        ctx["ld"] = java.time.LocalDate.of(2026, 1, 2)
        val types = js("[typeof kl, kl(1), typeof fl, fl('x'), typeof ob, typeof al, typeof ld].join()")
        check(types == "function,2,function,x!,function,object,object") { "functions: $types" }
        ctx["keeper"] = CheckKeeper()
        val round = js("var f = s => s + '?'; keeper.keep(f); [keeper.getFn() === f, keeper.getFn()('y')].join()")
        check(round == "true,y?") { "round trip: $round" }
        // longs beyond 2^53 - 1 are BigInts and come back exact; a BigInt in an Object parameter is a Long
        ctx["id"] = Long.MAX_VALUE
        ctx["m"] = HashMap<String, Any?>()
        val longs = js("m.put('id', id); m.put('small', 5n); [typeof id, typeof Java.type('java.lang.Long').MIN_VALUE, Java.type('java.lang.Long').valueOf(id) === id, typeof m.get('small')].join()")
        check(longs == "bigint,bigint,true,number") { "longs: $longs" }
        check(ctx.eval("m.get('id')").asLong() == Long.MAX_VALUE) { "long round trip" }
        // a method naming a class missing at run time is left out; ART resolves signatures method by method, so the
        // class's other methods stay
        val definer = CodeDefiners.default
        val base = definer.define("missing.CheckBase", missingClassBytes("missing/CheckBase", "java/lang/Object", "ok", null), AndroidCheck::class.java.classLoader!!)
        val sub = definer.define("missing.CheckSub", missingClassBytes("missing/CheckSub", "missing/CheckBase", "fine", "bad"), base.classLoader)
        ctx["x"] = sub.getConstructor().newInstance()
        val partial = js("[x.ok(), x.fine(), typeof x.bad].join()")
        check(partial == "ok,fine,undefined") { "missing class: $partial" }
        val missing = ArrayList<String>()
        var methods = 0
        for ((name, v) in listOf<Pair<String, Any>>("StringBuilder" to StringBuilder("ab"), "listOf" to listOf(1, 2),
            "CharBuffer.wrap" to java.nio.CharBuffer.wrap("ab"), "ConcurrentHashMap.keySet" to java.util.concurrent.ConcurrentHashMap<String, Int>().keySet(0),
            "Kotlin CharSequence" to CheckChars("ab"))) {
            ctx["x"] = v
            for (n in callableMethodNames(v.javaClass)) {
                methods++
                ctx["n"] = n
                if (js("typeof x[n]") != "function") missing.add("$name.$n")
            }
        }
        check(missing.isEmpty()) { "not members: $missing" }
        return "StringBuilder, functions, round trip, longs, missing classes; $methods methods of 5 classes are members"
    }
}

/**
 * A public class [name] extending [superName] with a no-argument constructor, a method [ok] returning its name and,
 * if [bad] is given, a method [bad] taking a `missing.Absent`, a class that does not exist.
 */
private fun missingClassBytes(name: String, superName: String, ok: String, bad: String?): ByteArray {
    val cw = org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS)
    cw.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC or org.objectweb.asm.Opcodes.ACC_SUPER, name, null, superName, null)
    val init = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
    init.visitCode()
    init.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0)
    init.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false)
    init.visitInsn(org.objectweb.asm.Opcodes.RETURN)
    init.visitMaxs(0, 0)
    init.visitEnd()
    for ((m, desc) in listOfNotNull(ok to "()Ljava/lang/String;", bad?.let { it to "(Lmissing/Absent;)Ljava/lang/String;" })) {
        val mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, m, desc, null, null)
        mv.visitCode()
        mv.visitLdcInsn(m)
        mv.visitInsn(org.objectweb.asm.Opcodes.ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
    }
    cw.visitEnd()
    return cw.toByteArray()
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
        "host interop" to ::hostInterop,
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
