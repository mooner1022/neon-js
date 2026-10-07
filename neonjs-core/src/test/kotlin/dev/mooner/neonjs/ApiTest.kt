package dev.mooner.neonjs

import kotlin.math.sqrt
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.function.Function as JFunction

class Point(@JvmField var x: Int, @JvmField var y: Int) {
    fun length(): Double = sqrt((x * x + y * y).toDouble())
    fun add(other: Point) = Point(x + other.x, y + other.y)
    fun scale(f: Int) = Point(x * f, y * f)
    fun scale(f: Double) = "double:$f"
    override fun toString() = "Point($x, $y)"

    companion object {
        @JvmStatic fun origin() = Point(0, 0)
    }
}

data class User(val name: String, var age: Int) {
    val tags = mutableListOf("a", "b")
    fun greet(prefix: String) = "$prefix $name"
}

class Exported {
    @HostExport fun visible() = "ok"
    fun hidden() = "secret"
}

class EventBus {
    private val listeners = ArrayList<java.util.function.Consumer<String>>()
    fun on(listener: java.util.function.Consumer<String>) { listeners.add(listener) }
    fun emit(e: String) { for (l in listeners) l.accept(e) }
    fun transform(f: JFunction<Int, Int>, v: Int): Int = f.apply(v)
    fun runIt(r: Runnable) = r.run()
    fun sum(vararg xs: Int) = xs.sum()
    fun fail(): Nothing = throw IllegalStateException("boom")
}

class ApiTest {
    private fun engine(access: HostAccess = HostAccess.ALL, sandbox: SandboxPolicy = SandboxPolicy.UNRESTRICTED, mode: ExecutionMode = ExecutionMode.INTERPRETER) =
        NeonEngine.builder().hostAccess(access).sandbox(sandbox).executionMode(mode).build()

    @Test
    fun basicEvaluation() {
        engine().newContext().use { ctx ->
            assertEquals(42, ctx.eval("6 * 7").asInt())
            assertEquals("ab", ctx.eval("'a' + 'b'").asString())
            val o = ctx.eval("({x: 1, y: [1, 2, 3]})")
            assertEquals(1, o.getMember("x").asInt())
            assertEquals(3L, o.getMember("y").arraySize)
            assertEquals(setOf("x", "y"), o.memberKeys())
        }
    }

    @Test
    fun hostFunctionsAndCallbacks() {
        engine().newContext().use { ctx ->
            val log = ArrayList<String>()
            ctx.setFunction("log") { args -> log.add(args.joinToString(" ") { it.toString() }); null }
            var saved: NeonValue? = null
            ctx.setFunction("onEvent") { args -> saved = args[0]; null }
            ctx.eval("log('hello', 1 + 1); onEvent(function (e) { log('event:' + e); return e * 2; })")
            assertEquals(listOf("hello 2"), log)
            val r = saved!!.call(21)
            assertEquals(42, r.asInt())
            assertEquals("event:21", log[1])
        }
    }

    @Test
    fun hostObjectsAndOverloads() {
        engine().newContext().use { ctx ->
            ctx["p"] = Point(3, 4)
            assertEquals(5.0, ctx.eval("p.length()").asDouble())
            assertEquals(7, ctx.eval("p.add(p).x + 1").asInt())
            assertEquals("Point(6, 8)", ctx.eval("String(p.scale(2))").asString())
            assertEquals("double:1.5", ctx.eval("p.scale(1.5)").asString())
            ctx.eval("p.x = 10")
            assertEquals(10, ctx["p"].asHostObject<Point>().x)
            ctx["u"] = User("kim", 30)
            assertEquals("hi kim", ctx.eval("u.greet('hi')").asString())
            assertEquals(30, ctx.eval("u.age").asInt())
            ctx.eval("u.age = 31")
            assertEquals(31, ctx["u"].asHostObject<User>().age)
            assertEquals("a,b", ctx.eval("u.tags.join ? u.tags.join(',') : Array.from(u.tags).join(',')").asString())
            assertEquals(2, ctx.eval("u.tags.length").asInt())
            assertEquals("b", ctx.eval("u.tags[1]").asString())
        }
    }

    @Test
    fun exposedClasses() {
        engine().newContext().use { ctx ->
            ctx.exposeClass("Point", Point::class.java)
            assertEquals(5.0, ctx.eval("new Point(3, 4).length()").asDouble())
            assertTrue(ctx.eval("new Point(1, 2) instanceof Point").asBoolean())
            assertEquals(0, ctx.eval("Point.origin().x").asInt())
            assertThrows<NeonException> { ctx.eval("Point(1, 2)") }
        }
    }

    @Test
    fun samConversionAndEvents() {
        engine().newContext().use { ctx ->
            val bus = EventBus()
            ctx["bus"] = bus
            ctx.eval("var got = []; bus.on(e => got.push(e)); bus.on({ accept(e) { got.push('obj:' + e) } })")
            bus.emit("x")
            assertEquals("x,obj:x", ctx.eval("got.join(',')").asString())
            assertEquals(11, ctx.eval("bus.transform(v => v + 1, 10)").asInt())
            assertEquals(6, ctx.eval("bus.sum(1, 2, 3)").asInt())
            ctx.eval("var ran = false; bus.runIt(() => { ran = true })")
            assertTrue(ctx["ran"].asBoolean())
            val fn = ctx.eval("(x => x * 3)").`as`(JFunction::class.java) as JFunction<Any?, Any?>
            assertEquals(12, fn.apply(4))
        }
    }

    @Test
    fun hostExceptions() {
        engine().newContext().use { ctx ->
            ctx["bus"] = EventBus()
            assertEquals("boom", ctx.eval("try { bus.fail() } catch (e) { e.message }").asString())
            val ex = assertThrows<NeonException> { ctx.eval("bus.fail()") }
            assertTrue(ex.cause is IllegalStateException)
            val js = assertThrows<NeonException> { ctx.eval("throw new TypeError('bad')") }
            assertEquals("TypeError: bad", js.message)
            assertTrue(js.guestValue!!.isError)
            assertThrows<NeonSyntaxException> { ctx.eval("let let = 1") }
        }
    }

    @Test
    fun explicitAccess() {
        engine(HostAccess.EXPLICIT).newContext().use { ctx ->
            ctx["e"] = Exported()
            assertEquals("ok", ctx.eval("e.visible()").asString())
            assertEquals("undefined", ctx.eval("typeof e.hidden").asString())
        }
        engine(HostAccess.NONE).newContext().use { ctx ->
            ctx["e"] = Exported()
            assertEquals("undefined", ctx.eval("typeof e.visible").asString())
        }
    }

    @Test
    fun dangerousMembersAreBlocked() {
        val access = HostAccess.builder(HostAccess.Level.ALL).allowLookup { it.startsWith("java.util.") || it == "java.lang.Runtime" }.build()
        val policy = SandboxPolicy.builder().exposeJavaGlobal(true).build()
        engine(access, policy).newContext().use { ctx ->
            ctx["p"] = Point(1, 2)
            assertEquals("undefined", ctx.eval("typeof p.getClass").asString())
            assertEquals("undefined", ctx.eval("typeof p.wait").asString())
            val list = ctx.eval("var L = Java.type('java.util.ArrayList'); var l = new L(); l.add('x'); l.size()")
            assertEquals(1, list.asInt())
            assertThrows<NeonException> { ctx.eval("Java.type('java.lang.Runtime')") }
            assertThrows<NeonException> { ctx.eval("Java.type('java.io.File')") }
            ctx["r"] = Runtime.getRuntime()
            assertEquals("undefined", ctx.eval("typeof r.exec").asString())
        }
    }

    @Test
    fun timeLimit() {
        val policy = SandboxPolicy.builder().maxExecutionTime(200).build()
        engine(sandbox = policy).newContext().use { ctx ->
            val t0 = System.currentTimeMillis()
            assertThrows<NeonTimeoutException> { ctx.eval("while (true) {}") }
            assertTrue(System.currentTimeMillis() - t0 < 5000)
            // termination cannot be caught by JS
            assertThrows<NeonTimeoutException> { ctx.eval("try { for (;;) {} } catch (e) {} finally { }") }
            // the context remains usable
            assertEquals(3, ctx.eval("1 + 2").asInt())
        }
    }

    @Test
    fun statementAndDepthLimits() {
        val policy = SandboxPolicy.builder().maxStatements(100_000).maxCallDepth(200).build()
        engine(sandbox = policy).newContext().use { ctx ->
            assertThrows<NeonResourceLimitException> { ctx.eval("for (var i = 0; i < 1e9; i++) {}") }
            assertEquals("RangeError", ctx.eval("function f() { return f() } try { f() } catch (e) { e.name }").asString())
        }
    }

    @Test
    fun interruptFromAnotherThread() {
        engine().newContext().use { ctx ->
            val t = Thread {
                Thread.sleep(200)
                ctx.interrupt()
            }
            t.start()
            assertThrows<NeonInterruptedException> { ctx.eval("for (;;) {}") }
            t.join()
        }
    }

    @Test
    fun evalDisallowed() {
        engine(sandbox = SandboxPolicy.STRICT).newContext().use { ctx ->
            assertEquals("EvalError", ctx.eval("try { eval('1') } catch (e) { e.name }").asString())
            assertEquals("EvalError", ctx.eval("try { new Function('return 1') } catch (e) { e.name }").asString())
            assertEquals("EvalError", ctx.eval("try { (0, eval)('1') } catch (e) { e.name }").asString())
        }
    }

    @Test
    fun stringLengthLimit() {
        val policy = SandboxPolicy.builder().maxStringLength(1_000_000).build()
        engine(sandbox = policy).newContext().use { ctx ->
            assertEquals("RangeError", ctx.eval("var s = 'x'; try { for (;;) s += s } catch (e) { e.name }").asString())
        }
    }

    @Test
    fun deterministicMode() {
        val policy = SandboxPolicy.builder().deterministic(42, 1000).build()
        val a = engine(sandbox = policy).newContext().use { it.eval("Math.random()").asDouble() }
        val b = engine(sandbox = policy).newContext().use { it.eval("Math.random()").asDouble() }
        assertEquals(a, b)
    }

    @Test
    fun promisesAndAsync() {
        engine().newContext().use { ctx ->
            val p = ctx.eval("(async () => { await null; return 7 })()")
            assertTrue(p.isPromise)
            assertEquals(7, p.await().asInt())
            val order = ctx.eval("var o = []; Promise.resolve().then(() => o.push(2)); o.push(1); o")
            assertEquals("1,2", ctx.eval("o.join(',')").asString())
            assertEquals(2L, order.arraySize)
        }
    }

    @Test
    fun precompiledScripts() {
        val engine = engine()
        val script = engine.compile("var counter = (typeof counter === 'number' ? counter : 0) + 1; counter")
        engine.newContext().use { c1 ->
            assertEquals(1, c1.eval(script).asInt())
            assertEquals(2, c1.eval(script).asInt())
        }
        engine.newContext().use { c2 -> assertEquals(1, c2.eval(script).asInt()) }
    }

    @Test
    fun contextsAreIsolated() {
        val engine = engine()
        val a = engine.newContext()
        val b = engine.newContext()
        a.eval("Array.prototype.foo = 1; var shared = 1")
        assertEquals("undefined", b.eval("typeof [].foo").asString())
        assertEquals("undefined", b.eval("typeof shared").asString())
    }
}
