#!/usr/bin/env python3
"""Build only the pinned FFmpeg AVC/HEVC software decoders and RGB conversion for Android."""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import shutil
import subprocess
import tarfile

REVISION = '946fcce07b6dcd0331c8cc609192aeff5e1924f8'  # FFmpeg n9.0.2
NDK_VERSION = '28.2.13676358'
ROOT = Path(__file__).resolve().parents[1]

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source', required=True, type=Path)
    p.add_argument('--ndk', required=True, type=Path)
    p.add_argument('--output', type=Path, default=ROOT / 'build/simulator-codecs-android')
    args = p.parse_args()
    source, ndk, output = args.source.resolve(), args.ndk.resolve(), args.output.resolve()
    if f'Pkg.Revision = {NDK_VERSION}' not in (ndk / 'source.properties').read_text().splitlines():
        raise SystemExit('Wrong NDK version')
    actual = subprocess.check_output(['git', '-C', str(source), 'rev-parse', REVISION + '^{commit}'], text=True).strip()
    if actual != REVISION:
        raise SystemExit('Wrong FFmpeg source')
    output.mkdir(parents=True, exist_ok=True)
    archived = output / 'source.tar'
    expanded = output / 'source'
    if expanded.exists():
        raise SystemExit('Output source already exists; use a fresh output directory for a reproducible core build')
    subprocess.run(['git', '-C', str(source), 'archive', '--format=tar', '--output', str(archived), REVISION], check=True)
    expanded.mkdir()
    with tarfile.open(archived) as archive:
        archive.extractall(expanded, filter='data')
    archived.unlink()
    host = {'Darwin': 'darwin-x86_64', 'Linux': 'linux-x86_64'}[platform.system()]
    tools = ndk / 'toolchains/llvm/prebuilt' / host / 'bin'
    install = output / 'android'
    options = [
        '--target-os=android', '--arch=aarch64', '--enable-cross-compile',
        '--cc=' + str(tools / 'aarch64-linux-android26-clang'), '--cxx=' + str(tools / 'aarch64-linux-android26-clang++'),
        '--ar=' + str(tools / 'llvm-ar'), '--ranlib=' + str(tools / 'llvm-ranlib'), '--strip=' + str(tools / 'llvm-strip'),
        '--prefix=' + str(install), '--disable-autodetect', '--disable-everything', '--disable-programs',
        '--disable-doc', '--disable-debug', '--disable-network', '--disable-avformat', '--disable-avdevice',
        '--disable-avfilter', '--disable-swresample', '--disable-shared', '--enable-static', '--enable-pic',
        '--enable-small', '--enable-avcodec', '--enable-avutil', '--enable-swscale', '--enable-decoder=h264,hevc',
        '--pkg-config=false', '--extra-cflags=-fPIC -ffunction-sections -fdata-sections',
        '--extra-ldflags=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384'
    ]
    subprocess.run([str(expanded / 'configure'), *options], cwd=expanded, check=True)
    subprocess.run(['make', '-j2'], cwd=expanded, check=True)
    subprocess.run(['make', 'install'], cwd=expanded, check=True)
    notices = output / 'notices/licenses/simulator-video'
    notices.mkdir(parents=True, exist_ok=True)
    for name in ['COPYING.LGPLv2.1', 'LICENSE.md', 'COPYING.GPLv2', 'COPYING.GPLv3', 'COPYING.LGPLv3']:
        shutil.copy2(expanded / name, notices / name)
    # Configuration is the authority for the effective license; GPL/nonfree code is not enabled.
    config = (expanded / 'config.h').read_text()
    if '#define CONFIG_GPL 0' not in config or '#define CONFIG_NONFREE 0' not in config:
        raise SystemExit('Unexpected FFmpeg license configuration')
    shutil.copy2(expanded / 'config.h', output / 'config.h')
    receipt = {'sourceRevision': REVISION, 'sourceURL': 'https://github.com/FFmpeg/FFmpeg', 'tag': 'n9.0.2',
               'ndk': NDK_VERSION, 'androidApi': 26, 'abi': 'arm64-v8a', 'elfPageSize': 16384,
               'scriptSha256': digest(Path(__file__)), 'configure': options,
               'files': {str(f.relative_to(output)): digest(f) for root in [install, output / 'notices']
                         for f in sorted(root.rglob('*')) if f.is_file()}}
    receipt['files']['config.h'] = digest(output / 'config.h')
    (output / 'manifest.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print('Pinned Android simulator decoder core complete:', output)

if __name__ == '__main__':
    main()
