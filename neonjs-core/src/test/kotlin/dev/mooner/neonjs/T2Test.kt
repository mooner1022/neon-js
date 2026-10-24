package dev.mooner.neonjs

import dev.mooner.neonjs.compiler.CodeBlock
import dev.mooner.neonjs.jit.CodeDefiners
import dev.mooner.neonjs.jit.CompiledCode
import dev.mooner.neonjs.jit.Jit
import dev.mooner.neonjs.jit.JvmCompiler
import dev.mooner.neonjs.jit.opt.IrBuilder
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
    fun speculationOnProfilesAndShapes() {
        // the interpreter fills the value profiles (tier2) and the property caches; T2 code then reads numbers and
        // slots directly, and leaves when a value, a receiver's shape or a prototype's shape is another
        val src = """
            function Vec(x, y) { this.x = x; this.y = y }
            Vec.prototype.k = function () { return this.x + 1 }
            function len2(v) { return v.x * v.x + v.y * v.y }
            function sumLen(a) { var s = 0; for (var i = 0; i < a.length; i++) s += len2(a[i]); return s }
            function viaProto(o) { return o.k() }
            function setX(v, x) { v.x = x; return v.x * 2 }
            function half(n) { return n / 2 }
            var vs = []; for (var i = 0; i < 20; i++) vs.push(new Vec(i, i + 0.5));
            """.trimIndent()
        val warm = "for (var r = 0; r < 30; r++) { sumLen(vs); viaProto(vs[r % 20]); setX(new Vec(1, 2), r); half(r) }"
        val expr = "[sumLen(vs), sumLen([{ x: 1, y: 2 }, new Vec('3', 4)]), viaProto(vs[3]), (Vec.prototype.extra = 1, viaProto(vs[4]))," +
            " viaProto({ k: () => 'own' }), setX(new Vec(5, 6), 7), setX({ x: 1 }, 2), setX(new Vec(1, 2), 'str'), half(9), half('8'), half(undefined)].join('|')"
        val expected = interpreted(src, "$warm; $expr")
        val exits = dev.mooner.neonjs.jit.Deopt.count.get()
        NeonEngine.builder().executionMode(ExecutionMode.ADAPTIVE).jitThreshold(1 shl 30).backgroundCompilation(false).console(null)
            .build().newContext().use { ctx ->
                ctx.agent.config.tier2 = true
                ctx.eval(src)
                ctx.eval(warm)
                for (n in listOf("len2", "sumLen", "viaProto", "setX", "half")) {
                    val cb: CodeBlock = (ctx.eval(n).raw as JSClosure).code
                    assertNotNull(cb.profile, "$n has no profile")
                    cb.baseline ?: JvmCompiler.compile(cb)!!.also { cb.baseline = it; cb.compiled = it }
                    val t2 = T2.compile(cb, CodeDefiners.default) ?: fail("$n not compiled: ${T2.failureReasons.keys}")
                    Jit.install(cb, t2)
                }
                assertEquals(expected, ctx.eval(expr).asString())
            }
        assertTrue(dev.mooner.neonjs.jit.Deopt.count.get() - exits >= 5, "speculations that failed exited")
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
    fun exceptionHandlersAndIteration() {
        val src = """
            function boom(v) { if (v % 5 === 2) throw new RangeError('boom ' + v); return v * 2 }
            function caught(n) { var s = 0, c = 0, last = ''; for (var i = 0; i < n; i++) { try { s += boom(i) } catch (e) { c++; last = e.message } } return [s, c, last] }
            function fin(n) { var log = []; for (var i = 0; i < n; i++) { try { if (i === 1) continue; if (i === 3) break; log.push('t' + i) } finally { log.push('f' + i) } } return log.join() }
            function ret(x) { try { if (x) return 'early'; } finally { ret.count = (ret.count | 0) + 1 } return 'late' }
            function nested(n) { var out = []; for (var i = 0; i < n; i++) { try { try { if (i & 1) throw i; out.push('a' + i) } catch (e) { out.push('c' + e); if (e === 3) throw 'up' } } catch (e) { out.push('o' + e) } } return out.join() }
            function ofs(a) { var s = 0; for (const x of a) { if (x === 7) break; s += x } return s }
            function ofSet(n) { const st = new Set(); for (var i = 0; i < n; i++) st.add(i % 7); var t = 0; for (const v of st) t += v; return t }
            function ins(o) { var ks = []; for (const k in o) ks.push(k + o[k]); return ks.join() }
            function destr(p) { const [a, b, ...rest] = p; const { x, y = 9 } = { x: a }; return [a, b, rest.length, x, y].join() }
            function hot(n) { var s = 0.5; try { for (var i = 0; i < n; i++) s = s * 1.0001 + i % 3 } catch (e) { s = -1 } return s.toFixed(3) }
            """.trimIndent()
        val names = listOf("caught", "fin", "ret", "nested", "ofs", "ofSet", "ins", "destr", "hot")
        val expr = "[caught(40), fin(6), ret(1), ret(0), ret.count, nested(6), ofs([1, 2, 3, 7, 9]), ofs([4, 5]), ofSet(30)," +
            " ins({ a: 1, b: 2 }), destr([1, 2, 3, 4]), hot(5000)].join('|')"
        val before = JvmCompiler.DEOPT_STRESS
        try {
            for (stress in intArrayOf(0, 2)) {
                JvmCompiler.DEOPT_STRESS = stress
                same(src, names, expr)
            }
        } finally {
            JvmCompiler.DEOPT_STRESS = before
        }
    }

    @Test
    fun runningLoopsMoveToTheTiersCode() {
        // T1 code with the tier on checks its block's code at loop back edges: once T2 code is installed (here by the
        // loop itself, through a host function), the running loop goes on in it (Deopt.tierUp)
        val src = """
            function f(n) { var s = 0, t = 0.5, str = ''; for (var i = 0; i < n; i++) { s = (s + i % 7) | 0; t = t * 1.0001 + 1; if (i === 5000) install('f'); if (i % 9000 === 1) str += i } return [s, t.toFixed(2), str].join(' ') }
            function g(n) { var s = 0; for (var i = 0; i < n; i++) { for (var j = 0; j < 3; j++) s += j; if (i === 7000) install('g') } return s }
            """.trimIndent()
        val expr = "[f(40000), g(30000)].join('|')"
        val expected = interpreted(src.replace("install('f')", "0").replace("install('g')", "0"), expr)
        val tierUps = dev.mooner.neonjs.jit.Deopt.tierUps.get()
        NeonEngine.builder().executionMode(ExecutionMode.COMPILED).backgroundCompilation(false).console(null).build().newContext().use { ctx ->
            ctx.agent.config.tier2 = true
            ctx.setFunction("install") { args ->
                // the block of the function running the loop
                val n = args[0].asString()
                val cb: CodeBlock = (ctx.eval(n).raw as JSClosure).code
                Jit.install(cb, T2.compile(cb, CodeDefiners.default) ?: fail("$n not compiled"))
                null
            }
            ctx.eval(src)
            // compiled mode: T1 code before the first call; the tier's own compilation (on this thread) is undone first,
            // so that the loops start in T1 code
            for (n in listOf("f", "g")) {
                val cb: CodeBlock = (ctx.eval(n).raw as JSClosure).code
                cb.baseline ?: JvmCompiler.compileAll(listOf(cb), CodeDefiners.default, tierUp = true)[0]!!.also { cb.baseline = it; cb.compiled = it }
                cb.compiled = cb.baseline
            }
            assertEquals(expected, ctx.eval(expr).asString())
        }
        assertTrue(dev.mooner.neonjs.jit.Deopt.tierUps.get() - tierUps >= 2, "loops moved: ${dev.mooner.neonjs.jit.Deopt.tierUps.get() - tierUps}")
    }

    @Test
    fun failedSpeculationsAreNotMadeAgain() {
        // a callback site the feedback saw call one function, then called with another: the guard exits until the code
        // gives way to the baseline and is compiled again without inlining there (CodeBlock.t2Failed)
        val src = """
            function repeatf(n, block) { var s = 0; for (var i = 0; i < n; i++) s += block(i); return s }
            function dbl(x) { return x * 2 }
            function inc(x) { return x + 1 }
            """.trimIndent()
        val warm = "for (var w = 0; w < 30; w++) repeatf(20, dbl)"
        val expr = "var t = 0; for (var k = 0; k < 200; k++) t += repeatf(10, k % 2 ? dbl : inc); String(t)"
        val expected = interpreted(src, "$warm; $expr")
        NeonEngine.builder().executionMode(ExecutionMode.ADAPTIVE).jitThreshold(1 shl 30).backgroundCompilation(false).console(null)
            .build().newContext().use { ctx ->
                ctx.agent.config.tier2 = true
                ctx.eval(src)
                ctx.eval(warm)
                val cb: CodeBlock = (ctx.eval("repeatf").raw as JSClosure).code
                cb.baseline ?: JvmCompiler.compile(cb)!!.also { cb.baseline = it; cb.compiled = it }
                val inlined = T2.inlinedCalls.get()
                Jit.install(cb, T2.compile(cb, CodeDefiners.default) ?: fail("not compiled"))
                assertTrue(T2.inlinedCalls.get() > inlined, "the callback was not inlined")
                val exits = dev.mooner.neonjs.jit.Deopt.count.get()
                assertEquals(expected, ctx.eval(expr).asString())
                assertEquals(1, cb.t2Recompiles, "compiled again once")
                assertTrue(cb.compiled is dev.mooner.neonjs.jit.opt.T2Code, "the tier's code runs")
                assertTrue(cb.t2FailedAt(pcOfCall(cb)), "the call's speculation is noted")
                // the second version makes no exit there: the exits stop at the limit
                assertTrue(dev.mooner.neonjs.jit.Deopt.count.get() - exits <= dev.mooner.neonjs.jit.opt.T2Code.DEOPT_LIMIT + 2)
            }
    }

    private fun pcOfCall(cb: CodeBlock): Int {
        var pc = 0
        while (cb.code[pc] != dev.mooner.neonjs.compiler.Op.CALL) pc += dev.mooner.neonjs.compiler.Op.length(cb.code, pc)
        return pc
    }

    @Test
    fun objectLiteralsOnlyReadAreNotAllocated() {
        val src = """
            function lit(n) { var r = 0; for (var i = 0; i < n; i++) { var p = { x: i, y: i * 2 }; r += p.x + p.y } return r }
            function pt(i) { return { x: i, y: -i } }
            function viaCall(n) { var r = 0; for (var i = 0; i < n; i++) r += pt(i).x; return r }
            function escapes(n) { var a = []; for (var i = 0; i < n; i++) { var p = { x: i }; if (i % 100 === 0) a.push(p) } return a.length + ':' + a[3].x }
            function nested(n) { var s = ''; for (var i = 0; i < n; i++) { var o = { k: 'a' + i, v: { w: i } }; s = o.k + o.v.w } return s }
            function absent(n) { var t = 0; for (var i = 0; i < n; i++) { var o = { a: i }; t += o.toString === Object.prototype.toString ? 1 : 0 } return t }
            """.trimIndent()
        val names = listOf("lit", "viaCall", "escapes", "nested", "absent")
        val expr = "[lit(3000), viaCall(3000), escapes(1000), nested(500), absent(100)].join('|')"
        val g = IrBuilder(dev.mooner.neonjs.compiler.Compiler.compileScript(
            dev.mooner.neonjs.parser.Parser.parse(src, dev.mooner.neonjs.parser.ParseOptions(sourceName = "t.js")),
            dev.mooner.neonjs.compiler.Source("t.js", src)).constants.filterIsInstance<CodeBlock>().first { it.name == "lit" },
            ints = true, elems = true).build()
        assertTrue(dev.mooner.neonjs.jit.opt.IrPrinter.print(g).contains("virtual"), dev.mooner.neonjs.jit.opt.IrPrinter.print(g))
        val before = JvmCompiler.DEOPT_STRESS
        try {
            for (stress in intArrayOf(0, 2)) {
                JvmCompiler.DEOPT_STRESS = stress
                same(src, names, expr)
            }
        } finally {
            JvmCompiler.DEOPT_STRESS = before
        }
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

    @Test
    fun globalsAreReadThroughTheirCaches() {
        // the caches are filled before T2 compiles: a global object's property (its shape and the lexical epoch
        // checked), a lexical binding (the realm's token, then its value: TDZ); later a lexical declaration shadows a
        // property, a property is added and one deleted
        val src = """
            var gv = 1; let lex = 10; globalThis.shadowed = 'property';
            function pi(n) { let s = 0; for (let i = 0; i < n; i++) s += Math.PI; return s }
            function both() { return gv + lex }
            function late() { return shadowed }
            pi(3); both(); late();
            """.trimIndent()
        val expr = "var r = [pi(4), both()]; gv = 2; lex = 20; r.push(both()); globalThis.added = 1; r.push(both(), pi(2));" +
            " try { late() } catch (e) { r.push(e.name) } let shadowed = 'lexical'; r.push(late()); delete globalThis.Math;" +
            " try { pi(1) } catch (e) { r.push(e.name) } r.join()"
        for (stress in intArrayOf(0, 2)) {
            JvmCompiler.DEOPT_STRESS = stress
            try {
                same(src, listOf("pi", "both", "late"), expr)
            } finally {
                JvmCompiler.DEOPT_STRESS = 0
            }
        }
        assertTrue(irOf(src, "pi").contains("LoadGlobalSlot"), irOf(src, "pi"))
        assertTrue(irOf(src, "both").contains("LoadGlobalLex"), irOf(src, "both"))
    }

    @Test
    fun numbersOnlyPassedOnAreNotUnboxed() {
        // a value whose profile saw numbers gets a check only where something takes it unboxed: returned, passed to a
        // call or stored, it stays the Object it came as (no new box for it)
        val src = """
            function pass(o) { return o.x }
            function store(o, p) { p.y = o.x; return p.y }
            function arg(o) { return String(o.x) }
            function used(o) { return o.x * 2 }
            function both(o) { var v = o.x; return [v, v + 1] }
            """.trimIndent()
        val names = listOf("pass", "store", "arg", "used", "both")
        val warm = "for (var w = 0; w < 30; w++) { pass({ x: w }); store({ x: w }, { y: 0 }); arg({ x: w }); used({ x: w }); both({ x: w }) }"
        val expr = "[pass({ x: 1.5 }), pass({ x: 'str' }), store({ x: 2 }, { y: 0 }), arg({ x: 3 }), used({ x: 4 }), used({ x: 'u' })," +
            " both({ x: 5 }), both({ x: null })].join('|')"
        val expected = interpreted(src, "$warm; $expr")
        NeonEngine.builder().executionMode(ExecutionMode.ADAPTIVE).jitThreshold(1 shl 30).backgroundCompilation(false).console(null)
            .build().newContext().use { ctx ->
                ctx.agent.config.tier2 = true
                ctx.eval(src)
                ctx.eval(warm)
                for (n in names) {
                    val cb: CodeBlock = (ctx.eval(n).raw as JSClosure).code
                    val ir = dev.mooner.neonjs.jit.opt.IrPrinter.print(IrBuilder(cb, ints = true, elems = true).build())
                    assertEquals(n in listOf("used", "both"), ir.contains("CheckInt32") || ir.contains("CheckNumber"), "$n: $ir")
                    cb.baseline ?: JvmCompiler.compile(cb)!!.also { cb.baseline = it; cb.compiled = it }
                    Jit.install(cb, T2.compile(cb, CodeDefiners.default) ?: fail("$n not compiled"))
                }
                assertEquals(expected, ctx.eval(expr).asString())
            }
    }

    @Test
    fun objectsWithoutPropertyMapsPassShapeChecks() {
        // a new array has no property map: its shape is its prototype's empty one, as the caches compare, so a method
        // read through it (push on Array.prototype) does not exit
        val src = "function fill(n) { var a = []; for (var i = 0; i < n; i++) a.push(i); return a.length }"
        NeonEngine.builder().executionMode(ExecutionMode.ADAPTIVE).jitThreshold(1 shl 30).backgroundCompilation(false).console(null)
            .build().newContext().use { ctx ->
                ctx.agent.config.tier2 = true
                ctx.eval(src)
                ctx.eval("for (var w = 0; w < 30; w++) fill(10)")
                val cb: CodeBlock = (ctx.eval("fill").raw as JSClosure).code
                val ir = dev.mooner.neonjs.jit.opt.IrPrinter.print(IrBuilder(cb, ints = true, elems = true).build())
                assertTrue(ir.contains("LoadSlot"), ir)
                cb.baseline ?: JvmCompiler.compile(cb)!!.also { cb.baseline = it; cb.compiled = it }
                Jit.install(cb, T2.compile(cb, CodeDefiners.default) ?: fail("not compiled"))
                val exits = dev.mooner.neonjs.jit.Deopt.count.get()
                assertEquals("100,0", ctx.eval("[fill(100), fill(0)].join()").asString())
                assertEquals(exits, dev.mooner.neonjs.jit.Deopt.count.get(), "exits")
            }
    }

    @Test
    fun int32ArithmeticExitsWhereItLeavesTheRange() {
        // arithmetic of values the profiles saw as int32s stays on ints, exiting before the instruction where the
        // result is not an int32 or is -0; an ADD or `>>>` whose uses are all bitwise wraps around instead
        val src = """
            function sum(n) { var s = 0; for (var i = 0; i < n; i++) s += i; return s }
            function prod(a, b) { return a * b }
            function rem(a, b) { return a % b }
            function neg(a) { return -a }
            function dec(a) { return a - 1 }
            function inc(a) { var x = a; x++; return x }
            function hash(n) { var h = 0; for (var i = 0; i < n; i++) h = (h * 31 + i) | 0; return h }
            function bits(n) { var s = 0; for (var i = 0; i < n; i++) s = (s + ((i * i) ^ (i >>> 3))) | 0; return s }
            function shr(a) { return (a >>> 0) ^ 1 }
            function wrap(a, b) { return (a + b) | 0 }
            var z = v => Object.is(v, -0) ? '-0' : String(v);
            """.trimIndent()
        val warm = "for (var w = 0; w < 30; w++) { sum(10); prod(w, 3); rem(w, 7); neg(w + 1); dec(w); inc(w); hash(5); bits(5); shr(w); wrap(w, 2) }"
        val expr = "[sum(100), sum(100000), prod(6, 7), prod(0, -5), prod(-3, 0), prod(65536, 65536), prod(-2147483648, -1), rem(17, 5)," +
            " rem(5, 0), rem(-4, 2), rem(-2147483648, -1), rem(-7, 3), neg(5), neg(0), neg(-2147483648), dec(0), dec(-2147483648)," +
            " inc(41), inc(2147483647), hash(1000), bits(1000), shr(-1), shr(5), wrap(2147483647, 1), wrap(-2147483648, -1)].map(z).join('|')"
        val expected = interpreted(src, "$warm; $expr")
        for (stress in intArrayOf(0, 2)) {
            JvmCompiler.DEOPT_STRESS = stress
            try {
                NeonEngine.builder().executionMode(ExecutionMode.ADAPTIVE).jitThreshold(1 shl 30).backgroundCompilation(false).console(null)
                    .build().newContext().use { ctx ->
                        ctx.agent.config.tier2 = true
                        ctx.eval(src)
                        ctx.eval(warm)
                        for (n in listOf("sum", "prod", "rem", "neg", "dec", "inc", "hash", "bits", "shr", "wrap")) {
                            val cb: CodeBlock = (ctx.eval(n).raw as JSClosure).code
                            if (stress == 0 && n in listOf("bits", "wrap")) {
                                val ir = dev.mooner.neonjs.jit.opt.IrPrinter.print(IrBuilder(cb, ints = true, elems = true).build())
                                assertTrue(ir.contains("wraps"), ir)
                            }
                            cb.baseline ?: JvmCompiler.compile(cb)!!.also { cb.baseline = it; cb.compiled = it }
                            Jit.install(cb, T2.compile(cb, CodeDefiners.default) ?: fail("$n not compiled"))
                        }
                        assertEquals(expected, ctx.eval(expr).asString())
                    }
            } finally {
                JvmCompiler.DEOPT_STRESS = 0
            }
        }
    }

    @Test
    fun invariantGuardsAndLoadsLeaveLoops() {
        // LICM: the callback's guard and the loads of Math.PI move before repeatf's loop; a load stays where the loop
        // stores into its slot, or calls a function, or does not read it on every iteration; their exits resume at the
        // loop header (a callback of another function, none at all on a loop run zero times)
        val src = """
            function radius(r) { return 0.5 * r * r * Math.PI }
            function repeatf(n, block) { var s = 0; for (var i = 0; i < n; i++) s += block(i); return s }
            function writes(o, n) { var s = 0; for (var i = 0; i < n; i++) { s += o.x; o.x = i } return s }
            var K = { v: 1 };
            function bump() { K = { v: K.v + 1 } }
            function calls(n) { var s = 0; for (var i = 0; i < n; i++) { s += K.v; if (i === 5) bump() } return s }
            function sometimes(o, n) { var s = 0; for (var i = 0; i < n; i++) if (o) s += o.p; return s }
            class P { constructor(x) { this.x = x } twice() { return this.x * 2 } }
            function viaMethod(p, n) { var s = 0; for (var i = 0; i < n; i++) s += p.twice(); return s }
            Number.prototype.self = function () { return this };
            function wrappers(n) { var a = []; for (var i = 0; i < n; i++) a.push((5).self()); return a[0] === a[1] }
            """.trimIndent()
        val warm = "for (var w = 0; w < 30; w++) { repeatf(20, radius); writes({ x: 1 }, 5); calls(3); sometimes({ p: 2 }, 5); viaMethod(new P(w), 5); wrappers(2) }"
        // a strict method's `this` moves with the loads through it; a sloppy one's (a new wrapper each call) does not
        val expr = "[repeatf(100, radius), repeatf(0, 5), repeatf(3, x => x), writes({ x: 1 }, 10), calls(10), sometimes(null, 5)," +
            " sometimes({ p: 3 }, 4), viaMethod(new P(4), 10), viaMethod({ twice: () => 1 }, 3), wrappers(3), (Math.PI = 3, repeatf(4, radius))].join('|')"
        val expected = interpreted(src, "$warm; $expr")
        for (stress in intArrayOf(0, 2)) {
            JvmCompiler.DEOPT_STRESS = stress
            try {
                NeonEngine.builder().executionMode(ExecutionMode.ADAPTIVE).jitThreshold(1 shl 30).backgroundCompilation(false).console(null)
                    .build().newContext().use { ctx ->
                        ctx.agent.config.tier2 = true
                        ctx.eval(src)
                        ctx.eval(warm)
                        val moved = HashMap<String, List<String>>()
                        for (n in listOf("repeatf", "writes", "calls", "sometimes", "viaMethod", "wrappers")) {
                            val cb: CodeBlock = (ctx.eval(n).raw as JSClosure).code
                            val g = IrBuilder(cb, ints = true, elems = true).build()
                            dev.mooner.neonjs.jit.opt.IrVerifier.verify(g)
                            moved[n] = g.blocks.mapNotNull { it.loopEntry }.flatMap { e -> e.insts.map { dev.mooner.neonjs.jit.opt.Ir.name(it.op) } }
                            cb.baseline ?: JvmCompiler.compile(cb)!!.also { cb.baseline = it; cb.compiled = it }
                            Jit.install(cb, T2.compile(cb, CodeDefiners.default) ?: fail("$n not compiled"))
                        }
                        if (stress == 0) {
                            assertTrue(moved["repeatf"]!!.containsAll(listOf("CheckClosure", "LoadGlobalSlot", "LoadSlot", "CheckNumber")), "$moved")
                            assertFalse(moved["writes"]!!.contains("LoadSlot"), "$moved")
                            assertFalse(moved["calls"]!!.contains("LoadGlobalLex"), "$moved")
                            assertFalse(moved["sometimes"]!!.contains("LoadSlot"), "$moved")
                            assertTrue(moved["viaMethod"]!!.containsAll(listOf("InlineThis", "LoadSlot")), "$moved")
                            assertFalse(moved["wrappers"]!!.contains("InlineThis"), "$moved")
                        }
                        assertEquals(expected, ctx.eval(expr).asString())
                    }
            } finally {
                JvmCompiler.DEOPT_STRESS = 0
            }
        }
    }

    @Test
    fun runningLoopsEnterCodeWithInvariantsMovedOut() {
        // on-stack replacement at a header whose invariants LICM moved out (the inner loops: no call there) makes them
        // again from the registers; a guard failing there (the global object changed) resumes the loop in the interpreter
        val src = """
            function f(n) { var s = 0; for (var k = 0; k < 2; k++) { if (k === 1) install('f'); for (var i = 0; i < n; i++) s += Math.PI * (i % 3) } return s.toFixed(3) }
            function g(n, tag) { var s = 0; for (var k = 0; k < 2; k++) { if (k === 1) { install('g'); globalThis['extra' + tag] = k } for (var i = 0; i < n; i++) s += Math.E } return s.toFixed(3) }
            """.trimIndent()
        // the interpreter fills the caches and profiles first
        val warm = "f(100); g(100, 1)"
        val expr = "[f(20000), g(20000, 2)].join('|')"
        val expected = interpreted("function install() {}\n$src", "$warm; $expr")
        val tierUps = dev.mooner.neonjs.jit.Deopt.tierUps.get()
        var armed = false
        NeonEngine.builder().executionMode(ExecutionMode.ADAPTIVE).jitThreshold(1 shl 30).backgroundCompilation(false).console(null)
            .build().newContext().use { ctx ->
                ctx.agent.config.tier2 = true
                ctx.setFunction("install") { args ->
                    if (armed) {
                        val cb: CodeBlock = (ctx.eval(args[0].asString()).raw as JSClosure).code
                        val g = IrBuilder(cb, ints = true, elems = true).build()
                        assertTrue(g.blocks.any { it.loopEntry != null }, dev.mooner.neonjs.jit.opt.IrPrinter.print(g))
                        Jit.install(cb, T2.compile(cb, CodeDefiners.default) ?: fail("not compiled"))
                    }
                    null
                }
                ctx.eval(src)
                ctx.eval(warm)
                armed = true
                // the loops start in T1 code that looks for the tier's code at its back edges
                for (n in listOf("f", "g")) {
                    val cb: CodeBlock = (ctx.eval(n).raw as JSClosure).code
                    cb.baseline ?: JvmCompiler.compileAll(listOf(cb), CodeDefiners.default, tierUp = true)[0]!!.also { cb.baseline = it; cb.compiled = it }
                    cb.compiled = cb.baseline
                }
                assertEquals(expected, ctx.eval(expr).asString())
            }
        assertTrue(dev.mooner.neonjs.jit.Deopt.tierUps.get() - tierUps >= 2, "loops moved: ${dev.mooner.neonjs.jit.Deopt.tierUps.get() - tierUps}")
    }

    @Test
    fun quietCallsAreNotRecorded() {
        // bodies that cannot throw, call, loop or catch are entered without the frame's record of inlined calls; their
        // exits still give the call a frame, so an error thrown there after an exit has the callee in its stack
        val src = """
            function sq(x) { return x * x }
            function px(o) { return o.p.q }
            function sumSq(n) { var s = 0; for (var i = 0; i < n; i++) s += sq(i); return s }
            function readAll(a) { var s = 0; for (var i = 0; i < a.length; i++) s += px(a[i]); return s }
            function trace(a) { try { return readAll(a) } catch (e) { return e.name + ' ' + e.stack.split('\n').length } }
            var objs = []; for (var i = 0; i < 10; i++) objs.push({ p: { q: i } });
            """.trimIndent()
        val warm = "for (var r = 0; r < 30; r++) { sumSq(10); readAll(objs) }"
        val expr = "[sumSq(100), readAll(objs), trace(objs.concat([{ p: null }])), readAll([{ p: { q: 'x' } }, { q: 1, p: { q: 2 } }])].join('|')"
        val expected = interpreted(src, "$warm; $expr")
        for (stress in intArrayOf(0, 2)) {
            JvmCompiler.DEOPT_STRESS = stress
            try {
                NeonEngine.builder().executionMode(ExecutionMode.ADAPTIVE).jitThreshold(1 shl 30).backgroundCompilation(false).console(null)
                    .build().newContext().use { ctx ->
                        ctx.eval(src)
                        ctx.eval(warm)
                        for (n in listOf("sumSq", "readAll")) {
                            val cb: CodeBlock = (ctx.eval(n).raw as JSClosure).code
                            val ir = dev.mooner.neonjs.jit.opt.IrPrinter.print(IrBuilder(cb, ints = true, elems = true).build())
                            assertTrue(ir.contains(" quiet"), ir)
                            cb.baseline ?: JvmCompiler.compile(cb)!!.also { cb.baseline = it; cb.compiled = it }
                            Jit.install(cb, T2.compile(cb, CodeDefiners.default) ?: fail("$n not compiled"))
                        }
                        assertEquals(expected, ctx.eval(expr).asString())
                    }
            } finally {
                JvmCompiler.DEOPT_STRESS = 0
            }
        }
    }

    @Test
    fun loopsOfQuietCallsCanBeInterrupted() {
        NeonEngine.builder().executionMode(ExecutionMode.ADAPTIVE).jitThreshold(1 shl 30).backgroundCompilation(false).console(null)
            .build().newContext().use { ctx ->
                ctx.eval("function step(x) { return x + 1 } function spin(stop) { var i = 0; while (i !== stop) i = step(i) % 1000; return i }")
                ctx.eval("for (var r = 0; r < 20; r++) spin(5)")
                val cb: CodeBlock = (ctx.eval("spin").raw as JSClosure).code
                val ir = dev.mooner.neonjs.jit.opt.IrPrinter.print(IrBuilder(cb, ints = true, elems = true).build())
                assertTrue(ir.contains(" quiet"), ir)
                cb.baseline ?: JvmCompiler.compile(cb)!!.also { cb.baseline = it; cb.compiled = it }
                Jit.install(cb, T2.compile(cb, CodeDefiners.default) ?: fail("spin not compiled"))
                val t = Thread { Thread.sleep(300); ctx.interrupt() }
                t.start()
                assertThrows(NeonInterruptedException::class.java) { ctx.eval("spin(-1)") }
                t.join()
                assertEquals(5, ctx.eval("spin(5)").asInt())
            }
    }

    /** The IR of function [name] after [src] ran in the interpreter (its caches and profiles filled). */
    private fun irOf(src: String, name: String): String =
        NeonEngine.builder().executionMode(ExecutionMode.ADAPTIVE).jitThreshold(1 shl 30).console(null).build().newContext().use { ctx ->
            ctx.eval(src)
            dev.mooner.neonjs.jit.opt.IrPrinter.print(IrBuilder((ctx.eval(name).raw as JSClosure).code, ints = true, elems = true).build())
        }
}
