package dev.mooner.neonjs

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** An event loop driven by the external job listener instead of a thread waiting in runEventLoop. */
class ExternalJobListenerTest {
    private fun ctx() = NeonEngine.builder().webGlobals(true).hostAccess(HostAccess.ALL).console(null).build().newContext()

    @Test
    fun theListenerHearsOfTimersAndHostFutures() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            ctx().use { c ->
                val told = AtomicInteger()
                c.setExternalJobListener {
                    told.incrementAndGet()
                    pool.execute { c.runEventLoop(0) }
                }
                val future = CompletableFuture<String>()
                c["future"] = future
                c.eval("globalThis.log = []; setTimeout(() => log.push('timer'), 20); future.then(v => log.push(v))")
                Thread.sleep(50)
                future.complete("future")
                val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (System.nanoTime() < end && c.eval("log.length").asInt() < 2) Thread.sleep(10)
                assertEquals("timer,future", c.eval("log.join()").asString())
                assertTrue(told.get() >= 2)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun nothingIsToldAfterClose() {
        val c = ctx()
        val told = AtomicInteger()
        c.setExternalJobListener { told.incrementAndGet() }
        val future = CompletableFuture<String>()
        c["future"] = future
        c.eval("future.then(() => {})")
        c.close()
        future.complete("late")
        assertEquals(0, told.get())
    }

    @Test
    fun aFailingListenerStillLeavesTheJobQueued() {
        ctx().use { c ->
            c.setExternalJobListener { throw IllegalStateException("listener") }
            c.eval("globalThis.fired = false; setTimeout(() => fired = true, 10)")
            assertTrue(c.runEventLoop(5_000))
            assertTrue(c.eval("fired").asBoolean())
        }
    }
}
