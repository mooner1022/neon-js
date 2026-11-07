# unittest (CPython's): TestCase's assertions and their messages, skips, expected failures, subtests, fixtures,
# cleanups, IsolatedAsyncioTestCase, TextTestRunner's output, unittest.main; unittest.mock. Tracebacks' source
# lines are left out (CPython reads the program's file, which NeonPy's tests give as a string).
import unittest, io, re, sys, asyncio
from unittest import mock


class Arith(unittest.TestCase):
    def setUp(self):
        self.x = 2

    def test_add(self):
        self.assertEqual(self.x + 2, 4)

    def test_fail(self):
        self.assertEqual([1, 2, 3], [1, 2, 4])

    def test_dict(self):
        self.assertDictEqual({'a': 1, 'b': 2}, {'a': 1, 'b': 3})

    def test_error(self):
        raise KeyError('k')

    @unittest.skip('not now')
    def test_skip(self):
        pass

    @unittest.expectedFailure
    def test_xfail(self):
        self.assertTrue(False)

    def test_raises(self):
        with self.assertRaises(ZeroDivisionError) as cm:
            1 / 0
        self.assertIn('division', str(cm.exception))
        with self.assertRaisesRegex(ValueError, 'bad'):
            raise ValueError('bad value')

    def test_subtests(self):
        for i in range(3):
            with self.subTest(i=i):
                self.assertNotEqual(i, 1)

    def test_almost(self):
        self.assertAlmostEqual(0.1 + 0.2, 0.3)
        self.assertCountEqual([1, 2, 2], [2, 1, 2])
        self.assertIsInstance(1, int)
        self.assertRegex('hello', 'h.l')
        with self.assertWarns(DeprecationWarning):
            import warnings
            warnings.warn('old', DeprecationWarning)
        with self.assertLogs('x', level='INFO') as lc:
            import logging
            logging.getLogger('x').info('logged')
        self.assertEqual(lc.output, ['INFO:x:logged'])

    def test_multiline(self):
        self.assertMultiLineEqual('a\nb\nc\n', 'a\nB\nc\n')


class Fixtures(unittest.TestCase):
    log = []

    @classmethod
    def setUpClass(cls):
        cls.log.append('setUpClass')

    @classmethod
    def tearDownClass(cls):
        cls.log.append('tearDownClass')

    def tearDown(self):
        self.log.append('tearDown')

    def test_one(self):
        self.addCleanup(self.log.append, 'cleanup')
        self.log.append('one')


class Async(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.v = await asyncio.sleep(0, 5)

    async def test_await(self):
        self.assertEqual(self.v, 5)
        await asyncio.sleep(0.001)


def run(*classes, verbosity=2):
    stream = io.StringIO()
    loader = unittest.TestLoader()
    suite = unittest.TestSuite(loader.loadTestsFromTestCase(c) for c in classes)
    result = unittest.TextTestRunner(stream=stream, verbosity=verbosity).run(suite)
    text = re.sub(r'Ran (\d+) tests? in [0-9.]+s', r'Ran \1 tests in Xs', stream.getvalue())
    text = re.sub(r'File "[^"]*", line \d+', 'File "F", line N', text)
    # (a traceback's source lines and carets: the program's file is read by CPython only)
    kept = []
    for line in text.split('\n'):
        if re.fullmatch(r'\s*[~^]+\s*', line):
            continue
        if kept and kept[-1].startswith('  File "') and line.startswith('    '):
            continue
        kept.append(line)
    print('\n'.join(kept))
    print('result:', result.testsRun, len(result.failures), len(result.errors), len(result.skipped),
          len(result.expectedFailures), result.wasSuccessful())


def mocks():
    m = mock.Mock(return_value=3)
    print('mock', m(1, k=2), m.call_args, m.call_count, repr(m.attr.sub()).split(' id=')[0], m.attr.sub.called)
    m.assert_called_with(1, k=2)
    try:
        m.assert_called_once_with(2)
    except AssertionError as e:
        print('assert', str(e).splitlines()[0])
    mm = mock.MagicMock()
    mm.__len__.return_value = 7
    print('magic', len(mm), repr(mm[1]).split(' id=')[0], list(iter(mm)), mm.__enter__ is not None)
    patches()


def patches():
    with mock.patch('os.getcwd', return_value='/patched') as p:
        import os
        print('patched', os.getcwd(), p.called)
    print('unpatched', os.getcwd() != '/patched')
    with mock.patch.object(Fixtures, 'log', ['x']):
        print('patch.object', Fixtures.log)
    sm = mock.create_autospec(lambda a, b: None)
    sm(1, 2)
    try:
        sm(1)
    except TypeError as e:
        print('autospec', e)
    am = mock.AsyncMock(return_value=9)
    print('asyncmock', asyncio.run(am()), am.await_count)
    s = mock.sentinel.thing
    print('sentinel', s, s is mock.sentinel.thing, mock.ANY == 3, mock.call(1).args)


def main():
    run(Arith)
    run(Fixtures)
    print('fixtures', Fixtures.log)
    run(Async, verbosity=1)
    mocks()
    prog = unittest.main(module=None, argv=['x', 'Fixtures.test_one'], exit=False, testRunner=unittest.TextTestRunner(stream=io.StringIO()))
    print('main', prog.result.testsRun, prog.result.wasSuccessful())


main()
