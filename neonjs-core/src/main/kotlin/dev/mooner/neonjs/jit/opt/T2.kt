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

    /**
     * The most bytes of JVM code the tier's `run` may have (`-Dneonjs.jit.t2MaxCode`). HotSpot does not compile a
     * method of more than 8,000 (`-XX:+DontCompileHugeMethods`, the default), and ART not one of more than 10,000 dex
     * code units, which such a method stays under once translated (dx optimizes large classes, D8 all). Code past
     * that would run in the JVM's bytecode interpreter, far slower than the baseline's compiled code: the block keeps
     * its baseline.
     */
    @JvmField val MAX_CODE: Int = Integer.getInteger("neonjs.jit.t2MaxCode", 8000)

    private val counter = AtomicLong()
    private val debugShown = AtomicLong()
    /** Blocks compiled, and those that could not be, by reason; code that deoptimized too often (T2Code.exit). */
    @JvmField val compiledCount = AtomicLong()
    @JvmField val reverted = AtomicLong()
    /** Calls compiled inlined (IrBuilder). */
    @JvmField val inlinedCalls = AtomicLong()
    @JvmField val failedCount = AtomicLong()
    @JvmField val failureReasons = ConcurrentHashMap<String, AtomicLong>()
    /** Baselines this tier compiled ([compileBaselines]), and those it could not, by reason. */
    @JvmField val baselines = AtomicLong()
    @JvmField val baselinesFailed = AtomicLong()
    /** Baselines whose class another block's code had defined already. */
    @JvmField val baselinesShared = AtomicLong()
    @JvmField val baselineReasons = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Testing: with `-Dneonjs.jit.irBaseline`, the baseline of a JavaScript block is this tier's ([compileBaselines]),
     * the baseline compiler's (T1) only where this tier cannot compile the block: what a language without a compiler of
     * its own runs (docs/IR_NEUTRALITY.md §5).
     */
    @JvmField val IR_BASELINE: Boolean = System.getProperty("neonjs.jit.irBaseline").let { it != null && it != "false" }

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
            val (g, c) = generate(cb, definer, baseline = false)
            val cls = definer.define(c.name, c.bytes, JvmCompiler::class.java.classLoader)
            val code = instantiate(cls, g, cb, definer)
            compiledCount.incrementAndGet()
            return code
        } catch (e: Throwable) {
            failedCount.incrementAndGet()
            failureReasons.computeIfAbsent(reason(e)) { AtomicLong() }.incrementAndGet()
            return null
        }
    }

    /**
     * Baselines (docs/IR_NEUTRALITY.md §5): the code of each of [blocks] made by this tier without speculations or exits
     * (but the stress mode's), the code a block runs before the tier's speculating code and goes back to when that
     * deoptimizes. Defined by [definer] in one batch, as the baseline compiler's are. Element i is `blocks[i]`'s code,
     * the baseline installed already, or null (counted, with the reason) where the tier cannot compile it. Never throws.
     */
    fun compileBaselines(blocks: List<CodeBlock>, definer: CodeDefiner): List<CompiledCode?> {
        val out = arrayOfNulls<CompiledCode>(blocks.size)
        val made = ArrayList<Int>()
        val graphs = ArrayList<Graph>()
        val classes = ArrayList<dev.mooner.neonjs.jit.GeneratedClass>()
        for ((i, cb) in blocks.withIndex()) {
            (cb.baseline as CompiledCode?)?.let { out[i] = it; continue }
            try {
                val (g, c) = generate(cb, definer, baseline = true)
                made.add(i)
                graphs.add(g)
                classes.add(c)
            } catch (e: Throwable) {
                baselineFailed(e)
            }
        }
        if (classes.isEmpty()) return out.asList()
        val loader = JvmCompiler::class.java.classLoader
        val known = baselineClasses(definer)
        // the classes not defined yet, each once
        val fresh = LinkedHashMap<String, dev.mooner.neonjs.jit.GeneratedClass>()
        for (c in classes) if (known[c.name]?.get() == null) fresh.putIfAbsent(c.name, c)
        val news = fresh.values.toList()
        // one batch; class by class where the definer rejects it, so that one bad class fails alone
        val defined: List<Class<*>?> = if (news.isEmpty()) emptyList() else try {
            definer.defineAll(news, loader)
        } catch (e: Throwable) {
            news.map { c -> try { definer.define(c.name, c.bytes, loader) } catch (e2: Throwable) { null } }
        }
        for ((k, cls) in defined.withIndex()) if (cls != null) known[news[k].name] = java.lang.ref.WeakReference(cls)
        // (a class's first block in the batch that defined it is not a share)
        val first = HashSet<String>()
        for ((k, c) in classes.withIndex()) {
            val cls = known[c.name]?.get()
            val i = made[k]
            if (cls == null) {
                baselineFailed(JitBailout("class not defined"))
                continue
            }
            if (c.name !in fresh || !first.add(c.name)) baselinesShared.incrementAndGet()
            try {
                out[i] = instantiate(cls, graphs[k], blocks[i], definer)
                baselines.incrementAndGet()
            } catch (e: Throwable) {
                baselineFailed(e)
            }
        }
        return out.asList()
    }

    private fun baselineFailed(e: Throwable) {
        baselinesFailed.incrementAndGet()
        baselineReasons.computeIfAbsent(reason(e)) { AtomicLong() }.incrementAndGet()
    }

    /** [cb]'s graph and class, within the JVM's size limits; a JitBailout (or another exception) where it has none. */
    private fun generate(cb: CodeBlock, definer: CodeDefiner, baseline: Boolean): Pair<Graph, dev.mooner.neonjs.jit.GeneratedClass> {
        val name = if (baseline) BASELINE_NAME else "dev/mooner/neonjs/jit/T2$" + JvmCompiler.sanitize(cb.name) + "$" + counter.incrementAndGet()
        var g = build(cb, unswitch = true, baseline = baseline)
        val bytes = try {
            fun generate() = IrCodegen.generate(g, name, definer.classFileVersion)
            var bytes = try {
                generate()
            } catch (e: org.objectweb.asm.MethodTooLargeException) {
                if (!g.unswitched) throw e
                null
            }
            // loops copied made the method too large: again without copies
            if (g.unswitched && (bytes == null || IrCodegen.codeLength(bytes, "run") > MAX_CODE)) {
                g = build(cb, unswitch = false, baseline = baseline)
                bytes = generate()
            }
            val size = IrCodegen.codeLength(bytes!!, "run")
            if (size > MAX_CODE) throw JitBailout("code of $size bytes: past what the JVM compiles")
            bytes
        } catch (e: JitBailout) {
            if (System.getProperty("neonjs.jit.t2Debug") != null && e.message!!.contains("no local") && debugShown.incrementAndGet() <= 2) {
                System.err.println("T2DEBUG ${cb.name}: ${e.message}\n${cb.sourceText()?.take(800)}\n${IrPrinter.print(g)}")
            }
            throw e
        }
        val (final, out) = if (baseline) named(bytes) else name to bytes
        JvmCompiler.DUMP_DIR?.let { dir -> runCatching { java.io.File(dir, final.substringAfterLast('/') + ".class").writeBytes(out) } }
        return g to dev.mooner.neonjs.jit.GeneratedClass(final.replace('/', '.'), out)
    }

    /**
     * A baseline's class holds nothing of its block's (the block's objects are its instance's data, its constants are
     * read from the frame): blocks whose code is the same share one class. Generated as [BASELINE_NAME], the class is
     * named after a hash of its bytes, written over the placeholder's 16 digits in them (the name's every occurrence:
     * the same length, so the class file stays valid).
     */
    private fun named(bytes: ByteArray): Pair<String, ByteArray> {
        val hash = java.security.MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)
        val final = BASELINE_NAME.replace(BASELINE_HASH, hash)
        val from = BASELINE_NAME.toByteArray()
        val to = final.toByteArray()
        val out = bytes.copyOf()
        var k = 0
        while (k <= out.size - from.size) {
            var same = true
            for (j in from.indices) if (out[k + j] != from[j]) { same = false; break }
            if (same) {
                System.arraycopy(to, 0, out, k, to.size)
                k += from.size
            } else k++
        }
        return final to out
    }

    private const val BASELINE_HASH = "0000000000000000"
    private const val BASELINE_NAME = "dev/mooner/neonjs/jit/T2\$base\$" + BASELINE_HASH

    /** Baseline classes by name (a hash of their code), per definer, weakly: a class goes when no block's code uses it. */
    private val baselineClasses = java.util.WeakHashMap<CodeDefiner, ConcurrentHashMap<String, java.lang.ref.WeakReference<Class<*>>>>()

    private fun baselineClasses(definer: CodeDefiner) = synchronized(baselineClasses) { baselineClasses.getOrPut(definer) { ConcurrentHashMap() } }

    private fun instantiate(cls: Class<*>, g: Graph, cb: CodeBlock, definer: CodeDefiner): T2Code {
        val code = cls.getDeclaredConstructor(Array<Any?>::class.java, CodeBlock::class.java).newInstance(g.data.toTypedArray(), cb) as T2Code
        code.osrPcs = g.osrPcs
        code.resumer = g.dialect.resumer
        code.definer = definer
        return code
    }

    private fun reason(e: Throwable): String = when (e) {
        is JitBailout -> (e.message ?: "bailout").replace(Regex("\\d+"), "N")
        is org.objectweb.asm.MethodTooLargeException -> "method too large"
        else -> {
            if (System.getProperty("neonjs.jit.debug") != null) e.printStackTrace()
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }.take(120)

    private fun build(cb: CodeBlock, unswitch: Boolean, baseline: Boolean): Graph {
        val g = Dialects.of(cb).build(cb, stressExits = JvmCompiler.DEOPT_STRESS > 0, unswitch = unswitch, baseline = baseline)
        if (PRINT != null && cb.name.contains(PRINT)) System.err.println("T2 ${cb.name}:\n${IrPrinter.print(g)}")
        if (VERIFY) IrVerifier.verify(g)
        return g
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
