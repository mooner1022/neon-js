package dev.mooner.neonwasm

/**
 * A function's code for the interpreter, as validation produces it: wasm's instructions with their immediates
 * decoded, blocks gone, and every branch a jump to a resolved position that moves its values to a resolved stack
 * height. Positions and heights are known statically: a height is a slot index relative to the frame, whose first
 * [nLocals] slots are the locals (the parameters first), the operand stack above them.
 */
internal class Code(
    @JvmField val type: FuncType,
    @JvmField val code: IntArray,
    /** parameters and declared locals */
    @JvmField val nLocals: Int,
    /** whether each local holds a reference (kept in the frame's reference slots) */
    @JvmField val refLocals: BooleanArray,
    /** slots the frame needs: its locals and its deepest operand stack */
    @JvmField val frameSize: Int,
    @JvmField val index: Int,
) {
    @JvmField val nParams = type.params.size
    @JvmField val nResults = type.results.size
    @JvmField val hasRefLocals = refLocals.any { it }
}

/**
 * The interpreter's instructions. Numeric instructions without immediates keep their wasm opcodes (0x45-0xC4), as do
 * most others; the rest are numbered in wasm's gaps. Immediates follow the opcode in [Code.code].
 */
internal object Op {
    const val UNREACHABLE = 0x00
    /** pc: jump, no values moved */
    const val JUMP = 0x02
    /** pc, arity, height: the top arity values moved to height, then a jump */
    const val BR = 0x03
    /** pc: pops an i32, jumps where it is zero */
    const val IF = 0x04
    /** pc: pops an i32, jumps where it is not zero (no values moved) */
    const val BR_IF_JUMP = 0x06
    /** a loop's header: counts toward the interrupt check */
    const val LOOP = 0x07
    /** pc, arity, height */
    const val BR_IF = 0x0D
    /** n, then n + 1 targets (the last the default) of pc, arity, height */
    const val BR_TABLE = 0x0E
    const val RETURN = 0x0F
    /** function index */
    const val CALL = 0x10
    /** type index, table index */
    const val CALL_INDIRECT = 0x11
    /** local index (references) */
    const val LOCAL_GET_REF = 0x12
    const val LOCAL_SET_REF = 0x13
    const val LOCAL_TEE_REF = 0x14
    /** global index (references) */
    const val GLOBAL_GET_REF = 0x15
    const val GLOBAL_SET_REF = 0x16
    const val DROP = 0x1A
    const val SELECT = 0x1B
    const val SELECT_REF = 0x1C
    /** local index */
    const val LOCAL_GET = 0x20
    const val LOCAL_SET = 0x21
    const val LOCAL_TEE = 0x22
    /** global index */
    const val GLOBAL_GET = 0x23
    const val GLOBAL_SET = 0x24
    /** table index */
    const val TABLE_GET = 0x25
    const val TABLE_SET = 0x26
    // 0x28-0x3E loads and stores: offset (a u32 as an Int)
    const val I32_LOAD = 0x28
    const val I64_LOAD = 0x29
    const val F32_LOAD = 0x2A
    const val F64_LOAD = 0x2B
    const val I32_LOAD8_S = 0x2C
    const val I32_LOAD8_U = 0x2D
    const val I32_LOAD16_S = 0x2E
    const val I32_LOAD16_U = 0x2F
    const val I64_LOAD8_S = 0x30
    const val I64_LOAD8_U = 0x31
    const val I64_LOAD16_S = 0x32
    const val I64_LOAD16_U = 0x33
    const val I64_LOAD32_S = 0x34
    const val I64_LOAD32_U = 0x35
    const val I32_STORE = 0x36
    const val I64_STORE = 0x37
    const val F32_STORE = 0x38
    const val F64_STORE = 0x39
    const val I32_STORE8 = 0x3A
    const val I32_STORE16 = 0x3B
    const val I64_STORE8 = 0x3C
    const val I64_STORE16 = 0x3D
    const val I64_STORE32 = 0x3E
    const val MEMORY_SIZE = 0x3F
    const val MEMORY_GROW = 0x40
    /** value */
    const val I32_CONST = 0x41
    /** low, high */
    const val I64_CONST = 0x42
    /** bits */
    const val F32_CONST = 0x43
    /** low bits, high bits */
    const val F64_CONST = 0x44
    // 0x45-0xC4: numeric instructions, no immediates
    const val REF_NULL = 0xD0
    const val REF_IS_NULL = 0xD1
    /** function index */
    const val REF_FUNC = 0xD2
    // 0xFC-prefixed: 0xE0 + the sub-opcode
    const val TRUNC_SAT_BASE = 0xE0
    /** data index */
    const val MEMORY_INIT = 0xE8
    const val DATA_DROP = 0xE9
    const val MEMORY_COPY = 0xEA
    const val MEMORY_FILL = 0xEB
    /** elem index, table index */
    const val TABLE_INIT = 0xEC
    /** elem index */
    const val ELEM_DROP = 0xED
    /** destination table, source table */
    const val TABLE_COPY = 0xEE
    /** table index */
    const val TABLE_GROW = 0xEF
    const val TABLE_SIZE = 0xF0
    const val TABLE_FILL = 0xF1
}

/** A growable IntArray. */
internal class IntList(capacity: Int = 64) {
    @JvmField var a = IntArray(capacity)
    @JvmField var size = 0

    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(a.size * 2)
        a[size++] = v
    }

    operator fun get(i: Int) = a[i]
    operator fun set(i: Int, v: Int) {
        a[i] = v
    }

    fun toArray(): IntArray = a.copyOf(size)
}
