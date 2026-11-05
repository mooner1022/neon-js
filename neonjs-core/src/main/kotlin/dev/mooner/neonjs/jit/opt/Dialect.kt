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
    /** The graph of [cb] for the optimizing tier, or a JitBailout where it has none. */
    fun build(cb: CodeBlock, stressExits: Boolean, unswitch: Boolean): Graph

    /** The generator of the `run` method of [g]'s class [className], written to [mv] (exit methods to [cw]). */
    fun codegen(g: Graph, mv: MethodVisitor, kinds: Kinds, cw: ClassWriter, className: String): IrCodegen
}

/** The dialect of each code block. */
internal object Dialects {
    /** [cb]'s language (JavaScript's for every block until another language compiles to code blocks). */
    fun of(cb: CodeBlock): Dialect = JsDialect
}

/** JavaScript, with the baseline's int32 and element settings (JvmCompiler.INT32, JvmCompiler.ELEMS). */
internal object JsDialect : Dialect {
    override fun build(cb: CodeBlock, stressExits: Boolean, unswitch: Boolean): Graph =
        IrBuilder(cb, JvmCompiler.INT32, JvmCompiler.ELEMS, stressExits = stressExits, unswitch = unswitch).build()

    override fun codegen(g: Graph, mv: MethodVisitor, kinds: Kinds, cw: ClassWriter, className: String): IrCodegen =
        JsCodegen(g, mv, kinds, (g.kindSystem as JsKinds).ints, cw, className)
}
