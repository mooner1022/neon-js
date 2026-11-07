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


# object.__dir__ and __sizeof__, a class's own through super(), slots, pre-headers
class C:
    def __dir__(self):
        return ['b', 'a'] + [n for n in super().__dir__() if n == '__class__']
class Big:
    def __sizeof__(self):
        return super().__sizeof__() + 100
print(dir(C()), '__dir__' in dir(object), '__class__' in dir(object), '__sizeof__' in dir(object), '__doc__' in dir(object))
print(sorted(object.__dir__(1)) == dir(1), type(object.__dir__(C())).__name__)
print(sys.getsizeof(Big()), Big().__sizeof__(), object().__sizeof__(), C().__sizeof__(), [].__sizeof__(), (1,).__sizeof__(), 'ab'.__sizeof__(), (5).__sizeof__())
print(sorted(set(dir(object))))
class S:
    __slots__ = ('a', 'b')
class SW:
    __slots__ = ('a', '__weakref__')
class SB(S):
    def __sizeof__(self):
        return 10
print(sys.getsizeof(S()), S().__sizeof__(), sys.getsizeof(SW()), SW().__sizeof__(), sys.getsizeof(SB()))
class L(list): pass
print(sys.getsizeof(L()), L().__sizeof__())
