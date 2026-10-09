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
        val failed = JvmCompiler.failedCount.get()
        val interpreted = run(code, ExecutionMode.INTERPRETER)
        if (expected != null) assertEquals(expected, interpreted, "interpreter: $code")
        assertEquals(interpreted, run(code, ExecutionMode.COMPILED), "compiled: $code")
        assertEquals(untyped, JvmCompiler.untypedReasons.values.sumOf { it.get() }, "compiled without unboxed values: $code")
        // a class the JVM rejects (VerifyError) leaves its block interpreted, which would hide it from the comparison
        assertEquals(failed, JvmCompiler.failedCount.get(), "not compiled (${JvmCompiler.failureReasons.keys}): $code")
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
    fun int32ValuesAreNumbersInArithmetic() {
        // -0 and overflow: arithmetic on int32 values (bitwise results, integer literals and constants) is on doubles
        same(
            "(function () { var z = 5 & 0, m = 2147483647 | 0, n = -2147483648 | 0, f = -4 | 0, c = 2147483647, d = -2147483648; " +
                "return all(-z, z * -1, z / -1, f % 2, -f % 4, z - 0, m + 1, m * 2, m - -1, -n, n - 1, n * n, n / -1, c + 1, d - 1, -d, (m + 1) | 0, 1 / z, 0 - z) })()",
            "-0 -0 -0 -0 0 0 2147483648 4294967294 2147483648 2147483648 -2147483649 4611686018427388000 2147483648 2147483648 -2147483649 2147483648 -2147483648 Infinity 0",
        )
        same(
            "(function () { var j = 2147483646 | 0; j++; var k = j; j++; var l = -2147483647 | 0; l--; l--; var p = 7 & 7; p += 0.5; var q = 3 | 0; q = q / 2; " +
                "return all(j, k, l, p, q, 2147483647 + 1, -(0 | 0), 0 * -1) })()",
            "2147483648 2147483647 -2147483649 7.5 1.5 2147483648 -0 -0",
        )
        // -0 constants are not int32s
        same("(function () { var z = -0; var w = z | 0; return all(z, 1 / z, w, 1 / w, -0 & 1, z === 0, Object.is(z, -0)) })()", "-0 -Infinity 0 Infinity 0 true true")
    }

    @Test
    fun int32BitwiseOperators() {
        same(
            "(function () { var one = 1 | 0, m1 = -1 | 0, s = 32 | 0; " +
                "return all(one << 31, one << 32, one << 33, one << m1, m1 >>> 0, m1 >>> 31, m1 >>> s, -8 >> 1, m1 >> 33, (one << 31) >>> 0, ~0, ~m1, ~~3.7, ~~-3.7, ~2147483647, " +
                "0x5bd1e995 ^ m1, 0x80000000 | 0, 4294967295 & m1, 1e9 | 0, -1e9 >> 0, (0x80000000 | 0) >> 31, 255 & -1, m1 & 0xFFFF, s >>> 1) })()",
            "-2147483648 1 2 -2147483648 4294967295 1 4294967295 -4 -1 2147483648 -1 0 3 -3 -2147483648 -1540483478 -2147483648 -1 1000000000 -1000000000 -1 255 65535 16",
        )
        // hash and random number functions: chains of int operations, with products beyond 2^53 rounded as doubles
        same(
            "(function () { var h = 2166136261 | 0, str = 'hello, int32'; for (var i = 0; i < str.length; i++) { h ^= str.charCodeAt(i); h = (h * 16777619) | 0 } " +
                "var x = 2463534242 | 0, r = []; for (var k = 0; k < 6; k++) { x ^= x << 13; x ^= x >> 17; x ^= x << 5; r.push(x, x >>> 0) } " +
                "var c = 0; for (var n = 0; n < 1000; n++) { c = (c + (n & 7) * 0x7fffffff) | 0 } return all(h, h >>> 0, c) + ' ' + r.join() })()",
            "554921442 554921442 -3500 723471715,723471715,-1797960838,2497006458,-1963417273,2331550023,1155419087,1155419087,961924764,961924764,-1874307777,2420659519",
        )
        same(
            "(function () { var x = 0, n = 0 / 0, inf = 1 / 0, big = 2 ** 53 + 2, r = []; var v = [n, inf, -inf, big, -big, 2 ** 63, -(2 ** 63), 2 ** 64 + 4096, 1e300, 2 ** 32 + 5, -(2 ** 31) - 1, 2 ** 31, 0.5, -0.5, -1.5]; " +
                "for (var i = 0; i < v.length; i++) { x = v[i] * 1; r.push(x | 0, x >> 0, x << 1, ~x, x & x, x ^ 0, x >>> 0) } return all.apply(null, r) })()",
            "0 0 0 -1 0 0 0 0 0 0 -1 0 0 0 0 0 0 -1 0 0 0 2 2 4 -3 2 2 2 -2 -2 -4 1 -2 -2 4294967294 0 0 0 -1 0 0 0 0 0 0 -1 0 0 0 4096 4096 8192 -4097 4096 4096 4096 0 0 0 -1 0 0 0 5 5 10 -6 5 5 5 2147483647 2147483647 -2 -2147483648 2147483647 2147483647 2147483647 -2147483648 -2147483648 0 2147483647 -2147483648 -2147483648 2147483648 0 0 0 -1 0 0 0 0 0 0 -1 0 0 0 -1 -1 -2 0 -1 -1 4294967295",
        )
        same("(function () { var a = 6 | 0, b = 3 | 0; a |= 0; a <<= 2; a >>= 1; a ^= b; a &= 0xFF; var c = a; c >>>= 1; return all(a, c, a | b, a & ~b) })()", "15 7 15 12")
    }

    @Test
    fun int32ComparisonsAndTruth() {
        same(
            "(function () { var a = -5 | 0, b = 3 | 0, n = 0 / 0, h = 0.5; " +
                "return all(a < b, a > b, a <= -5, a >= b, a == -5, a === -5, a != b, a !== b, a < n, a >= n, a == n, (a & 0) < h, (b | 0) > 2.5, b == '3', b === '3', a < '1', b < null, b == true) })()",
            "true false true false true true true true false false false true true true false true false false",
        )
        same(
            "(function () { var r = [], z = 0 | 0, m = -2147483648 | 0, k = 1024 | 0, c = 0; while ((k >>= 1)) c++; " +
                "r.push(!z, !m, !(5 & 2), !(5 & 4), z ? 1 : 2, m ? 1 : 2, (m & m) && 'x', (z | z) || 'y', c, !!(7 & 3)); for (var i = 0; i < 20; i++) if ((i & 7) === 0) r.push(i); return all.apply(null, r) })()",
            "true false true false 2 1 \"x\" \"y\" 10 true 0 8 16",
        )
        same(
            "(function () { var r = []; for (var i = -2; i < 6; i++) { switch (i & 3) { case 0: r.push('z'); break; case 1.0: r.push('o'); break; case '2': r.push('s'); break; default: r.push(i & 3) } } " +
                "var t = true, f = false; r.push(+t + (5 | 0), +f, +t + 0.5, -(+f)); return all.apply(null, r) })()",
            "2 3 \"z\" \"o\" 2 3 \"z\" \"o\" 6 0 1.5 -0",
        )
    }

    @Test
    fun int32ValuesMergedAndStored() {
        // registers holding ints and doubles on different paths, values of both kinds meeting on the stack
        same(
            "(function () { var v = 1 | 0, r = []; for (var i = 0; i < 8; i++) { v = i % 3 ? v * 1.5 : v | 0; r.push(v) } var w = i & 1 ? 0.25 : (i | 0); " +
                "var x = i > 3 ? (i << 1) : 'str'; var y = (i & 1) || (i | 0) * 0.5; return all(w, x, y) + ' ' + r.join() })()",
            "8 16 4 1,1.5,2.25,2,3,4.5,4,6",
        )
        same(
            "(function () { var k = 1 | 0, r = []; try { k = k << 2; throw 0 } catch (e) { k = k ^ 3 } r.push(k); var m = 5 | 0; try { m = m * 0.5; null.x } catch (e) { m = m | 0 } r.push(m); " +
                "var q = 7 | 0; try { q = 'q' + q; undefinedName } catch (e) { r.push(q) } finally { q = q | 1 } r.push(q); return all.apply(null, r) })()",
            "7 2 \"q7\" 1",
        )
        same(
            "(function () { var s = 0 | 0; for (var i = 0; i < 100; i++) { s = (s + i * 123456789) | 0; if (i === 50) s = s / 3 } var u = s; u = u & 0xFF; return all(s, u, s >>> 0) })()",
            "-2064961794 254 2230005502",
        )
        same(
            "(function () { var log = []; var o = { valueOf: function () { log.push('o'); return 5 } }; var a = 6 | 0; var r = [o | a, a & o, a ^ '3', '12' | a, null | a, a << undefined, [5] | a, a >> [1], a >>> o]; " +
                "try { a | 1n } catch (e) { r.push(e.name) } try { 1n & a } catch (e) { r.push(e.name) } try { a >>> 1n } catch (e) { r.push(e.name) } try { a << Symbol() } catch (e) { r.push(e.name) } " +
                "return log.join() + ' ' + all.apply(null, r) })()",
            "o,o,o 7 4 5 14 6 6 7 3 0 \"TypeError\" \"TypeError\" \"TypeError\" \"TypeError\"",
        )
    }

    @Test
    fun int32Keys() {
        same(
            "(function () { var a = [10, 20, , 40]; var m = -1 | 0, two = 2 & 3, r = [a[m], a[two], a[1 | 0], 2 in a]; a[m] = 'neg'; a[two] = 30; a[7 & 7] = 'far'; " +
                "r.push(a[-1], a['-1'], a.length, a[2], a[6]); var t = new Int8Array(4); for (var i = 0; i < 6; i++) t[i & 7] = i * 100; t[m] = 1; r.push(t.join(), t[m], t[4 | 0]); " +
                "var f = Object.freeze([1, 2]); f[0 | 0] = 9; r.push(f[0]); return all.apply(null, r) })()",
            "undefined undefined 20 false \"neg\" \"neg\" 8 30 undefined \"0,100,-56,44\" undefined undefined 1",
        )
        same(
            "(function () { var log = []; var p = new Proxy([], { set: function (t, k, v) { log.push(typeof k + k); t[k] = v; return true }, get: function (t, k) { log.push('g' + String(k)); return t[k] } }); " +
                "for (var i = 0; i < 2; i++) p[i & 1] = i | 0; var v = p[1 | 0]; var s = 'abc'; return log.join() + ' ' + all(v, s[1 | 0], s[-1 | 0]) })()",
            "string0,string1,g1 1 \"b\" undefined",
        )
    }

    @Test
    fun numberStoresIntoElements() {
        // every key/value kind pair (int or double) into every Number element type
        same(
            "(function () { var types = [Int8Array, Uint8Array, Uint8ClampedArray, Int16Array, Uint16Array, Int32Array, Uint32Array, Float16Array, Float32Array, Float64Array]; " +
                "var nums = [0, -0, 1.5, -1.5, 254.5, 255.5, 256, -1, 2147483648, -2147483649, 4294967295, 1e10, NaN, Infinity, -Infinity, 0.1, 65504, 65520, 1e-8, 3.4028235677973366e38]; var r = []; " +
                "for (var t = 0; t < types.length; t++) { var a = new types[t](nums.length), b = new types[t](nums.length), c = new types[t](8), d = new types[t](8); " +
                "for (var i = 0; i < nums.length; i++) { var x = nums[i] * 1; a[i] = x; b[i & 31] = x } " +
                "for (var k = 0; k < 8; k++) { var v = (k * 0x3fffffff) | 0; c[k] = v; d[k & 7] = v - 1 | 0 } " +
                "r.push([a, b, c, d].map(function (e) { return Array.prototype.map.call(e, show).join() }).join(' ')) } return r.join('|') })()",
            "0,0,1,-1,-2,-1,0,-1,0,-1,-1,0,0,0,0,0,-32,-16,0,0 0,0,1,-1,-2,-1,0,-1,0,-1,-1,0,0,0,0,0,-32,-16,0,0 0,-1,-2,-3,-4,-5,-6,-7 -1,-2,-3,-4,-5,-6,-7,-8|0,0,1,255,254,255,0,255,0,255,255,0,0,0,0,0,224,240,0,0 0,0,1,255,254,255,0,255,0,255,255,0,0,0,0,0,224,240,0,0 0,255,254,253,252,251,250,249 255,254,253,252,251,250,249,248|0,0,2,0,254,255,255,0,255,0,255,255,0,255,0,0,255,255,0,255 0,0,2,0,254,255,255,0,255,0,255,255,0,255,0,0,255,255,0,255 0,255,255,0,0,255,255,0 0,255,255,0,0,255,255,0|0,0,1,-1,254,255,256,-1,0,-1,-1,-7168,0,0,0,0,-32,-16,0,0 0,0,1,-1,254,255,256,-1,0,-1,-1,-7168,0,0,0,0,-32,-16,0,0 0,-1,-2,-3,-4,-5,-6,-7 -1,-2,-3,-4,-5,-6,-7,-8|0,0,1,65535,254,255,256,65535,0,65535,65535,58368,0,0,0,0,65504,65520,0,0 0,0,1,65535,254,255,256,65535,0,65535,65535,58368,0,0,0,0,65504,65520,0,0 0,65535,65534,65533,65532,65531,65530,65529 65535,65534,65533,65532,65531,65530,65529,65528|0,0,1,-1,254,255,256,-1,-2147483648,2147483647,-1,1410065408,0,0,0,0,65504,65520,0,0 0,0,1,-1,254,255,256,-1,-2147483648,2147483647,-1,1410065408,0,0,0,0,65504,65520,0,0 0,1073741823,2147483646,-1073741827,-4,1073741819,2147483642,-1073741831 -1,1073741822,2147483645,-1073741828,-5,1073741818,2147483641,-1073741832|0,0,1,4294967295,254,255,256,4294967295,2147483648,2147483647,4294967295,1410065408,0,0,0,0,65504,65520,0,0 0,0,1,4294967295,254,255,256,4294967295,2147483648,2147483647,4294967295,1410065408,0,0,0,0,65504,65520,0,0 0,1073741823,2147483646,3221225469,4294967292,1073741819,2147483642,3221225465 4294967295,1073741822,2147483645,3221225468,4294967291,1073741818,2147483641,3221225464|0,-0,1.5,-1.5,254.5,255.5,256,-1,Infinity,-Infinity,Infinity,Infinity,NaN,Infinity,-Infinity,0.0999755859375,65504,Infinity,0,Infinity 0,-0,1.5,-1.5,254.5,255.5,256,-1,Infinity,-Infinity,Infinity,Infinity,NaN,Infinity,-Infinity,0.0999755859375,65504,Infinity,0,Infinity 0,Infinity,Infinity,-Infinity,-4,Infinity,Infinity,-Infinity -1,Infinity,Infinity,-Infinity,-5,Infinity,Infinity,-Infinity|0,-0,1.5,-1.5,254.5,255.5,256,-1,2147483648,-2147483648,4294967296,10000000000,NaN,Infinity,-Infinity,0.10000000149011612,65504,65520,9.99999993922529e-9,Infinity 0,-0,1.5,-1.5,254.5,255.5,256,-1,2147483648,-2147483648,4294967296,10000000000,NaN,Infinity,-Infinity,0.10000000149011612,65504,65520,9.99999993922529e-9,Infinity 0,1073741824,2147483648,-1073741824,-4,1073741824,2147483648,-1073741824 -1,1073741824,2147483648,-1073741824,-5,1073741824,2147483648,-1073741824|0,-0,1.5,-1.5,254.5,255.5,256,-1,2147483648,-2147483649,4294967295,10000000000,NaN,Infinity,-Infinity,0.1,65504,65520,1e-8,3.4028235677973366e+38 0,-0,1.5,-1.5,254.5,255.5,256,-1,2147483648,-2147483649,4294967295,10000000000,NaN,Infinity,-Infinity,0.1,65504,65520,1e-8,3.4028235677973366e+38 0,1073741823,2147483646,-1073741827,-4,1073741819,2147483642,-1073741831 -1,1073741822,2147483645,-1073741828,-5,1073741818,2147483641,-1073741832",
        )
        // the value of the assignment is the value assigned, not the one stored
        same("(function () { var u = new Uint8Array(2), f = new Float32Array(1), x = 300.5, y = 256 | 0; var p = (u[0] = x), q = (u[1 | 0] = y), s = (f[0] = 0.1); return all(p, q, s, u[0], u[1], f[0]) })()", "300.5 256 0.1 44 0 0.10000000149011612")
        same(
            "(function () { var r = [], t = new Int16Array(2), m = -1 | 0, h = 1.5, big = 2 ** 32, z = -0; t[2 | 0] = 5; t[m] = 6; t[h] = 7; t[big] = 8; t[z] = 9; t[1 | 0] = 10.5; " +
                "r.push(t.join(), Object.keys(t).join(), '-1' in t, t[m], t[h]); " +
                "var buf = new ArrayBuffer(8, { maxByteLength: 16 }), lt = new Uint8Array(buf), fx = new Uint8Array(buf, 4, 4); buf.resize(2); var i = 3 | 0; lt[i] = 1; fx[0 | 0] = 1; lt[1 | 0] = 2.5; " +
                "r.push(lt.length, fx.length, lt.join()); buf.resize(8); r.push(fx.join()); " +
                "var d = new Float64Array(2), moved = d.buffer.transfer(); d[0 | 0] = 1.5; d[1] = 2.5; r.push(d.length, d[0], new Float64Array(moved).join()); " +
                "var b64 = new BigInt64Array(1), zd = h - 1.5; try { b64[0 | 0] = 1 } catch (e) { r.push(e.name) } try { b64[0] = 1.5 } catch (e) { r.push(e.name) } " +
                "try { b64[zd] = 2 | 0 } catch (e) { r.push(e.name) } try { b64[zd] = 2.5 } catch (e) { r.push(e.name) } b64[0] = 5n; r.push(String(b64[0])); " +
                "var arr = [1, 2]; arr[3 | 0] = 4.5; arr[1] = 2.5; r.push(arr.length, String(arr[2]), arr[3], arr[1]); var fr = Object.freeze([1, 2]); fr[0 | 0] = 9; fr[1] = 9.5; r.push(fr.join()); " +
                "var log = []; Object.defineProperty(Array.prototype, 5, { set: function (v) { log.push(v) }, configurable: true }); var g = [1]; g[5 | 0] = 7.5; g[5] = 8 | 0; delete Array.prototype[5]; r.push(log.join(), g.length); " +
                "return all.apply(null, r) })()",
            "\"9,10\" \"0,1\" false undefined undefined 2 0 \"0,2\" \"0,0,0,0\" 0 undefined \"0,0\" \"TypeError\" \"TypeError\" \"TypeError\" \"TypeError\" \"5\" 4 \"undefined\" 4.5 2.5 \"1,2\" \"7.5,8\" 1",
        )
        same(
            "(function () { 'use strict'; var r = [], s = 'abc', fr = Object.freeze([1]), b = new BigInt64Array(1); var cases = [function () { s[0 | 0] = 1 }, function () { fr[0 | 0] = 2.5 }, function () { undefined[0 | 0] = 1 }, function () { b[0] = 1 | 0 }]; " +
                "for (var i = 0; i < cases.length; i++) { try { cases[i](); r.push('ok') } catch (e) { r.push(e.name) } } return r.join() })()",
            "TypeError,TypeError,TypeError,TypeError",
        )
        // immutable buffers (not in Node): stores are ignored, or throw in strict code
        same(
            "(function () { var im = new Uint8Array(new Uint8Array([1, 2]).buffer.transferToImmutable()), one = 2 / 2, zero = one - 1; " +
                "im[0 | 0] = 5; im[1] = 6.5; im[zero] = 3 | 0; im[one] = 4.5; var r = [im.join()]; (function () { 'use strict'; var o = 2 / 2, z = o - 1; " +
                "try { im[0 | 0] = 7 } catch (e) { r.push(e.name) } try { im[1] = 7.5 } catch (e) { r.push(e.name) } " +
                "try { im[z] = 7 | 0 } catch (e) { r.push(e.name) } try { im[o] = 7.5 } catch (e) { r.push(e.name) } })(); return r.join() })()",
            "1,2,TypeError,TypeError,TypeError,TypeError",
        )
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
