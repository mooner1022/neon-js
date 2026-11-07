# Annotations evaluated when asked for (PEP 649, PEP 749): __annotate__ functions of functions, classes and modules,
# __annotations__ computed from them and kept.
import sys


def error(f, *args):
    try:
        f(*args)
        print('no error')
    except Exception as e:
        print(type(e).__name__ + ':', e)


def annotated_names(d):
    return sorted(k for k in d if 'annot' in k)


def functions():
    def f(a: int, /, b: str, *c: float, d: 'fwd' = 1, **e: bool) -> list:
        pass
    print(f.__annotations__)
    an = f.__annotate__
    print(an.__name__, an.__qualname__, an(1) == f.__annotations__, an(2) == an(1))
    for fmt in (3, 4):
        try:
            an(fmt)
        except NotImplementedError as e:
            print('NotImplementedError', repr(e))
    error(an)
    error(an, 'x')
    print(f.__annotations__ is f.__annotations__)

    # none: no __annotate__, an empty dict
    def plain(x):
        pass
    print(plain.__annotate__, plain.__annotations__, (lambda q: q).__annotations__)

    # evaluated when asked for, once: names defined later, side effects after the def
    def later(x: Later) -> Later:
        pass
    error(lambda: later.__annotations__)
    global Later
    Later = 'now defined'
    print(later.__annotations__)

    def log(v):
        print('  eval', v)
        return v

    def dec(fn):
        print('  decorated')
        return fn

    @dec
    def g(a: log('a') = log('default'), *, k: log('k') = log('kd')) -> log('ret'):
        pass
    print('defined')
    print(g.__annotations__)
    print(g.__annotations__)

    # setting and deleting
    def h(x: int):
        pass
    h.__annotations__ = {'z': 1}
    print(h.__annotations__, h.__annotate__)
    del h.__annotations__
    print(h.__annotations__)
    h.__annotate__ = lambda fmt: {'from': fmt}
    print(h.__annotations__)
    h.__annotate__ = None
    print(h.__annotations__, h.__annotate__)
    error(setattr, h, '__annotate__', 1)
    error(setattr, h, '__annotations__', 1)
    error(delattr, h, '__annotate__')
    h.__annotate__ = lambda fmt: [fmt]
    error(lambda: h.__annotations__)

    # the scope annotations are evaluated in: the def's, not the function's parameters
    x = 'enclosing x'

    def params(x, y: x):
        pass
    print(params.__annotations__)

    def outer():
        V = float

        def inner(a: V, b: lambda: V):
            pass
        return inner
    inner = outer()
    print(inner.__annotations__['a'], inner.__annotations__['b'](), inner.__annotate__.__qualname__)

    # a name `format` is the builtin's
    def fmt(v: format):
        pass
    print(fmt.__annotations__['v'] is format)

    # *args: *X, the value X unpacks to
    def star(*args: *[1]):
        pass
    print(star.__annotations__)

    # an async def's, a method's
    async def coro(p: int) -> str:
        pass
    print(coro.__annotations__)

    # local annotations are never evaluated
    def local():
        x: undefined_name = 1
        y: also_undefined
        return x
    print(local())


functions()


class Plain:
    pass


class Base:
    x: int


class Derived(Base):
    y: str = 'a'

    def m(self, p: 'P') -> None:
        pass


def classes():
    print(Base.__annotations__, Derived.__annotations__, Plain.__annotations__)
    print(Base.__annotate__(1), Base.__annotate__.__qualname__, Plain.__annotate__)
    print(annotated_names(Base.__dict__), annotated_names(Plain.__dict__))
    print('x' in Base.__dict__, Derived.y, Derived.m.__annotations__)
    print(hasattr(Base(), '__annotations__'), hasattr(Base(), '__annotate__'))
    error(lambda: int.__annotations__)
    error(lambda: int.__annotate__)

    class Sub(Base):
        pass
    print(Sub.__annotations__)

    # the class's names, then the globals'; a name bound later in the body is the body's last value
    T = 'local T'

    class K:
        T = int
        a: T
        b: 'fwd'

        def m(self, p: T) -> T:
            pass
        T = str
        c: T
    print(K.__annotations__, K.m.__annotations__)

    # a function's names seen from a class in it
    def make():
        U = float

        class C:
            a: U
            V = bytes
            b: V
        return C
    print(make().__annotations__)

    # evaluated once, when asked for; after the class's dict changed
    def log(v):
        print('  eval', v)
        return v

    class E:
        a: log(1)
        b: Later
        Later = 'from the class'
    E.Later = 'changed after'
    print('made')
    print(E.__annotations__)
    print(E.__annotations__)

    # conditional annotations: those whose statement ran
    class Cond:
        if True:
            a: int
        else:
            b: str
        for i in range(1):
            c: float
        while False:
            d: bytes
        try:
            e: list
            raise ValueError
        except ValueError:
            f: dict
        else:
            g: set
        match 1:
            case 2:
                h: tuple
            case 1:
                k: complex
        z: bool
    print(Cond.__annotations__)

    # setting and deleting
    class S:
        x: int
    print(S.__annotate__(1))
    S.__annotations__ = {'a': 1}
    print(S.__annotations__, S.__annotate__)
    del S.__annotations__
    error(delattr, S, '__annotations__')
    print(S.__annotations__)

    class S2:
        x: int
    error(delattr, S2, '__annotations__')
    S2.__annotate__ = lambda fmt: {'n': fmt}
    print(S2.__annotations__)
    error(setattr, S2, '__annotate__', 2)
    error(delattr, S2, '__annotate__')

    # an __annotations__ the body assigns comes first; __annotations__ is no name in the body
    class Assigns:
        x: int
        __annotations__ = {'z': 2}
    print(Assigns.__annotations__, Assigns.__annotate__(1))

    class NoName:
        x: int
        try:
            __annotations__
        except NameError as e:
            print('NameError:', e)

    # comprehensions and lambdas in a class's annotations do not see the class
    class Comp:
        T = 3
        a: [T for i in range(2)]
        b: (lambda: T)()
    print(Comp.__annotations__)


T = 'global T'
classes()


def modules():
    m = sys.modules[__name__]
    print(annotated_names(globals()))
    print(m.__annotations__)
    print(annotated_names(globals()))
    print(m.__annotate__ is __annotate__, __annotate__.__qualname__)
    try:
        __annotations__
    except NameError as e:
        print('NameError:', e)


mod_a: int = 1
mod_b: 'string annotation'
if False:
    mod_c: str
for _i in range(1):
    mod_d: float
modules()
mod_e: bool
print(sys.modules[__name__].__annotations__)
