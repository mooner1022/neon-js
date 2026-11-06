# A metaclass's own __call__ (enum's way, a singleton's), type.__call__ under it; a type's names as type's data descriptors give
# them; the builtin types' own __reduce__.
class M(type):
    def __call__(cls, *a):
        print('meta call', a)
        return super().__call__(*a)


class C(metaclass=M):
    def __init__(self, x):
        self.x = x


print(C(5).x, type.__call__(C, 6).x)


class Single(type):
    _inst = {}

    def __call__(cls, *a, **k):
        if cls not in cls._inst:
            cls._inst[cls] = super().__call__(*a, **k)
        return cls._inst[cls]


class S(metaclass=Single):
    pass


print(S() is S(), C.__call__)


class A:
    __name__ = 'x'


print(A.__name__)
A.__name__ = 'B'
print(A.__name__, A.__qualname__, A.__dict__.get('__name__'))
try:
    A.__name__ = 1
except TypeError as e:
    print(e)
A.__qualname__ = 'Q.B'
print(A.__qualname__, repr(A))
print(range(1, 5, 2).__reduce__(), slice(1, 2).__reduce__(), bytearray(b'x').__reduce_ex__(4), {1}.__reduce__(), frozenset({2}).__reduce__())


class S(set):
    pass


s = S([1])
s.a = 1
print(s.__reduce__())
