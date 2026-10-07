package dev.mooner.neonjs.compiler

import dev.mooner.neonjs.parser.*
import dev.mooner.neonjs.runtime.PK
import dev.mooner.neonjs.runtime.Undefined

class CompileError(msg: String) : RuntimeException(msg)

class Label {
    var pos = -1
    var depth = -1
    val fixups = ArrayList<Int>()
}

/** Control-flow context used to compile break / continue / return. */
internal abstract class Ctl(val parent: Ctl?)

internal class LoopCtl(parent: Ctl?, val labels: List<String>, val breakLabel: Label, val continueLabel: Label?, val isSwitch: Boolean) : Ctl(parent)
internal class LabelCtl(parent: Ctl?, val labels: List<String>, val breakLabel: Label) : Ctl(parent)
internal class ScopeCtl(parent: Ctl?, val scope: Scope) : Ctl(parent)
internal class IterCtl(parent: Ctl?, val iterReg: Int, val isAsync: Boolean) : Ctl(parent)
internal class FinallyCtl(parent: Ctl?, val kindReg: Int, val valueReg: Int, val entry: Label) : Ctl(parent) {
    /** Pending jumps through this finally: index -> (target kind, payload). */
    val pending = ArrayList<PendingJump>()
}

internal class PendingJump(val kind: Int, val target: Ctl?, val label: String?, val isContinue: Boolean)

/** Static name used for NamedEvaluation of anonymous functions/classes. */
internal sealed class FnName
internal class StaticName(val name: String) : FnName()
internal object DynamicName : FnName()

internal abstract class EmitterBase(
    val fi: FnInfo,
    val source: Source,
    val analyzer: ScopeAnalyzer,
) {
    // ------------------------------------------------------------------ code buffer
    protected var code = IntArray(256)
    protected var pc = 0
    protected var depth = 0
    protected var maxDepth = 0
    protected val constants = ArrayList<Any?>()
    private val constIndex = HashMap<Any, Int>()
    protected val handlers = ArrayList<IntArray>()
    protected val lineTable = ArrayList<Int>()
    protected val callSites = ArrayList<Int>()
    private var lastPos = -1
    protected var nextReg = 0
    private var maxReg = 0
    private val freeRegs = ArrayList<Int>()

    /** Current compile-time scope (matches the runtime environment chain). */
    lateinit var scope: Scope
    internal var ctl: Ctl? = null
    /** > 0 while emitting a class (heritage, computed keys, decorators): strict mode code even in sloppy functions. */
    protected var strictDepth = 0
    val strict: Boolean get() = fi.strict || strictDepth > 0
    /** Instructions of strict regions in sloppy code (CodeBlock.strictPcs). */
    protected var strictPcs: java.util.BitSet? = null

    private fun markStrict() {
        if (strictDepth > 0 && !fi.strict) (strictPcs ?: java.util.BitSet().also { strictPcs = it }).set(pc)
    }

    /** Register holding the completion value (script/eval code), or -1. */
    var completionReg = -1

    fun initRegs(n: Int) {
        nextReg = n
        maxReg = n
    }

    // ------------------------------------------------------------------ emission

    private fun ensure(n: Int) {
        if (pc + n > code.size) code = code.copyOf(maxOf(code.size * 2, pc + n + 16))
    }

    private fun adjust(delta: Int) {
        depth += delta
        if (depth < 0) throw CompileError("stack underflow at pc $pc in ${fi.node::class.simpleName}")
        if (depth > maxDepth) maxDepth = depth
    }

    fun emit(op: Int) {
        ensure(1)
        markStrict()
        code[pc++] = op
        adjust(effect(op, 0, 0))
    }

    fun emit(op: Int, a: Int) {
        ensure(2)
        markStrict()
        code[pc++] = op
        // Named property / global accesses get their own inline-cache site. Every emitted instruction must get a
        // distinct site: load and store caches are filled under different conditions (a store hit writes the slot
        // without re-checking writability), so a site must never be shared between a load and a store.
        code[pc++] = when (op) {
            Op.GET_PROP, Op.PUT_PROP -> {
                constants.add(dev.mooner.neonjs.runtime.PropSite(constants[a]!!))
                constants.size - 1
            }
            Op.LOAD_GLOBAL, Op.LOAD_GLOBAL_TYPEOF, Op.STORE_GLOBAL -> {
                constants.add(dev.mooner.neonjs.runtime.GlobalSite(constants[a] as String))
                constants.size - 1
            }
            else -> a
        }
        adjust(effect(op, a, 0))
    }

    fun emit(op: Int, a: Int, b: Int) {
        ensure(3)
        markStrict()
        code[pc++] = op
        code[pc++] = a
        code[pc++] = b
        adjust(effect(op, a, b))
    }

    fun emit(op: Int, a: Int, b: Int, c: Int) {
        ensure(4)
        markStrict()
        code[pc++] = op
        code[pc++] = a
        code[pc++] = b
        code[pc++] = c
        adjust(effect(op, a, b))
    }

    /** Stack effect of an instruction. */
    private fun effect(op: Int, a: Int, b: Int): Int {
        val e = Op.effects[op]
        if (e != Op.VAR) return e
        return when (op) {
            Op.CALL, Op.TAIL_CALL, Op.CALL_EVAL, Op.SUPER_CALL, Op.COPY_DATA_PROPS_EXCL -> -(a + 1)
            Op.NEW -> -a
            Op.NEW_OBJECT_LITERAL -> 1 - b
            Op.MAKE_CLASS -> 2 - ((if (b and 1 != 0) 1 else 0) + (if (b and 2 != 0) 1 else 0))
            Op.DECLARE_GLOBALS, Op.DECLARE_EVAL -> -b
            else -> throw CompileError("no stack effect for ${Op.names[op]}")
        }
    }

    fun resetDepth(d: Int) {
        depth = d
        if (d > maxDepth) maxDepth = d
    }

    val currentDepth: Int get() = depth

    // ------------------------------------------------------------------ labels & jumps

    fun newLabel() = Label()

    fun emitJump(op: Int, l: Label) {
        emit(op, 0)
        val at = pc - 1
        if (l.pos >= 0) code[at] = l.pos else l.fixups.add(at)
        if (l.depth < 0) l.depth = depth
    }

    fun place(l: Label) {
        l.pos = pc
        for (f in l.fixups) code[f] = pc
        l.fixups.clear()
        if (l.depth >= 0) depth = l.depth else l.depth = depth
    }

    /** Places a label that is only reached by jumps (after an unconditional transfer). */
    fun placeAfterJump(l: Label) {
        if (l.depth >= 0) depth = l.depth
        place(l)
    }

    fun emitJumpTable(reg: Int, targets: List<Label>, default: Label) {
        ensure(4 + targets.size)
        code[pc++] = Op.JUMP_TABLE
        code[pc++] = reg
        code[pc++] = targets.size
        for (t in targets) {
            val at = pc++
            if (t.pos >= 0) code[at] = t.pos else t.fixups.add(at)
            if (t.depth < 0) t.depth = depth
        }
        val at = pc++
        if (default.pos >= 0) code[at] = default.pos else default.fixups.add(at)
        if (default.depth < 0) default.depth = depth
    }

    // ------------------------------------------------------------------ constants & registers

    fun const(v: Any): Int {
        if (v is CodeBlock || v is ScopeInfo || v is Array<*> || v is TemplateSite || v is RegExpSite || v is DeclInfo) {
            constants.add(v)
            return constants.size - 1
        }
        val key: Any = if (v is Double) DoubleKey(v) else v
        return constIndex.getOrPut(key) {
            constants.add(v)
            constants.size - 1
        }
    }

    /** Canonical property key constant for a property name. */
    fun keyConst(name: String): Int = const(PK.fromString(name))

    private data class DoubleKey(val bits: Long) {
        constructor(d: Double) : this(java.lang.Double.doubleToRawLongBits(d))
    }

    fun allocReg(): Int {
        if (freeRegs.isNotEmpty()) return freeRegs.removeAt(freeRegs.size - 1)
        val r = nextReg++
        if (nextReg > maxReg) maxReg = nextReg
        return r
    }

    fun freeReg(r: Int) {
        freeRegs.add(r)
    }

    val totalRegs: Int get() = maxReg

    fun mark(n: Node) {
        val pos = n.start
        if (pos != lastPos) {
            lastPos = pos
            lineTable.add(pc)
            lineTable.add(pos)
        }
    }

    /** Records the source range of [callee] for the call instruction emitted next ("… is not a function"). */
    fun markCallee(callee: Node) {
        var start = callee.start
        var end = callee.end
        if (callee.parenthesized) {
            // the node's range leaves out its parentheses: `(0, o.f)()` reads better with them
            val t = source.text
            var s = start - 1
            while (s >= 0 && t[s].isWhitespace()) s--
            var e = end
            while (e < t.length && t[e].isWhitespace()) e++
            if (s >= 0 && t[s] == '(' && e < t.length && t[e] == ')') {
                start = s
                end = e + 1
            }
        }
        callSites.add(pc)
        callSites.add(start)
        callSites.add(end)
    }

    fun addHandler(start: Int, end: Int, handler: Int, stackDepth: Int) {
        if (end > start) handlers.add(intArrayOf(start, end, handler, stackDepth))
    }

    // ------------------------------------------------------------------ helpers

    fun pushInt(i: Int) {
        emit(Op.PUSH_INT, i)
    }

    fun pushConst(v: Any?) {
        when (v) {
            null, Undefined -> emit(Op.PUSH_UNDEF)
            is Boolean -> emit(if (v) Op.PUSH_TRUE else Op.PUSH_FALSE)
            is Double -> {
                val i = v.toInt()
                // PUSH_INT only for the shared boxes of Ops.num (0..1023); other numbers are pre-boxed constants
                if (i.toDouble() == v && !(v == 0.0 && 1.0 / v < 0) && i >= 0 && i < 1024) pushInt(i)
                else emit(Op.PUSH_CONST, const(v))
            }
            else -> emit(Op.PUSH_CONST, const(v))
        }
    }

    fun throwError(kind: Int, msg: String) {
        emit(Op.THROW_ERROR, kind, const(msg))
    }

    // ------------------------------------------------------------------ scopes at runtime

    /** Number of materialized environments between the current scope and [target] (exclusive). */
    fun hops(target: Scope): Int {
        var n = 0
        var s: Scope? = scope
        while (s != null && s !== target) {
            if (s.needsEnv) n++
            s = s.parent
        }
        if (s == null) throw CompileError("scope not on chain for hop computation")
        return n
    }

    fun enterScope(s: Scope) {
        scope = s
        if (s.needsEnv) {
            emit(Op.PUSH_SCOPE, const(s.info!!))
            ctl = ScopeCtl(ctl, s)
        }
        initTdzRegisters(s)
    }

    fun initTdzRegisters(s: Scope) {
        for (b in s.bindings.values) {
            if (!b.inEnv && b.needsTdz && b.reg >= 0) {
                emit(Op.PUSH_CONST, const(UNINIT))
                emit(Op.STORE_REG, b.reg)
            }
        }
    }

    fun exitScope(s: Scope) {
        if (s.needsEnv) {
            emit(Op.POP_SCOPE)
            val c = ctl
            if (c is ScopeCtl && c.scope === s) ctl = c.parent
        }
        scope = s.parent ?: s
    }

    // ------------------------------------------------------------------ variable access

    /** Emits a load of a resolved reference. */
    fun emitLoadRef(ref: Any?, name: String, forTypeof: Boolean = false) {
        when (ref) {
            is LocalRef -> emitLoadBinding(ref.binding, ref.checkTdz)
            is DynamicRef -> emit(if (forTypeof) Op.LOAD_NAME_TYPEOF else Op.LOAD_NAME, const(name))
            is GlobalRef -> emit(if (forTypeof) Op.LOAD_GLOBAL_TYPEOF else Op.LOAD_GLOBAL, const(name))
            null -> emit(if (forTypeof) Op.LOAD_GLOBAL_TYPEOF else Op.LOAD_GLOBAL, const(name))
            else -> throw CompileError("bad ref")
        }
    }

    fun emitLoadBinding(b: Binding, checkTdz: Boolean) {
        if (b.scope.fn !== fi && !b.inEnv) throw CompileError("captured binding ${b.name} not in env")
        if (!b.inEnv) {
            when (b.kind) {
                BKind.THIS -> if (!b.tdz) { emit(Op.LOAD_THIS); return }
                BKind.NEW_TARGET -> { emit(Op.LOAD_NEW_TARGET); return }
                BKind.HOME -> { emit(Op.LOAD_HOME); return }
                BKind.FN -> { emit(Op.LOAD_FUNCTION); return }
                BKind.CALLEE -> { emit(Op.LOAD_FUNCTION); return }
                else -> {}
            }
            if (checkTdz) emit(Op.LOAD_REG_TDZ, b.reg, const(tdzName(b))) else emit(Op.LOAD_REG, b.reg)
        } else {
            val h = hops(b.scope)
            if (b.kind == BKind.IMPORT) emit(Op.LOAD_IMPORT, h, b.slot)
            else if (checkTdz) emit(Op.LOAD_ENV_TDZ, h, b.slot, const(tdzName(b))) else emit(Op.LOAD_ENV, h, b.slot)
        }
    }

    private fun tdzName(b: Binding) = if (b.kind == BKind.THIS) "this" else b.name

    /** Store to a binding for initialization (no TDZ / const checks). Value is popped. */
    fun emitInitBinding(b: Binding) {
        if (!b.inEnv) {
            if (b.reg < 0) {
                emit(Op.POP)
                return
            }
            emit(Op.STORE_REG, b.reg)
        } else emit(Op.STORE_ENV, hops(b.scope), b.slot)
    }

    /** Emits an assignment to a resolved identifier reference (value popped). */
    fun emitStoreRef(ref: Any?, name: String) {
        when (ref) {
            is LocalRef -> {
                val b = ref.binding
                if (b.kind.isConst || b.kind == BKind.CALLEE) {
                    if (ref.checkTdz || b.needsTdz) emitTdzCheck(b, ref.checkTdz)
                    if (b.kind == BKind.CALLEE && !strict) {
                        emit(Op.POP)
                        return
                    }
                    emit(Op.POP)
                    throwError(0, "Assignment to constant variable.")
                    return
                }
                if (ref.checkTdz) emitTdzCheck(b, true)
                emitInitBinding(b)
            }
            is DynamicRef -> emit(Op.STORE_NAME, const(name))
            is GlobalRef -> emit(Op.STORE_GLOBAL, const(name))
            null -> emit(Op.STORE_GLOBAL, const(name))
        }
    }

    fun emitTdzCheck(b: Binding, check: Boolean) {
        if (!check) return
        if (!b.inEnv) emit(Op.CHECK_REG_TDZ, b.reg, const(tdzName(b)))
        else emit(Op.CHECK_ENV_TDZ, hops(b.scope), b.slot, const(tdzName(b)))
    }

    /** Initializes a declared binding found by name in the current compile-time scope chain. */
    fun emitInitDeclared(id: Identifier) {
        when (val ref = id.ref) {
            is LocalRef -> emitInitBinding(ref.binding)
            is DynamicRef -> emit(Op.INIT_NAME, const(id.name))
            else -> emit(Op.INIT_GLOBAL_LEX, const(id.name))
        }
    }

    companion object {
        val UNINIT: Any = dev.mooner.neonjs.runtime.Uninitialized
    }
}

/** Template literal call site (cached template object per realm). */
class TemplateSite(val cooked: Array<String?>, val raw: Array<String>)

/** Regular expression literal site. */
class RegExpSite(val pattern: String, val flags: String) {
    @JvmField @Volatile var compiled: Any? = null
}

/** Declaration info for global / eval declaration instantiation. */
class DeclInfo(
    val varNames: Array<String>,
    val functionNames: Array<String>,
    val lexNames: Array<String>,
    val lexConst: BooleanArray,
    val annexBNames: Array<String>,
    val strict: Boolean,
)
