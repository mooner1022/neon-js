package io.neonjs

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class Counter(val start: Int) {
    fun next() = start + 1

    companion object {
        var created = 0
        const val LIMIT = 10
        fun make(n: Int): Counter {
            created++
            return Counter(n)
        }
    }
}

class ArrayHost {
    fun sum(xs: IntArray) = xs.sum()
    fun join(xs: Array<String>) = xs.joinToString("-")
    fun names(): List<String> = listOf("a", "b", "c")
    fun lookup(): Map<String, Int> = linkedMapOf("one" to 1, "two" to 2)
}

abstract class Shape2D(val name: String) {
    abstract fun area(): Double
    open fun describe(): String = "$name:${area()}"
    protected open fun unit(): String = "cm2"
    fun withUnit(): String = "${area()} ${unit()}"
    fun locked(): String = "final"
}

interface Greeter {
    fun greet(who: String): String
    fun twice(who: String): String = greet(who) + "/" + greet(who)
}

@HostExport
abstract class ExportedTask(val label: String) {
    abstract fun run(x: Int): Int
    fun twice(x: Int) = run(run(x))
}

abstract class PartlyExported {
    @HostExport abstract fun value(): String
    abstract fun secret(): String
    @HostExport fun show() = value() + "/" + secret()
}

class AsyncHost {
    @Volatile var received: String? = null
    @Volatile var failure: Throwable? = null
    fun later(value: String, delayMillis: Long): java.util.concurrent.CompletableFuture<String> =
        java.util.concurrent.CompletableFuture.supplyAsync({ value }, java.util.concurrent.CompletableFuture.delayedExecutor(delayMillis, java.util.concurrent.TimeUnit.MILLISECONDS))
    fun failing(): java.util.concurrent.CompletableFuture<String> = java.util.concurrent.CompletableFuture.failedFuture(IllegalStateException("nope"))
    fun never(): java.util.concurrent.CompletableFuture<String> = java.util.concurrent.CompletableFuture()
    fun take(stage: java.util.concurrent.CompletionStage<String>) {
        stage.whenComplete { v, e -> received = v; failure = e }
    }
}

class Clock {
    fun millis(i: java.time.Instant) = i.toEpochMilli()
    fun legacy(d: java.util.Date) = d.time
    fun day(d: java.time.LocalDate) = d.toString()
    fun zone(z: java.time.ZonedDateTime) = z.zone.id
    fun now(): java.time.Instant = java.time.Instant.parse("2026-01-02T03:04:05.678Z")
}

object Shapes {
    @JvmStatic fun total(shapes: List<Shape2D>): Double = shapes.sumOf { it.area() }
    @JvmStatic fun describeAll(shapes: List<Shape2D>): String = shapes.joinToString { it.describe() }
}

class InteropTest {
    private fun ctx(access: HostAccess = HostAccess.ALL, javaGlobal: Boolean = false) =
        NeonEngine.builder().hostAccess(access).console(null)
            .sandbox(SandboxPolicy.builder().exposeJavaGlobal(javaGlobal).build()).build().newContext()

    private val lookupAll = HostAccess.builder(HostAccess.Level.ALL).allowLookup { true }.build()

    @Test
    fun mapEntriesAsProperties() {
        ctx().use { c ->
            val m = java.util.LinkedHashMap<String, Any?>()
            m["a"] = 1
            m["size"] = "shadowed by the Java member"
            c["m"] = m
            assertEquals(1, c.eval("m.a").asInt())
            assertEquals(2, c.eval("m.size()").asInt(), "Java members win over entries")
            c.eval("m.b = 'x'; m[7] = true")
            assertEquals("x", m["b"])
            assertEquals(true, m["7"])
            assertEquals("a,b,7", c.eval("Object.keys(m).join()").asString(), "entries shadowed by members are not keys")
            assertEquals("shadowed by the Java member", m["size"])
            c.eval("delete m.a")
            assertFalse(m.containsKey("a"))
            assertEquals("undefined", c.eval("typeof m.missing").asString())
            // a Kotlin read-only map rejects writes
            c["ro"] = mapOf("k" to 1)
            assertEquals(1, c.eval("ro.k").asInt())
            assertEquals("TypeError", c.eval("'use strict'; try { ro.k = 2; 'no' } catch (e) { e.name }").asString())
        }
    }

    @Test
    fun iteratorsAndIterables() {
        ctx().use { c ->
            c["it"] = listOf(1, 2, 3).iterator()
            c["set"] = linkedSetOf("x", "y")
            c["arr"] = intArrayOf(4, 5)
            assertEquals("1,2,3", c.eval("[...it].join()").asString())
            assertEquals("x|y", c.eval("var s = []; for (const v of set) s.push(v); s.join('|')").asString())
            assertEquals(9, c.eval("var t = 0; for (const v of arr) t += v; t").asInt())
            c["h"] = ArrayHost()
            assertEquals("a-b-c", c.eval("Array.from(h.names()).join('-')").asString())
        }
    }

    @Test
    fun javaArrays() {
        ctx(lookupAll, javaGlobal = true).use { c ->
            c["h"] = ArrayHost()
            assertEquals(3, c.eval("var IntArray = Java.type('int[]'); var a = new IntArray(3); a.length").asInt())
            assertEquals(12, c.eval("a[0] = 5; a[2] = 7; h.sum(a)").asInt())
            assertEquals(6, c.eval("h.sum(Java.to([1, 2, 3], 'int[]'))").asInt())
            assertEquals("p-q", c.eval("h.join(Java.to(['p', 'q'], 'java.lang.String[]'))").asString())
            assertEquals(2, c.eval("new (Java.type('java.lang.String[][]'))(2).length").asInt())
            assertEquals("RangeError", c.eval("try { new IntArray(1e12) } catch (e) { e.name }").asString())
            assertThrows<NeonException> { c.eval("Java.type('java.lang.Class[]')") }
            assertThrows<NeonException> { c.eval("Java.type('int')") }
            assertEquals("one=1,two=2", c.eval("var lm = h.lookup(); Object.keys(lm).map(k => k + '=' + lm[k]).join()").asString())
            assertEquals("1,2", c.eval("Java.from(Java.to([1, 2], 'java.util.List')).join()").asString())
        }
    }

    @Test
    fun kotlinCompanionMembers() {
        ctx().use { c ->
            c.exposeClass("Counter", Counter::class.java)
            Counter.created = 0
            assertEquals(6, c.eval("Counter.make(5).next()").asInt())
            assertEquals(1, c.eval("Counter.created").asInt())
            assertEquals(10, c.eval("Counter.LIMIT").asInt())
            c.eval("Counter.created = 42")
            assertEquals(42, Counter.created)
            assertEquals(8, c.eval("new Counter(7).next()").asInt())
        }
    }

    @Test
    fun javaExtend() {
        ctx(lookupAll, javaGlobal = true).use { c ->
            c.exposeClass("Shapes", Shapes::class.java)
            c.eval("""
                var Shape2D = Java.type('io.neonjs.Shape2D');
                var Square = Java.extend(Shape2D, {
                    area() { return 4 },
                    describe() { return 'square[' + Java.super(this).describe() + ']' },
                    unit() { return 'm2' },
                });
                var sq = new Square('sq');
                var Adapter = Java.extend(Shape2D);
                var circle = new Adapter({ area: () => 3.5 }, 'circle');
            """)
            assertEquals(4.0, c.eval("sq.area()").asDouble())
            assertEquals("square[sq:4.0]", c.eval("sq.describe()").asString())
            assertEquals("4.0 m2", c.eval("sq.withUnit()").asString(), "protected override called from Java")
            assertEquals("circle:3.5", c.eval("circle.describe()").asString(), "non-overridden method uses super")
            assertEquals(7.5, c.eval("Shapes.total([sq, circle])").asDouble())
            assertEquals("square[sq:4.0], circle:3.5", c.eval("Shapes.describeAll([sq, circle])").asString())
            assertEquals("true,true", c.eval("[sq instanceof Shape2D, sq instanceof Square].join()").asString())
            assertEquals("final", c.eval("sq.locked()").asString())
            assertEquals("undefined", c.eval("typeof sq.super\$describe\$0").asString(), "super bridges are not members")

            // interfaces with default methods
            assertEquals("hi x/hi x", c.eval("var G = Java.extend(Java.type('io.neonjs.Greeter'), { greet: w => 'hi ' + w }); new G().twice('x')").asString())
            // an unimplemented abstract method surfaces as a host error
            assertEquals("HostError", c.eval("try { new Adapter({}, 'empty').area() } catch (e) { e.name }").asString())
            // policy: final, denied and non-type arguments are rejected
            assertThrows<NeonException> { c.eval("Java.extend(Java.type('java.lang.String'), {})") }
            assertThrows<NeonException> { c.eval("Java.extend({}, {})") }
            assertThrows<NeonException> { c.eval("Java.extend(Java.type('java.lang.Thread'), {})") }
        }
        // implementations disabled by policy
        val noImpl = HostAccess.builder(HostAccess.Level.ALL).allowLookup { true }.allowImplementations(false).build()
        ctx(noImpl, javaGlobal = true).use { c ->
            assertThrows<NeonException> { c.eval("Java.extend(Java.type('io.neonjs.Shape2D'), { area() { return 1 } })") }
        }
    }

    @Test
    fun javaExtendUnderExplicitAccess() {
        val explicit = HostAccess.builder(HostAccess.Level.EXPLICIT).allowLookup { it.startsWith("io.neonjs.") }.build()
        ctx(explicit, javaGlobal = true).use { c ->
            c.eval("var T = Java.extend(Java.type('io.neonjs.ExportedTask'), { run(x) { return x + 1 } }); var t = new T('a')")
            assertEquals(3, c.eval("t.twice(1)").asInt())
            assertEquals(6, c.eval("t.run(5)").asInt(), "override of a method of an exported class")
            c.eval("var P = Java.extend(Java.type('io.neonjs.PartlyExported'), { value() { return 'v' }, secret() { return 's' } }); var p = new P()")
            assertEquals("function,undefined,v/s", c.eval("[typeof p.value, typeof p.secret, p.show()].join()").asString(),
                "only overrides of exported methods are visible")
        }
    }

    @Test
    fun hostFuturesBecomePromises() {
        ctx().use { c ->
            c["host"] = AsyncHost()
            c.eval("var out = []; host.later('x', 30).then(v => out.push('ok ' + v)); host.failing().catch(e => out.push(e.name + ': ' + e.message))")
            assertTrue(c.runEventLoop(10_000))
            assertEquals("HostError: nope,ok x", c.eval("out.join()").asString())
            val p = c.eval("(async () => (await host.later('a', 20)) + (await host.later('b', 1)))()")
            assertEquals("ab", p.await().asString())
            // top-level await on a host future
            assertEquals("y!", c.evalModule("export const v = (await host.later('y', 20)) + '!'", "m.js").getMember("v").asString())
            c.eval("host.never().then(() => {})")
            assertFalse(c.runEventLoop(50), "a pending future keeps the loop busy")
        }
        val timed = NeonEngine.builder().console(null).hostAccess(HostAccess.ALL).sandbox(SandboxPolicy.builder().maxExecutionTime(1_000).maxStatements(10_000).build()).build()
        timed.newContext().use { c ->
            c["host"] = AsyncHost()
            val p = c.eval("host.never()")
            assertThrows<NeonTimeoutException> { p.await() }
            // waiting does not consume the instruction budget
            assertEquals("z", c.eval("host.later('z', 250)").await().asString())
        }
    }

    @Test
    fun promisesBecomeHostFutures() {
        ctx().use { c ->
            val host = AsyncHost()
            c["host"] = host
            val f = c.eval("Promise.resolve(5).then(x => x * 2)").`as`(java.util.concurrent.CompletableFuture::class.java)
            assertEquals(10, f.get(1, java.util.concurrent.TimeUnit.SECONDS))
            c.eval("var settle; host.take(new Promise(r => settle = r))")
            assertNull(host.received)
            c.eval("settle(42)")
            assertEquals("42", host.received, "converted to the stage's element type")
            c.eval("host.take(Promise.reject(new RangeError('bad')))")
            assertEquals("RangeError: bad", host.failure?.message)
            c.eval("host.take({ then(r) { r('thenable') } })")
            assertEquals("thenable", host.received)
            c.eval("host.take('plain')")
            assertEquals("plain", host.received)
        }
    }

    @Test
    fun dateTimeConversions() {
        NeonEngine.builder().hostAccess(HostAccess.ALL).console(null).sandbox(SandboxPolicy.builder().timeZone("Asia/Seoul").build()).build().newContext().use { c ->
            c["clock"] = Clock()
            assertEquals(0, c.eval("clock.millis(new Date(0))").asInt())
            assertEquals(1000, c.eval("clock.legacy(new Date(1000))").asInt())
            assertEquals("2026-01-02", c.eval("clock.day(new Date(Date.UTC(2026, 0, 1, 20)))").asString(), "local types use the context's zone")
            assertEquals("Asia/Seoul", c.eval("clock.zone(new Date())").asString())
            assertEquals(5, c.eval("clock.millis(Temporal.Instant.fromEpochMilliseconds(5))").asInt())
            assertEquals("RangeError", c.eval("try { clock.millis(new Date(NaN)) } catch (e) { e.name }").asString())
            assertEquals(java.time.Instant.ofEpochMilli(5), c.eval("new Date(5)").asInstant())
            // a host Instant reaches JS as a host object; Date accepts its ISO string form
            assertEquals(1767323045678.0, c.eval("new Date(clock.now()).getTime()").asDouble())
        }
    }

    @Test
    fun hostDefinedModules() {
        ctx().use { c ->
            val log = ArrayList<String>()
            c.defineModule("host:log", mapOf(
                "default" to "the default",
                "write" to c.createFunction("write") { a -> log.add(a[0].asString()); null },
                "version" to 3,
                "list" to listOf(1, 2),
            ))
            c.setModuleLoader(MapModuleLoader(mapOf("main.js" to "import def, { write, version } from 'host:log'; write(def + ' v' + version); export const n = 42;")))
            val ns = c.evalModule("import { n } from './main.js'; import * as h from 'host:log'; export const sum = n + h.version + h.list.length;", "entry.js")
            assertEquals(47, ns.getMember("sum").asInt())
            assertEquals(listOf("the default v3"), log)
            c.eval("var r; import('host:log').then(m => { 'use strict'; try { m.version = 1 } catch (e) { r = e.name } })")
            c.runJobs()
            assertEquals("TypeError", c.eval("r").asString(), "module namespaces are read-only")
            c.eval("var e2; import('nope').catch(e => e2 = e.name)")
            c.runJobs()
            assertEquals("TypeError", c.eval("e2").asString())
        }
    }

    @Test
    fun fileSystemLoaderStaysInsideRoot() {
        val root = java.nio.file.Files.createTempDirectory("neon-mod")
        val outside = java.nio.file.Files.createTempDirectory("neon-out")
        java.nio.file.Files.writeString(outside.resolve("secret.js"), "export const s = 'secret';")
        java.nio.file.Files.writeString(root.resolve("ok.js"), "export const v = 'ok';")
        val loader = FileSystemModuleLoader(root)
        ctx().use { c ->
            c.setModuleLoader(loader)
            assertEquals("ok", c.evalModule("export { v } from './ok.js';", root.resolve("main.js").toString()).getMember("v").asString())
            assertThrows<NeonException> { c.evalModule("import { s } from '../${outside.fileName}/secret.js';", root.resolve("main2.js").toString()) }
        }
        val link = root.resolve("link.js")
        val linked = try {
            java.nio.file.Files.createSymbolicLink(link, outside.resolve("secret.js"))
            true
        } catch (e: Exception) {
            false // symbolic links need extra privileges on Windows
        }
        if (linked) {
            ctx().use { c ->
                c.setModuleLoader(loader)
                assertThrows<NeonException> { c.evalModule("import { s } from './link.js';", root.resolve("main3.js").toString()) }
            }
        }
    }
}
