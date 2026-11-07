# pathlib (over the context's file system, one in memory here), zipfile in memory, logging, sched, and what they
# stand on: operator's builtins (unbound in a class), __class__ assignment, slots' solid bases; Linux CPython's output.
import pathlib, zipfile, io, logging, sched, time, operator
from pathlib import Path, PurePosixPath, PureWindowsPath


def t(label, f):
    try:
        print(label, "->", f())
    except Exception as e:
        print(label, "!!", type(e).__name__, e)


def paths():
    p = PurePosixPath('/a/b/c.tar.gz')
    t("pure", lambda: (p.name, p.suffix, p.suffixes, p.stem, p.parent, p.parts, p.anchor, str(p.with_suffix('.zip')), p.relative_to('/a'), p.is_absolute(), p / 'x'))
    t("windows", lambda: (PureWindowsPath('C:/x/y.txt').drive, str(PureWindowsPath('C:/x') / 'y'), PureWindowsPath('a\\b').parts))
    t("match", lambda: (p.match('*.gz'), PurePosixPath('a/b').full_match('a/*'), PurePosixPath('x/y').with_name('z')))
    t("types", lambda: (type(Path('x')).__name__, Path('x') == PurePosixPath('x'), sorted(c.__name__ for c in type(Path('x')).__mro__)))
    base = Path('pl')
    base.mkdir()
    (base / 'a.txt').write_text('héllo\n', encoding='utf-8')
    (base / 'sub').mkdir()
    (base / 'sub' / 'b.py').write_bytes(b'x = 1\n')
    t("io", lambda: ((base / 'a.txt').read_text(encoding='utf-8'), (base / 'sub' / 'b.py').read_bytes(), (base / 'a.txt').stat().st_size))
    t("tests", lambda: (base.exists(), base.is_dir(), (base / 'a.txt').is_file(), (base / 'nope').exists()))
    t("iterdir", lambda: sorted(x.name for x in base.iterdir()))
    t("glob", lambda: (sorted(str(x) for x in base.glob('*')), sorted(str(x) for x in base.rglob('*.py'))))
    t("walk", lambda: sorted((str(d), sorted(ds), sorted(fs)) for d, ds, fs in base.walk()))
    t("resolve", lambda: (Path('.').resolve() == Path.cwd(), Path('pl/../pl/a.txt').resolve().name))
    t("mkdir exists", lambda: base.mkdir())
    t("mkdir parents", lambda: (Path('pl/x/y').mkdir(parents=True), Path('pl/x/y').is_dir(), Path('pl/x/y').rmdir(), Path('pl/x').rmdir()))
    t("rename", lambda: ((base / 'a.txt').rename(base / 'c.txt').name, sorted(x.name for x in base.iterdir())))
    t("missing", lambda: (base / 'zz').read_text())
    t("unlink", lambda: ((base / 'c.txt').unlink(), (base / 'sub' / 'b.py').unlink(), (base / 'sub').rmdir(), base.rmdir(), base.exists()))


def zips():
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, 'w', compression=zipfile.ZIP_DEFLATED) as z:
        z.writestr('a.txt', 'hello' * 10)
        z.writestr('d/b.bin', b'\x00\x01' * 5, compress_type=zipfile.ZIP_STORED)
    data = buf.getvalue()
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        t("namelist", lambda: z.namelist())
        t("read", lambda: (z.read('a.txt')[:10], z.read('d/b.bin')))
        t("infos", lambda: [(i.filename, i.file_size, i.compress_type) for i in z.infolist()])
        t("testzip", lambda: z.testzip())
        t("path", lambda: sorted(str(p.name) for p in zipfile.Path(z).iterdir()))
        t("path read", lambda: (zipfile.Path(z, 'd/b.bin').read_bytes(), (zipfile.Path(z) / 'a.txt').read_text()[:5], type(z).__name__))
    t("is_zipfile", lambda: (zipfile.is_zipfile(io.BytesIO(data)), zipfile.is_zipfile(io.BytesIO(b'nope'))))
    t("bad", lambda: zipfile.ZipFile(io.BytesIO(b'not a zip')))
    with zipfile.ZipFile('z.zip', 'w') as z:
        z.writestr('f.txt', 'file')
    with zipfile.ZipFile('z.zip') as z:
        t("on disk", lambda: (z.namelist(), z.read('f.txt')))


def logs():
    stream = io.StringIO()
    log = logging.getLogger('probe')
    h = logging.StreamHandler(stream)
    h.setFormatter(logging.Formatter('%(levelname)s:%(name)s:%(message)s'))
    log.addHandler(h)
    log.setLevel(logging.INFO)
    log.debug('hidden')
    log.info('shown %d', 1)
    log.warning('warn')
    try:
        1 / 0
    except ZeroDivisionError:
        log.exception('boom')
    child = logging.getLogger('probe.child')
    child.error('from child')
    lines = stream.getvalue().splitlines()
    t("log", lambda: lines[:4] + lines[-2:])
    t("levels", lambda: (logging.getLevelName(10), logging.getLevelName('ERROR'), log.getEffectiveLevel(), child.parent.name))
    s = sched.scheduler(time.monotonic, time.sleep)
    out = []
    s.enter(0.02, 1, out.append, ('b',))
    s.enter(0.01, 1, out.append, ('a',))
    s.enter(0.01, 0, out.append, ('first',))
    s.run()
    t("sched", lambda: out)


def underneath():
    class A:
        f = operator.add
        g = len
    t("operator unbound", lambda: (A().f(1, 2), A().g('abc'), operator.add, operator.__add__ is operator.add))
    t("operator", lambda: (operator.countOf([1, 1.0, True], 1), operator.indexOf('abc', 'c'), operator.call(len, 'ab'), operator.is_none(None), operator.inv(5), operator.abs(-2)))
    t("operator errors", lambda: operator.add(1))
    t("operator errors 2", lambda: operator.neg())
    t("operator errors 3", lambda: operator.indexOf([1], 2))
    t("inplace", lambda: (operator.iadd([1], [2]), operator.imul('ab', 2), operator.matmul))

    class B:
        def m(self): return 'B'

    class C(B):
        def m(self): return 'C'

    class S:
        __slots__ = ('a',)

    class L(list): pass

    class L2(list):
        def first(self): return self[0]

    def setc(o, c):
        o.__class__ = c
        return type(o).__name__, o.__class__.__name__

    b = B()
    t("class set", lambda: (setc(b, C), b.m(), isinstance(b, C)))
    t("class layout", lambda: setc(b, S))
    t("class builtin", lambda: setc(b, int))
    t("class not a class", lambda: setc(b, 3))
    t("class of int", lambda: setc(1, B))
    lst = L([5])
    t("class list", lambda: (setc(lst, L2), lst.first(), lst))

    class P:
        __slots__ = ('x',)

    class Q(P): pass

    class R(P): pass

    class QR(Q, R): pass

    class U:
        __slots__ = ('y',)

    def conflict():
        class V(P, U): pass
    t("solid bases", lambda: (QR.__mro__[1].__name__, QR().__class__.__name__))
    t("layout conflict", conflict)


def main():
    paths()
    zips()
    logs()
    underneath()


main()
