# parameters: defaults, keyword arguments, *args, **kwargs, keyword-only and positional-only ones
def f(a, b=2, *args, c, d=4, **kw):
    return [a, b, args, c, d, kw]

print(f(1, c=3))
print(f(1, 5, 6, 7, c=8, e=9))
print(f(c=1, a=2))
print(f(*[1, 2, 3], **{'c': 4, 'z': 5}))

def g(a, b, /, c=0, *, d=0):
    return a + b + c + d

print(g(1, 2), g(1, 2, 3), g(1, 2, c=3, d=4))

def h(*args, **kwargs):
    return args, kwargs

print(h())
print(h(1, 2, x=3))
print(h(*(1, 2), *[3], 4, **{'a': 1}, b=2, **{'c': 3}))

# defaults are evaluated once, when the function is made
n = 10
def k(x=n):
    return x
n = 20
print(k(), k(1))

def acc(item, into=[]):
    into.append(item)
    return into

print(acc(1), acc(2))

# lambdas
sq = lambda x: x * x
add = lambda x, y=1, *r, **kw: x + y + len(r) + len(kw)
print(sq(7), add(1), add(1, 2, 3, 4, z=5))
print((lambda: 'no args')())

# keyword arguments to print
print(1, 2, 3, sep='-', end='!\n')
print('a', 'b', sep='')
print(*range(5))

# recursion with keywords
def fact(n, *, acc=1):
    return acc if n <= 1 else fact(n - 1, acc=acc * n)

print(fact(20))

# defaults set later, mangled keyword-only defaults, staticmethod and classmethod as their functions
def func(x):
    "doc"
    return x


for w in (staticmethod, classmethod):
    s = w(func)
    print(s.__func__ is func, s.__wrapped__ is func, repr(s) == "<%s(%r)>" % (w.__name__, func))
    print([getattr(s, a) is getattr(func, a) for a in ('__module__', '__qualname__', '__name__', '__doc__', '__annotations__')])
    try:
        print(s(1))
    except TypeError as e:
        print("TypeError", e)
print(func.__name__ is func.__name__, func.__qualname__ is func.__qualname__)


def foo(p1, p2=0, *, k1, k2=0):
    return p1 + p2 + k1 + k2


print(foo.__kwdefaults__, foo.__defaults__)
foo.__kwdefaults__ = {"k1": 0}
try:
    foo(1, k1=10)
except TypeError as e:
    print("TypeError", e)
print(foo(1, k2=5))
foo.__defaults__ = (7,)
print(foo(1, k1=1, k2=1), foo.__defaults__)
foo.__defaults__ = None
try:
    foo(1, k1=1, k2=1)
except TypeError as e:
    print("TypeError", e)
for bad in (1, [1]):
    try:
        foo.__defaults__ = bad
    except TypeError as e:
        print("TypeError", e)
    try:
        foo.__kwdefaults__ = bad
    except TypeError as e:
        print("TypeError", e)
foo.__kwdefaults__ = None
print(foo.__kwdefaults__)
try:
    del foo.__defaults__
    print(foo.__defaults__)
    del foo.__kwdefaults__
    print(foo.__kwdefaults__)
except Exception as e:
    print(type(e).__name__, e)


class X:
    def f(self, *, __a=42):
        return __a

    def g(self, *, __b):
        return __b


print(X().f(), X.f.__kwdefaults__, X().g(_X__b=3))
