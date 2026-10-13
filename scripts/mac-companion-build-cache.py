#!/usr/bin/env python3
"""Scope compiler reuse and normalize actual patched source contents for Xcode."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path, PurePosixPath
import stat
import subprocess

ROOT = Path(__file__).resolve().parents[1]
BASE_SECONDS = 978_307_200
SPAN_SECONDS = 15 * 365 * 24 * 60 * 60


def digest(data):
    return hashlib.sha256(data).hexdigest()


def stable_mtime(data):
    value = digest(data)
    seconds = BASE_SECONDS + int(value[:12], 16) % SPAN_SECONDS
    return seconds * 1_000_000_000 + int(value[12:20], 16) % 1_000_000_000


def normalize_sources(directory, paths):
    directory = directory.resolve()
    prepared = []
    # Validate all paths before changing any timestamps. Never follow a symlink.
    for name in sorted(set(paths)):
        relative = PurePosixPath(name)
        if relative.is_absolute() or '..' in relative.parts or not relative.parts:
            raise ValueError('Source path is outside the checkout')
        path = directory / name
        if any(parent.is_symlink() for parent in path.parents if parent != directory and directory in parent.parents):
            raise ValueError('Source parent is a symlink')
        try:
            mode = path.lstat().st_mode
        except FileNotFoundError:
            continue  # Deleted tracked inputs stay deleted and invalidate normally.
        if stat.S_ISLNK(mode):
            content = os.fsencode(os.readlink(path))
        elif stat.S_ISREG(mode):
            content = path.read_bytes()
        else:
            continue
        prepared.append((path, stable_mtime(content)))
    for path, mtime in prepared:
        os.utime(path, ns=(path.lstat().st_atime_ns, mtime), follow_symlinks=False)
    return len(prepared)


def cache_keys(contract, patch_hashes, run_id, attempt):
    if not run_id.isdecimal() or not attempt.isdecimal():
        raise ValueError('Invalid workflow run identity')
    prefix = 'cmux-mac-compile-v1-' + digest(json.dumps(contract, sort_keys=True).encode()) + '-'
    inputs = digest(json.dumps(patch_hashes, sort_keys=True).encode())
    return {'prefix': prefix, 'sourcePrefix': prefix + inputs + '-',
            'key': prefix + inputs + '-run-' + run_id + '-' + attempt}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('keys', 'normalize'))
    parser.add_argument('--upstream', type=Path, required=True)
    parser.add_argument('--derived-data', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    directory = args.upstream.resolve()
    spec = importlib.util.spec_from_file_location('native_patch', ROOT / 'scripts/apply-native-push-patch.py')
    patch = importlib.util.module_from_spec(spec); spec.loader.exec_module(patch)
    original = {path: subprocess.check_output(['git', '-C', str(directory), 'show', f'{patch.REF}:{path}']) for path in patch.PINS}
    expected = patch.patched_sources(original)
    if any((directory / path).read_bytes() != data for path, data in expected.items()):
        raise ValueError('Checkout does not contain the reviewed source patch')
    if args.operation == 'normalize':
        tracked = subprocess.check_output(['git', '-C', str(directory), 'ls-files', '--recurse-submodules', '--stage', '-z'])
        paths = set(expected)
        for record in tracked.split(b'\0'):
            if not record:
                continue
            metadata, path = record.split(b'\t', 1)
            mode, _, stage = metadata.split()
            if stage != b'0':
                raise ValueError('Unmerged source index')
            if mode != b'160000':
                paths.add(os.fsdecode(path))
        result = {'normalizedSources': normalize_sources(directory, paths),
                  'policy': 'SHA-256 of actual working-tree bytes, including patched and added files; never index blob IDs'}
    else:
        if args.derived_data is None:
            parser.error('--derived-data is required for keys')
        head = subprocess.check_output(['git', '-C', str(directory), 'rev-parse', 'HEAD'], text=True).strip()
        if head != patch.REF:
            raise ValueError('Unreviewed upstream revision')
        contract = {'upstream': head, 'sourcePath': str(directory), 'derivedPath': str(args.derived_data.resolve()),
                    'xcode': subprocess.check_output(['xcodebuild', '-version'], text=True).strip(),
                    'sdk': subprocess.check_output(['xcrun', '--sdk', 'macosx', '--show-sdk-version'], text=True).strip(),
                    'sdkPath': subprocess.check_output(['xcrun', '--sdk', 'macosx', '--show-sdk-path'], text=True).strip(),
                    'os': subprocess.check_output(['sw_vers', '-productVersion'], text=True).strip(),
                    'arch': os.uname().machine,
                    'workflow': digest((ROOT / '.github/workflows/mac-notification-companion.yml').read_bytes()),
                    'cachePolicy': digest(Path(__file__).read_bytes())}
        hashes = {path: digest(data) for path, data in expected.items()}
        result = {**cache_keys(contract, hashes, os.environ['GITHUB_RUN_ID'], os.environ['GITHUB_RUN_ATTEMPT']),
                  'contract': contract, 'patchFiles': hashes}
        with Path(os.environ['GITHUB_OUTPUT']).open('a') as stream:
            for name in ('key', 'prefix', 'sourcePrefix'):
                stream.write(name + '=' + result[name] + '\n')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print('Mac companion cache inputs verified' if args.operation == 'keys' else f"Normalized {result['normalizedSources']} source timestamps")


if __name__ == '__main__':
    main()
