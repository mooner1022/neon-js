# splitting and joining
s = '  hello  world \t\n'
print(s.split(), s.split(' '), 'a,b,,c'.split(','), 'a,b,c'.split(',', 1), 'a b c'.rsplit(None, 1), 'a,b,c'.rsplit(',', 1))
print('x'.split(','), ''.split(), ''.split(','), '  a  b  '.split(None, 1), 'a b'.split(), 'one\ntwo\r\nthree\rfour'.splitlines())
print('a\nb\n'.splitlines(True), ''.splitlines(), 'a\x0bb\x1cc'.splitlines(), ','.join(['a', 'b']), 'ab'.join('xyz'))
print('key=value=x'.partition('='), 'key=value=x'.rpartition('='), 'abc'.partition('z'), 'abc'.rpartition('z'))

# stripping, padding, case
print(repr(s.strip()), repr(s.lstrip()), repr(s.rstrip()), 'xxhixx'.strip('x'), '<<a>>'.lstrip('<'), 'a!?'.rstrip('?!'), '\xa0a\xa0'.strip())
print('[' + 'abc'.center(6) + ']', '[' + 'ab'.center(5, '*') + ']', '[' + 'abc'.ljust(5, '.') + ']', '[' + 'abc'.rjust(5) + ']', 'abc'.center(2))
print('42'.zfill(5), '-42'.zfill(5), '+7'.zfill(3), 'abc'.zfill(2), '3.14'.zfill(6))
print('hello World'.upper(), 'HeLLo'.lower(), 'hello world'.title(), "they're bill's".title(), 'hELLO'.swapcase(), 'hello WORLD'.capitalize())
print('Straße'.upper(), 'Straße'.casefold(), 'ǅ'.lower(), 'aBc'.casefold() == 'ABC'.casefold())

# finding and counting (by code point)
t = 'hello hello'
print(t.find('lo'), t.rfind('lo'), t.find('x'), t.find('l', 5), t.find('l', 3, 4), t.index('o'), t.rindex('h'), t.count('l'), t.count('l', 3), ''.count(''), 'aaa'.count('a', 1))
print('a😀b😀c'.find('b'), 'a😀b😀c'.rfind('😀'), 'a😀b'.index('b'), 'a😀b😀c'.count('😀'), len('a😀b'))
print(t.startswith('he'), t.startswith(('x', 'hel')), t.startswith('lo', 3), t.endswith('lo'), t.endswith(('a', 'lo')), t.endswith('hel', 0, 3))
print('a-b-c'.replace('-', '+'), 'a-b-c'.replace('-', '', 1), 'abc'.replace('', '|'), 'aaa'.replace('a', 'b', 2), 'abc'.replace('x', 'y'))
print('prefix_name'.removeprefix('prefix_'), 'name.py'.removesuffix('.py'), 'abc'.removeprefix('x'), 'a\tb\tc'.expandtabs(4), 'ab\tc'.expandtabs())

# predicates
for v in ['abc', 'ABC', 'Abc', 'abc1', '123', '½', '²', '٣', ' \t', '', 'a b', 'Title Case', '_id1', '1id', 'é', 'x\x00']:
    print(repr(v), v.isalpha(), v.isalnum(), v.isdigit(), v.isdecimal(), v.isnumeric(), v.isspace(), v.islower(), v.isupper(), v.istitle(), v.isidentifier(), v.isascii(), v.isprintable())

# translate and maketrans
table = str.maketrans('abc', 'xyz', 'd')
print('abcdef'.translate(table), 'hello'.translate({ord('l'): None, ord('h'): 'J'}), str.maketrans({'a': '1'}), 'aaa'.translate(str.maketrans('a', 'b')))
print('%s|%s' % ('a'.center(3), 'é'.ljust(3, 'ü')), 'abc'[::-1].upper().center(7, '-'))
