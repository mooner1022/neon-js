# slicing lists, tuples, strs (by code point) and ranges
xs = [0, 1, 2, 3, 4, 5]
print(xs[1:4], xs[:2], xs[3:], xs[::2], xs[::-1], xs[-2:], xs[:-2], xs[4:1:-1], xs[100:], xs[-100:2], xs[::100])
print((1, 2, 3)[1:], (1, 2, 3)[::-1], 'héllo'[1:3], 'héllo'[::-1], 'abcdef'[1:5:2], 'a😀b'[1:2], 'a😀b'[::-1], ''[:])
print(range(10)[2:8:2], range(10)[::-1], range(0, 20, 3)[1:4], list(range(10)[7:2:-2]))
t = (1, 2, 3)
print(t[:] is t, t[0:3] is t, t[1:2])

# assigning and deleting slices
a = [0, 1, 2, 3]
a[1:3] = range(5)
print(a)
a[::2] = 'abcd'
print(a)
del a[1::2]
print(a)
a[1:1] = [9, 9]
print(a)
a[2:] = []
print(a)
a[:0] = (7,)
print(a)
a[:] = a
print(a)
b = list(range(10))
del b[::3]
del b[-2:]
print(b)
b[1:3] = b
print(b)

# slice objects
s = slice(1, 5, 2)
print(s, s.start, s.stop, s.step, slice(3), slice(None, 5, -1), slice(3).indices(10), slice(-3, None, 2).indices(5))
print(xs[s], slice(1, 2) == slice(1, 2), slice(1, 2) == slice(1, 3), {slice(1, 2): 'hashable'}[slice(1, 2)], slice(1, 2, None) == slice(1, 2))

class Grid:
    def __getitem__(self, key):
        return key

    def __setitem__(self, key, v):
        print('set', key, v)

    def __delitem__(self, key):
        print('del', key)

g = Grid()
print(g[1:2], g[::3], g[1:2, 3], g[...], g[:, 0], g[1:2:3, ...])
g[1:3] = 'x'
del g[::2]

# augmented assignment through a slice key
c = [1, 2, 3, 4]
c[1:3] += [9]
print(c)
d = {'a': 1}
print(d | {'b': 2}, {'b': 2} | d)
d |= {'c': 3}
d |= [('e', 5)]
print(d)
