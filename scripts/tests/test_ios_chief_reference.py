"""Integrity checks for reference export association; these synthetic PNGs are not UIKit evidence."""
import binascii
from contextlib import redirect_stdout
import hashlib
import importlib.util
import json
import io
from pathlib import Path
import struct
import tempfile
import unittest
import zlib

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('ios_reference_verifier', ROOT / 'scripts/verify-ios-chief-reference.py')
VERIFIER = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(VERIFIER)


def png(color, width=2, height=3):
    def chunk(kind, payload):
        return struct.pack('>I', len(payload)) + kind + payload + struct.pack('>I', binascii.crc32(kind + payload) & 0xffffffff)
    header = struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0)
    pixels = (b'\x00' + bytes([color, 25, 50, 255]) * width) * height
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', header) + chunk(b'IDAT', zlib.compress(pixels)) + chunk(b'IEND', b'')


class ReferenceIntegrityTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(); self.addCleanup(self.temporary.cleanup)
        self.exported = Path(self.temporary.name) / 'exported'; self.exported.mkdir()
        self.output = Path(self.temporary.name) / 'canonical'
        self.manifest = json.loads((ROOT / 'third_party/cmux-chief/manifest.json').read_text())
        source = (ROOT / 'tests/ios-chief-reference/Scenes.json').read_bytes()
        self.scenes = json.loads(source)['scenes']; scene_sha = hashlib.sha256(source).hexdigest()
        self.commit = 'a' * 40
        for index, scene in enumerate(self.scenes):
            image = png(index); name = scene['id']
            (self.exported / (name + '.png')).write_bytes(image)
            value = {'scene': name, 'fixtureSha256': scene_sha, 'imageSha256': hashlib.sha256(image).hexdigest(),
                     'viewport': [2, 3], 'scale': 1, 'labels': [{'text': 'Synthetic verifier test', 'class': 'ConversationEffectLabel', 'fontSize': 28}], 'storeMessages': 1,
                     'style': scene['style'], 'category': scene['category'], 'applicationCategoryMatchesScene': True,
                     'provenance': {'scenesSha256': scene_sha, 'upstreamRevision': self.manifest['revision'],
                                    'packageTree': self.manifest['referencePackage']['tree'],
                                    'projectSourceCommit': self.commit, 'projectSourceDirty': False}}
            (self.exported / (name + '.json')).write_text(json.dumps(value))

    def verify(self, with_interactions=False):
        with redirect_stdout(io.StringIO()): VERIFIER.verify(self.exported, self.output, self.commit, with_interactions)
    def mutate(self, change):
        path = self.exported / (self.scenes[0]['id'] + '.json'); value = json.loads(path.read_text())
        change(value); path.write_text(json.dumps(value))

    def test_complete_payloads_are_canonicalized_and_missing_or_changed_png_is_rejected(self):
        self.verify()
        self.assertEqual(len(self.scenes), len(list(self.output.glob('*.png'))))
        self.assertEqual(self.manifest['revision'], json.loads((self.output / 'receipt.json').read_text())['revision'])
        target = self.exported / (self.scenes[0]['id'] + '.png'); target.write_bytes(png(250))
        with self.assertRaisesRegex(ValueError, 'Missing or changed screenshot'): self.verify()

    def test_unreviewed_source_is_rejected_before_writing_an_output(self):
        self.mutate(lambda value: value['provenance'].update(packageTree='f' * 40))
        with self.assertRaisesRegex(ValueError, 'Unreviewed UIKit'): self.verify()
        self.assertFalse(self.output.exists())

    def test_an_old_project_commit_cannot_be_accepted_for_the_current_run(self):
        self.mutate(lambda value: value['provenance'].update(projectSourceCommit='b' * 40))
        with self.assertRaisesRegex(ValueError, 'Unexpected reference project commit'): self.verify()

    def test_a_missing_scene_is_not_a_successful_partial_reference(self):
        (self.exported / (self.scenes[0]['id'] + '.json')).unlink()
        with self.assertRaisesRegex(ValueError, 'Missing/unexpected rendered scenes'): self.verify()

    def test_image_dimensions_must_match_the_actual_capture_viewport(self):
        self.mutate(lambda value: value.update(viewport=[200, 300]))
        with self.assertRaisesRegex(ValueError, 'Wrong rendered image dimensions'): self.verify()

    def test_a_large_text_label_cannot_pass_without_actual_accessibility_fonts(self):
        path = self.exported / 'large-dark.json'; value = json.loads(path.read_text())
        value['labels'][0]['fontSize'] = 17; path.write_text(json.dumps(value))
        with self.assertRaisesRegex(ValueError, 'Accessibility body type was not rendered'): self.verify()

    def add_interactions(self):
        source = (ROOT / 'tests/ios-chief-reference/Interactions.json').read_bytes()
        interaction_sha = hashlib.sha256(source).hexdigest()
        baseline = json.loads((self.exported / (self.scenes[0]['id'] + '.json')).read_text())
        for index, specification in enumerate(json.loads(source)['scenes']):
            name = specification['id']; details = specification['mode'] == 'details'
            image = png(100 + index, width=2 if details else 3, height=3 if details else 2)
            value = dict(baseline, **{key: specification[key] for key in ('id', 'style', 'category', 'sourceScene', 'mode', 'orientation')})
            value.update(scene=name, interactionFixtureSha256=interaction_sha,
                         viewport=[2, 3] if details else [3, 2], imageSha256=hashlib.sha256(image).hexdigest(),
                         safeArea=[0, 0, 0, 0], verticalSizeClass='regular' if details else 'compact',
                         imageViews=[{'imageSize': [17, 17], 'symbol': True}],
                         behavior={'viewingBefore': True})
            value['provenance'] = dict(baseline['provenance'], interactionsSha256=interaction_sha)
            if details:
                value['labels'] = [{'accessibilityIdentifier': 'conversation.details.title', 'text': 'Chief',
                                    'fontSize': 38 if specification['category'] == 'accessibilityMedium' else 28}]
                value['controls'] = [{'identifier': 'conversation.details.' + action, 'enabled': False} for action in ('call', 'video', 'mail')]
                value['controls'] += [{'identifier': 'conversation.details.' + action, 'enabled': True, 'on': enabled}
                                      for action, enabled in (('hideAlerts', False), ('readReceipts', True))]
                value['behavior'].update(viewingWhileDetails=True, viewingAfterReturn=True,
                    detailsCloseActivation='uniqueNavigationPrimaryAction', navigationPrimaryControlCount=1,
                    navigationLeftItemCount=1, navigationRightItemCount=0, navigationItemIdentifier='conversation.details.back',
                    editorFocusedWhileDetails=False, editorFocusedAfterReturn=False, draftPreserved=True,
                    alertsOptimistic=True, receiptsOptimistic=True, alertsAfterRollback=False, receiptsAfterRollback=True)
            else:
                value['controls'] = [{'identifier': 'conversation.header.' + action, 'enabled': True} for action in ('name', 'trailing')]
            (self.exported / (name + '.png')).write_bytes(image)
            (self.exported / (name + '.json')).write_text(json.dumps(value))

    def mutate_interaction(self, name, change):
        path = self.exported / (name + '.json'); value = json.loads(path.read_text())
        change(value); path.write_text(json.dumps(value))

    def test_expanded_batch_requires_all_thirteen_scenes_and_a_distinct_fixture_hash(self):
        self.add_interactions(); self.verify(with_interactions=True)
        receipt = json.loads((self.output / 'receipt.json').read_text())
        self.assertEqual(13, len(receipt['scenes']))
        self.assertNotEqual(receipt['scenesSha256'], receipt['interactionsSha256'])
        (self.exported / 'compact-dark.json').unlink()
        with self.assertRaisesRegex(ValueError, 'Missing/unexpected rendered scenes'): self.verify(with_interactions=True)

    def test_changed_interaction_fixture_is_rejected(self):
        self.add_interactions()
        self.mutate_interaction('details-dark', lambda value: value.update(interactionFixtureSha256='0' * 64))
        with self.assertRaisesRegex(ValueError, 'Interaction fixture changed'): self.verify(with_interactions=True)

    def test_details_checks_reject_enabled_unavailable_actions_or_missing_rollback_and_focus(self):
        self.add_interactions()
        for key, bad in (('alertsAfterRollback', True), ('editorFocusedAfterReturn', True), ('draftPreserved', False)):
            path = self.exported / 'details-dark.json'; original = path.read_text()
            self.mutate_interaction('details-dark', lambda value: value['behavior'].update({key: bad}))
            with self.assertRaisesRegex(ValueError, 'Actual details focus, draft or rollback'): self.verify(with_interactions=True)
            path.write_text(original)
        self.mutate_interaction('details-dark', lambda value: value['controls'][0].update(enabled=True))
        with self.assertRaisesRegex(ValueError, 'Unavailable source details action'): self.verify(with_interactions=True)

    def test_details_accessibility_requires_actual_title_type(self):
        self.add_interactions()
        self.mutate_interaction('details-large-dark', lambda value: value['labels'][0].update(fontSize=28))
        with self.assertRaisesRegex(ValueError, 'Actual details title type'): self.verify(with_interactions=True)

    def test_compact_capture_requires_real_landscape_and_compact_traits(self):
        self.add_interactions()
        self.mutate_interaction('compact-dark', lambda value: value.update(viewport=[2, 3]))
        with self.assertRaisesRegex(ValueError, 'Wrong interaction orientation'): self.verify(with_interactions=True)
        self.mutate_interaction('compact-dark', lambda value: value.update(viewport=[3, 2], verticalSizeClass='regular'))
        with self.assertRaisesRegex(ValueError, 'Wrong actual vertical size class'): self.verify(with_interactions=True)

    def test_modal_viewing_is_an_observation_and_may_be_false(self):
        self.add_interactions()
        self.mutate_interaction('details-dark', lambda value: value['behavior'].update(viewingWhileDetails=False))
        self.verify(with_interactions=True)

    def test_manual_modal_dismissal_cannot_substitute_for_the_source_back_item(self):
        self.add_interactions()
        self.mutate_interaction('details-dark', lambda value: value['behavior'].update(detailsCloseActivation='manualDismiss'))
        with self.assertRaisesRegex(ValueError, 'actual source details Back item|Actual source details Back item'):
            self.verify(with_interactions=True)

    def test_an_ambiguous_navigation_bar_cannot_prove_the_source_back_action(self):
        self.add_interactions()
        self.mutate_interaction('details-dark', lambda value: value['behavior'].update(navigationPrimaryControlCount=2))
        with self.assertRaisesRegex(ValueError, 'Ambiguous source Back navigation controls'):
            self.verify(with_interactions=True)


if __name__ == '__main__': unittest.main()
