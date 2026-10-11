package dev.mooner.neonjs

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

class Secret {
    @JvmField val password = "hunter2"
    fun reveal() = password
}

private fun secretFn(s: String) = "fn $s"

class Swallower {
    /** Badly behaved host code: swallows every runtime exception thrown by the callback. */
    fun run(r: Runnable) {
        try {
            r.run()
        } catch (_: RuntimeException) {
            // ignored
        }
    }
}

/** Host code that runs [work] on another thread and blocks until it is done. */
class OtherThread(private val pool: java.util.concurrent.ExecutorService, private val work: () -> Any?) {
    @Volatile var failure: Throwable? = null

    fun run() {
        try {
            pool.submit(java.util.concurrent.Callable { work() }).get()
        } catch (e: java.util.concurrent.ExecutionException) {
            failure = e.cause
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
            assertThrows<NeonSyntaxException> { c.eval($$"$v/[@@]/v;") }
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
            // opaque includes not callable, and not array-like
            c["r"] = Runnable { }
            c["f"] = { x: Int -> x }
            c["l"] = java.util.List.of(1, 2)
            assertEquals("object,object,TypeError,undefined", c.eval("[typeof r, typeof f, (() => { try { r() } catch (e) { return e.name } })(), typeof l.size].join()").asString())
        }
    }

    @Test
    fun hostFunctionsFollowTheAccessPolicy() {
        ctx().use { c ->
            // a denied class implementing Runnable: neither callable nor showing members
            c["t"] = Thread { }
            assertEquals("object,TypeError,undefined,undefined", c.eval("[typeof t, (() => { try { t() } catch (e) { return e.name } })(), typeof t.run, typeof t.start].join()").asString())
            // a lambda implementing a denied interface: not callable, its method hidden
            c["pa"] = java.security.PrivilegedAction { "secret" }
            assertEquals("object,undefined", c.eval("[typeof pa, typeof pa.run].join()").asString())
            // Kotlin function references are callable, but their kotlin.jvm.internal base still hides its members
            c["fr"] = ::secretFn
            assertEquals("function,fn x,undefined,undefined", c.eval("[typeof fr, fr('x'), typeof fr.getOwner, typeof fr.getName].join()").asString())
            // a proxy from another context shows its interface's methods, not Proxy's
            val keeper = Keeper()
            ctx().use { other ->
                other["keeper"] = keeper
                other.eval("keeper.keep(s => s + '!')")
                c["p"] = keeper.transformer
                assertEquals("y!,undefined,undefined", c.eval("[p.transform('y'), typeof p.getInvocationHandler, typeof p.getClass].join()").asString())
            }
        }
        // under EXPLICIT, functions handed over by the host stay callable (they are the host's explicit choice) while
        // members of collections need annotations
        ctx(access = HostAccess.EXPLICIT).use { c ->
            c["r"] = Runnable { }
            c["l"] = java.util.List.of(1, 2)
            assertEquals("function,undefined,2", c.eval("[typeof r, typeof l.size, l.length].join()").asString())
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
    fun theEngineItselfIsDenied() {
        // compiled classes of the engine's package and subpackages: a new one must be denied or declared harmless here
        val harmless = setOf(
            "HostExport", "HostName", "HostFunction", "UncaughtErrorHandler", "NeonExtension", "NeonConsole", "NeonModuleLoader", "ExecutionMode", "NeonException",
            "NeonSyntaxException", "NeonTerminatedException", "NeonTimeoutException", "NeonResourceLimitException",
            "NeonInterruptedException",
        )
        val names = engineClassNames()
        val loader = NeonEngine::class.java.classLoader
        val root = names.filter { it.lastIndexOf('.') == "dev.mooner.neonjs".length }
        assertTrue(root.size > 20, "$root")
        for (n in root) {
            val outer = n.substringAfterLast('.').substringBefore('$')
            assertTrue(outer in harmless || HostAccess.ALL.isClassDenied(Class.forName(n, false, loader)), n)
        }
        val packages = names.filter { it !in root }.groupBy { it.split('.')[3] }
        assertTrue(packages.keys.containsAll(listOf("interop", "jit", "builtins", "vm")), "${packages.keys}")
        for ((pkg, classes) in packages) assertTrue(HostAccess.ALL.isClassDenied(Class.forName(classes.first(), false, loader)), pkg)

        val access = HostAccess.builder(HostAccess.Level.ALL).allowLookup { true }.build()
        val p = SandboxPolicy.builder().exposeJavaGlobal(true).build()
        ctx(p, access).use { c ->
            for (cls in listOf("dev.mooner.neonjs.NeonEngine", "dev.mooner.neonjs.NeonEngine${'$'}Builder", "dev.mooner.neonjs.HostAccess",
                "dev.mooner.neonjs.SandboxPolicy${'$'}Builder", "dev.mooner.neonjs.interop.HostBridge", "dev.mooner.neonjs.jit.JvmCompiler")) {
                assertThrows<NeonException>(cls) { c.eval("Java.type('$cls')") }
            }
            // handed to JS by the host, engine objects are opaque: a context, and a value of another context
            c["self"] = c
            ctx(p, access).use { other ->
                c["foreign"] = other.eval("({ a: 1 })")
                assertEquals("object,undefined,object,undefined", c.eval("[typeof self, typeof self.eval, typeof foreign, typeof foreign.getMember].join()").asString())
            }
            // exceptions and annotations stay visible
            assertEquals("NeonTimeoutException", c.eval("Java.type('dev.mooner.neonjs.NeonTimeoutException').name").asString().substringAfterLast('.'))
        }
        // and the embedder can lift the denial
        val lifted = HostAccess.builder(HostAccess.Level.ALL).allowClass("dev.mooner.neonjs.NeonContext").build()
        ctx(access = lifted).use { c ->
            c["self"] = c
            assertEquals("function", c.eval("typeof self.eval").asString())
        }
    }

    /** Binary names of the classes compiled from neonjs-core's main sources. */
    private fun engineClassNames(): List<String> {
        val location = java.io.File(NeonEngine::class.java.protectionDomain.codeSource.location.toURI())
        val prefix = "dev/mooner/neonjs/"
        val paths = if (location.isDirectory) location.walk().filter { it.isFile }.map { it.relativeTo(location).invariantSeparatorsPath }.toList()
        else java.util.jar.JarFile(location).use { jar -> jar.entries().toList().map { it.name } }
        return paths.filter { it.startsWith(prefix) && it.endsWith(".class") }.map { it.removeSuffix(".class").replace('/', '.') }.sorted()
    }

    @Test
    fun builtInDenialsCanBeLifted() {
        val p = SandboxPolicy.builder().exposeJavaGlobal(true).build()
        val lifted = HostAccess.builder(HostAccess.Level.ALL).allowLookup { true }
            .allowClass("java.lang.System").allowClass("java.lang.reflect.Array").allowPackage("java.lang.management").build()
        ctx(p, lifted).use { c ->
            assertEquals(true, c.eval("Java.type('java.lang.System').currentTimeMillis() > 0").asBoolean())
            // a class of a denied package
            assertEquals(3, c.eval("Java.type('java.lang.reflect.Array').getLength(Java.to([1, 2, 3]))").asInt())
            assertEquals("java.lang:type=Runtime", c.eval("Java.type('java.lang.management.ManagementFactory').RUNTIME_MXBEAN_NAME").asString())
            // objects are judged by their class: the MXBean is a sun.management class, still denied
            assertEquals("undefined", c.eval("typeof Java.type('java.lang.management.ManagementFactory').getRuntimeMXBean().getUptime").asString())
            // the rest of the list still applies
            for (cls in listOf("java.lang.Runtime", "java.lang.Class", "java.lang.reflect.Method", "sun.misc.Unsafe")) {
                assertThrows<NeonException>(cls) { c.eval("Java.type('$cls')") }
            }
            c["s"] = Secret()
            assertEquals("undefined", c.eval("typeof s.getClass").asString())
        }

        // the embedder's own denials win over lifted entries
        val both = HostAccess.builder(HostAccess.Level.ALL).allowLookup { true }
            .allowClass("java.lang.System").denyClass("java.lang.System").build()
        ctx(p, both).use { c -> assertThrows<NeonException> { c.eval("Java.type('java.lang.System')") } }

        // lifting java.lang.Class exposes getClass()
        val classes = HostAccess.builder(HostAccess.Level.ALL).allowClass("java.lang.Class").build()
        ctx(access = classes).use { c ->
            c["s"] = Secret()
            assertEquals("hunter2", c.eval("s.getClass() === undefined ? '' : s.reveal()").asString())
            assertEquals("function", c.eval("typeof s.getClass").asString())
        }

        // without the built-in list only the embedder's denials apply
        val trusted = HostAccess.builder(HostAccess.Level.ALL).allowLookup { true }.defaultDenyList(false)
            .denyClass("java.lang.ProcessBuilder").build()
        ctx(p, trusted).use { c ->
            assertEquals(true, c.eval("Java.type('java.lang.Runtime').getRuntime().availableProcessors() > 0").asBoolean())
            assertThrows<NeonException> { c.eval("Java.type('java.lang.ProcessBuilder')") }
            c["s"] = Secret()
            assertEquals("undefined", c.eval("typeof s.wait").asString())
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
                assertThrows<NeonTimeoutException> { c.eval("/^(a+)+$/.test('a'.repeat(40) + 'b')") }
                assertThrows<NeonTimeoutException> { c.eval("'a'.repeat(40).replace(/(a|aa)+b/g, 'x')") }
                // the context is still usable afterwards
                assertEquals(true, c.eval("/^(a+)+$/.test('aaa')").asBoolean())
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
    fun waitingForABusyContextIsBoundedByTheTimeLimit() {
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            ctx(SandboxPolicy.builder().maxExecutionTime(500).build()).use { c ->
                // JS blocks on host work that needs the same context on another thread: that thread gives up after
                // the time limit, instead of both threads waiting for each other forever
                val other = OtherThread(pool) { c.eval("1").asInt() }
                c["other"] = other
                val start = System.nanoTime()
                runCatching { c.eval("other.run()") }
                val ms = (System.nanoTime() - start) / 1_000_000
                assertInstanceOf(NeonTimeoutException::class.java, other.failure)
                assertTrue(ms in 400..10_000, "$ms ms")
                assertEquals(2, c.eval("1 + 1").asInt(), "the context is usable afterwards")
                // a shorter wait is just a wait
                val entered = java.util.concurrent.CountDownLatch(1)
                val later = pool.submit(java.util.concurrent.Callable { entered.await(); c.eval("2").asInt() })
                c["hold"] = Runnable { entered.countDown(); Thread.sleep(100) }
                c.eval("hold()")
                assertEquals(2, later.get(10, TimeUnit.SECONDS))
            }
        } finally {
            pool.shutdownNow()
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
