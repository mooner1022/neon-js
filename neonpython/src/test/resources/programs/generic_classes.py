# Generic classes (PEP 695's class C[T]): typing.Generic[...] a base the class statement adds, its subscriptions
# typing._GenericAlias, its subclasses' __parameters__ computed as typing.py's Generic does.


def error(f, *args):
    try:
        f(*args)
        print('no error')
    except Exception as e:
        print(type(e).__name__ + ':', e)


class K[T]:
    def __init__(self, v=None):
        self.v = v

    def get(self) -> T:
        return self.v


class L(K[str]):
    pass


class M[U](K[U]):
    pass


def classes():
    print(K.__type_params__, K.__mro__, K.__bases__, K.__orig_bases__, K.__parameters__)
    print(sorted(k for k in K.__dict__ if k in ('__orig_bases__', '__parameters__', '__type_params__')))
    print(L.__bases__, L.__orig_bases__, L.__parameters__, L.__type_params__, '__type_params__' in L.__dict__)
    print(M.__bases__, M.__orig_bases__, M.__parameters__, M.__type_params__, M.__mro__)
    print(K.get.__annotations__, K.__qualname__, K(3).get(), isinstance(L(), K), isinstance(K(), object))

    class Plain:
        pass
    print(Plain.__type_params__)

    class Inner[T](list):
        y: T
    print(Inner.__qualname__.split('.')[-1], Inner.__type_params__, Inner.__mro__[1:], Inner.__annotations__, Inner([1]))

    class WithKw[T](metaclass=type):
        pass
    print(WithKw.__type_params__, type(WithKw).__name__)

    class Outer:
        X = int

        class Nested[T: X]:
            pass
    print(Outer.Nested.__qualname__, Outer.Nested.__type_params__[0].__bound__)

    # the bases are evaluated in the type parameters' scope: they may use them
    class Sub[T](K[T]):
        pass
    print(Sub.__orig_bases__, Sub.__parameters__)


classes()


def generic():
    from_k = K.__type_params__[0]
    G = K.__mro__[1]
    print(G, G.__module__, G.__qualname__, type(G), G.__mro__)
    print(G[from_k], type(G[from_k]).__name__, G[from_k].__origin__, G[from_k].__args__, G[from_k].__parameters__)
    print(type(G()).__name__)
    error(lambda: G[int])
    error(lambda: G[from_k, from_k])
    error(lambda: G[()])

    def plain_generic():
        class X(G):
            pass
    error(plain_generic)

    def twice():
        class Y(G[from_k], G[from_k]):
            pass
    error(twice)

    def not_listed[A, B]():
        class Z(G[A], dict[A, B]):
            pass
    error(not_listed)


generic()


def subscriptions():
    a = K[int]
    print(a, type(a).__name__, type(a).__module__, a.__origin__, a.__args__, a.__parameters__)
    print(a == K[int], hash(a) == hash(K[int]), a == K[str], a.__name__, a.__qualname__, a.get is K.get)
    print(K[int](5).v, K[int]().__orig_class__, K[None].__args__)
    print(K.__class_getitem__(int), K[int] | None, type(K[int] | None).__name__)
    T = K.__type_params__[0]
    print(K[T], K[T].__parameters__, K[T][int], K[list[T]][str])
    error(lambda: K[int, str])
    error(lambda: K[()])
    error(lambda: L[int])
    error(isinstance, K(), K[int])
    error(issubclass, L, K[int])
    print(K[int].__mro_entries__((K[int],)))

    class D[A, B = int]:
        pass
    print(D[str], D[str, bytes])
    error(lambda: D[()])

    class G[*Ts]:
        pass
    print(G[int, str], G[()], G.__parameters__)

    class P[**Q]:
        pass
    print(P[int, str], P[[int, str]], P[...])

    class TwoT[T, *Ts]:
        pass
    print(TwoT[int], TwoT[int, str, bytes])


subscriptions()
