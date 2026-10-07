package io.neonjs.runtime

/**
 * Structure token of a [PropertyMap], used by property inline caches.
 *
 * Invariant relied upon by the caches: two maps carrying the same *cacheable* shape have the same keys in the same
 * slots with the same attribute flags, and their owners have the same prototype and the same Java class. This holds
 * because
 *  - shared shapes form a transition tree whose root is specific to one (prototype object, owner class) pair, and a
 *    map only moves along tree edges when a property is appended;
 *  - every other structural change (delete, attribute change, prototype change of a non-replayable map, slot
 *    compaction) gives the map a fresh [unique] shape that no other map ever carries.
 *
 * Transition trees are per prototype object, so they are only touched by the agent that owns that object (contexts
 * are single-threaded) and never shared between contexts; they die with their prototype.
 *
 * Objects with overridden [[Get]]/[[Set]]/[[HasProperty]] (proxies, host objects, namespaces...) carry [UNCACHEABLE].
 */
class Shape private constructor(
    /** Key appended by the edge from the parent (null for roots and unique shapes). */
    @JvmField val key: Any?,
    /** Attribute flags of [key]. */
    @JvmField val attrs: Int,
    /** Number of slots described (meaningful for shared shapes only). */
    @JvmField val size: Int,
    /** Tree root (self for roots); null for unique shapes. */
    root: Shape?,
    /** Owner class (roots only). */
    @JvmField val ownerClass: Class<*>?,
    @JvmField val cacheable: Boolean,
) {
    private val root: Shape = root ?: this

    /** True when the shape belongs to a transition tree (append transitions are deterministic). */
    @JvmField val shared: Boolean = ownerClass != null || root != null

    /** Single transition (Shape) or a map of them (HashMap<TransitionKey, Shape>). */
    private var transitions: Any? = null

    /** Number of shapes in the tree (maintained on the root). */
    private var treeSize = 1

    private data class TransitionKey(val key: Any, val attrs: Int)

    /**
     * Shape after appending [key] with [attrs], or a unique shape if the tree is full or the map is too large (the map
     * then continues in dictionary mode).
     */
    fun append(key: Any, attrs: Int): Shape {
        if (!shared || size >= MAX_SHARED_SIZE) return unique()
        val t = transitions
        if (t is Shape) {
            if (t.attrs == attrs && t.key == key) return t
        } else if (t != null) {
            @Suppress("UNCHECKED_CAST")
            (t as HashMap<TransitionKey, Shape>)[TransitionKey(key, attrs)]?.let { return it }
        }
        val r = root
        if (r.treeSize >= MAX_TREE_SIZE) return unique()
        r.treeSize++
        val child = Shape(key, attrs, size + 1, r, null, true)
        when (t) {
            null -> transitions = child
            is Shape -> {
                val m = HashMap<TransitionKey, Shape>()
                m[TransitionKey(t.key!!, t.attrs)] = t
                m[TransitionKey(key, attrs)] = child
                transitions = m
            }
            else -> @Suppress("UNCHECKED_CAST") (t as HashMap<TransitionKey, Shape>).put(TransitionKey(key, attrs), child)
        }
        return child
    }

    override fun toString(): String = when {
        !cacheable -> "Shape(uncacheable)"
        !shared -> "Shape(unique@${Integer.toHexString(System.identityHashCode(this))})"
        else -> "Shape(size=$size, key=$key)"
    }

    companion object {
        /** Maps with more properties than this are kept in dictionary mode. */
        const val MAX_SHARED_SIZE = 128

        /** Bound on the number of shapes per transition tree (bounds memory under adversarial key patterns). */
        const val MAX_TREE_SIZE = 16384

        /** Shape of maps whose owner has exotic [[Get]]/[[Set]]: never cached, never changes. */
        @JvmField val UNCACHEABLE = Shape(null, 0, 0, null, null, false)

        /** A fresh token not shared with any other map. */
        @JvmStatic fun unique(): Shape = Shape(null, 0, 0, null, null, true)

        @JvmStatic fun newRoot(cls: Class<*>): Shape = Shape(null, 0, 0, null, cls, true)

        private const val SPECIAL_ACCESS = JSObject.SPECIAL_GET or JSObject.SPECIAL_SET or JSObject.SPECIAL_HAS

        /**
         * Shape of an object that has no [PropertyMap] yet (or the root for a new map): the empty root of its
         * (prototype, class) tree. Null when the object has no prototype (such maps start in dictionary mode).
         */
        @JvmStatic
        fun emptyShapeOf(o: JSObject): Shape? {
            if (o.special and SPECIAL_ACCESS != 0) return UNCACHEABLE
            val p = o.proto ?: return null
            val c = p.childShapes
            val cls = o.javaClass
            if (c is Shape && c.ownerClass === cls) return c
            return rootSlow(p, cls)
        }

        private fun rootSlow(p: JSObject, cls: Class<*>): Shape {
            val c = p.childShapes
            if (c == null) {
                val r = newRoot(cls)
                p.childShapes = r
                return r
            }
            if (c is Shape) {
                if (c.ownerClass === cls) return c
                val m = HashMap<Class<*>, Shape>(4)
                m[c.ownerClass!!] = c
                val r = newRoot(cls)
                m[cls] = r
                p.childShapes = m
                return r
            }
            @Suppress("UNCHECKED_CAST")
            return (c as HashMap<Class<*>, Shape>).getOrPut(cls) { newRoot(cls) }
        }
    }
}
