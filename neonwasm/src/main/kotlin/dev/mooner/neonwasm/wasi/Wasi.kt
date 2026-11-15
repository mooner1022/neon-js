package dev.mooner.neonwasm.wasi

import dev.mooner.neonwasm.FuncType
import dev.mooner.neonwasm.HostFunction
import dev.mooner.neonwasm.ValType
import dev.mooner.neonwasm.WasmContext
import dev.mooner.neonwasm.WasmImports
import dev.mooner.neonwasm.WasmInstance
import dev.mooner.neonwasm.WasmMemory
import dev.mooner.neonwasm.WasmModule
import java.io.IOException

/**
 * WASI preview 1 (`wasi_snapshot_preview1`) for one program: its descriptors (the standard streams, then the
 * preopened directories), over [config]'s streams, in-memory file system, clocks and randomness.
 *
 * ```
 * val wasi = Wasi(WasiConfig.builder().args("prog").stdout(out).build())
 * val code = wasi.run(WasmContext(), WasmModule.decode(bytes))
 * ```
 */
class Wasi(@JvmField val config: WasiConfig) {
    internal val fds = HashMap<Int, Fd>()
    private var memory: GuestMemory? = null
    /** The agent of the context [run] runs in: its limits end waits (poll_oneoff). */
    internal var agent: dev.mooner.neonjs.runtime.VmAgent? = null

    init {
        fds[0] = Fd.Stream(config.stdin, null)
        fds[1] = Fd.Stream(null, config.stdout)
        fds[2] = Fd.Stream(null, config.stderr)
        for ((i, p) in config.preopens.withIndex()) fds[3 + i] = Fd.Directory(p.second, p.first)
    }

    internal val mem: GuestMemory get() = memory ?: fail(Errno.FAULT)

    /** Binds the instance's exported memory, which every call reads and writes (done by [run]). */
    fun bind(instance: WasmInstance) {
        memory = GuestMemory(instance.memory("memory") ?: throw IllegalArgumentException("the module exports no memory"))
    }

    /** Binds [memory] directly (a module whose memory is not exported as "memory"). */
    fun bind(memory: WasmMemory) {
        this.memory = GuestMemory(memory)
    }

    /**
     * Instantiates [module] with WASI's functions (and [other] imports), runs its `_start`, and gives the exit code:
     * proc_exit's, or 0 where `_start` returns. A trap is a [dev.mooner.neonwasm.WasmTrap].
     */
    fun run(context: WasmContext, module: WasmModule, other: WasmImports = WasmImports.NONE): Int {
        agent = context.agent
        val wasi = imports()
        val inst = try {
            context.instantiate(module) { m, n -> wasi.resolve(m, n) ?: other.resolve(m, n) }
        } catch (e: WasiExit) {
            return e.code
        }
        bind(inst)
        val start = inst.function("_start") ?: throw IllegalArgumentException("the module exports no _start")
        return try {
            start.call()
            0
        } catch (e: WasiExit) {
            e.code
        } finally {
            flush()
        }
    }

    private fun flush() {
        try { config.stdout?.flush() } catch (_: IOException) {}
        try { config.stderr?.flush() } catch (_: IOException) {}
    }

    /** WASI's functions, as imports of module `wasi_snapshot_preview1`. */
    fun imports(): WasmImports = WasmImports { m, n -> if (m == MODULE) functions[n] else null }

    private val functions: Map<String, HostFunction> by lazy { WasiFunctions(this).all() }

    internal fun fd(n: Int): Fd = fds[n] ?: fail(Errno.BADF)

    companion object {
        const val MODULE = "wasi_snapshot_preview1"
    }
}

/** A descriptor: a stream, an open file or an open directory. */
internal sealed class Fd(@JvmField var rightsBase: Long, @JvmField var rightsInheriting: Long) {
    @JvmField var flags = 0
    abstract val fileType: Int

    class Stream(@JvmField val input: java.io.InputStream?, @JvmField val output: java.io.OutputStream?) :
        Fd(if (input != null) Rights.STDIN else Rights.STDOUT, 0) {
        override val fileType get() = FileType.CHARACTER_DEVICE
    }

    class File(@JvmField val node: WasiFs.File, @JvmField val readable: Boolean, @JvmField val writable: Boolean) :
        Fd(Rights.FILE_BASE or (if (readable) Rights.FD_READ else 0) or (if (writable) Rights.FD_WRITE else 0), 0) {
        @JvmField var pos = 0L
        override val fileType get() = FileType.REGULAR_FILE
    }

    class Directory(@JvmField val node: WasiFs.Dir,
                    /** the name it is preopened under, null for a directory opened by path_open */
                    @JvmField val preopen: String? = null) : Fd(Rights.DIR_BASE, Rights.DIR_INHERITING) {
        override val fileType get() = FileType.DIRECTORY
    }
}

/** Builds [HostFunction]s from a signature ("i" an i32, "I" an i64) and a body returning the errno. */
internal fun wasiFunction(sig: String, body: (Array<Any?>) -> Int): HostFunction {
    val params = IntArray(sig.length) { if (sig[it] == 'I') ValType.I64 else ValType.I32 }
    return HostFunction(FuncType(params, intArrayOf(ValType.I32))) { args ->
        val errno = try {
            body(args)
        } catch (e: WasiError) {
            e.errno
        }
        arrayOf(errno)
    }
}
