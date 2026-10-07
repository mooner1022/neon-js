package io.neonjs.vm

import io.neonjs.builtins.AsyncDisposal
import io.neonjs.builtins.DisposeCapability
import io.neonjs.builtins.DisposeHint
import io.neonjs.runtime.*

/**
 * Runtime support for `using` / `await using` (shared by the interpreter and compiled code).
 *
 * A scope containing such declarations keeps a [DisposeCapability] in a register and runs its body in a finally-like
 * region; the finalizer receives the completion as the (kind, value) register pair of that region: kind 1 is a throw
 * completion carrying the thrown value, any other kind (normal, return, break, continue) disposes with a normal
 * completion.
 */
object Disposal {
    private const val KIND_THROW = 1.0

    @JvmStatic fun newCapability(): Any = DisposeCapability()

    @JvmStatic
    fun add(realm: Realm, cap: Any?, value: Any?, hint: Int) {
        (cap as DisposeCapability).add(realm, value, if (hint == 1) DisposeHint.ASYNC else DisposeHint.SYNC)
    }

    private fun completion(kind: Any?, value: Any?): Throwable? = if (kind == KIND_THROW) JSException(value) else null

    /** DisposeResources for a scope without `await using`; throws the resulting completion if it is abrupt. */
    @JvmStatic
    fun disposeSync(realm: Realm, cap: Any?, kind: Any?, value: Any?) {
        (cap as DisposeCapability).disposeAll(realm, completion(kind, value))
    }

    @JvmStatic
    fun begin(realm: Realm, cap: Any?, kind: Any?, value: Any?): Any =
        (cap as DisposeCapability).beginAsyncDisposal(realm, completion(kind, value))

    /** Advances to the next Await; returns false when disposal is complete. */
    @JvmStatic fun step(d: Any?): Boolean = (d as AsyncDisposal).step()

    @JvmStatic fun awaitValue(d: Any?): Any? = (d as AsyncDisposal).awaitValue

    /** Reports the outcome of an Await: [mode] is non-zero when it was rejected with [result]. */
    @JvmStatic
    fun awaited(result: Any?, d: Any?, mode: Any?) {
        if (mode != 0.0) (d as AsyncDisposal).rejected(result)
    }

    @JvmStatic
    fun end(d: Any?) {
        (d as AsyncDisposal).result?.let { throw it }
    }
}
