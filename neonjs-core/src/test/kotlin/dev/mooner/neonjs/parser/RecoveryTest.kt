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
        assertEquals(listOf("1 Unexpected token ';'"), errors("let a = (;\nlet a = 1;"))
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
    fun aFailedPatternDeclaresNoNameOfItsDefaultsOrComputedKeys() {
        val src = "const { a = fallback, [key]: v, b = { k: w } } = (;\na; v; b; fallback; key; w;"
        val prog = parse(src, module = true).program
        ScopeAnalyzer(CodeMode.MODULE).analyze(prog)
        for (n in listOf("a", "v", "b")) assertTrue(identifiers(prog, n).all { it.ref is LocalRef }, "$n declared")
        for (n in listOf("fallback", "key", "w")) assertTrue(identifiers(prog, n).all { it.ref is GlobalRef }, "$n not declared")
    }

    @Test
    fun aRepairInANestedStatementHidesNoErrorAfterIt() {
        // the string's repair is read past by `g();`: the `(;` of the statement around both is still told
        assertEquals(
            listOf("2 Unterminated string constant", "4 Unexpected token ';'"),
            errors("(function(){\n  var s = \"abc;\n  g();\n})(;"),
        )
        // the statement the repair is in still takes the errors after it as its echoes
        assertEquals(listOf("1 Unterminated string constant"), errors("f(\"abc, x);\ng();"))
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
    fun aFailedExportStatementTakesBackOnlyItsOwnExports() {
        // its own are undone, so `a` and `b` are exported once; the exports before it stay, so `c` twice is still told
        assertEquals(
            listOf("2 Unexpected token '('", "4 Duplicate export of 'c'"),
            errors("export const c = 1;\nexport { a, b } from (;\nexport const a = 1, b = 2;\nexport { c };", module = true),
        )
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

    // ------------------------------------------------------------ P2: class members, the end, missing tokens, the lexer

    @Test
    fun aClassMemberThatFailsLeavesTheOthers() {
        val src = """
            class A {
              foo( { }
              bar() { let k; let k; }
              #baz() {}
              qux() { return this.#baz }
            }
        """.trimIndent()
        assertEquals(listOf("3 Expected ',' but found 'bar'", "3 Identifier 'k' has already been declared"), errors(src))
        val cls = (parse(src).program.body.single() as ClassDeclaration).cls
        assertEquals(listOf("ErrorNode", "MethodDefinition", "MethodDefinition", "MethodDefinition"), cls.body.map { it.javaClass.simpleName })
    }

    @Test
    fun aFailedMembersPrivateNameIsDeclared() {
        // `#m` fails in its parameters: the member using it is no error, nor is a member declaring it again
        assertEquals(listOf("2 Unexpected token ')'"), errors("class A {\n  #m(a, {) {}\n  n() { return this.#m }\n}"))
        assertEquals(listOf("2 Unexpected token ','"), errors("class A {\n  get #m(, ) {}\n  set #m(v) {}\n}"))
    }

    @Test
    fun theEndKeepsTheBlocksStatementsAndPointsAtTheOpenBrace() {
        val src = "function f() {\n  if (x) {\n    y();\n  let k; let k;\n"
        val parsed = parse(src)
        assertEquals(listOf("4 Identifier 'k' has already been declared", "5 Unexpected end of input"), errors(src))
        // the brace left open is the if's, the innermost
        assertEquals(src.indexOf("{", src.indexOf("if")), parsed.problems.last().related)
        // the function is kept, with its statements
        assertTrue(parsed.program.body.single() is FunctionDeclaration)
        assertEquals(listOf("4 Unexpected end of input"), errors("class C {\n  m() {}\n  n() {\n"))
        assertTrue(parse("class C {\n  m() {}\n  n() {\n").program.body.single() is ClassDeclaration)
        assertEquals(listOf("3 Unexpected end of input"), errors("switch (x) {\n  case 1:\n    f();"))
    }

    @Test
    fun aMissingTokenIsTakenAsThere() {
        // the body after `if (a {` is read: the redeclaration in it is found
        assertEquals(
            listOf("1 Expected ')' but found '{'", "2 Identifier 'k' has already been declared"),
            errors("if (a {\n  let k; let k;\n}"),
        )
        assertEquals(listOf("3 Expected ',' but found 'b'"), errors("const o = {\n  a: 1\n  b: 2\n};\no.b;"))
        assertEquals(listOf("1 Expected ',' but found number"), errors("f(1 2);"))
        assertEquals(listOf("1 Expected ']' but found ';'"), errors("a[i;"))
        assertEquals(listOf("1 Unexpected token 'let'".replace("token 'let'", "identifier 'let'"), "1 Identifier 'b' has already been declared"),
            errors("let a = 1 let b = 2; let b = 3;"))
        // one token missing in a statement: what follows in it is its echo, not another error
        assertEquals(listOf("1 Expected ',' but found 'b'"), errors("let x = (a b c) d;"))
        // a statement in the block read after one is a statement of its own
        assertEquals(listOf("1 Expected ')' but found '{'", "2 Unexpected token ';'"), errors("while (a {\n  f(;\n}"))
    }

    @Test
    fun aListsEndMissingBeforeAStatementIsNotACommaMissing() {
        assertEquals(
            listOf("2 Expected ',' but found 'let'", "2 Identifier 'c' has already been declared"),
            errors("foo(a, b\nlet c = 1; let c = 2;").take(2),
        )
    }

    @Test
    fun theLexersErrorsAreRepairedAndTheStatementsAroundRead() {
        // the statement before the string left open is kept: its last token was read as the lexer failed
        assertEquals(
            listOf("1 Identifier 'a' has already been declared", "2 Unterminated string constant", "3 Identifier 'c' has already been declared"),
            errors("let a; let a;\nconst s = 'abc\nlet c; let c;"),
        )
        // the rest of the statement with the string is read too
        val parsed = parse("function f() {}\n'abc\nf();", module = true)
        assertTrue(parsed.program.body[0] is FunctionDeclaration)
        assertEquals(listOf("2 Unterminated string constant"), parsed.problems.map { "${"function f() {}\n'abc\nf();".substring(0, it.pos).count { c -> c == '\n' } + 1} ${it.message}" })
        assertEquals(listOf("1 Invalid hexadecimal escape sequence", "2 Identifier 'e' has already been declared"), errors("x = '\\xZZ';\nlet e; let e;"))
        // (the engine's position: the `}` the template goes on from)
        assertEquals(listOf("2 Unterminated template literal"), errors("x = `abc\n\${y}\ndef"))
        assertEquals(listOf("1 Unterminated comment"), errors("x = 1; /* abc\nlet e; let e;"))
        assertEquals(listOf("1 Invalid or unexpected token", "2 Identifier 'e' has already been declared"), errors("x = 3in;\nlet e; let e;"))
        assertEquals(listOf("1 Invalid regular expression: missing /", "2 Identifier 'e' has already been declared"), errors("x = /abc\nlet e; let e;"))
        // offsets are the source's
        val src = "let s = 'abc\nlet q = s;"
        val p = parse(src)
        assertEquals(src, p.program.source)
        assertEquals(src.indexOf("let q"), p.program.body[1].start)
    }

    @Test
    fun garbageIsReadQuickly() {
        val rnd = java.util.Random(7)
        val chars = "abc{}()[];,.=+-*/\\'\"`#@€ \n\t0123456789<>!?:&|^~%"
        val src = String(CharArray(200_000) { chars[rnd.nextInt(chars.length)] })
        val t0 = System.nanoTime()
        val parsed = parse(src, limits = ParseLimits(maxProblems = 100))
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(parsed.problems.isNotEmpty())
        assertTrue(ms < 3000, "$ms ms")
        val all = parse(src)
        assertTrue(all.problems.isNotEmpty())
    }

    // ------------------------------------------------------------ P4: the recovery mode's own diagnoses

    /** The errors, as "line code" (the diagnosis's code and line when there is one, else the engine's message). */
    private fun diagnoses(src: String, module: Boolean = false): List<String> = parse(src, module).problems.map {
        val at = it.diagnosis?.start ?: it.pos
        "${src.substring(0, at).count { c -> c == '\n' } + 1} ${it.diagnosis?.code ?: it.message}"
    }

    @Test
    fun anExpressionMissingIsStoodInAndTheStatementRead() {
        assertEquals(listOf("1 syntax/missing-expression", "2 Identifier 'k' has already been declared"), diagnoses("let x = ;\nlet k; let k;"))
        assertEquals(listOf("1 syntax/missing-expression"), diagnoses("f(1, , 2);"))
        assertEquals(listOf("1 syntax/missing-expression"), diagnoses("function g(a) { return a + ; }"))
        assertEquals(listOf("1 syntax/missing-expression", "2 Identifier 'k' has already been declared"), diagnoses("if () {\n  let k; let k;\n}"))
        assertEquals(listOf("1 syntax/missing-expression"), diagnoses("let o = { a: , b: 1 };"))
        assertEquals(listOf("1 syntax/missing-expression"), diagnoses("x = `a \${} b`;"))
        // the engine's message and position stay the error's
        val p = parse("let x = ;").problems.single()
        assertEquals("Unexpected token ';'", p.message)
        assertEquals(8, p.pos)
        // not at a statement's first token, nor where no operand is due (`f(;` misses its `)`: the engine's error)
        assertEquals(listOf("1 Unexpected token ')'"), diagnoses(")"))
        assertEquals(listOf("1 Unexpected token ';'"), diagnoses("f(;"))
        // a declaration whose value is missing declares its name
        assertEquals(listOf("1 syntax/missing-expression", "2 Identifier 'a' has already been declared"), diagnoses("let a = ;\nlet a = 1;"))
        // an early error about what stands in is its echo
        assertEquals(listOf("1 syntax/missing-expression"), diagnoses("const f = (a, , b) => a;"))
    }

    @Test
    fun aMissingTokenIsNamedWithTheBracketItBelongsTo() {
        val src = "if (a {\n  f();\n}"
        val d = parse(src).problems.single().diagnosis!!
        assertEquals("syntax/missing-paren", d.code)
        assertEquals(src.indexOf('('), d.related)
        assertEquals(listOf("3 syntax/missing-comma"), diagnoses("const o = {\n  a: 1\n  b: 2\n};"))
        assertEquals("properties", parse("const o = {a: 1 b: 2};").problems.single().diagnosis!!.args.single())
        assertEquals("elements", parse("const a = [1 2];").problems.single().diagnosis!!.args.single())
        assertEquals(listOf("1 syntax/missing-paren"), diagnoses("foo(a, b;\nlet c;"))
        assertEquals(listOf("1 syntax/missing-bracket"), diagnoses("a[i;"))
        assertEquals(listOf("1 syntax/missing-colon"), diagnoses("x = a ? b c;"))
    }

    @Test
    fun anElseWithoutIfAndElseifAreTold() {
        val src = "if (a)\n  f();\n  g();\nelse {\n  let k; let k;\n}"
        assertEquals(listOf("4 syntax/else-without-if", "5 Identifier 'k' has already been declared"), diagnoses(src))
        // `elseif (b) {`: told once, for the `else` after it too
        assertEquals(listOf("2 syntax/elseif", "3 Identifier 'k' has already been declared"),
            diagnoses("if (a) {\n} elseif (b) {\n  let k; let k;\n} else {\n}"))
        // a function named elseif is no mistake
        assertEquals(emptyList<String>(), diagnoses("elseif(b);"))
    }

    @Test
    fun aConditionOrACatchParameterWithoutParenthesesIsMended() {
        assertEquals(listOf("1 syntax/condition-parens", "2 Identifier 'k' has already been declared"), diagnoses("if a == b {\n  let k; let k;\n}"))
        assertEquals(listOf("1 syntax/condition-parens"), diagnoses("while x < 10 { x++; }"))
        assertEquals(listOf("1 syntax/missing-condition", "2 Identifier 'k' has already been declared"), diagnoses("if (x) {} else if {\n  let k; let k;\n}"))
        assertEquals(listOf("1 syntax/catch-parens", "1 Identifier 'k' has already been declared"), diagnoses("try { f(); } catch e { let k; let k; }"))
        // the source as given, its offsets the source's
        val src = "try { f(); } catch e { g(); }"
        assertEquals(src, parse(src).program.source)
    }

    @Test
    fun aParameterListLeftOpenEndsBeforeTheBody() {
        assertEquals(listOf("1 syntax/unclosed-parameters", "2 Identifier 'k' has already been declared"), diagnoses("function f(a, {\n  let k; let k;\n}\nf();"))
        assertEquals(listOf("1 syntax/unclosed-parameters", "2 Identifier 'k' has already been declared"), diagnoses("function f(a {\n  let k; let k;\n}"))
        val d = parse("function f(a, {\n  g();\n}").problems.single().diagnosis!!
        assertEquals(10, d.related)
        // a destructuring parameter is no body
        assertEquals(emptyList<String>(), diagnoses("function f(a, { b }) {\n  g();\n}"))
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
