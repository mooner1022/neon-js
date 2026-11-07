"""Generates neonpython's unicodedata resource (resources dev/mooner/neonpython/unicodedata.bin) from CPython 3.14's
own unicodedata module: what it answers for every code point, in both databases (16.0.0 and ucd_3_2_0), so that
NeonPy's unicodedata (PyUnicodedata.kt) answers as CPython does, whatever Unicode the JVM or Android has.

Per code point (0..0x10FFFF): category, bidirectional, combining, east_asian_width, mirrored, decimal, digit,
numeric, decomposition and name (16.0.0), the normalization quick checks (seen through normalize/is_normalized:
CPython gives the str itself back when the quick check says YES), and 3.2.0's category, bidirectional,
east_asian_width, mirrored, decimal and numeric. 3.2.0's combining, decomposition and name are 16.0.0's where the
code point is assigned in 3.2.0 (unicodedata.c), which the tool checks. The composition pairs are the canonical
two-character decompositions NFC composes again; 3.2.0's normalization corrections are the code points whose NFD
the decompositions do not explain. The names of the aliases and named sequences (which no Python API lists) are
read from Modules/unicodename_db.h (third_party/cpython, the release's sources) and checked by lookup.

Usage, from the repository root, with CPython 3.14.6: python -X utf8 -I neonpython/tools/gen_unicodedata.py

The file: b'NPUD', a version byte, then sections, each a big-endian 4-byte length and its gzip'd bytes: the
properties (records, their runs over the code points, decompositions, composition pairs, corrections, the derived
name ranges), then the names. Numbers are unsigned LEB128 varints; strings a varint length and ASCII bytes.
"""
import gzip
import os
import re
import struct
import sys
import unicodedata as U

TOOLS = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(TOOLS))
OUT = os.path.join(ROOT, 'neonpython/src/main/resources/dev/mooner/neonpython/unicodedata.bin')
NAME_DB = os.path.join(ROOT, 'third_party/cpython/Modules/unicodename_db.h')
DATA_DB = os.path.join(ROOT, 'third_party/cpython/Modules/unicodedata_db.h')
MAX = 0x110000
OLD = U.ucd_3_2_0
SBASE, LBASE, VBASE, TBASE, LCOUNT, VCOUNT, TCOUNT = 0xAC00, 0x1100, 0x1161, 0x11A7, 19, 21, 28
NCOUNT = VCOUNT * TCOUNT
SCOUNT = LCOUNT * NCOUNT
JAMO_L = ["G", "GG", "N", "D", "DD", "R", "M", "B", "BB", "S", "SS", "", "J", "JJ", "C", "K", "T", "P", "H"]
JAMO_V = ["A", "AE", "YA", "YAE", "EO", "E", "YEO", "YE", "O", "WA", "WAE", "OE", "YO", "U", "WEO", "WE", "WI", "YU",
          "EU", "YI", "I"]
JAMO_T = ["", "G", "GG", "GS", "N", "NJ", "NH", "D", "L", "LG", "LM", "LB", "LS", "LT", "LP", "LH", "M", "B", "BS", "S",
          "SS", "NG", "J", "C", "K", "T", "P", "H"]
PREFIXES = ["HANGUL SYLLABLE ", "CJK UNIFIED IDEOGRAPH-", "TANGUT IDEOGRAPH-"]
FORMS = [('NFD', 0), ('NFKD', 2), ('NFC', 4), ('NFKC', 6)]  # (form, its quick check's shift, as unicodedata.c's)


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

    def d(self, f):
        self.b += struct.pack('>d', f)


def hangul_name(c):
    k = c - SBASE
    return 'HANGUL SYLLABLE ' + JAMO_L[k // NCOUNT] + JAMO_V[(k % NCOUNT) // TCOUNT] + JAMO_T[k % TCOUNT]


def parse_decomp(s):
    """decomposition()'s string as (tag, code points): tag '' for a canonical one."""
    if not s:
        return None
    parts = s.split()
    tag = ''
    if parts[0].startswith('<'):
        tag = parts.pop(0)
    return tag, [int(p, 16) for p in parts]


def c_array(src, name):
    m = re.search(r'\b' + name + r'\[\] = \{(.*?)\n\};', src, re.S)
    return m.group(1)


def dawg_names(packed):
    """The names of CPython's packed DAWG, in its positions' order (unicodedata.c's _inverse_dawg_lookup)."""
    def varint(i):
        res = shift = 0
        while True:
            b = packed[i]
            res |= (b & 0x7F) << shift
            i += 1
            shift += 7
            if not b & 0x80:
                return res, i
    names = []
    sys.setrecursionlimit(10000)

    def walk(node, prefix):
        num, edge = varint(node)
        if num & 1:
            names.append(prefix)
        prev = edge
        first = True
        while True:
            num, i = varint(edge)
            if num == 0 and first:
                return
            first = False
            last = num & 1
            num >>= 1
            one = num & 1
            num >>= 1
            target = prev + num
            if one:
                size = 1
            else:
                size = packed[i]
                i += 1
            label = bytes(packed[i:i + size]).decode('ascii')
            prev = target
            walk(target, prefix + label)
            if last:
                return
            edge = i + size
    walk(0, '')
    return names


def aliases_and_sequences():
    """[(alias, code)] and [(name, code points)] as unicodename_db.h has them, each checked by lookup."""
    src = open(NAME_DB, encoding='utf-8').read()
    packed = [int(x) for x in re.findall(r'\d+', c_array(src, 'packed_name_dawg'))]
    pos_to_cp = [int(x) for x in re.findall(r'\d+', c_array(src, 'dawg_pos_to_codepoint'))]
    a_start = int(re.search(r'aliases_start = (0x[0-9a-f]+);', src).group(1), 16)
    a_end = int(re.search(r'aliases_end = (0x[0-9a-f]+);', src).group(1), 16)
    s_start = int(re.search(r'named_sequences_start = (0x[0-9a-f]+);', src).group(1), 16)
    s_end = int(re.search(r'named_sequences_end = (0x[0-9a-f]+);', src).group(1), 16)
    targets = [int(x, 16) for x in re.findall(r'0x[0-9A-Fa-f]+', c_array(src, 'name_aliases'))]
    seqs = [[int(x, 16) for x in re.findall(r'0x[0-9A-Fa-f]+', m)]
            for m in re.findall(r'\{\d+, \{([^}]*)\}\}', c_array(src, 'named_sequences'))]
    assert len(targets) == a_end - a_start and len(seqs) == s_end - s_start
    names = dawg_names(packed)
    assert len(names) == len(pos_to_cp), (len(names), len(pos_to_cp))
    by_cp = {}
    for n, cp in zip(names, pos_to_cp):
        by_cp[cp] = n
    # (the DAWG's names of code points are unicodedata.name's)
    for cp, n in by_cp.items():
        if cp < a_start or cp >= s_end:
            assert U.name(chr(cp)) == n, (hex(cp), n)
    aliases = []
    for i, t in enumerate(targets):
        n = by_cp[a_start + i]
        assert U.lookup(n) == chr(t), n
        try:
            OLD.lookup(n)
            raise AssertionError(n)
        except KeyError:
            pass
        aliases.append((n, t))
    sequences = []
    for i, s in enumerate(seqs):
        n = by_cp[s_start + i]
        assert U.lookup(n) == ''.join(map(chr, s)), n
        sequences.append((n, s))
    return aliases, sequences, (a_start, a_end, s_start, s_end)


def nfd(c, k, old, decomps, cn32, corrections, ccc):
    """unicodedata.c's nfd_nfkd for one code point, over the tables."""
    stack = [c]
    out = []
    while stack:
        x = stack.pop()
        if SBASE <= x < SBASE + SCOUNT:
            i = x - SBASE
            out.append(LBASE + i // NCOUNT)
            out.append(VBASE + (i % NCOUNT) // TCOUNT)
            if i % TCOUNT:
                out.append(TBASE + i % TCOUNT)
            continue
        if old and x in corrections:
            stack.append(corrections[x])
            continue
        d = None if old and cn32[x] else decomps.get(x)
        if d is None or (d[0] and not k):
            out.append(x)
            continue
        stack.extend(reversed(d[1]))
    # (each run of non-starters in order of combining class: a stable sort)
    i = 0
    while i < len(out):
        if ccc[out[i]] == 0:
            i += 1
            continue
        j = i
        while j < len(out) and ccc[out[j]] != 0:
            j += 1
        out[i:j] = sorted(out[i:j], key=lambda x: ccc[x])
        i = j
    return out


def main():
    assert U.unidata_version == '16.0.0' and OLD.unidata_version == '3.2.0', 'run with CPython 3.14'
    cats, bidis, eaws, tags, nums = set(), set(), set(), set(), set()
    cur = []   # per code point: (category, bidirectional, combining, east_asian_width, mirrored, decimal, digit, numeric)
    old = []   # 3.2.0's (category, bidirectional, east_asian_width, mirrored, decimal, numeric)
    decomps = {}
    names = {}
    ccc = [0] * MAX
    cn32 = [False] * MAX
    for c in range(MAX):
        ch = chr(c)
        r = (U.category(ch), U.bidirectional(ch), U.combining(ch), U.east_asian_width(ch), U.mirrored(ch),
             U.decimal(ch, -1), U.digit(ch, -1), U.numeric(ch, None))
        o = (OLD.category(ch), OLD.bidirectional(ch), OLD.east_asian_width(ch), OLD.mirrored(ch),
             OLD.decimal(ch, -1), OLD.numeric(ch, None))
        cur.append(r)
        old.append(o)
        cats.update((r[0], o[0]))
        bidis.update((r[1], o[1]))
        eaws.update((r[3], o[2]))
        nums.update(x for x in (r[7], o[5]) if x is not None)
        ccc[c] = r[2]
        cn32[c] = o[0] == 'Cn'
        ds = U.decomposition(ch)
        n = U.name(ch, None)
        # (3.2.0's digit is 16.0.0's; its combining, decomposition and name are 16.0.0's where it is assigned)
        assert OLD.digit(ch, -1) == r[6], hex(c)
        assert OLD.combining(ch) == (0 if cn32[c] else r[2]), hex(c)
        assert OLD.decomposition(ch) == ('' if cn32[c] else ds), hex(c)
        assert OLD.name(ch, None) == (None if cn32[c] else n), hex(c)
        if SBASE <= c < SBASE + SCOUNT:
            k = c - SBASE
            want = '%04X %04X' % (LBASE + k // NCOUNT, VBASE + (k % NCOUNT) // TCOUNT)
            if k % TCOUNT:
                want += ' %04X' % (TBASE + k % TCOUNT)
            assert ds == want and n == hangul_name(c), hex(c)
            continue
        d = parse_decomp(ds)
        if d:
            tags.add(d[0])
            decomps[c] = d
        if n is not None:
            names[c] = n
    print('properties read', file=sys.stderr)

    # the derived names: what lookup takes algorithmically (CJK unified ideographs, Tangut), by prefix
    derived = []
    for pid in (1, 2):
        run = None
        for c in range(0x1000, MAX):
            ok = False
            try:
                ok = U.lookup(PREFIXES[pid] + '%04X' % c) == chr(c)
            except KeyError:
                pass
            if ok:
                if run and run[1] == c - 1:
                    run[1] = c
                else:
                    run = [c, c, pid]
                    derived.append(run)
    derived.sort()
    derived.insert(0, [SBASE, SBASE + SCOUNT - 1, 0])
    derived.sort()
    for first, last, pid in derived:
        for c in range(first, last + 1):
            if pid == 1:
                assert names.pop(c) == 'CJK UNIFIED IDEOGRAPH-%04X' % c, hex(c)
            elif pid == 2:
                # (Tangut ideographs: lookup finds them, name gives none: 3.15 names them)
                assert c not in names, hex(c)
    print('derived ranges', [(hex(a), hex(b), p) for a, b, p in derived], file=sys.stderr)
    for c, n in names.items():
        assert re.fullmatch(r'[A-Z0-9 -]+', n), n
        assert U.lookup(n) == chr(c), n

    # the normalization quick checks (16.0.0's): YES (0), MAYBE (1), NO (2) at shifts 0, 2, 4, 6
    qc = [0] * MAX
    for c in range(0x80, MAX):
        s = '!' + chr(c)
        v = 0
        for form, shift in FORMS:
            if U.normalize(form, s) is s:
                q = 0
            elif U.is_normalized(form, s):
                q = 1
            else:
                q = 2
            assert not (q == 1 and shift < 4), (hex(c), form)
            v |= q << shift
        qc[c] = v
    print('quick checks read', file=sys.stderr)

    # the composition pairs: two-character canonical decompositions NFC composes back
    pairs = []
    for c, (tag, cps) in sorted(decomps.items()):
        if not tag and len(cps) == 2 and U.normalize('NFC', chr(cps[0]) + chr(cps[1])) == chr(c):
            pairs.append((cps[0], cps[1], c))
    if os.path.exists(DATA_DB):
        check_pairs(pairs)

    # 3.2.0's normalization corrections: code points whose NFD the decompositions do not give
    corrections = {}
    for c in range(MAX):
        for k, form in ((0, 'NFD'), (1, 'NFKD')):
            want = [ord(x) for x in U.normalize(form, chr(c))]
            assert nfd(c, k, False, decomps, cn32, corrections, ccc) == want, (hex(c), form)
            want = [ord(x) for x in OLD.normalize(form, chr(c))]
            got = nfd(c, k, True, decomps, cn32, corrections, ccc)
            if got != want:
                assert len(want) == 1 and c not in corrections, (hex(c), form)
                corrections[c] = want[0]
                assert nfd(c, k, True, decomps, cn32, corrections, ccc) == want
    print('corrections', {hex(k): hex(v) for k, v in corrections.items()}, file=sys.stderr)

    aliases, sequences, pua = aliases_and_sequences()

    # the tables
    cats, bidis, eaws, tags = sorted(cats), sorted(bidis), sorted(eaws), sorted(tags)
    nums = sorted(nums)
    ci = {v: i for i, v in enumerate(cats)}
    bi = {v: i for i, v in enumerate(bidis)}
    ei = {v: i for i, v in enumerate(eaws)}
    ni = {v: i + 1 for i, v in enumerate(nums)}
    ti = {v: i for i, v in enumerate(tags)}
    assert tags[0] == ''
    records = {}
    runs = []
    for c in range(MAX):
        r, o = cur[c], old[c]
        rec = (ci[r[0]], bi[r[1]], r[2], ei[r[3]], r[4], r[5] + 1, r[6] + 1, ni.get(r[7], 0), qc[c],
               1 if c in decomps or SBASE <= c < SBASE + SCOUNT else 0,
               ci[o[0]], bi[o[1]], ei[o[2]], o[3], o[4] + 1, ni.get(o[5], 0))
        idx = records.setdefault(rec, len(records))
        if runs and runs[-1][1] == idx:
            runs[-1][0] += 1
        else:
            runs.append([1, idx])
    print(len(records), 'records', len(runs), 'runs', len(decomps), 'decompositions', len(pairs), 'pairs',
          len(names), 'names', file=sys.stderr)

    p = W()
    for table in (cats, bidis, eaws, tags):
        p.u(len(table))
        for v in table:
            p.s(v)
    p.u(len(nums))
    for v in nums:
        p.d(v)
    p.u(len(records))
    for rec in sorted(records, key=records.get):
        for v in rec:
            p.u(v)
    p.u(len(runs))
    for n, idx in runs:
        p.u(n)
        p.u(idx)
    p.u(len(decomps))
    last = 0
    for c, (tag, cps) in sorted(decomps.items()):
        p.u(c - last)
        last = c
        p.u(ti[tag])
        p.u(len(cps))
        for x in cps:
            p.u(x)
    p.u(len(pairs))
    for a, b, c in pairs:
        p.u(a)
        p.u(b)
        p.u(c)
    p.u(len(corrections))
    for c, v in sorted(corrections.items()):
        p.u(c)
        p.u(v)
    p.u(len(derived))
    for first, last_, pid in derived:
        p.u(first)
        p.u(last_)
        p.u(pid)

    n = W()
    for v in pua:
        n.u(v)
    n.u(len(names))
    last = 0
    for c, name in sorted(names.items()):
        n.u(c - last)
        last = c
        n.s(name)
    n.u(len(aliases))
    for name, t in aliases:
        n.s(name)
        n.u(t)
    n.u(len(sequences))
    for name, s in sequences:
        n.s(name)
        n.u(len(s))
        for x in s:
            n.u(x)

    out = bytearray(b'NPUD\x01')
    for sec in (p, n):
        z = gzip.compress(bytes(sec.b), 9, mtime=0)
        out += struct.pack('>I', len(z)) + z
        print('section', len(sec.b), 'bytes,', len(z), 'compressed', file=sys.stderr)
    open(OUT, 'wb').write(out)
    print('wrote', OUT, len(out), 'bytes', file=sys.stderr)


def check_pairs(pairs):
    """The pairs against unicodedata_db.h's nfc_first, nfc_last and comp_data (the release's sources)."""
    src = open(DATA_DB, encoding='utf-8').read()

    def reindex(name):
        out = {}
        for start, count, index in re.findall(r'\{ *(\d+), *(\d+), *(\d+)\}', c_array(src, name)):
            if start == '0':
                continue  # (the end)
            for d in range(int(count) + 1):
                out[int(start) + d] = int(index) + d
        return out
    first, last = reindex('nfc_first'), reindex('nfc_last')
    total_last = int(re.search(r'#define TOTAL_LAST (\d+)', src).group(1))
    shift = int(re.search(r'#define COMP_SHIFT (\d+)', src).group(1))
    index = [int(x) for x in re.findall(r'\d+', c_array(src, 'comp_index'))]
    data = [int(x) for x in re.findall(r'\d+', c_array(src, 'comp_data'))]
    theirs = set()
    for a, f in first.items():
        for b, l in last.items():
            i = f * total_last + l
            code = data[(index[i >> shift] << shift) + (i & ((1 << shift) - 1))]
            if code:
                theirs.add((a, b, code))
    assert theirs == set(pairs), (len(theirs), len(pairs), sorted(theirs ^ set(pairs))[:10])


if __name__ == '__main__':
    main()
