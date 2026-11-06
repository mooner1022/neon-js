# math (written in Kotlin as CPython writes it in C): its values, special values, ints of any size, CPython's errors.
import math


def show(f, *args, **kw):
    try:
        return repr(f(*args, **kw))
    except Exception as e:
        return '%s: %s' % (type(e).__name__, e)


def approx(f, *args):
    # (what the C library gives, its last digits differing from one platform's to another's: 12 of them)
    try:
        return '%.12g' % f(*args)
    except Exception as e:
        return '%s: %s' % (type(e).__name__, e)


class F(float):
    def __float__(self): return 2.0
class I(int):
    def __float__(self): return 2.5
class Real:
    def __float__(self): return 6.25
class Index:
    def __index__(self): return 9
class Both:
    def __float__(self): return 1.5
    def __index__(self): return 9
class BadFloat:
    def __float__(self): return 'x'
class Rounds:
    def __floor__(self): return 'floor'
    def __ceil__(self): return 'ceil'
    def __trunc__(self): return 'trunc'
class FF(float):
    def __floor__(self): return super().__floor__() + 100
    def __trunc__(self): return super().__trunc__() + 100
class II(int):
    def __ceil__(self): return super().__ceil__() + 1000
class Meta(type):
    def __floor__(cls): return 'meta'
class WithMeta(metaclass=Meta):
    pass


def names():
    print(math.pi, math.e, math.tau, math.inf, -math.inf, math.nan, math.copysign(1.0, math.nan), math.copysign(1.0, -math.nan))
    public = [n for n in dir(math) if not n.startswith('_')]
    print(len(public), public)
    print(math.__name__, repr(math.__doc__), repr(math.sqrt), math.floor.__name__, type(math.sqrt).__name__)


def conversions():
    for v in [16.0, 16, True, F(16.0), I(16), Real(), Index(), Both(), BadFloat(), 'x', None, 1j, [1], 2 ** 1100, -2.25, -0.0, math.nan, math.inf, -math.inf]:
        print(show(math.sqrt, v), end=' | ')
    print()
    print(show(math.sqrt, -1), show(math.sqrt, -1e16), show(math.sqrt, -1e-5), show(math.fabs, -2 ** 60), show(math.isnan, Index()), show(math.isinf, 'x'), show(math.isfinite, 2 ** 1024))


def rounding():
    for v in [2.5, -2.5, 3, -3, True, 2 ** 100, -2 ** 100 - 1, I(7), F(7.5), F(-7.5), FF(3.5), FF(-3.5), II(4), Real(), Index(), Rounds(), WithMeta, 1e300, -0.0, math.nan, math.inf, -math.inf, 'x', None]:
        print(show(math.floor, v), show(math.ceil, v), show(math.trunc, v), end=' | ')
    print()
    print(type(math.floor(I(3))).__name__, type(math.trunc(True)).__name__, type(math.ceil(F(2.5))).__name__, type(math.floor(II(4))).__name__)
    print(int.__floor__(5), int.__ceil__(-5), int.__trunc__(7), float.__floor__(2.5), float.__ceil__(2.5), float.__trunc__(-2.5), (-3.7).__trunc__(), True.__floor__())


def libm():
    for name in ['acos', 'asin', 'atan', 'acosh', 'asinh', 'atanh', 'cos', 'sin', 'tan', 'cosh', 'sinh', 'tanh', 'exp', 'exp2', 'expm1', 'log1p', 'cbrt', 'erf', 'erfc', 'gamma', 'lgamma', 'log', 'log2', 'log10', 'sqrt']:
        f = getattr(math, name)
        print(name, [approx(f, v) for v in [0.5, -0.5, 1.5, 2, -3.25, 10.0, 100.0]])


def special_values():
    for name in ['acos', 'asin', 'atan', 'acosh', 'asinh', 'atanh', 'cos', 'sin', 'tan', 'cosh', 'sinh', 'tanh', 'exp', 'exp2', 'expm1', 'log1p', 'cbrt', 'erf', 'erfc', 'gamma', 'lgamma', 'log', 'log2', 'log10', 'sqrt', 'fabs', 'degrees', 'radians', 'ulp']:
        f = getattr(math, name)
        print(name, [show(f, v) for v in [0.0, -0.0, math.inf, -math.inf, math.nan]])
    print(show(math.acos, 2), show(math.asin, -2), show(math.acosh, 0.5), show(math.atanh, 1), show(math.atanh, -1.5), show(math.cos, math.inf), show(math.tan, -math.inf), show(math.log1p, -1), show(math.log1p, -2.5))
    print(show(math.gamma, 0), show(math.gamma, -0.0), show(math.gamma, -2), show(math.gamma, 172), show(math.gamma, 1e-320), show(math.gamma, -200.5), show(math.gamma, 23), approx(math.gamma, 24), show(math.lgamma, -3), show(math.lgamma, 1e308))
    print(show(math.exp, 1000), show(math.exp, -1000), show(math.exp2, 1024), show(math.exp2, -1074), show(math.exp2, 10), show(math.expm1, 1000), show(math.expm1, -1000), show(math.cosh, 1000), show(math.sinh, -1000), show(math.sqrt, 2 ** 1022))


def two_floats():
    vals = [0.0, -0.0, 1.0, -2.5, 3, math.inf, -math.inf, math.nan]
    for name in ['copysign', 'remainder', 'fmod']:
        f = getattr(math, name)
        print(name, [show(f, x, y) for x in vals for y in vals])
    print([approx(math.pow, x, y) for x in vals for y in vals])
    print([approx(math.atan2, y, x) for y in vals for x in vals])
    print(math.atan2(0.0, -0.0), math.atan2(-0.0, -0.0), math.atan2(-0.0, 0.0), approx(math.pow, 2, 0.5), approx(math.log, 8, 2), show(math.log, 10, 1), show(math.log, 0, 10), show(math.log, 1.0, 1.0))
    print(math.remainder(7, 2), math.remainder(-5, 2), math.remainder(1e308, 3.0), math.fmod(-4.0, 2.0), math.fmod(1e308, 1e-308), math.fmod(-5.5, 2.0), show(math.pow, -8, 1 / 3), show(math.pow, 0, -1), show(math.pow, -0.0, -3), show(math.pow, 10, 400), math.pow(10, -400))


def float_bits():
    for x in [0.0, -0.0, 1.0, -1.5, 3.0, 5e-324, 2.2250738585072014e-308, 1e-310, 1.7976931348623157e308, math.inf, -math.inf, math.nan, 123.456, True, 2 ** 53]:
        print(show(math.frexp, x), show(math.modf, x), show(math.ulp, x), show(math.ldexp, x, -1075), show(math.ldexp, x, 1024), show(math.ldexp, x, 2 ** 100), show(math.ldexp, x, -2 ** 100))
    print(show(math.ldexp, 1.0, 1.5), show(math.ldexp, 1.0, Index()), show(math.ldexp, 3.0, -1075), show(math.ldexp, 2.5, -1074), show(math.ldexp, 1.5, True), show(math.ldexp, 1.5, I(3)))
    print(math.fma(0.1, 10.0, -1.0), math.fma(1.0000000000000002, 1.0000000000000002, -1.0), math.fma(3.0, 1 / 3, -1.0), math.fma(-0.0, 1.0, -0.0), math.fma(-1e-300, 1e-300, 0.0), math.fma(5e-324, 0.5, 0.0), math.fma(1e300, 1e8, -1e308))
    print(show(math.fma, 1e308, 10.0, 0.0), show(math.fma, math.inf, 0.0, 1.0), show(math.fma, math.inf, 1.0, -math.inf), show(math.fma, math.nan, 0.0, 1.0), show(math.fma, 1, 2))
    print(math.degrees(math.pi), math.radians(180), math.degrees(1), math.radians(1), math.degrees(-0.0))


def neighbours():
    for x, y, s in [(1.0, 2.0, None), (1.0, 0.0, None), (0.0, -1.0, None), (-0.0, 1.0, None), (0.0, -0.0, None), (1.0, math.nan, None), (1.7976931348623157e308, math.inf, None), (1.0, 2.0, 0), (1.0, 2.0, 10), (1.0, 0.0, 10),
                    (5e-324, -1.0, 1), (5e-324, -1.0, 2), (5e-324, -1.0, 3), (-5e-324, 1.0, 5), (1.0, -1.0, 2 ** 62), (1.0, -1.0, 2 ** 100), (1.0, 2.0, -1), (1.0, 2.0, 1.0), (1.0, 2.0, Index()), (0.0, -0.0, 1), (math.inf, -math.inf, 3), (1.5, 1.0, 2 ** 52)]:
        print(show(math.nextafter, x, y) if s is None else show(math.nextafter, x, y, steps=s), end=' ')
    print()
    for a, b, kw in [(1.0, 1.0000000001, {}), (1.0, 1.00000001, {}), (0.0, 1e-10, {}), (0.0, 1e-10, {'abs_tol': 1e-9}), (math.inf, math.inf, {}), (math.inf, -math.inf, {}), (math.nan, math.nan, {}), (1, 2, {'rel_tol': 0.5}),
                     (1, 2, {'rel_tol': -0.1}), ('a', 1, {}), (1, 1, {'rel_tol': 'x'}), (1, 1, {'x': 1}), (1, 1, {'rel_tl': 1}), (1, 1, {'a': 1}), (1, 1, {'REL_TOL': 1})]:
        print(show(math.isclose, a, b, **kw), end=' ')
    print()


def big_logs():
    for v in [1, 8, 2 ** 53 + 1, 2 ** 1023, 2 ** 1024, 2 ** 5000, True, I(64), 0.125, 2.0 ** -1074]:
        print(show(math.log2, v), approx(math.log, v), approx(math.log10, v), end=' | ')
    print(approx(math.log2, Index()), approx(math.log, Index()), approx(math.log10, Real()))
    for v in [10 ** 400, 2 ** 2000 - 1, 3 ** 2000]:
        print(approx(math.log, v), approx(math.log10, v), approx(math.log2, v), approx(math.log, v, 2 ** 300))
    for v in [0, -1, -2 ** 2000, False, 0.0, -0.0, -1.5, -math.inf, 'x']:
        print(show(math.log, v), show(math.log2, v), show(math.log10, v), end=' | ')
    print()
    print(show(math.log), show(math.log, 1, 2, 3), show(math.log2, 2, 3), show(math.log10, x=1))


def integers():
    for args in [(), (0,), (-5,), (12, 18), (-12, 18), (0, 0), (2 ** 100, 2 ** 60), (-2 ** 63, 2 ** 63), (6, 10, 15), (1, 'x'), ('x', 1), (1.0,), (2 ** 200 * 3, 2 ** 150 * 9), (True, 4), (I(12), 8), (Index(), 15), (4, 6, 1.5)]:
        print(show(math.gcd, *args), show(math.lcm, *args), end=' | ')
    print()
    print([show(math.isqrt, v) for v in [0, 1, 15, 16, 2 ** 32 - 1, 2 ** 62, 2 ** 64 - 1, 2 ** 64, 10 ** 30, 2 ** 200 - 1, -1, 1.0, True, Index(), 'x']])
    print(math.isqrt(10 ** 1000) == 10 ** 500, math.isqrt(10 ** 101 - 1), all(math.isqrt(n) ** 2 <= n < (math.isqrt(n) + 1) ** 2 for n in range(0, 10 ** 6, 997)))
    print([show(math.factorial, v) for v in [0, 1, 12, 13, 20, 21, 25, -1, -2 ** 70, 1.0, True, Index(), 'x']])
    print(math.factorial(1000) % (10 ** 40 + 7), math.factorial(3000).bit_length(), math.factorial(1234) == math.prod(range(1, 1235)))


def combinatorics():
    for n, k in [(5, 2), (5, 0), (5, 5), (5, 6), (0, 0), (52, 5), (100, 50), (127, 30), (128, 64), (67, 33), (2 ** 32, 2), (2 ** 32 + 1, 2), (3329022, 3), (3329023, 3), (10 ** 9, 7), (10 ** 20, 3), (2 ** 64, 5),
                 (2 ** 70, 2 ** 70 - 1), (-1, 2), (2, -1), (1.0, 1), (1, 1.0), (5, I(2)), (Index(), 3), (True, True), (300, 150)]:
        print(show(math.comb, n, k), show(math.perm, n, k), end=' | ')
    print()
    print(show(math.perm, 5), show(math.perm, 5, None), show(math.perm, -5), show(math.perm, 2 ** 64, 2 ** 64), show(math.comb, 2 ** 64, 2 ** 63), show(math.comb, 1), show(math.perm, 1, 2, 3))
    print(sum(math.comb(n, k) for n in range(130) for k in range(n + 1)) % (10 ** 18 + 9), sum(math.perm(n, k) for n in range(130) for k in range(n + 1)) % (10 ** 18 + 9))
    print(all(math.comb(n, k) == math.factorial(n) // (math.factorial(k) * math.factorial(n - k)) for n in range(0, 300, 7) for k in range(0, n + 1, 3)))
    print(math.comb(10 ** 5, 500) % 1000003, math.perm(10 ** 4, 300) % 1000003, math.comb(2 ** 40, 3), math.perm(2 ** 40, 3))


def sums():
    for xs in [[], [-0.0], [1e308, 1e308, -1e308], [1e308, -1e308, 1e308], [math.inf, -math.inf], [math.inf, math.inf], [math.nan, 1.0], [0.1] * 10, [1e-16, 1, 1e16], [1, 1e100, 1, -1e100], [3, 4.5, True], [2 ** 1100], ['a'], [0.1, 0.2, 0.3, -0.6]]:
        print(show(math.fsum, xs), end=' ')
    print(show(math.fsum, 1), math.fsum(x / 7 for x in range(1000)), sum(x / 7 for x in range(1000)))
    print(math.hypot(), math.hypot(-3), math.hypot(3, 4), math.hypot(1, 2, 3, 4, 5), math.hypot(math.inf, math.nan), math.hypot(math.nan, 1), math.hypot(1e308, 1e308), math.hypot(5e-324, 5e-324), math.hypot(1e-320, 2e-320, 3e-320), math.hypot(*[i * 0.37 for i in range(100)]))
    print(show(math.hypot, 'x'), show(math.hypot, 2 ** 1100, 1), math.dist((1, 2, 3), (4, 6, 8)), math.dist([1.5, 2.5], iter([0.5, 0.5])), show(math.dist, (1, 2), (1, 2, 3)), show(math.dist, 1, (1,)), math.dist((), ()), math.dist([i * 1.1 for i in range(50)], [i * 0.3 for i in range(50)]))
    for p, q in [([1, 2, 3], [4, 5, 6]), ([1.5, 2.5], [2, 4]), ([0.1] * 10, [0.1] * 10), ([1, 2.5, 3], [True, 2, 0.5]), ([2 ** 62, 2 ** 62], [2, 2]), ([1e308, 1e308], [10, -10]), ([1, 2], [3]), (['a'], [2]),
                 ([math.inf, 1.0], [1.0, 1.0]), ([1, 2 ** 100], [3, 2 ** 100]), ([0.1, 0.2, 0.3], [1e16, 1, -1e16]), ([F(1.5), 2.0], [2.0, 3.0])]:
        print(show(math.sumprod, p, q), end=' ')
    print()
    for xs, kw in [([], {}), ([1, 2, 3, 4], {}), ([1, 2, 3], {'start': 2}), ([2, 3.5], {}), ([2 ** 62, 4], {}), (['ab'], {'start': 2}), ([2, 3], {'start': 1.5}), ([], {'start': 'x'}), ([1.5, 2 ** 64], {}), ([1e308, 10], {}), ([1], {'strat': 3}), ([1], {'iterable': 3})]:
        print(show(math.prod, xs, **kw), end=' ')
    print(show(math.prod, 1), show(math.prod), show(math.prod, [1], 2), math.prod(range(1, 30)))


def arguments():
    print(show(math.sqrt), show(math.sqrt, 1, 2), show(math.sqrt, x=1), show(math.atan2, 1), show(math.atan2, 1, x=2), show(math.gcd, 1, x=2), show(math.hypot, 1, x=2), show(math.fma, 1, 2), show(math.copysign, 'a', 1))
    print(show(math.isclose), show(math.isclose, 1), show(math.isclose, 1, 2, 3), show(math.isclose, 1.0, a=1.0), show(math.isclose, 1, 2, b=2), show(math.isclose, a=1, b=2, rel_tol=3, abs_tol=4, x=5), show(math.isclose, b=1, a=1))
    print(show(math.nextafter, 1, 2, x=3), show(math.nextafter, 1, 2, stepz=3), show(math.nextafter, x=1, y=2), show(math.nextafter, 1, 2, 3), show(math.nextafter, 1, 2, 3, 4), show(math.prod, start=2), show(math.prod, [1], 2, 3))
    print(list(map(math.sqrt, [1, 4, 9])), sorted([2.5, -1.5, 0.5], key=math.floor), math.sqrt(4, **{}), math.isclose(*[1, 1.1], **{'rel_tol': 0.2}))


def main():
    for part in [names, conversions, rounding, libm, special_values, two_floats, float_bits, neighbours, big_logs, integers, combinatorics, sums, arguments]:
        part()


main()
