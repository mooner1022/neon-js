"""Generates NeonPy's _ast (resources dev/mooner/neonpython/lib/_ast.py) from CPython's Parser/Python.asdl.

CPython makes _ast's classes in C (Python/Python-ast.c, which Parser/asdl_c.py generates); NeonPy's are Python,
generated from the same ASDL, with AST's methods as asdl_c.py's templates have them (the constructor's defaults and
deprecation warnings, __reduce__, __replace__, the depth-limited repr). compile(..., PyCF_ONLY_AST) makes these
classes' instances from NeonPy's own syntax trees (PyAstObjects).

It also writes PyAsdl.kt, the same ASDL as Kotlin data, with which compile() of an AST object reads these classes'
instances (PyAstCompile, as Python-ast.c's obj2ast does).

Usage, from the repository root: python -X utf8 -I neonpython/tools/gen_ast.py
(CPython's sources in third_party/cpython: see port_cpython.py; Parser/ in its sparse checkout).
"""
import os
import sys

TOOLS = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(TOOLS))
PARSER = os.path.join(ROOT, 'third_party/cpython/Parser')
OUT = os.path.join(ROOT, 'neonpython/src/main/resources/dev/mooner/neonpython/lib/_ast.py')
KT_OUT = os.path.join(ROOT, 'neonpython/src/main/kotlin/dev/mooner/neonpython/PyAsdl.kt')

sys.path.insert(0, PARSER)
import asdl  # noqa: E402

BUILTIN = {'identifier': 'str', 'string': 'str', 'int': 'int', 'constant': 'object'}

HEAD = '''"""NeonPy's _ast: Python's syntax tree classes, generated from CPython 3.14.6's Parser/Python.asdl by
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

'''


def type_of(field, names):
    t = BUILTIN.get(field.type, field.type)
    if field.seq:
        return f'list[{t}]'
    if field.opt:
        return f'{t} | None'
    return t


# (CPython's docstrings: asdl_c.py's asdl_of)
from asdl_c import asdl_of  # noqa: E402


def main():
    mod = asdl.parse(os.path.join(PARSER, 'Python.asdl'))
    out = [HEAD]
    later = []
    for dfn in mod.dfns:
        name = dfn.name
        value = dfn.value
        attrs = value.attributes or []
        if isinstance(value, asdl.Sum):
            doc = asdl_of(name, value)
            # (__doc__ assigned, not a docstring: a compiler of 3.13 on dedents docstrings, CPython's keep their padding)
            out.append(f'class {name}(AST):\n    __doc__ = {doc!r}\n    __module__ = "ast"\n    _attributes = {tuple(a.name for a in attrs)!r}\n')
            for a in attrs:
                if a.opt:
                    out.append(f'    {a.name} = None\n')
            out.append('\n\n')
            for c in value.types:
                out.append(class_text(c.name, name, c.fields))
                later.append((c.name, c.fields))
        else:
            out.append(class_text(name, 'AST', value.fields, attrs))
            later.append((name, value.fields))
    # (one instance of each operator and context class: what the parser gives, Load the constructors' default, as
    # CPython's cached singletons)
    simple = [c.name for d in mod.dfns if isinstance(d.value, asdl.Sum) and not d.value.attributes
              and not any(c.fields for c in d.value.types) for c in d.value.types]
    out.append('\n_singletons = {%s}\n_LOAD = _singletons[%r]\n\n' % (', '.join(f'{n!r}: {n}()' for n in simple), 'Load'))
    # (the field types, once every class is there: list[stmt], expr | None)
    for name, fields in later:
        types = ', '.join(f'{f.name!r}: {type_of(f, None)}' for f in fields)
        out.append(f'{name}._field_types = {{{types}}}\n')
    text = ''.join(out)
    with open(OUT, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)
    print('wrote', OUT, len(text))
    kotlin(mod)


KT_HEAD = '''package dev.mooner.neonpython

/*
 * Generated by neonpython/tools/gen_ast.py from CPython 3.14.6's Parser/Python.asdl (do not edit): Python's syntax
 * tree types as data, with which compile() of an AST object reads `_ast`'s instances ([PyAstCompile], as
 * Python-ast.c's obj2ast, which Parser/asdl_c.py generates, reads them).
 */

/** The ASDL: each type's constructors (a product's one, named as the type), their fields, the type's attributes. */
internal object PyAsdl {
    /** A field: its name, its type (identifier, string, int, constant or a type's), a list ([seq]), optional ([opt]). */
    class Field(@JvmField val name: String, @JvmField val type: String, @JvmField val seq: Boolean, @JvmField val opt: Boolean)

    class Cons(@JvmField val name: String, @JvmField val fields: List<Field>) {
        lateinit var type: Type
        private val index = fields.withIndex().associate { (i, f) -> f.name to i }

        /** The index of its field [name]. */
        fun at(name: String): Int = index[name] ?: throw IllegalArgumentException("${this.name} has no field $name")
    }

    /** [simple]: an operator's or a context's type, its constructors without fields (asdl_c.py's is_simple). */
    class Type(@JvmField val name: String, @JvmField val product: Boolean, @JvmField val attributes: List<Field>, @JvmField val cons: List<Cons>) {
        @JvmField val simple = !product && cons.all { it.fields.isEmpty() }
    }

    @JvmField val TYPES = LinkedHashMap<String, Type>()

    /** "type? name, ..." as fields ('*' a list, '?' optional). */
    private fun fields(s: String): List<Field> = if (s.isEmpty()) emptyList() else s.split(", ").map { f ->
        val (t, n) = f.split(' ')
        Field(n, t.trimEnd('*', '?'), '*' in t, '?' in t)
    }

    /** A type: [cons] "Name(fields)" each (a product's one, named as it); [attributes] its attributes. */
    private fun type(name: String, product: Boolean, attributes: String, vararg cons: String) {
        val cs = cons.map { c ->
            val open = c.indexOf('(')
            if (open < 0) Cons(c, emptyList()) else Cons(c.substring(0, open), fields(c.substring(open + 1, c.length - 1)))
        }
        val t = Type(name, product, fields(attributes), cs)
        for (c in cs) c.type = t
        TYPES[name] = t
    }

    init {
'''


def field_text(f):
    return f'{f.type}{"?" if f.opt else ""}{"*" if f.seq else ""} {f.name}'


def kotlin(mod):
    out = [KT_HEAD]
    for dfn in mod.dfns:
        v = dfn.value
        attrs = ', '.join(field_text(a) for a in (v.attributes or []))
        if isinstance(v, asdl.Sum):
            cons = [c.name + (f'({", ".join(field_text(f) for f in c.fields)})' if c.fields else '') for c in v.types]
            product = 'false'
        else:
            cons = [f'{dfn.name}({", ".join(field_text(f) for f in v.fields)})']
            product = 'true'
        args = ',\n            '.join(f'"{c}"' for c in cons)
        out.append(f'        type("{dfn.name}", {product}, "{attrs}",\n            {args})\n')
    out.append('    }\n}\n')
    text = ''.join(out)
    with open(KT_OUT, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)
    print('wrote', KT_OUT, len(text))


def class_text(name, base, fields, attrs=None):
    doc = asdl_of(name, asdl.Constructor(name, fields))
    lines = [f'class {name}({base}):', f'    __doc__ = {doc!r}', '    __module__ = "ast"',
             f'    _fields = {tuple(f.name for f in fields)!r}',
             f'    __match_args__ = {tuple(f.name for f in fields)!r}']
    kinds = {}
    for f in fields:
        kinds[f.name] = '*' if f.seq else '?' if f.opt else 'ctx' if f.type == 'expr_context' else ''
    lines.append(f'    _field_kinds = {kinds!r}')
    if attrs is not None:
        lines.append(f'    _attributes = {tuple(a.name for a in attrs)!r}')
        for a in attrs:
            if a.opt:
                lines.append(f'    {a.name} = None')
    for f in fields:
        if f.opt:
            lines.append(f'    {f.name} = None')
    return '\n'.join(lines) + '\n\n\n'


if __name__ == '__main__':
    main()
