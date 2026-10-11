package dev.mooner.neonjs.node

import dev.mooner.neonjs.NeonConsole
import dev.mooner.neonjs.NeonContext
import dev.mooner.neonjs.NeonEngine
import dev.mooner.neonjs.ext.WebGlobals
import dev.mooner.neonjs.runtime.*
import java.math.BigInteger

/**
 * The Kotlin side of the JS libraries: the `binding` argument of their module functions (never a property of
 * anything scripts reach).
 */
internal class Binding(private val rt: NodeRuntime) {
    private val realm = rt.realm
    private val startNanos = System.nanoTime()
    private val pending = arrayOf(StringBuilder(), StringBuilder())

    /** A task of the realm's timers, as `binding.schedule` returns it to JS. */
    class TaskHandle(@JvmField val task: WebGlobals.ScheduledTask) : JSObject(null)

    fun create(): JSObject {
        val b = JSObject(null)
        fun fn(name: String, length: Int, impl: NativeImpl) = b.defineOwn(name, NativeFunction(realm, name, length, impl), Attr.NONE)

        // an Error (or TypeError, RangeError) with Node's code property, to throw or reject with
        fn("error", 3) { _, _, a, _ ->
            val kind = when (Ops.toString(a.arg(0))) {
                "TypeError" -> ErrorKind.TYPE
                "RangeError" -> ErrorKind.RANGE
                else -> ErrorKind.ERROR
            }
            rt.nodeError(kind, Ops.toString(a.arg(1)), Ops.toString(a.arg(2))).value
        }
        // Node's AbortError: an Error named AbortError with code ABORT_ERR and the signal's reason as cause
        fn("abortError", 1) { _, _, a, _ ->
            val e = realm.newError(ErrorKind.ERROR, "The operation was aborted")
            e.createDataProperty("name", "AbortError")
            e.createDataProperty("code", "ABORT_ERR")
            if (a.isNotEmpty() && a[0] !== Undefined) e.createDataProperty("cause", a[0])
            e
        }
        fn("nextTick", 2) { f, _, a, _ ->
            val cb = a.arg(0)
            val args = (a.arg(1) as? JSArray)?.let { arr -> Array(arr.length.toInt()) { arr.get(PK.fromIndex(it.toLong()), arr) } } ?: EMPTY_ARGS
            f.realm.agent.enqueueTick { Ops.call(cb, Undefined, args) }
            Undefined
        }

        // ---------------- timers
        fn("schedule", 3) { f, _, a, _ ->
            val cb = a.arg(0)
            val ms = Ops.toNumber(a.arg(1)).let { if (it.isNaN() || it < 0) 0L else it.toLong() }
            val task = WebGlobals.timersOf(f.realm)!!.task(ms, Ops.toBoolean(a.arg(2))) { Ops.call(cb, Undefined, EMPTY_ARGS) }
            if (task == null) Undefined else TaskHandle(task)
        }
        fn("cancel", 1) { f, _, a, _ ->
            (a.arg(0) as? TaskHandle)?.let { WebGlobals.timersOf(f.realm)!!.cancel(it.task) }
            Undefined
        }
        fn("setRef", 2) { f, _, a, _ ->
            (a.arg(0) as? TaskHandle)?.let { f.realm.agent.setKeepsAlive(it.task, Ops.toBoolean(a.arg(1))) }
            Undefined
        }

        // ---------------- process
        fn("processInfo", 0) { f, _, _, _ ->
            val o = JSObject(f.realm.objectPrototype)
            val opts = rt.options
            o.createDataProperty("version", NODE_VERSION)
            o.createDataProperty("neonjsVersion", engineVersion())
            o.createDataProperty("platform", opts.platform)
            o.createDataProperty("arch", arch())
            o.createDataProperty("cwd", opts.cwd)
            o.createDataProperty("argv", JSArray.of(f.realm.arrayPrototype, opts.argv.toTypedArray<Any?>()))
            val env = JSObject(f.realm.objectPrototype)
            for ((k, v) in opts.env) env.createDataProperty(PK.fromString(k), v)
            o.createDataProperty("env", env)
            o
        }
        fn("setProcess", 1) { _, _, a, _ ->
            rt.process = a.arg(0) as? JSObject
            Undefined
        }
        fn("exit", 1) { f, _, a, _ ->
            val code = Ops.toNumber(a.arg(0)).let { if (it.isNaN()) 0 else it.toInt() }
            rt.options.onExit?.accept(code)
            // the script stops here, as Node's process does; the host sees the evaluation interrupted. The request
            // also stops the loop should host code between here and there swallow the exception
            f.realm.agent.requestInterrupt()
            throw InterruptedExecutionException("process.exit($code)")
        }
        fn("hrtime", 0) { f, _, _, _ ->
            val n = if (f.realm.agent.clock != null) 0L else System.nanoTime() - startNanos
            JSArray.of(f.realm.arrayPrototype, arrayOf<Any?>((n / 1_000_000_000).toDouble(), (n % 1_000_000_000).toDouble()))
        }
        fn("hrtimeBigint", 0) { f, _, _, _ ->
            BigInteger.valueOf(if (f.realm.agent.clock != null) 0L else System.nanoTime() - startNanos)
        }
        fn("uptime", 0) { f, _, _, _ -> if (f.realm.agent.clock != null) 0.0 else (System.nanoTime() - startNanos) / 1e9 }
        fn("memoryUsage", 0) { f, _, _, _ ->
            val r = Runtime.getRuntime()
            val o = JSObject(f.realm.objectPrototype)
            val used = (r.totalMemory() - r.freeMemory()).toDouble()
            o.createDataProperty("rss", r.totalMemory().toDouble())
            o.createDataProperty("heapTotal", r.totalMemory().toDouble())
            o.createDataProperty("heapUsed", used)
            o.createDataProperty("external", 0.0)
            o.createDataProperty("arrayBuffers", 0.0)
            o
        }
        // process.stdout / stderr: whole lines go to the engine's console sink (log / error)
        fn("write", 2) { _, _, a, _ ->
            val stream = if (Ops.toNumber(a.arg(0)) == 2.0) 1 else 0
            write(stream, Ops.toString(a.arg(1)))
            Undefined
        }

        // ---------------- modules
        b.defineOwn("buffer", BufferBinding.create(realm), Attr.NONE)
        b.defineOwn("types", Types.create(realm), Attr.NONE)
        // the libraries' own modules (lib/internal_<name>.js), which scripts cannot require
        fn("internal", 1) { _, _, a, _ -> rt.internal(Ops.toString(a.arg(0))) }
        return b
    }

    private fun write(stream: Int, text: String) {
        val console = ((realm.hostData as? NeonContext)?.engine?.console) ?: return
        val sb = pending[stream]
        sb.append(text)
        if (sb.length > 1 shl 20) {
            // a line that never ends is written out rather than kept
            console.write(if (stream == 1) NeonConsole.Level.ERROR else NeonConsole.Level.LOG, sb.toString())
            sb.setLength(0)
            return
        }
        while (true) {
            val nl = sb.indexOf("\n")
            if (nl < 0) break
            console.write(if (stream == 1) NeonConsole.Level.ERROR else NeonConsole.Level.LOG, sb.substring(0, nl))
            sb.delete(0, nl + 1)
        }
    }

    companion object {
        /** The Node.js release line whose APIs the module follows (`process.version`). */
        const val NODE_VERSION = "v22.12.0"

        fun engineVersion(): String = NeonEngine::class.java.`package`?.implementationVersion ?: "dev"

        private fun arch(): String = when (val a = System.getProperty("os.arch", "").lowercase()) {
            "amd64", "x86_64" -> "x64"
            "aarch64", "arm64" -> "arm64"
            "x86", "i386", "i686" -> "ia32"
            else -> if (a.startsWith("arm")) "arm" else a
        }
    }
}
