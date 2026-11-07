# a generator: values on demand, its return value in StopIteration
def count(n):
    i = 0
    while i < n:
        yield i
        i += 1
    return 'counted ' + str(n)

g = count(3)
print(type(g).__name__, g.__name__, iter(g) is g, next(g), next(g), next(g))
try:
    next(g)
except StopIteration as e:
    print('stop', e.value, e.args)
print(next(g, 'exhausted'), list(count(4)), sum(count(5)), [x * x for x in count(4)])
for v in count(2):
    print('for', v)

def fib():
    a, b = 0, 1
    while True:
        yield a
        a, b = b, a + b

out = []
for v in fib():
    if v > 50:
        break
    out.append(v)
print(out)

# send: the value of a yield expression
def accumulate():
    total = 0
    while True:
        got = yield total
        if got is None:
            return total
        total += got

acc = accumulate()
print(next(acc), acc.send(10), acc.send(5), acc.send(1))
try:
    acc.send(None)
except StopIteration as e:
    print('accumulated', e.value)

# throw: raised where it yields; close: GeneratorExit
def careful():
    while True:
        try:
            yield 'ready'
        except ValueError as e:
            print('careful caught', repr(e))
            yield 'recovered'
        finally:
            print('careful finally')

c = careful()
print(next(c), c.throw(ValueError('bad')), next(c))
print(c.close(), c.gi_running)

def ignoring():
    try:
        yield 1
    except GeneratorExit:
        yield 'not closing'

ig = ignoring()
next(ig)
try:
    ig.close()
except RuntimeError as e:
    print(e)

def returning():
    try:
        yield 1
    except GeneratorExit:
        return 'closed with a value'

r = returning()
next(r)
print(r.close(), returning().close())

def thrown_through():
    yield 1
    yield 2

t = thrown_through()
next(t)
try:
    t.throw(KeyError, 'k')
except KeyError as e:
    print('through', repr(e))
print(next(t, 'finished after throw'))

def second_half():
    # yield from: values, sends, throws and closes passed on; its result
    def inner():
        x = yield 'inner 1'
        print('inner got', x)
        try:
            yield 'inner 2'
        except IndexError:
            yield 'inner caught'
        return 'inner result'

    def outer():
        r = yield from inner()
        print('outer got', r)
        yield from [10, 20]
        yield from range(2)

    o = outer()
    print(next(o), o.send('hello'), o.throw(IndexError), next(o), list(o))

    class Counter:
        def __init__(self):
            self.n = 0
        def __iter__(self):
            return self
        def __next__(self):
            self.n += 1
            if self.n > 2:
                raise StopIteration('counter done')
            return self.n

    def from_iterator():
        r = yield from Counter()
        yield r

    print(list(from_iterator()))

    def closing_inner():
        try:
            yield 1
        finally:
            print('inner closed')

    def closing_outer():
        try:
            yield from closing_inner()
        finally:
            print('outer closed')

    co = closing_outer()
    next(co)
    co.close()

    # generators and the exception handled
    def handles():
        try:
            raise KeyError('in generator')
        except KeyError:
            yield 'in except'
            raise

    h = handles()
    next(h)
    try:
        raise ValueError('caller')
    except ValueError:
        try:
            next(h)
        except KeyError as e:
            print('raised again', repr(e), repr(e.__context__))

    def raises_later():
        yield 1
        raise TypeError('later')

    try:
        raise ValueError('while handling')
    except ValueError:
        rl = raises_later()
        next(rl)
        try:
            next(rl)
        except TypeError as e:
            print('context', repr(e.__context__))

    # PEP 479; a generator running is not entered again
    def leaks():
        next(iter([]))
        yield

    try:
        next(leaks())
    except RuntimeError as e:
        print(e, repr(e.__cause__))

    def reentrant():
        yield next(me)

    me = reentrant()
    try:
        next(me)
    except ValueError as e:
        print(e)

    # with and try around a yield
    class Manager:
        def __enter__(self):
            print('enter')
            return self
        def __exit__(self, kind, value, tb):
            print('exit', kind.__name__ if kind else None)

    def managed():
        with Manager():
            yield 1
            yield 2

    m = managed()
    print(next(m))
    m.close()
    print(list(managed()))

    # generator expressions: lazy, their first iterable at once
    def source():
        print('source runs')
        yield 1
        yield 2

    ge = (x * 10 for x in source())
    print('made', type(ge).__name__)
    print(list(ge), list(ge))
    print(sum(x for x in range(5)), max(len(w) for w in ['a', 'abc', 'ab']), list(x for x in 'ab' if x != 'a'))
    lam = lambda: (yield 'from a lambda')
    print(list(lam()))

second_half()
