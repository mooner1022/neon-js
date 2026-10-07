#!/usr/bin/env python3
"""Runs NeonJS bundles (made by bundle.py) on an Android device or emulator, through adb and app_process.

  device.py [-s SERIAL] install BUNDLE.jar [--name NAME]   push to /data/local/tmp/NAME.jar, compile ahead of time
  device.py [-s SERIAL] push-test262 ROOT DIR...            copy Test262's harness and test directories (test/...)
  device.py [-s SERIAL] run NAME [-Dprop=value...] MAIN [ARG...]
                                                            run class MAIN of the bundle; exit status of the program
  device.py [-s SERIAL] test262 NAME [-Dprop=value...] [--known FILE] [RUNNER ARGS...]
                                                            run the Test262 runner on the copied tests

Ahead-of-time compilation (dex2oat, as an installed app gets) matters: app_process does not JIT-compile a jar by
itself, so without it the engine would run in ART's interpreter. NAME defaults to the bundle's file name.
"""
import argparse
import os
import shlex
import subprocess
import sys
import tarfile
import tempfile

DIR = '/data/local/tmp'
ISA = {'arm64-v8a': 'arm64', 'armeabi-v7a': 'arm', 'x86_64': 'x86_64', 'x86': 'x86'}


class Device:
    def __init__(self, serial):
        sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or os.path.join(
            os.environ.get('LOCALAPPDATA', ''), 'Android', 'Sdk')
        exe = os.path.join(sdk, 'platform-tools', 'adb.exe' if os.name == 'nt' else 'adb')
        self.adb = [exe if os.path.isfile(exe) else 'adb'] + (['-s', serial] if serial else [])

    def shell(self, cmd, capture=False):
        r = subprocess.run(self.adb + ['shell', cmd], capture_output=capture, text=True)
        return r if capture else r.returncode

    def out(self, cmd):
        return self.shell(cmd, capture=True).stdout.strip()

    def push(self, local, remote):
        subprocess.run(self.adb + ['push', local, remote], check=True, stdout=subprocess.DEVNULL)


def split_vm_options(args):
    """Leading -D options go to the VM (before app_process's class directory)."""
    i = 0
    while i < len(args) and args[i].startswith('-D'):
        i += 1
    return args[:i], args[i:]


def install(dev, bundle, name):
    name = name or os.path.splitext(os.path.basename(bundle))[0]
    dev.push(bundle, '%s/%s.jar' % (DIR, name))
    abi = dev.out('getprop ro.product.cpu.abi')
    isa = ISA.get(abi)
    if not isa:
        sys.exit('unknown ABI ' + abi)
    dex2oat = dev.out('for d in /apex/com.android.art/bin/dex2oat64 /apex/com.android.art/bin/dex2oat32 '
                      '/apex/com.android.runtime/bin/dex2oat /system/bin/dex2oat64 /system/bin/dex2oat; '
                      'do [ -x $d ] && echo $d && break; done')
    if not dex2oat:
        sys.exit('no dex2oat on the device')
    oat = '%s/oat/%s' % (DIR, isa)
    rc = dev.shell('mkdir -p %s && rm -f %s/%s.* && %s --dex-file=%s/%s.jar --oat-file=%s/%s.odex --instruction-set=%s '
                   '--compiler-filter=speed' % (oat, oat, name, dex2oat, DIR, name, oat, name, isa))
    if rc != 0:
        sys.exit('dex2oat failed (%d)' % rc)
    api = dev.out('getprop ro.build.version.sdk')
    print('installed %s.jar on API %s (%s), compiled ahead of time with %s' % (name, api, isa, dex2oat))


def push_test262(dev, root, dirs):
    with tempfile.TemporaryDirectory() as tmp:
        tar = os.path.join(tmp, 't262.tar')
        n = 0

        def as_shell(info):
            # owned by the shell user, which extracts them (older toybox tar fails to chown to anyone else)
            info.uid = info.gid = 2000
            info.uname = info.gname = 'shell'
            return info

        with tarfile.open(tar, 'w', format=tarfile.USTAR_FORMAT) as t:
            for d in ['harness'] + ['test/' + d.removeprefix('test/') for d in dirs]:
                for base, _, files in os.walk(os.path.join(root, d)):
                    for f in files:
                        p = os.path.join(base, f)
                        t.add(p, arcname=os.path.relpath(p, root).replace(os.sep, '/'), filter=as_shell)
                        n += 1
        dev.push(tar, DIR + '/t262.tar')
    rc = dev.shell('rm -rf {0}/test262 && mkdir -p {0}/test262 && cd {0}/test262 && tar -xf ../t262.tar && rm ../t262.tar'.format(DIR))
    if rc != 0:
        sys.exit('extracting the tests failed')
    print('pushed %d Test262 files' % n)


def run(dev, name, rest):
    vm, rest = split_vm_options(rest)
    cmd = 'cd %s && CLASSPATH=%s/%s.jar app_process %s %s %s' % (
        DIR, DIR, name, ' '.join(shlex.quote(o) for o in vm), DIR, ' '.join(shlex.quote(a) for a in rest))
    rc = dev.shell(cmd)
    if rc >= 128:
        # killed by a signal (the runtime aborted): say why, from the crash log
        lines = dev.out('logcat -d -b crash -t 300 2>/dev/null; logcat -d -t 2000 2>/dev/null').splitlines()
        why = [l for l in lines if 'Abort message' in l or 'Fatal signal' in l]
        print('app_process died with signal %d%s' % (rc - 128, ''.join('\n  ' + l.strip() for l in why[-4:])))
    return rc


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('-s', '--serial', help='adb device serial (default: the only device)')
    sub = ap.add_subparsers(dest='cmd', required=True)
    p = sub.add_parser('install')
    p.add_argument('bundle')
    p.add_argument('--name')
    p = sub.add_parser('push-test262')
    p.add_argument('root')
    p.add_argument('dirs', nargs='+')
    p = sub.add_parser('run')
    p.add_argument('name')
    p.add_argument('rest', nargs=argparse.REMAINDER)
    p = sub.add_parser('test262')
    p.add_argument('name')
    p.add_argument('rest', nargs=argparse.REMAINDER)
    args = ap.parse_args()
    dev = Device(args.serial)
    if args.cmd == 'install':
        install(dev, args.bundle, args.name)
    elif args.cmd == 'push-test262':
        push_test262(dev, args.root, args.dirs)
    elif args.cmd == 'run':
        sys.exit(run(dev, args.name, args.rest))
    else:
        vm, rest = split_vm_options(args.rest)
        if '--known' in rest:
            i = rest.index('--known')
            dev.push(rest[i + 1], DIR + '/known-failures.txt')
            rest[i + 1] = DIR + '/known-failures.txt'
        sys.exit(run(dev, args.name, vm + ['dev.mooner.neonjs.test262.MainKt', '--root', DIR + '/test262'] + rest))


if __name__ == '__main__':
    main()
