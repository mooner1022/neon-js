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

/**
 * The linter over Test262 (third_party/test262; skipped without it), each file in each mode the runner parses it in.
 * Asserted:
 * - no `internal-error`, and in a program the engine accepts, the tree walk reaches every node the tree has;
 * - in one it rejects, the engine's error is a syntax diagnostic at its offset or over it, or one of the diagnoses that
 *   point elsewhere (docs/LINT.md) stands for it;
 * - every code is one NeonLint.rules lists.
 * Listed in build/test262-lint.txt, for a reader to check: the errors in programs the engine accepts (a rule's bug, or
 * a runtime error the program tests), and the syntax errors left to `syntax/error`.
 */
class Test262LintTest {

    private class Case(val rel: String, val mode: String, val src: String, val module: Boolean)

    @Test
    @Timeout(value = 20, unit = TimeUnit.MINUTES)
    fun test262() {
        val root = System.getProperty("neonjs.test262")?.let(::File)
        assumeTrue(root != null && File(root, "test").isDirectory, "no Test262 checkout (tools/check.sh checks it out)")
        val testDir = File(root, "test")
        val cases = ArrayList<Case>()
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
            for (m in modes) cases.add(Case(rel, m, if (m == "strict") "\"use strict\";\n$src" else src, m == "module"))
        }
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()) { r ->
            Thread(null, r, "lint", 64L * 1024 * 1024).also { it.isDaemon = true }
        }
        class Out(val case: Case, val accepted: Boolean, val engineError: String?, val diagnostics: List<Diagnostic>, val walkMissed: String?)
        val outs = try {
            pool.invokeAll(cases.map { c -> Callable {
                val options = ParseOptions(isModule = c.module)
                val engineError = try { Parser.parse(c.src, options); null } catch (e: JSSyntaxError) { "${e.pos}: ${e.rawMessage}" } catch (e: StackOverflowError) { "stack overflow" }
                val type = if (c.module) SourceType.MODULE else SourceType.SCRIPT
                val r = NeonLint.lint(c.src, LintOptions(type, maxDiagnostics = 1000))
                Out(c, engineError == null, engineError, r.diagnostics, if (engineError == null) walkMisses(c.src, options) else null)
            } }).map { it.get() }
        } finally {
            pool.shutdownNow()
        }

        val accepted = outs.filter { it.accepted }
        val errorsInAccepted = accepted.filter { o -> o.diagnostics.any { it.severity == Severity.ERROR } }
        val internal = outs.filter { o -> o.diagnostics.any { it.code == NeonLint.INTERNAL_ERROR } }
        val rejected = outs.filter { !it.accepted && it.engineError != "stack overflow" }
        // the engine's error is a syntax diagnostic at its offset or over it; or one of the three diagnoses that point
        // elsewhere (docs/LINT.md: `elseif` rather than the `{` after it, the keyword, the body's `{`) stands for it
        val elsewhere = setOf("syntax/elseif", "syntax/missing-condition", "syntax/unclosed-parameters")
        fun at(o: Out): Boolean {
            val pos = o.engineError!!.substringBefore(':').toInt()
            return o.diagnostics.any { d -> d.code.startsWith("syntax/") && (d.start == pos || (pos >= d.start && pos < d.end)) }
        }
        val firstDiffers = rejected.filter { o -> !at(o) && o.diagnostics.none { it.code in elsewhere } }
        val byDiagnosis = rejected.count { o -> !at(o) && o.diagnostics.any { it.code in elsewhere } }
        val listed = NeonLint.rules.map { it.code }.toSet()
        val unlisted = outs.flatMap { o -> o.diagnostics.map { it.code } }.filter { it !in listed }.toSet()
        val uncoded = rejected.flatMap { o -> o.diagnostics.filter { it.code == "syntax/error" }.map { it.message } }.groupingBy { it }.eachCount()
        val walk = accepted.filter { it.walkMissed != null }
        val byRule = errorsInAccepted.flatMap { o -> o.diagnostics.filter { it.severity == Severity.ERROR }.map { it.code } }.groupingBy { it }.eachCount()
        val warnings = accepted.flatMap { o -> o.diagnostics.map { it.code } }.groupingBy { it }.eachCount().toSortedMap()

        val report = buildString {
            appendLine("cases ${outs.size}: accepted ${accepted.size}, rejected ${rejected.size}")
            appendLine("internal errors ${internal.size}; walks missing a node ${walk.size}")
            appendLine("accepted programs with an error diagnostic ${errorsInAccepted.size}, by rule $byRule")
            appendLine("rejected programs whose engine error is not among the diagnostics ${firstDiffers.size}; told by a diagnosis pointing elsewhere $byDiagnosis")
            appendLine("codes emitted that NeonLint.rules does not list: $unlisted")
            appendLine("syntax errors left without a code: ${uncoded.values.sum()} (${uncoded.size} messages)")
            appendLine("diagnostics in accepted programs, by code: $warnings")
            uncoded.entries.sortedByDescending { it.value }.take(30).forEach { appendLine("  uncoded ${it.value}: ${it.key}") }
            for (o in internal.take(10)) appendLine("  internal ${o.case.rel} [${o.case.mode}]: ${o.diagnostics.first { it.code == NeonLint.INTERNAL_ERROR }.message}")
            for (o in walk.take(10)) appendLine("  walk ${o.case.rel} [${o.case.mode}]: ${o.walkMissed}")
            for (o in firstDiffers.take(10)) appendLine("  first ${o.case.rel} [${o.case.mode}]: engine ${o.engineError}, lint ${o.diagnostics.take(3)}")
            appendLine("errors in accepted programs (each a rule's bug or a test of a runtime error):")
            for (o in errorsInAccepted) {
                val d = o.diagnostics.first { it.severity == Severity.ERROR }
                appendLine("  ${o.case.rel} [${o.case.mode}] ${d.code} at ${d.start}: ${d.message}")
            }
        }
        File("build/test262-lint.txt").writeText(report)
        assertEquals(0, internal.size + walk.size + firstDiffers.size + unlisted.size, report)
    }

    /** What a reflective walk of the tree reaches (but the aliases the walk visits once) that Tree does not: null if nothing. */
    private fun walkMisses(src: String, options: ParseOptions): String? {
        val program = Parser.parseTolerant(src, options).program
        val visited = IdentityHashMap<Node, Boolean>()
        Tree(program) { false }.visits.forEach { visited[it.node] = true }
        val seen = IdentityHashMap<Any, Boolean>()
        var missed: String? = null
        fun walk(v: Any?) {
            if (missed != null || v == null || seen.put(v, true) != null) return
            when (v) {
                is Node -> {
                    if (v.javaClass.simpleName != "EmptyHole" && v !is TemplateElement && v !is ImportAttribute && v !is ExportSpecifier && visited[v] == null) {
                        missed = v.javaClass.simpleName + " at " + v.start
                        return
                    }
                    var k: Class<*>? = v.javaClass
                    while (k != null && k != Node::class.java) {
                        for (f in k.declaredFields) {
                            if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                            // (aliases: a field's value is its initializer's body, a static block's statements its
                            // function's, a class's constructor one of its members; the class of a method)
                            if (f.name == "classNode" || f.name == "constructor" || (v is PropertyDefinition && f.name == "value") || (v is StaticBlock && f.name == "body")) continue
                            if (v is ErrorNode) continue
                            f.isAccessible = true
                            walk(f.get(v))
                        }
                        k = k.superclass
                    }
                }
                is List<*> -> v.forEach { walk(it) }
            }
        }
        walk(program)
        return missed
    }
}
