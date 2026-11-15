package dev.mooner.neonwasm.wasi

import dev.mooner.neonjs.runtime.ExecutionTimeoutException
import dev.mooner.neonjs.runtime.VmConfig
import dev.mooner.neonwasm.ExternKind
import dev.mooner.neonwasm.WasmBytes.I32
import dev.mooner.neonwasm.WasmBytes.body
import dev.mooner.neonwasm.WasmBytes.bytes
import dev.mooner.neonwasm.WasmBytes.code
import dev.mooner.neonwasm.WasmBytes.data
import dev.mooner.neonwasm.WasmBytes.export
import dev.mooner.neonwasm.WasmBytes.exports
import dev.mooner.neonwasm.WasmBytes.funcType
import dev.mooner.neonwasm.WasmBytes.funcs
import dev.mooner.neonwasm.WasmBytes.importFunc
import dev.mooner.neonwasm.WasmBytes.imports
import dev.mooner.neonwasm.WasmBytes.memory
import dev.mooner.neonwasm.WasmBytes.module
import dev.mooner.neonwasm.WasmBytes.types
import dev.mooner.neonwasm.WasmContext
import dev.mooner.neonwasm.WasmModule
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files

/** What the WASI testsuite does not check: the host's limits over WASI's waits, and the file system's isolation. */
@Timeout(60)
class WasiTest {
    /** `_start` polls a monotonic clock of 10 s: `poll_oneoff(subscription at 0, events at 64, 1, nevents at 128)`. */
    private val sleeper = module(
        types(funcType(listOf(I32, I32, I32, I32), listOf(I32)), funcType(listOf(), listOf())),
        imports(importFunc("wasi_snapshot_preview1", "poll_oneoff", 0)),
        funcs(1),
        memory(1),
        exports(export("memory", ExternKind.MEMORY, 0), export("_start", ExternKind.FUNC, 1)),
        code(body(listOf(), bytes(0x41, 0x00, 0x41, 0x40, 0x41, 0x01, 0x41, 0x80, 0x01, 0x10, 0x00, 0x1A, 0x0B))),
        data(0, ByteArray(48).also { s ->
            // tag 0 (clock) at 8; clock id 1 (monotonic) at 16; timeout 10 s at 24
            s[16] = 1
            java.nio.ByteBuffer.wrap(s, 24, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(10_000_000_000L)
        }),
    )

    @Test
    fun aWaitStopsAtTheTimeLimit() {
        val wasi = Wasi(WasiConfig.builder().args("sleeper").build())
        val start = System.nanoTime()
        assertThrows(ExecutionTimeoutException::class.java) {
            wasi.run(WasmContext(VmConfig().apply { maxExecutionMillis = 300 }), WasmModule.decode(sleeper))
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(ms < 2_000, "the wait went on for $ms ms")
    }

    @Test
    fun aHostDirectoryIsCopiedNotOpened() {
        val dir = Files.createTempDirectory("wasi")
        try {
            Files.write(dir.resolve("file"), byteArrayOf(1, 2, 3))
            val fs = WasiFs.fromHost(dir)
            val f = fs.root.entries["file"] as WasiFs.File
            assertArrayEquals(byteArrayOf(1, 2, 3), f.data.copyOf(f.length.toInt()))
            // a change in memory is not one on the host
            f.resize(0)
            assertArrayEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(dir.resolve("file")))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
