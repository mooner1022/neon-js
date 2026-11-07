"""Generates neonpython's CJK codecs' tables (resources dev/mooner/neonpython/cjkcodecs.bin) from CPython 3.14's own:
Modules/cjkcodecs/mappings_*.h (third_party/cpython, the release's sources), which _codecs_cn, _codecs_jp, _codecs_kr,
_codecs_tw, _codecs_hk and _codecs_iso2022 are built with. NeonPy's codecs (PyCjkCodecs.kt, PyIso2022.kt) are those
modules' C in Kotlin over the same tables, each read when a codec first needs it, and shared as CPython shares it
(jisx0208_decmap by shift_jis, cp932, euc_jp and the iso2022_jp codecs, big5_decmap by big5, cp950 and big5hkscs...).

The tables: each `__name[N]` array with its 256-row index `name[256]` (dbcs_index, unim_index, widedbcs_index: a row's
values, the first at `bottom`, through `top`), jisx0213_pair_encmap (pair_encodemap), gb18030's ranges of four-byte
sequences (gb18030_to_unibmp_ranges, its last row the end) and big5hkscs's plane-2 hints (big5hkscs_phint_*). The tool
checks that each index's rows take the array's values in order, each once, so a table is its rows' bounds and its
values. An encmap is mostly the inverse of decmaps (DERIVED): it is stored as what it is derived from and the values
that are not derived (73 of them in all), the tool checking that the derivation gives CPython's table.

Usage, from the repository root, with CPython 3.14.6: python -X utf8 -I neonpython/tools/gen_cjkcodecs.py
(with --digests: what CPython's codecs give for every code point and every short byte sequence, as PyCjkCodecsTest
digests it, printed as the test's table)

The file: b'NPCJ', a version byte, the number of tables, each table's name, kind, offset and length (from the end of
this directory), then the tables, each gzip'd. Kinds 0 and 2 (a decmap of ucs2_t, of Py_UCS4): the number of rows, each
row's first byte, bottom and top, then the values, each the difference from the one before it (from 0; modulo 2**16,
2**32 for kind 2) zigzag'd. Kind 1 (an encmap of DBCHAR): its rows so, the decmaps it is derived from (each its name, a
mask on its codes, which of big5hkscs's characters it takes: 0 all, 1 those of the BMP, 2 those past it), then the
values not derived, each its position (after the one before it) and its value. Kind 3 (jisx0213_pair_encmap): its
pairs, each the sequence and the code. Kind 4 (gb18030_to_unibmp_ranges): its rows, each first, last and base. Kind 5
(a hint): its bytes. Numbers are unsigned LEB128 varints; a name a varint length and ASCII bytes.
"""
import gzip
import os
import re
import sys

TOOLS = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(TOOLS))
SRC = os.path.join(ROOT, 'third_party/cpython/Modules/cjkcodecs')
OUT = os.path.join(ROOT, 'neonpython/src/main/resources/dev/mooner/neonpython/cjkcodecs.bin')
HEADERS = ['mappings_cn.h', 'mappings_jp.h', 'mappings_jisx0213_pair.h', 'mappings_kr.h', 'mappings_tw.h',
           'mappings_hk.h']
# cjkcodecs.h's: UNIINV, NOCHAR, MULTIC, DBCINV
SYMBOLS = {'U': 0xFFFE, 'N': 0xFFFF, 'M': 0xFFFE, 'D': 0xFFFD}
KIND_DEC, KIND_ENC, KIND_WIDE, KIND_PAIRS, KIND_RANGES, KIND_BYTES = range(6)
INDEX_KINDS = {'dbcs_index': KIND_DEC, 'unim_index': KIND_ENC, 'widedbcs_index': KIND_WIDE}
VALUE_TYPES = {KIND_DEC: 'ucs2_t', KIND_ENC: 'DBCHAR', KIND_WIDE: 'Py_UCS4'}


class W:
    def __init__(self):
        self.b = bytearray()

    def u(self, n):
        assert n >= 0, n
        while True:
            x = n & 0x7F
            n >>= 7
            if n:
                self.b.append(x | 0x80)
            else:
                self.b.append(x)
                return

    def s(self, t):
        bs = t.encode('ascii')
        self.u(len(bs))
        self.b += bs


def number(tok):
    tok = tok.strip()
    if tok in SYMBOLS:
        return SYMBOLS[tok]
    return int(tok, 0)


def values(body):
    return [number(t) for t in body.replace('\n', '').split(',') if t.strip()]


def parse(src):
    """The tables of one header: {name: (kind, payload)}."""
    out = {}
    arrays = {}
    for typ, name, n, body in re.findall(r'static const (\w+) __(\w+)\[(\d+)\] = \{(.*?)\};', src, re.S):
        v = values(body)
        assert len(v) == int(n), (name, len(v), n)
        arrays[name] = (typ, v)
    for typ, name, body in re.findall(r'static const struct (\w+) (\w+)\[256\] = \{(.*?)\n\};', src, re.S):
        kind = INDEX_KINDS[typ]
        rows = re.findall(r'\{([^{}]*)\}', body.replace('\n', ''))
        assert len(rows) == 256, (name, len(rows))
        vtyp, data = arrays.pop(name)
        assert vtyp == VALUE_TYPES[kind], (name, vtyp)
        table = []
        at = 0
        for c1, row in enumerate(rows):
            p, bottom, top = [x.strip() for x in row.split(',')]
            if p == '0':
                assert bottom == '0' and top == '0', (name, c1, row)
                continue
            m = re.fullmatch(r'__(\w+)\+(\d+)', p)
            assert m and m.group(1) == name, (name, row)
            off, bottom, top = int(m.group(2)), int(bottom), int(top)
            assert off == at and bottom <= top, (name, c1, off, at)
            table.append((c1, bottom, top, data[at:at + top - bottom + 1]))
            at += top - bottom + 1
        assert at == len(data), (name, at, len(data))
        out[name] = (kind, table)
    assert not arrays, sorted(arrays)
    for name, n, body in re.findall(r'static const struct pair_encodemap (\w+)\[(\w+)\] = \{(.*?)\n\};', src, re.S):
        body = body.replace('\n', '')
        pairs = [(int(a, 16), int(b, 16)) for a, b in re.findall(r'\{0x([0-9a-f]+),0x([0-9a-f]+)\}', body)]
        count = int(re.search(r'#define %s (\d+)' % n, src).group(1))
        assert len(pairs) == count, (name, len(pairs), count)
        assert pairs == sorted(pairs), name  # (find_pairencmap's binary search)
        out[name] = (KIND_PAIRS, pairs)
    for name, body in re.findall(r'\} (gb18030_to_unibmp_ranges)\[\] = \{(.*?)\};', src, re.S):
        rows = [tuple(int(x) for x in r.split(',')) for r in re.findall(r'\{([^{}]*)\}', body.replace('\n', ''))]
        assert rows[-1][:2] == (0, 0), rows[-1]
        out[name] = (KIND_RANGES, rows)
    for name, body in re.findall(r'static const unsigned char (big5hkscs_phint_\d+)\[\] = \{(.*?)\};', src, re.S):
        out[name] = (KIND_BYTES, values(body))
    return out


def zigzag(d, bits):
    """[d] (a difference modulo 2**bits) as a signed number, zigzag'd."""
    if d >= 1 << (bits - 1):
        d -= 1 << bits
    return (d << 1) if d >= 0 else ((-d << 1) - 1)


# What each encmap is mostly the inverse of: (decmap, its codes' mask, which of big5hkscs's characters: 0 all, 1 those
# of the BMP, 2 those past it, by the plane-2 hints), the first code of a character kept; the rest is stored.
DERIVED = {
    'gbcommon_encmap': [('gb2312_decmap', 0, 0), ('gbkext_decmap', 0, 0)],
    'gb18030ext_encmap': [('gb18030ext_decmap', 0, 0)],
    'jisxcommon_encmap': [('jisx0208_decmap', 0, 0), ('jisx0212_decmap', 0x8000, 0)],
    'cp932ext_encmap': [('cp932ext_decmap', 0, 0)],
    'jisx0213_bmp_encmap': [('jisx0213_1_bmp_decmap', 0, 0), ('jisx0213_2_bmp_decmap', 0x8000, 0)],
    'jisx0213_emp_encmap': [('jisx0213_1_emp_decmap', 0, 0), ('jisx0213_2_emp_decmap', 0x8000, 0)],
    'cp949_encmap': [('ksx1001_decmap', 0, 0), ('cp949ext_decmap', 0, 0)],
    'big5_encmap': [('big5_decmap', 0, 0)],
    'cp950ext_encmap': [('cp950ext_decmap', 0, 0)],
    'big5hkscs_bmp_encmap': [('big5hkscs_decmap', 0, 1)],
    'big5hkscs_nonbmp_encmap': [('big5hkscs_decmap', 0, 2)],
}


def bh2s(c1, c2):
    return (c1 - 0x87) * (0xfe - 0x40 + 1) + (c2 - 0x40)


def hkscs_hint(tables, c1, c2):
    """_codecs_hk.c's: whether big5hkscs's (c1, c2) is past the BMP (its hint's bit), None outside the hints."""
    s = bh2s(c1, c2)
    for name, lo, hi in (('big5hkscs_phint_0', (0x87, 0x40), (0xa0, 0xfe)), ('big5hkscs_phint_12130', (0xc6, 0xa1), (0xc8, 0xfe)),
                         ('big5hkscs_phint_21924', (0xf9, 0xd6), (0xfe, 0xfe))):
        if bh2s(*lo) <= s <= bh2s(*hi):
            s -= bh2s(*lo)
            return bool(tables[name][1][s >> 3] & (1 << (s & 7)))
    return None


def derive(tables, rows, sources):
    """The encmap's values as PyCjkCodecs.kt derives them from [sources] (NOCHAR elsewhere)."""
    start = {}
    at = 0
    for c1, bottom, top, _ in rows:
        start[c1] = (at, bottom, top)
        at += top - bottom + 1
    values = [SYMBOLS['N']] * at
    for name, mask, which in sources:
        for c1, bottom, top, vs in tables[name][1]:
            for i, u in enumerate(vs):
                if u == SYMBOLS['U']:
                    continue
                c2 = bottom + i
                if which:
                    h = hkscs_hint(tables, c1, c2)
                    if h is None or h != (which == 2):
                        continue
                r = start.get(u >> 8)
                if r is None or not r[1] <= (u & 0xFF) <= r[2]:
                    continue
                k = r[0] + (u & 0xFF) - r[1]
                if values[k] == SYMBOLS['N']:
                    values[k] = (c1 << 8 | c2) | mask
    return values


def table_bytes(tables, name):
    kind, payload = tables[name]
    w = W()
    if kind in (KIND_DEC, KIND_ENC, KIND_WIDE):
        bits = 32 if kind == KIND_WIDE else 16
        w.u(len(payload))
        for c1, bottom, top, _ in payload:
            w.u(c1)
            w.u(bottom)
            w.u(top)
        actual = [v for _, _, _, vs in payload for v in vs]
        if kind == KIND_ENC:
            sources = DERIVED.get(name, [])
            w.u(len(sources))
            for src, mask, which in sources:
                w.s(src)
                w.u(mask)
                w.u(which)
            derived = derive(tables, payload, sources)
            exceptions = [(k, v) for k, (v, d) in enumerate(zip(actual, derived)) if v != d]
            w.u(len(exceptions))
            prev = -1
            for k, v in exceptions:
                w.u(k - prev - 1)
                w.u(v)
                prev = k
            for k, v in exceptions:
                derived[k] = v
            assert derived == actual, name
            print('%-28s %6d values, %4d not derived' % (name, len(actual), len(exceptions)), file=sys.stderr)
        else:
            prev = 0
            for v in actual:
                w.u(zigzag((v - prev) % (1 << bits), bits))
                prev = v
    elif kind == KIND_PAIRS:
        w.u(len(payload))
        for seq, code in payload:
            w.u(seq)
            w.u(code)
    elif kind == KIND_RANGES:
        w.u(len(payload))
        for first, last, base in payload:
            w.u(first)
            w.u(last)
            w.u(base)
    else:
        w.u(len(payload))
        w.b += bytes(payload)
    return bytes(w.b)


def main():
    tables = {}
    for h in HEADERS:
        src = open(os.path.join(SRC, h), encoding='utf-8').read()
        for name, t in parse(src).items():
            assert name not in tables, name
            tables[name] = t
    names = sorted(tables)
    blobs = [gzip.compress(table_bytes(tables, n), 9, mtime=0) for n in names]
    d = W()
    d.u(len(names))
    at = 0
    for n, z in zip(names, blobs):
        d.s(n)
        d.u(tables[n][0])
        d.u(at)
        d.u(len(z))
        at += len(z)
    out = b'NPCJ\x01' + bytes(d.b) + b''.join(blobs)
    open(OUT, 'wb').write(out)
    for n, z in zip(names, blobs):
        print('%-28s kind %d  %6d bytes' % (n, tables[n][0], len(z)), file=sys.stderr)
    print('wrote', OUT, len(out), 'bytes', file=sys.stderr)


# ------------------------------------------------------------------ --digests: CPython's results for PyCjkCodecsTest

# Each codec by its module (CPython's _codecs_*.getcodec), in the test's order.
DIGEST_CODECS = [('cn', 'gb2312'), ('cn', 'gbk'), ('cn', 'gb18030'), ('cn', 'hz'), ('tw', 'big5'), ('tw', 'cp950'),
                 ('hk', 'big5hkscs'), ('jp', 'shift_jis'), ('jp', 'cp932'), ('jp', 'euc_jp'), ('jp', 'shift_jis_2004'),
                 ('jp', 'euc_jis_2004'), ('jp', 'euc_jisx0213'), ('jp', 'shift_jisx0213'), ('kr', 'euc_kr'),
                 ('kr', 'cp949'), ('kr', 'johab'), ('iso2022', 'iso2022_kr'), ('iso2022', 'iso2022_jp'),
                 ('iso2022', 'iso2022_jp_1'), ('iso2022', 'iso2022_jp_2'), ('iso2022', 'iso2022_jp_2004'),
                 ('iso2022', 'iso2022_jp_3'), ('iso2022', 'iso2022_jp_ext')]
# The sequences a codec's escape designates, each followed by every two bytes.
DIGEST_PREFIXES = {
    'hz': [b'~{'],
    'iso2022_kr': [b'\x1b$)C\x0e'],
    'iso2022_jp': [b'\x1b$B', b'\x1b(J', b'\x1b&@\x1b$B'],
    'iso2022_jp_1': [b'\x1b$B', b'\x1b$(D'],
    'iso2022_jp_2': [b'\x1b$(C', b'\x1b$A', b'\x1b.A\x1bN', b'\x1b.F\x1bN'],
    'iso2022_jp_2004': [b'\x1b$(Q', b'\x1b$(P'],
    'iso2022_jp_3': [b'\x1b$(O', b'\x1b$(P'],
    'iso2022_jp_ext': [b'\x1b(I', b'\x1b$(D'],
}


def sequences(name):
    """The byte sequences decoded: every one of one and two bytes, then the codec's longer ones."""
    for b1 in range(256):
        yield bytes((b1,))
    for b1 in range(256):
        for b2 in range(256):
            yield bytes((b1, b2))
    if name == 'gb18030':
        for b1 in range(0x81, 0xff):
            for b2 in range(0x30, 0x3a):
                for b3 in range(0x81, 0xff):
                    for b4 in range(0x30, 0x3a):
                        yield bytes((b1, b2, b3, b4))
        for b1 in (0x80, 0x81, 0x84, 0x8f, 0x90, 0xe3, 0xfe, 0xff):
            for b2 in (0x30, 0x39):
                for b3 in range(256):
                    for b4 in range(256):
                        yield bytes((b1, b2, b3, b4))
    elif name in ('euc_jp', 'euc_jis_2004', 'euc_jisx0213'):
        for b2 in range(256):
            for b3 in range(256):
                yield bytes((0x8f, b2, b3))
    elif name == 'euc_kr':
        for x in range(0xa0, 0xc0):
            for y in range(0xbe, 0xd5):
                for z in list(range(0xa0, 0xc0)) + [0xd4, 0xd5]:
                    yield bytes((0xa4, 0xd4, 0xa4, x, 0xa4, y, 0xa4, z))
    for p in DIGEST_PREFIXES.get(name, []):
        for b1 in range(256):
            for b2 in range(256):
                yield p + bytes((b1, b2))


def digests():
    import hashlib
    import importlib
    out = []
    for loc, name in DIGEST_CODECS:
        codec = importlib.import_module('_codecs_' + loc).getcodec(name)
        h = hashlib.md5()
        for cp in range(0x110000):
            try:
                line = '%x %s\n' % (cp, codec.encode(chr(cp))[0].hex())
            except UnicodeEncodeError as e:
                line = '%x E%d,%d,%s\n' % (cp, e.start, e.end, e.reason)
            h.update(line.encode('ascii'))
        hd = hashlib.md5()
        n = 0
        for b in sequences(name):
            try:
                line = '%s .%s\n' % (b.hex(), '.'.join('%x' % ord(c) for c in codec.decode(b)[0]))
            except UnicodeDecodeError as e:
                line = '%s E%d,%d,%s\n' % (b.hex(), e.start, e.end, e.reason)
            hd.update(line.encode('ascii'))
            n += 1
        out.append('        "%s" to Triple("%s", "%s", %d),' % (name, h.hexdigest(), hd.hexdigest(), n))
        print(out[-1], file=sys.stderr)
    print('\n'.join(out))


if __name__ == '__main__':
    if sys.argv[1:] == ['--digests']:
        digests()
    else:
        main()
