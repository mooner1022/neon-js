# types.MappingProxyType, a class's __dict__ one: read-only, CPython's messages.
import types


def main():
    m = types.MappingProxyType({'a': 1, 'b': 2})
    print(repr(m), str(m), m['a'], m.get('z', 0), len(m), list(m), 'a' in m, m.keys(), m.copy(), type(m.copy()).__name__, m == {'a': 1, 'b': 2}, {'a': 1, 'b': 2} == m, m | {'c': 3}, {'z': 0} | m, list(reversed(m)))
    for f in [lambda: m.__setitem__('a', 2), lambda: m.__delitem__('a'), lambda: types.MappingProxyType([1]), lambda: hash(m), lambda: m['zz'], lambda: setattr(m, 'x', 1), lambda: type('X', (types.MappingProxyType,), {})]:
        try:
            f()
        except Exception as e:
            print(type(e).__name__, e)

    class C:
        x = 1

    print(type(C.__dict__).__name__, C.__dict__ is C.__dict__, C.__dict__['x'], vars(C)['x'], type(vars(C)).__name__, types.MappingProxyType.__name__, types.MappingProxyType[int])
    try:
        C.__dict__['y'] = 2
    except TypeError as e:
        print(e)
    print(sorted(k for k in C.__dict__ if not k.startswith('__')), C.__dict__.get('x'), 'x' in vars(C))


main()
