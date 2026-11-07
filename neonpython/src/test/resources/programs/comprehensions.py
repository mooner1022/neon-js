# list and dict comprehensions: loops, conditions, nesting
print([x * 2 for x in range(5)], [x for x in range(10) if x % 3 == 0])
print([(x, y) for x in range(3) if x for y in range(x)], [[y for y in range(x)] for x in range(4)])
print({k: v for k, v in [(1, 'a'), (2, 'b'), (3, 'c')] if k != 2}, {x: x * x for x in range(4)})
print([c.upper() for c in 'abc'], {w: len(w) for w in ['one', 'three']}, [x for x in []])
print([a + b for a, b in [(1, 2), (3, 4)]], [first for first, *rest in [(1, 2, 3), (4, 5)]])
print([x for x in range(20) if x % 2 if x % 3], [i * j for i in range(1, 3) for j in range(1, 4) if i != j])

# the loop variable stays inside; names around are seen
x = 'outer x'
squares = [x * x for x in range(3)]
print(x, squares)

def scale(items, factor):
    return [i * factor for i in items]

def make_adders():
    return [lambda n, k=k: n + k for k in range(3)]

print(scale([1, 2], 10), [f(100) for f in make_adders()])

def late_binding():
    fs = [lambda: k for k in range(3)]
    return [f() for f in fs]

print(late_binding())

def nested_scopes():
    base = 10
    return [[base + i + j for j in range(2)] for i in range(2)]

print(nested_scopes())

# := binds in the scope around the comprehension
print([y := v * 2 for v in range(3)], y)
def walrus():
    total = 0
    sums = [total := total + v for v in range(4)]
    return sums, total

print(walrus())

# in a class body: the first iterable is the class's; the rest is not
class Table:
    size = 3
    rows = [r for r in range(size)]
    cells = {r: [c for c in range(2)] for r in range(2)}

print(Table.rows, Table.cells)

# the first iterable evaluated at once, errors where CPython raises them
def tracked():
    print('iterable made')
    return [1, 2]

lazy = (v for v in tracked())
print('before use')
print(list(lazy))
try:
    [v for v in 5]
except TypeError as e:
    print(e)
try:
    {k: 1 for k in [[]]}
except TypeError as e:
    print(e)
try:
    [1 / v for v in [1, 0]]
except ZeroDivisionError as e:
    print('in a comprehension:', e)

# comprehensions over generators, generators over comprehensions
def evens(n):
    for i in range(n):
        if i % 2 == 0:
            yield i

print([e * 10 for e in evens(7)], list(x + 1 for x in [y * 2 for y in range(3)]))
print(sorted({k: None for k in 'banana'}) if False else list({k: None for k in 'banana'}))
print(sum(len(str(n)) for n in range(100)), any(x > 3 for x in range(5)) if False else [x > 3 for x in range(5)][-1])
