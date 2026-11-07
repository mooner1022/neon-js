# asyncio, CPython's on the sandbox's socket, select and signal: tasks, gather, wait_for, timeout, TaskGroup,
# cancellation, Queue, Lock, Event, Semaphore, call_later's order, futures, sock_*, streams over a socket pair,
# types.coroutine, async generators, asyncio.run again; Linux CPython's output (its selector loop). The executor
# (one thread) and the network (refused) are PyAsyncioTest's.
import asyncio, inspect, types, socket, time


def t(label, f):
    try:
        print(label, "->", f())
    except BaseException as e:
        print(label, "!!", type(e).__name__, e)


out = []


async def worker(name, delay):
    await asyncio.sleep(delay)
    out.append(name)
    return name.upper()


async def boom():
    await asyncio.sleep(0)
    raise ValueError('boom')


async def basics():
    r = await asyncio.gather(worker('b', 0.06), worker('a', 0.03), worker('c', 0))
    t("gather", lambda: (r, out))
    t("sleep0", lambda: None)
    await asyncio.sleep(0)
    try:
        await asyncio.wait_for(asyncio.sleep(1), 0.01)
    except TimeoutError as e:
        t("wait_for", lambda: type(e).__name__)
    try:
        async with asyncio.timeout(0.01):
            await asyncio.sleep(1)
    except TimeoutError:
        t("timeout", lambda: 'timed out')
    await groups()
    await cancels()


async def groups():
    async with asyncio.TaskGroup() as tg:
        t1 = tg.create_task(worker('x', 0.01))
        t2 = tg.create_task(worker('y', 0))
    t("taskgroup", lambda: (t1.result(), t2.result()))
    try:
        async with asyncio.TaskGroup() as tg:
            tg.create_task(boom())
            tg.create_task(asyncio.sleep(1))
    except* ValueError as eg:
        t("taskgroup error", lambda: [str(e) for e in eg.exceptions])


async def cancels():
    task = asyncio.create_task(asyncio.sleep(10))
    await asyncio.sleep(0)
    task.cancel()
    try:
        await task
    except asyncio.CancelledError:
        t("cancel", lambda: (task.cancelled(), task.done()))
    r = await asyncio.gather(boom(), worker('z', 0), return_exceptions=True)
    t("gather exceptions", lambda: [type(x).__name__ if isinstance(x, BaseException) else x for x in r])


async def sync():
    q = asyncio.Queue(maxsize=2)
    got = []

    async def producer():
        for i in range(5):
            await q.put(i)
        await q.put(None)

    async def consumer():
        while (x := await q.get()) is not None:
            got.append(x)
            q.task_done()
        q.task_done()
    await asyncio.gather(producer(), consumer())
    t("queue", lambda: got)
    lock = asyncio.Lock()
    order = []

    async def locked(n):
        async with lock:
            order.append(('in', n))
            await asyncio.sleep(0)
            order.append(('out', n))
    await asyncio.gather(locked(1), locked(2))
    t("lock", lambda: order)
    await events()


async def events():
    ev = asyncio.Event()

    async def waiter():
        await ev.wait()
        return 'woke'
    w = asyncio.create_task(waiter())
    await asyncio.sleep(0)
    ev.set()
    t("event", lambda: None)
    t("event result", lambda: None)
    print("event awaited", await w)
    sem = asyncio.Semaphore(2)
    active = []
    peak = []

    async def limited(n):
        async with sem:
            active.append(n)
            peak.append(len(active))
            await asyncio.sleep(0.005)
            active.remove(n)
    await asyncio.gather(*(limited(i) for i in range(5)))
    t("semaphore", lambda: max(peak))


async def loop_things():
    loop = asyncio.get_running_loop()
    calls = []
    loop.call_later(0.1, calls.append, 'late')
    loop.call_soon(calls.append, 'soon')
    loop.call_later(0.05, calls.append, 'early')
    loop.call_at(loop.time() + 0.075, calls.append, 'at')
    await asyncio.sleep(0.15)
    t("call order", lambda: calls)
    fut = loop.create_future()
    loop.call_soon(fut.set_result, 42)
    t("future", lambda: None)
    print("future awaited", await fut)
    await sockets(loop)


async def sockets(loop):
    a, b = socket.socketpair()
    a.setblocking(False)
    b.setblocking(False)
    await loop.sock_sendall(a, b'ping')
    data = await loop.sock_recv(b, 10)
    t("sock", lambda: data)
    reader, writer = await asyncio.open_connection(sock=a)
    b.send(b'line1\nline2\n')
    l1 = await reader.readline()
    writer.write(b'reply')
    await writer.drain()
    await asyncio.sleep(0.01)
    t("streams", lambda: (l1, b.recv(10)))
    writer.close()
    b.close()
    t("clock", lambda: (loop.time() <= time.monotonic(), loop.get_debug(), type(loop).__name__))


@types.coroutine
def legacy():
    yield
    return 'legacy'


async def gens():
    async def agen():
        for i in range(3):
            await asyncio.sleep(0)
            yield i
    t("legacy", lambda: None)
    print("legacy awaited", await legacy())
    print("agen", [x async for x in agen()])
    c = basics()
    print("iscoroutine", asyncio.iscoroutine(c), inspect.iscoroutinefunction(basics), asyncio.iscoroutine(legacy()))
    c.close()


def codes():
    def g():
        yield 1

    x = g()
    t("gen state", lambda: (inspect.getgeneratorstate(x), x.gi_frame is not None, x.gi_code.co_name, x.gi_code is g.__code__))
    next(x)
    t("gen suspended", lambda: (inspect.getgeneratorstate(x), x.gi_frame.f_code is x.gi_code))
    x.close()
    t("gen closed", lambda: (inspect.getgeneratorstate(x), x.gi_frame))

    async def c():
        pass

    y = c()
    t("coro", lambda: (y.cr_code.co_name, y.cr_frame is not None, inspect.getcoroutinestate(y)))
    y.close()
    t("coro closed", lambda: (y.cr_frame, inspect.getcoroutinestate(y)))
    t("legacy flags", lambda: (legacy.__code__.co_flags & 0x100 != 0, g.__code__.co_flags & 0x100, inspect.isawaitable(legacy()), inspect.isawaitable(g())))

    def h():
        yield 2
    co = h.__code__
    h.__code__ = co.replace(co_name='renamed')
    t("code set", lambda: (h.__code__.co_name, h.__name__, list(h())))
    t("code set error", lambda: setattr(h, '__code__', 1))
    t("code del", lambda: delattr(h, '__code__'))


def main():
    codes()
    asyncio.run(basics())
    asyncio.run(sync())
    asyncio.run(loop_things())
    asyncio.run(gens())
    t("run twice", lambda: asyncio.run(asyncio.sleep(0, 'again')))
    t("run nested", lambda: asyncio.run(nested()))
    t("no loop", lambda: asyncio.get_running_loop())


async def nested():
    c = asyncio.sleep(0)
    try:
        asyncio.run(c)
    finally:
        c.close()


main()
