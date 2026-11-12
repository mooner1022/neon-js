package dev.mooner.neonjs.parser

import dev.mooner.neonjs.compiler.CodeMode
import dev.mooner.neonjs.compiler.ScopeAnalyzer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The recovery mode over all of Test262 (third_party/test262; skipped without it), each file in each mode the runner
 * parses it in: it reads every file to the end, records nothing in a program the engine accepts, records first the
 * error the engine throws, and leaves trees the scope analysis takes.
 */
@OptIn(UnstableParserApi::class)
class TolerantProbeTest {

    private class Case(val rel: String, val mode: String, val src: String, val module: Boolean)

    private class Outcome(
        val case: Case, val engineError: String?, val problems: List<ParseProblem>?, val crash: String?,
        val analyzerCrash: String?, val millis: Long,
    )

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun test262() {
        val root = System.getProperty("neonjs.test262")?.let(::File)
        assumeTrue(root != null && File(root, "test").isDirectory, "no Test262 checkout (tools/check.sh checks it out)")
        val cases = cases(File(root, "test"))
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()) { r ->
            Thread(null, r, "probe", 64L * 1024 * 1024).also { it.isDaemon = true }
        }
        val outcomes = try {
            pool.invokeAll(cases.map { c -> Callable { probe(c) } }).map { it.get() }
        } finally {
            pool.shutdownNow()
        }

        val rejected = outcomes.filter { it.engineError != null }
        val accepted = outcomes.filter { it.engineError == null }
        val crashes = outcomes.filter { it.crash != null }
        val analyzerCrashes = outcomes.filter { it.analyzerCrash != null }
        val silent = rejected.filter { it.problems != null && it.problems.isEmpty() }
        val firstDiffers = rejected.filter { o ->
            val p = o.problems?.firstOrNull()
            p != null && "${p.pos}: ${p.message}" != o.engineError
        }
        val falseErrors = accepted.filter { it.problems != null && it.problems.isNotEmpty() }
        val slow = outcomes.filter { it.millis > 2000 }
        val counts = rejected.mapNotNull { it.problems?.size }.groupingBy { if (it >= 5) "5+" else "$it" }.eachCount().toSortedMap()

        val report = buildString {
            appendLine("cases ${outcomes.size}: rejected by the engine ${rejected.size}, accepted ${accepted.size}")
            appendLine("read to the end ${rejected.size - rejected.count { it.crash != null }} of ${rejected.size}")
            appendLine("crashes ${crashes.size}, scope analysis crashes ${analyzerCrashes.size}, slower than 2 s ${slow.size}")
            appendLine("rejected with nothing recorded ${silent.size}; first error not the engine's ${firstDiffers.size}")
            appendLine("errors recorded in accepted programs ${falseErrors.size}")
            appendLine("errors recorded per rejected case: $counts")
            fun list(title: String, os: List<Outcome>, what: (Outcome) -> String) {
                if (os.isEmpty()) return
                appendLine(title)
                os.take(15).forEach { appendLine("  ${it.case.rel} [${it.case.mode}]: ${what(it)}") }
            }
            list("crashes", crashes) { it.crash!! }
            list("scope analysis crashes", analyzerCrashes) { it.analyzerCrash!! }
            list("nothing recorded", silent) { it.engineError!! }
            list("first error differs", firstDiffers) { "engine ${it.engineError}, recorded ${it.problems!!.first()}" }
            list("errors in accepted programs", falseErrors) { it.problems!!.joinToString("; ") }
            list("slow", slow) { "${it.millis} ms" }
        }
        File("build/tolerant-probe.txt").writeText(report)
        val failures = crashes.size + analyzerCrashes.size + silent.size + firstDiffers.size + falseErrors.size + slow.size
        assertEquals(0, failures, report)
    }

    private fun probe(c: Case): Outcome {
        val options = ParseOptions(isModule = c.module)
        val engineError = try {
            Parser.parse(c.src, options)
            null
        } catch (e: JSSyntaxError) {
            "${e.pos}: ${e.rawMessage}"
        } catch (e: StackOverflowError) {
            "stack overflow"
        }
        val t0 = System.nanoTime()
        val parsed = try {
            Parser.parseTolerant(c.src, options)
        } catch (e: Throwable) {
            return Outcome(c, engineError, null, e.toString(), null, (System.nanoTime() - t0) / 1_000_000)
        }
        val millis = (System.nanoTime() - t0) / 1_000_000
        val analyzerCrash = try {
            ScopeAnalyzer(if (c.module) CodeMode.MODULE else CodeMode.SCRIPT).analyze(parsed.program)
            null
        } catch (e: Throwable) {
            e.toString()
        }
        return Outcome(c, engineError, parsed.problems, null, analyzerCrash, millis)
    }

    /** Every test (not the fixtures), in the modes `neonjs-test262 --parse-only` reads it in. */
    private fun cases(testDir: File): List<Case> {
        val out = ArrayList<Case>()
        testDir.walkTopDown().filter { it.isFile && it.name.endsWith(".js") && !it.name.contains("_FIXTURE") }.forEach { f ->
            val src = f.readText()
            val flags = test262Flags(src)
            val rel = f.relativeTo(testDir).path.replace('\\', '/')
            val modes = when {
                "module" in flags -> listOf("module")
                "onlyStrict" in flags -> listOf("strict")
                "noStrict" in flags || "raw" in flags -> listOf("sloppy")
                else -> listOf("sloppy", "strict")
            }
            for (m in modes) out.add(Case(rel, m, if (m == "strict") "\"use strict\";\n$src" else src, m == "module"))
        }
        out.sortBy { it.rel }
        return out
    }
}
