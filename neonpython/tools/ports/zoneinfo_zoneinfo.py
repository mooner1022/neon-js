# zoneinfo/_zoneinfo: CPython's pure ZoneInfo, which NeonPy's is (there is no C _zoneinfo): its repr C _zoneinfo's,
# the type's tp_name ("zoneinfo.ZoneInfo", a subclass's its own name). The lines are kept.
NOTE = "ZoneInfo's repr C _zoneinfo's (its tp_name, zoneinfo.ZoneInfo)"

REPLACEMENTS = [
    ('''            return f"{self.__class__.__name__}(key={self._key!r})"
        else:
            return f"{self.__class__.__name__}.from_file({self._file_repr})"
''', '''            return f"{'zoneinfo.ZoneInfo' if self.__class__ is ZoneInfo else self.__class__.__name__}(key={self._key!r})"
        else:
            return f"{'zoneinfo.ZoneInfo' if self.__class__ is ZoneInfo else self.__class__.__name__}.from_file({self._file_repr})"
'''),
]
