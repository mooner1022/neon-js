"""Ports CPython's own Lib modules into neonpython's bundled library (resources dev/mooner/neonpython/lib).

Each line of PORTS is `<path in CPython's Lib> [<rules in tools/ports>]` (a package's files each on its own line).
A rules file defines REPLACEMENTS = [(old, new), ...] (each old must occur exactly once in CPython's file) and
optionally NOTE (said in the lib's INDEX: the summary of changes PSF's license asks for). Files are otherwise as
CPython has them (LF line ends): their lines are CPython's, and so are tracebacks' line numbers. CPython's LICENSE goes
with them. CPython's sources are expected in third_party/cpython at the release named in VERSION, a sparse checkout:

    git clone --depth 1 --branch v3.14.6 --filter=blob:none --sparse https://github.com/python/cpython third_party/cpython
    git -C third_party/cpython sparse-checkout set Lib

Usage, from the repository root: python -X utf8 -I neonpython/tools/port_cpython.py [path...]
(without paths, every port is made again, and INDEX written; after a CPython update, check the rules still apply and
run the tests).
"""
import os
import runpy
import sys

VERSION = 'v3.14.6'
PORTS = """
__future__.py
_ast_unparse.py
_collections_abc.py
_py_abc.py
_py_warnings.py
_weakrefset.py
abc.py
annotationlib.py
ast.py
bisect.py
codecs.py
collections/__init__.py
contextlib.py
contextvars.py
copy.py
copyreg.py
encodings/__init__.py
encodings/_win_cp_codecs.py
encodings/aliases.py
encodings/ascii.py
encodings/charmap.py
encodings/cp037.py
encodings/cp1006.py
encodings/cp1026.py
encodings/cp1125.py
encodings/cp1140.py
encodings/cp1250.py
encodings/cp1251.py
encodings/cp1252.py
encodings/cp1253.py
encodings/cp1254.py
encodings/cp1255.py
encodings/cp1256.py
encodings/cp1257.py
encodings/cp1258.py
encodings/cp273.py
encodings/cp424.py
encodings/cp437.py
encodings/cp500.py
encodings/cp720.py
encodings/cp737.py
encodings/cp775.py
encodings/cp850.py
encodings/cp852.py
encodings/cp855.py
encodings/cp856.py
encodings/cp857.py
encodings/cp858.py
encodings/cp860.py
encodings/cp861.py
encodings/cp862.py
encodings/cp863.py
encodings/cp864.py
encodings/cp865.py
encodings/cp866.py
encodings/cp869.py
encodings/cp874.py
encodings/cp875.py
encodings/hp_roman8.py
encodings/iso8859_1.py
encodings/iso8859_10.py
encodings/iso8859_11.py
encodings/iso8859_13.py
encodings/iso8859_14.py
encodings/iso8859_15.py
encodings/iso8859_16.py
encodings/iso8859_2.py
encodings/iso8859_3.py
encodings/iso8859_4.py
encodings/iso8859_5.py
encodings/iso8859_6.py
encodings/iso8859_7.py
encodings/iso8859_8.py
encodings/iso8859_9.py
encodings/koi8_r.py
encodings/koi8_t.py
encodings/koi8_u.py
encodings/kz1048.py
encodings/latin_1.py
encodings/mac_arabic.py
encodings/mac_croatian.py
encodings/mac_cyrillic.py
encodings/mac_farsi.py
encodings/mac_greek.py
encodings/mac_iceland.py
encodings/mac_latin2.py
encodings/mac_roman.py
encodings/mac_romanian.py
encodings/mac_turkish.py
encodings/palmos.py
encodings/ptcp154.py
encodings/punycode.py
encodings/raw_unicode_escape.py
encodings/rot_13.py
encodings/tis_620.py
encodings/undefined.py
encodings/unicode_escape.py
encodings/utf_16.py
encodings/utf_16_be.py
encodings/utf_16_le.py
encodings/utf_32.py
encodings/utf_32_be.py
encodings/utf_32_le.py
encodings/utf_7.py
encodings/utf_8.py
encodings/utf_8_sig.py
enum.py
fnmatch.py
functools.py
genericpath.py
glob.py
heapq.py
keyword.py
linecache.py
numbers.py
operator.py
os.py
posixpath.py
random.py
re/__init__.py
re/_casefix.py
re/_compiler.py
re/_constants.py
re/_parser.py
reprlib.py
shutil.py
stat.py
string/__init__.py
string/templatelib.py
textwrap.py
types.py
typing.py
warnings.py
weakref.py
"""

# The lib's files that are NeonPy's, not CPython's (made by another tool): their INDEX notes
OWN = {
    '_ast.py': "NeonPy's: generated from CPython's Parser/Python.asdl by neonpython/tools/gen_ast.py",
    'dis.py': "NeonPy's: NeonPy's bytecode listed (_dis), CPython's names other modules read",
    'importlib/__init__.py': "NeonPy's: importlib's API over NeonPy's import (_imp), CPython's checks and messages",
    'importlib/machinery.py': "NeonPy's: importlib.machinery over NeonPy's import, CPython's ModuleSpec",
    'importlib/util.py': "NeonPy's: CPython's importlib.util over NeonPy's import, a spec made where a module has none",
}

TOOLS = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(TOOLS))
SRC = os.path.join(ROOT, 'third_party/cpython/Lib')
LIB = os.path.join(ROOT, 'neonpython/src/main/resources/dev/mooner/neonpython/lib')


def port(rel, rules):
    src = open(os.path.join(SRC, rel), encoding='utf-8').read().replace('\r\n', '\n')
    cfg = runpy.run_path(os.path.join(TOOLS, 'ports', rules + '.py')) if rules else {}
    for old, new in cfg.get('REPLACEMENTS', []):
        n = src.count(old)
        if n != 1:
            sys.exit(f'{rel}: expected one occurrence, found {n}: {old[:80]!r}')
        src = src.replace(old, new)
    out = os.path.join(LIB, rel)
    os.makedirs(os.path.dirname(out), exist_ok=True)
    open(out, 'w', encoding='utf-8', newline='\n').write(src)
    print('ported', rel)
    return cfg.get('NOTE')


def main(names):
    index = []
    for line in PORTS.strip().splitlines():
        parts = line.split()
        rel = parts[0]
        rules = parts[1] if len(parts) > 1 else None
        note = None
        if not names or rel in names:
            note = port(rel, rules)
        index.append(rel + (f'  # {note}' if note else ''))
    for rel, note in OWN.items():
        index.append(f'{rel}  # {note}')
    if not names:
        lic = open(os.path.join(os.path.dirname(SRC), 'LICENSE'), encoding='utf-8').read().replace('\r\n', '\n')
        open(os.path.join(LIB, 'LICENSE'), 'w', encoding='utf-8', newline='\n').write(lic)
        with open(os.path.join(LIB, 'INDEX'), 'w', encoding='utf-8', newline='\n') as f:
            f.write(f'# CPython {VERSION} Lib files bundled (neonpython/tools/port_cpython.py); one per line\n')
            for rel in index:
                f.write(rel + '\n')


if __name__ == '__main__':
    main(sys.argv[1:])
