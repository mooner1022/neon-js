"""NeonPy's _ast: Python's syntax tree classes, generated from CPython 3.14.6's Parser/Python.asdl by
neonpython/tools/gen_ast.py (CPython makes them in C, Python/Python-ast.c)."""

PyCF_ONLY_AST = 1024
PyCF_TYPE_COMMENTS = 4096
PyCF_ALLOW_TOP_LEVEL_AWAIT = 8192
PyCF_OPTIMIZED_AST = 33792


def _warn(msg):
    import warnings
    warnings.warn(msg, DeprecationWarning, stacklevel=3)


def _names(names):
    return ', '.join(sorted(repr(n) for n in names))


def _repr(value, depth):
    if isinstance(value, (list, tuple)):
        if not value:
            return repr(value)
        first = value[0]
        parts = [first._repr(depth - 1) if isinstance(first, AST) else repr(first)]
        if len(value) > 2:
            parts.append('...')
        if len(value) > 1:
            last = value[-1]
            parts.append(last._repr(depth - 1) if isinstance(last, AST) else repr(last))
        return ('[%s]' if isinstance(value, list) else '(%s)') % ', '.join(parts)
    if isinstance(value, AST):
        return value._repr(depth - 1)
    return repr(value)


_repr_active = set()


class AST:
    _fields = ()
    _attributes = ()
    __match_args__ = ()

    def __init__(self, *args, **kwargs):
        cls = type(self)
        name = cls.__name__
        fields = cls._fields
        if len(args) > len(fields):
            raise TypeError(f"{name} constructor takes at most {len(fields)} positional argument{'' if len(fields) == 1 else 's'}")
        remaining = list(fields)
        for field, value in zip(fields, args):
            setattr(self, field, value)
            remaining.remove(field)
        for key, value in kwargs.items():
            if key in fields:
                if key not in remaining:
                    raise TypeError(f"{name} got multiple values for argument {key!r}")
                remaining.remove(key)
            elif key not in cls._attributes:
                _warn(f"{name}.__init__ got an unexpected keyword argument {key!r}. "
                      "Support for arbitrary keyword arguments is deprecated and will be removed in Python 3.15.")
            setattr(self, key, value)
        if remaining:
            types = getattr(cls, '_field_types', None)
            if types is None:
                return
            for field in remaining:
                kind = getattr(cls, '_field_kinds', {}).get(field)
                if field not in types:
                    _warn(f"Field {field!r} is missing from {name}._field_types. This will become an error in Python 3.15.")
                elif kind == '?':
                    pass
                elif kind == '*':
                    setattr(self, field, [])
                elif kind == 'ctx':
                    setattr(self, field, _LOAD)
                else:
                    _warn(f"{name}.__init__ missing 1 required positional argument: {field!r}. "
                          "This will become an error in Python 3.15.")

    def __reduce__(self):
        d = getattr(self, '__dict__', None)
        if d is None:
            return type(self), ()
        fields = getattr(type(self), '_fields', None)
        if fields is None:
            return type(self), (), d
        args = []
        for field in fields:
            if field not in d:
                break
            args.append(None)
        return type(self), tuple(args), d

    def __replace__(self, /, **kwargs):
        cls = type(self)
        name = cls.__name__
        fields = getattr(cls, '_fields', ())
        attributes = getattr(cls, '_attributes', ())
        expecting = set(fields) | set(attributes)
        for key in kwargs:
            if key not in expecting:
                raise TypeError(f"{name}.__replace__ got an unexpected keyword argument {key!r}.")
            expecting.discard(key)
        d = getattr(self, '__dict__', {})
        expecting -= set(d)
        expecting -= set(attributes)
        kinds = getattr(cls, '_field_kinds', {})
        expecting = {f for f in expecting if kinds.get(f) != '?'}
        if expecting:
            m = len(expecting)
            raise TypeError(f"{name}.__replace__ missing {m} keyword argument{'' if m == 1 else 's'}: {_names(expecting)}.")
        payload = {}
        for key in (*fields, *attributes):
            if key in d:
                payload[key] = d[key]
        payload.update(kwargs)
        return cls(**payload)

    def _repr(self, depth):
        name = type(self).__name__
        if depth <= 0 or id(self) in _repr_active:
            return f'{name}(...)'
        fields = type(self)._fields
        if not fields:
            return f'{name}()'
        _repr_active.add(id(self))
        try:
            return '%s(%s)' % (name, ', '.join(f'{f}={_repr(getattr(self, f), depth)}' for f in fields))
        finally:
            _repr_active.discard(id(self))

    def __repr__(self):
        return self._repr(3)

class mod(AST):
    __doc__ = 'mod = Module(stmt* body, type_ignore* type_ignores)\n    | Interactive(stmt* body)\n    | Expression(expr body)\n    | FunctionType(expr* argtypes, expr returns)'
    __module__ = "ast"
    _attributes = ()


class Module(mod):
    __doc__ = 'Module(stmt* body, type_ignore* type_ignores)'
    __module__ = "ast"
    _fields = ('body', 'type_ignores')
    __match_args__ = ('body', 'type_ignores')
    _field_kinds = {'body': '*', 'type_ignores': '*'}


class Interactive(mod):
    __doc__ = 'Interactive(stmt* body)'
    __module__ = "ast"
    _fields = ('body',)
    __match_args__ = ('body',)
    _field_kinds = {'body': '*'}


class Expression(mod):
    __doc__ = 'Expression(expr body)'
    __module__ = "ast"
    _fields = ('body',)
    __match_args__ = ('body',)
    _field_kinds = {'body': ''}


class FunctionType(mod):
    __doc__ = 'FunctionType(expr* argtypes, expr returns)'
    __module__ = "ast"
    _fields = ('argtypes', 'returns')
    __match_args__ = ('argtypes', 'returns')
    _field_kinds = {'argtypes': '*', 'returns': ''}


class stmt(AST):
    __doc__ = 'stmt = FunctionDef(identifier name, arguments args, stmt* body, expr* decorator_list, expr? returns, string? type_comment, type_param* type_params)\n     | AsyncFunctionDef(identifier name, arguments args, stmt* body, expr* decorator_list, expr? returns, string? type_comment, type_param* type_params)\n     | ClassDef(identifier name, expr* bases, keyword* keywords, stmt* body, expr* decorator_list, type_param* type_params)\n     | Return(expr? value)\n     | Delete(expr* targets)\n     | Assign(expr* targets, expr value, string? type_comment)\n     | TypeAlias(expr name, type_param* type_params, expr value)\n     | AugAssign(expr target, operator op, expr value)\n     | AnnAssign(expr target, expr annotation, expr? value, int simple)\n     | For(expr target, expr iter, stmt* body, stmt* orelse, string? type_comment)\n     | AsyncFor(expr target, expr iter, stmt* body, stmt* orelse, string? type_comment)\n     | While(expr test, stmt* body, stmt* orelse)\n     | If(expr test, stmt* body, stmt* orelse)\n     | With(withitem* items, stmt* body, string? type_comment)\n     | AsyncWith(withitem* items, stmt* body, string? type_comment)\n     | Match(expr subject, match_case* cases)\n     | Raise(expr? exc, expr? cause)\n     | Try(stmt* body, excepthandler* handlers, stmt* orelse, stmt* finalbody)\n     | TryStar(stmt* body, excepthandler* handlers, stmt* orelse, stmt* finalbody)\n     | Assert(expr test, expr? msg)\n     | Import(alias* names)\n     | ImportFrom(identifier? module, alias* names, int? level)\n     | Global(identifier* names)\n     | Nonlocal(identifier* names)\n     | Expr(expr value)\n     | Pass\n     | Break\n     | Continue'
    __module__ = "ast"
    _attributes = ('lineno', 'col_offset', 'end_lineno', 'end_col_offset')
    end_lineno = None
    end_col_offset = None


class FunctionDef(stmt):
    __doc__ = 'FunctionDef(identifier name, arguments args, stmt* body, expr* decorator_list, expr? returns, string? type_comment, type_param* type_params)'
    __module__ = "ast"
    _fields = ('name', 'args', 'body', 'decorator_list', 'returns', 'type_comment', 'type_params')
    __match_args__ = ('name', 'args', 'body', 'decorator_list', 'returns', 'type_comment', 'type_params')
    _field_kinds = {'name': '', 'args': '', 'body': '*', 'decorator_list': '*', 'returns': '?', 'type_comment': '?', 'type_params': '*'}
    returns = None
    type_comment = None


class AsyncFunctionDef(stmt):
    __doc__ = 'AsyncFunctionDef(identifier name, arguments args, stmt* body, expr* decorator_list, expr? returns, string? type_comment, type_param* type_params)'
    __module__ = "ast"
    _fields = ('name', 'args', 'body', 'decorator_list', 'returns', 'type_comment', 'type_params')
    __match_args__ = ('name', 'args', 'body', 'decorator_list', 'returns', 'type_comment', 'type_params')
    _field_kinds = {'name': '', 'args': '', 'body': '*', 'decorator_list': '*', 'returns': '?', 'type_comment': '?', 'type_params': '*'}
    returns = None
    type_comment = None


class ClassDef(stmt):
    __doc__ = 'ClassDef(identifier name, expr* bases, keyword* keywords, stmt* body, expr* decorator_list, type_param* type_params)'
    __module__ = "ast"
    _fields = ('name', 'bases', 'keywords', 'body', 'decorator_list', 'type_params')
    __match_args__ = ('name', 'bases', 'keywords', 'body', 'decorator_list', 'type_params')
    _field_kinds = {'name': '', 'bases': '*', 'keywords': '*', 'body': '*', 'decorator_list': '*', 'type_params': '*'}


class Return(stmt):
    __doc__ = 'Return(expr? value)'
    __module__ = "ast"
    _fields = ('value',)
    __match_args__ = ('value',)
    _field_kinds = {'value': '?'}
    value = None


class Delete(stmt):
    __doc__ = 'Delete(expr* targets)'
    __module__ = "ast"
    _fields = ('targets',)
    __match_args__ = ('targets',)
    _field_kinds = {'targets': '*'}


class Assign(stmt):
    __doc__ = 'Assign(expr* targets, expr value, string? type_comment)'
    __module__ = "ast"
    _fields = ('targets', 'value', 'type_comment')
    __match_args__ = ('targets', 'value', 'type_comment')
    _field_kinds = {'targets': '*', 'value': '', 'type_comment': '?'}
    type_comment = None


class TypeAlias(stmt):
    __doc__ = 'TypeAlias(expr name, type_param* type_params, expr value)'
    __module__ = "ast"
    _fields = ('name', 'type_params', 'value')
    __match_args__ = ('name', 'type_params', 'value')
    _field_kinds = {'name': '', 'type_params': '*', 'value': ''}


class AugAssign(stmt):
    __doc__ = 'AugAssign(expr target, operator op, expr value)'
    __module__ = "ast"
    _fields = ('target', 'op', 'value')
    __match_args__ = ('target', 'op', 'value')
    _field_kinds = {'target': '', 'op': '', 'value': ''}


class AnnAssign(stmt):
    __doc__ = 'AnnAssign(expr target, expr annotation, expr? value, int simple)'
    __module__ = "ast"
    _fields = ('target', 'annotation', 'value', 'simple')
    __match_args__ = ('target', 'annotation', 'value', 'simple')
    _field_kinds = {'target': '', 'annotation': '', 'value': '?', 'simple': ''}
    value = None


class For(stmt):
    __doc__ = 'For(expr target, expr iter, stmt* body, stmt* orelse, string? type_comment)'
    __module__ = "ast"
    _fields = ('target', 'iter', 'body', 'orelse', 'type_comment')
    __match_args__ = ('target', 'iter', 'body', 'orelse', 'type_comment')
    _field_kinds = {'target': '', 'iter': '', 'body': '*', 'orelse': '*', 'type_comment': '?'}
    type_comment = None


class AsyncFor(stmt):
    __doc__ = 'AsyncFor(expr target, expr iter, stmt* body, stmt* orelse, string? type_comment)'
    __module__ = "ast"
    _fields = ('target', 'iter', 'body', 'orelse', 'type_comment')
    __match_args__ = ('target', 'iter', 'body', 'orelse', 'type_comment')
    _field_kinds = {'target': '', 'iter': '', 'body': '*', 'orelse': '*', 'type_comment': '?'}
    type_comment = None


class While(stmt):
    __doc__ = 'While(expr test, stmt* body, stmt* orelse)'
    __module__ = "ast"
    _fields = ('test', 'body', 'orelse')
    __match_args__ = ('test', 'body', 'orelse')
    _field_kinds = {'test': '', 'body': '*', 'orelse': '*'}


class If(stmt):
    __doc__ = 'If(expr test, stmt* body, stmt* orelse)'
    __module__ = "ast"
    _fields = ('test', 'body', 'orelse')
    __match_args__ = ('test', 'body', 'orelse')
    _field_kinds = {'test': '', 'body': '*', 'orelse': '*'}


class With(stmt):
    __doc__ = 'With(withitem* items, stmt* body, string? type_comment)'
    __module__ = "ast"
    _fields = ('items', 'body', 'type_comment')
    __match_args__ = ('items', 'body', 'type_comment')
    _field_kinds = {'items': '*', 'body': '*', 'type_comment': '?'}
    type_comment = None


class AsyncWith(stmt):
    __doc__ = 'AsyncWith(withitem* items, stmt* body, string? type_comment)'
    __module__ = "ast"
    _fields = ('items', 'body', 'type_comment')
    __match_args__ = ('items', 'body', 'type_comment')
    _field_kinds = {'items': '*', 'body': '*', 'type_comment': '?'}
    type_comment = None


class Match(stmt):
    __doc__ = 'Match(expr subject, match_case* cases)'
    __module__ = "ast"
    _fields = ('subject', 'cases')
    __match_args__ = ('subject', 'cases')
    _field_kinds = {'subject': '', 'cases': '*'}


class Raise(stmt):
    __doc__ = 'Raise(expr? exc, expr? cause)'
    __module__ = "ast"
    _fields = ('exc', 'cause')
    __match_args__ = ('exc', 'cause')
    _field_kinds = {'exc': '?', 'cause': '?'}
    exc = None
    cause = None


class Try(stmt):
    __doc__ = 'Try(stmt* body, excepthandler* handlers, stmt* orelse, stmt* finalbody)'
    __module__ = "ast"
    _fields = ('body', 'handlers', 'orelse', 'finalbody')
    __match_args__ = ('body', 'handlers', 'orelse', 'finalbody')
    _field_kinds = {'body': '*', 'handlers': '*', 'orelse': '*', 'finalbody': '*'}


class TryStar(stmt):
    __doc__ = 'TryStar(stmt* body, excepthandler* handlers, stmt* orelse, stmt* finalbody)'
    __module__ = "ast"
    _fields = ('body', 'handlers', 'orelse', 'finalbody')
    __match_args__ = ('body', 'handlers', 'orelse', 'finalbody')
    _field_kinds = {'body': '*', 'handlers': '*', 'orelse': '*', 'finalbody': '*'}


class Assert(stmt):
    __doc__ = 'Assert(expr test, expr? msg)'
    __module__ = "ast"
    _fields = ('test', 'msg')
    __match_args__ = ('test', 'msg')
    _field_kinds = {'test': '', 'msg': '?'}
    msg = None


class Import(stmt):
    __doc__ = 'Import(alias* names)'
    __module__ = "ast"
    _fields = ('names',)
    __match_args__ = ('names',)
    _field_kinds = {'names': '*'}


class ImportFrom(stmt):
    __doc__ = 'ImportFrom(identifier? module, alias* names, int? level)'
    __module__ = "ast"
    _fields = ('module', 'names', 'level')
    __match_args__ = ('module', 'names', 'level')
    _field_kinds = {'module': '?', 'names': '*', 'level': '?'}
    module = None
    level = None


class Global(stmt):
    __doc__ = 'Global(identifier* names)'
    __module__ = "ast"
    _fields = ('names',)
    __match_args__ = ('names',)
    _field_kinds = {'names': '*'}


class Nonlocal(stmt):
    __doc__ = 'Nonlocal(identifier* names)'
    __module__ = "ast"
    _fields = ('names',)
    __match_args__ = ('names',)
    _field_kinds = {'names': '*'}


class Expr(stmt):
    __doc__ = 'Expr(expr value)'
    __module__ = "ast"
    _fields = ('value',)
    __match_args__ = ('value',)
    _field_kinds = {'value': ''}


class Pass(stmt):
    __doc__ = 'Pass'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class Break(stmt):
    __doc__ = 'Break'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class Continue(stmt):
    __doc__ = 'Continue'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class expr(AST):
    __doc__ = 'expr = BoolOp(boolop op, expr* values)\n     | NamedExpr(expr target, expr value)\n     | BinOp(expr left, operator op, expr right)\n     | UnaryOp(unaryop op, expr operand)\n     | Lambda(arguments args, expr body)\n     | IfExp(expr test, expr body, expr orelse)\n     | Dict(expr?* keys, expr* values)\n     | Set(expr* elts)\n     | ListComp(expr elt, comprehension* generators)\n     | SetComp(expr elt, comprehension* generators)\n     | DictComp(expr key, expr value, comprehension* generators)\n     | GeneratorExp(expr elt, comprehension* generators)\n     | Await(expr value)\n     | Yield(expr? value)\n     | YieldFrom(expr value)\n     | Compare(expr left, cmpop* ops, expr* comparators)\n     | Call(expr func, expr* args, keyword* keywords)\n     | FormattedValue(expr value, int conversion, expr? format_spec)\n     | Interpolation(expr value, constant str, int conversion, expr? format_spec)\n     | JoinedStr(expr* values)\n     | TemplateStr(expr* values)\n     | Constant(constant value, string? kind)\n     | Attribute(expr value, identifier attr, expr_context ctx)\n     | Subscript(expr value, expr slice, expr_context ctx)\n     | Starred(expr value, expr_context ctx)\n     | Name(identifier id, expr_context ctx)\n     | List(expr* elts, expr_context ctx)\n     | Tuple(expr* elts, expr_context ctx)\n     | Slice(expr? lower, expr? upper, expr? step)'
    __module__ = "ast"
    _attributes = ('lineno', 'col_offset', 'end_lineno', 'end_col_offset')
    end_lineno = None
    end_col_offset = None


class BoolOp(expr):
    __doc__ = 'BoolOp(boolop op, expr* values)'
    __module__ = "ast"
    _fields = ('op', 'values')
    __match_args__ = ('op', 'values')
    _field_kinds = {'op': '', 'values': '*'}


class NamedExpr(expr):
    __doc__ = 'NamedExpr(expr target, expr value)'
    __module__ = "ast"
    _fields = ('target', 'value')
    __match_args__ = ('target', 'value')
    _field_kinds = {'target': '', 'value': ''}


class BinOp(expr):
    __doc__ = 'BinOp(expr left, operator op, expr right)'
    __module__ = "ast"
    _fields = ('left', 'op', 'right')
    __match_args__ = ('left', 'op', 'right')
    _field_kinds = {'left': '', 'op': '', 'right': ''}


class UnaryOp(expr):
    __doc__ = 'UnaryOp(unaryop op, expr operand)'
    __module__ = "ast"
    _fields = ('op', 'operand')
    __match_args__ = ('op', 'operand')
    _field_kinds = {'op': '', 'operand': ''}


class Lambda(expr):
    __doc__ = 'Lambda(arguments args, expr body)'
    __module__ = "ast"
    _fields = ('args', 'body')
    __match_args__ = ('args', 'body')
    _field_kinds = {'args': '', 'body': ''}


class IfExp(expr):
    __doc__ = 'IfExp(expr test, expr body, expr orelse)'
    __module__ = "ast"
    _fields = ('test', 'body', 'orelse')
    __match_args__ = ('test', 'body', 'orelse')
    _field_kinds = {'test': '', 'body': '', 'orelse': ''}


class Dict(expr):
    __doc__ = 'Dict(expr?* keys, expr* values)'
    __module__ = "ast"
    _fields = ('keys', 'values')
    __match_args__ = ('keys', 'values')
    _field_kinds = {'keys': '*', 'values': '*'}


class Set(expr):
    __doc__ = 'Set(expr* elts)'
    __module__ = "ast"
    _fields = ('elts',)
    __match_args__ = ('elts',)
    _field_kinds = {'elts': '*'}


class ListComp(expr):
    __doc__ = 'ListComp(expr elt, comprehension* generators)'
    __module__ = "ast"
    _fields = ('elt', 'generators')
    __match_args__ = ('elt', 'generators')
    _field_kinds = {'elt': '', 'generators': '*'}


class SetComp(expr):
    __doc__ = 'SetComp(expr elt, comprehension* generators)'
    __module__ = "ast"
    _fields = ('elt', 'generators')
    __match_args__ = ('elt', 'generators')
    _field_kinds = {'elt': '', 'generators': '*'}


class DictComp(expr):
    __doc__ = 'DictComp(expr key, expr value, comprehension* generators)'
    __module__ = "ast"
    _fields = ('key', 'value', 'generators')
    __match_args__ = ('key', 'value', 'generators')
    _field_kinds = {'key': '', 'value': '', 'generators': '*'}


class GeneratorExp(expr):
    __doc__ = 'GeneratorExp(expr elt, comprehension* generators)'
    __module__ = "ast"
    _fields = ('elt', 'generators')
    __match_args__ = ('elt', 'generators')
    _field_kinds = {'elt': '', 'generators': '*'}


class Await(expr):
    __doc__ = 'Await(expr value)'
    __module__ = "ast"
    _fields = ('value',)
    __match_args__ = ('value',)
    _field_kinds = {'value': ''}


class Yield(expr):
    __doc__ = 'Yield(expr? value)'
    __module__ = "ast"
    _fields = ('value',)
    __match_args__ = ('value',)
    _field_kinds = {'value': '?'}
    value = None


class YieldFrom(expr):
    __doc__ = 'YieldFrom(expr value)'
    __module__ = "ast"
    _fields = ('value',)
    __match_args__ = ('value',)
    _field_kinds = {'value': ''}


class Compare(expr):
    __doc__ = 'Compare(expr left, cmpop* ops, expr* comparators)'
    __module__ = "ast"
    _fields = ('left', 'ops', 'comparators')
    __match_args__ = ('left', 'ops', 'comparators')
    _field_kinds = {'left': '', 'ops': '*', 'comparators': '*'}


class Call(expr):
    __doc__ = 'Call(expr func, expr* args, keyword* keywords)'
    __module__ = "ast"
    _fields = ('func', 'args', 'keywords')
    __match_args__ = ('func', 'args', 'keywords')
    _field_kinds = {'func': '', 'args': '*', 'keywords': '*'}


class FormattedValue(expr):
    __doc__ = 'FormattedValue(expr value, int conversion, expr? format_spec)'
    __module__ = "ast"
    _fields = ('value', 'conversion', 'format_spec')
    __match_args__ = ('value', 'conversion', 'format_spec')
    _field_kinds = {'value': '', 'conversion': '', 'format_spec': '?'}
    format_spec = None


class Interpolation(expr):
    __doc__ = 'Interpolation(expr value, constant str, int conversion, expr? format_spec)'
    __module__ = "ast"
    _fields = ('value', 'str', 'conversion', 'format_spec')
    __match_args__ = ('value', 'str', 'conversion', 'format_spec')
    _field_kinds = {'value': '', 'str': '', 'conversion': '', 'format_spec': '?'}
    format_spec = None


class JoinedStr(expr):
    __doc__ = 'JoinedStr(expr* values)'
    __module__ = "ast"
    _fields = ('values',)
    __match_args__ = ('values',)
    _field_kinds = {'values': '*'}


class TemplateStr(expr):
    __doc__ = 'TemplateStr(expr* values)'
    __module__ = "ast"
    _fields = ('values',)
    __match_args__ = ('values',)
    _field_kinds = {'values': '*'}


class Constant(expr):
    __doc__ = 'Constant(constant value, string? kind)'
    __module__ = "ast"
    _fields = ('value', 'kind')
    __match_args__ = ('value', 'kind')
    _field_kinds = {'value': '', 'kind': '?'}
    kind = None


class Attribute(expr):
    __doc__ = 'Attribute(expr value, identifier attr, expr_context ctx)'
    __module__ = "ast"
    _fields = ('value', 'attr', 'ctx')
    __match_args__ = ('value', 'attr', 'ctx')
    _field_kinds = {'value': '', 'attr': '', 'ctx': 'ctx'}


class Subscript(expr):
    __doc__ = 'Subscript(expr value, expr slice, expr_context ctx)'
    __module__ = "ast"
    _fields = ('value', 'slice', 'ctx')
    __match_args__ = ('value', 'slice', 'ctx')
    _field_kinds = {'value': '', 'slice': '', 'ctx': 'ctx'}


class Starred(expr):
    __doc__ = 'Starred(expr value, expr_context ctx)'
    __module__ = "ast"
    _fields = ('value', 'ctx')
    __match_args__ = ('value', 'ctx')
    _field_kinds = {'value': '', 'ctx': 'ctx'}


class Name(expr):
    __doc__ = 'Name(identifier id, expr_context ctx)'
    __module__ = "ast"
    _fields = ('id', 'ctx')
    __match_args__ = ('id', 'ctx')
    _field_kinds = {'id': '', 'ctx': 'ctx'}


class List(expr):
    __doc__ = 'List(expr* elts, expr_context ctx)'
    __module__ = "ast"
    _fields = ('elts', 'ctx')
    __match_args__ = ('elts', 'ctx')
    _field_kinds = {'elts': '*', 'ctx': 'ctx'}


class Tuple(expr):
    __doc__ = 'Tuple(expr* elts, expr_context ctx)'
    __module__ = "ast"
    _fields = ('elts', 'ctx')
    __match_args__ = ('elts', 'ctx')
    _field_kinds = {'elts': '*', 'ctx': 'ctx'}


class Slice(expr):
    __doc__ = 'Slice(expr? lower, expr? upper, expr? step)'
    __module__ = "ast"
    _fields = ('lower', 'upper', 'step')
    __match_args__ = ('lower', 'upper', 'step')
    _field_kinds = {'lower': '?', 'upper': '?', 'step': '?'}
    lower = None
    upper = None
    step = None


class expr_context(AST):
    __doc__ = 'expr_context = Load | Store | Del'
    __module__ = "ast"
    _attributes = ()


class Load(expr_context):
    __doc__ = 'Load'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class Store(expr_context):
    __doc__ = 'Store'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class Del(expr_context):
    __doc__ = 'Del'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class boolop(AST):
    __doc__ = 'boolop = And | Or'
    __module__ = "ast"
    _attributes = ()


class And(boolop):
    __doc__ = 'And'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class Or(boolop):
    __doc__ = 'Or'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class operator(AST):
    __doc__ = 'operator = Add | Sub | Mult | MatMult | Div | Mod | Pow | LShift | RShift | BitOr | BitXor | BitAnd | FloorDiv'
    __module__ = "ast"
    _attributes = ()


class Add(operator):
    __doc__ = 'Add'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class Sub(operator):
    __doc__ = 'Sub'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class Mult(operator):
    __doc__ = 'Mult'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class MatMult(operator):
    __doc__ = 'MatMult'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class Div(operator):
    __doc__ = 'Div'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class Mod(operator):
    __doc__ = 'Mod'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class Pow(operator):
    __doc__ = 'Pow'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class LShift(operator):
    __doc__ = 'LShift'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class RShift(operator):
    __doc__ = 'RShift'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class BitOr(operator):
    __doc__ = 'BitOr'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class BitXor(operator):
    __doc__ = 'BitXor'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class BitAnd(operator):
    __doc__ = 'BitAnd'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class FloorDiv(operator):
    __doc__ = 'FloorDiv'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class unaryop(AST):
    __doc__ = 'unaryop = Invert | Not | UAdd | USub'
    __module__ = "ast"
    _attributes = ()


class Invert(unaryop):
    __doc__ = 'Invert'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class Not(unaryop):
    __doc__ = 'Not'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class UAdd(unaryop):
    __doc__ = 'UAdd'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class USub(unaryop):
    __doc__ = 'USub'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class cmpop(AST):
    __doc__ = 'cmpop = Eq | NotEq | Lt | LtE | Gt | GtE | Is | IsNot | In | NotIn'
    __module__ = "ast"
    _attributes = ()


class Eq(cmpop):
    __doc__ = 'Eq'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class NotEq(cmpop):
    __doc__ = 'NotEq'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class Lt(cmpop):
    __doc__ = 'Lt'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class LtE(cmpop):
    __doc__ = 'LtE'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class Gt(cmpop):
    __doc__ = 'Gt'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class GtE(cmpop):
    __doc__ = 'GtE'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class Is(cmpop):
    __doc__ = 'Is'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class IsNot(cmpop):
    __doc__ = 'IsNot'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class In(cmpop):
    __doc__ = 'In'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class NotIn(cmpop):
    __doc__ = 'NotIn'
    __module__ = "ast"
    _fields = ()
    __match_args__ = ()
    _field_kinds = {}


class comprehension(AST):
    __doc__ = 'comprehension(expr target, expr iter, expr* ifs, int is_async)'
    __module__ = "ast"
    _fields = ('target', 'iter', 'ifs', 'is_async')
    __match_args__ = ('target', 'iter', 'ifs', 'is_async')
    _field_kinds = {'target': '', 'iter': '', 'ifs': '*', 'is_async': ''}
    _attributes = ()


class excepthandler(AST):
    __doc__ = 'excepthandler = ExceptHandler(expr? type, identifier? name, stmt* body)'
    __module__ = "ast"
    _attributes = ('lineno', 'col_offset', 'end_lineno', 'end_col_offset')
    end_lineno = None
    end_col_offset = None


class ExceptHandler(excepthandler):
    __doc__ = 'ExceptHandler(expr? type, identifier? name, stmt* body)'
    __module__ = "ast"
    _fields = ('type', 'name', 'body')
    __match_args__ = ('type', 'name', 'body')
    _field_kinds = {'type': '?', 'name': '?', 'body': '*'}
    type = None
    name = None


class arguments(AST):
    __doc__ = 'arguments(arg* posonlyargs, arg* args, arg? vararg, arg* kwonlyargs, expr?* kw_defaults, arg? kwarg, expr* defaults)'
    __module__ = "ast"
    _fields = ('posonlyargs', 'args', 'vararg', 'kwonlyargs', 'kw_defaults', 'kwarg', 'defaults')
    __match_args__ = ('posonlyargs', 'args', 'vararg', 'kwonlyargs', 'kw_defaults', 'kwarg', 'defaults')
    _field_kinds = {'posonlyargs': '*', 'args': '*', 'vararg': '?', 'kwonlyargs': '*', 'kw_defaults': '*', 'kwarg': '?', 'defaults': '*'}
    _attributes = ()
    vararg = None
    kwarg = None


class arg(AST):
    __doc__ = 'arg(identifier arg, expr? annotation, string? type_comment)'
    __module__ = "ast"
    _fields = ('arg', 'annotation', 'type_comment')
    __match_args__ = ('arg', 'annotation', 'type_comment')
    _field_kinds = {'arg': '', 'annotation': '?', 'type_comment': '?'}
    _attributes = ('lineno', 'col_offset', 'end_lineno', 'end_col_offset')
    end_lineno = None
    end_col_offset = None
    annotation = None
    type_comment = None


class keyword(AST):
    __doc__ = 'keyword(identifier? arg, expr value)'
    __module__ = "ast"
    _fields = ('arg', 'value')
    __match_args__ = ('arg', 'value')
    _field_kinds = {'arg': '?', 'value': ''}
    _attributes = ('lineno', 'col_offset', 'end_lineno', 'end_col_offset')
    end_lineno = None
    end_col_offset = None
    arg = None


class alias(AST):
    __doc__ = 'alias(identifier name, identifier? asname)'
    __module__ = "ast"
    _fields = ('name', 'asname')
    __match_args__ = ('name', 'asname')
    _field_kinds = {'name': '', 'asname': '?'}
    _attributes = ('lineno', 'col_offset', 'end_lineno', 'end_col_offset')
    end_lineno = None
    end_col_offset = None
    asname = None


class withitem(AST):
    __doc__ = 'withitem(expr context_expr, expr? optional_vars)'
    __module__ = "ast"
    _fields = ('context_expr', 'optional_vars')
    __match_args__ = ('context_expr', 'optional_vars')
    _field_kinds = {'context_expr': '', 'optional_vars': '?'}
    _attributes = ()
    optional_vars = None


class match_case(AST):
    __doc__ = 'match_case(pattern pattern, expr? guard, stmt* body)'
    __module__ = "ast"
    _fields = ('pattern', 'guard', 'body')
    __match_args__ = ('pattern', 'guard', 'body')
    _field_kinds = {'pattern': '', 'guard': '?', 'body': '*'}
    _attributes = ()
    guard = None


class pattern(AST):
    __doc__ = 'pattern = MatchValue(expr value)\n        | MatchSingleton(constant value)\n        | MatchSequence(pattern* patterns)\n        | MatchMapping(expr* keys, pattern* patterns, identifier? rest)\n        | MatchClass(expr cls, pattern* patterns, identifier* kwd_attrs, pattern* kwd_patterns)\n        | MatchStar(identifier? name)\n        | MatchAs(pattern? pattern, identifier? name)\n        | MatchOr(pattern* patterns)'
    __module__ = "ast"
    _attributes = ('lineno', 'col_offset', 'end_lineno', 'end_col_offset')


class MatchValue(pattern):
    __doc__ = 'MatchValue(expr value)'
    __module__ = "ast"
    _fields = ('value',)
    __match_args__ = ('value',)
    _field_kinds = {'value': ''}


class MatchSingleton(pattern):
    __doc__ = 'MatchSingleton(constant value)'
    __module__ = "ast"
    _fields = ('value',)
    __match_args__ = ('value',)
    _field_kinds = {'value': ''}


class MatchSequence(pattern):
    __doc__ = 'MatchSequence(pattern* patterns)'
    __module__ = "ast"
    _fields = ('patterns',)
    __match_args__ = ('patterns',)
    _field_kinds = {'patterns': '*'}


class MatchMapping(pattern):
    __doc__ = 'MatchMapping(expr* keys, pattern* patterns, identifier? rest)'
    __module__ = "ast"
    _fields = ('keys', 'patterns', 'rest')
    __match_args__ = ('keys', 'patterns', 'rest')
    _field_kinds = {'keys': '*', 'patterns': '*', 'rest': '?'}
    rest = None


class MatchClass(pattern):
    __doc__ = 'MatchClass(expr cls, pattern* patterns, identifier* kwd_attrs, pattern* kwd_patterns)'
    __module__ = "ast"
    _fields = ('cls', 'patterns', 'kwd_attrs', 'kwd_patterns')
    __match_args__ = ('cls', 'patterns', 'kwd_attrs', 'kwd_patterns')
    _field_kinds = {'cls': '', 'patterns': '*', 'kwd_attrs': '*', 'kwd_patterns': '*'}


class MatchStar(pattern):
    __doc__ = 'MatchStar(identifier? name)'
    __module__ = "ast"
    _fields = ('name',)
    __match_args__ = ('name',)
    _field_kinds = {'name': '?'}
    name = None


class MatchAs(pattern):
    __doc__ = 'MatchAs(pattern? pattern, identifier? name)'
    __module__ = "ast"
    _fields = ('pattern', 'name')
    __match_args__ = ('pattern', 'name')
    _field_kinds = {'pattern': '?', 'name': '?'}
    pattern = None
    name = None


class MatchOr(pattern):
    __doc__ = 'MatchOr(pattern* patterns)'
    __module__ = "ast"
    _fields = ('patterns',)
    __match_args__ = ('patterns',)
    _field_kinds = {'patterns': '*'}


class type_ignore(AST):
    __doc__ = 'type_ignore = TypeIgnore(int lineno, string tag)'
    __module__ = "ast"
    _attributes = ()


class TypeIgnore(type_ignore):
    __doc__ = 'TypeIgnore(int lineno, string tag)'
    __module__ = "ast"
    _fields = ('lineno', 'tag')
    __match_args__ = ('lineno', 'tag')
    _field_kinds = {'lineno': '', 'tag': ''}


class type_param(AST):
    __doc__ = 'type_param = TypeVar(identifier name, expr? bound, expr? default_value)\n           | ParamSpec(identifier name, expr? default_value)\n           | TypeVarTuple(identifier name, expr? default_value)'
    __module__ = "ast"
    _attributes = ('lineno', 'col_offset', 'end_lineno', 'end_col_offset')


class TypeVar(type_param):
    __doc__ = 'TypeVar(identifier name, expr? bound, expr? default_value)'
    __module__ = "ast"
    _fields = ('name', 'bound', 'default_value')
    __match_args__ = ('name', 'bound', 'default_value')
    _field_kinds = {'name': '', 'bound': '?', 'default_value': '?'}
    bound = None
    default_value = None


class ParamSpec(type_param):
    __doc__ = 'ParamSpec(identifier name, expr? default_value)'
    __module__ = "ast"
    _fields = ('name', 'default_value')
    __match_args__ = ('name', 'default_value')
    _field_kinds = {'name': '', 'default_value': '?'}
    default_value = None


class TypeVarTuple(type_param):
    __doc__ = 'TypeVarTuple(identifier name, expr? default_value)'
    __module__ = "ast"
    _fields = ('name', 'default_value')
    __match_args__ = ('name', 'default_value')
    _field_kinds = {'name': '', 'default_value': '?'}
    default_value = None



_singletons = {'Load': Load(), 'Store': Store(), 'Del': Del(), 'And': And(), 'Or': Or(), 'Add': Add(), 'Sub': Sub(), 'Mult': Mult(), 'MatMult': MatMult(), 'Div': Div(), 'Mod': Mod(), 'Pow': Pow(), 'LShift': LShift(), 'RShift': RShift(), 'BitOr': BitOr(), 'BitXor': BitXor(), 'BitAnd': BitAnd(), 'FloorDiv': FloorDiv(), 'Invert': Invert(), 'Not': Not(), 'UAdd': UAdd(), 'USub': USub(), 'Eq': Eq(), 'NotEq': NotEq(), 'Lt': Lt(), 'LtE': LtE(), 'Gt': Gt(), 'GtE': GtE(), 'Is': Is(), 'IsNot': IsNot(), 'In': In(), 'NotIn': NotIn()}
_LOAD = _singletons['Load']

Module._field_types = {'body': list[stmt], 'type_ignores': list[type_ignore]}
Interactive._field_types = {'body': list[stmt]}
Expression._field_types = {'body': expr}
FunctionType._field_types = {'argtypes': list[expr], 'returns': expr}
FunctionDef._field_types = {'name': str, 'args': arguments, 'body': list[stmt], 'decorator_list': list[expr], 'returns': expr | None, 'type_comment': str | None, 'type_params': list[type_param]}
AsyncFunctionDef._field_types = {'name': str, 'args': arguments, 'body': list[stmt], 'decorator_list': list[expr], 'returns': expr | None, 'type_comment': str | None, 'type_params': list[type_param]}
ClassDef._field_types = {'name': str, 'bases': list[expr], 'keywords': list[keyword], 'body': list[stmt], 'decorator_list': list[expr], 'type_params': list[type_param]}
Return._field_types = {'value': expr | None}
Delete._field_types = {'targets': list[expr]}
Assign._field_types = {'targets': list[expr], 'value': expr, 'type_comment': str | None}
TypeAlias._field_types = {'name': expr, 'type_params': list[type_param], 'value': expr}
AugAssign._field_types = {'target': expr, 'op': operator, 'value': expr}
AnnAssign._field_types = {'target': expr, 'annotation': expr, 'value': expr | None, 'simple': int}
For._field_types = {'target': expr, 'iter': expr, 'body': list[stmt], 'orelse': list[stmt], 'type_comment': str | None}
AsyncFor._field_types = {'target': expr, 'iter': expr, 'body': list[stmt], 'orelse': list[stmt], 'type_comment': str | None}
While._field_types = {'test': expr, 'body': list[stmt], 'orelse': list[stmt]}
If._field_types = {'test': expr, 'body': list[stmt], 'orelse': list[stmt]}
With._field_types = {'items': list[withitem], 'body': list[stmt], 'type_comment': str | None}
AsyncWith._field_types = {'items': list[withitem], 'body': list[stmt], 'type_comment': str | None}
Match._field_types = {'subject': expr, 'cases': list[match_case]}
Raise._field_types = {'exc': expr | None, 'cause': expr | None}
Try._field_types = {'body': list[stmt], 'handlers': list[excepthandler], 'orelse': list[stmt], 'finalbody': list[stmt]}
TryStar._field_types = {'body': list[stmt], 'handlers': list[excepthandler], 'orelse': list[stmt], 'finalbody': list[stmt]}
Assert._field_types = {'test': expr, 'msg': expr | None}
Import._field_types = {'names': list[alias]}
ImportFrom._field_types = {'module': str | None, 'names': list[alias], 'level': int | None}
Global._field_types = {'names': list[str]}
Nonlocal._field_types = {'names': list[str]}
Expr._field_types = {'value': expr}
Pass._field_types = {}
Break._field_types = {}
Continue._field_types = {}
BoolOp._field_types = {'op': boolop, 'values': list[expr]}
NamedExpr._field_types = {'target': expr, 'value': expr}
BinOp._field_types = {'left': expr, 'op': operator, 'right': expr}
UnaryOp._field_types = {'op': unaryop, 'operand': expr}
Lambda._field_types = {'args': arguments, 'body': expr}
IfExp._field_types = {'test': expr, 'body': expr, 'orelse': expr}
Dict._field_types = {'keys': list[expr], 'values': list[expr]}
Set._field_types = {'elts': list[expr]}
ListComp._field_types = {'elt': expr, 'generators': list[comprehension]}
SetComp._field_types = {'elt': expr, 'generators': list[comprehension]}
DictComp._field_types = {'key': expr, 'value': expr, 'generators': list[comprehension]}
GeneratorExp._field_types = {'elt': expr, 'generators': list[comprehension]}
Await._field_types = {'value': expr}
Yield._field_types = {'value': expr | None}
YieldFrom._field_types = {'value': expr}
Compare._field_types = {'left': expr, 'ops': list[cmpop], 'comparators': list[expr]}
Call._field_types = {'func': expr, 'args': list[expr], 'keywords': list[keyword]}
FormattedValue._field_types = {'value': expr, 'conversion': int, 'format_spec': expr | None}
Interpolation._field_types = {'value': expr, 'str': object, 'conversion': int, 'format_spec': expr | None}
JoinedStr._field_types = {'values': list[expr]}
TemplateStr._field_types = {'values': list[expr]}
Constant._field_types = {'value': object, 'kind': str | None}
Attribute._field_types = {'value': expr, 'attr': str, 'ctx': expr_context}
Subscript._field_types = {'value': expr, 'slice': expr, 'ctx': expr_context}
Starred._field_types = {'value': expr, 'ctx': expr_context}
Name._field_types = {'id': str, 'ctx': expr_context}
List._field_types = {'elts': list[expr], 'ctx': expr_context}
Tuple._field_types = {'elts': list[expr], 'ctx': expr_context}
Slice._field_types = {'lower': expr | None, 'upper': expr | None, 'step': expr | None}
Load._field_types = {}
Store._field_types = {}
Del._field_types = {}
And._field_types = {}
Or._field_types = {}
Add._field_types = {}
Sub._field_types = {}
Mult._field_types = {}
MatMult._field_types = {}
Div._field_types = {}
Mod._field_types = {}
Pow._field_types = {}
LShift._field_types = {}
RShift._field_types = {}
BitOr._field_types = {}
BitXor._field_types = {}
BitAnd._field_types = {}
FloorDiv._field_types = {}
Invert._field_types = {}
Not._field_types = {}
UAdd._field_types = {}
USub._field_types = {}
Eq._field_types = {}
NotEq._field_types = {}
Lt._field_types = {}
LtE._field_types = {}
Gt._field_types = {}
GtE._field_types = {}
Is._field_types = {}
IsNot._field_types = {}
In._field_types = {}
NotIn._field_types = {}
comprehension._field_types = {'target': expr, 'iter': expr, 'ifs': list[expr], 'is_async': int}
ExceptHandler._field_types = {'type': expr | None, 'name': str | None, 'body': list[stmt]}
arguments._field_types = {'posonlyargs': list[arg], 'args': list[arg], 'vararg': arg | None, 'kwonlyargs': list[arg], 'kw_defaults': list[expr], 'kwarg': arg | None, 'defaults': list[expr]}
arg._field_types = {'arg': str, 'annotation': expr | None, 'type_comment': str | None}
keyword._field_types = {'arg': str | None, 'value': expr}
alias._field_types = {'name': str, 'asname': str | None}
withitem._field_types = {'context_expr': expr, 'optional_vars': expr | None}
match_case._field_types = {'pattern': pattern, 'guard': expr | None, 'body': list[stmt]}
MatchValue._field_types = {'value': expr}
MatchSingleton._field_types = {'value': object}
MatchSequence._field_types = {'patterns': list[pattern]}
MatchMapping._field_types = {'keys': list[expr], 'patterns': list[pattern], 'rest': str | None}
MatchClass._field_types = {'cls': expr, 'patterns': list[pattern], 'kwd_attrs': list[str], 'kwd_patterns': list[pattern]}
MatchStar._field_types = {'name': str | None}
MatchAs._field_types = {'pattern': pattern | None, 'name': str | None}
MatchOr._field_types = {'patterns': list[pattern]}
TypeIgnore._field_types = {'lineno': int, 'tag': str}
TypeVar._field_types = {'name': str, 'bound': expr | None, 'default_value': expr | None}
ParamSpec._field_types = {'name': str, 'default_value': expr | None}
TypeVarTuple._field_types = {'name': str, 'default_value': expr | None}
