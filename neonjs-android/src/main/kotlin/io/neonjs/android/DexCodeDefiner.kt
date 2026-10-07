package io.neonjs.android

import io.neonjs.jit.CodeDefiner
import java.io.File
import java.lang.reflect.Constructor
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Defines the engine's generated classes on Android: each JVM class file is translated to dex by [converter] (dx
 * unless `neonjs-android-d8` or `-Dneonjs.dexConverter` selects another, see [DexConverters.default]) and loaded by a
 * class loader of its own.
 *
 *  - `dalvik.system.InMemoryDexClassLoader` (API 26, the engine's minimum): nothing touches the disk. This is what the
 *    [java.util.ServiceLoader] registration of this module uses, so adding the module to an app is enough.
 *  - With a [cacheDir] (pass `context.codeCacheDir`), converted dex files are kept and reused across launches
 *    (generated class files are deterministic, the cache key is their SHA-256), which saves the dx step for code
 *    compiled before. Files are made read-only, as Android 14+ requires for dynamically loaded code. Where in-memory
 *    loading is unavailable, `dalvik.system.DexClassLoader` loads them from there.
 */
class DexCodeDefiner @JvmOverloads constructor(
    private val cacheDir: File? = null,
    private val minSdk: Int = 26,
    val converter: DexConverter = DexConverters.default,
) : CodeDefiner {

    override val classFileVersion: Int get() = converter.classFileVersion

    override fun isSupported(): Boolean = IN_MEMORY != null || (DEX_FILE_LOADER != null && cacheDir != null)

    override fun define(name: String, bytes: ByteArray, parent: ClassLoader): Class<*> {
        val key = if (cacheDir != null) cacheKey(bytes) else null
        val cached = key?.let { File(cacheDir, "$it.dex") }?.takeIf { it.isFile }
        val inMemory = IN_MEMORY
        if (inMemory != null) {
            val dex = cached?.readBytes() ?: converter.toDex(name, bytes, minSdk).also { d -> if (key != null) store(key, d) }
            val loader = inMemory.newInstance(ByteBuffer.wrap(dex), parent) as ClassLoader
            return loader.loadClass(name)
        }
        val fileLoader = DEX_FILE_LOADER ?: throw UnsupportedOperationException("no dex class loader on this platform")
        val dir = cacheDir ?: throw UnsupportedOperationException("DexCodeDefiner needs a cacheDir below API 26")
        val file = cached ?: store(key!!, converter.toDex(name, bytes, minSdk))
        val loader = fileLoader.newInstance(file.path, dir.path, null, parent) as ClassLoader
        return loader.loadClass(name)
    }

    private fun store(key: String, dex: ByteArray): File {
        val dir = cacheDir!!
        dir.mkdirs()
        val target = File(dir, "$key.dex")
        if (target.isFile) return target
        val tmp = File.createTempFile("neonjs", ".tmp", dir)
        tmp.writeBytes(dex)
        // Android 14+ refuses to load dynamically loaded code from writable files
        tmp.setReadOnly()
        if (!tmp.renameTo(target)) tmp.delete()
        return target
    }

    /** SHA-256 of the class file, the converter and the API level: each of them changes the dex. */
    private fun cacheKey(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update("${converter.id}:$minSdk:".toByteArray())
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private val IN_MEMORY: Constructor<*>? = try {
            Class.forName("dalvik.system.InMemoryDexClassLoader").getConstructor(ByteBuffer::class.java, ClassLoader::class.java)
        } catch (e: Throwable) {
            null
        }
        private val DEX_FILE_LOADER: Constructor<*>? = try {
            Class.forName("dalvik.system.DexClassLoader")
                .getConstructor(String::class.java, String::class.java, String::class.java, ClassLoader::class.java)
        } catch (e: Throwable) {
            null
        }
    }
}
