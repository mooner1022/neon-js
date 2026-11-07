package dev.mooner.neonpython

import dev.mooner.neonjs.runtime.VmConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Where asyncio departs from CPython in the sandbox (docs/PYTHON_PLATFORM.md §12.2). There is one thread, so the
 * executor and to_thread cannot start a worker, and a loop with nothing that could wake it raises instead of waiting
 * for ever. The network and processes are refused. The loop is still fine afterwards. What stays CPython's is in
 * programs/asyncio_module.py.
 */
class PyAsyncioTest {
    private fun run(src: String): String {
        val out = StringBuilder()
        PyContext(out, VmConfig(), null, out).exec(
            "def t(label, f):\n    try:\n        print(label, '->', f())\n    except BaseException as e:\n        print(label, '!!', type(e).__name__, e)\n" +
                src.trimIndent() + "\n")
        return out.toString()
    }

    @Test
    fun whatOneThreadAndNoNetworkRefuse() {
        val out = run("""
            import asyncio, sys
            async def executor():
                return await asyncio.get_running_loop().run_in_executor(None, sum, [1, 2])
            async def to_thread():
                return await asyncio.to_thread(sum, [1, 2])
            async def network():
                return await asyncio.open_connection('127.0.0.1', 80)
            async def server():
                return await asyncio.start_server(lambda r, w: None, '127.0.0.1', 0)
            async def forever():
                await asyncio.get_running_loop().create_future()
            async def subprocess():
                return await asyncio.create_subprocess_exec('ls')
            t('executor', lambda: asyncio.run(executor()))
            t('to_thread', lambda: asyncio.run(to_thread()))
            t('network', lambda: asyncio.run(network()))
            t('server', lambda: asyncio.run(server()))
            t('forever', lambda: asyncio.run(forever()))
            t('subprocess', lambda: asyncio.run(subprocess()))
            t('after', lambda: asyncio.run(asyncio.sleep(0, 'still runs')))
            t('hooks', lambda: sys.get_asyncgen_hooks())
        """)
        val refused = "PermissionError [Errno 1] Operation not permitted"
        assertEquals(listOf(
            "executor !! RuntimeError can't start new thread",
            "to_thread !! RuntimeError can't start new thread",
            "network !! $refused",
            "server !! PermissionError [Errno 1] error while attempting to bind on address ('127.0.0.1', 0): [errno 1] operation not permitted",
            "forever !! RuntimeError cannot wait for descriptors nothing else can make ready (one thread: it would wait for ever)",
            "subprocess !! $refused",
            "after -> still runs",
            "hooks -> asyncgen_hooks(firstiter=None, finalizer=None)",
        ).joinToString("\n", postfix = "\n"), out)
    }

    @Test
    fun asyncGeneratorHooks() {
        val out = run("""
            import sys
            calls = []
            t('bad', lambda: sys.set_asyncgen_hooks(1))
            t('bad kw', lambda: sys.set_asyncgen_hooks(finalizer='x'))
            t('unknown', lambda: sys.set_asyncgen_hooks(bogus=1))
            t('too many', lambda: sys.set_asyncgen_hooks(None, None, None))
            sys.set_asyncgen_hooks(firstiter=lambda g: calls.append(g.__name__), finalizer=print)
            async def ag():
                yield 1
            g = ag()
            t('none yet', lambda: list(calls))
            a = g.__anext__()
            b = g.__anext__()
            a.close()
            b.close()
            g2 = ag()
            g2.aclose().close()
            t('once each', lambda: calls)
            t('kept', lambda: sys.get_asyncgen_hooks().finalizer is print)
            sys.set_asyncgen_hooks(firstiter=None)
            t('cleared', lambda: (sys.get_asyncgen_hooks().firstiter, type(sys.get_asyncgen_hooks())((1, 2))))
        """)
        assertEquals(listOf(
            "bad !! TypeError callable firstiter expected, got int",
            "bad kw !! TypeError callable finalizer expected, got str",
            "unknown !! TypeError this function got an unexpected keyword argument 'bogus'",
            "too many !! TypeError function takes at most 2 arguments (3 given)",
            "none yet -> []",
            "once each -> ['ag', 'ag']",
            "kept -> True",
            "cleared -> (None, asyncgen_hooks(firstiter=1, finalizer=2))",
        ).joinToString("\n", postfix = "\n"), out)
    }
}
