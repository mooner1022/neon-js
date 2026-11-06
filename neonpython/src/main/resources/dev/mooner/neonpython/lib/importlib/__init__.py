"""A pure Python implementation of import."""
# NeonPy's own importlib (docs/PYTHON_PLATFORM.md §12.2): NeonPy's import system is its runtime's, not CPython's
# importlib._bootstrap; this package gives importlib's API over it (_imp: a module's source found without running it,
# a module run again for reload). ModuleSpec and the checks and messages are CPython's.
__all__ = ['__import__', 'import_module', 'invalidate_caches', 'reload']

import _imp
import sys
from builtins import __import__

_RELOADING = {}


def _resolve_name(name, package, level):
    """Resolve a relative module name to an absolute one."""
    bits = package.rsplit('.', level - 1)
    if len(bits) < level:
        raise ImportError('attempted relative import beyond top-level package')
    base = bits[0]
    return f'{base}.{name}' if name else base


def _sanity_check(name, package, level):
    """Verify arguments are "sane"."""
    if not isinstance(name, str):
        raise TypeError(f'module name must be str, not {type(name)}')
    if level < 0:
        raise ValueError('level must be >= 0')
    if level > 0:
        if not isinstance(package, str):
            raise TypeError('__package__ not set to a string')
        elif not package:
            raise ImportError('attempted relative import with no known parent package')
    if not name and level == 0:
        raise ValueError('Empty module name')


def _gcd_import(name, package=None, level=0):
    """Import and return the module based on its name, the package the call is
    being made from, and the level adjustment."""
    _sanity_check(name, package, level)
    if level > 0:
        name = _resolve_name(name, package, level)
    __import__(name)
    return sys.modules[name]


def import_module(name, package=None):
    """Import a module.

    The 'package' argument is required when performing a relative import. It
    specifies the package to use as the anchor point from which to resolve the
    relative import to an absolute import.

    """
    level = 0
    if name.startswith('.'):
        if not package:
            raise TypeError("the 'package' argument is required to perform a "
                            f"relative import for {name!r}")
        for character in name:
            if character != '.':
                break
            level += 1
    return _gcd_import(name[level:], package, level)


def invalidate_caches():
    """Call the invalidate_caches() method on all meta path finders stored in
    sys.meta_path (where implemented)."""
    for finder in sys.meta_path:
        if hasattr(finder, 'invalidate_caches'):
            finder.invalidate_caches()


def reload(module):
    """Reload the module and return it.

    The module must have been successfully imported before.

    """
    try:
        name = module.__spec__.name
    except AttributeError:
        try:
            name = module.__name__
        except AttributeError:
            raise TypeError("reload() argument must be a module")

    if sys.modules.get(name) is not module:
        raise ImportError(f"module {name} not in sys.modules", name=name)
    if name in _RELOADING:
        return _RELOADING[name]
    _RELOADING[name] = module
    try:
        parent_name = name.rpartition('.')[0]
        if parent_name:
            try:
                sys.modules[parent_name]
            except KeyError:
                msg = "parent {!r} not in sys.modules"
                raise ImportError(msg.format(parent_name),
                                  name=parent_name) from None
        if not _imp.is_builtin(name) and _imp._find_source(name) is None:
            raise ModuleNotFoundError(f"spec not found for the module {name!r}", name=name)
        _imp._reload(module)
        # The module may have replaced itself in sys.modules!
        return sys.modules[name]
    finally:
        try:
            del _RELOADING[name]
        except KeyError:
            pass
