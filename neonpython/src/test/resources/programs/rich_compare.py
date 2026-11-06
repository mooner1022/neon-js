# Rich comparisons: what __lt__ and the others return is the comparison's value.


class Vec:
    def __init__(self, *xs):
        self.xs = list(xs)

    def _cmp(self, other, f):
        ys = other.xs if isinstance(other, Vec) else [other] * len(self.xs)
        return Vec(*[f(x, y) for x, y in zip(self.xs, ys)])

    def __lt__(self, other):
        return self._cmp(other, lambda x, y: x < y)

    def __le__(self, other):
        return self._cmp(other, lambda x, y: x <= y)

    def __gt__(self, other):
        return self._cmp(other, lambda x, y: x > y)

    def __ge__(self, other):
        return self._cmp(other, lambda x, y: x >= y)

    def __eq__(self, other):
        return self._cmp(other, lambda x, y: x == y)

    def __bool__(self):
        raise ValueError("the truth value of a Vec is ambiguous")

    def __repr__(self):
        return "Vec%r" % (tuple(self.xs),)


def values():
    a = Vec(1, 5, 3)
    b = Vec(2, 2, 3)
    print(a < b, a <= b, a > b, a >= b, a == b)
    print(a < 3, 3 > a, 2 <= a)
    try:
        # (object's __ne__ inverts __eq__'s result, taking its truth)
        print(a != b)
    except ValueError as e:
        print("!=: ValueError", e)
    r = [a < b, (a == b)]
    print(r)
    try:
        if a < b:
            pass
    except ValueError as e:
        print("ValueError", e)
    try:
        print(a < b < Vec(9, 9, 9))
    except ValueError as e:
        print("chained: ValueError", e)
    try:
        print(not (a < b))
    except ValueError as e:
        print("not: ValueError", e)


class Tag:
    def __init__(self, t):
        self.t = t

    def __eq__(self, other):
        return "eq(%s, %s)" % (self.t, getattr(other, "t", other))

    def __lt__(self, other):
        return NotImplemented

    def __gt__(self, other):
        return "gt(%s, %s)" % (self.t, getattr(other, "t", other))


class SubTag(Tag):
    def __gt__(self, other):
        return "sub gt(%s, %s)" % (self.t, getattr(other, "t", other))


def resolution():
    x, y = Tag("x"), Tag("y")
    print(x == y, x != y, x == 1, 1 == x)
    print(x < y, y > x)
    print(x < SubTag("s"))
    print(x == x, [x] == [x], x in [y], (x, 1) == (x, 1))
    try:
        print(x <= y)
    except TypeError as e:
        print("TypeError", e)

    class Plain:
        pass
    p = Plain()
    print(p == p, p != p, p == Plain(), p != Plain())

    class NotEq:
        def __eq__(self, other):
            return NotImplemented
    print(NotEq() == 1, NotEq() != 1)

    class OnlyNe:
        def __ne__(self, other):
            return "ne!"
    print(OnlyNe() != 1, OnlyNe() == 1)


def builtins_still_bool():
    xs = [Vec(1), Vec(2)]
    print(1 < 2, 'a' == 'a', [1] < [2], (1, 2) != (1, 3), {1} <= {1, 2}, 1.5 >= 2)
    print(sorted([3, 1, 2]), max(4, 9), 2 in [1, 2], 3 not in [1, 2], None is None)
    try:
        sorted(xs)
    except ValueError as e:
        print("sorted: ValueError", e)
    t = 0
    for i in range(1000):
        t += (i < 500) + (i == 3)
    print(t)


values()
resolution()
builtins_still_bool()
