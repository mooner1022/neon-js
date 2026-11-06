# _collections (written in Kotlin as CPython writes it in C): deque, defaultdict, dict.__getitem__ calling __missing__.
from _collections import deque, defaultdict


def dq():
    d = deque([1, 2, 3], maxlen=3)
    print(repr(d), repr(deque()), repr(deque([], 2)))
    for f in [lambda: d.index(9), lambda: d.remove(9), lambda: deque().pop(), lambda: deque().popleft(), lambda: d[5], lambda: d['a'], lambda: d.insert(0, 1), lambda: deque(maxlen=-1), lambda: deque(maxlen='a'),
              lambda: d + [1], lambda: setattr(d, 'maxlen', 3), lambda: deque([1]) < [1], lambda: hash(d), lambda: d[1:2]]:
        try:
            print(f())
        except Exception as e:
            print(type(e).__name__, e)
    x = deque([1, 2]); x.append(x); print(x)

    class D(deque):
        pass

    print(repr(D([1])), type(iter(d)).__name__, type(reversed(d)).__name__, d.maxlen, deque().maxlen, type(D([1]).copy()).__name__)
    e = deque(range(5)); e.rotate(2); print(e, e * 2, 3 in e, e.count(3), e.index(3), e.copy())
    it = iter(e); next(it); e.append(9)
    try:
        next(it)
    except RuntimeError as ex:
        print(ex)
    f = deque('abc'); f.appendleft('z'); f.extend('de'); f.extendleft('xy'); print(f, f.pop(), f.popleft(), len(f), list(reversed(f)), f[0], f[-1])
    f.rotate(-2); print(f); f.reverse(); print(f); f.remove('b'); del f[0]; f[0] = 'Q'; print(f, bool(f), bool(deque()))
    g = deque(maxlen=2); g.extend(range(5)); print(g); g.appendleft(9); print(g)
    print(deque([1, 2]) == deque([1, 2]), deque([1, 2]) < deque([1, 3]), deque([1]) == [1], deque([1, 2]) >= deque([1]))
    h = deque([1]); h += [2, 3]; h *= 2; print(h, deque[int], deque.__module__, deque.__qualname__)
    print(sorted(deque([3, 1, 2])), sum(deque(range(4))), max(deque('abc')), list(deque('ab') + deque('cd')))
    k = deque(); k.extend(k); k.append(1); k.extend(k); print(k)


def dd():
    d = defaultdict(list); d['a'].append(1); print(d, repr(defaultdict()), d.default_factory, d.copy())
    try:
        defaultdict(1)
    except TypeError as ex:
        print(ex)
    try:
        defaultdict()['x']
    except KeyError as ex:
        print(repr(ex))

    class DD(defaultdict):
        pass

    print(DD(int, a=1), d | {'b': 2}, type(d | {'b': 2}).__name__, {'b': 2} | d, type({'b': 2} | d).__name__)
    print(defaultdict.__module__, isinstance(d, dict), 'a' in d, 'zz' in d, d.get('zz'), len(d))
    c = defaultdict(int)
    for w in 'abracadabra':
        c[w] += 1
    print(sorted(c.items()), c.default_factory)
    c.default_factory = None
    try:
        c['q']
    except KeyError as ex:
        print(repr(ex))

    class M(dict):
        def __missing__(self, k):
            return k * 2

    class N(M):
        def __getitem__(self, k):
            return super().__getitem__(k) + '!'

    print(M()['ab'], N()['xy'])
    r = defaultdict(list); r['self'] = r; print(r)


dq()
dd()
