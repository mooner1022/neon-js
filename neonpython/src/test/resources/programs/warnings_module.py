# CPython's warnings, bundled (_py_warnings): filters, catch_warnings, formatwarning, warn_explicit.
import sys
import warnings


def t(label, f):
    try:
        print(label, "->", f())
    except Exception as e:
        print(label, "!!", type(e).__name__, e)


class MyWarning(UserWarning):
    pass


def main():
    with warnings.catch_warnings(record=True) as w:
        warnings.simplefilter("always")
        warnings.warn("first")
        warnings.warn("second", DeprecationWarning)
        warnings.warn(MyWarning("mine"))
        print([(type(x.message).__name__, str(x.message), x.category.__name__, x.lineno > 0, isinstance(x.filename, str)) for x in w])
    with warnings.catch_warnings():
        warnings.simplefilter("error")
        t("error", lambda: warnings.warn("boom", RuntimeWarning))
    with warnings.catch_warnings(record=True) as w:
        warnings.simplefilter("ignore")
        warnings.warn("hidden")
        print(len(w))
    with warnings.catch_warnings(record=True) as w:
        warnings.filterwarnings("always", message="keep")
        warnings.filterwarnings("ignore", message="drop")
        warnings.warn("drop this")
        warnings.warn("keep this")
        print([str(x.message) for x in w])
    with warnings.catch_warnings(record=True) as w:
        warnings.simplefilter("default")
        for _ in range(3):
            warnings.warn("once per place")
        print(len(w))
    print(warnings.formatwarning("msg", UserWarning, "f.py", 3, line="src"))
    t("bad category", lambda: warnings.warn("x", int))

    with warnings.catch_warnings(record=True) as w:
        warnings.simplefilter("always")
        warnings.warn_explicit("explicit", UserWarning, "file.py", 7)
        print([(x.filename, x.lineno) for x in w])
    print(type(warnings.filters).__name__, warnings.showwarning.__name__)


main()
