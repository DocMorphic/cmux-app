#!/usr/bin/env python3
"""Execute immutable CoreGraphics paths/metrics; compare committed Android reference vectors."""
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
    scenes = (root / 'tests/ios-chief-reference/Scenes.json').read_bytes()
    if scenes != (root / 'app/src/androidTest/assets/parity/chief-conversation-scenes.json').read_bytes():
        raise ValueError('Android and UIKit reference scene inputs differ')
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    sources = []
    for path, expected in manifest['bubbleFiles'].items():
        raw = subprocess.check_output(['git', '-C', str(args.upstream.resolve()), 'show', manifest['revision'] + ':' + path])
        if hashlib.sha256(raw).hexdigest() != expected: raise ValueError('Unreviewed bubble source: ' + path)
        if '/CmuxConversationGeometry/' in path:
            destination = output / Path(path).name; destination.write_bytes(raw); sources.append(str(destination))
        if path.endswith('/ConversationTheme.swift'):
            theme = raw.decode()
            start = theme.index('    static func maxBubbleWidth(')
            method = theme[start:theme.index('\n    }', start) + len('\n    }')]
            constant = next(line for line in theme.splitlines() if 'static let accessibilityBubbleInset:' in line)
            destination = output / 'ThemeWidth.swift'
            destination.write_text('import CoreGraphics\nenum ReferenceBubbleTheme {\n'
                '    nonisolated(unsafe) static var isAccessibilitySize = true\n' + constant + '\n' + method + '\n}\n')
            sources.append(str(destination))
    binary = output / 'fixture'
    build = subprocess.run(['/usr/bin/swiftc', '-swift-version', '6', '-parse-as-library', *sources,
                            str(root / 'scripts/chief-bubble-fixture.swift'), '-o', str(binary)], capture_output=True, timeout=60)
    (output / 'build.log').write_bytes(build.stdout + build.stderr); build.check_returncode()
    vectors = json.loads(subprocess.check_output([str(binary)], timeout=10))
    encoded = (json.dumps(vectors, indent=2, sort_keys=True) + '\n').encode()
    (output / 'vectors.json').write_bytes(encoded)
    if args.vectors:
        args.vectors.parent.mkdir(parents=True, exist_ok=True); args.vectors.write_bytes(encoded)
    elif (root / 'app/src/test/resources/parity/chief-bubble.json').read_bytes() != encoded:
        raise ValueError('Committed bubble vectors differ from actual Swift execution')
    receipt = {'revision': manifest['revision'], 'sources': manifest['bubbleFiles'], 'paths': len(vectors['paths']),
               'widths': len(vectors['widths']), 'accessibilityWidths': len(vectors['accessibilityWidths']), 'rounding': len(vectors['rounding']), 'drops': len(vectors['drops']), 'spaces': len(vectors['spaces']),
               'vectorsSha256': hashlib.sha256(encoded).hexdigest(),
               'scenesSha256': hashlib.sha256(scenes).hexdigest(),
               'scope': 'Actual shared CoreGraphics paths and metrics; UIKit tailless wrapper uses circular corners; no font, rendering, Android or real-host acceptance'}
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(f"Pinned bubble geometry: {receipt['paths']} paths, {receipt['widths']} regular/{receipt['accessibilityWidths']} AX widths, {receipt['rounding']} pixel, {receipt['drops']} drop and {receipt['spaces']} space vectors")


if __name__ == '__main__': main()
