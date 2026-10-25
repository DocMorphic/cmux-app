#!/usr/bin/env python3
"""Rehash an archived iOS reference app, including its Debug implementation dylib.

This verifies built bundle bytes against the capture's product manifest. The
caller must separately bind the GitHub artifact, source revision and UI results.
No downloaded code is executed or extracted.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import plistlib
import posixpath
import stat
import zipfile


def digest(data):
    return hashlib.sha256(data).hexdigest()


def verify(capture):
    product = json.loads((capture / 'build-product.json').read_text())
    name = product['archive']
    assert Path(name).name == name and name.endswith('.zip'), 'Unsafe app archive name'
    archive = capture / name
    assert digest(archive.read_bytes()) == product['archiveSha256'], 'App archive digest mismatch'
    actual = {}; links = {}; seen = set(); roots = set(); executable = None; info_raw = None
    with zipfile.ZipFile(archive) as bundle:
        for entry in bundle.infolist():
            name = entry.filename; path = PurePosixPath(name)
            assert name and not path.is_absolute() and '..' not in path.parts and '\\' not in name
            assert str(path) == name.rstrip('/') and name not in seen, 'Noncanonical or duplicate archive entry'
            seen.add(name)
            mode = entry.external_attr >> 16
            assert stat.S_IFMT(mode) in [0, stat.S_IFREG, stat.S_IFDIR, stat.S_IFLNK], 'Unsupported archive entry'
            if path.parts[0] == '__MACOSX':
                assert not stat.S_ISLNK(mode), 'Metadata symlink'
                continue
            root = path.parts[0]; roots.add(root)
            assert root.endswith('.app') and len(path.parts) > 1 or entry.is_dir(), 'Unexpected archive root'
            if entry.is_dir():
                continue
            relative = '/'.join(path.parts[1:]); raw = bundle.read(entry)
            if stat.S_ISLNK(mode):
                target = raw.decode('utf-8')
                resolved = posixpath.normpath(posixpath.join(posixpath.dirname(name), target))
                assert target and not target.startswith('/') and resolved.startswith(root + '/'), 'Escaping bundle link'
                links[relative] = target
            else:
                actual[relative] = digest(raw)
                if relative == 'Info.plist':
                    info_raw = raw
        assert len(roots) == 1 and info_raw is not None, 'Expected exactly one complete app'
        assert actual == product['bundleFilesSha256'], 'Bundle file identity mismatch'
        assert links == product['bundleSymlinks'], 'Bundle link identity mismatch'
        assert digest(info_raw) == product['infoSha256']
        info = plistlib.loads(info_raw)
        assert info['CFBundleIdentifier'] == product['bundleID']
        executable = info['CFBundleExecutable']
        assert executable and Path(executable).name == executable
        assert actual[executable] == product['executableSha256']
        assert product['resourceSha256'] and all(actual[name] == value for name, value in product['resourceSha256'].items())
    return dict(archiveSha256=product['archiveSha256'], bundleID=product['bundleID'],
                regularFiles=len(actual), links=len(links), implementationDylibs=sorted(p for p in actual if p.endswith('.debug.dylib')),
                scope='Archived bundle bytes, Info, launcher, resources and all implementation files rehashed. Does not establish source provenance, executed UI results, visual equivalence or physical/full-app parity.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--capture', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = verify(args.capture.resolve())
    with args.output.open('x') as stream:
        stream.write(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
