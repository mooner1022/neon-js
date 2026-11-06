# Type parameters of functions and type aliases (PEP 695, PEP 696's defaults): TypeVar, TypeVarTuple and ParamSpec
# made by a generic's <generic parameters of X> function, their bounds, constraints, defaults and alias values
# evaluated when first asked for.


def error(f, *args):
    try:
        f(*args)
        print('no error')
    except Exception as e:
        print(type(e).__name__ + ':', e)


def functions():
    def f[T, *Ts, **P](x: T, *a: *Ts) -> T:
        return x
    print(f.__type_params__, [type(p).__name__ for p in f.__type_params__], [repr(p) for p in f.__type_params__])
    print(f(5), f.__name__, f.__qualname__.split('.')[-1], f.__annotations__)
    T = f.__type_params__[0]
    print(T.__name__, T.__bound__, T.__constraints__, T.__covariant__, T.__contravariant__, T.__infer_variance__,
          T.has_default(), T.__default__, T.__module__, type(T).__module__, type(T).__qualname__)
    print(T.evaluate_bound, T.evaluate_constraints, T.evaluate_default)

    # bounds, constraints and defaults: evaluated when first asked for, once
    def log(v):
        print('  eval', v)
        return v

    def g[B: log(int), C: (int, str), D = log(str), *E = *tuple[int], **F = [int]]():
        pass
    B, C, D, E, F = g.__type_params__
    print('made')
    print(B.__bound__, B.__bound__, C.__constraints__, C.__bound__, D.__default__, D.__default__)
    print(E.__default__, F.__default__, D.has_default(), B.has_default(), E.has_default())
    print(B.evaluate_bound(1), B.evaluate_bound.__name__, C.evaluate_constraints(2), D.evaluate_default())
    error(B.evaluate_bound, 3)

    def lazy[L: Later]():
        pass
    error(lambda: lazy.__type_params__[0].__bound__)
    global Later
    Later = 'defined later'
    print(lazy.__type_params__[0].__bound__)

    # the type parameters are the body's names, and the annotations'; defaults are evaluated outside
    def body[T]():
        return T
    print(body() is body.__type_params__[0])

    def with_defaults[T](x=1, *, k=2):
        return x, k
    print(with_defaults(), with_defaults.__defaults__, with_defaults.__kwdefaults__)
    try:
        def bad[Q](x=Q):
            pass
    except NameError as e:
        print('NameError:', e)

    # decorators apply to the generic function; methods' qualnames do not show the type parameters' scope
    def dec(fn):
        print('  decorating', fn.__type_params__)
        return fn

    @dec
    def decorated[T](): pass

    class K:
        def m[T](self, x: T) -> T:
            return x
    print(K.m.__qualname__, K().m(3), K.m.__type_params__)

    async def coro[T](x: T) -> T:
        return x
    c = coro(1)
    print(coro.__type_params__, type(c).__name__)
    c.close()

    # __type_params__ of a plain function: settable to a tuple only
    def plain():
        pass
    print(plain.__type_params__, (lambda: 1).__type_params__)
    plain.__type_params__ = (1,)
    print(plain.__type_params__)
    error(setattr, plain, '__type_params__', [1])
    error(delattr, plain, '__type_params__')


functions()


def type_variables():
    def f[T, *Ts, **P, U = int]():
        pass
    T, Ts, P, U = f.__type_params__
    print(P.args, P.kwargs, type(P.args).__name__, P.args.__origin__ is P, P.args == P.args, P.kwargs.__origin__)
    print(T | None, None | T, T | int, type(T | int).__name__, (T | int).__args__, T | 1)
    print(list(Ts), type(list(Ts)[0]).__name__, list(Ts)[0].__origin__, list(Ts)[0].__args__)
    print(hash(T) == hash(T), T == T, T == U, T is f.__type_params__[0])
    print(Ts.__name__, Ts.has_default(), P.__covariant__, P.__infer_variance__, P.__bound__)
    print(T.__typing_subst__(int))
    T.custom = 1
    print(T.custom)
    error(setattr, T, '__bound__', 1)
    error(T)
    error(lambda: T.nope)
    error(list, T)

    # substituted in aliases and unions: defaults fill what is missing
    print(list[T][int], dict[T, U][str, int], dict[T, U][str], list[T].__parameters__, dict[T, list[U]].__parameters__)
    print((T | None)[int], (int | T).__parameters__, (T | U)[str], tuple[T, U, T].__parameters__)
    print(tuple[*Ts].__parameters__, tuple[*Ts][int, str], tuple[T, *Ts][int, str, bytes], tuple[T, *Ts][int])
    error(lambda: list[T][int, str])
    error(lambda: dict[T, T][()])
    error(lambda: tuple[T, U][1, 2, 3])


type_variables()


type Alias = int | str
type Lazy = LaterAlias
type Gen[T, *Ts] = dict[T, tuple[*Ts]]
type Rec = list[Rec]


def aliases():
    print(Alias, type(Alias).__name__, type(Alias).__module__, Alias.__name__, Alias.__value__, Alias.__type_params__)
    print(Alias.__module__, Alias.__parameters__, repr(Alias), str(Alias), Alias | None, type(Alias | None).__name__)
    error(lambda: Alias[int])
    error(Alias)
    error(setattr, Alias, '__value__', 1)
    error(setattr, Alias, 'other', 1)
    error(isinstance, 1, Alias)
    print(hash(Alias) == hash(Alias), Alias == Alias)
    error(lambda: Lazy.__value__)
    global LaterAlias
    LaterAlias = 'later'
    print(Lazy.__value__)
    print(Gen.__type_params__, Gen.__parameters__, Gen[int, str, bytes], Gen.__value__)
    print(type(Gen[int]).__name__, Gen[int].__origin__ is Gen, Gen[int].__args__, Gen[int].__parameters__)
    T = Gen.__type_params__[0]
    print(Gen[T].__parameters__, Gen.evaluate_value(1), Gen.evaluate_value.__name__)
    error(Gen.evaluate_value, 3)
    print(Rec.__value__)

    class C:
        X = int
        type A = X
        type B[T] = list[X]

        def meth[T: X](self):
            pass
    print(C.A.__value__, C.B.__value__, C.meth.__type_params__[0].__bound__, C.A.__module__)


aliases()


def soft_keyword():
    type = 'a soft keyword'
    print(type)


soft_keyword()
