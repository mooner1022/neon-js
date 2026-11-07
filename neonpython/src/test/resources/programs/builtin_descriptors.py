# A builtin type's methods as CPython's: method_descriptor (str.upper), wrapper_descriptor (str.__add__), method-wrapper
# ((1).__add__), their reprs, __objclass__ and __get__, inspect's view of them (signatures, predicates), pickling
# them; a builtin type's own __new__ and __text_signature__. Docstrings of builtins are not kept (None), so getdoc is
# left out.
import types, inspect, collections, itertools, struct, pickle, copy, hashlib


def t(label, f):
    try:
        print(label, '->', f())
    except BaseException as e:
        print(label, '!!', type(e).__name__, e)


def kinds():
    t('repr', lambda: (repr(str.upper), type(str.upper).__name__, repr('x'.upper)[:30], type('x'.upper).__name__))
    t('objclass', lambda: (str.upper.__objclass__, str.upper.__name__, str.upper.__qualname__))
    t('get', lambda: (str.upper.__get__('ab')(), str.upper.__get__(None, str) is str.upper))
    t('get error', lambda: str.upper.__get__(1))
    t('call', lambda: (str.upper('ab'), str.upper.__call__('cd')))
    t('types', lambda: (types.MethodDescriptorType.__name__, types.WrapperDescriptorType.__name__, types.MethodWrapperType.__name__, types.BuiltinFunctionType.__name__))
    t('isinst', lambda: (isinstance(len, types.BuiltinFunctionType), isinstance(str.join, types.MethodDescriptorType), isinstance(object.__init__, types.WrapperDescriptorType), isinstance(object().__str__, types.MethodWrapperType)))
    t('reprs', lambda: (repr(str.__add__), repr(object.__init__), repr(struct.Struct.pack), repr(collections.deque.append), repr(itertools.count.__next__)))
    t('mw', lambda: (repr(object().__str__)[:40], (1).__add__(2), type((1).__add__).__name__))


def views():
    t('preds', lambda: (inspect.ismethoddescriptor(str.upper), inspect.isroutine(str.upper), inspect.isbuiltin(str.upper), inspect.isbuiltin('x'.upper), inspect.ismethoddescriptor(len)))
    t('sigs', lambda: [str(inspect.signature(f)) for f in (str.upper, str.split, list.append, dict.get, len, sorted)])
    t('type sigs', lambda: (str(inspect.signature(object)), str(inspect.signature(hashlib.blake2b))[:30], object.__text_signature__, getattr(range, '__text_signature__', 'x')))
    t('no sig', lambda: str(inspect.signature(str)))
    t('isroutine', lambda: [inspect.isroutine(f) for f in (str.upper, 'x'.upper, len, str.__add__, (1).__add__)])
    t('new', lambda: (type(str.__new__).__name__, type(object.__new__).__name__, range.__new__(range, 2, 5), type(range.__new__).__name__, range.__new__ is object.__new__))
    t('new error', lambda: range.__new__(int, 3))


def uses():
    import functools
    t('partial', lambda: functools.partial(str.upper)('ab'))
    t('mapping', lambda: list(map(str.upper, ['a', 'b'])))
    t('sorted key', lambda: sorted(['b', 'A', 'c'], key=str.lower))
    t('pickle', lambda: pickle.loads(pickle.dumps(str.upper))('q'))
    t('pickle more', lambda: (pickle.loads(pickle.dumps('xy'.upper))(), pickle.loads(pickle.dumps(len))('abc'), pickle.loads(pickle.dumps((5).__add__))(1), pickle.loads(pickle.dumps(str.__add__))('a', 'b')))
    t('reduce', lambda: (str.upper.__reduce__()[1], 'z'.upper.__reduce__()[1], len.__reduce__(), copy.copy(str.upper) is str.upper))


kinds()
views()
uses()
