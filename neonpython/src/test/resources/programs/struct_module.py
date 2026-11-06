# struct (its _struct written in Kotlin as CPython writes it in C): Linux x86_64's native sizes and alignment, the
# standard tables, every format character, half floats and NaNs bit for bit, buffers, Struct and its subclasses,
# CPython's errors.
import copy
import struct
import _struct


def show(f, *args, **kw):
    try:
        return repr(f(*args, **kw))
    except Exception as e:
        return '%s: %s' % (type(e).__name__, e)


def hexpack(fmt, *args):
    try:
        return struct.pack(fmt, *args).hex()
    except Exception as e:
        return '%s: %s' % (type(e).__name__, e)


def bits(x):
    return struct.pack('<d', x).hex()


class Index:
    def __init__(self, v):
        self.v = v

    def __index__(self):
        return self.v


class Real:
    def __float__(self):
        return 2.5


class Cx:
    def __complex__(self):
        return 1 - 2j


class Truth:
    def __bool__(self):
        raise ValueError('no truth')


class Big(int):
    def __index__(self):
        return 99


def names():
    print(sorted(n for n in dir(struct) if not n.startswith('__')), struct.__all__)
    print(sorted(dir(_struct)), struct.Struct is _struct.Struct, struct.error is _struct.error)
    print(struct.__doc__.splitlines()[:2], len(struct.__doc__))
    print(struct.Struct, struct.error, struct.error.__mro__, struct.error.__module__, repr(struct.Struct.__doc__))
    it = struct.iter_unpack('b', b'x')
    print(type(it), type(it).__name__, type(it).__module__, show(type(it)), show(type(it).__init_subclass__))
    print(struct.pack.__name__, struct.Struct.pack.__name__, show(struct.calcsize, [1]))


def sizes():
    for f in ['x', 'c', 'b', 'B', '?', 'h', 'H', 'i', 'I', 'l', 'L', 'q', 'Q', 'n', 'N', 'e', 'f', 'd', 'F', 'D', 'P', 's', 'p']:
        print(f, struct.calcsize(f), struct.calcsize('c' + f), struct.calcsize(f + 'c'), show(struct.calcsize, '<' + f), show(struct.calcsize, '!c' + f), end=' | ')
    print()
    for f in ['', '@', '=', '<', '>', '!', 'ic', 'ci', 'cl', 'b0i', 'b0d', '0s', '0p', '3x', 'x0x', ' 2 h', '  i  h  ', 'i\n\ti', '10s3x2c', '@bB?hHiIlLqQnNefdFDP', '=bB?hHiIlLqQefdFD', b'4i', '999999999999999999x']:
        print(repr(f), show(struct.calcsize, f), end=' | ')
    print()
    for f in ['3', ' 3i', '3 i', 'z', 'i@', '<n', '>N', '=P', '\xe9', 'i\0', b'\xff', b'\0', '99999999999999999999x', '9223372036854775807xb', '4611686018427387904h', 'q' * 3 + '1152921504606846976q', 1, None]:
        print(show(struct.calcsize, f), end=' | ')
    print()


def integers():
    for f in 'bBhHiIlLqQnN':
        for order in ['', '<', '>']:
            if order and f in 'nN':
                continue
            fmt = order + f
            size = struct.calcsize(fmt)
            lo = -(1 << (8 * size - 1)) if f.islower() else 0
            hi = (1 << (8 * size - 1)) - 1 if f.islower() else (1 << (8 * size)) - 1
            vals = [lo, hi, 0, 1, -1]
            print(fmt, [hexpack(fmt, v) for v in vals], [struct.unpack(fmt, struct.pack(fmt, v))[0] for v in (lo, hi)], hexpack(fmt, lo - 1), hexpack(fmt, hi + 1))
    print(hexpack('<l', 2 ** 63), hexpack('l', 2 ** 64), hexpack('>Q', -2 ** 70), hexpack('Q', 2 ** 100), hexpack('<h', True), hexpack('b', Index(-5)), hexpack('B', Index(300)), hexpack('b', Index('x')), hexpack('b', Big(5)))
    print(hexpack('i', 1.0), hexpack('i', '1'), hexpack('i', None), hexpack('Q', Index(2 ** 64 - 1)), hexpack('q', Index(2 ** 64)))
    print(hexpack('P', -1), hexpack('P', 2 ** 64 - 1), hexpack('P', 2 ** 64), hexpack('P', -2 ** 63 - 1), hexpack('P', Index(2 ** 64)), hexpack('P', Index(-2 ** 64)), struct.unpack('P', b'\xff' * 8), struct.unpack('N', b'\x80' + b'\x00' * 7))


def floats():
    for v in [0.0, -0.0, 1.0, -2.5, 0.1, 1e-5, 65504.0, 65519.99, 65520.0, 2 ** -24, 2 ** -25, 2 ** -25 + 2 ** -35, 2 ** -26, 2 ** -14 - 2 ** -25, 1 + 2 ** -11, 1 + 2 ** -11 + 2 ** -16, 0x1ffdffffff * 2 ** -26, 1e300, float('inf'), float('-inf')]:
        print(hexpack('<e', v), hexpack('>e', v), hexpack('<f', v), hexpack('>f', v), hexpack('f', v), hexpack('<d', v), end=' | ')
    print()
    for v in [3.4028234663852886e38, 3.4028235677973366e38, 3.4028235677973362e38, 1e-46, 7.006492321624085e-46, 7.006492321624086e-46, -1e-50]:
        print(hexpack('<f', v), hexpack('<F', v), hexpack('>F', v), hexpack('F', complex(1, v)), end=' | ')
    print()
    for b in ['007c', '017c', '007e', '01fe', 'ff7f', '0100', 'ff03', '0004', 'ff7b', '5535']:
        x = struct.unpack('<e', bytes.fromhex(b))[0]
        print(repr(x), bits(x), end=' | ')
    print()
    for b in ['0100807f', '0000c0ff', '0100a07f', '0000807f', '01000000']:
        raw = bytes.fromhex(b)
        print([bits(struct.unpack(f, r)[0]) for f, r in [('<f', raw), ('f', raw), ('>f', raw[::-1])]], bits(struct.unpack('<F', raw * 2)[0].imag), bits(struct.unpack('>F', raw[::-1] * 2)[0].imag), end=' | ')
    print()
    for d in ['000100000000f07f', '000000000000f87f', '00000020000af07f', '000000000000f8ff', '0000000000000080']:
        x = struct.unpack('<d', bytes.fromhex(d))[0]
        print(hexpack('<f', x), hexpack('f', x), hexpack('>f', x), hexpack('<e', x), hexpack('>e', x), hexpack('<F', complex(x, 1)), hexpack('>F', complex(x, 1)), hexpack('<d', x), end=' | ')
    print()
    print(hexpack('e', 10 ** 6), hexpack('e', 1e6), hexpack('e', Index(10 ** 6)), hexpack('f', 10 ** 300), hexpack('d', 10 ** 400), hexpack('d', Real()), hexpack('d', Index(3)), hexpack('d', 'x'), hexpack('e', None))
    print(hexpack('<D', 1 + 2j), hexpack('>D', Cx()), hexpack('<D', Real()), hexpack('<F', True), hexpack('<D', 'x'), hexpack('F', None), struct.unpack('<FD', struct.pack('<FD', 1.5 - 2j, -0.5j)), struct.unpack('>F', b'\x7f\x80\x00\x00\x7f\xc0\x00\x00'))


def strings_and_bools():
    print(hexpack('5s', b'ab'), hexpack('2s', b'hello'), hexpack('0s', b'x'), hexpack('3s', bytearray(b'xyz')), hexpack('s', 'a'), hexpack('s', memoryview(b'a')), hexpack('2s', 1))
    print(hexpack('5p', b'ab'), hexpack('3p', b'hello'), hexpack('0p', b'x'), hexpack('1p', b'x'), hexpack('300p', b'a' * 299)[:6], hexpack('p', 'a'), hexpack('0pc', b'x', b'z'), hexpack('c0p', b'z', b'x'))
    print(struct.unpack('3p', b'\x09ab'), struct.unpack('3p', b'\x01ab'), struct.unpack('0p', b''), struct.unpack('2s0s', b'ab'), struct.unpack('4s', b'\x00a\x00b'), struct.unpack('0s0p', b''))
    print(hexpack('c', b'a'), hexpack('c', bytearray(b'a')), hexpack('c', b'ab'), hexpack('c', 'a'), hexpack('3c', b'a', b'b', b'c'), struct.unpack('2c', b'\x00\xff'))
    print(hexpack('?', 2), hexpack('?', []), hexpack('?', 'x'), hexpack('<?', 0.0), hexpack('>?', Index(0)), hexpack('?', Truth()), struct.unpack('3?', b'\x00\x01\x80'), struct.unpack('<?', b'\x02'))
    ba = bytearray(b'\xff' * 8)
    struct.pack_into('i0p', ba, 0, 5, b'')
    print(ba)


def buffers():
    for off in [0, 4, 6, 7, -4, -6, -10, -11, -1, 2 ** 62, -2 ** 62]:
        print(off, show(struct.unpack_from, '<hi', b'0123456789', off), show(struct.Struct('<hi').unpack_from, bytearray(b'0123456789'), offset=off), end=' | ')
    print()
    for off in [0, 4, 6, 7, -4, -6, -10, -11, True, 2 ** 70, 'x']:
        ba = bytearray(10)
        print(off, show(struct.pack_into, '<hi', ba, off, 1, 2), ba.hex(), end=' | ')
    print()
    mv = memoryview(bytearray(b'abcdefghij'))[2:8]
    struct.pack_into('>h', mv, 1, 0x4142)
    print(mv.obj, struct.unpack_from('4s', mv, 2), struct.unpack('6s', mv), list(struct.iter_unpack('3s', mv)), struct.unpack('<i', memoryview(b'\x01\x00\x00\x00\x02\x00\x00\x00').cast('i')[1:]))
    released = memoryview(b'abcd')
    released.release()
    print(show(struct.unpack, 'i', 'abcd'), show(struct.unpack, 'i', memoryview(b'abcdefgh')[::2]), show(struct.unpack, 'i', released), show(struct.unpack, 'i', b'abc'), show(struct.unpack_from, 'i', b'abcd', 2 ** 70), show(struct.unpack_from, 'i', b'abcd', 'x'))
    for target in [b'abcd', memoryview(b'abcd'), memoryview(bytearray(8))[::2], released, 'abcd', bytearray(4)]:
        print(show(struct.pack_into, 'b', target, 0, 1), end=' | ')
    print()
    ba = bytearray(8)
    print(show(struct.pack_into, 'ii', ba, 0, 7, 'x'), ba)
    ba = bytearray(b'\x01\x00\x02\x00\x03\x00')
    it = struct.iter_unpack('<h', ba)
    print(next(it), it.__length_hint__())
    ba[2] = 9
    print(list(it), it.__length_hint__(), list(it), show(next, it), iter(it) is it)
    print(show(struct.iter_unpack, 'x', 3), show(struct.iter_unpack, '', b''), show(struct.iter_unpack, '0i', b''), show(struct.iter_unpack, '<h', b'abc'), list(struct.iter_unpack('<2b', bytes(range(6)))))


def struct_objects():
    s = struct.Struct('<2h')
    print(s, s.format, s.size, s.pack(1, -2), s.unpack(b'\x01\x00\xfe\xff'), s.__sizeof__(), struct.Struct('3i5s').__sizeof__(), struct.Struct('').__sizeof__(), struct.Struct(b'>q').format)
    s.__init__('>q')
    print(s, s.size, show(s.__init__, 'z'), s.format, show(s.__init__, '\udc00'), show(s.__init__), s.format)
    print(show(setattr, s, 'size', 4), show(delattr, s, 'format'), show(setattr, s, 'x', 1), show(delattr, s, 'x'))
    u = struct.Struct.__new__(struct.Struct)
    print(u.size, show(lambda: u.format), show(repr, u), show(u.pack), show(u.unpack, b''), show(u.iter_unpack, b''), show(u.__sizeof__), show(u.pack_into, bytearray(1), 0))
    print(show(struct.Struct.__new__), show(struct.Struct.__new__, 1), show(struct.Struct.__new__, int), show(struct.Struct.__init__, 1, 'i'), show(struct.Struct.unpack, 1, b''))
    print(show(struct.Struct), show(struct.Struct, 'i', 'j'), show(struct.Struct, format='i'), show(struct.Struct, fmt='i'), show(struct.Struct, 1), show(struct.Struct, bytearray(b'i')), show(struct.Struct, 'é'))

    class Pair(struct.Struct):
        def __init__(self, fmt, extra):
            super().__init__(fmt)
            self.extra = extra

        def total(self, b):
            return sum(self.unpack(b))

    p = Pair('<2h', 'x')
    print(p, p.extra, p.size, p.total(b'\x01\x00\x02\x00'), type(p).__mro__, isinstance(p, struct.Struct), p.pack(3, 4), p.__sizeof__())
    p.z = 5
    print(p.__dict__, show(delattr, p, 'nope'), show(setattr, p, 'size', 1))

    class Fixed(struct.Struct):
        __slots__ = ()

        def __new__(cls, fmt):
            self = super().__new__(cls)
            struct.Struct.__init__(self, '>' + fmt)
            return self

        def __init__(self, fmt):
            pass

    class Shown(struct.Struct):
        def __repr__(self):
            return 'Shown<' + super().__repr__() + '>'

    print(Fixed('h'), Fixed('h').pack(1), repr(Shown('i')), str(Shown('2c')))
    print(show(copy.copy, s), show(copy.copy, p), show(copy.copy, struct.iter_unpack('b', b'x')), s.__getstate__())


def methods():
    s = struct.Struct('i')
    print(show(struct.pack), show(struct.pack, 'i', x=1), show(struct.pack_into), show(struct.unpack, 'i'), show(struct.unpack, 'i', b'', 1), show(struct.iter_unpack, 'i'))
    print(show(struct.calcsize), show(struct.calcsize, 'i', 'j'), show(struct.calcsize, format='i'), show(struct._clearcache, 1))
    print(show(struct.unpack_from, 'i'), show(struct.unpack_from, 'i', b'abcd', 0, 1), show(struct.unpack_from, 'i', buffer=b'abcd'), show(struct.unpack_from, 'i', b'abcd', offset=0), show(struct.unpack_from, 'i', b'abcd', offsett=0), show(struct.unpack_from, format='i', buffer=b'abcd'))
    print(show(s.unpack), show(s.unpack, 1, 2), show(s.unpack, buffer=b'abcd'), show(s.iter_unpack), show(s.pack, x=1), show(s.pack_into, x=1), show(s.unpack_from), show(s.unpack_from, b'abcd', 0, 1), show(s.unpack_from, buffer=b'abcd', offset=0), show(s.__sizeof__, 1))
    print(show(s.pack, 1, 2), show(s.pack_into), show(s.pack_into, bytearray(4)), show(s.pack_into, bytearray(4), 0), show(s.pack_into, bytearray(4), 0, 1, 2), show(struct.pack, '10000000000x'))


def cache_and_errors():
    struct._clearcache()
    for i in range(150):
        struct.calcsize('%dx' % i)
    print(struct.calcsize('i'), struct.calcsize(b'i'), show(struct.calcsize, bytearray(b'i')), show(struct.calcsize, None))

    class Fmt(str):
        def __hash__(self):
            return hash(str(self))

    class NoHash(str):
        def __hash__(self):
            raise ValueError('no hash')

    class B(bytes):
        pass

    print(struct.calcsize(Fmt('2i')), struct.pack(Fmt('<h'), 7), show(struct.calcsize, NoHash('i')), struct.Struct(NoHash('i')), struct.Struct(B(b'<i')).format, struct.pack('2s', B(b'ab')), struct.unpack('h', B(b'ab')))
    print(issubclass(struct.error, Exception), repr(struct.error('x')), str(struct.error('a', 'b')))
    try:
        struct.pack('i', 'x')
    except struct.error as e:
        print('caught', e, e.args, type(e).__name__)
    try:
        1 / 0
    except ZeroDivisionError:
        try:
            struct.pack('b', 300)
        except struct.error as e:
            print(repr(e), repr(e.__context__), e.__suppress_context__)


def main():
    for part in [names, sizes, integers, floats, strings_and_bools, buffers, struct_objects, methods, cache_and_errors]:
        part()


main()
