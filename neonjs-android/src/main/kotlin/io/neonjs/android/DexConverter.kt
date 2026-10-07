package io.neonjs.android

import com.android.dx.cf.direct.DirectClassFile
import com.android.dx.cf.direct.StdAttributeFactory
import com.android.dx.command.dexer.DxContext
import com.android.dx.dex.DexOptions
import com.android.dx.dex.cf.CfOptions
import com.android.dx.dex.cf.CfTranslator
import com.android.dx.dex.file.DexFile
import io.neonjs.jit.GeneratedClass
import java.util.ServiceLoader

/**
 * Translates generated JVM class files into a dex file, in memory. [DxConverter] (dx) is built in; the
 * `neonjs-android-d8` module adds one based on D8.
 */
interface DexConverter {
    /** Short name of the converter; part of [DexCodeDefiner]'s cache key, since converters emit different dex. */
    val id: String

    /** Highest class file major version the converter reads; the engine generates code for it. */
    val classFileVersion: Int

    /**
     * One dex file holding all of [classes] (distinct names, none referring to another), for devices of API level
     * [minSdk] and up. Throws when a class cannot be translated or the classes do not fit one dex file.
     */
    fun toDex(classes: List<GeneratedClass>, minSdk: Int): ByteArray

    /** The dex file holding just the class [name] (binary name) of class file [classBytes]. */
    fun toDex(name: String, classBytes: ByteArray, minSdk: Int): ByteArray = toDex(listOf(GeneratedClass(name, classBytes)), minSdk)
}

/** dx, the class-file-to-dex translator of the Android SDK before D8, as rhino-android uses it. */
object DxConverter : DexConverter {
    override val id: String get() = "dx"

    /** dx reads class files up to Java 8. */
    override val classFileVersion: Int get() = 52

    /**
     * dx's intern tables are concurrent, but `com.android.dx.ssa.Optimizer` keeps its options in static fields that
     * every translation writes. Conversions happen once per batch of compiled functions, so they are serialized.
     */
    private val lock = Any()

    override fun toDex(classes: List<GeneratedClass>, minSdk: Int): ByteArray = synchronized(lock) {
        val options = DexOptions()
        options.minSdkVersion = minSdk
        val dex = DexFile(options)
        val context = DxContext()
        val cfOptions = CfOptions()
        for (c in classes) {
            val cf = DirectClassFile(c.bytes, c.name.replace('.', '/') + ".class", true)
            cf.setAttributeFactory(StdAttributeFactory.THE_ONE)
            cf.magic // parses the class file now, so a malformed one fails here
            dex.add(CfTranslator.translate(context, cf, null, cfOptions, options, dex))
        }
        dex.toDex(null, false)
    }
}

object DexConverters {
    /**
     * The converter used when a definer is not given one: `-Dneonjs.dexConverter=dx|d8|<class name>`, else the first
     * [ServiceLoader] provider (adding `neonjs-android-d8` selects D8), else [DxConverter].
     */
    @JvmStatic
    val default: DexConverter by lazy {
        when (val p = System.getProperty("neonjs.dexConverter")) {
            null, "" -> {}
            "dx" -> return@lazy DxConverter
            else -> {
                val name = if (p == "d8") "io.neonjs.android.d8.D8Converter" else p
                return@lazy Class.forName(name).getDeclaredConstructor().newInstance() as DexConverter
            }
        }
        val loaders = listOfNotNull(Thread.currentThread().contextClassLoader, DexConverter::class.java.classLoader).distinct()
        for (l in loaders) {
            try {
                for (c in ServiceLoader.load(DexConverter::class.java, l)) return@lazy c
            } catch (_: Throwable) {
                // a broken provider must not prevent code generation (dx is always there)
            }
        }
        DxConverter
    }

    /** Whether [bytes] look like a dex file ("dex\n" magic). */
    @JvmStatic
    fun isDex(bytes: ByteArray): Boolean =
        bytes.size > 8 && bytes[0] == 'd'.code.toByte() && bytes[1] == 'e'.code.toByte() && bytes[2] == 'x'.code.toByte() && bytes[3] == '\n'.code.toByte()

    /** Number of classes defined in dex file [bytes] (`class_defs_size` in the header). */
    @JvmStatic
    fun classCount(bytes: ByteArray): Int =
        (bytes[0x60].toInt() and 0xff) or ((bytes[0x61].toInt() and 0xff) shl 8) or
            ((bytes[0x62].toInt() and 0xff) shl 16) or ((bytes[0x63].toInt() and 0xff) shl 24)
}
