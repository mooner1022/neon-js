package dev.mooner.neonjs.builtins

import dev.mooner.neonjs.runtime.*
import dev.mooner.neonjs.vm.*

// ====================================================================== Explicit Resource Management

/** The hint of a DisposableResource Record (sync-dispose / async-dispose). */
enum class DisposeHint { SYNC, ASYNC }

/** DisposableResource Record: [method] is undefined only for a nullish value added with [DisposeHint.ASYNC]. */
class DisposableResource(@JvmField val value: Any?, @JvmField val hint: DisposeHint, @JvmField val method: Any?)

/**
 * DisposeCapability Record: the resource stack of a DisposableStack / AsyncDisposableStack or of a block containing
 * `using` / `await using` declarations, plus the DisposeResources algorithm over it.
 *
 * Completions are represented as `Throwable?`: `null` is a normal completion, anything else a throw completion (the
 * thrown JS value is obtained like a catch clause would). Disposal errors are aggregated into SuppressedErrors.
 */
class DisposeCapability {
    private var stack = ArrayList<DisposableResource>()

    val isEmpty: Boolean get() = stack.isEmpty()

    /**
     * AddDisposableResource(cap, value, hint) for `using` / `await using` / `use()`: throws a TypeError if [value] is
     * neither nullish nor an object with a callable dispose method. A nullish value is ignored for [DisposeHint.SYNC]
     * and recorded (so that disposal awaits once) for [DisposeHint.ASYNC].
     */
    fun add(realm: Realm, value: Any?, hint: DisposeHint) {
        if (value === Undefined || value === Null) {
            if (hint == DisposeHint.ASYNC) stack.add(DisposableResource(Undefined, hint, Undefined))
            return
        }
        if (value !is JSObject) throw JSException.typeError("${Ops.describe(value)} is not an object and cannot be disposed")
        val method = getDisposeMethod(realm, value, hint)
        if (method === Undefined) {
            val name = if (hint == DisposeHint.ASYNC) "Symbol.asyncDispose or Symbol.dispose" else "Symbol.dispose"
            throw JSException.typeError("${Ops.describe(value)} does not have a $name method")
        }
        stack.add(DisposableResource(value, hint, method))
    }

    /** AddDisposableResource(cap, undefined, hint, method) for adopt() / defer(): [method] is called with this = undefined. */
    fun addMethod(hint: DisposeHint, method: Any?) {
        if (!Ops.isCallable(method)) throw JSException.typeError("${Ops.describe(method)} is not a function")
        stack.add(DisposableResource(Undefined, hint, method))
    }

    /**
     * DisposeResources(cap, completion) for a capability holding only [DisposeHint.SYNC] resources. Returns normally
     * for a normal result, otherwise throws the resulting completion ([completion] itself if no disposer threw).
     */
    fun disposeAll(realm: Realm, completion: Throwable? = null) {
        val resources = takeResources()
        var error = completion
        for (i in resources.indices.reversed()) {
            val r = resources[i]
            check(r.hint == DisposeHint.SYNC) { "async-dispose resource in synchronous disposal" }
            try {
                Ops.call(r.method, r.value, EMPTY_ARGS)
            } catch (t: Throwable) {
                error = suppress(realm, error, t)
            }
        }
        if (error != null) throw error
    }

    /**
     * Starts DisposeResources(cap, completion) with Await steps. The caller drives the returned [AsyncDisposal]: see
     * there. Use this from code that can suspend natively (async functions); otherwise use [disposeAllAsync].
     */
    fun beginAsyncDisposal(realm: Realm, completion: Throwable? = null): AsyncDisposal = AsyncDisposal(realm, takeResources(), completion)

    /**
     * DisposeResources(cap, completion) with Await steps driven by promise jobs. [onDone] receives the resulting
     * completion (null for normal); it runs synchronously when no Await is needed.
     */
    fun disposeAllAsync(realm: Realm, completion: Throwable?, onDone: (Throwable?) -> Unit) {
        val disposal = beginAsyncDisposal(realm, completion)
        fun run() {
            if (!disposal.step()) {
                onDone(disposal.result)
                return
            }
            awaitValue(realm, disposal.awaitValue, { run() }, { reason ->
                disposal.rejected(reason)
                run()
            })
        }
        run()
    }

    /** DisposeResources clears the stack; the resources are taken up front since nothing can be added meanwhile. */
    private fun takeResources(): List<DisposableResource> {
        val resources = stack
        stack = ArrayList()
        return resources
    }

    companion object {
        /** GetDisposeMethod(V, hint) */
        @JvmStatic
        fun getDisposeMethod(realm: Realm, v: JSObject, hint: DisposeHint): Any? {
            if (hint == DisposeHint.SYNC) return Ops.getMethod(v, JSSymbol.dispose)
            val method = Ops.getMethod(v, JSSymbol.asyncDispose)
            if (method !== Undefined) return method
            val syncMethod = Ops.getMethod(v, JSSymbol.dispose)
            if (syncMethod === Undefined) return Undefined
            // Calls the sync method and returns a promise for undefined: its result is never awaited and its
            // exceptions reject instead of being thrown synchronously.
            return NativeFunction(realm, "", 0, { f, thisArg, _, _ ->
                val p = Promises.newPromise(f.realm)
                try {
                    Ops.call(syncMethod, thisArg, EMPTY_ARGS)
                    Promises.resolvePromise(f.realm, p, Undefined)
                } catch (t: Throwable) {
                    Promises.rejectPromise(f.realm, p, thrownValue(f.realm, t))
                }
                p
            })
        }

        /** Combines a disposal error [t] with the pending [completion] (wrapping both in a SuppressedError if needed). */
        @JvmStatic
        fun suppress(realm: Realm, completion: Throwable?, t: Throwable): Throwable {
            if (t is TerminationException) throw t
            if (completion == null) return t
            return JSException(newSuppressedError(realm, thrownValue(realm, t), thrownValue(realm, completion)))
        }

        /** A newly created SuppressedError with non-enumerable "error" and "suppressed" properties. */
        @JvmStatic
        fun newSuppressedError(realm: Realm, error: Any?, suppressed: Any?): JSErrorObject {
            val e = JSErrorObject(realm.errorPrototypes[ErrorKind.SUPPRESSED] ?: realm.errorPrototype)
            e.defineOwn("error", error, Attr.WC)
            e.defineOwn("suppressed", suppressed, Attr.WC)
            if (realm.agent.topFrame != null) e.stackTrace = realm.agent.captureStack()
            return e
        }

        /** The JS value of a throw completion represented by [t]. */
        @JvmStatic
        fun thrownValue(realm: Realm, t: Throwable): Any? = if (t is JSException) t.value else Rt.catchValue(t, realm, null, 0)
    }
}

/**
 * The DisposeResources loop of an async disposal, suspended at each Await. Protocol:
 * call [step]; while it returns true, Await [awaitValue] and on rejection report the reason via [rejected] (on
 * fulfillment nothing is reported), then call [step] again. Once [step] returns false, [result] is the resulting
 * completion (null for normal).
 */
class AsyncDisposal internal constructor(private val realm: Realm, private val resources: List<DisposableResource>, completion: Throwable?) {
    private var index = resources.size - 1
    private var needsAwait = false
    private var hasAwaited = false

    /** The pending completion: null while normal. */
    var result: Throwable? = completion
        private set

    /** The value to Await after [step] returned true. */
    var awaitValue: Any? = Undefined
        private set

    /** Runs the algorithm until the next Await (returns true) or to the end (returns false). */
    fun step(): Boolean {
        while (index >= 0) {
            val r = resources[index]
            if (r.hint == DisposeHint.SYNC && needsAwait && !hasAwaited) {
                // Await(undefined) before disposing this resource
                needsAwait = false
                awaitValue = Undefined
                return true
            }
            index--
            if (r.method === Undefined) {
                needsAwait = true
                continue
            }
            val value = try {
                Ops.call(r.method, r.value, EMPTY_ARGS)
            } catch (t: Throwable) {
                result = DisposeCapability.suppress(realm, result, t)
                continue
            }
            if (r.hint == DisposeHint.ASYNC) {
                hasAwaited = true
                awaitValue = value
                return true
            }
        }
        if (needsAwait && !hasAwaited) {
            needsAwait = false
            awaitValue = Undefined
            return true
        }
        return false
    }

    /** The last Await threw [reason]. */
    fun rejected(reason: Any?) {
        result = DisposeCapability.suppress(realm, result, JSException(reason))
    }
}

/**
 * Await(value) for builtins written as promise-driven state machines: PromiseResolve(%Promise%, value) and
 * PerformPromiseThen with host continuations. A throw from PromiseResolve calls [onRejected] synchronously.
 * Exceptions escaping the continuations are not reported anywhere, so they must settle their own promise.
 */
internal fun awaitValue(realm: Realm, value: Any?, onFulfilled: (Any?) -> Unit, onRejected: (Any?) -> Unit) {
    val promise = try {
        Promises.promiseResolve(realm, realm.promiseConstructor, value) as JSPromise
    } catch (t: Throwable) {
        if (t is TerminationException) throw t
        onRejected(DisposeCapability.thrownValue(realm, t))
        return
    }
    Promises.thenHost(realm, promise, { v -> onFulfilled(v); Undefined }, { e -> onRejected(e); Undefined })
}

/** DisposableStack / AsyncDisposableStack instance (`[[DisposableState]]` or `[[AsyncDisposableState]]`). */
class JSDisposableStack(proto: JSObject?, @JvmField val isAsync: Boolean) : JSObject(proto) {
    @JvmField var disposed = false
    @JvmField var capability = DisposeCapability()
}

internal object DisposableBuiltins {
    fun install(realm: Realm) {
        installStack(realm, false)
        installStack(realm, true)
    }

    private fun installStack(realm: Realm, isAsync: Boolean) {
        val name = if (isAsync) "AsyncDisposableStack" else "DisposableStack"
        val hint = if (isAsync) DisposeHint.ASYNC else DisposeHint.SYNC
        val protoName = "%$name.prototype%"
        val proto = JSObject(realm.objectPrototype)
        realm.intrinsics[protoName] = proto
        val ctor = makeCtor(realm, name, 0, proto) { _, _, _, nt ->
            if (nt == null) typeErr("Constructor $name requires 'new'")
            JSDisposableStack(Ops.getPrototypeFromConstructor(nt) { it.intrinsic(protoName) }, isAsync)
        }
        realm.intrinsics["%$name%"] = ctor
        realm.global(name, ctor)

        fun thisStack(t: Any?, m: String): JSDisposableStack {
            if (t is JSDisposableStack && t.isAsync == isAsync) return t
            typeErr("Method $name.prototype.$m called on incompatible receiver ${Ops.describe(t)}")
        }
        fun pendingStack(t: Any?, m: String): JSDisposableStack {
            val s = thisStack(t, m)
            if (s.disposed) throw JSException.referenceError("Cannot call $name.prototype.$m on a disposed $name")
            return s
        }

        val onDisposeName = if (isAsync) "onDisposeAsync" else "onDispose"
        proto.method(realm, "adopt", 2) { f, t, args, _ ->
            val s = pendingStack(t, "adopt")
            val value = args.arg(0)
            val onDispose = args.arg(1)
            if (!Ops.isCallable(onDispose)) typeErr("$onDisposeName ${Ops.describe(onDispose)} is not a function")
            val closure = NativeFunction(f.realm, "", 0, { _, _, _, _ -> Ops.call(onDispose, Undefined, arrayOf(value)) })
            s.capability.addMethod(hint, closure)
            value
        }
        proto.method(realm, "defer", 1) { _, t, args, _ ->
            val s = pendingStack(t, "defer")
            val onDispose = args.arg(0)
            if (!Ops.isCallable(onDispose)) typeErr("$onDisposeName ${Ops.describe(onDispose)} is not a function")
            s.capability.addMethod(hint, onDispose)
            Undefined
        }
        val dispose = if (isAsync) {
            proto.method(realm, "disposeAsync", 0) { f, t, _, _ ->
                val p = Promises.newPromise(f.realm)
                if (t !is JSDisposableStack || !t.isAsync) {
                    Promises.rejectPromise(f.realm, p, f.realm.newError(ErrorKind.TYPE, "Method $name.prototype.disposeAsync called on incompatible receiver ${Ops.describe(t)}"))
                } else if (t.disposed) {
                    Promises.resolvePromise(f.realm, p, Undefined)
                } else {
                    t.disposed = true
                    t.capability.disposeAllAsync(f.realm, null) { error ->
                        if (error == null) Promises.resolvePromise(f.realm, p, Undefined)
                        else Promises.rejectPromise(f.realm, p, DisposeCapability.thrownValue(f.realm, error))
                    }
                }
                p
            }
        } else {
            proto.method(realm, "dispose", 0) { f, t, _, _ ->
                val s = thisStack(t, "dispose")
                if (!s.disposed) {
                    s.disposed = true
                    s.capability.disposeAll(f.realm)
                }
                Undefined
            }
        }
        proto.getter(realm, "disposed") { _, t, _, _ -> thisStack(t, "disposed").disposed }
        proto.method(realm, "move", 0) { f, t, _, _ ->
            val s = pendingStack(t, "move")
            val moved = JSDisposableStack(f.realm.intrinsic(protoName), isAsync)
            moved.capability = s.capability
            s.capability = DisposeCapability()
            s.disposed = true
            moved
        }
        proto.method(realm, "use", 1) { f, t, args, _ ->
            val s = pendingStack(t, "use")
            val value = args.arg(0)
            s.capability.add(f.realm, value, hint)
            value
        }
        proto.defineOwn(if (isAsync) JSSymbol.asyncDispose else JSSymbol.dispose, dispose, Attr.WC)
        proto.value(JSSymbol.toStringTag, name, Attr.CONFIGURABLE)
    }
}
