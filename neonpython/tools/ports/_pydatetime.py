# _pydatetime: CPython's pure datetime, which NeonPy's datetime is (there is no C _datetime): where its repr is its
# own, C _datetime's.
NOTE = "IsoCalendarDate's repr C _datetime's (its module named)"

REPLACEMENTS = [
    ('''        return (f'{self.__class__.__name__}'
                f'(year={self[0]}, week={self[1]}, weekday={self[2]})')
''',
     '''        return (f'{_get_class_module(self)}{self.__class__.__name__}'
                f'(year={self[0]}, week={self[1]}, weekday={self[2]})')
'''),
]
