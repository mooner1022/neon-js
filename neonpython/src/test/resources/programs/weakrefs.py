# Weak references, __del__ and gc.collect().
import gc
import _weakref


class C:
    def __init__(self, n):
        self.n = n

    def m(self):
        return self.n


class D:
    log = []

    def __init__(self, n):
        self.n = n

    def __del__(self):
        D.log.append(self.n)


# (what a statement looks at goes in a function of its own: code the tier compiled keeps a function's temporaries
# until it returns, while the JVM interprets it)
def look(r, c):
    return r() is c, r().n, _weakref.ref(c) is r, _weakref.getweakrefcount(c), type(r).__name__, callable(r)


def look2(r, r2, c):
    return r2 is not r, _weakref.getweakrefcount(c), r2.__callback__ is not None, hash(r) == hash(c), r == r2


def made():
    return _weakref.ref(C(2))


def refs():
    c = C(1)
    r = _weakref.ref(c)
    print(look(r, c))
    got = []
    r2 = _weakref.ref(c, lambda ref: got.append(ref is r2))
    print(look2(r, r2, c))
    del c
    gc.collect()
    print(r() is None, r2() is None, got, repr(r).endswith("; dead>"))
    r3 = made()
    gc.collect()
    try:
        hash(r3)
    except TypeError as e:
        print("TypeError", e)
    for v in (1, "s", [], {}, (1,), object()):
        try:
            _weakref.ref(v)
        except TypeError as e:
            print("TypeError", e)
    print(_weakref.ref(len)() is len, _weakref.ref(C)() is C, _weakref.ref(refs)() is refs)


def look_proxy(p, c):
    print(p.n, p.m(), type(p).__name__, p.__class__.__name__)
    p.n = 6
    print(c.n)


def proxies():
    c = C(5)
    p = _weakref.proxy(c)
    look_proxy(p, c)
    del c
    gc.collect()
    try:
        p.n
    except ReferenceError as e:
        print("ReferenceError", e)
    lst = [1, 2, 3]

    class L(list):
        pass
    xs = L([3, 1, 2])
    q = _weakref.proxy(xs)
    print(len(q), q[0], 2 in q, sorted(q), q + [9], str(q))
    f = _weakref.proxy(C.m)
    print(type(f).__name__, f(C(7)))


def made_d():
    D(1)


def dels():
    made_d()
    x = D(2)
    del x
    gc.collect()
    print(sorted(D.log))


class Sub(_weakref.ref):
    def __new__(cls, ob, key):
        self = _weakref.ref.__new__(cls, ob)
        self.key = key
        return self

    def __init__(self, ob, key):
        super().__init__(ob)


def subclass():
    c = C(3)
    s = Sub(c, "k")
    print(s.key, s() is c, type(s).__name__, isinstance(s, _weakref.ref), _weakref.getweakrefcount(c))


def gc_module():
    print(gc.isenabled(), gc.collect() >= 0)
    gc.disable()
    print(gc.isenabled())
    gc.enable()
    print(gc.isenabled(), isinstance(gc.garbage, list))


refs()
proxies()
dels()
subclass()
gc_module()
