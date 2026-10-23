package dev.mooner.neonjs.jit

import dev.mooner.neonjs.compiler.CodeBlock
import dev.mooner.neonjs.compiler.Op
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*
import java.util.concurrent.atomic.AtomicLong

/**
 * Everything the class generated for a code block depends on, and nothing else: [JvmCompiler]'s code generator reads
 * only these fields, so [identity], a hash of all of them, decides whether two code blocks can share a class. Constants
 * are not part of it (generated code reads them from the running frame's code block at run time).
 */
class JitInput(
    cb: CodeBlock,
    debugInfo: Boolean = JvmCompiler.DEBUG_INFO,
    planInlining: Boolean = true,
    /**
     * Whether the code moves to a later version of its block's code (the optimizing tier's) from its loops
     * (Gen.tierUpCheck): with the tier on.
     */
    @JvmField val tierUp: Boolean = false,
) {
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
    /**
     * Calls compiled with the callee's body inlined (Inlining), by site k: the pc of the CALL and the callee's input
     * (part of [identity]). [inlineTargets] are the callees themselves, for `CodeBlock.inlineTargets`.
     */
    @JvmField val inlineSites: List<Pair<Int, JitInput>>
    @JvmField val inlineTargets: Array<CodeBlock?>?
    /** Start pc of each statement (the generated code records the pc there). */
    @JvmField val statementPcs: IntArray
    /** Debug info: source line of each statement (or -1), and the source name; null without debug info. */
    @JvmField val lines: IntArray?
    @JvmField val sourceName: String?
    /** Whether the code gets the deopt stress mode's exits ([JvmCompiler.DEOPT_STRESS]). */
    @JvmField val deoptStress: Boolean = JvmCompiler.DEOPT_STRESS > 0

    init {
        val plan = if (planInlining && JvmCompiler.TYPED) Inlining.plan(cb) else emptyList()
        inlineSites = plan.map { (pc, callee) -> pc to JitInput(callee, debugInfo = false, planInlining = false) }
        inlineTargets = if (plan.isEmpty()) null else Array(plan.size) { plan[it].second }
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
        out.writeInt(if (deoptStress) -FORMAT else FORMAT)
        out.writeBoolean(JvmCompiler.TYPED)
        out.writeBoolean(JvmCompiler.INT32)
        out.writeBoolean(JvmCompiler.ELEMS)
        out.writeBoolean(Inlining.ENABLED)
        out.writeInt(inlineSites.size)
        for ((pc, callee) in inlineSites) {
            out.writeInt(pc)
            out.writeUTF(callee.identity)
        }
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
        if (tierUp) out.writeUTF("tierUp")
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
        private const val FORMAT = 8
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

    /** Most instructions after a GET_ELEM compiled twice (Gen.planSplit). */
    private const val MAX_SPLIT = 6

    fun canCompile(cb: CodeBlock): Boolean = !cb.isGenerator && !cb.isAsync && cb.flags and CodeBlock.NO_JIT == 0

    /**
     * The targets of the backward jumps (`JUMP`, `JUMP_IF_TRUE`, `JUMP_IF_FALSE`) in [code], sorted: the loop headers
     * where the interpreter may continue in compiled code (Jit.osr), and only there.
     */
    internal fun backEdgeTargets(code: IntArray): IntArray {
        val t = java.util.TreeSet<Int>()
        var pc = 0
        while (pc < code.size) {
            val op = code[pc]
            if ((op == Op.JUMP || op == Op.JUMP_IF_TRUE || op == Op.JUMP_IF_FALSE) && code[pc + 1] <= pc) t.add(code[pc + 1])
            pc += Op.length(code, pc)
        }
        return t.toIntArray()
    }

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
    fun compileAll(blocks: List<CodeBlock>, definer: CodeDefiner, tierUp: Boolean = false): List<CompiledCode?> {
        val out = arrayOfNulls<CompiledCode>(blocks.size)
        val usable = CodeDefiners.isUsable(definer)
        // visible classes live in the engine's class loader under counter names: never shared
        val cache = if (JvmCodeDefiner.VISIBLE_CLASSES) null else CodeCache.of(definer)
        // identity -> input and the blocks wanting it, in order
        val inputs = LinkedHashMap<String, JitInput>()
        val wanting = HashMap<String, MutableList<Int>>()
        for ((i, cb) in blocks.withIndex()) {
            // a block's baseline is never replaced. Jit.prepare reads `compiled` before `jitTask`, so a thread that read
            // it just before the code was installed can queue the block again once the task is gone; the installed
            // code reads the callees it inlines from the block (inlineTargets), set once with it
            val installed = cb.baseline
            if (installed != null) {
                out[i] = installed as CompiledCode
                continue
            }
            if (!canCompile(cb)) continue
            if (!usable) {
                fail("no usable code definer on this platform")
                continue
            }
            try {
                val input = JitInput(cb, tierUp = tierUp)
                // read by the generated code through its frame's code block: set before the code is installed
                cb.inlineTargets = input.inlineTargets
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

    /** Binary name of the class [CodeDefiners.isUsable] defines to test a definer. */
    const val PROBE_CLASS = "dev.mooner.neonjs.jit.JSProbe"

    /** A trivial [CompiledCode] class (its run returns null) for testing a definer. */
    fun probeClass(version: Int): ByteArray {
        val cw = JitClassWriter()
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
            classBytes(input, name, version, typed = TYPED, inline = true)
        } catch (e: Exception) {
            // inlined bodies make code larger, unboxed code too, and the type analysis gives up on some shapes: such
            // blocks get the code without inlined calls, or the Object code, as before them (deterministic, so equal
            // inputs still give equal classes)
            if (!TYPED || e !is org.objectweb.asm.MethodTooLargeException && e !is JitBailout) throw e
            try {
                if (input.inlineSites.isEmpty()) throw e
                notInlinedReasons.computeIfAbsent(reason(e)) { AtomicLong() }.incrementAndGet()
                classBytes(input, name, version, typed = true, inline = false)
            } catch (e2: Exception) {
                if (e2 !is org.objectweb.asm.MethodTooLargeException && e2 !is JitBailout) throw e2
                untypedReasons.computeIfAbsent(reason(e2)) { AtomicLong() }.incrementAndGet()
                classBytes(input, name, version, typed = false, inline = false)
            }
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

    /**
     * Testing deoptimization: with `-Dneonjs.jit.deoptStress=N` (N > 0; 3 without a value), code compiled from then on
     * has exits (Gen.stressExit) at its start, its loop headers, the start and loop headers of the bodies it inlines,
     * and after each inlined call, and one in N exits reached (JitRt.stressExit) leaves the activation to the
     * interpreter there (Deopt). Part of the class identity; tests change it between compilations.
     */
    @JvmField @Volatile var DEOPT_STRESS: Int = System.getProperty("neonjs.jit.deoptStress")?.let { it.toIntOrNull() ?: 3 } ?: 0

    /** Debugging: `-Dneonjs.jit.dump=DIR` writes every generated class there. */
    internal val DUMP_DIR: String? = System.getProperty("neonjs.jit.dump")

    private fun reason(e: Exception) = if (e is JitBailout) (e.message ?: "bailout").take(120) else "method too large"

    /** Calls compiled with the callee's body inlined (Inlining). */
    @JvmField val inlinedCalls = AtomicLong()

    /** Blocks compiled without their inlined calls, by reason (see [generate]). */
    @JvmField val notInlinedReasons = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()

    /** Blocks compiled without unboxed values, by reason (see [generate]). */
    @JvmField val untypedReasons = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()

    private fun classBytes(input: JitInput, name: String, version: Int, typed: Boolean, inline: Boolean): ByteArray {
        val cw = JitClassWriter()
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
        Gen(input, mv, typed, inlining = inline).emit()
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

    /**
     * What the body of a call compiled inlined reads instead of its own frame's state (see Gen.emitInlineCall), and what
     * an exit in it needs of the call: its pc, the kinds of the caller's values below its operands (on the JVM stack
     * while the body runs), the function and the arguments.
     */
    private class InlineCtx(
        val constsLocal: Int, val envLocal: Int, val thisLocal: Int, val resultLocal: Int, val resultKind: Byte, val exit: Label,
        val callPc: Int, val below: ByteArray, val fnLocal: Int, val argLocals: IntArray, val argKinds: ByteArray,
    )

    private class Gen(
        val cb: JitInput,
        mv: MethodVisitor,
        val typed: Boolean,
        /** For the body of a callee inlined into another function: that function's generator (whose method it is). */
        val outer: Gen? = null,
        argKinds: ByteArray? = null,
        /** Whether [JitInput.inlineSites] are compiled inlined. */
        inlining: Boolean = false,
    ) : JvmEmitter(mv) {
        val code = cb.code
        val labels = HashMap<Int, Label>()
        val handlerLabels = HashMap<Int, Label>()
        /** Kinds of the operand stack and of the registers (JitTypes): which values are unboxed. */
        val ta: TypeAnalysis? = if (typed) TypeAnalysis(cb, INT32, ELEMS, argKinds).run() else null
        /** Set while this generator emits a body inlined at a call. */
        var inl: InlineCtx? = null
        /** The outermost generator, the method's: it emits the exits of the bodies inlined into it ([stressExit]). */
        val root: Gen = outer?.root ?: this
        /** The deopt stress mode's exits, emitted after the method's body (outside every exception range). */
        private val exits = ArrayList<() -> Unit>()
        /** The instructions the deopt stress mode has an exit before (DEOPT_STRESS): the first, the loop headers. */
        private val exitAt: BooleanArray? = if (!cb.deoptStress || ta == null) null else BooleanArray(code.size).also { e ->
            if (e.isNotEmpty()) e[0] = true
            for (t in backEdgeTargets(code)) e[t] = true
        }
        /** CALL pc -> inline site k (the outermost function only: inlined bodies do not inline further). */
        val inlineAt: Map<Int, Int> =
            if (!inlining || outer != null || ta == null) emptyMap() else cb.inlineSites.withIndex().associate { (k, s) -> s.first to k }
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

        /** JVM local of register 0 (an inlined body's registers are locals of the method it is in). */
        val regBase: Int

        init {
            if (outer == null) {
                var n = L_REGS + cb.numRegs
                for (r in 0 until cb.numRegs) when (storage[r]) {
                    JT.NUM -> { slots[r] = n; n += 2 }
                    JT.INT -> { slots[r] = n; n += 1 }
                }
                nextTemp = n
                regBase = L_REGS
            } else {
                nextTemp = -1
                regBase = outer.alloc(cb.numRegs)
                for (r in 0 until cb.numRegs) when (storage[r]) {
                    JT.NUM -> slots[r] = outer.alloc(2)
                    JT.INT -> slots[r] = outer.alloc(1)
                }
            }
        }

        fun label(pc: Int): Label = labels.getOrPut(pc) { Label() }

        /** [n] new JVM locals, from the outermost generator (the method's). */
        override fun alloc(n: Int): Int = if (outer != null) outer.alloc(n) else nextTemp.also { nextTemp += n }

        fun reg(r: Int) = regBase + r

        override fun consts() = mv.visitVarInsn(ALOAD, inl?.constsLocal ?: L_CONSTS)
        override fun inlinedEnv(): Int = inl?.envLocal ?: -1
        override fun inlinedThis(): Int = inl?.thisLocal ?: -1
        override val inInlinedBody: Boolean get() = inl != null

        /** The Frame field recording the position: an inlined body's is inlinePc ([Frame.pc] stays at its call). */
        fun pcField() = if (inl != null) "inlinePc" else "pc"

        /** Value on the stack is [dev.mooner.neonjs.vm.Interpreter.TAIL]: return it so the caller performs the tail call. */

        fun emit() {
            collectJumpLabels()
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
            emitOsrEntry()
            cb.paramRegs?.forEachIndexed { idx, r ->
                // a parameter register kept unboxed is never read before a number is stored in it
                if (r >= 0 && storage[r] == JT.ANY) {
                    frame()
                    iconst(idx)
                    rt("arg", "($FRAME_D I)$OBJ".replace(" ", ""))
                    mv.visitVarInsn(ASTORE, reg(r))
                }
            }
            emitBody()
            // falling off the end is impossible: emitter always ends with RETURN
            pushUndefined()
            mv.visitInsn(ARETURN)
            emitTrampolines()
            for (e in exits) e()
        }

        /**
         * On-stack replacement ([CompiledCode]): given an interpreted frame, continues at the loop header in its pc with
         * the registers in its slots, or returns [JitRt.NO_OSR] when that pc is not an entry. Entries are the targets of
         * backward jumps where the operand stack is empty (the interpreter enters only at those, and checks its stack).
         * A register held unboxed is loaded only where the analysis has a number in it, through the checkcast (and
         * exactInt for an int) of any load of it; where the analysis has none, it is not read before a store (the
         * analysis's own rule) and keeps its initial value. Emitted whenever the block has a backward jump, entries or
         * not, since the interpreter tries every such block. Registers are initialized before (for the verifier).
         */
        private fun emitOsrEntry() {
            val targets = backEdgeTargets(code)
            if (targets.isEmpty()) return
            val normal = Label()
            val none = Label()
            frame()
            mv.visitFieldInsn(GETFIELD, FRAME, "slots", CONSTS_D)
            getstatic(FRAME, "NO_SLOTS", CONSTS_D)
            mv.visitJumpInsn(IF_ACMPEQ, normal)
            // the untyped code's entries come from the analysis too: only the stack depths are used then
            val kinds = ta ?: try { TypeAnalysis(cb, INT32, ELEMS).run() } catch (_: JitBailout) { null }
            val entries = if (kinds == null) IntArray(0) else targets.filter { kinds.reachable(it) && kinds.stackIn[it]!!.isEmpty() }.toIntArray()
            if (entries.isNotEmpty()) {
                // the unboxed registers loaded at each entry (and of which kind): entries alike share their loads
                val groups = LinkedHashMap<List<Byte>, Label>()
                val groupOf = Array(entries.size) { i ->
                    val load = List(cb.numRegs) { r ->
                        val k = if (storage[r] == JT.ANY) JT.ANY else ta!!.regKind(entries[i], r)
                        when {
                            storage[r] == JT.NUM && JT.isNum(k) -> JT.NUM
                            storage[r] == JT.INT && k == JT.INT -> JT.INT
                            else -> JT.ANY // held as an Object, or not read before a store
                        }
                    }
                    groups.getOrPut(load) { Label() }
                }
                frame()
                mv.visitFieldInsn(GETFIELD, FRAME, "pc", "I")
                mv.visitLookupSwitchInsn(none, entries, groupOf)
                val common = Label()
                for ((load, l) in groups) {
                    mv.visitLabel(l)
                    for (r in 0 until cb.numRegs) {
                        if (storage[r] == JT.ANY || load[r] == JT.ANY) continue
                        frame()
                        mv.visitFieldInsn(GETFIELD, FRAME, "slots", CONSTS_D)
                        iconst(r)
                        mv.visitInsn(AALOAD)
                        unboxNum()
                        if (load[r] == JT.INT) {
                            rt("exactInt", "(D)I")
                            mv.visitVarInsn(ISTORE, slots[r])
                        } else mv.visitVarInsn(DSTORE, slots[r])
                    }
                    mv.visitJumpInsn(GOTO, common)
                }
                mv.visitLabel(common)
                for (r in 0 until cb.numRegs) {
                    if (storage[r] != JT.ANY) continue
                    frame()
                    mv.visitFieldInsn(GETFIELD, FRAME, "slots", CONSTS_D)
                    iconst(r)
                    mv.visitInsn(AALOAD)
                    mv.visitVarInsn(ASTORE, reg(r))
                }
                frame()
                mv.visitFieldInsn(GETFIELD, FRAME, "pc", "I")
                mv.visitLookupSwitchInsn(none, entries, Array(entries.size) { label(entries[it]) })
            }
            mv.visitLabel(none)
            getstatic(RT, "NO_OSR", OBJ)
            mv.visitInsn(ARETURN)
            mv.visitLabel(normal)
        }

        private fun emitTrampolines() {
            for ((l, kinds, target) in trampolines) {
                mv.visitLabel(l)
                st.set(kinds)
                convertTo(ta!!.stackIn[target]!!)
                mv.visitJumpInsn(GOTO, label(target))
            }
            trampolines.clear()
        }

        /** The labels of jump targets (before any code is emitted). */
        private fun collectJumpLabels() {
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
        }

        /** The instructions, in order: the code of a function, or of a body inlined at a call. */
        private fun emitBody() {
            var pc = 0
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
                if (exitAt != null && exitAt[pc]) stressExit(pc)
                if (newStatement) {
                    // f.pc = pc at each statement, so errors raised between calls point at the right statement
                    frame()
                    iconst(pc)
                    mv.visitFieldInsn(PUTFIELD, FRAME, pcField(), "I")
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
                val site = if (op == Op.CALL) inlineAt[pc] else null
                if (site != null) {
                    val end = emitInlineCall(pc, site, ta!!)
                    if (end >= 0) {
                        while (si < stmts.size && stmts[si] < end) {
                            if (lines != null) curLine = lines[si]
                            si++
                        }
                        pc = end
                        continue
                    }
                }
                if (ta == null) emitGeneric(pc) else emitOp(pc, ta)
                val next = pc + Op.length(code, pc)
                // the fall-through edge: box what the next instruction expects boxed
                if (ta != null && fallsThrough(op) && ta.reachable(next)) convertTo(ta.stackIn[next]!!)
                pc = next
            }
            labels[pc]?.let { mv.visitLabel(it) }
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
            val first = pc0 + Op.length(code, pc0)
            val fast = ta.stackIn[pc0]!!.copyOf(ta.stackIn[pc0]!!.size - 1).also { it[it.size - 1] = elem }
            return simulateRegion(first, fast, allowPop = false, ta)
        }

        /**
         * The end of the instructions from [first] worth compiling twice, the fast copy starting with the kinds [fastIn]
         * (the analysis's, but for the value on top, which is more precise), or null when there is none. A value only
         * popped gains nothing after a GET_ELEM ([allowPop] false), but avoids boxing a call's unboxed result.
         */
        private fun simulateRegion(first: Int, fastIn: ByteArray, allowPop: Boolean, ta: TypeAnalysis): SplitSim? {
            var pc = first
            if (!ta.reachable(pc)) return null
            val static = KindStack().also { it.set(ta.stackIn[pc]!!) }
            val fast = KindStack().also { it.set(fastIn) }
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
                    if (op == Op.POP && !allowPop) return null // discarded: nothing to gain
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
            emitRegionFast(first, s.end, ta)
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
            emitRegionSlow(first, s.end, ta)
            mv.visitLabel(merge)
        }

        /** The fast copy of the instructions [first, end): with the more precise kinds on the stack, then the analysis's. */
        private fun emitRegionFast(first: Int, end: Int, ta: TypeAnalysis) {
            var pc = first
            while (pc < end) {
                storePc(pc)
                emitOp(pc, ta)
                pc += Op.length(code, pc)
            }
            convertTo(ta.stackIn[end]!!)
        }

        /** The slow copy of the instructions [first, end): as the main loop compiles them. */
        private fun emitRegionSlow(first: Int, end: Int, ta: TypeAnalysis) {
            var pc = first
            while (pc < end) {
                st.set(ta.stackIn[pc]!!)
                storePc(pc)
                emitOp(pc, ta)
                val next = pc + Op.length(code, pc)
                convertTo(ta.stackIn[next]!!)
                pc = next
            }
        }

        // ------------------------------------------------------------------ inlined calls (Inlining)

        /**
         * The CALL at [pc] with the body of inline site [k]'s callee compiled in: when the function called is a closure
         * of exactly `CodeBlock.inlineTargets[k]` (read through the frame's code block: classes are shared by blocks
         * with equal code) in this realm, its body runs here with its registers as locals of this method and the
         * arguments as they are (unboxed numbers stay unboxed); otherwise the ordinary call. As after a GET_ELEM
         * ([emitSplit]), the instructions after the call that use an unboxed result are compiled for both cases.
         * Returns the pc to continue at, or -1 when the call is compiled as usual instead.
         */
        private fun emitInlineCall(pc: Int, k: Int, ta: TypeAnalysis): Int {
            val argc = code[pc + 1]
            val n = argc + 2
            if (st.size < n || st[st.size - n] != JT.ANY) return -1
            val kinds = ByteArray(n) { st[st.size - n + it] }
            val argKinds = ByteArray(argc) { val kk = kinds[2 + it]; if (JT.isNum(kk)) kk else JT.ANY }
            val callee = try {
                Gen(cb.inlineSites[k].second, mv, true, this, argKinds)
            } catch (_: JitBailout) {
                return -1
            }
            val r = callee.resultKind()
            val first = pc + Op.length(code, pc)
            // (fn this args... -- result)
            val temps = IntArray(n)
            for (i in n - 1 downTo 0) temps[i] = storeTemp(kinds[i])
            repeat(n) { st.pop() }
            val below = st.toArray()
            var end = first
            if (r != JT.ANY) simulateRegion(first, below.copyOf(below.size + 1).also { it[below.size] = r }, allowPop = true, ta)?.let { end = it.end }
            val slow = Label()
            val merge = Label()
            val fn = temps[0]
            // the guard
            mv.visitVarInsn(ALOAD, fn)
            mv.visitTypeInsn(INSTANCEOF, CLOSURE)
            mv.visitJumpInsn(IFEQ, slow)
            mv.visitVarInsn(ALOAD, fn)
            mv.visitTypeInsn(CHECKCAST, CLOSURE)
            mv.visitFieldInsn(GETFIELD, CLOSURE, "code", CODEBLOCK_D)
            frame()
            mv.visitFieldInsn(GETFIELD, FRAME, "code", CODEBLOCK_D)
            mv.visitFieldInsn(GETFIELD, CODEBLOCK, "inlineTargets", "[$CODEBLOCK_D")
            iconst(k)
            mv.visitInsn(AALOAD)
            mv.visitJumpInsn(IF_ACMPNE, slow)
            mv.visitVarInsn(ALOAD, fn)
            mv.visitTypeInsn(CHECKCAST, CLOSURE)
            mv.visitFieldInsn(GETFIELD, CLOSURE, "realm", REALM_D)
            frame()
            mv.visitFieldInsn(GETFIELD, FRAME, "realm", REALM_D)
            mv.visitJumpInsn(IF_ACMPNE, slow)
            // the body
            frame()
            mv.visitVarInsn(ALOAD, fn)
            mv.visitTypeInsn(CHECKCAST, CLOSURE)
            iconst(pc)
            rt("inlineEnter", "($FRAME_D$CLOSURE_D" + "I)V")
            val constsLocal = temp()
            mv.visitVarInsn(ALOAD, fn)
            mv.visitTypeInsn(CHECKCAST, CLOSURE)
            mv.visitFieldInsn(GETFIELD, CLOSURE, "code", CODEBLOCK_D)
            mv.visitFieldInsn(GETFIELD, CODEBLOCK, "constants", CONSTS_D)
            mv.visitVarInsn(ASTORE, constsLocal)
            val envLocal = temp()
            mv.visitVarInsn(ALOAD, fn)
            mv.visitTypeInsn(CHECKCAST, CLOSURE)
            mv.visitFieldInsn(GETFIELD, CLOSURE, "env", "Ldev/mooner/neonjs/runtime/Env;")
            mv.visitVarInsn(ASTORE, envLocal)
            // the callee's `this`, bound as a call would bind it, if it reads it
            var thisLocal = -1
            if (callee.readsThis()) {
                thisLocal = temp()
                mv.visitVarInsn(ALOAD, fn)
                mv.visitTypeInsn(CHECKCAST, CLOSURE)
                loadTemp(kinds[1], temps[1])
                box(kinds[1])
                rt("inlineThis", "($CLOSURE_D$OBJ)$OBJ")
                mv.visitVarInsn(ASTORE, thisLocal)
            }
            val resultLocal = if (r == JT.NUM) tempD() else temp()
            val exit = Label()
            callee.inl = InlineCtx(
                constsLocal, envLocal, thisLocal, resultLocal, r, exit,
                pc, below, fn, IntArray(argc) { temps[2 + it] }, ByteArray(argc) { kinds[2 + it] },
            )
            callee.emitInlined(IntArray(argc) { temps[2 + it] }, ByteArray(argc) { kinds[2 + it] })
            mv.visitLabel(exit)
            frame()
            rt("inlineExit", "($FRAME_D)V")
            st.set(below)
            loadTemp(r, resultLocal)
            st.push(r)
            if (exitAt != null && ta.reachable(first)) stressExit(first)
            emitRegionFast(first, end, ta)
            mv.visitJumpInsn(GOTO, merge)
            // the ordinary call
            mv.visitLabel(slow)
            st.set(below)
            for (i in 0 until n) {
                loadTemp(kinds[i], temps[i])
                box(kinds[i])
                st.push(JT.ANY)
            }
            emitGeneric(pc)
            st.set(below)
            st.push(JT.ANY)
            if (!st.matches(ta.stackIn[first]!!)) throw JitBailout("inlined call at $pc: stack kinds differ")
            emitRegionSlow(first, end, ta)
            mv.visitLabel(merge)
            inlinedCalls.incrementAndGet()
            return end
        }

        /**
         * The deopt stress mode's exit before the instruction at [pc]: reached with the operand stack [st] (in an inlined
         * body, on top of the caller's values below the call), taken when JitRt.stressExit says so, it leaves the
         * activation to the interpreter (JitRt.deopt / deoptInlined) with this function's state at [pc] and, in an
         * inlined body, the caller's at the call. Its code comes after the method's body, outside every exception
         * range ([root]).
         */
        private fun stressExit(pc: Int) {
            val ta = ta!!
            val l = Label()
            mv.visitVarInsn(ALOAD, L_AGENT)
            rt("stressExit", "($AGENT_D)Z")
            mv.visitJumpInsn(IFNE, l)
            val stack = st.toArray()
            val regKinds = ByteArray(cb.numRegs) { ta.regKind(pc, it) }
            val inl = inl
            root.exits.add {
                mv.visitLabel(l)
                val temps = IntArray(stack.size)
                for (k in stack.indices.reversed()) temps[k] = storeTemp(stack[k])
                if (inl == null) {
                    frame()
                    iconst(pc)
                    values(regKinds, stack, temps)
                    rt("deopt", "(${FRAME_D}I$CONSTS_D)$OBJ")
                } else {
                    val o = outer!!
                    val belowTemps = IntArray(inl.below.size)
                    for (k in inl.below.indices.reversed()) belowTemps[k] = storeTemp(inl.below[k])
                    frame()
                    iconst(inl.callPc)
                    o.values(ByteArray(o.cb.numRegs) { o.ta!!.regKind(inl.callPc, it) }, inl.below, belowTemps)
                    mv.visitVarInsn(ALOAD, inl.fnLocal)
                    if (inl.thisLocal >= 0) mv.visitVarInsn(ALOAD, inl.thisLocal) else pushUndefined() // never read then
                    iconst(inl.argLocals.size)
                    mv.visitTypeInsn(ANEWARRAY, "java/lang/Object")
                    for (i in inl.argLocals.indices) {
                        mv.visitInsn(DUP)
                        iconst(i)
                        loadTemp(inl.argKinds[i], inl.argLocals[i])
                        box(inl.argKinds[i])
                        mv.visitInsn(AASTORE)
                    }
                    mv.visitVarInsn(ALOAD, inl.envLocal)
                    iconst(pc)
                    values(regKinds, stack, temps)
                    rt("deoptInlined", "(${FRAME_D}I$CONSTS_D$OBJ$OBJ$CONSTS_D${OBJ}I$CONSTS_D)$OBJ")
                }
                mv.visitInsn(ARETURN)
            }
        }

        /**
         * Pushes an Object[] of this function's registers, of kinds [regKinds] at the exit, then the stack values in
         * [temps], of kinds [kinds], all boxed: the interpreter's registers and operand stack there.
         */
        private fun values(regKinds: ByteArray, kinds: ByteArray, temps: IntArray) {
            iconst(cb.numRegs + kinds.size)
            mv.visitTypeInsn(ANEWARRAY, "java/lang/Object")
            for (r in 0 until cb.numRegs) {
                mv.visitInsn(DUP)
                iconst(r)
                val k = regKinds[r]
                when {
                    storage[r] == JT.ANY -> mv.visitVarInsn(ALOAD, reg(r))
                    storage[r] == JT.NUM && JT.isNum(k) -> { mv.visitVarInsn(DLOAD, slots[r]); box(JT.NUM) }
                    storage[r] == JT.INT && k == JT.INT -> { mv.visitVarInsn(ILOAD, slots[r]); box(JT.INT) }
                    // held unboxed but without a number here: no load reads it before a store (TypeAnalysis), and the
                    // register is invisible outside the function, so any value will do
                    else -> pushUndefined()
                }
                mv.visitInsn(AASTORE)
            }
            for (k in kinds.indices) {
                mv.visitInsn(DUP)
                iconst(cb.numRegs + k)
                loadTemp(kinds[k], temps[k])
                box(kinds[k])
                mv.visitInsn(AASTORE)
            }
        }

        /**
         * The back edge JUMP at [pc] to the loop header [target] (an empty stack there), with the optimizing tier on: the
         * interrupt check, and, when it runs (every 4096 iterations), a look at the block's code: when a later version is
         * installed that can be entered at [target] (JitRt.canTierUp), the loop goes on in it (JitRt.tierUp), from an
         * exit after the method's body with the registers at [pc] (as at [target]: the jump changes none).
         */
        private fun tierUpCheck(pc: Int, target: Int) {
            val ta = ta!!
            val ok = Label()
            val exit = Label()
            mv.visitIincInsn(L_BUDGET, -1)
            mv.visitVarInsn(ILOAD, L_BUDGET)
            mv.visitJumpInsn(IFGT, ok)
            mv.visitVarInsn(ALOAD, L_AGENT)
            mv.visitMethodInsn(INVOKEVIRTUAL, "dev/mooner/neonjs/runtime/Agent", "checkInterrupt", "()V", false)
            iconst(4096)
            mv.visitVarInsn(ISTORE, L_BUDGET)
            frame()
            mv.visitVarInsn(ALOAD, 0)
            iconst(target)
            rt("canTierUp", "(${FRAME_D}${OBJ}I)Z")
            mv.visitJumpInsn(IFNE, exit)
            mv.visitLabel(ok)
            val regKinds = ByteArray(cb.numRegs) { ta.regKind(pc, it) }
            root.exits.add {
                mv.visitLabel(exit)
                frame()
                iconst(target)
                values(regKinds, ByteArray(0), IntArray(0))
                rt("tierUp", "(${FRAME_D}I$CONSTS_D)$OBJ")
                mv.visitInsn(ARETURN)
            }
        }

        /** Whether this (inlined) function reads `this`. */
        fun readsThis(): Boolean {
            var pc = 0
            while (pc < code.size) {
                if (code[pc] == Op.LOAD_THIS) return true
                pc += Op.length(code, pc)
            }
            return false
        }

        /** The kind of the values this (inlined) function returns: INT or NUM when every return is one, else ANY. */
        fun resultKind(): Byte {
            val ta = ta!!
            var r: Byte = -1
            var pc = 0
            while (pc < code.size) {
                if (code[pc] == Op.RETURN && ta.reachable(pc)) {
                    val s = ta.stackIn[pc]!!
                    val k = if (s.isEmpty()) JT.ANY else s[s.size - 1]
                    r = if (r < 0) k else JT.join(r, k)
                }
                pc += Op.length(code, pc)
            }
            return if (JT.isNum(r)) r else JT.ANY
        }

        /** This function's body, inlined: registers start undefined, the arguments in [argTemps] go to the parameters. */
        fun emitInlined(argTemps: IntArray, argKinds: ByteArray) {
            collectJumpLabels()
            for (r in 0 until cb.numRegs) {
                pushUndefined()
                mv.visitVarInsn(ASTORE, reg(r))
                when (storage[r]) {
                    JT.NUM -> { mv.visitInsn(DCONST_0); mv.visitVarInsn(DSTORE, slots[r]) }
                    JT.INT -> { mv.visitInsn(ICONST_0); mv.visitVarInsn(ISTORE, slots[r]) }
                }
            }
            cb.paramRegs?.forEachIndexed { i, r ->
                if (i < argTemps.size) {
                    loadTemp(argKinds[i], argTemps[i])
                    storeReg(r, argKinds[i])
                }
            }
            emitBody()
            emitTrampolines()
        }

        /** RETURN in an inlined body: the value to the result local (in the call's result kind), then to the exit. */
        private fun emitInlineReturn() {
            val inl = inl!!
            val k = st.pop()
            convert(k, inl.resultKind)
            mv.visitVarInsn(when (inl.resultKind) { JT.NUM -> DSTORE; JT.INT -> ISTORE; else -> ASTORE }, inl.resultLocal)
            while (st.size > 0) popValue(st.pop())
            mv.visitJumpInsn(GOTO, inl.exit)
        }

        /** In a split, what the main loop does at a line table entry: f.pc = pc (and the JVM line number). */
        private fun storePc(pc: Int) {
            if (!stmtStart[pc]) return
            frame()
            iconst(pc)
            mv.visitFieldInsn(PUTFIELD, FRAME, pcField(), "I")
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

        /** Converts the value of kind [from] on top of the JVM stack to kind [to]: unchanged, an int widened, or boxed. */

        /** The int on top of the JVM stack as a double (unchanged if [kind] is NUM). */

        /** ToInt32 of the number of [kind] on top of the JVM stack. */

        /** The Object on top of the JVM stack is a Double (TypeAnalysis says so): its double value. */

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
                    storeReg(a, st.pop())
                    return
                }
                Op.RETURN -> if (inl != null) {
                    emitInlineReturn()
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
                    if (a <= pc) {
                        if (cb.tierUp && inl == null && st.size == 0 && ta.stackIn[a]!!.isEmpty()) tierUpCheck(pc, a)
                        else interruptCheck()
                    }
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

        /** Stores the value of [k] on the stack in register [a] (STORE_REG). */
        private fun storeReg(a: Int, k: Byte) {
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
        }

        /** Pushes 0 or 1 for the comparison of the two ints on the stack. */

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
                Op.INIT_THIS_REG -> {
                    mv.visitVarInsn(ALOAD, reg(a))
                    frame()
                    rt("initThisReg", "($OBJ$OBJ$FRAME_D)$OBJ")
                    mv.visitVarInsn(ASTORE, reg(a))
                }
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
                Op.MAKE_METHOD -> { mv.visitVarInsn(ALOAD, reg(b)); frame(); consts(); iconst(a); rt("makeMethod", "($OBJ$FRAME_D${CONSTS_D}I)$OBJ") }
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
                else -> if (!emitHelper(op, pc, a, b, c)) throw JitBailout("unsupported op ${Op.names[op]}")
            }
        }
    }
}
