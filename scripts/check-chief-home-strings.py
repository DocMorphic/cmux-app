#!/usr/bin/env python3
"""Check Home catalog copies and the source unread helper at the immutable pin.

This checks catalog text, not the original app's Bundle.main lookup at runtime.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
FOLDERS = {"en":"values", "ja":"values-ja", "de":"values-de", "es":"values-es", "fr":"values-fr",
           "ar":"values-ar", "ko":"values-ko", "zh-Hans":"values-b+zh+Hans", "zh-Hant":"values-b+zh+Hant"}

def check(upstream, root=ROOT):
    manifest = json.loads((root/'third_party/cmux-chief/manifest.json').read_text())
    binding = manifest['homeStrings'];expected = {locale:{} for locale in FOLDERS}
    def original(entry):
        raw = subprocess.check_output(['git','-C',str(upstream),'show',manifest['revision']+':'+entry['path']])
        assert hashlib.sha256(raw).hexdigest() == entry['sha256'], entry['path']
        return raw
    original(binding['unreadHelper'])
    for catalog in binding['catalogs']:
        source = json.loads(original(catalog))['strings']
        for key,name in catalog['keys'].items():
            for locale in FOLDERS:
                value = source[key]['localizations'][locale]['stringUnit']['value']
                expected[locale][name] = value.replace('%d','%1$d') if name.endswith('_other') else value
    count = 0
    for locale,folder in FOLDERS.items():
        rows = ET.parse(root/'app/src/main/res'/folder/'chief_home.xml').getroot()
        actual = {row.attrib['name']:row.text.replace("\\'", "'").replace('\\"','"').replace('\\\\','\\') for row in rows}
        assert actual == expected[locale] and len(rows) == len(actual), folder
        assert all(row.attrib.get('formatted','true') == ('true' if row.attrib['name'].endswith('_other') else 'false') for row in rows)
        count += len(actual)
    return count

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__);parser.add_argument('--upstream',type=Path,required=True)
    print('Checked',check(parser.parse_args().upstream),'Home labels in nine locales against two source catalogs and the unread helper')
