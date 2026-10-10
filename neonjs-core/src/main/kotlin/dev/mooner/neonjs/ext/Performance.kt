package dev.mooner.neonjs.ext

import dev.mooner.neonjs.runtime.*

/**
 * `performance` (High Resolution Time): `now()` counts milliseconds from the realm's time origin, set when the web
 * globals are installed, coarsened to 5 microseconds as browsers do for isolated contexts (a finer clock would only
 * help timing attacks); under a fixed sandbox clock it is always 0 and `timeOrigin` is the fixed time.
 */
internal object Performance {
    class JSPerformance(proto: JSObject?) : Events.JSEventTarget(proto) {
        override val className: String get() = "Performance"
    }

    private const val RESOLUTION_MS = 0.005

    /** Starts the realm's clock: what `performance.now()` and event time stamps count from. */
    fun startClock(realm: Realm) {
        realm.intrinsicsAny["%TimeOrigin%"] = System.nanoTime()
        realm.intrinsicsAny["%TimeOriginMillis%"] = realm.agent.currentTimeMillis()
    }

    /** DOMHighResTimeStamp: milliseconds since the realm's time origin, coarsened. */
    fun now(realm: Realm): Double {
        if (realm.agent.clock != null) return 0.0
        val origin = realm.intrinsicsAny.getOrPut("%TimeOrigin%") { System.nanoTime() } as Long
        val ms = (System.nanoTime() - origin) / 1e6
        return Math.floor(ms / RESOLUTION_MS) * RESOLUTION_MS
    }

    fun install(realm: Realm) {
        val i = WebInterface.define(realm, "Performance", 0, parent = "EventTarget")
        fun perf(t: Any?, m: String) = Idl.self<JSPerformance>(t, "Performance", m)
        i.operation("now", 0) { f, t, _, _ -> perf(t, "now"); now(f.realm) }
        i.attribute("timeOrigin", { f, t, _, _ -> perf(t, "timeOrigin"); f.realm.intrinsicsAny["%TimeOriginMillis%"] as Double })
        i.operation("toJSON", 0) { f, t, _, _ ->
            perf(t, "toJSON")
            val o = JSObject(f.realm.objectPrototype)
            o.createDataProperty("timeOrigin", f.realm.intrinsicsAny["%TimeOriginMillis%"])
            o
        }
        val performance = JSPerformance(i.proto)
        // [Replaceable] readonly attribute of the global object: setting it replaces the accessor with a data property
        val get = NativeFunction(realm, "performance", 0, { _, _, _, _ -> performance }, namePrefix = "get")
        val set = NativeFunction(realm, "performance", 1, { _, t, a, _ ->
            val target = if (t is JSObject) t else realm.globalObject
            target.defineOwnProperty("performance", PropertyDescriptor.data(a.arg(0), Attr.ALL))
            Undefined
        }, namePrefix = "set")
        realm.globalObject.defineAccessor("performance", get, set, Attr.ENUMERABLE or Attr.CONFIGURABLE)
    }
}
