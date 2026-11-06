# ExceptionGroup: its message, its exceptions, str and repr; BaseExceptionGroup of Exceptions is one
eg = ExceptionGroup('g', [ValueError(1), TypeError(2)])
print(eg.message, eg.exceptions, eg.args, str(eg), repr(eg))
print(type(BaseExceptionGroup('b', [ValueError()])).__name__, type(BaseExceptionGroup('b', [KeyboardInterrupt()])).__name__)
print(str(ExceptionGroup('one', (KeyError('k'),))), isinstance(eg, Exception), isinstance(eg, BaseExceptionGroup))

# split and subgroup keep the group's shape; derive makes the parts
nested = ExceptionGroup('g', [ValueError(1), ExceptionGroup('n', [TypeError(2), ValueError(3)])])
m, r = nested.split(ValueError)
print(repr(m))
print(repr(r))
print(repr(nested.subgroup(TypeError)), nested.subgroup(KeyError), nested.split(ExceptionGroup)[0] is nested)
m, r = nested.split(lambda e: isinstance(e, ValueError) and e.args[0] > 1)
print(repr(m), repr(r))
print(repr(nested.split((TypeError, KeyError))[0]))
print(repr(eg.derive([KeyError('d')])))
eg.add_note('a note')
m, r = eg.split(ValueError)
print(m.__notes__, r.__notes__)

class MyGroup(ExceptionGroup):
    pass

mine = MyGroup('mine', [ValueError(1), TypeError(2)])
m, r = mine.split(ValueError)
print(type(mine).__name__, type(m).__name__, repr(m))

# except*: each clause takes its part, the rest goes on
def handle(exc):
    try:
        raise exc
    except* ValueError as e:
        print('  values', repr(e))
    except* TypeError as e:
        print('  types', repr(e))

for exc in [ExceptionGroup('g', [ValueError(1), TypeError(2), ValueError(3)]),
            ExceptionGroup('g', [ValueError(1)]),
            ValueError('naked'),
            nested]:
    print('handling', repr(exc))
    handle(exc)

def unhandled(exc):
    try:
        try:
            raise exc
        except* ValueError as e:
            print('  took', repr(e))
    except BaseException as left:
        return repr(left)
    return 'all handled'

print(unhandled(ExceptionGroup('g', [ValueError(1), KeyError(2)])))
print(unhandled(KeyError('naked, unmatched')))
print(unhandled(ValueError('naked, matched')))

# raising in a clause: again (the match), or anew; one alone as it is, more in a group
def reraising(exc, kind):
    try:
        try:
            raise exc
        except* ValueError as e:
            if kind == 'again':
                raise
            if kind == 'new':
                raise KeyError('new')
        except* TypeError:
            if kind == 'new':
                raise IndexError('other')
    except BaseException as out:
        return type(out).__name__ + ' ' + repr(out)
    return 'nothing'

g = ExceptionGroup('g', [ValueError(1), TypeError(2)])
print(reraising(g, 'again'))
print(reraising(g, 'new'))
print(reraising(ExceptionGroup('g', [ValueError(1)]), 'new'))
print(reraising(ValueError('v'), 'again'))
print(reraising(ValueError('v'), 'new'))
print(reraising(ExceptionGroup('g', [ValueError(1)]), 'nothing'))

# with else and finally; the match is the exception handled in its clause
try:
    pass
except* ValueError:
    print('not run')
else:
    print('else runs')
finally:
    print('finally runs')

try:
    raise ExceptionGroup('g', [ValueError(1)])
except* ValueError:
    try:
        raise KeyError('inner')
    except KeyError as k:
        print('context', repr(k.__context__))

def catching_group():
    try:
        raise ValueError(1)
    except* ExceptionGroup:
        pass

try:
    catching_group()
except TypeError as e:
    print(e)
