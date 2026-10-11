package dev.mooner.neonjs.node

import dev.mooner.neonjs.builtins.*
import dev.mooner.neonjs.interop.HostClassObject
import dev.mooner.neonjs.interop.HostObject
import dev.mooner.neonjs.runtime.*
import dev.mooner.neonjs.vm.*

/**
 * What util.types and util.inspect cannot see from JS: an object's internal slots (`binding.types`). None of these
 * runs script code: a proxy is a proxy here, never asked anything.
 */
internal object Types {
    private val CHECKS: List<Pair<String, (Any?) -> Boolean>> = listOf(
        c("isExternal") { _ -> false },
        c("isDate") { v -> v is JSDate },
        c("isArgumentsObject") { v -> v is JSArgumentsObject },
        c("isBigIntObject") { v -> v is JSPrimitiveWrapper && v.primitive is java.math.BigInteger },
        c("isBooleanObject") { v -> v is JSPrimitiveWrapper && v.primitive is Boolean },
        c("isNumberObject") { v -> v is JSPrimitiveWrapper && v.primitive is Double },
        c("isStringObject") { v -> v is JSStringObject },
        c("isSymbolObject") { v -> v is JSPrimitiveWrapper && v.primitive is JSSymbol },
        c("isBoxedPrimitive") { v -> v is JSPrimitiveWrapper || v is JSStringObject },
        c("isNativeError") { v -> v is JSErrorObject },
        c("isRegExp") { v -> v is JSRegExp },
        c("isAsyncFunction") { v -> v is JSClosure && v.code.isAsync },
        c("isGeneratorFunction") { v -> v is JSClosure && v.code.isGenerator },
        c("isGeneratorObject") { v -> v is JSGenerator || v is JSAsyncGenerator },
        c("isPromise") { v -> v is JSPromise },
        c("isMap") { v -> v is JSMapObject && !v.isSet },
        c("isSet") { v -> v is JSMapObject && v.isSet },
        c("isMapIterator") { v -> v is CollectionIterator && iteratorTag(v) == "Map Iterator" },
        c("isSetIterator") { v -> v is CollectionIterator && iteratorTag(v) == "Set Iterator" },
        c("isWeakMap") { v -> v is JSWeakCollection && !v.isSet },
        c("isWeakSet") { v -> v is JSWeakCollection && v.isSet },
        c("isArrayBuffer") { v -> v is JSArrayBuffer && !v.isShared },
        c("isSharedArrayBuffer") { v -> v is JSArrayBuffer && v.isShared },
        c("isAnyArrayBuffer") { v -> v is JSArrayBuffer },
        c("isDataView") { v -> v is JSDataView },
        c("isArrayBufferView") { v -> v is JSTypedArray || v is JSDataView },
        c("isTypedArray") { v -> v is JSTypedArray },
        c("isProxy") { v -> v is ProxyObject },
        c("isModuleNamespaceObject") { v -> v is ModuleNamespace },
        c("isKeyObject") { _ -> false },
        c("isCryptoKey") { _ -> false },
    ) + ElementType.entries.map { t -> c("is${t.ctorName}") { v -> v is JSTypedArray && v.type == t } }

    private fun c(name: String, check: (Any?) -> Boolean): Pair<String, (Any?) -> Boolean> = name to check

    /** The tag a Map or Set iterator was made with (its prototype's), which no script can change for the object. */
    private fun iteratorTag(it: CollectionIterator): String? {
        var p = it.proto
        while (p != null) {
            val d = p.ordinaryGetOwnProperty(JSSymbol.toStringTag)
            if (d != null && !d.isAccessor && d.value is CharSequence) {
                val s = d.value.toString()
                if (s == "Map Iterator" || s == "Set Iterator") return s
            }
            p = p.proto
        }
        return null
    }

    fun create(realm: Realm): JSObject {
        val b = JSObject(null)
        fun fn(name: String, length: Int, impl: NativeImpl) = b.defineOwn(name, NativeFunction(realm, name, length, impl), Attr.NONE)
        for ((name, check) in CHECKS) fn(name, 1) { _, _, a, _ -> check(a.arg(0)) }

        // [state, result]: 0 pending, 1 fulfilled, 2 rejected
        fn("promiseDetails", 1) { f, _, a, _ ->
            val p = a.arg(0) as? JSPromise ?: return@fn Undefined
            val state = when (p.state) { JSPromise.PENDING -> 0.0; JSPromise.FULFILLED -> 1.0; else -> 2.0 }
            JSArray.of(f.realm.arrayPrototype, arrayOf(state, if (p.state == JSPromise.PENDING) Undefined else p.result))
        }
        // [target, handler] ([null, null] once revoked), or undefined for anything else
        fn("proxyDetails", 1) { f, _, a, _ ->
            val p = a.arg(0) as? ProxyObject ?: return@fn Undefined
            JSArray.of(f.realm.arrayPrototype, arrayOf(p.target ?: Null, p.handler ?: Null))
        }
        fn("isClass", 1) { _, _, a, _ -> (a.arg(0) as? JSClosure)?.code?.isClassConstructor == true }
        // the engine's class name of an object, for what has no constructor to name it
        fn("className", 1) { _, _, a, _ -> (a.arg(0) as? JSObject)?.className ?: "Object" }
        // a host object (interop) as one opaque line: its members are not walked
        fn("hostTag", 1) { _, _, a, _ ->
            when (val v = a.arg(0)) {
                is HostObject -> "[Host ${v.target.javaClass.simpleName}]"
                is HostClassObject -> "[HostClass ${v.cls.name}]"
                else -> Undefined
            }
        }
        // own keys that are not array indices (an array's or typed array's other properties), without listing the
        // elements; strings and symbols, enumerable ones only unless [1] is true
        fn("ownNonIndexKeys", 2) { f, _, a, _ ->
            val o = a.arg(0) as? JSObject ?: return@fn JSArray.of(f.realm.arrayPrototype, emptyArray())
            val all = Ops.toBoolean(a.arg(1))
            val keys = if (o is JSTypedArray) o.ordinaryOwnKeys(null) else o.ownPropertyKeys()
            val out = ArrayList<Any?>()
            for (k in keys) {
                if (k is Int || (k is String && PK.arrayIndex(k) >= 0)) continue
                if (!all) {
                    val d = o.getOwnProperty(k) ?: continue
                    if (!d.enumerable) continue
                }
                out.add(if (k is Int) k.toString() else k)
            }
            JSArray.of(f.realm.arrayPrototype, out.toTypedArray())
        }
        return b
    }
}
