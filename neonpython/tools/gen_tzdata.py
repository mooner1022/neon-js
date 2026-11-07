"""Generates neonpython's tzdata.zip: the IANA time zone database zoneinfo reads, mounted read-only at
/usr/share/zoneinfo (PyTzdata, PyLibraryFs), from a system's compiled database.

Usage, from the repository root, on Linux (WSL), where /usr/share/zoneinfo is the database the Linux oracle uses:
    python3 neonpython/tools/gen_tzdata.py [/usr/share/zoneinfo]

What it keeps: every TZif file (the zones zoneinfo opens), and every symbolic link to one as an alias (the ALIASES
entry: "link target" lines, the link served as its target's bytes). It leaves out posix/ and right/ (zoneinfo's
available_timezones skips them too) and the files that are not TZif (tzdata.zi, the .tab tables). The zip's entries
are sorted and dated 1980-01-01, so the same database gives the same file. VERSION holds the database's version
(tzdata.zi's first line).
"""
import os
import sys
import zipfile

ROOT = sys.argv[1] if len(sys.argv) > 1 else '/usr/share/zoneinfo'
OUT = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                   'src/main/resources/dev/mooner/neonpython/tzdata.zip')
SKIP = {'posix', 'right'}


def is_tzif(path):
    with open(path, 'rb') as f:
        return f.read(4) == b'TZif'


def main():
    files = {}
    aliases = {}
    for dirpath, dirnames, filenames in os.walk(ROOT):
        if dirpath == ROOT:
            dirnames[:] = [d for d in dirnames if d not in SKIP]
        dirnames.sort()
        for name in sorted(filenames):
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, ROOT).replace(os.sep, '/')
            if not os.path.isfile(path) or not is_tzif(path):
                continue
            if os.path.islink(path):
                target = os.path.relpath(os.path.realpath(path), os.path.realpath(ROOT)).replace(os.sep, '/')
                aliases[rel] = target
            else:
                files[rel] = open(path, 'rb').read()
    # (a link to a link, or to a file left out: served as the bytes of what it ends at, as a file of its own)
    for rel, target in list(aliases.items()):
        if target not in files:
            files[rel] = open(os.path.join(ROOT, rel), 'rb').read()
            del aliases[rel]
    version = 'unknown'
    zi = os.path.join(ROOT, 'tzdata.zi')
    if os.path.exists(zi):
        version = open(zi, encoding='ascii').readline().strip().removeprefix('# version ').strip()

    def entry(z, name, data):
        info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        z.writestr(info, data, compresslevel=9)

    with zipfile.ZipFile(OUT, 'w') as z:
        entry(z, 'VERSION', version + '\n')
        entry(z, 'ALIASES', ''.join(f'{k} {v}\n' for k, v in sorted(aliases.items())))
        for rel in sorted(files):
            entry(z, rel, files[rel])
    print(f'wrote {OUT}: {len(files)} zones, {len(aliases)} aliases, version {version}, {os.path.getsize(OUT)} bytes')


if __name__ == '__main__':
    main()
