package io.neonjs.runtime

/**
 * Array exotic object. Elements live in [dense] (with [Hole] for missing entries, all with default attributes)
 * until an element with non-default attributes or a very sparse write occurs, after which all indexed elements are
 * stored as Int keys in [props] ([sparse] mode).
 */
class JSArray(proto: JSObject?, capacity: Int = 0) : JSObject(proto) {
    @JvmField var dense: Array<Any?> = if (capacity == 0) EMPTY else arrayOfNulls(capacity)
    @JvmField var denseLen = 0
    @JvmField var length: Long = 0
    @JvmField var lengthWritable = true
    @JvmField var sparse = false

    init {
        special = special or EXOTIC_OWN
    }

    companion object {
        private val EMPTY = arrayOfNulls<Any?>(0)
        const val MAX_LENGTH = 4294967295L

        @JvmStatic
        fun of(proto: JSObject?, values: Array<Any?>): JSArray {
            val a = JSArray(proto)
            a.dense = values
            a.denseLen = values.size
            a.length = values.size.toLong()
            return a
        }

        @JvmStatic
        fun of(realm: Realm, values: List<Any?>): JSArray = of(realm.arrayPrototype, values.toTypedArray())
    }

    override val className: String get() = "Array"

    // ------------------------------------------------------------------ fast paths

    /** True if no object on the prototype chain has indexed properties or exotic behaviour. */
    fun protoChainClean(): Boolean {
        var p = proto
        while (p != null) {
            if (p.special and SPECIAL_ALL != 0) return false
            val pm = p.props
            if (pm != null && pm.hasIndexKeys) return false
            p = p.proto
        }
        return true
    }

    fun getIndexFast(i: Int): Any? {
        if (!sparse && i < denseLen) {
            val v = dense[i]
            if (v !== Hole) return v
        }
        return get(i, this)
    }

    /** Direct element store; returns false if the slow path must be used. */
    fun trySetIndexFast(i: Int, v: Any?): Boolean {
        if (sparse) return false
        if (i < denseLen) {
            if (dense[i] !== Hole) {
                dense[i] = v
                return true
            }
            if (!extensible || !protoChainClean()) return false
            dense[i] = v
            return true
        }
        if (i == denseLen && extensible && lengthWritable && protoChainClean()) {
            appendDense(v)
            if (denseLen.toLong() > length) length = denseLen.toLong()
            return true
        }
        return false
    }

    private fun appendDense(v: Any?) {
        if (denseLen == dense.size) {
            val n = if (dense.isEmpty()) 8 else dense.size + (dense.size shr 1) + 1
            dense = dense.copyOf(n)
        }
        dense[denseLen++] = v
    }

    /** Pushes during array construction (literals, Array.from on fresh arrays). */
    fun pushInit(v: Any?) {
        if (sparse) {
            val idx = length
            ensureProps().add(PK.fromIndex(idx), v, Attr.ALL)
            length = idx + 1
            return
        }
        if (denseLen.toLong() == length) {
            appendDense(v)
            length = denseLen.toLong()
        } else {
            defineOwnProperty(PK.fromIndex(length), PropertyDescriptor.data(v, Attr.ALL))
        }
    }

    fun pushHoleInit() {
        if (!sparse && denseLen.toLong() == length) {
            appendDense(Hole)
            length = denseLen.toLong()
        } else {
            length++
        }
    }

    private fun toSparse() {
        if (sparse) return
        val pm = ensureProps()
        // Move indexed elements into props preserving their order before other keys (order is computed on demand).
        for (i in 0 until denseLen) {
            val v = dense[i]
            if (v !== Hole) pm.add(i, v, Attr.ALL)
        }
        dense = EMPTY
        denseLen = 0
        sparse = true
    }

    // ------------------------------------------------------------------ internal methods

    override fun getOwnProperty(key: Any): PropertyDescriptor? {
        if (key is Int && !sparse) {
            if (key < denseLen) {
                val v = dense[key]
                if (v !== Hole) return PropertyDescriptor.data(v, Attr.ALL)
            }
            return null
        }
        if (key == "length") return PropertyDescriptor.data(length.toDouble(), if (lengthWritable) Attr.WRITABLE else 0)
        return ordinaryGetOwnProperty(key)
    }

    override fun getOwnValue(key: Any, receiver: Any?): Any? {
        if (key is Int && !sparse) {
            if (key < denseLen) {
                val v = dense[key]
                if (v !== Hole) return v
            }
            return NotFound
        }
        if (key == "length") return length.toDouble()
        return super.getOwnValue(key, receiver)
    }

    override fun icSafeKey(key: Any): Boolean = key is String && key != "length" && PK.arrayIndex(key) < 0

    override fun hasOwnProperty(key: Any): Boolean {
        if (key is Int && !sparse) return key < denseLen && dense[key] !== Hole
        if (key == "length") return true
        val p = props ?: return false
        return p.find(key) >= 0
    }

    override fun get(key: Any, receiver: Any?): Any? {
        if (key is Int && !sparse && key < denseLen) {
            val v = dense[key]
            if (v !== Hole) return v
        }
        return super.get(key, receiver)
    }

    override fun set(key: Any, value: Any?, receiver: Any?): Boolean {
        return (key is Int && receiver === this && trySetIndexFast(key, value)) || ordinarySet(key, value, receiver)
    }

    override fun defineOwnProperty(key: Any, desc: PropertyDescriptor): Boolean {
        if (key == "length") return setLength(desc)
        val idx = PK.arrayIndex(key)
        if (idx >= 0) {
            if (idx >= length && !lengthWritable) return false
            if (!defineIndex(key, desc)) return false
            if (idx >= length) length = idx + 1
            return true
        }
        return ordinaryDefineOwnProperty(key, desc)
    }

    private fun defineIndex(key: Any, desc: PropertyDescriptor): Boolean {
        if (!sparse && key is Int) {
            val exists = key < denseLen && dense[key] !== Hole
            val defaultAttrs = !desc.isAccessor &&
                    (if (exists) (!desc.hasWritable || desc.writable) && (!desc.hasEnumerable || desc.enumerable) && (!desc.hasConfigurable || desc.configurable)
                    else desc.hasWritable && desc.writable && desc.hasEnumerable && desc.enumerable && desc.hasConfigurable && desc.configurable)
            if (defaultAttrs) {
                if (exists) {
                    if (desc.hasValue) dense[key] = desc.value
                    return true
                }
                if (!extensible) return false
                if (key < denseLen) {
                    dense[key] = if (desc.hasValue) desc.value else Undefined
                    return true
                }
                if (key.toLong() - denseLen < 1024 || key < denseLen * 2) {
                    while (denseLen < key) appendDense(Hole)
                    appendDense(if (desc.hasValue) desc.value else Undefined)
                    return true
                }
            }
            if (!exists && !extensible) return false
            toSparse()
        }
        return ordinaryDefineOwnProperty(key, desc)
    }

    /** ArraySetLength */
    private fun setLength(desc: PropertyDescriptor): Boolean {
        if (!desc.hasValue) {
            return validateLengthAttrs(desc).also { ok -> if (ok && desc.hasWritable && !desc.writable) lengthWritable = false }
        }
        val newLen = Ops.toUint32(desc.value)
        val numberLen = Ops.toNumber(desc.value)
        if (newLen.toDouble() != numberLen) throw JSException.rangeError("Invalid array length")
        if (!validateLengthAttrs(desc)) return false
        if (newLen >= length) {
            if (!lengthWritable && newLen != length) return false
            length = newLen
            if (desc.hasWritable && !desc.writable) lengthWritable = false
            return true
        }
        if (!lengthWritable) return false
        val newWritable = !(desc.hasWritable && !desc.writable)
        val ok = truncate(newLen)
        if (!newWritable) lengthWritable = false
        return ok
    }

    private fun validateLengthAttrs(desc: PropertyDescriptor): Boolean {
        if (desc.hasConfigurable && desc.configurable) return false
        if (desc.hasEnumerable && desc.enumerable) return false
        if (desc.isAccessor) return false
        if (!lengthWritable && desc.hasWritable && desc.writable) return false
        return true
    }

    /** Deletes elements >= newLen (descending); stops at a non-configurable element. */
    private fun truncate(newLen: Long): Boolean {
        if (!sparse) {
            if (newLen < denseLen) {
                for (i in newLen.toInt() until denseLen) dense[i] = null
                denseLen = newLen.toInt()
                if (dense.size > 64 && denseLen < dense.size / 4) dense = dense.copyOf(maxOf(denseLen, 8))
            }
            // big-index string keys may exist in props
            val pm = props
            if (pm != null) {
                val bigKeys = ArrayList<String>()
                pm.forEachLive { i -> val k = pm.keys[i]; if (k is String && PK.arrayIndex(k) >= newLen) bigKeys.add(k) }
                bigKeys.sortByDescending { PK.arrayIndex(it) }
                for (k in bigKeys) {
                    if (!delete(k)) {
                        length = PK.arrayIndex(k) + 1
                        return false
                    }
                }
            }
            length = newLen
            return true
        }
        val pm = props
        if (pm != null) {
            val keys = ArrayList<Any>()
            pm.forEachLive { i ->
                val k = pm.keys[i]!!
                val ai = PK.arrayIndex(k)
                if (ai >= newLen) keys.add(k)
            }
            keys.sortByDescending { PK.arrayIndex(it) }
            for (k in keys) {
                if (!delete(k)) {
                    length = PK.arrayIndex(k) + 1
                    return false
                }
            }
        }
        length = newLen
        return true
    }

    override fun delete(key: Any): Boolean {
        if (key is Int && !sparse) {
            if (key < denseLen) {
                dense[key] = Hole
                if (key == denseLen - 1) {
                    var n = denseLen - 1
                    while (n > 0 && dense[n - 1] === Hole) n--
                    for (j in n until denseLen) dense[j] = null
                    denseLen = n
                }
            }
            return true
        }
        if (key == "length") return false
        return super.delete(key)
    }

    override fun preventExtensions(): Boolean {
        extensible = false
        return true
    }

    override fun ownPropertyKeys(): MutableList<Any> {
        val out = ArrayList<Any>()
        val pm = props
        if (!sparse) {
            for (i in 0 until denseLen) if (dense[i] !== Hole) out.add(i)
            if (pm != null) {
                val big = ArrayList<String>()
                pm.forEachLive { i -> val k = pm.keys[i]; if (k is String && PK.arrayIndex(k) >= 0) big.add(k) }
                if (big.isNotEmpty()) {
                    big.sortBy { PK.arrayIndex(it) }
                    out.addAll(big)
                }
            }
        } else if (pm != null) {
            val idx = ArrayList<Any>()
            pm.forEachLive { i -> val k = pm.keys[i]!!; if (PK.arrayIndex(k) >= 0) idx.add(k) }
            idx.sortWith { a, b -> PK.arrayIndex(a).compareTo(PK.arrayIndex(b)) }
            out.addAll(idx)
        }
        out.add("length")
        if (pm != null) {
            pm.forEachLive { i -> val k = pm.keys[i]!!; if (k is String && PK.arrayIndex(k) < 0) out.add(k) }
            pm.forEachLive { i -> val k = pm.keys[i]!!; if (k is JSSymbol) out.add(k) }
        }
        return out
    }

    /** Snapshot of elements [0, length) for internal use (holes become Undefined via [[Get]]). */
    fun toList(): List<Any?> {
        val n = length.toInt()
        val out = ArrayList<Any?>(n)
        for (i in 0 until n) out.add(get(i, this))
        return out
    }
}

/** String exotic object. */
class JSStringObject(proto: JSObject?, @JvmField val value: String) : JSObject(proto) {
    init {
        special = special or EXOTIC_OWN
    }

    override val className: String get() = "String"

    override fun getOwnProperty(key: Any): PropertyDescriptor? {
        if (key is Int && key < value.length) return PropertyDescriptor.data(value[key].toString(), Attr.ENUMERABLE)
        if (key == "length") return PropertyDescriptor.data(value.length.toDouble(), 0)
        return ordinaryGetOwnProperty(key)
    }

    override fun getOwnValue(key: Any, receiver: Any?): Any? {
        if (key is Int && key < value.length) return value[key].toString()
        if (key == "length") return value.length.toDouble()
        return super.getOwnValue(key, receiver)
    }

    override fun icSafeKey(key: Any): Boolean = key is String && key != "length"

    override fun hasOwnProperty(key: Any): Boolean {
        if (key is Int && key < value.length) return true
        if (key == "length") return true
        val p = props ?: return false
        return p.find(key) >= 0
    }

    override fun defineOwnProperty(key: Any, desc: PropertyDescriptor): Boolean {
        if ((key is Int && key < value.length) || key == "length") {
            val cur = getOwnProperty(key)
            return Ops.isCompatiblePropertyDescriptor(extensible, desc, cur)
        }
        return ordinaryDefineOwnProperty(key, desc)
    }

    override fun delete(key: Any): Boolean {
        return !((key is Int && key < value.length) || key == "length") && super.delete(key)
    }

    override fun ownPropertyKeys(): MutableList<Any> {
        val extra = ArrayList<Any>(value.length)
        for (i in value.indices) extra.add(i)
        val keys = ordinaryOwnKeys(extra)
        // "length" goes after indices, before other string keys
        var firstNonIndex = 0
        while (firstNonIndex < keys.size && PK.arrayIndex(keys[firstNonIndex]) >= 0) firstNonIndex++
        keys.add(firstNonIndex, "length")
        return keys
    }
}
