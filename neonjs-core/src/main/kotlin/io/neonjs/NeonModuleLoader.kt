package io.neonjs

import java.nio.file.Files
import java.nio.file.Path

/** Host module loader: maps specifiers to canonical keys and keys to source text. */
interface NeonModuleLoader {
    /** Resolves [specifier] imported from the module [referrer] (null for top-level code). */
    fun resolve(specifier: String, referrer: String?): String

    /** Returns the module source for [key], or null if it does not exist. */
    fun load(key: String): String?

    /** Raw contents for `import ... with { type: "bytes" }` (default: the UTF-8 encoding of [load]). */
    fun loadBytes(key: String): ByteArray? = load(key)?.toByteArray(Charsets.UTF_8)
}

/**
 * Loads modules from a directory tree. Specifiers resolve relative to the importing module; resolution outside
 * [root] is rejected, so scripts cannot read arbitrary files.
 */
class FileSystemModuleLoader(root: Path) : NeonModuleLoader {
    private val root: Path = root.toAbsolutePath().normalize()

    override fun resolve(specifier: String, referrer: String?): String {
        val base = if (referrer != null) java.nio.file.Paths.get(referrer).parent ?: root else root
        val p = (if (specifier.startsWith("/")) root.resolve(specifier.removePrefix("/")) else base.resolve(specifier)).normalize()
        if (!p.startsWith(root)) throw SecurityException("Module '$specifier' resolves outside the module root")
        return p.toString()
    }

    override fun load(key: String): String? = file(key)?.let { String(Files.readAllBytes(it), Charsets.UTF_8) }

    override fun loadBytes(key: String): ByteArray? = file(key)?.let { Files.readAllBytes(it) }

    private fun file(key: String): Path? {
        val p = java.nio.file.Paths.get(key).normalize()
        if (!p.startsWith(root) || !Files.isRegularFile(p)) return null
        // resolve symbolic links: a link inside the root must not expose files outside it
        val real = p.toRealPath()
        if (!real.startsWith(root.toRealPath())) throw SecurityException("Module '$key' resolves outside the module root")
        return real
    }
}

/** In-memory module loader (specifiers are keys; relative paths resolved against the referrer's key). */
class MapModuleLoader(private val modules: Map<String, String>) : NeonModuleLoader {
    override fun resolve(specifier: String, referrer: String?): String {
        if (!specifier.startsWith("./") && !specifier.startsWith("../")) return specifier
        val base = referrer?.substringBeforeLast('/', "") ?: ""
        val parts = ArrayList<String>()
        if (base.isNotEmpty()) parts.addAll(base.split('/'))
        for (seg in specifier.split('/')) {
            when (seg) {
                ".", "" -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    override fun load(key: String): String? = modules[key]
}
