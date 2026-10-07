package io.neonjs.builtins

import io.neonjs.runtime.*
import io.neonjs.vm.*
import java.lang.ref.WeakReference

/**
 * Insertion-ordered hash table with tombstones. Live iterators are tracked weakly and re-indexed on compaction, so
 * iteration observes additions and deletions as the spec requires.
 */
class OrderedTable {
    private val keys = ArrayList<Any?>()
    private val values = ArrayList<Any?>()
    private val index = HashMap<MapKey, Int>()
    private var tombstones = 0
    private val iterators = ArrayList<WeakReference<TableCursor>>()

    private object Tomb

    val size: Int get() = index.size

    fun find(k: Any?): Int = index[MapKey.of(k)] ?: -1

    fun get(k: Any?): Any? {
        val i = find(k)
        return if (i < 0) Undefined else values[i]
    }

    fun has(k: Any?): Boolean = find(k) >= 0

    fun set(k: Any?, v: Any?) {
        val key = if (k is Double && k == 0.0) 0.0 else if (k is CharSequence) k.toString() else k
        val mk = MapKey.of(key)
        val i = index[mk]
        if (i != null) {
            values[i] = v
            return
        }
        index[mk] = keys.size
        keys.add(key)
        values.add(v)
    }

    fun delete(k: Any?): Boolean {
        val mk = MapKey.of(k)
        val i = index.remove(mk) ?: return false
        keys[i] = Tomb
        values[i] = null
        tombstones++
        maybeCompact()
        return true
    }

    fun clear() {
        for (i in keys.indices) {
            if (keys[i] !== Tomb) {
                keys[i] = Tomb
                values[i] = null
                tombstones++
            }
        }
        index.clear()
        maybeCompact(force = true)
    }

    private fun maybeCompact(force: Boolean = false) {
        if (!force && (tombstones < 16 || tombstones * 2 < keys.size)) return
        // map old -> new positions
        val live = IntArray(keys.size + 1)
        var n = 0
        for (i in keys.indices) {
            live[i] = n
            if (keys[i] !== Tomb) n++
        }
        live[keys.size] = n
        val it = iterators.iterator()
        while (it.hasNext()) {
            val c = it.next().get()
            if (c == null) { it.remove(); continue }
            c.pos = live[minOf(c.pos, keys.size)]
        }
        val nk = ArrayList<Any?>(n)
        val nv = ArrayList<Any?>(n)
        for (i in keys.indices) {
            if (keys[i] !== Tomb) {
                nk.add(keys[i])
                nv.add(values[i])
            }
        }
        keys.clear(); keys.addAll(nk)
        values.clear(); values.addAll(nv)
        index.clear()
        for (i in keys.indices) index[MapKey.of(keys[i])] = i
        tombstones = 0
    }

    fun cursor(): TableCursor {
        val c = TableCursor(this)
        iterators.add(WeakReference(c))
        if (iterators.size > 64) iterators.removeIf { it.get() == null }
        return c
    }

    /** Advances [c]; returns false when exhausted. */
    fun advance(c: TableCursor): Boolean {
        while (c.pos < keys.size) {
            val i = c.pos++
            if (keys[i] !== Tomb) {
                c.key = keys[i]
                c.value = values[i]
                return true
            }
        }
        return false
    }

    /** Snapshot-free forEach that tolerates mutation. */
    inline fun forEachLive(f: (Any?, Any?) -> Unit) {
        val c = cursor()
        while (advance(c)) f(c.key, c.value)
    }
}

class TableCursor(@JvmField val table: OrderedTable) {
    @JvmField var pos = 0
    @JvmField var key: Any? = null
    @JvmField var value: Any? = null
}

class JSMapObject(proto: JSObject?, @JvmField val isSet: Boolean) : JSObject(proto) {
    @JvmField val table = OrderedTable()
}

class CollectionIterator(proto: JSObject?, @JvmField var cursor: TableCursor?, @JvmField val kind: Int) : JSObject(proto)

class JSWeakCollection(proto: JSObject?, @JvmField val isSet: Boolean) : JSObject(proto) {
    @JvmField val map = java.util.WeakHashMap<Any, Any?>()
}

class JSWeakRef(proto: JSObject?, target: Any) : JSObject(proto) {
    @JvmField val ref = WeakReference(target)
}

class JSFinalizationRegistry(proto: JSObject?, @JvmField val cleanup: JSObject, @JvmField val realm: Realm) : JSObject(proto) {
    class Cell(target: Any, val held: Any?, val token: Any?, q: java.lang.ref.ReferenceQueue<Any>, val owner: JSFinalizationRegistry) : java.lang.ref.WeakReference<Any>(target, q)
    val cells = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Cell, Boolean>())
    val queue = java.lang.ref.ReferenceQueue<Any>()
}

internal object CollectionBuiltins {
    fun canBeHeldWeakly(v: Any?): Boolean = v is JSObject || (v is JSSymbol && v.registryKey == null)

    fun install(realm: Realm) {
        installMapSet(realm, false)
        installMapSet(realm, true)
        installWeak(realm, false)
        installWeak(realm, true)
        installWeakRef(realm)
        installFinalization(realm)
    }

    private fun installMapSet(realm: Realm, isSet: Boolean) {
        val name = if (isSet) "Set" else "Map"
        val proto = JSObject(realm.objectPrototype)
        realm.intrinsics["%$name.prototype%"] = proto
        val ctor = makeCtor(realm, name, 0, proto) { f, _, args, nt ->
            if (nt == null) typeErr("Constructor $name requires 'new'")
            val o = JSMapObject(Ops.getPrototypeFromConstructor(nt) { it.intrinsic("%$name.prototype%") }, isSet)
            val iterable = args.arg(0)
            if (iterable !== Undefined && iterable !== Null) {
                val adder = o.get(if (isSet) "add" else "set", o)
                if (!Ops.isCallable(adder)) typeErr("'${if (isSet) "add" else "set"}' returned for property is not a function")
                val rec = Iteration.getIterator(f.realm, iterable, false)
                while (true) {
                    val next = Iteration.stepValue(rec)
                    if (next === NotFound) break
                    try {
                        if (isSet) (adder as JSObject).call(o, arrayOf(next))
                        else {
                            if (next !is JSObject) typeErr("Iterator value ${Ops.toDisplayString(next)} is not an entry object")
                            val k = next.get(0, next)
                            val v = next.get(1, next)
                            (adder as JSObject).call(o, arrayOf(k, v))
                        }
                    } catch (t: Throwable) {
                        Iteration.closeAndRethrow(rec, t)
                    }
                }
            }
            o
        }
        realm.intrinsics["%$name%"] = ctor
        realm.global(name, ctor)
        ctor.getter(realm, JSSymbol.species) { _, t, _, _ -> t }
        fun thisMap(t: Any?, m: String): JSMapObject {
            if (t is JSMapObject && t.isSet == isSet) return t
            typeErr("Method $name.prototype.$m called on incompatible receiver ${Ops.describe(t)}")
        }
        proto.method(realm, "clear", 0) { _, t, _, _ -> thisMap(t, "clear").table.clear(); Undefined }
        proto.method(realm, "delete", 1) { _, t, args, _ -> thisMap(t, "delete").table.delete(args.arg(0)) }
        proto.method(realm, "has", 1) { _, t, args, _ -> thisMap(t, "has").table.has(args.arg(0)) }
        proto.method(realm, "forEach", 1) { _, t, args, _ ->
            val m = thisMap(t, "forEach")
            val cb = callable(args.arg(0), "forEach")
            m.table.forEachLive { k, v -> cb.call(args.arg(1), arrayOf(if (isSet) k else v, k, m)) }
            Undefined
        }
        proto.getter(realm, "size") { _, t, _, _ -> thisMap(t, "size").table.size.toDouble() }
        val iterProto = JSObject(realm.iteratorPrototype)
        iterProto.method(realm, "next", 0) { f, t, _, _ ->
            val it = t as? CollectionIterator ?: typeErr("next method called on incompatible receiver")
            val c = it.cursor
            if (c == null || !c.table.advance(c)) {
                it.cursor = null
                Iteration.createIterResult(f.realm, Undefined, true)
            } else {
                val v: Any? = when (it.kind) {
                    0 -> c.key
                    1 -> c.value
                    else -> Builtins.arrayOf(f.realm, listOf(c.key, c.value))
                }
                Iteration.createIterResult(f.realm, v, false)
            }
        }
        iterProto.value(JSSymbol.toStringTag, "$name Iterator", Attr.CONFIGURABLE)
        if (isSet) {
            proto.method(realm, "add", 1) { _, t, args, _ ->
                val m = thisMap(t, "add")
                val v = args.arg(0)
                if (!m.table.has(v)) m.table.set(v, v)
                m
            }
            val values = proto.method(realm, "values", 0) { _, t, _, _ -> CollectionIterator(iterProto, thisMap(t, "values").table.cursor(), 0) }
            proto.defineOwn("keys", values, Attr.WC)
            proto.defineOwn(JSSymbol.iterator, values, Attr.WC)
            proto.method(realm, "entries", 0) { _, t, _, _ -> CollectionIterator(iterProto, thisMap(t, "entries").table.cursor(), 2) }
            SetMethods.install(realm, proto)
        } else {
            proto.method(realm, "get", 1) { _, t, args, _ -> thisMap(t, "get").table.get(args.arg(0)) }
            proto.method(realm, "set", 2) { _, t, args, _ ->
                val m = thisMap(t, "set")
                m.table.set(args.arg(0), args.arg(1))
                m
            }
            proto.method(realm, "getOrInsert", 2) { _, t, args, _ ->
                val m = thisMap(t, "getOrInsert")
                val k = args.arg(0)
                if (!m.table.has(k)) m.table.set(k, args.arg(1))
                m.table.get(k)
            }
            proto.method(realm, "getOrInsertComputed", 2) { _, t, args, _ ->
                val m = thisMap(t, "getOrInsertComputed")
                val cb = callable(args.arg(1), "getOrInsertComputed")
                var k = args.arg(0)
                if (k is Double && k == 0.0) k = 0.0
                if (m.table.has(k)) m.table.get(k)
                else {
                    val v = cb.call(Undefined, arrayOf(k))
                    m.table.set(k, v)
                    v
                }
            }
            proto.method(realm, "keys", 0) { _, t, _, _ -> CollectionIterator(iterProto, thisMap(t, "keys").table.cursor(), 0) }
            proto.method(realm, "values", 0) { _, t, _, _ -> CollectionIterator(iterProto, thisMap(t, "values").table.cursor(), 1) }
            val entries = proto.method(realm, "entries", 0) { _, t, _, _ -> CollectionIterator(iterProto, thisMap(t, "entries").table.cursor(), 2) }
            proto.defineOwn(JSSymbol.iterator, entries, Attr.WC)
            ctor.method(realm, "groupBy", 2) { f, _, args, _ ->
                val groups = ObjectBuiltins.groupBy(f.realm, args.arg(0), args.arg(1), false)
                val m = JSMapObject(f.realm.intrinsic("%Map.prototype%"), false)
                for ((k, v) in groups) m.table.set(if (k is MapKey) k.value else k, Builtins.arrayOf(f.realm, v))
                m
            }
        }
        proto.value(JSSymbol.toStringTag, name, Attr.CONFIGURABLE)
    }

    private fun installWeak(realm: Realm, isSet: Boolean) {
        val name = if (isSet) "WeakSet" else "WeakMap"
        val proto = JSObject(realm.objectPrototype)
        realm.intrinsics["%$name.prototype%"] = proto
        val ctor = makeCtor(realm, name, 0, proto) { f, _, args, nt ->
            if (nt == null) typeErr("Constructor $name requires 'new'")
            val o = JSWeakCollection(Ops.getPrototypeFromConstructor(nt) { it.intrinsic("%$name.prototype%") }, isSet)
            val iterable = args.arg(0)
            if (iterable !== Undefined && iterable !== Null) {
                val adder = o.get(if (isSet) "add" else "set", o)
                if (!Ops.isCallable(adder)) typeErr("adder is not a function")
                val rec = Iteration.getIterator(f.realm, iterable, false)
                while (true) {
                    val next = Iteration.stepValue(rec)
                    if (next === NotFound) break
                    try {
                        if (isSet) (adder as JSObject).call(o, arrayOf(next))
                        else {
                            if (next !is JSObject) typeErr("Iterator value is not an entry object")
                            (adder as JSObject).call(o, arrayOf(next.get(0, next), next.get(1, next)))
                        }
                    } catch (t: Throwable) {
                        Iteration.closeAndRethrow(rec, t)
                    }
                }
            }
            o
        }
        realm.global(name, ctor)
        fun thisW(t: Any?, m: String): JSWeakCollection {
            if (t is JSWeakCollection && t.isSet == isSet) return t
            typeErr("Method $name.prototype.$m called on incompatible receiver ${Ops.describe(t)}")
        }
        proto.method(realm, "delete", 1) { _, t, args, _ ->
            val w = thisW(t, "delete")
            val k = args.arg(0)
            if (!canBeHeldWeakly(k)) false else removeKey(w, k)
        }
        proto.method(realm, "has", 1) { _, t, args, _ ->
            val w = thisW(t, "has")
            val k = args.arg(0)
            canBeHeldWeakly(k) && w.map.containsKey(k)
        }
        if (isSet) {
            proto.method(realm, "add", 1) { _, t, args, _ ->
                val w = thisW(t, "add")
                val k = args.arg(0)
                if (!canBeHeldWeakly(k)) typeErr("Invalid value used in weak set")
                w.map[k!!] = true
                w
            }
        } else {
            proto.method(realm, "get", 1) { _, t, args, _ ->
                val w = thisW(t, "get")
                val k = args.arg(0)
                if (!canBeHeldWeakly(k) || !w.map.containsKey(k)) Undefined else w.map[k]
            }
            proto.method(realm, "set", 2) { _, t, args, _ ->
                val w = thisW(t, "set")
                val k = args.arg(0)
                if (!canBeHeldWeakly(k)) typeErr("Invalid value used as weak map key")
                w.map[k!!] = args.arg(1)
                w
            }
            proto.method(realm, "getOrInsert", 2) { _, t, args, _ ->
                val w = thisW(t, "getOrInsert")
                val k = args.arg(0)
                if (!canBeHeldWeakly(k)) typeErr("Invalid value used as weak map key")
                if (!w.map.containsKey(k)) w.map[k!!] = args.arg(1)
                w.map[k]
            }
            proto.method(realm, "getOrInsertComputed", 2) { _, t, args, _ ->
                val w = thisW(t, "getOrInsertComputed")
                val k = args.arg(0)
                if (!canBeHeldWeakly(k)) typeErr("Invalid value used as weak map key")
                val cb = callable(args.arg(1), "getOrInsertComputed")
                if (w.map.containsKey(k)) w.map[k]
                else {
                    val v = cb.call(Undefined, arrayOf(k))
                    w.map[k!!] = v
                    v
                }
            }
        }
        proto.value(JSSymbol.toStringTag, name, Attr.CONFIGURABLE)
    }

    private fun removeKey(w: JSWeakCollection, k: Any?): Boolean {
        if (k == null) return false
        if (!w.map.containsKey(k)) return false
        w.map.remove(k)
        return true
    }

    private fun installWeakRef(realm: Realm) {
        val proto = JSObject(realm.objectPrototype)
        realm.intrinsics["%WeakRef.prototype%"] = proto
        val ctor = makeCtor(realm, "WeakRef", 1, proto) { f, _, args, nt ->
            if (nt == null) typeErr("Constructor WeakRef requires 'new'")
            val target = args.arg(0)
            if (!canBeHeldWeakly(target)) typeErr("WeakRef: target must be an object or non-registered symbol")
            val o = JSWeakRef(Ops.getPrototypeFromConstructor(nt) { it.intrinsic("%WeakRef.prototype%") }, target!!)
            f.realm.agent.keptAlive.add(target)
            o
        }
        realm.global("WeakRef", ctor)
        proto.method(realm, "deref", 0) { f, t, _, _ ->
            val w = t as? JSWeakRef ?: typeErr("WeakRef.prototype.deref called on incompatible receiver")
            val v = w.ref.get()
            if (v == null) Undefined else {
                f.realm.agent.keptAlive.add(v)
                v
            }
        }
        proto.value(JSSymbol.toStringTag, "WeakRef", Attr.CONFIGURABLE)
    }

    private fun installFinalization(realm: Realm) {
        val proto = JSObject(realm.objectPrototype)
        realm.intrinsics["%FinalizationRegistry.prototype%"] = proto
        val ctor = makeCtor(realm, "FinalizationRegistry", 1, proto) { f, _, args, nt ->
            if (nt == null) typeErr("Constructor FinalizationRegistry requires 'new'")
            val cb = args.arg(0)
            if (!Ops.isCallable(cb)) typeErr("FinalizationRegistry: cleanup must be callable")
            JSFinalizationRegistry(Ops.getPrototypeFromConstructor(nt) { it.intrinsic("%FinalizationRegistry.prototype%") }, cb as JSObject, f.realm)
        }
        realm.global("FinalizationRegistry", ctor)
        fun thisFR(t: Any?): JSFinalizationRegistry = t as? JSFinalizationRegistry ?: typeErr("FinalizationRegistry method called on incompatible receiver")
        proto.method(realm, "register", 2) { _, t, args, _ ->
            val fr = thisFR(t)
            val target = args.arg(0)
            if (!canBeHeldWeakly(target)) typeErr("FinalizationRegistry.prototype.register: invalid target")
            val held = args.arg(1)
            if (Ops.sameValue(target, held)) typeErr("FinalizationRegistry.prototype.register: target and holdings must not be same")
            val token = args.arg(2)
            if (!canBeHeldWeakly(token) && token !== Undefined) typeErr("FinalizationRegistry.prototype.register: invalid unregister token")
            fr.cells.add(JSFinalizationRegistry.Cell(target!!, held, if (token === Undefined) null else token, fr.queue, fr))
            Undefined
        }
        proto.method(realm, "unregister", 1) { _, t, args, _ ->
            val fr = thisFR(t)
            val token = args.arg(0)
            if (!canBeHeldWeakly(token)) typeErr("Invalid unregisterToken")
            var removed = false
            val it = fr.cells.iterator()
            while (it.hasNext()) {
                val c = it.next()
                if (c.token === token) {
                    it.remove()
                    removed = true
                }
            }
            removed
        }
        proto.method(realm, "cleanupSome", 0) { _, t, args, _ ->
            val fr = thisFR(t)
            val cb = args.arg(0)
            if (cb !== Undefined && !Ops.isCallable(cb)) typeErr("cleanupSome callback must be callable")
            cleanup(fr, if (cb === Undefined) fr.cleanup else cb as JSObject)
            Undefined
        }
        proto.value(JSSymbol.toStringTag, "FinalizationRegistry", Attr.CONFIGURABLE)
    }

    fun cleanup(fr: JSFinalizationRegistry, cb: JSObject) {
        while (true) {
            val r = fr.queue.poll() ?: break
            val c = r as JSFinalizationRegistry.Cell
            if (fr.cells.remove(c)) cb.call(Undefined, arrayOf(c.held))
        }
    }
}

/** Set methods (union, intersection, ...). */
internal object SetMethods {
    class SetRecord(val obj: JSObject, val size: Double, val has: JSObject, val keys: JSObject)

    fun getSetRecord(v: Any?): SetRecord {
        if (v !is JSObject) typeErr("Set operation argument must be an object")
        val rawSize = v.get("size", v)
        val numSize = Ops.toNumber(rawSize)
        if (numSize.isNaN()) typeErr("The 'size' property must be a number")
        val intSize = Ops.integerPart(numSize)
        if (intSize < 0) rangeErr("The 'size' property must not be negative")
        val has = v.get("has", v)
        if (!Ops.isCallable(has)) typeErr("The 'has' property must be callable")
        val keys = v.get("keys", v)
        if (!Ops.isCallable(keys)) typeErr("The 'keys' property must be callable")
        return SetRecord(v, intSize, has as JSObject, keys as JSObject)
    }

    private fun keysIter(r: SetRecord): IteratorRecord {
        val it = r.keys.call(r.obj, EMPTY_ARGS)
        if (it !is JSObject) typeErr("keys() result is not an object")
        return IteratorRecord(it, it.get("next", it))
    }

    private fun thisSet(t: Any?): JSMapObject = (t as? JSMapObject)?.takeIf { it.isSet } ?: typeErr("Set method called on incompatible receiver")

    private fun newSet(realm: Realm): JSMapObject = JSMapObject(realm.intrinsic("%Set.prototype%"), true)

    private fun copy(realm: Realm, s: JSMapObject): JSMapObject {
        val r = newSet(realm)
        s.table.forEachLive { k, _ -> r.table.set(k, k) }
        return r
    }

    private fun canon(v: Any?): Any? = if (v is Double && v == 0.0) 0.0 else v

    fun install(realm: Realm, proto: JSObject) {
        proto.method(realm, "union", 1) { f, t, args, _ ->
            val s = thisSet(t)
            val other = getSetRecord(args.arg(0))
            val it = keysIter(other)
            val r = copy(f.realm, s)
            while (true) {
                val v = Iteration.stepValue(it)
                if (v === NotFound) break
                val c = canon(v)
                if (!r.table.has(c)) r.table.set(c, c)
            }
            r
        }
        proto.method(realm, "intersection", 1) { f, t, args, _ ->
            val s = thisSet(t)
            val other = getSetRecord(args.arg(0))
            val r = newSet(f.realm)
            if (s.table.size <= other.size) {
                // live iteration over [[SetData]]: elements removed and re-added are visited again (spec index loop)
                val cur = s.table.cursor()
                while (s.table.advance(cur)) {
                    val e = cur.key
                    if (Ops.toBoolean(other.has.call(other.obj, arrayOf(e)))) if (!r.table.has(e)) r.table.set(e, e)
                }
            } else {
                val it = keysIter(other)
                while (true) {
                    val v = Iteration.stepValue(it)
                    if (v === NotFound) break
                    val c = canon(v)
                    if (s.table.has(c) && !r.table.has(c)) r.table.set(c, c)
                }
            }
            r
        }
        proto.method(realm, "difference", 1) { f, t, args, _ ->
            val s = thisSet(t)
            val other = getSetRecord(args.arg(0))
            val r = copy(f.realm, s)
            if (s.table.size <= other.size) {
                // iterates the result copy (not the live receiver), removing elements found in other
                val cur = r.table.cursor()
                while (r.table.advance(cur)) {
                    val e = cur.key
                    if (Ops.toBoolean(other.has.call(other.obj, arrayOf(e)))) r.table.delete(e)
                }
            } else {
                val it = keysIter(other)
                while (true) {
                    val v = Iteration.stepValue(it)
                    if (v === NotFound) break
                    r.table.delete(canon(v))
                }
            }
            r
        }
        proto.method(realm, "symmetricDifference", 1) { f, t, args, _ ->
            val s = thisSet(t)
            val other = getSetRecord(args.arg(0))
            val it = keysIter(other)
            val r = copy(f.realm, s)
            while (true) {
                val v = Iteration.stepValue(it)
                if (v === NotFound) break
                val c = canon(v)
                if (s.table.has(c)) r.table.delete(c) else if (!r.table.has(c)) r.table.set(c, c)
            }
            r
        }
        proto.method(realm, "isSubsetOf", 1) { _, t, args, _ ->
            val s = thisSet(t)
            val other = getSetRecord(args.arg(0))
            if (s.table.size > other.size) false
            else {
                var r = true
                // live iteration over [[SetData]]: elements removed and re-added are visited again (spec index loop)
                val cur = s.table.cursor()
                while (s.table.advance(cur)) {
                    val e = cur.key
                    if (!Ops.toBoolean(other.has.call(other.obj, arrayOf(e)))) { r = false; break }
                }
                r
            }
        }
        proto.method(realm, "isSupersetOf", 1) { _, t, args, _ ->
            val s = thisSet(t)
            val other = getSetRecord(args.arg(0))
            if (s.table.size < other.size) false
            else {
                val it = keysIter(other)
                var r = true
                while (true) {
                    val v = Iteration.stepValue(it)
                    if (v === NotFound) break
                    if (!s.table.has(v)) {
                        Iteration.closeNormal(it)
                        r = false
                        break
                    }
                }
                r
            }
        }
        proto.method(realm, "isDisjointFrom", 1) { _, t, args, _ ->
            val s = thisSet(t)
            val other = getSetRecord(args.arg(0))
            var r = true
            if (s.table.size <= other.size) {
                // live iteration over [[SetData]]: elements removed and re-added are visited again (spec index loop)
                val cur = s.table.cursor()
                while (s.table.advance(cur)) {
                    val e = cur.key
                    if (Ops.toBoolean(other.has.call(other.obj, arrayOf(e)))) { r = false; break }
                }
            } else {
                val it = keysIter(other)
                while (true) {
                    val v = Iteration.stepValue(it)
                    if (v === NotFound) break
                    if (s.table.has(v)) {
                        Iteration.closeNormal(it)
                        r = false
                        break
                    }
                }
            }
            r
        }
    }
}
