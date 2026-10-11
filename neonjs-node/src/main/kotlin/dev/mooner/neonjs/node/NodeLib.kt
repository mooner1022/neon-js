package dev.mooner.neonjs.node

import dev.mooner.neonjs.NeonContext
import dev.mooner.neonjs.NeonEngine
import dev.mooner.neonjs.compiler.CodeBlock
import dev.mooner.neonjs.runtime.*
import dev.mooner.neonjs.vm.Evaluator
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * The modules written in JS (resources `lib/<name>.js`, `/` in a name as `_`): each is a CommonJS function of
 * `(exports, require, module, binding[, primordials][, process])` as Node's own libraries are, compiled once per engine
 * (code blocks are shared by the contexts of an engine) and run once per realm. Their `require` also resolves
 * `internal/...`, the libraries' own modules, which scripts cannot require. `binding` gives the Kotlin parts; it is an
 * argument only, so scripts never reach it. `primordials` (the builtins as Node's libraries take them, made on first
 * use) and `process` are given to the libraries that use them.
 */
internal object NodeLib {
    private class Lib(@JvmField val code: CodeBlock, @JvmField val usesPrimordials: Boolean, @JvmField val usesProcess: Boolean)

    private val compiled = WeakHashMap<NeonEngine, ConcurrentHashMap<String, Lib>>()

    /** The libraries `process` would be loaded for and that process.js itself needs. */
    private val PROCESS_DEPENDENCIES = setOf("process", "events", "primordials")
    private val USES_PROCESS = Regex("""(?<![\w.$'"])process\.""")

    private fun source(name: String): String {
        val path = "lib/" + name.replace('/', '_') + ".js"
        val stream = NodeLib::class.java.getResourceAsStream(path) ?: throw IllegalStateException("missing $path")
        return stream.use { String(it.readBytes(), Charsets.UTF_8) }
    }

    private fun lib(realm: Realm, name: String): Lib {
        val engine = (realm.hostData as? NeonContext)?.engine
        val cache = if (engine == null) null else synchronized(compiled) { compiled.getOrPut(engine) { ConcurrentHashMap() } }
        cache?.get(name)?.let { return it }
        val src = source(name)
        val usesPrimordials = name != "primordials" && src.contains("} = primordials;")
        val usesProcess = name !in PROCESS_DEPENDENCIES && USES_PROCESS.containsMatchIn(src)
        // only the parameters the library uses (a library may declare a process of its own); the wrapper opens on the
        // first line of the source, so that stack traces give the library's own lines
        val params = "exports, require, module, binding" + (if (usesPrimordials) ", primordials" else "") + (if (usesProcess) ", process" else "")
        val cb = Evaluator.compileScript(realm, "(function ($params) { " + src + "\n})", "node:$name")
        val lib = Lib(cb, usesPrimordials, usesProcess)
        cache?.putIfAbsent(name, lib)
        return lib
    }

    /**
     * Runs the library [name] in the realm of [rt] and returns its `module.exports`. [loading] gets the module object
     * before the library runs, so that a library required again while it loads (a cycle) gives what it has so far.
     */
    fun load(rt: NodeRuntime, name: String, loading: ((JSObject) -> Unit)? = null): Any? {
        val realm = rt.realm
        val lib = lib(realm, name)
        val fn = Evaluator.runScript(realm, lib.code) as JSObject
        val module = JSObject(realm.objectPrototype)
        val exports = JSObject(realm.objectPrototype)
        module.createDataProperty("exports", exports)
        loading?.invoke(module)
        val args = arrayListOf<Any?>(exports, rt.libRequire, module, rt.binding)
        if (lib.usesPrimordials) args.add(rt.primordials)
        if (lib.usesProcess) args.add(rt.require("node:process"))
        Ops.call(fn, Undefined, args.toTypedArray())
        return module.get("exports", module)
    }
}
