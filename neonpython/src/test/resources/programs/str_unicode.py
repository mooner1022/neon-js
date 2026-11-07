# str's Unicode as CPython 3.14's (Objects/unicodectype.c): every code point's predicates, case mappings, re's simple
# mappings and classes as digests, and what title(), lower()'s final sigma, isidentifier(), splitlines() and split()
# see of each in context (cased, case-ignorable, XID_Continue, line breaks, whitespace) over the planes with what is
# assigned; named examples; int(), float() and complex() of other scripts' digits; format specs; identifiers in source
# (XID_Start, XID_Continue, NFKC).
import _sre
import hashlib
import operator
import re

CHUNK = 0x10000
PREDICATES = ['isalpha', 'isalnum', 'isdecimal', 'isdigit', 'isnumeric', 'isspace', 'isprintable', 'isupper', 'islower',
              'istitle', 'isidentifier']
MAPPINGS = ['upper', 'lower', 'casefold', 'title', 'capitalize', 'swapcase']


def t(label, f):
    try:
        print(label, '->', ascii(f()))
    except Exception as e:
        print(label, '!!', type(e).__name__, ascii(str(e)))


def xid_continue(ch):
    return ('a' + ch).isidentifier()


def cased(ch):
    # (title() lowers what follows a cased character)
    return (ch + 'a').title()[-1] == 'a'


def sigma_after(ch):
    return ('a' + ch + '\u03a3').lower()[-1] == '\u03c2'


def sigma_before(ch):
    return ('a\u03a3' + ch).lower()[1] == '\u03c2'


def linebreak(ch):
    return ('a' + ch + 'b').splitlines() == ['a', 'b']


def splits(ch):
    return ('a' + ch + 'b').split() == ['a', 'b'] and (ch + 'a' + ch).strip() == 'a'


def title_after(ch):
    return ('a' + ch).title()[1:]


CONTEXTS = [xid_continue, cased, sigma_after, sigma_before, linebreak, splits]


def digests():
    names = PREDICATES + MAPPINGS + [f.__name__ for f in CONTEXTS] + ['title_after', 'sre_tolower', 'sre_iscased']
    return {n: hashlib.sha256() for n in names}, {n: 0 for n in names if n not in ('title_after', 'sre_tolower')}


def contexts(hs, counts, cs):
    for f in CONTEXTS:
        b = bytes(map(f, cs))
        hs[f.__name__].update(b)
        counts[f.__name__] += sum(b)
    hs['title_after'].update('\0'.join(map(title_after, cs)).encode('utf-8', 'surrogatepass'))


def sweep_chunk(hs, counts, lo):
    cs = list(map(chr, range(lo, lo + CHUNK)))
    for name in PREDICATES:
        b = bytes(map(getattr(str, name), cs))
        hs[name].update(b)
        counts[name] += sum(b)
    for name in MAPPINGS:
        m = list(map(getattr(str, name), cs))
        counts[name] += sum(map(operator.ne, m, cs))
        hs[name].update('\0'.join(m).encode('utf-8', 'surrogatepass'))
    # (the contexts, in Python, over the planes with what is assigned and not private: 0 to 3 and 14)
    if lo < 0x40000 or 0xe0000 <= lo < 0xf0000:
        contexts(hs, counts, cs)
    lows = map(_sre.unicode_tolower, range(lo, lo + CHUNK))
    hs['sre_tolower'].update('\0'.join(map(chr, lows)).encode('utf-8', 'surrogatepass'))
    b = bytes(map(_sre.unicode_iscased, range(lo, lo + CHUNK)))
    hs['sre_iscased'].update(b)
    counts['sre_iscased'] += sum(b)


def sweep():
    hs, counts = digests()
    for lo in range(0, 0x110000, CHUNK):
        sweep_chunk(hs, counts, lo)
    for name, h in hs.items():
        print(name, counts.get(name, '-'), h.hexdigest()[:24])


def text(lo):
    # (every code point of the chunk but the surrogates, which a str of them all would pair)
    if lo == 0:
        return ''.join(map(chr, range(0xd800))) + ''.join(map(chr, range(0xe000, CHUNK)))
    return ''.join(map(chr, range(lo, lo + CHUNK)))


def re_classes():
    hs = {}
    for pat in (r'\d', r'\w', r'\s', r'\W', r'(?i)[a-z\u03c3]', r'(?i)[^\W\d]', r'\b\w', r'(?a)\w'):
        hs[pat] = hashlib.sha256()
    for lo in range(0, 0x110000, CHUNK):
        s = text(lo)
        for pat, h in hs.items():
            h.update(''.join(re.findall(pat, s)).encode())
    for pat, h in hs.items():
        print('re', ascii(pat), h.hexdigest()[:24])


def known():
    # (what the JVM's Unicode answered otherwise)
    t('casefold Cherokee', lambda: ['\u13a0'.casefold(), '\uab70'.casefold(), '\u13f8'.casefold(), '\u13f0'.casefold()])
    t('casefold', lambda: ['\u0131'.casefold(), '\u1e9e'.casefold(), '\u00df'.casefold(), '\u0130'.casefold(), '\ufb05'.casefold()])
    t('isidentifier start', lambda: [c.isidentifier() for c in '\u037a\u0e33\u309b\u309c\u2118\u212e\u1885\u00b7_'])
    t('isidentifier continue', lambda: [('x' + c).isidentifier() for c in '\u037a\u0e33\u309b\u200c\u200d\u00b7\u0387\u1369\u19da'])
    t('isdigit', lambda: [c.isdigit() for c in '\u00b2\u0c78\u17f0\u215f\u2189\u3192\u3220\u3280\U00010107\U0001d360'])
    t('isnumeric', lambda: [c.isnumeric() for c in '\u4e00\u4e8c\u4e09\u56db\u4e94\u5146\u96f6\u3007\u5341\uf96b'])
    t('title special', lambda: ['\u0149'.title(), '\u01f0'.title(), '\u0390'.title(), '\u1f80'.title(), '\ufb01x'.title()])
    t('swapcase titlecase', lambda: ['\u01c5'.swapcase(), '\u01c8'.swapcase(), '\u01cb'.swapcase(), '\u01f2'.swapcase(), '\u1f88'.swapcase()])
    t('upper special', lambda: ['\u00df'.upper(), '\u0149'.upper(), '\u1f80'.upper(), '\ufb00'.upper(), '\u0587'.upper()])
    t('lower special', lambda: ['\u0130'.lower(), '\u1e9e'.lower(), '\u2126'.lower(), '\u212a'.lower()])
    t('re ignorecase', lambda: [bool(re.fullmatch(p, s, re.I)) for p, s in [('\u212a', 'k'), ('\u017f', 'S'), ('\u03a3', '\u03c2'),
                                                                          ('\u0131', 'I'), ('\u0130', 'i'), ('\u01c5', '\u01c6')]])


def final_sigma():
    S = '\u03a3'
    for s in ['A' + S, 'A' + S + 'B', 'A' + S + ' ', 'A.' + S, 'A' + S + "'B", S + 'A', 'A' + S + S, '\u039f\u0394\u039f\u03a3',
              'A\u0345' + S, '1' + S, 'A\U0001d400' + S, 'A' + S + '\u00ad', 'A' + S + '\u00adB', '\u1f88' + S]:
        t('sigma ' + ascii(s), lambda: (s.lower(), s.capitalize(), s.swapcase(), s.title(), s.casefold()))


def words():
    for s in ["they're bill's friends", '\ufb01sh \ufb02our', '\u01c4ungla \u01c6ungla', '\u00dfa', '\u0149a', 'a\u0345b',
              '\u1f80\u1f88 \u1fb3', '\u03b1\u03b2 \u0391\u0392', 'x1y 2z', '\U00010428\U00010400 \U00010400x', '\u13a0\u13a1 \uab70']:
        t('case ' + ascii(s), lambda: (s.title(), s.capitalize(), s.swapcase(), s.upper(), s.lower(), s.casefold()))
        t('preds ' + ascii(s), lambda: (s.istitle(), s.isupper(), s.islower(), s.isalpha(), s.isprintable()))
    for s in ['A\u01c5', '\u01c5a', '\u01c5A', 'Ab Cd', 'Ab cd', 'A\u02b0', '\u2160', '\u2170', '\u24b6', '\u1f88', '\u01c5\u01c5']:
        t('istitle ' + ascii(s), lambda: (s.istitle(), s.isupper(), s.islower()))


def whitespace():
    ws = ''.join(c for c in map(chr, range(0x3001)) if c.isspace())
    t('whitespace', lambda: ws)
    t('split', lambda: ('a' + ws + 'b' + ws).split())
    t('split max', lambda: ('a\u3000b\u2003c\x1cd').split(None, 2))
    t('rsplit max', lambda: ('a\u3000b\u2003c\x1cd').rsplit(None, 2))
    t('strip', lambda: ((ws + 'a' + ws).strip(), (ws + 'a').lstrip(), ('a' + ws).rstrip(), '\u200ba\ufeff'.strip()))
    t('splitlines', lambda: 'a\nb\rc\r\nd\x0be\x0cf\x1cg\x1dh\x1ei\x85j\u2028k\u2029l\x1fm'.splitlines())
    t('splitlines keep', lambda: 'a\r\nb\x85c\u2028d\n\r'.splitlines(True))
    t('repr', lambda: repr('a\x00\x7f\x80\xa0\xad\u0378\u200b\u2028\ue000\U000e0001\u00e9\u3000 \ud800'))


def numbers():
    t('int', lambda: [int('\u0661\u0662\u0663'), int('\uff11\uff12'), int('\u3000 12\u2003\n'), int('-\u0665'), int('\x8512\x85')])
    t('int base', lambda: [int('\u0661\u0660', 16), int('0x\u0661', 0), int('1_\u0662')])
    t('int digits', lambda: [int(c) for c in map(chr, range(0x110000)) if c.isdecimal()][::7])
    for s in ['\u00b9', '\u2160', '\uff21', '\x1c12', '12\x00', '12\x7f', '\u0661x']:
        t('int ' + ascii(s), lambda: int(s, 16 if s == '\uff21' else 10))
    t('float', lambda: [float('\u0661.\u0665'), float('\u0661e\u0662'), float('\u2003 1.5 \u3000'), float('1_0.5'), float('+infinity')])
    for s in ['\uff11\uff0e\uff15', '\u0661.x', '1d', '0x1p3', '\x1c1.5', '1__0', ' ']:
        t('float ' + ascii(s), lambda: float(s))
    t('complex', lambda: [complex('\u0661+\u0662j'), complex('\u3000(1+2j)\u2003'), complex('1_0+2_0j')])
    for s in ['\u0661+\u2160j', ' 1 + 2j', '1__0j']:
        t('complex ' + ascii(s), lambda: complex(s))
    t('fromhex', lambda: float.fromhex(' 0x1.8p1 '))
    t('fromhex digit', lambda: float.fromhex('0x\uff11'))


def formats():
    t('field', lambda: ('{\u0661}'.format('a', 'b'), '{0[\u0661]}'.format(['a', 'b']), '{\uff11}'.format('a', 'b')))
    t('field key', lambda: '{\u00b9}'.format('a'))
    t('spec', lambda: (format(1, '\u0663'), '{:\uff15}|'.format('ab'), format(1.23456, '.\u0662'), format(1, '\u0661>5')))
    t('percent', lambda: '%\u00b35d' % 1)
    t('percent ff', lambda: '%\uff15d' % 1)


def compiled(src):
    g = {'__name__': 'm'}
    exec(src, g)
    return sorted(k for k in g if k != '__builtins__')


def identifiers():
    for src in ['\ufb01 = 1', '\U0001d422f = 1', '\U0001d426atch = 2', 'x\u00b7y = 1', 'x\u200c = 1', '\u2160 = 4',
                'e\u0301t\u00e9 = 1', 'x\U000e0100 = 1', 'class C:\n  def __\U0001d41f(self): pass\nr = dir(C)[0]',
                'def g(\ufb01): return fi\ny = g(4)', 'import \U0001d426ath', 'x = f"{1!\uff52}"',
                '\u037a = 1', 'x\u037a = 1', '\u0e33 = 1', 'x\u309b = 1', 'a\u20acb = 1', '\u00b2 = 1', 'x = 1\u00a0+ 1',
                '\U0001d413rue = 1', 'x = (1).\U0001d40done', 'x = 0x\uff11', 'x = f"{1!\ufb01}"']:
        t('source ' + ascii(src), lambda: compiled(src))
    t('slots', lambda: type('S', (), {'__slots__': ('x\u037a',)}))


sweep()
re_classes()
known()
final_sigma()
words()
whitespace()
numbers()
formats()
identifiers()
