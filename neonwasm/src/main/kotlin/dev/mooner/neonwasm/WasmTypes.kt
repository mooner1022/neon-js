package dev.mooner.neonwasm

/**
 * Value types, as their binary encodings (WebAssembly 2.0, §5.3.1). Kept as Ints everywhere: the validator's operand
 * stack holds them, with [UNKNOWN] for a value of unreachable code.
 */
object ValType {
    const val I32 = 0x7F
    const val I64 = 0x7E
    const val F32 = 0x7D
    const val F64 = 0x7C
    const val V128 = 0x7B
    const val FUNCREF = 0x70
    const val EXTERNREF = 0x6F
    /** The operand of unreachable code: matches any type (the validation algorithm's "Unknown"). */
    const val UNKNOWN = -1

    @JvmStatic fun isNum(t: Int) = t == I32 || t == I64 || t == F32 || t == F64
    @JvmStatic fun isRef(t: Int) = t == FUNCREF || t == EXTERNREF
    @JvmStatic fun isValue(t: Int) = isNum(t) || isRef(t)

    @JvmStatic fun name(t: Int): String = when (t) {
        I32 -> "i32"
        I64 -> "i64"
        F32 -> "f32"
        F64 -> "f64"
        V128 -> "v128"
        FUNCREF -> "funcref"
        EXTERNREF -> "externref"
        UNKNOWN -> "unknown"
        else -> "0x" + Integer.toHexString(t)
    }
}

/** A function type: its parameters' and results' value types. Equal by structure, as call_indirect compares them. */
class FuncType(@JvmField val params: IntArray, @JvmField val results: IntArray) {
    override fun equals(other: Any?) =
        other === this || other is FuncType && params.contentEquals(other.params) && results.contentEquals(other.results)

    override fun hashCode() = params.contentHashCode() * 31 + results.contentHashCode()

    override fun toString() = "(func" + params.joinToString("") { " (param ${ValType.name(it)})" } +
        results.joinToString("") { " (result ${ValType.name(it)})" } + ")"

    companion object {
        @JvmField val EMPTY = FuncType(IntArray(0), IntArray(0))
    }
}

/** Limits of a table (elements) or a memory (pages): unsigned 32-bit values, [max] -1 where there is none. */
class Limits(@JvmField val min: Long, @JvmField val max: Long) {
    val hasMax: Boolean get() = max >= 0

    /** Import matching (§4.5.2): at least as large, and a maximum where the import has one, no larger. */
    fun matches(required: Limits) = min >= required.min && (!required.hasMax || hasMax && max <= required.max)

    override fun toString() = if (hasMax) "$min $max" else "$min"
}

class TableType(@JvmField val elemType: Int, @JvmField val limits: Limits)
class MemType(@JvmField val limits: Limits)
class GlobalType(@JvmField val valType: Int, @JvmField val mutable: Boolean)

/** The kinds of imports and exports, as encoded. */
object ExternKind {
    const val FUNC = 0
    const val TABLE = 1
    const val MEMORY = 2
    const val GLOBAL = 3

    fun name(k: Int) = when (k) {
        FUNC -> "func"
        TABLE -> "table"
        MEMORY -> "memory"
        GLOBAL -> "global"
        else -> "kind $k"
    }
}

/** Base of NeonWasm's errors. */
open class WasmException(message: String) : RuntimeException(message)

/** A module's binary is not well-formed (decoding, §5). */
class WasmMalformedException(message: String) : WasmException(message)

/** A module is well-formed but not valid (validation, §3). */
class WasmInvalidException(message: String) : WasmException(message)

/** A module's imports cannot be resolved or do not match (instantiation, §4.5.4 steps 1-4). */
class WasmLinkException(message: String) : WasmException(message)

/**
 * A trap (§4.4.1): execution stopped by an instruction, a segment out of bounds during instantiation, or the call
 * stack exhausted. Code past the trap does not run; what it changed in memories, tables and globals stays.
 */
class WasmTrap(message: String) : WasmException(message)
