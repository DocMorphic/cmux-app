"""Adversarial source/export guards using synthetic payloads, never native observations."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('rich_composer', ROOT / 'scripts/verify-ios-chief-rich-composer.py')
VERIFY = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(VERIFY)


class RichComposerExportGuards(unittest.TestCase):
    def setUp(self):
        self.fixture = (ROOT / 'tests/chief-rich-composer/Cases.json').read_bytes()
        self.case = json.loads(self.fixture)['cases'][0]
        self.manifest = json.loads((ROOT / 'third_party/cmux-chief/manifest.json').read_text())
        self.commit = 'b' * 40
        h = lambda p: hashlib.sha256(p).hexdigest()
        self.payload = dict(schema=1, referenceKind='chiefRichComposer', caseID=self.case['id'], completed=True,
            fixtureSha256=h(self.fixture), platform='iOS', runtimeVersion='26.4.1', actualCategory=VERIFY.CATEGORIES['large'],
            supportsAttachments=False, privateSelectorsUsed=False, sourceModified=False, externalBackend=False,
            backendMode=self.case['backend'], style=self.case['style'],
            provenance=dict(upstreamRevision=self.manifest['revision'], packageTree=self.manifest['referencePackage']['tree'],
                projectSourceCommit=self.commit, projectSourceDirty=False, richComposerCasesSha256=h(self.fixture),
                richComposerTestsSha256=h((ROOT / 'tests/ios-chief-reference/RichComposerTests.swift').read_bytes()),
                referenceAppSha256=h((ROOT / 'tests/ios-chief-reference/ReferenceApp.swift').read_bytes()),
                referenceTestsSha256=h((ROOT / 'tests/ios-chief-reference/ReferenceTests.swift').read_bytes())))
        records = []
        palette = False
        for i, action in enumerate([dict(kind='initial')] + self.case['actions']):
            if action['kind'] == 'openEffects': palette = True
            if action['kind'] in ['closeEffects', 'resign', 'send']: palette = False
            text = '' if i == 0 else 'abc'
            state = dict(text=text, utf16Count=len(text), selection=dict(location=0, length=0), marked=None, runs=[],
                attributes=[] if not text else [dict(location=0, length=len(text), attributes={})],
                typing=dict(style=0, effect=None, fontSize=17), delegateClass='ConversationComposerView', viewing=True,
                editorIdentity='ObjectIdentifier(synthetic)', actualCategory=VERIFY.CATEGORIES['large'], firstResponder=True,
                canUndo=False, canRedo=False, paletteInWindow=palette, reduceMotion=False, boldText=False,
                unsupportedWrites=0, backendDrafts=[], storeMessages=[], inputViewIdentifier='conversation.textEffects.palette' if palette else None,
                controls=[dict(identifier='conversation.textEffects.' + name, label=name, enabled=True, selected=False,
                               accessibilitySelected=False, frameInPalette=[0, 0, 30, 30]) for name in VERIFY.CONTROLS] if palette else [])
            observed = {}
            if action['kind'] == 'openEffects':
                observed = dict(menuChildren=['Fixture before', 'Text Effects', 'Fixture after'],
                                invocation='source-menu-UIAction-via-UIButton-primaryAction')
            if action['kind'] in ['undo', 'redo']: observed['performed'] = False
            records.append(dict(step=i - 1, action=copy.deepcopy(action), state=state, observed=observed, before=i, after=i + .2))
        self.payload['records'] = records

    def validate(self):
        VERIFY.validate(self.payload, self.case, 'large', self.commit, self.fixture, self.manifest)

    def test_complete_bound_synthetic_observation_is_structurally_valid(self): self.validate()

    def test_rejects_dirty_or_changed_native_source(self):
        self.payload['provenance']['projectSourceDirty'] = True
        with self.assertRaisesRegex(ValueError, 'provenance'): self.validate()
        self.payload['provenance']['projectSourceDirty'] = False
        self.payload['provenance']['richComposerTestsSha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'provenance'): self.validate()

    def test_rejects_missing_reordered_and_changed_actions(self):
        record = self.payload['records'][2]
        record['action']['text'] = 'changed'
        with self.assertRaisesRegex(ValueError, 'action'): self.validate()
        self.payload['records'].pop()
        with self.assertRaisesRegex(ValueError, 'steps'): self.validate()

    def test_rejects_editor_recreation_and_wrong_delegate(self):
        state = self.payload['records'][-1]['state']
        state['editorIdentity'] = 'replacement'
        with self.assertRaisesRegex(ValueError, 'Recreated'): self.validate()
        state['editorIdentity'] = 'ObjectIdentifier(synthetic)'; state['delegateClass'] = 'FakeComposer'
        with self.assertRaisesRegex(ValueError, 'unmounted'): self.validate()

    def test_uses_utf16_counts_and_rejects_invalid_ranges(self):
        state = self.payload['records'][-1]['state']
        state.update(text='😀', utf16Count=2, attributes=[dict(location=0, length=2, attributes={})])
        self.validate()
        state['utf16Count'] = 1
        with self.assertRaisesRegex(ValueError, 'length mismatch'): self.validate()
        state['utf16Count'] = 2; state['selection']['length'] = 3
        with self.assertRaisesRegex(ValueError, 'UTF-16 range'): self.validate()

    def test_rejects_missing_palette_controls_and_uninvoked_menu(self):
        record = next(r for r in self.payload['records'] if r['action']['kind'] == 'openEffects')
        removed = record['state']['controls'].pop()
        with self.assertRaisesRegex(ValueError, 'palette control'): self.validate()
        record['state']['controls'].append(removed); record['observed']['menuChildren'].reverse()
        with self.assertRaisesRegex(ValueError, 'source menu action'): self.validate()

    def test_rejects_missing_attributes_overlapping_runs_and_missing_undo_admission(self):
        state = self.payload['records'][-1]['state']; state['attributes'] = []
        with self.assertRaisesRegex(ValueError, 'cover text'): self.validate()
        state['attributes'] = [dict(location=0, length=3, attributes={})]
        state['runs'] = [dict(location=0, length=2, style=1, effect=None), dict(location=1, length=2, style=2, effect=None)]
        with self.assertRaisesRegex(ValueError, 'Overlapping'): self.validate()
        state['runs'] = []
        record = next(r for r in self.payload['records'] if r['action']['kind'] == 'undo'); record['observed'].clear()
        with self.assertRaisesRegex(ValueError, 'undo/redo'): self.validate()

    def test_rejects_extra_writes_and_wrong_runtime_or_category(self):
        self.payload['records'][-1]['state']['unsupportedWrites'] = 1
        with self.assertRaisesRegex(ValueError, 'backend write'): self.validate()
        self.payload['records'][-1]['state']['unsupportedWrites'] = 0
        self.payload['runtimeVersion'] = '17.0'
        with self.assertRaisesRegex(ValueError, 'iOS 26'): self.validate()
        self.payload['runtimeVersion'] = '26.4.1'; self.payload['actualCategory'] = VERIFY.CATEGORIES['accessibilityMedium']
        with self.assertRaisesRegex(ValueError, 'text category'): self.validate()

    def test_rejects_private_modified_or_external_scope(self):
        for key in ['privateSelectorsUsed', 'sourceModified', 'externalBackend', 'supportsAttachments']:
            self.payload[key] = True
            with self.assertRaisesRegex(ValueError, 'native scope'): self.validate()
            self.payload[key] = False


class RichComposerFontExportGuards(unittest.TestCase):
    def setUp(self):
        def record(kind, category, target=None, before=None):
            value = dict(action=dict(kind=kind), state=dict(controllerCategory=VERIFY.CATEGORIES[category],
                editorCategory=VERIFY.CATEGORIES[category], attributes=[dict(attributes=dict(fontSize=17, fontName='SyntheticFont'))]))
            if target is not None:
                value['action']['target'] = target
                value['observed'] = dict(invocation='UIViewController.traitOverrides.preferredContentSizeCategory',
                                         beforeControllerCategory=VERIFY.CATEGORIES[before])
            return value
        self.records = [record('initial', 'large'),
            record('metricsCategory', 'accessibilityMedium', 'accessibilityMedium', 'large'),
            record('insert', 'accessibilityMedium'), record('metricsCategory', 'large', 'large', 'accessibilityMedium')]

    def validate(self): VERIFY.validate_font_categories(self.records, 'large')

    def test_accepts_bound_public_controller_roundtrip_without_prescribing_formatting(self):
        self.validate()
        self.records[1]['state']['attributes'][0]['attributes']['fontSize'] = 29
        self.validate()

    def test_rejects_trait_not_reaching_editor_and_wrong_prior_category(self):
        self.records[1]['state']['editorCategory'] = VERIFY.CATEGORIES['large']
        with self.assertRaisesRegex(ValueError, 'reach original editor'): self.validate()
        self.records[1]['state']['editorCategory'] = VERIFY.CATEGORIES['accessibilityMedium']
        self.records[3]['observed']['beforeControllerCategory'] = VERIFY.CATEGORIES['large']
        with self.assertRaisesRegex(ValueError, 'public trait transition'): self.validate()

    def test_rejects_missing_public_transition_or_metric_font(self):
        self.records[1]['observed']['invocation'] = 'manual-private-callback'
        with self.assertRaisesRegex(ValueError, 'public trait transition'): self.validate()
        self.records[1]['observed']['invocation'] = 'UIViewController.traitOverrides.preferredContentSizeCategory'
        self.records[2]['state']['attributes'][0]['attributes']['fontSize'] = float('nan')
        with self.assertRaisesRegex(ValueError, 'actual span font'): self.validate()

    def test_rejects_unknown_target_and_missing_roundtrip(self):
        self.records[1]['action']['target'] = 'invented'
        with self.assertRaisesRegex(ValueError, 'Unknown text metric'): self.validate()
        self.setUp(); self.records.pop()
        with self.assertRaisesRegex(ValueError, 'roundtrip'): self.validate()


if __name__ == '__main__': unittest.main()
