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
