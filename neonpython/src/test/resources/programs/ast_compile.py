# compile() of AST objects, as CPython 3.14's: the node each mode wants, Python-ast.c's errors for fields missing
# or of the wrong kind, ast.c's validation (CPython's own ASTValidatorTests, printing what each compile() raised),
# the compiler's errors for trees no parser makes, round trips (compile(ast.parse(src)) running as compile(src)),
# line numbers from the nodes, hand-made trees, NodeTransformers, PyCF_ONLY_AST's new tree, library modules
# compiled from their trees.
import ast
import io
import sys


def show(label, f):
    try:
        r = f()
        if type(r).__name__ == 'code':
            r = 'code ' + r.co_name
        print(label, 'ok', r)
    except SyntaxError as e:
        print(label, 'SyntaxError', e.msg, e.lineno, e.offset, e.text)
    except Exception as e:
        print(label, type(e).__name__, e)


def cc(tree, mode='exec', flags=0):
    return lambda: compile(tree, '<ast>', mode, flags)


P = dict(lineno=1, col_offset=0, end_lineno=1, end_col_offset=1)


def mod(*body):
    return ast.Module(list(body), [])


def mode_checks():
    print('-- modes')
    show('m1', cc(ast.Expression(ast.Constant(1, **P)), 'exec'))
    show('m2', cc(mod(), 'eval'))
    show('m3', cc(mod(), 'single'))
    show('m4', cc(ast.Interactive([]), 'exec'))
    show('m5', cc(ast.Name('x', ast.Load(), **P), 'exec'))
    show('m6', cc(ast.Expression(ast.Constant(1, **P)), 'single'))
    show('m7', cc(ast.Expression(ast.Constant(1, **P)), 'exec', ast.PyCF_ONLY_AST))
    show('m8', cc(ast.FunctionType([], ast.Constant(1, **P)), 'exec'))
    show('m9', cc(5, 'exec'))
    show('m10', cc(mod(), 'exec'))
    show('m11', cc(ast.Interactive([]), 'single'))
    show('m12', cc(ast.Expression(ast.Constant(1, **P)), 'eval'))
    show('m13', cc(ast.Expression(ast.Constant(1, **P)), 'nope'))
    show('m14', lambda: exec(ast.Module([], [])))


class S(str):
    pass


def required_fields():
    print('-- required fields')
    show('r1', cc(mod(ast.Pass())))
    show('r2', cc(mod(ast.Expr(ast.Name('x', ast.Load()), **P))))
    show('r3', cc(mod(5)))
    show('r4', cc(mod(ast.Name('x', ast.Load(), **P))))
    show('r5', cc(ast.Module(5, [])))
    show('r6', cc(ast.Module([ast.Pass(**P)], 5)))
    show('r7', cc(mod(ast.Expr(None, **P))))
    show('r8', cc(mod(ast.Expr(ast.Name(5, ast.Load(), **P), **P))))
    show('r9', cc(mod(ast.Expr(ast.Name(None, ast.Load(), **P), **P))))
    show('r10', cc(mod(ast.Expr(ast.Name('x', None, **P), **P))))
    show('r11', cc(mod(ast.Expr(ast.Name('x', 5, **P), **P))))
    show('r12', cc(mod(ast.Expr(ast.BinOp(ast.Constant(1, **P), ast.Load(), ast.Constant(1, **P), **P), **P))))
    show('r13', cc(mod(ast.Expr(ast.Name(S('x'), ast.Load(), **P), **P))))
    n = ast.Constant(1, **P)
    del n.value
    show('r14', cc(mod(ast.Expr(n, **P))))
    n = ast.Name('x', ast.Load(), **P)
    del n.ctx
    show('r15', cc(mod(ast.Expr(n, **P))))
    n = ast.Name('x', ast.Load(), **P)
    del n.id
    show('r16', cc(mod(ast.Expr(n, **P))))
    m = ast.Module([ast.Pass(**P)], [])
    del m.body
    show('r17', cc(m))
    show('r18', cc(ast.Module([ast.Pass(**P)])))


def field_values():
    print('-- field values')
    show('v1', cc(mod(ast.ImportFrom('m', [ast.alias('x', **P)], 'a', **P))))
    show('v2', cc(mod(ast.ImportFrom('m', [ast.alias('x', **P)], 2**40, **P))))
    show('v3', cc(mod(ast.ImportFrom('m', [ast.alias('x', **P)], -1, **P))))
    show('v4', cc(mod(ast.Pass(lineno='1', col_offset=0))))
    show('v5', cc(mod(ast.Pass(lineno=1, col_offset=0))))
    show('v6', cc(mod(ast.Pass(lineno=1.0, col_offset=0))))
    show('v7', cc(mod(ast.Pass(lineno=True, col_offset=0))))
    show('v8', cc(mod(ast.Expr(ast.Constant(1, kind=5, **P), **P))))
    show('v9', cc(mod(ast.Assign([ast.Name('x', ast.Store(), **P)], ast.Constant(1, **P), type_comment=5, **P))))
    show('v10', cc(mod(ast.Assign([ast.Name('x', ast.Store(), **P)], ast.Constant(1, **P), type_comment=b'x', **P))))
    show('v11', cc(mod(ast.FunctionDef('f', None, [ast.Pass(**P)], [], **P))))
    show('v12', cc(mod(ast.FunctionDef('f', 5, [ast.Pass(**P)], [], **P))))
    show('v13', cc(mod(ast.Expr(ast.Call(ast.Name('f', ast.Load(), **P), [], [None], **P), **P))))
    show('v14', cc(mod(ast.Expr(ast.Call(ast.Name('f', ast.Load(), **P), [None], [], **P), **P))))
    show('v15', cc(mod(None)))


def field_values2():
    show('v16', cc(mod(ast.Expr(ast.Name('x', ast.Load, **P), **P))))
    show('v17', cc(mod(ast.Expr(ast.UnaryOp(ast.Add(), ast.Constant(1, **P), **P), **P))))
    show('v18', cc(mod(ast.Expr(ast.Compare(ast.Constant(1, **P), [ast.Add()], [ast.Constant(1, **P)], **P), **P))))
    show('v19', cc(mod(ast.Expr(ast.BoolOp(ast.Add(), [ast.Constant(1, **P), ast.Constant(2, **P)], **P), **P))))
    show('v20', cc(mod(ast.Expr(ast.Lambda(ast.arguments(args=[ast.arg(5, **P)]), ast.Constant(1, **P), **P), **P))))
    show('v21', cc(mod(ast.Expr(ast.Lambda(ast.arguments(args=[ast.arg('a')]), ast.Constant(1, **P), **P), **P))))
    show('v22', cc(mod(ast.Expr(ast.Lambda(None, ast.Constant(1, **P), **P), **P))))
    show('v23', cc(mod(ast.Global([5], **P))))
    show('v24', cc(mod(ast.Import([ast.alias(None, **P)], **P))))
    show('v25', cc(mod(ast.Expr(ast.Constant(1, **P), lineno=2**31, col_offset=0))))
    show('v26', cc(mod(ast.Expr(ast.Constant(1, **P), lineno=-5, col_offset=0))))
    show('v27', cc(mod(ast.Expr(ast.JoinedStr([ast.FormattedValue(ast.Name('x', ast.Load(), **P), 'r', None, **P)], **P), **P))))
    show('v28', cc(mod(ast.Expr(ast.JoinedStr(5, **P), **P))))
    show('v29', cc(mod(ast.Expr(ast.List((ast.Constant(1, **P),), ast.Load(), **P), **P))))
    show('v30', cc(mod(ast.Expr(ast.Dict([None], [ast.Constant(1, **P)], **P), **P))))
    show('v31', cc(mod(ast.Expr(ast.Dict([ast.Constant(1, **P)], [None], **P), **P))))


class Boom:
    @property
    def lineno(self):
        raise KeyError('boom')


class Shrinking(list):
    pass


class Grower(ast.Expr):
    def __getattr__(self, name):
        if name != 'value':
            raise AttributeError(name)
        self.items.append(ast.Pass(**P))
        return ast.Constant(1, **P)


class MyName(ast.Name):
    pass


def odd_objects():
    print('-- odd objects')
    show('o1', cc(mod(Boom())))
    body = []
    body.append(ast.Expr(ast.Constant(1, **P), **P))
    show('o2', cc(ast.Module(Shrinking(body), [])))
    g = Grower(ast.Constant(1, **P), **P)
    del g.value
    g.items = [g]
    show('o3', cc(ast.Module(g.items, [])))
    ns = {'x': 4}
    show('o4', lambda: eval(compile(ast.Expression(MyName('x', ast.Load(), **P)), '<ast>', 'eval'), ns))
    a = ast.arguments()
    a.args = [ast.arg('q', **P)]
    show('o5', lambda: eval(compile(ast.Expression(ast.Lambda(a, ast.Name('q', ast.Load(), **P), **P)), '<ast>', 'eval'))(9))


def positions():
    print('-- positions')
    def L(n, line, col, end, endcol):
        n.lineno, n.col_offset, n.end_lineno, n.end_col_offset = line, col, end, endcol
        return n
    X = lambda: ast.Name('x', ast.Load())
    show('p1', cc(mod(L(ast.Expr(L(X(), 3, 5, 2, 0)), 1, 0, 3, 1))))
    show('p2', cc(mod(L(ast.Expr(L(X(), 2, 5, 2, 3)), 2, 0, 2, 9))))
    show('p3', cc(mod(L(ast.Expr(L(X(), -1, 0, 2, 3)), 2, 0, 2, 9))))
    show('p4', cc(mod(L(ast.Expr(L(X(), 1, -1, 1, 3)), 1, 0, 1, 9))))
    show('p5', cc(mod(L(ast.Pass(), 5, 0, 4, 1))))
    show('p6', cc(mod(ast.Pass(lineno=-1, col_offset=-1, end_lineno=-1, end_col_offset=-1))))
    show('p7', cc(mod(L(ast.Pass(), 0, 0, 0, 0))))
    def bad(m, node, *pos):
        ast.fix_missing_locations(m)
        L(node, *pos)
        return cc(m)
    p = ast.MatchAs()
    show('p8', bad(mod(ast.Match(X(), [ast.match_case(p, None, [ast.Pass()])])), p, 1, 3, 1, 2))
    a = ast.arg('a')
    show('p9', bad(mod(ast.FunctionDef('f', ast.arguments(args=[a]), [ast.Pass()])), a, 2, 1, 1, 0))
    h = ast.ExceptHandler(None, None, [ast.Pass()])
    show('p10', bad(mod(ast.Try([ast.Pass()], [h], [], [])), h, 4, 0, 3, 0))
    h = ast.ExceptHandler(X(), None, [ast.Pass()])
    show('p11', bad(mod(ast.TryStar([ast.Pass()], [h], [], [])), h, 4, 0, 3, 0))
    tv = ast.TypeVar('U')
    show('p12', bad(mod(ast.TypeAlias(ast.Name('T', ast.Store()), [tv], X())), tv, 3, 0, 2, 0))
    k = ast.keyword('k', X())
    show('p13', bad(mod(ast.Expr(ast.Call(X(), [], [k]))), k, 3, 0, 2, 0))
    al = ast.alias('os')
    show('p14', bad(mod(ast.Import([al])), al, 3, 0, 2, 0))

def checks():
    print('-- checks')
    X = lambda c=ast.Store(): ast.Name('x', c, **P)
    one = lambda: ast.Constant(1, **P)
    show('c1', cc(mod(ast.Expr(ast.NamedExpr(ast.Attribute(X(ast.Load()), 'a', ast.Store(), **P), one(), **P), **P))))
    show('c2', cc(mod(ast.TypeAlias(ast.Attribute(X(ast.Load()), 'a', ast.Store(), **P), [], one(), **P))))
    show('c3', cc(mod(ast.AnnAssign(ast.Attribute(X(ast.Load()), 'a', ast.Store(), **P), X(ast.Load()), None, 1, **P))))
    show('c4', cc(mod(ast.Expr(ast.Constant([1], **P), **P))))
    show('c5', cc(mod(ast.Expr(ast.Constant((1, [2]), **P), **P))))
    show('c6', cc(mod(ast.Expr(ast.Constant(type('I', (int,), {})(3), **P), **P))))
    show('c7', cc(mod(ast.Expr(ast.Name('None', ast.Load(), **P), **P))))
    show('c8', cc(mod(ast.Assign([one()], one(), **P))))
    show('c9', cc(mod(ast.Delete([one()], **P))))
    show('c10', cc(mod(ast.AugAssign(one(), ast.Add(), one(), **P))))
    show('c11', cc(mod(ast.For(one(), X(ast.Load()), [ast.Pass(**P)], [], **P))))
    show('c12', cc(mod(ast.With([ast.withitem(one(), one())], [ast.Pass(**P)], **P))))


def checks2():
    X = lambda c=ast.Store(): ast.Name('x', c, **P)
    one = lambda: ast.Constant(1, **P)
    show('c13', cc(mod(ast.Expr(ast.ListComp(one(), [ast.comprehension(one(), one(), [], 0)], **P), **P))))
    show('c14', cc(mod(ast.Expr(ast.Starred(X(ast.Store()), ast.Load(), **P), **P))))
    show('c15', cc(mod(ast.Assign([ast.Tuple([ast.Starred(X(ast.Load()), ast.Store(), **P)], ast.Store(), **P)], one(), **P))))
    show('c16', cc(mod(ast.FunctionDef('f', ast.arguments(), [ast.Pass(**P)], [], **P), ast.Expr(ast.Name('True', ast.Load(), **P), **P))))
    show('c17', cc(mod(ast.Assign([ast.Name('x', ast.Del(), **P)], one(), **P))))
    show('c18', cc(ast.Expression(ast.Name('x', ast.Del(), **P)), 'eval'))
    show('c19', cc(ast.Interactive([ast.Expr(ast.Name('x', ast.Store(), **P), **P)]), 'single'))
    show('c20', cc(mod(ast.Expr(ast.Yield(ast.Starred(X(ast.Load()), ast.Load(), **P), **P), **P))))
    show('c21', cc(mod(ast.Expr(ast.Call(ast.Name('f', ast.Load(), **P), [], [ast.keyword('a', X(), **P)], **P), **P))))
    show('c22', cc(mod(ast.Expr(ast.Constant(frozenset({1, (2, ...)}), **P), **P))))
    show('c23', cc(mod(ast.Expr(ast.Constant(frozenset({1, (2, frozenset({bytearray}))}), **P), **P))))
    show('c24', cc(mod(ast.Expr(ast.Constant(object(), **P), **P))))


class _CM:
    def __enter__(self):
        return self

    def __exit__(self, t, v, tb):
        if t is None:
            print('   ok')
            return False
        print('   ', t.__name__, v)
        return True


class _Sub:
    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


class Shim:
    def assertRaises(self, exc):
        return _CM()

    def subTest(self, *a, **k):
        return _Sub()

    def mod(self, mod, msg=None, mode="exec", *, exc=ValueError):
        mod.lineno = mod.col_offset = 0
        ast.fix_missing_locations(mod)
        try:
            compile(mod, "<test>", mode)
            print('   ok')
        except Exception as e:
            print('   ', type(e).__name__, e)

    def expr(self, node, msg=None, *, exc=ValueError):
        mod = ast.Module([ast.Expr(node)], [])
        self.mod(mod, msg, exc=exc)

    def stmt(self, stmt, msg=None):
        mod = ast.Module([stmt], [])
        self.mod(mod, msg)


class ValidatorTests(Shim):
    def test_module(self):
        m = ast.Interactive([ast.Expr(ast.Name("x", ast.Store()))])
        self.mod(m, "must have Load context", "single")
        m = ast.Expression(ast.Name("x", ast.Store()))
        self.mod(m, "must have Load context", "eval")

    def _check_arguments(self, fac, check):
        def arguments(args=None, posonlyargs=None, vararg=None,
                      kwonlyargs=None, kwarg=None,
                      defaults=None, kw_defaults=None):
            if args is None:
                args = []
            if posonlyargs is None:
                posonlyargs = []
            if kwonlyargs is None:
                kwonlyargs = []
            if defaults is None:
                defaults = []
            if kw_defaults is None:
                kw_defaults = []
            args = ast.arguments(args, posonlyargs, vararg, kwonlyargs,
                                 kw_defaults, kwarg, defaults)
            return fac(args)
        args = [ast.arg("x", ast.Name("x", ast.Store()))]
        check(arguments(args=args), "must have Load context")
        check(arguments(posonlyargs=args), "must have Load context")
        check(arguments(kwonlyargs=args), "must have Load context")
        check(arguments(defaults=[ast.Constant(3)]),
                       "more positional defaults than args")
        check(arguments(kw_defaults=[ast.Constant(4)]),
                       "length of kwonlyargs is not the same as kw_defaults")
        args = [ast.arg("x", ast.Name("x", ast.Load()))]
        check(arguments(args=args, defaults=[ast.Name("x", ast.Store())]),
                       "must have Load context")
        args = [ast.arg("a", ast.Name("x", ast.Load())),
                ast.arg("b", ast.Name("y", ast.Load()))]
        check(arguments(kwonlyargs=args,
                          kw_defaults=[None, ast.Name("x", ast.Store())]),
                          "must have Load context")

    def test_funcdef(self):
        a = ast.arguments([], [], None, [], [], None, [])
        f = ast.FunctionDef("x", a, [], [], None, None, [])
        self.stmt(f, "empty body on FunctionDef")
        f = ast.FunctionDef("x", a, [ast.Pass()], [ast.Name("x", ast.Store())], None, None, [])
        self.stmt(f, "must have Load context")
        f = ast.FunctionDef("x", a, [ast.Pass()], [],
                            ast.Name("x", ast.Store()), None, [])
        self.stmt(f, "must have Load context")
        f = ast.FunctionDef("x", ast.arguments(), [ast.Pass()])
        self.stmt(f)
        def fac(args):
            return ast.FunctionDef("x", args, [ast.Pass()], [], None, None, [])
        self._check_arguments(fac, self.stmt)


    def test_classdef(self):
        def cls(bases=None, keywords=None, body=None, decorator_list=None, type_params=None):
            if bases is None:
                bases = []
            if keywords is None:
                keywords = []
            if body is None:
                body = [ast.Pass()]
            if decorator_list is None:
                decorator_list = []
            if type_params is None:
                type_params = []
            return ast.ClassDef("myclass", bases, keywords,
                                body, decorator_list, type_params)
        self.stmt(cls(bases=[ast.Name("x", ast.Store())]),
                  "must have Load context")
        self.stmt(cls(keywords=[ast.keyword("x", ast.Name("x", ast.Store()))]),
                  "must have Load context")
        self.stmt(cls(body=[]), "empty body on ClassDef")
        self.stmt(cls(body=[None]), "None disallowed")
        self.stmt(cls(decorator_list=[ast.Name("x", ast.Store())]),
                  "must have Load context")

    def test_delete(self):
        self.stmt(ast.Delete([]), "empty targets on Delete")
        self.stmt(ast.Delete([None]), "None disallowed")
        self.stmt(ast.Delete([ast.Name("x", ast.Load())]),
                  "must have Del context")

    def test_assign(self):
        self.stmt(ast.Assign([], ast.Constant(3)), "empty targets on Assign")
        self.stmt(ast.Assign([None], ast.Constant(3)), "None disallowed")
        self.stmt(ast.Assign([ast.Name("x", ast.Load())], ast.Constant(3)),
                  "must have Store context")
        self.stmt(ast.Assign([ast.Name("x", ast.Store())],
                                ast.Name("y", ast.Store())),
                  "must have Load context")

    def test_augassign(self):
        aug = ast.AugAssign(ast.Name("x", ast.Load()), ast.Add(),
                            ast.Name("y", ast.Load()))
        self.stmt(aug, "must have Store context")
        aug = ast.AugAssign(ast.Name("x", ast.Store()), ast.Add(),
                            ast.Name("y", ast.Store()))
        self.stmt(aug, "must have Load context")

    def test_for(self):
        x = ast.Name("x", ast.Store())
        y = ast.Name("y", ast.Load())
        p = ast.Pass()
        self.stmt(ast.For(x, y, [], []), "empty body on For")
        self.stmt(ast.For(ast.Name("x", ast.Load()), y, [p], []),
                  "must have Store context")
        self.stmt(ast.For(x, ast.Name("y", ast.Store()), [p], []),
                  "must have Load context")
        e = ast.Expr(ast.Name("x", ast.Store()))
        self.stmt(ast.For(x, y, [e], []), "must have Load context")
        self.stmt(ast.For(x, y, [p], [e]), "must have Load context")

    def test_while(self):
        self.stmt(ast.While(ast.Constant(3), [], []), "empty body on While")
        self.stmt(ast.While(ast.Name("x", ast.Store()), [ast.Pass()], []),
                  "must have Load context")
        self.stmt(ast.While(ast.Constant(3), [ast.Pass()],
                             [ast.Expr(ast.Name("x", ast.Store()))]),
                             "must have Load context")

    def test_if(self):
        self.stmt(ast.If(ast.Constant(3), [], []), "empty body on If")
        i = ast.If(ast.Name("x", ast.Store()), [ast.Pass()], [])
        self.stmt(i, "must have Load context")
        i = ast.If(ast.Constant(3), [ast.Expr(ast.Name("x", ast.Store()))], [])
        self.stmt(i, "must have Load context")
        i = ast.If(ast.Constant(3), [ast.Pass()],
                   [ast.Expr(ast.Name("x", ast.Store()))])
        self.stmt(i, "must have Load context")

    def test_with(self):
        p = ast.Pass()
        self.stmt(ast.With([], [p]), "empty items on With")
        i = ast.withitem(ast.Constant(3), None)
        self.stmt(ast.With([i], []), "empty body on With")
        i = ast.withitem(ast.Name("x", ast.Store()), None)
        self.stmt(ast.With([i], [p]), "must have Load context")
        i = ast.withitem(ast.Constant(3), ast.Name("x", ast.Load()))
        self.stmt(ast.With([i], [p]), "must have Store context")

    def test_raise(self):
        r = ast.Raise(None, ast.Constant(3))
        self.stmt(r, "Raise with cause but no exception")
        r = ast.Raise(ast.Name("x", ast.Store()), None)
        self.stmt(r, "must have Load context")
        r = ast.Raise(ast.Constant(4), ast.Name("x", ast.Store()))
        self.stmt(r, "must have Load context")

    def test_try(self):
        p = ast.Pass()
        t = ast.Try([], [], [], [p])
        self.stmt(t, "empty body on Try")
        t = ast.Try([ast.Expr(ast.Name("x", ast.Store()))], [], [], [p])
        self.stmt(t, "must have Load context")
        t = ast.Try([p], [], [], [])
        self.stmt(t, "Try has neither except handlers nor finalbody")
        t = ast.Try([p], [], [p], [p])
        self.stmt(t, "Try has orelse but no except handlers")
        t = ast.Try([p], [ast.ExceptHandler(None, "x", [])], [], [])
        self.stmt(t, "empty body on ExceptHandler")
        e = [ast.ExceptHandler(ast.Name("x", ast.Store()), "y", [p])]
        self.stmt(ast.Try([p], e, [], []), "must have Load context")
        e = [ast.ExceptHandler(None, "x", [p])]
        t = ast.Try([p], e, [ast.Expr(ast.Name("x", ast.Store()))], [p])
        self.stmt(t, "must have Load context")
        t = ast.Try([p], e, [p], [ast.Expr(ast.Name("x", ast.Store()))])
        self.stmt(t, "must have Load context")

    def test_try_star(self):
        p = ast.Pass()
        t = ast.TryStar([], [], [], [p])
        self.stmt(t, "empty body on TryStar")
        t = ast.TryStar([ast.Expr(ast.Name("x", ast.Store()))], [], [], [p])
        self.stmt(t, "must have Load context")
        t = ast.TryStar([p], [], [], [])
        self.stmt(t, "TryStar has neither except handlers nor finalbody")
        t = ast.TryStar([p], [], [p], [p])
        self.stmt(t, "TryStar has orelse but no except handlers")
        t = ast.TryStar([p], [ast.ExceptHandler(None, "x", [])], [], [])
        self.stmt(t, "empty body on ExceptHandler")
        e = [ast.ExceptHandler(ast.Name("x", ast.Store()), "y", [p])]
        self.stmt(ast.TryStar([p], e, [], []), "must have Load context")
        e = [ast.ExceptHandler(None, "x", [p])]
        t = ast.TryStar([p], e, [ast.Expr(ast.Name("x", ast.Store()))], [p])
        self.stmt(t, "must have Load context")
        t = ast.TryStar([p], e, [p], [ast.Expr(ast.Name("x", ast.Store()))])
        self.stmt(t, "must have Load context")

    def test_assert(self):
        self.stmt(ast.Assert(ast.Name("x", ast.Store()), None),
                  "must have Load context")
        assrt = ast.Assert(ast.Name("x", ast.Load()),
                           ast.Name("y", ast.Store()))
        self.stmt(assrt, "must have Load context")

    def test_import(self):
        self.stmt(ast.Import([]), "empty names on Import")

    def test_importfrom(self):
        imp = ast.ImportFrom(None, [ast.alias("x", None)], -42)
        self.stmt(imp, "Negative ImportFrom level")
        self.stmt(ast.ImportFrom(None, [], 0), "empty names on ImportFrom")

    def test_global(self):
        self.stmt(ast.Global([]), "empty names on Global")

    def test_nonlocal(self):
        self.stmt(ast.Nonlocal([]), "empty names on Nonlocal")

    def test_expr(self):
        e = ast.Expr(ast.Name("x", ast.Store()))
        self.stmt(e, "must have Load context")

    def test_boolop(self):
        b = ast.BoolOp(ast.And(), [])
        self.expr(b, "less than 2 values")
        b = ast.BoolOp(ast.And(), [ast.Constant(3)])
        self.expr(b, "less than 2 values")
        b = ast.BoolOp(ast.And(), [ast.Constant(4), None])
        self.expr(b, "None disallowed")
        b = ast.BoolOp(ast.And(), [ast.Constant(4), ast.Name("x", ast.Store())])
        self.expr(b, "must have Load context")

    def test_unaryop(self):
        u = ast.UnaryOp(ast.Not(), ast.Name("x", ast.Store()))
        self.expr(u, "must have Load context")

    def test_lambda(self):
        a = ast.arguments([], [], None, [], [], None, [])
        self.expr(ast.Lambda(a, ast.Name("x", ast.Store())),
                  "must have Load context")
        def fac(args):
            return ast.Lambda(args, ast.Name("x", ast.Load()))
        self._check_arguments(fac, self.expr)

    def test_ifexp(self):
        l = ast.Name("x", ast.Load())
        s = ast.Name("y", ast.Store())
        for args in (s, l, l), (l, s, l), (l, l, s):
            self.expr(ast.IfExp(*args), "must have Load context")

    def test_dict(self):
        d = ast.Dict([], [ast.Name("x", ast.Load())])
        self.expr(d, "same number of keys as values")
        d = ast.Dict([ast.Name("x", ast.Load())], [None])
        self.expr(d, "None disallowed")

    def test_set(self):
        self.expr(ast.Set([None]), "None disallowed")
        s = ast.Set([ast.Name("x", ast.Store())])
        self.expr(s, "must have Load context")

    def _check_comprehension(self, fac):
        self.expr(fac([]), "comprehension with no generators")
        g = ast.comprehension(ast.Name("x", ast.Load()),
                              ast.Name("x", ast.Load()), [], 0)
        self.expr(fac([g]), "must have Store context")
        g = ast.comprehension(ast.Name("x", ast.Store()),
                              ast.Name("x", ast.Store()), [], 0)
        self.expr(fac([g]), "must have Load context")
        x = ast.Name("x", ast.Store())
        y = ast.Name("y", ast.Load())
        g = ast.comprehension(x, y, [None], 0)
        self.expr(fac([g]), "None disallowed")
        g = ast.comprehension(x, y, [ast.Name("x", ast.Store())], 0)
        self.expr(fac([g]), "must have Load context")

    def _simple_comp(self, fac):
        g = ast.comprehension(ast.Name("x", ast.Store()),
                              ast.Name("x", ast.Load()), [], 0)
        self.expr(fac(ast.Name("x", ast.Store()), [g]),
                  "must have Load context")
        def wrap(gens):
            return fac(ast.Name("x", ast.Store()), gens)
        self._check_comprehension(wrap)

    def test_listcomp(self):
        self._simple_comp(ast.ListComp)

    def test_setcomp(self):
        self._simple_comp(ast.SetComp)

    def test_generatorexp(self):
        self._simple_comp(ast.GeneratorExp)

    def test_dictcomp(self):
        g = ast.comprehension(ast.Name("y", ast.Store()),
                              ast.Name("p", ast.Load()), [], 0)
        c = ast.DictComp(ast.Name("x", ast.Store()),
                         ast.Name("y", ast.Load()), [g])
        self.expr(c, "must have Load context")
        c = ast.DictComp(ast.Name("x", ast.Load()),
                         ast.Name("y", ast.Store()), [g])
        self.expr(c, "must have Load context")
        def factory(comps):
            k = ast.Name("x", ast.Load())
            v = ast.Name("y", ast.Load())
            return ast.DictComp(k, v, comps)
        self._check_comprehension(factory)

    def test_yield(self):
        self.expr(ast.Yield(ast.Name("x", ast.Store())), "must have Load")
        self.expr(ast.YieldFrom(ast.Name("x", ast.Store())), "must have Load")

    def test_compare(self):
        left = ast.Name("x", ast.Load())
        comp = ast.Compare(left, [ast.In()], [])
        self.expr(comp, "no comparators")
        comp = ast.Compare(left, [ast.In()], [ast.Constant(4), ast.Constant(5)])
        self.expr(comp, "different number of comparators and operands")
        comp = ast.Compare(ast.Constant("blah"), [ast.In()], [left])
        self.expr(comp)
        comp = ast.Compare(left, [ast.In()], [ast.Constant("blah")])
        self.expr(comp)

    def test_call(self):
        func = ast.Name("x", ast.Load())
        args = [ast.Name("y", ast.Load())]
        keywords = [ast.keyword("w", ast.Name("z", ast.Load()))]
        call = ast.Call(ast.Name("x", ast.Store()), args, keywords)
        self.expr(call, "must have Load context")
        call = ast.Call(func, [None], keywords)
        self.expr(call, "None disallowed")
        bad_keywords = [ast.keyword("w", ast.Name("z", ast.Store()))]
        call = ast.Call(func, args, bad_keywords)
        self.expr(call, "must have Load context")

    def test_attribute(self):
        attr = ast.Attribute(ast.Name("x", ast.Store()), "y", ast.Load())
        self.expr(attr, "must have Load context")

    def test_subscript(self):
        sub = ast.Subscript(ast.Name("x", ast.Store()), ast.Constant(3),
                            ast.Load())
        self.expr(sub, "must have Load context")
        x = ast.Name("x", ast.Load())
        sub = ast.Subscript(x, ast.Name("y", ast.Store()),
                            ast.Load())
        self.expr(sub, "must have Load context")
        s = ast.Name("x", ast.Store())
        for args in (s, None, None), (None, s, None), (None, None, s):
            sl = ast.Slice(*args)
            self.expr(ast.Subscript(x, sl, ast.Load()),
                      "must have Load context")
        sl = ast.Tuple([], ast.Load())
        self.expr(ast.Subscript(x, sl, ast.Load()))
        sl = ast.Tuple([s], ast.Load())
        self.expr(ast.Subscript(x, sl, ast.Load()), "must have Load context")

    def test_starred(self):
        left = ast.List([ast.Starred(ast.Name("x", ast.Load()), ast.Store())],
                        ast.Store())
        assign = ast.Assign([left], ast.Constant(4))
        self.stmt(assign, "must have Store context")

    def _sequence(self, fac):
        self.expr(fac([None], ast.Load()), "None disallowed")
        self.expr(fac([ast.Name("x", ast.Store())], ast.Load()),
                  "must have Load context")

    def test_list(self):
        self._sequence(ast.List)

    def test_tuple(self):
        self._sequence(ast.Tuple)

    def test_match_validation_pattern(self):
        name_x = ast.Name('x', ast.Load())
        for pattern in match_patterns():
            node = ast.Match(subject=name_x, cases=[ast.match_case(pattern=pattern, body=[ast.Pass()])])
            node = ast.fix_missing_locations(node)
            module = ast.Module([node], [])
            with self.assertRaises(ValueError):
                compile(module, "<test>", "exec")


def validation():
    print('-- validation')
    t = ValidatorTests()
    for name in sorted(n for n in dir(t) if n.startswith('test_')):
        print(name)
        getattr(t, name)()


def match_patterns():
    constant_1 = ast.Constant(1)
    pattern_1 = ast.MatchValue(constant_1)

    constant_x = ast.Constant('x')
    pattern_x = ast.MatchValue(constant_x)

    constant_true = ast.Constant(True)
    pattern_true = ast.MatchSingleton(True)

    name_carter = ast.Name('carter', ast.Load())

    return [
        ast.MatchValue(
            ast.Attribute(
                ast.Attribute(
                    ast.Name('x', ast.Store()),
                    'y', ast.Load()
                ),
                'z', ast.Load()
            )
        ),
        ast.MatchValue(
            ast.Attribute(
                ast.Attribute(
                    ast.Name('x', ast.Load()),
                    'y', ast.Store()
                ),
                'z', ast.Load()
            )
        ),
        ast.MatchValue(
            ast.Constant(...)
        ),
        ast.MatchValue(
            ast.Constant(True)
        ),
        ast.MatchValue(
            ast.Constant((1,2,3))
        ),
        ast.MatchSingleton('string'),
        ast.MatchSequence([
          ast.MatchSingleton('string')
        ]),
        ast.MatchSequence(
            [
                ast.MatchSequence(
                    [
                        ast.MatchSingleton('string')
                    ]
                )
            ]
        ),
        ast.MatchMapping(
            [constant_1, constant_true],
            [pattern_x]
        ),
        ast.MatchMapping(
            [constant_true, constant_1],
            [pattern_x, pattern_1],
            rest='True'
        ),
        ast.MatchMapping(
            [constant_true, ast.Starred(ast.Name('lol', ast.Load()), ast.Load())],
            [pattern_x, pattern_1],
            rest='legit'
        ),
        ast.MatchClass(
            ast.Attribute(
                ast.Attribute(
                    constant_x,
                    'y', ast.Load()),
                'z', ast.Load()),
            patterns=[], kwd_attrs=[], kwd_patterns=[]
        ),
        ast.MatchClass(
            name_carter,
            patterns=[],
            kwd_attrs=['True'],
            kwd_patterns=[pattern_1]
        ),
        ast.MatchClass(
            name_carter,
            patterns=[],
            kwd_attrs=[],
            kwd_patterns=[pattern_1]
        ),
        ast.MatchClass(
            name_carter,
            patterns=[ast.MatchSingleton('string')],
            kwd_attrs=[],
            kwd_patterns=[]
        ),
        ast.MatchClass(
            name_carter,
            patterns=[ast.MatchStar()],
            kwd_attrs=[],
            kwd_patterns=[]
        ),
        ast.MatchClass(
            name_carter,
            patterns=[],
            kwd_attrs=[],
            kwd_patterns=[ast.MatchStar()]
        ),
        ast.MatchClass(
            constant_true,  # invalid name
            patterns=[],
            kwd_attrs=['True'],
            kwd_patterns=[pattern_1]
        ),
        ast.MatchSequence(
            [
                ast.MatchStar("True")
            ]
        ),
        ast.MatchAs(
            name='False'
        ),
        ast.MatchOr(
            []
        ),
        ast.MatchOr(
            [pattern_1]
        ),
        ast.MatchOr(
            [pattern_1, pattern_x, ast.MatchSingleton('xxx')]
        ),
        ast.MatchAs(name="_"),
        ast.MatchStar(name="x"),
        ast.MatchSequence([ast.MatchStar("_")]),
        ast.MatchMapping([], [], rest="_"),
    ]


COMPILER_ERRORS = [
    "return 1",
    "x = 1\nreturn",
    "def f():\n    nonlocal x",
    "nonlocal x",
    "break",
    "continue",
    "def f(a, a): pass",
    "f(a=1, a=2)",
    "await x",
    "def f():\n    await x",
    "yield 1",
    "x = [(yield) for y in z]",
    "async def f():\n    yield from x",
    "async def f():\n    yield 1\n    return 2",
    "from __future__ import braces",
    "'doc'\nfrom __future__ import spam",
    "x = 1\nfrom __future__ import annotations",
    "try:\n    pass\nexcept:\n    pass\nexcept ValueError:\n    pass",
    "def f():\n    x = 1\n    global x",
    "async with a: pass",
    "async for a in b: pass",
    "match x:\n    case a:\n        pass\n    case b:\n        pass",
    "match x:\n    case [a, a]:\n        pass",
    "match x:\n    case {1: a, 1: b}:\n        pass",
    "match x:\n    case a | [b]:\n        pass",
    "class C[T, T]: pass",
    "def f[T = int, U](): pass",
]


def compiler_errors():
    print('-- compiler errors')
    for s in COMPILER_ERRORS:
        label = s.split('\n')[-1][:28]
        show('src  ' + label, lambda: compile(s, '<ast>', 'exec'))
        try:
            tree = ast.parse(s)
        except SyntaxError as e:
            print('parse', e.msg)
            continue
        show('tree ' + label, lambda: compile(tree, '<ast>', 'exec'))


def hand_errors():
    print('-- hand-made trees the compiler refuses')
    X = lambda c=ast.Store(): ast.Name('x', c, **P)
    one = lambda: ast.Constant(1, **P)
    star = lambda: ast.Starred(X(), ast.Store(), **P)
    show('h1', cc(mod(ast.Assign([ast.Tuple([star(), star()], ast.Store(), **P)], one(), **P))))
    show('h2', cc(mod(ast.Assign([star()], one(), **P))))
    show('h3', cc(mod(ast.Expr(ast.Starred(X(ast.Load()), ast.Load(), **P), **P))))
    show('h4', cc(mod(ast.For(ast.List([star(), star()], ast.Store(), **P), X(ast.Load()), [ast.Pass(**P)], [], **P))))
    show('h5', cc(mod(ast.Assign([ast.Tuple([X()] * 256 + [star()], ast.Store(), **P)], one(), **P))))
    show('h6', cc(mod(ast.AugAssign(ast.Tuple([X()], ast.Store(), **P), ast.Add(), one(), **P))))
    show('h7', cc(mod(ast.AugAssign(ast.List([X()], ast.Store(), **P), ast.Add(), one(), **P))))
    show('h8', cc(mod(ast.AugAssign(star(), ast.Add(), one(), **P))))
    show('h9', cc(mod(ast.Delete([ast.Tuple([ast.Starred(ast.Name('b', ast.Del(), **P), ast.Del(), **P)], ast.Del(), **P)], **P))))
    show('h10', cc(ast.Expression(ast.JoinedStr([ast.FormattedValue(one(), 120, None, **P)], **P)), 'eval'))
    show('h11', cc(ast.Expression(ast.TemplateStr([ast.Interpolation(one(), 'x', 99, None, **P)], **P)), 'eval'))
    show('h11b', cc(ast.Expression(ast.TemplateStr([ast.Interpolation(one(), 'x', -5, None, **P)], **P)), 'eval'))
    show('h11c', cc(ast.Expression(ast.FormattedValue(one(), -5, None, **P)), 'eval'))
    case = lambda p: ast.match_case(p, None, [ast.Pass(**P)])
    show('h12', cc(mod(ast.Match(X(ast.Load()), [case(ast.MatchValue(ast.JoinedStr([ast.Constant('a', **P)], **P), **P))], **P))))
    show('h13', cc(mod(ast.Match(X(ast.Load()), [case(ast.MatchMapping([ast.JoinedStr([], **P)], [ast.MatchAs(**P)], **P))], **P))))
    show('h14', cc(mod(ast.TryStar([ast.Pass(**P)], [ast.ExceptHandler(None, None, [ast.Pass(**P)], **P)], [], [], **P))))


def run(*body, ns=None):
    ns = {} if ns is None else ns
    exec(compile(ast.fix_missing_locations(ast.Module(list(body), [])), '<run>', 'exec'), ns)
    return ns.get('r')


def N(i, c=None):
    return ast.Name(i, c or ast.Load())


def C(v):
    return ast.Constant(v)


def R(v):
    return ast.Assign([N('r', ast.Store())], v)


def odd_but_valid():
    print('-- trees no parser makes')
    x5 = ast.Assign([N('x', ast.Store())], C(5))
    show('q1', lambda: run(R(ast.JoinedStr([C(1), C('a')]))))
    show('q2', lambda: run(R(ast.TemplateStr([C('a'), C('b')]))))
    show('q3', lambda: run(x5, R(ast.TemplateStr([N('x'), ast.Interpolation(N('x'), 'x', -1)]))))
    t = lambda: run(x5, R(ast.TemplateStr([ast.Interpolation(N('x'), 'x', 114), ast.Interpolation(N('x'), 'y', -1, ast.JoinedStr([C('>4')]))])))
    show('q4', lambda: (t().strings, t().values, [i.conversion for i in t().interpolations]))
    show('q5', lambda: run(R(ast.JoinedStr([ast.FormattedValue(C(3), -1, C(5))]))))
    show('q6', lambda: run(R(ast.TemplateStr([ast.Interpolation(C(3), 'x', -1, C(5))]))))
    show('q7', lambda: run(ast.Try([ast.Raise(ast.Call(N('ValueError'), [C(3)], []))], [ast.ExceptHandler(None, 'e', [R(N('e'))])], [], [])))
    show('q8', lambda: run(ast.ImportFrom(None, [ast.alias('x')], 0)))
    show('q9', lambda: run(R(ast.Call(ast.Lambda(ast.arguments(args=[ast.arg('x', N('int'))]), N('x')), [C(4)], []))))
    show('q10', lambda: run(ast.Assign([N('a', ast.Store()), N('b', ast.Store())], C(1)), ast.Delete([ast.Tuple([N('a', ast.Del()), ast.List([N('b', ast.Del())], ast.Del())], ast.Del())]), R(C('deleted'))))
    show('q11', lambda: run(ast.AnnAssign(N('z', ast.Store()), N('int'), C(1), 0), R(ast.Call(N('globals'), [], []))).get('__annotations__', 'none'))
    show('q12', lambda: run(ast.Assign([ast.Tuple([ast.Starred(N('a', ast.Store()), ast.Store())], ast.Store())], ast.List([C(1), C(2)], ast.Load())), R(N('a'))))
    show('q13', lambda: run(R(ast.Subscript(ast.Dict([C('a')], [C(1)]), ast.Tuple([ast.Starred(ast.List([C('a')], ast.Load()), ast.Load())], ast.Load()), ast.Load()))))
    show('q14', lambda: run(ast.For(ast.Attribute(N('o'), 'a', ast.Store()), ast.List([C(1)], ast.Load()), [ast.Pass()], []), R(ast.Attribute(N('o'), 'a', ast.Load())), ns={'o': type('O', (), {})()}))
    show('q15', lambda: run(R(ast.Constant(-0.0))))
    show('q16', lambda: run(ast.Match(C(5), [ast.match_case(ast.MatchValue(ast.UnaryOp(ast.USub(), C(5))), None, [R(C('neg'))]), ast.match_case(ast.MatchValue(C(5)), None, [R(C('five'))])])))
    show('q17', lambda: run(ast.Match(C(1+2j), [ast.match_case(ast.MatchValue(ast.BinOp(ast.UnaryOp(ast.USub(), C(-1)), ast.Add(), C(2j))), None, [R(C('cx'))])])))
    show('q18', lambda: run(ast.Match(C(None), [ast.match_case(ast.MatchMapping([C(None)], [ast.MatchAs()], None), None, [R(C('m'))]), ast.match_case(ast.MatchSingleton(None), None, [R(C('none'))])])))
    show('q19', lambda: run(ast.Match(ast.Dict([C(True)], [C(1)]), [ast.match_case(ast.MatchMapping([C(True)], [ast.MatchValue(C(1))], 'rest'), None, [R(N('rest'))])])))
    show('q20', lambda: run(R(ast.Call(N('int'), [], [ast.keyword(None, ast.Dict([C('base')], [C(2)])), ast.keyword('x', C('101'))]))))
    show('q21', lambda: run(ast.Expr(ast.Yield(None))))
    show('q22', lambda: run(ast.Expr(ast.ListComp(N('i'), [ast.comprehension(N('i', ast.Store()), N('x'), [], 1)]))))
    show('q23', lambda: run(R(ast.Compare(C(1), [ast.Lt(), ast.Lt()], [C(2), C(3)]))))
    show('q24', lambda: run(R(ast.Dict([None, C('b')], [ast.Dict([C('a')], [C(1)]), C(2)]))))
    show('q25', lambda: run(R(ast.Slice(C(1), None, None))))
    show('q26', lambda: run(R(N('__debug__'))))
    show('q27', lambda: run(R(ast.Constant('u', kind='u'))))
    show('q28', lambda: run(ast.Global(['g']), ast.Assign([N('g', ast.Store())], C(3)), R(N('g'))))


SOURCES = [
    "x = 1 + 2 * 3\nprint(x, -x, ~x, not x, x ** 2, x // 2, x % 4, x / 2, x << 1, x >> 1, x & 3, x | 8, x ^ 5)",
    "a, *b, c = range(6)\nprint(a, b, c)\n[d, e] = 'xy'\nprint(d, e)\n(f, (g, h)) = 1, (2, 3)\nprint(f, g, h)",
    "def f(a, b=2, /, c=3, *args, d, e=5, **kw):\n    return a, b, c, args, d, e, kw\nprint(f(1, d=4))\nprint(f(1, 2, 3, 4, 5, d=6, z=7))",
    "def outer():\n    n = 0\n    def inc():\n        nonlocal n\n        n += 1\n        return n\n    return inc\ni = outer()\ni(); print(i())",
    "g = 10\ndef h():\n    global g\n    g = 20\nh(); print(g)",
    "class A:\n    '''doc'''\n    x = 1\n    def m(self):\n        return __class__.__name__, self.x\n    @property\n    def p(self):\n        return 2\nprint(A().m(), A().p, A.__doc__, A.__qualname__)",
    "class B(dict, metaclass=type):\n    def __init_subclass__(cls, **kw):\n        print('sub', kw)\nclass C(B, k=1): pass\nprint(C.__mro__[1].__name__)",
    "def deco(f):\n    print('deco', f.__name__)\n    return f\n@deco\n@deco\ndef w(): pass\nprint(w.__code__.co_firstlineno)\n@deco\nclass K: pass\nprint(K.__firstlineno__)",
    "def gen():\n    x = yield 1\n    print('got', x)\n    yield from range(2)\n    return 5\ng = gen()\nprint(next(g), g.send('s'), next(g))\ntry:\n    next(g)\nexcept StopIteration as e:\n    print('stop', e.value)",
    "async def co(n):\n    return n * 2\nasync def main():\n    r = [await co(i) for i in range(3)]\n    async def agen():\n        yield 1\n        yield 2\n    r2 = [x async for x in agen()]\n    return r, r2\nc = main()\ntry:\n    c.send(None)\nexcept StopIteration as e:\n    print(e.value)",
    "print([x * y for x in range(3) for y in range(3) if x != y], {x: x * x for x in range(3)}, {x % 2 for x in range(5)}, list(x + 1 for x in range(3)))",
    "def m(v):\n    match v:\n        case 0 | 1:\n            return 'small'\n        case [a, *rest]:\n            return ('seq', a, rest)\n        case {'k': val, **others}:\n            return ('map', val, others)\n        case complex(real=r) if r > 0:\n            return ('cx', r)\n        case str() as s:\n            return ('str', s)\n        case -1:\n            return 'neg'\n        case 1+2j:\n            return 'lit'\n        case None:\n            return 'none'\n        case _:\n            return 'other'\nfor v in [0, [1, 2, 3], {'k': 1, 'z': 2}, 3+4j, 'hi', -1, 1+2j, None, 2.5]:\n    print(m(v))",
    "try:\n    1 / 0\nexcept ZeroDivisionError as e:\n    print('zde', e)\nelse:\n    print('no')\nfinally:\n    print('fin')\ntry:\n    raise ExceptionGroup('g', [ValueError(1), TypeError(2)])\nexcept* ValueError as eg:\n    print('v', eg.exceptions)\nexcept* TypeError as eg:\n    print('t', eg.exceptions)",
    "class CM:\n    def __enter__(self):\n        print('enter'); return self\n    def __exit__(self, *a):\n        print('exit', a[0]); return True\nwith CM() as c, CM():\n    raise KeyError(1)\nprint('after')",
    "x = 42\nname = 'n'\nprint(f'{x}', f'{x!r:>6}', f'{x=}', f'{x:{4}d}', f'{name!a}', f'{x:#x}', f'a{x}b{x+1}c', f'{3.14159:.2f}', f'{\"q\"!s}')",
    "x = 5\nt = t'a{x!r:>3}b{x + 1}'\nprint(type(t).__name__, t.strings, [(i.value, i.expression, i.conversion, i.format_spec) for i in t.interpolations])",
    "def first[T](xs: list[T]) -> T:\n    return xs[0]\nclass Box[T: int, *Ts, **P]:\n    pass\ntype Alias[K] = dict[K, int]\nprint(first([3]), Box.__type_params__, Alias.__value__, Alias.__type_params__)",
    "f = lambda a, b=2, *c, d=4, **e: (a, b, c, d, e)\nprint(f(1), (lambda: 7)())",
    "if (n := 10) > 5:\n    print(n)\nprint([y := 1, y + 1])",
    "x = [1, 2, 3, 4, 5]\nprint(x[1:3], x[::2], x[::-1], x[-1])\nx[1:2] = [9, 9]\ndel x[0]\nprint(x)\nd = {'a': 1}\ndel d['a']\nprint(d)",
    "x = 5\nx += 1; x -= 2; x *= 3; x //= 2; x **= 2; x %= 7; x <<= 2; x >>= 1; x |= 8; x &= 12; x ^= 1\nprint(x)\nclass O: pass\no = O(); o.a = 1; o.a += 2; print(o.a)\nl = [1]; l[0] += 5; print(l)",
    "print(1 < 2 < 3, 1 < 3 < 2, None is not None, 'a' in 'abc', 2 not in [1], 1 == 1.0 != 2)",
    "assert True, 'no'\ntry:\n    assert 1 == 2, 'msg'\nexcept AssertionError as e:\n    print('assert', e)",
    "import math\nfrom collections import OrderedDict, deque\nprint(math.pi > 3, deque([1]), OrderedDict(a=1))",
    "x: int = 5\ny: 'str'\nclass K:\n    a: int\n    b: list[int] = []\nprint(K.__annotations__)",
    "from __future__ import annotations\ndef f(a: undefined_name) -> List[int]:\n    pass\nprint(f.__annotations__)",
    "def f(*args, **kwargs):\n    return args, kwargs\nprint(f(*[1, 2], *(3,), **{'a': 1}, **{'b': 2}))\nprint([*range(3), *'ab'], {**{'x': 1}, 'y': 2}, {*[1, 2], 3})",
    "i = 0\nwhile True:\n    i += 1\n    if i < 3:\n        continue\n    if i > 5:\n        break\nelse:\n    print('never')\nprint(i)\nfor j in range(3):\n    pass\nelse:\n    print('for else', j)",
    "def r():\n    try:\n        return 'try'\n    finally:\n        print('finally runs')\nprint(r())",
    "x = 1 if True else 2\nprint(x, (lambda: (yield))().__class__.__name__)",
    "print(b'abc' + b'\\x00', 'u' 'v', u'w', r'\\n', 0x1f, 0o17, 0b101, 1_000, 1e3, 2j, ..., None, True)",
    "class Meta(type):\n    def __new__(m, n, b, d):\n        d['made'] = True\n        return super().__new__(m, n, b, d)\nclass M(metaclass=Meta): pass\nprint(M.made)",
    "def f():\n    x = 1\n    def g():\n        return x\n    return g\nprint(f()(), f().__code__.co_freevars)",
    "e = None\ntry:\n    try:\n        raise ValueError('a')\n    except ValueError as v:\n        raise TypeError('b') from v\nexcept TypeError as t:\n    e = t\nprint(repr(e), repr(e.__cause__))\ntry:\n    raise\nexcept RuntimeError as r:\n    print('bare raise', r)",
    "def f(x):\n    '''Docstring.'''\n    return x\nprint(f.__doc__, f.__name__, f.__qualname__, f.__code__.co_varnames, f.__code__.co_argcount)",
    "print(__name__, __debug__)\nclass P:\n    __slots__ = ('a',)\n    def __init__(self):\n        self.__private = 1\nprint(P.__slots__, P.__static_attributes__)",
    "é = 'ü'\ndef ñ(ß='∂'):\n    return é + ß\nprint(ñ(), f'{é!a}')",
    "class Mat:\n    def __matmul__(self, o):\n        return 'matmul'\nm = Mat()\nm2 = m\nm2 @= m\nprint(Mat() @ Mat(), m2, +5, 1 <= 2 >= 1, (0 and 1) or 2, not 0, 3 if 0 else 4)",
    "class ACM:\n    async def __aenter__(self):\n        return 'in'\n    async def __aexit__(self, *a):\n        return False\nclass AIt:\n    def __init__(self):\n        self.n = 0\n    def __aiter__(self):\n        return self\n    async def __anext__(self):\n        self.n += 1\n        if self.n > 2:\n            raise StopAsyncIteration\n        return self.n\nasync def am():\n    out = []\n    async with ACM() as v:\n        out.append(v)\n    async for i in AIt():\n        out.append(i)\n    else:\n        out.append('else')\n    return out\nc = am()\ntry:\n    c.send(None)\nexcept StopIteration as e:\n    print(e.value)",
]


def capture(code):
    out = io.StringIO()
    old = sys.stdout
    sys.stdout = out
    try:
        exec(code, {'__name__': '__main__'})
    except Exception as e:
        print('!!', type(e).__name__, e)
    finally:
        sys.stdout = old
    return out.getvalue()


def round_trips():
    print('-- round trips')
    for i, src in enumerate(SOURCES):
        a = capture(compile(src, '<src>', 'exec'))
        b = capture(compile(ast.parse(src), '<tree>', 'exec'))
        print(i, 'same' if a == b else 'DIFFERENT')
        print(b, end='')


def last_frame(e):
    tb = e.__traceback__
    while tb.tb_next:
        tb = tb.tb_next
    return tb


def line_numbers():
    print('-- line numbers')
    src = "def f(a):\n    x = a + 1\n    return 1 / a\n\nclass C:\n    def m(self):\n        raise ValueError('m')\n"
    t = ast.parse(src)
    ast.increment_lineno(t, 10)
    ns = {}
    exec(compile(t, 'inc.py', 'exec'), ns)
    print(ns['f'].__code__.co_firstlineno, ns['C'].m.__code__.co_firstlineno, ns['C'].__firstlineno__, ns['f'].__code__.co_filename)
    for call in (lambda: ns['f'](0), lambda: ns['C']().m()):
        try:
            call()
        except Exception as e:
            tb = last_frame(e)
            print(type(e).__name__, tb.tb_lineno, tb.tb_frame.f_code.co_filename, tb.tb_frame.f_code.co_name)
    t = ast.parse("x = 1\ny = x / 0\n")
    ast.increment_lineno(t.body[1], 38)
    try:
        exec(compile(t, 'moved.py', 'exec'), {})
    except ZeroDivisionError as e:
        print('moved to', last_frame(e).tb_lineno)
    t = ast.parse("def g():\n    return h()\n")
    ast.increment_lineno(t, 100)
    ns = {'h': lambda: 1 / 0}
    exec(compile(t, 'g.py', 'exec'), ns)
    try:
        ns['g']()
    except ZeroDivisionError as e:
        tb = e.__traceback__.tb_next
        print('g at', tb.tb_lineno, tb.tb_frame.f_code.co_firstlineno)


def handmade():
    print('-- hand-made trees')
    f = ast.FunctionDef(
        name='add',
        args=ast.arguments(posonlyargs=[], args=[ast.arg('a'), ast.arg('b')], kwonlyargs=[], kw_defaults=[], defaults=[ast.Constant(10)]),
        body=[ast.Return(ast.BinOp(ast.Name('a', ast.Load()), ast.Add(), ast.Name('b', ast.Load())))],
        decorator_list=[], returns=None, type_params=[])
    m = ast.Module([f, ast.Expr(ast.Call(ast.Name('print', ast.Load()), [ast.Call(ast.Name('add', ast.Load()), [ast.Constant(5)], [])], []))], [])
    ast.fix_missing_locations(m)
    print(ast.dump(m.body[0], include_attributes=True)[:120])
    ns = {}
    exec(compile(m, '<hand>', 'exec'), ns)
    print(ns['add'](1, 2), ns['add'].__code__.co_firstlineno, ns['add'].__defaults__, ns['add'].__name__)
    e = ast.Expression(ast.BinOp(ast.Constant(6), ast.Mult(), ast.Constant(7)))
    print(eval(compile(ast.fix_missing_locations(e), '<e>', 'eval')))
    x = ast.Expression(ast.ListComp(ast.BinOp(ast.Name('i', ast.Load()), ast.Pow(), ast.Constant(2)),
                                    [ast.comprehension(ast.Name('i', ast.Store()), ast.Call(ast.Name('range', ast.Load()), [ast.Constant(4)], []), [], 0)]))
    print(eval(compile(ast.fix_missing_locations(x), '<e>', 'eval')))
    try:
        exec(compile(ast.fix_missing_locations(mod(ast.Expr(ast.BinOp(ast.Constant(1), ast.Div(), ast.Constant(0))))), '<z>', 'exec'))
    except ZeroDivisionError as z:
        print('zde at', last_frame(z).tb_lineno)
    c = ast.ClassDef('Point', [], [], [ast.Assign([ast.Name('x', ast.Store())], ast.Constant(3)),
                                        ast.FunctionDef('norm', ast.arguments(args=[ast.arg('self')]), [ast.Return(ast.Attribute(ast.Name('self', ast.Load()), 'x', ast.Load()))], [])], [], [])
    ns = {}
    exec(compile(ast.fix_missing_locations(mod(c)), '<cls>', 'exec'), ns)
    print(ns['Point']().norm(), ns['Point'].__qualname__, ns['Point'].norm.__qualname__)


class Doubler(ast.NodeTransformer):
    def visit_BinOp(self, node):
        self.generic_visit(node)
        if isinstance(node.op, ast.Add):
            return ast.copy_location(ast.BinOp(node.left, ast.Mult(), node.right), node)
        return node

    def visit_Constant(self, node):
        if isinstance(node.value, str):
            return ast.copy_location(ast.Constant(node.value.upper()), node)
        return node


class Wrap(ast.NodeTransformer):
    def visit_Call(self, node):
        self.generic_visit(node)
        if isinstance(node.func, ast.Name) and node.func.id == 'print':
            node.args.insert(0, ast.Constant('>>'))
        return node


class Dropper(ast.NodeTransformer):
    def visit_Assert(self, node):
        return None

    def visit_Expr(self, node):
        if isinstance(node.value, ast.Constant):
            return ast.Pass()
        return node


def transformers():
    print('-- transformers')
    t = Doubler().visit(ast.parse("x = 3 + 4\nprint(x, 'abc', (1 + 2) + 5)\ndef g(n):\n    return n + n\nprint(g(5))\n"))
    exec(compile(t, '<transformed>', 'exec'), {})
    print(ast.unparse(t))
    t2 = ast.fix_missing_locations(Wrap().visit(ast.parse("for i in range(2):\n    print(i)\n")))
    exec(compile(t2, '<wrapped>', 'exec'), {})
    t3 = Dropper().visit(ast.parse("def f():\n    'doc'\n    assert False\n    return 'kept'\nprint(f(), f.__doc__)\n"))
    exec(compile(ast.fix_missing_locations(t3), '<dropped>', 'exec'), {})


def single():
    print('-- single and eval')
    t = ast.parse('1 + 1', mode='single')
    print(type(t).__name__)
    exec(compile(t, '<i>', 'single'))
    i = ast.Interactive([ast.Expr(ast.Constant('shown')), ast.Assign([ast.Name('q', ast.Store())], ast.Constant(3)), ast.Expr(ast.Name('q', ast.Load()))])
    exec(compile(ast.fix_missing_locations(i), '<i>', 'single'), {})
    exec(compile(ast.Interactive([]), '<i>', 'single'))
    print(eval(compile(ast.parse('(x := 5) * 2', mode='eval'), '<e>', 'eval')))
    print(eval(compile(ast.parse('[a for a in "ab"]', mode='eval'), '<e>', 'eval')))
    print(exec(compile(ast.parse('7', mode='eval'), '<e>', 'eval')))


def constants():
    print('-- constants')
    for v in [None, False, True, ..., b'', 123, 123.0, 123j, 'unicode', b'bytes', tuple('tup'), frozenset('a'), ((1,), 2), frozenset({frozenset({1}), 2}), 2**100]:
        t = ast.parse('x = 123')
        n = ast.Constant(value=v)
        ast.copy_location(n, t.body[0].value)
        t.body[0].value = n
        ns = {}
        exec(compile(t, '<c>', 'exec'), ns)
        print(repr(ns['x']), ns['x'] is v if v in (None, True, False, ...) else ns['x'] == v)
    t = ast.parse('1 + 2', mode='eval').body
    t.left = ast.copy_location(ast.Constant(10), t.left)
    t.right = ast.copy_location(ast.Constant(20j), t.right)
    print(ast.literal_eval(t), ast.literal_eval('[1, -2.5, {"a": (True, None)}]'))


def only_ast():
    print('-- PyCF_ONLY_AST')
    t = ast.parse("x = 1\nprint(x)\n")
    t2 = compile(t, '<t>', 'exec', ast.PyCF_ONLY_AST)
    print('same object', t2 is t, type(t2).__name__, ast.dump(t2) == ast.dump(t))
    print(ast.dump(t2, include_attributes=True))
    print(t2.body[0].targets[0].ctx is t.body[0].targets[0].ctx, t2.body[0].value is t.body[0].value)
    t3 = ast.parse(t)
    print('parse of a tree', t3 is t, ast.dump(t3) == ast.dump(t))
    e = ast.Expression(ast.BinOp(ast.Constant(2, **P), ast.Mult(), ast.Constant(21, **P), **P))
    print(ast.dump(compile(e, '<e>', 'eval', ast.PyCF_ONLY_AST), include_attributes=True))
    show('oa1', cc(mod(ast.Expr(ast.Name('x', ast.Store(), **P), **P)), 'exec', ast.PyCF_ONLY_AST))
    show('oa2', cc(mod(ast.ImportFrom('__future__', [ast.alias('braces', **P)], 0, **P)), 'exec', ast.PyCF_ONLY_AST))
    show('oa3', lambda: ast.parse('"doc"\nfrom __future__ import nope'))
    show('oa4', lambda: ast.dump(ast.parse('x = 1\nfrom __future__ import nope')))
    show('oa5', lambda: ast.parse('from __future__ import braces'))
    show('oa6', lambda: ast.dump(compile(ast.Interactive([ast.Pass(**P)]), '<i>', 'single', ast.PyCF_ONLY_AST)))
    show('oa7', lambda: ast.dump(compile(ast.Interactive([ast.Pass(**P)]), '<i>', 'single', ast.PyCF_OPTIMIZED_AST)))
    t = ast.parse('def f(a: int, *b, c=1, **d) -> str:\n    """doc"""\n    return a\n')
    t.body[0].type_comment = 'tc'
    t.body[0].args.args[0].type_comment = 'atc'
    t.type_ignores = [ast.TypeIgnore(3, 'ignore')]
    print(ast.dump(compile(t, '<t>', 'exec', ast.PyCF_ONLY_AST), include_attributes=True))


def library():
    print('-- library modules')
    import textwrap, json, string, colorsys, fnmatch, shlex, heapq, bisect, difflib, keyword
    for m in (textwrap, json, string, colorsys, fnmatch, shlex, heapq, bisect, difflib, keyword):
        f = m.__file__
        src = open(f, encoding='utf-8').read()
        code = compile(ast.parse(src, f), f, 'exec')
        ns = {'__name__': m.__name__, '__file__': f, '__package__': m.__package__}
        if hasattr(m, '__path__'):
            ns['__path__'] = m.__path__
        exec(code, ns)
        public = sorted(k for k in vars(m) if not k.startswith('_') and type(vars(m)[k]).__name__ != 'module')
        print(m.__name__, all(k in ns for k in public), code.co_filename == f)
        globals()['lib_' + m.__name__] = ns
    print(lib_textwrap['dedent']('    a\n      b\n'), end='')
    print(lib_textwrap['fill']('The quick brown fox jumps over the lazy dog', 12))
    print(lib_json['dumps']({'a': [1, 2.5, None, True]}, sort_keys=True), lib_json['loads']('{"x": [1, {"y": null}]}'))
    print(lib_string['capwords']('hello ast world'), lib_string['Template']('$who likes $what').substitute(who='tim', what='ast'))
    print([round(c, 3) for c in lib_colorsys['rgb_to_hsv'](0.2, 0.4, 0.4)], lib_fnmatch['fnmatch']('a.py', '*.py'))
    print(lib_shlex['split']('a "b c" d'), lib_shlex['quote']("it's"))
    h = []
    for v in (5, 1, 4):
        lib_heapq['heappush'](h, v)
    print(lib_heapq['nsmallest'](2, [9, 3, 7, 1]), h[0], lib_bisect['bisect']([1, 3, 5], 4))
    print(list(lib_difflib['unified_diff'](['a\n', 'b\n'], ['a\n', 'c\n'], lineterm='')))
    print(lib_keyword['iskeyword']('match'), lib_keyword['issoftkeyword']('match'))


def main():
    mode_checks()
    required_fields()
    field_values()
    field_values2()
    odd_objects()
    positions()
    checks()
    checks2()
    validation()
    compiler_errors()
    hand_errors()
    odd_but_valid()
    round_trips()
    line_numbers()
    handmade()
    transformers()
    single()
    constants()
    only_ast()
    library()


main()
