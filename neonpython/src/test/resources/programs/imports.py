'''imports: the forms, sys.modules, packages, relative imports'''
from __future__ import annotations
import sys

# import, import as, from import, packages and their submodules
import pkg.sub
print(pkg.sub.x, pkg.value, type(pkg).__name__, pkg.__package__, pkg.sub.__package__, hasattr(pkg, '__path__'), hasattr(pkg.sub, '__path__'))
import pkg.sub as s
print(s is pkg.sub, s.__name__, s.sibling.shared, s.shared)
from pkg import sub, sibling
print(sub.x, sibling.shared, pkg.sibling is sibling)
from pkg.deep.leaf import leaf
print(leaf, 'pkg.deep.leaf' in sys.modules, pkg.deep.leaf.leaf)
import plain, plain as p2
print(plain is p2, plain.x, sys.modules['plain'] is plain, plain.__name__, repr(sys)[:20])

# a module's state is the module's: imported once
import state
from state import bump
print(bump(), bump(), state.counter)
import state as again
print(again.counter, again is state)

# star imports: __all__, else the public names
from starmod import *
print(a, c, 'b' in globals(), '_private' in globals())
from starpub import *
print('_hidden' in globals())

# module attributes: set, deleted, a module-level __getattr__
plain.y = 2
print(plain.y, plain.__dict__['y'])
del plain.y
print(hasattr(plain, 'y'))
import lazy
print(lazy.real, lazy.anything)

# what goes wrong
for bad in ['nope', 'plain.nope', 'raises', 'circ_a']:
    try:
        __import__(bad)
    except ImportError as e:
        print(type(e).__name__, str(e).split(' (')[0])
    except ValueError as e:
        print('ValueError', e, bad in sys.modules)
try:
    from plain import nope
except ImportError as e:
    print(e.args[0].split(' (')[0])
try:
    from badall import *
except TypeError as e:
    print(e)

# sys
sys.modules['fake'] = 42
import fake
print(fake)
print(sys.maxsize, sys.byteorder, sys.version_info[:2], type(sys.argv).__name__, sys.getrecursionlimit() > 0)
try:
    sys.exit(3)
except SystemExit as e:
    print('exit', e.code)
try:
    1 / 0
except ZeroDivisionError:
    print(sys.exc_info()[0].__name__, sys.exc_info()[1])
print(sys.exc_info())
print('to stderr', file=sys.stderr)
sys.stdout.write('written\n')
import builtins
print(builtins.len is len, type(builtins).__name__)
from string.templatelib import Template, Interpolation
import string
print(isinstance(t'x', Template), string.digits, string.ascii_lowercase[:3])
