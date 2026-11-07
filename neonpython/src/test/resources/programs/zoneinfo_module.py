# zoneinfo over the bundled IANA database (read-only at /usr/share/zoneinfo, whatever the file system is): zones,
# DST gaps and folds, history, conversions, errors, the cache, pickling, from_file, TZPATH; importlib.resources
# (a package's files through the library's mount), as far as it does not show where the library is. Linux
# CPython's output (its system database is the one bundled).
import zoneinfo, pickle, copy, os
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo


def t(label, f):
    try:
        print(label, '->', f())
    except BaseException as e:
        print(label, '!!', type(e).__name__, e)


def basics():
    seoul = ZoneInfo('Asia/Seoul')
    t('key', lambda: (seoul.key, str(seoul), repr(seoul), ZoneInfo('Asia/Seoul') is seoul))
    d = datetime(2026, 11, 8, 12, 0, tzinfo=seoul)
    t('seoul', lambda: (d.isoformat(), d.utcoffset(), d.tzname(), d.dst(), d.astimezone(timezone.utc).isoformat()))
    ny = ZoneInfo('America/New_York')
    t('ny winter', lambda: datetime(2026, 1, 15, 9, tzinfo=ny).strftime('%Y-%m-%d %H:%M %Z %z'))
    t('ny summer', lambda: datetime(2026, 7, 15, 9, tzinfo=ny).strftime('%Y-%m-%d %H:%M %Z %z'))
    t('gap', lambda: datetime(2026, 3, 8, 2, 30, tzinfo=ny).utcoffset())
    t('fold', lambda: (datetime(2026, 11, 1, 1, 30, tzinfo=ny).utcoffset(), datetime(2026, 11, 1, 1, 30, fold=1, tzinfo=ny).utcoffset()))
    t('convert', lambda: datetime(2026, 6, 1, 12, tzinfo=timezone.utc).astimezone(ZoneInfo('Europe/London')).isoformat())
    t('history', lambda: (datetime(1950, 6, 1, tzinfo=ZoneInfo('Asia/Seoul')).utcoffset(), datetime(1988, 6, 1, tzinfo=ZoneInfo('Asia/Seoul')).utcoffset()))
    t('far future', lambda: datetime(2100, 7, 1, tzinfo=ny).tzname())
    t('utc', lambda: (ZoneInfo('UTC').utcoffset(None), ZoneInfo('Etc/GMT+5').utcoffset(datetime(2026, 1, 1))))
    t('lord howe', lambda: (datetime(2026, 1, 1, tzinfo=ZoneInfo('Australia/Lord_Howe')).utcoffset(), datetime(2026, 7, 1, tzinfo=ZoneInfo('Australia/Lord_Howe')).utcoffset()))


def errors():
    t('missing', lambda: ZoneInfo('Mars/Olympus'))
    t('bad key', lambda: ZoneInfo('../etc/passwd'))
    t('abs key', lambda: ZoneInfo('/usr/share/zoneinfo/UTC'))
    t('empty', lambda: ZoneInfo(''))
    t('type', lambda: ZoneInfo(5))


def module():
    t('tzpath', lambda: zoneinfo.TZPATH)
    a = zoneinfo.available_timezones()
    t('available', lambda: (len(a) > 400, 'Asia/Seoul' in a, 'America/New_York' in a, 'Etc/UTC' in a, 'posix/UTC' in a, sorted(a)[:3]))
    seoul = ZoneInfo('Asia/Seoul')
    t('pickle', lambda: pickle.loads(pickle.dumps(seoul)) is seoul)
    t('copy', lambda: (copy.copy(seoul) is seoul, copy.deepcopy(seoul) is seoul))
    t('no_cache', lambda: (ZoneInfo.no_cache('Asia/Seoul') is seoul, ZoneInfo.no_cache('Asia/Seoul').key))
    ZoneInfo.clear_cache()
    t('cleared', lambda: ZoneInfo('Asia/Seoul') is seoul)
    with open('/usr/share/zoneinfo/Asia/Tokyo', 'rb') as f:
        tok = ZoneInfo.from_file(f, key='Tokyo!')
    t('from_file', lambda: (tok.key, datetime(2026, 1, 1, tzinfo=tok).utcoffset(), repr(tok)))
    t('isfile', lambda: (os.path.isfile('/usr/share/zoneinfo/Asia/Seoul'), os.path.isdir('/usr/share/zoneinfo/Asia'), 'Seoul' in os.listdir('/usr/share/zoneinfo/Asia')))
    zoneinfo.reset_tzpath(['/nowhere'])
    t('reset', lambda: (zoneinfo.TZPATH, ZoneInfo.no_cache('Asia/Seoul')))
    zoneinfo.reset_tzpath()
    t('reset back', lambda: (zoneinfo.TZPATH[0], ZoneInfo.no_cache('Asia/Seoul').key))


basics()
errors()
module()


def resources():
    import importlib.resources as r, json
    t('files', lambda: (type(r.files('json')).__name__, r.files('json').name, r.files(json) == r.files('json'), str(r.files('json')).endswith('/json')))
    t('module', lambda: (r.files('json.decoder').name, r.files('email').joinpath('mime', '__init__.py').is_file()))
    t('iterdir', lambda: {'__init__.py', 'decoder.py', 'encoder.py', 'scanner.py'} <= {p.name for p in r.files('json').iterdir()})
    t('read', lambda: (r.read_text('json', '__init__.py')[:20], r.read_binary('json', '__init__.py')[:3], r.open_text('json', '__init__.py').readline()[:3]))
    t('multi', lambda: r.read_text('email', 'mime', '__init__.py'))
    t('multi enc', lambda: len(r.read_text('email', 'mime', '__init__.py', encoding='utf-8')) >= 0)
    t('is_resource', lambda: (r.is_resource('email', 'charset.py'), r.is_resource('email', 'mime')))
    t('contents', lambda: sorted(c for c in r.contents('email') if c != '__pycache__')[:4])

    def asf():
        with r.as_file(r.files('json') / '__init__.py') as p:
            return type(p).__name__, p.name, p.exists()
    t('as_file', asf)
    t('missing', lambda: r.files('nope_pkg'))

    def missing_res():
        try:
            r.read_text('json', 'nope.txt')
        except FileNotFoundError as e:
            return type(e).__name__, e.errno, e.filename.endswith('/json/nope.txt')
    t('missing res', missing_res)
    t('abc', lambda: (r.abc.Traversable.__name__, isinstance(r.files('json'), r.abc.Traversable)))


resources()
