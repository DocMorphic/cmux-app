#!/usr/bin/env python3
"""Pair exported XCTest PNGs with their scene metadata by exact payload hash."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct


def verify_interaction(name, value, specification):
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
        if behavior.get('detailsCloseActivation') not in ('barButtonTargetAction', 'barButtonAccessibilityActivate',
                                                         'barButtonRenderedControl', 'barButtonRenderedAccessibilityActivate'):
            raise ValueError('Actual source details Back item was not exercised: ' + name)
        required = {'editorFocusedWhileDetails': False, 'editorFocusedAfterReturn': False,
                    'draftPreserved': True, 'viewingAfterReturn': True,
                    'alertsOptimistic': True, 'receiptsOptimistic': True,
                    'alertsAfterRollback': False, 'receiptsAfterRollback': True}
        if any(behavior.get(key) is not expected for key, expected in required.items()):
            raise ValueError('Actual details focus, draft or rollback behavior failed: ' + name)
    else:
        for identifier in ('conversation.header.name', 'conversation.header.trailing'):
            if controls.get(identifier, {}).get('enabled') is not True:
                raise ValueError('Missing actual compact header control: ' + name)


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
            verify_interaction(name, value, interactions[name])
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
    parser.add_argument('--with-interactions', action='store_true', help='Require all four actual details/compact interaction captures as well as the original nine scenes')
    args = parser.parse_args(); verify(args.exported.resolve(), args.output.resolve(), args.project_commit, args.with_interactions)
