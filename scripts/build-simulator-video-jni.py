#!/usr/bin/env python3
"""Link the bounded simulator Surface decoder against a verified Android FFmpeg core."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import platform
import subprocess

ROOT = Path(__file__).resolve().parents[1]
REVISION = '946fcce07b6dcd0331c8cc609192aeff5e1924f8'

def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--core', type=Path, required=True)
    p.add_argument('--ndk', type=Path, required=True)
    args = p.parse_args(); core, ndk = args.core.resolve(), args.ndk.resolve()
    receipt = json.loads((core / 'manifest.json').read_text())
    if receipt['sourceRevision'] != REVISION or receipt['ndk'] != '28.2.13676358':
        raise SystemExit('Wrong simulator codec checkpoint')
    if 'Pkg.Revision = 28.2.13676358' not in (ndk / 'source.properties').read_text().splitlines():
        raise SystemExit('Wrong NDK')
    for name, expected in receipt['files'].items():
        f = (core / name).resolve()
        if not f.is_relative_to(core) or digest(f) != expected: raise SystemExit(f'Changed core artifact: {name}')
    host = {'Darwin': 'darwin-x86_64', 'Linux': 'linux-x86_64'}[platform.system()]
    tools = ndk / 'toolchains/llvm/prebuilt' / host / 'bin'
    source = ROOT / 'app/src/main/c/simulator_video_jni.c'
    library = core / 'jniLibs/arm64-v8a/libcmux_video.so'; library.parent.mkdir(parents=True, exist_ok=True)
    libraries = [str(core / 'android/lib' / ('lib' + name + '.a')) for name in ['avcodec', 'swscale', 'avutil']]
    subprocess.run([str(tools / 'aarch64-linux-android26-clang'), '-shared', '-std=c11', '-O2', '-fPIC',
        '-Wall', '-Wextra', '-Werror', '-fvisibility=hidden', '-I', str(core / 'android/include'), str(source),
        *libraries, '-landroid', '-llog', '-lm', '-lz', '-Wl,--no-undefined', '-Wl,--exclude-libs,ALL',
        '-Wl,--gc-sections', '-Wl,-z,relro,-z,now', '-Wl,-z,max-page-size=16384', '-Wl,-z,common-page-size=16384',
        '-Wl,-soname,libcmux_video.so', '-o', str(library)], check=True)
    subprocess.run([str(tools / 'llvm-strip'), '--strip-debug', str(library)], check=True)
    spec = importlib.util.spec_from_file_location('alignment', ROOT / 'scripts/verify-native-alignment.py')
    check = importlib.util.module_from_spec(spec); spec.loader.exec_module(check)
    print(check.verify(library.read_bytes(), library.name))
    (core / 'jni-manifest.json').write_text(json.dumps({
        'sourceRevision': REVISION, 'ndk': '28.2.13676358', 'abi': 'arm64-v8a', 'androidApi': 26, 'elfPageSize': 16384,
        'bindingSourceSha256': digest(source), 'coreManifestSha256': digest(core / 'manifest.json'),
        'files': {str(library.relative_to(core)): digest(library), **{k: v for k, v in receipt['files'].items() if k.startswith('notices/')}}
    }, indent=2) + '\n')

if __name__ == '__main__': main()
