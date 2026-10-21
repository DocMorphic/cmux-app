#!/usr/bin/env python3
"""Execute pinned direct-header/details geometry and verify source labels/type anchors."""
import argparse,hashlib,json,subprocess,xml.etree.ElementTree as ET
from pathlib import Path

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--upstream',type=Path,required=True);p.add_argument('--typography',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True);p.add_argument('--vectors',type=Path)
    a=p.parse_args();root=Path(__file__).resolve().parents[1]
    m=json.loads((root/'third_party/cmux-chief/manifest.json').read_text());out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
    def source(path,sha):
        raw=subprocess.check_output(['git','-C',str(a.upstream.resolve()),'show',m['revision']+':'+path])
        if hashlib.sha256(raw).hexdigest()!=sha:raise ValueError('Unreviewed source: '+path)
        return raw
    files=[]
    for path,sha in m['headerFiles'].items():
        raw=source(path,sha)
        if '/CmuxConversationGeometry/' in path or path.endswith('/ConversationListState.swift'):
            dest=out/Path(path).name;dest.write_bytes(raw);files.append(str(dest))
    controller_path='Packages/iOS/CmuxConversation/Sources/CmuxConversationUI/ConversationViewController.swift'
    controller=source(controller_path,m['behaviorFiles'][controller_path]).decode()
    anchors=[line.strip() for line in controller.splitlines() if line.strip().startswith(('let top = header.frame.maxY +','let bottom = max(0, view.bounds.maxY - composerContainer.frame.minY) +'))]
    if len(anchors)!=2:raise ValueError('Reviewed transcript inset anchors changed')
    inset_wrapper=out/'SourceTranscriptInsets.swift'
    inset_wrapper.write_text('import CoreGraphics\n' + '''struct SourceHeader { let frame: CGRect; let isCompact: Bool }
struct SourceView { let bounds: CGRect }
struct SourceComposer { let frame: CGRect }
func sourceTranscriptInsets(headerBottom: CGFloat, footerHeight: CGFloat, compact: Bool) -> (CGFloat, CGFloat) {
    let header = SourceHeader(frame: CGRect(x: 0, y: 0, width: 402, height: headerBottom), isCompact: compact)
    let view = SourceView(bounds: CGRect(x: 0, y: 0, width: 402, height: 874))
    let composerContainer = SourceComposer(frame: CGRect(x: 0, y: 874-footerHeight, width: 402, height: footerHeight))
    let translationIndicatorReserve: CGFloat = 0
''' + '\n'.join(anchors) + '\nreturn (top, bottom)\n}\n')
    files.append(str(inset_wrapper))
    chief=source('Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/ChiefConversationBackend.swift',m['behaviorFiles']['Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/ChiefConversationBackend.swift']).decode()
    assert 'func updateListState' not in chief and 'ConversationBackgroundBackend' not in chief
    backend=source('Packages/iOS/CmuxConversation/Sources/CmuxConversationCore/ConversationBackend.swift',m['headerFiles']['Packages/iOS/CmuxConversation/Sources/CmuxConversationCore/ConversationBackend.swift']).decode()
    assert 'throw ConversationBackendError(code: -32601, message: "list state unsupported")' in backend
    raw=a.typography.read_bytes()
    if hashlib.sha256(raw).hexdigest()!=m['headerTypography']['sha256']:raise ValueError('Typography source changed')
    apple=json.loads(raw);anchors=[]
    def text(x):
        if isinstance(x,list):return ''.join(map(text,x))
        if isinstance(x,dict):return x.get('text','')+text(x.get('inlineContent',[]))
        return ''
    content=apple['primaryContentSections'][0]['content']
    for index in (67,69):
        for tab in content[index]['tabs']:
            rows={text(r[0]):[text(c) for c in r] for r in tab['content'][1]['rows']}
            anchors.append({key:float(rows[style][2]) for key,style in [('body','Body'),('title','Title 1'),('caption','Caption 2')]})
    assert len(anchors)==12
    binary=out/'fixture'
    b=subprocess.run(['/usr/bin/swiftc','-swift-version','6','-parse-as-library',*files,str(root/'scripts/chief-header-fixture.swift'),'-o',str(binary)],capture_output=True,timeout=90)
    (out/'build.log').write_bytes(b.stdout+b.stderr);b.check_returncode()
    vectors=json.loads(subprocess.check_output([str(binary)],timeout=20));vectors['typography']=anchors
    encoded=(json.dumps(vectors,indent=2,sort_keys=True)+'\n').encode();(out/'vectors.json').write_bytes(encoded)
    if a.vectors:a.vectors.parent.mkdir(parents=True,exist_ok=True);a.vectors.write_bytes(encoded)
    elif encoded!=(root/'app/src/test/resources/parity/chief-header.json').read_bytes():raise ValueError('Vectors differ')
    folders={'en':'values','ja':'values-ja','de':'values-de','fr':'values-fr','ar':'values-ar','es':'values-es','zh-Hant':'values-b+zh+Hant','zh-Hans':'values-b+zh+Hans','ko':'values-ko'}
    catalogs=[(json.loads(source(s['path'],s['sha256']))['strings'],s['keys']) for s in m['headerStrings']];count=0
    for lang,folder in folders.items():
        expected={name:d[key]['localizations'][lang]['stringUnit']['value'].replace('%d','%1$d') for d,keys in catalogs for key,name in keys.items() if lang in d[key]['localizations']}
        actual={e.attrib['name']:e.text.replace("\\'","'") for e in ET.parse(root/'app/src/main/res'/folder/'chief_header.xml').getroot()}
        if expected!=actual:raise ValueError('Header strings differ: '+lang)
        count+=len(actual)
    receipt={'revision':m['revision'],'compactCases':len(vectors['compact']),'detailsCases':len(vectors['details']),'washCases':len(vectors['wash']),'transcriptInsetCases':len(vectors['transcriptInsets']),'unreadCases':len(vectors['unread']),'nameCases':len(vectors['names']),'typeAnchors':12,'strings':count,'vectorsSha256':hashlib.sha256(encoded).hexdigest(),'insetSourceSha256':m['behaviorFiles'][controller_path],'insetWrapperSha256':hashlib.sha256(inset_wrapper.read_bytes()).hexdigest(),'scope':'Actual unmodified portable Swift geometry/defaults/wash, exact source inset expressions with inert frame inputs, guarded UIKit/backend source and primary Apple type anchors; not complete UIKit, OS transition, visual or real-host acceptance'}
    (out/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt,indent=2))

if __name__=='__main__':main()
