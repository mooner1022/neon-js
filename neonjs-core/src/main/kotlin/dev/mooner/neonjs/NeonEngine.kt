package dev.mooner.neonjs

import dev.mooner.neonjs.compiler.CodeBlock
import dev.mooner.neonjs.compiler.Compiler
import dev.mooner.neonjs.compiler.Source
import dev.mooner.neonjs.parser.JSSyntaxError
import dev.mooner.neonjs.parser.ParseOptions
import dev.mooner.neonjs.parser.Parser

/** How JS functions are executed. */
enum class ExecutionMode {
    /** Bytecode interpreter only (fast startup, no class generation). */
    INTERPRETER,
    /** Functions are compiled to JVM bytecode before their first call. */
    COMPILED,
    /**
     * Interpret first; compile hot functions to JVM bytecode after [NeonEngine.Builder.jitThreshold] calls (in the
     * background unless [NeonEngine.Builder.backgroundCompilation] is off: the function stays interpreted meanwhile).
     */
    ADAPTIVE,
}

/** Exception raised to the host for errors in JS code (uncaught JS exceptions, syntax errors, limits). */
open class NeonException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    /** The thrown JS value, if the error originated from a JS `throw` or built-in error. */
    var guestValue: NeonValue? = null
        internal set
    /** JS stack trace (frames), if available. */
    var jsStack: String? = null
        internal set
    open val isSyntaxError: Boolean get() = false
    open val isTermination: Boolean get() = false
}

class NeonSyntaxException(message: String, val line: Int, val column: Int, val sourceName: String?) : NeonException(message) {
    override val isSyntaxError get() = true
}

/** Execution was stopped by a sandbox limit or an interrupt; the JS code could not intercept it. */
open class NeonTerminatedException(message: String, cause: Throwable? = null) : NeonException(message, cause) {
    override val isTermination get() = true
}
class NeonTimeoutException(message: String) : NeonTerminatedException(message)
class NeonResourceLimitException(message: String) : NeonTerminatedException(message)
class NeonInterruptedException(message: String) : NeonTerminatedException(message)

/** A script compiled once and runnable in any context of the same engine. */
class NeonScript internal constructor(val engine: NeonEngine, internal val code: CodeBlock, val name: String)

/**
 * Entry point: an engine holds shared configuration (execution mode, sandbox policy, host access) and creates
 * isolated [NeonContext]s. Engines and compiled scripts are thread-safe; contexts are single-threaded.
 */
class NeonEngine private constructor(b: Builder) : AutoCloseable {
    val executionMode: ExecutionMode = b.executionMode
    val jitThreshold: Int = b.jitThreshold
    val backgroundCompilation: Boolean = b.backgroundCompilation
    val sandbox: SandboxPolicy = b.sandbox
    val hostAccess: HostAccess = b.hostAccess
    /** Automatically run pending Promise jobs when a top-level evaluation returns. */
    val autoRunJobs: Boolean = b.autoRunJobs
    val console: NeonConsole? = b.console
    /** Whether contexts get the web platform globals of [dev.mooner.neonjs.ext.WebGlobals]. */
    val webGlobals: Boolean = b.webGlobals
    /** Whether contexts get `Intl` (when the neonjs-intl module is on the class path). */
    val intl: Boolean = b.intl
    /** Defines generated classes (JIT code, Java.extend adapters); null = dev.mooner.neonjs.jit.CodeDefiners.default. */
    val codeDefiner: dev.mooner.neonjs.jit.CodeDefiner? = b.codeDefiner
    /** Engine modules installed into every context, in order ([Builder.extension]). */
    val extensions: List<NeonExtension> = b.extensions.toList()
    @Volatile private var closed = false

    fun newContext(): NeonContext {
        check(!closed) { "engine is closed" }
        return NeonContext(this)
    }

    /** Parses and compiles [source] for repeated execution. Throws [NeonSyntaxException]. */
    fun compile(source: String, name: String = "<script>"): NeonScript {
        val prog = try {
            Parser.parse(source, ParseOptions(sourceName = name))
        } catch (e: JSSyntaxError) {
            throw NeonSyntaxException(e.message, e.line, e.column, name)
        }
        return NeonScript(this, Compiler.compileScript(prog, Source(name, source)), name)
    }

    override fun close() {
        closed = true
    }

    class Builder {
        var executionMode = ExecutionMode.ADAPTIVE
        var jitThreshold = 1000
        var backgroundCompilation = true
        var sandbox = SandboxPolicy.UNRESTRICTED
        var hostAccess = HostAccess.NONE
        var autoRunJobs = true
        var console: NeonConsole? = NeonConsole.STDIO
        var webGlobals = false
        var intl = true
        var codeDefiner: dev.mooner.neonjs.jit.CodeDefiner? = null
        val extensions = ArrayList<NeonExtension>()

        fun executionMode(m: ExecutionMode) = apply { executionMode = m }

        /** Where the JS `console` object writes; `null` leaves `console` undefined. Default: stdout/stderr. */
        fun console(c: NeonConsole?) = apply { console = c }

        /**
         * Installs `queueMicrotask`, `setTimeout` / `setInterval` / `clearTimeout` / `clearInterval`, `atob` / `btoa`
         * and UTF-8 `TextEncoder` / `TextDecoder` in every context. Timers fire while the host runs the context's
         * event loop (`NeonContext.runEventLoop`, `NeonValue.await`) or later evaluations. Default: off.
         */
        fun webGlobals(enabled: Boolean) = apply { webGlobals = enabled }

        /**
         * Installs ECMA-402 `Intl` and the locale-sensitive built-in methods when the `neonjs-intl` module is on the
         * class path (default). With `false`, or without the module, `toLocaleString` and friends are locale-independent.
         */
        fun intl(enabled: Boolean) = apply { intl = enabled }

        /**
         * How classes generated at run time (JIT-compiled functions, `Java.extend` adapters) are defined. The default
         * is a supported [java.util.ServiceLoader] provider (e.g. neonjs-android's dex definer on Android), else the
         * standard JVM definer. Without a usable definer the engine runs interpreted and Java.extend is unavailable.
         */
        fun codeDefiner(d: dev.mooner.neonjs.jit.CodeDefiner) = apply { codeDefiner = d }

        /**
         * Rhino-style optimization level: -1 = interpreter only, 0..8 = adaptive (lower = compile later),
         * 9 = compile everything eagerly.
         */
        fun optimizationLevel(level: Int) = apply {
            when {
                level < 0 -> executionMode = ExecutionMode.INTERPRETER
                level >= 9 -> executionMode = ExecutionMode.COMPILED
                else -> {
                    executionMode = ExecutionMode.ADAPTIVE
                    jitThreshold = 1 shl (2 * (9 - level))
                }
            }
        }

        fun jitThreshold(n: Int) = apply { jitThreshold = n }

        /**
         * Whether functions are compiled by background threads (default): in adaptive mode a hot function keeps running
         * in the interpreter until its code is ready, and functions that become hot together are compiled together
         * (one dex file on Android); in compiled mode the functions a script defines are compiled in parallel while it
         * runs. With `false`, every function is compiled on the thread calling it. The workers are shared by all
         * engines (`-Dneonjs.jit.threads=n`, 0 disables them) and their work is not charged to a context's limits.
         */
        fun backgroundCompilation(enabled: Boolean) = apply { backgroundCompilation = enabled }
        fun sandbox(p: SandboxPolicy) = apply { sandbox = p }
        fun hostAccess(a: HostAccess) = apply { hostAccess = a }
        fun autoRunJobs(b: Boolean) = apply { autoRunJobs = b }

        /**
         * Installs an engine module into every context (e.g. neonjs-node's `NodeExtension`), after the built-ins, the
         * console and the web globals. Extensions are never found on the class path: an application opts in here.
         */
        fun extension(e: NeonExtension) = apply { extensions.add(e) }
        fun build() = NeonEngine(this)
    }

    companion object {
        @JvmStatic fun builder() = Builder()
        @JvmStatic fun create() = Builder().build()
    }
}
