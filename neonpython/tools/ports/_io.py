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
    # (C _io's messages for a closed, detached or wrong-mode text file, BytesIO, a buffered writer and FileIO;
    # RawIOBase's readinto and write NotImplementedError, as C's)
    ('    def flush(self):\n        self.buffer.flush()\n        self._telling = self._seekable\n',
     '    def flush(self):\n        if self.closed:\n            raise ValueError(self._closed_message())\n        self.buffer.flush()\n        self._telling = self._seekable\n'),
    ('    def tell(self):\n        if not self._seekable:\n            raise UnsupportedOperation("underlying stream is not seekable")\n        if not self._telling:',
     '    def tell(self):\n        if self.closed:\n            raise ValueError(self._closed_message())\n        if not self._seekable:\n            raise UnsupportedOperation("underlying stream is not seekable")\n        if not self._telling:'),
    ('        if self.closed:\n            raise ValueError("tell on closed file")\n        if not self._seekable:',
     '        if self.closed:\n            raise ValueError(self._closed_message())\n        if not self._seekable:'),
    ('        if self.closed:\n            raise ValueError("write to closed file")\n        if not isinstance(s, str):\n            raise TypeError("can\'t write %s to text stream" %\n                            s.__class__.__name__)\n',
     '        if not isinstance(s, str):\n            raise TypeError("write() argument must be str, not %s" %\n                            s.__class__.__name__)\n        if self.closed:\n            raise ValueError(self._closed_message())\n        if not self.writable():\n            raise UnsupportedOperation("not writable")\n'),
    ('    def read(self, size=None):\n        self._checkReadable()\n        if size is None:',
     '    def read(self, size=None):\n        if self.closed:\n            raise ValueError(self._closed_message())\n        if not self.readable():\n            raise UnsupportedOperation("not readable")\n        if size is None:'),
    ('    def readline(self, size=None):\n        if self.closed:\n            raise ValueError("read from closed file")\n',
     '    def readline(self, size=None):\n        if self.closed:\n            raise ValueError(self._closed_message())\n        if not self.readable():\n            raise UnsupportedOperation("not readable")\n'),
    ('    @property\n    def buffer(self):\n        return self._buffer\n',
     '    @property\n    def buffer(self):\n        if self._buffer is None:\n            raise ValueError("underlying buffer has been detached")\n        return self._buffer\n\n    def _closed_message(self):\n        # (C _io\'s: a StringIO\'s without the full stop)\n        return "I/O operation on closed file" if isinstance(self, StringIO) else "I/O operation on closed file."\n'),
    ('    def read(self, size=-1):\n        if self.closed:\n            raise ValueError("read from closed file")\n',
     '    def read(self, size=-1):\n        if self.closed:\n            raise ValueError("I/O operation on closed file.")\n'),
    ('            if self.closed:\n                raise ValueError("write to closed file")\n\n            n = view.nbytes  # Size of any bytes-like object',
     '            if self.closed:\n                raise ValueError("I/O operation on closed file.")\n\n            n = view.nbytes  # Size of any bytes-like object'),
    ('            raise ValueError("seek on closed file")',
     '            raise ValueError("I/O operation on closed file.")'),
    ('    def tell(self):\n        if self.closed:\n            raise ValueError("tell on closed file")\n        return self._pos',
     '    def tell(self):\n        if self.closed:\n            raise ValueError("I/O operation on closed file.")\n        return self._pos'),
    ('            raise ValueError("truncate on closed file")',
     '            raise ValueError("I/O operation on closed file.")'),
    ('            raise ValueError("getvalue on closed file")',
     '            raise ValueError("I/O operation on closed file.")'),
    ('            raise ValueError("getbuffer on closed file")',
     '            raise ValueError("I/O operation on closed file.")'),
    ('    def flush(self):\n        if self.closed:\n            raise ValueError("flush on closed file")\n        self.raw.flush()',
     '    def flush(self):\n        if self.closed:\n            raise ValueError("I/O operation on closed file.")\n        self.raw.flush()'),
    ('    def _flush_unlocked(self):\n        if self.closed:\n            raise ValueError("flush on closed file")',
     '    def _flush_unlocked(self):\n        if self.closed:\n            raise ValueError("flush of closed file")'),
    ('        if whence not in valid_seek_flags:\n            raise ValueError("invalid whence value")\n        with self._write_lock:\n            self._flush_unlocked()\n            return _BufferedIOMixin.seek(self, pos, whence)',
     '        if whence not in valid_seek_flags:\n            raise ValueError("invalid whence value")\n        self._checkClosed("seek of closed file")\n        with self._write_lock:\n            self._flush_unlocked()\n            return _BufferedIOMixin.seek(self, pos, whence)'),
    ('class FileIO(RawIOBase):\n    _fd = -1\n',
     'class FileIO(RawIOBase):\n    _fd = -1\n\n    def _checkClosed(self, msg=None):\n        # (C FileIO\'s: without the full stop)\n        if self.closed:\n            raise ValueError("I/O operation on closed file" if msg is None else msg)\n'),
    ('        None if the object is set not to block and has no data to read.\n        """\n        self._unsupported("readinto")\n',
     '        None if the object is set not to block and has no data to read.\n        """\n        raise NotImplementedError\n'),
    ('        length of b in bytes.\n        """\n        self._unsupported("write")\n',
     '        length of b in bytes.\n        """\n        raise NotImplementedError\n'),
    ('        if size is not None and size < -1:\n            raise ValueError("invalid number of bytes to read")\n        with self._read_lock:\n            return self._read_unlocked(size)\n',
     '        if size is not None and size < -1:\n            raise ValueError("invalid number of bytes to read")\n        self._checkClosed("read of closed file")\n        with self._read_lock:\n            return self._read_unlocked(size)\n'),
    ('    def truncate(self, pos=None):\n        self._checkClosed()\n        self._checkWritable()\n',
     '    def truncate(self, pos=None):\n        self._checkClosed()\n        if not self.writable():\n            raise UnsupportedOperation("truncate")\n'),
]
