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
 * `(exports, require, module, binding)`, compiled once per engine (code blocks are shared by the contexts of an
 * engine) and run once per realm. `binding` gives the Kotlin parts; it is an argument only, so scripts never reach it.
 */
internal object NodeLib {
    private val compiled = WeakHashMap<NeonEngine, ConcurrentHashMap<String, CodeBlock>>()

    private fun source(name: String): String {
        val path = "lib/" + name.replace('/', '_') + ".js"
        val stream = NodeLib::class.java.getResourceAsStream(path) ?: throw IllegalStateException("missing $path")
        return stream.use { String(it.readBytes(), Charsets.UTF_8) }
    }

    private fun code(realm: Realm, name: String): CodeBlock {
        val engine = (realm.hostData as? NeonContext)?.engine
        val cache = if (engine == null) null else synchronized(compiled) { compiled.getOrPut(engine) { ConcurrentHashMap() } }
        cache?.get(name)?.let { return it }
        // the wrapper opens on the first line of the source, so that stack traces give the library's own lines
        val cb = Evaluator.compileScript(realm, "(function (exports, require, module, binding) { " + source(name) + "\n})", "node:$name")
        cache?.putIfAbsent(name, cb)
        return cb
    }

    /** Runs the library [name] in the realm of [rt] and returns its `module.exports`. */
    fun load(rt: NodeRuntime, name: String): Any? {
        val realm = rt.realm
        val fn = Evaluator.runScript(realm, code(realm, name)) as JSObject
        val module = JSObject(realm.objectPrototype)
        val exports = JSObject(realm.objectPrototype)
        module.createDataProperty("exports", exports)
        Ops.call(fn, Undefined, arrayOf(exports, rt.require, module, rt.binding))
        return module.get("exports", module)
    }
}
