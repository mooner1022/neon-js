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
        val g = realm.globalObject
        g.defineOwn("global", g, Attr.WC)
        g.defineOwn("require", require, Attr.WC)
        ErrorStack.install(realm)
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

        /** The Node.js side of [realm] (a realm the extension was installed in). */
        fun of(realm: Realm): NodeRuntime = realm.intrinsicsAny[KEY] as NodeRuntime
    }
}
