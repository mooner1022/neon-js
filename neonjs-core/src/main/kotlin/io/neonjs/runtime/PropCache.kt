package io.neonjs.runtime

/**
 * A named property access site (GET_PROP / PUT_PROP operand). Holds the key and the site's inline cache.
 *
 * Code blocks (and therefore sites) are shared by all contexts of an engine and may run on several threads at once.
 * Cache entries are immutable and published with a single reference write, so a racing reader always sees a
 * self-consistent entry; a lost update only costs another miss. Shapes are per prototype object, hence per context,
 * so an entry filled by one context can never hit in another.
 */
class PropSite(@JvmField val key: Any) {
    @JvmField var entry: PropIC? = null
    @JvmField var fills = 0

    override fun toString(): String = PK.toStringKey(key)
}

/**
 * Immutable inline-cache entry.
 *
 * [shape] is the receiver's shape. [chain] holds the shapes of the prototypes visited after the receiver, up to the
 * holder of the property (or up to the end of the chain for [PropCache.ABSENT] and for [PropCache.ADD] when no
 * prototype has the key); null means the property is an own property of the receiver.
 */
class PropIC(
    @JvmField val shape: Shape,
    @JvmField val chain: Array<Shape>?,
    @JvmField val slot: Int,
    @JvmField val kind: Int,
    @JvmField val next: Shape?,
    @JvmField val link: PropIC?,
) {
    fun withLink(l: PropIC?) = PropIC(shape, chain, slot, kind, next, l)
}

object PropCache {
    // get kinds
    const val DATA = 0
    const val GETTER = 1
    const val ABSENT = 2
    // put kinds
    const val STORE = 3
    const val ADD = 4
    const val SETTER = 5

    private const val MAX_POLY = 4
    private const val MAX_FILLS = 32
    private const val MAX_DEPTH = 8
    private const val SPECIAL_ACCESS = JSObject.SPECIAL_GET or JSObject.SPECIAL_SET or JSObject.SPECIAL_HAS

    @JvmStatic
    fun shapeOf(o: JSObject): Shape? {
        val pm = o.props
        return pm?.shape ?: Shape.emptyShapeOf(o)
    }

    /** Walks [chain] from [o]'s prototype; returns the last object if every shape matches, else null. */
    @JvmStatic
    private fun walk(o: JSObject, chain: Array<Shape>): JSObject? {
        var h = o
        for (s in chain) {
            h = h.proto ?: return null
            val hp = h.props
            val hs = hp?.shape ?: Shape.emptyShapeOf(h)
            if (hs !== s) return null
        }
        return h
    }

    // ------------------------------------------------------------------ [[Get]]

    @JvmStatic
    fun get(o: JSObject, site: PropSite): Any? {
        val pm = o.props
        val sh = pm?.shape ?: Shape.emptyShapeOf(o)
        var e = site.entry
        while (e != null) {
            if (e.shape === sh) {
                val c = e.chain
                if (c == null) {
                    val v = pm!!.values[e.slot]
                    return if (e.kind == DATA) v else callGetter(v, o)
                }
                val h = walk(o, c)
                if (h != null) {
                    if (e.kind == ABSENT) return Undefined
                    val v = h.props!!.values[e.slot]
                    return if (e.kind == DATA) v else callGetter(v, o)
                }
            }
            e = e.link
        }
        return getMiss(o, site, sh)
    }

    private fun callGetter(acc: Any?, receiver: Any?): Any? {
        val g = (acc as Accessor).getter
        return if (g is JSObject) g.call(receiver, EMPTY_ARGS) else Undefined
    }

    private fun getMiss(o: JSObject, site: PropSite, sh: Shape?): Any? {
        val key = site.key
        // an array's length is not an IC-able slot; read it directly (`i < arr.length` in loops)
        if (o is JSArray && key == "length") return Ops.num(o.length)
        if (sh != null && sh.cacheable && site.fills < MAX_FILLS) {
            site.fills++
            buildGet(o, key, sh)?.let { install(site, it) }
        }
        return o.get(key, o)
    }

    private fun cacheableFor(h: JSObject, key: Any, hs: Shape?): Boolean =
        hs != null && hs.cacheable && h.special and SPECIAL_ACCESS == 0 &&
            (h.special and JSObject.EXOTIC_OWN == 0 || h.icSafeKey(key))

    /** Computes the entry describing how [[Get]] of [key] resolves on [o], without running any guest code. */
    private fun buildGet(o: JSObject, key: Any, sh: Shape): PropIC? {
        var h = o
        var hs: Shape? = sh
        var chain: ArrayList<Shape>? = null
        while (true) {
            if (!cacheableFor(h, key, hs)) return null
            val pm = h.props
            val i = pm?.find(key) ?: -1
            if (i >= 0) {
                val kind = if (pm!!.flags[i] and Attr.ACCESSOR != 0) GETTER else DATA
                return PropIC(sh, chain?.toTypedArray(), i, kind, null, null)
            }
            val p = h.proto ?: return PropIC(sh, chain?.toTypedArray() ?: EMPTY_CHAIN, -1, ABSENT, null, null)
            if (chain == null) chain = ArrayList(4)
            if (chain.size >= MAX_DEPTH) return null
            hs = shapeOf(p)
            if (hs == null) {
                // a prototype without properties and without its own prototype: give it a map so it has a token
                p.ensureProps()
                hs = p.props!!.shape
            }
            chain.add(hs)
            h = p
        }
    }

    /** Puts [e] first, keeping up to MAX_POLY - 1 previous entries for other receiver shapes. */
    private fun install(site: PropSite, e: PropIC) {
        val olds = ArrayList<PropIC>(MAX_POLY)
        var x = site.entry
        while (x != null && olds.size < MAX_POLY - 1) {
            if (x.shape !== e.shape) olds.add(x)
            x = x.link
        }
        var tail: PropIC? = null
        for (i in olds.indices.reversed()) tail = olds[i].withLink(tail)
        site.entry = if (tail != null) e.withLink(tail) else e
    }

    // ------------------------------------------------------------------ [[Set]]

    /** Performs `o[key] = v` with `o` as receiver; returns false when the assignment failed (strict-mode error). */
    @JvmStatic
    fun put(o: JSObject, site: PropSite, v: Any?): Boolean {
        val pm = o.props
        val sh = pm?.shape ?: Shape.emptyShapeOf(o)
        var e = site.entry
        while (e != null) {
            if (e.shape === sh) {
                when (e.kind) {
                    STORE -> {
                        pm!!.values[e.slot] = v
                        return true
                    }
                    ADD -> if (o.extensible) {
                        val c = e.chain
                        if (c == null || walk(o, c) != null) {
                            o.ensureProps().addTransition(e.next!!, v)
                            return true
                        }
                    }
                    SETTER -> {
                        val h = walk(o, e.chain!!)
                        if (h != null) {
                            // the setter itself can be replaced without a shape change
                            val setter = (h.props!!.values[e.slot] as Accessor).setter
                            if (setter is JSObject) {
                                setter.call(o, arrayOf(v))
                                return true
                            }
                        }
                    }
                }
            }
            e = e.link
        }
        return putMiss(o, site, v, sh)
    }

    private fun putMiss(o: JSObject, site: PropSite, v: Any?, sh: Shape?): Boolean {
        val key = site.key
        if (sh != null && sh.cacheable && site.fills < MAX_FILLS) {
            site.fills++
            buildPut(o, key, sh)?.let { install(site, it) }
        }
        return o.set(key, v, o)
    }

    private fun buildPut(o: JSObject, key: Any, sh: Shape): PropIC? {
        if (!cacheableFor(o, key, sh)) return null
        val pm = o.props
        val i = pm?.find(key) ?: -1
        if (i >= 0) {
            val f = pm!!.flags[i]
            if (f and (Attr.ACCESSOR or Attr.WRITABLE) != Attr.WRITABLE) return null
            return PropIC(sh, null, i, STORE, null, null)
        }
        if (!o.extensible || !sh.shared) return null
        var h = o
        val chain = ArrayList<Shape>(4)
        while (true) {
            val p = h.proto ?: break
            if (chain.size >= MAX_DEPTH) return null
            val ps = shapeOf(p) ?: return null
            if (!cacheableFor(p, key, ps)) return null
            chain.add(ps)
            val pp = p.props
            val j = pp?.find(key) ?: -1
            if (j >= 0) {
                val f = pp!!.flags[j]
                if (f and Attr.ACCESSOR != 0) {
                    val setter = (pp.values[j] as Accessor).setter
                    if (setter !is JSObject) return null
                    return PropIC(sh, chain.toTypedArray(), j, SETTER, null, null)
                }
                if (f and Attr.WRITABLE == 0) return null
                break // writable data on a prototype: the receiver gets a new own property
            }
            h = p
        }
        val next = sh.append(key, Attr.ALL)
        if (!next.shared) return null
        return PropIC(sh, if (chain.isEmpty()) null else chain.toTypedArray(), -1, ADD, next, null)
    }

    private val EMPTY_CHAIN = arrayOf<Shape>()
}

/** A global variable access site (LOAD_GLOBAL / LOAD_GLOBAL_TYPEOF / STORE_GLOBAL operand). */
class GlobalSite(@JvmField val name: String) {
    @JvmField var cache: GlobalIC? = null
    /** Number of cache fills; past a limit the site stops caching (megamorphic, e.g. globals added in a loop). */
    @JvmField var fills = 0

    override fun toString(): String = name
}

/**
 * Immutable global-access cache entry: either lexical binding [index] of the global environment identified by
 * [token], or data property [slot] of a global object whose map has [shape] while the environment's lexical epoch is
 * [epoch] (a newer lexical declaration could shadow the property). Holds no realm objects.
 */
class GlobalIC(@JvmField val token: Any?, @JvmField val index: Int, @JvmField val shape: Shape?, @JvmField val slot: Int, @JvmField val epoch: Int)

object GlobalCache {
    @JvmStatic
    fun load(realm: Realm, site: GlobalSite, forTypeof: Boolean): Any? {
        val ge = realm.globalEnv
        val ic = site.cache
        if (ic != null) {
            if (ic.shape == null) {
                if (ic.token === ge.token) {
                    val v = ge.lexicalList[ic.index].value
                    if (v !== Uninitialized) return v
                }
            } else {
                val pm = ge.global.props
                if (pm != null && pm.shape === ic.shape && ge.lexicalEpoch == ic.epoch) return pm.values[ic.slot]
            }
        }
        fill(ge, site, false)
        return io.neonjs.vm.Rt.loadGlobal(realm, site.name, forTypeof)
    }

    @JvmStatic
    fun store(realm: Realm, site: GlobalSite, value: Any?, strict: Boolean) {
        val ge = realm.globalEnv
        val ic = site.cache
        if (ic != null) {
            if (ic.shape == null) {
                if (ic.token === ge.token) {
                    val b = ge.lexicalList[ic.index]
                    if (b.mutable && b.value !== Uninitialized) {
                        b.value = value
                        return
                    }
                }
            } else {
                val pm = ge.global.props
                if (pm != null && pm.shape === ic.shape && ge.lexicalEpoch == ic.epoch) {
                    pm.values[ic.slot] = value
                    return
                }
            }
        }
        fill(ge, site, true)
        io.neonjs.vm.Rt.storeGlobal(realm, site.name, value, strict)
    }

    private const val MAX_FILLS = 32

    private fun fill(ge: GlobalEnv, site: GlobalSite, forStore: Boolean) {
        if (site.fills >= MAX_FILLS) return
        site.fills++
        val name = site.name
        val b = ge.lexical[name]
        if (b != null) {
            val i = ge.lexicalList.indexOf(b)
            if (i >= 0) site.cache = GlobalIC(ge.token, i, null, -1, 0)
            return
        }
        val g = ge.global
        if (g.special and JSObject.SPECIAL_ALL != 0) return
        val pm = g.props ?: return
        if (!pm.shape.cacheable) return
        val i = pm.find(name)
        if (i < 0) return
        val f = pm.flags[i]
        if (f and Attr.ACCESSOR != 0) return
        if (forStore && f and Attr.WRITABLE == 0) return
        site.cache = GlobalIC(null, -1, pm.shape, i, ge.lexicalEpoch)
    }
}
