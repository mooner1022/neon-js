#!/usr/bin/env python3
"""Dexes JVM jars into one jar that app_process can run on an Android device or emulator.

The result holds classes*.dex (D8 from the SDK's newest build-tools, against its newest android.jar) plus the jars'
resources (ICU data, META-INF/services, which D8 drops; service files of the same name are concatenated).

usage: bundle.py --out OUT.jar [--min-api 26] [--sdk DIR] JAR_OR_DIR...
A directory stands for the jars in it. Jars with the same file name are taken once (the first one).
"""
import argparse
import os
import re
import subprocess
import sys
import tempfile
import zipfile


def sdk_root(arg):
    for d in (arg, os.environ.get('ANDROID_HOME'), os.environ.get('ANDROID_SDK_ROOT')):
        if d and os.path.isdir(d):
            return d
    home = os.path.expanduser('~')
    for d in (os.path.join(os.environ.get('LOCALAPPDATA', ''), 'Android', 'Sdk'),
              os.path.join(home, 'Android', 'Sdk'), os.path.join(home, 'Library', 'Android', 'sdk')):
        if os.path.isdir(d):
            return d
    sys.exit('Android SDK not found: set ANDROID_HOME or pass --sdk')


def newest(parent, pattern):
    """The entry of parent matching pattern (with one version group) with the highest version."""
    best = None
    for name in os.listdir(parent) if os.path.isdir(parent) else []:
        m = re.fullmatch(pattern, name)
        if m:
            key = tuple(int(x) for x in re.findall(r'\d+', m.group(1)))
            if best is None or key > best[0]:
                best = (key, os.path.join(parent, name))
    return best[1] if best else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', required=True)
    ap.add_argument('--min-api', default='26')
    ap.add_argument('--sdk')
    ap.add_argument('-v', '--verbose', action='store_true')
    ap.add_argument('inputs', nargs='+')
    args = ap.parse_args()

    sdk = sdk_root(args.sdk)
    bt = newest(os.path.join(sdk, 'build-tools'), r'(\d+(?:\.\d+)*)')
    platform = newest(os.path.join(sdk, 'platforms'), r'android-(\d+(?:\.\d+)?)')
    d8 = bt and os.path.join(bt, 'lib', 'd8.jar')
    lib = platform and os.path.join(platform, 'android.jar')
    if not d8 or not os.path.isfile(d8) or not lib or not os.path.isfile(lib):
        sys.exit('need build-tools (d8.jar) and a platform (android.jar) in ' + sdk)

    jars, seen = [], set()
    for i in args.inputs:
        for j in sorted(os.path.join(i, f) for f in os.listdir(i) if f.endswith('.jar')) if os.path.isdir(i) else [i]:
            if os.path.basename(j) not in seen:
                seen.add(os.path.basename(j))
                jars.append(j)

    with tempfile.TemporaryDirectory() as tmp:
        dex_zip = os.path.join(tmp, 'dex.zip')
        cmd = ['java', '-cp', d8, 'com.android.tools.r8.D8', '--release', '--min-api', args.min_api,
               '--lib', lib, '--output', dex_zip] + jars
        r = subprocess.run(cmd, capture_output=True, text=True)
        if r.returncode != 0 or args.verbose:
            sys.stderr.write(r.stdout + r.stderr)
        if r.returncode != 0:
            sys.exit('d8 failed')
        resources = {}
        for j in jars:
            with zipfile.ZipFile(j) as z:
                for info in z.infolist():
                    n = info.filename
                    if (n.endswith('/') or n.endswith('.class') or n == 'META-INF/MANIFEST.MF' or n.startswith('META-INF/versions/')
                            or re.match(r'META-INF/[^/]+\.(SF|RSA|DSA|EC)$', n)):
                        continue
                    data = z.read(n)
                    if n in resources:
                        if n.startswith('META-INF/services/'):
                            resources[n] += b'\n' + data
                        continue
                    resources[n] = data
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        with zipfile.ZipFile(args.out, 'w', zipfile.ZIP_DEFLATED) as out:
            with zipfile.ZipFile(dex_zip) as d:
                for n in d.namelist():
                    out.writestr(n, d.read(n))
            for n, data in resources.items():
                out.writestr(n, data)
    print('%s: %d jars, %d bytes (d8 from %s, android.jar of %s)' % (
        args.out, len(jars), os.path.getsize(args.out), os.path.basename(bt), os.path.basename(platform)))


if __name__ == '__main__':
    main()
