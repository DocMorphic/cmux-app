#!/usr/bin/env python3
"""Check full-question error labels, source module lookup and test observations."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
FOLDERS={'en':'values','ja':'values-ja','de':'values-de','es':'values-es','fr':'values-fr',
         'ar':'values-ar','ko':'values-ko','zh-Hans':'values-b+zh+Hans','zh-Hant':'values-b+zh+Hant'}


def check(upstream):
    manifest=json.loads((ROOT/'third_party/cmux-chief/manifest.json').read_text());entry=manifest['questionErrorStrings']
    raw=subprocess.check_output(['git','-C',str(upstream),'show',manifest['revision']+':'+entry['path']])
    assert hashlib.sha256(raw).hexdigest()==entry['sha256']
    catalog=json.loads(raw)['strings']
    path='Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/ChiefConversationBackend.swift'
    backend=subprocess.check_output(['git','-C',str(upstream),'show',manifest['revision']+':'+path])
    assert hashlib.sha256(backend).hexdigest()==manifest['questionPresentationFiles'][path]
    model=(ROOT/'app/src/main/java/io/github/docmorphic/cmuxapp/NativeChiefQuestionError.kt').read_text()
    english={key:catalog[key]['localizations']['en']['stringUnit']['value'] for key in entry['keys']}
    for key,value in english.items():
        assert re.search(r'String\(localized: '+re.escape(json.dumps(key))+r', defaultValue: '+re.escape(json.dumps(value))+r', bundle: \.module\)',backend.decode())
        assert json.dumps(key)+', '+json.dumps(value) in model
    labels={};count=0
    for locale,folder in FOLDERS.items():
        rows=ET.parse(ROOT/'app/src/main/res'/folder/'chief_question_errors.xml').getroot()
        actual={r.attrib['name']:r.text.replace("\\'","'").replace('\\"','"').replace('\\\\','\\') for r in rows}
        expected={name:catalog[key]['localizations'][locale]['stringUnit']['value'] for key,name in entry['keys'].items()}
        assert actual==expected and len(rows)==len(expected)
        assert all(r.attrib.get('formatted')=='false' for r in rows)
        labels[locale]={key:catalog[key]['localizations'][locale]['stringUnit']['value'] for key in entry['keys']};count+=len(rows)
    fixture=json.loads((ROOT/'app/src/androidTest/assets/parity/chief-question-errors.json').read_text())
    assert fixture==dict(revision=manifest['revision'],catalogSha256=entry['sha256'],labels=labels)
    return count


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--upstream',type=Path,required=True)
    print('Checked',check(parser.parse_args().upstream),'full-question error labels across nine locales and three source module lookups')
