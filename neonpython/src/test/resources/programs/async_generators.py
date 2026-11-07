# Asynchronous generators (PEP 525) and asynchronous comprehensions (PEP 530): async defs with yields, stepped by
# the awaitables their __anext__, asend, athrow and aclose give; aiter() and anext(). Driven by send() as in
# coroutines.py: what an await passes through is printed.


class Sleep:
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


def step(aw):
    """Steps the awaitable aw to its end: what its awaits pass through printed; its value."""
    try:
        while True:
            print('  passed', aw.send(None))
    except StopIteration as e:
        return e.value


def error(f, *args):
    try:
        f(*args)
        print('no error')
    except Exception as e:
        print(type(e).__name__ + ':', e)


class Ticker:
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


def basics():
    async def ag():
        yield 1
        await Sleep('mid')
        yield 2
    g = ag()
    print(type(g).__name__, g.__name__, g.__qualname__, repr(g).split(' at ')[0])
    print(hasattr(g, '__aiter__'), hasattr(g, '__anext__'), hasattr(g, 'asend'), hasattr(g, 'athrow'), hasattr(g, 'aclose'))
    print(hasattr(g, '__next__'), hasattr(g, '__iter__'), hasattr(g, 'send'), hasattr(g, 'close'), hasattr(g, '__await__'))
    print(g.ag_running, g.ag_await, g.ag_suspended, g.__aiter__() is g)
    a = g.__anext__()
    print(type(a).__name__, repr(a).split(' at ')[0], iter(a) is a, a.__await__() is a)
    print(step(a))
    b = g.__anext__()
    print(b.send(None))
    print(g.ag_running, type(g.ag_await).__name__, g.ag_suspended)
    print(step(b), g.ag_running)
    try:
        step(g.__anext__())
    except StopAsyncIteration:
        print('StopAsyncIteration')
    error(step, g.__anext__())

    async def consume():
        out = []
        async for v in ag():
            out.append(v)
        return out
    print(run(consume()))

    # the StopIteration an awaitable ends with: its value always its argument (None too)
    async def values():
        yield (1, 2)
        yield None
    g = values()
    for i in range(2):
        try:
            g.__anext__().send(None)
        except StopIteration as e:
            print('value', e.value, e.args)

    # an async generator's frame: arguments, closures, a return without a value
    def make(base):
        async def counter(n, step=1):
            i = 0
            while i < n:
                if i == 3:
                    return
                yield base + i
                i += step
        return counter

    async def counted():
        return [v async for v in make(100)(10, step=1)], [v async for v in make(0)(5, 2)]
    print(run(counted()))


basics()


def asend_values():
    async def echo():
        received = []
        x = yield 'first'
        while x is not None:
            received.append(x)
            x = yield x * 2
        yield received
    g = echo()
    print(step(g.asend(None)))
    print(step(g.asend(5)))
    print(step(g.asend(7)))
    print(step(g.asend(None)))
    error(g.asend)
    error(step, echo().asend(1))


asend_values()


def awaitable_states():
    async def ag():
        yield 1
        yield 2
    g = ag()
    a = g.__anext__()
    print(step(a))
    error(a.send, None)
    print(a.close())

    # one of its awaitables in flight: another is the error
    async def slow():
        await Sleep('slow')
        yield 'v'
    s = slow()
    b = s.__anext__()
    print(b.send(None), s.ag_running)
    error(s.__anext__().send, None)
    error(s.aclose().send, None)
    error(s.athrow(ValueError).send, None)
    print(step(b), s.ag_running)


awaitable_states()


def closing():
    async def with_finally():
        try:
            yield 1
            yield 2
        finally:
            print('finally ran')
    g = with_finally()
    print(step(g.__anext__()))
    print(step(g.aclose()))
    error(step, g.__anext__())
    print(step(g.aclose()))

    # unstarted: closed without running
    g = with_finally()
    print(step(g.aclose()))
    error(step, g.__anext__())

    # an await in the finally body: aclose awaits it through
    async def awaits_in_finally():
        try:
            yield 1
        finally:
            await Sleep('closing')
            print('closed after await')
    g = awaits_in_finally()
    step(g.__anext__())
    print(step(g.aclose()))

    # yielding while it closes is the error
    async def stubborn():
        try:
            yield 1
        except GeneratorExit:
            yield 2
    g = stubborn()
    step(g.__anext__())
    error(step, g.aclose())

    # a loop left early leaves it suspended: aclose runs its finally body
    async def breaker():
        g = with_finally()
        async for v in g:
            print('got', v)
            break
        await g.aclose()
        return 'broke'
    print(run(breaker()))


closing()


def closing_while_awaiting():
    class Noisy:
        def __await__(self):
            try:
                yield 'noisy waiting'
            except GeneratorExit:
                print('noisy got GeneratorExit')
                raise
            return 'noisy done'

    async def ag():
        try:
            await Noisy()
            yield 1
        finally:
            print('ag finally')

    # its awaitable closed mid-await: the await's iterator closed, then the generator
    g = ag()
    a = g.__anext__()
    print(a.send(None))
    error(g.aclose().send, None)
    print(a.close(), g.ag_running)
    error(step, g.__anext__())

    # aclose of one suspended at a yield after an await, and of one whose finally awaits Noisy
    g = ag()
    print(step(g.__anext__()))
    print(step(g.aclose()))

    async def awaits_noisy_in_finally():
        try:
            yield 1
        finally:
            r = await Noisy()
            print('finally awaited', r)
    g = awaits_noisy_in_finally()
    step(g.__anext__())
    c = g.aclose()
    print(c.send(None))
    print(step(c))

    # the exception thrown into an awaitable mid-await goes into what it awaits
    g = ag()
    a = g.__anext__()
    a.send(None)
    error(a.throw, KeyError('into noisy'))
    print(g.ag_running, g.ag_suspended)


closing_while_awaiting()


def throwing():
    async def catcher():
        try:
            yield 1
        except ValueError as e:
            yield 'caught ' + str(e)
        yield 'after'
    g = catcher()
    print(step(g.__anext__()))
    print(step(g.athrow(ValueError('v'))))
    print(step(g.__anext__()))
    error(step, g.athrow(KeyError('k')))
    print(step(g.athrow(ValueError)))
    error(step, g.__anext__())

    # thrown in before its start: raised there
    g = catcher()
    error(step, g.athrow(ValueError('x')))
    error(step, g.__anext__())

    # an athrow is awaited once; sending it a value first is the error
    g = catcher()
    step(g.__anext__())
    t = g.athrow(TypeError)
    error(step, t)
    error(t.send, None)
    g = catcher()
    error(g.athrow(ValueError).send, 5)

    # an exception thrown into an await inside it reaches the awaitable's frame
    async def awaiting():
        try:
            await Sleep('waiting')
        except KeyError:
            yield 'caught at the await'
    g = awaiting()
    a = g.__anext__()
    print(a.send(None))
    try:
        a.throw(KeyError('k'))
    except StopIteration as e:
        print(e.value)


throwing()


def pep479():
    async def raises_stop():
        raise StopIteration
        yield

    async def raises_stop_async():
        raise StopAsyncIteration
        yield
    for f in (raises_stop, raises_stop_async):
        try:
            step(f().__anext__())
        except RuntimeError as e:
            print('RuntimeError:', e, type(e.__cause__).__name__, e.__suppress_context__)


pep479()


def comprehensions():
    async def ag(n):
        for i in range(n):
            await Sleep('ag %d' % i)
            yield i

    async def f():
        a = [x async for x in ag(3)]
        b = {x % 2 async for x in ag(3)}
        c = {x: x * x async for x in ag(3) if x}
        d = [(x, y) async for x in ag(2) for y in range(3) if y]
        e = [y for x in range(2) async for y in ag(x + 1)]
        return a, sorted(b), c, d, e
    print(run(f()))

    async def genexp():
        g = (x * 10 async for x in ag(2))
        print(type(g).__name__)
        return [v async for v in g]
    print(run(genexp()))

    # an await in a generator expression makes it an async generator
    async def awaits_in_genexp():
        g = (await Sleep(x) for x in range(2))
        print(type(g).__name__)
        return [v async for v in g]
    print(run(awaits_in_genexp(), ['s0', 's1']))

    # a function that is not async may make an async generator expression
    def maker(it):
        return (x async for x in it)

    async def use():
        return [v async for v in maker(ag(2))]
    print(run(use()))

    # the first iterable's __aiter__ is called where the comprehension is
    def bad():
        return (x async for x in 1)
    error(bad)

    async def bad2():
        return [x async for x in [1]]
    error(run, bad2())

    async def nested():
        return [[y async for y in ag(x)] for x in range(3)]
    print(run(nested()))

    async def with_ticker():
        return [t async for t in Ticker(2)]
    print(run(with_ticker()))


comprehensions()


def builtins():
    async def ag():
        yield 1
        yield 2

    async def f():
        g = ag()
        print(aiter(g) is g)
        print(await anext(g))
        a = anext(g, 'default')
        print(type(a).__name__)
        print(await a)
        print(await anext(g, 'default'))
        try:
            await anext(g)
        except StopAsyncIteration:
            print('StopAsyncIteration')
        print(await anext(Ticker(1)), await anext(Ticker(0), 'empty'))
        t = Ticker(1)
        print(aiter(t) is t)
    run(f())
    error(aiter, 1)
    error(anext, 1)

    class BadAiter:
        def __aiter__(self):
            return 1
    error(aiter, BadAiter())
    error(anext)


builtins()
