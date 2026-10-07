package dev.mooner.neonjs.runtime

/** Property attribute bits. */
object Attr {
    const val WRITABLE = 1
    const val ENUMERABLE = 2
    const val CONFIGURABLE = 4
    const val ACCESSOR = 8
    const val ALL = WRITABLE or ENUMERABLE or CONFIGURABLE
    /** writable + configurable, not enumerable (default for builtin methods). */
    const val WC = WRITABLE or CONFIGURABLE
    const val NONE = 0
}

/**
 * Insertion-ordered property storage. Keys are property keys as produced by [PK]: Int (array index), String or
 * JSSymbol. Small maps use linear search; larger maps use an open-addressing index.
 */
class PropertyMap private constructor(
    /**
     * Structure token for inline caches (see [Shape]). Changes on every structural modification; value writes keep
     * it. Structural changes must therefore only happen through this class's methods, never by writing [keys] or
     * [flags] directly.
     */
    @JvmField var shape: Shape,
    @JvmField var keys: Array<Any?>,
    @JvmField var values: Array<Any?>,
    @JvmField var flags: IntArray,
    /** Key hash codes (parallel to [keys]) so that mismatching keys are rejected without a virtual equals call. */
    private var hashes: IntArray,
    /** Number of used entry slots, including deleted ones. */
    @JvmField var used: Int,
    /**
     * True while [keys], [flags] and [hashes] are a layout shared with other maps of the same shape (object literals,
     * see [withLayout]); they are copied before the first structural change.
     */
    private var layoutShared: Boolean,
) {
    constructor(initialShape: Shape?, initialCapacity: Int = 4) : this(
        initialShape ?: Shape.unique(), arrayOfNulls(initialCapacity), arrayOfNulls(initialCapacity),
        IntArray(initialCapacity), IntArray(initialCapacity), 0, false,
    )

    /** Number of live entries. */
    @JvmField var count = used
    private var index: IntArray? = null
    /** True once an Int key was ever added (to speed up ownKeys ordering). */
    @JvmField var hasIndexKeys = false

    private object Deleted

    fun find(key: Any): Int {
        val idx = index
        if (idx == null) {
            val ks = keys
            val hs = hashes
            val h = keyHash(key)
            for (i in 0 until used) {
                if (hs[i] != h) continue
                val k = ks[i]
                if (k === key || k == key) return i
            }
            return -1
        }
        val mask = idx.size - 1
        var h = spread(keyHash(key)) and mask
        while (true) {
            val e = idx[h]
            if (e == 0) return -1
            val k = keys[e - 1]
            if (k === key || (k !== Deleted && k == key)) return e - 1
            h = (h + 1) and mask
        }
    }

    /** Hash of a property key without a megamorphic virtual call (keys are String, Int or JSSymbol). */
    private fun keyHash(key: Any): Int = when (key) {
        is String -> key.hashCode()
        is Int -> key
        else -> System.identityHashCode(key)
    }

    private fun spread(h: Int): Int = h xor (h ushr 16) xor (h ushr 7)

    /** Adds a new entry (caller guarantees the key is absent). Returns entry index. */
    fun add(key: Any, value: Any?, attrs: Int): Int {
        val i = append(key, value, attrs)
        val s = shape
        if (s.cacheable) shape = s.append(key, attrs)
        return i
    }

    /**
     * Appends [next].key with [next].attrs where [next] is the transition of the current (shared) shape for that key,
     * as computed earlier by an inline cache. Equivalent to [add].
     */
    fun addTransition(next: Shape, value: Any?) {
        val before = shape
        append(next.key!!, value, next.attrs)
        // a shared shape implies no deleted slots, so append cannot have compacted; stay safe regardless
        shape = if (shape === before) next else Shape.unique()
    }

    /** Copies a shared layout before it is changed. */
    private fun ownLayout() {
        if (!layoutShared) return
        keys = keys.copyOf()
        flags = flags.copyOf()
        hashes = hashes.copyOf()
        layoutShared = false
    }

    /** Changes the attribute flags of entry [i]. */
    fun setFlags(i: Int, f: Int) {
        if (flags[i] == f) return
        ownLayout()
        flags[i] = f
        if (shape.cacheable) shape = Shape.unique()
    }

    /** Called after the owner's prototype changed: [newRoot] is the empty shape for the new (prototype, class). */
    fun reroot(newRoot: Shape?) {
        val s = shape
        if (!s.cacheable) return
        if (newRoot === Shape.UNCACHEABLE) {
            shape = newRoot
            return
        }
        if (newRoot == null || !s.shared || !newRoot.shared) {
            shape = Shape.unique()
            return
        }
        var t: Shape = newRoot
        for (i in 0 until used) {
            t = t.append(keys[i]!!, flags[i])
            if (!t.shared) break
        }
        shape = t
    }

    private fun append(key: Any, value: Any?, attrs: Int): Int {
        if (used == keys.size) grow()
        ownLayout()
        val i = used++
        keys[i] = key
        values[i] = value
        flags[i] = attrs
        hashes[i] = keyHash(key)
        count++
        if (key is Int) hasIndexKeys = true
        val idx = index
        if (idx != null) {
            if (used * 2 > idx.size) rebuildIndex(idx.size * 2) else insertIndex(idx, key, i)
        } else if (used > 8) rebuildIndex(32)
        return i
    }

    private fun insertIndex(idx: IntArray, key: Any, entry: Int) {
        val mask = idx.size - 1
        var h = spread(keyHash(key)) and mask
        while (idx[h] != 0) h = (h + 1) and mask
        idx[h] = entry + 1
    }

    private fun rebuildIndex(size: Int) {
        var sz = size
        while (sz < used * 2) sz *= 2
        val idx = IntArray(sz)
        for (i in 0 until used) {
            val k = keys[i]
            if (k !== Deleted) insertIndex(idx, k!!, i)
        }
        index = idx
    }

    private fun grow() {
        if (count < used && count <= used / 2) {
            compact()
            if (used < keys.size) return
        }
        val n = maxOf(4, keys.size * 2)
        keys = keys.copyOf(n)
        values = values.copyOf(n)
        flags = flags.copyOf(n)
        hashes = hashes.copyOf(n)
        layoutShared = false
    }

    private fun compact() {
        var j = 0
        for (i in 0 until used) {
            val k = keys[i]
            if (k !== Deleted) {
                keys[j] = k
                values[j] = values[i]
                flags[j] = flags[i]
                hashes[j] = hashes[i]
                j++
            }
        }
        for (i in j until used) {
            keys[i] = null; values[i] = null
        }
        used = j
        if (shape.cacheable) shape = Shape.unique()
        if (index != null) {
            if (used > 8) rebuildIndex(32) else index = null
        }
    }

    fun removeAt(i: Int) {
        ownLayout()
        if (shape.cacheable) shape = Shape.unique()
        keys[i] = Deleted
        values[i] = null
        count--
        if (count == 0) {
            for (j in 0 until used) { keys[j] = null; values[j] = null }
            used = 0
            index = null
        }
    }

    fun isLive(i: Int): Boolean = keys[i] !== Deleted

    inline fun forEachLive(f: (Int) -> Unit) {
        for (i in 0 until used) if (isLive(i)) f(i)
    }

    /** Immutable key layout of a shared shape, built once per object-literal site (see [withLayout]). */
    class Layout(
        /** The empty shape the keys were added to (identifies the prototype, class and context). */
        @JvmField val root: Shape,
        @JvmField val shape: Shape,
        @JvmField val keys: Array<Any?>,
        @JvmField val flags: IntArray,
        @JvmField val hashes: IntArray,
        @JvmField val hasIndexKeys: Boolean,
    )

    companion object {
        /** Largest literal built from a [Layout] (larger maps need the hash index). */
        const val MAX_LAYOUT = 8

        /**
         * The layout of a map with [keys] (distinct, all with attributes [Attr.ALL]) added in order to the empty
         * shape [root]; null when the result would not be a shared shape.
         */
        @JvmStatic
        fun layoutOf(root: Shape?, keys: Array<Any>): Layout? {
            if (root == null || !root.shared || keys.size > MAX_LAYOUT) return null
            var s: Shape = root
            val ks = arrayOfNulls<Any?>(keys.size)
            val fs = IntArray(keys.size)
            val hs = IntArray(keys.size)
            var hasIndex = false
            for (i in keys.indices) {
                val k = keys[i]
                s = s.append(k, Attr.ALL)
                if (!s.shared) return null
                ks[i] = k
                fs[i] = Attr.ALL
                hs[i] = when (k) {
                    is String -> k.hashCode()
                    is Int -> k
                    else -> System.identityHashCode(k)
                }
                if (k is Int) hasIndex = true
            }
            return Layout(root, s, ks, fs, hs, hasIndex)
        }

        /** A map with [layout] and its [values] (used as the map's own values array). */
        @JvmStatic
        fun withLayout(layout: Layout, values: Array<Any?>): PropertyMap {
            val m = PropertyMap(layout.shape, layout.keys, values, layout.flags, layout.hashes, values.size, true)
            m.hasIndexKeys = layout.hasIndexKeys
            return m
        }
    }
}

/** A (possibly partial) property descriptor. */
class PropertyDescriptor {
    @JvmField var value: Any? = Undefined
    @JvmField var getter: Any? = Undefined
    @JvmField var setter: Any? = Undefined
    @JvmField var writable = false
    @JvmField var enumerable = false
    @JvmField var configurable = false
    @JvmField var present = 0

    val hasValue get() = present and HAS_VALUE != 0
    val hasWritable get() = present and HAS_WRITABLE != 0
    val hasGet get() = present and HAS_GET != 0
    val hasSet get() = present and HAS_SET != 0
    val hasEnumerable get() = present and HAS_ENUMERABLE != 0
    val hasConfigurable get() = present and HAS_CONFIGURABLE != 0
    val isAccessor get() = present and (HAS_GET or HAS_SET) != 0
    val isData get() = present and (HAS_VALUE or HAS_WRITABLE) != 0
    val isGeneric get() = !isAccessor && !isData

    fun value(v: Any?): PropertyDescriptor { value = v; present = present or HAS_VALUE; return this }
    fun writable(b: Boolean): PropertyDescriptor { writable = b; present = present or HAS_WRITABLE; return this }
    fun enumerable(b: Boolean): PropertyDescriptor { enumerable = b; present = present or HAS_ENUMERABLE; return this }
    fun configurable(b: Boolean): PropertyDescriptor { configurable = b; present = present or HAS_CONFIGURABLE; return this }
    fun getter(g: Any?): PropertyDescriptor { getter = g; present = present or HAS_GET; return this }
    fun setter(s: Any?): PropertyDescriptor { setter = s; present = present or HAS_SET; return this }

    /** Attribute bits for a complete descriptor (missing fields default to false). */
    fun attrs(): Int = (if (writable) Attr.WRITABLE else 0) or (if (enumerable) Attr.ENUMERABLE else 0) or
            (if (configurable) Attr.CONFIGURABLE else 0) or (if (isAccessor) Attr.ACCESSOR else 0)

    companion object {
        const val HAS_VALUE = 1
        const val HAS_WRITABLE = 2
        const val HAS_GET = 4
        const val HAS_SET = 8
        const val HAS_ENUMERABLE = 16
        const val HAS_CONFIGURABLE = 32

        fun data(value: Any?, attrs: Int): PropertyDescriptor {
            val d = PropertyDescriptor()
            d.value = value
            d.writable = attrs and Attr.WRITABLE != 0
            d.enumerable = attrs and Attr.ENUMERABLE != 0
            d.configurable = attrs and Attr.CONFIGURABLE != 0
            d.present = HAS_VALUE or HAS_WRITABLE or HAS_ENUMERABLE or HAS_CONFIGURABLE
            return d
        }

        fun accessor(getter: Any?, setter: Any?, attrs: Int): PropertyDescriptor {
            val d = PropertyDescriptor()
            d.getter = getter ?: Undefined
            d.setter = setter ?: Undefined
            d.enumerable = attrs and Attr.ENUMERABLE != 0
            d.configurable = attrs and Attr.CONFIGURABLE != 0
            d.present = HAS_GET or HAS_SET or HAS_ENUMERABLE or HAS_CONFIGURABLE
            return d
        }
    }
}

/** Property key helpers. Keys are Int (array index <= Int.MAX_VALUE), String, or JSSymbol. */
object PK {
    /** Converts a string into a canonical property key. */
    @JvmStatic
    fun fromString(s: String): Any {
        val n = s.length
        if (n == 0 || n > 10) return s
        val c0 = s[0]
        if (c0 !in '0'..'9') return s
        if (c0 == '0') return if (n == 1) 0 else s
        var v = 0L
        for (i in 0 until n) {
            val c = s[i]
            if (c !in '0'..'9') return s
            v = v * 10 + (c - '0')
        }
        return if (v <= Int.MAX_VALUE) v.toInt() else s
    }

    @JvmStatic
    fun fromIndex(i: Long): Any = if (i in 0..Int.MAX_VALUE.toLong()) i.toInt() else i.toString()

    /** Key for a numeric value (ToPropertyKey of a Number). */
    @JvmStatic
    fun fromDouble(d: Double): Any {
        val i = d.toInt()
        if (i.toDouble() == d && i >= 0 && !(d == 0.0 && 1.0 / d < 0)) return i
        return fromString(NumberConv.toString(d))
    }

    /** Converts a key to the JS value used for ownKeys results and for-in. */
    @JvmStatic
    fun toValue(key: Any): Any = if (key is Int) key.toString() else key

    @JvmStatic
    fun toStringKey(key: Any): String = when (key) {
        is Int -> key.toString()
        is String -> key
        is JSSymbol -> key.toString()
        else -> key.toString()
    }

    /** Array index value (0..2^32-2) or -1. */
    @JvmStatic
    fun arrayIndex(key: Any): Long {
        if (key is Int) return key.toLong()
        if (key is String) {
            val n = key.length
            if (n != 10) return -1
            if (key[0] == '0') return -1
            var v = 0L
            for (c in key) {
                if (c !in '0'..'9') return -1
                v = v * 10 + (c - '0')
            }
            return if (v < 4294967295L) v else -1
        }
        return -1
    }

    /** Function name derived from a property key (SetFunctionName). */
    @JvmStatic
    fun functionName(key: Any): String = when (key) {
        is JSSymbol -> key.functionName()
        else -> toStringKey(key)
    }
}
