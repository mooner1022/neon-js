package dev.mooner.neonjs.jit.opt

import dev.mooner.neonjs.jit.Emit
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*

/**
 * How generated code holds a value: in a JVM local or on the JVM stack, as an Object or as one of the JVM's primitive
 * types. The core's code generation (locals, copies on edges, exits, on-stack replacement entries) works on
 * representations; what a value of a representation means is its kind's ([KindSystem]).
 */
/** The kind of a value about which nothing is known, in every kind system (a value's kind before inference). */
const val ANY_KIND: Byte = 0

enum class Rep(@JvmField val desc: String, @JvmField val load: Int, @JvmField val store: Int, @JvmField val slots: Int) {
    OBJECT("Ljava/lang/Object;", ALOAD, ASTORE, 1),
    INT("I", ILOAD, ISTORE, 1),
    LONG("J", LLOAD, LSTORE, 2),
    DOUBLE("D", DLOAD, DSTORE, 2);

    /** Drops a value of this representation from the JVM stack. */
    fun pop(mv: MethodVisitor) = mv.visitInsn(if (slots == 2) POP2 else POP)

    /** Pushes zero of a primitive representation (null for [OBJECT]). */
    fun zero(mv: MethodVisitor) = when (this) {
        OBJECT -> mv.visitInsn(ACONST_NULL)
        INT -> mv.visitInsn(ICONST_0)
        LONG -> mv.visitInsn(LCONST_0)
        DOUBLE -> mv.visitInsn(DCONST_0)
    }
}

/**
 * A language's kinds of values (docs/IR_NEUTRALITY.md, "KindSystem"): what the optimizing tier knows of a value
 * without a check, as a byte. Every value of the IR has one ([Value.kind]); [Kinds] infers them, and the code generator
 * holds each value as its kind's [rep].
 *
 * A kind must say the truth on every path: it comes from what an operation produces by definition or from a check that
 * exits otherwise (a speculation), never from a profile alone.
 */
interface KindSystem {
    /** The kind of a value about which nothing is known, held as an Object: [ANY_KIND] in every kind system. */
    val any: Byte get() = ANY_KIND

    /** How a value of [kind] is held. */
    fun rep(kind: Byte): Rep

    /** The kind of a value that may be one of [a] or of [b] (where paths meet). */
    fun join(a: Byte, b: Byte): Byte

    /** Whether values of [kind] are numbers: an operation [OpInfo.NUMERIC_PURE] of such inputs only computes. */
    fun isNumeric(kind: Byte): Boolean

    /** The kind of constant [value]. */
    fun constKind(value: Any?): Byte

    /** The kind of [i]'s result, given the kinds [kinds] has for its inputs. */
    fun resultKind(i: Inst, kinds: Kinds): Byte

    /**
     * Pushes the value a local of [kind] holds before anything is stored in it (one a handler may read on a path where
     * nothing was: the JVM's verifier wants it assigned): the language's "no value" for an Object.
     */
    fun initial(em: Emit, kind: Byte)

    /** Converts the value of [from] on the JVM stack to [to] (to [any]: boxes it); fails (JitBailout) where it cannot. */
    fun convert(em: Emit, from: Byte, to: Byte)

    /**
     * On-stack replacement: the letter `JitRt.osrKinds` checks a frame's register against for a value of [kind], '.'
     * for none (an Object: anything goes); then, given the frame's slots and the register's index on the JVM stack,
     * the code that reads it as [kind] ([osrRead]).
     */
    fun osrCode(kind: Byte): Char
    fun osrRead(em: Emit, kind: Byte)

    /**
     * Given the frame's slots on the JVM stack, leaves whether each register whose letter in [kinds] (osrCode) names a
     * kind holds a value of it (the language's helper); called only where some letter is not '.'.
     */
    fun osrCheck(em: Emit, kinds: String): Unit = throw dev.mooner.neonjs.jit.JitBailout("on-stack replacement: no check of kinds")

    /** [kind] in the IR's printed form ("" for [any]). */
    fun name(kind: Byte): String
}
