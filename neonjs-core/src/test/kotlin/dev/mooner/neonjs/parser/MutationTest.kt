package dev.mooner.neonjs.parser

import dev.mooner.neonjs.compiler.CodeMode
import dev.mooner.neonjs.compiler.GlobalRef
import dev.mooner.neonjs.compiler.LocalRef
import dev.mooner.neonjs.compiler.ScopeAnalyzer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

/**
 * The recovery mode on programs with one token deleted, replaced or inserted: Test262's (1 in 20 of the files the
 * engine accepts; skipped without Test262) and Node.js's library (third_party/node/lib, when there), each mutation the
 * engine rejects. It must read each without a crash, within 2 s, recording something; how many errors it records and
 * how often a name the program declares is no longer declared after the mutation are measured and reported
 * (build/recovery-mutations.txt). They may not get worse than [THRESHOLDS] by more than a point (the user's
 * targets, set at their first measurement).
 */
@OptIn(UnstableParserApi::class)
class MutationTest {

    private class Source(val corpus: String, val rel: String, val src: String, val module: Boolean)

    /** [removed]: the name the mutation deleted or replaced, when it was one (its declaration is gone, not lost). */
    private class Mutant(val source: Source, val what: String, val at: Int, val src: String, val removed: String?)

    private class Outcome(val mutant: Mutant, val problems: Int, val crash: String?, val millis: Long, val lostNames: List<String>)

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun mutations() {
        val sources = ArrayList<Source>()
        System.getProperty("neonjs.test262")?.let(::File)?.let { File(it, "test") }?.takeIf { it.isDirectory }?.let { dir ->
            dir.walkTopDown().filter { it.isFile && it.name.endsWith(".js") && !it.name.contains("_FIXTURE") }.forEach { f ->
                val rel = f.relativeTo(dir).path.replace('\\', '/')
                if (crc(rel) % 20 != 0L) return@forEach
                val src = f.readText()
                sources.add(Source("test262", rel, src, "module" in test262Flags(src)))
            }
        }
        System.getProperty("neonjs.test262")?.let { File(File(it).parentFile, "node/lib") }?.takeIf { it.isDirectory }?.let { dir ->
            dir.walkTopDown().filter { it.isFile && it.name.endsWith(".js") }.forEach { f ->
                sources.add(Source("node", f.relativeTo(dir).path, f.readText(), false))
            }
        }
        assumeTrue(sources.isNotEmpty(), "no Test262 or Node.js checkout")
        val accepted = sources.filter { s -> accepts(s.src, s.module) }
        val mutants = accepted.flatMap { mutantsOf(it) }.filter { !accepts(it.src, it.source.module) }

        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()) { r ->
            Thread(null, r, "mutants", 64L * 1024 * 1024).also { it.isDaemon = true }
        }
        val originals = HashMap<Source, Set<String>>()
        for (s in accepted) originals[s] = declaredNames(Parser.parse(s.src, ParseOptions(isModule = s.module)), s.module)
        val outcomes = try {
            pool.invokeAll(mutants.map { m -> Callable { probe(m, originals[m.source]!!) } }).map { it.get() }
        } finally {
            pool.shutdownNow()
        }

        val crashes = outcomes.filter { it.crash != null }
        val slow = outcomes.filter { it.millis > 2000 }
        val silent = outcomes.filter { it.crash == null && it.problems == 0 }
        val report = buildString {
            appendLine("sources ${accepted.size} (${accepted.groupingBy { it.corpus }.eachCount()}), mutations the engine rejects ${outcomes.size}")
            appendLine("crashes ${crashes.size}, slower than 2 s ${slow.size}, nothing recorded ${silent.size}")
            for (corpus in listOf("test262", "node")) for (kind in listOf("delete", "replace", "insert", "all")) {
                val os = outcomes.filter { it.mutant.source.corpus == corpus && (kind == "all" || it.mutant.what.startsWith(kind)) && it.crash == null }
                if (os.isEmpty()) continue
                val counts = os.groupingBy { if (it.problems >= 5) "5+" else "${it.problems}" }.eachCount().toSortedMap()
                val lost = os.count { it.lostNames.isNotEmpty() }
                appendLine("$corpus $kind: ${os.size} mutations; errors recorded ${counts.mapValues { "%.1f%%".format(it.value * 100.0 / os.size) }}; " +
                    "mean %.2f; a declared name undeclared after the mutation in %.1f%%".format(os.sumOf { it.problems }.toDouble() / os.size, lost * 100.0 / os.size))
            }
            fun list(title: String, os: List<Outcome>, what: (Outcome) -> String) {
                if (os.isEmpty()) return
                appendLine(title)
                os.take(15).forEach { appendLine("  ${it.mutant.source.rel} ${it.mutant.what} at ${it.mutant.at}: ${what(it)}") }
            }
            list("crashes", crashes) { it.crash!! }
            list("slow", slow) { "${it.millis} ms" }
            list("nothing recorded", silent) { "" }
            for (corpus in listOf("test262", "node")) {
                list("names lost, $corpus (sample)", outcomes.filter { it.mutant.source.corpus == corpus && it.lostNames.isNotEmpty() }.shuffled(java.util.Random(1)).take(12)) { it.lostNames.take(5).toString() }
            }
        }
        File("build/recovery-mutations.txt").writeText(report)
        // (every mutant with a name lost, to compare two versions of the recovery mode)
        File("build/recovery-mutations-lost.txt").writeText(outcomes.filter { it.lostNames.isNotEmpty() }
            .joinToString("") { "${it.mutant.source.corpus} ${it.mutant.source.rel} ${it.mutant.what} at ${it.mutant.at}: ${it.lostNames.distinct()}\n" })
        assertEquals(0, crashes.size + slow.size + silent.size, report)
        for ((corpus, limits) in THRESHOLDS) {
            val os = outcomes.filter { it.mutant.source.corpus == corpus && it.crash == null }
            if (os.isEmpty()) continue
            val one = os.count { it.problems == 1 } * 100.0 / os.size
            val lost = os.count { it.lostNames.isNotEmpty() } * 100.0 / os.size
            assertTrue(one >= limits.first - 1.0, "%s: one error recorded in %.1f%%, the target %.1f%%".format(corpus, one, limits.first) + "\n" + report)
            assertTrue(lost <= limits.second + 1.0, "%s: a declared name lost in %.1f%%, the target %.1f%%".format(corpus, lost, limits.second) + "\n" + report)
        }
    }

    private fun accepts(src: String, module: Boolean): Boolean = try {
        Parser.parse(src, ParseOptions(isModule = module))
        true
    } catch (e: JSSyntaxError) {
        false
    } catch (e: StackOverflowError) {
        false
    }

    private fun probe(m: Mutant, declared: Set<String>): Outcome {
        val t0 = System.nanoTime()
        val parsed = try {
            Parser.parseTolerant(m.src, ParseOptions(isModule = m.source.module))
        } catch (e: Throwable) {
            return Outcome(m, 0, e.toString(), (System.nanoTime() - t0) / 1_000_000, emptyList())
        }
        val millis = (System.nanoTime() - t0) / 1_000_000
        val lost = try {
            lostNames(parsed.program, m.source.module, declared - setOfNotNull(m.removed), m.at)
        } catch (e: Throwable) {
            return Outcome(m, parsed.problems.size, "scope analysis: $e", millis, emptyList())
        }
        return Outcome(m, parsed.problems.size, null, millis, lost)
    }

    /** Names a program declares: bound locally, or top-level declarations of a script. */
    private fun declaredNames(prog: Program, module: Boolean): Set<String> {
        val an = ScopeAnalyzer(if (module) CodeMode.MODULE else CodeMode.SCRIPT)
        an.analyze(prog)
        val out = HashSet<String>(an.globalVarNames)
        out.addAll(an.globalLexNames.keys)
        identifiers(prog) { id -> if (id.ref is LocalRef) out.add(id.name) }
        return out
    }

    /** References after [at] to a name the original declared, which the mutant's analysis finds undeclared. */
    private fun lostNames(prog: Program, module: Boolean, declared: Set<String>, at: Int): List<String> {
        val an = ScopeAnalyzer(if (module) CodeMode.MODULE else CodeMode.SCRIPT)
        an.analyze(prog)
        val out = ArrayList<String>()
        identifiers(prog) { id ->
            val ref = id.ref
            if (ref is GlobalRef && id.start > at && ref.name in declared && ref.name !in an.globalVarNames && ref.name !in an.globalLexNames) out.add(ref.name)
        }
        return out
    }

    private fun identifiers(n: Any?, seen: java.util.IdentityHashMap<Any, Boolean> = java.util.IdentityHashMap(), f: (Identifier) -> Unit) {
        when (n) {
            null -> {}
            is Identifier -> f(n)
            is ErrorNode -> {}
            is Node -> {
                if (seen.put(n, true) != null) return
                var k: Class<*>? = n.javaClass
                while (k != null && k != Node::class.java) {
                    for (fl in k.declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(fl.modifiers) || fl.name == "classNode") continue
                        fl.isAccessible = true
                        identifiers(fl.get(n), seen, f)
                    }
                    k = k.superclass
                }
            }
            is List<*> -> n.forEach { identifiers(it, seen, f) }
        }
    }

    /** Three tokens of [s] (chosen by its path), each deleted, replaced and with one inserted before it. */
    private fun mutantsOf(s: Source): List<Mutant> {
        val tokens = ArrayList<IntArray>()
        val names = ArrayList<String?>()
        try {
            val lex = Lexer(s.src, null, s.module)
            lex.next()
            while (lex.type != T.EOF) {
                tokens.add(intArrayOf(lex.start, lex.end))
                names.add(if (lex.type == T.NAME) lex.value as String else null)
                lex.next()
            }
        } catch (e: JSSyntaxError) {
            // (a regular expression or a template the lexer reads as tokens of its own: the file is left out)
            return emptyList()
        }
        if (tokens.size < 2) return emptyList()
        val out = ArrayList<Mutant>()
        var seed = crc(s.rel)
        repeat(3) { n ->
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val i = ((seed ushr 33) % tokens.size).toInt()
            val t = tokens[i]
            val pick = PUNCTUATION[((seed ushr 17) % PUNCTUATION.size).toInt()]
            out.add(Mutant(s, "delete", t[0], s.src.substring(0, t[0]) + s.src.substring(t[1]), names[i]))
            out.add(Mutant(s, "replace '$pick'", t[0], s.src.substring(0, t[0]) + pick + s.src.substring(t[1]), names[i]))
            out.add(Mutant(s, "insert '$pick'", t[0], s.src.substring(0, t[0]) + pick + " " + s.src.substring(t[0]), null))
            @Suppress("UNUSED_EXPRESSION") n
        }
        return out
    }

    private fun crc(s: String): Long = CRC32().also { it.update(s.toByteArray()) }.value

    private companion object {
        /** Per corpus: the share of mutants with one error recorded, and with a declared name lost (P2's, in %). */
        val THRESHOLDS = mapOf("test262" to (79.3 to 4.3), "node" to (69.1 to 10.2))

        val PUNCTUATION = listOf(")", "(", ";", ",", "{", "}", "]", "=", ".", "=>", "let", "if")
    }
}
