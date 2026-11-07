# json, datetime, calendar, dataclasses, inspect (builtins' signatures among it), traceback: CPython's own, over NeonPy; the output Linux CPython's.
import json, datetime, calendar, traceback, inspect, dataclasses, io, sys, textwrap
from dataclasses import dataclass, field, asdict, astuple, replace, fields, InitVar, KW_ONLY


def t(label, f):
    try:
        print(label, "->", f())
    except Exception as e:
        print(label, "!!", type(e).__name__, e)


def jsons():
    t("dumps", lambda: (json.dumps({'a': [1, 2.5, None, True], 'b': 'é"\n'}), json.dumps([1, 2], indent=2), json.dumps({'b': 1, 'a': 2}, sort_keys=True, separators=(',', ':'))))
    t("dumps ascii", lambda: (json.dumps('é😀'), json.dumps('é', ensure_ascii=False), json.dumps(float('inf')), json.dumps(1e100), json.dumps(0.1)))
    t("loads", lambda: (json.loads('{"a": [1, 2.5e3, null, true, false], "b": "\\u00e9\\ud83d\\ude00"}'), json.loads('1'), json.loads('"x"'), json.loads('NaN')))
    t("loads error", lambda: json.loads('{"a": }'))
    t("loads error2", lambda: json.loads('[1, 2'))
    t("dumps error", lambda: json.dumps({1, 2}))
    t("hooks", lambda: (json.loads('{"a": 1}', object_hook=lambda d: sorted(d)), json.loads('1.5', parse_float=str), json.dumps(object(), default=lambda o: 'obj')))
    t("roundtrip", lambda: json.loads(json.dumps({'k': [{'x': i} for i in range(3)]})))


def datetimes():
    d = datetime.datetime(2024, 2, 29, 13, 45, 30, 123456)
    t("datetime", lambda: (d, repr(d), str(d), d.isoformat(), d.date(), d.time(), d.weekday(), d.isocalendar()))
    t("arith", lambda: (d + datetime.timedelta(days=1, hours=11), d - datetime.datetime(2024, 1, 1), datetime.timedelta(days=-1, seconds=5), datetime.timedelta(microseconds=1) * 1.5))
    t("strftime", lambda: (d.strftime('%Y-%m-%d %H:%M:%S.%f %a %b %j %p'), datetime.date(2024, 1, 5).strftime('%A %B %d')))
    t("fromiso", lambda: (datetime.datetime.fromisoformat('2024-02-29T13:45:30+09:00'), datetime.date.fromisoformat('2024-03-01'), datetime.time.fromisoformat('12:30')))
    tz = datetime.timezone(datetime.timedelta(hours=9), 'KST')
    t("tz", lambda: (d.replace(tzinfo=tz), d.replace(tzinfo=tz).utcoffset(), d.replace(tzinfo=tz).astimezone(datetime.timezone.utc), tz.tzname(None), datetime.timezone.utc))
    t("timestamp", lambda: (datetime.datetime.fromtimestamp(0, datetime.timezone.utc), datetime.datetime(2000, 1, 1, tzinfo=datetime.timezone.utc).timestamp()))
    t("strptime", lambda: datetime.datetime.strptime('2024-02-29 13:45', '%Y-%m-%d %H:%M'))
    t("errors", lambda: datetime.date(2023, 2, 29))
    t("compare", lambda: (datetime.date(2024, 1, 1) < datetime.date(2024, 1, 2), sorted([datetime.time(3), datetime.time(1)]), hash(datetime.date(2024, 1, 1)) == hash(datetime.date(2024, 1, 1))))
    t("calendar", lambda: (calendar.isleap(2024), calendar.monthrange(2024, 2), calendar.weekday(2024, 2, 29), calendar.month(2024, 2).splitlines()[:3], calendar.day_name[0]))


@dataclass(order=True)
class P:
    x: int
    y: int = 0
    tags: list = field(default_factory=list, compare=False)


@dataclass(frozen=True)
class F:
    a: str
    b: float = 1.5


@dataclass
class K:
    a: int
    _: KW_ONLY
    b: int = 2
    c: InitVar[int] = 5

    def __post_init__(self, c):
        self.d = self.a + c


@dataclass(slots=True)
class S:
    v: int


def dataclass_part():
    p = P(1, 2)
    t("dataclass", lambda: (p, P(1) < P(2), P(1, 2) == P(1, 2, ['x']), asdict(p), astuple(p), replace(p, y=5), [f.name for f in fields(P)]))
    t("frozen", lambda: (F('x'), hash(F('x')) == hash(F('x')), F('x') == F('x')))
    t("frozen set", lambda: setattr(F('x'), 'a', 'y'))
    t("kw_only", lambda: (K(1, b=3), K(1).d, K(1, c=10).d))
    t("kw_only bad", lambda: K(1, 2))
    t("slots", lambda: (S(1), S.__slots__, hasattr(S(1), '__dict__')))
    t("missing", lambda: P())
    t("is_dataclass", lambda: (dataclasses.is_dataclass(P), dataclasses.is_dataclass(p), dataclasses.is_dataclass(1)))
    t("make", lambda: dataclasses.make_dataclass('M', [('q', int), ('r', str, field(default='z'))])(1))


def g(a, b: int = 2, *args, c, d='x', **kw) -> str:
    """g's doc."""
    return a


class C:
    def m(self, x, /, y=1): pass

    @classmethod
    def cm(cls, z): pass

    @property
    def p(self): return 1


def inspects():
    t("signature", lambda: (str(inspect.signature(g)), str(inspect.signature(C.m)), str(inspect.signature(C().m)), str(inspect.signature(C.cm)), str(inspect.signature(P))))
    t("params", lambda: [(n, str(p.kind), p.default) for n, p in inspect.signature(g).parameters.items()])
    t("bind", lambda: inspect.signature(g).bind(1, c=3).arguments)
    t("bind bad", lambda: inspect.signature(g).bind(1))
    t("predicates", lambda: (inspect.isfunction(g), inspect.ismethod(C().m), inspect.isclass(C), inspect.isgeneratorfunction(g), inspect.iscoroutinefunction(g), inspect.ismodule(inspect), inspect.isbuiltin(len)))
    t("members", lambda: [n for n, _ in inspect.getmembers(C) if not n.startswith('__')])
    t("getdoc", lambda: (inspect.getdoc(g), inspect.cleandoc('  a\n    b')))
    t("getsource lib", lambda: inspect.getsource(textwrap.indent).splitlines()[0])
    t("getfile", lambda: inspect.getfile(textwrap).endswith('textwrap.py'))
    t("getmodule", lambda: inspect.getmodule(textwrap.dedent).__name__)
    t("unwrap", lambda: inspect.unwrap(dataclasses.dataclass) is dataclasses.dataclass)
    t("currentframe", lambda: inspect.currentframe().f_code.co_name)
    t("stack", lambda: [f.function for f in inspect.stack()][:2])
    t("builtin sig", lambda: str(inspect.signature(len)))
    t("getfullargspec", lambda: inspect.getfullargspec(g))
    t("closurevars", lambda: inspect.getclosurevars(g))


def boom():
    return 1 / 0


def tracebacks():
    try:
        boom()
    except ZeroDivisionError as e:
        lines = traceback.format_exception(e)
        t("format_exception", lambda: [l.splitlines()[0].strip() for l in lines][-1])
        t("format_exc", lambda: traceback.format_exc().splitlines()[-1])
        t("extract_tb", lambda: [(f.name, f.lineno) for f in traceback.extract_tb(e.__traceback__)])
        te = traceback.TracebackException.from_exception(e)
        t("TracebackException", lambda: (te.exc_type_str if hasattr(te, 'exc_type_str') else te.exc_type.__name__, list(te.format_exception_only())))
        buf = io.StringIO()
        traceback.print_exc(file=buf)
        t("print_exc", lambda: buf.getvalue().splitlines()[-1])
    t("exception only", lambda: traceback.format_exception_only(ValueError('v')))



def builtin_signatures():
    import math, itertools, time, os
    for f in (len, sorted, print, max, getattr, math.sqrt, math.log, time.sleep, os.stat, str.join, list.append, dict.get, 'x'.split, [].sort, int.to_bytes, dict.fromkeys, object.__init__, itertools.chain.from_iterable):
        t(f.__qualname__, lambda: str(inspect.signature(f)))

class Slotted:
    __slots__ = ('a', '__b')


def pickles():
    import pickle
    o = Slotted()
    o.a = 1
    o._Slotted__b = 2
    t("pickle", lambda: (pickle.loads(pickle.dumps({"k": [1, 2.5, None, (3,)], "s": {"x"}})), pickle.loads(pickle.dumps(P(1, 2)))))
    t("pickle slots", lambda: (pickle.loads(pickle.dumps(o)).a, pickle.loads(pickle.dumps(o))._Slotted__b))


def main():
    pickles()
    builtin_signatures()
    jsons()
    datetimes()
    dataclass_part()
    inspects()
    tracebacks()


main()
