#!/usr/bin/env python3
"""Execute whole pinned original Swift input declarations, with an authored JSON adapter."""
import argparse
import hashlib
import json
from pathlib import Path
import random
import string
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
REVISION = 'a5911a1f92f3eeb11c4d7f3e2ef89204718c5009'
PREFIX = 'Packages/iOS/CmuxMobileWorkspace/Sources/CmuxMobileWorkspace/'
SOURCES = {PREFIX+'SignInCodeInputPolicy.swift': '7ac93e945b430aa7409e575a1f7e6169de5ba35cd0a41191cc7a54cf3dab148f',
    PREFIX+'SignInCodeInputChangeAction.swift': '0591b382aa7bac4b3f57618389e85c8f19f990cad3eb12e203bef04287e281bc'}
ADAPTER = '''import Foundation
let input = try JSONSerialization.jsonObject(with: FileHandle.standardInput.readDataToEndOfFile()) as! [String]
func name(_ action: SignInCodeInputChangeAction) -> String {
    switch action { case .assign: return "assign"; case .verify: return "verify"; case .none: return "none" }
}
let output = input.map { value -> [String: String] in
    let normalized = SignInCodeInputPolicy.normalizedCode(value)
    return ["input": value, "normalized": normalized,
        "initialAction": name(SignInCodeInputPolicy.action(for: value)),
        "stableAction": name(SignInCodeInputPolicy.action(for: normalized))]
}
FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: output, options: [.sortedKeys]))
'''


def inputs():
    values = ['', 'abc12', 'abc123', 'ABC123', 'ab c-123', '123456\n', '1234567',
        'ＡＢＣ１２３', 'äéİıßKабв１２３١٢٣abc123', '🙂a\u0301b\nc123', '\0A\tbC123']
    values += [chr(scalar)+'_a1b2c3' for scalar in range(256)]
    randomizer = random.Random(0xC0DE)
    alphabet = string.ascii_letters+string.digits+string.punctuation+' \n\t🙂éİıßK漢字\u0301١２'
    values += [''.join(randomizer.choice(alphabet) for _ in range(randomizer.randrange(41))) for _ in range(256)]
    return list(dict.fromkeys(values))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream', required=True, type=Path)
    parser.add_argument('--output', type=Path, default=ROOT/'tests/fixtures/signin-code-input-reference.json')
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='cmux-signin-original-') as temporary:
        folder = Path(temporary); files = []
        for path, expected in SOURCES.items():
            raw = subprocess.check_output(['git', '-C', str(args.upstream), 'show', REVISION+':'+path])
            if hashlib.sha256(raw).hexdigest() != expected: raise ValueError('Changed original declaration: '+path)
            copied = folder/Path(path).name; copied.write_bytes(raw); files.append(str(copied))
        (folder/'main.swift').write_text(ADAPTER)
        subprocess.run(['swiftc', *files, str(folder/'main.swift'), '-o', str(folder/'original')], check=True)
        observed = json.loads(subprocess.check_output([str(folder/'original')], input=json.dumps(inputs()).encode()))
    result = dict(schema=1, upstream=REVISION, sourceFiles=SOURCES,
        adapterSha256=hashlib.sha256(ADAPTER.encode()).hexdigest(),
        generatorSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        compiler=subprocess.check_output(['swiftc', '--version'], text=True).strip(), cases=observed,
        scope='Whole unchanged original Swift normalization/completion declarations executed with authored JSON input/output. Not SwiftUI event ordering, focus/busy behavior, UIKit, backend, physical device or full parity acceptance.')
    if args.check:
        saved = json.loads(args.output.read_text())
        for key in ('schema', 'upstream', 'sourceFiles', 'adapterSha256', 'cases'):
            if saved[key] != result[key]: raise ValueError('Original input reference differs: '+key)
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2, ensure_ascii=True)+'\n')
    print(str(len(observed))+' actual original Swift code-input cases '+('verified' if args.check else 'captured'))


if __name__ == '__main__': main()
