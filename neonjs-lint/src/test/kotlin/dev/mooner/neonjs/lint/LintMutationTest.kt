@file:OptIn(LintTreeApi::class, UnstableParserApi::class)

package dev.mooner.neonjs.lint

import dev.mooner.neonjs.parser.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.IdentityHashMap
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

/**
 * The rules on programs with a syntax error: the mutants neonjs-core's MutationTest makes (one token of a program
 * deleted, replaced or one inserted; Test262's 1 in 20 of the files, Node.js's library), each one the engine rejects.
 * Asserted:
 * - no `unused-variable` (the use of a name may be in what the parser could not read);
 * - `sparse-array`, `precision-loss`, `useless-escape` and `constant-condition` tell nothing of what has an ErrorNode
 *   in it: the array, the literal, the `if`'s condition or the conditional.
 * Listed in build/lint-mutations.txt: how many each of these rules tells in the programs as they are (Node.js's as
 * scripts, as NeonJS's runner reads them, and as modules) and in their mutants.
 */
class LintMutationTest {

    private class Source(val corpus: String, val rel: String, val src: String, val module: Boolean)
    private class Mutant(val source: Source, val what: String, val at: Int, val src: String)
    private class Outcome(val mutant: Mutant, val codes: List<String>, val unused: Int, val violations: List<String>)

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun mutants() {
        val sources = ArrayList<Source>()
        val root = System.getProperty("neonjs.test262")?.let(::File)
        root?.let { File(it, "test") }?.takeIf { it.isDirectory }?.let { dir ->
            dir.walkTopDown().filter { it.isFile && it.name.endsWith(".js") && !it.name.contains("_FIXTURE") }.forEach { f ->
                val rel = f.relativeTo(dir).path.replace('\\', '/')
                if (crc(rel) % 20 != 0L) return@forEach
                val src = f.readText()
                sources.add(Source("test262", rel, src, "module" in test262Flags(src)))
            }
        }
        root?.let { File(it.parentFile, "node/lib") }?.takeIf { it.isDirectory }?.let { dir ->
            dir.walkTopDown().filter { it.isFile && it.name.endsWith(".js") }.forEach { f ->
                sources.add(Source("node", f.relativeTo(dir).path, f.readText(), false))
            }
        }
        assumeTrue(sources.isNotEmpty(), "no Test262 or Node.js checkout")
        val accepted = sources.filter { accepts(it.src, it.module) }
        val mutants = accepted.flatMap { mutantsOf(it) }.filter { !accepts(it.src, it.source.module) }

        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()) { r ->
            Thread(null, r, "lint-mutants", 64L * 1024 * 1024).also { it.isDaemon = true }
        }
        // (the programs as they are: Node.js's also as modules, where a file's top level is no global)
        val asTheyAre = accepted.map { s -> s to s.module } + accepted.filter { it.corpus == "node" }.map { s -> s to true }
        val (originals, outcomes) = try {
            val o = pool.invokeAll(asTheyAre.map { (s, module) -> Callable {
                val r = NeonLint.lint(s.src, LintOptions(if (module) SourceType.MODULE else SourceType.SCRIPT, maxDiagnostics = 10_000))
                Triple(s, module, r.diagnostics.filter { it.code in RULES }.map { it.code })
            } }).map { it.get() }
            o to pool.invokeAll(mutants.map { m -> Callable { probe(m) } }).map { it.get() }
        } finally {
            pool.shutdownNow()
        }

        val withUnused = outcomes.filter { it.unused > 0 }
        val violating = outcomes.filter { it.violations.isNotEmpty() }
        val report = buildString {
            appendLine("sources ${accepted.size} (${accepted.groupingBy { it.corpus }.eachCount()}), mutations the engine rejects ${outcomes.size}")
            for ((label, os) in listOf(
                "test262 as they are" to originals.filter { it.first.corpus == "test262" },
                "node as scripts" to originals.filter { it.first.corpus == "node" && !it.second },
                "node as modules" to originals.filter { it.first.corpus == "node" && it.second },
            )) appendLine("$label: ${byRule(os.flatMap { it.third })}")
            for (corpus in listOf("test262", "node")) appendLine("$corpus mutants: ${byRule(outcomes.filter { it.mutant.source.corpus == corpus }.flatMap { it.codes })}")
            appendLine("mutants with unused-variable ${withUnused.size}; with a rule telling of what has an ErrorNode in it ${violating.size}")
            for (o in withUnused.take(10)) appendLine("  unused ${o.mutant.source.rel} ${o.mutant.what} at ${o.mutant.at}")
            for (o in violating.take(10)) appendLine("  ${o.mutant.source.rel} ${o.mutant.what} at ${o.mutant.at}: ${o.violations.take(3)}")
        }
        File("build/lint-mutations.txt").writeText(report)
        assertEquals(0, withUnused.size + violating.size, report)
    }

    private fun byRule(codes: List<String>): Map<String, Int> = RULES.associateWith { r -> codes.count { it == r } }

    private fun probe(m: Mutant): Outcome {
        val module = m.source.module
        val r = NeonLint.lint(m.src, LintOptions(if (module) SourceType.MODULE else SourceType.SCRIPT, maxDiagnostics = 10_000))
        val told = r.diagnostics.filter { it.code in RULES }
        val checked = told.filter { it.code != "unused-variable" }
        if (checked.isEmpty()) return Outcome(m, told.map { it.code }, told.size, emptyList())
        // the node each of these rules reads, and whether an ErrorNode is in it
        val tree = Tree(Parser.parseTolerant(m.src, ParseOptions(isModule = module)).program) { false }
        val parent = IdentityHashMap<Node, Node?>()
        for (v in tree.visits) parent.putIfAbsent(v.node, v.parent)
        val errors = tree.visits.filter { it.node is ErrorNode }.map { it.node }
        fun within(n: Node, subject: Node): Boolean {
            var at: Node? = n
            while (at != null) {
                if (at === subject) return true
                at = parent[at]
            }
            return false
        }
        fun covering(d: Diagnostic, pick: (Node) -> Boolean): Node? =
            tree.visits.map { it.node }.filter { pick(it) && it.start <= d.start && d.end <= it.end }.minByOrNull { it.end - it.start }
        val violations = ArrayList<String>()
        for (d in checked) {
            val subject = when (d.code) {
                "sparse-array" -> covering(d) { it is ArrayLiteral }
                "constant-condition" -> tree.visits.map { it.node }.firstNotNullOfOrNull { n ->
                    when {
                        n is IfStatement && n.test.start == d.start && n.test.end == d.end -> n.test
                        n is ConditionalExpression && n.test.start == d.start && n.test.end == d.end -> n
                        else -> null
                    }
                }
                else -> covering(d) { it is NumberLiteral || it is StringLiteral || it is TemplateLiteral }
            }
            if (subject == null) violations.add("${d.code} at ${d.start}: no node it reads")
            else if (errors.any { within(it, subject) }) violations.add("${d.code} at ${d.start}: an ErrorNode in the ${subject.javaClass.simpleName}")
        }
        return Outcome(m, told.map { it.code }, told.count { it.code == "unused-variable" }, violations)
    }

    private fun accepts(src: String, module: Boolean): Boolean = try {
        Parser.parse(src, ParseOptions(isModule = module))
        true
    } catch (e: JSSyntaxError) {
        false
    } catch (e: StackOverflowError) {
        false
    }

    /** neonjs-core's MutationTest's: three tokens of [s] (chosen by its path), each deleted, replaced and with one inserted before it. */
    private fun mutantsOf(s: Source): List<Mutant> {
        val tokens = ArrayList<IntArray>()
        try {
            val lex = Lexer(s.src, null, s.module)
            lex.next()
            while (lex.type != T.EOF) {
                tokens.add(intArrayOf(lex.start, lex.end))
                lex.next()
            }
        } catch (e: JSSyntaxError) {
            return emptyList()
        }
        if (tokens.size < 2) return emptyList()
        val out = ArrayList<Mutant>()
        var seed = crc(s.rel)
        repeat(3) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val t = tokens[((seed ushr 33) % tokens.size).toInt()]
            val pick = PUNCTUATION[((seed ushr 17) % PUNCTUATION.size).toInt()]
            out.add(Mutant(s, "delete", t[0], s.src.substring(0, t[0]) + s.src.substring(t[1])))
            out.add(Mutant(s, "replace '$pick'", t[0], s.src.substring(0, t[0]) + pick + s.src.substring(t[1])))
            out.add(Mutant(s, "insert '$pick'", t[0], s.src.substring(0, t[0]) + pick + " " + s.src.substring(t[0])))
        }
        return out
    }

    private fun crc(s: String): Long = CRC32().also { it.update(s.toByteArray()) }.value

    private companion object {
        val RULES = listOf("sparse-array", "precision-loss", "useless-escape", "unused-variable", "constant-condition")
        val PUNCTUATION = listOf(")", "(", ";", ",", "{", "}", "]", "=", ".", "=>", "let", "if")
    }
}
