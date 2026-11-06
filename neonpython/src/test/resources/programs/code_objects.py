# code objects' attributes as far as they are CPython's (names, flags, docstring, replace, lasti and positions), and dis's names; NeonPy's bytecode is its own.
import dis, sys
def t(label, f):
    try:
        print(label, "->", f())
    except Exception as e:
        print(label, "!!", type(e).__name__, e)
def f(a, b=2, *args, c, **kw):
    "doc"
    x = len(a.attr) + b
    def g():
        return x + c
    return g
co = f.__code__
t("names", lambda: (co.co_name, co.co_varnames, co.co_cellvars, co.co_freevars, [k for k in co.co_consts if hasattr(k, 'co_freevars')][0].co_freevars, co.co_nlocals))
t("names2", lambda: (sorted(co.co_names), co.co_consts[0], type(co.co_code).__name__, co.co_stacksize > 0))
t("flags", lambda: (dis.pretty_flags(co.co_flags), dis.COMPILER_FLAG_NAMES[32]))
t("positions", lambda: next(co.co_positions())[2:])
ins = list(dis.get_instructions(f))
t("instructions", lambda: (type(ins[0]).__name__, all(isinstance(i.offset, int) and i.offset % 2 == 0 for i in ins), sorted({i.argval for i in ins if i.opname in ('LOAD_GLOBAL', 'LOAD_ATTR')})))
t("replace", lambda: (co.replace(co_name='h').co_name, co.replace().co_name, co.replace(co_filename='x.py').co_filename, f.__code__.co_name))
t("code_info", lambda: dis.code_info(f).splitlines()[0])
def boom():
    1/0
try:
    boom()
except ZeroDivisionError as e:
    tb = e.__traceback__.tb_next
    t("lasti", lambda: (tb.tb_lasti % 2, tb.tb_frame.f_lasti % 2, tb.tb_lineno, list(tb.tb_frame.f_code.co_positions())[tb.tb_lasti // 2][0]))
t("dis names", lambda: (dis.Positions._fields, dis.Instruction._fields[:6], callable(dis.get_instructions), callable(dis.findlinestarts)))
class A:
    def m(self):
        "d"
    def n(self): pass
    @staticmethod
    def s(): pass
    l = lambda self: 1
def top(): pass
for f in (A.m, A.n, A.s, A.l, top):
    print(f.__name__, dis.pretty_flags(f.__code__.co_flags))
print(dis.pretty_flags(compile("1", "x", "exec").co_flags))
print(dis.pretty_flags((lambda: (yield)).__code__.co_flags))
