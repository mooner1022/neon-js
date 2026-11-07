package dev.mooner.neonpython

import dev.mooner.neonjs.runtime.ExecutionTimeoutException
import dev.mooner.neonjs.runtime.VmConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What socket, select, signal and subprocess do where a sandbox departs from CPython (docs/PYTHON_PLATFORM.md §3.8,
 * §12.2): the network refused, a wait nothing could end raised at once (one thread), a wait with a timeout ended by
 * the agent's limits, signals only the program's own, no process started; and the class layouts asyncio's transports
 * take. What stays in the program is CPython's (programs/socket_module.py).
 */
class PySocketTest {
    private fun run(src: String, config: VmConfig = VmConfig()): String {
        val out = StringBuilder()
        PyContext(out, config, null, out).exec(src.trimIndent() + "\n")
        return out.toString()
    }

    /** Each line of [src] printed as `label -> value` or `label !! Error message` (a BaseException's too). */
    private fun probe(src: String): String = run(
        "def t(label, f):\n    try:\n        print(label, '->', f())\n    except BaseException as e:\n        print(label, '!!', type(e).__name__, e)\n" +
            src.trimIndent())

    private fun lines(vararg l: String) = l.joinToString("\n", postfix = "\n")

    @Test
    fun theNetworkIsRefused() {
        val out = probe("""
            import socket
            s = socket.socket()
            u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            t('connect', lambda: s.connect(('127.0.0.1', 80)))
            t('connect_ex', lambda: s.connect_ex(('127.0.0.1', 80)))
            t('bind', lambda: s.bind(('127.0.0.1', 0)))
            t('listen', lambda: s.listen())
            t('accept', lambda: s.accept())
            t('sendto', lambda: u.sendto(b'x', ('127.0.0.1', 9)))
            t('recvfrom', lambda: u.recvfrom(10))
            t('recvfrom_into', lambda: u.recvfrom_into(bytearray(4)))
            t('getaddrinfo', lambda: socket.getaddrinfo('localhost', 80))
            t('gethostbyname', lambda: socket.gethostbyname('localhost'))
            t('gethostname', socket.gethostname)
            t('getfqdn', socket.getfqdn)
            t('getservbyname', lambda: socket.getservbyname('http'))
            t('if_nameindex', socket.if_nameindex)
            t('create_connection', lambda: socket.create_connection(('example.com', 80)))
            t('create_server', lambda: socket.create_server(('127.0.0.1', 0)))
            t('raw', lambda: socket.socket(socket.AF_INET, socket.SOCK_RAW))
            t('netlink', lambda: socket.socket(socket.AF_NETLINK, socket.SOCK_RAW))
            t('still made', lambda: (s.family, s.type, s.getsockname(), u.getsockname()))
        """)
        val refused = "PermissionError [Errno 1] Operation not permitted"
        assertEquals(lines(
            "connect !! $refused", "connect_ex -> 1", "bind !! $refused", "listen !! $refused", "accept !! $refused",
            "sendto !! $refused", "recvfrom !! $refused", "recvfrom_into !! $refused", "getaddrinfo !! $refused",
            "gethostbyname !! $refused", "gethostname !! $refused", "getfqdn !! $refused", "getservbyname !! $refused",
            "if_nameindex !! $refused", "create_connection !! $refused",
            "create_server !! $refused (while attempting to bind on address ('127.0.0.1', 0))",
            "raw !! $refused", "netlink !! $refused",
            "still made -> (<AddressFamily.AF_INET: 2>, <SocketKind.SOCK_STREAM: 1>, ('0.0.0.0', 0), ('0.0.0.0', 0))",
        ), out)
    }

    @Test
    fun aWaitNothingCanEndRaises() {
        val out = probe("""
            import socket, select, signal, os
            a, b = socket.socketpair()
            r, w = os.pipe()
            t('recv', lambda: b.recv(1))
            t('os.read', lambda: os.read(b.fileno(), 1))
            t('pipe', lambda: os.read(r, 1))
            t('select', lambda: select.select([b, r], [], []))
            t('select nothing', lambda: select.select([], [], [], None))
            t('pause', signal.pause)
            a.setblocking(False)
            t('fill', lambda: [a.send(b'x' * 100000) for i in range(2)] and None)
            a.setblocking(True)
            t('send', lambda: a.send(b'x' * 100000))
            t('sendall', lambda: a.sendall(b'x' * 100000))
            t('peer data still there', lambda: len(b.recv(1 << 20)) > 0)
        """)
        fun never(what: String) = "RuntimeError cannot wait for $what (one thread: it would wait for ever)"
        assertEquals(lines(
            "recv !! ${never("data nothing else can send")}", "os.read !! ${never("data nothing else can send")}",
            "pipe !! ${never("data nothing else can send")}",
            "select !! ${never("descriptors nothing else can make ready")}",
            "select nothing !! ${never("descriptors nothing else can make ready")}",
            "pause !! ${never("a signal nothing else can send")}",
            "fill -> None", "send !! ${never("room nothing else can make")}", "sendall !! ${never("room nothing else can make")}",
            "peer data still there -> True",
        ), out)
    }

    @Test
    fun waitsWithATimeoutEndWithTheAgentsLimits() {
        for (src in listOf(
            "import select\nselect.select([], [], [], 100)",
            "import socket\na, b = socket.socketpair()\nb.settimeout(100)\nb.recv(1)",
            "import selectors, socket\na, b = socket.socketpair()\ns = selectors.DefaultSelector()\ns.register(b, selectors.EVENT_READ)\ns.select(100)",
            // (not caught by the program)
            "import select\ntry:\n    select.select([], [], [], 100)\nexcept BaseException:\n    print('caught')",
        )) {
            val cfg = VmConfig().apply { maxExecutionMillis = 300 }
            val out = StringBuilder()
            val ctx = PyContext(out, cfg, null, out)
            ctx.agent.startLimits()
            val t0 = System.nanoTime()
            assertThrows(ExecutionTimeoutException::class.java) { ctx.exec(src + "\n") }
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue(ms in 250..20_000, "$src: ended after $ms ms")
            assertEquals("", out.toString(), src)
        }
    }

    @Test
    fun selectsOnTheContextsStreamsAndMemory() {
        val out = probe("""
            import select, selectors
            t('stdin at its end, outputs writable', lambda: select.select([0, 1, 2], [0, 1, 2], [], 0))
            t('stdin reads', lambda: __import__('os').read(0, 10))
            t('no poll', lambda: [hasattr(select, n) for n in ('poll', 'epoll', 'kqueue', 'devpoll')])
            t('DefaultSelector', lambda: selectors.DefaultSelector.__name__)
        """)
        assertEquals(lines(
            "stdin at its end, outputs writable -> ([0], [1, 2], [])", "stdin reads -> b''",
            "no poll -> [False, False, False, False]", "DefaultSelector -> SelectSelector",
        ), out)
    }

    @Test
    fun signalsComeOnlyFromTheProgram() {
        val out = probe("""
            import signal
            t('alarm', lambda: signal.alarm(5))
            t('alarm 0', lambda: signal.alarm(0))
            t('setitimer', lambda: signal.setitimer(signal.ITIMER_REAL, 0.5))
            t('setitimer 0', lambda: signal.setitimer(signal.ITIMER_REAL, 0))
            t('stop', lambda: signal.raise_signal(signal.SIGTSTP))
            t('chld', lambda: signal.raise_signal(signal.SIGCHLD))
            t('term', lambda: signal.raise_signal(signal.SIGTERM))
            t('kill', lambda: signal.raise_signal(signal.SIGKILL))
            t('int', lambda: signal.raise_signal(signal.SIGINT))
            signal.signal(signal.SIGTERM, lambda n, f: print('handled', n, f.f_code.co_name))
            t('handled', lambda: signal.raise_signal(signal.SIGTERM))
        """)
        assertEquals(lines(
            "alarm !! PermissionError [Errno 1] Operation not permitted", "alarm 0 -> 0",
            "setitimer !! ItimerError [Errno 1] Operation not permitted", "setitimer 0 -> (0.0, 0.0)",
            "stop -> None", "chld -> None", "term !! SystemExit 143", "kill !! SystemExit 137", "int !! KeyboardInterrupt ",
            "handled 15 <lambda>", "handled -> None",
        ), out)
    }

    @Test
    fun basesSharingTheirSlotsCombine() {
        // (asyncio's transports: Transport(ReadTransport, WriteTransport), both BaseTransport's slots only; Linux CPython's output)
        val out = probe("""
            class Base:
                __slots__ = ('_extra',)
            class Read(Base):
                __slots__ = ()
            class Write(Base):
                __slots__ = ()
            class Both(Read, Write):
                __slots__ = ()
            b = Both()
            b._extra = 1
            t('both', lambda: (b._extra, Both.__mro__[-2].__name__, hasattr(b, '__dict__')))
            class Other:
                __slots__ = ('x',)
            class More(Read):
                __slots__ = ('y',)
            t('unrelated', lambda: type('C', (Read, Other), {}))
            t('extends', lambda: type('D', (More, Write), {}).__mro__[1].__name__)
            t('other way', lambda: type('E', (Write, More), {}).__mro__[1].__name__)
            t('two more', lambda: type('F', (More, type('G', (Write,), {'__slots__': ('z',)})), {}))
        """)
        assertEquals(lines(
            "both -> (1, 'Base', False)", "unrelated !! TypeError multiple bases have instance lay-out conflict",
            "extends -> More", "other way -> Write", "two more !! TypeError multiple bases have instance lay-out conflict",
        ), out)
    }

    @Test
    fun processesAreNotStarted() {
        val out = probe("""
            import _posixsubprocess, os, sys
            t('fork_exec', lambda: _posixsubprocess.fork_exec(*range(22)))
            t('waitpid', lambda: os.waitpid(-1, 0))
            t('wait', os.wait)
            try:
                import threading
            except ModuleNotFoundError:
                # (threading not bundled yet: what subprocess.Popen takes of it)
                import types, _thread
                sys.modules['threading'] = types.SimpleNamespace(Lock=_thread.allocate_lock)
            import subprocess
            def opened(lo):
                n = []
                for fd in range(lo, lo + 20):
                    try:
                        os.fstat(fd)
                        n.append(fd)
                    except OSError:
                        pass
                return n
            r, w = os.pipe()
            os.close(r)
            os.close(w)
            t('run', lambda: subprocess.run(['ls']))
            t('capture', lambda: subprocess.run(['echo', 'hi'], capture_output=True))
            t('shell', lambda: subprocess.check_output('echo hi', shell=True))
            t('its pipes closed', lambda: opened(w + 1))
        """)
        val refused = "PermissionError [Errno 1] Operation not permitted"
        assertEquals(lines(
            "fork_exec !! $refused", "waitpid !! ChildProcessError [Errno 10] No child processes",
            "wait !! ChildProcessError [Errno 10] No child processes",
            "run !! $refused", "capture !! $refused", "shell !! $refused", "its pipes closed -> []",
        ), out)
    }
}
