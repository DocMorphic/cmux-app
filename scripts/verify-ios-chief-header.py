#!/usr/bin/env python3
"""Verify an animation-enabled pinned UIKit header timeline, without assuming its result."""
import argparse
import hashlib
import json
import math
from pathlib import Path

CASES = {'settled': (.85, False), 'early-close': (.10, False), 'glyph-swap-close': (.34, False),
         'reopen-return': (.85, True), 'reopen-glyph-swap': (.34, True)}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def number(value):
    return type(value) in (int, float) and math.isfinite(value)


def validate(value, project_commit, manifest, fixture_sha, test_sha, app_sha):
    require(value.get('schema') == 1 and value.get('kind') == 'chiefReplyHeaderTimeline', 'Unexpected header schema')
    p = value.get('provenance', {})
    require(p.get('projectSourceCommit') == project_commit and p.get('projectSourceDirty') is False, 'Unreviewed header project source')
    require(p.get('upstreamRevision') == manifest['revision'] and p.get('packageTree') == manifest['referencePackage']['tree'], 'Unreviewed upstream package')
    require(p.get('referenceTestsSha256') == test_sha and p.get('referenceAppSha256') == app_sha, 'Header harness changed')
    require(value.get('fixtureSha256') == fixture_sha and p.get('scenesSha256') == fixture_sha, 'Header fixture changed')
    require(value.get('reduceMotion') is False, 'Reduced motion is not animated header evidence')
    require(isinstance(value.get('osVersion'), str) and value['osVersion'].split('.')[0].isdigit(), 'Missing actual simulator runtime')
    require(isinstance(value.get('os'), str) and value['os'], 'Missing runtime build')
    require(value.get('category') == 'UICTContentSizeCategoryL', 'Wrong application content category')
    require(number(value.get('scale')) and value['scale'] > 0, 'Invalid header scale')
    require(isinstance(value.get('viewport'), list) and len(value['viewport']) == 2 and all(number(x) and x > 0 for x in value['viewport']), 'Invalid viewport')
    cases = value.get('cases')
    require(isinstance(cases, list) and len(cases) == len(CASES), 'Missing header cases')
    require(len({c.get('name') for c in cases}) == len(cases) and {c.get('name') for c in cases} == set(CASES), 'Duplicate or unreviewed header cases')

    def state(s):
        require(isinstance(s, dict) and number(s.get('elapsed')) and s['elapsed'] >= 0, 'Invalid sample clock')
        for field in ('overlayPresent', 'backingAXHidden', 'editorFocused', 'draftPreserved', 'animationsEnabled'):
            require(type(s.get(field)) is bool, 'Missing sample boolean: ' + field)
        require(s['animationsEnabled'] and s['draftPreserved'] and s['editorFocused'], 'Animation/draft/focus precondition lost')
        for name in ('back', 'trailing'):
            c = s.get(name, {})
            require(c.get('label') in ({'Back'} if name == 'back' else {'Stop Chief', 'Close'}), 'Unreviewed control label')
            for field in ('modelOpacity', 'presentationOpacity'):
                require(number(c.get(field)) and 0 <= c[field] <= 1.00001, 'Invalid control opacity')
            require(type(c.get('presentationLayers')) is int and c['presentationLayers'] >= 0, 'Missing presentation-layer count')
            for field in ('hiddenAncestor', 'axHiddenAncestor', 'isAccessibilityElement', 'enabled', 'interactionEnabled', 'hitControl', 'symbol'):
                require(type(c.get(field)) is bool, 'Missing control observation: ' + field)
            require(isinstance(c.get('hitClass'), str) and c['hitClass'], 'Missing actual window hit result')
            require(isinstance(c.get('frame'), list) and len(c['frame']) == 4 and all(number(x) for x in c['frame']) and min(c['frame'][2:]) > 0, 'Invalid control frame')
            require(isinstance(c.get('imageSize'), list) and len(c['imageSize']) == 2 and all(number(x) and x > 0 for x in c['imageSize']) and c['symbol'], 'Missing real source symbol')

    summaries = []
    for case in cases:
        name = case['name']; duration, reopens = CASES[name]
        state(case.get('initial')); state(case.get('final'))
        require(not case['initial']['overlayPresent'] and not case['final']['overlayPresent'], 'Overlay lifetime did not complete')
        require(all(case['initial'][control]['presentationOpacity'] >= .99 for control in ('back', 'trailing')), 'Header did not begin from a fresh visible baseline')
        require(not case['final']['backingAXHidden'] and case['final']['trailing']['label'] == 'Stop Chief', 'Source backing/normal label not restored')
        phases = case.get('phases', [])
        expected = [('enter', duration), ('exit-before-reopen', .01 if name == 'reopen-glyph-swap' else .10), ('reopen', .85), ('final-exit', .85)] if reopens else [('enter', duration), ('exit', .85)]
        require(len(phases) == len(expected), 'Missing header phase')
        records = []
        for phase, (phase_name, wanted) in zip(phases, expected):
            require(phase.get('name') == phase_name and number(phase.get('requestedDuration')) and abs(phase['requestedDuration'] - wanted) < 1e-8, 'Wrong phase order/duration')
            samples = phase.get('samples')
            # The synchronous source exit can itself take more than the 10 ms
            # reopen interval. Its immediate observation is still meaningful;
            # longer animation phases must retain dense continuous sampling.
            require(isinstance(samples, list) and len(samples) >= (15 if wanted >= .7 else 1 if wanted <= .01 else 2), 'Insufficient animated samples')
            for s in samples:
                state(s)
            clocks = [s['elapsed'] for s in samples]
            require(all(b > a for a, b in zip(clocks, clocks[1:])), 'Nonmonotonic header clock')
            require(clocks[0] < .15 and clocks[-1] >= wanted and clocks[-1] < wanted + .3, 'Incomplete/late header observation')
            gaps = [b-a for a, b in zip(clocks, clocks[1:])]
            require(not gaps or max(gaps) < .15, 'Header sampling missed too much runtime')
            require(any(s['trailing']['presentationLayers'] > 0 for s in samples), 'No real presentation layers')
            if phase_name in ('enter', 'reopen'):
                require(samples[0]['overlayPresent'] and samples[0]['backingAXHidden'], 'Actual source reply did not enter')
            else:
                require(not samples[0]['backingAXHidden'], 'Source backing AX not restored at exit')
            records.append({'phase': phase_name, 'samples': len(samples), 'first': clocks[0], 'last': clocks[-1],
                'maxGap': max(gaps, default=0),
                'firstCloseLabel': next((s['elapsed'] for s in samples if s['trailing']['label'] == 'Close'), None),
                'firstBackHit': next((s['elapsed'] for s in samples if s['back']['hitControl']), None),
                'finalBackOpacity': samples[-1]['back']['presentationOpacity'],
                'finalTrailingOpacity': samples[-1]['trailing']['presentationOpacity']})
        summaries.append({'name': name, 'phases': records})
    return {'schema': 1, 'upstreamRevision': manifest['revision'], 'projectSourceCommit': project_commit,
        'osVersion': value['osVersion'], 'runtimeBranch': 'iOS27+' if int(value['osVersion'].split('.')[0]) >= 27 else 'iOS26-or-earlier',
        'cases': summaries, 'scope': 'Validated actual animated UIKit observations; opacity/hit-test outcomes are observations, not forced expected behavior. No physical VoiceOver or Android parity claim.'}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--exported', type=Path, required=True); p.add_argument('--output', type=Path, required=True)
    p.add_argument('--project-commit', required=True); a = p.parse_args()
    root = Path(__file__).resolve().parents[1]
    found = []
    for path in a.exported.rglob('*.json'):
        try:
            value = json.loads(path.read_text())
        except (UnicodeDecodeError, json.JSONDecodeError):
            continue
        if isinstance(value, dict) and value.get('kind') == 'chiefReplyHeaderTimeline':
            found.append((path, value))
    require(len(found) == 1, 'Require exactly one header timeline payload')
    source, value = found[0]
    sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
    receipt = validate(value, a.project_commit, json.loads((root/'third_party/cmux-chief/manifest.json').read_text()),
        sha(root/'tests/ios-chief-reference/Scenes.json'), sha(root/'tests/ios-chief-reference/ReferenceTests.swift'), sha(root/'tests/ios-chief-reference/ReferenceApp.swift'))
    a.output.mkdir(parents=True, exist_ok=True)
    data = source.read_bytes(); (a.output/'chief-reply-header-timeline.json').write_bytes(data)
    receipt['payloadSha256'] = hashlib.sha256(data).hexdigest()
    (a.output/'receipt.json').write_text(json.dumps(receipt, indent=2)+'\n')
    print(json.dumps(receipt, indent=2))

if __name__ == '__main__':
    main()
