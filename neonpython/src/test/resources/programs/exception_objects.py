# classes of one's own
class AppError(Exception):
    def __init__(self, code, msg):
        super().__init__(msg)
        self.code = code

class NotFound(AppError):
    def __str__(self):
        return 'not found: ' + self.args[0]

try:
    raise NotFound(404, 'page')
except AppError as e:
    print(type(e).__name__, e.code, str(e), repr(e), e.args, isinstance(e, Exception))

class Plain(Exception):
    pass

class NoSuper(Exception):
    def __init__(self, a, b):
        self.a = a

print(repr(Plain()), repr(Plain(1)), repr(Plain(1, 'two')), str(Plain(1, 'two')), NoSuper(1, 2).args, str(NoSuper(1, 2)))
try:
    raise Plain
except Plain as e:
    print('raised the class', repr(e))

# the builtin classes' fields
print(KeyError('k').args, str(KeyError('k')), str(KeyError()), str(KeyError('a', 'b')))
e = OSError(2, 'No such file')
print(e.errno, e.strerror, str(e), e.args, FileNotFoundError.__mro__[1].__name__)
print(SystemExit(3).code, SystemExit().code, StopIteration(5).value, StopIteration().value)
e = ValueError('x')
e.add_note('first')
e.add_note('second')
e.extra = 1
print(e.__notes__, e.extra, e.with_traceback(None) is e)
e.args = (1, 2)
print(str(e), e.args, ExceptionGroup.__mro__[1].__name__)
print(issubclass(KeyError, LookupError), issubclass(BrokenPipeError, OSError), isinstance(ZeroDivisionError(), ArithmeticError))

# an iterator of one's own: StopIteration ends the for
class Countdown:
    def __init__(self, n):
        self.n = n

    def __iter__(self):
        return self

    def __next__(self):
        if self.n == 0:
            raise StopIteration
        self.n -= 1
        return self.n

print(list(Countdown(3)))
total = 0
for x in Countdown(5):
    total += x
print(total, next(Countdown(0), 'empty'))

# errors in loops, caught each time
results = []
for v in [1, 0, 'x', 2]:
    try:
        results.append(10 // v)
    except ZeroDivisionError:
        results.append('div0')
    except TypeError as err:
        results.append('type')
print(results)

def deep(n):
    return deep(n + 1)

try:
    deep(0)
except RecursionError as e:
    print('recursion', e)
