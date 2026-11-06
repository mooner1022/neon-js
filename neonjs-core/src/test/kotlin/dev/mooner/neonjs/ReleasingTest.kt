package dev.mooner.neonjs

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/** [NeonContext.releasing]: host code blocking on another thread that calls into the same context. */
class ReleasingTest {
    private fun engine(build: NeonEngine.Builder.() -> Unit = {}) =
        NeonEngine.builder().webGlobals(true).console(null).apply(build).build()

    /** Runs [block] on a new thread named [name]. */
    private fun <T> thread(name: String = "other", block: () -> T): CompletableFuture<T> {
        val f = CompletableFuture<T>()
        Thread({
            try {
                f.complete(block())
            } catch (e: Throwable) {
                f.completeExceptionally(e)
            }
        }, name).start()
        return f
    }

    @Test
    fun anotherThreadCallsInWhileTheCallerWaitsForIt() {
        engine().newContext().use { c ->
            // what a UI-thread helper does: run a JS function on another thread and wait for its result
            c.setFunction("onOtherThread") { args ->
                val fn = args[0]
                NeonContext.current()!!.releasing { thread { fn.call().asInt() }.get(10, TimeUnit.SECONDS) }
            }
            assertEquals(43, c.eval("onOtherThread(() => 42) + 1").asInt())
        }
    }

    @Test
    fun nestedReleasesResumeInReverseOrder() {
        engine().newContext().use { c ->
            val order = Collections.synchronizedList(ArrayList<String>())
            val innerReleased = CountDownLatch(1)
            val outerDone = CountDownLatch(1)
            c.setFunction("record") { args -> order.add(args[0].asString()); null }
            // the inner thread, running nested in the outer's call, lets go too, and holds on until the outer's wait is over
            c.setFunction("inner") {
                NeonContext.current()!!.releasing {
                    innerReleased.countDown()
                    assertTrue(outerDone.await(10, TimeUnit.SECONDS))
                    Thread.sleep(100)
                }
                null
            }
            c.setFunction("outer") {
                val inner = NeonContext.current()!!.releasing {
                    val inner = thread { c.eval("inner(); record('inner resumed')") }
                    assertTrue(innerReleased.await(10, TimeUnit.SECONDS))
                    // this wait ends first, while the inner thread still holds on: taking the context back waits for it
                    outerDone.countDown()
                    inner
                }
                inner.get(10, TimeUnit.SECONDS)
                null
            }
            c.eval("outer(); record('outer resumed')")
            assertEquals(listOf("inner resumed", "outer resumed"), order)
        }
    }

    @Test
    fun aNestedCallLeavesTheJobsToTheReleasingThread() {
        engine().newContext().use { c ->
            c.setFunction("onOtherThread") { args ->
                val fn = args[0]
                NeonContext.current()!!.releasing { thread { fn.call().asString() }.get(10, TimeUnit.SECONDS) }
            }
            val seen = c.eval("""
                var log = [];
                Promise.resolve().then(() => log.push('job'));
                const during = onOtherThread(() => log.join());
                during
            """).asString()
            assertEquals("", seen, "the queued job ran during the nested call")
            assertEquals("job", c.eval("log.join()").asString())
        }
    }

    @Test
    fun aWaitingEventLoopResumesAfterANestedRelease() {
        engine().newContext().use { c ->
            val order = Collections.synchronizedList(ArrayList<String>())
            val held = CountDownLatch(1)
            c.setFunction("record") { args -> order.add(args[0].asString()); null }
            c.setFunction("hold") {
                NeonContext.current()!!.releasing {
                    held.countDown()
                    // the timer fires meanwhile: the loop must not run it on top of this call
                    Thread.sleep(400)
                }
                null
            }
            c.eval("setTimeout(() => record('timer'), 100)")
            val loop = thread { c.runEventLoop(10_000) }
            // a call into the context while its loop waits for the timer, which lets go in turn
            val call = thread { c.eval("hold(); record('call resumed')") }
            assertTrue(held.await(10, TimeUnit.SECONDS))
            call.get(10, TimeUnit.SECONDS)
            assertTrue(loop.get(10, TimeUnit.SECONDS))
            assertEquals(listOf("call resumed", "timer"), order)
        }
    }

    @Test
    fun theWaitLimitAppliesToTheThreadsItSelects() {
        engine { contextWaitLimit(150) { it.name == "limited" } }.newContext().use { c ->
            val busy = CountDownLatch(1)
            c.setFunction("busy") { busy.countDown(); Thread.sleep(800); null }
            val holder = thread("holder") { c.eval("busy()") }
            assertTrue(busy.await(10, TimeUnit.SECONDS))
            val limited = thread("limited") { c.eval("1") }
            val e = assertThrows<ExecutionException> { limited.get(10, TimeUnit.SECONDS) }
            assertInstanceOf(NeonTimeoutException::class.java, e.cause)
            // others wait as long as it takes
            assertEquals(2, thread("unlimited") { c.eval("2").asInt() }.get(10, TimeUnit.SECONDS))
            holder.get(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun currentIsTheInnermostContextEntered() {
        engine().use { e ->
            e.newContext().use { outer ->
                e.newContext().use { inner ->
                    assertNull(NeonContext.current())
                    inner.setFunction("innerSees") { NeonContext.current() === inner }
                    outer.setFunction("check") {
                        val before = NeonContext.current() === outer
                        val nested = inner.eval("innerSees()").asBoolean()
                        before && nested && NeonContext.current() === outer
                    }
                    assertTrue(outer.eval("check()").asBoolean())
                    assertNull(NeonContext.current())
                }
            }
        }
    }

    @Test
    fun releasingNeedsTheContext() {
        engine().newContext().use { c ->
            assertThrows<IllegalStateException> { c.releasing { } }
        }
    }
}
