package io.neonjs

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConsoleTest {
    private fun capture(code: String): List<Pair<NeonConsole.Level, String>> {
        val out = ArrayList<Pair<NeonConsole.Level, String>>()
        NeonEngine.builder().console { l, m -> out.add(l to m) }.build().newContext().use { it.eval(code) }
        return out
    }

    @Test
    fun formatsValuesWithoutRunningGuestCode() {
        val out = capture("""
            var touched = false;
            var o = { get g() { touched = true; return 1 }, toString() { touched = true; return 'x' } };
            var p = new Proxy({}, { get() { touched = true }, ownKeys() { touched = true; return [] } });
            console.log(o, p, [1, , 3], { n: null, u: undefined, s: 'q' });
            console.log('touched=%s', touched);
        """)
        assertEquals("{ g: [Getter], toString: [Function: toString] } Proxy {} [ 1, <1 empty item>, 3 ] { n: null, u: undefined, s: 'q' }", out[0].second)
        assertEquals("touched=false", out[1].second)
    }

    @Test
    fun accessorElementsOfArrays() {
        // found by the fuzzer: an accessor in an array's element storage was printed as an internal record
        val out = capture("""
            var touched = false;
            var a = [1, 2]; Object.defineProperty(a, 0, { get() { touched = true; return 5 }, enumerable: true });
            Object.defineProperty(Array.prototype, 0, { set() {}, configurable: true });
            console.log(a, Array.prototype, touched);
            delete Array.prototype[0];
        """)
        assertEquals("[ [Getter], 2 ] [ [Setter] ] false", out[0].second)
    }

    @Test
    fun levelsGroupsAndCounters() {
        val out = capture("""
            console.warn('w'); console.error('e'); console.info('i'); console.debug('d');
            console.group('g'); console.log('a\nb'); console.groupEnd();
            console.count('k'); console.count('k');
            console.assert(true, 'never'); console.assert(false, 'x=%d', 5);
        """)
        assertEquals(listOf(NeonConsole.Level.WARN, NeonConsole.Level.ERROR, NeonConsole.Level.INFO, NeonConsole.Level.DEBUG), out.take(4).map { it.first })
        assertEquals("g", out[4].second)
        assertEquals("  a\n  b", out[5].second)
        assertEquals(listOf("k: 1", "k: 2", "Assertion failed: x=5"), out.drop(6).map { it.second })
    }

    @Test
    fun circularAndDepth() {
        val out = capture("var a = { b: { c: { d: { e: 1 } } } }; a.self = a; console.log(a)")
        assertEquals("{ b: { c: { d: [Object] } }, self: [Circular] }", out[0].second)
    }

    @Test
    fun consoleCanBeDisabled() {
        NeonEngine.builder().console(null).build().newContext().use { c ->
            assertEquals("undefined", c.eval("typeof console").asString())
        }
    }

    @Test
    fun hugeMessagesAreTruncated() {
        val out = capture("console.log('x'.repeat(5_000_000))")
        assertTrue(out[0].second.length < 2_000_000)
    }
}
