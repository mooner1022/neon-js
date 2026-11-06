# typing: CPython's typing.py and annotationlib over _typing (TypeVar, ParamSpec, TypeAliasType, Generic, Union).
import typing
from typing import (Any, Callable, ClassVar, Final, Generic, Literal, NamedTuple, NewType, Optional, TypedDict,
                    TypeVar, Union, cast, get_args, get_origin, get_type_hints, overload, Annotated, ParamSpec,
                    Concatenate, Self, Never, TypeAlias, Iterable, Sequence, Dict, List, Tuple)
import annotationlib


def t(label, f):
    try:
        print(label, "->", f())
    except Exception as e:
        print(label, "!!", type(e).__name__, e)


T = TypeVar('T')
K = TypeVar('K', bound=str)
V_co = TypeVar('V_co', covariant=True)
P = ParamSpec('P')


class Box(Generic[T]):
    def __init__(self, v: T) -> None:
        self.v = v


class Pair(Generic[K, T]):
    pass


class Point(NamedTuple):
    x: int
    y: int = 0


class Movie(TypedDict, total=False):
    title: str
    year: int


class Required(TypedDict):
    name: str


UserId = NewType('UserId', int)


@overload
def f(x: int) -> int: ...
@overload
def f(x: str) -> str: ...
def f(x):
    return x


def g(a: int, b: 'str' = 'x') -> Optional[list[int]]:
    return None


class C:
    a: int
    b: 'list[str]'


def annotated(x: 'Undefined', y: int):
    pass


t("typevar", lambda: (T, K, V_co, K.__bound__, V_co.__covariant__, T.__module__, P, P.args, P.kwargs))
t("variance", lambda: (repr(TypeVar('C', contravariant=True)), repr(TypeVar('I', infer_variance=True)), T.__constraints__))
t("constrained", lambda: TypeVar('S', int, str).__constraints__)
t("aliases", lambda: (List[int], Dict[str, int], Optional[int], Union[int, str], Tuple[int, ...], Callable[[int], str], Callable[..., int]))
t("origin", lambda: (get_origin(List[int]), get_args(Dict[str, int]), get_origin(Optional[int]), get_args(Union[int, str]), get_args(Callable[[int], str])))
t("union", lambda: (Union[int], Union[int, str, int], Union[int, Union[str, bytes]], Union[int, None] == Optional[int], int | str == Union[int, str]))
t("union errors", lambda: Union[1])
t("union empty", lambda: Union[()])
t("generic", lambda: (Box[int], Box(3).v, Box.__parameters__, Pair[str, int], Box.__orig_bases__))
t("namedtuple", lambda: (Point(1), Point(1, 2)._asdict(), Point._fields, Point.__annotations__, Point._field_defaults))
t("typeddict", lambda: (Movie(title='x'), Movie.__total__, Movie.__annotations__, sorted(Movie.__optional_keys__), sorted(Required.__required_keys__)))
t("typeddict check", lambda: isinstance({}, Movie))
t("newtype", lambda: (UserId(5), UserId, UserId.__supertype__, UserId.__name__))
t("literal", lambda: (Literal[1, 'a'], get_args(Literal[1, 2]), Literal[1] == Literal[1]))
t("annotated", lambda: (Annotated[int, 'meta'], get_args(Annotated[int, 'meta']), Annotated[int, 'm'].__metadata__))
t("hints", lambda: (get_type_hints(g), get_type_hints(Point), get_type_hints(Box.__init__), get_type_hints(C)))
t("hints extras", lambda: get_type_hints(Annotated, include_extras=True) if False else get_type_hints(g, include_extras=True))
t("cast", lambda: (cast(int, 'x'), cast('int', 3)))
t("overload", lambda: (f(1), [o.__name__ for o in typing.get_overloads(f)]))
t("misc", lambda: (Any, ClassVar[int], Final[int], Never, Self, Concatenate[int, P], typing.TYPE_CHECKING, Iterable[int], isinstance([], Sequence)))
t("union op", lambda: (int | str, List[int] | None))
t("type alias", lambda: (TypeAlias, typing.TypeAliasType('A', int), typing.TypeAliasType('A', int).__value__))
t("errors2", lambda: Box[int, int])
t("errors3", lambda: TypeVar('X', int))
t("forward", lambda: (typing.ForwardRef('int'), annotationlib.get_annotations(annotated, format=annotationlib.Format.FORWARDREF)))
t("ann string", lambda: annotationlib.get_annotations(annotated, format=annotationlib.Format.STRING))
t("ann value", lambda: annotationlib.get_annotations(annotated))
t("call_annotate", lambda: annotationlib.call_annotate_function(C.__annotate__, annotationlib.Format.STRING))
t("final", lambda: typing.final(C) is C)
t("assert_never", lambda: typing.assert_never(1))
