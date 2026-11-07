# _py_abc: CPython's pure ABCMeta, which NeonPy's abc is (docs/PYTHON_PLATFORM.md §12.2), but with what C _abc does
# to a class's type flags: _abc_init takes a new ABC's __abc_tpflags__ from its dict and sets its collection flags
# (collections.abc's Sequence and Mapping), and _abc_register gives a registered class (and its subclasses) the ABC's.
# They are what `match` takes for a sequence or a mapping. NeonPy's _collections does both (its _abc_init_tpflags and
# _abc_register_tpflags). The lines are kept.
NOTE = "C _abc's collection flags: a new ABC's __abc_tpflags__, a registered class's (what match takes for a sequence or a mapping)"

REPLACEMENTS = [
    ('''from _weakrefset import WeakSet
''', '''from _weakrefset import WeakSet; from _collections import _abc_init_tpflags, _abc_register_tpflags
'''),
    ('''        cls = super().__new__(mcls, name, bases, namespace, **kwargs)
''', '''        cls = super().__new__(mcls, name, bases, namespace, **kwargs); _abc_init_tpflags(cls)
'''),
    ('''        cls._abc_registry.add(subclass)
''', '''        cls._abc_registry.add(subclass); _abc_register_tpflags(cls, subclass)
'''),
]
