# A class's own __dict__ and __weakref__ descriptors, the immutable builtin types (type's own __setattr__ and
# __delattr__, object's refused on a class), a metaclass's __setattr__ and __delattr__ for its classes, and
# importlib.util's bytecode paths, as CPython 3.14 gives them.
import importlib.util


def t(label, f):
    try:
        print(label, '->', f())
    except BaseException as e:
        print(label, '!!', type(e).__name__, e)



def descriptors():
    class A: pass
    class B(A): pass
    class S: __slots__ = ('__weakref__',)
    class P: __slots__ = ()
    class E(Exception): pass
    class L(list): pass
    t('A dict', lambda: sorted(k for k in vars(A) if k.startswith('__w') or k == '__dict__'))
    t('B dict', lambda: sorted(k for k in vars(B) if k.startswith('__w') or k == '__dict__'))
    t('S dict', lambda: sorted(k for k in vars(S)))
    t('A attr', lambda: (repr(A.__weakref__), A.__weakref__.__name__, A.__weakref__.__objclass__, A.__weakref__.__doc__))
    t('B attr', lambda: B.__weakref__ is A.__weakref__)
    a = A()
    t('none', lambda: a.__weakref__)
    r = weakref.ref(a)
    t('ref', lambda: a.__weakref__ is r)
    t('P attr', lambda: P.__weakref__)
    t('P inst', lambda: P().__weakref__)
    t('E attr', lambda: (repr(E.__weakref__), E().__weakref__))
    t('L', lambda: (repr(L.__weakref__), L().__weakref__))
    t('set', lambda: a.__setattr__('__weakref__', 1))
    t('del', lambda: a.__delattr__('__weakref__'))
    t('S inst', lambda: S().__weakref__)
    t('dict descr', lambda: (repr(A.__dict__['__dict__']), A().__dict__, repr(L.__dict__['__dict__'])))
    t('dir', lambda: ('__weakref__' in dir(A), '__weakref__' in dir(a), '__weakref__' in dir(P)))
    class A: pass
    class D(A): pass
    class Q: __slots__ = ('a',)
    class QD(Q): pass
    class I(int): pass
    class S: __slots__ = ('__dict__', 'x')
    class N:
        __dict__ = 5
    class E(Exception): pass
    t('order', lambda: list(vars(A)))
    t('order D', lambda: list(vars(D)))
    t('order QD', lambda: list(vars(QD)))
    t('I', lambda: [k for k in vars(I) if k in ('__dict__', '__weakref__')])
    t('S', lambda: list(vars(S)))
    t('N', lambda: (vars(N)['__dict__'], N().__dict__))
    t('E', lambda: [k for k in vars(E) if k in ('__dict__', '__weakref__')])
    a = A()
    a.x = 1
    def deld():
        del a.__dict__
        return vars(a)
    t('del dict', deld)
    t('after', lambda: (a.__dict__, hasattr(a, 'x')))
    t('set dict', lambda: (setattr(a, '__dict__', {'y': 2}), a.y))
    t('set bad', lambda: setattr(a, '__dict__', 5))
    t('descr', lambda: (A.__dict__['__dict__'].__get__(a), A.__dict__['__dict__'].__doc__, A.__dict__['__dict__'].__qualname__))
    t('descr other', lambda: A.__dict__['__dict__'].__get__(Q()))
    t('weak descr set', lambda: A.__dict__['__weakref__'].__set__(a, 1))
    t('weak descr del', lambda: A.__dict__['__weakref__'].__delete__(a))
    t('dict descr del', lambda: A.__dict__['__dict__'].__delete__(a))

def immutables():
    t('obj setattr str', lambda: object.__setattr__(str, 'foo', 42))
    t('obj delattr str', lambda: object.__delattr__(str, 'lower'))
    t('type setattr str', lambda: type.__setattr__(str, 'foo', 42))
    t('type delattr str', lambda: type.__delattr__(str, 'lower'))
    t('type delattr str missing', lambda: type.__delattr__(str, 'nope'))
    t('setattr str', lambda: setattr(str, 'foo', 1))
    t('delattr str', lambda: delattr(str, 'lower'))
    t('delattr int', lambda: delattr(int, 'bit_length'))
    class C: x = 1
    t('obj setattr C', lambda: object.__setattr__(C, 'y', 2))
    t('obj delattr C', lambda: object.__delattr__(C, 'x'))
    t('obj setattr int inst', lambda: object.__setattr__(1, 'y', 2))
    class A(type):
        def __setattr__(cls, key, value):
            object.__setattr__(cls, key, value)
    class B: pass
    class D(B, A): pass
    obj = D('E', (object,), {})
    t('indirect', lambda: setattr(obj, 'test', True))
    class L(list):
        def __setattr__(self, k, v): dict.__setattr__(self, k, v)
    t('list via dict', lambda: setattr(L(), 'a', 1))
    t('str lower', lambda: 'A'.lower())

def metaclasses():
    class Meta(type):
        def __setattr__(cls, k, v):
            print('meta set', k)
            super().__setattr__(k, v)
        def __delattr__(cls, k):
            print('meta del', k)
            super().__delattr__(k)
    class K(metaclass=Meta): pass
    K.a = 1
    t('a', lambda: K.a)
    del K.a
    t('gone', lambda: hasattr(K, 'a'))
    setattr(K, 'b', 2)
    delattr(K, 'b')
    class Color(enum.Enum):
        RED = 1
    def reassign():
        Color.RED = 2
    t('enum reassign', reassign)
    def delete():
        del Color.RED
    t('enum delete', delete)


def bytecode():
    # (CPython's bytecode paths as functions: NeonPy, which writes none, raises NotImplementedError from them)
    t('cache paths', lambda: (callable(importlib.util.cache_from_source), callable(importlib.util.source_from_cache)))


import weakref, enum
descriptors()
immutables()
metaclasses()
bytecode()
