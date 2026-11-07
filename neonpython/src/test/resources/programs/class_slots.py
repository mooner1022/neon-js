# __slots__: member descriptors, no __dict__ (nor weak references) without one, CPython's errors; a descriptor's __delete__; copy of slots.
import weakref, copy
def t(label, f):
    try:
        print(label, "->", f())
    except Exception as e:
        print(label, "!!", type(e).__name__, e)
class S:
    __slots__ = ('a', '__b')
    def __init__(self): self.a = 1
class D(S):
    pass
class E(S):
    __slots__ = ('c', '__dict__')
class N(S):
    __slots__ = ()
s = S()
t("get", lambda: (s.a, S.a, repr(S.a), type(S.a).__name__, S.__slots__, S.a.__name__, S.a.__objclass__))
t("unset", lambda: s._S__b)
t("unset2", lambda: S().__dict__)
t("set new", lambda: setattr(s, 'z', 1))
t("del", lambda: (delattr(s, 'a'), hasattr(s, 'a')))
t("del again", lambda: delattr(s, 'a'))
t("vars", lambda: vars(s))
t("sub dict", lambda: (D().__dict__, setattr(D(), 'q', 1), N().a))
t("sub no dict", lambda: setattr(N(), 'q', 1))
t("E", lambda: (E().__dict__, 'c' in E.__dict__))
t("mangled", lambda: [k for k in S.__dict__ if 'b' in k])
t("object", lambda: setattr(object(), 'x', 1))
t("bad type", lambda: type('X', (), {'__slots__': 1}))
t("bad item", lambda: type('X', (), {'__slots__': (1,)}))
t("bad ident", lambda: type('X', (), {'__slots__': ('a b',)}))
t("conflict", lambda: type('X', (), {'__slots__': ('a',), 'a': 1}))
t("dict twice", lambda: type('X', (D,), {'__slots__': ('__dict__',)}))
t("weakref", lambda: type('X', (D,), {'__slots__': ('__weakref__',)}))
t("int slots", lambda: type('X', (int,), {'__slots__': ('a',)}))
t("int empty", lambda: type('X', (int,), {'__slots__': ()})(5))
t("str empty", lambda: setattr(type('X', (str,), {'__slots__': ()})('a'), 'q', 1))
t("dup", lambda: type('X', (), {'__slots__': ('a', 'a')}))
t("layout", lambda: type('X', (S, type('Y', (), {'__slots__': ('q',)})), {}))
t("str slot", lambda: type('X', (), {'__slots__': 'abc'}).abc)
t("descr wrong obj", lambda: S.a.__get__(5))
t("weakref slotted", lambda: weakref.ref(S()))
t("copy", lambda: copy.copy(s).__class__.__name__)
s2 = S(); s2._S__b = 7
t("copy slots", lambda: (copy.copy(s2)._S__b, copy.copy(s2).a))
t("getstate", lambda: s2.__getstate__())
class Desc:
    def __get__(self, o, t=None): return 'got'
    def __set__(self, o, v): print('set', v)
    def __delete__(self, o): print('deleted')
class OnlySet:
    def __get__(self, o, t=None): return 'g'
    def __set__(self, o, v): pass
class H:
    d = Desc()
    s = OnlySet()
h = H()
h.d = 1
del h.d
t("del no __delete__", lambda: delattr(h, 's'))
