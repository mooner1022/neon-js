"""Utility code for constructing importers, etc."""
# NeonPy's own (see importlib/__init__.py): CPython's importlib.util over NeonPy's import system. A module NeonPy
# imported has no __spec__ (None): find_spec makes the one CPython's would have given it.
from . import _resolve_name as __resolve_name
from .machinery import ModuleSpec, BuiltinImporter, PathFinder, SourceFileLoader

import _imp
import sys
import types

MAGIC_NUMBER = (3627).to_bytes(2, 'little') + b'\r\n'


def source_hash(source_bytes):
    "Return the hash of *source_bytes* as used in hash-based pyc files."
    raise NotImplementedError("NeonPy writes no bytecode files")


def resolve_name(name, package):
    """Resolve a relative module name to an absolute one."""
    if not name.startswith('.'):
        return name
    elif not package:
        raise ImportError(f'no package specified for {repr(name)} '
                          '(required for relative module names)')
    level = 0
    for character in name:
        if character != '.':
            break
        level += 1
    return __resolve_name(name[level:], package, level)


def _find_spec(fullname, path):
    for finder in (BuiltinImporter, PathFinder):
        spec = finder.find_spec(fullname, path)
        if spec is not None:
            return spec
    return None


def _spec_of(module):
    """The spec NeonPy's import gave [module] none of: what CPython's would be."""
    name = module.__name__
    if _imp.is_builtin(name):
        return ModuleSpec(name, BuiltinImporter, origin='built-in')
    spec = _find_spec(name, None)
    if spec is None:
        spec = ModuleSpec(name, None)
    return spec


def find_spec(name, package=None):
    """Return the spec for the specified module.

    First, sys.modules is checked to see if the module was already imported. If
    so, then sys.modules[name].__spec__ is returned. If that happens to be
    set to None, then ValueError is raised. If the module is not in
    sys.modules, then sys.meta_path is searched for a suitable spec with the
    value of 'path' given to the finders. None is returned if no spec could
    be found.

    If the name is for submodule (contains a dot), the parent module is
    automatically imported.

    The name and package arguments work the same as importlib.import_module().
    In other words, relative module names (with leading dots) work.

    """
    fullname = resolve_name(name, package) if name.startswith('.') else name
    if fullname not in sys.modules:
        parent_name = fullname.rpartition('.')[0]
        if parent_name:
            parent = __import__(parent_name, fromlist=['__path__'])
            try:
                parent_path = parent.__path__
            except AttributeError as e:
                raise ModuleNotFoundError(
                    f"__path__ attribute not found on {parent_name!r} "
                    f"while trying to find {fullname!r}", name=fullname) from e
        else:
            parent_path = None
        return _find_spec(fullname, parent_path)
    else:
        module = sys.modules[fullname]
        if module is None:
            return None
        try:
            spec = module.__spec__
        except AttributeError:
            raise ValueError(f'{name}.__spec__ is not set') from None
        else:
            if spec is None:
                # (NeonPy's import sets none: the one CPython's would have)
                return _spec_of(module)
            return spec


def spec_from_loader(name, loader, *, origin=None, is_package=None):
    """Return a module spec based on various loader methods."""
    if origin is None:
        origin = getattr(loader, '_ORIGIN', None)
    if not origin and hasattr(loader, 'get_filename'):
        if is_package is None:
            return spec_from_file_location(name, loader=loader)
        search = [] if is_package else None
        return spec_from_file_location(name, loader=loader,
                                       submodule_search_locations=search)
    if is_package is None:
        if hasattr(loader, 'is_package'):
            try:
                is_package = loader.is_package(name)
            except ImportError:
                is_package = None  # aka, undefined
        else:
            # the default
            is_package = False
    return ModuleSpec(name, loader, origin=origin, is_package=is_package)


_POPULATE = object()


def spec_from_file_location(name, location=None, *, loader=None,
                            submodule_search_locations=_POPULATE):
    """Return a module spec based on a file location."""
    if location is None:
        location = '<unknown>'
        if hasattr(loader, 'get_filename'):
            try:
                location = loader.get_filename(name)
            except ImportError:
                pass
    else:
        location = __import__('os').fspath(location)
    spec = ModuleSpec(name, loader, origin=location)
    spec._set_fileattr = True
    if loader is None and location.endswith('.py'):
        loader = SourceFileLoader(name, location)
        spec.loader = loader
    if submodule_search_locations is _POPULATE:
        if hasattr(loader, 'is_package'):
            try:
                is_package = loader.is_package(name)
            except ImportError:
                pass
            else:
                if is_package:
                    spec.submodule_search_locations = []
    else:
        spec.submodule_search_locations = submodule_search_locations
    if spec.submodule_search_locations == []:
        if location:
            dirname = location.rpartition('/')[0]
            spec.submodule_search_locations.append(dirname)
    return spec


def module_from_spec(spec):
    """Create a module based on the provided spec."""
    module = None
    if hasattr(spec.loader, 'create_module'):
        module = spec.loader.create_module(spec)
    elif hasattr(spec.loader, 'exec_module'):
        raise ImportError('loaders that define exec_module() '
                          'must also define create_module()')
    if module is None:
        module = types.ModuleType(spec.name)
    module.__spec__ = spec
    module.__loader__ = spec.loader
    if spec.parent is not None or spec.submodule_search_locations is None:
        try:
            module.__package__ = spec.parent
        except AttributeError:
            pass
    if spec.submodule_search_locations is not None:
        module.__path__ = spec.submodule_search_locations
    if spec.has_location:
        module.__file__ = spec.origin
    return module


def decode_source(source_bytes):
    """Decode bytes representing source code and return the string."""
    import tokenize  # To avoid bootstrap issues.
    import io
    source_bytes_readline = io.BytesIO(source_bytes).readline
    encoding = tokenize.detect_encoding(source_bytes_readline)
    newline_decoder = io.IncrementalNewlineDecoder(None, True)
    return newline_decoder.decode(source_bytes.decode(encoding[0]))
