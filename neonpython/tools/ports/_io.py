# _io: CPython's _pyio (its pure implementation of _io) as NeonPy's _io (docs/PYTHON_PLATFORM.md §12.2: the pure
# fallback until an accelerator is worth it). _pyio imports io, which imports _io: what it takes from io is made here.
TARGET = '_io.py'
NOTE = "CPython's _pyio.py as _io: what it takes from io (which imports _io) defined in it, the ABCs' registrations left to io, C _io's messages for open's mode and unsupported operations"

REPLACEMENTS = [
    ('''import io
from io import (__all__, SEEK_SET, SEEK_CUR, SEEK_END, Reader, Writer)  # noqa: F401
''', '''SEEK_SET, SEEK_CUR, SEEK_END = 0, 1, 2
'''),
    ('''try:
    open_code = io.open_code
except AttributeError:
    open_code = _open_code_with_warning
''', '''def open_code(path):
    """Opens the provided file with the intent to import the contents."""
    if not isinstance(path, str):
        raise TypeError(f"open_code() argument 'path' must be str, not {type(path).__name__}")
    return open(path, "rb")
'''),
    ('''try:
    UnsupportedOperation = io.UnsupportedOperation
except AttributeError:
    class UnsupportedOperation(OSError, ValueError):
        pass
''', '''class UnsupportedOperation(OSError, ValueError):
    pass
UnsupportedOperation.__module__ = "io"
'''),
    ('''io.IOBase.register(IOBase)
''', '''_IOBase = IOBase
'''),
    ('''io.RawIOBase.register(RawIOBase)
''', '''_RawIOBase = RawIOBase
'''),
    ('''io.BufferedIOBase.register(BufferedIOBase)
''', '''_BufferedIOBase = BufferedIOBase
'''),
    ('''io.TextIOBase.register(TextIOBase)
''', '''_TextIOBase = TextIOBase
'''),
    # (C _io's messages where _pyio's are its own)
    ("""        raise ValueError("can't have read/write/append mode at once")
""", """        raise ValueError("must have exactly one of create/read/write/append mode")
"""),
    ("""        raise ValueError("must have exactly one of read/write/append mode")
""", """        raise ValueError("Must have exactly one of create/read/write/append mode and at most one plus")
"""),
    ("""        raise UnsupportedOperation("%s.%s() not supported" %
                                   (self.__class__.__name__, name))
""", """        raise UnsupportedOperation(name)
"""),
]
