# starred targets
a, *b = [1, 2, 3, 4]
print(a, b)
*a, b = (1, 2, 3)
print(a, b)
a, *b, c = range(5)
print(a, b, c)
a, *b, c = 'xy'
print(a, b, c)
(a, b), *c = [(1, 2), 3, 4]
print(a, b, c)
first, *_ = [9]
print(first, _)

# unpacking in displays
x = [1, 2]
y = (3, 4)
print([*x, *y, 5])
print((*x, 0, *y))
print([*range(3), *'ab'])
d = {'a': 1, 'b': 2}
print({**d, 'c': 3, **{'a': 10}})
print({'z': 0, **d})

# assignment expressions
data = [1, 2, 3, 4, 5]
if (n := len(data)) > 3:
    print('long', n)
total = 0
i = 0
while (i := i + 1) <= 3:
    total += i
print(total, i)
print((w := 7) * w)

# del
v = [1, 2, 3, 4]
del v[0]
print(v)
del v[-1]
print(v)
m = {'k': 1, 'j': 2}
del m['k']
print(m)
g = 5
del g
def f():
    loc = 1
    del loc
    return 'deleted'
print(f())
p, q = 1, 2
del p, q

# assert
assert True
assert 1 + 1 == 2, 'math'
print('asserts passed')

# chained assignment and swaps
a = b = c = 0
a, b = 1, 2
a, b = b, a
print(a, b, c)
