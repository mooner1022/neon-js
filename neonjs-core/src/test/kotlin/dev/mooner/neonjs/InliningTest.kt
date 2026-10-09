package dev.mooner.neonjs

import dev.mooner.neonjs.builtins.Builtins
import dev.mooner.neonjs.jit.JvmCompiler
import dev.mooner.neonjs.runtime.Realm
import dev.mooner.neonjs.vm.Evaluator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

/**
 * Calls compiled with the callee's body inlined (jit/Inlining.kt). Each case runs interpreted and in adaptive mode, where
 * functions are compiled on their second call with the calls recorded during the first: the caller below warms up with
 * one call (recording its calls), so later calls run compiled code with the callee inlined. Both must give the same
 * result, stack traces included, and something must have been inlined.
 */
class InliningTest {
    private val prelude = """
        function show(v) {
          if (typeof v === 'number') return Object.is(v, -0) ? '-0' : String(v);
          if (typeof v === 'bigint') return v + 'n';
          if (typeof v === 'string') return JSON.stringify(v);
          return String(v);
        }
        function all() { return Array.prototype.map.call(arguments, show).join(' '); }
    """.trimIndent() + "\n"

    private fun run(code: String, mode: ExecutionMode, policy: SandboxPolicy? = null, threshold: Int = 2): String {
        val b = NeonEngine.builder().executionMode(mode).console(null)
        if (mode == ExecutionMode.ADAPTIVE) b.jitThreshold(threshold).backgroundCompilation(false)
        if (policy != null) b.sandbox(policy)
        return b.build().newContext().use { it.eval(prelude + code).asString() }
    }

    /** [threshold]: recursive functions are called by themselves; they need more calls to record theirs first. */
    private fun same(code: String, expected: String? = null, policy: SandboxPolicy? = null, threshold: Int = 2) {
        val inlined = JvmCompiler.inlinedCalls.get()
        val failed = JvmCompiler.failedCount.get()
        val interpreted = run(code, ExecutionMode.INTERPRETER, policy)
        if (expected != null) assertEquals(expected, interpreted, "interpreter: $code")
        assertEquals(interpreted, run(code, ExecutionMode.ADAPTIVE, policy, threshold), "adaptive: $code")
        assertTrue(JvmCompiler.inlinedCalls.get() > inlined, "nothing inlined: $code")
        assertEquals(failed, JvmCompiler.failedCount.get(), "not compiled (${JvmCompiler.failureReasons.keys}): $code")
    }

    /**
     * As [same], against compiled code without inlining: where compiled code and the interpreter already differ (the
     * position of an error raised inside an operator, e.g. by valueOf: compiled code records the expression's).
     */
    private fun sameAsCompiled(code: String) {
        val inlined = JvmCompiler.inlinedCalls.get()
        assertEquals(run(code, ExecutionMode.COMPILED), run(code, ExecutionMode.ADAPTIVE), "adaptive: $code")
        assertTrue(JvmCompiler.inlinedCalls.get() > inlined, "nothing inlined: $code")
    }

    @Test
    fun resultsOfEveryKind() {
        // the user's benchmark shape: a callback through a parameter, and recursion through a global const
        same(
            "const radius = (r) => { return 0.5 * r * r * Math.PI; }; const fib = (n) => { if (n <= 1) return 1; else return fib(n - 1) + fib(n - 2); };" +
                "const repeat = (count, block) => { let s = 0; for (let i = 0; i < count; i++) { s += block(i); } return s; };" +
                "var r = []; for (var k = 0; k < 3; k++) r.push(repeat(40, radius), fib(15)); r.join()",
        )
        // results of each kind, used as numbers, discarded, compared, concatenated
        same(
            "function i(x) { return x | 0 } function d(x) { return x / 3 } function s(x) { return 'v' + x } function o(x) { return { x: x } } function b(x) { return x > 2 } function u(x) { }" +
                "function neg(x) { return -x } function mixed(x) { return x > 5 ? 'big' : x * 0.5 }" +
                "function caller(n) { var r = []; for (var k = 0; k < n; k++) { var a = i(k) + 1, c = d(k) * 2, e = s(k), f = o(k).x, g = b(k), h = u(k), z = neg(k - k); i(k); d(k);" +
                " r.push(all(a, c, e, f, g, h, z, i(k) | 0, d(k) > 1, mixed(k), (i(k) << 1) >>> 0)) } return r.join(';') }" +
                "caller(20); caller(20); caller(8)",
        )
        // fewer and more arguments than parameters, default values
        same(
            "function f(a, b, c = 7) { return all(a, b, c) } function g(x, y) { return x + y } function caller(n) { var r = []; for (var k = 0; k < n; k++) r.push(f(k), f(k, 2), f(k, 2, 3, 4), g(k, 1, 2), g()); return r.join('|') }" +
                "caller(20); caller(20); caller(3)",
        )
    }

    @Test
    fun stackTracesShowTheInlinedCall() {
        same(
            "function inner(x) {\n  if (x === 1000) throw new Error('at ' + x);\n  return x + 1;\n}\nfunction outer(n, v) {\n  var s = 0;\n  for (var k = 0; k < n; k++) s += inner(k === n - 1 ? v : k);\n  return s;\n}\n" +
                "outer(20, 0); outer(20, 0); var r; try { outer(5, 1000) } catch (e) { r = e.stack } r",
        )
        same(
            "function make(x) {\n  return new Error('made ' + x).stack;\n}\nfunction outer(n) {\n  var s = '';\n  for (var k = 0; k < n; k++) s = make(k);\n  return s;\n}\nouter(20); outer(20); outer(3)",
        )
        // an Error created by valueOf, called from arithmetic in the inlined body
        sameAsCompiled(
            "function inner(x) {\n  return x * 2;\n}\nfunction outer(n, v) {\n  var s = 0;\n  for (var k = 0; k < n; k++) s += inner(k === n - 1 ? v : k);\n  return s;\n}\n" +
                "outer(20, 1); outer(20, 1); var r; try { outer(3, { valueOf: function () { throw new RangeError('from valueOf') } }) } catch (e) { r = e.stack } r",
        )
        // an Error made in the inlined body and thrown by the caller keeps the stack of where it was made
        same(
            "function mk(x) {\n  return new Error('made ' + x);\n}\nfunction outer(n) {\n  var r = '';\n  for (var k = 0; k < n; k++) {\n    try { if (k === n - 1) throw mk(k); mk(k) } catch (e) { r = e.stack }\n  }\n  return r;\n}\n" +
                "outer(20); outer(20); outer(3)",
        )
        // errors after an inlined call: in the caller, in its catch, and the stack in a later call
        same(
            "function inner(x) {\n  if (x < 0) throw new TypeError('negative');\n  return x;\n}\nfunction outer(n, bad) {\n  var s = 0;\n  for (var k = 0; k < n; k++) {\n    try { s += inner(bad && k === 2 ? -1 : k) } catch (e) { s += 100; if (bad === 2) null.x }\n  }\n  return new Error('after ' + s).stack;\n}\n" +
                "outer(20, 0); outer(20, 0); var r = [outer(5, 1)]; try { outer(5, 2) } catch (e) { r.push(e.stack) } r.push(outer(4, 0)); r.join('\\n---\\n')",
        )
        // calling something that is not a function from the inlined body: the message names the callee's expression
        same(
            "function inner(f, x) {\n  return f(x) + 1;\n}\nfunction outer(n, g) {\n  var s = 0;\n  for (var k = 0; k < n; k++) s += inner(k === n - 1 ? g : Math.abs, k);\n  return s;\n}\n" +
                "outer(20, Math.abs); outer(20, Math.abs); var r; try { outer(3, 42) } catch (e) { r = e.message + ' | ' + e.stack } r",
        )
    }

    @Test
    fun guardsTakeTheOrdinaryCall() {
        // the call site sees other functions, another closure of the same code, a reassigned variable
        same(
            "function sq(x) { return x * x } function cube(x) { return x * x * x } function each(n, f) { var s = 0; for (var k = 0; k < n; k++) s += f(k); return s }" +
                "each(20, sq); each(20, sq); var r = [each(5, sq), each(5, cube), each(5, function (x) { return -x }), each(5, Math.sqrt), each(5, sq)]; r.join()",
        )
        same(
            "function makeAdder(a) { return function (x) { return x + a } } var add1 = makeAdder(1), add10 = makeAdder(10);" +
                "function each(n, f) { var s = 0; for (var k = 0; k < n; k++) s += f(k); return s } each(20, add1); each(20, add1); all(each(5, add1), each(5, add10), each(5, makeAdder(100)))",
        )
        same(
            "var g = function (x) { return x + 1 }; function caller(n) { var s = 0; for (var k = 0; k < n; k++) { s += g(k); if (k === 2 && n < 10) g = function (x) { return x * 100 } } return s }" +
                "caller(20); caller(20); var a = caller(5); var b = caller(5); all(a, b)",
        )
    }

    @Test
    fun calleesOfOtherModesOrRealmsAreCalled() {
        // strict and sloppy code behave differently in the same op (an assignment to a frozen object's property throws
        // only in strict code): a callee whose mode differs from the caller's is called, never inlined, while the other
        // calls of the caller are
        same(
            "var frozen = Object.freeze({ x: 0 }); function helper(x) { return x + 1 } function strictPut(o, x) { 'use strict'; o.x = x; return x }" +
                "function caller(n) { var s = 0; for (var k = 0; k < n; k++) { s += helper(k); try { s += strictPut(frozen, k) } catch (e) { s += e.name.length } } return s }" +
                "caller(20); caller(20); all(caller(3))",
        )
        same(
            "var frozen = Object.freeze({ x: 0 }); function helper(x) { 'use strict'; return x + 1 } function sloppyPut(o, x) { o.x = x; return x }" +
                "function caller(n) { 'use strict'; var s = 0; for (var k = 0; k < n; k++) { s += helper(k); try { s += sloppyPut(frozen, k) } catch (e) { s += e.name.length } } return s }" +
                "caller(20); caller(20); all(caller(3))",
        )
        // one compiled script runs in several realms (as Test262's createRealm does): a closure of the same code from
        // another realm is called, where it reads its own realm's globals, not inlined into the caller's realm
        fun go(mode: ExecutionMode): String {
            val b = NeonEngine.builder().executionMode(mode).console(null)
            if (mode == ExecutionMode.ADAPTIVE) b.jitThreshold(2).backgroundCompilation(false)
            val engine = b.build()
            val script = engine.compile("var tag = 'R'; function inner(x) { return tag + x } function caller(n, f) { var r = ''; for (var k = 0; k < n; k++) r += f(k); return r }", "s.js")
            return engine.newContext().use { c ->
                c.eval(script)
                c.call {
                    val r2 = Realm(c.agent)
                    Builtins.install(r2)
                    r2.enter {
                        Evaluator.runScript(r2, script.code)
                        Evaluator.evaluateScript(r2, "tag = 'two'", "t.js")
                    }
                    c.realm.globalObject.defineOwn("other", r2.globalObject.get("inner"))
                }
                c.eval("tag = 'one'; caller(20, inner); caller(20, inner); [caller(2, inner), caller(2, other), caller(2, inner)].join(' ')").asString()
            }
        }
        val inlined = JvmCompiler.inlinedCalls.get()
        assertEquals("one0one1 two0two1 one0one1", go(ExecutionMode.INTERPRETER))
        assertEquals("one0one1 two0two1 one0one1", go(ExecutionMode.ADAPTIVE))
        assertTrue(JvmCompiler.inlinedCalls.get() > inlined, "nothing inlined")
    }

    @Test
    fun closuresAndEnvironments() {
        // the inlined body reads and writes its closure's variables, not the caller's
        same(
            "function counter() { var c = 0; return function (x) { c += x; return c } } var c1 = counter(), c2 = counter();" +
                "function each(n, f) { var s = 0; for (var k = 0; k < n; k++) s = f(k); return s } each(20, c1); each(20, c1); var c = 'caller'; all(each(5, c1), each(5, c2), c)",
        )
        same(
            "let late = 1; function readLate() { return late + 1 } function each(n) { var s = 0; for (var k = 0; k < n; k++) s += readLate(); return s } each(20); each(20); late = 5; all(each(3))",
        )
        same(
            "function caller(n, f) { var s = 0; for (var k = 0; k < n; k++) s += f(); return s } function tdz() { return before + 1 } caller(20, function () { return 1 }); caller(20, function () { return 1 });" +
                "var r; try { caller(3, tdz) } catch (e) { r = e.name } let before = 2; all(r, caller(3, tdz))",
        )
    }

    @Test
    fun typedArraysAndConversions() {
        same(
            "var t = new Float64Array([1.5, -0, NaN, 2]); function at(i) { return t[i & 3] } function sum(n) { var s = 0; for (var k = 0; k < n; k++) s += at(k); return s }" +
                "function ints(n) { var s = 0; for (var k = 0; k < n; k++) s = (s + at(k)) | 0; return s } sum(20); sum(20); ints(20); ints(20); all(sum(7), ints(7), 1 / at(1))",
        )
    }

    @Test
    @Timeout(60, unit = TimeUnit.SECONDS)
    fun limitsHoldInsideInlinedBodies() {
        // the depth limit counts inlined calls: the same recursion fails at the same depth
        val depth = SandboxPolicy.builder().maxCallDepth(120).build()
        same(
            "function down(n) { if (n === 0) return 0; return down(n - 1) + 1 } function probe() { for (var n = 1; ; n++) { try { down(n) } catch (e) { return n + ' ' + e.name } } }" +
                "for (var w = 0; w < 30; w++) down(20); probe()",
            policy = depth,
            threshold = 40,
        )
        // an exception out of an inlined body gives its depth back, whether the caller catches it or it leaves the caller
        same(
            "function inner(x) { if (x < 0) throw new Error('negative'); return x }" +
                "function catches(n) { var s = 0; for (var k = 0; k < n; k++) { try { s += inner(k === 0 ? 1 : -1) } catch (e) { s++ } } return s }" +
                "function leaves(n) { var s = 0; for (var k = 0; k < n; k++) s += inner(k === n - 1 ? -1 : k); return s }" +
                "function down(n) { return n === 0 ? 0 : down(n - 1) + 1 } function probe() { for (var n = 1; ; n++) { try { down(n) } catch (e) { return n } } }" +
                "var before = probe(); catches(20); catches(20); var c = catches(300); for (var w = 0; w < 300; w++) { try { leaves(w < 2 ? 20 : 5) } catch (e) {} } all(before, c, probe())",
            policy = depth,
        )
        // an endless loop in an inlined body still times out
        val p = SandboxPolicy.builder().maxExecutionTime(300).build()
        NeonEngine.builder().executionMode(ExecutionMode.ADAPTIVE).jitThreshold(2).backgroundCompilation(false).sandbox(p).console(null).build().newContext().use { c ->
            assertThrows<NeonTimeoutException> {
                c.eval("function spin(x) { if (x > 1000) { for (;;) {} } return x } function caller(n, v) { var s = 0; for (var k = 0; k < n; k++) s += spin(k === n - 1 ? v : k); return s } caller(20, 1); caller(20, 1); caller(3, 5000)")
            }
        }
    }
}
