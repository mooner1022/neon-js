# What is left of the syntax: private names mangled in classes (`__x` as `_C__x`), a class's __static_attributes__
# and __firstlineno__, __debug__.


def error(f, *args):
    try:
        f(*args)
        print('no error')
    except Exception as e:
        print(type(e).__name__ + ':', e)


class C:
    __a = 1
    __b__ = 2
    _c = 3

    def __m(self, __p, *, __k=0):
        return __p, __k

    def run(self):
        self.__x = 10
        __local = 5
        return self.__m(1, _C__k=2), self.__x, __local, [__i for __i in range(2)], self.__a

    def kw(self):
        def f(**kw):
            return sorted(kw)
        return f(__y=1)

    def glob(self):
        global __g
        __g = 'glob'
        return __g

    class __Inner:
        pass

    import sys as __sys
    from sys import version as __version

    def lam(self):
        return (lambda __q: __q)(4)

    def delete(self):
        self.__d = 1
        del self.__d
        return hasattr(self, '_C__d')

    def handler(self):
        try:
            raise ValueError('v')
        except ValueError as __e:
            return str(__e)

    __n: int = 7


def mangling():
    print(sorted(k for k in C.__dict__ if not k.startswith('__') or k.endswith('__') and 'b' in k))
    print(C().run(), C().kw(), C().lam(), C().delete(), C().handler())
    print(C().glob(), '_C__g' in globals(), '__g' in globals())
    print(C._C__Inner.__qualname__, C._C__m.__name__, C._C__m.__qualname__, C._C__sys.__name__, type(C._C__version).__name__)
    print(getattr(C(), '__a', 'not found'), getattr(C, '_C__a'), C.__annotations__)
    error(lambda: C().__m)
    error(lambda: C._C__m(C(), p=1))

    class _:
        __x = 1

    class __:
        __y = 2

    class ___A:
        __z = 3
    print([k for k in _.__dict__ if 'x' in k], [k for k in __.__dict__ if 'y' in k], [k for k in ___A.__dict__ if 'z' in k])

    class D:
        def f(self):
            return __name__, __debug__, self.__class__.__name__
    print(D().f())

    class Match:
        def m(self, x):
            match x:
                case Point(__a=1):
                    return 'matched'
                case __v:
                    return type(__v).__name__

    class Point:
        _Match__a = 1
    print(Match().m(Point()), Match().m(5))

    # a generic class's type parameters are its private names (TypeVar's own name unmangled)
    class G[__T]:
        def m(self, x: __T) -> __T:
            return x
    print(G.__type_params__, G.m.__annotations__)


mangling()


class S:
    def __init__(self):
        self.a = 1
        self.b, self.c = 2, 3
        self.d += 1 if False else 0
        (self.e) = 5
        for self.f in range(1):
            pass

    def other(this):
        this.z = 1
        self.not_self = 1 if False else None

    @staticmethod
    def st(x):
        x.st_attr = 1

    def nested(self):
        def inner():
            self.inner_attr = 1

        class K:
            def m(self):
                self.k_attr = 1
        return K

    def deleted(self):
        del self.gone

    def annotated(self):
        self.ann: int = 1
        self.ann2: int

    def __mangled(self):
        self.__private = 1


def deco(c):
    return c


@deco
class Decorated:
    pass


class Doc:
    "doc"
    x = 1


def class_attributes():
    print(S.__static_attributes__, S.nested(None).__static_attributes__, Doc.__static_attributes__)
    print(S.__firstlineno__, Decorated.__firstlineno__, Doc.__firstlineno__, C.__firstlineno__)
    print([k for k in Doc.__dict__ if k in ('__module__', '__firstlineno__', '__doc__', 'x', '__static_attributes__')])
    T = type('T', (), {})
    print(hasattr(T, '__static_attributes__'), hasattr(T, '__firstlineno__'))


class_attributes()


def debug():
    print(__debug__, type(__debug__).__name__)
    if __debug__:
        print('debug on')


debug()
