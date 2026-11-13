package dev.mooner.neonjs.lint

/**
 * The syntax errors' codes: each of the engine parser's and lexer's messages under a stable code, with what in it a
 * translation fills in. The first entry whose pattern matches the whole message gives the code; a message none
 * matches (one the engine adds later) is `syntax/error`, its argument the message.
 */
internal object SyntaxCodes {

    class Entry(val code: String, val pattern: Regex, val args: List<String>, val description: String)

    private fun e(code: String, pattern: String, args: List<String>, description: String) =
        Entry(code, Regex(pattern), args, description)

    val entries: List<Entry> = listOf(
        e("syntax/unexpected-end", "Unexpected end of input", emptyList(), "The source ends where more is needed (a `}` or `)` left open)."),
        e("syntax/unexpected-token", "Unexpected (identifier '.*'|string|number|end of input|token '(?!export'$).*')", listOf("token"), "A token where none of its kind can be."),
        e("syntax/missing-token", "Expected '(.+?)' but found (.+)", listOf("expected", "found"), "A token is missing before another."),
        e("syntax/unterminated-string", "Unterminated string constant", emptyList(), "A string has no closing quote on its line."),
        e("syntax/unterminated-template", "Unterminated template literal", emptyList(), "A template has no closing backtick."),
        e("syntax/unterminated-comment", "Unterminated comment", emptyList(), "A `/*` comment has no `*/`."),
        e("syntax/unterminated-regexp", "Invalid regular expression: missing /", emptyList(), "A regular expression has no closing `/` on its line."),
        e("syntax/invalid-regexp", "Invalid regular expression: /(.*)/([^:]*): (.*)", listOf("pattern", "flags", "detail"), "A regular expression's pattern or flags are invalid."),
        e("syntax/invalid-regexp-flags", "Invalid regular expression flags", emptyList(), "A regular expression's flags are invalid."),
        e("syntax/invalid-character", "Invalid or unexpected token( '.*')?", emptyList(), "A character no token begins with, or that cannot follow a number."),
        e("syntax/invalid-escape", "Invalid (hexadecimal|Unicode) escape sequence|Undefined Unicode code-point|Invalid escape sequence in template|Keyword must not contain escaped characters|'(new\\.target|import\\.meta)' must not contain escaped characters", emptyList(), "An escape sequence is invalid where it is."),
        e("syntax/invalid-number", "Numeric separators? .*|Invalid BigInt literal|Octal literals are not allowed in strict mode", emptyList(), "A number literal is malformed, or not allowed in strict mode."),
        e("syntax/strict-octal-escape", "Octal escape sequences are not allowed in strict mode", emptyList(), "A legacy octal escape in strict code."),
        e("syntax/private-name-undeclared", "Private field '#(.+)' must be declared in an enclosing class", listOf("name"), "A private name its class does not declare."),
        e("syntax/redeclared", "Identifier '([^#].*)' has already been declared", listOf("name"), "A name declared twice in one scope."),
        e("syntax/duplicate-parameter", "Duplicate parameter name not allowed in this context", emptyList(), "A parameter name twice where that is not allowed."),
        e("syntax/duplicate-export", "Duplicate export of '(.+)'", listOf("name"), "A name exported twice."),
        e("syntax/undefined-export", "Export '(.+)' is not defined", listOf("name"), "An exported name the module does not declare."),
        e("syntax/reserved-word", "Unexpected (strict mode )?reserved word '(.+)'|Unexpected eval or arguments in strict mode|let is disallowed as a lexically bound name|Binding (eval|arguments) in strict mode|Assigning to (eval|arguments) in strict mode", emptyList(), "A reserved word used as a name."),
        e("syntax/invalid-assignment-target", "Invalid left-hand side.*|Invalid destructuring assignment target|Optional chaining cannot appear in left-hand side|Binding .*|Invalid rest element|Rest element must be last element|Rest elements? (cannot|may not) have a default (value|initializer)|Rest parameter may not have a default initializer|Comma is not permitted after the rest element|Object pattern can't contain getter or setter|Only '=' operator can be used for specifying default value\\.|Invalid shorthand property initializer|Shorthand property assignments are valid only in destructuring patterns|Redefinition of __proto__ property", emptyList(), "What is assigned or declared cannot be."),
        e("syntax/missing-initializer", "Missing initializer in .*", emptyList(), "A declaration needs an initializer."),
        e("syntax/illegal-return", "Illegal return statement", emptyList(), "`return` outside a function."),
        e("syntax/illegal-break", "Illegal break statement|Illegal continue statement.*|Undefined label '(.+)'|Label '(.+)' has already been declared", emptyList(), "`break` or `continue` with nothing (no such label) to go to."),
        e("syntax/import-export-placement", "Cannot use import statement outside a module|Unexpected token 'export'|'import' and 'export' may only appear at the top level|Cannot use 'import\\.meta' outside a module", emptyList(), "`import` or `export` where it cannot be."),
        e("syntax/await-yield", ".*'(await|yield)'.*|for await is only valid .*|(Await|Yield) expression cannot be a default value", emptyList(), "`await` or `yield` where it cannot be."),
        e("syntax/super-new-target", "'super' keyword unexpected here|Unexpected private field access on super|'new\\.target' can only be used .*|The only valid meta property for new is 'new\\.target'|Cannot use new with import\\(\\)", emptyList(), "`super` or `new.target` where it cannot be."),
        e("syntax/class", "A class may only have one constructor|Class constructor may not be .*|Classes may not have .*|Getter must not have any formal parameters|Setter must have exactly one formal parameter|Decorators .*|Unexpected arrow function in class heritage|Identifier '#.+' has already been declared|Private fields can not be deleted|Private identifier can only be left side of binary expression|'arguments' is not allowed .*", emptyList(), "A class or its members are malformed."),
        e("syntax/strict-mode", "Strict mode code may not include a with statement|In strict mode code, functions can only be declared .*|Deleting local variable in strict mode|Illegal 'use strict' directive .*", emptyList(), "What strict code does not allow."),
        e("syntax/declaration-placement", "Lexical declaration cannot appear in a single-statement context|Function declarations are not allowed in this context|Async functions can only be declared .*|Using declaration.*|Using declarations cannot be exported|using declarations are not allowed in for-in", emptyList(), "A declaration where only a statement can be."),
        e("syntax/statement", "Missing catch or finally after try|More than one default clause in switch statement|Illegal newline after throw|Malformed arrow function parameter list|Logical expressions and coalesce expressions cannot be mixed\\..*|(for-in|for-of) loop variable declaration may not have an initializer|The left-hand side of a for-of loop .*|Optional chaining cannot appear in the (callee|tag) .*", emptyList(), "A statement or expression is malformed."),
        e("syntax/module", "String import names must be followed by 'as'|A string literal cannot be used as an exported binding without 'from'|An export name cannot include a lone surrogate|Duplicate import attribute '(.+)'", emptyList(), "An import or export is malformed."),
    )

    /** A message no entry matches. */
    const val FALLBACK = "syntax/error"

    /** What the recovery mode tells of some mistakes itself (a Diagnosis), in place of the engine's message. */
    class Known(val code: String, val args: List<String>, val description: String)

    val diagnoses: List<Known> = listOf(
        Known("syntax/missing-expression", emptyList(), "An expression is missing where an operand is due: `let x = ;`, `f(1, , 2)`, `if () {`."),
        Known("syntax/missing-paren", emptyList(), "A `)` is missing (related: the `(` it would close)."),
        Known("syntax/missing-bracket", emptyList(), "A `]` is missing (related: the `[` it would close)."),
        Known("syntax/missing-brace", emptyList(), "A `}` is missing before the statement goes on (related: the `{` it would close)."),
        Known("syntax/missing-comma", listOf("items"), "A `,` is missing between a list's items: properties, elements or other items."),
        Known("syntax/missing-colon", emptyList(), "A conditional expression's `:` is missing."),
        Known("syntax/else-without-if", emptyList(), "An `else` with no `if` before it, often an `if` of several statements without braces."),
        Known("syntax/elseif", emptyList(), "`elseif` written for `else if`."),
        Known("syntax/missing-condition", listOf("keyword"), "`if`, `while`, `for`, `switch` or `with` with no condition before its body."),
        Known("syntax/condition-parens", listOf("keyword"), "A condition without its parentheses: `if a == b {`."),
        Known("syntax/catch-parens", emptyList(), "A `catch` parameter without its parentheses: `catch e {`."),
        Known("syntax/unclosed-parameters", emptyList(), "A parameter list not closed before the function's body (related: its `(`)."),
    )

    /** What a diagnosis's related position is. */
    fun relatedMessage(code: String): String = when (code) {
        "syntax/missing-paren" -> "The '(' it would close."
        "syntax/missing-bracket" -> "The '[' it would close."
        "syntax/missing-brace" -> "The '{' it would close."
        "syntax/missing-comma" -> "Where the list begins."
        "syntax/unclosed-parameters" -> "The parameter list's '('."
        else -> "Here."
    }

    fun codeOf(message: String): Pair<String, List<String>> {
        for (e in entries) {
            val m = e.pattern.matchEntire(message) ?: continue
            return e.code to m.groupValues.drop(1).take(e.args.size)
        }
        return FALLBACK to listOf(message)
    }
}
