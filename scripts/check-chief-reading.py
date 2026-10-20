#!/usr/bin/env python3
"""Execute pinned read/catch-up store bodies with inert Chief-only models and receipt backend."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--vectors', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    manifest = json.loads((root / 'third_party/cmux-chief/manifest.json').read_text())
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    path = 'Packages/iOS/CmuxConversation/Sources/CmuxConversationCore/ConversationStore.swift'
    raw = subprocess.check_output(['git', '-C', str(args.upstream.resolve()), 'show', manifest['revision'] + ':' + path])
    if hashlib.sha256(raw).hexdigest() != manifest['timelineFiles'][path]:
        raise ValueError('Unreviewed read-state store')
    source = raw.decode()
    signatures = ['private var newestIncomingSeq:', 'public func markRead(through', 'public func setViewing(',
                  'public func endVisit()', 'public func dismissCatchUp()', 'public var catchUpTarget:',
                  'private func refreshReadState()', 'private func computeUnread()']
    bodies = []
    for signature in signatures:
        start = source.index('    ' + signature)
        end = source.index('\n    }', start) + len('\n    }')
        bodies.append(source[start:end])
    start = source.index('        case let .readState(state):') + len('        case let .readState(state):\n')
    event = source[start:source.index('        case let .conversationChanged', start)]
    fixture = (root / 'scripts/chief-reading-fixture.swift').read_text().replace('// PINNED_METHODS', '\n'.join(bodies)).replace('// PINNED_READ_EVENT', event)
    (output / 'fixture.swift').write_text(fixture)
    compiled = subprocess.run(['/usr/bin/swiftc', '-swift-version', '6', '-parse-as-library', str(output / 'fixture.swift'),
                               '-o', str(output / 'fixture')], capture_output=True, timeout=60)
    (output / 'build.log').write_bytes(compiled.stdout + compiled.stderr); compiled.check_returncode()
    vectors = json.loads(subprocess.check_output([str(output / 'fixture')], timeout=10))
    encoded = (json.dumps(vectors, indent=2, sort_keys=True) + '\n').encode()
    (output / 'vectors.json').write_bytes(encoded)
    if args.vectors:
        args.vectors.parent.mkdir(parents=True, exist_ok=True); args.vectors.write_bytes(encoded)
    elif encoded != (root / 'app/src/test/resources/parity/chief-reading.json').read_bytes():
        raise ValueError('Committed Chief read vectors differ from actual Swift execution')
    strings = manifest['catchUpStrings']
    catalog = subprocess.check_output(['git', '-C', str(args.upstream.resolve()), 'show', manifest['revision'] + ':' + strings['path']])
    if hashlib.sha256(catalog).hexdigest() != strings['sha256']: raise ValueError('Unreviewed catch-up catalog')
    entries = json.loads(catalog)['strings']
    for locale, folder in {'en': 'values', 'ja': 'values-ja'}.items():
        actual = {n.attrib['name']: n.text for n in ET.parse(root / 'app/src/main/res' / folder / 'chief_reading.xml').getroot()}
        expected = {name: entries[key]['localizations'][locale]['stringUnit']['value'] for key, name in strings['keys'].items()}
        if actual != expected: raise ValueError('Catch-up labels differ from source: ' + locale)
    receipt = {'revision': manifest['revision'], 'source': {path: manifest['timelineFiles'][path]}, 'methods': signatures,
               'cases': len(vectors), 'labelValues': 2, 'vectorsSha256': hashlib.sha256(encoded).hexdigest(),
               'scope': 'Actual read-event, read-state and catch-up bodies; inert direct-conversation model and receipt backend. No UIKit, network or live host.'}
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(f'Pinned Chief read/catch-up policy: {len(vectors)} Swift vectors and two source labels')


if __name__ == '__main__':
    main()
