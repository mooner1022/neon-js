"""The machinery of importlib: finders, loaders, and related objects."""
# NeonPy's own (see importlib/__init__.py): what importlib.machinery names, over NeonPy's import system. ModuleSpec is
# CPython's (importlib/_bootstrap.py); the finders and loaders find and give what NeonPy's import does (_imp).

import _imp
import sys

SOURCE_SUFFIXES = ['.py']
BYTECODE_SUFFIXES = ['.pyc']
# Deprecated.
DEBUG_BYTECODE_SUFFIXES = OPTIMIZED_BYTECODE_SUFFIXES = BYTECODE_SUFFIXES
EXTENSION_SUFFIXES = _imp.extension_suffixes()


def all_suffixes():
    """Returns a list of all recognized module suffixes for this process"""
    return SOURCE_SUFFIXES + BYTECODE_SUFFIXES + EXTENSION_SUFFIXES


class ModuleSpec:
    """The specification for a module, used for loading.

    A module's spec is the source for information about the module.  For
    data associated with the module, including source, use the spec's
    loader.

    `name` is the absolute name of the module.  `loader` is the loader
    to use when loading the module.  `parent` is the name of the
    package the module is in.  The parent is derived from the name.

    `is_package` determines if the module is considered a package or
    not.  On modules this is reflected by the `__path__` attribute.

    `origin` is the specific location used by the loader from which to
    load the module, if that information is available.  When filename is
    set, origin will match.

    `has_location` indicates that a spec's "origin" reflects a location.
    When this is True, `__file__` attribute of the module is set.

    `cached` is the location of the cached bytecode file, if any.  It
    corresponds to the `__cached__` attribute.

    `submodule_search_locations` is the sequence of path entries to
    search when importing submodules.  If set, is_package should be
    True--and False otherwise.

    Packages are simply modules that (may) have submodules.  If a spec
    has a non-None value in `submodule_search_locations`, the import
    system will consider modules loaded from the spec as packages.

    Only finders (see importlib.abc.MetaPathFinder and
    importlib.abc.PathEntryFinder) should modify ModuleSpec instances.

    """

    def __init__(self, name, loader, *, origin=None, loader_state=None,
                 is_package=None):
        self.name = name
        self.loader = loader
        self.origin = origin
        self.loader_state = loader_state
        self.submodule_search_locations = [] if is_package else None
        self._uninitialized_submodules = []

        # file-location attributes
        self._set_fileattr = False
        self._cached = None

    def __repr__(self):
        args = [f'name={self.name!r}', f'loader={self.loader!r}']
        if self.origin is not None:
            args.append(f'origin={self.origin!r}')
        if self.submodule_search_locations is not None:
            args.append(f'submodule_search_locations={self.submodule_search_locations}')
        return f'{self.__class__.__name__}({", ".join(args)})'

    def __eq__(self, other):
        smsl = self.submodule_search_locations
        try:
            return (self.name == other.name and
                    self.loader == other.loader and
                    self.origin == other.origin and
                    smsl == other.submodule_search_locations and
                    self.cached == other.cached and
                    self.has_location == other.has_location)
        except AttributeError:
            return NotImplemented

    @property
    def cached(self):
        # (no bytecode is written: none)
        return self._cached

    @cached.setter
    def cached(self, cached):
        self._cached = cached

    @property
    def parent(self):
        """The name of the module's parent."""
        if self.submodule_search_locations is None:
            return self.name.rpartition('.')[0]
        else:
            return self.name

    @property
    def has_location(self):
        return self._set_fileattr

    @has_location.setter
    def has_location(self, value):
        self._set_fileattr = bool(value)


class BuiltinImporter:
    """Meta path import for built-in modules."""

    _ORIGIN = "built-in"

    @classmethod
    def find_spec(cls, fullname, path=None, target=None):
        if _imp.is_builtin(fullname):
            return ModuleSpec(fullname, cls, origin=cls._ORIGIN)
        return None

    @staticmethod
    def create_module(spec):
        """Create a built-in module"""
        if spec.name not in sys.builtin_module_names:
            raise ImportError(f'{spec.name!r} is not a built-in module', name=spec.name)
        return __import__(spec.name)

    @staticmethod
    def exec_module(module):
        """Exec a built-in module"""

    @classmethod
    def is_package(cls, fullname):
        """Return False as built-in modules are never packages."""
        return False

    @classmethod
    def get_code(cls, fullname):
        """Return None as built-in modules do not have code objects."""
        return None

    @classmethod
    def get_source(cls, fullname):
        """Return None as built-in modules do not have source code."""
        return None


class FrozenImporter:
    """Meta path import for frozen modules (NeonPy has none)."""

    _ORIGIN = "frozen"

    @classmethod
    def find_spec(cls, fullname, path=None, target=None):
        return None

    @classmethod
    def is_package(cls, fullname):
        return False


class SourceFileLoader:
    """Concrete implementation of SourceLoader using the file system."""

    def __init__(self, fullname, path):
        self.name = fullname
        self.path = path

    def __eq__(self, other):
        return (self.__class__ == other.__class__ and
                self.__dict__ == other.__dict__)

    def __hash__(self):
        return hash(self.name) ^ hash(self.path)

    def get_filename(self, name=None):
        """Return the path to the source file as found by the finder."""
        return self.path

    def get_data(self, path):
        """Return the data from path as raw bytes."""
        with open(path, 'rb') as file:
            return file.read()

    def get_source(self, fullname):
        """Concrete implementation of InspectLoader.get_source."""
        found = _imp._find_source(fullname)
        if found is None:
            raise ImportError('source not available through get_data()', name=fullname)
        return found[2]

    def is_package(self, fullname):
        found = _imp._find_source(fullname)
        return found is not None and found[1]

    def create_module(self, spec):
        """Use default semantics for module creation."""

    def exec_module(self, module):
        """Execute the module."""
        _imp._reload(module)


class SourcelessFileLoader(SourceFileLoader):
    """Loader which handles sourceless file imports (NeonPy writes and reads none)."""


class ExtensionFileLoader(SourceFileLoader):
    """Loader for extension modules (NeonPy has none)."""


class NamespaceLoader:
    """A loader for namespace packages."""

    def __init__(self, name, path, path_finder):
        self._path = path


class PathFinder:
    """Meta path finder for sys.path and package __path__ attributes."""

    @staticmethod
    def invalidate_caches():
        """Call the invalidate_caches() method on all path entry finders."""

    @classmethod
    def find_spec(cls, fullname, path=None, target=None):
        found = _imp._find_source(fullname)
        if found is None:
            return None
        origin, is_package, _ = found
        spec = ModuleSpec(fullname, SourceFileLoader(fullname, origin), origin=origin, is_package=is_package)
        spec.has_location = True
        if is_package:
            spec.submodule_search_locations = [origin.rpartition('/')[0]]
        return spec


class FileFinder:
    """File-based finder."""

    def __init__(self, path, *loader_details):
        self.path = path or '.'

    def invalidate_caches(self):
        """Invalidate the directory mtime."""

    def find_spec(self, fullname, target=None):
        return PathFinder.find_spec(fullname)

    @classmethod
    def path_hook(cls, *loader_details):
        def path_hook_for_FileFinder(path):
            return cls(path, *loader_details)
        return path_hook_for_FileFinder


class WindowsRegistryFinder:
    """Meta path finder for modules declared in the Windows registry (none here)."""

    @classmethod
    def find_spec(cls, fullname, path=None, target=None):
        return None
