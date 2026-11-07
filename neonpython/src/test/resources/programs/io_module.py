# io over the context's file system (one in memory here): open, _io (CPython's _pyio), text and binary files, encodings, newlines, StringIO/BytesIO, and what stands on them (shutil, tokenize, linecache, tempfile); the output Linux CPython's.
import io, os, sys


def t(label, f):
    try:
        print(label, "->", f())
    except Exception as e:
        print(label, "!!", type(e).__name__, e)


def files():
    with open('a.txt', 'w') as f:
        t("write", lambda: (f.write('héllo\nwörld\n'), f.name, f.mode, f.encoding, f.closed, f.writable(), f.readable()))
        print('print to file', 1, file=f)
    t("closed", lambda: f.closed)
    with open('a.txt') as f:
        t("read", lambda: f.read())
    with open('a.txt') as f:
        t("lines", lambda: (f.readline(), list(f)))
    with open('a.txt', 'rb') as f:
        t("rb", lambda: (f.read(3), f.tell(), f.seek(0), f.read(), type(f).__name__, f.raw.__class__.__name__))
    with open('a.txt', 'a', encoding='utf-8') as f:
        f.write('more')
    t("append", lambda: open('a.txt', encoding='utf-8').read().splitlines())
    with open('b.bin', 'wb') as f:
        t("wb", lambda: (f.write(b'\x00\x01\xff'), f.write(bytearray(b'xy')), type(f).__name__))
    t("rb2", lambda: open('b.bin', 'rb').read())
    with open('c.txt', 'w', newline='') as f:
        f.write('a\r\nb\rc\n')
    t("newline raw", lambda: open('c.txt', 'rb').read())
    t("newline universal", lambda: repr(open('c.txt').read()))
    t("newline none", lambda: repr(open('c.txt', newline='').read()))
    with open('d.txt', 'w', encoding='latin-1') as f:
        f.write('é')
    t("latin-1", lambda: (open('d.txt', 'rb').read(), open('d.txt', encoding='latin-1').read()))
    t("decode error", lambda: open('d.txt', encoding='utf-8').read())
    t("errors replace", lambda: open('d.txt', encoding='utf-8', errors='replace').read())
    with open('e.txt', 'w+') as f:
        f.write('0123456789')
        f.seek(3)
        t("w+", lambda: (f.read(2), f.tell(), f.seek(0, 2), f.truncate(5), f.seek(0), f.read()))
    t("missing", lambda: open('nope.txt'))
    t("isdir", lambda: open('.'))
    t("bad mode", lambda: open('a.txt', 'rw'))
    t("x mode", lambda: open('a.txt', 'x'))
    t("binary encoding", lambda: open('a.txt', 'rb', encoding='utf-8'))
    t("closed read", lambda: f.read())
    t("fileno", lambda: (isinstance(open('a.txt').fileno(), int), open('a.txt').isatty()))
    t("buffering 0", lambda: type(open('a.txt', 'rb', buffering=0)).__name__)
    t("text buffering 0", lambda: open('a.txt', 'r', buffering=0))
    t("repr", lambda: (repr(open('a.txt'))[:40], repr(open('a.txt', 'rb'))[:30]))


def memory():
    s = io.StringIO('x\ny\n')
    t("StringIO", lambda: (s.read(), s.tell(), s.getvalue(), io.StringIO().write('abc')))
    b = io.BytesIO(b'abc')
    t("BytesIO", lambda: (b.read(2), b.getvalue(), b.seek(0, 2), b.write(b'de'), b.getvalue(), bytes(b.getbuffer())))
    w = io.TextIOWrapper(io.BytesIO(), encoding='utf-8', newline='\n')
    w.write('tést\n')
    w.flush()
    t("TextIOWrapper", lambda: (w.buffer.getvalue(), w.encoding, w.line_buffering))
    r = io.TextIOWrapper(io.BytesIO('é\nz'.encode()), encoding='utf-8')
    t("TextIOWrapper read", lambda: (r.readline(), r.read()))
    t("isinstance", lambda: (isinstance(open('a.txt'), io.TextIOBase), isinstance(open('a.txt', 'rb'), io.BufferedIOBase),
                             isinstance(io.StringIO(), io.IOBase), isinstance(io.BytesIO(), io.RawIOBase), issubclass(io.FileIO, io.RawIOBase)))
    t("UnsupportedOperation", lambda: (issubclass(io.UnsupportedOperation, OSError), issubclass(io.UnsupportedOperation, ValueError)))
    t("unsupported", lambda: io.StringIO().fileno())
    t("open is io.open", lambda: (open is io.open, io.open.__name__))
    t("text_encoding", lambda: io.text_encoding(None))
    t("stdout seek", lambda: sys.stdout.seek(0))
    t("names", lambda: [n for n in ('FileIO', 'BytesIO', 'StringIO', 'BufferedReader', 'BufferedWriter', 'BufferedRandom', 'BufferedRWPair', 'TextIOWrapper', 'IncrementalNewlineDecoder', 'SEEK_SET', 'DEFAULT_BUFFER_SIZE') if not hasattr(io, n)])


def tools():
    import shutil, tempfile, tokenize, linecache
    shutil.copyfile('a.txt', 'f.txt')
    t("copyfile", lambda: open('f.txt').read() == open('a.txt').read())
    t("tokenize", lambda: [tok.string for tok in tokenize.generate_tokens(io.StringIO('x = 1 # c\n').readline)])
    t("tokenize file", lambda: (tokenize.open('a.txt').encoding, len(list(tokenize.tokenize(open('a.txt', 'rb').readline))) > 0))
    t("linecache", lambda: (linecache.getline(os.__file__, 1)[:3], linecache.getline('a.txt', 2)))
    t("tempfile", lambda: (len(tempfile.gettempdir()) > 0,))


def main():
    files()
    memory()
    tools()
    for n in os.listdir('.'):
        os.unlink(n)
    print('cleaned', os.listdir('.'))


main()
