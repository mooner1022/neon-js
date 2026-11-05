package dev.mooner.neonjs.jit.opt

import dev.mooner.neonjs.compiler.CodeUnit
import dev.mooner.neonjs.jit.CodeDefiner
import dev.mooner.neonjs.jit.CodeDefiners
import dev.mooner.neonjs.jit.CompiledCode
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
    fun build(cb: CodeUnit, stressExits: Boolean, unswitch: Boolean, baseline: Boolean = false): Graph

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
    fun canCompile(cb: CodeUnit): Boolean = cb.flags and CodeUnit.NO_JIT == 0

    /**
     * Whether an interpreted loop of [cb] gets it compiled and goes on in its code (Jit.loop), as its calls get it
     * compiled; JavaScript's eval code and module bodies are compiled at neither.
     */
    fun compilesAtLoops(cb: CodeUnit): Boolean = true

    /**
     * The baselines of [blocks], all of this language (ClassBackend; null where one has none): the tier's pipeline's
     * ([T2.compileBaselines]), unless the language has a baseline compiler of its own (JavaScript's T1).
     */
    fun compileBaselines(blocks: List<CodeUnit>, definer: CodeDefiner, tierUp: Boolean): List<CompiledCode?> =
        if (CodeDefiners.isUsable(definer)) T2.compileBaselines(blocks, definer, tierUp) else arrayOfNulls<CompiledCode>(blocks.size).asList()
}

/** The dialect of each code block. */
internal object Dialects {
    /** [cb]'s language: the dialect it names (the JS compiler gives its blocks [JsDialect]). */
    fun of(cb: CodeUnit): Dialect = cb.dialect as Dialect
}
