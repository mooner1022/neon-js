xs = []
for i in range(10):
    xs.append(i * 2)
print(xs)
print(len(xs), xs[3], xs[-1])
xs[0] = 100
print(sum(xs), min(xs), max(xs))
ys = [1, "two", 3.0, None, True, [4, 5], (6,)]
print(ys, len(ys))
print(ys.pop(), ys)
zs = list(range(5))
zs[1] += 10
print(zs, zs == [0, 11, 2, 3, 4], [1, 2] < [1, 3], [1, 2] + [3])
print(tuple(zs), list("abc"))
