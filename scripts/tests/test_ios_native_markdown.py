"""Adversarial export guards; these synthetic PNGs are never native UI evidence."""
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
spec = importlib.util.spec_from_file_location('native_markdown_verifier', ROOT / 'scripts/verify-ios-native-markdown.py')
V = importlib.util.module_from_spec(spec); spec.loader.exec_module(V)


class NativeMarkdownExportGuards(unittest.TestCase):
    def test_android_comparison_uses_identical_input(self):
        self.assertEqual((V.TEMPLATES / 'References.json').read_bytes(),
            (ROOT / 'app/src/androidTest/assets/native-markdown/reference.json').read_bytes())

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.export = Path(self.tmp.name) / 'export'; self.export.mkdir()
        self.output = Path(self.tmp.name) / 'canonical'; self.commit = 'a' * 40
        self.fixture = json.loads((V.TEMPLATES / 'References.json').read_text())
        provenance = dict(V.reviewed_provenance(), projectSourceCommit=self.commit, projectSourceDirty=False)
        for index, scene in enumerate(self.fixture['scenes']):
            size = [160, 120] if scene['orientation'] == 'landscape' else [120, 160]
            pages = []
            for i, stage in enumerate(self.fixture['stages']):
                raw = png(index * 3 + i, *size)
                (self.export / (scene['id'] + '-' + stage + '.png')).write_bytes(raw)
                body = 17 if scene['category'] == 'large' else 28
                pages.append(dict(schema=1, referenceKind='nativeMarkdownObservation', scene=scene['id'], stage=stage,
                    provenance=provenance, ready=True, categoryMatches=True, device=scene['device'], style=scene['style'],
                    context='standaloneBaseWindow', interfaceLevel=0, sourceSessionCreated=False, interceptedURLs=[],
                    platform='iOS', runtimeVersion='Synthetic verifier guard only',
                    swiftUIAccessibilitySize=scene['category'] != 'large', swiftUIReduceMotion=False,
                    swiftUIDirection='rtl' if scene['language'] == 'ar' else 'ltr',
                    category='UICTContentSizeCategoryL' if scene['category'] == 'large' else 'UICTContentSizeCategoryAccessibilityM',
                    bundleLocalizations=[scene['language']], preferredLanguages=[scene['language']], viewport=size, scale=1,
                    safeArea=[0, 0, 0, 0], fonts={s: dict(pointSize=body, lineHeight=body+3, ascender=body-3, descender=-3)
                        for s in ['body', 'title', 'title2', 'title3', 'headline', 'caption2']},
                    colors={c: [0, 0, 0, 1] for c in ['background', 'label', 'secondaryLabel', 'tertiaryLabel', 'separator']},
                    target=dict(exists=True, hittable=True, label=self.fixture['probes'][stage], frame=[16, 40, 90, 20]),
                    captureMethod='XCUIScreen.main.screenshot', imageSha256=hashlib.sha256(raw).hexdigest()))
            (self.export / (scene['id'] + '.json')).write_text(json.dumps(dict(schema=1, referenceKind='nativeMarkdownScene', scene=scene['id'], pages=pages)))

    def verify(self):
        with redirect_stdout(io.StringIO()): return V.verify(self.export, self.output, self.commit)

    def rejects(self, change, message, name=None):
        path = self.export / ((name or self.fixture['scenes'][0]['id']) + '.json')
        value = json.loads(path.read_text()); change(value); path.write_text(json.dumps(value))
        with self.assertRaisesRegex(ValueError, message): self.verify()
        self.assertFalse(self.output.exists())

    def test_complete_inventory_is_associated(self):
        self.assertEqual(self.verify()['capturedPageCount'], 39)
        self.assertEqual(len(list(self.output.glob('*.png'))), 39)

    def test_missing_scene_is_rejected(self):
        (self.export / 'en-dark.json').unlink()
        with self.assertRaisesRegex(ValueError, 'Missing/unexpected'): self.verify()

    def test_changed_declaration_is_rejected(self):
        self.rejects(lambda v: v['pages'][0]['provenance'].update(nativeDeclarationSha256='f'*64), 'Unreviewed')

    def test_changed_host_is_rejected(self):
        self.rejects(lambda v: v['pages'][0]['provenance']['templateHashes'].update(**{'ReferenceApp.swift': 'f'*64}), 'Unreviewed')

    def test_dirty_project_is_rejected(self):
        self.rejects(lambda v: v['pages'][0]['provenance'].update(projectSourceDirty=True), 'Wrong/dirty')

    def test_mixed_observations_are_rejected(self):
        self.rejects(lambda v: v['pages'][1]['provenance'].update(scope='Different host'), 'Mixed')

    def test_missing_image_is_rejected(self):
        self.rejects(lambda v: v['pages'][0].update(imageSha256='f'*64), 'Missing/changed')

    def test_wrong_dimensions_are_rejected(self):
        self.rejects(lambda v: v['pages'][0].update(scale=2), 'compositor dimensions')

    def test_missing_stage_is_rejected(self):
        self.rejects(lambda v: v['pages'].pop(), 'Missing/reordered')

    def test_offscreen_target_is_rejected(self):
        self.rejects(lambda v: v['pages'][0]['target'].update(frame=[16, 150, 90, 20]), 'outside viewport')

    def test_visible_landscape_edge_is_not_rejected_by_invented_insets(self):
        path = self.export / 'en-landscape.json'
        value = json.loads(path.read_text()); value['pages'][0]['target']['frame'] = [16, 16, 90, 20]
        path.write_text(json.dumps(value)); self.verify()

    def test_wrong_actual_large_text_is_rejected(self):
        self.rejects(lambda v: v['pages'][0]['fonts']['body'].update(pointSize=17), 'actual native Markdown body size', 'de-accessibility')

    def test_wrong_actual_direction_is_rejected(self):
        self.rejects(lambda v: v['pages'][0].update(swiftUIDirection='ltr'), 'accessibility traits', 'ar-dark')

    def test_scope_cannot_expand_to_production_session(self):
        self.rejects(lambda v: v['pages'][0].update(sourceSessionCreated=True), 'host scope')

    def test_standalone_window_cannot_claim_sheet_palette(self):
        self.rejects(lambda v: v['pages'][0].update(interfaceLevel=1), 'host scope')

    def test_ui_color_white_conversion_epsilon_is_preserved(self):
        path = self.export / 'en-dark.json'; value = json.loads(path.read_text())
        value['pages'][0]['colors']['label'] = [1.0000001192092896] * 3 + [1]
        path.write_text(json.dumps(value)); self.verify()

    def test_invalid_color_is_not_hidden_by_epsilon(self):
        self.rejects(lambda v: v['pages'][0]['colors'].update(label=[1.001, 1, 1, 1]), 'resolved native Markdown colors')
