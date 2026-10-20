#!/usr/bin/env python3
"""Pair exported XCTest PNGs with their scene metadata by exact payload hash."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct


def verify(exported, output, project_commit):
    root = Path(__file__).resolve().parents[1]
    manifest = json.loads((root / 'third_party/cmux-chief/manifest.json').read_text())
    scene_bytes = (root / 'tests/ios-chief-reference/Scenes.json').read_bytes()
    expected = {scene['id'] for scene in json.loads(scene_bytes)['scenes']}
    scene_sha = hashlib.sha256(scene_bytes).hexdigest()
    images = {}; metadata = {}
    for path in exported.rglob('*'):
        if not path.is_file() or path.stat().st_size > 32 * 1024 * 1024: continue
        data = path.read_bytes()
        if data.startswith(b'\x89PNG\r\n\x1a\n'):
            images[hashlib.sha256(data).hexdigest()] = path
        elif data.lstrip().startswith(b'{'):
            try: value = json.loads(data)
            except (ValueError, UnicodeError): continue
            if isinstance(value, dict) and 'scene' in value and 'imageSha256' in value:
                if value['scene'] in metadata: raise ValueError('Duplicate scene metadata')
                metadata[value['scene']] = (path, value)
    if set(metadata) != expected: raise ValueError(f'Missing/unexpected rendered scenes: expected {sorted(expected)}, found {sorted(metadata)}')
    paired = []
    for name in sorted(expected):
        path, value = metadata[name]; provenance = value['provenance']
        if value['fixtureSha256'] != scene_sha or provenance['scenesSha256'] != scene_sha: raise ValueError('Scene fixture changed')
        if provenance['upstreamRevision'] != manifest['revision'] or provenance['packageTree'] != manifest['referencePackage']['tree']:
            raise ValueError('Unreviewed UIKit source')
        if provenance['projectSourceDirty']: raise ValueError('Reference project was built from an uncommitted tree')
        if provenance['projectSourceCommit'] != project_commit: raise ValueError('Unexpected reference project commit')
        image = images.get(value['imageSha256'])
        if image is None: raise ValueError('Missing or changed screenshot payload: ' + name)
        dimensions = struct.unpack('>II', image.read_bytes()[16:24])
        expected_dimensions = tuple(round(d * value['scale']) for d in value['viewport'])
        if dimensions != expected_dimensions or min(dimensions) <= 0: raise ValueError('Wrong rendered image dimensions: ' + name)
        if not value['labels'] or value['storeMessages'] <= 0: raise ValueError('No rendered transcript evidence: ' + name)
        paired.append((name, path, image, value))
    output.mkdir(parents=True, exist_ok=False)
    for name, path, image, value in paired:
        shutil.copyfile(image, output / (name + '.png')); shutil.copyfile(path, output / (name + '.json'))
    receipt = {'revision': manifest['revision'], 'packageTree': manifest['referencePackage']['tree'], 'scenesSha256': scene_sha,
               'scenes': sorted(expected), 'projectSourceCommits': sorted({v['provenance']['projectSourceCommit'] for _, _, _, v in paired}),
               'scope': 'Verified source/fixture/PNG payload association; images still require visual inspection, matched Android comparison and real-device acceptance'}
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps(receipt, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--exported', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--project-commit', required=True)
    args = parser.parse_args(); verify(args.exported.resolve(), args.output.resolve(), args.project_commit)
