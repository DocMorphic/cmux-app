#!/usr/bin/env python3
"""Preserve independently verified mounted native system-font observations."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]
def sha(data): return hashlib.sha256(data).hexdigest()


def project(args):
    if not re.fullmatch('[0-9a-f]{40}', args.project_commit) or not re.fullmatch('[0-9a-f]{64}', args.artifact_sha256):
        raise ValueError('Exact native source and service artifact identities required')
    if args.run_id <= 0 or args.artifact_id <= 0 or sha(args.artifact_zip.read_bytes()) != args.artifact_sha256:
        raise ValueError('Unbound service artifact')
    source = args.source_root; path = source / 'scripts/verify-ios-chief-rich-composer.py'
    spec = importlib.util.spec_from_file_location('global_fonts_native_verifier', path)
    validator = importlib.util.module_from_spec(spec); spec.loader.exec_module(validator)
    fixture = (source / 'tests/chief-rich-composer/GlobalFontCases.json').read_bytes()
    cases = json.loads(fixture)['cases']; manifest = json.loads((source / 'third_party/cmux-chief/manifest.json').read_text())
    receipt_data = (args.canonical / 'receipt.json').read_bytes(); receipt = json.loads(receipt_data)
    bridge_data = args.bridge_events.read_bytes(); bridge = json.loads(bridge_data)
    if (receipt['projectCommit'] != args.project_commit or receipt['category'] != 'large' or receipt['probe'] != 'globalFonts' or
        receipt['fixtureSha256'] != sha(fixture) or receipt['bridgeEventsSha256'] != sha(bridge_data) or
        set(receipt['files']) != {c['id'] + '.json' for c in cases}):
        raise ValueError('Wrong independently verified global font receipt')
    projected = []; files = {}; used = []; runtime = None
    with zipfile.ZipFile(args.artifact_zip) as archive:
        if archive.read('rich-composer-global-font-events.json') != bridge_data or archive.read('rich-composer-canonical/large/receipt.json') != receipt_data:
            raise ValueError('Bridge/canonical receipt differs from service archive')
        for case in cases:
            name = case['id'] + '.json'; data = (args.canonical / name).read_bytes()
            if sha(data) != receipt['files'][name] or archive.read('rich-composer-canonical/large/' + name) != data:
                raise ValueError('Changed native canonical payload: ' + name)
            payload = json.loads(data)
            validator.validate(payload, case, 'large', args.project_commit, fixture, manifest, system_font_cases=True, bridge=bridge)
            runtime = dict(version=payload['runtimeVersion'], os=payload['os']); files[name] = sha(data)
            records = []
            for row in payload['records']:
                state = row['state']; observed = row.get('observed', {})
                record = dict(step=row['step'], action=row['action'], state={k: state[k] for k in [
                    'text','selection','marked','runs','firstResponder','canUndo','canRedo','inputViewIdentifier',
                    'typing','attributes','actualCategory','controllerCategory','editorCategory','backendDrafts','storeMessages',
                    'editorBounds','contentSize','paletteInWindow']})
                record['state']['selectedControls'] = [c['identifier'] for c in state['controls'] if c['accessibilitySelected']]
                if 'performed' in observed: record['undoOrRedoPerformed'] = observed['performed']
                if 'bridge' in observed:
                    record['bridgeEventID'] = observed['bridge']['id']; used.append(record['bridgeEventID'])
                records.append(record)
            projected.append(dict(id=case['id'], style=case['style'], backend=case['backend'], records=records))
    if len(set(used)) != len(used) or set(used) != {e['id'] for e in bridge['events']}:
        raise ValueError('Unmatched native/host event inventory')
    return dict(schema=1, referenceKind='chiefRichComposerGlobalFontProjection',
        provenance=dict(nativeProjectCommit=args.project_commit, upstreamRevision=manifest['revision'], packageTree=manifest['referencePackage']['tree'],
            runID=args.run_id, artifactID=args.artifact_id, artifactSha256=args.artifact_sha256, fixtureSha256=sha(fixture),
            nativeTestsSha256=sha((source/'tests/ios-chief-reference/RichComposerTests.swift').read_bytes()),
            verifierSha256=sha(path.read_bytes()), bridgeDriverSha256=bridge['driverSha256'], bridgeEventsSha256=sha(bridge_data),
            canonicalFilesSha256=files, projectorSha256=sha(Path(__file__).read_bytes())),
        runtime=runtime, startingCategory=validator.CATEGORIES['large'], systemEvents=bridge['events'],
        systemCategoryRestored=bridge['restored'], cases=projected,
        scope='Original pinned UIKit composer/Store under actual mounted global Simulator category changes; same editor identity, public text/menu/control actions and authored in-memory rejecting backend. No rendered pixels, physical IME/hardware keyboard, real Chief backend, Android implementation or full app parity acceptance.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--canonical', type=Path, required=True)
    parser.add_argument('--source-root', type=Path, required=True)
    parser.add_argument('--project-commit', required=True)
    parser.add_argument('--run-id', type=int, required=True)
    parser.add_argument('--artifact-id', type=int, required=True)
    parser.add_argument('--artifact-zip', type=Path, required=True)
    parser.add_argument('--artifact-sha256', required=True)
    parser.add_argument('--bridge-events', type=Path, required=True)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args(); value = project(args)
    data = (json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + '\n').encode()
    destination = args.output or ROOT/'tests/chief-rich-composer/GlobalFontObservations.json'
    if args.output: destination.write_bytes(data)
    elif destination.read_bytes() != data: raise ValueError('Committed native font observations differ')
    print(json.dumps(dict(output=str(destination), sha256=sha(data), cases=len(value['cases']),
        records=sum(len(c['records']) for c in value['cases']), systemEvents=len(value['systemEvents'])),indent=2))
