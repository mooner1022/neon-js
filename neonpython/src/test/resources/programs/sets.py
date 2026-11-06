# sets lay out and print their elements as CPython's table does
print({3, 1, 2}, set(range(20, 0, -1)), {-1, -2}, {10, 5, 100}, {8, 16, 0, 24}, {1.5, 2.5, 0.5}, {(1, 2), (2, 1), (0, 0)})
print({True, 2, 0}, {None, 1}, set(), frozenset(), frozenset({3, 1}), {frozenset({1}), frozenset({2})}, set([5, 13, 21, 29]))
s = set(range(10))
s.discard(3)
s.add(33)
s.add(3)
print(s.pop(), s.pop(), s)
print(hash(None), hash(1.5), hash(-2.5), hash(1e100), hash((1, 2)), hash(()), hash((1, (2, 3))), hash(frozenset({1, 2})), hash(frozenset()))
print(hash(10 ** 20), hash(-1), hash(2 ** 61 - 1), hash(0.1), hash(float('inf')), hash((None, True)), hash(frozenset({(1, 2), 3})))

# operators, as CPython makes their results
print({1, 2} | {2, 3}, {1, 2} & {2, 3}, {1, 2} - {2, 3}, {1, 2} ^ {2, 3}, {1, 2} <= {1, 2, 3}, {1} < {1}, frozenset({1}) == {1})
print({9, 1} | {5}, {100, 1, 50} & {50, 100, 7}, set(range(40)) - {1, 2, 3}, {1, 2, 3} > {1}, {1} >= {1, 2})
print(type(frozenset({1}) | {2}).__name__, type({1} | frozenset({2})).__name__, {1, 2} == {2, 1}, {1} != {1, 2})
a = {1, 2, 3}
alias = a
a |= {4}
a -= {1}
a &= {2, 3, 4, 5}
a ^= {3, 9}
print(a, a is alias)

# methods
s = {5, 1}
s.add(3)
s.update([7, 1], (8,))
s.discard(100)
s.remove(5)
print(s, len(s), 7 in s, 6 not in s, sorted(s) if False else list(s))
print(s.union([10], {11}), s.intersection([1, 3, 99]), s.difference([1]), s.symmetric_difference([1, 99]))
print(s.issubset(range(20)), s.issuperset([1, 3]), s.isdisjoint([100]), s.copy() == s, s.copy() is s)
t = s.copy()
t.intersection_update({1, 3, 7})
t.difference_update([7])
t.symmetric_difference_update([3, 50])
print(t)
t.clear()
print(t, bool(t), bool({0}))
fs = frozenset([3, 2, 1])
print(fs, fs.copy() is fs, frozenset(fs) is fs, {fs: 'key'}[frozenset({1, 2, 3})], frozenset() in {frozenset()})

# displays, comprehensions, iteration
xs = [1, 2]
print({*xs, 3, *range(2)}, {x % 3 for x in range(10)}, {c for c in 'aaa'}, [v * 2 for v in {4, 2}])
for v in {30, 10, 20}:
    print('element', v)
print(set('a') == {'a'}, set({'k': 1}), set(frozenset({2})), {1, 2}.pop(), {(1, 2)}.pop())

# a set of one's own
class Tags(set):
    def tag(self, t):
        self.add(t)
        return self

tg = Tags([3])
tg.tag(1).tag(2)
print(tg, type(tg).__name__, isinstance(tg, set), tg | {9}, type(tg | {9}).__name__)
