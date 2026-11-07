# The CJK codecs (CPython's _multibytecodec and _codecs_cn, _jp, _kr, _tw, _hk, _iso2022, under encodings): sampled
# digests of each codec's encoding and decoding, named examples, errors and their handlers, incremental and stream
# use, and what uses them (io, email, xml). PyCjkCodecsTest checks every code point and short sequence.
import codecs
import hashlib
import io

CODECS = ['gb2312', 'gbk', 'gb18030', 'hz', 'big5', 'cp950', 'big5hkscs', 'cp932', 'shift_jis', 'euc_jp',
          'shift_jis_2004', 'euc_jis_2004', 'euc_jisx0213', 'shift_jisx0213', 'euc_kr', 'cp949', 'johab',
          'iso2022_kr', 'iso2022_jp', 'iso2022_jp_1', 'iso2022_jp_2', 'iso2022_jp_2004', 'iso2022_jp_3',
          'iso2022_jp_ext']


def U(*h):
    return ''.join(chr(int(x, 16)) for x in h)


def t(label, f):
    try:
        print(label, '->', ascii(f()))
    except Exception as e:
        print(label, '!!', type(e).__name__, ascii(str(e)))


def samples():
    cps = list(range(0x20, 0x3000, 7)) + list(range(0x3000, 0xa000, 3)) + list(range(0xac00, 0xd7a4, 5)) + \
        list(range(0xe000, 0x10000, 11)) + list(range(0x20000, 0x2b000, 97))
    return [''.join(map(chr, cps[i:i + 200])) for i in range(0, len(cps), 200)]


def digest_encodings(name, chunks):
    h = hashlib.md5()
    hd = hashlib.md5()
    for s in chunks:
        b = s.encode(name, 'backslashreplace')
        h.update(b)
        hd.update(b.decode(name).encode('utf-8'))
    return h.hexdigest()[:12], hd.hexdigest()[:12]


def digest_decodings(name):
    h = hashlib.md5()
    for b1 in range(0x80, 0x100, 3):
        for b2 in range(0x21, 0x100, 5):
            try:
                h.update(bytes((b1, b2)).decode(name).encode('utf-8'))
            except UnicodeDecodeError as e:
                h.update(('%d %d %s' % (e.start, e.end, e.reason)).encode())
    return h.hexdigest()[:12]


def digests():
    chunks = samples()
    for name in CODECS:
        print(name, codecs.lookup(name).name, digest_encodings(name, chunks), digest_decodings(name))


ZH = U('4e2d', '6587', '6d4b', '8bd5')
JA = U('65e5', '672c', '8a9e', '306e', '30c6', '30ad', '30b9', '30c8')
KO = U('d55c', 'ad6d', 'c5b4')
TW = U('7e41', '9ad4', '4e2d', '6587')


def examples():
    for text, names in [(ZH, ['gb2312', 'gbk', 'gb18030', 'hz', 'iso2022_jp_2']), (TW, ['big5', 'cp950', 'big5hkscs']),
                        (JA, ['shift_jis', 'cp932', 'euc_jp', 'iso2022_jp', 'shift_jis_2004', 'euc_jisx0213']),
                        (KO, ['euc_kr', 'cp949', 'johab', 'iso2022_kr'])]:
        for name in names:
            b = text.encode(name)
            print(name, b, b.decode(name) == text)
    t('gb18030 4-byte', lambda: (U('80', 'ffff', '10000', '10ffff').encode('gb18030'), b'\x81\x30\x81\x30\x95\x32\x82\x36'.decode('gb18030')))
    t('euc_kr make-up', lambda: (U('ac02').encode('euc_kr'), b'\xa4\xd4\xa4\xa1\xa4\xbf\xa4\xa2'.decode('euc_kr')))
    t('jisx0213 pair', lambda: (U('304b', '309a').encode('euc_jis_2004'), b'\xa4\xf7'.decode('euc_jis_2004')))
    t('hkscs pair', lambda: (U('ca', '304', 'ea', '30c').encode('big5hkscs'), b'\x88\x62\x88\xa5'.decode('big5hkscs')))
    t('hkscs plane 2', lambda: b'\x87\x40\x88\x40\xfe\xfe'.decode('big5hkscs'))
    t('jisx0213 2000', lambda: (U('9b1d').encode('euc_jisx0213'), U('9b1d').encode('euc_jis_2004', 'replace'), U('9b1c').encode('shift_jisx0213', 'replace')))
    t('iso2022_jp_2 sets', lambda: (U('e9', '3b1', 'd55c', '4e2d', '65e5').encode('iso2022_jp_2', 'replace')))
    t('cp932 compat', lambda: (b'\x80\xa0\xfd'.decode('cp932'), U('ff5e', 'ff3c').encode('cp932')))
    t('hz escapes', lambda: (b'~~ ~{VP~}~\nx'.decode('hz'), (ZH[:1] + '~' + ZH[:1] + 'a' + ZH[:1] + 'a' + ZH[:1] + 'a' + ZH[:1] + '~').encode('hz')))
    t('codec object', lambda: (codecs.lookup('gbk').encode(ZH), codecs.lookup('gbk').decode(b'\xd6\xd0', 'strict'), codecs.lookup('gbk').encode(12)))


def errors():
    s = 'a' + U('20ac', '1f600') + 'b' + U('e01')
    for err in ['strict', 'ignore', 'replace', 'backslashreplace', 'xmlcharrefreplace', 'namereplace']:
        t('shift_jis ' + err, lambda: s.encode('shift_jis', err))
    t('iso2022_jp replace', lambda: (JA[:2] + U('e01') + 'x').encode('iso2022_jp', 'replace'))
    b = b'a\x81\xff\x82b\x81'
    for err in ['strict', 'ignore', 'replace', 'backslashreplace', 'surrogateescape']:
        t('shift_jis dec ' + err, lambda: b.decode('shift_jis', err))
    t('surrogateescape back', lambda: b.decode('shift_jis', 'surrogateescape').encode('shift_jis', 'surrogateescape'))
    seen = []

    def handler(e):
        seen.append((type(e).__name__, e.start, e.end, e.reason))
        return ('[%d]' % e.start, e.end) if isinstance(e, UnicodeEncodeError) else ('<%d>' % e.start, e.end)
    codecs.register_error('cjk.test', handler)
    t('custom encode', lambda: s.encode('euc_kr', 'cjk.test'))
    t('custom decode', lambda: b.decode('big5', 'cjk.test'))
    print('seen', seen)
    codecs.register_error('cjk.bytes', lambda e: (b'?!', e.end))
    t('bytes from handler', lambda: s.encode('gbk', 'cjk.bytes'))
    codecs.register_error('cjk.bad', lambda e: ('x', 10 ** 6))
    t('position out of bounds', lambda: s.encode('gbk', 'cjk.bad'))
    codecs.register_error('cjk.type', lambda e: 'x')
    t('not a tuple', lambda: b.decode('gbk', 'cjk.type'))
    t('unknown handler', lambda: s.encode('gbk', 'cjk.none'))


def incremental():
    data = (JA + 'abc' + JA[:2]).encode('iso2022_jp')
    d = codecs.getincrementaldecoder('iso2022_jp')()
    out = []
    for i in range(len(data)):
        out.append((d.decode(data[i:i + 1]), d.getstate()))
    print('bytewise', ascii(out[:6]), ''.join(x for x, _ in out) == JA + 'abc' + JA[:2], d.getstate())
    d = codecs.getincrementaldecoder('shift_jis')()
    t('pending', lambda: (d.decode(b'a\x93'), d.getstate(), d.decode(b'\xfa'), d.getstate()))
    t('final', lambda: (d.decode(b'\x93'), d.decode(b'', True)))
    t('after error', lambda: d.getstate())
    e = codecs.getincrementalencoder('iso2022_jp')()
    t('encoder', lambda: (e.encode(JA[0]), e.getstate(), e.encode('a'), e.encode(JA[1]), e.encode('', True)))
    e = codecs.getincrementalencoder('euc_jis_2004')()
    t('encoder pair', lambda: (e.encode(U('304b')), e.getstate(), e.encode(U('309a')), e.encode(U('304b')), e.encode('', True)))
    e2 = codecs.getincrementalencoder('euc_jis_2004')()
    t('setstate', lambda: (e.encode(U('304b')), e2.setstate(e.getstate()), e2.encode(U('309a'))))
    d1 = codecs.getincrementaldecoder('gb18030')()
    d1.decode(b'\x81\x30')
    d2 = codecs.getincrementaldecoder('gb18030')()
    t('decoder setstate', lambda: (d1.getstate(), d2.setstate(d1.getstate()), d2.decode(b'\x81\x30')))
    t('errors attribute', lambda: (d2.errors, setattr(d2, 'errors', 'replace'), d2.errors, d2.decode(b'\xff', True)))
    t('reset', lambda: (d1.reset(), d1.getstate()))


def streams():
    data = ('line1 ' + JA + '\nline2 ' + JA[:3] + '\n').encode('euc_jp')
    r = codecs.getreader('euc_jp')(io.BytesIO(data))
    t('read(1)', lambda: [r.read(1) for _ in range(9)])
    t('readline', lambda: r.readline())
    t('readlines', lambda: r.readlines())
    r = codecs.getreader('euc_jp')(io.BytesIO(data + b'\xa4'), 'replace')
    t('read all', lambda: r.read())
    b = io.BytesIO()
    w = codecs.getwriter('iso2022_kr')(b)
    w.write(KO)
    w.writelines(['a', KO[:1]])
    t('writer', lambda: b.getvalue())
    b = io.BytesIO()
    w = codecs.getwriter('shift_jis_2004')(b)
    t('writer pending', lambda: (w.write(U('304b')), b.getvalue(), w.reset(), b.getvalue()))


def text_io():
    b = io.BytesIO()
    w = io.TextIOWrapper(b, encoding='shift_jis', newline='')
    w.write(JA + '\n' + ZH[:0] + 'abc\n' + JA[:3] + '\n')
    w.flush()
    t('written', lambda: b.getvalue())
    r = io.TextIOWrapper(io.BytesIO(b.getvalue()), encoding='shift_jis', newline='')
    marks = []
    while True:
        marks.append(r.tell())
        if not r.readline():
            break
    t('tell', lambda: marks)
    r.seek(marks[1])
    t('seek', lambda: r.read())
    r = io.TextIOWrapper(io.BytesIO(JA.encode('iso2022_jp') * 2), encoding='iso2022_jp')
    t('iso2022 read(3)', lambda: [r.read(3) for _ in range(6)])
    r = io.TextIOWrapper(io.BytesIO(b'ok\xff'), encoding='gbk')
    t('strict', lambda: r.read())


def users():
    import email.header
    from email.mime.text import MIMEText
    h = email.header.Header(JA[:3], 'iso-2022-jp')
    t('header', lambda: h.encode())
    t('decode_header', lambda: email.header.decode_header(h.encode()))
    m = MIMEText(JA, 'plain', 'iso-2022-jp')
    t('mime', lambda: m.as_string())
    import xml.etree.ElementTree as ET
    doc = ('<?xml version="1.0" encoding="Shift_JIS"?><a>%s</a>' % JA[:2]).encode('shift_jis')
    t('xml shift_jis', lambda: ET.fromstring(doc))
    e = ET.fromstring('<a>%s</a>' % ZH)
    t('tostring gbk', lambda: ET.tostring(e, encoding='gbk'))
    t('aliases', lambda: [codecs.lookup(n).name for n in ['sjis', 'ms932', 'ujis', 'csiso2022jp', 'chinese', 'uhc', 'hkscs', 'hzgb']])
    t('iterdecode', lambda: ''.join(codecs.iterdecode([bytes([x]) for x in KO.encode('johab')], 'johab')))


def main():
    digests()
    examples()
    errors()
    incremental()
    streams()
    text_io()
    users()


main()
