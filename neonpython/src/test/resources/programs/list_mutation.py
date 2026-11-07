# A list changed under its own operation, as CPython takes it (no JVM exception): a slice assignment whose indices
# or value change it, remove/index/count/in with an __eq__ that does, list.sort (the list empty meanwhile, "list
# modified during sort", a key's error putting the items back); generators never pickled nor copied; bound builtin
# methods equal and hashed by their object and function.
import copy, pickle
def t(label, f):
    try:
        print(label, '->', f())
    except BaseException as e:
        print(label, '!!', type(e).__name__, e)
class Ix:
    def __init__(self, l, v): self.l, self.v = l, v
    def __index__(self):
        self.l.clear()
        return self.v
def slice_index():
    l = list(range(10))
    l[Ix(l, 2):Ix(l, 8)] = [1, 2]
    return l
t('slice index mutates', slice_index)
def slice_iter():
    l = list(range(10))
    def gen():
        l.clear()
        yield 1
    l[2:8] = gen()
    return l
t('slice iter mutates', slice_iter)
def slice_ext():
    l = list(range(10))
    def gen():
        del l[5:]
        yield from (1, 2, 3)
    l[0:6:2] = gen()
    return l
t('extended slice mutates', slice_ext)
class Eq:
    def __init__(self, l): self.l = l
    def __eq__(self, o):
        self.l.clear()
        return False
def remove():
    l = [1, 2, 3]
    l.append(Eq(l))
    l.remove(99)
t('remove mutates', remove)
def index():
    l = [Eq(None), 1]
    l[0].l = l
    return l.index(1)
t('index mutates', index)
def count():
    l = [1, 2]
    l.insert(0, Eq(l))
    return l.count(5)
t('count mutates', count)
def contains():
    l = [1, 2]
    l.insert(0, Eq(l))
    return 5 in l
t('contains mutates', contains)
def sort_mutate():
    l = [3, 1, 2]
    def key(x):
        l.append(0)
        return x
    l.sort(key=key)
t('sort mutates', sort_mutate)
def gen():
    yield 1
t('copy gen', lambda: copy.copy(gen()))
t('deepcopy gen', lambda: copy.deepcopy(gen()))
t('pickle gen', lambda: pickle.dumps(gen()))
l = [1]
t('bound eq', lambda: (l.append == l.append, l.append == [].append, l.append is l.append, hash(l.append) == hash(l.append), 'x'.upper == 'x'.upper, len == len))
t('bound repr', lambda: repr(l.append)[:30])
class C:
    def __init__(s, v): s.v = v
    def __lt__(s, o):
        l2.append(0)
        return s.v < o.v
    def __repr__(s): return 'C%d' % s.v
l2 = [C(3), C(1), C(2)]
t('sort compare mutates', lambda: (l2.sort(), l2))
t('after', lambda: l2)
l4 = [3, 1, 2]
seen = []
def k2(x):
    seen.append(len(l4)); return x
t('sort sees empty', lambda: (l4.sort(key=k2), l4, seen))
l5 = [3, 1, 2]
def bad(x):
    if x == 1: raise KeyError('k')
    return x
t('sort key raises', lambda: l5.sort(key=bad))
t('after raise', lambda: l5)
t('sorted', lambda: sorted([3, 1, 2], reverse=True))
