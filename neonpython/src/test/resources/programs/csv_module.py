# csv (its _csv written in Kotlin as CPython writes it in C): dialects and their checks, the registry, every parser
# state, every quoting mode both ways, escapes, strict mode, the field limit, DictReader, DictWriter and Sniffer, over
# io.StringIO and files opened with newline=''; CPython's messages. The output Linux CPython's.
import csv, _csv, io


def t(label, f):
    try:
        print(label, "->", ascii(f()))
    except Exception as e:
        print(label, "!!", type(e).__name__, ascii(str(e)))


def attrs(d):
    return (d.delimiter, d.quotechar, d.escapechar, d.doublequote, d.skipinitialspace, d.lineterminator, d.quoting, d.strict)


def rd(lines, *a, **k):
    r = csv.reader(lines, *a, **k)
    rows = []
    try:
        for row in r:
            rows.append(row)
    except csv.Error as e:
        rows.append("Error: %s (line %d)" % (e, r.line_num))
    return rows


class Sink:
    def __init__(self):
        self.parts = []

    def write(self, s):
        self.parts.append(s)
        return len(s)


def wr(rows, *a, **k):
    s = Sink()
    w = csv.writer(s, *a, **k)
    out = []
    for row in rows:
        try:
            out.append(w.writerow(row))
        except csv.Error as e:
            out.append("Error: %s" % e)
    return "".join(s.parts), out


def module():
    t("names", lambda: sorted(n for n in dir(_csv) if not n.startswith("__")))
    t("constants", lambda: (csv.QUOTE_MINIMAL, csv.QUOTE_ALL, csv.QUOTE_NONNUMERIC, csv.QUOTE_NONE, csv.QUOTE_STRINGS, csv.QUOTE_NOTNULL))
    t("version", lambda: (csv.__version__, _csv.__doc__))
    t("types", lambda: (_csv.Reader.__name__, _csv.Writer.__name__, _csv.Dialect.__module__, csv.Error.__mro__))
    t("excel", lambda: attrs(csv.get_dialect("excel")))
    t("excel-tab", lambda: attrs(csv.get_dialect("excel-tab")))
    t("unix", lambda: attrs(csv.get_dialect("unix")))
    t("no instances", lambda: _csv.Reader())
    t("no instances 2", lambda: _csv.Writer())


def dialect_checks():
    for k, v in [("delimiter", 1), ("delimiter", ""), ("delimiter", "ab"), ("quotechar", b'"'), ("escapechar", "xy"),
                 ("lineterminator", None), ("quoting", True), ("quoting", 6), ("quoting", 2**40), ("quoting", 1.0),
                 ("delimiter", "\n"), ("escapechar", "\r"), ("quotechar", " ")]:
        t("%s=%r" % (k, v), lambda: attrs(_csv.Dialect(**{k: v})))
    t("space sis", lambda: _csv.Dialect(quotechar=" ", skipinitialspace=True))
    t("quotechar None", lambda: attrs(_csv.Dialect(quotechar=None)))
    t("quotechar None quoting", lambda: _csv.Dialect(quotechar=None, quoting=csv.QUOTE_ALL))
    t("lineterminator", lambda: _csv.Dialect(delimiter=";", lineterminator=";\n"))
    t("same chars", lambda: _csv.Dialect(delimiter="x", quotechar="x"))
    t("same chars 2", lambda: _csv.Dialect(escapechar="x", quotechar="x"))
    t("unknown", lambda: _csv.Dialect("nope"))
    t("typo", lambda: csv.reader([], delimeter=";"))
    t("bad keyword", lambda: csv.reader([], foo=1))
    t("too many", lambda: _csv.Dialect(*range(10)))
    t("by name and position", lambda: _csv.Dialect("excel", ";", delimiter=","))
    t("csv.Dialect()", lambda: csv.Dialect())


def dialect_sources():
    class Semi(csv.excel):
        delimiter = ";"
        quoting = csv.QUOTE_ALL
    t("class", lambda: attrs(_csv.Dialect(Semi)))
    t("instance", lambda: attrs(_csv.Dialect(Semi())))
    t("name, override", lambda: attrs(_csv.Dialect("unix", quoting=csv.QUOTE_MINIMAL)))
    t("other object", lambda: attrs(_csv.Dialect(5)))
    d = csv.get_dialect("excel")
    t("reused", lambda: (_csv.Dialect(d) is d, csv.reader([], "excel").dialect is d, csv.reader([]).dialect is d))
    class D(_csv.Dialect):
        pass
    x = D(delimiter="|")
    t("subclass", lambda: (type(x).__name__, attrs(x), csv.writer(Sink(), x).dialect is x))
    t("immutable", lambda: setattr(d, "delimiter", ";"))
    t("immutable 2", lambda: setattr(d, "strict", True))
    t("no dict", lambda: setattr(d, "x", 1))
    t("pickle", lambda: d.__reduce_ex__(2))


def registry():
    t("register", lambda: csv.register_dialect("pipes", delimiter="|", quoting=csv.QUOTE_NONE, escapechar="\\"))
    t("get", lambda: attrs(csv.get_dialect("pipes")))
    t("list", lambda: csv.list_dialects())
    t("use", lambda: rd(["a|b\\|c|d\n"], "pipes"))
    t("unregister", lambda: csv.unregister_dialect("pipes"))
    t("unregister again", lambda: csv.unregister_dialect("pipes"))
    t("get unknown", lambda: csv.get_dialect("pipes"))
    t("bad name", lambda: csv.register_dialect(1))
    t("bad args", lambda: csv.register_dialect())
    t("unhashable", lambda: csv.get_dialect([]))


def limits():
    old = csv.field_size_limit()
    t("limit", lambda: (old, csv.field_size_limit(4), csv.field_size_limit()))
    t("within", lambda: rd(["abcd,efgh\n"]))
    t("over", lambda: rd(["abcde\n"]))
    t("over quoted", lambda: rd(['"ab\n', 'cd"\n']))
    t("bad limit", lambda: csv.field_size_limit(True))
    t("big limit", lambda: csv.field_size_limit(2**64))
    t("reset", lambda: csv.field_size_limit(old))


def parsing():
    t("simple", lambda: rd(["a,b,c\r\n", "1,2,3\r\n"]))
    t("no line ends", lambda: rd(["a,b", "c"]))
    t("empty lines", lambda: rd(["", "\n", "\r\n", ",\n"]))
    t("quoted", lambda: rd(['"a","b,c","d""e",""\n']))
    t("multiline", lambda: rd(['"a\r\n', 'b",c\r\n', 'd\r\n']))
    t("quote mid field", lambda: rd(['a"b"c,"d"e\n']))
    t("unterminated", lambda: rd(['"abc\n']))
    t("doublequote off", lambda: rd(['"a""b"\n'], doublequote=False))
    t("skipinitialspace", lambda: rd(['a, b,  "c"\n'], skipinitialspace=True))
    t("space delimiter", lambda: rd(["a  b c\n"], delimiter=" ", skipinitialspace=True))
    t("non-BMP", lambda: rd(["\U0001F600|é|x\n"], delimiter="|"))
    t("non-BMP delimiter", lambda: rd(["a\U0001F600b\n"], delimiter="\U0001F600"))
    t("newline in field", lambda: rd(["a\nb\n"]))
    t("bytes", lambda: rd([b"a,b\n"]))
    t("not iterable", lambda: csv.reader(5))


def escapes():
    e = {"escapechar": "\\"}
    t("unquoted", lambda: rd(['a\\,b,c\\\\d\n'], **e))
    t("quoted", lambda: rd(['"a\\"b",c\n'], **e))
    t("escaped newline", lambda: rd(['a\\\n', 'b,c\n'], **e))
    t("escaped newline, field goes on", lambda: rd(['a\\\n', 'b', 'c\n'], **e))
    t("at end of line", lambda: rd(['a\\', 'b\n'], **e))
    t("quote none", lambda: rd(['a\\"b,"c"\n'], quoting=csv.QUOTE_NONE, **e))


def strict():
    s = {"strict": True}
    t("after quote", lambda: rd(['"a"b,c\n']))
    t("after quote strict", lambda: rd(['"a"b,c\n'], **s))
    t("after quote strict semi", lambda: rd(["x;'a'b\n"], delimiter=";", quotechar="'", **s))
    t("end of data", lambda: rd(['a,"b\n', 'c'], **s))
    r = csv.reader(["a,b\n", '"c\n', 'd"\n', "e\n"])
    t("line_num", lambda: [(row, r.line_num) for row in r])


def reader_quoting():
    t("nonnumeric", lambda: rd(['1,"2",3.5,-1e3, 4 ,\n'], quoting=csv.QUOTE_NONNUMERIC))
    t("nonnumeric bad", lambda: rd(['1,abc\n'], quoting=csv.QUOTE_NONNUMERIC))
    t("strings", lambda: rd(['1,"2",,""\n'], quoting=csv.QUOTE_STRINGS))
    t("notnull", lambda: rd(['1,"2",,""\n'], quoting=csv.QUOTE_NOTNULL))
    t("none", lambda: rd(['1,"2",,""\n'], quoting=csv.QUOTE_NONE))


class Num:
    def __float__(self):
        return 1.5

    def __str__(self):
        return "num"


ROW = ["a", 1, 2.5, None, "", True, 10**20, "b,c", 'd"e', "f\ng", Num(), b"x"]


def writer_quoting():
    for q in range(6):
        t("quoting %d" % q, lambda: wr([ROW], quoting=q, escapechar="\\"))
        t("quoting %d empty" % q, lambda: wr([[], [""], [None], ["", None]], quoting=q, escapechar="\\"))
    t("space delimiter", lambda: wr([["", "a"], [None]], delimiter=" ", skipinitialspace=True))
    t("space delimiter strings", lambda: wr([["", "a"], [None]], delimiter=" ", skipinitialspace=True, quoting=csv.QUOTE_STRINGS))


def writer_details():
    t("need to escape", lambda: wr([["a,b"], ["c"]], quoting=csv.QUOTE_NONE))
    t("doublequote off", lambda: wr([['a"b']], doublequote=False))
    t("doublequote off escaped", lambda: wr([['a"b']], doublequote=False, escapechar="\\"))
    t("lineterminator", lambda: wr([["a|b", "c"]], lineterminator="|"))
    t("unix", lambda: wr([["a", 1, None]], "unix"))
    t("not iterable", lambda: wr([5]))
    s = Sink()
    w = csv.writer(s, delimiter=";")
    t("returns", lambda: (w.writerow(["a", "b"]), w.writerows([["c"], ("d", "e")]), s.parts, w.dialect.delimiter))
    t("one argument", lambda: w.writerow())
    t("no write", lambda: csv.writer(object()))


def roundtrips():
    rows = [["a", "b,c", 'd"e', "f\ng", "h\r\ni", "", " j "], ["1", "2.5"], [""], []]
    for q in [csv.QUOTE_MINIMAL, csv.QUOTE_ALL, csv.QUOTE_NONNUMERIC]:
        s = io.StringIO()
        csv.writer(s, quoting=q).writerows(rows)
        t("roundtrip %d" % q, lambda: (s.getvalue(), list(csv.reader(io.StringIO(s.getvalue()), quoting=q)) == rows))
    s = io.StringIO()
    csv.writer(s, quoting=csv.QUOTE_NONE, escapechar="\\").writerow(["a,b", 'c"d', "e\\f", "g\nh"])
    t("roundtrip none", lambda: (s.getvalue(), list(csv.reader(io.StringIO(s.getvalue()), quoting=csv.QUOTE_NONE, escapechar="\\"))))
    s = io.StringIO()
    csv.writer(s, quoting=csv.QUOTE_NOTNULL).writerow(["a", None, 1, ""])
    t("roundtrip notnull", lambda: (s.getvalue(), list(csv.reader(io.StringIO(s.getvalue()), quoting=csv.QUOTE_NOTNULL))))


def dict_reader():
    data = "name,age\r\nann,30\r\nbob\r\n\r\ncy,40,x,y\r\n"
    r = csv.DictReader(io.StringIO(data), restkey="rest", restval="?")
    t("fieldnames", lambda: r.fieldnames)
    t("rows", lambda: list(r))
    t("line_num", lambda: r.line_num)
    t("given fieldnames", lambda: list(csv.DictReader(["1;2"], fieldnames=["a", "b"], delimiter=";")))
    t("empty", lambda: list(csv.DictReader(io.StringIO(""))))


def dict_writer():
    s = io.StringIO()
    w = csv.DictWriter(s, ["a", "b"], restval="-")
    t("header", lambda: w.writeheader())
    t("rows", lambda: (w.writerow({"a": 1}), w.writerows([{"b": "x,y"}, {"a": None, "b": 2.5}])))
    t("extra", lambda: w.writerow({"a": 1, "z": 2}))
    t("value", lambda: s.getvalue())
    s = io.StringIO()
    w = csv.DictWriter(s, ["a"], extrasaction="ignore", quoting=csv.QUOTE_ALL)
    w.writerow({"a": 1, "z": 2})
    t("ignore", lambda: s.getvalue())
    t("bad extrasaction", lambda: csv.DictWriter(s, ["a"], extrasaction="no"))


SAMPLES = ['a,b,c\r\n1,2,3\r\n4,5,6\r\n', 'name;age;city\n"Smith, J";42;"New York"\nDoe;37;Boston\n',
           "x\ty\n1\t2\n", "'a'|'b'\n'1'|'2'\n", 'id,value\n1,"he said ""hi"""\n2,"ok"\n', "abc\ndef\n"]


def sniffer():
    sn = csv.Sniffer()
    for i, s in enumerate(SAMPLES):
        def sniff(s=s):
            d = sn.sniff(s)
            return (d.delimiter, d.quotechar, d.doublequote, d.skipinitialspace, d.quoting)
        t("sniff %d" % i, sniff)
        t("has_header %d" % i, lambda: sn.has_header(s))
    t("delimiters", lambda: sn.sniff("a;b,c\n1;2,3\n", delimiters=";").delimiter)
    d = sn.sniff(SAMPLES[1])
    t("read sniffed", lambda: list(csv.reader(io.StringIO(SAMPLES[1]), d)))


def files():
    with open("out.csv", "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(["id", "text"])
        w.writerows([[1, "line1\nline2"], [2, 'say "hi"'], [3, "é"]])
    t("bytes", lambda: open("out.csv", "rb").read())
    with open("out.csv", newline="") as f:
        t("read", lambda: list(csv.reader(f)))
    with open("out.csv", newline="") as f:
        t("dict read", lambda: list(csv.DictReader(f)))
    with open("out.csv", "rb") as f:
        t("binary", lambda: next(csv.reader(f)))
    with open("dw.csv", "w", newline="") as f:
        dw = csv.DictWriter(f, ["k", "v"], lineterminator="\n")
        dw.writeheader()
        dw.writerows([{"k": "a", "v": 1}, {"k": "b"}])
    with open("dw.csv", newline="") as f:
        t("dict file", lambda: (f.read(), f.seek(0), list(csv.DictReader(f))))


module()
dialect_checks()
dialect_sources()
registry()
limits()
parsing()
escapes()
strict()
reader_quoting()
writer_quoting()
writer_details()
roundtrips()
dict_reader()
dict_writer()
sniffer()
files()
