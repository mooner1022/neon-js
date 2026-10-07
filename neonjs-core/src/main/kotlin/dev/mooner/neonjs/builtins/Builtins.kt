package dev.mooner.neonjs.builtins

import dev.mooner.neonjs.runtime.*
import dev.mooner.neonjs.vm.*

// ====================================================================== DSL helpers

/** Defines a builtin method (writable, configurable, non-enumerable by default). */
internal fun JSObject.method(realm: Realm, name: Any, length: Int, attrs: Int = Attr.WC, impl: NativeImpl): NativeFunction {
    val f = NativeFunction(realm, name, length, impl)
    defineOwn(name, f, attrs)
    return f
}

internal fun JSObject.getter(realm: Realm, name: Any, impl: NativeImpl): NativeFunction {
    val f = NativeFunction(realm, name, 0, impl, namePrefix = "get")
    defineAccessor(name, f, Undefined, Attr.CONFIGURABLE)
    return f
}

internal fun JSObject.accessor(realm: Realm, name: Any, get: NativeImpl, set: NativeImpl): Pair<NativeFunction, NativeFunction> {
    val g = NativeFunction(realm, name, 0, get, namePrefix = "get")
    val s = NativeFunction(realm, name, 1, set, namePrefix = "set")
    defineAccessor(name, g, s, Attr.CONFIGURABLE)
    return g to s
}

internal fun JSObject.value(name: Any, v: Any?, attrs: Int = Attr.WC) = defineOwn(name, v, attrs)

/** Creates a constructor function with its prototype wiring (prototype: non-writable, constructor back-link). */
internal fun makeCtor(realm: Realm, name: String, length: Int, proto: JSObject?, ctorProto: JSObject? = realm.functionPrototype, impl: NativeImpl): NativeFunction {
    val c = NativeFunction(realm, name, length, impl, isConstructor = true, proto = ctorProto)
    if (proto != null) {
        c.defineOwn("prototype", proto, Attr.NONE)
        proto.defineOwn("constructor", c, Attr.WC)
    }
    return c
}

internal fun Realm.global(name: String, v: Any?, attrs: Int = Attr.WC) = globalObject.defineOwn(name, v, attrs)

/** Relative index helper used by slice/splice/at etc. */
internal fun relIndex(v: Any?, len: Long, default: Long): Long {
    if (v === Undefined) return default
    val r = Ops.toIntegerOrInfinity(v)
    return if (r < 0) maxOf(0.0, len + r).toLong() else minOf(r, len.toDouble()).toLong()
}

internal fun typeErr(msg: String): Nothing = throw JSException.typeError(msg)
internal fun rangeErr(msg: String): Nothing = throw JSException.rangeError(msg)

internal fun callable(v: Any?): JSObject {
    if (!Ops.isCallable(v)) typeErr("${Ops.describe(v)} is not a function")
    return v as JSObject
}

object Builtins {
    /** Installs all intrinsics and globals into [realm]. */
    @JvmStatic
    fun install(realm: Realm) {
        realm.enter {
            val objProto = JSObject(null)
            objProto.special = objProto.special or JSObject.IMMUTABLE_PROTO
            realm.objectPrototype = objProto
            val fnProto = NativeFunction(realm, "", 0, { _, _, _, _ -> Undefined }, proto = objProto)
            realm.functionPrototype = fnProto
            val global = JSObject(objProto)
            realm.globalObject = global
            realm.globalEnv = GlobalEnv(realm, global, global)
            realm.throwTypeError = makeThrowTypeError(realm)

            ObjectBuiltins.install(realm)
            FunctionBuiltins.install(realm)
            ErrorBuiltins.install(realm)
            SymbolBuiltins.install(realm)
            BooleanBuiltins.install(realm)
            NumberBuiltins.install(realm)
            MathBuiltins.install(realm)
            BigIntBuiltins.install(realm)
            IteratorBuiltins.install(realm)
            ArrayBuiltins.install(realm)
            StringBuiltins.install(realm)
            GeneratorBuiltins.install(realm)
            PromiseBuiltins.install(realm)
            GlobalBuiltins.install(realm)
            JSONBuiltins.install(realm)
            ReflectBuiltins.install(realm)
            CollectionBuiltins.install(realm)
            DisposableBuiltins.install(realm)
            ModuleSourceBuiltins.install(realm)
            ShadowRealms.install(realm)
            RegExpBuiltins.install(realm)
            ArrayBufferBuiltins.install(realm)
            TypedArrayBuiltins.install(realm)
            DataViewBuiltins.install(realm)
            AtomicsBuiltins.install(realm)
            DateBuiltins.install(realm)
            dev.mooner.neonjs.builtins.temporal.TemporalBuiltins.install(realm)
            // last, so the provider can replace the locale-sensitive methods of every builtin (Date, %TypedArray%, ...)
            if (realm.agent.config.intl) IntlSupport.provider?.install(realm)
            for (ext in extensions) ext(realm)
        }
    }

    /** Additional installers (RegExp, Date, TypedArrays, Intl, ...) registered by other modules. */
    @JvmStatic
    val extensions = ArrayList<(Realm) -> Unit>()

    private fun makeThrowTypeError(realm: Realm): JSObject {
        val f = NativeFunction(realm, "", 0, { _, _, _, _ -> throw JSException.typeError("'caller', 'callee', and 'arguments' properties may not be accessed on strict mode functions or the arguments objects for calls to them") })
        f.defineOwn("length", 0.0, Attr.NONE)
        f.defineOwn("name", "", Attr.NONE)
        f.preventExtensions()
        return f
    }

    /** SetIntegrityLevel(O, frozen) */
    @JvmStatic
    fun freeze(o: JSObject): Boolean = setIntegrityLevel(o, true)

    @JvmStatic
    fun setIntegrityLevel(o: JSObject, frozen: Boolean): Boolean {
        if (!o.preventExtensions()) return false
        val keys = o.ownPropertyKeys()
        if (!frozen) {
            for (k in keys) o.definePropertyOrThrow(k, PropertyDescriptor().configurable(false))
        } else {
            for (k in keys) {
                val d = o.getOwnProperty(k) ?: continue
                val nd = if (d.isAccessor) PropertyDescriptor().configurable(false) else PropertyDescriptor().configurable(false).writable(false)
                o.definePropertyOrThrow(k, nd)
            }
        }
        return true
    }

    @JvmStatic
    fun testIntegrityLevel(o: JSObject, frozen: Boolean): Boolean {
        if (o.isExtensible()) return false
        for (k in o.ownPropertyKeys()) {
            val d = o.getOwnProperty(k) ?: continue
            if (d.configurable) return false
            if (frozen && d.isData && d.writable) return false
        }
        return true
    }

    /** CreateArrayFromList */
    @JvmStatic
    fun arrayOf(realm: Realm, items: List<Any?>): JSArray = JSArray.of(realm.arrayPrototype, items.toTypedArray())

    /** OrdinaryCreateFromConstructor helper: prototype from newTarget or intrinsic default. */
    @JvmStatic
    fun protoFrom(newTarget: JSObject?, default: (Realm) -> JSObject, realm: Realm): JSObject =
        if (newTarget == null) default(realm) else Ops.getPrototypeFromConstructor(newTarget, default)
}

// ====================================================================== Object

internal object ObjectBuiltins {
    fun install(realm: Realm) {
        val proto = realm.objectPrototype
        val ctor = makeCtor(realm, "Object", 1, proto) { f, _, args, nt ->
            if (nt != null && nt !== f) {
                JSObject(Ops.getPrototypeFromConstructor(nt) { it.objectPrototype })
            } else {
                val v = args.arg(0)
                if (v === Undefined || v === Null) JSObject(f.realm.objectPrototype) else Ops.toObject(f.realm, v)
            }
        }
        realm.objectConstructor = ctor
        realm.global("Object", ctor)

        ctor.method(realm, "assign", 2) { _, _, args, _ ->
            val to = Ops.toObject(args.arg(0))
            for (i in 1 until args.size) {
                val src = args[i]
                if (src === Undefined || src === Null) continue
                val from = Ops.toObject(src)
                for (k in from.ownPropertyKeys()) {
                    val d = from.getOwnProperty(k)
                    if (d != null && d.enumerable) to.setOrThrow(k, from.get(k, from))
                }
            }
            to
        }
        ctor.method(realm, "create", 2) { _, _, args, _ ->
            val p = args.arg(0)
            if (p !is JSObject && p !== Null) typeErr("Object prototype may only be an Object or null: ${Ops.toDisplayString(p)}")
            val o = JSObject(p as? JSObject)
            val props = args.arg(1)
            if (props !== Undefined) defineProperties(o, props)
            o
        }
        ctor.method(realm, "defineProperties", 2) { _, _, args, _ ->
            val o = args.arg(0) as? JSObject ?: typeErr("Object.defineProperties called on non-object")
            defineProperties(o, args.arg(1))
            o
        }
        ctor.method(realm, "defineProperty", 3) { _, _, args, _ ->
            val o = args.arg(0) as? JSObject ?: typeErr("Object.defineProperty called on non-object")
            val key = Ops.toPropertyKey(args.arg(1))
            val d = Ops.toPropertyDescriptor(args.arg(2))
            o.definePropertyOrThrow(key, d)
            o
        }
        ctor.method(realm, "entries", 1) { f, _, args, _ -> enumerableOwn(f.realm, Ops.toObject(args.arg(0)), 2) }
        ctor.method(realm, "freeze", 1) { _, _, args, _ ->
            val o = args.arg(0)
            if (o is JSObject && !Builtins.setIntegrityLevel(o, true)) typeErr("Cannot freeze")
            o
        }
        ctor.method(realm, "fromEntries", 1) { f, _, args, _ ->
            val iterable = Ops.requireObjectCoercible(args.arg(0))
            val obj = JSObject(f.realm.objectPrototype)
            val rec = Iteration.getIterator(f.realm, iterable, false)
            while (true) {
                val next = Iteration.stepValue(rec)
                if (next === NotFound) break
                try {
                    if (next !is JSObject) typeErr("Iterator value ${Ops.toDisplayString(next)} is not an entry object")
                    val k = next.get(0, next)
                    val v = next.get(1, next)
                    obj.createDataPropertyOrThrow(Ops.toPropertyKey(k), v)
                } catch (t: Throwable) {
                    Iteration.closeAndRethrow(rec, t)
                }
            }
            obj
        }
        ctor.method(realm, "getOwnPropertyDescriptor", 2) { f, _, args, _ ->
            val o = Ops.toObject(args.arg(0))
            val key = Ops.toPropertyKey(args.arg(1))
            Ops.fromPropertyDescriptor(f.realm, o.getOwnProperty(key))
        }
        ctor.method(realm, "getOwnPropertyDescriptors", 1) { f, _, args, _ ->
            val o = Ops.toObject(args.arg(0))
            val res = JSObject(f.realm.objectPrototype)
            for (k in o.ownPropertyKeys()) {
                val d = Ops.fromPropertyDescriptor(f.realm, o.getOwnProperty(k))
                if (d !== Undefined) res.createDataPropertyOrThrow(k, d)
            }
            res
        }
        ctor.method(realm, "getOwnPropertyNames", 1) { f, _, args, _ ->
            val o = Ops.toObject(args.arg(0))
            Builtins.arrayOf(f.realm, o.ownPropertyKeys().filter { it !is JSSymbol }.map { PK.toValue(it) })
        }
        ctor.method(realm, "getOwnPropertySymbols", 1) { f, _, args, _ ->
            val o = Ops.toObject(args.arg(0))
            Builtins.arrayOf(f.realm, o.ownPropertyKeys().filterIsInstance<JSSymbol>())
        }
        ctor.method(realm, "getPrototypeOf", 1) { _, _, args, _ -> Ops.toObject(args.arg(0)).getPrototypeOf() ?: Null }
        ctor.method(realm, "groupBy", 2) { f, _, args, _ ->
            val groups = groupBy(f.realm, args.arg(0), args.arg(1), true)
            val o = JSObject(null)
            for ((k, v) in groups) o.createDataPropertyOrThrow(k!!, Builtins.arrayOf(f.realm, v))
            o
        }
        ctor.method(realm, "hasOwn", 2) { _, _, args, _ ->
            val o = Ops.toObject(args.arg(0))
            o.hasOwnProperty(Ops.toPropertyKey(args.arg(1)))
        }
        ctor.method(realm, "is", 2) { _, _, args, _ -> Ops.sameValue(args.arg(0), args.arg(1)) }
        ctor.method(realm, "isExtensible", 1) { _, _, args, _ -> val o = args.arg(0); o is JSObject && o.isExtensible() }
        ctor.method(realm, "isFrozen", 1) { _, _, args, _ -> val o = args.arg(0); o !is JSObject || Builtins.testIntegrityLevel(o, true) }
        ctor.method(realm, "isSealed", 1) { _, _, args, _ -> val o = args.arg(0); o !is JSObject || Builtins.testIntegrityLevel(o, false) }
        ctor.method(realm, "keys", 1) { f, _, args, _ -> enumerableOwn(f.realm, Ops.toObject(args.arg(0)), 0) }
        ctor.method(realm, "preventExtensions", 1) { _, _, args, _ ->
            val o = args.arg(0)
            if (o is JSObject && !o.preventExtensions()) typeErr("Cannot prevent extensions")
            o
        }
        ctor.method(realm, "seal", 1) { _, _, args, _ ->
            val o = args.arg(0)
            if (o is JSObject && !Builtins.setIntegrityLevel(o, false)) typeErr("Cannot seal")
            o
        }
        ctor.method(realm, "setPrototypeOf", 2) { _, _, args, _ ->
            val o = Ops.requireObjectCoercible(args.arg(0))
            val p = args.arg(1)
            if (p !is JSObject && p !== Null) typeErr("Object prototype may only be an Object or null: ${Ops.toDisplayString(p)}")
            if (o is JSObject && !o.setPrototypeOf(p as? JSObject)) typeErr("Cannot set prototype")
            o
        }
        ctor.method(realm, "values", 1) { f, _, args, _ -> enumerableOwn(f.realm, Ops.toObject(args.arg(0)), 1) }

        // ---------------- Object.prototype
        proto.method(realm, "hasOwnProperty", 1) { _, t, args, _ ->
            val key = Ops.toPropertyKey(args.arg(0))
            Ops.toObject(t).hasOwnProperty(key)
        }
        proto.method(realm, "isPrototypeOf", 1) { _, t, args, _ ->
            val v = args.arg(0)
            if (v !is JSObject) false
            else {
                val o = Ops.toObject(t)
                var p = v.getPrototypeOf()
                var r = false
                while (p != null) {
                    if (p === o) { r = true; break }
                    p = p.getPrototypeOf()
                }
                r
            }
        }
        proto.method(realm, "propertyIsEnumerable", 1) { _, t, args, _ ->
            val key = Ops.toPropertyKey(args.arg(0))
            val d = Ops.toObject(t).getOwnProperty(key)
            d != null && d.enumerable
        }
        proto.method(realm, "toLocaleString", 0) { _, t, _, _ -> Ops.invoke(t, "toString", EMPTY_ARGS) }
        val toStr = proto.method(realm, "toString", 0) { _, t, _, _ -> objectToString(t) }
        realm.intrinsics["%Object.prototype.toString%"] = toStr
        proto.method(realm, "valueOf", 0) { _, t, _, _ -> Ops.toObject(t) }
        proto.accessor(realm, "__proto__", { _, t, _, _ ->
            Ops.toObject(t).getPrototypeOf() ?: Null
        }, { _, t, args, _ ->
            Ops.requireObjectCoercible(t)
            val p = args.arg(0)
            if ((p is JSObject || p === Null) && t is JSObject) {
                if (!t.setPrototypeOf(p as? JSObject)) typeErr("Object.prototype.__proto__ setter failed")
            }
            Undefined
        })
        proto.method(realm, "__defineGetter__", 2) { _, t, args, _ ->
            val o = Ops.toObject(t)
            val g = args.arg(1)
            if (!Ops.isCallable(g)) typeErr("Getter must be a function")
            o.definePropertyOrThrow(Ops.toPropertyKey(args.arg(0)), PropertyDescriptor().getter(g).enumerable(true).configurable(true))
            Undefined
        }
        proto.method(realm, "__defineSetter__", 2) { _, t, args, _ ->
            val o = Ops.toObject(t)
            val s = args.arg(1)
            if (!Ops.isCallable(s)) typeErr("Setter must be a function")
            o.definePropertyOrThrow(Ops.toPropertyKey(args.arg(0)), PropertyDescriptor().setter(s).enumerable(true).configurable(true))
            Undefined
        }
        proto.method(realm, "__lookupGetter__", 1) { _, t, args, _ -> lookupAccessor(Ops.toObject(t), Ops.toPropertyKey(args.arg(0)), true) }
        proto.method(realm, "__lookupSetter__", 1) { _, t, args, _ -> lookupAccessor(Ops.toObject(t), Ops.toPropertyKey(args.arg(0)), false) }
    }

    private fun lookupAccessor(o0: JSObject, key: Any, getter: Boolean): Any? {
        var o: JSObject? = o0
        while (o != null) {
            val d = o.getOwnProperty(key)
            if (d != null) {
                if (d.isAccessor) return if (getter) d.getter else d.setter
                return Undefined
            }
            o = o.getPrototypeOf()
        }
        return Undefined
    }

    fun objectToString(t: Any?): String {
        if (t === Undefined) return "[object Undefined]"
        if (t === Null) return "[object Null]"
        val o = Ops.toObject(t)
        val builtinTag = when {
            Ops.isArray(o) -> "Array"
            o.isCallable -> "Function"
            else -> when (o.className) {
                "Arguments", "Error", "Boolean", "Number", "String", "Date", "RegExp" -> o.className
                else -> "Object"
            }
        }
        val tag = o.get(JSSymbol.toStringTag, o)
        return "[object ${if (tag is CharSequence) tag.toString() else builtinTag}]"
    }

    fun defineProperties(o: JSObject, props: Any?) {
        val p = Ops.toObject(props)
        val descs = ArrayList<Pair<Any, PropertyDescriptor>>()
        for (k in p.ownPropertyKeys()) {
            val pd = p.getOwnProperty(k)
            if (pd != null && pd.enumerable) {
                descs.add(k to Ops.toPropertyDescriptor(p.get(k, p)))
            }
        }
        for ((k, d) in descs) o.definePropertyOrThrow(k, d)
    }

    /** EnumerableOwnProperties: kind 0 keys, 1 values, 2 entries. */
    fun enumerableOwn(realm: Realm, o: JSObject, kind: Int): JSArray {
        val out = ArrayList<Any?>()
        for (k in o.ownPropertyKeys()) {
            if (k is JSSymbol) continue
            val d = o.getOwnProperty(k) ?: continue
            if (!d.enumerable) continue
            when (kind) {
                0 -> out.add(PK.toValue(k))
                1 -> out.add(o.get(k, o))
                else -> out.add(Builtins.arrayOf(realm, listOf(PK.toValue(k), o.get(k, o))))
            }
        }
        return Builtins.arrayOf(realm, out)
    }

    /** GroupBy(items, callback, keyCoercion property|zero) */
    fun groupBy(realm: Realm, items: Any?, cb: Any?, propertyKeys: Boolean): LinkedHashMap<Any?, ArrayList<Any?>> {
        Ops.requireObjectCoercible(items)
        if (!Ops.isCallable(cb)) typeErr("callback is not a function")
        val groups = LinkedHashMap<Any?, ArrayList<Any?>>()
        val rec = Iteration.getIterator(realm, items, false)
        var k = 0.0
        while (true) {
            val v = Iteration.stepValue(rec)
            if (v === NotFound) break
            try {
                var key = (cb as JSObject).call(Undefined, arrayOf(v, k))
                if (propertyKeys) key = Ops.toPropertyKey(key)
                else if (key is Double && key == 0.0) key = 0.0
                val mk: Any? = if (key is CharSequence) key.toString() else if (!propertyKeys) MapKey.of(key) else key
                groups.getOrPut(mk) { ArrayList() }.add(v)
            } catch (t: Throwable) {
                Iteration.closeAndRethrow(rec, t)
            }
            k++
        }
        return groups
    }
}

/** Wrapper giving SameValueZero semantics to map keys. */
class MapKey private constructor(@JvmField val value: Any?) {
    override fun equals(other: Any?): Boolean = other is MapKey && Ops.sameValueZero(value, other.value)
    override fun hashCode(): Int = when (val v = value) {
        is Double -> if (v == 0.0) 0 else v.hashCode()
        is CharSequence -> v.toString().hashCode()
        null -> 0
        else -> v.hashCode()
    }

    companion object {
        fun of(v: Any?): MapKey = MapKey(if (v is CharSequence) v.toString() else if (v is Double && v == 0.0) 0.0 else v)
    }
}

// ====================================================================== Function

internal object FunctionBuiltins {
    fun install(realm: Realm) {
        val proto = realm.functionPrototype
        val ctor = makeCtor(realm, "Function", 1, proto) { f, _, args, nt ->
            Evaluator.createDynamicFunction(f.realm, nt ?: f, "normal", args)
        }
        realm.functionConstructor = ctor
        realm.global("Function", ctor)
        proto.method(realm, "apply", 2) { _, t, args, _ ->
            if (!Ops.isCallable(t)) typeErr("Function.prototype.apply was called on ${Ops.describe(t)}, which is not a function")
            val arr = args.arg(1)
            val list = if (arr === Undefined || arr === Null) EMPTY_ARGS else Ops.createListFromArrayLike(arr)
            (t as JSObject).call(args.arg(0), list)
        }
        proto.method(realm, "bind", 1) { _, t, args, _ ->
            if (!Ops.isCallable(t)) typeErr("Bind must be called on a function")
            val target = t as JSObject
            val boundArgs = if (args.size > 1) args.copyOfRange(1, args.size) else EMPTY_ARGS
            val bf = BoundFunction(target, args.arg(0), boundArgs, target.getPrototypeOf())
            var len = 0.0
            if (target.hasOwnProperty("length")) {
                val tl = target.get("length", target)
                if (tl is Double) {
                    len = when (tl) {
                        Double.POSITIVE_INFINITY -> tl
                        Double.NEGATIVE_INFINITY -> 0.0
                        else -> maxOf(0.0, Ops.integerPart(tl) - boundArgs.size)
                    }
                }
            }
            bf.defineOwn("length", len, Attr.CONFIGURABLE)
            val tn = target.get("name", target)
            bf.defineOwn("name", "bound " + (if (tn is CharSequence) tn.toString() else ""), Attr.CONFIGURABLE)
            bf
        }
        proto.method(realm, "call", 1) { _, t, args, _ ->
            if (!Ops.isCallable(t)) typeErr("Function.prototype.call was called on ${Ops.describe(t)}, which is not a function")
            (t as JSObject).call(args.arg(0), if (args.size > 1) args.copyOfRange(1, args.size) else EMPTY_ARGS)
        }
        proto.method(realm, "toString", 0) { _, t, _, _ ->
            when (t) {
                is JSFunction -> t.sourceText()
                is JSObject if t.isCallable -> "function () { [native code] }"
                else -> typeErr("Function.prototype.toString requires that 'this' be a Function")
            }
        }
        proto.method(realm, JSSymbol.hasInstance, 1, Attr.NONE) { _, t, args, _ -> Ops.ordinaryHasInstance(t, args.arg(0)) }
        proto.defineAccessor("caller", realm.throwTypeError, realm.throwTypeError, Attr.CONFIGURABLE)
        proto.defineAccessor("arguments", realm.throwTypeError, realm.throwTypeError, Attr.CONFIGURABLE)
    }
}

// ====================================================================== Errors

internal object ErrorBuiltins {
    fun install(realm: Realm) {
        val errProto = JSObject(realm.objectPrototype)
        realm.errorPrototype = errProto
        realm.errorPrototypes[ErrorKind.ERROR] = errProto
        val errCtor = makeCtor(realm, "Error", 1, errProto) { f, _, args, nt -> construct(f, nt, args, ErrorKind.ERROR) }
        realm.errorConstructors[ErrorKind.ERROR] = errCtor
        errProto.value("name", "Error")
        errProto.value("message", "")
        errProto.method(realm, "toString", 0) { _, t, _, _ ->
            if (t !is JSObject) typeErr("Error.prototype.toString called on non-object")
            val n = t.get("name", t)
            val m = t.get("message", t)
            val name = if (n === Undefined) "Error" else Ops.toString(n)
            val msg = if (m === Undefined) "" else Ops.toString(m)
            if (name.isEmpty()) msg else if (msg.isEmpty()) name else "$name: $msg"
        }
        errCtor.method(realm, "isError", 1) { _, _, args, _ -> args.arg(0) is JSErrorObject }
        errProto.accessor(realm, "stack", { _, t, _, _ ->
            if (t !is JSObject) typeErr("Error.prototype.stack getter called on non-object")
            if (t !is JSErrorObject) Undefined else stackString(t)
        }, { f, t, args, _ ->
            if (t !is JSObject) typeErr("Error.prototype.stack setter called on non-object")
            val v = args.arg(0)
            if (v !is CharSequence) typeErr("Error.prototype.stack value must be a string")
            IteratorBuiltins.setterThatIgnoresPrototypeProperties(t, f.realm.errorPrototype, "stack", v.toString())
            Undefined
        })
        realm.global("Error", errCtor)
        for (kind in listOf(ErrorKind.EVAL, ErrorKind.RANGE, ErrorKind.REFERENCE, ErrorKind.SYNTAX, ErrorKind.TYPE, ErrorKind.URI)) {
            val p = JSObject(errProto)
            realm.errorPrototypes[kind] = p
            val c = makeCtor(realm, kind.jsName, 1, p, errCtor) { f, _, args, nt -> construct(f, nt, args, kind) }
            realm.errorConstructors[kind] = c
            p.value("name", kind.jsName)
            p.value("message", "")
            realm.global(kind.jsName, c)
        }
        // AggregateError
        val ap = JSObject(errProto)
        realm.errorPrototypes[ErrorKind.AGGREGATE] = ap
        val ac = makeCtor(realm, "AggregateError", 2, ap, errCtor) { f, _, args, nt ->
            val o = JSErrorObject(Builtins.protoFrom(nt ?: f, { it.errorPrototypes[ErrorKind.AGGREGATE]!! }, f.realm))
            val msg = args.arg(1)
            if (msg !== Undefined) o.defineOwn("message", Ops.toString(msg), Attr.WC)
            installCause(o, args.arg(2))
            val errs = Iteration.toList(f.realm, args.arg(0))
            o.defineOwn("errors", Builtins.arrayOf(f.realm, errs), Attr.WC)
            captureStack(f.realm, o)
            o
        }
        realm.errorConstructors[ErrorKind.AGGREGATE] = ac
        ap.value("name", "AggregateError")
        ap.value("message", "")
        realm.global("AggregateError", ac)
        // SuppressedError
        val sp = JSObject(errProto)
        realm.errorPrototypes[ErrorKind.SUPPRESSED] = sp
        val sc = makeCtor(realm, "SuppressedError", 3, sp, errCtor) { f, _, args, nt ->
            val o = JSErrorObject(Builtins.protoFrom(nt ?: f, { it.errorPrototypes[ErrorKind.SUPPRESSED]!! }, f.realm))
            val msg = args.arg(2)
            if (msg !== Undefined) o.defineOwn("message", Ops.toString(msg), Attr.WC)
            o.defineOwn("error", args.arg(0), Attr.WC)
            o.defineOwn("suppressed", args.arg(1), Attr.WC)
            captureStack(f.realm, o)
            o
        }
        realm.errorConstructors[ErrorKind.SUPPRESSED] = sc
        sp.value("name", "SuppressedError")
        sp.value("message", "")
        realm.global("SuppressedError", sc)
    }

    /** Implementation-defined stack string ("Name: message" header + frames) without invoking user code. */
    fun stackString(e: JSErrorObject): String {
        fun dataProp(k: String): String? {
            var o: JSObject? = e
            while (o != null) {
                val pm = o.props
                if (pm != null) {
                    val i = pm.find(k)
                    if (i >= 0) return if (pm.flags[i] and Attr.ACCESSOR == 0 && pm.values[i] is CharSequence) pm.values[i].toString() else null
                }
                if (o.special and JSObject.SPECIAL_ALL != 0) return null
                o = o.proto
            }
            return null
        }
        val name = e.slotName ?: dataProp("name") ?: "Error"
        val msg = e.slotMessage ?: dataProp("message") ?: ""
        val head = if (msg.isEmpty()) name else "$name: $msg"
        val st = e.stackTrace
        return if (st.isNullOrEmpty()) head else head + "\n" + st
    }

    private fun captureStack(realm: Realm, o: JSErrorObject) {
        if (realm.agent.topFrame != null) o.stackTrace = realm.agent.captureStack()
    }

    private fun installCause(o: JSObject, options: Any?) {
        if (options is JSObject && options.hasProperty("cause")) {
            o.defineOwn("cause", options.get("cause", options), Attr.WC)
        }
    }

    private fun construct(f: NativeFunction, nt: JSObject?, args: Array<Any?>, kind: ErrorKind): JSObject {
        val o = JSErrorObject(Builtins.protoFrom(nt ?: f, { it.errorPrototypes[kind]!! }, f.realm))
        val msg = args.arg(0)
        if (msg !== Undefined) o.defineOwn("message", Ops.toString(msg), Attr.WC)
        installCause(o, args.arg(1))
        captureStack(f.realm, o)
        return o
    }
}

// ====================================================================== Symbol & Boolean

internal object SymbolBuiltins {
    fun install(realm: Realm) {
        val proto = JSObject(realm.objectPrototype)
        realm.symbolPrototype = proto
        val ctor = makeCtor(realm, "Symbol", 0, proto) { _, _, args, nt ->
            if (nt != null) typeErr("Symbol is not a constructor")
            val d = args.arg(0)
            JSSymbol(if (d === Undefined) null else Ops.toString(d))
        }
        realm.global("Symbol", ctor)
        for ((n, s) in JSSymbol.wellKnown) ctor.defineOwn(n, s, Attr.NONE)
        ctor.method(realm, "for", 1) { f, _, args, _ ->
            val key = Ops.toString(args.arg(0))
            f.realm.agent.symbolRegistry.getOrPut(key) { JSSymbol(key, key) }
        }
        ctor.method(realm, "keyFor", 1) { _, _, args, _ ->
            val s = args.arg(0) as? JSSymbol ?: typeErr("${Ops.toDisplayString(args.arg(0))} is not a symbol")
            s.registryKey ?: Undefined
        }
        fun thisSymbol(t: Any?): JSSymbol = when (t) {
            is JSSymbol -> t
            is JSPrimitiveWrapper -> t.primitive as? JSSymbol ?: typeErr("not a Symbol")
            else -> typeErr("Symbol.prototype method called on incompatible receiver")
        }
        proto.getter(realm, "description") { _, t, _, _ -> thisSymbol(t).description ?: Undefined }
        proto.method(realm, "toString", 0) { _, t, _, _ -> thisSymbol(t).toString() }
        proto.method(realm, "valueOf", 0) { _, t, _, _ -> thisSymbol(t) }
        proto.method(realm, JSSymbol.toPrimitive, 1, Attr.CONFIGURABLE) { _, t, _, _ -> thisSymbol(t) }
        proto.value(JSSymbol.toStringTag, "Symbol", Attr.CONFIGURABLE)
    }
}

internal object BooleanBuiltins {
    fun install(realm: Realm) {
        val proto = JSPrimitiveWrapper(realm.objectPrototype, false)
        realm.booleanPrototype = proto
        val ctor = makeCtor(realm, "Boolean", 1, proto) { _, _, args, nt ->
            val b = Ops.toBoolean(args.arg(0))
            if (nt == null) b else JSPrimitiveWrapper(Ops.getPrototypeFromConstructor(nt) { it.booleanPrototype }, b)
        }
        realm.global("Boolean", ctor)
        fun thisBool(t: Any?): Boolean = when (t) {
            is Boolean -> t
            is JSPrimitiveWrapper -> t.primitive as? Boolean ?: typeErr("not a Boolean")
            else -> typeErr("Boolean.prototype method called on incompatible receiver")
        }
        proto.method(realm, "toString", 0) { _, t, _, _ -> if (thisBool(t)) "true" else "false" }
        proto.method(realm, "valueOf", 0) { _, t, _, _ -> thisBool(t) }
    }
}
