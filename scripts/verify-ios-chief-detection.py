#!/usr/bin/env python3
"""Bind actual iOS detector observations to inputs/source; not a parity verdict."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
from urllib.parse import urlsplit, parse_qs

ROOT = Path(__file__).resolve().parents[1]
LOCALES = ('en', 'de', 'es', 'fr', 'ar', 'ja', 'ko', 'zh-Hans', 'zh-Hant')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def language_matches(actual, expected):
    value = actual.lower().replace('_', '-')
    return value == expected.lower() or value.startswith(expected.lower() + '-')


def validate(payload, language, commit, fixture_bytes, manifest):
    require(payload.get('schema') == 1 and payload.get('referenceKind') == 'chiefDataDetection', 'Wrong reference kind')
    require(payload.get('fixtureSha256') == sha(fixture_bytes), 'Changed detection fixture')
    provenance = payload.get('provenance', {})
    expected = dict(upstreamRevision=manifest['revision'], packageTree=manifest['referencePackage']['tree'],
                    projectSourceCommit=commit, dataDetectionCasesSha256=sha(fixture_bytes),
                    dataDetectionTestsSha256=sha((ROOT / 'tests/ios-chief-reference/DataDetectionTests.swift').read_bytes()))
    require(all(provenance.get(k) == v for k, v in expected.items()) and provenance.get('projectSourceDirty') is False, 'Unbound or dirty source provenance')
    require(payload.get('platform') == 'iOS' and str(payload.get('runtimeVersion', '')).startswith('26.'), 'Actual iOS 26 runtime required')
    preferred = payload.get('preferredLanguages', [])
    require(isinstance(preferred, list) and preferred and isinstance(preferred[0], str) and language_matches(preferred[0], language), 'Requested language was not active')
    fixture = json.loads(fixture_bytes)
    require(payload.get('defaultTimeZone') == fixture['timeZone'], 'Wrong controlled process time zone')
    require(payload.get('referenceMode') == 'capturedCurrentClock', 'Native reference clock must be observed, not injected')
    require(payload.get('fixtureReferenceEpochMillis') == fixture['referenceEpochMillis'], 'Wrong Android reference metadata')
    require(payload.get('actionOpened') is False and payload.get('detectorTypeMask') == 2104, 'Wrong source types or action execution')
    records = payload.get('records')
    require(isinstance(records, list) and len(records) == len(fixture['samples']), 'Missing or duplicated cases')
    for record, case in zip(records, fixture['samples']):
        require(all(record.get(k) == case[v] for k, v in [('id', 'id'), ('language', 'language'), ('locale', 'locale'), ('source', 'text')]), 'Changed or reordered case')
        before, after = record.get('referenceBeforeEpochMillis'), record.get('referenceAfterEpochMillis')
        require(all(type(v) in (float, int) and math.isfinite(v) for v in (before, after)) and 0 <= after - before < 60_000, 'Missing actual reference clock bounds')
        matches = record.get('matches')
        require(isinstance(matches, list), 'Missing detector results')
        text = case['text'].encode('utf-16-le')
        previous_end = 0
        for match in matches:
            start, end = match.get('start'), match.get('end')
            require(type(start) is int and type(end) is int and previous_end <= start < end <= len(text) // 2, 'Invalid or overlapping UTF-16 range')
            try:
                substring = text[start * 2:end * 2].decode('utf-16-le')
            except UnicodeError as error:
                raise ValueError('Split Unicode scalar') from error
            require(match.get('substring') == substring, 'Range and detected text differ')
            previous_end = end
            kind = match.get('type')
            require(kind in {'date', 'address', 'phone', 'link'}, 'Unexpected entity type')
            require(match.get('rawType') == {'date': 8, 'address': 16, 'phone': 2048, 'link': 32}[kind], 'Mismatched native checking type')
            action = match.get('sourceActionURL')
            if kind == 'date':
                epoch, apple = match.get('epochMillis'), match.get('secondsSinceAppleReferenceDate')
                require(all(type(v) in (int, float) and math.isfinite(v) for v in (epoch, apple)), 'Date has no resolved timestamp')
                require(abs(epoch - (apple + 978307200) * 1000) < .1, 'Inconsistent source date epochs')
                require(action == 'calshow:' + str(math.trunc(apple)), 'Wrong source Calendar mapping')
            elif action is not None:
                require(isinstance(action, str), 'Malformed source action URL')
                url = urlsplit(action)
                if kind == 'address':
                    require(url.scheme == 'https' and url.netloc == 'maps.apple.com' and bool(parse_qs(url.query).get('q', [''])[0]), 'Wrong source Maps mapping')
                elif kind == 'phone':
                    require(url.scheme == 'tel' and bool(url.path), 'Wrong source telephone mapping')
                else:
                    require(action == match.get('matchedURL'), 'Wrong source link mapping')


def verify(exported, output, commit, locales=LOCALES):
    require(re.fullmatch('[0-9a-f]{40}', commit) is not None, 'Full immutable project commit required')
    fixture = (ROOT / 'tests/chief-data-detection/Cases.json').read_bytes()
    manifest = json.loads((ROOT / 'third_party/cmux-chief/manifest.json').read_text())
    accepted = {}
    for language in locales:
        candidates = []
        for path in (exported / language).rglob('*.json'):
            require(path.stat().st_size <= 4 * 1024 * 1024, 'Oversized attachment')
            try:
                value = json.loads(path.read_text())
            except (UnicodeError, json.JSONDecodeError):
                continue
            if isinstance(value, dict) and value.get('referenceKind') == 'chiefDataDetection':
                candidates.append((path, value))
        require(len(candidates) == 1, f'Expected one actual detector payload for {language}')
        path, payload = candidates[0]
        validate(payload, language, commit, fixture, manifest)
        accepted[language] = payload
    output.mkdir(parents=True, exist_ok=False)
    files = {}
    for language, value in accepted.items():
        data = (json.dumps(value, indent=2, ensure_ascii=False) + '\n').encode()
        name = language + '.json'; (output / name).write_bytes(data); files[name] = sha(data)
    receipt = dict(projectCommit=commit, upstreamRevision=manifest['revision'], fixtureSha256=sha(fixture),
                   languages=list(locales), casesPerLanguage=len(json.loads(fixture)['samples']), files=files,
                   scope='Actual iOS 26 detector/configuration and pinned actionURL observations. No rendered-bubble/tap, physical device, Android mapping or full parity acceptance.')
    (output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps(receipt, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--exported', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--project-commit', required=True)
    args = parser.parse_args()
    verify(args.exported, args.output, args.project_commit)
