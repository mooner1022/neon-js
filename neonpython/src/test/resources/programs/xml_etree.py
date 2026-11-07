# CPython's xml package and plistlib, unchanged, over pyexpat (Expat in Kotlin): ElementTree (parse, fromstring,
# tostring and its options, write, indent, the XPath subset, namespaces, iterparse, XMLPullParser and flush, parser
# targets, ParseError's code and position, canonicalize, ElementInclude), minidom (parseString, toxml, toprettyxml,
# building a document), pulldom, sax (a ContentHandler, namespaces, errors, saxutils, XMLGenerator, incremental feeding),
# plistlib (XML and binary round trips, its errors).
import io
import xml.etree.ElementTree as ET
from xml.dom import minidom
import xml.sax
import xml.sax.handler
import xml.sax.saxutils
import plistlib
import datetime
from xml.dom import pulldom
from xml.etree import ElementInclude


def t(label, f):
    try:
        print(label, f())
    except Exception as e:
        print(label, '!!', type(e).__name__, e)


DOC = '''<?xml version="1.0" encoding="UTF-8"?>
<catalog xmlns="http://example.com/c" xmlns:x="http://example.com/x">
  <!-- a comment -->
  <book id="b1" x:lang="en">
    <title>Python &amp; XML</title>
    <price currency="USD">29.99</price>
    <x:tag>one</x:tag>
  </book>
  <book id="b2">
    <title>Kotlin</title>
    <price currency="EUR">35.00</price>
    <?pi target?>
  </book>
  <empty/>
</catalog>'''


def etree_basics():
    root = ET.fromstring(DOC)
    print(root.tag, root.attrib, len(root), [c.tag for c in root])
    ns = {'c': 'http://example.com/c', 'x': 'http://example.com/x'}
    for b in root.findall('c:book', ns):
        print(b.get('id'), b.find('c:title', ns).text, b.find('c:price', ns).attrib, b.findtext('x:tag', default='-', namespaces=ns))
    print([e.text for e in root.iter('{http://example.com/c}title')])
    print(root.find('.//c:price[@currency="EUR"]', ns).text)
    print([b.get('id') for b in root.findall("c:book[c:title='Kotlin']", ns)])
    print([e.tag for e in root.findall('./c:book/*', ns)])
    print(root.findall('c:book[2]', ns)[0].get('id'), root.findall('c:book[last()]', ns)[0].get('id'))
    print([e.tag for e in root.iterfind('.//{*}tag')])
    print(ET.tostring(root.find('c:book', ns), encoding='unicode'))
    print(ET.tostring(root, encoding='utf-8')[:80])
    print(ET.tostring(root, method='text', encoding='unicode').split())
    e = ET.Element('root', {'a': '1'})
    s = ET.SubElement(e, 'child', b='2')
    s.text = 'text <&>'
    s.tail = 'tail'
    ET.SubElement(e, 'empty')
    print(ET.tostring(e))
    print(ET.tostring(e, short_empty_elements=False))
    print(ET.tostring(e, xml_declaration=True, encoding='us-ascii'))
    ET.indent(e)
    print(ET.tostring(e, encoding='unicode'))
    tree = ET.ElementTree(e)
    buf = io.BytesIO()
    tree.write(buf, encoding='utf-8', xml_declaration=True)
    print(buf.getvalue())
    print(ET.dump(ET.fromstring('<a><b/></a>')))
    print(ET.XML('<a>x</a>').text, ET.fromstringlist(['<a>', 'y', '</a>']).text)
    print(ET.iselement(e), repr(ET.Comment('c'))[:20], ET.tostring(ET.ProcessingInstruction('pi', 'v')))
    root2, ids = ET.XMLID('<a><b id="1"/><c id="2">x</c></a>')
    print(sorted(ids), ids['2'].text)


def etree_ns():
    ET.register_namespace('ex', 'http://example.com/x')
    e = ET.Element('{http://example.com/c}root')
    ET.SubElement(e, '{http://example.com/x}item', {'{http://example.com/x}attr': 'v'})
    ET.SubElement(e, 'plain')
    print(ET.tostring(e, encoding='unicode'))
    t('defns', lambda: ET.tostring(e, encoding='unicode', default_namespace='http://example.com/c'))
    e.remove(e[1])
    t('defns2', lambda: ET.tostring(e, encoding='unicode', default_namespace='http://example.com/c'))


def etree_parse():
    tree = ET.parse(io.StringIO(DOC))
    print(tree.getroot().tag, tree.find('{http://example.com/c}empty').tag)
    for ev, el in ET.iterparse(io.BytesIO(DOC.encode()), events=('start', 'end', 'start-ns', 'end-ns', 'comment', 'pi')):
        tag = getattr(el, 'tag', el)
        print(ev, tag.__name__ if callable(tag) else tag, getattr(el, 'text', None))
    it = ET.iterparse(io.BytesIO(b'<r>' + b'<i>x</i>' * 3000 + b'</r>'))
    n = 0
    for ev, el in it:
        n += 1
    print(n, it.root.tag, len(it.root))


def pull():
    p = ET.XMLPullParser(events=('start', 'end'))
    for chunk in ['<root><a x="1">te', 'xt</a><b/', '></root>']:
        p.feed(chunk)
        print([(ev, el.tag) for ev, el in p.read_events()])
    p.close()
    p = ET.XMLPullParser(events=('start',))
    p.feed('<root><child')
    print([el.tag for ev, el in p.read_events()])
    p.feed('/>')
    print([el.tag for ev, el in p.read_events()])
    p.flush()
    print([el.tag for ev, el in p.read_events()])
    p = ET.XMLPullParser(events=('start-ns', 'end-ns'))
    p.feed('<a xmlns="u"><b xmlns:p="v"/></a>')
    print(list(p.read_events()))


def targets():
    p = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True, insert_pis=True))
    p.feed('<!DOCTYPE r [<!ENTITY e "ent">]><r>&e;<!--c--><?p d?></r>')
    r = p.close()
    print([x.tag if isinstance(x.tag, str) else x.tag.__name__ for x in r], r.text, p.version)

    class Target:
        def start(self, tag, attrib):
            print('start', tag, attrib)

        def end(self, tag):
            print('end', tag)

        def data(self, d):
            print('data', repr(d))

        def close(self):
            return 'closed'
    p = ET.XMLParser(target=Target())
    p.feed('<a b="c">x<d/>y</a>')
    print(p.close())
    for bad in ['<a>', '<a></b>', '<a>&x;</a>', '', '<a b="1" b="2"/>', 'x<a/>', '<a>\n<b>\n</c></a>']:
        try:
            ET.fromstring(bad)
        except ET.ParseError as e:
            print(repr(bad), e, e.code, e.position)


def include():
    docs = {'inc.xml': '<inc>included</inc>', 'text.txt': 'plain text'}

    def loader(href, parse, encoding=None):
        return ET.fromstring(docs[href]) if parse == 'xml' else docs[href]
    root = ET.fromstring('<r xmlns:xi="http://www.w3.org/2001/XInclude"><xi:include href="inc.xml"/>'
                         '<p><xi:include href="text.txt" parse="text"/></p></r>')
    ElementInclude.include(root, loader=loader)
    print(ET.tostring(root, encoding='unicode'))


def canon():
    xml = '<root xmlns:b="u" a2="2" a1="1"><!-- c --><b:x/>  <y>t</y></root>'
    print(ET.canonicalize(xml))
    print(ET.canonicalize(xml, with_comments=True, strip_text=True))
    print(ET.canonicalize(xml, exclude_attrs=['a1'], rewrite_prefixes=True))


def dom():
    d = minidom.parseString(DOC)
    print(d.documentElement.tagName, d.documentElement.namespaceURI)
    for b in d.getElementsByTagName('book'):
        print(b.getAttribute('id'), b.getElementsByTagName('title')[0].firstChild.data, b.getAttributeNS('http://example.com/x', 'lang'))
    print(d.toprettyxml(indent='  ')[:400])
    d2 = minidom.parseString('<a><b c="1">t</b></a>')
    print(d2.toxml(), d2.documentElement.toprettyxml(indent='\t', newl='\n'))
    print(d2.toxml(encoding='utf-8'))
    impl = minidom.getDOMImplementation()
    doc = impl.createDocument(None, 'root', None)
    el = doc.createElement('child')
    el.setAttribute('k', 'v')
    el.appendChild(doc.createTextNode('text & more'))
    doc.documentElement.appendChild(el)
    print(doc.toxml())
    try:
        minidom.parseString('<a><b></a>')
    except Exception as e:
        print(type(e).__name__, e)


def sax():
    class H(xml.sax.handler.ContentHandler):
        def __init__(self):
            super().__init__()
            self.events = []

        def startDocument(self):
            self.events.append('startDocument')

        def endDocument(self):
            self.events.append('endDocument')

        def startElement(self, name, attrs):
            self.events.append(('start', name, dict(attrs), attrs.getNames()))

        def endElement(self, name):
            self.events.append(('end', name))

        def characters(self, content):
            self.events.append(('chars', content))

        def processingInstruction(self, target, data):
            self.events.append(('pi', target, data))

    h = H()
    xml.sax.parseString(b'<a x="1"><b>hi</b><?p d?>there</a>', h)
    for e in h.events:
        print(e)

    class NS(xml.sax.handler.ContentHandler):
        def startElementNS(self, name, qname, attrs):
            print('startNS', name, qname, dict(attrs.items()))

        def endElementNS(self, name, qname):
            print('endNS', name, qname)

        def startPrefixMapping(self, prefix, uri):
            print('prefix', prefix, uri)
    p = xml.sax.make_parser()
    p.setFeature(xml.sax.handler.feature_namespaces, True)
    p.setContentHandler(NS())
    p.parse(io.BytesIO(b'<r xmlns="d" xmlns:p="q"><p:x p:y="1"/></r>'))
    try:
        xml.sax.parseString(b'<a><b></a>', xml.sax.handler.ContentHandler())
    except xml.sax.SAXParseException as e:
        print('sax error', e, e.getLineNumber(), e.getColumnNumber(), e.getMessage())
    print(xml.sax.saxutils.escape('<a & b>'), xml.sax.saxutils.quoteattr('x "y" \'z\''), xml.sax.saxutils.unescape('&lt;&amp;&gt;'))
    out = io.StringIO()
    g = xml.sax.saxutils.XMLGenerator(out)
    xml.sax.parseString(b'<a b="1">t&amp;<c/></a>', g)
    print(out.getvalue())


def plist():
    data = {'name': 'NeonPy', 'version': 3.14, 'count': 42, 'flags': [True, False], 'nested': {'a': [1, 2, {'b': 'c'}]},
            'bytes': b'\x00\x01binary', 'date': datetime.datetime(2026, 11, 7, 12, 30, 45), 'empty': {}, 'unicode': 'é中'}
    x = plistlib.dumps(data)
    print(x.decode())
    print(plistlib.loads(x) == data, plistlib.loads(x))
    b = plistlib.dumps(data, fmt=plistlib.FMT_BINARY)
    print(len(b), b[:8], plistlib.loads(b) == data)
    print(plistlib.dumps([1, 'two'], sort_keys=False, skipkeys=True))
    try:
        plistlib.loads(b'<plist><dict><key>a</key></plist>')
    except Exception as e:
        print(type(e).__name__, e)
    print(plistlib.loads(b'<?xml version="1.0"?><plist version="1.0"><array><integer>0x10</integer><real>1e3</real><string>s</string><data>AAE=</data></array></plist>'))


def pulldom_events():
    for ev, node in pulldom.parseString('<a><b x="1">t</b><c/></a>'):
        print(ev, node.nodeName if hasattr(node, 'nodeName') else node)


class Reader:
    """A file whose read() gives bytes of another kind, or more than asked."""
    def __init__(self, data, kind=bytes, extra=0):
        self.data = data
        self.kind = kind
        self.extra = extra
        self.calls = []

    def read(self, n):
        self.calls.append(n)
        chunk, self.data = self.data[:n], self.data[n:]
        return self.kind(chunk + b'x' * self.extra)


def parse_file():
    # (pyexpat's ParseFile, what plistlib.load reads with)
    from xml.parsers.expat import ParserCreate
    doc = b'<root>' + b'<a>text</a>' * 500 + b'</root>'
    for src in [io.BytesIO(doc), Reader(doc), Reader(doc, bytearray), Reader(doc, extra=3000), Reader(doc, str),
                io.BytesIO(b'<a><b></a>'), io.BytesIO(b'')]:
        p = ParserCreate()
        out = []
        p.StartElementHandler = lambda n, a: out.append(n)
        p.CharacterDataHandler = lambda d: out.append(len(d))
        try:
            print(p.ParseFile(src), out[:6], len(out), getattr(src, 'calls', None))
        except Exception as e:
            print(type(e).__name__, e, out[:6])
    print(plistlib.load(io.BytesIO(plistlib.dumps({'k': list(range(300))})))['k'][-3:])


def main():
    for f in [etree_basics, etree_ns, etree_parse, pull, targets, include, canon, dom, pulldom_events, sax, plist,
              parse_file]:
        print('==', f.__name__)
        f()


main()
