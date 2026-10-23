#!/usr/bin/env python3
"""Attribute unchanged original app UI-test captures; not Android parity acceptance."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shutil
import subprocess

PIN = 'a5911a1f92f3eeb11c4d7f3e2ef89204718c5009'
EXPECTED = {
    'testFeedFullTextReadingAndRetry': ['feed-full-text-entry', 'feed-markdown-heading-list-code',
        'feed-full-text-final-paragraph', 'feed-scoped-search-and-toolbar'],
    'testFeedRowTapOpensItsDestination': ['feed-row-tap-opened-destination'],
    'testFeedStartsBelowToolbar': ['feed-first-row-spacing'],
}

def require(value, message):
    if not value: raise ValueError(message)

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def verify(root, upstream, output, driver):
    require(re.fullmatch('[a-f0-9]{40}', driver), 'Exact driver commit required')
    execution = json.loads((root/'execution.json').read_text())
    require(execution.get('upstream') == PIN and execution.get('driver') == driver and
        execution.get('trackedSourceUnchanged') is True and execution.get('testSucceeded') is True and
        execution.get('selectedOriginalMethods') == dict.fromkeys(EXPECTED, True), 'Wrong original execution binding')
    require((root/'upstream-revision.txt').read_text().strip() == PIN and
        (root/'driver-revision.txt').read_text().strip() == driver and
        not (root/'source-status-before.txt').read_text().strip() and
        not (root/'source-status-after.txt').read_text().strip(), 'Dirty/mixed original source')
    expected_index = {}
    tree = subprocess.check_output(['git', 'ls-tree', '-r', PIN], cwd=upstream, text=True)
    for line in tree.splitlines():
        fields, path = line.split('\t', 1); mode, kind, digest = fields.split()
        expected_index[path] = (mode, digest)
    actual_index = {}
    for line in (root/'source-index.txt').read_text().splitlines():
        fields, path = line.split('\t', 1); mode, digest, stage = fields.split()
        require(stage == '0' and path not in actual_index, 'Conflicted/duplicate original index')
        actual_index[path] = (mode, digest)
    require(actual_index == expected_index, 'Captured source index differs from the pinned complete tree')
    log = (root/'test.log').read_text()
    require('** TEST SUCCEEDED **' in log and not re.search(r"Test Case .* failed|Test Suite .* failed|Assertion Failure", log), 'Original test failure/missing final success')
    times = {}
    for method in EXPECTED:
        matches = re.findall(r"Test Case '-\[cmuxUITests\.cmuxUITests " + method + r"\]' passed \(([0-9.]+) seconds\)\.", log)
        require(len(matches) == 1, 'Missing/duplicate selected original execution: '+method)
        times[method] = float(matches[0])
    device = json.loads((root/'selected-simulator.json').read_text())
    require(device.get('name') == 'iPhone 17 Pro', 'Unreviewed source device profile')
    spec = importlib.util.spec_from_file_location('context_png_geometry', Path(__file__).with_name('verify-ios-native-markdown.py'))
    geometry = importlib.util.module_from_spec(spec); spec.loader.exec_module(geometry)
    groups = json.loads((root/'exported/manifest.json').read_text())
    require(len(groups) == len(EXPECTED), 'Missing/unexpected original method groups')
    images, seen = {}, set()
    for group in groups:
        method = group.get('testIdentifierURL', '').rsplit('/', 1)[-1]
        require(method in EXPECTED and method not in seen and
            group.get('testIdentifier') == 'cmuxUITests/'+method+'()', 'Wrong/duplicate source method association')
        seen.add(method)
        pngs = [a for a in group.get('attachments', []) if a.get('exportedFileName', '').endswith('.png')]
        require(len(pngs) == len(EXPECTED[method]), 'Missing/unexpected original method screenshots')
        for name in EXPECTED[method]:
            matches = [a for a in pngs if re.fullmatch(re.escape(name)+r'_0_[A-F0-9-]{36}\.png', a.get('suggestedHumanReadableName', ''))]
            require(len(matches) == 1, 'Missing/duplicate named compositor attachment: '+name)
            attachment = matches[0]; filename = attachment['exportedFileName']
            require(Path(filename).name == filename and attachment.get('isAssociatedWithFailure') is False and
                attachment.get('deviceId') == device['udid'] and attachment.get('deviceName') == device['name'], 'Wrong capture/device/failure association')
            path = root/'exported'/filename
            require(path.is_file(), 'Missing original screenshot')
            info = geometry.geometry.png_geometry(path.read_bytes())
            require(info['displaySize'] == [1206, 2622], 'Unreviewed original viewport')
            require(filename not in [value['file'] for value in images.values()], 'Reused attachment file across original states')
            images[name] = dict(file=filename, sha256=sha(path), method=method, geometry=info)
    require(seen == set(EXPECTED), 'Incomplete original test attribution')
    output.mkdir(parents=True, exist_ok=False)
    for name, image in images.items(): shutil.copyfile(root/'exported'/image['file'], output/(name+'.png'))
    receipt = dict(upstream=PIN, driver=driver, indexedOriginalPaths=len(actual_index), methods=times, images=images,
        inputHashes={name:sha(root/name) for name in ['execution.json','source-index.txt','source-status-before.txt','source-status-after.txt','test.log','selected-simulator.json','exported/manifest.json']},
        verifierSha256=sha(Path(__file__)), scope='Whole unchanged original application and original Feed UI tests with official synthetic preview data. Source index, exact selected execution and compositor attachments attributed. Not a real user account/transport, all original features/locales, Android, physical devices or full parity acceptance.')
    (output/'receipt.json').write_text(json.dumps(receipt, indent=2)+'\n')
    return receipt

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', required=True, type=Path); parser.add_argument('--upstream', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path); parser.add_argument('--driver', required=True)
    args = parser.parse_args()
    print(json.dumps(verify(args.input, args.upstream, args.output, args.driver), indent=2))
