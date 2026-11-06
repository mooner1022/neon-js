# codecs (the registry, _codecs and the error handlers in Kotlin as CPython writes them in C; codecs.py and the
# encodings package CPython's own): str.encode and bytes.decode, every builtin error handler, handlers of one's own,
# incremental and stream codecs, UnicodeError's objects, CPython's errors.
import codecs
import _codecs
import warnings


def show(f, *args, **kw):
    try:
        return repr(f(*args, **kw))
    except Exception as e:
        notes = getattr(e, '__notes__', None)
        return '%s: %s%s' % (type(e).__name__, e, ' %r' % notes if notes else '')


def checksum(seq):
    h = 0
    for i, c in enumerate(seq):
        h = (h * 1000003 + (c if isinstance(c, int) else ord(c)) + i) % 2305843009213693951
    return h


HANDLERS = ['strict', 'replace', 'ignore', 'backslashreplace', 'xmlcharrefreplace', 'namereplace', 'surrogateescape', 'surrogatepass']


def text_encodings():
    text = 'h\xe9 \u20ac\U0001F600\udc80'
    for enc in ['utf-8', 'UTF8', 'utf_16', 'utf-16-le', 'UTF-16BE', 'utf-32', 'utf_32_le', 'utf-32-be', 'ascii', 'us-ascii', 'latin-1', 'L1', 'iso8859-1', 'cp1252', 'utf-7']:
        print(enc, [show(text.encode, enc, h) for h in HANDLERS])
    data = b'h\xc3\xa9 \xe2\x82\xac\xf0\x9f\x98\x80 \xff\xed\xa0\x80\xe2\x82'
    for enc in ['utf-8', 'utf8', 'ascii', 'latin-1', 'cp1252', 'utf-16', 'utf-16-be', 'utf-32-le', 'utf-7']:
        print(enc, [show(data.decode, enc, h) for h in HANDLERS if h not in ('xmlcharrefreplace', 'namereplace')])
    print(show('x'.encode), show('x'.encode, encoding='ascii', errors='strict'), show('x'.encode, 5), show('x'.encode, 'ascii', 5),
          show('x'.encode, 'ascii', 'strict', 3), show('x'.encode, foo=1), show('x'.encode, 'utf\x008'), show('x'.encode, 'nope'), show(''.encode, 'nope'))
    print(show(b'x'.decode, 5), show(b'x'.decode, 'ascii', errors=5), show(bytearray(b'x\xe9').decode, encoding='latin-1'), show(b''.decode, 'nope'),
          show(b'x'.decode, 'nope'), show('abc'.encode, 'ascii', 'nope'), show('\xe9'.encode, 'ascii', 'nope'), show(b'\xe9'.decode, 'ascii', 'nope'))
    print(show(str, b'h\xe9', 'cp1252'), show(str, b'\xe9', errors='replace'), show(str, b'', 'nope'), show(str, 'x', 'utf-8'), show(str, 5, 'utf-8'),
          show(str, memoryview(b'h\xc3\xa9'), 'utf-8'), show(str, bytearray(b'\x80'), 'cp1252'), show(str, b'x', 5))
    print(show(bytes, 'h\xe9', 'cp1252'), show(bytes, '\xe9', 'ascii', 'replace'), show(bytes, 'x', 5), show(bytes, 'x', 'ascii', 5), show(bytes, 'x'),
          show(bytearray, '\xe9\u20ac', 'ascii', 'xmlcharrefreplace'), show(bytearray, 'x', 5), show(bytes, 'abc', 'rot13'))
    print(show('abc'.encode, 'rot13'), show(b'abc'.decode, 'rot13'), show(codecs.encode, 'abc', 'rot13'), show(codecs.decode, 'Uryyb', 'rot-13'),
          show(codecs.encode, 'abc'), show(codecs.decode, b'abc'), show(codecs.encode, b'abc'), show(codecs.decode, 'abc'), show(codecs.encode, obj='x'), show(codecs.encode))


def utf_details():
    for b in [b'\xe2\x82', b'\xe2\x82\x41', b'\xff\xfe', b'\xe0\x80', b'\xed\xa0', b'\xed\xa0\x80', b'\xf0\x90\x80', b'\xf0\x90\x80\x41', b'\xf4\x90', b'\xc0\xaf', b'a\x80b', b'\xf0\x9f\x98\x80']:
        print(b, [show(_codecs.utf_8_decode, b, e, f) for e in ('strict', 'replace', 'surrogateescape', 'backslashreplace') for f in (False, True)])
    for b in [b'', b'\xff', b'\xff\xfe', b'\xfe\xff\x00a', b'a\x00\x00\xd8', b'a\x00\x00\xd8b\x00', b'a\x00\x00\xdcb\x00', b'\x3d\xd8\x00\xde\x41']:
        print(b, [show(f, b, 'replace', final) for f in (_codecs.utf_16_decode, _codecs.utf_16_le_decode, _codecs.utf_16_be_decode) for final in (False, True)],
              show(_codecs.utf_16_ex_decode, b, None, 0, False), show(_codecs.utf_16_ex_decode, b, None, 1, True), show(b.decode, 'utf-16', 'surrogatepass'))
    for b in [b'\xff\xfe\x00\x00', b'\x00\x00\xfe\xffa', b'a\x00\x00\x00\x00\xd8\x00\x00', b'a\x00\x00\x00\x00\x00\x11\x00', b'a\x00\x00\x00\x00', b'\x00\xf6\x01\x00']:
        print(b, [show(f, b, 'replace', final) for f in (_codecs.utf_32_decode, _codecs.utf_32_le_decode, _codecs.utf_32_be_decode) for final in (False, True)],
              show(_codecs.utf_32_ex_decode, b, None, 0, False), show(b.decode, 'utf-32', 'surrogatepass'))
    for s in ['a', '\U0001F600', 'a\udc80b', '\ud800']:
        print(repr(s), [show(s.encode, n, e) for n in ('utf-16', 'utf-16-be', 'utf-32-le') for e in ('strict', 'replace', 'surrogatepass', 'backslashreplace')])
    print(show(_codecs.utf_7_encode, 'Hi Mom -\u263a-!A+B~\\\U0001F600'), show(_codecs.utf_7_decode, b'Hi Mom -+Jjo--!A+-B'), show(_codecs.utf_7_decode, b'a+2D3eAA-b'))
    for b in [b'a+2D3e', b'a+Jjo', b'a+Jj', b'a+!b', b'a\x80b\\c~', b'+AGEAYgBj', b'+AOkA6QDp', b'+ZeVnLIqe-']:
        print(b, [show(_codecs.utf_7_decode, b, e, f) for e in ('strict', 'replace') for f in (False, True)])


def escapes():
    print(show(_codecs.unicode_escape_encode, 'a\\\t\n\r\x00\x7f\xe9\u20ac\U0001F600"\''), show(_codecs.raw_unicode_escape_encode, 'a\\\xe9\u20ac\U0001F600'))
    for b in [b'a\\t\\x41\\u20ac\\U0001F600\\N{EURO SIGN}\\101\\\n\\"', '\xe9\\u20ac', b'\\x4', b'\\x4g', b'ab\\', b'\\U00110000', b'\\N{NOPE}', b'\\N{EURO', b'\\N', b'\\N{}', b'\\N{euro sign}', b'\\N{CJK UNIFIED IDEOGRAPH-4E00}', b'\\N{HANGUL SYLLABLE GAG}']:
        print(repr(b), [show(_codecs.unicode_escape_decode, b, e, f) for e in ('strict', 'replace', 'backslashreplace') for f in (False, True)])
    for b in [b'a\\u20ac\\U0001F600\\x41\\', b'a\\', b'a\\u20', b'a\\U00110000', b'\xe9', '\xe9\\u20ac']:
        print(repr(b), [show(_codecs.raw_unicode_escape_decode, b, e, f) for e in ('strict', 'replace') for f in (False, True)])
    print(show(_codecs.escape_encode, b'\x00\'"\\\t\n\r\x7f\x80 a'), show(_codecs.escape_encode, 'x'), show(_codecs.escape_encode, bytearray(b'x')))
    print([show(_codecs.escape_decode, b, e) for b in (b'ab\\x41', 'ab\\x41', b'ab\\x4', b'ab\\x4g', b'ab\\', b'\\101\\n\\\n') for e in (None, 'replace', 'ignore', 'bogus')])
    for f in [lambda: b'\\q\\777'.decode('unicode_escape'), lambda: codecs.escape_decode(b'\\777\\q'), lambda: b'\\8'.decode('unicode_escape')]:
        with warnings.catch_warnings(record=True) as w:
            warnings.simplefilter('always')
            print(show(f), [(x.category.__name__, str(x.message)) for x in w])
    print(show(_codecs.readbuffer_encode, 'h\xe9'), show(_codecs.readbuffer_encode, bytearray(b'x'), 'e'), show(_codecs.readbuffer_encode, 5))


def charmaps():
    m = _codecs.charmap_build('\x00abc')
    print(type(m).__name__, m.size(), show(_codecs.charmap_build, 'abc'), show(_codecs.charmap_build, ''), show(_codecs.charmap_build, b'x'), show(_codecs.charmap_build, '\x00a\x00'))
    print(show(_codecs.charmap_encode, 'abc', None, m), show(_codecs.charmap_encode, 'abd', 'replace', _codecs.charmap_build('\x00abc?')),
          show(_codecs.charmap_encode, 'abd', 'replace', m), show(_codecs.charmap_encode, 'abd', 'xmlcharrefreplace', _codecs.charmap_build('\x00abcd&#0123456789;')),
          show(_codecs.charmap_encode, 'abd\xe9', 'backslashreplace', {ord(c): ord(c) for c in 'abcdx\\0123456789e'}))
    print(show(_codecs.charmap_encode, 'ab', None, {97: 300}), show(_codecs.charmap_encode, 'ab', None, {97: 'x'}), show(_codecs.charmap_encode, 'ab', None, {97: b'xy', 98: None}),
          show(_codecs.charmap_encode, 'ab', None), show(_codecs.charmap_encode, 'a\u20ac', None))
    print(show(_codecs.charmap_decode, b'abc', None, 'xyz'), show(_codecs.charmap_decode, b'abc', None, 'xy'), show(_codecs.charmap_decode, b'abc', 'replace', 'xy\ufffe'),
          show(_codecs.charmap_decode, b'abc', None, {97: 'x', 98: 0x20ac, 99: 'yz'}), show(_codecs.charmap_decode, b'abc', 'ignore', {97: None, 98: 0xfffe, 99: '\ufffe'}),
          show(_codecs.charmap_decode, b'a', None, {97: 0x110000}), show(_codecs.charmap_decode, b'a', None, {97: b'x'}), show(_codecs.charmap_decode, b'abc', 'replace', ['x']), show(_codecs.charmap_decode, b'\xe9'))


def bundled():
    allbytes = bytes(range(256))
    sample = ''.join(map(chr, range(0, 0x600, 3))) + '\u20ac\u2122\uff21\U0001F600\udc80'
    for n in ['cp037', 'cp1006', 'cp1026', 'cp1125', 'cp1140', 'cp1250', 'cp1251', 'cp1252', 'cp1253', 'cp1254', 'cp1255', 'cp1256', 'cp1257',
              'cp1258', 'cp273', 'cp424', 'cp437', 'cp500', 'cp720', 'cp737', 'cp775', 'cp850', 'cp852', 'cp855', 'cp856', 'cp857', 'cp858', 'cp860',
              'cp861', 'cp862', 'cp863', 'cp864', 'cp865', 'cp866', 'cp869', 'cp874', 'cp875', 'hp_roman8', 'iso8859_10', 'iso8859_11', 'iso8859_13',
              'iso8859_14', 'iso8859_15', 'iso8859_16', 'iso8859_2', 'iso8859_3', 'iso8859_4', 'iso8859_5', 'iso8859_6', 'iso8859_7', 'iso8859_8',
              'iso8859_9', 'koi8_r', 'koi8_t', 'koi8_u', 'kz1048', 'mac_arabic', 'mac_croatian', 'mac_cyrillic', 'mac_farsi', 'mac_greek', 'mac_iceland',
              'mac_latin2', 'mac_roman', 'mac_romanian', 'mac_turkish', 'palmos', 'ptcp154', 'tis_620', 'utf_8_sig', 'raw_unicode_escape', 'unicode_escape']:
        out = [n, codecs.lookup(n).name]
        for errs in ('replace', 'backslashreplace'):
            with warnings.catch_warnings():
                # (unicode_escape's for the escapes Python has not)
                warnings.simplefilter('ignore')
                s = allbytes.decode(n, errs)
            out.append('%d:%d' % (len(s), checksum(s)))
        for errs in ('replace', 'backslashreplace', 'xmlcharrefreplace', 'ignore'):
            b = sample.encode(n, errs)
            out.append('%d:%d' % (len(b), checksum(b)))
        print(' '.join(out))
    for n in ['UTF-8', 'U8', 'UTF', 'Latin-1', 'ISO-8859-1', '8859', 'cp819', 'US-ASCII', '646', 'ANSI_X3.4_1968', 'UTF16', 'utf_16le', 'U32', 'U7',
              'utf-8-sig', 'cp65001', 'windows-1252', 'koi8-r', 'macintosh', 'unicode-escape', 'rot13', 'punycode', 'undefined', 'csASCII', ' utf 8 ', 'utf..8', 'csHPRoman8', 'no-such-codec']:
        print(repr(n), show(lambda: codecs.lookup(n).name))
    print(show('b\xfccher'.encode, 'punycode'), show(b'bcher-kva'.decode, 'punycode'), show(b'bcher-kv'.decode, 'punycode'), show('abc'.encode, 'undefined'), show(b'abc'.decode, 'undefined'))
    print(show('a\xe9'.encode, 'utf-8-sig'), show(b'\xef\xbb\xbfa'.decode, 'utf-8-sig'), show(b'a'.decode, 'utf-8-sig'), show('\u65e5\u672c\u8a9e'.encode, 'utf-7'), show('Hello'.encode, 'rot13'))


def handlers():
    for n in HANDLERS:
        print(n, codecs.lookup_error(n))
    E = UnicodeEncodeError
    calls = [('ignore', E('a', 'xyz', 1, 2, 'r')), ('replace', E('a', 'xyz', 1, 3, 'r')), ('replace', UnicodeDecodeError('a', b'xyz', 1, 3, 'r')),
             ('replace', UnicodeTranslateError('xyz', 1, 3, 'r')), ('xmlcharrefreplace', E('a', 'x\u20ac\U0001F600', 1, 3, 'r')),
             ('xmlcharrefreplace', UnicodeDecodeError('a', b'x', 0, 1, 'r')), ('backslashreplace', E('a', 'x\xe9\u20ac\U0001F600', 1, 4, 'r')),
             ('backslashreplace', UnicodeDecodeError('a', b'x\xff\x80', 1, 3, 'r')), ('backslashreplace', UnicodeTranslateError('x\xe9', 1, 2, 'r')),
             ('namereplace', E('a', 'x\xe9\u20ac\U0001F600\x80\ue000\u4e00\uac00\uf900\x00\ud800', 1, 11, 'r')),
             ('surrogateescape', E('a', 'x\udc80\udcff', 1, 3, 'r')), ('surrogateescape', E('a', 'x\udc7f', 1, 2, 'r')),
             ('surrogateescape', UnicodeDecodeError('a', b'x\x80\x81\x82\x83\x84', 1, 6, 'r')), ('surrogateescape', UnicodeDecodeError('a', b'x\x10', 1, 2, 'r')),
             ('surrogatepass', E('UTF_16_be', 'x\ud800', 1, 2, 'r')), ('surrogatepass', E('utf-32', 'x\ud800', 1, 2, 'r')), ('surrogatepass', E('latin-1', 'x\ud800', 1, 2, 'r')),
             ('surrogatepass', E('utf-8', 'x\udc80', 1, 2, 'r')), ('surrogatepass', UnicodeDecodeError('utf-8', b'x\xed\xa0\x80', 1, 4, 'r')),
             ('surrogatepass', UnicodeDecodeError('utf-16-le', b'x\x00\xd8', 1, 3, 'r')), ('ignore', E('a', 'xyz', 5, 9, 'r')), ('replace', E('a', 'xyz', -5, -9, 'r')),
             ('backslashreplace', E('a', 'xyz', 2, 1, 'r')), ('replace', E('a', '', 0, 0, 'r')), ('strict', E('a', 'x', 0, 1, 'r')), ('strict', KeyError('k')),
             ('strict', 5), ('ignore', ValueError()), ('namereplace', UnicodeTranslateError('x\u20ac', 1, 2, 'r')), ('replace', UnicodeEncodeError.__new__(UnicodeEncodeError))]
    for name, exc in calls:
        print(name, show(codecs.lookup_error(name), exc))
    print(show(codecs.lookup_error('ignore')), show(codecs.lookup_error('ignore'), 1, 2), show(codecs.register_error, 'x', 5), show(codecs.lookup_error, 'nope'),
          show(codecs.register_error, 5, len), show(codecs.lookup_error, 5), show(_codecs._unregister_error, 'strict'), show(_codecs._unregister_error, 'nope'))


def own_handlers():
    log = []

    def logging(e):
        log.append((type(e).__name__, e.encoding, e.start, e.end, e.reason))
        if isinstance(e, UnicodeDecodeError):
            return ('[%s]' % e.object[e.start:e.end].hex(), e.end)
        return ('<%s>' % e.object[e.start:e.end], e.end)
    codecs.register_error('test.log', logging)
    print(show('a\xe9\u20acb\U0001F600'.encode, 'ascii', 'test.log'), show('a\xe9\u20acb'.encode, 'latin-1', 'test.log'), show('a\udc80\udcffb'.encode, 'utf-8', 'test.log'),
          show('a\u20acb'.encode, 'cp437', 'test.log'), show(b'a\xff\x80b\xe2\x82'.decode, 'utf-8', 'test.log'), show(b'a\x81\x8db'.decode, 'cp1252', 'test.log'),
          show(b'\x00a\xd8\x00'.decode, 'utf-16-be', 'test.log'), show(b'a+AGE\x80'.decode, 'utf-7', 'test.log'), show(b'\\u12 \\x1'.decode, 'unicode_escape', 'test.log'))
    for x in log:
        print(x)
    for name, result in [('a', 1), ('b', ('x',)), ('d', (1, 1)), ('e', ('x', 'y')), ('f', ('x', 100)), ('g', ('x', -1)), ('h', ('x', -100)), ('l', ('x', 2 ** 70)),
                         ('m', ('x', 1.0)), ('i', (b'X', 'end')), ('j', ('\xe9', 'end')), ('k', ['x', 'end']), ('n', (bytearray(b'z'), 'end')), ('o', (b'XY', 'end')),
                         ('p', ('\u20ac', 'end')), ('q', ('ab', 'end'))]:
        codecs.register_error('test.' + name, lambda e, r=result: (r[0], e.end) if isinstance(r, tuple) and r[1:] == ('end',) else (r if not isinstance(r, list) else [r[0], e.end]))
        cases = [('a\xe9\xe9b', 'ascii'), ('a\xe9\u20acb', 'latin-1'), ('a\udc80\udc81b', 'utf-8'), ('a\udc80b', 'utf-16-le'), ('a\udc80b', 'utf-32'), ('a\u20ac\u20acb', 'cp437')]
        print(name, [show(s.encode, enc, 'test.' + name) for s, enc in cases],
              [show(b.decode, enc, 'test.' + name) for b, enc in [(b'a\xff\xfeb', 'ascii'), (b'a\xff\xfeb', 'utf-8'), (b'a\x81b', 'cp1252')]])

    def swap(e):
        e.object = b'XYZ\xffQ'
        return ('?', 4)
    codecs.register_error('test.swap', swap)
    print(show(b'\xffabcdef'.decode, 'ascii', 'test.swap'), show(b'\xffabcdef'.decode, 'utf-8', 'test.swap'))

    def counting(e):
        counting.n = getattr(counting, 'n', 0) + 1
        return ('<%d>' % counting.n, e.end if counting.n > 3 else e.start - 1)
    codecs.register_error('test.counting', counting)
    print(show('ab\xe9c'.encode, 'ascii', 'test.counting'), show(_codecs._unregister_error, 'test.counting'), show(_codecs._unregister_error, 'test.counting'), show(codecs.lookup_error, 'test.counting'))


def error_objects():
    print(show(UnicodeEncodeError), show(UnicodeEncodeError, 1, 2, 3, 4, 5), show(UnicodeEncodeError, 'a', b'x', 3, 4, 'r'), show(UnicodeEncodeError, 'a', 'x', '3', 4, 'r'),
          show(UnicodeEncodeError, 'a', 'x', 3, 4, b'r'), show(UnicodeEncodeError, 'a', 'x', 3, 4, 'r', 6), show(UnicodeDecodeError, 'a', 'x', 3, 4, 'r'),
          show(lambda: UnicodeDecodeError('a', bytearray(b'xy'), 0, 1, 'r').object), show(lambda: UnicodeTranslateError('x', 0, 1, 'r').encoding),
          show(UnicodeTranslateError, 'x', 0, 1), show(UnicodeEncodeError, 'a', 'x', 0, 1, 'r', x=1))
    e = UnicodeEncodeError('ascii', 'h\xe9llo', 1, 2, 'bad')
    print(str(e), repr(e), e.args, e.encoding, e.object, e.start, e.end, e.reason)
    for start, end in [(1, 3), (1, 1), (1, 100), (-5, 100)]:
        e.start, e.end = start, end
        print(str(e))
    for name, value in [('start', 'x'), ('start', 2 ** 70), ('start', True), ('encoding', 5), ('reason', 5), ('object', b'x')]:
        try:
            setattr(e, name, value)
            print(name, getattr(e, name), show(str, e))
        except Exception as x:
            print(name, type(x).__name__, x)
    print(show(delattr, e, 'start'), show(delattr, e, 'encoding'), e.encoding)
    blank = UnicodeEncodeError.__new__(UnicodeEncodeError)
    print(repr(str(blank)), blank.start, blank.object, UnicodeError('a').__dict__, hasattr(UnicodeError('a'), 'start'))
    d = UnicodeDecodeError('utf-8', b'\xff\xfe', 0, 1, 'invalid start byte')
    print(str(d), repr(d))
    d.end = 2
    print(str(d))
    t = UnicodeTranslateError('\U0001F600ab', 0, 1, 'oops')
    print(str(t), repr(t))
    t.end = 3
    print(str(t), str(UnicodeEncodeError('ascii', '\u20ac', 0, 1, 'r')), str(UnicodeEncodeError('ascii', '\U0001F600', 0, 1, 'r')))
    try:
        try:
            raise KeyError('k')
        except KeyError:
            b'\x81'.decode('cp1252')
    except UnicodeDecodeError as x:
        print(repr(x), repr(x.__context__), x.__notes__, x.args)
    try:
        'a\xe9\xe9b'.encode('ascii')
    except UnicodeEncodeError as x:
        print(repr(x), x.start, x.end, getattr(x, '__notes__', None))


calls = []


def search(name):
    calls.append(name)
    if name == 'test_codec':
        return codecs.CodecInfo(lambda s, errors='strict': (s.upper().encode('ascii', errors), len(s)),
                                lambda b, errors='strict': (bytes(b).decode('ascii', errors).lower(), len(b)), name='test-codec')
    if name == 'tuple_codec':
        return (lambda s, e='strict': (b'T', 1), lambda b, e='strict': ('t', 1), None, None)
    if name == 'bad_codec':
        return (1, 2, 3)
    if name == 'not_text':
        return codecs.CodecInfo(lambda s, e='strict': (s, len(s)), lambda b, e='strict': (b, len(b)), name='not-text', _is_text_encoding=False)
    if name == 'returns_str':
        return codecs.CodecInfo(lambda s, e='strict': ('str!', 1), lambda b, e='strict': (b'bytes!', 1), name='returns-str')
    if name == 'one_item':
        return codecs.CodecInfo(lambda s, e='strict': (b'x',), lambda b, e='strict': 5, name='one')
    if name == 'raises':
        def boom(*a):
            raise ValueError('boom')
        return codecs.CodecInfo(boom, boom, name='raises')
    if name == 'errors_seen':
        return codecs.CodecInfo(lambda s, *a: (repr(a).encode(), 1), lambda b, *a: ('%s %r' % (type(b).__name__, a), 1), name='errors-seen')
    return None


def registry():
    codecs.register(search)
    print(show('Hello'.encode, 'test_codec'), show('Hello'.encode, 'Test-Codec'), show(b'HELLO'.decode, 'test codec'), codecs.lookup('test_codec').name,
          codecs.lookup('TEST_CODEC') is codecs.lookup('test_codec'), show('x'.encode, 'tuple_codec'), show(b'x'.decode, 'tuple-codec'), show(codecs.lookup, 'bad_codec'))
    print(show('x'.encode, 'not_text'), show(b'x'.decode, 'not_text'), show(codecs.encode, 'x', 'not_text'), show('x'.encode, 'returns_str'), show(b'x'.decode, 'returns_str'),
          show('x'.encode, 'one_item'), show(b'x'.decode, 'one_item'), show('x'.encode, 'raises'), show(b'x'.decode, 'raises'), show(codecs.encode, 'x', 'raises'))
    print(show('x'.encode, 'errors_seen'), show('x'.encode, 'errors_seen', 'strict'), show(b'x'.decode, 'errors_seen'), show(codecs.decode, b'x', 'errors_seen', 'ignore'),
          show(str, b'x', 'errors_seen'), show(bytes, 'x', 'errors_seen', 'replace'), show('x'.encode, 'no_such_codec'), show('x'.encode, 'no_such_codec'))
    print([c for c in calls if 'such' in c])
    codecs.unregister(search)
    print(show('x'.encode, 'test_codec'), show(codecs.unregister, search), show(codecs.register, 5), show(codecs.lookup, 'x\x00'), show(codecs.lookup, '\udc80'),
          show(codecs.lookup, 5), show(codecs.lookup), show(_codecs.lookup, encoding='x'), show(_codecs.register), show(_codecs.register_error, 'x'))
    print(show(_codecs.utf_8_decode), show(_codecs.utf_8_decode, b'', 'strict', True, 4), show(_codecs.utf_8_decode, b'', errors='strict'), show(_codecs.utf_8_decode, 'abc'),
          show(_codecs.utf_8_decode, b'abc', 5), show(_codecs.utf_8_decode, bytearray(b'abc'), None, 1), show(_codecs.utf_8_encode, b'abc'), show(_codecs.utf_8_encode, 'abc', 5),
          show(_codecs.utf_16_encode, 'abc', None, 'x'), show(_codecs.utf_16_encode, 'abc', None, 2 ** 40), show(_codecs.utf_16_encode, 'abc', None, 5), show(_codecs.utf_16_encode, 'abc', None, -5))


def incremental():
    text = 'h\xe9llo \u20ac \U0001F600 +x\\'
    for n in ('utf-8', 'utf-16', 'utf-16-be', 'utf-32', 'utf-8-sig', 'utf-7', 'cp1252', 'unicode_escape', 'raw_unicode_escape'):
        t = text if n != 'cp1252' else 'h\xe9llo \u20ac +x'
        enc = codecs.getincrementalencoder(n)()
        data = b''.join(enc.encode(c) for c in t) + enc.encode('', final=True)
        dec = codecs.getincrementaldecoder(n)()
        parts = [dec.decode(data[i:i + 1]) for i in range(len(data))]
        parts.append(dec.decode(b'', final=True))
        print(n, data, parts, ''.join(parts) == t, dec.getstate())
        dec.reset()
        print(n, repr(dec.decode(data[:3])), dec.getstate())
    d = codecs.getincrementaldecoder('utf-8')(errors='replace')
    print(repr(d.decode(b'a\xe2\x82')), repr(d.decode(b'b', final=True)))
    d = codecs.getincrementaldecoder('utf-16')()
    print(repr(d.decode(b'\xfe')), repr(d.decode(b'\xff\x00')), repr(d.decode(b'a')), d.getstate(), show(d.decode, b'\x00', final=True))
    d = codecs.getincrementaldecoder('utf-8')()
    print(show(d.decode, b'\xe2\x82', final=True))
    e = codecs.getincrementalencoder('utf-16')()
    print(e.encode('a'), e.encode('b'), e.getstate())
    e.setstate(0)
    print(e.encode('c'))
    print(list(codecs.iterdecode([b'\xe2', b'\x82', b'\xac', b'a'], 'utf-8')), list(codecs.iterencode(['a', '\u20ac'], 'utf-16')))


class Stream:
    def __init__(self, data=b''):
        self.data = data
        self.out = []

    def read(self, n=-1):
        if n < 0:
            r, self.data = self.data, b''
        else:
            r, self.data = self.data[:n], self.data[n:]
        return r

    def write(self, b):
        self.out.append(b)


def streams():
    w = codecs.getwriter('utf-16')(Stream())
    w.write('ab')
    w.write('\u20ac')
    w.writelines(['c', 'd'])
    print(w.stream.out)
    r = codecs.getreader('utf-8')(Stream('h\xe9llo\nw\u20acrld\n!'.encode('utf-8')))
    print(repr(r.readline()), repr(r.read(3)), repr(r.read()))
    r = codecs.getreader('utf-16')(Stream('ab\ncd'.encode('utf-16')))
    print(r.readlines())
    r = codecs.getreader('cp1252')(Stream(b'\x80\x81'), errors='replace')
    print(repr(r.read()))


def main():
    for part in [text_encodings, utf_details, escapes, charmaps, bundled, handlers, own_handlers, error_objects, registry, incremental, streams]:
        part()


main()
