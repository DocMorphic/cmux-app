#!/usr/bin/env python3
"""Actual native owner/production helper lost-receipt recovery and scope retirement."""
import argparse
import os
from pathlib import Path
import signal
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--node', required=True)
    parser.add_argument('--output', type=Path, default=Path('captures/runtime/push-native-recovery/check'))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    binary = output / 'recovery-fixture'
    sources = ['push/NativePushSourceFrame.swift', 'push/mac-native/NativePushSourceSink.swift',
        'push/mac-native/NativePushSourceDeliveryQueue.swift', 'push/mac-native/NativePushSourceProcessSink.swift',
        'push/mac-native/NativePushOwnerDescriptorRepository.swift', 'push/mac-native/NativePushSourceOwner.swift',
        'push/mac-native/NativePushCaptureRepository.swift', 'push/mac-native/NativePushSourceCaptureController.swift',
        'scripts/native-push-recovery-fixture.swift']
    build = subprocess.run(['/usr/bin/swiftc', '-swift-version', '6', *[str(root / p) for p in sources], '-o', str(binary)], capture_output=True, timeout=90)
    (output / 'build.log').write_bytes(build.stdout + build.stderr); build.check_returncode()
    def run(mode, temporary, helper, die=False):
        child = subprocess.Popen([str(binary), mode, args.node, str(root / 'scripts' / helper), temporary],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            data = child.communicate(timeout=30)[0]
            (output / f'{Path(temporary).name}-{mode}.log').write_bytes(data)
        finally:
            try: os.killpg(child.pid, signal.SIGKILL)
            except ProcessLookupError: pass
            child.wait()
        if die:
            if child.returncode != -signal.SIGKILL or b'encrypted original retained before SIGKILL' not in data:
                raise RuntimeError('Native owner did not reach committed lost-receipt checkpoint')
        elif child.returncode: raise subprocess.CalledProcessError(child.returncode, mode)
        print(data.decode().strip())
    for target in ['restore', 'restore-after-unavailable', 'restore-other-team', 'scope-aba', 'held-stop', 'held-team-hop', 'full-store']:
        with tempfile.TemporaryDirectory(prefix='cmux-recovery-', dir='/tmp') as temporary:
            prepared = subprocess.run([args.node, str(root / 'scripts/native-push-owner-fixture.mjs'), 'prepare', temporary], capture_output=True, timeout=20)
            prepared.check_returncode()
            if target != 'scope-aba':
                run('commit-and-die', temporary, 'native-push-recovery-child.mjs', die=True)
                run(target, temporary, 'cmux-push-helper.mjs')
            else: run(target, temporary, 'native-push-recovery-child.mjs')
            probe = subprocess.run([args.node, str(root / 'scripts/native-push-owner-fixture.mjs'),
                'verify-full-recovery' if target == 'full-store' else 'verify-recovery', temporary], capture_output=True, timeout=10)
            (output / f'{target}-journal.log').write_bytes(probe.stdout + probe.stderr); probe.check_returncode()
            print(probe.stdout.decode().strip())


if __name__ == '__main__':
    main()
