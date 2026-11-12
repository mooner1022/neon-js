package dev.mooner.neonjs.lint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Each syntax error code with code that gives it (and the same mended, which gives none). */
class SyntaxCodesTest {

    private class Example(val code: String, val wrong: String, val right: String, val type: SourceType = SourceType.MODULE)

    private val examples = listOf(
        Example("syntax/unexpected-end", "function f() {", "function f() {}"),
        Example("syntax/unexpected-token", "let a = ;", "let a = 1;"),
        Example("syntax/missing-token", "if (a { }", "if (a) { }"),
        Example("syntax/unterminated-string", "let s = 'abc", "let s = 'abc'"),
        Example("syntax/unterminated-template", "let s = `abc", "let s = `abc`"),
        Example("syntax/unterminated-comment", "let a; /* abc", "let a; /* abc */"),
        Example("syntax/unterminated-regexp", "let r = /abc", "let r = /abc/"),
        Example("syntax/invalid-regexp", "let r = /(/;", "let r = /\\(/;"),
        Example("syntax/invalid-regexp-flags", "let r = /a/g\\u0069;", "let r = /a/gi;"),
        Example("syntax/invalid-character", "let a = 1 € 2;", "let a = 1 + 2;"),
        Example("syntax/invalid-escape", "let s = '\\x4';", "let s = '\\x41';"),
        Example("syntax/invalid-number", "let n = 1__0;", "let n = 1_0;"),
        Example("syntax/strict-octal-escape", "let s = '\\101';", "let s = '\\x41';"),
        Example("syntax/private-name-undeclared", "class A { m() { return this.#x } }", "class A { #x; m() { return this.#x } }"),
        Example("syntax/redeclared", "let a; let a;", "let a; let b;"),
        Example("syntax/duplicate-parameter", "const f = (a, a) => a;", "const f = (a, b) => a;"),
        Example("syntax/duplicate-export", "export const a = 1; export { a };", "export const a = 1;"),
        Example("syntax/undefined-export", "export { a };", "const a = 1; export { a };"),
        Example("syntax/reserved-word", "let package = 1;", "let pkg = 1;"),
        Example("syntax/invalid-assignment-target", "f() = 1;", "a.b = 1;"),
        Example("syntax/missing-initializer", "const a;", "const a = 1;"),
        Example("syntax/illegal-return", "return 1;", "function f() { return 1; }", SourceType.SCRIPT),
        Example("syntax/illegal-break", "break;", "for (;;) break;"),
        Example("syntax/import-export-placement", "function f() { import a from 'a'; }", "import a from 'a';"),
        Example("syntax/await-yield", "function f() { let await = 1; }", "async function f() { await x; }"),
        Example("syntax/super-new-target", "function f() { super.x(); }", "class A extends B { m() { super.x(); } }"),
        Example("syntax/class", "class A { constructor() {} constructor() {} }", "class A { constructor() {} }"),
        Example("syntax/strict-mode", "with (a) {}", "{ a; }"),
        Example("syntax/declaration-placement", "if (a) const b = 1;", "if (a) { const b = 1; }"),
        Example("syntax/statement", "try {}", "try {} finally {}"),
        Example("syntax/module", "export { 'a' };", "const a = 1; export { a as 'a' };"),
    )

    @Test
    fun eachCodeHasAnExample() {
        for (e in examples) {
            val wrong = NeonLint.lint(e.wrong, LintOptions(e.type)).diagnostics.map { it.code }
            assertTrue(e.code in wrong, "${e.code}: ${e.wrong} gives $wrong")
            val right = NeonLint.lint(e.right, LintOptions(e.type)).diagnostics.filter { it.code.startsWith("syntax/") }
            assertEquals(emptyList<Diagnostic>(), right, e.right)
        }
        val listed = SyntaxCodes.entries.map { it.code }.toSet()
        assertEquals(listed, examples.map { it.code }.toSet())
    }
}
