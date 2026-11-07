# zlib (written in Kotlin over java.util.zip as CPython writes it in C over zlib), and CPython's own gzip over it: the
# zlib, gzip and raw containers (their headers, trailers, checks and messages), wbits, levels, strategies, dictionaries,
# the flush modes, max_length and unconsumed_tail, unused_data, eof, copies, _ZlibDecompressor, CPython's errors.
# Compressed bytes are printed only where they do not depend on the deflate implementation (level 0, headers,
# trailers, flush markers); the rest is checked by round trips and by decompressing what CPython's zlib made.
import codecs
import copy
import gzip
import io
import zlib

DATA = (bytes(range(256)) * 30 + b'hello world, ' * 700 + bytes((i * 7919) % 251 for i in range(5000)))
HELLO = bytes.fromhex('78dacb48cdc9c95728cf2fca4951c818016c00557659b1')
WITH_DICT = bytes.fromhex('78f91a0b045dcb403075148a133353144a3252217c3d8561240900854b5201')
RAW = bytes.fromhex('6360646266616563e7e0e4e2e6e1e5e3171014121611151397909492969195935750545256515553d7d0d4d2d6d1d5d33730343236313533b7b0b4b2'
                    'b6b1b5b37770747276717573f7f0f4f2f6f1f5f30f080c0a0e090d0b8f888c8a8e898d8b4f484c4a4e494d4bcfc8cccacec9cdcb2f282c2a2e292d2b'
                    'afa8acaaaea9adab6f686c6a6e696d6befe8eceaeee9edeb9f3071d2e42953a74d9f3173d6ec3973e7cd5fb070d1e2254b972d5fb172d5ea356bd7ad'
                    'dfb071d3e62d5bb76ddfb173d7ee3d7bf7ed3f70f0d0e123478f1d3f71f2d4e93367cf9dbf70f1d2e52b57af5dbf71f3d6ed3b77efdd7ff0f0d1e3274f'
                    '9f3d7ff1f2d5eb376fdfbdfff0f1d3e72f5fbf7dfff1f3d7ef3f7ffffd6718f5ffa8ff47b0ff01')
GZ = bytes.fromhex('1f8b0800d202964902ff4bafca2c50c84dcd4d4a2de24aa7231b005899db8178000000')
FLUSHED = bytes.fromhex('789c4acb2c2a2e5128482c2ad15100000000ffff2a4e4dcecf4b017300000000ffffd351c8492c2e0100a1550a9f')


def show(f, *args, **kw):
    try:
        r = f(*args, **kw)
        return r.hex() if isinstance(r, bytes) and len(r) < 60 else repr(r)[:80]
    except Exception as e:
        return '%s: %s' % (type(e).__name__, e)


def names():
    print([n for n in dir(zlib) if not n.startswith('__')], zlib.__version__, zlib.ZLIB_VERSION, zlib.ZLIB_RUNTIME_VERSION)
    print(zlib.MAX_WBITS, zlib.DEFLATED, zlib.DEF_MEM_LEVEL, zlib.DEF_BUF_SIZE, zlib.Z_NO_COMPRESSION, zlib.Z_BEST_SPEED, zlib.Z_BEST_COMPRESSION,
          zlib.Z_DEFAULT_COMPRESSION, zlib.Z_FILTERED, zlib.Z_HUFFMAN_ONLY, zlib.Z_RLE, zlib.Z_FIXED, zlib.Z_DEFAULT_STRATEGY, zlib.Z_NO_FLUSH,
          zlib.Z_PARTIAL_FLUSH, zlib.Z_SYNC_FLUSH, zlib.Z_FULL_FLUSH, zlib.Z_FINISH, zlib.Z_BLOCK, zlib.Z_TREES)
    print(zlib.__doc__.splitlines()[:2], type(zlib.compressobj()), type(zlib.decompressobj()), zlib._ZlibDecompressor, zlib.error.__mro__, zlib.error.__module__)
    print(show(type(zlib.compressobj())), show(type(zlib.decompressobj())), zlib._ZlibDecompressor.__doc__.splitlines()[0], type(zlib.compressobj()).__module__)
    try:
        class S(zlib._ZlibDecompressor):
            pass
    except TypeError as e:
        print(e)


def checksums():
    print(zlib.crc32(b'abc'), zlib.crc32(b'abc', -1), zlib.crc32(b'abc', 2 ** 40 + 5), show(zlib.crc32, b'abc', 1.5), zlib.adler32(b'abc'), zlib.adler32(b'abc', -1),
          show(zlib.adler32, 'abc'), show(zlib.crc32), show(zlib.crc32, b'', 1, 2), show(zlib.crc32, b'', value=1), show(zlib.adler32, b'', value=1))
    print(zlib.crc32(DATA), zlib.adler32(DATA), zlib.adler32(DATA[1000:], zlib.adler32(DATA[:1000])) == zlib.adler32(DATA), zlib.adler32(b'\xff' * 100000), zlib.crc32(memoryview(b'xyz')))


def one_shot():
    for level in range(-1, 10):
        c = zlib.compress(DATA, level)
        print(level, c[:2].hex(), c[-4:].hex(), zlib.decompress(c) == DATA, zlib.decompress(c, 47) == DATA)
    for wbits in (9, 10, 12, 14, 15, -9, -12, -15, 25, 28, 31, 8):
        c = zlib.compress(DATA, 6, wbits)
        head = c[:2].hex() if 8 <= wbits <= 15 else c[:10].hex() if wbits > 15 else ''
        print(wbits, head, show(lambda: zlib.decompress(c, wbits) == DATA), show(zlib.decompress, c, 15)[:40], show(zlib.decompress, c, 9 if wbits > 0 else -9)[:40])
    print(zlib.compress(b'stored data', 0).hex(), zlib.compress(b'', 0).hex(), zlib.compress(b'', 0, -15).hex(), zlib.compress(b'x', 0, 31).hex())
    print(zlib.decompress(HELLO), zlib.decompress(RAW, -15) == bytes(range(256)) * 4, zlib.decompress(GZ, 31), zlib.decompress(GZ, 47) == zlib.decompress(GZ, 16 + 15))
    print(zlib.decompress(FLUSHED), zlib.decompress(HELLO, 0)[:5], zlib.decompress(HELLO, bufsize=1)[:5], zlib.decompress(DATA and zlib.compress(DATA), 15, 0) == DATA)


def one_shot_errors():
    for w in (-16, -8, -7, 0, 1, 7, 8, 16, 24, 32, 47, 48, 2 ** 31):
        print(w, show(zlib.compress, b'x', 6, w)[:24], show(zlib.decompress, HELLO, w)[:60])
    for lv in (-2, 10, 11):
        print(lv, show(zlib.compress, b'x', lv))
    bad = [b'', b'x', b'x\x9c', b'x\x9cK', HELLO[:-1], HELLO[:-4], HELLO[:10], b'\x78\x9d' + HELLO[2:], b'\x79\x9c' + HELLO[2:], b'\x88\x9c' + HELLO[2:], b'\x77\x9c',
           b'xyz', HELLO + b'extra', HELLO[:2] + b'\xff' + HELLO[3:], HELLO[:-1] + bytes([HELLO[-1] ^ 1]), WITH_DICT]
    for b in bad:
        print(b[:6].hex(), show(zlib.decompress, b))
    for b in [GZ[:-1], GZ[:-5], GZ[:-8], GZ[:3], b'\x1f\x8b\x07', b'\x1f\x8b\x08\xe0', GZ[:-8] + b'\0\0\0\0' + GZ[-4:], GZ[:-4] + b'\0\0\0\0', GZ + b'tail', HELLO]:
        print(b[:4].hex(), show(zlib.decompress, b, 31), show(zlib.decompress, b, 47))
    for b in [RAW[:-1], RAW + b'tail', b'\xff\xff', b'\x07', b'']:
        print(b[:4].hex(), show(zlib.decompress, b, -15)[:70])
    print(show(zlib.decompress, HELLO, 15, -1), show(zlib.decompress, HELLO, 15, 2 ** 63), show(zlib.compress, 's'), show(zlib.decompress, 's'), show(zlib.compress, b'x', level=None),
          show(zlib.compress, b'x', 1, 2, 3), show(zlib.compress, data=b'x'), show(zlib.decompress, b'x', bufsize='a'))


def gzip_headers():
    body = zlib.compress(b'payload', 6, -15)
    trailer = zlib.crc32(b'payload').to_bytes(4, 'little') + (7).to_bytes(4, 'little')
    for flg, extra in [(0, b''), (4, b'\x03\x00abc'), (8, b'name\x00'), (16, b'comment\x00'), (8 | 16 | 4, b'\x01\x00znm\x00cm\x00')]:
        head = b'\x1f\x8b\x08' + bytes([flg]) + b'\0\0\0\0\0\x03' + extra
        print(flg, show(zlib.decompress, head + body + trailer, 31))
        hc = (zlib.crc32(head[:3] + bytes([flg | 2]) + head[4:]) & 0xffff).to_bytes(2, 'little')
        good = head[:3] + bytes([flg | 2]) + head[4:] + hc
        print(show(zlib.decompress, good + body + trailer, 31), show(zlib.decompress, good[:-1] + b'\0' + body + trailer, 31))
        d = zlib.decompressobj(31)
        stream = good + body + trailer
        print(b''.join(d.decompress(stream[i:i + 1]) for i in range(len(stream))), d.eof, d.unused_data)


def compressors():
    for kw in [dict(level=10), dict(level=-2), dict(method=7), dict(wbits=8), dict(wbits=-8), dict(wbits=24), dict(wbits=7), dict(wbits=16), dict(wbits=32),
               dict(memLevel=0), dict(memLevel=10), dict(memLevel=9), dict(memLevel=1), dict(strategy=-1), dict(strategy=5), dict(strategy=4), dict(strategy=3),
               dict(strategy=2), dict(strategy=1), dict(zdict='abc'), dict(zdict=b'abc', wbits=31), dict(zdict=b'abc', wbits=-15), dict(zdict=bytearray(b'x')), dict(levl=1)]:
        try:
            c = zlib.compressobj(**kw)
            out = c.compress(DATA) + c.flush()
            wb = kw.get('wbits', 15)
            print(kw, out[:2].hex() if wb > 0 else '', out[-4:].hex() if wb > 0 else '', zlib.decompressobj(wb, kw.get('zdict', b'')).decompress(out) == DATA)
        except Exception as e:
            print(kw, '%s: %s' % (type(e).__name__, e))
    print(show(zlib.compressobj, 1, 2, 3, 4, 5, 6, 7), show(zlib.compressobj, wbits='a'), show(lambda: zlib.compressobj().compress('s')))


def flushes():
    for m in range(-1, 9):
        c = zlib.compressobj()
        first = c.compress(b'hello world ' * 20)
        f1 = show(c.flush, m)
        f2 = show(c.flush, m)
        rest = c.flush() if m != zlib.Z_FINISH else b''
        print(m, first.hex(), f1[-8:] if m in (2, 3) else f1 if 'Error' in f1 or m in (0, -1) else '', f2, rest[-4:].hex())
    c = zlib.compressobj()
    print(show(c.compress, b''), show(c.compress, b''), show(c.flush, 2), show(c.flush, 2), show(c.compress, b''), show(c.flush, 2), show(c.flush))
    c = zlib.compressobj()
    c.compress(b'abc')
    print(show(c.flush), show(c.compress, b'x'), show(c.flush), show(c.flush, 2), show(c.copy))
    c = zlib.compressobj()
    c.compress(b'')
    c.compress(b'')
    print(show(c.flush), show(c.compress, b'x'), show(c.flush, 2))
    c = zlib.compressobj()
    print(show(c.flush, 9), show(c.flush, -1), show(c.flush, 2 ** 31), show(c.flush, 'x'), show(c.flush, mode=2), show(c.compress), show(c.compress, b'a', b'b'))
    c = zlib.compressobj(9, zlib.DEFLATED, -12, 9, zlib.Z_FILTERED)
    out = c.compress(b'xyz' * 30) + c.flush(zlib.Z_SYNC_FLUSH) + c.compress(b'q') + c.flush(zlib.Z_FULL_FLUSH) + c.flush()
    print(zlib.decompress(out, -12)[-5:], len(zlib.decompress(out, -12)))


def copies():
    for wb in (15, -15, 31, 10, -12):
        for lvl in (0, 1, 9):
            c = zlib.compressobj(lvl, 8, wb)
            head = c.compress(DATA[:5000])
            c2 = c.copy()
            c3 = copy.copy(c)
            a = head + c.compress(DATA[5000:]) + c.flush()
            b = head + c2.compress(DATA[5000:]) + c2.flush()
            mid = c3.flush(zlib.Z_SYNC_FLUSH)
            c4 = copy.deepcopy(c3)
            x = head + mid + c3.compress(b'tail') + c3.flush()
            y = head + mid + c4.compress(b'tail') + c4.flush()
            print(wb, lvl, a == b, x == y, zlib.decompress(a, wb) == DATA, zlib.decompress(x, wb) == DATA[:5000] + b'tail', show(c.copy))
    c = zlib.compressobj()
    print(show(c.copy, 1), show(c.__deepcopy__), show(c.copy, x=1), type(c.__copy__()).__name__)


def decompressors():
    z = zlib.compress(DATA * 3, 9)
    for ml in (0, 1, 100, 5000):
        d = zlib.decompressobj()
        out = d.decompress(z[:3000], ml)
        e = d.copy()
        f = copy.deepcopy(d)
        r1 = out + d.decompress(d.unconsumed_tail + z[3000:]) + d.flush()
        r2 = out + e.decompress(e.unconsumed_tail + z[3000:]) + e.flush()
        r3 = out + f.decompress(f.unconsumed_tail + z[3000:]) + f.flush()
        print(ml, len(out) if ml else len(out) > 5000, r1 == DATA * 3, r2 == r1, r3 == r1, d.eof, e.eof)
    for wb in (31, -15, 47, 15):
        zz = zlib.compress(DATA, 6, 31 if wb == 47 else wb)
        d = zlib.decompressobj(wb)
        res = []
        for i in range(0, len(zz), 37):
            res.append(d.decompress(d.unconsumed_tail + zz[i:i + 37], 211))
        while d.unconsumed_tail:
            res.append(d.decompress(d.unconsumed_tail, 211))
        res.append(d.flush())
        print(wb, b''.join(res) == DATA, d.eof, max(len(r) for r in res), d.unused_data)
    d = zlib.decompressobj()
    print(show(d.decompress, HELLO + b'xy'), d.unused_data, d.eof, show(d.decompress, b'more'), d.unused_data, show(d.flush), show(d.decompress, b'q'), show(d.flush), show(d.copy))
    d = zlib.decompressobj()
    print(show(d.decompress, HELLO[:20]), show(d.flush), d.eof, show(d.decompress, HELLO[20:]), d.eof, show(d.flush), d.eof, show(d.decompress, b'zz'), show(d.copy))
    d = zlib.decompressobj()
    print(show(d.decompress, HELLO, -1), show(d.decompress, HELLO, 2 ** 63), show(d.flush, 0), show(d.flush, -1), show(d.flush, 1, 2), show(d.decompress), show(d.flush, length=5))
    d = zlib.decompressobj()
    bad = HELLO[:2] + b'\xff' + HELLO[3:]
    print(show(d.decompress, bad), d.unconsumed_tail, show(d.decompress, b'abc'), show(d.flush), type(d.copy()).__name__)


def dictionaries():
    d = zlib.decompressobj()
    print(show(d.decompress, WITH_DICT), d.unconsumed_tail.hex(), d.unused_data, d.eof)
    d = zlib.decompressobj(zdict=b'hello worlx')
    print(show(d.decompress, WITH_DICT), d.unconsumed_tail, d.eof)
    d = zlib.decompressobj(zdict=b'hello world')
    print(show(d.decompress, WITH_DICT), d.eof)
    d = zlib.decompressobj(zdict=bytearray(b'hello world'))
    print(b''.join(d.decompress(WITH_DICT[i:i + 3]) for i in range(0, len(WITH_DICT), 3)), d.eof)
    for kw in [dict(wbits=7), dict(wbits=8), dict(wbits=-8), dict(wbits=0), dict(wbits=16), dict(wbits=48), dict(wbits=47), dict(wbits=-16), dict(zdict='abc'),
               dict(zdict=1), dict(zdict=None), dict(zdict=b'abc', wbits=-15), dict(zdict=memoryview(b'ab'))]:
        print(kw if 'zdict' not in kw or not isinstance(kw['zdict'], memoryview) else 'memoryview', show(zlib.decompressobj, **kw)[:25])
    c = zlib.compressobj(wbits=-15, zdict=b'hello world')
    raw = c.compress(b'hello world hello') + c.flush()
    d = zlib.decompressobj(-15, zdict=bytearray(b'hello world'))
    print(d.decompress(raw), show(zlib.decompress, raw, -15))
    c = zlib.compressobj(zdict=b'hello world, ' * 10)
    zd = c.compress(DATA) + c.flush()
    d = zlib.decompressobj(zdict=b'hello world, ' * 10)
    p = d.decompress(zd[:100])
    e = d.copy()
    print(zd[:6].hex(), p + e.decompress(zd[100:]) == DATA, p + d.decompress(zd[100:]) == DATA)


def decompressor_objects():
    Z = zlib._ZlibDecompressor
    print(show(Z, 1, 2, 3), show(Z, x=1), show(Z, 15, wbits=15), show(Z, 1.5), show(Z, 2 ** 31), show(Z, -2 ** 31 - 1), show(Z, 2 ** 64), show(Z, '15'),
          show(Z, wbits=1), type(Z(zdict=3)).__name__, show(Z, -15, 3))
    zz = Z()
    print(show(zz.decompress, HELLO), zz.eof, zz.unused_data, zz.needs_input, show(zz.decompress, b'x'))
    zz = Z()
    print(show(zz.decompress, HELLO + b'tail'), zz.unused_data, show(setattr, zz, 'eof', 1), show(setattr, zz, 'x', 1))
    d = Z()
    print(show(d.decompress, WITH_DICT), d.needs_input, d.eof, show(d.decompress, b''), d.needs_input)
    d = Z()
    print(show(d.decompress, HELLO, 0), d.needs_input, show(d.decompress, b'', 5), d.needs_input, show(d.decompress, b'', 0), d.needs_input, show(d.decompress, b''), d.eof)
    dd = Z(-15)
    res = []
    ins = []
    for i in range(0, len(RAW), 53):
        res.append(dd.decompress(RAW[i:i + 53], 97))
        ins.append(dd.needs_input)
    while not dd.eof and not dd.needs_input:
        res.append(dd.decompress(b'', 97))
    print(b''.join(res) == bytes(range(256)) * 4, dd.eof, ins, max(len(r) for r in res))
    zz = zlib.compress(DATA, 6)
    dd = Z()
    res = [dd.decompress(zz[i:i + 53], 97) for i in range(0, len(zz), 53)]
    while not dd.eof and not dd.needs_input:
        res.append(dd.decompress(b'', 97))
    print(b''.join(res) == DATA, dd.eof, max(len(r) for r in res))
    dd = Z(31)
    print(dd.decompress(zlib.compress(DATA, 6, 31) + b'trailing', -1) == DATA, dd.eof, dd.unused_data, dd.needs_input, show(dd.decompress, data=b''))


def attributes():
    c = zlib.compressobj()
    d = zlib.decompressobj()
    for o, n in ((c, 'x'), (d, 'eof'), (d, 'unused_data'), (d, 'unconsumed_tail'), (d, 'x')):
        try:
            setattr(o, n, 1)
            print('set')
        except AttributeError as e:
            print(e)
    try:
        del d.eof
    except AttributeError as e:
        print(e)
    print(type(d.eof).__name__, type(d.unused_data).__name__, d.unconsumed_tail)
    d.decompress(HELLO + b'yy')
    print(d.unused_data is d.unused_data, d.unconsumed_tail is d.unconsumed_tail, show(copy.copy, zlib._ZlibDecompressor()), show(copy.deepcopy, zlib._ZlibDecompressor()),
          show(c.__reduce_ex__, 2), show(d.__reduce_ex__, 4), show(zlib._ZlibDecompressor().__reduce_ex__, 3))


def gzip_module():
    g = gzip.compress(b'hello gzip' * 50, mtime=0)
    print(g[:10].hex(), g[-8:].hex(), gzip.decompress(g) == b'hello gzip' * 50, gzip.decompress(g + g) == b'hello gzip' * 100, gzip.compress(b'', compresslevel=1, mtime=12345)[:10].hex())
    print(gzip.decompress(GZ), show(gzip.decompress, g[:-1]), show(gzip.decompress, b'xx' + g), show(gzip.decompress, g[:-8] + b'\0\0\0\0' + g[-4:]), len(gzip.decompress(g + b'\0\0')),
          show(gzip.decompress, g + b'junk'))
    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode='wb', mtime=0, filename='name.txt') as f:
        f.write(b'line one\nline two\n')
        f.write(memoryview(b'line three\n'))
        print(f.tell(), f.mode)
    print(buf.getvalue()[:19])
    buf.seek(0)
    with gzip.GzipFile(fileobj=buf) as f:
        print(f.readline(), f.peek(3)[:3], f.read(4), f.tell(), f.readlines(), f.seekable(), f.seek(5), f.read(), f.mtime, f.name)
    buf.seek(0)
    with gzip.open(buf, 'rt', encoding='utf-8') as f:
        print(list(f))
    raw = io.BytesIO()
    with gzip.open(raw, 'wt', encoding='utf-8', newline='') as f:
        f.write('text\r\nmode\n')
    print(gzip.decompress(raw.getvalue()))


def gzip_streams():
    big = bytes(range(256)) * 400
    b2 = io.BytesIO()
    with gzip.GzipFile(fileobj=b2, mode='wb', compresslevel=6, mtime=1) as f:
        for i in range(0, len(big), 1000):
            f.write(big[i:i + 1000])
    b2.seek(0)
    with gzip.GzipFile(fileobj=b2) as f:
        parts = []
        while True:
            c = f.read(7000)
            if not c:
                break
            parts.append(len(c))
        print(len(parts), sum(parts), parts[:3])
    b2.seek(0)
    with gzip.GzipFile(fileobj=b2) as f:
        print(f.read1(10), len(f.read1()) + 10, f.tell(), f.seek(-100, 2) if False else f.seek(100), f.read(5))
    tr = io.BytesIO(gzip.compress(b'abc' * 1000)[:-20])
    with gzip.GzipFile(fileobj=tr) as f:
        print(show(f.read))
    with gzip.GzipFile(fileobj=io.BytesIO(b'not gzip data')) as f:
        print(show(f.read))
    print(show(gzip.open, io.BytesIO(), 'rb', encoding='x'), gzip.BadGzipFile.__mro__, gzip.READ, gzip.WRITE)
    zi = codecs.getincrementalencoder('zlib')()
    zd = codecs.getincrementaldecoder('zlib')()
    zz = zi.encode(b'abc' * 10) + zi.encode(b'', final=True)
    print(codecs.decode(codecs.encode(b'hello world', 'zlib'), 'zlib'), zd.decode(zz[:5]) + zd.decode(zz[5:], final=True), codecs.lookup('zip').name)
    import compression.gzip
    import compression.zlib
    print(compression.gzip.compress is gzip.compress, compression.zlib.compress is zlib.compress, compression.zlib.__doc__ == zlib.__doc__)


names()
checksums()
one_shot()
one_shot_errors()
gzip_headers()
compressors()
flushes()
copies()
decompressors()
dictionaries()
decompressor_objects()
attributes()
gzip_module()
gzip_streams()
