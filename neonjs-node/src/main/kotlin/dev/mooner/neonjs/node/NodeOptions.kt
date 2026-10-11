package dev.mooner.neonjs.node

import java.util.function.IntConsumer

/**
 * What the Node.js APIs of a context see of the host. Nothing of the host's own environment shows unless given
 * here: `process.env` is empty by default, and `process.exit` never ends the host process.
 */
class NodeOptions private constructor(b: Builder) {
    /** `process.env` (default: empty). */
    val env: Map<String, String> = b.env.toMap()
    /** `process.argv` (default: `["neonjs"]`). */
    val argv: List<String> = b.argv.toList()
    /** `process.cwd()` and what relative paths resolve against (default: `/`). */
    val cwd: String = b.cwd
    /** `process.platform` (default: `android` on Android, else that of the host's OS: `linux`, `win32`, `darwin`). */
    val platform: String = b.platform ?: hostPlatform()
    /** Called with the code of `process.exit(code)`; by default the context's evaluation is interrupted. */
    val onExit: IntConsumer? = b.onExit

    class Builder {
        internal val env = LinkedHashMap<String, String>()
        internal val argv = arrayListOf("neonjs")
        internal var cwd = "/"
        internal var platform: String? = null
        internal var onExit: IntConsumer? = null

        fun env(vars: Map<String, String>) = apply { env.clear(); env.putAll(vars) }
        fun argv(args: List<String>) = apply { argv.clear(); argv.addAll(args) }
        fun cwd(path: String) = apply { cwd = path }
        fun platform(name: String) = apply { platform = name }
        fun onExit(callback: IntConsumer) = apply { onExit = callback }
        fun build() = NodeOptions(this)
    }

    companion object {
        @JvmField val DEFAULT: NodeOptions = Builder().build()

        @JvmStatic fun builder() = Builder()

        private fun hostPlatform(): String {
            if (System.getProperty("java.vendor", "").contains("Android", ignoreCase = true)) return "android"
            val os = System.getProperty("os.name", "").lowercase()
            return when {
                os.startsWith("windows") -> "win32"
                os.startsWith("mac") || os.startsWith("darwin") -> "darwin"
                else -> "linux"
            }
        }
    }
}
