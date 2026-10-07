package io.neonjs.intl

import io.neonjs.runtime.*

// Small copies of the core builtin DSL (whose helpers are `internal` to neonjs-core).

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

internal fun makeCtor(realm: Realm, name: String, length: Int, proto: JSObject, impl: NativeImpl): NativeFunction {
    val c = NativeFunction(realm, name, length, impl, isConstructor = true)
    c.defineOwn("prototype", proto, Attr.NONE)
    proto.defineOwn("constructor", c, Attr.WC)
    return c
}

internal fun typeErr(msg: String): Nothing = throw JSException.typeError(msg)
internal fun rangeErr(msg: String): Nothing = throw JSException.rangeError(msg)

/** CreateArrayFromList */
internal fun jsArray(realm: Realm, items: List<Any?>): JSArray = JSArray.of(realm.arrayPrototype, items.toTypedArray())

/** OrdinaryObjectCreate(%Object.prototype%) */
internal fun plainObject(realm: Realm): JSObject = JSObject(realm.objectPrototype)

/** OrdinaryCreateFromConstructor: prototype from newTarget (or the intrinsic named [protoName]). */
internal fun protoFromCtor(newTarget: JSObject?, protoName: String, realm: Realm): JSObject =
    if (newTarget == null) realm.intrinsic(protoName) else Ops.getPrototypeFromConstructor(newTarget) { it.intrinsic(protoName) }

internal fun str(v: Any?): String = if (v is CharSequence) v.toString() else Ops.toString(v)

/** Checks a produced string against the context's string-length limit before returning it to JS. */
internal fun Realm.checkedString(s: CharSequence): String {
    agent.checkStringLength(s.length.toLong())
    return s.toString()
}

/** Calls [Agent.checkInterrupt] once every 1024 iterations of a loop over a user-controlled size. */
internal fun Realm.tick(i: Long) {
    if (i and 1023L == 1023L) agent.checkInterrupt()
}

/** Creates a { type, value } part object as used by every formatToParts. */
internal fun partObject(realm: Realm, type: String, value: String): JSObject {
    val o = plainObject(realm)
    o.createDataPropertyOrThrow("type", type)
    o.createDataPropertyOrThrow("value", value)
    return o
}
