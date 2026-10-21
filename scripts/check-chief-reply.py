#!/usr/bin/env python3
"""Execute the pinned thread projection/loaded-root guard and verify its labels/backend boundary."""
import argparse, hashlib, json, subprocess, xml.etree.ElementTree as ET
from pathlib import Path


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--upstream',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--vectors',type=Path)
    a=p.parse_args();root=Path(__file__).resolve().parents[1];out=a.output.resolve();out.mkdir(parents=True,exist_ok=True)
    manifest=json.loads((root/'third_party/cmux-chief/manifest.json').read_text())
    pins={**manifest['behaviorFiles'],**manifest['replyFiles']}
    def source(path):
        raw=subprocess.check_output(['git','-C',str(a.upstream.resolve()),'show',manifest['revision']+':'+path])
        if hashlib.sha256(raw).hexdigest()!=pins[path]:raise ValueError('Unreviewed reply source: '+path)
        return raw.decode()
    path='Packages/iOS/CmuxConversation/Sources/CmuxConversationUI/ConversationViewController+Reply.swift'
    reply=source(path)
    start=reply.index('        let thread = store.messages.filter',reply.index('private func populate('))
    end=reply.index('        let width = view.bounds.width',start)
    guard='        guard let root = store.message(id: rootID) else { return }'
    if reply.count(guard)!=1:raise ValueError('Loaded-root guard changed')
    generated=out/'SourceProjection.swift'
    generated.write_text('import Foundation\nfunc sourceModels(store: SourceStore, rootID: String) -> [MessageRowModel] {\n'+reply[start:end]+'\nreturn models\n}\nfunc sourceOpen(store: SourceStore, rootID: String, result: inout [MessageRowModel]?) {\n'+guard+'\n_ = root\nresult = sourceModels(store: store, rootID: rootID)\n}\n')
    accessibility=source('Packages/iOS/CmuxConversation/Sources/CmuxConversationUI/ConversationAccessibility.swift')
    if 'self?.enterReplyMode(for: message)' not in accessibility or 'self?.openThread(rootID: parent)' not in accessibility:raise ValueError('Review source reply/quote accessibility admission')
    backend=source('Packages/iOS/CmuxMobileShellUI/Sources/CmuxMobileShellUI/ChiefConversationBackend.swift')
    send=backend[backend.index('    func send(_ draft:'):backend.index('    func react(',backend.index('    func send(_ draft:'))]
    if 'draft.replyToID' in send or 'client.send(' not in send:raise ValueError('Review the Chief reply transport boundary')
    binary=out/'fixture'
    build=subprocess.run(['/usr/bin/swiftc','-swift-version','6',str(generated),str(root/'scripts/chief-reply-fixture.swift'),'-o',str(binary)],capture_output=True,timeout=90)
    (out/'build.log').write_bytes(build.stdout+build.stderr);build.check_returncode()
    vectors=json.loads(subprocess.check_output([str(binary)],timeout=20))
    encoded=(json.dumps(vectors,indent=2,sort_keys=True)+'\n').encode();(out/'vectors.json').write_bytes(encoded)
    if a.vectors:a.vectors.parent.mkdir(parents=True,exist_ok=True);a.vectors.write_bytes(encoded)
    elif encoded!=(root/'app/src/test/resources/parity/chief-reply.json').read_bytes():raise ValueError('Reply vectors differ')
    catalog_path='Packages/iOS/CmuxConversation/Sources/CmuxConversationUI/Resources/Localizable.xcstrings'
    raw=subprocess.check_output(['git','-C',str(a.upstream.resolve()),'show',manifest['revision']+':'+catalog_path])
    if hashlib.sha256(raw).hexdigest()!=manifest['retryStrings']['sha256']:raise ValueError('Reply catalog changed')
    catalog=json.loads(raw)['strings']
    keys={'conversation.composer.reply':'chief_reply_placeholder','conversation.header.close':'chief_reply_close','conversation.ax.replyTranscript':'chief_reply_transcript'}
    core=manifest['replyAccessibilityStrings']
    raw=subprocess.check_output(['git','-C',str(a.upstream.resolve()),'show',manifest['revision']+':'+core['path']])
    if hashlib.sha256(raw).hexdigest()!=core['sha256']:raise ValueError('Reply accessibility catalog changed')
    core_reply=json.loads(raw)['strings'][core['key']]['localizations']
    core_text=source('Packages/iOS/CmuxConversation/Sources/CmuxConversationCore/ConversationAccessibilityText.swift')
    if core['key'] not in core_text:raise ValueError('Reply accessibility key changed')
    question=manifest['replyQuestionStrings']
    raw=subprocess.check_output(['git','-C',str(a.upstream.resolve()),'show',manifest['revision']+':'+question['path']])
    if hashlib.sha256(raw).hexdigest()!=question['sha256']:raise ValueError('Chief question label catalog changed')
    question_labels=json.loads(raw)['strings'][question['key']]['localizations']
    folders={'en':'values','ja':'values-ja','de':'values-de','fr':'values-fr','ar':'values-ar','es':'values-es','zh-Hant':'values-b+zh+Hant','zh-Hans':'values-b+zh+Hans','ko':'values-ko'}
    count=0
    for lang,folder in folders.items():
        expected={name:catalog[key]['localizations'][lang]['stringUnit']['value'] for key,name in keys.items() if lang in catalog[key]['localizations']}
        if lang in core_reply:expected['chief_reply_action']=core_reply[lang]['stringUnit']['value']
        if lang in question_labels:expected['chief_reply_answer_questions']=question_labels[lang]['stringUnit']['value']
        actual={e.attrib['name']:e.text.replace("\\'","'") for e in ET.parse(root/'app/src/main/res'/folder/'chief_reply.xml').getroot()}
        if actual!=expected:raise ValueError('Reply labels differ: '+lang)
        count+=len(actual)
    receipt={'revision':manifest['revision'],'projectionCases':len(vectors['cases']),'projectionSourceSha256':hashlib.sha256(generated.read_bytes()).hexdigest(),'vectorsSha256':hashlib.sha256(encoded).hexdigest(),'sourcePins':manifest['replyFiles'],'localizedValues':count,'accessibilityCatalog':core,'questionCatalog':question,'chiefSendReplyParameter':'none in the actual source backend','scope':'Actual source projection and loaded-root guard with inert row-builder/model inputs, source labels and backend audit. Not UIKit rendering, gestures, animation, full accessibility or real host acceptance.'}
    (out/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt,indent=2))


if __name__=='__main__':main()
