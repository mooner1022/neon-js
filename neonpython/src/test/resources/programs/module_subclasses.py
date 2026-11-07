# types.ModuleType subclassed: properties and setters, a class __getattr__ and __getattribute__, __init__ through
# super(), module.__new__/__init__'s errors, repr (the class's, importlib's), __class__ set to a subclass and back,
# the running module's own (sys.modules[__name__].__class__ = ...).
import sys, types, importlib.util
def t(label, f):
    try:
        print(label, '->', f())
    except BaseException as e:
        print(label, '!!', type(e).__name__, e)
class M(types.ModuleType):
    count = 0
    @property
    def lazy(self):
        return 'computed ' + self.__name__
    @property
    def rw(self):
        return self.__dict__.get('_rw')
    @rw.setter
    def rw(self, v):
        self.__dict__['_rw'] = v * 2
    def __getattr__(self, name):
        return 'missing ' + name
m = types.ModuleType('mm', 'docs')
def setc():
    m.__class__ = M
    return type(m).__name__, m.lazy, isinstance(m, M), m.__doc__, m.count, m.whatever
t('module class', setc)
def setrw():
    m.rw = 4
    return m.rw, m._rw, m.__dict__['_rw']
t('setter', setrw)
def back():
    m.__class__ = types.ModuleType
    return type(m).__name__, hasattr(m, 'lazy')
t('back', back)
t('module sub', lambda: (M('x').lazy, type(M('x')).__name__, repr(M('y')), M('z', 'd').__doc__, M('w').__dict__))
t('bad class', lambda: setattr(types.ModuleType('q'), '__class__', int))
class N(types.ModuleType):
    def __init__(self, name):
        super().__init__(name)
        self.extra = 1
t('init', lambda: (N('n').extra, N('n').__name__))
t('errors', lambda: M())
t('errors2', lambda: M(1))
class Self(types.ModuleType):
    def __getattribute__(self, name):
        if name == 'magic':
            return 42
        return super().__getattribute__(name)
s = Self('s')
t('getattribute', lambda: (s.magic, s.__name__))
this = sys.modules[__name__]
class Deprecating(types.ModuleType):
    @property
    def OLD(self):
        return 'old value'
this.__class__ = Deprecating
t('main module', lambda: (OLD if False else this.OLD, type(this).__name__))
class R(types.ModuleType):
    def __repr__(self): return 'R!'
t('repr', lambda: (repr(R('r')), str(R('r')), repr(types.ModuleType('plain'))))
t('kw', lambda: (types.ModuleType(nm='x')))
t('kw2', lambda: (types.ModuleType('a', doc='d').__doc__, types.ModuleType(name='b').__name__))
