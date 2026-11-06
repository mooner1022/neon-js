# complex numbers
z = 3 + 4j
print(z, z.real, z.imag, z.conjugate(), abs(z), -z, +z, 1j ** 2, 2j * 2j, (1 + 2j) / (3 - 4j), 1j == 1j, 1j != 2j)
print(complex(1, 2), complex('1+2j'), complex(' -3.5j '), complex(2), complex(), complex(1.5, -0.0), 0j, -0j, 1e300j * 1e10)
print(z + 1, 1 + z, z - 2.5, 2 * z, z * 0.5, z / 2, 2 / (1 + 1j), z ** 2, z ** 0.5, 2 ** (1j), (1 + 1j) ** 3)
print(repr(1j), repr(1 + 0j), repr(complex(0, -1)), str(3 - 0j), (1 + 2j) == complex(1, 2), 1 + 0j == 1, hash(1 + 0j) == hash(1))
print((5).real, (5).imag, (5).conjugate(), (2.5).real, (2.5).imag, bool(0j), bool(1j), isinstance(1j, complex), type(1j).__name__)

# bytes
b = b'hello'
print(b, b[1], b[1:3], b[::-1], len(b), b + b'!', b * 2, b'el' in b, 104 in b, list(b'ab'), b == b'hello', b < b'help')
print(bytes(3), bytes([65, 66, 67]), bytes(range(3)), bytes('é', 'utf-8'), 'é'.encode(), b'\xc3\xa9'.decode(), b'abc'.decode('ascii'))
print(repr(b'\x00\x7f\x80'), b"it's", b'a"b', b'\t\n', bytes([200]).hex(), bytes.fromhex('41 42'), b'ab'.hex(), type(b).__name__)
print(b'abc'.upper(), b' x '.strip(), b'a,b'.split(b','), b'-'.join([b'a', b'b']), b'abc'.startswith(b'ab'), b'abc'.find(b'c'))
for byte in b'hi':
    print('byte', byte)
print(hash(b'ab') == hash(b'ab'), {b'k': 1}[b'k'], b'abc'[-1], bytes(b'copy'), b'' == bytes())

# Ellipsis
print(..., Ellipsis, ... is Ellipsis, type(...).__name__, repr(...), bool(...))

# t-strings: templates, not strs
name = 'world'
count = 3
t = t'hello {name}, {count:03d} times {name!r}'
print(type(t).__name__, t.strings, len(t.interpolations), t.values)
for part in t:
    print(repr(part) if isinstance(part, str) else (part.value, part.expression, part.conversion, part.format_spec))
i = t.interpolations[1]
print(type(i).__name__, i.value, i.expression, i.conversion, i.format_spec)
t2 = t'a' + t'{count}b'
print(t2.strings, t2.values, t'{count}'.strings, t''.strings, t'x'.values)
