package dev.mooner.neonjs.jit.opt

import dev.mooner.neonjs.compiler.CodeBlock
import dev.mooner.neonjs.jit.CodeDefiner
import dev.mooner.neonjs.jit.CodeDefiners
import dev.mooner.neonjs.jit.CompiledCode
import dev.mooner.neonjs.jit.JvmCompiler
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor

/**
 * A language of the optimizing tier (docs/IR_NEUTRALITY.md): how its code blocks become graphs ([build]: its
 * [GraphBuilder], [OpTable] and [KindSystem]) and how their instructions become JVM code ([codegen]: its
 * [IrCodegen]). Everything between, the passes and the code's frame, exits and entries, is the core's.
 *
 * (Part of the core's interface for languages: public for NeonJS's language modules, not a stable API.)
 */
interface Dialect {
    /**
     * The graph of [cb] for the optimizing tier, or a JitBailout where it has none. A [baseline]'s speculates on nothing
     * and has no exits but the stress mode's (docs/IR_NEUTRALITY.md §5).
     */
    fun build(cb: CodeBlock, stressExits: Boolean, unswitch: Boolean, baseline: Boolean = false): Graph

    /** How its code's exits continue in its interpreter. */
    val resumer: Resumer

    /**
     * What a baseline's code depends on besides its graph (GraphIdentity): the language's settings, what its code
     * generator reads of the block (JavaScript: its loop headers).
     */
    fun identityExtras(g: Graph, out: java.io.DataOutputStream) {}

    /** Its facts after guards for [g] (null: none), [Facts]. */
    fun facts(g: Graph, kinds: Kinds): FactRules?

    /** The generator of the `run` method of [g]'s class [className], written to [mv] (exit methods to [cw]). */
    fun codegen(g: Graph, mv: MethodVisitor, kinds: Kinds, cw: ClassWriter, className: String): IrCodegen

    /** Whether some tier compiles [cb] (the JIT's policy asks: Jit.canCompile); a NO_JIT block stays interpreted. */
    fun canCompile(cb: CodeBlock): Boolean = cb.flags and CodeBlock.NO_JIT == 0

    /**
     * The baselines of [blocks], all of this language (ClassBackend; null where one has none): the tier's pipeline's
     * ([T2.compileBaselines]), unless the language has a baseline compiler of its own (JavaScript's T1).
     */
    fun compileBaselines(blocks: List<CodeBlock>, definer: CodeDefiner, tierUp: Boolean): List<CompiledCode?> =
        if (CodeDefiners.isUsable(definer)) T2.compileBaselines(blocks, definer, tierUp) else arrayOfNulls<CompiledCode>(blocks.size).asList()
}

/** The dialect of each code block. */
internal object Dialects {
    /** [cb]'s language: the dialect it names (the JS compiler gives its blocks [JsDialect]). */
    fun of(cb: CodeBlock): Dialect = cb.dialect as Dialect
}

/** JavaScript, with the baseline's int32 and element settings (JvmCompiler.INT32, JvmCompiler.ELEMS). */
internal object JsDialect : Dialect {
    override val resumer: Resumer get() = Resumer.JS

    override fun facts(g: Graph, kinds: Kinds): FactRules = KnownShapes.rules(g, kinds)

    override fun identityExtras(g: Graph, out: java.io.DataOutputStream) {
        out.writeBoolean((g.kindSystem as JsKinds).ints)
        out.writeBoolean(JvmCompiler.ELEMS)
        out.writeInt(JvmCompiler.DEOPT_STRESS)
        val t = JvmCompiler.backEdgeTargets(g.code.code)
        out.writeInt(t.size)
        for (pc in t) out.writeInt(pc)
    }

    override fun build(cb: CodeBlock, stressExits: Boolean, unswitch: Boolean, baseline: Boolean): Graph =
        IrBuilder(cb, JvmCompiler.INT32, JvmCompiler.ELEMS, stressExits = stressExits, unswitch = unswitch,
            speculate = T2.SPECULATE && !baseline, exits = !baseline).build()

    override fun codegen(g: Graph, mv: MethodVisitor, kinds: Kinds, cw: ClassWriter, className: String): IrCodegen =
        JsCodegen(g, mv, kinds, (g.kindSystem as JsKinds).ints, cw, className)

    /** The baseline compiler's (T1), or, for generators and async functions, the tier's pipeline's. */
    override fun canCompile(cb: CodeBlock): Boolean = JvmCompiler.canCompile(cb) || T2.suspendable(cb)

    /**
     * T1's baselines, and the tier's for the generators and async functions T1 leaves; with `-Dneonjs.jit.irBaseline`
     * (testing), the tier's, T1's where it has none.
     */
    override fun compileBaselines(blocks: List<CodeBlock>, definer: CodeDefiner, tierUp: Boolean): List<CompiledCode?> {
        if (!T2.IR_BASELINE) {
            val out = JvmCompiler.compileAll(blocks, definer, tierUp)
            val rest = blocks.indices.filter { out[it] == null && T2.suspendable(blocks[it]) }
            if (rest.isEmpty() || !CodeDefiners.isUsable(definer)) return out
            val ir = T2.compileBaselines(rest.map { blocks[it] }, definer, tierUp)
            return out.toMutableList().also { for ((j, i) in rest.withIndex()) it[i] = ir[j] }
        }
        val usable = CodeDefiners.isUsable(definer)
        val ir = if (usable) T2.compileBaselines(blocks.filter { canCompile(it) }, definer, tierUp) else emptyList()
        val out = arrayOfNulls<CompiledCode>(blocks.size)
        var k = 0
        for ((i, cb) in blocks.withIndex()) if (usable && canCompile(cb)) out[i] = ir[k++]
        val rest = blocks.indices.filter { out[it] == null }
        if (rest.isNotEmpty()) {
            val t1 = JvmCompiler.compileAll(rest.map { blocks[it] }, definer, tierUp)
            for ((j, i) in rest.withIndex()) out[i] = t1[j]
        }
        return out.asList()
    }
}
