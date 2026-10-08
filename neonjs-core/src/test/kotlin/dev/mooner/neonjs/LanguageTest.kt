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
    fun directEvalInModulesSeesImportedValues() {
        for (mode in listOf(ExecutionMode.INTERPRETER, ExecutionMode.COMPILED, ExecutionMode.ADAPTIVE)) {
            NeonEngine.builder().executionMode(mode).console(null).build().newContext().use { c ->
                c.setModuleLoader(MapModuleLoader(mapOf(
                    "lib.js" to "export function foo() { return 'foo!' } export let n = 1; export function bump() { n++ }",
                )))
                val received = ArrayList<Boolean>()
                c["host"] = c.createFunction("host") { a -> received.add(a[0].isFunction); null }
                val ns = c.evalModule(
                    """
                    import { foo, n, bump } from './lib.js';
                    export const type = eval('typeof foo');
                    export const called = eval('foo()');
                    export const nested = (() => eval('(() => foo())()'))();
                    eval('host(foo)');
                    bump();
                    export const live = eval('n');
                    let error;
                    try { eval('foo = 1') } catch (e) { error = e.name }
                    export const assigned = error + ' ' + typeof foo;
                    """.trimIndent(), "main.js",
                )
                assertEquals("function", ns.getMember("type").asString(), "mode $mode")
                assertEquals("foo!", ns.getMember("called").asString(), "mode $mode")
                assertEquals("foo!", ns.getMember("nested").asString(), "mode $mode")
                assertEquals(2, ns.getMember("live").asInt(), "mode $mode: imports stay live bindings")
                assertEquals("TypeError function", ns.getMember("assigned").asString(), "mode $mode: imports are immutable")
                assertEquals(listOf(true), received, "mode $mode")
            }
        }
    }

    @Test
    fun notCallableMessagesNameTheCallee() {
        val setup = "var o = { f() { return {} }, a: [1] }; function m(f) { try { f() } catch (e) { return e.message } } "
        both(setup + "m(() => o.f().g())", "o.f(...).g is not a function")
        both(setup + "m(() => o.f(1, 'a)b', [2]).g())", "o.f(...).g is not a function")
        both(setup + "m(() => o\n  .f()\n  .g())", "o.f(...).g is not a function")
        both(setup + "m(() => o.a[0]())", "o.a[0] is not a function")
        both(setup + "m(() => o?.f().zz())", "o?.f(...).zz is not a function")
        both(setup + "m(() => (0, o.zz)())", "(0, o.zz) is not a function")
        both(setup + "m(() => o.f`q`.k())", "o.f`q`.k is not a function")
        both(setup + "m(() => new o.a())", "o.a is not a constructor")
        both(setup + "m(() => o.nope())", "o.nope is not a function")
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

    @Test
    fun arrayCallbacksSeeWhatCallbacksChange() {
        // the result array, reached through Symbol.species, changed by the callback between two elements
        val species = "var keep; function C(n) { keep = new Array(n); return keep } function src(a) { a.constructor = {}; a.constructor[Symbol.species] = C; return a } "
        both(
            species + "var r; try { src([1, 2, 3]).map(function (x, i) { if (i === 1) Object.freeze(keep); return x * 2 }); r = 'no error' } catch (e) { r = e.name } " +
                "r + ' ' + keep.length + ' ' + keep[0] + ' ' + (1 in keep)",
            "TypeError 3 2 false",
        )
        // preventExtensions keeps the elements dense: new ones are refused, existing ones can still be redefined
        both(
            species + "var r; try { src([1, 2, 3]).map(function (x, i) { if (i === 1) Object.preventExtensions(keep); return x * 2 }); r = 'no error' } catch (e) { r = e.name } " +
                "r + ' ' + keep.length + ' ' + keep[0] + ' ' + (1 in keep)",
            "TypeError 3 2 false",
        )
        both(
            "function F(n) { keep = [7, 7, 7]; return keep } var keep, a = [1, 2, 3]; a.constructor = {}; a.constructor[Symbol.species] = F; " +
                "a.map(function (x, i) { if (i === 0) Object.preventExtensions(keep); return x * 2 }).join()",
            "2,4,6",
        )
        both(
            species + "var r; try { src([1, 2, 3]).filter(function (x, i) { if (i === 1) Object.defineProperty(keep, 'length', { writable: false }); return true }); r = 'no error' } " +
                "catch (e) { r = e.name } r + ' ' + keep.length + ' ' + keep[0]",
            "TypeError 1 1",
        )
        both(
            species + "var out = src([1, 2, 3, 4]).map(function (x, i) { if (i === 1) keep[5000000] = 'far'; return x * 10 }); " +
                "[out === keep, out[0], out[1], out[2], out[3], out[5000000], out.length].join()",
            "true,10,20,30,40,far,5000001",
        )
        both(
            "var log = []; function P() { return new Proxy([], { defineProperty: function (t, k, d) { log.push(k + ':' + d.value + d.writable + d.enumerable + d.configurable); " +
                "return Reflect.defineProperty(t, k, d) } }) } var a = [1, 2]; a.constructor = {}; a.constructor[Symbol.species] = P; a.map(function (x) { return x + 1 }); log.join()",
            "0:2truetruetrue,1:3truetruetrue",
        )
        // the source array changed by the callback
        both(
            "var a = [1, 2, 3, 4, 5], seen = []; a.forEach(function (x, i) { seen.push(i + '=' + x); if (i === 0) delete a[2]; if (i === 1) a.length = 4; if (i === 3) a.push(9) }); seen.join()",
            "0=1,1=2,3=4,4=9",
        )
        both(
            // (seen is a string: with that getter on Array.prototype, pushing to an array would throw)
            "var a = [1, , 3], seen = ''; Object.defineProperty(Array.prototype, 1, { get: function () { return 'p' }, configurable: true }); " +
                "var r = a.map(function (x) { seen += x; return x }); var own = r.hasOwnProperty(1); delete Array.prototype[1]; seen + ' ' + r.join() + ' ' + own",
            "1p3 1,p,3 true",
        )
        both("var a = [1, 2, 3], seen = []; a.forEach(function (x, i) { seen.push(x); if (i === 0) a[3000000] = 'z' }); seen.join() + ' ' + a.length", "1,2,3 3000001")
        both("String([1, 2, 3, 4].reduce(function (acc, x, i, arr) { if (i === 1) arr.pop(); return acc + x }))", "6")
        both("var r = [1, 2, 3, 4].filter(function (x, i, arr) { if (i === 0) arr[1] = 20; return x % 2 === 0 }); r.join()", "20,4")
        both("var r = Array.prototype.slice.call({ length: 3, 0: 'a', 2: 'c' }); r.join() + ' ' + (1 in r) + ' ' + [1, 2].concat([3, , 5]).length", "a,,c false 5")
    }
}
