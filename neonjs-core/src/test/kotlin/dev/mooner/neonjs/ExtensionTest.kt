package dev.mooner.neonjs

import dev.mooner.neonjs.ext.WebGlobals
import dev.mooner.neonjs.runtime.*
import dev.mooner.neonjs.vm.Modules
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** What engine modules (neonjs-node) build on: extensions, built-in modules, the tick lane, timers, stack capture. */
class ExtensionTest {
    private var created = 0

    /** An extension with a global `tick(fn)`, a built-in `node:demo` / `demo`, and a global `stackAbove(fn)`. */
    private val demo = NeonExtension { realm ->
        realm.globalObject.defineOwn("tick", NativeFunction(realm, "tick", 1, { f, _, a, _ ->
            val fn = a[0]
            f.realm.agent.enqueueTick { Ops.call(fn, Undefined, EMPTY_ARGS) }
            Undefined
        }), Attr.WC)
        realm.globalObject.defineOwn("hostReq", NativeFunction(realm, "hostReq", 1, { f, _, a, _ ->
            Modules.hostModuleObject(f.realm, Ops.toString(a[0])) ?: Undefined
        }), Attr.WC)
        realm.globalObject.defineOwn("stackAbove", NativeFunction(realm, "stackAbove", 1, { f, _, a, _ ->
            f.realm.agent.captureStack(a[0] as JSObject, 10)
        }), Attr.WC)
        Modules.defineBuiltin(realm, Modules.Builtin("node:demo") {
            created++
            val o = JSObject(realm.objectPrototype)
            o.createDataProperty("answer", 42.0)
            o
        }, "demo")
    }

    private fun ctx(webGlobals: Boolean = false) =
        NeonEngine.builder().console(null).webGlobals(webGlobals).extension(demo).build().newContext()

    @Test
    fun builtInModulesResolveWithoutAHostLoader() {
        ctx().use { c ->
            val ns = c.evalModule("import d, { answer } from 'node:demo'; import d2 from 'demo'; export const r = [answer, d.answer, d === d2]", "main.mjs")
            assertEquals("42,42,true", ns.getMember("r").toString())
            assertEquals(1, created, "the exports are made once")
            // a ShadowRealm gets no built-in modules
            assertTrue(c.eval("new ShadowRealm().importValue('node:demo', 'answer').then(() => 'loaded', e => e.name)").await(5_000).asString() == "TypeError")
        }
        // the host's own modules come first (defined before the first import: a loaded module stays), for require too
        ctx().use { c ->
            c.defineModule("node:demo", mapOf("answer" to 7))
            assertEquals(7, c.evalModule("import { answer } from 'node:demo'; export const a = answer", "other.mjs").getMember("a").asInt())
            assertEquals("7,true", c.eval("[hostReq('node:demo').answer, hostReq('node:demo') === hostReq('node:demo')].join()").asString())
        }
    }

    @Test
    fun ticksRunBeforePromiseJobsAtEachCheckpoint() {
        ctx().use { c ->
            // as process.nextTick in Node: t1, then the promise jobs (p1, p2), then the tick a promise job queued (t2)
            c.eval("""
                var log = [];
                Promise.resolve().then(() => { log.push('p1'); tick(() => log.push('t2')) });
                tick(() => { log.push('t1'); Promise.resolve().then(() => log.push('p2')) });
            """)
            assertEquals("t1,p1,p2,t2", c.eval("log.join()").asString())
        }
    }

    @Test
    fun aStackCanLeaveOutTheFramesAboveAFunction() {
        ctx().use { c ->
            val s = c.eval("""
                function inner() { return stackAbove(make) }
                function make() { return inner() }
                function outer() { return make() }
                outer()
            """).asString()
            assertFalse(s.contains("inner") || s.contains("at make"), s)
            assertTrue(s.contains("at outer"), s)
            assertEquals("", c.eval("stackAbove(function notOnTheStack() {})").asString())
        }
    }

    @Test
    fun timersCanStopKeepingTheLoopWaiting() {
        ctx(webGlobals = true).use { c ->
            var ran = false
            val agent = c.agent
            val realm = c.realm
            val task = c.call { WebGlobals.timersOf(realm)!!.task(300, keepsAlive = true) { ran = true } }!!
            agent.setKeepsAlive(task, false)
            val start = System.nanoTime()
            assertTrue(c.runEventLoop(5_000))
            assertTrue((System.nanoTime() - start) / 1_000_000 < 200, "the loop did not wait for the task")
            assertFalse(ran)
            agent.setKeepsAlive(task, true)
            assertTrue(c.runEventLoop(5_000))
            assertTrue(ran)
        }
    }

    @Test
    fun cancelledTasksLeaveTheLoopAndTheLimit() {
        NeonEngine.builder().console(null).webGlobals(true).sandbox(SandboxPolicy.builder().maxTimers(10).build()).build().newContext().use { c ->
            val realm = c.realm
            var ran = 0
            c.call {
                val timers = WebGlobals.timersOf(realm)!!
                // a withdrawn task neither keeps the loop waiting nor holds one of the 10 slots
                repeat(20_000) { timers.cancel(timers.task(60_000, keepsAlive = true) { ran++ }!!) }
                // withdrawn after the scheduler posted its job: it does not run, and is counted out once
                val late = timers.task(0, keepsAlive = true) { ran++ }!!
                Thread.sleep(100)
                timers.cancel(late)
            }
            val start = System.nanoTime()
            assertTrue(c.runEventLoop(5_000))
            assertTrue((System.nanoTime() - start) / 1_000_000 < 200, "the loop did not wait")
            assertEquals(0, ran)
            // all 10 slots are free again
            c.call {
                val timers = WebGlobals.timersOf(realm)!!
                repeat(10) { timers.task(0, keepsAlive = true) { ran++ } }
            }
            assertTrue(c.runEventLoop(5_000))
            assertEquals(10, ran)
        }
    }

    @Test
    fun anInterceptorSeesUncaughtErrorsBeforeTheHost() {
        // the interceptor handles errors whose message starts with "mine", and lets the others through
        val intercepted = ArrayList<String>()
        val ext = NeonExtension { realm ->
            realm.agent.uncaughtInterceptor = { e, p ->
                val v = if (e is JSException) e.value else p?.result
                val m = (v as? JSObject)?.get("message", v)?.toString() ?: ""
                if (m.startsWith("mine")) {
                    intercepted.add((if (e != null) "exception " else "rejection ") + m)
                    true
                } else false
            }
        }
        val engine = NeonEngine.builder().console(null).webGlobals(true).extension(ext).build()
        // no host handler: what the interceptor handles does not end the loop
        engine.newContext().use { c ->
            c.eval("setTimeout(() => { throw new Error('mine 1') }, 1); setTimeout(() => { globalThis.after = true }, 20); Promise.reject(new Error('mine 2'))")
            assertTrue(c.runEventLoop(5_000))
            assertTrue(c.eval("after").asBoolean())
            assertEquals(listOf("rejection mine 2", "exception mine 1"), intercepted)
            // and what it does not handle propagates, as without it
            c.eval("setTimeout(() => { throw new Error('other') }, 1)")
            assertThrows<NeonException> { c.runEventLoop(5_000) }
        }
        // with a host handler: the host gets what the interceptor did not handle
        intercepted.clear()
        engine.newContext().use { c ->
            val host = ArrayList<String>()
            c.setUncaughtErrorHandler { e, _ -> host.add(e.message ?: "") }
            c.eval("Promise.reject(new Error('mine 3')); Promise.reject(new Error('yours'))")
            assertEquals(listOf("rejection mine 3"), intercepted)
            assertEquals(listOf("Error: yours"), host)
        }
    }

    @Test
    fun extensionsAreInstalledInOrderAfterTheWebGlobals() {
        val seen = ArrayList<String>()
        NeonEngine.builder().console(null).webGlobals(true)
            .extension { realm -> seen.add("a:" + (realm.globalObject.getOwnProperty("URL") != null)) }
            .extension { seen.add("b") }
            .build().newContext().close()
        assertEquals(listOf("a:true", "b"), seen)
        assertThrows<IllegalStateException> {
            NeonEngine.builder().console(null).extension { throw IllegalStateException("needs the web globals") }.build().newContext()
        }
    }
}
