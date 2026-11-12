package dev.mooner.neonjs.parser

import dev.mooner.neonjs.compiler.CodeMode
import dev.mooner.neonjs.compiler.GlobalRef
import dev.mooner.neonjs.compiler.LocalRef
import dev.mooner.neonjs.compiler.ScopeAnalyzer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/** The parser's recovery mode (Parser.parseTolerant): what it records, and how it reads on after a syntax error. */
@OptIn(UnstableParserApi::class)
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class RecoveryTest {

    private fun parse(src: String, module: Boolean = false, limits: ParseLimits = ParseLimits()) =
        Parser.parseTolerant(src, ParseOptions(isModule = module), limits)

    /** The errors recorded, as "line message". */
    private fun errors(src: String, module: Boolean = false): List<String> =
        parse(src, module).problems.map { "${src.substring(0, it.pos).count { c -> c == '\n' } + 1} ${it.message}" }

    @Test
    fun aMissingParenIsFoundInEachFunctionAndWhatFollowsIsChecked() {
        val src = """
            function a() {
              if (a {
                x();
              }
            }
            function b() {
              if (b {
                y();
              }
            }
            function c() { let k; let k; }
        """.trimIndent()
        assertEquals(
            listOf("2 Expected ')' but found '{'", "7 Expected ')' but found '{'", "11 Identifier 'k' has already been declared"),
            errors(src),
        )
    }

    @Test
    fun aFailedDeclarationIsNoRedeclaration() {
        assertEquals(listOf("1 Unexpected token ';'"), errors("let a = ;\nlet a = 1;"))
        assertEquals(listOf("1 Unexpected token ';'"), errors("function f() { const b = (; const b = 1; }"))
        // var goes up to the function: both its scopes forget it
        assertEquals(
            listOf("1 Unexpected token ';'", "1 Identifier 'x' has already been declared"),
            errors("function f() { { var x = (; } var x = 1; let x = 2; }"),
        )
    }

    @Test
    fun aFailedDeclarationStillDeclaresItsNames() {
        val src = "const x = foo(;\nx.bar();\nfunction g(a, {) {}\ng();"
        val module = parse(src, module = true).program
        ScopeAnalyzer(CodeMode.MODULE).analyze(module)
        assertTrue(identifiers(module, "x").all { it.ref is LocalRef }, "x declared in the module")
        assertTrue(identifiers(module, "g").all { it.ref is LocalRef }, "g declared in the module")

        val script = parse(src).program
        val an = ScopeAnalyzer(CodeMode.SCRIPT)
        an.analyze(script)
        assertTrue("x" in an.globalLexNames, "x a global lexical name")
        assertTrue("g" in an.globalVarNames, "g a global var")
        assertTrue(identifiers(script, "foo").all { it.ref is GlobalRef })
    }

    @Test
    fun theRealDeclarationWins() {
        // the failed `let` comes first, the const after it: the binding is the const's
        val prog = parse("let a = (;\nconst a = 1;\na;", module = true).program
        ScopeAnalyzer(CodeMode.MODULE).analyze(prog)
        val refs = identifiers(prog, "a").mapNotNull { it.ref as? LocalRef }
        assertTrue(refs.isNotEmpty() && refs.all { it.binding.kind.isConst })
    }

    @Test
    fun anExportOfANameAFailedStatementDeclaredIsNoError() {
        assertEquals(listOf("2 Unexpected token ';'"), errors("export { x };\nconst x = foo(;", module = true))
        assertEquals(listOf("1 Unexpected token ';'"), errors("export default (;\nexport default 1;", module = true))
    }

    @Test
    fun aRegularExpressionIsPassedOverAsOneToken() {
        assertEquals(
            listOf("1 Expected ',' but found 'bar'", "2 Identifier 'c' has already been declared"),
            errors("foo(/`/ bar);\nlet c = 1; let c = 2;"),
        )
        assertEquals(
            listOf("1 Expected ',' but found 'bar'", "2 Identifier 'c' has already been declared"),
            errors("foo(/[/*]/ bar);\nlet c = 1; let c = 2;"),
        )
    }

    @Test
    fun aTemplateIsPassedOverWithItsSubstitutions() {
        assertEquals(
            listOf("1 Expected ',' but found 'bar'", "2 Identifier 'd' has already been declared"),
            errors("foo(`a \${ {k: 1}.k } b` bar);\nlet d = 1; let d = 2;"),
        )
        assertEquals(
            listOf("2 Expected ',' but found 'bar'", "3 Identifier 'd' has already been declared"),
            errors("foo(`a \${ x }\n\${y} }` bar);\nlet d = 1; let d = 2;"),
        )
    }

    @Test
    fun theLexersErrorsEndTheStatementWithLinesCounted() {
        assertEquals(
            listOf("1 Invalid or unexpected token", "2 Identifier 'e' has already been declared"),
            errors("€\nlet e = 1; let e = 2;"),
        )
        assertEquals(
            listOf("1 Unterminated string constant", "2 Identifier 'e' has already been declared"),
            errors("let s = 'abc\nlet e = 1; let e = 2;"),
        )
        assertEquals(
            listOf("1 Unterminated string constant", "3 Identifier 'e' has already been declared"),
            errors("f('abc, 1);\nfoo();\nlet e = 1; let e = 2;"),
        )
    }

    @Test
    fun theEndInsideBlocksIsOneError() {
        assertEquals(listOf("4 Unexpected end of input"), errors("function f() {\n  if (x) {\n    y();\n"))
        assertEquals(listOf("2 Unexpected end of input"), errors("switch (x) {\n  case 1:"))
        assertEquals(listOf("1 Unexpected end of input"), errors("class C { m() {"))
    }

    @Test
    fun aSwitchsNextCaseEndsAFailedStatement() {
        val src = "switch (x) {\n  case 1:\n    foo(\n  case 2:\n    let b = 1; let b = 2;\n}"
        // (`case` read as a name: an early error, the parse going on to the error after it)
        assertEquals(
            listOf("4 Unexpected reserved word 'case'", "4 Expected ',' but found number", "5 Identifier 'b' has already been declared"),
            errors(src),
        )
    }

    @Test
    fun aStrayBraceAtTheTopLevelGoesWithTheStatement() {
        assertEquals(listOf("1 Unexpected token '}'", "2 Identifier 'f' has already been declared"), errors("}\nlet f; let f;"))
    }

    @Test
    fun theFirstErrorIsTheEnginesAndEarlyErrorsAreAllRecorded() {
        assertEquals(
            listOf("1 Identifier 'a' has already been declared", "2 Identifier 'b' has already been declared"),
            errors("let a; let a;\nlet b; let b;"),
        )
    }

    @Test
    fun aLimitOrACancelStopsTheParse() {
        val src = "let a = ;\nlet b = ;\nlet c = ;\nlet d = 1;"
        val limited = parse(src, limits = ParseLimits(maxProblems = 2))
        assertEquals(2, limited.problems.size)
        assertEquals(ParseStop.LIMIT, limited.stopped)
        val cancelled = parse(src, limits = ParseLimits(isCancelled = { true }))
        assertEquals(ParseStop.CANCELLED, cancelled.stopped)
        assertEquals(0, cancelled.problems.size)
        assertTrue(cancelled.program.body.single() is ErrorNode)
    }

    @Test
    fun aProgramWithoutErrorsGivesTheEnginesTree() {
        val src = "'use strict'; class A { #x = 1; m() { return this.#x } }\nfor (const k of [1, 2]) { switch (k) { case 1: break } }"
        val parsed = parse(src)
        assertTrue(parsed.problems.isEmpty())
        assertEquals(Parser.parse(src).body.size, parsed.program.body.size)
        assertTrue(parsed.program.strict)
    }

    private fun identifiers(n: Any?, name: String, out: MutableList<Identifier> = ArrayList()): List<Identifier> {
        when (n) {
            is Identifier -> if (n.name == name) out.add(n)
            // (the names a failed statement declared: no references)
            is ErrorNode -> {}
            is Node -> {
                var k: Class<*>? = n.javaClass
                while (k != null && k != Node::class.java) {
                    for (f in k.declaredFields) {
                        if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                        f.isAccessible = true
                        identifiers(f.get(n), name, out)
                    }
                    k = k.superclass
                }
            }
            is List<*> -> n.forEach { identifiers(it, name, out) }
        }
        return out
    }
}
