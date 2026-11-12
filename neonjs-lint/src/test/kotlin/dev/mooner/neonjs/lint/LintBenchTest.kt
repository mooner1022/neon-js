package dev.mooner.neonjs.lint

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * How long a lint takes (written to build/lint-bench.txt; nothing is asserted): Node.js's lib/events.js (1,256 lines,
 * third_party/node; skipped without it) and the same with a syntax error in its middle, the first lint in a new JVM
 * (each of 5 JVMs) and the median of 50 after 50 to warm up.
 */
class LintBenchTest {
    @Test
    fun timing() {
        val node = System.getProperty("neonjs.test262")?.let { File(File(it).parentFile, "node/lib/events.js") }
        assumeTrue(node != null && node.isFile, "no third_party/node")
        val src = node!!.readText()
        val broken = src.substring(0, src.length / 2) + "\nlet broken = (;\n" + src.substring(src.length / 2)
        val out = StringBuilder("${node.name}: ${src.lines().size} lines, ${src.length} chars\n")
        for ((name, text) in listOf("as it is" to src, "with a syntax error" to broken)) {
            val file = File.createTempFile("lint-bench", ".js").also { it.writeText(text); it.deleteOnExit() }
            val cold = (1..5).map { coldRun(file) }.sorted()
            repeat(50) { NeonLint.lint(text, LintOptions(SourceType.SCRIPT, EngineGlobals.of(node = true))) }
            val warm = (1..50).map {
                val t0 = System.nanoTime()
                NeonLint.lint(text, LintOptions(SourceType.SCRIPT, EngineGlobals.of(node = true)))
                (System.nanoTime() - t0) / 1e6
            }.sorted()
            out.append("$name: first lint in a new JVM ${cold.joinToString { "%.1f".format(it) }} ms (median %.1f); warm median %.2f ms (min %.2f)\n"
                .format(cold[cold.size / 2], warm[warm.size / 2], warm.first()))
        }
        File("build/lint-bench.txt").writeText(out.toString())
    }

    private fun coldRun(file: File): Double {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val p = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), LintOnce::class.java.name, file.path).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        return text.lines().last().toDouble()
    }
}

/** One lint of a file in this JVM, its time in ms printed. */
object LintOnce {
    @JvmStatic
    fun main(args: Array<String>) {
        val text = File(args[0]).readText()
        val t0 = System.nanoTime()
        NeonLint.lint(text, LintOptions(SourceType.SCRIPT, EngineGlobals.of(node = true)))
        println((System.nanoTime() - t0) / 1e6)
    }
}
