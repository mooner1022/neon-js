# typing: CPython's, but for _allow_reckless_class_checks, which lets abc's and functools' own class checks through by
# their callers' module names. CPython's abc is C _abc under abc.py's methods (the caller's module 'abc'); NeonPy's is
# the pure _py_abc (docs/PYTHON_PLATFORM.md §12.2), so its checks come from '_py_abc': isinstance() of a runtime
# protocol with data members (importlib.resources.abc.Traversable) would otherwise raise. The lines are kept.
NOTE = "abc's class checks let through from _py_abc too (NeonPy's abc is the pure one)"

REPLACEMENTS = [
    ('''    return _caller(depth) in {'abc', 'functools', None}
''', '''    return _caller(depth) in {'abc', '_py_abc', 'functools', None}
'''),
]
