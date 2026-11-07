# CPython's pure modules over NeonPy: argparse, difflib, pprint, shlex, html (and its parser), urllib.parse, gettext, fractions, decimal (_pydecimal, C _decimal's messages), statistics, graphlib, colorsys, ipaddress; the output Linux CPython's.
import argparse, difflib, pprint, shlex, fractions, decimal, statistics, graphlib, colorsys, ipaddress, html, urllib.parse, gettext, io, sys
from html.parser import HTMLParser
from fractions import Fraction
from decimal import Decimal


def t(label, f):
    try:
        print(label, "->", f())
    except BaseException as e:
        print(label, "!!", type(e).__name__, e)


def args():
    p = argparse.ArgumentParser(prog='tool', description='A tool.')
    p.add_argument('files', nargs='*')
    p.add_argument('-n', '--count', type=int, default=3, help='how many')
    p.add_argument('-v', action='store_true')
    p.add_argument('--mode', choices=['a', 'b'], default='a')
    sub = p.add_subparsers(dest='cmd')
    s = sub.add_parser('run')
    s.add_argument('--fast', action='store_true')
    t("parse", lambda: vars(p.parse_args(['-n', '5', '-v', '--mode', 'b'])))
    t("parse sub", lambda: vars(p.parse_args(['run', '--fast'])))
    t("help", lambda: p.format_help())
    t("usage", lambda: p.format_usage())
    err = io.StringIO()
    old = sys.stderr
    sys.stderr = err
    try:
        t("error", lambda: p.parse_args(['-n', 'x']))
    finally:
        sys.stderr = old
    t("error text", lambda: err.getvalue())


def texts():
    a = ['one\n', 'two\n', 'three\n']
    b = ['one\n', 'tree\n', 'three\n', 'four\n']
    t("unified", lambda: ''.join(difflib.unified_diff(a, b, 'a', 'b')))
    t("ndiff", lambda: list(difflib.ndiff(['abc'], ['abd'])))
    t("ratio", lambda: (difflib.SequenceMatcher(None, 'abcd', 'bcde').ratio(), difflib.get_close_matches('appel', ['ape', 'apple', 'peach'])))
    t("pprint", lambda: pprint.pformat({'k%d' % i: list(range(i)) for i in range(8)}, width=40))
    t("pprint sorted", lambda: pprint.pformat({'b': {1, 2}, 'a': (1, [2, {'x': 'y' * 30}])}, width=30))
    t("shlex", lambda: (shlex.split('a "b c" d\\ e'), shlex.quote("it's"), shlex.join(['a b', 'c'])))
    t("html", lambda: (html.escape('<a href="x">&</a>'), html.unescape('&lt;&amp;&#39;&eacute;&nbsp;')))

    class P(HTMLParser):
        def __init__(self):
            super().__init__()
            self.out = []

        def handle_starttag(self, tag, attrs): self.out.append(('start', tag, attrs))
        def handle_endtag(self, tag): self.out.append(('end', tag))
        def handle_data(self, data): self.out.append(('data', data))
    hp = P()
    hp.feed('<p class="x">Hi <b>there</b>&amp;</p>')
    t("htmlparser", lambda: hp.out)
    u = urllib.parse.urlparse('https://user@ex.com:8080/a/b;p?q=1&r=2#frag')
    t("urlparse", lambda: (u, u.hostname, u.port, urllib.parse.parse_qs(u.query)))
    t("url misc", lambda: (urllib.parse.urlencode({'a': 'b c', 'd': 'é'}), urllib.parse.quote('a b/é'), urllib.parse.unquote('%C3%A9'), urllib.parse.urljoin('http://a/b/c', '../d')))
    t("gettext", lambda: (gettext.gettext('hello'), gettext.ngettext('one', 'many', 2), gettext.NullTranslations().gettext('x')))


def numbers():
    t("fraction", lambda: (Fraction(3, 4) + Fraction(1, 6), Fraction('0.125'), Fraction(1.1).limit_denominator(100), Fraction(7, 3) // 2, str(Fraction(-5, 10)), Fraction(1, 3) ** 2))
    t("decimal", lambda: (Decimal('1.1') + Decimal('2.2'), Decimal(1) / Decimal(7), Decimal('3.14159').quantize(Decimal('0.01')), Decimal('1e3').normalize(), Decimal(0.1)))
    ctx = decimal.Context(prec=5, rounding=decimal.ROUND_HALF_EVEN)
    t("decimal ctx", lambda: (ctx.divide(Decimal(2), Decimal(3)), ctx.sqrt(Decimal(2)), Decimal('NaN').is_nan(), Decimal('-0').is_signed()))
    t("decimal error", lambda: Decimal(1) / Decimal(0))
    data = [2, 4, 4, 4, 5, 5, 7, 9]
    t("stats", lambda: (statistics.mean(data), statistics.median(data), statistics.mode(data), statistics.pstdev(data), statistics.stdev(data), statistics.quantiles(data, n=4)))
    t("normal", lambda: (round(statistics.NormalDist(0, 1).cdf(1.0), 12), statistics.NormalDist(5, 2).inv_cdf(0.5), statistics.fmean([1, 2, 3])))
    ts = graphlib.TopologicalSorter({'d': {'b', 'c'}, 'b': {'a'}, 'c': {'a'}})
    t("toposort", lambda: list(ts.static_order()))
    t("cycle", lambda: list(graphlib.TopologicalSorter({'a': {'b'}, 'b': {'a'}}).static_order()))
    t("colorsys", lambda: (colorsys.rgb_to_hsv(0.2, 0.4, 0.4), colorsys.hls_to_rgb(0.5, 0.5, 0.5)))
    net = ipaddress.ip_network('192.168.0.0/30')
    t("ipaddress", lambda: (ipaddress.ip_address('10.0.0.1') + 5, list(net.hosts()), net.broadcast_address, ipaddress.ip_address('::1').is_loopback, list(net.subnets(new_prefix=31)), ipaddress.ip_interface('10.1.2.3/24').network))
    t("ip error", lambda: ipaddress.ip_address('300.1.1.1'))


def decimals():
    D = decimal.Decimal
    t("div0", lambda: D(1) / D(0))
    t("0/0", lambda: D(0) / D(0))
    t("syntax", lambda: D('x'))
    t("inf-inf", lambda: D('inf') - D('inf'))
    t("sqrt neg", lambda: D(-1).sqrt())
    c = decimal.Context(Emax=2, traps=[decimal.Overflow])
    t("overflow", lambda: c.create_decimal('1e10'))
    c2 = decimal.Context(traps=[decimal.Inexact])
    t("inexact", lambda: c2.divide(D(1), D(3)))
    t("quantize", lambda: D('1.23456').quantize(D('1e-10'), context=decimal.Context(prec=3)))
    t("int conv", lambda: int(D('nan')))
    t("module", lambda: (D.__module__, decimal.DivisionByZero.__module__, repr(D('1.5'))))


def main():
    decimals()
    args()
    texts()
    numbers()


main()
