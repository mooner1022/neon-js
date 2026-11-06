# range as CPython has it: its bounds any ints (past a Long a longrange_iterator), its attributes, index and count, == and hash by values.
def t(label, f):
    try:
        print(label, "->", f())
    except Exception as e:
        print(label, "!!", type(e).__name__, e)


def small():
    r = range(1, 20, 3)
    t("attrs", lambda: (r.start, r.stop, r.step, len(r), r[2], r[-1], r[1:4], r[::-1], 7 in r, 8 in r, r.index(7), r.count(7)))
    t("eq", lambda: (range(0) == range(2, 2), range(1, 5, 2) == range(1, 4, 2), range(3) == [0, 1, 2], hash(range(0)) == hash(range(5, 1)), hash(range(1, 5, 2)) == hash(range(1, 4, 2))))
    t("repr", lambda: (repr(range(3)), repr(range(1, 3)), repr(range(1, 9, 2)), str(range(-2, 0))))
    t("bool", lambda: (bool(range(0)), bool(range(1))))
    t("reversed", lambda: list(reversed(range(1, 10, 4))))
    t("index err", lambda: range(3).index(5))
    t("getitem err", lambda: range(3)[3])
    t("step 0", lambda: range(1, 2, 0))
    t("float", lambda: range(1.5))
    t("contains float", lambda: (2.0 in range(3), 'a' in range(3), True in range(3)))
    t("iter type", lambda: type(iter(range(3))).__name__)
    t("count obj", lambda: range(5).count(2.0))
    t("index", lambda: range(0, 10, 2).index(True))
    t("cmp", lambda: range(1) < range(2))


def big():
    b = 1 << 100
    r = range(b)
    t("big len", lambda: len(r))
    t("big attrs", lambda: (r.start, r.stop, r.step, r[0], r[-1], r[b - 1], (b - 1) in r, b in r, r.index(5), r.count(b - 2)))
    t("big repr", lambda: (repr(r), repr(range(b, b + 3)), repr(range(-b, b, b))))
    t("big iter", lambda: (type(iter(r)).__name__, list(range(b, b + 3)), list(range(b + 2, b - 1, -1)), type(iter(range(b, b + 3))).__name__))
    t("big slice", lambda: (r[1:3], r[::b // 2], r[b - 2:], len(r[::2]) if False else r[::2].step))
    t("big eq", lambda: (range(b, b) == range(0), range(b, b + 1) == range(b, b + 1, 7), hash(range(b, b + 1)) == hash(range(b, b + 1, 7))))
    t("big rev", lambda: list(reversed(range(b, b + 3))))
    t("big bool", lambda: (bool(range(b, b)), bool(range(b, b + 1))))
    t("big step", lambda: (list(range(0, 3 * b, b)), range(0, 3 * b, b)[2], len(range(0, 3 * b, b)), range(0, 3 * b, b).index(2 * b)))
    t("big getitem err", lambda: r[b])
    t("mixed", lambda: (list(range(-2, 2 ** 63 + 1, 2 ** 62)), len(range(-2 ** 63, 2 ** 63 - 1)) if False else range(2 ** 63 - 2, 2 ** 63 + 1)[2]))
    t("len max", lambda: len(range(2 ** 63 - 1)))
    t("len over", lambda: len(range(2 ** 63)))
    t("in neg", lambda: (-1 in range(b), 0 in range(b, 0, -1), b in range(b, 0, -1)))
    t("index big", lambda: range(b, 0, -3).index(b - 6))
    t("for", lambda: [x for x in range(2 ** 64 - 2, 2 ** 64 + 2)])


small()
big()
