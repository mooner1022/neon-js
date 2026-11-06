# try, except, else, finally: in order
def order(fail):
    out = []
    try:
        out.append('body')
        if fail:
            raise ValueError('bad')
        out.append('body end')
    except ValueError as e:
        out.append('except ' + str(e))
    else:
        out.append('else')
    finally:
        out.append('finally')
    out.append('after')
    return out

print(order(False))
print(order(True))

# the first clause that matches; tuples; PEP 758's unparenthesized list; a bare except last
def which(exc):
    try:
        raise exc
    except (KeyError, IndexError) as e:
        return 'lookup ' + type(e).__name__
    except ZeroDivisionError, OverflowError:
        return 'arith'
    except ValueError:
        return 'value'
    except:
        return 'other'

print(which(KeyError('k')), which(IndexError), which(ZeroDivisionError()), which(OverflowError), which(ValueError), which(TypeError))
print(which(UnicodeError('u')), which(LookupError))

# exceptions the runtime raises, as objects
def caught(f):
    try:
        f()
    except Exception as e:
        return type(e).__name__ + ': ' + str(e) + ' ' + repr(e.args)

print(caught(lambda: 1 / 0))
print(caught(lambda: [][1]))
print(caught(lambda: {}['missing']))
print(caught(lambda: {}[(1, 2)]))
print(caught(lambda: int('x')))
print(caught(lambda: undefined_name))
print(caught(lambda: (1).nope))
print(caught(lambda: 'a' + 1))
print(caught(lambda: None()))
try:
    undefined_name
except NameError as e:
    print(e.name, repr(e))
try:
    (1).nope
except AttributeError as e:
    print(e.name, e.obj)

# the name of `except ... as e` is gone after its clause
try:
    raise KeyError('k')
except KeyError as e:
    print('in clause', repr(e))
try:
    print(e)
except NameError as gone:
    print('e is gone:', gone)

# an exception no clause takes goes on to the next handler out
def inner():
    try:
        raise TypeError('t')
    except ValueError:
        print('not here')
    finally:
        print('inner finally')

try:
    inner()
except TypeError as e:
    print('outer caught', e)

# bare raise: the exception handled, again; raise in a finally replaces it
def reraise():
    try:
        {}['k']
    except KeyError:
        print('handling')
        raise

try:
    reraise()
except KeyError as e:
    print('reraised', repr(e), e.args)

def replaced():
    try:
        raise KeyError('first')
    finally:
        raise ValueError('second')

try:
    replaced()
except ValueError as e:
    print(repr(e), repr(e.__context__))

# chaining: __context__, __cause__, __suppress_context__
def chaining():
    try:
        try:
            raise KeyError('a')
        except KeyError:
            raise ValueError('b')
    except ValueError as e:
        print(repr(e.__context__), e.__cause__, e.__suppress_context__)

    try:
        raise ValueError('b') from KeyError('a')
    except ValueError as e:
        print(repr(e.__context__), repr(e.__cause__), e.__suppress_context__)

    try:
        try:
            1 / 0
        except ZeroDivisionError:
            raise ValueError('c') from None
    except ValueError as e:
        print(repr(e.__context__), e.__cause__, e.__suppress_context__)

chaining()

# return, break and continue through finally
def ret():
    try:
        return 'try'
    finally:
        print('finally runs')

def override():
    try:
        return 1
    finally:
        return 2

def swallow():
    try:
        raise ValueError('lost')
    finally:
        return 'swallowed'

print(ret(), override(), swallow())

for i in range(5):
    try:
        if i == 1:
            continue
        if i == 3:
            break
        print('loop', i)
    finally:
        print('loop finally', i)

def nested_finally():
    out = []
    for i in range(3):
        try:
            try:
                if i == 1:
                    return out
                out.append(i)
            finally:
                out.append('inner')
        finally:
            out.append('outer')
    return out

r = nested_finally()
print(r)

def break_in_except():
    for i in range(3):
        try:
            raise ValueError(i)
        except ValueError as e:
            if i == 1:
                break
            print('handled', e)
    return i

print(break_in_except())

def return_in_except():
    try:
        raise KeyError('x')
    except KeyError as e:
        return 'returned ' + str(e)
    finally:
        print('cleanup')

print(return_in_except())

# a handler's own exception does not leak the one it handled
def handled_after():
    try:
        try:
            raise KeyError('k')
        except KeyError:
            pass
        raise ValueError('v')
    except ValueError as e:
        return repr(e.__context__)

print(handled_after())
