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
