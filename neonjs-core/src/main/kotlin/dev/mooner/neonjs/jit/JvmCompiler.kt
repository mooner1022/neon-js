package dev.mooner.neonjs.jit

import dev.mooner.neonjs.compiler.CodeBlock
import dev.mooner.neonjs.compiler.Op
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
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
    /**
     * Kind of each constant ([CONST_INT], [CONST_NUMBER] or [CONST_OTHER]). The generated code treats number constants
     * as `double`s and int32 ones as `int`s, so code blocks may share a class only if their constants have the same
     * kinds (the values may differ).
     */
    @JvmField val constKinds: ByteArray = ByteArray(cb.constants.size) { constKind(cb.constants[it]) }
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
        out.writeBoolean(JvmCompiler.TYPED)
        out.writeBoolean(JvmCompiler.INT32)
        out.writeBoolean(JvmCompiler.ELEMS)
        out.writeUTF(name)
        ints(code)
        ints(handlers)
        out.writeInt(numRegs)
        ints(paramRegs)
        out.writeInt(constKinds.size)
        out.write(constKinds)
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

    companion object {
        /** Changes when the code generator changes what it emits for the same input. */
        private const val FORMAT = 6
        const val CONST_OTHER: Byte = 0
        const val CONST_NUMBER: Byte = 1
        /** A number that is an int32 (and not -0). */
        const val CONST_INT: Byte = 2

        fun constKind(v: Any?): Byte = when {
            v !is Double -> CONST_OTHER
            v.toInt().toDouble() == v && !(v == 0.0 && 1.0 / v < 0) -> CONST_INT
            else -> CONST_NUMBER
        }
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
    init {
        // before the first generated class (including the definer probe) is linked: see ArtCha
        ArtCha.linkCompiledCodeImplementations()
    }

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

    private const val FRAME = "dev/mooner/neonjs/vm/Frame"
    private const val RT = "dev/mooner/neonjs/jit/JitRt"
    private const val OBJ = "Ljava/lang/Object;"
    private const val FRAME_D = "Ldev/mooner/neonjs/vm/Frame;"
    private const val AGENT = "dev/mooner/neonjs/runtime/Agent"
    private const val AGENT_D = "Ldev/mooner/neonjs/runtime/Agent;"
    private const val CONSTS_D = "[Ljava/lang/Object;"

    private const val L_FRAME = 1
    private const val L_CONSTS = 2
    private const val L_AGENT = 3
    private const val L_BUDGET = 4
    private const val L_REGS = 5

    /** Most instructions after a GET_ELEM compiled twice (Gen.planSplit). */
    private const val MAX_SPLIT = 6

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
    const val PROBE_CLASS = "dev.mooner.neonjs.jit.JSProbe"

    /** A trivial [CompiledCode] class (its run returns null) for testing a definer. */
    fun probeClass(version: Int): ByteArray {
        val cw = CW()
        cw.visit(version, ACC_PUBLIC or ACC_FINAL or ACC_SUPER, PROBE_CLASS.replace('.', '/'), null, "java/lang/Object", arrayOf("dev/mooner/neonjs/jit/CompiledCode"))
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
        val name = $$"dev/mooner/neonjs/jit/JS$$${input.name}$$$suffix"
        val bytes = try {
            classBytes(input, name, version, typed = TYPED)
        } catch (e: Exception) {
            // unboxed code is larger, and the type analysis gives up on some shapes: such blocks get the Object code,
            // as before it (deterministic, so equal inputs still give equal classes)
            if (!TYPED || e !is org.objectweb.asm.MethodTooLargeException && e !is JitBailout) throw e
            untypedReasons.computeIfAbsent(if (e is JitBailout) (e.message ?: "bailout").take(120) else "method too large") { AtomicLong() }
                .incrementAndGet()
            classBytes(input, name, version, typed = false)
        }
        DUMP_DIR?.let { dir -> runCatching { java.io.File(dir, name.substringAfterLast('/') + ".class").writeBytes(bytes) } }
        return name.replace('/', '.') to bytes
    }

    /**
     * Whether generated code keeps numbers in unboxed `double` locals (see [TypeAnalysis]); `-Dneonjs.jit.typed=false`
     * turns it off, giving the Object-only code of earlier versions.
     */
    internal val TYPED = System.getProperty("neonjs.jit.typed") != "false"

    /**
     * Whether unboxed code also keeps int32 values (bitwise results, integer literals) in `int` locals;
     * `-Dneonjs.jit.int32=false` turns that off, leaving them `double`s.
     */
    internal val INT32 = System.getProperty("neonjs.jit.int32") != "false"

    /**
     * Whether unboxed code stores numbers into typed arrays without boxing them (and keeps the assigned value unboxed),
     * and reads number elements without boxing them where they are used as numbers (emitSplit);
     * `-Dneonjs.jit.elem=false` turns both off.
     */
    internal val ELEMS = System.getProperty("neonjs.jit.elem") != "false"

    /** Debugging: `-Dneonjs.jit.dump=DIR` writes every generated class there. */
    private val DUMP_DIR: String? = System.getProperty("neonjs.jit.dump")

    /** Blocks compiled without unboxed values, by reason (see [generate]). */
    @JvmField val untypedReasons = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()

    private fun classBytes(input: JitInput, name: String, version: Int, typed: Boolean): ByteArray {
        val cw = CW()
        cw.visit(version, ACC_PUBLIC or ACC_FINAL or ACC_SUPER, name, null, "java/lang/Object", arrayOf("dev/mooner/neonjs/jit/CompiledCode"))
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
        Gen(input, mv, typed).emit()
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    internal fun sanitize(s: String): String {
        val sb = StringBuilder()
        for (c in s) if (c.isLetterOrDigit() || c == '_') sb.append(c)
        return if (sb.isEmpty()) "anon" else sb.take(40).toString()
    }

    private class Gen(val cb: JitInput, val mv: MethodVisitor, val typed: Boolean) {
        val code = cb.code
        val labels = HashMap<Int, Label>()
        val handlerLabels = HashMap<Int, Label>()
        /** Kinds of the operand stack and of the registers (JitTypes): which values are unboxed. */
        val ta: TypeAnalysis? = if (typed) TypeAnalysis(cb, INT32, ELEMS).run() else null
        /** How each register is held ([TypeAnalysis.regStorage]; Objects in untyped code). */
        val storage: ByteArray = ta?.regStorage ?: ByteArray(cb.numRegs)
        /** JVM local of each register kept as a `double` or an `int` (-1: an Object local, [reg]). */
        val slots = IntArray(cb.numRegs) { -1 }
        var nextTemp: Int
        /** Kinds of the values on the JVM operand stack at the current point of the generated code. */
        val st = KindStack()
        /** Instructions with a line table entry (the generated code stores the pc there), and their source lines. */
        val stmtStart = BooleanArray(code.size).also { s -> for (p in cb.statementPcs) if (p < s.size) s[p] = true }
        val stmtLine = IntArray(code.size) { -1 }.also { l ->
            val lines = cb.lines
            if (lines != null) for ((k, p) in cb.statementPcs.withIndex()) if (p < l.size) l[p] = lines[k]
        }
        /** Conditional jumps that must box values first: (label, stack kinds there, target pc), emitted at the end. */
        val trampolines = ArrayList<Triple<Label, ByteArray, Int>>()

        init {
            var n = L_REGS + cb.numRegs
            for (r in 0 until cb.numRegs) when (storage[r]) {
                JT.NUM -> { slots[r] = n; n += 2 }
                JT.INT -> { slots[r] = n; n += 1 }
            }
            nextTemp = n
        }

        fun label(pc: Int): Label = labels.getOrPut(pc) { Label() }

        fun temp(): Int = nextTemp++
        fun tempD(): Int = nextTemp.also { nextTemp += 2 }

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

        fun pushUndefined() = getstatic("dev/mooner/neonjs/runtime/Undefined", "INSTANCE", "Ldev/mooner/neonjs/runtime/Undefined;")

        /** Value on the stack is [dev.mooner.neonjs.vm.Interpreter.TAIL]: return it so the caller performs the tail call. */
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
                val target = h[i + 2]
                // a handler is reachable only if some instruction it protects is: no code is generated otherwise
                if (ta == null || ta.reachable(target)) {
                    val start = label(h[i])
                    val end = label(h[i + 1])
                    val hl = handlerLabels.getOrPut(target) { Label() }
                    mv.visitTryCatchBlock(start, end, hl, "java/lang/Throwable")
                }
                i += 4
            }
            // prologue
            frame()
            mv.visitFieldInsn(GETFIELD, FRAME, "code", "Ldev/mooner/neonjs/compiler/CodeBlock;")
            mv.visitFieldInsn(GETFIELD, "dev/mooner/neonjs/compiler/CodeBlock", "constants", CONSTS_D)
            mv.visitVarInsn(ASTORE, L_CONSTS)
            frame()
            mv.visitFieldInsn(GETFIELD, FRAME, "realm", "Ldev/mooner/neonjs/runtime/Realm;")
            mv.visitFieldInsn(GETFIELD, "dev/mooner/neonjs/runtime/Realm", "agent", "Ldev/mooner/neonjs/runtime/Agent;")
            mv.visitVarInsn(ASTORE, L_AGENT)
            iconst(4096)
            mv.visitVarInsn(ISTORE, L_BUDGET)
            for (r in 0 until cb.numRegs) {
                pushUndefined()
                mv.visitVarInsn(ASTORE, reg(r))
                // never read before a number is stored (TypeAnalysis); initialized for the verifier
                when (storage[r]) {
                    JT.NUM -> { mv.visitInsn(DCONST_0); mv.visitVarInsn(DSTORE, slots[r]) }
                    JT.INT -> { mv.visitInsn(ICONST_0); mv.visitVarInsn(ISTORE, slots[r]) }
                }
            }
            cb.paramRegs?.forEachIndexed { idx, r ->
                // a parameter register kept unboxed is never read before a number is stored in it
                if (r >= 0 && storage[r] == JT.ANY) {
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
                var newStatement = false
                while (si < stmts.size && stmts[si] <= pc) {
                    if (lines != null) curLine = lines[si]
                    si++
                    newStatement = true
                }
                if (ta != null && !ta.reachable(pc)) {
                    pc += Op.length(code, pc)
                    continue
                }
                handlerLabels[pc]?.let { hl ->
                    // exception entry: [Throwable] -> [value]
                    mv.visitLabel(hl)
                    frame()
                    rt("catchValue", "(Ljava/lang/Throwable;$FRAME_D)$OBJ")
                }
                if (ta != null) st.set(ta.stackIn[pc]!!)
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
                val op = code[pc]
                val split = if (ta != null && op == Op.GET_ELEM) planSplit(pc, ta) else null
                if (split != null) {
                    emitSplit(pc, split, ta!!)
                    // both copies stored the pc at the line table entries inside
                    while (si < stmts.size && stmts[si] < split.end) {
                        if (lines != null) curLine = lines[si]
                        si++
                    }
                    pc = split.end
                    continue
                }
                if (ta == null) emitGeneric(pc) else emitOp(pc, ta)
                val next = pc + Op.length(code, pc)
                // the fall-through edge: box what the next instruction expects boxed
                if (ta != null && fallsThrough(op) && ta.reachable(next)) convertTo(ta.stackIn[next]!!)
                pc = next
            }
            labels[pc]?.let { mv.visitLabel(it) }
            // falling off the end is impossible: emitter always ends with RETURN
            pushUndefined()
            mv.visitInsn(ARETURN)
            for ((l, kinds, target) in trampolines) {
                mv.visitLabel(l)
                st.set(kinds)
                convertTo(ta!!.stackIn[target]!!)
                mv.visitJumpInsn(GOTO, label(target))
            }
        }

        // ------------------------------------------------------------------ elements read as numbers

        /** A GET_ELEM compiled twice ([emitSplit]): the instructions after it up to [end], and whether its fast case gives an int. */
        private class Split(val end: Int, val int: Boolean)

        /**
         * Whether the GET_ELEM at [pc0] (with a number key) is followed by a few instructions that use its value as a
         * number, so that a fast case reading a number element without a box pays. The instructions are simulated with
         * the analysis's rules ([TypeAnalysis.stackStep]) for the element as a number and as the analysis has it (an
         * Object): the split ends where the two have the same kinds again, or where the fast case's convert to the
         * analysis's. The element's first use decides its kind: the ToInt32 int where it is a bitwise operator's
         * operand, else the double.
         */
        private fun planSplit(pc0: Int, ta: TypeAnalysis): Split? {
            if (!ta.elems) return null
            val in0 = ta.stackIn[pc0]!!
            if (in0.size < 2 || !JT.isNum(in0[in0.size - 1])) return null
            val asDouble = simulateSplit(pc0, JT.NUM, ta) ?: return null
            if (ta.ints && asDouble.bitwiseUse) return simulateSplit(pc0, JT.INT, ta)?.let { Split(it.end, true) }
            return Split(asDouble.end, false)
        }

        private class SplitSim(val end: Int, val bitwiseUse: Boolean)

        /** The end of a split with the element of kind [elem], or null when there is none worth making. */
        private fun simulateSplit(pc0: Int, elem: Byte, ta: TypeAnalysis): SplitSim? {
            var pc = pc0 + Op.length(code, pc0)
            val static = KindStack().also { it.set(ta.stackIn[pc]!!) }
            val fast = KindStack().also { it.set(ta.stackIn[pc0]!!); it.pop(); it.pop(); it.push(elem) }
            if (fast.size != static.size) return null
            var elemAt = fast.size - 1 // stack index of the element until it is used
            var bitwiseUse = false
            var end = -1
            var n = 0
            while (n < MAX_SPLIT && pc < code.size) {
                if (!ta.reachable(pc) || labels.containsKey(pc) || handlerLabels.containsKey(pc)) break
                if (!static.matches(ta.stackIn[pc]!!)) return null
                val op = code[pc]
                val a = if (Op.operands[op] >= 1) code[pc + 1] else 0
                val regKind = if (op == Op.LOAD_REG) ta.regKind(pc, a) else JT.ANY
                val before = fast.size
                if (!ta.stackStep(op, a, fast, regKind)) break
                ta.stackStep(op, a, static, regKind)
                if (elemAt >= 0 && elemAt >= before - stackPops(op)) {
                    if (op == Op.POP) return null // discarded: nothing to gain
                    bitwiseUse = JT.isBitwise(op) || op == Op.BNOT
                    elemAt = -1
                }
                pc += Op.length(code, pc)
                n++
                if (elemAt < 0) {
                    if (fast.matches(static.toArray())) {
                        end = pc
                        break
                    }
                    if (convertible(fast, static)) end = pc
                }
            }
            if (end < 0 || !ta.reachable(end)) return null
            return SplitSim(end, bitwiseUse)
        }

        /** Values consumed by the instructions [TypeAnalysis.stackStep] handles. */
        private fun stackPops(op: Int): Int = when (op) {
            Op.PUSH_INT, Op.PUSH_CONST, Op.LOAD_REG -> 0
            Op.POP, Op.NEG, Op.BNOT, Op.INC, Op.DEC, Op.TO_NUMERIC, Op.TO_NUMBER -> 1
            Op.PUT_ELEM -> 3
            else -> 2
        }

        /** Whether [convertTo] can turn the kinds [from] into [to]. */
        private fun convertible(from: KindStack, to: KindStack): Boolean {
            if (from.size != to.size) return false
            for (i in 0 until from.size) {
                val f = from[i]
                val t = to[i]
                if (f != t && t != JT.ANY && !(f == JT.INT && t == JT.NUM)) return false
            }
            return true
        }

        /**
         * The GET_ELEM at [pc0] and the instructions after it up to [s]'s end, compiled twice. For a typed array (tested
         * inline: the elements of other arrays are boxed already, so they gain nothing and take the slow case at once),
         * the fast case reads a Number element without boxing it (JitRt.elemNumI and the like) and compiles the
         * instructions with it unboxed; when that does not apply (index, BigInt type), the helper sets Agent.elemMiss,
         * and the slow case clears it. The slow case performs the whole [[Get]] (getElemI / getElemD) and compiles the
         * instructions as usual. Both end
         * with the analysis's kinds. The instructions in between have no labels or handler bounds, so both copies are
         * covered by the same exception handlers, and both store the pc at the line table entries among them.
         */
        private fun emitSplit(pc0: Int, s: Split, ta: TypeAnalysis) {
            // (obj key -- value): the key stays unboxed, and both are kept for the slow case
            convertTo(st.toArray().also { it[it.size - 2] = JT.ANY })
            val key = st.pop()
            st.pop()
            val tKey = storeTemp(key)
            val tObj = temp()
            mv.visitVarInsn(ASTORE, tObj)
            val below = st.toArray()
            val k = if (key == JT.INT) "I" else "D"
            val slowGet = Label()
            val miss = Label()
            val merge = Label()
            mv.visitVarInsn(ALOAD, tObj)
            mv.visitTypeInsn(INSTANCEOF, "dev/mooner/neonjs/builtins/JSTypedArray")
            mv.visitJumpInsn(IFEQ, slowGet)
            mv.visitVarInsn(ALOAD, tObj)
            loadTemp(key, tKey)
            mv.visitVarInsn(ALOAD, L_AGENT)
            if (s.int) rt("elemInt32$k", "($OBJ$k$AGENT_D)I") else rt("elemNum$k", "($OBJ$k$AGENT_D)D")
            mv.visitVarInsn(ALOAD, L_AGENT)
            mv.visitFieldInsn(GETFIELD, AGENT, "elemMiss", "Z")
            mv.visitJumpInsn(IFNE, miss)
            // fast case
            val first = pc0 + Op.length(code, pc0)
            st.set(below)
            st.push(if (s.int) JT.INT else JT.NUM)
            var pc = first
            while (pc < s.end) {
                storePc(pc)
                emitOp(pc, ta)
                pc += Op.length(code, pc)
            }
            convertTo(ta.stackIn[s.end]!!)
            mv.visitJumpInsn(GOTO, merge)
            // slow case: from a miss (drop the helper's 0, clear the flag), or from another receiver
            mv.visitLabel(miss)
            mv.visitInsn(if (s.int) POP else POP2)
            mv.visitVarInsn(ALOAD, L_AGENT)
            mv.visitInsn(ICONST_0)
            mv.visitFieldInsn(PUTFIELD, AGENT, "elemMiss", "Z")
            mv.visitLabel(slowGet)
            mv.visitVarInsn(ALOAD, tObj)
            loadTemp(key, tKey)
            frame()
            if (key == JT.INT) rt("getElemI", "(${OBJ}I$FRAME_D)$OBJ") else rt("getElemD", "(${OBJ}D$FRAME_D)$OBJ")
            st.set(below)
            st.push(JT.ANY)
            if (!st.matches(ta.stackIn[first]!!)) throw JitBailout("split at $pc0: stack kinds differ")
            pc = first
            while (pc < s.end) {
                st.set(ta.stackIn[pc]!!)
                storePc(pc)
                emitOp(pc, ta)
                val next = pc + Op.length(code, pc)
                convertTo(ta.stackIn[next]!!)
                pc = next
            }
            mv.visitLabel(merge)
        }

        /** In a split, what the main loop does at a line table entry: f.pc = pc (and the JVM line number). */
        private fun storePc(pc: Int) {
            if (!stmtStart[pc]) return
            frame()
            iconst(pc)
            mv.visitFieldInsn(PUTFIELD, FRAME, "pc", "I")
            if (stmtLine[pc] >= 0) {
                val l = Label()
                mv.visitLabel(l)
                mv.visitLineNumber(stmtLine[pc], l)
            }
        }

        private fun fallsThrough(op: Int) =
            op != Op.JUMP && op != Op.JUMP_TABLE && op != Op.RETURN && op != Op.THROW && op != Op.THROW_ERROR

        // ------------------------------------------------------------------ unboxed values

        /** Boxes the value of [kind] on top of the JVM stack into the Object the generic code expects. */
        private fun box(kind: Byte) {
            when (kind) {
                JT.NUM -> mv.visitMethodInsn(INVOKESTATIC, "dev/mooner/neonjs/runtime/Ops", "num", "(D)$OBJ", false)
                JT.INT -> mv.visitMethodInsn(INVOKESTATIC, "dev/mooner/neonjs/runtime/Ops", "num", "(I)$OBJ", false)
                JT.BOOL -> mv.visitMethodInsn(INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false)
            }
        }

        /** Converts the value of kind [from] on top of the JVM stack to kind [to]: unchanged, an int widened, or boxed. */
        private fun convert(from: Byte, to: Byte) {
            when {
                from == to -> {}
                to == JT.ANY -> box(from)
                from == JT.INT && to == JT.NUM -> mv.visitInsn(I2D)
                else -> throw JitBailout("cannot narrow a stack value")
            }
        }

        private fun popValue(kind: Byte) = mv.visitInsn(if (kind == JT.NUM) POP2 else POP)

        /** The int on top of the JVM stack as a double (unchanged if [kind] is NUM). */
        private fun toDouble(kind: Byte) = convert(kind, JT.NUM)

        /** ToInt32 of the number of [kind] on top of the JVM stack. */
        private fun toInt32(kind: Byte) {
            if (kind == JT.NUM) rt("toInt32D", "(D)I")
        }

        /** The Object on top of the JVM stack is a Double (TypeAnalysis says so): its double value. */
        private fun unboxNum() {
            mv.visitTypeInsn(CHECKCAST, "java/lang/Double")
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false)
        }

        private fun storeTemp(kind: Byte): Int = when (kind) {
            JT.NUM -> tempD().also { mv.visitVarInsn(DSTORE, it) }
            JT.BOOL, JT.INT -> temp().also { mv.visitVarInsn(ISTORE, it) }
            else -> temp().also { mv.visitVarInsn(ASTORE, it) }
        }

        private fun loadTemp(kind: Byte, t: Int) = mv.visitVarInsn(when (kind) { JT.NUM -> DLOAD; JT.BOOL, JT.INT -> ILOAD; else -> ALOAD }, t)

        /**
         * Makes the stack kinds [want] (same depth): every slot is unchanged, an int widened to a double, or boxed. Slots
         * above the lowest one to convert go through temporaries.
         */
        private fun convertTo(want: ByteArray) {
            if (want.size != st.size) throw JitBailout("operand stack depth differs")
            var low = -1
            for (k in 0 until st.size) if (st[k] != want[k]) {
                if (want[k] != JT.ANY && !(want[k] == JT.NUM && st[k] == JT.INT)) throw JitBailout("cannot narrow a stack value")
                if (low < 0) low = k
            }
            if (low < 0) return
            if (low == st.size - 1) {
                convert(st[low], want[low])
                st.setKind(low, want[low])
                return
            }
            val kinds = ByteArray(st.size - low) { st[low + it] }
            val temps = IntArray(kinds.size)
            for (k in kinds.size - 1 downTo 0) temps[k] = storeTemp(kinds[k])
            for (k in kinds.indices) {
                loadTemp(kinds[k], temps[k])
                convert(kinds[k], want[low + k])
                st.setKind(low + k, want[low + k])
            }
        }

        /** Widens the ints among the top [n] stack values to doubles. */
        private fun widenInts(n: Int) {
            convertTo(st.toArray().also { k -> for (i in k.size - n until k.size) if (k[i] == JT.INT) k[i] = JT.NUM })
        }

        private fun boxAll() = convertTo(ByteArray(st.size))

        /** Rearranges the top [n] slots as [order] (indices into them, 0 = deepest) through typed temporaries. */
        private fun permute(n: Int, order: IntArray) {
            val kinds = ByteArray(n) { st[st.size - n + it] }
            val temps = IntArray(n)
            for (k in n - 1 downTo 0) temps[k] = storeTemp(kinds[k])
            for (k in order) loadTemp(kinds[k], temps[k])
        }

        /** Pushes 0 or 1 for the comparison of the two doubles on the stack. */
        private fun compareDoubles(op: Int) {
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

        /** Jumps to [target] if the int on the stack satisfies [jump], boxing on the way what the target expects. */
        private fun branch(jump: Int, target: Int, ta: TypeAnalysis) {
            val want = ta.stackIn[target]!!
            if (st.matches(want)) {
                mv.visitJumpInsn(jump, label(target))
            } else {
                val t = Label()
                trampolines.add(Triple(t, st.toArray(), target))
                mv.visitJumpInsn(jump, t)
            }
        }

        private fun emitOp(pc: Int, ta: TypeAnalysis) {
            val op = code[pc]
            val a = if (Op.operands[op] >= 1) code[pc + 1] else 0
            when (op) {
                Op.PUSH_INT -> {
                    if (ta.intKind == JT.INT) {
                        iconst(a)
                    } else when (a) {
                        0 -> mv.visitInsn(DCONST_0)
                        1 -> mv.visitInsn(DCONST_1)
                        else -> mv.visitLdcInsn(a.toDouble())
                    }
                    st.push(ta.intKind)
                    return
                }
                Op.PUSH_CONST -> {
                    val k = ta.constKind(a)
                    if (JT.isNum(k)) {
                        constant(a)
                        unboxNum()
                        // an int32 (its kind is part of the class identity)
                        if (k == JT.INT) rt("exactInt", "(D)I")
                        st.push(k)
                        return
                    }
                }
                Op.PUSH_TRUE, Op.PUSH_FALSE -> {
                    mv.visitInsn(if (op == Op.PUSH_TRUE) ICONST_1 else ICONST_0)
                    st.push(JT.BOOL)
                    return
                }
                Op.LOAD_REG -> {
                    val k = ta.regKind(pc, a)
                    // TypeAnalysis chose the storage from the kinds of all loads: an int register is loaded only as INT, a
                    // double one only as a number (an int where every store reaching the load was one: exactInt)
                    when (storage[a]) {
                        JT.INT -> {
                            if (k != JT.INT) throw JitBailout("int register loaded as kind $k")
                            mv.visitVarInsn(ILOAD, slots[a])
                        }
                        JT.NUM -> {
                            if (!JT.isNum(k)) throw JitBailout("double register loaded as kind $k")
                            mv.visitVarInsn(DLOAD, slots[a])
                            if (k == JT.INT) rt("exactInt", "(D)I")
                        }
                        else -> {
                            mv.visitVarInsn(ALOAD, reg(a))
                            if (JT.isNum(k)) {
                                unboxNum()
                                if (k == JT.INT) rt("exactInt", "(D)I")
                            }
                        }
                    }
                    st.push(if (JT.isNum(k)) k else JT.ANY)
                    return
                }
                Op.STORE_REG -> {
                    val k = st.pop()
                    // a value of a kind the register is not held as is never loaded from it (TypeAnalysis): a dead store
                    when (storage[a]) {
                        JT.INT -> if (k == JT.INT) mv.visitVarInsn(ISTORE, slots[a]) else popValue(k)
                        JT.NUM -> if (JT.isNum(k)) {
                            toDouble(k)
                            mv.visitVarInsn(DSTORE, slots[a])
                        } else popValue(k)
                        else -> {
                            box(k)
                            mv.visitVarInsn(ASTORE, reg(a))
                        }
                    }
                    return
                }
                Op.POP -> {
                    popValue(st.pop())
                    return
                }
                Op.DUP -> {
                    val k = st.peek(0)
                    mv.visitInsn(if (k == JT.NUM) DUP2 else DUP)
                    st.push(k)
                    return
                }
                Op.DUP2, Op.DUP3, Op.SWAP, Op.ROT3, Op.ROT4 -> {
                    val (n, order) = when (op) {
                        Op.DUP2 -> 2 to TypeAnalysis.PERM_DUP2
                        Op.DUP3 -> 3 to TypeAnalysis.PERM_DUP3
                        Op.SWAP -> 2 to TypeAnalysis.PERM_SWAP
                        Op.ROT3 -> 3 to TypeAnalysis.PERM_ROT3
                        else -> 4 to TypeAnalysis.PERM_ROT4
                    }
                    if (st.allAny(n)) emitGeneric(pc) else permute(n, order)
                    st.permute(n, order)
                    return
                }
                Op.ADD, Op.SUB, Op.MUL, Op.DIV, Op.MOD, Op.EXP, Op.BAND, Op.BOR, Op.BXOR, Op.SHL, Op.SAR, Op.SHR,
                Op.LT, Op.GT, Op.LE, Op.GE, Op.EQ, Op.NE, Op.SEQ, Op.SNE -> {
                    val cls = JT.binaryClass(op, st.peek(1), st.peek(0))
                    if (cls != JT.GENERIC) {
                        val result = JT.binaryResult(op, cls, ta.intKind)
                        emitBinary(op, cls, result)
                        st.pop(); st.pop()
                        st.push(result)
                        return
                    }
                }
                Op.NEG, Op.BNOT, Op.INC, Op.DEC, Op.TO_NUMERIC -> {
                    val t = st.peek(0)
                    if (JT.isNum(t)) {
                        val result = ta.unaryResult(op, t)
                        when (op) {
                            Op.NEG -> { toDouble(t); mv.visitInsn(DNEG) } // -0 for 0
                            Op.BNOT -> if (result == JT.INT) {
                                toInt32(t)
                                mv.visitInsn(ICONST_M1)
                                mv.visitInsn(IXOR)
                            } else rt("bnotD", "(D)D") // without INT: as before it
                            Op.INC -> { toDouble(t); mv.visitInsn(DCONST_1); mv.visitInsn(DADD) }
                            Op.DEC -> { toDouble(t); mv.visitInsn(DCONST_1); mv.visitInsn(DSUB) }
                        }
                        st.pop()
                        st.push(result)
                        return
                    }
                }
                Op.TO_NUMBER -> {
                    val t = st.pop()
                    val result = ta.toNumberResult(t)
                    when (t) {
                        JT.NUM, JT.INT -> {}
                        JT.BOOL -> if (result == JT.NUM) mv.visitInsn(I2D)
                        else -> rt("toNumberD", "($OBJ)D")
                    }
                    st.push(result)
                    return
                }
                Op.NOT -> {
                    truth(st.pop(), normalized = true)
                    mv.visitInsn(ICONST_1)
                    mv.visitInsn(IXOR)
                    st.push(JT.BOOL)
                    return
                }
                Op.JUMP -> {
                    if (a <= pc) interruptCheck()
                    convertTo(ta.stackIn[a]!!)
                    mv.visitJumpInsn(GOTO, label(a))
                    return
                }
                Op.JUMP_IF_TRUE, Op.JUMP_IF_FALSE -> {
                    if (a <= pc) interruptCheck()
                    truth(st.pop())
                    branch(if (op == Op.JUMP_IF_TRUE) IFNE else IFEQ, a, ta)
                    return
                }
                Op.GET_ELEM -> {
                    val key = st.peek(0)
                    if (JT.isNum(key)) {
                        // (obj key -- value)
                        convertTo(st.toArray().also { it[it.size - 2] = JT.ANY })
                        frame()
                        if (key == JT.INT) rt("getElemI", "(${OBJ}I$FRAME_D)$OBJ") else rt("getElemD", "(${OBJ}D$FRAME_D)$OBJ")
                        st.pop(); st.pop()
                        st.push(JT.ANY)
                        return
                    }
                }
                Op.PUT_ELEM -> {
                    val key = st.peek(1)
                    val stored = ta.putElemResult(key, st.peek(0))
                    if (JT.isNum(stored)) {
                        // (obj key value -- value), the value unboxed
                        convertTo(st.toArray().also { it[it.size - 3] = JT.ANY })
                        frame()
                        val k = if (key == JT.INT) "I" else "D"
                        val v = if (stored == JT.INT) "I" else "D"
                        rt("setElem$k$v", "($OBJ$k$v$FRAME_D)$v")
                        st.pop(); st.pop(); st.pop()
                        st.push(stored)
                        return
                    }
                    if (JT.isNum(key)) {
                        // (obj key value -- value)
                        val vNum = key == JT.NUM && st.peek(0) == JT.NUM
                        convertTo(st.toArray().also { it[it.size - 3] = JT.ANY; if (!vNum) it[it.size - 1] = JT.ANY })
                        frame()
                        when {
                            key == JT.INT -> rt("putElemIA", "(${OBJ}I$OBJ$FRAME_D)$OBJ")
                            vNum -> rt("putElemDD", "(${OBJ}DD$FRAME_D)$OBJ")
                            else -> rt("putElemDA", "(${OBJ}D$OBJ$FRAME_D)$OBJ")
                        }
                        st.pop(); st.pop(); st.pop()
                        st.push(JT.ANY)
                        return
                    }
                }
            }
            // everything else: the Object code; its results are Objects
            val pops = JT.pops(op, a)
            if (pops >= 0) {
                // it consumes only the top values: box those, the ones below may stay unboxed
                if (pops > st.size) throw JitBailout("operand stack underflow at $pc")
                convertTo(st.toArray().also { k -> for (i in k.size - pops until k.size) k[i] = JT.ANY })
                val pushes = pops + effectOf(op, pc)
                emitGeneric(pc)
                repeat(pops) { st.pop() }
                repeat(pushes) { st.push(JT.ANY) }
                return
            }
            boxAll()
            val depth = st.size + effectOf(op, pc)
            emitGeneric(pc)
            st.setAllAny(depth)
        }

        /**
         * The truth value of the value of [kind] on the stack, as an int that is 0 for false: 0 or 1, or (for INT, unless
         * [normalized]) the int itself.
         */
        private fun truth(kind: Byte, normalized: Boolean = false) {
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

        /**
         * A binary operator with at least one number operand ([cls] NN, NA or AN) giving [result]; booleans are boxed
         * first. With INT, bitwise operators on two numbers are JVM int operations on their ToInt32s and comparisons of two
         * ints are int comparisons; everything else is done on doubles (ints are widened).
         */
        private fun emitBinary(op: Int, cls: Int, result: Byte) {
            if (st.peek(1) == JT.BOOL || st.peek(0) == JT.BOOL) {
                convertTo(st.toArray().also { k ->
                    if (k[k.size - 2] == JT.BOOL) k[k.size - 2] = JT.ANY
                    if (k[k.size - 1] == JT.BOOL) k[k.size - 1] = JT.ANY
                })
            }
            val left = st.peek(1)
            val right = st.peek(0)
            if (JT.isBitwise(op) && cls == JT.NN && ta!!.ints) {
                toInt32(right)
                if (left == JT.NUM) {
                    val t = temp()
                    mv.visitVarInsn(ISTORE, t)
                    toInt32(left)
                    mv.visitVarInsn(ILOAD, t)
                }
                // the JVM masks shift counts to 5 bits, as JS does
                when (op) {
                    Op.BAND -> mv.visitInsn(IAND)
                    Op.BOR -> mv.visitInsn(IOR)
                    Op.BXOR -> mv.visitInsn(IXOR)
                    Op.SHL -> mv.visitInsn(ISHL)
                    Op.SAR -> mv.visitInsn(ISHR)
                    else -> {
                        // >>>: the uint32 as a double
                        mv.visitInsn(IUSHR)
                        mv.visitInsn(I2L)
                        mv.visitLdcInsn(0xFFFFFFFFL)
                        mv.visitInsn(LAND)
                        mv.visitInsn(L2D)
                    }
                }
                return
            }
            if (JT.isCompare(op) && left == JT.INT && right == JT.INT) {
                compareInts(op)
                return
            }
            widenInts(2)
            val base = when (op) {
                Op.ADD -> "add"; Op.SUB -> "sub"; Op.MUL -> "mul"; Op.DIV -> "div"; Op.MOD -> "mod"; Op.EXP -> "exp"
                Op.BAND -> "band"; Op.BOR -> "bor"; Op.BXOR -> "bxor"; Op.SHL -> "shl"; Op.SAR -> "sar"; Op.SHR -> "shr"
                Op.LT -> "lt"; Op.GT -> "gt"; Op.LE -> "le"; Op.GE -> "ge"; Op.EQ -> "eq"; Op.NE -> "ne"
                Op.SEQ -> "seq"; else -> "sne"
            }
            // the helpers of the bitwise operators (but >>>) return the int32
            val intResult = JT.isBitwise(op) && op != Op.SHR
            val ret = when {
                JT.isCompare(op) -> "Z"
                op == Op.ADD && cls != JT.NN -> OBJ
                intResult -> "I"
                else -> "D"
            }
            when (cls) {
                JT.NN -> when (op) {
                    Op.ADD -> mv.visitInsn(DADD)
                    Op.SUB -> mv.visitInsn(DSUB)
                    Op.MUL -> mv.visitInsn(DMUL)
                    Op.DIV -> mv.visitInsn(DDIV)
                    Op.MOD -> mv.visitInsn(DREM) // fmod: the sign of the dividend, as JS %
                    Op.EXP -> rt("powDD", "(DD)D") // not Math.pow: 1 ** Infinity is NaN in JS
                    // bitwise ones without INT: as before it
                    else -> if (JT.isCompare(op)) compareDoubles(op) else rt(base + "DD", "(DD)D")
                }
                JT.NA -> if (JT.isCompare(op)) compareMixed(op, base, numLeft = true) else rt(base + "DA", "(D$OBJ)$ret")
                else -> if (JT.isCompare(op)) compareMixed(op, base, numLeft = false) else rt(base + "AD", "(${OBJ}D)$ret")
            }
            if (intResult && cls != JT.NN && result == JT.NUM) mv.visitInsn(I2D)
        }

        /** Pushes 0 or 1 for the comparison of the two ints on the stack. */
        private fun compareInts(op: Int) {
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
        private fun compareMixed(op: Int, base: String, numLeft: Boolean) {
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

        /** Stack effect of [op] at [pc] (as TypeAnalysis computes it). */
        private fun effectOf(op: Int, pc: Int): Int {
            val e = Op.effects[op]
            if (e != Op.VAR) return e
            val a = code[pc + 1]
            val b = if (Op.operands[op] >= 2) code[pc + 2] else 0
            return when (op) {
                Op.CALL, Op.TAIL_CALL, Op.CALL_EVAL, Op.SUPER_CALL, Op.COPY_DATA_PROPS_EXCL -> -(a + 1)
                Op.NEW -> -a
                Op.NEW_OBJECT_LITERAL -> 1 - b
                Op.MAKE_CLASS -> 2 - ((if (b and 1 != 0) 1 else 0) + (if (b and 2 != 0) 1 else 0))
                Op.DECLARE_GLOBALS, Op.DECLARE_EVAL -> -b
                else -> throw JitBailout("no stack effect for ${Op.names[op]}")
            }
        }

        private fun emitGeneric(pc: Int) {
            val op = code[pc]
            val a = if (Op.operands[op] >= 1) code[pc + 1] else 0
            val b = if (Op.operands[op] >= 2) code[pc + 2] else 0
            val c = if (Op.operands[op] >= 3) code[pc + 3] else 0
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
                Op.GET_ENV -> { frame(); mv.visitFieldInsn(GETFIELD, FRAME, "env", "Ldev/mooner/neonjs/runtime/Env;") }
                Op.SET_ENV -> { frame(); rt("setEnv", "($OBJ$FRAME_D)V") }
                Op.PUSH_WITH -> { frame(); rt("pushWith", "($OBJ$FRAME_D)V") }
                Op.LOAD_ENV -> { frame(); iconst(a); iconst(b); rt("loadEnv", "(${FRAME_D}II)$OBJ") }
                Op.STORE_ENV -> { frame(); iconst(a); iconst(b); rt("storeEnv", "($OBJ${FRAME_D}II)V") }
                Op.LOAD_IMPORT -> {
                    frame(); iconst(a); iconst(b); rt("loadEnv", "(${FRAME_D}II)$OBJ")
                    mv.visitMethodInsn(INVOKESTATIC, "dev/mooner/neonjs/vm/Modules", "deref", "($OBJ)$OBJ", false)
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
