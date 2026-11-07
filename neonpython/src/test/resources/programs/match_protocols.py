# match's sequences and mappings by their types' flags, star wildcards taken by index, class patterns through
# isinstance (ABCs, protocols), and the compiler's pattern errors, as CPython 3.14 gives them.
import collections, collections.abc as cabc
from typing import Protocol, runtime_checkable


def t(label, f):
    try:
        print(label, '->', f())
    except BaseException as e:
        print(label, '!!', type(e).__name__, e)


class Endless(cabc.Sequence):
    calls = []
    def __getitem__(self, i):
        self.calls.append(i)
        return i
    def __len__(self):
        return 42


class NoLen(cabc.Sequence):
    def __getitem__(self, i):
        raise IndexError
    def __len__(self):
        raise RuntimeError('len called')


def seq(v):
    match v:
        case [*_]:
            return 'any'


def ends(v):
    match v:
        case [0, *_, 41]:
            return 'ends'
        case [x, _, *_, y]:
            return 'x y', x, y


def sequences():
    t('star wildcard', lambda: (ends(Endless()), Endless.calls))
    Endless.calls.clear()
    t('first fails', lambda: (ends(type('E2', (Endless,), {'__getitem__': lambda s, i: (s.calls.append(i), i + 1)[1]})()), Endless.calls))
    t('lone star', lambda: seq(NoLen()))
    t('deque', lambda: ends(collections.deque(range(42))))
    t('range', lambda: ends(range(42)))
    t('memoryview', lambda: seq(memoryview(b'ab')))

    def rest(v):
        match v:
            case [a, *r]:
                return a, r
    t('star capture', lambda: (rest(collections.deque([1, 2, 3])), rest(collections.UserList([4, 5]))))


def mappings():
    def m(v):
        match v:
            case {'a': 1, **kw}:
                return 'a', kw
            case {'b': b}:
                return 'b', b
            case {}:
                return 'empty'
    t('chainmap', lambda: m(collections.ChainMap({'a': 1}, {'c': 3})))
    t('userdict', lambda: m(collections.UserDict(b=2)))
    t('mappingproxy', lambda: m(type('K', (), {}).__dict__)[0])
    t('keys view', lambda: m({}.keys()))

    class Getter(cabc.Mapping):
        log = []
        def __getitem__(self, k):
            self.log.append(('getitem', k))
            return 1
        def __iter__(self):
            return iter(['a', 'z'])
        def __len__(self):
            return 2
        def get(self, k, d=None):
            self.log.append(('get', k))
            return 1 if k in ('a', 'z') else d
    g = Getter()
    t('get used', lambda: (m(g), g.log))


@runtime_checkable
class HasXY(Protocol):
    __match_args__ = ('x', 'y')
    x: int
    y: int


class Plain(Protocol):
    def f(self): ...


class Pt:
    def __init__(self, x, y):
        self.x, self.y = x, y


class Reg: pass


cabc.Sized.register(Reg)


def classes():
    def c(v):
        match v:
            case HasXY(x, y):
                return 'point', x, y
            case cabc.Sized():
                return 'sized'
            case _:
                return 'other'
    t('protocol', lambda: c(Pt(1, 2)))
    t('registered', lambda: (c(Reg()), c([1])))
    t('other', lambda: c(5))

    def plain(v):
        match v:
            case Plain():
                return 'plain'
    t('plain protocol', lambda: plain(1))

    class Twice:
        __match_args__ = ('a', 'a')
        a = 1

    def twice(v):
        match v:
            case Twice(p, q):
                return p, q
    t('twice', lambda: twice(Twice()))

    class Missing:
        __match_args__ = ('m', 'm')

    def missing(v):
        match v:
            case Missing(p, q):
                return p, q
            case _:
                return 'no match'
    t('missing first', lambda: missing(Missing()))

    def kw(v):
        match v:
            case Twice(a, a=b):
                return b
    t('positional and keyword', lambda: kw(Twice()))


def errors():
    for s in ["case Class(a=_, a=_)", "case Class(a=_, b=_, a=_)", "case (*x)", "case (*x,)", "case {0: _, False: _}",
              "case {0: _, 0.0: _}", "case {0: _, -0: _}", "case {'a': _, 'a': _}", "case {0: _, 0j: _}", "case {1: _, True: _}",
              "case {1: _, 1+0j: _}", "case {0j+0: _}", "case 0j+0j", "case -0j+0j", "case {-0j+0j: _}", "case 0+0", "case 1-2j",
              "case {x.y: _, x.y: _}", "case {b'a': _, 'a': _}"]:
        try:
            compile("match ...:\n " + s + ":\n  pass", '<t>', 'exec')
            print(s, 'ok')
        except SyntaxError as e:
            print(s, '->', e.msg, e.lineno, e.offset)


sequences()
mappings()
classes()
errors()
