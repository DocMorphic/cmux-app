#!/usr/bin/env python3
"""Pair exported XCTest PNGs with their scene metadata by exact payload hash."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import shutil
import struct


def verify_visible_geometry(name, value):
    width, height = value['viewport']
    for record in value.get('controls', []) + value.get('imageViews', []):
        frame, visible = record.get('frame'), record.get('visibleFrame')
        if any(not isinstance(rect, list) or len(rect) != 4 or
               any(type(v) not in (int, float) or not math.isfinite(v) for v in rect)
               for rect in (frame, visible)):
            raise ValueError('Missing effective ancestor visibility geometry: ' + name)
        x, y, w, h = visible
        fx, fy, fw, fh = frame
        if (w <= 0 or h <= 0 or x < -0.01 or y < -0.01 or x + w > width + 0.01 or y + h > height + 0.01 or
            x < fx - 0.01 or y < fy - 0.01 or x + w > fx + fw + 0.01 or y + h > fy + fh + 0.01):
            raise ValueError('Invalid effective ancestor visibility geometry: ' + name)


def verify_interaction(name, value, specification, source_scene):
    """Require observed source behavior; never assume overFullScreen suspends viewing."""
    if any(value.get(key) != specification[key] for key in ('mode', 'sourceScene', 'orientation')):
        raise ValueError('Wrong interaction identity: ' + name)
    width, height = value['viewport']
    landscape = specification['orientation'] == 'landscape'
    if (width > height) != landscape or width == height:
        raise ValueError('Wrong interaction orientation: ' + name)
    if value.get('verticalSizeClass') != ('compact' if landscape else 'regular'):
        raise ValueError('Wrong actual vertical size class: ' + name)
    if len(value.get('safeArea', [])) != 4 or not value.get('imageViews'):
        raise ValueError('Missing interaction geometry: ' + name)
    behavior = value.get('behavior', {})
    if behavior.get('viewingBefore') is not True:
        raise ValueError('Reference conversation did not start viewing: ' + name)
    if behavior.get('ancestorVisibilityGuardPassed') is not True:
        raise ValueError('Ancestor visibility observation guard did not run: ' + name)
    verify_visible_geometry(name, value)
    controls = {control['identifier']: control for control in value.get('controls', []) if control.get('identifier')}
    if specification['mode'] == 'details':
        titles = [label for label in value['labels'] if label.get('accessibilityIdentifier') == 'conversation.details.title']
        minimum_title_size = 38 if specification['category'] == 'accessibilityMedium' else 28
        if len(titles) != 1 or titles[0].get('fontSize', 0) < minimum_title_size:
            raise ValueError('Actual details title type was not rendered: ' + name)
        for action in ('call', 'video', 'mail'):
            button = controls.get('conversation.details.' + action)
            if button is None or button.get('enabled') is not False:
                raise ValueError('Unavailable source details action was enabled or absent: ' + name)
        for action, expected in (('hideAlerts', False), ('readReceipts', True)):
            toggle = controls.get('conversation.details.' + action)
            if toggle is None or toggle.get('enabled') is not True or toggle.get('on') is not expected:
                raise ValueError('Source details switch did not roll back: ' + name)
        if type(behavior.get('viewingWhileDetails')) is not bool:
            raise ValueError('Missing actual modal viewing observation: ' + name)
        if behavior.get('detailsCloseActivation') != 'uniqueNavigationPrimaryAction':
            raise ValueError('Actual source details Back item was not exercised: ' + name)
        if (behavior.get('navigationPrimaryControlCount') != 1 or behavior.get('navigationLeftItemCount') != 1 or
            behavior.get('navigationRightItemCount') != 0 or behavior.get('navigationItemIdentifier') != 'conversation.details.back'):
            raise ValueError('Ambiguous source Back navigation controls: ' + name)
        required = {'editorFocusedWhileDetails': False, 'editorFocusedAfterReturn': False,
                    'draftPreserved': True, 'viewingAfterReturn': True,
                    'alertsOptimistic': True, 'receiptsOptimistic': True,
                    'alertsAfterRollback': False, 'receiptsAfterRollback': True}
        if any(behavior.get(key) is not expected for key, expected in required.items()):
            raise ValueError('Actual details focus, draft or rollback behavior failed: ' + name)
    elif specification['mode'] == 'thread':
        thread = value.get('thread', {})
        root_id, quote_id = specification['rootID'], specification['quoteMessageID']
        rows = source_scene['rows']
        by_id = {row['id']: row for row in rows}
        if (root_id not in by_id or quote_id not in by_id or by_id[quote_id].get('replyTo') != root_id or
            thread.get('rootID') != root_id or thread.get('quoteMessageID') != quote_id):
            raise ValueError('Wrong actual thread root/quote identity: ' + name)
        expected = sorted('conversation.bubble.' + row['id'] for row in rows if row['id'] == root_id or row.get('replyTo') == root_id)
        if thread.get('bubbleIdentifiers') != expected:
            raise ValueError('Thread must contain exactly loaded root and direct replies: ' + name)
        required = {'threadOpenActivation': 'sourceQuoteAccessibilityActivate', 'quoteContainerCount': 1, 'quoteElementCount': 1,
                    'threadCloseActivation': 'sourceTrailingTouchUpInside', 'threadCloseControlCount': 1,
                    'viewingWhileThread': True, 'editorFocusedWhileThread': True, 'draftPreservedWhileThread': True,
                    'backingAccessibilityHidden': True, 'backHiddenWhileThread': True,
                    'threadRemovedAfterClose': True, 'backingAccessibilityRestored': True, 'viewingAfterReturn': True,
                    'editorFocusedAfterReturn': True, 'draftPreserved': True, 'normalHeaderRestored': True}
        if any(behavior.get(key) != expected or type(behavior.get(key)) is not type(expected) for key, expected in required.items()):
            raise ValueError('Actual source thread entry/close, focus, draft or accessibility behavior failed: ' + name)
        closes = [c for c in value.get('controls', []) if c.get('identifier') == 'conversation.header.trailing']
        if len(closes) != 1 or closes[0].get('label') != 'Close' or closes[0].get('enabled') is not True:
            raise ValueError('Actual source thread Close control missing or ambiguous: ' + name)
        if any(c.get('label') == 'Back' for c in value.get('controls', [])):
            raise ValueError('Hidden backing Back control leaked into thread observations: ' + name)
        body = [label for label in thread.get('labels', []) if label.get('class') == 'ConversationEffectLabel']
        expected_text = [row['text'] for row in rows if row['id'] == root_id or row.get('replyTo') == root_id]
        if sorted(label.get('text', '') for label in body) != sorted(expected_text):
            raise ValueError('Actual thread copies missing, duplicated or carrying nested quote text: ' + name)
        if specification['category'] == 'accessibilityMedium' and any(label.get('fontSize', 0) < 28 for label in body):
            raise ValueError('Actual thread accessibility type missing: ' + name)
        frames = thread.get('rowFrames', [])
        if not frames or any(row.get('identityTransform') is not True for row in frames):
            raise ValueError('Actual thread row motion did not settle: ' + name)
        for key, count in (('contentFrame', 4), ('contentSize', 2), ('contentOffset', 2), ('editorFrame', 4)):
            points = thread.get(key)
            if (not isinstance(points, list) or len(points) != count or
                any(type(v) not in (int, float) or not math.isfinite(v) for v in points)):
                raise ValueError('Actual thread layout geometry missing: ' + name)
    elif specification['mode'] == 'header':
        for identifier in ('conversation.header.name', 'conversation.header.trailing'):
            if controls.get(identifier, {}).get('enabled') is not True:
                raise ValueError('Missing actual compact header control: ' + name)
    else:
        raise ValueError('Unreviewed interaction mode: ' + name)


def verify(exported, output, project_commit, with_interactions=False):
    root = Path(__file__).resolve().parents[1]
    manifest = json.loads((root / 'third_party/cmux-chief/manifest.json').read_text())
    scene_bytes = (root / 'tests/ios-chief-reference/Scenes.json').read_bytes()
    scenes = {scene['id']: scene for scene in json.loads(scene_bytes)['scenes']}
    interaction_bytes = (root / 'tests/ios-chief-reference/Interactions.json').read_bytes()
    interactions = {scene['id']: scene for scene in json.loads(interaction_bytes)['scenes']} if with_interactions else {}
    scenes.update(interactions)
    expected = set(scenes)
    scene_sha = hashlib.sha256(scene_bytes).hexdigest()
    interaction_sha = hashlib.sha256(interaction_bytes).hexdigest()
    images = {}; metadata = {}
    for path in exported.rglob('*'):
        if not path.is_file() or path.stat().st_size > 32 * 1024 * 1024: continue
        data = path.read_bytes()
        if data.startswith(b'\x89PNG\r\n\x1a\n'):
            images[hashlib.sha256(data).hexdigest()] = path
        elif data.lstrip().startswith(b'{'):
            try: value = json.loads(data)
            except (ValueError, UnicodeError): continue
            if isinstance(value, dict) and 'scene' in value and 'imageSha256' in value:
                if value['scene'] in metadata: raise ValueError('Duplicate scene metadata')
                metadata[value['scene']] = (path, value)
    if set(metadata) != expected: raise ValueError(f'Missing/unexpected rendered scenes: expected {sorted(expected)}, found {sorted(metadata)}')
    paired = []
    for name in sorted(expected):
        path, value = metadata[name]; provenance = value['provenance']
        if value.get('style') != scenes[name]['style'] or value.get('category') != scenes[name]['category']:
            raise ValueError('Wrong scene traits: ' + name)
        if value.get('applicationCategoryMatchesScene') is not True:
            raise ValueError('Application content-size category did not match scene: ' + name)
        if scenes[name]['category'] == 'accessibilityMedium' and scenes[name].get('mode') != 'details':
            body = [label for label in value['labels'] if label.get('class') == 'ConversationEffectLabel']
            if not body or any(label.get('fontSize', 0) < 28 for label in body):
                raise ValueError('Accessibility body type was not rendered: ' + name)
        if value['fixtureSha256'] != scene_sha or provenance['scenesSha256'] != scene_sha: raise ValueError('Scene fixture changed')
        if provenance['upstreamRevision'] != manifest['revision'] or provenance['packageTree'] != manifest['referencePackage']['tree']:
            raise ValueError('Unreviewed UIKit source')
        if provenance['projectSourceDirty']: raise ValueError('Reference project was built from an uncommitted tree')
        if provenance['projectSourceCommit'] != project_commit: raise ValueError('Unexpected reference project commit')
        if name in interactions:
            if value.get('interactionFixtureSha256') != interaction_sha or provenance.get('interactionsSha256') != interaction_sha:
                raise ValueError('Interaction fixture changed')
            verify_interaction(name, value, interactions[name], scenes[interactions[name]['sourceScene']])
        image = images.get(value['imageSha256'])
        if image is None: raise ValueError('Missing or changed screenshot payload: ' + name)
        dimensions = struct.unpack('>II', image.read_bytes()[16:24])
        expected_dimensions = tuple(round(d * value['scale']) for d in value['viewport'])
        if dimensions != expected_dimensions or min(dimensions) <= 0: raise ValueError('Wrong rendered image dimensions: ' + name)
        if not value['labels'] or value['storeMessages'] <= 0: raise ValueError('No rendered transcript evidence: ' + name)
        paired.append((name, path, image, value))
    output.mkdir(parents=True, exist_ok=False)
    for name, path, image, value in paired:
        shutil.copyfile(image, output / (name + '.png')); shutil.copyfile(path, output / (name + '.json'))
    receipt = {'revision': manifest['revision'], 'packageTree': manifest['referencePackage']['tree'], 'scenesSha256': scene_sha,
               'scenes': sorted(expected), 'projectSourceCommits': sorted({v['provenance']['projectSourceCommit'] for _, _, _, v in paired}),
               'scope': 'Verified source/fixture/PNG payload association; images still require visual inspection, matched Android comparison and real-device acceptance'}
    if with_interactions: receipt['interactionsSha256'] = interaction_sha
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps(receipt, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--exported', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--project-commit', required=True)
    parser.add_argument('--with-interactions', action='store_true', help='Require all actual details/compact/thread interaction captures as well as the original nine scenes')
    args = parser.parse_args(); verify(args.exported.resolve(), args.output.resolve(), args.project_commit, args.with_interactions)
