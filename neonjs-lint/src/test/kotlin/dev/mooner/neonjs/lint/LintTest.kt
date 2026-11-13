package dev.mooner.neonjs.lint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Each rule's diagnostics: for each, code it finds and code it does not (the cases ESLint's and quick-lint-js's docs
 * give, and StarLight's editor's), in a module and in a script where scopes differ.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class LintTest {

    /** The diagnostics, as "L<line> <e|w> <code>". */
    private fun marks(code: String, type: SourceType = SourceType.MODULE, globals: Globals? = null, rules: Map<String, RuleSetting> = emptyMap()): List<String> =
        NeonLint.lint(code, LintOptions(type, globals, rules)).diagnostics.map {
            "L${code.substring(0, it.start).count { c -> c == '\n' } + 1} ${if (it.severity == Severity.ERROR) "e" else "w"} ${it.code}"
        }

    private fun messages(code: String, type: SourceType = SourceType.MODULE): List<String> =
        NeonLint.lint(code, LintOptions(type)).diagnostics.map { it.message }

    private fun flags(code: String, rule: String, type: SourceType = SourceType.MODULE, globals: Globals? = null) =
        assertTrue(marks(code, type, globals).any { it.endsWith(" $rule") }, "$rule not found in: $code -> ${marks(code, type, globals)}")

    private fun clean(code: String, rule: String, type: SourceType = SourceType.MODULE, globals: Globals? = null) =
        assertTrue(marks(code, type, globals).none { it.endsWith(" $rule") }, "$rule found in: $code -> ${marks(code, type, globals)}")

    private fun both(f: (SourceType) -> Unit) { f(SourceType.MODULE); f(SourceType.SCRIPT) }

    // ------------------------------------------------------------ StarLight's editor's cases

    @Test
    fun newerSyntaxIsNoError() {
        assertEquals(emptyList<String>(), marks("""
            class A { #x = 1; static y = 3600_000; static { A.z = 1; } #m() { return this.#x; } has(o) { return #x in o; } }
            let v; v ??= await f(); v ||= 1n;
            { using r = open(); }
            export function onMessage(event) { event.room.send(v?.a ?? ""); }
        """.trimIndent()))
    }

    @Test
    fun aLegacyScriptIsNoError() {
        assertEquals(emptyList<String>(), marks("function response(room, msg) { with (Math) { var package = floor(1.5); } replier.reply(msg); }", SourceType.SCRIPT))
    }

    @Test
    fun mistakesThatAreValidCodeAreTold() {
        // (k, g, o and late are never read: each is also told)
        assertEquals(listOf(
            "L1 w unused-variable", "L1 e const-assign", "L2 w cond-assign", "L3 w unreachable", "L4 w unused-variable",
            "L4 w fallthrough", "L5 w unused-variable", "L5 w duplicate-key", "L6 w nan-comparison", "L7 w invalid-typeof",
            "L8 w debugger", "L9 e use-before-define", "L9 w unused-variable",
        ), marks(listOf(
            "const k = 1; k = 2;",
            "let v; if (v = 1) {}",
            "function f(x) { return 1; f(); }",
            "function g(x) { switch (x) { case 1: f(); case 2: f(); } }",
            "const o = { a: 1, a: 2 };",
            "if (v === NaN) {}",
            "if (typeof v === 'strnig') {}",
            "debugger;",
            "late = 1; let late;",
        ).joinToString("\n")))
    }

    @Test
    fun styleAndUnknownNamesAreNotToldWithoutGlobals() {
        assertEquals(emptyList<String>(), marks("let a = 1\nconst o = {}; o['a'] = new Date;;\nLog.i(fizz, a);\n"))
    }

    @Test
    fun oneSyntaxErrorHidesNothing() {
        assertEquals(listOf("L4 e const-assign", "L5 e syntax/redeclared", "L6 e syntax/private-name-undeclared"), marks(listOf(
            "export function onMessage(event) {",
            "    let a = 0;",
            "    const k = 1;",
            "    k = 2;",
            "    const a = 1;",
            "    new Test().#foo();",
            "}",
            "class Test { #foo() {} }",
        ).joinToString("\n")))
    }

    @Test
    fun eachBrokenStatementIsToldAndTheCodeAfterChecked() {
        val cases = mapOf(
            "function f() {\n  let a = ;\n  let b = 1;\n  let c = ;\n  const k = 1; k = 2;\n}\n" to
                listOf("L2 e syntax/missing-expression", "L4 e syntax/missing-expression", "L5 e const-assign"),
            "function f() {\n  if (a {\n    g();\n  }\n  if (b {\n    g();\n  }\n}\nconst k = 1; k = 2;\n" to
                listOf("L2 e syntax/missing-paren", "L5 e syntax/missing-paren", "L9 e const-assign"),
            "let a = 1;\n) ;\nlet b = 2 +;\nconst k = 1; k = 2;\n" to
                listOf("L2 e syntax/unexpected-token", "L3 e syntax/missing-expression", "L4 e const-assign"),
            "let a = \"abc;\nlet b = 1;\nconst k = 1; k = 2;\n" to
                listOf("L1 e syntax/unterminated-string", "L3 e const-assign"),
            "class A {\n  foo( { }\n  bar() { const k = 1; k = 2; }\n  #x = ;\n}\n" to
                listOf("L3 e syntax/missing-paren", "L3 e const-assign", "L4 e syntax/missing-expression"),
            "function f(a, {\n  g();\n}\nfunction h() { const k = 1; k = 2; }\n" to
                listOf("L1 e syntax/unclosed-parameters", "L4 e const-assign"),
            "const o = {\n  a: 1\n  b: 2,\n};\nconst k = 1; k = 2;\n" to
                listOf("L3 e syntax/missing-comma", "L5 e const-assign"),
            "function f() {\n  if (a) { g(); }\n  else if { h(); }\n  const k = 1; k = 2;\n}\n" to
                listOf("L3 e syntax/missing-condition", "L4 e const-assign"),
            // (both early errors of the second export: the engine throws the first)
            "export function a() {}\nexport function a() {}\nconst k = 1; k = 2;\n" to
                listOf("L2 e syntax/redeclared", "L2 e syntax/duplicate-export", "L3 e const-assign"),
        )
        for ((code, expected) in cases) assertEquals(expected, marks(code), code)
        assertEquals(listOf("L2 e syntax/missing-expression", "L3 e const-assign"),
            marks("function response(room, msg) {\n  with (Math) { var x = ; }\n  const k = 1; k = 2;\n}\n", SourceType.SCRIPT))
    }

    @Test
    fun codeThatIsAllErrorsEndsWithinTheLimit() {
        val r = NeonLint.lint("}}}} ((( ]]] === ;;; +++\n@@@ ### $$$\n")
        assertTrue(r.diagnostics.isNotEmpty())
        val many = NeonLint.lint("let z = ;\n".repeat(30), LintOptions(maxDiagnostics = 10))
        assertEquals(10, many.diagnostics.size)
        assertTrue(many.truncated)
    }

    @Test
    fun theRecoveryModesOwnDiagnoses() {
        assertEquals(listOf("L1 e syntax/missing-expression"), marks("let x = ;"))
        assertEquals(listOf("L1 e syntax/missing-expression"), marks("f(1, , 2);"))
        assertEquals(listOf("L1 e syntax/missing-expression"), marks("function g(a) { return a + ; }"))
        // (one error in the statement; what stands in for the conditions makes no duplicate of them)
        assertEquals(listOf("L1 e syntax/missing-expression"), marks("if () {} else if () {}"))
        assertEquals(listOf("L1 e syntax/missing-expression"), marks("switch (x) { case 1 + : break; case 1 + : break; }"))
        assertEquals(listOf("L1 e syntax/missing-paren"), marks("if (a {\n  f();\n}"))
        assertEquals(listOf("L3 e syntax/missing-comma"), marks("const o = {\n  a: 1\n  b: 2\n};"))
        assertEquals(listOf("L4 e syntax/else-without-if"), marks("if (a)\n  f();\n  g();\nelse {\n}"))
        assertEquals(listOf("L2 e syntax/elseif"), marks("if (a) {\n} elseif (b) {\n} else {\n}"))
        assertEquals(listOf("L1 e syntax/condition-parens", "L2 e const-assign"), marks("if a == b {\n  const k = 1; k = 2;\n}"))
        assertEquals(listOf("L1 e syntax/missing-condition", "L2 e const-assign"), marks("if {\n  const k = 1; k = 2;\n}"))
        // (a loop's body is not read as a block: its `break` would be an error there)
        assertEquals(listOf("L1 e syntax/missing-condition"), marks("while {\n  break;\n}"))
        assertEquals(listOf("L1 e syntax/missing-condition"), marks("for {\n  continue;\n}"))
        assertEquals(listOf("L1 e syntax/catch-parens", "L1 e const-assign"), marks("try { f(); } catch e { const k = 1; k = 2; }"))
        assertEquals(listOf("L1 e syntax/unclosed-parameters", "L2 e const-assign"), marks("function f(a, {\n  const k = 1; k = 2;\n}"))
        val d = NeonLint.lint("if (a {\n  f();\n}").diagnostics.single()
        assertEquals("A ')' is missing to close the '('.", d.message)
        assertEquals(3, d.related.single().start)
    }

    // ------------------------------------------------------------ errors

    @Test
    fun constAssign() {
        both { t ->
            flags("const a = 0; a = 1;", "const-assign", t)
            flags("const a = 0; ++a;", "const-assign", t)
            flags("const a = 0; [a] = [1];", "const-assign", t)
            flags("const a = 0; function f() { a += 1 }", "const-assign", t)
            clean("const a = 0; console.log(a);", "const-assign", t)
            clean("for (const a of [1, 2, 3]) { console.log(a); }", "const-assign", t)
            clean("const a = 0; { let a; a = 1; }", "const-assign", t)
        }
        flags("import mod from 'm'; mod = 1;", "const-assign")
        flags("let A = class A { b() { A = 0; } }", "const-assign")
        clean("let A = class A { }; A = 0;", "const-assign")
        assertEquals(listOf("'a' is constant."), messages("const a = 0; a = 1;", SourceType.SCRIPT))
    }

    @Test
    fun useBeforeDefine() {
        both { t ->
            flags("{ alert(c); let c = 1; }", "use-before-define", t)
            flags("late = 1; let late;", "use-before-define", t)
            flags("let x = x + 1;", "use-before-define", t)
            flags("new A(); class A {}", "use-before-define", t)
            flags("function f() { if (a) { b; } const b = 1; }", "use-before-define", t)
            clean("const f = () => f();", "use-before-define", t)
            clean("function g() { return b; } const b = 1;", "use-before-define", t)
            clean("let a = 1; a;", "use-before-define", t)
            clean("{ const C = class { x = foo; }; const foo = 1; }", "use-before-define", t)
            clean("for (const [zone, id = zone] of list) {}", "use-before-define", t)
            clean("let [a, b = a] = [1];", "use-before-define", t)
            flags("let [a = b, b] = [1];", "use-before-define", t)
            flags("const { x = x } = {};", "use-before-define", t)
        }
        clean("f(); function f() {}", "use-before-define", SourceType.SCRIPT)
    }

    @Test
    fun varBeforeDefine() {
        both { t ->
            flags("alert(a); var a = 10;", "var-before-define", t)
            clean("var a; a = 10; alert(a);", "var-before-define", t)
            clean("function g() { return b; } var b = 1;", "var-before-define", t)
            clean("var c = 1; function h(c) { return c; }", "var-before-define", t)
        }
        assertTrue(marks("alert(a); var a = 10;").all { it.startsWith("L1 w") })
    }

    @Test
    fun thisBeforeSuperAndMissingSuperCall() {
        flags("class A extends B { constructor() { this.a = 0; super(); } }", "this-before-super")
        flags("class A extends B { constructor() { super(this.foo()); } }", "this-before-super")
        flags("class A extends B { constructor() { super.x(); super(); } }", "this-before-super")
        clean("class A extends B { constructor() { super(); this.a = 0; } }", "this-before-super")
        clean("class A { constructor() { this.a = 0; } }", "this-before-super")
        clean("class A extends B { constructor() { var c = () => this.d(); super(); } }", "this-before-super")
        clean("class A extends B { constructor() { if (a) super(); else super(); this.a(); } }", "this-before-super")
        flags("class A extends B { constructor() { } }", "missing-super-call")
        clean("class A extends B { constructor() { super(); } }", "missing-super-call")
        clean("class A extends B { constructor() { return {}; } }", "missing-super-call")
        clean("class A extends B { constructor() { const f = () => super(); f(); } }", "missing-super-call")
        clean("class A extends B { }", "missing-super-call")
        clean("class A extends null { constructor() { } }", "missing-super-call")
        clean("class A extends B { constructor() { throw new Error('no'); } }", "missing-super-call")
        // (a direct eval may call super(): neither rule can tell)
        clean("class A extends B { constructor() { eval('super()'); } }", "missing-super-call")
        clean("class A extends B { constructor() { this.a = 1; eval('super()'); super(); } }", "this-before-super")
    }

    // ------------------------------------------------------------ warnings

    @Test
    fun undefinedNameNeedsGlobals() {
        val g = EngineGlobals.of() + Globals.of(listOf("Chat"))
        both { t ->
            flags("const foo = someFunction();", "undefined-name", t, g)
            flags("x = 1;", "undefined-name", t, g)
            clean("const foo = someFunction();", "undefined-name", t, null)
            clean("if (typeof UndefinedIdentifier === 'undefined') {}", "undefined-name", t, g)
            clean("Chat.send(Promise.resolve(1), JSON.stringify({}))", "undefined-name", t, g)
            clean("var a = 1; function f() { return a + b; } var b;", "undefined-name", t, g)
            clean("class C { #x; m() { return this.#x } }", "undefined-name", t, g)
        }
        // (in a with, any name may be the object's)
        clean("var o = {}; with (o) { anything; }", "undefined-name", SourceType.SCRIPT, g)
        flags("Chat = 1;", "readonly-global-assign", globals = g)
        flags("undefined = 1;", "readonly-global-assign", SourceType.SCRIPT, g)
        clean("Chat = 1;", "readonly-global-assign", globals = Globals.of(mapOf("Chat" to GlobalKind.WRITABLE)) + g)
        // a statement that failed still declares its names
        clean("const x = foo(;\nx.bar();", "undefined-name", globals = EngineGlobals.of() + Globals.of(listOf("foo")))
    }

    @Test
    fun condAssign() {
        flags("if (x = 0) {}", "cond-assign")
        flags("do {} while (n = n.parentNode);", "cond-assign")
        flags("for (; x += 1 ;) {}", "cond-assign")
        flags("var b = (x = 0) ? 1 : 0;", "cond-assign")
        clean("var b = ((x = 0)) ? 1 : 0;", "cond-assign")
        clean("while ((n = n.parentNode)) {}", "cond-assign")
        clean("while ((n = n.parentNode) !== null) {}", "cond-assign")
        clean("if (a || (b = c)) {}", "cond-assign")
        clean("switch (foo) { case a = b: }", "cond-assign")
    }

    @Test
    fun unreachable() {
        flags("function foo() { return true; console.log('done'); }", "unreachable")
        flags("function f() { if (a) { return; } else { throw new Error(); } g(); }", "unreachable")
        flags("for (;;) { break; x(); }", "unreachable")
        clean("function foo() { return bar(); function bar() { return 1; } }", "unreachable")
        clean("function bar() { return x; var x; }", "unreachable")
        clean("for (;;) { if (x) break; } x = 2;", "unreachable")
        clean("A: { break A; } foo()", "unreachable")
        clean("function f() { try { return; } finally { x = 2; } }", "unreachable")
        assertEquals(1, marks("function f() { return; a(); b(); c(); }").count { it.endsWith("unreachable") })
    }

    @Test
    fun fallthrough() {
        flags("switch (foo) { case 1: doSomething(); case 2: doSomething(); }", "fallthrough")
        flags("switch (foo) { case 0: if (a) { break; } default: b() }", "fallthrough")
        clean("switch (foo) { case 1: doSomething(); // falls through\n case 2: doSomething(); }", "fallthrough")
        clean("switch (foo) { case 1:\n case 2: doSomething(); }", "fallthrough")
        clean("switch (foo) { case 0: if (a) { break; } else { throw 0; } default: b() }", "fallthrough")
        clean("function f() { switch (foo) { case 1: return 1; case 2: return 2; } }", "fallthrough")
        clean("switch (foo) { case 1: a(); break; default: b(); }", "fallthrough")
    }

    @Test
    fun duplicates() {
        flags("const o = { bar: 'baz', bar: 'qux' };", "duplicate-key")
        flags("const o = { 0x1: 'baz', 1: 'qux' };", "duplicate-key")
        flags("const o = { a: 1, get a() {} };", "duplicate-key")
        clean("const o = { get a() {}, set a(v) {} };", "duplicate-key")
        clean("const o = { [a]: 1, [a]: 2 };", "duplicate-key")
        clean("const o = { '__proto__': baz, ['__proto__']: qux };", "duplicate-key")
        flags("function foo(a, b, a) {}", "duplicate-param", SourceType.SCRIPT)
        clean("function foo(a, b, c) {}", "duplicate-param", SourceType.SCRIPT)
        flags("class A { bar() { } bar() { } }", "duplicate-class-member")
        flags("class D { bar; bar() { } }", "duplicate-class-member")
        clean("class B { get bar() { } set bar(value) { } }", "duplicate-class-member")
        clean("class E { static bar() { } bar() { } }", "duplicate-class-member")
        flags("switch (a) { case 1: break; case 2: break; case 1: break; }", "duplicate-case")
        flags("switch (a) { case f(a + 1).p1: break; case f(a+1).p1: break; }", "duplicate-case")
        clean("switch (a) { case 1: break; case '1': break; }", "duplicate-case")
        flags("if (isSomething(x)) {} else if (isSomething(x)) {}", "duplicate-else-if")
        flags("if (a || b) {} else if (a) {}", "duplicate-else-if")
        flags("if (a) {} else if (a && b) {}", "duplicate-else-if")
        clean("if (a) { if (a) { foo(); } }", "duplicate-else-if")
        clean("if (a) { foo(); } else { if (a) { bar(); } }", "duplicate-else-if")
        clean("if (a) {} else if (b) {} else if (c) {}", "duplicate-else-if")
    }

    @Test
    fun selfAndFunctionAndClassAssign() {
        flags("let foo; foo = foo;", "self-assign")
        flags("let a, b; [a, b] = [a, b];", "self-assign")
        flags("a.b = a.b", "self-assign")
        flags("let foo; foo ??= foo;", "self-assign")
        clean("let foo; [foo = 1] = [foo];", "self-assign")
        clean("let foo; foo &= foo;", "self-assign")
        clean("obj.a().b = obj.a().b;", "self-assign")
        clean("obj[a + b] = obj[a + b];", "self-assign")
        both { t ->
            flags("function foo() {} foo = bar;", "func-assign", t)
            clean("let foo = function () {}; foo = bar;", "func-assign", t)
            clean("function baz(baz) { baz = bar; }", "func-assign", t)
            flags("class A { } A = 0;", "class-assign", t)
            clean("let A = class A { }; A = 0;", "class-assign", t)
        }
        flags("let a = function hello() { hello = 123; };", "func-assign")
    }

    @Test
    fun nanTypeofNegationDebugger() {
        flags("if (foo == NaN) {}", "nan-comparison")
        flags("if (foo != Number.NaN) {}", "nan-comparison")
        flags("switch (foo) { case NaN: break; }", "nan-comparison")
        clean("if (isNaN(foo)) {}", "nan-comparison")
        clean("function f(NaN) { return x == NaN }", "nan-comparison")
        flags("typeof foo === 'strnig'", "invalid-typeof")
        flags("typeof bar !== 'fucntion'", "invalid-typeof")
        flags("typeof x === undefined", "invalid-typeof")
        clean("typeof bar == 'undefined'", "invalid-typeof")
        clean("typeof foo === baz", "invalid-typeof")
        clean("typeof bar === typeof qux", "invalid-typeof")
        clean("typeof b === 'bigint' || typeof s === 'symbol'", "invalid-typeof")
        flags("if (!key in object) {}", "unsafe-negation")
        flags("if (!obj instanceof Ctor) {}", "unsafe-negation")
        clean("if (!(key in object)) {}", "unsafe-negation")
        clean("if ((!foo) in object) {}", "unsafe-negation")
        flags("function isTruthy(x) { debugger; return Boolean(x); }", "debugger")
        clean("obj.debugger = 1;", "debugger")
    }

    // ------------------------------------------------------------ warnings added later (docs/LINT.md)

    /** The diagnostics of [rule], as "<start>-<end>". */
    private fun spans(code: String, rule: String, type: SourceType = SourceType.MODULE): List<String> =
        NeonLint.lint(code, LintOptions(type)).diagnostics.filter { it.code == rule }.map { "${it.start}-${it.end}" }

    /** The `unused-variable` diagnostics, as their arguments: "<name> <kind>". */
    private fun unused(code: String, type: SourceType = SourceType.MODULE): List<String> =
        NeonLint.lint(code, LintOptions(type)).diagnostics.filter { it.code == "unused-variable" }.map { it.args.joinToString(" ") }

    @Test
    fun sparseArray() {
        both { t ->
            flags("const a = [1, , 2];", "sparse-array", t)
            flags("const a = [, 1];", "sparse-array", t)
            flags("const a = [1, ,];", "sparse-array", t)
            flags("f([ , ]);", "sparse-array", t)
            assertEquals(2, spans("const a = [1,,2,,3];", "sparse-array", t).size)
            clean("const a = [1, 2,];", "sparse-array", t)
            clean("const [, second] = pair;", "sparse-array", t)
            clean("[, b] = arr;", "sparse-array", t)
            clean("function f([, x]) {}", "sparse-array", t)
            clean("for (const [, v] of entries) {}", "sparse-array", t)
        }
        // (the comma that makes the hole: the first when it leads, else the one after the hole)
        assertEquals(listOf("14-15"), spans("const a = [1, , 2];", "sparse-array"))
        assertEquals(listOf("11-12"), spans("const a = [, 1];", "sparse-array"))
        assertEquals(listOf("14-15"), spans("const a = [1, ,];", "sparse-array"))
        assertEquals(listOf("24-25"), spans("const a = [(1) /* , */, , 2];", "sparse-array"))
        // (a default's value is an array literal: its hole is told; a destructuring target in a for-of or an arrow's is not)
        flags("[a = [, 1]] = q;", "sparse-array")
        clean("for ([, a] of x); ({ a: [, b] } = o); const g = ([, x]) => x;", "sparse-array")
        assertEquals(listOf("This array has an empty slot: a ',' too many, or a value left out."), messages("f([1, , 2]);").filter { it.startsWith("This array") })
        // (an array left as an invalid assignment's target is a pattern; a character the lexer cannot read is no space)
        clean("let second;\n[, second] += pair;", "sparse-array", SourceType.SCRIPT)
        clean("let value;\n[, value] ??= defaults;", "sparse-array", SourceType.SCRIPT)
        clean("const a = [1, \u001F, 2];", "sparse-array", SourceType.SCRIPT)
        clean("let a;\n[[, a]] += pairs;\n({ list: [, a] } ??= defaults);\n[...[, a]] ||= fallback;", "sparse-array", SourceType.SCRIPT)
        flags("let a; a = [x, [, 1]];", "sparse-array")
        // (nor an array a syntax error follows or comes from: a parameter list missing its `=>`, a template left open)
        clean("export const pick = ([, second])\n  => second;", "sparse-array")
        clean("export const pick = ([, second]) {\n  return second;\n};", "sparse-array")
        clean("const header = `Report\nconst note = `expected = [1, , 3]`;", "sparse-array", SourceType.SCRIPT)
    }

    @Test
    fun precisionLoss() {
        val lossy = listOf(
            "9007199254740993", "5123000000000000000000000000001", "1230000000000000000000000.0", ".1230000000000000000000000",
            "0x20000000000001", "0b100000000000000000000000000000000000000000000000000001", "2e999", "9_007_199_254_740_993",
            "123456789012345678", "1.0000000000000001", "1e-400",
            // (halfway between two numbers of as many digits: JavaScript prints the even one, not the one written)
            "1700000000000000.3", "1700000000000000.7", "3.0000228881835937",
        )
        val kept = listOf(
            "9007199254740992", "5123000000000000000000000000000", "0.1", "0.3", "1.50", "12345.6789", "0.1000000000000000055511151231257827",
            "0x1FFFFFFFFFFFFF", "0x20000000000000", "1e21", "1.5e300", "123e-5", "1_000_000", "0", "0.0", "9007199254740993n",
            "12300000000000000000000000", "100", "5e-324", "0.000001", "9007199254740991",
        )
        both { t ->
            for (n in lossy) flags("f($n);", "precision-loss", t)
            for (n in kept) clean("f($n);", "precision-loss", t)
        }
        // (a value halfway between two numbers of as many digits as written keeps the even one: JavaScript prints it so)
        for (n in listOf("95.00259399414062", "0.6981887817382812", "1700000000000000.2")) clean("f($n);", "precision-loss")
        // (what JavaScript prints, as many digits as written: by a power of two the nearest such number may read back
        // as another double, 2^-24 is printed 5.960464477539063e-8)
        for (n in listOf("5.960464477539063e-8", "5.684341886080802e-14", "6.189700196426902e+26")) clean("f($n);", "precision-loss")
        // (nor where a template left open may have made code of text)
        clean("const header = `Report\nconst line = `total = 9007199254740993 + tax`;", "precision-loss", SourceType.SCRIPT)
        // (a legacy octal literal, in a script; the minus sign is no part of the literal)
        flags("f(0777777777777777777777);", "precision-loss", SourceType.SCRIPT)
        clean("f(0777);", "precision-loss", SourceType.SCRIPT)
        assertEquals(listOf("3-19"), spans("f(-9007199254740993);", "precision-loss"))
        val m = NeonLint.lint("f(9007199254740993, 2e999, 1e-400);").diagnostics.filter { it.code == "precision-loss" }
        assertEquals(listOf(
            "This number cannot be kept exactly: it becomes 9007199254740992.",
            "This number cannot be kept exactly: it becomes Infinity.",
            "This number cannot be kept exactly: it becomes 0.",
        ), m.map { it.message })
        assertEquals(listOf(listOf("9007199254740992"), listOf("Infinity"), listOf("0")), m.map { it.args })
    }

    @Test
    fun uselessEscape() {
        both { t ->
            flags(""" f("\d+"); """, "useless-escape", t)
            flags(""" f('\s*'); """, "useless-escape", t)
            flags(""" f("a\.b"); """, "useless-escape", t)
            flags(" f(`\\w+`); ", "useless-escape", t)
            flags(""" f("\D"); """, "useless-escape", t)
            assertEquals(2, spans(""" f("\(x\)"); """, "useless-escape", t).size)
            clean(""" f("\\d+", /\d+/); """, "useless-escape", t)
            clean(""" f("\n\t\x41é\u{1F600}"); """, "useless-escape", t)
            clean(""" f('It\'s', "say \"hi\"", "it\'s"); """, "useless-escape", t)
            clean(" f(`\\\${name}`, `a\\`b`); ", "useless-escape", t)
            clean(""" f("http:\/\/x"); """, "useless-escape", t)
            clean(" f(String.raw`\\d+`); ", "useless-escape", t)
            clean(""" f("\\\\d", "one line \""" + "\n" + """and the next"); """, "useless-escape", t)
        }
        // (`\\\d`: the third '\' alone; two units, the '\' and its character)
        val src = """f("\\\d");"""
        assertEquals(listOf("${src.indexOf('d') - 1}-${src.indexOf('d') + 1}"), spans(src, "useless-escape"))
        assertEquals(listOf("'\\d' is no escape in a string: it is just 'd'. For a regular expression's \\d, write '\\\\d'."), messages(""" f("\d+"); """).filter { it.startsWith("'\\") })
        assertEquals(listOf("'\\.' is no escape in a string: it is just '.'. For a regular expression's \\., write '\\\\.'."), messages(""" f("a\.b"); """).filter { it.startsWith("'\\") })
        // (each piece of a template, between its expressions)
        assertEquals(2, spans(" f(`\\d\${x}\\w`); ", "useless-escape").size)
        // (a string the recovery mode cut short is read only as far as its node goes; a template left open, which it
        // closes at the source's end, and one with an ErrorNode in it are not read)
        clean(""" let a = "a\d;""" + "\nlet b = 1;", "useless-escape")
        clean(" var greeting = `Hello\nfunction isNumber(s) {\n  return /^\\d+(\\.\\d+)?$/.test(s);\n}\n", "useless-escape", SourceType.SCRIPT)
        clean(" const pattern = `\\d+-\${}`; log(`\\(\${a +})`); ", "useless-escape")
        clean("var greeting = `Hello\nfunction isNumber(s) {\n  return /^\\d+(\\.\\d+)?\$/.test(s);\n}\nvar label = `Number`;", "useless-escape", SourceType.SCRIPT)
        clean("const title = `Report\nexport const html = String.raw`<input pattern=\"\\d{4}\">`;", "useless-escape")
        // (nor what the recovery mode reads on its line after a statement it passed over: here a regular expression's text)
        clean("if (a b) /;\"\\d\"/g.exec(s);", "useless-escape", SourceType.SCRIPT)
        clean("if (a b) /;[1,,2]/g.test(s);", "sparse-array", SourceType.SCRIPT)
        // (a template that is a tag is no tagged template)
        assertEquals(1, spans(" const patterns = [`^\\d+\$` `^\\w+\$`]; ", "useless-escape").size)
    }

    @Test
    fun unusedVariable() {
        // the spec's examples, in a module: a top-level function that is not exported is one too
        assertEquals(listOf("f declared", "x declared"), unused("function f() { let x = 1; }"))
        assertEquals(listOf("f declared", "x assigned"), unused("function f() { let x; x = 1; }"))
        assertEquals(listOf("f declared", "g declared"), unused("function f() { const g = () => 1; }"))
        assertEquals(listOf("f declared", "inner declared"), unused("function f() { function inner() {} }"))
        assertEquals(listOf("a declared"), unused("import { a } from \"./a.js\";"))
        assertEquals(listOf("onMessage declared"), unused("function onMessage(event) {}"))
        assertEquals(listOf("helper declared"), unused("const helper = 1;"))
        assertEquals(listOf("item declared"), unused("for (const item of list) { n++; }"))
        assertEquals(listOf("f declared"), unused("function f() { let x = 1; return x; }"))
        assertEquals(listOf("f declared"), unused("function f(a, b) { return b; }"))
        for (code in listOf(
            "try { f(); } catch (e) { }", "export function onMessage(event) {}", "const a = 1; export { a };", "for (const _ of list) n++;",
            "const { password, ...rest } = user; use(rest);", "function f() { let x = 1; eval(\"x\"); }", "let v = 1; typeof v;",
        )) assertEquals(emptyList<String>(), unused(code), code)
        // ... and in a script, where the top level is global
        for (code in listOf("function f() { let x = 1; return x; }", "function f(a, b) { return b; }", "var cache = {}; function response(room, msg) {}", "const helper = 1;"))
            assertEquals(emptyList<String>(), unused(code, SourceType.SCRIPT), code)
        assertEquals(listOf("unused declared"), unused("function response() { var unused = 1; }", SourceType.SCRIPT))

        // reads: an update or compound assignment whose value is used, a function or class inside itself, a sloppy
        // block function through its var, `export`, a name in `with`
        fun inScript(body: String) = unused("function f() { $body }", SourceType.SCRIPT)
        assertEquals(listOf("x assigned"), inScript("let x = 0; x++;"))
        assertEquals(listOf("x assigned"), inScript("let x = 0; x += 1;"))
        assertEquals(listOf("x assigned"), inScript("let x = 0; for (;; x++) {}"))
        assertEquals(listOf("x assigned"), inScript("let x = 0, y = 0; x++, y++; return y;").filter { it.startsWith("x") })
        assertEquals(emptyList<String>(), inScript("let x = 0; return x++;"))
        assertEquals(emptyList<String>(), inScript("let x = 0; g(x += 1);"))
        assertEquals(emptyList<String>(), inScript("let x = 0; y = x++;"))
        assertEquals(emptyList<String>(), inScript("let i; i ??= g(); let j; j ||= g(); let k = 1; k &&= g();"))
        assertEquals(emptyList<String>(), inScript("let immediate; w = () => { immediate ??= setImmediate(() => { immediate = undefined; }); };"))
        assertEquals(emptyList<String>(), inScript("for (const x of xs) return true; for (const k in o) { return false; } for (const [a, b] of e) return;"))
        assertEquals(listOf("x declared"), inScript("for (const x of xs) { n++; return; }"))
        assertEquals(emptyList<String>(), inScript("function g() { g(); }"))
        assertEquals(emptyList<String>(), inScript("class C { static make() { return new C(); } }"))
        assertEquals(emptyList<String>(), inScript("if (a) { function g() {} } g();"))
        assertEquals(emptyList<String>(), unused("{ function g() {} }", SourceType.SCRIPT))
        assertEquals(emptyList<String>(), unused("function f(o) { let x = 1; with (o) { x; } }", SourceType.SCRIPT))
        assertEquals(emptyList<String>(), unused("export default function f() {} export class C {} export const a = 1, { b } = o; import * as ns from 'm'; ns.x;"))
        assertEquals(emptyList<String>(), unused("import d, { a as b } from 'm'; export { d, b as default };"))
        assertEquals(emptyList<String>(), unused("function f() { using r = open(); await using s = open(); } export { f };"))
        assertEquals(emptyList<String>(), unused("let { a, b: [c], ...rest } = o; export { rest };"))
        assertEquals(listOf("C declared"), unused("class C { m() { return 1; } }"))
        // (a function with a `with` in it may read any of its names through it, as the spec's eval)
        assertEquals(emptyList<String>(), unused("function response(room, msg, sender, isGroupChat, replier) {\n  var prefix = \"!\";\n" +
            "  with (new JavaImporter(java.lang)) {\n    replier.reply(String.valueOf(msg));\n  }\n}", SourceType.SCRIPT))
        // (a sloppy block function with a "use strict" of its own: the engine makes it no var, but the language does)
        assertEquals(emptyList<String>(), inScript("if (flag) function chosen() { \"use strict\"; return 1; } return typeof chosen === \"function\" ? chosen() : 0;"))
        assertEquals(emptyList<String>(), inScript("if (msg) { function command(t) { \"use strict\"; return t; } } if (typeof command === \"function\") g(command(msg));"))
        assertEquals(emptyList<String>(), unused("if (typeof Api !== \"undefined\") {\n  function onStartCompile() { \"use strict\"; Api.reload(); }\n}", SourceType.SCRIPT))
        assertEquals(emptyList<String>(), inScript("if (fast) { function handler(e) { \"use strict\"; return e; } } target.onMessage = handler ??= fallback;"))
        // ... but not where the language makes no var either: an async function, a parameter's or a let's name
        assertEquals(listOf("onStartCompile declared"), unused("if (typeof Api !== \"undefined\") {\n  async function onStartCompile() { Api.reload(); }\n}", SourceType.SCRIPT))
        assertEquals(listOf("load declared"), inScript("if (m) { async function load() { r(); } } if (typeof load === \"function\") load();"))
        assertEquals(listOf("transform declared"), unused("function format(text, transform) {\n  if (typeof transform !== \"function\") {\n    function transform(s) { return s; }\n  }\n  return transform(text);\n}", SourceType.SCRIPT))
        assertEquals(listOf("handler declared"), inScript("let handler = d; if (fast) { function handler() { return 1; } } return handler;"))
        // (a function of a parameter's name, which a sloppy function's `arguments` reads)
        assertEquals(emptyList<String>(), unused("function f(a) {\n  function a() { return 1; }\n  return arguments[0];\n}\nf();", SourceType.SCRIPT))
        assertEquals(listOf("a declared"), unused("\"use strict\";\nfunction f(a) {\n  function a() { return 1; }\n  return arguments[0];\n}\nf();", SourceType.SCRIPT))
        // (a var of a parameter's name, where parameter expressions give it a binding of its own)
        assertEquals(emptyList<String>(), unused("export function pick(primary, fallback = primary) {\n  var primary;\n  return fallback;\n}"))
        assertEquals(emptyList<String>(), unused("function response(room, msg, sender, prefix = \"!\") {\n  if (!msg.startsWith(prefix)) { var sender = null; return; }\n}", SourceType.SCRIPT))
        // (a class with a decorator or a static block registers itself)
        assertEquals(emptyList<String>(), unused("import { customElement } from \"./elements.js\";\n@customElement(\"chat-badge\")\nclass ChatBadge { render() { return 1; } }"))
        assertEquals(emptyList<String>(), unused("import { registry } from \"./registry.js\";\nclass EchoCommand { static { registry.add(\"echo\", this); } }"))
        assertEquals(emptyList<String>(), unused("import { commands } from \"./commands.js\";\nclass PingCommand {\n  static registered = commands.register(\"ping\", this);\n}"))
        assertEquals(emptyList<String>(), unused("import { on } from \"./events.js\";\nclass Greeter {\n  @on(\"message\")\n  static greet(event) { event.reply(\"hi\"); }\n}"))
        assertEquals(listOf("Plain declared"), unused("class Plain { static count = 0; static #seen = []; m() { return 1; } }"))
        // (a loop that does nothing drains an iterator: its names are needed)
        assertEquals(emptyList<String>(), inScript("for (const x of xs); for (const k in o) {} return async () => { for await (const unused of it); };"))
        // (a function of a parameter's name is told at its name)
        val shadow = "function response(room, replier) {\n  function replier() {}\n}"
        assertEquals(listOf("${shadow.indexOf("replier()")}-${shadow.indexOf("replier()") + 7}"), spans(shadow, "unused-variable", SourceType.SCRIPT))
        // (a var declared twice is told once, at the first)
        assertEquals(listOf("a declared"), inScript("var a; var a = 1;"))
        assertEquals(listOf("19-20"), spans("function f() { var a; var a = 1; }", "unused-variable", SourceType.SCRIPT))
        // (a syntax error anywhere turns the rule off: the use may be in what the parser could not read)
        assertEquals(emptyList<String>(), unused("function f() { let x = 1; }\nlet y = (;"))
        assertEquals(emptyList<String>(), unused("function f() { let x = 1; g(x }"))
        val d = NeonLint.lint("function f() { let x = 1; let y; y = 2; } export { f };").diagnostics
        assertEquals(listOf("'x' is declared and never used.", "'y' is assigned and never read."), d.map { it.message })
        assertEquals(listOf(listOf("x", "declared"), listOf("y", "assigned")), d.map { it.args })
    }

    @Test
    fun constantCondition() {
        both { t ->
            for (code in listOf(
                "if (true) f();", "if (0) f();", "if (\"debug\") f();", "const v = null ? a : b;", "if ([]) f();", "if ({}) f();",
                "if (typeof x) f();", "if (!0) f();", "if (undefined) f();",
                "if (``) f();", "if (`x`) f();", "if (void 0) f();", "if (-0) f();", "if (0n) f();", "if (/re/) f();",
                "if (function () {}) f();", "if (() => 1) f();", "if (class {}) f();", "if (new Foo()) f();", "if (!!1) f();",
            )) flags(code, "constant-condition", t)
            for (code in listOf(
                "while (true) { if (done()) break; }", "for (;;) { }", "do { } while (false);", "const DEBUG = false; if (DEBUG) f();",
                "if (typeof x === \"string\") f();", "if (a || true) f();", "function g(undefined) { if (undefined) f(); }",
                "if (`\${a}`) f();", "if (-a) f();",
            )) clean(code, "constant-condition", t)
        }
        assertEquals(listOf("L1 w cond-assign"), marks("if (a = 1) f();"))
        assertEquals(listOf("This condition is always true.", "This condition is always false."), messages("if (true) f(); if (0) g();"))
        assertEquals(listOf(listOf("true"), listOf("false")), NeonLint.lint("if (true) f(); if (0) g();").diagnostics.map { it.args })
        // (the condition is the span; nothing is told of a condition, or a conditional, the parser could not read)
        assertEquals(listOf("4-8"), spans("if (true) f();", "constant-condition"))
        assertEquals(listOf("L1 e syntax/missing-expression"), marks("if () {}"))
        assertEquals(listOf("L1 e syntax/missing-expression"), marks("f(true ? : b);"))
        // (nor of a literal the recovery mode made, repairing the token: `"abc` is read as `""`, `1x` and an open
        // regular expression as `0`); a condition beside a syntax error is still told
        for (code in listOf("if (\"abc) f();", "if (1x) f();", "if (/abc) f();", "if (`abc) f();", "f(1if 1, 9007199254740993x);",
            "if (0x) f();", "const v = 0.5x ? a : b;", "if (0_1) f();", "if (1e) run();", "f(100px);"))
            assertTrue(marks(code).none { it.endsWith(" constant-condition") || it.endsWith(" precision-loss") }, "$code -> ${marks(code)}")
        assertEquals(listOf("L1 w constant-condition", "L2 e syntax/unexpected-token"), marks("if (true) f();\nlet x = (;"))
        // (nor of a condition the recovery mode made, pretending a `,` or `;` before it)
        for (code in listOf("log(typeof value \"string\" ? \"text\" : \"other\");", "export const labels = [count !0 ? count : \"none\"];",
            "let mode = config.debug true ? \"debug\" : \"release\";\nexport { mode };", "var shown = value undefined ? \"none\" : value;\nlog(shown);",
            "var r = f(i++ (0) ? a : b);", "[i-- (!0) ? a : b];", "x = c ? a++ (0) ? b : d;",
            // (after a token it repaired, the recovery mode tells no error for what it pretends there)
            "var v = DEBUG # true ? \"debug\" : \"release\";", "x = \"\\x\" 0 ? a : b;", "x = 1_ 0 ? a : b;", "let v = a\\u0000 0 ? b : c;"))
            clean(code, "constant-condition")
        assertEquals(listOf("L1 e syntax/unexpected-token", "L2 w constant-condition"), marks("let x = (;\nif (true) f();"))
        // (nor of one with an early error in it, which leaves no ErrorNode: an arrow function with what is no parameter)
        clean("try { f(); } catch (e) {\n  if ((e instanceof TypeError) => true) {\n    g();\n  }\n}", "constant-condition", SourceType.SCRIPT)
        // (`undefined` may be a declaration's when the source declares one, which the engine misses in a case)
        clean("function load(flag) {\n  if (flag) function undefined() { \"use strict\"; }\n  return undefined ? 1 : 0;\n}", "constant-condition", SourceType.SCRIPT)
    }

    // ------------------------------------------------------------ the API

    @Test
    fun settingsTurnRulesOffOrChangeTheirSeverity() {
        assertEquals(emptyList<String>(), marks("debugger;", rules = mapOf("debugger" to RuleSetting.OFF)))
        assertEquals(listOf("L1 e debugger"), marks("debugger;", rules = mapOf("debugger" to RuleSetting.ERROR)))
        assertEquals(listOf("L1 w const-assign"), marks("const a = 1; a = 2;", rules = mapOf("const-assign" to RuleSetting.WARNING, "unused-variable" to RuleSetting.OFF)))
    }

    @Test
    fun syntaxErrorsHaveTheEnginesMessageAndARange() {
        val src = "let a = 1;\nlet a = 2;\nfunction f() {\n  if (x) {\n"
        val d = NeonLint.lint(src).diagnostics
        assertEquals(listOf("syntax/redeclared", "syntax/unexpected-end"), d.map { it.code })
        assertEquals("Identifier 'a' has already been declared", d[0].message)
        assertEquals(listOf("a"), d[0].args)
        assertEquals(src.indexOf("a", 12), d[0].start)
        assertEquals(d[0].start + 1, d[0].end)
        assertEquals(src.indexOf("{", src.indexOf("if")), d[1].related.single().start)
        assertEquals(2, NeonLint.lint(src).syntaxErrorCount)
    }

    @Test
    fun aCancelEndsTheCheck() {
        var asked = 0
        val r = NeonLint.lint("let a = 1;\n".repeat(1000), LintOptions(isCancelled = { ++asked > 3 }))
        assertTrue(r.cancelled)
        assertTrue(r.diagnostics.isEmpty())
    }

    @Test
    fun aHostsRuleReadsTheTree() {
        @OptIn(LintTreeApi::class)
        val rule = object : LintRule {
            override val code = "starlight/unexported-event"
            override val defaultSeverity = Severity.WARNING
            override fun check(context: LintContext) {
                for (st in context.program.body) {
                    val f = (st as? dev.mooner.neonjs.parser.FunctionDeclaration)?.function ?: continue
                    if (f.id?.name == "onMessage") context.report(f.id!!, "'onMessage' is not exported: the app does not call it.", listOf("onMessage"))
                }
            }
        }
        @OptIn(LintTreeApi::class)
        val failing = object : LintRule {
            override val code = "starlight/broken"
            override val defaultSeverity = Severity.WARNING
            override fun check(context: LintContext) = throw IllegalStateException("bug")
        }
        @OptIn(LintTreeApi::class)
        val r = NeonLint.lint("function onMessage(e) {}\ndebugger;", LintOptions(extraRules = listOf(rule, failing)))
        // (the built-in unused-variable tells the same, in its own words)
        assertEquals(listOf("internal-error", "unused-variable", "starlight/unexported-event", "debugger"), r.diagnostics.map { it.code })
    }

    @Test
    fun nestingTooDeepIsOneDiagnosticOnASmallStack() {
        var result: LintResult? = null
        val t = Thread(null, { result = NeonLint.lint("(".repeat(50_000) + "1" + ")".repeat(50_000)) }, "small", 256L * 1024)
        t.start()
        t.join()
        assertEquals(listOf(NeonLint.TOO_DEEP), result!!.diagnostics.map { it.code })
    }

    @Test
    fun everyCodeIsListed() {
        val codes = NeonLint.rules.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
        for (c in listOf("const-assign", "use-before-define", "undefined-name", "syntax/unexpected-token", "syntax/error", "too-deep")) assertTrue(c in codes, c)
    }
}
