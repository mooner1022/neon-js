# sorted, reversed, enumerate, zip, map, filter, any, all
words = ['pear', 'Apple', 'fig', 'banana']
print(sorted(words), sorted(words, key=len), sorted(words, key=str.lower, reverse=True), sorted([3, 1, 2], reverse=True), sorted('cab'))
print(sorted([(2, 'b'), (1, 'z'), (2, 'a')]), sorted({3: 'c', 1: 'a'}), sorted([1.5, -2, 0]), sorted([], key=len))
print(list(reversed([1, 2, 3])), list(reversed('abc')), list(reversed(range(3))), list(reversed((1, 2))))
print(list(enumerate('ab')), list(enumerate('ab', 10)), list(zip('ab', [1, 2, 3])), list(zip()), list(zip([1], [2], [3])))
print(list(map(str, [1, 2])), list(map(lambda a, b: a * b, [1, 2], [3, 4, 5])), list(filter(None, [0, 1, '', 'x'])), list(filter(lambda v: v > 1, [1, 2, 3])))
print(any([0, '', None]), any([0, 1]), all([1, 'x']), all([1, 0]), any([]), all([]), any(x > 2 for x in range(5)))
for i, (a, b) in enumerate(zip('xy', 'zw')):
    print(i, a, b)
m = map(abs, [-1, -2])
print(type(m).__name__, next(m), list(m), type(zip()).__name__, type(enumerate([])).__name__, type(reversed([])).__name__, type(filter(None, [])).__name__)
print(type(iter([])).__name__, type(iter(())).__name__, type(iter('a')).__name__, type(iter({})).__name__, type(iter(range(1))).__name__, type(iter({1})).__name__)
it = iter([1, 2])
print(it.__next__(), next(it), it.__iter__() is it)

# min and max: key, default, several arguments
print(min(3, 1, 2), max('a', 'b'), min([4, 2, 8]), max([1, 5], key=lambda v: -v), min([], default='none'), max('xyz', key=ord))
print(min([(1, 'b'), (1, 'a')]), max([1, 2, 2], key=lambda v: v % 2), min(['bb', 'a', 'cc'], key=len))

def numbers():
    # numbers: round, divmod, pow, abs, conversions
    print(round(2.675, 2), round(0.5), round(1.5), round(2.5), round(-0.5), round(1234.5678, -2), round(7), round(7, -1), round(15, -1), round(2.5, 0))
    print(type(round(2.5)).__name__, type(round(2.5, 0)).__name__, round(1e300, 2), round(-1.0), round(5.0e-1, 1))
    print(divmod(7, 2), divmod(-7, 2), divmod(7.5, 2), divmod(-7.5, 2), pow(2, 10), pow(2, -1), pow(2, 10, 1000), pow(3, -1, 7), pow(2.0, 3))
    print(chr(65), chr(0x1F600), ord('a'), ord('é'), hex(255), hex(-16), oct(8), bin(5), bin(-5), int('ff', 16), int('0b101', 0))
    print((255).bit_length(), (-8).bit_length(), (0).bit_length(), (7).bit_count(), (1.5).is_integer(), (2.0).is_integer(), (3).is_integer() if False else 0)
    print((10).to_bytes(2, 'big'), int.from_bytes(b'\x01\x00', 'little'), (1.5).as_integer_ratio(), (6).as_integer_ratio())

numbers()

# id, vars, globals, dir-free introspection
class P:
    def __init__(self):
        self.x = 1
p = P()
print(id(p) == id(p), id(p) != id(P()), vars(p), 'P' in globals(), callable(len), isinstance(id(p), int))

def scope():
    a = 1
    b = 'two'
    return locals()

print(scope())

# sum with a start, and the iterator protocol of the builtins' results
print(sum([1, 2], 10), sum([[1], [2]], []), sum([0.1] * 10), sum(range(101)), sum((x * x for x in range(4)), 1))
z = zip([1, 2], 'ab')
print(next(z), next(z), next(z, 'done'))
e = enumerate(['a'])
print(next(e), iter(e) is e)
