# Subclasses of int, float, complex and bytes: their instances are their values, with their class.


class MyInt(int):
    def double(self):
        return self * 2

    def __repr__(self):
        return "MyInt(%d)" % int(self)


class Named(int):
    def __new__(cls, value, name):
        self = super().__new__(cls, value)
        self.name = name
        return self

    def __add__(self, other):
        return Named(int(self) + int(other), self.name + "+")


class F(float):
    def __str__(self):
        return "F<%s>" % float.__repr__(self)


class B(bytes):
    pass


class C(complex):
    pass


class Up(int):
    def __add__(self, other):
        return Up(super().__add__(other) * 10)

    def __lt__(self, other):
        return "lt!"


class Idx:
    def __index__(self):
        return 2


class Conv:
    def __int__(self):
        return 7

    def __float__(self):
        return 2.5


def ints():
    a = MyInt(21)
    print(a, type(a).__name__, isinstance(a, int), a + 1, type(a + 1).__name__, a.double(), a == 21, hash(a) == hash(21))
    print({a: 'x'}[21], {21: 'y'}[a], int(a), float(a), str(a), -a, abs(MyInt(-3)), a // 4, a % 4, a ** 2, a << 1, a & 7)
    print(a < 22, sorted([MyInt(3), 1, MyInt(2)]), max(a, 5), [0] * MyInt(3), "abc"[MyInt(1)], range(MyInt(3)))
    print(MyInt("ff", 16), MyInt(), MyInt(3.9), bool(MyInt(0)), divmod(a, 5), round(a), format(a, "x"), "%d" % a, f"{a:04d}")
    a.attr = 5
    print(a.attr, a.__dict__)
    n = Named(3, "three")
    print(n, n.name, n + 1, (n + 1).name, 1 + n, type(1 + n).__name__, sum([n, 2]))
    print(isinstance(True, int), True + MyInt(1), MyInt(True), type(MyInt(True)).__name__)


def others():
    f = F(1.5)
    print(f, repr(f), f + 1, type(f + 1).__name__, isinstance(f, float), f * 2, f.is_integer(), F("2.5"), F(), f.hex())
    print(f == 1.5, hash(f) == hash(1.5), {1.5: 'z'}[f], int(f), round(f), f.real, f > 1)
    print(B(b"ab") + b"c", type(B(b"ab")).__name__, len(B(b"xyz")), C(1, 2) + 1, type(C(1, 2)).__name__, C(1, 2).imag)
    try:
        class NoBool(bool):
            pass
    except TypeError as e:
        print("TypeError", e)


def methods():
    u = Up(2)
    print(u + 3, type(u + 3).__name__, int.__add__(u, 3), int.__add__(4, 5), int.__lt__(1, 2), int.__eq__(1, 'a'), u < 1, 3 > u)
    print(float.__mul__(2.0, 3), int.__neg__(MyInt(4)), int.__index__(True), MyInt.__mro__, isinstance(MyInt(1), MyInt))
    print([10, 20, 30][MyInt(1)], [10, 20, 30][MyInt(0):MyInt(2)], "hello"[MyInt(1):MyInt(3)], MyInt(5) in [5], 5 in [MyInt(5)])


def conversions():
    print([1, 2, 3, 4][Idx()], "abcd"[Idx():], [0] * Idx(), hex(Idx()), range(Idx()), int(Conv()), float(Conv()), int(Idx()), float(Idx()))
    try:
        int(object())
    except TypeError as e:
        print("TypeError", e)
    try:
        float([])
    except TypeError as e:
        print("TypeError", e)
    print(float.fromhex('0x1.8p+0'), float.fromhex('-0X1P-3'), (0.1).hex(), (5e-324).hex(), F.fromhex('0x1p1'), type(F.fromhex('0x1p1')).__name__)


def loops():
    # (never speculated on as ints or floats: the interpreter saw other values there)
    t = MyInt(0)
    for i in range(3000):
        t = t + MyInt(1)
    print(t, type(t).__name__)
    s = 0.0
    for i in range(3000):
        s += F(0.5)
    print(s, type(s).__name__)


ints()
others()
methods()
conversions()
loops()
