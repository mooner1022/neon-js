# hashlib as CPython 3.14 has it without OpenSSL (_hashlib and _hmac blocked): md5, sha1, sha2, blake2 (all its parameters), sha3, shake; hmac; messages.
import sys
sys.modules['_hashlib'] = None
sys.modules['_hmac'] = None
import hashlib, hmac, io, _md5, _sha1, _sha2, _blake2, _sha3


def t(label, f):
    try:
        print(label, "->", ascii(f()))
    except Exception as e:
        print(label, "!!", type(e).__name__, ascii(str(e)))


MSGS = [b'', b'a', b'abc', b'x' * 63, b'x' * 64, b'x' * 65, b'y' * 127, b'y' * 128, b'y' * 129, b'z' * 135, b'z' * 136,
        b'z' * 137, b'w' * 167, b'w' * 168, b'w' * 169, bytes(range(256)) * 3]


def module():
    t("available", lambda: sorted(hashlib.algorithms_available))
    t("guaranteed", lambda: sorted(hashlib.algorithms_guaranteed))
    t("all", lambda: sorted(hashlib.__all__))
    t("no OpenSSL", lambda: (hasattr(hashlib, 'pbkdf2_hmac'), hasattr(hashlib, 'scrypt'), hashlib._hashlib))
    t("_blake2", lambda: (sorted(k for k in dir(_blake2) if not k.startswith('__')), _blake2.__doc__))
    t("_sha3", lambda: (sorted(k for k in dir(_sha3) if not k.startswith('__')), _sha3.implementation))
    t("_md5", lambda: (sorted(k for k in dir(_md5) if not k.startswith('__')), _md5.MD5Type, _sha1.SHA1Type, _sha2.SHA512Type))
    t("types", lambda: [type(getattr(hashlib, n)()) for n in sorted(hashlib.algorithms_guaranteed)])


def digests():
    for name in sorted(hashlib.algorithms_guaranteed):
        out = []
        for m in MSGS:
            h = hashlib.new(name, m)
            h2 = getattr(hashlib, name)()
            for i in range(0, len(m), 37):
                h2.update(m[i:i + 37])
            h3 = h2.copy()
            h3.update(b'more')
            if name.startswith('shake'):
                out.append(h.hexdigest(0) + h.hexdigest(20) + h.hexdigest(170) + h2.hexdigest(16) + h3.hexdigest(16))
            else:
                out.append(h.hexdigest() + h2.hexdigest() + h3.hexdigest())
        print(name, hashlib.sha256(''.join(out).encode()).hexdigest(), out[2][:40])


def attributes():
    for name in sorted(hashlib.algorithms_guaranteed):
        h = hashlib.new(name, b'abc')
        t(name, lambda: (h.name, h.digest_size, h.block_size, type(h).__name__, type(h).__module__))
    for name in ('sha3_224', 'sha3_256', 'sha3_384', 'sha3_512', 'shake_128', 'shake_256'):
        h = getattr(_sha3, name)()
        t(name + " bits", lambda: (h._capacity_bits, h._rate_bits, h._suffix))
    t("blake2b sizes", lambda: (_blake2.blake2b.SALT_SIZE, _blake2.blake2b.PERSON_SIZE, _blake2.blake2b.MAX_KEY_SIZE, _blake2.blake2b.MAX_DIGEST_SIZE))
    t("blake2s sizes", lambda: (_blake2.blake2s.SALT_SIZE, _blake2.blake2s.PERSON_SIZE, _blake2.blake2s.MAX_KEY_SIZE, _blake2.blake2s.MAX_DIGEST_SIZE))
    t("constants", lambda: (_blake2.BLAKE2B_MAX_DIGEST_SIZE, _blake2.BLAKE2S_SALT_SIZE, _blake2._GIL_MINSIZE, _sha3._GIL_MINSIZE, _md5._GIL_MINSIZE))


def blake2():
    import random
    r = random.Random(7)
    for ctor, maxd, maxs in ((hashlib.blake2b, 64, 16), (hashlib.blake2s, 32, 8)):
        out = []
        for i in range(60):
            kw = {}
            if r.random() < 0.5: kw['digest_size'] = r.randrange(1, maxd + 1)
            if r.random() < 0.5: kw['key'] = bytes(r.randrange(256) for _ in range(r.randrange(0, maxd + 1)))
            if r.random() < 0.4: kw['salt'] = bytes(r.randrange(256) for _ in range(r.randrange(0, maxs + 1)))
            if r.random() < 0.4: kw['person'] = bytes(r.randrange(256) for _ in range(r.randrange(0, maxs + 1)))
            if r.random() < 0.3: kw['fanout'] = r.randrange(0, 256)
            if r.random() < 0.3: kw['depth'] = r.randrange(1, 256)
            if r.random() < 0.3: kw['leaf_size'] = r.randrange(0, 2 ** 32)
            if r.random() < 0.3: kw['node_offset'] = r.randrange(0, 2 ** 48 if maxd == 32 else 2 ** 64)
            if r.random() < 0.3: kw['node_depth'] = r.randrange(0, 256)
            if r.random() < 0.3: kw['inner_size'] = r.randrange(0, maxd + 1)
            if r.random() < 0.3: kw['last_node'] = r.random() < 0.5
            out.append(ctor(MSGS[r.randrange(len(MSGS))], **kw).hexdigest())
        print(ctor.__name__, hashlib.sha256(''.join(out).encode()).hexdigest(), out[0])
    t("keyed", lambda: hashlib.blake2b(b'msg', key=b'key', salt=b'salt', person=b'me', digest_size=20).hexdigest())
    t("tree", lambda: hashlib.blake2s(b'leaf', fanout=2, depth=2, leaf_size=4096, node_offset=1, node_depth=0, inner_size=32, last_node=True).hexdigest())


def blake2_errors():
    for ctor in (_blake2.blake2b, _blake2.blake2s):
        n = ctor.__name__
        t(n + " digest_size 0", lambda: ctor(digest_size=0))
        t(n + " digest_size 65", lambda: ctor(digest_size=65))
        t(n + " digest_size big", lambda: ctor(digest_size=2 ** 40))
        t(n + " digest_size str", lambda: ctor(digest_size='a'))
        t(n + " key long", lambda: ctor(key=b'k' * 65))
        t(n + " key str", lambda: ctor(key='k'))
        t(n + " salt long", lambda: ctor(salt=b's' * 17))
        t(n + " person long", lambda: ctor(person=b'p' * 17))
        t(n + " fanout", lambda: ctor(fanout=256))
        t(n + " depth", lambda: ctor(depth=0))
        t(n + " leaf_size", lambda: ctor(leaf_size=2 ** 32))
        t(n + " leaf_size negative", lambda: ctor(leaf_size=-1))
        t(n + " leaf_size huge", lambda: ctor(leaf_size=2 ** 64))
        t(n + " node_offset", lambda: ctor(node_offset=2 ** 48).name)
        t(n + " node_offset huge", lambda: ctor(node_offset=2 ** 64))
        t(n + " node_depth", lambda: ctor(node_depth=256))
        t(n + " inner_size", lambda: ctor(inner_size=65))
        t(n + " two positional", lambda: ctor(b'a', b'b'))
        t(n + " unknown keyword", lambda: ctor(datax=b'x'))


def errors():
    for ctor in (_md5.md5, _sha2.sha256, _blake2.blake2b, _sha3.sha3_256, _sha3.shake_128):
        n = ctor.__name__
        t(n + " None", lambda: ctor(None))
        t(n + " string None", lambda: ctor(string=None))
        t(n + " data and string", lambda: ctor(b'a', string=b'b'))
        t(n + " string", lambda: ctor(string=b'b').hexdigest(4) if n.startswith('shake') else ctor(string=b'b').hexdigest())
        t(n + " str", lambda: ctor('abc'))
        t(n + " int", lambda: ctor(1))
        t(n + " by name and position", lambda: ctor(b'x', data=b'y'))
        t(n + " update ()", lambda: ctor().update())
        t(n + " update kw", lambda: ctor().update(data=b'a'))
        t(n + " update str", lambda: ctor().update('a'))
        t(n + " update strided", lambda: ctor().update(memoryview(b'abc')[::2]))
        t(n + " copy 1", lambda: ctor().copy(1))
        t(n + " setattr name", lambda: setattr(ctor(), 'name', 'x'))
        t(n + " setattr foo", lambda: setattr(ctor(), 'foo', 'x'))
        t(n + " subclass", lambda: type('X', (type(ctor()),), {}))
        t(n + " pickle", lambda: ctor().__reduce_ex__(2))
    h = hashlib.shake_256(b'abc')
    t("shake digest ()", lambda: h.digest())
    t("shake digest -1", lambda: h.digest(-1))
    t("shake digest 2**29", lambda: h.digest(1 << 29))
    t("shake digest 2**64", lambda: h.digest(2 ** 64))
    t("shake digest float", lambda: h.digest(1.5))
    t("shake digest length=", lambda: h.digest(length=3))
    t("shake digest 2", lambda: h.digest(3, 4))
    t("sha3 digest 1", lambda: hashlib.sha3_256().digest(1))
    t("md5 digest 1", lambda: hashlib.md5().digest(1))
    t("new nope", lambda: hashlib.new('nope'))
    t("new SHA256", lambda: hashlib.new('SHA256', b'x').hexdigest())
    t("new blake2b", lambda: hashlib.new('blake2b', b'x', digest_size=8).hexdigest())


def hmacs():
    for dm in ('md5', 'sha1', 'sha256', 'sha512', 'sha3_256', 'blake2b', 'blake2s', hashlib.sha224, _sha3.sha3_384):
        t("hmac " + str(dm)[:30], lambda: hmac.new(b'key', b'The quick brown fox jumps over the lazy dog', dm).hexdigest())
        t("hmac.digest " + str(dm)[:30], lambda: hmac.digest(b'k' * 200, b'msg', dm).hex())
    h = hmac.new(b'key', digestmod='sha256')
    h.update(b'abc')
    t("hmac object", lambda: (h.name, h.digest_size, h.block_size, h.copy().hexdigest(), h.hexdigest()))
    t("hmac no digestmod", lambda: hmac.new(b'key'))
    t("hmac str key", lambda: hmac.new('key', digestmod='sha256'))
    t("compare_digest", lambda: (hmac.compare_digest(b'abc', b'abc'), hmac.compare_digest('abc', 'abd')))


class Reader:
    def __init__(self, data):
        self.data = data
        self.pos = 0

    def readable(self):
        return True

    def readinto(self, buf):
        n = min(len(buf), len(self.data) - self.pos)
        buf[:n] = self.data[self.pos:self.pos + n]
        self.pos += n
        return n


def text_file():
    try:
        hashlib.file_digest(io.StringIO('x'), 'sha1')
    except ValueError as e:
        return str(e).split("'")[-1]


def files():
    t("file_digest BytesIO", lambda: hashlib.file_digest(io.BytesIO(b'hello' * 1000), 'sha3_256').hexdigest())
    t("file_digest constructor", lambda: hashlib.file_digest(io.BytesIO(b'hello' * 1000), hashlib.blake2s).hexdigest())
    t("file_digest reader", lambda: hashlib.file_digest(Reader(b'abc' * 100000), 'sha1').hexdigest())
    t("file_digest text", lambda: text_file())
    t("buffers", lambda: [hashlib.sha256(memoryview(b'abcdef')).hexdigest(), hashlib.sha3_224(bytearray(b'abc')).hexdigest(),
                          hashlib.blake2b(memoryview(b'abcdef')[1:4]).hexdigest()])


module()
digests()
attributes()
blake2()
blake2_errors()
errors()
hmacs()
files()
