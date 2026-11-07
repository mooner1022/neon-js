# The builtin types' operator methods as CPython 3.14's dicts have them (set.__sub__, complex.__radd__, list.__iadd__,
# range.__len__, dict.__or__...), a method's __get__ and ==, classmethod's and staticmethod's attributes, super() with one
# argument, type.__base__ and __bases__ assignment, isinstance() through __class__, attribute hooks as descriptors.
import collections.abc as cabc
import types


def t(label, f):
    try:
        print(label, '->', f())
    except BaseException as e:
        print(label, '!!', type(e).__name__, e)


def part1():
    class A: pass
    class B(A): pass
    class Q: __slots__ = ('a',)
    class M(A, Q): pass
    class I(int): pass
    t('base', lambda: (object.__base__, int.__base__, bool.__base__, B.__base__, M.__base__, I.__base__, type.__base__, Exception.__base__))


    class Proxy:
        def __init__(self, obj):
            self._obj = obj
        @property
        def __class__(self):
            return type(self._obj)
    t('isinstance proxy', lambda: (isinstance(Proxy(1), int), isinstance(Proxy(1), Proxy), isinstance(Proxy(B()), A), issubclass(Proxy, int)))
    t('proxy class', lambda: (Proxy(1).__class__, type(Proxy(1)).__name__))


    class Meth:
        def m(self):
            return self
    a, b = Meth(), Meth()
    t('method get', lambda: (a.m.__get__(b, Meth)() is a, a.m.__get__(None, Meth)() is a))
    t('complex kw', lambda: (complex(imag=42, real=666), complex(real=1), complex(imag=2)))
    t('complex kw bad', lambda: complex(foo=1))
    t('str kw', lambda: (str(object=500), str(object=b'abc', errors='strict')))


    def setdict():
        class C: pass
        C.__dict__ = {}
    t('set class dict', setdict)


    def slotmsg():
        class X:
            __slots__ = ('a',)
        return X().a
    t('slot msg', slotmsg)
    t('member type', lambda: (type(Q.__dict__['a']) is types.MemberDescriptorType, type(Q.__dict__['a']).__name__))
    t('dir property', lambda: [n for n in ('fget', 'fset', 'fdel', 'getter', '__isabstractmethod__') if n in dir(property)])
    t('dunders', lambda: [n for t2, n in ((list, '__iadd__'), (list, '__imul__'), (complex, '__radd__'), (complex, '__rsub__'),
                                         (complex, '__rmul__'), (complex, '__rtruediv__'), (complex, '__rpow__'), (int, '__radd__'),
                                         (float, '__rmod__'), (range, '__len__'), (bytearray, '__iadd__'), (set, '__ior__'),
                                         (dict, '__ior__'), (str, '__rmod__'), (tuple, '__rmul__'), (bytes, '__rmod__'))
                             if not hasattr(t2, n)])


    def cm():
        class C:
            @classmethod
            def f(cls):
                "doc"
                return cls
            @staticmethod
            def g():
                "sdoc"
        c = C.__dict__['f']
        s = C.__dict__['g']
        c.x = 1
        return (c.__dict__, s.__dict__, c.__doc__, s.__doc__, c.__name__, s.__qualname__, c.__wrapped__ is c.__func__, c.__module__)
    t('classmethod attrs', cm)


    def cmnew():
        class MyClassMethod(classmethod):
            def __init__(self, func):
                pass
        class MyStaticMethod(staticmethod):
            def __init__(self, func):
                pass
        def func(): pass
        return (repr(MyClassMethod(func)), MyClassMethod(func).__func__, repr(MyStaticMethod(func)), MyStaticMethod(func).__func__)
    t('classmethod new', cmnew)


    def sup1():
        class A:
            def f(self):
                return 'A'
            @classmethod
            def cm(cls):
                return cls.__name__
        class B(A):
            def f(self):
                return 'B' + self.__super.f()
        B._B__super = super(B)
        s = super(B)
        return (B().f(), repr(s).split(' at ')[0], s.__thisclass__.__name__, s.__self__, s.__self_class__, super(B).__get__(B(), B).f(),
                super(B, B).cm())
    t('super one arg', sup1)


    def getattr_desc():
        class Descriptor:
            def __get__(self, obj, objtype=None):
                def getter(name):
                    return 'got ' + name
                return getter
        class X:
            __getattr__ = Descriptor()
        return X().foo
    t('getattr descriptor', getattr_desc)


    def special_desc():
        class SpecialDescr:
            def __init__(self, impl):
                self.impl = impl
            def __get__(self, obj, owner):
                return self.impl.__get__(obj, owner)
        class X:
            __len__ = SpecialDescr(lambda self: 5)
            __iter__ = SpecialDescr(lambda self: iter([1, 2]))
            __eq__ = SpecialDescr(lambda self, o: True)
        return (len(X()), list(X()), X() == 3)
    t('special descriptor', special_desc)


    def setdoc():
        class X:
            "elephant"
        X.__doc__ = "banana"
        return (X.__doc__, X.__dict__['__doc__'])
    t('set doc', setdoc)


    def mutable_bases():
        class C:
            def meth(self):
                return 'C'
        class D(C): pass
        class E:
            def meth(self):
                return 'E'
        d = D()
        r1 = d.meth()
        D.__bases__ = (E,)
        r2 = d.meth()
        class F(D): pass
        D.__bases__ = (C,)
        return (r1, r2, d.meth(), F().meth(), F.__mro__[1:3])
    t('mutable bases', mutable_bases)


def part2():
    class X:
        __slots__ = ('a',)
    def d(): del X().a
    t('del unset', d)
    t('get other', lambda: X.__dict__['a'].__get__(5))
    t('set ro', lambda: setattr(type, '__mro__', ()))
    class C: pass
    t('set mro', lambda: setattr(C, '__mro__', ()))
    t('set base', lambda: setattr(C, '__base__', object))
    t('set flags', lambda: setattr(C, '__flags__', 0))
    t('member qual', lambda: (X.a.__qualname__, repr(X.a), type(X.a).__name__, type(X.a).__qualname__))
    t('member is', lambda: (type(X.a) is types.MemberDescriptorType, type(types.FunctionType.__globals__) is type(X.a)))
    t('m eq', lambda: (C().__init__ == C().__init__))
    class M:
        def f(self): pass
    m = M()
    t('meth eq', lambda: (m.f == m.f, m.f != m.f, hash(m.f) == hash(m.f), m.f == M().f, m.f.__eq__(m.f)))
    t('dir meth', lambda: [n for n in ('__func__', '__self__', '__get__') if n in dir(m.f)])
    t('dir cm', lambda: [n for n in ('__func__', '__wrapped__', '__isabstractmethod__', '__dict__') if n in dir(classmethod)])
    t('dir super', lambda: [n for n in ('__thisclass__', '__self__', '__self_class__', '__get__') if n in dir(super)])


def part3():
    class C: pass
    class D(C): pass
    for lab, f in (('dup', lambda: setattr(D, '__bases__', (C, C))), ('del dict', lambda: delattr(C, '__dict__')),
                   ('prop fget', lambda: setattr(property(), 'fget', 1)), ('prop del', lambda: delattr(property(), 'fdel')),
                   ('cm func', lambda: setattr(classmethod(len), '__func__', 1))):
        t(lab, f)
    t('reversed views', lambda: (list(reversed({1: 2}.keys())), list(reversed({1: 2}.items())), list(reversed({1: 2}.values()))))
    t('reversible', lambda: (isinstance({}.keys(), cabc.Reversible), isinstance({}, cabc.Reversible), isinstance(set(), cabc.Reversible)))
    t('buffer', lambda: (isinstance(b'', cabc.Buffer), isinstance(bytearray(), cabc.Buffer), isinstance(memoryview(b''), cabc.Buffer), isinstance('', cabc.Buffer)))
    t('set ops', lambda: (set.__sub__({1, 2}, {1}), frozenset.__or__(frozenset({1}), {2}), set.__and__({1}, [1]), {1, 2}.__rsub__({2, 3})))
    s = {1, 2}
    t('iand', lambda: (set.__iand__(s, {2, 3}), s))
    t('list ops', lambda: (list.__iadd__([1], 'ab'), list.__imul__([1, 2], 2), list.__lt__([1], [2]), list.__lt__([1], (2,))))
    t('complex ops', lambda: (complex.__radd__(1j, 2), complex.__add__(1j, 'a'), complex.__pow__(2j, 2), complex.__lt__(1j, 2j), complex.__eq__(1+0j, 1)))
    t('complex pow mod', lambda: complex.__pow__(1j, 2, 3))
    t('range', lambda: (range.__len__(range(5)), range.__getitem__(range(5), -1), list(range.__reversed__(range(3))), range.__eq__(range(0), range(1, 1))))
    t('dict ops', lambda: (dict.__or__({1: 2}, {3: 4}), dict.__ror__({1: 2}, {3: 4}), dict.__or__({}, []), list(dict.__reversed__({1: 2, 3: 4}))))
    d = {1: 2}
    t('dict ior', lambda: (dict.__ior__(d, [(5, 6)]), d))
    t('str mod', lambda: (str.__mod__('%s!', 'x'), str.__rmod__('x', '%s?'), str.__rmod__('x', 5)))
    t('int divmod', lambda: (int.__divmod__(7, 2), int.__rdivmod__(7, 2), float.__divmod__(7.5, 2), int.__divmod__(7, 2.0), round(2.5), int.__round__(15, -1), float.__round__(2.675, 2)))
    t('bool ops', lambda: (True & False, bool.__or__(True, False), bool.__and__(True, 3), True ^ True, repr(bool.__xor__(False, True))))
    class SL(list):
        def __iadd__(self, o):
            return super().__iadd__(o + o)
    x = SL([1])
    x += [2]
    t('super iadd', lambda: x)
    class SS(set):
        def __sub__(self, o):
            return ('sub', super().__sub__(o))
    t('super sub', lambda: SS({1, 2}) - {1})


part1()
part2()
part3()
