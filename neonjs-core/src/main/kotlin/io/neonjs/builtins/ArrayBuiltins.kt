package io.neonjs.builtins

import io.neonjs.runtime.*
import io.neonjs.vm.*

/** Array iterator instance. kind: 0 keys, 1 values, 2 entries. */
class ArrayIteratorObject(proto: JSObject?, @JvmField var iterated: JSObject?, @JvmField val kind: Int) : JSObject(proto) {
    @JvmField var index = 0L
}

internal object ArrayBuiltins {
    const val MAX_SAFE = 9007199254740991L

    /**
     * The property key of index [i]. Every generic loop over an array-like goes through here, and such a loop can run
     * up to 2^53 - 1 times without calling any JS (`Array.prototype.indexOf.call({ length: 2 ** 53 - 1 })`), so this
     * is also where those loops check the agent's limits: once per 1,024 consecutive indices.
     */
    fun key(i: Long): Any {
        if (i and 1023L == 1023L) Agent.current.get()?.checkInterrupt()
        return PK.fromIndex(i)
    }

    fun arrayCreate(realm: Realm, length: Long, proto: JSObject? = null): JSArray {
        if (length > JSArray.MAX_LENGTH) rangeErr("Invalid array length")
        val a = JSArray(proto ?: realm.arrayPrototype)
        a.length = length
        return a
    }

    /** ArraySpeciesCreate */
    fun speciesCreate(realm: Realm, original: JSObject, length: Long): JSObject {
        if (!Ops.isArray(original)) return arrayCreate(realm, length)
        var c = original.get("constructor", original)
        if (Ops.isConstructor(c)) {
            val realmC = Ops.getFunctionRealm(c as JSObject)
            if (realmC !== realm && c === realmC.arrayConstructor) c = Undefined
        }
        if (c is JSObject) {
            c = c.get(JSSymbol.species, c)
            if (c === Null) c = Undefined
        }
        if (c === Undefined) return arrayCreate(realm, length)
        if (!Ops.isConstructor(c)) typeErr("object.constructor[Symbol.species] is not a constructor")
        return (c as JSObject).construct(arrayOf(length.toDouble()), c) as JSObject
    }

    fun len(o: JSObject): Long = Ops.lengthOfArrayLike(o)

    fun setLen(o: JSObject, n: Long) = o.setOrThrow("length", n.toDouble())

    fun isConcatSpreadable(o: Any?): Boolean {
        if (o !is JSObject) return false
        val s = o.get(JSSymbol.isConcatSpreadable, o)
        if (s !== Undefined) return Ops.toBoolean(s)
        return Ops.isArray(o)
    }

    fun install(realm: Realm) {
        val proto = JSArray(realm.objectPrototype)
        realm.arrayPrototype = proto
        val ctor = makeCtor(realm, "Array", 1, proto) { f, _, args, nt ->
            val p = Ops.getPrototypeFromConstructor(nt ?: f) { it.arrayPrototype }
            when (args.size) {
                0 -> arrayCreate(f.realm, 0, p)
                1 -> {
                    val l = args[0]
                    if (l !is Double) {
                        val a = arrayCreate(f.realm, 0, p)
                        a.pushInit(l)
                        a
                    } else {
                        val il = Ops.toUint32(l)
                        if (il.toDouble() != l) rangeErr("Invalid array length")
                        arrayCreate(f.realm, il, p)
                    }
                }
                else -> {
                    val a = JSArray.of(p, args.copyOf())
                    a
                }
            }
        }
        realm.arrayConstructor = ctor
        realm.global("Array", ctor)
        ctor.getter(realm, JSSymbol.species) { _, t, _, _ -> t }
        ctor.method(realm, "isArray", 1) { _, _, args, _ -> Ops.isArray(args.arg(0)) }
        ctor.method(realm, "of", 0) { f, t, args, _ ->
            val a: JSObject = if (Ops.isConstructor(t)) (t as JSObject).construct(arrayOf(args.size.toDouble()), t) as JSObject
            else arrayCreate(f.realm, args.size.toLong())
            for (i in args.indices) a.createDataPropertyOrThrow(i, args[i])
            setLen(a, args.size.toLong())
            a
        }
        ctor.method(realm, "from", 1) { f, t, args, _ -> from(f.realm, t, args.arg(0), args.arg(1), args.arg(2)) }
        ctor.method(realm, "fromAsync", 1) { f, t, args, _ -> ArrayFromAsync.start(f.realm, t, args.arg(0), args.arg(1), args.arg(2)) }

        // ---------------- prototype
        proto.method(realm, "at", 1) { _, t, args, _ ->
            val o = Ops.toObject(t)
            val n = len(o)
            val rel = Ops.toIntegerOrInfinity(args.arg(0))
            val k = if (rel >= 0) rel else n + rel
            if (k < 0 || k >= n) Undefined else o.get(key(k.toLong()), o)
        }
        proto.method(realm, "concat", 1) { f, t, args, _ ->
            val o = Ops.toObject(t)
            val a = speciesCreate(f.realm, o, 0)
            var n = 0L
            val items = ArrayList<Any?>(args.size + 1)
            items.add(o)
            items.addAll(args)
            for (e in items) {
                if (isConcatSpreadable(e)) {
                    e as JSObject
                    val l = len(e)
                    if (n + l > MAX_SAFE) typeErr("Invalid array length")
                    var k = 0L
                    while (k < l) {
                        val pk = key(k)
                        if (e.hasProperty(pk)) a.createDataPropertyOrThrow(key(n), e.get(pk, e))
                        n++
                        k++
                    }
                } else {
                    if (n >= MAX_SAFE) typeErr("Invalid array length")
                    a.createDataPropertyOrThrow(key(n), e)
                    n++
                }
            }
            setLen(a, n)
            a
        }
        proto.method(realm, "copyWithin", 2) { _, t, args, _ ->
            val o = Ops.toObject(t)
            val n = len(o)
            var to = relIndex(args.arg(0), n, 0)
            var from = relIndex(args.arg(1), n, 0)
            val fin = relIndex(args.arg(2), n, n)
            var count = minOf(fin - from, n - to)
            if (count > 0) {
                var dir = 1
                if (from < to && to < from + count) {
                    dir = -1
                    from += count - 1
                    to += count - 1
                }
                while (count > 0) {
                    val fk = key(from)
                    val tk = key(to)
                    if (o.hasProperty(fk)) o.setOrThrow(tk, o.get(fk, o)) else o.deleteOrThrow(tk)
                    from += dir
                    to += dir
                    count--
                }
            }
            o
        }
        proto.method(realm, "entries", 0) { f, t, _, _ -> ArrayIteratorObject(f.realm.arrayIteratorPrototype, Ops.toObject(t), 2) }
        proto.method(realm, "every", 1) { _, t, args, _ ->
            val o = Ops.toObject(t)
            val n = len(o)
            val cb = callable(args.arg(0))
            var r = true
            var k = 0L
            while (k < n) {
                val pk = key(k)
                if (o.hasProperty(pk)) {
                    val v = o.get(pk, o)
                    if (!Ops.toBoolean(cb.call(args.arg(1), arrayOf(v, k.toDouble(), o)))) { r = false; break }
                }
                k++
            }
            r
        }
        proto.method(realm, "fill", 1) { _, t, args, _ ->
            val o = Ops.toObject(t)
            val n = len(o)
            val v = args.arg(0)
            var k = relIndex(args.arg(1), n, 0)
            val fin = relIndex(args.arg(2), n, n)
            while (k < fin) {
                o.setOrThrow(key(k), v)
                k++
            }
            o
        }
        proto.method(realm, "filter", 1) { f, t, args, _ ->
            val o = Ops.toObject(t)
            val n = len(o)
            val cb = callable(args.arg(0))
            val a = speciesCreate(f.realm, o, 0)
            var to = 0L
            var k = 0L
            while (k < n) {
                val pk = key(k)
                if (o.hasProperty(pk)) {
                    val v = o.get(pk, o)
                    if (Ops.toBoolean(cb.call(args.arg(1), arrayOf(v, k.toDouble(), o)))) {
                        a.createDataPropertyOrThrow(key(to), v)
                        to++
                    }
                }
                k++
            }
            a
        }
        for ((name, fromEnd, wantIndex) in listOf(
            Triple("find", false, false), Triple("findIndex", false, true),
            Triple("findLast", true, false), Triple("findLastIndex", true, true),
        )) {
            proto.method(realm, name, 1) { _, t, args, _ ->
                val o = Ops.toObject(t)
                val n = len(o)
                val cb = callable(args.arg(0))
                var result: Any? = if (wantIndex) -1.0 else Undefined
                var k = if (fromEnd) n - 1 else 0L
                while (if (fromEnd) k >= 0 else k < n) {
                    val v = o.get(key(k), o)
                    if (Ops.toBoolean(cb.call(args.arg(1), arrayOf(v, k.toDouble(), o)))) {
                        result = if (wantIndex) k.toDouble() else v
                        break
                    }
                    if (fromEnd) k-- else k++
                }
                result
            }
        }
        proto.method(realm, "flat", 0) { f, t, args, _ ->
            val o = Ops.toObject(t)
            val n = len(o)
            var depth = 1.0
            if (args.arg(0) !== Undefined) {
                depth = Ops.toIntegerOrInfinity(args.arg(0))
                if (depth < 0) depth = 0.0
            }
            val a = speciesCreate(f.realm, o, 0)
            flatten(a, o, n, 0, depth, null, null)
            a
        }
        proto.method(realm, "flatMap", 1) { f, t, args, _ ->
            val o = Ops.toObject(t)
            val n = len(o)
            val cb = callable(args.arg(0))
            val a = speciesCreate(f.realm, o, 0)
            flatten(a, o, n, 0, 1.0, cb, args.arg(1))
            a
        }
        proto.method(realm, "forEach", 1) { _, t, args, _ ->
            val o = Ops.toObject(t)
            val n = len(o)
            val cb = callable(args.arg(0))
            var k = 0L
            while (k < n) {
                val pk = key(k)
                if (o.hasProperty(pk)) cb.call(args.arg(1), arrayOf(o.get(pk, o), k.toDouble(), o))
                k++
            }
            Undefined
        }
        proto.method(realm, "includes", 1) { _, t, args, _ ->
            val o = Ops.toObject(t)
            val n = len(o)
            if (n == 0L) false
            else {
                var k = startIndex(args.arg(1), n)
                var r = false
                while (k < n) {
                    if (Ops.sameValueZero(o.get(key(k), o), args.arg(0))) { r = true; break }
                    k++
                }
                r
            }
        }
        proto.method(realm, "indexOf", 1) { _, t, args, _ ->
            val o = Ops.toObject(t)
            val n = len(o)
            if (n == 0L) -1.0
            else {
                var k = startIndex(args.arg(1), n)
                var r = -1.0
                while (k < n) {
                    val pk = key(k)
                    if (o.hasProperty(pk) && Ops.strictEquals(o.get(pk, o), args.arg(0))) { r = k.toDouble(); break }
                    k++
                }
                r
            }
        }
        proto.method(realm, "join", 1) { f, t, args, _ ->
            val o = Ops.toObject(t)
            val n = len(o)
            val sep = if (args.arg(0) === Undefined) "," else Ops.toString(args.arg(0))
            val sb = StringBuilder()
            var k = 0L
            while (k < n) {
                if (k > 0) sb.append(sep)
                val e = o.get(key(k), o)
                if (e !== Undefined && e !== Null) sb.append(Ops.toString(e))
                if (sb.length > 65536) f.realm.agent.checkStringLength(sb.length.toLong())
                k++
            }
            sb.toString()
        }
        proto.method(realm, "keys", 0) { f, t, _, _ -> ArrayIteratorObject(f.realm.arrayIteratorPrototype, Ops.toObject(t), 0) }
        proto.method(realm, "lastIndexOf", 1) { _, t, args, _ ->
            val o = Ops.toObject(t)
            val n = len(o)
            if (n == 0L) -1.0
            else {
                var k: Long = if (args.size > 1) {
                    val fi = Ops.toIntegerOrInfinity(args[1])
                    if (fi == Double.NEGATIVE_INFINITY) -1 else if (fi >= 0) minOf(fi, (n - 1).toDouble()).toLong() else (n + fi).toLong()
                } else n - 1
                var r = -1.0
                while (k >= 0) {
                    val pk = key(k)
                    if (o.hasProperty(pk) && Ops.strictEquals(o.get(pk, o), args.arg(0))) { r = k.toDouble(); break }
                    k--
                }
                r
            }
        }
        proto.method(realm, "map", 1) { f, t, args, _ ->
            val o = Ops.toObject(t)
            val n = len(o)
            val cb = callable(args.arg(0))
            val a = speciesCreate(f.realm, o, n)
            var k = 0L
            while (k < n) {
                val pk = key(k)
                if (o.hasProperty(pk)) a.createDataPropertyOrThrow(pk, cb.call(args.arg(1), arrayOf(o.get(pk, o), k.toDouble(), o)))
                k++
            }
            a
        }
        proto.method(realm, "pop", 0) { _, t, _, _ ->
            val o = Ops.toObject(t)
            val n = len(o)
            if (n == 0L) {
                setLen(o, 0)
                Undefined
            } else {
                val pk = key(n - 1)
                val e = o.get(pk, o)
                o.deleteOrThrow(pk)
                setLen(o, n - 1)
                e
            }
        }
        proto.method(realm, "push", 1) { _, t, args, _ ->
            val o = Ops.toObject(t)
            var n = len(o)
            if (n + args.size > MAX_SAFE) typeErr("Pushing ${args.size} elements on an array-like of length $n is disallowed, as the total surpasses 2**53-1")
            if (o is JSArray && o.lengthWritable && !o.sparse && o.extensible && o.length == o.denseLen.toLong() && o.protoChainClean()) {
                for (a in args) o.pushInit(a)
                o.length.toDouble()
            } else {
                for (a in args) {
                    o.setOrThrow(key(n), a)
                    n++
                }
                setLen(o, n)
                n.toDouble()
            }
        }
        for ((name, right) in listOf("reduce" to false, "reduceRight" to true)) {
            proto.method(realm, name, 1) { _, t, args, _ ->
                val o = Ops.toObject(t)
                val n = len(o)
                val cb = callable(args.arg(0))
                var k = if (right) n - 1 else 0L
                var acc: Any?
                if (args.size >= 2) acc = args[1]
                else {
                    var found = false
                    acc = Undefined
                    while (if (right) k >= 0 else k < n) {
                        val pk = key(k)
                        if (o.hasProperty(pk)) {
                            acc = o.get(pk, o)
                            found = true
                            if (right) k-- else k++
                            break
                        }
                        if (right) k-- else k++
                    }
                    if (!found) typeErr("Reduce of empty array with no initial value")
                }
                while (if (right) k >= 0 else k < n) {
                    val pk = key(k)
                    if (o.hasProperty(pk)) acc = cb.call(Undefined, arrayOf(acc, o.get(pk, o), k.toDouble(), o))
                    if (right) k-- else k++
                }
                acc
            }
        }
        proto.method(realm, "reverse", 0) { _, t, _, _ ->
            val o = Ops.toObject(t)
            val n = len(o)
            val mid = n / 2
            var lower = 0L
            while (lower != mid) {
                val upper = n - lower - 1
                val lk = key(lower)
                val uk = key(upper)
                val lowerExists = o.hasProperty(lk)
                val lowerValue = if (lowerExists) o.get(lk, o) else Undefined
                val upperExists = o.hasProperty(uk)
                val upperValue = if (upperExists) o.get(uk, o) else Undefined
                if (lowerExists && upperExists) {
                    o.setOrThrow(lk, upperValue)
                    o.setOrThrow(uk, lowerValue)
                } else if (upperExists) {
                    o.setOrThrow(lk, upperValue)
                    o.deleteOrThrow(uk)
                } else if (lowerExists) {
                    o.deleteOrThrow(lk)
                    o.setOrThrow(uk, lowerValue)
                }
                lower++
            }
            o
        }
        proto.method(realm, "shift", 0) { _, t, _, _ ->
            val o = Ops.toObject(t)
            val n = len(o)
            if (n == 0L) {
                setLen(o, 0)
                Undefined
            } else {
                val first = o.get(0, o)
                var k = 1L
                while (k < n) {
                    val from = key(k)
                    val to = key(k - 1)
                    if (o.hasProperty(from)) o.setOrThrow(to, o.get(from, o)) else o.deleteOrThrow(to)
                    k++
                }
                o.deleteOrThrow(key(n - 1))
                setLen(o, n - 1)
                first
            }
        }
        proto.method(realm, "slice", 2) { f, t, args, _ ->
            val o = Ops.toObject(t)
            val n = len(o)
            var k = relIndex(args.arg(0), n, 0)
            val fin = relIndex(args.arg(1), n, n)
            val count = maxOf(fin - k, 0)
            val a = speciesCreate(f.realm, o, count)
            var i = 0L
            while (k < fin) {
                val pk = key(k)
                if (o.hasProperty(pk)) a.createDataPropertyOrThrow(key(i), o.get(pk, o))
                k++
                i++
            }
            setLen(a, i)
            a
        }
        proto.method(realm, "some", 1) { _, t, args, _ ->
            val o = Ops.toObject(t)
            val n = len(o)
            val cb = callable(args.arg(0))
            var r = false
            var k = 0L
            while (k < n) {
                val pk = key(k)
                if (o.hasProperty(pk) && Ops.toBoolean(cb.call(args.arg(1), arrayOf(o.get(pk, o), k.toDouble(), o)))) { r = true; break }
                k++
            }
            r
        }
        proto.method(realm, "sort", 1) { _, t, args, _ ->
            val cmp = args.arg(0)
            if (cmp !== Undefined && !Ops.isCallable(cmp)) typeErr("The comparison function must be either a function or undefined")
            val o = Ops.toObject(t)
            val n = len(o)
            val items = collectItems(o, n, true)
            val sorted = mergeSort(items, cmp)
            var i = 0L
            for (v in sorted) {
                o.setOrThrow(key(i), v)
                i++
            }
            while (i < n) {
                o.deleteOrThrow(key(i))
                i++
            }
            o
        }
        proto.method(realm, "splice", 2) { f, t, args, _ ->
            val o = Ops.toObject(t)
            val n = len(o)
            val start = relIndex(args.arg(0), n, 0)
            val itemCount: Int
            val delCount: Long
            if (args.isEmpty()) {
                itemCount = 0
                delCount = 0
            } else if (args.size == 1) {
                itemCount = 0
                delCount = n - start
            } else {
                itemCount = args.size - 2
                val dc = Ops.toIntegerOrInfinity(args[1])
                delCount = minOf(maxOf(dc, 0.0), (n - start).toDouble()).toLong()
            }
            if (n + itemCount - delCount > MAX_SAFE) typeErr("Invalid array length")
            val a = speciesCreate(f.realm, o, delCount)
            var k = 0L
            while (k < delCount) {
                val from = key(start + k)
                if (o.hasProperty(from)) a.createDataPropertyOrThrow(key(k), o.get(from, o))
                k++
            }
            setLen(a, delCount)
            if (itemCount < delCount) {
                k = start
                while (k < n - delCount) {
                    val from = key(k + delCount)
                    val to = key(k + itemCount)
                    if (o.hasProperty(from)) o.setOrThrow(to, o.get(from, o)) else o.deleteOrThrow(to)
                    k++
                }
                k = n
                while (k > n - delCount + itemCount) {
                    o.deleteOrThrow(key(k - 1))
                    k--
                }
            } else if (itemCount > delCount) {
                k = n - delCount
                while (k > start) {
                    val from = key(k + delCount - 1)
                    val to = key(k + itemCount - 1)
                    if (o.hasProperty(from)) o.setOrThrow(to, o.get(from, o)) else o.deleteOrThrow(to)
                    k--
                }
            }
            for (i in 0 until itemCount) o.setOrThrow(key(start + i), args[i + 2])
            setLen(o, n - delCount + itemCount)
            a
        }
        proto.method(realm, "toLocaleString", 0) { _, t, _, _ ->
            val o = Ops.toObject(t)
            val n = len(o)
            val sb = StringBuilder()
            var k = 0L
            while (k < n) {
                if (k > 0) sb.append(',')
                val e = o.get(key(k), o)
                if (e !== Undefined && e !== Null) sb.append(Ops.toString(Ops.invoke(e, "toLocaleString", EMPTY_ARGS)))
                k++
            }
            sb.toString()
        }
        proto.method(realm, "toReversed", 0) { f, t, _, _ ->
            val o = Ops.toObject(t)
            val n = len(o)
            val a = arrayCreate(f.realm, n)
            var k = 0L
            while (k < n) {
                a.createDataPropertyOrThrow(key(k), o.get(key(n - k - 1), o))
                k++
            }
            a
        }
        proto.method(realm, "toSorted", 1) { f, t, args, _ ->
            val cmp = args.arg(0)
            if (cmp !== Undefined && !Ops.isCallable(cmp)) typeErr("The comparison function must be either a function or undefined")
            val o = Ops.toObject(t)
            val n = len(o)
            val a = arrayCreate(f.realm, n)
            val items = collectItems(o, n, false)
            val sorted = mergeSort(items, cmp)
            for ((i, v) in sorted.withIndex()) a.createDataPropertyOrThrow(key(i.toLong()), v)
            a
        }
        proto.method(realm, "toSpliced", 2) { f, t, args, _ ->
            val o = Ops.toObject(t)
            val n = len(o)
            val start = relIndex(args.arg(0), n, 0)
            val insertCount: Int
            val skip: Long
            if (args.isEmpty()) { insertCount = 0; skip = 0 }
            else if (args.size == 1) { insertCount = 0; skip = n - start }
            else {
                insertCount = args.size - 2
                val sc = Ops.toIntegerOrInfinity(args[1])
                skip = minOf(maxOf(sc, 0.0), (n - start).toDouble()).toLong()
            }
            val newLen = n + insertCount - skip
            if (newLen > MAX_SAFE) typeErr("Invalid array length")
            val a = arrayCreate(f.realm, newLen)
            var i = 0L
            var r = start + skip
            while (i < start) {
                a.createDataPropertyOrThrow(key(i), o.get(key(i), o))
                i++
            }
            for (j in 0 until insertCount) {
                a.createDataPropertyOrThrow(key(i), args[j + 2])
                i++
            }
            while (i < newLen) {
                a.createDataPropertyOrThrow(key(i), o.get(key(r), o))
                i++
                r++
            }
            a
        }
        proto.method(realm, "toString", 0) { _, t, _, _ ->
            val o = Ops.toObject(t)
            val join = o.get("join", o)
            if (Ops.isCallable(join)) (join as JSObject).call(o, EMPTY_ARGS)
            else ObjectBuiltins.objectToString(o)
        }
        proto.method(realm, "unshift", 1) { _, t, args, _ ->
            val o = Ops.toObject(t)
            val n = len(o)
            val c = args.size
            if (c > 0) {
                if (n + c > MAX_SAFE) typeErr("Invalid array length")
                var k = n
                while (k > 0) {
                    val from = key(k - 1)
                    val to = key(k + c - 1)
                    if (o.hasProperty(from)) o.setOrThrow(to, o.get(from, o)) else o.deleteOrThrow(to)
                    k--
                }
                for (j in 0 until c) o.setOrThrow(key(j.toLong()), args[j])
            }
            setLen(o, n + c)
            (n + c).toDouble()
        }
        val values = proto.method(realm, "values", 0) { f, t, _, _ -> ArrayIteratorObject(f.realm.arrayIteratorPrototype, Ops.toObject(t), 1) }
        realm.arrayProtoValues = values
        proto.defineOwn(JSSymbol.iterator, values, Attr.WC)
        proto.method(realm, "with", 2) { f, t, args, _ ->
            val o = Ops.toObject(t)
            val n = len(o)
            val rel = Ops.toIntegerOrInfinity(args.arg(0))
            val actual = if (rel >= 0) rel else n + rel
            if (actual >= n || actual < 0) rangeErr("Invalid index")
            val a = arrayCreate(f.realm, n)
            var k = 0L
            while (k < n) {
                a.createDataPropertyOrThrow(key(k), if (k.toDouble() == actual) args.arg(1) else o.get(key(k), o))
                k++
            }
            a
        }
        val uns = JSObject(null)
        for (n in listOf("at", "copyWithin", "entries", "fill", "find", "findIndex", "findLast", "findLastIndex", "flat",
            "flatMap", "includes", "keys", "toReversed", "toSorted", "toSpliced", "values")) uns.createDataProperty(n, true)
        proto.defineOwn(JSSymbol.unscopables, uns, Attr.CONFIGURABLE)

        // ---------------- Array iterator
        val aip = JSObject(realm.iteratorPrototype)
        realm.arrayIteratorPrototype = aip
        val next = aip.method(realm, "next", 0) { f, t, _, _ ->
            val it = t as? ArrayIteratorObject ?: typeErr("next method called on incompatible receiver")
            val o = it.iterated
            if (o == null) Iteration.createIterResult(f.realm, Undefined, true)
            else {
                val idx = it.index
                val n = if (o is TypedArrayLike) {
                    if (o.isOutOfBounds()) typeErr("Cannot perform %ArrayIteratorPrototype%.next on a detached or out-of-bounds TypedArray")
                    o.typedLength()
                } else len(o)
                if (idx >= n) {
                    it.iterated = null
                    Iteration.createIterResult(f.realm, Undefined, true)
                } else {
                    it.index = idx + 1
                    when (it.kind) {
                        0 -> Iteration.createIterResult(f.realm, idx.toDouble(), false)
                        1 -> Iteration.createIterResult(f.realm, o.get(key(idx), o), false)
                        else -> Iteration.createIterResult(f.realm, Builtins.arrayOf(f.realm, listOf(idx.toDouble(), o.get(key(idx), o))), false)
                    }
                }
            }
        }
        realm.arrayIteratorNext = next
        aip.value(JSSymbol.toStringTag, "Array Iterator", Attr.CONFIGURABLE)
    }

    private fun startIndex(v: Any?, n: Long): Long {
        val fi = Ops.toIntegerOrInfinity(v)
        if (fi == Double.POSITIVE_INFINITY) return n
        if (fi == Double.NEGATIVE_INFINITY) return 0
        return if (fi >= 0) fi.toLong() else maxOf(n + fi.toLong(), 0)
    }

    private fun flatten(target: JSObject, src: JSObject, srcLen: Long, start0: Long, depth: Double, mapper: JSObject?, thisArg: Any?): Long {
        var targetIndex = start0
        var si = 0L
        while (si < srcLen) {
            val pk = key(si)
            if (src.hasProperty(pk)) {
                var el = src.get(pk, src)
                if (mapper != null) el = mapper.call(thisArg, arrayOf(el, si.toDouble(), src))
                var shouldFlatten = false
                if (depth > 0) shouldFlatten = Ops.isArray(el)
                if (shouldFlatten) {
                    el as JSObject
                    targetIndex = flatten(target, el, len(el), targetIndex, if (depth == Double.POSITIVE_INFINITY) depth else depth - 1, null, null)
                } else {
                    if (targetIndex >= MAX_SAFE) typeErr("Invalid array length")
                    target.createDataPropertyOrThrow(key(targetIndex), el)
                    targetIndex++
                }
            }
            si++
        }
        return targetIndex
    }

    fun collectItems(o: JSObject, n: Long, skipHoles: Boolean): ArrayList<Any?> {
        val items = ArrayList<Any?>()
        var k = 0L
        while (k < n) {
            val pk = key(k)
            if (!skipHoles || o.hasProperty(pk)) items.add(o.get(pk, o))
            k++
        }
        return items
    }

    /** SortCompare */
    fun sortCompare(x: Any?, y: Any?, cmp: Any?): Int {
        if (x === Undefined && y === Undefined) return 0
        if (x === Undefined) return 1
        if (y === Undefined) return -1
        if (cmp !== Undefined) {
            val v = Ops.toNumber((cmp as JSObject).call(Undefined, arrayOf(x, y)))
            return if (v.isNaN()) 0 else if (v < 0) -1 else if (v > 0) 1 else 0
        }
        val xs = Ops.toString(x)
        val ys = Ops.toString(y)
        return xs.compareTo(ys).let { if (it < 0) -1 else if (it > 0) 1 else 0 }
    }

    /** Stable merge sort tolerant of inconsistent comparators. */
    fun mergeSort(items: List<Any?>, cmp: Any?): List<Any?> {
        val n = items.size
        if (n < 2) return items
        var src = items.toTypedArray()
        var dst = arrayOfNulls<Any?>(n)
        // insertion sort runs of 8
        val run = 8
        var i = 0
        while (i < n) {
            val end = minOf(i + run, n)
            for (j in i + 1 until end) {
                val v = src[j]
                var k = j - 1
                while (k >= i && sortCompare(src[k], v, cmp) > 0) {
                    src[k + 1] = src[k]
                    k--
                }
                src[k + 1] = v
            }
            i = end
        }
        var width = run
        while (width < n) {
            var lo = 0
            while (lo < n) {
                val mid = minOf(lo + width, n)
                val hi = minOf(lo + 2 * width, n)
                var a = lo
                var b = mid
                var o = lo
                while (a < mid && b < hi) {
                    if (sortCompare(src[b], src[a], cmp) < 0) dst[o++] = src[b++] else dst[o++] = src[a++]
                }
                while (a < mid) dst[o++] = src[a++]
                while (b < hi) dst[o++] = src[b++]
                lo = hi
            }
            val t = src
            src = dst
            dst = t
            width *= 2
        }
        return src.asList()
    }

    /** Array.from */
    fun from(realm: Realm, c: Any?, items: Any?, mapfn: Any?, thisArg: Any?): Any? {
        val mapping = mapfn !== Undefined
        if (mapping && !Ops.isCallable(mapfn)) typeErr("${Ops.describe(mapfn)} is not a function")
        val usingIterator = Ops.getMethod(items, JSSymbol.iterator)
        if (usingIterator !== Undefined) {
            val a: JSObject = if (Ops.isConstructor(c)) (c as JSObject).construct(EMPTY_ARGS, c) as JSObject else arrayCreate(realm, 0)
            val rec = Iteration.fromMethod(items, usingIterator)
            var k = 0L
            while (true) {
                if (k >= MAX_SAFE) {
                    val err = JSException.typeError("Invalid array length")
                    Iteration.closeAndRethrow(rec, err)
                }
                val v = Iteration.stepValue(rec)
                if (v === NotFound) {
                    setLen(a, k)
                    return a
                }
                try {
                    val mv = if (mapping) (mapfn as JSObject).call(thisArg, arrayOf(v, k.toDouble())) else v
                    a.createDataPropertyOrThrow(key(k), mv)
                } catch (t: Throwable) {
                    Iteration.closeAndRethrow(rec, t)
                }
                k++
            }
        }
        val arrayLike = Ops.toObject(items)
        val n = len(arrayLike)
        val a: JSObject = if (Ops.isConstructor(c)) (c as JSObject).construct(arrayOf(n.toDouble()), c) as JSObject else arrayCreate(realm, n)
        var k = 0L
        while (k < n) {
            val v = arrayLike.get(key(k), arrayLike)
            a.createDataPropertyOrThrow(key(k), if (mapping) (mapfn as JSObject).call(thisArg, arrayOf(v, k.toDouble())) else v)
            k++
        }
        setLen(a, n)
        return a
    }
}

/** Implemented by typed arrays so the array iterator can query their length. */
interface TypedArrayLike {
    fun typedLength(): Long
    fun isOutOfBounds(): Boolean
}
