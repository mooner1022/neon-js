import sys


def t(label, f):
    try:
        print(label, '->', f())
    except BaseException as e:
        print(label, '!!', type(e).__name__, e)


def monitoring():
    m = sys.monitoring
    E = m.events
    t('ids', lambda: (m.DEBUGGER_ID, m.COVERAGE_ID, m.PROFILER_ID, m.OPTIMIZER_ID))
    t('events', lambda: (E.PY_START, E.LINE, E.CALL, E.RAISE, E.BRANCH, E.NO_EVENTS, E.C_RAISE, E.BRANCH_LEFT))
    t('unused', lambda: m.get_tool(3))
    t('use', lambda: (m.use_tool_id(3, 'mine'), m.get_tool(3)))
    t('again', lambda: m.use_tool_id(3, 'other'))
    t('bad id', lambda: m.use_tool_id(6, 'x'))
    t('neg id', lambda: m.get_tool(-1))
    t('name', lambda: m.use_tool_id(4, 5))
    t('events0', lambda: m.get_events(3))
    t('set', lambda: (m.set_events(3, E.PY_START | E.LINE), m.get_events(3)))
    t('set bad', lambda: m.set_events(3, 1 << 30))
    t('set unused', lambda: m.set_events(4, E.LINE))

    def cb(*a):
        pass

    t('register', lambda: m.register_callback(3, E.LINE, cb))
    t('register2', lambda: m.register_callback(3, E.LINE, None) is cb)

    def f():
        return 1

    t('local', lambda: (m.set_local_events(3, f.__code__, E.LINE), m.get_local_events(3, f.__code__)))
    t('local other', lambda: m.get_local_events(3, t.__code__))
    t('restart', lambda: m.restart_events())
    t('clear', lambda: (m.clear_tool_id(3), m.get_tool(3), m.get_events(3), m.get_local_events(3, f.__code__)))
    t('free', lambda: (m.free_tool_id(3), m.get_tool(3)))
    t('sentinels', lambda: (repr(m.MISSING) != repr(m.DISABLE), m.MISSING is m.MISSING))


def trace():
    t('none', lambda: (sys.gettrace(), sys.getprofile()))

    def tr(frame, event, arg):
        return tr

    sys.settrace(tr)
    t('trace', lambda: sys.gettrace() is tr)
    sys.settrace(None)
    t('cleared', lambda: sys.gettrace())
    sys.setprofile(tr)
    t('profile', lambda: sys.getprofile() is tr)
    sys.setprofile(None)
    t('profile cleared', lambda: sys.getprofile())
    t('settrace args', lambda: sys.settrace())


def misc():
    t('jit', lambda: (sys._jit.is_available(), sys._jit.is_enabled(), sys._jit.is_active()))
    t('getformat', lambda: (float.__getformat__('double'), float.__getformat__('float')))
    t('getformat bad', lambda: float.__getformat__('long'))
    n = 20000
    src = 'lambda ' + ', '.join('a%d' % i for i in range(n)) + ': a%d' % (n - 1)
    t('many params', lambda: eval(src)(*range(n)))

    def g(a, b):
        del a
        try:
            return a
        except UnboundLocalError as e:
            return str(e)

    t('del param', lambda: g(1, 2))


def libs():
    import doctest, bdb, pdb

    def sq(x):
        """
        >>> sq(3)
        9
        >>> sq(-2)
        5
        """
        return x * x

    finder = doctest.DocTestFinder()
    tests = finder.find(sq, 'sq', globs={'sq': sq})
    t('found', lambda: [(x.name, len(x.examples)) for x in tests])
    out = []
    runner = doctest.DocTestRunner(verbose=False)
    for x in tests:
        runner.run(x, out=out.append)
    t('results', lambda: (runner.failures, runner.tries))
    t('report', lambda: [line for line in ''.join(out).splitlines() if line.startswith(('Expected', 'Got', '    5', '    4'))])
    p = doctest.DocTestParser()
    t('parse', lambda: [(e.source, e.want) for e in p.get_examples('>>> 1 + 1\n2\n>>> print("a")\na\n')])
    t('bdb', lambda: (bdb.Bdb().get_stack.__name__, bdb.BdbQuit.__mro__[1].__name__))
    t('pdb', lambda: (pdb.Pdb.__mro__[1].__name__, callable(pdb.set_trace)))


monitoring()
trace()
misc()
libs()
