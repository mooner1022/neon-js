# Generators and coroutines whose code the tier compiles: suspended and resumed by compiled code, by the
# interpreter, or by one and then the other; exits inside them; values on the stack across a yield.


def counting():
    # one generator, made once: its first run's long loop goes on in compiled code, then yields from there
    total = 0
    for i in range(20000):
        total += i
    yield total
    for i in range(3):
        yield total + i


def first_values():
    g = counting()
    print(next(g), list(g))


def made_before(n):
    for i in range(n):
        yield i * i


def resumed_later():
    # suspended by the interpreter (made before its code is compiled), resumed by compiled code
    early = made_before(5)
    print(next(early), next(early))
    for k in range(4):
        print(sum(made_before(k + 3)), end=" ")
    print(list(early))


def growing():
    # an int leaving 64 bits midway: the speculating code exits inside the generator, which goes on interpreted
    x = 3
    while True:
        x = x * x + 1
        yield x


def overflow():
    g = growing()
    # (made first: interpreted, its operands ints only; made again: its code compiled, speculating on ints)
    small = [next(g) for _ in range(4)]
    g = growing()
    vals = [next(g) for _ in range(8)]
    print(small, [v.bit_length() for v in vals], vals[-1] % 1000003)


def on_the_stack():
    # values on the operand stack across a yield
    def g():
        a = [1, (yield 'a'), 3]
        b = (10, (yield 'b'), 30)
        c = len('xy') + (yield 'c') * 2
        yield a, b, c
    it = g()
    print(next(it), it.send(2), it.send(20), it.send(5))


def handlers():
    def g():
        try:
            yield 1
            yield 2
        except ValueError as e:
            yield 'caught %s' % e
        finally:
            print('finally')
        yield 'after'
    it = g()
    print(next(it), it.throw(ValueError('v')), next(it))
    it = g()
    print(next(it))
    it.close()
    it = g()
    print(next(it), next(it))
    try:
        it.throw(KeyError('k'))
    except KeyError as e:
        print('KeyError', e)


def sub():
    try:
        x = yield 'sub1'
        y = yield 'sub2 %s' % x
    except ValueError as e:
        yield 'sub caught %s' % e
    finally:
        print('sub finally')
    return 'result'


def delegating():
    def outer():
        r = yield from sub()
        yield 'outer got %s' % r
    it = outer()
    print(next(it), it.send('x'), it.send('y'))
    it = outer()
    print(next(it), it.throw(ValueError('into sub')))
    print(next(it), next(it, 'end'))
    it = outer()
    next(it)
    it.close()
    print('closed', it.gi_frame is None)


class Ready:
    def __init__(self, v):
        self.v = v

    def __await__(self):
        got = yield ('waiting', self.v)
        return got


async def coro(n):
    total = 0
    for i in range(n):
        total += await Ready(i)
    return total


def coroutines():
    c = coro(4)
    out = [c.send(None)]
    try:
        while True:
            out.append(c.send(len(out)))
    except StopIteration as e:
        out.append(('result', e.value))
    print(out)


async def agen(n):
    for i in range(n):
        got = await Ready(i)
        yield i, got


def async_generators():
    a = agen(3)
    out = []
    for _ in range(3):
        step = a.asend(None)
        out.append(step.send(None))
        try:
            step.send('sent')
        except StopIteration as e:
            out.append(e.value)
    try:
        a.asend(None).send(None)
    except StopAsyncIteration:
        out.append('done')
    print(out)


def expressions():
    print(sum(i * i for i in range(100)), list(x + 1 for x in (y * 2 for y in range(5))))


for f in (first_values, resumed_later, overflow, on_the_stack, handlers, delegating, coroutines, async_generators, expressions):
    f()
