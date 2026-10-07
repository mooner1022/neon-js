package dev.mooner.neonjs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Engine-level language behaviour not covered (or not exercised in both execution modes) by Test262. */
class LanguageTest {
    private fun eval(code: String, mode: ExecutionMode): NeonValue =
        NeonEngine.builder().executionMode(mode).console(null).build().newContext().eval(code)

    private fun both(code: String, expected: String) {
        for (mode in listOf(ExecutionMode.INTERPRETER, ExecutionMode.COMPILED)) {
            assertEquals(expected, eval(code, mode).asString(), "mode $mode")
        }
    }

    @Test
    fun properTailCalls() {
        both("'use strict'; function f(n, acc) { return n === 0 ? acc : f(n - 1, acc + 1) } String(f(200000, 0))", "200000")
        both("'use strict'; function e(n) { return n === 0 || o(n - 1) } function o(n) { return n !== 0 && e(n - 1) } String(e(100001))", "false")
        both("'use strict'; var o = { m(n) { return n ? this.m(n - 1) : 'done' } }; o.m(100000)", "done")
        both("'use strict'; function F(n) { return n ? F(n - 1) : { ok: 'yes' } } new F(50000).ok", "yes")
        // not in tail position: still bounded by the call-depth limit
        both("'use strict'; function g(n) { return n ? 1 + g(n - 1) : 0 } try { g(1e6) } catch (e) { e.name }", "RangeError")
        both("function s(n) { return n ? s(n - 1) : 0 } try { s(1e6) } catch (e) { e.name }", "RangeError")
        both("'use strict'; function t(n) { try { return n ? t(n - 1) : 0 } finally {} } try { t(1e6) } catch (e) { e.name }", "RangeError")
    }

    @Test
    fun classPartsAreStrictInSloppyCode() {
        both("function f() { try { class C extends (Object.preventExtensions({}).p = 1, Object) {} } catch (e) { return e.name } return 'none' } f()", "TypeError")
        both("function f() { try { (class { [(undeclaredX = 1, 'k')]() {} }) } catch (e) { return e.name } return 'none' } f()", "ReferenceError")
        both("function f() { var o = Object.freeze({}); try { (class { [o.p = 1]() {} }) } catch (e) { return e.name } return 'none' } f()", "TypeError")
        both("function f() { var o = Object.freeze({a: 1}); try { (class { [delete o.a]() {} }) } catch (e) { return e.name } return 'none' } f()", "TypeError")
        // direct eval in a computed key is strict: its var declarations stay local
        both("function f() { (class { [eval('var leak = 1; \"m\"')]() {} }); return typeof leak } f()", "undefined")
        // the surrounding sloppy code is unaffected
        both("function f() { Object.freeze({}).p = 1; undeclaredY = 2; class C {} eval('var e1 = 1'); return typeof undeclaredY + typeof e1 } f()", "numbernumber")
    }

    @Test
    fun sloppyFunctionsWithClassesAreStillCompiled() {
        NeonEngine.builder().executionMode(ExecutionMode.COMPILED).console(null).build().newContext().use { ctx ->
            val plain = ctx.eval("function f(x) { class C extends Object { static [x]() {} } return C } f('a'); f").raw as dev.mooner.neonjs.vm.JSClosure
            assertNotNull(plain.code.compiled, "no strict-sensitive instruction in the class: JIT-compiled")
            assertNull(plain.code.strictPcs)
            val sensitive = ctx.eval("function g(o) { return class { [o.k = 'm']() {} } } g({}); g").raw as dev.mooner.neonjs.vm.JSClosure
            assertNotNull(sensitive.code.strictPcs)
            assertEquals("TypeError", ctx.eval("try { g(Object.freeze({})) } catch (e) { e.name }").asString())
        }
    }

    @Test
    fun awaitInClassFieldInitializer() {
        // field initializers are parsed with [~Await]: `await` is an identifier there in scripts, even in async code
        both("var await = 1, r; async function g() { r = new (class { x = await })().x } g(); String(r)", "1")
        both("var out = []; for (const src of ['async () => class { x = await 1 }', 'async () => class { [await] = 1 }']) { try { eval(src); out.push('ok') } catch (e) { out.push(e.name) } } out.join()",
            "SyntaxError,SyntaxError")
    }

    @Test
    fun engineErrorsPointAtTheFailingStatement() {
        // found by the fuzzer: errors raised by the engine (not by `new Error`) used to report the last call site
        val code = """
            var r = [];
            function g(o) {
              var a = 1;
              return o.x.y;
            }
            try { g({}) } catch (e) { r.push(e.stack.split('\n')[1].trim()) }
            try {
              g({ x: {} });
              undefinedName;
            } catch (e) { r.push(e.stack.split('\n')[1].trim()) }
            r.join(' | ')
        """.trimIndent()
        both(code, "at g (<eval>:4:10) | at <script> (<eval>:9:3)")
        // an engine error swallowed by native code and rethrown later keeps the place where it was raised
        val rethrown = """
            var r = [];
            async function h() {
              const p = new Promise(JSON.parse);
              await p;
            }
            h().catch(e => r.push(e.stack.split('\n')[1].trim()));
        """.trimIndent()
        for (mode in listOf(ExecutionMode.INTERPRETER, ExecutionMode.COMPILED)) {
            NeonEngine.builder().executionMode(mode).console(null).build().newContext().use { ctx ->
                ctx.eval(rethrown)
                assertEquals("at h (<eval>:3:13)", ctx.eval("r.join()").asString(), "mode $mode")
            }
        }
    }

    @Test
    fun arrowThisAtTopLevel() {
        both("var u = {}; var r; [1].forEach(() => { r = (this === u) }, u); String(r)", "false")
        both("String((() => this).call(5) === globalThis)", "true")
    }
}
