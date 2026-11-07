# Equal keys of different kinds meet in a dict, as CPython's one hash makes them: a class whose __hash__ is a str's
# or a tuple's, a float and a Fraction or a Decimal, bytes and a memoryview, big ints, frozensets, complex and range;
# a __hash__'s int kept as slot_tp_hash keeps it; ElementTree's QName tags (its serializer's dict).
from fractions import Fraction
from decimal import Decimal
import xml.etree.ElementTree as ET
class K:
    def __init__(self, s): self.s = s
    def __hash__(self): return hash(self.s)
    def __eq__(self, o): return o == self.s
words = ['alpha', 'beta', 'gamma', 'delta', 'zeta', 'eta', 'theta', 'iota', 'kappa', 'lambda'] + ['w%d' % i for i in range(200)]
d = {w: i for i, w in enumerate(words)}
print(sum(1 for w in words if K(w) in d), len(words))
print({1.5: 'f'}[Fraction(3, 2)], {Fraction(3, 2): 'r'}[1.5], {2: 'i'}[2.0], {2.0: 'x'}[Decimal(2)], {Decimal('1.5'): 'd'}[1.5])
print({(1, 'a'): 't'}[(1, 'a')], {b'ab': 'b'}[memoryview(b'ab')], {2**70: 'big'}[2**70], {-1: 'm'}[-1], {-2: 'n'}[-2], len({-1, -2}))
class T:
    def __init__(self, t): self.t = t
    def __hash__(self): return hash(self.t)
    def __eq__(self, o): return o == self.t
print({(1, 2): 'tt'}[T((1, 2))], {2**64 + 1: 'h'}[Fraction(2**64 + 1)])
class H:
    def __hash__(self): return 2**100
print(hash(H()) == hash(2**100), {H(): 1}.__len__())
q = ET.QName('{http://x}tag')
root = ET.Element(q)
ET.SubElement(root, ET.QName('{http://x}child'))
print(ET.tostring(root))
print(hash(-1), hash(-2), hash(2**61 - 1), hash(2**61), hash(-(2**61)), hash(1.5), hash(-0.5))
s = {frozenset([1, 2]): 'fs'}
print(s[frozenset([2, 1])], {1j: 'c'}[1j], {complex(2, 0): 'c2'}[2], {range(3): 'r'}[range(0, 3)])
class X:
    def __init__(s, h): s.h = h
    def __hash__(s): return s.h
print([hash(X(h)) for h in [2**62, -1, 2**64, -(2**63), 2**63 - 1]])
import weakref
class W: pass
w = W()
print(hash(weakref.ref(w)) == hash(w))
