"""Synthetic integrity tests, not evidence of actual UIKit animation behavior."""
import copy
import importlib.util
from pathlib import Path
import unittest

SPEC = importlib.util.spec_from_file_location('header', Path(__file__).resolve().parents[1] / 'verify-ios-chief-header.py')
HEADER = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(HEADER)


class HeaderIntegrityTests(unittest.TestCase):
    def setUp(self):
        self.manifest = {'revision': 'upstream', 'referencePackage': {'tree': 'tree'}}
        def state(t, opened=False):
            control = dict(label='Back', modelOpacity=1, presentationOpacity=1, presentationLayers=3,
                hiddenAncestor=False, axHiddenAncestor=False, isAccessibilityElement=True,
                enabled=True, interactionEnabled=True, hitControl=True, symbol=True,
                hitClass='UIButton', frame=[12, 20, 44, 44], imageSize=[17, 17])
            return dict(elapsed=t, back=control, trailing=dict(control, label='Close' if opened else 'Stop Chief'),
                overlayPresent=opened, backingAXHidden=opened, editorFocused=True, draftPreserved=True, animationsEnabled=True)
        cases = []
        for name, (duration, reopens) in HEADER.CASES.items():
            phases = [('enter', duration), ('exit', .85)]
            if reopens:
                phases = [('enter', duration), ('exit-before-reopen', .01 if name == 'reopen-glyph-swap' else .10), ('reopen', .85), ('final-exit', .85)]
            records = []
            for phase, duration in phases:
                count = max(1, round(duration / .01))
                samples = [state(duration * i / count, phase in ('enter', 'reopen')) for i in range(count + 1)]
                records.append(dict(name=phase, requestedDuration=duration, samples=samples))
            cases.append(dict(name=name, initial=state(0), final=state(0), phases=records))
        self.value = dict(schema=1, kind='chiefReplyHeaderTimeline', reduceMotion=False, osVersion='26.4.1', os='build',
            category='UICTContentSizeCategoryL', scale=3, viewport=[402, 874], fixtureSha256='fixture', cases=cases,
            provenance=dict(projectSourceCommit='commit', projectSourceDirty=False, upstreamRevision='upstream', packageTree='tree',
                referenceTestsSha256='test', referenceAppSha256='app', scenesSha256='fixture'))

    def verify(self, value=None):
        return HEADER.validate(value or self.value, 'commit', self.manifest, 'fixture', 'test', 'app')

    def test_complete_timeline_records_all_cases_and_actual_runtime_branch(self):
        self.assertEqual(5, len(self.verify()['cases']))
        self.assertEqual('iOS26-or-earlier', self.verify()['runtimeBranch'])
        self.value['osVersion'] = '27.0'
        self.assertEqual('iOS27+', self.verify()['runtimeBranch'])

    def test_source_and_fixture_tampering_are_rejected(self):
        for key in ('projectSourceCommit', 'upstreamRevision', 'packageTree', 'referenceTestsSha256', 'referenceAppSha256', 'scenesSha256'):
            with self.subTest(key=key):
                value = copy.deepcopy(self.value); value['provenance'][key] = 'wrong'
                with self.assertRaises(ValueError): self.verify(value)
        self.value['provenance']['projectSourceDirty'] = True
        with self.assertRaises(ValueError): self.verify()

    def test_disabled_animation_or_lost_editor_state_is_rejected(self):
        for key in ('animationsEnabled', 'draftPreserved', 'editorFocused'):
            value = copy.deepcopy(self.value); value['cases'][0]['phases'][0]['samples'][1][key] = False
            with self.subTest(key=key), self.assertRaises(ValueError): self.verify(value)
        self.value['reduceMotion'] = True
        with self.assertRaises(ValueError): self.verify()

    def test_missing_or_duplicate_case_is_rejected(self):
        value = copy.deepcopy(self.value); value['cases'].pop()
        with self.assertRaises(ValueError): self.verify(value)
        self.value['cases'][-1] = copy.deepcopy(self.value['cases'][0])
        with self.assertRaises(ValueError): self.verify()

    def test_contaminated_starting_opacity_is_rejected(self):
        self.value['cases'][1]['initial']['trailing']['presentationOpacity'] = 0
        with self.assertRaisesRegex(ValueError, 'fresh visible baseline'): self.verify()

    def test_short_reopen_interval_can_have_one_immediate_source_observation(self):
        phase = self.value['cases'][-1]['phases'][1]
        phase['samples'] = [phase['samples'][-1]]
        self.verify()

    def test_reordered_or_incomplete_phases_are_rejected(self):
        self.value['cases'][0]['phases'].reverse()
        with self.assertRaises(ValueError): self.verify()

    def test_incomplete_nonmonotonic_or_sparse_sampling_is_rejected(self):
        for mode in ('short', 'backward', 'gap', 'late', 'noLayers'):
            value = copy.deepcopy(self.value); samples = value['cases'][0]['phases'][0]['samples']
            if mode == 'short': del samples[-3:]
            elif mode == 'backward': samples[2]['elapsed'] = samples[1]['elapsed']
            elif mode == 'gap': del samples[10:40]
            elif mode == 'late':
                for s in samples: s['elapsed'] += .2
            else:
                for s in samples: s['trailing']['presentationLayers'] = 0
            with self.subTest(mode=mode), self.assertRaises(ValueError): self.verify(value)

    def test_nonfinite_or_boolean_geometry_and_opacity_are_rejected(self):
        for field, bad in (('presentationOpacity', float('nan')), ('modelOpacity', True), ('frame', [0, 0, 0, 44]), ('imageSize', [])):
            value = copy.deepcopy(self.value); value['cases'][0]['phases'][0]['samples'][0]['trailing'][field] = bad
            with self.subTest(field=field), self.assertRaises(ValueError): self.verify(value)

    def test_real_reply_and_backing_accessibility_lifetimes_are_required(self):
        for phase, key, bad in ((0, 'overlayPresent', False), (0, 'backingAXHidden', False), (1, 'backingAXHidden', True)):
            value = copy.deepcopy(self.value); value['cases'][0]['phases'][phase]['samples'][0][key] = bad
            with self.subTest(phase=phase, key=key), self.assertRaises(ValueError): self.verify(value)

    def test_interrupted_opacity_and_hit_test_results_remain_observations(self):
        for case in self.value['cases']:
            for phase in case['phases']:
                for sample in phase['samples']:
                    sample['trailing'].update(presentationOpacity=0, hitControl=False)
            case['final']['trailing'].update(presentationOpacity=0, hitControl=False)
        self.assertEqual(0, self.verify()['cases'][0]['phases'][-1]['finalTrailingOpacity'])


if __name__ == '__main__':
    unittest.main()
