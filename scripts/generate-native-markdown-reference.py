#!/usr/bin/env python3
"""Execute pinned native Markdown blocks and original Foundation inline method without UI/network."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
REFERENCE = ROOT / 'tests/native-markdown-reference'


def main():
    if len(sys.argv) != 2:
        raise SystemExit('Usage: generate-native-markdown-reference.py /path/to/cmux')
    manifest = json.loads((REFERENCE / 'References.json').read_text())

    def source(path, expected):
        data = subprocess.check_output(['git', '-C', sys.argv[1], 'show', manifest['upstream'] + ':' + path])
        if hashlib.sha256(data).hexdigest() != expected:
            raise ValueError('Source hash mismatch: ' + path)
        return data

    with tempfile.TemporaryDirectory(prefix='cmux-native-markdown-') as folder:
        folder = Path(folder); sources = []
        for path, expected in manifest['files'].items():
            target = folder / Path(path).name
            target.write_bytes(source(path, expected)); sources.append(str(target))
        text = source(manifest['inlineSource']['path'], manifest['inlineSource']['sha256']).decode()
        method = text[text.index('    private func renderedInline('):text.rindex('\n}')]
        main = folder / 'main.swift'
        main.write_text((REFERENCE / 'main.swift').read_text() + '\n' + method + '\n')
        executable = folder / 'reference'
        subprocess.run(['swiftc', '-swift-version', '6', *sources, str(main), '-o', str(executable)], check=True)
        result = json.loads(subprocess.check_output([str(executable), str(REFERENCE / 'Cases.json')], timeout=30))
    result['upstream'] = manifest['upstream']
    result['sourceFiles'] = manifest['files']
    result['inlineSource'] = manifest['inlineSource']
    result['inlineMethodSha256'] = hashlib.sha256(method.encode()).hexdigest()
    result['scope'] = manifest['scope']
    output = ROOT / 'app/src/test/resources/parity/native-markdown.json'
    output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + '\n')
    print('Native reference:', len(result['documents']), 'documents,', len(result['inline']), 'inline cases')


if __name__ == '__main__':
    main()
