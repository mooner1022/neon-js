# hash() and __hash__ agree (tuple, str, int subclasses, struct sequences; a __hash__'s result in range kept, larger ones hashed); struct sequences take no attributes.
import sys, time, os
def t(label, f):
    try:
        print(label, "->", f())
    except Exception as e:
        print(label, "!!", type(e).__name__, e)
class S(str): pass
class T(tuple): pass
class I(int): pass
t("tuple", lambda: (hash((1, 2)) == (1, 2).__hash__(), hash((1, 'a')) == tuple.__hash__((1, 'a')), hash(T((1, 2))) == hash((1, 2))))
t("str", lambda: (hash('ab') == 'ab'.__hash__(), hash(S('ab')) == hash('ab'), S('ab').__hash__() == hash('ab')))
t("int", lambda: (hash(I(5)) == 5, I(5).__hash__(), (2**70).__hash__() == hash(2**70), (1.5).__hash__() == hash(1.5)))
t("struct hash", lambda: hash(sys.version_info) == hash(tuple(sys.version_info)))
t("struct setattr", lambda: setattr(time.gmtime(0), 'x', 1))
t("version setattr", lambda: setattr(sys.version_info, 'major', 1))
t("stat setattr", lambda: setattr(os.stat_result(range(10)), 'y', 1))
class H:
    def __hash__(self): return 2**62 + 5
class H2:
    def __hash__(self): return -1
class H3:
    def __hash__(self): return 2**70
t("custom", lambda: (hash(H()), hash(H2()), hash(H3())))
