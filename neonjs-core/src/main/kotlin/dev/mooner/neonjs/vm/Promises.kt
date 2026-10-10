package dev.mooner.neonjs.vm

import dev.mooner.neonjs.runtime.*

/** Promise instance with `[[PromiseState]]` etc. */
class JSPromise(proto: JSObject?) : JSObject(proto) {
    @JvmField var state = PENDING
    @JvmField var result: Any? = Undefined
    @JvmField var fulfillReactions: ArrayList<PromiseReaction>? = ArrayList(1)
    @JvmField var rejectReactions: ArrayList<PromiseReaction>? = ArrayList(1)
    @JvmField var isHandled = false

    override val className: String get() = "Promise"

    companion object {
        const val PENDING = 0
        const val FULFILLED = 1
        const val REJECTED = 2
    }
}

class PromiseCapability(@JvmField val promise: JSObject, @JvmField val resolve: Any?, @JvmField val reject: Any?)

/** Reaction; [handler] is a JS callable, Undefined, or a Kotlin callback ([hostHandler]). */
class PromiseReaction(
    @JvmField val capability: PromiseCapability?,
    @JvmField val isFulfill: Boolean,
    @JvmField val handler: Any?,
    @JvmField val hostHandler: ((Any?) -> Any?)? = null,
)

object Promises {

    /** Creates a new pending promise with the intrinsic prototype. */
    @JvmStatic
    fun newPromise(realm: Realm): JSPromise = JSPromise(realm.promisePrototype)

    @JvmStatic
    fun rejected(realm: Realm, reason: Any?): JSPromise {
        val p = newPromise(realm)
        rejectPromise(realm, p, reason)
        return p
    }

    @JvmStatic
    fun resolved(realm: Realm, value: Any?): JSPromise {
        val p = newPromise(realm)
        resolvePromise(realm, p, value)
        return p
    }

    /** CreateResolvingFunctions */
    @JvmStatic
    fun createResolvingFunctions(realm: Realm, promise: JSPromise): Pair<NativeFunction, NativeFunction> {
        val alreadyResolved = BooleanArray(1)
        val resolve = NativeFunction(realm, "", 1, { f, _, args, _ ->
            if (!alreadyResolved[0]) {
                alreadyResolved[0] = true
                resolvePromise(f.realm, promise, args.arg(0))
            }
            Undefined
        })
        val reject = NativeFunction(realm, "", 1, { f, _, args, _ ->
            if (!alreadyResolved[0]) {
                alreadyResolved[0] = true
                rejectPromise(f.realm, promise, args.arg(0))
            }
            Undefined
        })
        return resolve to reject
    }

    /** The `[[Resolve]]` steps of promise resolve functions. */
    @JvmStatic
    fun resolvePromise(realm: Realm, promise: JSPromise, resolution: Any?) {
        if (resolution === promise) {
            rejectPromise(realm, promise, realm.newError(ErrorKind.TYPE, "Chaining cycle detected for promise"))
            return
        }
        if (resolution !is JSObject) {
            fulfillPromise(realm, promise, resolution)
            return
        }
        val then: Any?
        try {
            then = resolution.get("then", resolution)
        } catch (e: JSException) {
            rejectPromise(realm, promise, e.value)
            return
        }
        if (!Ops.isCallable(then)) {
            fulfillPromise(realm, promise, resolution)
            return
        }
        // NewPromiseResolveThenableJob
        realm.agent.enqueueJob {
            val (res, rej) = createResolvingFunctions(realm, promise)
            try {
                (then as JSObject).call(resolution, arrayOf(res, rej))
            } catch (e: JSException) {
                rej.call(Undefined, arrayOf(e.value))
            } catch (_: StackOverflowError) {
                rej.call(Undefined, arrayOf(realm.newError(ErrorKind.RANGE, "Maximum call stack size exceeded")))
            }
        }
    }

    @JvmStatic
    fun fulfillPromise(realm: Realm, p: JSPromise, value: Any?) {
        if (p.state != JSPromise.PENDING) return
        val reactions = p.fulfillReactions
        p.result = value
        p.fulfillReactions = null
        p.rejectReactions = null
        p.state = JSPromise.FULFILLED
        if (reactions != null) for (r in reactions) enqueueReaction(realm, r, value)
    }

    @JvmStatic
    fun rejectPromise(realm: Realm, p: JSPromise, reason: Any?) {
        if (p.state != JSPromise.PENDING) return
        val reactions = p.rejectReactions
        p.result = reason
        p.fulfillReactions = null
        p.rejectReactions = null
        p.state = JSPromise.REJECTED
        if (!p.isHandled) realm.agent.trackRejection(p, false)
        if (reactions != null) for (r in reactions) enqueueReaction(realm, r, reason)
    }

    private fun enqueueReaction(realm: Realm, r: PromiseReaction, argument: Any?) {
        realm.agent.enqueueJob { runReaction(realm, r, argument) }
    }

    /** NewPromiseReactionJob body. */
    private fun runReaction(realm: Realm, r: PromiseReaction, argument: Any?) {
        val cap = r.capability
        var ok = true
        var value: Any?
        val hh = r.hostHandler
        try {
            value = if (hh != null) hh(argument)
            else if (r.handler === Undefined || r.handler == null) {
                if (!r.isFulfill) ok = false
                argument
            } else (r.handler as JSObject).call(Undefined, arrayOf(argument))
        } catch (e: JSException) {
            ok = false
            value = e.value
        } catch (_: StackOverflowError) {
            ok = false
            value = realm.newError(ErrorKind.RANGE, "Maximum call stack size exceeded")
        }
        // without a capability there is nothing to settle (a failing host handler's error is dropped)
        if (cap == null) return
        if (ok) Ops.call(cap.resolve, Undefined, arrayOf(value))
        else Ops.call(cap.reject, Undefined, arrayOf(value))
    }

    /** NewPromiseCapability(C) */
    @JvmStatic
    fun newPromiseCapability(realm: Realm, c: Any?): PromiseCapability {
        if (c === realm.promiseConstructor) {
            val p = newPromise(realm)
            val (res, rej) = createResolvingFunctions(realm, p)
            return PromiseCapability(p, res, rej)
        }
        if (!Ops.isConstructor(c)) throw JSException.typeError("Promise resolve or reject function is not callable")
        val slots = arrayOfNulls<Any?>(2)
        slots[0] = Undefined
        slots[1] = Undefined
        val executor = NativeFunction(realm, "", 2, { _, _, args, _ ->
            if (slots[0] !== Undefined) throw JSException.typeError("Promise executor has already been invoked with non-undefined arguments")
            if (slots[1] !== Undefined) throw JSException.typeError("Promise executor has already been invoked with non-undefined arguments")
            slots[0] = args.arg(0)
            slots[1] = args.arg(1)
            Undefined
        })
        val promise = (c as JSObject).construct(arrayOf(executor), c)
        if (!Ops.isCallable(slots[0])) throw JSException.typeError("Promise resolve function is not callable")
        if (!Ops.isCallable(slots[1])) throw JSException.typeError("Promise reject function is not callable")
        return PromiseCapability(promise as JSObject, slots[0], slots[1])
    }

    /** PromiseResolve(C, x) */
    @JvmStatic
    fun promiseResolve(realm: Realm, c: JSObject, x: Any?): JSObject {
        if (x is JSPromise) {
            val ctor = x.get("constructor", x)
            if (ctor === c) return x
        }
        val cap = newPromiseCapability(realm, c)
        Ops.call(cap.resolve, Undefined, arrayOf(x))
        return cap.promise
    }

    /** PerformPromiseThen with JS handlers. */
    @JvmStatic
    fun performThen(realm: Realm, p: JSPromise, onFulfilled: Any?, onRejected: Any?, cap: PromiseCapability?): Any? {
        val f = if (Ops.isCallable(onFulfilled)) onFulfilled else Undefined
        val r = if (Ops.isCallable(onRejected)) onRejected else Undefined
        addReactions(realm, p, PromiseReaction(cap, true, f), PromiseReaction(cap, false, r))
        return cap?.promise ?: Undefined
    }

    /** PerformPromiseThen with host callbacks (await, internal plumbing). */
    @JvmStatic
    fun thenHost(realm: Realm, p: JSPromise, onFulfilled: (Any?) -> Any?, onRejected: (Any?) -> Any?) {
        addReactions(realm, p, PromiseReaction(null, true, null, onFulfilled), PromiseReaction(null, false, null, onRejected))
    }

    private fun addReactions(realm: Realm, p: JSPromise, fr: PromiseReaction, rr: PromiseReaction) {
        when (p.state) {
            JSPromise.PENDING -> {
                p.fulfillReactions!!.add(fr)
                p.rejectReactions!!.add(rr)
            }
            JSPromise.FULFILLED -> enqueueReaction(realm, fr, p.result)
            else -> {
                if (!p.isHandled) realm.agent.trackRejection(p, true)
                enqueueReaction(realm, rr, p.result)
            }
        }
        p.isHandled = true
    }
}
