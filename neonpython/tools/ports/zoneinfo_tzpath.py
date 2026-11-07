# zoneinfo/_tzpath: CPython's, but for its default TZPATH, which it reads from sysconfig's build configuration. NeonPy
# has no sysconfig; its TZPATH is Linux CPython's (/usr/share/zoneinfo first, where NeonPy mounts its bundled
# database, PyTzdata; docs/PYTHON_PLATFORM.md §12.2). The lines are kept.
NOTE = "the default TZPATH Linux CPython's, without sysconfig (/usr/share/zoneinfo: the bundled database)"

REPLACEMENTS = [
    ('''import sysconfig
''', '''_TZPATH = "/usr/share/zoneinfo:/usr/lib/zoneinfo:/usr/share/lib/zoneinfo:/etc/zoneinfo"  # NeonPy: sysconfig's
'''),
    ('''            env_var = sysconfig.get_config_var("TZPATH")
''', '''            env_var = _TZPATH
'''),
]
