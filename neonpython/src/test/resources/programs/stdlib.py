# The bundled library (CPython's own Lib modules, docs/PYTHON_PLATFORM.md §12): imported from it, run as CPython runs them.
import __future__
import bisect
import copyreg
import heapq
import keyword
import operator


def keywords():
    print(keyword.iskeyword('for'), keyword.iskeyword('match'), keyword.issoftkeyword('match'), keyword.issoftkeyword('type'))
    print(len(keyword.kwlist), keyword.softkwlist)


def operators():
    print(operator.add(2, 3), operator.itemgetter(1, 0)('ab'), operator.attrgetter('real')(3), operator.methodcaller('upper')('x'))
    print(operator.index(7), operator.length_hint([1, 2]), operator.concat([1], [2]), operator.contains('abc', 'b'), operator.is_none(None))
    print(repr(operator.itemgetter(1)), repr(operator.attrgetter('a.b')), repr(operator.methodcaller('f', 1, k=2)))
    x = [1, 2]
    print(operator.iadd(x, [3]), x, operator.getitem({'k': 9}, 'k'), operator.truth(0), operator.not_(0))


def heaps():
    h = []
    for x in [5, 1, 4, 2, 3]:
        heapq.heappush(h, x)
    print([heapq.heappop(h) for _ in range(5)], heapq.nsmallest(2, [4, 1, 3]), heapq.nlargest(2, [4, 1, 3]))
    print(list(heapq.merge([1, 3, 5], [2, 4])), list(heapq.merge(['b', 'a'], ['c'], key=len)), heapq.heappushpop([1, 2], 0))
    g = [3, 1, 2]
    heapq.heapify(g)
    print(g, heapq.heapreplace(g, 9), g, heapq.nsmallest(2, ['bb', 'a', 'ccc'], key=len))


def bisects():
    a = [1, 2, 4, 4, 8]
    print(bisect.bisect_left(a, 4), bisect.bisect_right(a, 4), bisect.bisect(a, 5), bisect.bisect_left(a, 4, 3))
    bisect.insort(a, 3)
    bisect.insort_left(a, 4)
    print(a, bisect.bisect([(1, 'a'), (3, 'b')], 2, key=lambda p: p[0]))


def futures():
    f = __future__.annotations
    print(type(f).__name__, f.getMandatoryRelease(), f.getOptionalRelease(), __future__.all_feature_names[:3])
    print(heapq.__name__, heapq.__file__.endswith('heapq.py'), type(copyreg.dispatch_table).__name__, copyreg.__newobj__.__name__)


keywords()
operators()
heaps()
bisects()
futures()
