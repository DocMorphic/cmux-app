"""Adversarial association guards. Synthetic fixtures here are never native UI evidence."""
from contextlib import redirect_stdout
import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from test_ios_chief_reference import png

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('poll_verifier', ROOT / 'scripts/verify-ios-chief-polls.py')
V = importlib.util.module_from_spec(spec); spec.loader.exec_module(V)


class PollExportGuards(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.export = Path(self.tmp.name) / 'export'; self.export.mkdir()
        self.output = Path(self.tmp.name) / 'canonical'; self.commit = 'a' * 40
        template = ROOT / 'tests/ios-chief-reference'
        fixtures = json.loads((template / 'PollReferences.json').read_text())
        manifest = json.loads((ROOT / 'third_party/cmux-chief/manifest.json').read_text())
        provenance = dict(projectSourceCommit=self.commit, projectSourceDirty=False,
            upstreamRevision=manifest['revision'], packageTree=manifest['referencePackage']['tree'],
            referenceAppSha256=V.digest(template / 'PollReferenceApp.swift'),
            pollUiTestsSha256=V.digest(template / 'PollUITests.swift'),
            pollFixtureSha256=V.digest(template / 'PollReferences.json'))
        self.names = []
        for index, scene in enumerate(fixtures['scenes']):
            name = scene['id']; self.names.append(name)
            poll = fixtures['polls'][scene['model']]
            w, h = (874, 402) if scene['orientation'] == 'landscape' else (402, 874)
            image = png(index, w, h); (self.export / (name + '.png')).write_bytes(image)
            sections = []
            for wanted in V.expected_sections(poll):
                sections.append(dict(title=wanted['title'], footer=wanted['footer'], rows=[
                    dict(row=i, frame=[16, 100 + i * 44, 370, 44], visible=True, text=text, selectionStyle=0)
                    for i, text in enumerate(wanted['names'])]))
            value = dict(scene=name, pollScene=name, fixture=scene, provenance=provenance,
                fixtureSha256=provenance['pollFixtureSha256'], imageSha256=hashlib.sha256(image).hexdigest(),
                captureMethod='XCUIScreen.main.screenshot', activation='physicalPressAndSourcePollDetails',
                ready=True, presented=True, doneDismissed=True, backendWriteAttempts=0,
                categoryMatches=True, style=scene['style'], direction=scene['direction'], viewport=[w, h], scale=1,
                runtime=dict(systemVersion='Synthetic test only'), navigationFrame=[0, 40, w, 50],
                navigationPrompt=poll['question'], table=dict(style=2, frame=[0, 90, w, h - 90], sections=sections),
                labels=[dict(text='Synthetic verifier test', frame=[16, 100, 200, 44], fontSize=28)])
            (self.export / (name + '.json')).write_text(json.dumps(value))

    def verify(self):
        with redirect_stdout(io.StringIO()): V.verify(self.export, self.output, self.commit)

    def rejects(self, mutate, message, scene=None):
        path = self.export / ((scene or self.names[0]) + '.json')
        raw = path.read_text(); value = json.loads(raw); mutate(value); path.write_text(json.dumps(value))
        try:
            with self.assertRaisesRegex(ValueError, message): self.verify()
            self.assertFalse(self.output.exists())
        finally: path.write_text(raw)

    def test_complete_payload_binds_all_seven_images_and_sources(self):
        self.verify()
        self.assertEqual(len(list(self.output.glob('*.png'))), 7)
        self.assertEqual(json.loads((self.output / 'receipt.json').read_text())['scenes'], self.names)

    def test_missing_and_duplicate_scene_rejected(self):
        path = self.export / (self.names[0] + '.json'); duplicate = self.export / 'duplicate.json'
        duplicate.write_bytes(path.read_bytes())
        with self.assertRaisesRegex(ValueError, 'Duplicate'): self.verify()
        duplicate.unlink(); path.unlink()
        with self.assertRaisesRegex(ValueError, 'Missing/unexpected'): self.verify()

    def test_changed_host_fixture_package_commit_or_dirty_state_rejected(self):
        for key, bad, message in [('referenceAppSha256', 'changed', 'host/tests'),
            ('pollUiTestsSha256', 'changed', 'host/tests'), ('pollFixtureSha256', 'changed', 'fixture'),
            ('packageTree', 'changed', 'package'), ('projectSourceCommit', 'b' * 40, 'project source'),
            ('projectSourceDirty', True, 'project source')]:
            with self.subTest(key=key):
                self.rejects(lambda v: v['provenance'].__setitem__(key, bad), message)

    def test_missing_real_controls_dismissal_traits_and_no_write_proof_rejected(self):
        for key, bad, message in [('activation', 'directPrivateSelector', 'activation'),
            ('captureMethod', 'drawHierarchy', 'activation'), ('doneDismissed', False, 'dismissed'),
            ('backendWriteAttempts', 1, 'wrote'), ('categoryMatches', False, 'traits'),
            ('style', 'light', 'traits'), ('direction', 'rtl', 'traits')]:
            with self.subTest(key=key): self.rejects(lambda v: v.__setitem__(key, bad), message)

    def test_table_style_question_and_section_inventory_rejected(self):
        self.rejects(lambda v: v['table'].__setitem__('style', 0), 'inset-grouped')
        self.rejects(lambda v: v.__setitem__('navigationPrompt', 'wrong question'), 'question')
        self.rejects(lambda v: v['table']['sections'].pop(), 'section count')
        self.rejects(lambda v: v['table']['sections'][0].__setitem__('footer', '0 votes'), 'footer')

    def test_voter_order_me_alias_unknown_fallback_adder_and_non_voters_rejected(self):
        scene = 'poll-voters-dark'
        self.rejects(lambda v: v['table']['sections'][0]['rows'].reverse(), 'row geometry', scene)
        self.rejects(lambda v: v['table']['sections'][0]['rows'][0].__setitem__('text', 'Owner display name'), 'name', scene)
        self.rejects(lambda v: v['table']['sections'][1]['rows'][1].__setitem__('text', 'You'), 'name', scene)
        self.rejects(lambda v: v['table']['sections'][1].__setitem__('footer', '2 Votes'), 'footer', scene)
        self.rejects(lambda v: v['table']['sections'][-1]['rows'].pop(), 'row count', scene)

    def test_selectable_or_missing_visible_rows_invalid_frames_and_fake_large_type_rejected(self):
        self.rejects(lambda v: v['table']['sections'][0]['rows'][0].__setitem__('selectionStyle', 1), 'name')
        self.rejects(lambda v: v['table']['sections'][0]['rows'][0].__setitem__('frame', [0, 0, 0, 44]), 'geometry')
        self.rejects(lambda v: [r.__setitem__('visible', False) for s in v['table']['sections'] for r in s['rows']], 'No visible')
        self.rejects(lambda v: v['labels'][0].__setitem__('fontSize', 17), 'accessibility', 'poll-pending-large')

    def test_wrong_os_orientation_scale_and_unpaired_image_rejected(self):
        self.rejects(lambda v: v.__setitem__('viewport', [874, 402]), 'orientation')
        self.rejects(lambda v: v.__setitem__('scale', 3), 'dimensions')
        self.rejects(lambda v: v.__setitem__('imageSha256', 'missing'), 'screenshot')


if __name__ == '__main__': unittest.main()
