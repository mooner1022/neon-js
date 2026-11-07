# Types subscripted (PEP 585's types.GenericAlias) and joined with | (PEP 604; 3.14's typing.Union), the values
# annotations hold; PEP 560's __mro_entries__ for a class deriving from one.


def error(f, *args):
    try:
        f(*args)
        print('no error')
    except Exception as e:
        print(type(e).__name__ + ':', e)


class C:
    pass


def fn():
    pass


def aliases():
    la = list[int]
    print(la, repr(dict[str, list[int]]), tuple[int, ...], tuple[()], list[None], list[1, 'a'], type[int], set[int], frozenset[str])
    print(list[C], list[fn], list[len], list[[int, str]], list[int, str], dict[int])
    print(type(la).__name__, type(la).__module__, type(la).__qualname__)
    print(la.__origin__, la.__args__, la.__parameters__, la.__unpacked__, la.__name__, la.__qualname__, la.__module__)
    print(la.append is list.append, la.__class__, list[(int,)].__args__, list[int,].__args__)
    print(la(), la((1, 2)), dict[str, int](a=1), tuple[int]((1,)))
    print(la == list[int], la == list[str], la == list, list[int] != list[int], hash(la) == hash(list[int]), {la: 1}[list[int]])
    print(tuple[()] == tuple[()], bool(la), str(la))
    error(isinstance, [], la)
    error(issubclass, list, la)
    error(lambda: la[str])
    error(lambda: int[0])
    error(lambda: fn[0])
    error(setattr, la, 'x', 1)
    print(la.__mro_entries__((la,)))
    # *tuple[...]: iterating an alias gives its starred form
    print(list(iter(tuple[int, ...])), repr(next(iter(tuple[int, ...]))), next(iter(tuple[int])).__unpacked__, [*tuple[int, str]])
    print(next(iter(tuple[int])).__typing_unpacked_tuple_args__, la.__typing_unpacked_tuple_args__)
    print(list.__class_getitem__(int), tuple.__class_getitem__((int, str)))

    class MyList(list):
        pass
    print(MyList[int], type(MyList[int]).__name__)

    class Custom:
        def __class_getitem__(cls, item):
            return 'custom ' + repr(item)
    print(Custom[int], Custom[1, 2])

    class Meta(type):
        def __getitem__(cls, item):
            return 'meta ' + repr(item)

    class WithMeta(metaclass=Meta):
        pass
    print(WithMeta[3])


aliases()


def unions():
    u = int | str
    print(u, type(u).__name__, type(u).__module__, u.__args__, u.__origin__, u.__parameters__, u.__name__)
    print((int | None).__args__, None | int, int | None | str)
    print((int | str) | (bytes | int), int | int, (int | int) is int, list[int] | None, list[int] | dict[str, int])
    print((int | str) == (str | int), hash(int | str) == hash(str | int), (int | str) == int, {int | str: 'u'}[str | int])
    print(isinstance(1, int | str), isinstance(None, int | None), issubclass(bool, int | str), isinstance(1, (str, int | None)))
    print(isinstance(1, int | list[int]), isinstance('s', int | str | bytes), isinstance(1.5, int | str))
    print(C | None, list[C] | int, bool(u), type(list[int] | None).__name__)
    error(lambda: int | 1)
    error(lambda: 1 | int)
    error(lambda: int | 'str')
    error(lambda: (int | str)[int])
    error(lambda: (int | str)())
    error(isinstance, [], int | list[int])


unions()


def subclassing():
    class Sub(list[int]):
        pass
    print(Sub.__mro__, Sub.__orig_bases__, Sub.__bases__, Sub([1, 2]))

    class Plain(list):
        pass
    print('__orig_bases__' in Plain.__dict__)

    class Entries:
        def __mro_entries__(self, bases):
            print('  bases', len(bases))
            return (dict,)

    class FromEntries(Entries(), C):
        pass
    print(FromEntries.__mro__, FromEntries.__orig_bases__[1])

    class Bad:
        def __mro_entries__(self, bases):
            return [dict]

    def bad():
        class X(Bad()):
            pass
    error(bad)


subclassing()


def in_annotations():
    def f(a: list[int], b: dict[str, int | None] = None, *c: *tuple[int, ...]) -> int | None:
        pass
    print(f.__annotations__)

    class K:
        x: set[C] | None
        y: type[C]
    print(K.__annotations__)


in_annotations()
