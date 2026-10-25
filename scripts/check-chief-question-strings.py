#!/usr/bin/env python3
"""Check source question labels; this does not prove native Bundle.main resolution."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
FOLDERS={"en":"values","ja":"values-ja","de":"values-de","es":"values-es","fr":"values-fr",
         "ar":"values-ar","ko":"values-ko","zh-Hans":"values-b+zh+Hans","zh-Hant":"values-b+zh+Hant"}
def check(upstream,root=ROOT):
    manifest=json.loads((root/'third_party/cmux-chief/manifest.json').read_text());binding=manifest['questionStrings']
    raw=subprocess.check_output(['git','-C',str(upstream),'show',manifest['revision']+':'+binding['path']])
    assert hashlib.sha256(raw).hexdigest()==binding['sha256']
    source=json.loads(raw)['strings'];count=0
    for locale,folder in FOLDERS.items():
        rows=ET.parse(root/'app/src/main/res'/folder/'chief_question.xml').getroot()
        actual={r.attrib['name']:r.text.replace("\\'", "'").replace('\\"','"').replace('\\\\','\\') for r in rows}
        expected={name:source[key]['localizations'][locale]['stringUnit']['value'] for key,name in binding['keys'].items()}
        assert actual==expected and len(rows)==len(expected),folder
        assert all(r.attrib.get('formatted')=='false' for r in rows)
        count+=len(actual)
    return count
if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--upstream',type=Path,required=True)
    print('Checked',check(parser.parse_args().upstream),'question labels in nine locales')
