# os and posix over the context's file system (one in memory here, the working directory its empty /tmp), errno, contextlib; the output Linux CPython's.
import os, sys, stat, errno, posixpath, contextlib, genericpath


def t(label, f):
    try:
        print(label, "->", f())
    except Exception as e:
        print(label, "!!", type(e).__name__, e)


print(os.name, os.sep, os.pathsep, os.linesep == '\n', os.curdir, os.pardir, os.devnull, os.extsep, os.altsep)
print(sys.platform in ('linux', 'android'), type(os.environ).__name__, os.path is posixpath, sys.getfilesystemencoding(), sys.getfilesystemencodeerrors())
print(errno.ENOENT, errno.errorcode[2], errno.EWOULDBLOCK == errno.EAGAIN, os.strerror(2), os.strerror(13), os.strerror(9999))
print(os.O_RDONLY, os.O_WRONLY, os.O_CREAT, os.O_EXCL, os.O_TRUNC, os.O_APPEND, os.SEEK_END, os.F_OK, os.R_OK)
print(os.stat_result.n_fields, os.stat_result.n_sequence_fields, os.stat_result.n_unnamed_fields, os.stat_result.__match_args__)
print(os.supports_follow_symlinks >= {os.stat}, os.stat in os.supports_fd)

base = 'neon_os_probe'


def rmtree(p):
    for root, dirs, files in os.walk(p, topdown=False):
        for f in files:
            os.unlink(os.path.join(root, f))
        for d in dirs:
            q = os.path.join(root, d)
            if os.path.islink(q):
                os.unlink(q)
            else:
                os.rmdir(q)
    os.rmdir(p)


if os.path.exists(base):
    rmtree(base)
os.mkdir(base)
os.chdir(base)


def part1():
    t("cwd", lambda: (os.getcwd().endswith('/' + base), os.getcwdb().endswith(b'/' + base.encode())))
    t("listdir empty", lambda: (os.listdir(), os.listdir('.'), list(os.scandir())))
    fd = os.open('a.txt', os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o644)
    t("fd", lambda: fd >= 3)
    t("write", lambda: (os.write(fd, b'hello\n'), os.write(fd, bytearray(b'world\n')), os.write(fd, memoryview(b'!!'))))
    t("read wronly", lambda: os.read(fd, 1))
    os.close(fd)
    t("close twice", lambda: os.close(fd))
    s = os.stat('a.txt')
    t("stat", lambda: (s.st_size, stat.S_ISREG(s.st_mode), stat.S_ISDIR(s.st_mode), oct(stat.S_IMODE(s.st_mode)), s[6], len(s), type(s.st_mtime).__name__, type(s[8]).__name__))
    t("stat ns", lambda: (s.st_mtime_ns // 10**9 == s[8], int(s.st_mtime) == s[8], s.st_blksize > 0))
    fd = os.open('a.txt', os.O_RDONLY)
    t("read", lambda: (os.read(fd, 3), os.read(fd, 100), os.read(fd, 10)))
    t("lseek", lambda: (os.lseek(fd, 0, os.SEEK_SET), os.read(fd, 5), os.lseek(fd, -2, os.SEEK_END), os.read(fd, 9), os.lseek(fd, 0, os.SEEK_CUR)))
    t("lseek bad", lambda: os.lseek(fd, -100, os.SEEK_SET))
    t("fstat", lambda: (os.fstat(fd).st_size, stat.S_ISREG(os.fstat(fd).st_mode)))
    t("write rdonly", lambda: os.write(fd, b'x'))
    ba = bytearray(4)
    t("readinto", lambda: (os.lseek(fd, 0, 0), os.readinto(fd, ba), ba))
    os.close(fd)
    fd = os.open('a.txt', os.O_WRONLY | os.O_APPEND)
    os.write(fd, b'+tail')
    os.close(fd)
    t("append", lambda: os.stat('a.txt').st_size)
    t("excl", lambda: os.open('a.txt', os.O_WRONLY | os.O_CREAT | os.O_EXCL))
    t("open missing", lambda: os.open('missing.txt', os.O_RDONLY))


def part2():
    os.mkdir('sub')
    os.mkdir('sub/x', 0o700)
    os.makedirs('deep/er/est')
    t("makedirs exist", lambda: os.makedirs('deep/er'))
    t("makedirs exist_ok", lambda: os.makedirs('deep/er', exist_ok=True))
    t("listdir", lambda: sorted(os.listdir('.')))
    t("listdir bytes", lambda: sorted(os.listdir(b'.')))
    t("listdir file", lambda: os.listdir('a.txt'))
    t("listdir missing", lambda: os.listdir('nope'))
    t("scandir", lambda: sorted((e.name, e.is_dir(), e.is_file(), e.is_symlink(), e.path) for e in os.scandir('.')))
    t("scandir sub", lambda: sorted((e.name, e.path, os.fspath(e)) for e in os.scandir('sub')))
    t("scandir bytes", lambda: sorted((e.name, e.path) for e in os.scandir(b'sub')))
    with os.scandir('.') as it:
        first = next(it)
    t("scandir closed", lambda: (type(first).__name__, repr(first).startswith('<DirEntry'), list(it)))
    t("walk", lambda: [(d, sorted(ds), sorted(fs)) for d, ds, fs in sorted(os.walk('.'))])
    t("walk bottom", lambda: [d for d, ds, fs in os.walk('deep', topdown=False)])
    t("exists", lambda: (os.path.exists('a.txt'), os.path.isdir('sub'), os.path.isfile('sub'), os.path.exists('nope'), os.path.getsize('a.txt'), os.path.lexists('a.txt')))
    t("mkdir exists", lambda: os.mkdir('sub'))
    t("rmdir nonempty", lambda: os.rmdir('sub'))
    t("rmdir file", lambda: os.rmdir('a.txt'))
    t("unlink dir", lambda: os.unlink('sub'))
    t("unlink missing", lambda: os.unlink('nope'))
    t("chdir file", lambda: os.chdir('a.txt'))
    t("chdir missing", lambda: os.chdir('nope'))
    t("stat missing", lambda: os.stat('nope'))
    t("stat bytes missing", lambda: os.stat(b'nope'))
    t("stat type", lambda: os.stat(1.5))
    t("stat list", lambda: os.listdir([]))
    t("stat null", lambda: os.stat('a\0b'))


def part3():
    t("rename", lambda: (os.rename('a.txt', 'b.txt'), sorted(os.listdir('.'))))
    t("replace", lambda: (os.close(os.open('c.txt', os.O_CREAT | os.O_WRONLY)), os.replace('b.txt', 'c.txt'), sorted(os.listdir('.')), os.stat('c.txt').st_size))
    t("rename missing", lambda: os.rename('nope', 'x'))
    t("rename mixed", lambda: (os.rename('c.txt', b'x'), os.rename(b'x', 'c.txt')))
    t("removedirs", lambda: (os.removedirs('deep/er/est'), os.path.exists('deep')))
    t("symlink", lambda: (os.symlink('c.txt', 'link'), os.readlink('link'), os.path.islink('link'), os.path.isfile('link'), os.lstat('link').st_size, stat.S_ISLNK(os.lstat('link').st_mode)))
    t("readlink file", lambda: os.readlink('c.txt'))
    t("dangling", lambda: (os.symlink('nowhere', 'dang'), os.path.exists('dang'), os.path.lexists('dang'), os.unlink('dang')))
    t("realpath", lambda: os.path.realpath('link') == os.path.join(os.getcwd(), 'c.txt'))
    t("utime", lambda: (os.utime('c.txt', (1000, 2000)), os.stat('c.txt').st_mtime, os.stat('c.txt').st_atime, os.path.getmtime('c.txt')))
    t("utime ns", lambda: (os.utime('c.txt', ns=(5 * 10**9, 7 * 10**9 + 5)), os.stat('c.txt').st_mtime_ns))
    t("utime both", lambda: os.utime('c.txt', (1, 2), ns=(1, 2)))
    t("chmod", lambda: (os.chmod('c.txt', 0o600), oct(stat.S_IMODE(os.stat('c.txt').st_mode))))
    t("truncate", lambda: (os.truncate('c.txt', 3), os.stat('c.txt').st_size, os.truncate('c.txt', 10), os.stat('c.txt').st_size))
    fd = os.open('c.txt', os.O_RDWR)
    t("ftruncate", lambda: (os.ftruncate(fd, 2), os.fstat(fd).st_size, os.read(fd, 10)))
    os.close(fd)
    t("access", lambda: (os.access('c.txt', os.F_OK), os.access('c.txt', os.R_OK | os.W_OK), os.access('nope', os.F_OK)))



def part4():
    class P:
        def __init__(self, p):
            self.p = p

        def __fspath__(self):
            return self.p
    t("pathlike", lambda: (os.fspath(P('c.txt')), os.stat(P('c.txt')).st_size, os.fspath(P(b'x')), os.path.join(P('a'), 'b')))
    t("fspath bad", lambda: os.fspath(P(1)))
    t("fspath int", lambda: os.fspath(1))
    t("stat pathlike bad", lambda: os.stat(P(1)))
    t("fsencode", lambda: (os.fsencode('a\udcff'), os.fsdecode(b'a\xff'), os.fsencode(P('x'))))
    t("environ", lambda: (os.environ.get('NEON_PROBE'), os.getenv('NEON_PROBE', 'd')))
    os.environ['NEON_PROBE'] = 'v1'
    t("environ set", lambda: (os.environ['NEON_PROBE'], os.environb[b'NEON_PROBE'], os.getenv('NEON_PROBE'), 'NEON_PROBE' in os.environ))
    del os.environ['NEON_PROBE']
    t("environ del", lambda: ('NEON_PROBE' in os.environ, os.environ.get('NEON_PROBE')))
    t("environ bad", lambda: os.environ.__setitem__('A', 1))
    t("putenv bad", lambda: os.putenv('A=B', 'x'))
    t("path", lambda: (os.path.join('a', 'b', '/c', 'd'), os.path.split('/a/b/c'), os.path.normpath('a//b/../c/./d'), os.path.splitext('x.tar.gz'),
                       os.path.basename('/a/b'), os.path.dirname('/a/b'), os.path.isabs('/x'), os.path.relpath('/a/b/c', '/a'),
                       os.path.commonpath(['/a/b/c', '/a/b/d']), os.path.commonprefix(['abc', 'abd']), os.path.splitroot('//a/b')))
    t("abspath", lambda: os.path.abspath('q') == os.path.join(os.getcwd(), 'q'))
    t("expandvars", lambda: os.path.expandvars('$NOPE_X/y'))
    t("urandom", lambda: (len(os.urandom(5)), type(os.urandom(0)).__name__))
    t("misc", lambda: (os.cpu_count() >= 1, type(os.getpid()).__name__, os.isatty(99), os.device_encoding(99), os.get_inheritable(1) in (True, False)))
    t("umask", lambda: (os.umask(0o22), os.umask(0o22)))
    t("terminal", lambda: os.get_terminal_size(99))
    t("stat_result", lambda: (os.stat_result(tuple(range(10))), os.stat_result(range(10)).st_atime, os.stat_result(tuple(range(12))).st_atime))
    t("stat_result bad", lambda: os.stat_result((1, 2)))
    t("stat_result repr", lambda: repr(os.stat_result(tuple(range(10)))))
    t("DirEntry new", lambda: os.DirEntry())
    t("errors", lambda: (issubclass(os.error, OSError), FileNotFoundError(2, 'x').errno, OSError(2, 'No such file', 'f').__class__.__name__))



def part5():
    # contextlib
    with contextlib.suppress(FileNotFoundError):
        os.unlink('nope')
    print("suppressed")

    @contextlib.contextmanager
    def cm(x):
        print("enter", x)
        try:
            yield x * 2
        finally:
            print("exit", x)
    with cm(3) as v:
        print("body", v)
    with contextlib.ExitStack() as stack:
        for i in range(3):
            stack.enter_context(cm(i))
        stack.callback(print, "callback")
    with contextlib.nullcontext(5) as n:
        print("null", n)

    class C:
        def close(self):
            print("closed")
    with contextlib.closing(C()):
        pass
    t("chdir cm", lambda: (contextlib.chdir('sub').__enter__(), os.getcwd().endswith('/sub'), os.chdir('..')))


def main():
    part1()
    part2()
    part3()
    part4()
    part5()


main()
os.chdir('..')
rmtree(base)
print("cleaned", os.path.exists(base))
