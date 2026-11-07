# f-strings: fields, conversions, specs, nesting, =
x = 42
name = 'Ada'
pi = 3.14159
print(f'{x} {name} {pi}', f'{x!r} {name!r} {name!s} {name!a}', f"{'é'!a} {'ü'!r}")
print(f'{x=}', f'{name=!s}', f'{pi=:.2f}', f'{x + 1 = }', f'{{literal}} {x}{{}}')
w, p = 10, 3
print(f'[{pi:{w}.{p}f}]', f'[{name:>{w}}]', f'[{x:{"^"}{w}}]')
print(f'{"nested " + f"{x}"}', f'{[1, 2][0]}', f"{ {'a': 1}['a'] }", f'{x:}', f'{None}')

# int: fill, align, sign, width, grouping, types
for spec in ['d', '5d', '<5d', '^5d', '*>7d', '+d', ' d', '05d', '=+8d', ',d', '_d', 'x', 'X', '#x', 'o', '#o', 'b', '#b', '_b', '08b', 'c', 'n', '']:
    print(repr(spec), '[' + format(255, spec) + ']', '[' + format(-255, spec if spec != 'c' else 'd') + ']')
print(format(12345678901234567890, ','), format(10 ** 30, 'x'), format(True, 'd'), format(True, '>6'), format(False, ''))
print(format(42, 'f'), format(42, '.2e'), format(42, '%'), format(7, 'g'))

# float: fixed, exponent, general, percent, the default
for v in [0.0, -0.0, 1.0, 2.675, 0.1, 1e16, 1.5e-7, 123456.789, -987.654321, 1e300, float('inf'), float('-inf'), float('nan')]:
    print(repr(v), format(v, ''), format(v, '.2f'), format(v, '.3e'), format(v, 'g'), format(v, '.3g'), format(v, '.4'), format(v, '+.1%'))
print(format(0.1, '.20f'), format(2.675, '.2f'), format(1.25, '.1f'), format(1.35, '.1f'), format(0.5, '.0f'), format(1.5, '.0f'), format(2.5, '.0f'))
print(format(12345.678, ',.1f'), format(1234567.0, '_.2f'), format(-1.5, '010.2f'), format(1.5, '^12.3f') + '|', format(3.0, '#g'), format(3.0, '#.0f'))
print(format(-0.0, 'z.1f'), format(-0.0001, 'z.1f'), format(1e-5, ''), format(1e-4, ''), format(123.0, 'G'), format(1e-10, 'E'), format(1.0, 'F'))
print(format(1e16, '.3'), format(123.456, '.3'), format(1.0, '.3'), format(0.000012345, '.2'), format(100.0, '.2'))

# str
for spec in ['', '10', '<10', '>10', '^10', '*^11', '.2', '10.3', 's']:
    print(repr(spec), '[' + format('hello', spec) + ']')
print(format('é', '^5') + '|', format('', '>3') + '|')

# __format__ of one's own; format() and object's
class Money:
    def __init__(self, cents):
        self.cents = cents
    def __format__(self, spec):
        if spec == 'short':
            return '$' + str(self.cents // 100)
        return '$' + format(self.cents / 100, spec or '.2f')

m = Money(12345)
print(f'{m}', f'{m:short}', f'{m:,.1f}', format(m, '>10'), '{:short}'.format(m))

class Plain:
    def __str__(self):
        return 'plain str'

print(f'{Plain()}', format(Plain()), type(f'{Plain()!r}').__name__)

# str.format: positions, names, attributes, items, nested specs, conversions
print('{} {}'.format('a', 'b'), '{1} {0} {1}'.format('a', 'b'), '{x}-{y}'.format(x=1, y=2), '{0[1]} {0[0]}'.format(['p', 'q']))
print('{p.cents} {d[k]} {d[0]}'.format(p=m, d={'k': 'v', 0: 'zero'}), '{:{w}.{p}f}'.format(pi, w=8, p=2), '{!r:>8}'.format('s'))
print('{{}} {{{}}}'.format(5), '{:*^9}'.format('mid'), '{0:x} {0:o} {0:b}'.format(10), '{:,}'.format(1234567))
print('{a} and {b}'.format_map({'a': 1, 'b': 2}))

# %: the old formatting
print('%s %s' % ('a', 'b'), '%d%%' % 50, '%5.2f|%-6s|%+d' % (pi, 'ab', 3), '%x %X %o %#x %#o' % (255, 255, 8, 255, 8))
print('%(a)s-%(b)03d' % {'a': 'x', 'b': 7}, '%r %a' % ('é', 'é'), '%c%c' % (72, 'i'), '%*d|%-*d|' % (5, 1, 4, 2), '%.*f' % (2, 2.675))
print('%e %E %g %G' % (12345.678, 0.00012, 1e-5, 1e20), '%i %u' % (3, 4), '%s' % [1, 2], '%s' % (1,), '%5s|%.1s' % ('x', 'abc'))
print('%d' % True, '%f' % 1, '%.0f %.0f' % (0.5, 1.5), '%10.3e' % 1234.5, '%-+8.2f|' % 3.14159, '% d' % 5)
