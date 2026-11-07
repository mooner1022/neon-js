"""Generates neonpython's str type resource (resources dev/mooner/neonpython/unicodetype.bin) from CPython 3.14's own
str: what its predicates and case mappings answer for every code point, so that NeonPy's str (PyUnicodeType.kt)
answers as CPython's Objects/unicodectype.c does, whatever Unicode the JVM or Android has.

Per code point (0..0x10FFFF, surrogates too): the flags unicodectype.c keeps (isalpha, isdecimal, isdigit, islower,
istitle, isupper, XID_Start, XID_Continue, isprintable, isnumeric, case-ignorable, cased), the two it computes
(isspace, a line break as splitlines sees one), the decimal and digit values, and the full lower, upper, title and
casefold mappings. Cased is seen through title() (what follows a cased character is lowered), case-ignorable through
lower()'s final sigma, XID_Start and XID_Continue through isidentifier(), the line breaks through splitlines(). The
records are makeunicodedata.py's: a mapping to one character a delta, the rest (EXTENDED_CASE) indices into the
extended case table, casefold after lower where it differs; the simple mappings (re's) are the full ones' first
characters, as unicodectype.c's. Where Objects/unicodetype_db.h is there (third_party/cpython, the release's
sources), every code point's answers are checked against its records, index and functions.

Usage, from the repository root, with CPython 3.14.6: python -X utf8 -I neonpython/tools/gen_unicodetype.py

The file: b'NPUT', a version byte, then a big-endian 4-byte length and the gzip'd table: the records (flags, decimal,
digit, upper, lower, title: the last three zigzag'd), the extended case table, then SHIFT and the two-level index
(index1, index2) of each code point's record. Numbers are unsigned LEB128 varints.
"""
import _sre
import gzip
import os
import re
import struct
import sys
import unicodedata as U

TOOLS = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(TOOLS))
OUT = os.path.join(ROOT, 'neonpython/src/main/resources/dev/mooner/neonpython/unicodetype.bin')
TYPE_DB = os.path.join(ROOT, 'third_party/cpython/Objects/unicodetype_db.h')
MAX = 0x110000

# unicodectype.c's flags (whitespace and line breaks: bits its records keep, which functions answer there)
ALPHA_MASK = 0x01
DECIMAL_MASK = 0x02
DIGIT_MASK = 0x04
LOWER_MASK = 0x08
LINEBREAK_MASK = 0x10
SPACE_MASK = 0x20
TITLE_MASK = 0x40
UPPER_MASK = 0x80
XID_START_MASK = 0x100
XID_CONTINUE_MASK = 0x200
PRINTABLE_MASK = 0x400
NUMERIC_MASK = 0x800
CASE_IGNORABLE_MASK = 0x1000
CASED_MASK = 0x2000
EXTENDED_CASE_MASK = 0x4000


def u(out, n):
    assert n >= 0, n
    while True:
        x = n & 0x7F
        n >>= 7
        if n:
            out.append(x | 0x80)
        else:
            out.append(x)
            return


def zigzag(n):
    return n * 2 if n >= 0 else -n * 2 - 1


def observe(c):
    """(flags, decimal, digit, lower, upper, title, casefold) of code point [c], as CPython's str answers."""
    ch = chr(c)
    f = 0
    if ch.isalpha():
        f |= ALPHA_MASK
    if ch.isdecimal():
        f |= DECIMAL_MASK
    if ch.isdigit():
        f |= DIGIT_MASK
    if ch.islower():
        f |= LOWER_MASK
    if ch.isupper():
        f |= UPPER_MASK
    # (a single character's istitle is ISTITLE or ISUPPER)
    if ch.istitle() and not ch.isupper():
        f |= TITLE_MASK
    # ('_' starts an identifier by isidentifier's own rule: it is not XID_Start)
    if c != 0x5F and ch.isidentifier():
        f |= XID_START_MASK
    if ('a' + ch).isidentifier():
        f |= XID_CONTINUE_MASK
    if ch.isprintable():
        f |= PRINTABLE_MASK
    if ch.isnumeric():
        f |= NUMERIC_MASK
    if ch.isspace():
        f |= SPACE_MASK
    if ('a' + ch + 'b').splitlines() == ['a', 'b']:
        f |= LINEBREAK_MASK
    # cased: title() lowers what follows a cased character
    cased = (ch + 'a').title()[-1] == 'a'
    if cased:
        f |= CASED_MASK
    # case-ignorable: a sigma is final after a cased character and what is ignorable (cased ^ the next test), and
    # before what is ignorable and then nothing cased
    after = ('a' + ch + 'Σ').lower()[-1] == 'ς'    # ignorable or cased
    before = ('aΣ' + ch).lower()[1] == 'ς'         # ignorable or not cased
    if (before if cased else after):
        f |= CASE_IGNORABLE_MASK
    assert (after if cased else before), hex(c)
    decimal = int(ch) if f & DECIMAL_MASK else -1
    digit = U.digit(ch, -1)
    assert (digit >= 0) == bool(f & DIGIT_MASK), hex(c)
    assert U.decimal(ch, -1) == decimal, hex(c)
    assert ch.isalnum() == bool(f & (ALPHA_MASK | DECIMAL_MASK | DIGIT_MASK | NUMERIC_MASK)), hex(c)
    maps = tuple([ord(x) for x in m] for m in (ch.lower(), ch.upper(), ch.title(), ch.casefold()))
    # (re's simple mappings: the full ones' first characters)
    assert _sre.unicode_tolower(c) == maps[0][0], hex(c)
    assert _sre.unicode_iscased(c) == (maps[0][0] != c or maps[1][0] != c), hex(c)
    return (f, decimal, digit) + maps


def record(c, obs, extended):
    """makeunicodedata.py's record of [c]: (flags, decimal, digit, upper, lower, title), [extended] grown."""
    f, decimal, digit, lower, upper, title, fold = obs
    if len(lower) == len(upper) == len(title) == 1 and fold == lower:
        return f, max(decimal, 0), max(digit, 0), upper[0] - c, lower[0] - c, title[0] - c
    f |= EXTENDED_CASE_MASK
    lo = len(extended) | (len(lower) << 24)
    extended.extend(lower)
    if fold != lower:
        lo |= len(fold) << 20
        extended.extend(fold)
    up = len(extended) | (len(upper) << 24)
    extended.extend(upper)
    if title == upper:
        ti = up
    else:
        ti = len(extended) | (len(title) << 24)
        extended.extend(title)
    return f, max(decimal, 0), max(digit, 0), up, lo, ti


def splitbins(t):
    """(index1, index2, shift), t[i] == index2[(index1[i >> shift] << shift) + (i & mask)], the smallest."""
    best = None
    for shift in range(1, 12):
        size = 1 << shift
        bins = {}
        index1 = []
        index2 = []
        for i in range(0, len(t), size):
            blk = tuple(t[i:i + size])
            k = bins.get(blk)
            if k is None:
                k = bins[blk] = len(index2) >> shift
                index2.extend(blk)
            index1.append(k)
        if best is None or len(index1) + len(index2) < len(best[0]) + len(best[1]):
            best = (index1, index2, shift)
    index1, index2, shift = best
    assert max(index1) < 0x10000 and max(index2) < 0x10000
    for i in range(len(t)):
        assert t[i] == index2[(index1[i >> shift] << shift) + (i & ((1 << shift) - 1))]
    return best


def resolve(rec, extended, c):
    """unicodectype.c over a record: (flags but EXTENDED_CASE, decimal, digit, lower, upper, title, casefold)."""
    f, decimal, digit, up, lo, ti = rec

    def full(v):
        if f & EXTENDED_CASE_MASK:
            return extended[v & 0xFFFF:(v & 0xFFFF) + (v >> 24)]
        return [c + v]
    fold = full(lo)
    if f & EXTENDED_CASE_MASK and (lo >> 20) & 7:
        at = (lo & 0xFFFF) + (lo >> 24)
        fold = extended[at:at + ((lo >> 20) & 7)]
    return (f & ~EXTENDED_CASE_MASK, decimal if f & DECIMAL_MASK else -1, digit if f & DIGIT_MASK else -1,
            full(lo), full(up), full(ti), fold)


def check_db(obs):
    """Every code point's answers against unicodetype_db.h's records, index, whitespace and line breaks."""
    src = open(TYPE_DB, encoding='utf-8').read()

    def array(name):
        m = re.search(r'\b' + name + r'\[\] = \{(.*?)\n\};', src, re.S)
        return m.group(1)
    recs = [tuple(int(x) for x in m.split(',')) for m in re.findall(r'\{([-\d, ]+)\}', array('_PyUnicode_TypeRecords'))]
    extended = [int(x) for x in re.findall(r'\d+', array('_PyUnicode_ExtendedCase'))]
    index1 = [int(x) for x in re.findall(r'\d+', array('index1'))]
    index2 = [int(x) for x in re.findall(r'\d+', array('index2'))]
    shift = int(re.search(r'#define SHIFT (\d+)', src).group(1))

    def cases(fn):
        body = re.search(r'int ' + fn + r'\(const Py_UCS4 ch\)\n\{(.*?)\n\}', src, re.S).group(1)
        return {int(x, 16) for x in re.findall(r'case 0x([0-9A-F]+):', body)}
    space, linebreak = cases('_PyUnicode_IsWhitespace'), cases('_PyUnicode_IsLinebreak')
    for c in range(MAX):
        upper, lower, title, decimal, digit, flags = recs[index2[(index1[c >> shift] << shift) + (c & ((1 << shift) - 1))]]
        # (the records keep the whitespace and line breaks too, which the functions answer)
        assert bool(flags & SPACE_MASK) == (c in space) and bool(flags & LINEBREAK_MASK) == (c in linebreak), hex(c)
        theirs = resolve((flags, decimal, digit, upper, lower, title), extended, c)
        assert theirs == resolve(*obs[c]), (hex(c), theirs, resolve(*obs[c]))
    print('checked against', TYPE_DB, file=sys.stderr)


def main():
    assert sys.version_info[:2] == (3, 14) and U.unidata_version == '16.0.0', 'run with CPython 3.14'
    extended = []
    records = {}
    per = []
    obs = []
    for c in range(MAX):
        o = observe(c)
        rec = record(c, o, extended)
        idx = records.setdefault(rec, len(records))
        per.append(idx)
        obs.append((rec, extended, c))
    print('properties read:', len(records), 'records,', len(extended), 'extended', file=sys.stderr)
    # (ASCII as the fast paths have it, apart from the table: checked here)
    for c in range(128):
        f = resolve(*obs[c])[0]
        ch = chr(c)
        want = 0
        if ch.isalpha():
            want |= ALPHA_MASK | XID_START_MASK | XID_CONTINUE_MASK | CASED_MASK | (UPPER_MASK if ch < 'a' else LOWER_MASK)
        if '0' <= ch <= '9':
            want |= DECIMAL_MASK | DIGIT_MASK | NUMERIC_MASK | XID_CONTINUE_MASK
        if ch == '_':
            want |= XID_CONTINUE_MASK
        if 0x20 <= c < 0x7F:
            want |= PRINTABLE_MASK
        if ch in "'.:^`":
            want |= CASE_IGNORABLE_MASK
        if c in (9, 10, 11, 12, 13, 0x1C, 0x1D, 0x1E, 0x1F, 0x20):
            want |= SPACE_MASK
        if c in (10, 11, 12, 13, 0x1C, 0x1D, 0x1E):
            want |= LINEBREAK_MASK
        assert f == want, (hex(c), hex(f), hex(want))
    if os.path.exists(TYPE_DB):
        check_db(obs)
    index1, index2, shift = splitbins(per)
    print('shift', shift, len(index1), '+', len(index2), 'index entries', file=sys.stderr)

    b = bytearray()
    u(b, len(records))
    for rec in sorted(records, key=records.get):
        f, decimal, digit, up, lo, ti = rec
        for v in (f, decimal, digit, zigzag(up), zigzag(lo), zigzag(ti)):
            u(b, v)
    u(b, len(extended))
    for v in extended:
        u(b, v)
    u(b, shift)
    for t in (index1, index2):
        u(b, len(t))
        for v in t:
            u(b, v)
    z = gzip.compress(bytes(b), 9, mtime=0)
    out = b'NPUT\x01' + struct.pack('>I', len(z)) + z
    open(OUT, 'wb').write(out)
    print('wrote', OUT, len(b), 'bytes,', len(out), 'compressed', file=sys.stderr)


if __name__ == '__main__':
    main()
