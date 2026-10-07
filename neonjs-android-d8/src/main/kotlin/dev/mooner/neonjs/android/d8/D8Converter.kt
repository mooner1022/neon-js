package dev.mooner.neonjs.android.d8

import com.android.tools.r8.ByteDataView
import com.android.tools.r8.CompilationMode
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.DexIndexedConsumer
import com.android.tools.r8.Diagnostic
import com.android.tools.r8.DiagnosticsHandler
import com.android.tools.r8.origin.Origin
import dev.mooner.neonjs.android.DexConverter
import dev.mooner.neonjs.jit.GeneratedClass
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Translates generated classes with D8, the dexer that replaced dx in the Android SDK (maintained, no Java 8 class
 * file ceiling). D8 is built for whole-program builds: a run has a fixed cost of several milliseconds on a phone, many
 * times dx's, so it suits batches of classes (background compilation) far better than one class at a time; the r8
 * library adds several megabytes of dex.
 *
 * Desugaring is off: generated code uses neither lambdas nor Java library APIs that would need it.
 */
class D8Converter : DexConverter {
    override val id: String get() = "d8"

    override val classFileVersion: Int get() = 61

    override fun toDex(classes: List<GeneratedClass>, minSdk: Int): ByteArray {
        val files = ArrayList<ByteArray>(1)
        val errors = Errors()
        val builder = D8Command.builder(errors)
        for (c in classes) builder.addClassProgramData(c.bytes, Origin.unknown())
        val command = builder
            .setMinApiLevel(minSdk)
            .setMode(CompilationMode.RELEASE)
            .setDisableDesugaring(true)
            .setProgramConsumer(object : DexIndexedConsumer {
                override fun accept(fileIndex: Int, data: ByteDataView, descriptors: Set<String>, handler: DiagnosticsHandler) {
                    synchronized(files) { files.add(data.copyByteData()) }
                }

                override fun finished(handler: DiagnosticsHandler) {}
            })
            .build()
        val what = if (classes.size == 1) classes[0].name else "${classes.size} classes"
        try {
            D8.run(command, EXECUTOR)
        } catch (e: Exception) {
            throw IllegalStateException("D8 cannot translate $what: ${errors.text.ifEmpty { e.toString() }}", e)
        }
        return when (files.size) {
            1 -> files[0]
            0 -> throw IllegalStateException("D8 produced no dex for $what: ${errors.text}")
            else -> throw IllegalStateException("$what do not fit one dex file")
        }
    }

    /** Keeps errors for the exception message; warnings and infos are not useful for one generated class. */
    private class Errors : DiagnosticsHandler {
        private val sb = StringBuilder()
        val text: String get() = synchronized(sb) { sb.toString().trim() }

        override fun error(error: Diagnostic) {
            synchronized(sb) { sb.append(error.diagnosticMessage).append('\n') }
        }

        override fun warning(warning: Diagnostic) {}

        override fun info(info: Diagnostic) {}
    }

    companion object {
        /** D8 runs its work on an executor; one shared pool saves creating threads for every conversion. */
        private val EXECUTOR: ExecutorService by lazy {
            Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(1, 4)) { r ->
                Thread(r, "neonjs-d8").apply { isDaemon = true }
            }
        }
    }
}
