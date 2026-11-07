# Type layouts (__flags__, __weakrefoffset__, __dictoffset__, __basicsize__, __itemsize__) as Linux CPython 3.14 gives
# them, and what reads them: match's sequences and mappings (abc's collection flags), weak references, getsizeof's
# pre-headers, __class__ assignment, copyreg's protocols 0 and 1, dataclasses' slots, inspect.isabstract.
import abc, array, collections, collections.abc as cabc, copy, copyreg, dataclasses, enum, inspect, pickle, sys, types, typing, weakref


def t(label, f):
    try:
        print(label, '->', f())
    except BaseException as e:
        print(label, '!!', type(e).__name__, e)


def layout(c):
    return hex(c.__flags__), c.__weakrefoffset__, c.__dictoffset__, c.__basicsize__, c.__itemsize__


def ok(f):
    try:
        f()
        return True
    except TypeError:
        return False


def builtins_():
    for c in (object, int, bool, float, complex, str, bytes, bytearray, list, tuple, dict, set, frozenset, range, slice,
              memoryview, type, property, staticmethod, classmethod, super, enumerate, zip, map, filter, reversed,
              BaseException, Exception, ValueError, OSError, KeyError, StopIteration, types.FunctionType,
              types.ModuleType, types.MethodType, types.GeneratorType, types.CodeType, types.SimpleNamespace,
              types.BuiltinFunctionType, types.MappingProxyType, type(iter([])), type({}.keys()), collections.deque,
              collections.defaultdict, array.array, weakref.ref, type(None), type(NotImplemented), types.CellType,
              types.MethodWrapperType, types.WrapperDescriptorType, types.MethodDescriptorType):
        t(c.__qualname__, lambda: layout(c))


def classes():
    class C: pass
    class D(C): pass
    class P: __slots__ = ()
    class Q: __slots__ = ('a', 'b')
    class R: __slots__ = ('__dict__',)
    class W: __slots__ = ('__weakref__',)
    class RW: __slots__ = ('__dict__', '__weakref__', 'x')
    class PD(P): pass
    class QS(Q): __slots__ = ('c',)
    class CS(C): __slots__ = ('a',)
    class M(C, Q): pass
    class Ab(abc.ABC):
        @abc.abstractmethod
        def f(self): ...
    class Co(Ab):
        def f(self): ...
    class Call(type):
        def __call__(cls): ...
    class E(enum.Enum):
        A = 1
    class Ex(Exception): pass
    class ExS(Exception): __slots__ = ('a',)
    class ExW(Exception): __slots__ = ('__weakref__',)
    class Ty(type): pass
    class Tu(tuple): pass
    class TuP(tuple): __slots__ = ()
    class By(bytes): pass
    class Ia(int): pass
    class Fl(float): pass
    class Cx(complex): pass
    class St(str): pass
    class StS(str): __slots__ = ('a',)
    class Li(list): pass
    class Dc(dict): pass
    class Se(set): pass
    class SeP(set): __slots__ = ()
    class Dq(collections.deque): pass
    class DqD(collections.deque): __slots__ = ('__dict__',)
    class Dd(collections.defaultdict): pass
    class Mo(types.ModuleType): pass
    class Nt(typing.NamedTuple):
        a: int
    @dataclasses.dataclass(slots=True)
    class DtS:
        a: int
    for n, c in list(locals().items()):
        if isinstance(c, type):
            t(n, lambda: layout(c))
    k = type('Y', (), {})
    k.__abstractmethods__ = frozenset({'x'})
    t('abstract later', lambda: hex(k.__flags__))
    k.__abstractmethods__ = frozenset()
    t('abstract cleared', lambda: hex(k.__flags__))
    t('slot errors', lambda: [x for x in (set, Exception, tuple) for sl in (('__weakref__',), ('__dict__',))
                              if not ok(lambda: type('Z', (x,), {'__slots__': sl}))])
    for b, sl in ((type, ('a',)), (Tu, ('__weakref__',)), (int, ['a']), (bytes, 'a'), (Tu, ())):
        t(f'var slots {b.__name__} {sl}', lambda: type('X', (b,), {'__slots__': sl}).__basicsize__)


def weakrefs():
    class ExS(Exception): __slots__ = ('a',)
    class ExW(Exception): __slots__ = ('__weakref__',)
    class SeP(set): __slots__ = ()
    for base, arg in ((tuple, ()), (int, 0), (bytes, b''), (str, ''), (list, []), (set, ()), (Exception, ())):
        K = type('K', (base,), {})
        t('weakref ' + base.__name__, lambda: ok(lambda: weakref.ref(K(arg))))
    t('weakref slots', lambda: (ok(lambda: weakref.ref(ExS())), ok(lambda: weakref.ref(ExW())), ok(lambda: weakref.ref(SeP()))))
    t('weakref builtin', lambda: (ok(lambda: weakref.ref(ValueError())), ok(lambda: weakref.ref(set()))))


def kind(c):
    f = c.__flags__
    return ('S' if f & 32 else '') + ('M' if f & 64 else '') or '-'


def collections_():
    class Mixin: pass
    class S1(cabc.Sequence):
        def __getitem__(self, i): raise IndexError
        def __len__(self): return 0
    class S2(Mixin, cabc.Sequence):
        def __getitem__(self, i): raise IndexError
        def __len__(self): return 0
    class M1(cabc.Mapping):
        def __init__(self, d=None): self.d = d or {}
        def __getitem__(self, k): return self.d[k]
        def __len__(self): return len(self.d)
        def __iter__(self): return iter(self.d)
    class Rg: pass
    class RgSub(Rg): pass
    cabc.Sequence.register(Rg)
    class RgLater(Rg): pass
    class Both(S1, M1): pass
    class Both2(M1, S1): pass
    class X: pass
    cabc.Sequence.register(X)
    cabc.Mapping.register(X)
    class L(list): pass
    cabc.Mapping.register(L)
    cabc.Sequence.register(str)
    class Cl: pass

    def m(v):
        match v:
            case {'a': a, **rest}:
                return 'mapping a', a, rest
            case {}:
                return 'mapping'
            case [x, *_]:
                return 'sequence', x
            case [*_]:
                return 'sequence'
            case _:
                return 'other'

    t('popped', lambda: ('__abc_tpflags__' in cabc.Sequence.__dict__, '__abc_tpflags__' in cabc.Mapping.__dict__))
    t('kinds', lambda: [kind(c) for c in (list, tuple, range, memoryview, array.array, collections.deque, str, bytes, bytearray,
                                          dict, set, collections.OrderedDict, collections.Counter, collections.ChainMap,
                                          collections.UserDict, collections.UserList, type({}.keys()), types.MappingProxyType,
                                          S1, S2, M1, Rg, RgSub, RgLater, Both, Both2, X, L, cabc.Set)])
    for v in ([1], (1,), range(1), memoryview(b'a'), array.array('b', [7]), collections.deque([2]), 'a', b'a', bytearray(b'a'),
              {'a': 1, 'b': 2}, collections.OrderedDict(a=3), collections.Counter('ab'), collections.ChainMap({'a': 4}),
              collections.UserDict(a=5), collections.UserList([6]), vars(Cl), {}.keys(), S1(), S2(), M1({'a': 8, 'c': 9}),
              Rg(), RgSub(), RgLater(), Both(), Both2(), X(), L([1]), set()):
        t('match ' + type(v).__qualname__.rpartition('.')[2], lambda: m(v))
    try:
        class Bad(cabc.Sized):
            __abc_tpflags__ = 0x60
    except TypeError as e:
        print('both', e)
    class Own(abc.ABC):
        __abc_tpflags__ = 1 << 6
    class NotAbc:
        __abc_tpflags__ = 1 << 6
    t('own', lambda: (kind(Own), '__abc_tpflags__' in Own.__dict__, kind(NotAbc), '__abc_tpflags__' in NotAbc.__dict__))


class Pt:
    def __init__(self, x):
        self.x = x


class PI(int):
    def __init__(self, v):
        self.tag = 't'


def consumers():
    class C: pass
    class P: __slots__ = ()
    class Ex(Exception): pass
    class Ab(abc.ABC):
        @abc.abstractmethod
        def f(self): ...
    class Co(Ab):
        def f(self): ...
    t('isabstract', lambda: (inspect.isabstract(Ab), inspect.isabstract(Co), inspect.isabstract(C), inspect.isabstract(cabc.Sequence)))
    for proto in (0, 1):
        t(f'pickle {proto}', lambda: (lambda p: (type(p).__name__, p.x))(pickle.loads(pickle.dumps(Pt(3), proto))))
        t(f'pickle int {proto}', lambda: (lambda p: (type(p).__name__, int(p), p.tag))(pickle.loads(pickle.dumps(PI(4), proto))))
        t(f'reduce_ex {proto}', lambda: (lambda r: (r[0].__name__, r[1]))(copyreg._reduce_ex(Pt(1), proto)))
    t('copy', lambda: copy.copy(Pt(5)).x)
    t('object pickle 0', lambda: ok(lambda: pickle.dumps(object(), 0)))
    t('dataclass slots', lambda: (list(dataclasses._get_slots(C)), list(dataclasses._get_slots(P)), list(dataclasses._get_slots(Ex))))

    @dataclasses.dataclass(slots=True, weakref_slot=True)
    class WS:
        a: int
    t('weakref_slot', lambda: (WS.__slots__, ok(lambda: weakref.ref(WS(1)))))

    @dataclasses.dataclass(slots=True)
    class Base:
        a: int

    @dataclasses.dataclass(slots=True)
    class Sub(Base):
        b: int
    t('slots sub', lambda: (Sub.__slots__, repr(Sub(1, 2)).rpartition('.')[2]))


# sys.getsizeof: a GC header where the base has none, the pre-header where the flags say so
def sizes():
    PREHEADER = (1 << 3) | (1 << 4)
    for base, arg in ((object, None), (int, 5), (float, 1.0), (complex, 1j), (str, 'ab'), (bytes, b'ab'), (bytearray, b'ab'),
                      (tuple, (1,)), (list, [1]), (dict, {1: 2}), (set, {1}), (frozenset, {1}), (collections.deque, [1]),
                      (array.array, 'b'), (Exception, 'x'), (types.ModuleType, 'm'), (property, None), (types.SimpleNamespace, None)):
        for sl in (None, ()):
            ns = {} if sl is None else {'__slots__': sl}
            try:
                K = type('K', (base,), ns)
                v = K() if arg is None else K(arg)
                b = base() if arg is None else base(arg)
            except Exception as e:
                print(base.__name__, sl, '!!', type(e).__name__, e)
                continue
            print(base.__name__, sl, sys.getsizeof(v) - sys.getsizeof(b), bool(K.__flags__ & PREHEADER), v.__sizeof__() - b.__sizeof__())
    class S(str): pass
    class SS(str): __slots__ = ()
    class F(float): __slots__ = ('a',)
    class FD(float): pass
    for v in ('', 'ab', 'é', 'éa', '가나', '\U0001F600x'):
        print(repr(v), sys.getsizeof(S(v)) - sys.getsizeof(v), sys.getsizeof(SS(v)), S(v).__sizeof__())
    print(sys.getsizeof(F(1.0)), F(1.0).__sizeof__(), sys.getsizeof(FD(1.0)), FD(1.0).__sizeof__())


# __class__ assignment: CPython's compatible_for_assignment over the layouts
def assignments():
    def assign(a, b, *args):
        o = a(*args)
        o.__class__ = b
        return type(o).__name__

    class A: pass
    class B: pass
    class Sa: __slots__ = ('x', 'y')
    class Sb: __slots__ = ('x', 'y')
    class Sc: __slots__ = ('y', 'x')
    class Sd: __slots__ = ('x',)
    class Se: __slots__ = ('x', 'y', '__weakref__')
    class Sf: __slots__ = ('x', 'y', '__weakref__')
    class Sub(Sa): pass
    class Sub2(Sa): pass
    class R: __slots__ = ('__dict__',)
    class W: __slots__ = ('__weakref__',)
    class P: __slots__ = ()
    class P2: __slots__ = ()
    class L1(list): pass
    class L2(list): pass
    class LP(list): __slots__ = ()
    class I1(int): pass
    class I2(int): pass
    class E1(Exception): pass
    class E2(Exception): pass
    class Mixed(A, Sa): pass

    for a, b, args in ((A, B, ()), (Sa, Sb, ()), (Sa, Sc, ()), (Sa, Sd, ()), (Se, Sf, ()), (Sa, Se, ()), (Sub, Sub2, ()),
                       (Sub, Sa, ()), (Sa, Sub, ()), (R, W, ()), (P, P2, ()), (P, A, ()), (A, P, ()), (L1, L2, ()), (L1, LP, ()),
                       (I1, I2, (3,)), (E1, E2, ()), (A, Mixed, ()), (Mixed, A, ()), (A, int, ()), (L1, list, ())):
        t(f'{a.__name__} -> {b.__name__}', lambda: assign(a, b, *args))

    o = Sa()
    o.x, o.y = 1, 2
    o.__class__ = Sb
    t('values kept', lambda: (o.x, o.y, type(o).__name__))
    o = Sa()
    o.x, o.y = 3, 4
    o.__class__ = Sc
    t('reordered kept', lambda: (o.x, o.y, type(o).__name__))
    o.__class__ = Sa
    t('back', lambda: (o.x, o.y))
    q = Sa()
    q.y = 5
    q.__class__ = Sc
    t('partly set', lambda: (hasattr(q, 'x'), q.y))


builtins_()
classes()
weakrefs()
collections_()
consumers()
sizes()
assignments()
