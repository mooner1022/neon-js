package dev.mooner.neonjs.node

import dev.mooner.neonjs.NeonContext
import dev.mooner.neonjs.NeonEngine
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** node:assert (Node's own, over the primordials). */
class AssertTest {
    private fun ctx(): NeonContext = NeonEngine.builder().console(null).webGlobals(true).extension(NodeExtension()).build().newContext()

    private fun NeonContext.str(code: String): String = eval(code).asString()

    @Test
    fun passingAssertions() {
        ctx().use { c ->
            assertEquals("ok", c.str("""
                const assert = require('assert');
                assert(true); assert.ok(1); assert.equal(1, '1'); assert.strictEqual(NaN, NaN); assert.notStrictEqual(1, 2);
                assert.deepEqual({ a: [1, 2] }, { a: ['1', 2] });
                assert.deepStrictEqual(new Map([[1, { b: new Set([2]) }]]), new Map([[1, { b: new Set([2]) }]]));
                assert.notDeepStrictEqual({ a: 1 }, { a: '1' });
                assert.throws(() => { throw new TypeError('bad thing') }, TypeError);
                assert.throws(() => { throw new TypeError('bad thing') }, /bad/);
                assert.throws(() => { throw Object.assign(new RangeError('r'), { code: 'X' }) }, { name: 'RangeError', code: 'X', message: /^r$/ });
                assert.doesNotThrow(() => {});
                assert.match('abc', /b/);
                assert.ifError(null);
                require('node:assert/strict').equal(1, 1);
                'ok'
            """))
        }
    }

    @Test
    fun failures() {
        ctx().use { c ->
            assertEquals(
                "AssertionError|ERR_ASSERTION|strictEqual|Expected values to be strictly equal:\n\n1 !== 2\n",
                c.str("""
                    let e;
                    try { require('assert').strictEqual(1, 2) } catch (err) { e = err }
                    [e.name, e.code, e.operator, e.message].join('|')
                """))
            assertEquals("false == true|No value argument passed to `assert.ok()`|Missing expected exception.", c.str("""
                const a = require('assert'), msgs = [];
                for (const f of [() => a(false), () => a.ok(), () => a.throws(() => {})]) { try { f() } catch (err) { msgs.push(err.message) } }
                msgs.join('|')
            """))
            // deep inequality comes with Node's diff
            val diff = c.str("try { require('assert').deepStrictEqual({ a: 1, b: [1, 2] }, { a: 1, b: [1, 3] }) } catch (err) { err.message }")
            assertTrue(diff.startsWith("Expected values to be strictly deep-equal:"), diff)
            assertTrue(diff.contains("+     2") && diff.contains("-     3"), diff)
        }
        ctx().use { c ->
            val r = c.eval("""
                (async () => {
                    const assert = require('assert');
                    await assert.rejects(Promise.reject(new Error('no')), { message: 'no' });
                    await assert.doesNotReject(Promise.resolve(1));
                    return assert.rejects(Promise.resolve(1)).catch((e) => e.message);
                })()
            """).await(5_000).asString()
            assertEquals("Missing expected rejection.", r)
        }
    }
}
