package io.neonjs.runtime

/**
 * Base class of all JS objects. Implements the ordinary object internal methods; exotic objects override them.
 *
 * [special] bits tell the generic algorithms when a subclass customises behaviour:
 *  - [EXOTIC_OWN]: own properties are not (only) stored in [props]; algorithms use the virtual [getOwnProperty].
 *  - [SPECIAL_GET]/[SPECIAL_SET]/[SPECIAL_HAS]: [[Get]]/[[Set]]/[[HasProperty]] are overridden and must be called
 *    even when the object appears in a prototype chain.
 */
open class JSObject(@JvmField var proto: JSObject?) {
    @JvmField var props: PropertyMap? = null
    @JvmField var extensible = true
    @JvmField var special = 0
    /** Private elements: PrivateName -> value (fields) or PrivateElement (methods/accessors). */
    @JvmField var privateElements: java.util.IdentityHashMap<Any, Any?>? = null
    /** Roots of the shape trees of objects that have this object as prototype (see [Shape.emptyShapeOf]). */
    @JvmField var childShapes: Any? = null

    companion object {
        const val EXOTIC_OWN = 1
        const val SPECIAL_GET = 2
        const val SPECIAL_SET = 4
        const val SPECIAL_HAS = 8
        const val CALLABLE = 16
        const val CONSTRUCTOR = 32
        const val IMMUTABLE_PROTO = 64
        /** Object is a class constructor (throws when called without new). */
        const val CLASS_CONSTRUCTOR = 128
        const val HTMLDDA = 256
        const val SPECIAL_ALL = EXOTIC_OWN or SPECIAL_GET or SPECIAL_SET or SPECIAL_HAS
    }

    /** Builtin tag used by Object.prototype.toString ("Object", "Array", "Function", "Error", ...). */
    open val className: String get() = "Object"

    val isCallable: Boolean get() = special and CALLABLE != 0
    val isConstructor: Boolean get() = special and CONSTRUCTOR != 0

    // ------------------------------------------------------------------ call / construct

    open fun call(thisArg: Any?, args: Array<Any?>): Any? =
        throw JSException.typeError("${Ops.describe(this)} is not a function")

    open fun construct(args: Array<Any?>, newTarget: JSObject): Any? =
        throw JSException.typeError("${Ops.describe(this)} is not a constructor")

    // ------------------------------------------------------------------ prototype / extensibility

    open fun getPrototypeOf(): JSObject? = proto

    open fun setPrototypeOf(p: JSObject?): Boolean {
        if (p === proto) return true
        if (special and IMMUTABLE_PROTO != 0) return false
        if (!extensible) return false
        var q = p
        while (q != null) {
            if (q === this) return false
            if (q.special and SPECIAL_GET != 0 && q is ProxyObject) break
            q = q.proto
        }
        changeProto(p)
        return true
    }

    /** Sets the prototype field without checks (initialization / exotic objects), keeping the shape consistent. */
    fun changeProto(p: JSObject?) {
        if (p === proto) return
        proto = p
        props?.reroot(Shape.emptyShapeOf(this))
    }

    open fun isExtensible(): Boolean = extensible

    open fun preventExtensions(): Boolean {
        extensible = false
        return true
    }

    // ------------------------------------------------------------------ own properties

    fun ensureProps(): PropertyMap {
        var p = props
        if (p == null) {
            p = PropertyMap(Shape.emptyShapeOf(this))
            props = p
        }
        return p
    }

    /** [[GetOwnProperty]] */
    open fun getOwnProperty(key: Any): PropertyDescriptor? = ordinaryGetOwnProperty(key)

    fun ordinaryGetOwnProperty(key: Any): PropertyDescriptor? {
        val p = props ?: return null
        val i = p.find(key)
        if (i < 0) return null
        val f = p.flags[i]
        return if (f and Attr.ACCESSOR != 0) {
            val a = p.values[i] as Accessor
            PropertyDescriptor.accessor(a.getter, a.setter, f)
        } else PropertyDescriptor.data(p.values[i], f)
    }

    /** Fast own-value lookup used by [[Get]]: returns [NotFound] when absent; invokes getters with [receiver]. */
    open fun getOwnValue(key: Any, receiver: Any?): Any? {
        val p = props ?: return NotFound
        val i = p.find(key)
        if (i < 0) return NotFound
        if (p.flags[i] and Attr.ACCESSOR != 0) {
            val g = (p.values[i] as Accessor).getter
            if (g is JSObject) return g.call(receiver, EMPTY_ARGS)
            return Undefined
        }
        return p.values[i]
    }

    /**
     * Whether inline caches may treat [key] as an ordinary property stored in [props] for objects of this class even
     * though the class has [EXOTIC_OWN] behaviour. Must depend only on the class and the key. Subclasses that override
     * [getOwnValue], [get], [set] or [getOwnProperty] must set one of the [SPECIAL_ALL] bits.
     */
    open fun icSafeKey(key: Any): Boolean = special and EXOTIC_OWN == 0

    /** Fast own-property existence check. */
    open fun hasOwnProperty(key: Any): Boolean {
        if (special and EXOTIC_OWN != 0) return getOwnProperty(key) != null
        val p = props ?: return false
        return p.find(key) >= 0
    }

    /** [[DefineOwnProperty]] */
    open fun defineOwnProperty(key: Any, desc: PropertyDescriptor): Boolean = ordinaryDefineOwnProperty(key, desc)

    fun ordinaryDefineOwnProperty(key: Any, desc: PropertyDescriptor): Boolean {
        val p = props
        val i = p?.find(key) ?: -1
        if (i < 0) {
            if (!isExtensible()) return false
            val pm = ensureProps()
            if (desc.isAccessor) {
                pm.add(key, Accessor(if (desc.hasGet) desc.getter else Undefined, if (desc.hasSet) desc.setter else Undefined),
                    Attr.ACCESSOR or (if (desc.enumerable) Attr.ENUMERABLE else 0) or (if (desc.configurable) Attr.CONFIGURABLE else 0))
            } else {
                pm.add(key, if (desc.hasValue) desc.value else Undefined,
                    (if (desc.writable) Attr.WRITABLE else 0) or (if (desc.enumerable) Attr.ENUMERABLE else 0) or (if (desc.configurable) Attr.CONFIGURABLE else 0))
            }
            return true
        }
        return applyDescriptor(p!!, i, desc)
    }

    /** ValidateAndApplyPropertyDescriptor for an existing slot. */
    private fun applyDescriptor(p: PropertyMap, i: Int, desc: PropertyDescriptor): Boolean {
        if (desc.present == 0) return true
        val f = p.flags[i]
        val curAccessor = f and Attr.ACCESSOR != 0
        val curConfigurable = f and Attr.CONFIGURABLE != 0
        val curEnumerable = f and Attr.ENUMERABLE != 0
        if (!curConfigurable) {
            if (desc.hasConfigurable && desc.configurable) return false
            if (desc.hasEnumerable && desc.enumerable != curEnumerable) return false
            if (!desc.isGeneric && desc.isAccessor != curAccessor) return false
            if (curAccessor) {
                val a = p.values[i] as Accessor
                if (desc.hasGet && !Ops.sameValue(desc.getter, a.getter)) return false
                if (desc.hasSet && !Ops.sameValue(desc.setter, a.setter)) return false
            } else if (f and Attr.WRITABLE == 0) {
                if (desc.hasWritable && desc.writable) return false
                if (desc.hasValue && !Ops.sameValue(desc.value, p.values[i])) return false
            }
        }
        val configurable = if (desc.hasConfigurable) desc.configurable else curConfigurable
        val enumerable = if (desc.hasEnumerable) desc.enumerable else curEnumerable
        val ce = (if (configurable) Attr.CONFIGURABLE else 0) or (if (enumerable) Attr.ENUMERABLE else 0)
        if (!curAccessor && desc.isAccessor) {
            p.values[i] = Accessor(if (desc.hasGet) desc.getter else Undefined, if (desc.hasSet) desc.setter else Undefined)
            p.setFlags(i, Attr.ACCESSOR or ce)
        } else if (curAccessor && desc.isData) {
            p.values[i] = if (desc.hasValue) desc.value else Undefined
            p.setFlags(i, ce or (if (desc.hasWritable && desc.writable) Attr.WRITABLE else 0))
        } else if (curAccessor) {
            val a = p.values[i] as Accessor
            if (desc.hasGet) a.getter = desc.getter
            if (desc.hasSet) a.setter = desc.setter
            p.setFlags(i, Attr.ACCESSOR or ce)
        } else {
            if (desc.hasValue) p.values[i] = desc.value
            val w = if (desc.hasWritable) desc.writable else f and Attr.WRITABLE != 0
            p.setFlags(i, ce or (if (w) Attr.WRITABLE else 0))
        }
        return true
    }

    /** ValidateAndApplyPropertyDescriptor with an externally supplied current descriptor (for exotic objects). */
    fun validateAndApply(key: Any, extensible: Boolean, desc: PropertyDescriptor, current: PropertyDescriptor?): Boolean =
        Ops.isCompatiblePropertyDescriptor(extensible, desc, current)

    // ------------------------------------------------------------------ [[HasProperty]]

    open fun hasProperty(key: Any): Boolean {
        var o: JSObject = this
        while (true) {
            if (o !== this && o.special and SPECIAL_HAS != 0) return o.hasProperty(key)
            if (o.hasOwnProperty(key)) return true
            o = o.getPrototypeOf() ?: return false
        }
    }

    // ------------------------------------------------------------------ [[Get]]

    open fun get(key: Any, receiver: Any?): Any? {
        var o: JSObject = this
        while (true) {
            val v = o.getOwnValue(key, receiver)
            if (v !== NotFound) return v
            o = o.getPrototypeOf() ?: return Undefined
            if (o.special and SPECIAL_GET != 0) return o.get(key, receiver)
        }
    }

    fun get(key: Any): Any? = get(key, this)

    // ------------------------------------------------------------------ [[Set]]

    open fun set(key: Any, value: Any?, receiver: Any?): Boolean = ordinarySet(key, value, receiver)

    fun ordinarySet(key: Any, value: Any?, receiver: Any?): Boolean {
        var o: JSObject = this
        while (true) {
            if (o.special and EXOTIC_OWN == 0) {
                val p = o.props
                val i = p?.find(key) ?: -1
                if (i >= 0) {
                    val f = p!!.flags[i]
                    if (f and Attr.ACCESSOR != 0) {
                        val s = (p.values[i] as Accessor).setter
                        if (s !is JSObject) return false
                        s.call(receiver, arrayOf(value))
                        return true
                    }
                    if (f and Attr.WRITABLE == 0) return false
                    if (receiver === o) {
                        p.values[i] = value
                        return true
                    }
                    return setOnReceiver(key, value, receiver)
                }
            } else {
                val d = o.getOwnProperty(key)
                if (d != null) {
                    if (d.isAccessor) {
                        val s = d.setter
                        if (s !is JSObject) return false
                        s.call(receiver, arrayOf(value))
                        return true
                    }
                    if (!d.writable) return false
                    return setOnReceiver(key, value, receiver)
                }
            }
            val parent = o.getPrototypeOf() ?: break
            if (parent.special and SPECIAL_SET != 0) return parent.set(key, value, receiver)
            o = parent
        }
        return setOnReceiver(key, value, receiver)
    }

    /** Final step of OrdinarySetWithOwnDescriptor for data properties: define/update on the receiver. */
    protected fun setOnReceiver(key: Any, value: Any?, receiver: Any?): Boolean {
        if (receiver !is JSObject) return false
        if (receiver.special and EXOTIC_OWN == 0 && receiver.special and SPECIAL_GET == 0) {
            val p = receiver.props
            val i = p?.find(key) ?: -1
            if (i >= 0) {
                val f = p!!.flags[i]
                if (f and Attr.ACCESSOR != 0 || f and Attr.WRITABLE == 0) return false
                p.values[i] = value
                return true
            }
            if (!receiver.isExtensible()) return false
            receiver.ensureProps().add(key, value, Attr.ALL)
            return true
        }
        val existing = receiver.getOwnProperty(key)
        if (existing != null) {
            if (existing.isAccessor || !existing.writable) return false
            return receiver.defineOwnProperty(key, PropertyDescriptor().value(value))
        }
        return receiver.defineOwnProperty(key, PropertyDescriptor.data(value, Attr.ALL))
    }

    // ------------------------------------------------------------------ [[Delete]]

    open fun delete(key: Any): Boolean {
        val p = props ?: return true
        val i = p.find(key)
        if (i < 0) return true
        if (p.flags[i] and Attr.CONFIGURABLE == 0) return false
        p.removeAt(i)
        return true
    }

    // ------------------------------------------------------------------ [[OwnPropertyKeys]]

    open fun ownPropertyKeys(): MutableList<Any> = ordinaryOwnKeys(null)

    /** Ordinary key ordering; [extraIndices] (sorted) are prepended for exotic index storage. */
    fun ordinaryOwnKeys(extraIndices: List<Any>?): MutableList<Any> {
        val p = props
        val out = ArrayList<Any>((p?.count ?: 0) + (extraIndices?.size ?: 0))
        if (extraIndices != null) out.addAll(extraIndices)
        if (p == null) return out
        var indexKeys: ArrayList<Any>? = null
        if (p.hasIndexKeys) {
            indexKeys = ArrayList()
            p.forEachLive { i -> val k = p.keys[i]!!; if (k is Int) indexKeys.add(k) }
        }
        p.forEachLive { i ->
            val k = p.keys[i]!!
            if (k is String && k.length == 10 && PK.arrayIndex(k) >= 0) {
                if (indexKeys == null) indexKeys = ArrayList()
                indexKeys!!.add(k)
            }
        }
        if (indexKeys != null) {
            indexKeys!!.sortWith { a, b -> java.lang.Long.compare(PK.arrayIndex(a), PK.arrayIndex(b)) }
            if (extraIndices != null && extraIndices.isNotEmpty()) {
                indexKeys!!.addAll(0, extraIndices)
                indexKeys!!.sortWith { a, b -> java.lang.Long.compare(PK.arrayIndex(a), PK.arrayIndex(b)) }
                out.clear()
            }
            out.addAll(indexKeys!!)
        }
        p.forEachLive { i ->
            val k = p.keys[i]!!
            if (k is String && !(k.length == 10 && PK.arrayIndex(k) >= 0)) out.add(k)
        }
        p.forEachLive { i ->
            val k = p.keys[i]!!
            if (k is JSSymbol) out.add(k)
        }
        return out
    }

    // ------------------------------------------------------------------ helpers for builtins / runtime

    /** Defines (or overwrites) a data property directly, bypassing extensibility (initialization only). */
    fun defineOwn(key: Any, value: Any?, attrs: Int = Attr.WC) {
        val p = ensureProps()
        val i = p.find(key)
        if (i >= 0) {
            p.values[i] = value
            p.setFlags(i, attrs)
        } else p.add(key, value, attrs)
    }

    fun defineAccessor(key: Any, getter: Any?, setter: Any?, attrs: Int = Attr.CONFIGURABLE) {
        val p = ensureProps()
        val i = p.find(key)
        val a = Accessor(getter ?: Undefined, setter ?: Undefined)
        if (i >= 0) {
            p.values[i] = a
            p.setFlags(i, attrs or Attr.ACCESSOR)
        } else p.add(key, a, attrs or Attr.ACCESSOR)
    }

    /** CreateDataProperty */
    fun createDataProperty(key: Any, value: Any?): Boolean {
        if (special and SPECIAL_ALL == 0) {
            val p = props
            val i = p?.find(key) ?: -1
            if (i < 0) {
                if (!isExtensible()) return false
                ensureProps().add(key, value, Attr.ALL)
                return true
            }
        }
        return defineOwnProperty(key, PropertyDescriptor.data(value, Attr.ALL))
    }

    fun createDataPropertyOrThrow(key: Any, value: Any?) {
        if (!createDataProperty(key, value)) throw JSException.typeError("Cannot define property ${PK.toStringKey(key)}")
    }

    fun definePropertyOrThrow(key: Any, desc: PropertyDescriptor) {
        if (!defineOwnProperty(key, desc)) throw JSException.typeError("Cannot redefine property: ${PK.toStringKey(key)}")
    }

    fun setOrThrow(key: Any, value: Any?) {
        if (!set(key, value, this)) throw JSException.typeError("Cannot assign to read only property '${PK.toStringKey(key)}' of ${Ops.describe(this)}")
    }

    fun deleteOrThrow(key: Any) {
        if (!delete(key)) throw JSException.typeError("Cannot delete property '${PK.toStringKey(key)}' of ${Ops.describe(this)}")
    }

    override fun toString(): String = "[object $className]"
}
