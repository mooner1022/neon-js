x = 1
#---
a = b = c = [1, 2, (3, 4)]
#---
a, b = b, a
#---
[a, *b] = c
#---
x += 1; y -= 2; z *= 3; w @= m; v /= 4; u //= 5; t %= 6; s **= 7; r >>= 1; q <<= 2; o &= 3; n |= 4; k ^= 5
#---
x: int
#---
x: list[int] = []
#---
(x): int = 1
#---
a.b: int = 2
#---
a[0]: str
#---
def f(): pass
#---
def f(a, b=1, *args, c, d=2, **kw) -> None:
    return a
#---
def f(a, /, b, *, c): ...
#---
def f(a=1, /, b=2): pass
#---
def f(*args: *Ts): pass
#---
def f[T, *Ts, **P](x: T) -> T: return x
#---
def f[T: int, U: (str, bytes) = str](): pass
#---
async def f():
    await x
    async for i in y:
        pass
    async with a as b, c as (d, e):
        pass
    return [i async for i in y]
#---
@dec
@dec.attr(1)
@(lambda f: f)
def f(): pass
#---
class C: pass
#---
class C(A, B, metaclass=M, **kw):
    x = 1
    def m(self): return self.x
#---
class C[T](Base[T]): pass
#---
@dataclass
class P:
    x: int = 0
#---
if a:
    pass
elif b:
    pass
elif c: x = 1
else:
    y = 2
#---
while x:
    x -= 1
    if x: break
    else: continue
else:
    done()
#---
for i in range(10): print(i)
#---
for i, (j, k) in items:
    pass
else:
    pass
#---
for x, in y: pass
#---
with open(f) as g: pass
#---
with a, b as c: pass
#---
with (a, b): pass
#---
with (a as b, c as d,): pass
#---
with (a, b) as c: pass
#---
with (yield x): pass
#---
try:
    pass
except ValueError:
    pass
except (TypeError, KeyError) as e:
    raise
except:
    raise RuntimeError("x") from e
else:
    pass
finally:
    pass
#---
try:
    pass
finally:
    pass
#---
try:
    pass
except ValueError, TypeError:
    pass
#---
try:
    pass
except* ValueError as e:
    pass
except* (TypeError, KeyError):
    pass
#---
raise
#---
raise E
#---
raise E from None
#---
assert x
#---
assert x, "message"
#---
del a
#---
del a, b[0], c.d, (e, f), [g]
#---
global a, b
#---
def f():
    nonlocal x
#---
import os
#---
import os.path as p, sys
#---
from os import path
#---
from os.path import join as j, split
#---
from . import x
#---
from .. import y
#---
from ...pkg.mod import (a, b as c,)
#---
from .... import z
#---
from m import *
#---
type X = int
#---
type L[T] = list[T]
#---
type = 1
#---
type(x)
#---
match = 1
#---
match(x)
#---
match x:
    case 1:
        pass
    case "s" | b"b":
        pass
    case -1 | 1.5 | -2j | 1 + 2j | -1 - 2j:
        pass
    case None | True | False:
        pass
    case a.b.c:
        pass
    case [1, 2, *rest]:
        pass
    case (1, *_):
        pass
    case ():
        pass
    case []:
        pass
    case {"k": v, 1: w, a.b: u, **rest}:
        pass
    case {}:
        pass
    case Point(x, y=0):
        pass
    case Point():
        pass
    case a.B(1, z=[2]):
        pass
    case [x] as y if y > 0:
        pass
    case (x):
        pass
    case x, y:
        pass
    case _:
        pass
#---
match x, y:
    case a, b: pass
#---
match *a, b:
    case _: pass
#---
match -x:
    case _: pass
#---
x = 1 if a else 2
#---
lambda: 0
#---
lambda x, y=1, *a, z, **k: x + y
#---
lambda x, /, y: x
#---
f(a)(b)[c].d
#---
f(a, *b, c=1, *d, **e)
#---
f(x for x in y)
#---
f(**a, b=1)
#---
a[1]
#---
a[1:2]
#---
a[::2]
#---
a[:, 1:, ::-1]
#---
a[1, 2]
#---
a[*b]
#---
a[x := 1]
#---
a[b, *c]
#---
(x := 5)
#---
[y := 1, y ** 2]
#---
f(x := 1)
#---
not a and b or not c
#---
a < b <= c == d != e > f >= g is h is not i in j not in k
#---
-a + +b - ~c
#---
a ** -b ** c
#---
-2 ** 2
#---
a | b ^ c & d << e >> f + g - h * i / j // k % l @ m
#---
await x ** 2
#---
[]
#---
[1, 2, 3,]
#---
[*a, *b, c]
#---
()
#---
(1,)
#---
(1, 2)
#---
(*a, b)
#---
{}
#---
{1: 2, "a": [3], **d, 4: 5}
#---
{1, 2, *s}
#---
[x for x in y]
#---
[x for x in y if x if not x for z in x]
#---
{k: v for k, v in d.items()}
#---
{x for x in y}
#---
(x for x in y)
#---
[x async for x in y]
#---
def g():
    yield
    yield 1
    yield 1, 2
    x = yield
    y = yield from z
    return (yield)
#---
...
#---
None; True; False
#---
1; 1.5; 1e10; 1E-5; .5; 5.; 0x1F; 0o17; 0b101; 1_000_000; 0.000_1; 10j; 1.5J; 99999999999999999999999
#---
'a' "b" '''c''' """d"""
#---
'a' 'b' "c"
#---
'\n\t\\\'\"\x41é\U0001F600\101\N{BULLET}'
#---
r'\n\d' R"\w"
#---
u'unicode'
#---
b'bytes' B"\x00\xff" rb'\d'
#---
f'plain'
#---
f'{x}'
#---
f'a{x}b{y!r}c{z:>10}d{w!s:{width}.{prec}}'
#---
f'{x=}'
#---
f'{x = }'
#---
f'{x=!s}'
#---
f'{x=:>5}'
#---
f'{{literal}} {x}'
#---
f'{"nested"}' f"{'q'}"
#---
f"{f'{x}'}"
#---
f'{x["k"]}'
#---
f'a' 'b' f'{c}'
#---
f'{a!r:{b}}'
#---
f"""multi
{line}
"""
#---
rf'\d{x}'
#---
f'{3.14:.2f} {1000000:,} {x:#x}'
#---
f'{lambda: 1}'
#---
f'{(lambda: 1)()}'
#---
f'{x:{"a"}}'
#---
f'{yield}'
#---
t'{x}'
#---
t'a{x}b{y!r:>{w}}'
#---
t'{x=}'
#---
t'a' t'b{c}'
#---
x if y else z if w else v
#---
a.b.c.d
#---
print("hello", end="", sep=" ")
#---
x = (
    1 +
    2
)
#---
x = 1 + \
    2
#---
if x:
    # comment
    pass  # trailing
#---
class A:
    @property
    def p(self): return 1
    @p.setter
    def p(self, v): pass
#---
def f(a, b, /, c, d, *, e, f): pass
#---
x = *a, *b
#---
for x in *a, *b: pass
#---
return_ = 1
#---
def f():
    return *a, b
#---
x = yield
#---
i = j = k = 0
#---
a, *b, c = d
#---
(a, b), c = d
#---
x[1:2] = y
#---
x.y.z = 1
#---
f() = 1
#---
1 = x
#---
x + 1 = 2
#---
def f(a, a): pass
#---
def f(a=1, b): pass
#---
f(a=1, b)
#---
f(**a, *b)
#---
f(x for x in y, z)
#---
del f()
#---
a, b: int
#---
x = 1 +
#---
if x
    pass
#---
try:
    pass
#---
try:
    pass
except:
    pass
except ValueError:
    pass
#---
'unterminated
#---
f'{}'
#---
f'{x!z}'
#---
def f(*): pass
#---
lambda: (yield)
#---
match x:
    case {**rest, "k": v}: pass
#---
nonlocal_ = global_ = 1
#---
x = [
    1,
    2,
]
#---
z = {
    'a': 1,
    'b': [
        2, 3
    ],
}
#---
if (n := len(a)) > 10:
    pass
#---
print(f"{'a' if x else 'b'}")
#---
x = not -1
#---
assert (yield)
#---
async def f():
    async with a:
        pass
    await asyncio.sleep(0)
    x = [await y for y in z]
#---
@a.b[c](d)
def f(): pass
#---
x = a if b else lambda: c
#---
print(*a, **b)
#---
x = 1_0.0_1e1_0
#---
rf'\{x}' f'\{y}'
#---
t'{ x }' t'{y = }'
#---
x = 5e-324, 1e-323, 2.2250738585072014e-308, 1e23, 1e16, 123456789.0
#---
f"""{1+2 = # comment
  }"""
#---
'\x80\u2028\xa0\u00ad'
