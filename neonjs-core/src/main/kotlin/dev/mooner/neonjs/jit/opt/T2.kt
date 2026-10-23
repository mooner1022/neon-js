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

    private val counter = AtomicLong()
    /** Blocks compiled, and those that could not be, by reason. */
    @JvmField val compiledCount = AtomicLong()
    @JvmField val failedCount = AtomicLong()
    @JvmField val failureReasons = ConcurrentHashMap<String, AtomicLong>()

    /** [cb]'s code, compiled by this tier and defined by [definer], or null (counted, with the reason) when it cannot be. */
    fun compile(cb: CodeBlock, definer: CodeDefiner): CompiledCode? {
        try {
            val g = IrBuilder(cb, JvmCompiler.INT32, JvmCompiler.ELEMS).build()
            val name = "dev/mooner/neonjs/jit/T2$" + JvmCompiler.sanitize(cb.name) + "$" + counter.incrementAndGet()
            val bytes = IrCodegen.generate(g, name, definer.classFileVersion, JvmCompiler.INT32, JvmCompiler.ELEMS)
            JvmCompiler.DUMP_DIR?.let { dir -> runCatching { java.io.File(dir, name.substringAfterLast('/') + ".class").writeBytes(bytes) } }
            val cls = definer.define(name.replace('/', '.'), bytes, JvmCompiler::class.java.classLoader)
            val code = cls.getDeclaredConstructor().newInstance() as CompiledCode
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

    /** Compiles [cb] with [definer] and installs the code over its baseline (the stress mode). */
    fun compileOver(cb: CodeBlock, definer: CodeDefiner) {
        val code = compile(cb, definer) ?: return
        Jit.install(cb, code)
    }
}
