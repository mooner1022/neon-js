# properties
class C:
    def __init__(self):
        self._x = 1

    @property
    def x(self):
        return self._x

    @x.setter
    def x(self, v):
        self._x = v * 2

    @x.deleter
    def x(self):
        print('deleting')
        del self._x

    @staticmethod
    def s(a, b):
        return a + b

    @classmethod
    def make(cls, v):
        o = cls()
        o.x = v
        return o

c = C()
print(c.x)
c.x = 5
print(c.x, c._x)
del c.x
print(hasattr(c, '_x'))
print(C.s(1, 2), c.s(3, 4), C.make(7).x)

class D(C):
    pass
d = D.make(2)
print(type(d).__name__, d.x, type(C.__dict__['x']).__name__)

class E:
    def g(self):
        return 'got'
    p = property(g)
print(E().p, type(E.p), type(E.__dict__['s'] if False else C.__dict__['s']), type(C.__dict__['make']))

# descriptors of one's own, named by __set_name__
class Typed:
    def __set_name__(self, owner, name):
        self.name = '_' + name

    def __get__(self, obj, objtype=None):
        if obj is None:
            return self
        return getattr(obj, self.name, 'unset')

    def __set__(self, obj, value):
        setattr(obj, self.name, value)

class F:
    a = Typed()
    b = Typed()

f = F()
print(f.a)
f.a = 3
print(f.a, f._a, F.a.name, F.b.name, type(F.a).__name__)

# a non-data descriptor is shadowed by the instance's dict; a data descriptor is not
class NonData:
    def __get__(self, obj, objtype=None):
        return 'from descriptor'

class G:
    nd = NonData()
    td = Typed()

g = G()
print(g.nd)
g.nd = 'instance'
print(g.nd)
g.__dict__['td'] = 'in dict'
print(g.td)

# __getattr__ for what is not found
class H:
    def __getattr__(self, name):
        return name.upper()

h = H()
h.real = 1
print(h.real, h.missing)

# classmethods through instances and subclasses, staticmethods without an instance
class Base:
    kind = 'base'
    @classmethod
    def describe(cls):
        return cls.__name__ + ':' + cls.kind

class Derived(Base):
    kind = 'derived'

print(Base.describe(), Derived.describe(), Derived().describe())
