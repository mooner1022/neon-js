package dev.mooner.neonpython

/**
 * `atexit` (Modules/atexitmodule.c, CPython 3.14): each context's callbacks, the last registered first. A context has
 * no exit of its own to run them at (the host runs code in it and lets it go), so they run when `_run_exitfuncs()` is
 * called; a callback's exception is printed as CPython's unraisable hook prints it (to stderr) and the next runs.
 */
object PyAtexit {
    private val NO_ARGS = arrayOfNulls<Any?>(0)

    private class Callback(@JvmField val fn: Any?, @JvmField val args: Array<Any?>, @JvmField val kw: PyDict?)

    @JvmStatic
    fun fill(ctx: PyContext, d: PyDict) {
        val callbacks = ArrayList<Callback>()
        fun noKw(q: String, kw: PyDict?) {
            if (kw != null && kw.size > 0) throw PyRt.typeError("$q() takes no keyword arguments")
        }
        fun noArgs(n: String, impl: () -> Any?) {
            val q = "atexit.$n"
            fun check(a: Array<Any?>): Any? {
                if (a.isNotEmpty()) throw PyRt.typeError("$q() takes no arguments (${a.size} given)")
                return impl()
            }
            d[PyStr(n)] = PyBuiltin(n, { _, a -> check(a) }, { _, a, kw -> noKw(q, kw); check(a) })
        }
        fun register(a: Array<Any?>, kw: PyDict?): Any? {
            if (a.isEmpty()) throw PyRt.typeError("register() takes at least 1 argument (0 given)")
            val fn = a[0]
            if (!PyCalls.isCallable(fn)) throw PyRt.typeError("the first argument must be callable")
            val kwCopy = kw?.takeIf { it.size > 0 }?.let { PyDict().also { c -> for ((k, v) in it.map) c.map[k] = v } }
            callbacks.add(0, Callback(fn, a.copyOfRange(1, a.size), kwCopy))
            return fn
        }
        d[PyStr("register")] = PyBuiltin("register", { _, a -> register(a, null) }, { _, a, kw -> register(a, kw) })
        fun unregister(a: Array<Any?>): Any? {
            if (a.size != 1) throw PyRt.typeError("atexit.unregister() takes exactly one argument (${a.size} given)")
            // (each callback of the function, from the last: equal by ==)
            var i = callbacks.size - 1
            while (i >= 0) {
                val cb = callbacks[i]
                if (PyRt.eq(a[0], cb.fn)) {
                    val j = minOf(callbacks.size - 1, i).let { start -> (start downTo 0).firstOrNull { callbacks[it] === cb } }
                    if (j != null) {
                        callbacks.removeAt(j)
                        i = j
                    }
                }
                if (i >= callbacks.size) i = callbacks.size
                i--
            }
            return PyNone
        }
        d[PyStr("unregister")] = PyBuiltin("unregister", { _, a -> unregister(a) }, { _, a, kw -> noKw("atexit.unregister", kw); unregister(a) })
        noArgs("_run_exitfuncs") {
            val copy = ArrayList(callbacks)
            for (cb in copy) {
                try {
                    if (cb.kw == null) PyCalls.callWith(ctx, cb.fn, cb.args, NO_ARGS, NO_ARGS)
                    else PyCalls.callWith(ctx, cb.fn, cb.args, cb.kw.map.keys.map { it.v }.toTypedArray(), cb.kw.map.values.toTypedArray())
                } catch (e: PyException) {
                    ctx.err.append("Exception ignored in atexit callback ").append(PyRt.repr(cb.fn)).append(":\n").append(ctx.formatException(e))
                }
            }
            callbacks.clear()
            PyNone
        }
        noArgs("_clear") {
            callbacks.clear()
            PyNone
        }
        noArgs("_ncallbacks") { callbacks.size.toLong() }
    }
}
