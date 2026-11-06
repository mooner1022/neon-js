# contextvars (_contextvars in Kotlin): ContextVar, Token, Context.run, copy_context, CPython's messages.
import contextvars as cv


def s(x):
    return str(x).split(' at ')[0]


def main():
    v = cv.ContextVar('v', default=1)
    w = cv.ContextVar('w')
    print(s(repr(v)), s(repr(w)), v.name, v.get(), w.get(5))
    try:
        w.get()
    except LookupError as e:
        print('LookupError', s(repr(e.args[0])))
    t = v.set(2)
    print(v.get(), t.var is v, t.old_value is cv.Token.MISSING, repr(cv.Token.MISSING), s(repr(t)))
    v.reset(t)
    print(v.get())
    try:
        v.reset(t)
    except RuntimeError as e:
        print('RuntimeError', s(e))
    c = cv.copy_context()
    print(type(c).__name__, len(c), v in c, list(c.keys()) == [], dict(c.items()))

    def f(a):
        v.set(a)
        return v.get()

    print(c.run(f, 9), v.get(), c[v], c.get(v), len(c), list(c.values()), c.get(w, 'd'))
    try:
        c.run(lambda: c.run(lambda: 0))
    except RuntimeError as e:
        print('RuntimeError', s(e))
    with v.set(7) as tok:
        print(v.get(), type(tok).__name__)
    print(v.get())
    t2 = w.set(1)
    try:
        v.reset(t2)
    except ValueError as e:
        print('ValueError', s(e))
    t3 = v.set(3)
    try:
        cv.copy_context().run(v.reset, t3)
    except ValueError as e:
        print('ValueError', s(e))
    print(type(cv.Context()).__name__, len(cv.Context()), cv.ContextVar.__module__, cv.Context.__module__, cv.Token.__module__)
    try:
        cv.ContextVar()
    except TypeError as e:
        print(e)
    try:
        class X(cv.ContextVar):
            pass
    except TypeError as e:
        print(e)
    print(cv.ContextVar[int], hash(v) == hash(v), c == c.copy(), c.copy() is c)
    try:
        c[w]
    except KeyError as e:
        print('KeyError', s(repr(e.args[0])))
    nested = cv.copy_context()
    print(nested.run(lambda: (v.set(100), cv.copy_context()[v])[1]), v.get())


main()
