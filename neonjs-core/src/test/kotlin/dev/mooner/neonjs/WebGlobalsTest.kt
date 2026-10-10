package dev.mooner.neonjs

import dev.mooner.neonjs.ext.WebGlobals
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WebGlobalsTest {
    private fun ctx(policy: SandboxPolicy = SandboxPolicy.UNRESTRICTED, mode: ExecutionMode = ExecutionMode.INTERPRETER) =
        NeonEngine.builder().webGlobals(true).sandbox(policy).executionMode(mode).console(null).build().newContext()

    @Test
    fun timersFireInDueOrder() {
        for (mode in listOf(ExecutionMode.INTERPRETER, ExecutionMode.COMPILED)) ctx(mode = mode).use { c ->
            c.eval("""
                var log = [];
                setTimeout(() => log.push('a'), 40);
                setTimeout((x, y) => log.push('b' + x + y), 10, 1, 2);
                var never = setTimeout(() => log.push('never'), 5);
                clearTimeout(never);
                var n = 0, iv = setInterval(() => { if (++n === 3) clearInterval(iv); log.push('i' + n) }, 60);
                queueMicrotask(() => log.push('micro'));
                log.push('sync');
            """)
            assertTrue(c.runEventLoop(10_000))
            assertEquals("sync,micro,b12,a,i1,i2,i3", c.eval("log.join()").asString(), "mode $mode")
        }
    }

    @Test
    fun eachTaskIsFollowedByItsMicrotasks() {
        ctx().use { c ->
            // both timers are due before the loop takes the first: the first one's microtasks still run before the second
            c.eval("""
                var log = [];
                setTimeout(() => { log.push('t1'); Promise.resolve().then(() => log.push('m1')); queueMicrotask(() => log.push('q1')) }, 0);
                setTimeout(() => log.push('t2'), 0);
                var end = Date.now() + 50; while (Date.now() < end) {}
            """)
            assertTrue(c.runEventLoop(10_000))
            assertEquals("t1,m1,q1,t2", c.eval("log.join()").asString())
        }
    }

    @Test
    fun pendingTimersKeepTheLoopBusyAndAreReleasedOnClose() {
        val before = WebGlobals.scheduledTaskCount
        val c = ctx()
        c.eval("setTimeout(() => {}, 60_000); setInterval(() => {}, 60_000)")
        assertFalse(c.runEventLoop(50), "pending timers")
        assertEquals(before + 2, WebGlobals.scheduledTaskCount)
        c.close()
        assertEquals(before, WebGlobals.scheduledTaskCount, "close cancels the context's timers")
    }

    @Test
    fun timerLimitsAndCallbacks() {
        ctx(SandboxPolicy.builder().maxTimers(3).build()).use { c ->
            assertEquals("RangeError", c.eval("for (var i = 0; i < 3; i++) setTimeout(() => {}, 1000); try { setTimeout(() => {}, 1) } catch (e) { e.name }").asString())
            assertEquals("TypeError", c.eval("try { setTimeout('alert(1)', 1) } catch (e) { e.name }").asString(), "no string callbacks")
            assertEquals("TypeError", c.eval("try { queueMicrotask(1) } catch (e) { e.name }").asString())
        }
        // an endless interval is stopped by the time limit of the loop
        ctx(SandboxPolicy.builder().maxExecutionTime(300).build()).use { c ->
            c.eval("var ticks = 0; setInterval(() => ticks++, 1)")
            assertThrows<NeonTimeoutException> { c.runEventLoop() }
            assertTrue(c.eval("ticks").asInt() > 0)
        }
        // an exception in a timer surfaces to the host; an interval keeps running
        ctx().use { c ->
            c.eval("var k = 0; var iv = setInterval(() => { if (++k === 1) throw new Error('tick'); if (k === 2) clearInterval(iv) }, 1)")
            val e = assertThrows<NeonException> { c.runEventLoop(5_000) }
            assertEquals("Error: tick", e.message)
            assertTrue(c.runEventLoop(5_000))
            assertEquals(2, c.eval("k").asInt())
        }
    }

    @Test
    fun limitsCanApplyToEachTask() {
        val perTaskTime = SandboxPolicy.builder().maxExecutionTime(300).limitsPerTask(true).build()
        ctx(perTaskTime).use { c ->
            // 400 ms of ticks, the wait for a late timer and the late timer's 150 ms of work all fit: each task has its own 300 ms
            c.eval("var ticks = 0, late = 0; var iv = setInterval(() => ticks++, 1); setTimeout(() => { clearInterval(iv); var end = Date.now() + 150; while (Date.now() < end) {} late++ }, 400)")
            assertTrue(c.runEventLoop(5_000))
            assertEquals(1, c.eval("late").asInt())
            // a task running too long is still stopped
            c.eval("setTimeout(() => { for (;;) {} }, 1)")
            assertThrows<NeonTimeoutException> { c.runEventLoop(5_000) }
        }
        val steps = """var done = 0; for (var k = 0; k < 5; k++) setTimeout(() => { for (var i = 0; i < 300000; i++) {} done++ }, 1)"""
        ctx(SandboxPolicy.builder().maxStatements(1_000_000).limitsPerTask(true).build()).use { c ->
            // five tasks of 300,000 iterations each stay within 1,000,000 instructions per task
            c.eval(steps)
            assertTrue(c.runEventLoop(5_000))
            assertEquals(5, c.eval("done").asInt())
        }
        // by default the same tasks share the budget of the runEventLoop call
        ctx(SandboxPolicy.builder().maxStatements(1_000_000).build()).use { c ->
            c.eval(steps)
            assertThrows<NeonResourceLimitException> { c.runEventLoop(5_000) }
        }
        // a new task's budget keeps an interrupt (requested until the loop ends: one before the loop starts is cleared)
        ctx(perTaskTime).use { c ->
            c.eval("setInterval(() => {}, 1)")
            val done = java.util.concurrent.atomic.AtomicBoolean()
            val t = Thread {
                while (!done.get()) {
                    Thread.sleep(20)
                    c.interrupt()
                }
            }
            t.start()
            try {
                assertThrows<NeonInterruptedException> { c.runEventLoop(5_000) }
            } finally {
                done.set(true)
                t.join()
            }
        }
    }

    @Test
    fun textCodecsAndBase64() {
        ctx().use { c ->
            assertEquals("aGVsbG8=,hello,hello,InvalidCharacterError,InvalidCharacterError", c.eval("""
                var r = [btoa('hello'), atob('aGVsbG8='), atob(' aGVs bG8 ')];
                try { btoa('Ā') } catch (e) { r.push(e.name) }
                try { atob('abcde') } catch (e) { r.push(e.name) }
                r.join()
            """).asString())
            assertEquals("97,226,130,172,240,159,152,128,239,191,189", c.eval("Array.from(new TextEncoder().encode('a€😀\\uD800')).join()").asString())
            assertEquals("{\"read\":2,\"written\":4}", c.eval("JSON.stringify(new TextEncoder().encodeInto('a€😀', new Uint8Array(5)))").asString())
            assertEquals("A�B�", c.eval("new TextDecoder().decode(new Uint8Array([0xEF, 0xBB, 0xBF, 0x41, 0xFF, 0x42, 0xE2, 0x82]))").asString())
            assertEquals("|€|", c.eval("var d = new TextDecoder(), b = new TextEncoder().encode('€'); d.decode(b.subarray(0, 1), { stream: true }) + '|' + d.decode(b.subarray(1), { stream: true }) + '|' + d.decode()").asString())
            assertEquals("TypeError,RangeError", c.eval("""
                var r = [];
                try { new TextDecoder('utf-8', { fatal: true }).decode(new Uint8Array([0xC0])) } catch (e) { r.push(e.name) }
                try { new TextDecoder('windows-1252') } catch (e) { r.push(e.name) }
                r.join()
            """).asString())
            assertEquals("héllo", c.eval("new TextDecoder().decode(new TextEncoder().encode('héllo').buffer)").asString())
        }
    }

    @Test
    fun structuredCloneAndDOMException() {
        for (mode in listOf(ExecutionMode.INTERPRETER, ExecutionMode.COMPILED)) ctx(mode = mode).use { c ->
            assertEquals("true,true,5,xgi,v,true,q,10,w,false,3,x,1|2|3,true,true,bad", c.eval("""
                const o = { d: new Date(5), r: /x/gi, m: new Map([[1, { k: 'v' }]]), s: new Set(['q']), big: 10n, str: new String('w'),
                    arr: [1, , 3], u8: new Uint8Array([1, 2, 3]), err: new RangeError('bad') };
                o.self = o; o.arr.extra = 'x'; o.view = new DataView(o.u8.buffer, 1);
                const k = structuredClone(o);
                [k !== o, k.self === k, k.d.getTime(), k.r.source + k.r.flags, k.m.get(1).k, k.m.get(1) !== o.m.get(1), [...k.s][0], k.big,
                 String(k.str), 1 in k.arr, k.arr.length, k.arr.extra, Array.from(k.u8).join('|'), k.view.buffer === k.u8.buffer,
                 k.err instanceof RangeError, k.err.message].join()
            """).asString(), "mode $mode")
            assertEquals("DataCloneError:25,DataCloneError:25,DataCloneError:25,DataCloneError:25,DataCloneError:25", c.eval("""
                [() => {}, Symbol('s'), new WeakMap(), Promise.resolve(), new Proxy({}, {})].map(v => {
                    try { structuredClone(v); return 'cloned' } catch (e) { return e instanceof DOMException && e.name + ':' + e.code }
                }).join()
            """).asString())
            assertEquals("0,true,8", c.eval("var b = new ArrayBuffer(8), t = structuredClone(b, { transfer: [b] }); [b.byteLength, b.detached, t.byteLength].join()").asString())
            assertEquals("AbortError: m,20,true,true", c.eval("var de = new DOMException('m', 'AbortError'); [String(de), de.code, de instanceof Error, Error.isError(de)].join()").asString())
            // nesting deeper than the clone depth limit is a RangeError, not a host stack overflow
            assertEquals("RangeError", c.eval("var d = [], cur = d; for (var i = 0; i < 100000; i++) { var n = []; cur.push(n); cur = n } try { structuredClone(d) } catch (e) { e.name }").asString())
        }
    }

    @Test
    fun offByDefaultAndAbsentFromShadowRealms() {
        val names = "[typeof setTimeout, typeof TextEncoder, typeof queueMicrotask, typeof EventTarget, typeof Event, typeof AbortController, " +
            "typeof AbortSignal, typeof Performance, typeof Crypto, typeof QuotaExceededError, typeof Blob, typeof File, typeof performance, " +
            "typeof crypto].join()"
        NeonEngine.builder().console(null).build().newContext().use { c ->
            assertEquals(List(14) { "undefined" }.joinToString(","), c.eval(names).asString())
        }
        ctx().use { c ->
            assertEquals(List(12) { "function" }.plus(listOf("object", "object")).joinToString(","), c.eval(names).asString())
            assertEquals(List(14) { "undefined" }.joinToString(","), c.eval("new ShadowRealm().evaluate('$names')").asString())
        }
    }

    @Test
    fun performanceAndCrypto() {
        ctx().use { c ->
            assertEquals("true,true,true,true", c.eval("""
                var a = performance.now(), b = performance.now();
                [a >= 0 && b >= a, Math.abs(performance.timeOrigin - Date.now()) < 60000,
                 /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}${'$'}/.test(crypto.randomUUID()),
                 crypto.getRandomValues(new Uint8Array(64)).some(x => x !== 0)].join()
            """).asString())
        }
        // a deterministic sandbox: a still clock, and reproducible random values
        fun run(): String = ctx(SandboxPolicy.builder().deterministic(42, 1000).build()).use { c ->
            c.eval("[performance.now(), performance.timeOrigin, crypto.randomUUID(), crypto.getRandomValues(new Uint32Array(2)).join('-')].join()").asString()
        }
        val r = run()
        assertTrue(r.startsWith("0,1000,"), r)
        assertEquals(r, run())
    }

    @Test
    fun blobs() {
        ctx().use { c ->
            assertEquals("4,text/plain,bc,x.txt,5,true,abcd,TypeError", c.eval("""
                (async () => {
                    const b = new Blob(['ab', new Uint8Array([99]), new Blob(['d'])], { type: 'Text/Plain' });
                    const f = new File([b], 'x.txt', { lastModified: 5 });
                    const clone = structuredClone(f);
                    // a promise-returning operation rejects rather than throws
                    const bad = await Blob.prototype.text.call({}).catch(e => e.name);
                    return [b.size, b.type, await b.slice(1, 3).text(), f.name, f.lastModified, clone instanceof File, await clone.text(), bad].join();
                })()
            """).await(5_000).asString())
        }
        // the size of a blob is charged to the allocation budget before its bytes are allocated
        ctx(SandboxPolicy.builder().maxAllocatedBytes(16L shl 20).build()).use { c ->
            assertThrows<NeonResourceLimitException> { c.eval("const mb = new Blob([new Uint8Array(1 << 20)]); new Blob(Array(64).fill(mb))") }
        }
    }

    @Test
    fun listenerExceptionsAreReportedAndTheDispatchGoesOn() {
        ctx().use { c ->
            val seen = ArrayList<String>()
            c.setUncaughtErrorHandler { e, _ -> seen.add(e.message ?: "") }
            assertEquals("a,c,true", c.eval("""
                var log = [], t = new EventTarget();
                t.addEventListener('x', () => log.push('a'));
                t.addEventListener('x', () => { throw new Error('in b') });
                t.addEventListener('x', { handleEvent() { log.push('c') } });
                var r = t.dispatchEvent(new Event('x'));
                log.push(r);
                log.join()
            """).asString())
            assertEquals(listOf("Error: in b"), seen)
        }
    }

    @Test
    fun abortSignals() {
        ctx().use { c ->
            assertEquals("abort:AbortError,handler,true,AbortError,TypeError", c.eval("""
                var log = [], ac = new AbortController(), s = ac.signal;
                s.addEventListener('abort', e => log.push(e.type + ':' + s.reason.name));
                s.onabort = () => log.push('handler');
                var any = AbortSignal.any([s, new AbortController().signal]);
                ac.abort();
                log.push(any.aborted, any.reason.name);
                try { new AbortSignal() } catch (e) { log.push(e.name) }
                log.join()
            """).asString())
            // AbortSignal.timeout fires as a task, and does not keep the loop waiting
            c.eval("var timedOut = null; AbortSignal.timeout(50).onabort = e => { timedOut = e.target.reason.name }")
            assertTrue(c.runEventLoop(5_000))
            assertEquals("null", c.eval("String(timedOut)").asString(), "the loop ended without waiting for the timeout")
            Thread.sleep(150)
            c.runJobs()
            assertEquals("TimeoutError", c.eval("timedOut").asString())
        }
        // a dependent signal held by its onabort alone still fires: its sources keep it
        ctx().use { c ->
            c.eval("var fired = false, ac = new AbortController(); AbortSignal.any([ac.signal]).onabort = () => { fired = true }")
            repeat(3) {
                System.gc()
                Thread.sleep(20)
            }
            c.eval("ac.abort()")
            assertTrue(c.eval("fired").asBoolean())
        }
        // and counts against the limit of pending timers
        ctx(SandboxPolicy.builder().maxTimers(2).build()).use { c ->
            assertEquals("RangeError", c.eval("setTimeout(() => {}, 1000); AbortSignal.timeout(1000); try { AbortSignal.timeout(1000) } catch (e) { e.name }").asString())
        }
    }
}
