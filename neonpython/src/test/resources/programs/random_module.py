# random over _random (the Mersenne Twister, CPython's numbers when seeded), _sha2 (random.seed's sha512), _operator.index.
import random, _random, _sha2, operator, _operator

def t(label, f):
    try:
        print(label, "->", f())
    except Exception as e:
        print(label, "!!", type(e).__name__, e)

def generator():
    r = _random.Random(42)
    t("random", lambda: [r.random() for _ in range(3)])
    t("bits", lambda: (r.getrandbits(0), r.getrandbits(5), r.getrandbits(40), r.getrandbits(100)))
    t("bits neg", lambda: r.getrandbits(-1))
    t("bits type", lambda: r.getrandbits(1.5))
    t("seed float", lambda: (r.seed(1.5), r.random()))
    t("seed neg", lambda: (r.seed(-5), r.random(), r.seed(5), r.random()))
    t("seed big", lambda: (r.seed(2**100 + 7), r.random()))
    t("seed tuple", lambda: (r.seed((1, 2)), r.random()))
    t("seed list", lambda: r.seed([1]))
    st = r.getstate()
    t("state", lambda: (len(st), st[-1], type(st[0]).__name__))
    t("setstate", lambda: (r.setstate(st), r.random() == (r.setstate(st) or r.random())))
    t("setstate bad", lambda: r.setstate([1]))
    t("setstate size", lambda: r.setstate((1, 2)))
    t("setstate index", lambda: r.setstate(st[:-1] + (700,)))
    t("setstate neg", lambda: r.setstate((-1,) + st[1:]))
    t("init kw", lambda: _random.Random(x=1))
    t("init many", lambda: _random.Random(1, 2))
    t("subclass", lambda: type('R', (_random.Random,), {})(3).random())
    t("sha", lambda: (_sha2.sha256(b'abc').hexdigest(), _sha2.sha512(b'a').digest()[:4], _sha2.sha224().name, _sha2.sha384().digest_size, _sha2.sha256().block_size))
    h = _sha2.sha256()
    h.update(b'a'); h2 = h.copy(); h2.update(b'b')
    t("sha update", lambda: (h.hexdigest()[:8], h2.hexdigest()[:8], _sha2.sha256(b'ab').hexdigest()[:8]))
    t("sha str", lambda: _sha2.sha256('x'))
    t("sha kw", lambda: (_sha2.sha256(data=b'a').hexdigest()[:6], _sha2.sha256(string=b'a').hexdigest()[:6], _sha2.sha256(usedforsecurity=False).name))
    t("sha types", lambda: (type(h).__name__, type(h).__module__, _sha2.SHA256Type is type(h)))

def operators():
    class I:
        def __index__(self): return 7
    class B:
        def __index__(self): return 1.5
    t("index", lambda: (operator.index(5), operator.index(True), operator.index(I()), operator.index(2**70)))
    t("float", lambda: operator.index(1.5))
    t("bad", lambda: operator.index(B()))
    t("cd", lambda: (_operator._compare_digest(b'ab', b'ab'), _operator._compare_digest('ab', 'ac'), _operator._compare_digest(b'a', b'ab')))
    t("cd mixed", lambda: _operator._compare_digest('a', b'a'))
    t("cd nonascii", lambda: _operator._compare_digest('é', 'é'))
    t("cd int", lambda: _operator._compare_digest(1, 1))

def main():
    random.seed(12345)
    t("basic", lambda: (random.random(), random.randint(1, 100), random.randrange(0, 100, 7), random.choice('abcdef'), random.uniform(1, 2)))
    t("choices", lambda: (random.choices(range(10), k=5), random.choices('abc', weights=[1, 5, 10], k=6), random.choices('abc', cum_weights=[1, 2, 3], k=4)))
    xs = list(range(10)); random.shuffle(xs)
    t("shuffle", lambda: xs)
    t("sample", lambda: (random.sample(range(100), 5), random.sample(['a', 'b', 'c'], counts=[2, 1, 3], k=4)))
    t("dists", lambda: [round(f(*a), 12) for f, a in [(random.triangular, (0, 10, 3)), (random.gauss, (0, 1)), (random.normalvariate, (5, 2)), (random.lognormvariate, (0, 1)),
        (random.expovariate, (2,)), (random.vonmisesvariate, (1, 2)), (random.gammavariate, (2, 3)), (random.gammavariate, (0.5, 1)), (random.betavariate, (2, 5)),
        (random.paretovariate, (3,)), (random.weibullvariate, (1, 2))]])
    t("binomial", lambda: [random.binomialvariate(n, p) for n, p in [(10, 0.5), (100, 0.1), (1000, 0.7), (5, 0.0), (5, 1.0)]])
    t("randbytes", lambda: random.randbytes(7))
    t("getrandbits", lambda: random.getrandbits(70))
    st = random.getstate()
    a = random.random(); random.setstate(st)
    t("state", lambda: (a == random.random(), st[0]))
    random.seed("hello"); t("seed str", lambda: random.random())
    random.seed(b"hello"); t("seed bytes", lambda: random.random())
    random.seed(bytearray(b"x")); t("seed bytearray", lambda: random.random())
    random.seed(3.25); t("seed float", lambda: random.random())
    random.seed("hello", version=1); t("seed v1", lambda: random.random())
    t("seed bad", lambda: random.seed([1]))
    r = random.Random(7)
    t("instance", lambda: (r.random(), r.randrange(10**30), type(r).__mro__))
    t("errors", lambda: random.randrange(0))
    t("errors2", lambda: random.choice([]))
    t("errors3", lambda: random.sample(range(3), 5))
    t("errors4", lambda: random.randint(1, 2.5))
    t("errors5", lambda: random.randrange(1.5))
    s = random.SystemRandom()
    t("system", lambda: (0 <= s.random() < 1, s.getrandbits(8) < 256, len(s.randbytes(3))))
    t("system state", lambda: s.getstate())

generator()
operators()
main()
