# array (written in Kotlin as CPython writes it in C): Linux x86_64's item sizes, each typecode's limits and messages,
# every method, slices with steps, the operators, comparisons, the iterator, pickling at each protocol and copying,
# subclasses, and the buffer protocol (memoryview, bytes, struct, files, hashlib, zlib); the output Linux CPython's.
import array
import binascii
import collections.abc
import copy
import hashlib
import io
import operator
import os
import pickle
import struct
import sys
import warnings
import weakref
import zlib
from array import array as A

warnings.simplefilter('ignore', DeprecationWarning)


def show(f, /, *args, **kw):
    try:
        return repr(f(*args, **kw))
    except Exception as e:
        return '%s: %s' % (type(e).__name__, e)


class Index:
    def __init__(self, v):
        self.v = v

    def __index__(self):
        return self.v


class Real:
    def __float__(self):
        return 2.5


class Integral:
    def __int__(self):
        return 3


class MyInt(int):
    pass


class MyStr(str):
    pass


def names():
    print(sorted(n for n in dir(array) if not n.startswith('__')), array.typecodes, array.ArrayType is A)
    print(A, A.__module__, A.__name__, A.__qualname__, A.__mro__, type(A).__name__)
    print(array.__doc__.splitlines()[0], A.__doc__.splitlines()[0])
    it = iter(A('b'))
    print(type(it), type(it).__module__, type(it).__name__, show(type(it)))
    print(array._array_reconstructor.__module__, array._array_reconstructor.__name__)
    print([(c, A(c).itemsize) for c in array.typecodes])
    print(isinstance(A('b'), collections.abc.MutableSequence), isinstance(A('b'), collections.abc.Buffer), issubclass(A, collections.abc.Hashable))
    print(show(hash, A('b')), A.__hash__, A[int], A.__class_getitem__(float))
    print(type(A.typecode).__name__, A.typecode, A.itemsize, A.typecode.__get__(A('h')), A.typecode.__qualname__)
    print(show(setattr, A('b'), 'typecode', 'h'), show(setattr, A('b'), 'itemsize', 2), show(delattr, A('b'), 'typecode'))
    print(show(setattr, A('b'), 'x', 1), hasattr(A('b'), '__dict__'), show(A.typecode.__get__, 5))
    print(show(getattr, A, 'nope'), show(getattr, A('b'), 'nope'))


def limits():
    for c in 'bBhHiIlLqQ':
        size = A(c).itemsize * 8
        lo, hi = (-2 ** (size - 1), 2 ** (size - 1) - 1) if c.islower() else (0, 2 ** size - 1)
        print(c, A(c, [lo, hi]), show(A, c, [lo - 1]), show(A, c, [hi + 1]))
    for c in 'bBhHiIlLqQ':
        print(c, show(A, c, [2 ** 64]), show(A, c, [-2 ** 64]), show(A, c, [2 ** 63]), show(A, c, [-2 ** 63 - 1]))
    print(A('f', [1e40, -1e40, 1e-50, 0.1, 2 ** 31 - 1, 16777217]), A('d', [0.1, 1e308, -0.0, 2 ** 64]))
    print(A('d', [float('nan'), float('inf')]), show(A, 'f', [10 ** 400]), show(A, 'd', [10 ** 400]))


def conversions():
    for c in 'bBhHiIlLqQfd':
        row = [show(A, c, [v]) for v in (1.5, '1', None, Index(5), Index(-1), Index(2 ** 70), Real(), Integral(), 1j, True, MyInt(7))]
        print(c, ' | '.join(row))
    for c in 'uw':
        row = [show(A, c, [v]) for v in ('a', 'ab', '', '\U0001f600', '\ud800', 1, b'a', None, MyStr('z'), Integral())]
        print(c, ' | '.join(row))


def construction():
    print(show(A), show(A, 'x'), show(A, 'bb'), show(A, ''), show(A, 1), show(A, b'b'), show(A, 'b', [1], 3))
    print(show(A, 'b', initializer=[1]), show(A, typecode='b'), show(A, 'b', 'abc'), show(A, 'x', 'abc'), show(A, 'x', 5))
    print(show(A, 'b', A('w', 'ab')), show(A, 'h', A('b', [1, 2])), show(A, 'b', A('h', [1, 300])), show(A, 'b', A('b', [1, 2])))
    print(show(A, 'b', b'\x01\xff'), show(A, 'h', b'\x01\xff'), show(A, 'h', b'\x01'), show(A, 'h', bytearray(b'\x01\x02\x03\x04')))
    print(show(A, 'b', memoryview(b'\x01\x02')), show(A, 'b', (1, 2, 3)), show(A, 'b', range(5)), show(A, 'b', iter([1, 2])))
    print(show(A, 'b', {1: 2, 3: 4}), show(A, 'b', 5), show(A, 'b', None), show(A, 'w', 'abc'), show(A, 'w', ['a', 'b']))
    print(show(A, 'w', b'abcd'), show(A, 'w', [1]), show(A, 'w', A('w', 'xy')), show(A, 'd', [1, 2.5, True]), show(A, 'd', (x for x in [1, 2])))
    print(show(A, 'b', [1, 2, 300]), show(A, 'w', 'a\U0001f600b'), show(A, 'B', b''), show(A, MyStr('b'), [1]), show(A, 'w', MyStr('xy')))

    class L(list):
        pass

    class By(bytes):
        pass

    print(show(A, 'b', L([1, 2])), show(A, 'h', By(b'\x01\x00')), show(A, 'b', bytearray(b'\x01')), show(A, 'é'))


def unicode():
    with warnings.catch_warnings(record=True) as w:
        warnings.simplefilter('always')
        u = A('u', 'hi')
        A('w', 'x')
        print(len(w), w[0].category.__name__, w[0].message, u, u.itemsize, u.typecode)
    with warnings.catch_warnings():
        warnings.simplefilter('error')
        print(show(A, 'u', 'hi'))
    w = A('w', 'ab')
    print(show(w.fromunicode, 'cd'), show(w.fromunicode, b'x'), show(w.fromunicode, 1), show(w.fromunicode, '\U0001f600'), show(w.tounicode))
    print(show(A('b').fromunicode, 'x'), show(A('b').tounicode), show(A('b').fromunicode, 1), w.tobytes()[:8], w.tolist())
    u = A('u', 'xy')
    print(show(u.fromunicode, 'z\U0001f600'), show(u.tounicode), len(u), u.tobytes(), repr(u))
    for c in 'uw':
        for raw in (b'\xff\xff\xff\xff', b'\x00\x00\x11\x00', b'\x00\xd8\x00\x00', b'\x41\x00\x00\x00'):
            x = A(c)
            x.frombytes(raw)
            print(c, raw, show(x.__getitem__, 0), show(x.tounicode), show(repr, x), show(x.tolist))
    print(show(A('w', '\ud800').tounicode), show(A('u', '\ud800').tounicode), repr(A('w', 'a\'b"c\n\U0001f600')))


def items():
    a = A('i', [1, 2, 3])
    print(show(a.append, 4), show(a.append), show(a.append, 1, 2), show(a.append, x=1), show(a.append, 'x'), show(a.append, 2 ** 40))
    print(show(a.insert, 0, 9), show(a.insert, -1, 8), show(a.insert, 100, 7), show(a.insert, -100, 6), show(a.insert, 1.5, 6), show(a.insert, '1', 6))
    print(show(a.insert, 2 ** 70, 6), show(a.insert, 1), show(a.insert, 1, 2, 3), show(a.insert, 0, 'x'), show(a.insert, 0, 1, x=2), a)
    print(show(a.pop), show(a.pop, 0), show(a.pop, -2), show(a.pop, 100), show(a.pop, -100), show(a.pop, 1.0), show(a.pop, 2 ** 70), show(a.pop, 1, 2))
    print(show(A('b').pop), show(A('b').pop, 5), a)
    print(show(a.remove, 2), show(a.remove, 2), show(a.remove, 'x'), show(a.remove, 3.0), show(a.remove), a)
    d = A('d', [1, 2, 1, 3, 1.0])
    print(show(d.count, 1), show(d.count, True), show(d.count, '1'), show(d.count), show(d.count, x=1))
    print(show(d.index, 1), show(d.index, 3), show(d.index, 1, 1), show(d.index, 1, 3), show(d.index, 1, -1), show(d.index, 1, -100))
    print(show(d.index, 1, 0, 0), show(d.index, 1, 1, -2), show(d.index, 1, 1, -3), show(d.index, 9), show(d.index, 1, None), show(d.index, 1, 1.0))
    print(show(d.index, 1, 2 ** 70), show(d.index, 1, -2 ** 70), show(d.index, 1, 0, 2 ** 70), show(d.index), show(d.index, 1, 2, 3, 4), show(d.index, 1, start=1))
    print(show(A('b', [1]).index, MyInt(1)), show(A('d', [float('nan')]).count, float('nan')), show(A('b', [1, 2]).index, 2, Index(1)))


def bulk():
    a = A('h', [1])
    print(show(a.extend, [2, 3]), show(a.extend, A('h', [4])), show(a.extend, A('i', [4])), show(a.extend, (5,)), show(a.extend, iter([6])))
    print(show(a.extend, 7), show(a.extend, 'ab'), show(a.extend, b'ab'), show(a.extend, [1, 'x', 3]), show(a.extend, a), a)
    print(show(a.extend), show(a.extend, bb=[1]), show(a.extend, [1], x=1), show(a.extend, [1], [2]))
    a = A('h', [1])
    print(show(a.fromlist, [2, 3]), show(a.fromlist, (2, 3)), show(a.fromlist, [4, 'x', 5]), show(a.fromlist, []), a)
    print(show(a.frombytes, b'\x01\x00'), show(a.frombytes, b'\x01'), show(a.frombytes, bytearray(b'\x02\x00')), show(a.frombytes, memoryview(b'\x03\x00')))
    print(show(a.frombytes, A('b', [4, 0])), show(a.frombytes, A('h', [5])), show(a.frombytes, memoryview(b'\x03\x00\x04\x00').cast('h')))
    print(show(a.frombytes, memoryview(b'\x03\x00\x04\x00')[::2]), show(a.frombytes, 'ab'), show(a.frombytes, 1), show(a.frombytes), a)
    print(a.tobytes(), A('w', 'ab').tobytes(), A('d', [1.0]).tobytes(), A('q', [-2]).tobytes(), A('f', [0.5]).tobytes())
    for c in 'bhiqfdw':
        x = A(c, [1, 2]) if c != 'w' else A(c, 'ab')
        x.byteswap()
        y = x.tobytes()
        x.reverse()
        print(c, y, x.tobytes(), end='; ')
    print(show(A('b').byteswap, 1), show(A('b').reverse, 1))
    a = A('i', [1, 2, 3])
    print(show(a.clear), a, len(a), show(a.clear, 1), A('i', [1, 2]).tolist(), show(A('i').tolist, 1))
    print(type(A('b', [1]).buffer_info()[0]), A('b').buffer_info(), A('d', [1, 2]).buffer_info()[1], show(A('b').buffer_info, 1))


def files():
    a = A('h', [1, 2, 3])
    f = io.BytesIO()
    print(show(a.tofile, f), f.getvalue())
    f.seek(0)
    b = A('h')
    print(show(b.fromfile, f, 2), b, show(b.fromfile, f, 2), b, show(b.fromfile, f, -1), show(b.fromfile, f, 1.0))
    print(show(b.fromfile, f), show(b.fromfile, io.StringIO('ab'), 1), show(b.fromfile, 1, 1), show(b.tofile, 1), show(A('b').tofile, 1))
    print(show(b.tofile), show(b.tofile, 1, 2), show(b.fromfile, 1, 2, 3), show(b.fromfile, f=1, n=2))

    class R:
        def read(self, n):
            print('read', n)
            return b'\x01\x00\x02'

    class W:
        def write(self, x):
            print('write', type(x).__name__, len(x))

    print(show(b.fromfile, R(), 2), b, show(A('b', range(10)).tofile, W()), show(A('b', [1] * 70000).tofile, W()))
    with open('array.bin', 'wb') as f:
        print(f.write(A('i', [1, 2, 3])), A('d', [0.5]).tofile(f), f.write(memoryview(A('h', [7]))))
    with open('array.bin', 'rb') as f:
        c = A('i', [0, 0])
        print(f.readinto(c), c, f.read(2), show(A('i').fromfile, f, 3))
    with open('array.bin', 'rb', buffering=0) as f:
        c = A('b', [0] * 5)
        print(f.readinto(c), c, f.readinto(A('w', 'z')))
    fd = os.open('array.bin', os.O_RDWR)
    print(os.write(fd, A('b', [65, 66])), os.lseek(fd, 0, 0), os.readinto(fd, c), c)
    os.close(fd)
    sys.stdout.flush()
    os.write(1, A('b', [111, 115, 10]))
    os.remove('array.bin')


def slicing():
    a = A('i', range(10))
    keys = [0, -1, 10, -11, 2 ** 70, True, 1.0, 'x', None, slice(1, 3), slice(None, None, 2), slice(None, None, -1), slice(8, 2, -3),
            slice(100, None), slice(-100, 2), slice(None, None, 0), slice(1, 2, None), slice(2 ** 70, None), slice('a', None), ..., (1, 2), Index(3)]
    print(' | '.join(show(a.__getitem__, k) for k in keys))
    for k in keys[:12]:
        b = A('i', range(10))
        print(show(b.__delitem__, k), b, end='; ')
    print()


def assign(key, v):
    b = A('i', range(10))
    try:
        b[key] = v
        return repr(b)
    except Exception as e:
        return '%s: %s' % (type(e).__name__, e)


def slice_assignment():
    print(assign(0, 99), assign(-1, 99), assign(10, 99), assign(-11, 99), assign(2 ** 70, 99), assign(0, 'x'), assign(0, 2 ** 40))
    print(assign(10, 'x'), assign('x', 1), assign(1.0, 1), assign(slice(1, 3), A('i', [7, 8, 9])), assign(slice(1, 3), A('i')))
    print(assign(slice(1, 3), A('h', [1, 2])), assign(slice(1, 3), [1, 2]), assign(slice(1, 3), (1, 2)), assign(slice(None, None, 2), A('i', [1, 2, 3, 4, 5])))
    print(assign(slice(None, None, 2), A('i', [1, 2, 3])), assign(slice(None, None, -3), A('i', [1, 2, 3, 4])), assign(slice(None, None, 2), A('i')))
    print(assign(slice(5, 2), A('i', [7, 7])), assign(slice(5, 2, -1), A('i', [7, 7, 7])), assign(slice(5, 2, -1), A('i', [7])))
    print(assign(slice(100, 200), A('i', [1])), assign(slice(-100, -50), A('i', [1])), assign(slice(None, None, 0), A('i')), assign(..., A('i')))
    b = A('i', [1, 2, 3])
    b[1:2] = b
    c = A('i', [1, 2, 3, 4, 5])
    c[::2] = c[:3]
    d = A('w', 'abcdef')
    del d[1::2]
    d[:0] = A('w', 'xy')
    print(b, c, d, show(b.__setitem__, 0), show(b.__delitem__), show(b.__setitem__, slice(0, 1), A('i', [5]), 1))
    for st in (2, 3, -2, -3, 7):
        e = A('h', range(20))
        del e[3:17:st]
        f = A('h', range(20))
        f[1:19:st] = A('h', [0] * len(range(20)[1:19:st]))
        print(st, e.tolist(), f.tolist())


def operators():
    a = A('h', [1, 2])
    print(show(lambda: a + A('h', [3])), show(lambda: a + A('i', [3])), show(lambda: a + [3]), show(lambda: [3] + a), show(lambda: a + b'x'))
    print(show(lambda: a * 3), show(lambda: 3 * a), show(lambda: a * 0), show(lambda: a * -1), show(lambda: a * True), show(lambda: a * 1.5))
    print(show(lambda: a * 'x'), show(lambda: a * 2 ** 70), show(lambda: A('h') * 2 ** 70), show(lambda: a * a), show(a.__mul__, 2), show(a.__rmul__, 2))
    print(show(a.__add__, [1]), show(a.__add__, A('b')), show(lambda: a - a), show(lambda: -a), show(lambda: a < 1), a == 1, a == [1, 2], a != (1, 2))

    class X:
        def __radd__(self, o):
            return 'radd'

        def __rmul__(self, o):
            return 'rmul'

    class Y:
        def __mul__(self, o):
            return NotImplemented

    print(show(lambda: a + X()), show(lambda: a * X()), show(lambda: X() * a), show(lambda: X() + a), show(lambda: Y() * a), show(lambda: (A('b', [1]) * 3).tobytes()))
    for x, y in ((A('h', [1]), A('h', [2])), (A('h', [1]), A('i', [2])), (A('h', [1]), [2]), (A('h', [1]), (2,)), (A('h', [1]), 1), (A('h', [1]), X())):
        old = x
        try:
            x += y
            print('ok', x, x is old, end='; ')
        except Exception as e:
            print(type(e).__name__, e, end='; ')
    print()
    for x, y in ((A('h', [1, 2]), 3), (A('h', [1, 2]), 0), (A('h', [1, 2]), -2), (A('h', [1, 2]), 1), (A('h'), 5), (A('h', [1]), 'x'), (A('h', [1]), 2 ** 70), (A('h'), 2 ** 70), (A('h'), X())):
        old = x
        try:
            x *= y
            print('ok', x, x is old, end='; ')
        except Exception as e:
            print(type(e).__name__, e, end='; ')
    print()


def comparisons():
    nan = float('nan')
    pairs = [(A('h', [1, 2]), A('h', [1, 2])), (A('h', [1, 2]), A('h', [1, 3])), (A('h', [1, 2]), A('h', [1])), (A('h', [1, 2]), A('i', [1, 2])),
             (A('h', [1, 2]), A('d', [1.0, 2.5])), (A('d', [nan]), A('d', [nan])), (A('f', [1.5]), A('d', [1.5])), (A('b', [-1]), A('B', [255])),
             (A('B', [255]), A('B', [1])), (A('b', [-1]), A('b', [1])), (A('h'), A('h')), (A('w', 'ab'), A('w', 'b')), (A('w', 'ab'), A('d', [1])),
             (A('Q', [2 ** 64 - 1]), A('q', [-1])), (A('Q', [2 ** 64 - 1]), A('Q', [1])), (A('q', [-1]), A('q', [1])), (A('I', [2 ** 32 - 1]), A('I', [1])),
             (A('i', [-1]), A('i', [1])), (A('H', [65535]), A('H', [1])), (A('h', [-1]), A('h', [1])), (A('d', [1, nan]), A('d', [1, 2])),
             (A('w', 'a'), A('w', '\U0001f600')), (A('u', 'a'), A('w', 'a')), (A('u', 'b'), A('u', 'a')), (A('l', [3]), A('L', [3]))]
    for x, y in pairs:
        row = [show(op, x, y) for op in (operator.eq, operator.ne, operator.lt, operator.le, operator.gt, operator.ge)]
        print(x.typecode, y.typecode, ' '.join(row))
    u = A('u')
    u.frombytes(b'\xff\xff\xff\xff')
    w = A('w')
    w.frombytes(b'\xff\xff\xff\xff')
    print(show(lambda: u < A('u', 'a')), show(lambda: u == A('u', 'a')), show(lambda: w < A('w', 'a')), show(lambda: w == A('b', [1])))
    print(show(A('b', [1]).__eq__, [1]), show(A('b', [1]).__lt__, A('b', [2])), show(A('b').__eq__), sorted([A('b', [2]), A('b', [1, 5]), A('b')]))


def iteration():
    a = A('i', [1, 2, 3])
    print(2 in a, 2.0 in a, 'x' in a, 5 in a, None in A('d', [1]), float('nan') in A('d', [float('nan')]), show(a.__contains__))
    it = iter(a)
    print(next(it), it.__reduce__(), list(it), it.__reduce__(), show(next, it), show(it.__setstate__, 1), list(it))
    it = iter(a)
    print(it.__setstate__(2), list(it), iter(it) is it, show(it.__reduce__, 1), hasattr(it, '__length_hint__'))
    it = iter(a)
    print(it.__setstate__(-5), next(it), it.__setstate__(50), list(it), show(it.__setstate__, 'x'), show(it.__setstate__, 2 ** 70))
    it = iter(a)
    print(it.__setstate__(True), next(it), show(it.__setstate__), show(type(it)), show(type(it).__next__, 5))
    b = A('i', [1, 2, 3])
    it = iter(b)
    next(it)
    b.append(4)
    print(list(it), end=' ')
    it = iter(b)
    next(it)
    b.clear()
    print(list(it), end=' ')
    b.append(9)
    print(list(it), list(reversed(A('i', [1, 2, 3]))), hasattr(A, '__reversed__'), sorted(A('i', [3, 1, 2])), sum(A('d', [0.5, 0.25])))
    print(max(A('b', [3, -1])), list(enumerate(A('w', 'ab'))), dict(zip('ab', A('b', [1, 2]))), [*A('b', [1, 2])], len(A('q', [1, 2, 3])))
    x, y = A('b', [5, 6])
    print(x, y, bool(A('b')), bool(A('b', [0])), list(map(str, A('f', [0.5]))))
    for proto in range(pickle.HIGHEST_PROTOCOL + 1):
        it = iter(A('h', [7, 8, 9]))
        next(it)
        it2 = pickle.loads(pickle.dumps(it, proto))
        print(proto, type(it2).__name__, list(it2), list(copy.copy(it)), end='; ')
    print()


def pickling():
    a = A('h', [1, -2])
    print(a.__reduce_ex__(0), a.__reduce_ex__(2), a.__reduce_ex__(3), a.__reduce_ex__(5), a.__reduce_ex__(True))
    print(show(a.__reduce_ex__, 'x'), show(a.__reduce_ex__, 2 ** 70), show(a.__reduce_ex__), show(a.__reduce_ex__, 1, 2), show(a.__reduce_ex__, proto=1))
    for c in array.typecodes:
        x = A(c, 'ab') if c in 'uw' else A(c, [1])
        print(c, x.__reduce_ex__(3)[1][2:], end='; ')
    print()

    class S(A):
        pass

    s = S('i', [1, 2])
    s.x = 5
    print(s.__reduce_ex__(3), s.__reduce_ex__(1))
    for proto in range(pickle.HIGHEST_PROTOCOL + 1):
        for x in (A('b', [1, 2]), A('w', 'xy'), A('d', [0.5, -1e300]), A('Q', [2 ** 64 - 1]), A('u', 'hi'), A('f', [0.1]), A('L')):
            y = pickle.loads(pickle.dumps(x, proto))
            print(type(y).__name__, y, y == x, end='; ')
        print()
    for proto in (0, 3):
        print(pickle.dumps(A('b', [1]), proto), pickle.dumps(A('d', [0.5]), proto))
    print(pickle.dumps(A('w', 'a'), 2), pickle.dumps(A('w', 'a'), 4))


class Pickled(A):
    pass


def pickled_subclasses():
    p = Pickled('i', [1, 2])
    p.x = 5
    for proto in range(pickle.HIGHEST_PROTOCOL + 1):
        q = pickle.loads(pickle.dumps(p, proto))
        print(proto, type(q).__name__, q, q.__dict__, end='; ')
    print()
    print(pickle.loads(pickle.dumps([A('b', [1]), A('b', [1])], 2)), pickle.dumps(Pickled('b', [3]), 4))


def reconstructor():
    r = array._array_reconstructor
    cases = [(A, 'b', 1, b'\x01\xff'), (A, 'h', 4, b'\x01\x00'), (A, 'h', 5, b'\x01\x00'), (A, 'h', 2, b'\xff\xff'), (A, 'h', 3, b'\xff\x00'),
             (A, 'i', 4, b'\x01\x00'), (A, 'i', 13, b'\x00\x00\x00\x00\x00\x00\x00\x05'), (A, 'b', 12, b'\x05\x00\x00\x00\x00\x00\x00\x00'),
             (A, 'B', 10, b'\x05\x00\x00\x00\x00\x00\x00\x00'), (A, 'B', 10, b'\x05\x00\x00\x00\x00\x00\x00'), (A, 'B', 11, b'\xff' * 8),
             (A, 'q', 9, b'\xff\xff\xff\xfe'), (A, 'q', 7, b'\xff\xff\xff\xfe'), (A, 'd', 17, b'\x3f\xf0\x00\x00\x00\x00\x00\x00'),
             (A, 'd', 15, b'\x3f\xc0\x00\x00'), (A, 'f', 14, b'\x00\x00\xc0\x3f'), (A, 'f', 17, b'\x3f\xf0\x00\x00\x00\x00\x00\x00'),
             (A, 'w', 21, b'\x00\x00\x00a'), (A, 'w', 21, b'\x00\x00\x00a\x00\x00\x00b'), (A, 'w', 19, b'\x00a\xd8\x3d'), (A, 'w', 18, b'a\x00b\x00'),
             (A, 'u', 20, b'a\x00\x00\x00'), (A, 'b', 18, b'a\x00b\x00'), (A, 'w', 21, b'\x00\x00\xd8\x00\x00\x00\x00a'), (A, 'd', 3, b'\x00\x01'),
             (A, 'w', 4, b'\x00\x01'), (A, 'x', 1, b''), (A, 'bb', 1, b''), (A, 1, 1, b''), (A, 'b', 22, b''), (A, 'b', -1, b''), (A, 'b', 2 ** 40, b''),
             (A, 'b', 'x', b''), (A, 'b', 1, bytearray(b'')), (A, 'b', 1, [1]), (int, 'b', 1, b''), (1, 'b', 1, b''), (Pickled, 'b', 1, b'\x01'),
             (A, 'b', 1.5, b''), (A, 'b', True, b'\x01'), (A, 'f', 14, b'\x00\x00\x80\x7f')]
    for args in cases:
        print(show(r, *args), end='; ')
    print()
    print(show(r, A, 'b', 1), show(r, A, 'b', 1, b'', 5), show(r, A, 'b', 1, b'', x=5))


def copying():
    a = A('i', [1, 2])
    b = copy.copy(a)
    c = copy.deepcopy(a)
    print(b, b is a, b == a, c, c is a, a.__copy__(), a.__deepcopy__({}), show(a.__deepcopy__), show(a.__copy__, 1), show(a.__copy__, x=1))

    class S(A):
        pass

    s = S('i', [1, 2])
    s.x = 5
    for y in (copy.copy(s), copy.deepcopy(s), s.__copy__(), s[:], s + s, s * 2, 2 * s):
        print(type(y).__name__, y, end='; ')
    print()


class T(A):
    def __init__(self, tc, init=(), extra=None):
        self.extra = extra

    def __repr__(self):
        return 'T' + super().__repr__()


class U(A):
    def __new__(cls, *args, **kw):
        print('new', args, kw)
        return super().__new__(cls, *args)


class V(A):
    pass


class W(A):
    def __getitem__(self, i):
        return 'W' + str(super().__getitem__(i))

    def __len__(self):
        return 99


def subclasses():
    x = T('b', [1, 2], extra=5)
    print(x, x.extra, len(x), x.typecode, isinstance(x, A), show(T, 'b', [1], 2, 3), U('b', [1], k=1))
    print(show(V, 'b', [1], k=1), show(V, 'b', x=1), show(V), V('b', [1]) == A('b', [1]), issubclass(V, A), V.__mro__)
    w = W('b', [1, 2])
    print(w[0], len(w), list(w), w.tolist(), repr(w), w[:1], 2 in w)
    print(show(A.__new__, A, 'b', [1]), show(A.__new__, int, 'b'), show(A.__new__, V, 'b', [1]), show(A.__new__), show(A.__new__, 1))
    print(show(A.append, 1, 2), show(A.append, V('b'), 2), show(A.tolist, 5), show(A.__len__, 5), show(A.__len__, V('b', [1, 2])))
    print(show(A.__getitem__, V('b', [1, 2]), 1), show(A.append), show(A.__len__), show(A('b').__len__, 1), show(A('b').__getitem__))

    class Sl(A):
        __slots__ = ('p',)

    class Z(A):
        __slots__ = ()

    sl = Sl('b')
    sl.p = 1
    print(sl.p, show(setattr, sl, 'q', 1), show(setattr, Z('b'), 'q', 1), Sl('b').__sizeof__(), Z('b').__sizeof__(), V('b', [1, 2]).__sizeof__())
    a = A('b', [1])
    r = weakref.ref(a)
    print(r() is a, weakref.proxy(a)[0], show(lambda: [] in A('b', [1])))


def sizes():
    for c in 'bhiqfdw':
        a = A(c)
        row = [a.__sizeof__()]
        for i in range(20):
            a.append('x' if c == 'w' else 1)
            row.append(a.__sizeof__())
        for i in range(20):
            a.pop()
            row.append(a.__sizeof__())
        print(c, row)
    a = A('d', range(100))
    print(a.__sizeof__(), sys.getsizeof(a), sys.getsizeof(A('b', [1, 2, 3])), A('w', 'abc').__sizeof__(), (A('b', [1]) * 10).__sizeof__())
    a.extend(range(5))
    b = A('d', range(40))
    del b[5:]
    print(a.__sizeof__(), b.__sizeof__(), show(A('b').__sizeof__, 1))


def buffers():
    for c in array.typecodes:
        a = A(c, 'ab') if c in 'uw' else A(c, [1, 2])
        m = memoryview(a)
        print(c, m.format, m.itemsize, m.nbytes, m.shape, m.strides, m.readonly, m.obj is a, show(m.tolist), show(m.__getitem__, 0), m.tobytes()[:5])
    a = A('h', [1, 2, 3])
    m = memoryview(a)
    m[0] = 100
    print(a, show(m.__setitem__, 1, 70000), show(m.__setitem__, 1, 1.5), show(m.__setitem__, slice(0, 2), b'\x00\x00\x00\x00'))
    m[1:3] = memoryview(A('h', [7, 8]))
    mb = m.cast('B')
    mb[0] = 255
    a[2] = 5
    print(a, mb.format, len(mb), m[2], m.tolist(), show(hash, m), m == a, m == A('h', [255, 7, 5]), m == A('i', [255, 7, 5]), m == [255, 7, 5], m.hex())
    d = memoryview(A('d', [1.5, -2.0]))
    d[0] = 3
    f = memoryview(A('f', [1.5]))
    f[0] = 0.1
    q = memoryview(A('Q', [2 ** 64 - 1]))
    print(d.obj, d.tolist(), f.obj, f[0], q[0], show(memoryview(A('w', 'ab')).cast, 'I'))
    w = A('w', 'ab')
    mw = memoryview(w).cast('B').cast('I')
    mw[0] = 0x1F600
    mv = memoryview(A('i', [1, 2, 3, 4]))[::2]
    print(w, mv.tolist(), mv.strides, mv.contiguous, memoryview(A('b')).tolist(), memoryview(A('d')).format, memoryview(A('i', [1, 2]))[::-1].tolist())
    with memoryview(A('b', [1])) as mm:
        print(mm[0], end=' ')
    m = a.__buffer__(0)
    print(m.format, m.tolist(), a.__buffer__(4).format, show(a.__release_buffer__, m), show(m.__getitem__, 0), show(a.__buffer__), show(a.__buffer__, 'x'))
    print(show(a.__buffer__, 2 ** 40), show(a.__release_buffer__, 5), show(a.__release_buffer__))


def bytes_likes():
    a = A('h', [1, 256])
    print(bytes(a), bytearray(a), bytes(A('w', 'a')), bytes(A('d', [1.0])), b'x' + a, bytearray(b'x') + a, b'x'.join([a, a]))
    ba = bytearray(b'zz')
    ba += a
    ba.extend(a)
    print(ba, bytes(A('b')), b'' + A('b'), show(lambda: a == b'\x01\x00\x00\x01'), show(lambda: b'\x01\x00\x00\x01' == a))
    print(A('b', [97]) in b'abc', A('h', [0x6261]) in b'abc', b'abc'.startswith(A('b', [97])), b'abc'.find(A('b', [98])), b'a,b'.split(A('b', [44])))
    print(str(A('b', [104, 105]), 'ascii'), show(getattr, A('b'), 'decode'), show(bytes.fromhex, A('b', [104])), int.from_bytes(A('B', [1, 2]), 'big'))
    print(float(A('b', [49])), int(A('b', [49])), show(int, A('b', [120])), show(int, A('b', [49]), 10), show(float, A('b', [120])), bytes(A('b', [1, 2]))[::-1])
    print(list(memoryview(A('b', [1, 2]))), A('b', [1]) == memoryview(b'\x01'), memoryview(b'\x01') == A('b', [1]), bytearray(b'a') in b'abc')


def modules():
    a = A('b', [0] * 8)
    struct.pack_into('<hi', a, 1, 258, -1)
    print(a, struct.unpack_from('<hi', a, 1), struct.unpack('<8b', a), list(struct.iter_unpack('<h', a)), show(struct.pack_into, '<q', a, 1, 5))
    print(show(struct.unpack, '<h', A('h', [7])), show(struct.Struct('<h').unpack_from, A('h', [7, 8]), 2), show(struct.pack_into, '<h', A('d', [0.0]), 6, 513))
    a = A('i', [1, 2, 3])
    print(hashlib.md5(a).hexdigest(), hashlib.sha256(a).hexdigest()[:16], hashlib.blake2b(a).hexdigest()[:16], hashlib.sha3_256(a).hexdigest()[:16])
    h = hashlib.sha1()
    h.update(a)
    print(h.hexdigest(), zlib.compress(a), zlib.crc32(a), zlib.adler32(a), zlib.decompress(zlib.compress(a)) == a.tobytes())
    co = zlib.compressobj()
    print(len(co.compress(a) + co.flush()), binascii.hexlify(a), binascii.crc32(a), binascii.b2a_base64(a))
    f = io.BytesIO()
    print(f.write(a), f.getvalue(), show(io.BytesIO(b'abcd').readinto, A('w', 'z')), show(io.BytesIO(b'abc').readinto1, A('b', [0, 0])))
    f = io.BytesIO(b'\x05\x00\x06\x00\x07\x00')
    c = A('h', [0, 0, 0, 0])
    br = io.BufferedReader(f)
    print(br.readinto(c), c, io.BufferedWriter(io.BytesIO()).write(A('i', [1])))


def matching():
    for x in (A('b', [1, 2]), A('w', 'ab'), A('d', [1.5]), A('b')):
        match x:
            case [p, q]:
                print('two', p, q, end='; ')
            case [p]:
                print('one', p, end='; ')
            case []:
                print('empty', end='; ')
    print()
    seen = []
    sys.addaudithook(lambda ev, args: seen.append((ev, args)) if ev == 'array.__new__' else None)
    A('b', [1])
    A('h')
    A('d', (1,)) * 2
    print(seen)


def main():
    for part in [names, limits, conversions, construction, unicode, items, bulk, files, slicing, slice_assignment, operators, comparisons,
                 iteration, pickling, pickled_subclasses, reconstructor, copying, subclasses, sizes, buffers, bytes_likes, modules, matching]:
        part()


main()
