package dev.mooner.neonjs.runtime

/** Base class for function objects that carry a `[[Realm]]`. */
abstract class JSFunction(@JvmField val realm: Realm, proto: JSObject?) : JSObject(proto) {
    init {
        special = special or CALLABLE
    }

    override val className: String get() = "Function"

    /** Best-effort name for messages and stack traces (does not invoke getters). */
    open fun debugName(): String {
        val p = props ?: return ""
        val i = p.find("name")
        if (i < 0 || p.flags[i] and Attr.ACCESSOR != 0) return ""
        val v = p.values[i]
        return if (v is CharSequence) v.toString() else ""
    }

    /** SetFunctionName */
    fun setFunctionName(key: Any, prefix: String? = null) {
        var name = PK.functionName(key)
        if (prefix != null) name = if (name.isEmpty()) "$prefix " else "$prefix $name"
        defineOwn("name", name, Attr.CONFIGURABLE)
    }

    /** Source text for Function.prototype.toString. */
    open fun sourceText(): String = "function ${debugName()}() { [native code] }"
}

fun interface NativeImpl {
    fun invoke(f: NativeFunction, thisArg: Any?, args: Array<Any?>, newTarget: JSObject?): Any?
}

/** Builtin function implemented in Kotlin. */
open class NativeFunction(
    realm: Realm,
    name: Any,
    length: Int,
    @JvmField val impl: NativeImpl,
    isConstructor: Boolean = false,
    proto: JSObject? = realm.functionPrototype,
    namePrefix: String? = null,
) : JSFunction(realm, proto) {
    /** Optional internal slots for builtins that need state (e.g. promise resolving functions). */
    @JvmField var slot0: Any? = null

    init {
        if (isConstructor) special = special or CONSTRUCTOR
        defineOwn("length", length.toDouble(), Attr.CONFIGURABLE)
        setFunctionName(name, namePrefix)
    }

    override fun call(thisArg: Any?, args: Array<Any?>): Any? {
        val ag = realm.agent
        val prev = ag.currentRealm
        if (prev === realm) return impl.invoke(this, thisArg, args, null)
        ag.currentRealm = realm
        try {
            return impl.invoke(this, thisArg, args, null)
        } finally {
            ag.currentRealm = prev
        }
    }

    override fun construct(args: Array<Any?>, newTarget: JSObject): Any? {
        val ag = realm.agent
        val prev = ag.currentRealm
        if (prev === realm) return impl.invoke(this, Undefined, args, newTarget)
        ag.currentRealm = realm
        try {
            return impl.invoke(this, Undefined, args, newTarget)
        } finally {
            ag.currentRealm = prev
        }
    }

    override fun sourceText(): String = "function ${debugName()}() { [native code] }"
}

/** Bound function exotic object. */
class BoundFunction(
    @JvmField val target: JSObject,
    @JvmField val boundThis: Any?,
    @JvmField val boundArgs: Array<Any?>,
    proto: JSObject?,
) : JSObject(proto) {
    init {
        special = special or CALLABLE
        if (target.isConstructor) special = special or CONSTRUCTOR
    }

    override val className: String get() = "Function"

    private fun allArgs(args: Array<Any?>): Array<Any?> {
        if (boundArgs.isEmpty()) return args
        if (args.isEmpty()) return boundArgs.copyOf()
        val a = arrayOfNulls<Any?>(boundArgs.size + args.size)
        System.arraycopy(boundArgs, 0, a, 0, boundArgs.size)
        System.arraycopy(args, 0, a, boundArgs.size, args.size)
        return a
    }

    override fun call(thisArg: Any?, args: Array<Any?>): Any? = target.call(boundThis, allArgs(args))

    override fun construct(args: Array<Any?>, newTarget: JSObject): Any? {
        val nt = if (newTarget === this) target else newTarget
        return target.construct(allArgs(args), nt)
    }
}

/** Wrapper objects for Boolean, Number, Symbol and BigInt primitives. */
class JSPrimitiveWrapper(proto: JSObject?, @JvmField val primitive: Any) : JSObject(proto) {
    override val className: String
        get() = when (primitive) {
            is Boolean -> "Boolean"
            is Double -> "Number"
            else -> "Object"
        }
}
