# io's errors as C _io gives them: wrong modes, closed and detached files, the base classes' unsupported operations (one file system in memory; the output Linux CPython's).
import io, os


def t(label, f):
    try:
        print(label, "->", f())
    except Exception as e:
        print(label, "!!", type(e).__name__, e)


def modes():
    with open('f.txt', 'w') as f:
        f.write('abc')
    w = open('f.txt', 'w')
    r = open('f.txt')
    wb = open('f.txt', 'ab')
    rb = open('f.txt', 'rb')
    raww = open('f.txt', 'ab', buffering=0)
    t("text w read", lambda: w.read())
    t("text w readline", lambda: w.readline())
    t("text r write", lambda: r.write('x'))
    t("text r truncate", lambda: r.truncate())
    t("bin w read", lambda: wb.read())
    t("bin r write", lambda: rb.write(b'x'))
    t("raw w read", lambda: raww.read())
    t("raw w readinto", lambda: raww.readinto(bytearray(2)))
    t("stringio detach", lambda: io.StringIO().detach())
    t("stringio fileno", lambda: io.StringIO().fileno())
    t("bytesio fileno", lambda: io.BytesIO().fileno())
    t("buffered on bytesio read", lambda: io.BufferedWriter(io.BytesIO()).read())
    t("bufferedreader write", lambda: io.BufferedReader(io.BytesIO(b'x')).write(b'y'))
    t("iobase seek", lambda: io.IOBase().seek(0))
    t("iobase truncate", lambda: io.IOBase().truncate())
    t("iobase fileno", lambda: io.IOBase().fileno())
    t("rawiobase read", lambda: io.RawIOBase().read())
    t("bufferediobase read", lambda: io.BufferedIOBase().read())
    t("bufferediobase read1", lambda: io.BufferedIOBase().read1())
    t("bufferediobase write", lambda: io.BufferedIOBase().write(b''))
    t("bufferediobase detach", lambda: io.BufferedIOBase().detach())
    t("textiobase read", lambda: io.TextIOBase().read())
    t("textiobase readline", lambda: io.TextIOBase().readline())
    t("textiobase write", lambda: io.TextIOBase().write(''))
    t("textiobase detach", lambda: io.TextIOBase().detach())
    t("closed read", lambda: (r.close(), r.read()))
    t("detached", lambda: (lambda x: (x.detach(), x.read()))(open('f.txt')))
    for x in (w, wb, rb, raww):
        x.close()
    os.unlink('f.txt')




def closed():
    open('g.txt', 'w').close()
    for mode in ('w', 'r', 'wb', 'rb'):
        f = open('g.txt', mode)
        f.close()
        if 'b' in mode:
            t(mode + " write", lambda: f.write(b'x'))
            t(mode + " read", lambda: f.read())
        else:
            t(mode + " write", lambda: f.write('x'))
            t(mode + " read", lambda: f.read())
            t(mode + " readline", lambda: f.readline())
        t(mode + " seek", lambda: f.seek(0))
        t(mode + " tell", lambda: f.tell())
        t(mode + " flush", lambda: f.flush())
        t(mode + " iter", lambda: next(iter(f)))
    t("text write bytes", lambda: open('g.txt', 'w').write(b'x'))
    t("stringio closed", lambda: (lambda s: (s.close(), s.write('x')))(io.StringIO()))
    t("bytesio closed", lambda: (lambda s: (s.close(), s.read()))(io.BytesIO()))
    import os; os.unlink('g.txt')


modes()
closed()
