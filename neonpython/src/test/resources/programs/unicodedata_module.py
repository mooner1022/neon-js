# unicodedata as CPython 3.14 has it: Unicode 16.0.0 and ucd_3_2_0, properties, normalization, names, aliases, named sequences, messages.
import unicodedata as u
U = u.ucd_3_2_0


def t(label, f):
    try:
        print(label, "->", ascii(f()))
    except Exception as e:
        print(label, "!!", type(e).__name__, ascii(str(e)))


def s(*cps):
    return ''.join(map(chr, cps))


def props(db, c):
    ch = chr(c)
    return (db.category(ch), db.bidirectional(ch), db.combining(ch), db.east_asian_width(ch), db.mirrored(ch),
            db.decimal(ch, None), db.digit(ch, None), db.numeric(ch, None), db.decomposition(ch), db.name(ch, None))


def sample_counts(db):
    cats = {}
    named = 0
    decomposed = 0
    numeric = 0.0
    for c in range(0, 0x110000, 251):
        p = props(db, c)
        cats[p[0]] = cats.get(p[0], 0) + 1
        named += p[9] is not None
        decomposed += p[8] != ''
        if p[7] is not None:
            numeric += p[7]
    return sorted(cats.items()), named, decomposed, numeric


def characters():
    for c in (0x41, 0x31, 0x663, 0xbd, 0x4e00, 0xac01, 0xe9, 0xa0, 0x28, 0x301, 0x1f10c, 0xe0080, 0x2f868, 0x378, 0x17000,
              0x1fae8, 0x2ebf0, 0x31350, 0xd800, 0x10ffff, 0xf0000, 0x2163, 0x1d7ce, 0x2460, 0x5341):
        t(hex(c), lambda: props(u, c))
        t(hex(c) + " 3.2.0", lambda: props(U, c))


def forms(db, x):
    return [db.normalize(f, x) for f in ('NFC', 'NFD', 'NFKC', 'NFKD')] + [db.is_normalized(f, x) for f in ('NFC', 'NFD', 'NFKC', 'NFKD')]


def normalization():
    cases = [s(0x63, 0x61, 0x66, 0xe9), s(0x63, 0x61, 0x66, 0x65, 0x301), s(0xac01), s(0x1100, 0x1161, 0x11a8), s(0xfb01),
             s(0x2f868), s(0x1e9b, 0x323), s(0x61, 0x328, 0x301, 0x323), s(0x3d3), s(0x1d15e, 0x1d165), s(0x0f73, 0x0f75),
             s(0x41, 0x30a, 0x212b), s(0x1100, 0x1161, 0x11a7), s(0x1100, 0x1176), s(0x3b1, 0x313, 0x300, 0x345),
             s(0xd55c, 0xae00), s(0x1e0a, 0x323), s(0x61, 0x5ae, 0x302, 0x301, 0x316), '', 'abc', s(0x2126, 0x212b)]
    for x in cases:
        t("forms " + ascii(x), lambda: forms(u, x))
        t("forms 3.2.0 " + ascii(x), lambda: forms(U, x))
    import random
    r = random.Random(16)
    pool = list(range(0x300, 0x316)) + [0x41, 0x45, 0x61, 0x65, 0x6f, 0x1100, 0x1161, 0x11a8, 0xac00, 0x3b1, 0x1e0a, 0x323, 0xfb01, 0x2f868]
    out = []
    for i in range(150):
        x = ''.join(chr(r.choice(pool)) for _ in range(r.randrange(1, 8)))
        out.append((x, forms(u, x), forms(U, x)))
    print("random forms", len(out), ascii(out[:3]))
    nfc = [f[1][0] for f in out]
    print("random nfc", ascii(''.join(nfc))[:300])
    x = s(0x63, 0x61, 0x66, 0xe9, 0x78)
    t("identity", lambda: (u.normalize('NFC', x) is x, u.normalize('NFD', x) is x, u.normalize('NFKD', 'abc') == 'abc'))


def names():
    for n in ['LATIN SMALL LETTER A', 'latin small letter a', 'BYTE ORDER MARK', 'LATIN CAPITAL LETTER A WITH MACRON AND GRAVE',
              'KEYCAP NUMBER SIGN', 'HANGUL SYLLABLE GAG', 'hangul syllable gag', 'HANGUL SYLLABLE ', 'HANGUL SYLLABLE A',
              'CJK UNIFIED IDEOGRAPH-4e00', 'CJK UNIFIED IDEOGRAPH-04E00', 'CJK UNIFIED IDEOGRAPH-9FFF', 'TANGUT IDEOGRAPH-17000',
              'CJK COMPATIBILITY IDEOGRAPH-F900', 'x' * 300, 'LATIN SMALL LETTER A' + chr(0), '', b'LATIN SMALL LETTER B',
              'LINE FEED', 'LF', 'ZWJ', 'VS256', 'EGYPTIAN HIEROGLYPH A001', 'NO SUCH NAME', 'LATIN SMALL LETTER A ']:
        t("lookup " + ascii(n)[:40], lambda: u.lookup(n))
        t("lookup 3.2.0 " + ascii(n)[:40], lambda: U.lookup(n))
    for c in (0, 0x20, 0x41, 0xac00, 0xd7a3, 0x9fff, 0x2ebf0, 0x17000, 0xf900, 0xfeff, 0x1f600, 0xe01ef, 0xf0000, 0x323af):
        t("name " + hex(c), lambda: (u.name(chr(c), None), U.name(chr(c), None)))
    bad = 0
    for c in range(0, 0x110000, 97):
        n = u.name(chr(c), None)
        if n is not None and (u.lookup(n) != chr(c) or u.lookup(n.lower()) != chr(c)):
            bad += 1
    print("round trips, bad:", bad)


def escapes():
    b = chr(92)
    for name in ['BYTE ORDER MARK', 'LATIN CAPITAL LETTER A WITH MACRON AND GRAVE', 'latin small letter a', 'LF', 'NO SUCH THING',
                 'CJK UNIFIED IDEOGRAPH-4E00', 'HANGUL SYLLABLE GAG']:
        t("escape " + name, lambda: eval("'" + b + "N{" + name + "}'"))
        t("unicode_escape " + name, lambda: (b + 'N{' + name + '}').encode('ascii').decode('unicode_escape'))
    t("namereplace", lambda: s(0xfeff, 0x4e00, 0xac00, 0x17000, 0xf0000, 0xf0200, 0xe9).encode('ascii', 'namereplace'))


def errors():
    t("category ab", lambda: u.category('ab'))
    t("category 1", lambda: u.category(1))
    t("category ()", lambda: u.category())
    t("UCD category ()", lambda: U.category())
    t("category kw", lambda: u.category(c='a'))
    t("UCD decimal kw", lambda: U.decimal(c='a'))
    t("decimal ()", lambda: u.decimal())
    t("decimal 3", lambda: u.decimal('a', 1, 2))
    t("decimal a", lambda: u.decimal('a'))
    t("digit a", lambda: u.digit('a'))
    t("numeric a", lambda: u.numeric('a'))
    t("name NUL", lambda: u.name(chr(0)))
    t("name 1", lambda: u.name(1))
    t("name ab", lambda: u.name('ab'))
    t("normalize NFX", lambda: u.normalize('NFX', 'a'))
    t("normalize 1", lambda: u.normalize(1, 'a'))
    t("normalize str 1", lambda: u.normalize('NFC', 1))
    t("normalize one", lambda: u.normalize('NFC'))
    t("is_normalized NFX", lambda: u.is_normalized('NFX', 'a'))
    t("is_normalized NFX empty", lambda: u.is_normalized('NFX', ''))
    t("normalize NFX empty", lambda: u.normalize('NFX', ''))
    t("lookup ()", lambda: u.lookup())
    t("lookup 1", lambda: u.lookup(1))
    t("lookup bytearray", lambda: u.lookup(bytearray(b'A')))
    t("lookup surrogate", lambda: u.lookup(chr(0xd800)))
    t("UCD()", lambda: u.UCD())
    t("UCD subclass", lambda: type('X', (u.UCD,), {}))
    t("unidata_version set", lambda: setattr(U, 'unidata_version', 'x'))
    t("UCD attribute", lambda: setattr(U, 'foo', 'x'))


def module():
    t("versions", lambda: (u.unidata_version, U.unidata_version, type(U).__name__, type(U).__module__))
    t("names", lambda: sorted(n for n in dir(u) if not n.startswith('__')))
    t("UCD names", lambda: sorted(n for n in dir(U) if not n.startswith('__')))
    t("doc", lambda: u.__doc__.splitlines()[3])
    t("capsule", lambda: type(u._ucnhash_CAPI).__name__)


module()
print("sample", sample_counts(u))
print("sample 3.2.0", sample_counts(U))
characters()
normalization()
names()
escapes()
errors()
