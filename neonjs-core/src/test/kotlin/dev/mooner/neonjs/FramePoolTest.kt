package dev.mooner.neonjs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Frames of calls of compiled code that returned serve the next calls (Agent.framePool): nothing a call leaves behind
 * may see its frame change.
 */
class FramePoolTest {
    /** With the pool on (it is off by default on HotSpot, where these tests run). */
    private fun run(mode: ExecutionMode, src: String): String {
        val before = dev.mooner.neonjs.runtime.FRAME_POOLING
        dev.mooner.neonjs.runtime.FRAME_POOLING = true
        try {
            return NeonEngine.builder().executionMode(mode).backgroundCompilation(false).console(null).build().newContext().use { it.eval(src).asString() }
        } finally {
            dev.mooner.neonjs.runtime.FRAME_POOLING = before
        }
    }

    private fun same(src: String) {
        val expected = run(ExecutionMode.INTERPRETER, src)
        assertEquals(expected, run(ExecutionMode.COMPILED, src), src)
        assertEquals(expected, run(ExecutionMode.ADAPTIVE, src), src)
    }

    @Test
    fun whatACallLeavesBehindKeepsItsValues() {
        same(
            """
            function args() { return arguments }
            function make(x) { var y = x * 2; return () => x + y }
            function self() { return this }
            function rest(x, ...r) { return r }
            function* gen(x, y) { yield x; yield y }
            var g = gen('g1', 'g2'), r1 = rest(1, 2, 3), r2 = rest(4, 5);
            var a = args(1, 2), b = args(3, 4), fs = [];
            for (var i = 0; i < 100; i++) fs.push(make(i));
            var s = 0; for (var f of fs) s += f();
            var o = { m: self }, p = { m: self };
            [a[0], a[1], b[0], b.length, s, o.m() === o, p.m() === p, r1, r2, [...g]].join()
            """.trimIndent(),
        )
    }

    @Test
    fun errorsKeepTheStackOfWhereTheyWereMade() {
        // a TypeError made at f's call (f's frame on top), kept, then thrown again through later calls of f, which may
        // get the same frame: its stack stays the first one (an error that has not left its frame yet is told from a
        // later activation of that frame by Frame.activation)
        same(
            """
            function f(g) { return g() }
            var saved;
            try { f(null) } catch (e) { saved = e }
            var first = saved.stack;
            for (var k = 0; k < 50; k++) { try { f(() => { throw saved }) } catch (e) {} }
            [saved.stack === first, saved.stack.split('\n').length].join()
            """.trimIndent(),
        )
    }

    @Test
    fun recursionAndExceptionsAcrossFrames() {
        same(
            """
            function fib(n) { return n < 2 ? n : fib(n - 1) + fib(n - 2) }
            function deep(n) { if (n === 0) throw new Error('bottom'); return deep(n - 1) + 1 }
            function catcher(n) { try { return deep(n) } catch (e) { return e.message + ' ' + e.stack.split('\n').length } }
            var r = []; for (var i = 0; i < 20; i++) r.push(fib(i), catcher(i % 5));
            r.join()
            """.trimIndent(),
        )
        assertTrue(run(ExecutionMode.COMPILED, "function f(n) { return n ? f(n - 1) : 0 } for (var i = 0; i < 1000; i++) f(10); 'ok'") == "ok")
    }
}
