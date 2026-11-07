# sys.getsizeof as Linux CPython 3.14 gives it for what NeonPy can size as it does (a list's overallocation and a
# dict's table, which depend on how they were built, are left out).
import sys
class C: pass
class S:
    __slots__ = ('a', 'b')
def f(): pass
vals = [None, True, False, 0, 1, 2**30, 2**62, 2**100, -5, 1.5, 1j, '', 'ab', 'é', '€', '😀', b'', b'xy', bytearray(), bytearray(b'xy'), (), (1,), (1, 2, 3), [], [1], {}, {1: 2}, set(), {1, 2}, frozenset(), range(3), object(), C(), S(), f, print, len, C, type, slice(1, 2), iter([]), ...]
for v in vals:
    print(type(v).__name__, sys.getsizeof(v))
