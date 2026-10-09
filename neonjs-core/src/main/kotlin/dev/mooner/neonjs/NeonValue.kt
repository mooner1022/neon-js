package dev.mooner.neonjs

import dev.mooner.neonjs.interop.HostClassObject
import dev.mooner.neonjs.interop.HostObject
import dev.mooner.neonjs.runtime.*
import dev.mooner.neonjs.vm.JSPromise
import java.math.BigInteger

/**
 * Host-side handle to a JS value of a [NeonContext]. All operations enter the context (and are serialized with
 * other users of the context).
 */
class NeonValue internal constructor(val context: NeonContext, @PublishedApi internal val raw: Any?) {
    val isUndefined get() = raw === Undefined || raw == null
    val isNull get() = raw === Null
    val isNullish get() = isUndefined || isNull
    val isBoolean get() = raw is Boolean
    val isNumber get() = raw is Double
    val isString get() = raw is CharSequence
    val isBigInt get() = raw is BigInteger
    val isSymbol get() = raw is JSSymbol
    val isObject get() = raw is JSObject
    val isFunction get() = raw is JSObject && raw.isCallable
    val isArray get() = raw is JSArray
    val isPromise get() = raw is JSPromise
    val isError get() = raw is JSErrorObject
    val isHostObject get() = raw is HostObject
    val isHostClass get() = raw is HostClassObject

    fun asBoolean(): Boolean = raw as? Boolean ?: throw ClassCastException("not a boolean: ${typeOf()}")
    fun asDouble(): Double = raw as? Double ?: throw ClassCastException("not a number: ${typeOf()}")
    fun asInt(): Int {
        val d = asDouble()
        val i = d.toInt()
        if (i.toDouble() != d) throw ArithmeticException("number $d does not fit in an int")
        return i
    }
    /** A number, or a BigInt (host longs beyond 2^53 - 1 arrive as BigInts), that is exactly a long. */
    fun asLong(): Long {
        if (raw is BigInteger) {
            if (raw.bitLength() >= 64) throw ArithmeticException("bigint $raw does not fit in a long")
            return raw.toLong()
        }
        val d = asDouble()
        val l = d.toLong()
        if (l.toDouble() != d) throw ArithmeticException("number $d does not fit in a long")
        return l
    }
    /** A JS string, or the text of a host `CharSequence` (a StringBuilder, Android's Spanned...). */
    fun asString(): String = (raw as? CharSequence ?: (raw as? HostObject)?.target as? CharSequence)?.toString()
        ?: throw ClassCastException("not a string: ${typeOf()}")
    fun asBigInteger(): BigInteger = raw as? BigInteger ?: throw ClassCastException("not a bigint: ${typeOf()}")

    @Suppress("UNCHECKED_CAST")
    fun <T> asHostObject(): T = (raw as? HostObject)?.target as? T ?: throw ClassCastException("not a host object: ${typeOf()}")

    /** Converts to a host type (numbers, strings, collections, functional interfaces, host objects...). */
    fun <T> `as`(type: Class<T>): T = context.call {
        @Suppress("UNCHECKED_CAST")
        context.bridge.toHost(raw, type) as T
    }

    inline fun <reified T> to(): T = `as`(T::class.java)

    /** A JS `Date` or `Temporal.Instant` as an [java.time.Instant] (RangeError for an invalid Date). */
    fun asInstant(): java.time.Instant = `as`(java.time.Instant::class.java)

    fun typeOf(): String = Ops.typeOf(raw)

    // ------------------------------------------------------------------ members

    private fun obj(): JSObject = raw as? JSObject ?: throw IllegalStateException("not an object: ${typeOf()}")

    fun getMember(key: String): NeonValue = context.call {
        NeonValue(context, Ops.getV(raw, PK.fromString(key)))
    }

    fun putMember(key: String, value: Any?) = context.call {
        obj().setOrThrow(PK.fromString(key), context.bridge.toJS(value))
    }

    fun hasMember(key: String): Boolean = context.call { raw is JSObject && raw.hasProperty(PK.fromString(key)) }

    fun removeMember(key: String): Boolean = context.call { obj().delete(PK.fromString(key)) }

    /** Own enumerable string keys. */
    fun memberKeys(): Set<String> = context.call {
        val o = obj()
        val out = LinkedHashSet<String>()
        for (k in o.ownPropertyKeys()) {
            if (k is JSSymbol) continue
            val d = o.getOwnProperty(k) ?: continue
            if (d.enumerable) out.add(PK.toStringKey(k))
        }
        out
    }

    val arraySize: Long get() = context.call { Ops.lengthOfArrayLike(obj()) }

    fun getElement(index: Long): NeonValue = context.call {
        val o = obj()
        NeonValue(context, o.get(PK.fromIndex(index), o))
    }

    fun setElement(index: Long, value: Any?) = context.call { obj().setOrThrow(PK.fromIndex(index), context.bridge.toJS(value)) }

    // ------------------------------------------------------------------ calls

    /** Calls this function with `this` undefined. */
    fun call(vararg args: Any?): NeonValue = callWithThis(null, *args)

    fun callWithThis(thisArg: Any?, vararg args: Any?): NeonValue = context.call {
        val b = context.bridge
        val f = raw as? JSObject ?: throw JSException.typeError("${Ops.describe(raw)} is not a function")
        NeonValue(context, Ops.call(f, if (thisArg == null) Undefined else b.toJS(thisArg), Array(args.size) { b.toJS(args[it]) }))
    }

    fun callMember(name: String, vararg args: Any?): NeonValue = context.call {
        val b = context.bridge
        NeonValue(context, Ops.invoke(raw, PK.fromString(name), Array(args.size) { b.toJS(args[it]) }))
    }

    fun newInstance(vararg args: Any?): NeonValue = context.call {
        val b = context.bridge
        NeonValue(context, Ops.construct(raw, Array(args.size) { b.toJS(args[it]) }))
    }

    /**
     * For promises: runs pending jobs until the promise settles and returns its value; throws [NeonException] if it
     * is rejected. While it depends on external events (host futures, Atomics.waitAsync) waits up to [timeoutMillis]
     * for them; [IllegalStateException] if it is still pending after that or once nothing more can settle it.
     */
    @JvmOverloads
    fun await(timeoutMillis: Long = Long.MAX_VALUE): NeonValue = context.call {
        val p = raw as? JSPromise ?: return@call this
        context.pumpUntil(timeoutMillis) { p.state != JSPromise.PENDING }
        when (p.state) {
            JSPromise.FULFILLED -> NeonValue(context, p.result)
            JSPromise.REJECTED -> throw JSException(p.result)
            else -> throw IllegalStateException("promise is still pending")
        }
    }

    override fun toString(): String = try {
        context.call { Ops.toDisplayString(if (raw is JSObject && raw !is HostObject) Ops.toString(raw) else raw) }
    } catch (_: Exception) {
        "[${typeOf()}]"
    }

    override fun equals(other: Any?): Boolean = other is NeonValue && other.context === context && Ops.sameValue(raw, other.raw)
    override fun hashCode(): Int = when (val r = raw) {
        is CharSequence -> r.toString().hashCode()
        null -> 0
        else -> r.hashCode()
    }
}
