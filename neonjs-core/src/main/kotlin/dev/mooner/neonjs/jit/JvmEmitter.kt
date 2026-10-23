package dev.mooner.neonjs.jit

import dev.mooner.neonjs.compiler.Op
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*

// Names the generated code uses, and the locals of CompiledCode.run that every code generator lays out the same way.
internal const val FRAME = "dev/mooner/neonjs/vm/Frame"
internal const val RT = "dev/mooner/neonjs/jit/JitRt"
internal const val OBJ = "Ljava/lang/Object;"
internal const val FRAME_D = "Ldev/mooner/neonjs/vm/Frame;"
internal const val AGENT = "dev/mooner/neonjs/runtime/Agent"
internal const val CLOSURE = "dev/mooner/neonjs/vm/JSClosure"
internal const val CLOSURE_D = "Ldev/mooner/neonjs/vm/JSClosure;"
internal const val CODEBLOCK = "dev/mooner/neonjs/compiler/CodeBlock"
internal const val CODEBLOCK_D = "Ldev/mooner/neonjs/compiler/CodeBlock;"
internal const val REALM_D = "Ldev/mooner/neonjs/runtime/Realm;"
internal const val AGENT_D = "Ldev/mooner/neonjs/runtime/Agent;"
internal const val CONSTS_D = "[Ljava/lang/Object;"

internal const val L_FRAME = 1
internal const val L_CONSTS = 2
internal const val L_AGENT = 3
internal const val L_BUDGET = 4
internal const val L_REGS = 5

/**
 * Writes the classes of generated code: frames and stack sizes computed, every class merged into Object (the generated
 * code never needs a more precise common supertype, and loading classes to find one could fail in a definer's loader).
 */
internal class JitClassWriter : org.objectweb.asm.ClassWriter(COMPUTE_FRAMES or COMPUTE_MAXS) {
    override fun getCommonSuperClass(type1: String, type2: String): String = "java/lang/Object"
}

/**
 * What the code generators of the JIT tiers emit alike: constants, `JitRt` calls, conversions of unboxed values, and
 * the helper call of every instruction whose operands are Objects on the JVM stack ([emitHelper]): the code an
 * instruction gets when nothing about it is specialized. [JvmCompiler]'s generator extends it, as does the optimizing
 * tier's, so that both call the runtime the same way.
 */
internal abstract class JvmEmitter(@JvmField val mv: MethodVisitor) {
    /** [n] new JVM locals of the method being generated. */
    abstract fun alloc(n: Int): Int

    /** Pushes the constants array of the code the instruction belongs to (an inlined body's are its own). */
    open fun consts() = mv.visitVarInsn(ALOAD, L_CONSTS)

    /** The local holding the environment, or `this`, of the inlined body being generated; -1 elsewhere (the frame's). */
    open fun inlinedEnv(): Int = -1
    open fun inlinedThis(): Int = -1

    /** Whether the instruction is in an inlined body (its calls record its position in Frame.inlinePc). */
    open val inInlinedBody: Boolean get() = false

    fun temp(): Int = alloc(1)

    fun tempD(): Int = alloc(2)

    fun rt(name: String, desc: String) = mv.visitMethodInsn(INVOKESTATIC, RT, name, desc, false)

    fun iconst(v: Int) {
        when (v) {
            in -1..5 -> mv.visitInsn(ICONST_0 + v)
            in Byte.MIN_VALUE..Byte.MAX_VALUE -> mv.visitIntInsn(BIPUSH, v)
            in Short.MIN_VALUE..Short.MAX_VALUE -> mv.visitIntInsn(SIPUSH, v)
            else -> mv.visitLdcInsn(v)
        }
    }

    fun frame() = mv.visitVarInsn(ALOAD, L_FRAME)

    fun constant(k: Int) {
        consts()
        iconst(k)
        mv.visitInsn(AALOAD)
    }

    fun getstatic(owner: String, field: String, desc: String) = mv.visitFieldInsn(GETSTATIC, owner, field, desc)

    fun pushUndefined() = getstatic("dev/mooner/neonjs/runtime/Undefined", "INSTANCE", "Ldev/mooner/neonjs/runtime/Undefined;")

    fun returnIfTail() {
        mv.visitInsn(DUP)
        getstatic("dev/mooner/neonjs/vm/Interpreter", "TAIL", OBJ)
        val cont = Label()
        mv.visitJumpInsn(IF_ACMPNE, cont)
        mv.visitInsn(ARETURN)
        mv.visitLabel(cont)
    }

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

    fun box(kind: Byte) {
        when (kind) {
            JT.NUM -> mv.visitMethodInsn(INVOKESTATIC, "dev/mooner/neonjs/runtime/Ops", "num", "(D)$OBJ", false)
            JT.INT -> mv.visitMethodInsn(INVOKESTATIC, "dev/mooner/neonjs/runtime/Ops", "num", "(I)$OBJ", false)
            JT.BOOL -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)
        }
    }

    fun convert(from: Byte, to: Byte) {
        when {
            from == to -> {}
            to == JT.ANY -> box(from)
            from == JT.INT && to == JT.NUM -> mv.visitInsn(I2D)
            else -> throw JitBailout("cannot narrow a stack value")
        }
    }

    fun popValue(kind: Byte) = mv.visitInsn(if (kind == JT.NUM) POP2 else POP)

    fun toDouble(kind: Byte) = convert(kind, JT.NUM)

    fun toInt32(kind: Byte) {
        if (kind == JT.NUM) rt("toInt32D", "(D)I")
    }

    fun unboxNum() {
        mv.visitTypeInsn(CHECKCAST, "java/lang/Double")
        mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false)
    }

    fun storeTemp(kind: Byte): Int = when (kind) {
        JT.NUM -> tempD().also { mv.visitVarInsn(DSTORE, it) }
        JT.BOOL, JT.INT -> temp().also { mv.visitVarInsn(ISTORE, it) }
        else -> temp().also { mv.visitVarInsn(ASTORE, it) }
    }

    fun loadTemp(kind: Byte, t: Int) = mv.visitVarInsn(when (kind) { JT.NUM -> DLOAD; JT.BOOL, JT.INT -> ILOAD; else -> ALOAD }, t)

    fun compareDoubles(op: Int) {
        // NaN: DCMPG gives 1 (so < and <= are false), DCMPL -1 (so > and >= are false); == and != use DCMPL
        val (cmp, jump) = when (op) {
            Op.LT -> DCMPG to IFLT
            Op.LE -> DCMPG to IFLE
            Op.GT -> DCMPL to IFGT
            Op.GE -> DCMPL to IFGE
            Op.EQ, Op.SEQ -> DCMPL to IFEQ
            else -> DCMPL to IFNE // NE, SNE
        }
        mv.visitInsn(cmp)
        val yes = Label()
        val done = Label()
        mv.visitJumpInsn(jump, yes)
        mv.visitInsn(ICONST_0)
        mv.visitJumpInsn(GOTO, done)
        mv.visitLabel(yes)
        mv.visitInsn(ICONST_1)
        mv.visitLabel(done)
    }

    fun compareInts(op: Int) {
        val jump = when (op) {
            Op.LT -> IF_ICMPLT
            Op.LE -> IF_ICMPLE
            Op.GT -> IF_ICMPGT
            Op.GE -> IF_ICMPGE
            Op.EQ, Op.SEQ -> IF_ICMPEQ
            else -> IF_ICMPNE // NE, SNE
        }
        val yes = Label()
        val done = Label()
        mv.visitJumpInsn(jump, yes)
        mv.visitInsn(ICONST_0)
        mv.visitJumpInsn(GOTO, done)
        mv.visitLabel(yes)
        mv.visitInsn(ICONST_1)
        mv.visitLabel(done)
    }

    /**
     * A comparison of a number with a value of unknown type: compares inline when the value is a number, and calls
     * the `xxDA` / `xxAD` helper (the generic operator) otherwise.
     */
    fun compareMixed(op: Int, base: String, numLeft: Boolean) {
        val slow = Label()
        val done = Label()
        if (numLeft) {
            // number, value
            mv.visitInsn(DUP)
            mv.visitTypeInsn(INSTANCEOF, "java/lang/Double")
            mv.visitJumpInsn(IFEQ, slow)
            unboxNum()
            compareDoubles(op)
            mv.visitJumpInsn(GOTO, done)
            mv.visitLabel(slow)
            rt(base + "DA", "(D$OBJ)Z")
        } else {
            // value, number
            val t = tempD()
            mv.visitVarInsn(DSTORE, t)
            mv.visitInsn(DUP)
            mv.visitTypeInsn(INSTANCEOF, "java/lang/Double")
            mv.visitJumpInsn(IFEQ, slow)
            unboxNum()
            mv.visitVarInsn(DLOAD, t)
            compareDoubles(op)
            mv.visitJumpInsn(GOTO, done)
            mv.visitLabel(slow)
            mv.visitVarInsn(DLOAD, t)
            rt(base + "AD", "(${OBJ}D)Z")
        }
        mv.visitLabel(done)
    }

    /**
     * The truth value of the value of [kind] on the stack, as an int that is 0 for false: 0 or 1, or (for INT, unless
     * [normalized]) the int itself.
     */
    fun truth(kind: Byte, normalized: Boolean = false) {
        when (kind) {
            JT.BOOL -> {}
            JT.INT -> if (normalized) {
                // (x | -x) >>> 31: 1 for every int but 0
                mv.visitInsn(DUP)
                mv.visitInsn(INEG)
                mv.visitInsn(IOR)
                iconst(31)
                mv.visitInsn(IUSHR)
            }
            JT.NUM -> rt("truthyD", "(D)Z")
            else -> rt("truthy", "($OBJ)Z")
        }
    }

    /** A loop back edge: counts down the interrupt budget (L_BUDGET) and checks for interrupts when it runs out. */
    fun interruptCheck() {
        val ok = Label()
        mv.visitIincInsn(L_BUDGET, -1)
        mv.visitVarInsn(ILOAD, L_BUDGET)
        mv.visitJumpInsn(IFGT, ok)
        mv.visitVarInsn(ALOAD, L_AGENT)
        mv.visitMethodInsn(INVOKEVIRTUAL, "dev/mooner/neonjs/runtime/Agent", "checkInterrupt", "()V", false)
        iconst(4096)
        mv.visitVarInsn(ISTORE, L_BUDGET)
        mv.visitLabel(ok)
    }

    /**
     * The code of instruction [op] at [pc] (operands [a], [b], [c]) whose operands are Objects on the JVM stack and whose
     * results are left there: a `JitRt` (or runtime) call. False, with nothing emitted, for the instructions every
     * generator compiles its own way: stack shuffles, registers, jumps, iteration steps and returns.
     */
    fun emitHelper(op: Int, pc: Int, a: Int, b: Int, c: Int): Boolean {
        when (op) {
            Op.NOP -> {}
            Op.PUSH_UNDEF -> pushUndefined()
            Op.PUSH_NULL -> getstatic("dev/mooner/neonjs/runtime/Null", "INSTANCE", "Ldev/mooner/neonjs/runtime/Null;")
            Op.PUSH_TRUE -> getstatic("java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;")
            Op.PUSH_FALSE -> getstatic("java/lang/Boolean", "FALSE", "Ljava/lang/Boolean;")
            Op.PUSH_CONST -> constant(a)
            Op.PUSH_INT -> {
                iconst(a)
                mv.visitMethodInsn(INVOKESTATIC, "dev/mooner/neonjs/runtime/Ops", "num", "(I)$OBJ", false)
            }
            Op.PUSH_SCOPE -> { frame(); consts(); iconst(a); rt("pushScope", "(${FRAME_D}${CONSTS_D}I)V") }
            Op.POP_SCOPE -> { frame(); rt("popScope", "($FRAME_D)V") }
            Op.COPY_SCOPE -> { frame(); rt("copyScope", "($FRAME_D)V") }
            Op.GET_ENV -> { frame(); mv.visitFieldInsn(GETFIELD, FRAME, "env", "Ldev/mooner/neonjs/runtime/Env;") }
            Op.SET_ENV -> { frame(); rt("setEnv", "($OBJ$FRAME_D)V") }
            Op.PUSH_WITH -> { frame(); rt("pushWith", "($OBJ$FRAME_D)V") }
            // an inlined body's environment is its closure's
            Op.LOAD_ENV -> if (inlinedEnv() >= 0) {
                mv.visitVarInsn(ALOAD, inlinedEnv()); iconst(a); iconst(b); rt("loadEnvE", "(${OBJ}II)$OBJ")
            } else { frame(); iconst(a); iconst(b); rt("loadEnv", "(${FRAME_D}II)$OBJ") }
            Op.STORE_ENV -> if (inlinedEnv() >= 0) {
                mv.visitVarInsn(ALOAD, inlinedEnv()); iconst(a); iconst(b); rt("storeEnvE", "($OBJ${OBJ}II)V")
            } else { frame(); iconst(a); iconst(b); rt("storeEnv", "($OBJ${FRAME_D}II)V") }
            Op.LOAD_IMPORT -> {
                frame(); iconst(a); iconst(b); rt("loadEnv", "(${FRAME_D}II)$OBJ")
                mv.visitMethodInsn(INVOKESTATIC, "dev/mooner/neonjs/vm/Modules", "deref", "($OBJ)$OBJ", false)
            }
            Op.LOAD_ENV_TDZ, Op.CHECK_ENV_TDZ -> {
                if (inlinedEnv() >= 0) {
                    mv.visitVarInsn(ALOAD, inlinedEnv()); iconst(a); iconst(b); iconst(c); consts()
                    rt("loadEnvTdzE", "(${OBJ}III$CONSTS_D)$OBJ")
                } else {
                    frame(); iconst(a); iconst(b); iconst(c); consts()
                    rt("loadEnvTdz", "(${FRAME_D}III$CONSTS_D)$OBJ")
                }
                if (op == Op.CHECK_ENV_TDZ) mv.visitInsn(POP)
            }
            Op.LOAD_NAME -> { frame(); constant(a); rt("loadName", "($FRAME_D$OBJ)$OBJ") }
            Op.LOAD_NAME_TYPEOF -> { frame(); constant(a); rt("loadNameTypeof", "($FRAME_D$OBJ)$OBJ") }
            Op.LOAD_NAME_CALL -> {
                frame(); constant(a); rt("loadNameCall", "($FRAME_D$OBJ)$CONSTS_D")
                mv.visitInsn(DUP)
                iconst(0)
                mv.visitInsn(AALOAD)
                mv.visitInsn(SWAP)
                iconst(1)
                mv.visitInsn(AALOAD)
            }
            Op.STORE_NAME -> { frame(); constant(a); rt("storeName", "($OBJ$FRAME_D$OBJ)V") }
            Op.STORE_NAME_VAR -> { frame(); constant(a); rt("storeNameVar", "($OBJ$FRAME_D$OBJ)V") }
            Op.DELETE_NAME -> { frame(); constant(a); rt("deleteName", "($FRAME_D$OBJ)$OBJ") }
            Op.INIT_NAME -> { frame(); constant(a); rt("initName", "($OBJ$FRAME_D$OBJ)V") }
            Op.LOAD_GLOBAL -> { frame(); constant(a); rt("loadGlobal", "($FRAME_D$OBJ)$OBJ") }
            Op.LOAD_GLOBAL_TYPEOF -> { frame(); constant(a); rt("loadGlobalTypeof", "($FRAME_D$OBJ)$OBJ") }
            Op.STORE_GLOBAL -> { frame(); constant(a); rt("storeGlobal", "($OBJ$FRAME_D$OBJ)V") }
            Op.INIT_GLOBAL_LEX -> { frame(); constant(a); rt("initGlobalLex", "($OBJ$FRAME_D$OBJ)V") }
            Op.RESOLVE_NAME -> { frame(); constant(a); rt("resolveName", "($FRAME_D$OBJ)$OBJ") }
            Op.GET_REF -> { frame(); rt("getRef", "($OBJ$FRAME_D)$OBJ") }
            Op.PUT_REF -> { frame(); rt("putRef", "($OBJ$OBJ$FRAME_D)$OBJ") }
            // an inlined body's `this` was bound when it was entered
            Op.LOAD_THIS -> if (inlinedThis() >= 0) mv.visitVarInsn(ALOAD, inlinedThis()) else { frame(); mv.visitFieldInsn(GETFIELD, FRAME, "thisValue", OBJ) }
            Op.LOAD_FUNCTION -> { frame(); rt("loadFunction", "($FRAME_D)$OBJ") }
            Op.LOAD_NEW_TARGET -> { frame(); mv.visitFieldInsn(GETFIELD, FRAME, "newTarget", OBJ) }
            Op.LOAD_HOME -> { frame(); rt("loadHome", "($FRAME_D)$OBJ") }
            Op.CREATE_ARGUMENTS -> { frame(); iconst(a); rt("createArguments", "(${FRAME_D}I)$OBJ") }
            Op.CREATE_REST -> { frame(); iconst(a); rt("createRest", "(${FRAME_D}I)$OBJ") }
            Op.LOAD_ARG -> { frame(); iconst(a); rt("arg", "(${FRAME_D}I)$OBJ") }
            Op.INIT_THIS_ENV -> { frame(); iconst(a); iconst(b); rt("initThisEnv", "($OBJ${FRAME_D}II)V") }
            Op.INIT_THIS_NAME -> { frame(); rt("initThisName", "($OBJ$FRAME_D)V") }
            Op.THROW -> { rt("throwValue", "($OBJ)Ljava/lang/Throwable;"); mv.visitInsn(ATHROW) }
            Op.THROW_ERROR -> { iconst(a); iconst(b); consts(); rt("throwError", "(II$CONSTS_D)Ljava/lang/Throwable;"); mv.visitInsn(ATHROW) }
            Op.GET_PROP -> { constant(a); frame(); rt("getProp", "($OBJ$OBJ$FRAME_D)$OBJ") }
            Op.PUT_PROP -> { constant(a); frame(); rt("putProp", "($OBJ$OBJ$OBJ$FRAME_D)$OBJ") }
            Op.GET_ELEM -> { frame(); rt("getElem", "($OBJ$OBJ$FRAME_D)$OBJ") }
            Op.PUT_ELEM -> { frame(); rt("putElem", "($OBJ$OBJ$OBJ$FRAME_D)$OBJ") }
            Op.DELETE_PROP -> { constant(a); frame(); rt("deleteProp", "($OBJ$OBJ$FRAME_D)$OBJ") }
            Op.DELETE_ELEM -> { frame(); rt("deleteElem", "($OBJ$OBJ$FRAME_D)$OBJ") }
            Op.TO_PROPERTY_KEY -> rt("toPropertyKey", "($OBJ)$OBJ")
            Op.TO_KEY_FOR_BASE -> {
                // (obj key -- obj key')
                mv.visitInsn(DUP2)
                rt("keyForBase", "($OBJ$OBJ)$OBJ")
                mv.visitInsn(SWAP)
                mv.visitInsn(POP)
            }
            Op.GET_SUPER -> { frame(); rt("getSuper", "($OBJ$OBJ$OBJ$FRAME_D)$OBJ") }
            Op.PUT_SUPER -> { frame(); rt("putSuper", "($OBJ$OBJ$OBJ$OBJ$FRAME_D)$OBJ") }
            Op.GET_SUPER_BASE -> rt("superBase", "($OBJ)$OBJ")
            Op.GET_PRIVATE -> rt("getPrivate", "($OBJ$OBJ)$OBJ")
            Op.PUT_PRIVATE -> rt("putPrivate", "($OBJ$OBJ$OBJ)$OBJ")
            Op.HAS_PRIVATE -> rt("hasPrivate", "($OBJ$OBJ)$OBJ")
            Op.ADD -> rt("add", "($OBJ$OBJ)$OBJ")
            Op.SUB -> rt("sub", "($OBJ$OBJ)$OBJ")
            Op.MUL -> rt("mul", "($OBJ$OBJ)$OBJ")
            Op.DIV -> rt("div", "($OBJ$OBJ)$OBJ")
            Op.MOD -> rt("mod", "($OBJ$OBJ)$OBJ")
            Op.EXP -> rt("exp", "($OBJ$OBJ)$OBJ")
            Op.SHL -> rt("shl", "($OBJ$OBJ)$OBJ")
            Op.SAR -> rt("sar", "($OBJ$OBJ)$OBJ")
            Op.SHR -> rt("shr", "($OBJ$OBJ)$OBJ")
            Op.BAND -> rt("band", "($OBJ$OBJ)$OBJ")
            Op.BOR -> rt("bor", "($OBJ$OBJ)$OBJ")
            Op.BXOR -> rt("bxor", "($OBJ$OBJ)$OBJ")
            Op.EQ -> rt("eq", "($OBJ$OBJ)$OBJ")
            Op.NE -> rt("ne", "($OBJ$OBJ)$OBJ")
            Op.SEQ -> rt("seq", "($OBJ$OBJ)$OBJ")
            Op.SNE -> rt("sne", "($OBJ$OBJ)$OBJ")
            Op.LT -> rt("lt", "($OBJ$OBJ)$OBJ")
            Op.GT -> rt("gt", "($OBJ$OBJ)$OBJ")
            Op.LE -> rt("le", "($OBJ$OBJ)$OBJ")
            Op.GE -> rt("ge", "($OBJ$OBJ)$OBJ")
            Op.INSTANCEOF -> rt("instanceOf", "($OBJ$OBJ)$OBJ")
            Op.IN -> rt("inOp", "($OBJ$OBJ)$OBJ")
            Op.NEG -> rt("neg", "($OBJ)$OBJ")
            Op.TO_NUMBER -> rt("toNumber", "($OBJ)$OBJ")
            Op.NOT -> rt("not", "($OBJ)$OBJ")
            Op.BNOT -> rt("bnot", "($OBJ)$OBJ")
            Op.TYPEOF -> rt("typeOf", "($OBJ)$OBJ")
            Op.TO_NUMERIC -> rt("toNumeric", "($OBJ)$OBJ")
            Op.INC -> rt("inc", "($OBJ)$OBJ")
            Op.DEC -> rt("dec", "($OBJ)$OBJ")
            Op.TO_STRING -> rt("toStr", "($OBJ)$OBJ")
            Op.CONCAT -> rt("concat", "($OBJ$OBJ)$OBJ")
            Op.TO_OBJECT -> { frame(); rt("toObject", "($OBJ$FRAME_D)$OBJ") }
            Op.REQUIRE_COERCIBLE -> rt("requireCoercible", "($OBJ)$OBJ")
            Op.CHECK_OBJECT -> { iconst(a); consts(); rt("checkObject", "(${OBJ}I$CONSTS_D)$OBJ") }
            Op.DERIVED_RETURN -> rt("derivedReturn", "($OBJ$OBJ)$OBJ")
            Op.CALL -> {
                // from an inlined body: the frame's pc stays at the inlined call (icall* record the body's pc)
                val c = if (inInlinedBody) "icall" else "call"
                when (a) {
                    0 -> { frame(); iconst(pc); rt(c + "0", "($OBJ$OBJ${FRAME_D}I)$OBJ") }
                    1 -> { frame(); iconst(pc); rt(c + "1", "($OBJ$OBJ$OBJ${FRAME_D}I)$OBJ") }
                    2 -> { frame(); iconst(pc); rt(c + "2", "($OBJ$OBJ$OBJ$OBJ${FRAME_D}I)$OBJ") }
                    3 -> { frame(); iconst(pc); rt(c + "3", "($OBJ$OBJ$OBJ$OBJ$OBJ${FRAME_D}I)$OBJ") }
                    4 -> { frame(); iconst(pc); rt(c + "4", "($OBJ$OBJ$OBJ$OBJ$OBJ$OBJ${FRAME_D}I)$OBJ") }
                    else -> { packArray(a); frame(); iconst(pc); rt(c, "($OBJ$OBJ$CONSTS_D${FRAME_D}I)$OBJ") }
                }
            }
            Op.CALL_SPREAD -> { frame(); iconst(pc); rt("callSpread", "($OBJ$OBJ$OBJ${FRAME_D}I)$OBJ") }
            Op.NEW -> { packArray(a); frame(); iconst(pc); rt(if (inInlinedBody) "iconstruct" else "construct", "($OBJ$CONSTS_D${FRAME_D}I)$OBJ") }
            Op.NEW_SPREAD -> { frame(); iconst(pc); rt("newSpread", "($OBJ$OBJ${FRAME_D}I)$OBJ") }
            Op.CALL_EVAL -> {
                packArray(a); frame(); iconst(b); iconst(pc); rt("callEval", "($OBJ$OBJ$CONSTS_D${FRAME_D}II)$OBJ")
                if (b and 2 != 0) returnIfTail()
            }
            Op.TAIL_CALL -> {
                packArray(a); frame(); iconst(pc); rt("tailCall", "($OBJ$OBJ$CONSTS_D${FRAME_D}I)$OBJ")
                returnIfTail()
            }
            Op.CALL_EVAL_SPREAD -> { frame(); iconst(a); iconst(pc); rt("callEvalSpread", "($OBJ$OBJ$OBJ${FRAME_D}II)$OBJ") }
            Op.SUPER_CALL -> { packArray(a); frame(); iconst(pc); rt("superCall", "($OBJ$OBJ$CONSTS_D${FRAME_D}I)$OBJ") }
            Op.SUPER_CALL_SPREAD -> { frame(); iconst(pc); rt("superCallSpread", "($OBJ$OBJ$OBJ${FRAME_D}I)$OBJ") }
            Op.GET_PROTO_OF -> rt("getProtoOf", "($OBJ)$OBJ")
            Op.INIT_INSTANCE -> rt("initInstance", "($OBJ$OBJ)$OBJ")
            Op.NEW_OBJECT -> { frame(); rt("newObject", "($FRAME_D)$OBJ") }
            Op.NEW_OBJECT_LITERAL -> {
                // (v1..vn -- obj): move the values into an Object[] that becomes the object's values array
                iconst(b)
                mv.visitTypeInsn(ANEWARRAY, "java/lang/Object")
                for (i in b - 1 downTo 0) {
                    mv.visitInsn(DUP_X1)   // .. arr v arr
                    mv.visitInsn(SWAP)     // .. arr arr v
                    iconst(i)
                    mv.visitInsn(SWAP)     // .. arr arr i v
                    mv.visitInsn(AASTORE)  // .. arr
                }
                constant(a)
                frame()
                rt("newObjectLiteral", "([$OBJ$OBJ$FRAME_D)$OBJ")
            }
            Op.NEW_ARRAY -> { frame(); rt("newArray", "($FRAME_D)$OBJ") }
            Op.ARRAY_PUSH -> rt("arrayPush", "($OBJ$OBJ)$OBJ")
            Op.ARRAY_HOLE -> rt("arrayHole", "($OBJ)$OBJ")
            Op.ARRAY_SPREAD -> { frame(); rt("arraySpread", "($OBJ$OBJ$FRAME_D)$OBJ") }
            Op.DEFINE_FIELD -> { constant(a); rt("defineField", "($OBJ$OBJ$OBJ)$OBJ") }
            Op.DEFINE_FIELD_ELEM -> rt("defineFieldElem", "($OBJ$OBJ$OBJ)$OBJ")
            Op.DEFINE_GETTER, Op.DEFINE_SETTER, Op.DEFINE_METHOD -> { iconst(op); iconst(a); rt("defineMethod", "($OBJ$OBJ${OBJ}II)$OBJ") }
            Op.COPY_DATA_PROPS -> rt("copyDataProps", "($OBJ$OBJ)$OBJ")
            Op.COPY_DATA_PROPS_EXCL -> { packArray(a); rt("copyDataPropsExcl", "($OBJ$OBJ$CONSTS_D)$OBJ") }
            Op.SET_PROTO -> rt("setProto", "($OBJ$OBJ)$OBJ")
            Op.SET_FUNCTION_NAME -> { mv.visitInsn(DUP2); iconst(a); consts(); rt("setFunctionName", "($OBJ${OBJ}I$CONSTS_D)V") }
            Op.MAKE_CLOSURE -> { frame(); consts(); iconst(a); rt("makeClosure", "($FRAME_D${CONSTS_D}I)$OBJ") }
            Op.MAKE_CLASS -> {
                // stack: [heritage]? [name]? -> proto ctor
                val hasName = b and 2 != 0
                val hasHeritage = b and 1 != 0
                val tn = temp()
                if (hasName) mv.visitVarInsn(ASTORE, tn) else { mv.visitInsn(ACONST_NULL); mv.visitVarInsn(ASTORE, tn) }
                if (!hasHeritage) getstatic("dev/mooner/neonjs/runtime/NotFound", "INSTANCE", "Ldev/mooner/neonjs/runtime/NotFound;")
                mv.visitVarInsn(ALOAD, tn)
                frame(); consts(); iconst(a)
                rt("makeClass", "($OBJ$OBJ$FRAME_D${CONSTS_D}I)$CONSTS_D")
                mv.visitInsn(DUP)
                iconst(0)
                mv.visitInsn(AALOAD)
                mv.visitInsn(SWAP)
                iconst(1)
                mv.visitInsn(AALOAD)
            }
            Op.NEW_PRIVATE_NAME -> { constant(a); rt("newPrivateName", "($OBJ)$OBJ") }
            Op.ADD_FIELD -> rt("addField", "($OBJ$OBJ$OBJ)V")
            Op.ADD_PRIVATE_METHOD -> { iconst(a); rt("addPrivateMethod", "($OBJ$OBJ${OBJ}I)V") }
            Op.STATIC_PRIVATE_METHOD -> { iconst(a); rt("staticPrivateMethod", "($OBJ$OBJ${OBJ}I)V") }
            Op.RUN_FIELD -> { frame(); iconst(pc); rt("runField", "($OBJ$OBJ$OBJ${FRAME_D}I)V") }
            Op.GET_ITERATOR -> { frame(); rt("getIterator", "($OBJ$FRAME_D)$OBJ") }
            Op.FOR_IN_START -> { frame(); rt("forInStart", "($OBJ$FRAME_D)$OBJ") }
            Op.DEBUGGER -> { frame(); rt("debugger", "($FRAME_D)V") }
            Op.DISPOSE_NEW -> rt("disposeNew", "()$OBJ")
            Op.ADD_DISPOSABLE -> { iconst(a); frame(); rt("addDisposable", "($OBJ${OBJ}I$FRAME_D)V") }
            Op.DISPOSE_SYNC -> { frame(); rt("disposeSync", "($OBJ$OBJ$OBJ$FRAME_D)V") }
            Op.TEMPLATE_OBJECT -> { frame(); consts(); iconst(a); rt("templateObject", "($FRAME_D${CONSTS_D}I)$OBJ") }
            Op.NEW_REGEXP -> { frame(); consts(); iconst(a); rt("newRegExp", "($FRAME_D${CONSTS_D}I)$OBJ") }
            Op.IMPORT_META -> { frame(); rt("importMeta", "($FRAME_D)$OBJ") }
            Op.DYNAMIC_IMPORT -> { frame(); rt("dynamicImport", "($OBJ$OBJ$FRAME_D)$OBJ") }
            Op.CLASS_DEF_NEW -> rt("classDefNew", "($OBJ$OBJ)$OBJ")
            Op.CLASS_ELEMENT -> { iconst(a); rt("classElement", "($OBJ$OBJ$OBJ${OBJ}I)V") }
            Op.CLASS_FINISH -> rt("classFinish", "($OBJ)V")
            Op.CLASS_DECORATE -> rt("classDecorate", "($OBJ$OBJ)$OBJ")
            Op.CLASS_STATIC_INIT -> rt("classStaticInit", "($OBJ)V")
            Op.DYNAMIC_IMPORT_PHASE -> { frame(); constant(a); rt("dynamicImportPhase", "($OBJ$OBJ$FRAME_D$OBJ)$OBJ") }
            Op.DECLARE_GLOBALS -> { packArray(b); frame(); consts(); iconst(a); iconst(pc); rt("declareGlobals", "($CONSTS_D$FRAME_D${CONSTS_D}II)V") }
            Op.DECLARE_EVAL -> { packArray(b); frame(); consts(); iconst(a); iconst(pc); rt("declareEval", "($CONSTS_D$FRAME_D${CONSTS_D}II)V") }
            else -> return false
        }
        return true
    }
}
