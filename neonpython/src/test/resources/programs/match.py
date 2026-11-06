# literals, captures, wildcards, guards, or-patterns
def describe(v):
    match v:
        case 0:
            return 'zero'
        case 1 | 2 | 3:
            return 'small'
        case -1:
            return 'minus one'
        case 1.5 | 2.5:
            return 'a half'
        case 'hi' | 'hello':
            return 'greeting'
        case None:
            return 'nothing'
        case True:
            return 'true itself'
        case 1+2j:
            return 'complex'
        case int(n) if n > 100:
            return 'big ' + str(n)
        case str() as s:
            return 'other str ' + s
        case _:
            return 'something else'

for v in [0, 2, -1, 2.5, 'hello', None, True, 1 + 2j, 500, 'x', 50, [1]]:
    print(repr(v), describe(v))

# sequences: lengths, stars, nesting, what is a sequence
def shape(v):
    match v:
        case []:
            return 'empty'
        case [x]:
            return 'one: ' + repr(x)
        case [x, y]:
            return 'two: ' + repr(x) + ' ' + repr(y)
        case [first, *mid, last] if len(mid) > 1:
            return 'many: ' + repr(first) + ' ' + repr(mid) + ' ' + repr(last)
        case (a, *rest):
            return 'head ' + repr(a) + ' rest ' + repr(rest) + ' ' + type(rest).__name__
        case _:
            return 'not a sequence'

for v in [[], [1], (1, 2), [1, 2, 3, 4], (1, 2, 3), 'ab', b'ab', {1: 2}, {1, 2}, range(2), range(5)]:
    print(repr(v), shape(v))

match [1, [2, 3]]:
    case [x, [y, *_] as inner]:
        print('nested', x, y, inner)

match (1, 'x'):
    case (1 | 2 as n, 'x' | 'y' as s):
        print('or with as', n, s)

# mappings: keys, extra keys, **rest, a dict's subclass by get
def config(c):
    match c:
        case {'mode': 'fast', **rest}:
            return 'fast with ' + repr(rest)
        case {'mode': mode, 'level': int(level)}:
            return mode + ' at ' + str(level)
        case {'mode': _}:
            return 'some mode'
        case {}:
            return 'a mapping'
        case _:
            return 'not a mapping'

for c in [{'mode': 'fast', 'x': 1}, {'mode': 'slow', 'level': 3}, {'mode': 'slow', 'level': 'high'}, {}, [], {'other': 1}]:
    print(repr(c), config(c))

class D(dict):
    def __missing__(self, k):
        return 'missing'

match D():
    case {'k': v}:
        print('matched', v)
    case _:
        print('a mapping pattern uses get, not []')

# classes: keywords, __match_args__, the builtins that match themselves
class Point:
    __match_args__ = ('x', 'y')

    def __init__(self, x, y):
        self.x = x
        self.y = y

def where(p):
    match p:
        case Point(0, 0):
            return 'origin'
        case Point(x=0, y=y):
            return 'on the y axis at ' + str(y)
        case Point(x, 0):
            return 'on the x axis at ' + str(x)
        case Point(x, y) if x == y:
            return 'diagonal'
        case Point():
            return 'somewhere'
        case _:
            return 'not a point'

for p in [Point(0, 0), Point(0, 5), Point(3, 0), Point(2, 2), Point(1, 9), 'p']:
    print(where(p))

class Q:
    def __init__(self):
        self.a = 1

match Q():
    case Q(a=1, b=2):
        print('no b')
    case Q(a=a):
        print('a is', a)

for v in [5, 'str', 2.5, [1], (1,), {1: 1}, True]:
    match v:
        case bool(b):
            print('bool', b)
        case int(i):
            print('int', i)
        case float(f) | str(f):
            print('float or str', f)
        case list(l) | tuple(l):
            print('list or tuple', l)
        case dict(d):
            print('dict', d)

# dotted values, the subject a tuple, captures only once the pattern matched, guards keep theirs
class Color:
    RED = 'red'
    BLUE = 'blue'

match 'blue':
    case Color.RED:
        print('red')
    case Color.BLUE:
        print('blue by a dotted name')

match 1, 2:
    case (a, b):
        print('tuple subject', a, b)

match [1, 2]:
    case [first, 3]:
        pass
    case _:
        pass
try:
    print(first)
except NameError as e:
    print('not bound:', e)

match [1, 2]:
    case [g1, g2] if g1 > 5:
        pass
    case _:
        print('bound by the case whose guard failed:', g1, g2)

match 3:
    case 1:
        print('no')
print('no case matched: on we go')
match = 'match is a soft keyword'
print(match)
