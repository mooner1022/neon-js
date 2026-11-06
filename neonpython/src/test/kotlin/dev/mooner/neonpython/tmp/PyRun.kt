package dev.mooner.neonpython.tmp

import dev.mooner.neonjs.jit.Jit
import dev.mooner.neonjs.runtime.VmConfig
import dev.mooner.neonpython.PyContext
import dev.mooner.neonpython.PyException
import dev.mooner.neonpython.PyModuleLoader
import dev.mooner.neonpython.PyModuleSource
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Scratch (not committed): runs the file -Dneonjs.pyrun names in the four modes, each's output to <file>.<mode>.txt
 * (modules from -Dneonjs.pyrun.path, a directory, if given).
 */
class PyRun {
    @Test
    fun run() {
        val path = System.getProperty("neonjs.pyrun")
        Assumptions.assumeTrue(path != null, "no -Dneonjs.pyrun")
        val src = File(path!!).readText().replace("\r\n", "\n")
        val dir = System.getProperty("neonjs.pyrun.path")
        val loader = PyModuleLoader { name ->
            if (dir == null) return@PyModuleLoader null
            val base = "$dir/" + name.replace('.', '/')
            fun read(p: String) = File(p).takeIf { it.isFile }?.readText()?.replace("\r\n", "\n")
            read("$base/__init__.py")?.let { PyModuleSource(it, "$base/__init__.py", true) } ?: read("$base.py")?.let { PyModuleSource(it, "$base.py", false) }
        }
        for (mode in listOf("interpreted", "compiled", "adaptive", "speculating")) {
            val cfg = VmConfig().apply {
                executionMode = when (mode) {
                    "interpreted" -> Jit.MODE_INTERPRETER
                    "compiled" -> Jit.MODE_COMPILED
                    else -> Jit.MODE_ADAPTIVE
                }
                tier2 = mode == "speculating"
                jitThreshold = 2
                backgroundJit = false
            }
            val out = StringBuilder()
            val err = StringBuilder()
            try {
                // (-Dneonjs.pyrun.fs=memory: a file system in memory, the working directory /tmp in it)
                val fs = if (System.getProperty("neonjs.pyrun.fs") == "memory") dev.mooner.neonpython.PyFileSystem.memory(mapOf("/tmp/.keep" to ByteArray(0)))
                    else dev.mooner.neonpython.PyFileSystem.NONE
                val ctx = PyContext(out, cfg, loader, err, fs)
                if (System.getProperty("neonjs.pyrun.fs") == "memory") ctx.exec("import os\nos.chdir('/tmp')\nos.unlink('.keep')\ndel os\n")
                ctx.exec(src)
            } catch (e: PyException) {
                out.append("!! ${e.type}: ${e.message} (line ${e.line} of ${e.function})\n")
            } catch (t: Throwable) {
                out.append("!! ").append(t).append('\n')
                for (x in t.stackTrace.take(14)) out.append("   at ").append(x).append('\n')
            }
            File("$path.$mode.txt").writeText(out.toString())
            File("$path.$mode.t2.txt").writeText("baseline=" + dev.mooner.neonjs.jit.opt.T2.baselineReasons.keys + "\nfailed=" + dev.mooner.neonjs.jit.opt.T2.failureReasons.keys + "\nosr=" + Jit.osrEntered.get() + "\n")
        }
    }
}
