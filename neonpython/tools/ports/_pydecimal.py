# _pydecimal: CPython's pure decimal, which NeonPy's decimal is (there is no C _decimal): a trapped signal's
# exception has the conditions as its argument, as C _decimal's has; int()'s errors C's.
NOTE = "a trapped signal raised with its condition in a list, int()'s errors' messages, as C _decimal's"

REPLACEMENTS = [
    ('        # Errors should only be risked on copies of the context\n        # self._ignored_flags = []\n        raise error(explanation)\n',
     '        # Errors should only be risked on copies of the context\n        # self._ignored_flags = []\n        raise error([condition])\n'),
    ('raise ValueError("Cannot convert NaN to integer")', 'raise ValueError("cannot convert NaN to integer")'),
    ('raise OverflowError("Cannot convert infinity to integer")', 'raise OverflowError("cannot convert Infinity to integer")'),
]
