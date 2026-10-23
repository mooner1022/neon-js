package dev.mooner.neonjs

import dev.mooner.neonjs.compiler.CodeBlock
import dev.mooner.neonjs.jit.CodeDefiners
import dev.mooner.neonjs.jit.CompiledCode
import dev.mooner.neonjs.jit.Jit
import dev.mooner.neonjs.jit.JvmCompiler
import dev.mooner.neonjs.jit.opt.T2
import dev.mooner.neonjs.vm.Frame
import dev.mooner.neonjs.vm.JSClosure
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * The optimizing tier's code (jit/opt, T2) for functions of every shape it compiles: installed over the baseline, it
 * must give what the interpreter gives, errors and stack traces included.
 */
class T2Test {
    private fun interpreted(src: String, expr: String): String =
        NeonEngine.builder().executionMode(ExecutionMode.INTERPRETER).console(null).build().newContext().use { it.eval(src); it.eval(expr).asString() }

    /** Runs [expr] after [src] with [names] compiled by T2 (each must compile), counting their activations. */
    private fun withT2(src: String, names: List<String>, expr: String): Pair<String, Int> {
        NeonEngine.builder().executionMode(ExecutionMode.ADAPTIVE).jitThreshold(1 shl 30).backgroundCompilation(false).console(null)
            .build().newContext().use { ctx ->
                ctx.eval(src)
                var runs = 0
                for (n in names) {
                    val cb: CodeBlock = (ctx.eval(n).raw as JSClosure).code
                    val base = cb.baseline ?: JvmCompiler.compile(cb)!!.also { cb.baseline = it; cb.compiled = it }
                    val t2 = T2.compile(cb, CodeDefiners.default) ?: fail("$n not compiled by T2: ${T2.failureReasons.keys}")
                    assertNotSame(base, t2)
                    Jit.install(cb, object : CompiledCode {
                        override fun run(f: Frame): Any? {
                            runs++
                            return t2.run(f)
                        }
                    })
                }
                return ctx.eval(expr).asString() to runs
            }
    }

    private fun same(src: String, names: List<String>, expr: String) {
        val expected = interpreted(src, expr)
        val (actual, runs) = withT2(src, names, expr)
        assertEquals(expected, actual, expr)
        assertTrue(runs > 0, "no T2 activation")
    }

    @Test
    fun numbersAndLoops() {
        same(
            """
            function sum(n) { var s = 0; for (var i = 0; i < n; i++) s += i % 7; return s }
            function ints(n) { var h = 0; for (var i = 0; i < n; i++) h = (h * 31 + i) | 0; return h ^ (h >>> 3) }
            function mixed(a, b) { return [a + b, a - b, a * b, a / b, a % b, a ** 2, a < b, a == b, a === b, -a, +b, ~a, !a] }
            function neg0() { var z = 0; return [1 / -z, Object.is(-z, -0), 0 * -1] }
            function bools(n) { var t = true; for (var i = 0; i < n; i++) t = !t; return t ? 'odd' : 'even' }
            function cond(c, a) { return 10 + (c ? a : 2) }
            function nested(n) { var c = 0; for (var i = 0; i < n; i++) for (var j = 0; j < i; j++) if ((i + j) % 3 === 0) c++; else c += 2; return c }
            """.trimIndent(),
            listOf("sum", "ints", "mixed", "neg0", "bools", "cond", "nested"),
            "[sum(1000), ints(500), mixed(7, 2), mixed('7', 2), (() => { try { return mixed(7n, 2n) } catch (e) { return e.name } })(), neg0(), bools(5), cond(1, 5), cond(0, 'x'), nested(40)].join('|')",
        )
    }

    @Test
    fun objectsStringsAndCalls() {
        same(
            """
            function Pt(x, y) { this.x = x; this.y = y }
            function len2(p) { return p.x * p.x + p.y * p.y }
            function build(n) { var a = []; for (var i = 0; i < n; i++) a.push({ k: 'k' + i, v: i * 2 }); return a }
            function total(a) { var t = 0; for (var i = 0; i < a.length; i++) t += a[i].v; return t }
            function str(n) { var s = ''; for (var i = 0; i < n; i++) s += String.fromCharCode(65 + i % 26); return s }
            function elems(n) { var t = new Float64Array(n); for (var i = 0; i < n; i++) t[i] = i * 0.5; var s = 0; for (var i = 0; i < n; i++) s += t[i]; return s }
            function calls(f, n) { var s = 0; for (var i = 0; i < n; i++) s += f(i); return s }
            function closures(n) { var fs = []; for (let i = 0; i < n; i++) fs.push(() => i); return fs.map(f => f()).join(',') }
            function self() { return typeof this + (this === undefined) }
            function types(v) { return [typeof v, v == null, v === undefined, 'x' in { x: 1 }, v instanceof Object] }
            """.trimIndent(),
            listOf("Pt", "len2", "build", "total", "str", "elems", "calls", "closures", "self", "types"),
            "[len2(new Pt(3, 4)), total(build(100)), str(30), elems(64), calls(x => x * 2, 50), closures(5), self(), types(null), types({})].join('|')",
        )
    }

    @Test
    fun stressExitsLeaveForTheInterpreter() {
        // exits at the start and the loop headers (JvmCompiler.DEOPT_STRESS), taken one time in 2 or every time
        val src = """
            function loops(n) { var s = 0, t = '', k = 0.5; for (var i = 0; i < n; i++) { s = (s + i) | 0; k = k * 1.5 % 7; if (i % 2000 === 0) t += i } return [s, t, k.toFixed(3)] }
            function nested(n) { var c = 0; for (var i = 0; i < n; i++) for (var j = 0; j < 30; j++) c += (i ^ j) & 1; return c }
            function cond(c, a) { return 10 + (c ? a : 2) }
            """.trimIndent()
        val expr = "[loops(20000), nested(500), cond(1, 3), cond(0, 3)].join('|')"
        val expected = interpreted(src, expr)
        val before = JvmCompiler.DEOPT_STRESS
        try {
            for (n in intArrayOf(1, 2)) {
                JvmCompiler.DEOPT_STRESS = n
                val exits = dev.mooner.neonjs.jit.Deopt.count.get()
                val (actual, runs) = withT2(src, listOf("loops", "nested", "cond"), expr)
                assertEquals(expected, actual, "every ${n}th exit")
                assertTrue(runs > 0)
                assertTrue(dev.mooner.neonjs.jit.Deopt.count.get() - exits > 3, "exits taken")
            }
        } finally {
            JvmCompiler.DEOPT_STRESS = before
        }
    }

    @Test
    fun inlinedCalls() {
        // static targets: calls of declared functions are compiled inlined, their results unboxed
        val inlined = T2.inlinedCalls.get()
        same(
            """
            function sq(x) { return x * x }
            function sum(n) { var s = 0; for (var i = 0; i < n; i++) s += sq(i); return s }
            function pick(c, a, b) { if (c) return a; return b }
            function choose(n) { var t = 0; for (var i = 0; i < n; i++) t += pick(i & 1, i, -i); return t }
            function inner() { return new Error('x').stack }
            function outer() { return 'trace ' + inner() }
            function thrower(v) { throw new RangeError('no ' + v) }
            function guarded(n) { var c = 0; for (var i = 0; i < n; i++) { try { wrap(i) } catch (e) { c++ } } return c }
            function wrap(v) { return thrower(v) + 1 }
            function looped(n) { var s = 0; for (var j = 0; j < n; j++) s += j; return s }
            function useLoop(n) { return looped(n) * 2 + 1 }
            """.trimIndent(),
            listOf("sum", "choose", "outer", "wrap", "useLoop"),
            "[sum(100), choose(50), outer(), guarded(3000), useLoop(1000)].join('|')",
        )
        assertTrue(T2.inlinedCalls.get() > inlined, "no call compiled inlined")
    }

    @Test
    fun callsInlinedIntoOneAnother() {
        val src = """
            function c3(x) { return new Error('deep ' + x).stack }
            function c2(x) { return 'b' + c3(x + 1) }
            function c1(x) { return 'a' + c2(x + 1) }
            function traced(n) { var r = ''; for (var i = 0; i < n; i++) r = c1(i); return r }
            function t3(v) { if (v % 7 === 3) throw new TypeError('t' + v); return v }
            function t2(v) { return t3(v) * 2 }
            function t1(v) { return t2(v) + 1 }
            function many(n) { var c = 0, s = 0; for (var i = 0; i < n; i++) { try { s += t1(i) } catch (e) { c++ } } return c + ':' + s }
            function n3(x) { return x * 3 }
            function n2(x) { return n3(x) + n3(x + 1) }
            function n1(x) { return n2(x) - n2(x - 1) }
            function sum(n) { var s = 0; for (var i = 0; i < n; i++) s += n1(i); return s }
            """.trimIndent()
        val expr = "[traced(20), many(3000), sum(2000)].join('|')"
        val expected = interpreted(src, expr)
        val before = JvmCompiler.DEOPT_STRESS
        try {
            for (stress in intArrayOf(0, 3)) {
                JvmCompiler.DEOPT_STRESS = stress
                val inlined = T2.inlinedCalls.get()
                val nested = dev.mooner.neonjs.jit.Deopt.inlinedCount.get()
                NeonEngine.builder().executionMode(ExecutionMode.ADAPTIVE).jitThreshold(1 shl 30).backgroundCompilation(false).console(null)
                    .build().newContext().use { ctx ->
                        ctx.eval(src)
                        for (n in listOf("traced", "t1", "sum")) {
                            val cb: CodeBlock = (ctx.eval(n).raw as JSClosure).code
                            cb.baseline ?: JvmCompiler.compile(cb)!!.also { cb.baseline = it; cb.compiled = it }
                            Jit.install(cb, T2.compile(cb, CodeDefiners.default) ?: fail("$n not compiled"))
                        }
                        assertEquals(expected, ctx.eval(expr).asString(), "deopt stress $stress")
                        // exceptions out of calls inlined two deep gave their call depth back
                        assertEquals(0, ctx.agent.depth)
                    }
                assertTrue(T2.inlinedCalls.get() - inlined >= 6, "calls inlined into one another")
                if (stress > 0) assertTrue(dev.mooner.neonjs.jit.Deopt.inlinedCount.get() > nested, "no exit inside inlined calls")
            }
        } finally {
            JvmCompiler.DEOPT_STRESS = before
        }
    }

    @Test
    fun guardsLeaveWhenAnotherFunctionIsCalled() {
        // the interpreter's feedback names sq at apply's call; then cube is called there: the guard exits, the
        // interpreter calls cube, and after enough exits the block goes back to its baseline for good
        val src = """
            function sq(x) { return x * x }
            function cube(x) { return x * x * x }
            function apply(f, x) { return f(x) }
            var o = { k: 3, m: function (x) { return this.k * x } }
            function method(p, x) { return p.m(x) }
            """.trimIndent()
        val warm = "for (var i = 0; i < 50; i++) { apply(sq, i); method(o, i) }"
        val expr = "var r = []; for (var i = 0; i < 200; i++) r.push(apply(i < 50 ? sq : cube, i), method(i < 60 ? o : { k: 5, m: function (x) { return -x } }, i)); r.join(',')"
        val expected = interpreted(src, "$warm; $expr")
        val reverted = T2.reverted.get()
        NeonEngine.builder().executionMode(ExecutionMode.ADAPTIVE).jitThreshold(1 shl 30).backgroundCompilation(false).console(null)
            .build().newContext().use { ctx ->
                ctx.eval(src)
                ctx.eval(warm)
                for (n in listOf("apply", "method")) {
                    val cb: CodeBlock = (ctx.eval(n).raw as JSClosure).code
                    cb.baseline ?: JvmCompiler.compile(cb)!!.also { cb.baseline = it; cb.compiled = it }
                    val inlined = T2.inlinedCalls.get()
                    Jit.install(cb, T2.compile(cb, CodeDefiners.default) ?: fail("$n not compiled"))
                    assertTrue(T2.inlinedCalls.get() > inlined, "$n: no call compiled inlined")
                }
                assertEquals(expected, ctx.eval(expr).asString())
            }
        assertTrue(T2.reverted.get() > reverted, "no block went back to its baseline")
    }

    @Test
    fun errorsAndTdz() {
        same(
            """
            function thrower(v) { if (v > 2) throw new RangeError('big ' + v); return v }
            function tdz() { let a = b + 1; let b = 2; return a }
            function nullProp(o) { return o.x.y }
            function notFn(o) { return o.f() }
            function deep(n) { return n === 0 ? new Error('d').stack : deep(n - 1) }
            function strictAssign() { 'use strict'; var o = Object.freeze({ a: 1 }); o.a = 2 }
            function catcher(f) { try { return f() } catch (e) { return e.name + ': ' + e.message + ' @' + e.stack.split('\n').length } }
            """.trimIndent(),
            listOf("thrower", "tdz", "nullProp", "notFn", "deep", "strictAssign"),
            "[catcher(() => thrower(1)), catcher(() => thrower(5)), catcher(tdz), catcher(() => nullProp({})), catcher(() => notFn({}))," +
                " deep(3), catcher(strictAssign)].join('|')",
        )
    }
}
