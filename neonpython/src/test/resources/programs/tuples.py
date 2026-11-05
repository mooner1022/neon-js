a, b = 1, 2
a, b = b, a
print(a, b)

def divmod2(x, y):
    return x // y, x % y

q, r = divmod2(17, 5)
print(q, r)
print((1, 2) == (1, 2), (1, 2) < (1, 3), (1,), ())
[p, q] = [10, 20]
print(p + q)
pairs = [(1, "a"), (2, "b")]
for n, c in pairs:
    print(n, c)
