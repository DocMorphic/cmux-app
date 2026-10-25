#!/usr/bin/env python3
"""Validate original-app resource observations without assuming catalog ownership."""
import argparse
import hashlib
import json
from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[1]
MISSING='__CMUX_REFERENCE_MISSING__'


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def verify(capture, project_commit, root=ROOT):
    manifest=json.loads((root/'third_party/cmux-chief/manifest.json').read_text())
    provenance=json.loads((capture/'Provenance.json').read_text())
    assert re.fullmatch('[0-9a-f]{40}',project_commit)
    assert provenance['projectCommit']==project_commit and provenance['revision']==manifest['revision']
    pins=dict(manifest['nativeBundleFiles']);pins.update(manifest['questionPresentationFiles'])
    for item in manifest['homeStrings']['catalogs']+[manifest['homeStrings']['unreadHelper'],manifest['questionStrings']]:
        pins[item['path']]=item['sha256']
    assert provenance['sourceSha256']==pins
    assert provenance['generatorSha256']==sha(root/'scripts/run-ios-chief-bundles.py')
    for name,expected in provenance['generatedSha256'].items():
        assert Path(name).name==name and sha(capture/name)==expected,name
    assert sha(capture/'Probe.swift')==sha(root/'scripts/native-chief-bundle-probe.swift')
    assert sha(capture/'OriginalL10n.swift')==manifest['homeStrings']['unreadHelper']['sha256']
    product=json.loads((capture/'build-product.json').read_text())
    assert product['originalTrackedInputsUnchanged'] is True
    for key in ['executableSha256','infoSha256','probeLibrarySha256']:
        assert re.fullmatch('[0-9a-f]{64}',product[key])
    assert product['resourceSha256'] and all(re.fullmatch('[0-9a-f]{64}',v) for v in product['resourceSha256'].values())
    main_keys=set(provenance['mainKeys']);all_keys=main_keys|set(provenance['moduleKeys'])
    rows=[]
    files=list((capture/'observations').glob('*.json'))
    assert {p.stem for p in files}==set(provenance['languages']) and len(files)==9
    for language in provenance['languages']:
        path=capture/'observations'/(language+'.json');row=json.loads(path.read_text())
        assert row['schema']==1 and row['requested']==language and row['bundleID']==product['bundleID']
        assert row['mainBundle'].endswith('.app') and row['preferredLanguages']
        assert all(row[key]==product[key] for key in ['executableSha256','infoSha256','resourceSha256'])
        assert row['preferredLanguages'][0].split('-')[0]==language.split('-')[0]
        if language in ['zh-Hans','zh-Hant']:assert language.split('-')[1] in row['preferredLanguages'][0].split('-')
        for name in ['mainRaw','moduleRaw']:
            assert set(row[name])==all_keys and all(isinstance(v,str) for v in row[name].values())
        assert set(row['mainValues'])==set(row['defaults'])==main_keys
        for key in main_keys:
            raw=row['mainRaw'][key]
            assert row['mainValues'][key]==(row['defaults'][key] if raw==MISSING else raw),(language,key)
        selected=row['selectedModule']
        if selected is not None:
            assert selected in row['moduleCandidates'] and selected.startswith(row['mainBundle']+'/') and selected.endswith('.bundle')
        else:
            assert all(v==MISSING for v in row['moduleRaw'].values())
        rows.append(dict(language=language,mainPreferred=row['mainPreferred'],modulePreferred=row['modulePreferred'],
            mainResolved=sorted(k for k in main_keys if row['mainRaw'][k]!=MISSING),
            mainFallback=sorted(k for k in main_keys if row['mainRaw'][k]==MISSING),
            moduleResolved=sorted(k for k in all_keys if row['moduleRaw'][k]!=MISSING),sha256=sha(path)))
    receipt=dict(schema=1,projectCommit=project_commit,revision=manifest['revision'],languages=9,
                 mainQueries=len(main_keys)*9,moduleQueries=len(all_keys)*9,rows=rows,
                 provenanceSha256=sha(capture/'Provenance.json'),productSha256=sha(capture/'build-product.json'),
                 scope='Actual compiled original app Bundle.main with unmodified source L10n and explicit packaged-module lookup. No native Home/question view, authentication, backend static-property or physical parity claim.')
    return receipt


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--capture',type=Path,required=True)
    parser.add_argument('--project-commit',required=True)
    args=parser.parse_args();receipt=verify(args.capture.resolve(),args.project_commit)
    (args.capture/'verification.json').write_text(json.dumps(receipt,indent=2)+'\n')
    print(json.dumps({k:receipt[k] for k in ['revision','languages','mainQueries','moduleQueries']},indent=2))


if __name__=='__main__':main()
