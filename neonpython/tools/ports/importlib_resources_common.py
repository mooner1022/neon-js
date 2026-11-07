# importlib/resources/_common: CPython's, but for from_package, which asks a module's spec's loader for a resource
# reader. NeonPy's modules have no __spec__ (docs/PYTHON_PLATFORM.md §12.2: importlib is NeonPy's own): a module's
# resources are its file's directory, the bundled library's through its read-only mount. The lines are kept.
NOTE = "a module's resources its file's directory (NeonPy's modules have no spec, no loader's reader)"

REPLACEMENTS = [
    ('''    # deferred for performance (python/cpython#109829)
    from ._adapters import wrap_spec

    spec = wrap_spec(package)
    reader = spec.loader.get_resource_reader(spec.name)
    return reader.files()
''', '''    # NeonPy: no module specs; a module's resources are its file's directory, the bundled
    # library's through its read-only mount (docs/PYTHON_PLATFORM.md 12.2)
    file = getattr(package, '__file__', None)
    if file is None:
        return pathlib.PurePosixPath(package.__name__)
    return pathlib.Path(file).parent
'''),
]
