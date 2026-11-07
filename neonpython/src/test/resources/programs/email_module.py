# email (CPython's): EmailMessage built and parsed with the default policy, folded and encoded headers, MIME
# parts and attachments, the compat32 parser, email.utils, header and charset; and a module's docstring as its
# __doc__ (_extend_docstrings reads them).
import email, email.policy, email.utils, email.header, email.charset
from email.message import EmailMessage
from email.mime.text import MIMEText
from email.mime.multipart import MIMEMultipart
from email.mime.application import MIMEApplication
from email.parser import BytesParser, Parser
from email.generator import BytesGenerator
import io


def t(label, f):
    try:
        print(label, "->", f())
    except BaseException as e:
        print(label, "!!", type(e).__name__, e)


def build():
    m = EmailMessage()
    m['From'] = 'Alice <alice@example.com>'
    m['To'] = 'Bob <bob@example.com>, carol@example.com'
    m['Subject'] = 'Héllo wörld, a subject long enough to be folded when it is written out by the generator'
    m['Date'] = email.utils.format_datetime(email.utils.parsedate_to_datetime('Sat, 07 Nov 2026 12:00:00 +0900'))
    m.set_content('Plain body\nwith ünïcode\n')
    m.add_alternative('<p>HTML</p>', subtype='html')
    m.add_attachment(b'\x00\x01binary', maintype='application', subtype='octet-stream', filename='a.bin')
    s = m.as_string()
    t("structure", lambda: [p.get_content_type() for p in m.walk()])
    t("headers", lambda: (m['subject'], m['to'].addresses[1].username, m['from'].addresses[0].display_name))
    lines = s.splitlines()
    i = next(i for i, l in enumerate(lines) if l.startswith('Subject'))
    t("folded", lambda: lines[i:i + 2])
    return m


def parse(m):
    raw = m.as_bytes()
    p = BytesParser(policy=email.policy.default).parsebytes(raw)
    t("parsed", lambda: (p['subject'], p.get_body(('plain',)).get_content(), p.is_multipart()))
    t("attachments", lambda: [(a.get_filename(), a.get_content()) for a in p.iter_attachments()])
    old = Parser().parsestr('From: x@y\nSubject: =?utf-8?b?w6k=?=\nContent-Type: text/plain; charset="utf-8"\n\nbody\n')
    t("compat", lambda: (old['Subject'], email.header.decode_header(old['Subject']), old.get_payload(), old.get_content_charset()))
    t("make_header", lambda: str(email.header.make_header(email.header.decode_header('=?iso-8859-1?q?p=F6stal?= x'))))


def mime():
    mm = MIMEMultipart()
    mm['Subject'] = 'mime'
    mm.attach(MIMEText('text part', 'plain', 'utf-8'))
    mm.attach(MIMEApplication(b'data', Name='d.bin'))
    out = io.BytesIO()
    BytesGenerator(out, mangle_from_=False).flatten(mm)
    text = out.getvalue().decode()
    import re
    text = re.sub(r'=+\d+==', 'BOUNDARY', text)
    t("mime", lambda: text.splitlines())


def utils():
    t("parseaddr", lambda: (email.utils.parseaddr('Name <a@b.c>'), email.utils.formataddr(('Nämé', 'a@b.c')), email.utils.getaddresses(['a@b, C <c@d>'])))
    t("dates", lambda: (email.utils.parsedate_tz('Mon, 20 Nov 1995 19:12:08 -0500'), email.utils.formatdate(0, usegmt=True), email.utils.mktime_tz((1995, 11, 20, 19, 12, 8, 0, 1, -1, -18000))))
    t("quote", lambda: (email.utils.quote('a"b'), email.utils.unquote('"x"'), email.utils.encode_rfc2231('é', 'utf-8')))
    t("msgid", lambda: email.utils.make_msgid(domain='example.com').endswith('@example.com>'))
    t("charset", lambda: (email.charset.Charset('utf-8').header_encoding, str(email.charset.Charset('iso-8859-1'))))


def main():
    m = build()
    parse(m)
    mime()
    utils()


main()


def docs():
    import email.policy, email.headerregistry
    t("docs", lambda: (email.__doc__.splitlines()[0], email.policy.EmailPolicy.__doc__.split(chr(10))[1].strip()[:30], 'x'.__doc__ is not None or True))


    class A:
        "A's doc"

    class B(A):
        pass

    import enum

    class E(enum.Enum):
        X = 1
    t("class docs", lambda: (A.__doc__, B.__doc__, B().__doc__, B.__dict__['__doc__'], E.__doc__, E.X.__doc__))


docs()
