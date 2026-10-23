#!/usr/bin/env python3
"""Associate actual Cloud onboarding source controls, traits and compositor exports."""
import argparse
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import re
import shutil

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('cloud_image_geometry', ROOT / 'scripts/verify-ios-chief-translations.py')
geometry = importlib.util.module_from_spec(spec); spec.loader.exec_module(geometry)
require = geometry.require
digest = geometry.digest


def validate_observation(value, scene, commit, provenance):
    require(value.get('schema') == 1 and value.get('referenceKind') == 'cloudOnboarding' and
        value.get('scene') == scene['id'], 'Wrong onboarding observation')
    require(value.get('provenance') == provenance, 'Changed onboarding provenance')
    require(provenance.get('projectSourceCommit') == commit and provenance.get('projectSourceDirty') is False,
        'Wrong/dirty onboarding project source')
    require(value.get('ready') is True and value.get('categoryMatches') is True and
        value.get('style') == scene['style'] and value.get('device') == scene['device'], 'Wrong actual onboarding traits')
    require(value.get('swiftUIAccessibilitySize') is (scene['category'] != 'large') and
        type(value.get('swiftUIReduceMotion')) is bool and value.get('swiftUIDirection') == ('rtl' if scene['language'] == 'ar' else 'ltr'),
        'Wrong actual onboarding accessibility traits')
    category = 'UICTContentSizeCategoryL' if scene['category'] == 'large' else 'UICTContentSizeCategoryAccessibilityM'
    require(value.get('category') == category, 'Wrong actual onboarding text category')
    language = scene['language']
    require(value.get('bundleLocalizations', [None])[0] == language and
        any(p == language or p.startswith(language + '-') for p in value.get('preferredLanguages', [])), 'Wrong actual onboarding language')
    require(value.get('sourceSessionCreated') is False and value.get('sourceVPNControllerCreated') is False,
        'Unexpected onboarding session/VPN scope')
    require(value.get('platform') == 'iOS' and type(value.get('runtimeVersion')) is str and value['runtimeVersion'], 'Missing iOS onboarding runtime')
    size = value.get('viewport'); scale = value.get('scale')
    require(isinstance(size, list) and len(size) == 2 and all(type(x) in (int, float) and math.isfinite(x) and x > 0 for x in size) and
        type(scale) in (int, float) and math.isfinite(scale) and scale > 0, 'Invalid onboarding viewport/scale')
    require((size[0] > size[1]) == (scene['orientation'] == 'landscape'), 'Wrong actual onboarding orientation')
    require(value.get('horizontalSizeClass') in (1, 2) and value.get('verticalSizeClass') in (1, 2), 'Missing actual onboarding size classes')
    require(set(value.get('fonts', {})) == {'body', 'largeTitle', 'subheadline'} and
        all(type(x) in (float, int) and math.isfinite(x) and x > 0 for x in value['fonts'].values()), 'Missing measured onboarding fonts')
    require(set(value.get('colors', {})) == {'blue', 'green', 'purple', 'background', 'gray2', 'gray4'} and
        all(isinstance(c, list) and len(c) == 4 and all(type(x) in (float, int) and math.isfinite(x) and 0 <= x <= 1 for x in c)
            for c in value['colors'].values()), 'Missing resolved onboarding colors')
    return size, scale


def validate_graphic(graphic, index, vertical_size_class, scale):
    require(graphic.get('identifier') == 'CloudOnboardingGraphic-' + ['workspace', 'vpn', 'key'][index] and geometry.rect(graphic.get('frame')),
        'Wrong actual source graphic')
    # The source's 1-pt .stroke is centered on its nominal 152/88-pt card.
    # Actual XCUI envelopes include that outer stroke: 153/89 pt, subject to
    # one physical pixel of quantization (observed 152 2/3 at 3x accessibility).
    dimension = (88 if vertical_size_class == 1 else 152) + 1
    require(all(abs(dimension - v) <= 1 / scale + .001 for v in graphic['frame'][2:]),
        'Wrong stroke-inclusive source graphic dimensions: ' + str(graphic['frame'][2:]))


def verify(exported, output, commit):
    require(re.fullmatch('[0-9a-f]{40}', commit), 'Exact project commit required')
    template = ROOT / 'tests/ios-cloud-onboarding-reference'
    fixture = json.loads((template / 'References.json').read_text())
    manifest = json.loads((ROOT / 'third_party/cmux-cloud/onboarding-manifest.json').read_text())
    scenes = {s['id']: s for s in fixture['scenes']}
    require(len(scenes) == len(fixture['scenes']), 'Duplicate declared onboarding scenes')
    values = {}; images = {}
    for path in exported.rglob('*'):
        if not path.is_file() or path.stat().st_size > 32 * 1024 * 1024: continue
        data = path.read_bytes()
        if data.startswith(b'\x89PNG\r\n\x1a\n'): images[hashlib.sha256(data).hexdigest()] = path
        elif data.lstrip().startswith(b'{'):
            try: value = json.loads(data)
            except (ValueError, UnicodeError): continue
            if isinstance(value, dict) and value.get('referenceKind') == 'cloudOnboardingScene':
                name = value.get('scene'); require(name not in values, 'Duplicate onboarding scene')
                values[name] = value
    require(set(values) == set(scenes), 'Missing/unexpected onboarding scenes')
    paired = []; source = None
    for name, scene in scenes.items():
        value = values[name]
        require(value.get('schema') == 1, 'Wrong onboarding scene schema')
        pages = value.get('pages', [])
        require([p.get('page') for p in pages] == [0, 1, 2], 'Missing/reordered onboarding pages')
        provenance = pages[0].get('provenance', {})
        require(provenance.get('upstreamRevision') == manifest['revision'] and provenance.get('packageTrees') == manifest['packageTrees'] and
            provenance.get('sourceFiles') == manifest['files'], 'Unreviewed onboarding source')
        for key, file in [('fixtureSha256', 'References.json'), ('appSha256', 'ReferenceApp.swift'), ('testSha256', 'ReferenceUITests.swift')]:
            require(provenance.get(key) == digest(template / file), 'Changed onboarding fixture/host/tests')
        if source is None: source = provenance
        require(source == provenance, 'Mixed onboarding provenance')
        labels = fixture['labels'][scene['language']]; progress = []
        for page in pages:
            index = page['page']; size, scale = validate_observation(page, scene, commit, provenance)
            require(page.get('completed') is False and page.get('completionCount') == 0, 'Premature onboarding completion')
            require(page.get('captureMethod') == 'XCUIScreen.main.screenshot', 'Missing actual onboarding compositor capture')
            if index == 2: require(page.get('backRoundTripVerified') is True, 'Source onboarding Back round trip missing')
            primary = page.get('primary', {})
            require(primary.get('identifier') == 'CloudOnboardingPrimaryButton' and primary.get('hittable') is True and primary.get('enabled') is True and
                geometry.rect(primary.get('frame')) and primary.get('label') == labels['mobile.cloud.onboarding.' + ('continue' if index < 2 else 'done')],
                'Missing actual localized onboarding action')
            indicator = page.get('progress', {})
            require(indicator.get('identifier') == 'CloudOnboardingProgressIndicator' and type(indicator.get('value')) is str and indicator['value'],
                'Missing actual onboarding progress')
            progress.append(indicator['value'])
            title = labels[['mobile.cloud.onboarding.workspace.title', 'mobile.cloud.vpn.title', 'mobile.cloud.onboarding.key.title'][index]]
            require(any(title in e.get('label', '') and geometry.rect(e.get('frame')) for e in page.get('elements', [])), 'Missing localized onboarding copy')
            graphic = page.get('graphic')
            if graphic is not None:
                validate_graphic(graphic, index, page['verticalSizeClass'], scale)
            if index > 0:
                secondary = page.get('secondary') or {}
                require(secondary.get('identifier') == 'CloudOnboardingSecondaryButton' and secondary.get('label') == labels['mobile.cloud.onboarding.back'] and
                    secondary.get('hittable') is True and geometry.rect(secondary.get('frame')), 'Missing actual onboarding Back action')
            image = images.get(page.get('imageSha256')); require(image is not None, 'Missing/changed onboarding compositor image')
            image_geometry = geometry.png_geometry(image.read_bytes())
            require(image_geometry['displaySize'] == [round(x * scale) for x in size], 'Wrong onboarding compositor dimensions')
            paired.append((name + '-page-' + str(index), image, image_geometry))
        require(len(set(progress)) == 3, 'Source onboarding progress did not advance')
        for key in ('completed', 'skipped'):
            final = value.get(key, {})
            validate_observation(final, scene, commit, provenance)
            require(final.get('completed') is True and final.get('completionCount') == 1, 'Source onboarding completion/Skip failed')
    output.mkdir(parents=True, exist_ok=False)
    for name, image, _ in paired: shutil.copyfile(image, output / (name + '.png'))
    for name, value in values.items(): (output / (name + '.json')).write_text(json.dumps(value, indent=2) + '\n')
    receipt = dict(schema=1, projectCommit=commit, verifierSha256=digest(Path(__file__)), revision=manifest['revision'],
        packageTrees=manifest['packageTrees'], sourceFiles=manifest['files'], scenes=list(scenes), capturedPageCount=len(paired),
        imageGeometry={name: g for name, _, g in paired},
        imageSha256={name: digest(image) for name, image, _ in paired},
        graphicFrameScope='Public XCUI stroke-inclusive image envelope: nominal 152/88 pt plus the centered 1-pt stroke, with at most one physical pixel quantization. Not a synthesized nominal layout frame.',
        scope='Unchanged source onboarding/backdrop leaves, actual iOS simulator compositor and public controls/traits, nil session/VPN controllers. This is not full CloudUI, real VPN/service, Android, physical-device or full parity acceptance.')
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps(receipt, indent=2))
    return receipt


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--exported', required=True, type=Path); parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--project-commit', required=True)
    args = parser.parse_args()
    verify(args.exported, args.output, args.project_commit)
