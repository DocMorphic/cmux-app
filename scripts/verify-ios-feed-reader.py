#!/usr/bin/env python3
"""Associate original full-sheet compositor evidence with pinned whole source and host."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shutil

ROOT = Path(__file__).resolve().parents[1]
TEMPLATES = ROOT / 'tests/ios-feed-reader-reference'
def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value
native = module('feed_native_geometry', ROOT / 'scripts/verify-ios-native-markdown.py')
preparer = module('feed_preparer', ROOT / 'scripts/prepare-ios-feed-reader-reference.py')
require, digest, finite, geometry = native.require, native.digest, native.finite, native.geometry


def reviewed_provenance():
    manifest = json.loads((TEMPLATES / 'Source.json').read_text())
    names = ('ReferenceApp.swift', 'ReferenceUITests.swift', 'References.json', 'OriginalSheetAdapter.swift', 'Source.json')
    return dict(upstreamRevision=manifest['upstream'], sourceFiles=manifest['files'],
        templateHashes={n: digest(TEMPLATES / n) for n in names},
        packageManifestSha256=hashlib.sha256(preparer.PACKAGE.encode()).hexdigest(),
        preparerSha256=digest(ROOT / 'scripts/prepare-ios-feed-reader-reference.py'), context='presentedOriginalFullTextSheet', scope=manifest['scope'])


def traits(page, scene, commit, provenance, probe):
    require(page.get('schema') == 1 and page.get('referenceKind') == 'originalFeedFullTextObservation' and
        page.get('scene') == scene['id'] and page.get('probe') == probe, 'Wrong full-sheet observation')
    require(page.get('provenance') == provenance and provenance.get('projectSourceCommit') == commit and
        provenance.get('projectSourceDirty') is False, 'Mixed/wrong/dirty full-sheet source')
    require(page.get('context') == 'presentedOriginalFullTextSheet' and page.get('sourceSessionCreated') is False and
        page.get('interceptedURLs') == [], 'Unexpected full-sheet host scope')
    require(page.get('ready') is True and page.get('categoryMatches') is True and page.get('device') == scene['device'] and
        page.get('style') == scene['style'], 'Wrong actual full-sheet traits')
    # Measure actual level: compact-height adaptive sheets may differ from portrait.
    require(type(page.get('interfaceLevel')) is int and page['interfaceLevel'] in (0, 1), 'Missing actual sheet interface level')
    require(page.get('platform') == 'iOS' and isinstance(page.get('runtimeVersion'), str) and page['runtimeVersion'], 'Missing runtime')
    require(page.get('swiftUIDirection') == ('rtl' if scene['language'] == 'ar' else 'ltr') and
        page.get('swiftUIAccessibilitySize') is (scene['category'] != 'large'), 'Wrong actual sheet direction/type')
    require(page.get('category') == ('UICTContentSizeCategoryL' if scene['category'] == 'large' else 'UICTContentSizeCategoryAccessibilityM') and
        finite(page.get('bodyPointSize')) and abs(page['bodyPointSize'] - (17 if scene['category'] == 'large' else 28)) < .01, 'Wrong measured sheet text size')
    require(page.get('shellLocalizations', [None])[0] == scene['language'] and any(
        p == scene['language'] or p.startswith(scene['language'] + '-') for p in page.get('preferredLanguages', [])), 'Wrong original module localization')
    size, scale = page.get('viewport'), page.get('scale')
    require(isinstance(size, list) and len(size) == 2 and all(finite(v) and v > 0 for v in size) and
        finite(scale) and scale > 0 and ((size[0] > size[1]) == (scene['orientation'] == 'landscape')), 'Wrong sheet viewport/orientation')
    require(isinstance(page.get('safeArea'), list) and len(page['safeArea']) == 4 and all(finite(v) and v >= 0 for v in page['safeArea']), 'Missing sheet safe area')
    colors = page.get('colors', {})
    require(set(colors) == {'background', 'label', 'secondaryLabel', 'tertiaryLabel', 'link', 'separator'} and all(
        isinstance(c, list) and len(c) == 4 and all(finite(v) and -1e-6 <= v <= 1+1e-6 for v in c) for c in colors.values()), 'Invalid sheet colors')
    window_chrome(page, scale)
    return size, scale


def window_chrome(page, scale):
    """Require actual public window/bar layout observations, without inventing margins."""
    chrome = page.get('windowChrome')
    require(isinstance(chrome, dict) and set(chrome) == {'sceneOrientation', 'horizontalSizeClass', 'verticalSizeClass', 'statusBar'},
        'Missing/invalid window chrome observations')
    def enum(value, allowed, message):
        require(type(value) is int and value in allowed, message)
    enum(chrome['sceneOrientation'], range(5), 'Invalid actual scene orientation')
    for key in ('horizontalSizeClass', 'verticalSizeClass'):
        enum(chrome[key], range(3), 'Invalid actual size class')
    def box(value, message):
        require(isinstance(value, list) and len(value) == 4 and all(finite(v) for v in value) and
            value[2] >= 0 and value[3] >= 0, message)
    status = chrome['statusBar']
    require(isinstance(status, dict) and set(status) == {'available', 'hidden', 'frameInScene', 'style'} and
        status['available'] is True and type(status['hidden']) is bool, 'Missing actual status-bar manager')
    box(status['frameInScene'], 'Invalid actual status-bar frame')
    enum(status['style'], range(4), 'Invalid actual status-bar style')
    bars, metrics = page.get('navigationBars'), page.get('navigationBarMetrics')
    require(isinstance(bars, list) and isinstance(metrics, list) and len(bars) == len(metrics),
        'Missing/unbound navigation layout observations for navigation bar')
    keys = {'className', 'frame', 'bounds', 'safeArea', 'margins', 'directionalMargins', 'guideFrame',
        'effectiveDirection', 'preservesSuperviewMargins', 'insetsMarginsFromSafeArea', 'horizontalSizeClass', 'verticalSizeClass'}
    def layout(value, root=False):
        require(isinstance(value, dict) and set(value) == keys | ({'children'} if root else set()) and
            isinstance(value['className'], str) and value['className'], 'Invalid public navigation layout shape')
        for key in ('frame', 'bounds', 'guideFrame'): box(value[key], 'Invalid public navigation layout rectangle')
        for key in ('safeArea', 'margins', 'directionalMargins'):
            v = value[key]
            require(isinstance(v, list) and len(v) == 4 and all(finite(n) for n in v) and
                (key != 'safeArea' or all(n >= 0 for n in v)), 'Invalid public navigation layout insets')
        enum(value['effectiveDirection'], range(2), 'Invalid public navigation direction')
        for key in ('horizontalSizeClass', 'verticalSizeClass'):
            enum(value[key], range(3), 'Invalid public navigation size class')
        for key in ('preservesSuperviewMargins', 'insetsMarginsFromSafeArea'):
            require(type(value[key]) is bool, 'Invalid public navigation margin behavior')
        if root:
            require(isinstance(value['children'], list), 'Missing public navigation child layout')
            for child in value['children']: layout(child)
    for bar, metric in zip(bars, metrics):
        require(geometry.rect(bar), 'Invalid observed navigation frame')
        layout(metric, root=True)
        require(all(abs(a-b) <= 1/scale+1e-6 for a,b in zip(bar, metric['frame'])),
            'Public navigation layout is not bound to observed bar frame')


def public_controls(page, size):
    labels, buttons = page.get('visibleLabels'), page.get('visibleButtons')
    require(isinstance(labels, list) and isinstance(buttons, list), 'Missing public label/button observations')
    def frame(value):
        require(geometry.rect(value), 'Invalid public control frame')
        x,y,w,h = value
        require(x < size[0] and x+w > 0 and y < size[1] and y+h > 0, 'Public control outside viewport')
    def font(value):
        require(isinstance(value, dict) and isinstance(value.get('text'), str) and value['text'] and
            isinstance(value.get('fontName'), str) and value['fontName'] and
            all(finite(value.get(key)) and value[key] > 0 for key in ('pointSize','lineHeight')), 'Invalid public control font')
    def label(value):
        font(value); frame(value.get('frame'))
        require(type(value.get('adjustsForContentSizeCategory')) is bool, 'Missing public label type behavior')
        if 'color' in value:
            color = value['color']
            require(isinstance(color, list) and len(color) == 4 and
                all(finite(v) and -1e-6 <= v <= 1+1e-6 for v in color), 'Invalid public label color')
    for value in labels: label(value)
    for value in buttons:
        require(isinstance(value, dict) and all(isinstance(value.get(k), str) for k in ('title','accessibilityLabel')) and
            isinstance(value.get('attributedFonts'), list), 'Invalid public button observation')
        frame(value.get('frame'))
        if 'titleLabel' in value: label(value['titleLabel'])
        for observed in value['attributedFonts']: font(observed)


def visible(value, label, size, require_hit=True):
    require(value.get('exists') is True and type(value.get('hittable')) is bool and (not require_hit or value['hittable'] is True) and value.get('label') == label and
        geometry.rect(value.get('frame')), 'Missing visible original sheet control/content')
    x, y, w, h = value['frame']
    require(y >= 0 and y+h <= size[1] and x < size[0] and x+w > 0, 'Original sheet target outside viewport')


def navigation_typography(page, scene, size, scale):
    bars, labels = page.get('navigationBars'), page.get('navigationLabels')
    require(isinstance(bars, list) and bars and all(geometry.rect(r) for r in bars), 'Missing measured original navigation bar')
    require(isinstance(labels, list), 'Missing original navigation labels')
    matches = [value for value in labels if isinstance(value, dict) and value.get('text') == page['title']['label']]
    require(len(matches) == 1, 'Missing/ambiguous original title font observation')
    value = matches[0]
    require(finite(value.get('pointSize')) and abs(value['pointSize'] - (17 if scene['category'] == 'large' else 21)) < .01 and
        finite(value.get('lineHeight')) and value['pointSize'] <= value['lineHeight'] < value['pointSize']*2 and
        isinstance(value.get('fontName'), str) and value['fontName'] and value.get('adjustsForContentSizeCategory') is True,
        'Wrong measured original title typography')
    require(geometry.rect(value.get('frame')) and all(abs(a-b) <= 1/scale+1e-6 for a,b in zip(value['frame'], page['title']['frame'])),
        'Original UIKit title font is not bound to its independent AX target')
    x,y,w,h = value['frame']
    require(any(x >= bx-1/scale and y >= by-1/scale and x+w <= bx+bw+1/scale and y+h <= by+bh+1/scale for bx,by,bw,bh in bars),
        'Original title font outside navigation bar')


def verify(exported, output, commit):
    require(re.fullmatch('[0-9a-f]{40}', commit), 'Exact project commit required')
    fixture = json.loads((TEMPLATES / 'References.json').read_text()); scenes = {s['id']: s for s in fixture['scenes']}
    require(len(scenes) == len(fixture['scenes']), 'Duplicate declared sheet scenes')
    values, images = {}, {}
    for path in exported.rglob('*'):
        if not path.is_file() or path.stat().st_size > 32*1024*1024: continue
        raw = path.read_bytes()
        if raw.startswith(b'\x89PNG\r\n\x1a\n'): images[hashlib.sha256(raw).hexdigest()] = path
        elif raw.lstrip().startswith(b'{'):
            try: value = json.loads(raw)
            except (ValueError, UnicodeError): continue
            if isinstance(value, dict) and value.get('referenceKind') == 'originalFeedFullTextScene':
                name = value.get('scene'); require(name not in values, 'Duplicate exported sheet scene'); values[name] = value
    require(set(values) == set(scenes), 'Missing/unexpected full-sheet scenes')
    provenance = None; reviewed = reviewed_provenance(); pairs = []; controls = {}; chrome = {}; layouts = {}; dismissals = {}
    for name, scene in scenes.items():
        value = values[name]
        require(value.get('schema') == 1 and isinstance(value.get('pages'), list) and
            [p.get('stage') for p in value['pages']] == fixture['states'], 'Missing/reordered sheet states')
        require(len({p.get('imageSha256') for p in value['pages']}) == len(fixture['states']), 'Reused image across different sheet states')
        for page in value['pages']:
            source = page.get('provenance', {})
            require(all(source.get(k) == v for k, v in reviewed.items()), 'Unreviewed original sheet source/host')
            if provenance is None: provenance = source
            stage = page['stage']; closed = stage == 'closed'
            size, scale = traits(page, scene, commit, provenance, 'root' if closed else 'sheet')
            chrome[name+'-'+stage] = page['windowChrome']
            layouts[name+'-'+stage] = page['navigationBarMetrics']
            public_controls(page, size)
            require(page.get('mode') == ('loading' if stage == 'loading' else 'retry') and
                page.get('loadAttempts') == (1 if stage in ('loading', 'error') else 2), 'Wrong original load/retry sequence')
            expected = 'Open reference sheet' if closed else fixture['probes']['top'] if stage == 'loaded' else fixture['uiStrings'][stage if stage == 'loading' else 'unavailable'][scene['language']]
            if stage == 'loading':
                require(page.get('loadingActivityIndicator') is True and expected in page.get('target', {}).get('label', ''), 'Missing native loading Activity Indicator')
                # UIKit prepends its localized in-progress accessibility phrase.
                visible(page['target'], page['target']['label'], size, require_hit=False)
            else:
                visible(page.get('target', {}), expected, size)
            if closed:
                require(page.get('dismissed') is True and page.get('close', {}).get('exists') is False, 'Original dismissal unproven')
            else:
                visible(page.get('close', {}), fixture['uiStrings']['close'][scene['language']], size)
                visible(page.get('title', {}), fixture['uiStrings']['title'][scene['language']], size)
                navigation_typography(page, scene, size, scale)
                cx,cy,cw,ch = page['close']['frame']
                def inside_close(label):
                    x,y,w,h = label['frame']; tolerance = 1/scale + 1e-6
                    return label['text'] == page['close']['label'] and x >= cx-tolerance and y >= cy-tolerance and x+w <= cx+cw+tolerance and y+h <= cy+ch+tolerance
                controls[name+'-'+stage] = dict(labels=len(page['visibleLabels']), buttons=len(page['visibleButtons']),
                    closeLabelCandidates=[label for label in page['visibleLabels'] if inside_close(label)])
                require(page.get('unexpectedSourceControl') is False, 'Unexpected authored Source control')
            require(page.get('captureMethod') == 'XCUIScreen.main.screenshot', 'Missing actual sheet compositor capture')
            image = images.get(page.get('imageSha256')); require(image is not None, 'Missing/changed sheet compositor image')
            info = geometry.png_geometry(image.read_bytes())
            require(info['displaySize'] == [round(v*scale) for v in size], 'Wrong full-sheet compositor dimensions')
            pairs.append((name+'-'+stage, image, info))
        dismissal = value.get('loadingDismissal', {})
        dismissal_size, _ = traits(dismissal, scene, commit, provenance, 'root')
        public_controls(dismissal, dismissal_size)
        dismissals[name] = dismissal['windowChrome']
        require(dismissal.get('mode') == 'loading' and dismissal.get('dismissed') is True and
            dismissal.get('loadAttempts') == 1 and dismissal.get('loadCancellations') == 1, 'Loading cancellation/dismissal unproven')
    output.mkdir(parents=True, exist_ok=False)
    for name, image, _ in pairs: shutil.copyfile(image, output/(name+'.png'))
    for name, value in values.items(): (output/(name+'.json')).write_text(json.dumps(value, indent=2)+'\n')
    receipt = dict(schema=1, projectCommit=commit, provenance=provenance, verifierSha256=digest(Path(__file__)), scenes=list(scenes),
        capturedPageCount=len(pairs), imageSha256={n:digest(p) for n,p,_ in pairs}, imageGeometry={n:g for n,_,g in pairs},
        publicControlObservations=controls,
        windowChromeObservations=chrome, navigationLayoutObservations=layouts, loadingDismissalWindowChrome=dismissals,
        scope='Whole unchanged original Feed full-text view/model/renderer declarations in an authored offline sheet presenter. Actual simulator compositor, localized controls, loading/retry/cancellation and measured traits. Not original account/transport/URL router, Android, physical-device or full app parity acceptance.')
    (output/'receipt.json').write_text(json.dumps(receipt, indent=2)+'\n'); print(json.dumps(receipt, indent=2)); return receipt

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--exported', required=True, type=Path); parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--project-commit', required=True); args = parser.parse_args(); verify(args.exported, args.output, args.project_commit)
