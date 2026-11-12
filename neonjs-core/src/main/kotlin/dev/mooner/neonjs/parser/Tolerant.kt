package dev.mooner.neonjs.parser

/**
 * Marks the parser's interface for NeonJS's own modules (the linter, neonjs-lint): public, but not a stable API. It may
 * change in any release.
 */
@RequiresOptIn(message = "The parser's interface for NeonJS's modules (neonjs-lint): not a stable API.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR)
annotation class UnstableParserApi

/** What a syntax error was, for the recovery mode's record of it. */
@UnstableParserApi
enum class ProblemKind {
    /** An early error: a rule broken where the syntax is intact (the parse went on from it). */
    EARLY,
    /** A token where none of its kind can be. */
    UNEXPECTED,
    /** A token missing (`Expected 'x' but found …`). */
    MISSING,
    /** The lexer's: a token it could not read. */
    LEXICAL,
}

/** Why a tolerant parse stopped before the end of the source. */
@UnstableParserApi
enum class ParseStop { LIMIT, CANCELLED }

/**
 * A syntax error the recovery mode recorded: the engine's message, and where. [pos] is the UTF-16 offset the engine
 * reports; [end] the end of the token there when the error is about the current token, otherwise -1. [related]: for
 * the source ending inside a block, the innermost `{` it leaves open (-1 otherwise).
 */
@UnstableParserApi
class ParseProblem(val pos: Int, val end: Int, val message: String, val kind: ProblemKind, val related: Int = -1) {
    override fun toString() = "$pos: $message"
}

/** Bounds of a tolerant parse: it stops at [maxProblems] errors, and when [isCancelled] (asked at each statement) says so. */
@UnstableParserApi
class ParseLimits(val maxProblems: Int = Int.MAX_VALUE, val isCancelled: (() -> Boolean)? = null)

/**
 * A tolerant parse's result: the tree, with an [ErrorNode] for each statement the parser could not read, and the errors
 * in the order found. The first is the one [Parser.parse] throws. [stopped] says why the parse ended early, if it did
 * (the tree then has an [ErrorNode] for the rest).
 */
@UnstableParserApi
class TolerantParse(val program: Program, val problems: List<ParseProblem>, val stopped: ParseStop?)
