#!/usr/bin/env python3
"""Run exact pinned swipe geometry/update method with inert UIKit/haptic dependencies."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--upstream', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--vectors', type=Path)
    a = p.parse_args()
    root = Path(__file__).resolve().parents[1]
    manifest = json.loads((root / 'third_party/cmux-chief/manifest.json').read_text())
    sources = {}
    for path, digest in manifest['replySwipeFiles'].items():
        data = subprocess.check_output(['git', '-C', str(a.upstream.resolve()), 'show', manifest['revision'] + ':' + path])
        if hashlib.sha256(data).hexdigest() != digest:
            raise ValueError('Unreviewed swipe source: ' + path)
        sources[Path(path).name] = data.decode()
    out = a.output.resolve(); out.mkdir(parents=True, exist_ok=True)
    geometry = out / 'ConversationReplyMotion.swift'
    geometry.write_text(sources['ConversationReplyMotion.swift'])
    swipe = sources['ConversationViewController+ReplySwipe.swift']
    update = swipe[swipe.index('    func updateReplySwipe('):swipe.index('    func endReplySwipe(')]
    release = '        let commit = replyHapticFired && ended'
    if swipe.count(release) != 1 or 'model.message.seq != nil' not in swipe:
        raise ValueError('Review acknowledged-message/release guards')
    gestures = sources['ConversationViewController+Gestures.swift']
    if 'velocity.x > 0 && replySwipeCell(at: pan.location(in: collectionView), velocity: velocity) != nil' not in gestures:
        raise ValueError('Review rightward gesture admission')
    owner = out / 'SourceOwner.swift'
    owner.write_text('import Foundation\nimport CoreGraphics\n@MainActor final class SourceOwner {\n'
        'var replyDragOffset: CGFloat = 0\nvar replyHapticFired = false\nlet collectionView = SourceCollection()\n'
        'func indexPath(for: String) -> IndexPath? { IndexPath(index: 0) }\n' + update +
        '\nfunc sourceRelease(ended: Bool) -> Bool {\n' + release + '\nreturn commit\n}\n}\n')
    binary = out / 'fixture'
    result = subprocess.run(['/usr/bin/swiftc', '-swift-version', '6', str(geometry), str(owner),
        str(root / 'scripts/chief-reply-swipe-fixture.swift'), '-o', str(binary)], capture_output=True, timeout=90)
    (out / 'build.log').write_bytes(result.stdout + result.stderr); result.check_returncode()
    vectors = json.loads(subprocess.check_output([str(binary)], timeout=20))
    encoded = (json.dumps(vectors, indent=2, sort_keys=True) + '\n').encode()
    (out / 'vectors.json').write_bytes(encoded)
    target = root / 'app/src/test/resources/parity/chief-reply-swipe.json'
    if a.vectors:
        a.vectors.parent.mkdir(parents=True, exist_ok=True); a.vectors.write_bytes(encoded)
    elif encoded != target.read_bytes():
        raise ValueError('Swipe vectors differ')
    receipt = {'revision': manifest['revision'], 'sourcePins': manifest['replySwipeFiles'],
        'vectorsSha256': hashlib.sha256(encoded).hexdigest(),
        'geometryCases': sum(len(vectors[key]) for key in ('offsets', 'areas', 'angles')),
        'updateSequences': len(vectors['crossings']), 'updateSteps': sum(len(x['steps']) for x in vectors['crossings']),
        'scope': 'Exact whole Swift geometry and actual update/release logic with inert collection/cell/indicator/haptic inputs. Static source admission audit. Not UIKit gesture dispatch, haptic timing, artwork/motion or physical acceptance.'}
    (out / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps(receipt, indent=2))


if __name__ == '__main__':
    main()
