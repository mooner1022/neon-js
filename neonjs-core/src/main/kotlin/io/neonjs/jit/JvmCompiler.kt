package io.neonjs.jit

import io.neonjs.compiler.CodeBlock
import io.neonjs.compiler.Op
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.atomic.AtomicLong

/**
 * Everything the class generated for a code block depends on, and nothing else: [JvmCompiler]'s code generator reads
 * only these fields, so [identity], a hash of all of them, decides whether two code blocks can share a class. Constants
 * are not part of it (generated code reads them from the running frame's code block at run time).
 */
class JitInput(cb: CodeBlock, debugInfo: Boolean = JvmCompiler.DEBUG_INFO) {
    /** Function name part of the class name (for profilers). */
    @JvmField val name: String = JvmCompiler.sanitize(cb.name)
    @JvmField val code: IntArray = cb.code
    @JvmField val handlers: IntArray = cb.handlers
    @JvmField val numRegs: Int = cb.numRegs
    @JvmField val paramRegs: IntArray? = cb.paramRegs
    /** Start pc of each statement (the generated code records the pc there). */
    @JvmField val statementPcs: IntArray
    /** Debug info: source line of each statement (or -1), and the source name; null without debug info. */
    @JvmField val lines: IntArray?
    @JvmField val sourceName: String?

    init {
        val lt = cb.lineTable
        statementPcs = IntArray(lt.size / 2) { lt[2 * it] }
        val src = cb.source
        if (debugInfo && src != null) {
            lines = IntArray(lt.size / 2) { val pos = lt[2 * it + 1]; if (pos >= 0) src.lineCol(pos).first else -1 }
            sourceName = src.name
        } else {
            lines = null
            sourceName = null
        }
    }

    /** 128 bits of the SHA-256 of all fields, in hex. */
    val identity: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val out = java.io.DataOutputStream(java.security.DigestOutputStream(NullOutput, md))
        fun ints(a: IntArray?) {
            if (a == null) return out.writeInt(-1)
            out.writeInt(a.size)
            for (v in a) out.writeInt(v)
        }
        out.writeInt(FORMAT)
        out.writeUTF(name)
        ints(code)
        ints(handlers)
        out.writeInt(numRegs)
        ints(paramRegs)
        ints(statementPcs)
        ints(lines)
        out.writeUTF(sourceName ?: "")
        out.flush()
        md.digest().copyOf(16).joinToString("") { "%02x".format(it) }
    }

    /** OutputStream.nullOutputStream needs JDK 11 / Android API 33. */
    private object NullOutput : java.io.OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }

    private companion object {
        /** Changes when the code generator changes what it emits for the same input. */
        const val FORMAT = 1
    }
}

/** Thrown when a code block cannot be compiled (unsupported instruction, method too large). */
class JitBailout(msg: String) : RuntimeException(msg, null, false, false)

/**
 * Translates NeonJS stack bytecode into a JVM class implementing [CompiledCode]. The JS operand stack maps onto the
 * JVM operand stack, registers onto JVM locals, and exception handler ranges onto JVM exception tables; complex
 * instructions call [JitRt]. Generator and async functions are not compiled (they stay interpreted).
 */
object JvmCompiler {
    private val counter = AtomicLong()
    /** Classes generated and defined. */
    @JvmField val compiledCount = AtomicLong()
    /** Code blocks given code compiled earlier for another block with the same identity ([CodeCache]). */
    @JvmField val sharedCount = AtomicLong()
    @JvmField val failedCount = AtomicLong()
    @JvmField val failureReasons = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()

    private fun fail(reason: String): CompiledCode? {
        failedCount.incrementAndGet()
        failureReasons.computeIfAbsent(reason.take(120)) { AtomicLong() }.incrementAndGet()
        return null
    }

    private const val FRAME = "io/neonjs/vm/Frame"
    private const val RT = "io/neonjs/jit/JitRt"
    private const val OBJ = "Ljava/lang/Object;"
    private const val FRAME_D = "Lio/neonjs/vm/Frame;"
    private const val CONSTS_D = "[Ljava/lang/Object;"

    private const val L_FRAME = 1
    private const val L_CONSTS = 2
    private const val L_AGENT = 3
    private const val L_BUDGET = 4
    private const val L_REGS = 5

    fun canCompile(cb: CodeBlock): Boolean = !cb.isGenerator && !cb.isAsync && cb.flags and CodeBlock.NO_JIT == 0

    /**
     * Compiles [cb] into a class defined by [definer], or reuses the code of a block with the same identity; returns
     * null (and counts the failure) when it cannot be compiled.
     */
    fun compile(cb: CodeBlock, definer: CodeDefiner = CodeDefiners.default): CompiledCode? = compileAll(listOf(cb), definer)[0]

    /**
     * Compiles [blocks] together: blocks with the same identity get one class, classes compiled before come from the
     * definer's [CodeCache], and the new classes are defined with one [CodeDefiner.defineAll] call (one dex file on
     * Android). Element i of the result is the code for `blocks[i]`, or null if it cannot be compiled. Never throws.
     */
    fun compileAll(blocks: List<CodeBlock>, definer: CodeDefiner): List<CompiledCode?> {
        val out = arrayOfNulls<CompiledCode>(blocks.size)
        val usable = CodeDefiners.isUsable(definer)
        // visible classes live in the engine's class loader under counter names: never shared
        val cache = if (JvmCodeDefiner.VISIBLE_CLASSES) null else CodeCache.of(definer)
        // identity -> input and the blocks wanting it, in order
        val inputs = LinkedHashMap<String, JitInput>()
        val wanting = HashMap<String, MutableList<Int>>()
        for ((i, cb) in blocks.withIndex()) {
            if (!canCompile(cb)) continue
            if (!usable) {
                fail("no usable code definer on this platform")
                continue
            }
            try {
                val input = JitInput(cb)
                val id = if (cache == null) "#$i" else input.identity
                val known = cache?.get(id)
                if (known != null) {
                    out[i] = known
                    sharedCount.incrementAndGet()
                    continue
                }
                inputs.putIfAbsent(id, input)
                wanting.getOrPut(id) { ArrayList(1) }.add(i)
            } catch (e: Throwable) {
                failure(e)
            }
        }
        if (inputs.isEmpty()) return out.asList()
        // generate
        val ids = ArrayList<String>(inputs.size)
        val classes = ArrayList<GeneratedClass>(inputs.size)
        for ((id, input) in inputs) {
            try {
                val (name, bytes) = generate(input, definer.classFileVersion)
                ids.add(id)
                classes.add(GeneratedClass(name, bytes))
            } catch (e: Throwable) {
                repeat(wanting[id]!!.size) { failure(e) }
            }
        }
        // define (one batch; if the definer rejects it, class by class so one bad class fails alone)
        val loader = JvmCompiler::class.java.classLoader
        val defined: List<Class<*>?> = try {
            definer.defineAll(classes, loader).also { check(it.size == classes.size) { "defineAll returned ${it.size} classes for ${classes.size}" } }
        } catch (e: Throwable) {
            if (classes.size == 1) {
                repeat(wanting[ids[0]]!!.size) { failure(e) }
                listOf(null)
            } else classes.mapIndexed { k, c ->
                try {
                    definer.define(c.name, c.bytes, loader)
                } catch (e2: Throwable) {
                    repeat(wanting[ids[k]]!!.size) { failure(e2) }
                    null
                }
            }
        }
        for ((k, cls) in defined.withIndex()) {
            if (cls == null) continue
            val id = ids[k]
            val code = try {
                cls.getDeclaredConstructor().newInstance() as CompiledCode
            } catch (e: Throwable) {
                repeat(wanting[id]!!.size) { failure(e) }
                continue
            }
            compiledCount.incrementAndGet()
            val shared = cache?.putIfAbsent(id, code) ?: code
            for ((n, i) in wanting[id]!!.withIndex()) {
                out[i] = shared
                if (n > 0) sharedCount.incrementAndGet()
            }
        }
        return out.asList()
    }

    private fun failure(e: Throwable) {
        when (e) {
            is JitBailout -> fail(e.message ?: "bailout")
            is org.objectweb.asm.MethodTooLargeException -> fail("method too large")
            else -> {
                if (System.getProperty("neonjs.jit.debug") != null) e.printStackTrace()
                fail("${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private class CW : ClassWriter(COMPUTE_FRAMES or COMPUTE_MAXS) {
        override fun getCommonSuperClass(type1: String, type2: String): String = "java/lang/Object"
    }

    /** Binary name of the class [CodeDefiners.isUsable] defines to test a definer. */
    const val PROBE_CLASS = "io.neonjs.jit.JSProbe"

    /** A trivial [CompiledCode] class (its run returns null) for testing a definer. */
    fun probeClass(version: Int): ByteArray {
        val cw = CW()
        cw.visit(version, ACC_PUBLIC or ACC_FINAL or ACC_SUPER, PROBE_CLASS.replace('.', '/'), null, "java/lang/Object", arrayOf("io/neonjs/jit/CompiledCode"))
        val init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null)
        init.visitCode()
        init.visitVarInsn(ALOAD, 0)
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        init.visitInsn(RETURN)
        init.visitMaxs(0, 0)
        init.visitEnd()
        val mv = cw.visitMethod(ACC_PUBLIC or ACC_FINAL, "run", "($FRAME_D)$OBJ", null, null)
        mv.visitCode()
        mv.visitInsn(ACONST_NULL)
        mv.visitInsn(ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    /**
     * Whether generated classes carry the source file name and line numbers (JVM debug info). JS stack traces do not use
     * them (they come from the frame's pc), only JVM tools do; without them, code blocks that differ only in their
     * position in the source get the same class. Enabled with `-Dneonjs.jit.debugInfo` and with visible classes.
     */
    @JvmField val DEBUG_INFO = JvmCodeDefiner.VISIBLE_CLASSES || System.getProperty("neonjs.jit.debugInfo") != null

    /**
     * Generates the class for [input] with class file [version]; returns its binary name and bytes. The name ends with
     * the input's [JitInput.identity]: equal names mean equal class files, in every run, so classes can be shared
     * between code blocks and a dex-converting definer can cache conversions across launches.
     */
    fun generate(input: JitInput, version: Int = V17): Pair<String, ByteArray> {
        val suffix = if (JvmCodeDefiner.VISIBLE_CLASSES) counter.incrementAndGet().toString() else input.identity
        val name = "io/neonjs/jit/JS\$" + input.name + "\$" + suffix
        val cw = CW()
        cw.visit(version, ACC_PUBLIC or ACC_FINAL or ACC_SUPER, name, null, "java/lang/Object", arrayOf("io/neonjs/jit/CompiledCode"))
        input.sourceName?.let { cw.visitSource(it, null) }
        val init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null)
        init.visitCode()
        init.visitVarInsn(ALOAD, 0)
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        init.visitInsn(RETURN)
        init.visitMaxs(0, 0)
        init.visitEnd()
        val mv = cw.visitMethod(ACC_PUBLIC or ACC_FINAL, "run", "($FRAME_D)$OBJ", null, null)
        mv.visitCode()
        Gen(input, mv).emit()
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        return name.replace('/', '.') to cw.toByteArray()
    }

    internal fun sanitize(s: String): String {
        val sb = StringBuilder()
        for (c in s) if (c.isLetterOrDigit() || c == '_') sb.append(c)
        return if (sb.isEmpty()) "anon" else sb.take(40).toString()
    }

    private class Gen(val cb: JitInput, val mv: MethodVisitor) {
        val code = cb.code
        val labels = HashMap<Int, Label>()
        val handlerLabels = HashMap<Int, Label>()
        var nextTemp = L_REGS + cb.numRegs

        fun label(pc: Int): Label = labels.getOrPut(pc) { Label() }

        fun temp(): Int = nextTemp++

        fun reg(r: Int) = L_REGS + r

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
        fun consts() = mv.visitVarInsn(ALOAD, L_CONSTS)

        fun constant(k: Int) {
            consts()
            iconst(k)
            mv.visitInsn(AALOAD)
        }

        fun getstatic(owner: String, field: String, desc: String) = mv.visitFieldInsn(GETSTATIC, owner, field, desc)

        fun pushUndefined() = getstatic("io/neonjs/runtime/Undefined", "INSTANCE", "Lio/neonjs/runtime/Undefined;")

        /** Pops [n] stack values into a fresh Object[] (keeping order). */
        /** Value on the stack is [io.neonjs.vm.Interpreter.TAIL]: return it so the caller performs the tail call. */
        fun returnIfTail() {
            mv.visitInsn(DUP)
            getstatic("io/neonjs/vm/Interpreter", "TAIL", OBJ)
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

        fun interruptCheck() {
            val ok = Label()
            mv.visitIincInsn(L_BUDGET, -1)
            mv.visitVarInsn(ILOAD, L_BUDGET)
            mv.visitJumpInsn(IFGT, ok)
            mv.visitVarInsn(ALOAD, L_AGENT)
            mv.visitMethodInsn(INVOKEVIRTUAL, "io/neonjs/runtime/Agent", "checkInterrupt", "()V", false)
            iconst(4096)
            mv.visitVarInsn(ISTORE, L_BUDGET)
            mv.visitLabel(ok)
        }

        fun boolToObj() = mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)

        fun emit() {
            // collect labels
            var pc = 0
            while (pc < code.size) {
                val op = code[pc]
                val len = Op.length(code, pc)
                if (op == Op.JUMP_TABLE) {
                    val n = code[pc + 2]
                    for (i in 0..n) label(code[pc + 3 + i])
                } else if (Op.isJump(op)) label(code[pc + len - 1])
                pc += len
            }
            val h = cb.handlers
            var i = 0
            while (i < h.size) {
                val start = label(h[i])
                val end = label(h[i + 1])
                val target = h[i + 2]
                val hl = handlerLabels.getOrPut(target) { Label() }
                mv.visitTryCatchBlock(start, end, hl, "java/lang/Throwable")
                i += 4
            }
            // prologue
            frame()
            mv.visitFieldInsn(GETFIELD, FRAME, "code", "Lio/neonjs/compiler/CodeBlock;")
            mv.visitFieldInsn(GETFIELD, "io/neonjs/compiler/CodeBlock", "constants", CONSTS_D)
            mv.visitVarInsn(ASTORE, L_CONSTS)
            frame()
            mv.visitFieldInsn(GETFIELD, FRAME, "realm", "Lio/neonjs/runtime/Realm;")
            mv.visitFieldInsn(GETFIELD, "io/neonjs/runtime/Realm", "agent", "Lio/neonjs/runtime/Agent;")
            mv.visitVarInsn(ASTORE, L_AGENT)
            iconst(4096)
            mv.visitVarInsn(ISTORE, L_BUDGET)
            for (r in 0 until cb.numRegs) {
                pushUndefined()
                mv.visitVarInsn(ASTORE, reg(r))
            }
            cb.paramRegs?.forEachIndexed { idx, r ->
                if (r >= 0) {
                    frame()
                    iconst(idx)
                    rt("arg", "($FRAME_D I)$OBJ".replace(" ", ""))
                    mv.visitVarInsn(ASTORE, reg(r))
                }
            }
            pc = 0
            var lastLine = -1
            val stmts = cb.statementPcs
            val lines = cb.lines
            var si = 0
            var curLine = -1
            while (pc < code.size) {
                labels[pc]?.let { mv.visitLabel(it) }
                handlerLabels[pc]?.let { hl ->
                    // exception entry: [Throwable] -> [value]
                    mv.visitLabel(hl)
                    frame()
                    rt("catchValue", "(Ljava/lang/Throwable;$FRAME_D)$OBJ")
                }
                var newStatement = false
                while (si < stmts.size && stmts[si] <= pc) {
                    if (lines != null) curLine = lines[si]
                    si++
                    newStatement = true
                }
                if (newStatement) {
                    // f.pc = pc at each statement, so errors raised between calls point at the right statement
                    frame()
                    iconst(pc)
                    mv.visitFieldInsn(PUTFIELD, FRAME, "pc", "I")
                }
                if (curLine >= 0 && curLine != lastLine) {
                    val ll = Label()
                    mv.visitLabel(ll)
                    mv.visitLineNumber(curLine, ll)
                    lastLine = curLine
                }
                emitOp(pc)
                pc += Op.length(code, pc)
            }
            labels[pc]?.let { mv.visitLabel(it) }
            // falling off the end is impossible: emitter always ends with RETURN
            pushUndefined()
            mv.visitInsn(ARETURN)
        }

        private fun emitOp(pc: Int) {
            val op = code[pc]
            val a = if (Op.operands[op] >= 1) code[pc + 1] else 0
            val b = if (Op.operands[op] >= 2) code[pc + 2] else 0
            val c = if (Op.operands[op] >= 3) code[pc + 3] else 0
            when (op) {
                Op.NOP -> {}
                Op.PUSH_UNDEF -> pushUndefined()
                Op.PUSH_NULL -> getstatic("io/neonjs/runtime/Null", "INSTANCE", "Lio/neonjs/runtime/Null;")
                Op.PUSH_TRUE -> getstatic("java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;")
                Op.PUSH_FALSE -> getstatic("java/lang/Boolean", "FALSE", "Ljava/lang/Boolean;")
                Op.PUSH_CONST -> constant(a)
                Op.PUSH_INT -> {
                    iconst(a)
                    mv.visitMethodInsn(INVOKESTATIC, "io/neonjs/runtime/Ops", "num", "(I)$OBJ", false)
                }
                Op.POP -> mv.visitInsn(POP)
                Op.DUP -> mv.visitInsn(DUP)
                Op.DUP2 -> mv.visitInsn(DUP2)
                Op.DUP3 -> {
                    val t3 = temp(); val t2 = temp(); val t1 = temp()
                    mv.visitVarInsn(ASTORE, t3); mv.visitVarInsn(ASTORE, t2); mv.visitVarInsn(ASTORE, t1)
                    for (t in intArrayOf(t1, t2, t3, t1, t2, t3)) mv.visitVarInsn(ALOAD, t)
                }
                Op.SWAP -> mv.visitInsn(SWAP)
                Op.ROT3 -> { mv.visitInsn(DUP_X2); mv.visitInsn(POP) }
                Op.ROT4 -> {
                    val t4 = temp(); val t3 = temp(); val t2 = temp(); val t1 = temp()
                    mv.visitVarInsn(ASTORE, t4); mv.visitVarInsn(ASTORE, t3); mv.visitVarInsn(ASTORE, t2); mv.visitVarInsn(ASTORE, t1)
                    for (t in intArrayOf(t4, t1, t2, t3)) mv.visitVarInsn(ALOAD, t)
                }
                Op.LOAD_REG -> mv.visitVarInsn(ALOAD, reg(a))
                Op.STORE_REG -> mv.visitVarInsn(ASTORE, reg(a))
                Op.LOAD_REG_TDZ, Op.CHECK_REG_TDZ -> {
                    mv.visitVarInsn(ALOAD, reg(a))
                    iconst(b)
                    consts()
                    rt("tdz", "(${OBJ}I$CONSTS_D)$OBJ")
                    if (op == Op.CHECK_REG_TDZ) mv.visitInsn(POP)
                }
                Op.PUSH_SCOPE -> { frame(); consts(); iconst(a); rt("pushScope", "(${FRAME_D}${CONSTS_D}I)V") }
                Op.POP_SCOPE -> { frame(); rt("popScope", "($FRAME_D)V") }
                Op.COPY_SCOPE -> { frame(); rt("copyScope", "($FRAME_D)V") }
                Op.GET_ENV -> { frame(); mv.visitFieldInsn(GETFIELD, FRAME, "env", "Lio/neonjs/runtime/Env;") }
                Op.SET_ENV -> { frame(); rt("setEnv", "($OBJ$FRAME_D)V") }
                Op.PUSH_WITH -> { frame(); rt("pushWith", "($OBJ$FRAME_D)V") }
                Op.LOAD_ENV -> { frame(); iconst(a); iconst(b); rt("loadEnv", "(${FRAME_D}II)$OBJ") }
                Op.STORE_ENV -> { frame(); iconst(a); iconst(b); rt("storeEnv", "($OBJ${FRAME_D}II)V") }
                Op.LOAD_IMPORT -> {
                    frame(); iconst(a); iconst(b); rt("loadEnv", "(${FRAME_D}II)$OBJ")
                    mv.visitMethodInsn(INVOKESTATIC, "io/neonjs/vm/Modules", "deref", "($OBJ)$OBJ", false)
                }
                Op.LOAD_ENV_TDZ, Op.CHECK_ENV_TDZ -> {
                    frame(); iconst(a); iconst(b); iconst(c); consts()
                    rt("loadEnvTdz", "(${FRAME_D}III$CONSTS_D)$OBJ")
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
                Op.LOAD_THIS -> { frame(); mv.visitFieldInsn(GETFIELD, FRAME, "thisValue", OBJ) }
                Op.LOAD_FUNCTION -> { frame(); rt("loadFunction", "($FRAME_D)$OBJ") }
                Op.LOAD_NEW_TARGET -> { frame(); mv.visitFieldInsn(GETFIELD, FRAME, "newTarget", OBJ) }
                Op.LOAD_HOME -> { frame(); rt("loadHome", "($FRAME_D)$OBJ") }
                Op.CREATE_ARGUMENTS -> { frame(); iconst(a); rt("createArguments", "(${FRAME_D}I)$OBJ") }
                Op.CREATE_REST -> { frame(); iconst(a); rt("createRest", "(${FRAME_D}I)$OBJ") }
                Op.LOAD_ARG -> { frame(); iconst(a); rt("arg", "(${FRAME_D}I)$OBJ") }
                Op.INIT_THIS_REG -> {
                    mv.visitVarInsn(ALOAD, reg(a))
                    frame()
                    rt("initThisReg", "($OBJ$OBJ$FRAME_D)$OBJ")
                    mv.visitVarInsn(ASTORE, reg(a))
                }
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
                Op.JUMP -> {
                    if (a <= pc) interruptCheck()
                    mv.visitJumpInsn(GOTO, label(a))
                }
                Op.JUMP_IF_TRUE, Op.JUMP_IF_FALSE -> {
                    if (a <= pc) interruptCheck()
                    rt("truthy", "($OBJ)Z")
                    mv.visitJumpInsn(if (op == Op.JUMP_IF_TRUE) IFNE else IFEQ, label(a))
                }
                Op.JUMP_IF_NULLISH, Op.JUMP_IF_NOT_NULLISH -> {
                    rt("isNullish", "($OBJ)Z")
                    mv.visitJumpInsn(if (op == Op.JUMP_IF_NULLISH) IFNE else IFEQ, label(a))
                }
                Op.JUMP_IF_UNDEFINED, Op.JUMP_IF_NOT_UNDEFINED -> {
                    rt("isUndefined", "($OBJ)Z")
                    mv.visitJumpInsn(if (op == Op.JUMP_IF_UNDEFINED) IFNE else IFEQ, label(a))
                }
                Op.JUMP_TABLE -> {
                    val r = code[pc + 1]
                    val n = code[pc + 2]
                    mv.visitVarInsn(ALOAD, reg(r))
                    rt("toIntIndex", "($OBJ)I")
                    val targets = Array(n) { label(code[pc + 3 + it]) }
                    if (n == 0) {
                        mv.visitInsn(POP)
                        mv.visitJumpInsn(GOTO, label(code[pc + 3]))
                    } else mv.visitTableSwitchInsn(0, n - 1, label(code[pc + 3 + n]), *targets)
                }
                Op.RETURN -> mv.visitInsn(ARETURN)
                Op.CALL -> {
                    when (a) {
                        0 -> { frame(); iconst(pc); rt("call0", "($OBJ$OBJ${FRAME_D}I)$OBJ") }
                        1 -> { frame(); iconst(pc); rt("call1", "($OBJ$OBJ$OBJ${FRAME_D}I)$OBJ") }
                        2 -> { frame(); iconst(pc); rt("call2", "($OBJ$OBJ$OBJ$OBJ${FRAME_D}I)$OBJ") }
                        3 -> { frame(); iconst(pc); rt("call3", "($OBJ$OBJ$OBJ$OBJ$OBJ${FRAME_D}I)$OBJ") }
                        4 -> { frame(); iconst(pc); rt("call4", "($OBJ$OBJ$OBJ$OBJ$OBJ$OBJ${FRAME_D}I)$OBJ") }
                        else -> { packArray(a); frame(); iconst(pc); rt("call", "($OBJ$OBJ$CONSTS_D${FRAME_D}I)$OBJ") }
                    }
                }
                Op.CALL_SPREAD -> { frame(); iconst(pc); rt("callSpread", "($OBJ$OBJ$OBJ${FRAME_D}I)$OBJ") }
                Op.NEW -> { packArray(a); frame(); iconst(pc); rt("construct", "($OBJ$CONSTS_D${FRAME_D}I)$OBJ") }
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
                Op.MAKE_METHOD -> { mv.visitVarInsn(ALOAD, reg(b)); frame(); consts(); iconst(a); rt("makeMethod", "($OBJ$FRAME_D${CONSTS_D}I)$OBJ") }
                Op.MAKE_CLASS -> {
                    // stack: [heritage]? [name]? -> proto ctor
                    val hasName = b and 2 != 0
                    val hasHeritage = b and 1 != 0
                    val tn = temp()
                    if (hasName) mv.visitVarInsn(ASTORE, tn) else { mv.visitInsn(ACONST_NULL); mv.visitVarInsn(ASTORE, tn) }
                    if (!hasHeritage) getstatic("io/neonjs/runtime/NotFound", "INSTANCE", "Lio/neonjs/runtime/NotFound;")
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
                Op.ITER_STEP -> {
                    mv.visitVarInsn(ALOAD, reg(a))
                    rt("iterStep", "($OBJ)$OBJ")
                    mv.visitInsn(DUP)
                    rt("isNotFound", "($OBJ)Z")
                    val cont = Label()
                    mv.visitJumpInsn(IFEQ, cont)
                    mv.visitInsn(POP)
                    mv.visitJumpInsn(GOTO, label(b))
                    mv.visitLabel(cont)
                }
                Op.ITER_STEP_U -> { mv.visitVarInsn(ALOAD, reg(a)); rt("iterStepU", "($OBJ)$OBJ") }
                Op.ITER_REST -> { mv.visitVarInsn(ALOAD, reg(a)); frame(); rt("iterRest", "($OBJ$FRAME_D)$OBJ") }
                Op.ITER_CLOSE -> { mv.visitVarInsn(ALOAD, reg(a)); rt("iterClose", "($OBJ)V") }
                Op.ITER_CLOSE_THROW -> { mv.visitVarInsn(ALOAD, reg(a)); rt("iterCloseThrow", "($OBJ)V") }
                Op.FOR_IN_START -> { frame(); rt("forInStart", "($OBJ$FRAME_D)$OBJ") }
                Op.FOR_IN_NEXT -> {
                    mv.visitVarInsn(ALOAD, reg(a))
                    rt("forInNext", "($OBJ)$OBJ")
                    mv.visitInsn(DUP)
                    val cont = Label()
                    mv.visitJumpInsn(IFNONNULL, cont)
                    mv.visitInsn(POP)
                    mv.visitJumpInsn(GOTO, label(b))
                    mv.visitLabel(cont)
                }
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
                else -> throw JitBailout("unsupported op ${Op.names[op]}")
            }
        }
    }
}
