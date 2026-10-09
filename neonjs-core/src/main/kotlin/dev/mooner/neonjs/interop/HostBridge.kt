package dev.mooner.neonjs.interop

import dev.mooner.neonjs.HostAccess
import dev.mooner.neonjs.runtime.*
import dev.mooner.neonjs.vm.HostException
import dev.mooner.neonjs.vm.JSPromise
import dev.mooner.neonjs.vm.Promises
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.Future
import java.lang.reflect.*
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.math.abs
import kotlin.math.round

/** Re-entry point used by host-side callbacks (interface proxies) to run JS code. */
interface ContextGate {
    fun <R> enter(block: () -> R): R
    /** Wraps a JS value for host code (e.g. dev.mooner.neonjs.NeonValue). */
    fun wrapValue(v: Any?): Any
    /** Unwraps a host-side value wrapper (NeonValue) into a JS value, or returns NotFound. */
    fun unwrapValue(v: Any?): Any?
    /** The host wrapper class (NeonValue) used as a conversion target. */
    val valueClass: Class<*>
    /** The host exception completing a future with the JS rejection [reason]. */
    fun rejectionToHost(reason: Any?): Throwable = JSException(reason)
}

/**
 * Converts values between JS and the host, selects overloads and creates host wrappers. One bridge per context.
 */
class HostBridge(val realm: Realm, val access: HostAccess, val gate: ContextGate) {
    private val wrapperCache = java.util.WeakHashMap<Any, java.lang.ref.WeakReference<HostObject>>()

    fun classInfo(c: Class<*>) = HostClassInfo.of(c, access)

    // ------------------------------------------------------------------ host -> JS

    fun toJS(v: Any?): Any? = when (v) {
        null -> Null
        is JSObject -> v
        is String -> v
        is Boolean -> v
        is Double -> v
        is Int -> v.toDouble()
        is Long -> v.toDouble()
        is Float -> v.toDouble()
        is Short -> v.toDouble()
        is Byte -> v.toDouble()
        is Char -> v.toString()
        // the engine's own strings; any other CharSequence (StringBuilder, CharBuffer, Android's Spanned) is mutable
        // or carries more than its text, so it stays a host object rather than a copy of the text
        is Rope -> v
        is BigInteger -> v
        is BigDecimal -> v.toDouble()
        is Undefined -> Undefined
        is Null -> Null
        is JSSymbol -> v
        is Unit -> Undefined
        is Class<*> -> if (access.isClassAccessible(v)) HostClassObject(this, v) else opaque(v)
        is CompletionStage<*> -> promiseOf(v)
        else -> {
            val un = gate.unwrapValue(v)
            if (un !== NotFound) un else wrap(v)
        }
    }

    /**
     * A promise settled with the outcome of [stage]. The completion (on whatever thread completes the stage) only
     * posts an external job; the promise is settled by that job on the context's thread. Until then the stage counts
     * as pending external work (see NeonContext.runEventLoop).
     */
    private fun promiseOf(stage: CompletionStage<*>): JSPromise {
        val agent = realm.agent
        val p = Promises.newPromise(realm)
        // nothing to withdraw on close: a completion arriving afterwards is dropped by postExternalJob. (An object
        // expression, not a lambda: a non-capturing lambda would be one shared instance in the agent's source set.)
        val source = object : Agent.ExternalSource {
            override fun cancel() {}
        }
        if (!agent.addExternalSource(source)) return p
        stage.whenComplete { value, error ->
            agent.postExternalJob {
                agent.removeExternalSource(source)
                if (error != null) {
                    val cause = if (error is CompletionException && error.cause != null) error.cause!! else error
                    Promises.rejectPromise(realm, p, agent.hostExceptionToJS(HostException(cause), realm))
                } else {
                    Promises.resolvePromise(realm, p, toJS(value))
                }
            }
        }
        return p
    }

    /**
     * A future completed when the JS value [v] settles (a promise or thenable; other values complete it at once), its
     * value converted to [elementType]. Completion happens in a job on the context's thread, so host code waiting for
     * it on that thread must keep running jobs (NeonValue.await, NeonContext.runEventLoop) rather than block.
     */
    private fun futureOf(v: Any?, elementType: Class<*>): CompletableFuture<Any?> {
        val f = CompletableFuture<Any?>()
        fun complete(x: Any?) {
            try {
                f.complete(toHost(x, elementType))
            } catch (e: JSException) {
                f.completeExceptionally(gate.rejectionToHost(e.value))
            }
        }
        val p: JSPromise = when (v) {
            is JSPromise -> v
            is JSObject if Ops.isCallable(v.get("then", v)) -> Promises.promiseResolve(realm, realm.promiseConstructor, v) as JSPromise
            else -> {
                complete(v)
                return f
            }
        }
        Promises.thenHost(realm, p, { x -> complete(x); Undefined }, { r -> f.completeExceptionally(gate.rejectionToHost(r)); Undefined })
        return f
    }

    private fun opaque(v: Any): HostObject = HostObject(this, v, HostClassInfo.of(Any::class.java, HostAccess.NONE))

    /** Wraps a host object (identity preserving while the host object is alive). */
    fun wrap(v: Any): HostObject {
        val cached = wrapperCache[v]?.get()
        if (cached != null && cached.target === v) return cached
        val info = classInfo(v.javaClass)
        val w = if (info.accessible || info.functionalMethod != null || v.javaClass.isArray || v is List<*> || v is Map<*, *> || v is Iterable<*>) HostObject(this, v, info) else opaque(v)
        if (v !is Number && v !is String) wrapperCache[v] = java.lang.ref.WeakReference(w)
        return w
    }

    // ------------------------------------------------------------------ JS -> host

    /**
     * Calls of interface default methods on proxies. `InvocationHandler.invokeDefault` (JDK 16+) is absent on Android;
     * there a method handle with private access to the interface invokes the default implementation, as Retrofit does:
     * the lookup comes from `MethodHandles.privateLookupIn` (Android 13+) or from `Lookup`'s (Class, int) constructor.
     */
    private object DefaultMethods {
        val HAS_INVOKE_DEFAULT = try {
            InvocationHandler::class.java.getMethod("invokeDefault", Any::class.java, Method::class.java, Array<Any>::class.java)
            true
        } catch (_: Throwable) {
            false
        }
        private val PRIVATE_LOOKUP_IN: Method? = try {
            java.lang.invoke.MethodHandles::class.java.getMethod("privateLookupIn", Class::class.java, java.lang.invoke.MethodHandles.Lookup::class.java)
        } catch (_: Throwable) {
            null
        }
        private val LOOKUP_CONSTRUCTOR: Constructor<java.lang.invoke.MethodHandles.Lookup>? by lazy {
            try {
                java.lang.invoke.MethodHandles.Lookup::class.java.getDeclaredConstructor(Class::class.java, Int::class.javaPrimitiveType).also { it.isAccessible = true }
            } catch (_: Throwable) {
                null
            }
        }
        private val handles = java.util.concurrent.ConcurrentHashMap<Method, java.lang.invoke.MethodHandle>()

        /** A handle invoking [method]'s default implementation (not dispatching to the proxy) on its receiver. */
        fun special(method: Method): java.lang.invoke.MethodHandle = handles.getOrPut(method) {
            val iface = method.declaringClass
            var failure: Throwable? = null
            for (make in listOf(
                { PRIVATE_LOOKUP_IN?.invoke(null, iface, java.lang.invoke.MethodHandles.lookup()) as java.lang.invoke.MethodHandles.Lookup? },
                { LOOKUP_CONSTRUCTOR?.newInstance(iface, ALL_MODES) },
            )) {
                try {
                    val lookup = make() ?: continue
                    return@getOrPut lookup.unreflectSpecial(method, iface)
                } catch (e: Throwable) {
                    failure = (e as? InvocationTargetException)?.targetException ?: e
                }
            }
            throw failure ?: UnsupportedOperationException("no private method handle lookup")
        }

        /** PUBLIC | PRIVATE | PROTECTED | PACKAGE. */
        private const val ALL_MODES = 15
    }

    companion object {
        const val IMPOSSIBLE = Int.MAX_VALUE
        /** Largest Java array created from JS (elements); larger requests are a RangeError. */
        const val MAX_ARRAY_LENGTH = 1 shl 26

        /** Resolves `int`, `java.lang.String[]`, `int[][]`... to a class (array syntax and primitive names). */
        fun classForName(name: String, loader: ClassLoader?): Class<*> {
            var n = name.trim()
            var dims = 0
            while (n.endsWith("[]")) {
                dims++
                n = n.substring(0, n.length - 2).trim()
            }
            var c: Class<*> = when (n) {
                "boolean" -> java.lang.Boolean.TYPE
                "byte" -> java.lang.Byte.TYPE
                "char" -> Character.TYPE
                "short" -> java.lang.Short.TYPE
                "int" -> Integer.TYPE
                "long" -> java.lang.Long.TYPE
                "float" -> java.lang.Float.TYPE
                "double" -> java.lang.Double.TYPE
                else -> Class.forName(n, true, loader)
            }
            if (dims == 0 && c.isPrimitive) throw ClassNotFoundException("$name is a primitive type")
            repeat(dims) { c = java.lang.reflect.Array.newInstance(c, 0).javaClass }
            return c
        }
    }

    /** Cost of converting JS value [v] to [t]; lower is better, IMPOSSIBLE if not convertible. */
    fun cost(v: Any?, t: Class<*>): Int {
        if (t == gate.valueClass) return 1
        if (v !is HostObject && isFutureType(t)) return when (v) {
            Undefined, Null, null -> 1
            is JSObject -> 2
            else -> 8
        }
        when (v) {
            Undefined, Null, null -> return if (t.isPrimitive) IMPOSSIBLE else 1
            is Boolean -> return when (t) {
                java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> 0
                Any::class.java -> 2
                else -> IMPOSSIBLE
            }
            is Double -> {
                val integral = v == round(v) && !v.isInfinite()
                return when (t) {
                    java.lang.Double.TYPE, java.lang.Double::class.java -> if (integral) 2 else 1
                    java.lang.Float.TYPE, java.lang.Float::class.java -> 3
                    java.lang.Long.TYPE, java.lang.Long::class.java -> if (integral && abs(v) <= 9.007199254740991E15) 1 else IMPOSSIBLE
                    java.lang.Integer.TYPE, java.lang.Integer::class.java -> if (integral && v >= Int.MIN_VALUE && v <= Int.MAX_VALUE) 1 else IMPOSSIBLE
                    java.lang.Short.TYPE, java.lang.Short::class.java -> if (integral && v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) 2 else IMPOSSIBLE
                    java.lang.Byte.TYPE, java.lang.Byte::class.java -> if (integral && v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) 2 else IMPOSSIBLE
                    java.lang.Number::class.java, Any::class.java -> 3
                    BigInteger::class.java -> if (integral) 4 else IMPOSSIBLE
                    BigDecimal::class.java -> 4
                    else -> IMPOSSIBLE
                }
            }
            is CharSequence -> return when {
                t == String::class.java || t == CharSequence::class.java -> 0
                t == Character.TYPE || t == Character::class.java -> if (v.length == 1) 1 else IMPOSSIBLE
                t == Any::class.java -> 2
                t.isEnum -> if (t.enumConstants.any { (it as Enum<*>).name == v.toString() }) 2 else IMPOSSIBLE
                else -> IMPOSSIBLE
            }
            is BigInteger -> return when (t) {
                BigInteger::class.java -> 0
                java.lang.Long.TYPE, java.lang.Long::class.java -> if (v.bitLength() < 64) 1 else IMPOSSIBLE
                java.lang.Number::class.java, Any::class.java -> 3
                else -> IMPOSSIBLE
            }
            is HostObject -> {
                val x = v.target
                if (t.isInstance(x)) return distance(x.javaClass, t)
                val boxed = boxed(t)
                if (boxed != null && boxed.isInstance(x)) return 1
                // a StringBuilder where a String is expected: its text (after any overload taking the object itself)
                if (t == String::class.java && x is CharSequence) return 8
                return IMPOSSIBLE
            }
            is HostClassObject -> return if (t == Any::class.java) 5 else IMPOSSIBLE
            is JSObject -> {
                if ((v is dev.mooner.neonjs.builtins.JSDate || v is dev.mooner.neonjs.builtins.temporal.JSTemporalInstant) && isDateTimeType(t)) return 1
                if (v.isCallable && t.isInterface && HostClassInfo.samOf(t) != null && access.allowImplementations) return 1
                if (Ops.isArray(v)) {
                    if (t.isArray) return 2
                    if (t == List::class.java || t == Collection::class.java || t == Iterable::class.java) return 2
                }
                if (t == Map::class.java && access.allowMapAccess) return 3
                if (t.isInterface && access.allowImplementations && t != Map::class.java && t != List::class.java) return 6
                if (t == Any::class.java) return 5
                return IMPOSSIBLE
            }
            is JSSymbol -> return if (t == Any::class.java) 5 else IMPOSSIBLE
            else -> return if (t.isInstance(v)) 0 else IMPOSSIBLE
        }
    }

    private fun boxed(t: Class<*>): Class<*>? = when (t) {
        Integer.TYPE -> Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        Character.TYPE -> Character::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        else -> null
    }

    private fun distance(from: Class<*>, to: Class<*>): Int {
        if (from == to) return 0
        var d = 0
        var c: Class<*>? = from
        while (c != null) {
            if (c == to) return d
            d++
            c = c.superclass
        }
        return if (to.isInterface) 2 else 3
    }

    /** Converts JS value [v] to host type [t] (generic type [gt] used for collections/functions). */
    fun toHost(v: Any?, t: Class<*>, gt: Type? = null): Any? {
        if (t == gate.valueClass) return gate.wrapValue(v)
        when (v) {
            Undefined, Null, null -> {
                if (t.isPrimitive) throw JSException.typeError("Cannot convert ${Ops.toDisplayString(v)} to ${t.name}")
                return null
            }
            is HostObject -> {
                val x = v.target
                if (t.isInstance(x) || boxed(t)?.isInstance(x) == true) return x
                if (t == String::class.java && x is CharSequence) return x.toString()
            }
            is HostClassObject -> if (t == Any::class.java) return v.cls
        }
        when (t) {
            Any::class.java -> return toObject(v)
            String::class.java, CharSequence::class.java -> return if (v is CharSequence) v.toString() else Ops.toString(v)
            java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> return Ops.toBoolean(v)
            java.lang.Double.TYPE, java.lang.Double::class.java -> return Ops.toNumber(v)
            java.lang.Float.TYPE, java.lang.Float::class.java -> return Ops.toNumber(v).toFloat()
            java.lang.Long.TYPE, java.lang.Long::class.java -> return if (v is BigInteger) v.toLong() else Ops.toNumber(v).toLong()
            Integer.TYPE, Integer::class.java -> return Ops.toNumber(v).toInt()
            java.lang.Short.TYPE, java.lang.Short::class.java -> return Ops.toNumber(v).toInt().toShort()
            java.lang.Byte.TYPE, java.lang.Byte::class.java -> return Ops.toNumber(v).toInt().toByte()
            Character.TYPE, Character::class.java -> {
                if (v is CharSequence && v.length == 1) return v[0]
                return Ops.toNumber(v).toInt().toChar()
            }
            java.lang.Number::class.java -> return toObject(v) as? Number ?: Ops.toNumber(v)
            BigInteger::class.java -> return v as? BigInteger ?: Ops.bigIntFromDouble(Ops.toNumber(v))
            BigDecimal::class.java -> return if (v is BigInteger) BigDecimal(v) else BigDecimal(Ops.toNumber(v))
        }
        if (isFutureType(t)) return futureOf(v, elementType(gt, 0))
        if (v is JSObject) dateTimeOf(v, t)?.let { return it }
        if (t.isEnum && v is CharSequence) {
            val n = v.toString()
            return t.enumConstants.firstOrNull { (it as Enum<*>).name == n } ?: throw JSException.typeError("No enum constant ${t.name}.$n")
        }
        if (v is JSObject) {
            if (t.isArray && Ops.isArray(v)) {
                val ct = t.componentType
                val n = Ops.lengthOfArrayLike(v).toInt()
                val arr = java.lang.reflect.Array.newInstance(ct, n)
                for (i in 0 until n) java.lang.reflect.Array.set(arr, i, toHost(v.get(i, v), ct))
                return arr
            }
            if ((t == List::class.java || t == Collection::class.java || t == Iterable::class.java) && Ops.isArray(v)) {
                return JSListView(this, v, elementType(gt, 0))
            }
            if (t == Map::class.java) return JSMapView(this, v, elementType(gt, 1))
            if (t.isInterface && access.allowImplementations) return implement(v, t)
        }
        if (t.isInstance(v)) return v
        throw JSException.typeError("Cannot convert ${Ops.describe(v)} to ${t.name}")
    }

    /** Date-time types a JS `Date` or `Temporal.Instant` converts to (see [dateTimeOf]). */
    private fun isDateTimeType(t: Class<*>) = t == java.time.Instant::class.java || t == java.util.Date::class.java ||
        t == java.time.ZonedDateTime::class.java || t == java.time.OffsetDateTime::class.java ||
        t == java.time.LocalDateTime::class.java || t == java.time.LocalDate::class.java || t == java.time.LocalTime::class.java

    /**
     * A JS `Date` or `Temporal.Instant` as an instance of the date-time type [t] (local types in the context's time
     * zone); null when [v] is neither or [t] is not a date-time type.
     */
    private fun dateTimeOf(v: JSObject, t: Class<*>): Any? {
        if (!isDateTimeType(t)) return null
        val instant = when (v) {
            is dev.mooner.neonjs.builtins.JSDate -> {
                if (v.timeValue.isNaN()) throw JSException.rangeError("Invalid Date cannot be converted to ${t.simpleName}")
                java.time.Instant.ofEpochMilli(v.timeValue.toLong())
            }
            is dev.mooner.neonjs.builtins.temporal.JSTemporalInstant -> {
                val qr = v.epochNs.divideAndRemainder(BigInteger.valueOf(1_000_000_000L))
                java.time.Instant.ofEpochSecond(qr[0].toLong(), qr[1].toLong())
            }
            else -> return null
        }
        val zone = dev.mooner.neonjs.builtins.DateTime.zone
        return when (t) {
            java.time.Instant::class.java -> instant
            java.util.Date::class.java -> java.util.Date.from(instant)
            java.time.ZonedDateTime::class.java -> instant.atZone(zone)
            java.time.OffsetDateTime::class.java -> instant.atZone(zone).toOffsetDateTime()
            java.time.LocalDateTime::class.java -> java.time.LocalDateTime.ofInstant(instant, zone)
            java.time.LocalDate::class.java -> instant.atZone(zone).toLocalDate()
            else -> instant.atZone(zone).toLocalTime()
        }
    }

    /** Future types a JS value converts to (see [futureOf]). */
    private fun isFutureType(t: Class<*>) = t == CompletableFuture::class.java || t == CompletionStage::class.java || t == Future::class.java

    private fun elementType(gt: Type?, index: Int): Class<*> {
        if (gt is ParameterizedType) {
            val a = gt.actualTypeArguments.getOrNull(index)
            if (a is Class<*>) return a
            if (a is ParameterizedType) return a.rawType as Class<*>
        }
        return Any::class.java
    }

    /** Conversion for Object-typed targets: JS primitives become boxed Java values, objects become NeonValues. */
    fun toObject(v: Any?): Any? = when (v) {
        Undefined, Null, null -> null
        is Boolean -> v
        is Double -> if (v == round(v) && !v.isInfinite() && !(v == 0.0 && 1.0 / v < 0)) {
            if (v >= Int.MIN_VALUE && v <= Int.MAX_VALUE) v.toInt() else if (abs(v) < 9.2e18) v.toLong() else v
        } else v
        is CharSequence -> v.toString()
        is BigInteger -> v
        is HostObject -> v.target
        is HostClassObject -> v.cls
        else -> gate.wrapValue(v)
    }

    /** Creates a host implementation of interface [iface] backed by a JS function or object. */
    fun implement(v: JSObject, iface: Class<*>): Any {
        val handler = InvocationHandler { proxy, method, args ->
            when {
                method.declaringClass == Any::class.java -> when (method.name) {
                    "toString" -> "JSProxy[${iface.simpleName}]"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.get(0)
                    else -> null
                }
                else -> gate.enter {
                    val jsArgs = arrayOfNulls<Any?>(args?.size ?: 0)
                    if (args != null) for (i in args.indices) jsArgs[i] = toJS(args[i])
                    val r = if (v.isCallable) v.call(Undefined, jsArgs)
                    else {
                        val fn = v.get(method.name, v)
                        if (!Ops.isCallable(fn)) {
                            if (method.isDefault) return@enter invokeDefault(proxy, method, args)
                            throw JSException.typeError("${method.name} is not implemented")
                        }
                        (fn as JSObject).call(v, jsArgs)
                    }
                    if (method.returnType == Void.TYPE) null else toHost(r, method.returnType, method.genericReturnType)
                }
            }
        }
        return Proxy.newProxyInstance(iface.classLoader ?: javaClass.classLoader, arrayOf(iface), handler)
    }

    /** Runs the default implementation of an interface method the JS object does not provide. */
    private fun invokeDefault(proxy: Any, method: Method, args: Array<Any?>?): Any? {
        val a = args ?: emptyArray()
        if (DefaultMethods.HAS_INVOKE_DEFAULT) return InvocationHandler.invokeDefault(proxy, method, *a)
        val handle = try {
            DefaultMethods.special(method)
        } catch (e: Throwable) {
            throw JSException.typeError("${method.name} is not implemented (the platform cannot call default methods: $e)")
        }
        return handle.bindTo(proxy).invokeWithArguments(*a)
    }

    // ------------------------------------------------------------------ invocation

    /** Selects the best overload for [args]; returns null if none applies. */
    fun <E : Executable> select(cands: List<E>, args: Array<Any?>): E? {
        var best: E? = null
        var bestCost = IMPOSSIBLE
        for (c in cands) {
            val pts = c.parameterTypes
            var total = 0
            if (c.isVarArgs) {
                if (args.size < pts.size - 1) continue
                for (i in 0 until pts.size - 1) {
                    val k = cost(args[i], pts[i])
                    if (k == IMPOSSIBLE) { total = IMPOSSIBLE; break }
                    total += k
                }
                if (total == IMPOSSIBLE) continue
                val ct = pts.last().componentType
                if (args.size == pts.size && cost(args.last(), pts.last()) != IMPOSSIBLE) total += cost(args.last(), pts.last())
                else for (i in pts.size - 1 until args.size) {
                    val k = cost(args[i], ct)
                    if (k == IMPOSSIBLE) { total = IMPOSSIBLE; break }
                    total += k
                }
                if (total == IMPOSSIBLE) continue
                total += 10
            } else {
                if (pts.size != args.size) continue
                for (i in pts.indices) {
                    val k = cost(args[i], pts[i])
                    if (k == IMPOSSIBLE) { total = IMPOSSIBLE; break }
                    total += k
                }
                if (total == IMPOSSIBLE) continue
            }
            if (total < bestCost) {
                best = c
                bestCost = total
            }
        }
        return best
    }

    fun convertArgs(e: Executable, args: Array<Any?>): Array<Any?> {
        val pts = e.parameterTypes
        val gts = e.genericParameterTypes
        if (!e.isVarArgs) return Array(pts.size) { i -> toHost(args[i], pts[i], gts[i]) }
        val fixed = pts.size - 1
        val out = arrayOfNulls<Any?>(pts.size)
        for (i in 0 until fixed) out[i] = toHost(args[i], pts[i], gts[i])
        if (args.size == pts.size && cost(args.last(), pts.last()) != IMPOSSIBLE) {
            out[fixed] = toHost(args.last(), pts.last(), gts.last())
        } else {
            val ct = pts.last().componentType
            val arr = java.lang.reflect.Array.newInstance(ct, args.size - fixed)
            for (i in fixed until args.size) java.lang.reflect.Array.set(arr, i - fixed, toHost(args[i], ct))
            out[fixed] = arr
        }
        return out
    }

    /** Invokes a host method/constructor, mapping exceptions. */
    fun invoke(m: Method, target: Any?, args: Array<Any?>): Any? {
        val conv = convertArgs(m, args)
        val r = try {
            m.invoke(target, *conv)
        } catch (e: InvocationTargetException) {
            throw hostError(e.targetException)
        } catch (e: IllegalAccessException) {
            throw JSException.typeError("Cannot access ${m.name}: ${e.message}")
        }
        if (m.returnType == Void.TYPE) return Undefined
        return toJS(r)
    }

    fun construct(c: Constructor<*>, args: Array<Any?>): Any? {
        val conv = convertArgs(c, args)
        val r = try {
            c.newInstance(*conv)
        } catch (e: InvocationTargetException) {
            throw hostError(e.targetException)
        } catch (e: IllegalAccessException) {
            throw JSException.typeError("Cannot access constructor: ${e.message}")
        }
        return toJS(r)
    }

    /** `new (Java.type('int[]'))(n)`: a zero-initialized Java array; [len] is capped to protect the host heap. */
    fun newArray(component: Class<*>, len: Any?): Any? {
        val d = Ops.toIntegerOrInfinity(len)
        if (d < 0 || d > MAX_ARRAY_LENGTH) throw JSException.rangeError("Invalid Java array length: ${Ops.toDisplayString(len)}")
        realm.agent.reserveAllocation(d.toLong() * (if (component.isPrimitive) 8 else 4))
        val arr = try {
            java.lang.reflect.Array.newInstance(component, d.toInt())
        } catch (_: OutOfMemoryError) {
            throw JSException.rangeError("Java array allocation failed: length ${d.toInt()}")
        }
        return toJS(arr)
    }

    /** Converts a JS array-like (or iterable host collection) to a Java array / List of the given type. */
    fun convertTo(v: Any?, t: Class<*>): Any? {
        if (t.isArray && v is JSObject && v !is HostObject) {
            val n = Ops.lengthOfArrayLike(v)
            if (n > MAX_ARRAY_LENGTH) throw JSException.rangeError("Invalid Java array length: $n")
            val ct = t.componentType
            val arr = java.lang.reflect.Array.newInstance(ct, n.toInt())
            for (i in 0 until n.toInt()) {
                java.lang.reflect.Array.set(arr, i, toHost(v.get(PK.fromIndex(i.toLong()), v), ct))
                if (i and 1023 == 1023) realm.agent.checkInterrupt()
            }
            return arr
        }
        if ((t == List::class.java || t == java.util.ArrayList::class.java || t == Collection::class.java) && v is JSObject && v !is HostObject) {
            val n = Ops.lengthOfArrayLike(v)
            if (n > MAX_ARRAY_LENGTH) throw JSException.rangeError("Invalid Java list length: $n")
            val out = java.util.ArrayList<Any?>(n.toInt())
            for (i in 0 until n.toInt()) {
                out.add(toObject(v.get(PK.fromIndex(i.toLong()), v)))
                if (i and 1023 == 1023) realm.agent.checkInterrupt()
            }
            return out
        }
        return toHost(v, t)
    }

    fun hostError(t: Throwable): Throwable = when (t) {
        is JSException, is TerminationException -> t
        is StackOverflowError, is OutOfMemoryError -> t
        else -> HostException(t)
    }
}

/** Live java.util.List view of a JS array. */
class JSListView(private val bridge: HostBridge, private val arr: JSObject, private val et: Class<*>) : java.util.AbstractList<Any?>() {
    override val size: Int get() = bridge.gate.enter { Ops.lengthOfArrayLike(arr).toInt() }
    override fun get(index: Int): Any? = bridge.gate.enter {
        if (index < 0 || index >= Ops.lengthOfArrayLike(arr)) throw IndexOutOfBoundsException("$index")
        bridge.toHost(arr.get(index, arr), et)
    }
    override fun set(index: Int, element: Any?): Any? = bridge.gate.enter {
        val old = arr.get(index, arr)
        arr.setOrThrow(index, bridge.toJS(element))
        bridge.toHost(old, et)
    }
    override fun add(index: Int, element: Any?) {
        bridge.gate.enter { Ops.invoke(arr, "splice", arrayOf(index.toDouble(), 0.0, bridge.toJS(element))) }
    }
    override fun removeAt(index: Int): Any? = bridge.gate.enter {
        val r = Ops.invoke(arr, "splice", arrayOf(index.toDouble(), 1.0)) as JSObject
        bridge.toHost(r.get(0, r), et)
    }
}

/** Live java.util.Map view of a JS object's own enumerable string-keyed properties. */
class JSMapView(private val bridge: HostBridge, private val obj: JSObject, private val vt: Class<*>) : java.util.AbstractMap<String, Any?>() {
    override val entries: MutableSet<MutableMap.MutableEntry<String, Any?>>
        get() = bridge.gate.enter {
            val set = LinkedHashSet<MutableMap.MutableEntry<String, Any?>>()
            for (k in obj.ownPropertyKeys()) {
                if (k is JSSymbol) continue
                val d = obj.getOwnProperty(k) ?: continue
                if (!d.enumerable) continue
                val key = PK.toStringKey(k)
                set.add(java.util.AbstractMap.SimpleEntry(key, bridge.toHost(obj.get(k, obj), vt)))
            }
            set
        }

    override fun get(key: String): Any? = bridge.gate.enter { bridge.toHost(obj.get(PK.fromString(key), obj), vt) }
    override fun containsKey(key: String): Boolean = bridge.gate.enter { obj.hasProperty(PK.fromString(key)) }
    override fun put(key: String, value: Any?): Any? = bridge.gate.enter {
        val k = PK.fromString(key)
        val old = obj.get(k, obj)
        obj.setOrThrow(k, bridge.toJS(value))
        bridge.toHost(old, vt)
    }
    override fun remove(key: String): Any? = bridge.gate.enter {
        val k = PK.fromString(key)
        val old = obj.get(k, obj)
        obj.delete(k)
        bridge.toHost(old, vt)
    }
}
