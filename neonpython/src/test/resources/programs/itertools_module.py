# itertools (written in Kotlin as CPython writes it in C): its iterators, their reprs, CPython's errors.
import itertools as it


def main():
    print(repr(it.count()), repr(it.count(5)), repr(it.count(1, 2)), repr(it.count(1.5, 0.5)), repr(it.count(step=-1)), repr(it.repeat('a')), repr(it.repeat('a', 3)), repr(it.repeat(1, -2)))
    c = it.count(3); next(c); print(repr(c))
    r = it.repeat(1, 2); next(r); print(repr(r), r.__length_hint__())
    for f in [lambda: it.count('a'), lambda: it.islice([], -1), lambda: it.islice([], 1, -1), lambda: it.islice([], 0, 1, 0), lambda: it.islice([], 'a'), lambda: it.islice([]), lambda: it.islice([], -5, 2),
              lambda: it.batched([], 0), lambda: list(it.batched([1, 2, 3], 2, strict=True)), lambda: it.permutations([], -1), lambda: it.combinations([], -1), lambda: it.product([], repeat=-1), lambda: it.tee([], -1),
              lambda: list(it.chain(1)), lambda: it.repeat(), lambda: it.islice([], 1, 2, 3, 4), lambda: it.cycle(), lambda: it.accumulate(), lambda: list(it.zip_longest(1)), lambda: it.compress([1]), lambda: it.starmap(len),
              lambda: it.pairwise(), lambda: it.groupby(), lambda: it.count(1, 2, 3)]:
        try:
            print(f())
        except Exception as e:
            print(type(e).__name__, e)
    print([type(x).__name__ for x in [it.count(), it.chain(), it.tee([])[0], it.groupby([1]), next(it.groupby([1]))[1], it.islice([], 1), it.zip_longest(), it.batched([], 1)]])
    print(it.chain.__module__, type(it.tee([])[0]).__module__, type(it.tee([])[0]).__qualname__)
    print(list(it.accumulate([1, 2, 3], initial=10)), list(it.accumulate([1, 2, 3], lambda a, b: a * b)), list(it.batched('abcdefg', 3)), list(it.pairwise('abcd')))
    print(list(it.product('ab', repeat=2)), list(it.permutations(range(3), 2)), list(it.combinations_with_replacement('abc', 2)), list(it.compress('abc', [1, 0, 1])))
    print([(k, list(g)) for k, g in it.groupby('aabbbc')], list(it.islice(range(10), 2, 8, 3)), list(it.zip_longest('ab', 'c', fillvalue='-')))
    a, b = it.tee(iter([1, 2, 3])); print(list(a), list(b), list(it.takewhile(lambda x: x < 3, [1, 2, 3, 1])), list(it.dropwhile(lambda x: x < 3, [1, 2, 3, 1])), list(it.filterfalse(None, [0, 1, 0, 2])))
    g = it.groupby([1, 1, 2]); k, g1 = next(g); next(g); print(list(g1))
    t = it.tee([1])[0]; print(type(t.__copy__()).__name__, it.tee(t, 3)[0] is t)
    print(it.count(2 ** 70), it.count(1, 1.0), list(it.combinations(range(4), 3)), list(it.product()), list(it.permutations('ab', 3)), list(it.combinations('ab', 0)))
    print(list(it.islice(it.cycle('ab'), 5)), list(it.chain.from_iterable(['ab', 'cd'])), list(it.starmap(pow, [(2, 3), (3, 2)])), list(it.islice('abcdef', None, None, 2)))
    src = iter(range(10)); print(list(it.islice(src, 3)), next(src))
    x, y = it.tee(range(5)); next(x); next(x); print(list(y), list(x))
    print(list(it.combinations_with_replacement([], 0)), list(it.combinations_with_replacement([], 1)), list(it.product('ab', [])), it.chain[int])


main()
