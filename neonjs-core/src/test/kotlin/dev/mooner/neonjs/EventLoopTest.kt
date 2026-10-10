package dev.mooner.neonjs

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** The event loop of a context used by several threads. */
class EventLoopTest {
    private fun ctx(policy: SandboxPolicy = SandboxPolicy.UNRESTRICTED) =
        NeonEngine.builder().webGlobals(true).sandbox(policy).console(null).build().newContext()

    /** Runs [block] on a new thread. */
    private fun <T> thread(block: () -> T): CompletableFuture<T> {
        val f = CompletableFuture<T>()
        Thread {
            try {
                f.complete(block())
            } catch (e: Throwable) {
                f.completeExceptionally(e)
            }
        }.start()
        return f
    }

    /**
     * Starts runEventLoop on a new thread (after [before], on that thread) and returns once a task run by that loop
     * has seen it, so what the caller does next happens while the loop runs or waits. Something must keep the loop
     * pending (a timer) for it to wait.
     */
    private fun startLoop(c: NeonContext, timeoutMillis: Long = Long.MAX_VALUE, before: () -> Unit = {}): CompletableFuture<Boolean> {
        val running = CountDownLatch(1)
        val looping = AtomicReference<Thread>()
        c.setFunction("inLoop") {
            val yes = Thread.currentThread() === looping.get()
            if (yes) running.countDown()
            yes
        }
        c.eval("(function tick() { if (!inLoop()) setTimeout(tick, 1) })()")
        val loop = thread {
            before()
            looping.set(Thread.currentThread())
            c.runEventLoop(timeoutMillis)
        }
        assertTrue(running.await(10, TimeUnit.SECONDS), "the loop runs")
        return loop
    }

    @Test
    fun otherThreadsUseTheContextWhileTheLoopWaits() {
        ctx().use { c ->
            c.eval("var keep = setTimeout(() => {}, 10_000)")
            val loop = startLoop(c)
            val start = System.nanoTime()
            assertEquals(2, c.eval("1 + 1").asInt())
            val ms = (System.nanoTime() - start) / 1_000_000
            // (it used to wait for the loop to end)
            assertTrue(ms < 300, "eval waited $ms ms for the loop")
            c.eval("clearTimeout(keep)")
            assertTrue(loop.get(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun anInterruptEndsAWaitingLoop() {
        // with only the loop in the context
        ctx().use { c ->
            c.eval("setTimeout(() => {}, 10_000)")
            val loop = startLoop(c)
            c.interrupt()
            val e = assertThrows<ExecutionException> { loop.get(5, TimeUnit.SECONDS) }
            assertInstanceOf(NeonInterruptedException::class.java, e.cause)
        }
        // and while another thread evaluates: both end
        ctx().use { c ->
            c.eval("setTimeout(() => {}, 10_000)")
            val loop = startLoop(c)
            val busyRuns = CountDownLatch(1)
            c.setFunction("busyRuns") { busyRuns.countDown(); null }
            val busy = thread { c.eval("busyRuns(); for (;;) {}") }
            assertTrue(busyRuns.await(5, TimeUnit.SECONDS))
            c.interrupt()
            assertInstanceOf(NeonInterruptedException::class.java, assertThrows<ExecutionException> { busy.get(5, TimeUnit.SECONDS) }.cause)
            assertInstanceOf(NeonInterruptedException::class.java, assertThrows<ExecutionException> { loop.get(5, TimeUnit.SECONDS) }.cause)
        }
    }

    @Test
    fun theLoopKeepsItsOwnLimits() {
        // the evaluation of another thread starts limits of its own: the allocation budget, counted per thread, must be
        // the loop thread's again when it runs the task that evaluation scheduled
        ctx(SandboxPolicy.builder().maxAllocatedBytes(64L shl 20).build()).use { c ->
            c.eval("var ran = false, keep = setTimeout(() => {}, 10_000)")
            val loop = startLoop(c) {
                // the loop thread has allocated far more than the budget before it starts, a fresh thread far less
                var sink = 0
                repeat(256) { sink += ByteArray(1 shl 20).size }
                assertEquals(256 shl 20, sink)
            }
            thread { c.eval("setTimeout(() => { for (var i = 0; i < 20000; i++) {} ran = true; clearTimeout(keep) }, 50)") }.get(5, TimeUnit.SECONDS)
            assertTrue(loop.get(5, TimeUnit.SECONDS))
            assertTrue(c.eval("ran").asBoolean())
        }
    }

    @Test
    fun uncaughtErrorsGoToTheHandler() {
        ctx().use { c ->
            val seen = ArrayList<String>()
            c.setUncaughtErrorHandler { e, rejection -> seen.add((if (rejection) "rejection " else "exception ") + e.message) }
            c.setFunction("hostFails") { throw IllegalStateException("from the host") }
            c.eval("""
                var after = [];
                setTimeout(() => { throw new Error('in a timer') }, 1);
                setTimeout(hostFails, 2);
                setTimeout(() => after.push('next timer'), 20);
                queueMicrotask(() => { throw new TypeError('in a microtask') });
                queueMicrotask(() => after.push('next microtask'));
                Promise.reject(new Error('never handled'));
                Promise.reject(new Error('handled in the same turn')).catch(() => after.push('caught'));
                (async () => { throw new RangeError('in an async function') })();
            """)
            assertTrue(c.runEventLoop(5_000))
            assertEquals(listOf(
                "exception TypeError: in a microtask", "rejection Error: never handled", "rejection RangeError: in an async function",
                "exception Error: in a timer", "exception Host exception: java.lang.IllegalStateException: from the host",
            ), seen)
            assertEquals("next microtask,caught,next timer", c.eval("after.join()").asString())
            // the reason is the guest value
            seen.clear()
            var reason: NeonValue? = null
            c.setUncaughtErrorHandler { e, _ -> reason = e.guestValue }
            c.eval("Promise.reject({ code: 7 })")
            assertEquals(7, reason!!.getMember("code").asInt())
        }
    }

    @Test
    fun loopsMayEndInAnyOrder() {
        ctx().use { c ->
            // the first loop to enter ends first, while the second waits; the second then runs the remaining task
            c.eval("var r, keep = setTimeout(() => {}, 10_000)")
            val first = startLoop(c, 1_000)
            val second = startLoop(c, 10_000)
            assertFalse(first.get(5, TimeUnit.SECONDS), "the first loop times out")
            c.eval("setTimeout(() => { try { null.x } catch (e) { r = e instanceof TypeError } clearTimeout(keep) }, 10)")
            assertTrue(second.get(5, TimeUnit.SECONDS))
            assertTrue(c.eval("r").asBoolean())
        }
    }

    @Test
    fun loopsOnSeveralThreadsShareTheTasks() {
        ctx().use { c ->
            c.eval("var n = 0; for (var i = 0; i < 20; i++) setTimeout(() => n++, 10 * i)")
            val loops = List(2) { thread { c.runEventLoop(10_000) } }
            // both return once the tasks are done, whichever ran them
            for (l in loops) assertTrue(l.get(5, TimeUnit.SECONDS))
            assertEquals(20, c.eval("n").asInt())
        }
    }
}
