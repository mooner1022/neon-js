package dev.mooner.neonjs.vm

import dev.mooner.neonjs.runtime.*

/** Iterator Record. */
class IteratorRecord(@JvmField val iterator: JSObject, @JvmField val nextMethod: Any?, @JvmField var done: Boolean = false)

/** for-in key enumeration (EnumerateObjectProperties). */
class ForInIterator(start: JSObject) {
    private var obj: JSObject? = start
    private var keys: List<Any>? = null
    private var idx = 0
    private val visited = HashSet<Any>()

    /** Returns the next key as a JS string value, or null when exhausted. */
    fun next(): Any? {
        while (true) {
            val o = obj ?: return null
            var ks = keys
            if (ks == null) {
                ks = o.ownPropertyKeys()
                keys = ks
                idx = 0
            }
            while (idx < ks.size) {
                val k = ks[idx++]
                if (k is JSSymbol) continue
                if (visited.contains(k)) continue
                val d = o.getOwnProperty(k) ?: continue
                visited.add(k)
                if (d.enumerable) return PK.toValue(k)
            }
            obj = o.getPrototypeOf()
            keys = null
        }
    }
}

object Iteration {
    @JvmStatic
    fun getIterator(realm: Realm, obj: Any?, async: Boolean): IteratorRecord {
        if (async) {
            val m = Ops.getMethod(obj, JSSymbol.asyncIterator)
            if (m === Undefined) {
                val sm = Ops.getMethod(obj, JSSymbol.iterator)
                if (sm === Undefined) throw JSException.typeError("${Ops.describe(obj)} is not async iterable")
                val syncRec = fromMethod(obj, sm)
                return AsyncFromSyncIterator.create(realm, syncRec)
            }
            return fromMethod(obj, m)
        }
        val m = Ops.getMethod(obj, JSSymbol.iterator)
        if (m === Undefined) throw JSException.typeError("${Ops.describe(obj)} is not iterable")
        return fromMethod(obj, m)
    }

    @JvmStatic
    fun fromMethod(obj: Any?, method: Any?): IteratorRecord {
        val it = Ops.call(method, obj, EMPTY_ARGS)
        if (it !is JSObject) throw JSException.typeError("Result of the Symbol.iterator method is not an object")
        val next = it.get("next", it)
        return IteratorRecord(it, next)
    }

    /** IteratorStepValue: returns the next value or [NotFound] when done. */
    @JvmStatic
    fun stepValue(rec: IteratorRecord): Any? {
        val r: Any?
        try {
            r = Ops.call(rec.nextMethod, rec.iterator, EMPTY_ARGS)
        } catch (e: Throwable) {
            rec.done = true
            throw e
        }
        if (r !is JSObject) {
            rec.done = true
            throw JSException.typeError("Iterator result ${Ops.toDisplayString(r)} is not an object")
        }
        val done: Boolean
        try {
            done = Ops.toBoolean(r.get("done", r))
        } catch (e: Throwable) {
            rec.done = true
            throw e
        }
        if (done) {
            rec.done = true
            return NotFound
        }
        try {
            return r.get("value", r)
        } catch (e: Throwable) {
            rec.done = true
            throw e
        }
    }

    @JvmStatic
    fun callNext(rec: IteratorRecord, v: Any?): Any? {
        try {
            return Ops.call(rec.nextMethod, rec.iterator, if (v === Undefined) EMPTY_ARGS else arrayOf(v))
        } catch (e: Throwable) {
            rec.done = true
            throw e
        }
    }

    @JvmStatic
    fun complete(rec: IteratorRecord, r: JSObject): Boolean {
        try {
            return Ops.toBoolean(r.get("done", r))
        } catch (e: Throwable) {
            rec.done = true
            throw e
        }
    }

    @JvmStatic
    fun value(rec: IteratorRecord, r: JSObject): Any? {
        try {
            return r.get("value", r)
        } catch (e: Throwable) {
            rec.done = true
            throw e
        }
    }

    @JvmStatic
    fun rest(realm: Realm, rec: IteratorRecord): JSArray {
        val a = JSArray(realm.arrayPrototype)
        while (!rec.done) {
            val v = stepValue(rec)
            if (v === NotFound) break
            a.pushInit(v)
        }
        return a
    }

    /** IteratorClose with a normal completion. */
    @JvmStatic
    fun closeNormal(rec: IteratorRecord) {
        if (rec.done) return
        rec.done = true
        val it = rec.iterator
        val ret = Ops.getMethod(it, "return")
        if (ret === Undefined) return
        val r = Ops.call(ret, it, EMPTY_ARGS)
        if (r !is JSObject) throw JSException.typeError("Iterator result ${Ops.toDisplayString(r)} is not an object")
    }

    /** IteratorClose with a throw completion: errors from return() are ignored. */
    @JvmStatic
    fun closeOnThrow(rec: IteratorRecord) {
        if (rec.done) return
        rec.done = true
        try {
            val it = rec.iterator
            val ret = Ops.getMethod(it, "return")
            if (ret === Undefined) return
            Ops.call(ret, it, EMPTY_ARGS)
        } catch (_: JSException) {
            // ignored
        } catch (_: StackOverflowError) {
            // ignored
        }
    }

    /** Close helper for host/builtin code: IteratorClose(rec, completion) where [t] is the pending throwable. */
    @JvmStatic
    fun closeAndRethrow(rec: IteratorRecord, t: Throwable): Nothing {
        if (t is TerminationException) throw t
        closeOnThrow(rec)
        throw t
    }

    /** AsyncIteratorClose: calls return(); returns the call result (to be awaited) or [NotFound]. */
    @JvmStatic
    fun asyncCloseCall(rec: IteratorRecord, isThrow: Boolean): Any? {
        if (rec.done && !isThrow) return NotFound
        rec.done = true
        val it = rec.iterator
        if (isThrow) {
            return try {
                val ret = Ops.getMethod(it, "return")
                if (ret === Undefined) NotFound else Ops.call(ret, it, EMPTY_ARGS)
            } catch (_: JSException) {
                NotFound
            }
        }
        val ret = Ops.getMethod(it, "return")
        if (ret === Undefined) return NotFound
        return Ops.call(ret, it, EMPTY_ARGS)
    }

    class YStarDone(@JvmField val value: Any?, @JvmField val isReturn: Boolean)

    /** One step of yield* (sync generators). */
    @JvmStatic
    fun yieldStarStep(rec: IteratorRecord, received: Any?, mode: Int): Any? {
        val it = rec.iterator
        when (mode) {
            Frame.MODE_THROW -> {
                val th = Ops.getMethod(it, "throw")
                if (th === Undefined) {
                    rec.done = false
                    closeNormal(rec)
                    throw JSException.typeError("The iterator does not provide a 'throw' method")
                }
                val r = Ops.call(th, it, arrayOf(received))
                if (r !is JSObject) throw JSException.typeError("Iterator result ${Ops.toDisplayString(r)} is not an object")
                if (Ops.toBoolean(r.get("done", r))) return YStarDone(r.get("value", r), false)
                return r
            }
            Frame.MODE_RETURN -> {
                val ret = Ops.getMethod(it, "return")
                if (ret === Undefined) return YStarDone(received, true)
                val r = Ops.call(ret, it, arrayOf(received))
                if (r !is JSObject) throw JSException.typeError("Iterator result ${Ops.toDisplayString(r)} is not an object")
                if (Ops.toBoolean(r.get("done", r))) return YStarDone(r.get("value", r), true)
                return r
            }
            else -> {
                val r = Ops.call(rec.nextMethod, it, arrayOf(received))
                if (r !is JSObject) throw JSException.typeError("Iterator result ${Ops.toDisplayString(r)} is not an object")
                if (Ops.toBoolean(r.get("done", r))) return YStarDone(r.get("value", r), false)
                return r
            }
        }
    }

    /**
     * yield* in async generators, call part: returns the value to await, or [YStarDone] (return without return
     * method -> isReturn; throw without throw method -> not return, mode becomes 3).
     */
    @JvmStatic
    fun yieldStarAsyncCall(rec: IteratorRecord, received: Any?, mode: Int): Any? {
        val it = rec.iterator
        return when (mode) {
            Frame.MODE_THROW -> {
                val th = Ops.getMethod(it, "throw")
                if (th === Undefined) YStarDone(Undefined, false) else Ops.call(th, it, arrayOf(received))
            }
            Frame.MODE_RETURN -> {
                val ret = Ops.getMethod(it, "return")
                if (ret === Undefined) YStarDone(received, true) else Ops.call(ret, it, arrayOf(received))
            }
            else -> Ops.call(rec.nextMethod, it, arrayOf(received))
        }
    }

    /** True if iterating [arr] with the default iterator is unobservable. */
    @JvmStatic
    fun isPristineArrayIteration(realm: Realm, arr: JSArray): Boolean {
        if (arr.proto !== realm.arrayPrototype) return false
        val pm = arr.props
        if (pm != null && pm.find(JSSymbol.iterator) >= 0) return false
        val ap = realm.arrayPrototype
        val app = ap.props ?: return false
        val i = app.find(JSSymbol.iterator)
        if (i < 0 || app.values[i] !== realm.arrayProtoValues || app.flags[i] and Attr.ACCESSOR != 0) return false
        val aip = realm.arrayIteratorPrototype.props ?: return false
        val j = aip.find("next")
        return j >= 0 && aip.values[j] === realm.arrayIteratorNext && arr.protoChainClean()
    }

    /** Iterates an iterable into a list (host helper). */
    @JvmStatic
    fun toList(realm: Realm, iterable: Any?): ArrayList<Any?> {
        val out = ArrayList<Any?>()
        if (iterable is JSArray && !iterable.sparse && isPristineArrayIteration(realm, iterable)) {
            for (i in 0 until iterable.length.toInt()) out.add(iterable.get(i, iterable))
            return out
        }
        val rec = getIterator(realm, iterable, false)
        while (true) {
            val v = stepValue(rec)
            if (v === NotFound) break
            out.add(v)
        }
        return out
    }

    @JvmStatic
    fun createIterResult(realm: Realm, value: Any?, done: Boolean): JSObject {
        val o = JSObject(realm.objectPrototype)
        val pm = o.ensureProps()
        pm.add("value", value, Attr.ALL)
        pm.add("done", done, Attr.ALL)
        return o
    }
}
