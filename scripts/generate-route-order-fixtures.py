#!/usr/bin/env python3
"""Run the pinned iOS route comparator against synthetic, credential-free cases."""
import argparse
import hashlib
import json
from pathlib import Path
import random
import subprocess
import tempfile

PIN = '186cec79781256867ad4516f0802118738bd2393'
SOURCE = 'Packages/iOS/CmuxMobileShell/Sources/CmuxMobileShell/MobileShellComposite+Helpers.swift'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=Path('app/src/test/resources/pairing/route-order.json'))
    args = parser.parse_args()
    source = subprocess.check_output(['git', '-C', str(args.source), 'show', PIN + ':' + SOURCE])
    text = source.decode()
    start = text.index('    static func routeSortsBefore(')
    end = text.index('\n    }', start) + len('\n    }')
    comparator = text[start:end]
    # Extract the complete comparator byte-for-byte. The stand-in only supplies
    # the two fields it reads; no transport or authentication implementation runs.
    ids = ['z', 'a', 'a2', 'a10', 'A', 'é', 'e\u0301', '\ue000', '😀', '中',
           'Å', 'A\u030a', 'a\u0315\u0300', 'à\u0315', 'a/b', 'a']
    populations = [
        [('max', 2**63 - 1), ('zero', 0), ('min', -(2**63)), ('negative', -1), ('one', 1)],
        [(name, 0) for name in ids],
        [(name, (i % 3) - 1) for i, name in enumerate(ids)],
    ]
    cases = []
    randomizer = random.Random(596)
    for group, values in enumerate(populations):
        for variant in range(4):
            rows = [dict(id=name, priority=priority, key=i) for i, (name, priority) in enumerate(values)]
            if variant:
                randomizer.shuffle(rows)
            cases.append(dict(name=f'group_{group}_order_{variant}', routes=rows))
    runner = '''import Foundation
struct CmxAttachRoute: Decodable { let id: String; let priority: Int; let key: Int }
struct Case: Decodable { let name: String; let routes: [CmxAttachRoute] }
enum Reference {
''' + comparator + r'''
}
let cases = try JSONDecoder().decode([Case].self, from: FileHandle.standardInput.readDataToEndOfFile())
let results = cases.map { test in test.routes.sorted(by: Reference.routeSortsBefore).map(\.key) }
FileHandle.standardOutput.write(try JSONEncoder().encode(results))
'''
    with tempfile.TemporaryDirectory(prefix='cmux-route-order-') as directory:
        root = Path(directory)
        (root / 'main.swift').write_text(runner)
        subprocess.run(['xcrun', 'swiftc', str(root / 'main.swift'), '-o', str(root / 'reference')], check=True)
        expected = json.loads(subprocess.check_output([str(root / 'reference')], input=json.dumps(cases).encode()))
    for case, order in zip(cases, expected, strict=True):
        case['expected'] = order
    result = dict(upstream=PIN, source=SOURCE, source_sha256=hashlib.sha256(source).hexdigest(),
                  comparator_sha256=hashlib.sha256(comparator.encode()).hexdigest(),
                  extraction='Unchanged routeSortsBefore function with id/priority stand-in; Swift stable sort', cases=cases)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(f'Generated {len(cases)} iOS route-order cases')


if __name__ == '__main__':
    main()
