package io.neonjs

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

class Secret {
    @JvmField val password = "hunter2"
    fun reveal() = password
}

class Swallower {
    /** Badly behaved host code: swallows every runtime exception thrown by the callback. */
    fun run(r: Runnable) {
        try {
            r.run()
        } catch (e: RuntimeException) {
            // ignored
        }
    }
}

class SecurityTest {
    private fun ctx(policy: SandboxPolicy = SandboxPolicy.STRICT, access: HostAccess = HostAccess.ALL, mode: ExecutionMode = ExecutionMode.INTERPRETER) =
        NeonEngine.builder().sandbox(policy).hostAccess(access).executionMode(mode).build().newContext()

    /** Found by the Test262 mutation fuzzer: built-in loops over huge array-likes that call no JS must still stop. */
    @Test
    @Timeout(60, unit = TimeUnit.SECONDS)
    fun builtinLoopsOverHugeArrayLikesAreInterruptible() {
        val policy = SandboxPolicy.builder().maxExecutionTime(300).build()
        val cases = listOf(
            "Array.prototype.indexOf.call({ length: 2 ** 53 - 1 }, 1)",
            "Array.prototype.some.call({ 0: 11, length: 'Infinity' }, v => v > 65535)",
            "Array.prototype.reduceRight.call({ length: 2 ** 53 - 1, [2 ** 53 - 2]: 1 }, (a, b) => a)",
            "var a = []; a.length = 2 ** 32 - 1; a.join('')",
            "Array(2 ** 32 - 1).sort()",
            "JSON.stringify({}, new Proxy([], { get: (t, k) => k === 'length' ? 2 ** 32 - 1 : undefined }))",
            "String.raw({ raw: { length: 2 ** 53 - 1 } })",
            "var re = /a/; re.exec = function () { if (this.done) return null; this.done = true; return { length: 2 ** 53 - 1, 0: 'a', index: 0 } }; 'a'.replace(re, 'b')",
        )
        for (mode in listOf(ExecutionMode.INTERPRETER, ExecutionMode.COMPILED)) for (code in cases) ctx(policy, mode = mode).use { c ->
            val start = System.nanoTime()
            assertThrows<NeonTerminatedException>("$mode: $code") { c.eval(code) }
            assertTrue(System.nanoTime() - start < 10_000_000_000L, "$mode: stopped late: $code")
        }
    }

    /** Inputs found by the fuzzer that used to fail with engine-internal exceptions. */
    @Test
    fun fuzzerRegressions() {
        ctx().use { c ->
            assertThrows<NeonSyntaxException> { c.eval("\$v/[@@]/v;") }
            assertThrows<NeonSyntaxException> { c.eval("@ 1 class C {}") }
            assertEquals(2, c.eval("1 + 1").asInt())
        }
    }

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun deeplyNestedSourceDoesNotCrash() {
        ctx().use { c ->
            val deep = "(".repeat(100_000) + "1" + ")".repeat(100_000)
            val e = assertThrows<NeonException> { c.eval(deep) }
            assertTrue(e.message!!.contains("stack", ignoreCase = true) || e.message!!.contains("Syntax"), e.message)
            val arrays = "[".repeat(100_000) + "]".repeat(100_000)
            assertThrows<NeonException> { c.eval(arrays) }
            // the context is still usable
            assertEquals(2, c.eval("1 + 1").asInt())
        }
    }

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun waitAsyncTimeoutsResolveOnTheOwningContext() {
        ctx().use { c ->
            c.eval("var r = 'pending'; Atomics.waitAsync(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 20).value.then(v => { r = v })")
            assertEquals(1, c.agent.externalSourceCount)
            // the timer thread only posts the timeout job: nothing ran until the context drains its jobs again
            Thread.sleep(100)
            assertEquals("pending", c.eval("r").asString())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (c.agent.externalSourceCount > 0 && System.nanoTime() < deadline) {
                Thread.sleep(10)
                c.runJobs()
            }
            assertEquals("timed-out", c.eval("r").asString())
            assertEquals(0, c.agent.externalSourceCount)
        }
    }

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun waitAsyncWaitersAreCappedAndWithdrawnOnClose() {
        val c = ctx()
        val r = c.eval("var ia = new Int32Array(new SharedArrayBuffer(8)); var n = 0;" +
            "try { for (;;) { Atomics.waitAsync(ia, 0, 0, 1e7); n++ } } catch (e) { e.name + ' ' + n }").asString()
        assertEquals("RangeError 10000", r)
        assertEquals(10_000, c.agent.externalSourceCount)
        c.close()
        assertEquals(0, c.agent.externalSourceCount)
        assertFalse(c.agent.hasPendingExternal())
    }

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun runawayRecursionInsideJsIsCatchable() {
        ctx().use { c ->
            assertEquals("RangeError", c.eval("function f(){ return f() } try { f() } catch (e) { e.name }").asString())
            assertEquals("RangeError", c.eval("var o = {}; o.toString = function () { return String(this) }; try { String(o) } catch (e) { e.name }").asString())
            assertEquals("ok", c.eval("var a = []; a[0] = a; try { JSON.stringify(a) } catch (e) { 'ok' }").asString())
        }
    }

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun hugeAllocationsAreRejected() {
        val p = SandboxPolicy.builder().maxStringLength(10_000_000).maxExecutionTime(5000).build()
        ctx(p).use { c ->
            assertEquals("RangeError", c.eval("try { 'x'.repeat(2 ** 31) } catch (e) { e.name }").asString())
            assertEquals("RangeError", c.eval("try { 'abc'.padEnd(2 ** 40) } catch (e) { e.name }").asString())
            assertEquals("RangeError", c.eval("try { new Array(2 ** 32) } catch (e) { e.name }").asString())
            assertEquals("RangeError", c.eval("try { var s = 'ab'; while (true) s = s + s } catch (e) { e.name }").asString())
            assertEquals("RangeError", c.eval("try { Array(1e6).join('x'.repeat(100)) } catch (e) { e.name }").asString())
        }
    }

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun allocationBudget() {
        val p = SandboxPolicy.builder().maxAllocatedBytes(64L * 1024 * 1024).build()
        ctx(p).use { c ->
            assertThrows<NeonResourceLimitException> { c.eval("var keep = []; for (;;) keep.push({a: 1, b: [1, 2, 3]})") }
        }
    }

    @Test
    fun contextsDoNotShareMutableState() {
        val engine = NeonEngine.builder().build()
        val a = engine.newContext()
        val b = engine.newContext()
        a.eval("Object.prototype.polluted = 1; Array.prototype.push = null; JSON.parse = () => 'evil'")
        assertEquals("undefined", b.eval("typeof ({}).polluted").asString())
        assertEquals(1, b.eval("[].push(1)").asInt())
        assertEquals(1, b.eval("JSON.parse('1')").asInt())
    }

    @Test
    fun hostReflectionIsUnreachable() {
        ctx(SandboxPolicy.UNRESTRICTED).use { c ->
            c["s"] = Secret()
            assertEquals("hunter2", c.eval("s.reveal()").asString())
            for (expr in listOf("s.getClass", "s.class", "s.wait", "s.notify", "s.constructor.constructor('return 1').getClass")) {
                assertEquals("undefined", c.eval("try { typeof $expr } catch (e) { 'undefined' }").asString(), expr)
            }
            // Function constructor reached through host objects is the JS one, still governed by policy
            assertEquals("function", c.eval("typeof s.constructor").asString())
            assertEquals("undefined", c.eval("typeof Java").asString())
            assertEquals("undefined", c.eval("typeof Packages").asString())
        }
    }

    @Test
    fun noHostAccessMeansOpaque() {
        ctx(access = HostAccess.NONE).use { c ->
            c["s"] = Secret()
            assertEquals("undefined", c.eval("typeof s.reveal").asString())
            assertEquals("undefined", c.eval("typeof s.password").asString())
            assertEquals(0, c.eval("Object.keys(s).length").asInt())
        }
    }

    @Test
    fun deniedClassesStayDenied() {
        val access = HostAccess.builder(HostAccess.Level.ALL).allowLookup { true }.build()
        val p = SandboxPolicy.builder().exposeJavaGlobal(true).build()
        ctx(p, access).use { c ->
            for (cls in listOf("java.lang.Class", "java.lang.System", "java.lang.Runtime", "java.lang.ProcessBuilder", "java.lang.Thread",
                "java.lang.reflect.Method", "java.lang.invoke.MethodHandles", "sun.misc.Unsafe", "java.lang.ClassLoader")) {
                assertThrows<NeonException>(cls) { c.eval("Java.type('$cls')") }
            }
            // an allowed class still works
            assertEquals(3, c.eval("Java.type('java.lang.Math').max(1, 3)").asInt())
        }
    }

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun terminationIsNotInterceptableByFinallyOrPromises() {
        val p = SandboxPolicy.builder().maxExecutionTime(300).build()
        ctx(p).use { c ->
            assertThrows<NeonTimeoutException> {
                c.eval("try { while (true) {} } finally { while (true) {} }")
            }
            assertThrows<NeonTimeoutException> {
                c.eval("Promise.resolve().then(function loop() { return Promise.resolve().then(loop) })")
            }
        }
    }

    @Test
    @Timeout(60, unit = TimeUnit.SECONDS)
    fun infiniteTailRecursionIsStillInterruptible() {
        // proper tail calls run in constant stack space, so only the time / statement limits can stop this
        val p = SandboxPolicy.builder().maxExecutionTime(300).build()
        for (mode in listOf(ExecutionMode.INTERPRETER, ExecutionMode.COMPILED)) {
            ctx(p, mode = mode).use { c ->
                assertThrows<NeonTimeoutException> { c.eval("'use strict'; function f(n) { return f(n + 1) } f(0)") }
                assertThrows<NeonTimeoutException> { c.eval("'use strict'; var g = n => g(n + 1); g(0)") }
            }
        }
        val s = SandboxPolicy.builder().maxStatements(100_000).build()
        ctx(s).use { c -> assertThrows<NeonResourceLimitException> { c.eval("'use strict'; function f(n) { return f(n + 1) } f(0)") } }
    }

    @Test
    @Timeout(60, unit = TimeUnit.SECONDS)
    fun compiledModeHonoursLimits() {
        val p = SandboxPolicy.builder().maxExecutionTime(300).maxCallDepth(500).build()
        ctx(p, mode = ExecutionMode.COMPILED).use { c ->
            assertThrows<NeonTimeoutException> { c.eval("(function () { for (;;) {} })()") }
            assertEquals("RangeError", c.eval("function f(){ return f() } try { f() } catch (e) { e.name }").asString())
            assertEquals(55, c.eval("function fib(n) { return n < 2 ? n : fib(n - 1) + fib(n - 2) } fib(10)").asInt())
        }
    }

    @Test
    @Timeout(60, unit = TimeUnit.SECONDS)
    fun catastrophicRegexBacktrackingIsInterruptible() {
        val p = SandboxPolicy.builder().maxExecutionTime(500).build()
        for (mode in listOf(ExecutionMode.INTERPRETER, ExecutionMode.COMPILED)) {
            ctx(p, mode = mode).use { c ->
                assertThrows<NeonTimeoutException> { c.eval("/^(a+)+\$/.test('a'.repeat(40) + 'b')") }
                assertThrows<NeonTimeoutException> { c.eval("'a'.repeat(40).replace(/(a|aa)+b/g, 'x')") }
                // the context is still usable afterwards
                assertEquals(true, c.eval("/^(a+)+\$/.test('aaa')").asBoolean())
            }
        }
    }

    @Test
    @Timeout(60, unit = TimeUnit.SECONDS)
    fun shapeTreeChurnStaysWithinAllocationBudget() {
        // objects with ever-new property names: shape trees are bounded and allocations are counted
        val p = SandboxPolicy.builder().maxAllocatedBytes(64L * 1024 * 1024).maxExecutionTime(20_000).build()
        ctx(p).use { c ->
            assertThrows<NeonResourceLimitException> {
                c.eval("var keep = []; for (var i = 0; ; i++) { var o = {}; o['k' + i] = i; o['j' + (i % 97)] = 1; keep.push(o) }")
            }
            assertEquals(3, c.eval("var o = {a: 1, b: 2}; o.a + o.b").asInt())
        }
    }

    @Test
    @Timeout(60, unit = TimeUnit.SECONDS)
    fun bufferAllocationsAreBounded() {
        val p = SandboxPolicy.builder().maxAllocatedBytes(64L * 1024 * 1024).build()
        ctx(p).use { c ->
            assertEquals("RangeError", c.eval("try { new ArrayBuffer(2 ** 40) } catch (e) { e.name }").asString())
            assertEquals("RangeError", c.eval("try { new SharedArrayBuffer(2 ** 40) } catch (e) { e.name }").asString())
            assertEquals("RangeError", c.eval("try { new ArrayBuffer(8, { maxByteLength: 2 ** 40 }) } catch (e) { e.name }").asString())
            assertThrows<NeonResourceLimitException> { c.eval("var bufs = []; for (;;) bufs.push(new ArrayBuffer(1024 * 1024))") }
        }
    }

    @Test
    @Timeout(60, unit = TimeUnit.SECONDS)
    fun atomicsWaitHonoursTimeLimit() {
        val p = SandboxPolicy.builder().maxExecutionTime(300).build()
        ctx(p).use { c ->
            assertThrows<NeonTimeoutException> { c.eval("Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0)") }
        }
    }

    @Test
    @Timeout(30, unit = TimeUnit.SECONDS)
    fun terminationSurvivesHostCodeSwallowingIt() {
        ctx(SandboxPolicy.UNRESTRICTED).use { c ->
            c["s"] = Swallower()
            val t = Thread { Thread.sleep(300); c.interrupt() }
            t.start()
            assertThrows<NeonInterruptedException> { c.eval("s.run(() => { for (;;) {} }); for (;;) {}") }
            t.join()
            assertEquals(2, c.eval("1 + 1").asInt(), "the next evaluation starts fresh")
        }
        ctx(SandboxPolicy.builder().maxExecutionTime(300).build()).use { c ->
            c["s"] = Swallower()
            assertThrows<NeonTimeoutException> { c.eval("s.run(() => { for (;;) {} }); for (;;) {}") }
        }
    }

    @Test
    fun timeZoneIsPerContextAndHiddenInDeterministicMode() {
        ctx(SandboxPolicy.builder().deterministic(1, 0).build()).use { c ->
            assertEquals(0, c.eval("new Date(0).getTimezoneOffset()").asInt())
            assertEquals("UTC", c.eval("Temporal.Now.timeZoneId()").asString())
            assertEquals("1970-01-01T00:00:00Z", c.eval("Temporal.Now.instant().toString()").asString())
        }
        ctx(SandboxPolicy.builder().timeZone("+09:00").build()).use { c ->
            assertEquals(-540, c.eval("new Date(0).getTimezoneOffset()").asInt())
            assertEquals("+09:00", c.eval("Temporal.Now.timeZoneId()").asString())
        }
        ctx(SandboxPolicy.builder().timeZone("America/New_York").build()).use { c ->
            assertEquals(300, c.eval("new Date(Date.UTC(2020, 0, 15)).getTimezoneOffset()").asInt())
            assertEquals(240, c.eval("new Date(Date.UTC(2020, 6, 15)).getTimezoneOffset()").asInt())
        }
    }
}
