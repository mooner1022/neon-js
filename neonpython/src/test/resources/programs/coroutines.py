# Coroutines (PEP 492): async def, await, async with, async for, driven by send() and throw() as an event loop
# would drive them (there is no asyncio): what they yield is printed, what the driver sends back the await's value.
import sys


class Sleep:
    """An awaitable whose __await__ yields to the driver: what the driver sends back is the await's value."""
    def __init__(self, v):
        self.v = v

    def __await__(self):
        got = yield self.v
        return got


def run(coro, sends=()):
    """Steps coro to its end: each value it yields printed, the next of sends sent back; its result."""
    sends = list(sends)
    v = None
    try:
        while True:
            out = coro.send(v)
            print('  yielded', out)
            v = sends.pop(0) if sends else None
    except StopIteration as e:
        return e.value


def error(f, *args):
    try:
        f(*args)
        print('no error')
    except Exception as e:
        print(type(e).__name__ + ':', e)


def basics():
    async def add(a, b):
        return a + b
    c = add(1, 2)
    print(type(c).__name__, c.__name__, c.__qualname__, repr(c).split(' at ')[0])
    print(hasattr(c, '__next__'), hasattr(c, '__iter__'), hasattr(c, '__await__'), hasattr(c, 'send'), hasattr(c, 'throw'))
    print(c.cr_running, c.cr_suspended, c.cr_await, c.cr_frame is None or True)
    print(run(c))
    error(c.send, None)
    print(c.close())

    async def g(x):
        a = await Sleep(x)
        return a

    async def f():
        r = await g(1)
        s = await Sleep(2)
        return r, s
    print(run(f(), ['one', 'two']))

    # an __await__ that is a plain iterator, one that is a generator function
    class Lst:
        def __await__(self):
            return iter([10, 20])

    async def h():
        return await Lst()
    print(run(h()))

    class Gen:
        def __await__(self):
            yield 'from gen'
            return 'gen result'

    async def k():
        x = await Gen()
        y = await add(x, '!')
        return y
    print(run(k()))

    # a coroutine's frame: arguments, locals, closures, defaults, keyword arguments
    def outer(n):
        async def inner(a, b=10, *rest, scale=1, **kw):
            total = (a + b + sum(rest)) * scale + n
            await Sleep(total)
            return total, sorted(kw)
        return inner
    print(run(outer(100)(1, 2, 3, scale=2, z=1, y=2)))

    class K:
        async def m(self, v):
            return self, v
    kk = K()
    co = kk.m(5)
    print(co.__qualname__, run(co)[0] is kk)


basics()


def await_errors():
    async def await_it(v):
        await v

    def go(v):
        run(await_it(v))
    error(go, 1)
    error(go, None)

    def gen():
        yield 1
    error(go, gen())

    class BadIter:
        def __await__(self):
            return 1
    error(go, BadIter())

    async def coro():
        return 1

    class ReturnsCoro:
        def __await__(self):
            return coro()
    error(go, ReturnsCoro())

    class AwaitNone:
        __await__ = None
    error(go, AwaitNone())

    # an awaited coroutine is done with; one another await is stepping cannot be awaited
    done = coro()
    run(done)
    error(go, done)

    async def sleeper():
        await Sleep('sleeping')
    busy = sleeper()
    busy.send(None)
    error(go, busy)
    print(busy.cr_suspended, type(busy.cr_await).__name__)
    busy.close()
    print(busy.cr_suspended, busy.cr_await)

    # coro.__await__(): an iterator over the coroutine, itself not awaitable
    c = coro()
    w = c.__await__()
    print(type(w).__name__, iter(w) is w, hasattr(w, '__await__'), hasattr(w, 'send'))
    try:
        next(w)
    except StopIteration as e:
        print('wrapper done', e.value, e.args)
    error(next, w)
    error(go, coro().__await__())

    # yield from a coroutine, iterating one
    def yf():
        yield from coro()
    co = coro()
    error(list, yf())
    error(iter, co)
    error(next, co)
    error(lambda: [x for x in co])
    co.close()


await_errors()


def stepping():
    async def nothing():
        pass

    # send's and throw's checks
    c = nothing()
    error(c.send, 1)
    error(c.send)
    print(run(c))

    async def selfish():
        me.send(None)
    me = selfish()
    error(me.send, None)

    async def stops():
        raise StopIteration(7)
    try:
        stops().send(None)
    except RuntimeError as e:
        print('RuntimeError:', e, repr(e.__cause__), e.__suppress_context__)

    # throw into an await: the awaitable's frame raises it
    async def catcher():
        try:
            await Sleep(1)
        except ValueError as e:
            return 'caught ' + str(e)
    c = catcher()
    c.send(None)
    try:
        c.throw(ValueError('boom'))
    except StopIteration as e:
        print(e.value)

    # throw before its start: raised there, the coroutine done with
    async def never():
        print('never')
    c = never()
    error(c.throw, KeyError('k'))
    error(c.send, None)
    print(c.close())

    # close: GeneratorExit at the await, finally bodies run; one that awaits on is the error; a return the value
    async def cleanup():
        try:
            await Sleep(1)
        finally:
            print('cleanup ran')
    c = cleanup()
    c.send(None)
    print(c.close(), c.cr_suspended)

    async def stubborn():
        try:
            await Sleep(1)
        except GeneratorExit:
            await Sleep(2)
    c = stubborn()
    c.send(None)
    error(c.close)

    async def returns():
        try:
            await Sleep(1)
        except GeneratorExit:
            return 7
    c = returns()
    c.send(None)
    print(c.close())

    # close of a coroutine awaiting another: the inner one closed first
    async def inner():
        try:
            await Sleep('inner')
        finally:
            print('inner closed')

    async def outer():
        try:
            await inner()
        finally:
            print('outer closed')
    c = outer()
    c.send(None)
    c.close()

    # an exception the awaitable raises comes out of the await
    class Raises:
        def __await__(self):
            raise KeyError('from __await__')
            yield

    async def awaits_raise():
        try:
            await Raises()
        except KeyError as e:
            return 'got ' + repr(e)
    print(run(awaits_raise()))

    # an await in a finally body, in an except clause; the exception handled kept across the await
    async def in_finally():
        try:
            return 1
        finally:
            await Sleep('fin')
    print(run(in_finally()))

    async def handled():
        try:
            raise ValueError('v')
        except ValueError:
            await Sleep(1)
            print('handled:', sys.exc_info()[0].__name__)
            raise
    c = handled()
    c.send(None)
    print('outside:', sys.exc_info()[0])
    try:
        c.send(None)
    except ValueError as e:
        print('raised again:', repr(e))


stepping()


class Manager:
    def __init__(self, name, suppress=False):
        self.name = name
        self.suppress = suppress

    async def __aenter__(self):
        print('enter', self.name)
        await Sleep('entering ' + self.name)
        return self.name.upper()

    async def __aexit__(self, t, v, tb):
        print('exit', self.name, t.__name__ if t else None, v)
        await Sleep('exiting ' + self.name)
        return self.suppress


def async_with():
    async def one():
        async with Manager('a') as v:
            print('body', v)
        return 'done'
    print(run(one()))

    async def two():
        async with Manager('a') as x, Manager('b') as y:
            print('body', x, y)
            return x + y
    print(run(two()))

    async def suppressed():
        async with Manager('s', True):
            1 / 0
        print('after')
    run(suppressed())

    async def raised():
        async with Manager('r'):
            raise KeyError('k')
    try:
        run(raised())
    except KeyError as e:
        print('KeyError', e)

    async def loop():
        for i in range(3):
            async with Manager(str(i)):
                if i == 0:
                    continue
                if i == 1:
                    break
        return i
    print(run(loop()))

    # nested in a try, its exit's exception the one raised
    class BadExit:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *a):
            raise ValueError('from exit')

    async def bad_exit():
        try:
            async with BadExit():
                raise KeyError('body')
        except ValueError as e:
            return repr(e), repr(e.__context__)
    print(run(bad_exit()))


async_with()


def async_with_errors():
    def go(m):
        async def f():
            async with m:
                pass
        run(f())
    error(go, 1)

    class OnlyEnter:
        async def __aenter__(self):
            pass
    error(go, OnlyEnter())

    class Sync:
        def __enter__(self):
            pass

        def __exit__(self, *a):
            pass
    error(go, Sync())

    class Async:
        async def __aenter__(self):
            pass

        async def __aexit__(self, *a):
            pass

    def sync_with(m):
        with m:
            pass
    error(sync_with, Async())

    class PlainEnter:
        def __aenter__(self):
            return 1

        async def __aexit__(self, *a):
            pass
    error(go, PlainEnter())

    class PlainExit:
        async def __aenter__(self):
            return 1

        def __aexit__(self, *a):
            return 1
    error(go, PlainExit())


async_with_errors()


class Ticker:
    """An asynchronous iterator: __anext__ awaits, then gives the next number, StopAsyncIteration after n."""
    def __init__(self, n):
        self.i = 0
        self.n = n

    def __aiter__(self):
        return self

    async def __anext__(self):
        self.i += 1
        if self.i > self.n:
            raise StopAsyncIteration
        await Sleep('tick %d' % self.i)
        return self.i


def async_for():
    async def total():
        t = 0
        async for x in Ticker(3):
            t += x
        else:
            print('else ran')
        return t
    print(run(total()))

    async def breaks():
        async for x in Ticker(5):
            if x == 1:
                continue
            if x == 3:
                break
            print('x', x)
        else:
            print('no else')
        return x
    print(run(breaks()))

    async def pairs():
        out = []
        async for a, b in PairTicker():
            out.append(a + b)
        return out

    class PairTicker:
        def __init__(self):
            self.k = 0

        def __aiter__(self):
            return self

        async def __anext__(self):
            self.k += 1
            if self.k > 2:
                raise StopAsyncIteration
            return self.k, self.k * 10
    print(run(pairs()))

    # a StopAsyncIteration the body raises is no end of the loop; a target's error is raised
    async def body_raises():
        async for x in Ticker(2):
            raise StopAsyncIteration('from body')
    try:
        run(body_raises())
    except StopAsyncIteration as e:
        print('StopAsyncIteration', e)

    async def bad_target():
        async for x, y in Ticker(1):
            pass
    error(run, bad_target())

    # nested, and in a try
    async def nested():
        out = []
        async for a in Ticker(2):
            try:
                async for b in Ticker(2):
                    out.append((a, b))
                    if b == 2:
                        raise KeyError(a)
            except KeyError as e:
                out.append(('caught', e.args[0]))
        return out
    print(run(nested()))


async_for()


def async_for_errors():
    def go(it):
        async def f():
            async for x in it:
                pass
        run(f())
    error(go, [1])

    class NoANext:
        def __aiter__(self):
            return 1
    error(go, NoANext())

    class PlainANext:
        def __aiter__(self):
            return self

        def __anext__(self):
            return 1
    try:
        go(PlainANext())
    except TypeError as e:
        print('TypeError:', e, '/', repr(e.__cause__), e.__suppress_context__)


async_for_errors()


def comprehensions():
    async def f():
        squares = [await Sleep(i * i) for i in range(3)]
        evens = {await Sleep(i) for i in range(4) if i % 2 == 0}
        table = {i: await Sleep(-i) for i in range(2)}
        return squares, sorted(evens), table
    print(run(f(), [0, 1, 4, 0, 2, 0, -1]))

    async def g():
        return [x * y async for x in Ticker(2) for y in range(2)]
    print(run(g()))


comprehensions()
