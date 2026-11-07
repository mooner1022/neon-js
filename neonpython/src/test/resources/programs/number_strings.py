# float(), complex() and int() of strings as CPython parses them: underscores only between digits, Unicode digits
# and spaces (NEL, no-break space) as ASCII, inf/infinity/nan in any case, no hexadecimal floats nor Java's d/f
# suffixes, complex's two errors (an underscore misplaced, a malformed string), int's bases.


def floats():
    cases = ['1_000', '1d', '1f', '1_0.5', '_1', '1__0', '1_', '0x10', ' 1.5 ', '1e1_0', 'infinity', '-iNF', 'nan', '-nan', '+inf', '1.5\n', '١٢', '1D', '0_0', '.5', '5.', '1e', '+-1', '', ' ', '.', 'e5', '1.e5', '1._5', '1_.5', '1e_5', '1e5_', '\xa01\xa0', ' 1', '\x851', '​1', '1　', '١.٥', 'in f', 'inF', 'infinit', 'nanx', '1e-3', '-0', '+.5e+2', '9' * 400, '1e400', '-1e-400', '0.1', '2.5e-324', '1.7976931348623157e308']
    for s in cases:
        try:
            r = float(s)
        except ValueError as e:
            r = 'ValueError ' + str(e)
        try:
            c = complex(s)
        except ValueError as e:
            c = 'VE'
        print(repr(s), r, c)
    for s in ['1_0+2_0j', '1__0j', '1e1_0j', '(1.5-2j)', '1+j', 'inf+nanj', '1_j']:
        try:
            print(repr(s), complex(s))
        except ValueError as e:
            print(repr(s), 'ValueError', e)
    for s in ['', ' ', 'x', '1+', '(1', '1j2', 'j', '-j', '١+٢j', '\x851j', '( 1+2j )', '1_+2j']:
        try: print(repr(s), complex(s))
        except ValueError as e: print(repr(s), e)


def ints():
    cases = [('1_000', 10), ('١٢', 10), (' 1 ', 10), ('1__0', 10), ('_1', 10), ('1_', 10), ('0x_1f', 16), ('0x_1f', 0), ('0b1_0', 0), ('0_0', 0), ('00', 0), ('01', 0), ('\xa012\xa0', 10), ('\x8512', 10), ('+-1', 10), ('١٢', 16), ('z', 36), ('Z', 36), ('1d', 10), ('9' * 30, 10), ('-0x10', 0), ('0o17', 0), ('٣', 2), ('', 10), (' ', 10), ('1.5', 10), ('12', 1), ('12', 37)]
    for s, b in cases:
        try:
            r = int(s, b)
        except ValueError as e:
            r = 'ValueError ' + str(e)
        print(repr(s), b, r)


floats()
ints()
