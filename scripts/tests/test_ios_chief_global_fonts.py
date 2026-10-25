"""Synthetic bridge/receipt guards; never substituted for native font observations."""
import copy
import hashlib
import importlib.util
import os
import json
import tempfile
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch, Mock

ROOT = Path(__file__).resolve().parents[2]
def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / filename)
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value); return value
DRIVER = module('global_font_driver', 'run-ios-chief-global-fonts.py')
VERIFY = module('global_font_verify', 'verify-ios-chief-rich-composer.py')
COMMIT = 'b' * 40
SIMULATOR = '11111111-1111-4111-8111-111111111111'
DIGEST = hashlib.sha256((ROOT / 'scripts/run-ios-chief-global-fonts.py').read_bytes()).hexdigest()
def request(index, target):
    return dict(schema=1, referenceKind='chiefGlobalFontRequest', id=f'00000000-0000-4000-8000-{index:012d}',
                sourceCommit=COMMIT, target=target)


class GlobalFontHostBridge(unittest.TestCase):
    def test_actual_commands_are_explicit_and_read_back(self):
        calls = []; replies = iter(['large\n', '', 'accessibility-medium\n'])
        def execute(command): calls.append(command); return next(replies)
        event = DRIVER.apply_request(request(1, 'accessibilityMedium'), COMMIT, SIMULATOR, execute, DIGEST)
        read = ['xcrun','simctl','ui',SIMULATOR,'content_size']
        self.assertEqual([read, read + ['accessibility-medium'], read], calls)
        self.assertEqual('applied', event['status']); self.assertEqual(DIGEST, event['driverSha256'])
        self.assertEqual('large', event['before']); self.assertEqual('accessibility-medium', event['after'])

    def test_rejects_unbound_requests_without_changing_system(self):
        for changes in [dict(sourceCommit='a' * 40), dict(target='shell-command'), dict(id='not-a-nonce'), dict(referenceKind='other')]:
            value = request(1, 'large'); value.update(changes); calls = []
            with self.assertRaises(ValueError): DRIVER.apply_request(value, COMMIT, SIMULATOR, lambda c: calls.append(c), DIGEST)
            self.assertFalse(calls)

    def test_rejects_wrong_system_readback(self):
        replies = iter(['large','', 'large'])
        with self.assertRaisesRegex(ValueError, 'readback'):
            DRIVER.apply_request(request(1,'accessibilityMedium'), COMMIT, SIMULATOR, lambda _: next(replies), DIGEST)

    def test_refuses_local_simulator_mutations(self):
        with patch.dict(os.environ, {'GITHUB_ACTIONS':'false'}):
            with self.assertRaisesRegex(ValueError, 'disposable hosted'): DRIVER.run(SimpleNamespace())

    def test_host_failure_still_restores_system_category(self):
        with tempfile.TemporaryDirectory() as folder:
            events = Path(folder) / 'events.json'; commands = []
            def execute(command, **kwargs):
                commands.append(command)
                if command[:4] == ['xcrun','simctl','list','devices']:
                    return json.dumps(dict(devices={'runtime':[dict(udid=SIMULATOR,isAvailable=True,state='Booted')]}))
                return 'large\n'
            process = Mock(); process.poll.return_value = 1; process.returncode = 1
            args = SimpleNamespace(simulator=SIMULATOR,source_commit=COMMIT,events=events,log=Path(folder)/'build.log',command=['--','xcodebuild','test'])
            with patch.dict(os.environ,{'GITHUB_ACTIONS':'true','RUNNER_TEMP':folder}), \
                 patch.object(DRIVER.subprocess,'check_output',side_effect=execute), patch.object(DRIVER.subprocess,'Popen',return_value=process):
                with self.assertRaisesRegex(RuntimeError,'xcodebuild failed'): DRIVER.run(args)
            receipt = json.loads(events.read_text()); self.assertTrue(receipt['restored'])
            self.assertEqual(1,receipt['xcodebuildExitCode'])
            self.assertIn(['xcrun','simctl','ui',SIMULATOR,'content_size','large'],commands)


    def test_container_observation_timeout_keeps_the_same_build_alive(self):
        with tempfile.TemporaryDirectory() as folder:
            container = Path(folder) / 'app'; bridge = container / 'Documents/ChiefGlobalFontBridge'
            bridge.mkdir(parents=True); DRIVER.atomic_json(bridge/'request.json',request(1,'accessibilityMedium'))
            current = 'large'; probes = 0
            def execute(command, **kwargs):
                nonlocal current, probes
                if command[:4] == ['xcrun','simctl','list','devices']:
                    return json.dumps(dict(devices={'runtime':[dict(udid=SIMULATOR,isAvailable=True,state='Booted')]}))
                if command[:3] == ['xcrun','simctl','get_app_container']:
                    probes += 1
                    if probes == 1: raise DRIVER.subprocess.TimeoutExpired(command,3)
                    return str(container)
                if len(command) == 6: current = command[-1]
                return current
            process = Mock(); process.poll.side_effect = [None,None,0,0]; process.returncode = 0
            args = SimpleNamespace(simulator=SIMULATOR,source_commit=COMMIT,events=Path(folder)/'events.json',
                                   log=Path(folder)/'build.log',command=['--','xcodebuild','test'])
            with patch.dict(os.environ,{'GITHUB_ACTIONS':'true','RUNNER_TEMP':folder}), \
                 patch.object(DRIVER.subprocess,'check_output',side_effect=execute), \
                 patch.object(DRIVER.subprocess,'Popen',return_value=process) as start, \
                 patch.object(DRIVER.time,'sleep'), patch.object(DRIVER.os,'killpg') as kill:
                DRIVER.run(args)
            start.assert_called_once(); kill.assert_not_called()
            receipt = json.loads(args.events.read_text())
            self.assertEqual(1,receipt['containerProbeTimeouts']); self.assertEqual(1,len(receipt['events']))
            self.assertTrue(receipt['restored']); self.assertEqual('large',current)



class GlobalFontExportGuards(unittest.TestCase):
    def setUp(self):
        events = []
        for index,target,before,after in [(1,'accessibilityMedium','large','accessibility-medium'),(2,'large','accessibility-medium','large')]:
            replies = iter([before,'',after])
            events.append(DRIVER.apply_request(request(index,target), COMMIT, SIMULATOR, lambda _: next(replies), DIGEST))
        self.bridge = dict(schema=1, sourceCommit=COMMIT, simulator=SIMULATOR, driverSha256=DIGEST,
            initialCategory='large', restoredCategory='large', restored=True, xcodebuildExitCode=0, events=events)
        def state(category, size): return dict(actualCategory=VERIFY.CATEGORIES[category],
            controllerCategory=VERIFY.CATEGORIES[category], editorCategory=VERIFY.CATEGORIES[category],
            attributes=[dict(attributes=dict(fontSize=size,fontName='SyntheticFont'))])
        self.records = [dict(action=dict(kind='initial'), state=state('large',17))]
        for event,size in zip(events,[28,17]):
            self.records.append(dict(action=dict(kind='systemMetricsCategory',target=event['target']),
                observed=dict(invocation='xcrun.simctl.ui.content_size',bridge=copy.deepcopy(event)),
                state=state(event['target'],size)))

    def validate(self): VERIFY.validate_system_font_categories(self.records,'large',COMMIT,self.bridge)

    def test_accepts_exact_host_readback_and_actual_editor_categories(self): self.validate()

    def test_rejects_override_without_actual_application_category(self):
        self.records[1]['state']['actualCategory'] = VERIFY.CATEGORIES['large']
        with self.assertRaisesRegex(ValueError, 'Global category'): self.validate()

    def test_rejects_unrestored_failed_or_changed_bridge(self):
        original = copy.deepcopy(self.bridge)
        for key,value in [('restored',False),('xcodebuildExitCode',1),('driverSha256','0' * 64),('sourceCommit','a'*40)]:
            self.bridge = copy.deepcopy(original); self.bridge[key] = value
            with self.assertRaisesRegex(ValueError, 'incomplete system bridge'): self.validate()

    def test_rejects_native_response_not_matching_actual_host_command(self):
        self.records[1]['observed']['bridge']['after']='large'
        with self.assertRaisesRegex(ValueError, 'transition differs'): self.validate()
        self.setUp(); self.bridge['events'][0]['command'][-1] = 'small'
        with self.assertRaisesRegex(ValueError, 'transition differs'): self.validate()

    def test_rejects_duplicate_identity_clock_and_missing_font(self):
        self.bridge['events'][1]['id'] = self.bridge['events'][0]['id']
        with self.assertRaisesRegex(ValueError, 'host event identity'): self.validate()
        self.setUp(); self.bridge['events'][1]['ended'] = -1
        with self.assertRaisesRegex(ValueError, 'event clock'): self.validate()
        self.setUp(); self.records[1]['state']['attributes'][0]['attributes']['fontSize'] = float('nan')
        with self.assertRaisesRegex(ValueError, 'actual global span font'): self.validate()


if __name__ == '__main__': unittest.main()
