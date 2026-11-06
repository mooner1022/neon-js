# classes: instances, methods, class attributes, inheritance
class Point:
    """A point."""
    dims = 2

    def __init__(self, x, y=0):
        self.x = x
        self.y = y

    def norm1(self):
        return abs(self.x) + abs(self.y)

    def moved(self, dx, dy=0):
        return Point(self.x + dx, self.y + dy)

p = Point(3, -4)
print(p.x, p.y, p.norm1(), p.dims, Point.dims, Point.__doc__)
q = p.moved(1, dy=1)
print(q.x, q.y, type(q) is Point, isinstance(q, Point), q.__class__ is Point)
print(Point.norm1(p), Point.__name__, Point.__qualname__, Point.__module__)
p.dims = 3
print(p.dims, Point.dims)
m = p.norm1
print(m(), m.__self__ is p, m.__func__ is Point.norm1, m.__name__)

class Point3(Point):
    dims = 3

    def __init__(self, x, y, z):
        super().__init__(x, y)
        self.z = z

    def norm1(self):
        return super().norm1() + abs(self.z)

r = Point3(1, 2, -3)
print(r.norm1(), r.dims, isinstance(r, Point), issubclass(Point3, Point), Point3.__bases__, Point3.__mro__)

# a diamond, and cooperative super()
class A:
    def who(self):
        return ['A']
class B(A):
    def who(self):
        return ['B'] + super().who()
class C(A):
    def who(self):
        return ['C'] + super().who()
class D(B, C):
    def who(self):
        return ['D'] + super().who()
print(D().who(), D.__mro__)

# __new__ and __init__
class Counted:
    made = 0
    def __new__(cls, *args):
        Counted.made += 1
        return super().__new__(cls)
    def __init__(self, tag):
        self.tag = tag
c1 = Counted('a')
c2 = Counted('b')
print(Counted.made, c1.tag, c2.tag)

# a class body runs, and sees its enclosing function's variables
def make(n):
    class K:
        size = n
        items = []
        for i in range(n):
            items.append(i * i)
        def get(self):
            return n
    return K
K = make(3)
print(K.size, K.items, K().get())

# attributes by name
class Bag:
    pass
b = Bag()
setattr(b, 'a', 1)
b.b = 2
print(getattr(b, 'a'), getattr(b, 'zz', 'none'), hasattr(b, 'b'), hasattr(b, 'c'))
delattr(b, 'a')
del b.b
print(hasattr(b, 'a'), hasattr(b, 'b'), b.__dict__)

# functions are objects with attributes
def f(a, b=2, *, c=3):
    "doc of f"
    return a
f.tag = 'tagged'
print(f.__name__, f.__qualname__, f.__doc__, f.__defaults__, f.__kwdefaults__, f.tag, f.__module__)

# type(name, bases, dict) and metaclasses
T = type('T', (), {'x': 1, 'get': lambda self: self.x * 10})
print(T.__name__, T.x, T().get(), T.__mro__)

class Meta(type):
    def __new__(mcs, name, bases, ns):
        ns['added'] = name.lower()
        return super().__new__(mcs, name, bases, ns)
class WithMeta(metaclass=Meta):
    pass
print(WithMeta.added, type(WithMeta) is Meta, isinstance(WithMeta, type))

# __init_subclass__
class Registry:
    names = []
    def __init_subclass__(cls, label='?', **kw):
        super().__init_subclass__(**kw)
        Registry.names.append(cls.__name__ + ':' + label)
class R1(Registry, label='one'):
    pass
class R2(Registry):
    pass
print(Registry.names)

# class decorators
def add_hello(cls):
    cls.hello = lambda self: 'hello from ' + type(self).__name__
    return cls

@add_hello
class Greeter:
    pass
print(Greeter().hello())

# classes in the class namespace and nested classes
class Outer:
    class Inner:
        def where(self):
            return type(self).__qualname__
print(Outer.Inner().where(), Outer.Inner.__qualname__)
