#!/usr/bin/env python3
"""Project independently validated native observations into a committed editor reference."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def project(canonical, source, commit, run_id, artifact_id, artifact_zip, artifact_sha):
    if not re.fullmatch('[0-9a-f]{40}', commit) or not re.fullmatch('[0-9a-f]{64}', artifact_sha):
        raise ValueError('Exact native source and artifact identities required')
    if sha(artifact_zip.read_bytes()) != artifact_sha:
        raise ValueError('Artifact archive does not match service identity')
    path = source / 'scripts/verify-ios-chief-rich-composer.py'
    spec = importlib.util.spec_from_file_location('native_rich_validation', path)
    validator = importlib.util.module_from_spec(spec); spec.loader.exec_module(validator)
    fixture = (source / 'tests/chief-rich-composer/Cases.json').read_bytes()
    cases = json.loads(fixture)['cases']
    manifest = json.loads((source / 'third_party/cmux-chief/manifest.json').read_text())
    payloads = {}; bound_files = {}; layouts = {}; profiles = {}
    for category in ['large', 'accessibilityMedium']:
        folder = canonical / category
        receipt = json.loads((folder / 'receipt.json').read_text())
        if receipt['projectCommit'] != commit or receipt['category'] != category or receipt['fixtureSha256'] != sha(fixture):
            raise ValueError('Wrong independently verified category receipt')
        if set(receipt['files']) != {c['id'] + '.json' for c in cases}:
            raise ValueError('Changed native observation inventory')
        payloads[category] = {}; bound_files[category] = {}; layouts[category] = {}; profiles[category] = {}
        for case in cases:
            name = case['id'] + '.json'; data = (folder / name).read_bytes()
            if sha(data) != receipt['files'][name]:
                raise ValueError('Changed canonical observation: ' + name)
            payload = json.loads(data)
            validator.validate(payload, case, category, commit, fixture, manifest)
            payloads[category][case['id']] = payload
            bound_files[category][name] = sha(data)
            observations = []
            for record in payload['records']:
                state = record['state']; layout = None
                if state['inputViewIdentifier'] is not None:
                    value = dict(bounds=state['paletteBounds'], controls=[
                        {k: c[k] for k in ['identifier', 'label', 'class', 'frameInPalette']} for c in state['controls']])
                    layout = sha(json.dumps(value, sort_keys=True, separators=(',', ':')).encode())
                    layouts[category][layout] = value
                observations.append(dict(step=record['step'], typing=state['typing'], attributes=state['attributes'],
                    editorBounds=state['editorBounds'], contentSize=state['contentSize'], contentOffset=state['contentOffset'],
                    paletteLayout=layout, paletteInWindow=state['paletteInWindow'],
                    effectGlyphLayers=state['effectGlyphLayers'], effectAnimatedLayers=state['effectAnimatedLayers'],
                    reduceMotion=state['reduceMotion'], boldText=state['boldText']))
            profiles[category][case['id']] = observations

    def semantic(record):
        state = record['state']
        result = dict(step=record['step'], action=record['action'], state={k: state[k] for k in [
            'text', 'selection', 'marked', 'runs', 'firstResponder', 'canUndo', 'canRedo',
            'inputViewIdentifier', 'backendDrafts', 'storeMessages']})
        result['state']['typing'] = {k: state['typing'][k] for k in ['style', 'effect']}
        result['state']['selectedControls'] = [c['identifier'] for c in state['controls'] if c['accessibilitySelected']]
        if 'performed' in record.get('observed', {}):
            result['undoOrRedoPerformed'] = record['observed']['performed']
        return result

    projected = []; window_differences = []
    for case in cases:
        a = [semantic(x) for x in payloads['large'][case['id']]['records']]
        b = [semantic(x) for x in payloads['accessibilityMedium'][case['id']]['records']]
        if a != b:
            raise ValueError('Native category semantics differ; do not collapse them: ' + case['id'])
        for x, y in zip(payloads['large'][case['id']]['records'], payloads['accessibilityMedium'][case['id']]['records']):
            if x['state']['paletteInWindow'] != y['state']['paletteInWindow']:
                window_differences.append(dict(caseID=case['id'],step=x['step'],action=x['action'],
                    large=x['state']['paletteInWindow'],accessibilityMedium=y['state']['paletteInWindow']))
        projected.append(dict(id=case['id'], style=case['style'], backend=case['backend'], records=a))
    return dict(schema=1, referenceKind='chiefRichComposerNativeProjection',
        provenance=dict(upstreamRevision=manifest['revision'], packageTree=manifest['referencePackage']['tree'],
            nativeProjectCommit=commit, runID=run_id, runURL=f'https://github.com/DocMorphic/cmux-app/actions/runs/{run_id}',
            artifactID=artifact_id, artifactSha256=artifact_sha, fixtureSha256=sha(fixture),
            nativeTestsSha256=sha((source / 'tests/ios-chief-reference/RichComposerTests.swift').read_bytes()),
            verifierSha256=sha(path.read_bytes()), projectorSha256=sha(Path(__file__).read_bytes()),
            canonicalFilesSha256=bound_files),
        categories={c: dict(actualCategory=validator.CATEGORIES[c], runtimeVersion=next(iter(payloads[c].values()))['runtimeVersion'],
                            os=next(iter(payloads[c].values()))['os']) for c in payloads},
        categorySemanticEquality=True, paletteWindowDifferences=window_differences,
        cases=projected, paletteLayouts=layouts, displayProfiles=profiles,
        scope='Actual original UIKit public-API/source-control semantic and display observations. Authored rejecting/plain-ACK backend; programmatic menu UIAction bridge. No gestures, actual IME/hardware keyboard, XCUIScreen/pixel, Chief backend, Android implementation or full parity acceptance.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--canonical', type=Path, required=True)
    parser.add_argument('--source-root', type=Path, default=ROOT)
    parser.add_argument('--project-commit', required=True)
    parser.add_argument('--run-id', type=int, required=True)
    parser.add_argument('--artifact-id', type=int, required=True)
    parser.add_argument('--artifact-zip', type=Path, required=True)
    parser.add_argument('--artifact-sha256', required=True)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    value = project(args.canonical, args.source_root, args.project_commit, args.run_id,
                    args.artifact_id, args.artifact_zip, args.artifact_sha256)
    encoded = (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + '\n').encode()
    destination = args.output or ROOT / 'tests/chief-rich-composer/NativeObservations.json'
    if args.output:
        destination.parent.mkdir(parents=True, exist_ok=True); destination.write_bytes(encoded)
    elif destination.read_bytes() != encoded:
        raise ValueError('Native observation projection differs')
    print(json.dumps(dict(output=str(destination),sha256=sha(encoded),cases=len(value['cases']),
        semanticRecords=sum(len(c['records']) for c in value['cases']),categories=list(value['categories']),
        paletteLayouts={k:len(v) for k,v in value['paletteLayouts'].items()},scope=value['scope']),indent=2))


if __name__ == '__main__': main()
