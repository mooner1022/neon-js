# CPython's string package, bundled, over _string (Kotlin, CPython's format-string parser); string.templatelib.
import _string
import string
from string.templatelib import Template, Interpolation, convert


def t(label, f):
    try:
        print(label, "->", f())
    except Exception as e:
        print(label, "!!", type(e).__name__, e)


def parser():
    for s in ['a{0!r:>5}b{}', 'a{{b}}c', '{x.y[0]:{w}}', 'tail', '', '{}{}', '{:}', '{!s}', 'x{{', '}}y', '{0[a.b]}', '{a!r}', '{0!x}', '{0:{1:{2}}}']:
        print(repr(s), list(_string.formatter_parser(s)))
    for s in ['{', '}', 'a{0', '{0!}', '{0!r', '{[}', '{a{b}', '{0!rx}']:
        t(repr(s), lambda: list(_string.formatter_parser(s)))
    for f in ['0', 'x.y[0]', 'a[1].b', '12.real', '', 'x[a][b]', '[0]', 'a.', 'a[0]x', 'a[0', '٣']:
        t(repr(f), lambda: (lambda r: (r[0], list(r[1])))(_string.formatter_field_name_split(f)))
    print(type(_string.formatter_parser('')).__name__)


def module():
    print(string.ascii_letters, string.digits, string.hexdigits, string.octdigits, repr(string.whitespace), string.punctuation)
    print(len(string.printable), string.capwords('hello   world foo'), string.capwords('a-b-c', '-'))
    f = string.Formatter()
    print(f.format('{0}-{name}-{1:>4}', 'a', 'b', name='n'), f.vformat('{x!r}', (), {'x': 'q'}), list(f.parse('a{b}c')))
    tpl = string.Template('$who likes ${what}; $$ cost')
    print(tpl.substitute(who='tim', what='kung pao'), tpl.safe_substitute(who='tim'), tpl.get_identifiers(), tpl.is_valid())
    t("template missing", lambda: tpl.substitute(who='x'))
    t("template bad", lambda: string.Template('$').substitute())

    class MyTemplate(string.Template):
        delimiter = '%'

    print(MyTemplate('%x and %{y}').substitute(x=1, y=2))
    tt = t"hi {1 + 1!r:>3} there"
    print(type(tt) is Template, tt.strings, [(i.value, i.expression, i.conversion, i.format_spec) for i in tt.interpolations], convert(5, 'r'), convert('x', 'a'))
    t("convert bad", lambda: convert(1, 'z'))


parser()
module()
