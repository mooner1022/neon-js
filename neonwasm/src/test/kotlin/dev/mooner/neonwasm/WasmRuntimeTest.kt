package dev.mooner.neonwasm

import dev.mooner.neonjs.runtime.ExecutionTimeoutException
import dev.mooner.neonjs.runtime.InterruptedExecutionException
import dev.mooner.neonjs.runtime.ResourceLimitException
import dev.mooner.neonjs.runtime.VmConfig
import dev.mooner.neonwasm.WasmBytes.F64
import dev.mooner.neonwasm.WasmBytes.I32
import dev.mooner.neonwasm.WasmBytes.body
import dev.mooner.neonwasm.WasmBytes.bytes
import dev.mooner.neonwasm.WasmBytes.code
import dev.mooner.neonwasm.WasmBytes.export
import dev.mooner.neonwasm.WasmBytes.exports
import dev.mooner.neonwasm.WasmBytes.funcType
import dev.mooner.neonwasm.WasmBytes.funcs
import dev.mooner.neonwasm.WasmBytes.importFunc
import dev.mooner.neonwasm.WasmBytes.imports
import dev.mooner.neonwasm.WasmBytes.memory
import dev.mooner.neonwasm.WasmBytes.module
import dev.mooner.neonwasm.WasmBytes.types
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/** The host's side: limits of the execution core, calls both ways, values as the API gives them. */
@Timeout(60)
class WasmRuntimeTest {
    /** `(func (export "spin") (loop (br 0)))` */
    private val spin = module(
        types(funcType(listOf(), listOf())),
        funcs(0),
        exports(export("spin", ExternKind.FUNC, 0)),
        code(body(listOf(), bytes(0x03, 0x40, 0x0C, 0x00, 0x0B, 0x0B))),
    )

    private fun instance(bytes: ByteArray, config: VmConfig = VmConfig(), imports: WasmImports = WasmImports.NONE) =
        WasmContext(config).instantiate(WasmModule.decode(bytes), imports)

    @Test
    fun aLoopStopsAtTheTimeLimit() {
        val f = instance(spin, VmConfig().apply { maxExecutionMillis = 200 }).function("spin")!!
        assertThrows(ExecutionTimeoutException::class.java) { f.call() }
    }

    @Test
    fun aLoopStopsAtTheInstructionLimit() {
        val f = instance(spin, VmConfig().apply { maxStatements = 1_000_000 }).function("spin")!!
        assertThrows(ResourceLimitException::class.java) { f.call() }
    }

    @Test
    fun aLoopStopsWhenInterrupted() {
        val inst = instance(spin)
        val t = Thread {
            Thread.sleep(100)
            inst.context.agent.requestInterrupt()
        }
        t.start()
        assertThrows(InterruptedExecutionException::class.java) { inst.function("spin")!!.call() }
        t.join()
    }

    @Test
    fun recursionPastTheCallDepthTrapsAndTheContextGoesOn() {
        // (func $f (export "f") (call $f)), (func (export "one") (result i32) (i32.const 1))
        val inst = instance(module(
            types(funcType(listOf(), listOf()), funcType(listOf(), listOf(I32))),
            funcs(0, 1),
            exports(export("f", ExternKind.FUNC, 0), export("one", ExternKind.FUNC, 1)),
            code(body(listOf(), bytes(0x10, 0x00, 0x0B)), body(listOf(), bytes(0x41, 0x01, 0x0B))),
        ), VmConfig().apply { maxCallDepth = 500 })
        val e = assertThrows(WasmTrap::class.java) { inst.function("f")!!.call() }
        assertEquals("call stack exhausted", e.message)
        assertEquals(0, inst.context.agent.depth)
        assertArrayEquals(arrayOf<Any?>(1), inst.function("one")!!.call())
    }

    @Test
    fun hostFunctionsAreCalledAndCallBack() {
        // (import "env" "add" (func (param i32 i32) (result i32)))
        // (func (export "twice") (param i32) (result i32) (call 0 (local.get 0) (local.get 0)))
        // (func (export "inc") (param i32) (result i32) (i32.add (local.get 0) (i32.const 1)))
        var inst: WasmInstance? = null
        var reentered = 0
        val add = HostFunction(FuncType(intArrayOf(I32, I32), intArrayOf(I32))) { args ->
            // add(a, b) as inc(a) + b - 1: a call back into the module from inside its own call
            reentered++
            arrayOf((inst!!.function("inc")!!.call(args[0])[0] as Int) + (args[1] as Int) - 1)
        }
        inst = instance(module(
            types(funcType(listOf(I32, I32), listOf(I32)), funcType(listOf(I32), listOf(I32))),
            imports(importFunc("env", "add", 0)),
            funcs(1, 1),
            exports(export("twice", ExternKind.FUNC, 1), export("inc", ExternKind.FUNC, 2)),
            code(
                body(listOf(), bytes(0x20, 0x00, 0x20, 0x00, 0x10, 0x00, 0x0B)),
                body(listOf(), bytes(0x20, 0x00, 0x41, 0x01, 0x6A, 0x0B)),
            ),
        ), imports = { m, n -> if (m == "env" && n == "add") add else null })
        assertArrayEquals(arrayOf<Any?>(42), inst.function("twice")!!.call(21))
        assertEquals(1, reentered)
    }

    @Test
    fun aHostExceptionPassesThroughAndTheContextGoesOn() {
        val boom = HostFunction(FuncType(intArrayOf(), intArrayOf())) { throw IllegalStateException("boom") }
        // (import "env" "boom" (func)) (func (export "f") (call 0)) (func (export "one") (result i32) (i32.const 1))
        val inst = instance(module(
            types(funcType(listOf(), listOf()), funcType(listOf(), listOf(I32))),
            imports(importFunc("env", "boom", 0)),
            funcs(0, 1),
            exports(export("f", ExternKind.FUNC, 1), export("one", ExternKind.FUNC, 2)),
            code(body(listOf(), bytes(0x10, 0x00, 0x0B)), body(listOf(), bytes(0x41, 0x01, 0x0B))),
        ), imports = { _, _ -> boom })
        val e = assertThrows(IllegalStateException::class.java) { inst.function("f")!!.call() }
        assertEquals("boom", e.message)
        assertEquals(0, inst.context.agent.depth)
        assertArrayEquals(arrayOf<Any?>(1), inst.function("one")!!.call())
    }

    @Test
    fun anUnknownImportDoesNotLink() {
        val m = WasmModule.decode(module(
            types(funcType(listOf(), listOf())),
            imports(importFunc("env", "missing", 0)),
        ))
        val e = assertThrows(WasmLinkException::class.java) { WasmContext().instantiate(m) }
        assertEquals("unknown import env.missing", e.message)
    }

    /** `(memory 1 max) (func (export "grow") (param i32) (result i32) (memory.grow (local.get 0)))` */
    private fun growable(max: Int) = module(
        types(funcType(listOf(I32), listOf(I32))),
        funcs(0),
        memory(1, max),
        exports(export("grow", ExternKind.FUNC, 0)),
        code(body(listOf(), bytes(0x20, 0x00, 0x40, 0x00, 0x0B))),
    )

    @Test
    fun memoryGrowsToItsMaximum() {
        val grow = instance(growable(3)).function("grow")!!
        assertArrayEquals(arrayOf<Any?>(1), grow.call(1))
        assertArrayEquals(arrayOf<Any?>(2), grow.call(1))
        assertArrayEquals(arrayOf<Any?>(-1), grow.call(1))
    }

    @Test
    fun memoryGrowsWithinTheAllocationLimit() {
        val grow = instance(growable(-1), VmConfig().apply { maxAllocatedBytes = 4L shl 20 }).function("grow")!!
        // 100 pages, 6.5 MB, past the limit of 4 MB: refused as memory.grow may be, not an error
        assertArrayEquals(arrayOf<Any?>(-1), grow.call(100))
        assertArrayEquals(arrayOf<Any?>(1), grow.call(10))
    }

    @Test
    fun floatsCrossAsTheyAre() {
        // (func (export "add") (param f64 f64) (result f64) (f64.add (local.get 0) (local.get 1)))
        val add = instance(module(
            types(funcType(listOf(F64, F64), listOf(F64))),
            funcs(0),
            exports(export("add", ExternKind.FUNC, 0)),
            code(body(listOf(), bytes(0x20, 0x00, 0x20, 0x01, 0xA0, 0x0B))),
        )).function("add")!!
        assertArrayEquals(arrayOf<Any?>(3.75), add.call(1.5, 2.25))
    }
}
