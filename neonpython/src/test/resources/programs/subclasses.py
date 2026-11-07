# a list's subclass: its methods, attributes of its own, super().__init__
class Stack(list):
    def __init__(self, name, *items):
        super().__init__(items)
        self.name = name

    def push(self, v):
        self.append(v)
        return self

    def peek(self):
        return self[-1]

s = Stack('s', 1, 2)
s.push(3).push(4)
print(s, len(s), s.peek(), s.name, type(s).__name__, isinstance(s, list), isinstance(s, Stack))
print(s == [1, 2, 3, 4], [1, 2, 3, 4] == s, s[0], 3 in s, s.pop(), s, bool(Stack('e')), list(s), [*s])
t = s + [9]
print(t, type(t).__name__, type(s * 2).__name__, s.__dict__, Stack.__mro__)
s += [5]
print(s, type(s).__name__, s.name)
a, b, c, d = s
print(a, b, c, d)
for x in s:
    print('item', x)

# overridden special methods win over the list's
class Wrapped(list):
    def __getitem__(self, i):
        return 'item ' + repr(list.__getitem__(self, i))

    def __len__(self):
        return 100

    def __repr__(self):
        return 'Wrapped(' + super().__repr__() + ')'

    def __contains__(self, v):
        return v == 'anything'

    def __iter__(self):
        return iter(['a', 'b'])

    def __add__(self, o):
        return 'added'

    def __radd__(self, o):
        return 'radded'

w = Wrapped([10, 20])
print(w[0], len(w), w, 'anything' in w, 10 in w, list(w), w + [1], [1] + w, str(w), [w])

class Sorted(list):
    def __setitem__(self, i, v):
        super().__setitem__(i, v * 10)

    def __delitem__(self, i):
        print('deleting', i)
        super().__delitem__(i)

so = Sorted([1, 2, 3])
so[0] = 5
del so[1]
print(so)

# a dict's subclass: __missing__, __setitem__ through super()
class Counter(dict):
    def __missing__(self, k):
        return 0

    def add(self, k):
        self[k] = self[k] + 1

cnt = Counter()
for w2 in ['a', 'b', 'a', 'c', 'a']:
    cnt.add(w2)
print(cnt, cnt['a'], cnt['zzz'], 'zzz' in cnt, len(cnt), type(cnt).__name__, cnt.get('b'), sorted(cnt.keys()) if False else list(cnt.keys()))

class Lower(dict):
    def __setitem__(self, k, v):
        super().__setitem__(k.lower(), v)

    def __getitem__(self, k):
        return super().__getitem__(k.lower())

lo = Lower(x=1)
lo['ABC'] = 2
print(lo, lo['abc'], lo['Abc'], lo['X'] if 'X' in lo else 'no X', Lower({'Q': 1}), dict(lo))

class Defaults(dict):
    def __init__(self, **kw):
        super().__init__(a=1)
        self.update_from(kw)

    def update_from(self, kw):
        for k in kw:
            self[k] = kw[k]

print(Defaults(b=2, c=3), Defaults() == {'a': 1})

# a str's subclass: __new__, its methods give plain strs
class Name(str):
    def __new__(cls, first, last):
        return super().__new__(cls, first + ' ' + last)

    def initials(self):
        return self[0] + '.'

n = Name('Ada', 'Lovelace')
print(n, repr(n), len(n), n.upper(), type(n.upper()).__name__, type(n + '!').__name__, n.initials(), n == 'Ada Lovelace')
print({n: 1}['Ada Lovelace'], hash(n) == hash('Ada Lovelace'), n < 'B', isinstance(n, str), str(n), type(str(n)).__name__, n * 2)

class Shout(str):
    def __str__(self):
        return self.upper() + '!'

sh = Shout('hey')
print(sh, str(sh), repr(sh), [sh], sh + 'x')

# a tuple's subclass: __new__ with fields, read by properties
class Point(tuple):
    def __new__(cls, x, y):
        return super().__new__(cls, (x, y))

    @property
    def x(self):
        return self[0]

    @property
    def y(self):
        return self[1]

    def __add__(self, o):
        return Point(self.x + o.x, self.y + o.y)

    def __repr__(self):
        return 'Point(' + repr(self.x) + ', ' + repr(self.y) + ')'

p = Point(1, 2)
q = p + Point(3, 4)
print(p, q, q.x, q.y, len(q), q == (4, 6), type(q).__name__, tuple(q), {p: 'p'}[(1, 2)], x0 if False else 0)
px, py = p
print(px, py, Point.__mro__)

class Pair(tuple):
    pass

pr = Pair([1, 2])
pr.extra = 'e'
print(pr, pr.extra, pr + (3,), type(pr + (3,)).__name__, Pair(), Pair() == ())

# a subclass of a subclass, and a class mixed in
class Named:
    def describe(self):
        return type(self).__name__ + ' of ' + str(len(self))

class NamedList(Named, list):
    pass

class Deeper(Stack):
    pass

nl = NamedList([1, 2, 3])
print(nl.describe(), nl, NamedList.__mro__)
dp = Deeper('d', 7)
print(dp, dp.name, dp.peek(), isinstance(dp, Stack), Deeper.__mro__)
print(type(list.__new__(Stack)).__name__, list.__new__(Stack), tuple.__new__(Pair, [1]), str.__new__(Name, 'x'))
