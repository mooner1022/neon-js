# list methods
a = [3, 1, 2]
a.append(4)
a.extend([5, 6])
a.extend(range(2))
a.insert(0, 'first')
a.insert(-1, 'before last')
a.insert(100, 'end')
print(a, a.index(2), a.count(1), a.index(1, 3), len(a))
a.remove('first')
print(a.pop(), a.pop(0), a)
b = [5, 2, 8, 1]
b.sort()
print(b, b.reverse(), b)
words = ['bb', 'a', 'ccc', 'dd']
words.sort(key=len)
print(words)
words.sort(key=len, reverse=True)
print(words)
c = b.copy()
c.append(0)
print(b, c, c is b)
c.clear()
print(c, [1, 2] * 2, [[0] * 2] * 2)
nums = [1, 2, 3]
nums += [4]
nums *= 2
print(nums)

# tuple methods
t = (1, 2, 2, 3)
print(t.count(2), t.index(3), t.index(2, 2))

# dict methods and views
d = {'a': 1, 'b': 2}
k = d.keys()
v = d.values()
it = d.items()
print(k, v, it, len(k), 'a' in k, 1 in v, ('a', 1) in it, ('a', 2) in it)
d['c'] = 3
print(k, list(v), list(it))
print(k & {'a', 'z'}, sorted(k | {'z'}), sorted(k - {'a'}), it & {('b', 2)}, k == {'a', 'b', 'c'}, type(k).__name__, type(v).__name__, type(it).__name__)
print(d.get('z'), d.get('z', 0), d.pop('a'), d.pop('zz', 'missing'), d, d.setdefault('b', 99), d.setdefault('new', []), d)
d.update({'x': 1}, y=2)
d.update([('p', 0)])
print(d, d.popitem(), d)
e = dict.fromkeys(['k1', 'k2'], 0)
f = e.copy()
f['k1'] = 5
print(e, f, dict.fromkeys('ab'), list(reversed(d)), list(reversed(d.keys())) if False else 0)
e.clear()
print(e, len(f), list(f.items())[0])
for key, value in {'one': 1, 'two': 2}.items():
    print(key, value)
print(sorted({'b': 1, 'a': 2}.items()), max({'a': 3, 'b': 5}.values()), list({'x': 1}.keys()), dict(zip('ab', [1, 2])))
counts = {}
for ch in 'hello':
    counts[ch] = counts.get(ch, 0) + 1
print(counts, {k2: v2 for k2, v2 in counts.items() if v2 > 1})
