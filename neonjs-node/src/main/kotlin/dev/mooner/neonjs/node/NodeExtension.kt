package dev.mooner.neonjs.node

import dev.mooner.neonjs.NeonExtension
import dev.mooner.neonjs.ext.WebGlobals
import dev.mooner.neonjs.runtime.*
import dev.mooner.neonjs.vm.Modules

/**
 * Node.js APIs for the contexts of an engine: `NeonEngine.builder().webGlobals(true).extension(NodeExtension())`.
 * Scripts get the `node:` built-in modules (also without the prefix), `require` for them and for the host's own
 * modules, the `global` object and `Error.captureStackTrace`. There is no `process` global: scripts import it
 * (`import process from 'node:process'`), so libraries that tell Node from other runtimes by `globalThis.process` take
 * their web-standard paths. Needs the web globals, which several modules re-export (`node:url`'s `URL`, `node:events`'
 * `EventTarget`...).
 */
class NodeExtension @JvmOverloads constructor(val options: NodeOptions = NodeOptions.DEFAULT) : NeonExtension {
    override fun install(realm: Realm) {
        check(WebGlobals.timersOf(realm) != null) { "neonjs-node needs the web globals: NeonEngine.builder().webGlobals(true)" }
        NodeRuntime(realm, options).install()
    }
}

/** The Node.js side of one realm. */
internal class NodeRuntime(@JvmField val realm: Realm, @JvmField val options: NodeOptions) {
    /** The `binding` argument of the JS libraries. */
    val binding: JSObject by lazy { Binding(this).create() }

    /** The process object, once node:process was made: its listeners see what the script left uncaught. */
    @JvmField var process: JSObject? = null

    /** `require`: the host's modules first (as for import), then the built-in modules; nothing else. */
    val require: NativeFunction = NativeFunction(realm, "require", 1, { _, _, a, _ ->
        if (a.isEmpty() || a[0] !is CharSequence) throw nodeError(ErrorKind.TYPE, "The \"id\" argument must be of type string", "ERR_INVALID_ARG_TYPE")
        require(a[0].toString())
    })

    fun install() {
        realm.intrinsicsAny[KEY] = this
        require.defineOwn("resolve", NativeFunction(realm, "resolve", 1, { _, _, a, _ ->
            val id = Ops.toString(a.arg(0))
            if (Modules.hostModule(realm, id) == null && Modules.builtin(realm, id) == null) throw notFound(id)
            id
        }), Attr.WC)
        require.defineOwn("cache", JSObject(null), Attr.WC)
        defineModule("module") { moduleExports() }
        for (name in JS_MODULES) defineModule(name) { NodeLib.load(this, name) }
        defineModule("util/types") { (require("node:util") as JSObject).let { it.get("types", it) } }
        val g = realm.globalObject
        g.defineOwn("global", g, Attr.WC)
        g.defineOwn("require", require, Attr.WC)
        // Buffer, as in Node, but node:buffer runs only once a script looks at it
        g.definePropertyOrThrow("Buffer", PropertyDescriptor.accessor(
            NativeFunction(realm, "get Buffer", 0, { _, _, _, _ ->
                val buffer = (require("node:buffer") as JSObject).let { it.get("Buffer", it) }
                g.definePropertyOrThrow("Buffer", PropertyDescriptor.data(buffer, Attr.WC))
                buffer
            }),
            NativeFunction(realm, "set Buffer", 1, { _, _, a, _ ->
                g.definePropertyOrThrow("Buffer", PropertyDescriptor.data(a.arg(0), Attr.WC))
                Undefined
            }),
            Attr.CONFIGURABLE,
        ))
        ErrorStack.install(realm)
        // Node's timers are the global ones (Timeout objects rather than numbers), from the start
        val timers = require("node:timers") as JSObject
        for (n in TIMER_GLOBALS) g.defineOwn(n, timers.get(n, timers), Attr.ALL)
        // process.on('uncaughtException' / 'unhandledRejection') first, then whatever was there (the host's handler
        // comes after both)
        val previous = realm.agent.uncaughtInterceptor
        realm.agent.uncaughtInterceptor = { e, p -> uncaught(e, p) || previous?.invoke(e, p) == true }
    }

    /** Emits an uncaught exception or unhandled rejection on the process object; false when nothing listens. */
    private fun uncaught(e: RuntimeException?, p: dev.mooner.neonjs.vm.JSPromise?): Boolean {
        val proc = process ?: return false
        val event = if (e != null) "uncaughtException" else "unhandledRejection"
        if (Ops.toNumber(Ops.invoke(proc, "listenerCount", arrayOf(event))) == 0.0) return false
        if (e != null) {
            val value = if (e is JSException) e.value else realm.agent.hostExceptionToJS(e as dev.mooner.neonjs.vm.HostException, realm)
            Ops.invoke(proc, "emit", arrayOf(event, value, "uncaughtException"))
        } else {
            Ops.invoke(proc, "emit", arrayOf(event, p!!.result, p))
        }
        return true
    }

    private val internals = HashMap<String, Any?>()

    /** The libraries' own module `internal/[name]`, run once per realm (on first use). */
    fun internal(name: String): Any? {
        internals[name]?.let { return it }
        val exports = NodeLib.load(this, "internal/$name")
        internals[name] = exports
        return exports
    }

    fun require(id: String): Any? {
        Modules.hostModuleObject(realm, id)?.let { return it }
        val b = Modules.builtin(realm, id) ?: throw notFound(id)
        return b.exports()
    }

    /** Defines the built-in module `node:[name]` (also importable and requirable as [name]). */
    fun defineModule(name: String, create: () -> Any?) {
        Modules.defineBuiltin(realm, Modules.Builtin("node:$name", create), name)
    }

    private fun notFound(id: String) = nodeError(ErrorKind.ERROR, "Cannot find module '$id' (require loads built-in and host modules only)", "MODULE_NOT_FOUND")

    private fun moduleExports(): JSObject {
        val o = JSObject(realm.objectPrototype)
        val names = Modules.builtinNames(realm).map { it.removePrefix("node:") }
        o.createDataProperty("builtinModules", JSArray.of(realm.arrayPrototype, names.toTypedArray<Any?>()))
        o.createDataProperty("createRequire", NativeFunction(realm, "createRequire", 1, { _, _, a, _ ->
            if (a.isEmpty() || (a[0] !is CharSequence && a[0] !is JSObject)) {
                throw nodeError(ErrorKind.TYPE, "The \"filename\" argument must be a file URL object, a file URL string or an absolute path", "ERR_INVALID_ARG_VALUE")
            }
            require
        }))
        o.createDataProperty("isBuiltin", NativeFunction(realm, "isBuiltin", 1, { _, _, a, _ ->
            val id = a.arg(0)
            id is CharSequence && Modules.builtin(realm, id.toString()) != null
        }))
        return o
    }

    /** An error of [kind] with Node's `code` property. */
    fun nodeError(kind: ErrorKind, message: String, code: String): JSException {
        val e = realm.newError(kind, message)
        e.createDataProperty("code", code)
        return JSException(e)
    }

    companion object {
        const val KEY = "%Node%"
        /** The built-in modules written in JS (resources lib/<name>.js). */
        private val JS_MODULES = listOf("buffer", "events", "process", "string_decoder", "timers", "timers/promises", "util")
        private val TIMER_GLOBALS = listOf("setTimeout", "clearTimeout", "setInterval", "clearInterval", "setImmediate", "clearImmediate")

        /** The Node.js side of [realm] (a realm the extension was installed in). */
        fun of(realm: Realm): NodeRuntime = realm.intrinsicsAny[KEY] as NodeRuntime
    }
}
