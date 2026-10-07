package io.neonjs.builtins

import io.neonjs.runtime.*
import io.neonjs.vm.*

/** Iterator helper object (%IteratorHelperPrototype% instances). */
class IteratorHelperObject(proto: JSObject?, @JvmField val underlying: IteratorRecord, @JvmField val step: (IteratorHelperObject) -> Any?) : JSObject(proto) {
    @JvmField var state = 0 // 0 suspended-start, 1 suspended-yield, 2 running, 3 completed
    /** Inner iterator for flatMap. */
    @JvmField var inner: IteratorRecord? = null
    @JvmField var counter = 0.0
    @JvmField var extra: Any? = null
    /** [[UnderlyingIterators]] of helpers over several iterators (Iterator.zip/zipKeyed); closed in reverse order. */
    @JvmField var openIters: MutableList<IteratorRecord>? = null
}

/** %WrapForValidIteratorPrototype% instances. */
class WrappedIteratorObject(proto: JSObject?, @JvmField val rec: IteratorRecord) : JSObject(proto)

internal object IteratorBuiltins {
    private const val MAX_SAFE_INTEGER = 9007199254740991.0

    fun install(realm: Realm) {
        val ip = JSObject(realm.objectPrototype)
        realm.iteratorPrototype = ip
        ip.method(realm, JSSymbol.iterator, 0) { _, t, _, _ -> t }
        val aip = JSObject(realm.objectPrototype)
        realm.asyncIteratorPrototype = aip
        aip.method(realm, JSSymbol.asyncIterator, 0) { _, t, _, _ -> t }

        // Iterator constructor (abstract)
        val ctor = makeCtor(realm, "Iterator", 0, ip) { f, _, _, nt ->
            if (nt == null || nt === f) typeErr("Abstract class Iterator not directly constructable")
            JSObject(Ops.getPrototypeFromConstructor(nt) { it.iteratorPrototype })
        }
        realm.global("Iterator", ctor)
        realm.intrinsics["%Iterator%"] = ctor
        // constructor / @@toStringTag are accessors per the iterator helpers spec
        ip.accessor(realm, "constructor", { _, _, _, _ -> ctor }, { f, t, args, _ ->
            setterThatIgnoresPrototypeProperties(t, f.realm.iteratorPrototype, "constructor", args.arg(0)); Undefined
        })
        ip.accessor(realm, JSSymbol.toStringTag, { _, _, _, _ -> "Iterator" }, { f, t, args, _ ->
            setterThatIgnoresPrototypeProperties(t, f.realm.iteratorPrototype, JSSymbol.toStringTag, args.arg(0)); Undefined
        })
        ip.method(realm, JSSymbol.dispose, 0) { _, t, _, _ ->
            val ret = Ops.getMethod(t, "return")
            if (ret !== Undefined) Ops.call(ret, t, EMPTY_ARGS)
            Undefined
        }
        aip.method(realm, JSSymbol.asyncDispose, 0) { f, t, _, _ ->
            val cap = Promises.newPromiseCapability(f.realm, f.realm.promiseConstructor)
            try {
                val ret = Ops.getMethod(t, "return")
                if (ret === Undefined) Ops.call(cap.resolve, Undefined, arrayOf(Undefined))
                else {
                    val r = Ops.call(ret, t, EMPTY_ARGS)
                    val p = Promises.promiseResolve(f.realm, f.realm.promiseConstructor, r)
                    val onFul = NativeFunction(f.realm, "", 1, { _, _, _, _ -> Undefined })
                    Promises.performThen(f.realm, p as JSPromise, onFul, Undefined, cap)
                }
            } catch (e: JSException) {
                Ops.call(cap.reject, Undefined, arrayOf(e.value))
            }
            cap.promise
        }

        // helper prototype
        val hp = JSObject(ip)
        realm.intrinsics["%IteratorHelperPrototype%"] = hp
        hp.method(realm, "next", 0) { f, t, _, _ ->
            val h = t as? IteratorHelperObject ?: typeErr("Iterator Helper next called on incompatible receiver")
            helperNext(f.realm, h)
        }
        hp.method(realm, "return", 0) { f, t, _, _ ->
            val h = t as? IteratorHelperObject ?: typeErr("Iterator Helper return called on incompatible receiver")
            when (h.state) {
                2 -> typeErr("Generator is already running")
                3 -> {}
                0 -> {
                    // suspended-start: the helper completes before its underlying iterators are closed
                    h.state = 3
                    closeUnderlying(h)
                }
                else -> {
                    // suspended-yield: resume with a return completion; the closure is executing while it closes
                    h.state = 2
                    try {
                        closeUnderlying(h)
                    } finally {
                        h.state = 3
                    }
                }
            }
            Iteration.createIterResult(f.realm, Undefined, true)
        }
        hp.value(JSSymbol.toStringTag, "Iterator Helper", Attr.CONFIGURABLE)

        // wrap-for-valid-iterator prototype
        val wp = JSObject(ip)
        wp.method(realm, "next", 0) { _, t, _, _ ->
            val w = t as? WrappedIteratorObject ?: typeErr("incompatible receiver")
            Ops.call(w.rec.nextMethod, w.rec.iterator, EMPTY_ARGS)
        }
        wp.method(realm, "return", 0) { f, t, _, _ ->
            val w = t as? WrappedIteratorObject ?: typeErr("incompatible receiver")
            val it = w.rec.iterator
            val ret = Ops.getMethod(it, "return")
            if (ret === Undefined) Iteration.createIterResult(f.realm, Undefined, true)
            else Ops.call(ret, it, EMPTY_ARGS)
        }

        ctor.method(realm, "from", 1) { f, _, args, _ ->
            val o = args.arg(0)
            val rec = getIteratorFlattenable(o, true)
            if (Ops.ordinaryHasInstance(ctor, rec.iterator)) rec.iterator
            else WrappedIteratorObject(wp, rec)
        }
        ctor.method(realm, "concat", 0) { _, _, args, _ ->
            val iterables = ArrayList<Pair<JSObject, Any?>>()
            for (a in args) {
                if (a !is JSObject) typeErr("Iterator.concat requires iterable objects")
                val m = Ops.getMethod(a, JSSymbol.iterator)
                if (m === Undefined) typeErr("${Ops.describe(a)} is not iterable")
                iterables.add(a to m)
            }
            var idx = 0
            val dummy = IteratorRecord(JSObject(null), Undefined, true)
            val h = IteratorHelperObject(hp, dummy) { self ->
                var result: Any? = NotFound
                while (true) {
                    val cur = self.inner
                    if (cur == null) {
                        if (idx >= iterables.size) break
                        val (obj, m) = iterables[idx++]
                        val it = Ops.call(m, obj, EMPTY_ARGS)
                        if (it !is JSObject) typeErr("Result of the Symbol.iterator method is not an object")
                        self.inner = IteratorRecord(it, it.get("next", it))
                        continue
                    }
                    val r = Ops.call(cur.nextMethod, cur.iterator, EMPTY_ARGS)
                    if (r !is JSObject) typeErr("Iterator result ${Ops.toDisplayString(r)} is not an object")
                    if (Ops.toBoolean(r.get("done", r))) {
                        self.inner = null
                        continue
                    }
                    result = r.get("value", r)
                    break
                }
                result
            }
            h
        }

        val proto = ip
        proto.method(realm, "map", 1) { _, t, args, _ ->
            val o = requireIterObj(t)
            val mapper = args.arg(0)
            if (!Ops.isCallable(mapper)) closeAndThrow(o, "${Ops.describe(mapper)} is not a function")
            val rec = getIteratorDirect(o)
            IteratorHelperObject(hp, rec) { h ->
                val v = Iteration.stepValue(rec)
                if (v === NotFound) NotFound
                else {
                    try {
                        (mapper as JSObject).call(Undefined, arrayOf(v, h.counter++))
                    } catch (t: Throwable) {
                        Iteration.closeAndRethrow(rec, t)
                    }
                }
            }
        }
        proto.method(realm, "filter", 1) { _, t, args, _ ->
            val o = requireIterObj(t)
            val pred = args.arg(0)
            if (!Ops.isCallable(pred)) closeAndThrow(o, "${Ops.describe(pred)} is not a function")
            val rec = getIteratorDirect(o)
            IteratorHelperObject(hp, rec) { h ->
                var res: Any? = NotFound
                while (true) {
                    val v = Iteration.stepValue(rec)
                    if (v === NotFound) break
                    val sel = try {
                        Ops.toBoolean((pred as JSObject).call(Undefined, arrayOf(v, h.counter++)))
                    } catch (t: Throwable) {
                        Iteration.closeAndRethrow(rec, t)
                    }
                    if (sel) { res = v; break }
                }
                res
            }
        }
        proto.method(realm, "take", 1) { _, t, args, _ ->
            val o = requireIterObj(t)
            val limit = numericLimit(o, args.arg(0))
            val rec = getIteratorDirect(o)
            var remaining = limit
            IteratorHelperObject(hp, rec) { _ ->
                if (remaining == 0.0) {
                    Iteration.closeNormal(rec)
                    NotFound
                } else {
                    if (remaining != Double.POSITIVE_INFINITY) remaining--
                    val v = Iteration.stepValue(rec)
                    v
                }
            }
        }
        proto.method(realm, "drop", 1) { _, t, args, _ ->
            val o = requireIterObj(t)
            val limit = numericLimit(o, args.arg(0))
            val rec = getIteratorDirect(o)
            var remaining = limit
            IteratorHelperObject(hp, rec) { _ ->
                var res: Any? = NotFound
                var done = false
                while (remaining > 0) {
                    if (remaining != Double.POSITIVE_INFINITY) remaining--
                    val v = Iteration.stepValue(rec)
                    if (v === NotFound) { done = true; break }
                }
                if (!done) res = Iteration.stepValue(rec)
                res
            }
        }
        proto.method(realm, "flatMap", 1) { f, t, args, _ ->
            val o = requireIterObj(t)
            val mapper = args.arg(0)
            if (!Ops.isCallable(mapper)) closeAndThrow(o, "${Ops.describe(mapper)} is not a function")
            val rec = getIteratorDirect(o)
            IteratorHelperObject(hp, rec) { h ->
                var res: Any? = NotFound
                while (true) {
                    val inner = h.inner
                    if (inner != null) {
                        val v = try {
                            Iteration.stepValue(inner)
                        } catch (t: Throwable) {
                            Iteration.closeAndRethrow(rec, t)
                        }
                        if (v === NotFound) {
                            h.inner = null
                            continue
                        }
                        res = v
                        break
                    }
                    val v = Iteration.stepValue(rec)
                    if (v === NotFound) break
                    try {
                        val mapped = (mapper as JSObject).call(Undefined, arrayOf(v, h.counter++))
                        h.inner = getIteratorFlattenable(mapped, false)
                    } catch (t: Throwable) {
                        Iteration.closeAndRethrow(rec, t)
                    }
                }
                res
            }
        }
        proto.method(realm, "reduce", 1) { _, t, args, _ ->
            val o = requireIterObj(t)
            val reducer = args.arg(0)
            if (!Ops.isCallable(reducer)) closeAndThrow(o, "${Ops.describe(reducer)} is not a function")
            val rec = getIteratorDirect(o)
            var acc: Any?
            var counter: Double
            if (args.size < 2) {
                val first = Iteration.stepValue(rec)
                if (first === NotFound) typeErr("Reduce of empty iterator with no initial value")
                acc = first
                counter = 1.0
            } else {
                acc = args[1]
                counter = 0.0
            }
            while (true) {
                val v = Iteration.stepValue(rec)
                if (v === NotFound) break
                try {
                    acc = (reducer as JSObject).call(Undefined, arrayOf(acc, v, counter))
                } catch (t: Throwable) {
                    Iteration.closeAndRethrow(rec, t)
                }
                counter++
            }
            acc
        }
        proto.method(realm, "toArray", 0) { f, t, _, _ ->
            val o = requireIterObj(t)
            val rec = getIteratorDirect(o)
            val items = ArrayList<Any?>()
            while (true) {
                val v = Iteration.stepValue(rec)
                if (v === NotFound) break
                items.add(v)
            }
            Builtins.arrayOf(f.realm, items)
        }
        proto.method(realm, "forEach", 1) { _, t, args, _ ->
            val o = requireIterObj(t)
            val fn = args.arg(0)
            if (!Ops.isCallable(fn)) closeAndThrow(o, "${Ops.describe(fn)} is not a function")
            val rec = getIteratorDirect(o)
            var counter = 0.0
            while (true) {
                val v = Iteration.stepValue(rec)
                if (v === NotFound) break
                try {
                    (fn as JSObject).call(Undefined, arrayOf(v, counter++))
                } catch (t: Throwable) {
                    Iteration.closeAndRethrow(rec, t)
                }
            }
            Undefined
        }
        for ((name, kind) in listOf("some" to 0, "every" to 1, "find" to 2)) {
            proto.method(realm, name, 1) { _, t, args, _ ->
                val o = requireIterObj(t)
                val pred = args.arg(0)
                if (!Ops.isCallable(pred)) closeAndThrow(o, "${Ops.describe(pred)} is not a function")
                val rec = getIteratorDirect(o)
                var counter = 0.0
                var result: Any? = when (kind) { 0 -> false; 1 -> true; else -> Undefined }
                while (true) {
                    val v = Iteration.stepValue(rec)
                    if (v === NotFound) break
                    val r = try {
                        Ops.toBoolean((pred as JSObject).call(Undefined, arrayOf(v, counter++)))
                    } catch (t: Throwable) {
                        Iteration.closeAndRethrow(rec, t)
                    }
                    if (kind == 0 && r) { Iteration.closeNormal(rec); result = true; break }
                    if (kind == 1 && !r) { Iteration.closeNormal(rec); result = false; break }
                    if (kind == 2 && r) { Iteration.closeNormal(rec); result = v; break }
                }
                result
            }
        }
        proto.method(realm, "chunks", 1) { f, t, args, _ ->
            val o = requireIterObj(t)
            val chunkSize = chunkingSize(o, args.arg(0), "chunkSize")
            val rec = getIteratorDirect(o)
            var buffer = ArrayList<Any?>()
            IteratorHelperObject(hp, rec) { _ ->
                var res: Any? = NotFound
                // once the final partial chunk was yielded the closure returns without stepping the iterator again
                while (!rec.done) {
                    val v = Iteration.stepValue(rec)
                    if (v === NotFound) {
                        if (buffer.isNotEmpty()) res = Builtins.arrayOf(f.realm, buffer)
                        break
                    }
                    buffer.add(v)
                    if (buffer.size.toLong() == chunkSize) {
                        res = Builtins.arrayOf(f.realm, buffer)
                        buffer = ArrayList()
                        break
                    }
                }
                res
            }
        }
        proto.method(realm, "windows", 1) { f, t, args, _ ->
            val o = requireIterObj(t)
            val windowSize = chunkingSize(o, args.arg(0), "windowSize")
            val undersized = args.arg(1)
            val allowPartial = when {
                undersized === Undefined -> false
                undersized is CharSequence && undersized.toString() == "only-full" -> false
                undersized is CharSequence && undersized.toString() == "allow-partial" -> true
                else -> closeAndThrow(o, "undersized must be \"only-full\" or \"allow-partial\"")
            }
            val rec = getIteratorDirect(o)
            val buffer = ArrayDeque<Any?>()
            IteratorHelperObject(hp, rec) { _ ->
                var res: Any? = NotFound
                while (!rec.done) {
                    val v = Iteration.stepValue(rec)
                    if (v === NotFound) {
                        if (allowPartial && buffer.isNotEmpty() && buffer.size < windowSize) res = Builtins.arrayOf(f.realm, buffer.toList())
                        break
                    }
                    if (buffer.size.toLong() == windowSize) buffer.removeFirst()
                    buffer.addLast(v)
                    if (buffer.size.toLong() == windowSize) {
                        res = Builtins.arrayOf(f.realm, buffer.toList())
                        break
                    }
                }
                res
            }
        }
        proto.method(realm, "includes", 1) { _, t, args, _ ->
            val o = requireIterObj(t)
            val searchElement = args.arg(0)
            val skippedElements = args.arg(1)
            val toSkip = when {
                skippedElements === Undefined -> 0.0
                skippedElements is Double && (skippedElements.isInfinite() || Ops.isIntegral(skippedElements)) -> skippedElements
                else -> closeAndThrow(o, "skippedElements must be an integral number")
            }
            if (toSkip < 0.0 || (toSkip.isFinite() && toSkip > MAX_SAFE_INTEGER)) {
                closeAndThrow(o, JSException.rangeError("skippedElements must be a non-negative safe integer"))
            }
            val rec = getIteratorDirect(o)
            var skipped = 0.0
            var found = false
            while (true) {
                val v = Iteration.stepValue(rec)
                if (v === NotFound) break
                if (skipped < toSkip) skipped++
                else if (Ops.sameValueZero(v, searchElement)) {
                    found = true
                    Iteration.closeNormal(rec)
                    break
                }
            }
            found
        }
        proto.method(realm, "join", 1) { _, t, args, _ ->
            val o = requireIterObj(t)
            val separator = args.arg(0)
            val sep = if (separator === Undefined) "," else try {
                Ops.toString(separator)
            } catch (e: Throwable) {
                Iteration.closeAndRethrow(IteratorRecord(o, Undefined), e)
            }
            val rec = getIteratorDirect(o)
            val sb = StringBuilder()
            var first = true
            while (true) {
                val v = Iteration.stepValue(rec)
                if (v === NotFound) break
                try {
                    if (!first) sb.append(sep)
                    first = false
                    if (v !== Undefined && v !== Null) sb.append(Ops.toString(v))
                    if (sb.length > 65536) realm.agent.checkStringLength(sb.length.toLong())
                } catch (e: Throwable) {
                    Iteration.closeAndRethrow(rec, e)
                }
            }
            sb.toString()
        }

        ctor.method(realm, "zip", 1) { f, _, args, _ ->
            val iterables = args.arg(0) as? JSObject ?: typeErr("Iterator.zip requires an iterable object")
            val (mode, paddingOption) = zipOptions(args.arg(1))
            val iters = ArrayList<IteratorRecord>()
            val inputIter = Iteration.getIterator(f.realm, iterables, false)
            while (true) {
                val next = try {
                    Iteration.stepValue(inputIter)
                } catch (t: Throwable) {
                    closeAllAndThrow(iters, t)
                }
                if (next === NotFound) break
                val iter = try {
                    getIteratorFlattenable(next, false)
                } catch (t: Throwable) {
                    closeAllAndThrow(listOf(inputIter) + iters, t)
                }
                iters.add(iter)
            }
            val padding = ArrayList<Any?>(iters.size)
            if (mode == ZIP_LONGEST) {
                if (paddingOption === Undefined) repeat(iters.size) { padding.add(Undefined) }
                else {
                    val paddingIter = try {
                        Iteration.getIterator(f.realm, paddingOption, false)
                    } catch (t: Throwable) {
                        closeAllAndThrow(iters, t)
                    }
                    var usingIterator = true
                    repeat(iters.size) {
                        if (usingIterator) {
                            val next = try {
                                Iteration.stepValue(paddingIter)
                            } catch (t: Throwable) {
                                closeAllAndThrow(iters, t)
                            }
                            if (next === NotFound) usingIterator = false else padding.add(next)
                        }
                        if (!usingIterator) padding.add(Undefined)
                    }
                    if (usingIterator) {
                        try {
                            Iteration.closeNormal(paddingIter)
                        } catch (t: Throwable) {
                            closeAllAndThrow(iters, t)
                        }
                    }
                }
            }
            iteratorZip(hp, iters, mode, padding) { results -> Builtins.arrayOf(f.realm, results) }
        }
        ctor.method(realm, "zipKeyed", 1) { f, _, args, _ ->
            val iterables = args.arg(0) as? JSObject ?: typeErr("Iterator.zipKeyed requires an object")
            val (mode, paddingOption) = zipOptions(args.arg(1))
            val iters = ArrayList<IteratorRecord>()
            val keys = ArrayList<Any>()
            for (key in iterables.ownPropertyKeys()) {
                val desc = try {
                    iterables.getOwnProperty(key)
                } catch (t: Throwable) {
                    closeAllAndThrow(iters, t)
                }
                if (desc == null || !desc.enumerable) continue
                val value = try {
                    iterables.get(key, iterables)
                } catch (t: Throwable) {
                    closeAllAndThrow(iters, t)
                }
                if (value === Undefined) continue
                val iter = try {
                    getIteratorFlattenable(value, false)
                } catch (t: Throwable) {
                    closeAllAndThrow(iters, t)
                }
                keys.add(key)
                iters.add(iter)
            }
            val padding = ArrayList<Any?>(iters.size)
            if (mode == ZIP_LONGEST) {
                if (paddingOption === Undefined) repeat(iters.size) { padding.add(Undefined) }
                else for (key in keys) {
                    val value = try {
                        (paddingOption as JSObject).get(key, paddingOption)
                    } catch (t: Throwable) {
                        closeAllAndThrow(iters, t)
                    }
                    padding.add(value)
                }
            }
            iteratorZip(hp, iters, mode, padding) { results ->
                val obj = JSObject(null)
                for (i in results.indices) obj.createDataPropertyOrThrow(keys[i], results[i])
                obj
            }
        }
    }

    private const val ZIP_SHORTEST = "shortest"
    private const val ZIP_LONGEST = "longest"
    private const val ZIP_STRICT = "strict"

    /** GetOptionsObject + the "mode" / "padding" options of Iterator.zip and Iterator.zipKeyed. */
    private fun zipOptions(options: Any?): Pair<String, Any?> {
        val opts = when (options) {
            Undefined -> JSObject(null)
            is JSObject -> options
            else -> typeErr("options must be an object")
        }
        val m = opts.get("mode", opts)
        val mode = if (m === Undefined) ZIP_SHORTEST else when ((m as? CharSequence)?.toString()) {
            ZIP_SHORTEST -> ZIP_SHORTEST
            ZIP_LONGEST -> ZIP_LONGEST
            ZIP_STRICT -> ZIP_STRICT
            else -> typeErr("mode must be \"shortest\", \"longest\", or \"strict\"")
        }
        var padding: Any? = Undefined
        if (mode == ZIP_LONGEST) {
            padding = opts.get("padding", opts)
            if (padding !== Undefined && padding !is JSObject) typeErr("padding must be an object")
        }
        return mode to padding
    }

    /** IteratorZip(iters, mode, padding, finishResults) */
    private fun iteratorZip(hp: JSObject, iterList: List<IteratorRecord>, mode: String, padding: List<Any?>, finishResults: (List<Any?>) -> Any?): IteratorHelperObject {
        val iters = ArrayList<IteratorRecord?>(iterList)
        val openIters = ArrayList(iterList)
        val h = IteratorHelperObject(hp, IteratorRecord(JSObject(null), Undefined, true)) { _ -> zipStep(iters, openIters, mode, padding, finishResults) }
        h.openIters = openIters
        return h
    }

    /** One resumption of the IteratorZip closure: the next results, or [NotFound] when it returns. */
    private fun zipStep(iters: ArrayList<IteratorRecord?>, openIters: MutableList<IteratorRecord>, mode: String, padding: List<Any?>, finishResults: (List<Any?>) -> Any?): Any? {
        val iterCount = iters.size
        if (iterCount == 0) return NotFound
        val results = ArrayList<Any?>(iterCount)
        for (i in 0 until iterCount) {
            val iter = iters[i]
            if (iter == null) {
                results.add(padding[i])
                continue
            }
            val result = try {
                Iteration.stepValue(iter)
            } catch (t: Throwable) {
                openIters.remove(iter)
                closeAllAndThrow(openIters, t)
            }
            if (result !== NotFound) {
                results.add(result)
                continue
            }
            openIters.remove(iter)
            when (mode) {
                ZIP_SHORTEST -> {
                    closeAll(openIters)
                    return NotFound
                }
                ZIP_STRICT -> {
                    if (i != 0) closeAllAndThrow(openIters, JSException.typeError("Iterator.zip: iterators have different lengths in strict mode"))
                    for (k in 1 until iterCount) {
                        val other = iters[k]!!
                        val done = try {
                            val r = Iteration.callNext(other, Undefined)
                            if (r !is JSObject) {
                                other.done = true
                                throw JSException.typeError("Iterator result ${Ops.toDisplayString(r)} is not an object")
                            }
                            Iteration.complete(other, r)
                        } catch (t: Throwable) {
                            openIters.remove(other)
                            closeAllAndThrow(openIters, t)
                        }
                        if (!done) closeAllAndThrow(openIters, JSException.typeError("Iterator.zip: iterators have different lengths in strict mode"))
                        openIters.remove(other)
                    }
                    return NotFound
                }
                else -> {
                    if (openIters.isEmpty()) return NotFound
                    iters[i] = null
                    results.add(padding[i])
                }
            }
        }
        return finishResults(results)
    }

    private fun helperNext(realm: Realm, h: IteratorHelperObject): Any? {
        when (h.state) {
            2 -> typeErr("Generator is already running")
            3 -> return Iteration.createIterResult(realm, Undefined, true)
        }
        h.state = 2
        val v: Any?
        try {
            v = h.step(h)
        } catch (t: Throwable) {
            h.state = 3
            throw t
        }
        if (v === NotFound) {
            h.state = 3
            return Iteration.createIterResult(realm, Undefined, true)
        }
        h.state = 1
        return Iteration.createIterResult(realm, v, false)
    }

    private fun requireIterObj(t: Any?): JSObject = t as? JSObject ?: typeErr("Iterator.prototype method called on non-object")

    /** IteratorClose(iterated, ThrowCompletion(err)) for an iterator whose next method has not been read yet. */
    private fun closeAndThrow(o: JSObject, err: JSException): Nothing = Iteration.closeAndRethrow(IteratorRecord(o, Undefined), err)

    private fun closeAndThrow(o: JSObject, msg: String): Nothing = closeAndThrow(o, JSException.typeError(msg))

    private fun numericLimit(o: JSObject, v: Any?): Double {
        val num: Double
        try {
            num = Ops.toNumber(v)
        } catch (t: Throwable) {
            Iteration.closeAndRethrow(IteratorRecord(o, Undefined), t)
        }
        if (num.isNaN() || (num.isFinite() && num > MAX_SAFE_INTEGER)) {
            closeAndThrow(o, JSException.rangeError("${Ops.toDisplayString(v)} must be a non-negative safe integer"))
        }
        val lim = Ops.integerPart(num)
        if (lim < 0) closeAndThrow(o, JSException.rangeError("${Ops.toDisplayString(v)} must be positive"))
        return lim
    }

    /** Validates the size argument of chunks/windows: an integral Number in [1, 2^32 - 1], without coercion. */
    private fun chunkingSize(o: JSObject, v: Any?, what: String): Long {
        if (v !is Double || !Ops.isIntegral(v)) closeAndThrow(o, "$what must be an integral number")
        if (v < 1.0 || v > 4294967295.0) closeAndThrow(o, JSException.rangeError("$what must be between 1 and 2^32 - 1"))
        return v.toLong()
    }

    /** Closes the underlying iterators of [h] when it is resumed with a return completion. */
    private fun closeUnderlying(h: IteratorHelperObject) {
        val open = h.openIters
        if (open != null) {
            closeAll(open)
            return
        }
        val inner = h.inner
        if (inner != null) {
            try {
                Iteration.closeNormal(inner)
            } catch (e: Throwable) {
                Iteration.closeAndRethrow(h.underlying, e)
            }
        }
        Iteration.closeNormal(h.underlying)
    }

    /** IteratorCloseAll(iters, NormalCompletion): closes in reverse order; the first error from a return() wins. */
    fun closeAll(iters: List<IteratorRecord>) {
        var pending: Throwable? = null
        for (i in iters.indices.reversed()) {
            if (pending != null) {
                Iteration.closeOnThrow(iters[i])
                continue
            }
            try {
                Iteration.closeNormal(iters[i])
            } catch (t: Throwable) {
                if (t is TerminationException) throw t
                pending = t
            }
        }
        if (pending != null) throw pending
    }

    /** IteratorCloseAll(iters, ThrowCompletion(t)): errors from return() are ignored. */
    fun closeAllAndThrow(iters: List<IteratorRecord>, t: Throwable): Nothing {
        if (t is TerminationException) throw t
        for (i in iters.indices.reversed()) Iteration.closeOnThrow(iters[i])
        throw t
    }

    /** GetIteratorDirect */
    fun getIteratorDirect(o: JSObject): IteratorRecord {
        val next = o.get("next", o)
        return IteratorRecord(o, next)
    }

    /** GetIteratorFlattenable(obj, primitiveHandling) */
    fun getIteratorFlattenable(obj: Any?, iterateStrings: Boolean): IteratorRecord {
        if (obj !is JSObject) {
            if (!(iterateStrings && obj is CharSequence)) typeErr("${Ops.describe(obj)} is not an object")
        }
        val m = Ops.getMethod(obj, JSSymbol.iterator)
        val it = if (m === Undefined) obj else Ops.call(m, obj, EMPTY_ARGS)
        if (it !is JSObject) typeErr("${Ops.describe(it)} is not an object")
        return getIteratorDirect(it)
    }

    /** SetterThatIgnoresPrototypeProperties */
    fun setterThatIgnoresPrototypeProperties(thisV: Any?, home: JSObject, p: Any, v: Any?) {
        if (thisV !is JSObject) typeErr("setter called on non-object")
        if (thisV === home) typeErr("Cannot assign to read only property")
        val d = thisV.getOwnProperty(p)
        if (d == null) thisV.createDataPropertyOrThrow(p, v)
        else thisV.setOrThrow(p, v)
    }
}

internal object GeneratorBuiltins {
    fun install(realm: Realm) {
        // %GeneratorFunction%, %GeneratorFunction.prototype%, %GeneratorPrototype%
        val gfp = JSObject(realm.functionPrototype)
        realm.generatorFunctionPrototype = gfp
        val gp = JSObject(realm.iteratorPrototype)
        realm.generatorPrototype = gp
        val gf = makeCtor(realm, "GeneratorFunction", 1, null, realm.functionConstructor) { f, _, args, nt ->
            Evaluator.createDynamicFunction(f.realm, nt ?: f, "generator", args)
        }
        gf.defineOwn("prototype", gfp, Attr.NONE)
        gfp.defineOwn("constructor", gf, Attr.CONFIGURABLE)
        gfp.defineOwn("prototype", gp, Attr.CONFIGURABLE)
        gfp.value(JSSymbol.toStringTag, "GeneratorFunction", Attr.CONFIGURABLE)
        gp.defineOwn("constructor", gfp, Attr.CONFIGURABLE)
        gp.value(JSSymbol.toStringTag, "Generator", Attr.CONFIGURABLE)
        realm.intrinsics["%GeneratorFunction%"] = gf
        fun gen(t: Any?): JSGenerator = t as? JSGenerator ?: typeErr("next method called on incompatible receiver ${Ops.describe(t)}")
        gp.method(realm, "next", 1) { f, t, args, _ -> gen(t).resume(f.realm, args.arg(0), Frame.MODE_NEXT) }
        gp.method(realm, "return", 1) { f, t, args, _ -> gen(t).resume(f.realm, args.arg(0), Frame.MODE_RETURN) }
        gp.method(realm, "throw", 1) { f, t, args, _ -> gen(t).resume(f.realm, args.arg(0), Frame.MODE_THROW) }

        // %AsyncFunction%
        val afp = JSObject(realm.functionPrototype)
        realm.asyncFunctionPrototype = afp
        val af = makeCtor(realm, "AsyncFunction", 1, null, realm.functionConstructor) { f, _, args, nt ->
            Evaluator.createDynamicFunction(f.realm, nt ?: f, "async", args)
        }
        af.defineOwn("prototype", afp, Attr.NONE)
        afp.defineOwn("constructor", af, Attr.CONFIGURABLE)
        afp.value(JSSymbol.toStringTag, "AsyncFunction", Attr.CONFIGURABLE)
        realm.intrinsics["%AsyncFunction%"] = af

        // %AsyncGeneratorFunction%
        val agfp = JSObject(realm.functionPrototype)
        realm.asyncGeneratorFunctionPrototype = agfp
        val agp = JSObject(realm.asyncIteratorPrototype)
        realm.asyncGeneratorPrototype = agp
        val agf = makeCtor(realm, "AsyncGeneratorFunction", 1, null, realm.functionConstructor) { f, _, args, nt ->
            Evaluator.createDynamicFunction(f.realm, nt ?: f, "asyncGenerator", args)
        }
        agf.defineOwn("prototype", agfp, Attr.NONE)
        agfp.defineOwn("constructor", agf, Attr.CONFIGURABLE)
        agfp.defineOwn("prototype", agp, Attr.CONFIGURABLE)
        agfp.value(JSSymbol.toStringTag, "AsyncGeneratorFunction", Attr.CONFIGURABLE)
        agp.defineOwn("constructor", agfp, Attr.CONFIGURABLE)
        agp.value(JSSymbol.toStringTag, "AsyncGenerator", Attr.CONFIGURABLE)
        realm.intrinsics["%AsyncGeneratorFunction%"] = agf
        agp.method(realm, "next", 1) { f, t, args, _ -> Generators.asyncGenEnqueue(f.realm, t, Frame.MODE_NEXT, args.arg(0)) }
        agp.method(realm, "return", 1) { f, t, args, _ -> Generators.asyncGenEnqueue(f.realm, t, Frame.MODE_RETURN, args.arg(0)) }
        agp.method(realm, "throw", 1) { f, t, args, _ -> Generators.asyncGenEnqueue(f.realm, t, Frame.MODE_THROW, args.arg(0)) }

        // %AsyncFromSyncIteratorPrototype%
        val afsp = JSObject(realm.asyncIteratorPrototype)
        realm.asyncFromSyncIteratorPrototype = afsp
        afsp.method(realm, "next", 1) { f, t, args, _ -> AsyncFromSyncIterator.method(f.realm, t, args, 0) }
        afsp.method(realm, "return", 1) { f, t, args, _ -> AsyncFromSyncIterator.method(f.realm, t, args, 1) }
        afsp.method(realm, "throw", 1) { f, t, args, _ -> AsyncFromSyncIterator.method(f.realm, t, args, 2) }
    }
}
