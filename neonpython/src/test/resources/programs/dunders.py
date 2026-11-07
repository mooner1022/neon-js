# arithmetic, reflected operators, NotImplemented
class V:
    def __init__(self, x, y):
        self.x = x
        self.y = y

    def __add__(self, o):
        if isinstance(o, V):
            return V(self.x + o.x, self.y + o.y)
        return NotImplemented

    def __sub__(self, o):
        return V(self.x - o.x, self.y - o.y)

    def __mul__(self, k):
        if isinstance(k, V):
            return self.x * k.x + self.y * k.y
        return V(self.x * k, self.y * k)

    def __rmul__(self, k):
        return V(self.x * k, self.y * k)

    def __matmul__(self, o):
        return self.x * o.y - self.y * o.x

    def __neg__(self):
        return V(-self.x, -self.y)

    def __pos__(self):
        return self

    def __abs__(self):
        return self.x * self.x + self.y * self.y

    def __eq__(self, o):
        return isinstance(o, V) and self.x == o.x and self.y == o.y

    def __hash__(self):
        return hash((self.x, self.y))

    def __repr__(self):
        return 'V(' + repr(self.x) + ', ' + repr(self.y) + ')'

a = V(1, 2)
b = V(3, 4)
print(a + b, a - b, a * b, a * 3, 2 * a, a @ b, -a, +a is a, abs(b))
print(a == V(1, 2), a != V(1, 2), a == b, a != b, a == 1, 1 == a, a != 'x')
print([a, b], (a,), {a: 'one'}[V(1, 2)], V(1, 2) in [a, b], V(9, 9) in [a, b])
s = {}
s[a] = 1
s[V(1, 2)] = 2
print(len(s), s[a])

# the subclass's reflected method first
class W(V):
    def __radd__(self, o):
        return 'W.__radd__'

print(a + W(0, 0), W(0, 0) + a)

class Num:
    def __init__(self, v):
        self.v = v

    def __radd__(self, o):
        return Num(o + self.v)

    def __rsub__(self, o):
        return Num(o - self.v)

    def __rtruediv__(self, o):
        return Num(o / self.v)

    def __rpow__(self, o):
        return Num(o ** self.v)

    def __rlshift__(self, o):
        return Num(o << self.v)

    def __rand__(self, o):
        return Num(o & self.v)

    def __repr__(self):
        return 'Num(' + repr(self.v) + ')'

print(10 + Num(1), 10 - Num(1), 1 / Num(4), 2 ** Num(10), 1 << Num(3), 6 & Num(3), 'x' + Num('y') if False else 0)
print(sum([Num(1), Num(2)], 0) if False else sum([1, 2], 0))

# augmented assignment: __iadd__, else __add__; lists in place
class Acc:
    def __init__(self):
        self.items = []

    def __iadd__(self, v):
        self.items.append(v)
        return self

acc = Acc()
before = acc
acc += 1
acc += 2
print(acc is before, acc.items)

c = V(1, 1)
c0 = c
c += V(1, 1)
print(c, c0, c is c0)
c *= 2
print(c)

l = [1]
l2 = l
l += [2, 3]
l += (4,)
l += range(5, 7)
print(l, l2, l is l2)
l *= 2
print(l2)
t = (1,)
t2 = t
t += (2,)
print(t, t2)
n = 5
n += 2
n -= 1
n *= 3
n //= 4
n **= 2
n %= 7
n <<= 3
n >>= 1
n &= 0xff
n |= 1
n ^= 3
print(n)
x = 1.5
x /= 2
print(x)

class Box:
    def __init__(self, v):
        self.v = v

b2 = Box(1)
b2.v += 10
b2.v *= 2
d = {'k': [1]}
d['k'] += [2]
d['n'] = 1
d['n'] += 1
print(b2.v, d)

# comparisons: __lt__ and reflected ones, ordering through sorted
class P:
    def __init__(self, n):
        self.n = n

    def __lt__(self, o):
        return self.n < o.n

    def __le__(self, o):
        return self.n <= o.n

    def __repr__(self):
        return 'P' + str(self.n)

ps = [P(3), P(1), P(2)]
print(P(1) < P(2), P(2) > P(1), P(1) <= P(1), P(3) >= P(4), min(ps), max(ps))

class OnlyEq:
    def __eq__(self, o):
        return 'truthy'

print(bool(OnlyEq() == 1), OnlyEq() != 1, OnlyEq.__hash__)

# the same object compares equal in containers, even where __eq__ says otherwise
class Never:
    def __eq__(self, o):
        return False

nv = Never()
print(nv == nv, [nv] == [nv], nv in [nv], (nv,) == (nv,))
