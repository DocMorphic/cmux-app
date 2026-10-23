#!/usr/bin/env python3
"""Associate pinned native Markdown leaf observations with real compositor exports."""
import argparse
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import re
import shutil

ROOT = Path(__file__).resolve().parents[1]
TEMPLATES = ROOT / 'tests/ios-native-markdown-reference'
spec = importlib.util.spec_from_file_location('native_markdown_geometry', ROOT / 'scripts/verify-ios-chief-translations.py')
geometry = importlib.util.module_from_spec(spec); spec.loader.exec_module(geometry)
require = geometry.require
digest = geometry.digest


def reviewed_provenance():
    manifest = json.loads((ROOT / 'tests/native-markdown-reference/References.json').read_text())
    leaf = json.loads((TEMPLATES / 'Source.json').read_text())
    return dict(upstreamRevision=manifest['upstream'], sourceFiles=manifest['files'], leafSource=manifest['inlineSource'],
        **leaf, templateHashes={name: digest(TEMPLATES / name) for name in ('References.json', 'ReferenceApp.swift', 'ReferenceUITests.swift', 'Localizable.xcstrings')},
        preparerSha256=digest(ROOT / 'scripts/prepare-ios-native-markdown-reference.py'), context='standaloneBaseWindow')


def finite(value):
    return type(value) in (int, float) and math.isfinite(value)


def validate(page, scene, stage, fixture, commit, provenance):
    require(page.get('schema') == 1 and page.get('referenceKind') == 'nativeMarkdownObservation' and
        page.get('scene') == scene['id'] and page.get('stage') == stage, 'Wrong native Markdown observation')
    require(page.get('provenance') == provenance, 'Mixed native Markdown provenance')
    require(provenance.get('projectSourceCommit') == commit and provenance.get('projectSourceDirty') is False, 'Wrong/dirty project source')
    require(page.get('ready') is True and page.get('categoryMatches') is True and page.get('device') == scene['device'] and
        page.get('style') == scene['style'], 'Wrong actual native Markdown traits')
    require(page.get('context') == 'standaloneBaseWindow' and page.get('interfaceLevel') == 0 and
        page.get('sourceSessionCreated') is False and page.get('interceptedURLs') == [], 'Unexpected native Markdown host scope')
    require(page.get('platform') == 'iOS' and isinstance(page.get('runtimeVersion'), str) and page['runtimeVersion'], 'Missing iOS runtime')
    require(page.get('swiftUIAccessibilitySize') is (scene['category'] != 'large') and
        type(page.get('swiftUIReduceMotion')) is bool and page.get('swiftUIDirection') == ('rtl' if scene['language'] == 'ar' else 'ltr'),
        'Wrong actual native Markdown accessibility traits')
    category = 'UICTContentSizeCategoryL' if scene['category'] == 'large' else 'UICTContentSizeCategoryAccessibilityM'
    require(page.get('category') == category, 'Wrong actual native Markdown category')
    language = scene['language']
    require(page.get('bundleLocalizations', [None])[0] == language and
        any(p == language or p.startswith(language + '-') for p in page.get('preferredLanguages', [])), 'Wrong actual native Markdown language')
    size, scale = page.get('viewport'), page.get('scale')
    require(isinstance(size, list) and len(size) == 2 and all(finite(v) and v > 0 for v in size) and finite(scale) and scale > 0,
        'Invalid actual native Markdown viewport')
    require((size[0] > size[1]) == (scene['orientation'] == 'landscape'), 'Wrong actual native Markdown orientation')
    require(isinstance(page.get('safeArea'), list) and len(page['safeArea']) == 4 and all(finite(v) and v >= 0 for v in page['safeArea']),
        'Missing native Markdown safe area')
    fonts = page.get('fonts', {})
    require(set(fonts) == {'body', 'title', 'title2', 'title3', 'headline', 'caption2'} and all(
        isinstance(f, dict) and set(f) == {'pointSize', 'lineHeight', 'ascender', 'descender'} and all(finite(v) for v in f.values()) and
        f['pointSize'] > 0 and f['lineHeight'] > 0 and f['ascender'] > 0 and f['descender'] < 0 for f in fonts.values()), 'Missing measured native Markdown fonts')
    require(abs(fonts['body']['pointSize'] - (17 if scene['category'] == 'large' else 28)) < 0.01, 'Wrong actual native Markdown body size')
    colors = page.get('colors', {})
    require(set(colors) == {'background', 'label', 'secondaryLabel', 'tertiaryLabel', 'separator'} and all(
        isinstance(c, list) and len(c) == 4 and all(finite(v) and 0 <= v <= 1 for v in c) for c in colors.values()), 'Missing resolved native Markdown colors')
    target = page.get('target', {})
    require(target.get('exists') is True and target.get('hittable') is True and target.get('label') == fixture['probes'][stage] and
        geometry.rect(target.get('frame')), 'Missing visible original native Markdown target')
    x, y, w, h = target['frame']
    # Wide source code text has an intentionally wider accessibility envelope.
    require(y >= 0 and y + h <= size[1] and x < size[0] and x + w > 0, 'Native Markdown target outside viewport')
    require(page.get('captureMethod') == 'XCUIScreen.main.screenshot', 'Missing actual native Markdown compositor capture')
    return size, scale


def verify(exported, output, commit):
    require(re.fullmatch('[0-9a-f]{40}', commit), 'Exact project commit required')
    fixture = json.loads((TEMPLATES / 'References.json').read_text())
    scenes = {s['id']: s for s in fixture['scenes']}
    require(len(scenes) == len(fixture['scenes']), 'Duplicate declared native Markdown scenes')
    values, images = {}, {}
    for path in exported.rglob('*'):
        if not path.is_file() or path.stat().st_size > 32 * 1024 * 1024: continue
        raw = path.read_bytes()
        if raw.startswith(b'\x89PNG\r\n\x1a\n'): images[hashlib.sha256(raw).hexdigest()] = path
        elif raw.lstrip().startswith(b'{'):
            try: value = json.loads(raw)
            except (ValueError, UnicodeError): continue
            if isinstance(value, dict) and value.get('referenceKind') == 'nativeMarkdownScene':
                name = value.get('scene'); require(name not in values, 'Duplicate exported native Markdown scene'); values[name] = value
    require(set(values) == set(scenes), 'Missing/unexpected native Markdown scenes')
    reviewed = reviewed_provenance(); paired = []; provenance = None
    for name, scene in scenes.items():
        value = values[name]
        require(value.get('schema') == 1 and isinstance(value.get('pages'), list) and
            [p.get('stage') for p in value['pages']] == fixture['stages'], 'Missing/reordered native Markdown stages')
        for page in value['pages']:
            source = page.get('provenance', {})
            require(all(source.get(k) == v for k, v in reviewed.items()), 'Unreviewed native Markdown source/host')
            if provenance is None: provenance = source
            size, scale = validate(page, scene, page['stage'], fixture, commit, provenance)
            image = images.get(page.get('imageSha256')); require(image is not None, 'Missing/changed native Markdown compositor image')
            image_geometry = geometry.png_geometry(image.read_bytes())
            require(image_geometry['displaySize'] == [round(v * scale) for v in size], 'Wrong native Markdown compositor dimensions')
            paired.append((name + '-' + page['stage'], image, image_geometry))
    output.mkdir(parents=True, exist_ok=False)
    for name, image, _ in paired: shutil.copyfile(image, output / (name + '.png'))
    for name, value in values.items(): (output / (name + '.json')).write_text(json.dumps(value, indent=2) + '\n')
    receipt = dict(schema=1, projectCommit=commit, provenance=provenance, verifierSha256=digest(Path(__file__)),
        scenes=list(scenes), capturedPageCount=len(paired), imageSha256={name: digest(image) for name, image, _ in paired},
        imageGeometry={name: g for name, _, g in paired},
        scope='Original native SwiftUI Markdown leaf and five pure files in an authored standalone base window; actual simulator compositor/public AX/traits. Not original Feed chrome, production URL routing, Android, physical-device, transport, account or full parity acceptance.')
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps(receipt, indent=2)); return receipt


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--exported', required=True, type=Path); parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--project-commit', required=True); args = parser.parse_args()
    verify(args.exported, args.output, args.project_commit)
