#!/usr/bin/env python3
"""Verify actual pinned UIKit action attachments; synthetic unit fixtures are not UI evidence."""
import argparse
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import shutil
import struct

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('chief_reference', ROOT / 'scripts/verify-ios-chief-reference.py')
reference = importlib.util.module_from_spec(spec); spec.loader.exec_module(reference)

def digest(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def require(condition, message):
    if not condition: raise ValueError(message)
def rect(value):
    return isinstance(value, list) and len(value) == 4 and all(type(x) in (float, int) and math.isfinite(x) for x in value) and min(value[2:]) > 0

def verify(exported, output, commit):
    require(len(commit) == 40 and all(x in '0123456789abcdef' for x in commit), 'Expected exact project commit')
    manifest = json.loads((ROOT / 'third_party/cmux-chief/manifest.json').read_text())
    template = ROOT / 'tests/ios-chief-reference'
    fixtures = json.loads((template / 'Actions.json').read_text()); require(fixtures['schema'] == 1, 'Action fixture schema')
    fixtures = {x['id']: x for x in fixtures['scenes']}
    bases = {x['id']: x for x in json.loads((template/'Scenes.json').read_text())['scenes']}
    images, metadata = {}, {}
    for p in exported.rglob('*'):
        if not p.is_file() or p.stat().st_size > 32 * 1024 * 1024: continue
        data = p.read_bytes()
        if data.startswith(b'\x89PNG\r\n\x1a\n'): images[hashlib.sha256(data).hexdigest()] = p
        elif data.lstrip().startswith(b'{'):
            try: value = json.loads(data)
            except (ValueError, UnicodeError): continue
            if isinstance(value, dict) and 'actionScene' in value:
                name = value['actionScene']; require(name not in metadata, 'Duplicate action metadata')
                metadata[name] = (p, value)
    require(set(metadata) == set(fixtures), 'Missing/unexpected action scenes')
    paired = []
    mandatory = ['arrowshape.turn.up.left', 'doc.on.doc', 'selection.pin.in.out', 'ellipsis.circle']
    for name, fixture in fixtures.items():
        p, v = metadata[name]; provenance = v['provenance']; o = v['observation']; b = o['behavior']
        require(all(v.get(k) == fixture[k] for k in ('sourceScene','style','category','orientation','messageID','action')), 'Wrong action fixture state')
        require(v.get('actualStyle') == fixture['style'] and v.get('applicationCategoryMatchesScene') is True, 'Wrong actual action traits')
        target = next(x for x in bases[fixture['sourceScene']]['rows'] if x['id'] == fixture['messageID'])
        require(v.get('outgoing') is target['outgoing'] and v.get('capturePhase') == ('selection' if fixture['action']=='more' else 'menu'), 'Wrong action capture phase/sender')
        landscape = fixture['orientation'] == 'landscape'
        require(v.get('actualLandscape') == landscape and (v['viewport'][0] > v['viewport'][1]) == landscape, 'Wrong actual orientation')
        require(v.get('animationsEnabled') is False, 'Settled reference must declare animation scope')
        require(v['fixtureSha256'] == digest(template / 'Actions.json') == provenance.get('actionsSha256'), 'Action fixture changed')
        require(provenance.get('scenesSha256') == digest(template / 'Scenes.json'), 'Base fixture changed')
        require(provenance.get('referenceTestsSha256') == digest(template / 'ReferenceTests.swift') and provenance.get('referenceAppSha256') == digest(template / 'ReferenceApp.swift'), 'Reference template changed')
        require(provenance.get('upstreamRevision') == manifest['revision'] and provenance.get('packageTree') == manifest['referencePackage']['tree'], 'Unreviewed UIKit source')
        require(provenance.get('projectSourceCommit') == commit and provenance.get('projectSourceDirty') is False, 'Wrong/dirty project source')
        require(o.get('activation') == 'sourceBubbleAccessibilityActivate' and o.get('ancestorVisibilityGuardPassed') is True, 'Missing actual source activation/visibility guard')
        require(o.get('keyboardDismissed') is True and o.get('modal') is True and o.get('previewClips') is True, 'Wrong source menu ownership')
        require(rect(o.get('sourceBubbleFrame')), 'Missing actual source bubble frame')
        require(rect(o['platterFrame']) and rect(o['previewFrame']) and abs(o['platterFrame'][2] - 250) < .01 and 1 <= o['previewScale'] <= 1.151, 'Invalid menu/preview geometry')
        preview, platter = o['previewFrame'], o['platterFrame']; inset = o['screenInset']; safe = o['safeArea']
        require(len(safe)==4 and all(type(x) in (float,int) and math.isfinite(x) and x>=0 for x in safe) and type(inset) in (float,int) and math.isfinite(inset) and inset>=0, 'Missing source safe-area/margins')
        expected_x=min(max(inset,preview[0]+preview[2]-250 if v['outgoing'] else preview[0]),v['viewport'][0]-inset-250)
        expected_y=min(preview[1]+preview[3]+18,v['viewport'][1]-safe[2]-platter[3]-8)
        require(abs(platter[0]-expected_x)<.1 and abs(platter[1]-expected_y)<.1,'Menu not placed from actual lifted preview')
        ids = [row['identifier'].removeprefix('conversation.menu.') for row in o['rows']]
        require(len(set(ids)) == len(ids) and all(x in ids for x in mandatory) and set(ids) <= set(mandatory + ['translate']), 'Missing/forbidden Chief menu actions')
        require(ids.index('arrowshape.turn.up.left') < ids.index('doc.on.doc') < ids.index('selection.pin.in.out') < ids.index('ellipsis.circle'), 'Wrong source action ordering')
        for row in o['rows']:
            require(rect(row['frame']) and rect(row['visibleFrame']) and row.get('enabled') is True and abs(row['frame'][3] - 42) < .01 and abs(row['visibleFrame'][3] - 42) < .01, 'Missing/clipped menu row')
        expected_height = 42 * len(ids) + (14 if len(ids) > 3 else 0) + 20
        require(abs(o['platterFrame'][3] - expected_height) < .01 and len(o['menuLabels']) == len(ids) and all(abs(x['fontSize'] - 17) < .01 for x in o['menuLabels']), 'Wrong fixed menu metrics')
        require(b.get('draftPreserved') is True and b.get('backendWriteAttempts') == 0 and b.get('viewingAfterAction') is True, 'Draft/viewing/backend ownership lost')
        if fixture['action'] == 'copy': require(b.get('copyMatchesSourceText') is True and b.get('copySentinelReplaced') is True, 'Copy callback not proven')
        if fixture['action'] == 'reply': require(b.get('replyHandoff') == 'sourceMenuTouchUpInside' and b.get('editorFocusedAfterReply') is True and b.get('replyAnimationsEnabled') is True, 'Reply handoff with source animation lifetime not proven')
        if fixture['action'] == 'more':
            require(b.get('selectedInitially') == ['conversation.bubble.' + fixture['messageID']] and b.get('selectedAfterToggle') == ['conversation.bubble.parent','conversation.bubble.reply'], 'Wrong selected source rows')
            require(b.get('forwardVisible') is False and b.get('composerHidden') is True and b.get('selectionCancelRestoresComposer') is True, 'Wrong Chief selection toolbar')
            require('Delete 2 Messages' in b.get('deleteConfirmation', []) and b.get('directCoreLocalDeleteIDs') == ['next'] and b.get('destructiveAlertActivated') is False, 'Unproven local-delete/confirmation scope')
        if fixture['category'] == 'accessibilityMedium':
            body = [x for x in v['labels'] if x.get('class') == 'ConversationEffectLabel']
            require(body and all(x['fontSize'] >= 28 for x in body), 'Accessibility body not rendered')
        reference.verify_visible_geometry(name, v)
        image = images.get(v['imageSha256']); require(image is not None, 'Missing/changed action screenshot')
        require(struct.unpack('>II', image.read_bytes()[16:24]) == tuple(round(x * v['scale']) for x in v['viewport']), 'Wrong image dimensions')
        paired.append((name, p, image, v))
    output.mkdir(parents=True, exist_ok=True)
    for name, p, image, v in paired:
        shutil.copyfile(image, output / (name + '.png')); (output / (name + '.json')).write_text(json.dumps(v,indent=2)+'\n')
    receipt = dict(projectCommit=commit,revision=manifest['revision'],packageTree=manifest['referencePackage']['tree'],actionsSha256=digest(template/'Actions.json'),scenes=[n for n,_,_,_ in paired],scope='Actual pinned UIKit settled action/selection views and registered source control callbacks. Public Core local-delete proof is separate; no destructive alert activation, physical long press, animation timing or Android acceptance.')
    (output/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n'); print(json.dumps(receipt,indent=2))

if __name__ == '__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--exported',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--project-commit',required=True);a=p.parse_args();verify(a.exported,a.output,a.project_commit)
