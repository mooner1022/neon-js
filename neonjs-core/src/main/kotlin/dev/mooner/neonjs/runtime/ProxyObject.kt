package dev.mooner.neonjs.runtime

/** Proxy exotic object (ECMA-262 10.5). */
class ProxyObject(@JvmField var target: JSObject?, @JvmField var handler: JSObject?) : JSObject(null) {
    init {
        special = special or SPECIAL_ALL
        val t = target!!
        if (t.isCallable) {
            special = special or CALLABLE
            if (t.isConstructor) special = special or CONSTRUCTOR
        }
    }

    override val className: String get() = "Object"

    private fun check(): Pair<JSObject, JSObject> {
        val h = handler ?: throw JSException.typeError("Cannot perform operation on a revoked proxy")
        return target!! to h
    }

    private fun trap(h: JSObject, name: String): JSObject? {
        val t = Ops.getMethod(h, name)
        return if (t === Undefined) null else t as JSObject
    }

    override fun getPrototypeOf(): JSObject? {
        val (t, h) = check()
        val tr = trap(h, "getPrototypeOf") ?: return t.getPrototypeOf()
        val p = tr.call(h, arrayOf(t))
        if (p !is JSObject && p !== Null) throw JSException.typeError("'getPrototypeOf' on proxy: trap returned neither object nor null")
        val proto = p as? JSObject
        if (t.isExtensible()) return proto
        val tp = t.getPrototypeOf()
        if (proto !== tp) throw JSException.typeError("'getPrototypeOf' on proxy: proxy target is non-extensible but the trap did not return its actual prototype")
        return proto
    }

    override fun setPrototypeOf(p: JSObject?): Boolean {
        val (t, h) = check()
        val tr = trap(h, "setPrototypeOf") ?: return t.setPrototypeOf(p)
        val r = Ops.toBoolean(tr.call(h, arrayOf(t, p ?: Null)))
        if (!r) return false
        if (t.isExtensible()) return true
        if (t.getPrototypeOf() !== p) throw JSException.typeError("'setPrototypeOf' on proxy: trap returned truish for setting a new prototype on the non-extensible proxy target")
        return true
    }

    override fun isExtensible(): Boolean {
        val (t, h) = check()
        val tr = trap(h, "isExtensible") ?: return t.isExtensible()
        val r = Ops.toBoolean(tr.call(h, arrayOf(t)))
        if (r != t.isExtensible()) throw JSException.typeError("'isExtensible' on proxy: trap result does not reflect extensibility of proxy target")
        return r
    }

    override fun preventExtensions(): Boolean {
        val (t, h) = check()
        val tr = trap(h, "preventExtensions") ?: return t.preventExtensions()
        val r = Ops.toBoolean(tr.call(h, arrayOf(t)))
        if (r && t.isExtensible()) throw JSException.typeError("'preventExtensions' on proxy: trap returned truish but the proxy target is extensible")
        return r
    }

    override fun getOwnProperty(key: Any): PropertyDescriptor? {
        val (t, h) = check()
        val tr = trap(h, "getOwnPropertyDescriptor") ?: return t.getOwnProperty(key)
        val res = tr.call(h, arrayOf(t, PK.toValue(key)))
        if (res !is JSObject && res !== Undefined) throw JSException.typeError("'getOwnPropertyDescriptor' on proxy: trap returned neither object nor undefined for property '${Ops.describeKey(key)}'")
        val targetDesc = t.getOwnProperty(key)
        if (res === Undefined) {
            if (targetDesc == null) return null
            if (!targetDesc.configurable) throw JSException.typeError("'getOwnPropertyDescriptor' on proxy: trap returned undefined for property '${Ops.describeKey(key)}' which is non-configurable in the proxy target")
            if (!t.isExtensible()) throw JSException.typeError("'getOwnPropertyDescriptor' on proxy: trap returned undefined for property '${Ops.describeKey(key)}' which exists in the non-extensible proxy target")
            return null
        }
        val ext = t.isExtensible()
        val rd = Ops.completePropertyDescriptor(Ops.toPropertyDescriptor(res))
        if (!Ops.isCompatiblePropertyDescriptor(ext, rd, targetDesc)) throw JSException.typeError("'getOwnPropertyDescriptor' on proxy: trap returned descriptor for property '${Ops.describeKey(key)}' that is incompatible with the existing property in the proxy target")
        if (!rd.configurable) {
            if (targetDesc == null || targetDesc.configurable) throw JSException.typeError("'getOwnPropertyDescriptor' on proxy: trap reported non-configurability for property '${Ops.describeKey(key)}' which is either non-existent or configurable in the proxy target")
            if (rd.hasWritable && !rd.writable && targetDesc.writable) throw JSException.typeError("'getOwnPropertyDescriptor' on proxy: trap reported non-configurable and writable for property '${Ops.describeKey(key)}' which is non-configurable, non-writable in the proxy target")
        }
        return rd
    }

    override fun getOwnValue(key: Any, receiver: Any?): Any? {
        val d = getOwnProperty(key) ?: return NotFound
        if (d.isAccessor) {
            val g = d.getter
            return if (g is JSObject) g.call(receiver, EMPTY_ARGS) else Undefined
        }
        return d.value
    }

    override fun hasOwnProperty(key: Any): Boolean = getOwnProperty(key) != null

    override fun defineOwnProperty(key: Any, desc: PropertyDescriptor): Boolean {
        val (t, h) = check()
        val tr = trap(h, "defineProperty") ?: return t.defineOwnProperty(key, desc)
        val descObj = Ops.fromPropertyDescriptor(Agent.currentRealm(), desc)
        val r = Ops.toBoolean(tr.call(h, arrayOf(t, PK.toValue(key), descObj)))
        if (!r) return false
        val targetDesc = t.getOwnProperty(key)
        val ext = t.isExtensible()
        val settingConfigFalse = desc.hasConfigurable && !desc.configurable
        if (targetDesc == null) {
            if (!ext) throw JSException.typeError("'defineProperty' on proxy: trap returned truish for adding property '${Ops.describeKey(key)}'  to the non-extensible proxy target")
            if (settingConfigFalse) throw JSException.typeError("'defineProperty' on proxy: trap returned truish for defining non-configurable property '${Ops.describeKey(key)}' which is either non-existent or configurable in the proxy target")
        } else {
            if (!Ops.isCompatiblePropertyDescriptor(ext, desc, targetDesc)) throw JSException.typeError("'defineProperty' on proxy: trap returned truish for adding property '${Ops.describeKey(key)}'  that is incompatible with the existing property in the proxy target")
            if (settingConfigFalse && targetDesc.configurable) throw JSException.typeError("'defineProperty' on proxy: trap returned truish for defining non-configurable property '${Ops.describeKey(key)}' which is either non-existent or configurable in the proxy target")
            if (targetDesc.isData && !targetDesc.configurable && targetDesc.writable) {
                if (desc.hasWritable && !desc.writable) throw JSException.typeError("'defineProperty' on proxy: trap returned truish for defining non-configurable property '${Ops.describeKey(key)}' which cannot be non-writable, unless there exists a corresponding non-configurable, non-writable own property of the target object.")
            }
        }
        return true
    }

    override fun hasProperty(key: Any): Boolean {
        val (t, h) = check()
        val tr = trap(h, "has") ?: return t.hasProperty(key)
        val r = Ops.toBoolean(tr.call(h, arrayOf(t, PK.toValue(key))))
        if (!r) {
            val td = t.getOwnProperty(key)
            if (td != null) {
                if (!td.configurable) throw JSException.typeError("'has' on proxy: trap returned falsish for property '${Ops.describeKey(key)}' which exists in the proxy target as non-configurable")
                if (!t.isExtensible()) throw JSException.typeError("'has' on proxy: trap returned falsish for property '${Ops.describeKey(key)}' but the proxy target is not extensible")
            }
        }
        return r
    }

    override fun get(key: Any, receiver: Any?): Any? {
        val (t, h) = check()
        val tr = trap(h, "get") ?: return t.get(key, receiver)
        val v = tr.call(h, arrayOf(t, PK.toValue(key), receiver))
        val td = t.getOwnProperty(key)
        if (td != null && !td.configurable) {
            if (td.isData && !td.writable && !Ops.sameValue(v, td.value)) throw JSException.typeError("'get' on proxy: property '${Ops.describeKey(key)}' is a read-only and non-configurable data property on the proxy target but the proxy did not return its actual value")
            if (td.isAccessor && td.getter === Undefined && v !== Undefined) throw JSException.typeError("'get' on proxy: property '${Ops.describeKey(key)}' is a non-configurable accessor property on the proxy target and does not have a getter function, but the trap did not return 'undefined'")
        }
        return v
    }

    override fun set(key: Any, value: Any?, receiver: Any?): Boolean {
        val (t, h) = check()
        val tr = trap(h, "set") ?: return t.set(key, value, receiver)
        val r = Ops.toBoolean(tr.call(h, arrayOf(t, PK.toValue(key), value, receiver)))
        if (!r) return false
        val td = t.getOwnProperty(key)
        if (td != null && !td.configurable) {
            if (td.isData && !td.writable && !Ops.sameValue(value, td.value)) throw JSException.typeError("'set' on proxy: trap returned truish for property '${Ops.describeKey(key)}' which exists in the proxy target as a non-configurable and non-writable data property with a different value")
            if (td.isAccessor && td.setter === Undefined) throw JSException.typeError("'set' on proxy: trap returned truish for property '${Ops.describeKey(key)}' which exists in the proxy target as a non-configurable and non-writable accessor property without a setter")
        }
        return true
    }

    override fun delete(key: Any): Boolean {
        val (t, h) = check()
        val tr = trap(h, "deleteProperty") ?: return t.delete(key)
        val r = Ops.toBoolean(tr.call(h, arrayOf(t, PK.toValue(key))))
        if (!r) return false
        val td = t.getOwnProperty(key) ?: return true
        if (!td.configurable) throw JSException.typeError("'deleteProperty' on proxy: trap returned truish for property '${Ops.describeKey(key)}' which is non-configurable in the proxy target")
        if (!t.isExtensible()) throw JSException.typeError("'deleteProperty' on proxy: trap returned truish for property '${Ops.describeKey(key)}' but the proxy target is non-extensible")
        return true
    }

    override fun ownPropertyKeys(): MutableList<Any> {
        val (t, h) = check()
        val tr = trap(h, "ownKeys") ?: return t.ownPropertyKeys()
        val arr = tr.call(h, arrayOf(t))
        if (arr !is JSObject) throw JSException.typeError("CreateListFromArrayLike called on non-object")
        val raw = Ops.createListFromArrayLike(arr, onlyPropertyKeys = true)
        val keys = ArrayList<Any>(raw.size)
        val seen = HashSet<Any>()
        for (v in raw) {
            val k: Any = if (v is JSSymbol) v else PK.fromString(v.toString())
            if (!seen.add(k)) throw JSException.typeError("'ownKeys' on proxy: trap returned duplicate entries")
            keys.add(k)
        }
        val ext = t.isExtensible()
        val targetKeys = t.ownPropertyKeys()
        val configurable = ArrayList<Any>()
        val nonConfigurable = ArrayList<Any>()
        for (k in targetKeys) {
            val d = t.getOwnProperty(k)
            if (d != null && !d.configurable) nonConfigurable.add(k) else configurable.add(k)
        }
        if (ext && nonConfigurable.isEmpty()) return keys
        val unchecked = LinkedHashSet<Any>(keys)
        for (k in nonConfigurable) {
            if (!unchecked.remove(k)) throw JSException.typeError("'ownKeys' on proxy: trap result did not include '${Ops.describeKey(k)}'")
        }
        if (ext) return keys
        for (k in configurable) {
            if (!unchecked.remove(k)) throw JSException.typeError("'ownKeys' on proxy: trap result did not include '${Ops.describeKey(k)}'")
        }
        if (unchecked.isNotEmpty()) throw JSException.typeError("'ownKeys' on proxy: trap returned extra keys but proxy target is non-extensible")
        return keys
    }

    override fun call(thisArg: Any?, args: Array<Any?>): Any? {
        val (t, h) = check()
        val tr = trap(h, "apply") ?: return t.call(thisArg, args)
        val realm = Agent.currentRealm()
        return tr.call(h, arrayOf(t, thisArg, JSArray.of(realm.arrayPrototype, args.copyOf())))
    }

    override fun construct(args: Array<Any?>, newTarget: JSObject): Any? {
        val (t, h) = check()
        val tr = trap(h, "construct") ?: return t.construct(args, newTarget)
        val realm = Agent.currentRealm()
        val r = tr.call(h, arrayOf(t, JSArray.of(realm.arrayPrototype, args.copyOf()), newTarget))
        if (r !is JSObject) throw JSException.typeError("proxy [[Construct]] must return an object")
        return r
    }
}
