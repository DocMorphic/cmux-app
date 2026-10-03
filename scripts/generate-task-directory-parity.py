#!/usr/bin/env python3
"""Generate ranking cases with the pinned, unmodified iOS directory suggestion index."""
import gzip
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile

PIN = '4d3385b9d7ac80a9bbdf5c886cc276849b1e4fa0'
REL = 'Packages/iOS/CmuxMobileShellModel/Sources/CmuxMobileShellModel/MobileTaskDirectorySuggestion.swift'
root = Path(__file__).resolve().parents[1]
source = subprocess.check_output(['git', '-C', sys.argv[1], 'show', f'{PIN}:{REL}'])
now = 2_000_000_000
paths = ['~', '/', '/Users/dev', '/Users/dev/project', '/Users/dev/Project', '/Users/dev/projects/cmux',
         '/Volumes/work/project', '/work/project-alpha', '/work/prototype', '/repo/café', '/repo/cafe\u0301',
         '/repo/ＣＭＵＸ', '/repo/cmux', '/work/中', '/repo/👩🏽‍💻', '/space dir/my project', '/short',
         '/a/long/path/project', '/work/presentation', '/tmp/build', '/tmp/builds']
candidates = []
for i, path in enumerate(paths):
    candidates.append(dict(path=path, source=i % 9, context=f'Workspace {i}',
                           time=now-[0, 3600, 86400, 8*86400, 31*86400][i % 5], uses=i*7))
for i, path in enumerate(paths[::3]):
    candidates.append(dict(path=path, source=8-i % 9, context=f'Terminal {i}', time=None, uses=i))
queries = ['', ' ', '/', '~', '/Users/dev/project', '/Users/dev/pro', 'project', 'PROJ', 'dev pro', 'projct',
           'presentaton', 'cmux', 'ＣＭＵＸ', 'cafe', 'café', 'cafe\u0301', '中', '👩🏽‍💻', 'space my',
           'buid', 'build', 'no-such-folder', '/work/project-alpha']
cases = [dict(query=query, limit=limit) for query in queries for limit in [0, 1, 3, 8, 64]]
driver = r'''
import Foundation
let input = try JSONSerialization.jsonObject(with: FileHandle.standardInput.readDataToEndOfFile()) as! [String: Any]
let candidates = (input["candidates"] as! [[String: Any]]).map { row in
    MobileTaskDirectoryCandidate(path: row["path"] as! String,
        source: MobileTaskDirectorySource(rawValue: row["source"] as! Int)!,
        context: row["context"] as? String,
        lastUsedAt: (row["time"] as? Double).map { Date(timeIntervalSince1970: $0) },
        useCount: row["uses"] as! Int)
}
let index = MobileTaskDirectorySuggestionIndex(candidates: candidates, now: Date(timeIntervalSince1970: input["now"] as! Double))
let output = (input["cases"] as! [[String: Any]]).map { row -> [String: Any] in
    var result = row
    result["expected"] = index.suggestions(matching: row["query"] as! String, limit: row["limit"] as! Int).map(\.path)
    return result
}
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: output, options: [.sortedKeys]))
'''
with tempfile.TemporaryDirectory() as folder:
    folder = Path(folder)
    (folder/'Suggestions.swift').write_bytes(source)
    (folder/'main.swift').write_text(driver)
    subprocess.run(['swiftc', str(folder/'Suggestions.swift'), str(folder/'main.swift'), '-o', str(folder/'reference')], check=True)
    expected = json.loads(subprocess.check_output([str(folder/'reference')], input=json.dumps(dict(now=now, candidates=candidates, cases=cases)).encode()))
result = dict(upstream=PIN, source=REL, sha256=hashlib.sha256(source).hexdigest(), now=now, candidates=candidates, cases=expected)
output = root/'app/src/test/resources/tasks/ios-directories.json.gz'
output.parent.mkdir(parents=True, exist_ok=True)
output.write_bytes(gzip.compress(json.dumps(result, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode(), mtime=0))
print(f'Generated {len(expected)} directory-ranking cases from unmodified Swift')
