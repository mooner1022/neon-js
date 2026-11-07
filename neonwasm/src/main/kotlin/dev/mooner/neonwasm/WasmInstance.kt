package dev.mooner.neonwasm

import dev.mooner.neonjs.runtime.VmAgent
import dev.mooner.neonjs.runtime.VmConfig

/** What a module imports, by module and name: a [WasmFunction], [WasmTable], [WasmMemory] or [WasmGlobal], or null. */
fun interface WasmImports {
    fun resolve(module: String, name: String): Any?

    companion object {
        @JvmField val NONE = WasmImports { _, _ -> null }
    }
}

/**
 * Where modules are instantiated and their code runs: one thread's execution, within the limits of [config] (the
 * execution core's [VmAgent]: time, instructions counted at loops and calls, memory, interrupts, call depth). Not
 * thread-safe; instances of one context may import one another's exports.
 */
class WasmContext(@JvmField val config: VmConfig = VmConfig()) {
    @JvmField val agent = VmAgent(config)

    // the value stack all of this context's activations share: numbers' bits, and references in the slot beside them
    internal @JvmField var stack = LongArray(1024)
    internal @JvmField var refs = arrayOfNulls<Any>(1024)
    /** The first slot a call from the host may use (above the frames of the code that called the host). */
    internal @JvmField var top = 0
    /** Loop iterations left before the next interrupt check. */
    internal @JvmField var loopBudget = LOOP_BUDGET

    /**
     * Instantiates [module] (§4.5.4): imports resolved and matched ([WasmLinkException]), memories, tables and
     * globals made, active segments copied in order, the start function run. A trap there ([WasmTrap]) fails the
     * instantiation; what it changed in imported memories and tables stays.
     */
    fun instantiate(module: WasmModule, imports: WasmImports = WasmImports.NONE): WasmInstance {
        val inst = WasmInstance(module, this)
        inst.link(imports)
        inst.initialize()
        return inst
    }

    internal fun ensure(size: Int) {
        if (size <= stack.size) return
        var n = stack.size
        while (n < size) n *= 2
        stack = stack.copyOf(n)
        refs = refs.copyOf(n)
    }

    /** A call from the host: [args] raw, written above the stack in use. */
    internal fun invoke(f: ModuleFunction, args: Array<Any?>): Array<Any?> {
        val t = f.type
        val fp = top
        val depth = agent.depth
        if (depth == 0) agent.startLimits()
        ensure(fp + maxOf(t.params.size, t.results.size) + 1)
        for (i in args.indices) {
            if (ValType.isRef(t.params[i])) refs[fp + i] = args[i] else stack[fp + i] = args[i] as Long
        }
        try {
            Interpreter.run(this, f, fp)
        } catch (_: StackOverflowError) {
            throw WasmTrap("call stack exhausted")
        } finally {
            agent.depth = depth
            top = fp
        }
        val res = arrayOfNulls<Any>(t.results.size)
        for (i in res.indices) res[i] = if (ValType.isRef(t.results[i])) refs[fp + i] else stack[fp + i]
        return res
    }

    /** A host function called by wasm code: its arguments at [base], its results written there. */
    internal fun callHost(f: WasmFunction, base: Int, sp: Int) {
        val t = f.type
        val args = arrayOfNulls<Any>(t.params.size)
        for (i in args.indices) args[i] = if (ValType.isRef(t.params[i])) refs[base + i] else stack[base + i]
        val savedTop = top
        top = sp
        val res = try {
            f.invokeRaw(args)
        } finally {
            top = savedTop
        }
        ensure(base + res.size)
        for (i in res.indices) {
            if (ValType.isRef(t.results[i])) refs[base + i] = res[i] else stack[base + i] = res[i] as Long
        }
    }

    internal companion object {
        const val LOOP_BUDGET = 4096
    }
}

/** An instance of a module (§4.2.5): its functions, tables, memory, globals and segments, and its exports. */
class WasmInstance internal constructor(@JvmField val module: WasmModule, @JvmField val context: WasmContext) {
    internal lateinit var funcs: Array<WasmFunction>
    internal lateinit var tables: Array<WasmTable>
    /** memory 0, if there is one */
    @JvmField internal var memory: WasmMemory? = null
    internal lateinit var globals: Array<WasmGlobal>
    /** element segments' references; empty once dropped */
    internal lateinit var elems: Array<Array<Any?>>
    /** data segments' bytes; empty once dropped */
    internal lateinit var datas: Array<ByteArray>

    /** The exports, by name: [WasmFunction], [WasmTable], [WasmMemory] or [WasmGlobal]. */
    lateinit var exports: Map<String, Any>
        private set

    fun function(name: String): WasmFunction? = exports[name] as? WasmFunction
    fun memory(name: String): WasmMemory? = exports[name] as? WasmMemory
    fun table(name: String): WasmTable? = exports[name] as? WasmTable
    fun global(name: String): WasmGlobal? = exports[name] as? WasmGlobal

    /** Imports resolved and matched (§4.5.4 steps 1-4), and the instance's own entities made. */
    internal fun link(imports: WasmImports) {
        val m = module
        val fs = ArrayList<WasmFunction>()
        val ts = ArrayList<WasmTable>()
        val ms = ArrayList<WasmMemory>()
        val gs = ArrayList<WasmGlobal>()
        for (imp in m.imports) {
            val v = imports.resolve(imp.module, imp.name) ?: throw WasmLinkException("unknown import ${imp.module}.${imp.name}")
            fun incompatible(): Nothing = throw WasmLinkException("incompatible import type ${imp.module}.${imp.name}")
            when (imp.kind) {
                ExternKind.FUNC -> {
                    if (v !is WasmFunction || v.type != m.types[imp.typeIndex]) incompatible()
                    fs.add(v)
                }
                ExternKind.TABLE -> {
                    val t = imp.type as TableType
                    if (v !is WasmTable || v.elemType != t.elemType || !Limits(v.size.toLong(), v.max).matches(t.limits)) incompatible()
                    ts.add(v)
                }
                ExternKind.MEMORY -> {
                    val t = imp.type as MemType
                    if (v !is WasmMemory || !Limits(v.pages.toLong(), v.max).matches(t.limits)) incompatible()
                    ms.add(v)
                }
                else -> {
                    val t = imp.type as GlobalType
                    if (v !is WasmGlobal || v.type.valType != t.valType || v.type.mutable != t.mutable) incompatible()
                    gs.add(v)
                }
            }
        }
        for (i in m.codes.indices) {
            val index = m.importedFuncs + i
            fs.add(ModuleFunction(m.funcType(index), this, m.codes[i]))
        }
        funcs = fs.toTypedArray()
        for (t in m.tables) ts.add(WasmTable(t))
        tables = ts.toTypedArray()
        for (t in m.memories) ms.add(WasmMemory(t))
        memory = ms.firstOrNull()
        // globals' initializers read imported globals only, which are all there now
        globals = gs.toTypedArray()
        for (g in m.globals) {
            val w = WasmGlobal(g.type)
            evaluate(g.init, w)
            gs.add(w)
            globals = gs.toTypedArray()
        }
        elems = Array(m.elems.size) { i ->
            val e = m.elems[i]
            Array(e.init.size) { j -> WasmGlobal(GlobalType(e.elemType, false)).also { evaluate(e.init[j], it) }.ref }
        }
        datas = Array(m.datas.size) { m.datas[it].bytes }
        val ex = LinkedHashMap<String, Any>()
        for (e in m.exports) ex[e.name] = when (e.kind) {
            ExternKind.FUNC -> funcs[e.index]
            ExternKind.TABLE -> tables[e.index]
            ExternKind.MEMORY -> ms[e.index]
            else -> globals[e.index]
        }
        exports = ex
    }

    /** Active segments copied in order, declarative ones dropped, then the start function (§4.5.4 steps 12-15). */
    internal fun initialize() {
        val m = module
        for ((i, e) in m.elems.withIndex()) {
            when (e.mode) {
                SegmentMode.ACTIVE -> {
                    val off = constI32(e.offset!!)
                    tableInit(tables[e.table], elems[i], off, 0, elems[i].size)
                    elems[i] = NO_REFS
                }
                SegmentMode.DECLARATIVE -> elems[i] = NO_REFS
            }
        }
        for ((i, d) in m.datas.withIndex()) {
            if (d.mode == SegmentMode.ACTIVE) {
                val off = constI32(d.offset!!)
                memoryInit(datas[i], off, 0, datas[i].size)
                datas[i] = NO_BYTES
            }
        }
        if (m.start >= 0) funcs[m.start].invokeRaw(arrayOfNulls(0))
    }

    private fun constI32(e: ConstExpr): Int {
        val g = WasmGlobal(GlobalType(ValType.I32, false))
        evaluate(e, g)
        return g.bits.toInt()
    }

    /** A constant expression's value, into [into]'s bits or reference. */
    private fun evaluate(e: ConstExpr, into: WasmGlobal) {
        when (e.op) {
            ConstExpr.I32_CONST, ConstExpr.I64_CONST, ConstExpr.F32_CONST, ConstExpr.F64_CONST -> into.bits = e.imm
            ConstExpr.REF_NULL -> into.ref = null
            ConstExpr.REF_FUNC -> into.ref = funcs[e.imm.toInt()]
            else -> {
                val g = globals[e.imm.toInt()]
                into.bits = g.bits
                into.ref = g.ref
            }
        }
    }

    // ------------------------------------------------------------------ the bulk instructions, shared with the interpreter

    /** table.init's copy ([d] and [s] unsigned), or the trap. */
    internal fun tableInit(t: WasmTable, seg: Array<Any?>, d: Int, s: Int, n: Int) {
        val dl = d.toLong() and MASK
        val sl = s.toLong() and MASK
        val nl = n.toLong() and MASK
        if (sl + nl > seg.size || dl + nl > t.size) throw WasmTrap("out of bounds table access")
        System.arraycopy(seg, s, t.elems, d, n)
    }

    internal fun memoryInit(seg: ByteArray, d: Int, s: Int, n: Int) {
        val mem = memory ?: throw WasmTrap("out of bounds memory access")
        val dl = d.toLong() and MASK
        val sl = s.toLong() and MASK
        val nl = n.toLong() and MASK
        if (sl + nl > seg.size || dl + nl > mem.bytes.size) throw WasmTrap("out of bounds memory access")
        System.arraycopy(seg, s, mem.bytes, d, n)
    }

    internal companion object {
        const val MASK = 0xFFFFFFFFL
        @JvmField val NO_REFS = arrayOfNulls<Any>(0)
        @JvmField val NO_BYTES = ByteArray(0)
    }
}
