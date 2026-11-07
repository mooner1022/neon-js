# __enter__ and __exit__, in order; the target; the exception __exit__ sees
class Manager:
    def __init__(self, name, suppress=False):
        self.name = name
        self.suppress = suppress

    def __enter__(self):
        print('enter', self.name)
        return self.name.upper()

    def __exit__(self, kind, value, tb):
        print('exit', self.name, kind.__name__ if kind else None, repr(value), tb is None if value is None else 'tb')
        return self.suppress

with Manager('a') as value:
    print('body', value)

with Manager('b'):
    print('no target')

try:
    with Manager('c'):
        raise ValueError('inside')
except ValueError as e:
    print('propagated', e)

with Manager('d', suppress=True):
    raise KeyError('swallowed')
print('after the suppressed one')

def first_half():
    # several items: entered in order, left in reverse; parenthesized too
    with Manager('x') as x, Manager('y') as y:
        print('both', x, y)

    with (
        Manager('p') as p,
        Manager('q'),
    ):
        print('parenthesized', p)

    try:
        with Manager('outer'), Manager('inner'):
            raise TypeError('t')
    except TypeError:
        print('caught after both exits')

    # return, break and continue leave through __exit__
    def leave():
        with Manager('ret'):
            return 'returned'

    print(leave())

    for i in range(3):
        with Manager('loop' + str(i)):
            if i == 0:
                continue
            if i == 2:
                break
            print('loop body', i)

first_half()

def second_half():
    # an exception in __enter__: no __exit__; one storing the target: __exit__ sees it
    class BadEnter:
        def __enter__(self):
            raise RuntimeError('enter failed')

        def __exit__(self, *a):
            print('not called')

    try:
        with BadEnter():
            print('not run')
    except RuntimeError as e:
        print('enter raised', e)

    class Pair:
        def __enter__(self):
            return (1, 2)

        def __exit__(self, kind, value, tb):
            print('pair exit', kind.__name__ if kind else None)
            return True

    with Pair() as (first, second):
        print('unpacked', first, second)

    with Pair() as (only,):
        print('not reached')
    print('unpacking error suppressed')

    # __exit__ raising: its exception replaces the body's, with it as context
    class Raising:
        def __enter__(self):
            return self

        def __exit__(self, kind, value, tb):
            raise ValueError('from exit')

    try:
        with Raising():
            raise KeyError('from body')
    except ValueError as e:
        print(repr(e), repr(e.__context__))

    # managers that are not classes' own methods: found on the type, bound
    class Base:
        def __enter__(self):
            return 'base enter'

        def __exit__(self, *a):
            return False

    class Derived(Base):
        pass

    with Derived() as d:
        print(d)

    # a with inside try/finally inside a loop
    log = []
    for n in range(2):
        try:
            with Manager('n' + str(n)) as m:
                log.append(m)
                if n == 1:
                    raise IndexError('i')
        except IndexError:
            log.append('caught')
        finally:
            log.append('finally')
    print(log)

    # nested in functions, the exception seen by __exit__ is the one handled
    class Seen:
        def __enter__(self):
            return self

        def __exit__(self, kind, value, tb):
            self.seen = value
            return True

    s = Seen()
    with s:
        {}['k']
    print(repr(s.seen), type(s.seen).__name__)

second_half()
