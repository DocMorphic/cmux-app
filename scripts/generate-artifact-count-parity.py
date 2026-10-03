#!/usr/bin/env python3
"""Run unmodified pinned Swift count and path policies to generate Android parity cases."""
import gzip, hashlib, json, random, subprocess, sys, tempfile
from pathlib import Path
PIN = '4c5272e9153eca2033c9f40ac749f0c3a5bcb291'
SOURCES = ['Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/TerminalArtifactChipCountState.swift',
           'Packages/Shared/CmuxAgentChat/Sources/CmuxAgentChat/Artifacts/TerminalArtifactPathDetector.swift']
root = Path(__file__).resolve().parents[1]
sources = [subprocess.check_output(['git', '-C', sys.argv[1], 'show', f'{PIN}:{name}']) for name in SOURCES]
rng = random.Random(58466)
scenarios = []
for _ in range(80):
    steps = []; generation = 0
    for _ in range(80):
        op = rng.randrange(12)
        generation += rng.choice([0, 0, 1, 1, 2, 120])
        if op == 0:
            steps.append(dict(op='reset'))
        elif op < 8:
            steps.append(dict(op='trigger', count=rng.randrange(8), generation=generation, supported=rng.randrange(5) != 0))
        else:
            steps.append(dict(op='complete', generation=generation, count=rng.randrange(8), gallery=rng.choice([None, None, 0, 3, 17]),
                              sessionTotal=rng.choice([None, 0, 5, 29]), session=rng.choice([None, 'a', 'b']), succeeded=rng.randrange(4) != 0))
    scenarios.append(steps)
# Use a nonexistent prefix for parent-path normalization: /tmp is a symlink on the generator Mac.
paths = ['/', '/.', '/a/..', '/a/../b', '/tmp/file.png', './notes.md', '../a/b', 'src/main.swift:42:8:error',
         'https://site/a', 'http://site/b', 'ftp://site/file', 'README.md', '~/work/file', '[image](/tmp/a.png)',
         'file:///tmp/hello%20world.png', '"/tmp/file.png",', '/tmp/path..', '/tmp/path.', '(/tmp/folder)',
         '/tmp/a\\b', '/tmp/👩‍💻.png', '/tmp/中文.txt', '/tmp/f /tmp/f ./f',
         '\x1b[31m/tmp/red.png\x1b[0m', '\x1b]8;;https://hidden/path\x07/tmp/link.png\x1b]8;;\x07',
         '\x1bP/tmp/hidden\x07still-hidden\x1b\\ /tmp/visible', '\x9d/tmp/hidden\x9c/tmp/visible',
         '/tmp/a:123:matching', '/tmp/a:123x', '/tmp/a:1', '/tmp/a:abc:22', '/cmux-parity-root/../', '/tmp/a!']
driver = r'''
import Foundation
let input = try JSONSerialization.jsonObject(with: FileHandle.standardInput.readDataToEndOfFile()) as! [String: Any]
func requestJSON(_ r: TerminalArtifactChipCountState.Request?) -> Any {
    guard let r else { return NSNull() }
    return ["state": r.stateGeneration, "generation": r.surfaceGeneration, "count": r.localCount] as [String: Any]
}
func reportJSON(_ r: TerminalArtifactChipCountState.Report?) -> Any {
    guard let r else { return NSNull() }
    return ["count": r.count, "generation": r.surfaceGeneration] as [String: Any]
}
var output: [[[String: Any]]] = []
for steps in input["scenarios"] as! [[[String: Any]]] {
    var state = TerminalArtifactChipCountState()
    var active: TerminalArtifactChipCountState.Request?
    var results: [[String: Any]] = []
    for step in steps {
        let op = step["op"] as! String
        var result = step
        if op == "reset" { state.reset(); result["expected"] = ["kind": "reset"] }
        else if op == "trigger" {
            let action = state.trigger(localCount: step["count"] as! Int, surfaceGeneration: UInt64(step["generation"] as! Int), supportsSessionCount: step["supported"] as! Bool)
            var report: TerminalArtifactChipCountState.Report?; var request: TerminalArtifactChipCountState.Request?; var authoritative = false
            switch action {
            case .none: break
            case .report(let r): report = r; authoritative = true
            case .provisionalReport(let r): report = r
            case .request(let r): request = r
            case .reportAndRequest(let r, let q): report = r; request = q
            }
            if let request { active = request }
            result["expected"] = ["kind": "trigger", "report": reportJSON(report), "authoritative": authoritative, "request": requestJSON(request)]
        } else {
            let completion = state.complete(active ?? .init(stateGeneration: UInt64.max, surfaceGeneration: 0, localCount: 0),
                galleryRowTotal: step["gallery"] as? Int, sessionTotal: step["sessionTotal"] as? Int, sessionID: step["session"] as? String,
                scanSucceeded: step["succeeded"] as! Bool, currentSurfaceGeneration: UInt64(step["generation"] as! Int), freshestLocalCount: step["count"] as! Int)
            var outcome = ""; var report: TerminalArtifactChipCountState.Report?
            switch completion.outcome {
            case .reported(let r): outcome = "REPORTED"; report = r
            case .stale: outcome = "STALE"
            case .droppedForSurfaceGenerationMismatch: outcome = "DROPPED"
            }
            active = completion.nextRequest
            result["expected"] = ["kind": outcome, "report": reportJSON(report), "next": requestJSON(completion.nextRequest)]
        }
        results.append(result)
    }
    output.append(results)
}
let pathResults = (input["paths"] as! [String]).map { ["text": $0, "expected": TerminalArtifactPathDetector().paths(in: $0)] as [String: Any] }
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: ["scenarios": output, "paths": pathResults], options: [.sortedKeys]))
'''
with tempfile.TemporaryDirectory() as folder:
    folder = Path(folder)
    for i, source in enumerate(sources): (folder / f'Source{i}.swift').write_bytes(source)
    (folder / 'main.swift').write_text(driver)
    subprocess.run(['xcrun', 'swiftc', *map(str, sorted(folder.glob('*.swift'))), '-o', str(folder / 'reference')], check=True)
    output = json.loads(subprocess.check_output([str(folder / 'reference')], input=json.dumps(dict(scenarios=scenarios, paths=paths)).encode()))
output['upstream'] = PIN
output['sources'] = [dict(path=name, sha256=hashlib.sha256(source).hexdigest()) for name, source in zip(SOURCES, sources)]
path = root / 'app/src/test/resources/artifacts/ios-count-paths.json.gz'
path.parent.mkdir(parents=True, exist_ok=True)
path.write_bytes(gzip.compress(json.dumps(output, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode(), mtime=0))
print(f'Generated {sum(map(len, scenarios))} count transitions and {len(paths)} path cases from unmodified Swift')
