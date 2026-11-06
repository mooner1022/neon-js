# sys past its modules and streams: struct sequences, implementation (a SimpleNamespace), the int-str limit, audit hooks.
import sys


def structs():
    v = sys.version_info
    print(v, v.major, v[:2], v >= (3, 14), type(v).__name__, type(v).__module__, len(v), v.releaselevel, list(v)[3])
    print(len(sys.flags), sys.flags.optimize, sys.flags.utf8_mode, sys.flags.gil, type(sys.flags).n_fields, type(sys.flags).n_sequence_fields)
    print(type(v).__match_args__, type(sys.flags).__match_args__[-1], sys.flags.__reduce__()[1][1])
    print(sys.float_info.max, sys.float_info.epsilon, sys.float_info.min, sys.float_info.dig, sys.float_info.mant_dig, sys.float_info)
    print(sys.int_info, sys.hash_info.modulus, sys.hash_info.inf, sys.maxunicode, sys.maxsize)
    print(hash(2 ** 61) == 1, hash(float('inf')) == sys.hash_info.inf, sys.thread_info.lock)
    for f in [lambda: type(sys.flags)(), lambda: setattr(sys.version_info, 'major', 4)]:
        try:
            print(f())
        except Exception as e:
            print(type(e).__name__, e)
    match sys.version_info:
        case (3, minor, *_):
            print("3 with", minor)


def namespaces():
    impl = sys.implementation
    print(type(impl).__name__, type(impl).__module__, isinstance(impl.name, str), impl.version == sys.version_info, impl.hexversion == sys.hexversion)
    ns = type(impl)(a=1, b='x')
    print(repr(ns), ns == type(impl)(a=1, b='x'), ns != type(impl)(a=2), type(impl)({'c': 2}, d=3), ns.__dict__)
    ns.c = [ns]
    print(ns, ns.__replace__(a=5))
    del ns.c
    print(ns)

    class N(type(impl)):
        pass

    print(repr(N(x=1)), repr(type(impl)()))
    for f in [lambda: type(impl)(1), lambda: type(impl)({1: 2}), lambda: hash(ns), lambda: type(impl)({}, {})]:
        try:
            print(f())
        except Exception as e:
            print(type(e).__name__, e)


def limits():
    print(sys.get_int_max_str_digits(), sys.int_info.default_max_str_digits)
    sys.set_int_max_str_digits(5000)
    print(sys.get_int_max_str_digits(), len(str(10 ** 4999)), len(str(-10 ** 4999)))
    for f in [lambda: sys.set_int_max_str_digits(100), lambda: str(10 ** 5000), lambda: int('1' * 5001), lambda: repr(-10 ** 5000), lambda: f'{10 ** 5000}',
              lambda: len(hex(10 ** 6000)), lambda: int('f' * 6000, 16) > 0, lambda: sys.set_int_max_str_digits(maxdigits=700)]:
        try:
            print(f())
        except Exception as e:
            print(type(e).__name__, e)
    print(sys.get_int_max_str_digits())
    sys.set_int_max_str_digits(0)
    print(len(str(10 ** 6000)), len(f'{10 ** 6000:d}'), len(str(int('9' * 6000))))
    sys.set_int_max_str_digits(4300)


def reprs():
    l = [1]
    l.append(l)
    d = {}
    d['me'] = d
    d['l'] = l
    print(l, d, [l, l], {'x': [d]})


def others():
    print(sys.getdefaultencoding(), sys.getfilesystemencoding(), sys.intern('ab'), sys.is_finalizing(), sys.getsizeof(1) > 0, sys.getsizeof([1, 2]) > sys.getsizeof([]))
    print(sys.stdlib_module_names >= {'os', 'sys', 'itertools'}, 'sys' in sys.builtin_module_names, type(sys.builtin_module_names).__name__)
    print(sys.prefix == sys.base_prefix, isinstance(sys.platlibdir, str), type(sys.meta_path).__name__, type(sys.path_hooks).__name__, sys.warnoptions)
    seen = []
    sys.addaudithook(lambda e, a: seen.append((e, a)))
    sys.audit('my.event', 1, 2)
    sys.audit('other')
    print(seen)
    try:
        sys.intern(1)
    except TypeError as e:
        print(e)

    class Big:
        def __sizeof__(self):
            return 100

    print(sys.getsizeof(Big()) >= 100)


structs()
namespaces()
limits()
reprs()
others()
