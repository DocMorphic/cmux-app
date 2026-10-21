#!/usr/bin/env python3
"""Execute pinned composer geometry, field measurement and plain-text send trimming."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--upstream', type=Path, required=True); p.add_argument('--output', type=Path, required=True)
    p.add_argument('--vectors', type=Path); args = p.parse_args()
    root = Path(__file__).resolve().parents[1]
    manifest = json.loads((root / 'third_party/cmux-chief/manifest.json').read_text())
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    sources = []
    def source(path, expected):
        raw = subprocess.check_output(['git', '-C', str(args.upstream.resolve()), 'show', manifest['revision'] + ':' + path])
        if hashlib.sha256(raw).hexdigest() != expected: raise ValueError('Unreviewed composer source: ' + path)
        return raw
    for path, sha in manifest['composerFiles'].items():
        raw = source(path, sha)
        if '/CmuxConversationUI/' not in path:
            dest = output / Path(path).name; dest.write_bytes(raw); sources.append(str(dest))
    binary = output / 'fixture'
    build = subprocess.run(['/usr/bin/swiftc', '-swift-version', '6', '-parse-as-library', *sources,
        str(root / 'scripts/chief-composer-fixture.swift'), '-o', str(binary)],capture_output=True,timeout=90)
    (output / 'build.log').write_bytes(build.stdout + build.stderr); build.check_returncode()
    vectors = json.loads(subprocess.check_output([str(binary)],timeout=20))
    assert all(c <= 0xffff for c in vectors['whitespaceScalars'])
    encoded = (json.dumps(vectors, indent=2,sort_keys=True,ensure_ascii=False)+'\n').encode()
    (output / 'vectors.json').write_bytes(encoded)
    if args.vectors: args.vectors.parent.mkdir(parents=True,exist_ok=True); args.vectors.write_bytes(encoded)
    elif encoded != (root / 'app/src/test/resources/parity/chief-composer.json').read_bytes():
        raise ValueError('Committed composer vectors differ from actual Swift execution')
    folders={'en':'values','ja':'values-ja','de':'values-de','fr':'values-fr','ar':'values-ar','es':'values-es',
        'zh-Hant':'values-b+zh+Hant','zh-Hans':'values-b+zh+Hans','ko':'values-ko'}
    entries = [(json.loads(source(s['path'],s['sha256']))['strings'],s['keys']) for s in manifest['composerStrings']]
    label_count = 0
    for locale,folder in folders.items():
        expected={name:d[key]['localizations'][locale]['stringUnit']['value'] for d,keys in entries for key,name in keys.items() if locale in d[key]['localizations']}
        actual={s.attrib['name']:s.text for s in ET.parse(root / 'app/src/main/res' / folder / 'chief_composer.xml').getroot()}
        if expected != actual: raise ValueError('Composer labels differ from source: '+locale)
        label_count += len(actual)
    receipt={'revision':manifest['revision'],'sources':manifest['composerFiles'],'rowCases':len(vectors['rows']),
        'trimCases':len(vectors['trims']),'whitespaceScalars':vectors['whitespaceScalars'],'measureCases':len(vectors['measures']),
        'stringValues':label_count,'vectorsSha256':hashlib.sha256(encoded).hexdigest(),
        'scope':'Actual Swift text-only row/height/trim functions and local Foundation CharacterSet; not UIKit, keyboard, rich composer or real-host acceptance'}
    (output / 'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n'); print(json.dumps({k:v for k,v in receipt.items() if k not in ['sources','whitespaceScalars']},indent=2))


if __name__ == '__main__': main()
