# _tokenize (CPython's lexer: TokenizerIter, tokenize.py's tokens) and token (its numbers and names).
import _tokenize, token, warnings

def t(label, f):
    try:
        print(label, "->", f())
    except Exception as e:
        print(label, "!!", type(e).__name__, e.args)

def lines(src):
    # (lines as a file's readline gives them: split at '\n' only)
    parts = src.split('\n')
    return [p + '\n' for p in parts[:-1]] + ([parts[-1]] if parts[-1] else [])

def toks(src, extra=True):
    it = iter(lines(src))
    return [(token.tok_name[ty], repr(s), start, end) for ty, s, start, end, line in _tokenize.TokenizerIter(it.__next__, extra_tokens=extra)]

def show(label, src, extra=True):
    try:
        for x in toks(src, extra):
            print(" ", label, *x)
    except SyntaxError as e:
        print(" ", label, "!!", type(e).__name__, e.msg, e.lineno, e.offset, repr(e.text), e.end_lineno, e.end_offset)

def token_module():
    t("numbers", lambda: (token.ENDMARKER, token.NAME, token.OP, token.FSTRING_START, token.TSTRING_END, token.COMMENT, token.NL, token.ENCODING, token.N_TOKENS, token.NT_OFFSET))
    t("names", lambda: [token.tok_name[i] for i in (0, 4, 5, 6, 54, 55, 63, 67)])
    t("exact", lambda: (token.EXACT_TOKEN_TYPES['->'], token.EXACT_TOKEN_TYPES[':='], token.EXACT_TOKEN_TYPES['...'], len(token.EXACT_TOKEN_TYPES)))
    t("predicates", lambda: (token.ISTERMINAL(5), token.ISNONTERMINAL(300), token.ISEOF(0), token.ISEOF(1)))
    t("all", lambda: (len(token.__all__), token.__all__[:5]))

def iterator_type():
    T = _tokenize.TokenizerIter
    t("type", lambda: (T.__name__, T.__module__, repr(T), sorted(n for n in dir(_tokenize) if not n.startswith('__'))))
    it = T(iter(['x\n']).__next__, extra_tokens=True)
    t("iter", lambda: (iter(it) is it, isinstance(it, T), next(it)))
    t("rest", lambda: (list(it), list(it)))
    t("no args", lambda: T())
    t("no extra", lambda: T(None))
    t("two positional", lambda: T(None, 1))
    t("keyword", lambda: T(None, extra_tokens=1, foo=2))
    t("encoding type", lambda: T(None, extra_tokens=1, encoding=None))
    t("not callable", lambda: list(T(None, extra_tokens=1)))
    t("not str", lambda: list(T(lambda: 1, extra_tokens=1)))
    t("not bytes", lambda: list(T(lambda: 'x', extra_tokens=1, encoding='utf-8')))
    t("subclass", lambda: type('S', (T,), {}))

def readline_raises():
    def boom():
        raise ValueError('boom')
    t("raises", lambda: list(_tokenize.TokenizerIter(boom, extra_tokens=True)))
    state = [0]
    def rl():
        state[0] += 1
        return 'x%d = %d\n' % (state[0], state[0]) if state[0] < 3 else ''
    t("empty str ends", lambda: [x[1] for x in _tokenize.TokenizerIter(rl, extra_tokens=True)])
    t("empty input", lambda: (list(_tokenize.TokenizerIter(iter([]).__next__, extra_tokens=True)), list(_tokenize.TokenizerIter(iter([]).__next__, extra_tokens=False))))

def program():
    src = 'def f(a, *b, **c) -> int:  # f\n    """doc\n    string"""\n\n    if a >= 0x_1f:\n        return a ** 2 // 3\n    return [x for x in b if x != 1.5e-3j]\n'
    show("prog", src)
    show("parser", src, False)

def raw_tuples():
    src = ['x = 1 # é\n', 'y="é" + é\n', 'z = (1,\n', '  2) \\\n', '  + 3']
    for extra in (True, False):
        for x in _tokenize.TokenizerIter(iter(src).__next__, extra_tokens=extra):
            print(" ", extra, x)

def indentation():
    show("indent", 'if x:\n    y\n  # c\n\n    z\nw\n')
    show("dedent eof", 'if x:\n  if y:\n    z')
    show("unindent", 'if x:\n  a\n b\n')
    show("tabs", 'if x:\n\ta\n        b\n')
    show("form feed", 'if x:\n\f  a\n  b\n')
    show("continuation", 'if x:\n    \\\n  y\n')
    show("brackets", 'x = [\n  1, # c\n\n  2,\n]\n')
    show("too deep", 'if 1:\n' + ''.join(' ' * i + 'if 1:\n' for i in range(1, 101)))

def newlines():
    show("no newline", 'x = 1')
    show("comment no newline", '# c')
    show("crlf", 'x = 1\r\n# c\r\ny\r')
    show("cr", 'a\rb\n')
    show("blank", '\n\n')

def numbers():
    for s in ['0', '0_0', '0777', '0o1_7', '0b1_0', '0x_1f', '1_000.000_1e1_0j', '.5', '1.', '1..2', '1e5j', '1.__class__']:
        show(s, s + '\n')
        show(s, s + '\n', False)

def number_errors():
    for s in ['1_', '1__0', '0x', '0o8', '0b2', '0b12', '1e+', '1abc', '0x1g', '07.5', '07j', '0_7']:
        show(s, s + '\n', False)

def fstrings():
    show("simple", 'f"a{x}b{y!r:>{w}.{p}}c"\n')
    show("debug", 'f"{x = }{y=!r:^20}"\n')
    show("braces", 'f"{{}}a{{b}}"\n')
    show("nested", 'f"{f\'{f"{x}"}\'}"\n')
    show("triple", 'f"""a\nb{x:\n}c\nd"""\n')
    show("raw", 'rf"\\{x}\\n"\n')
    show("named", 'f"\\N{BULLET} {x}"\n')
    show("spec braces", 'f"{x:{{}}}"\n')
    show("comment", 'f"""{x # c\n}"""\n')

def tstrings():
    show("t", 't"a{x!r:>10}b"\n')
    show("t triple", 't"""a\nb{x}c\nd"""\n')
    show("t raw", 'Rt"{x}" tR"{y}"\n')
    show("t in f", 'f"{t"{x}"}"\n')

def string_errors():
    for s in ['"abc', '"abc\\"', '"""abc\nd', 'f"abc', 'f"""abc\n', 't"{', 'f"{"', 'f"}"', 'f"{x"', 'f"{)}"', 'f"{x:\n}"', 'ub"x"', 'bf"x"', 'ft"x"']:
        show(repr(s), s + '\n')

def other_errors():
    for s in ['x = (\n', 'x = \\', 'x\\ y\n', 'x = )\n', 'x = (]\n', 'x = (\n]\n', '\x01\n', 'x = 1\x00\n', 'a€b = 1\n', 'a\u200bb = 1\n', '$ ? `\n']:
        show(repr(s), s, False)
    show("too many parens", 'x = ' + '(' * 201 + ')' * 201 + '\n')

def operators():
    src = 'a+=b-=c*=d/=e//=f%=g@=h&=i|=j^=k>>=l<<=m**=n\na->b:=c!=d<>e<=f>=g==h ... x.y\n'
    for ty, s, start, end, line in _tokenize.TokenizerIter(iter(lines(src)).__next__, extra_tokens=True):
        if ty == token.OP:
            print(" ", s, token.tok_name[token.EXACT_TOKEN_TYPES.get(s, token.OP)], start, end)

def unicode():
    show("columns", 'é = "\U0001f600" + λ  # \U0001f600 é\nx = 1\n')
    show("columns parser", 'é = "\U0001f600" + λ  # \U0001f600 é\nx = 1\n', False)
    show("multi-line", '"""\U0001f600\né""" + x\n')

def encodings():
    T = _tokenize.TokenizerIter
    t("latin-1", lambda: [x[1] for x in T(iter([b'x = "\xe9"\n']).__next__, extra_tokens=True, encoding='latin-1')])
    t("utf-8", lambda: [x[1] for x in T(iter([b'x = "\xc3\xa9"\n']).__next__, extra_tokens=True, encoding='utf-8')])
    t("replace", lambda: [x[1] for x in T(iter([b'x = "\xff"\n']).__next__, extra_tokens=True, encoding='utf-8')])

def warns():
    for src, extra in [('f"\\{x}"\n', True), ('1if x else 2\n', False), ('0x1for x in y\n', False)]:
        with warnings.catch_warnings(record=True) as w:
            warnings.simplefilter('always')
            show(repr(src), src, extra)
            print(" ", [(x.category.__name__, str(x.message), x.filename, x.lineno) for x in w])
        with warnings.catch_warnings():
            warnings.simplefilter('error')
            show(repr(src), src, extra)

def block_end():
    # (where a def ends, as inspect.getblock finds it: its DEDENT back to its column)
    src = 'x = 1\ndef f(a,\n      b):\n    if a:\n        return b\n    return a\ny = 2\n'
    depth = 0
    for ty, s, start, end, line in _tokenize.TokenizerIter(iter(lines(src)).__next__, extra_tokens=True):
        if ty == token.INDENT:
            depth += 1
        elif ty == token.DEDENT:
            depth -= 1
            if depth == 0:
                print("  f ends before line", start[0])
                break

token_module()
iterator_type()
readline_raises()
program()
raw_tuples()
indentation()
newlines()
numbers()
number_errors()
fstrings()
tstrings()
string_errors()
other_errors()
operators()
unicode()
encodings()
warns()
block_end()
