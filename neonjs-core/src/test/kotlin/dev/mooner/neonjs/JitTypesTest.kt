package dev.mooner.neonjs

import dev.mooner.neonjs.jit.JvmCompiler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

/**
 * Unboxed numbers in compiled code (jit/JitTypes.kt): every case must give in compiled mode exactly what it gives in
 * the interpreter. The cases keep their numbers in registers (no closure reads them), so they run on the `double`
 * locals, and they pick the edges: -0, NaN, 2^53, ToInt32, operands of other types (valueOf order, BigInt), merges of
 * numbers with other values, numbers across try / catch / finally, and number keys.
 */
class JitTypesTest {
    private val prelude = """
        function show(v) {
          if (typeof v === 'number') return Object.is(v, -0) ? '-0' : String(v);
          if (typeof v === 'bigint') return v + 'n';
          if (typeof v === 'string') return JSON.stringify(v);
          return String(v);
        }
        function all() { return Array.prototype.map.call(arguments, show).join(' '); }
    """.trimIndent() + "\n"

    private fun run(code: String, mode: ExecutionMode): String =
        NeonEngine.builder().executionMode(mode).console(null).build().newContext().use { it.eval(prelude + code).asString() }

    private fun same(code: String, expected: String? = null) {
        val untyped = JvmCompiler.untypedReasons.values.sumOf { it.get() }
        val interpreted = run(code, ExecutionMode.INTERPRETER)
        if (expected != null) assertEquals(expected, interpreted, "interpreter: $code")
        assertEquals(interpreted, run(code, ExecutionMode.COMPILED), "compiled: $code")
        assertEquals(untyped, JvmCompiler.untypedReasons.values.sumOf { it.get() }, "compiled without unboxed values: $code")
    }

    @Test
    fun negativeZeroNaNAndLargeNumbers() {
        same(
            "(function () { var a = 0; var b = -a; var c = a * -1; var d = -0 + 0; var e = -0 - 0; var f = 0 / -5; " +
                "return all(b, c, d, e, 1 / b, -0 % 5, 0 % -5, -5 % 5, 5.5 % 2, -5.5 % 2, 5 % 0, Infinity % 2, 2 % Infinity, f) })()",
            "-0 -0 0 -0 -Infinity -0 0 -0 1.5 -1.5 NaN NaN 2 -0",
        )
        same(
            "(function () { var n = 0 / 0; return all(n < 1, n > 1, n <= 1, n >= 1, n == n, n != n, n === n, n !== n, 1 < n, 1 >= n, !n, n ? 1 : 2, -n) })()",
            "false false false false false true false true false false true 2 NaN",
        )
        same(
            "(function () { var x = 9007199254740992; var y = x + 1, z = x + 2, w = x * x; x++; " +
                "return all(x, y, z, w, 2 ** 53 + 1, 1 / 0, -1 / 0, 1 ** Infinity, (-8) ** (1 / 3), 2 ** -1074, 2 ** 1024, (-2) ** 3, 0 ** -1, (-0) ** -1) })()",
        )
        same("(function () { var x = 0, n = 0; for (; x != 1 && n < 20; x += 0.1) n++; var y = 0; do { y += 0.25 } while (y < 2); return all(x, n, y) })()")
        same("(function () { var a = 0.1, b = 0.2; return all(a + b, 1e21 * 1, -1e-7 * 1, 123456789012345680000 + 0, 5e-324 / 2, (0.1 * 3).toFixed(20), 255 .toString(16)) })()")
    }

    @Test
    fun int32Conversions() {
        same(
            "(function () { var v = [4294967301, -2147483649, 1e21, -1e21, 0 / 0, 1 / 0, -0, 2147483648, -2147483648.5, 0.9, -0.9, 4294967295.5]; var r = []; " +
                "for (var i = 0; i < v.length; i++) { var x = v[i] * 1; r.push(x | 0, x >>> 0, x >> 1, x << 31, ~x, x & -1, x ^ 1, 1 << x, -1 >>> x, x >> 33) } " +
                "return all.apply(null, r) })()",
        )
        same("(function () { var s = 0; for (var i = 0; i < 100; i++) s = (s + i * 123456789) | 0; return all(s, s >>> 0, -s >>> 0) })()")
    }

    @Test
    fun operandsOfOtherTypes() {
        // valueOf runs once per comparison, at the comparison
        same(
            "(function () { var log = []; var n = { valueOf: function () { log.push('n'); return 3 } }; for (var i = 0; i < n; i++) log.push('b'); return log.join() + ' ' + i })()",
            "n,b,n,b,n,b,n 3",
        )
        same(
            "(function () { var log = []; var a = { valueOf: function () { log.push('a'); return 2 } }; var s = 1; " +
                "for (var i = 0; i < 3; i++) { s = s * a; s = a - s; s = (s + a) | 0 } return log.length + ' ' + all(s) })()",
        )
        same(
            "(function () { var o = { valueOf: function () { throw new RangeError('v') } }; var x = 1, r = []; " +
                "try { x < o } catch (e) { r.push(e.name) } try { o * x } catch (e) { r.push(e.name) } try { x - o } catch (e) { r.push(e.name) } " +
                "try { x == o } catch (e) { r.push(e.name) } r.push(x === o); return r.join() })()",
            "RangeError,RangeError,RangeError,RangeError,false",
        )
        // the other operand changes type within the loop
        same(
            "(function () { var lim = 6, r = []; for (var i = 0; i < lim; i++) { r.push(i); if (i === 1) lim = '5'; " +
                "if (i === 2) lim = { valueOf: function () { return 4 } }; if (i === 3) lim = 10n; if (i === 5) lim = null } return r.join() })()",
            "0,1,2,3,4,5",
        )
        same("(function () { var i = 2; return all(i < '10', '10' < '9', i == '2', i === '2', null >= 0, i < null, undefined < i, i > undefined, i == null, i != undefined, i < true + 2, [2] == i, i < [3], i < 'x', 'x' > i) })()")
        same(
            "(function () { var x = 1, r = []; var vals = [1, '1', 1n, true, [1], { valueOf: function () { return 1 } }, null, 0 / 0]; " +
                "for (var i = 0; i < vals.length; i++) { var v = vals[i]; switch (v) { case x: r.push('num'); break; default: r.push(x == v ? 'loose' : 'no') } } return r.join() })()",
        )
    }

    @Test
    fun bigIntsCompareButDoNotMix() {
        same(
            "(function () { var i = 1, b = 10n, r = [i < b, i > b, i == 1n, i === 1n, 2 ** 53 < 2n ** 53n + 1n, 0.5 < 1n, i >= 1n]; " +
                "try { i * b } catch (e) { r.push(e.name) } try { i + b } catch (e) { r.push(e.name) } try { i | b } catch (e) { r.push(e.name) } " +
                "try { i ** b } catch (e) { r.push(e.name) } var j = 1; j++; try { j * 2n } catch (e) { r.push(e.name + j) } " +
                "try { b - j } catch (e) { r.push(e.name) } return r.join() })()",
            "true,false,true,false,true,true,true,TypeError,TypeError,TypeError,TypeError,TypeError2,TypeError",
        )
    }

    @Test
    fun numbersMergedWithOtherValues() {
        same(
            "(function () { var r = []; for (var i = 0; i < 4; i++) { var x = i & 1 ? 1 : 'a'; var y = (i && 2) || 3; var z = (i > 2 ? undefined : null) ?? 5; " +
                "var w = i ? i * 2 : true; r.push(all(x + 1, y * 2, z - 1, w + 1)) } return r.join('|') })()",
        )
        same("(function () { var v = 0; for (var i = 0; i < 10; i++) { v = v + 1; if (i === 5) v = 'str' } var u = 1; if (v.length) u = {}; return all(v) + ' ' + typeof u })()")
        same("(function () { var s = 0, t = '0'; for (var k = 0; k < 3; k++) { t += 1; s += t.length } return all(s, t) })()", "9 \"0111\"")
        same(
            "(function () { var s = '5'; s++; var t = '5', u = t++; var o = { valueOf: function () { return 1 } }; o++; var b = 1n; b++; var q = 'a'; q--; var n = null; n++; var d; d++; " +
                "return all(s, typeof s, t, u, typeof u, o, b, q, n, d) })()",
            "6 \"number\" 6 5 \"number\" 2 2n NaN 1 NaN",
        )
        same(
            "(function () { var r = [+'  12  ', +[], +{}, +true, +null, +undefined, +'0x10', +'1e3', -'', +[5]]; " +
                "try { +Symbol() } catch (e) { r.push(e.name) } try { +1n } catch (e) { r.push(e.name) } return all.apply(null, r) })()",
        )
        same("(function () { var n = 1; var r = [typeof n, typeof (n + 1), typeof (n < 2), typeof -n]; try { let x = x + 1 } catch (e) { r.push(e.name) } return r.join() })()")
    }

    @Test
    fun numbersAcrossExceptionsAndFinally() {
        same("(function () { var s = 0; for (var i = 0; i < 10; i++) { try { s += i; if (i === 3) throw i; if (i === 5) null.x } catch (e) { s += 100 } finally { s += 0.5 } } return all(s) })()", "250")
        same("(function () { var k = 1; try { k = 2; k = k * 3; throw 0 } catch (e) { return all(k) } })()", "6")
        same("(function () { var k = 1; try { k = 'a'; throw 0 } catch (e) { k = k + 1 } return all(k) })()", "\"a1\"")
        same("(function () { var k = 1.5; try { k = k * 2; undefinedName } catch (e) { k = k + e.name } return all(k) })()", "\"3ReferenceError\"")
        same(
            "(function () { var s = 0; outer: for (var i = 0; i < 5; i++) { for (var j = 0; j < 5; j++) { try { if (j === 2) continue outer; if (i === 3) break outer; s += j } finally { s += 0.25 } } } return all(s, i, j) })()",
            "5.5 3 0",
        )
        same("(function () { function f(n) { var acc = 0.5; try { for (var i = 0; i < n; i++) { if (i === 3) return acc; acc *= 2 } } finally { acc = -1 } return acc } return all(f(2), f(5)) })()", "-1 4")
        // using: the register that picks how a finally block continues holds numbers
        same(
            "(function () { var log = []; for (var i = 0; i < 3; i++) { using r = { [Symbol.dispose]: function () { log.push('d') } }; log.push(i * 1.5); if (i === 1) continue } return log.join() })()",
            "0,d,1.5,d,3,d",
        )
    }

    @Test
    fun numbersInEnvironments() {
        same("(function () { var c = 0; var inc = function () { return c++ }; for (var i = 0; i < 5; i++) inc(); c = c * 1.5; return all(c, inc()) })()", "7.5 7.5")
        same(
            "(function () { function g(a) { a = a + 1; arguments[0] = 10; return a } function h(a) { arguments[0] = 'x'; return a + 1 } " +
                "function k(a) { 'use strict'; arguments[0] = 'x'; return a + 1 } return all(g(1), h(1), k(1)) })()",
            "10 \"x1\" 2",
        )
        same("(function () { var x = 1; eval('x = \"s\"'); var y = 2; eval('y = y * 3'); return all(x + 1, y) })()", "\"s1\" 6")
        same("(function () { var o = { x: 5 }; var x = 1; with (o) { x = x + 1 } return all(x, o.x) })()", "1 6")
        same(
            "(function () { function f(a = 1, [b, c] = [2, 3], ...rest) { a = a | 0; return a + b * c + rest.length } " +
                "function g(n) { n = n | 0; var s = 0; while (n--) s += n; return s } return all(f(), f(2.7, [1, 1], 0, 0), f('3'), g(5), g('4'), g()) })()",
            "7 5 9 10 6 0",
        )
        same("(function () { function fib(n) { return n < 2 ? n : fib(n - 1) + fib(n - 2) } function f(n) { return n <= 0 ? -0 : f(n - 0.5) } return all(fib(20), f(3)) })()", "6765 -0")
        same("(function () { var s = 0, k; for (k in { a: 1, b: 2 }) s += k.length; for (var v of [1.5, 2.5]) s += v; var [p, q] = [3, 4]; [p, q] = [q, p]; s += p * 10 + q; return all(s, k, v) })()", "49 \"b\" 2.5")
        same("(function () { function* g() { var x = 0.5; for (var i = 0; i < 3; i++) { x = x * 2 + (yield x) } return x } var it = g(), r = [], s = it.next(); while (!s.done) { r.push(s.value); s = it.next(1) } r.push(s.value); return r.join() })()", "0.5,2,5,11")
    }

    @Test
    fun numberKeys() {
        same(
            "(function () { var a = [1, 2, 3]; var k = 1.5; a[k] = 'x'; var z = -0; a[z] = 'zero'; var big = 2 ** 32; a[big] = 'big'; var nan = 0 / 0; a[nan] = 'nan'; var h = [1, , 3]; " +
                "return all(a[1.5], a[0], a['0'], a.length, a[big], a.NaN, h[1], 1 in h, a[-1], a[3]) })()",
            "\"x\" \"zero\" \"zero\" 3 \"big\" \"nan\" undefined false undefined undefined",
        )
        same(
            "(function () { var t = new Uint8Array(4); var i = 1; t[i] = 300; t[i + 0.5] = 7; t[-0] = 9; t[4] = 1; t[-1] = 2; var f = new Float64Array(2); f[0] = 0 / 0; f[1] = -0; " +
                "return all(t[1], t[0], t[1.5], t[4], t.length, f[0], f[1], Object.keys(t).length) })()",
            "44 9 undefined undefined 4 NaN -0 4",
        )
        same(
            "(function () { var a = Object.freeze([1, 2]); var i = 0; a[i] = 99; var s = (function () { 'use strict'; var j = 0; try { a[j] = 99 } catch (e) { return e.name } })(); return all(a[0], s) })()",
            "1 \"TypeError\"",
        )
        same(
            "(function () { Object.defineProperty(Array.prototype, 3, { get: function () { return 'proto' }, configurable: true }); var a = [1, 2]; var i = 3; var r = a[i]; " +
                "delete Array.prototype[3]; return all(r, a[i]) })()",
            "\"proto\" undefined",
        )
        same(
            "(function () { var log = []; var p = new Proxy([], { set: function (t, k, v) { log.push(typeof k + k); t[k] = v; return true }, get: function (t, k) { log.push('g' + String(k)); return t[k] } }); " +
                "for (var i = 0; i < 2; i++) p[i] = i * 0.5; var v = p[1]; return log.join() + ' ' + all(v) })()",
            "string0,string1,g1 0.5",
        )
        same("(function () { var o = {}; var i = 1; o[i] = 'a'; o[i + 0.5] = 'b'; o[1e21] = 'c'; o[-0] = 'd'; return Object.keys(o).join() })()", "0,1,1.5,1e+21")
    }

    @Test
    @Timeout(60, unit = TimeUnit.SECONDS)
    fun numberLoopsHonourLimits() {
        val p = SandboxPolicy.builder().maxExecutionTime(300).build()
        NeonEngine.builder().sandbox(p).executionMode(ExecutionMode.COMPILED).build().newContext().use { c ->
            assertThrows<NeonTimeoutException> { c.eval("(function () { for (var i = 0; ; i += 0.5) {} })()") }
            assertThrows<NeonTimeoutException> { c.eval("(function (n) { var i = 0; while (i < n) { i = i + 1 - 1 } })(1)") }
            assertThrows<NeonTimeoutException> { c.eval("(function () { var x = 1; do { x = x * 1 } while (x) })()") }
        }
        val s = SandboxPolicy.builder().maxStatements(100_000).build()
        NeonEngine.builder().sandbox(s).executionMode(ExecutionMode.COMPILED).build().newContext().use { c ->
            assertThrows<NeonResourceLimitException> { c.eval("(function () { var s = 0; for (var i = 0; i < 1e9; i++) s += i; return s })()") }
        }
    }
}
