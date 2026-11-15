@file:OptIn(LintTreeApi::class, UnstableParserApi::class)

package dev.mooner.neonjs.lint

import dev.mooner.neonjs.compiler.CodeMode
import dev.mooner.neonjs.compiler.ScopeAnalyzer
import dev.mooner.neonjs.parser.ParseLimits
import dev.mooner.neonjs.parser.ParseOptions
import dev.mooner.neonjs.parser.ParseStop
import dev.mooner.neonjs.parser.Parser
import dev.mooner.neonjs.parser.UnstableParserApi

/**
 * The linter for NeonJS's JavaScript: the engine's own parser in its recovery mode (every syntax error, the first the
 * engine's, and the code after them still checked), the compiler's scope analysis, and rules for mistakes that are
 * valid code ([rules]). No style rules.
 *
 * [lint] needs no engine or context, keeps no state between calls and may be called from any thread. It throws
 * nothing: an input nested too deeply to read is one `too-deep` diagnostic, an internal failure one `internal-error`.
 */
object NeonLint {

    /** The diagnostics' codes: the syntax errors' and the rules'. */
    @JvmStatic
    val rules: List<RuleInfo> by lazy {
        SyntaxCodes.entries.map { RuleInfo(it.code, Severity.ERROR, it.args, it.description) } +
            SyntaxCodes.diagnoses.map { RuleInfo(it.code, Severity.ERROR, it.args, it.description) } +
            RuleInfo(SyntaxCodes.FALLBACK, Severity.ERROR, listOf("message"), "A syntax error with no code of its own: the engine's message.") +
            RuleInfo(TOO_DEEP, Severity.ERROR, emptyList(), "The source is nested too deeply to read (the engine could not run it either).") +
            RuleInfo(INTERNAL_ERROR, Severity.WARNING, listOf("detail"), "The linter failed on this source (a bug): nothing more could be checked.") +
            BuiltinRules.all.map { RuleInfo(it.code, it.defaultSeverity, it.args, it.description) }
    }

    @JvmStatic
    @JvmOverloads
    fun lint(source: String, options: LintOptions = LintOptions()): LintResult = try {
        run(source, options)
    } catch (e: Tree.Cancelled) {
        LintResult(emptyList(), 0, truncated = false, cancelled = true)
    } catch (e: StackOverflowError) {
        LintResult(listOf(Diagnostic(TOO_DEEP, Severity.ERROR, 0, 0, "The source is nested too deeply to check.", emptyList())), 0, false, false)
    } catch (e: Exception) {
        LintResult(listOf(internalError(e.toString())), 0, false, false)
    }

    private fun internalError(detail: String) =
        Diagnostic(INTERNAL_ERROR, Severity.WARNING, 0, 0, "The linter failed: $detail", listOf(detail))

    private fun run(source: String, options: LintOptions): LintResult {
        val module = options.sourceType == SourceType.MODULE
        val max = maxOf(options.maxDiagnostics, 0)
        val parsed = Parser.parseTolerant(source, ParseOptions(isModule = module), ParseLimits(maxOf(max, 1), options.isCancelled))
        if (parsed.stopped == ParseStop.CANCELLED || options.isCancelled()) return LintResult(emptyList(), 0, false, true)

        val syntax = parsed.problems.map { p ->
            val d = p.diagnosis
            if (d != null) {
                // the recovery mode's own reading of the error: its code, message and span
                val related = if (d.related >= 0) listOf(Related(d.related, d.related + 1, SyntaxCodes.relatedMessage(d.code))) else emptyList()
                Diagnostic(d.code, Severity.ERROR, d.start, maxOf(d.end, d.start), d.message, d.args, related)
            } else {
                val (code, args) = SyntaxCodes.codeOf(p.message)
                val related = if (p.related >= 0) listOf(Related(p.related, p.related + 1, "The '{' left open.")) else emptyList()
                Diagnostic(code, Severity.ERROR, p.pos, endOf(source, p.pos, p.end), p.message, args, related)
            }
        }

        val findings = ArrayList<Diagnostic>()
        val analyzer = ScopeAnalyzer(if (module) CodeMode.MODULE else CodeMode.SCRIPT)
        val tree: Tree
        val facts: Facts
        try {
            analyzer.analyze(parsed.program)
            tree = Tree(parsed.program, options.isCancelled)
            // (a template left open pairs with the next one's "`": from the first "`" on, code may be read as text and text as code)
            val openTemplate = parsed.problems.any { it.message == "Unterminated template literal" }
            val misreadFrom = if (openTemplate) source.indexOf('`').let { if (it < 0) Int.MAX_VALUE else it } else Int.MAX_VALUE
            facts = Facts(tree, options.sourceType, analyzer, parsed.problems.map { it.pos }.sorted().toIntArray(), misreadFrom)
        } catch (e: Exception) {
            if (e is Tree.Cancelled) throw e
            // (no rule can run, but the syntax errors are still told)
            val kept = (syntax.take(max) + internalError(e.toString())).take(max).sortedWith(compareBy({ it.start }, { it.end }))
            return LintResult(kept, parsed.problems.size, parsed.stopped == ParseStop.LIMIT || syntax.size + 1 > kept.size, false)
        }
        for (rule in BuiltinRules.all + options.extraRules) {
            val setting = options.rules[rule.code]
            if (setting == RuleSetting.OFF) continue
            val severity = when (setting) {
                RuleSetting.ERROR -> Severity.ERROR
                RuleSetting.WARNING -> Severity.WARNING
                else -> rule.defaultSeverity
            }
            if (options.isCancelled()) throw Tree.Cancelled()
            val context = LintContext(source, options.sourceType, tree, analyzer, options.globals, facts) { start, end, message, args, related ->
                findings.add(Diagnostic(rule.code, severity, start, maxOf(end, start), message, args, related))
            }
            try {
                rule.check(context)
            } catch (e: Exception) {
                if (e is Tree.Cancelled) throw e
                // (a rule that fails takes only its own findings with it)
                findings.add(internalError("${rule.code}: $e"))
            }
        }

        // the syntax errors first (as many as fit), then the findings in the source's order
        val kept = ArrayList<Diagnostic>(minOf(syntax.size + findings.size, max))
        syntax.take(max).forEach { kept.add(it) }
        findings.sortBy { it.start }
        for (f in findings) {
            if (kept.size >= max) break
            kept.add(f)
        }
        kept.sortWith(compareBy({ it.start }, { it.end }))
        val truncated = parsed.stopped == ParseStop.LIMIT || syntax.size + findings.size > kept.size
        return LintResult(kept, parsed.problems.size, truncated, false)
    }

    /** A syntax error's end: the token's the parser gave, else the word (or the character) at [pos]. */
    private fun endOf(src: String, pos: Int, end: Int): Int {
        if (end > pos) return end
        if (pos >= src.length) return pos
        var i = pos
        while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_' || src[i] == '$')) i++
        return if (i > pos) i else pos + Character.charCount(src.codePointAt(pos))
    }

    const val TOO_DEEP = "too-deep"
    const val INTERNAL_ERROR = "internal-error"
}
