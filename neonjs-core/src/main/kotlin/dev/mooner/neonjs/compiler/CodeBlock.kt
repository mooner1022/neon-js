package dev.mooner.neonjs.compiler

import dev.mooner.neonjs.parser.FunctionKind

/** Source text holder shared by all code blocks of one script/module/eval. */
class Source(val name: String, val text: String) {
    private var lineStarts: IntArray? = null
    /** Owning script or module record (used for import.meta and import() referrers). */
    @JvmField var owner: Any? = null

    /** 1-based line and column for an offset. */
    fun lineCol(pos: Int): Pair<Int, Int> {
        var ls = lineStarts
        if (ls == null) {
            val list = ArrayList<Int>()
            list.add(0)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\n' || c == ' ' || c == ' ') list.add(i + 1)
                else if (c == '\r') {
                    if (i + 1 < text.length && text[i + 1] == '\n') i++
                    list.add(i + 1)
                }
                i++
            }
            ls = list.toIntArray()
            lineStarts = ls
        }
        var lo = 0
        var hi = ls.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (ls[mid] <= pos) lo = mid else hi = mid - 1
        }
        return (lo + 1) to (pos - ls[lo] + 1)
    }
}

/** Runtime descriptor of a declarative environment created for a [Scope]. */
class ScopeInfo(
    @JvmField val names: Array<String>,
    @JvmField val flags: IntArray,
    @JvmField val kind: ScopeKind,
) {
    @JvmField val size = names.size
    private val index: HashMap<String, Int>? = if (names.size > 6) HashMap<String, Int>().also { m -> names.forEachIndexed { i, n -> m[n] = i } } else null

    /** True when sloppy direct eval may add bindings to environments of this scope. */
    @JvmField var evalVarTarget = false
    @JvmField var isFunctionScope = false

    fun lookup(name: String): Int {
        if (index != null) return index[name] ?: -1
        for (i in names.indices) if (names[i] == name) return i
        return -1
    }

    /** Initial slot values: lexical bindings start uninitialized. */
    fun newSlots(): Array<Any?> {
        val a = arrayOfNulls<Any?>(size)
        for (i in 0 until size) a[i] = if (flags[i] and F_TDZ != 0) dev.mooner.neonjs.runtime.Uninitialized else dev.mooner.neonjs.runtime.Undefined
        return a
    }

    companion object {
        const val F_TDZ = 1
        const val F_CONST = 2
        /** Named function expression binding: assignments are ignored in sloppy mode. */
        const val F_CALLEE = 4
        const val F_LEXICAL = 8
        const val F_PSEUDO = 16
        const val F_FUNCTION = 32
        const val F_CATCH = 64
        const val F_PRIVATE = 128

        fun from(s: Scope): ScopeInfo {
            val bs = s.bindings.values.filter { it.inEnv }.sortedBy { it.slot }
            val names = Array(bs.size) { bs[it].name }
            val flags = IntArray(bs.size) { i ->
                val b = bs[i]
                var f = 0
                if (b.needsTdz) f = f or F_TDZ
                if (b.kind.isConst) f = f or F_CONST
                if (b.kind == BKind.CALLEE) f = f or F_CALLEE
                if (b.kind.isLexical) f = f or F_LEXICAL
                if (b.kind == BKind.THIS || b.kind == BKind.NEW_TARGET || b.kind == BKind.HOME || b.kind == BKind.FN) f = f or F_PSEUDO
                if (b.kind == BKind.FUNCTION) f = f or F_FUNCTION
                if (b.kind == BKind.CATCH) f = f or F_CATCH
                if (b.kind == BKind.PRIVATE) f = f or F_PRIVATE
                f
            }
            val info = ScopeInfo(names, flags, s.kind)
            info.evalVarTarget = s.evalVarTarget
            info.isFunctionScope = s.kind == ScopeKind.FUNCTION || s.kind == ScopeKind.BODY || s.kind == ScopeKind.EVAL
            return info
        }
    }
}

/** The callee a CALL site saw most: a majority vote (Boyer-Moore) over the code blocks called there. */
class CallFeedback {
    @JvmField var target: CodeBlock? = null
    @JvmField var count = 0
    /** The builtin intrinsic (NativeFunction.intrinsic) called there: 0 none, -1 more than one. */
    @JvmField var intrinsic = 0

    fun recordIntrinsic(id: Int) {
        intrinsic = if (intrinsic == 0 || intrinsic == id) id else -1
    }

    fun record(cb: CodeBlock) {
        if (target === cb) count++
        else if (count == 0) {
            target = cb
            count = 1
        } else count--
    }
}

/**
 * Kinds of the values the interpreter saw a code block produce, for the optimizing tier's speculation
 * (docs/OPTIMIZING_TIER.md, "Feedback"): at the pc of each GET_PROP, GET_ELEM, CALL and LOAD_ENV, and for each formal
 * parameter at entry, a set of [kindOf] bits. Written without synchronization: a lost bit only means a speculation
 * that fails and deoptimizes.
 */
class ValueProfile(codeSize: Int, params: Int) {
    @JvmField val values = ByteArray(codeSize)
    @JvmField val params = ByteArray(params)

    fun record(pc: Int, v: Any?) {
        val k = kindOf(v)
        val old = values[pc].toInt()
        if (old and k != k) values[pc] = (old or k).toByte()
    }

    fun recordArgs(args: Array<Any?>) {
        for (i in params.indices) {
            val k = kindOf(if (i < args.size) args[i] else dev.mooner.neonjs.runtime.Undefined)
            val old = params[i].toInt()
            if (old and k != k) params[i] = (old or k).toByte()
        }
    }

    companion object {
        /** A number that is an int32 (not -0). */
        const val INT = 1
        /** Any other number. */
        const val DOUBLE = 2
        const val STRING = 4
        const val BOOLEAN = 8
        /** `undefined` or `null`. */
        const val NULLISH = 16
        /** An object that is not callable. */
        const val OBJECT = 32
        const val FUNCTION = 64
        /** BigInt, Symbol, anything else. */
        const val OTHER = 128

        @JvmStatic
        fun kindOf(v: Any?): Int = when (v) {
            is Double -> {
                val i = v.toInt()
                if (i.toDouble() == v && (i != 0 || 1.0 / v > 0)) INT else DOUBLE
            }
            is CharSequence -> STRING
            is Boolean -> BOOLEAN
            is dev.mooner.neonjs.runtime.JSObject -> if (v.special and dev.mooner.neonjs.runtime.JSObject.CALLABLE != 0) FUNCTION else OBJECT
            else -> if (v === dev.mooner.neonjs.runtime.Undefined || v === dev.mooner.neonjs.runtime.Null) NULLISH else OTHER
        }
    }
}

/** Compiled function template (bytecode + metadata). */
class CodeBlock(@JvmField val name: String, @JvmField val kind: FunctionKind) {
    @JvmField var flags = 0
    @JvmField var code: IntArray = IntArray(0)
    @JvmField var constants: Array<Any?> = arrayOfNulls(0)
    /** Exception handlers: triples (startPc, endPc, handlerPc). Inner handlers come first. */
    @JvmField var handlers: IntArray = IntArray(0)
    @JvmField var numRegs = 0
    @JvmField var maxStack = 0
    /** Value of the "length" property. */
    @JvmField var length = 0
    /** Number of formal parameters (for mapped arguments). */
    @JvmField var formalCount = 0
    /** For each formal parameter index, the register receiving the argument directly, or -1. */
    @JvmField var paramRegs: IntArray? = null
    /** Pairs (pc, sourcePos) sorted by pc. */
    @JvmField var lineTable: IntArray = IntArray(0)
    /** Triples (pc of a call or `new` instruction, source start, source end of its callee) sorted by pc. */
    @JvmField var callSites: IntArray = IntArray(0)
    @JvmField var source: Source? = null
    @JvmField var srcStart = 0
    @JvmField var srcEnd = 0
    /** Register holding the completion value for script/eval code, or -1. */
    @JvmField var completionReg = -1
    /** Mapped arguments: env slot of each parameter in the function scope env. */
    @JvmField var mappedSlots: IntArray? = null
    /**
     * Interpreter feedback for the JIT's inlining (dev.mooner.neonjs.jit.Inlining): at the pc of each CALL that called a
     * JS function, the code block called most. Created on the first such call; written without synchronization (a
     * heuristic only: the generated code checks its target at every call).
     */
    @JvmField var callFeedback: Array<CallFeedback?>? = null
    /** Value kinds the interpreter saw, with the optimizing tier on (adaptive mode); created on first use. */
    @JvmField var profile: ValueProfile? = null
    /**
     * Static call targets, for the JIT's inlining where there is no feedback (compiled mode): the pcs of CALLs whose
     * callee is a name that a function declaration, or a `var`/`let`/`const` declaration with a function or arrow,
     * binds, sorted, and the code blocks of those functions ([callHintTargets]). Written by the compiler once the
     * whole source is compiled. A hint only, as the feedback: the binding may be assigned something else, and the
     * generated code checks its target at every call.
     */
    @JvmField var callHintPcs: IntArray? = null
    @JvmField var callHintTargets: Array<CodeBlock>? = null

    /** The static call target of the CALL at [pc] ([callHintPcs]), or null. */
    fun callHint(pc: Int): CodeBlock? {
        val pcs = callHintPcs ?: return null
        val i = java.util.Arrays.binarySearch(pcs, pc)
        return if (i >= 0) callHintTargets!![i] else null
    }
    /**
     * The code blocks the [baseline] code inlines, by site: the generated code inlines site k's body when the function
     * called is a closure of exactly `inlineTargets[k]`. Set before the baseline is installed, never changed after.
     */
    @JvmField var inlineTargets: Array<CodeBlock?>? = null
    /**
     * JIT state (see dev.mooner.neonjs.jit.Jit): the code new activations of this block run, once some is installed:
     * the [baseline], or a later version over it (docs/OPTIMIZING_TIER.md, "Code versions"), which can give way to the
     * baseline again. Every version runs a frame made for compiled code and answers on-stack replacement, so readers
     * need not care which one they get.
     */
    @JvmField @Volatile var compiled: Any? = null
    /** The first compiled code (dev.mooner.neonjs.jit.JvmCompiler's), once installed: never replaced. */
    @JvmField @Volatile var baseline: Any? = null
    /** Whether the optimizing tier's code for this block deoptimized too often: it is not compiled by that tier again. */
    @JvmField var t2Off = false
    /**
     * The pcs where a speculation of the optimizing tier failed (an exit taken), which its next compilations of this
     * block, or of blocks inlining it, do not make again (bit [code].size: the parameters); and how many times the block
     * was compiled again because of them.
     */
    @JvmField var t2Failed: java.util.BitSet? = null
    @JvmField var t2Recompiles = 0

    /** Whether a speculation of the optimizing tier at [pc] failed before ([t2Failed]). */
    fun t2FailedAt(pc: Int): Boolean = t2Failed?.get(pc) ?: false
    @JvmField var invocationCount = 0
    @JvmField @Volatile var jitFailed = false
    /** The pending dev.mooner.neonjs.jit.JitTask while the block is queued or being compiled. */
    @JvmField @Volatile var jitTask: Any? = null
    /** Whether the functions this block defines were queued for compilation (compiled mode). */
    @JvmField var childrenQueued = false
    /** Context flags for direct eval inside this function (CTX_*). */
    @JvmField var evalCtx = 0
    /** Private names visible to direct eval inside this function. */
    @JvmField var privateNames: Set<String>? = null
    /**
     * In sloppy code: the start offsets of instructions that are strict mode code nonetheless (all parts of a class,
     * e.g. its heritage and computed keys, are strict). Null when there are none. Such code blocks stay interpreted.
     */
    @JvmField var strictPcs: java.util.BitSet? = null

    /** Whether the instruction at [pc] of this sloppy code block is strict mode code (see [strictPcs]). */
    fun strictAt(pc: Int): Boolean {
        val s = strictPcs ?: return false
        return s.get(pc)
    }

    val isGenerator get() = flags and GENERATOR != 0
    val isAsync get() = flags and ASYNC != 0
    val isClassConstructor get() = flags and CLASS_CTOR != 0
    val isDerived get() = flags and DERIVED != 0
    val isConstructor get() = flags and CONSTRUCTOR != 0

    companion object {
        const val STRICT = 1
        const val ARROW = 2
        const val GENERATOR = 4
        const val ASYNC = 8
        const val CLASS_CTOR = 16
        const val DERIVED = 32
        const val METHOD = 64
        const val USES_THIS = 128
        const val DEFAULT_CTOR = 256
        const val CONSTRUCTOR = 512
        const val TOP_LEVEL = 1024
        const val HAS_EVAL = 2048
        const val NO_JIT = 4096
        /** A classic script's top-level code (eval code and module bodies are TOP_LEVEL without it). */
        const val SCRIPT = 8192

        const val CTX_NEW_TARGET = 1
        const val CTX_SUPER_PROP = 2
        const val CTX_SUPER_CALL = 4
        const val CTX_FIELD_INIT = 8
    }

    /** Source position for a pc (offset in source), or -1. */
    fun positionAt(pc: Int): Int {
        val t = lineTable
        var best = -1
        var i = 0
        while (i < t.size) {
            if (t[i] > pc) break
            best = t[i + 1]
            i += 2
        }
        return best
    }

    fun sourceText(): String? {
        val s = source ?: return null
        if (srcEnd <= srcStart || srcEnd > s.text.length) return null
        return s.text.substring(srcStart, srcEnd)
    }

    fun disassemble(): String {
        val sb = StringBuilder()
        sb.append("function ").append(name).append(" regs=").append(numRegs).append(" stack=").append(maxStack).append('\n')
        var pc = 0
        while (pc < code.size) {
            val op = code[pc]
            sb.append(String.format("%5d  %-20s", pc, Op.names[op]))
            val len = Op.length(code, pc)
            for (i in 1 until len) sb.append(' ').append(code[pc + i])
            if (op == Op.PUSH_CONST || op == Op.GET_PROP || op == Op.PUT_PROP || op == Op.LOAD_GLOBAL || op == Op.STORE_GLOBAL || op == Op.LOAD_NAME) {
                val c = constants[code[pc + 1]]
                sb.append("   ; ").append(if (c is CodeBlock) "<fn ${c.name}>" else c.toString().take(40))
            }
            sb.append('\n')
            pc += len
        }
        for (i in handlers.indices step 4) sb.append("handler [${handlers[i]}, ${handlers[i + 1]}) -> ${handlers[i + 2]} depth ${handlers[i + 3]}\n")
        return sb.toString()
    }
}
