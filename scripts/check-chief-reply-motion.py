#!/usr/bin/env python3
"""Execute whole pinned ReplyTrip/geometry and actual threadRows projection."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def member(source, declaration):
    start = source.index(declaration)
    first = source.index('{', start)
    end = first + 1
    depth = 1
    while depth:
        if source[end] == '{': depth += 1
        elif source[end] == '}': depth -= 1
        end += 1
    return source[start:end]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--upstream', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--vectors', type=Path)
    a = p.parse_args()
    root = Path(__file__).resolve().parents[1]
    manifest = json.loads((root / 'third_party/cmux-chief/manifest.json').read_text())
    sources = {}
    for path, digest in manifest['replyMotionFiles'].items():
        data = subprocess.check_output(['git', '-C', str(a.upstream.resolve()), 'show', manifest['revision'] + ':' + path])
        if hashlib.sha256(data).hexdigest() != digest:
            raise ValueError('Unreviewed reply motion source: ' + path)
        sources[Path(path).name] = data.decode()
    out = a.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    inputs = []
    for name in ['ConversationReplyMotion.swift', 'ReplyTrip.swift']:
        target = out / name
        target.write_text(sources[name])
        inputs.append(target)
    reply = sources['ConversationViewController+Reply.swift']
    projection = member(reply, '    private func threadRows(')
    generated = out / 'SourceRows.swift'
    generated.write_text('import Foundation\nimport CoreGraphics\n'
        'typealias ReplyThreadRow = (view: UIView, baseY: CGFloat, startY: CGFloat, targetY: CGFloat, initialOffsetX: CGFloat)\n'
        '@MainActor final class SourceOwner {\n' + projection + '\n'
        'func project(_ overlay: ReplyThreadOverlay, _ starts: [ObjectIdentifier: CGFloat], _ anchor: String, _ offset: CGFloat) -> [ReplyThreadRow] {\n'
        'threadRows(in: overlay, starts: starts, anchorID: anchor, anchorOffset: offset)\n}\n}\n')
    device = out / 'SourceObservations.swift'
    arrays = []
    for name in ['deviceIntoY', 'deviceIntoX', 'deviceBackY']:
        matches = re.findall(r'    static let ' + name + r': \[CGFloat\] = \[[^]]+\]', sources['ReplyTripTests.swift'])
        if len(matches) != 1:
            raise ValueError('Recorded source observation array changed: ' + name)
        arrays.append(matches[0])
    device.write_text('import CoreGraphics\nenum SourceObservations {\n' + '\n'.join(arrays) + '\n}\n')
    binary = out / 'fixture'
    command = ['/usr/bin/swiftc', '-swift-version', '6'] + [str(x) for x in inputs + [generated, device]] + [
        str(root / 'scripts/chief-reply-motion-fixture.swift'), '-o', str(binary)]
    result = subprocess.run(command, capture_output=True, timeout=90)
    (out / 'build.log').write_bytes(result.stdout + result.stderr)
    result.check_returncode()
    vectors = json.loads(subprocess.check_output([str(binary)], timeout=30))
    encoded = (json.dumps(vectors, indent=2, sort_keys=True) + '\n').encode()
    (out / 'vectors.json').write_bytes(encoded)
    if a.vectors:
        a.vectors.parent.mkdir(parents=True, exist_ok=True)
        a.vectors.write_bytes(encoded)
    elif encoded != (root / 'app/src/test/resources/parity/chief-reply-motion.json').read_bytes():
        raise ValueError('Reply motion vectors differ')
    receipt = {'revision': manifest['revision'], 'sourcePins': manifest['replyMotionFiles'],
        'vectorsSha256': hashlib.sha256(encoded).hexdigest(),
        'generatedProjectionSha256': hashlib.sha256(generated.read_bytes()).hexdigest(),
        'geometryCases': sum(len(vectors[x]) for x in ['easings', 'steps', 'curves', 'snaps']),
        'trajectories': len(vectors['trips']), 'tripActions': sum(len(x['actions']) for x in vectors['trips']),
        'projectionCases': len(vectors['plans']),
        'sourceRecordedEnteringFrames': len(vectors['sourceObservations']['intoY']),
        'sourceRecordedReturningFrames': len(vectors['sourceObservations']['backY']),
        'scope': 'Whole actual Swift ReplyTrip and ConversationReplyMotion; verbatim threadRows with inert view identities/frames; arrays from upstream authored observation tests. Not UIKit coordinate conversion, CADisplayLink dispatch, Compose mounting, actual animation rendering or our physical-device evidence.'}
    (out / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps(receipt, indent=2))


if __name__ == '__main__':
    main()
