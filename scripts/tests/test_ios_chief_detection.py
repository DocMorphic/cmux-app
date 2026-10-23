"""Export guard checks with synthetic payloads; not native detector evidence."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('chief_detection', ROOT / 'scripts/verify-ios-chief-detection.py')
VERIFY = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(VERIFY)


class DetectionExportGuards(unittest.TestCase):
    def setUp(self):
        self.fixture = (ROOT / 'tests/chief-data-detection/Cases.json').read_bytes()
        self.manifest = json.loads((ROOT / 'third_party/cmux-chief/manifest.json').read_text())
        self.commit = 'a' * 40
        digest = lambda b: hashlib.sha256(b).hexdigest()
        self.payload = dict(schema=1, referenceKind='chiefDataDetection', fixtureSha256=digest(self.fixture),
            provenance=dict(upstreamRevision=self.manifest['revision'], packageTree=self.manifest['referencePackage']['tree'],
                projectSourceCommit=self.commit, projectSourceDirty=False, dataDetectionCasesSha256=digest(self.fixture),
                dataDetectionTestsSha256=digest((ROOT / 'tests/ios-chief-reference/DataDetectionTests.swift').read_bytes())),
            platform='iOS', runtimeVersion='26.4.1', preferredLanguages=['en-DE'], defaultTimeZone='Europe/Berlin',
            referenceMode='capturedCurrentClock', fixtureReferenceEpochMillis=1792749600000,
            actionOpened=False, detectorTypeMask=2104,
            records=[dict(id=c['id'], language=c['language'], locale=c['locale'], source=c['text'],
                referenceBeforeEpochMillis=1792749600000, referenceAfterEpochMillis=1792749600001, matches=[])
                for c in json.loads(self.fixture)['samples']])

    def validate(self):
        VERIFY.validate(self.payload, 'en', self.commit, self.fixture, self.manifest)

    def test_observations_may_truthfully_contain_no_matches(self):
        self.validate()

    def test_rejects_wrong_or_dirty_source(self):
        self.payload['provenance']['projectSourceDirty'] = True
        with self.assertRaisesRegex(ValueError, 'source provenance'): self.validate()

    def test_rejects_inactive_language_and_wrong_runtime(self):
        self.payload['preferredLanguages'] = ['de-DE']
        with self.assertRaisesRegex(ValueError, 'language'): self.validate()
        self.payload['preferredLanguages'] = ['en-DE']; self.payload['runtimeVersion'] = '17.0'
        with self.assertRaisesRegex(ValueError, 'iOS 26'): self.validate()

    def test_rejects_changed_fixture_missing_cases_and_reordering(self):
        self.payload['records'][0], self.payload['records'][1] = self.payload['records'][1], self.payload['records'][0]
        with self.assertRaisesRegex(ValueError, 'reordered'): self.validate()
        self.payload['records'].pop()
        with self.assertRaisesRegex(ValueError, 'Missing or duplicated'): self.validate()

    def test_checks_utf16_including_emoji_and_split_surrogates(self):
        record = self.payload['records'][3]
        record['matches'] = [dict(start=3, end=19, substring='Friday at 5:30pm', type='date', rawType=8,
            epochMillis=1792769400000, secondsSinceAppleReferenceDate=814462200,
            sourceActionURL='calshow:814462200')]
        self.validate()
        record['matches'][0].update(start=1, end=2)
        with self.assertRaisesRegex(ValueError, 'Unicode scalar'): self.validate()

    def test_rejects_wrong_calendar_url_and_epoch_association(self):
        match = dict(start=8, end=23, substring='tomorrow at 8am', type='date', rawType=8,
            epochMillis=1792821600000, secondsSinceAppleReferenceDate=814514400, sourceActionURL='calshow:814514400')
        self.payload['records'][0]['matches'] = [match]
        self.validate()
        match['sourceActionURL'] = 'https://example.com'
        with self.assertRaisesRegex(ValueError, 'Calendar'): self.validate()
        match['epochMillis'] += 1_000
        with self.assertRaisesRegex(ValueError, 'date epochs'): self.validate()

    def test_rejects_wrong_checking_type_and_recorded_action_execution(self):
        self.payload['records'][0]['matches'] = [dict(start=8, end=23, substring='tomorrow at 8am', type='link', rawType=8, matchedURL='https://example.com', sourceActionURL='https://example.com')]
        with self.assertRaisesRegex(ValueError, 'checking type'): self.validate()
        self.payload['actionOpened'] = True
        with self.assertRaisesRegex(ValueError, 'action execution'): self.validate()


if __name__ == '__main__': unittest.main()
