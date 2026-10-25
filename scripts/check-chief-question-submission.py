#!/usr/bin/env python3
"""Execute the pinned complete-question method with controlled state/client seams.

This does not execute the whole backend, SwiftUI presentation or a real Mac RPC.
"""
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
    path = 'Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/ChiefConversationBackend.swift'
    raw = subprocess.check_output(['git', '-C', str(args.upstream.resolve()), 'show', manifest['revision'] + ':' + path])
    assert hashlib.sha256(raw).hexdigest() == manifest['questionPresentationFiles'][path]
    source = raw.decode()
    start = source.index('    func answerQuestion(messageID:')
    end = source.index('    func uploadImage(', start)
    method = source[start:end]
    assert 'decidingMessageIDs' not in method
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    generated = out / 'OriginalQuestionSubmission.swift'
    generated.write_text('import Foundation\nextension ChiefConversationBackend {\n' + method + '}\n')
    fixture = root / 'scripts/chief-question-submission-fixture.swift'
    binary = out / 'fixture'
    build = subprocess.run(['/usr/bin/swiftc', '-swift-version', '6', '-parse-as-library', str(generated), str(fixture), '-o', str(binary)], capture_output=True, timeout=90)
    (out / 'build.log').write_bytes(build.stdout + build.stderr)
    build.check_returncode()
    run = subprocess.run([str(binary)], capture_output=True, timeout=30)
    (out / 'run.stderr').write_bytes(run.stderr)
    run.check_returncode()
    vectors = json.loads(run.stdout)
    encoded = (json.dumps(vectors, indent=2, sort_keys=True) + '\n').encode()
    (out / 'vectors.json').write_bytes(encoded)
    target = root / 'app/src/test/resources/parity/chief-question-submission.json'
    if args.vectors:
        args.vectors.parent.mkdir(parents=True, exist_ok=True)
        args.vectors.write_bytes(encoded)
    else:
        assert target.read_bytes() == encoded, 'Question submission observations differ'
    receipt = dict(revision=manifest['revision'],sourceFile=path,sourceSha256=hashlib.sha256(raw).hexdigest(),
                   generatedSha256=hashlib.sha256(generated.read_bytes()).hexdigest(),fixtureSha256=hashlib.sha256(fixture.read_bytes()).hexdigest(),
                   vectorsSha256=hashlib.sha256(encoded).hexdigest(),cases=len(vectors['cases']),
                   scope='Unmodified answerQuestion method on host Swift/Foundation with controlled question lookup, retained-row publication and async client seams. Not the whole backend, native question UI, transport or physical acceptance.')
    (out / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps(receipt, indent=2))


if __name__ == '__main__':
    main()
