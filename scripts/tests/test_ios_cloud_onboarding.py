"""Adversarial export association guards; synthetic inputs here are not native UI evidence."""
import copy
from contextlib import redirect_stdout
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from test_ios_chief_reference import png

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('onboarding_verifier', ROOT / 'scripts/verify-ios-cloud-onboarding.py')
V = importlib.util.module_from_spec(spec); spec.loader.exec_module(V)


class OnboardingExportGuards(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.export = Path(self.tmp.name) / 'export'; self.export.mkdir()
        self.output = Path(self.tmp.name) / 'canonical'; self.commit = 'a' * 40
        template = ROOT / 'tests/ios-cloud-onboarding-reference'
        self.fixture = json.loads((template / 'References.json').read_text())
        manifest = json.loads((ROOT / 'third_party/cmux-cloud/onboarding-manifest.json').read_text())
        provenance = dict(projectSourceCommit=self.commit, projectSourceDirty=False,
            upstreamRevision=manifest['revision'], packageTrees=manifest['packageTrees'], sourceFiles=manifest['files'],
            fixtureSha256=V.digest(template / 'References.json'), appSha256=V.digest(template / 'ReferenceApp.swift'),
            testSha256=V.digest(template / 'ReferenceUITests.swift'))
        for index, scene in enumerate(self.fixture['scenes']):
            labels = self.fixture['labels'][scene['language']]
            size = [80, 40] if scene['orientation'] == 'landscape' else [40, 80]
            base = dict(schema=1, referenceKind='cloudOnboarding', scene=scene['id'], provenance=provenance,
                ready=True, categoryMatches=True, style=scene['style'], device=scene['device'],
                swiftUIAccessibilitySize=scene['category'] != 'large', swiftUIReduceMotion=False,
                swiftUIDirection='rtl' if scene['language'] == 'ar' else 'ltr',
                category='UICTContentSizeCategoryL' if scene['category'] == 'large' else 'UICTContentSizeCategoryAccessibilityM',
                bundleLocalizations=[scene['language']], preferredLanguages=[scene['language']],
                sourceSessionCreated=False, sourceVPNControllerCreated=False,
                platform='iOS', runtimeVersion='Synthetic guard test only', viewport=size, scale=1,
                horizontalSizeClass=1, verticalSizeClass=2, fonts=dict(body=17, largeTitle=34, subheadline=15),
                colors={c: [0, 0, 0, 1] for c in ['blue', 'green', 'purple', 'background', 'gray2', 'gray4']})
            pages = []
            for page in range(3):
                image = png(index * 3 + page, *size)
                (self.export / (scene['id'] + str(page) + '.png')).write_bytes(image)
                state = copy.deepcopy(base)
                title = labels[['mobile.cloud.onboarding.workspace.title', 'mobile.cloud.vpn.title', 'mobile.cloud.onboarding.key.title'][page]]
                state.update(page=page, imageSha256=hashlib.sha256(image).hexdigest(), completed=False, completionCount=0,
                    primary=dict(identifier='CloudOnboardingPrimaryButton', label=labels['mobile.cloud.onboarding.' + ('continue' if page < 2 else 'done')],
                        hittable=True, enabled=True, frame=[0, 0, 30, 10]),
                    progress=dict(identifier='CloudOnboardingProgressIndicator', value=str(page)),
                    elements=[dict(label=title, frame=[0, 10, 30, 10])],
                    secondary=dict(identifier='CloudOnboardingSecondaryButton', label=labels['mobile.cloud.onboarding.back'], hittable=True, frame=[0, 20, 30, 10]))
                pages.append(state)
            final = copy.deepcopy(base); final.update(completed=True, completionCount=1)
            payload = dict(schema=1, referenceKind='cloudOnboardingScene', scene=scene['id'], pages=pages, completed=final, skipped=final)
            (self.export / (scene['id'] + '.json')).write_text(json.dumps(payload))

    def verify(self):
        with redirect_stdout(io.StringIO()): return V.verify(self.export, self.output, self.commit)

    def rejects(self, change, message):
        path = self.export / (self.fixture['scenes'][0]['id'] + '.json')
        value = json.loads(path.read_text()); change(value); path.write_text(json.dumps(value))
        with self.assertRaisesRegex(ValueError, message): self.verify()
        self.assertFalse(self.output.exists())

    def test_complete_inventory_is_associated(self):
        receipt = self.verify()
        self.assertEqual(receipt['capturedPageCount'], 39)
        self.assertEqual(len(list(self.output.glob('*.png'))), 39)

    def test_missing_scene_is_rejected(self):
        (self.export / (self.fixture['scenes'][0]['id'] + '.json')).unlink()
        with self.assertRaisesRegex(ValueError, 'Missing/unexpected'): self.verify()

    def test_unreviewed_source_is_rejected(self):
        self.rejects(lambda v: v['pages'][0]['provenance'].update(upstreamRevision='f' * 40), 'Unreviewed')

    def test_wrong_project_or_dirty_source_is_rejected(self):
        self.rejects(lambda v: v['pages'][0]['provenance'].update(projectSourceDirty=True), 'Wrong/dirty')

    def test_missing_image_is_rejected(self):
        self.rejects(lambda v: v['pages'][0].update(imageSha256='f' * 64), 'Missing/changed')

    def test_reordered_page_is_rejected(self):
        self.rejects(lambda v: v['pages'].reverse(), 'reordered')

    def test_frozen_progress_is_rejected(self):
        self.rejects(lambda v: v['pages'][1]['progress'].update(value='0'), 'did not advance')

    def test_unusable_action_is_rejected(self):
        self.rejects(lambda v: v['pages'][0]['primary'].update(hittable=False), 'localized onboarding action')

    def test_wrong_runtime_language_is_rejected(self):
        self.rejects(lambda v: v['pages'][0].update(bundleLocalizations=['fr']), 'actual onboarding language')

    def test_noop_skip_is_rejected(self):
        self.rejects(lambda v: v['skipped'].update(completionCount=0), 'completion/Skip failed')

    def test_session_scope_cannot_be_silently_expanded(self):
        self.rejects(lambda v: v['pages'][0].update(sourceSessionCreated=True), 'session/VPN scope')

    def test_wrong_actual_category_is_rejected(self):
        self.rejects(lambda v: v['pages'][0].update(category='UICTContentSizeCategoryM'), 'actual onboarding text category')
