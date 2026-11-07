# str, repr, bool, len
class S:
    def __str__(self):
        return 'str of S'

    def __repr__(self):
        return 'repr of S'

class R:
    def __repr__(self):
        return 'only repr'

print(S(), str(S()), repr(S()), [S()], R(), str(R()))

class Empty:
    def __len__(self):
        return 0

class Full:
    def __len__(self):
        return 3

class No:
    def __bool__(self):
        return False

print(len(Full()), bool(Empty()), bool(Full()), bool(No()), not No(), 'yes' if Full() else 'no')
if Empty() or No():
    print('wrong')
else:
    print('falsy')
z = 0
while Full() and z < 2:
    z += 1
print(z)

# items: __getitem__, __setitem__, __delitem__, __contains__
class Grid:
    def __init__(self):
        self.cells = {}

    def __getitem__(self, k):
        return self.cells.get(k, 0)

    def __setitem__(self, k, v):
        self.cells[k] = v

    def __delitem__(self, k):
        print('del', k)

    def __contains__(self, k):
        return k in self.cells

g = Grid()
g[1, 2] = 5
g['a'] = 1
g['a'] += 2
print(g[1, 2], g[0, 0], g['a'], (1, 2) in g, (0, 0) in g, (0, 0) not in g)
del g['a']

# iteration: __iter__ giving a builtin iterator, the __getitem__ protocol, membership by iteration
class Bag:
    def __init__(self, *items):
        self.items = list(items)

    def __iter__(self):
        return iter(self.items)

    def __len__(self):
        return len(self.items)

bag = Bag(3, 1, 2)
for v in bag:
    print('item', v)
print(list(bag), sorted(bag) if False else 0, sum(bag), min(bag), 2 in bag, 5 in bag, len(bag))
p, q, r = bag
print(p, q, r, [*bag], tuple(bag))

class Squares:
    def __getitem__(self, i):
        if i >= 4:
            return [][i]
        return i * i

print(list(Squares()), 9 in Squares(), 5 in Squares())

class Countdown:
    def __init__(self, n):
        self.n = n

    def __iter__(self):
        return self

    def __next__(self):
        self.n -= 1
        return self.n

cd = Countdown(3)
print(iter(cd) is cd, next(cd), next(cd), next(iter([7, 8])), next(iter([]), 'default'))
it = iter([1, 2])
print(next(it), next(it), next(it, 'end'))

# hash: the builtin's values
print(hash(1), hash(-1), hash(True), hash(2 ** 61), hash(-(2 ** 61) - 5), hash((1, 2)) == hash((1, 2)))

class H:
    def __hash__(self):
        return 42

print(hash(H()), {H(): 1} != {H(): 1})

# unary ~ and bitwise operators of a class
class Bits:
    def __init__(self, v):
        self.v = v

    def __invert__(self):
        return Bits(~self.v)

    def __and__(self, o):
        return Bits(self.v & o.v)

    def __or__(self, o):
        return Bits(self.v | o.v)

    def __xor__(self, o):
        return Bits(self.v ^ o.v)

    def __lshift__(self, n):
        return Bits(self.v << n)

    def __rshift__(self, n):
        return Bits(self.v >> n)

    def __floordiv__(self, n):
        return Bits(self.v // n)

    def __mod__(self, n):
        return Bits(self.v % n)

    def __pow__(self, n):
        return Bits(self.v ** n)

    def __truediv__(self, n):
        return self.v / n

print((~Bits(5)).v, (Bits(12) & Bits(10)).v, (Bits(12) | Bits(10)).v, (Bits(12) ^ Bits(10)).v, (Bits(1) << 4).v,
      (Bits(16) >> 2).v, (Bits(17) // 5).v, (Bits(17) % 5).v, (Bits(3) ** 3).v, Bits(3) / 2)

# object's own methods through super() and object
class Base:
    def __init__(self):
        object.__setattr__(self, 'made', True)

    def __eq__(self, o):
        return super().__eq__(o)

class Logged:
    def __setattr__(self, name, v):
        print('set', name, v)
        super().__setattr__(name, v)

    def __getattribute__(self, name):
        if name == 'secret':
            return 'hidden'
        return object.__getattribute__(self, name)

    def __getattr__(self, name):
        return 'missing ' + name

    def __delattr__(self, name):
        print('del attr', name)
        object.__delattr__(self, name)

bs = Base()
print(bs.made, bs == bs, bs == Base(), bs != Base(), object.__eq__(bs, 1), Base.__ne__(bs, bs))
lg = Logged()
lg.x = 1
print(lg.x, lg.secret, lg.nothing, hasattr(lg, 'x'), getattr(lg, 'y', 'dflt'))
del lg.x
print(lg.x)
print(type(object.__repr__(bs)).__name__, object.__repr__(bs) == repr(bs), str(bs) == repr(bs))
