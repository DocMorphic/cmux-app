#!/usr/bin/env python3
"""Execute pinned Chief emoji classification/count/font functions outside UIKit."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def declaration(source, signature):
    start = source.index(signature)
    opening = source.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


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
    for path, sha in manifest['emojiFiles'].items():
        raw = subprocess.check_output(['git', '-C', str(args.upstream.resolve()), 'show', manifest['revision'] + ':' + path])
        if hashlib.sha256(raw).hexdigest() != sha: raise ValueError('Unreviewed emoji source: ' + path)
        sources[Path(path).name] = raw.decode()
    portable = sources['ConversationTextScan.swift']
    portable += '\nimport CoreGraphics\nenum ConversationRowBuilder {\n'
    portable += declaration(sources['ConversationRows.swift'], 'static func isEmojiOnly') + '\n}\nenum MessageCellLayout {\n'
    portable += declaration(sources['MessageCellLayout.swift'], 'static func emojiCount') + '\n}\nenum ConversationTheme {\n'
    theme = sources['ConversationTheme.swift']
    for name in ('singleEmojiFontSize', 'emojiOnlyFontSize'):
        portable += next(line.strip() for line in theme.splitlines() if line.strip().startswith('static let ' + name + ':')) + '\n'
    portable += declaration(theme, 'static func emojiOnlyFontSize') + '\n}\nenum ComposerReference {\n'
    portable += declaration(sources['ConversationComposerView.swift'], 'static func emojiPointSize') + '\n}\n'
    source = output / 'PinnedEmojiFunctions.swift'; source.write_text(portable)
    binary = output / 'fixture'
    build = subprocess.run(['/usr/bin/swiftc', '-swift-version', '6', '-parse-as-library', str(source),
        str(root / 'scripts/chief-emoji-fixture.swift'), '-o', str(binary)], capture_output=True, timeout=90)
    (output / 'build.log').write_bytes(build.stdout + build.stderr); build.check_returncode()
    vectors = json.loads(subprocess.check_output([str(binary)], timeout=30))
    encoded = (json.dumps(vectors, indent=2, sort_keys=True, ensure_ascii=False) + '\n').encode()
    (output / 'vectors.json').write_bytes(encoded)
    runtime_vectors = root / 'app/src/androidTest/assets/parity/chief-emoji.json'
    if args.vectors:
        args.vectors.write_bytes(encoded)
        runtime_vectors.write_bytes(encoded)
    elif encoded != (root / 'app/src/test/resources/parity/chief-emoji.json').read_bytes():
        raise ValueError('Committed emoji vectors differ from actual Swift execution')
    if encoded != runtime_vectors.read_bytes(): raise ValueError('Android runtime emoji oracle differs from actual Swift execution')
    receipt = {'revision': manifest['revision'], 'sources': manifest['emojiFiles'], 'cases': len(vectors['cases']),
        'vectorsSha256': hashlib.sha256(encoded).hexdigest(),
        'scope': 'Actual source prefilter, grapheme predicate, count and fixed font-size functions; local Swift Unicode data. Not UIKit metrics, platform emoji artwork, composer/send animations or physical acceptance.'}
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps({key: value for key, value in receipt.items() if key != 'sources'}, indent=2))


if __name__ == '__main__': main()
