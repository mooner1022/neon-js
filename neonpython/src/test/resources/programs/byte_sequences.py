# bytes, bytearray and memoryview as CPython has them: bytes' methods on both (bytearrays from a bytearray's), bytearray's
# changes, % formatting, memoryview's views and casts, str(bytes, encoding). (A bytearray is resizable while views of it
# live here: docs/PYTHON_PLATFORM.md §12.2.)
def t(label, f):
    try:
        print(label, "->", f())
    except Exception as e:
        print(label, "!!", type(e).__name__, e)


def methods(B):
    n = B.__name__
    s = B(b'Hello, World  hello\tx\n')
    t(n + " case", lambda: (s.upper(), s.lower(), s.swapcase(), s.title(), s.capitalize(), B(b'abc def').title()))
    t(n + " pad", lambda: (B(b'ab').center(6, b'*'), B(b'ab').ljust(5), B(b'ab').rjust(5, b'-'), B(b'-12').zfill(6), B(b'a\tb').expandtabs(4)))
    t(n + " strip", lambda: (s.strip(), s.lstrip(b'He'), s.rstrip(b'\n'), B(b'xxaxx').strip(b'x'), B(b'  a ').strip(None)))
    t(n + " find", lambda: (s.find(b'o'), s.find(b'o', 5), s.rfind(b'o'), s.find(111), s.index(b'W'), s.rindex(b'l'), s.count(b'l'), s.count(108), s.find(b'zz')))
    t(n + " index err", lambda: s.index(b'zz'))
    t(n + " split", lambda: (s.split(), s.split(b','), s.split(b'l', 2), s.rsplit(b'l', 2), B(b'a\nb\r\nc').splitlines(), B(b'a\nb\r\nc').splitlines(True), B(b'').split()))
    t(n + " part", lambda: (s.partition(b', '), s.rpartition(b'l'), s.partition(b'zz'), s.rpartition(b'zz')))
    t(n + " join", lambda: (B(b'-').join([b'a', bytearray(b'b'), memoryview(b'c')]), B(b'').join([])))
    t(n + " join err", lambda: B(b'-').join([b'a', 'b']))
    t(n + " replace", lambda: (s.replace(b'l', b'L'), s.replace(b'l', b'', 2), B(b'abc').replace(b'', b'-'), s.removeprefix(b'Hel'), s.removesuffix(b'x\n')))
    t(n + " starts", lambda: (s.startswith(b'He'), s.startswith((b'x', b'Hello')), s.endswith(b'\n'), s.startswith(b'llo', 2), s.endswith(b'lo', 0, 5)))
    t(n + " is", lambda: [B(x).isalnum() for x in (b'ab1', b'a b', b'')] + [B(b'ab').isalpha(), B(b'12').isdigit(), B(b'ab').islower(), B(b'AB').isupper(), B(b' \t').isspace(), B(b'Ab Cd').istitle(), B(b'\x80').isascii()])
    t(n + " hex", lambda: (B(b'\x01\xab').hex(), B(b'\x01\xab\xcd').hex(':'), B(b'\x01\xab\xcd').hex('-', 2), B.fromhex('01 ab'), B.fromhex('')))
    t(n + " fromhex err", lambda: B.fromhex('0g'))
    t(n + " translate", lambda: (B(b'abc').translate(bytes.maketrans(b'ab', b'xy')), B(b'abc').translate(None, b'b'), bytes.maketrans(b'a', b'b')[97]))
    t(n + " decode", lambda: (B(b'\xc3\xa9').decode(), B(b'\xe9').decode('latin-1'), B(b'abc').decode('ascii')))
    t(n + " decode err", lambda: B(b'\xff').decode('utf-8'))
    t(n + " ops", lambda: (B(b'ab') + b'c', B(b'ab') * 2, 2 * B(b'ab'), b'a' in B(b'xa'), 97 in B(b'a'), B(b'ab') == b'ab', B(b'ab') < B(b'b'), B(b'ab')[0], B(b'ab')[-1], B(b'abcd')[1:3], B(b'abcd')[::-1], list(B(b'ab'))))
    t(n + " ops err", lambda: B(b'a') + 'b')
    t(n + " contains err", lambda: 256 in B(b'a'))
    t(n + " getitem err", lambda: B(b'a')[5])
    t(n + " repr", lambda: (repr(B(b"a'b")), repr(B(b'a"b')), repr(B(b'\x00\n\x7f\x80')), repr(B(b"'\""))))
    t(n + " mod", lambda: (B(b'%s-%d-%x-%c-%r-%%') % (b'ab', 12, 255, 65, 'q'), B(b'%b') % bytearray(b'z'), B(b'%5.1f|%-4d|') % (3.14159, 7), B(b'%c') % b'z'))
    t(n + " mod err", lambda: B(b'%s') % 'str')
    t(n + " ctor", lambda: (B(3), B([1, 2]), B(b'x'), B('é', 'utf-8'), B(range(3)), B(memoryview(b'mv')), B()))
    t(n + " ctor err", lambda: B('abc'))
    t(n + " ctor err2", lambda: B([256]))
    t(n + " ctor err3", lambda: B(-1))
    t(n + " misc", lambda: (len(B(b'abc')), bool(B()), B(b'abc').__len__(), type(iter(B(b''))).__name__, B(b'ab') == bytearray(b'ab'), b'ab' == memoryview(b'ab')))


def mutable():
    a = bytearray(b'abc')
    a.append(100); a.extend(b'ef'); a.insert(0, 90); a[1] = 65; print(a, len(a))
    a[1:3] = b'XYZ'; print(a); del a[0]; print(a); del a[::2]; print(a)
    print(a.pop(), a.pop(0), a); a.extend(b"xyz"); a.remove(121); print(a)
    a.reverse(); print(a); a += b'!!'; print(a); a *= 2; print(a)
    c = a.copy(); a.clear(); print(a, c)
    t("ba errs", lambda: [bytearray().pop()])
    t("ba errs2", lambda: bytearray(b'a').remove(98))
    t("ba errs3", lambda: bytearray(b'a').append(256))
    t("ba errs4", lambda: bytearray(b'a').__setitem__(0, b'x'))
    t("ba errs5", lambda: hash(bytearray()))
    t("ba slice ext", lambda: (lambda b: (b.__setitem__(slice(None, None, 2), b'XY'), b)[1])(bytearray(b'abcd')))
    t("ba slice ext err", lambda: (lambda b: b.__setitem__(slice(None, None, 2), b'X'))(bytearray(b'abcd')))
    t("ba resize", lambda: (lambda b: (b.resize(5), b, b.resize(1), b)[1:])(bytearray(b'abc')))
    t("ba types", lambda: (type(bytearray(b'a') + b'b').__name__, type(b'b' + bytearray(b'a')).__name__, type(bytearray(b'a').upper()).__name__, type(bytearray(b'a,b').split(b',')[0]).__name__))
    t("ba repr", lambda: (repr(bytearray(b'a\x00')), repr(bytearray())))
    t("ba iter", lambda: type(iter(bytearray())).__name__)
    t("ba setitem neg", lambda: (lambda b: (b.__setitem__(-1, 0), b)[1])(bytearray(b'ab')))
    t("ba from int list", lambda: bytearray([1, 2, 255]))
    t("ba eq", lambda: (bytearray(b'a') == bytearray(b'a'), bytearray(b'a') < b'b', bytearray(b'ab') > bytearray(b'a')))
    t("ba fromhex", lambda: (bytearray.fromhex('ff'), type(bytearray.fromhex('ff')).__name__))

    class BA(bytearray):
        pass

    x = BA(b'sub')
    t("ba sub", lambda: (x, type(x).__name__, type(x.upper()).__name__, type(x + b'!').__name__, x == b'sub', len(x)))


def views():
    m = memoryview(b'abcdef')
    t("mv attrs", lambda: (m.format, m.itemsize, m.ndim, m.shape, m.strides, m.readonly, m.nbytes, m.obj, m.c_contiguous, m.contiguous))
    t("mv ops", lambda: (m[0], m[-1], m[1:3].tobytes(), bytes(m[::2]), m.tolist()[:3], m.hex(), len(m), m == b'abcdef', m[1:3] == b'bc', list(m[:2])))
    t("mv ro", lambda: m.__setitem__(0, 1))
    w = bytearray(b'xyz')
    v = memoryview(w)
    v[0] = 65
    v[1:3] = b'BC'
    print(w, v.readonly, v.tobytes(), bytes(v.toreadonly()))
    v.release()
    w.append(33)
    print(w)
    t("mv released", lambda: v[0])
    t("mv released repr", lambda: repr(v).startswith('<released memory at 0x'))
    t("mv repr", lambda: repr(memoryview(b'')).startswith('<memory at 0x'))
    with memoryview(bytearray(b'ctx')) as cm:
        print(cm.tobytes())
    t("mv ctx released", lambda: cm.tobytes())
    c = memoryview(b'\x01\x00\x02\x00').cast('H')
    t("mv cast", lambda: (c.format, c.itemsize, len(c), c.tolist(), c[1], c.nbytes, c.cast('B').tolist(), memoryview(b'ab').cast('c').tolist()))
    t("mv cast err", lambda: memoryview(b'abc').cast('H'))
    t("mv hash", lambda: (hash(memoryview(b'ab')) == hash(b'ab')))
    t("mv hash err", lambda: hash(memoryview(bytearray(b'ab'))))
    t("mv set err", lambda: memoryview(bytearray(b'ab')).__setitem__(0, 300))
    t("mv set err2", lambda: memoryview(bytearray(b'ab')).__setitem__(slice(0, 2), b'abc'))
    t("mv count", lambda: (memoryview(b'abca').count(97), memoryview(b'abca').index(98)))
    t("mv of mv", lambda: (memoryview(memoryview(b'q')).tobytes(), memoryview(b'xy').obj))
    t("mv ctor err", lambda: memoryview('str'))
    t("mv iter", lambda: (list(memoryview(b'ab')), type(iter(memoryview(b''))).__name__))


def m(v):
    match v:
        case bytearray(x):
            return ('ba', x)
        case bytes(x):
            return ('b', x)


def interplay():
    t("str dec", lambda: (str(b'\xc3\xa9', 'utf-8'), str(bytearray(b'ab'), 'ascii'), str(b'x'), str(bytearray(b'y'))))
    t("from_bytes", lambda: (int.from_bytes(bytearray(b'\x01\x00'), 'little'), int.from_bytes(memoryview(b'\x02'), 'big'), (258).to_bytes(2, 'big')))
    t("set", lambda: (len({b'a', b'a', b'b'}), {b'k': 1}[b'k'], sorted([b'b', b'a', b'ab'])))
    t("ba key", lambda: {bytearray(b'a'): 1})
    t("cmp", lambda: (b'a' < bytearray(b'b'), bytearray(b'a') == b'a', b'a' != bytearray(b'a'), memoryview(b'a') == bytearray(b'a')))
    t("isinstance", lambda: (isinstance(bytearray(), bytes), isinstance(memoryview(b''), memoryview), issubclass(bytearray, object)))
    t("decode errors", lambda: (b'a\xffb'.decode('utf-8', 'ignore'), b'a\xffb'.decode('utf-8', errors='replace'), b'abc'.decode(encoding='ascii')))
    t("encode", lambda: ('é'.encode(), 'abc'.encode('ascii'), bytes('é', encoding='utf-8')))
    t("bytes kw", lambda: (bytes(source=b'x'), bytearray(source=[1]), bytes(b'z', encoding='utf-8')))
    t("bool idx", lambda: (b'ab'[True], bytearray(b'ab')[False]))
    t("join gen", lambda: b','.join(x for x in [b'1', b'2']))
    t("unpack", lambda: (lambda a, b: (a, b))(*b'xy'))
    t("max", lambda: (max(b'abc'), sum(bytearray(b'\x01\x02')), list(reversed(b'ab')), list(reversed(bytearray(b'ab')))))
    t("match", lambda: (lambda v: [r for r in [v] if True])(bytearray(b'm')))
    t("match ba", lambda: (m(bytearray(b'q')), m(b'r')))
    t("fmt", lambda: (f'{b"x"}', format(bytearray(b'y')), '%s' % b'z', '%r' % bytearray(b'w')))

methods(bytes)
methods(bytearray)
mutable()
views()
interplay()
