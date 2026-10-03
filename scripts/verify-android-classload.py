#!/usr/bin/env python3
"""Verify APK classes with the connected device's ART, without installing the APK.

Requires JDK 17, SDK build-tools 36.0.0, and an explicitly selected booted device.
This catches release DEX verification failures that JVM tests and packaging gates
cannot detect. It does not replace actual Activity launch or authenticated tests.
"""
import argparse
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import uuid

JAVA = '''public final class VerifyAndroidClasses {
    public static void main(String[] names) {
        try {
            for (String name : names) {
                Class<?> type = Class.forName(name, true, VerifyAndroidClasses.class.getClassLoader());
                System.out.println("VERIFIED " + name + " methods=" + type.getDeclaredMethods().length);
            }
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--sdk', type=Path, default=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT'))
    args = parser.parse_args()
    if args.sdk is None or not args.apk.is_file():
        parser.error('Provide an existing APK and ANDROID_HOME or --sdk')
    java_home = os.environ.get('JAVA_HOME')
    javac = str(Path(java_home) / 'bin/javac') if java_home else shutil.which('javac')
    if not javac:
        parser.error('Set JAVA_HOME to JDK 17')
    suffix = '.exe' if os.name == 'nt' else ''
    adb = [str(args.sdk / 'platform-tools' / ('adb' + suffix)), '-s', args.serial]
    d8 = args.sdk / 'build-tools/36.0.0' / ('d8.bat' if os.name == 'nt' else 'd8')
    def run(argv, **kwargs):
        return subprocess.run([str(x) for x in argv], check=True, timeout=120, **kwargs)
    boot = run(adb + ['shell', 'getprop', 'sys.boot_completed'], capture_output=True, text=True)
    if boot.stdout.strip() != '1':
        parser.error('Wait for the selected device to finish booting')
    remote = '/data/local/tmp/cmux-classcheck-' + uuid.uuid4().hex
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='cmux-classcheck-') as directory:
        root = Path(directory)
        source = root / 'VerifyAndroidClasses.java'
        source.write_text(JAVA, encoding='utf-8')
        run([javac, '--release', '8', source])
        run([d8, '--min-api', '26', '--output', root, root / 'VerifyAndroidClasses.class'])
        try:
            run(adb + ['shell', 'mkdir', remote])
            run(adb + ['push', args.apk.resolve(), remote + '/candidate.apk'], capture_output=True)
            run(adb + ['push', root / 'classes.dex', remote + '/probe.dex'], capture_output=True)
            run(adb + ['shell', 'chmod', '444', remote + '/candidate.apk', remote + '/probe.dex'])
            name = 'io.github.docmorphic.cmuxapp.NativeScreenKt'
            command = 'CLASSPATH=' + shlex.quote(remote + '/probe.dex:' + remote + '/candidate.apk') + ' app_process /system/bin VerifyAndroidClasses ' + name
            result = subprocess.run(adb + ['shell', command], capture_output=True, text=True, timeout=120)
            args.output.write_text(result.stdout + result.stderr, encoding='utf-8')
            if result.returncode or 'VERIFIED ' + name + ' methods=' not in result.stdout:
                raise SystemExit('ART class verification failed; inspect ' + str(args.output))
            print(result.stdout.strip())
        finally:
            subprocess.run(adb + ['shell', 'rm', '-rf', remote], timeout=30, check=False, capture_output=True)


if __name__ == '__main__':
    main()
