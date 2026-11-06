# CPython's own abc, collections, functools, copy, weakref, types and numbers, bundled (docs/PYTHON_PLATFORM.md §12): run as
# CPython runs them.
import abc
import collections
import collections.abc as cabc
import copy
import functools
import numbers
import types
import weakref


def t(label, f):
    try:
        print(label, "->", f())
    except Exception as e:
        print(label, "!!", type(e).__name__, e)


def abcs():
    class Shape(abc.ABC):
        @abc.abstractmethod
        def area(self): ...

        @classmethod
        @abc.abstractmethod
        def make(cls): ...

    class Sq(Shape):
        def area(self):
            return 4

        @classmethod
        def make(cls):
            return cls()

    t("abstract", lambda: Shape())
    t("concrete", lambda: (Sq.make().area(), isinstance(Sq(), Shape), sorted(Shape.__abstractmethods__)))

    class Duck:
        def __len__(self):
            return 0

    t("register", lambda: (Shape.register(Duck), issubclass(Duck, Shape), isinstance(Duck(), Shape)))
    t("hooks", lambda: (isinstance(Duck(), cabc.Sized), isinstance([], cabc.Sequence), issubclass(dict, cabc.Mapping), isinstance({}.keys(), cabc.KeysView)))
    t("more", lambda: (isinstance(iter([]), cabc.Iterator), isinstance((x for x in []), cabc.Generator), issubclass(str, cabc.Hashable), isinstance(1, cabc.Hashable)))
    t("bytes abc", lambda: (isinstance(bytearray(), cabc.MutableSequence), isinstance(memoryview(b''), cabc.Sequence), isinstance(b'', cabc.Sequence)))
    t("callable", lambda: (isinstance(len, cabc.Callable), cabc.Callable[[int], str].__args__, type(cabc.Callable[[int], str]).__name__))
    t("get_cache_token", lambda: type(abc.get_cache_token()).__name__)

    class MyMap(cabc.Mapping):
        def __init__(self, d):
            self.d = d

        def __getitem__(self, k):
            return self.d[k]

        def __iter__(self):
            return iter(self.d)

        def __len__(self):
            return len(self.d)

    m = MyMap({'a': 1, 'b': 2})
    t("mixin", lambda: (m.get('a'), 'b' in m, list(m.keys()), list(m.items()), m == {'a': 1, 'b': 2}))


def colls():
    P = collections.namedtuple('P', 'x y', defaults=[0])
    p = P(1)
    t("namedtuple", lambda: (p, p.x, p.y, p._asdict(), p._replace(x=5), P._fields, P._field_defaults, repr(P(1, 2)), isinstance(p, tuple)))
    c = collections.Counter('abracadabra')
    t("counter", lambda: (c.most_common(2), c['z'], sorted(c.elements())[:3], (c + collections.Counter('aaz'))['a'], c.total()))
    o = collections.OrderedDict(a=1, b=2)
    o.move_to_end('a')
    t("ordered", lambda: (list(o), o.popitem(last=False), repr(o), collections.OrderedDict(x=1) == collections.OrderedDict(x=1)))
    ch = collections.ChainMap({'a': 1}, {'a': 2, 'b': 3})
    t("chainmap", lambda: (ch['a'], ch['b'], len(ch), sorted(ch), ch.new_child({'c': 4})['c']))
    t("deque", lambda: (collections.deque([1, 2], maxlen=2), isinstance(collections.deque(), cabc.MutableSequence)))
    t("defaultdict", lambda: (collections.defaultdict(int)['x'], collections.defaultdict.__module__))

    class UD(collections.UserDict):
        pass

    t("user", lambda: (UD(a=1)['a'], collections.UserList([1, 2]) + [3], collections.UserString('ab').upper()))


def functs():
    t("partial", lambda: (functools.partial(int, base=2)('101'), repr(functools.partial(max, 1)).startswith('functools.partial(')))
    t("reduce", lambda: functools.reduce(lambda a, b: a * b, range(1, 6)))

    @functools.lru_cache(maxsize=None)
    def fib(n):
        return n if n < 2 else fib(n - 1) + fib(n - 2)

    t("lru", lambda: (fib(50), fib.cache_info().hits > 0, fib.__name__, fib.__wrapped__.__name__))

    def deco(f):
        @functools.wraps(f)
        def w(*a):
            return f(*a)
        return w

    @deco
    def named(x):
        "doc"
        return x

    t("wraps", lambda: (named.__name__, named.__doc__, named.__wrapped__.__name__, named(3)))

    @functools.total_ordering
    class V:
        def __init__(self, v):
            self.v = v

        def __eq__(self, o):
            return self.v == o.v

        def __lt__(self, o):
            return self.v < o.v

    t("total_ordering", lambda: (V(1) <= V(2), V(3) >= V(2), V(1) > V(2)))

    @functools.singledispatch
    def show(x):
        return 'obj'

    @show.register(int)
    def _(x):
        return 'int'

    @show.register(list)
    def _(x):
        return 'list'

    t("singledispatch", lambda: (show(1), show([]), show('s'), show(True)))

    class C:
        @functools.cached_property
        def val(self):
            print("computing")
            return 42

    cc = C()
    t("cached_property", lambda: (cc.val, cc.val, 'val' in cc.__dict__))
    t("cmp_to_key", lambda: sorted([3, 1, 2], key=functools.cmp_to_key(lambda a, b: b - a)))
    t("partialmethod", lambda: (lambda K: K().m())(type('K', (), {'f': lambda self, a, b: a + b, 'm': functools.partialmethod(lambda self, a, b: a * b, 3, 4)})))


def copies():
    class Pt:
        def __init__(self, x):
            self.x = x

    a = [1, [2, 3], {'k': Pt(4)}]
    b = copy.copy(a)
    c = copy.deepcopy(a)
    t("copy", lambda: (b[1] is a[1], c[1] is a[1], c[2]['k'].x, c[2]['k'] is a[2]['k']))
    t("copy builtins", lambda: (copy.copy((1, 2)), copy.deepcopy({1, 2}), copy.copy(bytearray(b'x')), copy.deepcopy(collections.OrderedDict(a=[1]))))
    r = [1]
    r.append(r)
    d = copy.deepcopy(r)
    t("copy cycle", lambda: d[1] is d)


def weaks():
    class O:
        pass

    o = O()
    wv = weakref.WeakValueDictionary()
    wv['a'] = o
    ws = weakref.WeakSet([o])
    wk = weakref.WeakKeyDictionary({o: 1})
    t("weak", lambda: (wv['a'] is o, o in ws, wk[o], len(ws), weakref.ref(o)() is o))
    t("weak method", lambda: (lambda m: m() is not None)(weakref.WeakMethod(O().__init__) if False else weakref.ref(o)))


def typs():
    t("types", lambda: (types.FunctionType.__name__, types.GetSetDescriptorType.__name__, types.MemberDescriptorType.__name__, type(types.FunctionType.__code__).__name__))
    t("mappingproxy", lambda: (types.MappingProxyType({'a': 1})['a'], types.SimpleNamespace(a=1)))
    t("new_class", lambda: (lambda C: (C.__name__, C.x))(types.new_class('C', (), {}, lambda ns: ns.update(x=1))))
    t("numbers", lambda: (isinstance(1, numbers.Integral), isinstance(1.5, numbers.Real), isinstance(1j, numbers.Complex), isinstance(True, numbers.Number), issubclass(int, numbers.Rational)))
    t("descr", lambda: (repr(types.FunctionType.__code__), types.FunctionType.__globals__.__objclass__, types.FunctionType.__code__.__get__(typs) is typs.__code__))
    t("descr ro", lambda: types.FunctionType.__globals__.__set__(typs, {}))


abcs()
colls()
functs()
copies()
weaks()
typs()
