package dev.mooner.neonpython

import java.math.BigInteger

/**
 * Hashes as CPython computes them (Py_hash_t, 64 bits), where they are not randomized: ints (modulo 2**61 - 1), floats
 * (_Py_HashDouble), None, tuples (xxHash), frozensets, slices, complex numbers. Sets lay their elements out by them,
 * so that they iterate, and print, as CPython's do; a str's is the JVM's (CPython randomizes them: no order to keep).
 */
object PyHash {
    private const val MODULUS = (1L shl 61) - 1
    private const val BITS = 61
    private const val INF = 314159L
    private const val XXPRIME_1 = -7046029288634856825L  // 11400714785074694791
    private const val XXPRIME_2 = -4417276706812531889L  // 14029467366897019727
    private const val XXPRIME_5 = 2870177450012600261L
    @JvmField val NONE = 4238894112L

    private fun fix(h: Long) = if (h == -1L) -2L else h

    @JvmStatic
    fun int(v: BigInteger): Long {
        val m = v.abs().mod(BigInteger.valueOf(MODULUS)).toLong()
        return fix(if (v.signum() < 0) -m else m)
    }

    @JvmStatic
    fun int(v: Long): Long {
        if (v == Long.MIN_VALUE) return int(BigInteger.valueOf(v))
        val m = Math.abs(v) % MODULUS
        return fix(if (v < 0) -m else m)
    }

    /** _Py_HashDouble: an integral float as its int, the others from their exact binary value modulo 2**61 - 1. */
    @JvmStatic
    fun float(v: Double, inst: Any?): Long {
        if (v.isInfinite()) return if (v > 0) INF else -INF
        if (v.isNaN()) return System.identityHashCode(inst).toLong()
        if (v == 0.0) return 0L
        val exp0 = Math.getExponent(v)
        // frexp: v = m * 2**e, 0.5 <= |m| < 1
        var e = if (exp0 == java.lang.Double.MIN_EXPONENT - 1) Math.getExponent(v * 9007199254740992.0) - 53 + 1 else exp0 + 1
        var m = v / Math.scalb(1.0, e)
        var sign = 1L
        if (m < 0) {
            sign = -1L
            m = -m
        }
        var x = 0L
        while (m != 0.0) {
            x = ((x shl 28) and MODULUS) or (x ushr (BITS - 28))
            m *= 268435456.0
            e -= 28
            val y = m.toLong()
            m -= y.toDouble()
            x += y
            if (java.lang.Long.compareUnsigned(x, MODULUS) >= 0) x -= MODULUS
        }
        e = if (e >= 0) e % BITS else BITS - 1 - ((-1 - e) % BITS)
        x = ((x shl e) and MODULUS) or (x ushr (BITS - e))
        x *= sign
        return fix(x)
    }

    /** A tuple's (xxHash's steps over its items' hashes, as CPython 3.8 on). */
    @JvmStatic
    fun tuple(items: Array<Any?>): Long {
        var acc = XXPRIME_5
        for (it in items) {
            acc += hash(it) * XXPRIME_2
            acc = (acc shl 31) or (acc ushr 33)
            acc *= XXPRIME_1
        }
        acc += items.size.toLong() xor (XXPRIME_5 xor 3527539L)
        return if (acc == -1L) 1546275796L else acc
    }

    /** hash(v), the 64 bits CPython gives; a TypeError where it has none. */
    @JvmStatic
    fun hash(v: Any?): Long = when {
        v == null || v === PyNone -> NONE
        v is PyBool -> if (v.value) 1L else 0L
        v is Long -> int(v)
        v is BigInteger -> int(v)
        v is Double -> float(v, v)
        PyRt.special(v) -> special(v!!)
        v is PyStr -> v.s.hashCode().toLong().let(::fix)
        v is PyTuple -> tuple(v.items)
        v is PySet -> if (v.frozen) v.frozenHash() else throw PyRt.typeError("unhashable type: '${PyTypes.name(v)}'")
        v is PyList || v is PyDict -> throw PyRt.typeError("unhashable type: '${PyTypes.name(v)}'")
        v is PySlice -> tuple(arrayOf(v.start, v.stop, v.step))
        v is PyRange -> PyRanges.hash(v)
        v is PyComplex -> fix(float(v.real, v) + 1000003L * float(v.imag, v))
        v is PyBytes -> fix(v.b.contentHashCode().toLong())
        v is PyByteArray -> throw PyRt.typeError("unhashable type: 'bytearray'")
        v is PyMemoryView -> PyByteSeqs.viewHash(v)
        v is PyGenericAlias || v is PyUnion || v is PyParamSpecPart -> v.hashCode().toLong()
        else -> System.identityHashCode(v).toLong()
    }

    /** A builtin value's own hash, a subclass's instance's too (str.__hash__, tuple.__hash__, int.__hash__): what hash() gives it. */
    @JvmStatic
    fun valueHash(v: Any?): Long = when (v) {
        is PyStr -> fix(v.s.hashCode().toLong())
        is PyTuple -> tuple(v.items)
        is PyNumSub -> hash(v.num)
        else -> hash(v)
    }

    /** An instance's: its class's __hash__ (None: unhashable), else its base type's or its identity. */
    private fun special(v: Any): Long {
        val t = PyTypes.typeOf(v)
        val h = t.lookup("__hash__")
        if (h === PyNone) throw PyRt.typeError("unhashable type: '${t.tpName ?: t.name}'")
        if (h == null || h is PyBuiltin && !t.dispatchBuiltins) return when (v) {
            is PyStr -> fix(v.s.hashCode().toLong())
            is PyTuple -> tuple(v.items)
            is PySet -> if (v.frozen) v.frozenHash() else throw PyRt.typeError("unhashable type: '${t.tpName ?: t.name}'")
            // (an int's or a float's subclass: its number's)
            is PyNumSub -> hash(v.num)
            is PyValue -> throw PyRt.typeError("unhashable type: '${t.tpName ?: t.name}'")
            else -> System.identityHashCode(v).toLong()
        }
        return when (val r = PyCalls.callWith(PyContext.current(), PyClasses.descrGet(null, h, v, t), arrayOfNulls(0), arrayOfNulls(0), arrayOfNulls(0))) {
            // (slot_tp_hash: a value in a hash's range as it is, so hash(x) == hash(y) where x.__hash__ gives hash(y);
            // a larger int hashed as an int is)
            is Long -> fix(r)
            is BigInteger -> int(r)
            is PyBool -> if (r.value) 1L else 0L
            else -> throw PyRt.typeError("__hash__ method should return an integer")
        }
    }
}

/**
 * A set or a frozenset: CPython's table (setobject.c), open addressing with its probes (linear, then perturbed), its
 * dummies where elements were removed and its growth, so that elements lie, and iterate, where CPython's do.
 */
class PySet(@JvmField val frozen: Boolean) : PyValue() {
    @JvmField var keys: Array<Any?> = arrayOfNulls(MIN_SIZE)
    @JvmField var hashes: LongArray = LongArray(MIN_SIZE)
    @JvmField var mask = MIN_SIZE - 1
    @JvmField var fill = 0
    @JvmField var used = 0
    /** Where pop() looks first. */
    @JvmField var finger = 0
    private var hashCache = -1L

    val size: Int get() = used

    private fun eqKey(a: Any?, b: Any?) = a === b || PyRt.eq(a, b)

    /** The slot of [key] (hashed [hash]), or -1. */
    fun find(key: Any?, hash: Long): Int {
        var i = (hash and mask.toLong()).toInt()
        var perturb = hash
        while (true) {
            var probes = if (i + LINEAR_PROBES <= mask) LINEAR_PROBES else 0
            var j = i
            while (true) {
                val k = keys[j]
                if (k == null) return -1
                if (k !== DUMMY && hashes[j] == hash) {
                    if (k === key) return j
                    val n = used
                    val same = PyRt.eq(k, key)
                    if (n != used || keys[j] !== k) throw PyException("RuntimeError", "set changed size during iteration")
                    if (same) return j
                }
                if (probes-- == 0) break
                j++
            }
            perturb = perturb ushr PERTURB_SHIFT
            i = ((i.toLong() * 5 + 1 + perturb) and mask.toLong()).toInt()
        }
    }

    /** set_add_entry: [key] added (a dummy before an unused slot taken), the table grown past 3/5 full. */
    fun add(key: Any?, hash: Long) {
        var i = (hash and mask.toLong()).toInt()
        var perturb = hash
        var free = -1
        while (true) {
            var probes = if (i + LINEAR_PROBES <= mask) LINEAR_PROBES else 0
            var j = i
            while (true) {
                val k = keys[j]
                if (k == null) {
                    if (free >= 0) {
                        keys[free] = key
                        hashes[free] = hash
                        used++
                        return
                    }
                    keys[j] = key
                    hashes[j] = hash
                    fill++
                    used++
                    if (fill.toLong() * 5 >= mask.toLong() * 3) resize(if (used > 50000) used * 2 else used * 4)
                    return
                }
                if (k === DUMMY) {
                    if (free < 0) free = j
                } else if (hashes[j] == hash && eqKey(k, key)) return
                if (probes-- == 0) break
                j++
            }
            perturb = perturb ushr PERTURB_SHIFT
            i = ((i.toLong() * 5 + 1 + perturb) and mask.toLong()).toInt()
        }
    }

    /** set_insert_clean: into a table without dummies or this key. */
    private fun insertClean(ks: Array<Any?>, hs: LongArray, m: Int, key: Any?, hash: Long) {
        var i = (hash and m.toLong()).toInt()
        var perturb = hash
        while (true) {
            var probes = if (i + LINEAR_PROBES <= m) LINEAR_PROBES else 0
            var j = i
            while (true) {
                if (ks[j] == null) {
                    ks[j] = key
                    hs[j] = hash
                    return
                }
                if (probes-- == 0) break
                j++
            }
            perturb = perturb ushr PERTURB_SHIFT
            i = ((i.toLong() * 5 + 1 + perturb) and m.toLong()).toInt()
        }
    }

    /** set_table_resize: a table of the first power of two above [minUsed], the elements put in again in slot order. */
    fun resize(minUsed: Int) {
        var n = MIN_SIZE
        while (n <= minUsed) n = n shl 1
        val ks = arrayOfNulls<Any?>(n)
        val hs = LongArray(n)
        for (j in keys.indices) {
            val k = keys[j]
            if (k != null && k !== DUMMY) insertClean(ks, hs, n - 1, k, hashes[j])
        }
        keys = ks
        hashes = hs
        mask = n - 1
        fill = used
    }

    /** [key] removed (a dummy left where it was); whether it was there. */
    fun discard(key: Any?, hash: Long): Boolean {
        val j = find(key, hash)
        if (j < 0) return false
        keys[j] = DUMMY
        hashes[j] = -1L
        used--
        return true
    }

    /** After discarding many: the table without its dummies, where they are more than a quarter of it. */
    fun shrinkDummies() {
        if (fill - used > mask / 4) resize(if (used > 50000) used * 2 else used * 4)
    }

    fun clear() {
        keys = arrayOfNulls(MIN_SIZE)
        hashes = LongArray(MIN_SIZE)
        mask = MIN_SIZE - 1
        fill = 0
        used = 0
        finger = 0
    }

    /** set.pop(): the element at or after the finger. */
    fun pop(): Any? {
        if (used == 0) throw PyException.keyError(PyStr("pop from an empty set"))
        var j = finger and mask
        while (keys[j] == null || keys[j] === DUMMY) j = (j + 1) and mask
        val k = keys[j]
        keys[j] = DUMMY
        hashes[j] = -1L
        used--
        finger = j + 1
        return k
    }

    /** The elements, in slot order (as CPython's iterate). */
    fun elements(): ArrayList<Any?> {
        val out = ArrayList<Any?>(used)
        for (k in keys) if (k != null && k !== DUMMY) out.add(k)
        return out
    }

    /** Element [key]'s hash, or the TypeError of an element that has none (CPython 3.14's message). */
    fun hashOf(key: Any?): Long = try {
        PyHash.hash(key)
    } catch (e: PyException) {
        if (e.matches(PyExceptions.type("TypeError"))) throw PyRt.typeError("cannot use '${PyTypes.name(key)}' as a set element (${e.message})") else throw e
    }

    fun addKey(key: Any?) = add(key, hashOf(key))

    /** `key in s`: a set looked up as the frozenset of its elements (CPython's set_contains). */
    fun contains(key: Any?): Boolean {
        if (key is PySet && !key.frozen) return find(key, PySets.frozenOf(key).frozenHash()) >= 0
        return find(key, hashOf(key)) >= 0
    }

    /** set_merge: [other]'s elements added in its slot order, the table grown first for them all. */
    fun merge(other: PySet) {
        if (other === this || other.used == 0) return
        if ((fill + other.used).toLong() * 5 >= mask.toLong() * 3) resize((used + other.used) * 2)
        // (into an empty table of the same size, without dummies to drop: the slots as they are)
        if (fill == 0 && mask == other.mask && other.fill == other.used) {
            other.keys.copyInto(keys)
            other.hashes.copyInto(hashes)
            fill = other.fill
            used = other.used
            return
        }
        for (j in other.keys.indices) {
            val k = other.keys[j]
            if (k != null && k !== DUMMY) add(k, other.hashes[j])
        }
    }

    /** frozenset's hash (CPython's: each element's shuffled and combined, the table's dummies and empties factored). */
    fun frozenHash(): Long {
        if (hashCache != -1L) return hashCache
        fun shuffle(h: Long) = ((h xor 89869747L) xor (h shl 16)) * 3644798167L
        var h = 0L
        for (j in keys.indices) if (keys[j] != null && keys[j] !== DUMMY) h = h xor shuffle(hashes[j])
        h = h xor ((used.toLong() + 1) * 1927868237L)
        h = h xor ((h ushr 11) xor (h ushr 25))
        h = h * 69069L + 907133923L
        if (h == -1L) h = 590923713L
        hashCache = h
        return h
    }

    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)

    companion object {
        const val MIN_SIZE = 8
        const val LINEAR_PROBES = 9
        const val PERTURB_SHIFT = 5
        @JvmField val DUMMY = Any()
    }
}

/** The set and frozenset types' operations and methods, as CPython's setobject.c makes them. */
object PySets {
    /** BUILD_SET: a set of the items, added in order. */
    @JvmStatic
    fun buildSet(items: Array<Any?>): Any? {
        val s = PySet(false)
        for (x in items) s.addKey(x)
        return s
    }

    /** SET_ADD: (a set comprehension's, a display's) */
    @JvmStatic
    fun setAdd(s: Any?, v: Any?): Any? {
        (s as PySet).addKey(v)
        return s
    }

    /** SET_UPDATE: a display's `*it` */
    @JvmStatic
    fun setUpdate(s: Any?, it: Any?): Any? {
        update(s as PySet, it)
        return s
    }

    /** BUILD_SLICE */
    @JvmStatic
    fun buildSlice(parts: Array<Any?>): Any? = PySlice(parts[0], parts[1], if (parts.size > 2) parts[2] else PyNone)

    /** A new set (or frozenset) of [iterable]'s elements, in CPython's order (a set's merged, a dict's keys, else iterated). */
    @JvmStatic
    fun of(iterable: Any?, frozen: Boolean): PySet {
        val s = PySet(frozen)
        update(s, iterable)
        return s
    }

    @JvmStatic fun frozenOf(s: PySet): PySet = if (s.frozen) s else PySet(true).also { it.merge(s) }

    /** set_update_internal. */
    @JvmStatic
    fun update(s: PySet, other: Any?) {
        when {
            other is PySet && other.sub == null -> s.merge(other)
            other is PySet -> s.merge(other)
            other is PyDict && other.sub == null -> {
                if ((s.fill + other.size).toLong() * 5 >= s.mask.toLong() * 3) s.resize((s.used + other.size) * 2)
                for (k in other.map.keys) s.addKey(k.v)
            }
            else -> {
                val it = PyRt.iter(other)
                while (true) {
                    val x = it.next()
                    if (x === PyIterator.STOP) break
                    s.addKey(x)
                }
            }
        }
    }

    private fun asSet(v: Any?): PySet = v as? PySet ?: of(v, false)

    @JvmStatic fun copy(s: PySet, frozen: Boolean = s.frozen): PySet = PySet(frozen).also { it.merge(s) }

    /** set_intersection: the smaller's elements the other has, in the smaller's order. */
    @JvmStatic
    fun intersection(s: PySet, other: Any?, frozen: Boolean = s.frozen): PySet {
        val result = PySet(frozen)
        if (other is PySet) {
            var a = s
            var b = other
            if (b.used > a.used) {
                a = other
                b = s
            }
            // (the smaller iterated, the larger looked in)
            for (j in b.keys.indices) {
                val k = b.keys[j]
                if (k != null && k !== PySet.DUMMY && a.find(k, b.hashes[j]) >= 0) result.add(k, b.hashes[j])
            }
            return result
        }
        val it = PyRt.iter(other)
        while (true) {
            val x = it.next()
            if (x === PyIterator.STOP) break
            val h = s.hashOf(x)
            if (s.find(x, h) >= 0) result.add(x, h)
        }
        return result
    }

    /** set_difference: [s]'s elements [other] has not (copied, then discarded, where s is four times other's size). */
    @JvmStatic
    fun difference(s: PySet, other: Any?, frozen: Boolean = s.frozen): PySet {
        // (set_copy_and_difference: a copy, other's elements discarded from it)
        if (other !is PySet && !(other is PyDict && other.sub == null) || (s.used shr 2) > (if (other is PySet) other.used else (other as PyDict).size)) {
            val result = copy(s, frozen)
            differenceUpdate(result, other)
            return result
        }
        val o = other
        val result = PySet(frozen)
        for (j in s.keys.indices) {
            val k = s.keys[j]
            if (k == null || k === PySet.DUMMY) continue
            val has = if (o is PySet) o.find(k, s.hashes[j]) >= 0 else (o as PyDict).containsKey(k)
            if (!has) result.add(k, s.hashes[j])
        }
        return result
    }

    /** set_difference_update_internal: other's elements discarded from [s], then its dummies past a quarter dropped. */
    @JvmStatic
    fun differenceUpdate(s: PySet, other: Any?) {
        if (other === s) {
            s.clear()
            return
        }
        when (other) {
            is PySet -> for (j in other.keys.indices) {
                val k = other.keys[j]
                if (k != null && k !== PySet.DUMMY) s.discard(k, other.hashes[j])
            }
            is PyDict -> for (k in other.map.keys) s.discard(k.v, s.hashOf(k.v))
            else -> {
                val it = PyRt.iter(other)
                while (true) {
                    val x = it.next()
                    if (x === PyIterator.STOP) break
                    s.discard(x, s.hashOf(x))
                }
            }
        }
        s.shrinkDummies()
    }

    /** set_symmetric_difference_update: each of other's elements discarded where s has it, else added. */
    @JvmStatic
    fun symmetricUpdate(s: PySet, other: Any?) {
        val o = other as? PySet ?: of(other, false)
        if (o === s) {
            s.clear()
            return
        }
        for (j in o.keys.indices) {
            val k = o.keys[j]
            if (k == null || k === PySet.DUMMY) continue
            if (!s.discard(k, o.hashes[j])) s.add(k, o.hashes[j])
        }
    }

    /** set_symmetric_difference: other's elements as a new set, updated with s's. */
    @JvmStatic
    fun symmetric(s: PySet, other: Any?, frozen: Boolean = s.frozen): PySet {
        val result = of(other, frozen)
        symmetricUpdate(result, s)
        return result
    }

    @JvmStatic
    fun isSubset(a: PySet, b: PySet): Boolean {
        if (a.used > b.used) return false
        for (j in a.keys.indices) {
            val k = a.keys[j]
            if (k != null && k !== PySet.DUMMY && b.find(k, a.hashes[j]) < 0) return false
        }
        return true
    }

    @JvmStatic fun equal(a: PySet, b: PySet): Boolean = a.used == b.used && isSubset(a, b)

    /** a op b of sets (| & - ^), or null where they are not both sets. */
    @JvmStatic
    fun binary(op: Int, a: Any?, b: Any?): Any? {
        if (a !is PySet || b !is PySet) return null
        // (the result is of the left one's kind)
        val frozen = a.frozen
        return when (op) {
            PyOp.BITOR -> copy(a, frozen).also { it.merge(b) }
            PyOp.BITAND -> intersection(a, b, frozen)
            PyOp.SUB -> difference(a, b, frozen)
            PyOp.BITXOR -> symmetric(a, b, frozen)
            else -> null
        }
    }

    /** a op= b of a set: a changed in place (|= &= -= ^=); null where it is not that. */
    @JvmStatic
    fun inplace(op: Int, a: Any?, b: Any?): Any? {
        if (a !is PySet || a.frozen || b !is PySet) return null
        when (op) {
            PyOp.BITOR -> a.merge(b)
            PyOp.BITAND -> {
                val r = intersection(a, b, false)
                a.keys = r.keys; a.hashes = r.hashes; a.mask = r.mask; a.fill = r.fill; a.used = r.used
            }
            PyOp.SUB -> differenceUpdate(a, b)
            PyOp.BITXOR -> symmetricUpdate(a, b)
            else -> return null
        }
        return a
    }

    @JvmStatic
    fun repr(s: PySet): String {
        val items = s.elements()
        val name = s.sub?.cls?.name
        if (items.isEmpty()) return (name ?: if (s.frozen) "frozenset" else "set") + "()"
        val body = items.joinToString(", ", "{", "}") { PyRt.repr(it) }
        return if (name != null) "$name($body)" else if (s.frozen) "frozenset($body)" else body
    }

    /** The set types: their constructors and methods (CPython's names and messages). */
    @JvmStatic
    fun define(d: PyDict) {
        val st = PyTypes.SET
        val ft = PyTypes.FROZENSET
        fun self(a: Array<Any?>, n: String): PySet = a.getOrNull(0) as? PySet ?: throw PyRt.typeError("descriptor '$n' requires a 'set' object but received a '${PyTypes.name(a.getOrNull(0))}'")
        fun mutable(a: Array<Any?>, n: String): PySet = self(a, n).also { if (it.frozen) throw PyException.attributeError(it, n, "'frozenset' object has no attribute '$n'") }
        fun m(t: PyType, n: String, impl: (Array<Any?>) -> Any?) = t.def(n, PyBuiltin(n) { _, a -> impl(a) })
        fun both(n: String, impl: (Array<Any?>) -> Any?) {
            m(st, n, impl)
            m(ft, n, impl)
        }
        // constructors: set() and frozenset() exactly; __new__ and __init__ for subclasses
        st.newImpl = { _, _, a, kw ->
            if (kw != null && kw.size > 0) throw PyRt.typeError("set() takes no keyword arguments")
            if (a.size > 1) throw PyRt.typeError("set expected at most 1 argument, got ${a.size}")
            if (a.isEmpty()) PySet(false) else of(a[0], false)
        }
        ft.newImpl = { _, _, a, kw ->
            if (kw != null && kw.size > 0) throw PyRt.typeError("frozenset() takes no keyword arguments")
            if (a.size > 1) throw PyRt.typeError("frozenset expected at most 1 argument, got ${a.size}")
            val v = a.getOrNull(0)
            if (v is PySet && v.frozen && v.sub == null) v else if (a.isEmpty()) PySet(true) else of(v, true)
        }
        fun subNew(t: PyType, frozen: Boolean) = PyBuiltin("__new__", { _, a ->
            val cls = a[0] as PyType
            val s = if (frozen && a.size > 1) of(a[1], true) else PySet(frozen)
            if (cls !== t) s.sub = PySub(cls)
            s
        }, { _, a, _ ->
            val cls = a[0] as PyType
            val s = if (frozen && a.size > 1) of(a[1], true) else PySet(frozen)
            if (cls !== t) s.sub = PySub(cls)
            s
        })
        st.def("__new__", subNew(st, false))
        ft.def("__new__", subNew(ft, true))
        st.def("__init__", PyBuiltin("__init__") { _, a ->
            val s = self(a, "__init__")
            if (a.size > 2) throw PyRt.typeError("set expected at most 1 argument, got ${a.size - 1}")
            s.clear()
            if (a.size > 1) update(s, a[1])
            PyNone
        })
        d[PyStr("set")] = st
        d[PyStr("frozenset")] = ft
        // the mutable set's
        m(st, "add") { a -> mutable(a, "add").addKey(a[1]); PyNone }
        m(st, "remove") { a -> val s = mutable(a, "remove"); if (!s.discard(a[1], s.hashOf(a[1]))) throw PyException.keyError(a[1]); PyNone }
        m(st, "discard") { a -> val s = mutable(a, "discard"); s.discard(a[1], s.hashOf(a[1])); PyNone }
        m(st, "pop") { a -> mutable(a, "pop").pop() }
        m(st, "clear") { a -> mutable(a, "clear").clear(); PyNone }
        m(st, "update") { a -> val s = mutable(a, "update"); for (i in 1 until a.size) update(s, a[i]); PyNone }
        m(st, "intersection_update") { a ->
            val s = mutable(a, "intersection_update")
            var r = s
            for (i in 1 until a.size) r = intersection(r, a[i], false)
            if (r !== s) { s.keys = r.keys; s.hashes = r.hashes; s.mask = r.mask; s.fill = r.fill; s.used = r.used }
            PyNone
        }
        m(st, "difference_update") { a ->
            val s = mutable(a, "difference_update")
            for (i in 1 until a.size) differenceUpdate(s, a[i])
            PyNone
        }
        m(st, "symmetric_difference_update") { a -> symmetricUpdate(mutable(a, "symmetric_difference_update"), a[1]); PyNone }
        // both kinds'
        both("copy") { a -> val s = self(a, "copy"); if (s.frozen && s.sub == null) s else copy(s) }
        both("union") { a -> val r = copy(self(a, "union")); for (i in 1 until a.size) update(r, a[i]); r }
        both("intersection") { a -> var r = self(a, "intersection"); if (a.size == 1) r = copy(r); for (i in 1 until a.size) r = intersection(r, a[i]); r }
        both("difference") { a -> var r = self(a, "difference"); if (a.size == 1) r = copy(r); for (i in 1 until a.size) r = difference(r, a[i]); r }
        both("symmetric_difference") { a -> symmetric(self(a, "symmetric_difference"), a[1]) }
        both("issubset") { a -> PyBool.of(isSubset(self(a, "issubset"), asSet(a[1]))) }
        both("issuperset") { a -> PyBool.of(isSubset(asSet(a[1]), self(a, "issuperset"))) }
        both("isdisjoint") { a ->
            val s = self(a, "isdisjoint")
            val it = PyRt.iter(a[1])
            var disjoint = true
            while (true) {
                val x = it.next()
                if (x === PyIterator.STOP) break
                if (s.contains(x)) { disjoint = false; break }
            }
            PyBool.of(disjoint)
        }
        both("__contains__") { a -> PyBool.of(self(a, "__contains__").contains(a[1])) }
        both("__len__") { a -> self(a, "__len__").used.toLong() }
        both("__iter__") { a -> PyRt.iterValues(self(a, "__iter__")) }
        both("__repr__") { a -> PyStr(repr(self(a, "__repr__"))) }
        st.def("__hash__", PyNone)
        m(ft, "__hash__") { a -> self(a, "__hash__").frozenHash() }
    }
}

/** slice(start, stop, step): what `a[i:j:k]` subscripts with (hashable since 3.12). */
class PySlice(@JvmField val start: Any?, @JvmField val stop: Any?, @JvmField val step: Any?) {
    override fun equals(other: Any?) = other is PySlice && PyRt.eq(start, other.start) && PyRt.eq(stop, other.stop) && PyRt.eq(step, other.step)
    override fun hashCode() = PyHash.hash(this).toInt()

    /**
     * The slice applied to a sequence of [length] (PySlice_Unpack and PySlice_AdjustIndices): start, stop, step and
     * how many items it takes.
     */
    fun indices(length: Long): LongArray {
        fun index(v0: Any?): Long = when (val v = PyRt.indexValue(v0) ?: v0) {
            is Long -> v
            is PyBool -> if (v.value) 1L else 0L
            is BigInteger -> if (v.signum() < 0) Long.MIN_VALUE + 1 else Long.MAX_VALUE
            else -> throw PyRt.typeError("slice indices must be integers or None or have an __index__ method")
        }
        val st = if (step === PyNone || step == null) 1L else index(step)
        if (st == 0L) throw PyException("ValueError", "slice step cannot be zero")
        var a = if (start === PyNone || start == null) (if (st < 0) Long.MAX_VALUE else 0L) else index(start)
        var b = if (stop === PyNone || stop == null) (if (st < 0) Long.MIN_VALUE else Long.MAX_VALUE) else index(stop)
        if (a < 0) {
            a += length
            if (a < 0) a = if (st < 0) -1 else 0
        } else if (a >= length) a = if (st < 0) length - 1 else length
        if (b < 0) {
            b += length
            if (b < 0) b = if (st < 0) -1 else 0
        } else if (b >= length) b = if (st < 0) length - 1 else length
        val n = if (st < 0) (if (b < a) (a - b - 1) / -st + 1 else 0L) else (if (a < b) (b - a - 1) / st + 1 else 0L)
        return longArrayOf(a, b, st, n)
    }

    override fun toString() = "slice(${PyRt.repr(start)}, ${PyRt.repr(stop)}, ${PyRt.repr(step)})"
}
