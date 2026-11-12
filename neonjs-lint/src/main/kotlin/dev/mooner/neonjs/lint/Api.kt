package dev.mooner.neonjs.lint

/** How a source is read: as a module (NeonJS's `evalModule`; strict, with `import`/`export`) or as a script. */
enum class SourceType { MODULE, SCRIPT }

/** A diagnostic's severity. */
enum class Severity { ERROR, WARNING }

/** A rule's setting: off, or the severity its diagnostics have instead of their default. */
enum class RuleSetting { OFF, WARNING, ERROR }

/** What a global name allows: reading only (assigning it is told), or assigning too. */
enum class GlobalKind { READ_ONLY, WRITABLE }

/**
 * The global names a script may use without declaring them: the engine's ([EngineGlobals]) and the host's (an app's
 * API objects). [kind] is null for a name that is not one.
 */
fun interface Globals {
    fun kind(name: String): GlobalKind?

    /** These names, then [other]'s. */
    operator fun plus(other: Globals): Globals = Globals { name -> kind(name) ?: other.kind(name) }

    companion object {
        /** [names], all of [kind]. */
        @JvmStatic
        @JvmOverloads
        fun of(names: Collection<String>, kind: GlobalKind = GlobalKind.READ_ONLY): Globals {
            val set = HashSet(names)
            return Globals { if (it in set) kind else null }
        }

        /** Each name with its kind. */
        @JvmStatic
        fun of(names: Map<String, GlobalKind>): Globals {
            val map = HashMap(names)
            return Globals { map[it] }
        }
    }
}

/**
 * What [NeonLint.lint] checks and how.
 *
 * - [globals]: the names a script may use without declaring them; null turns the `undefined-name` and
 *   `readonly-global-assign` rules off (nothing is known of the host's names).
 * - [rules]: a setting for a rule's code; a rule not named keeps its default. Syntax errors cannot be turned off.
 * - [maxDiagnostics]: at most this many diagnostics (the result says when there were more); syntax errors count first.
 * - [isCancelled]: asked as the check goes on; when it says true, the check ends with a result marked cancelled.
 * - [extraRules]: the host's own rules, run after the built-in ones.
 */
class LintOptions @JvmOverloads constructor(
    val sourceType: SourceType = SourceType.MODULE,
    val globals: Globals? = null,
    val rules: Map<String, RuleSetting> = emptyMap(),
    val maxDiagnostics: Int = 100,
    val isCancelled: () -> Boolean = { false },
    val extraRules: List<LintRule> = emptyList(),
)

/** A place a diagnostic points to besides its own: where something was declared, the `{` left open. */
class Related(val start: Int, val end: Int, val message: String)

/**
 * A finding. [start] and [end] are UTF-16 offsets into the source ([end] exclusive). [code] is stable: a host keys its
 * settings and its translations of [message] (English) on it, filling in [args] in the order [RuleInfo.args] names
 * them.
 */
class Diagnostic(
    val code: String,
    val severity: Severity,
    val start: Int,
    val end: Int,
    val message: String,
    val args: List<String>,
    val related: List<Related> = emptyList(),
) {
    override fun toString() = "$start-$end ${severity.name.lowercase()} $code: $message"
}

/**
 * The diagnostics of a source, ordered by offset: its syntax errors ([syntaxErrorCount] of them, those a diagnostic
 * limit left out included) and its rules' findings. [truncated]: the limit left some out. [cancelled]: the check was
 * cancelled before its end; the diagnostics are then those found before (possibly none).
 */
class LintResult(val diagnostics: List<Diagnostic>, val syntaxErrorCount: Int, val truncated: Boolean, val cancelled: Boolean)

/** A rule's (or a syntax error's) code, default severity, the arguments its message has and what it tells. */
class RuleInfo(val code: String, val defaultSeverity: Severity, val args: List<String>, val description: String)

/**
 * Marks what a host's rules see: the parser's tree and the scope analysis's results, which are not a stable API (they
 * change with the engine). A host rule is built against one version of NeonJS.
 */
@RequiresOptIn(message = "The tree a lint rule reads is NeonJS's parser's: not a stable API.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR)
annotation class LintTreeApi
