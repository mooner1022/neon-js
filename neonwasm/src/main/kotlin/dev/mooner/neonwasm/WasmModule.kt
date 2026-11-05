package dev.mooner.neonwasm

/**
 * A constant expression (§3.3.10): in WebAssembly 2.0 one instruction (`*.const`, `ref.null`, `ref.func`, `global.get`
 * of an imported global). [op] is its opcode, [imm] its immediate (a value's bits, a reference type, an index).
 */
class ConstExpr(@JvmField val op: Int, @JvmField val imm: Long) {
    companion object {
        const val I32_CONST = 0x41
        const val I64_CONST = 0x42
        const val F32_CONST = 0x43
        const val F64_CONST = 0x44
        const val REF_NULL = 0xD0
        const val REF_FUNC = 0xD2
        const val GLOBAL_GET = 0x23
    }
}

class Import(@JvmField val module: String, @JvmField val name: String, @JvmField val kind: Int,
             /** the function's type index ([ExternKind.FUNC]), else a [TableType], [MemType] or [GlobalType] */
             @JvmField val typeIndex: Int, @JvmField val type: Any?)

class Export(@JvmField val name: String, @JvmField val kind: Int, @JvmField val index: Int)

class Global(@JvmField val type: GlobalType, @JvmField val init: ConstExpr)

/** An element segment (§2.5.7): its mode, and its elements as constant expressions. */
class ElemSegment(@JvmField val mode: Int, @JvmField val elemType: Int, @JvmField val table: Int,
                  @JvmField val offset: ConstExpr?, @JvmField val init: Array<ConstExpr>)

/** A data segment (§2.5.8): its bytes, and where they go if it is active. */
class DataSegment(@JvmField val mode: Int, @JvmField val memory: Int, @JvmField val offset: ConstExpr?,
                  @JvmField val bytes: ByteArray)

/** Segment modes. */
object SegmentMode {
    const val ACTIVE = 0
    const val PASSIVE = 1
    const val DECLARATIVE = 2
}

/** A function body as decoded: its declared locals' types (after the parameters) and where its code is. */
class FuncBody(@JvmField val locals: IntArray, @JvmField val codeStart: Int, @JvmField val codeEnd: Int)

/**
 * A decoded and validated module (§2.5): [decode] checks the binary format and validates, so that a [WasmModule]
 * can always be instantiated (its imports permitting). Function bodies are compiled to the interpreter's code
 * ([Code]) during validation.
 */
class WasmModule internal constructor(
    @JvmField val bytes: ByteArray,
    @JvmField val types: Array<FuncType>,
    @JvmField val imports: Array<Import>,
    /** the type index of each function, imported ones first */
    @JvmField val funcTypes: IntArray,
    @JvmField val tables: Array<TableType>,
    @JvmField val memories: Array<MemType>,
    @JvmField val globals: Array<Global>,
    @JvmField val exports: Array<Export>,
    @JvmField val start: Int,
    @JvmField val elems: Array<ElemSegment>,
    @JvmField val datas: Array<DataSegment>,
    @JvmField val dataCount: Int,
    @JvmField val bodies: Array<FuncBody>,
) {
    @JvmField val importedFuncs = imports.count { it.kind == ExternKind.FUNC }
    @JvmField val importedTables = imports.count { it.kind == ExternKind.TABLE }
    @JvmField val importedMemories = imports.count { it.kind == ExternKind.MEMORY }
    @JvmField val importedGlobals = imports.count { it.kind == ExternKind.GLOBAL }

    /** Every table's type, imported ones first. */
    @JvmField val allTables: Array<TableType> =
        (imports.filter { it.kind == ExternKind.TABLE }.map { it.type as TableType } + tables).toTypedArray()
    @JvmField val allMemories: Array<MemType> =
        (imports.filter { it.kind == ExternKind.MEMORY }.map { it.type as MemType } + memories).toTypedArray()
    @JvmField val allGlobals: Array<GlobalType> =
        (imports.filter { it.kind == ExternKind.GLOBAL }.map { it.type as GlobalType } + globals.map { it.type }).toTypedArray()

    /** The interpreter's code of each function defined here (not imported), set by validation. */
    internal lateinit var codes: Array<Code>

    fun funcType(index: Int): FuncType = types[funcTypes[index]]

    companion object {
        /** Decodes and validates a binary module; throws [WasmMalformedException] or [WasmInvalidException]. */
        @JvmStatic
        fun decode(bytes: ByteArray): WasmModule {
            val m = WasmDecoder(bytes).decode()
            WasmValidator(m).validate()
            return m
        }
    }
}
