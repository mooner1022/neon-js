package dev.mooner.neonjs.android

import dev.mooner.neonjs.jit.CodeDefiner
import dev.mooner.neonjs.jit.GeneratedClass
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Defines the engine's generated classes on Android: JVM class files are translated to dex by [converter] (dx unless
 * `neonjs-android-d8` or `-Dneonjs.dexConverter` selects another, see [DexConverters.default]) and loaded by a class
 * loader for the dex file. A batch of classes ([defineAll], as the background compiler hands them over) shares one dex
 * file and one loader, which spreads the converter's and the runtime's fixed costs over the batch.
 *
 *  - `dalvik.system.InMemoryDexClassLoader` (API 26, the engine's minimum): nothing touches the disk. This is what the
 *    [java.util.ServiceLoader] registration of this module uses, so adding the module to an app is enough.
 *  - With a [cacheDir] (pass `context.codeCacheDir`), converted dex files are kept and reused across launches
 *    (generated class files are deterministic): each class file's hash points to the dex file that holds it, which
 *    saves the conversion for code compiled before. Dex files are made read-only, as Android 14+ requires for
 *    dynamically loaded code. Where in-memory loading is unavailable, `dalvik.system.DexClassLoader` loads them.
 */
class DexCodeDefiner internal constructor(
    private val cacheDir: File?,
    private val minSdk: Int,
    val converter: DexConverter,
    private val loaders: DexLoaders,
) : CodeDefiner {
    @JvmOverloads constructor(cacheDir: File? = null, minSdk: Int = 26, converter: DexConverter = DexConverters.default) :
        this(cacheDir, minSdk, converter, DexLoaders.PLATFORM)

    /** Loaders of cached dex files already open, for the engine's class loader as parent (dex file name -> loader). */
    private val open = ConcurrentHashMap<String, WeakReference<ClassLoader>>()

    override val classFileVersion: Int get() = converter.classFileVersion

    override fun isSupported(): Boolean = loaders.hasInMemory || (loaders.hasFile && cacheDir != null)

    override fun define(name: String, bytes: ByteArray, parent: ClassLoader): Class<*> =
        defineAll(listOf(GeneratedClass(name, bytes)), parent)[0]

    override fun defineAll(classes: List<GeneratedClass>, parent: ClassLoader): List<Class<*>> {
        val dir = cacheDir
        if (dir == null) {
            val dex = converter.toDex(classes, minSdk)
            val loader = loaders.inMemory(dex, parent) ?: throw UnsupportedOperationException("no in-memory dex class loader: DexCodeDefiner needs a cacheDir")
            return classes.map { loader.loadClass(it.name) }
        }
        val keys = classes.map { cacheKey(it.bytes) }
        val result = arrayOfNulls<Class<*>>(classes.size)
        val missing = ArrayList<Int>()
        for (i in classes.indices) {
            val file = cachedDex(dir, keys[i])
            result[i] = if (file == null) null else try {
                loaderFor(file, parent, null).loadClass(classes[i].name)
            } catch (_: ClassNotFoundException) {
                null // stale index entry: convert again
            }
            if (result[i] == null) missing.add(i)
        }
        if (missing.isNotEmpty()) {
            val dex = converter.toDex(missing.map { classes[it] }, minSdk)
            val file = store(dir, dex, missing.map { keys[it] })
            val loader = if (file != null) loaderFor(file, parent, dex)
            else loaders.inMemory(dex, parent) ?: throw java.io.IOException("cannot store dex file in $dir")
            for (i in missing) result[i] = loader.loadClass(classes[i].name)
        }
        return result.map { it!! }
    }

    /** The dex file the index of [dir] names for class file key [key], if it is there. */
    private fun cachedDex(dir: File, key: String): File? {
        val index = File(dir, "$key.idx")
        if (!index.isFile) return null
        val name = try {
            index.readText().trim()
        } catch (_: java.io.IOException) {
            return null
        }
        return File(dir, name).takeIf { name.endsWith(".dex") && it.isFile }
    }

    /** A loader for dex [file] ([bytes] if already in memory), shared while alive when [parent] is the engine's loader. */
    private fun loaderFor(file: File, parent: ClassLoader, bytes: ByteArray?): ClassLoader {
        val shared = parent === ENGINE_LOADER
        if (shared) open[file.name]?.get()?.let { return it }
        val loader = loaders.inMemory(bytes ?: file.readBytes(), parent)
            ?: loaders.fromFile(file, cacheDir!!, parent)
            ?: throw UnsupportedOperationException("no dex class loader on this platform")
        if (shared) open[file.name] = WeakReference(loader)
        return loader
    }

    /** Writes [dex] (read-only) and points [keys] at it; null if the file cannot be written. */
    private fun store(dir: File, dex: ByteArray, keys: List<String>): File? = try {
        dir.mkdirs()
        val target = File(dir, sha256(dex, "") + ".dex")
        if (!target.isFile) {
            val tmp = File.createTempFile("neonjs", ".tmp", dir)
            tmp.writeBytes(dex)
            // Android 14+ refuses to load dynamically loaded code from writable files
            tmp.setReadOnly()
            if (!tmp.renameTo(target)) tmp.delete()
        }
        if (!target.isFile) null else {
            for (k in keys) {
                val tmp = File.createTempFile("neonjs", ".tmp", dir)
                tmp.writeText(target.name)
                val index = File(dir, "$k.idx")
                if (!tmp.renameTo(index)) {
                    index.delete()
                    if (!tmp.renameTo(index)) tmp.delete()
                }
            }
            target
        }
    } catch (_: java.io.IOException) {
        null
    }

    /** SHA-256 of the class file, the converter and the API level: each of them changes the dex. */
    private fun cacheKey(bytes: ByteArray): String = sha256(bytes, "${converter.id}:$minSdk:")

    private fun sha256(bytes: ByteArray, prefix: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(prefix.toByteArray())
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val ENGINE_LOADER: ClassLoader? = CodeDefiner::class.java.classLoader
    }
}

/** How dex files become class loaders; the platform's `dalvik.system` loaders, reached through reflection. */
internal interface DexLoaders {
    val hasInMemory: Boolean
    val hasFile: Boolean

    /** A loader over dex file contents [dex], or null without in-memory loading (below API 26). */
    fun inMemory(dex: ByteArray, parent: ClassLoader): ClassLoader?

    /** A loader over dex [file] (optimized code goes to [dir]), or null without file loading. */
    fun fromFile(file: File, dir: File, parent: ClassLoader): ClassLoader?

    companion object {
        val PLATFORM: DexLoaders = object : DexLoaders {
            private val IN_MEMORY: Constructor<*>? = try {
                Class.forName("dalvik.system.InMemoryDexClassLoader").getConstructor(ByteBuffer::class.java, ClassLoader::class.java)
            } catch (_: Throwable) {
                null
            }
            private val DEX_FILE: Constructor<*>? = try {
                Class.forName("dalvik.system.DexClassLoader")
                    .getConstructor(String::class.java, String::class.java, String::class.java, ClassLoader::class.java)
            } catch (_: Throwable) {
                null
            }

            override val hasInMemory get() = IN_MEMORY != null
            override val hasFile get() = DEX_FILE != null

            override fun inMemory(dex: ByteArray, parent: ClassLoader): ClassLoader? =
                IN_MEMORY?.newInstance(ByteBuffer.wrap(dex), parent) as ClassLoader?

            override fun fromFile(file: File, dir: File, parent: ClassLoader): ClassLoader? =
                DEX_FILE?.newInstance(file.path, dir.path, null, parent) as ClassLoader?
        }
    }
}
