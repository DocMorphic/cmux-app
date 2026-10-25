#!/usr/bin/env python3
"""Validate original-app resource observations without assuming catalog ownership."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[1]
MISSING='__CMUX_REFERENCE_MISSING__'
HOME='Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/ChiefHomeView.swift'
CLIENT='Packages/iOS/CmuxMobileShell/Sources/CmuxMobileShell/MobileChiefClient.swift'
UNREAD_KEYS={'nil','0','1','2','17','1234','2147483647','2147483648','4294967296','9223372036854775807'}
LOCALES={'en':'en_US','ja':'ja_JP','de':'de_DE','es':'es_ES','fr':'fr_FR',
         'ar':'ar_SA','ko':'ko_KR','zh-Hans':'zh_CN','zh-Hant':'zh_TW'}


def home_search_source(home, client, cases):
    """Keep the complete original predicate and wire model; replace only view state."""
    start=home.index('    private func matches(_ conversation: MobileChiefConversation) -> Bool {')
    method=home[start:home.index('    private func load() async {',start)]
    start=client.index('public struct MobileChiefConversation:')
    model=client[start:client.index('/// One compact transcript message',start)]
    payload=base64.b64encode(cases).decode()
    return ('import Foundation\n'+model+'\nprivate struct ChiefHomeSearchProbe {\n    let query: String\n'+method+'''
    func record(_ row: ChiefHomeSearchCase) -> [String: Any] {
        let conversation = MobileChiefConversation(id: row.id, title: row.title, preview: row.preview)
        return ["id": row.id, "title": row.title, "preview": row.preview, "query": row.query,
                "matches": matches(conversation)]
    }
}
private struct ChiefHomeSearchCase: Decodable {
    let id: String; let title: String; let preview: String; let query: String
}
func sourceHomeSearchObservations() throws -> [[String: Any]] {
    let data = Data(base64Encoded: "'''+payload+'''")!
    let rows = try JSONDecoder().decode([ChiefHomeSearchCase].self, from: data)
    return rows.map { ChiefHomeSearchProbe(query: $0.query).record($0) }
}
''')


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def verify(capture, project_commit, root=ROOT):
    manifest=json.loads((root/'third_party/cmux-chief/manifest.json').read_text())
    provenance=json.loads((capture/'Provenance.json').read_text())
    assert re.fullmatch('[0-9a-f]{40}',project_commit)
    assert provenance['schema']==3
    assert provenance['projectCommit']==project_commit and provenance['revision']==manifest['revision']
    pins=dict(manifest['nativeBundleFiles']);pins.update(manifest['questionPresentationFiles'])
    pins[CLIENT]=manifest['files'][CLIENT]
    for item in manifest['homeStrings']['catalogs']+[manifest['homeStrings']['unreadHelper'],manifest['questionStrings']]:
        pins[item['path']]=item['sha256']
    assert provenance['sourceSha256']==pins
    assert provenance['generatorSha256']==sha(root/'scripts/run-ios-chief-bundles.py')
    for name,expected in provenance['generatedSha256'].items():
        assert Path(name).name==name and sha(capture/name)==expected,name
    assert sha(capture/'Probe.swift')==sha(root/'scripts/native-chief-bundle-probe.swift')
    assert sha(capture/'OriginalL10n.swift')==manifest['homeStrings']['unreadHelper']['sha256']
    assert sha(capture/'OriginalChiefHome.swift')==pins[HOME]
    assert sha(capture/'OriginalMobileChiefClient.swift')==pins[CLIENT]
    cases_raw=(root/'tests/chief-home-search/Cases.json').read_bytes()
    assert (capture/'HomeSearchCases.json').read_bytes()==cases_raw
    cases=json.loads(cases_raw)
    ids=[row['id'] for row in cases]
    assert cases and len(ids)==len(set(ids))
    assert all(set(row)=={'id','title','preview','query'} and all(isinstance(v,str) for v in row.values()) for row in cases)
    expected=home_search_source((capture/'OriginalChiefHome.swift').read_text(),(capture/'OriginalMobileChiefClient.swift').read_text(),cases_raw)
    assert (capture/'OriginalHomeSearch.swift').read_text()==expected
    product=json.loads((capture/'build-product.json').read_text())
    assert product['originalTrackedInputsUnchanged'] is True
    for key in ['executableSha256','infoSha256','probeLibrarySha256']:
        assert re.fullmatch('[0-9a-f]{64}',product[key])
    assert product['resourceSha256'] and all(re.fullmatch('[0-9a-f]{64}',v) for v in product['resourceSha256'].values())
    main_keys=set(provenance['mainKeys']);all_keys=main_keys|set(provenance['moduleKeys'])
    expected_main=set(manifest['questionStrings']['keys'])
    for item in manifest['homeStrings']['catalogs']:expected_main.update(item['keys'])
    assert main_keys==expected_main and len(provenance['mainKeys'])==len(main_keys)
    assert all(key.startswith('mobile.chief.error.') for key in provenance['moduleKeys'])
    rows=[]
    files=list((capture/'observations').glob('*.json'))
    assert set(provenance['languages'])==set(LOCALES) and len(provenance['languages'])==9
    assert {p.stem for p in files}==set(provenance['languages']) and len(files)==9
    for language in provenance['languages']:
        path=capture/'observations'/(language+'.json');row=json.loads(path.read_text())
        assert row['schema']==3 and row['requested']==language and row['bundleID']==product['bundleID']
        assert row['mainBundle'].endswith('.app') and row['preferredLanguages']
        assert row['locale']==LOCALES[language],(language,row['locale'])
        assert all(row[key]==product[key] for key in ['executableSha256','infoSha256','resourceSha256'])
        assert row['preferredLanguages'][0].split('-')[0]==language.split('-')[0]
        if language in ['zh-Hans','zh-Hant']:assert language.split('-')[1] in row['preferredLanguages'][0].split('-')
        for name in ['mainRaw','moduleRaw']:
            assert set(row[name])==all_keys and all(isinstance(v,str) for v in row[name].values())
        assert set(row['mainValues'])==set(row['defaults'])==main_keys
        for key in main_keys:
            raw=row['mainRaw'][key]
            assert row['mainValues'][key]==(row['defaults'][key] if raw==MISSING else raw),(language,key)
        unread=row['unreadLabels']
        assert set(unread)==UNREAD_KEYS and all(isinstance(value,str) and value for value in unread.values())
        assert unread['0']==unread['nil'] and unread['1']==row['mainValues']['mobile.workspace.unreadCountFormat.one']
        assert len({unread[key] for key in ['2','17','1234']})==3
        searches=row['homeSearch']
        assert len(searches)==len(cases)
        for case,observation in zip(cases,searches):
            assert set(observation)==set(case)|{'matches'} and type(observation['matches']) is bool
            assert all(observation[key]==value for key,value in case.items())
        selected=row['selectedModule']
        if selected is not None:
            assert selected in row['moduleCandidates'] and selected.startswith(row['mainBundle']+'/') and selected.endswith('.bundle')
        else:
            assert all(v==MISSING for v in row['moduleRaw'].values())
        rows.append(dict(language=language,mainPreferred=row['mainPreferred'],modulePreferred=row['modulePreferred'],
            mainResolved=sorted(k for k in main_keys if row['mainRaw'][k]!=MISSING),
            mainFallback=sorted(k for k in main_keys if row['mainRaw'][k]==MISSING),
            moduleResolved=sorted(k for k in all_keys if row['moduleRaw'][k]!=MISSING),unreadLabels=unread,
            homeSearch=searches,sha256=sha(path)))
    receipt=dict(schema=3,projectCommit=project_commit,revision=manifest['revision'],languages=9,
                 mainQueries=len(main_keys)*9,moduleQueries=len(all_keys)*9,unreadQueries=len(UNREAD_KEYS)*9,
                 homeSearchQueries=len(cases)*9,rows=rows,
                 provenanceSha256=sha(capture/'Provenance.json'),productSha256=sha(capture/'build-product.json'),
                 scope='Actual compiled original app Bundle.main with unmodified source L10n, explicit packaged-module lookup and the complete original Home search predicate/wire model in a sidecar query-state wrapper. No native Home/question view, authentication, backend static-property or physical parity claim.')
    return receipt


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--capture',type=Path,required=True)
    parser.add_argument('--project-commit',required=True)
    args=parser.parse_args();receipt=verify(args.capture.resolve(),args.project_commit)
    (args.capture/'verification.json').write_text(json.dumps(receipt,indent=2)+'\n')
    print(json.dumps({k:receipt[k] for k in ['revision','languages','mainQueries','moduleQueries','unreadQueries','homeSearchQueries']},indent=2))


if __name__=='__main__':main()
