# builtin functions' and methods' names and text signatures (CPython's, by their module and type), and a function's names set.
import math, itertools
for f in (len, sorted, print, max, getattr, math.sqrt, math.log, str.join, list.append, dict.get, 'x'.split, [].sort, int.to_bytes, dict.fromkeys, object.__init__, itertools.chain.from_iterable):
    print(f.__qualname__, f.__text_signature__)
print(len.__module__, math.sqrt.__module__, len.__self__.__name__, str.join.__name__)
def f(a): pass
f.__qualname__ = "X.y"
f.__name__ = "y"
print(f.__qualname__, f.__name__, repr(f).startswith('<function X.y at 0x'))
try:
    f()
except TypeError as e:
    print(e)
for v in (1, None):
    try:
        f.__qualname__ = v
    except TypeError as e:
        print(e)
    try:
        f.__name__ = v
    except TypeError as e:
        print(e)
try:
    del f.__qualname__
except TypeError as e:
    print(e)
