# platform: CPython's, which parses sys.version as Jython's, PyPy's or else CPython's. NeonPy's ("3.14.6 (NeonPy)")
# is none of them: its own, python_implementation() 'NeonPy' (docs/PYTHON_PLATFORM.md §12.2: code asking whether it
# runs on CPython is told it does not).
NOTE = "sys.version parsed as NeonPy's, python_implementation() 'NeonPy'"

REPLACEMENTS = [
    ('''    elif "PyPy" in sys_version:
        # PyPy
''',
     '''    elif "(NeonPy)" in sys_version:
        # NeonPy
        name = "NeonPy"
        version = sys_version.split()[0]
        buildno = builddate = compiler = ""

    elif "PyPy" in sys_version:
        # PyPy
'''),
]
