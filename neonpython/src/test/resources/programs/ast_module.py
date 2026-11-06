# CPython's ast, bundled, over NeonPy's _ast (generated from CPython's ASDL) and compile(..., PyCF_ONLY_AST): every position
# as CPython gives it, dump, literal_eval, walk, visitors, repr, compare, match.
import ast


def t(label, f):
    try:
        print(label, "->", f())
    except Exception as e:
        print(label, "!!", type(e).__name__, e)


srcs = [
    "x = 1\nif y:\n    z = f(a, *b, k=2)\nelse:\n    pass\n",
    'é = "ü"; a.b[1:2] += -c',
    "def f(a, /, b: int = 1, *c, d, **e) -> int:\n    '''doc'''\n    return [x for x in y if x] + {k: v for k, v in z}\n",
    "class C(B, metaclass=M):\n    @dec(1)\n    async def m(self):\n        async with a as b, c:\n            await x\n",
    "try:\n    pass\nexcept* (E, F) as e:\n    raise X from e\nfinally:\n    del a[0], b.c\n",
    "match p:\n    case [1, *r] | {'k': v, **kw} | P(a, b=c) if g:\n        pass\n    case _:\n        x = lambda q, *w: q\n",
    "f'{a!r:>{w}} and {b=}' + t'{c}'\n(x := 3)\nwhile not a and b or c: break\nfor i, j in k: continue\n",
    "import a.b as c, d\nfrom . import (e as f)\nglobal g\nassert h, 'm'\nwith (open(x) as y, z): ...\n",
    "type T[U: int, *V, **W] = list[U] | None\ns = x[1:2:3, ...]\nr = (yield) if a else (yield from b)\n",
    '"""multi\nline""" + rb"x"\nu"y"\n0x1F + 1_000 + 3.5j\n',
]


def positions():
    for s in srcs:
        print(ast.dump(ast.parse(s), include_attributes=True))


def api():
    tree = ast.parse("x = 1\nif y:\n    z = f(a, *b, k=2)\nelse:\n    pass\n")
    print(ast.dump(tree))
    print(ast.dump(tree, include_attributes=True)[:400])
    print(ast.dump(ast.parse('é = "ü"; a.b[1:2] += -c', mode='exec'), include_attributes=True)[:500])
    e = ast.parse('1 + 2 * x', mode='eval')
    print(type(e).__name__, ast.dump(e), ast.literal_eval('[1, (2, "a"), {3: None}, -4.5, 1+2j]'))
    t("literal bad", lambda: ast.literal_eval('f(1)'))
    print([type(n).__name__ for n in ast.walk(ast.parse('a + b'))])

    class V(ast.NodeVisitor):
        def visit_Name(self, node):
            print('name', node.id, node.lineno, node.col_offset)

    V().visit(ast.parse('foo(bar, baz.qux)'))

    class T(ast.NodeTransformer):
        def visit_Constant(self, node):
            return ast.Constant(node.value * 2)

    print(ast.dump(ast.fix_missing_locations(T().visit(ast.parse('x = 1 + 2')))))
    print(ast.get_docstring(ast.parse('def f():\n    "doc"\n    pass').body[0], clean=False), ast.get_source_segment('a = b + c', ast.parse('a = b + c').body[0].value))
    print(repr(ast.parse('x = [1, 2, 3]')), repr(ast.Name('x')), ast.Name('x').ctx is ast.parse('y').body[0].value.ctx)
    print(ast.compare(ast.parse('a+b'), ast.parse('a + b')), ast.dump(ast.parse('a', mode='single')))
    t("syntax", lambda: ast.parse('x = = 1'))
    print(isinstance(tree, ast.AST), isinstance(tree.body[0], ast.stmt), ast.Assign._fields, ast.Assign.__match_args__)
    match tree.body[0]:
        case ast.Assign(targets=[ast.Name(id=n)], value=ast.Constant(value=v)):
            print('matched', n, v)


positions()
api()
