# CPython's enum, bundled: Enum, IntEnum, Flag, StrEnum, auto, unique, the functional API, members with methods.
import enum
from enum import Enum, IntEnum, Flag, IntFlag, StrEnum, auto, unique


def t(label, f):
    try:
        print(label, "->", f())
    except Exception as e:
        print(label, "!!", type(e).__name__, e)


class Color(Enum):
    RED = 1
    GREEN = 2
    BLUE = 3


class Num(IntEnum):
    ONE = 1
    TWO = 2


class Perm(Flag):
    R = auto()
    W = auto()
    X = auto()


class Mode(StrEnum):
    A = auto()
    B = 'bee'


def main():
    t("basic", lambda: (Color.RED, repr(Color.GREEN), Color(3), Color['BLUE'], Color.RED.name, Color.RED.value, list(Color), len(Color)))
    t("identity", lambda: (Color(1) is Color.RED, Color.RED == Color.RED, Color.RED != Color.BLUE, isinstance(Color.RED, Color), type(Color.RED).__name__))
    t("bad value", lambda: Color(9))
    t("bad name", lambda: Color['PINK'])
    t("members", lambda: (list(Color.__members__), type(Color.__members__).__name__, Color.RED in Color, 1 in Color))
    t("intenum", lambda: (Num.ONE + 1, Num.TWO * 2, Num(2), Num.ONE < Num.TWO, int(Num.TWO), str(Num.ONE), format(Num.TWO), repr(Num.ONE)))
    t("flag", lambda: (Perm.R | Perm.W, (Perm.R | Perm.W).value, Perm.R in (Perm.R | Perm.X), list(Perm.R | Perm.X), repr(Perm(0)), Perm(7)))
    t("strenum", lambda: (Mode.A, Mode.A == 'a', Mode.B.upper(), str(Mode.B), repr(Mode.A)))
    t("auto", lambda: [m.value for m in Perm])

    @unique
    class U(Enum):
        X = 1

    t("unique", lambda: U.X)
    t("unique dup", lambda: unique(Enum('D', [('A', 1), ('B', 1)])))
    t("functional", lambda: (list(Enum('Animal', 'ANT BEE CAT')), Enum('V', {'X': 10}).X.value))
    t("alias", lambda: (lambda E: (E.B is E.A, list(E), list(E.__members__)))(Enum('E', [('A', 1), ('B', 1)])))
    t("dup name", lambda: Enum('Z', [('A', 1), ('A', 2)]))

    class Planet(Enum):
        EARTH = (5.97, 6.37)

        def __init__(self, mass, radius):
            self.mass = mass
            self.radius = radius

        @property
        def ratio(self):
            return round(self.mass / self.radius, 3)

    t("methods", lambda: (Planet.EARTH.mass, Planet.EARTH.ratio, Planet.EARTH.value))

    class Shape(Enum):
        SQUARE = 2

        @classmethod
        def default(cls):
            return cls.SQUARE

        def describe(self):
            return f'{self.name}={self.value}'

    t("cls methods", lambda: (Shape.default(), Shape.SQUARE.describe(), hash(Shape.SQUARE) == hash(Shape.SQUARE)))
    t("subclass err", lambda: type('Sub', (Color,), {}))
    t("pickle-ish", lambda: (Color.RED.__reduce_ex__(4), __import__('copy').copy(Color.RED) is Color.RED))
    t("match", lambda: [{Color.RED: 'r', Color.GREEN: 'g'}.get(c, '?') for c in Color])
    t("dir", lambda: sorted(n for n in dir(Color.RED) if not n.startswith('_')))
    t("missing", lambda: (lambda E: E(5))(type('M', (Enum,), {'A': 1, '_missing_': classmethod(lambda cls, v: cls.A)})) if False else None)


main()
