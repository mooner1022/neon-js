# What abc's ABCMeta stands on: a metaclass's __instancecheck__ and __subclasscheck__, __subclasses__, mro(),
# __prepare__, __subclasshook__, __abstractmethods__ and the classes object.__new__ refuses for them.
class Meta(type):
    def __instancecheck__(cls, inst):
        print("instancecheck", cls.__name__, type(inst).__name__)
        return inst == 42

    def __subclasscheck__(cls, sub):
        print("subclasscheck", cls.__name__, getattr(sub, "__name__", sub))
        return sub is int


class M(metaclass=Meta):
    pass


class Sub(M):
    pass


class Checker:
    def __instancecheck__(self, inst):
        return isinstance(inst, str)

    def __subclasscheck__(self, sub):
        return sub is bytes


def checks():
    print(isinstance(42, M), isinstance(1, M), isinstance(M(), M), isinstance(Sub(), M))
    print(issubclass(int, M), issubclass(str, M), issubclass(1, M), issubclass(int, (str, M)))
    print(isinstance('x', Checker()), isinstance(1, Checker()), issubclass(bytes, Checker()), isinstance(1, (Checker(), int)))
    try:
        issubclass(1, int)
    except TypeError as e:
        print(e)
    print(type.__instancecheck__(int, 3), type.__subclasscheck__(object, int), int.__instancecheck__(True))


class A:
    pass


class B(A):
    pass


class C(A):
    pass


class D(B, C):
    pass


def hierarchy():
    print(A.__subclasses__(), B.__subclasses__(), D.__subclasses__(), type.__subclasses__(A) == A.__subclasses__())
    print(D.mro(), type.mro(D), int.mro(), bool in int.__subclasses__())
    print(type(type.__prepare__('X', (), k=1)).__name__, Meta.__prepare__('X', ()), object.__subclasshook__(int), A.__subclasshook__(int))
    print(type(object.__subclasses__()).__name__, M in type.__subclasses__(type) or Meta in type.__subclasses__(type))


def abstract():
    class P:
        pass

    try:
        P.__abstractmethods__
    except AttributeError as e:
        print("AttributeError", e)
    P.__abstractmethods__ = frozenset({'z', 'a'})
    print(sorted(P.__abstractmethods__))

    class Q(P):
        pass

    print(type(Q()).__name__)
    try:
        Q.__abstractmethods__
    except AttributeError as e:
        print("AttributeError", e)
    try:
        P()
    except TypeError as e:
        print(e)
    P.__abstractmethods__ = {'only'}
    try:
        P()
    except TypeError as e:
        print(e)
    P.__abstractmethods__ = ()
    print(type(P()).__name__)
    del P.__abstractmethods__
    try:
        del P.__abstractmethods__
    except AttributeError as e:
        print("AttributeError", e)
    try:
        type.__abstractmethods__
    except AttributeError as e:
        print("AttributeError", e)
    print(type(iter(lambda: 1, 1)).__name__)


checks()
hierarchy()
abstract()
