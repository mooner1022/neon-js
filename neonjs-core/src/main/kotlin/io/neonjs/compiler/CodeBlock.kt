package io.neonjs.compiler

import io.neonjs.parser.FunctionKind

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
        for (i in 0 until size) a[i] = if (flags[i] and F_TDZ != 0) io.neonjs.runtime.Uninitialized else io.neonjs.runtime.Undefined
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
    @JvmField var source: Source? = null
    @JvmField var srcStart = 0
    @JvmField var srcEnd = 0
    /** Register holding the completion value for script/eval code, or -1. */
    @JvmField var completionReg = -1
    /** Mapped arguments: env slot of each parameter in the function scope env. */
    @JvmField var mappedSlots: IntArray? = null
    /** JIT state. */
    @JvmField @Volatile var compiled: Any? = null
    @JvmField var invocationCount = 0
    @JvmField var jitFailed = false
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

    val isStrict get() = flags and STRICT != 0
    val isArrow get() = flags and ARROW != 0
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
