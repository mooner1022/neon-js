package dev.mooner.neonjs

import dev.mooner.neonjs.jit.opt.T2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Generators and async functions compiled (docs/IR_NEUTRALITY.md, step 7): their code, the optimizing tier's baseline,
 * suspends where the interpreter would (yield, await, the generator's start) and resumes where a suspended activation
 * does, with what the resumption hands over (a value, a throw, a return); each case runs interpreted and compiled.
 */
class SuspendableJitTest {
    private val script = """
        function* counter(n) { let s = 0; for (let i = 0; i < n; i++) { const x = yield i; s += x | 0; } return s; }
        function* guarded() { try { yield 1; yield 2; } finally { log.push("finally"); } }
        function* delegate() { const r = yield* counter(2); yield r; }
        async function add(x) { const y = await x; const z = await Promise.resolve(y + 1); return z; }
        async function rejected() { const k = 5; try { await Promise.reject(new Error("no")); } catch (e) { return k + e.message; } }
        async function* agen() { yield 1; yield* [2, 3]; yield await 4; }
        // (a loop header at pc 0: a fresh frame starts at the start, not at the loop)
        async function loopFirst(x) { do { x++ } while (x < 10); return x }
        var log = [];
        function run() {
            const it = counter(4); let r = it.next(); const seen = [];
            while (!r.done) { seen.push(r.value); r = it.next(r.value * 10); }
            log.push(seen.join(",") + "=" + r.value);
            for (const v of guarded()) { log.push("v" + v); break; }
            const g = guarded(); g.next();
            try { g.throw(new Error("boom")); } catch (e) { log.push("caught " + e.message); }
            const d = delegate(); const ds = []; let dr = d.next();
            while (!dr.done) { ds.push(dr.value); dr = d.next(7); }
            log.push("delegate " + ds.join(","));
            return Promise.all([add(1), rejected(), (async () => { const o = []; for await (const v of agen()) o.push(v); return o.join(","); })(), loopFirst(3)])
                .then(v => log.join("|") + "|" + v.join("|"));
        }
        run();
    """.trimIndent()

    private val expected = "0,1,2,3=60|v1|finally|finally|caught boom|delegate 0,1,14|2|5no|1,2,3,4|10"

    private fun runIn(mode: ExecutionMode): String {
        val engine = NeonEngine.builder().executionMode(mode).build()
        return engine.newContext().use { ctx -> ctx.eval(script).await(5_000).asString() }
    }

    @Test
    fun forOfStepsGeneratorsAsTheirNextWould() {
        // for-of steps a generator whose next is the intrinsic one without its result objects (Iteration.stepValue):
        // a result yield* passes through is still read as it is (its getters run), a next of the instance's own is
        // called, an exception in the body ends the loop, and the return value is not read
        val src = """
            const log = [];
            function* plain() { yield 1; yield 2; return 'dropped'; }
            function* through() { let k = 0; yield* { [Symbol.iterator]() { return { next() { k++;
                return k > 2 ? { done: true, value: 'end' } : { get value() { log.push('value'); return k * 10 }, done: false } } } } } }
            function* throws() { yield 1; throw new Error('inside'); }
            const own = plain(); own.next = function () { log.push('own next'); return { done: true } };
            const out = [];
            for (const v of plain()) out.push(v);
            for (const v of through()) out.push(v);
            for (const v of own) out.push('never');
            try { for (const v of throws()) out.push(v); } catch (e) { out.push(e.message); }
            out.join(',') + '|' + log.join(',');
        """.trimIndent()
        val expected = "1,2,10,20,1,inside|value,value,own next"
        for (mode in ExecutionMode.values()) {
            val engine = NeonEngine.builder().executionMode(mode).build()
            assertEquals(expected, engine.newContext().use { it.eval(src).asString() }, "$mode")
        }
    }

    @Test
    fun interpretedAndCompiledAgree() {
        assertEquals(expected, runIn(ExecutionMode.INTERPRETER))
        val before = T2.baselines.get()
        assertEquals(expected, runIn(ExecutionMode.COMPILED))
        // (the generators and async functions above, at least, compiled by the optimizing tier's pipeline)
        if (T2.SUSPENDABLE) assertTrue(T2.baselines.get() - before >= 6, "baselines: ${T2.baselines.get() - before} ${T2.baselineReasons.keys}")
        assertEquals(expected, runIn(ExecutionMode.ADAPTIVE))
    }
}
