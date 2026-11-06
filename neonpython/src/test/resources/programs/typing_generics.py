# typing's generics: Generic and its subscriptions typing.py's, types.GenericAlias's and unions' parameters and substitution (genericaliasobject.c's).
import typing
from typing import Generic, TypeVar, ParamSpec, TypeVarTuple, List, Dict, Callable, Unpack, Optional, Union


def t(label, f):
    try:
        print(label, "->", f())
    except Exception as e:
        print(label, "!!", type(e).__name__, e)


T = TypeVar('T')
S = TypeVar('S')
K = TypeVar('K', bound=str)
D = TypeVar('D', default=int)
P = ParamSpec('P')
Ts = TypeVarTuple('Ts')


class Box(Generic[T]):
    pass


class Pair(Generic[K, T]):
    pass


class WithDefault(Generic[T, D]):
    pass


class Fn(Generic[P, T]):
    pass


class Arr(Generic[*Ts]):
    pass


class Arr2(Generic[T, *Ts]):
    pass


class New[X, *Ys, **Q]:
    pass


class NewD[X = str]:
    pass


type Alias[X] = list[X]
type TAlias[*Xs] = tuple[*Xs]


t("sub", lambda: (Pair[str, T][int], Box[List[T]][int], Box[Dict[S, T]][str, int]))
t("ga nested", lambda: (list[Box[T]][int], dict[str, Box[T]][int], list[typing.List[T]].__parameters__, list[List[T]][int]))
t("ga params", lambda: (list[T].__parameters__, dict[T, list[S]].__parameters__, dict[T, list[S]][str, int], list[[T]][str] if False else 0))
t("union", lambda: ((int | Box[T]).__parameters__, (int | Box[T])[str], Optional[Box[T]][int], Union[T, S][int, str]))
t("default", lambda: (WithDefault[int], WithDefault[int, str], WithDefault.__parameters__, D.__default__))
t("paramspec", lambda: (Fn[[int, str], bool], Fn[..., bool], Fn[P, T][[int], str], Fn.__parameters__))
t("tvt", lambda: (Arr[int, str], Arr[()], Arr2[int, str, bytes], Arr.__parameters__, Arr2.__parameters__, Arr[*tuple[int, ...]]))
t("unpack", lambda: (list(iter(Ts)), [*Ts], Unpack[Ts], tuple[*Ts], tuple[*Ts][int, str], Unpack[Ts].__args__))
t("pep695", lambda: (New.__type_params__, New.__parameters__, New[int, str, bytes, [float]], NewD[int], NewD.__parameters__))
t("pep695 orig", lambda: (New.__orig_bases__, New.__mro__, NewD[()] if False else 0))
t("alias", lambda: (Alias[int], Alias.__parameters__, TAlias.__parameters__, TAlias[int, str], list(iter(Alias))))
t("isinstance", lambda: isinstance(1, Box[int]))
t("issubclass", lambda: issubclass(int, Box[int]))
t("instance", lambda: (Box[int]().__orig_class__, type(Box[int]())))
t("errors1", lambda: Box[int, int])
t("errors2", lambda: Pair[int])
t("errors3", lambda: Generic[int])
t("errors4", lambda: Generic[T, T])
t("errors5", lambda: list[int][int])
t("errors6", lambda: Box[int][int])
t("errors7", lambda: Generic[()])
t("errors8", lambda: type('X', (Generic,), {}))
t("errors9", lambda: Ts[int])
t("subst", lambda: (T.__typing_subst__(int), P.__typing_subst__([int]), T.__typing_subst__(1)))
t("subst2", lambda: Ts.__typing_subst__(int))
t("prepare", lambda: (T.__typing_prepare_subst__(Box, (int,)), D.__typing_prepare_subst__(WithDefault, (int,))))
t("generic mro", lambda: (Box.__mro__, Pair.__orig_bases__, Box[int].__mro_entries__((Box[int],))))
t("callable", lambda: (Callable[[T], S][int, str], Callable[P, T][[int], str], Callable[..., T][int]))
t("eq", lambda: (Box[int] == Box[int], hash(Box[int]) == hash(Box[int]), Box[int] is Box[int]))


class Sub(Box[int]):
    pass


class Sub2(Box[T], Generic[T]):
    pass


t("sub classes", lambda: (Sub.__orig_bases__, Sub.__parameters__, Sub2.__parameters__, Sub2[str]))
