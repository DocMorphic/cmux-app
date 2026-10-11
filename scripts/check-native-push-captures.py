#!/usr/bin/env python3
"""Compile actual encrypted capture storage and verify committed SIGKILL recovery."""
import argparse
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=Path('captures/runtime/push-native-captures/check'))
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    binary = output / 'capture-fixture'
    sources = ['push/mac-native/NativePushCaptureRepository.swift', 'push/mac-native/NativePushCaptureKeyStore.swift',
               'scripts/native-push-capture-fixture.swift']
    build = subprocess.run(['/usr/bin/swiftc', '-swift-version', '6', *[str(root / p) for p in sources], '-o', str(binary)],
                           capture_output=True, timeout=90)
    (output / 'build.log').write_bytes(build.stdout + build.stderr); build.check_returncode()
    with tempfile.TemporaryDirectory(prefix='cmux-native-capture-', dir='/tmp') as temporary:
        os.chmod(temporary, 0o700)
        commit = subprocess.run([str(binary), 'commit-and-die', temporary], capture_output=True, timeout=15)
        (output / 'committed-sigkill.log').write_bytes(commit.stdout + commit.stderr)
        if commit.returncode != -signal.SIGKILL or b'durable capture committed before SIGKILL' not in commit.stdout:
            raise RuntimeError('Capture fixture did not commit before its intentional SIGKILL')
        verify = subprocess.run([str(binary), 'verify', temporary], capture_output=True, timeout=15)
        (output / 'behavior.log').write_bytes(verify.stdout + verify.stderr); verify.check_returncode()
        print(verify.stdout.decode().strip())
    (output / 'receipt.json').write_text(json.dumps({'kind': 'native-capture-storage-verified', 'sources': sources,
        'scope': 'Actual Swift 6/CryptoKit encrypted repository compiled/executed across committed SIGKILL; synthetic key closure. Keychain implementation compiled only; no production wiring, fresh-authority replay, whole app or provider/device acceptance.'}, indent=2) + '\n')


if __name__ == '__main__':
    main()
