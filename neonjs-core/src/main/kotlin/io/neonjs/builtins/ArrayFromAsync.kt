package io.neonjs.builtins

import io.neonjs.runtime.*
import io.neonjs.vm.*

/**
 * Array.fromAsync(asyncItems, mapfn, thisArg): the fromAsyncClosure run with AsyncFunctionStart semantics, as a state
 * machine whose Await steps resume through promise reactions. Every continuation settles [promise] itself, since
 * exceptions escaping host reactions are dropped.
 */
internal class ArrayFromAsync private constructor(
    private val realm: Realm,
    private val c: Any?,
    private val mapfn: Any?,
    private val thisArg: Any?,
) {
    private val promise = Promises.newPromise(realm)
    private var mapping = false
    private lateinit var target: JSObject
    private var k = 0L

    /** Async iterator (possibly a wrapped sync iterator) of the iterable path. */
    private var iteratorRecord: IteratorRecord? = null

    /** Source object and length of the array-like path. */
    private var arrayLike: JSObject? = null
    private var len = 0L

    companion object {
        fun start(realm: Realm, c: Any?, asyncItems: Any?, mapfn: Any?, thisArg: Any?): JSPromise {
            val job = ArrayFromAsync(realm, c, mapfn, thisArg)
            job.guard { job.begin(asyncItems) }
            return job.promise
        }
    }

    /** Runs a step of the closure; a throw completion rejects the result promise. */
    private inline fun guard(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            if (t is TerminationException) throw t
            Promises.rejectPromise(realm, promise, DisposeCapability.thrownValue(realm, t))
        }
    }

    /** Await(value), continuing with [cont]; a rejection rejects the result promise. */
    private fun await(value: Any?, cont: (Any?) -> Unit) {
        awaitValue(realm, value, { v -> guard { cont(v) } }, { e -> Promises.rejectPromise(realm, promise, e) })
    }

    private fun begin(asyncItems: Any?) {
        if (mapfn !== Undefined) {
            if (!Ops.isCallable(mapfn)) throw realm.typeError("${Ops.describe(mapfn)} is not a function")
            mapping = true
        }
        val usingAsyncIterator = Ops.getMethod(asyncItems, JSSymbol.asyncIterator)
        val usingSyncIterator = if (usingAsyncIterator === Undefined) Ops.getMethod(asyncItems, JSSymbol.iterator) else Undefined
        if (usingAsyncIterator !== Undefined || usingSyncIterator !== Undefined) {
            iteratorRecord = if (usingAsyncIterator !== Undefined) Iteration.fromMethod(asyncItems, usingAsyncIterator)
            else AsyncFromSyncIterator.create(realm, Iteration.fromMethod(asyncItems, usingSyncIterator))
            target = if (Ops.isConstructor(c)) Ops.construct(c, EMPTY_ARGS) as JSObject else ArrayBuiltins.arrayCreate(realm, 0)
            iteratorStep()
        } else {
            // neither an AsyncIterable nor an Iterable: an array-like object
            val o = Ops.toObject(realm, asyncItems)
            arrayLike = o
            len = Ops.lengthOfArrayLike(o)
            target = if (Ops.isConstructor(c)) Ops.construct(c, arrayOf(len.toDouble())) as JSObject else ArrayBuiltins.arrayCreate(realm, len)
            arrayLikeStep()
        }
    }

    // ------------------------------------------------------------------ iterable path

    private fun iteratorStep() {
        val rec = iteratorRecord!!
        if (k >= ArrayBuiltins.MAX_SAFE) {
            closeAndReject(rec, realm.typeError("Array.fromAsync: too many elements"))
            return
        }
        val nextResult = Ops.call(rec.nextMethod, rec.iterator, EMPTY_ARGS)
        await(nextResult) { r -> onNextResult(rec, r) }
    }

    private fun onNextResult(rec: IteratorRecord, r: Any?) {
        if (r !is JSObject) throw realm.typeError("Iterator result ${Ops.toDisplayString(r)} is not an object")
        if (Ops.toBoolean(r.get("done", r))) {
            target.setOrThrow("length", k.toDouble())
            Promises.resolvePromise(realm, promise, target)
            return
        }
        val nextValue = r.get("value", r)
        if (!mapping) {
            addFromIterator(rec, nextValue)
            return
        }
        val mapped = try {
            Ops.call(mapfn, thisArg, arrayOf(nextValue, k.toDouble()))
        } catch (t: Throwable) {
            closeAndReject(rec, t)
            return
        }
        awaitValue(realm, mapped, { v -> guard { addFromIterator(rec, v) } }, { e -> guard { closeAndReject(rec, JSException(e)) } })
    }

    private fun addFromIterator(rec: IteratorRecord, value: Any?) {
        try {
            target.createDataPropertyOrThrow(ArrayBuiltins.key(k), value)
        } catch (t: Throwable) {
            closeAndReject(rec, t)
            return
        }
        k++
        iteratorStep()
    }

    /** AsyncIteratorClose(rec, ThrowCompletion(t)): awaits the result of return() but always rejects with [t]. */
    private fun closeAndReject(rec: IteratorRecord, t: Throwable) {
        if (t is TerminationException) throw t
        val error = DisposeCapability.thrownValue(realm, t)
        val inner = Iteration.asyncCloseCall(rec, true)
        if (inner === NotFound) Promises.rejectPromise(realm, promise, error)
        else awaitValue(realm, inner, { Promises.rejectPromise(realm, promise, error) }, { Promises.rejectPromise(realm, promise, error) })
    }

    // ------------------------------------------------------------------ array-like path

    private fun arrayLikeStep() {
        if (k >= len) {
            target.setOrThrow("length", len.toDouble())
            Promises.resolvePromise(realm, promise, target)
            return
        }
        val source = arrayLike!!
        val kValue = source.get(ArrayBuiltins.key(k), source)
        await(kValue) { v ->
            if (mapping) await(Ops.call(mapfn, thisArg, arrayOf(v, k.toDouble()))) { mv -> addFromArrayLike(mv) }
            else addFromArrayLike(v)
        }
    }

    private fun addFromArrayLike(value: Any?) {
        target.createDataPropertyOrThrow(ArrayBuiltins.key(k), value)
        k++
        arrayLikeStep()
    }
}
