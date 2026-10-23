package dev.mooner.neonjs

import dev.mooner.neonjs.jit.Jit
import dev.mooner.neonjs.jit.JvmCompiler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * On-stack replacement (Jit.osr): a loop of interpreted code continues in its block's compiled code. Each case runs
 * interpreted and in adaptive mode with a threshold of 2 and no background compilation: a function's first call is
 * interpreted, and the first loop check of a loop (Jit.LOOP_CHECK iterations) compiles its block and enters the code at
 * the loop header. Both must give the same
 * result, stack traces included, and some loop must have been entered.
 */
class OsrTest {
    private val prelude = """
        function show(v) {
          if (typeof v === 'number') return Object.is(v, -0) ? '-0' : String(v);
          if (typeof v === 'string') return JSON.stringify(v);
          if (Array.isArray(v)) return '[' + v.map(show).join(',') + ']';
          return String(v);
        }
        function all() { return Array.prototype.map.call(arguments, show).join(' '); }
        var N = 5000;
    """.trimIndent() + "\n"

    private fun run(code: String, mode: ExecutionMode, policy: SandboxPolicy? = null): String {
        val b = NeonEngine.builder().executionMode(mode).console(null)
        if (mode == ExecutionMode.ADAPTIVE) b.jitThreshold(2).backgroundCompilation(false)
        if (policy != null) b.sandbox(policy)
        return b.build().newContext().use { it.eval(prelude + code).asString() }
    }

    private fun same(code: String, expected: String? = null) {
        val entered = Jit.osrEntered.get()
        val failed = JvmCompiler.failedCount.get()
        val interpreted = run(code, ExecutionMode.INTERPRETER)
        if (expected != null) assertEquals(expected, interpreted, "interpreter: $code")
        assertEquals(interpreted, run(code, ExecutionMode.ADAPTIVE), "adaptive: $code")
        assertTrue(Jit.osrEntered.get() > entered, "no loop entered: $code")
        assertEquals(failed, JvmCompiler.failedCount.get(), "not compiled (${JvmCompiler.failureReasons.keys}): $code")
    }

    @Test
    fun registersOfEveryKind() {
        // doubles, ints, values of other kinds and still-undefined registers at the loop header
        same(
            "function f(n) { var s = 0, x = 0.5, k = 0, b = 'x', c = null, d, e = { z: 1 }, g = true, u;" +
                " for (var i = 0; i < n; i++) { s += i * 2; x = x * 1.0001 + 0.25; k = (k + i) | 0; if (i > n - 4) b = b + i; }" +
                " u = k ^ 3; return all(s, x.toFixed(4), k, b, c, d, e.z, g, u) } f(N)",
        )
        // a number that turns into a string, and an int register only assigned after the loop
        same("function f(n) { var x = 0; for (var i = 0; i < n; i++) x = x + (i === n - 2 ? 'a' : 1); var late = i | 0; return all(x, late) } f(N)")
        // nested loops: the inner one is entered, then the outer one runs in the same compiled code
        same("function f(n) { var s = 0; for (var i = 0; i < 3; i++) for (var j = 0; j < n; j++) s += i ^ j; return s } String(f(N))")
        // let bindings (TDZ registers) and closures capturing the loop variable (environment slots)
        same("function f(n) { const fs = []; for (let i = 0; i < n; i++) { if (i % 1000 === 0) fs.push(() => i) } let after = fs.length; return all(after, fs.map(g => g())) } f(N)")
    }

    @Test
    fun loopForms() {
        same(
            "function w(n) { var i = 0, s = 0; while (i < n) { s += i; i++ } return s }" +
                "function dw(n) { var i = 0; do { i += 2 } while (i < n); return i }" +
                "function forOf(a) { var s = 0; for (const v of a) s += v; return s }" +
                "function forIn(o) { var s = ''; for (const k in o) s += k.length; return s.length }" +
                "function labeled(n) { var s = 0; outer: for (var i = 0; i < n; i++) { for (var j = 0; j < 3; j++) { if (j === 1) continue outer; if (i === n - 1) break outer; s++ } } return s }" +
                "function bare(o) { for (;;) { if (o.n-- <= 0) return o.n } }" +
                "function sw(n) { var r = 0; for (var i = 0; i < n; i++) { switch (i % 3) { case 0: r += 1; break; case 1: r += 10; continue; default: try { r += 100; break } finally { r += 1000 } } } return r }" +
                "var big = {}; for (var q = 0; q < N; q++) big['k' + q] = q;" +
                "all(w(N), dw(N), forOf(Array.from({ length: N }, (_, i) => i)), forIn(big), labeled(N), bare({ n: N }), sw(N))",
        )
        // with, arguments, strict code
        same(
            "function withLoop(o, n) { var c = 0; with (o) { do { c += a } while (c < n * a) } return c }" +
                "function args() { var s = 0, i = 0; while (i < arguments.length * 2000) { s += arguments[i % arguments.length]; i++ } return s }" +
                "function strict(n) { 'use strict'; let s = 0; for (let i = 0; i < n; i++) s += i; return Math.max(s, 0) }" +
                "all(withLoop({ a: 3 }, N), args(1, 2, 3), strict(N))",
        )
    }

    @Test
    fun topLevelLoops() {
        same("var s = 0; for (var i = 0; i < N; i++) s += i; let t = ''; let j = 0; while (j < N) { if (j % 1000 === 0) t += j; j++ } all(s, t)")
    }

    @Test
    fun exceptions() {
        // handlers around and inside the loop run once, in the compiled code
        same(
            "function f(n) { var r = 0, fin = 0; try { for (var i = 0; i < n; i++) { try { if (i % 7 === 3) throw i; r += i } catch (e) { r -= e } finally { fin++ } } } finally { fin += 1000 } return all(r, fin) } f(N)",
        )
        // a getter throwing inside the loop, caught outside it
        same("function f(n) { const o = { get x() { throw new RangeError('g') } }; var c = 0; for (var i = 0; i < n; i++) { try { o.x } catch (e) { c++ } } return c } String(f(N))")
        // an exception leaving the frame: its stack names the line in the loop
        same(
            "function thrower(n) { for (var i = 0; i < n; i++) {\n if (i === n - 1) null.x } }\n" +
                "function caller() { try { thrower(N) } catch (e) { return e.name + ' ' + e.stack.split('\\n').slice(0, 3).join('|') } } caller()",
        )
        // a throw out of a finally inside the loop, after the OSR
        same("function f(n) { var c = 0; try { for (var i = 0; i < n; i++) { try { c++ } finally { if (i === n - 1) throw new Error('last ' + c) } } } catch (e) { return e.message } } f(N)")
    }

    @Test
    fun recursionAndGenerators() {
        // a recursive call made while the loop runs compiled; generator loops stay interpreted
        same(
            "function rec(d, n) { var s = 0; for (var i = 0; i < n; i++) s += d > 0 && i % 2500 === 0 ? rec(d - 1, n) : 1; return s }" +
                "function* gen(n) { for (var i = 0; i < n; i++) yield i } function g(n) { var s = 0; for (const v of gen(n)) s += v; return s }" +
                "all(rec(2, N), g(N))",
        )
    }

    @Test
    fun endlessLoopsTimeOut() {
        val p = SandboxPolicy.builder().maxExecutionTime(300).build()
        for (code in listOf("function spin() { var i = 0; while (true) i++ } spin()", "var i = 0; for (;;) i += 0.5")) {
            val entered = Jit.osrEntered.get()
            NeonEngine.builder().executionMode(ExecutionMode.ADAPTIVE).jitThreshold(2).backgroundCompilation(false).sandbox(p).console(null).build().newContext().use { c ->
                assertThrows<NeonTimeoutException> { c.eval(code) }
            }
            assertTrue(Jit.osrEntered.get() > entered, "no loop entered: $code")
        }
    }

    @Test
    fun interpreterModeNeverEnters() {
        val entered = Jit.osrEntered.get()
        run("function f(n) { var s = 0; for (var i = 0; i < n; i++) s += i; return s } String(f(N * 4))", ExecutionMode.INTERPRETER)
        assertEquals(entered, Jit.osrEntered.get())
    }
}
