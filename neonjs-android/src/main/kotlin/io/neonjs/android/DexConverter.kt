package io.neonjs.android

import com.android.dx.cf.direct.DirectClassFile
import com.android.dx.cf.direct.StdAttributeFactory
import com.android.dx.command.dexer.DxContext
import com.android.dx.dex.DexOptions
import com.android.dx.dex.cf.CfOptions
import com.android.dx.dex.cf.CfTranslator
import com.android.dx.dex.file.DexFile
import java.util.ServiceLoader

/**
 * Translates one generated JVM class file into a dex file holding just that class, in memory. [DxConverter] (dx) is
 * built in; the `neonjs-android-d8` module adds one based on D8.
 */
interface DexConverter {
    /** Short name of the converter; part of [DexCodeDefiner]'s cache key, since converters emit different dex. */
    val id: String

    /** Highest class file major version the converter reads; the engine generates code for it. */
    val classFileVersion: Int

    /**
     * The dex file holding the class [name] (binary name) of class file [classBytes], for devices of API level
     * [minSdk] and up. Throws when the class cannot be translated.
     */
    fun toDex(name: String, classBytes: ByteArray, minSdk: Int): ByteArray
}

/** dx, the class-file-to-dex translator of the Android SDK before D8, as rhino-android uses it. */
object DxConverter : DexConverter {
    override val id: String get() = "dx"

    /** dx reads class files up to Java 8. */
    override val classFileVersion: Int get() = 52

    /**
     * dx's intern tables are concurrent, but `com.android.dx.ssa.Optimizer` keeps its options in static fields that
     * every translation writes. Conversions happen once per compiled function, so they are serialized.
     */
    private val lock = Any()

    override fun toDex(name: String, classBytes: ByteArray, minSdk: Int): ByteArray = synchronized(lock) {
        val options = DexOptions()
        options.minSdkVersion = minSdk
        val dex = DexFile(options)
        val cf = DirectClassFile(classBytes, name.replace('.', '/') + ".class", true)
        cf.setAttributeFactory(StdAttributeFactory.THE_ONE)
        cf.magic // parses the class file now, so a malformed one fails here
        dex.add(CfTranslator.translate(DxContext(), cf, null, CfOptions(), options, dex))
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
            } catch (e: Throwable) {
                // a broken provider must not prevent code generation (dx is always there)
            }
        }
        DxConverter
    }

    /** Whether [bytes] look like a dex file ("dex\n" magic). */
    @JvmStatic
    fun isDex(bytes: ByteArray): Boolean =
        bytes.size > 8 && bytes[0] == 'd'.code.toByte() && bytes[1] == 'e'.code.toByte() && bytes[2] == 'x'.code.toByte() && bytes[3] == '\n'.code.toByte()
}
