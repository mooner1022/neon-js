# A metaclass's __prepare__ (with the class's keywords) and the namespace of a dict's subclass the body goes through;
# the __classdictcell__ a class with a def gets; type.__new__'s __set_name__ and __init_subclass__ with or without
# keywords.
class NS(dict):
    def __setitem__(self, k, v):
        print("set", k)
        super().__setitem__(k, v)

    def __getitem__(self, k):
        if k == 'magic':
            return 42
        return super().__getitem__(k)


class Meta(type):
    @classmethod
    def __prepare__(mcls, name, bases, **kw):
        print("prepare", name, kw)
        return NS()

    def __new__(mcls, name, bases, ns, **kw):
        print("new", type(ns).__name__, sorted(k for k in ns if not k.startswith('__')))
        return super().__new__(mcls, name, bases, dict(ns))


class C(metaclass=Meta, flag=1):
    x = 1
    y = magic

    def f(self):
        pass


print(C.y)


class NS2(dict):
    def __setitem__(self, k, v):
        if k.startswith('__class'):
            print("  set", k)
        super().__setitem__(k, v)


class Meta2(type):
    @classmethod
    def __prepare__(mcls, name, bases, **kw):
        return NS2()


print("A")
class A(metaclass=Meta2):
    x = 1
print("B")
class B(metaclass=Meta2):
    def f(self):
        pass
print("C")
class C(metaclass=Meta2):
    y = magic if False else 1
print("D")
class D(metaclass=Meta2):
    def f(self):
        return __class__
print("E")
class E(metaclass=Meta2):
    z: int = 1


import abc


class Base(abc.ABC):
    def __init_subclass__(cls, **kw):
        print("init_subclass", cls.__name__, kw)
        super().__init_subclass__()


class Sub(Base):
    pass


class Desc:
    def __set_name__(self, owner, name):
        print("set_name", owner.__name__, name)


class M(type):
    def __new__(mcls, name, bases, ns):
        return super().__new__(mcls, name, bases, ns)


class W(metaclass=M):
    d = Desc()


class Sub2(Base, flag=1):
    pass
