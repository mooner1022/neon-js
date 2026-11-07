package dev.mooner.neonjs.jit

import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*

// Names the generated code uses, and the locals of CompiledCode.run that every code generator lays out the same way.
// (Part of the core's interface for languages: public for NeonJS's language modules, not a stable API.)
const val OBJ = "Ljava/lang/Object;"
const val CONSTS_D = "[Ljava/lang/Object;"
// the core's classes (docs/IR_NEUTRALITY.md §10)
const val VM_FRAME = "dev/mooner/neonjs/vm/VmFrame"
const val VM_FRAME_D = "Ldev/mooner/neonjs/vm/VmFrame;"
const val VM_AGENT = "dev/mooner/neonjs/runtime/VmAgent"
const val VM_AGENT_D = "Ldev/mooner/neonjs/runtime/VmAgent;"
const val CODE_UNIT = "dev/mooner/neonjs/compiler/CodeUnit"
const val CODE_UNIT_D = "Ldev/mooner/neonjs/compiler/CodeUnit;"
const val VM_RT = "dev/mooner/neonjs/jit/VmRt"
const val VM_DEOPT = "dev/mooner/neonjs/jit/VmDeopt"

const val L_FRAME = 1
const val L_CONSTS = 2
const val L_AGENT = 3
const val L_BUDGET = 4
const val L_REGS = 5

/**
 * Writes the classes of generated code: frames and stack sizes computed, every class merged into Object (the generated
 * code never needs a more precise common supertype, and loading classes to find one could fail in a definer's loader).
 */
class JitClassWriter : org.objectweb.asm.ClassWriter(COMPUTE_FRAMES or COMPUTE_MAXS) {
    override fun getCommonSuperClass(type1: String, type2: String): String = "java/lang/Object"
}

/**
 * What every code generator of the JIT emits alike, whatever the language: JVM locals, constants, the interrupt check
 * of a loop's back edge. [JvmEmitter] is the base class of the generators; a language adds what its code needs
 * (JavaScript's JsEmit: its runtime's helpers, its values).
 */
interface Emit {
    val mv: MethodVisitor

    /** [n] new JVM locals of the method being generated. */
    fun alloc(n: Int): Int

    fun temp(): Int = alloc(1)

    fun tempD(): Int = alloc(2)


    fun iconst(v: Int) {
        when (v) {
            in -1..5 -> mv.visitInsn(ICONST_0 + v)
            in Byte.MIN_VALUE..Byte.MAX_VALUE -> mv.visitIntInsn(BIPUSH, v)
            in Short.MIN_VALUE..Short.MAX_VALUE -> mv.visitIntInsn(SIPUSH, v)
            else -> mv.visitLdcInsn(v)
        }
    }

    fun frame() = mv.visitVarInsn(ALOAD, L_FRAME)

    /** The class of the agent the code's L_AGENT holds: the core's, or the language's own subclass (JavaScript's Agent). */
    val agentClass: String get() = VM_AGENT

    fun getstatic(owner: String, field: String, desc: String) = mv.visitFieldInsn(GETSTATIC, owner, field, desc)

    fun packArray(n: Int) {
        val temps = IntArray(n) { temp() }
        for (i in n - 1 downTo 0) mv.visitVarInsn(ASTORE, temps[i])
        iconst(n)
        mv.visitTypeInsn(ANEWARRAY, "java/lang/Object")
        for (i in 0 until n) {
            mv.visitInsn(DUP)
            iconst(i)
            mv.visitVarInsn(ALOAD, temps[i])
            mv.visitInsn(AASTORE)
        }
    }

    /** A loop back edge: counts down the interrupt budget (L_BUDGET) and checks for interrupts when it runs out. */
    fun interruptCheck() {
        val ok = Label()
        mv.visitIincInsn(L_BUDGET, -1)
        mv.visitVarInsn(ILOAD, L_BUDGET)
        mv.visitJumpInsn(IFGT, ok)
        mv.visitVarInsn(ALOAD, L_AGENT)
        mv.visitMethodInsn(INVOKEVIRTUAL, agentClass, "checkInterrupt", "()V", false)
        iconst(4096)
        mv.visitVarInsn(ISTORE, L_BUDGET)
        mv.visitLabel(ok)
    }
}

/** A code generator's base: the method it writes ([mv]) and what [Emit] gives every generator. */
abstract class JvmEmitter(final override val mv: MethodVisitor) : Emit

