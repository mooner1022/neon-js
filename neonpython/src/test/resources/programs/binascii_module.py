# binascii (written in Kotlin as CPython writes it in C), and what CPython's own pure modules make of it: uu, base64
# with its strict mode, hex with separators, quoted-printable, CRC-CCITT and CRC-32, the ascii buffers, CPython's
# errors; base64 and quopri; the base64, hex, uu and quoted-printable codecs.
import binascii
import base64
import codecs
import io
import quopri


def show(f, *args, **kw):
    try:
        return repr(f(*args, **kw))
    except Exception as e:
        return '%s: %s' % (type(e).__name__, e)


class Truth:
    def __bool__(self):
        raise ValueError('no truth')


def names():
    print([n for n in dir(binascii) if not n.startswith('__')], binascii.__doc__)
    print(binascii.Error.__mro__, binascii.Incomplete.__mro__, binascii.Error.__module__, binascii.Incomplete.__module__)
    print(issubclass(binascii.Error, ValueError), binascii.Error('x').args, show(binascii.a2b_hex, b'0'))


def uu():
    for s in [b'', b' ', b'!', b'!!', b'#86)C\n', b'#86)C  `\r\n', b'#86)C x', b'#8\x7f)C', b'`', b'M' + b'a' * 60, b'\x7f', b'!8```']:
        print(s, show(binascii.a2b_uu, s))
    for d in [b'', b'\0\0\0', b'abc', b'x' * 45, b'x' * 46, bytes(range(40))]:
        print(d[:8], show(binascii.b2a_uu, d), show(binascii.b2a_uu, d, backtick=True))
    print(show(binascii.b2a_uu, b'ab', True), show(binascii.b2a_uu, data=b'a'), show(binascii.b2a_uu, b'a', backtick=[]), show(binascii.b2a_uu, b'x', backtick=Truth()))
    print(binascii.a2b_uu(binascii.b2a_uu(bytes(range(45)))) == bytes(range(45)), binascii.a2b_uu(binascii.b2a_uu(b'\0\1', backtick=True)))


def b64():
    for s in [b'', b'YQ==', b'YQ', b'YQ=', b'YQ===', b'=YQ==', b'Y=Q==', b'YQ==YQ==', b'YQ==\n', b'Y Q = =', b'YWJj', b'YWJjZA', b'Y',
              b'YWJjZ', b'Y===', b'YQ=a', b'YQ==a', b'YQ=\n=', b'a\xffb=', b'==', b'YWJj=', b'YWJj==', b'YW=Jj', b'YW==Jj', b'YW+/', b'-_==']:
        print(s, show(binascii.a2b_base64, s), show(binascii.a2b_base64, s, strict_mode=True))
    for d in [b'', b'a', b'ab', b'abc', b'abcd', bytes(range(256))[:50]]:
        print(d[:4], binascii.b2a_base64(d), binascii.b2a_base64(d, newline=False))
    print(show(binascii.a2b_base64, b'YQ==', True), show(binascii.a2b_base64, data=b'YQ=='), show(binascii.b2a_base64, b'a', newline=0), show(binascii.b2a_base64, b'x', newline=Truth()))
    data = bytes(range(256)) * 3
    print(binascii.a2b_base64(binascii.b2a_base64(data)) == data, binascii.a2b_base64('YWJj'), show(binascii.a2b_base64, 'YWJj' + chr(233)))


def hexes():
    print(show(binascii.a2b_hex, b'0g'), show(binascii.a2b_hex, b'abc'), show(binascii.a2b_hex, b'ABcd09'), show(binascii.a2b_hex, ''), show(binascii.unhexlify, b'\xff\xff'), show(binascii.a2b_hex, b'0 '))
    d = b'\xb9\x01\xef\x02\x03'
    cases = [(None, None), (':', None), (b'_', 2), ('-', -2), (':', 0), ('::', 1), ('', 1), (b'\xff', 1), (chr(233), 1), (chr(300), 1),
             (bytearray(b':'), 1), (memoryview(b':'), 1), (3, 1), (None, 1), (':', 2 ** 31), (':', -2 ** 31), (':', 5), (':', 6), (':', -5), (':', 4), (':', -4), (':', 1.0), ('x', True)]
    for sep, n in cases:
        a = (d,) if sep is None and n is None else (d, sep) if n is None else (d, sep, n)
        print('memoryview' if isinstance(sep, memoryview) else repr(sep), n, show(binascii.b2a_hex, *a), show(binascii.hexlify, *a))
    print(show(binascii.b2a_hex, b'', ':', 2), show(binascii.hexlify, b'a', ':', 2), show(binascii.hexlify, data=b'ab', sep=':', bytes_per_sep=1),
          show(binascii.hexlify, b'ab', bytes_per_sep=1), show(binascii.hexlify, b'ab', sepp=':'), show(binascii.b2a_hex, b'\x00\xff', ''))
    print(binascii.unhexlify(binascii.hexlify(bytes(range(256)))) == bytes(range(256)), binascii.hexlify(bytes(range(16)), ' ', -3))


def qp_decode():
    for s in [b'', b'abc', b'a=b', b'=', b'=4', b'=41', b'=4g', b'=\n', b'a=\r\nb', b'a=\rb', b'a=  \nb', b'==41', b'_x_', b'a\x80b', b'=a1=A1=aZ', b'a=', b'a=\r', b'=\r\n=']:
        print(s, show(binascii.a2b_qp, s), show(binascii.a2b_qp, s, header=True))
    print(show(binascii.a2b_qp, b'a', 1, 2), show(binascii.a2b_qp, data='a=41'), show(binascii.a2b_qp, 'a' + chr(233)), show(binascii.a2b_qp))


def qp_encode():
    for s in [b'', b'abc', b'a b', b'a\tb', b'a ', b'a\t', b'a \nb', b'a\t\r\nb', b'a\r\nb\nc', b'=', b'_', b'.', b'.\n', b'a\n.\n', b'.x',
              b'\x00\x01\x7f\x80\xff', b'x' * 80, b'x' * 75 + b'=', b'x' * 74 + b' y', b'a' * 76 + b'\n', b'\r', b'a\rb', b'\n', b'a  \r\nb  \n',
              b'x' * 73 + b'\x01\x02', b' ', b'\t', b'a .', b'x' * 160, b'a\r\n' + b'y' * 80 + b'\r\n', b'line\nother\r\n', b'.\r\n', b'\x00.\n']:
        print(s[:12], show(binascii.b2a_qp, s), show(binascii.b2a_qp, s, quotetabs=True), show(binascii.b2a_qp, s, istext=False),
              show(binascii.b2a_qp, s, header=True), show(binascii.b2a_qp, s, True, False, True))
    print(show(binascii.b2a_qp, b'a', 1, 2, 3, 4), show(binascii.b2a_qp, data=b'a b', header=1), show(binascii.b2a_qp, b'x', quotetabs=None), show(binascii.b2a_qp, b'x', Truth()))


def crcs():
    print(show(binascii.crc_hqx, b'abc'), show(binascii.crc_hqx, b'abc', 0), show(binascii.crc_hqx, b'abc', -1), show(binascii.crc_hqx, b'abc', 2 ** 70 + 3),
          show(binascii.crc_hqx, b'abc', 1.0), show(binascii.crc_hqx, b'abc', 0, 1), show(binascii.crc_hqx, b'abc', crc=1), binascii.crc_hqx(bytes(range(256)), 0xffff))
    print(show(binascii.crc32, b'abc'), show(binascii.crc32, b'abc', 5), show(binascii.crc32, b'abc', -5), show(binascii.crc32, b'', 2 ** 33 + 7),
          show(binascii.crc32, b'a' * 10000), show(binascii.crc32, b'abc', crc=1), show(binascii.crc32), show(binascii.crc32, 'abc'))
    print(binascii.crc32(b'world', binascii.crc32(b'hello ')) == binascii.crc32(b'hello world'), binascii.crc32(memoryview(b'xyz')), binascii.crc32(bytearray(b'xyz')))


def buffers():
    e = chr(233)
    for f in (binascii.a2b_uu, binascii.a2b_base64, binascii.a2b_hex, binascii.unhexlify, binascii.a2b_qp):
        print(f.__name__, show(f, 'abc' + e), show(f, 3), show(f, None), show(f, memoryview(b'0011')), show(f, bytearray(b'0011')), show(f, '00'), show(f))
    for f in (binascii.b2a_uu, binascii.b2a_base64, binascii.b2a_hex, binascii.hexlify, binascii.b2a_qp, binascii.crc32):
        print(f.__name__, show(f, 'abc'), show(f, 3), show(f, memoryview(b'0011')), show(f, bytearray(b'0011')), show(f))
    print(show(binascii.a2b_uu, b'x', backtick=1), show(binascii.a2b_hex, data=b'00'), show(binascii.a2b_hex, b'00', b'11'), show(binascii.b2a_base64, memoryview(b'abcdef')[::2]),
          show(binascii.a2b_base64, memoryview(b'abcdef')[::2]))


def base64_module():
    data = bytes(range(256)) + b'hello world'
    print(base64.b64encode(data)[:40], base64.b64decode(base64.b64encode(data)) == data, sorted(base64.__all__))
    print(base64.urlsafe_b64encode(b'\xfb\xff\xfe'), base64.urlsafe_b64decode(b'-__-'), base64.standard_b64encode(b'xyz'), base64.standard_b64decode(b'eHl6'))
    print(base64.b64encode(b'abc', altchars=b'-_'), show(base64.b64decode, b'YWJj\n', validate=True), show(base64.b64decode, 'YWJj'), show(base64.b64decode, 'YWJ'))
    print(base64.b32encode(data)[:40], base64.b32decode(base64.b32encode(data)) == data, base64.b32hexencode(b'abcde'), base64.b32hexdecode(b'C5H66P35'))
    print(show(base64.b32decode, b'MFRGG==='), show(base64.b32decode, b'mfrgg===', casefold=True), show(base64.b32decode, b'MFRGG=='), show(base64.b32decode, b'0FRGG===', map01=b'L'))
    print(base64.b16encode(b'\x01\xab'), base64.b16decode(b'01AB'), show(base64.b16decode, b'01ab'), base64.b16decode(b'01ab', casefold=True))
    print(base64.a85encode(data)[:40], base64.a85decode(base64.a85encode(data)) == data, base64.a85encode(b'abc', adobe=True), base64.a85encode(b'\0\0\0\0    ', foldspaces=True))
    print(base64.a85encode(data, wrapcol=20, pad=True)[:50], show(base64.a85decode, b'<~@:E_~>', adobe=True), show(base64.a85decode, b'abc~'))
    print(base64.b85encode(data)[:40], base64.b85decode(base64.b85encode(data)) == data, base64.b85encode(b'abc', pad=True), show(base64.b85decode, b'|NsC0~'))
    print(base64.z85encode(b'\x86\x4F\xD2\x6F\xB5\x59\xF7\x5B'), base64.z85decode(b'HelloWorld'))
    print(base64.encodebytes(data)[:80], base64.decodebytes(base64.encodebytes(data)) == data, show(base64.encodebytes, 'x'))
    out = io.BytesIO()
    base64.encode(io.BytesIO(data * 3), out)
    back = io.BytesIO()
    base64.decode(io.BytesIO(out.getvalue()), back)
    print(len(out.getvalue()), out.getvalue()[-20:], back.getvalue() == data * 3)


def quopri_module():
    qp = b'h\xe9llo = world \t\nline two with trailing space \nlong ' + b'x' * 90 + b'\n'
    e = quopri.encodestring(qp)
    print(e, quopri.decodestring(e) == qp, quopri.encodestring(qp, quotetabs=True)[:40], quopri.encodestring(b'a b_c', header=True), quopri.decodestring(b'a_b=3Dc', header=True))
    o = io.BytesIO()
    quopri.encode(io.BytesIO(qp), o, quotetabs=False)
    o2 = io.BytesIO()
    quopri.decode(io.BytesIO(e), o2)
    print(o.getvalue() == e, o2.getvalue() == qp)


def codecs_of_bytes():
    for codec in ('base64', 'base64_codec', 'base_64', 'hex', 'hex_codec', 'uu', 'uu_codec', 'quopri', 'quotedprintable', 'quoted_printable'):
        enc = codecs.encode(b'hello world', codec)
        print(codec, enc, codecs.decode(enc, codec), codecs.lookup(codec).name)
    print(show(codecs.encode, 'text', 'base64'), show(codecs.decode, b'zz', 'hex'), show(b'abc'.decode, 'hex'), show('abc'.encode, 'base64'))
    ie = codecs.getincrementalencoder('base64')()
    idd = codecs.getincrementaldecoder('hex')()
    print(ie.encode(b'abc') + ie.encode(b'def', final=True), idd.decode(b'6162') + idd.decode(b'63', final=True))
    sw = io.BytesIO()
    w = codecs.getwriter('uu')(sw)
    w.write(b'stream')
    print(sw.getvalue(), codecs.decode(b'begin 666 <data>\n&<W1R96%M\n \nend\n', 'uu'), show(codecs.decode, b'nothing', 'uu'))


names()
uu()
b64()
hexes()
qp_decode()
qp_encode()
crcs()
buffers()
base64_module()
quopri_module()
codecs_of_bytes()
