package dev.mooner.neonwasm

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Reads the binary format's primitives (§5.2) from [b] between [pos] and [end]. */
internal class WasmReader(@JvmField val b: ByteArray, @JvmField var pos: Int, @JvmField var end: Int) {
    val atEnd: Boolean get() = pos >= end

    fun u8(): Int {
        if (pos >= end) malformed("unexpected end")
        return b[pos++].toInt() and 0xFF
    }

    /** An unsigned 32-bit LEB128, as a Long (0 to 2^32 - 1). */
    fun u32L(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val x = u8()
            if (shift == 28) {
                if (x and 0x80 != 0) malformed("integer representation too long")
                if (x and 0x70 != 0) malformed("integer too large")
            }
            result = result or ((x and 0x7F).toLong() shl shift)
            if (x and 0x80 == 0) return result
            shift += 7
        }
    }

    /** An unsigned 32-bit LEB128 that must fit an Int (counts, indices: larger ones are past any limit anyway). */
    fun u32(): Int {
        val v = u32L()
        return if (v > Int.MAX_VALUE) Int.MAX_VALUE else v.toInt()
    }

    fun s32(): Int = signed(32).toInt()
    fun s33(): Long = signed(33)
    fun s64(): Long = signed(64)

    private fun signed(bits: Int): Long {
        val maxBytes = (bits + 6) / 7
        var result = 0L
        var shift = 0
        for (i in 0 until maxBytes) {
            val x = u8()
            result = result or ((x and 0x7F).toLong() shl shift)
            shift += 7
            if (x and 0x80 == 0) {
                if (i == maxBytes - 1) {
                    // the last byte's bits past the value's own are all its sign
                    val used = bits - 7 * (maxBytes - 1)
                    val mask = (0x7F shr (used - 1)) shl (used - 1)
                    val rest = x and mask
                    if (rest != 0 && rest != mask) malformed("integer too large")
                }
                if (shift < 64 && x and 0x40 != 0) result = result or (-1L shl shift)
                return result
            }
        }
        malformed("integer representation too long")
    }

    fun f32Bits(): Int {
        if (end - pos < 4) { pos = end; malformed("unexpected end") }
        val v = (b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8) or
            ((b[pos + 2].toInt() and 0xFF) shl 16) or ((b[pos + 3].toInt() and 0xFF) shl 24)
        pos += 4
        return v
    }

    fun f64Bits(): Long {
        val lo = f32Bits().toLong() and 0xFFFFFFFFL
        val hi = f32Bits().toLong()
        return lo or (hi shl 32)
    }

    fun bytes(n: Int): ByteArray {
        if (n < 0 || n > end - pos) malformed("unexpected end")
        val r = b.copyOfRange(pos, pos + n)
        pos += n
        return r
    }

    fun name(): String {
        val n = u32()
        if (n > end - pos) malformed("unexpected end")
        val s = try {
            UTF8.get().decode(ByteBuffer.wrap(b, pos, n)).toString()
        } catch (_: CharacterCodingException) {
            malformed("malformed UTF-8 encoding")
        }
        pos += n
        return s
    }

    companion object {
        private val UTF8 = ThreadLocal.withInitial {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        }
    }
}

internal fun malformed(message: String): Nothing = throw WasmMalformedException(message)
internal fun invalid(message: String): Nothing = throw WasmInvalidException(message)

/**
 * The binary format (§5.5): sections in their order, each consumed exactly. Function bodies are kept as ranges of
 * [bytes] with their locals; the validator reads their instructions.
 */
internal class WasmDecoder(private val bytes: ByteArray) {
    private var types: Array<FuncType> = emptyArray()
    private var imports: Array<Import> = emptyArray()
    private var funcs = IntArray(0)
    private var tables: Array<TableType> = emptyArray()
    private var memories: Array<MemType> = emptyArray()
    private var globals: Array<Global> = emptyArray()
    private var exports: Array<Export> = emptyArray()
    private var start = -1
    private var elems: Array<ElemSegment> = emptyArray()
    private var datas: Array<DataSegment> = emptyArray()
    private var dataCount = -1
    private var bodies: Array<FuncBody>? = null

    fun decode(): WasmModule {
        val r = WasmReader(bytes, 0, bytes.size)
        if (bytes.size < 4 || bytes[0].toInt() != 0 || bytes[1].toInt() != 0x61 || bytes[2].toInt() != 0x73 || bytes[3].toInt() != 0x6D) {
            malformed("magic header not detected")
        }
        r.pos = 4
        if (bytes.size < 8 || bytes[4].toInt() != 1 || bytes[5].toInt() != 0 || bytes[6].toInt() != 0 || bytes[7].toInt() != 0) {
            malformed("unknown binary version")
        }
        r.pos = 8
        var last = 0
        while (!r.atEnd) {
            val id = r.u8()
            val size = r.u32()
            if (size > r.end - r.pos) malformed("unexpected end of section or function")
            val s = WasmReader(bytes, r.pos, r.pos + size)
            r.pos += size
            if (id == 0) {
                s.name()
                continue
            }
            val order = when (id) {
                in 1..9 -> id
                12 -> 10
                10 -> 11
                11 -> 12
                else -> malformed("malformed section id")
            }
            if (order <= last) malformed("unexpected content after last section")
            last = order
            when (id) {
                1 -> types = vec(s) { funcType(s) }
                2 -> imports = vec(s) { import(s) }
                3 -> funcs = IntArray(count(s)) { s.u32() }
                4 -> tables = vec(s) { tableType(s) }
                5 -> memories = vec(s) { MemType(limits(s)) }
                6 -> globals = vec(s) { Global(globalType(s), constExpr(s)) }
                7 -> exports = vec(s) { export(s) }
                8 -> start = s.u32()
                9 -> elems = vec(s) { elem(s) }
                12 -> dataCount = s.u32()
                10 -> bodies = vec(s) { body(s) }
                11 -> datas = vec(s) { data(s) }
            }
            if (!s.atEnd) malformed("section size mismatch")
        }
        val bodies = bodies ?: emptyArray()
        if (bodies.size != funcs.size) malformed("function and code section have inconsistent lengths")
        if (dataCount >= 0 && dataCount != datas.size) malformed("data count and data section have inconsistent lengths")
        val funcTypes = IntArray(imports.count { it.kind == ExternKind.FUNC } + funcs.size)
        var i = 0
        for (imp in imports) if (imp.kind == ExternKind.FUNC) funcTypes[i++] = imp.typeIndex
        for (t in funcs) funcTypes[i++] = t
        return WasmModule(bytes, types, imports, funcTypes, tables, memories, globals, exports, start, elems, datas,
            dataCount, bodies)
    }

    private fun count(r: WasmReader): Int {
        val n = r.u32()
        // each element takes a byte at least: a larger count cannot be right
        if (n > r.end - r.pos) malformed("unexpected end")
        return n
    }

    private inline fun <reified T> vec(r: WasmReader, item: () -> T): Array<T> {
        val n = count(r)
        return Array(n) { item() }
    }

    private fun valType(r: WasmReader): Int {
        val t = r.u8()
        if (t == ValType.V128) malformed("SIMD is not supported")
        if (!ValType.isValue(t)) malformed("malformed value type")
        return t
    }

    private fun refType(r: WasmReader): Int {
        val t = r.u8()
        if (!ValType.isRef(t)) malformed("malformed reference type")
        return t
    }

    private fun funcType(r: WasmReader): FuncType {
        if (r.u8() != 0x60) malformed("integer representation too long")
        val params = IntArray(count(r)) { valType(r) }
        val results = IntArray(count(r)) { valType(r) }
        return FuncType(params, results)
    }

    private fun limits(r: WasmReader): Limits = when (r.u8()) {
        0x00 -> Limits(r.u32L(), -1)
        0x01 -> {
            val min = r.u32L()
            Limits(min, r.u32L())
        }
        else -> malformed("integer too large")
    }

    private fun tableType(r: WasmReader): TableType {
        val t = refType(r)
        return TableType(t, limits(r))
    }

    private fun globalType(r: WasmReader): GlobalType {
        val t = valType(r)
        return when (r.u8()) {
            0 -> GlobalType(t, false)
            1 -> GlobalType(t, true)
            else -> malformed("malformed mutability")
        }
    }

    private fun import(r: WasmReader): Import {
        val module = r.name()
        val name = r.name()
        return when (val kind = r.u8()) {
            ExternKind.FUNC -> Import(module, name, kind, r.u32(), null)
            ExternKind.TABLE -> Import(module, name, kind, -1, tableType(r))
            ExternKind.MEMORY -> Import(module, name, kind, -1, MemType(limits(r)))
            ExternKind.GLOBAL -> Import(module, name, kind, -1, globalType(r))
            else -> malformed("malformed import kind")
        }
    }

    private fun export(r: WasmReader): Export {
        val name = r.name()
        val kind = r.u8()
        if (kind > ExternKind.GLOBAL) malformed("malformed export kind")
        return Export(name, kind, r.u32())
    }

    /**
     * A constant expression: one constant instruction and `end`. Any other instruction is not constant (an invalid
     * module, found here since the decoder has to find the expression's end).
     */
    private fun constExpr(r: WasmReader): ConstExpr {
        var e: ConstExpr? = null
        var n = 0
        while (true) {
            val op = r.u8()
            val c = when (op) {
                0x0B -> break
                ConstExpr.I32_CONST -> ConstExpr(op, r.s32().toLong())
                ConstExpr.I64_CONST -> ConstExpr(op, r.s64())
                ConstExpr.F32_CONST -> ConstExpr(op, r.f32Bits().toLong() and 0xFFFFFFFFL)
                ConstExpr.F64_CONST -> ConstExpr(op, r.f64Bits())
                ConstExpr.REF_NULL -> ConstExpr(op, refType(r).toLong())
                ConstExpr.REF_FUNC -> ConstExpr(op, r.u32L())
                ConstExpr.GLOBAL_GET -> ConstExpr(op, r.u32L())
                else -> invalid("constant expression required")
            }
            e = c
            n++
        }
        if (n != 1) invalid("type mismatch")
        return e!!
    }

    private fun funcIndices(r: WasmReader): Array<ConstExpr> = vec(r) { ConstExpr(ConstExpr.REF_FUNC, r.u32L()) }

    private fun elemKind(r: WasmReader): Int {
        if (r.u8() != 0x00) malformed("malformed element kind")
        return ValType.FUNCREF
    }

    private fun elem(r: WasmReader): ElemSegment = when (r.u32()) {
        0 -> {
            val off = constExpr(r)
            ElemSegment(SegmentMode.ACTIVE, ValType.FUNCREF, 0, off, funcIndices(r))
        }
        1 -> ElemSegment(SegmentMode.PASSIVE, elemKind(r), 0, null, funcIndices(r))
        2 -> {
            val table = r.u32()
            val off = constExpr(r)
            val kind = elemKind(r)
            ElemSegment(SegmentMode.ACTIVE, kind, table, off, funcIndices(r))
        }
        3 -> ElemSegment(SegmentMode.DECLARATIVE, elemKind(r), 0, null, funcIndices(r))
        4 -> {
            val off = constExpr(r)
            ElemSegment(SegmentMode.ACTIVE, ValType.FUNCREF, 0, off, vec(r) { constExpr(r) })
        }
        5 -> {
            val t = refType(r)
            ElemSegment(SegmentMode.PASSIVE, t, 0, null, vec(r) { constExpr(r) })
        }
        6 -> {
            val table = r.u32()
            val off = constExpr(r)
            val t = refType(r)
            ElemSegment(SegmentMode.ACTIVE, t, table, off, vec(r) { constExpr(r) })
        }
        7 -> {
            val t = refType(r)
            ElemSegment(SegmentMode.DECLARATIVE, t, 0, null, vec(r) { constExpr(r) })
        }
        else -> malformed("malformed elements segment kind")
    }

    private fun data(r: WasmReader): DataSegment = when (r.u32()) {
        0 -> {
            val off = constExpr(r)
            DataSegment(SegmentMode.ACTIVE, 0, off, r.bytes(r.u32()))
        }
        1 -> DataSegment(SegmentMode.PASSIVE, 0, null, r.bytes(r.u32()))
        2 -> {
            val mem = r.u32()
            val off = constExpr(r)
            DataSegment(SegmentMode.ACTIVE, mem, off, r.bytes(r.u32()))
        }
        else -> malformed("malformed data segment kind")
    }

    private fun body(r: WasmReader): FuncBody {
        val size = r.u32()
        if (size > r.end - r.pos) malformed("unexpected end of section or function")
        val f = WasmReader(bytes, r.pos, r.pos + size)
        r.pos += size
        val groups = count(f)
        var total = 0L
        val counts = IntArray(groups)
        val kinds = IntArray(groups)
        for (g in 0 until groups) {
            val n = f.u32L()
            total += n
            // (an implementation's limit, as the JS API's: 50,000 locals)
            if (total > MAX_LOCALS) malformed("too many locals")
            counts[g] = n.toInt()
            kinds[g] = valType(f)
        }
        val locals = IntArray(total.toInt())
        var i = 0
        for (g in 0 until groups) repeat(counts[g]) { locals[i++] = kinds[g] }
        return FuncBody(locals, f.pos, f.end)
    }

    companion object {
        const val MAX_LOCALS = 50_000L
    }
}
