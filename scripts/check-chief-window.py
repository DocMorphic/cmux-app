#!/usr/bin/env python3
"""Execute the pinned store's complete trim methods with inert Chief-only state."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--vectors', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    manifest = json.loads((root / 'third_party/cmux-chief/manifest.json').read_text())
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    sources = {}
    for name in ('ConversationStore.swift', 'ConversationModel.swift'):
        path = next(path for path in manifest['timelineFiles'] if path.endswith('/' + name))
        raw = subprocess.check_output(['git', '-C', str(args.upstream.resolve()), 'show', manifest['revision'] + ':' + path])
        if hashlib.sha256(raw).hexdigest() != manifest['timelineFiles'][path]: raise ValueError('Unreviewed window source: ' + path)
        sources[name] = raw.decode()
    store = sources['ConversationStore.swift']
    def body(signature):
        start = store.index('    ' + signature)
        if signature.startswith('public func trimOlder'):
            start = store.rfind('    @discardableResult', 0, start)
        return store[start:store.index('\n    }', start) + len('\n    }')]
    constants = '\n'.join(line for line in store.splitlines() if 'public static let windowTrimThreshold' in line or 'public static let windowKeepCount' in line)
    row_id = next(line for line in sources['ConversationModel.swift'].splitlines() if 'public var rowID: String {' in line)
    fixture = (root / 'scripts/chief-window-fixture.swift').read_text().replace('// PINNED_ROW_ID', row_id)
    fixture = fixture.replace('// PINNED_CONSTANTS', constants).replace('// PINNED_TRIM', body('public func trimOlder(keepingNewest'))
    fixture = fixture.replace('// PINNED_IF_LARGE', body('public func trimOlderIfLarge('))
    (output / 'fixture.swift').write_text(fixture)
    compiled = subprocess.run(['/usr/bin/swiftc', '-swift-version', '6', '-parse-as-library', str(output / 'fixture.swift'),
                               '-o', str(output / 'fixture')], capture_output=True, timeout=60)
    (output / 'build.log').write_bytes(compiled.stdout + compiled.stderr); compiled.check_returncode()
    vectors = json.loads(subprocess.check_output([str(output / 'fixture')], timeout=10))
    encoded = (json.dumps(vectors, indent=2, sort_keys=True) + '\n').encode()
    (output / 'vectors.json').write_bytes(encoded)
    target = root / 'app/src/test/resources/parity/chief-window.json'
    if args.vectors:
        args.vectors.parent.mkdir(parents=True, exist_ok=True); args.vectors.write_bytes(encoded)
    elif target.read_bytes() != encoded: raise ValueError('Committed window vectors differ from actual Swift execution')
    receipt = {'revision': manifest['revision'], 'cases': len(vectors['cases']), 'threshold': vectors['threshold'],
               'keep': vectors['keep'], 'vectorsSha256': hashlib.sha256(encoded).hexdigest(),
               'scope': 'Actual trim, trim-if-large and row-ID bodies; inert message/poll/read/notification state, not UIKit or a live host'}
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(f"Pinned Chief window: {len(vectors['cases'])} actual Swift vectors; threshold {vectors['threshold']}, keep {vectors['keep']}")


if __name__ == '__main__':
    main()
