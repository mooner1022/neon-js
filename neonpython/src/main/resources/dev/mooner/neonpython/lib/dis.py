"""Disassembler of Python byte code into mnemonics."""
# NeonPy's own dis (docs/PYTHON_PLATFORM.md §12.2): NeonPy's bytecode is its own, so dis lists NeonPy's instructions
# (_dis decodes them), keeping CPython's names other modules read (COMPILER_FLAG_NAMES, Positions, Instruction,
# get_instructions, findlinestarts, code_info...). An instruction's offset is twice its index in NeonPy's code (as
# f_lasti and tb_lasti are). pretty_flags, _get_code_object, code_info and Positions are CPython's.

import sys
import types
import collections

import _dis

__all__ = ["code_info", "dis", "disassemble", "distb", "disco",
           "findlinestarts", "findlabels", "show_code",
           "get_instructions", "Instruction", "Bytecode",
           "opname", "opmap", "stack_effect", "hasconst", "hasname",
           "hasjump", "haslocal", "COMPILER_FLAG_NAMES", "Positions"]

opname = list(_dis.opname)
opmap = {name: op for op, name in enumerate(opname) if not name.startswith('<')}
hasconst = list(_dis.hasconst)
hasname = list(_dis.hasname)
hasjump = hasjrel = list(_dis.hasjump)
hasjabs = []
haslocal = list(_dis.haslocal)
hascompare = []
hasfree = []
hasexc = []
cmp_op = ('<', '<=', '==', '!=', '>', '>=')
HAVE_ARGUMENT = 0
EXTENDED_ARG = -1

_have_code = (types.MethodType, types.FunctionType, types.CodeType,
              classmethod, staticmethod, type)

COMPILER_FLAG_NAMES = {
            1: "OPTIMIZED",
            2: "NEWLOCALS",
            4: "VARARGS",
            8: "VARKEYWORDS",
           16: "NESTED",
           32: "GENERATOR",
           64: "NOFREE",
          128: "COROUTINE",
          256: "ITERABLE_COROUTINE",
          512: "ASYNC_GENERATOR",
    0x4000000: "HAS_DOCSTRING",
    0x8000000: "METHOD",
}


def pretty_flags(flags):
    """Return pretty representation of code flags."""
    names = []
    for i in range(32):
        flag = 1<<i
        if flags & flag:
            names.append(COMPILER_FLAG_NAMES.get(flag, hex(flag)))
            flags ^= flag
            if not flags:
                break
    else:
        names.append(hex(flags))
    return ", ".join(names)


def _try_compile(source, name):
    """Attempts to compile the given source, first as an expression and
       then as a statement if the first approach fails.
    """
    try:
        return compile(source, name, 'eval')
    except SyntaxError:
        pass
    return compile(source, name, 'exec')


def _get_code_object(x):
    """Helper to handle methods, compiled or raw code objects, and strings."""
    # Extract functions from methods.
    if hasattr(x, '__func__'):
        x = x.__func__
    # Extract compiled code objects from...
    if hasattr(x, '__code__'):  # ...a function, or
        x = x.__code__
    elif hasattr(x, 'gi_code'):  #...a generator object, or
        x = x.gi_code
    elif hasattr(x, 'ag_code'):  #...an asynchronous generator object, or
        x = x.ag_code
    elif hasattr(x, 'cr_code'):  #...a coroutine.
        x = x.cr_code
    # Handle source code.
    if isinstance(x, str):
        x = _try_compile(x, "<disassembly>")
    # By now, if we don't have a code object, we can't disassemble x.
    if hasattr(x, 'co_code'):
        return x
    raise TypeError("don't know how to disassemble %s objects" %
                    type(x).__name__)


def code_info(x):
    """Formatted details of methods, functions, or code."""
    return _format_code_info(_get_code_object(x))


def _format_code_info(co):
    lines = []
    lines.append("Name:              %s" % co.co_name)
    lines.append("Filename:          %s" % co.co_filename)
    lines.append("Argument count:    %s" % co.co_argcount)
    lines.append("Positional-only arguments: %s" % co.co_posonlyargcount)
    lines.append("Kw-only arguments: %s" % co.co_kwonlyargcount)
    lines.append("Number of locals:  %s" % co.co_nlocals)
    lines.append("Stack size:        %s" % co.co_stacksize)
    lines.append("Flags:             %s" % pretty_flags(co.co_flags))
    if co.co_consts:
        lines.append("Constants:")
        for i_c in enumerate(co.co_consts):
            lines.append("%4d: %r" % i_c)
    if co.co_names:
        lines.append("Names:")
        for i_n in enumerate(co.co_names):
            lines.append("%4d: %s" % i_n)
    if co.co_varnames:
        lines.append("Variable names:")
        for i_n in enumerate(co.co_varnames):
            lines.append("%4d: %s" % i_n)
    if co.co_freevars:
        lines.append("Free variables:")
        for i_n in enumerate(co.co_freevars):
            lines.append("%4d: %s" % i_n)
    if co.co_cellvars:
        lines.append("Cell variables:")
        for i_n in enumerate(co.co_cellvars):
            lines.append("%4d: %s" % i_n)
    return "\n".join(lines)


def show_code(co, *, file=None):
    """Print details of methods, functions, or code to *file*.

    If *file* is not provided, the output is printed on stdout.
    """
    print(code_info(co), file=file)


Positions = collections.namedtuple(
    'Positions',
    [
        'lineno',
        'end_lineno',
        'col_offset',
        'end_col_offset',
    ],
    defaults=[None] * 4
)

_Instruction = collections.namedtuple(
    "_Instruction",
    [
        'opname',
        'opcode',
        'arg',
        'argval',
        'argrepr',
        'offset',
        'start_offset',
        'starts_line',
        'line_number',
        'label',
        'positions',
        'cache_info',
    ],
    defaults=[None, None, None]
)


class Instruction(_Instruction):
    """Details for a bytecode operation (NeonPy's).

       Defined fields:
         opname - human readable name for operation
         opcode - numeric code for operation
         arg - numeric argument to operation (if any), otherwise None
         argval - resolved arg value (if known), otherwise same as arg
         argrepr - human readable description of operation argument
         offset - start index of operation within bytecode sequence
         start_offset - start index of operation within bytecode sequence
         starts_line - True if this opcode starts a source line, otherwise False
         line_number - source line number associated with this opcode (if any), otherwise None
         label - A label if this instruction is a jump target, otherwise None
         positions - Optional dis.Positions object holding the span of source code
                     covered by this instruction
         cache_info - None (NeonPy's instructions have no inline caches)
    """

    @property
    def oparg(self):
        """Alias for Instruction.arg."""
        return self.arg

    @property
    def baseopcode(self):
        """Numeric code for the base operation if operation is specialized."""
        return self.opcode

    @property
    def baseopname(self):
        """Human readable name for the base operation if operation is specialized."""
        return self.opname

    @property
    def cache_offset(self):
        """Start index of the cache entries following the operation."""
        return self.end_offset

    @property
    def end_offset(self):
        """End index of the cache entries following the operation."""
        return self.offset + 2 * (1 + len(self._operands()))

    @property
    def jump_target(self):
        """Bytecode index of the jump target if this is a jump operation."""
        if self.opcode in hasjump:
            return self.argval
        return None

    @property
    def is_jump_target(self):
        """True if other code jumps to here, otherwise False"""
        return self.label is not None

    def _operands(self):
        return () if self.arg is None else (self.argval if isinstance(self.argval, tuple) else (self.arg,))

    def __str__(self):
        import io
        output = io.StringIO()
        self._disassemble(output)
        return output.getvalue()

    def _disassemble(self, file, lineno_width=3, mark_as_current=False, offset_width=4):
        fields = []
        if lineno_width:
            if self.starts_line:
                fields.append(f"{self.line_number!s:>{lineno_width}}")
            else:
                fields.append(' ' * lineno_width)
        fields.append('-->' if mark_as_current else '   ')
        fields.append(repr(self.offset).rjust(offset_width))
        fields.append(self.opname.ljust(20))
        if self.arg is not None:
            arg = repr(self.arg)
            fields.append(arg.rjust(5))
            if self.argrepr:
                fields.append('(' + self.argrepr + ')')
        print(' '.join(fields).rstrip(), file=file)


def _instructions(co, first_line=None):
    raw = _dis.instructions(co)
    targets = sorted({i[4] for i in raw if i[1] in hasjump and isinstance(i[4], int)})
    labels = {offset: n + 1 for n, offset in enumerate(targets)}
    shift = 0 if first_line is None else first_line - co.co_firstlineno
    last = None
    for offset, opcode, name, arg, argval, argrepr, line, operands in raw:
        if line is not None:
            line += shift
        starts = line is not None and line != last
        if line is not None:
            last = line
        yield Instruction(name, opcode, None if arg is None else arg, argval, argrepr, offset, offset, starts, line,
                          labels.get(offset), Positions(line, line, None, None), None)


def get_instructions(x, *, first_line=None, show_caches=None, adaptive=False):
    """Iterator for the opcodes in methods, functions or code

    Generates a series of Instruction named tuples giving the details of
    each operations in the supplied code.

    If *first_line* is not None, it indicates the line number that should
    be reported for the first source line in the disassembled code.
    Otherwise, the source line information (if any) is taken directly from
    the disassembled code object.
    """
    co = _get_code_object(x)
    return _instructions(co, first_line)


def findlabels(code):
    """Detect all offsets in a byte code which are jump targets.

    Return the list of offsets.

    """
    if hasattr(code, 'co_code') and not isinstance(code, bytes):
        return sorted({i[4] for i in _dis.instructions(code) if i[1] in hasjump})
    return []


def findlinestarts(code):
    """Find the offsets in a byte code which are start of lines in the source.

    Generate pairs (offset, lineno)
    lineno will be an integer or None the offset does not have a source line.
    """
    lastline = False # None is a valid line number
    for start, end, line in code.co_lines():
        if line is not lastline:
            lastline = line
            yield start, line
    return


def stack_effect(opcode, oparg=None, *, jump=None):
    """Compute the stack effect of the opcode."""
    return _dis.stack_effect(opcode, 0 if oparg is None else oparg)


def disassemble(co, lasti=-1, *, file=None, show_caches=False, adaptive=False,
                show_offsets=False, show_positions=False):
    """Disassemble a code object."""
    for instr in _instructions(co):
        instr._disassemble(file, mark_as_current=instr.offset == lasti)


disco = disassemble


def _disassemble_recursive(co, *, file=None, depth=None, show_caches=False, adaptive=False,
                           show_offsets=False, show_positions=False):
    disassemble(co, file=file)
    if depth is None or depth > 0:
        if depth is not None:
            depth = depth - 1
        for x in co.co_consts:
            if hasattr(x, 'co_code'):
                print(file=file)
                print("Disassembly of %r:" % (x,), file=file)
                _disassemble_recursive(x, file=file, depth=depth)


def dis(x=None, *, file=None, depth=None, show_caches=False, adaptive=False,
        show_offsets=False, show_positions=False):
    """Disassemble classes, methods, functions, and other compiled objects.

    With no argument, disassemble the last traceback.

    Compiled objects currently include generator objects, async generator
    objects, and coroutine objects, all of which store their code object
    in a special attribute.
    """
    if x is None:
        distb(file=file)
        return
    # Extract functions from methods.
    if hasattr(x, '__func__'):
        x = x.__func__
    # Extract compiled code objects from...
    if hasattr(x, '__code__'):  # ...a function, or
        x = x.__code__
    elif hasattr(x, 'gi_code'):  #...a generator object, or
        x = x.gi_code
    elif hasattr(x, 'ag_code'):  #...an asynchronous generator object, or
        x = x.ag_code
    elif hasattr(x, 'cr_code'):  #...a coroutine.
        x = x.cr_code
    # Perform the disassembly.
    if hasattr(x, '__dict__'):  # Class or module
        items = sorted(x.__dict__.items())
        for name, x1 in items:
            if isinstance(x1, _have_code):
                print("Disassembly of %s:" % name, file=file)
                try:
                    dis(x1, file=file, depth=depth)
                except TypeError as msg:
                    print("Sorry:", msg, file=file)
                print(file=file)
    elif hasattr(x, 'co_code'): # Code object
        _disassemble_recursive(x, file=file, depth=depth)
    elif isinstance(x, str):    # Source code
        _disassemble_recursive(_try_compile(x, '<dis>'), file=file, depth=depth)
    else:
        raise TypeError("don't know how to disassemble %s objects" %
                        type(x).__name__)


def distb(tb=None, *, file=None, show_caches=False, adaptive=False, show_offsets=False, show_positions=False):
    """Disassemble a traceback (default: last traceback)."""
    if tb is None:
        try:
            if hasattr(sys, 'last_exc'):
                tb = sys.last_exc.__traceback__
            else:
                tb = sys.last_traceback
        except AttributeError:
            raise RuntimeError("no last traceback to disassemble") from None
        while tb.tb_next: tb = tb.tb_next
    disassemble(tb.tb_frame.f_code, tb.tb_lasti, file=file)


class Bytecode:
    """The bytecode operations of a piece of code

    Instantiate this with a function, method, other compiled object, string of
    code, or a code object (as returned by compile()).

    Iterating over this yields the bytecode operations as Instruction instances.
    """
    def __init__(self, x, *, first_line=None, current_offset=None, show_caches=False, adaptive=False,
                 show_offsets=False, show_positions=False):
        self.codeobj = co = _get_code_object(x)
        if first_line is None:
            self.first_line = co.co_firstlineno
            self._line_offset = 0
        else:
            self.first_line = first_line
            self._line_offset = first_line - co.co_firstlineno
        self._original_object = x
        self.current_offset = current_offset

    def __iter__(self):
        co = self.codeobj
        return _instructions(co, self.first_line if self._line_offset else None)

    def __repr__(self):
        return "{}({!r})".format(self.__class__.__name__,
                                 self._original_object)

    @classmethod
    def from_traceback(cls, tb, *, show_caches=False, adaptive=False):
        """ Construct a Bytecode from the given traceback """
        while tb.tb_next:
            tb = tb.tb_next
        return cls(tb.tb_frame.f_code, current_offset=tb.tb_lasti)

    def info(self):
        """Return formatted information about the code object."""
        return _format_code_info(self.codeobj)

    def dis(self):
        """Return a formatted view of the bytecode operations."""
        import io
        co = self.codeobj
        with io.StringIO() as output:
            for instr in _instructions(co, self.first_line if self._line_offset else None):
                instr._disassemble(output, mark_as_current=instr.offset == self.current_offset)
            return output.getvalue()
