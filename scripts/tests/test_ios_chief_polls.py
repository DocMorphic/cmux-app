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
                captureMethod='XCUIScreen.main.screenshot', activation='physicalPressAndSourcePollDetails', activationPoint='bubbleTopPadding',
                ready=True, presented=True, doneDismissed=True, backendWriteAttempts=0,
                categoryMatches=True, style=scene['style'], direction=scene['direction'], viewport=[w, h], scale=1,
                runtime=dict(systemVersion='Synthetic test only'), navigationFrame=[0, 40, w, 50],
                navigationPrompt=poll['question'], table=dict(style=2, frame=[0, 90, w, h - 90], sections=sections),
                labels=[dict(text='Synthetic verifier test', frame=[16, 100, 200, 44], fontSize=28)])
            names = ['extraSmall', 'small', 'medium', 'large', 'extraLarge', 'extraExtraLarge', 'extraExtraExtraLarge',
                'accessibilityMedium', 'accessibilityLarge', 'accessibilityExtraLarge', 'accessibilityExtraExtraLarge', 'accessibilityExtraExtraExtraLarge']
            bodies = [14, 15, 16, 17, 19, 21, 23, 28, 33, 40, 47, 53]
            footers = [12, 12, 12, 13, 15, 17, 19, 23, 27, 33, 38, 44]
            records = [dict(name=n, category='Synthetic-' + n, bodyPointSize=b, bodyLineHeight=b * 1.2,
                bodyAscender=b * .95, bodyDescender=-b * .25, footerPointSize=f, footerLineHeight=f * 1.2,
                footerAscender=f * .95, footerDescender=-f * .25) for n, b, f in zip(names, bodies, footers)]
            current = records[names.index(scene['category'])]
            value['typeMetrics'] = dict(schema=1, records=records, currentName=scene['category'], actualCategory=current['category'],
                actualBodyPointSize=current['bodyPointSize'], actualBodyLineHeight=current['bodyLineHeight'],
                actualFooterPointSize=current['footerPointSize'], actualFooterLineHeight=current['footerLineHeight'], boldText=False)
            if scene['model'].startswith('pending'):
                if poll['options']:
                    value['labels'].append(dict(text='0 Votes', frame=[16, 140, 200, 20], fontSize=current['footerPointSize'], lineHeight=current['footerLineHeight'], insidePollTable=True))
                if scene['scroll'] == 'bottom':
                    value['table']['contentOffset'] = [0, 400]
                if scene['scroll'] == 'bottom' or not poll['options']:
                    value['labels'] += [dict(text=t, frame=[16, 180 + i * 70, 200, 64], fontSize=current['bodyPointSize'], insidePollTable=True)
                        for i, t in enumerate(['No Votes', 'Chief', 'You'])]
            (self.export / (name + '.json')).write_text(json.dumps(value))

    def verify(self, require_lifecycle=False, category=None):
        with redirect_stdout(io.StringIO()): V.verify(self.export, self.output, self.commit, require_lifecycle, category)

    def rejects(self, mutate, message, scene=None):
        path = self.export / ((scene or self.names[0]) + '.json')
        raw = path.read_text(); value = json.loads(raw); mutate(value); path.write_text(json.dumps(value))
        try:
            with self.assertRaisesRegex(ValueError, message): self.verify()
            self.assertFalse(self.output.exists())
        finally: path.write_text(raw)

    def test_complete_payload_binds_all_declared_images_and_sources(self):
        self.verify()
        self.assertEqual(len(list(self.output.glob('*.png'))), len(self.names))
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
            ('activationPoint', 'bubbleCenter', 'physical press location'),
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

    def install_lifecycle(self):
        original = json.loads((self.export / 'poll-pending-dark.json').read_text())
        steps = []
        for index, (phase, width, height) in enumerate([
            ('portrait-before', 402, 874), ('landscape-open', 874, 402), ('portrait-returned', 402, 874)]):
            state = copy.deepcopy(original); state.pop('pollScene'); state.pop('doneDismissed')
            image = png(20 + index, width, height)
            (self.export / (phase + '.png')).write_bytes(image)
            state.update(viewport=[width, height], imageSha256=hashlib.sha256(image).hexdigest(),
                presentationGeneration=1, tableGeneration=1, observationOwner='00000000-0000-4000-8000-000000000001', navigationFrame=[0, 40, width, 50])
            state['table']['frame'] = [0, 90, width, height - 90]
            steps.append(dict(phase=phase, state=state))
        path = self.export / 'lifecycle.json'
        path.write_text(json.dumps(dict(schema=1, pollLifecycle='pending-roundtrip', steps=steps, doneDismissed=True)))
        return path

    def rejects_lifecycle(self, mutate, message):
        path = self.export / 'lifecycle.json'; original = path.read_text(); value = json.loads(original)
        mutate(value); path.write_text(json.dumps(value))
        try:
            with self.assertRaisesRegex(ValueError, message): self.verify(require_lifecycle=True)
            self.assertFalse(self.output.exists())
        finally: path.write_text(original)

    def test_required_lifecycle_missing_rejected_and_full_roundtrip_pairs_three_more_images(self):
        with self.assertRaisesRegex(ValueError, 'Missing/unexpected poll lifecycle'): self.verify(require_lifecycle=True)
        self.install_lifecycle(); self.verify(require_lifecycle=True)
        receipt = json.loads((self.output / 'receipt.json').read_text())
        self.assertEqual(receipt['lifecycle'], ['pending-roundtrip'])
        self.assertEqual(len(receipt['lifecycleImageGeometry']), 3)
        self.assertEqual(len(list(self.output.glob('*.png'))), len(self.names) + 3)
        self.assertTrue((self.output / 'poll-lifecycle-pending-roundtrip.json').is_file())

    def test_duplicate_or_unexpected_lifecycle_rejected(self):
        path = self.install_lifecycle(); duplicate = self.export / 'duplicate-lifecycle.json'
        duplicate.write_bytes(path.read_bytes())
        with self.assertRaisesRegex(ValueError, 'Duplicate poll lifecycle'): self.verify(require_lifecycle=True)
        duplicate.unlink()
        self.rejects_lifecycle(lambda v: v.__setitem__('pollLifecycle', 'unreviewed'), 'Missing/unexpected')

    def test_missing_or_reordered_phases_and_failed_done_rejected(self):
        self.install_lifecycle()
        self.rejects_lifecycle(lambda v: v['steps'].pop(), 'rotation steps')
        self.rejects_lifecycle(lambda v: v['steps'].reverse(), 'rotation steps')
        self.rejects_lifecycle(lambda v: v.__setitem__('doneDismissed', False), 'Done did not dismiss')
        self.rejects_lifecycle(lambda v: v.__setitem__('schema', 2), 'lifecycle identity')

    def test_recreated_source_table_or_controller_and_missing_identity_rejected(self):
        self.install_lifecycle()
        self.rejects_lifecycle(lambda v: v['steps'][1]['state'].__setitem__('observationOwner', '00000000-0000-4000-8000-000000000002'), 'observation owner replaced')
        self.rejects_lifecycle(lambda v: v['steps'][1]['state'].__setitem__('observationOwner', None), 'actual observation owner')
        for key in ['presentationGeneration', 'tableGeneration']:
            with self.subTest(key=key):
                self.rejects_lifecycle(lambda v: v['steps'][1]['state'].__setitem__(key, 2), 'replaced during live rotation')
                for invalid in [0, None, True, 1.0]:
                    self.rejects_lifecycle(lambda v: v['steps'][1]['state'].__setitem__(key, invalid), 'actual source presentation/table identity')

    def test_rotation_state_requires_real_controls_current_source_and_unchanged_sections(self):
        self.install_lifecycle()
        self.rejects_lifecycle(lambda v: v['steps'][1]['state'].__setitem__('presented', False), 'presented/dismissed')
        self.rejects_lifecycle(lambda v: v['steps'][1]['state'].__setitem__('activation', 'directPrivateSelector'), 'activation')
        self.rejects_lifecycle(lambda v: v['steps'][2]['state']['provenance'].__setitem__('projectSourceCommit', 'b' * 40), 'project source')
        self.rejects_lifecycle(lambda v: v['steps'][1]['state']['table']['sections'][0].__setitem__('footer', '1 Vote'), 'footer')
        self.rejects_lifecycle(lambda v: v['steps'][2]['state'].__setitem__('backendWriteAttempts', 1), 'wrote')

    def test_rotation_screenshot_and_actual_orientation_must_agree(self):
        self.install_lifecycle()
        self.rejects_lifecycle(lambda v: v['steps'][1]['state'].__setitem__('viewport', [402, 874]), 'orientation')
        self.rejects_lifecycle(lambda v: v['steps'][1]['state'].__setitem__('scale', 3), 'dimensions')
        self.rejects_lifecycle(lambda v: v['steps'][2]['state'].__setitem__('imageSha256', 'missing'), 'screenshot')

    def test_incomplete_wrong_weight_or_nonfinite_font_profiles_rejected(self):
        self.rejects(lambda v: v.pop('typeMetrics'), 'complete default-weight')
        self.rejects(lambda v: v['typeMetrics']['records'].pop(), 'complete default-weight')
        self.rejects(lambda v: v['typeMetrics'].__setitem__('boldText', True), 'complete default-weight')
        self.rejects(lambda v: v['typeMetrics']['records'][0].__setitem__('footerLineHeight', float('nan')), 'finite poll font')
        self.rejects(lambda v: v['typeMetrics']['records'][0].__setitem__('footerPointSize', -1), 'measured poll font')
        self.rejects(lambda v: v['typeMetrics']['records'][0].__setitem__('bodyPointSize', 17), 'measured poll font')

    def test_profile_must_match_actual_global_category_and_font(self):
        self.rejects(lambda v: v['typeMetrics'].__setitem__('actualCategory', 'Synthetic-small'), 'actual application category')
        self.rejects(lambda v: v['typeMetrics'].__setitem__('actualFooterPointSize', 12), 'Actual poll font')
        self.rejects(lambda v: v['typeMetrics']['records'][0].__setitem__('category', 'Synthetic-small'), 'Duplicate compatible')

    def test_visible_footer_and_body_fonts_are_validated_only_inside_actual_table(self):
        self.rejects(lambda v: v['labels'][-1].__setitem__('fontSize', 12), 'Visible source footer')
        self.rejects(lambda v: v['labels'][-1].__setitem__('insidePollTable', False), 'Missing visible actual source footer')
        self.rejects(lambda v: v['labels'].append(dict(text='No votes yet', frame=[16, 100, 200, 44], fontSize=13, insidePollTable=True)), 'Visible source row')
        # Covered transcript labels must not be mistaken for the presented table.
        path = self.export / (self.names[0] + '.json'); value = json.loads(path.read_text())
        value['labels'].append(dict(text='0 Votes', frame=[16, 100, 200, 44], fontSize=99, insidePollTable=False))
        path.write_text(json.dumps(value)); self.verify()



    def test_visible_footer_line_height_must_match_actual_application_footnote(self):
        self.rejects(lambda v: v['labels'][-1].__setitem__('lineHeight', 99), 'footer line height')

    def test_pending_bottom_requires_scroll_and_visible_table_labels(self):
        scene = 'poll-type-accessibilityExtraExtraExtraLarge-bottom'
        self.rejects(lambda v: v['table'].__setitem__('contentOffset', [0, 0]), 'did not scroll', scene)
        self.rejects(lambda v: v['labels'][-1].__setitem__('insidePollTable', False), 'Missing visible pending bottom', scene)
        self.rejects(lambda v: v['labels'][-1].__setitem__('fontSize', 17), 'bottom font', scene)

    def test_explicit_category_subset_cannot_pass_as_full_inventory(self):
        fixture = json.loads((ROOT / 'tests/ios-chief-reference/PollReferences.json').read_text())
        selected = [s['id'] for s in fixture['scenes'] if s['category'] == 'large']
        for name in self.names:
            if name not in selected:
                (self.export / (name + '.json')).unlink(); (self.export / (name + '.png')).unlink()
        with self.assertRaisesRegex(ValueError, 'Missing/unexpected'): self.verify()
        self.verify(category='large')
        receipt = json.loads((self.output / 'receipt.json').read_text())
        self.assertEqual(receipt['scenes'], selected)
        self.assertEqual(receipt['categoryScope'], 'large')
        self.assertEqual(receipt['declaredSceneCount'], len(self.names))
        self.assertEqual(receipt['capturedSceneCount'], len(selected))

    def test_category_scope_rejects_unknown_extra_scenes_and_incompatible_lifecycle(self):
        with self.assertRaisesRegex(ValueError, 'Unknown poll category scope'): self.verify(category='invented')
        with self.assertRaisesRegex(ValueError, 'Missing/unexpected'): self.verify(category='large')
        with self.assertRaisesRegex(ValueError, 'Lifecycle requires standard'): self.verify(require_lifecycle=True, category='accessibilityMedium')

if __name__ == '__main__': unittest.main()
