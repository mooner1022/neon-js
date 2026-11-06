# Frames and tracebacks: an exception's entries, frames' f_back, sys._getframe.
import sys


def chain(tb):
    out = []
    while tb is not None:
        out.append((tb.tb_frame.f_code.co_name, tb.tb_lineno - BASE))
        tb = tb.tb_next
    return out


BASE = sys._getframe().f_lineno


def inner():
    return 1 / 0


def middle():
    return inner() + 1


def outer():
    try:
        middle()
    except ZeroDivisionError as e:
        return chain(e.__traceback__), chain(sys.exc_info()[2]), sys.exception() is e


def reraising():
    try:
        inner()
    except ZeroDivisionError:
        raise


def raising_again():
    try:
        inner()
    except ZeroDivisionError as e:
        raise e


def wrapping():
    try:
        inner()
    except ZeroDivisionError as e:
        raise ValueError("wrapped") from e


def caught(f):
    try:
        f()
    except Exception as e:
        return type(e).__name__, chain(e.__traceback__)


def frames():
    def deep(n):
        if n == 0:
            names = []
            f = sys._getframe()
            while f is not None:
                names.append(f.f_code.co_name)
                f = f.f_back
            return names, sys._getframe(1).f_code.co_name, sys._getframemodulename(), sys._getframe(2).f_lineno > BASE
        return deep(n - 1)
    return deep(2)


def gen():
    yield sys._getframe().f_back.f_code.co_name
    yield sys._getframe().f_code.co_name


def tb_objects():
    try:
        inner()
    except ZeroDivisionError as e:
        tb = e.__traceback__
    print(type(tb).__name__, type(tb.tb_frame).__name__, tb.tb_frame.f_code.co_name, tb.tb_next.tb_frame.f_code.co_name)
    print(tb.tb_frame.f_globals is globals(), tb.tb_lasti >= 0, tb.tb_frame.f_back is None)
    t2 = type(tb)(None, tb.tb_frame, 0, 99)
    print(t2.tb_lineno, t2.tb_next)
    try:
        raise IndexError().with_traceback(tb)
    except IndexError as e:
        print(e.__traceback__ is not tb, e.__traceback__.tb_next is tb)
    try:
        tb.tb_next = 5
    except TypeError as e:
        print("TypeError", e)
    try:
        tb.tb_next.tb_next = tb
    except ValueError as e:
        print("ValueError", e)
    try:
        sys._getframe(1000)
    except ValueError as e:
        print("ValueError", e)


def module_frame():
    return sys._getframe(1).f_code.co_name, sys._getframe(1).f_locals is globals()


print(outer())
print(caught(reraising))
print(caught(raising_again))
print(caught(wrapping))
print(frames())
print(list(gen()))
tb_objects()
print(module_frame())
