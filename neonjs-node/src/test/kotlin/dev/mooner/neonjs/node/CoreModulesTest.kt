package dev.mooner.neonjs.node

import dev.mooner.neonjs.NeonContext
import dev.mooner.neonjs.NeonEngine
import dev.mooner.neonjs.NeonException
import dev.mooner.neonjs.NeonInterruptedException
import dev.mooner.neonjs.SandboxPolicy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** node:events, node:process, node:timers and node:timers/promises. */
class CoreModulesTest {
    private fun ctx(options: NodeOptions = NodeOptions.DEFAULT, policy: SandboxPolicy = SandboxPolicy.UNRESTRICTED): NeonContext =
        NeonEngine.builder().console(null).webGlobals(true).sandbox(policy).extension(NodeExtension(options)).build().newContext()

    @Test
    fun eventEmitters() {
        ctx().use { c ->
            assertEquals("a1,b1,a2,b2,once,2,x|y,true,1", c.eval("""
                const EventEmitter = require('events');
                const e = new EventEmitter(), log = [];
                const a = (v) => log.push('a' + v);
                e.on('x', a);
                e.on('x', (v) => log.push('b' + v));
                e.emit('x', 1);
                e.off('x', a);
                e.prependListener('x', a);
                e.once('x', () => log.push('once'));
                e.removeAllListeners('y');
                e.on('y', () => {});
                e.emit('x', 2);
                // the once listener is gone, a and b remain
                [log.join(), e.listenerCount('x'), e.eventNames().join('|'), e.emit('y'), e.listeners('y').length].join()
            """).asString())
            // 'error' without a listener throws; a non-Error is wrapped
            assertEquals("boom,ERR_UNHANDLED_ERROR", c.eval("""
                const E = require('node:events'), r = [];
                try { new E().emit('error', new Error('boom')) } catch (e) { r.push(e.message) }
                try { new E().emit('error', 'text') } catch (e) { r.push(e.code) }
                r.join()
            """).asString())
            // old-style subclasses and errorMonitor
            assertEquals("true,monitored", c.eval("""
                function Old() { E.call(this) }
                Object.setPrototypeOf(Old.prototype, E.prototype);
                const o = new Old(); let seen;
                o.on(E.errorMonitor, (err) => { seen = 'monitored' });
                o.on('error', () => {});
                o.emit('error', new Error('x'));
                [o instanceof E, seen].join()
            """).asString())
        }
    }

    @Test
    fun eventsHelpersAndWarnings() {
        ctx().use { c ->
            val r = c.eval("""
                (async () => {
                    const { once, EventEmitter } = require('events');
                    const process = require('process');
                    const e = new EventEmitter(), out = [];
                    setTimeout(() => e.emit('ready', 1, 2), 1);
                    out.push((await once(e, 'ready')).join('+'));
                    const ac = new AbortController();
                    const p = once(e, 'never', { signal: ac.signal });
                    ac.abort();
                    out.push(await p.catch((err) => err.name + ':' + err.code));
                    // more than 10 listeners: a MaxListenersExceededWarning on the process
                    const warned = new Promise((resolve) => process.once('warning', (w) => resolve(w.name)));
                    for (let i = 0; i < 11; i++) e.on('many', () => {});
                    out.push(await warned);
                    // a listener's rejection becomes an 'error' with captureRejections
                    const cr = new EventEmitter({ captureRejections: true });
                    const failed = new Promise((resolve) => cr.on('error', (err) => resolve(err.message)));
                    cr.on('go', async () => { throw new Error('async fail') });
                    cr.emit('go');
                    out.push(await failed);
                    return out.join();
                })()
            """).await(5_000).asString()
            assertEquals("1+2,AbortError:ABORT_ERR,MaxListenersExceededWarning,async fail", r)
        }
    }

    @Test
    fun processShowsOnlyWhatTheHostGives() {
        val options = NodeOptions.builder().env(mapOf("TOKEN" to "t")).argv(listOf("neonjs", "bot.js")).cwd("/app").platform("android").build()
        ctx(options).use { c ->
            assertEquals("t,undefined,bot.js,android,v26.11.1,26.11.1,true,/app,/app/sub,process", c.eval("""
                const process = require('node:process');
                process.chdir('sub');
                [process.env.TOKEN, String(process.env.HOME), process.argv[1], process.platform, process.version, process.versions.node,
                 typeof process.versions.neonjs === 'string', '/app', process.cwd(), Object.prototype.toString.call(process).slice(8, -1)].join()
            """).asString())
            // path follows the platform
            assertEquals("true,/app/sub/x", c.eval("[require('path') === require('path').posix, require('path').resolve('x')].join()").asString())
            // import gives the same object, and there is still no process global
            val ns = c.evalModule("import process, { nextTick } from 'node:process'; export const r = [process === require('process'), typeof nextTick, typeof globalThis.process]", "m.mjs")
            assertEquals("true,function,undefined", ns.getMember("r").toString())
        }
    }

    @Test
    fun theDefaultsFollowTheHostsPlatform() {
        // the host's platform and its rules, with that platform's root as the cwd (not the host's own) and an empty env
        val os = System.getProperty("os.name", "").lowercase()
        val windows = os.startsWith("windows")
        val platform = if (windows) "win32" else if (os.startsWith("mac")) "darwin" else "linux"
        ctx().use { c ->
            val expected = if (windows) "win32,0,C:\\,\\,C:\\config.json" else "$platform,0,/,/,/config.json"
            assertEquals(expected, c.eval("""
                const p = require('process'), path = require('path');
                [p.platform, Object.keys(p.env).length, p.cwd(), path.sep, path.resolve('config.json')].join()
            """).asString())
        }
        // a platform named without a cwd gets that platform's root
        ctx(NodeOptions.builder().platform("win32").build()).use { c ->
            assertEquals("C:\\sub", c.eval("const p2 = require('process'); p2.chdir('sub'); p2.cwd()").asString())
        }
    }

    @Test
    fun nextTickRunsBeforePromiseJobs() {
        ctx().use { c ->
            c.eval("""
                var log = [];
                const process = require('process');
                Promise.resolve().then(() => { log.push('p1'); process.nextTick(() => log.push('t2')) });
                process.nextTick((a, b) => { log.push('t1' + a + b); Promise.resolve().then(() => log.push('p2')) }, 'x', 'y');
            """)
            assertEquals("t1xy,p1,p2,t2", c.eval("log.join()").asString())
        }
    }

    @Test
    fun exitEndsTheScriptNotTheHost() {
        var code = -1
        ctx(NodeOptions.builder().onExit { code = it }.build()).use { c ->
            // an 'exit' listener that exits again does not recurse
            c.eval("var exits = []; const p = require('process'); p.on('exit', (c) => { exits.push(c); p.exit(4) })")
            assertThrows<NeonInterruptedException> { c.eval("require('process').exit(3); globalThis.after = true") }
            assertEquals(4, code)
            assertEquals("3,undefined", c.eval("[exits.join(), typeof after].join()").asString())
        }
    }

    @Test
    fun uncaughtListenersCatchWhatTheScriptLeaves() {
        ctx().use { c ->
            c.eval("""
                var seen = [];
                const process = require('process');
                process.on('uncaughtException', (err, origin) => seen.push(err.message + '/' + origin));
                process.on('unhandledRejection', (reason) => seen.push('rejected ' + reason.message));
                setTimeout(() => { throw new Error('in a timer') }, 1);
                setTimeout(() => seen.push('later'), 20);
                Promise.reject(new Error('no handler'));
            """)
            assertTrue(c.runEventLoop(5_000))
            assertEquals("rejected no handler,in a timer/uncaughtException,later", c.eval("seen.join()").asString())
        }
        // without listeners an exception ends the loop as before, and the host's handler gets what no listener took
        ctx().use { c ->
            val host = ArrayList<String>()
            c.setUncaughtErrorHandler { e, _ -> host.add(e.message ?: "") }
            c.eval("require('process').on('unhandledRejection', () => {}); Promise.reject(new Error('taken')); setTimeout(() => { throw new Error('left') }, 1)")
            assertTrue(c.runEventLoop(5_000))
            assertEquals(listOf("Error: left"), host)
        }
        ctx().use { c ->
            // the timer may already fire while the evaluation runs its jobs (on a slow machine)
            assertThrows<NeonException> {
                c.eval("setTimeout(() => { throw new Error('nobody') }, 1)")
                c.runEventLoop(5_000)
            }
        }
    }

    @Test
    fun timersAreNodes() {
        ctx().use { c ->
            c.eval("""
                var log = [];
                const t = setTimeout(() => log.push('cancelled'), 5);
                clearTimeout(+t);
                const n = setInterval(() => { log.push('i'); if (log.filter((x) => x === 'i').length === 3) clearInterval(n) }, 2);
                setImmediate((a) => log.push('immediate' + a), 1);
                const kept = setTimeout(() => log.push('refreshed'), 30);
                setTimeout(() => kept.refresh(), 15);
                var types = [typeof t, typeof t.ref, t.hasRef(), t.unref().hasRef(), setTimeout === require('timers').setTimeout].join();
            """)
            assertTrue(c.runEventLoop(5_000))
            assertEquals("object,function,true,false,true", c.eval("types").asString())
            assertEquals("immediate1,i,i,i,refreshed", c.eval("log.join()").asString())
            // an unref'd timer does not keep the loop waiting
            c.eval("var late = false; setTimeout(() => { late = true }, 300).unref()")
            val start = System.nanoTime()
            assertTrue(c.runEventLoop(5_000))
            assertTrue((System.nanoTime() - start) / 1_000_000 < 200)
            assertFalse(c.eval("late").asBoolean())
        }
        // Node's timers count against maxTimers too
        ctx(policy = SandboxPolicy.builder().maxTimers(2).build()).use { c ->
            assertEquals("RangeError", c.eval("setTimeout(() => {}, 1000); setInterval(() => {}, 1000); try { setImmediate(() => {}) } catch (e) { e.name }").asString())
        }
    }

    @Test
    fun timerPromises() {
        ctx().use { c ->
            val r = c.eval("""
                (async () => {
                    const { setTimeout: sleep, setInterval, setImmediate, scheduler } = require('timers/promises');
                    const out = [await sleep(5, 'slept'), await setImmediate('now')];
                    const ac = new AbortController();
                    const p = sleep(1000, 'never', { signal: ac.signal });
                    ac.abort();
                    out.push(await p.catch((e) => e.name + ':' + e.code));
                    let n = 0;
                    for await (const v of setInterval(2, 'tick')) { if (++n === 3) break }
                    out.push(n);
                    await scheduler.wait(1);
                    return out.join();
                })()
            """).await(5_000).asString()
            assertEquals("slept,now,AbortError:ABORT_ERR,3", r)
        }
    }

    @Test
    fun node26Process() {
        ctx().use { c ->
            assertEquals("true,,,function,object", c.eval("""
                const process = require('process');
                [process.getBuiltinModule('node:fs') === require('fs'), process.getBuiltinModule('left-pad'),
                 process.noDeprecation, typeof process.availableMemory, typeof process.finalization].join()
            """).asString())
            // a capture callback takes what is left uncaught before 'uncaughtException' listeners do
            c.eval("""
                var captured = [];
                const p = require('process');
                p.setUncaughtExceptionCaptureCallback((err) => captured.push(err.message));
                p.on('uncaughtException', () => captured.push('listener'));
                setTimeout(() => { throw new Error('caught first') }, 1);
            """)
            assertTrue(c.runEventLoop(5_000))
            assertEquals("caught first,true", c.eval("[captured.join(), p.hasUncaughtExceptionCaptureCallback()].join()").asString())
        }
        // finalization callbacks run at exit for what is still alive
        var code = -1
        ctx(NodeOptions.builder().onExit { code = it }.build()).use { c ->
            c.eval("var fin = []; const keep = {}; require('process').finalization.register(keep, (obj, event) => fin.push(event))")
            assertThrows<NeonInterruptedException> { c.eval("require('process').exit(0)") }
            assertEquals("exit", c.eval("fin.join()").asString())
        }
    }
    // expectations checked against Node v26.11.1
    @Test
    fun asyncHooksWithoutTracking() {
        ctx().use { c ->
            // every module of node:events, as an ES module: EventEmitterAsyncResource is node:async_hooks' AsyncResource
            val ns = c.evalModule("""
                import * as events from 'node:events';
                import { AsyncResource, AsyncLocalStorage, createHook } from 'node:async_hooks';
                const r = new AsyncResource('X');
                const bound = AsyncResource.bind(function f(a, b) { return [this.k, a, b].join('-') });
                const ee = new events.EventEmitterAsyncResource({ name: 'E' });
                const codes = [];
                for (const f of [() => new AsyncResource(1), () => r.bind(1), () => new AsyncResource('X', { triggerAsyncId: -2 }),
                                 () => new AsyncLocalStorage(), () => createHook({})]) {
                    try { f() } catch (e) { codes.push(e.code) }
                }
                export const out = [typeof events.EventEmitter, typeof r.asyncId(), r.emitDestroy() === r, bound.length, bound.name,
                    bound.call({ k: 1 }, 2, 3), r.runInAsyncScope(function (x) { return this.v + x }, { v: 9 }, 8),
                    typeof ee.asyncId, ee.asyncResource.constructor.name, codes.join(' ')].join();
            """, "m.mjs")
            assertEquals("function,number,true,2,bound,1-2-3,17,number,EventEmitterReferencingAsyncResource," +
                "ERR_INVALID_ARG_TYPE ERR_INVALID_ARG_TYPE ERR_INVALID_ASYNC_ID ERR_UNAVAILABLE ERR_UNAVAILABLE", ns.getMember("out").toString())
        }
    }
}
