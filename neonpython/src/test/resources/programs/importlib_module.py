# importlib, NeonPy's own over its import: import_module, find_spec (a spec made where a module has none), ModuleSpec, reload; the output Linux CPython's.
import importlib, importlib.machinery, importlib.util, sys


def t(label, f):
    try:
        print(label, "->", f())
    except Exception as e:
        print(label, "!!", type(e).__name__, e)


t("import_module", lambda: (importlib.import_module('string').__name__, importlib.import_module('re._parser').__name__, importlib.import_module('._parser', 're').__name__))
t("relative no package", lambda: importlib.import_module('.x'))
t("missing", lambda: importlib.import_module('no_such_mod_xyz'))
t("bad name", lambda: importlib.import_module(5))
t("empty", lambda: importlib.import_module(''))
t("beyond", lambda: importlib.import_module('...x', 're'))
t("suffixes", lambda: (importlib.machinery.SOURCE_SUFFIXES, importlib.machinery.BYTECODE_SUFFIXES[0], '.py' in importlib.machinery.all_suffixes()))
s = importlib.util.find_spec('collections')
t("find_spec pkg", lambda: (s.name, s.origin.endswith('collections/__init__.py'), s.parent, s.has_location, s.submodule_search_locations[0].endswith('collections'), type(s).__name__))
s2 = importlib.util.find_spec('textwrap')
t("find_spec textwrap", lambda: (s2.name, s2.origin.endswith('textwrap.py'), s2.parent, s2.submodule_search_locations))
t("find_spec missing", lambda: importlib.util.find_spec('no_such_mod_xyz'))
t("find_spec missing sub", lambda: importlib.util.find_spec('re.no_such'))
t("find_spec nonpkg sub", lambda: importlib.util.find_spec('textwrap.x'))
t("find_spec builtin", lambda: (importlib.util.find_spec('sys').name, importlib.util.find_spec('sys').origin))
import textwrap
t("find_spec loaded", lambda: (importlib.util.find_spec('textwrap').name, importlib.util.find_spec('textwrap').origin.endswith('textwrap.py')))
t("resolve", lambda: (importlib.util.resolve_name('.b', 'a.c'), importlib.util.resolve_name('..b', 'a.c'), importlib.util.resolve_name('x', None)))
t("resolve bad", lambda: importlib.util.resolve_name('.b', None))
spec = importlib.machinery.ModuleSpec('m', None, origin='o')
t("spec repr", lambda: (repr(spec), spec.parent, spec == importlib.machinery.ModuleSpec('m', None, origin='o')))
m = importlib.util.module_from_spec(spec)
t("module_from_spec", lambda: (type(m).__name__, m.__name__, m.__spec__ is spec, m.__loader__))
textwrap.marker = 1
r = importlib.reload(textwrap)
t("reload", lambda: (r is textwrap, textwrap.marker, callable(textwrap.wrap)))
t("reload bad", lambda: importlib.reload(5))
t("builtin importer", lambda: (importlib.machinery.BuiltinImporter.find_spec('sys').origin, importlib.machinery.BuiltinImporter.find_spec('json')))
