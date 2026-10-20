#!/usr/bin/env python3
"""Run the pinned history-prefetch geometry and store backoff with actual Swift bodies."""
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
    def source(group, suffix):
        path = next(p for p in manifest[group] if p.endswith('/' + suffix))
        raw = subprocess.check_output(['git', '-C', str(args.upstream.resolve()), 'show', manifest['revision'] + ':' + path])
        if hashlib.sha256(raw).hexdigest() != manifest[group][path]: raise ValueError('Unreviewed source: ' + path)
        return raw.decode()
    geometry = source('historyFiles', 'ConversationHistoryPrefetch.swift')
    store = source('timelineFiles', 'ConversationStore.swift')
    start = store.index('    static func backoff(')
    backoff = store[start:store.index('\n    }', start) + len('\n    }')]
    fixture = '''import Foundation
enum Store {
BACKOFF
}
@main struct HistoryFixture {
    static func main() throws {
        let inputs: [(Double, Double)] = [(-1, 900), (0, 900), (3599, 900), (3600, 900),
            (3601, 900), (5399, 900), (5400, 900), (5401, 900), (0, 0), (1, 0),
            (0, 1), (3.999, 1), (4, 1), (6, 1), (6.001, 1), (3000, 500), (3001, 500)]
        let decisions: [[String: Any]] = inputs.map { distance, viewport in
            let decision = ConversationHistoryPrefetch.decision(distanceFromTop: distance, viewport: viewport)
            let label: String
            switch decision { case .load: label = "LOAD"; case .stop: label = "STOP"; case .none: label = "NONE" }
            return ["distance": distance, "viewport": viewport, "decision": label]
        }
        let delays: [[String: Any]] = [0, 1, 2, 3, 4, 5, 10, Int.max].map { attempt in
            let components = Store.backoff(attempt).components
            return ["attempt": attempt, "milliseconds": components.seconds * 1000 + components.attoseconds / 1_000_000_000_000_000]
        }
        FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: ["decisions": decisions, "backoff": delays], options: [.sortedKeys]))
    }
}
'''.replace('BACKOFF', backoff)
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    (output / 'geometry.swift').write_text(geometry)
    (output / 'fixture.swift').write_text(fixture)
    run = subprocess.run(['/usr/bin/swiftc', '-swift-version', '6', '-parse-as-library', str(output / 'geometry.swift'),
                          str(output / 'fixture.swift'), '-o', str(output / 'fixture')], capture_output=True, timeout=60)
    (output / 'build.log').write_bytes(run.stdout + run.stderr); run.check_returncode()
    vectors = json.loads(subprocess.check_output([str(output / 'fixture')], timeout=10))
    encoded = (json.dumps(vectors, indent=2, sort_keys=True) + '\n').encode()
    (output / 'vectors.json').write_bytes(encoded)
    if args.vectors:
        args.vectors.parent.mkdir(parents=True, exist_ok=True); args.vectors.write_bytes(encoded)
    elif (root / 'app/src/test/resources/parity/chief-history.json').read_bytes() != encoded:
        raise ValueError('Committed history vectors differ from actual Swift execution')
    receipt = {'revision': manifest['revision'], 'decisions': len(vectors['decisions']), 'backoff': len(vectors['backoff']),
               'vectorsSha256': hashlib.sha256(encoded).hexdigest(),
               'scope': 'Actual geometry file and store backoff; excludes asynchronous pager, UIKit and real-host evidence'}
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(f"Pinned Chief history: {receipt['decisions']} geometry and {receipt['backoff']} backoff vectors")


if __name__ == '__main__':
    main()
