package dev.mooner.neonjs

/**
 * An engine module installed into every context of an engine with [NeonEngine.Builder.extension] (neonjs-node's
 * `NodeExtension` is one). [install] gets the context's realm after the built-ins, the console and the web globals;
 * it may define globals and built-in modules (`dev.mooner.neonjs.vm.Modules.defineBuiltin`). The realm is the
 * engine's internal view: an extension is built against one version of the engine.
 */
fun interface NeonExtension {
    fun install(realm: dev.mooner.neonjs.runtime.Realm)
}
