package dev.mooner.neonwasm

import dev.mooner.neonwasm.WasmBytes.I32
import dev.mooner.neonwasm.WasmBytes.body
import dev.mooner.neonwasm.WasmBytes.bytes
import dev.mooner.neonwasm.WasmBytes.code
import dev.mooner.neonwasm.WasmBytes.export
import dev.mooner.neonwasm.WasmBytes.exports
import dev.mooner.neonwasm.WasmBytes.funcType
import dev.mooner.neonwasm.WasmBytes.funcs
import dev.mooner.neonwasm.WasmBytes.module
import dev.mooner.neonwasm.WasmBytes.types
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Decoding and validation of a few modules; the spec tests (SpecTest) check the rest. */
class WasmValidationTest {
    private fun identity(body: ByteArray) = module(
        types(funcType(listOf(I32), listOf(I32))),
        funcs(0),
        exports(export("f", ExternKind.FUNC, 0)),
        code(body(listOf(), body)),
    )

    @Test
    fun aValidModuleDecodes() {
        val m = WasmModule.decode(identity(bytes(0x20, 0x00, 0x0B)))
        assertEquals(1, m.funcTypes.size)
        assertEquals("f", m.exports.single().name)
        assertEquals(FuncType(intArrayOf(ValType.I32), intArrayOf(ValType.I32)), m.funcType(0))
    }

    @Test
    fun malformedModulesAreRejected() {
        fun message(b: ByteArray) = assertThrows(WasmMalformedException::class.java) { WasmModule.decode(b) }.message
        assertEquals("magic header not detected", message(bytes(1, 2, 3, 4)))
        assertEquals("unknown binary version", message(bytes(0, 0x61, 0x73, 0x6D, 2, 0, 0, 0)))
        // the function section after the code section
        assertEquals("unexpected content after last section", message(module(
            types(funcType(listOf(), listOf())), code(body(listOf(), bytes(0x0B))), funcs(0))))
        // a body that ends before its last `end`
        assertEquals("unexpected end", message(identity(bytes(0x20, 0x00))))
    }

    @Test
    fun invalidModulesAreRejected() {
        fun message(b: ByteArray) = assertThrows(WasmInvalidException::class.java) { WasmModule.decode(b) }.message
        // nothing left for the result
        assertEquals("type mismatch", message(identity(bytes(0x0B))))
        assertEquals("unknown local 5", message(identity(bytes(0x20, 0x05, 0x0B))))
        // br 3 from the function's own block
        assertEquals("unknown label", message(identity(bytes(0x20, 0x00, 0x0C, 0x03, 0x0B))))
        // an i64 where the function returns an i32
        assertEquals("type mismatch", message(identity(bytes(0x42, 0x00, 0x0B))))
    }
}
