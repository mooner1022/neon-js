# socket, select, selectors and signal: what stays in the program (socket pairs, pipes, select over them, signal
# handlers), as Linux CPython 3.14 does it; descriptors printed as differences only (CPython reuses closed ones).
import _socket, _signal, socket, select, selectors, signal, os, stat, time, errno


def t(label, f):
    try:
        r = f()
    except BaseException as e:
        print(label, '->', type(e).__name__, e.args)
    else:
        print(label, '=', repr(r))


def constants():
    ints = [(n, getattr(_socket, n)) for n in dir(_socket) if type(getattr(_socket, n)) is int]
    print(len(ints), sum(v for n, v in ints) % 1000003, socket.has_ipv6)
    print(socket.AF_UNIX, socket.AF_INET, socket.AF_INET6, socket.SOCK_STREAM, socket.SOCK_DGRAM, socket.SOCK_NONBLOCK)
    print(socket.SOL_SOCKET, socket.SO_REUSEADDR, socket.IPPROTO_TCP, socket.TCP_NODELAY, socket.MSG_PEEK, socket.SHUT_RDWR)
    print(repr(socket.AF_UNIX), repr(socket.SOCK_DGRAM), repr(socket.MSG_PEEK), len(socket.AddressFamily), len(socket.SocketKind))
    print(socket.error is OSError, socket.timeout is TimeoutError, issubclass(socket.herror, OSError), socket.gaierror.__module__)
    print(_socket.SocketType is _socket.socket, _socket.socket.__module__, socket.socket.__mro__)
    print(select.error is OSError, select.PIPE_BUF)


def pair_basics():
    a, b = socket.socketpair()
    fa, fb = a.fileno(), b.fileno()
    print(fb - fa, a.family, a.type, a.proto, type(a).__name__)
    print(repr(a).replace(str(fa), 'A'))
    t('names', lambda: (a.getsockname(), a.getpeername()))
    t('send', lambda: a.send(b'hello'))
    t('recv 2', lambda: b.recv(2))
    t('recv 100', lambda: b.recv(100))
    t('blocking', lambda: (a.getblocking(), a.gettimeout(), a.timeout, os.get_blocking(fa)))
    b.setblocking(False)
    t('nonblocking', lambda: (b.getblocking(), b.gettimeout(), os.get_blocking(fb)))
    t('recv empty', lambda: b.recv(1))
    try:
        b.recv(1)
    except BlockingIOError as e:
        print(str(e), e.errno, e.strerror)
    b.settimeout(0.05)
    t('timeout set', lambda: (b.getblocking(), b.gettimeout(), os.get_blocking(fb)))
    t0 = time.monotonic()
    try:
        b.recv(1)
    except TimeoutError as e:
        print('timed out', repr(e), e.errno, type(e) is socket.timeout, 0.04 < time.monotonic() - t0 < 2)
    b.settimeout(None)
    t('timeout none', lambda: (b.getblocking(), b.gettimeout(), os.get_blocking(fb)))
    b.settimeout(0)
    t('timeout 0', lambda: (b.getblocking(), b.gettimeout(), os.get_blocking(fb)))
    b.setblocking(True)
    t('blocking again', lambda: (b.getblocking(), b.gettimeout(), os.get_blocking(fb)))
    t('settimeout neg', lambda: b.settimeout(-1))
    t('settimeout str', lambda: b.settimeout('x'))
    t('settimeout nan', lambda: b.settimeout(float('nan')))
    t('settimeout int', lambda: (b.settimeout(3), b.gettimeout(), b.settimeout(None)))
    a.close()
    b.close()


def pair_data():
    a, b = socket.socketpair()
    t('sendall', lambda: a.sendall(b'abc'))
    buf = bytearray(10)
    t('recv_into', lambda: (b.recv_into(buf), bytes(buf)))
    a.send(b'xyz')
    t('recv_into n', lambda: (b.recv_into(buf, 2), bytes(buf)))
    t('rest', lambda: b.recv(10))
    mv = memoryview(bytearray(4))
    a.send(b'12345')
    t('recv_into memoryview', lambda: (b.recv_into(mv), bytes(mv)))
    t('recv last', lambda: b.recv(10))
    t('recv_into kw', lambda: (a.send(b'kw'), b.recv_into(buffer=bytearray(5), nbytes=1), b.recv(5)))
    t('peek', lambda: (a.send(b'pp'), b.recv(1, socket.MSG_PEEK), b.recv(5)))
    t('recv 0', lambda: b.recv(0))
    t('memoryview, bytearray', lambda: (a.send(memoryview(b'mv')), a.send(bytearray(b'ba')), b.recv(9)))
    t('chunks', lambda: (a.send(b'one'), a.send(b'two'), a.send(b'three'), b.recv(4), b.recv(4), b.recv(100)))
    t('recvfrom', lambda: (a.send(b'rf'), b.recvfrom(5)))
    t('recvfrom_into', lambda: (a.send(b'rfi'), b.recvfrom_into(bytearray(5))))
    t('dontwait', lambda: b.recv(1, socket.MSG_DONTWAIT))
    t('os.write', lambda: os.write(a.fileno(), b'via os'))
    t('os.read', lambda: (os.read(b.fileno(), 3), b.recv(10)))
    st = os.fstat(a.fileno())
    print('fstat', oct(st.st_mode), st.st_size, stat.S_ISSOCK(st.st_mode))
    t('sendto', lambda: a.sendto(b'x', ''))
    t('big nonblocking', lambda: big(a, b))
    a.close()
    b.close()


def big(a, b):
    a.setblocking(False)
    total = 0
    while True:
        try:
            total += a.send(b'x' * 100000)
        except BlockingIOError:
            break
    full = select.select([], [a], [], 0)[1] == []
    got = 0
    b.setblocking(False)
    while True:
        try:
            got += len(b.recv(1 << 20))
        except BlockingIOError:
            break
    return total > 100000, full, got == total, select.select([], [a], [], 0)[1] == [a]


def misuse():
    a, b = socket.socketpair()
    a.setblocking(False)
    t('recv neg', lambda: a.recv(-1))
    t('recv str', lambda: a.recv('x'))
    t('recv none', lambda: a.recv())
    t('recv 3', lambda: a.recv(1, 0, 0))
    t('recv kw', lambda: a.recv(bufsize=1))
    t('send str', lambda: a.send('x'))
    t('send none', lambda: a.send())
    t('sendall str', lambda: a.sendall('x'))
    t('recv_into bytes', lambda: a.recv_into(b'xx'))
    t('recv_into neg', lambda: a.recv_into(bytearray(3), -1))
    t('recv_into big', lambda: a.recv_into(bytearray(3), 5))
    t('recv_into none', lambda: a.recv_into())
    t('recv_into badkw', lambda: a.recv_into(bytearray(1), x=0))
    t('sendto 1', lambda: a.sendto(b''))
    t('settimeout none', lambda: a.settimeout())
    t('gettimeout 1', lambda: a.gettimeout(1))
    t('shutdown bad', lambda: a.shutdown(7))
    t('shutdown str', lambda: a.shutdown('x'))
    t('getsockopt 4', lambda: a.getsockopt(1, 2, 3, 4))
    t('setsockopt 2', lambda: a.setsockopt(1, 2))
    t('connect none', lambda: a.connect())
    t('listen 2', lambda: a.listen(1, 2))
    t('dup none', lambda: _socket.dup())
    t('dup neg', lambda: _socket.dup(-1))
    t('close str', lambda: _socket.close('x'))
    t('socketpair kw', lambda: _socket.socketpair(family=1))
    t('socketpair 4', lambda: _socket.socketpair(1, 1, 0, 0))
    t('socket kw', lambda: _socket.socket(foo=1))
    t('socket 5', lambda: _socket.socket(1, 1, 0, None, 5))
    t('socket float', lambda: _socket.socket(1.5))
    a.close()
    b.close()


def options():
    a, b = socket.socketpair()
    S = socket.SOL_SOCKET
    t('get', lambda: [a.getsockopt(S, o) for o in (socket.SO_TYPE, socket.SO_ERROR, socket.SO_REUSEADDR, socket.SO_KEEPALIVE,
                                                    socket.SO_DOMAIN, socket.SO_PROTOCOL, socket.SO_ACCEPTCONN, socket.SO_LINGER)])
    t('bufs', lambda: (a.getsockopt(S, socket.SO_SNDBUF), a.getsockopt(S, socket.SO_RCVBUF)))
    t('buflen', lambda: (a.getsockopt(S, socket.SO_LINGER, 8), a.getsockopt(S, socket.SO_TYPE, 4), a.getsockopt(S, socket.SO_TYPE, 2),
                         a.getsockopt(S, socket.SO_TYPE, 8), a.getsockopt(S, socket.SO_TYPE, 0)))
    t('buflen 2000', lambda: a.getsockopt(S, socket.SO_TYPE, 2000))
    t('set', lambda: (a.setsockopt(S, socket.SO_REUSEADDR, 1), a.getsockopt(S, socket.SO_REUSEADDR)))
    t('set bool', lambda: (a.setsockopt(S, socket.SO_KEEPALIVE, 7), a.getsockopt(S, socket.SO_KEEPALIVE)))
    t('set neg', lambda: (a.setsockopt(S, socket.SO_REUSEADDR, -5), a.getsockopt(S, socket.SO_REUSEADDR)))
    t('set bytes', lambda: (a.setsockopt(S, socket.SO_REUSEADDR, b'\x00\x00\x00\x00'), a.getsockopt(S, socket.SO_REUSEADDR)))
    t('set short', lambda: a.setsockopt(S, socket.SO_REUSEADDR, b'\x01'))
    t('set none', lambda: a.setsockopt(S, socket.SO_REUSEADDR, None))
    t('set none 4', lambda: a.setsockopt(S, socket.SO_REUSEADDR, None, 4))
    t('set str', lambda: a.setsockopt(S, socket.SO_REUSEADDR, 'x'))
    t('set big', lambda: a.setsockopt(S, socket.SO_REUSEADDR, 2 ** 40))
    t('set type', lambda: a.setsockopt(S, socket.SO_TYPE, 1))
    t('set bufs', lambda: (a.setsockopt(S, socket.SO_SNDBUF, 4096), a.setsockopt(S, socket.SO_RCVBUF, 100),
                           a.getsockopt(S, socket.SO_SNDBUF), a.getsockopt(S, socket.SO_RCVBUF)))
    t('linger', lambda: (a.setsockopt(S, socket.SO_LINGER, b'\x01\x00\x00\x00\x05\x00\x00\x00'), a.getsockopt(S, socket.SO_LINGER, 8),
                         a.getsockopt(S, socket.SO_LINGER)))
    t('linger int', lambda: a.setsockopt(S, socket.SO_LINGER, 1))
    t('unknown', lambda: a.getsockopt(S, 9999))
    t('tcp level', lambda: a.getsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY))
    t('bad level', lambda: a.setsockopt(12345, 1, 1))
    a.close()
    b.close()


def dups():
    a, b = socket.socketpair()
    fa = a.fileno()
    d = a.dup()
    t('dup', lambda: (d.fileno() != fa, d.family, d.type, type(d).__name__, d.send(b'dd'), b.recv(5)))
    d.close()
    t('after dup closed', lambda: (a.send(b'still'), b.recv(9)))
    n = _socket.dup(fa)
    t('_socket.dup', lambda: (n != fa, os.close(n)))
    t('fromfd', lambda: (lambda s: (s.family, s.type, s.proto, s.send(b'ff'), b.recv(9), s.close()))(socket.fromfd(fa, socket.AF_UNIX, socket.SOCK_STREAM)))
    t('fileno=', lambda: (lambda s: (s.family, s.type, s.proto, s.detach() == fa))(socket.socket(fileno=fa)))
    t('_socket fileno=', lambda: (lambda s: (s.family, s.type, s.proto, repr(s).startswith('<socket object, fd='), s.detach() == fa))(_socket.socket(fileno=fa)))
    t('fileno= closed', lambda: socket.socket(fileno=99999))
    t('fileno= neg', lambda: socket.socket(fileno=-5))
    t('fileno= float', lambda: socket.socket(fileno=1.5))
    t('fileno= stdout', lambda: socket.socket(fileno=1))
    r, w = os.pipe()
    t('fileno= pipe', lambda: socket.socket(fileno=r))
    os.close(r)
    os.close(w)
    t('detach', lambda: (lambda x: (x > 0, a.fileno(), repr(a)[:41]))(a.detach()))
    os.close(fa)
    b.close()


def shutdowns():
    c, d = socket.socketpair()
    c.send(b'before')
    c.shutdown(socket.SHUT_WR)
    t('shut wr: recv', lambda: (d.recv(10), d.recv(10), d.recv(10)))
    t('shut wr: send', lambda: c.send(b'x'))
    t('shut wr: peer sends', lambda: (d.send(b'back'), c.recv(10)))
    d.shutdown(socket.SHUT_RD)
    t('shut rd: recv', lambda: d.recv(10))
    t('shut rd: peer sends', lambda: c.send(b'x'))
    c.close()
    d.close()
    c, d = socket.socketpair()
    c.close()
    t('peer closed: recv', lambda: (d.recv(10), d.recv(10)))
    t('peer closed: send', lambda: d.send(b'x'))
    t('peer closed: select', lambda: select.select([d], [d], [d], 0) == ([d], [d], []))
    d.close()
    c, d = socket.socketpair()
    d.send(b'unread')
    c.send(b'pending')
    c.close()
    for i in range(5):
        t('reset %d' % i, lambda: d.recv(3))
    t('reset: send', lambda: d.send(b'x'))
    d.close()
    c, d = socket.socketpair()
    d.send(b'unread')
    c.close()
    t('reset: SO_ERROR', lambda: (d.getsockopt(socket.SOL_SOCKET, socket.SO_ERROR), d.getsockopt(socket.SOL_SOCKET, socket.SO_ERROR), d.recv(1)))
    d.close()


def closed():
    c, d = socket.socketpair()
    fc = c.fileno()
    c.close()
    t('fileno', lambda: c.fileno())
    t('repr', lambda: repr(c))
    t('recv', lambda: c.recv(1))
    t('send', lambda: c.send(b'x'))
    t('close again', lambda: c.close())
    t('settimeout', lambda: c.settimeout(1))
    t('gettimeout', lambda: (c.gettimeout(), c.getblocking()))
    t('setblocking', lambda: c.setblocking(False))
    t('getsockopt', lambda: c.getsockopt(socket.SOL_SOCKET, socket.SO_TYPE))
    t('shutdown', lambda: c.shutdown(socket.SHUT_RDWR))
    t('getsockname', lambda: c.getsockname())
    t('detach', lambda: c.detach())
    t('os.read', lambda: os.read(fc, 1))
    t('attrs', lambda: (c.family, c.type, c.proto))
    t('dup', lambda: c.dup())
    t('_socket.close', lambda: _socket.close(fc))
    d.close()


def kinds():
    t('inet', lambda: socket.socketpair(socket.AF_INET))
    t('bad family', lambda: _socket.socketpair(12345))
    t('dgram', lambda: [(x.family, x.type) for x in socket.socketpair(socket.AF_UNIX, socket.SOCK_DGRAM)])
    t('seqpacket', lambda: [(x.family, x.type) for x in socket.socketpair(socket.AF_UNIX, socket.SOCK_SEQPACKET)])
    t('bad type', lambda: socket.socketpair(socket.AF_UNIX, 99))
    t('bad proto', lambda: socket.socketpair(socket.AF_UNIX, socket.SOCK_STREAM, 6))
    t('nonblock', lambda: [(x.type, x.getblocking(), x.gettimeout()) for x in socket.socketpair(socket.AF_UNIX, socket.SOCK_STREAM | socket.SOCK_NONBLOCK)])
    t('_socketpair', lambda: [(type(x).__name__, x.family, x.type, x.proto) for x in _socket.socketpair()])
    c, d = socket.socketpair(socket.AF_UNIX, socket.SOCK_DGRAM)
    c.send(b'one')
    c.send(b'two22')
    c.send(b'three')
    t('dgram recv', lambda: (d.recv(2), d.recv(10), d.recv_into(bytearray(1))))
    c.send(b'')
    d.setblocking(False)
    t('dgram empty', lambda: (d.recv(5), d.recv(5)))
    t('dgram recvfrom', lambda: (c.send(b'q'), d.recvfrom(5)))
    c.close()
    t('dgram peer closed: recv', lambda: d.recv(5))
    t('dgram peer closed: send', lambda: d.send(b'z'))
    d.close()
    c, d = socket.socketpair(socket.AF_UNIX, socket.SOCK_SEQPACKET)
    c.send(b'packet one')
    c.send(b'packet two')
    t('seqpacket recv', lambda: (d.recv(6), d.recv(100)))
    c.close()
    t('seqpacket peer closed', lambda: d.recv(5))
    d.close()


def unconnected():
    s = socket.socket()
    print(repr(s).replace(str(s.fileno()), 'S'))
    t('fresh', lambda: (s.family, s.type, s.proto, s.gettimeout(), s.getblocking()))
    t('getsockname', lambda: s.getsockname())
    t('getpeername', lambda: s.getpeername())
    t('recv', lambda: s.recv(1))
    t('send', lambda: s.send(b'x'))
    t('shutdown', lambda: s.shutdown(socket.SHUT_RDWR))
    t('getsockopt', lambda: [s.getsockopt(socket.SOL_SOCKET, o) for o in (socket.SO_TYPE, socket.SO_ERROR, socket.SO_DOMAIN, socket.SO_PROTOCOL)])
    t('bufs', lambda: (s.getsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF), s.getsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF)))
    t('tcp', lambda: [s.getsockopt(socket.IPPROTO_TCP, o) for o in (socket.TCP_NODELAY, socket.TCP_KEEPIDLE, socket.TCP_KEEPINTVL, socket.TCP_KEEPCNT)])
    t('nodelay', lambda: (s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1), s.getsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY)))
    t('ip tos', lambda: (s.setsockopt(socket.IPPROTO_IP, socket.IP_TOS, 16), s.getsockopt(socket.IPPROTO_IP, socket.IP_TOS)))
    t('ipv6 on 4', lambda: s.getsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY))
    t('select', lambda: select.select([s], [s], [s], 0) == ([s], [s], []))
    t('fstat', lambda: oct(os.fstat(s.fileno()).st_mode))
    t('os.read', lambda: os.read(s.fileno(), 1))
    s.close()
    u = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    t('udp send', lambda: u.send(b'x'))
    u.setblocking(False)
    t('udp recv', lambda: u.recv(1))
    t('udp select', lambda: select.select([u], [u], [], 0) == ([], [u], []))
    u.close()
    x = socket.socket(socket.AF_UNIX)
    print(repr(x).replace(str(x.fileno()), 'X'))
    t('unix', lambda: (x.getsockname(), x.recv(1)))
    t('unix send', lambda: x.send(b'x'))
    t('unix getpeername', lambda: x.getpeername())
    x.close()
    s6 = socket.socket(socket.AF_INET6)
    t('inet6', lambda: (s6.getsockname(), s6.getsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY)))
    s6.close()
    t('bad family', lambda: socket.socket(12345))
    t('bad type', lambda: socket.socket(socket.AF_INET, 99))
    t('bad proto', lambda: socket.socket(socket.AF_INET, socket.SOCK_STREAM, 17))
    t('inet seqpacket', lambda: socket.socket(socket.AF_INET, socket.SOCK_SEQPACKET))
    t('protos', lambda: [(lambda z: (z.proto, z.getsockopt(socket.SOL_SOCKET, socket.SO_PROTOCOL), z.close()))(socket.socket(*k))
                         for k in ((2, 1, 6), (2, 2, 17), (2, 2), (1, 1, 1))])
    t('nonblock type', lambda: (lambda z: (z.type, z.gettimeout(), os.get_blocking(z.fileno()), z.close()))(socket.socket(socket.AF_INET, socket.SOCK_STREAM | socket.SOCK_NONBLOCK)))
    t('_socket.socket()', lambda: (lambda z: (z.family, z.type, z.proto, z.close()))(_socket.socket()))


def defaults():
    t('default', lambda: socket.getdefaulttimeout())
    socket.setdefaulttimeout(2.5)
    e, g = socket.socketpair()
    t('applied', lambda: (e.gettimeout(), os.get_blocking(e.fileno()), e.getblocking()))
    socket.setdefaulttimeout(None)
    t('neg', lambda: socket.setdefaulttimeout(-2))
    t('str', lambda: socket.setdefaulttimeout('x'))
    t('with', lambda: (e.__enter__() is e, e.__exit__(None, None, None), e.fileno()))
    t('pickle', lambda: __import__('pickle').dumps(g))
    c, d = socket.socketpair()
    t('makefile r', lambda: (lambda f: (type(f).__name__, c.send(b'line1\nline2\n'), f.readline(), f.readline(), f.close()))(d.makefile('rb')))
    t('makefile w', lambda: (lambda f: (f.write(b'wf'), f.flush(), d.recv(5), f.close()))(c.makefile('wb')))
    t('io refs', lambda: (c._io_refs, c._closed))
    t('inheritable', lambda: (c.get_inheritable(), c.set_inheritable(True), c.get_inheritable()))
    for x in (c, d, g):
        x.close()


def pipes():
    r, w = os.pipe()
    t('pipe', lambda: (w - r, os.get_blocking(r), oct(os.fstat(r).st_mode), os.fstat(w).st_size))
    t('select empty', lambda: select.select([r, w], [r, w], [r, w], 0) == ([], [w], []))
    os.write(w, b'abc')
    t('select data', lambda: select.select([r, w], [r, w], [], 0) == ([r], [w], []))
    t('read', lambda: (os.read(r, 2), os.read(r, 10)))
    t('write read end', lambda: os.write(r, b'x'))
    t('read write end', lambda: os.read(w, 1))
    os.set_blocking(r, False)
    t('read nonblocking', lambda: os.read(r, 1))
    os.close(w)
    t('eof', lambda: (os.read(r, 1), select.select([r], [r], [], 0) == ([r], [], [])))
    os.close(r)
    r, w = os.pipe()
    os.close(r)
    t('no reader', lambda: os.write(w, b'x'))
    t('select no reader', lambda: select.select([w], [w], [], 0) == ([w], [w], []))
    os.close(w)
    r, w = os.pipe()
    os.set_blocking(w, False)
    total = 0
    while True:
        try:
            total += os.write(w, b'y' * 10000)
        except BlockingIOError:
            break
    t('full', lambda: (60000 < total <= 65536, select.select([], [w], [], 0)))
    t('full small', lambda: os.write(w, b'z'))
    os.close(r)
    os.close(w)


def selecting():
    a, b = socket.socketpair()
    t('empty', lambda: select.select([], [], [], 0))
    t('pair', lambda: select.select([a, b], [a, b], [a, b], 0) == ([], [a, b], []))
    a.send(b'x')
    t('data', lambda: select.select([a, b], [], [], 0) == ([b], [], []))
    t('fds', lambda: select.select([a.fileno(), b.fileno()], [], [], 0) == ([b.fileno()], [], []))
    t0 = time.monotonic()
    t('timeout', lambda: (select.select([a], [], [], 0.05), 0.04 < time.monotonic() - t0 < 2))
    t('neg', lambda: select.select([], [], [], -1))
    t('str timeout', lambda: select.select([], [], [], 'x'))
    t('nan', lambda: select.select([], [], [], float('nan')))
    t('none list', lambda: select.select(None, [], []))
    t('str item', lambda: select.select(['x'], [], [], 0))
    t('float item', lambda: select.select([1.5], [], [], 0))
    t('neg fd', lambda: select.select([-1], [], [], 0))
    t('big fd', lambda: select.select([70000], [], [], 0))
    t('tuple, iterator', lambda: (select.select((b,), (), (), 0) == ([b], [], []), select.select(iter([b]), [], [], 0) == ([b], [], [])))
    t('dup', lambda: select.select([b, b, b.fileno()], [], [], 0) == ([b, b, b.fileno()], [], []))

    class F:
        def __init__(self, n):
            self.n = n

        def fileno(self):
            return self.n
    t('fileno object', lambda: select.select([F(b.fileno())], [], [], 0)[0][0].n == b.fileno())
    t('fileno str', lambda: select.select([F('x')], [], [], 0))
    t('fileno neg', lambda: select.select([F(-3)], [], [], 0))
    t('3 args', lambda: select.select([b], [], []) == ([b], [], []))
    t('2 args', lambda: select.select([b], []))
    t('5 args', lambda: select.select([], [], [], 0, 0))
    t('kw', lambda: select.select([b], [], [], timeout=0))
    t('none timeout', lambda: select.select([b], [], [], None) == ([b], [], []))
    t('stdout', lambda: select.select([], [1, 2], [], 0))
    b.recv(1)
    a.close()
    b.close()
    t('closed', lambda: select.select([b], [], [], 0))
    r, w = os.pipe()
    os.close(r)
    t('closed fd', lambda: select.select([r], [], [], 0))
    os.close(w)


def selector():
    a, b = socket.socketpair()
    sel = selectors.DefaultSelector()
    key = sel.register(b, selectors.EVENT_READ, 'data')
    t('key', lambda: (key.fileobj is b, key.events, key.data))
    t('nothing', lambda: sel.select(0))
    a.send(b'!')
    t('ready', lambda: [(k.fileobj is b, ev) for k, ev in sel.select(0)])
    t0 = time.monotonic()
    b.recv(1)
    t('timeout', lambda: (sel.select(0.03), 0.02 < time.monotonic() - t0 < 2))
    sel.modify(b, selectors.EVENT_READ | selectors.EVENT_WRITE)
    t('modify', lambda: [ev for k, ev in sel.select(0)])
    sel.unregister(b)
    t('map', lambda: len(sel.get_map()))
    sel.close()
    a.close()
    b.close()


def handler(n, f):
    got.append((n, type(f).__name__, f.f_code.co_name if f else None))


got = []


def raising():
    signal.raise_signal(signal.SIGUSR1)
    return 'after'


def signals():
    ints = {n: getattr(_signal, n) for n in dir(_signal) if type(getattr(_signal, n)) is int}
    print(len(ints), sorted(ints.items())[:6], signal.NSIG)
    print(signal.SIGINT, repr(signal.SIGTERM), signal.SIG_DFL, signal.SIG_IGN, repr(signal.SIG_DFL), len(signal.Signals))
    print(list(signal.Handlers), list(signal.Sigmasks))
    print([i for i in range(1, signal.NSIG) if signal.getsignal(i) != signal.SIG_DFL])
    print(signal.getsignal(signal.SIGINT) is signal.default_int_handler, signal.getsignal(32), signal.getsignal(signal.SIGPIPE))
    print(len(signal.valid_signals()), sorted(signal.valid_signals())[:3], type(signal.valid_signals()).__name__)
    print([signal.strsignal(i) for i in (1, 2, 9, 15, 17, 32, 34, 64)])
    t('strsignal 0', lambda: signal.strsignal(0))
    t('default_int_handler', lambda: signal.default_int_handler(2, None))
    t('default_int_handler 1', lambda: signal.default_int_handler(1))
    t('set', lambda: signal.signal(signal.SIGUSR1, handler))
    t('get', lambda: signal.getsignal(signal.SIGUSR1) is handler)
    t('again', lambda: signal.signal(signal.SIGUSR1, signal.SIG_IGN) is handler)
    t('ign', lambda: signal.getsignal(signal.SIGUSR1))
    t('dfl', lambda: signal.signal(signal.SIGUSR1, signal.SIG_DFL))
    t('int 1', lambda: (signal.signal(signal.SIGUSR1, 1), signal.getsignal(signal.SIGUSR1)))
    t('int 0', lambda: signal.signal(signal.SIGUSR1, 0))
    t('int 2', lambda: signal.signal(signal.SIGUSR1, 2))
    t('bad handler', lambda: signal.signal(signal.SIGUSR1, 'x'))
    t('kill', lambda: signal.signal(signal.SIGKILL, handler))
    t('stop', lambda: signal.signal(signal.SIGSTOP, signal.SIG_IGN))
    t('0', lambda: signal.signal(0, handler))
    t('65', lambda: signal.signal(65, handler))
    t('32', lambda: signal.signal(32, handler))
    t('34', lambda: (signal.signal(34, handler), signal.signal(34, signal.SIG_DFL) is handler))
    t('str', lambda: signal.signal('x', handler))
    t('kw', lambda: _signal.signal(signalnum=10, handler=handler))
    t('args', lambda: _signal.signal(1))
    t('getsignal 0', lambda: signal.getsignal(0))
    t('getsignal kill', lambda: signal.getsignal(signal.SIGKILL))


def delivering():
    signal.signal(signal.SIGUSR1, handler)
    t('raise', lambda: (raising(), got))
    signal.signal(signal.SIGUSR1, signal.SIG_IGN)
    t('raise ign', lambda: signal.raise_signal(signal.SIGUSR1))
    t('raise int', lambda: signal.raise_signal(signal.SIGINT))
    t('raise 0', lambda: signal.raise_signal(0))
    t('raise 65', lambda: signal.raise_signal(65))
    t('raise -1', lambda: signal.raise_signal(-1))
    t('raise chld', lambda: signal.raise_signal(signal.SIGCHLD))

    def boom(n, f):
        raise ValueError('from handler %d' % n)
    signal.signal(signal.SIGUSR2, boom)
    t('raise boom', lambda: signal.raise_signal(signal.SIGUSR2))
    signal.signal(signal.SIGUSR2, signal.SIG_DFL)
    t('siginterrupt', lambda: (signal.siginterrupt(signal.SIGUSR1, False), signal.siginterrupt(signal.SIGUSR1, True)))
    t('siginterrupt 0', lambda: signal.siginterrupt(0, True))
    t('siginterrupt kill', lambda: signal.siginterrupt(signal.SIGKILL, True))
    t('siginterrupt 1', lambda: signal.siginterrupt(1))


def wakeups():
    t('first', lambda: signal.set_wakeup_fd(-1))
    a, b = socket.socketpair()
    try:
        signal.set_wakeup_fd(a.fileno())
    except ValueError as e:
        print('blocking fd', str(e).replace(str(a.fileno()), 'A'))
    a.setblocking(False)
    b.setblocking(False)
    t('set', lambda: signal.set_wakeup_fd(a.fileno()))
    t('again', lambda: signal.set_wakeup_fd(a.fileno(), warn_on_full_buffer=False) == a.fileno())
    t('closed fd', lambda: signal.set_wakeup_fd(99999))
    t('str', lambda: signal.set_wakeup_fd('x'))
    t('socket', lambda: signal.set_wakeup_fd(a))
    t('badkw', lambda: signal.set_wakeup_fd(-1, foo=1))
    t('2 args', lambda: signal.set_wakeup_fd(-1, True))
    got.clear()
    signal.signal(signal.SIGUSR1, handler)
    signal.raise_signal(signal.SIGUSR1)
    t('byte', lambda: (b.recv(10), got))
    signal.signal(signal.SIGUSR1, signal.SIG_IGN)
    signal.raise_signal(signal.SIGUSR1)
    t('ignored: no byte', lambda: b.recv(10))
    signal.signal(signal.SIGINT, handler)
    signal.raise_signal(signal.SIGINT)
    t('sigint', lambda: (b.recv(10), got[-1]))
    signal.signal(signal.SIGINT, signal.default_int_handler)
    t('reset', lambda: signal.set_wakeup_fd(-1) == a.fileno())
    t('pipe', lambda: (lambda r, w: (os.set_blocking(w, False), signal.set_wakeup_fd(w), signal.set_wakeup_fd(-1) == w, os.close(r), os.close(w)))(*os.pipe()))
    t('stdout', lambda: signal.set_wakeup_fd(1))
    a.close()
    b.close()


def masks():
    t('mask', lambda: signal.pthread_sigmask(signal.SIG_BLOCK, []))
    t('block', lambda: signal.pthread_sigmask(signal.SIG_BLOCK, [signal.SIGUSR1]))
    t('blocked', lambda: signal.pthread_sigmask(signal.SIG_BLOCK, []))
    signal.signal(signal.SIGUSR1, handler)
    got.clear()
    signal.raise_signal(signal.SIGUSR1)
    t('raised blocked', lambda: (got, signal.sigpending()))
    t('unblock', lambda: (signal.pthread_sigmask(signal.SIG_UNBLOCK, [signal.SIGUSR1]), got, signal.sigpending()))
    t('setmask', lambda: signal.pthread_sigmask(signal.SIG_SETMASK, []))
    t('bad how', lambda: signal.pthread_sigmask(5, []))
    t('bad signal', lambda: signal.pthread_sigmask(signal.SIG_BLOCK, [0]))
    t('kill', lambda: (signal.pthread_sigmask(signal.SIG_BLOCK, [signal.SIGKILL]), signal.pthread_sigmask(signal.SIG_SETMASK, [])))
    t('str', lambda: signal.pthread_sigmask(signal.SIG_BLOCK, ['x']))
    t('none', lambda: signal.pthread_sigmask(signal.SIG_BLOCK, None))
    t('alarm 0', lambda: signal.alarm(0))
    t('getitimer', lambda: signal.getitimer(signal.ITIMER_REAL))
    t('getitimer bad', lambda: signal.getitimer(5))
    t('setitimer 0', lambda: signal.setitimer(signal.ITIMER_REAL, 0))
    t('setitimer neg', lambda: signal.setitimer(signal.ITIMER_REAL, -1))
    t('setitimer str', lambda: signal.setitimer(signal.ITIMER_REAL, 'x'))
    t('ItimerError', lambda: (signal.ItimerError.__mro__, signal.ItimerError.__module__))
    signal.signal(signal.SIGUSR1, signal.SIG_DFL)


def addresses():
    t('htons', lambda: (socket.htons(1), socket.ntohs(0x1234), socket.htonl(1), socket.ntohl(0x12345678), socket.htons(True)))
    t('htons big', lambda: socket.htons(65536))
    t('htonl big', lambda: socket.htonl(2 ** 32))
    t('ntohl neg', lambda: socket.ntohl(-1))
    t('htons float', lambda: socket.htons(1.5))
    t('inet_aton', lambda: [socket.inet_aton(x) for x in ('127.0.0.1', '1', '1.2', '1.2.3', '0x7f.1', '010.0.0.1', '255.255.255.255', '1.2.3.4 x')])
    for bad in ('x', '256.1.1.1', ' 1.2.3.4', '1.2.3.4.5', '1..2', '', '0x', '4294967296'):
        t('inet_aton %r' % bad, lambda: socket.inet_aton(bad))
    t('inet_aton bytes', lambda: socket.inet_aton(b'1.2.3.4'))
    t('inet_ntoa', lambda: (socket.inet_ntoa(b'\x7f\x00\x00\x01'), socket.inet_ntoa(bytearray(b'\x01\x02\x03\x04'))))
    t('inet_ntoa bad', lambda: socket.inet_ntoa(b'12'))
    t('inet_pton', lambda: (socket.inet_pton(socket.AF_INET, '1.2.3.4'), socket.inet_pton(socket.AF_INET6, '::1'), socket.inet_pton(socket.AF_INET6, '::1.2.3.4')))
    for bad in ('1.2.3', '01.2.3.4', '1.2.3.256', '1.2.3.4 '):
        t('inet_pton4 %r' % bad, lambda: socket.inet_pton(socket.AF_INET, bad))
    for bad in ('1:::2', '1::2::3', '12345::', ':1', '1:2:3:4:5:6:7:8:9', 'g::'):
        t('inet_pton6 %r' % bad, lambda: socket.inet_pton(socket.AF_INET6, bad))
    t('inet_pton family', lambda: socket.inet_pton(99, '1.2.3.4'))
    t('inet_pton bytes', lambda: socket.inet_pton(socket.AF_INET, b'1.2.3.4'))
    t('inet_ntop', lambda: [socket.inet_ntop(socket.AF_INET6, socket.inet_pton(socket.AF_INET6, x))
                            for x in ('2001:db8::1', '::ffff:1.2.3.4', 'fe80::1:2', '1:0:0:1:0:0:0:1', '::', '1::', '::1.2.3.4', '1:2:3:4:5:6:7:8', '0:0:1::', '1:0::')])
    t('inet_ntop4', lambda: socket.inet_ntop(socket.AF_INET, b'abcd'))
    t('inet_ntop bad', lambda: socket.inet_ntop(socket.AF_INET, b'abc'))
    t('inet_ntop family', lambda: socket.inet_ntop(99, b'1234'))
    t('CMSG', lambda: (socket.CMSG_LEN(4), socket.CMSG_SPACE(4), socket.CMSG_SPACE(0), socket.CMSG_LEN(0), socket.CMSG_SPACE(1)))
    t('CMSG neg', lambda: socket.CMSG_LEN(-1))


def waits():
    t('waitpid', lambda: os.waitpid(12345, 0))
    t('wait statuses', lambda: [(os.WIFEXITED(s), os.WEXITSTATUS(s), os.WIFSIGNALED(s), os.WTERMSIG(s), os.WIFSTOPPED(s), os.WSTOPSIG(s),
                                 os.WCOREDUMP(s), os.WIFCONTINUED(s)) for s in (0, 256, 9, 0x8b, 0x137f, 0xffff)])
    t('exit codes', lambda: [os.waitstatus_to_exitcode(s) for s in (0, 256, 9, 0x8b)])
    t('stopped', lambda: os.waitstatus_to_exitcode(0x137f))
    t('invalid', lambda: os.waitstatus_to_exitcode(0xffff))


for f in (constants, pair_basics, pair_data, misuse, options, dups, shutdowns, closed, kinds, unconnected, defaults, pipes,
          selecting, selector, signals, delivering, wakeups, masks, addresses, waits):
    print('--', f.__name__)
    f()
