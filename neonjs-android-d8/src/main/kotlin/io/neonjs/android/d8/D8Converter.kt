package io.neonjs.android.d8

import com.android.tools.r8.ByteDataView
import com.android.tools.r8.CompilationMode
import com.android.tools.r8.D8
import com.android.tools.r8.D8Command
import com.android.tools.r8.DexIndexedConsumer
import com.android.tools.r8.Diagnostic
import com.android.tools.r8.DiagnosticsHandler
import com.android.tools.r8.origin.Origin
import io.neonjs.android.DexConverter
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Translates generated classes with D8, the dexer that replaced dx in the Android SDK (maintained, no Java 8 class
 * file ceiling). D8 is built for whole-program builds: a run has a fixed cost of a few milliseconds on a phone, several
 * times dx's for the one small class the engine converts at a time, and the r8 library adds several megabytes of dex.
 *
 * Desugaring is off: generated code uses neither lambdas nor Java library APIs that would need it.
 */
class D8Converter : DexConverter {
    override val id: String get() = "d8"

    override val classFileVersion: Int get() = 61

    override fun toDex(name: String, classBytes: ByteArray, minSdk: Int): ByteArray {
        var dex: ByteArray? = null
        val errors = Errors()
        val command = D8Command.builder(errors)
            .addClassProgramData(classBytes, Origin.unknown())
            .setMinApiLevel(minSdk)
            .setMode(CompilationMode.RELEASE)
            .setDisableDesugaring(true)
            .setProgramConsumer(object : DexIndexedConsumer {
                override fun accept(fileIndex: Int, data: ByteDataView, descriptors: Set<String>, handler: DiagnosticsHandler) {
                    dex = data.copyByteData()
                }

                override fun finished(handler: DiagnosticsHandler) {}
            })
            .build()
        try {
            D8.run(command, EXECUTOR)
        } catch (e: Exception) {
            throw IllegalStateException("D8 cannot translate $name: ${errors.text.ifEmpty { e.toString() }}", e)
        }
        return dex ?: throw IllegalStateException("D8 produced no dex for $name: ${errors.text}")
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
