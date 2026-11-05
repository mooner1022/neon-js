package dev.mooner.neonjs.jit.opt

import dev.mooner.neonjs.compiler.CodeBlock
import dev.mooner.neonjs.compiler.CodeUnit
import dev.mooner.neonjs.jit.CodeDefiners
import dev.mooner.neonjs.jit.CompiledCode
import dev.mooner.neonjs.jit.Emit
import dev.mooner.neonjs.jit.FRAME
import dev.mooner.neonjs.jit.JitBailout
import dev.mooner.neonjs.jit.Jit
import dev.mooner.neonjs.jit.JvmEmitter
import dev.mooner.neonjs.parser.FunctionKind
import dev.mooner.neonjs.runtime.Agent
import dev.mooner.neonjs.runtime.Realm
import dev.mooner.neonjs.runtime.RuntimeConfig
import dev.mooner.neonjs.vm.Frame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*

/**
 * A language other than JavaScript on the optimizing tier's core (docs/IR_NEUTRALITY.md): a toy stack bytecode with
 * registers, numbers, `+`, `<`, jumps and returns, given to the core as a dialect (its builder, op table, kinds and code
 * generator, a few dozen lines each), compiled through the same driver as JavaScript (T2.compileBaselines,
 * T2.compile), and run. Whatever the core still assumes of JavaScript shows here.
 */
@Timeout(60)
class ToyDialectTest {
    @Test
    fun aBaselineOfAnotherLanguageRuns() {
        val cb = sumLoop()
        val code = T2.compileBaselines(listOf(cb), CodeDefiners.default).single()
        assertNotNull(code, "not compiled: ${T2.baselineReasons.keys}")
        assertEquals(50.0 * (2.0 + 3.0), run(cb, code!!, 50.0, 2.0, 3.0))
        // operands that are not numbers take the generic path (the language's helper): 0.0 + ("a" + "b")
        assertEquals("0.0ab", run(cb, code, 1.0, "a", "b"))
    }

    @Test
    fun theJitGivesAnotherLanguageTheTiersBaseline() {
        // the tier-up policy's path (Jit.prepare, the queue, ClassBackend): the optimizing tier's baseline, never the
        // baseline compiler's (T1 reads JavaScript's bytecode)
        val cb = sumLoop()
        val agent = Agent(RuntimeConfig().apply {
            executionMode = Jit.MODE_COMPILED
            backgroundJit = false
        })
        val code = Jit.prepare(cb, agent)
        assertTrue(code is T2Code, "not the tier's code: $code (${T2.baselineReasons.keys})")
        assertEquals(50.0 * (2.0 + 3.0), run(cb, code!!, 50.0, 2.0, 3.0))
        // NO_JIT keeps a block of another language interpreted, as JavaScript's
        val off = sumLoop().also { it.flags = it.flags or CodeBlock.NO_JIT }
        assertEquals(null, Jit.prepare(off, agent))
    }

    @Test
    fun theCorePassesWorkOnAnotherLanguage() {
        // the speculating tier's pipeline: loop-invariant code motion moves `2 + 3` (numbers, no exit) before the loop
        // (`a + b` of arguments stays: nothing says they are numbers)
        val cb = sumLoop(constants = true)
        val g = ToyBuilder(cb, speculate = true).build()
        val header = g.blocks.single { it.loopHeader }
        val entry = header.loopEntry
        assertNotNull(entry, "nothing moved out of the loop:\n${IrPrinter.print(g)}")
        assertTrue(entry!!.insts.any { it.op == T_ADD }, IrPrinter.print(g))
        val code = T2.compile(cb, CodeDefiners.default)
        assertNotNull(code, "not compiled: ${T2.failureReasons.keys}")
        assertEquals(10.0 * 5.0, run(cb, code!!, 10.0, 0.0, 0.0))
    }

    /**
     * `s = 0; i = 0; while (i < n) { s = s + (a + b); i = i + 1 }; return s`, with n, a, b its arguments; with
     * [constants], `s + (2 + 3)` instead.
     */
    private fun sumLoop(constants: Boolean = false): CodeBlock {
        val cb = CodeBlock("sumLoop", FunctionKind.NORMAL)
        // registers: 0 n, 1 a, 2 b, 3 s, 4 i
        cb.constants = arrayOf(0.0, 1.0, 2.0, 3.0)
        val (ra, rb) = if (constants) T_CONST to T_CONST else T_LOAD to T_LOAD
        val (ka, kb) = if (constants) 2 to 3 else 1 to 2
        cb.code = intArrayOf(
            T_ARG, 0, T_STORE, 0,
            T_ARG, 1, T_STORE, 1,
            T_ARG, 2, T_STORE, 2,
            T_CONST, 0, T_STORE, 3,          // s = 0
            T_CONST, 0, T_STORE, 4,          // i = 0
            // 20: loop header
            T_LOAD, 4, T_LOAD, 0, T_LT, T_JUMP_IF_FALSE, 46,
            T_LOAD, 3, ra, ka, rb, kb, T_ADD, T_ADD, T_STORE, 3,
            T_LOAD, 4, T_CONST, 1, T_ADD, T_STORE, 4,
            T_JUMP, 20,
            // 46
            T_LOAD, 3, T_RETURN,
        )
        cb.numRegs = 5
        cb.maxStack = 4
        cb.dialect = ToyDialect
        return cb
    }

    private fun run(cb: CodeBlock, code: CompiledCode, n: Double, a: Any, b: Any): Any? {
        cb.compiled = code
        val realm = Realm(Agent())
        val f = Frame(null, cb, realm, null, arrayOf(n, a, b), null, null)
        return code.run(f)
    }
}

// ---------------------------------------------------------------------- the toy language

internal const val T_CONST = 0
internal const val T_LOAD = 1
internal const val T_STORE = 2
internal const val T_ADD = 3
internal const val T_LT = 4
internal const val T_JUMP = 5
internal const val T_JUMP_IF_FALSE = 6
internal const val T_RETURN = 7
internal const val T_ARG = 8
private val NAMES = arrayOf("Const", "Load", "Store", "Add", "Lt", "Jump", "JumpIfFalse", "Return", "Arg")

/** The toy language's runtime: what its generic operations call. */
object ToyRt {
    @JvmStatic fun add(a: Any?, b: Any?): Any? = if (a is Double && b is Double) a + b else a.toString() + b.toString()
    @JvmStatic fun lt(a: Any?, b: Any?): Boolean = (a as Double) < (b as Double)
}

/** Kinds: anything (an Object), a number (a double), a boolean (an int). */
internal object ToyKinds : KindSystem {
    const val NUM: Byte = 1
    const val BOOL: Byte = 2

    override fun rep(kind: Byte): Rep = when (kind) {
        NUM -> Rep.DOUBLE
        BOOL -> Rep.INT
        else -> Rep.OBJECT
    }

    override fun join(a: Byte, b: Byte): Byte = if (a == b) a else ANY_KIND
    override fun isNumeric(kind: Byte): Boolean = kind == NUM
    override fun constKind(value: Any?): Byte = when (value) {
        is Double -> NUM
        is Boolean -> BOOL
        else -> ANY_KIND
    }

    override fun resultKind(i: Inst, kinds: Kinds): Byte = when (i.op) {
        T_ADD -> if (i.inputs.all { kinds.of(it) == NUM }) NUM else ANY_KIND
        T_LT -> BOOL
        else -> ANY_KIND
    }

    override fun initial(em: Emit, kind: Byte) = rep(kind).zero(em.mv)

    override fun convert(em: Emit, from: Byte, to: Byte) {
        when {
            from == to -> {}
            to == ANY_KIND && from == NUM -> em.mv.visitMethodInsn(INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false)
            to == ANY_KIND && from == BOOL -> em.mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)
            else -> throw JitBailout("toy: kind $from as $to")
        }
    }

    override fun osrCode(kind: Byte): Char = if (kind == NUM) 'N' else '.'
    override fun osrRead(em: Emit, kind: Byte) {
        if (kind == NUM) em.rt("slotNum", "([Ljava/lang/Object;I)D") else em.mv.visitInsn(AALOAD)
    }

    override fun name(kind: Byte): String = when (kind) {
        NUM -> " :num"
        BOOL -> " :bool"
        else -> ""
    }
}

/** What the passes know of the toy ops: `+` and `<` of numbers only compute, `+` moves out of loops. */
internal object ToyOps : OpTable {
    private val add = OpInfo("Add", OpInfo.ANYTHING or OpInfo.NUMERIC_PURE, OpInfo.HOIST_NUMERIC_NO_EXIT)
    private val lt = OpInfo("Lt", OpInfo.ANYTHING or OpInfo.NUMERIC_PURE)
    private val arg = OpInfo("Arg", 0)
    private val control = OpInfo("Control", 0)
    private val ret = OpInfo("Return", OpInfo.OBJECT_OPERANDS)

    override fun info(op: Int): OpInfo = when (op) {
        T_ADD -> add
        T_LT -> lt
        T_ARG -> arg
        Ir.GOTO, Ir.BRANCH -> control
        Ir.RETURN -> ret
        else -> OpInfo(if (op < NAMES.size) NAMES[op] else Ir.name(op), OpInfo.ANYTHING)
    }
}

internal object ToyDialect : Dialect {
    override fun build(cb: CodeUnit, stressExits: Boolean, unswitch: Boolean, baseline: Boolean): Graph =
        ToyBuilder(cb, speculate = !baseline).build()

    override val resumer: Resumer get() = Resumer { _, _, _, _ -> error("the toy language has no exits") }

    override fun facts(g: Graph, kinds: Kinds): FactRules? = null

    override fun codegen(g: Graph, mv: MethodVisitor, kinds: Kinds, cw: ClassWriter, className: String): IrCodegen =
        ToyCodegen(g, mv, kinds, cw, className)
}

internal class ToyBuilder(cb: CodeUnit, speculate: Boolean) :
    GraphBuilder(cb, Graph(cb, ToyOps, ToyKinds, null, ToyDialect), stressExits = false, speculate = speculate) {
    override fun unbuildable(): String? = null
    override fun root(): Body = ToyBody(cb)
    override fun entry(start: Block): Array<Value> = Array(cb.numRegs) { dead() }

    private inner class ToyBody(code: CodeUnit) : Body(code, null) {
        override fun length(pc: Int): Int = if (bc[pc] == T_ADD || bc[pc] == T_LT || bc[pc] == T_RETURN) 1 else 2
        override fun operand(pc: Int, k: Int): Int = if (k == 1 && length(pc) == 2) bc[pc + 1] else 0
        override fun targets(pc: Int): IntArray? {
            val next = pc + length(pc)
            return when (bc[pc]) {
                T_JUMP -> intArrayOf(bc[pc + 1])
                T_JUMP_IF_FALSE -> if (bc[pc + 1] == next) intArrayOf(next) else intArrayOf(bc[pc + 1], next)
                T_RETURN -> IntArray(0)
                else -> null
            }
        }
        override fun supported(pc: Int): Boolean = bc[pc] < NAMES.size
        override fun regsRead(pc: Int): IntArray = if (bc[pc] == T_LOAD) intArrayOf(bc[pc + 1]) else IntArray(0)
        override fun regWritten(pc: Int): Int = if (bc[pc] == T_STORE) bc[pc + 1] else -1
        override fun nameAt(pc: Int): String = NAMES[bc[pc]]

        override fun buildOp(at: At): Boolean = with(at) {
            when (op) {
                T_CONST -> stack.add(Const(code.constants[a]))
                T_LOAD -> stack.add(regs[a]!!)
                T_STORE -> {
                    regs[a] = pop()
                    home(a)
                }
                T_ADD, T_LT -> {
                    val r = pop()
                    val l = pop()
                    inst(op, arrayOf(l, r), result = true)
                }
                T_ARG -> inst(T_ARG, NO_VALUES, result = true)
                T_JUMP -> {
                    end()
                    goto(cur, blockAt[a]!!)
                    return true
                }
                T_JUMP_IF_FALSE -> {
                    val c = pop()
                    end()
                    add(cur, Inst(Ir.BRANCH, arrayOf(c), T_JUMP_IF_FALSE, 0, 0, pc, stmt)).targets = listOf(blockAt[a]!!, blockAt[pc + len]!!)
                    return true
                }
                T_RETURN -> {
                    add(cur, Inst(Ir.RETURN, arrayOf(pop()), 0, 0, 0, pc, stmt))
                    end()
                    return true
                }
            }
            false
        }
    }
}

internal class ToyCodegen(g: Graph, mv: MethodVisitor, kinds: Kinds, cw: ClassWriter, className: String) :
    IrCodegen(g, mv, kinds, cw, className) {
    override fun inst(i: Inst) {
        when (i.op) {
            T_ARG -> {
                frame()
                mv.visitFieldInsn(GETFIELD, FRAME, "args", "[Ljava/lang/Object;")
                iconst(i.a)
                mv.visitInsn(AALOAD)
                done(i, ANY_KIND)
            }
            T_ADD -> if (i.kind == ToyKinds.NUM) {
                load(i.inputs[0], ToyKinds.NUM)
                load(i.inputs[1], ToyKinds.NUM)
                mv.visitInsn(DADD)
                done(i, ToyKinds.NUM)
            } else {
                load(i.inputs[0], ANY_KIND)
                load(i.inputs[1], ANY_KIND)
                mv.visitMethodInsn(INVOKESTATIC, RT, "add", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false)
                done(i, ANY_KIND)
            }
            T_LT -> {
                load(i.inputs[0], ANY_KIND)
                load(i.inputs[1], ANY_KIND)
                mv.visitMethodInsn(INVOKESTATIC, RT, "lt", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false)
                done(i, ToyKinds.BOOL)
            }
            else -> throw JitBailout("toy: no code for ${Ir.name(i.op)}")
        }
    }

    override fun branch(i: Inst): Int {
        load(i.inputs[0], ToyKinds.BOOL)
        return IFEQ // to the jump's target when false
    }

    override fun switchIndex(i: Inst) = throw JitBailout("toy: no switch")
    override fun otherTerminator(i: Inst): Boolean = false

    override fun pushConst(c: Const, want: Byte) {
        when (val v = c.value) {
            null -> mv.visitInsn(ACONST_NULL)
            is Double -> {
                mv.visitLdcInsn(v)
                if (want == ANY_KIND) ToyKinds.convert(this, ToyKinds.NUM, ANY_KIND)
            }
            else -> mv.visitLdcInsn(v)
        }
    }

    override fun constObject(em: Emit, c: Const) = throw JitBailout("toy: no exits")
    override fun allocateVirtual(em: Emit, v: Inst) = throw JitBailout("toy: no virtual objects")
    override fun inlinedRecord(em: Emit, site: InlineSite, state: FrameState, inner: Int, value: (Value) -> Unit, array: (Array<Value>) -> Unit) =
        throw JitBailout("toy: no inlining")
    override fun stressExit(em: Emit) = throw JitBailout("toy: no exits")
    override fun suspendCall(em: Emit, i: Inst, value: (Value) -> Unit) = throw JitBailout("toy: no suspensions")
    override fun siteLocals(site: InlineSite): List<Value> = emptyList()
    override fun newEmitter(m: MethodVisitor, first: Int): Emit = object : JvmEmitter(m) {
        var top = first
        override fun alloc(n: Int): Int = top.also { top += n }
    }

    override val deadValue: Any? get() = null

    override fun backEdgeTargets(): IntArray {
        val code = g.code.code
        val out = sortedSetOf<Int>()
        var pc = 0
        while (pc < code.size) {
            val op = code[pc]
            if ((op == T_JUMP || op == T_JUMP_IF_FALSE) && code[pc + 1] <= pc) out.add(code[pc + 1])
            pc += if (op == T_ADD || op == T_LT || op == T_RETURN) 1 else 2
        }
        return out.toIntArray()
    }

    private companion object {
        const val RT = "dev/mooner/neonjs/jit/opt/ToyRt"
    }
}
