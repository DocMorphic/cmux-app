#!/usr/bin/env python3
"""Execute unchanged pinned Swift colony method bodies; this is math evidence, not UIKit."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def prepare(upstream, output):
    manifest = json.loads((ROOT / 'third_party/cmux-cloud/onboarding-manifest.json').read_text())
    path = 'Packages/iOS/CmuxMobileCloudUI/Sources/CmuxMobileCloudUI/CloudOnboardingBackdrop.swift'
    raw = subprocess.check_output(['git', '-C', str(upstream), 'show', manifest['revision'] + ':' + path])
    if hashlib.sha256(raw).hexdigest() != manifest['files'][path]: raise ValueError('Unreviewed colony source')
    source = raw.decode(); bodies = {}; methods = []
    for name in ('index', 'isAlive', 'seed', 'step', 'neighborCount'):
        start = source.index('    private func ' + name + '(')
        opening = source.index('{', start); depth = 1; end = opening + 1
        while depth:
            if source[end] == '{': depth += 1
            elif source[end] == '}': depth -= 1
            end += 1
        method = source[start:end]
        bodies[name] = hashlib.sha256(source[opening + 1:end - 1].encode()).hexdigest()
        # @State has a nonmutating setter in the original View. The isolated
        # value-type host needs mutating declarations; method bodies stay exact.
        if name in ('seed', 'step'): method = method.replace('private func', 'private mutating func', 1)
        methods.append(method)
    cases = []
    def case(name, columns, rows, alive, before=0):
        cases.append(dict(id=name, columns=columns, rows=rows, alive=sorted(r * columns + c for r, c in alive), stepCountBefore=before))
    edge = [(0, 11), (0, 0), (0, 1), (6, 4), (6, 5), (6, 6)]
    blocks = [(2, 2), (2, 3), (3, 2), (3, 3), (8, 8), (8, 9), (9, 8), (9, 9)]
    case('wrap-blinkers-population-six', 12, 12, edge)
    case('stable-eight', 12, 12, blocks)
    case('stable-step-120-retained', 12, 12, blocks, 119)
    case('stable-step-121-reseeds', 12, 12, blocks, 120)
    case('tiny-colony-reseeds', 12, 12, [(6, 4), (6, 5), (6, 6)])
    case('isolated-cell-dies-and-reseeds', 12, 12, [(6, 6)])
    for count in (21, 22):
        alive = [(2 + 4 * (i // 8) + dr, 2 + 4 * (i % 8) + dc) for i in range(count) for dr in range(2) for dc in range(2)]
        case('production-stable-' + str(count * 4), 36, 52, alive)
    wrapper = '''import Foundation
struct SourceColony {
    let columns: Int; let rows: Int
    var cells: [Bool]; var stepCount: Int
''' + '\n'.join(methods) + '''
    mutating func run() -> [String: Any] {
        step()
        return ["stepCountAfter": stepCount, "reseeded": stepCount == 0,
            "aliveAfter": stepCount == 0 ? NSNull() : cells.indices.filter { cells[$0] } as Any]
    }
}
let cases = try JSONSerialization.jsonObject(with: Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))) as! [[String: Any]]
let results = cases.map { fixture -> [String: Any] in
    let columns = fixture["columns"] as! Int, rows = fixture["rows"] as! Int
    let alive = Set(fixture["alive"] as! [Int])
    var colony = SourceColony(columns: columns, rows: rows,
        cells: (0..<(columns * rows)).map { alive.contains($0) }, stepCount: fixture["stepCountBefore"] as! Int)
    var value = fixture; value["expected"] = colony.run(); return value
}
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: results, options: [.sortedKeys]))
'''
    with tempfile.TemporaryDirectory() as folder:
        folder = Path(folder); (folder / 'Source.swift').write_text(wrapper)
        (folder / 'Cases.json').write_text(json.dumps(cases))
        values = json.loads(subprocess.check_output(['swift', str(folder / 'Source.swift'), str(folder / 'Cases.json')]))
    result = dict(schema=1, upstreamRevision=manifest['revision'], sourceFile=path, sourceFileSha256=manifest['files'][path],
        methodBodySha256=bodies, cases=values,
        scope='Executed original Swift colony method bodies with only mutating method declarations added for the isolated value-type host. Random reseed payloads are deliberately not compared. No UIKit, visual, timing or full parity acceptance.')
    output.parent.mkdir(parents=True, exist_ok=True); output.write_text(json.dumps(result, indent=2) + '\n')
    print('Executed', len(values), 'pinned Swift colony cases:', output)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream', required=True, type=Path); parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args(); prepare(args.upstream, args.output)
