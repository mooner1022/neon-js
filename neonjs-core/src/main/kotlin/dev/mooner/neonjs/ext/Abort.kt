package dev.mooner.neonjs.ext

import dev.mooner.neonjs.builtins.typeErr
import dev.mooner.neonjs.runtime.*
import java.lang.ref.WeakReference

/** `AbortController` and `AbortSignal` of the DOM Standard. */
internal object Abort {
    class JSAbortSignal(proto: JSObject?) : Events.JSEventTarget(proto) {
        override val className: String get() = "AbortSignal"
        @JvmField var aborted = false
        @JvmField var reason: Any? = Undefined
        /** Abort algorithms: what other APIs do when the signal aborts (remove a listener, reject a fetch...). */
        private val algorithms = ArrayList<Runnable>(1)
        /** Of a signal made by `AbortSignal.any()`: the signals it follows. */
        @JvmField var dependent = false
        @JvmField val sources = ArrayList<WeakReference<JSAbortSignal>>(0)
        /** The signals made by `AbortSignal.any()` that follow this one (weakly: an unused one may go away). */
        @JvmField val dependents = ArrayList<WeakReference<JSAbortSignal>>(0)
        /**
         * The dependents that must not go away: the DOM Standard keeps a dependent signal while it has sources and an
         * abort listener or abort algorithm (one held by its `onabort` alone would otherwise never fire).
         */
        private var kept: LinkedHashSet<JSAbortSignal>? = null

        fun addAlgorithm(r: Runnable) {
            if (aborted) return
            algorithms.add(r)
            keepForSources()
        }

        override fun listenerAdded(l: Events.Listener) {
            if (l.type == "abort") keepForSources()
        }

        private fun keepForSources() {
            if (!dependent || aborted) return
            for (r in sources) {
                val s = r.get() ?: continue
                if (!s.aborted) (s.kept ?: LinkedHashSet<JSAbortSignal>().also { s.kept = it }).add(this)
            }
        }

        /** "signal abort" */
        fun abort(realm: Realm, reason: Any?) {
            if (aborted) return
            aborted = true
            this.reason = if (reason === Undefined) newDOMError(realm, "signal is aborted without reason", "AbortError") else reason
            val toAbort = ArrayList<JSAbortSignal>()
            for (r in dependents) {
                val d = r.get() ?: continue
                if (d.aborted) continue
                d.aborted = true
                d.reason = this.reason
                toAbort.add(d)
            }
            kept = null
            runAbortSteps(realm)
            for (d in toAbort) d.runAbortSteps(realm)
        }

        private fun runAbortSteps(realm: Realm) {
            val run = algorithms.toTypedArray()
            algorithms.clear()
            for (a in run) a.run()
            Events.dispatch(realm, this, Events.engineEvent(realm, "abort"))
        }
    }

    class JSAbortController(proto: JSObject?, @JvmField val signal: JSAbortSignal) : JSObject(proto) {
        override val className: String get() = "AbortController"
    }

    private fun newDOMError(realm: Realm, message: String, name: String): JSObject = WebGlobals.newDOMException(realm, message, name)

    /** An `AbortSignal` argument, or a TypeError. */
    fun signalOf(v: Any?): JSAbortSignal = v as? JSAbortSignal ?: typeErr("${Ops.describe(v)} is not an AbortSignal")

    fun newSignal(realm: Realm): JSAbortSignal = JSAbortSignal(realm.intrinsic("%AbortSignal.prototype%"))

    fun install(realm: Realm) {
        val c = WebInterface.define(realm, "AbortController", 0) { _, proto -> JSAbortController(proto, newSignal(realm)) }
        c.attribute("signal", { _, t, _, _ -> Idl.self<JSAbortController>(t, "AbortController", "signal").signal })
        c.operation("abort", 0) { f, t, a, _ ->
            Idl.self<JSAbortController>(t, "AbortController", "abort").signal.abort(f.realm, a.arg(0))
            Undefined
        }

        val s = WebInterface.define(realm, "AbortSignal", 0, parent = "EventTarget")
        s.staticOperation("abort", 0) { f, _, a, _ ->
            val signal = newSignal(f.realm)
            signal.abort(f.realm, a.arg(0))
            signal
        }
        s.staticOperation("timeout", 1) { f, _, a, _ ->
            Idl.required(a, 1, "AbortSignal.timeout")
            val ms = Idl.integer(a[0], 64, signed = false, what = "AbortSignal.timeout milliseconds", enforceRange = true)
            val signal = newSignal(f.realm)
            val r = f.realm
            // like Node's, the timer does not keep the event loop waiting
            WebGlobals.timersOf(r).task(ms.toLong(), keepsAlive = false) {
                signal.abort(r, newDOMError(r, "signal timed out", "TimeoutError"))
            }
            signal
        }
        s.staticOperation("any", 1) { f, _, a, _ ->
            Idl.required(a, 1, "AbortSignal.any")
            val signals = Idl.sequence(f.realm, a[0], "AbortSignal.any signals") { signalOf(it) }
            val result = newSignal(f.realm)
            val aborted = signals.firstOrNull { it.aborted }
            if (aborted != null) {
                result.aborted = true
                result.reason = aborted.reason
                return@staticOperation result
            }
            result.dependent = true
            fun follow(source: JSAbortSignal) {
                if (result.sources.any { it.get() === source }) return
                result.sources.add(WeakReference(source))
                source.dependents.removeIf { it.get() == null }
                source.dependents.add(WeakReference(result))
            }
            for (sig in signals) {
                if (!sig.dependent) follow(sig) else for (src in sig.sources) src.get()?.let { follow(it) }
            }
            result
        }
        fun sig(t: Any?, m: String) = Idl.self<JSAbortSignal>(t, "AbortSignal", m)
        s.attribute("aborted", { _, t, _, _ -> sig(t, "aborted").aborted })
        s.attribute("reason", { _, t, _, _ -> sig(t, "reason").reason })
        s.operation("throwIfAborted", 0) { _, t, _, _ ->
            val x = sig(t, "throwIfAborted")
            if (x.aborted) throw JSException(x.reason)
            Undefined
        }
        Events.eventHandler(s, "onabort", "abort")
    }
}
