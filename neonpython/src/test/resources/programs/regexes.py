# CPython's re, bundled, over _sre (Kotlin, CPython's engine): its syntax, flags, groups, backtracking, the module's functions, errors.
import re


def t(label, f):
    try:
        print(label, "->", f())
    except Exception as e:
        print(label, "!!", type(e).__name__, e)


def basics():
    t("match", lambda: (re.match('a+', 'aaab'), re.match('b', 'ab'), re.fullmatch('a+', 'aa'), re.fullmatch('a+', 'aab')))
    t("search", lambda: (re.search('b+', 'abbbc'), re.search('z', 'abc'), re.search('^c', 'abc'), re.search('c$', 'abc\n')))
    t("groups", lambda: (lambda m: (m.groups(), m.group(0, 2), m[1], m.span(2), m.start(1), m.end(), m.lastindex, m.lastgroup, m.groupdict()))(re.match(r'(\w+) (?P<last>\w+)', 'John Smith')))
    t("optional", lambda: re.match(r'(a)(b)?(c)', 'ac').groups())
    t("alternation", lambda: [m.group() for m in re.finditer('cat|dog|bird', 'dog cat bird fish')])
    t("classes", lambda: re.findall(r'[a-c]+|\d+|[^\w\s]', 'abc 123 x! ba'))
    t("quantifiers", lambda: (re.findall('a{2,3}', 'aaaaaaa'), re.findall('a{2,3}?', 'aaaaaaa'), re.findall('ab*?', 'abbb'), re.match('(ab)*', 'ababab').group(), re.match('(a|ab)*c', 'ababc').group()))
    t("lazy", lambda: (re.match('<.*>', '<a><b>').group(), re.match('<.*?>', '<a><b>').group(), re.match('(a+?)(a*)', 'aaa').groups()))
    t("anchors", lambda: (re.findall(r'^\w', 'ab\ncd', re.M), re.findall(r'\w$', 'ab\ncd', re.M), re.findall(r'\bfoo\b', 'foo foobar foo.'), re.findall(r'\Bo', 'foo'), re.search(r'\Aa', 'ba'), re.search(r'a\Z', 'a\n')))
    t("dotall", lambda: (re.match('a.b', 'a\nb'), re.match('a.b', 'a\nb', re.S).group()))
    t("ignorecase", lambda: (re.findall('abc', 'ABC abc aBc', re.I), re.match('[a-z]+', 'HeLLo', re.I).group(), re.match('straße', 'STRASSE', re.I), re.match('k', 'K', re.I).group(), re.findall('[^a]', 'Aab', re.I)))
    t("backrefs", lambda: (re.match(r'(\w)\1', 'aa').group(), re.match(r'(\w)\1', 'ab'), re.search(r'(?P<q>["\']).*?(?P=q)', 'say "hi" now').group(), re.match(r'(a)|b\1', 'b')))
    t("lookaround", lambda: (re.findall(r'\w+(?=,)', 'a, b, c'), re.findall(r'(?<=\$)\d+', '$12 $34 56'), re.findall(r'\d+(?!px)', '12px 34em'), re.findall(r'(?<!-)\b\d+', '-1 2 -3 4')))
    t("conditional", lambda: [re.match(r'(<)?(\w+)(?(1)>|$)', s) for s in ('<a>', 'a', '<a')])
    t("atomic", lambda: (re.match(r'(?>a+)b', 'aaab').group(), re.match(r'(?>a+)ab', 'aaab'), re.match(r'a++b', 'aab').group(), re.match(r'a*+a', 'aaa'), re.match(r'(?:ab)++c', 'ababc').group(), re.match(r'(?:ab)?+', 'ab').group()))
    t("unicode", lambda: (re.findall(r'\w+', 'héllo wörld 日本'), re.findall(r'\d', '٣4'), re.findall(r'\d', '٣4', re.A), re.findall(r'\s', 'a　b\xa0c'), re.match(r'\w+', 'naïve', re.A).group()))
    t("verbose", lambda: re.match(r'''(?x) (\d+)  # number
                                        \s* ([a-z]+) # unit''', '12 kg').groups())
    t("inline", lambda: (re.match('(?i)ab', 'AB').group(), re.match('a(?i:b)c', 'aBc').group(), re.match('a(?i:b)c', 'aBC')))
    t("named", lambda: (re.compile('(?P<y>\\d{4})-(?P<m>\\d\\d)').match('2024-05').groupdict(), re.compile('(?P<a>x)(?P<b>y)?').groupindex))
    t("empty", lambda: (re.findall('', 'ab'), re.split('x*', 'axb'), re.sub('x*', '-', 'abc'), list(re.finditer(r'\b', 'a b'))))
    t("nested", lambda: (re.match(r'((a)(b(c)))', 'abc').groups(), re.match(r'(a(b)?)+', 'aba').groups(), re.match(r'(?:(a)|b)+', 'ab').groups()))
    t("repeat groups", lambda: (re.match(r'(\w)+', 'abc').group(1), re.match(r'(a|b)*?c', 'abac').groups(), re.match(r'(a*)*', 'b').groups(), re.match(r'(a*)+', 'b').groups(), re.match(r'(a|)+', 'aa').groups()))


def apis():
    p = re.compile(r'(\d+)')
    t("sub", lambda: (p.sub(r'<\1>', 'a1b22'), p.sub(lambda m: str(int(m[1]) * 2), 'a1b22'), p.subn('#', 'a1b22'), p.sub('#', 'a1b22', 1), re.sub(r'(?P<n>\d)', r'\g<n>\g<0>', 'a1'), re.sub('a', r'\n', 'a') == '\n'))
    t("split", lambda: (re.split(r'\W+', 'a, b;c'), re.split(r'(\W+)', 'a, b'), re.split(',', 'a,b,c', maxsplit=1), re.split('(a)|(b)', 'xaybz')))
    t("findall groups", lambda: (re.findall(r'(\w)(\d)?', 'a1b'), re.findall(r'(\w)=(\d)', 'a=1 b=2')))
    t("finditer", lambda: [(m.span(), m.group()) for m in re.finditer(r'\d+', 'a12b3c')])
    t("pos endpos", lambda: (p.search('a12b34', 3), p.match('a12', 1), p.search('a12345', 0, 3), p.findall('1 2 3', 2), p.match('12', 3)))
    t("escape", lambda: (re.escape('a.b*c?'), re.escape('hello world'), re.match(re.escape('1+1=2'), '1+1=2').group()))
    t("repr", lambda: (repr(p), repr(re.compile('x', re.I | re.M)), repr(re.compile(b'x')), repr(p.match('7'))))
    t("attrs", lambda: (p.pattern, p.flags, p.groups, dict(p.groupindex), p.match('5').re is p, p.match('5').string, p.match('5', 0).pos, p.search('a5').endpos))
    t("expand", lambda: re.match(r'(\w+) (\w+)', 'hello world').expand(r'\2 \1'))
    t("cache", lambda: (re.compile('a') is re.compile('a'), re.purge()))
    t("bytes", lambda: (re.findall(rb'\d+', b'a1b22'), re.sub(rb'a', b'X', b'banana'), re.match(rb'\w+', b'ab_1 c').group(), re.search(rb'(?i)AB', b'xab').span()))
    t("bytes errors", lambda: re.match('a', b'a'))
    t("bytes errors2", lambda: re.match(b'a', 'a'))
    t("flags", lambda: (re.I, re.IGNORECASE | re.M, int(re.S), re.RegexFlag.ASCII, repr(re.X)))
    t("scanner", lambda: (lambda s: (s.match(), s.match(), s.match()))(re.compile(r'\w').scanner('ab')))
    t("fullmatch alt", lambda: (re.fullmatch('a|ab', 'ab'), re.fullmatch('(?:a|ab)c', 'abc')))


def errors():
    for pat in ['(', ')', '[a', 'a**', '(?P<1>a)', r'\1', '(?<=a+)b', '[z-a]', r'\x', '(?P<n>a)(?P<n>b)', 'a{2,1}', '(?i', r'\p']:
        t("error " + pat, lambda: re.compile(pat))
    t("error attrs", lambda: (lambda e: (e.msg, e.pos, e.pattern, e.lineno, e.colno))(next(iter([exc for exc in [None] if False]), None) or _err()))
    t("no such group", lambda: re.match('(a)', 'a').group(2))
    t("bad repl", lambda: re.sub('a', r'\2', 'a'))
    t("repl type", lambda: re.sub('a', lambda m: 1, 'a'))


def _err():
    try:
        re.compile('ab(c')
    except re.error as e:
        return e


def perf():
    s = 'x' * 20000 + 'y'
    t("long", lambda: (re.search('y', s).start(), len(re.findall('x', s)), re.match('(x)*y', s).end(), re.match('(?:x|z)*y', s).end(), len(re.sub('x', '', s))))
    t("deep", lambda: re.match('(a?){14}a{14}', 'a' * 14).end())


basics()
apis()
errors()
perf()
