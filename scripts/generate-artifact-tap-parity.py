#!/usr/bin/env python3
"""Generate terminal tap results from the pinned, unmodified Swift hit tester."""
import gzip, hashlib, json, subprocess, sys, tempfile
from pathlib import Path
PIN = '4c5272e9153eca2033c9f40ac749f0c3a5bcb291'
PREFIX = 'Packages/Shared/CmuxAgentChat/Sources/CmuxAgentChat/Artifacts/'
names = [PREFIX + 'TerminalArtifactPathDetector.swift', PREFIX + 'TerminalArtifactTapHitTester.swift']
sources = [subprocess.check_output(['git', '-C', sys.argv[1], 'show', f'{PIN}:{p}']) for p in names]
examples = [('Inspect data.csv, then continue.', 80), ('The log is build.log.', 80), ('open /fixture/notes.md', 40),
            ('/fixture/file.\nmd), next', 14), ('/fixture/exact.md\n$ next-command', 17), ('plain-output\ncontinuation', 12),
            ('http://example/file email@example.com 3.14', 80), ('漢字 open /fixture/note.txt', 80),
            ('👩‍💻 open /fixture/image.png', 80), ('café /fixture/é.txt', 80)]
for path in ['/fixture/a-very-long-project-name/folder/report.md', './notes.md', '/fixture/file.png']:
    for width in [8, 12, 20, len(path)-1, len(path)]:
        examples.append(('\n'.join(path[i:i+width] for i in range(0, len(path), width)), width))
cases = [dict(text=text, columns=columns, row=row, column=column, ascii=text.isascii())
         for text, columns in examples for row in range(len(text.split('\n'))) for column in range(-1, columns+1)]
driver = r'''
import Foundation
let rows = try JSONSerialization.jsonObject(with: FileHandle.standardInput.readDataToEndOfFile()) as! [[String: Any]]
let results = rows.map { row -> [String: Any] in
    var result = row
    result["expected"] = TerminalArtifactTapHitTester().path(in: row["text"] as! String, col: row["column"] as! Int,
        row: row["row"] as! Int, columns: row["columns"] as! Int) as Any? ?? NSNull()
    return result
}
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: results, options: [.sortedKeys]))
'''
with tempfile.TemporaryDirectory() as directory:
    directory = Path(directory)
    for i, source in enumerate(sources): (directory/f'Source{i}.swift').write_bytes(source)
    (directory/'main.swift').write_text(driver)
    subprocess.run(['xcrun', 'swiftc', *map(str, sorted(directory.glob('*.swift'))), '-o', str(directory/'reference')], check=True)
    results = json.loads(subprocess.check_output([str(directory/'reference')], input=json.dumps(cases).encode()))
root = Path(__file__).resolve().parents[1]
output = dict(upstream=PIN, sources=[dict(path=p, sha256=hashlib.sha256(s).hexdigest()) for p,s in zip(names,sources)], cases=results)
blob = gzip.compress(json.dumps(output, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode(), mtime=0)
target = root/'app/src/test/resources/artifacts'; target.mkdir(parents=True, exist_ok=True)
(target/'ios-taps.json.gz').write_bytes(blob)
target = root/'app/src/androidTest/assets/artifacts'; target.mkdir(parents=True, exist_ok=True)
(target/'ios-taps.json').write_bytes(gzip.decompress(blob))
print(f'Generated {len(results)} tap cases ({sum(not r["ascii"] for r in results)} Unicode)')
