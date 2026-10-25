#!/usr/bin/env python3
"""Check effective Home/question text against original built-app observations."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MISSING = '__CMUX_REFERENCE_MISSING__'


def check(upstream, capture):
    manifest = json.loads((ROOT/'third_party/cmux-chief/manifest.json').read_text())
    fixture = json.loads((ROOT/'app/src/androidTest/assets/parity/chief-main-bundle.json').read_text())
    assert fixture['revision'] == manifest['revision']
    reference = manifest['mainBundleResolution']
    assert reference['nativeRun'] == fixture['nativeRun'] and reference['projectCommit'] == fixture['projectCommit']
    assert reference['artifactSha256'] == fixture['artifactSha256']
    assert hashlib.sha256((ROOT/reference['androidReferencePath']).read_bytes()).hexdigest() == reference['sha256']
    keys = dict(manifest['questionStrings']['keys'])
    for item in manifest['homeStrings']['catalogs']:
        keys.update(item['keys'])
    assert keys == fixture['resources'] and len(keys) == 23
    pins = dict(manifest['nativeBundleFiles']); pins.update(manifest['questionPresentationFiles'])
    for item in manifest['homeStrings']['catalogs'] + [manifest['homeStrings']['unreadHelper']]:
        pins[item['path']] = item['sha256']
    sources = {}
    for path, expected in pins.items():
        raw = subprocess.check_output(['git','-C',str(upstream),'show',manifest['revision']+':'+path])
        assert hashlib.sha256(raw).hexdigest() == expected, path
        sources[path] = raw
    pattern = re.compile(r'(?:L10n\.)?string\(\s*"([^"\\]+)"\s*,\s*defaultValue:\s*("(?:\\.|[^"\\])*")\s*\)', re.S)
    defaults = {}
    for path in ['Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/ChiefHomeView.swift',manifest['homeStrings']['unreadHelper']['path']]:
        for key, literal in pattern.findall(sources[path].decode()):
            if key in keys:
                value = json.loads(literal)
                assert key not in defaults or defaults[key] == value
                defaults[key] = value
    assert set(defaults) == set(keys)
    english = {}
    for name in ['chief_home.xml','chief_question.xml']:
        for row in ET.parse(ROOT/'app/src/main/res/values'/name).getroot():
            english[row.attrib['name']] = row.text.replace("\\'", "'").replace('\\"','"').replace('\\\\','\\')
    fallbacks = set(fixture['fallbackKeys']); assert len(fallbacks) == 21
    for key in fallbacks:
        assert english[keys[key]] == defaults[key], key
    helper = (ROOT/'app/src/main/java/io/github/docmorphic/cmuxapp/NativeChiefMainStrings.kt').read_text()
    fallback_block = helper.split('private val chiefMainFallbackStrings = setOf(',1)[1].split('\n)',1)[0]
    assert set(re.findall(r'R\.string\.([a-z_]+)',fallback_block)) == {keys[key] for key in fallbacks}
    provenance = json.loads((capture/'Provenance.json').read_text())
    assert provenance['projectCommit'] == fixture['projectCommit'] and provenance['revision'] == fixture['revision']
    assert set(fixture['labels']) == set(provenance['languages']) and len(fixture['labels']) == 9
    for language, labels in fixture['labels'].items():
        row = json.loads((capture/'observations'/(language+'.json')).read_text())
        assert row['mainValues'] == labels and row['defaults'] == defaults
        assert row['unreadLabels'] == fixture['unreadLabels'][language]
        assert {key for key in keys if row['mainRaw'][key] == MISSING} == fallbacks
        for key in keys:
            assert labels[key] == (defaults[key] if key in fallbacks else row['mainRaw'][key])
    return len(keys)*len(fixture['labels'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream',type=Path,required=True)
    parser.add_argument('--capture',type=Path,required=True)
    args = parser.parse_args()
    print('Checked',check(args.upstream,args.capture),'native main-bundle labels; 21 English fallbacks, two localized keys and 54 native unread queries')
