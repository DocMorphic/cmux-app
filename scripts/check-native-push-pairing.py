#!/usr/bin/env python3
"""Compile the actual native pairing UI/client and execute model/QR pixel checks."""
import argparse
import os
import select
import signal
import subprocess
import tempfile
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=Path('captures/runtime/push-native-pairing/check'))
    parser.add_argument('--node', default='node')
    parser.add_argument('--patched-app-delegate', type=Path, help='Typecheck the exact injected composition against actual native owner/UI types')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    sources = ['push/mac-native/NativePushPairingClient.swift', 'push/mac-native/NativePushPairingModel.swift',
               'push/mac-native/NativePushPairingView.swift', 'push/mac-native/NativePushPairingPresentation.swift',
               'push/mac-native/NativePushPairingCommands.swift',
               'scripts/native-push-pairing-fixture.swift']
    if args.patched_app_delegate:
        app = args.patched_app_delegate.read_text()
        start = app.index('        if let owner = nativeAndroidPushOwner, nativeAndroidPushPairing == nil {\n')
        end = app.index('        nativeAndroidPushPairing?.installMenu()\n', start)
        fragment = app[start:end]
        composition = output / 'composition.swift'
        composition.write_text('import Foundation\n@MainActor func pairingComposition(nativeAndroidPushOwner: NativePushSourceOwner?) -> NativePushPairingPresentation? {\n'
                               'var nativeAndroidPushPairing: NativePushPairingPresentation?\n' + fragment + '\nreturn nativeAndroidPushPairing\n}\n')
        owner_sources = ['push/NativePushSourceFrame.swift', 'push/mac-native/NativePushSourceSink.swift',
                         'push/mac-native/NativePushSourceDeliveryQueue.swift', 'push/mac-native/NativePushSourceProcessSink.swift',
                         'push/mac-native/NativePushOwnerDescriptorRepository.swift', 'push/mac-native/NativePushCaptureRepository.swift',
                         'push/mac-native/NativePushSourceCaptureController.swift', 'push/mac-native/NativePushSourceOwner.swift']
        build = subprocess.run(['/usr/bin/swiftc', '-swift-version', '6', '-typecheck',
                                *[str(root / path) for path in sources[:-1] + owner_sources], str(composition)], capture_output=True, timeout=90)
        (output / 'composition-build.log').write_bytes(build.stdout + build.stderr); build.check_returncode()
    binary = output / 'pairing-fixture'
    build = subprocess.run(['/usr/bin/swiftc', '-swift-version', '6', *[str(root / path) for path in sources],
                            '-o', str(binary)], capture_output=True, timeout=90)
    (output / 'build.log').write_bytes(build.stdout + build.stderr); build.check_returncode()
    result = subprocess.run([str(binary)], capture_output=True, timeout=15)
    (output / 'behavior.log').write_bytes(result.stdout + result.stderr); result.check_returncode()
    print(result.stdout.decode().strip())
    for mode in ['hold', 'oversized', 'refused', 'public']:
        with tempfile.TemporaryDirectory(prefix='cmux-pair-', dir='/tmp') as temporary:
            path = str(Path(temporary) / 'owner.sock')
            server = subprocess.Popen([args.node, str(root / 'scripts/native-push-pairing-socket-fixture.mjs'), path, mode],
                                      stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
            try:
                if not select.select([server.stdout], [], [], 5)[0] or server.stdout.readline() != b'ready\n':
                    raise RuntimeError('Pairing socket fixture failed to start')
                result = subprocess.run([str(binary), 'client-cancel' if mode == 'hold' else 'client-refuse', path],
                                        capture_output=True, timeout=6)
                (output / f'socket-{mode}.log').write_bytes(result.stdout + result.stderr); result.check_returncode()
                print(result.stdout.decode().strip())
            finally:
                try: os.killpg(server.pid, signal.SIGKILL)
                except ProcessLookupError: pass
                server.communicate(timeout=5)


if __name__ == '__main__':
    main()
