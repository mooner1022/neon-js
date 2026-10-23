package dev.mooner.neonjs.jit.opt

import dev.mooner.neonjs.compiler.CodeBlock
import dev.mooner.neonjs.jit.CodeDefiner
import dev.mooner.neonjs.jit.CompiledCode
import dev.mooner.neonjs.jit.Jit
import dev.mooner.neonjs.jit.JitBailout
import dev.mooner.neonjs.jit.JvmCompiler
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The optimizing tier's compiler (docs/OPTIMIZING_TIER.md): a code block's [Graph], then its class, defined by the
 * same [CodeDefiner] as the baseline's. One class per compiled block: its code is the block's own, never shared.
 */
object T2 {
    /**
     * Testing: with `-Dneonjs.jit.t2Stress`, every block whose baseline is installed is compiled by this tier right
     * after, when it can be, and its code installed over the baseline (Jit.install).
     */
    @JvmField val STRESS: Boolean = System.getProperty("neonjs.jit.t2Stress").let { it != null && it != "false" }

    /**
     * Whether the code speculates on the interpreter's value profiles (numbers) and on monomorphic property caches
     * (shapes); `-Dneonjs.jit.t2Speculate=false` turns that off.
     */
    @JvmField val SPECULATE: Boolean = System.getProperty("neonjs.jit.t2Speculate") != "false"

    /** Debugging: `-Dneonjs.jit.t2Print=name` prints the IR of every block compiled whose name contains `name`. */
    private val PRINT: String? = System.getProperty("neonjs.jit.t2Print")
    /** Testing: `-Dneonjs.jit.t2Verify` checks every graph (IrVerifier) before its code is generated. */
    private val VERIFY = System.getProperty("neonjs.jit.t2Verify") != null

    private val counter = AtomicLong()
    private val debugShown = AtomicLong()
    /** Blocks compiled, and those that could not be, by reason; code that deoptimized too often (T2Code.exit). */
    @JvmField val compiledCount = AtomicLong()
    @JvmField val reverted = AtomicLong()
    /** Calls compiled inlined (IrBuilder). */
    @JvmField val inlinedCalls = AtomicLong()
    @JvmField val failedCount = AtomicLong()
    @JvmField val failureReasons = ConcurrentHashMap<String, AtomicLong>()

    init {
        // Debugging: `-Dneonjs.jit.t2Stats` prints the counters when the process exits
        if (System.getProperty("neonjs.jit.t2Stats") != null) Runtime.getRuntime().addShutdownHook(Thread {
            System.err.println("T2 compiled=$compiledCount not compiled=$failedCount reverted=$reverted calls inlined=$inlinedCalls" +
                " exits=${dev.mooner.neonjs.jit.Deopt.count} inlined exits=${dev.mooner.neonjs.jit.Deopt.inlinedCount}" +
                " tier-ups=${dev.mooner.neonjs.jit.Deopt.tierUps} reasons=$failureReasons")
        })
    }

    /** [cb]'s code, compiled by this tier and defined by [definer], or null (counted, with the reason) when it cannot be. */
    fun compile(cb: CodeBlock, definer: CodeDefiner): CompiledCode? {
        try {
            if (cb.t2Off) throw JitBailout("deoptimized too often")
            val g = IrBuilder(cb, JvmCompiler.INT32, JvmCompiler.ELEMS, stressExits = JvmCompiler.DEOPT_STRESS > 0).build()
            if (VERIFY) IrVerifier.verify(g)
            if (PRINT != null && cb.name.contains(PRINT)) System.err.println("T2 ${cb.name}:\n${IrPrinter.print(g)}")
            val name = "dev/mooner/neonjs/jit/T2$" + JvmCompiler.sanitize(cb.name) + "$" + counter.incrementAndGet()
            val bytes = try {
                IrCodegen.generate(g, name, definer.classFileVersion, JvmCompiler.INT32, JvmCompiler.ELEMS)
            } catch (e: JitBailout) {
                if (System.getProperty("neonjs.jit.t2Debug") != null && e.message!!.contains("no local") && debugShown.incrementAndGet() <= 2) {
                    System.err.println("T2DEBUG ${cb.name}: ${e.message}\n${cb.sourceText()?.take(800)}\n${IrPrinter.print(g)}")
                }
                throw e
            }
            JvmCompiler.DUMP_DIR?.let { dir -> runCatching { java.io.File(dir, name.substringAfterLast('/') + ".class").writeBytes(bytes) } }
            val cls = definer.define(name.replace('/', '.'), bytes, JvmCompiler::class.java.classLoader)
            val code = cls.getDeclaredConstructor(Array<Any?>::class.java, CodeBlock::class.java).newInstance(g.data.toTypedArray(), cb) as T2Code
            code.osrPcs = g.osrPcs
            code.definer = definer
            compiledCount.incrementAndGet()
            return code
        } catch (e: Throwable) {
            val reason = when (e) {
                is JitBailout -> (e.message ?: "bailout").replace(Regex("\\d+"), "N")
                is org.objectweb.asm.MethodTooLargeException -> "method too large"
                else -> {
                    if (System.getProperty("neonjs.jit.debug") != null) e.printStackTrace()
                    "${e.javaClass.simpleName}: ${e.message}"
                }
            }
            failedCount.incrementAndGet()
            failureReasons.computeIfAbsent(reason.take(120)) { AtomicLong() }.incrementAndGet()
            return null
        }
    }

    /** Blocks compiled by [submit]'s thread, one at a time, in order; past 4096 waiting, more are dropped. */
    private val queue = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 30, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue(4096),
        { r -> Thread(null, r, "neonjs-t2", 16L * 1024 * 1024).also { it.isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.DiscardPolicy(),
    ).also { it.allowCoreThreadTimeOut(true) }

    /**
     * Compiles [cb] on the tier's background thread (on the calling thread without [background]) and installs the code
     * over its baseline, which runs meanwhile.
     */
    fun submit(cb: CodeBlock, definer: CodeDefiner, background: Boolean = true) {
        if (cb.t2Off || !JvmCompiler.canCompile(cb)) return
        if (background) queue.execute { compileOver(cb, definer) } else compileOver(cb, definer)
    }

    /** Compiles [cb] with [definer] and installs the code over its baseline (the stress mode). */
    fun compileOver(cb: CodeBlock, definer: CodeDefiner) {
        val code = compile(cb, definer) ?: return
        Jit.install(cb, code)
    }
}
