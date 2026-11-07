# every value has a type, and the builtin types are types
print(type(1), type(1.5), type(True), type('s'), type([]), type(()), type({}), type(None))
print(type(range(3)), type(print), type(lambda: 0), type(type), type(int))
print(int, str, object, type)
print(type(1) is int, type(True) is bool, type(type(1)) is type)

# isinstance and issubclass, bool an int
print(isinstance(1, int), isinstance(True, int), isinstance(1, bool), isinstance(1.0, int))
print(isinstance('a', (int, str)), isinstance([], (int, (dict, list))), isinstance(None, object))
print(issubclass(bool, int), issubclass(int, bool), issubclass(int, object), issubclass(type, object))
print(issubclass(bool, (str, int)))

# the builtin types called
print(int(), int('42'), int('-0x1f', 16), int('0o17', 0), int('1_000'), int(' 7 '), int(3.9), int(-2.5), int(True))
print(int('ff', 16), int('z', 36), int('101', 2))
print(float(), float('1.5'), float('-inf'), float(2))
print(str(), str(12), str([1, 'a']), bool(), bool([]), bool([0]))
print(list(), list('ab'), list(range(3)), tuple([1, 2]), tuple())
print(dict(), dict(a=1, b=2), dict([(1, 'x'), (2, 'y')]), dict({'k': 'v'}, z=0))
print(type(object()) is object)

# methods through their types
print(str.upper('abc'), str.join('-', ['a', 'b']))
l = [1]
list.append(l, 2)
print(l, dict.get({'a': 1}, 'a'), dict.get({}, 'x', 'dflt'))
up = 'xyz'.upper
print(up())

# a type's attributes
print(int.__name__, bool.__mro__, bool.__bases__, object.__bases__, int.__module__)
