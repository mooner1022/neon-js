package dev.mooner.neonwasm

import dev.mooner.neonwasm.ValType.F32
import dev.mooner.neonwasm.ValType.F64
import dev.mooner.neonwasm.ValType.FUNCREF
import dev.mooner.neonwasm.ValType.I32
import dev.mooner.neonwasm.ValType.I64
import dev.mooner.neonwasm.ValType.UNKNOWN

/**
 * Validation (§3, with the algorithm of its appendix A.3) of a decoded module, compiling each function body to the
 * interpreter's [Code] as it goes: validation knows the operand stack's height at every instruction, so each branch
 * gets its target position and the height its values move to.
 */
internal class WasmValidator(private val m: WasmModule) {
    /** C.refs: the functions a ref.func may name (those in element segments, globals' initializers and exports). */
    private val refs = HashSet<Int>()

    fun validate() {
        for (imp in m.imports) when (imp.kind) {
            ExternKind.FUNC -> if (imp.typeIndex >= m.types.size) invalid("unknown type")
            ExternKind.TABLE -> tableLimits((imp.type as TableType).limits)
            ExternKind.MEMORY -> memLimits((imp.type as MemType).limits)
        }
        for (t in m.funcTypes) if (t >= m.types.size) invalid("unknown type")
        for (t in m.tables) tableLimits(t.limits)
        for (t in m.memories) memLimits(t.limits)
        if (m.allMemories.size > 1) invalid("multiple memories")

        for (g in m.globals) collectRefs(g.init)
        for (e in m.elems) for (x in e.init) collectRefs(x)
        for (e in m.exports) if (e.kind == ExternKind.FUNC) refs.add(e.index)

        // constant expressions see the imported globals only (§3.4.10, C')
        for (g in m.globals) constExpr(g.init, g.type.valType, m.importedGlobals)
        val names = HashSet<String>()
        for (e in m.exports) {
            if (!names.add(e.name)) invalid("duplicate export name")
            val n = when (e.kind) {
                ExternKind.FUNC -> m.funcTypes.size
                ExternKind.TABLE -> m.allTables.size
                ExternKind.MEMORY -> m.allMemories.size
                else -> m.allGlobals.size
            }
            if (e.index < 0 || e.index >= n) invalid("unknown ${ExternKind.name(e.kind).let { if (it == "func") "function" else it }}")
        }
        if (m.start >= 0) {
            if (m.start >= m.funcTypes.size) invalid("unknown function")
            val t = m.funcType(m.start)
            if (t.params.isNotEmpty() || t.results.isNotEmpty()) invalid("start function")
        }
        for (e in m.elems) {
            for (x in e.init) constExpr(x, e.elemType, m.importedGlobals)
            if (e.mode == SegmentMode.ACTIVE) {
                if (e.table >= m.allTables.size) invalid("unknown table")
                if (m.allTables[e.table].elemType != e.elemType) invalid("type mismatch")
                constExpr(e.offset!!, I32, m.importedGlobals)
            }
        }
        for (d in m.datas) if (d.mode == SegmentMode.ACTIVE) {
            if (d.memory >= m.allMemories.size) invalid("unknown memory ${d.memory}")
            constExpr(d.offset!!, I32, m.importedGlobals)
        }
        m.codes = Array(m.bodies.size) { i ->
            val index = m.importedFuncs + i
            FuncCompiler(m, refs, index, m.bodies[i]).compile()
        }
    }

    private fun tableLimits(l: Limits) {
        if (l.hasMax && l.min > l.max) invalid("size minimum must not be greater than maximum")
    }

    private fun memLimits(l: Limits) {
        if (l.min > 65536 || l.hasMax && l.max > 65536) invalid("memory size must be at most 65536 pages (4GiB)")
        if (l.hasMax && l.min > l.max) invalid("size minimum must not be greater than maximum")
    }

    private fun collectRefs(e: ConstExpr) {
        if (e.op == ConstExpr.REF_FUNC) refs.add(e.imm.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    /** A constant expression of type [type], whose global.get may name the first [globals] globals. */
    private fun constExpr(e: ConstExpr, type: Int, globals: Int) {
        val t = when (e.op) {
            ConstExpr.I32_CONST -> I32
            ConstExpr.I64_CONST -> I64
            ConstExpr.F32_CONST -> F32
            ConstExpr.F64_CONST -> F64
            ConstExpr.REF_NULL -> e.imm.toInt()
            ConstExpr.REF_FUNC -> {
                if (e.imm >= m.funcTypes.size) invalid("unknown function ${e.imm}")
                FUNCREF
            }
            else -> {
                if (e.imm >= globals) invalid("unknown global ${e.imm}")
                val g = m.allGlobals[e.imm.toInt()]
                if (g.mutable) invalid("constant expression required")
                g.valType
            }
        }
        if (t != type) invalid("type mismatch")
    }
}

/** Validates and compiles one function body. */
private class FuncCompiler(private val m: WasmModule, private val refs: Set<Int>, private val index: Int, body: FuncBody) {
    private val type = m.funcType(index)
    private val r = WasmReader(m.bytes, body.codeStart, body.codeEnd)
    private val localTypes = type.params + body.locals
    private val nLocals = localTypes.size
    private val out = IntList(maxOf(16, (body.codeEnd - body.codeStart) * 2))

    // the operand stack's types
    private var vals = IntArray(32)
    private var vsp = 0
    private var maxVsp = 0

    // the control stack
    private var cKind = IntArray(16)
    private var cStart = arrayOfNulls<IntArray>(16)
    private var cEnd = arrayOfNulls<IntArray>(16)
    private var cHeight = IntArray(16)
    private var cUnreachable = BooleanArray(16)
    /** whether no code is emitted in the frame at all (it began in unreachable code) */
    private var cDead = BooleanArray(16)
    /** a loop's position; a block's or an if's chain of positions to patch with its end */
    private var cTarget = IntArray(16)
    /** an if's position of its jump to the else branch (-1 once patched) */
    private var cElse = IntArray(16)
    private var csp = 0

    fun compile(): Code {
        pushCtrl(BLOCK, EMPTY, type.results)
        while (csp > 0) instruction()
        if (!r.atEnd) malformed("section size mismatch")
        val refLocals = BooleanArray(nLocals) { ValType.isRef(localTypes[it]) }
        return Code(type, out.toArray(), nLocals, refLocals, nLocals + maxVsp + 1, index)
    }

    // ------------------------------------------------------------------ the validation algorithm (appendix A.3)

    private fun push(t: Int) {
        if (vsp == vals.size) vals = vals.copyOf(vsp * 2)
        vals[vsp++] = t
        if (vsp > maxVsp) maxVsp = vsp
    }

    private fun pop(): Int {
        val top = csp - 1
        if (vsp == cHeight[top]) {
            if (cUnreachable[top]) return UNKNOWN
            invalid("type mismatch")
        }
        return vals[--vsp]
    }

    private fun pop(expect: Int): Int {
        val t = pop()
        if (t != expect && t != UNKNOWN && expect != UNKNOWN) invalid("type mismatch")
        return if (t == UNKNOWN) expect else t
    }

    private fun popAll(types: IntArray) {
        for (i in types.indices.reversed()) pop(types[i])
    }

    private fun pushAll(types: IntArray) {
        for (t in types) push(t)
    }

    private fun pushCtrl(kind: Int, start: IntArray, end: IntArray) {
        if (csp == cKind.size) {
            val n = csp * 2
            cKind = cKind.copyOf(n); cStart = cStart.copyOf(n); cEnd = cEnd.copyOf(n); cHeight = cHeight.copyOf(n)
            cUnreachable = cUnreachable.copyOf(n); cDead = cDead.copyOf(n); cTarget = cTarget.copyOf(n); cElse = cElse.copyOf(n)
        }
        cKind[csp] = kind
        cStart[csp] = start
        cEnd[csp] = end
        cHeight[csp] = vsp
        cUnreachable[csp] = false
        cDead[csp] = csp > 0 && !live()
        cTarget[csp] = if (kind == LOOP) out.size else -1
        cElse[csp] = -1
        csp++
        pushAll(start)
    }

    private fun popCtrl(): Int {
        val top = csp - 1
        popAll(cEnd[top]!!)
        if (vsp != cHeight[top]) invalid("type mismatch")
        csp--
        return top
    }

    private fun labelTypes(frame: Int): IntArray = if (cKind[frame] == LOOP) cStart[frame]!! else cEnd[frame]!!

    private fun setUnreachable() {
        val top = csp - 1
        vsp = cHeight[top]
        cUnreachable[top] = true
    }

    private fun label(depth: Int): Int {
        if (depth < 0 || depth >= csp) invalid("unknown label")
        return csp - 1 - depth
    }

    // ------------------------------------------------------------------ emission

    /** Whether the current instruction is reachable, so its code is emitted. */
    private fun live(): Boolean = !cUnreachable[csp - 1] && !cDead[csp - 1]

    private fun emit(v: Int) = out.add(v)

    /** The position of a branch to [frame]: a loop's start, or a link in the chain patched at a block's end. */
    private fun emitTarget(frame: Int) {
        if (cKind[frame] == LOOP) emit(cTarget[frame])
        else {
            emit(cTarget[frame])
            cTarget[frame] = out.size - 1
        }
    }

    private fun patchChain(head: Int, pc: Int) {
        var p = head
        while (p >= 0) {
            val next = out[p]
            out[p] = pc
            p = next
        }
    }

    /** A branch to [frame] from the current height: a jump, or a move of its values and a jump. */
    private fun emitBranch(frame: Int, op: Int, plainOp: Int) {
        val arity = labelTypes(frame).size
        val height = nLocals + cHeight[frame]
        if (nLocals + vsp == height + arity) {
            emit(plainOp)
            emitTarget(frame)
        } else {
            emit(op)
            emitTarget(frame)
            emit(arity)
            emit(height)
        }
    }

    // ------------------------------------------------------------------ instructions

    private fun blockType(): Pair<IntArray, IntArray> {
        if (r.atEnd) malformed("unexpected end")
        val b = r.b[r.pos].toInt() and 0xFF
        if (b == 0x40) { r.pos++; return EMPTY to EMPTY }
        if (ValType.isValue(b)) { r.pos++; return EMPTY to intArrayOf(b) }
        if (b == ValType.V128) malformed("SIMD is not supported")
        val x = r.s33()
        if (x < 0 || x >= m.types.size) invalid("unknown type")
        val t = m.types[x.toInt()]
        return t.params to t.results
    }

    private fun memarg(natural: Int): Int {
        val align = r.u32L()
        val offset = r.u32L()
        if (m.allMemories.isEmpty()) invalid("unknown memory 0")
        if (align >= 32 || (1L shl align.toInt()) > natural) invalid("alignment must not be larger than natural")
        return offset.toInt()
    }

    private fun memory() {
        if (m.allMemories.isEmpty()) invalid("unknown memory 0")
    }

    private fun zero() {
        if (r.u8() != 0) malformed("zero byte expected")
    }

    private fun table(x: Int): TableType {
        if (x < 0 || x >= m.allTables.size) invalid("unknown table $x")
        return m.allTables[x]
    }

    private fun load(op: Int, natural: Int, t: Int) {
        val off = memarg(natural)
        pop(I32)
        push(t)
        if (live()) { emit(op); emit(off) }
    }

    private fun store(op: Int, natural: Int, t: Int) {
        val off = memarg(natural)
        pop(t)
        pop(I32)
        if (live()) { emit(op); emit(off) }
    }

    private fun instruction() {
        if (r.atEnd) malformed("unexpected end")
        val op = r.u8()
        when (op) {
            0x00 -> {
                if (live()) emit(Op.UNREACHABLE)
                setUnreachable()
            }
            0x01 -> {}
            0x02, 0x03 -> {
                val (params, results) = blockType()
                popAll(params)
                pushCtrl(if (op == 0x02) BLOCK else LOOP, params, results)
                if (op == 0x03 && live()) emit(Op.LOOP)
            }
            0x04 -> {
                val (params, results) = blockType()
                pop(I32)
                popAll(params)
                val wasLive = live()
                pushCtrl(IF, params, results)
                if (wasLive) {
                    emit(Op.IF)
                    emit(-1)
                    cElse[csp - 1] = out.size - 1
                }
            }
            0x05 -> {
                val top = csp - 1
                if (cKind[top] != IF) malformed("else without if")
                // the then branch's end: its results, as at an end
                popAll(cEnd[top]!!)
                if (vsp != cHeight[top]) invalid("type mismatch")
                if (live()) {
                    emit(Op.JUMP)
                    emitTarget(top)
                }
                if (cElse[top] >= 0) {
                    out[cElse[top]] = out.size
                    cElse[top] = -1
                }
                cKind[top] = ELSE
                cUnreachable[top] = false
                pushAll(cStart[top]!!)
            }
            0x0B -> end()
            0x0C -> {
                val f = label(r.u32())
                if (live()) emitBranch(f, Op.BR, Op.JUMP)
                popAll(labelTypes(f))
                setUnreachable()
            }
            0x0D -> {
                val f = label(r.u32())
                pop(I32)
                if (live()) emitBranch(f, Op.BR_IF, Op.BR_IF_JUMP)
                val types = labelTypes(f)
                popAll(types)
                pushAll(types)
            }
            0x0E -> brTable()
            0x0F -> {
                if (live()) emit(Op.RETURN)
                popAll(type.results)
                setUnreachable()
            }
            0x10 -> {
                val x = r.u32()
                if (x >= m.funcTypes.size) invalid("unknown function $x")
                val t = m.funcType(x)
                popAll(t.params)
                pushAll(t.results)
                if (live()) { emit(Op.CALL); emit(x) }
            }
            0x11 -> {
                val y = r.u32()
                val x = r.u32()
                val tt = table(x)
                if (y >= m.types.size) invalid("unknown type")
                if (tt.elemType != FUNCREF) invalid("type mismatch")
                val t = m.types[y]
                pop(I32)
                popAll(t.params)
                pushAll(t.results)
                if (live()) { emit(Op.CALL_INDIRECT); emit(y); emit(x) }
            }
            0x1A -> {
                pop()
                if (live()) emit(Op.DROP)
            }
            0x1B -> {
                pop(I32)
                val t1 = pop()
                val t2 = pop()
                if (!(ValType.isNum(t1) || t1 == UNKNOWN) || !(ValType.isNum(t2) || t2 == UNKNOWN)) invalid("type mismatch")
                if (t1 != t2 && t1 != UNKNOWN && t2 != UNKNOWN) invalid("type mismatch")
                push(if (t1 == UNKNOWN) t2 else t1)
                if (live()) emit(Op.SELECT)
            }
            0x1C -> {
                val n = r.u32()
                if (n != 1) invalid("invalid result arity")
                val t = r.u8()
                if (t == ValType.V128) malformed("SIMD is not supported")
                if (!ValType.isValue(t)) malformed("malformed value type")
                pop(I32)
                pop(t)
                pop(t)
                push(t)
                if (live()) emit(if (ValType.isRef(t)) Op.SELECT_REF else Op.SELECT)
            }
            0x20, 0x21, 0x22 -> {
                val x = r.u32()
                if (x >= nLocals) invalid("unknown local $x")
                val t = localTypes[x]
                when (op) {
                    0x20 -> push(t)
                    0x21 -> pop(t)
                    else -> { pop(t); push(t) }
                }
                if (live()) {
                    val ref = ValType.isRef(t)
                    emit(when (op) {
                        0x20 -> if (ref) Op.LOCAL_GET_REF else Op.LOCAL_GET
                        0x21 -> if (ref) Op.LOCAL_SET_REF else Op.LOCAL_SET
                        else -> if (ref) Op.LOCAL_TEE_REF else Op.LOCAL_TEE
                    })
                    emit(x)
                }
            }
            0x23, 0x24 -> {
                val x = r.u32()
                if (x >= m.allGlobals.size) invalid("unknown global $x")
                val g = m.allGlobals[x]
                if (op == 0x23) push(g.valType)
                else {
                    if (!g.mutable) invalid("global is immutable")
                    pop(g.valType)
                }
                if (live()) {
                    val ref = ValType.isRef(g.valType)
                    emit(if (op == 0x23) (if (ref) Op.GLOBAL_GET_REF else Op.GLOBAL_GET) else (if (ref) Op.GLOBAL_SET_REF else Op.GLOBAL_SET))
                    emit(x)
                }
            }
            0x25 -> {
                val x = r.u32()
                val t = table(x)
                pop(I32)
                push(t.elemType)
                if (live()) { emit(Op.TABLE_GET); emit(x) }
            }
            0x26 -> {
                val x = r.u32()
                val t = table(x)
                pop(t.elemType)
                pop(I32)
                if (live()) { emit(Op.TABLE_SET); emit(x) }
            }
            0x28 -> load(op, 4, I32)
            0x29 -> load(op, 8, I64)
            0x2A -> load(op, 4, F32)
            0x2B -> load(op, 8, F64)
            0x2C, 0x2D -> load(op, 1, I32)
            0x2E, 0x2F -> load(op, 2, I32)
            0x30, 0x31 -> load(op, 1, I64)
            0x32, 0x33 -> load(op, 2, I64)
            0x34, 0x35 -> load(op, 4, I64)
            0x36 -> store(op, 4, I32)
            0x37 -> store(op, 8, I64)
            0x38 -> store(op, 4, F32)
            0x39 -> store(op, 8, F64)
            0x3A -> store(op, 1, I32)
            0x3B -> store(op, 2, I32)
            0x3C -> store(op, 1, I64)
            0x3D -> store(op, 2, I64)
            0x3E -> store(op, 4, I64)
            0x3F -> {
                zero()
                memory()
                push(I32)
                if (live()) emit(Op.MEMORY_SIZE)
            }
            0x40 -> {
                zero()
                memory()
                pop(I32)
                push(I32)
                if (live()) emit(Op.MEMORY_GROW)
            }
            0x41 -> {
                val v = r.s32()
                push(I32)
                if (live()) { emit(Op.I32_CONST); emit(v) }
            }
            0x42 -> {
                val v = r.s64()
                push(I64)
                if (live()) { emit(Op.I64_CONST); emit(v.toInt()); emit((v ushr 32).toInt()) }
            }
            0x43 -> {
                val v = r.f32Bits()
                push(F32)
                if (live()) { emit(Op.F32_CONST); emit(v) }
            }
            0x44 -> {
                val v = r.f64Bits()
                push(F64)
                if (live()) { emit(Op.F64_CONST); emit(v.toInt()); emit((v ushr 32).toInt()) }
            }
            in 0x45..0xC4 -> numeric(op, op)
            0xD0 -> {
                val t = r.u8()
                if (!ValType.isRef(t)) malformed("malformed reference type")
                push(t)
                if (live()) emit(Op.REF_NULL)
            }
            0xD1 -> {
                val t = pop()
                if (t != UNKNOWN && !ValType.isRef(t)) invalid("type mismatch")
                push(I32)
                if (live()) emit(Op.REF_IS_NULL)
            }
            0xD2 -> {
                val x = r.u32()
                if (x >= m.funcTypes.size) invalid("unknown function $x")
                if (x !in refs) invalid("undeclared function reference")
                push(FUNCREF)
                if (live()) { emit(Op.REF_FUNC); emit(x) }
            }
            0xFC -> prefixed()
            0xFD -> malformed("SIMD is not supported")
            else -> malformed("illegal opcode ${Integer.toHexString(op)}")
        }
    }

    private fun end() {
        val top = csp - 1
        val kind = cKind[top]
        // an if without else: the else branch passes its parameters on as its results
        if (kind == IF && !cStart[top]!!.contentEquals(cEnd[top]!!)) invalid("type mismatch")
        popCtrl()
        if (cElse[top] >= 0) out[cElse[top]] = out.size
        if (kind != LOOP) patchChain(cTarget[top], out.size)
        if (top == 0) emit(Op.RETURN)
        pushAll(cEnd[top]!!)
    }

    private fun brTable() {
        val n = r.u32()
        if (n > r.end - r.pos) malformed("unexpected end")
        val labels = IntArray(n) { label(r.u32()) }
        val default = label(r.u32())
        pop(I32)
        val arity = labelTypes(default).size
        if (live()) {
            emit(Op.BR_TABLE)
            emit(n)
            for (f in labels + default) {
                emitTarget(f)
                emit(labelTypes(f).size)
                emit(nLocals + cHeight[f])
            }
        }
        for (f in labels) {
            val types = labelTypes(f)
            if (types.size != arity) invalid("type mismatch")
            // (each label's types checked against the operands, which unreachable code may leave unknown)
            val save = vsp
            popAll(types)
            vsp = save
            if (vsp < cHeight[csp - 1]) vsp = cHeight[csp - 1]
        }
        popAll(labelTypes(default))
        setUnreachable()
    }

    private fun numeric(op: Int, emitted: Int) {
        val sig = SIGNATURES[op] ?: malformed("illegal opcode ${Integer.toHexString(op)}")
        if (sig.size == 3) { pop(sig[1]); pop(sig[0]) } else pop(sig[0])
        push(sig[sig.size - 1])
        // the slots hold bits: reinterpretations and wrapping change nothing
        if (live() && op !in 0xBC..0xBF && op != 0xA7) emit(emitted)
    }

    private fun prefixed() {
        when (val sub = r.u32()) {
            in 0..7 -> {
                val s = TRUNC_SAT[sub]
                pop(s[0])
                push(s[1])
                if (live()) emit(Op.TRUNC_SAT_BASE + sub)
            }
            8 -> {
                val x = r.u32()
                zero()
                memory()
                dataIndex(x)
                pop(I32); pop(I32); pop(I32)
                if (live()) { emit(Op.MEMORY_INIT); emit(x) }
            }
            9 -> {
                val x = r.u32()
                dataIndex(x)
                if (live()) { emit(Op.DATA_DROP); emit(x) }
            }
            10 -> {
                zero(); zero()
                memory()
                pop(I32); pop(I32); pop(I32)
                if (live()) emit(Op.MEMORY_COPY)
            }
            11 -> {
                zero()
                memory()
                pop(I32); pop(I32); pop(I32)
                if (live()) emit(Op.MEMORY_FILL)
            }
            12 -> {
                val y = r.u32()
                val x = r.u32()
                val t = table(x)
                if (y >= m.elems.size) invalid("unknown elem segment $y")
                if (m.elems[y].elemType != t.elemType) invalid("type mismatch")
                pop(I32); pop(I32); pop(I32)
                if (live()) { emit(Op.TABLE_INIT); emit(y); emit(x) }
            }
            13 -> {
                val y = r.u32()
                if (y >= m.elems.size) invalid("unknown elem segment $y")
                if (live()) { emit(Op.ELEM_DROP); emit(y) }
            }
            14 -> {
                val x = r.u32()
                val y = r.u32()
                val tx = table(x)
                val ty = table(y)
                if (tx.elemType != ty.elemType) invalid("type mismatch")
                pop(I32); pop(I32); pop(I32)
                if (live()) { emit(Op.TABLE_COPY); emit(x); emit(y) }
            }
            15 -> {
                val x = r.u32()
                val t = table(x)
                pop(I32)
                pop(t.elemType)
                push(I32)
                if (live()) { emit(Op.TABLE_GROW); emit(x) }
            }
            16 -> {
                val x = r.u32()
                table(x)
                push(I32)
                if (live()) { emit(Op.TABLE_SIZE); emit(x) }
            }
            17 -> {
                val x = r.u32()
                val t = table(x)
                pop(I32)
                pop(t.elemType)
                pop(I32)
                if (live()) { emit(Op.TABLE_FILL); emit(x) }
            }
            else -> malformed("illegal opcode 0xfc $sub")
        }
    }

    private fun dataIndex(x: Int) {
        if (m.dataCount < 0) malformed("data count section required")
        if (x >= m.dataCount) invalid("unknown data segment $x")
    }

    companion object {
        const val BLOCK = 0
        const val LOOP = 1
        const val IF = 2
        const val ELSE = 3
        val EMPTY = IntArray(0)

        /** Each numeric instruction's operand types and result type ([a, result] or [a, b, result]). */
        val SIGNATURES = arrayOfNulls<IntArray>(256).also { s ->
            fun un(range: IntRange, a: Int, res: Int) { for (op in range) s[op] = intArrayOf(a, res) }
            fun bin(range: IntRange, a: Int, res: Int) { for (op in range) s[op] = intArrayOf(a, a, res) }
            un(0x45..0x45, I32, I32); bin(0x46..0x4F, I32, I32)
            un(0x50..0x50, I64, I32); bin(0x51..0x5A, I64, I32)
            bin(0x5B..0x60, F32, I32); bin(0x61..0x66, F64, I32)
            un(0x67..0x69, I32, I32); bin(0x6A..0x78, I32, I32)
            un(0x79..0x7B, I64, I64); bin(0x7C..0x8A, I64, I64)
            un(0x8B..0x91, F32, F32); bin(0x92..0x98, F32, F32)
            un(0x99..0x9F, F64, F64); bin(0xA0..0xA6, F64, F64)
            un(0xA7..0xA7, I64, I32)
            un(0xA8..0xA9, F32, I32); un(0xAA..0xAB, F64, I32)
            un(0xAC..0xAD, I32, I64)
            un(0xAE..0xAF, F32, I64); un(0xB0..0xB1, F64, I64)
            un(0xB2..0xB3, I32, F32); un(0xB4..0xB5, I64, F32); un(0xB6..0xB6, F64, F32)
            un(0xB7..0xB8, I32, F64); un(0xB9..0xBA, I64, F64); un(0xBB..0xBB, F32, F64)
            un(0xBC..0xBC, F32, I32); un(0xBD..0xBD, F64, I64); un(0xBE..0xBE, I32, F32); un(0xBF..0xBF, I64, F64)
            un(0xC0..0xC1, I32, I32); un(0xC2..0xC4, I64, I64)
        }

        /** 0xFC 0-7, the saturating truncations: [operand, result]. */
        val TRUNC_SAT = arrayOf(
            intArrayOf(F32, I32), intArrayOf(F32, I32), intArrayOf(F64, I32), intArrayOf(F64, I32),
            intArrayOf(F32, I64), intArrayOf(F32, I64), intArrayOf(F64, I64), intArrayOf(F64, I64),
        )
    }
}
