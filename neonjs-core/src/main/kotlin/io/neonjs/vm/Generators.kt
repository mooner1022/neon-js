package io.neonjs.vm

import io.neonjs.compiler.Op
import io.neonjs.runtime.*

/** Generator instance. */
class JSGenerator(proto: JSObject?, @JvmField var frame: Frame?) : JSObject(proto) {
    @JvmField var state = SUSPENDED_START

    companion object {
        const val SUSPENDED_START = 0
        const val SUSPENDED_YIELD = 1
        const val EXECUTING = 2
        const val COMPLETED = 3
    }

    fun resume(realm: Realm, value: Any?, mode: Int): Any? {
        when (state) {
            EXECUTING -> throw JSException.typeError("Generator is already running")
            COMPLETED -> {
                return when (mode) {
                    Frame.MODE_THROW -> throw JSException(value)
                    Frame.MODE_RETURN -> Iteration.createIterResult(realm, value, true)
                    else -> Iteration.createIterResult(realm, Undefined, true)
                }
            }
            SUSPENDED_START -> {
                if (mode == Frame.MODE_RETURN) {
                    state = COMPLETED
                    frame = null
                    return Iteration.createIterResult(realm, value, true)
                }
                if (mode == Frame.MODE_THROW) {
                    state = COMPLETED
                    frame = null
                    throw JSException(value)
                }
            }
            else -> {
                val f = frame!!
                f.slots[f.sp++] = value
                f.resumeMode = mode
            }
        }
        val f = frame!!
        state = EXECUTING
        val r: Any?
        try {
            r = Interpreter.execute(f)
        } catch (t: Throwable) {
            state = COMPLETED
            frame = null
            throw t
        }
        if (r === Interpreter.SUSPENDED) {
            state = SUSPENDED_YIELD
            val v = f.suspendValue
            f.suspendValue = null
            return if (f.suspendKind == Frame.SUSPEND_YIELD_RAW) v else Iteration.createIterResult(realm, v, false)
        }
        state = COMPLETED
        frame = null
        return Iteration.createIterResult(realm, r, true)
    }
}

class AsyncGenRequest(@JvmField val mode: Int, @JvmField val value: Any?, @JvmField val capability: PromiseCapability)

/** Async generator instance. */
class JSAsyncGenerator(proto: JSObject?, @JvmField var frame: Frame?) : JSObject(proto) {
    @JvmField var state = SUSPENDED_START
    @JvmField val queue = ArrayDeque<AsyncGenRequest>()

    companion object {
        const val SUSPENDED_START = 0
        const val SUSPENDED_YIELD = 1
        const val EXECUTING = 2
        const val AWAITING_RETURN = 3
        const val COMPLETED = 4
    }
}

object Generators {

    /** Entry for generator / async function calls (after frame creation). */
    @JvmStatic
    fun start(frame: Frame): Any? {
        val code = frame.code
        val realm = frame.realm
        if (code.isAsync && !code.isGenerator) return asyncFunctionStart(frame)
        // run parameter initialization up to GENERATOR_INIT
        val r = Interpreter.execute(frame)
        if (r !== Interpreter.SUSPENDED) throw IllegalStateException("generator prologue did not suspend")
        val fn = frame.fn!!
        if (code.isAsync) {
            val proto = protoOf(fn, realm.asyncGeneratorPrototype)
            val g = JSAsyncGenerator(proto, frame)
            frame.generator = g
            return g
        }
        val proto = protoOf(fn, realm.generatorPrototype)
        val g = JSGenerator(proto, frame)
        frame.generator = g
        return g
    }

    private fun protoOf(fn: JSObject, default: JSObject): JSObject {
        val p = fn.get("prototype", fn)
        if (p is JSObject) return p
        return if (fn is JSFunction) {
            if (default === fn.realm.generatorPrototype || default === fn.realm.asyncGeneratorPrototype) default else default
        } else default
    }

    // ------------------------------------------------------------------ async functions

    private fun asyncFunctionStart(frame: Frame): Any? {
        val realm = frame.realm
        val p = Promises.newPromise(realm)
        asyncStep(frame, realm, p)
        return p
    }

    private fun asyncStep(frame: Frame, realm: Realm, p: JSPromise) {
        val r: Any?
        try {
            r = Interpreter.execute(frame)
        } catch (e: JSException) {
            Promises.rejectPromise(realm, p, e.value)
            return
        } catch (t: TerminationException) {
            throw t
        } catch (t: Throwable) {
            Promises.rejectPromise(realm, p, Rt.catchValue(t, realm, null, 0))
            return
        }
        if (r === Interpreter.SUSPENDED) {
            awaitThen(frame, realm) { asyncStep(frame, realm, p) }
            return
        }
        Promises.resolvePromise(realm, p, r)
    }

    /** Runs an async module body, settling [cap] when it completes. */
    @JvmStatic
    fun runAsyncBody(frame: Frame, realm: Realm, cap: PromiseCapability) {
        val r: Any?
        try {
            r = Interpreter.execute(frame)
        } catch (e: JSException) {
            Ops.call(cap.reject, Undefined, arrayOf(e.value))
            return
        } catch (t: TerminationException) {
            throw t
        } catch (t: Throwable) {
            Ops.call(cap.reject, Undefined, arrayOf(Rt.catchValue(t, realm, null, 0)))
            return
        }
        if (r === Interpreter.SUSPENDED) {
            awaitThen(frame, realm) { runAsyncBody(frame, realm, cap) }
            return
        }
        Ops.call(cap.resolve, Undefined, arrayOf(Undefined))
    }

    /** Subscribes to the awaited promise of a suspended frame; [cont] re-runs the frame after resumption. */
    @JvmStatic
    fun awaitThen(frame: Frame, realm: Realm, cont: () -> Unit) {
        val promise = frame.suspendValue as JSObject
        frame.suspendValue = null
        val pr = promise as? JSPromise ?: Promises.resolved(realm, promise)
        Promises.thenHost(realm, pr, { v ->
            prepareAwaitResume(frame, v, false)
            cont()
            Undefined
        }, { e ->
            prepareAwaitResume(frame, e, true)
            cont()
            Undefined
        })
    }

    /** Pushes the await result onto the frame according to the await variant at [Frame.awaitPc]. */
    @JvmStatic
    fun prepareAwaitResume(f: Frame, v: Any?, isThrow: Boolean) {
        val code = f.code.code
        when (code[f.awaitPc]) {
            Op.AWAIT_IGNORE -> {
                f.slots[f.sp++] = if (isThrow) Undefined else v
                f.resumeMode = Frame.MODE_NEXT
            }
            Op.AWAIT_CATCH -> {
                if (isThrow) f.slots[code[f.awaitPc + 1]] = Ops.num(Frame.MODE_THROW)
                f.slots[f.sp++] = v
                f.resumeMode = Frame.MODE_NEXT
            }
            else -> {
                f.slots[f.sp++] = v
                f.resumeMode = if (isThrow) Frame.MODE_THROW else Frame.MODE_NEXT
            }
        }
        f.suspendKind = Frame.SUSPEND_AWAIT
    }

    // ------------------------------------------------------------------ async generators

    @JvmStatic
    fun asyncGenEnqueue(realm: Realm, gen: Any?, mode: Int, value: Any?): Any? {
        val cap = Promises.newPromiseCapability(realm, realm.promiseConstructor)
        if (gen !is JSAsyncGenerator) {
            Ops.call(cap.reject, Undefined, arrayOf(realm.newError(ErrorKind.TYPE, "not an AsyncGenerator")))
            return cap.promise
        }
        var state = gen.state
        when (mode) {
            Frame.MODE_NEXT -> {
                if (state == JSAsyncGenerator.COMPLETED) {
                    Ops.call(cap.resolve, Undefined, arrayOf(Iteration.createIterResult(realm, Undefined, true)))
                    return cap.promise
                }
                gen.queue.addLast(AsyncGenRequest(mode, value, cap))
                if (state == JSAsyncGenerator.SUSPENDED_START || state == JSAsyncGenerator.SUSPENDED_YIELD) asyncGenResume(realm, gen, mode, value)
            }
            Frame.MODE_RETURN -> {
                gen.queue.addLast(AsyncGenRequest(mode, value, cap))
                if (state == JSAsyncGenerator.SUSPENDED_START || state == JSAsyncGenerator.COMPLETED) {
                    gen.state = JSAsyncGenerator.AWAITING_RETURN
                    asyncGenAwaitReturn(realm, gen)
                } else if (state == JSAsyncGenerator.SUSPENDED_YIELD) asyncGenResume(realm, gen, mode, value)
            }
            else -> {
                if (state == JSAsyncGenerator.SUSPENDED_START) {
                    gen.state = JSAsyncGenerator.COMPLETED
                    gen.frame = null
                    state = JSAsyncGenerator.COMPLETED
                }
                if (state == JSAsyncGenerator.COMPLETED) {
                    Ops.call(cap.reject, Undefined, arrayOf(value))
                    return cap.promise
                }
                gen.queue.addLast(AsyncGenRequest(mode, value, cap))
                if (state == JSAsyncGenerator.SUSPENDED_YIELD) asyncGenResume(realm, gen, mode, value)
            }
        }
        return cap.promise
    }

    private fun asyncGenResume(realm: Realm, gen: JSAsyncGenerator, mode: Int, value: Any?) {
        val f = gen.frame!!
        if (gen.state == JSAsyncGenerator.SUSPENDED_YIELD) {
            f.slots[f.sp++] = value
            f.resumeMode = mode
        }
        gen.state = JSAsyncGenerator.EXECUTING
        asyncGenStep(realm, gen, f)
    }

    private fun asyncGenStep(realm: Realm, gen: JSAsyncGenerator, f: Frame) {
        val frame = f
        while (true) {
            val r: Any?
            try {
                r = Interpreter.execute(frame)
            } catch (e: TerminationException) {
                throw e
            } catch (t: Throwable) {
                val v = if (t is JSException) t.value else Rt.catchValue(t, realm, null, 0)
                gen.state = JSAsyncGenerator.COMPLETED
                gen.frame = null
                completeStep(realm, gen, true, v, true)
                drainQueue(realm, gen)
                return
            }
            if (r === Interpreter.SUSPENDED) {
                if (frame.suspendKind == Frame.SUSPEND_AWAIT) {
                    awaitThen(frame, realm) { asyncGenStep(realm, gen, frame) }
                    return
                }
                // yield
                val v = frame.suspendValue
                frame.suspendValue = null
                gen.state = JSAsyncGenerator.SUSPENDED_YIELD
                completeStep(realm, gen, false, v, false)
                val next = gen.queue.firstOrNull() ?: return
                // resume immediately with the next queued request
                frame.slots[frame.sp++] = next.value
                frame.resumeMode = next.mode
                gen.state = JSAsyncGenerator.EXECUTING
                continue
            }
            gen.state = JSAsyncGenerator.COMPLETED
            gen.frame = null
            completeStep(realm, gen, false, r, true)
            drainQueue(realm, gen)
            return
        }
    }

    private fun completeStep(realm: Realm, gen: JSAsyncGenerator, isThrow: Boolean, value: Any?, done: Boolean) {
        val next = gen.queue.removeFirstOrNull() ?: return
        val cap = next.capability
        if (isThrow) Ops.call(cap.reject, Undefined, arrayOf(value))
        else Ops.call(cap.resolve, Undefined, arrayOf(Iteration.createIterResult(realm, value, done)))
    }

    private fun drainQueue(realm: Realm, gen: JSAsyncGenerator) {
        while (true) {
            val next = gen.queue.firstOrNull() ?: return
            if (next.mode == Frame.MODE_RETURN) {
                gen.state = JSAsyncGenerator.AWAITING_RETURN
                asyncGenAwaitReturn(realm, gen)
                return
            }
            if (next.mode == Frame.MODE_THROW) completeStep(realm, gen, true, next.value, true)
            else completeStep(realm, gen, false, Undefined, true)
        }
    }

    private fun asyncGenAwaitReturn(realm: Realm, gen: JSAsyncGenerator) {
        val next = gen.queue.first()
        val promise: JSObject
        try {
            promise = Promises.promiseResolve(realm, realm.promiseConstructor, next.value)
        } catch (e: JSException) {
            gen.state = JSAsyncGenerator.COMPLETED
            completeStep(realm, gen, true, e.value, true)
            drainQueue(realm, gen)
            return
        }
        Promises.thenHost(realm, promise as JSPromise, { v ->
            gen.state = JSAsyncGenerator.COMPLETED
            completeStep(realm, gen, false, v, true)
            drainQueue(realm, gen)
            Undefined
        }, { e ->
            gen.state = JSAsyncGenerator.COMPLETED
            completeStep(realm, gen, true, e, true)
            drainQueue(realm, gen)
            Undefined
        })
    }
}

/** %AsyncFromSyncIteratorPrototype% support. */
object AsyncFromSyncIterator {
    class Obj(proto: JSObject?, @JvmField val syncRec: IteratorRecord) : JSObject(proto)

    @JvmStatic
    fun create(realm: Realm, syncRec: IteratorRecord): IteratorRecord {
        val o = Obj(realm.asyncFromSyncIteratorPrototype, syncRec)
        val next = realm.asyncFromSyncIteratorPrototype.get("next", o)
        return IteratorRecord(o, next)
    }

    /** Implements next/return/throw. which: 0 next, 1 return, 2 throw. */
    @JvmStatic
    fun method(realm: Realm, thisV: Any?, args: Array<Any?>, which: Int): Any? {
        val o = thisV as Obj
        val cap = Promises.newPromiseCapability(realm, realm.promiseConstructor)
        val rec = o.syncRec
        val sync = rec.iterator
        val result: Any?
        try {
            when (which) {
                0 -> result = Ops.call(rec.nextMethod, sync, if (args.isNotEmpty()) arrayOf(args[0]) else EMPTY_ARGS)
                1 -> {
                    val ret = Ops.getMethod(sync, "return")
                    if (ret === Undefined) {
                        Ops.call(cap.resolve, Undefined, arrayOf(Iteration.createIterResult(realm, args.arg(0), true)))
                        return cap.promise
                    }
                    result = Ops.call(ret, sync, if (args.isNotEmpty()) arrayOf(args[0]) else EMPTY_ARGS)
                }
                else -> {
                    val th = Ops.getMethod(sync, "throw")
                    if (th === Undefined) {
                        // close the sync iterator and reject with TypeError
                        rec.done = false
                        try {
                            Iteration.closeNormal(rec)
                        } catch (e: JSException) {
                            Ops.call(cap.reject, Undefined, arrayOf(e.value))
                            return cap.promise
                        }
                        Ops.call(cap.reject, Undefined, arrayOf(realm.newError(ErrorKind.TYPE, "The iterator does not provide a 'throw' method")))
                        return cap.promise
                    }
                    result = Ops.call(th, sync, arrayOf(args.arg(0)))
                }
            }
            if (result !is JSObject) throw JSException.typeError("Iterator result ${Ops.toDisplayString(result)} is not an object")
        } catch (e: JSException) {
            Ops.call(cap.reject, Undefined, arrayOf(e.value))
            return cap.promise
        }
        continuation(realm, result, cap, rec, which != 1)
        return cap.promise
    }

    private fun continuation(realm: Realm, result: JSObject, cap: PromiseCapability, rec: IteratorRecord, closeOnRejection: Boolean) {
        val done: Boolean
        val value: Any?
        try {
            done = Ops.toBoolean(result.get("done", result))
            value = result.get("value", result)
        } catch (e: JSException) {
            Ops.call(cap.reject, Undefined, arrayOf(e.value))
            return
        }
        val wrapper: JSObject
        try {
            wrapper = Promises.promiseResolve(realm, realm.promiseConstructor, value)
        } catch (e: JSException) {
            if (!done && closeOnRejection) {
                rec.done = false
                Iteration.closeOnThrow(rec)
            }
            Ops.call(cap.reject, Undefined, arrayOf(e.value))
            return
        }
        val onFul = NativeFunction(realm, "", 1, { _, _, a, _ -> Iteration.createIterResult(realm, a.arg(0), done) })
        val onRej: Any = if (done || !closeOnRejection) Undefined else NativeFunction(realm, "", 1, { _, _, a, _ ->
            rec.done = false
            Iteration.closeOnThrow(rec)
            throw JSException(a.arg(0))
        })
        Promises.performThen(realm, wrapper as JSPromise, onFul, onRej, cap)
    }
}
