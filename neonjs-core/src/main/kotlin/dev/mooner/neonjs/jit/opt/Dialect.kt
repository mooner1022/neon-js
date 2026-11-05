package dev.mooner.neonjs.jit.opt

import dev.mooner.neonjs.compiler.CodeBlock
import dev.mooner.neonjs.jit.JvmCompiler
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor

/**
 * A language of the optimizing tier (docs/IR_NEUTRALITY.md): how its code blocks become graphs ([build]: its
 * [GraphBuilder], [OpTable] and [KindSystem]) and how their instructions become JVM code ([codegen]: its
 * [IrCodegen]). Everything between, the passes and the code's frame, exits and entries, is the core's.
 */
internal interface Dialect {
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
}

/** The dialect of each code block. */
internal object Dialects {
    /** [cb]'s language: the dialect it names, else JavaScript's. */
    fun of(cb: CodeBlock): Dialect = cb.dialect as Dialect? ?: JsDialect
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
}
