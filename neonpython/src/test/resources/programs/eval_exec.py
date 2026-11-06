# compile(), exec() and eval(): source compiled at run time, run in the namespaces given; compile errors as
# SyntaxError objects.


def error(f, *args):
    try:
        f(*args)
        print('no error')
    except Exception as e:
        print(type(e).__name__ + ':', e)


def syntax_errors():
    for src, mode in [("1 +", "exec"), ("x = (1,\n  2 +)\n", "exec"), ("def f():\nreturn 1\n", "exec"),
                      ("nonlocal x\n", "exec"), ("1 2", "eval"), ("x = 1", "eval"), ("f(**x, *y)", "exec"),
                      ("def f(a, a): pass", "exec"), ("break", "exec")]:
        try:
            compile(src, "<test>", mode)
            print('compiled', repr(src))
        except SyntaxError as e:
            print(type(e).__name__, repr(e.msg), e.filename, e.lineno, repr(e.text), len(e.args), '|', e)

    e = SyntaxError("m")
    print(repr(str(e)), e.msg, e.lineno, e.args)
    e = SyntaxError("m", ("f.py", 3, 4, "txt"))
    print(repr(str(e)), e.msg, e.lineno, e.offset, e.text, e.end_lineno, e.args, e.print_file_and_line)
    print(repr(str(SyntaxError("m", (None, 7, 4, "txt")))), repr(str(SyntaxError("m", ("f.py", None, 4, "txt")))), repr(str(SyntaxError())))
    print(issubclass(IndentationError, SyntaxError), issubclass(TabError, IndentationError))


syntax_errors()


def running():
    c = compile("1+2", "f", "eval")
    print(type(c).__name__, c.co_filename, eval(c), eval("3*4"), exec("pass"))
    g = {}
    exec("a = 1\ndef f(): return a\nb = f()", g)
    print(sorted(k for k in g), g['b'], type(g['__builtins__']).__name__)
    l = {}
    exec("a = 2\ndef f(): return a\ntry:\n    b = f()\nexcept NameError as e:\n    b = str(e)", {}, l)
    print(sorted(l), l['b'])
    print(eval("x + 1", {"x": 1}), eval("x", {"x": 1}, {"x": 2}), eval("  1 + 1  \n\n"), eval(b"2"))
    print(eval(compile("x = 5", "f", "exec"), {}), eval("[i * i for i in range(3)]"))

    def fn():
        q = 7
        return eval("q * 2"), exec("q = 99"), q
    print(fn())

    class K:
        z = 3
        w = eval("z + 1")
    print(K.w)
    exec("y_global = 'set by exec'")
    print(y_global)
    error(compile, "1", "f", "foo")
    error(compile, 1, "f", "exec")
    error(exec, 1)
    error(eval, "1", [])
    error(exec, "raise ValueError('from exec')")
    error(eval, "undefined_in_eval")
    # a code object runs again, in another namespace
    code = compile("n = n + 1 if 'n' in dir() else 1", "f", "exec")
    ns = {}
    exec(code, ns)
    exec(code, ns)
    print(ns['n'])
    # future annotations in the source
    ns = {}
    exec("from __future__ import annotations\ndef f(x: int) -> str: pass", ns)
    print(ns['f'].__annotations__)


y_global = None
running()


def dirs():
    def f(a, b=1):
        c = 3
        return dir()
    print(f(0))

    class K:
        x = 1
        print(dir())

        def m(self):
            pass

    class L(K):
        y = 2
    o = L()
    o.z = 3
    print([n for n in dir(o) if not n.startswith('__')], [n for n in dir(L) if not n.startswith('__')])

    class D:
        def __dir__(self):
            return ['b', 'a', 'a']
    print(dir(D()))
    error(dir, 1, 2)
    print([n for n in dir() if not n.startswith('__')])


dirs()
print([n for n in dir() if not n.startswith('__')][:4])


def code_objects():
    def f(a, b, /, c, *args, d, e=1, **kw):
        x = a
        def g():
            return x
        return g

    co = f.__code__
    print(co.co_name, co.co_qualname, co.co_argcount, co.co_posonlyargcount, co.co_kwonlyargcount)
    print(co.co_varnames, co.co_flags, co.co_firstlineno - code_objects.__code__.co_firstlineno)
    print(f.__code__ is co, f(1, 2, 3, d=4).__code__.co_qualname, f(1, 2, 3, d=4).__code__.co_flags)

    def gen():
        yield 1

    async def coro():
        pass

    async def agen():
        yield 1
    print(gen.__code__.co_flags, coro.__code__.co_flags, agen.__code__.co_flags, (lambda: 0).__code__.co_name)
    print(f.__call__(1, 2, 3, d=4)(), len.__call__([1, 2]), [].append.__call__ is not None, print.__call__('called'))


code_objects()
