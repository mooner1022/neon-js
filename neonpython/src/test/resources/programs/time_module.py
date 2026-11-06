# time (written in Kotlin as CPython writes it in C, glibc's strftime in the C locale), its zone UTC: what is the same
# from one run to another (the clocks by their relations), CPython's errors.
import time


def show(f, *args, **kw):
    try:
        return repr(f(*args, **kw))
    except Exception as e:
        return '%s: %s%s' % (type(e).__name__, e, ' (errno %s)' % e.errno if isinstance(e, OSError) else '')


class Index:
    def __index__(self): return 5
class Real:
    def __float__(self): return 5.5
class Tup(tuple): pass
class Flt(float): pass


def names():
    print([n for n in dir(time) if not n.startswith('__')])
    print(time.__name__, time.__doc__.splitlines()[0], time.tzname, time.timezone, time.altzone, time.daylight, time._STRUCT_TM_ITEMS)
    print(time.CLOCK_REALTIME, time.CLOCK_MONOTONIC, time.CLOCK_PROCESS_CPUTIME_ID, time.CLOCK_THREAD_CPUTIME_ID, time.CLOCK_MONOTONIC_RAW, time.CLOCK_BOOTTIME)
    print(time.time, time.struct_time, type(time.gmtime(0)).__module__)
    time.timezone = 5
    print(time.tzset(), time.timezone, time.tzname)


def clocks():
    t, ns = time.time(), time.time_ns()
    print(type(t).__name__, type(ns).__name__, abs(t - ns / 1e9) < 5, t > 1.7e9)
    for f, g in [(time.monotonic, time.monotonic_ns), (time.perf_counter, time.perf_counter_ns), (time.process_time, time.process_time_ns), (time.thread_time, time.thread_time_ns)]:
        a, b, c = f(), g(), f()
        print(f.__name__, type(a).__name__, type(b).__name__, a <= c, a >= 0, b >= 0, abs(b / 1e9 - a) < 60)
    for name in ['time', 'monotonic', 'perf_counter', 'process_time', 'thread_time']:
        i = time.get_clock_info(name)
        print(type(i).__name__, sorted(vars(i)), i.implementation, i.monotonic, i.adjustable, i.resolution)
    print(show(time.get_clock_info, 'x'), show(time.get_clock_info, 1), show(time.get_clock_info), show(time.get_clock_info, 'time\0'), show(time.get_clock_info, name='time'))
    print(show(time.time, 1), show(time.time, x=1), show(time.monotonic_ns, None), type(time.time(**{})).__name__)


def sleeping():
    m = time.monotonic()
    print(time.sleep(0), time.sleep(0.002), time.sleep(Flt(0.001)), time.monotonic() - m >= 0.003)
    for v in [-1, -1e-10, -0.0, float('nan'), float('inf'), 2 ** 63, 1e300, '1', None, Real(), 1j, 9223372036.854775, -2 ** 63]:
        print(show(time.sleep, v), end=' | ')
    print(show(time.sleep), show(time.sleep, 1, 2), show(time.sleep, secs=1))


def conversions():
    for x in [0, 1, -1, 1.999, -1.001, -0.5, 86399, 951782400, 1e9, 2 ** 31, -2 ** 31, 253402300800, -62135596801, -62167219201,
              1e15, -1e15, 67768036191676799, -67768040609740800, Index(), True, Flt(2.5)]:
        g = time.gmtime(x)
        print(tuple(g), g.tm_zone, g.tm_gmtoff, time.localtime(x).tm_zone, time.ctime(x), show(time.mktime, time.localtime(x)))
    for x in [67768036191676800, -67768040609740801, 2 ** 63 - 1, 2 ** 63, -2 ** 63, 9.3e18, 9.2e18, float('nan'), float('inf'), '1', Real()]:
        print(show(time.gmtime, x), show(time.localtime, x), show(time.ctime, x))
    print(show(time.gmtime, 1, 2), show(time.gmtime, x=1), show(time.ctime, 1, 2), show(time.localtime, 1, 2))
    print(len(time.ctime()), time.gmtime(None).tm_year > 2000, time.localtime().tm_zone, len(time.asctime()))


def mktimes():
    for t in [(2000, 1, 1, 0, 0, 0, 0, 1, 0), (2000, 1, 1, 0, 0, 0, 0, 1, -1), (2000, 1, 1, 0, 0, 0, 0, 1, 1), (1969, 12, 31, 23, 59, 59, 0, 1, 0),
              (2000, 13, 1, 0, 0, 0, 0, 1, 0), (2000, 0, 0, 0, 0, 0, 0, 0, 0), (2000, 1, 1, 0, 0, -100, 0, 1, 0), (2000, -30, 400, 99, -99, 999, 50, -3, 0),
              (2 ** 31 - 1, 1, 1, 0, 0, 0, 0, 1, 0), (-2 ** 31 + 1900, 1, 1, 0, 0, 0, 0, 1, 0), (-2147481748, 1, 1, 0, 0, -1, 0, 1, 0),
              (-2147481748, 1, 1, 0, 0, 0, 0, 1, 1), (2 ** 31, 1, 1, 0, 0, 0, 0, 1, 0), (-2 ** 31, 1, 1, 0, 0, 0, 0, 1, 0), (2000, 1, 1, 0, 0, 2 ** 31 - 1, 0, 1, 0),
              (2000, 2 ** 31 - 1, 1, 0, 0, 0, 0, 1, 0), (2000, -2 ** 31 + 1, 1, 0, 0, 0, 0, 1, 0), (1.5, 1, 1, 0, 0, 0, 0, 1, 0), (2000, 1, 1, 0, 0, 0, 0, 1, 2 ** 63),
              (2000, 1, 1), [2000, 1, 1, 0, 0, 0, 0, 1, 0], Tup((2000, 1, 1, 0, 0, 0, 0, 1, 0)), 1, time.gmtime(1e9)]:
        print(show(time.mktime, t), end=' | ')
    print(show(time.mktime), show(time.mktime, (2000, 1, 1, 0, 0, 0, 0, 1, 0), 1))


def structs():
    S = time.struct_time
    g = time.gmtime(1e9)
    print(S.n_fields, S.n_sequence_fields, S.n_unnamed_fields, S.__match_args__, S.__name__, S.__module__, S.__bases__, S.__doc__.splitlines()[0])
    print(S.tm_year.__doc__, S.tm_zone.__doc__, S.tm_gmtoff.__doc__)
    for args in [(), (1,), ((1,) * 8,), ((1,) * 9,), ((1,) * 10,), ((1,) * 11,), ((1,) * 12,), ([1] * 9,), (range(9),), ('abcdefghi',), ((1,) * 9, None),
                 ((1,) * 9, {'tm_zone': 'Z'}), ((1,) * 9, {'tm_zone': 'Z', 'x': 5}), ((1,) * 10, {'tm_zone': 'Z'}), ((1,) * 10, {'tm_gmtoff': 7}), ((1,) * 9, {}, 3)]:
        r = show(S, *args)
        s = None
        try:
            s = S(*args)
        except TypeError:
            pass
        print(r, (s.tm_zone, s.tm_gmtoff) if s is not None else '')
    print(show(S, sequence=(1,) * 9), show(S, seq=(1,) * 9), show(S, (1,) * 9, dict={'tm_gmtoff': 1}), show(S, (1,) * 9, dct={}))
    print(g, g.__reduce__(), S((1,) * 11).__reduce__(), len(S((1,) * 11)), S((1,) * 11) == (1,) * 9, S((1,) * 9) == S((1,) * 11))
    print(g[0], g[-1], g[:3], show(lambda: g[9]), g + (1,), type(g[:3]).__name__, g.tm_year, g.tm_yday, g.index(0), isinstance(g, tuple))
    print(g.__replace__(tm_year=5), g.__replace__(tm_zone='Q').tm_zone, show(g.__replace__, x=5), show(g.__replace__, 1), g.__replace__(tm_gmtoff=9).tm_gmtoff)
    print(show(S.__new__, S, (1,) * 9), show(S.__new__, tuple, (1,) * 9), show(S.__new__, 1, (1,) * 9))
    for n in ['tm_year', 'tm_zone']:
        try:
            setattr(g, n, 1)
        except AttributeError as e:
            print('set', n, e)
    try:
        class X(S): pass
    except TypeError as e:
        print(e)
    match g:
        case S(tm_year=y, tm_mon=m):
            print('match', y, m)


def asctimes():
    for t in [(2000, 1, 1, 0, 0, 0, 0, 1, 0), (2000, 0, 0, 0, 0, 0, 0, 0, 0), (2000, 1, 1, 0, 0, 61, 0, 1, 0), (2000, 1, 1, 0, 0, 0, -1, 1, 0),
              (2000, 1, 1, 0, 0, 0, -8, 1, 0), (2000, 1, 1, 0, 0, 0, 100, 1, 0), (99999, 1, 1, 0, 0, 0, 0, 1, 0), (-5, 1, 1, 0, 0, 0, 0, 1, 0),
              (2 ** 31 - 1, 1, 1, 0, 0, 0, 0, 1, 0), (-2 ** 31 + 1900, 1, 1, 0, 0, 0, 0, 1, 0), (2000, 13, 1, 0, 0, 0, 0, 1, 0), (2000, -1, 1, 0, 0, 0, 0, 1, 0),
              (2000, 1, 32, 0, 0, 0, 0, 1, 0), (2000, 1, -1, 0, 0, 0, 0, 1, 0), (2000, 1, 1, 24, 0, 0, 0, 1, 0), (2000, 1, 1, 0, 60, 0, 0, 1, 0),
              (2000, 1, 1, 0, 0, 62, 0, 1, 0), (2000, 1, 1, 0, 0, 0, -2, 1, 0), (2000, 1, 1, 0, 0, 0, 0, 367, 0), (2000, 1, 1, 0, 0, 0, 0, -1, 0),
              (2000, 1, 1, 0, 0, 0, 0, 1, '1'), (2000, 1, 1, 0, 0, 0, 0, 1, 2 ** 31), (2000, 1, 1, 0, 0, 0, 0, 1, -2 ** 31 - 1), (2000, Index(), 1, 0, 0, 0, 0, 1, 0),
              (2000, 1, 1, 0, 0, 0, 0, 1), (-2 ** 31, 1, 1, 0, 0, 0, 0, 1, 0), [2000, 1, 1, 0, 0, 0, 0, 1, 0], 'abcdefghi', None]:
        print(show(time.asctime, t), end=' | ')
    print(show(time.asctime, 1, 2), show(time.asctime, x=1))


T = (2000, 1, 2, 3, 4, 5, 6, 2, 0)


def strftime_conversions():
    convs = [chr(c) for c in range(ord('a'), ord('z') + 1)] + [chr(c) for c in range(ord('A'), ord('Z') + 1)] + ['%', '+', '']
    for tm in [(2000, 1, 1, 0, 0, 0, 5, 1, 0), (-5, 3, 7, 7, 30, 0, 2, 66, 0), (2021, 12, 31, 23, 59, 60, 4, 365, 1), (12345, 7, 4, 13, 0, 0, 3, 185, -1)]:
        print(' '.join(repr(time.strftime(f, tm)) for f in ['%' + c for c in convs]))
        print(' '.join(repr(time.strftime(f, tm)) for f in ['%E' + c for c in convs] + ['%O' + c for c in convs]))


def strftime_flags():
    for c in 'aAbBcCdDeFgGHIjklmMpPsSuUVwWxXyYzZq%':
        print(c, ' '.join(repr(time.strftime('%' + fl + w + c, (-5, 3, 7, 7, 30, 0, 2, 66, 0))) for fl in ['', '-', '_', '0', '^', '#', '_0', '0-'] for w in ['', '3', '8']))


def strftime_edges():
    for tm in [(1, 1, 1, 12, 5, 9, 0, 1, -1), (1899, 12, 31, 11, 0, 0, 6, 365, 0), (2147483647, 12, 31, 0, 0, 0, 0, 366, 0), (-2147481748, 1, 1, 0, 0, 0, 0, 1, 0),
               (0, 1, 1, 0, 0, 0, 5, 1, 0), (-100, 1, 1, 0, 0, 0, 4, 1, 0), (-101, 12, 31, 0, 0, 0, 4, 365, 0), (2000, 0, 0, 0, 0, 0, 0, 0, 5),
               time.gmtime(1e9), time.localtime(1e9), time.struct_time((2000, 1, 1, 0, 0, 0, 5, 1, 0, 'EST', -18000)),
               time.struct_time((2000, 1, 1, 0, 0, 0, 5, 1, 1, 'IST', 19800)), time.struct_time((2000, 1, 1, 0, 0, 0, 5, 1, 0, None, 360000)),
               time.struct_time((2000, 1, 1, 0, 0, 0, 5, 1, -1, None, None)), time.struct_time((2000, 1, 1, 0, 0, 0, 5, 1, 0, 'x', -2 ** 31)),
               time.struct_time((2000, 1, 1, 0, 0, 0, 5, 1, 0, 'a\0b', -59))]:
        print(repr(time.strftime('%Y|%C|%y|%G|%g|%V|%U|%W|%j|%s|%z|%Z|%c|%-6Z|%#Z|%5z|%_z|%-z', tm)))
    for y in range(2003, 2011):
        for md in [(1, 1), (1, 3), (1, 4), (12, 28), (12, 29), (12, 31)]:
            print(time.strftime('%F %a %j %U %W %V %G %g %u %w', time.gmtime(time.mktime((y, md[0], md[1], 12, 0, 0, 0, 0, 0)))), end=' | ')
        print()


def strftime_buffer():
    for f in ['', '%', '%%', 'abc', '%Y\0%m', 'a\0b%Yc', '%\0Y', '%-\0', 'é%Y', '%Yé%m', '%é', 'x€%5\U0001F600%Y', '%Eé',
              '%2047Y', '%2048Y', '%1000000000Y', '%99999999999999Y', '%-1000000Y', '%1030c', '%^1030c', '%1030z', '%1030s',
              'x' * 5000, '%Y' * 600, '%3000Y\0%1500Y', '%1500Y\0%3000Y', '%z' * 6 + '\0%3000Y']:
        r = time.strftime(f, (2000, 1, 2, 3, 4, 5, 6, 2, -1 if 'z' in f else 0))
        print(repr(f[:12]), len(r), repr(r[:16]), repr(r[-16:]))
    for args in [(), (1,), (b'%Y',), ('%Y', 1), ('%Y', None), ('%Y', (1,) * 8), ('%Y', T, 1), ('%Y', (2000, 13, 1, 0, 0, 0, 0, 1, 0)),
                 ('%Y', (2 ** 31, 1, 1, 0, 0, 0, 0, 1, 0)), ('%Z', time.struct_time((2000, 1, 1, 0, 0, 0, 0, 1, 0, 5, 0))),
                 ('%z', time.struct_time((2000, 1, 1, 0, 0, 0, 0, 1, 0, 'x', 'y'))), ('%z', time.struct_time((2000, 1, 1, 0, 0, 0, 0, 1, 0, 'x', 2 ** 63))),
                 ('%Z', time.struct_time((2000, 1, 1, 0, 0, 0, 0, 1, 0, '\ud800', 0))), ('%Y', Tup(T)), ('%Z|%6Z', time.struct_time((2000, 1, 1, 0, 0, 0, 0, 1, 0, 'é€', 0)))]:
        print(show(time.strftime, *args), end=' | ')
    print(show(time.strftime, format='%Y'), len(time.strftime('%Y-%m-%d %H:%M:%S %Z')), time.strftime('%Z %z'))


def clock_functions():
    for i in [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 11, -2, -6]:
        print(i, type(time.clock_gettime(i)).__name__, type(time.clock_gettime_ns(i)).__name__, time.clock_getres(i), end=' | ')
    print()
    for i in [10, 12, 99, -1, 2 ** 31 - 1, 'x', 1.5, 2 ** 31, None]:
        print(show(time.clock_gettime, i), show(time.clock_gettime_ns, i), show(time.clock_getres, i), end=' | ')
    print()
    print(show(time.clock_gettime), show(time.clock_gettime, clk_id=1), show(time.clock_getres), show(time.clock_getres, x=1))
    for i, v in [(0, 0), (0, 1.5), (0, -1.5), (0, 9.2e9), (1, 0), (2, 0), (3, 0), (11, 0), (-2, 0), (-3, 0), (-1, 0), (0, 2 ** 40), (0, float('nan')),
                 (0, 1e19), ('x', 0), (0, 'x'), (2 ** 31, 0), (0, None)]:
        print(show(time.clock_settime, i, v), end=' | ')
    print()
    for i, v in [(0, 0), (0, -1), (1, 0), (0, True), (0, 1.5), (0, 'x'), (0, 2 ** 63)]:
        print(show(time.clock_settime_ns, i, v), end=' | ')
    print(show(time.clock_settime, 0))
    import _thread
    c = time.pthread_getcpuclockid(_thread.get_ident())
    print(c < 0, type(time.clock_gettime(c)).__name__, show(time.pthread_getcpuclockid), show(time.pthread_getcpuclockid, 'x'), show(time.pthread_getcpuclockid, 1.5))


def main():
    for part in [names, clocks, sleeping, conversions, mktimes, structs, asctimes, strftime_conversions, strftime_flags, strftime_edges, strftime_buffer, clock_functions]:
        part()


main()
