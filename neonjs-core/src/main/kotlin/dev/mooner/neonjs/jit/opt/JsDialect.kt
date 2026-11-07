package dev.mooner.neonjs.jit.opt

import dev.mooner.neonjs.compiler.CodeBlock
import dev.mooner.neonjs.compiler.CodeUnit
import dev.mooner.neonjs.jit.CodeDefiner
import dev.mooner.neonjs.jit.CodeDefiners
import dev.mooner.neonjs.jit.CompiledCode
import dev.mooner.neonjs.jit.Deopt
import dev.mooner.neonjs.jit.JitRt
import dev.mooner.neonjs.jit.JvmCompiler
import dev.mooner.neonjs.runtime.Null
import dev.mooner.neonjs.runtime.Undefined
import dev.mooner.neonjs.vm.Frame
import dev.mooner.neonjs.vm.Interpreter
import dev.mooner.neonjs.vm.VmFrame
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor

/** JavaScript, with the baseline's int32 and element settings (JvmCompiler.INT32, JvmCompiler.ELEMS). */
internal object JsDialect : Dialect {
    /** Deopt.resume, its records Deopt.Inlined. */
    override val resumer: Resumer = Resumer { f, pc, values, inner -> Deopt.resume(f as Frame, pc, values, inner as Deopt.Inlined?) }

    override fun facts(g: Graph, kinds: Kinds): FactRules = KnownShapes.rules(g, kinds)

    override fun identityExtras(g: Graph, out: java.io.DataOutputStream) {
        out.writeBoolean((g.kindSystem as JsKinds).ints)
        out.writeBoolean(JvmCompiler.ELEMS)
        out.writeInt(JvmCompiler.DEOPT_STRESS)
        val t = JvmCompiler.backEdgeTargets(g.code.code)
        out.writeInt(t.size)
        for (pc in t) out.writeInt(pc)
    }

    override fun build(cb: CodeUnit, stressExits: Boolean, unswitch: Boolean, baseline: Boolean): Graph =
        IrBuilder(cb as CodeBlock, JvmCompiler.INT32, JvmCompiler.ELEMS, stressExits = stressExits, unswitch = unswitch,
            speculate = T2.SPECULATE && !baseline, exits = !baseline).build()

    override fun codegen(g: Graph, mv: MethodVisitor, kinds: Kinds, cw: ClassWriter, className: String): IrCodegen =
        JsCodegen(g, mv, kinds, (g.kindSystem as JsKinds).ints, cw, className)

    /**
     * The baseline compiler's (T1), or, for generators and async functions, the tier's pipeline's; code with strict regions
     * inside sloppy code ([CodeBlock.strictPcs]) is rare, and stays interpreted.
     */
    override fun canCompile(cb: CodeUnit): Boolean =
        (cb as CodeBlock).strictPcs == null && (JvmCompiler.canCompile(cb) || T2.suspendable(cb))

    override fun interpret(f: VmFrame): Any? = Interpreter.run(f as Frame)

    override fun leaveCompiled(e: Throwable, f: VmFrame): Throwable = JitRt.leaveCompiled(e, f as Frame)

    override fun dropInlined(f: VmFrame) = JitRt.dropInlined(f as Frame)

    /** undefined, null, true and false (constants of no index). */
    override fun constIdentity(value: Any, out: java.io.DataOutputStream): Boolean {
        when {
            value === Undefined -> out.writeByte('u'.code)
            value === Null -> out.writeByte('n'.code)
            value is Boolean -> { out.writeByte('b'.code); out.writeBoolean(value) }
            else -> return false
        }
        return true
    }

    /** Eval code and module bodies are compiled at neither their calls nor their loops; functions and classic scripts are. */
    override fun compilesAtLoops(cb: CodeUnit): Boolean =
        cb.flags and CodeBlock.TOP_LEVEL == 0 || cb.flags and CodeBlock.SCRIPT != 0

    /**
     * T1's baselines, and the tier's for the generators and async functions T1 leaves; with `-Dneonjs.jit.irBaseline`
     * (testing), the tier's, T1's where it has none.
     */
    override fun compileBaselines(blocks: List<CodeUnit>, definer: CodeDefiner, tierUp: Boolean): List<CompiledCode?> {
        if (!T2.IR_BASELINE) {
            val out = JvmCompiler.compileAll(blocks.map { it as CodeBlock }, definer, tierUp)
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
            val t1 = JvmCompiler.compileAll(rest.map { blocks[it] as CodeBlock }, definer, tierUp)
            for ((j, i) in rest.withIndex()) out[i] = t1[j]
        }
        return out.asList()
    }
}
