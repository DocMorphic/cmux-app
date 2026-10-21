#!/usr/bin/env python3
"""Execute exact source caption spacing and TimestampCell equations with inert font inputs."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def member(source, declaration):
    start = source.index(declaration)
    first = source.index('{', start)
    depth = 1; end = first + 1
    while depth:
        if source[end] == '{': depth += 1
        elif source[end] == '}': depth -= 1
        end += 1
    return source[start:end]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--upstream', type=Path, required=True); p.add_argument('--output', type=Path, required=True)
    p.add_argument('--vectors', type=Path); a = p.parse_args()
    root = Path(__file__).resolve().parents[1]
    manifest = json.loads((root / 'third_party/cmux-chief/manifest.json').read_text())
    sources = {}
    for path, digest in manifest['captionFiles'].items():
        data = subprocess.check_output(['git', '-C', str(a.upstream.resolve()), 'show', manifest['revision'] + ':' + path])
        if hashlib.sha256(data).hexdigest() != digest: raise ValueError('Unreviewed caption source: ' + path)
        sources[Path(path).name] = data.decode()
    out = a.output.resolve(); out.mkdir(parents=True, exist_ok=True)
    geometry = out / 'ConversationTranscriptMetrics.swift'; geometry.write_text(sources['ConversationTranscriptMetrics.swift'])
    cells = sources['ConversationCells.swift'][sources['ConversationCells.swift'].index('final class TimestampCell:'):]
    layout = sources['MessageCellLayout.swift']
    expression = '        label.frame = CGRect(x: 16, y: 10, width: contentView.bounds.width - 32, height: max(18, ceil(ConversationTheme.timestampFont.lineHeight)))'
    if cells.count(expression) != 1: raise ValueError('Timestamp label frame changed')
    next_gap = '    static let nextRowGap: CGFloat = 4'
    if cells.count(next_gap) != 1: raise ValueError('Timestamp next-row gap changed')
    theme = sources['ConversationTheme.swift']
    if ('static var timestampBoldFont: UIFont { font(11, .medium, style: .caption2) }' not in theme or
        'static var timestampFont: UIFont { font(11, style: .caption2) }' not in theme):
        raise ValueError('Timestamp font style changed')
    generated = out / 'SourceTimestamp.swift'
    generated.write_text('import Foundation\nimport CoreGraphics\n@MainActor enum MessageCellLayout {\n' +
        member(layout, '    static func pixelCeil(') + '\n}\n@MainActor enum SourceTimestamp {\n' +
        '\n'.join(member(cells, key) for key in ['    static var lineTop:', '    static var baselineOffset:', '    static var height:']) +
        '\n' + next_gap + '\n}\n' +
        'struct SourceView { var bounds = CGRect.zero; var frame = CGRect.zero }\n' +
        '@MainActor func sourceLabelFrame(width: CGFloat) -> CGRect {\nlet contentView = SourceView(bounds: CGRect(x: 0, y: 0, width: width, height: 0))\nvar label = SourceView()\n' + expression + '\nreturn label.frame\n}\n')
    binary = out / 'fixture'
    result = subprocess.run(['/usr/bin/swiftc', '-swift-version', '6', str(geometry), str(generated),
        str(root / 'scripts/chief-caption-fixture.swift'), '-o', str(binary)], capture_output=True, timeout=90)
    (out / 'build.log').write_bytes(result.stdout + result.stderr); result.check_returncode()
    vectors = json.loads(subprocess.check_output([str(binary)], timeout=20))
    encoded = (json.dumps(vectors, indent=2, sort_keys=True) + '\n').encode(); (out / 'vectors.json').write_bytes(encoded)
    if a.vectors: a.vectors.parent.mkdir(parents=True, exist_ok=True); a.vectors.write_bytes(encoded)
    elif encoded != (root / 'app/src/test/resources/parity/chief-captions.json').read_bytes(): raise ValueError('Caption vectors differ')
    receipt = {'revision': manifest['revision'], 'sourcePins': manifest['captionFiles'], 'cases': len(vectors['cases']),
        'vectorsSha256': hashlib.sha256(encoded).hexdigest(), 'generatedSourceSha256': hashlib.sha256(generated.read_bytes()).hexdigest(),
        'scope': 'Whole exact TranscriptMetrics plus actual TimestampCell lineTop/height/baseline/label-frame and pixelCeil with inert font/scale inputs. Actual UIFont probe, Android mounting/rendering, locales and physical acceptance remain separate.'}
    (out / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n'); print(json.dumps(receipt, indent=2))


if __name__ == '__main__': main()
