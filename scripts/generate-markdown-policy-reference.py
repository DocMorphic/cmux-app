#!/usr/bin/env python3
"""Execute pinned, unchanged cmux Markdown policies with CLT Swift; never fetch URLs."""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
REFERENCE = ROOT / 'tests/markdown-policy-reference'


def main():
    if len(sys.argv) != 2:
        raise SystemExit('Usage: generate-markdown-policy-reference.py /path/to/cmux')
    manifest = json.loads((REFERENCE / 'References.json').read_text())
    with tempfile.TemporaryDirectory(prefix='cmux-markdown-policies-') as folder:
        folder = Path(folder)
        sources = []
        for path, expected in manifest['files'].items():
            data = subprocess.check_output(['git', '-C', sys.argv[1], 'show', manifest['upstream'] + ':' + path])
            if hashlib.sha256(data).hexdigest() != expected:
                raise ValueError('Source hash mismatch: ' + path)
            target = folder / Path(path).name
            target.write_bytes(data)
            sources.append(str(target))
        main = folder / 'main.swift'
        main.write_bytes((REFERENCE / 'main.swift').read_bytes())
        executable = folder / 'reference'
        subprocess.run(['swiftc', '-swift-version', '6', *sources, str(main), '-o', str(executable)], check=True)
        values = json.loads(subprocess.check_output([str(executable)], timeout=30))
    values['upstream'] = manifest['upstream']
    values['sourceFiles'] = manifest['files']
    values['scope'] = manifest['scope']
    output = ROOT / 'app/src/test/resources/parity/markdown-viewer-policy.json'
    output.write_text(json.dumps(values, indent=2, ensure_ascii=False) + '\n')
    print('Executed five unchanged Swift policy/dependency files; wrote', output.relative_to(ROOT))


if __name__ == '__main__':
    main()
