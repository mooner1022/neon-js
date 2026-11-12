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
        assertEquals(listOf(
            "L1 e const-assign", "L2 w cond-assign", "L3 w unreachable", "L4 w fallthrough", "L5 w duplicate-key",
            "L6 w nan-comparison", "L7 w invalid-typeof", "L8 w debugger", "L9 e use-before-define",
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
        assertEquals(emptyList<String>(), marks("let a = 1\nconst o = {}; o['a'] = new Date;;\nLog.i(fizz);\n"))
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
                listOf("L2 e syntax/unexpected-token", "L4 e syntax/unexpected-token", "L5 e const-assign"),
            "function f() {\n  if (a {\n    g();\n  }\n  if (b {\n    g();\n  }\n}\nconst k = 1; k = 2;\n" to
                listOf("L2 e syntax/missing-token", "L5 e syntax/missing-token", "L9 e const-assign"),
            "let a = 1;\n) ;\nlet b = 2 +;\nconst k = 1; k = 2;\n" to
                listOf("L2 e syntax/unexpected-token", "L3 e syntax/unexpected-token", "L4 e const-assign"),
            "let a = \"abc;\nlet b = 1;\nconst k = 1; k = 2;\n" to
                listOf("L1 e syntax/unterminated-string", "L3 e const-assign"),
            "class A {\n  foo( { }\n  bar() { const k = 1; k = 2; }\n  #x = ;\n}\n" to
                listOf("L3 e syntax/missing-token", "L3 e const-assign", "L4 e syntax/unexpected-token"),
            "function f(a, {\n  g();\n}\nfunction h() { const k = 1; k = 2; }\n" to
                listOf("L2 e syntax/unexpected-token", "L4 e const-assign"),
            "const o = {\n  a: 1\n  b: 2,\n};\nconst k = 1; k = 2;\n" to
                listOf("L3 e syntax/missing-token", "L5 e const-assign"),
            "function f() {\n  if (a) { g(); }\n  else if { h(); }\n  const k = 1; k = 2;\n}\n" to
                listOf("L3 e syntax/missing-token", "L4 e const-assign"),
            // (both early errors of the second export: the engine throws the first)
            "export function a() {}\nexport function a() {}\nconst k = 1; k = 2;\n" to
                listOf("L2 e syntax/redeclared", "L2 e syntax/duplicate-export", "L3 e const-assign"),
        )
        for ((code, expected) in cases) assertEquals(expected, marks(code), code)
        assertEquals(listOf("L2 e syntax/unexpected-token", "L3 e const-assign"),
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
        assertEquals(listOf("'a' is constant."), messages("const a = 0; a = 1;"))
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

    // ------------------------------------------------------------ the API

    @Test
    fun settingsTurnRulesOffOrChangeTheirSeverity() {
        assertEquals(emptyList<String>(), marks("debugger;", rules = mapOf("debugger" to RuleSetting.OFF)))
        assertEquals(listOf("L1 e debugger"), marks("debugger;", rules = mapOf("debugger" to RuleSetting.ERROR)))
        assertEquals(listOf("L1 w const-assign"), marks("const a = 1; a = 2;", rules = mapOf("const-assign" to RuleSetting.WARNING)))
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
        assertEquals(listOf("internal-error", "starlight/unexported-event", "debugger"), r.diagnostics.map { it.code })
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
