# Subclasses of staticmethod, classmethod and property: abc's abstractclassmethod, a property whose __get__ is its own.
class acm(classmethod):
    __isabstractmethod__ = True

    def __init__(self, f):
        f.__isabstractmethod__ = True
        super().__init__(f)


class asm(staticmethod):
    __isabstractmethod__ = True


class C:
    @acm
    def make(cls):
        return cls.__name__

    @asm
    def plain(x):
        return x * 2


class logged(property):
    def __get__(self, obj, objtype=None):
        if obj is None:
            return self
        print("get", self.fget.__name__)
        return super().__get__(obj, objtype)

    def __set__(self, obj, value):
        print("set", value)
        super().__set__(obj, value)

    def __delete__(self, obj):
        print("delete")
        super().__delete__(obj)


class D:
    def __init__(self):
        self._x = 1

    @logged
    def x(self):
        return self._x

    @x.setter
    def x(self, v):
        self._x = v

    @x.deleter
    def x(self):
        del self._x


class Cached(property):
    pass


class E:
    @Cached
    def y(self):
        "the y"
        return 7


def ab(f):
    f.__isabstractmethod__ = True
    return f


def methods():
    print(C.make(), C().make(), C.plain(4), type(C.__dict__["make"]).__name__, C.__dict__["make"].__isabstractmethod__)
    print(isinstance(C.__dict__["make"], classmethod), C.__dict__["make"].__func__.__name__, C.__dict__["plain"].__func__.__name__)
    print(classmethod(ab(lambda c: 0)).__isabstractmethod__, staticmethod(ab(lambda: 0)).__isabstractmethod__, classmethod(lambda c: 0).__isabstractmethod__)
    print(type(staticmethod.__new__(staticmethod)).__name__, type(acm.__new__(acm)).__name__, C.__dict__["plain"].__wrapped__.__name__)
    for f in [lambda: staticmethod(), lambda: classmethod(1, 2), lambda: classmethod.__init__(1, 2)]:
        try:
            f()
        except TypeError as e:
            print(e)


def properties():
    d = D()
    print(d.x)
    d.x = 5
    print(d.x, type(D.__dict__["x"]).__name__, D.__dict__["x"].fset.__name__)
    del d.x
    print(hasattr(d, "_x"), type(D.x).__name__)
    p = property(lambda s: 1, doc="hi")
    print(p.fget is not None, p.fset, p.fdel, p.__doc__, property(ab(lambda s: 0)).__isabstractmethod__, property(len).__isabstractmethod__)
    print(E().y, type(E.y).__name__, E.y.__doc__, type(E.y.getter(lambda s: 8)).__name__, property.__get__(E.__dict__["y"], E()))
    q = Cached(lambda s: 3)
    q.extra = 1
    print(q.extra, q.__dict__, isinstance(q, property))


methods()
properties()
