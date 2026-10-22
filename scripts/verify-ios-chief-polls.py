#!/usr/bin/env python3
"""Bind native Poll Details measurements to immutable source, fixtures and compositor bytes."""
import argparse
import hashlib
import importlib.util
import json
import math
import re
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('translation_geometry', ROOT / 'scripts/verify-ios-chief-translations.py')
geometry = importlib.util.module_from_spec(spec); spec.loader.exec_module(geometry)
digest = geometry.digest
require = geometry.require
rect = geometry.rect


def expected_sections(poll):
    participants = {p['id']: p for p in poll['participants']}
    def name(identifier):
        p = participants.get(identifier)
        return ('You' if p['isMe'] else p['name']) if p else identifier
    sections = []
    for option in poll['options']:
        voters = [v['participantID'] for v in poll['votes'] if v['optionID'] == option['id']]
        footer = '1 Vote' if len(voters) == 1 else str(len(voters)) + ' Votes'
        if option.get('addedByID'):
            footer += ' · ' + ('Added by You' if participants.get(option['addedByID'], {}).get('isMe') else 'Added by ' + name(option['addedByID']))
        sections.append(dict(title=option['text'], footer=footer, names=[name(v) for v in voters] or ['No votes yet']))
    voted = {v['participantID'] for v in poll['votes']}
    non_voters = [name(p['id']) for p in poll['participants'] if p['id'] not in voted]
    if non_voters:
        sections.append(dict(title='No Votes', footer='', names=non_voters))
    return sections


def validate_type_metrics(value, specification):
    metrics = value.get('typeMetrics', {})
    names = ['extraSmall', 'small', 'medium', 'large', 'extraLarge', 'extraExtraLarge', 'extraExtraExtraLarge',
             'accessibilityMedium', 'accessibilityLarge', 'accessibilityExtraLarge',
             'accessibilityExtraExtraLarge', 'accessibilityExtraExtraExtraLarge']
    bodies = [14, 15, 16, 17, 19, 21, 23, 28, 33, 40, 47, 53]
    records = metrics.get('records', [])
    require(metrics.get('schema') == 1 and metrics.get('boldText') is False and
        [x.get('name') for x in records] == names, 'Missing complete default-weight poll type metrics')
    categories = []; previous = 0
    fields = ['bodyPointSize', 'bodyLineHeight', 'bodyAscender', 'bodyDescender',
              'footerPointSize', 'footerLineHeight', 'footerAscender', 'footerDescender']
    for row, body in zip(records, bodies):
        require(all(type(row.get(k)) in (int, float) and math.isfinite(row[k]) for k in fields), 'Invalid finite poll font input')
        require(row['bodyPointSize'] == body and 0 < row['footerPointSize'] <= body and
            row['footerPointSize'] >= previous and min(row['bodyLineHeight'], row['footerLineHeight']) > 0 and
            min(row['bodyAscender'], row['footerAscender']) > 0 and max(row['bodyDescender'], row['footerDescender']) < 0,
            'Invalid measured poll font input')
        require(type(row.get('category')) is str and row['category'], 'Missing poll font category')
        previous = row['footerPointSize']; categories.append(row['category'])
    require(len(set(categories)) == len(names), 'Duplicate compatible poll font categories')
    current = records[names.index(specification['category'])]
    require(metrics.get('currentName') == specification['category'] and metrics.get('actualCategory') == current['category'],
        'Poll font traits differ from actual application category')
    for actual, key in [('actualBodyPointSize', 'bodyPointSize'), ('actualBodyLineHeight', 'bodyLineHeight'),
                        ('actualFooterPointSize', 'footerPointSize'), ('actualFooterLineHeight', 'footerLineHeight')]:
        require(metrics.get(actual) == current[key], 'Actual poll font differs from compatible input')
    footers = {s.get('footer') for s in value['table']['sections'] if s.get('footer')}
    labels = [x for x in value.get('labels', []) if x.get('insidePollTable') is True]
    for label in labels:
        if label.get('text') in footers:
            require(label.get('fontSize') == current['footerPointSize'], 'Visible source footer does not use captured footnote font')
            require(label.get('lineHeight') == current['footerLineHeight'], 'Visible source footer line height differs from actual application font')
        if label.get('text') == 'No votes yet':
            require(label.get('fontSize') == current['bodyPointSize'], 'Visible source row does not use captured body font')
    if specification['model'] == 'pending':
        require(any(x.get('text') in footers for x in labels), 'Missing visible actual source footer font')
        if specification['scroll'] == 'bottom':
            require(value['table'].get('contentOffset', [0, 0])[1] > 0, 'Pending bottom reference did not scroll')
            require({'No Votes', 'Chief', 'You'} <= {x.get('text') for x in labels}, 'Missing visible pending bottom content')
            require(all(x.get('fontSize') == current['bodyPointSize'] for x in labels if x.get('text') in {'No Votes', 'Chief', 'You'}),
                'Visible pending bottom font differs from actual body')


def validate_state(value, specification, poll, commit, images, template, manifest, orientation=None, dismissed=True):
    name = specification['id']; source = value['provenance']
    require(value.get('scene') == name and value.get('fixture') == specification, 'Wrong poll fixture state')
    require(value.get('fixtureSha256') == digest(template / 'PollReferences.json') == source.get('pollFixtureSha256'), 'Changed poll fixture')
    require(source.get('referenceAppSha256') == digest(template / 'PollReferenceApp.swift') and source.get('pollUiTestsSha256') == digest(template / 'PollUITests.swift'), 'Changed poll host/tests')
    require(source.get('upstreamRevision') == manifest['revision'] and source.get('packageTree') == manifest['referencePackage']['tree'], 'Unreviewed poll package')
    require(source.get('projectSourceCommit') == commit and source.get('projectSourceDirty') is False, 'Wrong/dirty poll project source')
    require(value.get('captureMethod') == 'XCUIScreen.main.screenshot' and value.get('activation') == 'physicalPressAndSourcePollDetails', 'Missing actual poll activation/compositor capture')
    require(value.get('ready') is True and value.get('presented') is True and (not dismissed or value.get('doneDismissed') is True), 'Source poll was not presented/dismissed')
    require(value.get('backendWriteAttempts') == 0, 'Read-only poll wrote to backend')
    require(value.get('categoryMatches') is True and value.get('style') == specification['style'] and value.get('direction') == specification['direction'], 'Wrong actual poll traits')
    size = value.get('viewport'); scale = value.get('scale')
    require(isinstance(size, list) and len(size) == 2 and all(type(x) in (float, int) and math.isfinite(x) and x > 0 for x in size), 'Invalid poll viewport')
    require(type(scale) in (float, int) and math.isfinite(scale) and scale > 0, 'Invalid poll scale')
    require((size[0] > size[1]) == ((orientation or specification['orientation']) == 'landscape'), 'Wrong actual poll orientation')
    require(value.get('runtime', {}).get('systemVersion') and rect(value.get('navigationFrame')), 'Missing native runtime/navigation geometry')
    require(value.get('navigationPrompt') == poll['question'], 'Wrong poll question prompt')
    table = value['table']; require(table.get('style') == 2 and rect(table.get('frame')), 'Missing source inset-grouped table')
    expected = expected_sections(poll); sections = table.get('sections', [])
    require(len(sections) == len(expected), 'Wrong poll section count')
    visible = 0
    for section, wanted in zip(sections, expected):
        require(section.get('title') == wanted['title'] and section.get('footer') == wanted['footer'], 'Wrong poll option/footer/adder')
        rows = section.get('rows', []); require(len(rows) == len(wanted['names']), 'Wrong voter/non-voter row count')
        for index, (row, text) in enumerate(zip(rows, wanted['names'])):
            require(row.get('row') == index and rect(row.get('frame')) and type(row.get('visible')) is bool, 'Invalid poll row geometry')
            if row['visible']:
                visible += 1
                require(row.get('text') == text and row.get('selectionStyle') == 0, 'Wrong selectable voter/non-voter name')
    require(visible > 0, 'No visible source table rows')
    labels = value.get('labels', [])
    require(labels and all(rect(x.get('frame')) and type(x.get('fontSize')) in (float, int) and x['fontSize'] > 0 for x in labels), 'Missing visible native labels')
    if specification['category'] == 'accessibilityMedium':
        require(any(x['fontSize'] >= 28 for x in labels), 'Actual accessibility poll type missing')
    validate_type_metrics(value, specification)
    image = images.get(value.get('imageSha256')); require(image is not None, 'Missing/changed poll screenshot')
    image_geometry = geometry.png_geometry(image.read_bytes())
    require(image_geometry['displaySize'] == [round(x * scale) for x in size], 'Wrong poll compositor dimensions')
    return image, image_geometry


def validate_lifecycle(value, scenes, fixture, commit, images, template, manifest):
    require(value.get('schema') == 1 and value.get('pollLifecycle') == 'pending-roundtrip', 'Wrong poll lifecycle identity')
    require(value.get('doneDismissed') is True, 'Poll lifecycle Done did not dismiss')
    steps = value.get('steps', [])
    require([s.get('phase') for s in steps] == ['portrait-before', 'landscape-open', 'portrait-returned'], 'Missing/incorrect live rotation steps')
    specification = scenes['poll-pending-dark']; poll = fixture['polls']['pending']
    paired = []; generations = []; observers = []
    for step, orientation in zip(steps, ['portrait', 'landscape', 'portrait']):
        state = step['state']
        image, geometry = validate_state(state, specification, poll, commit, images, template, manifest,
            orientation=orientation, dismissed=False)
        observer = state.get('observationOwner')
        require(type(observer) is str and re.fullmatch(r'[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}', observer), 'Missing actual observation owner')
        observers.append(observer)
        generation = [state.get('presentationGeneration'), state.get('tableGeneration')]
        require(all(type(x) is int and x > 0 for x in generation), 'Missing actual source presentation/table identity')
        generations.append(generation)
        paired.append(('poll-lifecycle-' + step['phase'], image, geometry))
    require(all(x == observers[0] for x in observers), 'Source observation owner replaced during live rotation')
    require(all(x == generations[0] for x in generations), 'Source presentation/table replaced during live rotation')
    return paired


def verify(exported, output, commit, require_lifecycle=False):
    require(len(commit) == 40 and all(x in '0123456789abcdef' for x in commit), 'Exact project commit required')
    template = ROOT / 'tests/ios-chief-reference'
    fixture = json.loads((template / 'PollReferences.json').read_text())
    manifest = json.loads((ROOT / 'third_party/cmux-chief/manifest.json').read_text())
    scenes = {s['id']: s for s in fixture['scenes']}; values = {}; images = {}; lifecycles = {}
    for path in exported.rglob('*'):
        if not path.is_file() or path.stat().st_size > 32 * 1024 * 1024: continue
        data = path.read_bytes()
        if data.startswith(b'\x89PNG\r\n\x1a\n'): images[hashlib.sha256(data).hexdigest()] = path
        elif data.lstrip().startswith(b'{'):
            try: value = json.loads(data)
            except (ValueError, UnicodeError): continue
            if isinstance(value, dict) and 'pollScene' in value:
                name = value['pollScene']; require(name not in values, 'Duplicate poll scene')
                values[name] = value
            elif isinstance(value, dict) and 'pollLifecycle' in value:
                name = value['pollLifecycle']; require(name not in lifecycles, 'Duplicate poll lifecycle')
                lifecycles[name] = value
    require(set(values) == set(scenes), 'Missing/unexpected poll scenes')
    paired = []
    for name, specification in scenes.items():
        value = values[name]; source = value['provenance']; poll = fixture['polls'][specification['model']]
        image, image_geometry = validate_state(value, specification, poll, commit, images, template, manifest)
        paired.append((name, value, image, image_geometry))
    require(set(lifecycles) == ({'pending-roundtrip'} if require_lifecycle or lifecycles else set()), 'Missing/unexpected poll lifecycle')
    lifecycle_paired = []
    for value in lifecycles.values():
        lifecycle_paired.extend(validate_lifecycle(value, scenes, fixture, commit, images, template, manifest))
    output.mkdir(parents=True, exist_ok=True)
    for name, value, image, image_geometry in paired:
        shutil.copyfile(image, output / (name + '.png'))
        (output / (name + '.json')).write_text(json.dumps(value, indent=2) + '\n')
    for name, image, _ in lifecycle_paired:
        shutil.copyfile(image, output / (name + '.png'))
    for name, value in lifecycles.items():
        (output / ('poll-lifecycle-' + name + '.json')).write_text(json.dumps(value, indent=2) + '\n')
    receipt = dict(projectCommit=commit, verifierSha256=digest(Path(__file__)), revision=manifest['revision'],
        packageTree=manifest['referencePackage']['tree'], scenes=list(scenes), lifecycle=list(lifecycles),
        lifecycleImageGeometry={name: g for name, image, g in lifecycle_paired},
        imageGeometry={name: g for name, value, image, g in paired},
        scope='Actual pinned UIKit Poll Details controls, visible cells, source data-source sections and compositor frames with controlled pending/generic poll data. Offscreen cell text, live changes/votes, Chief RPC, Android/device/VoiceOver and full parity remain unverified.')
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps(receipt, indent=2))


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--exported', type=Path, required=True); p.add_argument('--output', type=Path, required=True)
    p.add_argument('--project-commit', required=True); p.add_argument('--require-lifecycle', action='store_true'); args = p.parse_args()
    verify(args.exported, args.output, args.project_commit, args.require_lifecycle)
