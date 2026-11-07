# pyexpat (Expat 2.8.1 ported to Kotlin, CPython's Modules/pyexpat.c over it): the handlers and their arguments, the
# errors (messages, codes, line, column and byte index) of malformed documents, fed whole and a byte at a time,
# namespaces (every separator, with prefixes), DTDs (entities, notations, attribute lists, content models), external
# entities and parameter entities through ExternalEntityParserCreate, buffer_text, GetInputContext, the encodings
# (built in, BOMs, through a codec), interning, a handler raising, reparse deferral, the protection settings.
import pyexpat
from xml.parsers import expat


def t(label, f):
    try:
        print(label, f())
    except Exception as e:
        print(label, '!!', type(e).__name__, e)


def recorder(p, out, names):
    for n in names:
        def h(*a, n=n):
            out.append((n[:-7],) + a)
        setattr(p, n, h)


ALL = ['StartElementHandler', 'EndElementHandler', 'CharacterDataHandler', 'CommentHandler',
       'ProcessingInstructionHandler', 'StartCdataSectionHandler', 'EndCdataSectionHandler', 'XmlDeclHandler',
       'StartDoctypeDeclHandler', 'EndDoctypeDeclHandler', 'EntityDeclHandler', 'StartNamespaceDeclHandler',
       'EndNamespaceDeclHandler', 'ElementDeclHandler', 'AttlistDeclHandler', 'SkippedEntityHandler',
       'NotationDeclHandler', 'UnparsedEntityDeclHandler']


def run(doc, **kw):
    out = []
    p = pyexpat.ParserCreate(**kw)
    recorder(p, out, ALL)
    try:
        out.append(('ok', p.Parse(doc, True)))
    except pyexpat.ExpatError as e:
        out.append(('error', str(e), e.code, e.lineno, e.offset))
    for x in out:
        print(x)
    print('--')


def module():
    print(pyexpat.EXPAT_VERSION, pyexpat.version_info, pyexpat.native_encoding)
    print(pyexpat.features)
    print(pyexpat.__doc__, pyexpat.errors.__name__, pyexpat.model.__name__, pyexpat.errors.__doc__, pyexpat.model.__doc__)
    print(expat.errors is pyexpat.errors, expat.model.XML_CTYPE_SEQ, expat.ParserCreate is pyexpat.ParserCreate)
    print(pyexpat.errors.codes['mismatched tag'], pyexpat.errors.messages[7], pyexpat.errors.XML_ERROR_SYNTAX)
    print(len(pyexpat.errors.codes), len(pyexpat.errors.messages), pyexpat.XML_PARAM_ENTITY_PARSING_ALWAYS)
    print(pyexpat.ExpatError, pyexpat.error is pyexpat.ExpatError, pyexpat.ExpatError.__mro__, pyexpat.XMLParserType)
    print([pyexpat.ErrorString(i) for i in (0, 1, 7, 43, 44, 45, -1)])
    p = pyexpat.ParserCreate()
    print(type(p), type(p).__name__, type(p).__module__, type(p).__doc__)
    print(sorted(n for n in dir(p) if not n.startswith('__')))


def arguments():
    p = pyexpat.ParserCreate()
    t('new', lambda: pyexpat.XMLParserType())
    t('setattr', lambda: setattr(p, 'foo', 1))
    t('set ErrorCode', lambda: setattr(p, 'ErrorCode', 1))
    t('del handler', lambda: delattr(p, 'StartElementHandler'))
    t('set intern', lambda: setattr(p, 'intern', {}))
    t('buffer_size', lambda: setattr(p, 'buffer_size', 'x'))
    t('buffer_size 0', lambda: setattr(p, 'buffer_size', 0))
    t('ParserCreate', lambda: pyexpat.ParserCreate(1))
    t('ParserCreate ns', lambda: pyexpat.ParserCreate(namespace_separator='ab'))
    t('ParserCreate ns2', lambda: pyexpat.ParserCreate(namespace_separator='é'))
    t('ParserCreate intern', lambda: pyexpat.ParserCreate(intern=[]))
    t('ParserCreate kw', lambda: pyexpat.ParserCreate(foo=1))
    t('ParserCreate nul', lambda: pyexpat.ParserCreate('a\0b'))
    t('ErrorString', lambda: pyexpat.ErrorString('x'))
    t('Parse', lambda: p.Parse())
    t('Parse int', lambda: p.Parse(1))
    t('Parse kw', lambda: p.Parse(b'', isfinal=True))
    t('ParseFile', lambda: p.ParseFile(1))
    t('SetBase', lambda: p.SetBase(1))
    t('SetBase ok', lambda: (p.SetBase('http://x/'), p.GetBase()))
    t('ExternalEntityParserCreate', lambda: p.ExternalEntityParserCreate(1))
    t('threshold', lambda: p.SetBillionLaughsAttackProtectionActivationThreshold(-1))
    t('threshold2', lambda: p.SetBillionLaughsAttackProtectionActivationThreshold(2 ** 64))
    t('amplification', lambda: p.SetBillionLaughsAttackProtectionMaximumAmplification(0.5))
    t('amplification2', lambda: p.SetAllocTrackerMaximumAmplification('x'))
    t('subparser', lambda: p.ExternalEntityParserCreate(None).SetAllocTrackerActivationThreshold(5))
    t('reparse', lambda: (p.GetReparseDeferralEnabled(), p.SetReparseDeferralEnabled(0), p.GetReparseDeferralEnabled()))
    t('positions', lambda: (p.ErrorCode, p.ErrorLineNumber, p.ErrorColumnNumber, p.ErrorByteIndex, p.CurrentByteIndex))


def documents():
    run(b'<?xml version="1.0" encoding="utf-8"?><a x="1" y=\'2\'>hi<b/>there &amp; &#65;&#x42;<![CDATA[<raw>]]><!--c--><?pi data?></a>')
    run('<a>é中\U0001F600</a>')
    run(b'<!DOCTYPE a [<!ENTITY e "val&#33;"><!ELEMENT a (#PCDATA|b)*><!ATTLIST a x CDATA "d" y (p|q) #REQUIRED>]><a>&e;</a>')
    run(b'<a xmlns="u" xmlns:p="v"><p:b p:c="1" d="2"/></a>', namespace_separator=' ')
    run(b'<a>\r\nline\rx</a>')
    run(b'<!DOCTYPE a [<!NOTATION n SYSTEM "s"><!ENTITY u SYSTEM "f" NDATA n><!ENTITY % p "x">]><a/>')


BAD = [
    b'<a', b'<a>', b'</a>', b'<a></b>', b'<a b></a>', b'<a b=1></a>', b'<a b="1></a>', b'<a b="<"/>',
    b'<a>&</a>', b'<a>&#;</a>', b'<a>&#xZZ;</a>', b'<a>&#0;</a>', b'<a>&#x110000;</a>', b'<a>&#55296;</a>',
    b'<a>]]></a>', b'<a><![CDATA[x</a>', b'<a><!-- -- --></a>', b'<a><?xml version="1.0"?></a>',
    b' <?xml version="1.0"?><a/>', b'<?xml version="2.0" foo="1"?><a/>', b'<?xml encoding="utf-8"?><a/>',
    b'<?xml version="1.0" standalone="maybe"?><a/>', b'<?xml version="1.0" encoding="utf-16"?><a/>', b'<a/><b/>',
    b'<a/>text', b'<a>\xff</a>', b'<a>\xc3(</a>', b'<a>\xed\xa0\x80</a>', b'<a>\xef\xbf\xbe</a>', b'<a>\x01</a>',
    b'<1a/>', b'<a:b:c/>', b'<!DOCTYPE a', b'<!DOCTYPE a [<!ENTITY>]><a/>', b'<!DOCTYPE a [<!ELEMENT a (b,c|d)>]><a/>',
    b'<!DOCTYPE a [<!ENTITY e "&e;">]><a>&e;</a>', b'<!DOCTYPE a [<!ENTITY e SYSTEM "x" NDATA n>]><a>&e;</a>',
    b'<!DOCTYPE a [<!ENTITY e SYSTEM "x">]><a b="&e;"/>', b'<!DOCTYPE a [<!ENTITY e "<b>">]><a>&e;</a>',
    b'<!DOCTYPE a [<!ENTITY e "x" %p;>]><a/>', b'<!DOCTYPE a PUBLIC "\t" "x"><a/>', b'<a xmlns:p=""/>',
    b'<a xmlns:xmlns="x"/>', b'<a xmlns:xml="x"/>', b'<a xmlns:p="http://www.w3.org/XML/1998/namespace"/>', b'<p:a/>',
    b'<a xmlns:p="u" xmlns:q="u" p:x="1" q:x="2"/>', b'\xef\xbb\xbf\xef\xbb\xbf<a/>', b'<a>\r', b'<a>]]',
    b'<a/><![CDATA[x]]>', b'<!DOCTYPE a [<![INCLUDE[]]>]><a/>', b'<a>&undefined;</a>', b'<a x="1" x="2"/>', b'',
]


def probe(doc, ns=None, chunk=None):
    p = pyexpat.ParserCreate(namespace_separator=ns)
    try:
        if chunk:
            for i in range(0, len(doc), chunk):
                p.Parse(doc[i:i + chunk], False)
            p.Parse(b'', True)
        else:
            p.Parse(doc, True)
        return 'ok', p.CurrentLineNumber, p.CurrentColumnNumber, p.CurrentByteIndex
    except pyexpat.ExpatError as e:
        return str(e), e.code, p.ErrorCode, p.ErrorByteIndex, p.ErrorLineNumber, p.ErrorColumnNumber


def malformed():
    for d in BAD:
        print(d, probe(d), probe(d, ns='!'))
    for d in BAD[:30]:
        print(d, probe(d, chunk=1), probe(d, chunk=3))
    p = pyexpat.ParserCreate()
    t('error', lambda: p.Parse(b'<a>\n<b>\n</a>', True))
    t('again', lambda: p.Parse(b'', True))
    q = pyexpat.ParserCreate()
    t('done', lambda: (q.Parse('<a/>', True), q.Parse('<a/>', True)))


def buffering():
    for size in (3, 8, 16, 8192):
        p = pyexpat.ParserCreate()
        p.buffer_text = True
        p.buffer_size = size
        out = []
        p.CharacterDataHandler = lambda d: out.append((d, p.buffer_used))
        p.StartElementHandler = lambda n, a: out.append(('<' + n, p.buffer_used))
        p.Parse(b'<a>hello &amp; world<b/>abcdefghijklmnopqrstuvwxyz&#65;\n\r\nmore</a>', True)
        print(size, out, p.buffer_used, p.buffer_text)
    p = pyexpat.ParserCreate()
    out = []
    p.CharacterDataHandler = out.append
    p.buffer_text = True
    p.Parse(b'<a>abc', False)
    print(out, p.buffer_used)
    p.buffer_text = False
    p.Parse(b'def</a>', True)
    print(out, p.buffer_used)
    p = pyexpat.ParserCreate()
    p.buffer_size = 4
    print(p.buffer_text, p.buffer_size)


def positions():
    p = pyexpat.ParserCreate()
    out = []
    for n in ['StartElementHandler', 'EndElementHandler', 'CharacterDataHandler', 'CommentHandler']:
        def h(*a, n=n):
            out.append((n[:-7], a, p.CurrentLineNumber, p.CurrentColumnNumber, p.CurrentByteIndex, p.GetInputContext()))
        setattr(p, n, h)
    p.Parse(b'<a>\n  <b x="1">t\xc3\xa9xt</b>\r\n  <!-- c -->', False)
    p.Parse(b'\n</a>', True)
    for x in out:
        print(x)
    print(p.GetInputContext())


def defaults():
    doc = b'<!DOCTYPE a [<!ENTITY e "E<b/>"><!ENTITY f "F">]><a>&e;&f;&amp;&lt;x</a>'
    for which in ('DefaultHandler', 'DefaultHandlerExpand'):
        p = pyexpat.ParserCreate()
        out = []
        recorder(p, out, ['StartElementHandler', 'CharacterDataHandler', 'SkippedEntityHandler', which])
        p.Parse(doc, True)
        print(which, out)
    p = pyexpat.ParserCreate()
    out = []
    p.DefaultHandler = out.append
    p.Parse(b'<?xml version="1.0"?>\n<!DOCTYPE a [<!ATTLIST a b CDATA "c">]>\n<a x="1">t<![CDATA[c]]><!--k--></a>\n', True)
    print(out)


def entity_handlers(p, out):
    names = ['EntityDeclHandler', 'UnparsedEntityDeclHandler', 'NotationDeclHandler', 'SkippedEntityHandler',
             'NotStandaloneHandler', 'StartElementHandler', 'CharacterDataHandler', 'StartDoctypeDeclHandler',
             'EndDoctypeDeclHandler', 'XmlDeclHandler']
    for n in names:
        def h(*a, n=n):
            out.append((n[:-7],) + a)
            return 1
        setattr(p, n, h)
    data = {'ext.xml': b'<?xml encoding="utf-8"?>ext<e/>', 'pub.xml': b'pub', 'doc.dtd': b'<!ENTITY fromDTD "dtd">'}

    def ext(ctx, base, sysid, pubid):
        out.append(('ExternalEntityRef', base, sysid, pubid))
        q = p.ExternalEntityParserCreate(ctx)
        q.Parse(data.get(sysid, b''), True)
        return 1
    p.ExternalEntityRefHandler = ext


def entities():
    doc = b'''<?xml version="1.0" standalone="no"?>
<!DOCTYPE doc SYSTEM "doc.dtd" [
<!ENTITY int "internal &amp; text">
<!ENTITY ext SYSTEM "ext.xml">
<!ENTITY pub PUBLIC "-//X//Y  Z//EN" "pub.xml">
<!ENTITY unp SYSTEM "u.gif" NDATA gif>
<!NOTATION gif SYSTEM "image/gif">
<!NOTATION jpg PUBLIC "-//J//EN">
<!ENTITY % pe "<!ENTITY fromPE 'yes'>">
%pe;
]>
<doc a="&int;">&int;&ext;&pub;&fromPE;&fromDTD;</doc>'''
    for pe in (0, 1, 2):
        p = pyexpat.ParserCreate()
        p.SetBase('http://base/')
        out = []
        entity_handlers(p, out)
        print('pe', pe, p.SetParamEntityParsing(pe))
        try:
            p.Parse(doc, True)
        except pyexpat.ExpatError as e:
            out.append(('error', str(e)))
        for x in out:
            print(x)


def foreign():
    for use in (True, False):
        p = pyexpat.ParserCreate()
        p.SetParamEntityParsing(pyexpat.XML_PARAM_ENTITY_PARSING_ALWAYS)
        p.UseForeignDTD(use)
        out = []

        def ext(ctx, base, sysid, pubid):
            out.append(('ext', base, sysid, pubid))
            q = p.ExternalEntityParserCreate(ctx)
            q.Parse(b'<!ENTITY e "foreign">', True)
            return 1
        p.ExternalEntityRefHandler = ext
        p.CharacterDataHandler = lambda d: out.append(('data', d))
        p.SkippedEntityHandler = lambda *a: out.append(('skip',) + a)
        try:
            p.Parse(b'<a>&e;</a>', True)
        except pyexpat.ExpatError as e:
            out.append(str(e))
        print(use, out)
    p = pyexpat.ParserCreate()
    p.ExternalEntityRefHandler = lambda *a: 0
    t('ext 0', lambda: p.Parse(b'<!DOCTYPE a [<!ENTITY e SYSTEM "x">]><a>&e;</a>', True))
    p = pyexpat.ParserCreate()
    p.Parse(b'<a>', False)
    t('late', lambda: p.UseForeignDTD())


def attributes():
    doc = (b'<!DOCTYPE a [<!ATTLIST a d CDATA "dv" e CDATA #IMPLIED i ID #IMPLIED t NMTOKENS " x  y ">]>'
           b'<a x="1" t="  p   q  " i="  z " w="a&#32;b&#10;c&#9;d\nend"/>')
    for ordered in (0, 1):
        for spec in (0, 1):
            p = pyexpat.ParserCreate()
            p.ordered_attributes = ordered
            p.specified_attributes = spec
            p.StartElementHandler = lambda n, a: print(ordered, spec, n, a)
            p.Parse(doc, True)


def namespaces():
    doc = b'<r xmlns="http://d/" xmlns:a="http://a/"><a:x a:y="1" z="2"><y xmlns=""><a:q/></y></a:x></r>'
    for sep in ('!', '', ':', ' '):
        for prefixes in (False, True):
            p = pyexpat.ParserCreate(namespace_separator=sep)
            p.namespace_prefixes = prefixes
            out = []
            recorder(p, out, ['StartElementHandler', 'EndElementHandler', 'StartNamespaceDeclHandler', 'EndNamespaceDeclHandler'])
            p.Parse(doc, True)
            print(repr(sep), prefixes, out)


def interning():
    d = {}
    p = pyexpat.ParserCreate(intern=d)
    names = []
    p.StartElementHandler = lambda n, a: names.append(n)
    p.Parse(b'<a><a/><b/></a>', True)
    print(sorted(d), names[0] is names[1], p.intern is d, pyexpat.ParserCreate(intern=None).intern)


def raising():
    p = pyexpat.ParserCreate()
    out = []

    def bad(n, a):
        out.append(n)
        if n == 'b':
            raise ValueError('boom ' + n)
    p.StartElementHandler = bad
    p.EndElementHandler = lambda n: out.append('/' + n)
    t('raise', lambda: p.Parse(b'<a><b><c/></b></a>', True))
    print(out, p.StartElementHandler, p.EndElementHandler, p.ErrorCode)
    t('after', lambda: p.Parse(b'<a/>', True))
    p = pyexpat.ParserCreate()
    p.buffer_text = True
    p.CharacterDataHandler = lambda d: 1 / 0
    t('chardata', lambda: p.Parse(b'<a>x<b/>y</a>', True))
    p = pyexpat.ParserCreate()
    out = []

    def cd(d):
        out.append(d)
        p.CharacterDataHandler = None
    p.CharacterDataHandler = cd
    p.Parse(b'<a>x&amp;y</a>', True)
    print(out, p.CharacterDataHandler)


def encodings():
    for enc, data in [('utf-16', '<?xml version="1.0" encoding="utf-16"?><a b="é">中</a>'.encode('utf-16')),
                      ('utf-16-le', '<a>x\U0001F600</a>'.encode('utf-16-le')),
                      ('utf-16-be', '<a>é</a>'.encode('utf-16-be')),
                      ('latin', '<?xml version="1.0" encoding="ISO-8859-1"?><a \xe9="\xff">\xe9</a>'.encode('latin-1')),
                      ('ascii', b'<?xml version="1.0" encoding="us-ascii"?><a>\xe9</a>'),
                      ('cp1252', '<?xml version="1.0" encoding="cp1252"?><a>€</a>'.encode('cp1252')),
                      ('koi8', '<?xml version="1.0" encoding="koi8-r"?><а>ж</а>'.encode('koi8-r')),
                      ('multibyte', b'<?xml version="1.0" encoding="utf_16_le"?><a>x</a>'),
                      ('unknown', b'<?xml version="1.0" encoding="no-such"?><a>x</a>'),
                      ('bom', b'\xef\xbb\xbf<a>bom</a>')]:
        p = pyexpat.ParserCreate()
        out = []
        p.StartElementHandler = lambda n, a: out.append((n, a))
        p.CharacterDataHandler = out.append
        try:
            p.Parse(data, True)
            print(enc, out)
        except Exception as e:
            print(enc, out, type(e).__name__, e)
    for name in ('utf-8', 'UTF-16LE', 'latin-1', 'cp437'):
        p = pyexpat.ParserCreate(name)
        out = []
        p.CharacterDataHandler = out.append
        try:
            p.Parse('<a>é</a>'.encode('latin-1'), True)
            print(name, out)
        except Exception as e:
            print(name, out, type(e).__name__, e)


def models():
    doc = b'''<!DOCTYPE a [
<!ELEMENT a (b, (c | d)*, e?, f+)>
<!ELEMENT b EMPTY>
<!ELEMENT c ANY>
<!ELEMENT d (#PCDATA)>
<!ELEMENT e (#PCDATA | b | c)*>
<!ELEMENT f ((b, c) | (d, e))+>
]><a/>'''
    p = pyexpat.ParserCreate()
    p.ElementDeclHandler = lambda n, m: print(n, m)
    p.Parse(doc, True)


def protection():
    ents = ''.join('<!ENTITY a%d "%s">' % (i, ('&a%d;' % (i - 1)) * 10 if i else 'lol') for i in range(6))
    doc = ('<!DOCTYPE r [%s]><r>&a5;</r>' % ents).encode()
    p = pyexpat.ParserCreate()
    n = []
    p.CharacterDataHandler = lambda d: n.append(len(d))
    t('laughs', lambda: p.Parse(doc, True))
    print(sum(n))
    p = pyexpat.ParserCreate()
    p.SetBillionLaughsAttackProtectionActivationThreshold(10)
    p.SetBillionLaughsAttackProtectionMaximumAmplification(2.0)
    t('laughs2', lambda: p.Parse(doc, True))


def chunks():
    for enabled in (True, False):
        p = pyexpat.ParserCreate()
        p.SetReparseDeferralEnabled(enabled)
        out = []
        p.StartElementHandler = lambda n, a: out.append(n)
        for c in [b'<roo', b't><ch', b'ild/', b'><x', b'/></root>']:
            p.Parse(c, False)
            out.append('|')
        p.Parse(b'', True)
        print(enabled, out)
    p = pyexpat.ParserCreate()
    n = [0, 0]

    def cd(d):
        n[0] += 1
        n[1] += len(d)
    p.CharacterDataHandler = cd
    p.Parse(b'<a>' + b'x' * 3000000 + b'</a>', True)
    print(n)


def many():
    doc = ('<root>' + ''.join('<item id="%d" name="n%d"><v>text %d &amp; more</v></item>' % (i, i, i) for i in range(30000)) + '</root>').encode()
    p = pyexpat.ParserCreate()
    n = [0, 0, 0]

    def se(name, attrs):
        n[0] += 1
        n[1] += len(attrs)

    def cd(d):
        n[2] += len(d)
    p.StartElementHandler = se
    p.CharacterDataHandler = cd
    p.buffer_text = True
    print(len(doc), p.Parse(doc, True), n, p.CurrentByteIndex, p.CurrentColumnNumber)
    p = pyexpat.ParserCreate(namespace_separator='}')
    p.StartElementHandler = se
    doc = b'<r xmlns="u" xmlns:p="v">' + b'<p:x p:a="1" b="2"/>' * 20000 + b'</r>'
    print(p.Parse(doc, True), n)


def main():
    for f in [module, arguments, documents, malformed, buffering, positions, defaults, entities, foreign, attributes,
              namespaces, interning, raising, encodings, models, protection, chunks, many]:
        print('==', f.__name__)
        f()


main()
