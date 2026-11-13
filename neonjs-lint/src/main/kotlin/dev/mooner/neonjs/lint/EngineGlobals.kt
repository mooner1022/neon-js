package dev.mooner.neonjs.lint

/**
 * The global names NeonJS's engine installs, by what installs them (NeonEngine.Builder's options and the extension
 * modules), for [LintOptions.globals]: a host adds its own (an app's API objects) with [Globals.plus]. All are
 * [GlobalKind.READ_ONLY]: assigning a built-in is told. EngineGlobalsTest checks them against the engine's realms.
 */
object EngineGlobals {
    /** ECMAScript's (every engine): Object, Promise, Temporal, ... */
    @JvmField
    val ECMASCRIPT: Set<String> = setOf(
        "AggregateError", "Array", "ArrayBuffer", "AsyncDisposableStack", "Atomics", "BigInt", "BigInt64Array", "BigUint64Array",
        "Boolean", "DataView", "Date", "DisposableStack", "Error", "EvalError", "FinalizationRegistry", "Float16Array",
        "Float32Array", "Float64Array", "Function", "Infinity", "Int16Array", "Int32Array", "Int8Array", "Iterator",
        "JSON", "Map", "Math", "NaN", "Number", "Object", "Promise", "Proxy",
        "RangeError", "ReferenceError", "Reflect", "RegExp", "Set", "ShadowRealm", "SharedArrayBuffer", "String",
        "SuppressedError", "Symbol", "SyntaxError", "Temporal", "TypeError", "URIError", "Uint16Array", "Uint32Array",
        "Uint8Array", "Uint8ClampedArray", "WeakMap", "WeakRef", "WeakSet", "decodeURI", "decodeURIComponent", "encodeURI",
        "encodeURIComponent", "escape", "eval", "globalThis", "isFinite", "isNaN", "parseFloat", "parseInt",
        "undefined", "unescape",
    )

    /** `console`, unless the engine is built with `console(null)`. */
    @JvmField
    val CONSOLE: Set<String> = setOf(
        "console",
    )

    /** The web globals (`webGlobals(true)`): timers, URL, TextEncoder, events, ... */
    @JvmField
    val WEB: Set<String> = setOf(
        "AbortController", "AbortSignal", "Blob", "CloseEvent", "Crypto", "CustomEvent", "DOMException", "ErrorEvent",
        "Event", "EventTarget", "File", "FormData", "Headers", "MessageEvent", "Performance", "QuotaExceededError",
        "Request", "Response", "TextDecoder", "TextEncoder", "URL", "URLSearchParams", "atob", "btoa",
        "clearInterval", "clearTimeout", "crypto", "performance", "queueMicrotask", "setInterval", "setTimeout", "structuredClone",
    )

    /** `Intl`, when neonjs-intl is on the class path (and `intl` is not turned off). */
    @JvmField
    val INTL: Set<String> = setOf(
        "Intl",
    )

    /** `Java`, when the sandbox policy exposes it (`exposeJavaGlobal(true)`). */
    @JvmField
    val JAVA: Set<String> = setOf(
        "Java",
    )

    /** neonjs-net's (NetExtension): `fetch`, `WebSocket`. */
    @JvmField
    val NET: Set<String> = setOf(
        "WebSocket", "fetch",
    )

    /** neonjs-node's (NodeExtension): `require`, `Buffer`, ... */
    @JvmField
    val NODE: Set<String> = setOf(
        "Buffer", "clearImmediate", "global", "require", "setImmediate",
    )

    /** The globals of an engine built with these options. */
    @JvmStatic
    @JvmOverloads
    fun of(
        console: Boolean = true,
        webGlobals: Boolean = false,
        intl: Boolean = false,
        java: Boolean = false,
        net: Boolean = false,
        node: Boolean = false,
    ): Globals {
        val names = HashSet(ECMASCRIPT)
        if (console) names.addAll(CONSOLE)
        if (webGlobals || net || node) names.addAll(WEB)
        if (intl) names.addAll(INTL)
        if (java) names.addAll(JAVA)
        if (net) names.addAll(NET)
        if (node) names.addAll(NODE)
        return Globals.of(names, GlobalKind.READ_ONLY)
    }
}
