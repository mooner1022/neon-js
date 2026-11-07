# configparser, tomllib, getopt, optparse, filecmp, fileinput, tarfile, wave, uuid, mimetypes, timeit, platform, code and
# codeop (compile()'s incomplete input, mode 'single'), over a file system in memory; Linux CPython's output.
import codeop, configparser, getopt, optparse, filecmp, fileinput, uuid, mimetypes, timeit, tarfile, wave, platform, code, tomllib, io, os, sys


def t(label, f):
    try:
        print(label, "->", f())
    except BaseException as e:
        print(label, "!!", type(e).__name__, e)


def configs():
    cp = configparser.ConfigParser()
    cp.read_string("[DEFAULT]\nbase = /srv\n[app]\npath = %(base)s/app\nport = 8080\ndebug = yes\n[empty]\n")
    t("sections", lambda: (cp.sections(), cp['app']['path'], cp.getint('app', 'port'), cp.getboolean('app', 'debug'), dict(cp['app'])))
    out = io.StringIO()
    cp['new'] = {'k': 'v'}
    cp.write(out)
    t("write", lambda: out.getvalue())
    t("missing", lambda: cp['nope'])
    t("dup", lambda: configparser.ConfigParser().read_string("[a]\nx=1\n[a]\n"))
    t("toml", lambda: tomllib.loads('title = "x"\n[owner]\nname = "n"\ndob = 1979-05-27T07:32:00-08:00\n[db]\nports = [ 8000, 8001 ]\nenabled = true\npi = 3.14\n'))
    t("toml error", lambda: tomllib.loads('a = '))


def options():
    t("getopt", lambda: getopt.getopt(['-a', '-b', 'x', '--long=y', 'rest'], 'ab:', ['long=']))
    t("getopt error", lambda: getopt.getopt(['-z'], 'a'))
    t("gnu_getopt", lambda: getopt.gnu_getopt(['r1', '-a', 'r2'], 'a'))
    p = optparse.OptionParser(prog='p')
    p.add_option('-n', type='int', dest='n', default=1)
    p.add_option('-q', action='store_true', dest='quiet')
    t("optparse", lambda: (lambda o, a: (o.n, o.quiet, a))(*p.parse_args(['-n', '3', '-q', 'x'])))
    t("optparse help", lambda: p.format_help())


def files():
    os.mkdir('fa')
    os.mkdir('fb')
    for d in ('fa', 'fb'):
        with open(d + '/same.txt', 'w') as f:
            f.write('same')
    with open('fa/diff.txt', 'w') as f:
        f.write('one')
    with open('fb/diff.txt', 'w') as f:
        f.write('two!')
    with open('fa/only.txt', 'w') as f:
        f.write('o')
    t("cmp", lambda: (filecmp.cmp('fa/same.txt', 'fb/same.txt'), filecmp.cmp('fa/diff.txt', 'fb/diff.txt', shallow=False)))
    dc = filecmp.dircmp('fa', 'fb')
    t("dircmp", lambda: (dc.left_only, dc.right_only, dc.same_files, dc.diff_files, sorted(dc.common)))
    t("fileinput", lambda: [(fileinput.filename(), fileinput.lineno(), line) for line in fileinput.input(['fa/same.txt', 'fa/diff.txt'])])
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode='w') as tf:
        tf.add('fa/same.txt')
        data = b'hello tar'
        info = tarfile.TarInfo('mem.txt')
        info.size = len(data)
        info.mtime = 0
        tf.addfile(info, io.BytesIO(data))
    buf.seek(0)
    with tarfile.open(fileobj=buf) as tf:
        t("tar", lambda: (tf.getnames(), tf.extractfile('mem.txt').read(), tf.getmember('fa/same.txt').size))
    w = io.BytesIO()
    with wave.open(w, 'wb') as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(8000)
        wf.writeframes(b'\x00\x01' * 10)
    w.seek(0)
    with wave.open(w) as wf:
        t("wave", lambda: (wf.getparams(), wf.readframes(2)))


def misc():
    u = uuid.UUID('12345678123456781234567812345678')
    t("uuid", lambda: (u, u.hex, u.version, u.int, u.bytes[:4], uuid.UUID(int=5), uuid.uuid4().version, u.urn, uuid.NAMESPACE_DNS))
    t("uuid error", lambda: uuid.UUID('xyz'))
    t("mimetypes", lambda: (mimetypes.guess_type('a.html'), mimetypes.guess_type('x.tar.gz'), mimetypes.guess_extension('image/png'), mimetypes.guess_type('noext')))
    t("timeit", lambda: (timeit.timeit('1+1', number=10) >= 0, timeit.repeat('x=1', repeat=2, number=5).__len__(), timeit.Timer('pass').autorange()[0] > 0))
    t("platform", lambda: (platform.system(), platform.python_version(), platform.python_version_tuple(), platform.machine() != None))
    out = io.StringIO()
    old = sys.stdout
    ic = code.InteractiveInterpreter()
    sys.stdout = out
    try:
        ic.runsource('print(6 * 7)')
        more = ic.runsource('if True:')
    finally:
        sys.stdout = old
    t("code", lambda: (out.getvalue(), more, code.compile_command('1 +') is None))


def interactive():
    for s in ['1 +', 'if True:', 'if True:\n  x = 1', 'if True:\n  x = 1\n', 'def f(:', '(1,', '"""abc', "'abc",
              'x = )', 'for', '@dec', 'x = 1 +\\', 'try:\n  pass\n', 'if x:\n  pass\nelse', 'f(**', '#c', '', 'x = 1',
              'for i in range(2): i', 'class C:\n  def f(self):\n', 'if 1:\n pass\n  pass', 'match x:\n case 1:']:
        try:
            r = codeop.compile_command(s)
            r = 'code' if r is not None else None
        except SyntaxError as e:
            r = '%s: %s %s' % (type(e).__name__, e.msg, e.lineno)
        print(repr(s), r)
    for s in ['1 +', 'if True:\n', '(1,\n', '1 +\n', 'for i in range(2): i']:
        for mode in ('single', 'exec', 'eval'):
            try:
                compile(s, 's', mode, 0x4200)
                r = 'ok'
            except SyntaxError as e:
                r = '%s: %s %s %s' % (type(e).__name__, e.msg, e.lineno, e.offset)
            print(repr(s), mode, r)
    for s in ['1+1', 'x=1; x', '1\n2', 'if 1:\n  3\n  4\n', 'for i in range(2): i\n', 'None', '', '"doc"', 'print(1); 2']:
        try:
            exec(compile(s, 's', 'single'), {})
            print(repr(s), 'ran')
        except SyntaxError as e:
            print(repr(s), type(e).__name__, e.msg, e.lineno, e.offset)
    t("incomplete type", lambda: (_IncompleteInputError.__mro__[1].__name__, issubclass(_IncompleteInputError, SyntaxError)))
    old = sys.displayhook
    sys.displayhook = lambda v: print('hook', repr(v))
    exec(compile('7', 's', 'single'))
    del sys.displayhook
    t("lost hook", lambda: exec(compile('8', 's', 'single')))
    sys.displayhook = old
    exec(compile('9', 's', 'single'))
    import builtins
    t("underscore", lambda: builtins._)
    out = io.StringIO()
    console = code.InteractiveConsole({'__name__': '__console__'})
    lines = ['x = 6', 'x * 7', 'for i in range(2):', '    i', '', 'def f():', '    return x', '', 'f()', '1/0', 'undefined']
    saved = sys.stdout, sys.stderr
    sys.stdout = sys.stderr = out
    try:
        more = [console.push(l) for l in lines]
    finally:
        sys.stdout, sys.stderr = saved
    t("console", lambda: more)
    t("console out", lambda: [l for l in out.getvalue().splitlines() if not l.startswith('  File')])


def main():
    configs()
    options()
    files()
    misc()
    interactive()


main()
