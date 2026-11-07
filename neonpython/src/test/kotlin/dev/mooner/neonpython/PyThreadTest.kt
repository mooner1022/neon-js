package dev.mooner.neonpython

import dev.mooner.neonjs.jit.Jit
import dev.mooner.neonjs.runtime.ExecutionTimeoutException
import dev.mooner.neonjs.runtime.InterruptedExecutionException
import dev.mooner.neonjs.runtime.VmConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * `_thread` for NeonPy's one thread (PyThread): what differs from CPython by design, which threading_module.py (its
 * output CPython's) cannot show. A thread cannot start, and a wait nothing could end raises; a wait with a timeout
 * waits it out under the agent's limits and interrupts, as time.sleep does.
 */
@Timeout(120)
class PyThreadTest {
    private fun config(limitMillis: Long = 0) = VmConfig().apply {
        executionMode = Jit.MODE_INTERPRETER
        backgroundJit = false
        maxExecutionMillis = limitMillis
    }

    private fun run(src: String): String {
        val out = StringBuilder()
        PyContext(out, config(), null, StringBuilder()).exec(src)
        return out.toString()
    }

    @Test
    fun threadsCannotStart() {
        // (CPython starts each of these threads; NeonPy's start fails as CPython's does where the OS refuses a
        // thread: RuntimeError("can't start new thread"), the thread left stopped, never in threading's tables)
        val src = """
            import _thread, threading, concurrent.futures as cf
            def show(f):
                try:
                    print('ok', f())
                except RuntimeError as e:
                    print(type(e).__name__, e)
            t = threading.Thread(target=print)
            show(t.start)
            print(t.is_alive(), repr(t), t.ident, threading.active_count(), threading._limbo, len(threading._active))
            show(t.start)
            show(t.join)
            show(threading.Timer(0.01, print).start)
            show(lambda: cf.ThreadPoolExecutor(max_workers=1).submit(print))
            show(lambda: _thread.start_new_thread(print, ()))
            h = _thread._ThreadHandle()
            show(lambda: _thread.start_joinable_thread(print, handle=h, daemon=False))
            print(h.is_done(), h.ident)
            show(lambda: _thread.start_joinable_thread(print, handle=h))
        """.trimIndent()
        assertEquals(
            """
            RuntimeError can't start new thread
            False <Thread(Thread-1 (print), stopped)> None 1 {} 1
            RuntimeError thread already started
            RuntimeError cannot join thread before it is started
            RuntimeError can't start new thread
            RuntimeError can't start new thread
            RuntimeError can't start new thread
            RuntimeError can't start new thread
            True 0
            RuntimeError thread already started

            """.trimIndent(), run(src))
    }

    @Test
    fun waitsNothingCouldEndRaise() {
        // (each would wait for ever in CPython: here no other thread could release the lock)
        val src = """
            import threading, queue
            def show(f):
                try:
                    print('ok', f())
                except RuntimeError as e:
                    print(type(e).__name__, e)
            lk = threading.Lock()
            lk.acquire()
            show(lk.acquire)
            show(threading.Event().wait)
            c = threading.Condition()
            with c:
                show(c.wait)
            show(threading.Semaphore(0).acquire)
            show(queue.Queue().get)
            show(threading.Barrier(2).wait)
            print(lk.locked(), c._is_owned())
        """.trimIndent()
        val held = "RuntimeError cannot acquire a lock this thread holds (one thread: it would wait for ever)\n"
        assertEquals(held.repeat(6) + "True False\n", run(src))
    }

    @Test
    fun aTimedWaitEndsAtTheAgentsTimeLimit() {
        val waits = listOf("threading.Event().wait(100)\n", "lk.acquire(timeout=100)\n", "c = threading.Condition()\nwith c:\n    c.wait(100)\n",
            "threading.Semaphore(0).acquire(timeout=100)\n")
        for (wait in waits) {
            val ctx = PyContext(StringBuilder(), config(limitMillis = 300), null, StringBuilder())
            // (imported before the limits start: each exec has its own globals, the module stays in sys.modules)
            ctx.exec("import threading\n")
            // (PyContext does not start the limits itself: the host does, as here)
            ctx.agent.startLimits()
            val t0 = System.nanoTime()
            assertThrows(ExecutionTimeoutException::class.java) { ctx.exec("import threading\nlk = threading.Lock()\nlk.acquire()\n$wait") }
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue(ms in 250..5_000, "$wait ended after $ms ms")
        }
    }

    @Test
    fun aTimedWaitEndsWhenInterrupted() {
        val ctx = PyContext(StringBuilder(), config(), null, StringBuilder())
        ctx.exec("import threading\n")
        ctx.agent.startLimits()
        val interrupter = Thread {
            Thread.sleep(200)
            ctx.agent.interruptRequested = true
        }
        interrupter.start()
        val t0 = System.nanoTime()
        assertThrows(InterruptedExecutionException::class.java) { ctx.exec("import threading\nthreading.Event().wait(100)\n") }
        interrupter.join()
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms in 150..5_000, "ended after $ms ms")
    }
}
