from __future__ import annotations
# PEP 563: annotations are strings, their source as CPython's compiler writes an expression back
# (Python/ast_unparse.c): its parentheses by precedence, its constants' reprs, f-strings rebuilt.
import sys


def f(a: int, b: list[str] = None, *c: 'q', d: 1 + 2 = 3, **k: dict[str, int | None]) -> tuple[int, ...]:
    pass


print(f.__annotations__)
print(f.__annotate__.__qualname__, f.__annotate__(1) == f.__annotations__)


def star(*args: *Ts, **kw: Unpack[K]):
    pass


print(star.__annotations__)


class Corpus:
    a01: x + y * z
    a02: (x + y) * z
    a03: x ** y ** z
    a04: (x ** y) ** z
    a05: -x ** 2
    a06: (-x) ** 2
    a07: not x
    a08: not (x and y)
    a09: x and y or z
    a10: x and (y or z)
    a11: x < y <= z != w
    a12: (x < y) < z
    a13: x if y else z
    a14: (x if y else z) if w else v
    a15: lambda: 1
    a16: lambda x, y=1, *a, k, kk=2, **kw: x
    a17: lambda *a: a
    a18: lambda x, /, y: x
    a19: f(x, *y, z=1, **w)
    a20: f(x for x in y)
    a21: f((x for x in y), 1)
    a22: x[1:2, ::3]
    a23: x[1:2:3]
    a24: x[()]
    a25: x[a, b]
    a26: x[(a, b)]
    a27: x.y.z
    a28: (1).real
    a29: 1.5.real
    a30: [1, 2, *x]
    a31: {1: 2, **x}
    a32: {1, 2}
    a33: ()
    a34: (1,)
    a35: (1, 2)
    a36: [x for x in y if x if not x]
    a37: {x: y for x, y in z}
    a38: {x for x in y}
    a40: 'str'
    a41: "it's"
    a42: b'bytes'
    a43: 12345678901234567890
    a44: 1e309
    a45: 1e309j
    a46: 3j
    a47: ...
    a48: None
    a49: True
    a50: f'{x!r:>{w}} {{literal}}'
    a51: f'{x=}'
    a52: f"{'q'}"
    a53: t'{x} and {y!s:3}'
    a54: x | None
    a55: ~x
    a56: +x - -y
    a57: x @ y
    a58: x // y % z
    a59: x << 1 >> 2
    a60: x & y ^ z | w
    a63: x[*y]
    a64: f(*x)
    a65: u'unicode'
    a66: 0x1F
    a67: 1_000
    a68: 'a' 'b'
    a70: x if (y if z else w) else v
    a71: (lambda: 1)()
    a72: x[lambda: 1]
    a74: f'{(lambda: 1)}'
    a75: f'{ {1: 2} }'
    a76: (1 if x else 2, 3)
    a78: [*a, *b]
    a79: {**a, 'k': 1}
    a80: f(a)(b)[c].d
    a81: -1
    a82: (-1) ** 2
    a83: 2 ** -1
    a84: not -x
    a85: x is not y in z not in w
    a86: f'{x:{y}.{z}f}'
    a87: f'{x!a}'
    a88: f'''multi
line'''
    a89: '\n\t\\'
    a90: 'é'
    a91: 1.0
    a92: 1e-5
    a93: 0.1 + 0.2
    a94: (x for x in y)
    a95: [x for x in (y, z)]
    a96: x[1:]
    a97: x[:]
    a98: x[::]
    a99: (((x)))


for k, v in Corpus.__annotations__.items():
    print(k, v)
print(Corpus.__annotate__, sorted(k for k in Corpus.__dict__ if 'annot' in k))


class Conditional:
    if True:
        a: int
    b: str


print(Conditional.__annotations__)

mod_a: int = 1
mod_b: list[int]
if False:
    mod_c: str
print(__annotations__)
print(sys.modules[__name__].__annotations__ is __annotations__)
print(sorted(k for k in globals() if 'annot' in k))


def local():
    x: undefined = 1
    return x


print(local())
