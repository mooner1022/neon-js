package dev.mooner.neonjs.ext

import dev.mooner.neonjs.builtins.JSArrayBuffer
import dev.mooner.neonjs.builtins.JSDataView
import dev.mooner.neonjs.builtins.JSTypedArray
import dev.mooner.neonjs.builtins.typeErr
import dev.mooner.neonjs.runtime.*

/**
 * A web interface as WebIDL's ECMAScript binding defines it: an interface object that must be called with `new` (and
 * always throws when the interface has no constructor), a prototype whose operations and attributes are enumerable
 * properties and whose @@toStringTag is the interface name, constants on both, and inheritance on both chains. The
 * interface object and its prototype are the realm's intrinsics `%Name%` and `%Name.prototype%`.
 */
internal class WebInterface private constructor(@JvmField val realm: Realm, @JvmField val name: String, @JvmField val proto: JSObject, @JvmField val ctor: NativeFunction) {
    /** A regular operation: an enumerable method of the prototype. */
    fun operation(name: Any, length: Int, impl: NativeImpl): NativeFunction {
        val f = NativeFunction(realm, name, length, impl)
        proto.defineOwn(name, f, Attr.ALL)
        return f
    }

    /**
     * An operation returning a promise: [impl] returns the value to resolve it with, and an exception it throws (the
     * receiver check and argument conversions included) rejects the promise instead of propagating, as WebIDL has it.
     */
    fun promiseOperation(name: Any, length: Int, impl: NativeImpl): NativeFunction = operation(name, length) { f, t, a, nt ->
        try {
            dev.mooner.neonjs.vm.Promises.promiseResolve(f.realm, f.realm.promiseConstructor, impl.invoke(f, t, a, nt))
        } catch (e: JSException) {
            val p = dev.mooner.neonjs.vm.Promises.newPromise(f.realm)
            dev.mooner.neonjs.vm.Promises.rejectPromise(f.realm, p, e.value)
            p
        }
    }

    /** A static operation: an enumerable method of the interface object. */
    fun staticOperation(name: String, length: Int, impl: NativeImpl): NativeFunction {
        val f = NativeFunction(realm, name, length, impl)
        ctor.defineOwn(name, f, Attr.ALL)
        return f
    }

    /** A regular attribute: an enumerable accessor of the prototype, read-only without [set]. */
    fun attribute(name: String, get: NativeImpl, set: NativeImpl? = null) {
        val g = NativeFunction(realm, name, 0, get, namePrefix = "get")
        val s = if (set == null) Undefined else NativeFunction(realm, name, 1, set, namePrefix = "set")
        proto.defineAccessor(name, g, s, Attr.ENUMERABLE or Attr.CONFIGURABLE)
    }

    /** An iterator of a pair iterable: the object iterated, the kind (0 keys, 1 values, 2 entries) and the next index. */
    class PairIterator(proto: JSObject?, @JvmField val target: JSObject, @JvmField val kind: Int) : JSObject(proto) {
        @JvmField var index = 0
    }

    /**
     * A pair iterator declaration (`iterable<K, V>`): `entries`, `keys`, `values` and `forEach` on the prototype,
     * `@@iterator` the same function as `entries`, and iterators inheriting from a "<Name> Iterator" prototype that
     * inherits from %Iterator.prototype%. [pairs] gives the pairs of a receiver (an instance of [cls]) and is asked
     * again at each step, so iteration sees changes made meanwhile.
     */
    fun <T : JSObject> pairIterable(cls: Class<T>, pairs: (T) -> List<Pair<Any?, Any?>>) {
        fun self(t: Any?, member: String): T =
            if (cls.isInstance(t)) cls.cast(t) else typeErr("$name.$member called on ${Ops.describe(t)}, which is not a $name")
        val iface = name
        val itProto = JSObject(realm.iteratorPrototype)
        itProto.defineOwn("next", NativeFunction(realm, "next", 0, { f, t, _, _ ->
            val it = t as? PairIterator
            if (it == null || it.proto !== itProto) typeErr("$iface Iterator.next called on ${Ops.describe(t)}")
            val list = pairs(cls.cast(it.target))
            if (it.index >= list.size) return@NativeFunction dev.mooner.neonjs.vm.Iteration.createIterResult(f.realm, Undefined, true)
            val (k, v) = list[it.index++]
            val value = when (it.kind) {
                0 -> k
                1 -> v
                else -> dev.mooner.neonjs.builtins.Builtins.arrayOf(f.realm, listOf(k, v))
            }
            dev.mooner.neonjs.vm.Iteration.createIterResult(f.realm, value, false)
        }), Attr.ALL)
        itProto.defineOwn(JSSymbol.toStringTag, "$iface Iterator", Attr.CONFIGURABLE)
        fun iterator(kind: Int, member: String) = NativeImpl { _, t, _, _ -> PairIterator(itProto, self(t, member), kind) }
        val entries = operation("entries", 0, iterator(2, "entries"))
        operation("keys", 0, iterator(0, "keys"))
        operation("values", 0, iterator(1, "values"))
        operation("forEach", 1) { _, t, a, _ ->
            val target = self(t, "forEach")
            val cb = Idl.callback(a.arg(0), "$iface.forEach callback")
            val thisArg = a.arg(1)
            var i = 0
            while (true) {
                val list = pairs(target)
                if (i >= list.size) break
                val (k, v) = list[i++]
                Ops.call(cb, thisArg, arrayOf(v, k, target))
            }
            Undefined
        }
        proto.defineOwn(JSSymbol.iterator, entries, Attr.WC)
    }

    /** A constant: an enumerable, read-only, non-configurable property of the interface object and the prototype. */
    fun constant(name: String, value: Double) {
        ctor.defineOwn(name, value, Attr.ENUMERABLE)
        proto.defineOwn(name, value, Attr.ENUMERABLE)
    }

    companion object {
        /**
         * Defines the interface [name] in [realm] and binds it on the global object. With [construct], `new` creates
         * the instance from the arguments and the prototype NewTarget designates; without it the interface object
         * throws a TypeError. [parent] names the inherited interface (defined before); [protoParent] overrides the
         * prototype's prototype (`DOMException.prototype` inherits from `Error.prototype`).
         */
        fun define(
            realm: Realm, name: String, length: Int, parent: String? = null, protoParent: JSObject? = null,
            construct: ((args: Array<Any?>, proto: JSObject) -> JSObject)? = null,
        ): WebInterface {
            val proto = JSObject(protoParent ?: parent?.let { realm.intrinsic("%$it.prototype%") } ?: realm.objectPrototype)
            val protoKey = "%$name.prototype%"
            val ctor = NativeFunction(realm, name, length, { _, _, args, nt ->
                if (nt == null) typeErr("Failed to construct '$name': use the 'new' operator")
                if (construct == null) typeErr("Illegal constructor")
                construct(args, Ops.getPrototypeFromConstructor(nt) { it.intrinsics[protoKey] ?: it.objectPrototype })
            }, isConstructor = true, proto = parent?.let { realm.intrinsic("%$it%") } ?: realm.functionPrototype)
            ctor.defineOwn("prototype", proto, Attr.NONE)
            proto.defineOwn("constructor", ctor, Attr.WC)
            proto.defineOwn(JSSymbol.toStringTag, name, Attr.CONFIGURABLE)
            realm.intrinsics[protoKey] = proto
            realm.intrinsics["%$name%"] = ctor
            realm.globalObject.defineOwn(name, ctor, Attr.WC)
            return WebInterface(realm, name, proto, ctor)
        }

        /** An operation of the global object (`setTimeout`, `atob`...): an enumerable function property of it. */
        fun globalOperation(realm: Realm, name: String, length: Int, impl: NativeImpl): NativeFunction {
            val f = NativeFunction(realm, name, length, impl)
            realm.globalObject.defineOwn(name, f, Attr.ALL)
            return f
        }
    }
}

/** WebIDL conversions of arguments and dictionary members, and receiver checks. */
internal object Idl {
    /** The receiver of [member] of interface [iface] as its implementation class, or a TypeError ("illegal invocation"). */
    inline fun <reified T> self(t: Any?, iface: String, member: String): T =
        t as? T ?: typeErr("$iface.$member called on ${Ops.describe(t)}, which is not a $iface")

    /** Throws a TypeError unless [args] has at least [n] elements. */
    fun required(args: Array<Any?>, n: Int, what: String) {
        if (args.size < n) typeErr("$what: $n argument${if (n == 1) "" else "s"} required, but only ${args.size} present")
    }

    fun domString(v: Any?): String = if (v is CharSequence) v.toString() else Ops.toString(v)

    /** USVString: a DOMString with each lone surrogate replaced by U+FFFD. */
    fun usvString(v: Any?): String = toWellFormed(domString(v))

    fun toWellFormed(s: String): String {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isSurrogate(c)) {
                if (Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                    i += 2
                    continue
                }
                val sb = StringBuilder(s.length).append(s, 0, i)
                while (i < s.length) {
                    val d = s[i]
                    if (Character.isHighSurrogate(d) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                        sb.append(d).append(s[i + 1])
                        i += 2
                        continue
                    }
                    sb.append(if (Character.isSurrogate(d)) '�' else d)
                    i++
                }
                return sb.toString()
            }
            i++
        }
        return s
    }

    /** ByteString: a DOMString whose code units are all at most 0xFF, else a TypeError. */
    fun byteString(v: Any?, what: String): String {
        val s = domString(v)
        for (c in s) if (c.code > 0xFF) typeErr("$what: not a ByteString (contains U+${"%04X".format(c.code)})")
        return s
    }

    /** A dictionary argument: null for undefined or null (all members default), the object, else a TypeError. */
    fun dictionary(v: Any?, what: String): JSObject? = when (v) {
        Undefined, Null -> null
        is JSObject -> v
        else -> typeErr("$what is not an object")
    }

    /** A dictionary member: undefined when the dictionary or the member is missing. */
    fun member(dict: JSObject?, key: String): Any? = if (dict == null) Undefined else dict.get(key, dict)

    /** A callback function argument. */
    fun callback(v: Any?, what: String): JSObject =
        if (v is JSObject && v.isCallable) v else typeErr("$what is not a function")

    /** `double`: a finite number, else a TypeError. */
    fun double(v: Any?, what: String): Double {
        val d = Ops.toNumber(v)
        if (d.isNaN() || d.isInfinite()) typeErr("$what is not a finite number")
        return d
    }

    /**
     * ConvertToInt for an integer type of [bits] bits ([signed] or not; 64-bit types are limited to the safe integers),
     * with [EnforceRange] (out of range is a TypeError) or [Clamp] (clamped, rounded half to even) when given.
     */
    fun integer(v: Any?, bits: Int, signed: Boolean, what: String, enforceRange: Boolean = false, clamp: Boolean = false): Double {
        val upper: Double
        val lower: Double
        if (bits == 64) {
            upper = 9007199254740991.0
            lower = if (signed) -9007199254740991.0 else 0.0
        } else {
            upper = if (signed) Math.pow(2.0, bits - 1.0) - 1 else Math.pow(2.0, bits.toDouble()) - 1
            lower = if (signed) -Math.pow(2.0, bits - 1.0) else 0.0
        }
        var x = Ops.toNumber(v)
        if (x == 0.0) x = 0.0
        if (enforceRange) {
            if (x.isNaN() || x.isInfinite()) typeErr("$what is not a finite number")
            x = truncate(x)
            if (x < lower || x > upper) typeErr("$what is outside the range $lower..$upper")
            return x + 0.0
        }
        if (!x.isNaN() && clamp) {
            x = minOf(maxOf(x, lower), upper)
            return Math.rint(x) + 0.0
        }
        if (x.isNaN() || x.isInfinite() || x == 0.0) return 0.0
        x = truncate(x)
        if (bits == 64) {
            // modulo 2^64, then the range of the type, as WebIDL does for (unsigned) long long
            val m = java.math.BigDecimal(x).toBigInteger().mod(java.math.BigInteger.ONE.shiftLeft(64))
            val r = if (signed && m.testBit(63)) m.subtract(java.math.BigInteger.ONE.shiftLeft(64)) else m
            return r.toDouble() + 0.0
        }
        val mod = Math.pow(2.0, bits.toDouble())
        x = ((x % mod) + mod) % mod
        if (signed && x >= mod / 2) x -= mod
        return x + 0.0
    }

    private fun truncate(x: Double) = if (x < 0) Math.ceil(x) else Math.floor(x)

    /**
     * A `record<K, V>` argument: for each own key of [o] (symbols included: their descriptors are asked for too), the
     * enumerable ones converted by [key] (a TypeError for a symbol) and their values by [value], in order.
     */
    fun <K, V> record(o: JSObject, key: (Any?) -> K, value: (Any?) -> V): List<Pair<K, V>> {
        val out = ArrayList<Pair<K, V>>()
        for (k in o.ownPropertyKeys()) {
            val d = o.getOwnProperty(k) ?: continue
            if (!d.enumerable) continue
            val typedKey = key(if (k is JSSymbol) k else PK.toStringKey(k))
            out.add(typedKey to value(o.get(k, o)))
        }
        return out
    }

    /** A `sequence<T>` argument: the values of an iterable object, each converted by [item]. */
    fun <T> sequence(realm: Realm, v: Any?, what: String, item: (Any?) -> T): List<T> {
        val m = if (v is JSObject) Ops.getMethod(v, JSSymbol.iterator) else Undefined
        if (m === Undefined) typeErr("$what is not iterable")
        val rec = dev.mooner.neonjs.vm.Iteration.fromMethod(v, m)
        val out = ArrayList<T>()
        while (true) {
            val x = dev.mooner.neonjs.vm.Iteration.stepValue(rec)
            if (x === NotFound) return out
            out.add(item(x))
            if (out.size and 1023 == 0) realm.agent.checkInterrupt()
        }
    }

    /**
     * A copy of the bytes of a BufferSource (ArrayBuffer or view; a SharedArrayBuffer or a view of one only when
     * [allowShared]), or a TypeError. A detached buffer gives no bytes.
     */
    fun bytes(v: Any?, what: String, allowShared: Boolean): ByteArray {
        val buffer = when (v) {
            is JSArrayBuffer -> v
            is JSTypedArray -> v.buffer
            is JSDataView -> v.buffer
            else -> typeErr("$what is not an ArrayBuffer or a view of one")
        }
        if (buffer.isShared && !allowShared) typeErr("$what is a SharedArrayBuffer or a view of one")
        return when (v) {
            is JSArrayBuffer -> v.data.copyOfRange(0, v.byteLength())
            is JSTypedArray -> v.buffer.data.copyOfRange(v.byteOffset, v.byteOffset + maxOf(v.lengthOrOOB(), 0) * v.type.size)
            else -> (v as JSDataView).let { d -> d.buffer.data.copyOfRange(d.byteOffset, d.byteOffset + maxOf(d.byteLengthOrOOB(), 0)) }
        }
    }
}
