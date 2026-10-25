#!/usr/bin/env python3
"""Bind native public-API rich composer observations; not a rendered/UI parity verdict."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
CATEGORIES = {'large': 'UICTContentSizeCategoryL', 'accessibilityMedium': 'UICTContentSizeCategoryAccessibilityM'}
EFFECTS = ['big', 'small', 'shake', 'nod', 'explode', 'ripple', 'bloom', 'jitter']
CONTROLS = ['close', 'bold', 'italic', 'underline', 'strikethrough'] + EFFECTS


def require(value, message):
    if not value:
        raise ValueError(message)


def sha(value):
    return hashlib.sha256(value).hexdigest()


def finite(value):
    return type(value) in (float, int) and math.isfinite(value)


def check_range(value, count):
    require(isinstance(value, dict), 'Missing UTF-16 range')
    start, length = value.get('location'), value.get('length')
    require(type(start) is int and type(length) is int and 0 <= start <= start + length <= count, 'Invalid UTF-16 range')


def check_runs(rows, count):
    require(isinstance(rows, list), 'Missing semantic runs')
    previous = 0
    for row in rows:
        check_range(row, count)
        require(row['length'] > 0 and row['location'] >= previous, 'Overlapping or empty semantic run')
        require(type(row.get('style')) is int and 0 <= row['style'] <= 15 and row.get('effect') in [None] + EFFECTS, 'Unknown fixture formatting')
        require(row['style'] != 0 or row['effect'] is not None, 'Plain semantic run')
        previous = row['location'] + row['length']


def validate(payload, case, category, commit, fixture_bytes, manifest, font_cases=False):
    require(payload.get('schema') == 1 and payload.get('referenceKind') == 'chiefRichComposer' and
            payload.get('caseID') == case['id'] and payload.get('completed') is True, 'Wrong or incomplete native case')
    require(payload.get('fixtureSha256') == sha(fixture_bytes), 'Changed fixture identity')
    provenance = payload.get('provenance', {})
    expected = dict(upstreamRevision=manifest['revision'], packageTree=manifest['referencePackage']['tree'],
                    projectSourceCommit=commit,
                    **{('richComposerFontCasesSha256' if font_cases else 'richComposerCasesSha256'): sha(fixture_bytes)},
                    richComposerTestsSha256=sha((ROOT / 'tests/ios-chief-reference/RichComposerTests.swift').read_bytes()),
                    referenceAppSha256=sha((ROOT / 'tests/ios-chief-reference/ReferenceApp.swift').read_bytes()),
                    referenceTestsSha256=sha((ROOT / 'tests/ios-chief-reference/ReferenceTests.swift').read_bytes()))
    require(all(provenance.get(k) == v for k, v in expected.items()) and provenance.get('projectSourceDirty') is False, 'Unbound or dirty native provenance')
    require(payload.get('platform') == 'iOS' and str(payload.get('runtimeVersion', '')).startswith('26.'), 'Actual iOS 26 required')
    require(payload.get('actualCategory') == CATEGORIES[category], 'Wrong actual text category')
    require(payload.get('supportsAttachments') is False and payload.get('privateSelectorsUsed') is False and
            payload.get('sourceModified') is False and payload.get('externalBackend') is False, 'Wrong native scope')
    require(payload.get('backendMode') == case['backend'] and payload.get('style') == case['style'], 'Changed fixture configuration')
    if font_cases:
        require(payload.get('probe') == 'liveFonts', 'Wrong native font probe')
    records = payload.get('records')
    require(isinstance(records, list) and len(records) == len(case['actions']) + 1, 'Missing native steps')
    identity = None
    sends = 0
    previous_time = 0
    for index, record in enumerate(records):
        action = {'kind': 'initial'} if index == 0 else case['actions'][index - 1]
        require(record.get('step') == index - 1 and record.get('action') == action, 'Changed or reordered native action')
        if index:
            before, after = record.get('before'), record.get('after')
            require(finite(before) and finite(after) and previous_time <= before <= after and after - before < 15, 'Missing native action clock')
            previous_time = after
        state = record.get('state', {})
        text = state.get('text')
        require(isinstance(text, str), 'Missing native text')
        count = len(text.encode('utf-16-le')) // 2
        require(type(state.get('utf16Count')) is int and state['utf16Count'] == count, 'Native UTF-16 length mismatch')
        check_range(state.get('selection'), count)
        if state.get('marked') is not None:
            check_range(state['marked'], count)
        check_runs(state.get('runs'), count)
        attrs = state.get('attributes')
        require(isinstance(attrs, list), 'Missing native attributed text')
        covered = 0
        for span in attrs:
            check_range(span, count)
            require(span['location'] == covered and span['length'] > 0, 'Noncontiguous native attributes')
            require(isinstance(span.get('attributes'), dict), 'Missing native attribute values')
            covered += span['length']
        require(covered == count, 'Native attributes do not cover text')
        typing = state.get('typing', {})
        require(type(typing.get('style')) is int and typing.get('effect') in [None] + EFFECTS, 'Missing typing attributes')
        require(finite(typing.get('fontSize')) and typing['fontSize'] > 0, 'Missing typing font')
        require(state.get('delegateClass', '').endswith('ConversationComposerView') and state.get('viewing') is True, 'Wrong or unmounted source composer')
        require(isinstance(state.get('editorIdentity'), str) and bool(state['editorIdentity']), 'Missing editor identity')
        if identity is None:
            identity = state['editorIdentity']
        require(state['editorIdentity'] == identity, 'Recreated native editor')
        require(state.get('actualCategory') == CATEGORIES[category], 'Changed actual text category')
        for flag in ['firstResponder', 'canUndo', 'canRedo', 'paletteInWindow', 'reduceMotion', 'boldText']:
            require(type(state.get(flag)) is bool, 'Missing native boolean: ' + flag)
        require(state.get('unsupportedWrites') == 0, 'Unexpected native backend write')
        controls = state.get('controls')
        require(isinstance(controls, list), 'Missing palette control observations')
        if state.get('inputViewIdentifier') == 'conversation.textEffects.palette':
            require(sorted(c.get('identifier', '') for c in controls) == sorted('conversation.textEffects.' + x for x in CONTROLS), 'Missing or duplicated source palette control')
            for control in controls:
                require(bool(control.get('label')) and type(control.get('enabled')) is bool and
                        type(control.get('selected')) is bool and type(control.get('accessibilitySelected')) is bool, 'Missing native control state')
                box = control.get('frameInPalette')
                require(isinstance(box, list) and len(box) == 4 and all(finite(x) for x in box), 'Missing native control geometry')
        else:
            require(state.get('inputViewIdentifier') is None and controls == [], 'Unidentified native input view')
        observed = record.get('observed', {})
        if action['kind'] == 'openEffects':
            require(observed.get('menuChildren') == ['Fixture before', 'Text Effects', 'Fixture after'] and
                    observed.get('invocation') == 'source-menu-UIAction-via-UIButton-primaryAction', 'Missing actual source menu action')
            require(state.get('inputViewIdentifier') == 'conversation.textEffects.palette', 'Source palette did not open')
        if action['kind'] == 'closeEffects':
            require(state.get('inputViewIdentifier') is None, 'Source palette did not close')
        if action['kind'] in ['undo', 'redo']:
            require(type(observed.get('performed')) is bool, 'Unrecorded undo/redo admission')
        if action['kind'] == 'send':
            sends += 1
        drafts = state.get('backendDrafts')
        require(isinstance(drafts, list) and len(drafts) == sends, 'Missing or extra fixture send')
        for draft in drafts:
            require(isinstance(draft.get('text'), str), 'Missing fixture draft text')
            check_runs(draft.get('runs'), len(draft['text'].encode('utf-16-le')) // 2)
        messages = state.get('storeMessages')
        require(isinstance(messages, list), 'Missing source Store observations')
        for message in messages:
            require(isinstance(message.get('text'), str) and isinstance(message.get('delivery'), str), 'Missing source Store message')
            check_runs(message.get('runs'), len(message['text'].encode('utf-16-le')) // 2)

    if font_cases:
        validate_font_categories(records, category)


def validate_font_categories(records, category):
    expected = CATEGORIES[category]
    changes = 0
    for record in records:
        action = record['action']; state = record['state']
        if action['kind'] == 'metricsCategory':
            require(action.get('target') in CATEGORIES, 'Unknown text metric category')
            observed = record.get('observed', {})
            require(observed.get('invocation') == 'UIViewController.traitOverrides.preferredContentSizeCategory' and
                    observed.get('beforeControllerCategory') == expected, 'Missing public trait transition')
            expected = CATEGORIES[action['target']]; changes += 1
        require(state.get('controllerCategory') == expected and state.get('editorCategory') == expected,
                'Trait override did not reach original editor')
        for span in state['attributes']:
            attributes = span['attributes']
            require(finite(attributes.get('fontSize')) and attributes['fontSize'] > 0 and
                    isinstance(attributes.get('fontName'), str) and bool(attributes['fontName']), 'Missing actual span font')
    require(changes >= 2, 'Missing live text metric roundtrip')


def verify(exported, output, commit, category, font_cases=False):
    require(re.fullmatch('[0-9a-f]{40}', commit) is not None, 'Full immutable source commit required')
    fixture = (ROOT / 'tests/chief-rich-composer' / ('FontCases.json' if font_cases else 'Cases.json')).read_bytes()
    manifest = json.loads((ROOT / 'third_party/cmux-chief/manifest.json').read_text())
    candidates = {}
    for path in exported.rglob('*.json'):
        require(path.stat().st_size <= 4 * 1024 * 1024, 'Oversized attachment')
        try:
            payload = json.loads(path.read_text())
        except (UnicodeError, json.JSONDecodeError):
            continue
        if isinstance(payload, dict) and payload.get('referenceKind') == 'chiefRichComposer':
            candidates.setdefault(payload.get('caseID'), []).append(payload)
    cases = json.loads(fixture)['cases']
    require(set(candidates) == {c['id'] for c in cases}, 'Missing or unexpected native case inventory')
    for case in cases:
        require(len(candidates[case['id']]) == 1, 'Duplicated native payload')
        validate(candidates[case['id']][0], case, category, commit, fixture, manifest, font_cases)
    output.mkdir(parents=True, exist_ok=False)
    files = {}
    for case in cases:
        name = case['id'] + '.json'
        data = (json.dumps(candidates[case['id']][0], ensure_ascii=False, indent=2) + '\n').encode()
        (output / name).write_bytes(data); files[name] = sha(data)
    receipt = dict(projectCommit=commit, upstreamRevision=manifest['revision'], fixtureSha256=sha(fixture), category=category,
                   probe='liveFonts' if font_cases else 'transactions', cases=len(cases), actions=sum(len(c['actions']) for c in cases), files=files,
                   scope='Actual pinned UIKit composer/Store public-API and source-control observations with authored in-memory backend. No gestures/physical keyboard, XCUIScreen/pixel, Chief backend, Android UI or full parity acceptance.')
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps(receipt, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--exported', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--project-commit', required=True)
    parser.add_argument('--category', choices=CATEGORIES, default='large')
    parser.add_argument('--font-cases', action='store_true', help='Verify the separate live controller-trait font probe')
    args = parser.parse_args()
    verify(args.exported, args.output, args.project_commit, args.category, args.font_cases)
